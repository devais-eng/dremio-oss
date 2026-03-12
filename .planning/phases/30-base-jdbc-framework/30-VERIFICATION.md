---
phase: 30-base-jdbc-framework
verified: 2026-03-12T22:10:00Z
status: passed
score: 17/17 must-haves verified
re_verification: false
---

# Phase 30: Base JDBC Framework Verification Report

**Phase Goal:** A reusable JDBC base module exists that any database connector can extend — providing connection pooling, schema discovery, type mapping, Arrow batch conversion, pushdown, and health reporting
**Verified:** 2026-03-12T22:10:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A new Maven module `plugins/jdbc-base` compiles as part of the project build | VERIFIED | `plugins/jdbc-base/pom.xml` declares `dremio-plugin-jdbc-base`; compiled `.class` files exist under `target/classes/`; `plugins/pom.xml` line 49 adds `<module>jdbc-base</module>` |
| 2 | HikariCP 5.1.0 is declared in root POM dependencyManagement and used by jdbc-base | VERIFIED | Root `pom.xml` line 1063–1066: `<artifactId>HikariCP</artifactId><version>5.1.0</version>`; `plugins/jdbc-base/pom.xml` references HikariCP without version (inherits) |
| 3 | `BaseJdbcConf` exposes configurable `poolSize`, `idleTimeoutMs`, and `validationQuery` fields with `@Tag` annotations | VERIFIED | `BaseJdbcConf.java` lines 38–49: `@Tag(1) public int poolSize = 5`, `@Tag(2) @NotMetadataImpacting public int idleTimeoutMs = 600_000`, `@Tag(3) @NotMetadataImpacting public String validationQuery = "SELECT 1"` |
| 4 | `JdbcStoragePlugin.start()` creates a HikariCP connection pool from conf | VERIFIED | `JdbcStoragePlugin.java` lines 101–104: `start()` creates `new JdbcConnectionPool(conf)` and `new JdbcSchemaFetcher(pool)` |
| 5 | `JdbcStoragePlugin.close()` shuts down the pool | VERIFIED | `JdbcStoragePlugin.java` lines 110–116: `close()` calls `pool.close()` and nullifies references |
| 6 | `JdbcStoragePlugin.getState()` returns GOOD when validation query succeeds, badState on failure | VERIFIED | `JdbcStoragePlugin.java` lines 124–135: executes `conf.validationQuery` via `Statement.execute()`; returns `SourceState.GOOD` on success, `SourceState.badState("Validation failed: ...")` on `SQLException`, and `SourceState.badState("Source not started")` when pool is null |
| 7 | `JdbcSchemaFetcher` discovers schemas, tables, and columns via JDBC `DatabaseMetaData` | VERIFIED | `JdbcSchemaFetcher.java`: `listSchemas()` calls `meta.getSchemas()`, `listTables()` calls `meta.getTables(null, schemaName, "%", {"TABLE","VIEW"})`, `getTableSchema()` calls `meta.getColumns(null, schemaName, tableName, "%")`; all use try-with-resources pooled connections |
| 8 | `JdbcStoragePlugin` implements `SupportsListingDatasets`, returning dataset handles from `JdbcSchemaFetcher` | VERIFIED | `JdbcStoragePlugin.java` class declaration: `implements StoragePlugin, SupportsListingDatasets`; `listDatasetHandles()` iterates `schemaFetcher.listSchemas()` and `schemaFetcher.listTables()` |
| 9 | `JdbcRecordReader` maps JDBC `ResultSet` rows to Arrow vectors using arrow-jdbc type mapping | VERIFIED | `JdbcRecordReader.java`: `setup()` registers vectors via `TypeHelper.getValueVectorClass()`; `next()` calls `writeValue()` which covers 11 concrete types: `IntVector`, `BigIntVector`, `Float4Vector`, `Float8Vector`, `BitVector`, `VarCharVector`, `VarBinaryVector`, `DecimalVector`, `DateMilliVector`, `TimeMilliVector`, `TimeStampMilliVector`, plus string fallback |
| 10 | `JdbcSubScan` carries the SQL query string and is serialized via JSON annotations | VERIFIED | `JdbcSubScan.java`: annotated `@JsonTypeName("jdbc-sub-scan")`, `@JsonCreator` constructor, `@JsonProperty` on all fields; `getOperatorType()` returns `CoreOperatorType.JDBC_SUB_SCAN_VALUE` (47) |
| 11 | `JdbcScanCreator` creates `ScanOperator` from `JdbcSubScan`, wiring `RecordReader` to execution engine | VERIFIED | `JdbcScanCreator.java`: `fec.getStoragePlugin(config.getPluginId())` → `new JdbcRecordReader(context, config, plugin.getPool())` → `new ScanOperator(fec, config, context, RecordReaderIterator.from(reader))` |
| 12 | `JdbcScanPrel` rewrites queries to include `WHERE` clauses from pushed-down filters | VERIFIED | `JdbcPushFilterIntoScan.java`: matches `FilterPrel` above `JdbcScanPrel`; `RexToSqlString` converts `RexNode` to SQL (11 operators); `call.transformTo(scan.cloneWithFilter(whereClause))`. `JdbcScanPrel.cloneWithFilter()` preserves other state |
| 13 | `JdbcScanPrel` rewrites queries to `SELECT` only projected columns | VERIFIED | `JdbcPushProjectIntoScan.java`: matches `ProjectPrel` above `JdbcScanPrel`; extracts `RexInputRef` indices, maps to column names via `SchemaPath.getSimplePath()`; calls `scan.cloneWithProject(projectedColumns)`. `SqlBuilder.buildSql()` emits `SELECT "col1", "col2"` when columns non-empty |
| 14 | `JdbcScanPrel` rewrites queries to include `LIMIT N` | VERIFIED | `JdbcPushLimitIntoScan.java`: matches `LimitPrel` above `JdbcScanPrel`; guards non-zero offsets; takes minimum when existing limit present; calls `scan.cloneWithLimit(newLimit)`. `SqlBuilder.buildSql()` appends `LIMIT N` |
| 15 | All three pushdown rules are registered in `JdbcRulesFactory` for the correct planning phases | VERIFIED | `JdbcRulesFactory.java`: LOGICAL phase returns `new JdbcScanDrule(pluginType)`; PHYSICAL phase returns `ImmutableSet.of(JdbcScanPrule.INSTANCE, JdbcPushFilterIntoScan.INSTANCE, JdbcPushProjectIntoScan.INSTANCE, JdbcPushLimitIntoScan.INSTANCE)` |
| 16 | `SqlBuilder` constructs syntactically correct SQL from schema, table, columns, filter, and limit | VERIFIED | `SqlBuilder.java`: `buildSql()` assembles `SELECT "col1","col2" FROM "schema"."table" WHERE ... LIMIT N`; `quoteIdentifier()` wraps in double-quotes and escapes internal double-quotes per SQL standard |
| 17 | Classpath scanning registration covers the `com.dremio.plugins.jdbc` package | VERIFIED | `sabot-module.conf`: `dremio.classpath.scanning.packages += "com.dremio.plugins.jdbc"` |

