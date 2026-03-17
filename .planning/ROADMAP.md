# Roadmap: Dremio OSS Enhancements

## Milestones

- ✅ **v1.0 Naive RBAC** — Phases 1-6 (shipped 2026-02-19)
- ✅ **v1.1 Enable Iceberg REST Catalog** — Phases 7-8 (shipped 2026-02-20)
- ✅ **v1.2 GitHub Actions Docker Distribution** — Phases 9-11 (shipped 2026-02-21)
- ✅ **v1.3 Privilege Context & Enforcement** — Phases 12-20 (shipped 2026-02-24)
- ✅ **v1.4 RBAC Issue Hardening** — Phases 21-29 (shipped 2026-03-11)
- 🚧 **v1.5 Open-Source RDBMS JDBC Plugin** — Phases 30-33 (in progress)

## Phases

<details>
<summary>✅ v1.0 Naive RBAC (Phases 1-6) — SHIPPED 2026-02-19</summary>

- [x] Phase 1: Design and Proto Schema (2/2 plans) — completed 2026-02-17
- [x] Phase 2: Persistence Layer (2/2 plans) — completed 2026-02-17
- [x] Phase 3: Service Layer (2/2 plans) — completed 2026-02-17
- [x] Phase 4: Catalog Enforcement and DI Wiring (3/3 plans) — completed 2026-02-18
- [x] Phase 5: DDL Handlers and System Tables (3/3 plans) — completed 2026-02-18
- [x] Phase 6: REST API and Access Path Hardening (3/3 plans) — completed 2026-02-18

See `milestones/v1.0-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.1 Enable Iceberg REST Catalog (Phases 7-8) — SHIPPED 2026-02-20</summary>

- [x] Phase 7: Plugin Wiring (1/1 plans) — completed 2026-02-20
- [x] Phase 8: End-to-End Validation (2/2 plans) — completed 2026-02-20

See `milestones/v1.1-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.2 GitHub Actions Docker Distribution (Phases 9-11) — SHIPPED 2026-02-21</summary>

- [x] Phase 9: Maven Build in CI (1/1 plans) — completed 2026-02-20
- [x] Phase 10: Dockerfile Adaptation (1/1 plans) — completed 2026-02-20
- [x] Phase 11: ECR Authentication and Push (1/1 plans) — completed 2026-02-20

See `milestones/v1.2-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.3 Privilege Context & Enforcement (Phases 12-20) — SHIPPED 2026-02-24</summary>

- [x] Phase 12: VDS Lifecycle Privilege Enforcement (2/2 plans) — completed 2026-02-21
- [x] Phase 13: VDS Definer Rights Safety Cluster (2/2 plans) — completed 2026-02-21
- [x] Phase 14: UDF Rights Verification and Owner Resolution (2/2 plans) — completed 2026-02-21
- [x] Phase 15: PDS SELECT Enforcement (Opt-in) (2/2 plans) — completed 2026-02-21
- [x] Phase 16: Container Visibility Filtering (2/2 plans) — completed 2026-02-21
- [x] Phase 17: Metadata Safety and Integration Testing (3/3 plans) — completed 2026-02-21
- [x] Phase 18: Code Hardening (1/1 plan) — completed 2026-02-23
- [x] Phase 19: Test Coverage and Documentation (1/1 plan) — completed 2026-02-23
- [x] Phase 20: File Browse and Promote RBAC Enforcement (2/2 plans) — completed 2026-02-23

See `milestones/v1.3-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.4 RBAC Issue Hardening (Phases 21-29) — SHIPPED 2026-03-11</summary>

