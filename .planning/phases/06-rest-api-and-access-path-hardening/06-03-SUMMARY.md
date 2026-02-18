---
phase: 06-rest-api-and-access-path-hardening
plan: "03"
subsystem: test
tags: [test, rbac, rest, catalog, visibility-filtering, mockito, assertj]
dependency_graph:
  requires:
    - 06-01 (RbacResource, GrantStore.listByObject, RbacService wrappers)
    - 06-02 (CatalogServiceHelper visibility filtering)
  provides:
    - Unit tests for all 9 REST endpoints in RbacResource (REST-01 through REST-09)
    - Unit tests for GrantStore.listByObject (3 tests)
    - Unit tests for CatalogServiceHelper visibility filtering (6 tests, META-03)
  affects: []
tech_stack:
  added: []
  patterns:
    - "JUnit 4 @Before/@Test pattern for GrantStoreTest and RbacResourceTest (consistent with sabot/kernel test conventions)"
    - "JUnit 5 @BeforeEach/@Test pattern for TestCatalogServiceHelper (consistent with dac/backend test conventions)"
    - "Mockito mock() for all dependency mocking in RbacResourceTest and TestCatalogServiceHelper RBAC tests"
    - "LocalKVStoreProvider for real KV store behavior in GrantStoreTest (consistent with existing pattern)"
    - "assertThatThrownBy for exception testing, assertThat for value assertions (AssertJ)"
    - "getChildrenForPath() public API tested in TestCatalogServiceHelper (getNamespaceChildrenForPath is protected)"
key_files:
  created:
    - dac/backend/src/test/java/com/dremio/dac/api/RbacResourceTest.java
  modified:
    - sabot/kernel/src/test/java/com/dremio/exec/rbac/GrantStoreTest.java
    - dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java
decisions:
  - "[06-03]: RbacResourceTest calls resource methods directly (not via HTTP) -- pure unit test, avoids JAX-RS container setup"
  - "[06-03]: TestCatalogServiceHelper RBAC tests use getChildrenForPath() (public) instead of getNamespaceChildrenForPath() (protected) -- cross-package access restriction"
  - "[06-03]: securityContext promoted from local variable to field in TestCatalogServiceHelper setup -- needed so RBAC tests can verify it resolves 'user' for isAdminMember() calls"
  - "[06-03]: setupRbacMockNamespace() helper mocks getEntities() + list() -- getChildrenForPath() calls getRootContainer() via getEntities() before calling list()"
metrics:
  duration: "6 min"
  completed_date: "2026-02-18"
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 2
---

# Phase 6 Plan 03: REST and Catalog Visibility Tests Summary

**One-liner:** Unit tests for all 9 RbacResource REST endpoints, GrantStore.listByObject, and CatalogServiceHelper RBAC visibility filtering using Mockito mocks and LocalKVStoreProvider.

## What Was Built

### Task 1: GrantStore.listByObject tests and RbacResourceTest (commit 8a93e3589)

**GrantStoreTest.java -- 3 new tests for `listByObject`:**

1. `testListByObject_returnsMatchingGrants` -- Creates 2 grants for VDS "space.view1" (roles "analyst" and "dev") and 1 for "space.view2". Calls `listByObject("VDS", "space.view1")`. Asserts 2 grants returned with correct roleIds.

2. `testListByObject_returnsEmptyForNoMatches` -- Adds grant for a different object. Calls `listByObject("VDS", "nonexistent.view")`. Asserts empty list returned.

3. `testListByObject_filtersCorrectlyByObjectType` -- Creates grants for same path but different object types (VDS and FUNCTION). Verifies VDS query returns only VDS grant, FUNCTION query returns only FUNCTION grant.

**RbacResourceTest.java -- 20 tests covering all 9 REST endpoints:**

| Category | Tests |
|----------|-------|
| Admin enforcement | `testListRoles_nonAdminReturns403`, `testCreateRole_nonAdminReturns403` |
| RBAC-disabled | `testListRoles_rbacDisabledReturnsError` |
| REST-01 (list roles) | `testListRoles_returnsAllRoles` |
| REST-02 (create role) | `testCreateRole_createsAndReturns` |
| REST-03 (delete role) | `testDeleteRole_deletesSuccessfully`, `testDeleteRole_notFoundReturns404` |
| REST-04 (list members) | `testListMembers_returnsMembers` |
| REST-05 (add member) | `testAddMember_addsSuccessfully` |
| REST-06 (remove member) | `testRemoveMember_removesSuccessfully` |
| REST-07 (list grants) | `testListGrants_returnsGrants`, `testListGrants_missingObjectTypeReturns400`, `testListGrants_missingObjectPathReturns400` |
| REST-08 (grant privilege) | `testGrantPrivilege_grantsSuccessfully` |
| REST-09 (revoke privilege) | `testRevokePrivilege_revokesSuccessfully`, `testRevokePrivilege_missingRoleIdReturns400`, `testRevokePrivilege_notFoundReturns404` |
| Input validation | `testCreateRole_nullBodyReturns400`, `testAddMember_nullBodyReturns400`, `testCreateRole_duplicateReturns409` |

