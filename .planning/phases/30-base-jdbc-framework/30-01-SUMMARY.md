---
phase: 30-base-jdbc-framework
plan: 01
subsystem: database
tags: [jdbc, hikaricp, maven, connection-pooling, storage-plugin, dremio-plugin]

# Dependency graph
requires: []
provides:
  - dremio-plugin-jdbc-base Maven module compiling within Dremio build
  - HikariCP 5.1.0 managed in root POM dependencyManagement
  - arrow-jdbc managed in root POM dependencyManagement
  - BaseJdbcConf abstract ConnectionConf with pool configuration fields
  - JdbcConnectionPool HikariCP wrapper for per-query connection access
  - JdbcStoragePlugin with start/close lifecycle and getState() health check
affects: [30-02, 30-03, 30-04, 30-05]

# Tech tracking
tech-stack:
  added:
    - "com.zaxxer:HikariCP:5.1.0 — production JDBC connection pooling"
    - "org.apache.arrow:arrow-jdbc:${arrow.version} — JDBC-to-Arrow type conversion (future plans)"
  patterns:
    - "ConnectionConf subclass with @Tag-annotated fields for Protostuff serialization"
    - "StoragePlugin lifecycle: start() creates pool, close() shuts it down"
    - "Health check via validation query through pooled connection in getState()"

key-files:
  created:
    - plugins/jdbc-base/pom.xml
    - plugins/jdbc-base/src/main/resources/sabot-module.conf
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/BaseJdbcConf.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/pool/JdbcConnectionPool.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java
  modified:
    - pom.xml (root) — added HikariCP 5.1.0 and arrow-jdbc to dependencyManagement
    - plugins/pom.xml — added jdbc-base module entry

key-decisions:
  - "HikariCP 5.1.0 declared in root POM dependencyManagement (not scoped to hive3); 2.6.1 entry in hive-function-registry is a separate test-scope property"
  - "BaseJdbcConf does NOT use @SourceType — that annotation belongs only on concrete connector subclasses"
  - "JdbcStoragePlugin stubs listDatasetHandles/getDatasetHandle returning empty — schema discovery deferred to plan 02"
  - "driverClassName() may return null to allow JDBC 4 service-loader auto-discovery; pool sets it only when non-null"

patterns-established:
  - "Pattern: abstract ConnectionConf<T,P> base with @Tag(N) fields and abstract jdbcUrl()/driverClassName()"
  - "Pattern: HikariCP pool wrapper (JdbcConnectionPool) held by plugin, not raw Connection"
  - "Pattern: getState() runs validation query via try-with-resources; returns GOOD or badState on SQLException"

requirements-completed:
  - BASE-01
  - BASE-08

# Metrics
duration: 9min
completed: 2026-03-12
---

# Phase 30 Plan 01: Base JDBC Framework Summary

**HikariCP 5.1.0 connection pool with configurable health-check query wired into a Dremio StoragePlugin skeleton that compiles as `dremio-plugin-jdbc-base`**

## Performance

- **Duration:** 9 min
- **Started:** 2026-03-12T21:16:17Z
- **Completed:** 2026-03-12T21:25:00Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments
- New `plugins/jdbc-base` Maven module added to the build tree; compiles clean with Java 21 / Maven 3.9.9
- `BaseJdbcConf` provides reusable `@Tag`-annotated `poolSize`, `idleTimeoutMs`, and `validationQuery` fields for all concrete JDBC connector confs
- `JdbcConnectionPool` wraps `HikariDataSource` and exposes `getConnection()` / `close()` for per-query use
- `JdbcStoragePlugin.getState()` executes the validation query and returns `SourceState.GOOD` or `SourceState.badState(...)` for Dremio health monitoring

## Task Commits

Each task was committed atomically:

1. **Task 1: Create jdbc-base Maven module and update parent POMs** - `1fde767f1` (feat)
2. **Task 2: Implement BaseJdbcConf, JdbcConnectionPool, and JdbcStoragePlugin** - `0d145760e` (feat)

**Plan metadata:** `(pending)` (docs: complete plan)

## Files Created/Modified

- `pom.xml` — HikariCP 5.1.0 and arrow-jdbc added to `<dependencyManagement>`
- `plugins/pom.xml` — `<module>jdbc-base</module>` added between icebergcatalog and mongo
- `plugins/jdbc-base/pom.xml` — module POM with sabot-kernel, dremio-common, HikariCP, arrow-jdbc deps
- `plugins/jdbc-base/src/main/resources/sabot-module.conf` — classpath scanning registration for `com.dremio.plugins.jdbc`
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/BaseJdbcConf.java` — abstract ConnectionConf base
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/pool/JdbcConnectionPool.java` — HikariCP wrapper
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java` — StoragePlugin with pool lifecycle and health check

## Decisions Made

- **HikariCP versioning:** 5.1.0 added separately to root POM `<dependencyManagement>`. The existing `plugin.hive3.hikaricp.version=2.6.1` property is hive3 test-scope only and was not modified.
- **No `@SourceType` on BaseJdbcConf:** The research anti-pattern guidance explicitly prohibits this. Only concrete subclasses (e.g., `PostgresConf`) carry `@SourceType`.
- **Null driver class name:** `driverClassName()` can return null; `JdbcConnectionPool` only calls `setDriverClassName()` when non-null, supporting JDBC 4 auto-loading.
- **Stub metadata methods:** `listDatasetHandles`, `getDatasetHandle`, etc. return empty/throw in the base plugin; real DatabaseMetaData-based discovery is deferred to plan 02.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- The project's `.mvn/maven.config` contains comment lines that Maven 3.8 cannot parse, but Maven 3.9.9 (at `~/.local/share/maven/bin/mvn`) with Java 21 handles the build correctly after stripping comments from maven.config temporarily. The file was restored after each build run. This is a pre-existing environment issue unrelated to this plan's changes.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `dremio-plugin-jdbc-base` module is buildable and on the classpath
- `BaseJdbcConf`, `JdbcConnectionPool`, and `JdbcStoragePlugin` provide the foundation for plan 02 (schema discovery + RecordReader)
- Plan 02 can now extend `JdbcStoragePlugin` to add `DatabaseMetaData`-based listing and the `JdbcRecordReader`
- Plan 03 can build a concrete connector (e.g., PostgreSQL) by subclassing `BaseJdbcConf` with `@SourceType`

## Self-Check: PASSED

All created files exist on disk. All task commits verified in git log:
- 1fde767f1: feat(30-01): create jdbc-base Maven module skeleton
- 0d145760e: feat(30-01): implement BaseJdbcConf, JdbcConnectionPool, and JdbcStoragePlugin

---
*Phase: 30-base-jdbc-framework*
*Completed: 2026-03-12*
