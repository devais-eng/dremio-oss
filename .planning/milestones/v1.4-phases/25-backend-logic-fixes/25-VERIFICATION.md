---
phase: 25-backend-logic-fixes
verified: 2026-03-11T16:15:00Z
status: passed
score: 10/10 must-haves verified
---

# Phase 25: Backend Logic Fixes Verification Report

**Phase Goal:** Dataset counts, system table queries, and view creation reflect the caller's RBAC context correctly
**Verified:** 2026-03-11T16:15:00Z
**Status:** PASSED
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

#### Plan 25-01 Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Space dataset count shows only RBAC-visible datasets for non-admin users | VERIFIED | SpaceResource.java:114-137 -- children fetched, filtered by `filterByRbacVisibility()`, then `.stream().filter(c -> c.getType() == DATASET).count()` for non-admin RBAC-enabled path |
| 2 | Admin users see the full dataset count (no regression) | VERIFIED | SpaceResource.java:119-130 -- when rbacService is null, dremioConfig is null, RBAC disabled, or `isAdminMember()` is true, uses `namespaceService.getDatasetCount()` optimized path |
| 3 | After creating a view, the creator holds SELECT, ALTER, and DROP on the new view | VERIFIED | CreateOrUpdateViewHandler.java:550-606 -- `autoGrantCreatorPrivileges()` grants {"SELECT","ALTER","DROP"} via `rbacService.grantPrivilege()` to all of the creator's explicit roles (or PUBLIC fallback) |
| 4 | Auto-grant is skipped when RBAC is disabled (no regression) | VERIFIED | CreateOrUpdateViewHandler.java:555-558 -- three-way null guard: returns immediately if rbacService==null, dremioConfig==null, or RBAC not enabled |
| 5 | Auto-grant is skipped for admin users (they already have full access) | VERIFIED | CreateOrUpdateViewHandler.java:563-566 -- `if (rbacService.isAdminMember(userName)) { return; }` |

#### Plan 25-02 Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 6 | Non-admin user can query sys.membership and sees only their own membership rows | VERIFIED | SystemTableScanCreator.java:95-96 -- `filterMembershipByUser()` filters by `userName.equals(row.member_name)` |
| 7 | Non-admin user can query sys.privileges and sees only grants for their own roles | VERIFIED | SystemTableScanCreator.java:98-100 -- `filterPrivilegesByUserRoles()` filters by `userRoleIds.contains(row.grantee)` using `rbacService.getUserRoleIds(userName)` which includes explicit roles + PUBLIC |
| 8 | Admin user queries sys.membership and sees all memberships (no regression) | VERIFIED | SystemTableScanCreator.java:89-91 -- `if (userName == null || rbacService.isAdminMember(userName)) { return iterator; }` returns unfiltered |
| 9 | Admin user queries sys.privileges and sees all grants (no regression) | VERIFIED | Same as above -- admin check at line 90 short-circuits before any filtering |
| 10 | RBAC-disabled deployments see all rows in both tables (no regression) | VERIFIED | SystemTableScanCreator.java:79-84 -- three-way null guard returns unfiltered iterator when RBAC not enabled. Also CatalogImpl.java:3048-3050 `isRbacDeniedForSysRoles` returns false when RBAC disabled |

