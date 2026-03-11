---
phase: 25-backend-logic-fixes
plan: 01
subsystem: api
tags: [rbac, space-resource, view-handler, auto-grant, dataset-count]

# Dependency graph
requires:
  - phase: 21-backend-api-critical-security
    provides: RBAC privilege enforcement on catalog write paths
provides:
  - RBAC-aware dataset count computation in SpaceResource
  - Auto-grant SELECT/ALTER/DROP to view creator after CREATE VIEW
  - listMembershipsByUser() API on RbacService
affects: [26-information-disclosure-fix, 25-backend-logic-fixes]

# Tech tracking
tech-stack:
  added: []
  patterns: [three-way-null-guard-for-rbac, rbac-filtered-count-over-namespace-count, auto-grant-on-create]

key-files:
  created: []
  modified:
    - dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java

key-decisions:
  - "Compute dataset count from RBAC-filtered children list instead of namespaceService.getDatasetCount() for non-admin users"
  - "Always fetch children list in getSpace() regardless of includeContents flag, since RBAC filtering already requires it"
  - "Grant view creator privileges to all explicit roles (not just first role), with PUBLIC fallback when no explicit roles exist"
  - "Added listMembershipsByUser() to RbacService to avoid scanning all memberships via getMembershipInfo()"

patterns-established:
  - "Auto-grant pattern: after creating a new RBAC-controlled object, grant the creator basic access privileges"
  - "RBAC-filtered count: when displaying counts of RBAC-controlled items, derive count from filtered list instead of unfiltered namespace count"

requirements-completed: [LOGIC-01, LOGIC-03]

# Metrics
duration: 4min
completed: 2026-03-11
---

# Phase 25 Plan 01: Backend Logic Fixes Summary

**RBAC-aware dataset count in space listings and auto-grant SELECT/ALTER/DROP to view creators on CREATE VIEW**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-11T14:53:16Z
- **Completed:** 2026-03-11T14:57:19Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- SpaceResource.getSpace() now returns dataset count based on RBAC-filtered children for non-admin users, fixing misleading counts
- CreateOrUpdateViewHandler auto-grants SELECT, ALTER, and DROP privileges to the view creator's roles after both versioned and non-versioned view creation
- Admin users and RBAC-disabled deployments are unaffected (optimized code paths preserved)

## Task Commits

Each task was committed atomically:

1. **Task 1: Compute RBAC-aware dataset count in SpaceResource (LOGIC-01)** - `6f1ef4919` (fix)
2. **Task 2: Auto-grant SELECT, ALTER, DROP to view creator in CreateOrUpdateViewHandler (LOGIC-03)** - `02aeabf21` (fix)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java` - RBAC-aware dataset count in getSpace() using filtered children instead of namespace count
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java` - Auto-grant SELECT/ALTER/DROP to creator's roles after view creation
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` - Added listMembershipsByUser() for efficient user role membership lookup

## Decisions Made
- Used RBAC-filtered children list for dataset count instead of a separate filtered query -- avoids double-fetching the children since filterByRbacVisibility() is already called
- Always fetch children list in getSpace() even when includeContents=false -- necessary for accurate RBAC-aware count, acceptable performance trade-off since RBAC filtering already requires the list
- Grant to ALL explicit roles (not just first) -- ensures creator access regardless of which role context they use
- Added listMembershipsByUser() to RbacService rather than using getMembershipInfo() full scan -- cleaner API and O(n) on user's memberships instead of all memberships
- Handle RbacEntityAlreadyExistsException silently for idempotent CREATE OR REPLACE scenarios

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added listMembershipsByUser() to RbacService**
- **Found during:** Task 2 (Auto-grant view creator privileges)
- **Issue:** RbacService had no public API to look up a user's role memberships directly. The only alternative was getMembershipInfo() which scans all memberships across all users.
- **Fix:** Added `listMembershipsByUser(String userName)` method delegating to `membershipStore.listByUser(userName)`
- **Files modified:** `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java`
- **Verification:** Method exists and is called from CreateOrUpdateViewHandler.autoGrantCreatorPrivileges()
- **Committed in:** 02aeabf21 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Single-method addition to RbacService API surface. No scope creep -- necessary for efficient user membership lookup.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- LOGIC-01 and LOGIC-03 are fixed. Plan 25-02 (user-scoped sys tables) is ready to proceed.
- The new listMembershipsByUser() API is available for Plan 25-02 if needed for membership scoping.

---
*Phase: 25-backend-logic-fixes*
*Completed: 2026-03-11*

## Self-Check: PASSED
- All 3 modified files exist on disk
- Both task commits (6f1ef4919, 02aeabf21) verified in git log
- SUMMARY.md created at expected path