- [x] Phase 21: Backend API Critical Security (1/1 plans) — completed 2026-03-11
- [x] Phase 22: Backend API High Security (2/2 plans) — completed 2026-03-11
- [x] Phase 23: UI Global Admin Gates (2/2 plans) — completed 2026-03-11
- [x] Phase 24: UI Dataset and Space Context Gates (2/2 plans) — completed 2026-03-11
- [x] Phase 25: Backend Logic Fixes (2/2 plans) — completed 2026-03-11
- [x] Phase 26: Information Disclosure Fix (1/1 plans) — completed 2026-03-11
- [x] Phase 27: Catalog API TOCTOU Fix (1/1 plans) — completed 2026-03-11
- [x] Phase 28: DACSecurityContext Role Enforcement (1/1 plans) — completed 2026-03-11
- [x] Phase 29: Backend Logic Gaps v2 (2/2 plans) — completed 2026-03-11

See `milestones/v1.4-ROADMAP.md` for full phase details.

</details>

### v1.5 Open-Source RDBMS JDBC Plugin (In Progress)

**Milestone Goal:** Introduce a fully open-source JDBC storage plugin into Dremio OSS supporting PostgreSQL and Oracle, with HikariCP connection pooling, Arrow type conversion, basic pushdown, Testcontainers integration tests, and UI source creation wizards — eliminating dependency on the closed-source CE JDBC plugin.

- [x] **Phase 30: Base JDBC Framework** - Module structure, HikariCP pooling, schema discovery, type mapping, Arrow conversion, basic pushdown, health check (completed 2026-03-12)
- [x] **Phase 31: PostgreSQL Connector** - POSTGRES_DB source type with full PG type mapping, SSL/TLS, UI form, Testcontainers tests (completed 2026-03-13)
- [ ] **Phase 32: Oracle Connector** - ORACLE_DB source type with full Oracle type mapping, NUMBER handling, SSL/TLS, UI form, Testcontainers tests
- [ ] **Phase 33: Advanced Query Pushdown Hardening** - PreparedStatement parameterization, expression expansion, ORDER BY, TopN, aggregation pushdown

## Phase Details

### Phase 30: Base JDBC Framework
**Goal**: A reusable JDBC base module exists that any database connector can extend — providing connection pooling, schema discovery, type mapping, Arrow batch conversion, pushdown, and health reporting
**Depends on**: Phase 29 (v1.4 complete)
**Requirements**: BASE-01, BASE-02, BASE-03, BASE-04, BASE-05, BASE-06, BASE-07, BASE-08
**Success Criteria** (what must be TRUE):
  1. A new Maven module `dremio-plugin-jdbc-base` compiles and its JAR is produced by the project build
  2. A connector that extends the base can open a pooled connection to a target database using HikariCP configuration (pool size, idle timeout, validation query)
  3. A connector that extends the base can list schemas, tables, and columns by calling the base schema-discovery helper
  4. A connector that extends the base can execute a SELECT and receive Arrow RecordBatches with correctly typed columns (BOOLEAN through VARBINARY) streamed to the Dremio execution engine
  5. A connector that extends the base automatically rewrites queries to include WHERE, projected columns, and LIMIT clauses supplied by the Dremio planner
**Plans:** 3/3 plans complete
Plans:
- [ ] 30-01-PLAN.md — Maven module skeleton, HikariCP pooling, BaseJdbcConf, JdbcStoragePlugin lifecycle, health check
- [ ] 30-02-PLAN.md — Schema discovery via DatabaseMetaData, JDBC-to-Arrow type mapping, execution pipeline (RecordReader, GroupScan, SubScan, ScanCreator)
- [ ] 30-03-PLAN.md — Planning layer: logical/physical scan nodes, pushdown rules (WHERE, projection, LIMIT), JdbcRulesFactory

