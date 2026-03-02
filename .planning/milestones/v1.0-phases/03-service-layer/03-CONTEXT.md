# Phase 3: Service Layer - Context

**Gathered:** 2026-02-17
**Status:** Ready for planning

<domain>
## Phase Boundary

RbacService implements all business logic -- privilege checking (hasPrivilege), role lifecycle, membership management, ADMIN bypass, PUBLIC implicit membership, and bootstrap ADMIN assignment. Testable in isolation without catalog wiring. The service also implements AccessControlListingManager to expose RBAC state for system table consumption in Phase 5.

</domain>

<decisions>
## Implementation Decisions

### Privilege Resolution
- OR logic: user has privilege if ANY of their roles has the matching grant
- hasPrivilege() returns boolean (true/false), not exception on denial -- caller decides how to handle denial
- ADMIN membership checked first (short-circuit): if user is ADMIN, return true immediately without checking grants
- No caching in v1: hit KV store on every hasPrivilege() call. Optimize later if needed
- Resolution order: check ADMIN -> collect user's explicit roles + PUBLIC -> check grants for all collected roles

### PUBLIC Role Behavior
- PUBLIC is a synthetic constant (never stored in KV store) -- consistent with Phase 1 decision
- PUBLIC grants checked alongside explicit role grants in a single pass (not a separate fallback step)
- PUBLIC is grantable: admins can assign privileges to PUBLIC via `GRANT ... TO ROLE PUBLIC`, giving all users access
- PUBLIC is immutable: cannot be dropped, users cannot be removed from PUBLIC

### Bootstrap Flow
- ADMIN membership assigned during FirstLoginSetupService (bootstrap user creation flow)
- One-time assignment: bootstrap sets ADMIN membership once, subsequent restarts read from KV store
- Fail-fast on startup: if RBAC enabled and ADMIN role has zero members, coordinator refuses to start with a clear error message telling admin to either assign an ADMIN member or disable RBAC
- ADMIN is immutable: cannot be dropped, it's a system constant. Only membership changes are allowed

### Service Listing Data
- Built-in roles (ADMIN, PUBLIC) appear in sys.roles listings as system-created entries
- PUBLIC implicit memberships are NOT listed in sys.membership -- only explicit role assignments shown
- Service layer converts protos to simple POJOs for system table consumption (not raw proto objects)
- Listing endpoints restricted to ADMIN-only -- non-admin users cannot query sys.roles, sys.privileges, sys.membership

### Claude's Discretion
- AccessControlListingManager interface design (method signatures, POJO class structure)
- Internal organization of RbacService (single class vs helper classes)
- Exact fail-fast error message wording
- How bootstrap detects "first user" vs "subsequent restart"

</decisions>

<specifics>
## Specific Ideas

- Privilege resolution should feel simple: collect all roles (explicit + PUBLIC), check if any has the grant, done
- Bootstrap fail-fast is a safety net -- the happy path is that FirstLoginSetupService always creates the ADMIN membership before RBAC enforcement matters
- sys.roles showing ADMIN and PUBLIC gives admins a complete picture of the role landscape without needing to know about synthetic constants

</specifics>

<deferred>
## Deferred Ideas

None -- discussion stayed within phase scope

</deferred>

---

*Phase: 03-service-layer*
*Context gathered: 2026-02-17*
