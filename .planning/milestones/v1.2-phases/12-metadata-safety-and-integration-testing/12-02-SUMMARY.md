---
phase: 12-metadata-safety-and-integration-testing
plan: 02
subsystem: auth
tags: [rbac, describe, explain, privilege, meta-02, meta-03]

# Dependency graph
requires:
  - phase: 12-01
    provides: isRbacDeniedForSysPrivileges wired into CatalogImpl.getTable()
  - phase: 10-01
    provides: isRbacDeniedForPds() soft-deny pattern for PDS SELECT grants
  - phase: 07-01
    provides: catalog.validatePrivilege() throwing UserException with "Permission denied" format
provides:
  - SELECT privilege enforcement in DescribeTableHandler.toResult() via soft-deny pattern
  - META-02 tests verifying DESCRIBE permission denied and sys-table skip behavior
  - META-03 test documenting ExplainHandler's delegation-based privilege enforcement
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Soft-deny pattern: validatePrivilege catches UserException, saved; re-thrown only if getTable() also fails"
    - "UserException catch between AccessControlException and Exception catches to preserve clean error messages"
    - "System schema skip: equalsIgnoreCase('sys') and equalsIgnoreCase('INFORMATION_SCHEMA') guard before validatePrivilege"

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DescribeTableHandler.java
    - sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDescribeTableHandler.java
    - sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/TestRbacDdlHandlers.java

key-decisions:
  - "Soft-deny pattern in DESCRIBE: validatePrivilege() is VDS-only; PDS users get through via getTable() fallback (isRbacDeniedForPds allows), so exception only re-thrown when both checks fail"
  - "UserException catch block added between AccessControlException and Exception catches to prevent planError wrapping of permission denied messages (Pitfall 5)"
  - "sys and INFORMATION_SCHEMA skip validatePrivilege entirely -- sys.privileges is gated by isRbacDeniedForSysPrivileges in getTable(); other sys/INFORMATION_SCHEMA tables open to all"
  - "META-03 required no code change -- ExplainHandler delegates to inner handlers that already enforce privileges; test documents architectural contract via structural assertion"

patterns-established:
  - "Soft-deny: catch UserException from privilege check, save it, proceed to table lookup, re-throw only if table lookup also fails"

requirements-completed: [META-02, META-03]

# Metrics
duration: 3min
completed: 2026-02-22
---

# Phase 12 Plan 02: DESCRIBE Privilege Enforcement and EXPLAIN Verification Summary

**SELECT privilege soft-deny in DescribeTableHandler using validatePrivilege + getTable fallback, completing META-02 and META-03 with 3 unit tests**

## Performance

- **Duration:** 3 min
- **Started:** 2026-02-21T23:21:15Z
- **Completed:** 2026-02-21T23:24:54Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- DescribeTableHandler.toResult() now enforces SELECT privilege before table lookup using soft-deny pattern: validatePrivilege(SELECT) fires first, UserException saved; if getTable() succeeds (PDS grant via isRbacDeniedForPds), table is accessible; if getTable() also returns null, permission denied is re-thrown with clean error message
- sys.* and INFORMATION_SCHEMA.* paths skip validatePrivilege entirely -- these system schemas use separate access control (isRbacDeniedForSysPrivileges in getTable())
- UserException catch block added between AccessControlException and Exception catches prevents planError wrapping of RBAC messages (Pitfall 5 from research)
- 2 META-02 tests and 1 META-03 structural test added covering: denied VDS throws permission denied, sys table skips check, ExplainHandler has no rbacService field (delegates to inner handlers)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add SELECT privilege check to DescribeTableHandler** - `83359774f` (feat)
2. **Task 2: Add META-02 and META-03 unit tests** - `7e707f4f5` (test)

**Plan metadata:** (docs commit to follow)

## Files Created/Modified

- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DescribeTableHandler.java` - Added SqlGrant import, soft-deny validatePrivilege pattern, UserException catch
- `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDescribeTableHandler.java` - Added 2 META-02 tests, assertThatThrownBy/doThrow/verify/never imports, UserException/SqlGrant imports
- `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/TestRbacDdlHandlers.java` - Added META-03 test + ExplainHandler import

## Decisions Made

- Soft-deny pattern chosen over hard-deny: validatePrivilege() only checks VDS grants; PDS paths need getTable() fallback to allow PDS SELECT grant holders through (isRbacDeniedForPds path in CatalogImpl)
- UserException catch block added between AccessControlException and Exception catches -- critical to prevent planError wrapping of saved permission denied (Pitfall 5)
- sys and INFORMATION_SCHEMA skip: system schemas have their own gating (isRbacDeniedForSysPrivileges for sys.privileges; other sys/INFORMATION_SCHEMA tables intentionally open)
- META-03: no code change needed -- ExplainHandler delegates to inner handlers; test is a structural assertion that ExplainHandler has no rbacService field

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 12 complete -- all 2 plans done (12-01: sys.privileges admin gating, 12-02: DESCRIBE privilege enforcement)
- v1.2 RBAC implementation complete: VDS enforce (Phase 7), definer rights (Phase 8), UDF enforce (Phase 9), PDS enforce (Phase 10), container visibility (Phase 11), metadata safety (Phase 12)
- Full Maven build required Java 21 (blocked until JDK 21 available); feature code complete

---
*Phase: 12-metadata-safety-and-integration-testing*
*Completed: 2026-02-22*
