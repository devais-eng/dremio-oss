---
phase: 02-persistence-layer
verified: 2026-02-17T17:00:00Z
status: passed
score: 12/12 must-haves verified
re_verification: false
---

# Phase 2: Persistence Layer Verification Report

**Phase Goal:** Roles, grants, and memberships can be created, read, updated, and deleted via KV stores, and all records survive coordinator restarts
**Verified:** 2026-02-17T17:00:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (from 02-01-PLAN.md must_haves)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | RoleStore.create() stores a role that can be retrieved by get() | VERIFIED | RoleStore.java L71-79 (create with PutOption.CREATE), L59-63 (get returns doc.getValue()); RoleStoreTest.testCreateAndGet asserts round-trip |
| 2 | RoleStore.create() throws RbacEntityAlreadyExistsException on duplicate role_id | VERIFIED | RoleStore.java L74-78 catches ConcurrentModificationException and rethrows as RbacEntityAlreadyExistsException; RoleStoreTest.testDuplicateRoleThrows confirms |
| 3 | RoleStore.delete() throws RbacEntityNotFoundException for unknown role_id | VERIFIED | RoleStore.java L90-92 checks get()==null before delete; RoleStoreTest.testDeleteNonExistentThrows confirms |
| 4 | RoleStore.delete() cascades: removes all grants and memberships for the deleted role | VERIFIED | RoleStore.java L94-95 calls grantStore.deleteByRole(roleId) then membershipStore.deleteByRole(roleId); RoleStoreTest.testCascadeDelete asserts grantStore.get(grantKey)==null and membershipStore.get(membershipKey)==null after role deletion |
| 5 | GrantStore.grant() stores a grant retrievable by the composite key | VERIFIED | GrantStore.java L76-84 (grant with PutOption.CREATE), L64-68 (get returns doc.getValue()); GrantStoreTest.testGrantAndGet confirms round-trip with all 4 proto fields |
| 6 | GrantStore.listByRole() returns all grants for a given role_id via scan-and-filter | VERIFIED | GrantStore.java L106-113 uses StreamSupport.stream(store.get().find().spliterator(), false).filter(doc -> doc.getKey().startsWith(prefix)); GrantStoreTest.testListByRole asserts 2 analyst + 1 admin; testListByRole_prefixSafety confirms "dev" does not match "devops" |
| 7 | GrantStore.revoke() throws RbacEntityNotFoundException for unknown grant key | VERIFIED | GrantStore.java L93-97 checks get()==null before delete; GrantStoreTest.testRevokeNonExistentThrows confirms |
| 8 | MembershipStore.add() stores a membership retrievable by the composite key | VERIFIED | MembershipStore.java L76-84 (add with PutOption.CREATE), L64-68 (get returns doc.getValue()); MembershipStoreTest.testAddAndGet confirms userName and roleId fields |
| 9 | MembershipStore.listByUser() returns all memberships for a given user_name via scan-and-filter | VERIFIED | MembershipStore.java L106-113 uses startsWith(userName + KEY_SEP); MembershipStoreTest.testListByUser asserts alice=2, bob=1 |
| 10 | MembershipStore.listByRole() returns all memberships for a given role_id via scan-and-filter | VERIFIED | MembershipStore.java L121-128 uses endsWith(KEY_SEP + roleId); MembershipStoreTest.testListByRole asserts analyst=2, admin=1 |
| 11 | MembershipStore.remove() throws RbacEntityNotFoundException for unknown membership key | VERIFIED | MembershipStore.java L92-97 checks get()==null before delete; MembershipStoreTest.testRemoveNonExistentThrows confirms |
| 12 | All three stores validate null/empty inputs via Preconditions.checkArgument | VERIFIED | RoleStore.java L60, L72; GrantStore.java L65, L77; MembershipStore.java L65, L77; RoleStoreTest.testInputValidation_nullRoleId asserts IllegalArgumentException |

