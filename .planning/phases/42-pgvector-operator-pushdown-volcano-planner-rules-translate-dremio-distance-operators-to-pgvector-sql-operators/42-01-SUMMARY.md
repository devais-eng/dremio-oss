---
phase: 42-pgvector-operator-pushdown-volcano-planner-rules-translate-dremio-distance-operators-to-pgvector-sql-operators
plan: 01
subsystem: database
tags: [pgvector, postgresql, calcite, sql-dialect, unparseCall, jdbc, vector-search, hnsw]

requires:
  - phase: 41-pgvector-sql-operators
    provides: "VectorDistanceFunctions.java with @FunctionTemplate l2_distance/cosine_distance/inner_product registered under those names"
  - phase: 31-postgresql-connector
    provides: "PostgresConf.newPlugin() with anonymous JdbcStoragePlugin and createDialect() override hook"
  - phase: 37-expression-pushdown
    provides: "StandardPushdownFunctionRegistry with WHITELISTED_FUNCTION_NAMES ImmutableSet; JdbcPushTopNWithExpressionsHep/JdbcPushSortWithExpressionsHep use isFunctionPushable()"

provides:
  - "DremioPostgresDialect extends PostgresqlSqlDialect with unparseCall override that translates l2_distance/cosine_distance/inner_product to pgvector infix operators <-> <=> <#>"
  - "ARRAY[...] second operand rendered as pgvector text literal '[val1,val2,val3]' via toPlainString() to avoid scientific notation"
  - "StandardPushdownFunctionRegistry.WHITELISTED_FUNCTION_NAMES includes L2_DISTANCE, COSINE_DISTANCE, INNER_PRODUCT"
  - "PostgresConf.createDialect() returns DremioPostgresDialect.INSTANCE"
  - "6 unit tests in TestDremioPostgresDialect (no container)"
  - "2 pgvector integration tests in TestPostgresPushdown (pgvector container)"

affects:
  - "43-pgvector-uat-integration-tests"
  - "any phase using PostgresConf or DremioJdbcImplementor with PostgreSQL dialect"

tech-stack:
  added: []
  patterns:
    - "SqlDialect.unparseCall() hook as the Calcite extension point for custom infix operator rendering"
    - "equalsIgnoreCase() for function name dispatch in unparseCall to handle normalized vs user-typed names"
    - "BigDecimal.toPlainString() for numeric literal extraction from SqlNumericLiteral to avoid scientific notation in pgvector text literals"
    - "SqlBasicCall(ARRAY_VALUE_CONSTRUCTOR) kind check to detect ARRAY[...] operands vs column references"

key-files:
  created:
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/DremioPostgresDialect.java
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestDremioPostgresDialect.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/StandardPushdownFunctionRegistry.java
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresConf.java
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresPushdown.java

key-decisions:
  - "DremioPostgresDialect uses equalsIgnoreCase for operator name matching — @FunctionTemplate registers 'l2_distance' lowercase; user may type L2_DISTANCE in SQL (Dremio normalizes at lookup time, but defensive matching prevents any rendering failure)"
  - "toPlainString() called on BigDecimal extracted from SqlNumericLiteral — avoids scientific notation (e.g. 1E2 → 100) which pgvector rejects in vector literals"
  - "ARRAY[...] detected via SqlKind.ARRAY_VALUE_CONSTRUCTOR kind check on SqlCall — falls through to operand.unparse() for column references to avoid ClassCastException"
  - "Function names added directly to StandardPushdownFunctionRegistry (not a PG-specific subclass) — JdbcRulesFactory hardcodes StandardPushdownFunctionRegistry.INSTANCE; per-plugin registry subclasses are not consulted at rule-firing time"
  - "pgvec_knn_test table set up in TestPostgresPushdown.setUpClass() alongside existing tables — shared pgvector container already available from Phase 40"

patterns-established:
  - "Pattern: SqlDialect.unparseCall() override for custom infix rendering — register via createDialect() in connector conf; all JDBC sources using that conf get the override automatically"
  - "Pattern: pgvector text literal format is '[val1,val2,val3]' — PostgreSQL applies implicit text→vector cast; ARRAY[] keyword form not accepted by pgvector distance operators"

requirements-completed: [PGVEC-03]

duration: 35min
completed: 2026-03-18
---

# Phase 42 Plan 01: pgvector Operator Pushdown Summary

**DremioPostgresDialect renders l2_distance/cosine_distance/inner_product as pgvector infix operators <-> <=> <#> with ARRAY[...] as '[...]' text literals, enabling HNSW index-accelerated nearest-neighbor search**

## Performance

- **Duration:** 35 min
- **Started:** 2026-03-18T15:45:00Z
- **Completed:** 2026-03-18T16:30:06Z
- **Tasks:** 2
- **Files modified:** 5 (1 created main, 2 modified main, 1 created test, 1 modified test)

