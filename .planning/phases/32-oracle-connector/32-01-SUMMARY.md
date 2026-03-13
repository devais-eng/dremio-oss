---
phase: 32-oracle-connector
plan: 01
subsystem: database
tags: [oracle, jdbc, ojdbc11, sql-dialect, fetch-first, type-mapping]

# Dependency graph
requires:
  - phase: 30-base-jdbc-framework
    provides: JdbcStoragePlugin, JdbcSchemaFetcher, JdbcRecordReader, SqlBuilder, JdbcConnectionPool, JdbcScanPrel
  - phase: 31-postgresql-connector
    provides: PostgresConf pattern for anonymous subclass wiring via newPlugin()
provides:
  - OracleConf with @SourceType(ORACLE_DB) and serviceName-based EZConnect URL
  - OracleSchemaFetcher with Oracle-native type mapping (NUMBER, BINARY_FLOAT/DOUBLE, CLOB/NCLOB, NVARCHAR2/NCHAR, TIMESTAMP WITH TIME ZONE)
  - OracleSqlBuilder using FETCH FIRST N ROWS ONLY dialect
  - SqlBuilder pluggability via JdbcStoragePlugin.createSqlBuilder() factory method
  - plugins/jdbc-oracle Maven module registered in plugins/pom.xml and dac/daemon/pom.xml
affects:
  - 32-02-PLAN (Oracle integration tests — depends on OracleConf, OracleSchemaFetcher, OracleSqlBuilder)

# Tech tracking
tech-stack:
  added: [ojdbc11 23.7.0.25.01 (com.oracle.database.jdbc)]
  patterns:
    - "OracleConf.newPlugin() anonymous JdbcStoragePlugin subclass wires createSchemaFetcher + createRecordReader + createSqlBuilder overrides"
    - "OracleSqlBuilder overrides buildSql() to replace LIMIT N with FETCH FIRST N ROWS ONLY"
    - "OracleSchemaFetcher.isSystemSchema() is a full replacement (does NOT call super) because base checks PG-specific schemas"
    - "JdbcStoragePlugin.createSqlBuilder() factory resolved at plan time in JdbcScanPrel.getPhysicalOperator()"

key-files:
  created:
    - plugins/jdbc-oracle/pom.xml
    - plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleConf.java
    - plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleSchemaFetcher.java
    - plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleRecordReader.java
    - plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleSqlBuilder.java
    - plugins/jdbc-oracle/src/main/resources/oracle-layout.json
    - plugins/jdbc-oracle/src/main/resources/sabot-module.conf
    - dac/ui-lib/icons/dremio/sources/ORACLE_DB.svg
    - dac/ui-lib/icons/dremio-dark/sources/ORACLE_DB.svg
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java
    - plugins/pom.xml
    - dac/daemon/pom.xml
    - pom.xml

key-decisions:
  - "OracleConf.validationQuery set to SELECT 1 FROM DUAL in constructor (not field initializer) to override BaseJdbcConf default"
  - "OracleSchemaFetcher.isSystemSchema() does NOT call super — base class filters PG-specific schemas (pg_catalog, information_schema) which do not apply to Oracle"
  - "JdbcScanPrel resolves JdbcStoragePlugin at getPhysicalOperator() time via PhysicalPlanCreator; sqlBuilder field removed entirely — cleaner than keeping as fallback"
  - "Oracle SSL handled via connection properties (oracle.net.ssl_server_dn_match) not URL params — simple v1.5 approach"
  - "ojdbc11 23.7.0.25.01 added to root POM dependencyManagement alongside postgresql 42.7.3"

patterns-established:
  - "SqlBuilder dialect pluggability: override createSqlBuilder() in JdbcStoragePlugin anonymous subclass within newPlugin()"
  - "Oracle FLOAT sentinel: NUMERIC with scale=-127 means floating-point FLOAT, not fixed-point NUMBER"
  - "Oracle bare NUMBER: NUMERIC with precision=0 maps to DOUBLE to avoid DECIMAL(0,0) errors"

requirements-completed: [ORA-01, ORA-02, ORA-03, ORA-04]

# Metrics
duration: 10min
completed: 2026-03-13
---

# Phase 32 Plan 01: Oracle Connector Source Code Summary

**Oracle JDBC connector (ORACLE_DB) with FETCH FIRST dialect, NUMBER/FLOAT/CLOB/NCLOB/NVARCHAR2 type mapping, 18-schema system filter, and SqlBuilder pluggability injected into base framework**

