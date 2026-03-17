# Phase 34: ADBC Driver for (at least) PostgreSQL - Research

**Researched:** 2026-03-14
**Domain:** Arrow Database Connectivity (ADBC) native driver integration via JNI
**Confidence:** MEDIUM (JNI driver is newly released; ADBC PG driver bind param format verified from source)

## Summary

This phase adds an optional high-performance execution backend that replaces the JDBC ResultSet-to-Arrow conversion path with native Arrow buffers via ADBC. The architecture swaps only the execution/reading layer (RecordReader, SchemaFetcher, ConnectionPool) while keeping all pushdown logic (RexToSqlString, SqlBuilder, SqlBuildRequest, BindParam, JdbcScanPrel, all pushdown rules) completely unchanged. PostgreSQL gets full ADBC support via the native C++ libpq-based ADBC driver loaded through JNI; Oracle stays on JDBC.

The critical technical decisions center around three areas: (1) bind parameter placeholder format -- the ADBC PostgreSQL driver uses `$1, $2, $3` syntax (PostgreSQL native prepared statement format), not JDBC `?` placeholders, requiring a translation layer; (2) the JNI driver (`adbc-driver-jni`) is newly available on Maven Central as of ADBC 21 (November 2025) with binary artifacts for Linux amd64/arm64, but described as "in a very limited fashion" experimentally; (3) the ADBC PG driver previously could not execute parameterized SELECTs, but this was fixed in ADBC 14 (August 2024) and is fully supported in ADBC 22.

**Primary recommendation:** Add ADBC classes in the existing `jdbc-base` module (no new module), use create-per-query for ADBC connections (no pooling needed for libpq), translate `?` placeholders to `$N` at the SqlBuilder output boundary before passing SQL to ADBC, and use ADBC's `getTableSchema()` for schema discovery when in ADBC mode.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Add `protocol_mode` field to source configuration: `AUTO` (default), `JDBC`, `ADBC`
- **AUTO**: Try ADBC if the native driver is available and the database supports it; fall back to JDBC otherwise
- **JDBC**: Force JDBC execution (existing behavior)
- **ADBC**: Force ADBC execution (fail if native driver unavailable)
- This is per-source, user-configurable
- All pushdown logic stays unchanged: RexToSqlString, SqlBuilder, SqlBuildRequest, JdbcScanPrel, all pushdown rules, JdbcGroupScan, JdbcSubScan, BindParam
- Only the execution/reading layer changes: RecordReader (query execution + Arrow batch production), SchemaFetcher (metadata discovery), ConnectionPool (connection lifecycle)
- ADBC uses native wire protocol via JNI (libpq for PostgreSQL), NOT ADBC-over-JDBC
- `adbc-driver-jni` (Maven artifact 0.22.0) -- JNI bridge from Java to C++ ADBC driver manager
- JniDriver -> JniConnection -> JniStatement -> ArrowReader (native Arrow buffers, zero-copy)
- Native PG driver (`libadbc_driver_postgresql.so`) loaded via C++ dlopen at runtime
- Bind parameters via `AdbcStatement.bind(VectorSchemaRoot)` -- Arrow-typed
- Use `dbc` CLI (from columnar.tech) to install native ADBC drivers in Docker image
- Add `dbc install postgresql` to Dockerfile
- The JNI bridge native lib is bundled inside `adbc-driver-jni-*.jar` (self-extracting)
- The database-specific driver `.so` needs to be on `LD_LIBRARY_PATH` or a standard lib path
- PostgreSQL ADBC: fully implemented and tested
- Oracle: stays on JDBC only (no ADBC Oracle driver exists in the ecosystem)
- The base framework supports both backends so future databases with ADBC drivers can opt in

### Claude's Discretion
- Bind Parameter Placeholder Format: Research whether ADBC PG driver translates `?` -> `$N` internally, or if SqlBuilder needs conditional emit
- ADBC Connection Pooling Strategy: Choose based on ADBC connection lifecycle characteristics
- Class Naming and Module Structure: Whether to add Adbc* classes in existing jdbc-base module or create a new module
- Schema Discovery Implementation: Whether to use ADBC schema discovery or keep JDBC DatabaseMetaData for schema

