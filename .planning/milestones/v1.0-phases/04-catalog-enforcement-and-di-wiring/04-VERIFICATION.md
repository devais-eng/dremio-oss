---
phase: 04-catalog-enforcement-and-di-wiring
verified: 2026-02-18T12:00:00Z
status: passed
score: 7/7 must-haves verified
re_verification: false
must_haves:
  truths:
    - "A user with no SELECT grant on a VDS receives a 'not found' error when querying it"
    - "A user with SELECT grant on a VDS can successfully query that VDS"
    - "A user with EXECUTE grant on a UDF can call it; without the grant cannot"
    - "Definer-rights preserved -- inner tables resolve under view owner identity"
    - "System user $dremio$ bypasses all privilege checks"
    - "RBAC feature flag OFF means no enforcement, identical to pre-RBAC behavior"
    - "CREATE OR REPLACE VIEW denied when user lacks CREATE_VIEW privilege"
  artifacts:
    - path: "dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java"
      provides: "RbacService registration via registry.bind()"
    - path: "sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogServiceImpl.java"
      provides: "Provider<RbacService> field and constructor param, passed to createCatalog()"
    - path: "sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java"
      provides: "validatePrivilege(), isRbacDeniedForVds(), isRbacDeniedForFunction(), resolveRbacObjectType()"
    - path: "sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java"
      provides: "CREATE_VIEW privilege check instead of ALTER"
    - path: "sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java"
      provides: "10 RBAC enforcement unit tests"
  key_links:
    - from: "DACDaemonModule.java"
      to: "CatalogServiceImpl constructor"
      via: "registry.provider(RbacService.class) passed as last constructor arg"
    - from: "CatalogServiceImpl.createCatalog()"
      to: "CatalogImpl constructor"
      via: "rbacServiceProvider.get() passed as 17th arg"
    - from: "CatalogImpl.validatePrivilege()"
      to: "RbacService.hasPrivilege()"
      via: "direct call after flag and system-user checks"
    - from: "CatalogImpl.resolveCatalog() x4"
      to: "new CatalogImpl()"
      via: "rbacService and dremioConfig passed through as last two args"
---

# Phase 4: Catalog Enforcement and DI Wiring Verification Report

**Phase Goal:** CatalogImpl.validatePrivilege() enforces real permission checks -- users without grants are denied access to VDS and UDFs, with system-user bypass and feature flag gating preserving existing behavior when disabled
**Verified:** 2026-02-18T12:00:00Z
**Status:** passed
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A user with no SELECT grant on a VDS receives a "not found" error (deny-by-default) | VERIFIED | `validatePrivilege()` at line 2826 calls `rbacService.hasPrivilege()`, throws `UserException("Table '%s' not found")` on false return. `isRbacDeniedForVds()` at line 2881 returns `true` on denied, causing `getTable()` to return null. Test `testValidatePrivilege_noGrant_throwsNotFound` asserts `VALIDATION` error with "not found" |
| 2 | A user with SELECT grant on a VDS can successfully query it | VERIFIED | `validatePrivilege()` returns without throwing when `rbacService.hasPrivilege()` returns true. `isRbacDeniedForVds()` returns false when granted. Test `testValidatePrivilege_withGrant_passes` confirms no exception |
| 3 | EXECUTE grant on UDF allows call; no grant denies | VERIFIED | `resolveRbacObjectType()` maps EXECUTE to "FUNCTION" (line 2841-2842). `isRbacDeniedForFunction()` at line 2912 checks EXECUTE/FUNCTION. `getFunctions()` returns `ImmutableList.of()` when denied (line 1359). Tests `testValidatePrivilege_executeMapsToFunction` and `testValidatePrivilege_executeDenied_throwsNotFound` verify both paths |
| 4 | Definer-rights preserved -- privilege checked on outermost entity only | VERIFIED | `validatePrivilege()` signature takes a single `NamespaceKey` (line 2805), inherently checks only one entity. ViewExpander handles inner table resolution under view owner identity (existing mechanism, no changes needed). Test `testValidatePrivilege_definerRights_onlyOutermostChecked` verifies exactly 1 call to `hasPrivilege` |
| 5 | System user ($dremio$) bypasses all privilege checks | VERIFIED | `SystemUser.isSystemUserName(userName)` check at lines 2812, 2872, 2903 returns immediately in all 3 methods. Test `testValidatePrivilege_systemUser_bypasses` confirms no RbacService interaction for $dremio$ |
| 6 | Feature flag OFF = no enforcement, identical to pre-RBAC behavior | VERIFIED | `dremioConfig == null \|\| !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` at lines 2807, 2867, 2898 returns early in all 3 methods. Tests `testValidatePrivilege_rbacDisabled_noEnforcement` and `testValidatePrivilege_nullDremioConfig_noEnforcement` verify both flag-OFF and null-config paths |
| 7 | CREATE OR REPLACE VIEW denied when user lacks CREATE_VIEW privilege | VERIFIED | `CreateOrUpdateViewHandler.java` line 105: `catalog.validatePrivilege(resolvedViewPath, SqlGrant.Privilege.CREATE_VIEW)`. `Privilege.ALTER` no longer present in the file (0 matches). `resolveRbacObjectType()` maps CREATE_VIEW to "VDS" (line 2843). Test `testValidatePrivilege_createViewMapsToVds` verifies |

