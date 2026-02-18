# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-17)

**Core value:** Users can only access views and UDFs they've been explicitly granted access to, with deny-by-default policy and admin bypass -- closing the open-access gap in Dremio OSS.
**Current focus:** Phase 6 in progress. Plan 06-02 (catalog visibility filtering) complete. Plan 06-03 (REST RBAC resource) is next.

## Current Position

Phase: 6 of 6 (REST API and Access Path Hardening) -- IN PROGRESS
Plan: 2 of 3 in current phase
Status: 06-02 complete (META-03 catalog visibility filtering). 06-03 (REST RBAC resource) pending.
Last activity: 2026-02-18 -- 06-02 catalog visibility filtering (2 tasks, 3 files, 2 min)

Progress: [█████████░] 90%

## Performance Metrics

**Velocity:**
- Total plans completed: 13
- Average duration: 4.9 min
- Total execution time: ~1.1 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-design-and-proto-schema | 2 | 20 min | 10 min |
| 02-persistence-layer | 2 | 10 min | 5 min |
| 03-service-layer | 2 | 4 min | 2 min |
| 04-catalog-enforcement-and-di-wiring | 3 | 13 min | 4.3 min |
| 05-ddl-handlers-and-system-tables | 3 | 19 min | 6.3 min |
| 06-rest-api-and-access-path-hardening | 1 (of 3) | 2 min | 2 min |

**Recent Trend:**
- Last 5 plans: 4 min, 8 min, 8 min, 3 min, 2 min
- Trend: stable-fast

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
- [03-01]: isAdminMember() was private -- changed to public in 05-01 for DDL handler access
- [03-01]: assignBootstrapAdmin() uses "SYSTEM" as grantedBy to distinguish bootstrap from user-initiated membership
- [03-01]: getRoleInfo() returns ADMIN/PUBLIC with role_type=SYSTEM, user-created with role_type=USER
- [03-01]: getMembershipInfo() returns only explicit memberships -- PUBLIC implicit membership excluded per locked decision
- [03-02]: Added revokePrivilege test beyond plan's 24 enumerated tests -- verification requires every public method to be tested
- [03-02]: Iterable results from AccessControlListingManager converted to List via StreamSupport for assertion with AssertJ
- [04-01]: RbacService registered in DACDaemonModule (not CatalogServiceImpl) because stores use non-legacy KVStoreProvider
- [04-01]: RbacService is @Nullable in CatalogImpl -- non-DAC test contexts may not have it available
- [04-01]: DremioConfig null check added as first guard in validatePrivilege() for test contexts
- [04-01]: resolveRbacObjectType maps by privilege: EXECUTE -> FUNCTION, all others -> VDS
- [04-01]: Test call sites pass () -> null for RbacService provider -- RBAC disabled by default in tests
- [04-02]: isRbacDeniedForVds checks instanceof ViewTable to skip PDS -- physical datasets are never RBAC-gated
- [04-02]: getFunctions RBAC check at entry point (before version context resolution) for early rejection
- [04-02]: RBAC denial returns null/empty (not exception) preserving "not found" information hiding semantics
- [04-03]: Tests use anyString() matcher for object path args because constructFullPath may quote identifiers with backticks
- [04-03]: getTable VDS-denied test verifies through validatePrivilege path since DatasetManager is internal and not directly mockable
- [04-03]: System user bypass tested via newCatalogImplForUser("$dremio$") helper with isolated SchemaConfig and AuthorizationContext
- [05-01]: isAdminMember() changed from private to public -- needed by DDL handler admin checks (plan 05-02)
- [05-01]: Provider<RbacService> added as LAST parameter to SabotContext and ContextService -- minimizes risk of miscounting 40+ constructor params
- [05-01]: SabotQueryContext default getRbacService() returns null -- safe for non-DAC test contexts
- [05-01]: SabotNode test harness uses Providers.of(null) -- RBAC disabled by default in unit tests
- [05-01]: getAccessControlListingManager() delegates to rbacServiceProvider -- system tables sys.roles, sys.privileges, sys.membership now work
- [05-02]: DDL handlers named CatalogGrantHandler/CatalogRevokeHandler (NOT GrantHandler/RevokeHandler) -- SqlGrantOnCatalog handles VDS/Function grants, not SqlGrant
- [05-02]: Object path uses String.join('.', entity.names) -- matches RbacConfig.grantKey() dot-delimited format ensuring grant and check keys are identical
- [05-02]: DDL works regardless of RBAC_ENABLED flag -- admins set up roles/grants before enabling enforcement; flag only gates enforcement in CatalogImpl

### Pending Todos

None yet.

### Blockers/Concerns

- [RESOLVED 04-01]: CatalogImpl injection path confirmed as constructor parameter (17th+18th args), resolved during Phase 4 execution
- [Research]: REST endpoint audit scope for Phase 6 is unknown -- DatasetVersionResource (1422 lines) may bypass CatalogImpl
- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available. Protoc 3.6.0 used directly for proto verification. Full Maven compile blocked until Java 21 JDK is installed.

### Phase 5 Decisions

- [05-planning]: DDL works even when RBAC enforcement is OFF -- allows admins to set up roles/grants before enabling enforcement
- [05-planning]: All DDL is admin-only -- every handler checks rbacService.isAdminMember(userName) before proceeding
- [05-planning]: Provider<RbacService> added as LAST parameter to SabotContext and ContextService constructors to minimize risk
- [05-planning]: System tables need NO code changes -- they already exist and delegate to AccessControlListingManager; wiring RbacService into SabotContext makes them work
- [05-planning]: isAdminMember() changed from private to public in RbacService for handler admin checks
- [05-planning]: 6 handler classes at exact FQCNs expected by SQL parsers via Class.forName() -- no parser modifications needed
- [05-01]: Provider threading pattern: add as last param at each layer (DACDaemonModule -> ContextService -> SabotContext) -- consistent with AccelerationManager/MetadataIOPool patterns
- [05-02]: CatalogGrantHandler/CatalogRevokeHandler (not GrantHandler/RevokeHandler) -- grammar routes VDS/Function grants to SqlGrantOnCatalog, not SqlGrant
- [05-02]: String.join('.', entity.names) for object path -- consistent with RbacConfig.grantKey() format
- [05-03]: Tests use direct SqlNode construction (not OPERATOR.createCall()) -- simpler, matches existing handler test patterns
- [05-03]: assertThatThrownBy (AssertJ) for exception testing -- more precise message verification than @Test(expected)

### Phase 6 Decisions

- [06-02]: RbacService and DremioConfig added as last two @Nullable constructor params in CatalogServiceHelper -- follows "last param" pattern from Phase 5
- [06-02]: filterByVisibility() applied post-pagination-trim -- pages may be smaller than maxChildren when RBAC filters active; documented v1 limitation, acceptable for OSS scale
- [06-02]: Separate isFunctionVisibleToUser(FunctionConfig) for getTopLevelCatalogItems() -- FunctionConfig is available directly without NameSpaceContainer wrapping
- [06-02]: PDS always visible, only VIRTUAL_DATASET type is RBAC-gated -- locked v1 decision maintained
- [06-02]: Test call sites pass null, null for new params -- disables RBAC filtering in test contexts (filterByVisibility short-circuits on null rbacService)

## Session Continuity

Last session: 2026-02-18
Stopped at: 06-02 complete (catalog visibility filtering). Ready for 06-03 (REST RBAC resource).
Resume file: .planning/phases/06-rest-api-and-access-path-hardening/06-02-SUMMARY.md
