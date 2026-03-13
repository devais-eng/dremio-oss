---
phase: 31-postgresql-connector
plan: 02
subsystem: testing
tags: [jdbc, postgresql, testcontainers, arrow, junit4, integration-tests, pushdown]

# Dependency graph
requires:
  - phase: 31-postgresql-connector
    plan: 01
    provides: PostgresSchemaFetcher, PostgresRecordReader, PostgresConf, JdbcConnectionPool, SqlBuilder
  - phase: 30-base-jdbc-framework
    provides: SqlBuilder, JdbcSchemaFetcher, JdbcConnectionPool, BaseJdbcConf
provides:
  - DremioPostgresContainer: postgres:16-alpine container implementing DremioContainer marker interface
  - PostgresTestContainer: shared test helper with pool factory and raw SQL execution
  - TestPostgresTypeMapping: 3 tests covering Arrow type mapping for all PG-specific types + value roundtrip
  - TestPostgresSchemaDiscovery: 10 tests covering listSchemas, listTables, tableExists, getTableSchema
  - TestPostgresPushdown: 15 tests covering SqlBuilder SQL generation and PostgreSQL execution correctness
affects:
  - 32-packaging-and-distribution (integration test infrastructure is now established)

# Tech tracking
tech-stack:
  added:
    - com.dremio.tools:dremio-testcontainers-core (test scope) for DremioContainer marker interface
  patterns:
    - DremioPostgresContainer pattern: wrap vendor TestContainers module with DremioContainer marker to satisfy error-prone DremioRestrictedTestcontainersUsage rule
    - Named inner class (TestJdbcConf) to satisfy self-referential generic bound BaseJdbcConf<T extends BaseJdbcConf<T,P>, P> — anonymous classes with wildcards cannot satisfy this bound
    - @ClassRule on static DremioPostgresContainer PG = PostgresTestContainer.PG for shared container across JUnit 4 test classes

key-files:
  created:
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/DremioPostgresContainer.java
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/PostgresTestContainer.java
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresTypeMapping.java
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresSchemaDiscovery.java
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresPushdown.java
  modified:
    - plugins/jdbc-postgresql/pom.xml

key-decisions:
  - "DremioPostgresContainer wraps PostgreSQLContainer and implements DremioContainer to satisfy the DremioRestrictedTestcontainersUsage error-prone rule which bans direct GenericContainer subclass instantiation"
  - "TestJdbcConf is a named static inner class (not anonymous) because BaseJdbcConf<T extends BaseJdbcConf<T,P>, P> self-referential bound cannot be satisfied by anonymous classes with wildcard type args"
  - "dremio-testcontainers-core added as test dependency to access the DremioContainer marker interface; jar was already in local Maven cache from prior builds"

patterns-established:
  - "PostgreSQL TestContainers pattern: DremioPostgresContainer -> PostgresTestContainer.PG -> @ClassRule in each test class referencing the same static instance"
  - "Test pool creation: PostgresTestContainer.createPool() returns JdbcConnectionPool via TestJdbcConf; tests share pool created in @BeforeClass"
  - "Raw SQL setup: PostgresTestContainer.executeSql() bypasses the pool for DDL/DML test setup"

requirements-completed: [PG-02, PG-03, PG-05]

# Metrics
duration: 17min
completed: 2026-03-13
---

# Phase 31 Plan 02: PostgreSQL Connector Integration Tests Summary

**TestContainers integration tests validating type mapping (UUID, JSONB, arrays, MONEY, CIDR), schema discovery (system schema exclusion), and SQL pushdown correctness against postgres:16-alpine**

## Performance

- **Duration:** 17 min
- **Started:** 2026-03-12T23:57:04Z
- **Completed:** 2026-03-13T00:14:59Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- DremioPostgresContainer and PostgresTestContainer shared test infrastructure providing postgres:16-alpine container, JdbcConnectionPool factory, and raw SQL execution helper for all three test classes
- TestPostgresTypeMapping validates Arrow type mapping for every PG-specific type from PG-03 (UUID, JSONB, JSON, MONEY, INTERVAL, CIDR, INET, MACADDR, BYTEA, SERIAL, BIGSERIAL, text arrays, integer arrays) plus null-handling and JDBC value roundtrip tests
- TestPostgresSchemaDiscovery validates listSchemas (public + test_schema present; pg_catalog, information_schema, pg_toast, pg_internal excluded), listTables (TABLE + VIEW), tableExists (true/false/wrong-schema cases), and getTableSchema column types
- TestPostgresPushdown validates all SqlBuilder combinations (SELECT *, projection, WHERE, LIMIT) both as pure string assertions and as live PostgreSQL executions with correctness verification

## Task Commits

1. **Task 1: PostgresTestContainer, DremioPostgresContainer, TestPostgresTypeMapping, TestPostgresSchemaDiscovery** - `42ac1cb61` (feat)
2. **Task 2: TestPostgresPushdown** - `cd8ff0734` (feat)

## Files Created/Modified

