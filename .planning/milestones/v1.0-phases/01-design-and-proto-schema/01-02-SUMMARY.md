---
phase: 01-design-and-proto-schema
plan: 02
subsystem: auth
tags: [kvstore, rbac, protobuf, kvstore-creator, dremio-datastore]

# Dependency graph
requires:
  - "01-01: rbac.proto with Role, Grant, Membership messages and RbacProto outer class"
provides:
  - "com.dremio.exec.rbac package with four Java classes"
  - "RbacConfig: ROLES_STORE, GRANTS_STORE, MEMBERSHIPS_STORE constants + grantKey/membershipKey helpers"
  - "RoleStore.StoreCreator: KVStoreCreationFunction for oss_rbac_roles (LOCKED name)"
  - "GrantStore.StoreCreator: KVStoreCreationFunction for oss_rbac_grants (LOCKED name)"
  - "MembershipStore.StoreCreator: KVStoreCreationFunction for oss_rbac_memberships (LOCKED name)"
affects:
  - 02-kvstore-foundation
  - 03-enforcement-core
  - 04-catalog-wiring
  - 05-ddl-and-system-tables
  - 06-rest-api-and-audit

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "KVStoreCreationFunction<String, ProtobufMessage> with StoreBuildingFactory (non-legacy API)"
    - "Provider<KVStoreProvider> injected via @Inject constructor, Suppliers.memoize() for lazy initialization"
    - "Format.ofString() for all key formats (human-readable), Format.ofProtobuf(Class) for proto3 values"

key-files:
  created:
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacConfig.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RoleStore.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java
  modified: []

key-decisions:
  - "StoreCreator inner class name is LOCKED -- renaming changes KV store identity and makes persisted data inaccessible"
  - "Non-legacy KVStoreCreationFunction (not LegacyKVStoreCreationFunction) used -- follows modern datastore API pattern from LocalProfileKVStoreCreator"
  - "Supplier<KVStore<K,V>> field with Suppliers.memoize() in constructor -- matches ScriptStoreImpl lazy-init pattern"
  - "Store name constants via RbacConfig references (not inline strings) -- single source of truth for store names"
  - "grantKey/membershipKey referenced in store Javadoc via @link -- Phase 2 will use them in actual CRUD methods"

patterns-established:
  - "RBAC store scaffold pattern: @Inject constructor with Provider<KVStoreProvider>, Suppliers.memoize, inner StoreCreator class"
  - "All store classes import from com.dremio.exec.rbac.proto.RbacProto.MessageName (dot-import)"

requirements-completed: []

# Metrics
duration: 12min
completed: 2026-02-17
---

# Phase 1 Plan 02: KV Store Package Scaffold Summary

**com.dremio.exec.rbac package with RbacConfig constants, grantKey/membershipKey composite key helpers, and three KV store creator stubs (RoleStore, GrantStore, MembershipStore) using non-legacy KVStoreCreationFunction with proto3-backed values**

## Performance

- **Duration:** 12 min
- **Started:** 2026-02-17T14:53:32Z
- **Completed:** 2026-02-17T15:05:32Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Created `com.dremio.exec.rbac` package under `sabot/kernel/src/main/java/`
- Created `RbacConfig.java` with three `oss_rbac_`-prefixed store name constants, pipe separator constant, and `grantKey()`/`membershipKey()` helpers with Javadoc explaining composite key format
- Created `RoleStore.java` with `KVStoreCreationFunction<String, Role>` inner class `StoreCreator` for `oss_rbac_roles`
- Created `GrantStore.java` with `KVStoreCreationFunction<String, Grant>` inner class `StoreCreator` for `oss_rbac_grants`
- Created `MembershipStore.java` with `KVStoreCreationFunction<String, Membership>` inner class `StoreCreator` for `oss_rbac_memberships`

## Task Commits

Each task was committed atomically:

1. **Task 1: Create RbacConfig.java** - `88e030dee` (feat)
2. **Task 2: Create RoleStore, GrantStore, MembershipStore** - `6ca3e27ec` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacConfig.java` - Store name constants (`ROLES_STORE`, `GRANTS_STORE`, `MEMBERSHIPS_STORE` with `oss_rbac_` prefix), `KEY_SEP = "|"`, `grantKey(roleId, objectType, objectPath, privilege)`, `membershipKey(userName, roleId)`. No imports required.
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RoleStore.java` - `KVStoreCreationFunction<String, Role>` with `Format.ofString()` key and `Format.ofProtobuf(Role.class)` value. Store name via `RbacConfig.ROLES_STORE`.
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java` - `KVStoreCreationFunction<String, Grant>` with `Format.ofString()` key and `Format.ofProtobuf(Grant.class)` value. Store name via `RbacConfig.GRANTS_STORE`. Class Javadoc documents composite key format.
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java` - `KVStoreCreationFunction<String, Membership>` with `Format.ofString()` key and `Format.ofProtobuf(Membership.class)` value. Store name via `RbacConfig.MEMBERSHIPS_STORE`. Class Javadoc documents composite key format.

## CRITICAL: Locked Names (WARNING -- DO NOT RENAME)

The inner class `StoreCreator` in each store class is the **permanent KV store identifier**. Renaming it changes the store identity and makes all persisted data inaccessible.

