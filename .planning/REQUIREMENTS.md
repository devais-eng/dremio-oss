# Requirements: Dremio OSS Enhancements

**Defined:** 2026-03-12
**Core Value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.

## v1.5 Requirements

Requirements for the Open-Source RDBMS JDBC Plugin milestone. Each maps to roadmap phases.

### Base JDBC Framework

- [ ] **BASE-01**: Plugin base module provides HikariCP connection pooling with configurable pool size, idle timeout, and validation query
- [ ] **BASE-02**: Plugin discovers schemas, tables, and columns via JDBC DatabaseMetaData
- [ ] **BASE-03**: Plugin maps standard JDBC types to Arrow/Dremio types (BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, VARCHAR, DATE, TIME, TIMESTAMP, BINARY, VARBINARY)
- [ ] **BASE-04**: Plugin converts JDBC ResultSet rows into Arrow RecordBatches and streams them to Dremio execution engine
- [ ] **BASE-05**: Plugin pushes down WHERE filters to source SQL
- [ ] **BASE-06**: Plugin pushes down column projection (SELECT specific columns) to source SQL
- [ ] **BASE-07**: Plugin pushes down LIMIT to source SQL
- [ ] **BASE-08**: Plugin reports source health status (good/warn/error) to Dremio coordinator via periodic validation query

### PostgreSQL Connector

- [ ] **PG-01**: User can create a POSTGRES_DB source via REST API with config: hostname, port, databaseName, username, password, fetchSize, useSsl, encryptionValidationMode, maxIdleConns, idleTimeSec, queryTimeoutSec
- [ ] **PG-02**: User can SELECT from PostgreSQL tables through Dremio SQL with correct results
- [ ] **PG-03**: PostgreSQL-specific types mapped correctly: TEXT→VARCHAR, BYTEA→VARBINARY, UUID→VARCHAR(36), JSONB/JSON→VARCHAR, SERIAL/BIGSERIAL→INT/BIGINT, arrays→LIST, INTERVAL→VARCHAR
- [ ] **PG-04**: User can create a POSTGRES_DB source via Dremio UI wizard with JSON layout form
- [ ] **PG-05**: Testcontainers integration tests validate type roundtrips, schema discovery, filter pushdown, and projection pushdown against postgres:16-alpine

### Oracle Connector

- [ ] **ORA-01**: User can create an ORACLE_DB source via REST API with config: hostname, port, serviceName, username, password, fetchSize, useSsl, encryptionValidationMode, maxIdleConns, idleTimeSec, queryTimeoutSec
- [ ] **ORA-02**: User can SELECT from Oracle tables through Dremio SQL with correct results
- [ ] **ORA-03**: Oracle-specific types mapped correctly: NUMBER(p,s)→DECIMAL, NUMBER(no precision)→FLOAT8, VARCHAR2→VARCHAR, NVARCHAR2→VARCHAR, CLOB/NCLOB→VARCHAR, BLOB→VARBINARY, RAW→VARBINARY, DATE→TIMESTAMP, BINARY_FLOAT→FLOAT4, BINARY_DOUBLE→FLOAT8, TIMESTAMP WITH TIME ZONE→TIMESTAMP
- [ ] **ORA-04**: User can create an ORACLE_DB source via Dremio UI wizard with JSON layout form
- [ ] **ORA-05**: Testcontainers integration tests validate type roundtrips, schema discovery, filter pushdown, and projection pushdown against gvenzl/oracle-xe:21-slim

## Future Requirements

Deferred to subsequent milestones. Tracked but not in current roadmap.

### Additional Databases

- **DB-01**: MySQL connector (MYSQL_DB source type)
- **DB-02**: SQL Server connector (SQLSERVER_DB source type)

### Advanced Pushdown

- **PUSH-01**: Aggregation pushdown (SUM, COUNT, AVG, MIN, MAX)
- **PUSH-02**: Sort pushdown (ORDER BY)
- **PUSH-03**: TopN pushdown (ORDER BY + LIMIT combined)

### pgvector Support

- **PGV-01**: pgvector `vector` type mapped to LIST<DOUBLE> instead of VARCHAR
- **PGV-02**: Distance operator pushdown (<->, <=>, <#>) to PostgreSQL
- **PGV-03**: ORDER BY distance + LIMIT k pushdown for HNSW/IVFFlat index usage

### Write Operations

- **WRITE-01**: INSERT support via JDBC prepared statements
- **WRITE-02**: CTAS (CREATE TABLE AS SELECT) support

### Authentication

- **AUTH-01**: Kerberos authentication support
- **AUTH-02**: OAuth2 authentication support

### ADBC Integration

- **ADBC-01**: Optional ADBC-over-JDBC backend as alternative to manual ResultSet→Arrow conversion

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Write operations (INSERT, CTAS) | Read-only for v1.5; writes are future scope |
| MySQL / SQL Server connectors | Deferred to future milestone; PostgreSQL + Oracle prove the architecture |
| Join pushdown | Not recommended per spec; cross-source joins handled by Dremio engine |
| Aggregation / sort pushdown | Basic pushdown first (WHERE + projection + LIMIT); advanced pushdown in future milestone |
| pgvector support | Next milestone — v1.5 builds the foundation |
| Replace CE plugin (same type name) | Coexist as POSTGRES_DB / ORACLE_DB; switch type names later if desired |
| ADBC integration | JDBC only for now; ADBC as future optimization layer |
| Kerberos / OAuth auth | Username/password + SSL/TLS only for v1.5 |
| Native wire protocols | Use standard JDBC drivers, not custom protocol implementations |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| BASE-01 | Phase 30 | Pending |
| BASE-02 | Phase 30 | Pending |
| BASE-03 | Phase 30 | Pending |
| BASE-04 | Phase 30 | Pending |
| BASE-05 | Phase 30 | Pending |
| BASE-06 | Phase 30 | Pending |
| BASE-07 | Phase 30 | Pending |
| BASE-08 | Phase 30 | Pending |
| PG-01 | Phase 31 | Pending |
| PG-02 | Phase 31 | Pending |
| PG-03 | Phase 31 | Pending |
| PG-04 | Phase 31 | Pending |
| PG-05 | Phase 31 | Pending |
| ORA-01 | Phase 32 | Pending |
| ORA-02 | Phase 32 | Pending |
| ORA-03 | Phase 32 | Pending |
| ORA-04 | Phase 32 | Pending |
| ORA-05 | Phase 32 | Pending |

**Coverage:**
- v1.5 requirements: 18 total
- Mapped to phases: 18
- Unmapped: 0

---
*Requirements defined: 2026-03-12*
*Last updated: 2026-03-12 after roadmap creation*