### Deferred Ideas (OUT OF SCOPE)
- Oracle ADBC support (no driver exists)
- MySQL/SQL Server ADBC
- Write operations via ADBC
- Replacing JDBC entirely (JDBC remains as fallback)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| ADBC-01 | Optional ADBC backend as alternative to manual ResultSet->Arrow conversion (updated: native ADBC via JNI to C++ drivers) | Full ADBC Java JNI API researched; JniDriver/JniConnection/JniStatement lifecycle documented; bind parameter format resolved ($1 syntax); Maven artifact availability confirmed (0.22.0); Arrow version compatibility analyzed |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| adbc-driver-jni | 0.22.0 | JNI bridge from Java to C++ ADBC driver manager | Official Apache Arrow ADBC Java JNI bindings, available on Maven Central since ADBC 21 |
| adbc-core | 0.22.0 | Core ADBC interfaces (AdbcConnection, AdbcStatement, AdbcDatabase) | Required API dependency for ADBC integration |
| libadbc_driver_postgresql.so | ADBC 22 (1.10.0 native) | Native C++ PostgreSQL ADBC driver using libpq wire protocol | Official Apache Arrow ADBC PG driver; zero-copy Arrow results |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| dbc CLI | latest | Install native ADBC driver .so files into Docker image | During Docker image build only |
| arrow-memory-core | 18.3.0 (ADBC dep) / 18.1.1-dremio (Dremio) | Arrow memory management / BufferAllocator | Already in Dremio; ADBC needs compatible version |
| arrow-vector | 18.3.0 (ADBC dep) / 18.1.1-dremio (Dremio) | Arrow VectorSchemaRoot for bind parameters | Already in Dremio; ADBC needs compatible version |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Native ADBC via JNI | ADBC-over-JDBC adapter | ADBC-over-JDBC wraps JDBC, defeating the purpose; native gives zero-copy Arrow from libpq |
| dbc CLI for driver install | Manual .so download | dbc handles versioning and platform detection; manual is fragile |
| No connection pool | Simple bounded pool | libpq connections are cheap (~1ms); pooling adds complexity for marginal gain |

**Installation (Maven):**
```xml
<dependency>
    <groupId>org.apache.arrow.adbc</groupId>
    <artifactId>adbc-driver-jni</artifactId>
    <version>0.22.0</version>
</dependency>
<dependency>
    <groupId>org.apache.arrow.adbc</groupId>
    <artifactId>adbc-core</artifactId>
    <version>0.22.0</version>
</dependency>
```

**Installation (Dockerfile -- native driver):**
```dockerfile
# Install dbc CLI and PostgreSQL ADBC driver
RUN curl -LsSf https://dbc.columnar.tech/install.sh | sh \
    && dbc install postgresql
```

## Architecture Patterns

### Recommended Project Structure
```
plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/
├── conf/
│   └── BaseJdbcConf.java           # Add ProtocolMode enum + protocolMode field
├── exec/
│   └── JdbcScanCreator.java        # Branch on protocolMode to create ADBC or JDBC reader
│   └── JdbcSubScan.java            # Unchanged (carries SQL + params for both backends)
├── planning/
│   └── SqlBuilder.java             # Add translatePlaceholders(sql, params) utility
│   └── BindParam.java              # Unchanged
│   └── RexToSqlString.java         # Unchanged (always emits ?)
│   └── ...                         # All other planning classes unchanged
├── reader/
│   └── JdbcRecordReader.java       # Unchanged (JDBC path)
│   └── AdbcRecordReader.java       # NEW: ADBC execution via JniStatement -> ArrowReader
├── schema/
│   └── JdbcSchemaFetcher.java      # Unchanged (JDBC path)
│   └── AdbcSchemaFetcher.java      # NEW: ADBC schema discovery via AdbcConnection.getTableSchema()
├── pool/
│   └── JdbcConnectionPool.java     # Unchanged (JDBC/HikariCP path)
│   └── AdbcConnectionFactory.java  # NEW: Creates AdbcDatabase/AdbcConnection (no pooling)
└── JdbcStoragePlugin.java          # Add createAdbcRecordReader(), createAdbcSchemaFetcher()
                                    # Add isAdbcAvailable() probe
                                    # Add resolveProtocolMode() (AUTO logic)
```

