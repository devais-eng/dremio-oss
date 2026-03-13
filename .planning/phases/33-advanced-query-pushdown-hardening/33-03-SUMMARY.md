---
phase: 33-advanced-query-pushdown-hardening
plan: 03
subsystem: database
tags: [jdbc, pushdown, aggregation, group-by, count, sum, min, max, avg, calcite, planner-rule]

# Dependency graph
requires:
  - phase: 33-advanced-query-pushdown-hardening
    plan: 01
    provides: "SqlBuildRequest DTO with selectExprs and groupByClause slots, SqlBuilder.buildSql with GROUP BY emission"
  - phase: 30-base-jdbc-framework
    provides: "JdbcScanPrel, JdbcRulesFactory, SqlBuilder base class"
  - phase: 32-oracle-connector
    provides: "OracleSqlBuilder with FETCH FIRST dialect and appendLimit() hook"
provides:
  - "JdbcPushAggIntoScan planner rule absorbing single-phase AggregatePrel into JdbcScanPrel"
  - "JdbcScanPrel aggregation state: selectExprs, groupByClause, overrideRowType"
  - "cloneWithAggregation() helper and deriveRowType() override for aggregated schema"
  - "COUNT(*), COUNT(col), SUM, MIN, MAX, AVG aggregate SQL generation"
  - "PHASE_1of1-only guard rejecting 2-phase distributed aggregates"
  - "Integration tests for aggregation pushdown against PostgreSQL and Oracle containers"
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Aggregation pushdown via selectExprs/groupByClause replacing projectedColumns in SQL generation"
    - "overrideRowType in JdbcScanPrel for schema change during aggregation pushdown"
    - "AggregatePrel phase guard: only PHASE_1of1 pushed to prevent incorrect partial aggregation"

key-files:
  created:
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushAggIntoScan.java"
  modified:
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java"
    - "plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresPushdown.java"
    - "plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOraclePushdown.java"

key-decisions:
  - "JdbcPushAggIntoScan declared final with private constructor (singleton INSTANCE) per checkstyle FinalClass rule"
  - "Only PHASE_1of1 aggregates pushed -- 2-phase partial/final aggregates produce incorrect results against single JDBC source"
  - "DISTINCT aggregates rejected in v1 -- graceful decline rather than incorrect SQL"
  - "Aggregation registered in PHYSICAL only (not PHYSICAL_HEP) -- structural rowType change benefits from cost-based Volcano decisions"
  - "overrideRowType mechanism: deriveRowType() returns override when set, enabling aggregated scan to produce different schema than base table"

patterns-established:
  - "Aggregation pushdown pattern: selectExprs replace projectedColumns, groupByClause emitted by SqlBuilder, overrideRowType overrides deriveRowType()"
  - "AggregatePrel.OperatorPhase guard: mandatory check for any aggregate pushdown to JDBC sources"

requirements-completed: [PUSH-01]

# Metrics
duration: 5min
completed: 2026-03-13
---

# Phase 33 Plan 03: Aggregation Pushdown (GROUP BY + COUNT/SUM/MIN/MAX/AVG) Summary

**JdbcPushAggIntoScan rule absorbing single-phase AggregatePrel into JdbcScanPrel with GROUP BY + aggregate SELECT list, verified against real PostgreSQL and Oracle containers**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-13T15:44:31Z
- **Completed:** 2026-03-13T15:49:38Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- Created JdbcPushAggIntoScan planner rule matching AggregatePrel above JdbcScanPrel with PHASE_1of1 guard
- Added aggregation state to JdbcScanPrel: selectExprs, groupByClause, overrideRowType with cloneWithAggregation helper
- Supported aggregate functions: COUNT(*), COUNT(col), SUM, SUM0, MIN, MAX, AVG with DISTINCT rejection
- Registered rule in PHYSICAL planner phase via JdbcRulesFactory
- 15 new integration tests (8 PostgreSQL + 7 Oracle) verifying aggregation SQL generation and execution against real containers
- All 55 tests pass across both connectors (28 PG + 27 Oracle)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add aggregation state to JdbcScanPrel, create JdbcPushAggIntoScan rule, register in JdbcRulesFactory** - `4f99ad043` (feat)
2. **Task 2: Add aggregation pushdown integration tests for PostgreSQL and Oracle** - `ff0c03547` (feat)

## Files Created/Modified
- `plugins/jdbc-base/.../planning/JdbcPushAggIntoScan.java` - Planner rule matching AggregatePrel above JdbcScanPrel, translating group set + agg calls to SQL
- `plugins/jdbc-base/.../planning/JdbcScanPrel.java` - Added selectExprs, groupByClause, overrideRowType fields; cloneWithAggregation(); deriveRowType() override; aggregated BatchSchema in getPhysicalOperator()
- `plugins/jdbc-base/.../planning/JdbcRulesFactory.java` - Registered JdbcPushAggIntoScan.INSTANCE in PHYSICAL phase
- `plugins/jdbc-postgresql/.../TestPostgresPushdown.java` - 8 new aggregation tests (3 SQL generation + 5 container execution)
- `plugins/jdbc-oracle/.../TestOraclePushdown.java` - 7 new aggregation tests (2 SQL generation + 5 container execution)

## Decisions Made
- JdbcPushAggIntoScan declared final with private constructor per checkstyle FinalClass rule
- Only PHASE_1of1 aggregates pushed to prevent incorrect partial aggregation against single JDBC source
- DISTINCT aggregates rejected in v1 (graceful decline)
- Aggregation registered in PHYSICAL only (not PHYSICAL_HEP) because rowType change benefits from cost-based decisions
- overrideRowType mechanism in JdbcScanPrel: deriveRowType() returns override, enabling aggregated schema different from base table

## Deviations from Plan

None - plan executed exactly as written. The aggregation infrastructure (selectExprs, groupByClause, overrideRowType fields, cloneWithAggregation helper, deriveRowType override) was pre-wired in earlier phases as part of the "future-facing fields" decision documented in 33-01.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 33 (Advanced Query Pushdown Hardening) is now fully complete
- All three pushdown plans delivered: bind parameters/operators (01), ORDER BY/TopN (02), aggregation (03)
- v1.5 milestone is complete: base JDBC framework, PostgreSQL connector, Oracle connector, advanced pushdown

## Self-Check: PASSED

All 5 created/modified files verified present on disk. Both task commits (4f99ad043, ff0c03547) verified in git log.

---
*Phase: 33-advanced-query-pushdown-hardening*
*Completed: 2026-03-13*
