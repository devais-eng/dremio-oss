# Phase 32: Oracle Connector - Research

**Researched:** 2026-03-13
**Domain:** Oracle JDBC driver, Oracle type mapping, TestContainers oracle-xe, Trino Oracle connector reference, Dremio plugin wiring
**Confidence:** HIGH (verified against Trino source code, Oracle JDBC docs, TestContainers source, and local codebase)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| ORA-01 | User can create an ORACLE_DB source via REST API with config: hostname, port, serviceName, username, password, fetchSize, useSsl, encryptionValidationMode, maxIdleConns, idleTimeSec, queryTimeoutSec | Section 6 (OracleConf pattern), Section 5 (JDBC URL format), Section 4 (SSL config) |
| ORA-02 | User can SELECT from Oracle tables through Dremio SQL with correct results | Section 3 (OracleRecordReader), Section 7 (Oracle DATE quirk), base framework handles query execution |
| ORA-03 | Oracle-specific types mapped correctly: NUMBER(p,s)->DECIMAL, NUMBER(no precision)->FLOAT8, VARCHAR2->VARCHAR, NVARCHAR2->VARCHAR, CLOB/NCLOB->VARCHAR, BLOB->VARBINARY, RAW->VARBINARY, DATE->TIMESTAMP, BINARY_FLOAT->FLOAT4, BINARY_DOUBLE->FLOAT8, TIMESTAMP WITH TIME ZONE->TIMESTAMP | Section 2 (complete type mapping analysis), Section 7 (Oracle DATE quirk) |
| ORA-04 | User can create an ORACLE_DB source via Dremio UI wizard with JSON layout form | Section 6 (oracle-layout.json pattern), Section 8 (source icon) |
| ORA-05 | Testcontainers integration tests validate type roundtrips, schema discovery, filter pushdown, and projection pushdown against gvenzl/oracle-xe:21-slim | Section 9 (TestContainers setup), Section 10 (Trino test patterns), Section 11 (test structure) |
</phase_requirements>

## 1. Summary

Phase 32 creates a new Maven module `plugins/jdbc-oracle/` (`dremio-plugin-jdbc-oracle`) that extends the Phase 30 base JDBC framework to connect Dremio to Oracle databases. The module follows the exact same structural pattern as the Phase 31 PostgreSQL connector, with Oracle-specific overrides.

The module needs:
1. **`OracleConf`** -- A `BaseJdbcConf` subclass with `@SourceType(value = "ORACLE_DB", ...)` carrying Oracle-specific fields (hostname, port, serviceName instead of databaseName, username, password, fetchSize, useSsl, encryptionValidationMode, queryTimeoutSec).
2. **`OracleSchemaFetcher`** -- A `JdbcSchemaFetcher` subclass overriding `mapJdbcType()` for Oracle-specific type mapping (NUMBER precision handling, DATE->TIMESTAMP, CLOB/NCLOB->VARCHAR, etc.) and `isSystemSchema()` to filter Oracle system schemas (SYS, SYSTEM, MDSYS, XDB, CTXSYS, etc.).
3. **`OracleRecordReader`** -- A `JdbcRecordReader` subclass overriding `writeValue()` to handle Oracle DATE (which includes time) via `rs.getTimestamp()` instead of `rs.getDate()`.
4. **`OracleSqlBuilder`** -- A `SqlBuilder` subclass overriding the LIMIT clause to use Oracle 12c+ `FETCH FIRST N ROWS ONLY` syntax instead of `LIMIT N`.
5. **`oracle-layout.json`** -- UI form definition for the source creation wizard.
6. **`ORACLE_DB.svg`** -- Source icon (copy from existing `ORACLE.svg`).
7. **TestContainers integration tests** against `gvenzl/oracle-xe:21-slim`.

**Primary recommendation:** Follow the PostgreSQL connector pattern exactly. The main Oracle-specific differences are: (a) NUMBER type precision handling, (b) Oracle DATE includes time, (c) Oracle uses `FETCH FIRST N ROWS ONLY` instead of `LIMIT`, (d) Oracle has many system schemas to filter, (e) Oracle identifiers are uppercase by default, (f) connection URL uses serviceName not databaseName.

---

## 2. Oracle Type Mapping Analysis (from Trino OracleClient.java)

### How Oracle JDBC Reports Types in DatabaseMetaData.getColumns()

Oracle's JDBC driver reports all numeric types as `java.sql.Types.NUMERIC` (2) with varying precision and scale:

