# RBAC Stack Research — Dremio OSS

**Research type**: Stack dimension — permission storage and enforcement
**Date**: 2026-02-17
**Scope**: Subsequent milestone; existing system already understood

---

## Summary

The stack for RBAC in Dremio OSS is almost entirely dictated by patterns that already exist and are used consistently across a dozen services. There is no gap requiring a new dependency or unfamiliar abstraction. The core pattern is: **proto3 Protobuf value + string key + LegacyKVStoreCreationFunction + service class injected via `Provider<LegacyKVStoreProvider>` + `SingletonRegistry` binding**.

The one divergence from the simplest pattern is that the grant lookup (permission check at query time) has a read pattern that does not map cleanly to the standard indexed-store scan. This is addressed in the caching section below.

---

## 1. Protobuf Schema Design

### Recommendation: Use proto3 with Format.ofProtobuf(), not Protostuff

**Why**: Two serialization formats coexist in Dremio: Protostuff (.proto files compiled by Protostuff, using `io.protostuff.Message`) and proto3 (compiled by the standard protoc, using `com.google.protobuf.Message`). Newer services — `scripts`, `jobcounts`, `accelerator` reflected goals/entries — use proto3 with `Format.ofProtobuf()`. Older services like `users`, `tokens`, `configuration` use Protostuff with `Format.ofProtostuff()`.

For new code, proto3 is the right choice. `Format.ofProtobuf(MyMessage.class)` is directly supported as a first-class path in `Format.java`. Schema evolution is cleaner (default field values, no required fields that break on schema change). The `ScriptStoreImpl` at `services/scripts/src/main/java/com/dremio/service/scripts/ScriptStoreImpl.java` is the cleanest recent exemplar — it uses `Format.ofProtobuf(Script.class)` with a proto3 schema.

**Confidence**: High. `Format.ofProtobuf()` is fully supported and is the newer pattern; zero evidence of proto3 causing issues.

### Three proto messages needed

```protobuf
syntax = "proto3";
package com.dremio.service.rbac.proto;
option java_package = "com.dremio.service.rbac.proto";
option optimize_for = SPEED;
option java_outer_classname = "RbacProto";

// Stored in "rbac_roles" table. Key: role_id (UUID string)
message Role {
  string role_id    = 1;  // UUID, immutable
  string role_name  = 2;  // mutable display name, unique
  string created_by = 3;
  uint64 created_at = 4;
}

// Stored in "rbac_grants" table.
// Key: role_id + "|" + object_type + "|" + object_path + "|" + privilege
// Value is thin — existence of the key IS the grant.
message Grant {
  string role_id     = 1;  // FK to Role.role_id
  string object_type = 2;  // "VDS" or "FUNCTION"
  string object_path = 3;  // canonical dot-joined path, e.g. "space.folder.view"
  string privilege   = 4;  // "SELECT", "CREATE_VIEW", "EXECUTE"
  string granted_by  = 5;
  uint64 granted_at  = 6;
}

// Stored in "rbac_memberships" table. Key: user_name + "|" + role_id
// Value is thin — existence is the membership.
message Membership {
  string user_name  = 1;
  string role_id    = 2;
  string granted_by = 3;
  uint64 granted_at = 4;
}
```

**Why the key design matters more than the value**: RocksDB has no secondary index unless you use an `IndexedStore`. For RBAC permission checks (the hot path), you need to look up "does role X have SELECT on path Y?" by exact key. The key must encode all lookup dimensions so you can use `store.get(compositeKey)` rather than a full scan. See section 2 for key design details.

**What NOT to do**: Do not put a `repeated Grant grants` list inside a Role message. This forces a read-modify-write cycle on every GRANT and cannot be scanned efficiently by object path. Flat messages with composite keys are the right model.

---

## 2. KV Store Key Design

### Three stores, three key shapes

#### Store 1: rbac_roles

- **Key**: `Format.ofString()` — the role UUID
- **Value**: `Format.ofProtobuf(Role.class)`
- **Lookups needed**: get by ID (handler level), scan all (sys.roles), get by name (CREATE ROLE idempotency check)