| Class                | Inner Class  | Store Name               | Used by                          |
|----------------------|--------------|--------------------------|----------------------------------|
| `RoleStore`          | `StoreCreator` | `oss_rbac_roles`         | `kvStoreProvider.get().getStore(RoleStore.StoreCreator.class)` |
| `GrantStore`         | `StoreCreator` | `oss_rbac_grants`        | `kvStoreProvider.get().getStore(GrantStore.StoreCreator.class)` |
| `MembershipStore`    | `StoreCreator` | `oss_rbac_memberships`   | `kvStoreProvider.get().getStore(MembershipStore.StoreCreator.class)` |

## Store Name Constants (EE Isolation)

All store names use the `oss_rbac_` prefix to isolate from Dremio EE's own RBAC implementation:

| Constant                        | Value                   | Key Format                                         |
|---------------------------------|-------------------------|----------------------------------------------------|
| `RbacConfig.ROLES_STORE`        | `oss_rbac_roles`        | `{role_id}` (simple string)                        |
| `RbacConfig.GRANTS_STORE`       | `oss_rbac_grants`       | `{role_id}|{object_type}|{object_path}|{privilege}` |
| `RbacConfig.MEMBERSHIPS_STORE`  | `oss_rbac_memberships`  | `{user_name}|{role_id}`                            |

## Import Paths Used (Confirmed from Codebase)

| Import | Source |
|--------|--------|
| `com.dremio.datastore.api.KVStore` | Standard datastore API |
| `com.dremio.datastore.api.KVStoreCreationFunction` | Non-legacy API (confirmed in `LocalProfileKVStoreCreator`) |
| `com.dremio.datastore.api.KVStoreProvider` | Standard datastore API |
| `com.dremio.datastore.api.StoreBuildingFactory` | Non-legacy API (confirmed in `LocalProfileKVStoreCreator`) |
| `com.dremio.datastore.format.Format` | Standard datastore format API |
| `com.dremio.exec.rbac.proto.RbacProto.Role/Grant/Membership` | Generated from rbac.proto by protobuf-maven-plugin |
| `com.google.common.base.Suppliers` | Guava (on kernel classpath) |
| `java.util.function.Supplier` | Java standard (compatible with Guava's Supplier via memoize) |
| `javax.inject.Inject` | JSR-330 (on kernel classpath) |
| `javax.inject.Provider` | JSR-330 (on kernel classpath) |

Note: The sabot/kernel module also uses `LegacyKVStoreCreationFunction` for older stores (e.g., `OptionValueStore`, `CatalogSourceDataCreator`). The new RBAC stores intentionally use the **non-legacy** `KVStoreCreationFunction` API, consistent with newer additions like `LocalProfileKVStoreCreator.IntermediateProfileStoreCreator`.

## What Phase 2 Needs to Add

Phase 2 (02-kvstore-foundation) will add CRUD methods to each store class on top of these stubs:

- **RoleStore:** `get(String roleId)`, `put(String roleId, Role role)`, `delete(String roleId)`, `list()` (Iterable over all roles)
- **GrantStore:** `get(String grantKey)`, `put(String grantKey, Grant grant)`, `delete(String grantKey)`, `listByRole(String roleId)` (prefix scan by roleId)
- **MembershipStore:** `get(String membershipKey)`, `put(String membershipKey, Membership membership)`, `delete(String membershipKey)`, `listByUser(String userName)`, `listByRole(String roleId)` (prefix scans)

Key helper invocations:
- `RbacConfig.grantKey(roleId, objectType, objectPath, privilege)` for all GrantStore lookups
- `RbacConfig.membershipKey(userName, roleId)` for all MembershipStore lookups

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Maven build requires Java 21 (enforcer plugin, range [21,22)) but only Java 11/17 are available. Structural verification was performed via grep/file inspection instead. All import paths were confirmed by reading existing examples in the codebase (`ScriptStoreImpl`, `LocalProfileKVStoreCreator`, `CatalogSourceDataCreator`). Full Maven compilation blocked until a Java 21 JDK is installed.

## Next Phase Readiness

- Package and class structure locked -- Phase 2 adds CRUD methods to existing files
- Store names frozen: `oss_rbac_roles`, `oss_rbac_grants`, `oss_rbac_memberships`
- `StoreCreator` inner class names frozen in all three store classes
- `RbacConfig.grantKey()` and `RbacConfig.membershipKey()` ready for use in Phase 2 CRUD implementations
- Import paths confirmed from codebase examples -- no guessing needed in Phase 2

---
*Phase: 01-design-and-proto-schema*
*Completed: 2026-02-17*

## Self-Check: PASSED

- FOUND: `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacConfig.java`
- FOUND: `sabot/kernel/src/main/java/com/dremio/exec/rbac/RoleStore.java`
- FOUND: `sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java`
- FOUND: `sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java`
- FOUND: `.planning/phases/01-design-and-proto-schema/01-02-SUMMARY.md`
- FOUND: commit `88e030dee` (feat(01-02): add RbacConfig)
- FOUND: commit `6ca3e27ec` (feat(01-02): add RoleStore, GrantStore, MembershipStore)
