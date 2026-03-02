# Phase 4: Catalog Enforcement and DI Wiring - Context

**Gathered:** 2026-02-18
**Status:** Ready for planning

<domain>
## Phase Boundary

Wire RbacService into CatalogImpl.validatePrivilege() to enforce real permission checks. Users without grants are denied access to VDS and UDFs. System-user bypass and feature flag gating preserve existing behavior when RBAC is disabled. This phase does NOT add DDL handlers, REST endpoints, or system tables — those are Phase 5 and 6.

</domain>

<decisions>
## Implementation Decisions

### Denial behavior
- Denied access returns "not found" error — identical to querying a genuinely non-existent object (complete information hiding)
- User cannot distinguish between "object doesn't exist" and "object exists but I lack access"
- Consistent for both VDS (SELECT) and UDF (EXECUTE) — denied EXECUTE looks like "function not found"
- Access denials logged server-side at WARN level for admin troubleshooting
- Log message is minimal: "RBAC: Access denied for user 'alice'" — no object path or privilege type in log output

### System bypass scope
- Only the $dremio$ system user bypasses RBAC — no other special-casing for internal operations
- Use Dremio's existing SystemUser identification mechanism (whatever the codebase already provides)
- Feature flag checked first: if RBAC disabled, return immediately — no system-user check needed
- ADMIN role members go through normal hasPrivilege() path (already implemented in Phase 3) — CatalogImpl does NOT special-case ADMIN

### DI wiring approach
- Claude researches CatalogImpl's construction path and follows the existing DI pattern for similar services
- RbacService registered following existing Dremio DI pattern (Claude discovers how NamespaceService, CatalogService etc. are bound and follows that)
- Enforcement logic lives directly in CatalogImpl.validatePrivilege() — no separate enforcer class
- CatalogImpl checks feature flag first and returns early if RBAC disabled — RbacService is never called when flag is OFF

### Claude's Discretion
- Exact DI wiring mechanism (constructor injection vs SabotContext lookup vs Guice binding) — Claude researches and picks cleanest fit
- Test unit selection — Claude decides whether to test validatePrivilege() directly or via catalog operations based on CatalogImpl testability
- Error message exact text — must be indistinguishable from genuine "not found"

</decisions>

<specifics>
## Specific Ideas

- Check order in validatePrivilege(): (1) feature flag OFF → return, (2) system user → return, (3) call RbacService.hasPrivilege()
- The MEDIUM-confidence blocker in STATE.md (CatalogImpl injection path) should be resolved during research — Claude traces the actual construction path

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 04-catalog-enforcement-and-di-wiring*
*Context gathered: 2026-02-18*