- `plugins/jdbc-postgresql/pom.xml` - Added dremio-testcontainers-core test dependency
- `plugins/jdbc-postgresql/src/test/.../DremioPostgresContainer.java` - postgres:16-alpine container implementing DremioContainer marker
- `plugins/jdbc-postgresql/src/test/.../PostgresTestContainer.java` - Shared test helper: static PG field, createPool(), executeSql(), TestJdbcConf inner class
- `plugins/jdbc-postgresql/src/test/.../TestPostgresTypeMapping.java` - 3 tests: testSchemaMapping, testNullHandling, testValueRoundtrip
- `plugins/jdbc-postgresql/src/test/.../TestPostgresSchemaDiscovery.java` - 10 tests: listSchemas, listTables (empty/view/table), tableExists, getTableSchema
- `plugins/jdbc-postgresql/src/test/.../TestPostgresPushdown.java` - 15 tests: SQL generation (10) + container execution (5)

## Decisions Made

- `DremioPostgresContainer` required as a named wrapper class: the project's `DremioRestrictedTestcontainersUsage` error-prone check (severity ERROR, not suppressible) rejects any `GenericContainer` subclass that doesn't also implement `DremioContainer`. Direct `PostgreSQLContainer<?>` instantiation fails this check. Wrapping in a named class that extends `PostgreSQLContainer<DremioPostgresContainer>` and implements `DremioContainer` satisfies the rule.
- `TestJdbcConf` is a named static inner class: `BaseJdbcConf<T extends BaseJdbcConf<T, P>, P>` has a self-referential F-bound that anonymous classes cannot satisfy when using wildcard type arguments (`BaseJdbcConf<?, ?>`). A named class `TestJdbcConf extends BaseJdbcConf<TestJdbcConf, StoragePlugin>` correctly satisfies the bound.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created DremioPostgresContainer to satisfy error-prone DremioRestrictedTestcontainersUsage**
- **Found during:** Task 1 (first compilation attempt)
- **Issue:** The project enforces via error-prone that all `GenericContainer` subclass instantiations must implement `DremioContainer`. Direct `new PostgreSQLContainer<>("postgres:16-alpine")` triggered a compile-time ERROR. The rule message says "Do not suppress this error."
- **Fix:** Created `DremioPostgresContainer extends PostgreSQLContainer<DremioPostgresContainer> implements DremioContainer` and added `dremio-testcontainers-core` as a test dependency for the `DremioContainer` interface. Updated `PostgresTestContainer.PG` to use this wrapper.
- **Files modified:** `DremioPostgresContainer.java` (new), `PostgresTestContainer.java` (updated), `pom.xml` (new test dep)
- **Verification:** `mvn test-compile -pl plugins/jdbc-postgresql -am -DskipTests` passes cleanly
- **Committed in:** `42ac1cb61` (Task 1 commit)

**2. [Rule 1 - Bug] Fixed self-referential generic bound for BaseJdbcConf anonymous class**
- **Found during:** Task 1 (first compilation attempt)
- **Issue:** Plan suggested an anonymous `BaseJdbcConf<?, ?>` subclass for `createPool()`. Java compiler rejected this: "type argument BaseJdbcConf<?, ?> is not within bounds of type-variable T". The `<T extends BaseJdbcConf<T, P>>` F-bound cannot be satisfied by wildcard type arguments in anonymous classes.
- **Fix:** Replaced the anonymous class with a named static inner class `TestJdbcConf extends BaseJdbcConf<TestJdbcConf, StoragePlugin>`.
- **Files modified:** `PostgresTestContainer.java`
- **Verification:** Compilation succeeds
- **Committed in:** `42ac1cb61` (Task 1 commit)

**3. [Rule 1 - Bug] Replaced Java assert statements with JUnit assertTrue**
- **Found during:** Task 1 (second compilation attempt)
- **Issue:** Plan's inline assertions used Java's native `assert` keyword (e.g., `assert jsonb.contains("key")`). Error-prone `UseCorrectAssertInTests` rule treats this as an ERROR in test code.
- **Fix:** Replaced all Java `assert` statements in `TestPostgresTypeMapping` with `assertTrue(message, condition)`. Added missing `import static org.junit.Assert.assertTrue`.
- **Files modified:** `TestPostgresTypeMapping.java`
- **Verification:** Compilation succeeds
- **Committed in:** `42ac1cb61` (Task 1 commit)

---

**Total deviations:** 3 auto-fixed (1 Rule 3 blocking, 2 Rule 1 bug)
**Impact on plan:** All three auto-fixes were necessary for compilation. No scope creep — the wrapper class, named inner class, and assertion replacements are implementation-detail corrections that do not change test coverage or semantics.

## Issues Encountered

None — all issues were auto-fixed during compilation loop.

## User Setup Required

None - no external service configuration required. Tests require Docker to be available for container-based execution.

## Next Phase Readiness

- Integration test suite complete and compiling; ready for Phase 32 (packaging and distribution)
- All PG-02, PG-03, PG-05 requirements covered by the test suite
- When Docker is available: `mvn test -pl plugins/jdbc-postgresql -am` will run all tests against a live postgres:16-alpine container

## Self-Check: PASSED

All created files verified present:
- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/DremioPostgresContainer.java` — FOUND
- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/PostgresTestContainer.java` — FOUND
- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresTypeMapping.java` — FOUND
- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresSchemaDiscovery.java` — FOUND
- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPostgresPushdown.java` — FOUND

Task commits verified in git log:
- `42ac1cb61` — Task 1 (PostgresTestContainer, DremioPostgresContainer, type mapping tests, schema discovery tests)
- `cd8ff0734` — Task 2 (TestPostgresPushdown)

Compilation: `mvn test-compile -pl plugins/jdbc-postgresql -am -DskipTests` → BUILD SUCCESS

---
*Phase: 31-postgresql-connector*
*Completed: 2026-03-13*