**Score:** 7/7 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` | RbacService registration via `registry.bind()` | VERIFIED | Line 1035: `registry.bind(RbacService.class, rbacServiceInstance)`. Stores created with `Provider<KVStoreProvider>` (non-legacy). RbacService passed to CatalogServiceImpl at line 1061 |
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogServiceImpl.java` | `Provider<RbacService>` field, constructor param, passed to createCatalog | VERIFIED | Field at line 165, public constructor param at line 187, test constructor param at line 234, assignment at line 258. `createCatalog()` passes `rbacServiceProvider.get()` with null guard at line 994 |
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` | rbacService + dremioConfig fields, validatePrivilege(), isRbacDeniedForVds(), isRbacDeniedForFunction(), resolveRbacObjectType() | VERIFIED | Fields at lines 221-222, constructor params at 241-242, assignments at 265-266. validatePrivilege() at 2805, resolveRbacObjectType() at 2839, isRbacDeniedForVds() at 2860, isRbacDeniedForFunction() at 2896. All 4 resolveCatalog methods pass through at lines 1605-1606, 1630-1631, 1654-1655, 1680-1681 |
| `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java` | CREATE_VIEW privilege check | VERIFIED | Line 105: `SqlGrant.Privilege.CREATE_VIEW`. Zero matches for `Privilege.ALTER` |
| `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` | 10 RBAC enforcement tests | VERIFIED | 56 total @Test annotations (46 existing + 10 new). Mock infrastructure at lines 143-144 (`mock(RbacService.class)`, `mock(DremioConfig.class)`). `newCatalogImplForUser()` helper at line 183. All 10 tests verified at lines 1426-1579 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| DACDaemonModule.java | CatalogServiceImpl constructor | `registry.provider(RbacService.class)` | WIRED | Line 1061: passed as final argument to `new CatalogServiceImpl(...)` |
| CatalogServiceImpl.createCatalog() | CatalogImpl constructor | `rbacServiceProvider.get()` | WIRED | Line 994: `rbacServiceProvider != null ? rbacServiceProvider.get() : null` passed as 17th arg with null-safe guard |
| CatalogImpl.validatePrivilege() | RbacService.hasPrivilege() | Direct call after flag + system-user checks | WIRED | Line 2826: `rbacService.hasPrivilege(userName, rbacPrivilege, rbacObjectType, objectPath)` |
| CatalogImpl.getTable() | isRbacDeniedForVds() | Post-resolution check returning null if denied | WIRED | 4 call sites: lines 290, 299, 312, 320 covering getTableNoResolve, getTableNoColumnCount, and getTable (both branches) |
| CatalogImpl.getFunctions() | isRbacDeniedForFunction() | Entry-point check returning empty list if denied | WIRED | Line 1358: `isRbacDeniedForFunction(resolvedPath != null ? resolvedPath : path.toNamespaceKey())` |
| CreateOrUpdateViewHandler | catalog.validatePrivilege() | Privilege.CREATE_VIEW argument | WIRED | Line 105: `catalog.validatePrivilege(resolvedViewPath, SqlGrant.Privilege.CREATE_VIEW)` |
| CatalogImpl.resolveCatalog() x4 | new CatalogImpl() | rbacService, dremioConfig as last 2 args | WIRED | Lines 1605-1606, 1630-1631, 1654-1655, 1680-1681 -- all 4 methods pass through both fields |
| Test call sites (8 files) | CatalogServiceImpl/CatalogImpl constructors | `() -> null` for rbacServiceProvider | WIRED | SabotNode.java (line 560), TestCatalogServiceImpl.java (line 331), and 6 others all updated |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| **ENFC-01** | 04-01, 04-02, 04-03 | Deny-by-default -- user with no grant cannot SELECT a VDS | SATISFIED | `validatePrivilege()` throws "Table not found" when `hasPrivilege()` returns false. `isRbacDeniedForVds()` causes `getTable()` to return null. Test `testValidatePrivilege_noGrant_throwsNotFound` verifies |
| **ENFC-02** | 04-01, 04-02, 04-03 | User with SELECT grant can query VDS | SATISFIED | `validatePrivilege()` returns normally when `hasPrivilege()` returns true. `isRbacDeniedForVds()` returns false. Test `testValidatePrivilege_withGrant_passes` verifies |
| **ENFC-03** | 04-01, 04-02, 04-03 | User with EXECUTE grant can call UDF; without cannot | SATISFIED | `resolveRbacObjectType()` maps EXECUTE to FUNCTION. `isRbacDeniedForFunction()` checks EXECUTE on FUNCTION. Tests `testValidatePrivilege_executeMapsToFunction` and `testValidatePrivilege_executeDenied_throwsNotFound` verify both paths |
| **ENFC-06** | 04-01, 04-03 | System user ($dremio$) bypasses all privilege checks | SATISFIED | `SystemUser.isSystemUserName(userName)` check in all 3 enforcement methods (lines 2812, 2872, 2903). Test `testValidatePrivilege_systemUser_bypasses` confirms `verifyNoInteractions(rbacService)` |
| **ENFC-07** | 04-01, 04-03 | Definer-rights preserved -- only outermost entity checked | SATISFIED | `validatePrivilege()` takes single NamespaceKey, checks only that entity. ViewExpander resolves inner tables under view owner identity (existing mechanism preserved). Test `testValidatePrivilege_definerRights_onlyOutermostChecked` verifies single hasPrivilege call |
| **ENFC-08** | 04-02, 04-03 | CREATE_VIEW checked for CREATE OR REPLACE VIEW | SATISFIED | CreateOrUpdateViewHandler line 105: `Privilege.CREATE_VIEW`. No `Privilege.ALTER` remains. Test `testValidatePrivilege_createViewMapsToVds` verifies mapping |
| **BOOT-02** | 04-01, 04-03 | System starts with RBAC disabled, no enforcement, existing behavior preserved | SATISFIED | Feature flag check is first guard in all 3 methods. Returns early when flag OFF or config null. Tests `testValidatePrivilege_rbacDisabled_noEnforcement` and `testValidatePrivilege_nullDremioConfig_noEnforcement` verify both paths |

No orphaned requirements found. All 7 requirement IDs (ENFC-01, ENFC-02, ENFC-03, ENFC-06, ENFC-07, ENFC-08, BOOT-02) from REQUIREMENTS.md Phase 4 mapping are covered.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| CatalogImpl.java | 490, 1143, etc. | TODO comments | Info | Pre-existing TODOs unrelated to RBAC (DX-65443, DX-44984, etc.) -- not introduced by this phase |

No RBAC-specific anti-patterns found. No placeholder implementations, no empty handlers, no stub returns in RBAC code. All `return null` in getTable paths are intentional information-hiding behavior (simulating "not found").

### Human Verification Required

### 1. End-to-end SELECT query denied for VDS

**Test:** Enable RBAC flag, create a user without SELECT grant, run `SELECT * FROM myspace.myview`
**Expected:** User receives "Table 'myspace.myview' not found" error
**Why human:** Unit tests mock DatasetManager; end-to-end path through query planner to getTable() null return cannot be verified programmatically

### 2. System user internal operations unaffected

**Test:** Enable RBAC flag, trigger metadata refresh or reflection job
**Expected:** Internal operations ($dremio$ system user) complete without RBAC errors
**Why human:** SystemUser bypass verified in unit test, but real coordinator behavior with async jobs needs runtime verification

### 3. Feature flag OFF preserves exact pre-RBAC behavior

**Test:** Start system with default config (RBAC OFF), run all existing queries
**Expected:** Zero behavioral changes -- every query that worked before still works
**Why human:** Unit tests verify no RbacService calls, but full system regression needs actual execution

### 4. Definer-rights model with nested views

**Test:** Create view A referencing table B, grant SELECT on A to user, user queries A
**Expected:** Query succeeds -- inner table B resolved under view A's owner identity, not the querying user
**Why human:** ViewExpander behavior is an existing mechanism not modified by this phase; cannot verify cross-component interaction statically

### Gaps Summary

No gaps found. All 7 observable truths verified with code-level evidence and unit test coverage. All 7 requirement IDs satisfied. All artifacts exist, are substantive (not stubs), and are properly wired through the DI chain from DACDaemonModule through CatalogServiceImpl to CatalogImpl. The 3-step enforcement chain (flag check, system-user bypass, hasPrivilege denial) is consistently applied across validatePrivilege(), isRbacDeniedForVds(), and isRbacDeniedForFunction(). All 4 resolveCatalog methods carry rbacService and dremioConfig through. All 8 test call sites updated for constructor compatibility. 10 new unit tests covering all enforcement paths.

---

_Verified: 2026-02-18T12:00:00Z_
_Verifier: Claude (gsd-verifier)_