## Performance

- **Duration:** 10 min
- **Started:** 2026-03-13T01:03:23Z
- **Completed:** 2026-03-13T01:13:23Z
- **Tasks:** 2
- **Files modified:** 14

## Accomplishments
- Added `createSqlBuilder()` factory method to `JdbcStoragePlugin` and updated `JdbcScanPrel.getPhysicalOperator()` to use plugin-resolved SQL dialect at plan time
- Created complete `plugins/jdbc-oracle` Maven module: OracleConf, OracleSchemaFetcher, OracleRecordReader, OracleSqlBuilder, layout JSON, sabot-module.conf
- OracleSchemaFetcher handles all Oracle-specific type semantics including Oracle JDBC type codes 100/101 (BINARY_FLOAT/DOUBLE), the -127 FLOAT scale sentinel, and 18 system schema exclusions
- Registered jdbc-oracle in plugins/pom.xml and dac/daemon/pom.xml; ojdbc11 23.7.0.25.01 added to root dependencyManagement

## Task Commits

Each task was committed atomically:

1. **Task 1: Amend base framework for SqlBuilder pluggability** - `0baab729c` (feat)
2. **Task 2: Create Oracle Maven module with OracleConf, type mapping, SQL builder, UI, icons, and wiring** - `685842ef6` (feat)

## Files Created/Modified
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java` - Added createSqlBuilder() factory returning base SqlBuilder by default
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java` - Removed sqlBuilder field; resolves plugin at plan time via PhysicalPlanCreator
- `plugins/jdbc-oracle/pom.xml` - Oracle module POM inheriting from dremio-plugin-parent
- `plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleConf.java` - @SourceType(ORACLE_DB), EZConnect URL, SELECT 1 FROM DUAL validation
- `plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleSchemaFetcher.java` - Full Oracle type mapping and system schema filtering
- `plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleRecordReader.java` - Structural placeholder extending JdbcRecordReader
- `plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleSqlBuilder.java` - FETCH FIRST N ROWS ONLY row-limiting syntax
- `plugins/jdbc-oracle/src/main/resources/oracle-layout.json` - UI form with serviceName (not databaseName)
- `plugins/jdbc-oracle/src/main/resources/sabot-module.conf` - Classpath scanner package registration
- `dac/ui-lib/icons/dremio/sources/ORACLE_DB.svg` - Light theme Oracle icon
- `dac/ui-lib/icons/dremio-dark/sources/ORACLE_DB.svg` - Dark theme Oracle icon
- `plugins/pom.xml` - Added jdbc-oracle module
- `dac/daemon/pom.xml` - Added dremio-plugin-jdbc-oracle dependency
- `pom.xml` - Added ojdbc11.version property and ojdbc11 managed dependency

## Decisions Made
- `OracleSchemaFetcher.isSystemSchema()` does NOT call `super.isSystemSchema()` — base class filters PostgreSQL-specific schemas (pg_catalog, information_schema, pg_toast_*, etc.) that do not apply to Oracle; full replacement avoids false positives
- `JdbcScanPrel` removes the `sqlBuilder` field entirely (was only used in `getPhysicalOperator()`); cleaner than keeping it as a fallback since the factory always provides a SqlBuilder
- Oracle SSL handled via `oracle.net.ssl_server_dn_match` connection property, not URL params — simple v1.5 approach consistent with Oracle JDBC driver documentation
- Oracle FLOAT type is represented as NUMERIC with scale=-127 in JDBC metadata; this sentinel is mapped to DOUBLE explicitly before the general NUMERIC handler

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- Maven 3.x (system default) could not parse the maven.config file with comment lines — resolved by using Maven 3.9.9 via `.local-env.sh` as established in prior phases.

## User Setup Required
None - no external service configuration required for source code compilation.

## Next Phase Readiness
- Oracle connector source code is complete and compiles cleanly
- Ready for Phase 32 Plan 02: Oracle connector integration tests using TestContainers (oracle-xe or gvenzl/oracle-xe)
- OracleConf, OracleSchemaFetcher, and OracleSqlBuilder are the primary classes under test in plan 02

---
*Phase: 32-oracle-connector*
*Completed: 2026-03-13*

## Self-Check: PASSED

- All 9 required files found on disk
- Commits 0baab729c and 685842ef6 verified in git log
- `mvn compile -pl plugins/jdbc-oracle -am -DskipTests` returns BUILD SUCCESS