| Oracle DDL Type | JDBC DATA_TYPE | COLUMN_SIZE (precision) | DECIMAL_DIGITS (scale) | Notes |
|----------------|----------------|-------------------------|------------------------|-------|
| `NUMBER(p,s)` | `Types.NUMERIC` (2) | p | s | Standard: precision and scale both specified |
| `NUMBER(p)` | `Types.NUMERIC` (2) | p | 0 | Integer-like: no decimal digits |
| `NUMBER` (bare) | `Types.NUMERIC` (2) | 0 (or 38) | varies (-127 possible) | **CRITICAL**: unspecified precision, driver may report 0 or 38 |
| `FLOAT` | `Types.NUMERIC` (2) | 126 | -127 | Oracle FLOAT is NUMBER subtype; scale=-127 is sentinel |
| `BINARY_FLOAT` | `Types.FLOAT` (6) or `100` | N/A | N/A | Oracle-specific JDBC type 100 |
| `BINARY_DOUBLE` | `Types.DOUBLE` (8) or `101` | N/A | N/A | Oracle-specific JDBC type 101 |
| `VARCHAR2(n)` | `Types.VARCHAR` (12) | n | 0 | Standard mapping |
| `NVARCHAR2(n)` | `Types.NVARCHAR` (-9) | n | 0 | Standard mapping |
| `CHAR(n)` | `Types.CHAR` (1) | n | 0 | Standard mapping |
| `NCHAR(n)` | `Types.NCHAR` (-15) | n | 0 | Standard mapping |
| `CLOB` | `Types.CLOB` (2005) | 0 | 0 | Large text |
| `NCLOB` | `Types.NCLOB` (2011) | 0 | 0 | Large national char text |
| `BLOB` | `Types.BLOB` (2004) | 0 | 0 | Large binary |
| `RAW(n)` | `Types.VARBINARY` (-3) | n | 0 | Variable-length binary |
| `DATE` | `Types.TIMESTAMP` (93) | 7 | 0 | **CRITICAL**: Oracle DATE includes time; driver reports as TIMESTAMP |
| `TIMESTAMP(p)` | `Types.TIMESTAMP` (93) | varies | p | Fractional seconds |
| `TIMESTAMP WITH TIME ZONE` | `Types.TIMESTAMP_WITH_TIMEZONE` (2014) | varies | p | Zone-aware timestamp |

### Recommended OracleSchemaFetcher.mapJdbcType() Override

```java
@Override
protected ArrowType mapJdbcType(int jdbcType, String typeName, int precision, int scale) {
    String lower = typeName != null ? typeName.toLowerCase() : "";

    // Oracle BINARY_FLOAT -> FLOAT4 (may report as Types.FLOAT=6 or OracleTypes.BINARY_FLOAT=100)
    if (lower.equals("binary_float") || jdbcType == 100) {
        return new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
    }
    // Oracle BINARY_DOUBLE -> FLOAT8 (may report as Types.DOUBLE=8 or OracleTypes.BINARY_DOUBLE=101)
    if (lower.equals("binary_double") || jdbcType == 101) {
        return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    }

    // CLOB/NCLOB -> VARCHAR
    if (jdbcType == Types.CLOB || jdbcType == Types.NCLOB) {
        return new ArrowType.Utf8();
    }

    // NVARCHAR2, NCHAR -> VARCHAR (base may not handle NVARCHAR/-9 and NCHAR/-15)
    if (jdbcType == Types.NVARCHAR || jdbcType == Types.NCHAR) {
        return new ArrowType.Utf8();
    }

    // NUMBER type precision/scale handling
    if (jdbcType == Types.NUMERIC) {
        // Oracle FLOAT: scale == -127 is the sentinel for unspecified scale
        if (scale == -127) {
            return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        }
        // Bare NUMBER (no precision): precision==0 means unspecified
        if (precision == 0) {
            return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        }
        // NUMBER(p,s) with valid precision -> DECIMAL
        // Arrow DECIMAL supports max precision=38 (matches Oracle)
        // Delegate to base for standard DECIMAL mapping
    }

    // TIMESTAMP WITH TIME ZONE -> TIMESTAMP (drop timezone info)
    if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
        return new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MILLISECOND, null);
    }

    // Delegate remaining types to base
    return super.mapJdbcType(jdbcType, typeName, precision, scale);
}
```

### Type Mapping Summary (Requirement ORA-03)

| Oracle Type | JDBC Type Code | Dremio Arrow Type | Read Method |
|-------------|---------------|-------------------|-------------|
| `NUMBER(p,s)` | NUMERIC (2) | `Decimal(p,s)` | `rs.getBigDecimal()` |
| `NUMBER` (bare) | NUMERIC (2), precision=0 | `FloatingPoint(DOUBLE)` | `rs.getDouble()` |
| `FLOAT` | NUMERIC (2), scale=-127 | `FloatingPoint(DOUBLE)` | `rs.getDouble()` |
| `VARCHAR2(n)` | VARCHAR (12) | `Utf8` | `rs.getString()` |
| `NVARCHAR2(n)` | NVARCHAR (-9) | `Utf8` | `rs.getString()` |
| `CHAR(n)` | CHAR (1) | `Utf8` | `rs.getString()` |
| `NCHAR(n)` | NCHAR (-15) | `Utf8` | `rs.getString()` |
| `CLOB` | CLOB (2005) | `Utf8` | `rs.getString()` |
| `NCLOB` | NCLOB (2011) | `Utf8` | `rs.getString()` |
| `BLOB` | BLOB (2004) | `Binary` | `rs.getBytes()` |
| `RAW(n)` | VARBINARY (-3) | `Binary` | `rs.getBytes()` |
| `DATE` | TIMESTAMP (93) | `Timestamp(MILLI)` | `rs.getTimestamp()` **NOT rs.getDate()** |
| `BINARY_FLOAT` | 100 or FLOAT (6) | `FloatingPoint(SINGLE)` | `rs.getFloat()` |
| `BINARY_DOUBLE` | 101 or DOUBLE (8) | `FloatingPoint(DOUBLE)` | `rs.getDouble()` |
| `TIMESTAMP(p)` | TIMESTAMP (93) | `Timestamp(MILLI)` | `rs.getTimestamp()` |
| `TIMESTAMP WITH TIME ZONE` | 2014 | `Timestamp(MILLI)` (drop TZ) | `rs.getTimestamp()` |

**Confidence: HIGH** -- Verified against Trino's OracleClient.java source code and Oracle JDBC documentation.

---

## 3. Oracle RecordReader: Critical DATE Quirk