For "get by name", use `LegacyIndexedStore` with a `DocumentConverter` that writes the `role_name` field to a Lucene index key. This follows the exact pattern in `SimpleUserService.UserGroupStoreBuilder` at `services/users/src/main/java/com/dremio/service/users/SimpleUserService.java` — it indexes `name_lowercase` on `UserInfo` and retrieves via `LegacyFindByCondition` + `SearchQueryUtils.newTermQuery()`.

#### Store 2: rbac_grants

- **Key**: composite string `role_id + "|" + object_type + "|" + object_path + "|" + privilege`
- **Value**: `Format.ofProtobuf(Grant.class)`
- **Lookups needed**: exact existence check (hot path), scan all for a role (REVOKE, sys.privileges), scan all for an object path (drop view cascade revoke)

The `"|"` separator works because role IDs are UUIDs (no `|`) and object paths in Dremio use dots as separators (no `|`). Privileges are enum string names (no `|`).

For the exact existence check (does this user have SELECT on this VDS?) the call chain is:
1. Get user's role IDs from `rbac_memberships` (a small set, cached — see section 3)
2. For each role ID, call `grantStore.get(compositeKey)` — O(1) RocksDB point lookup

**Key design rationale**: This makes the hot path a point lookup, which is what RocksDB is optimized for. Each GRANT and REVOKE is an atomic `put` / `delete` with no read-modify-write.

#### Store 3: rbac_memberships

- **Key**: composite string `user_name + "|" + role_id`
- **Value**: `Format.ofProtobuf(Membership.class)`
- **Lookups needed**: scan all memberships for a user (resolved at query time, then cached), scan all memberships for a role (REVOKE ROLE FROM USER, sys.membership)

For "scan all memberships for a user" — use `LegacyIndexedStore` with `user_name` as an indexed field, then `find(condition where user_name = X)`. This returns the small set of role IDs for a user. This scan happens once per cache miss (see section 3).

**What NOT to do**: Do not use a `Role` message with `repeated string member_ids`. Scanning all roles to find memberships for a given user requires reading every role record. The flat membership store with indexed `user_name` is an O(1) Lucene lookup.

### Concrete exemplars from the codebase

- **Minimal plain KVStore**: `TokenStoreCreator` at `services/tokens/src/main/java/com/dremio/service/tokens/TokenStoreCreator.java` — a static class implementing `LegacyKVStoreCreationFunction`, accessed via `provider.getStore(TokenStoreCreator.class)`. Copy this pattern for `rbac_grants`.
- **IndexedStore with DocumentConverter**: `ScriptStoreImpl.StoreCreator` at `services/scripts/src/main/java/com/dremio/service/scripts/ScriptStoreImpl.java` — implements `IndexedStoreCreationFunction`, uses `Format.ofProtobuf()`, and registers searchable fields via `DocumentConverter`. Copy this pattern for `rbac_roles` and `rbac_memberships`.
- **IndexedStore with LegacyFindByCondition**: `SimpleUserService.findUserByUserName()` uses `SearchQueryUtils.newTermQuery(UserIndexKeys.NAME_LOWERCASE, userName.toLowerCase())` — copy this for role-by-name and memberships-by-user lookups.

---

## 3. Caching Strategy

### Existing mechanism: PermissionCheckCache

`PermissionCheckCache` at `sabot/kernel/src/main/java/com/dremio/exec/catalog/PermissionCheckCache.java` is a Guava `Cache<Key, Value>` keyed on `(username, NamespaceKey)`. It caches the result of `StoragePlugin.hasAccessPermission()` with a configurable TTL. The cache is on `ManagedStoragePlugin`, one per source.

RBAC enforcement needs a similar but distinct cache because:
- The storage plugin cache is per-source; RBAC checks are per-user/per-object at the catalog level
- RBAC cache invalidation must happen on GRANT/REVOKE, not on TTL expiry alone

### Recommendation: A dedicated RbacPermissionCache inside RbacService

```java
// Keyed on (username, objectPath, privilege)
// Value: Boolean (has access)
// Invalidation: explicit on every GRANT/REVOKE mutation
Cache<RbacCacheKey, Boolean> cache = CacheBuilder.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(5, TimeUnit.MINUTES)  // safety TTL
    .build();
```

**Why not reuse `PermissionCheckCache`**: That cache is designed around the storage plugin access check paradigm. It is not accessible from `CatalogImpl.validatePrivilege()` without threading through `ManagedStoragePlugin`. The RBAC check happens above the plugin layer.

