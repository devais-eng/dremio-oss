# Phase 2: Persistence Layer - Research

**Researched:** 2026-02-17
**Domain:** Dremio KVStore CRUD operations, scan-and-filter patterns, in-memory unit testing
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Store Query Methods**
- MembershipStore uses scan-and-filter (not IndexedStore) to answer "roles for user X" and "users in role Y"
- GrantStore supports lookup by role only (not by object path). Object-level grant queries can scan all grants if needed later
- Return types are nullable (not Optional). Follow existing Dremio conventions
- Whether stores expose a "list all" operation: Claude's Discretion

**Error Semantics**
- Duplicate role creation throws an exception ("role already exists"). Not idempotent
- Deleting or revoking something that doesn't exist throws an exception ("not found"). Not a silent no-op
- Store layer validates inputs (null/empty checks on role names, paths, etc.) — defensive at the persistence boundary
- No concurrency concerns for v1 — single coordinator assumption. No locking or check-and-set needed

**Cascade on Delete**
- RoleStore.delete() cascades: automatically removes all related grants and memberships for the deleted role
- No dedicated bulk delete methods (deleteByRole, deleteByUser). Cascade iterates through related records
- Cross-reference validation on grant revoke (e.g., checking role exists): Claude's Discretion

**Test Scope**
- Mocked stores (in-memory mock KVStore), not real RocksDB integration tests
- Essential edge cases only: duplicates and not-found. No special characters, concurrent writes, etc.
- Tests in a new package under `com.dremio.exec.rbac` in sabot/kernel test sources

### Claude's Discretion
- Whether to expose "list all" operations on each store
- Cross-reference validation when revoking grants (whether to check role exists)
- Exact mock KVStore implementation pattern (follow existing test exemplars)
- Internal implementation of scan-and-filter for membership/grant queries

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| ROLE-07 | Roles and memberships persist across coordinator restarts (RocksDB KV store) | `LocalKVStoreProvider` with `inMemory=true` satisfies unit tests; production uses RocksDB automatically via the same `KVStoreCreationFunction` — no code change needed between test and production |
| PRIV-07 | Privilege grants persist across coordinator restarts (RocksDB KV store) | Same as ROLE-07: GrantStore uses `KVStore<String, Grant>` registered via `KVStoreCreationFunction`; production persistence is provided by the `LocalKVStoreProvider`/RocksDB backend transparently |

</phase_requirements>

---

## Summary

Phase 2 fills in the stub methods left in `RoleStore`, `GrantStore`, and `MembershipStore` from Phase 1. Every infrastructure piece (store registration, serialization, classpath scanning) is already wired. This phase is pure method body work: `get`, `put`, `delete`, list/scan methods, input validation, error semantics, and cascade logic.

The `KVStore<K, V>` interface (`com.dremio.datastore.api.KVStore`) provides all primitives needed. `get(key)` returns `Document<K, V>` or `null` (matching the "nullable, not Optional" convention). `put(key, value, PutOption.CREATE)` throws `ConcurrentModificationException` when the key already exists — this is the mechanism for "duplicate role creation throws". `delete(key)` silently succeeds even if the key is absent (meaning the store layer must call `get()` first to detect not-found). `find()` returns `Iterable<Document<K,V>>` over all entries, enabling scan-and-filter for membership and grant queries.

For unit testing, `LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false)` creates a fully functional in-memory KV store that discovers all `KVStoreCreationFunction` implementations via classpath scanning. Because `RoleStore.StoreCreator`, `GrantStore.StoreCreator`, and `MembershipStore.StoreCreator` are in the same `sabot/kernel` module as the tests, they are automatically discovered. The test setup pattern is: create `LocalKVStoreProvider`, call `start()`, create store instances via `new RoleStore(() -> kvStoreProvider)`, test, call `close()` in `@After`.

**Primary recommendation:** Implement CRUD methods directly on the three store classes, using `KVStore.PutOption.CREATE` for duplicate detection, `store.get()` null-check for not-found detection, and `StreamSupport.stream(store.find().spliterator(), false).filter(...)` for scan-and-filter. Test with `LocalKVStoreProvider` in-memory mode. No new Maven dependencies needed.

---

## Standard Stack

### Core

