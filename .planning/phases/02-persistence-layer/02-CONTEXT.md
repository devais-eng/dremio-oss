# Phase 2: Persistence Layer - Context

**Gathered:** 2026-02-17
**Status:** Ready for planning

<domain>
## Phase Boundary

Full CRUD operations on the three RBAC KV stores (roles, grants, memberships), building on the stubs created in Phase 1. Stores must persist across coordinator restarts. Unit tests cover CRUD and essential edge cases. The store stubs (RoleStore, GrantStore, MembershipStore with StoreCreator inner classes) and RbacConfig already exist in `com.dremio.exec.rbac`.

</domain>

<decisions>
## Implementation Decisions

### Store Query Methods
- MembershipStore uses scan-and-filter (not IndexedStore) to answer "roles for user X" and "users in role Y"
- GrantStore supports lookup by role only (not by object path). Object-level grant queries can scan all grants if needed later
- Return types are nullable (not Optional). Follow existing Dremio conventions
- Whether stores expose a "list all" operation: Claude's discretion based on downstream needs (sys tables, REST API)

### Error Semantics
- Duplicate role creation throws an exception ("role already exists"). Not idempotent
- Deleting or revoking something that doesn't exist throws an exception ("not found"). Not a silent no-op
- Store layer validates inputs (null/empty checks on role names, paths, etc.) — defensive at the persistence boundary
- No concurrency concerns for v1 — single coordinator assumption. No locking or check-and-set needed

### Cascade on Delete
- RoleStore.delete() cascades: automatically removes all related grants and memberships for the deleted role
- No dedicated bulk delete methods (deleteByRole, deleteByUser). Cascade iterates through related records
- Cross-reference validation on grant revoke (e.g., checking role exists): Claude's discretion

### Test Scope
- Mocked stores (in-memory mock KVStore), not real RocksDB integration tests
- Essential edge cases only: duplicates and not-found. No special characters, concurrent writes, etc.
- Tests in a new package under `com.dremio.exec.rbac` in sabot/kernel test sources

### Claude's Discretion
- Whether to expose "list all" operations on each store
- Cross-reference validation when revoking grants (whether to check role exists)
- Exact mock KVStore implementation pattern (follow existing test exemplars)
- Internal implementation of scan-and-filter for membership/grant queries

</decisions>

<specifics>
## Specific Ideas

- Phase 1 already delivered: RbacConfig (store names, key helpers), RoleStore/GrantStore/MembershipStore stubs with locked StoreCreator inner classes, rbac.proto with Role/Grant/Membership messages
- Store names are `oss_rbac_roles`, `oss_rbac_grants`, `oss_rbac_memberships` (locked in Phase 1)
- Key formats: role_id for roles, role_id|object_type|object_path|privilege for grants, user_name|role_id for memberships (locked in Phase 1)
- KVStoreCreationFunction (non-Legacy) API with Format.ofProtobuf + Format.ofString (locked in Phase 1)

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-persistence-layer*
*Context gathered: 2026-02-17*