**Score:** 17/17 truths verified

---

### Required Artifacts

| Artifact | Status | Details |
|----------|--------|---------|
| `plugins/jdbc-base/pom.xml` | VERIFIED | Exists; declares `dremio-plugin-jdbc-base`; lists HikariCP, arrow-jdbc, sabot-kernel, dremio-common; `.flattened-pom.xml` confirms Maven processed it |
| `plugins/jdbc-base/src/main/resources/sabot-module.conf` | VERIFIED | Exists; contains `dremio.classpath.scanning.packages += "com.dremio.plugins.jdbc"` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/BaseJdbcConf.java` | VERIFIED | 67 lines; abstract class with `@Tag(1,2,3)` fields; abstract `jdbcUrl()` and `driverClassName()` methods; no `@SourceType` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/pool/JdbcConnectionPool.java` | VERIFIED | 69 lines; `HikariDataSource` wrapper; `getConnection()`, `close()`, `AutoCloseable`; conditional `setDriverClassName()` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java` | VERIFIED | 299 lines; full `StoragePlugin` + `SupportsListingDatasets` implementation; pool lifecycle, health check, schema listing, `getRulesFactoryClass()` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/schema/JdbcSchemaFetcher.java` | VERIFIED | 192 lines; `listSchemas()`, `listTables()`, `tableExists()`, `getTableSchema()`; `mapJdbcType()` using `JdbcToArrowUtils`; protected `isSystemSchema()` hook |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/JdbcRecordReader.java` | VERIFIED | 308 lines; `setup()` registers vectors and executes query; `next()` reads rows and writes to 11 vector types; `close()` releases resources in reverse order; `writeValue()` protected for overrides |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcGroupScan.java` | VERIFIED | 179 lines; `getMaxParallelizationWidth()=1`; `getSplits()` returns single work unit; `getSpecificScan()` creates `JdbcSubScan` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcSubScan.java` | VERIFIED | 101 lines; `@JsonTypeName("jdbc-sub-scan")`; `@JsonCreator`/`@JsonProperty`; `getOperatorType()` returns `CoreOperatorType.JDBC_SUB_SCAN_VALUE` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcScanCreator.java` | VERIFIED | 47 lines; `ProducerOperator.Creator<JdbcSubScan>`; gets plugin, creates reader, wraps in `ScanOperator` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanDrel.java` | VERIFIED | 88 lines; extends `ScanRelBase` + `Rel`; `copy()` and `cloneWithProject()` present |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java` | VERIFIED | 251 lines; extends `ScanPrelBase`; `cloneWithFilter()`, `cloneWithProject()`, `cloneWithLimit()`; `getPhysicalOperator()` calls `SqlBuilder.buildSql()` then creates `JdbcGroupScan` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanDrule.java` | VERIFIED | 58 lines; extends `SourceLogicalConverter`; `convertScan()` produces `JdbcScanDrel` |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrule.java` | VERIFIED | 82 lines; `INSTANCE` singleton; matches `JdbcScanDrel`, produces `JdbcScanPrel` with null filter/limit; registered in PHYSICAL phase |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushFilterIntoScan.java` | VERIFIED | 271 lines; `INSTANCE` singleton; `RexToSqlString` inner class handling 11 operators; graceful null-bail on unsupported nodes |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushProjectIntoScan.java` | VERIFIED | 74 lines; `INSTANCE` singleton; `RexInputRef`-only push, bails on expressions |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushLimitIntoScan.java` | VERIFIED | 72 lines; `INSTANCE` singleton; offset guard; min-of-two logic |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java` | VERIFIED | 67 lines; extends `StoragePluginTypeRulesFactory`; LOGICAL → `JdbcScanDrule`; PHYSICAL → all four rules; default → empty |
| `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/SqlBuilder.java` | VERIFIED | 103 lines; `buildSql()` with SELECT/FROM/WHERE/LIMIT assembly; `quoteIdentifier()` with double-quote escaping |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `plugins/pom.xml` | `plugins/jdbc-base/pom.xml` | `<module>jdbc-base</module>` | WIRED | Line 49 in `plugins/pom.xml` |
| `JdbcStoragePlugin.java` | `JdbcConnectionPool.java` | `start()` creates pool, `close()` shuts down | WIRED | Lines 102, 111–114 |
| `JdbcStoragePlugin.java` | `BaseJdbcConf.java` | reads conf for pool config and validation query | WIRED | Constructor stores `conf`; `start()` passes to `JdbcConnectionPool(conf)`; `getState()` reads `conf.validationQuery` |
| `JdbcStoragePlugin.java` | `JdbcSchemaFetcher.java` | `listDatasetHandles()` delegates to schema fetcher | WIRED | `start()` creates `schemaFetcher = new JdbcSchemaFetcher(pool)`; all listing methods delegate to `schemaFetcher` |
| `JdbcScanCreator.java` | `JdbcRecordReader.java` | creates `RecordReader` from `SubScan` config | WIRED | `new JdbcRecordReader(context, config, plugin.getPool())` line 44 |
| `JdbcScanCreator.java` | `JdbcStoragePlugin.java` | `fec.getStoragePlugin(config.getPluginId())` to get pool | WIRED | `JdbcStoragePlugin plugin = fec.getStoragePlugin(config.getPluginId())` line 43 |
| `JdbcGroupScan.java` | `JdbcSubScan.java` | `getSpecificScan()` creates SubScan with SQL string | WIRED | `return new JdbcSubScan(props, schema, tableSchemaPath, sql, columns, pluginId)` line 132 |
| `JdbcRulesFactory.java` | `JdbcPushFilterIntoScan.java` | `getRules()` returns filter rule | WIRED | `JdbcPushFilterIntoScan.INSTANCE` in PHYSICAL case |
| `JdbcRulesFactory.java` | `JdbcPushProjectIntoScan.java` | `getRules()` returns project rule | WIRED | `JdbcPushProjectIntoScan.INSTANCE` in PHYSICAL case |
| `JdbcRulesFactory.java` | `JdbcPushLimitIntoScan.java` | `getRules()` returns limit rule | WIRED | `JdbcPushLimitIntoScan.INSTANCE` in PHYSICAL case |
| `JdbcScanPrel.java` | `JdbcGroupScan.java` | `getPhysicalOperator()` creates `JdbcGroupScan` with SQL from `SqlBuilder` | WIRED | Lines 218–229: `sqlBuilder.buildSql(...)` → `new JdbcGroupScan(...)` |
| `JdbcScanPrel.java` | `SqlBuilder.java` | `buildSql()` constructs final SQL with pushdown clauses | WIRED | `sqlBuilder.buildSql(schemaName, tableName, getProjectedColumns(), whereClause, limit)` line 218 |
| `JdbcStoragePlugin.java` | `JdbcRulesFactory.java` | `getRulesFactoryClass()` returns factory | WIRED | Line 173: `return JdbcRulesFactory.class` |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| BASE-01 | 30-01 | Plugin base module provides HikariCP connection pooling with configurable pool size, idle timeout, and validation query | SATISFIED | `JdbcConnectionPool` wraps `HikariDataSource`; `BaseJdbcConf` exposes `poolSize`, `idleTimeoutMs`, `validationQuery` with defaults; `JdbcStoragePlugin` manages lifecycle |
| BASE-02 | 30-02 | Plugin discovers schemas, tables, and columns via JDBC `DatabaseMetaData` | SATISFIED | `JdbcSchemaFetcher.listSchemas()` → `meta.getSchemas()`, `listTables()` → `meta.getTables()`, `getTableSchema()` → `meta.getColumns()`; wired into `JdbcStoragePlugin.listDatasetHandles()` |
| BASE-03 | 30-02 | Plugin maps standard JDBC types to Arrow/Dremio types | SATISFIED | `JdbcSchemaFetcher.mapJdbcType()` uses `JdbcToArrowUtils.getArrowTypeFromJdbcType(JdbcFieldInfo)` for all standard types; `JdbcRecordReader.writeValue()` handles BOOLEAN, INT, BIGINT, FLOAT4, FLOAT8, VARCHAR, VARBINARY, DECIMAL, DATE, TIME, TIMESTAMP |
| BASE-04 | 30-02 | Plugin converts JDBC `ResultSet` rows into Arrow `RecordBatch`es | SATISFIED | `JdbcRecordReader.setup()` registers Arrow vectors; `next()` reads up to `numRowsPerBatch` rows, writes to vectors via type-dispatched `writeValue()`; `setValueCount()` finalizes each batch |
| BASE-05 | 30-03 | Plugin pushes down WHERE filters to source SQL | SATISFIED | `JdbcPushFilterIntoScan` converts `FilterPrel` → `JdbcScanPrel.cloneWithFilter()`; `RexToSqlString` handles 11 operators; `SqlBuilder.buildSql()` appends `WHERE` clause |
| BASE-06 | 30-03 | Plugin pushes down column projection (SELECT specific columns) to source SQL | SATISFIED | `JdbcPushProjectIntoScan` extracts `RexInputRef` projections → `JdbcScanPrel.cloneWithProject()`; `SqlBuilder.buildSql()` emits `SELECT "col1","col2"` vs `SELECT *` |
| BASE-07 | 30-03 | Plugin pushes down LIMIT to source SQL | SATISFIED | `JdbcPushLimitIntoScan` reads `LimitPrel.getFetch()` → `JdbcScanPrel.cloneWithLimit()`; offset guard prevents incorrect pushdown; `SqlBuilder.buildSql()` appends `LIMIT N` |
| BASE-08 | 30-01 | Plugin reports source health status (good/warn/error) via periodic validation query | SATISFIED | `JdbcStoragePlugin.getState()` executes `conf.validationQuery` via try-with-resources; returns `SourceState.GOOD` or `SourceState.badState(message)` |

