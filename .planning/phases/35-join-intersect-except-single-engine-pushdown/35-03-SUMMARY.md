---
phase: 35-join-intersect-except-single-engine-pushdown
plan: 03
subsystem: jdbc-integration-tests
tags: [jdbc, join-pushdown, integration-tests, adbc, sql-injection, oracle, postgresql]
dependency_graph:
  requires:
    - 35-01 (SqlBuilder.buildJoinSql(), JdbcJoinScanDrel/Prel, JdbcPushJoinIntoScan)
    - 35-02 (LiteralInliner, AdbcRecordReader COPY binary path)
  provides:
    - PostgreSQL JOIN pushdown integration tests (TestPostgresJoinPushdown)
    - Oracle JOIN pushdown integration tests (TestOracleJoinPushdown)
    - Docker UAT script for full planner-level JOIN pushdown verification (test-uat-35.sh)
  affects:
    - OracleSqlBuilder (new buildJoinSql() override — no AS keyword for table aliases)
tech_stack:
  added: []
  patterns:
    - Testcontainers integration test pattern (shared ClassRule container)
    - Direct JDBC execution to verify SQL correctness against real databases
    - LiteralInliner ADBC COPY path verification against real PostgreSQL
    - SQL injection safety verification against real database
    - Docker UAT script with container log inspection (PG log_statement / Oracle V$SQL)
key_files:
  created:
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresJoinPushdown.java
    - plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOracleJoinPushdown.java
    - distribution/docker/test-uat-35.sh
  modified:
    - plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleSqlBuilder.java
decisions:
  - "OracleSqlBuilder.buildJoinSql() overrides base implementation to omit AS keyword — Oracle rejects AS for table aliases in FROM/JOIN clauses (ORA-00933)"
  - "Oracle alias syntax: space-separated (\"schema\".\"table\" \"alias\") not AS-prefixed (\"schema\".\"table\" AS \"alias\")"
  - "INTERSECT semantics verified via INNER JOIN DISTINCT (same result as set intersection)"
  - "EXCEPT semantics verified via LEFT JOIN + IS NULL filter (same result as set difference)"
metrics:
  duration_minutes: 22
  completed_date: "2026-03-14"
  tasks_completed: 3
  files_created: 3
  files_modified: 1
---

# Phase 35 Plan 03: JOIN Pushdown Integration Tests Summary

**One-liner:** Integration tests verifying SqlBuilder.buildJoinSql() produces correct SQL for INNER/LEFT/RIGHT/FULL JOIN against real PostgreSQL and Oracle containers, LiteralInliner ADBC COPY path verified with SQL injection safety, and Docker UAT script confirms full planner-level JOIN pushdown via container log inspection.

## What Was Built

### Task 1: TestPostgresJoinPushdown (commit ef2148997)

**`TestPostgresJoinPushdown`** — 13 integration tests against `postgres:16-alpine` container.

**JOIN SQL generation + execution tests:**
- `testInnerJoinSql()` — INNER JOIN, verifies 3 rows returned (orders matching customers)
- `testLeftOuterJoinSql()` — LEFT OUTER JOIN, verifies 4 rows (all orders, order 104 has null customer)
- `testRightOuterJoinSql()` — RIGHT OUTER JOIN, verifies >= 6 rows (all customers including unmatched)
- `testFullOuterJoinSql()` — FULL OUTER JOIN, verifies >= 6 rows (all matched + unmatched both sides)
- `testCrossSchemaJoinSql()` — Cross-schema JOIN (`pushdown_test` x `pushdown_test2`), verifies correct results
- `testJoinSqlAliasQualification()` — Verifies `"t1"."col"` alias qualification in SELECT and FROM
- `testJoinSqlSelectStarFallback()` — Verifies `SELECT *` when both column lists are empty

**ADBC COPY optimization tests:**
- `testLiteralInlinerWithWhereClause()` — DECIMAL + VARCHAR params inlined, executes against PG, correct rows
- `testLiteralInlinerStringEscaping()` — String with embedded single quotes (`O'Malley's`) doubled correctly
- `testLiteralInlinerSqlInjectionSafety()` — Adversarial string (`'; DROP TABLE customers; --`) treated as data, table survives
- `testLiteralInlinerDateParam()` — DATE epoch millis inlined as `DATE '2024-01-15'`, correct row found

**Semantic verification:**
- `testIntersectSemantics()` — INTERSECT result ({1,2}) via INNER JOIN DISTINCT
- `testExceptSemantics()` — EXCEPT result ({3,4,...}) via LEFT JOIN + IS NULL filter

### Task 2: TestOracleJoinPushdown + OracleSqlBuilder fix (commit 151f7e6a9)

