# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-17)

**Core value:** Users can only access views and UDFs they've been explicitly granted access to, with deny-by-default policy and admin bypass -- closing the open-access gap in Dremio OSS.
**Current focus:** Phase 2: Persistence Layer

## Current Position

Phase: 2 of 6 (Persistence Layer)
Plan: 1 of 2 in current phase
Status: Plan 02-01 complete, ready for 02-02 (unit tests)
Last activity: 2026-02-17 -- Completed 02-01 CRUD implementation for RoleStore, GrantStore, MembershipStore

Progress: [███░░░░░░░] 25%

## Performance Metrics

**Velocity:**
- Total plans completed: 3
- Average duration: 9 min
- Total execution time: 0.45 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-design-and-proto-schema | 2 | 20 min | 10 min |
| 02-persistence-layer | 1 | 8 min | 8 min |

**Recent Trend:**
- Last 5 plans: 8 min, 12 min, 8 min
- Trend: on track

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

### Pending Todos

None yet.

### Blockers/Concerns

- [Research]: CatalogImpl injection path (constructor parameter vs SabotContext lookup) has MEDIUM confidence -- needs concrete tracing during Phase 4 planning
- [Research]: REST endpoint audit scope for Phase 6 is unknown -- DatasetVersionResource (1422 lines) may bypass CatalogImpl
- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available. Protoc 3.6.0 used directly for proto verification. Full Maven compile blocked until Java 21 JDK is installed.

## Session Continuity

Last session: 2026-02-17
Stopped at: Completed 02-01-PLAN.md (RBAC store CRUD implementation)
Resume file: .planning/phases/02-persistence-layer/02-01-SUMMARY.md
