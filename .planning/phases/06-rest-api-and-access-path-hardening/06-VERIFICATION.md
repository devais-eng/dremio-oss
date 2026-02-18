---
phase: 06-rest-api-and-access-path-hardening
verified: 2026-02-18T00:00:00Z
status: passed
score: 10/10 must-haves verified
re_verification: false
---

# Phase 6: REST API and Access Path Hardening — Verification Report

**Phase Goal:** Roles, memberships, and grants are manageable via REST endpoints suitable for UI integration, and catalog browsing is filtered by the user's effective grants
**Verified:** 2026-02-18
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Success Criteria (from ROADMAP.md)

| #  | Criterion                                                                                    | Status     | Evidence                                                                                     |
|----|----------------------------------------------------------------------------------------------|------------|----------------------------------------------------------------------------------------------|
| 1  | `GET /api/v3/rbac/roles` returns a list of all defined roles                                 | VERIFIED   | `RbacResource.listRoles()` at `@GET @Path("/roles")` calls `rbacService.getRoleInfo()`       |
| 2  | `POST /api/v3/rbac/roles` creates a new role and returns its details                         | VERIFIED   | `RbacResource.createRole()` at `@POST @Path("/roles")` calls `rbacService.createRole()`      |
| 3  | `DELETE /api/v3/rbac/roles/{name}` deletes a role                                            | VERIFIED   | `RbacResource.deleteRole()` at `@DELETE @Path("/roles/{name}")` calls `rbacService.deleteRole()` |
| 4  | `GET /api/v3/rbac/roles/{name}/members` returns members; POST and DELETE add/remove users    | VERIFIED   | `listMembers`, `addMember`, `removeMember` at `/roles/{name}/members` sub-paths wired to `rbacService.listMembersByRole`, `addMembership`, `removeMembership` |
| 5  | `GET /api/v3/rbac/grants?object=...` returns grants; POST and DELETE grant/revoke privileges  | VERIFIED   | `listGrants`, `grantPrivilege`, `revokePrivilege` at `/grants` wired to `rbacService.listGrantsByObject`, `grantPrivilege`, `revokePrivilege` |
| 6  | REST endpoints require ADMIN role — non-admin users receive 403 Forbidden                    | VERIFIED   | `requireAdmin()` called at the start of every endpoint; checks `rbacService.isAdminMember(userName)`; throws `UserException.permissionError()` mapped to 403 |
| 7  | Users browsing the catalog via REST API only see views and UDFs they have grants on          | VERIFIED   | `CatalogServiceHelper.filterByVisibility()` applied post-pagination in `getNamespaceChildrenForPath()`; `isFunctionVisibleToUser()` in `getTopLevelCatalogItems()` |

**Score:** 7/7 success criteria verified (10/10 must-have truths verified across all three plans)

---

### Observable Truths (from Plan must_haves)

#### Plan 06-01 Truths

| # | Truth                                                                                   | Status     | Evidence                                                                       |
|---|-----------------------------------------------------------------------------------------|------------|--------------------------------------------------------------------------------|
| 1 | GET /api/v3/rbac/roles returns a JSON list of all defined roles                         | VERIFIED   | `listRoles()` returns `ResponseList<RbacRole>`, iterates `getRoleInfo()`       |
| 2 | POST /api/v3/rbac/roles creates a new role and returns 200                              | VERIFIED   | `createRole()` calls `rbacService.createRole()`, returns `RbacRole` DTO        |
| 3 | DELETE /api/v3/rbac/roles/{name} deletes a role                                         | VERIFIED   | `deleteRole()` calls `rbacService.deleteRole()`, returns 204 No Content        |
| 4 | GET /api/v3/rbac/roles/{name}/members returns members of a role                         | VERIFIED   | `listMembers()` calls `rbacService.listMembersByRole()`, returns `ResponseList<RbacMembership>` |
| 5 | POST /api/v3/rbac/roles/{name}/members adds a user to a role                            | VERIFIED   | `addMember()` calls `rbacService.addMembership(userName, name, getUserName())` |
| 6 | DELETE /api/v3/rbac/roles/{name}/members/{userName} removes a user from a role          | VERIFIED   | `removeMember()` calls `rbacService.removeMembership(userName, name)`          |
| 7 | GET /api/v3/rbac/grants?objectType=...&objectPath=... returns grants for an object      | VERIFIED   | `listGrants()` validates both params, calls `rbacService.listGrantsByObject()` |
| 8 | POST /api/v3/rbac/grants grants a privilege                                             | VERIFIED   | `grantPrivilege()` calls `rbacService.grantPrivilege()` with all 5 args        |
| 9 | DELETE /api/v3/rbac/grants?roleId=...&objectType=...&objectPath=...&privilege=... revokes | VERIFIED | `revokePrivilege()` validates all 4 query params, calls `rbacService.revokePrivilege()` |
| 10 | Non-admin users receive 403 Forbidden on all RBAC endpoints                            | VERIFIED   | `requireAdmin()` throws `UserException.permissionError()` when `isAdminMember()` returns false; tested in `testListRoles_nonAdminReturns403` and `testCreateRole_nonAdminReturns403` |
| 11 | RBAC-disabled state returns error on all RBAC endpoints                                 | VERIFIED   | `requireRbacEnabled()` checks `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)`, throws `UserException.unsupportedError()`; tested in `testListRoles_rbacDisabledReturnsError` |