All 8 requirements (BASE-01 through BASE-08) are SATISFIED with direct code evidence.

---

### Anti-Patterns Found

No anti-patterns found. The grep scan over all source files in `plugins/jdbc-base/src/main/java` returned zero matches for: `TODO`, `FIXME`, `PLACEHOLDER`, `placeholder`, `coming soon`. No stubs returning `null`, `{}`, `[]`, or console-log-only handlers were found. All methods have substantive implementations.

---

### Human Verification Required

The following items require runtime confirmation and cannot be verified statically:

#### 1. End-to-End Connection Pool Against a Live Database

**Test:** Start a Dremio node, create a source backed by `BaseJdbcConf` subclass (e.g. `PostgresConf`), observe `getState()` returns GOOD.
**Expected:** Dremio UI shows source as healthy; connection pool connects to the database.
**Why human:** Requires a running JDBC-accessible database and Dremio node; pool initialization (`HikariDataSource`) fails at runtime if driver or URL is wrong.

#### 2. Arrow Type Mapping Roundtrip

**Test:** Query a table containing BOOLEAN, DECIMAL, DATE, TIMESTAMP columns through the JDBC plugin.
**Expected:** Dremio returns correct values with no type errors or nulls for valid data.
**Why human:** `JdbcToArrowUtils.getArrowTypeFromJdbcType()` may return null for some JDBC driver type codes not covered by the Arrow adapter; the fallback-to-VARCHAR path would hide type mismatches silently.