**Why a TTL at all if you do explicit invalidation**: Guards against cache leaks if REVOKE fails silently or if two coordinator nodes diverge briefly. Five minutes is conservative and suitable for an initial implementation; it can be made configurable via `OptionManager` in a later iteration.

**Cache population flow**:
1. `validatePrivilege(key, privilege)` is called on `CatalogImpl`
2. Cache miss: ask `RbacService.hasPrivilege(username, path, privilege)`
3. Inside `hasPrivilege`: look up user's role IDs from membership sub-cache (below), then for each role do a point lookup in grant store
4. Cache hit: return cached Boolean directly
5. On `GRANT` or `REVOKE` DDL: call `rbacService.invalidatePermissionCache(affectedUser, affectedPath, privilege)`

**Membership sub-cache**: User-to-roles mapping changes rarely. Cache `Cache<String, Set<String>>` (username -> set of role IDs). Invalidate on membership mutation. This eliminates the Lucene index scan from the common read path so that the common case is: membership sub-cache hit -> N point lookups in grant store (where N = number of roles a user has, typically small).

**What NOT to do**: Do not cache at the RocksDB level (no write-through or read-through cache). RocksDB already has a block cache. Adding another Java-level cache in front of it for individual raw store entries adds complexity without benefit. Cache the derived boolean result, not the raw store entries.

---

## 4. Service Class Structure and Guice Wiring

### Pattern to follow: SimpleUserService + SingletonRegistry

The binding pattern in Dremio is not standard Guice `AbstractModule`. It uses `SingletonRegistry.bind()` and `SingletonRegistry.bindProvider()` via `DACDaemonModule` at `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java`. The RBAC service must follow this pattern.

```java
// In DACDaemonModule.bootstrap() or run():
final RbacService rbacService = new RbacService(
    registry.provider(LegacyKVStoreProvider.class)
);
registry.bind(RbacService.class, rbacService);
registry.bind(AccessControlListingManager.class, rbacService);
registry.bindSelf(rbacService);  // registers for lifecycle (start/close)
```

The `RbacService` class implements `com.dremio.service.Service` (which has `start()` and `close()`) and takes `Provider<LegacyKVStoreProvider>` in its constructor — not the `LegacyKVStoreProvider` directly. This deferred initialization via `Suppliers.memoize()` is used everywhere. See `SimpleUserService` constructor (line 101) and `ReflectionGoalsStore` constructor (line 76).

### Handler access to RbacService

The SQL DDL handlers (`GrantHandler`, `RevokeHandler`, `RoleCreateHandler`) receive a `QueryContext`. They reach the RBAC service through:

```
QueryContext.sabotQueryContext (SabotQueryContext)
  -> SabotContext.getRbacService()
```

Concretely:
1. Add `getRbacService()` to the `PluginSabotContext` interface at `sabot/kernel/src/main/java/com/dremio/exec/catalog/PluginSabotContext.java`
2. Implement it in `SabotContext` to return the registered `RbacService` from the registry
3. In handlers: `context.getSabotContext().getRbacService().createRole(roleName)`

This is exactly how `AccessControlListingManager` is exposed via `sabotContext.getAccessControlListingManager()` at `sabot/kernel/src/main/java/com/dremio/exec/server/SabotContext.java` line 554 — currently returning null in OSS, ready to be filled.

**What NOT to do**: Do not pass `RbacService` through `QueryContext` directly (adding a new constructor parameter breaks a long construction chain). Do not use a static singleton. Do not use `@Inject` on the handler class itself — handlers are instantiated reflectively by `SimpleDirectHandler.Creator.toDirectHandler()`, not by Guice.

---

## 5. Integrating with AccessControlListingManager

The interface `AccessControlListingManager` at `sabot/kernel/src/main/java/com/dremio/exec/store/sys/accesscontrol/AccessControlListingManager.java` is the bridge between the RBAC store and the system tables `sys.roles`, `sys.privileges`, `sys.membership`.

`SabotContext.getAccessControlListingManager()` currently returns `null`. The `RbacService` should implement this interface directly:

