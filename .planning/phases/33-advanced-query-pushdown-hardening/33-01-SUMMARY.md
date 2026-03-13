---
phase: 33-advanced-query-pushdown-hardening
plan: 01
subsystem: database
tags: [jdbc, pushdown, preparedstatement, bind-parameters, sql-injection, calcite, rex]

# Dependency graph
requires:
  - phase: 30-base-jdbc-framework
    provides: "SqlBuilder, JdbcScanPrel, JdbcGroupScan, JdbcSubScan, JdbcRecordReader, RexToSqlString inner class"
  - phase: 32-oracle-connector
    provides: "OracleSqlBuilder with FETCH FIRST dialect"
provides:
  - "BindParam DTO for JSON-serializable bind parameters"
  - "RexToSqlResult DTO carrying SQL with ? placeholders + ordered bind params"
  - "Top-level RexToSqlString with expanded operator coverage (IN, BETWEEN, arithmetic, COALESCE, NULLIF)"
  - "SqlBuildRequest DTO with slots for all pushdown types (WHERE, GROUP BY, ORDER BY, LIMIT, selectExprs)"
  - "SqlBuilder.buildSql(SqlBuildRequest) with correct clause ordering"
  - "End-to-end bind parameter threading: JdbcScanPrel -> JdbcGroupScan -> JdbcSubScan -> JdbcRecordReader"
  - "JdbcRecordReader.setBindParameters() dispatching to stmt.setXxx() per type"
affects: [33-02, 33-03]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "PreparedStatement bind parameters (? placeholders) instead of string-escaped literal inlining"
    - "SqlBuildRequest builder pattern for extensible pushdown DTO"
    - "OR-of-EQUALS auto-detection and conversion to IN clause"

key-files:
  created:
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/BindParam.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/RexToSqlResult.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/RexToSqlString.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/SqlBuildRequest.java"
  modified:
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/SqlBuilder.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushFilterIntoScan.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcGroupScan.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcSubScan.java"
    - "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/JdbcRecordReader.java"
    - "plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleSqlBuilder.java"
    - "plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestRexToSqlString.java"

key-decisions:
  - "NULL, TRUE, FALSE literals stay inline (not parameterized) -- SQL keywords, not injection risk"
  - "Date/Time/Timestamp values stored as epoch millis Long in BindParam for Jackson serialization"
  - "OracleSqlBuilder overrides appendLimit() hook method instead of full buildSql(SqlBuildRequest)"
  - "OR-of-EQUALS on same column auto-converts to IN (?, ?, ...) for cleaner SQL"
  - "SqlBuildRequest includes future-facing fields (selectExprs, groupByClause, orderByClause) for Plans 02/03"
  - "Backward-compatible constructors and delegates preserve all existing callers"

patterns-established:
  - "SqlBuildRequest builder pattern: all pushdown components flow through a single DTO"
  - "appendLimit/appendSelectList hook methods: dialect overrides target specific clauses, not full SQL"
  - "BindParam threading: immutable list flows through the entire operator chain via @JsonProperty"

requirements-completed: [PUSH-02, PUSH-03]

# Metrics
duration: 14min
completed: 2026-03-13
---

# Phase 33 Plan 01: Bind Parameters and Expanded Operator Coverage Summary

**PreparedStatement bind parameters (? placeholders) replace string-escaped literals end-to-end, plus RexToSqlString expanded with IN/BETWEEN/arithmetic/COALESCE/NULLIF and SqlBuildRequest DTO for extensible pushdown**

## Performance

- **Duration:** 14 min
- **Started:** 2026-03-13T14:59:26Z
- **Completed:** 2026-03-13T15:13:43Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments
- Eliminated SQL injection risk structurally by replacing literal inlining with ? bind parameters
- Expanded RexToSqlString operator coverage: IN (via OR-of-EQUALS detection), BETWEEN, +, -, *, /, COALESCE, NULLIF
- Created SqlBuildRequest DTO with slots for all current and future pushdown types (WHERE, GROUP BY, ORDER BY, LIMIT)
- Threaded bind parameters end-to-end: RexToSqlString -> JdbcPushFilterIntoScan -> JdbcScanPrel -> JdbcGroupScan -> JdbcSubScan -> JdbcRecordReader.setBindParameters()
- All 47 existing unit tests pass without modification via backward-compatible delegates