| Library / Component | Version / Location | Purpose | Why Standard |
|--------------------|--------------------|---------|--------------|
| `com.dremio.datastore.api.KVStore<K,V>` | `services/datastore/` | CRUD operations on a single store | The verified non-Legacy interface used by all new stores |
| `com.dremio.datastore.api.Document<K,V>` | `services/datastore/api/Document.java` | Return type from `get()` and `find()`; wraps key+value+tag | Part of KVStore API; `doc.getValue()` extracts the proto message |
| `com.dremio.datastore.api.KVStore.PutOption.CREATE` | Static field on `KVStore` | Enforces create-only semantics (throws `ConcurrentModificationException` on duplicate) | Standard mechanism used by `ScriptStoreImpl`, `UserSessionServiceImpl` |
| `com.google.common.base.Suppliers.memoize(...)` | Guava (already in kernel) | Lazy one-time initialization of the `KVStore` handle from the `KVStoreProvider` | Canonical pattern from `ScriptStoreImpl`, `EmbeddedPointerStore` |
| `java.util.stream.StreamSupport` | JDK | Converts `Iterable<Document>` from `find()` to a `Stream` for filter operations | Used by `EmbeddedPointerStore.findAll()` and `LocalProfileStore` |
| `com.google.common.base.Preconditions` | Guava (already in kernel) | Null/empty checks at store method boundaries | Standard in `ScriptStoreImpl` and all kernel stores |

### Supporting (Test Only)

| Library / Component | Purpose | When to Use |
|--------------------|---------|-------------|
| `com.dremio.datastore.LocalKVStoreProvider` | In-memory KVStore for unit tests; discovers all `KVStoreCreationFunction` via classpath scan | The canonical test provider — used in `TestScriptServiceImpl`, `TestEmbeddedMetadataPointerService`, `TestReindexVersionStore` |
| `com.dremio.test.DremioTest` | Provides `CLASSPATH_SCAN_RESULT` static constant needed by `LocalKVStoreProvider` | Extend this class or import its static field |
| `org.assertj.core.api.Assertions.assertThatThrownBy` | Modern exception assertion (already in kernel test scope) | Use for duplicate-creation and not-found tests |
| `org.junit.Test`, `org.junit.Before`, `org.junit.After` | JUnit 4 (standard in sabot/kernel) | All sabot/kernel tests use JUnit 4, not JUnit 5 |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `store.find()` + `StreamSupport.stream().filter()` for scan-and-filter | `FindByRange` with prefix range | `FindByRange` is faster for large stores but more complex; for v1 (small datasets, single coordinator) full scan is simpler and sufficient. CONTEXT.md locked scan-and-filter. |
| Custom `IllegalArgumentException` for "not found" | Dedicated checked exception classes | Checked exceptions force callers to handle errors explicitly; unchecked is easier but less discoverable. Research shows the codebase uses both; for a store-layer boundary, `IllegalArgumentException` (unchecked) is acceptable, but a named checked exception better communicates semantics. |
| `KVStore.PutOption.CREATE` for duplicate detection | Manual `get()` then conditional `put()` | `PutOption.CREATE` is atomic (no TOCTOU) and throws `ConcurrentModificationException`. Since no concurrency in v1, either works, but `PutOption.CREATE` is more idiomatic. |

---

## Architecture Patterns

### Recommended Project Structure

```
sabot/kernel/src/main/java/com/dremio/exec/rbac/
├── RbacConfig.java          # Already exists: store names, key helpers
├── RoleStore.java           # Phase 2: fill in get/create/delete/listAll
├── GrantStore.java          # Phase 2: fill in get/grant/revoke/listByRole/listAll
├── MembershipStore.java     # Phase 2: fill in get/add/remove/listByUser/listByRole/listAll
└── (exception classes)      # Phase 2: RbacEntityNotFoundException (or per-entity)

sabot/kernel/src/test/java/com/dremio/exec/rbac/
├── RoleStoreTest.java
├── GrantStoreTest.java
└── MembershipStoreTest.java
```

### Pattern 1: CRUD Method Implementation

**What:** Implement `get`, `create`, `update`, `delete` on a plain `KVStore<String, Role>`.

**When to use:** For all three stores in Phase 2.

**Example (RoleStore.create and RoleStore.get):**
```java
// Source: KVStore.java (com.dremio.datastore.api), Document.java, ScriptStoreImpl.java pattern

/** Returns the Role with the given roleId, or null if not found. */
public Role get(String roleId) {
  Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
  Document<String, Role> doc = store.get().get(roleId);
  return doc == null ? null : doc.getValue();
}

/**
 * Creates a new role. Throws if a role with this ID already exists.
 * @throws IllegalArgumentException if roleId or role is null/empty
 * @throws ConcurrentModificationException if a role with this roleId already exists
 */
public void create(String roleId, Role role) {
  Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
  Preconditions.checkNotNull(role, "role must not be null");
  // PutOption.CREATE throws ConcurrentModificationException if key already exists
  store.get().put(roleId, role, KVStore.PutOption.CREATE);
}

/**
 * Deletes the role and cascades to grants and memberships.
 * @throws RbacEntityNotFoundException if the role does not exist
 */
public void delete(String roleId, GrantStore grantStore, MembershipStore membershipStore) {
  Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
  if (store.get().get(roleId) == null) {
    throw new RbacEntityNotFoundException("Role not found: " + roleId);
  }
  // Cascade: delete all grants for this role
  grantStore.deleteByRole(roleId);
  // Cascade: delete all memberships for this role
  membershipStore.deleteByRole(roleId);
  store.get().delete(roleId);
}
```