## Accomplishments

- Created `DremioPostgresDialect extends PostgresqlSqlDialect` with `unparseCall` override that intercepts the three distance functions and emits pgvector infix operators; `ARRAY[...]` second operand rendered as `'[val1,val2,val3]'` text literal using `toPlainString()` to avoid scientific notation
- Added `L2_DISTANCE`, `COSINE_DISTANCE`, `INNER_PRODUCT` to `StandardPushdownFunctionRegistry.WHITELISTED_FUNCTION_NAMES` — unlocks `JdbcPushTopNWithExpressionsHep` and `JdbcPushSortWithExpressionsHep` for `ORDER BY distance LIMIT K` pushdown
- Wired `PostgresConf.createDialect()` to return `DremioPostgresDialect.INSTANCE` — all PostgreSQL sources now use the custom dialect
- 6 unit tests in `TestDremioPostgresDialect` pass without a container (infix rendering, ARRAY literal, column vs column, non-distance delegation, sci-notation avoidance)
- 2 pgvector integration tests added to `TestPostgresPushdown`: `testPgvectorOrderByDistanceLimitK` verifies nearest-neighbor returns id=1 at distance 0.0; `testPgvectorAllThreeOperators` confirms `<->`, `<=>`, `<#>` all execute and return expected values (`<#> = -14.0` for [1,2,3]·[1,2,3] per pgvector convention)
- Full module test suite: 317 tests total, 0 failures (198 jdbc-base, 119 jdbc-postgresql, 5 skipped = ADBC env-gated)

## Task Commits

Per the project memory rule (`feedback_no_commit_until_phase_pass`), no per-task commits were made during execution. All changes committed as a single plan-level commit.

**Plan metadata:** (docs commit after SUMMARY creation)

## Files Created/Modified

- `plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/DremioPostgresDialect.java` — New: PostgresqlSqlDialect subclass with unparseCall override for pgvector infix operators and ARRAY literal rendering
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/StandardPushdownFunctionRegistry.java` — Modified: added L2_DISTANCE, COSINE_DISTANCE, INNER_PRODUCT to WHITELISTED_FUNCTION_NAMES; updated Javadoc
- `plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresConf.java` — Modified: `createDialect()` returns `DremioPostgresDialect.INSTANCE` instead of `PostgresqlSqlDialect.DEFAULT`
- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestDremioPostgresDialect.java` — New: 6 unit tests for SQL rendering (no container)
- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresPushdown.java` — Modified: pgvec_knn_test table setup in setUpClass(); 2 new pgvector integration test methods

## Decisions Made

- `equalsIgnoreCase()` used in `getPgvectorOp()` switch for defensive matching — `@FunctionTemplate(name="l2_distance")` registers lowercase; Dremio SQL normalizes user input before function lookup, but the rendered `SqlCall.getOperator().getName()` returns the registered name. Defensive case-insensitivity avoids subtle breakage if registration changes.
- `toPlainString()` on `BigDecimal` — pgvector rejects scientific notation in text literals (e.g. `1E+2` instead of `100`). Using `SqlNumericLiteral.getValueAs(BigDecimal.class).toPlainString()` guarantees plain decimal output.
- `SqlKind.ARRAY_VALUE_CONSTRUCTOR` kind check — detects `ARRAY[...]` operands while falling through to normal `unparse()` for column references; avoids `ClassCastException` on non-ARRAY second operands.
- Functions added to `StandardPushdownFunctionRegistry` (not a PostgreSQL-specific subclass) — `JdbcRulesFactory` hardcodes `StandardPushdownFunctionRegistry.INSTANCE`; per-plugin registry subclasses are not invoked at rule-firing time. The three function names are unique and safe to add globally (no other dialect interprets them differently — only `DremioPostgresDialect` translates them to infix form).

## Deviations from Plan

None — plan executed exactly as written. All implementation decisions matched the RESEARCH.md specifications.

## Issues Encountered

None. Compilation, unit tests, and integration tests all passed on first attempt.

## User Setup Required

None — no external service configuration required. Tests use the existing `pgvector/pgvector:pg16` container (set up in Phase 40).

## Next Phase Readiness

- Phase 43 (pgvector UAT and integration tests) can now test full planner pushdown: `ORDER BY l2_distance(embedding, ARRAY[...]) LIMIT K` will push to PostgreSQL as `ORDER BY embedding <-> '[...]' LIMIT K` using HNSW index
- The `DremioPostgresDialect.INSTANCE` is wired via `PostgresConf`; all PG sources using the connector automatically use the new rendering
- Three distance functions are now whitelisted in the pushdown registry — the existing TopN and Sort expression-pushdown rules will approve them without any additional rule changes

---
*Phase: 42-pgvector-operator-pushdown*
*Completed: 2026-03-18*