Oracle's DATE type includes hours, minutes, and seconds (unlike SQL standard DATE which is date-only). The Oracle JDBC driver reports DATE as `java.sql.Types.TIMESTAMP` (93), so the base `JdbcSchemaFetcher.mapJdbcType()` will correctly map it to `ArrowType.Timestamp`. However, the base `JdbcRecordReader.writeValue()` has a potential issue:

The base class handles `TimeStampMilliVector` by calling `rs.getTimestamp(colName)`, which correctly preserves the time component of Oracle DATE. **No override needed for the read path** -- the base reader already uses `getTimestamp()` for `TimeStampMilliVector`.

However, `OracleRecordReader` may still need a `writeValue()` override for:
1. **CLOB/NCLOB**: The base reader's fallback to `rs.getString()` should work since these map to `VarCharVector`, but `rs.getString()` on a CLOB uses the CLOB's `getSubString()` internally. For very large CLOBs, this could cause memory issues. For v1.5, `rs.getString()` is acceptable.
2. **BLOB**: The base reader handles `VarBinaryVector` via `rs.getBytes()`, which works for BLOB. For very large BLOBs, same concern, but acceptable for v1.5.
3. **BINARY_FLOAT/BINARY_DOUBLE**: If the Oracle JDBC driver reports these with non-standard type codes (100/101), the schema fetcher maps them to Float4/Float8 vectors, and the base `writeValue()` handles `Float4Vector.setSafe(rs.getFloat())` and `Float8Vector.setSafe(rs.getDouble())` correctly. **No override needed.**

**Conclusion:** An `OracleRecordReader` subclass may not need any `writeValue()` override for v1.5, but should still exist (extending `JdbcRecordReader`) as a structural placeholder and for any future Oracle-specific read-path needs.

**Confidence: HIGH** -- Verified by reading the base `JdbcRecordReader.writeValue()` code and understanding Oracle JDBC driver behavior.

---

## 4. Oracle SSL/TLS Configuration

Oracle JDBC thin driver uses connection properties for SSL/TLS (not URL parameters like PostgreSQL):

| Oracle Property | Purpose |
|----------------|---------|
| `oracle.net.ssl_server_dn_match` | Enable hostname verification (like verify-full) |
| `javax.net.ssl.trustStore` | Path to truststore for certificate validation |
| `javax.net.ssl.trustStoreType` | Truststore type (JKS, PKCS12) |
| `javax.net.ssl.trustStorePassword` | Truststore password |

### Mapping to Dremio EncryptionValidationMode

| Dremio Mode | Oracle Properties |
|-------------|-------------------|
| `CERTIFICATE_AND_HOSTNAME_VALIDATION` | `oracle.net.ssl_server_dn_match=true` + truststore |
| `CERTIFICATE_ONLY_VALIDATION` | `oracle.net.ssl_server_dn_match=false` + truststore |
| `NO_VALIDATION` | No truststore; accept any certificate |

For Oracle, SSL is typically enabled by using `jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)...))` URL format (TCPS instead of TCP). A simpler approach for v1.5: append `?oracle.net.ssl_server_dn_match=true` to the EZConnect URL when SSL is enabled.

**For v1.5:** SSL support for Oracle is more complex than PostgreSQL. Basic implementation should support the `useSsl` flag and `encryptionValidationMode`, setting the appropriate Oracle connection properties via `getConnectionProperties()`. Full truststore configuration is deferred.

**Confidence: MEDIUM** -- Oracle SSL configuration is more complex than PostgreSQL; the exact property combination depends on Oracle server version and configuration.

---

## 5. Oracle JDBC URL Format

Oracle JDBC thin driver supports two connection styles:

### EZConnect (recommended for v1.5)
```
jdbc:oracle:thin:@//hostname:port/serviceName
```

### SID-based (legacy)
```
jdbc:oracle:thin:@hostname:port:SID
```

### TNS Descriptor (advanced)
```
jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=hostname)(PORT=port))(CONNECT_DATA=(SERVICE_NAME=serviceName)))
```

**For v1.5:** Use EZConnect format with service name. The `OracleConf` field is `serviceName` (not `databaseName`), matching the ORA-01 requirement. Default port is 1521.

**URL assembly:**
```java
public String jdbcUrl() {
    StringBuilder url = new StringBuilder("jdbc:oracle:thin:@//");
    url.append(hostname).append(":").append(port).append("/").append(serviceName);
    return url.toString();
}
```

**Confidence: HIGH** -- Verified against Oracle JDBC documentation and Trino's Oracle connector.

---

## 6. OracleConf Pattern (mirrors PostgresConf)

### Field Layout

| Field | @Tag | Type | Default | Notes |
|-------|------|------|---------|-------|
| hostname | 10 | String | - | Required |
| port | 11 | int | 1521 | Oracle default port |
| serviceName | 12 | String | - | Required; use service name, not SID |
| username | 20 | String | - | - |
| password | 21 | SecretRef (@Secret) | - | Uses `SecretRef.get()` |
| useSsl | 30 | boolean | false | - |
| encryptionValidationMode | 31 | EncryptionValidationMode | CERTIFICATE_AND_HOSTNAME_VALIDATION | - |
| fetchSize | 40 | int | 4096 | Rows per fetch |
| queryTimeoutSec | 41 | int | 0 | 0 = no timeout |

Note: Tags 1-3 are reserved by `BaseJdbcConf` (poolSize, idleTimeoutMs, validationQuery). Tags 10-41 match PostgresConf pattern for consistency.

### Key Differences from PostgresConf

