# Dremio OSS Naive RBAC

## What This Is

A role-based access control system for Dremio OSS that controls who can SELECT views (VDS), CREATE OR REPLACE views, and EXECUTE user-defined functions. Built on top of Dremio's existing catalog infrastructure, wiring up the previously no-op `validatePrivilege()` to enforce real permission checks backed by RocksDB. Manageable via SQL DDL, REST API, and observable through system tables.

## Core Value

Users can only access views and UDFs they've been explicitly granted access to, with deny-by-default policy and admin bypass — closing the open-access gap in Dremio OSS.

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

### Active

<!-- Current scope. Building toward these. -->

(None yet — define in next milestone)

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

## Context

Shipped v1.0 with ~4,577 LOC Java across 69 files.
Tech stack: Java, Proto3, RocksDB KV stores, Jersey/JAX-RS REST, Dremio CatalogImpl enforcement.
All RBAC code lives in `com.dremio.exec.rbac` package (sabot/kernel module).

Post-v1.0 audit identified and fixed 2 enforcement bypass paths (bulkGetTables, AT-specifier) and v2 API visibility gaps.
Known v1.0 limitation: catalog visibility pagination may return fewer items than requested when RBAC filters are active.
Build caveat: Maven build requires Java 21 (enforcer [21,22) range); proto verified with protoc 3.6.0 directly.

### v2 candidates (from requirements backlog)
- WITH GRANT OPTION (delegate privilege granting)
- REVOKE CASCADE
- Container grants (space/folder-level)
- INFORMATION_SCHEMA filtering
- Audit logging for RBAC DDL operations
- Migration tooling for existing deployments
- DACSecurityContext.isUserInRole() real implementation

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

---
*Last updated: 2026-02-19 after v1.0 milestone*