### Pattern 2: Scan-and-Filter for MembershipStore and GrantStore

**What:** Use `store.find()` to iterate all entries and filter by key prefix.

**When to use:** For `MembershipStore.listByUser(userName)`, `MembershipStore.listByRole(roleId)`, and `GrantStore.listByRole(roleId)`.

**Example (MembershipStore.listByUser — returns memberships where key starts with `userName|`):**
```java
// Source: EmbeddedPointerStore.findAll() (services/embedded-catalog)
//         LocalProfileStore.getAllExecutorProfiles() pattern for key-prefix scan
import java.util.stream.StreamSupport;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Returns all memberships where the user is the given userName.
 * Key format: "{user_name}|{role_id}" — filter keys starting with "userName|".
 */
public List<Membership> listByUser(String userName) {
  Preconditions.checkArgument(!Strings.isNullOrEmpty(userName), "userName must not be null or empty");
  String prefix = userName + RbacConfig.KEY_SEP;
  return StreamSupport.stream(store.get().find().spliterator(), false)
      .filter(doc -> doc.getKey().startsWith(prefix))
      .map(Document::getValue)
      .collect(Collectors.toList());
}

/**
 * Returns all memberships where the role is the given roleId.
 * Key format: "{user_name}|{role_id}" — filter keys ending with "|roleId".
 */
public List<Membership> listByRole(String roleId) {
  Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
  String suffix = RbacConfig.KEY_SEP + roleId;
  return StreamSupport.stream(store.get().find().spliterator(), false)
      .filter(doc -> doc.getKey().endsWith(suffix))
      .map(Document::getValue)
      .collect(Collectors.toList());
}
```

**Note on GrantStore.listByRole:** Same pattern. Key format `role_id|object_type|object_path|privilege` — filter keys starting with `roleId + "|"`.

### Pattern 3: LocalKVStoreProvider in-memory Test Setup

**What:** Create a fully functional in-memory KV store for unit tests using `LocalKVStoreProvider`. The classpath scan auto-discovers all `KVStoreCreationFunction` implementations in the module.

**When to use:** All RBAC store unit tests.

**Example (full test class skeleton):**
```java
// Source: TestEmbeddedMetadataPointerService (services/embedded-catalog/src/test),
//         TestScriptServiceImpl (services/scripts/src/test),
//         TestReindexVersionStore (services/reindexer/src/test)

import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.test.DremioTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RoleStoreTest {

  private LocalKVStoreProvider kvStoreProvider;
  private RoleStore roleStore;

  @Before
  public void setUp() throws Exception {
    // inMemory=true: no disk, no RocksDB, fully in-memory
    kvStoreProvider = new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    kvStoreProvider.start();
    roleStore = new RoleStore(() -> kvStoreProvider);
  }

  @After
  public void tearDown() throws Exception {
    kvStoreProvider.close();
  }

  @Test
  public void testCreateAndGet() {
    Role role = Role.newBuilder().setRoleId("analyst").setRoleName("Analyst").build();
    roleStore.create("analyst", role);
    Role retrieved = roleStore.get("analyst");
    assertThat(retrieved).isNotNull();
    assertThat(retrieved.getRoleName()).isEqualTo("Analyst");
  }

  @Test
  public void testDuplicateRoleThrows() {
    Role role = Role.newBuilder().setRoleId("analyst").setRoleName("Analyst").build();
    roleStore.create("analyst", role);
    assertThatThrownBy(() -> roleStore.create("analyst", role))
        .isInstanceOf(ConcurrentModificationException.class);
  }

  @Test
  public void testDeleteNonExistentThrows() {
    assertThatThrownBy(() -> roleStore.delete("nonexistent", grantStore, membershipStore))
        .isInstanceOf(RbacEntityNotFoundException.class);
  }
}
```

### Pattern 4: Exception Classes for "Not Found"

**What:** A checked exception `RbacEntityNotFoundException` thrown by `delete()` and `revoke()` when the target does not exist.

**When to use:** For all "not found" cases at the store layer.

**Example (based on ScriptNotFoundException pattern):**
```java
// Source: ScriptNotFoundException.java (services/scripts/src/main/java)
package com.dremio.exec.rbac;

/** Thrown when an RBAC entity (role, grant, membership) does not exist. */
public class RbacEntityNotFoundException extends Exception {
  public RbacEntityNotFoundException(String message) {
    super(message);
  }
}
```