```java
public class RbacService implements Service, AccessControlListingManager {
    @Override
    public Iterable<SysTableRoleInfo> getRoleInfo() {
        // scan rbac_roles store, map Role -> SysTableRoleInfo
    }
    @Override
    public Iterable<SysTablePrivilegeInfo> getPrivilegeInfo() {
        // scan rbac_grants store, map Grant -> SysTablePrivilegeInfo
    }
    @Override
    public Iterable<SysTableMembershipInfo> getMembershipInfo() {
        // scan rbac_memberships store, map Membership -> SysTableMembershipInfo
    }
}
```

The sys table schema classes — `SysTableRoleInfo`, `SysTablePrivilegeInfo`, `SysTableMembershipInfo` — are already defined with the right fields in the `accesscontrol` package. The mapping from protobuf store values to these classes is straightforward field projection.

**Confidence**: High. The interface is already defined, the system tables are already wired to it via `SystemTable.ROLES`, `SystemTable.PRIVILEGES`, `SystemTable.MEMBERSHIP` in `SystemTable.java`, and the `SabotContext` method is a hook waiting to be filled.

---

## 6. Enforcement in CatalogImpl.validatePrivilege()

The current implementation at line 2767 of `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java`:

```java
public void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege) {
    // For the default implementation, don't validate privilege.
}
```

`CatalogImpl` already holds `CatalogIdentity identity` from `options.getSchemaConfig().getAuthContext().getSubject()`. The username is `identity.getName()`.

The wired-up implementation needs:
1. Get current user: `identity.getName()` — already available
2. Admin bypass: check if user is in the ADMIN role via `rbacService`
3. PUBLIC role: always include grants for the synthetic PUBLIC role in the check
4. Permission check: call `rbacService.hasPrivilege(username, key.toString(), privilege.name())`
5. Deny: throw `UserException.permissionError().message("Access denied on " + key).build(logger)`

`CatalogImpl` needs access to `RbacService`. It is constructed in `CatalogServiceImpl`. The cleanest injection is adding `Optional<RbacService> rbacService` as a constructor parameter with a default of `Optional.empty()` — when empty, `validatePrivilege` remains a no-op (backward compatible). When present, enforcement is active.

**Confidence**: High for the enforcement logic itself. Medium for the constructor injection point — the construction chain from `DACDaemonModule` to `CatalogImpl` must be traced to confirm the `RbacService` reference is available at that point. An alternative is a service-locator lookup via `SabotContext` from within `CatalogImpl` (SabotContext is already accessible there), which avoids touching the constructor.

---

## 7. Handler Dispatch: Overriding the Enterprise Edition Bridge

The existing `SqlCreateRole.toDirectHandler()` at `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/SqlCreateRole.java` already has an OSS branch:

```java
} else {
    cl = Class.forName("com.dremio.exec.planner.sql.handlers.RoleCreateHandler");
}
```

This class does not exist in OSS, so it throws a reflective exception. The solution is to create `RoleCreateHandler` at exactly that class path. The reflective dispatch (`Class.forName`) is intentional — it allows EE and OSS to coexist in the same parser module by having EE override OSS handlers by providing the class on the classpath. Placing the OSS handler at `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleCreateHandler.java` means it will be found in OSS and overridden in EE (which provides a class at the same name from a different jar). This is the correct pattern to follow.

Similarly for `SqlGrant.toDirectHandler()` which references `com.dremio.exec.planner.sql.handlers.GrantHandler` and `SqlRevoke.toDirectHandler()` which references `com.dremio.exec.planner.sql.handlers.RevokeHandler`. Create those classes in the kernel module.

**Confidence**: High — the reflective dispatch pattern is intentional and well-established in the codebase.

---

## 8. What NOT To Do

### Do not implement permission storage as an in-memory map initialized at startup

The temptation is to load all grants into a `HashMap` at startup and check against it. This breaks in a multi-node or future distributed configuration where another node writes a GRANT. Use RocksDB as the source of truth; use the Guava cache for read performance with explicit invalidation on mutation.

### Do not use IndexedStore for rbac_grants

`IndexedStore` adds Lucene overhead to every write. For `rbac_grants`, where the hot path is a point lookup by composite key and the only scan needed is "all grants for a role" (a cold admin path), a plain `LegacyKVStore` is sufficient and simpler. Use `LegacyIndexedStore` only for `rbac_roles` (need name lookup) and `rbac_memberships` (need user lookup).

### Do not create a new Guice AbstractModule

