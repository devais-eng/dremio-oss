# Phase 7: VDS Lifecycle Privilege Enforcement - Context

**Gathered:** 2026-02-20
**Status:** Ready for planning

<domain>
## Phase Boundary

Wire ALTER, DROP, and CREATE_VIEW enforcement into the three missing call sites so no view lifecycle operation succeeds without an explicit grant. Close the v1.0 enforcement gap where createView() had no validatePrivilege() call. Add GRANT/REVOKE support for ALTER and DROP privilege types on VDS.

</domain>

<decisions>
## Implementation Decisions

### Error messaging
- Errors MUST name the missing privilege and include the full view path
- Format: "Permission denied: {PRIVILEGE} privilege required on '{full.path.to.view}'"
- All three operations (ALTER VIEW, DROP VIEW, CREATE VIEW) use the same error format
- Whether to include a GRANT hint in the error message is Claude's discretion (check existing Dremio error patterns)

### Rollout behavior
- Strict deny-by-default from day 1 — no log-only mode, no bootstrap grants, no kill switch
- Non-admin users without ALTER/DROP/CREATE_VIEW grants are immediately blocked when enforcement goes live
- Enforcement is always on when RBAC is enabled — no separate config flag for this feature
- SELECT grants still control visibility — users with SELECT can see views they can't modify
- CREATE_VIEW also requires an explicit grant (same strictness as ALTER/DROP)

### CREATE_VIEW gap closure
- Enforce CREATE_VIEW at ALL code paths — SQL DDL, REST API, and any internal creation path
- No auto-granting of privileges to view creators — admin must explicitly grant everything (matches existing Dremio model)
- CREATE_VIEW is a **container-scoped** privilege: grant on a space or folder covers creating any view under that container
- Syntax: `GRANT CREATE_VIEW ON VDS "my_space" TO ROLE dev` — grants ability to create views anywhere under my_space
- Enforcement checks the parent container path of the view being created

### Privilege naming & GRANT syntax
- Privilege names match the SqlGrant.Privilege enum exactly: 'ALTER', 'DROP', 'CREATE_VIEW'
- GRANT/REVOKE syntax follows the same `ON VDS` pattern as SELECT: `GRANT ALTER ON VDS space.my_view TO ROLE role`
- ALTER and DROP are **per-view only** (not container-scoped like CREATE_VIEW)
- One privilege per GRANT statement — no multi-privilege syntax
- Stored as plain strings in the grant store, consistent with v1.0

### Claude's Discretion
- Whether to include GRANT hint in error messages (check existing Dremio error patterns)
- Exact placement of validatePrivilege() calls in the code paths (technical implementation)
- How to resolve parent container path for CREATE_VIEW checks (implementation detail)
- Test structure and coverage approach

</decisions>

<specifics>
## Specific Ideas

- CREATE_VIEW container-scoped grant is different from ALTER/DROP per-view grant — the enforcement check for CREATE_VIEW must resolve the parent container, not the view path itself
- Error format must be consistent across all three operations despite CREATE_VIEW targeting a different scope level
- The existing v1.0 validatePrivilege() in CatalogImpl already maps CREATE_VIEW to objectType "VDS" — extend this pattern for ALTER and DROP

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 07-vds-lifecycle-privilege-enforcement*
*Context gathered: 2026-02-20*
