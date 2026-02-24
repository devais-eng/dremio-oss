# Dremio OSS Naive RBAC

## What This Is

A role-based access control system for Dremio OSS with privilege context switching. v1.0 established deny-by-default enforcement for VDS and UDF access. v1.2 shipped definer rights on views, UDF definer semantics, SELECT grants on physical tables, container visibility filtering, VDS lifecycle privileges (ALTER/DROP/CREATE_VIEW), metadata safety (sys.privileges admin-only, DESCRIBE/EXPLAIN gating), and file browse/promote admin restrictions. All access paths — SQL, REST API, catalog tree — are governed by explicit grants with deny-by-default policy. Built on Dremio's catalog infrastructure with RocksDB persistence, SQL DDL, REST API, and system table observability.

## Core Value

Users can only access views, tables, and UDFs they've been explicitly granted access to, with deny-by-default policy, privilege context switching (definer rights for VDS and UDF), and admin bypass.

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
- ✓ VDS definer rights: view expansion under last modifier's identity with cycle detection and deleted-owner safety — v1.2
- ✓ UDF definer semantics: FunctionConfig owner stamping, FUNCTION owner resolution fix — v1.2
- ✓ SELECT on physical tables (PDS): opt-in deny-by-default via `services.rbac.pds.enabled` — v1.2
- ✓ Container visibility: sources/spaces/folders hidden unless user has child access — v1.2
- ✓ VDS lifecycle privileges: ALTER, DROP, CREATE_VIEW enforced at all call sites — v1.2
- ✓ DESCRIBE gating: follows SELECT privilege — v1.2
- ✓ EXPLAIN gating: requires privileges on all referenced objects — v1.2
- ✓ sys.privileges admin-only — v1.2
- ✓ File browse and promote restricted to admin users — v1.2
- ✓ PDS visibility filtering: non-granted PDS hidden from catalog tree when PDS enforcement enabled — v1.2
- ✓ Plan cache definer-rights bypass: different definer chains produce different cache entries — v1.2

### Active

<!-- Current scope. Building toward these. -->

(No active milestone — next milestone TBD via `/gsd:new-milestone`)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Row-level security — complexity explosion, not needed for naive RBAC
- Column-level security — same; views already serve as column projection
- Nested roles (role contains role) — flat roles are sufficient for v1
- Full DML on PDS (INSERT/UPDATE/DELETE/MERGE) — only SELECT on tables for v1.2; full DML is future scope
- PDS CREATE for non-admin users — only admins can create physical tables in sources; non-admin PDS CREATE is future scope
- DENY grants (negative permissions) — adds complexity; deny-by-default achieves the same
- Ownership transfer (GRANT OWNERSHIP) — not needed for naive model
- Source-level or space-level explicit grants — container visibility is derived from child access, not directly grantable
- Offline mode — real-time catalog enforcement is the model

## Context

Shipped v1.2 with ~7,668 LOC Java across 114+ files (v1.0: 4,577 + v1.2: 3,091).
Tech stack: Java, Proto3, RocksDB KV stores, Jersey/JAX-RS REST, Dremio CatalogImpl enforcement.
RBAC core lives in `com.dremio.exec.rbac` package (sabot/kernel module). Enforcement wired into CatalogImpl, DDL handlers, REST resources, and CatalogServiceHelper.

v1.2 UAT verified on Docker (port 19047) with 10/10 RBAC tests + PostgreSQL source PDS enforcement (6/6 tests).
Known limitations: UI search blocks all source types for non-admin users; container visibility tested via SQL proxy only.
Build caveat: Maven build requires Java 21 (enforcer [21,22) range).

### Future candidates (from requirements backlog)
- WITH GRANT OPTION (delegate privilege granting)
- REVOKE CASCADE
- INFORMATION_SCHEMA filtering
- Audit logging for RBAC DDL operations
- Migration tooling for existing deployments
- DACSecurityContext.isUserInRole() real implementation
- Full DML privileges on Iceberg tables (INSERT, UPDATE, DELETE, MERGE)

## Constraints

- **Tech stack**: Must use existing Dremio patterns — KVStore for persistence, protobuf for serialization, Jersey/JAX-RS for REST
- **Compatibility**: Must not break existing Dremio OSS functionality — RBAC is additive, gated behind feature flag
- **View expansion model**: Definer rights must integrate with Dremio's existing view expansion identity model, not replace it

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Catalog-level enforcement only (v1.0) | Covers all access paths (SQL + REST), simpler than dual-layer, consistent with EE approach | ✓ Good — v1.2 added definer rights within catalog enforcement layer |
| Deny by default | More secure than allow-by-default; standard practice for access control systems | ✓ Good — clean security model |
| Flat roles only | Simplicity; nested roles add resolution complexity without clear v1 value | ✓ Good — sufficient for OSS use case |
| Views as security boundary (v1.0) | Definer rights model makes inner-table checks redundant; standard SQL behavior | ✓ Good — v1.2 shipped both: definer rights on views + opt-in PDS SELECT enforcement |
| KV Store (RocksDB) persistence | Consistent with Dremio's existing metadata storage patterns | ✓ Good — survives restarts, uses existing infra |
| Wire up existing SQL DDL | GRANT/REVOKE/CREATE ROLE parsers already exist; avoids reinventing SQL grammar | ✓ Good — zero parser changes needed |
| Feature flag defaults to OFF | Safe deployment — existing behavior preserved until admin explicitly enables RBAC | ✓ Good — no surprises on upgrade |
| ADMIN and PUBLIC as synthetic constants | Never written to KV store; simplifies bootstrap and immutability | ✓ Good — clean separation |
| oss_rbac_ KV store prefix | Isolates from Dremio EE namespace; pipe separator for composite keys | ✓ Good — no collisions |
| Role IDs = slugified names (not UUIDs) | Human-readable keys, immutable (no rename support) | ✓ Good — simple lookup |
| No privilege caching in v1 | Hit KV store every hasPrivilege() call; simplicity over performance | ⚠️ Revisit — may need caching at scale |
| DDL works when RBAC flag is OFF | Admins set up roles/grants before enabling enforcement | ✓ Good — enables staged rollout |
| Separate PDS enforcement flag | `services.rbac.pds.enabled` independent of `services.rbac.enabled` | ✓ Good — allows VDS-only RBAC rollout first |
| Definer rights via ViewExpander identity | Reuse existing view expansion identity model, not a new privilege layer | ✓ Good — minimal code, maximum integration |
| File browse/promote admin-only | Guard all source types (not just file-based) for simplicity | ⚠️ Revisit — over-restrictive for database/catalog sources |
| Container visibility from grants | Derive container visibility from child grants, not explicit container grants | ✓ Good — no new grant type needed |

---
*Last updated: 2026-02-24 after v1.2 milestone*