Dremio does not use standard Guice modules for service wiring — it uses `SingletonRegistry`. Creating a `GuiceModule extends AbstractModule` and installing it will not integrate with the lifecycle management (start/stop ordering) that `SingletonRegistry` provides. Follow the pattern in `DACDaemonModule`.

### Do not use Format.ofProtostuff() for new protos

Protostuff uses `io.protostuff.Message` and a different compiler toolchain. The newer proto3 path (`Format.ofProtobuf()`) handles field additions/deletions gracefully without the `required` field trap and generates cleaner Java. Existing services use Protostuff because they predate the migration; new code should use proto3.

### Do not add RBAC enforcement in the Calcite planner

Catalog-level enforcement at `validatePrivilege()` and at the dataset resolution path (`getTable`, `getFunction`) covers all access paths: SQL queries, REST API catalog lookups, and reflection planning. Adding checks in the planner would be a second enforcement layer that duplicates logic without adding coverage.

### Do not couple the PUBLIC role to a database record

PUBLIC is a synthetic role. Every user implicitly belongs to it. Implement it as a special string constant (`"PUBLIC"`) that is included in every `hasPrivilege()` check alongside the user's real roles — not as a row in `rbac_memberships`. This avoids the need to maintain N membership rows (one per user) and the need to update them when users are created or deleted.

---

## 9. Decision Summary

| Concern | Recommendation | Confidence |
|---------|---------------|------------|
| Value serialization | proto3 + `Format.ofProtobuf()` | High |
| Key format: roles | `Format.ofString()` UUID | High |
| Key format: grants | Composite string `role\|type\|path\|privilege` | High |
| Key format: memberships | Composite string `user\|role` | High |
| IndexedStore for roles? | Yes — need role-by-name lookup | High |
| IndexedStore for grants? | No — point lookup only; cold scans via store.find(all) | High |
| IndexedStore for memberships? | Yes — need memberships-by-user lookup | High |
| Caching | Dedicated RbacPermissionCache in RbacService (Guava), explicit invalidation on mutation | High |
| Membership sub-cache | Yes — separate Cache<String, Set<String>>, invalidate on membership change | High |
| Service wiring | `SingletonRegistry.bind()` in `DACDaemonModule` | High |
| System tables integration | `RbacService implements AccessControlListingManager` | High |
| Handler dispatch | Create `GrantHandler`, `RevokeHandler`, `RoleCreateHandler` at the class paths referenced by reflective dispatch | High |
| PUBLIC role implementation | Synthetic constant, not a database record | High |
| ADMIN bypass | First check in `hasPrivilege()`, short-circuit all further checks | High |
| CatalogImpl injection of RbacService | Optional constructor parameter or SabotContext service-locator lookup | Medium |

---

## 10. Suggested New File Locations

Following Dremio's module structure:

| Artifact | Suggested location |
|----------|-------------------|
| `rbac.proto` | `sabot/kernel/src/main/protobuf/rbac.proto` (keeps it in kernel where CatalogImpl lives) |
| `RbacService.java` | `services/rbac/src/main/java/com/dremio/service/rbac/RbacService.java` (new module) |
| `RoleStoreCreator.java` | same module, same package |
| `GrantStoreCreator.java` | same |
| `MembershipStoreCreator.java` | same |
| `RbacPermissionCache.java` | `sabot/kernel/src/main/java/com/dremio/exec/catalog/RbacPermissionCache.java` (near `PermissionCheckCache`) |
| `GrantHandler.java` | `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/GrantHandler.java` |
| `RevokeHandler.java` | same package |
| `RoleCreateHandler.java` | same package |
| `RoleDropHandler.java` | same package |
| `RoleResource.java` (REST) | `dac/backend/src/main/java/com/dremio/dac/api/RoleResource.java` |

If the `services/rbac` module needs to reference `SqlGrant.Privilege` from the kernel, that creates a circular dependency. Resolution: define a separate `RbacPrivilege` enum in the rbac module, or move the privilege enum to a shared `rbac-api` module that both kernel and rbac-service can depend on. Alternatively, use plain strings for privilege names at the store level (the proto uses `string privilege`) and only reference `SqlGrant.Privilege` in the handlers (which are already in the kernel module).

---

*Research: 2026-02-17. Based on analysis of Dremio OSS codebase at commit 799ccbda4.*
