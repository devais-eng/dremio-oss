---
phase: 40-pgvector-type-support-embedding-column-types-and-schema-discovery-for-vector-real-array-mapping
plan: 01
subsystem: jdbc-postgresql / jdbc-base
tags: [pgvector, arrow, schema-discovery, list-vector, integration-tests]
dependency_graph:
  requires: []
  provides:
    - PostgresSchemaFetcher maps vector(N) columns to LIST<FLOAT4>
    - JdbcRecordReader parses [x,y,z] text into ListVector<Float4> child values
    - pgvector/pgvector:pg16 container image for all PostgreSQL integration tests
  affects:
    - plugins/jdbc-postgresql (PostgresSchemaFetcher, DremioPostgresContainer)
    - plugins/jdbc-base (JdbcRecordReader, JdbcSchemaFetcher)
tech_stack:
  added: []
  patterns:
    - ArrowType.List.INSTANCE sentinel in mapJdbcType() + getTableSchema() override intercepts sentinel
    - ListVector write: getOffsetBuffer().getInt(index*4) / setSafe children / setInt end offset / setNotNull
    - pgvector/pgvector:pg16 as drop-in replacement for postgres:16-alpine
key_files:
  created:
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestPgvectorTypeMapping.java
  modified:
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresSchemaFetcher.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/JdbcRecordReader.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/schema/JdbcSchemaFetcher.java
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/DremioPostgresContainer.java
decisions:
  - "ArrowType.List.INSTANCE used as sentinel in mapJdbcType() return — identity comparison (==) in getTableSchema() override intercepts it to build proper LIST<FLOAT4> field with $data$ Float4 child"
  - "getPool() protected getter added to JdbcSchemaFetcher — avoids changing private field visibility while enabling PostgresSchemaFetcher to override getTableSchema() with pool access"
  - "ListVector offset buffer uses literal 4 (INT32 width) — OFFSET_WIDTH constant not available on ListVector class in Arrow 18.1.1-dremio; BaseVariableWidthVector has it but ListVector is not a subclass"
  - "DremioPostgresContainer IMAGE changed from postgres:16-alpine to pgvector/pgvector:pg16 — shared container used by all PG integration tests; pgvector image is a strict superset and fully backward compatible"
  - "testVectorValueRoundtrip uses raw JDBC getString() to verify [x,y,z] text format — confirms pgjdbc contract before ListVector write path is exercised end-to-end in Phase 41+"
metrics:
  duration_min: 15
  completed_date: "2026-03-18"
  tasks_completed: 2
  files_modified: 5
---

# Phase 40 Plan 01: pgvector Type Mapping (LIST<FLOAT4>) Summary

**One-liner:** pgvector `vector(N)` columns mapped to `LIST<FLOAT4>` (ArrowType.List + Float4 child) with ListVector write branch in JdbcRecordReader, backed by pgvector/pgvector:pg16 container image.

## What Was Built

### Task 1: LIST<FLOAT4> type mapping + ListVector parsing + pgvector container image

Three source file changes:

**JdbcSchemaFetcher.java (jdbc-base):**
- Added `protected JdbcConnectionPool getPool()` getter — enables `PostgresSchemaFetcher` to override `getTableSchema()` with pool access without modifying the private field

**PostgresSchemaFetcher.java (jdbc-postgresql):**
- Added `case "vector":` to `mapJdbcType()` switch — returns `ArrowType.List.INSTANCE` as a sentinel
- Added `getTableSchema()` override — intercepts the `ArrowType.List.INSTANCE` sentinel and constructs a proper `Field` with a `Float4` child (`$data$`, FloatingPoint(SINGLE))
- Updated Javadoc to list `vector(N) (pgvector) -> LIST<FLOAT4>`

**JdbcRecordReader.java (jdbc-base):**
- Added `import org.apache.arrow.vector.complex.ListVector`
- Added `else if (vec instanceof ListVector)` branch in `writeValue()` — parses pgjdbc text format `[x1,x2,...,xN]` into Float4 child vector values using low-level offset buffer writes

**DremioPostgresContainer.java (jdbc-postgresql test):**
- Changed `IMAGE` constant from `"postgres:16-alpine"` to `"pgvector/pgvector:pg16"`
- Updated Javadoc to reflect new image

### Task 2: Integration tests

**TestPgvectorTypeMapping.java (new):**
- `@BeforeClass`: `CREATE EXTENSION IF NOT EXISTS vector` first, then `CREATE TABLE vector_test (id SERIAL, embedding vector(3), label TEXT)` + two seed rows
- `testSchemaDiscoveryVector()`: asserts `embedding` field is `ArrowType.List` with 1 child field of `FloatingPoint(SINGLE)` precision; asserts `label` is `ArrowType.Utf8`
- `testVectorValueRoundtrip()`: opens raw JDBC connection, reads row 1, asserts `getString("embedding")` returns `[1.0,2.0,3.0]`-format text parseable as 3 floats
- `testVectorNullHandling()`: inserts a NULL embedding row, re-discovers schema, asserts type is still `ArrowType.List`
- `@AfterClass`: closes pool

## Test Results

| Test Class | Tests | Failures | Errors | Duration |
|------------|-------|----------|--------|----------|
| TestPgvectorTypeMapping | 3 | 0 | 0 | 18.84s |
| TestPostgresTypeMapping | 3 | 0 | 0 | 4.52s |

All 6 tests pass. `TestPostgresTypeMapping` confirms the pgvector image upgrade is backward compatible.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ListVector.OFFSET_WIDTH constant does not exist**
- **Found during:** Task 1 (JdbcRecordReader implementation)
- **Issue:** Plan specified `ListVector.OFFSET_WIDTH` but Arrow 18.1.1-dremio's `ListVector` class does not define this constant. It exists on `BaseVariableWidthVector` which is not a parent of `ListVector`.
- **Fix:** Replaced `ListVector.OFFSET_WIDTH` with integer literal `4` (INT32 width, 4 bytes per offset slot in Arrow offset buffer — documented inline)
- **Files modified:** `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/JdbcRecordReader.java`

No other deviations — plan executed as written.

## Architecture Notes

The `ArrowType.List.INSTANCE` singleton sentinel approach works cleanly:
1. `mapJdbcType("vector")` returns `ArrowType.List.INSTANCE`
2. `getTableSchema()` override does identity comparison `arrowType == ArrowType.List.INSTANCE` to detect this sentinel
3. When detected, builds `Field(columnName, FieldType(nullable, ArrowType.List.INSTANCE, null), [Float4 child])`
4. All other columns fall through to `new Field(columnName, new FieldType(...), null)` — identical to base class

This is the same pattern used for the Phase 41 plan: `l2_distance(LIST<FLOAT4>, LIST<FLOAT4>) -> FLOAT` can now operate on embedding columns without any type casting.

## Self-Check: PASSED

### Files Exist

- FOUND: PostgresSchemaFetcher.java
- FOUND: JdbcRecordReader.java
- FOUND: JdbcSchemaFetcher.java
- FOUND: DremioPostgresContainer.java
- FOUND: TestPgvectorTypeMapping.java
- FOUND: 40-01-SUMMARY.md

### Tests Passed

- TestPgvectorTypeMapping: 3/3 passed (testSchemaDiscoveryVector, testVectorValueRoundtrip, testVectorNullHandling)
- TestPostgresTypeMapping: 3/3 passed (backward compatibility with pgvector image confirmed)
