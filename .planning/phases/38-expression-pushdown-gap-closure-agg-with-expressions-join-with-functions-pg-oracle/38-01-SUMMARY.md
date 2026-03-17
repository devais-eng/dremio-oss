---
phase: 38-expression-pushdown-gap-closure-agg-with-expressions-join-with-functions-pg-oracle
plan: 01
subsystem: database
tags: [calcite, jdbc, pushdown, aggregation, expression, hep]

# Dependency graph
requires:
  - phase: 37-expression-pushdown-for-functions-having-and-order-by-expressions-pgvector-foundation
    provides: PushdownFunctionRegistry, JdbcPushSortWithExpressionsHep, sortKeyExpressions pattern in JdbcScanPrel
provides:
  - JdbcPushAggWithExpressionsHep: HEP-phase rule for GROUP BY and AGG operand function expressions
  - JdbcScanPrel.cloneWithAggregationExpressions(): new clone method for expression-based agg
  - JdbcScanPrel getPhysicalOperator() extend/aggregate/trim pattern for expression-based agg
  - JdbcPushJoinIntoScan constructor injection: findJdbcScan() allows whitelisted function expressions
affects: [39-pgvector-expression-pushdown, integration-tests-pushdown]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - extend/aggregate/trim: JdbcProject(extend) -> JdbcAggregate -> JdbcProject(trim) for expression-based aggregation
    - constructor injection for PushdownFunctionRegistry in all join/filter/agg HEP rules
    - HEP-phase rule for expression-containing aggregates mirroring JdbcPushSortWithExpressionsHep pattern

key-files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushAggWithExpressionsHep.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushJoinIntoScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcJoinRulesFactory.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestCalciteDialectSql.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestJdbcPushJoinIntoScan.java

key-decisions:
  - "JdbcPushJoinIntoScan INSTANCE singleton removed; replaced with constructor-injected PushdownFunctionRegistry in both JdbcJoinRulesFactory (global) and usage sites"
  - "findJdbcScan() method changed from private static to package-private static to allow registry parameter and direct test access"
  - "LOGICAL case in JdbcRulesFactory deliberately kept with only JdbcScanDrule (JOIN rule stays in JdbcJoinRulesFactory only to avoid duplicate registration)"
  - "extend/aggregate/trim pattern: group key expressions mapped to _group_key_N columns, agg operand expressions to _agg_operand_N columns in extended JdbcProject"
  - "groupKeyExpressions and aggOperandExpressions stored as null when simple column-refs to avoid breaking existing cloneWithAggregation() path"

requirements-completed: [GAP-01, GAP-02, GAP-03, GAP-04]

# Metrics
duration: 45min
completed: 2026-03-17
---

# Phase 38 Plan 01: Expression Pushdown Gap Closure Summary

**GROUP BY and AGG operand expression pushdown via JdbcPushAggWithExpressionsHep + extend/aggregate/trim pattern; JOIN with whitelisted CAST/function conditions via findJdbcScan() registry validation**

## Performance

- **Duration:** ~45 min
- **Started:** 2026-03-17T17:30:00Z
- **Completed:** 2026-03-17T18:19:41Z
- **Tasks:** 2
- **Files modified:** 7 (1 created + 6 modified)

## Accomplishments

- Created `JdbcPushAggWithExpressionsHep`: HEP-phase Calcite rule that matches `AggregatePrel(ProjectPrel(JdbcScanPrel))` and stores GROUP BY key expressions and aggregate operand expressions on the scan for SQL generation (closes Gaps 1, 3, 4)
- Extended `JdbcScanPrel` with `groupKeyExpressions`/`aggOperandExpressions` fields and `cloneWithAggregationExpressions()` method; added extend/aggregate/trim pattern in `getPhysicalOperator()` step 8a that builds `JdbcProject(extend) -> JdbcAggregate -> JdbcProject(trim)` for expression-based aggregation
- Fixed `JdbcPushJoinIntoScan.findJdbcScan()` (Gap 2): removed blanket `return null` for non-`RexInputRef` expressions in `LogicalProject`; replaced with `registry.isExpressionPushable()` check allowing whitelisted functions (CAST, EXTRACT, UPPER, etc.) through while correctly declining non-whitelisted ones; removed INSTANCE singleton, added constructor injection
- Added 5 new unit tests: 3 in `TestCalciteDialectSql` (GROUP BY expression rendering, AGG operand expression rendering, bare aggregate rendering) and 2 in `TestJdbcPushJoinIntoScan` (CAST condition allowed, non-whitelisted function declined)