### Phase 31: PostgreSQL Connector
**Goal**: Users can connect Dremio OSS to a PostgreSQL database as a POSTGRES_DB source, browse its schema, and query its tables with correct results and type fidelity
**Depends on**: Phase 30
**Requirements**: PG-01, PG-02, PG-03, PG-04, PG-05
**Success Criteria** (what must be TRUE):
  1. User can create a POSTGRES_DB source via the Dremio REST API providing hostname, port, databaseName, username, password, and optional SSL/TLS settings, and the source appears in the catalog
  2. User can run a SELECT query against a PostgreSQL table through Dremio SQL and receive correct results
  3. PostgreSQL-specific types (TEXT, BYTEA, UUID, JSONB, SERIAL, arrays, INTERVAL) are mapped to appropriate Dremio/Arrow types without data loss or errors
  4. User can create a POSTGRES_DB source through the Dremio UI source creation wizard using the JSON layout form
  5. Testcontainers integration tests against postgres:16-alpine pass, covering type roundtrips, schema discovery, filter pushdown, and projection pushdown
**Plans:** 2/2 plans complete
Plans:
- [ ] 31-01-PLAN.md — Base class amendments + PostgreSQL module (PostgresConf, type mapping, record reader, UI layout, icon, wiring)
- [ ] 31-02-PLAN.md — TestContainers integration tests (type roundtrips, schema discovery, pushdown verification)

### Phase 32: Oracle Connector
**Goal**: Users can connect Dremio OSS to an Oracle database as an ORACLE_DB source, browse its schema, and query its tables with correct results and type fidelity
**Depends on**: Phase 31
**Requirements**: ORA-01, ORA-02, ORA-03, ORA-04, ORA-05
**Success Criteria** (what must be TRUE):
  1. User can create an ORACLE_DB source via the Dremio REST API providing hostname, port, serviceName, username, password, and optional SSL/TLS settings, and the source appears in the catalog
  2. User can run a SELECT query against an Oracle table through Dremio SQL and receive correct results
  3. Oracle-specific types (NUMBER, VARCHAR2, NVARCHAR2, CLOB, BLOB, RAW, DATE, BINARY_FLOAT, BINARY_DOUBLE, TIMESTAMP WITH TIME ZONE) are mapped to appropriate Dremio/Arrow types without data loss or errors
  4. User can create an ORACLE_DB source through the Dremio UI source creation wizard using the JSON layout form
  5. Testcontainers integration tests against gvenzl/oracle-xe:21-slim pass, covering type roundtrips, schema discovery, filter pushdown, and projection pushdown
**Plans:** 2 plans
Plans:
- [ ] 32-01-PLAN.md — Oracle connector source code: base SqlBuilder pluggability, OracleConf, OracleSchemaFetcher, OracleSqlBuilder, UI layout, icons, Maven wiring
- [ ] 32-02-PLAN.md — Testcontainers integration tests: type roundtrips, schema discovery, pushdown verification against gvenzl/oracle-xe:21-slim

### Phase 33: Advanced Query Pushdown Hardening
**Goal**: Harden and extend JDBC query pushdown for both PostgreSQL and Oracle sources — PreparedStatement parameterization, expression expansion (IN, BETWEEN, arithmetic, COALESCE, NULLIF), ORDER BY pushdown, TopN (ORDER BY + LIMIT), and aggregation pushdown (GROUP BY + COUNT/SUM/MIN/MAX/AVG)
**Depends on**: Phase 32
**Requirements**: PUSH-01, PUSH-02, PUSH-03
**Success Criteria** (what must be TRUE):
  1. All WHERE clause literal values are sent as PreparedStatement bind parameters (? placeholders), not string-interpolated
  2. RexToSqlString handles IN (via OR-of-EQUALS detection), BETWEEN, arithmetic (+,-,*,/), COALESCE, NULLIF, LIKE, IS NULL, IS NOT NULL
  3. ORDER BY pushdown: SortPrel above JdbcScanPrel is absorbed into the scan with explicit NULLS FIRST/LAST
  4. TopN: ORDER BY + LIMIT combined in SQL when both SortPrel and LimitPrel are above JdbcScanPrel
  5. Aggregation pushdown: single-phase HashAggPrel/StreamAggPrel above JdbcScanPrel generates GROUP BY + aggregate SELECT
  6. SqlBuilder produces correct SQL for both PostgreSQL (LIMIT N) and Oracle (FETCH FIRST N ROWS ONLY) with ORDER BY
  7. Testcontainers integration tests verify pushdown for both PG and Oracle