**Score:** 10/10 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java` | RBAC-aware dataset count computation | VERIFIED | 212 lines. Contains `filterByRbacVisibility` (line 178), RBAC-aware count logic (lines 118-138). No stubs, no TODOs. |
| `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java` | Auto-grant SELECT, ALTER, DROP to view creator | VERIFIED | 611 lines. Contains `autoGrantCreatorPrivileges` (line 550), `grantPrivilege` calls (line 593). Called from both `createVersionedView` (line 162) and `createView` (line 198). |
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` | Removed admin-only block for sys.privileges and sys.membership | VERIFIED | `isRbacDeniedForSysRoles` (line 3043) only blocks `sys.roles` (checks `key.getLeaf()=="roles"`). Old method `isRbacDeniedForSysPrivileges` does not exist (0 occurrences). 7 call sites updated. |
| `sabot/kernel/src/main/java/com/dremio/exec/store/sys/SystemTableScanCreator.java` | User-scoped filtering of PRIVILEGES and MEMBERSHIP system table rows | VERIFIED | 138 lines. Contains `filterRbacSystemTableByUser` (line 68), `filterMembershipByUser` (line 108), `filterPrivilegesByUserRoles` (line 125). Uses `instanceof RbacService` cast from `getAccessControlListingManager()`. |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` | Added listMembershipsByUser() and getUserRoleIds() | VERIFIED | `listMembershipsByUser` (line 336), `getUserRoleIds` (line 347). Both methods delegate to `membershipStore.listByUser()`. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| SpaceResource.java | filterByRbacVisibility | Count filtered children instead of namespaceService.getDatasetCount() | WIRED | Line 116: `children = filterByRbacVisibility(children)`. Line 135-137: `children.stream().filter(c -> c.getType() == DATASET).count()` used for non-admin RBAC-enabled path. |
| CreateOrUpdateViewHandler.java | RbacService.grantPrivilege() | config.getContext().getRbacService().grantPrivilege() | WIRED | Line 551: `config.getContext().getRbacService()`. Line 593: `rbacService.grantPrivilege(roleId, "VDS", objectPath, privilege, userName)`. Called for {"SELECT","ALTER","DROP"} at line 569. |
| SystemTableScanCreator.java | RbacService | plugin.getSabotContext().getAccessControlListingManager() instanceof RbacService | WIRED | Line 78: `getAccessControlListingManager()`. Line 80: `instanceof RbacService` guard. Line 86: cast. Line 90: `isAdminMember()`. Line 99: `getUserRoleIds()`. |
| CatalogImpl.java | System table access control | Renamed to isRbacDeniedForSysRoles, only blocks sys.roles | WIRED | Line 3043: method definition checks `key.getLeaf()=="roles"` only. 6 call sites (lines 289, 301, 318, 327, 344, 391) all use `isRbacDeniedForSysRoles`. Old name has 0 occurrences. |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| LOGIC-01 | 25-01-PLAN | Dataset count shown next to space names reflects only RBAC-visible datasets | SATISFIED | SpaceResource.getSpace() computes count from RBAC-filtered children list for non-admin users (lines 118-138). Admin/RBAC-off uses optimized namespace count. |
| LOGIC-02 | 25-02-PLAN | sys.membership and sys.privileges queryable by non-admin users with scoped data | SATISFIED | CatalogImpl no longer blocks non-admin access (only sys.roles blocked). SystemTableScanCreator filters rows: membership by member_name, privileges by user's role set. |
| LOGIC-03 | 25-01-PLAN | After creating a view, creator auto-granted SELECT, ALTER, DROP | SATISFIED | CreateOrUpdateViewHandler.autoGrantCreatorPrivileges() called after both versioned and non-versioned view creation (lines 162, 198). Grants to all explicit roles or PUBLIC fallback. |

No orphaned requirements. ROADMAP.md maps LOGIC-01, LOGIC-02, LOGIC-03 to Phase 25. All three are claimed and implemented across plans 25-01 and 25-02.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none) | - | - | - | No anti-patterns detected in any modified file. No TODOs, FIXMEs, placeholders, empty implementations, or console-only handlers. |

### Human Verification Required

### 1. Dataset Count Accuracy for Non-Admin User

**Test:** Log in as a non-admin user with SELECT on only 2 of 5 VDS in a space. Navigate to the space listing.
**Expected:** The dataset count badge next to the space name shows "2" (not "5").
**Why human:** Requires running application with real RBAC grants to observe the rendered count matches visibility.

### 2. View Creation Auto-Grant

**Test:** Log in as a non-admin user with a role (e.g., "analyst"). Create a new view via `CREATE VIEW myspace.test_view AS SELECT 1`. Then run `SELECT * FROM myspace.test_view`.
**Expected:** The SELECT succeeds without any additional GRANT step. Verify via `SELECT * FROM sys.privileges WHERE object = 'myspace.test_view'` that SELECT, ALTER, DROP grants exist for the user's role.
**Why human:** Requires end-to-end query execution with real RBAC state to confirm the grant is applied and usable.

### 3. System Table Scoping for Non-Admin User

**Test:** Log in as a non-admin user. Run `SELECT * FROM sys.membership`. Run `SELECT * FROM sys.privileges`.
**Expected:** sys.membership returns only rows where member_name matches the current user. sys.privileges returns only rows where grantee is one of the user's roles or PUBLIC. Neither query returns "Object not found".
**Why human:** Requires live query execution to verify row-level filtering and absence of the previous "Object not found" error.

### 4. Admin Regression

**Test:** Log in as admin. Verify dataset count shows full count. Verify sys.membership and sys.privileges return all rows.
**Expected:** No change from previous behavior for admin users.
**Why human:** Requires application with running RBAC to confirm admin bypass is preserved.

### Gaps Summary

No gaps found. All 10 observable truths are verified. All 5 artifacts pass all three verification levels (exists, substantive, wired). All 4 key links are confirmed wired. All 3 requirements (LOGIC-01, LOGIC-02, LOGIC-03) are satisfied. All 4 commits exist in git history. No anti-patterns detected.

---

_Verified: 2026-03-11T16:15:00Z_
_Verifier: Claude (gsd-verifier)_
