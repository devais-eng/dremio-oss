---
phase: 22-backend-api-high-security
plan: 02
subsystem: api
tags: [rbac, security, authorization, jax-rs, java, reflection, folder, space]

# Dependency graph
requires:
  - phase: 21-backend-api-critical-security
    provides: "Established RBAC guard pattern (three-way null guard, admin bypass, privilege check) in CatalogServiceHelper"
provides:
  - "RBAC CREATE_FOLDER and ALTER privilege enforcement on SpaceFolderResource folder mutations"
  - "RBAC ALTER privilege enforcement on ReflectionResource reflection mutations (create, edit, delete)"
  - "enforceAlterOnDataset() helper resolving dataset ID to namespace path for privilege check"
affects: [rbac-enforcement, reflection-api, space-folder-api]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "enforceSpacePrivilege(privilege) helper for space-scoped mutations with parameterized privilege name"
    - "enforceAlterOnDataset(datasetId) helper resolving namespace entity from dataset UUID, dispatching VDS/PDS object type"
    - "Versioned dataset (Nessie) skip in RBAC enforcement -- no namespace entry available for privilege check"

key-files:
  created: []
  modified:
    - dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java
    - dac/backend/src/main/java/com/dremio/dac/api/ReflectionResource.java

key-decisions:
  - "SpaceFolderResource uses enforceSpacePrivilege() with parameterized privilege -- CREATE_FOLDER for createFolder, ALTER for deleteFolder"
  - "ReflectionResource resolves dataset from namespace via EntityId(datasetId) to determine VDS vs PDS for objectType in privilege check"
  - "Versioned datasets (Nessie) are skipped in RBAC enforcement -- no namespace entry exists, so privilege cannot be checked"
  - "deleteReflection resolves the reflection goal first to get datasetId, since the API only receives the reflection ID"
  - "Static createReflectionHelper left untouched -- RBAC check added in the instance method createReflection() before delegation"

patterns-established:
  - "Space-scoped mutation RBAC: enforceSpacePrivilege(privilege) with spaceName.getName() as object path"
  - "Dataset-scoped mutation RBAC: enforceAlterOnDataset(datasetId) with namespace resolution and VDS/PDS dispatch"

requirements-completed: [API-05, API-06]

# Metrics
duration: 3min
completed: 2026-03-11
---

# Phase 22 Plan 02: SpaceFolderResource and ReflectionResource RBAC Enforcement Summary

**RBAC privilege enforcement on folder mutations (CREATE_FOLDER/ALTER on parent space) and reflection mutations (ALTER on underlying dataset with namespace resolution and VDS/PDS dispatch)**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-11T13:43:52Z
- **Completed:** 2026-03-11T13:47:03Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Non-admin POST /space/{space}/folder/{path} now requires CREATE_FOLDER privilege on parent space
- Non-admin DELETE /space/{space}/folder/{path} now requires ALTER privilege on parent space
- Non-admin POST /api/v3/reflection now requires ALTER privilege on underlying dataset
- Non-admin PUT /api/v3/reflection/{id} now requires ALTER privilege on underlying dataset
- Non-admin DELETE /api/v3/reflection/{id} now requires ALTER privilege on underlying dataset (resolved from reflection goal)
- Admin users bypass all checks via isAdminMember() short-circuit
- RBAC-disabled deployments unaffected (three-way null guard)
- Versioned datasets (Nessie) gracefully skipped (no namespace entry)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add RBAC privilege enforcement to SpaceFolderResource createFolder and deleteFolder (API-05)** - `f524d513a` (fix)
2. **Task 2: Add RBAC ALTER privilege enforcement to ReflectionResource create, edit, and delete (API-06)** - `855572804` (fix)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java` - Added enforceSpacePrivilege() helper; createFolder enforces CREATE_FOLDER, deleteFolder enforces ALTER on parent space
- `dac/backend/src/main/java/com/dremio/dac/api/ReflectionResource.java` - Added SecurityContext, RbacService, DremioConfig, NamespaceService to constructor; added enforceAlterOnDataset() helper; create/edit/delete enforce ALTER on underlying dataset

## Decisions Made
- SpaceFolderResource uses parameterized enforceSpacePrivilege(privilege) rather than separate methods per operation -- cleaner since only the privilege name differs
- ReflectionResource resolves dataset via namespaceService.getEntityById(new EntityId(datasetId)) to determine full path and VDS/PDS type for privilege check
- Versioned datasets (Nessie) skipped in RBAC enforcement because they have no namespace entry -- this is consistent with the deferred Nessie RBAC work
- deleteReflection resolves the reflection goal first via reflectionServiceHelper.getReflectionById(id) to get the datasetId, since the REST API only receives the reflection ID
- Static createReflectionHelper left untouched -- RBAC check is in the instance method before delegation to avoid modifying a static method with no instance dependencies

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- API-05 and API-06 requirements complete
- Phase 22 (Backend API High Security) is now fully complete (plans 01 and 02 both done)
- Ready for next milestone phase if applicable

---
*Phase: 22-backend-api-high-security*
*Completed: 2026-03-11*

## Self-Check: PASSED

- FOUND: dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java
- FOUND: dac/backend/src/main/java/com/dremio/dac/api/ReflectionResource.java
- FOUND: .planning/phases/22-backend-api-high-security/22-02-SUMMARY.md
- FOUND: commit f524d513a (fix(22-02): add RBAC privilege enforcement to SpaceFolderResource folder mutations)
- FOUND: commit 855572804 (fix(22-02): add RBAC ALTER privilege enforcement to ReflectionResource mutations)