Tests use Mockito mocks for `RbacService`, `SecurityContext`, `DremioConfig`. Resource methods called directly (not via HTTP). Admin user "admin_user" configured via mock by default.

### Task 2: Catalog visibility filtering tests in TestCatalogServiceHelper (commit cf8a61168)

Added RBAC infrastructure to the existing test class:
- New fields: `securityContext` (promoted from local), `rbacService` (mocked), `dremioConfig` (mocked), `rbacEnabledHelper` (new CatalogServiceHelper with mocked RBAC)
- New imports: `DremioConfig`, `RbacService`
- `setupRbacMockNamespace()` helper: mocks `getEntities()` (for `getRootContainer()`) and `list()` (for `getNamespaceChildrenForPath()`)

**6 new tests for visibility filtering:**

1. `testGetNamespaceChildren_rbacDisabled_showsAllItems` -- Uses `catalogServiceHelperWithMockNs` (null rbacService). Verifies VDS appears without filtering.

2. `testGetNamespaceChildren_adminUser_showsAllItems` -- Mock `isAdminMember -> true`. VDS + folder both visible.

3. `testGetNamespaceChildren_nonAdminWithGrant_showsVds` -- Mock `isAdminMember -> false`, `hasPrivilege("user", "SELECT", "VDS", "myspace.my_view") -> true`. VDS visible.

4. `testGetNamespaceChildren_nonAdminWithoutGrant_hidesVds` -- Mock `isAdminMember -> false`, `hasPrivilege -> false`. VDS hidden (empty result).

5. `testGetNamespaceChildren_nonAdmin_foldersAlwaysVisible` -- VDS hidden, folder visible. Asserts 1 result with FOLDER container type.

6. `testGetNamespaceChildren_nonAdmin_pdsAlwaysVisible` -- Physical dataset visible to non-admin without privilege check.

**Note:** Tests use `getChildrenForPath()` (public method) instead of `getNamespaceChildrenForPath()` (protected) because TestCatalogServiceHelper is in `com.dremio.dac.service`, while CatalogServiceHelper is in `com.dremio.dac.service.catalog` -- different packages, cross-package protected access not permitted.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Used getChildrenForPath() instead of getNamespaceChildrenForPath()**
- **Found during:** Task 2
- **Issue:** `getNamespaceChildrenForPath()` is `protected` in `CatalogServiceHelper`. TestCatalogServiceHelper is in a different package (`com.dremio.dac.service` vs `com.dremio.dac.service.catalog`). Direct invocation from test would be a compile error.
- **Fix:** Used `getChildrenForPath(NamespaceKey, CatalogPageToken, Integer)` (public). This method internally calls `getNamespaceChildrenForPath()` for non-source paths, so the filtering logic under test is exercised correctly. Also needed to mock `getEntities()` to satisfy `getRootContainer()` call.
- **Files modified:** TestCatalogServiceHelper.java
- **Commit:** cf8a61168

## Requirements Coverage

| ID | Description | Test Coverage |
|----|-------------|---------------|
| REST-01 | List roles endpoint | `testListRoles_returnsAllRoles` |
| REST-02 | Create role endpoint | `testCreateRole_createsAndReturns`, `testCreateRole_duplicateReturns409`, `testCreateRole_nullBodyReturns400` |
| REST-03 | Delete role endpoint | `testDeleteRole_deletesSuccessfully`, `testDeleteRole_notFoundReturns404` |
| REST-04 | List members endpoint | `testListMembers_returnsMembers` |
| REST-05 | Add member endpoint | `testAddMember_addsSuccessfully`, `testAddMember_nullBodyReturns400` |
| REST-06 | Remove member endpoint | `testRemoveMember_removesSuccessfully` |
| REST-07 | List grants endpoint | `testListGrants_returnsGrants`, `testListGrants_missingObjectTypeReturns400`, `testListGrants_missingObjectPathReturns400` |
| REST-08 | Grant privilege endpoint | `testGrantPrivilege_grantsSuccessfully` |
| REST-09 | Revoke privilege endpoint | `testRevokePrivilege_revokesSuccessfully`, `testRevokePrivilege_missingRoleIdReturns400`, `testRevokePrivilege_notFoundReturns404` |
| META-03 | Catalog visibility filtering | 6 tests in TestCatalogServiceHelper |

Admin enforcement: `testListRoles_nonAdminReturns403`, `testCreateRole_nonAdminReturns403`
RBAC-disabled: `testListRoles_rbacDisabledReturnsError`
GrantStore.listByObject: 3 tests in GrantStoreTest

## Self-Check: PASSED

All 3 test files confirmed present. Both commits (8a93e3589, cf8a61168) confirmed in git log. Key content verified:
- RbacResourceTest has 20 @Test methods (required: 16+)
- GrantStoreTest has 3 testListByObject methods
- TestCatalogServiceHelper has 73 @Test methods (6 new RBAC tests added)
