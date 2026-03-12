---
phase: 31-postgresql-connector
plan: 01
subsystem: database
tags: [jdbc, postgresql, hikaricp, arrow, maven, dremio-plugin]

# Dependency graph
requires:
  - phase: 30-base-jdbc-framework
    provides: BaseJdbcConf, JdbcConnectionPool, JdbcRecordReader, JdbcStoragePlugin, JdbcSchemaFetcher, JdbcScanCreator
provides:
  - PostgresConf @SourceType(POSTGRES_DB) with full field set (hostname/port/db/auth/SSL/perf/pool)
  - PostgresSchemaFetcher with PG-native type mapping (UUID, JSONB, arrays, CIDR, INET, etc.)
  - PostgresRecordReader with autoCommit=false for cursor-based streaming
  - BaseJdbcConf.getUsername/getPassword/getConnectionProperties hooks with null/empty defaults
  - JdbcConnectionPool: username/password/properties wired from conf into HikariConfig
  - JdbcRecordReader.configureConnection(Connection) hook called before prepareStatement
  - JdbcStoragePlugin.createSchemaFetcher() and createRecordReader() factory methods
  - JdbcScanCreator: uses plugin.createRecordReader() factory
  - plugins/jdbc-postgresql Maven module with pom.xml, sabot-module.conf, postgres-layout.json
  - POSTGRES_DB.svg icons in dremio/sources/ and dremio-dark/sources/
affects:
  - 31-postgresql-connector (plan 02 tests build on this)
  - 32-packaging-and-distribution (daemon depends on this artifact)

# Tech tracking
tech-stack:
  added:
    - org.postgresql:postgresql 42.7.3 (managed by root POM)
    - org.testcontainers:postgresql (test scope, managed via testcontainers-bom)
  patterns:
    - PostgreSQL connector extends BaseJdbcConf via anonymous JdbcStoragePlugin subclass in newPlugin()
    - Factory method pattern for createSchemaFetcher/createRecordReader enables per-plugin polymorphism
    - configureConnection hook between getConnection() and prepareStatement() for connection-level setup
    - @SourceType with uiConfig JSON for declarative UI form definition

key-files:
  created:
    - plugins/jdbc-postgresql/pom.xml
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresConf.java
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresSchemaFetcher.java
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresRecordReader.java
    - plugins/jdbc-postgresql/src/main/resources/postgres-layout.json
    - plugins/jdbc-postgresql/src/main/resources/sabot-module.conf
    - dac/ui-lib/icons/dremio/sources/POSTGRES_DB.svg
    - dac/ui-lib/icons/dremio-dark/sources/POSTGRES_DB.svg
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/BaseJdbcConf.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/pool/JdbcConnectionPool.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/JdbcRecordReader.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcScanCreator.java
    - plugins/pom.xml
    - dac/daemon/pom.xml

key-decisions:
  - "createRecordReader() is public (not protected) in JdbcStoragePlugin because JdbcScanCreator lives in a sibling package and cannot access protected methods"
  - "SecretRef.get() used directly for getPassword() instead of SecretRef.toConfiguration() which requires a prefix string argument not applicable here"
  - "PostgresConf.newPlugin() uses anonymous JdbcStoragePlugin subclass to wire factory overrides without introducing a named subclass in the postgresql package"
  - "Tag numbers 10-41 in PostgresConf avoid conflict with BaseJdbcConf Tags 1-3; poolSize/idleTimeoutMs (Tags 1-2) satisfy PG-01 maxIdleConns/idleTimeSec requirements"

patterns-established:
  - "Factory method pattern: JdbcStoragePlugin.createSchemaFetcher/createRecordReader overridden per-database via anonymous inner class in newPlugin()"
  - "Connection hook: configureConnection(Connection) called between getConnection() and prepareStatement() — all database-specific pre-query setup goes here"
  - "Auth hook: getUsername/getPassword/getConnectionProperties in BaseJdbcConf are null-safe defaults; concrete conf classes override only what they need"

requirements-completed: [PG-01, PG-02, PG-03, PG-04]

# Metrics
duration: 21min
completed: 2026-03-12
---

# Phase 31 Plan 01: PostgreSQL Connector Source Code Summary

**PostgreSQL JDBC connector module with PG-native type mapping, cursor-based streaming, SSL/TLS support, and backward-compatible base class authentication hooks**

## Performance

- **Duration:** 21 min
- **Started:** 2026-03-12T23:31:57Z
- **Completed:** 2026-03-12T23:53:25Z
- **Tasks:** 3
- **Files modified:** 13

## Accomplishments

- Base class amendments adding getUsername/getPassword/getConnectionProperties hooks and createSchemaFetcher/createRecordReader factory methods — fully backward-compatible with null/empty/no-op defaults
- New `plugins/jdbc-postgresql` Maven module with PostgresConf (@SourceType POSTGRES_DB), postgres-layout.json UI form, sabot-module.conf, POSTGRES_DB.svg icons, module registration in plugins/pom.xml and dac/daemon/pom.xml
- PostgresSchemaFetcher maps UUID, JSONB, JSON, MONEY, INTERVAL, CIDR, INET, MACADDR, arrays, and unconstrained NUMERIC to safe Arrow types; excludes pg_internal system schema
- PostgresRecordReader sets autoCommit=false for cursor-based result set streaming (required for PostgreSQL JDBC to honour fetchSize)

