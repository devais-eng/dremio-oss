# Roadmap: Dremio OSS Enhancements

## Milestones

- ✅ **v1.0 Naive RBAC** — Phases 1-6 (shipped 2026-02-19)
- ✅ **v1.1 Enable Iceberg REST Catalog** — Phases 7-8 (shipped 2026-02-20)
- ✅ **v1.2 GitHub Actions Docker Distribution** — Phases 9-11 (shipped 2026-02-21)
- ✅ **v1.3 Privilege Context & Enforcement** — Phases 12-20 (shipped 2026-02-24)
- ✅ **v1.4 RBAC Issue Hardening** — Phases 21-29 (shipped 2026-03-11)
- 🚧 **v1.5 Open-Source RDBMS JDBC Plugin** — Phases 30-32 (in progress)

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

### 🚧 v1.5 Open-Source RDBMS JDBC Plugin (In Progress)

**Milestone Goal:** Introduce a fully open-source JDBC storage plugin into Dremio OSS supporting PostgreSQL and Oracle, with HikariCP connection pooling, Arrow type conversion, basic pushdown, Testcontainers integration tests, and UI source creation wizards — eliminating dependency on the closed-source CE JDBC plugin.

- [x] **Phase 30: Base JDBC Framework** - Module structure, HikariCP pooling, schema discovery, type mapping, Arrow conversion, basic pushdown, health check (completed 2026-03-12)
- [ ] **Phase 31: PostgreSQL Connector** - POSTGRES_DB source type with full PG type mapping, SSL/TLS, UI form, Testcontainers tests
- [ ] **Phase 32: Oracle Connector** - ORACLE_DB source type with full Oracle type mapping, NUMBER handling, SSL/TLS, UI form, Testcontainers tests

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
**Plans:** 2 plans
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
**Plans**: TBD

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
Phases execute in numeric order: 30 → 31 → 32

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
| 31. PostgreSQL Connector | v1.5 | 0/? | Not started | - |
| 32. Oracle Connector | v1.5 | 0/? | Not started | - |
