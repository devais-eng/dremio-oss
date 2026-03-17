---
phase: 38-expression-pushdown-gap-closure-agg-with-expressions-join-with-functions-pg-oracle
plan: 02
subsystem: database
tags: [calcite, jdbc, pushdown, integration-tests, postgresql, oracle, testcontainers]

# Dependency graph
requires:
  - phase: 38-expression-pushdown-gap-closure-agg-with-expressions-join-with-functions-pg-oracle
    plan: 01
    provides: JdbcPushAggWithExpressionsHep, extend/aggregate/trim pattern, findJdbcScan() registry validation
provides:
  - PostgreSQL integration tests: 4 new tests in TestPostgresPushdown covering Gaps 1-4 against real postgres:16-alpine container
  - Oracle integration tests: 4 new tests in TestOraclePushdown covering Gaps 1-4 against real gvenzl/oracle-xe:21-slim container
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - ORA-00955 catch-and-ignore for idempotent Oracle table creation in @Test (no BeforeClass, lazy init with error suppression)
    - Oracle CAST requires length: CAST(x AS VARCHAR2(50)) not bare CAST(x AS VARCHAR)
    - Docker API version override for Testcontainers 1.20.4 on Docker 29+: -Dapi.version=1.46 system property

key-files:
  created: []
  modified:
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresPushdown.java
    - plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOraclePushdown.java

key-decisions:
  - "Oracle CAST(x AS VARCHAR) rejected — must use VARCHAR2(n) with explicit length; test uses VARCHAR2(50) for DEPT_NAME join condition"
  - "Oracle test data tables (PUSHDOWN_EXPR_TEST, DEPARTMENTS_EXPR_TEST) created lazily in @Test with ORA-00955 catch-and-ignore instead of @BeforeClass to avoid ordering dependency with existing setup"
  - "Docker API version 1.32 (default in Testcontainers 1.20.4 shaded dockerjava) rejected by Docker Engine 29+ (minimum 1.40); override via -Dapi.version=1.46 system property uses the shaded dockerjava's parseConfigWithDefault mechanism"

requirements-completed: [GAP-01, GAP-02, GAP-03, GAP-04]

# Metrics
duration: 18min
completed: 2026-03-17
---

# Phase 38 Plan 02: Expression Pushdown Integration Tests Summary

**End-to-end integration tests for all 4 expression pushdown gaps against real PostgreSQL (postgres:16-alpine) and Oracle XE (gvenzl/oracle-xe:21-slim) Testcontainer databases, verifying EXTRACT(YEAR), SUM(salary * 1.1), bare aggregates, and JOIN with CAST produce correct SQL and results**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-03-17T18:42:09Z
- **Completed:** 2026-03-17T19:00:29Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Added 4 new integration tests to `TestPostgresPushdown`: `testGroupByExpressionExtractYear` (Gap 1), `testAggOperandExpression` (Gap 3), `testBareAggregateNoGroupBy` (Gap 4), `testJoinWithCastCondition` (Gap 2) — all passing against real PostgreSQL container
- Added matching 4 tests to `TestOraclePushdown` with Oracle-specific SQL (UPPERCASE names, `TO_DATE()`, `CAST(x AS VARCHAR2(50))`, results via UPPERCASE column labels) — all 35 Oracle tests passing
- Resolved Docker Engine 29+ / Testcontainers 1.20.4 API version incompatibility (shaded dockerjava defaults to API 1.32 which Docker 29 rejects; fixed by `-Dapi.version=1.46`)

## Task Commits

1. **Task 1: PostgreSQL integration tests for all 4 gaps** - `f3d902268` (feat)
2. **Task 2: Oracle integration tests for all 4 gaps** - `fa37c9538` (feat)

## Files Created/Modified

- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresPushdown.java` - Added `pushdown_expr_test`/`departments_expr_test` table creation in `@BeforeClass`; added 4 gap tests using direct JDBC queries against PG container
- `plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOraclePushdown.java` - Added 4 gap tests with lazy Oracle table creation (ORA-00955 suppression); Oracle-dialect SQL (`UPPERCASE` names, `TO_DATE`, `VARCHAR2(50)`)

## Decisions Made

- **Oracle CAST requires type length**: `CAST(x AS VARCHAR)` fails in Oracle with ORA-00906 (missing parenthesis). Test uses `CAST(d."DEPT_NAME" AS VARCHAR2(50))` to match the plan's intent while being Oracle-compliant.
- **Lazy Oracle table creation**: Rather than modifying `@BeforeClass` (which would create an ordering dependency with the existing `PUSHDOWN_TEST` setup), the new tables are created at the start of `testGroupByExpressionExtractYear` and `testJoinWithCastCondition`. `ORA-00955` ("name is already used") is suppressed to make the tests idempotent across reruns.
- **Docker API version fix**: Testcontainers 1.20.4 bundles a shaded dockerjava with default client API version 1.32. Docker Engine 29.x requires minimum API 1.40. The fix `-Dapi.version=1.46` passes through the shaded `DefaultDockerClientConfig.parseConfigWithDefault()` to override the default. This is a run-time system property, not a code change.

## Deviations from Plan

None — plan executed exactly as written. The Docker API version issue was a pre-existing environment constraint, not a code deviation.

## Issues Encountered

**Docker API version incompatibility**: Testcontainers 1.20.4 shaded dockerjava library defaults to requesting Docker API v1.32 in the HTTP `User-Agent`. Docker Engine 29.x enforces a minimum of v1.40 and rejects the connection with `BadRequestException (Status 400: client version 1.32 is too old)`. Resolved by passing `-Dapi.version=1.46` as a Maven system property, which reaches the shaded `RemoteApiVersion.parseConfigWithDefault()` via the `api.version` property key in `DefaultDockerClientConfig`. Tests then pass cleanly with 39 PG + 35 Oracle assertions.

## Next Phase Readiness

- All four expression pushdown gaps are now validated end-to-end against both PostgreSQL and Oracle
- Phase 38 is complete — both the infrastructure (Plan 01) and integration tests (Plan 02) are committed
- Ready for Phase 39 (pgvector expression pushdown) or any downstream phase

---
*Phase: 38-expression-pushdown-gap-closure-agg-with-expressions-join-with-functions-pg-oracle*
*Completed: 2026-03-17*