**Score:** 12/12 truths verified

### Required Artifacts

| Artifact | Min Lines | Actual | Status | Details |
|----------|-----------|--------|--------|---------|
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacEntityNotFoundException.java` | — | 23 lines | VERIFIED | `extends Exception`, single-arg String constructor, Apache 2.0 header |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacEntityAlreadyExistsException.java` | — | 23 lines | VERIFIED | `extends RuntimeException`, two-arg (message, cause) constructor, Apache 2.0 header |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RoleStore.java` | 80 | 123 lines | VERIFIED | 4 public methods (get, create, delete, listAll) + StoreCreator inner class intact |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java` | 90 | 154 lines | VERIFIED | 6 methods (get, grant, revoke, listByRole, listAll + package-private deleteByRole) + StoreCreator intact |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java` | 100 | 169 lines | VERIFIED | 7 methods (get, add, remove, listByUser, listByRole, listAll + package-private deleteByRole) + StoreCreator intact |
| `sabot/kernel/src/test/java/com/dremio/exec/rbac/RoleStoreTest.java` | 80 | 164 lines | VERIFIED | 8 JUnit 4 tests, LocalKVStoreProvider, cascade delete test present |
| `sabot/kernel/src/test/java/com/dremio/exec/rbac/GrantStoreTest.java` | 80 | 170 lines | VERIFIED | 8 JUnit 4 tests, LocalKVStoreProvider, prefix-safety test present |
| `sabot/kernel/src/test/java/com/dremio/exec/rbac/MembershipStoreTest.java` | 80 | 152 lines | VERIFIED | 8 JUnit 4 tests, LocalKVStoreProvider, dual-axis listing tests present |

All 8 artifacts exceed minimum line counts and contain substantive implementation.

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `RoleStore.delete()` | `GrantStore.deleteByRole()` and `MembershipStore.deleteByRole()` | method parameters `(String roleId, GrantStore grantStore, MembershipStore membershipStore)` | WIRED | RoleStore.java L87: signature matches pattern exactly; L94: `grantStore.deleteByRole(roleId)`; L95: `membershipStore.deleteByRole(roleId)` |
| `GrantStore.listByRole()` | `KVStore.find()` | `StreamSupport.stream(store.get().find().spliterator(), false).filter()` | WIRED | GrantStore.java L109-112: exact pattern present; filters on `doc.getKey().startsWith(prefix)` where `prefix = roleId + RbacConfig.KEY_SEP` |
| `MembershipStore.listByUser()` | `KVStore.find()` | `StreamSupport.stream(store.get().find().spliterator(), false).filter()` | WIRED | MembershipStore.java L109-112: startsWith filter on `userName + KEY_SEP` |
| `MembershipStore.listByRole()` | `KVStore.find()` | `StreamSupport.stream(store.get().find().spliterator(), false).filter()` | WIRED | MembershipStore.java L124-127: endsWith filter on `KEY_SEP + roleId` |
| `RoleStoreTest` | `LocalKVStoreProvider` | `new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false)` | WIRED | RoleStoreTest.java L42-43: exact constructor pattern matches plan spec |
| `RoleStoreTest` | `RoleStore` | `new RoleStore(() -> kvStoreProvider)` | WIRED | RoleStoreTest.java L44: lambda provider pattern; GrantStore and MembershipStore wired the same way |
| Cascade delete test | `GrantStore` and `MembershipStore` | `roleStore.delete(id, grantStore, membershipStore)` | WIRED | RoleStoreTest.java L136: `roleStore.delete("analyst", grantStore, membershipStore)` followed by null assertions on both dependent stores |

All 7 key links verified as fully wired.

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| ROLE-07 | 02-01-PLAN.md, 02-02-PLAN.md | Roles and memberships persist across coordinator restarts (RocksDB KV store) | SATISFIED | Three KVStoreCreationFunction-backed stores (RoleStore, GrantStore, MembershipStore) use Dremio's standard KVStore API (Format.ofString() + Format.ofProtobuf) which persists to RocksDB in production. Store names `oss_rbac_roles`, `oss_rbac_memberships` are registered via StoreCreator inner classes. Unit tests use LocalKVStoreProvider (in-memory mode) which exercises the same serialization code path as production RocksDB. |
| PRIV-07 | 02-01-PLAN.md, 02-02-PLAN.md | Privilege grants persist across coordinator restarts (RocksDB KV store) | SATISFIED | GrantStore uses the same KVStoreCreationFunction pattern with store name `oss_rbac_grants` and `Format.ofProtobuf(Grant.class)`. Grant records survive any restart because they are backed by the coordinator's KVStoreProvider (RocksDB). |

Both requirements are mapped to Phase 2 in REQUIREMENTS.md (lines 124, 131) and are satisfied by the implementation. No orphaned requirements found — the only requirements mapped to Phase 2 are ROLE-07 and PRIV-07.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| GrantStore.java | 102 | "not IndexedStore" in Javadoc | Info | Javadoc comment only, not real code usage. No actual IndexedStore import or use. |
| MembershipStore.java | 102, 117 | "not IndexedStore" in Javadoc | Info | Same as above. Explains design rationale in comments. |

No blockers or warnings. The "IndexedStore" mentions are documentation-only, confirming the design decision was deliberately made.

No TODO, FIXME, HACK, placeholder, or empty-implementation anti-patterns found in any of the 8 files.

### Human Verification Required

#### 1. RocksDB Persistence Under Real Coordinator Restart

**Test:** Start a Dremio coordinator with RBAC stores registered, create a role via RoleStore.create(), stop the coordinator, restart it, then call RoleStore.get() for the same roleId.
**Expected:** The role is returned from the restarted coordinator (persisted in RocksDB, not just in-memory).
**Why human:** LocalKVStoreProvider uses in-memory mode. Actual RocksDB persistence requires a full coordinator lifecycle test. This cannot be verified by code inspection alone — the KVStoreCreationFunction wiring is correct, but end-to-end persistence needs a running Dremio instance.

#### 2. StoreCreator Registration Validation

**Test:** Confirm the three StoreCreator classes (RoleStore.StoreCreator, GrantStore.StoreCreator, MembershipStore.StoreCreator) are discovered by classpath scanning at coordinator startup.
**Expected:** The stores are accessible via `kvStoreProvider.getStore(StoreCreator.class)` without a registration error at startup.
**Why human:** The `@Inject` wiring and Guice module registration of KVStoreProvider happen at runtime. While the StoreCreator pattern is correct (matches the codebase standard), the actual Guice module binding cannot be verified by static file inspection.

### Gaps Summary

No gaps found. All 12 observable truths are verified, all 8 artifacts exist with substantive implementation exceeding minimum line counts, all 7 key links are wired correctly, both requirements (ROLE-07, PRIV-07) are satisfied, and no blocker anti-patterns exist.

The two items flagged for human verification are operational confidence checks (RocksDB persistence end-to-end, startup classpath scan), not implementation gaps. The code that enables both behaviors is demonstrably correct.

---

## Commit Verification

All commits documented in SUMMARY files exist in the repository:

| Commit | Message | Status |
|--------|---------|--------|
| `efddfe711` | feat(02-01): add RBAC exception classes | VERIFIED |
| `a62ff16b3` | feat(02-01): implement RoleStore CRUD methods | VERIFIED |
| `fc023c99b` | feat(02-01): implement GrantStore and MembershipStore CRUD methods | VERIFIED |
| `45f592d61` | test(02-02): add JUnit 4 unit tests for RoleStore, GrantStore, MembershipStore | VERIFIED |

---

_Verified: 2026-02-17T17:00:00Z_
_Verifier: Claude (gsd-verifier)_
