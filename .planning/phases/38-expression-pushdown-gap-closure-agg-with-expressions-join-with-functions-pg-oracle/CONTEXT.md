# Phase 38 Context — Expression Pushdown Gap Closure

## Guiding Principle

Push down whenever there is a **network transfer benefit**. The decision is NOT "can the source compute this?" but "does pushing avoid transferring unnecessary rows/columns?"

## Gaps to Close (priority order)

### Gap 1: GROUP BY with expressions (HIGHEST PRIORITY)
**Example:** `SELECT EXTRACT(YEAR FROM hire_date), SUM(salary) FROM pg_table GROUP BY 1`
- Currently: entire table fetched, grouped locally
- Target: single aggregated result set from source
- **Root cause:** `JdbcPushAggIntoScan` only accepts plain columns in `groupSet` (ImmutableBitSet = column indices). An intermediate `ProjectPrel` that computes EXTRACT() breaks the `AggregatePrel(JdbcScanPrel)` pattern match.
- **Fix approach:** Store GROUP BY expressions as `List<RexNode>` on JdbcScanPrel (similar to `sortKeyExpressions`). Modify `JdbcPushAggIntoScan` to match through a ProjectPrel and capture the project expressions that form the group keys.

### Gap 2: JOIN with function expressions in conditions
**Example:** `SELECT e.name FROM employees e JOIN departments d ON e.department = CAST(d.dept_id AS VARCHAR)`
- Currently: `JdbcPushJoinIntoScan` bails out if intermediate Project has non-trivial expressions (CAST, functions)
- Target: single JOIN query pushed to source
- **Root cause:** Guard in `JdbcPushJoinIntoScan` returns null when walking down a tree with non-RexInputRef project expressions, to avoid column aliasing issues.
- **Fix approach:** Propagate function expressions through the join tree builder; validate them against `PushdownFunctionRegistry`.

### Gap 3: AGG operand expressions
**Example:** `SELECT SUM(salary * 1.1) FROM pg_table GROUP BY department`
- Currently: entire table fetched, expression computed locally, then aggregated
- Target: aggregation with expression computed at source
- **Root cause:** `JdbcPushAggIntoScan` requires aggregate operands to be plain column refs. An intermediate ProjectPrel that computes `salary * 1.1` blocks the pattern match.
- **Fix approach:** Same as Gap 1 — matching through ProjectPrel. The aggregate operands become expressions referencing the project's output, which includes the computed columns.

### Gap 4: Aggregate projections (SELECT SUM/MAX/COUNT without GROUP BY)
**Example:** `SELECT SUM(salary) FROM pg_table` or `SELECT MAX(hire_date) FROM pg_table`
- Currently: SCHEMA_CHANGE errors or full table scan + local aggregation
- Target: single-row result from source
- **Key insight:** These are the MAXIMUM network transfer benefit cases (N rows → 1 row). Even though they involve "function expressions in SELECT", the function IS the aggregation — it's not a cosmetic transform like UPPER().
- **Root cause:** Bare aggregates without GROUP BY may go through a different planner path that doesn't trigger `JdbcPushAggIntoScan`.
- **Fix approach:** Ensure bare aggregates (no GROUP BY, just `SELECT COUNT(*)/SUM(col)/MAX(col)`) are pushed. May need to handle the single-phase aggregate path specifically.

## Architecture Notes

- All gaps share a common pattern: an intermediate `ProjectPrel` between the aggregate/join and the `JdbcScanPrel` blocks pushdown
- Gaps 1, 3, and 4 are essentially the same root cause in `JdbcPushAggIntoScan`
- Gap 2 is in `JdbcPushJoinIntoScan`
- All function expressions must be validated against `PushdownFunctionRegistry`
- Must work on both PostgreSQL and Oracle (dialect-agnostic)

## Regression Baseline

32/34 tests pass (the 2 failures are pre-existing SCHEMA_CHANGE on bare COUNT(*), likely related to Gap 4).
Regression test script: `distribution/docker/test-regression.sh`

## Docker UAT Results (2026-03-18)

### Closed

| Gap | PG | Oracle | SQL pushed |
|-----|---|--------|------------|
| 1: GROUP BY EXTRACT(YEAR) | PASS | PASS | `SELECT EXTRACT(YEAR FROM "HIRE_DATE") "yr", COUNT(*) "cnt" ... GROUP BY EXTRACT(YEAR FROM "HIRE_DATE")` |
| 3: SUM(salary * 1.1) GROUP BY dept | PASS | PASS | `SELECT "DEPARTMENT", SUM("SALARY" * 1.1) "total" ... GROUP BY "DEPARTMENT"` |

### Remaining Gaps

| Gap | Issue | Root Cause |
|-----|-------|------------|
| 2: JOIN ON CAST(col) | PG: CAST(VARCHAR AS VARCHAR) optimized away — NOT a real test. Oracle: `ORA-00904: "EMPLOYEES"."DEPARTMENT0"` — column aliasing bug in `JdbcPushJoinIntoScan` appends dedup suffix to CAST output | `JdbcPushJoinIntoScan.findJdbcScan()` derives `leftColumns`/`rightColumns` from the LogicalProject's synthetic row type (with `EXPR$0` names) instead of the scan's original row type. Needs fix: when function-expression project intervenes, use scan's row type for column derivation |
| 4: Bare SELECT SUM(salary) | Not pushed — Dremio kernel creates 2-phase distributed aggregate (PHASE_1of2 + PHASE_2of2). Plugin rules correctly reject partial aggregates | Kernel-level issue in `sabot/kernel` (`HashAggPrule`/`StreamAggPrule`). Would need kernel change to convert to PHASE_1of1 when single JDBC source detected. Workaround: use `ORDER BY col DESC LIMIT 1` for MAX equivalent |

## Key Files

- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushAggIntoScan.java` — Gaps 1, 3, 4
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushAggWithExpressionsHep.java` — NEW: HEP rule for Gaps 1, 3
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushJoinIntoScan.java` — Gap 2
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java` — Storage + SQL generation
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PushdownFunctionRegistry.java` — Whitelist validation
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java` — Rule registration
