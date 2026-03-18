# Phase 40: pgvector Type Support — Research

**Researched:** 2026-03-18
**Domain:** PostgreSQL pgvector extension — JDBC schema discovery, type mapping, and vector data reading
**Confidence:** HIGH (codebase verified + authoritative external sources)

## Summary

pgvector is a PostgreSQL extension that adds a `vector(N)` column type for storing N-dimensional float vectors (embeddings). The JDBC layer (pgjdbc 42.7.x, which is our current driver) reports `vector` columns as `DATA_TYPE = java.sql.Types.OTHER` with `TYPE_NAME = "vector"`. The ADBC layer has no native vector support and returns the column as raw binary bytes. Neither path works out of the box.

The safest and most compatible Dremio mapping is `VARCHAR` (text). The reason: pgjdbc's `getString()` on a vector column returns the standard PostgreSQL text format `[0.1,0.2,0.3]`. This requires zero additional dependencies, produces no type-system complications, and is consistent with how we already handle other unstructured PG-specific types (UUID, JSONB, etc.). The text representation is round-trippable — users can cast it back to `vector` in pushdown expressions if needed.

A `LIST<FLOAT4>` mapping is architecturally possible (Dremio's Arrow type system supports `ListVector`) but requires non-trivial parsing logic in `JdbcRecordReader.writeValue()` and introduces writer complexity. It is deferred for a future phase and documented as an open question.

**Primary recommendation:** Map `vector(N)` to `ArrowType.Utf8` (VARCHAR) in `PostgresSchemaFetcher.mapJdbcType()`, read via `rs.getString()` in `JdbcRecordReader` (already the fallback), and add an integration test with the `pgvector/pgvector:pg16` Docker image.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.postgresql:postgresql` | 42.7.3 (project) | JDBC driver — reports vector as `Types.OTHER`, typeName `"vector"` | Already on classpath |
| `pgvector/pgvector:pg16` Docker image | latest pg16 | TestContainers image with pgvector extension pre-installed | Official pgvector image, used by Spring AI tests |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.pgvector:pgvector` | 0.1.6 | `PGvector` wrapper class for vector encoding/decoding | Only needed if round-trip float[] access is required; NOT needed for VARCHAR mapping |
| TestContainers `org.testcontainers:postgresql` | (project version) | Container infrastructure — already on classpath in test | Used with `asCompatibleSubstituteFor("postgres")` pattern |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| VARCHAR | `LIST<FLOAT4>` (ArrowType.List with Float4 child) | Better semantic type — but requires parsing `[a,b,c]` string to float[] in writeValue(); ListVector writing is complex; deferred |
| VARCHAR | `VARBINARY` | No benefit; binary representation has no standard format for pgvector values via plain JDBC |
| pgvector/pgvector:pg16 image | ankr/pgvector | ankr/pgvector is older, less maintained; pgvector/pgvector is the official image |

**Installation for tests:**
```xml
<!-- No new runtime dependency for VARCHAR approach -->
<!-- DremioPostgresContainer subclass changes to use pgvector/pgvector:pg16 image -->
```

## Architecture Patterns

### Recommended Project Structure

Changes are confined to two files plus one new test class:

```
plugins/jdbc-postgresql/src/main/java/.../postgresql/
├── PostgresSchemaFetcher.java   ← add "vector" case to mapJdbcType()
└── (no other changes needed for VARCHAR mapping)

plugins/jdbc-postgresql/src/test/java/.../postgresql/
├── DremioPostgresContainer.java          ← change IMAGE to pgvector/pgvector:pg16
├── TestPostgresTypeMapping.java          ← add vector column assertions
└── TestPgvectorTypeMapping.java          ← new: vector-specific test class
```

### Pattern 1: typeName-Based Override in PostgresSchemaFetcher

**What:** Add `"vector"` to the switch in `PostgresSchemaFetcher.mapJdbcType()`.

**When to use:** Whenever typeName equals `"vector"` regardless of jdbcType.

**Why this works:** pgjdbc 42.7.x reports vector columns with `DATA_TYPE = Types.OTHER` (JDBC type 1111) and `TYPE_NAME = "vector"`. The base `JdbcSchemaFetcher.mapJdbcType()` has no mapping for `Types.OTHER` — it falls through to `JdbcToArrowUtils.getArrowTypeFromJdbcType()` which returns `null`, triggering a fallback to `Utf8` with a WARN log. Adding explicit `"vector"` handling in the subclass silences the warning and makes the intent clear.

```java
// Source: PostgresSchemaFetcher.mapJdbcType() — add to existing switch
case "vector":
    // pgvector extension type — stored as text "[x1,x2,...,xN]"
    // DATA_TYPE=Types.OTHER, TYPE_NAME="vector"
    return new ArrowType.Utf8();
```

**Example (complete switch after change):**
```java
@Override
protected ArrowType mapJdbcType(int jdbcType, String typeName, int precision, int scale) {
    if (typeName != null) {
        String lower = typeName.toLowerCase();
        switch (lower) {
            case "uuid":
            case "jsonb":
            case "json":
            case "money":
            case "interval":
            case "cidr":
            case "inet":
            case "macaddr":
            case "macaddr8":
            case "hstore":
            case "tsvector":
            case "tsquery":
            case "vector":       // ← pgvector extension: vector(N) type
                return new ArrowType.Utf8();
            default:
                break;
        }
        if (lower.startsWith("_")) {
            return new ArrowType.Utf8();
        }
    }
    // ... rest unchanged
}
```

### Pattern 2: TestContainers with pgvector Image

**What:** Replace the Docker image in `DremioPostgresContainer` from `postgres:16-alpine` to `pgvector/pgvector:pg16`.

**Why this works:** The `pgvector/pgvector:pg16` image is a drop-in PostgreSQL 16 image with the pgvector extension pre-installed. It works identically with the existing `PostgreSQLContainer` subclass. `asCompatibleSubstituteFor` is NOT needed here because we subclass `PostgreSQLContainer` directly (not using the JDBC URL-based container startup).

```java
// Source: DremioPostgresContainer.java
private static final String IMAGE = "pgvector/pgvector:pg16";
// Was: "postgres:16-alpine"
```

**IMPORTANT:** Changing the base image affects ALL existing tests that use `PostgresTestContainer.PG`. Verify that all existing type mapping, schema discovery, pushdown, join, and ADBC tests still pass after the image change. The pgvector image is a strict superset of plain PostgreSQL.

### Pattern 3: Reading Vector Values via JdbcRecordReader (No Change Needed)

**What:** For VARCHAR mapping, `JdbcRecordReader.writeValue()` already handles `VarCharVector` by calling `rs.getString(colPosition)`.

pgjdbc's `getString()` on a `vector` column returns the PostgreSQL text representation: `[0.1,0.2,0.3]`. This is a compact, lossless string representation of the float array.

**No code change needed in JdbcRecordReader.** The existing VarCharVector branch handles it:
```java
// JdbcRecordReader.writeValue() — existing code, no change
} else if (vec instanceof VarCharVector) {
    String val = rs.getString(colPosition);
    if (!rs.wasNull() && val != null) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        ((VarCharVector) vec).setSafe(index, bytes, 0, bytes.length);
    }
}
```

### Pattern 4: ADBC Path — Disable for Vector Columns (Defensive)

**What:** The ADBC PostgreSQL driver (version 0.22.0, current in this project) does NOT support the pgvector `vector` type. When the ADBC driver encounters it, it returns a `VarBinaryVector` with raw wire-protocol bytes (not a usable float representation).

**Resolution:** `AdbcSchemaFetcher.getTableSchema()` calls `conn.getTableSchema()` which returns the ADBC-native Arrow schema. For vector columns, ADBC returns a `Binary` type (VarBinary). The `normalizeField()` method in `AdbcSchemaFetcher` does not currently handle this.

**Decision point for planner:** Either (a) override `normalizeField()` in a PostgreSQL-specific `AdbcSchemaFetcher` subclass to remap Binary-typed vector columns to Utf8, OR (b) accept that the ADBC path returns binary blobs for vector columns (usable but not the same format as the JDBC path). The JDBC path is simpler and sufficient for Phase 40.

### Anti-Patterns to Avoid

- **Don't use `com.pgvector:pgvector` dependency for pure read path.** The VARCHAR approach requires zero new runtime dependencies. The pgvector-java library (`PGvector.registerTypes()`, `PGvector` class) is only needed if you need to perform vector distance calculations from Java code — not for basic schema discovery and data reading.
- **Don't map to `ArrowType.FixedSizeList`.** Dremio's `BasicTypeHelper.getValueVectorClass()` does NOT have a `FIXED_SIZE_LIST` case — it throws `UnsupportedOperationException`. Only `ArrowType.List.INSTANCE` (variable-length LIST) is supported.
- **Don't try to parse the vector dimension from `COLUMN_SIZE`.** pgjdbc reports `COLUMN_SIZE = 0` for vector columns (the dimension is not exposed as precision in JDBC metadata). The dimension is embedded in the typmod and is not accessible via standard `getColumns()`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Detecting vector columns | Pattern-matching on arbitrary heuristics | Check `typeName.equalsIgnoreCase("vector")` in mapJdbcType() | Exact match is sufficient; pgvector always uses the type name "vector" |
| Reading vector values as strings | Custom binary decoder | `rs.getString()` | pgjdbc already returns the text representation `[x,y,z]` via getString |
| Float32 array representation | Custom ListVector writing | Defer to Phase 41+ | ListVector writing is complex; VARCHAR roundtrip is sufficient for Phase 40 |
| pgvector Docker image | Custom Dockerfile | `pgvector/pgvector:pg16` | Official image, maintained by pgvector authors |

**Key insight:** pgjdbc handles the pgvector text format transparently through `getString()`. No special type handling or custom driver configuration is needed for the VARCHAR approach.

## Common Pitfalls

### Pitfall 1: Assuming `typeName` Won't Be Null for Extension Types
**What goes wrong:** `typeName` is null for some PG edge cases.
**Why it happens:** `meta.getColumns()` returns null for `TYPE_NAME` in rare edge cases (e.g., composite types or columns in `information_schema` views).
**How to avoid:** Always null-check `typeName` before switching on it — the existing code already does this (`if (typeName != null)`).
**Warning signs:** `NullPointerException` in `mapJdbcType()` during schema discovery.

### Pitfall 2: `COLUMN_SIZE` for vector is 0 or Unreliable
**What goes wrong:** Code that uses `precision` to determine vector dimensionality (e.g., to construct `FixedSizeList(1536)`) gets 0.
**Why it happens:** pgjdbc does not map the vector type's typmod (which encodes dimension) to the `COLUMN_SIZE` JDBC column. The dimension is stored in `pg_type` / `pg_attribute.atttypmod` but is not surfaced through `DatabaseMetaData.getColumns()`.
**How to avoid:** Do not rely on `precision` for vector columns. Use VARCHAR and store the text representation.
**Warning signs:** `COLUMN_SIZE = 0` or `COLUMN_SIZE = -1` for vector columns.

### Pitfall 3: Changing the Docker Image Breaks All PG Tests
**What goes wrong:** Replacing `postgres:16-alpine` with `pgvector/pgvector:pg16` for ALL tests; if the pgvector image has a startup difference, all existing tests fail.
**Why it happens:** `DremioPostgresContainer` is a shared singleton used by all PostgreSQL integration tests via `PostgresTestContainer.PG`.
**How to avoid:** Keep the existing `DremioPostgresContainer` as is and either (a) change the IMAGE constant (safe — pgvector image is fully backward compatible), or (b) create a new `DremioPostgresVectorContainer` for pgvector-specific tests. Option (a) is simpler.
**Warning signs:** All existing PG integration tests fail after image change (check container startup logs).

### Pitfall 4: ADBC Path Returns VarBinary, Not Utf8, for Vector Columns
**What goes wrong:** ADBC schema fetcher reports the vector column as `Binary` (VarBinary in Arrow), while JDBC reports it as `Utf8`. If the same table is accessed via both paths with different schemas, there's a schema conflict.
**Why it happens:** ADBC PG driver (0.22.0) has no native vector support and falls back to binary. The `AdbcSchemaFetcher.normalizeField()` method does not override this to Utf8.
**How to avoid:** If ADBC is enabled for PostgreSQL, add vector handling to `AdbcSchemaFetcher.normalizeField()` or create a `PostgresAdbcSchemaFetcher` subclass (analogous to `PostgresSchemaFetcher`). This is out of scope for Phase 40 unless ADBC is the primary path.
**Warning signs:** `AdbcRecordReader` writes null values for vector columns, or `transferVector()` logs "Unsupported vector transfer".

### Pitfall 5: `CREATE EXTENSION vector` Must Precede Table Creation in Tests
**What goes wrong:** Test setup creates a table with `vector(N)` column type before calling `CREATE EXTENSION IF NOT EXISTS vector`, causing a SQL error.
**Why it happens:** pgvector is not enabled by default — it's an extension.
**How to avoid:** Always call `CREATE EXTENSION IF NOT EXISTS vector` as the first DDL statement in test setup.
**Warning signs:** `ERROR: type "vector" does not exist` in test output.

## Code Examples

Verified patterns from official sources and codebase analysis:

### What getColumns() Returns for vector(1536)
```
DATA_TYPE:   1111  (java.sql.Types.OTHER)
TYPE_NAME:   "vector"
COLUMN_SIZE: 0     (dimension NOT reported here)
DECIMAL_DIGITS: 0
```
Source: pgjdbc TypeInfoCache — unrecognized extension types receive `Types.OTHER`.

### PostgresSchemaFetcher.mapJdbcType() — Add vector case
```java
// Source: plugins/jdbc-postgresql/.../PostgresSchemaFetcher.java
case "vector":
    // pgvector extension type — map to VARCHAR (text representation "[x1,x2,...,xN]")
    return new ArrowType.Utf8();
```

### DremioPostgresContainer — Switch to pgvector image
```java
// Source: DremioPostgresContainer.java
private static final String IMAGE = "pgvector/pgvector:pg16";
// Previously: "postgres:16-alpine"
```

### Test setup — Enable extension and create table
```java
PostgresTestContainer.executeSql("CREATE EXTENSION IF NOT EXISTS vector");
PostgresTestContainer.executeSql(
    "CREATE TABLE IF NOT EXISTS vector_test (" +
    "  id SERIAL PRIMARY KEY," +
    "  embedding vector(3)," +
    "  name TEXT" +
    ")");
PostgresTestContainer.executeSql(
    "INSERT INTO vector_test (embedding, name) VALUES " +
    "('[1.0,2.0,3.0]', 'row1'), " +
    "('[0.1,0.2,0.3]', 'row2')");
```

### Test assertion — vector column maps to Utf8
```java
BatchSchema schema = schemaFetcher.getTableSchema("public", "vector_test");
Field embeddingField = findField(schema, "embedding");
assertNotNull("embedding field must exist", embeddingField);
assertEquals(
    "vector column must map to Utf8",
    ArrowType.Utf8.class, embeddingField.getType().getClass());
```

### Test assertion — vector value readback format
```java
// Verify the text format via direct JDBC
try (Connection conn = pool.getConnection();
     PreparedStatement ps = conn.prepareStatement(
         "SELECT embedding::text FROM vector_test WHERE id = 1");
     ResultSet rs = ps.executeQuery()) {
    assertTrue(rs.next());
    String vectorText = rs.getString("embedding");
    assertNotNull(vectorText);
    assertTrue("Must start with [", vectorText.startsWith("["));
    assertTrue("Must end with ]", vectorText.endsWith("]"));
}
```

### Future Pattern (Phase 41+): LIST<FLOAT4> mapping
```java
// NOT for Phase 40. For reference if LIST approach is implemented later:
// ArrowType for a LIST<FLOAT4> field:
Field float4Field = new Field(
    "value",
    FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
    null);
Field vectorField = new Field(
    "embedding",
    FieldType.nullable(ArrowType.List.INSTANCE),
    Collections.singletonList(float4Field));
// Writing to ListVector requires startingNewValue() / endValue() pattern.
// Parse "[0.1,0.2,0.3]" → split by comma → Float.parseFloat() each element.
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| ankane/pgvector Docker image | pgvector/pgvector Docker image | 2023 | Official image; ankane/pgvector is older and less maintained |
| Manual registerTypes() for every connection | No registration needed for VARCHAR approach | N/A | Not applicable if not using PGvector class |
| ADBC always fails on vector | ADBC returns VarBinary (raw bytes) | ADBC 0.22.0 | Not usable as float values, but doesn't crash |

**Deprecated/outdated:**
- `ankane/pgvector` Docker image: replaced by `pgvector/pgvector`; still works but use official image
- `PGvector.addVectorType(conn)` (old API): replaced by `PGvector.registerTypes(conn)` in pgvector-java 0.1.6

## Open Questions

1. **ADBC path for vector columns**
   - What we know: ADBC 0.22.0 returns VarBinary (raw bytes) for vector columns, not the text format
   - What's unclear: Whether the VarBinary contents are the PostgreSQL wire-format binary (float array in big-endian) or something else
   - Recommendation: Out of scope for Phase 40. The JDBC path is the primary path for vector support. If ADBC is ever used for PG vector tables, add a `normalizeField()` override in `AdbcSchemaFetcher` or a new `PostgresAdbcSchemaFetcher` subclass.

2. **Should we offer LIST<FLOAT4> as the mapping?**
   - What we know: Dremio Arrow type system supports `ListVector` (ArrowType.List.INSTANCE with Float4 child); `TypeHelper.getValueVectorClass()` handles `MinorType.LIST` → `ListVector.class`
   - What's unclear: Whether ListVector writing in `JdbcRecordReader.writeValue()` would work correctly with the scan operator batch lifecycle; whether Dremio's SQL layer can filter/project on LIST columns efficiently
   - Recommendation: Use VARCHAR for Phase 40. Design the code so the switch case for "vector" in `PostgresSchemaFetcher` can be changed from `Utf8` to `List` in a future phase.

3. **Vector dimension in schema**
   - What we know: `getColumns()` returns `COLUMN_SIZE = 0` for vector columns; the dimension is not available via standard JDBC metadata
   - What's unclear: Whether exposing dimension info (e.g., as column metadata) is useful for Dremio query planning
   - Recommendation: Ignore dimension for Phase 40. If needed later, query `pg_attribute.atttypmod - 4` for the stored dimension.

## Sources

### Primary (HIGH confidence)
- Codebase: `PostgresSchemaFetcher.java` — existing switch statement and mapJdbcType() contract
- Codebase: `JdbcSchemaFetcher.java` — base class; mapJdbcType() fallback to Utf8 on null
- Codebase: `JdbcRecordReader.writeValue()` — VarCharVector branch handles getString() result
- Codebase: `BasicTypeHelper.java` — confirms `MinorType.LIST` → `ListVector.class`; no `FIXED_SIZE_LIST` support
- pgjdbc TypeInfoCache: confirmed via WebFetch — extension types not in built-in map receive `Types.OTHER`
- ADBC PostgreSQL driver docs (arrow.apache.org/adbc/current): confirmed — unknown types return Binary (VarBinary)

### Secondary (MEDIUM confidence)
- pgvector-java SpringJDBCTest.java (github.com/pgvector/pgvector-java): shows `getObject()` returns PGobject, `getValue()` gives text string
- Testcontainers pgvector module docs (testcontainers.com): confirmed `pgvector/pgvector:pg16` image name and `asCompatibleSubstituteFor` pattern
- Docker Hub pgvector/pgvector: pg16 tag is current stable for PostgreSQL 16

### Tertiary (LOW confidence, for reference only)
- Various blog posts on pgvector JDBC integration — not verified against our specific driver version

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — driver version confirmed from pom.xml; image name confirmed from official TestContainers docs
- Architecture: HIGH — mapJdbcType() override pattern is the established pattern in our codebase (PostgresSchemaFetcher already does this for 13+ types)
- JDBC type code (Types.OTHER): HIGH — confirmed directly from pgjdbc TypeInfoCache source via WebFetch
- ADBC behavior (VarBinary fallback): HIGH — confirmed from official ADBC PG driver docs
- ListVector Dremio support: HIGH — confirmed from BasicTypeHelper.java source
- Pitfalls: MEDIUM — based on code analysis and known pgjdbc behavior

**Research date:** 2026-03-18
**Valid until:** 2026-09-18 (stable domain; pgvector JDBC behavior unlikely to change in 6 months)
