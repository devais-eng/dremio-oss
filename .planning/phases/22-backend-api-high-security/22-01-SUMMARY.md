---
phase: 22-backend-api-high-security
plan: 01
subsystem: api
tags: [rbac, authorization, collaboration, scripts, privilege-enforcement]

# Dependency graph
requires:
  - phase: 21-backend-api-critical-security
    provides: "Established RBAC guard pattern (three-way null guard, admin bypass, entity-type dispatch)"
provides:
  - "RBAC ALTER privilege enforcement on CollaborationResource setTags/setWiki"
  - "createdBy parameter restriction on ScriptsResource getScripts for non-admin users"
affects: [23-backend-api-high-security]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "CollaborationResource entity-type-aware ALTER privilege enforcement"
    - "ScriptsResource createdBy parameter override for non-admin callers"

key-files:
  created: []
  modified:
    - "dac/backend/src/main/java/com/dremio/dac/api/CollaborationResource.java"
    - "dac/backend/src/main/java/com/dremio/dac/api/ScriptsResource.java"

key-decisions:
  - "Versioned (Nessie) entities skip RBAC check in CollaborationResource since they have no namespace entry"
  - "Source tags/wiki mutation is admin-only (non-admin users cannot modify source metadata)"
  - "Folder ALTER privilege resolves to parent space (objectType=SPACE, objectPath=first path component)"

patterns-established:
  - "CollaborationResource enforceAlterPrivilege(): entity-type-aware ALTER check with VDS/PDS/SPACE/FOLDER/SOURCE dispatch"
  - "ScriptsResource createdBy override: non-admin forced to own username, admin can query any user"

requirements-completed: [API-03, API-04]

# Metrics
duration: 3min
completed: 2026-03-11
---

# Phase 22 Plan 01: Collaboration and Scripts API RBAC Enforcement Summary

**RBAC ALTER privilege enforcement on Collaboration API mutation endpoints; Scripts API createdBy parameter restricted to current user for non-admins**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-11T13:37:40Z
- **Completed:** 2026-03-11T13:40:54Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- CollaborationResource now enforces ALTER privilege before allowing setTags and setWiki mutations, with entity-type-aware dispatch (VDS/PDS/SPACE/FOLDER/SOURCE)
- ScriptsResource now overrides the createdBy query parameter to the current user for non-admin callers, preventing script enumeration across users
- Both changes follow the established Phase 21 RBAC guard pattern (three-way null guard, admin bypass)
- RBAC-disabled deployments remain completely unaffected

## Task Commits

Each task was committed atomically:

1. **Task 1: Add RBAC ALTER privilege enforcement to CollaborationResource setTags and setWiki** - `4f7388965` (fix)
2. **Task 2: Restrict Scripts API createdBy parameter to current user for non-admins** - `c905ef0a8` (fix)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/api/CollaborationResource.java` - Added RbacService/DremioConfig/NamespaceService/SecurityContext injection; enforceAlterPrivilege() with entity-type-aware ALTER check on setTags/setWiki
- `dac/backend/src/main/java/com/dremio/dac/api/ScriptsResource.java` - Added RbacService/DremioConfig/SecurityContext injection; createdBy override in getScripts() for non-admin callers

## Decisions Made
- Versioned (Nessie) entities skip RBAC enforcement in CollaborationResource because they have no namespace entry; this is consistent with CatalogServiceHelper behavior
- Source metadata mutation (tags/wiki on SOURCE entities) throws unconditional 403 for non-admin users, since sources are infrastructure-level objects
- Folder ALTER checks resolve to the parent space rather than the folder itself, consistent with Dremio's space-level permission model

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 22 Plan 02 can proceed (remaining high-security API endpoints)
- Collaboration and Scripts API RBAC patterns established and ready for reference

## Self-Check: PASSED

- All modified files exist on disk
- All commit hashes verified in git log
- SUMMARY.md created at expected path

---
*Phase: 22-backend-api-high-security*
*Completed: 2026-03-11*