1. **`serviceName` instead of `databaseName`** -- Oracle uses service names for PDB connections
2. **Default port 1521** instead of 5432
3. **Driver class: `oracle.jdbc.OracleDriver`** instead of `org.postgresql.Driver`
4. **Validation query: `SELECT 1 FROM DUAL`** instead of `SELECT 1`
5. **JDBC URL format: `jdbc:oracle:thin:@//host:port/service`** instead of `jdbc:postgresql://host:port/db`
6. **Connection properties for SSL** -- Oracle uses `oracle.net.*` properties, not URL params
7. **Query timeout** -- Oracle uses `oracle.jdbc.ReadTimeout` connection property (milliseconds), not PostgreSQL's `statement_timeout`

### newPlugin() Method

Same anonymous subclass pattern as PostgresConf:
```java
@Override
public JdbcStoragePlugin newPlugin(...) {
    return new JdbcStoragePlugin(this, name) {
        @Override
        protected JdbcSchemaFetcher createSchemaFetcher(JdbcConnectionPool pool) {
            return new OracleSchemaFetcher(pool);
        }
        @Override
        public JdbcRecordReader createRecordReader(...) {
            return new OracleRecordReader(ctx, config, pool);
        }
    };
}
```

**Confidence: HIGH** -- Pattern verified against PostgresConf source code.

---

## 7. OracleSqlBuilder: LIMIT vs FETCH FIRST

**Critical difference:** Oracle does not support the SQL `LIMIT` clause. The base `SqlBuilder` generates `LIMIT N` which is invalid Oracle SQL.

### Oracle LIMIT alternatives

1. **Oracle 12c+ (FETCH FIRST):** `SELECT * FROM t WHERE ... FETCH FIRST N ROWS ONLY`
2. **Pre-12c (ROWNUM):** `SELECT * FROM (SELECT * FROM t WHERE ...) WHERE ROWNUM <= N`

Since `gvenzl/oracle-xe:21-slim` uses Oracle 21c, **use `FETCH FIRST N ROWS ONLY`**.

### Implementation

Create `OracleSqlBuilder extends SqlBuilder` that overrides `buildSql()`:
- Replace `LIMIT N` with `FETCH FIRST N ROWS ONLY`
- Everything else (SELECT, FROM, WHERE) stays the same

The `SqlBuilder` is instantiated in pushdown test classes directly. For the plugin's query execution path, `SqlBuilder` is used by `JdbcScanPrel` to generate the SQL. The `OracleConf.newPlugin()` would need to wire the Oracle SQL builder.

**However**, looking at the current codebase, the `SqlBuilder` is not injected into the storage plugin -- it is used directly in `JdbcScanPrel`. This means either:
- (a) The `SqlBuilder` must be made pluggable (abstract factory pattern via the plugin), OR
- (b) The Oracle `SqlBuilder` override handles LIMIT differently at the SQL string level

**Recommended approach for v1.5:** Make `SqlBuilder` accessible from the `JdbcStoragePlugin` via a `createSqlBuilder()` factory method (similar to `createSchemaFetcher()`). The Oracle plugin overrides it to return `OracleSqlBuilder`. This is a small Phase 30 base amendment.

**Confidence: HIGH** -- Verified that `LIMIT` is not valid Oracle SQL; `FETCH FIRST N ROWS ONLY` is supported since Oracle 12c.

---

## 8. Source Icon

An `ORACLE.svg` already exists at `dac/ui-lib/icons/dremio/sources/ORACLE.svg`. Since the source type is `ORACLE_DB`, a copy named `ORACLE_DB.svg` is needed (same pattern as PostgreSQL where `POSTGRES.svg` was copied to `POSTGRES_DB.svg`).

Also check `dac/ui-lib/icons/dremio-dark/sources/` for a dark-mode variant.

**Confidence: HIGH** -- Verified by listing the icon directory.

---

## 9. TestContainers Setup: gvenzl/oracle-xe:21-slim

### Container Configuration

| Setting | Value | Notes |
|---------|-------|-------|
| Image | `gvenzl/oracle-xe:21-slim` | Slim variant is smallest; no faststart needed |
| Default port | 1521 | Standard Oracle listener port |
| Default service name | `XEPDB1` | Default pluggable database for 18c+ |
| Default SID | `xe` | Instance name |
| Default system user | `system` | Admin account |
| TestContainers default user | `test` | OracleContainer default username |
| TestContainers default password | `test` | OracleContainer default password |
| Validation query | `SELECT 1 FROM DUAL` | Oracle-specific |
| JDBC URL format | `jdbc:oracle:thin:@host:port/xepdb1` | Service name style (EZConnect) |

### Environment Variables (gvenzl image)

| Variable | Purpose |
|----------|---------|
| `ORACLE_PASSWORD` | Password for SYS/SYSTEM (required on first start) |
| `APP_USER` | Create application schema user |
| `APP_USER_PASSWORD` | Password for APP_USER |
| `ORACLE_DATABASE` | Create custom PDB (18c+ only) |

### OracleContainer API (org.testcontainers:oracle-xe)

```java
OracleContainer oracle = new OracleContainer("gvenzl/oracle-xe:21-slim")
    .withUsername("test_user")      // Creates app user
    .withPassword("test_pass")      // App user password
    .withDatabaseName("xepdb1")     // Service name (default)
    .withStartupAttempts(3);

// After start:
oracle.getJdbcUrl()    // "jdbc:oracle:thin:@host:port/xepdb1"
oracle.getUsername()   // "test_user"
oracle.getPassword()  // "test_pass"
```

### How Trino Tests Oracle (Key Reference)

