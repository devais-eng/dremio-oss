---
phase: 21-backend-api-critical-security
plan: 01
subsystem: api
tags: [rbac, security, authorization, jax-rs, java]

# Dependency graph
requires:
  - phase: v1.3-privilege-context-enforcement
    provides: "RbacService.hasPrivilege() and isAdminMember() methods, established RBAC guard pattern"
provides:
  - "Admin-only @RolesAllowed on UserResource createUser() and updateUser()"
  - "RBAC privilege enforcement helpers enforceCreatePrivilege(), enforceUpdatePrivilege(), enforceDeletePrivilege() in CatalogServiceHelper"
  - "Inline RBAC guard in refreshCatalogItem() requiring ALTER privilege"
affects: [catalog-api, user-api, rbac-enforcement]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Three-way null guard (rbacService == null || dremioConfig == null || !dremioConfig.getBoolean(RBAC_ENABLED)) used for RBAC early-return"
    - "Private helper methods (enforceXxxPrivilege) encapsulate entity-type-aware privilege checks before mutation dispatch"
    - "Admin short-circuit via rbacService.isAdminMember() before privilege checks"

key-files:
  created: []
  modified:
    - dac/backend/src/main/java/com/dremio/dac/api/UserResource.java
    - dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java

key-decisions:
  - "UserResource write methods use JAX-RS @RolesAllowed(admin) — enforcement is declarative and pre-route, no code needed in method body"
  - "CatalogServiceHelper uses inverted early-return null guard (rbacService == null → return) in helpers vs. positive guard (rbacService != null) in inline checks — both patterns are equivalent, helpers prefer early-return for clarity"
  - "Dataset CREATE_VIEW check in enforceCreatePrivilege is belt-and-suspenders over createDataset() which calls catalog.validateCreateViewPrivilege() — kept for fail-fast before calling into catalog layer"
  - "Source create/update are admin-only at the method level (createSource already guarded; enforceUpdatePrivilege blocks non-admin source update)"
  - "refreshCatalogItem uses inline guard instead of helper because it operates on DatasetConfig (resolved entity), not CatalogEntity"

patterns-established:
  - "Mutation method RBAC pattern: private enforceXxxPrivilege(entity) helper called at method entry, before any instanceof dispatch"
  - "Entity-type-aware privilege dispatch: Space/Source=admin-only, Dataset/Folder/Function=specific privilege check on parent container"

requirements-completed: [API-01, API-02]

# Metrics
duration: 3min
completed: 2026-03-11
---

# Phase 21 Plan 01: Backend API Critical Security Summary

**Closed two critical write-path auth holes: UserResource now requires admin for createUser/updateUser, CatalogServiceHelper now enforces RBAC privilege checks on all catalog mutation paths (create/update/delete/refresh)**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-11T13:17:00Z
- **Completed:** 2026-03-11T13:20:00Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Non-admin POST /api/v3/user now returns 403 (JAX-RS @RolesAllowed annotation change)
- Non-admin PUT /api/v3/user/{id} now returns 403 (JAX-RS @RolesAllowed annotation change)
- Non-admin catalog create/update/delete/refresh now requires RBAC privilege or admin role
- Three private helpers (enforceCreatePrivilege, enforceUpdatePrivilege, enforceDeletePrivilege) added to CatalogServiceHelper with entity-type-aware dispatch
- RBAC-disabled deployments are unaffected (three-way null guard)
- Admin users always pass via isAdminMember() short-circuit (no regression)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add admin-only annotations to UserResource write methods (API-01)** - `3cf7542ca` (fix)
2. **Task 2: Add RBAC privilege enforcement to CatalogServiceHelper mutation methods (API-02)** - `cef79e4a4` (fix)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/api/UserResource.java` - Changed @RolesAllowed on createUser() and updateUser() from {"admin","user"} to "admin"
- `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` - Added enforceCreatePrivilege(), enforceUpdatePrivilege(), enforceDeletePrivilege() helpers and inline RBAC guard in refreshCatalogItem()

## Decisions Made
- JAX-RS @RolesAllowed annotation used for UserResource (declarative, pre-route enforcement, consistent with v2 UsersResource.java pattern)
- Private helper methods encapsulate entity-type dispatch; called at top of createCatalogItem(entity, option), updateCatalogItem, deleteCatalogItem
- refreshCatalogItem uses inline guard (not helper) because the resolved DatasetConfig is needed to determine VDS vs PDS for privilege object type
- Source CREATE is already guarded inside createSource(); enforceCreatePrivilege returns early for Source to avoid double-check

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

Pre-existing compile errors exist in the codebase (`getAccessibleObjectPaths` and `hasAccessibleChildUnderPath` method calls on RbacService that don't exist). These are pre-existing and not caused by this plan's changes. They are documented in deferred-items.md scope note.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- API-01 and API-02 requirements are complete
- Ready for Phase 21 Plan 02 (remaining phases of 21-backend-api-critical-security)
- Pre-existing build errors in SpaceFolderResource, SpaceResource, ResourceTreeResource should be addressed in a separate task

---
*Phase: 21-backend-api-critical-security*
*Completed: 2026-03-11*

## Self-Check: PASSED

- FOUND: .planning/phases/21-backend-api-critical-security/21-01-SUMMARY.md
- FOUND: dac/backend/src/main/java/com/dremio/dac/api/UserResource.java
- FOUND: dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java
- FOUND: commit 3cf7542ca (fix(21-01): restrict createUser and updateUser to admin-only in UserResource)
- FOUND: commit cef79e4a4 (fix(21-01): add RBAC privilege enforcement to CatalogServiceHelper mutation methods)
