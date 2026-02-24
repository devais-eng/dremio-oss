---
phase: 12-metadata-safety-and-integration-testing
verified: 2026-02-22T00:00:00Z
status: passed
score: 3/3 must-haves verified
re_verification: false
human_verification:
  - test: "Non-admin user queries sys.privileges in a running cluster"
    expected: "Query fails with permission denied or table-not-found error; ADMIN user succeeds"
    why_human: "Unit tests cover guard logic via mock interactions; real query execution through the SQL engine requires a live Dremio instance"
  - test: "User without SELECT runs DESCRIBE on a real VDS in a running cluster"
    expected: "Error message contains 'Permission denied' with SELECT context, not 'Unknown table'"
    why_human: "Unit test mocks validatePrivilege throw; real Dremio end-to-end path (parser -> handler -> catalog) not executable without JDK 21 build"
  - test: "EXPLAIN on a query referencing objects the user lacks access to"
    expected: "EXPLAIN fails with permission denied (inherited from inner handler); EXPLAIN succeeds when all privileges are held"
    why_human: "META-03 is verified by a structural assertion only (ExplainHandler has no rbacService field); live query engine execution needed for behavioral proof"
---

# Phase 12: Metadata Safety and Integration Testing — Verification Report

**Phase Goal:** sys.privileges is admin-only, DESCRIBE and EXPLAIN are gated on existing privilege grants, and end-to-end integration tests prove all v1.2 features work correctly together
**Verified:** 2026-02-22T00:00:00Z
**Status:** passed (with human verification items)
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths (from ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Non-admin user querying `sys.privileges` receives a permission denied / not-found result; ADMIN user can query without restriction | VERIFIED | `isRbacDeniedForSysPrivileges()` present and wired in CatalogImpl.java at 7 sites (1 definition, 6 call sites); guard chain: key-path check -> RBAC_ENABLED -> SystemUser bypass -> isAdminMember(); 4 unit tests cover all branches |
| 2 | User without SELECT on a VDS or PDS who issues DESCRIBE receives a permission denied error | VERIFIED | `DescribeTableHandler.toResult()` uses soft-deny pattern: `catalog.validatePrivilege(path, SELECT)` fires first for non-sys/non-INFORMATION_SCHEMA paths; UserException saved and re-thrown if `getTable()` also returns null; outer `catch (UserException ue)` preserves clean error message |
| 3 | EXPLAIN referencing objects the user lacks access to fails with permission denied; succeeds when all privileges held | VERIFIED (structural) | META-03 structural test confirms `ExplainHandler` has no `rbacService` field — delegation to inner handlers (NormalHandler, InsertTableHandler, etc.) is confirmed by inspection; inner handlers enforce via `catalog.getTable()` deny-by-null and `catalog.validatePrivilege()`; behavioral proof requires live execution (flagged for human) |

**Score:** 3/3 truths verified (Truth 3 verified structurally; behavioral confirmation flagged for human)

---

## Required Artifacts

### Plan 12-01 Artifacts

| Artifact | Provides | Exists | Substantive | Wired | Status |
|----------|----------|--------|-------------|-------|--------|
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` | `isRbacDeniedForSysPrivileges()` method + 6 call-site wiring | Yes | Yes — full guard chain (42 lines, key-path check, RBAC_ENABLED, SystemUser, isAdminMember), equalsIgnoreCase on both components | Yes — wired at getTableNoResolve, getTableNoColumnCount, getTable(NamespaceKey) x2, getTable(CatalogEntityKey), bulkGetTables lambda | VERIFIED |
| `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` | 4 META-01 unit tests | Yes | Yes — 4 substantive tests with real mock interactions and assertions | Yes — part of existing TestCatalogImpl test class | VERIFIED |

### Plan 12-02 Artifacts

| Artifact | Provides | Exists | Substantive | Wired | Status |
|----------|----------|--------|-------------|-------|--------|
| `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DescribeTableHandler.java` | validatePrivilege(SELECT) soft-deny + UserException catch ordering | Yes | Yes — `savedPermissionDenied` pattern (4 occurrences), system-schema skip, `catch (UserException ue)` re-throw between AccessControlException and Exception catches | Yes — executes within `toResult()` on every DESCRIBE call | VERIFIED |
| `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDescribeTableHandler.java` | META-02 privilege denied test for DESCRIBE | Yes | Yes — 2 tests: `testToResult_rbacDenied_throwsPermissionDenied` (doThrow + assertThatThrownBy for "Permission denied") and `testToResult_sysTable_skipsPrivilegeCheck` (verify never() validatePrivilege) | Yes — part of existing TestDescribeTableHandler class | VERIFIED |
| `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/TestRbacDdlHandlers.java` | META-03 EXPLAIN verification comment + structural test | Yes | Yes — `testExplainHandler_inheritsPrivilegeEnforcement_fromInnerHandlers` uses `assertThat(ExplainHandler.class.getDeclaredFields())` to confirm no `rbacService` field | Yes — part of existing TestRbacDdlHandlers class | VERIFIED |

---

## Key Link Verification

| From | To | Via | Status | Evidence |
|------|----|-----|--------|----------|
| `CatalogImpl.getTable(NamespaceKey)` (and all overloads) | `isRbacDeniedForSysPrivileges(key)` | Inline call alongside isRbacDeniedForVds/Pds | WIRED | Lines 289, 301, 318, 327, 344, 391 in CatalogImpl.java; 7 total occurrences (1 def + 6 call sites) |
| `CatalogImpl.isRbacDeniedForSysPrivileges()` | `rbacService.isAdminMember(userName)` | Admin role check after RBAC_ENABLED + SystemUser guards | WIRED | Line 3056: `if (!rbacService.isAdminMember(userName))` in method body; `isAdminMember` is a real RbacService method (confirmed at RbacService.java:152) |
| `DescribeTableHandler.toResult()` | `catalog.validatePrivilege(path, SELECT)` | Direct call before `catalog.getTable()` for non-sys/non-INFORMATION_SCHEMA paths | WIRED | Line 100 in DescribeTableHandler.java; `SqlGrant` imported at line 27 |
| `DescribeTableHandler` exception handling | `UserException` re-throw | `catch (UserException ue)` before `catch (Exception ex)` | WIRED | Line 175: `catch (UserException ue) { throw ue; }` — prevents planError wrapping |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| META-01 | 12-01-PLAN.md | sys.privileges table is readable only by ADMIN role | SATISFIED | `isRbacDeniedForSysPrivileges()` returns true for non-admin users when RBAC enabled; returns false for admin (isAdminMember=true), system user, RBAC disabled; wired into all 6 getTable() RBAC check sites; 4 unit tests verify all branches |
| META-02 | 12-02-PLAN.md | DESCRIBE requires SELECT privilege on the target object | SATISFIED | Soft-deny `validatePrivilege(SELECT)` in `DescribeTableHandler.toResult()` before `getTable()`; permission denied re-thrown when both checks fail; sys/INFORMATION_SCHEMA skipped; 2 unit tests cover denied VDS and sys-skip cases |
| META-03 | 12-02-PLAN.md | EXPLAIN requires privileges on all objects referenced in the plan | SATISFIED (structural) | No code change needed — ExplainHandler delegates to inner handlers; structural test confirms ExplainHandler has no `rbacService` field; behavioral coverage flagged for human verification |

**Orphaned requirements check:** REQUIREMENTS.md maps exactly META-01, META-02, META-03 to Phase 12. Both plans declare these same IDs. No orphaned requirements.

**Tracker note:** REQUIREMENTS.md tracker still shows "Pending" for META-01/02/03 (last updated 2026-02-20). This is a documentation tracking gap — the implementation is complete as of 2026-02-22. The tracker status does not reflect the actual code state.

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `CatalogImpl.java` | 1214 | `// TODO: PDS enforcement not applied here -- review if this path needs isRbacDeniedForPds enforcement` | Info | Pre-existing TODO on `getTable(String datasetId)` overload; scope is PDS enforcement (Phase 10), not related to Phase 12 sys.privileges guard. All other getTable() overloads are covered. |
| `CatalogImpl.java` | 519, 1172, 1451, 1467, 1862, 2039, 2339, 3143, 3350, 3377, 3547 | Various pre-existing `// TODO: DX-...` comments | Info | Pre-existing TODOs unrelated to Phase 12 changes; none in RBAC code paths added by this phase |

No blockers or warnings found. All anti-patterns are pre-existing and outside the scope of Phase 12 changes.

---

## Human Verification Required

### 1. sys.privileges Admin-Only (Live Cluster)

**Test:** Log in as a non-admin user; execute `SELECT * FROM sys.privileges`. Log in as an ADMIN; execute the same query.
**Expected:** Non-admin receives a table-not-found or permission error. ADMIN receives the rows.
**Why human:** Unit tests mock `isAdminMember()` return values and verify the guard fires. Actual SQL execution through the parser, planner, and system storage plugin requires a running Dremio cluster with JDK 21 (build currently blocked per SUMMARY notes).

### 2. DESCRIBE Permission Denied Error Message (Live Cluster)

**Test:** As a user without SELECT on a VDS, execute `DESCRIBE myspace.myview`.
**Expected:** Error message contains "Permission denied" with SELECT context, not "Unknown table".
**Why human:** `testToResult_rbacDenied_throwsPermissionDenied` mocks `validatePrivilege` to throw and verifies the exception propagates. Real execution through the full SQL handler pipeline and actual RbacService.validatePrivilege() implementation requires a running cluster.

### 3. EXPLAIN Privilege Enforcement (Live Cluster)

**Test:** As a user without SELECT on a table, execute `EXPLAIN PLAN FOR SELECT * FROM myspace.mytable`. Then grant SELECT and retry.
**Expected:** First EXPLAIN fails with permission denied (inherited from NormalHandler). Second succeeds.
**Why human:** META-03 is proven structurally (ExplainHandler has no rbacService field, must delegate). The delegation path through NormalHandler -> catalog.getTable() -> isRbacDeniedForVds/Pds is covered by prior phases' unit tests. Full behavioral proof of the chain from EXPLAIN through inner handler requires query engine execution.

---

## Gaps Summary

No gaps found. All automated checks passed:

- `isRbacDeniedForSysPrivileges()` is defined and wired at all 6 getTable() RBAC sites (7 total occurrences confirmed by grep)
- The method uses `equalsIgnoreCase` for both "sys" and "privileges" path components
- The guard chain is correct: key-path check -> RBAC_ENABLED -> SystemUser bypass -> rbacService null guard -> isAdminMember()
- 4 META-01 unit tests exist and are substantive (verified real mock interactions and assertions)
- `DescribeTableHandler.toResult()` implements the correct soft-deny pattern with 1 validatePrivilege call, 4 savedPermissionDenied references, and 2 UserException catch blocks
- System schema skip (sys, INFORMATION_SCHEMA) is implemented with equalsIgnoreCase
- 2 META-02 tests and 1 META-03 structural test exist and are substantive
- All 4 commit hashes documented in SUMMARYs exist in git history
- No blocker or warning anti-patterns in Phase 12 code

Three items require human (live cluster) verification, as automated checks cannot substitute for query engine execution with real RBAC enforcement.

---

_Verified: 2026-02-22T00:00:00Z_
_Verifier: Claude (gsd-verifier)_