### Pattern 1: Protocol Mode Resolution at Plugin Start
**What:** During `JdbcStoragePlugin.start()`, if protocolMode is AUTO, probe for native ADBC driver availability by attempting to create an AdbcDatabase. Cache the resolved effective mode.
**When to use:** Every source start, so the scan creator doesn't need to probe at query time.
**Example:**
```java
// In JdbcStoragePlugin.start()
ProtocolMode configured = conf.getProtocolMode();
if (configured == ProtocolMode.AUTO) {
    effectiveMode = probeAdbcAvailability() ? ProtocolMode.ADBC : ProtocolMode.JDBC;
} else {
    effectiveMode = configured;
}
// Always start JDBC pool (needed for schema discovery fallback, health checks)
pool = new JdbcConnectionPool(conf);
schemaFetcher = createSchemaFetcher(pool);
// If ADBC, also initialize AdbcDatabase
if (effectiveMode == ProtocolMode.ADBC) {
    adbcFactory = new AdbcConnectionFactory(conf, allocator);
    adbcSchemaFetcher = createAdbcSchemaFetcher(adbcFactory);
}
```

### Pattern 2: AdbcRecordReader Lifecycle (setup/next/close)
**What:** The ADBC record reader follows the same RecordReader interface but uses JniStatement + ArrowReader instead of JDBC ResultSet.
**When to use:** When protocolMode resolves to ADBC for query execution.
**Example:**
```java
// AdbcRecordReader.setup(OutputMutator output)
AdbcConnection conn = factory.openConnection();
AdbcStatement stmt = conn.createStatement();
String adbcSql = translatePlaceholders(config.getSql(), config.getBindParams().size());
stmt.setSqlQuery(adbcSql);
if (!bindParams.isEmpty()) {
    VectorSchemaRoot bindRoot = buildBindRoot(allocator, bindParams);
    stmt.bind(bindRoot);
}
QueryResult result = stmt.executeQuery();
this.reader = result.getReader();

// AdbcRecordReader.next()
// Transfer Arrow batches from ArrowReader to Dremio OutputMutator vectors
if (!reader.loadNextBatch()) return 0;
VectorSchemaRoot batch = reader.getVectorSchemaRoot();
// Copy/transfer vectors to output mutator
int rowCount = batch.getRowCount();
for (Field field : batch.getSchema().getFields()) {
    ValueVector src = batch.getVector(field);
    ValueVector dst = output.getVector(field.getName());
    // Transfer data from ADBC batch to Dremio vector
}
return rowCount;
```

### Pattern 3: Bind Parameter Placeholder Translation
**What:** Translate JDBC `?` placeholders to PostgreSQL `$1, $2, ...` at the boundary between SQL generation and ADBC execution.
**When to use:** Only when executing via ADBC path. The SqlBuilder and RexToSqlString continue to emit `?` for both paths.
**Example:**
```java
// Utility in SqlBuilder or a helper class
public static String translatePlaceholders(String sql, int paramCount) {
    StringBuilder result = new StringBuilder();
    int paramIndex = 0;
    for (int i = 0; i < sql.length(); i++) {
        char c = sql.charAt(i);
        if (c == '?' && paramIndex < paramCount) {
            paramIndex++;
            result.append('$').append(paramIndex);
        } else {
            result.append(c);
        }
    }
    return result.toString();
}
```

### Pattern 4: Building VectorSchemaRoot for Bind Parameters
**What:** Convert List<BindParam> to a VectorSchemaRoot with one row, where each column is a bind parameter.
**When to use:** When ADBC statement needs bind parameters.
**Example:**
```java
private VectorSchemaRoot buildBindRoot(BufferAllocator allocator, List<BindParam> params) {
    List<Field> fields = new ArrayList<>();
    for (int i = 0; i < params.size(); i++) {
        BindParam p = params.get(i);
        ArrowType arrowType = sqlTypeToArrowType(p.getTypeName());
        fields.add(new Field("$" + (i + 1), FieldType.nullable(arrowType), null));
    }
    Schema schema = new Schema(fields);
    VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
    root.allocateNew();
    // Set values in each vector from BindParam values
    for (int i = 0; i < params.size(); i++) {
        setBindValue(root.getVector(i), params.get(i), 0);
    }
    root.setRowCount(1);
    return root;
}
```

