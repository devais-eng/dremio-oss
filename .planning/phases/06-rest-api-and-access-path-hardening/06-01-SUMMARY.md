---
phase: 06-rest-api-and-access-path-hardening
plan: "01"
subsystem: rest-api
tags: [rest, rbac, jax-rs, dto, jersey, hk2]
dependency_graph:
  requires:
    - 05-01 (RbacService in SabotContext/ContextService/DACDaemonModule, isAdminMember() public)
    - 03-01 (RbacService methods: createRole, deleteRole, addMembership, removeMembership, grantPrivilege, revokePrivilege, getRoleInfo, isAdminMember)
    - 02-01 (GrantStore, MembershipStore, RbacEntityNotFoundException, RbacEntityAlreadyExistsException)
  provides:
    - REST API at /api/v3/rbac/* for RBAC management (9 endpoints)
    - GrantStore.listByObject() for object-based grant listing
    - RbacService.listMembersByRole() and listGrantsByObject() wrappers
  affects:
    - 06-02 (CatalogServiceHelper visibility filtering can use same RbacService injection pattern)
tech_stack:
  added: []
  patterns:
    - "@APIResource + @Secured JAX-RS auto-scan pattern (same as CatalogResource)"
    - "HK2 @Inject constructor injection for RbacService + SecurityContext + DremioConfig"
    - "Programmatic isAdminMember() admin check (NOT @RolesAllowed -- it is a no-op in Dremio OSS)"
    - "ResponseList<T> standard v3 list wrapper"
    - "Jackson @JsonCreator + @JsonProperty DTO pattern"
    - "Scan-and-filter for listByObject (consistent with listByRole in GrantStore)"
key_files:
  created:
    - dac/backend/src/main/java/com/dremio/dac/api/RbacResource.java
    - dac/backend/src/main/java/com/dremio/dac/api/RbacRole.java
    - dac/backend/src/main/java/com/dremio/dac/api/RbacMembership.java
    - dac/backend/src/main/java/com/dremio/dac/api/RbacGrant.java
    - dac/backend/src/main/java/com/dremio/dac/api/CreateRoleRequest.java
    - dac/backend/src/main/java/com/dremio/dac/api/AddMemberRequest.java
    - dac/backend/src/main/java/com/dremio/dac/api/GrantRequest.java
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
decisions:
  - "[06-01]: RbacResource uses programmatic isAdminMember() check -- @RolesAllowed is a no-op because DACSecurityContext.isUserInRole() always returns true"
  - "[06-01]: DELETE /rbac/grants uses query parameters (roleId, objectType, objectPath, privilege) not request body -- follows DELETE /catalog/{id}?tag=... pattern"
  - "[06-01]: Role ID = role name (slugified = name itself per Phase 1 locked decision; no separate slug field)"
  - "[06-01]: GrantStore.listByObject is a full scan-and-filter -- object-based prefix matching is impossible because grant key puts role_id first"
  - "[06-01]: requireRbacEnabled() + requireAdmin() called at start of every endpoint, not via filter"
metrics:
  duration: "3 min"
  completed_date: "2026-02-18"
  tasks_completed: 2
  tasks_total: 2
  files_created: 7
  files_modified: 2
---

# Phase 6 Plan 01: RBAC REST API Resource Summary

**One-liner:** JAX-RS RbacResource with 9 admin-only endpoints at /api/v3/rbac using programmatic isAdminMember() enforcement, plus GrantStore.listByObject() scan-and-filter and RbacService wrapper methods.

## What Was Built

### Task 1: GrantStore.listByObject and RbacService wrappers (commit eea649d37)

Added `GrantStore.listByObject(objectType, objectPath)` -- a scan-and-filter method that iterates all grant records and filters by matching objectType and objectPath fields. This is the only viable approach because the grant key format (`role_id|object_type|object_path|privilege`) puts role_id first, making object-based prefix scans impossible. Consistent with the existing `listByRole` scan-and-filter pattern.

Added two public wrapper methods to `RbacService` in a new "REST API support" section:
- `listMembersByRole(roleId)` -- delegates to `MembershipStore.listByRole()`
- `listGrantsByObject(objectType, objectPath)` -- delegates to `GrantStore.listByObject()`

These keep the REST layer thin: RbacResource calls RbacService, which calls stores.

### Task 2: DTO classes and RbacResource REST endpoint (commit 15196709f)

**6 DTO classes** all in `com.dremio.dac.api`, following the CatalogItem.java Jackson pattern:
- `RbacRole` -- response DTO with `fromSysTableRoleInfo()` factory mapping `SysTableRoleInfo` fields
- `RbacMembership` -- response DTO with `fromProto(Membership)` factory mapping proto fields
- `RbacGrant` -- response DTO with `fromProto(Grant)` factory mapping proto fields
- `CreateRoleRequest` -- request body with `roleName` field
- `AddMemberRequest` -- request body with `userName` field
- `GrantRequest` -- request body with `roleId`, `objectType`, `objectPath`, `privilege` fields

**RbacResource.java** -- 9-endpoint JAX-RS resource:

| # | Method | Path | Requirement | Description |
|---|--------|------|-------------|-------------|
| 1 | GET | /rbac/roles | REST-01 | List all roles via getRoleInfo() |
| 2 | POST | /rbac/roles | REST-02 | Create role via createRole() |
| 3 | DELETE | /rbac/roles/{name} | REST-03 | Delete role via deleteRole() |
| 4 | GET | /rbac/roles/{name}/members | REST-04 | List members via listMembersByRole() |
| 5 | POST | /rbac/roles/{name}/members | REST-05 | Add member via addMembership() |
| 6 | DELETE | /rbac/roles/{name}/members/{userName} | REST-06 | Remove member via removeMembership() |
| 7 | GET | /rbac/grants | REST-07 | List grants via listGrantsByObject() |
| 8 | POST | /rbac/grants | REST-08 | Grant privilege via grantPrivilege() |
| 9 | DELETE | /rbac/grants | REST-09 | Revoke privilege via revokePrivilege() |

**Admin enforcement:** Every endpoint calls `requireRbacEnabled()` then `requireAdmin()`. The `requireAdmin()` helper checks `rbacService.isAdminMember(userName)` programmatically -- `@RolesAllowed("admin")` at the class level is present only for convention since `DACSecurityContext.isUserInRole()` always returns `true` in Dremio OSS.

**Feature flag gating:** `requireRbacEnabled()` checks `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` and throws `UserException.unsupportedError()` (maps to 400) if RBAC is off.

**Exception mapping:**
- `RbacEntityNotFoundException` -> `NotFoundException` (404)
- `RbacEntityAlreadyExistsException` -> `WebApplicationException(409 CONFLICT)`
- `IllegalArgumentException` -> `BadRequestException` (400)
- `UserException.permissionError()` -> handled by `DACExceptionMapperFeature` (403)
- `UserException.unsupportedError()` -> handled by `DACExceptionMapperFeature` (400)

## Deviations from Plan

None -- plan executed exactly as written.

## Requirements Coverage

| ID | Description | Status |
|----|-------------|--------|
| REST-01 | GET /rbac/roles returns list of all roles | DONE |
| REST-02 | POST /rbac/roles creates a role | DONE |
| REST-03 | DELETE /rbac/roles/{name} deletes a role | DONE |
| REST-04 | GET /rbac/roles/{name}/members returns members | DONE |
| REST-05 | POST /rbac/roles/{name}/members adds a user | DONE |
| REST-06 | DELETE /rbac/roles/{name}/members/{userName} removes a user | DONE |
| REST-07 | GET /rbac/grants?objectType=...&objectPath=... returns grants | DONE |
| REST-08 | POST /rbac/grants grants a privilege | DONE |
| REST-09 | DELETE /rbac/grants?roleId=...&... revokes a privilege | DONE |

## Self-Check: PASSED

All 7 created files confirmed present. Both commits (eea649d37, 15196709f) confirmed in git log. Key content (listByObject, listMembersByRole, listGrantsByObject, @APIResource) confirmed in respective files.
