---
phase: 25-backend-logic-fixes
plan: 02
subsystem: auth
tags: [rbac, system-tables, row-filtering, privilege-scoping]

# Dependency graph
requires:
  - phase: 25-backend-logic-fixes-01
    provides: "listMembershipsByUser() in RbacService"
provides:
  - "User-scoped sys.privileges filtering (non-admin sees only own roles' grants)"
  - "User-scoped sys.membership filtering (non-admin sees only own memberships)"
  - "getUserRoleIds() method on RbacService"
  - "sys.roles remains admin-only (renamed guard method)"
affects: [26-end-to-end-verification]

# Tech tracking
tech-stack:
  added: []
  patterns: [stream-based-iterator-wrapping, instanceof-cast-for-plugin-context]

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
    - sabot/kernel/src/main/java/com/dremio/exec/store/sys/SystemTableScanCreator.java

key-decisions:
  - "Cast AccessControlListingManager to RbacService via instanceof instead of adding getRbacService() to PluginSabotContext interface"
  - "Filter sys.membership by member_name equals query user; filter sys.privileges by grantee in user's roleIds set"
  - "Only sys.roles remains admin-only; sys.privileges and sys.membership open to all users with row scoping"

patterns-established:
  - "Iterator wrapping via StreamSupport.stream + Spliterators for user-scoped system table filtering"

requirements-completed: [LOGIC-02]

# Metrics
duration: 3min
completed: 2026-03-11
---

# Phase 25 Plan 02: User-Scoped System Table Access Summary

**Non-admin users can now query sys.privileges and sys.membership with row-level filtering -- sees only own roles' grants and own memberships**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-11T15:00:05Z
- **Completed:** 2026-03-11T15:03:21Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Removed admin-only block for sys.privileges and sys.membership in CatalogImpl (renamed to isRbacDeniedForSysRoles, only guards sys.roles)
- Added getUserRoleIds() to RbacService for efficient role set lookup
- Implemented user-scoped row filtering in SystemTableScanCreator for both PRIVILEGES and MEMBERSHIP tables
- Admin users and RBAC-disabled deployments see all rows unchanged (no regression)

## Task Commits

Each task was committed atomically:

1. **Task 1: Remove admin-only block and add user-role lookup method to RbacService** - `8f599a907` (fix)
2. **Task 2: Add user-scoped row filtering for PRIVILEGES and MEMBERSHIP in SystemTableScanCreator** - `143e4add1` (fix)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` - Renamed isRbacDeniedForSysPrivileges to isRbacDeniedForSysRoles; now only blocks sys.roles for non-admin
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` - Added getUserRoleIds(userName) returning explicit role IDs + PUBLIC
- `sabot/kernel/src/main/java/com/dremio/exec/store/sys/SystemTableScanCreator.java` - Added filterRbacSystemTableByUser, filterMembershipByUser, filterPrivilegesByUserRoles methods

## Decisions Made
- Used instanceof cast from AccessControlListingManager to RbacService rather than modifying the PluginSabotContext interface -- avoids cross-cutting interface change while safely accessing RBAC methods
- Filter sys.membership by exact member_name match against query user; filter sys.privileges by checking grantee against user's complete role set (explicit + PUBLIC)
- Preserved SystemUser bypass and three-way null guard patterns from Phase 21

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] PluginSabotContext does not expose getRbacService()**
- **Found during:** Task 2 (SystemTableScanCreator filtering)
- **Issue:** Plan assumed plugin2.getSabotContext().getRbacService() would work, but PluginSabotContext interface only has getAccessControlListingManager()
- **Fix:** Used getAccessControlListingManager() with instanceof RbacService check and cast
- **Files modified:** SystemTableScanCreator.java
- **Verification:** Code compiles and maintains type safety with instanceof guard
- **Committed in:** 143e4add1 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Interface adaptation necessary for correctness. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- LOGIC-02 complete: non-admin users can query sys.membership and sys.privileges with user-scoped filtering
- sys.roles remains properly admin-only
- Ready for Phase 26 end-to-end verification

## Self-Check: PASSED

All files verified present. All commits verified in git log.

---
*Phase: 25-backend-logic-fixes*
*Completed: 2026-03-11*