**Plans:** 3 plans
Plans:
- [ ] 33-01-PLAN.md — PreparedStatement bind parameters, RexToSqlString extraction and expression expansion, SqlBuildRequest DTO, pipeline parameterization
- [ ] 33-02-PLAN.md — ORDER BY pushdown (JdbcPushSortIntoScan) and TopN (ORDER BY + LIMIT combined), integration tests
- [ ] 33-03-PLAN.md — Aggregation pushdown (JdbcPushAggIntoScan: GROUP BY + COUNT/SUM/MIN/MAX/AVG), integration tests

### Phase 34: ADBC Driver for (at least) PostgreSQL
**Goal**: Add an optional ADBC (Arrow Database Connectivity) execution backend that replaces the manual JDBC ResultSet-to-Arrow conversion with native Arrow buffers via JNI for PostgreSQL sources, with per-source protocol mode configuration (AUTO/JDBC/ADBC)
**Depends on**: Phase 33
**Requirements**: ADBC-01
**Success Criteria** (what must be TRUE):
  1. User can configure protocolMode (AUTO/JDBC/ADBC) per JDBC source
  2. AUTO mode probes ADBC availability at source start and falls back to JDBC gracefully
  3. ADBC mode executes queries via JniStatement + ArrowReader with native Arrow buffers
  4. Bind parameters are translated from JDBC ? to PostgreSQL $1,$2,$3 at the ADBC execution boundary
  5. Schema discovery works via ADBC getTableSchema/getObjects when in ADBC mode
  6. Docker image includes native libadbc_driver_postgresql.so on LD_LIBRARY_PATH
  7. Integration tests verify ADBC query execution, bind parameters, and schema discovery
**Plans:** 3 plans
Plans:
- [ ] 34-01-PLAN.md — ADBC core infrastructure: Maven deps, ProtocolMode enum, AdbcConnectionFactory, placeholder translation, AdbcRecordReader
- [ ] 34-02-PLAN.md — Plugin wiring: AdbcSchemaFetcher, JdbcStoragePlugin ADBC lifecycle, JdbcScanCreator branching, BaseJdbcConf/PostgresConf protocolMode
- [ ] 34-03-PLAN.md — Docker deployment (native driver install) and PostgreSQL ADBC integration tests