#### Plan 06-02 Truths

| # | Truth                                                                                              | Status   | Evidence                                                                               |
|---|----------------------------------------------------------------------------------------------------|----------|----------------------------------------------------------------------------------------|
| 1 | Non-admin users browsing catalog via REST only see VDS they have SELECT grant on                   | VERIFIED | `isVisibleToUser()`: VDS path gated by `rbacService.hasPrivilege(userName, "SELECT", "VDS", path)` |
| 2 | Non-admin users browsing catalog via REST only see functions they have EXECUTE grant on             | VERIFIED | `isVisibleToUser()` for FUNCTION type; `isFunctionVisibleToUser()` for top-level functions |
| 3 | Admin users see all items regardless of grants                                                     | VERIFIED | `filterByVisibility()` short-circuits with `return children` when `isAdminMember()` is true |
| 4 | With RBAC disabled, all items are visible (existing behavior preserved)                            | VERIFIED | `filterByVisibility()` short-circuits when `rbacService == null` or `!RBAC_ENABLED`    |
| 5 | Folders, spaces, sources, homes, and physical datasets remain visible to all users                 | VERIFIED | `isVisibleToUser()` returns `true` for all types except `VIRTUAL_DATASET` and `FUNCTION` |

#### Plan 06-03 Truths

| # | Truth                                                                                              | Status   | Evidence                                                                                   |
|---|----------------------------------------------------------------------------------------------------|----------|--------------------------------------------------------------------------------------------|
| 1 | Unit tests verify all 9 REST endpoints return correct responses                                    | VERIFIED | `RbacResourceTest` has 20 `@Test` methods covering all 9 endpoints, happy paths, error paths |
| 2 | Unit tests verify admin-only enforcement (non-admin gets 403)                                     | VERIFIED | `testListRoles_nonAdminReturns403`, `testCreateRole_nonAdminReturns403`                    |
| 3 | Unit tests verify RBAC-disabled enforcement (returns error)                                       | VERIFIED | `testListRoles_rbacDisabledReturnsError`                                                   |
| 4 | Unit tests verify GrantStore.listByObject returns correct grants                                  | VERIFIED | `GrantStoreTest`: 3 new tests (`testListByObject_returnsMatchingGrants`, `_returnsEmptyForNoMatches`, `_filtersCorrectlyByObjectType`) using LocalKVStoreProvider |
| 5 | Unit tests verify catalog visibility filtering hides VDS/FUNCTION from non-admin users without grants | VERIFIED | `testGetNamespaceChildren_nonAdminWithoutGrant_hidesVds`, `_nonAdmin_foldersAlwaysVisible`, `_nonAdmin_pdsAlwaysVisible` |
| 6 | Unit tests verify catalog visibility filtering shows all items to admin users                     | VERIFIED | `testGetNamespaceChildren_adminUser_showsAllItems`                                         |

---

## Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `dac/backend/src/main/java/com/dremio/dac/api/RbacResource.java` | JAX-RS REST resource with 9 endpoint methods + `requireAdmin()` + `requireRbacEnabled()` | VERIFIED | 405 lines; `@APIResource`, `@Secured`, `@Path("/rbac")`, `@Inject` constructor, all 9 endpoints, both guard methods |
| `dac/backend/src/main/java/com/dremio/dac/api/RbacRole.java` | Role response DTO with Jackson annotations | VERIFIED | `@JsonCreator`, `@JsonIgnoreProperties`, `fromSysTableRoleInfo()` factory, 4 getters |
| `dac/backend/src/main/java/com/dremio/dac/api/RbacGrant.java` | Grant response DTO | VERIFIED | `@JsonCreator`, `fromProto(Grant)` factory, 5 fields with getters |
| `dac/backend/src/main/java/com/dremio/dac/api/RbacMembership.java` | Membership response DTO | VERIFIED | `@JsonCreator`, `fromProto(Membership)` factory, 3 fields with getters |
| `dac/backend/src/main/java/com/dremio/dac/api/CreateRoleRequest.java` | Request DTO with `roleName` field | VERIFIED | File present |
| `dac/backend/src/main/java/com/dremio/dac/api/AddMemberRequest.java` | Request DTO with `userName` field | VERIFIED | File present |
| `dac/backend/src/main/java/com/dremio/dac/api/GrantRequest.java` | Request DTO with `roleId`, `objectType`, `objectPath`, `privilege` | VERIFIED | File present |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java` | `listByObject()` scan-and-filter method | VERIFIED | Line 129: full scan via `store.get().find()`, filter by `objectType` + `objectPath`, returns `List<Grant>` |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` | `listMembersByRole()` and `listGrantsByObject()` wrapper methods | VERIFIED | Lines 320 and 334: delegating to `membershipStore.listByRole()` and `grantStore.listByObject()` |
| `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` | Visibility filtering via `isVisibleToUser()` | VERIFIED | Lines 3115–3160: `filterByVisibility()`, `isVisibleToUser()`, `isFunctionVisibleToUser()`; applied at line 1119 and line 374 |
| `dac/backend/src/test/java/com/dremio/dac/api/RbacResourceTest.java` | Unit tests for all `RbacResource` endpoints | VERIFIED | 20 `@Test` methods covering all 9 endpoints, admin enforcement, RBAC-disabled, error paths |
| `sabot/kernel/src/test/java/com/dremio/exec/rbac/GrantStoreTest.java` | Tests for `GrantStore.listByObject()` | VERIFIED | 3 new `testListByObject_*` methods with `LocalKVStoreProvider` |
| `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` | Tests for catalog visibility filtering | VERIFIED | 6 new `testGetNamespaceChildren_*` RBAC tests; null+null constructor call sites for existing helpers |

---

## Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `RbacResource.java` | `RbacService` | `@Inject` constructor injection | WIRED | `private final RbacService rbacService` injected via constructor (line 86); used in all 9 endpoints |
| `RbacResource.java` | `SecurityContext` | `@Inject` constructor injection | WIRED | `private final SecurityContext securityContext` injected via constructor (line 86); `securityContext.getUserPrincipal().getName()` in `requireAdmin()` and `getUserName()` |
| `RbacResource.requireAdmin()` | `RbacService.isAdminMember()` | Programmatic admin check | WIRED | Line 393: `if (!rbacService.isAdminMember(userName))` — not `@RolesAllowed` (which is a no-op in OSS) |
| `RbacResource.requireRbacEnabled()` | `DremioConfig.RBAC_ENABLED` | Feature flag gating | WIRED | Line 376: `if (!dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED))` |
| `CatalogServiceHelper.isVisibleToUser()` | `RbacService.hasPrivilege()` | Per-item privilege check for VDS and FUNCTION | WIRED | Lines 3135, 3142: `rbacService.hasPrivilege(userName, "SELECT", "VDS", objectPath)` and `rbacService.hasPrivilege(userName, "EXECUTE", "FUNCTION", objectPath)` |
| `CatalogServiceHelper.isVisibleToUser()` | `RbacService.isAdminMember()` | Admin short-circuit | WIRED | Line 3122: `if (rbacService.isAdminMember(userName)) return children;` |
| `CatalogServiceHelper constructor` | `RbacService + DremioConfig` | `@Nullable` constructor parameters | WIRED | Lines 296–297: last two parameters; assigned to `this.rbacService` and `this.dremioConfig` at lines 311–312 |
| `@APIResource` annotation on `RbacResource` | `APIServer.java` scan | `ScanResult.getAnnotatedClasses(APIResource.class)` | WIRED | `APIServer.java` line 44 iterates all `@APIResource`-annotated classes and registers them; `RbacResource` carries `@APIResource` |
| `RbacResourceTest` | `RbacResource` | Mock-based unit tests | WIRED | Constructs `new RbacResource(rbacService, securityContext, dremioConfig)` directly with mocks; all 9 endpoint methods tested |
| `GrantStoreTest.testListByObject` | `GrantStore.listByObject` | `LocalKVStoreProvider` integration | WIRED | Tests call `grantStore.listByObject(...)` directly against real KV store with pre-seeded grants |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| REST-01 | 06-01, 06-03 | REST endpoint to list all roles | SATISFIED | `@GET @Path("/roles") listRoles()` in `RbacResource`; tested in `testListRoles_returnsAllRoles` |
| REST-02 | 06-01, 06-03 | REST endpoint to create a role | SATISFIED | `@POST @Path("/roles") createRole()` in `RbacResource`; tested in `testCreateRole_createsAndReturns`, `_duplicateReturns409`, `_nullBodyReturns400` |
| REST-03 | 06-01, 06-03 | REST endpoint to delete a role | SATISFIED | `@DELETE @Path("/roles/{name}") deleteRole()` in `RbacResource`; tested in `testDeleteRole_deletesSuccessfully`, `_notFoundReturns404` |
| REST-04 | 06-01, 06-03 | REST endpoint to list members of a role | SATISFIED | `@GET @Path("/roles/{name}/members") listMembers()` in `RbacResource`; tested in `testListMembers_returnsMembers` |
| REST-05 | 06-01, 06-03 | REST endpoint to add a user to a role | SATISFIED | `@POST @Path("/roles/{name}/members") addMember()` in `RbacResource`; tested in `testAddMember_addsSuccessfully`, `_nullBodyReturns400` |
| REST-06 | 06-01, 06-03 | REST endpoint to remove a user from a role | SATISFIED | `@DELETE @Path("/roles/{name}/members/{userName}") removeMember()` in `RbacResource`; tested in `testRemoveMember_removesSuccessfully` |
| REST-07 | 06-01, 06-03 | REST endpoint to list grants on an object | SATISFIED | `@GET @Path("/grants") listGrants()` with `@QueryParam` validation in `RbacResource`; tested in `testListGrants_returnsGrants`, `_missingObjectTypeReturns400`, `_missingObjectPathReturns400` |
| REST-08 | 06-01, 06-03 | REST endpoint to grant a privilege | SATISFIED | `@POST @Path("/grants") grantPrivilege()` in `RbacResource`; tested in `testGrantPrivilege_grantsSuccessfully` |
| REST-09 | 06-01, 06-03 | REST endpoint to revoke a privilege | SATISFIED | `@DELETE @Path("/grants") revokePrivilege()` using query params in `RbacResource`; tested in `testRevokePrivilege_revokesSuccessfully`, `_missingRoleIdReturns400`, `_notFoundReturns404` |
| META-03 | 06-02, 06-03 | REST catalog API filtered by caller's effective grants | SATISFIED | `filterByVisibility()` + `isVisibleToUser()` + `isFunctionVisibleToUser()` in `CatalogServiceHelper`; applied at `getNamespaceChildrenForPath()` line 1119 and `getTopLevelCatalogItems()` line 374; 6 tests in `TestCatalogServiceHelper` |