**Alternative:** Use `IllegalArgumentException` (unchecked) instead of a checked exception. This is simpler but provides less discoverable API. Since the context says "throws an exception" and does not specify checked vs unchecked, either works. Recommendation: use a checked exception to force callers to handle the not-found case explicitly — this matches the `ScriptNotFoundException` pattern.

### Pattern 5: Cascade Delete in RoleStore

**What:** `RoleStore.delete()` first removes all related grants and memberships before removing the role itself.

**Implementation:** Inject `GrantStore` and `MembershipStore` into `RoleStore.delete()` as method parameters (not constructor injection), keeping the stores independent. Alternatively, `RoleStore` can hold references to the other stores if passed at construction or `start()`.

**Recommended approach:** Pass `GrantStore` and `MembershipStore` as parameters to `delete()`. This keeps the three stores independent — no circular dependencies — and is cleanest for unit testing. The cascade internally calls `membershipStore.deleteByRole(roleId)` and `grantStore.deleteByRole(roleId)`, which are package-visible scan-and-delete helpers (not part of the public API per the CONTEXT.md decision to have no dedicated bulk-delete methods).

```java
// Cascade implementation sketch
public void delete(String roleId, GrantStore grantStore, MembershipStore membershipStore)
    throws RbacEntityNotFoundException {
  // 1. Check existence (throw if not found)
  if (store.get().get(roleId) == null) {
    throw new RbacEntityNotFoundException("Role not found: " + roleId);
  }
  // 2. Cascade: delete all grants for this role
  StreamSupport.stream(grantStore.store.get().find().spliterator(), false)
      .filter(doc -> doc.getKey().startsWith(roleId + RbacConfig.KEY_SEP))
      .map(Document::getKey)
      .forEach(key -> grantStore.store.get().delete(key));
  // 3. Cascade: delete all memberships for this role
  StreamSupport.stream(membershipStore.store.get().find().spliterator(), false)
      .filter(doc -> doc.getKey().endsWith(RbacConfig.KEY_SEP + roleId))
      .map(Document::getKey)
      .forEach(key -> membershipStore.store.get().delete(key));
  // 4. Delete the role itself
  store.get().delete(roleId);
}
```

**Note:** If cross-store direct field access (`grantStore.store.get()`) feels wrong, the cleaner alternative is to expose package-private `deleteByRole(String roleId)` helper methods on `GrantStore` and `MembershipStore` that do the scan-and-delete internally. This is recommended: keeps each store's `store` field private, and the helpers become the "cascade contract" used only by `RoleStore`.

### Anti-Patterns to Avoid

- **Using `IndexedStore` for scan queries:** The CONTEXT.md locks scan-and-filter (not `IndexedStore`). Do not use `buildIndexed()` or `IndexedStoreCreationFunction` for RBAC stores.
- **Returning `Optional<Role>` instead of nullable `Role`:** The context says "nullable, not Optional. Follow existing Dremio conventions." `ScriptStoreImpl` uses `Optional` but note that is a service-layer convention, not store-layer. The RBAC store layer is explicitly decided to use nullable.
- **Silent no-op on delete of non-existent entity:** The context says "not a silent no-op." Always check existence first and throw if absent.
- **Using `LegacyKVStoreCreationFunction`:** Phase 1 locked `KVStoreCreationFunction` (non-Legacy). Do not switch.
- **Not calling `kvStoreProvider.start()` in test setUp:** `LocalKVStoreProvider.start()` must be called before `getStore()`. Missing this causes `NullPointerException` or `IllegalStateException`.
- **Not calling `kvStoreProvider.close()` in test tearDown:** Leaks resources; may cause port conflicts in CI if the provider starts RPC services even in in-memory mode.
- **Writing to the store in the `StoreCreator.StoreCreator` constructor or static initializer:** The `Suppliers.memoize` pattern defers store access to first use. Write operations belong in named methods only.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Duplicate-key detection | Manual `get()` + conditional throw before `put()` | `KVStore.PutOption.CREATE` | Idiomatic, single call; throws `ConcurrentModificationException` which is already the recognized error for this case in the datastore layer |
| In-memory KV store for tests | Custom `HashMap`-backed mock | `LocalKVStoreProvider(CLASSPATH_SCAN_RESULT, null, true, false)` | Uses the exact same code path as production (only the backend differs); validates `StoreCreator` registration, serialization, and key format |
| Classpath scan registration | Manual `@Inject @Named` wiring | Classpath scanner discovers `KVStoreCreationFunction` implementations automatically | `LocalKVStoreProvider` uses `scan.getImplementations(StoreCreationFunction.class)` — no registration step needed |
| Prefix-based scan | Custom index structure | `StreamSupport.stream(store.find().spliterator(), false).filter(...)` | The codebase uses this exact pattern in `EmbeddedPointerStore.findAll()` |
| Full store list | Custom `listAll()` with separate tracking | `store.find()` with no filter | Returns all documents; same mechanism, no extra state |

