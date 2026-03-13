---
phase: 33-advanced-query-pushdown-hardening
plan: 02
subsystem: database
tags: [jdbc, pushdown, order-by, sort, topn, calcite, planner-rule]

# Dependency graph
requires:
  - phase: 33-advanced-query-pushdown-hardening
    plan: 01
    provides: "SqlBuildRequest DTO with orderByClause slot, SqlBuilder.buildSql(SqlBuildRequest) with ORDER BY emission"
  - phase: 30-base-jdbc-framework
    provides: "JdbcScanPrel, JdbcRulesFactory, SqlBuilder base class"
  - phase: 32-oracle-connector
    provides: "OracleSqlBuilder with FETCH FIRST dialect and appendLimit() hook"
provides:
  - "JdbcPushSortIntoScan planner rule absorbing SortPrel into JdbcScanPrel with ORDER BY"
  - "JdbcScanPrel.orderByClause field with cloneWithOrderBy() helper"
  - "TopN pushdown via sequential rule firing (sort then limit)"
  - "Explicit NULLS FIRST/NULLS LAST in generated ORDER BY expressions"
  - "Integration tests for ORDER BY and TopN against PostgreSQL and Oracle containers"
affects: [33-03]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Sequential rule firing for TopN: JdbcPushSortIntoScan absorbs ORDER BY, then JdbcPushLimitIntoScan absorbs LIMIT"
    - "RelFieldCollation-to-SQL translation with explicit null direction handling"
    - "Graceful decline on unsupported CLUSTERED sort direction"

key-files:
  created:
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushSortIntoScan.java"
  modified:
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java"
    - "plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresPushdown.java"
    - "plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOraclePushdown.java"

key-decisions:
  - "TopN via sequential rule firing (sort then limit) rather than single combined rule -- leverages existing JdbcPushLimitIntoScan"
  - "SortPrel.offset/fetch always null in Dremio (SortRelBase asserts this) -- no secondary TopN path needed"
  - "Backward-compatible constructors: 2-arg and 3-arg JdbcScanPrel constructors delegate to full 4-arg (with orderByClause)"
  - "JdbcPushSortIntoScan declared final per checkstyle FinalClass rule"

patterns-established:
  - "orderByClause threading: same pattern as whereClause and limit -- nullable field propagated through all clone helpers"
  - "Sort rule registered in both PHYSICAL and PHYSICAL_HEP phases (same safety-net pattern as limit pushdown)"

requirements-completed: [PUSH-02, PUSH-03]

# Metrics
duration: 17min
completed: 2026-03-13
---

# Phase 33 Plan 02: ORDER BY and TopN Pushdown Summary

**ORDER BY pushdown rule absorbing SortPrel into JdbcScanPrel with explicit NULLS FIRST/LAST, TopN via sequential sort-then-limit rule firing, verified against PostgreSQL and Oracle containers**

## Performance

- **Duration:** 17 min
- **Started:** 2026-03-13T15:16:57Z
- **Completed:** 2026-03-13T15:34:26Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- Created JdbcPushSortIntoScan planner rule that matches SortPrel above JdbcScanPrel and translates RelFieldCollation to SQL ORDER BY with explicit NULLS FIRST/LAST
- Added orderByClause field to JdbcScanPrel with cloneWithOrderBy() helper, propagated through all existing clone methods
- Registered sort pushdown in both PHYSICAL and PHYSICAL_HEP planner phases
- TopN (ORDER BY + LIMIT) achieved via sequential rule firing: sort rule first, then existing limit rule
- 10 new integration tests (5 PostgreSQL + 5 Oracle) validating ORDER BY, TopN, and combined filter+sort queries against real containers
- All 40 tests pass (20 PostgreSQL + 20 Oracle)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add orderByClause to JdbcScanPrel, create JdbcPushSortIntoScan rule, register in JdbcRulesFactory** - `2e6292cfd` (feat)
2. **Task 2: Update SqlBuilder ORDER BY verification and add integration tests** - `f2d46aa7d` (feat)

## Files Created/Modified
- `plugins/jdbc-base/.../planning/JdbcPushSortIntoScan.java` - Planner rule absorbing SortPrel into JdbcScanPrel with ORDER BY clause
- `plugins/jdbc-base/.../planning/JdbcScanPrel.java` - Added orderByClause field, cloneWithOrderBy(), hasOrderBy(), updated all constructors and clone helpers
- `plugins/jdbc-base/.../planning/JdbcRulesFactory.java` - Registered JdbcPushSortIntoScan in PHYSICAL and PHYSICAL_HEP phases
- `plugins/jdbc-postgresql/.../TestPostgresPushdown.java` - Added 5 ORDER BY/TopN tests (SQL generation + container execution)
- `plugins/jdbc-oracle/.../TestOraclePushdown.java` - Added 5 ORDER BY/TopN tests including critical noLimitKeywordWithOrderBy test

## Decisions Made
- TopN uses sequential rule firing (sort absorbed first, then limit) rather than a single combined rule -- simpler implementation that reuses existing JdbcPushLimitIntoScan
- SortPrel.offset and SortPrel.fetch are always null in Dremio (SortRelBase enforces this) so no secondary TopN path through SortPrel.fetch was needed
- Backward-compatible constructors added: old 2-arg and 3-arg signatures delegate to the new full constructor with orderByClause
- JdbcPushSortIntoScan declared as final class per checkstyle FinalClass rule

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Made JdbcPushSortIntoScan final class**
- **Found during:** Task 2 (compile and install for test execution)
- **Issue:** Checkstyle FinalClass rule requires classes with only private constructors to be final
- **Fix:** Added `final` modifier to class declaration
- **Files modified:** JdbcPushSortIntoScan.java
- **Verification:** checkstyle passes for new file
- **Committed in:** f2d46aa7d (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Trivial fix, no scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- SqlBuildRequest DTO ready for Plan 03 (GROUP BY / aggregation pushdown) via groupByClause and selectExprs fields
- ORDER BY interacts correctly with WHERE and LIMIT in all clause orderings
- Pattern established for adding future planner rules (match Prel above JdbcScanPrel, clone with new pushdown state)

## Self-Check: PASSED

All 5 created/modified files verified present on disk. Both task commits (2e6292cfd, f2d46aa7d) verified in git log.

---
*Phase: 33-advanced-query-pushdown-hardening*
*Completed: 2026-03-13*
