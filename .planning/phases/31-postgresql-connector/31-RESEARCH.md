# Phase 31: PostgreSQL Connector - Research

**Researched:** 2026-03-13
**Domain:** PostgreSQL JDBC driver, Dremio plugin wiring, PG type mapping, TestContainers, Trino PG connector reference
**Confidence:** HIGH (all findings verified against live codebase and Trino GitHub source)

---

## 1. Summary of Findings

Phase 31 creates a new Maven module `plugins/jdbc-postgresql/` (`dremio-plugin-jdbc-postgresql`) that extends the Phase 30 base framework to connect Dremio to PostgreSQL databases. The module needs:

1. **`PostgresConf`** -- A `BaseJdbcConf` subclass with `@SourceType(value = "POSTGRES_DB", ...)` carrying PG-specific fields (hostname, port, databaseName, username, password, fetchSize, useSsl, encryptionValidationMode, maxIdleConns, idleTimeSec, queryTimeoutSec).
2. **`PostgresSchemaFetcher`** -- A `JdbcSchemaFetcher` subclass overriding `mapJdbcType()` and `isSystemSchema()` for PG-specific type mapping and schema filtering.
3. **`PostgresRecordReader`** -- A `JdbcRecordReader` subclass overriding `writeValue()` for PG-specific types (UUID, JSONB, arrays, etc.).
4. **`postgres-layout.json`** -- UI form definition for the source creation wizard.
5. **`POSTGRES_DB.svg`** -- Source icon (already exists at `dac/ui-lib/icons/dremio/sources/POSTGRES.svg`; needs renaming or aliasing).
6. **TestContainers integration tests** against `postgres:16-alpine`.

Key decision: The PostgreSQL JDBC driver (`org.postgresql:postgresql:42.7.3`) is already managed in the root POM. TestContainers 1.20.4 BOM is already managed. No new root POM version properties are needed.

Critical gap in Phase 30 base: `JdbcConnectionPool` does not currently set username/password on the HikariCP config. Either (a) add abstract `getUsername()`/`getPassword()` methods to `BaseJdbcConf`, or (b) add a `configurePool(HikariConfig)` hook method that concrete subclasses override, or (c) extend `JdbcConnectionPool` to accept a `Properties` or `Map<String,String>` for additional HikariCP datasource properties. Option (b) is cleanest and most extensible (allows SSL properties too).

---

## 2. Trino PostgreSQL Type Mapping Analysis

Trino's `PostgreSqlClient.java` handles PostgreSQL types in a two-stage dispatch:

### Stage 1: String-based type name matching (before JDBC Types switch)
PG reports some types with special type names that don't map to standard `java.sql.Types` constants:

| PG Type Name | Trino Mapping | Dremio Mapping (Recommended) |
|-------------|---------------|------------------------------|
| `"money"` | VARCHAR (read only; write unsupported) | VARCHAR -- PG JDBC driver returns currency-formatted string; amounts >= 1000 break `getDouble()` |
| `"uuid"` | Trino UUID type (128-bit) | VARCHAR(36) -- Dremio has no native UUID; read via `rs.getString()` |
| `"jsonb"` | Trino JSON type | VARCHAR -- read via `rs.getString()` |
| `"json"` | Trino JSON type | VARCHAR -- read via `rs.getString()` |
| `"timestamptz"` | TIMESTAMP WITH TIME ZONE | TIMESTAMP -- Dremio Arrow `TimeStampMilliVector` (drop TZ; PG doesn't store zone info anyway) |
| `"hstore"` | MAP(VARCHAR, VARCHAR) | VARCHAR -- serialize as string for v1.5 |
| `"vector"` (pgvector) | custom float array | VARCHAR for v1.5 (pgvector support deferred) |

### Stage 2: JDBC Types constant matching

| JDBC Type | PG Source Type | Trino Mapping | Dremio Mapping |
|-----------|---------------|---------------|----------------|
| `Types.BIT` | BOOLEAN | BOOLEAN | BOOLEAN (`BitVector`) |
| `Types.SMALLINT` | SMALLINT, SMALLSERIAL | SMALLINT | INT (`IntVector`) -- Dremio has no SMALLINT vector |
| `Types.INTEGER` | INTEGER, SERIAL | INTEGER | INT (`IntVector`) |
| `Types.BIGINT` | BIGINT, BIGSERIAL | BIGINT | BIGINT (`BigIntVector`) |
| `Types.REAL` | REAL | REAL | FLOAT (`Float4Vector`) |
| `Types.DOUBLE` | DOUBLE PRECISION | DOUBLE | DOUBLE (`Float8Vector`) |
| `Types.NUMERIC` | NUMERIC/DECIMAL | DECIMAL(p,s) | DECIMAL (`DecimalVector`) |
| `Types.CHAR` | CHAR(n) | CHAR(n) | VARCHAR (`VarCharVector`) |
| `Types.VARCHAR` | VARCHAR(n), TEXT | VARCHAR(n) | VARCHAR (`VarCharVector`) |
| `Types.BINARY` | BYTEA | VARBINARY | VARBINARY (`VarBinaryVector`) |
| `Types.DATE` | DATE | DATE | DATE (`DateMilliVector`) |
| `Types.TIME` | TIME | TIME(p) | TIME (`TimeMilliVector`) |
| `Types.TIMESTAMP` | TIMESTAMP | TIMESTAMP(p) | TIMESTAMP (`TimeStampMilliVector`) |
| `Types.ARRAY` | int[], text[], etc. | ARRAY(element_type) | Deferred to future -- fall back to VARCHAR for v1.5 |
| `Types.OTHER` | UUID, JSONB, JSON, etc. | (handled by name) | (handled by name -- see Stage 1) |

### Key Observations from Trino

1. **UUID is `Types.OTHER`**: PG JDBC driver reports UUID as `Types.OTHER` (1111). Must check `typeName` string. Read via `rs.getString(col)` or `rs.getObject(col, UUID.class)` then `.toString()`.

2. **JSONB is `Types.OTHER`**: Same -- check `typeName = "jsonb"` or `"json"`. Read via `rs.getString()`.

3. **SERIAL/BIGSERIAL**: PG reports `SERIAL` as `Types.INTEGER` and `BIGSERIAL` as `Types.BIGINT` in JDBC metadata. No special handling needed -- they are just auto-increment integers.

4. **TEXT vs VARCHAR**: PG JDBC driver reports `TEXT` as `Types.VARCHAR` with `typeName = "text"`. No special handling needed -- both map to VARCHAR.

5. **BYTEA**: PG JDBC driver reports `Types.BINARY` (-2). Read via `rs.getBytes(col)`.

6. **INTERVAL**: PG JDBC driver reports `Types.OTHER` with `typeName = "interval"`. Trino does NOT have explicit interval handling. Map to VARCHAR; read via `rs.getString()`.

7. **MONEY**: PG JDBC driver reports `Types.DOUBLE` but `getString()` returns currency-formatted text like `$1,234.56`. Trino maps to VARCHAR (read-only). Do the same.

8. **Arrays**: PG JDBC driver reports `Types.ARRAY` (2003). Trino uses `java.sql.Array` API with recursive element extraction. For v1.5, we map arrays to VARCHAR via `rs.getString()` which returns PG array literal format like `{1,2,3}`. Full LIST support is deferred.

9. **Unconstrained NUMERIC**: PG `NUMERIC` without precision/scale reports `COLUMN_SIZE = 0` and `DECIMAL_DIGITS = 0`. Map to `DOUBLE` (like Trino does for overflow mode) or `VARCHAR` to avoid precision loss.

### Recommended `mapJdbcType()` Override for PostgresSchemaFetcher

```java
@Override
protected ArrowType mapJdbcType(int jdbcType, String typeName, int precision, int scale) {
    // Stage 1: PG-specific type name matching
    String lower = typeName != null ? typeName.toLowerCase() : "";
    switch (lower) {
        case "uuid":     return new ArrowType.Utf8();           // UUID -> VARCHAR
        case "jsonb":    return new ArrowType.Utf8();           // JSONB -> VARCHAR
        case "json":     return new ArrowType.Utf8();           // JSON -> VARCHAR
        case "money":    return new ArrowType.Utf8();           // MONEY -> VARCHAR
        case "interval": return new ArrowType.Utf8();           // INTERVAL -> VARCHAR
        case "cidr":     return new ArrowType.Utf8();           // CIDR -> VARCHAR
        case "inet":     return new ArrowType.Utf8();           // INET -> VARCHAR
        case "macaddr":  return new ArrowType.Utf8();           // MACADDR -> VARCHAR
        case "macaddr8": return new ArrowType.Utf8();           // MACADDR8 -> VARCHAR
        case "hstore":   return new ArrowType.Utf8();           // hstore -> VARCHAR
        case "tsvector": return new ArrowType.Utf8();           // full-text search -> VARCHAR
        case "tsquery":  return new ArrowType.Utf8();           // full-text search -> VARCHAR
    }
    // Array types (type names start with underscore in PG: _int4, _text, etc.)
    if (lower.startsWith("_") || jdbcType == java.sql.Types.ARRAY) {
        return new ArrowType.Utf8();  // Arrays -> VARCHAR for v1.5
    }
    // Stage 2: Delegate to base for standard JDBC types
    return super.mapJdbcType(jdbcType, typeName, precision, scale);
}
```

---

## 3. Trino Test Suite Patterns (TestContainers, Test Structure)

### TestContainers Setup Pattern

Trino uses a shared `TestingPostgreSqlServer` helper class:

```java
public class TestingPostgreSqlServer implements Closeable {
    static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("postgres:12");
    // Configures:
    //   - 3 startup attempts
    //   - Database name: "tpch"
    //   - Username: "test", Password: "test"
    //   - Logging: log_destination=stderr, log_statement=all
}
```

Container is created once per test class via `closeAfterClass()`:
```java
@Override
protected QueryRunner createQueryRunner() throws Exception {
    postgreSqlServer = closeAfterClass(new TestingPostgreSqlServer(postgreSqlImage));
    return PostgreSqlQueryRunner.builder(postgreSqlServer).build();
}
```

### Test Hierarchy

```
AbstractTestQueryFramework
  -> BaseJdbcConnectorTest              // generic JDBC connector tests
      -> TestPostgreSqlConnectorTest    // PG-specific connector tests (pushdown, schema)
  -> BasePostgreSqlTypeMappingTest      // PG type mapping tests
      -> TestPostgreSqlTypeMapping      // concrete type mapping with default image
```

### Type Roundtrip Test Pattern

Trino uses `SqlDataTypeTest.create()` with a fluent builder:
```java
SqlDataTypeTest.create()
    .addRoundTrip("uuid", "'12151fd2-7586-11e9-8f9e-2a86e4085a59'::uuid",
        UUID_TYPE, "UUID '12151fd2-7586-11e9-8f9e-2a86e4085a59'")
    .execute(getQueryRunner(), postgresCreateAndInsert("test_uuid"));
```

Three execution modes:
1. `postgresCreateAndInsert()` -- create table in PG, insert via PG, read via Trino
2. `trinoCreateAsSelect()` -- create table via Trino CTAS, read via Trino
3. `trinoCreateAndInsert()` -- create table via Trino, insert via Trino, read via Trino

### Pushdown Verification Pattern

```java
assertThat(query("SELECT * FROM table WHERE col = 'value'"))
    .matches("VALUES ...")
    .isFullyPushedDown();

assertThat(query("SELECT * FROM table WHERE col LIKE '%value%'"))
    .matches("VALUES ...")
    .isNotFullyPushedDown(FilterNode.class);
```

### Recommended Dremio Test Structure

For Dremio, we cannot use Trino's `QueryRunner` but we can model the same patterns:

```
plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/
  TestPostgresTypeMapping.java        -- type roundtrip tests
  TestPostgresSchemaFetcher.java      -- schema discovery unit tests
  TestPostgresSchemaDiscovery.java    -- integration: list schemas/tables/columns
  TestPostgresPushdown.java           -- integration: filter/project/limit pushdown
  PostgresTestContainer.java          -- shared TestContainers helper
```

The `PostgresTestContainer` helper should:
- Use `@Container` with `PostgreSQLContainer<>("postgres:16-alpine")`
- Expose `getJdbcUrl()`, `getUsername()`, `getPassword()`
- Pre-create test schemas and tables via raw JDBC in `@BeforeAll`

---

## 4. PostgreSQL JDBC Driver Quirks and Connection Handling

### Driver Details
- **GroupId/ArtifactId:** `org.postgresql:postgresql`
- **Version in root POM:** `42.7.3` (declared at `<postgresql.version>`)
- **Driver class:** `org.postgresql.Driver` (auto-discovered via SPI; no explicit `Class.forName` needed)
- **JDBC URL format:** `jdbc:postgresql://host:port/database`

### Cursor-Based Fetching (Critical for Large Tables)
PG JDBC driver requires **both** conditions for server-side cursors:
1. `connection.setAutoCommit(false)` -- cursor mode only works inside a transaction
2. `statement.setFetchSize(N)` where N > 0 -- default fetchSize=0 means "fetch all rows"

Without both, the driver loads the entire ResultSet into client memory.

Trino implements this in `getPreparedStatement()`:
```java
connection.setAutoCommit(false);
statement.setFetchSize(max(100_000 / columnCount, 1_000));
```

**For Dremio:** The `JdbcRecordReader.setup()` already calls `stmt.setFetchSize(numRowsPerBatch)` (base class), but does NOT set `autoCommit=false`. The PostgreSQL `RecordReader` subclass must set `conn.setAutoCommit(false)` in `setup()` before executing the query. This is a PG-specific quirk.

### SSL/TLS Configuration
PG JDBC driver uses connection URL parameters or `Properties` for SSL:

| Property | Values | Description |
|----------|--------|-------------|
| `ssl` | `true`/`false` | Enable SSL |
| `sslmode` | `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full` | SSL validation level |
| `sslcert` | path | Client certificate file (PEM X509v3) |
| `sslkey` | path | Client key file (PKCS-8 DER or PKCS-12) |
| `sslrootcert` | path | Root CA certificate for server verification |

**Mapping to Dremio `EncryptionValidationMode`:**
- `CERTIFICATE_AND_HOSTNAME_VALIDATION` -> `sslmode=verify-full`
- `CERTIFICATE_ONLY_VALIDATION` -> `sslmode=verify-ca`
- `NO_VALIDATION` -> `sslmode=require` (encrypted but no cert check)
- SSL disabled -> no `ssl` param (or `sslmode=disable`)

### Connection URL Assembly
```java
public String jdbcUrl() {
    StringBuilder url = new StringBuilder("jdbc:postgresql://");
    url.append(hostname).append(":").append(port).append("/").append(databaseName);
    if (useSsl) {
        url.append("?ssl=true&sslmode=").append(sslModeFromValidation(encryptionValidationMode));
    }
    return url.toString();
}
```

### Other Driver Quirks
1. **`Types.BIT` for BOOLEAN**: PG reports `boolean` as `Types.BIT` (-7), not `Types.BOOLEAN` (16). Arrow's `JdbcToArrowUtils` handles this correctly (maps BIT to BooleanType), so no override needed in the base `mapJdbcType()`.
2. **MONEY as Types.DOUBLE**: PG reports `money` as `Types.DOUBLE`, but `getDouble()` fails for amounts >= 1000 because the driver returns formatted strings like `$1,234.56`. Must check typeName and use `getString()`.
3. **Array type names**: PG prefixes array element type names with underscore: `_int4`, `_text`, `_bool`, `_float8`. The JDBC type is `Types.ARRAY` (2003).
4. **Statement timeout**: Set via `options` URL parameter (`-c statement_timeout=Nms`) or via `SET statement_timeout TO N` after connection. Can also pass as `Properties` entry: `options=-c statement_timeout=5000`.

---

## 5. Dremio Source Plugin Patterns (Local Codebase Analysis)

### @SourceType Annotation Pattern

All Dremio source plugins follow this pattern on their `ConnectionConf` subclass:
```java
@SourceType(value = "ELASTIC", label = "Elasticsearch", uiConfig = "elastic-storage-layout.json")
public class ElasticStoragePluginConfig extends BaseElasticStoragePluginConfig<...> { ... }
```

For PostgreSQL:
```java
@SourceType(value = "POSTGRES_DB", label = "PostgreSQL", uiConfig = "postgres-layout.json")
public class PostgresConf extends BaseJdbcConf<PostgresConf, JdbcStoragePlugin> { ... }
```

### Field Annotations (Protostuff @Tag + Dremio metadata)

| Annotation | Purpose |
|------------|---------|
| `@Tag(N)` | Protostuff field number (immutable once deployed) |
| `@Secret` | Marks password/credential fields for secure storage |
| `@NotMetadataImpacting` | Changing this field doesn't trigger a full metadata refresh |
| `@DisplayMetadata(label = "...")` | UI label text for the field |
| `SecretRef` | Type for password fields (uses Dremio's credential service) |

Example from `ElasticStoragePluginConfig`:
```java
@Tag(3) @Secret public SecretRef password;
@Tag(7) @NotMetadataImpacting @DisplayMetadata(label = "Encrypt connection") public boolean sslEnabled = false;
```

### Tag Numbering Strategy

`BaseJdbcConf` uses tags 1-3 for pool fields. PostgresConf must start at a higher number. Strategy:
- Tags 1-3: Reserved by `BaseJdbcConf` (poolSize, idleTimeoutMs, validationQuery)
- Tags 10-19: Connection fields (hostname, port, databaseName)
- Tags 20-29: Authentication fields (username, password)
- Tags 30-39: SSL/TLS fields (useSsl, encryptionValidationMode)
- Tags 40-49: Performance fields (fetchSize, queryTimeoutSec)

### newPlugin() Method Pattern

`ConnectionConf.newPlugin()` creates the StoragePlugin instance:
```java
@Override
public JdbcStoragePlugin newPlugin(PluginSabotContext ctx, String name, Provider<StoragePluginId> pluginIdProvider) {
    return new JdbcStoragePlugin(this, name);
}
```

### UI Layout JSON Pattern

Source form layout is defined in a JSON resource file. Pattern from existing plugins:
```json
{
  "sourceType": "POSTGRES_DB",
  "metadataRefresh": { "datasetDiscovery": true },
  "form": {
    "tabs": [
      {
        "name": "General",
        "isGeneral": true,
        "sections": [
          { "name": "Connection", "elements": [ ... ] },
          { "name": "Authentication", "elements": [ ... ] }
        ]
      },
      {
        "name": "Advanced Options",
        "sections": [ ... ]
      }
    ]
  }
}
```

### Source Icon

Icons live in `dac/ui-lib/icons/dremio/sources/` and `dac/ui-lib/icons/dremio-dark/sources/`. A `POSTGRES.svg` already exists. The icon filename must match the `@SourceType.value` or the source type mapping. Since we're using `POSTGRES_DB` as the source type, we need `POSTGRES_DB.svg` -- either copy the existing `POSTGRES.svg` or check how the UI resolves icon names.

### Module Registration

1. **`plugins/pom.xml`**: Add `<module>jdbc-postgresql</module>`
2. **`dac/daemon/pom.xml`**: Add dependency `<artifactId>dremio-plugin-jdbc-postgresql</artifactId>`
3. **`dac/backend/pom.xml`**: May need the dependency too (check if other plugins are here)
4. **`sabot-module.conf`**: Register `com.dremio.plugins.jdbc.postgresql` for classpath scanning

---

## 6. Base Class Extension Points (What Phase 31 Needs to Override)

### BaseJdbcConf (extends ConnectionConf)

**Must provide:**
- `@SourceType(value = "POSTGRES_DB", label = "PostgreSQL", uiConfig = "postgres-layout.json")`
- `jdbcUrl()` -- assemble `jdbc:postgresql://host:port/db` with optional SSL params
- `driverClassName()` -- return `null` (PG driver auto-discovers via SPI) or `"org.postgresql.Driver"` for explicitness
- `newPlugin()` -- return `new JdbcStoragePlugin(this, name)` (or a PG-specific subclass if needed)

**New fields needed:**
- `hostname` (String, required)
- `port` (int, default 5432)
- `databaseName` (String, required)
- `username` (String)
- `password` (SecretRef, @Secret)
- `fetchSize` (int, default 4096)
- `useSsl` (boolean, default false)
- `encryptionValidationMode` (EncryptionValidationMode)
- `queryTimeoutSec` (int, default 0 = no timeout)

### JdbcConnectionPool (gap: no username/password support)

Current `JdbcConnectionPool` doesn't configure username/password on HikariConfig. Options:

**Option A (minimal, recommended for v1.5):** Add `getUsername()`, `getPassword()`, and `getConnectionProperties()` abstract/default methods to `BaseJdbcConf`. Modify `JdbcConnectionPool` to call them.

**Option B (extensible):** Add a `protected void configurePool(HikariConfig config)` hook to `BaseJdbcConf` that subclasses override. `JdbcConnectionPool` calls this hook after setting base properties.

**Option C (override pool construction):** Override `JdbcStoragePlugin.start()` in a PG subclass to construct a custom pool. Least clean -- requires a `PostgresStoragePlugin` subclass.

**Recommendation:** Option A -- add three methods to `BaseJdbcConf`:
```java
public String getUsername() { return null; }
public String getPassword() { return null; }
public java.util.Properties getConnectionProperties() { return new java.util.Properties(); }
```
Modify `JdbcConnectionPool` to call these. PostgresConf overrides them.

### JdbcSchemaFetcher (overridable methods)

**`mapJdbcType(int jdbcType, String typeName, int precision, int scale)`** -- Override to handle PG-specific types (UUID, JSONB, JSON, MONEY, INTERVAL, arrays, CIDR, INET, MACADDR). See Section 2 for the recommended implementation.

**`isSystemSchema(String schemaName)`** -- Override to add PG-specific system schemas. The base already handles `pg_catalog`, `pg_toast`, `pg_temp_`, `information_schema`. May want to also exclude `pg_internal`.

### JdbcRecordReader (overridable methods)

**`writeValue(ValueVector vec, ResultSet rs, String colName, int index)`** -- Override to handle PG-specific type reading:
- UUID columns: `rs.getString(colName)` -- works for VarCharVector
- JSONB/JSON: `rs.getString(colName)` -- works for VarCharVector
- MONEY: `rs.getString(colName)` -- works for VarCharVector
- BYTEA: `rs.getBytes(colName)` -- already handled by base class VarBinaryVector branch
- INTERVAL: `rs.getString(colName)` -- works for VarCharVector
- Arrays: `rs.getString(colName)` returns PG literal `{1,2,3}` -- works for VarCharVector

Actually, since all PG-specific types map to VARCHAR or VARBINARY in the schema fetcher, the base `writeValue()` should handle them correctly. The PG-specific override may only be needed for setting `autoCommit(false)`.

**Setup override for cursor mode:**
```java
@Override
public void setup(OutputMutator output) {
    super.setup(output);
    // After super.setup() acquires connection and executes query,
    // we need autoCommit=false BEFORE the query executes.
    // This means we need to override the connection setup logic.
}
```

Problem: The base `setup()` acquires the connection and immediately executes the query. We need autoCommit set BEFORE `executeQuery()`. Two options:
1. Refactor base `setup()` to have a `configureConnection(Connection)` hook called after `pool.getConnection()` but before `stmt.executeQuery()`.
2. Override `setup()` entirely in the PG reader.

**Recommendation:** Add a `protected void configureConnection(Connection conn)` no-op hook in the base `JdbcRecordReader`, called between `pool.getConnection()` and `stmt.executeQuery()`. The PG subclass overrides it to set `conn.setAutoCommit(false)`.

### SqlBuilder (likely no override needed)

PostgreSQL uses standard SQL syntax: double-quoted identifiers, LIMIT clause. The base `SqlBuilder` already handles this correctly. No PG-specific override needed for v1.5.

---

## 7. Recommended Approach for Each Requirement

### PG-01: REST API Source Creation

**What:** User creates a `POSTGRES_DB` source via Dremio REST API (`POST /api/v3/source`).

**Implementation:**
1. Create `PostgresConf` extending `BaseJdbcConf` with `@SourceType(value = "POSTGRES_DB", ...)`.
2. Add all config fields with `@Tag` annotations, `@DisplayMetadata` labels, `@Secret` for password.
3. Implement `jdbcUrl()` to build `jdbc:postgresql://host:port/db` with optional SSL parameters.
4. Implement `newPlugin()` to return `new JdbcStoragePlugin(this, name)`.
5. Modify `BaseJdbcConf` (or `JdbcConnectionPool`) to support username/password/connection properties.
6. Add `sabot-module.conf` with classpath scanning for `com.dremio.plugins.jdbc.postgresql`.
7. Register module in `plugins/pom.xml` and `dac/daemon/pom.xml`.

**Config fields:**
```
hostname        @Tag(10) String, required
port            @Tag(11) int, default 5432
databaseName    @Tag(12) String, required
username        @Tag(20) String
password        @Tag(21) @Secret SecretRef
fetchSize       @Tag(40) int, default 4096
useSsl          @Tag(30) boolean, default false
encryptionValidationMode  @Tag(31) EncryptionValidationMode
maxIdleConns    -> maps to base poolSize @Tag(1)
idleTimeSec     -> maps to base idleTimeoutMs @Tag(2) (convert sec to ms)
queryTimeoutSec @Tag(41) int, default 0
```

Note: `maxIdleConns` and `idleTimeSec` overlap with the base class `poolSize` and `idleTimeoutMs`. Two approaches:
- Expose new PG-specific fields and map them internally (redundant but matches the spec requirement names)
- Reuse the base fields and document the mapping in the UI layout (cleaner)

**Recommendation:** Reuse the base fields. The requirement names (`maxIdleConns`, `idleTimeSec`) are just API field names. The `@Tag` mapping in `BaseJdbcConf` already provides `poolSize` and `idleTimeoutMs`. The REST API payload uses the Protostuff field names, so the REST API config JSON will use `poolSize` and `idleTimeoutMs`. The UI layout can label them as "Max Idle Connections" and "Idle Timeout (sec)".

### PG-02: SELECT from PostgreSQL Tables

**What:** User runs `SELECT * FROM postgres_source.schema.table` in Dremio SQL.

**Implementation:** Fully handled by Phase 30 base framework:
1. `JdbcStoragePlugin.listDatasetHandles()` discovers PG schemas and tables.
2. `JdbcSchemaFetcher.getTableSchema()` reads column metadata.
3. `JdbcScanPrel` builds `SELECT * FROM "schema"."table"`.
4. `JdbcRecordReader` executes the query and reads results.

Phase 31 additions:
1. `PostgresSchemaFetcher` overrides `mapJdbcType()` for PG-specific types.
2. `PostgresRecordReader` overrides connection setup for `autoCommit(false)` + `fetchSize`.
3. Wire the PG-specific schema fetcher and reader into the plugin lifecycle.

### PG-03: PostgreSQL Type Mapping

**What:** Specific PG types mapped to Dremio types per the requirement.

| PG Type | Requirement | JDBC Types Code | How to Read | Dremio Arrow Type |
|---------|-------------|-----------------|-------------|-------------------|
| TEXT | -> VARCHAR | `Types.VARCHAR` | `rs.getString()` | `Utf8` (VarCharVector) |
| BYTEA | -> VARBINARY | `Types.BINARY` | `rs.getBytes()` | `Binary` (VarBinaryVector) |
| UUID | -> VARCHAR(36) | `Types.OTHER` | `rs.getString()` | `Utf8` (VarCharVector) |
| JSONB | -> VARCHAR | `Types.OTHER` | `rs.getString()` | `Utf8` (VarCharVector) |
| JSON | -> VARCHAR | `Types.OTHER` | `rs.getString()` | `Utf8` (VarCharVector) |
| SERIAL | -> INT | `Types.INTEGER` | `rs.getInt()` | `Int(32,true)` (IntVector) |
| BIGSERIAL | -> BIGINT | `Types.BIGINT` | `rs.getLong()` | `Int(64,true)` (BigIntVector) |
| arrays | -> VARCHAR (v1.5) | `Types.ARRAY` | `rs.getString()` | `Utf8` (VarCharVector) |
| INTERVAL | -> VARCHAR | `Types.OTHER` | `rs.getString()` | `Utf8` (VarCharVector) |

Implementation: Override `mapJdbcType()` in `PostgresSchemaFetcher` (see Section 2). The base `writeValue()` in `JdbcRecordReader` already handles all these Arrow types. No `writeValue()` override needed for type mapping -- only for `autoCommit(false)`.

### PG-04: UI Wizard Form

**What:** JSON layout file `postgres-layout.json` for Dremio UI source creation wizard.

**Implementation:** Create `plugins/jdbc-postgresql/src/main/resources/postgres-layout.json`:

```json
{
  "sourceType": "POSTGRES_DB",
  "metadataRefresh": { "datasetDiscovery": true },
  "form": {
    "tabs": [
      {
        "name": "General",
        "isGeneral": true,
        "sections": [
          {
            "name": "Connection",
            "elements": [
              { "propName": "config.hostname", "validate": { "isRequired": true } },
              { "propName": "config.port", "size": "half", "validate": { "isNumber": true } },
              { "propName": "config.databaseName", "validate": { "isRequired": true } }
            ]
          },
          {
            "name": "Authentication",
            "elements": [
              { "propName": "config.username" },
              { "propName": "config.password" }
            ]
          }
        ]
      },
      {
        "name": "Advanced Options",
        "sections": [
          {
            "name": "Connection Pool",
            "elements": [
              { "propName": "config.poolSize", "size": "half", "validate": { "isNumber": true } },
              { "propName": "config.idleTimeoutMs", "size": "half", "validate": { "isNumber": true },
                "scale": "1:1000", "label": "Idle timeout (seconds)" }
            ]
          },
          {
            "name": "Performance",
            "elements": [
              { "propName": "config.fetchSize", "size": "half", "validate": { "isNumber": true } },
              { "propName": "config.queryTimeoutSec", "size": "half", "validate": { "isNumber": true } }
            ]
          },
          {
            "name": "Encryption",
            "elements": [
              { "propName": "config.useSsl" },
              { "propName": "config.encryptionValidationMode" }
            ]
          }
        ]
      }
    ]
  }
}
```

**Icon:** The source icon filename must match the source type. Either:
- Copy `POSTGRES.svg` to `POSTGRES_DB.svg` in both `dac/ui-lib/icons/dremio/sources/` and `dac/ui-lib/icons/dremio-dark/sources/`
- OR check if the UI has a fallback mapping mechanism (it might -- the existing `POSTGRES.svg` could already be mapped for `POSTGRES_DB`)

### PG-05: TestContainers Integration Tests

**What:** Comprehensive tests against `postgres:16-alpine` validating type roundtrips, schema discovery, filter pushdown, and projection pushdown.

**Implementation:**

#### Test Container Helper
```java
public class PostgresTestContainer {
    @Container
    static final PostgreSQLContainer<?> PG =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dremio_test")
            .withUsername("test")
            .withPassword("test");
}
```

#### Test Classes

**1. `TestPostgresTypeMapping`** (unit test -- no Dremio engine needed)
- Creates PG table with all types (BOOLEAN, INT, BIGINT, TEXT, VARCHAR, BYTEA, UUID, JSONB, JSON, SERIAL, BIGSERIAL, INTERVAL, MONEY, arrays, NUMERIC, DATE, TIME, TIMESTAMP, TIMESTAMPTZ)
- Inserts test data via raw JDBC
- Uses `PostgresSchemaFetcher.getTableSchema()` to verify Arrow schema mapping
- Uses `PostgresRecordReader` (or raw JDBC + type mapping) to verify value roundtrip

**2. `TestPostgresSchemaDiscovery`** (integration -- needs PG container)
- Tests `listSchemas()` returns expected schemas, excludes system schemas
- Tests `listTables()` returns tables and views
- Tests `getTableSchema()` returns correct Arrow field types
- Tests `tableExists()` for existing and non-existing tables

**3. `TestPostgresPushdown`** (integration -- needs PG container + Dremio or SQL verification)
- Tests filter pushdown: verify SQL contains WHERE clause
- Tests projection pushdown: verify SQL contains SELECT col1, col2
- Tests limit pushdown: verify SQL contains LIMIT N
- Approach: Since full Dremio engine tests are expensive, test the `SqlBuilder` output directly by constructing `JdbcScanPrel` nodes and verifying the generated SQL.

**4. `TestPostgresEndToEnd`** (full integration -- needs Dremio engine + PG container)
- Uses `BaseTestQuery` or similar to start a Dremio node
- Creates a POSTGRES_DB source via the plugin API
- Runs SELECT queries and verifies results
- This is the most comprehensive but heaviest test

**Test Data Values (following Trino patterns):**
- BOOLEAN: `true`, `false`, `NULL`
- INTEGER: `0`, `2147483647`, `-2147483648`, `NULL`
- BIGINT: `0`, `9223372036854775807`, `-9223372036854775808`
- TEXT: `'hello'`, `''`, Unicode `'Piękna łąka'`, emoji `'😂'`
- BYTEA: `E'\\x48656C6C6F'` (hex for "Hello"), empty `E'\\x'`
- UUID: `'12151fd2-7586-11e9-8f9e-2a86e4085a59'`
- JSONB: `'{"key": "value", "number": 42}'`
- NUMERIC: `'123.456'`, `'0.27182818284590452353602874713527'`
- DATE: `'2024-01-15'`, `'1970-01-01'`, `'0001-01-01'`
- TIMESTAMP: `'2024-01-15 10:30:00.123456'`
- INTERVAL: `'1 year 2 months 3 days'`
- MONEY: `'$1,234.56'`
- Array: `ARRAY[1, 2, 3]`, `ARRAY['a', 'b', 'c']`

---

## 8. Risks and Concerns

### Risk 1: JdbcConnectionPool Gap (username/password)
**Severity:** HIGH -- blocks PG-01 entirely.
**Details:** `JdbcConnectionPool` currently only sets `jdbcUrl`, `driverClassName`, `poolSize`, `idleTimeout`, `connectionTestQuery`. It has no support for username, password, or additional JDBC properties (needed for SSL).
**Mitigation:** Modify `BaseJdbcConf` and `JdbcConnectionPool` before implementing PostgresConf. This is a Phase 30 amendment that Phase 31 depends on.
**Approach:** Add `getUsername()`, `getPassword()`, `getConnectionProperties()` with no-op defaults to `BaseJdbcConf`. Update `JdbcConnectionPool` constructor to call them on HikariConfig.

### Risk 2: Cursor Fetching Requires autoCommit=false
**Severity:** MEDIUM -- without this, large table queries will OOM.
**Details:** PG JDBC driver requires `autoCommit=false` for cursor-based fetching. The base `JdbcRecordReader.setup()` acquires a connection and immediately executes the query without setting autoCommit.
**Mitigation:** Add a `configureConnection(Connection)` hook in the base `JdbcRecordReader` (Phase 30 amendment). PG reader overrides it. Alternatively, override `setup()` entirely in the PG reader.
**Impact if ignored:** All rows loaded into client memory; OOM for tables > a few hundred MB.

### Risk 3: Source Icon Filename Mismatch
**Severity:** LOW -- cosmetic only.
**Details:** Existing `POSTGRES.svg` in `dac/ui-lib/icons/` may not match the `POSTGRES_DB` source type name. UI may show a generic icon.
**Mitigation:** Copy SVG to `POSTGRES_DB.svg` or verify the UI's icon resolution logic handles the mismatch.

### Risk 4: Protostuff Tag Conflict Between Base and Subclass
**Severity:** HIGH if mishandled -- corrupts stored configurations.
**Details:** `BaseJdbcConf` uses `@Tag(1)`, `@Tag(2)`, `@Tag(3)`. If `PostgresConf` reuses any of these tag numbers, Protostuff deserialization will corrupt data silently.
**Mitigation:** PostgresConf must start its tags at 10 or higher. Document the tag allocation strategy.

### Risk 5: Unconstrained NUMERIC Handling
**Severity:** LOW -- edge case.
**Details:** PG `NUMERIC` without precision/scale reports `COLUMN_SIZE=0`. Arrow's `JdbcToArrowUtils` may return null or an unexpected type for this case. Trino handles it with special session-configurable overflow modes.
**Mitigation:** In `PostgresSchemaFetcher.mapJdbcType()`, check for `Types.NUMERIC` with `precision=0` and map to `DOUBLE` (reasonable default) or `VARCHAR` (lossless).

### Risk 6: TestContainers Module Dependency
**Severity:** LOW -- just needs a POM declaration.
**Details:** The `testcontainers-bom` is managed in the root POM, but the `org.testcontainers:postgresql` module is not explicitly declared anywhere yet. It needs to be added as a test-scoped dependency in the PG plugin's POM.
**Mitigation:** Add to `plugins/jdbc-postgresql/pom.xml`:
```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

### Risk 7: `dac/daemon/pom.xml` Community Edition Profile
**Severity:** MEDIUM -- may block source discovery at runtime.
**Details:** Existing plugins are registered in `dac/daemon/pom.xml` as dependencies. There's also a `dremio-ce-jdbc-plugin` reference in the community profile (`<profile><id>oss</id>`) that already exists but has no corresponding source module. If the PG plugin dependency is added to the wrong section, it may not be loaded.
**Mitigation:** Add the PG plugin dependency in the main `<dependencies>` section (not inside a profile), alongside other plugins like `dremio-nas-plugin` and `dremio-elasticsearch-plugin`.

### Risk 8: Classpath Scanning Package Overlap
**Severity:** LOW.
**Details:** The base module registers `com.dremio.plugins.jdbc`. The PG module should register `com.dremio.plugins.jdbc.postgresql`. If the PG module uses a package under `com.dremio.plugins.jdbc`, the base module's scanning may already pick it up -- but it's better to be explicit with a separate `sabot-module.conf`.
**Mitigation:** PG module uses package `com.dremio.plugins.jdbc.postgresql` and its own `sabot-module.conf`.

---

## Appendix: Module File Tree (Proposed)

```
plugins/jdbc-postgresql/
  pom.xml
  src/
    main/
      java/com/dremio/plugins/jdbc/postgresql/
        PostgresConf.java             -- @SourceType("POSTGRES_DB"), extends BaseJdbcConf
        PostgresSchemaFetcher.java    -- mapJdbcType() overrides for PG types
        PostgresRecordReader.java     -- configureConnection() for autoCommit=false
      resources/
        postgres-layout.json          -- UI form definition
        sabot-module.conf             -- classpath scanning for com.dremio.plugins.jdbc.postgresql
    test/
      java/com/dremio/plugins/jdbc/postgresql/
        PostgresTestContainer.java    -- Shared TestContainers helper
        TestPostgresTypeMapping.java  -- Type mapping roundtrip tests
        TestPostgresSchemaDiscovery.java -- Schema listing tests
        TestPostgresPushdown.java     -- Pushdown SQL verification
```

## Appendix: Required Phase 30 Amendments

Before Phase 31 implementation, the following changes to `plugins/jdbc-base/` are needed:

1. **`BaseJdbcConf`**: Add default methods `getUsername()`, `getPassword()`, `getConnectionProperties()` returning null/empty.
2. **`JdbcConnectionPool`**: Update constructor to set `hikariConfig.setUsername()`, `hikariConfig.setPassword()`, and iterate `getConnectionProperties()` calling `hikariConfig.addDataSourceProperty()`.
3. **`JdbcRecordReader`**: Add `protected void configureConnection(Connection conn) throws SQLException {}` hook, called between `pool.getConnection()` and `stmt.executeQuery()` in `setup()`.

These amendments are backward-compatible (default methods return null/empty, hook is no-op) and don't affect the Phase 30 verification.

---

*Researched: 2026-03-13*
*Sources: Live codebase (dremio-oss), Trino GitHub (trinodb/trino master), PostgreSQL JDBC documentation (jdbc.postgresql.org)*
