---
phase: 39-docker-based-planner-integration-tests-full-pushdown-verification-with-adbc
plan: 01
subsystem: testing
tags: [testcontainers, jdbc, integration-tests, postgresql, oracle, adbc, dremio-planner, pushdown]

# Dependency graph
requires:
  - phase: 38-expression-pushdown-gap-closure-agg-with-expressions-join-with-functions-pg-oracle
    provides: Dremio JDBC planner stack with full pushdown support (AGG, JOIN, ORDER BY expressions)
  - phase: 37-expression-pushdown
    provides: UPPER/function expression pushdown via JdbcPushFilterIntoScan
provides:
  - Tier 2 Docker-based integration test class with 35 @Test methods verifying all pushdown patterns against live Dremio planner
  - DremioJdbcContainer wrapping dremio-oss:jdbc-test image with Assume-based skip guard
  - DremioJdbcPgContainer and DremioJdbcOracleContainer in integration package for jdbc-base tests
  - Updated jdbc-base pom.xml with testcontainers, postgresql, oracle-xe, and JDBC driver test dependencies
affects: [future-integration-test-phases, regression-firewall]

# Tech tracking
tech-stack:
  added:
    - testcontainers (org.testcontainers:testcontainers) — test scope in jdbc-base
    - testcontainers postgresql (org.testcontainers:postgresql) — test scope in jdbc-base
    - testcontainers oracle-xe (org.testcontainers:oracle-xe) — test scope in jdbc-base
    - dremio-testcontainers-core (com.dremio.tools) — test scope in jdbc-base
    - PostgreSQL JDBC driver (org.postgresql:postgresql) — test scope in jdbc-base
    - Oracle JDBC driver (com.oracle.database.jdbc:ojdbc11) — test scope in jdbc-base
  patterns:
    - DremioContainer marker interface pattern for testcontainers error-prone check compliance
    - Shared Network.newNetwork() ClassRule for cross-container communication in single test class
    - Echo-append temp-file pattern for Oracle sqlplus commands to avoid $ shell expansion
    - ORACLE.getPassword() for SYS password (OracleContainer.configure() sets ORACLE_PASSWORD = password field)
    - pgLogMarker/pgLogsSince with PG container getLogs() for pushdown verification via SQL logs
    - V$SQL-based Oracle pushdown verification via execInContainer sqlplus @file approach

key-files:
  created:
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcContainer.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcPgContainer.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcOracleContainer.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/TestDremioJdbcIntegration.java
  modified:
    - plugins/jdbc-base/pom.xml

key-decisions:
  - "OracleContainer.withNetwork/withNetworkAliases return OracleContainer (not subclass) — configured via static block after construction, then assigned to @ClassRule field"
  - "ORACLE_PASSWORD in testcontainers gvenzl/oracle-xe == ORACLE.getPassword() (OracleContainer.configure() sets ORACLE_PASSWORD = this.password) — do not hardcode orapass"
  - "Oracle sqlplus V$SQL queries written to /tmp/*.sql temp files via echo >> series — avoids $ shell expansion of v$sql reference in execInContainer sh -c strings"
  - "PG JDBC driver (org.postgresql:postgresql) and ojdbc11 added as test scope to jdbc-base pom — needed for local seedPostgres/seedOracle via JDBC before Dremio source creation"
  - "35 @Test methods (exceeds plan's 32 minimum) — includes all 8 sections: WHERE/FILTER, LIMIT, ORDER BY, AGG, JOIN, COMBINED, CORRECTNESS, ADBC"

patterns-established:
  - "Tier 2 integration test pattern: @ClassRule containers on shared Network + @BeforeClass seed + REST API source creation + getLogs verification"
  - "Image availability guard: DockerClientFactory.instance().client().inspectImageCmd().exec() + Assume.assumeTrue — whole class skips cleanly when image absent"

requirements-completed: [TEST-01]

# Metrics
duration: 25min
completed: 2026-03-18
---

# Phase 39 Plan 01: Docker-based JDBC Planner Integration Tests Summary

**35-test Tier 2 integration suite verifying all pushdown patterns (WHERE, LIMIT, ORDER BY, AGG, JOIN, combined, correctness, ADBC) through live Dremio planner with PG log and Oracle V$SQL verification**

## Performance

- **Duration:** 25 min
- **Started:** 2026-03-18T12:45:02Z
- **Completed:** 2026-03-18T13:10:29Z
- **Tasks:** 2
- **Files modified:** 5 (4 created + 1 modified)

## Accomplishments

- Created `DremioJdbcContainer` wrapping `dremio-oss:jdbc-test` Docker image with /apiv2/server_status health wait and Assume-based skip guard when image absent
- Created `DremioJdbcPgContainer` and `DremioJdbcOracleContainer` in the integration package (avoids importing test classes from sibling modules)
- Created `TestDremioJdbcIntegration` with 35 @Test methods spanning all 7 pushdown sections from test-regression.sh plus ADBC section, verified against the running Docker stack
- Added 6 test-scoped dependencies to jdbc-base pom (testcontainers, postgresql, oracle-xe, dremio-testcontainers-core, PG JDBC driver, ojdbc11)
- All 35 tests pass when `dremio-oss:jdbc-test` image is present; test class skips cleanly when image is absent

## Task Commits

No per-task commits per project instructions (waiting for all plans to pass).

## Files Created/Modified

