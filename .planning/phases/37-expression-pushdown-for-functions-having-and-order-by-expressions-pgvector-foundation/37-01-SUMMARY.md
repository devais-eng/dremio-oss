---
phase: 37-expression-pushdown
plan: 01
subsystem: jdbc-base-planning
tags: [pushdown, having, count-distinct, function-whitelist, planning]
dependency_graph:
  requires: [36-03-PLAN.md]
  provides: [PushdownFunctionRegistry, StandardPushdownFunctionRegistry, JdbcPushHavingIntoScan, HAVING-pushdown, COUNT-DISTINCT-pushdown]
  affects: [JdbcScanPrel, JdbcPushFilterIntoScan, JdbcPushAggIntoScan, JdbcRulesFactory, JdbcStoragePlugin]
tech_stack:
  added: []
  patterns: [RexVisitorImpl whitelist, per-dialect function registry, HAVING via JdbcFilter-after-JdbcAggregate]
key_files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PushdownFunctionRegistry.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/StandardPushdownFunctionRegistry.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushHavingIntoScan.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestPushdownFunctionRegistry.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushFilterIntoScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushAggIntoScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestJdbcRulesFactory.java
decisions:
  - "PushdownFunctionRegistry interface uses RexVisitorImpl<Boolean> with recursive visitCall() for deep composition checking — UPPER(TRIM(col)) is accepted, UPPER(unknownUDF(col)) rejected"
  - "SqlKind.LOGICAL does not exist in Calcite 1.22.0 — replaced with explicit AND/OR/NOT individual kind checks"
  - "JdbcPushFilterIntoScan changed from singleton INSTANCE to constructor injection (takes PushdownFunctionRegistry parameter) — INSTANCE singleton removed"
  - "HAVING guard added to JdbcPushFilterIntoScan.matches(): !scan.hasAggregation() prevents WHERE-as-HAVING bug"
  - "COUNT(DISTINCT) allowed in JdbcPushAggIntoScan — filterArg >= 0 (FILTER clause) still rejected for all aggregate kinds"
  - "JdbcScanPrel full constructor extended with havingRex as last parameter — backward-compatible: all clone methods updated to pass havingRex through"
  - "TestJdbcRulesFactory rule count updated from 7 to 8 (added JdbcPushHavingIntoScan)"
metrics:
  duration_minutes: 16
  completed_date: "2026-03-16"
  tasks_completed: 2
  files_created: 4
  files_modified: 6
---

# Phase 37 Plan 01: PushdownFunctionRegistry and HAVING/COUNT(DISTINCT) Pushdown Summary

**One-liner:** Per-dialect function whitelist (PushdownFunctionRegistry) + HAVING clause pushdown via havingRex field on JdbcScanPrel + COUNT(DISTINCT) support + HAVING-as-WHERE guard fix.

## What Was Built

### Task 1: PushdownFunctionRegistry infrastructure

Created `PushdownFunctionRegistry` interface with:
- `isFunctionPushable(SqlOperator op)` — per-dialect function whitelist check
- `isExpressionPushable(RexNode expr)` default method — recursive RexNode tree walk via `RexVisitorImpl<Boolean>`

The visitor handles the full expression tree:
- `visitInputRef` and `visitLiteral` return true unconditionally
- `visitCall` allows AND/OR/NOT/comparison/arithmetic without whitelist check, then checks `isFunctionPushable()` for all other calls (CAST, TRIM, UPPER, etc.) before recursing into operands

Created `StandardPushdownFunctionRegistry` with:
- Whitelisted by `SqlKind`: CAST, FLOOR, CEIL, TRIM, LTRIM, RTRIM, EXTRACT, COALESCE, NULLIF
- Whitelisted by name (`OTHER_FUNCTION`): UPPER, LOWER, ABS, ROUND, SUBSTRING, CHAR_LENGTH, CHARACTER_LENGTH, LENGTH

Added `JdbcStoragePlugin.getPushdownFunctionRegistry()` returning `StandardPushdownFunctionRegistry.INSTANCE` — overridable by dialect-specific subclasses.

Unit tests verify: all 13 whitelisted operators pass, LOCALTIME/CURRENT_TIMESTAMP fail, UPPER(col) passes, UPPER(unknownUDF(col)) fails, LOWER(UPPER(col)) passes.

### Task 2: HAVING pushdown, filter guard, COUNT(DISTINCT), JdbcScanPrel field, rule registration