**Key insight:** Every piece of infrastructure is already in place from Phase 1. Phase 2 is purely method bodies.

---

## Common Pitfalls

### Pitfall 1: `KVStore.delete()` Does Not Throw on Missing Key

**What goes wrong:** Calling `store.get().delete(key)` when the key doesn't exist silently succeeds — no exception. If the "not found" semantics are implemented by calling `delete()` directly, missing keys will be silently ignored.

**Why it happens:** The `KVStore.delete()` contract (as documented in `KVStore.java`) says it removes the document if it exists; the behavior on missing keys is unspecified (in practice, it is a no-op in RocksDB).

**How to avoid:** Always call `store.get().get(key)` first. If `null` is returned, throw `RbacEntityNotFoundException`. Then call `store.get().delete(key)`.

**Warning signs:** Test for "delete nonexistent throws" passes when it shouldn't; "delete" tests silently succeed without error on missing data.

### Pitfall 2: `ConcurrentModificationException` vs Custom Exception for Duplicates

**What goes wrong:** Callers catch `ConcurrentModificationException` (the raw exception from `PutOption.CREATE`) and get confusing error messages.

**Why it happens:** `KVStore.PutOption.CREATE` throws `ConcurrentModificationException` ("Tried to CREATE, found..."). This is the framework's chosen exception for this case.

**How to avoid:** Two options: (a) let `ConcurrentModificationException` propagate as-is from the store layer — the upper service layer catches it and wraps it with a friendly "role already exists" message; or (b) wrap it in a custom `RbacEntityAlreadyExistsException` at the store boundary. Since no higher layer exists yet in Phase 2, either is fine. Recommendation: wrap it for clarity, mirroring the `ScriptNotFoundException` pattern.

**Warning signs:** Test for "create duplicate throws ConcurrentModificationException" passes, but the error message is confusing in log output.

### Pitfall 3: Cascade Delete Misses Grant or Membership Records

**What goes wrong:** The cascade in `RoleStore.delete()` misses some grants or memberships because the key prefix/suffix filter is wrong.

**Why it happens:** Grant keys are `role_id|object_type|object_path|privilege`. If the filter uses `startsWith(roleId)` without the `|` separator, a roleId of "analyst" would also match a roleId of "analyst2". The separator is essential.

**How to avoid:** Always filter with `startsWith(roleId + RbacConfig.KEY_SEP)` (i.e., `startsWith("analyst|")`). Similarly, for membership listByRole, filter `endsWith(RbacConfig.KEY_SEP + roleId)`.

**Warning signs:** Cascade delete test passes with one role, but fails when two roles share a prefix (e.g., "dev" and "devops").

### Pitfall 4: Test Store Discovery Failure

**What goes wrong:** `kvStoreProvider.getStore(RoleStore.StoreCreator.class)` fails at runtime with a class-not-found or NullPointerException.

**Why it happens:** `LocalKVStoreProvider` discovers `KVStoreCreationFunction` implementations at classpath scan time (passed via `DremioTest.CLASSPATH_SCAN_RESULT`). If the `StoreCreator` classes are not in the scanned classpath, or if `start()` was not called first, `getStore()` returns null or throws.

**How to avoid:** Ensure `kvStoreProvider.start()` is called before any `getStore()`. Verify that `DremioTest.CLASSPATH_SCAN_RESULT` covers the sabot/kernel classes (it does, as confirmed by multiple kernel tests using this pattern). Do not rename the `StoreCreator` inner class.

**Warning signs:** `NullPointerException` at `store.get().get(...)` — the `Supplier` resolved to null because `getStore()` returned null; or `IllegalArgumentException: Store not found`.

### Pitfall 5: Scan `find()` Returns Stale Iterable

**What goes wrong:** The `Iterable<Document<K,V>>` returned by `store.find()` is consumed only once. If the code tries to iterate it twice (e.g., once to collect keys, once to check values), the second iteration returns nothing.

**Why it happens:** The underlying `Iterable` is a one-shot cursor, not a re-iterable collection.

**How to avoid:** Collect to a `List` or `Stream` immediately on first traversal. Do not hold a reference to the `Iterable` and iterate it multiple times.

**Warning signs:** Second loop over `find()` result returns 0 entries despite the first returning entries.

---

## Code Examples

Verified patterns from the actual codebase:

### KVStore.get() — nullable return, extract value from Document

```java
// Source: EmbeddedPointerStore.get() (services/embedded-catalog/.../EmbeddedPointerStore.java:46-49)
// Also: ScriptStoreImpl.get() (services/scripts/.../ScriptStoreImpl.java:64-70)
Document<String, Role> doc = store.get().get(roleId);
// doc is null if not found
return doc == null ? null : doc.getValue();
```