- `plugins/jdbc-base/pom.xml` — added 6 test-scoped dependencies (testcontainers core, PG, Oracle-XE, dremio-testcontainers-core, PG JDBC driver, ojdbc11)
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcContainer.java` — GenericContainer wrapper for dremio-oss:jdbc-test image with HTTP wait strategy
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcPgContainer.java` — PostgreSQLContainer wrapper with log_statement=all command
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcOracleContainer.java` — OracleContainer wrapper for gvenzl/oracle-xe:21-slim
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/TestDremioJdbcIntegration.java` — 35-test integration suite

## Decisions Made

- `OracleContainer.withNetwork/withNetworkAliases` return the raw `OracleContainer` type (not subclass), so the Oracle container is configured in a static initializer block after field construction, then assigned to the @ClassRule field.
- `ORACLE_PASSWORD` in testcontainers `gvenzl/oracle-xe` is set to `ORACLE.getPassword()` by `OracleContainer.configure()` — using hardcoded `orapass` fails; must use `ORACLE.getPassword()` for SYS sqlplus connections.
- Oracle sqlplus V$SQL queries are written to `/tmp/*.sql` temp files via `echo >> file` chains inside `sh -c` to avoid `$` shell expansion of `v$sql` references in the execInContainer command string.
- PG JDBC driver and ojdbc11 added as test-scoped dependencies to `jdbc-base/pom.xml` — needed to seed PG and Oracle containers directly via JDBC in `@BeforeClass` before the Dremio source is created.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added PostgreSQL and Oracle JDBC drivers as test dependencies**
- **Found during:** Task 2 (TestDremioJdbcIntegration @BeforeClass seedPostgres)
- **Issue:** `No suitable driver found for jdbc:postgresql://...` — pom.xml had testcontainers but not the actual JDBC drivers needed for local seeding
- **Fix:** Added `org.postgresql:postgresql` and `com.oracle.database.jdbc:ojdbc11` as test-scoped dependencies
- **Files modified:** plugins/jdbc-base/pom.xml
- **Verification:** Compilation + test run succeeds

**2. [Rule 1 - Bug] Fixed OracleContainer SYS password — use ORACLE.getPassword() not hardcoded "orapass"**
- **Found during:** Task 2 (Oracle pushdown verification via V$SQL)
- **Issue:** Plan specified `sys/orapass@//localhost:1521/XEPDB1` but testcontainers `OracleContainer.configure()` sets `ORACLE_PASSWORD = this.password` (same as APP_USER_PASSWORD = "testpass"). Wrong SYS password caused V$SQL queries to fail silently, returning empty results.
- **Fix:** Changed `oraFlushSharedPool()` and `oraQueriesSince()` to use `ORACLE.getPassword()` dynamically instead of hardcoded "orapass"
- **Files modified:** TestDremioJdbcIntegration.java
- **Verification:** All 9 previously-failing Oracle V$SQL tests now pass

**3. [Rule 1 - Bug] Fixed OracleContainer network configuration — static block pattern**
- **Found during:** Task 2 (compilation)
- **Issue:** `DremioJdbcOracleContainer.withNetwork(NETWORK)` returns `OracleContainer` (not subclass), causing type mismatch in field declaration
- **Fix:** Declare `ORACLE_CONTAINER` field separately, configure in static block, assign to `ORACLE` @ClassRule field
- **Files modified:** TestDremioJdbcIntegration.java
- **Verification:** Compilation succeeds

**4. [Rule 1 - Bug] Fixed Oracle V$SQL query shell escaping — echo >> temp file approach**
- **Found during:** Task 2 (Oracle pushdown tests returning empty results)
- **Issue:** `sh -c "echo \"... v\$sql ...\" | sqlplus"` — the `\$sql` in Java string becomes `\$sql` in shell double-quoted context, where `\$` should produce `$`. But the `echo` approach in `sh -c` with complex escaping was unreliable.
- **Fix:** Rewrote `oraQueriesSince()` to build the SQL file using a series of `echo "line" >> /tmp/vsql_query.sql` calls, then execute `sqlplus @/tmp/vsql_query.sql`. Within double-quoted shell strings, `\$` produces a literal `$` reliably.
- **Files modified:** TestDremioJdbcIntegration.java
- **Verification:** V$SQL queries return correct results; 9 Oracle tests pass

---

**Total deviations:** 4 auto-fixed (2 bugs, 2 blocking)
**Impact on plan:** All auto-fixes necessary for correctness and test execution. No scope creep.

## Issues Encountered

None beyond the auto-fixed deviations above.

## User Setup Required

None - no external service configuration required. The `dremio-oss:jdbc-test` Docker image is pre-built on this machine.

## Next Phase Readiness

- Phase 39 complete — Tier 2 integration test suite is in place as a permanent regression firewall
- All 35 tests pass against the live Dremio planner stack with PostgreSQL and Oracle backends
- Tests skip cleanly when `dremio-oss:jdbc-test` image is absent (CI-friendly)

## Self-Check: PASSED

Files created:
- plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcContainer.java: FOUND
- plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcPgContainer.java: FOUND
- plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcOracleContainer.java: FOUND
- plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/TestDremioJdbcIntegration.java: FOUND
- plugins/jdbc-base/pom.xml: MODIFIED

Test results: 35 tests run, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS

---
*Phase: 39-docker-based-planner-integration-tests-full-pushdown-verification-with-adbc*
*Completed: 2026-03-18*