**JdbcScanPrel**: Added `havingRex` field (nullable `RexNode`). Updated full constructor (havingRex as last parameter), simple constructor passes null. Updated all clone methods (`cloneWithProject`, `cloneWithFilter`, `cloneWithLimit`, `cloneWithCollation`, `cloneWithAggregation`, `copy`) to carry `havingRex` through. Added `cloneWithHaving(RexNode)`, `getHavingRex()`, `hasHaving()` accessors. Added step 8.5 in `getPhysicalOperator()`: wraps JdbcAggregate+rename output with `JdbcRules.JdbcFilter` when `havingRex != null`. Added `pw.itemIf("having", havingRex, ...)` to `explainTerms()`.

**JdbcPushHavingIntoScan**: New rule matching `FilterPrel(JdbcScanPrel[hasAgg=true, !hasHaving])`. Takes `PushdownFunctionRegistry` in constructor. `onMatch` validates condition via `registry.isExpressionPushable()` then calls `scan.cloneWithHaving(condition)`. No index normalization — HAVING references aggregate output positions directly.

**JdbcPushFilterIntoScan**: Added `!scan.hasAggregation()` guard to `matches()`. Changed from singleton to constructor injection (takes `PushdownFunctionRegistry`). Added whitelist validation of normalized condition in `onMatch()`.

**JdbcPushAggIntoScan**: Replaced blanket `isDistinct()` rejection with selective check — allows `COUNT(DISTINCT)`, rejects DISTINCT for other aggregate kinds. Adds `filterArg >= 0` rejection (FILTER clause not supported).

**JdbcRulesFactory**: PHYSICAL case creates `PushdownFunctionRegistry registry = StandardPushdownFunctionRegistry.INSTANCE`, passes it to `new JdbcPushFilterIntoScan(registry)` and `new JdbcPushHavingIntoScan(registry)`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] SqlKind.LOGICAL does not exist in Calcite 1.22.0**
- **Found during:** Task 1 compilation
- **Issue:** The research document referenced `kind.belongsTo(SqlKind.LOGICAL)` but `LOGICAL` is not an EnumSet in the version of Calcite used by this project
- **Fix:** Replaced with explicit individual checks: `kind == SqlKind.AND || kind == SqlKind.OR || kind == SqlKind.NOT`
- **Files modified:** `PushdownFunctionRegistry.java`

**2. [Rule 1 - Bug] TestJdbcRulesFactory rule count stale after adding JdbcPushHavingIntoScan**
- **Found during:** Task 2 test run
- **Issue:** Existing test `physicalPhaseReturnsSixRules` expected 7 rules; adding `JdbcPushHavingIntoScan` made it 8
- **Fix:** Updated expected count from 7 to 8, added `hasHaving` flag check for `JdbcPushHavingIntoScan`
- **Files modified:** `TestJdbcRulesFactory.java`

**3. [Rule 1 - Bug] RexBuilder.makeCall() with null return type inference fails for custom SqlFunction**
- **Found during:** Task 1 test run
- **Issue:** Test used `new SqlFunction("MY_CUSTOM_UDF", SqlKind.OTHER_FUNCTION, null, null, null, ...)` — RexBuilder throws `UnsupportedOperationException` when the return type inferrer is null
- **Fix:** Changed to use `ReturnTypes.VARCHAR_2000` and `OperandTypes.CHARACTER` in the SqlFunction constructor
- **Files modified:** `TestPushdownFunctionRegistry.java`

## Test Results

All 153 unit tests pass:
- `TestPushdownFunctionRegistry`: 21 tests — all pass
- `TestJdbcRulesFactory`: 4 tests — all pass (updated rule count)
- `TestCalciteDialectSql`: 8 tests — all pass
- `TestJdbcPushJoinIntoScan`: 16 tests — all pass
- `TestSetOpJoinPushdown`: 4 tests — all pass
- `TestLiteralInliner`: 34 tests — all pass
- `TestRexToSqlString`: 23 tests — all pass
- `TestSqlBuilder`: 24 tests — all pass
- `TestIntervalParsing`: 19 tests — all pass

Downstream modules (`plugins/jdbc-postgresql`, `plugins/jdbc-oracle`) compile without errors.

## Self-Check: PASSED

All created files verified present. No git commits yet (per project policy: commit after all plans in the phase pass).
