# Dremio OSS Enhancements

## What This Is

Enterprise-grade enhancements for Dremio OSS. v1.0 delivered deny-by-default RBAC for views and UDFs. v1.1 enabled the Iceberg REST Catalog source type, allowing Dremio OSS to connect to external Iceberg REST catalog servers (Lakekeeper, Nessie, Polaris) and query tables through standard SQL. Validated against both Lakekeeper and Nessie.

## Core Value

Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control and catalog connectivity.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- ✓ Flat role management: CREATE ROLE, DROP ROLE, GRANT ROLE TO USER, REVOKE ROLE FROM USER — v1.0
- ✓ Built-in ADMIN role that bypasses all RBAC checks — v1.0
- ✓ Built-in PUBLIC role that all users implicitly belong to — v1.0
- ✓ GRANT/REVOKE SELECT on VDS (views) to roles — v1.0
- ✓ GRANT/REVOKE CREATE OR REPLACE on VDS to roles — v1.0
- ✓ GRANT/REVOKE EXECUTE on UDFs to roles — v1.0
- ✓ Deny-by-default policy: no grant = no access (except ADMIN) — v1.0
- ✓ Catalog-level enforcement via CatalogImpl.validatePrivilege() — v1.0
- ✓ SELECT privilege check in dataset resolution path (getTable/getDataset) — v1.0
- ✓ EXECUTE privilege check in UDF resolution path (getFunction) — v1.0
- ✓ Persist roles, memberships, and grants in RocksDB via KVStore — v1.0
- ✓ Wire up existing GRANT/REVOKE SQL DDL (no longer throws UnsupportedError in OSS) — v1.0
- ✓ REST API endpoints for role and grant management (9 endpoints at /api/v3/rbac) — v1.0
- ✓ System tables populated: sys.roles, sys.privileges, sys.membership — v1.0
- ✓ Iceberg REST Catalog source type discoverable and creatable via UI/API — v1.1
- ✓ Read-only operations: browse namespaces, list tables, SELECT from tables — v1.1
- ✓ Validated end-to-end against Lakekeeper and Nessie — v1.1

### Active

<!-- Next milestone scope. -->

(None yet — define with `/gsd:new-milestone`)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Row-level security — complexity explosion, not needed for naive RBAC
- Column-level security — same; views already serve as column projection
- Nested roles (role contains role) — flat roles are sufficient for v1
- Physical dataset (PDS) permissions — views are the security boundary
- Planner-level enforcement — catalog layer covers all paths (SQL + REST)
- DENY grants (negative permissions) — adds complexity; deny-by-default achieves the same
- Ownership transfer (GRANT OWNERSHIP) — not needed for naive model
- Source-level or space-level permissions — out of scope; focus is on VDS and UDFs
- Offline mode — real-time catalog enforcement is the model
- Credential vending propagation — DremioFileIO uses static Hadoop Config; static creds workaround sufficient for v1.1

## Context

**v1.0 RBAC:** Shipped with ~4,577 LOC Java across 69 files. All RBAC code in `com.dremio.exec.rbac` package.

**v1.1 Iceberg REST Catalog:** `@SourceType(value="RESTCATALOG")` added to `RestIcebergCatalogPluginConfig`, `restcatalog-layout.json` created with 3-tab UI form, `RESTCATALOG.svg` icon at classpath root. 120 LOC across 3 files. Validated against Lakekeeper and Nessie. Static `fs.s3a.*` credentials needed as workaround for credential vending gap.

Build caveat: Maven build requires Java 21 (enforcer [21,22) range).

### Future candidates
- RBAC: WITH GRANT OPTION, REVOKE CASCADE, container grants, INFORMATION_SCHEMA filtering, audit logging, privilege caching
- Iceberg REST Catalog: write operations, credential vending fix, RBAC integration, auth config validation, multi-catalog validation (Polaris, Unity, Gravitino)

## Constraints

- **Tech stack**: Must use existing Dremio patterns — KVStore for persistence, protobuf for serialization, Jersey/JAX-RS for REST
- **Compatibility**: Must not break existing Dremio OSS functionality — RBAC is additive, gated behind feature flag
- **Definer rights**: Cannot change the view expansion identity model — it's deeply embedded in the planner

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Catalog-level enforcement only | Covers all access paths (SQL + REST), simpler than dual-layer, consistent with EE approach | ✓ Good — all paths covered after post-audit fix |
| Deny by default | More secure than allow-by-default; standard practice for access control systems | ✓ Good — clean security model |
| Flat roles only | Simplicity; nested roles add resolution complexity without clear v1 value | ✓ Good — sufficient for OSS use case |
| Views as security boundary | Definer rights model makes inner-table checks redundant; standard SQL behavior | ✓ Good — matches SQL standard |
| KV Store (RocksDB) persistence | Consistent with Dremio's existing metadata storage patterns | ✓ Good — survives restarts, uses existing infra |
| Wire up existing SQL DDL | GRANT/REVOKE/CREATE ROLE parsers already exist; avoids reinventing SQL grammar | ✓ Good — zero parser changes needed |
| Feature flag defaults to OFF | Safe deployment — existing behavior preserved until admin explicitly enables RBAC | ✓ Good — no surprises on upgrade |
| ADMIN and PUBLIC as synthetic constants | Never written to KV store; simplifies bootstrap and immutability | ✓ Good — clean separation |
| oss_rbac_ KV store prefix | Isolates from Dremio EE namespace; pipe separator for composite keys | ✓ Good — no collisions |
| Role IDs = slugified names (not UUIDs) | Human-readable keys, immutable (no rename support) | ✓ Good — simple lookup |
| No privilege caching in v1 | Hit KV store every hasPrivilege() call; simplicity over performance | ⚠️ Revisit — may need caching at scale |
| DDL works when RBAC flag is OFF | Admins set up roles/grants before enabling enforcement | ✓ Good — enables staged rollout |
| @SourceType on concrete class only | ConnectionReaderImpl.getCandidateSources() skips abstract classes | ✓ Good — scanner finds it correctly |
| Static fs.s3a.* credentials workaround | DremioFileIO discards vended credentials from loadTable(); static Hadoop Config is the only path | ⚠️ Revisit — need credential vending propagation for IAM/STS |
| Validate against multiple REST catalogs | Nessie + Lakekeeper confirms plugin is spec-compliant, not server-specific | ✓ Good — portable across implementations |

---
*Last updated: 2026-02-20 after v1.1 milestone shipped*