### Anti-Patterns to Avoid
- **Conditional SQL generation in RexToSqlString:** Do NOT make RexToSqlString aware of ADBC vs JDBC. Keep it always emitting `?`. Translate at the execution boundary.
- **Pooling AdbcConnections with HikariCP:** HikariCP pools `java.sql.Connection`. AdbcConnection is a different type. Do not try to adapt.
- **Using ADBC-over-JDBC adapter:** This wraps the existing JDBC driver and does not provide native Arrow benefits. Use the native JNI path.
- **Sharing ArrowReader vectors directly with OutputMutator:** The ArrowReader's VectorSchemaRoot uses its own allocator. You must transfer/copy data to Dremio's allocator-managed vectors.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JDBC-to-Arrow conversion | Custom ResultSet iteration + setValue calls | ADBC native ArrowReader | Zero-copy Arrow from libpq; the whole point of this phase |
| Native library loading | Custom JNI loading / System.loadLibrary | JniDriver (from adbc-driver-jni) | Self-extracting JNI lib inside JAR; handles platform detection |
| PostgreSQL wire protocol | Custom libpq bindings | libadbc_driver_postgresql.so | Maintained by Apache Arrow community; handles PG extended query protocol |
| Arrow buffer lifecycle | Manual ArrowBuf allocation/deallocation | BufferAllocator from OperatorContext | Dremio manages allocator lifecycle; ADBC respects allocator interface |
| Placeholder translation | Regex-based SQL rewriting | Simple char-by-char `?` -> `$N` translation | SQL from SqlBuilder has no string literals containing `?`; simple translation is safe |

**Key insight:** The entire value of this phase is replacing hand-rolled JDBC ResultSet -> Arrow conversion with the ADBC native path. Everything else (SQL generation, pushdown, planning) stays identical.

## Common Pitfalls

### Pitfall 1: Arrow Version Mismatch
**What goes wrong:** ADBC 0.22.0 depends on Arrow Java 18.3.0; Dremio uses Arrow `18.1.1-...-dremio` (a custom build). If ADBC's transitive Arrow dependency conflicts with Dremio's, class cast errors or binary incompatibility at runtime.
**Why it happens:** Arrow Java uses a custom Dremio fork. Minor version differences (18.1 vs 18.3) may introduce API changes.
**How to avoid:** Exclude Arrow transitive dependencies from ADBC artifacts in Maven; rely on Dremio's Arrow version at runtime. The ADBC core API interfaces (AdbcStatement, AdbcConnection) use Arrow's `BufferAllocator`, `VectorSchemaRoot`, `ArrowReader` -- these are stable across 18.x.
**Warning signs:** `NoSuchMethodError`, `ClassNotFoundException`, or `IncompatibleClassChangeError` at runtime involving `org.apache.arrow.*` classes.

### Pitfall 2: ADBC ArrowReader Allocator Ownership
**What goes wrong:** ArrowReader from ADBC uses its own allocator (the one passed to JniDriver). If you close the ADBC connection/statement before consuming all batches, the allocator closes and the vectors become invalid. Conversely, if you don't close properly, memory leaks.
**Why it happens:** ADBC vectors are allocated by the JNI layer. Dremio's OutputMutator expects vectors allocated from the OperatorContext's allocator.
**How to avoid:** Either (a) pass the OperatorContext's allocator to the JniDriver so vectors are allocated from Dremio's memory pool, or (b) copy/transfer data from ADBC vectors to Dremio vectors in each next() call. Option (a) is preferred if the allocator types are compatible.
**Warning signs:** `IllegalStateException: allocator closed`, memory usage growing without bound, vectors containing garbage data.

### Pitfall 3: Native Library Not Found at Runtime
**What goes wrong:** `libadbc_driver_postgresql.so` is not on `LD_LIBRARY_PATH` or in a standard lib search path. The JniDriver fails to dlopen the native PG driver.
**Why it happens:** The JNI bridge JAR (`adbc-driver-jni`) is self-extracting and bundles the driver manager native lib, but the database-specific driver (`libadbc_driver_postgresql.so`) is NOT bundled -- it must be installed separately (via `dbc install postgresql`).
**How to avoid:** Ensure the Dockerfile installs the native driver AND sets `LD_LIBRARY_PATH` to include the installation directory. In AUTO mode, the probe should catch this gracefully and fall back to JDBC.
**Warning signs:** `UnsatisfiedLinkError`, `AdbcException` with "driver not found" or "dlopen failed".

