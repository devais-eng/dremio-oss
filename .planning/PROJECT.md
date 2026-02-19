# Dremio OSS Naive RBAC

## What This Is

A role-based access control system for Dremio OSS that controls who can SELECT views (VDS), CREATE OR REPLACE views, and EXECUTE user-defined functions. Built on top of Dremio's existing catalog infrastructure, wiring up the currently no-op `validatePrivilege()` to enforce real permission checks backed by RocksDB.

## Core Value

Users can only access views and UDFs they've been explicitly granted access to, with deny-by-default policy and admin bypass — closing the open-access gap in Dremio OSS.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

(None yet — ship to validate)

### Active

<!-- Current scope. Building toward these. -->

- [ ] Flat role management: CREATE ROLE, DROP ROLE, GRANT ROLE TO USER, REVOKE ROLE FROM USER
- [ ] Built-in ADMIN role that bypasses all RBAC checks
- [ ] Built-in PUBLIC role that all users implicitly belong to
- [ ] GRANT/REVOKE SELECT on VDS (views) to roles
- [ ] GRANT/REVOKE CREATE OR REPLACE on VDS to roles
- [ ] GRANT/REVOKE EXECUTE on UDFs to roles
- [ ] Deny-by-default policy: no grant = no access (except ADMIN)
- [ ] Catalog-level enforcement via CatalogImpl.validatePrivilege()
- [ ] Add SELECT privilege check in dataset resolution path (getTable/getDataset)
- [ ] Add EXECUTE privilege check in UDF resolution path (getFunction)
- [ ] Persist roles, memberships, and grants in RocksDB via LegacyKVStore
- [ ] Wire up existing GRANT/REVOKE SQL DDL (currently throws UnsupportedError in OSS)
- [ ] REST API endpoints for role and grant management (for UI integration)
- [ ] System tables populated: sys.roles, sys.privileges, sys.membership

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

## Context

### Existing Infrastructure

Dremio OSS already has significant RBAC plumbing that's wired up but non-functional:

- **SQL DDL parsers exist**: `SqlCreateRole`, `SqlDropRole`, `SqlGrantRole`, `SqlRevokeRole`, `SqlGrant`, `SqlRevoke` in `sabot/kernel/.../sql/parser/` — all parse correctly but dispatch to handlers that throw `UnsupportedError` in OSS
- **Privilege enum exists**: `SqlGrant.Privilege` has SELECT, ALTER, DROP, CREATE_TABLE, CREATE_VIEW, EXECUTE, and many more
- **System tables defined**: `sys.roles`, `sys.privileges`, `sys.membership` with schema classes (`SysTableRoleInfo`, `SysTablePrivilegeInfo`, `SysTableMembershipInfo`) — backed by `AccessControlListingManager` interface
- **Catalog validation hooks exist**: `CatalogImpl.validatePrivilege()` and `validateOwnership()` are defined but are no-ops in OSS
- **Identity infrastructure exists**: `CatalogIdentity`, `CatalogUser`, `AuthorizationContext`, `SchemaConfig` all carry user identity through the system

### Security Model

- **Definer rights**: Views expand under the view creator's identity (`ViewExpander` calls `builder.withUser(viewOwner)`). Inner tables are resolved as the view owner.
- **View as security boundary**: We check only the outermost entity the user referenced. If user has SELECT on the view, the view's definer rights handle inner access.
- **UDFs also use definer rights**: `UserDefinedFunctionExpanderImpl` expands with `builder.withUser(dremioUserDefinedFunction.getOwner())`
- **No dynamic SQL risk**: No `EXECUTE IMMEDIATE`, no stored procedures. Built-in `TableMacro` implementations are system-level.

### Key Files

- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` — validatePrivilege() no-op to wire up
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/DatasetManager.java` — dataset resolution path (needs SELECT check)
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/SqlGrant.java` — existing GRANT parser
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/SqlCreateRole.java` — existing CREATE ROLE parser
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogIdentity.java` — identity interface
- `sabot/kernel/src/main/java/com/dremio/exec/ops/ViewExpansionContext.java` — view expansion identity tracking
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/ViewExpander.java` — view expansion with definer rights
- `sabot/kernel/src/main/java/com/dremio/exec/ops/UserDefinedFunctionExpanderImpl.java` — UDF expansion with definer rights
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/udf/DremioScalarUserDefinedFunction.java` — scalar UDF
- `dac/backend/src/main/java/com/dremio/dac/server/DACSecurityContext.java` — isUserInRole() always returns true (needs update)

## Constraints

- **Tech stack**: Must use existing Dremio patterns — LegacyKVStore for persistence, protobuf for serialization, Jersey/JAX-RS for REST
- **Compatibility**: Must not break existing Dremio OSS functionality — RBAC should be additive
- **Java 11**: Runtime constraint from the existing codebase
- **Definer rights**: Cannot change the view expansion identity model — it's deeply embedded in the planner

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Catalog-level enforcement only | Covers all access paths (SQL + REST), simpler than dual-layer, consistent with EE approach | — Pending |
| Deny by default | More secure than allow-by-default; standard practice for access control systems | — Pending |
| Flat roles only | Simplicity; nested roles add resolution complexity without clear v1 value | — Pending |
| Views as security boundary | Definer rights model makes inner-table checks redundant; standard SQL behavior | — Pending |
| KV Store (RocksDB) persistence | Consistent with Dremio's existing metadata storage patterns | — Pending |
| Wire up existing SQL DDL | GRANT/REVOKE/CREATE ROLE parsers already exist; avoids reinventing SQL grammar | — Pending |

---
*Last updated: 2026-02-17 after initialization*
