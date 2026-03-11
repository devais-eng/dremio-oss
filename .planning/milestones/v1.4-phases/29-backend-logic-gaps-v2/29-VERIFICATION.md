---
phase: 29-backend-logic-gaps-v2
verified: 2026-03-11T21:55:00Z
status: passed
score: 8/8 must-haves verified
re_verification: false
---

# Phase 29: Backend Logic Gaps v2 Verification Report

**Phase Goal:** Fix remaining backend logic gaps: v3 catalog dataset count, sys table row filtering in the production SysFlight path, and CREATE_VIEW privilege resolution
**Verified:** 2026-03-11T21:55:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Sidebar dataset count next to a space reflects only RBAC-visible datasets via v3 catalog API | VERIFIED | `CatalogServiceHelper.java:229-239` — `datasetCount.addInfo()` calls `namespaceService.list()` then `helper.filterByVisibility(children)` before counting `Type.DATASET` containers |
| 2 | Non-admin user can query sys.membership and see only own memberships (no Object not found error) | VERIFIED | `SysFlightScanCreator.java:56-57` — iterator is wrapped with `filterRbacSystemTableByUser()` before `PojoRecordReader`; `filterMembershipByUser()` filters by `member_name == userName` |
| 3 | Non-admin user can query sys.privileges and see only grants for own roles | VERIFIED | `SysFlightScanCreator.java:117-119` — `filterPrivilegesByUserRoles()` keeps only rows where `grantee` is in user's role ID set |
| 4 | Admin user sees unfiltered dataset counts and all sys table rows | VERIFIED | `filterByVisibility()` line 3387: `rbacService.isAdminMember(userName)` returns full list. `filterRbacSystemTableByUser()` line 109: admin check short-circuits before filtering |
| 5 | RBAC-disabled deployment returns original unfiltered counts and rows | VERIFIED | `filterByVisibility()` line 3383: `!RBAC_ENABLED` returns children unchanged. `filterRbacSystemTableByUser()` line 101: `!RBAC_ENABLED` returns iterator unchanged |
| 6 | Non-admin user with CREATE_VIEW privilege on a space can create a view via SQL CREATE VIEW | VERIFIED | `CatalogImpl.java:2884-2885` — `containerPath = viewKey.getRoot()`, `hasPrivilege(userName, "CREATE_VIEW", "SPACE", containerPath)` — matches key format used by REST API grant storage |
| 7 | Non-admin user without CREATE_VIEW privilege gets validation error on CREATE VIEW | VERIFIED | `CatalogImpl.java:2890-2893` — throws `UserException.validationError()` with "Permission denied: CREATE_VIEW privilege required" message |
| 8 | Admin user can create views without restriction; RBAC-disabled deployment allows unrestricted view creation | VERIFIED | `CatalogImpl.java:2875,2878,2881` — three-way guard: null dremioConfig, system user, null rbacService all return before the privilege check |

**Score:** 8/8 truths verified

---

## Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` | RBAC-aware dataset count in `DetailType.datasetCount.addInfo()` | VERIFIED | Lines 229-239: uses `list()` + `filterByVisibility()` + DATASET type count. Contains `filterByVisibility` call. Committed in `4a11f5a1f` |
| `plugins/sysflight/src/main/java/com/dremio/plugins/sysflight/SysFlightScanCreator.java` | RBAC row filtering for PRIVILEGES and MEMBERSHIP | VERIFIED | Lines 56-57: iterator wrapped before `PojoRecordReader`. Methods `filterRbacSystemTableByUser`, `filterMembershipByUser`, `filterPrivilegesByUserRoles` present (lines 84-158). Committed in `13b51067d` |
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` | Fixed `validateCreateViewPrivilege()` with `objectType=SPACE` and `path=viewKey.getRoot()` | VERIFIED | Lines 2884-2885: `viewKey.getRoot()` and `"SPACE"` confirmed. Contains `"SPACE"`. Committed in `8ab2ecd18` |
| `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` | Unit tests for RBAC-filtered dataset count | VERIFIED | Three test methods present: `testDatasetCount_rbacEnabled_nonAdmin_returnsFilteredCount`, `testDatasetCount_rbacDisabled_returnsUnfilteredCount`, `testDatasetCount_admin_returnsUnfilteredCount`. Committed in `5959002d7` |
| `plugins/sysflight/src/test/java/com/dremio/plugins/sysflight/TestSysFlightScanCreator.java` | Unit tests for SysFlightScanCreator RBAC row filtering | VERIFIED | Five test methods present: membership filter, privileges filter, admin passthrough, RBAC-disabled passthrough, non-filtered table. Committed in `5959002d7` |
| `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` | Tests asserting `objectType=SPACE` and `path="myspace"` | VERIFIED | Both `testValidateCreateViewPrivilege_denied_throwsPermissionDenied` and `testValidateCreateViewPrivilege_granted_passes` assert `eq("SPACE")` and `eq("myspace")`. Committed in `23914067d` |
| `plugins/sysflight/pom.xml` | junit and mockito-core test-scope dependencies | VERIFIED | Lines 37-45: both dependencies present without version (managed by parent BOM). Committed in `5959002d7` |

---

## Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `CatalogServiceHelper.DetailType.datasetCount.addInfo()` | `CatalogServiceHelper.filterByVisibility()` | Direct method call on `helper` instance | WIRED | `CatalogServiceHelper.java:233` — `helper.filterByVisibility(children)` confirmed |
| `SysFlightScanCreator.create()` | `filterRbacSystemTableByUser()` | Iterator wrapping before `PojoRecordReader` construction | WIRED | `SysFlightScanCreator.java:57` — `iterator = filterRbacSystemTableByUser(iterator, legacyTable.get(), plugin, config)` confirmed |
| `SysFlightScanCreator.filterRbacSystemTableByUser()` | `RbacService` via `instanceof` cast from `AccessControlListingManager` | `plugin.getSabotContext().getAccessControlListingManager()` | WIRED | `SysFlightScanCreator.java:97-105` — `aclManager = sabotContext.getAccessControlListingManager()`, `instanceof RbacService` check, cast confirmed |
| `CatalogImpl.validateCreateViewPrivilege()` | `RbacService.hasPrivilege()` | `objectType="SPACE"` and `containerPath=viewKey.getRoot()` | WIRED | `CatalogImpl.java:2884-2885` — `containerPath = viewKey.getRoot()` feeds `hasPrivilege(userName, "CREATE_VIEW", "SPACE", containerPath)` confirmed |
| REST API `GRANT CREATE_VIEW ON SPACE myspace` | `RbacConfig.grantKey(roleId, "SPACE", "myspace", "CREATE_VIEW")` | KV store key must match lookup key in `validateCreateViewPrivilege` | WIRED | Key alignment verified: `CatalogServiceHelper.enforceCreatePrivilege()` stores with `objectType="SPACE"` and `ds.getPath().get(0)`; `validateCreateViewPrivilege()` now looks up with `"SPACE"` and `getRoot()` — exact match |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| LOGIC-01 | 29-01-PLAN.md | Dataset count shown next to space names reflects only RBAC-visible datasets | SATISFIED | `CatalogServiceHelper.DetailType.datasetCount.addInfo()` computes count from `filterByVisibility()`-filtered children. 3 unit tests GREEN. |
| LOGIC-02 | 29-01-PLAN.md | `sys.membership` and `sys.privileges` system tables queryable by non-admin users (filtered) | SATISFIED | `SysFlightScanCreator.create()` wraps legacy table iterator with `filterRbacSystemTableByUser()`. 5 unit tests GREEN. |
| LOGIC-03 | 29-02-PLAN.md | After creating a view, creator automatically granted SELECT/ALTER/DROP (auto-grant); also CREATE_VIEW privilege resolved correctly | SATISFIED | `validateCreateViewPrivilege()` uses `"SPACE"` + `getRoot()` matching the REST API grant key format. 3 unit tests GREEN. Auto-grant mechanism was implemented in Phase 25 and remains unchanged. |

**All 3 required requirements satisfied.**

No orphaned requirements: REQUIREMENTS.md Traceability table maps LOGIC-01, LOGIC-02, LOGIC-03 to Phase 25 and Phase 29. Both plans claim exactly these three IDs. Coverage is complete.

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `TestCatalogServiceHelper.java` | 2615, 2662, 2695 | Stale Javadoc comment "RED until Task 1 replaces getDatasetCount()" | Info | Documentation artifact from TDD Wave 0 step. Production code IS fixed. Tests pass GREEN. No impact on behavior. |
| `CatalogServiceHelper.java` | 1212, 1235, 1761, 2007, 2056, 2424, 2737 | Pre-existing `TODO` comments | Info | All are pre-existing items unrelated to Phase 29 changes (file listing limits, namespace utility refactors). None block LOGIC-01. |

No blockers. No warnings. Stale test comments are cosmetic only.

---

## Human Verification Required

### 1. End-to-end sidebar dataset count

**Test:** Log in as a non-admin user. Grant SELECT on one of two views in a space. Navigate to the sidebar and check the dataset count displayed next to the space name.
**Expected:** Count displays 1 (only the granted view), not 2 (total views in space).
**Why human:** The full call stack from browser request through v3 catalog API to `getTopLevelCatalogItems(["datasetCount"])` cannot be exercised programmatically without a running Dremio instance.

### 2. Non-admin sys.membership query returns only own rows

**Test:** Log in as a non-admin user "alice". Run `SELECT * FROM sys.membership`.
**Expected:** Returns only rows where `member_name = 'alice'`. No "Object not found" error.
**Why human:** Requires a live Dremio instance with SysFlight enabled (the default) and RBAC enabled.

### 3. Non-admin CREATE VIEW succeeds after GRANT CREATE_VIEW ON SPACE

**Test:** Run `GRANT CREATE_VIEW ON SPACE myspace TO ROLE myrole`. Add user alice to myrole. Log in as alice. Run `CREATE VDS myspace.myview AS SELECT 1`.
**Expected:** View is created successfully and alice immediately holds SELECT, ALTER, DROP on the new view.
**Why human:** Requires a live Dremio instance to exercise the full SQL execution pipeline through `CatalogImpl.validateCreateViewPrivilege()` and the auto-grant path.

---

## Gaps Summary

None. All must-haves verified. All three requirements satisfied. All commits confirmed in git. Production code is substantive and correctly wired.

---

_Verified: 2026-03-11T21:55:00Z_
_Verifier: Claude (gsd-verifier)_
