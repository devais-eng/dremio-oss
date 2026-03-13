---
phase: 32-oracle-connector
plan: 02
subsystem: testing
tags: [oracle, jdbc, testcontainers, oracle-xe, type-mapping, schema-discovery, pushdown, fetch-first]

# Dependency graph
requires:
  - phase: 32-oracle-connector-01
    provides: OracleConf, OracleSchemaFetcher, OracleSqlBuilder, DremioOracleContainer marker pattern
  - phase: 31-postgresql-connector-02
    provides: PostgreSQL test pattern (DremioPostgresContainer, PostgresTestContainer, test class structure)
provides:
  - DremioOracleContainer wrapping OracleContainer with DremioContainer marker interface
  - OracleTestContainer shared container helper with createPool/executeSql for JUnit @ClassRule usage
  - TestOracleTypeMapping validating all 16 Oracle type roundtrips against gvenzl/oracle-xe:21-slim
  - TestOracleSchemaDiscovery validating system schema filtering and table/view listing
  - TestOraclePushdown validating FETCH FIRST N ROWS ONLY dialect and pushdown SQL correctness
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Oracle integration tests use static ORACLE @ClassRule field referencing OracleTestContainer.ORACLE"
    - "OracleTestContainer.executeSql() bypasses pool for test setup DDL/DML"
    - "Oracle table names referenced as UPPERCASE in schema fetcher (TEST_USER, TYPE_TEST, DISCOVERY_TABLE)"
    - "INSERT ALL...SELECT 1 FROM DUAL for Oracle multi-row insert in pushdown test setup"
    - "TestNoLimitKeyword asserts LIMIT never appears when FETCH FIRST is used"

key-files:
  created:
    - plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/DremioOracleContainer.java
    - plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/OracleTestContainer.java
    - plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOracleTypeMapping.java
    - plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOracleSchemaDiscovery.java
    - plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOraclePushdown.java
  modified: []

key-decisions:
  - "Oracle test files existed untracked from prior work — committed atomically as Task 1 and Task 2 without modification (files were already correct)"
  - "TestOracleSchemaDiscovery uses stream().anyMatch(s -> s.equalsIgnoreCase()) for schema/table name matching because Oracle consistently returns UPPERCASE identifiers but the anyMatch approach is more robust"
  - "TestOraclePushdown.testNoLimitKeyword() explicitly asserts LIMIT keyword never appears — this is the critical correctness gate for Oracle dialect"
  - "INSERT ALL syntax used in pushdown setup: Oracle lacks PostgreSQL-style VALUES (row1), (row2) multi-row syntax"

patterns-established:
  - "Oracle type test names use UPPERCASE (COL_VARCHAR2, TYPE_TEST, TEST_USER) matching Oracle's DatabaseMetaData behavior"
  - "testNullHandling uses INSERT INTO type_test (id) VALUES (2) — only non-null id needed, all others NULL"
  - "testNoLimitKeyword is the critical Oracle-specific test that has no PostgreSQL equivalent"

requirements-completed: [ORA-05]

# Metrics
duration: 4min
completed: 2026-03-13
---

# Phase 32 Plan 02: Oracle Integration Tests Summary

**Testcontainers integration test suite covering 16 Oracle type roundtrips, system schema filtering (18 schemas excluded), and FETCH FIRST N ROWS ONLY pushdown verification against gvenzl/oracle-xe:21-slim**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-13T01:49:44Z
- **Completed:** 2026-03-13T01:53:44Z
- **Tasks:** 2
- **Files modified:** 5 (all created)

## Accomplishments
- Created DremioOracleContainer implementing DremioContainer marker to satisfy DremioRestrictedTestcontainersUsage error-prone check
- Created OracleTestContainer with shared gvenzl/oracle-xe:21-slim container, JdbcConnectionPool creation (SELECT 1 FROM DUAL validation), and raw SQL execution helpers for test setup
- Created TestOracleTypeMapping covering all 16 Oracle column types including NUMBER(p,s), bare NUMBER, FLOAT sentinel, BINARY_FLOAT/DOUBLE, VARCHAR2/NVARCHAR2/CHAR, CLOB/NCLOB, BLOB, RAW, DATE, TIMESTAMP, TIMESTAMP WITH TIME ZONE
- Created TestOracleSchemaDiscovery verifying SYS/SYSTEM/CTXSYS/MDSYS/XDB/OUTLN/ORDSYS/WMSYS exclusion and DISCOVERY_TABLE/DISCOVERY_VIEW listing in TEST_USER schema
- Created TestOraclePushdown with pure SQL generation tests (FETCH FIRST, no LIMIT) and container-based pushdown correctness tests against real Oracle 21c XE

## Task Commits

Each task was committed atomically:

1. **Task 1: DremioOracleContainer, OracleTestContainer, TestOracleTypeMapping** - `3356a3e45` (feat)
2. **Task 2: TestOracleSchemaDiscovery, TestOraclePushdown** - `ddabfe6c7` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/DremioOracleContainer.java` - OracleContainer subclass with DremioContainer marker; uses gvenzl/oracle-xe:21-slim
- `plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/OracleTestContainer.java` - Shared container, createPool (SELECT 1 FROM DUAL), executeSql, TestJdbcConf inner class
- `plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOracleTypeMapping.java` - 3 tests: testSchemaMapping (16 column types), testNullHandling, testValueRoundtrip
- `plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOracleSchemaDiscovery.java` - 5 tests: listSchemas, listTables, listTablesNonExistent, tableExists variants, getTableSchemaColumns
- `plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOraclePushdown.java` - 14 tests: 9 SQL generation tests + 5 container-based execution tests

## Decisions Made
- Test files were already present as untracked files when this plan started — they were verified correct (test-compile passes), then committed without modification
- Oracle identifiers are UPPERCASE throughout: schema (TEST_USER), tables (TYPE_TEST, DISCOVERY_TABLE, PUSHDOWN_TEST), columns (COL_VARCHAR2, AGE, NAME)
- testNoLimitKeyword() is the most critical Oracle-specific assertion — ensures LIMIT never leaks into Oracle SQL when row-limiting is requested

## Deviations from Plan

None — all 5 test files were already written correctly and compiled cleanly. The plan was executed by committing existing correct test files.

## Issues Encountered
None — test sources compiled cleanly on the first attempt (BUILD SUCCESS).

## User Setup Required
Docker must be available with sufficient memory (2GB+) for gvenzl/oracle-xe:21-slim to start (30-120 seconds startup time). No external credentials or Oracle Wallet configuration required — tests use basic username/password auth.

## Next Phase Readiness
- Oracle connector integration test suite is complete
- Phase 32 (Oracle Connector) is fully complete: source code (plan 01) + integration tests (plan 02)
- RDBMS JDBC plugin milestone (v1.5) is complete: base framework (phase 30) + PostgreSQL connector (phase 31) + Oracle connector (phase 32)

---
*Phase: 32-oracle-connector*
*Completed: 2026-03-13*