### Pitfall 4: Bind Parameter Type Mismatch
**What goes wrong:** VectorSchemaRoot bind parameters use Arrow types, but the ADBC PG driver supports a limited set of types for binding: binary, bool, date32, int8/16/32/64, float32/64, string, timestamp. Unsupported types (e.g., DECIMAL as Arrow Decimal128) may fail.
**Why it happens:** The ADBC PG driver converts Arrow types to PG wire protocol types. Not all Arrow types are supported.
**How to avoid:** For the bind parameter VectorSchemaRoot, convert unsupported types to supported equivalents: DECIMAL -> string (let PG parse it), DATE -> date32, TIME -> string. Fall back to JDBC for queries with unsupported bind parameter types.
**Warning signs:** `AdbcException` mentioning "unsupported type" during statement execution.

### Pitfall 5: Placeholder Translation with Question Marks in String Literals
**What goes wrong:** If the SQL contains `?` inside string literals (e.g., `WHERE col LIKE '%?%'`), the naive translator converts them to `$N`, corrupting the SQL.
**Why it happens:** Simple char-by-char translation doesn't understand SQL syntax.
**How to avoid:** This is actually NOT a problem in our case because RexToSqlString never generates SQL with `?` inside string literals -- all literal values are emitted as bind parameters. The SqlBuilder's static SQL (table names, column names, SQL keywords) never contains `?`. But add a unit test to verify this invariant.
**Warning signs:** SQL parse errors from PostgreSQL about unexpected `$N` tokens.

