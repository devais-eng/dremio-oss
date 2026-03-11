---
phase: 29-backend-logic-gaps-v2
plan: 01
subsystem: api
tags: [rbac, catalog-api, sys-tables, dataset-count, sysflight]

# Dependency graph
requires:
  - phase: 25-backend-logic-fixes
    provides: filterByVisibility() on CatalogServiceHelper, SystemTableScanCreator RBAC row filtering pattern
  - phase: 28-dacsecuritycontext-role-enforcement
    provides: RBAC enforcement infrastructure stable
provides:
  - RBAC-aware dataset count in v3 CatalogServiceHelper.DetailType.datasetCount.addInfo()
  - RBAC row filtering for sys.privileges and sys.membership in SysFlightScanCreator (production path)
affects: [29-backend-logic-gaps-v2]

# Tech tracking
tech-stack:
  added: [junit (test scope), mockito-core (test scope) in plugins/sysflight/pom.xml]
  patterns: [rbac-filtered-count-over-namespace-count, rbac-sys-table-row-filter-via-iterator-wrap]

key-files:
  created:
    - plugins/sysflight/src/test/java/com/dremio/plugins/sysflight/TestSysFlightScanCreator.java
  modified:
    - dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java
    - plugins/sysflight/src/main/java/com/dremio/plugins/sysflight/SysFlightScanCreator.java
    - dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java
    - plugins/sysflight/pom.xml

key-decisions:
  - "Use namespaceService.list() + filterByVisibility() in DetailType.datasetCount instead of getDatasetCount() — same list already used by filterByVisibility, avoids double-fetching and adds RBAC scoping"
  - "Make filterRbacSystemTableByUser/filterMembershipByUser/filterPrivilegesByUserRoles package-private on SysFlightScanCreator for direct unit testing without full scan infrastructure"
  - "Test LOGIC-01 via getTopLevelCatalogItems(['datasetCount']) public API rather than calling package-private addInfo() directly"
  - "SystemTable.NODES used as non-filtered table in TestSysFlightScanCreator (SystemTable.JOBS does not exist)"

patterns-established:
  - "RBAC-filtered count: derive count from filterByVisibility()-filtered children list, not from unfiltered namespace count"
  - "SysFlight RBAC row filter: wrap iterator before PojoRecordReader construction — same pattern as SystemTableScanCreator"

requirements-completed: [LOGIC-01, LOGIC-02]

# Metrics
duration: 18min
completed: 2026-03-11
---

# Phase 29 Plan 01: Backend Logic Gaps v2 Summary

**RBAC-aware dataset count in v3 CatalogServiceHelper and sys table row filtering in SysFlightScanCreator — closing the production-path gaps missed in Phase 25**

## Performance

- **Duration:** 18 min
- **Started:** 2026-03-11T20:14:30Z
- **Completed:** 2026-03-11T20:32:30Z
- **Tasks:** 3 (Task 0: test skeletons, Task 1: LOGIC-01 fix, Task 2: LOGIC-02 fix)
- **Files modified:** 4

## Accomplishments
- CatalogServiceHelper.DetailType.datasetCount.addInfo() now computes count from RBAC-filtered children via filterByVisibility(), fixing misleading dataset counts in the v3 catalog API sidebar for non-admin users
- SysFlightScanCreator.create() wraps legacy table iterators with RBAC row filtering for sys.membership and sys.privileges — the production path used when ENABLE_SYSFLIGHT_SOURCE=true (the default)
- Phase 25 fixed SpaceResource (v2) and SystemTableScanCreator (fallback path); this plan fixes CatalogServiceHelper (v3) and SysFlightScanCreator (production path)

## Task Commits

Each task was committed atomically:

1. **Task 0: Create test skeletons for LOGIC-01 and LOGIC-02 (Wave 0 — Nyquist)** - `5959002d7` (test)
2. **Task 1: RBAC-aware dataset count in DetailType.datasetCount.addInfo() (LOGIC-01)** - `4a11f5a1f` (feat)
3. **Task 2: Port RBAC row filtering to SysFlightScanCreator (LOGIC-02)** - `13b51067d` (feat)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` - Replace getDatasetCount() with list() + filterByVisibility() in DetailType.datasetCount.addInfo()
- `plugins/sysflight/src/main/java/com/dremio/plugins/sysflight/SysFlightScanCreator.java` - Add filterRbacSystemTableByUser, filterMembershipByUser, filterPrivilegesByUserRoles; wrap iterator in create()
- `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` - Add 3 LOGIC-01 tests: non-admin filtered count, RBAC-disabled unfiltered count, admin unfiltered count
- `plugins/sysflight/src/test/java/com/dremio/plugins/sysflight/TestSysFlightScanCreator.java` - New file with 5 LOGIC-02 tests
- `plugins/sysflight/pom.xml` - Add junit and mockito-core test-scope dependencies

## Decisions Made
- Used `namespaceService.list(key, null, Integer.MAX_VALUE)` + `filterByVisibility()` in `DetailType.datasetCount` instead of `getDatasetCount()` — keeps the count consistent with what the user actually sees in the children list, and reuses the already-tested filtering path
- Made `filterRbacSystemTableByUser`, `filterMembershipByUser`, `filterPrivilegesByUserRoles` package-private (not private) so `TestSysFlightScanCreator` can test them directly without needing full scan operator infrastructure
- Tested LOGIC-01 via `getTopLevelCatalogItems(["datasetCount"])` (public API) rather than `DetailType.datasetCount.addInfo()` (package-private) — avoids making the test method public just for testing
- Used `SystemTable.NODES` (not `SystemTable.JOBS`) in the "non-filtered table" test — JOBS does not exist as a SystemTable enum value

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] SysTableMembershipInfo constructor is 3-arg, not 4-arg**
- **Found during:** Task 0 (TestSysFlightScanCreator creation)
- **Issue:** Plan's sample test code passed 4 args: `new SysTableMembershipInfo("role1", "role1-id", "alice", "user")`. Actual constructor is `(role_name, member_name, member_type)` — 3 args with no separate role ID field.
- **Fix:** Adjusted test to use 3-arg constructor: `new SysTableMembershipInfo("role1", "alice", "user")`
- **Files modified:** `plugins/sysflight/src/test/java/com/dremio/plugins/sysflight/TestSysFlightScanCreator.java`
- **Verification:** Compiled and tests pass GREEN
- **Committed in:** 5959002d7 (Task 0 commit)

**2. [Rule 1 - Bug] SysTablePrivilegeInfo constructor order is (grantee_type, grantee, object_type, object, privilege), not (grantee_id, grantee_name, privilege, object_type, object)**
- **Found during:** Task 0 (TestSysFlightScanCreator creation)
- **Issue:** Plan's sample test code used: `new SysTablePrivilegeInfo("role1-id", "role1", "SELECT", "VDS", "myspace.view1")`. Actual constructor order puts `grantee_type` first, then `grantee` (the role ID used for filtering), then `object_type`, `object`, `privilege`. The filtering checks `grantee` field against user's role IDs.
- **Fix:** Adjusted test: `new SysTablePrivilegeInfo("role", "role1-id", "VDS", "myspace.view1", "SELECT")` so `grantee="role1-id"` correctly matches the role ID used in filtering
- **Files modified:** `plugins/sysflight/src/test/java/com/dremio/plugins/sysflight/TestSysFlightScanCreator.java`
- **Verification:** Compiled and tests pass GREEN
- **Committed in:** 5959002d7 (Task 0 commit)

**3. [Rule 1 - Bug] SystemTable.JOBS does not exist — used SystemTable.NODES instead**
- **Found during:** Task 0 (TestSysFlightScanCreator creation)
- **Issue:** Plan used `SystemTable.JOBS` for the "non-filtered table" test. SystemTable enum has no JOBS value.
- **Fix:** Changed to `SystemTable.NODES` which is a valid enum value and is not PRIVILEGES or MEMBERSHIP
- **Files modified:** `plugins/sysflight/src/test/java/com/dremio/plugins/sysflight/TestSysFlightScanCreator.java`
- **Verification:** Compiled and tests pass GREEN
- **Committed in:** 5959002d7 (Task 0 commit)

**4. [Rule 1 - Bug] addInfo() is package-private — cannot test from com.dremio.dac.service**
- **Found during:** Task 0 (TestCatalogServiceHelper LOGIC-01 tests)
- **Issue:** Plan suggested calling `DetailType.datasetCount.addInfo()` directly from the test. However, `addInfo()` has no access modifier (package-private in Java), and the test is in `com.dremio.dac.service` while `CatalogServiceHelper` is in `com.dremio.dac.service.catalog`. Cross-package access not permitted.
- **Fix:** Tests were rewritten to call `getTopLevelCatalogItems(["datasetCount"])` (public API) which internally invokes `DetailType.datasetCount.addInfo()`. Appropriate mocks for `getSpaces()`, `getSources()`, `getTopLevelFunctions()`, `getHome()` added.
- **Files modified:** `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java`
- **Verification:** All 3 tests pass GREEN (3/3 Tests run: 3, Failures: 0)
- **Committed in:** 5959002d7 (Task 0 commit)

---

**Total deviations:** 4 auto-fixed (all Rule 1 — plan sample code had incorrect constructor signatures and missing enum values; access modifier constraint required API-level testing approach)
**Impact on plan:** All fixes were corrections to sample code in the plan. Production implementation followed the plan exactly. No scope creep.

## Issues Encountered
- The dac/backend test compilation needed `sabot/kernel` to be installed locally first (its compiled JAR in `.m2` was the pre-fork version without `getAccessibleObjectPaths()`). Fixed by running `mvn install -pl sabot/kernel -DskipTests -Dspotless.check.skip=true`.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- LOGIC-01 and LOGIC-02 are complete. The v3 catalog API dataset count and SysFlight sys table row filtering are now RBAC-aware.
- Phase 29 plan 02 (if any) can proceed.
- Remaining LOGIC-03 (CREATE_VIEW auto-grant) was handled in Phase 25 plan 01 — already complete.

---
*Phase: 29-backend-logic-gaps-v2*
*Completed: 2026-03-11*

## Self-Check: PASSED
- All 4 modified files exist on disk
- All 3 task commits (5959002d7, 4a11f5a1f, 13b51067d) verified in git log
- SUMMARY.md created at .planning/phases/29-backend-logic-gaps-v2/29-01-SUMMARY.md
- LOGIC-01 tests: 3/3 pass GREEN
- LOGIC-02 tests: 5/5 pass GREEN