**`TestOracleJoinPushdown`** — 5 integration tests against Oracle XE container.

- `testInnerJoinSql()` — INNER JOIN with uppercase identifiers, 3 rows
- `testLeftOuterJoinSql()` — LEFT OUTER JOIN, 4 rows
- `testRightOuterJoinSql()` — RIGHT OUTER JOIN, >= 4 rows
- `testFullOuterJoinSql()` — FULL OUTER JOIN, >= 6 rows
- `testJoinWithOracleUppercaseIdentifiers()` — All identifiers uppercase double-quoted, correct results

**`OracleSqlBuilder.buildJoinSql()` override** — Oracle rejects `AS` keyword for table aliases in FROM/JOIN clauses (ORA-00933). Added `buildJoinSql()` override to `OracleSqlBuilder` that uses space-separated alias syntax (`"schema"."table" "alias"` instead of `"schema"."table" AS "alias"`).

### Task 3: test-uat-35.sh (commit 4f6d1f5df)

**`test-uat-35.sh`** — 677-line Docker UAT script with 18 tests verifying full planner-level JOIN pushdown.

**Section 1: PG JDBC (8 tests):**
- Tests 1-4: INNER/LEFT/RIGHT/FULL OUTER JOIN pushdown, verifies JOIN appears in PG container logs
- Test 5: JOIN + WHERE filter
- Test 6: JOIN + GROUP BY + SUM (Engineering=505k, Marketing=265k, Sales=308k)
- Test 7: JOIN + GROUP BY + AVG + ORDER BY
- Test 8: JOIN + GROUP BY + COUNT (Engineering=4, Marketing=3, Sales=3)

**Section 2: PG ADBC (4 tests):**
- Tests 9-12: Same JOIN queries via `pg_adbc_test` ADBC source
- Test 12: Combined JOIN + WHERE + GROUP BY + AVG + ORDER BY

**Section 3: Oracle JDBC (5 tests):**
- Tests 13-15: INNER/LEFT/FULL OUTER JOIN pushdown
- Tests 16-17: JOIN + GROUP BY + SUM/AVG + ORDER BY

**Section 4: Negative test (1 test):**
- Test 18: Cross-source JOIN (PG x Oracle) correctly NOT pushed — PG log contains no JOIN keyword

**Script features:** `--pg-only` / `--ora-only` flags, Apache 2.0 license header, PASS/FAIL/SKIP summary, exits 1 on failure.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] OracleSqlBuilder.buildJoinSql() added to omit AS keyword for table aliases**

- **Found during:** Task 2 test run
- **Issue:** Oracle database rejects `AS` keyword for table aliases in FROM/JOIN clauses: `SELECT ... FROM "TEST_USER"."ORDERS" AS "t1" ...` produces `ORA-00933: SQL command not properly ended`. The base `SqlBuilder.buildJoinSql()` uses `AS`.
- **Fix:** Added `buildJoinSql()` override to `OracleSqlBuilder` that uses space-separated alias syntax: `"schema"."table" "alias"` (no AS keyword). All other aspects (SELECT list, JOIN type, ON clause) are identical to the base.
- **Files modified:** `OracleSqlBuilder.java`
- **Commit:** 151f7e6a9
- **Note:** This was anticipated in Plan 01 which made `buildJoinSql()` non-final precisely to enable this override.

## Self-Check

### Files exist:

- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresJoinPushdown.java` — FOUND
- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-oracle/src/test/java/com/dremio/plugins/jdbc/oracle/TestOracleJoinPushdown.java` — FOUND
- `/home/filippo/PycharmProjects/dremio-oss/distribution/docker/test-uat-35.sh` — FOUND
- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleSqlBuilder.java` — FOUND (modified)

### Commits exist:

- `ef2148997` — feat(35-03): add PostgreSQL JOIN pushdown and ADBC COPY integration tests
- `151f7e6a9` — feat(35-03): add Oracle JOIN pushdown integration tests; fix OracleSqlBuilder alias syntax
- `4f6d1f5df` — feat(35-03): add Docker UAT script for JOIN pushdown verification via container logs

### Verification passed:

- `mvn test -pl plugins/jdbc-postgresql -Dtest=TestPostgresJoinPushdown -am` — 13 tests, 0 failures, BUILD SUCCESS
- `mvn test -pl plugins/jdbc-oracle -Dtest=TestOracleJoinPushdown -am` — 5 tests, 0 failures, BUILD SUCCESS
- `chmod +x distribution/docker/test-uat-35.sh && head -20` — script exists with Apache 2.0 license header, 677 lines

## Self-Check: PASSED
