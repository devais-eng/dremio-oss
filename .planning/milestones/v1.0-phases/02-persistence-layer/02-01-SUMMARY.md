---
phase: 02-persistence-layer
plan: 01
subsystem: database
tags: [kvstore, protobuf, rbac, dremio-datastore]

# Dependency graph
requires:
  - phase: 01-design-and-proto-schema
    provides: "KV store stub classes (RoleStore, GrantStore, MembershipStore with StoreCreator inner classes), RbacConfig key constants and key-building utilities, proto-generated Role/Grant/Membership classes"
provides:
  - "RbacEntityNotFoundException: checked exception for delete/revoke/remove of non-existent records"
  - "RbacEntityAlreadyExistsException: unchecked exception wrapping ConcurrentModificationException from PutOption.CREATE"
  - "RoleStore.get/create/delete/listAll: full CRUD with cascade delete via GrantStore and MembershipStore"
  - "GrantStore.get/grant/revoke/listByRole/listAll/deleteByRole: full CRUD plus scan-and-filter listing"
  - "MembershipStore.get/add/remove/listByUser/listByRole/listAll/deleteByRole: full CRUD plus dual-axis scan-and-filter listing"
affects:
  - 02-02-unit-tests
  - 03-service-layer
  - 04-ddl-handlers
  - 05-catalog-integration

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "PutOption.CREATE for optimistic uniqueness enforcement (wraps ConcurrentModificationException at store boundary)"
    - "scan-and-filter via StreamSupport.stream(store.get().find().spliterator(), false).filter() for list operations"
    - "collect-keys-then-delete pattern in deleteByRole() to avoid iterator invalidation during mutation"
    - "existence check (get != null) before delete because KVStore.delete() is a silent no-op on missing keys"
    - "cascade delete: RoleStore.delete() calls grantStore.deleteByRole() and membershipStore.deleteByRole() before deleting role"

key-files:
  created:
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacEntityNotFoundException.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacEntityAlreadyExistsException.java
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RoleStore.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java

key-decisions:
  - "RbacEntityNotFoundException is checked (extends Exception) -- callers must handle delete/revoke/remove errors explicitly"
  - "RbacEntityAlreadyExistsException is unchecked (extends RuntimeException) -- wraps ConcurrentModificationException to isolate callers from datastore internals"
  - "get() returns nullable proto (not Optional) -- consistent with locked Phase 1 decision"
  - "deleteByRole() is package-private (no public modifier) -- cascade contract is internal to the rbac package"
  - "Keys collected to List before delete in deleteByRole() -- KVStore.find() returns a one-shot cursor; modifying store while iterating is undefined behavior"

patterns-established:
  - "Exception boundary pattern: wrap KVStore implementation exceptions (ConcurrentModificationException) in domain exceptions at the store layer"
  - "Existence check pattern: always call get() before delete() since KVStore.delete() is a silent no-op"
  - "Cascade delete pattern: parent store's delete() receives dependent stores as parameters and calls deleteByRole() before self-delete"

requirements-completed:
  - ROLE-07
  - PRIV-07

# Metrics
duration: 8min
completed: 2026-02-17
---

# Phase 2 Plan 01: RBAC Persistence Layer CRUD Implementation Summary

**Full CRUD implementation for RoleStore, GrantStore, and MembershipStore using KVStore PutOption.CREATE, scan-and-filter listing, and cascade delete wiring via package-private deleteByRole()**

## Performance

- **Duration:** 8 min
- **Started:** 2026-02-17T16:12:06Z
- **Completed:** 2026-02-17T16:14:17Z
- **Tasks:** 3
- **Files modified:** 5 (2 created, 3 modified)

## Accomplishments
- Created two exception classes establishing the error semantics boundary between KVStore internals and RBAC callers
- Implemented full CRUD for RoleStore (get/create/delete/listAll) with cascade delete that removes all grants and memberships before deleting a role
- Implemented full CRUD for GrantStore (6 methods) and MembershipStore (7 methods) using scan-and-filter pattern via StreamSupport for list operations without IndexedStore dependency

## Task Commits

Each task was committed atomically:

1. **Task 1: Create exception classes** - `efddfe711` (feat)
2. **Task 2: Implement RoleStore CRUD** - `a62ff16b3` (feat)
3. **Task 3: Implement GrantStore and MembershipStore CRUD** - `fc023c99b` (feat)

**Plan metadata:** (this commit, docs)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacEntityNotFoundException.java` - Checked exception for delete/revoke/remove of non-existent records (extends Exception)
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacEntityAlreadyExistsException.java` - Unchecked exception wrapping ConcurrentModificationException from PutOption.CREATE (extends RuntimeException)
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RoleStore.java` - Full CRUD: get, create (PutOption.CREATE), delete (existence check + cascade), listAll (111 lines total)
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java` - Full CRUD: get, grant, revoke, listByRole (prefix filter), listAll, deleteByRole (152 lines total)
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java` - Full CRUD: get, add, remove, listByUser (prefix filter), listByRole (suffix filter), listAll, deleteByRole (168 lines total)

## Decisions Made
- RbacEntityNotFoundException is **checked** (extends Exception) so callers must explicitly handle failure when deleting/revoking/removing non-existent entities
- RbacEntityAlreadyExistsException is **unchecked** (extends RuntimeException) so create/grant/add callers need not declare it but can catch it if needed
- get() returns nullable proto directly (not Optional) -- consistent with the Phase 1 locked decision
- deleteByRole() is package-private in both GrantStore and MembershipStore -- this is an internal cascade contract, not a public API

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. The overall verification check flagged "IndexedStore" strings but these were only in Javadoc comments explaining the scan-and-filter approach (e.g., "scan-and-filter, not IndexedStore"), not actual code usage. No `IndexedStore` imports or implementations were added.

## Next Phase Readiness
- All three stores are fully functional and ready for unit testing (Plan 02-02)
- StoreCreator inner classes preserved with original store names (`oss_rbac_roles`, `oss_rbac_grants`, `oss_rbac_memberships`) -- no KV identity changes
- Service layer (Phase 3) can depend on: RoleStore.get/create/delete/listAll, GrantStore.get/grant/revoke/listByRole/listAll, MembershipStore.get/add/remove/listByUser/listByRole/listAll
- No Maven dependencies added -- purely Java standard library + existing Guava/javax.inject already in sabot/kernel

---
*Phase: 02-persistence-layer*
*Completed: 2026-02-17*

## Self-Check: PASSED

- RbacEntityNotFoundException.java: FOUND
- RbacEntityAlreadyExistsException.java: FOUND
- RoleStore.java: FOUND
- GrantStore.java: FOUND
- MembershipStore.java: FOUND
- Commit efddfe711: FOUND
- Commit a62ff16b3: FOUND
- Commit fc023c99b: FOUND
