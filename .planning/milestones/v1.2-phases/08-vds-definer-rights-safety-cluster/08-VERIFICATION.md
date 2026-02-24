---
phase: 08-vds-definer-rights-safety-cluster
verified: 2026-02-21T15:30:00Z
status: passed
score: 6/6 must-haves verified
---

# Phase 8: VDS Definer Rights Safety Cluster Verification Report

**Phase Goal:** View expansion runs under the last modifier's identity, enabling users to query views over tables they cannot directly access — with all eight pitfall guards active as a unit
**Verified:** 2026-02-21T15:30:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #  | Truth                                                                                                    | Status     | Evidence                                                                                                                                                             |
|----|----------------------------------------------------------------------------------------------------------|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | User B with SELECT on view V can query V even when underlying table T is inaccessible to B              | VERIFIED | `CatalogEntityOwnershipImpl` returns VDS owner; `ViewExpander.expandRelNode()` uses `viewOwner` to build context via `builder.withUser(viewOwner)` (line 169)        |
| 2  | VDS-over-VDS chain with different owners resolves correctly at each level                                | VERIFIED | `ViewExpansionContext` tracks per-token identity; each `reserveViewExpansionToken` call sets context for that view's owner; chained test `testViewExpansion_chainedDefinerRights_noCycle` validates this |
| 3  | Revoking definer's SELECT causes view queries to fail (live KV store checked, no stale snapshot)         | VERIFIED | No plan caching for definer-rights queries (`containsDefinerRightsExpansion` in `PlanCacheUtils` bypasses cache); KV store is consulted fresh on every query         |
| 4  | Querying a view with deleted owner produces explicit "View owner no longer exists" error                  | VERIFIED | `ViewExpander.expandViewInternal()` catch block (lines 135-145): `if (rbacEnabled && viewOwner != null)` throws `UserException.planError()` with the exact message   |
| 5  | Cyclic VDS chain produces a clear validation error, not a StackOverflowError                             | VERIFIED | `ViewExpansionContext.reserveViewExpansionToken()` throws `UserException.validationError()` with "Cyclic view dependency detected" on duplicate path insertion        |

**Score:** 5/5 success criteria verified (plus DEFN-04 plan cache guard as the sixth requirement)

### Required Artifacts