Trino's `TestingOracleServer.java` (verified from source):
1. Uses `gvenzl/oracle-free:23.9-slim` (newer Oracle Free image, but oracle-xe:21-slim is functionally equivalent)
2. Creates a test user via SQL: `CREATE USER trino_test IDENTIFIED BY trino_test_password`
3. Creates a dedicated tablespace: `CREATE TABLESPACE trino_test DATAFILE 'test_db.dat' SIZE 100M ONLINE`
4. Grants permissions: standard Oracle grants
5. Switches container: `ALTER SESSION SET CONTAINER=FREEPDB1` (for oracle-free; for oracle-xe use XEPDB1)
6. Uses `init.sql` to increase process limit and disable async I/O
7. **Authentication: basic username/password only** -- no Oracle Wallet, no Kerberos
8. Waits for log message: `".*DATABASE IS READY TO USE!.*"` with 2-minute timeout

### Oracle Wallet Feasibility with gvenzl/oracle-xe

**Oracle Wallet is NOT feasible with the free container** for testing:
- Oracle Wallet requires Oracle Advanced Security option
- The XE (Express Edition) container does not include Advanced Security
- Trino does NOT use Oracle Wallet in tests -- they use basic username/password auth
- **Decision: Basic auth (username/password) is correct for testcontainer tests**

**Confidence: HIGH** -- Verified from Trino's TestingOracleServer.java source code.

---

## 10. Oracle System Schema Filtering

Trino's `OracleClient.java` defines an `INTERNAL_SCHEMAS` set with these entries:
- `ctxsys`, `flows_files`, `mdsys`, `outln`, `sys`, `system`, `xdb`, `xs$null`

Additional Oracle system schemas commonly seen:
- `audsys`, `dbsnmp`, `dvsys`, `gsmadmin_internal`, `lbacsys`, `orddata`, `ordsys`, `wmsys`, `appqossys`, `dbsfwuser`, `olapsys`

### Recommended isSystemSchema() Override for OracleSchemaFetcher

```java
private static final Set<String> ORACLE_SYSTEM_SCHEMAS = Set.of(
    "sys", "system", "ctxsys", "mdsys", "xdb", "outln",
    "xs$null", "flows_files", "dvsys", "audsys", "dbsnmp",
    "gsmadmin_internal", "lbacsys", "orddata", "ordsys",
    "wmsys", "appqossys", "dbsfwuser", "olapsys"
);

@Override
protected boolean isSystemSchema(String schemaName) {
    if (schemaName == null) return false;
    return ORACLE_SYSTEM_SCHEMAS.contains(schemaName.toLowerCase());
}
```

Note: The base `isSystemSchema()` checks for `information_schema`, `pg_catalog`, etc. (PostgreSQL-specific). The Oracle override should NOT call `super.isSystemSchema()` since those are irrelevant for Oracle -- it should be a full replacement.

**Confidence: HIGH** -- Verified against Trino OracleClient.java source.

---

## 11. Oracle Identifier Case Sensitivity

Oracle stores unquoted identifiers in UPPERCASE. When `DatabaseMetaData.getColumns()` returns column names, they are typically UPPERCASE (e.g., `COLUMN_NAME` returns `"ID"`, `"NAME"`, etc.).