### KVStore.put() with PutOption.CREATE — throws on duplicate

```java
// Source: ScriptStoreImpl.create() (services/scripts/.../ScriptStoreImpl.java:92-98)
// Also: UserSessionServiceImpl (services/usersessions/...)
store.get().put(roleId, role, KVStore.PutOption.CREATE);
// Throws java.util.ConcurrentModificationException if roleId already exists
// ("Tried to CREATE, found ...")
```

### KVStore.put() without PutOption — upsert (update existing)

```java
// Source: EmbeddedPointerStore.put() (services/embedded-catalog/.../EmbeddedPointerStore.java:51-66)
store.get().put(key, value);  // overwrites if exists, creates if not
```

### KVStore.delete() — no-op if key absent (must check first)

```java
// Source: EmbeddedPointerStore.delete() (services/embedded-catalog/.../EmbeddedPointerStore.java:42-44)
// Also used in TestEmbeddedMetadataPointerService.wipeKVStore()
store.get().delete(key);  // silently no-ops on missing key
```

### KVStore.find() + StreamSupport for full scan

```java
// Source: EmbeddedPointerStore.findAll() (services/embedded-catalog/.../EmbeddedPointerStore.java:69-73)
import java.util.stream.StreamSupport;
StreamSupport.stream(store.get().find().spliterator(), false)
    .filter(doc -> doc.getKey().startsWith(prefix))
    .map(Document::getValue)
    .collect(Collectors.toList());
```

### FindByRange — prefix scan alternative (not recommended for Phase 2)

```java
// Source: LocalProfileStore.getAllExecutorProfiles() (services/jobtelemetry/server/.../LocalProfileStore.java:165-171)
// For reference only — scan-and-filter is simpler for Phase 2
import com.dremio.datastore.api.ImmutableFindByRange;
import com.dremio.datastore.api.FindByRange;

// Append U+FFFF to get range end for string prefix scan
private static final byte[] MAX_UTF8_VALUE = new byte[]{(byte)0xef, (byte)0xbf, (byte)0xbf};
String end = prefix + new String(MAX_UTF8_VALUE, StandardCharsets.UTF_8);
FindByRange<String> range = new ImmutableFindByRange.Builder<String>()
    .setStart(prefix).setIsStartInclusive(true)
    .setEnd(end).setIsEndInclusive(false)
    .build();
Iterable<Document<String, Grant>> docs = store.get().find(range);
```

### LocalKVStoreProvider test setup — full lifecycle

```java
// Source: TestScriptServiceImpl.setUp() (services/scripts/src/test)
//         TestEmbeddedMetadataPointerService.setup() (services/embedded-catalog/src/test)
//         TestReindexVersionStore.setup() (services/reindexer/src/test)
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.test.DremioTest;

private LocalKVStoreProvider kvStoreProvider;

@Before
public void setUp() throws Exception {
  // params: ScanResult scan, String baseDirectory, boolean inMemory, boolean timed
  kvStoreProvider = new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
  kvStoreProvider.start(); // MUST call start() before any getStore() call
}

@After
public void tearDown() throws Exception {
  kvStoreProvider.close(); // always close to release resources
}

// In a test: wire the store
RoleStore roleStore = new RoleStore(() -> kvStoreProvider);
// Note: RoleStore's Supplier initializes via Suppliers.memoize() on first use.
// No explicit "start()" call needed on the store itself since Phase 1 did not
// implement a start() method — the store is lazy-initialized on first method call.
```

### Exception class — "not found" pattern

```java
// Source: ScriptNotFoundException.java (services/scripts/src/main/java) — adapt for RBAC
package com.dremio.exec.rbac;

public class RbacEntityNotFoundException extends Exception {
  public RbacEntityNotFoundException(String message) {
    super(message);
  }
}
```

### Input validation — Preconditions pattern

