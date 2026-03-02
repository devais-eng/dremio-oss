# Phase 1: Design and Proto Schema - Context

**Gathered:** 2026-02-17
**Status:** Ready for planning

<domain>
## Phase Boundary

Lock the foundational design decisions — proto3 messages, KV store key formats, package namespace, and feature flag — so all downstream phases can build without revisiting these choices. Delivers compilable proto schema, key format specifications, package structure, and a working feature flag.

</domain>

<decisions>
## Implementation Decisions

### Package Namespace
- All RBAC code lives inside the existing kernel module (`sabot/kernel`), not a new `services/rbac` module
- Package name: Claude's discretion (see below)
- EE isolation required: use distinct key prefixes and non-overlapping packages to avoid conflicts with Dremio Enterprise Edition's own RBAC

### KV Store Key Format
- Composite key separator: pipe `|` (validated safe for UUIDs, dot-paths, privilege names)
- Store name prefix: `oss_rbac_` for all three stores (`oss_rbac_roles`, `oss_rbac_grants`, `oss_rbac_memberships`)
- Role IDs: slugified from role name (e.g., role name "analyst" -> role ID "analyst"), not UUIDs
- Role names are immutable — no rename support. DROP and re-CREATE to "rename"

### Feature Flag Behavior
- RBAC toggle is a config file setting (dremio.conf), requires coordinator restart to change — not a runtime system option
- Default: OFF (RBAC disabled)
- On first enable with existing cluster: strict deny-by-default. All queries fail until admin configures grants. No auto-grant to PUBLIC
- Bootstrap: the first user created via the bootstrap flow automatically receives ADMIN role membership
- Fail-fast: system refuses to start if RBAC is enabled but ADMIN role has no members. Logs ERROR with clear message

### Proto Message Design
- Privilege types stored as strings ("SELECT", "EXECUTE", "CREATE_VIEW"), not proto enum
- Object types stored as strings ("VDS", "FUNCTION"), not proto enum
- Role message includes metadata: role_name, created_by, created_at
- Grant message: role_id, object_type, object_path, privilege, granted_by, granted_at
- Membership message: user_name, role_id, granted_by, granted_at
- ADMIN and PUBLIC are synthetic constants — never written to the KV store. Code checks for them directly in hasPrivilege()

### Claude's Discretion
- Exact Java package name (options discussed: `com.dremio.exec.catalog.rbac` or `com.dremio.exec.rbac`)
- Class naming convention: use domain names (RoleStore, GrantStore, PermissionService) not Rbac-prefixed names
- Proto file location within sabot/kernel
- Exact dremio.conf property name for the RBAC toggle

</decisions>

<specifics>
## Specific Ideas

- Role names as IDs: user explicitly chose slugified names over UUIDs for simplicity. Since roles are immutable (no rename), the slug IS the identity
- Strict deny-everything on enable: user does not want auto-grant PUBLIC SELECT on existing views. The admin must intentionally configure access
- Config-file toggle (not runtime): user wants RBAC enablement to be a deliberate, restart-required action — not something that can be toggled accidentally at runtime

</specifics>

<deferred>
## Deferred Ideas

- **Catalog visibility filtering** — pull META-03 (REST catalog API filtered by user's grants) into Phase 6. Users should only see views they can query when browsing the Dremio UI. Decision made during this discussion.
- **UI screens for RBAC administration** — not in v1. SQL DDL + REST API is the admin interface.

</deferred>

---

*Phase: 01-design-and-proto-schema*
*Context gathered: 2026-02-17*