**No orphaned requirements.** All 10 requirements mapped to Phase 6 in REQUIREMENTS.md (REST-01 through REST-09 and META-03) appear in at least one plan's `requirements` field and have implementation evidence.

---

## Anti-Patterns Found

No phase-6-introduced anti-patterns found in the key files.

Pre-existing TODOs in `CatalogServiceHelper.java` (lines 1184, 1207, 1568, 1801, 1838, 2201, 2498) are unrelated to RBAC and existed before this phase — classified as ℹ️ Info / pre-existing technical debt.

---

## Human Verification Required

### 1. End-to-end REST endpoint reachability

**Test:** Start Dremio, authenticate as an admin user, issue `GET http://localhost:9047/api/v3/rbac/roles` with a valid bearer token.
**Expected:** HTTP 200 with a JSON body `{"data": [...]}` listing at minimum the synthetic ADMIN and PUBLIC roles.
**Why human:** Auto-discovery via `@APIResource` classpath scanning happens at runtime; cannot be verified statically. Requires a running server.

### 2. Non-admin 403 enforcement end-to-end

**Test:** Start Dremio with RBAC enabled. Authenticate as a regular user who is NOT in the ADMIN role. Issue `GET http://localhost:9047/api/v3/rbac/roles`.
**Expected:** HTTP 403 Forbidden (Dremio maps `UserException.permissionError()` to 403 via `DACExceptionMapperFeature`).
**Why human:** The mapping from `UserException.permissionError()` to HTTP 403 is handled by `DACExceptionMapperFeature` at runtime; cannot verify the HTTP status code without a running container.

### 3. Catalog visibility filtering in the UI flow

**Test:** Create a VDS in Dremio. Grant SELECT on it to role "analyst". Log in as a user who is a member of "analyst" but not ADMIN. Browse the catalog space that contains the VDS.
**Expected:** The VDS is visible. Other VDS objects in the same space for which the user has no grant should not appear.
**Why human:** Requires a full Dremio environment with real users, roles, and grants to exercise the end-to-end listing path.

---

## Gaps Summary

No gaps. All 10 requirements are fully implemented and verified at all three levels (exists, substantive, wired). All test coverage is in place.

---

_Verified: 2026-02-18_
_Verifier: Claude (gsd-verifier)_