## Task Commits

1. **Task 1: JdbcPushAggWithExpressionsHep + JdbcScanPrel + JdbcRulesFactory** - `6c11d7c97` (feat)
2. **Task 2: JdbcPushJoinIntoScan Gap 2 fix + unit tests** - `af30aeb47` (feat)

## Files Created/Modified

- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushAggWithExpressionsHep.java` - New HEP rule: AggregatePrel(ProjectPrel(JdbcScanPrel)) matching, PHASE_1of1 guard, registry validation, cloneWithAggregationExpressions()
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java` - Added groupKeyExpressions, aggOperandExpressions fields; cloneWithAggregationExpressions(); extend/aggregate/trim in getPhysicalOperator() step 8a
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java` - Added JdbcPushAggWithExpressionsHep to PHYSICAL_HEP rule set
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushJoinIntoScan.java` - Removed INSTANCE singleton; added PushdownFunctionRegistry field; findJdbcScan() now validates via registry; added hasNonTrivialProject() helper
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcJoinRulesFactory.java` - Replaced INSTANCE with new JdbcPushJoinIntoScan(StandardPushdownFunctionRegistry.INSTANCE)
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestCalciteDialectSql.java` - Added testGroupByExpressionRendering, testAggOperandExpressionRendering, testBareAggregateRendering
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestJdbcPushJoinIntoScan.java` - Added testJoinWithCastConditionAllowed, testJoinWithNonWhitelistedFunctionDeclines

## Decisions Made

- **LOGICAL case kept single-rule**: JdbcRulesFactory LOGICAL still only has JdbcScanDrule. The plan instruction to "replace JdbcPushJoinIntoScan.INSTANCE in LOGICAL case" referred to JdbcJoinRulesFactory (the global RulesFactory), not JdbcRulesFactory's LOGICAL case. Adding JdbcPushJoinIntoScan to JdbcRulesFactory.LOGICAL would have duplicated the rule registration and broken TestJdbcRulesFactory.logicalPhaseReturnsOneRule.
- **groupKeyExpressions null for simple column-ref agg**: Stored as null (not empty list) to avoid touching the existing cloneWithAggregation() code path. Only non-null when JdbcPushAggWithExpressionsHep fires.
- **extend/aggregate/trim column naming**: `_group_key_N` and `_agg_operand_N` convention for extended project columns — mirrors `_sort_key_N` established in Phase 37.
- **findJdbcScan() changed to package-private**: Originally private static; changed to package-private static to allow the PushdownFunctionRegistry parameter and enable direct testing without reflection.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] TestJdbcRulesFactory.logicalPhaseReturnsOneRule broken by erroneous LOGICAL rule addition**
- **Found during:** Task 1 verification (full test suite run)
- **Issue:** I initially added JdbcPushJoinIntoScan to JdbcRulesFactory LOGICAL case, which broke the test expecting exactly 1 rule. The plan instruction actually referred to JdbcJoinRulesFactory (the global RulesFactory), not JdbcRulesFactory.
- **Fix:** Reverted JdbcRulesFactory LOGICAL to only JdbcScanDrule; updated JdbcJoinRulesFactory instead
- **Files modified:** plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java
- **Committed in:** 6c11d7c97 (Task 1 commit, fix included before test verification)

---

**Total deviations:** 1 auto-fixed (Rule 1 - logic error in LOGICAL rule placement)
**Impact on plan:** Auto-fix required to keep existing tests passing. No scope creep.

## Issues Encountered

None beyond the deviation documented above.

## Next Phase Readiness

- All four expression pushdown gaps are now closed at the rule/infrastructure level
- Phase 38 Plan 02 can proceed with Oracle dialect validation and integration tests
- The extend/aggregate/trim pattern is tested via unit tests; integration tests against real PG/Oracle databases would further validate end-to-end SQL generation

---
*Phase: 38-expression-pushdown-gap-closure-agg-with-expressions-join-with-functions-pg-oracle*
*Completed: 2026-03-17*