```java
// Source: ScriptStoreImpl.create() (services/scripts/src/main/java)
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
Preconditions.checkNotNull(role, "role must not be null");
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `LegacyKVStoreCreationFunction` | `KVStoreCreationFunction` (non-Legacy) | Ongoing migration | Phase 1 locked non-Legacy; Phase 2 continues it |
| `LegacyKVStoreProviderAdapter.inMemory(...)` for tests | `LocalKVStoreProvider(CLASSPATH_SCAN_RESULT, null, true, false)` | Ongoing migration | `TestConfigurationStore` uses Legacy adapter; newer tests (`TestScriptServiceImpl`, `TestEmbeddedMetadataPointerService`) use `LocalKVStoreProvider` directly |
| `IndexedStore` for filtered queries | Plain `KVStore` + `find()` scan | N/A (both patterns coexist) | CONTEXT.md explicitly chose scan-and-filter for RBAC Phase 2 |

**Deprecated/outdated:**
- `LegacyKVStoreProviderAdapter.inMemory(CLASSPATH_SCAN_RESULT)` — used in older tests (`TestConfigurationStore`). The current pattern is `LocalKVStoreProvider(CLASSPATH_SCAN_RESULT, null, true, false)`.
- JUnit 5 (`@BeforeEach`, `org.junit.jupiter`) — used in some newer service tests. However, sabot/kernel tests consistently use JUnit 4 (`@Before`, `@After`, `org.junit.Test`). Stick with JUnit 4 for consistency within the module.

---

## Claude's Discretion Recommendations

### Whether to expose "list all" operations on each store

**Recommendation: YES, expose `listAll()` on each store.** Rationale: System tables (Phase 3 per roadmap) and REST API (later phase) will need to enumerate roles, grants, and memberships. Implementing `listAll()` now costs trivial effort (it is `StreamSupport.stream(store.find().spliterator(), false).map(Document::getValue).collect(...)`) and avoids breaking changes later. Method signature: `List<Role> listAll()`.

### Cross-reference validation when revoking grants

**Recommendation: SKIP role-exists check on grant revoke.** Rationale: The delete-nonexistent rule covers grant revoke ("Grant not found: ..."). The role may have already been deleted (cascade handles that). Checking role existence before revoking would be redundant and adds a round-trip. Let the "grant not found" check be sufficient.

### Exact mock KVStore implementation pattern

**Recommendation: Use `LocalKVStoreProvider` in-memory mode** (see Pattern 3 above). Do NOT write a custom `HashMap`-backed mock — the `LocalKVStoreProvider` in-memory mode is the canonical approach used by `TestScriptServiceImpl`, `TestEmbeddedMetadataPointerService`, and `TestReindexVersionStore`.

### Internal implementation of scan-and-filter

**Recommendation: Use `StreamSupport.stream(store.get().find().spliterator(), false).filter(...)`.** For listByUser: filter `doc.getKey().startsWith(userName + "|")`. For listByRole in MembershipStore: filter `doc.getKey().endsWith("|" + roleId)`. For GrantStore.listByRole: filter `doc.getKey().startsWith(roleId + "|")`. This matches `EmbeddedPointerStore.findAll()` exactly.

---

## Open Questions

1. **Should `RoleStore.delete()` take `GrantStore` and `MembershipStore` as parameters, or should `RoleStore` hold references?**
   - What we know: The three stores are independent. The cascade requires access to the other two stores at delete time.
   - What's unclear: Whether Phase 3+ code will inject all three stores into a service layer that orchestrates deletion (which would make parameter-passing redundant).
   - Recommendation: Pass as parameters to `delete()`. The service layer (Phase 3+) will call `roleStore.delete(id, grantStore, membershipStore)`. This keeps stores independent and testable without a service layer.

2. **Should the exception for duplicate role be `ConcurrentModificationException` (from `PutOption.CREATE`) or a named `RbacEntityAlreadyExistsException`?**
   - What we know: `KVStore.PutOption.CREATE` throws `ConcurrentModificationException`. No higher layer exists yet to wrap it.
   - What's unclear: What the eventual service layer expects.
   - Recommendation: Wrap in `RbacEntityAlreadyExistsException extends RuntimeException` at the store boundary. This isolates callers from the datastore internals. Create the exception class alongside `RbacEntityNotFoundException`.

3. **Does the `RoleStore` constructor need a `start()` method, or does lazy initialization via `Suppliers.memoize()` suffice?**
   - What we know: Phase 1 did NOT add a `start()` method to the stub classes. `ScriptStoreImpl` uses `start()` to initialize `Suppliers.memoize(...)`. Phase 1's `RoleStore` initializes the `Supplier` in the constructor directly.
   - What's unclear: Whether calling `Suppliers.memoize` in the constructor (as Phase 1 did) vs. in a `start()` method matters.
   - Recommendation: Keep the Phase 1 pattern (constructor-initialization). The `Supplier` is lazy — it doesn't call `getStore()` until the first method invocation, which happens after `kvStoreProvider.start()`. No `start()` method needed on the RBAC stores themselves.

---

## Sources

### Primary (HIGH confidence)

Verified by reading actual source files in the repository:

- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/api/KVStore.java` — full interface: `get()`, `put()`, `delete()`, `find()`, `contains()`, `bulkDelete()`, `PutOption.CREATE`, `DeleteOption.NO_META`
- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/api/Document.java` — `getKey()`, `getValue()`, `getTag()` interface
- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/api/FindByRange.java` — `getStart()`, `getEnd()`, `isStartInclusive()`, `isEndInclusive()` for range queries
- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/DatastoreException.java` — `RuntimeException` subclass for datastore failures
- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/CoreKVStoreImpl.java` — confirmed `ConcurrentModificationException` thrown by `PutOption.CREATE` on duplicate
- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/LocalKVStoreProvider.java` — `@VisibleForTesting` constructor `(ScanResult, String, boolean, boolean)`; `start()` and `close()` lifecycle; confirmed `scan.getImplementations(StoreCreationFunction.class)` for auto-discovery
- `/home/emanuele/IdeaProjects/dremio-oss/services/embedded-catalog/src/main/java/com/dremio/service/embedded/catalog/EmbeddedPointerStore.java` — canonical `find()` + `StreamSupport.stream().filter()` scan-and-filter pattern
- `/home/emanuele/IdeaProjects/dremio-oss/services/scripts/src/main/java/com/dremio/service/scripts/ScriptStoreImpl.java` — canonical `put(key, value, KVStore.PutOption.CREATE)` pattern; `Preconditions.checkNotNull`; constructor-based `Suppliers.memoize`
- `/home/emanuele/IdeaProjects/dremio-oss/services/scripts/src/main/java/com/dremio/service/scripts/ScriptNotFoundException.java` — named checked exception pattern
- `/home/emanuele/IdeaProjects/dremio-oss/services/scripts/src/test/java/com/dremio/service/scripts/TestScriptServiceImpl.java` — `LocalKVStoreProvider(CLASSPATH_SCAN_RESULT, null, true, false)` test pattern; `@Before`/`@After` lifecycle; JUnit 4; `assertThatThrownBy`
- `/home/emanuele/IdeaProjects/dremio-oss/services/embedded-catalog/src/test/java/com/dremio/service/embedded/catalog/TestEmbeddedMetadataPointerService.java` — `LocalKVStoreProvider` with `@BeforeAll`/`@AfterAll`; `kvStore.find().forEach(doc -> kvStore.delete(doc.getKey()))` wipe pattern
- `/home/emanuele/IdeaProjects/dremio-oss/services/reindexer/src/test/java/com/dremio/service/reindexer/store/TestReindexVersionStore.java` — `LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false)`; JUnit 4; `DremioTest.CLASSPATH_SCAN_RESULT`
- `/home/emanuele/IdeaProjects/dremio-oss/services/jobtelemetry/server/src/main/java/com/dremio/service/jobtelemetry/server/store/LocalProfileStore.java` — `ImmutableFindByRange` prefix scan pattern; `buildRangeEndKey` with `MAX_UTF8_VALUE` (U+FFFF)
- `/home/emanuele/IdeaProjects/dremio-oss/common/legacy/src/test/java/com/dremio/test/DremioTest.java` — `CLASSPATH_SCAN_RESULT = ClassPathScanner.fromPrescan(DEFAULT_SABOT_CONFIG)` static constant
- `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/java/com/dremio/exec/rbac/RoleStore.java` — Phase 1 stub: constructor, `Suppliers.memoize`, `StoreCreator` inner class
- `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java` — Phase 1 stub
- `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java` — Phase 1 stub
- `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacConfig.java` — `KEY_SEP`, `ROLES_STORE`, `GRANTS_STORE`, `MEMBERSHIPS_STORE`, `grantKey()`, `membershipKey()`
- `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/pom.xml` — confirmed `dremio-services-datastore` dependency (non-test scope); JUnit 4 usage in tests; assertj available

---

## Metadata

**Confidence breakdown:**
- KVStore CRUD API: HIGH — read `KVStore.java`, `Document.java`, `CoreKVStoreImpl.java` directly; patterns verified in `ScriptStoreImpl`, `EmbeddedPointerStore`
- PutOption.CREATE duplicate behavior: HIGH — read `CoreKVStoreImpl.java`; confirmed `ConcurrentModificationException` thrown
- KVStore.delete() no-op on missing key: HIGH — read `KVStore.java` Javadoc; pattern confirmed by `wipeKVStore()` in test
- Scan-and-filter with `find()`: HIGH — read `EmbeddedPointerStore.findAll()`; `LocalProfileStore.getAllExecutorProfiles()`
- LocalKVStoreProvider test pattern: HIGH — verified in 3+ test files (`TestScriptServiceImpl`, `TestEmbeddedMetadataPointerService`, `TestReindexVersionStore`); constructor parameters verified in source
- JUnit 4 in sabot/kernel: HIGH — confirmed by reading multiple test files; all use `org.junit.Test` not JUnit 5
- assertj available in kernel tests: HIGH — found `assertThatThrownBy` usage in `TestDropTable.java`, `TestBugFixes.java`
- Exception class naming: MEDIUM — pattern from `ScriptNotFoundException`; decision on checked vs unchecked is discretionary

**Research date:** 2026-02-17
**Valid until:** 2026-03-19 (30 days — stable codebase patterns)