#### 3. Filter Pushdown SQL Generation

**Test:** Execute `SELECT * FROM source.schema.table WHERE age > 18 AND name LIKE 'A%'` through Dremio.
**Expected:** The query plan shows `JdbcScanPrel[where=("age" > 18 AND ("name" LIKE 'A%'))]`; the SQL sent to the JDBC source contains the WHERE clause.
**Why human:** Calcite `RexLiteral` value extraction (especially for DATE/TIME literals and the `BigDecimal` cast) may behave differently depending on the Calcite version bundled with this Dremio build.

#### 4. LIMIT Pushdown with Parallelization

**Test:** Execute `SELECT * FROM source.schema.table LIMIT 100`.
**Expected:** `JdbcGroupScan.getMaxParallelizationWidth()` returns 1; the SQL sent to source contains `LIMIT 100`; exactly 100 rows returned.
**Why human:** The planner's interaction with `LimitPrel` offset detection is sensitive to planner rules ordering; needs runtime observation.

---

### Gaps Summary

No gaps. All 17 observable truths verified, all 19 artifacts substantive and wired, all 13 key links confirmed present in code, all 8 requirements (BASE-01 through BASE-08) satisfied.

**Notable deviation from plan that was correctly self-fixed:** Plan 03 omitted `JdbcScanPrule` (the `JdbcScanDrel → JdbcScanPrel` conversion rule in the PHYSICAL phase). The implementer identified this gap during execution and added the rule — it is present and registered. The full pipeline `ScanCrel → JdbcScanDrel → JdbcScanPrel → JdbcGroupScan → JdbcSubScan → JdbcRecordReader` is complete.

**All six deliverables of the phase goal are present:**
- Connection pooling: `JdbcConnectionPool` + `BaseJdbcConf`
- Schema discovery: `JdbcSchemaFetcher`
- Type mapping: `JdbcSchemaFetcher.mapJdbcType()` + `JdbcRecordReader.writeValue()`
- Arrow batch conversion: `JdbcRecordReader`
- Pushdown: `JdbcPushFilterIntoScan`, `JdbcPushProjectIntoScan`, `JdbcPushLimitIntoScan`, `SqlBuilder`
- Health reporting: `JdbcStoragePlugin.getState()`

---

*Verified: 2026-03-12T22:10:00Z*
*Verifier: Claude (gsd-verifier)*
