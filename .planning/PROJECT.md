# Dremio OSS Naive RBAC

## What This Is

A role-based access control system for Dremio OSS with privilege context switching. v1.0 established deny-by-default enforcement for VDS and UDF access. v1.2 adds definer rights on views (VDS expansion uses the last modifier's grants), definer semantics verification for UDFs, SELECT grants on physical tables, container visibility filtering, additional VDS lifecycle privileges, and metadata safety. Built on Dremio's catalog infrastructure with RocksDB persistence, SQL DDL, REST API, and system table observability.

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

### Active

<!-- Current scope. Building toward these. -->

**Current Milestone: v1.2 Privilege Context & Enforcement**

**Goal:** Add privilege context switching (definer/invoker rights), table-level SELECT grants, container visibility, and VDS lifecycle privileges.

**Target features:**
- VDS definer rights — VDS expansion uses last modifier's privileges, enabling users to query views over tables they can't directly access
- UDF definer semantics — verified and tested; UDF body runs as creator's identity (already implemented, needs owner resolution fix)
- SELECT on physical tables (PDS) — grantable SELECT privilege on tables, not just views
- Container visibility — sources/spaces/folders only visible if user has access to at least one child, full ancestor path shown
- VDS lifecycle privileges — ALTER and DROP on views as distinct grantable privileges
- DESCRIBE gating — follows SELECT privilege (implicit, no separate grant)
- EXPLAIN gating — requires privileges on all referenced objects, for any command type (SELECT, CREATE, etc.)
- sys.privileges admin-only — system privilege table restricted to ADMIN role
- PDS CREATE admin-only — only admins can create physical tables in sources

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

Shipped v1.0 with ~4,577 LOC Java across 69 files.
Tech stack: Java, Proto3, RocksDB KV stores, Jersey/JAX-RS REST, Dremio CatalogImpl enforcement.
All RBAC code lives in `com.dremio.exec.rbac` package (sabot/kernel module).

Post-v1.0 audit identified and fixed 2 enforcement bypass paths (bulkGetTables, AT-specifier) and v2 API visibility gaps.
Known v1.0 limitation: catalog visibility pagination may return fewer items than requested when RBAC filters are active.
Build caveat: Maven build requires Java 21 (enforcer [21,22) range); proto verified with protoc 3.6.0 directly.

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
| Catalog-level enforcement only (v1.0) | Covers all access paths (SQL + REST), simpler than dual-layer, consistent with EE approach | ⚠️ Revisit — v1.2 needs privilege context switching during planning for definer/invoker rights |
| Deny by default | More secure than allow-by-default; standard practice for access control systems | ✓ Good — clean security model |
| Flat roles only | Simplicity; nested roles add resolution complexity without clear v1 value | ✓ Good — sufficient for OSS use case |
| Views as security boundary (v1.0) | Definer rights model makes inner-table checks redundant; standard SQL behavior | ⚠️ Revisit — v1.2 adds table-level SELECT and explicit definer rights |
| KV Store (RocksDB) persistence | Consistent with Dremio's existing metadata storage patterns | ✓ Good — survives restarts, uses existing infra |
| Wire up existing SQL DDL | GRANT/REVOKE/CREATE ROLE parsers already exist; avoids reinventing SQL grammar | ✓ Good — zero parser changes needed |
| Feature flag defaults to OFF | Safe deployment — existing behavior preserved until admin explicitly enables RBAC | ✓ Good — no surprises on upgrade |
| ADMIN and PUBLIC as synthetic constants | Never written to KV store; simplifies bootstrap and immutability | ✓ Good — clean separation |
| oss_rbac_ KV store prefix | Isolates from Dremio EE namespace; pipe separator for composite keys | ✓ Good — no collisions |
| Role IDs = slugified names (not UUIDs) | Human-readable keys, immutable (no rename support) | ✓ Good — simple lookup |
| No privilege caching in v1 | Hit KV store every hasPrivilege() call; simplicity over performance | ⚠️ Revisit — may need caching at scale |
| DDL works when RBAC flag is OFF | Admins set up roles/grants before enabling enforcement | ✓ Good — enables staged rollout |

---
*Last updated: 2026-02-20 after v1.2 requirements defined*