### Phase 35: JOIN/INTERSECT/EXCEPT Single-Engine Pushdown + ADBC COPY Optimization
**Goal**: Push JOIN, INTERSECT, and EXCEPT operations down to the JDBC/ADBC source when all referenced tables reside on the same single engine (PostgreSQL, Oracle, etc.) — avoiding unnecessary data transfer by letting the source compute the result with its own indexes and statistics. UNION is explicitly excluded (Dremio's parallel distributed execution is equal or better). Additionally, optimize ADBC execution by inlining bind parameter literals into SQL (instead of using Extended Query Protocol bind), enabling the ADBC PG driver to use the faster COPY binary protocol for all pushdown queries including those with WHERE clauses.
**Depends on**: Phase 34
**Requirements**: SETOP-01, ADBC-02
**Success Criteria** (what must be TRUE):
  1. INNER/LEFT/RIGHT/FULL JOIN between two tables on the same JDBC source is pushed down as a single SQL query to the source engine
  2. INTERSECT between tables on the same JDBC source is pushed down (source computes set intersection, returns only matching rows)
  3. EXCEPT between tables on the same JDBC source is pushed down (source computes set difference, returns only rows in A not in B)
  4. Queries involving tables from different sources (or mixed source/Dremio tables) are NOT pushed down — they execute normally in Dremio's engine
  5. Cached/reflected queries continue to use Dremio's engine (no interference with Reflections)
  6. Pushdown works for both JDBC and ADBC protocol modes
  7. ADBC mode inlines bind parameter literals into SQL (proper quoting/escaping) so the PG driver uses COPY binary protocol instead of Extended Query Protocol
  8. SQL injection safety: inlined literals are properly escaped; unit and integration tests verify that malicious string values (single quotes, backslashes, semicolons, SQL keywords) cannot break out of quoted context
  9. Integration tests verify correct results and source-side execution for PG and Oracle
**Plans:** 3/3 plans complete

Plans:
- [ ] 35-01-PLAN.md — JOIN pushdown planner infrastructure: JdbcJoinScanPrel, RexToJoinSqlString, JdbcPushJoinIntoScan rule, unit tests
- [ ] 35-02-PLAN.md — ADBC COPY optimization: LiteralInliner utility, AdbcRecordReader inline-mode wiring, SQL injection safety tests
- [ ] 35-03-PLAN.md — Integration tests: PostgreSQL JOIN + ADBC COPY + Oracle JOIN against Testcontainers

### Phase 36: Calcite JDBC Convention Migration for SQL Generation
**Goal**: Replace the manual `SqlBuilder` SQL string generation with Calcite's JDBC convention (`JdbcConvention`) where the entire pushdown subtree is represented as Calcite JDBC adapter nodes (`JdbcFilter`, `JdbcProject`, `JdbcJoin`, `JdbcSort`, `JdbcAggregate`) and Calcite renders the final SQL via `JdbcImplementor` + `SqlDialect`. This eliminates manual SQL construction, column aliasing bugs, and dialect-specific overrides — Calcite handles all of it. Pushdown rules store Calcite objects (RexNode, RelCollation, ImmutableBitSet, AggregateCall) instead of SQL strings. No kernel changes. All existing integration and unit test scenarios must produce identical results; test code may change but test logic and expected outcomes must not.
**Depends on**: Phase 35
**Requirements**: CALCITE-01
**Success Criteria** (what must be TRUE):
  1. All existing pushdowns (WHERE, projection, LIMIT, ORDER BY, aggregation, JOIN) produce identical query results before and after migration
  2. SQL generation uses Calcite's `JdbcImplementor` + `SqlDialect` (PG dialect, Oracle dialect) instead of manual `SqlBuilder`
  3. Self-joins and shared column names are handled automatically by Calcite (no manual dedup aliasing)
  4. JOINs, filters, projections, and aggregations compose correctly in any combination via JdbcRel subtree
  5. No kernel changes — DrelTransformer untouched (per user decision: "limit the intervention on core kernel part")
  6. No SQL injection risk — Calcite renders AST, never string-interpolates
  7. All existing unit tests pass (test code may be refactored, test scenarios unchanged)
  8. All existing integration tests (Testcontainers + Docker UAT) pass with identical expected results
  9. Plugin remains fully self-contained — no kernel modifications
**Plans:** 3 plans

Plans:
- [ ] 36-01-PLAN.md — JdbcCalciteLeaf, createDialect(), JdbcScanPrel field migration to Calcite objects, pushdown rule updates, getPhysicalOperator() JdbcImplementor rewrite
- [ ] 36-02-PLAN.md — JdbcJoinScanPrel Calcite migration: RexNode join condition, JdbcJoin subtree, JdbcImplementor rendering
- [ ] 36-03-PLAN.md — Unit test updates, TestCalciteDialectSql, full integration test suite verification

### Phase 37: Expression Pushdown — Functions, HAVING, ORDER BY Expressions (pgvector foundation)
**Goal**: Enable pushdown of SQL expressions containing standard functions, enabling HAVING clauses, ORDER BY with expressions, COUNT(DISTINCT), and CAST in JdbcProject nodes. Introduces a `PushdownFunctionRegistry` that whitelists which functions can be pushed to each dialect (PostgreSQL, Oracle). Function composition is supported when ALL functions in the expression tree are whitelisted; if any function is not whitelisted, the entire operator (agg, sort, project) stays in Dremio's engine. This phase lays the infrastructure for Phase 38 (pgvector UDFs + semantic search pushdown).
**Depends on**: Phase 36
**Requirements**: EXPR-01
**Success Criteria** (what must be TRUE):
  1. `PushdownFunctionRegistry` interface with per-dialect function whitelists (base: standard SQL, PG: standard + PG-specific, Oracle: standard + Oracle-specific like NVL)
  2. HAVING pushdown: `GROUP BY col HAVING COUNT(*) > N` pushed as single SQL to source
  3. COUNT(DISTINCT col) pushdown (currently rejected in Phase 33)
  4. ORDER BY with expressions: `ORDER BY UPPER(name)`, `ORDER BY ROUND(salary, -3)`
  5. ORDER BY expression + LIMIT K: `ORDER BY func(col) LIMIT K` pushed (critical for future pgvector `ORDER BY distance LIMIT K`)
  6. CAST in JdbcProject: the cross-source JOIN CAST case from Phase 36 now pushes instead of declining
  7. Whitelisted standard SQL functions pushed in WHERE, PROJECT, ORDER BY, HAVING:
     - Math: ROUND, CEIL/CEILING, FLOOR, ABS
     - String: UPPER, LOWER, TRIM, LENGTH/CHAR_LENGTH, SUBSTRING
     - Date: EXTRACT(YEAR/MONTH/DAY FROM col)
     - Null: COALESCE, NULLIF, NVL (Oracle)
     - Type: CAST
  8. Function composition works recursively: `ROUND(AVG(salary), 2)` pushes when both ROUND and AVG are whitelisted
  9. Non-whitelisted functions in any expression → entire operator declines pushdown (no partial pushdown)
  10. All existing tests pass; new tests for each pushdown pattern
  11. Integration tests against PG and Oracle verify pushed SQL contains the functions
**Plans:** 2 plans

Plans:
- [ ] 37-01-PLAN.md — PushdownFunctionRegistry, HAVING pushdown, COUNT(DISTINCT), filter guard fix
- [ ] 37-02-PLAN.md — JdbcProject function expressions, ORDER BY with expressions, integration tests (PG + Oracle)

### Phase 38: Expression Pushdown Gap Closure — AGG with Expressions, JOIN with Functions (PG + Oracle)
**Goal**: Close four expression pushdown gaps discovered during Phase 37 UAT: (1) GROUP BY with function expressions (e.g. EXTRACT(YEAR FROM col)), (2) JOIN with function expressions in conditions (e.g. ON CAST(col AS type)), (3) aggregate operand expressions (e.g. SUM(salary * 1.1)), and (4) bare aggregates without GROUP BY (e.g. SELECT SUM(salary) FROM table). All gaps share a common root cause: an intermediate ProjectPrel between the aggregate/join and JdbcScanPrel blocks the existing pushdown rules from firing.
**Requirements**: GAP-01, GAP-02, GAP-03, GAP-04
**Depends on:** Phase 37
**Plans:** 1/2 plans executed

Plans:
- [ ] 38-01-PLAN.md — JdbcPushAggWithExpressionsHep rule, JdbcScanPrel extend/aggregate/trim, JdbcPushJoinIntoScan findJdbcScan() fix, unit tests
- [ ] 38-02-PLAN.md — PostgreSQL and Oracle integration tests for all 4 gaps

## Quick Tasks

Ad-hoc tasks outside the milestone phase structure. See `.planning/quick/` for details.

| # | Description | Date | Status |
|---|-------------|------|--------|
| 1 | Fix Github Actions docker build ARG JAVA_IMAGE scope | 2026-02-25 | Done |
| 2 | Split docker-ecr workflow into build and docker jobs | 2026-02-25 | Done |
| 3 | Switch Docker push from ECR to GHCR | 2026-02-28 | Done |
| 4 | Merge develop into rbac and align .planning directory | 2026-03-01 | Done |

## Progress

**Execution Order:**
Phases execute in numeric order: 30 → 31 → 32 → 33 → 34 → 35 → 36 → 37 → 38

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Design and Proto Schema | v1.0 | 2/2 | Complete | 2026-02-17 |
| 2. Persistence Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 3. Service Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 4. Catalog Enforcement and DI Wiring | v1.0 | 3/3 | Complete | 2026-02-18 |
| 5. DDL Handlers and System Tables | v1.0 | 3/3 | Complete | 2026-02-18 |
| 6. REST API and Access Path Hardening | v1.0 | 3/3 | Complete | 2026-02-18 |
| 7. Plugin Wiring | v1.1 | 1/1 | Complete | 2026-02-20 |
| 8. End-to-End Validation | v1.1 | 2/2 | Complete | 2026-02-20 |
| 9. Maven Build in CI | v1.2 | 1/1 | Complete | 2026-02-20 |
| 10. Dockerfile Adaptation | v1.2 | 1/1 | Complete | 2026-02-20 |
| 11. ECR Authentication and Push | v1.2 | 1/1 | Complete | 2026-02-20 |
| 12. VDS Lifecycle Privilege Enforcement | v1.3 | 2/2 | Complete | 2026-02-21 |
| 13. VDS Definer Rights Safety Cluster | v1.3 | 2/2 | Complete | 2026-02-21 |
| 14. UDF Rights Verification and Owner Resolution | v1.3 | 2/2 | Complete | 2026-02-21 |
| 15. PDS SELECT Enforcement (Opt-in) | v1.3 | 2/2 | Complete | 2026-02-21 |
| 16. Container Visibility Filtering | v1.3 | 2/2 | Complete | 2026-02-21 |
| 17. Metadata Safety and Integration Testing | v1.3 | 3/3 | Complete | 2026-02-21 |
| 18. Code Hardening | v1.3 | 1/1 | Complete | 2026-02-23 |
| 19. Test Coverage and Documentation | v1.3 | 1/1 | Complete | 2026-02-23 |
| 20. File Browse and Promote RBAC Enforcement | v1.3 | 2/2 | Complete | 2026-02-23 |
| 21. Backend API Critical Security | v1.4 | 1/1 | Complete | 2026-03-11 |
| 22. Backend API High Security | v1.4 | 2/2 | Complete | 2026-03-11 |
| 23. UI Global Admin Gates | v1.4 | 2/2 | Complete | 2026-03-11 |
| 24. UI Dataset and Space Context Gates | v1.4 | 2/2 | Complete | 2026-03-11 |
| 25. Backend Logic Fixes | v1.4 | 2/2 | Complete | 2026-03-11 |
| 26. Information Disclosure Fix | v1.4 | 1/1 | Complete | 2026-03-11 |
| 27. Catalog API TOCTOU Fix | v1.4 | 1/1 | Complete | 2026-03-11 |
| 28. DACSecurityContext Role Enforcement | v1.4 | 1/1 | Complete | 2026-03-11 |
| 29. Backend Logic Gaps v2 | v1.4 | 2/2 | Complete | 2026-03-11 |
| 30. Base JDBC Framework | v1.5 | Complete    | 2026-03-12 | - |
| 31. PostgreSQL Connector | v1.5 | Complete    | 2026-03-13 | - |
| 32. Oracle Connector | v1.5 | 0/? | Not started | - |
| 33. Advanced Query Pushdown Hardening | v1.5 | 0/3 | Not started | - |
| 34. ADBC Driver for PostgreSQL | v1.5 | 0/3 | Not started | - |
| 35. JOIN/INTERSECT/EXCEPT Single-Engine Pushdown | v1.5 | Complete    | 2026-03-14 | - |
| 36. Calcite JDBC Convention Migration | v1.5 | 0/3 | Not started | - |
| 37. Expression Pushdown (pgvector foundation) | v1.5 | 0/2 | Not started | - |
| 38. Expression Pushdown Gap Closure | 1/2 | In Progress|  | - |