This affects schema discovery:
- Schema names are uppercase: `"PUBLIC"` not `"public"` (but Oracle doesn't have a `public` schema -- it has user schemas)
- Table names are uppercase: `"EMPLOYEES"` not `"employees"`
- Column names are uppercase: `"ID"`, `"NAME"`

The base `JdbcSchemaFetcher` uses column names as-is from `getColumns()`. The `SqlBuilder` wraps identifiers in double quotes. When Oracle returns uppercase names and we quote them, the SQL will contain `SELECT "ID", "NAME" FROM "SCHEMA"."TABLE"` -- this is correct for Oracle since Oracle preserves case in quoted identifiers.

**No special handling needed** for v1.5 -- the existing pattern works. Users will see uppercase schema/table/column names in Dremio, which correctly reflects Oracle's behavior.

**Confidence: HIGH** -- Standard Oracle behavior.

---

## 12. Oracle JDBC Driver Maven Coordinates

### Maven Dependency

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
    <version>23.7.0.25.01</version>
</dependency>
```

### License

The ojdbc11 driver is licensed under **Oracle Free Use Terms and Conditions (FUTC)**, which allows free use. It is available from Maven Central -- no special repository needed.

### Driver Class

`oracle.jdbc.OracleDriver` (auto-discovered via SPI in JDBC 4+, but explicit declaration is recommended).

### Compatibility

- ojdbc11: Requires JDK 11+ (compatible with JDK 11, 17, 19, 21)
- Supports Oracle Database 19c, 21c, 23ai

### Root POM Change Needed

The Oracle JDBC driver is NOT currently in the Dremio root POM `<dependencyManagement>`. A version property and managed dependency must be added:

```xml
<ojdbc11.version>23.7.0.25.01</ojdbc11.version>
```

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
    <version>${ojdbc11.version}</version>
</dependency>
```

### TestContainers oracle-xe Module

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>oracle-xe</artifactId>
    <scope>test</scope>
</dependency>
```

This is already managed by the `testcontainers-bom` (version 1.20.4) in the root POM.

**Confidence: HIGH** -- Verified from Maven Central and root POM.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| ojdbc11 | 23.7.0.25.01 | Oracle JDBC thin driver | Official Oracle driver, Maven Central, FUTC license |
| HikariCP | 5.1.0 (existing) | Connection pooling | Already in base JDBC framework |
| Apache Arrow | (existing) | Type mapping and vectors | Already in base JDBC framework |

### Supporting (Test)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| testcontainers:oracle-xe | 1.20.4 (BOM) | Oracle test container | Integration tests |
| gvenzl/oracle-xe | 21-slim | Docker image for tests | Lightweight Oracle XE |
| JUnit 4 | (existing) | Test framework | Following project convention |

---

## Architecture Patterns

### Recommended Module Structure

```
plugins/jdbc-oracle/
  pom.xml
  src/
    main/
      java/com/dremio/plugins/jdbc/oracle/
        OracleConf.java               -- @SourceType("ORACLE_DB"), extends BaseJdbcConf
        OracleSchemaFetcher.java      -- mapJdbcType() for Oracle types, isSystemSchema()
        OracleRecordReader.java       -- Placeholder (or writeValue override if needed)
        OracleSqlBuilder.java         -- FETCH FIRST N ROWS ONLY instead of LIMIT
      resources/
        oracle-layout.json            -- UI form definition
        sabot-module.conf             -- classpath scanning
    test/
      java/com/dremio/plugins/jdbc/oracle/
        DremioOracleContainer.java    -- OracleContainer + DremioContainer marker
        OracleTestContainer.java      -- Shared test helper (createPool, executeSql)
        TestOracleTypeMapping.java    -- Type mapping roundtrip tests
        TestOracleSchemaDiscovery.java -- Schema/table listing tests
        TestOraclePushdown.java       -- Filter/projection/limit pushdown SQL tests
```

### Pattern: DremioContainer Marker (from PostgreSQL)

All TestContainers container subclasses in Dremio must implement the `DremioContainer` marker interface (enforced by `DremioRestrictedTestcontainersUsage` error-prone check):

```java
public final class DremioOracleContainer
    extends OracleContainer
    implements DremioContainer {
  private static final String IMAGE = "gvenzl/oracle-xe:21-slim";
  public DremioOracleContainer() {
    super(IMAGE);
  }
}
```

### Pattern: TestJdbcConf for Test Pool Creation (from PostgreSQL)

```java
static final class TestJdbcConf extends BaseJdbcConf<TestJdbcConf, StoragePlugin> {
    @Override
    public String jdbcUrl() { return container.getJdbcUrl(); }
    @Override
    public String driverClassName() { return "oracle.jdbc.OracleDriver"; }
    @Override
    public String getUsername() { return container.getUsername(); }
    @Override
    public String getPassword() { return container.getPassword(); }
    @Override
    public StoragePlugin newPlugin(...) { throw new UnsupportedOperationException(); }
}
```

### Anti-Patterns to Avoid

- **Using LIMIT in Oracle SQL**: Oracle does not support `LIMIT`. Use `FETCH FIRST N ROWS ONLY` (12c+).
- **Using rs.getDate() for Oracle DATE**: Loses the time component. Always use `rs.getTimestamp()`.
- **Calling super.isSystemSchema() from OracleSchemaFetcher**: The base method checks PostgreSQL-specific schemas. Oracle should have its own complete list.
- **Expecting lowercase identifiers**: Oracle returns UPPERCASE names by default. Do not force lowercase.
- **Hardcoding SID-based URL**: Use service name (EZConnect) format, not SID format.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Connection pooling | Custom pool | HikariCP (base framework) | Already solved |
| Arrow type mapping | Custom mapper | Base `JdbcToArrowUtils` + Oracle overrides | Handles 90% of types |
| SQL generation | String concatenation | `SqlBuilder` subclass | Identifier quoting, clause ordering |
| Container lifecycle | Manual Docker | TestContainers `OracleContainer` | Automatic port mapping, health checks |
| Oracle JDBC URL | Manual formatting | `OracleConf.jdbcUrl()` method | EZConnect format, SSL parameters |

---

## Common Pitfalls

### Pitfall 1: Oracle NUMBER Without Precision
**What goes wrong:** `NUMBER` (bare, no precision) reports precision=0 or precision=38 with scale=-127 or scale=0. Trying to create `DECIMAL(0, 0)` or `DECIMAL(38, -127)` causes Arrow errors.
**Why it happens:** Oracle NUMBER is a variable-precision type; bare NUMBER stores up to 38 digits of any scale.
**How to avoid:** In `mapJdbcType()`, check for `precision == 0` or `scale == -127` and map to `DOUBLE` (Float8). This matches Trino's behavior.
**Warning signs:** Arrow `DecimalVector` creation errors or unexpected DECIMAL(0,0) in schema.

### Pitfall 2: Oracle DATE Includes Time
**What goes wrong:** Querying an Oracle DATE column returns only the date portion, losing hours/minutes/seconds.
**Why it happens:** Using `rs.getDate()` which truncates to midnight. Oracle DATE actually stores YYYY-MM-DD HH:MI:SS.
**How to avoid:** The Oracle JDBC driver reports DATE as `Types.TIMESTAMP` (93), so the schema fetcher maps it to `Timestamp`, and the base reader uses `rs.getTimestamp()`. This should work correctly out of the box.
**Warning signs:** All Oracle DATE values showing midnight (00:00:00) timestamps.

### Pitfall 3: LIMIT Clause in Oracle
**What goes wrong:** Generated SQL contains `LIMIT N` which is a syntax error in Oracle.
**Why it happens:** Base `SqlBuilder` generates `LIMIT N` (PostgreSQL/MySQL syntax).
**How to avoid:** Create `OracleSqlBuilder` that overrides the limit clause to use `FETCH FIRST N ROWS ONLY`.
**Warning signs:** `ORA-00933: SQL command not properly ended` errors.

### Pitfall 4: Oracle System Schemas in Listing
**What goes wrong:** Schema listing includes SYS, SYSTEM, MDSYS, XDB, and dozens of Oracle internal schemas.
**Why it happens:** Oracle XE includes many system schemas that are not relevant to end users.
**How to avoid:** Override `isSystemSchema()` with a comprehensive list (see Section 10).
**Warning signs:** 20+ schemas appearing in Dremio catalog, most with zero user tables.

### Pitfall 5: TestContainer Startup Time
**What goes wrong:** Tests timeout waiting for Oracle container to start.
**Why it happens:** Oracle XE is slower to start than PostgreSQL (30-120 seconds for slim variant).
**How to avoid:** Use `gvenzl/oracle-xe:21-slim` (fastest variant without faststart). Set `withStartupAttempts(3)`. Consider using `@ClassRule` to share the container across all test classes. Ensure Docker has sufficient memory (at least 2GB for Oracle XE).
**Warning signs:** `ContainerLaunchException: Container startup failed` in CI.

### Pitfall 6: Oracle Uppercase Identifiers
**What goes wrong:** SQL queries fail because identifier case doesn't match.
**Why it happens:** Oracle stores identifiers in UPPERCASE. When schema fetcher returns "EMPLOYEES" and SqlBuilder quotes it as `"EMPLOYEES"`, this works. But if user types `employees` in Dremio SQL, it needs case-insensitive matching.
**How to avoid:** Dremio handles case-insensitive matching in its own planner layer. The JDBC connector just needs to faithfully pass through the names from `DatabaseMetaData`. No special handling needed.
**Warning signs:** `ORA-00942: table or view does not exist` errors.

### Pitfall 7: CLOB/BLOB Size Limits
**What goes wrong:** Reading very large CLOB/BLOB values causes OOM.
**Why it happens:** `rs.getString()` for CLOB and `rs.getBytes()` for BLOB load the entire content into memory.
**How to avoid:** For v1.5, document that CLOB/BLOB support is for reasonable sizes (< 10MB). Streaming support is a future enhancement.
**Warning signs:** `OutOfMemoryError` when querying tables with large LOB columns.

---

## Code Examples

### Oracle TestContainer Setup (following PostgreSQL pattern)

```java
// DremioOracleContainer.java
public final class DremioOracleContainer
    extends OracleContainer
    implements DremioContainer {
  private static final String IMAGE = "gvenzl/oracle-xe:21-slim";
  public DremioOracleContainer() {
    super(IMAGE);
  }
}

// OracleTestContainer.java
public final class OracleTestContainer {
  public static final DremioOracleContainer ORACLE =
      (DremioOracleContainer) new DremioOracleContainer()
          .withUsername("test_user")
          .withPassword("test_pass")
          .withStartupAttempts(3);

  public static String getJdbcUrl() { return ORACLE.getJdbcUrl(); }
  public static String getUsername() { return "test_user"; }
  public static String getPassword() { return "test_pass"; }

  public static JdbcConnectionPool createPool() {
    TestJdbcConf conf = new TestJdbcConf();
    conf.poolSize = 3;
    conf.validationQuery = "SELECT 1 FROM DUAL";
    return new JdbcConnectionPool(conf);
  }

  public static void executeSql(String sql) throws SQLException {
    try (Connection conn = DriverManager.getConnection(
            ORACLE.getJdbcUrl(), getUsername(), getPassword());
         Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
    }
  }
}
```

### Oracle Type Test Table DDL

```sql
CREATE TABLE type_test (
  id NUMBER(10) NOT NULL,
  col_number_ps NUMBER(10,2),
  col_number_bare NUMBER,
  col_float FLOAT,
  col_binary_float BINARY_FLOAT,
  col_binary_double BINARY_DOUBLE,
  col_varchar2 VARCHAR2(100),
  col_nvarchar2 NVARCHAR2(100),
  col_char CHAR(10),
  col_clob CLOB,
  col_nclob NCLOB,
  col_blob BLOB,
  col_raw RAW(100),
  col_date DATE,
  col_timestamp TIMESTAMP(6),
  col_timestamp_tz TIMESTAMP(6) WITH TIME ZONE,
  PRIMARY KEY (id)
)
```

### Oracle Insert Test Data

```sql
INSERT INTO type_test VALUES (
  1,
  12345.67,
  3.141592653589793,
  2.71828,
  3.14,
  2.718281828459045,
  'hello',
  N'world',
  'pad       ',
  'clob text value',
  N'nclob text value',
  UTL_RAW.CAST_TO_RAW('binary data'),
  UTL_RAW.CAST_TO_RAW('raw data'),
  TO_DATE('2024-01-15 10:30:00', 'YYYY-MM-DD HH24:MI:SS'),
  TIMESTAMP '2024-01-15 10:30:00.123456',
  TIMESTAMP '2024-01-15 10:30:00.123 +05:30'
)
```

### OracleSqlBuilder

```java
public class OracleSqlBuilder extends SqlBuilder {
  @Override
  public String buildSql(String schemaName, String tableName,
      List<SchemaPath> projectedColumns, String whereClause, Integer limit) {
    // Build everything except LIMIT using a modified approach
    StringBuilder sb = new StringBuilder();

    // SELECT list (same as base)
    if (projectedColumns == null || projectedColumns.isEmpty()) {
      sb.append("SELECT *");
    } else {
      sb.append("SELECT ");
      boolean first = true;
      for (SchemaPath col : projectedColumns) {
        if (!first) sb.append(", ");
        sb.append(quoteIdentifier(col.getRootSegment().getPath()));
        first = false;
      }
    }

    // FROM clause
    sb.append(" FROM ").append(quoteIdentifier(schemaName))
      .append(".").append(quoteIdentifier(tableName));

    // WHERE clause
    if (whereClause != null && !whereClause.isEmpty()) {
      sb.append(" WHERE ").append(whereClause);
    }

    // Oracle 12c+ FETCH FIRST instead of LIMIT
    if (limit != null) {
      sb.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
    }

    return sb.toString();
  }
}
```

---

## 13. Phase 30 Base Amendments Required

### Amendment 1: SqlBuilder Pluggability

The `SqlBuilder` needs to be injectable into the plugin so that Oracle can substitute `OracleSqlBuilder`. Currently `SqlBuilder` is instantiated directly in `JdbcScanPrel`. Two options:

**Option A (recommended):** Add a `createSqlBuilder()` factory method to `JdbcStoragePlugin`:
```java
public SqlBuilder createSqlBuilder() {
    return new SqlBuilder();
}
```
Oracle's anonymous subclass in `OracleConf.newPlugin()` overrides it:
```java
@Override
public SqlBuilder createSqlBuilder() {
    return new OracleSqlBuilder();
}
```

**Option B:** Refactor `SqlBuilder.buildSql()` to have a protected `appendLimit()` method that `OracleSqlBuilder` overrides. This is cleaner but requires verifying the base class refactoring doesn't break PostgreSQL.

**Recommendation:** Option A is simpler and follows the existing factory pattern.

### Amendment 2: Validation Query Customization

The `BaseJdbcConf.validationQuery` defaults to `"SELECT 1"`, which does NOT work on Oracle (requires `SELECT 1 FROM DUAL`). The Oracle conf should set `validationQuery = "SELECT 1 FROM DUAL"` in its field initialization or constructor.

Actually, looking at the code again: `validationQuery` is a public field in `BaseJdbcConf` with default `"SELECT 1"`. The `OracleConf` class cannot override a field default in the superclass. Two approaches:

**Option A (simple):** Override `validationQuery` in a constructor or initializer block:
```java
public OracleConf() {
    this.validationQuery = "SELECT 1 FROM DUAL";
}
```

**Option B:** Make `validationQuery` abstract or add a method `getValidationQuery()`. Over-engineering for now.

**Recommendation:** Option A -- set in constructor or initializer.

---

## 14. Module Registration (same as PostgreSQL)

1. **`plugins/pom.xml`**: Add `<module>jdbc-oracle</module>`
2. **`dac/daemon/pom.xml`**: Add dependency `<artifactId>dremio-plugin-jdbc-oracle</artifactId>`
3. **`sabot-module.conf`**: `dremio.classpath.scanning.packages += "com.dremio.plugins.jdbc.oracle"`

---

## Open Questions

1. **Oracle JDBC driver scope: runtime or compile?**
   - What we know: The spec says "user-supplied at runtime" (Oracle Free Use license). However, for compilation and tests, the driver must be on the classpath.
   - What's unclear: Should the driver be `<scope>compile</scope>` (simplest) or `<scope>provided</scope>` (user supplies at runtime)?
   - Recommendation: Use `<scope>compile</scope>` for now. The FUTC license allows redistribution. If licensing concerns arise, change to `provided` later.

2. **Oracle XE container memory requirements in CI**
   - What we know: Oracle XE requires ~1-2GB RAM. gvenzl/oracle-xe:21-slim is the lightest.
   - What's unclear: Whether CI runners have enough memory and whether container startup is fast enough.
   - Recommendation: Use 21-slim (not faststart, which is larger on disk). Set startup attempts to 3. If CI is slow, consider an `@Category` annotation to optionally skip Oracle tests.

3. **How to handle Oracle FLOAT vs NUMBER precision in edge cases**
   - What we know: Oracle FLOAT reports scale=-127 as sentinel. NUMBER without precision reports precision=0 or 38.
   - What's unclear: Whether `ResultSetMetaData` vs `DatabaseMetaData` report different values.
   - Recommendation: Use `DatabaseMetaData.getColumns()` results (which is what `JdbcSchemaFetcher` uses). Map scale=-127 and precision=0 to DOUBLE. This matches Trino's behavior.

---

## Sources

### Primary (HIGH confidence)
- Trino OracleClient.java (GitHub raw source, trinodb/trino master) -- type mapping, system schema filtering
- Trino TestingOracleServer.java (GitHub raw source) -- test container setup, authentication pattern
- Oracle JDBC driver documentation (docs.oracle.com) -- connection URL format, SSL properties
- TestContainers OracleContainer source (GitHub testcontainers-java) -- API, defaults, getJdbcUrl()
- gvenzl/oracle-xe README (GitHub) -- environment variables, image variants, service names
- Local codebase -- PostgreSQL connector pattern, base framework code

### Secondary (MEDIUM confidence)
- Oracle JDBC FAQ (oracle.com) -- NUMBER precision behavior
- Maven Central (central.sonatype.com) -- ojdbc11 coordinates, latest versions
- Oracle community forums -- NUMBER scale=-127 sentinel behavior

### Tertiary (LOW confidence)
- Various blog posts about Oracle DATE quirk and SSL setup -- consistent across sources but not primary docs

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- ojdbc11 is the standard Oracle JDBC driver, available on Maven Central
- Architecture: HIGH -- follows proven PostgreSQL connector pattern in same codebase
- Type mapping: HIGH -- verified against Trino OracleClient.java source code
- Test infrastructure: HIGH -- Trino uses same gvenzl container with basic auth, no Oracle Wallet
- Pitfalls: HIGH -- well-documented Oracle quirks (DATE, NUMBER, LIMIT, uppercase)

**Research date:** 2026-03-13
**Valid until:** 2026-04-13 (stable domain; Oracle JDBC driver and container images change slowly)