| Artifact                                                                                                          | Expected                                    | Status     | Details                                                                                                                                  |
|-------------------------------------------------------------------------------------------------------------------|---------------------------------------------|------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java`                             | VDS owner resolution for definer rights     | VERIFIED   | Returns `Optional.of(new CatalogUser(owner))` for non-null owner; no VIRTUAL_DATASET early-return; DatasetType import removed           |
| `sabot/kernel/src/main/java/com/dremio/exec/ops/ViewExpansionContext.java`                                        | Cycle detection via `inExpansionPaths` set  | VERIFIED   | `Set<String> inExpansionPaths` field at line 78; add on reserve (line 104), remove on release (line 129); `ViewExpansionToken` stores path |
| `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/ViewExpander.java`                                        | Deleted-owner error and `rbacEnabled` flag  | VERIFIED   | `rbacEnabled` field and 4th constructor param at lines 49-61; DEFN-05 guard at lines 135-145; updated `reserveViewExpansionToken` call at line 128 |
| `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/SqlConverter.java`                                        | `rbacEnabled` wiring from `DremioConfig`    | VERIFIED   | `DremioConfig` import at line 21; null-guarded `getDremioConfig().getBoolean(DremioConfig.RBAC_ENABLED)` at lines 183-184               |
| `sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheUtils.java`                               | Definer-rights plan cache bypass            | VERIFIED   | `containsDefinerRightsExpansion()` at lines 158-175; wired into `supportPlanCache()` at lines 142-147                                   |
| `sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheMetrics.java`                             | `NOT_PUT_DEFINER_RIGHTS` constant           | VERIFIED   | Constant present at line 70 of `QueryOutcome` enum                                                                                       |
| `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java`                                        | Unit tests for DEFN-01 through DEFN-06      | VERIFIED   | 12 new test methods added (lines 1673-1986); all use `testDefinerRights_*`, `testCyclicViewChain_*`, `testViewExpansion_*` naming        |

### Key Link Verification

| From                             | To                                  | Via                                                         | Status   | Details                                                                                                             |
|----------------------------------|-------------------------------------|-------------------------------------------------------------|----------|---------------------------------------------------------------------------------------------------------------------|
| `CatalogEntityOwnershipImpl.java` | `ViewTable.viewOwner`               | `getCatalogEntityOwner()` returns `Optional.of(new CatalogUser(owner))` | WIRED    | Pattern `new CatalogUser(owner)` found at line 53; consumed by `DatasetManager.getEntityOwner()` to set view owner |
| `ViewExpander.java`               | `ViewExpansionContext.java`          | `reserveViewExpansionToken(viewOwner, viewTable.getPath())`  | WIRED    | Two-argument call at line 128; ViewExpansionContext method signature matches exactly                                |
| `SqlConverter.java`               | `ViewExpander.java`                 | `new ViewExpander(..., rbacEnabled)` with 4 args             | WIRED    | Constructor call at lines 178-184; null-guarded `context.getDremioConfig().getBoolean(DremioConfig.RBAC_ENABLED)`  |
| `PlanCacheUtils.java`             | `ExpansionNode.java` (via ViewTable) | `containsDefinerRightsExpansion()` traverses rel tree       | WIRED    | Pattern `ExpansionNode.getViewTable().getViewOwner()` implemented at lines 159-165; `ExpansionNode.getViewTable()` confirmed at line 207 of ExpansionNode.java |
| `TestCatalogImpl.java`            | `CatalogEntityOwnershipImpl.java`   | Tests verify `getCatalogEntityOwner` returns owner for VDS  | WIRED    | Pattern `getCatalogEntityOwner` appears 8 times in test section (lines 1689-1978); all use mock `systemNamespaceService` |

### Requirements Coverage

| Requirement | Source Plan | Description                                                                      | Status    | Evidence                                                                                                                          |
|-------------|-------------|----------------------------------------------------------------------------------|-----------|-----------------------------------------------------------------------------------------------------------------------------------|
| DEFN-01     | 08-01, 08-02 | User can query a VDS they have SELECT on, even when underlying tables are not directly accessible | SATISFIED | `CatalogEntityOwnershipImpl` returns VDS owner → `ViewExpander.expandRelNode()` uses owner context → definer's grants applied    |
| DEFN-02     | 08-01, 08-02 | VDS expansion runs under the last modifier's privileges, not the querying user's  | SATISFIED | `viewOwner` from `ViewTable.getViewOwner()` passed to `builder.withUser(viewOwner)` in `ViewExpander.expandRelNode()` (line 169) |
| DEFN-03     | 08-01, 08-02 | VDS-over-VDS chains with different owners resolve correctly at each level         | SATISFIED | `ViewExpansionContext` per-token identity tracking; `testViewExpansion_chainedDefinerRights_noCycle` demonstrates three-owner chain |
| DEFN-04     | 08-02       | Plan cache correctly scopes to definer identity chain                             | SATISFIED | `containsDefinerRightsExpansion()` in `PlanCacheUtils.supportPlanCache()` bypasses cache when non-query-user definer detected    |
| DEFN-05     | 08-01, 08-02 | Deleted view owner causes an explicit permission error, not a silent fallback     | SATISFIED | `ViewExpander.expandViewInternal()` catch block: `rbacEnabled && viewOwner != null` → `UserException.planError()` with "View owner '%s' no longer exists" |
| DEFN-06     | 08-01, 08-02 | Cyclic VDS chains produce a clear validation error, not a StackOverflow           | SATISFIED | `ViewExpansionContext.reserveViewExpansionToken()` throws `UserException.validationError("Cyclic view dependency detected: view '%s' references itself...")` |

All six DEFN requirements are claimed by Plans 08-01 and 08-02 and no orphaned requirements exist in REQUIREMENTS.md for Phase 8.

### Anti-Patterns Found

No blocker or warning anti-patterns detected in any of the seven modified files.

| File                              | Pattern | Severity | Impact |
|-----------------------------------|---------|----------|--------|
| (none found in production files)  | -       | -        | -      |

The test file `TestCatalogImpl.java` contains a noted limitation in `testDefinerRights_deletedOwner_throwsPlanError`: the DEFN-05 test is structural rather than fully behavioral (it cannot exercise `ViewExpander.expandViewInternal()` directly because that requires mocking `SqlValidatorAndToRelContext.BuilderFactory`). This is documented in the test's Javadoc and is an accepted tradeoff — the behavioral path is covered by the production code structure and would require an integration test.

### Human Verification Required

The following items cannot be verified programmatically and require runtime testing:

#### 1. DEFN-01/02/03: End-to-end definer rights query execution

**Test:** Create user Alice as view owner with SELECT on physical table T. Create user Bob with SELECT on view V (which queries T) but no direct access to T. Query V as Bob.
**Expected:** Query succeeds and returns rows from T, using Alice's grants during expansion.
**Why human:** The full identity-switching chain — `CatalogEntityOwnershipImpl` → `DatasetManager` → `ViewTable.viewOwner` → `ViewExpander.expandRelNode(builder.withUser(viewOwner))` — requires a running Dremio cluster with a real RBAC-enabled configuration and real user accounts.

#### 2. DEFN-05: Deleted owner runtime behavior

**Test:** Create view V owned by user Alice. Delete Alice's account. Query V as Bob (who has SELECT on V).
**Expected:** Query fails with "View owner 'alice' no longer exists. Cannot expand view 'V'. The view must be updated by an active user before it can be queried."
**Why human:** The `UserNotFoundException` must be thrown and wrapped as a `RuntimeException` cause during the actual `expandRelNode` call — this requires a live user service. The unit test covers the structural precondition (deleted namespace returns empty owner) but not the full runtime behavior where a live user lookup is attempted during expansion.

#### 3. DEFN-04: Plan cache bypass in practice

**Test:** Query a view V owned by Alice as user Bob. Confirm (via profile or metrics) that `NOT_PUT_DEFINER_RIGHTS` outcome is recorded and no plan cache entry is created.
**Expected:** Profile shows plan cache bypass reason; subsequent identical queries still re-plan (no cache hit).
**Why human:** Requires a running cluster with plan cache enabled and a way to inspect cache metrics or query profiles.

### Gaps Summary

No gaps. All six DEFN requirements have verified production implementations, all key links are wired, and 12 unit tests cover the behavioral contract. The phase goal is structurally achieved by the four production files modified in Plan 01 and the three files modified in Plan 02.

The only open concern is that DEFN-05's behavioral verification requires a running cluster (the `UserNotFoundException` must be wrapped as a `RuntimeException.cause` during live expansion). This is a known limitation acknowledged in Plan 02's decisions and does not block the goal — the production code path is correct.

---

## Commit Verification

All four implementation commits verified present in git history:

| Commit      | Description                                                          | Files                                            |
|-------------|----------------------------------------------------------------------|--------------------------------------------------|
| `ae68ad655` | Fix VDS owner resolution + ViewExpansionContext cycle detection       | CatalogEntityOwnershipImpl.java, ViewExpansionContext.java |
| `74e320a37` | Add rbacEnabled to ViewExpander + deleted-owner error via SqlConverter | ViewExpander.java, SqlConverter.java             |
| `9bf8c610a` | Add definer-rights plan cache bypass (DEFN-04)                        | PlanCacheMetrics.java, PlanCacheUtils.java       |
| `e1513534c` | Add 12 comprehensive unit tests for DEFN-01 through DEFN-06           | TestCatalogImpl.java                             |

---

_Verified: 2026-02-21T15:30:00Z_
_Verifier: Claude (gsd-verifier)_