## Task Commits

Each task was committed atomically:

1. **Task 1: Extract RexToSqlString, create DTOs, expand operators** - `7997f187f` (feat)
2. **Task 2: Thread bind params through operator chain** - `cb195e212` (feat)

## Files Created/Modified
- `plugins/jdbc-base/.../planning/BindParam.java` - Immutable JSON-serializable bind parameter with value + SqlTypeName
- `plugins/jdbc-base/.../planning/RexToSqlResult.java` - Immutable DTO carrying SQL with ? placeholders + ordered params
- `plugins/jdbc-base/.../planning/RexToSqlString.java` - Extracted top-level class with expanded operator support
- `plugins/jdbc-base/.../planning/SqlBuildRequest.java` - Builder-pattern DTO for all pushdown components
- `plugins/jdbc-base/.../planning/SqlBuilder.java` - Refactored with buildSql(SqlBuildRequest) and hook methods
- `plugins/jdbc-base/.../planning/JdbcPushFilterIntoScan.java` - Uses top-level RexToSqlString returning RexToSqlResult
- `plugins/jdbc-base/.../planning/JdbcScanPrel.java` - Carries bindParams, uses SqlBuildRequest in getPhysicalOperator
- `plugins/jdbc-base/.../exec/JdbcGroupScan.java` - Serializes bindParams via @JsonProperty
- `plugins/jdbc-base/.../exec/JdbcSubScan.java` - Carries bindParams to executor
- `plugins/jdbc-base/.../reader/JdbcRecordReader.java` - setBindParameters() calls stmt.setXxx() per type
- `plugins/jdbc-oracle/.../oracle/OracleSqlBuilder.java` - Overrides appendLimit() for FETCH FIRST dialect
- `plugins/jdbc-base/.../planning/TestRexToSqlString.java` - Updated for RexToSqlResult with bind param assertions

## Decisions Made
- NULL, TRUE, FALSE literals stay inline (SQL keywords, not injection risk)
- Date/Time/Timestamp values stored as epoch millis in BindParam for Jackson serialization compatibility
- OracleSqlBuilder overrides appendLimit() hook instead of full buildSql(SqlBuildRequest) -- reduces duplication
- OR-of-EQUALS on same column auto-converts to IN (?, ?, ...) for cleaner generated SQL
- SqlBuildRequest includes future-facing fields for Plans 02/03 (selectExprs, groupByClause, orderByClause)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added cloneWithFilter(String, List<BindParam>) to JdbcScanPrel in Task 1**
- **Found during:** Task 1 (compilation of JdbcPushFilterIntoScan)
- **Issue:** JdbcPushFilterIntoScan calls cloneWithFilter(sql, params) but the new signature was planned for Task 2
- **Fix:** Added the two-arg cloneWithFilter to JdbcScanPrel in Task 1 to unblock compilation
- **Files modified:** JdbcScanPrel.java
- **Verification:** mvn compile succeeds
- **Committed in:** 7997f187f (Task 1 commit)

**2. [Rule 3 - Blocking] Updated TestRexToSqlString for extracted top-level class**
- **Found during:** Task 1 (tests reference deleted inner class JdbcPushFilterIntoScan.RexToSqlString)
- **Issue:** Tests used `JdbcPushFilterIntoScan.RexToSqlString` and `String` return type which no longer exist
- **Fix:** Updated all tests to use top-level `RexToSqlString` and `RexToSqlResult` with bind param assertions
- **Files modified:** TestRexToSqlString.java
- **Verification:** All 23 tests pass
- **Committed in:** 7997f187f (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (2 blocking)
**Impact on plan:** Both auto-fixes necessary for compilation. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- SqlBuildRequest DTO is ready for Plan 02 (ORDER BY pushdown) and Plan 03 (GROUP BY / aggregation pushdown)
- The orderByClause and groupByClause fields are already wired into SqlBuilder.buildSql(SqlBuildRequest)
- OracleSqlBuilder correctly orders ORDER BY before FETCH FIRST per Oracle syntax

## Self-Check: PASSED

All 12 created/modified files verified present on disk. Both task commits (7997f187f, cb195e212) verified in git log.

---
*Phase: 33-advanced-query-pushdown-hardening*
*Completed: 2026-03-13*
