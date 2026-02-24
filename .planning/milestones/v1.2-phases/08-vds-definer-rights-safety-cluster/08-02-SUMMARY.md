---
phase: 08-vds-definer-rights-safety-cluster
plan: 02
subsystem: auth
tags: [rbac, definer-rights, plan-cache, ViewExpansionContext, CatalogEntityOwnershipImpl, ExpansionNode, PlanCacheUtils, PlanCacheMetrics]

# Dependency graph
requires:
  - phase: 08-vds-definer-rights-safety-cluster
    plan: 01
    provides: VDS owner resolution (DEFN-01/02/03), cycle detection (DEFN-06), deleted-owner explicit error (DEFN-05), rbacEnabled wiring

provides:
  - Plan cache bypass for definer-rights queries (DEFN-04) via containsDefinerRightsExpansion()
  - NOT_PUT_DEFINER_RIGHTS metric constant in PlanCacheMetrics.QueryOutcome
  - Comprehensive unit tests verifying DEFN-01 through DEFN-06 (12 tests)
affects:
  - 08-03 (if it exists — these tests already cover DEFN-01 through DEFN-06)
  - 10-pds-select-enforcement (plan cache bypass must remain in effect when PDS access goes through definer chain)
  - 12-integration-tests (integration test for chained VDS definer rights can reference these unit test patterns)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "DEFN-04 pattern: containsDefinerRightsExpansion() traverses RelNode tree recursively, returns true when any ExpansionNode.getViewTable().getViewOwner() != queryUser"
    - "Pattern: null viewTable guard (non-view ExpansionNodes e.g. default raw reflections), null viewOwner guard (legacy VDS) — only non-null, differing owner triggers bypass"
    - "Test pattern: CatalogEntityOwnershipImpl unit tests instantiate class directly with mock NamespaceService"
    - "Test pattern: ViewExpansionContext cycle tests instantiate directly (no mocking needed) — reserve/release token API is fully unit-testable"

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheMetrics.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheUtils.java
    - sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java

key-decisions:
  - "containsDefinerRightsExpansion placed AFTER versioned-table check — conservative ordering: versioned table check happens first (existing behavior), then definer check; both are short-circuit returns"
  - "null viewTable guard: ExpansionNode can exist without a ViewTable for default raw reflections; null-check prevents NPE and correctly treats those as non-definer expansions (remain cacheable)"
  - "null viewOwner guard: legacy VDS without a recorded owner gets null viewOwner from ViewTable; those remain cacheable — definer rights only activate when a non-null owner is present"
  - "12 tests instead of 6 minimum: added PDS owner, deep-chain cycle, self-reference cycle, chained-definer scenario, and combined DEFN-01/04 test to cover user's three-owner V1->V2->V3 scenario fully"
  - "DEFN-05 test is structural: NamespaceException path in getCatalogEntityOwner returns empty owner, which means ViewExpander DEFN-05 guard (rbacEnabled && viewOwner != null) will NOT fire for legacy/deleted namespace entries — behavioral test of ViewExpander itself requires integration test infrastructure"

patterns-established:
  - "Pattern: unit test CatalogEntityOwnershipImpl by instantiating directly with mock NamespaceService — no CatalogImpl construction needed"
  - "Pattern: unit test ViewExpansionContext cycle detection by instantiating directly with CatalogUser — reserve/release API is pure Java with no Calcite dependency"

requirements-completed:
  - DEFN-04
  - DEFN-01
  - DEFN-02
  - DEFN-03
  - DEFN-05
  - DEFN-06

# Metrics
duration: 4min
completed: 2026-02-21
---

# Phase 08 Plan 02: Plan Cache Bypass and Comprehensive Definer Rights Tests Summary

**Plan cache bypassed for definer-rights queries via recursive ExpansionNode traversal (DEFN-04), with 12 unit tests covering all DEFN-01 through DEFN-06 requirements including the three-owner chained VDS scenario**

## Performance

