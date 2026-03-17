# Phase 34 Context: ADBC Driver for (at least) PostgreSQL

## Decisions (LOCKED)

### Protocol Mode Configuration
- Add `protocol_mode` field to source configuration: `AUTO` (default), `JDBC`, `ADBC`
- **AUTO**: Try ADBC if the native driver is available and the database supports it; fall back to JDBC otherwise
- **JDBC**: Force JDBC execution (existing behavior)
- **ADBC**: Force ADBC execution (fail if native driver unavailable)
- This is per-source, user-configurable

### Architecture: Swap Execution Layer Only
- All pushdown logic stays unchanged: RexToSqlString, SqlBuilder, SqlBuildRequest, JdbcScanPrel, all pushdown rules, JdbcGroupScan, JdbcSubScan, BindParam
- Only the execution/reading layer changes: RecordReader (query execution + Arrow batch production), SchemaFetcher (metadata discovery), ConnectionPool (connection lifecycle)
- ADBC uses native wire protocol via JNI (libpq for PostgreSQL), NOT ADBC-over-JDBC

### ADBC Technology Stack
- `adbc-driver-jni` (Maven artifact 0.22.0) — JNI bridge from Java to C++ ADBC driver manager
- JniDriver → JniConnection → JniStatement → ArrowReader (native Arrow buffers, zero-copy)
- Native PG driver (`libadbc_driver_postgresql.so`) loaded via C++ dlopen at runtime
- Bind parameters via `AdbcStatement.bind(VectorSchemaRoot)` — Arrow-typed

### Native Driver Deployment
- Use `dbc` CLI (from columnar.tech) to install native ADBC drivers in Docker image
- Add `dbc install postgresql` to Dockerfile
- The JNI bridge native lib is bundled inside `adbc-driver-jni-*.jar` (self-extracting)
- The database-specific driver `.so` needs to be on `LD_LIBRARY_PATH` or a standard lib path

### Scope
- PostgreSQL ADBC: fully implemented and tested
- Oracle: stays on JDBC only (no ADBC Oracle driver exists in the ecosystem)
- The base framework supports both backends so future databases with ADBC drivers can opt in

## Claude's Discretion

### Bind Parameter Placeholder Format
- Current SqlBuilder emits `?` (JDBC convention). PG libpq uses `$1, $2, ...` natively.
- Research whether ADBC PG driver translates `?` → `$N` internally, or if SqlBuilder needs conditional emit.
- Choose simplest approach that doesn't complicate the shared SQL generation path.

### ADBC Connection Pooling Strategy
- HikariCP cannot pool AdbcConnection (wrong type). Options:
  - Simple bounded pool (queue of AdbcConnection)
  - Create/close per-query (libpq connections are lightweight)
  - Wrapper/adapter pattern
- Choose based on ADBC connection lifecycle characteristics.

### Class Naming and Module Structure
- Whether to add Adbc* classes alongside Jdbc* in existing jdbc-base module, or create a new module
- Keep naming consistent with existing patterns

### Schema Discovery Implementation
- ADBC provides `AdbcConnection.getObjects()` and `getTableSchema()` returning Arrow data
- Whether to use ADBC schema discovery or keep JDBC DatabaseMetaData for schema (even when using ADBC for queries)

## Deferred Ideas (OUT OF SCOPE)

- Oracle ADBC support (no driver exists)
- MySQL/SQL Server ADBC
- Write operations via ADBC
- Replacing JDBC entirely (JDBC remains as fallback)
