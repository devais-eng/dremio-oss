---
phase: 05-ddl-handlers-and-system-tables
plan: "02"
subsystem: DDL handler dispatch
tags: [rbac, ddl, handlers, sql-parser, reflection, privilege-grant]
dependency_graph:
  requires:
    - 05-01-SUMMARY.md  # RbacService wired into QueryContext via SabotContext
    - 03-01-SUMMARY.md  # RbacService implementation with createRole/deleteRole/addMembership etc.
  provides:
    - CREATE ROLE SQL handler (RoleCreateHandler)
    - DROP ROLE SQL handler (RoleDropHandler)
    - GRANT ROLE TO USER SQL handler (RoleGrantHandler)
    - REVOKE ROLE FROM USER SQL handler (RoleRevokeHandler)
    - GRANT privilege ON entity TO ROLE SQL handler (CatalogGrantHandler)
    - REVOKE privilege ON entity FROM ROLE SQL handler (CatalogRevokeHandler)
  affects:
    - sabot/kernel SQL handler classpath (6 new classes loaded via Class.forName)
    - All 6 RBAC DDL SQL statements (previously threw "Enterprise Edition only")
tech_stack:
  added: []
  patterns:
    - SimpleDirectHandler extension with QueryContext constructor
    - Class.forName reflection dispatch (existing parser pattern, no modification)
    - Admin-only enforcement via RbacService.isAdminMember() in every handler
    - Multi-privilege loop over SqlGrantOnCatalog.getPrivilegeList() / SqlRevokeOnCatalog.getPrivilegeList()
    - Dot-delimited object path via String.join(".", entity.names)
key_files:
  created:
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleCreateHandler.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleDropHandler.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleGrantHandler.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleRevokeHandler.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/CatalogGrantHandler.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/CatalogRevokeHandler.java
  modified: []
decisions:
  - "DDL operations work regardless of RBAC_ENABLED flag -- admins set up roles/grants before enabling enforcement"
  - "Handler names are CatalogGrantHandler/CatalogRevokeHandler (NOT GrantHandler/RevokeHandler) -- SqlGrantOnCatalog/SqlRevokeOnCatalog handle VDS/Function grants, not SqlGrant"
  - "Object path uses String.join('.', entity.names) to produce dot-delimited path matching RbacConfig.grantKey() format"
  - "Admin check via rbacService.isAdminMember(userName) at the top of every handler's toResult() before any mutation"
  - "deleteRole() and removeMembership() declare throws RbacEntityNotFoundException (checked) -- propagates as user-visible error via throws Exception on toResult()"
metrics:
  duration: "~8 min"
  completed: "2026-02-18"
  tasks_completed: 2
  files_created: 6
  files_modified: 0
---

# Phase 5 Plan 02: DDL Handlers Summary

**One-liner:** 6 DDL handler classes wired via Class.forName reflection replacing Enterprise-only stubs for CREATE/DROP/GRANT/REVOKE ROLE and GRANT/REVOKE privilege on VDS/FUNCTION.

## What Was Built

Created 6 Java handler classes in `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/`. Each class is loaded by the existing SQL parser infrastructure via `Class.forName()` when a user executes the corresponding DDL statement. These classes did not exist before, causing `ClassNotFoundException` and the "Enterprise Edition only" error.

### Task 1: Role DDL Handlers (4 files)

**RoleCreateHandler** -- handles `CREATE ROLE analyst`
- Calls `rbacService.createRole(roleName, roleName, userName)` (roleId = slugified name = name itself)
- Loaded by `SqlCreateRole.toDirectHandler()` (non-cloud path)

**RoleDropHandler** -- handles `DROP ROLE analyst`
- Calls `rbacService.deleteRole(roleName)` which cascades to all grants and memberships
- `RbacEntityNotFoundException` propagates naturally via `throws Exception`

**RoleGrantHandler** -- handles `GRANT ROLE analyst TO USER alice`
- Calls `rbacService.addMembership(granteeName, roleName, userName)`
- Extracts grantee from `SqlGrantRole.getGrantee().getSimple()`

**RoleRevokeHandler** -- handles `REVOKE ROLE analyst FROM USER alice`
- Calls `rbacService.removeMembership(revokeeName, roleName)`
- Extracts revokee from `SqlRevokeRole.getRevokee().getSimple()`

### Task 2: Catalog Privilege DDL Handlers (2 files)

