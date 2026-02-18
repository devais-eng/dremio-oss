# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-17)

**Core value:** Users can only access views and UDFs they've been explicitly granted access to, with deny-by-default policy and admin bypass -- closing the open-access gap in Dremio OSS.
**Current focus:** Phase 4: Catalog Enforcement and DI Wiring

## Current Position

Phase: 4 of 6 (Catalog Enforcement and DI Wiring)
Plan: 0 of 3 in current phase
Status: Phase 4 context gathered, ready to plan Phase 4
Last activity: 2026-02-18 -- Phase 4 context gathered (denial UX, bypass scope, DI wiring, test strategy)

Progress: [█████░░░░░] 50%

## Performance Metrics

**Velocity:**
- Total plans completed: 6
- Average duration: 6 min
- Total execution time: 0.57 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-design-and-proto-schema | 2 | 20 min | 10 min |
| 02-persistence-layer | 2 | 10 min | 5 min |
| 03-service-layer | 2 | 4 min | 2 min |

**Recent Trend:**
- Last 5 plans: 8 min, 12 min, 8 min, 2 min, 2 min
- Trend: accelerating

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: 6-phase structure derived from requirement clustering and research-recommended build order
- [Roadmap]: Phase 1 has one explicit requirement (ENFC-09 feature flag) plus foundational design artifacts
- [Roadmap]: Phase 5 is the largest phase (19 requirements) -- DDL handlers and system tables cluster naturally because handlers depend on the same DI wiring
- [Phase 1 Discussion]: All RBAC code inside sabot/kernel module, not a new services/rbac module
- [Phase 1 Discussion]: KV store prefix `oss_rbac_` with pipe `|` separator for EE isolation
- [Phase 1 Discussion]: Role IDs are slugified names (not UUIDs), immutable -- no rename support
- [Phase 1 Discussion]: Feature flag is config-file based (dremio.conf), requires restart, defaults to OFF
- [Phase 1 Discussion]: Strict deny-everything on first enable -- no auto-grant PUBLIC
- [Phase 1 Discussion]: Fail-fast if RBAC enabled but ADMIN role has no members
- [Phase 1 Discussion]: Privilege and object types stored as strings in proto, not enums
- [Phase 1 Discussion]: ADMIN and PUBLIC are synthetic constants -- never written to KV store
- [Phase 1 Discussion]: META-03 (catalog visibility filtering) added to v1 Phase 6
- [01-01]: DremioConfig.RBAC_ENABLED = "services.rbac.enabled" (shorter naming form, not _BOOLEAN suffix)
- [01-01]: Proto field numbers are permanent -- Role(1-4), Grant(1-6), Membership(1-4) are frozen
- [01-01]: KV key format: oss_rbac_roles=role_id, oss_rbac_grants=role_id|object_type|object_path|privilege, oss_rbac_memberships=user_name|role_id
- [01-01]: Use Format.ofString() for KV store keys (NOT Format.ofCompoundFormat()) for human-readable inspection
- [01-01]: uint64 timestamps in proto (epoch millis) consistent with script.proto, FunctionRPC.proto patterns
- [01-02]: StoreCreator inner class name is LOCKED -- renaming changes KV store identity and makes persisted data inaccessible
- [01-02]: Non-legacy KVStoreCreationFunction (not LegacyKVStoreCreationFunction) used -- follows modern datastore API pattern
- [01-02]: Provider<KVStoreProvider> + Suppliers.memoize() pattern for lazy store initialization (from ScriptStoreImpl)
- [01-02]: Store name constants via RbacConfig references -- single source of truth for store names
- [02-01]: RbacEntityNotFoundException is checked (extends Exception) -- callers must handle delete/revoke/remove errors explicitly
- [02-01]: RbacEntityAlreadyExistsException is unchecked (extends RuntimeException) -- wraps ConcurrentModificationException to isolate callers from datastore internals
- [02-01]: get() returns nullable proto (not Optional) -- locked Phase 1 decision maintained
- [02-01]: deleteByRole() is package-private -- cascade contract is internal to the rbac package, not a public API
- [02-01]: Keys collected to List before delete in deleteByRole() -- KVStore.find() returns a one-shot cursor; modifying store while iterating is undefined behavior
- [02-02]: Tests use LocalKVStoreProvider (not custom HashMap mocks) -- exercises real KVStore serialization path including proto encoding/decoding
- [02-02]: Single LocalKVStoreProvider shared across all three store instances in cascade delete test -- correct because each store uses a different KV store name within the same provider
- [02-02]: testListByRole_prefixSafety validates that "dev" prefix (with pipe: "dev|") does not match "devops|..." keys -- critical boundary case for the KEY_SEP design decision
- [Phase 3 Discussion]: hasPrivilege() returns boolean, OR logic across roles, ADMIN-first short-circuit
- [Phase 3 Discussion]: No caching in v1 -- hit KV store every hasPrivilege() call
- [Phase 3 Discussion]: PUBLIC is synthetic, grantable, immutable, checked alongside explicit roles
- [Phase 3 Discussion]: Bootstrap assigns ADMIN during FirstLoginSetupService, one-time only
- [Phase 3 Discussion]: Fail-fast = startup error if RBAC enabled with zero ADMIN members
- [Phase 3 Discussion]: ADMIN is immutable (cannot be dropped)
- [Phase 3 Discussion]: Built-in roles shown in sys.roles; PUBLIC memberships excluded from sys.membership
- [Phase 3 Discussion]: Service returns simple POJOs for system table consumption (not raw protos)
- [Phase 3 Discussion]: Listing endpoints ADMIN-only
- [03-01]: RbacService constructor takes 3 stores with no DI annotations -- Phase 4 handles wiring
- [03-01]: isAdminMember() is private (not package-private) -- only used internally by hasPrivilege()
- [03-01]: assignBootstrapAdmin() uses "SYSTEM" as grantedBy to distinguish bootstrap from user-initiated membership
- [03-01]: getRoleInfo() returns ADMIN/PUBLIC with role_type=SYSTEM, user-created with role_type=USER
- [03-01]: getMembershipInfo() returns only explicit memberships -- PUBLIC implicit membership excluded per locked decision
- [03-02]: Added revokePrivilege test beyond plan's 24 enumerated tests -- verification requires every public method to be tested
- [03-02]: Iterable results from AccessControlListingManager converted to List via StreamSupport for assertion with AssertJ

### Pending Todos

None yet.

### Blockers/Concerns

- [Research]: CatalogImpl injection path (constructor parameter vs SabotContext lookup) has MEDIUM confidence -- needs concrete tracing during Phase 4 planning
- [Research]: REST endpoint audit scope for Phase 6 is unknown -- DatasetVersionResource (1422 lines) may bypass CatalogImpl
- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available. Protoc 3.6.0 used directly for proto verification. Full Maven compile blocked until Java 21 JDK is installed.

## Session Continuity

Last session: 2026-02-18
Stopped at: Phase 4 context gathered. Ready for Phase 4 planning.
Resume file: .planning/phases/04-catalog-enforcement-and-di-wiring/04-CONTEXT.md