## Task Commits

1. **Task 1: Amend Phase 30 base classes with authentication and connection hooks** - `7b1ddb75c` (feat)
2. **Task 2: Create jdbc-postgresql module skeleton with PostgresConf, POM, UI layout, icon, and Maven wiring** - `833356dd4` (feat)
3. **Task 3: Create PostgresSchemaFetcher, PostgresRecordReader, and base-class factory methods** - `7cd079a80` (feat)

## Files Created/Modified

- `plugins/jdbc-base/.../conf/BaseJdbcConf.java` - Added getUsername, getPassword, getConnectionProperties hooks
- `plugins/jdbc-base/.../pool/JdbcConnectionPool.java` - Wires auth hooks into HikariConfig
- `plugins/jdbc-base/.../reader/JdbcRecordReader.java` - Calls configureConnection() before prepareStatement
- `plugins/jdbc-base/.../JdbcStoragePlugin.java` - createSchemaFetcher + createRecordReader factory methods; start() uses createSchemaFetcher
- `plugins/jdbc-base/.../exec/JdbcScanCreator.java` - Uses plugin.createRecordReader() factory
- `plugins/jdbc-postgresql/pom.xml` - Module definition with postgresql, testcontainers test deps
- `plugins/jdbc-postgresql/.../PostgresConf.java` - @SourceType(POSTGRES_DB), all config fields (Tags 10-41), jdbcUrl/driverClassName/getUsername/getPassword/getConnectionProperties, newPlugin() anonymous subclass
- `plugins/jdbc-postgresql/.../PostgresSchemaFetcher.java` - PG-native type mapping and pg_internal exclusion
- `plugins/jdbc-postgresql/.../PostgresRecordReader.java` - autoCommit=false for cursor streaming
- `plugins/jdbc-postgresql/src/main/resources/postgres-layout.json` - UI form with General + Advanced Options tabs
- `plugins/jdbc-postgresql/src/main/resources/sabot-module.conf` - Package classpath scanning registration
- `dac/ui-lib/icons/dremio/sources/POSTGRES_DB.svg` - Source icon (copied from POSTGRES.svg)
- `dac/ui-lib/icons/dremio-dark/sources/POSTGRES_DB.svg` - Dark theme source icon
- `plugins/pom.xml` - jdbc-postgresql module registration
- `dac/daemon/pom.xml` - dremio-plugin-jdbc-postgresql daemon dependency

## Decisions Made

- `createRecordReader()` made `public` (not `protected`) in JdbcStoragePlugin because JdbcScanCreator is in a sibling package (`jdbc.exec` vs `jdbc`) and Java's access rules prevent protected method calls across package boundaries except via inheritance.
- `SecretRef.get()` used directly for getPassword() rather than `SecretRef.toConfiguration()` which requires a hadoop-style prefix string not applicable to JDBC URL credentials.
- PostgresConf.newPlugin() uses an anonymous JdbcStoragePlugin subclass to wire both factory overrides without introducing a named PostgresStoragePlugin class — the anonymous pattern is simpler for a single-source connector.
- Tag numbers start at 10 in PostgresConf (10-41) to leave Tags 1-9 reserved for BaseJdbcConf; Tags 1-3 (poolSize, idleTimeoutMs, validationQuery) are inherited and exposed in UI to satisfy PG-01 requirements.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] createRecordReader access modifier widened to public**
- **Found during:** Task 3 (compile verification)
- **Issue:** Plan specified `protected` for `createRecordReader` in JdbcStoragePlugin, but JdbcScanCreator is in the `com.dremio.plugins.jdbc.exec` package while JdbcStoragePlugin is in `com.dremio.plugins.jdbc`. Java's protected access rule prohibits cross-package access except via inheritance — the direct call `plugin.createRecordReader(...)` in JdbcScanCreator failed with "has protected access" compilation error.
- **Fix:** Changed `createRecordReader` in JdbcStoragePlugin from `protected` to `public`; updated the override in PostgresConf's anonymous subclass to `public` as well (Java prohibits narrowing access in overrides).
- **Files modified:** plugins/jdbc-base/.../JdbcStoragePlugin.java, plugins/jdbc-postgresql/.../PostgresConf.java
- **Verification:** `mvn compile -pl plugins/jdbc-postgresql -am` passed cleanly after the fix
- **Committed in:** `7cd079a80` (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Necessary correction — `protected` access is semantically inappropriate for a method called from a sibling package class. Making it `public` is the correct design since it's an intentional extension point.

## Issues Encountered

None — only the access modifier issue documented above (auto-fixed under deviation rules).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- PostgreSQL connector source code complete and compiling; ready for Phase 31 Plan 02 (integration tests with Testcontainers)
- All factory hooks in place; Plan 02 tests can mock or override via the same extension points
- No blockers

## Self-Check: PASSED

All created files verified present. All task commits verified in git log:
- 7b1ddb75c — Task 1 (base class amendments)
- 833356dd4 — Task 2 (jdbc-postgresql module skeleton)
- 7cd079a80 — Task 3 (PostgresSchemaFetcher, PostgresRecordReader, factory methods)

---
*Phase: 31-postgresql-connector*
*Completed: 2026-03-12*