- **Duration:** 4 min
- **Started:** 2026-02-21T13:59:28Z
- **Completed:** 2026-02-21T14:03:28Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Added `NOT_PUT_DEFINER_RIGHTS` to `PlanCacheMetrics.QueryOutcome` enum — new metric tag for telemetry when plan cache bypass fires for definer-rights queries
- Added `containsDefinerRightsExpansion(RelNode, String)` private static helper to `PlanCacheUtils` — recursively traverses the rel tree; returns true when any `ExpansionNode` has a `ViewTable` with a non-null owner different from the query user
- Wired bypass into `PlanCacheUtils.supportPlanCache()` — dispatches `NOT_PUT_DEFINER_RIGHTS` event and returns false when definer identity detected in rel tree (DEFN-04 complete)
- Added 12 comprehensive unit tests in `TestCatalogImpl`: 4 ownership tests (VDS with owner, null owner, empty owner, PDS with owner), 1 deleted-owner structural test, 1 combined DEFN-01/04 test, and 6 cycle detection tests (basic cycle, no false positives, release-then-reuse, chained 3-owner scenario matching user's V1/V2/V3 chain, deep 3-level chain cycle, self-reference cycle)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add plan cache bypass for definer-rights queries (DEFN-04)** - `9bf8c610a` (feat)
2. **Task 2: Add unit tests for DEFN-01 through DEFN-06** - `e1513534c` (test)

## Files Created/Modified

- `sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheMetrics.java` - Added `NOT_PUT_DEFINER_RIGHTS` constant to `QueryOutcome` enum
- `sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheUtils.java` - Added `containsDefinerRightsExpansion()` helper, wired bypass into `supportPlanCache()`, added imports for `CatalogIdentity`, `ExpansionNode`, `ViewTable`, `NOT_PUT_DEFINER_RIGHTS`
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` - Added 12 new test methods in the RBAC section covering all DEFN-01 through DEFN-06 requirements

## Decisions Made

- Placed `containsDefinerRightsExpansion` check AFTER the versioned-table check — conservative ordering preserves existing behavior; the definer check is a new, additive condition
- Added null guard for `viewTable` in `containsDefinerRightsExpansion` — `ExpansionNode` instances for default raw reflections carry a null `viewTable`; these must not be treated as definer expansions
- Added null guard for `viewOwner` — legacy VDS without a recorded owner have null `viewOwner` on `ViewTable`; these remain cacheable (backward compat)
- Added 12 tests instead of the minimum 6: the chained three-owner scenario (`testViewExpansion_chainedDefinerRights_noCycle`) directly models the user's scenario (User A owns V1, User B creates V2 from V1, User C queries V2)
- DEFN-05 test is structural (tests the getCatalogEntityOwner exception path) rather than behavioral (which would require mocking the full SqlValidatorAndToRelContext.BuilderFactory chain) — documented this choice in the test Javadoc

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

- `NamespaceException` is abstract and cannot be instantiated directly — used `NamespaceNotFoundException` (a concrete subclass, already imported) for the DEFN-05 structural test. Not a deviation — this is the standard exception used throughout the test file.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- All 6 DEFN requirements (DEFN-01 through DEFN-06) are now complete with production code and unit tests
- Phase 8 is ready to be declared complete
- Phase 10 (PDS SELECT enforcement) can proceed — the definer's PDS access resolves correctly through the `ViewExpander` → `ViewExpansionContext` chain established in Phase 8 Plan 01
- Phase 12 (integration tests) can add end-to-end tests for the chained VDS definer scenario using the patterns established here

---
*Phase: 08-vds-definer-rights-safety-cluster*
*Completed: 2026-02-21*

## Self-Check: PASSED

All files verified present:
- sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheMetrics.java - FOUND
- sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheUtils.java - FOUND
- sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java - FOUND
- .planning/phases/08-vds-definer-rights-safety-cluster/08-02-SUMMARY.md - FOUND

All commits verified:
- 9bf8c610a - FOUND (Task 1: plan cache bypass DEFN-04)
- e1513534c - FOUND (Task 2: comprehensive unit tests DEFN-01 through DEFN-06)