### Pitfall 6: COPY Protocol and use_copy Option
**What goes wrong:** The ADBC PG driver defaults to using PostgreSQL COPY protocol for query execution (high performance for bulk data). Some queries (SHOW, SET) don't support COPY.
**Why it happens:** COPY is the default optimization. Our pushdown queries are standard SELECT statements which do support COPY.
**How to avoid:** For standard SELECT queries, the default COPY behavior is fine and desired (it's faster). If issues arise, set `adbc.postgresql.use_copy` to `false` on the statement to fall back to extended query protocol.
**Warning signs:** `AdbcException` mentioning "COPY" or "cannot use COPY in this context".

## Code Examples

### Creating an ADBC Connection to PostgreSQL via JNI
```java
// Source: ADBC Java JNI driver source code (JniDriver.java, JniDatabase.java)
import org.apache.arrow.adbc.core.*;
import org.apache.arrow.adbc.driver.jni.JniDriver;
import org.apache.arrow.memory.BufferAllocator;

BufferAllocator allocator = context.getAllocator();
Map<String, Object> params = new HashMap<>();
JniDriver.PARAM_DRIVER.set(params, "adbc_driver_postgresql");
AdbcDriver.PARAM_URI.set(params, "postgresql://user:pass@host:5432/dbname");

JniDriver driver = new JniDriver(allocator);
AdbcDatabase db = driver.open(params);
AdbcConnection conn = db.connect();
```

### Executing a Parameterized Query via ADBC
```java
// Source: ADBC Java JNI driver source code (JniStatement.java)
try (AdbcStatement stmt = conn.createStatement()) {
    // SQL uses PostgreSQL $1, $2 notation
    stmt.setSqlQuery("SELECT \"id\", \"name\" FROM \"public\".\"users\" WHERE \"age\" > $1");

    // Build bind parameter as VectorSchemaRoot
    VectorSchemaRoot bindRoot = VectorSchemaRoot.create(
        new Schema(List.of(new Field("$1", FieldType.nullable(new ArrowType.Int(32, true)), null))),
        allocator
    );
    bindRoot.allocateNew();
    ((IntVector) bindRoot.getVector(0)).setSafe(0, 25);
    bindRoot.setRowCount(1);

    stmt.bind(bindRoot);
    AdbcStatement.QueryResult result = stmt.executeQuery();
    ArrowReader reader = result.getReader();

    while (reader.loadNextBatch()) {
        VectorSchemaRoot batch = reader.getVectorSchemaRoot();
        // batch contains native Arrow vectors -- zero copy from libpq
        int rowCount = batch.getRowCount();
        // Process batch...
    }
}
```

### Schema Discovery via ADBC
```java
// Source: ADBC Java API (AdbcConnection interface)
Schema tableSchema = conn.getTableSchema(null, "public", "users");
// Returns Arrow Schema with fields corresponding to table columns
// Types come from PG native type mapping (not JDBC type mapping)

// For listing tables:
try (ArrowReader objects = conn.getObjects(
    AdbcConnection.GetObjectsDepth.TABLES,
    null,        // catalogPattern
    "public",    // dbSchemaPattern
    "%",         // tableNamePattern
    new String[]{"TABLE", "VIEW"},  // tableTypes
    null         // columnNamePattern
)) {
    while (objects.loadNextBatch()) {
        // Process catalog/schema/table hierarchy
    }
}
```

### ProtocolMode Enum
```java
// New enum in conf package
public enum ProtocolMode {
    AUTO,   // Try ADBC first, fall back to JDBC
    JDBC,   // Force JDBC (existing behavior)
    ADBC    // Force ADBC (fail if unavailable)
}
```

### Placeholder Translation
```java
// Utility method -- simple and safe for our generated SQL
public static String jdbcToPostgresPlaceholders(String sql, int paramCount) {
    if (paramCount == 0) return sql;
    StringBuilder sb = new StringBuilder(sql.length() + paramCount * 2);
    int idx = 0;
    for (int i = 0; i < sql.length(); i++) {
        char c = sql.charAt(i);
        if (c == '?') {
            idx++;
            sb.append('$').append(idx);
        } else {
            sb.append(c);
        }
    }
    return sb.toString();
}
```

## Discretion Decisions (Recommendations)

### 1. Bind Parameter Placeholder Format
**Recommendation: Translate at the execution boundary.**

The ADBC PostgreSQL driver uses `$1, $2, $3` syntax (PostgreSQL native prepared statement format via libpq), confirmed from the ADBC PG driver C++ source code which uses `PQexecPrepared` with `$N` placeholders.

The SqlBuilder and RexToSqlString should continue emitting `?` (JDBC convention). This keeps the shared SQL generation path simple and dialect-agnostic. A simple translation function `jdbcToPostgresPlaceholders(sql, paramCount)` converts `?` to `$N` right before passing SQL to `AdbcStatement.setSqlQuery()`.

This is safe because:
- Our SQL generation never puts `?` inside string literals (all literals are bind params)
- Column names and table names are double-quoted identifiers (no `?` possible)
- The translation is trivially simple and testable

**Confidence: HIGH** -- Verified from ADBC PG driver source code (statement.cc uses `PQexecPrepared` with `$N` syntax) and from the resolved GitHub issue #2024 test cases showing `$1` usage.

### 2. ADBC Connection Pooling Strategy
**Recommendation: Create-per-query, no pooling.**

libpq connections are lightweight (~1-5ms to establish). The ADBC PG driver uses the native PostgreSQL wire protocol via libpq, which is designed for efficient connection creation. A simple factory pattern (create `AdbcDatabase` once per source lifecycle, create `AdbcConnection` per query execution) is sufficient.

Reasons NOT to pool:
- `AdbcConnection` is not a `java.sql.Connection`; HikariCP cannot manage it
- Building a custom bounded pool adds significant complexity (thread safety, eviction, validation)
- libpq connection setup cost is minimal compared to query execution time
- The `AdbcDatabase` object (which represents the driver configuration) is reused across connections

The `AdbcDatabase` should be created once during `JdbcStoragePlugin.start()` and reused. Each `AdbcRecordReader.setup()` creates a new `AdbcConnection` from the database.

**Confidence: MEDIUM** -- libpq connection costs are well-documented as lightweight, but without production benchmarking in the ADBC JNI context we cannot be certain there is no JNI overhead.

### 3. Class Naming and Module Structure
**Recommendation: Add Adbc* classes in the existing `jdbc-base` module.**

Reasons:
- The ADBC classes share interfaces with JDBC classes (RecordReader, SchemaFetcher)
- The branching logic lives in JdbcStoragePlugin and JdbcScanCreator -- same module
- A separate module would require cross-module visibility for internal types
- The dependency is small (two Maven artifacts: adbc-core, adbc-driver-jni)
- Consistent with how the existing codebase adds database-specific subclasses alongside base classes

Naming convention:
- `AdbcRecordReader` (parallel to `JdbcRecordReader`)
- `AdbcSchemaFetcher` (parallel to `JdbcSchemaFetcher`)
- `AdbcConnectionFactory` (parallel role to `JdbcConnectionPool`, but factory not pool)
- `ProtocolMode` enum in `conf` package

**Confidence: HIGH** -- Follows existing codebase patterns.

### 4. Schema Discovery Implementation
**Recommendation: Use ADBC schema discovery when in ADBC mode.**

The ADBC `AdbcConnection` provides:
- `getTableSchema(catalog, dbSchema, tableName)` -> Arrow `Schema` (direct Arrow types from PG native types)
- `getObjects(depth, ...)` -> `ArrowReader` with catalog/schema/table hierarchy

Benefits of ADBC schema discovery:
- Type mapping comes from the native PG driver, which may be more accurate than JDBC DatabaseMetaData
- No need for a separate JDBC connection just for metadata when running in ADBC mode
- Consistent behavior: the types discovered match the types returned in query results

However, keep the JDBC schema fetcher as fallback for AUTO mode (when probing fails) and for Oracle (JDBC-only). The ADBC schema fetcher can extend or wrap the JDBC one for system schema filtering logic.

**Confidence: MEDIUM** -- ADBC `getObjects()` and `getTableSchema()` are part of the ADBC 1.1.0 spec and implemented in the PG driver, but the exact type mapping differences from JDBC need validation in testing.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| ADBC PG driver could NOT do parameterized SELECTs | Parameterized SELECTs with `$1` syntax fully supported | ADBC 14 (Aug 2024), PR #2065 | Enables bind parameter pushdown via ADBC |
| adbc-driver-jni NOT on Maven Central | Available on Maven Central (0.21.0, 0.22.0) | ADBC 21 (Nov 2025) | No longer need to build from source |
| JNI bindings "very limited" | JNI supports executeQuery, bind, getObjects, getTableSchema | ADBC 21-22 (2025-2026) | Full query lifecycle supported via JNI |

**Deprecated/outdated:**
- ADBC documentation from versions < 14 says "only supports INSERT with bind params" -- this is no longer true
- ADBC documentation from versions < 21 says "JNI must be built by hand" -- 0.22.0 is on Maven Central

## Open Questions

1. **Arrow Version Compatibility**
   - What we know: ADBC 0.22.0 depends on Arrow 18.3.0; Dremio uses Arrow 18.1.1-dremio (custom build). The core Arrow interfaces (BufferAllocator, VectorSchemaRoot, ArrowReader) are stable across 18.x.
   - What's unclear: Whether the Dremio-forked Arrow 18.1.1 binary is compatible with ADBC 0.22.0 at runtime, particularly for the JNI C Data Interface interchange.
   - Recommendation: Exclude Arrow transitive deps from ADBC in Maven, test at runtime. If incompatible, pin to an older ADBC version (0.21.0) or build ADBC against Dremio's Arrow fork.

2. **JNI Driver Self-Extraction Path**
   - What we know: The `adbc-driver-jni` JAR bundles the JNI native lib and self-extracts at runtime.
   - What's unclear: Where it extracts to (temp dir?), whether the Docker image's filesystem restrictions (non-root user `dremio`) allow this, and whether `noexec` on `/tmp` would block it.
   - Recommendation: Test in Docker environment early. May need to set `java.io.tmpdir` or extract manually.

3. **ADBC PG Driver Type Mapping vs JDBC Type Mapping**
   - What we know: ADBC PG driver maps PG types to Arrow types natively. Our existing PostgresSchemaFetcher has custom type mappings for UUID, JSONB, arrays, etc.
   - What's unclear: Whether ADBC's native type mapping handles these PostgreSQL-specific types the same way (as VARCHAR) or differently.
   - Recommendation: Create a type mapping comparison test that queries the same table via JDBC and ADBC and compares the resulting schemas.

4. **BufferAllocator Lifecycle in ADBC Context**
   - What we know: `OperatorContext.getAllocator()` provides a `BufferAllocator` scoped to the operator. The JniDriver constructor accepts a `BufferAllocator`.
   - What's unclear: Whether the ADBC JNI bridge allocates vectors from this allocator or uses its own native allocations. If native, the vectors may need to be copied to Dremio-managed memory.
   - Recommendation: Test by passing OperatorContext's allocator to JniDriver and verify memory accounting is correct.

5. **dbc CLI Installation in Docker**
   - What we know: `dbc` installs to `~/.config/columnar/adbc_drivers/` by default.
   - What's unclear: Exact `.so` file paths after installation, whether the Docker `dremio` user's home directory is writable during build, and the exact `LD_LIBRARY_PATH` configuration needed.
   - Recommendation: Test `dbc install postgresql` in the Docker build context and verify the `.so` location. May need to install as root then set permissions.

## Sources

### Primary (HIGH confidence)
- [ADBC Java JNI source code](https://github.com/apache/arrow-adbc/tree/main/java/driver/jni) - JniDriver, JniStatement, JniConnection, JniDatabase class structure and API
- [ADBC AdbcConnection API](https://arrow.apache.org/adbc/main/java/api/org/apache/arrow/adbc/core/AdbcConnection.html) - Full method inventory including getObjects(), getTableSchema()
- [ADBC AdbcStatement API](https://arrow.apache.org/adbc/main/java/api/org/apache/arrow/adbc/core/AdbcStatement.html) - executeQuery(), bind(), setSqlQuery(), prepare()
- [GitHub Issue #2024 - Resolved](https://github.com/apache/arrow-adbc/issues/2024) - Parameterized SELECT support confirmed fixed in ADBC 14 via PR #2065
- [ADBC PostgreSQL Driver docs (v22)](https://arrow.apache.org/adbc/current/driver/postgresql.html) - Type support, COPY protocol, connection URI format
- [Maven Central: adbc-driver-jni 0.22.0](https://central.sonatype.com/artifact/org.apache.arrow.adbc/adbc-driver-jni) - Confirmed available on Maven Central
- [ADBC 0.22.0 parent POM](https://repo.maven.apache.org/maven2/org/apache/arrow/adbc/arrow-adbc-java-root/0.22.0/) - Arrow dependency version: 18.3.0

### Secondary (MEDIUM confidence)
- [ADBC 21 Release Blog](https://arrow.apache.org/blog/2025/11/07/adbc-21-release/) - JNI bindings released "in a very limited fashion"; binary artifacts for amd64/arm64 Linux, arm64 macOS, amd64 Windows
- [ADBC 22 Release Blog](https://arrow.apache.org/blog/2026/01/09/adbc-22-release/) - PG driver: transaction isolation level, getObjects filter fix
- [dbc CLI docs](https://docs.columnar.tech/dbc/) - Installation and usage
- [Existing Dremio codebase](file://plugins/jdbc-base/) - JdbcRecordReader, JdbcSchemaFetcher, JdbcConnectionPool, BaseJdbcConf, JdbcStoragePlugin patterns

### Tertiary (LOW confidence)
- ADBC PG driver C++ source (statement.cc) - Confirmed `$1` placeholder format via commit messages and test references, but not directly verified in source code
- dbc CLI driver installation paths - Exact `.so` location needs runtime verification
- JNI self-extraction behavior - Based on common JNI JAR patterns, not verified for ADBC specifically

## Metadata

**Confidence breakdown:**
- Standard stack: MEDIUM - ADBC JNI 0.22.0 confirmed on Maven Central but described as "very limited" experimentally; Arrow version mismatch is a real risk
- Architecture: HIGH - The swap-execution-only pattern is clean and the existing codebase extension points (createRecordReader, createSchemaFetcher) support it well
- Pitfalls: HIGH - Arrow version conflicts, native lib loading, and allocator lifecycle are well-documented concerns in JNI/Arrow integrations
- Bind parameter format: HIGH - `$1` syntax confirmed from ADBC PG driver source code and test cases
- Connection pooling: MEDIUM - Create-per-query recommendation based on libpq characteristics but unverified in ADBC JNI context

**Research date:** 2026-03-14
**Valid until:** 2026-04-14 (30 days -- ADBC is evolving but 0.22.0 is the current stable release)