**CatalogGrantHandler** -- handles `GRANT SELECT ON VDS myspace.myview TO ROLE analyst`
- Loaded by `SqlGrantOnCatalog` (which handles VDS/FUNCTION grants, NOT SqlGrant)
- Loops over `getPrivilegeList()` to support multiple privileges in one statement
- Extracts entity type via `getEntityType().symbolValue(SqlGrant.GrantType.class).name()` -> "VDS" or "FUNCTION"
- Builds dot-delimited path via `String.join(".", getEntity().names)` matching `RbacConfig.grantKey()` format
- Calls `rbacService.grantPrivilege(grantee, objectType, objectPath, privilege.name(), userName)`

**CatalogRevokeHandler** -- handles `REVOKE SELECT ON VDS myspace.myview FROM ROLE analyst`
- Mirrors CatalogGrantHandler using `SqlRevokeOnCatalog` and `revokePrivilege()`
- Extracts revokee via `getRevokee().getSimple()` instead of `getGrantee()`

### Common Pattern (all 6 handlers)

```java
public class XxxHandler extends SimpleDirectHandler {
  private final QueryContext context;

  public XxxHandler(QueryContext context) {   // exact signature for cl.getConstructor(QueryContext.class)
    this.context = context;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    // 1. Admin check
    final RbacService rbacService = context.getRbacService();
    if (rbacService == null || !rbacService.isAdminMember(context.getQueryUserName())) {
      throw UserException.permissionError()
          .message("Only administrators can execute RBAC DDL statements.").buildSilently();
    }
    // 2. Extract SQL node fields
    // 3. Delegate to RbacService
    // 4. Return SimpleCommandResult.successful(...)
  }
}
```

## Decisions Made

1. **DDL works regardless of RBAC_ENABLED flag** -- allows admins to set up roles/grants before enabling enforcement. The flag only gates enforcement in CatalogImpl.validatePrivilege(). This matches the locked Phase 5 planning decision.

2. **CatalogGrantHandler / CatalogRevokeHandler (not GrantHandler / RevokeHandler)** -- The SQL grammar routes `GRANT SELECT ON VDS ...` to `SqlGrantOnCatalog`, which loads `CatalogGrantHandler`. `SqlGrant` (which would load `GrantHandler`) only handles `GRANT privilege ON PROJECT/SYSTEM` cases -- not relevant to our RBAC scope.

3. **Object path format** -- `String.join(".", entity.names)` produces the same dot-delimited format used by `RbacConfig.grantKey()`, ensuring privilege grant and privilege check use identical keys.

4. **Admin check at handler entry point** -- Every handler checks `isAdminMember()` before any read or write to the KV store. The `rbacService == null` guard handles non-DAC test contexts where RBAC is not wired.

5. **Checked exceptions propagate via `throws Exception`** -- `deleteRole()` and `removeMembership()` declare `throws RbacEntityNotFoundException`. The `throws Exception` on `toResult()` covers this without wrapping, letting the not-found error surface as a user-visible message.

## Deviations from Plan

None - plan executed exactly as written.

## Self-Check

### Files Created

- [x] `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleCreateHandler.java` -- FOUND
- [x] `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleDropHandler.java` -- FOUND
- [x] `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleGrantHandler.java` -- FOUND
- [x] `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/RoleRevokeHandler.java` -- FOUND
- [x] `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/CatalogGrantHandler.java` -- FOUND
- [x] `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/CatalogRevokeHandler.java` -- FOUND

### Commits

- [x] `05aaf085a` -- feat(05-02): create role DDL handlers (CREATE/DROP/GRANT/REVOKE ROLE)
- [x] `870c4c807` -- feat(05-02): create catalog privilege DDL handlers (GRANT/REVOKE privilege)

### FQCN Verification

Parser Class.forName strings verified to match handler package+class names:
- `SqlCreateRole` -> `com.dremio.exec.planner.sql.handlers.RoleCreateHandler` -- MATCH
- `SqlDropRole` -> `com.dremio.exec.planner.sql.handlers.RoleDropHandler` -- MATCH
- `SqlGrantRole` -> `com.dremio.exec.planner.sql.handlers.RoleGrantHandler` -- MATCH
- `SqlRevokeRole` -> `com.dremio.exec.planner.sql.handlers.RoleRevokeHandler` -- MATCH
- `SqlGrantOnCatalog` -> `com.dremio.exec.planner.sql.handlers.CatalogGrantHandler` -- MATCH
- `SqlRevokeOnCatalog` -> `com.dremio.exec.planner.sql.handlers.CatalogRevokeHandler` -- MATCH

## Self-Check: PASSED
