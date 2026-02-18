# Phase 5: DDL Handlers and System Tables - Research

**Researched:** 2026-02-18
**Domain:** Dremio SQL DDL handler dispatch, SimpleDirectHandler pattern, SystemTable enum, SabotContext service wiring
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| ROLE-01 | Administrator can create named roles via CREATE ROLE SQL | RoleCreateHandler class loaded by SqlCreateRole via Class.forName; calls RbacService.createRole() |
| ROLE-02 | Administrator can drop roles via DROP ROLE SQL | RoleDropHandler class loaded by SqlDropRole; calls RbacService.deleteRole() with cascade |
| ROLE-03 | Administrator can assign users to roles via GRANT ROLE TO USER SQL | RoleGrantHandler class loaded by SqlGrantRole; calls RbacService.addMembership() |
| ROLE-04 | Administrator can remove users from roles via REVOKE ROLE FROM USER SQL | RoleRevokeHandler class loaded by SqlRevokeRole; calls RbacService.removeMembership() |
| PRIV-01 | Grant SELECT on VDS to role | CatalogGrantHandler loaded by SqlGrantOnCatalog; calls RbacService.grantPrivilege() |
| PRIV-02 | Revoke SELECT on VDS from role | CatalogRevokeHandler loaded by SqlRevokeOnCatalog; calls RbacService.revokePrivilege() |
| PRIV-03 | Grant CREATE_VIEW on VDS to role | Same CatalogGrantHandler path -- CREATE_VIEW is a privilege enum value |
| PRIV-04 | Revoke CREATE_VIEW on VDS from role | Same CatalogRevokeHandler path |
| PRIV-05 | Grant EXECUTE on UDF to role | Same CatalogGrantHandler path with GrantType.FUNCTION |
| PRIV-06 | Revoke EXECUTE on UDF from role | Same CatalogRevokeHandler path with GrantType.FUNCTION |
| DDL-01 | CREATE ROLE SQL statement wired to handler | Handler class exists on classpath; Class.forName succeeds instead of throwing ClassNotFoundException |
| DDL-02 | DROP ROLE SQL statement wired to handler | Same pattern as DDL-01 |
| DDL-03 | GRANT ROLE TO USER SQL statement wired to handler | Same pattern |
| DDL-04 | REVOKE ROLE FROM USER SQL statement wired to handler | Same pattern |
| DDL-05 | GRANT privilege ON type TO ROLE SQL statement wired | CatalogGrantHandler on classpath |
| DDL-06 | REVOKE privilege ON type FROM ROLE SQL statement wired | CatalogRevokeHandler on classpath |
| OBSV-01 | sys.roles returns all defined roles | SystemTable.ROLES already delegates to AccessControlListingManager.getRoleInfo(); needs SabotContext to return RbacService instead of null |
| OBSV-02 | sys.privileges returns all grants | SystemTable.PRIVILEGES already delegates to AccessControlListingManager.getPrivilegeInfo() |
| OBSV-03 | sys.membership returns all role-user memberships | SystemTable.MEMBERSHIP already delegates to AccessControlListingManager.getMembershipInfo() |
</phase_requirements>

## Summary

Phase 5 has two distinct work streams: (1) creating 6 DDL handler classes that the existing SQL parser infrastructure already expects via reflection, and (2) wiring the RbacService into the SabotContext/ContextService chain so that both the DDL handlers and the system tables can access it at runtime.

The system tables (sys.roles, sys.privileges, sys.membership) already exist in the `SystemTable` enum with full POJO classes (`SysTableRoleInfo`, `SysTablePrivilegeInfo`, `SysTableMembershipInfo`) and delegate to `AccessControlListingManager` -- which `RbacService` already implements. The blocker is that `SabotContext.getAccessControlListingManager()` returns `null`. Wiring `RbacService` into `SabotContext` solves BOTH problems: system tables work AND DDL handlers can access the service.

The 6 SQL parser classes (`SqlCreateRole`, `SqlDropRole`, `SqlGrantRole`, `SqlRevokeRole`, `SqlGrantOnCatalog`, `SqlRevokeOnCatalog`) use `Class.forName()` to load handler classes by fully-qualified name. The handler classes do not exist in OSS today -- which causes `ClassNotFoundException` and the "Enterprise Edition only" error. Creating these 6 classes at the expected package paths is all that's needed to make the DDL work. Each handler extends `SimpleDirectHandler`, takes `QueryContext` in its constructor, and returns `List<SimpleCommandResult>` from its `toResult()` method.

**Primary recommendation:** Thread `Provider<RbacService>` through SabotContext (same pattern as AccelerationManager), create 6 handler classes at the exact FQCNs the parsers expect, and make `RbacService.isAdminMember()` public so handlers can enforce admin-only access.

## Standard Stack

### Core (all existing, no new libraries)
| Component | Location | Purpose | Why Standard |
|-----------|----------|---------|--------------|
| SimpleDirectHandler | `com.dremio.exec.planner.sql.handlers.direct.SimpleDirectHandler` | Abstract base for DDL handlers | All RBAC handlers extend this |
| SimpleCommandResult | `com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult` | Handler return type (ok + summary) | Standard return for DDL operations |
| QueryContext | `com.dremio.exec.ops.QueryContext` | Handler constructor arg; provides user, config, catalog | Sole entry point for handler context |
| RbacService | `com.dremio.exec.rbac.RbacService` | Business logic for role/grant/membership CRUD | Phase 3 output; implements AccessControlListingManager |
| SabotContext | `com.dremio.exec.server.SabotContext` | Runtime context; implements CatalogSabotContext | Target for RbacService wiring |
| ContextService | `com.dremio.exec.server.ContextService` | Factory for SabotContext | Adds RbacService provider to constructor chain |
| SystemTable | `com.dremio.exec.store.sys.SystemTable` | Enum defining sys.* tables | ROLES, PRIVILEGES, MEMBERSHIP entries already exist |
| DACDaemonModule | `com.dremio.dac.daemon.DACDaemonModule` | DI wiring | Already binds RbacService; needs to pass to ContextService |
| UserException | `com.dremio.common.exceptions.UserException` | Standard error building | For admin-only validation, feature-flag checks |
| DremioConfig | `com.dremio.config.DremioConfig` | Feature flag (RBAC_ENABLED) | Checked by handlers before proceeding |
| SqlGrant.Privilege | `com.dremio.exec.planner.sql.parser.SqlGrant.Privilege` | Privilege enum (SELECT, EXECUTE, CREATE_VIEW) | Used by handlers to extract privilege from SQL node |
| SqlGrant.GrantType | `com.dremio.exec.planner.sql.parser.SqlGrant.GrantType` | Entity type enum (VDS, FUNCTION) | Used by handlers to map to RBAC object type |

### SQL Parser Classes (existing, NOT modified)
| Parser Class | Handler It Loads | FQ Handler Class Name |
|-------------|-----------------|----------------------|
| SqlCreateRole | RoleCreateHandler | `com.dremio.exec.planner.sql.handlers.RoleCreateHandler` |
| SqlDropRole | RoleDropHandler | `com.dremio.exec.planner.sql.handlers.RoleDropHandler` |
| SqlGrantRole | RoleGrantHandler | `com.dremio.exec.planner.sql.handlers.RoleGrantHandler` |
| SqlRevokeRole | RoleRevokeHandler | `com.dremio.exec.planner.sql.handlers.RoleRevokeHandler` |
| SqlGrantOnCatalog | CatalogGrantHandler | `com.dremio.exec.planner.sql.handlers.CatalogGrantHandler` |
| SqlRevokeOnCatalog | CatalogRevokeHandler | `com.dremio.exec.planner.sql.handlers.CatalogRevokeHandler` |

### System Table POJOs (existing, NOT modified)
| POJO | Table | Fields |
|------|-------|--------|
| SysTableRoleInfo | sys.roles | role_id, role_name, role_type, owner_id, owner_type, created_by |
| SysTablePrivilegeInfo | sys.privileges | grantee_type, grantee, object_type, object, privilege |
| SysTableMembershipInfo | sys.membership | role_name, member_name, member_type |

## Architecture Patterns

### CRITICAL: SQL Grammar Dispatch (VDS/Function grants do NOT use SqlGrant)

The SQL grammar routes `GRANT SELECT ON VDS ...` to `SqlGrantOnCatalog` (NOT `SqlGrant`). This is because `isGrantOnCatalog` defaults to `true` in the grammar and is only set to `false` for PROJECT/SYSTEM/ORG/CLOUD/ENGINE/SCRIPT cases.

```
GRANT SELECT ON VDS my_space.my_view TO ROLE analyst
  -> grammar produces SqlGrantOnCatalog
  -> toDirectHandler() loads "com.dremio.exec.planner.sql.handlers.CatalogGrantHandler"

GRANT EXECUTE ON FUNCTION my_space.my_func TO ROLE analyst
  -> grammar produces SqlGrantOnCatalog (with GrantType.FUNCTION)
  -> same CatalogGrantHandler

GRANT ROLE analyst TO USER alice
  -> grammar produces SqlGrantRole
  -> toDirectHandler() loads "com.dremio.exec.planner.sql.handlers.RoleGrantHandler"
```

The `SqlGrant` class handles only the `GRANT <priv> ON PROJECT/SYSTEM TO <user/role>` case (isGrantOnCatalog=false) via the `GrantHandler` class. Our RBAC requirements do NOT need GrantHandler/RevokeHandler -- they need CatalogGrantHandler/CatalogRevokeHandler.

### Pattern 1: Handler Dispatch via Class.forName (existing pattern -- follow it)
**What:** SQL parser nodes implement `SimpleDirectHandler.Creator`, with `toDirectHandler(QueryContext)` using `Class.forName()` to load handler classes by FQ name.
**Why it exists:** The parsers live in sabot/kernel (OSS), while EE handlers lived in a separate module. Reflection allows the EE module to provide implementations without modifying OSS parsers.
**Our approach:** Create handler classes at the EXACT FQ class names the parsers expect. No parser modification needed. The Class.forName succeeds because our classes are in the same module (sabot/kernel).

```java
// This is what SqlCreateRole.toDirectHandler() does:
public SimpleDirectHandler toDirectHandler(QueryContext context) {
    Class<?> cl = Class.forName("com.dremio.exec.planner.sql.handlers.RoleCreateHandler");
    Constructor<?> ctor = cl.getConstructor(QueryContext.class);
    return (SimpleDirectHandler) ctor.newInstance(context);
}
```

**Handler constructor signature:** Each handler MUST have `public Handler(QueryContext context)`.

### Pattern 2: SimpleDirectHandler Implementation
**What:** Handler extends SimpleDirectHandler, implements `toResult(String sql, SqlNode sqlNode)`.
**Return:** `List<SimpleCommandResult>` -- typically `Collections.singletonList(SimpleCommandResult.successful("Role 'X' created"))`.

```java
// Source: existing Dremio handlers (CreateFunctionHandler, AccelCreateReflectionHandler)
public class RoleCreateHandler extends SimpleDirectHandler {
    private final QueryContext context;

    public RoleCreateHandler(QueryContext context) {
        this.context = context;
    }

    @Override
    public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
        SqlCreateRole createRole = SqlNodeUtil.unwrap(sqlNode, SqlCreateRole.class);
        String roleName = createRole.getRoleToCreate().getSimple();

        // 1. Check feature flag
        // 2. Check admin
        // 3. Call RbacService
        // 4. Return result

        return Collections.singletonList(SimpleCommandResult.successful("Role '%s' created", roleName));
    }
}
```

### Pattern 3: SabotContext Service Wiring (for RbacService access)
**What:** Add `Provider<RbacService>` to SabotContext constructor, expose via getter. Same pattern as AccelerationManager.
**Chain:**
```
DACDaemonModule
  -> registry.bind(RbacService.class, rbacServiceInstance)   // already done in Phase 4
  -> registry.bind(ContextService.class, new ContextService(..., rbacServiceProvider))
      -> ContextService builds SabotContext with Provider<RbacService>
          -> SabotContext.getRbacService() returns the instance
              -> implements getAccessControlListingManager() returning RbacService
```

**What this solves:**
1. DDL handlers: `context.getSabotQueryContext().getRbacService()` (after adding to SabotQueryContext interface)
2. System tables: `sabotContext.getAccessControlListingManager()` returns RbacService instead of null

### Pattern 4: Admin-Only Enforcement in Handlers
**What:** All RBAC DDL operations are admin-only per requirements. Handlers must verify the calling user is an ADMIN member.
**How:** `RbacService.isAdminMember(userName)` -- currently private, needs to be made public.
**Alternative:** Use `hasPrivilege()` with a synthetic privilege, but that's over-engineered. Simple `isAdminMember()` is clearest.

```java
// In each handler's toResult():
String userName = context.getQueryUserName();
RbacService rbacService = ... ; // from QueryContext -> SabotQueryContext
if (!rbacService.isAdminMember(userName)) {
    throw UserException.permissionError()
        .message("Only administrators can execute RBAC DDL statements.")
        .buildSilently();
}
```

### Pattern 5: SqlNode Data Extraction
**What:** Each parser SqlNode exposes getters for the parsed SQL components.

| SQL Statement | Parser Node | Key Getters |
|---------------|-------------|-------------|
| CREATE ROLE analyst | SqlCreateRole | `getRoleToCreate()` -> SqlIdentifier -> `.getSimple()` = "analyst" |
| DROP ROLE analyst | SqlDropRole | `getRoleToDrop()` -> SqlIdentifier -> `.getSimple()` |
| GRANT ROLE analyst TO USER alice | SqlGrantRole | `getRoleToGrant()`, `getGranteeType()` (USER/ROLE enum), `getGrantee()` |
| REVOKE ROLE analyst FROM USER alice | SqlRevokeRole | `getRoleToRevoke()`, `getRevokeeType()`, `getRevokee()` |
| GRANT SELECT ON VDS path TO ROLE role | SqlGrantOnCatalog | `getPrivilegeList()`, `getEntityType()` (VDS/FUNCTION), `getEntity()` (path), `getGranteeType()`, `getGrantee()` |
| REVOKE SELECT ON VDS path FROM ROLE role | SqlRevokeOnCatalog | `getPrivilegeList()`, `getEntityType()`, `getEntity()`, `getRevokeeType()`, `getRevokee()` |

**Object path from SqlIdentifier:** `entity.names` is a `List<String>`. Join with "." for the dot-delimited path used by RbacConfig.grantKey(). Example: `GRANT SELECT ON VDS myspace.myview TO ROLE analyst` -> entity.names = ["myspace", "myview"] -> objectPath = "myspace.myview".

### Pattern 6: SqlCreateRole Cloud vs Non-Cloud Branching
**What:** SqlCreateRole has special handling for cloud mode:
```java
if (context.isCloud()) {
    cl = Class.forName("com.dremio.exec.planner.sql.handlers.DCSCreateRoleHandler");
} else {
    cl = Class.forName("com.dremio.exec.planner.sql.handlers.RoleCreateHandler");
}
```
Our handler is the non-cloud `RoleCreateHandler`. The `context.isCloud()` check in SqlCreateRole means our code path is correct for self-hosted Dremio OSS.

### Recommended Project Structure
```
sabot/kernel/src/main/java/com/dremio/exec/
    planner/sql/handlers/
        RoleCreateHandler.java      # NEW - CREATE ROLE
        RoleDropHandler.java        # NEW - DROP ROLE
        RoleGrantHandler.java       # NEW - GRANT ROLE TO USER
        RoleRevokeHandler.java      # NEW - REVOKE ROLE FROM USER
        CatalogGrantHandler.java    # NEW - GRANT privilege ON entity
        CatalogRevokeHandler.java   # NEW - REVOKE privilege ON entity
    rbac/
        RbacService.java            # MODIFY - make isAdminMember() public
    server/
        SabotContext.java           # MODIFY - add Provider<RbacService>, override getAccessControlListingManager()
        SabotQueryContext.java      # MODIFY - add getRbacService() with default null
        ContextService.java         # MODIFY - add Provider<RbacService> to constructor chain
    ops/
        QueryContext.java           # MODIFY - add getRbacService() delegating to SabotQueryContext
dac/backend/src/main/java/com/dremio/dac/daemon/
    DACDaemonModule.java            # MODIFY - pass RbacService provider to ContextService
```

### Anti-Patterns to Avoid
- **Modifying SQL parser classes:** Do NOT modify SqlCreateRole, SqlGrant, etc. The Class.forName pattern works -- just create the handler classes at the expected names.
- **Using SqlGrant handler for VDS/Function grants:** The grammar routes VDS/Function grants to SqlGrantOnCatalog, NOT SqlGrant. Creating a "GrantHandler" class would only handle the PROJECT/SYSTEM case, not the actual privilege grants we need.
- **Creating custom system table implementations:** The SystemTable enum entries for ROLES, PRIVILEGES, MEMBERSHIP already exist and work correctly. The only fix needed is wiring RbacService into SabotContext so getAccessControlListingManager() returns non-null.
- **Skipping feature flag check in handlers:** Always check `DremioConfig.RBAC_ENABLED` first. If RBAC is disabled, DDL should either work silently (creating roles for later use) or be gated. Recommendation: allow DDL even when enforcement is off, so admins can set up roles before enabling enforcement.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SQL parsing for role/grant DDL | Custom parser | Existing grammar (role.ftl, grant.ftl) + SqlNode classes | Grammar already parses all 6 statements correctly |
| System table schema | Custom Arrow schema | SysTableRoleInfo/PrivilegeInfo/MembershipInfo POJOs + PojoDataType | SystemTable enum auto-derives schema from POJO fields |
| Handler dispatch | Custom router | SimpleDirectHandler.Creator + Class.forName pattern | CommandCreator already handles dispatch via instanceof check |
| Admin authorization | Custom auth framework | RbacService.isAdminMember() check in each handler | One-liner; ADMIN membership already tracked in KV store |
| Error responses | Custom error types | UserException.permissionError() / UserException.validationError() | Standard Dremio error mechanism with proper error codes |

**Key insight:** The entire SQL parsing, dispatch, and system table infrastructure already exists. Phase 5 is about filling in the missing handler classes and wiring a single service provider through the context chain.

## Common Pitfalls

### Pitfall 1: Wrong Handler Class for Privilege Grants
**What goes wrong:** Creating `GrantHandler` and `RevokeHandler` classes, expecting them to handle `GRANT SELECT ON VDS ... TO ROLE ...`.
**Why it happens:** The SqlGrant parser class references `GrantHandler`, leading one to think that's the right class. But the SQL grammar dispatches VDS/Function grants to SqlGrantOnCatalog, which loads `CatalogGrantHandler`.
**How to avoid:** Create `CatalogGrantHandler` and `CatalogRevokeHandler` for privilege grants on catalog entities.
**Warning signs:** "Enterprise Edition only" error persists for `GRANT SELECT ON VDS` even after creating GrantHandler.

### Pitfall 2: SabotContext.getAccessControlListingManager() Returns Null
**What goes wrong:** System tables (sys.roles, sys.privileges, sys.membership) throw `IllegalAccessException("Unable to retrieve sys.roles.")` at query time.
**Why it happens:** SabotContext base class returns null. The override must be added to return the RbacService instance.
**How to avoid:** Wire Provider<RbacService> into SabotContext and override getAccessControlListingManager() to return `rbacServiceProvider.get()`.
**Warning signs:** Queries like `SELECT * FROM sys.roles` fail with "Unable to retrieve" error.

### Pitfall 3: Forgetting Cloud Check in SqlCreateRole
**What goes wrong:** RoleCreateHandler breaks cloud/DCS deployments.
**Why it happens:** SqlCreateRole checks `context.isCloud()` and loads DCSCreateRoleHandler for cloud, RoleCreateHandler for non-cloud. Our handler is only for non-cloud.
**How to avoid:** Do NOT modify SqlCreateRole's branching logic. Our RoleCreateHandler is correctly loaded only in non-cloud mode.
**Warning signs:** N/A for OSS -- but must not break the isCloud() branch.

### Pitfall 4: SqlIdentifier Path Handling for Grants
**What goes wrong:** Object path stored incorrectly in KV store, causing privilege checks to fail.
**Why it happens:** SqlIdentifier compound identifiers have `names` as a List<String>. If the handler converts this to the wrong format, the key won't match what RbacService.hasPrivilege() uses.
**How to avoid:** Use `String.join(".", entity.names)` to produce the dot-delimited path, matching RbacConfig.grantKey() format.
**Warning signs:** `GRANT SELECT ON VDS myspace.myview TO ROLE analyst` succeeds, but `SELECT * FROM myspace.myview` is still denied.

### Pitfall 5: Constructor Signature Mismatch
**What goes wrong:** Class.forName finds the handler class but constructor lookup fails with NoSuchMethodException.
**Why it happens:** Handler constructor does not match `(QueryContext.class)` exactly.
**How to avoid:** Every handler MUST have exactly `public HandlerName(QueryContext context)` as its only constructor.
**Warning signs:** InvocationTargetException or NoSuchMethodException at SQL execution time.

### Pitfall 6: Not Checking Feature Flag in DDL Handlers
**What goes wrong:** DDL succeeds but the feature is supposed to be gated.
**Why it happens:** Handler does not check DremioConfig.RBAC_ENABLED.
**How to avoid:** Decide on the gating policy upfront. Recommended: DDL operations should work even when RBAC enforcement is OFF -- this allows admins to set up roles/grants before enabling enforcement. The feature flag only gates enforcement in CatalogImpl. Document this decision clearly.
**Warning signs:** Inconsistent behavior depending on flag state.

### Pitfall 7: ContextService Constructor Has 40+ Parameters
**What goes wrong:** Adding Provider<RbacService> to the wrong position or missing it in the call chain.
**Why it happens:** ContextService.java and SabotContext constructors are very long (30+ parameters). Easy to miscount.
**How to avoid:** Add Provider<RbacService> as the LAST parameter to minimize risk. Follow the pattern of how `metadataIOPoolProvider` was added (last param).
**Warning signs:** Compilation error in ContextService or SabotContext with wrong parameter count.

## Code Examples

### Handler: RoleCreateHandler
```java
// Source: Pattern derived from CreateFunctionHandler + AccelCreateReflectionHandler
package com.dremio.exec.planner.sql.handlers;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.handlers.direct.SimpleDirectHandler;
import com.dremio.exec.planner.sql.handlers.direct.SqlNodeUtil;
import com.dremio.exec.planner.sql.parser.SqlCreateRole;
import com.dremio.exec.rbac.RbacService;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlNode;

public class RoleCreateHandler extends SimpleDirectHandler {
    private final QueryContext context;

    public RoleCreateHandler(QueryContext context) {
        this.context = context;
    }

    @Override
    public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
        final SqlCreateRole createRole = SqlNodeUtil.unwrap(sqlNode, SqlCreateRole.class);
        final String roleName = createRole.getRoleToCreate().getSimple();
        final String userName = context.getQueryUserName();
        final RbacService rbacService = context.getRbacService();

        // Admin check
        if (rbacService == null || !rbacService.isAdminMember(userName)) {
            throw UserException.permissionError()
                .message("Only administrators can create roles.")
                .buildSilently();
        }

        // Use roleName as both ID and name (slugified)
        rbacService.createRole(roleName, roleName, userName);
        return Collections.singletonList(
            SimpleCommandResult.successful("Role '%s' created.", roleName));
    }
}
```

### Handler: CatalogGrantHandler (for GRANT SELECT ON VDS)
```java
// Source: Pattern derived from SqlGrantOnCatalog dispatch + RbacService API
package com.dremio.exec.planner.sql.handlers;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.handlers.direct.SimpleDirectHandler;
import com.dremio.exec.planner.sql.handlers.direct.SqlNodeUtil;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.planner.sql.parser.SqlGrantOnCatalog;
import com.dremio.exec.rbac.RbacService;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;

public class CatalogGrantHandler extends SimpleDirectHandler {
    private final QueryContext context;

    public CatalogGrantHandler(QueryContext context) {
        this.context = context;
    }

    @Override
    public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
        final SqlGrantOnCatalog grantNode = SqlNodeUtil.unwrap(sqlNode, SqlGrantOnCatalog.class);
        final String userName = context.getQueryUserName();
        final RbacService rbacService = context.getRbacService();

        // Admin check
        if (rbacService == null || !rbacService.isAdminMember(userName)) {
            throw UserException.permissionError()
                .message("Only administrators can grant privileges.")
                .buildSilently();
        }

        // Extract grant details
        final String grantee = grantNode.getGrantee().getSimple();
        final SqlGrant.GrantType entityType = grantNode.getEntityType()
            .symbolValue(SqlGrant.GrantType.class);
        final String objectType = entityType.name();       // "VDS" or "FUNCTION"
        final String objectPath = String.join(".", grantNode.getEntity().names);

        // Process each privilege in the list
        for (SqlNode privNode : grantNode.getPrivilegeList()) {
            SqlGrant.Privilege privilege = ((SqlLiteral) privNode)
                .symbolValue(SqlGrant.Privilege.class);
            rbacService.grantPrivilege(grantee, objectType, objectPath,
                privilege.name(), userName);
        }

        return Collections.singletonList(
            SimpleCommandResult.successful("Privilege granted on %s %s to %s.",
                objectType, objectPath, grantee));
    }
}
```

### SabotContext Wiring Addition
```java
// In SabotContext.java -- add field and override
private final Provider<RbacService> rbacServiceProvider;

// In constructor -- add parameter
public SabotContext(..., Provider<RbacService> rbacServiceProvider) {
    ...
    this.rbacServiceProvider = rbacServiceProvider;
}

// New getter (add to SabotQueryContext interface too)
public RbacService getRbacService() {
    return rbacServiceProvider != null ? rbacServiceProvider.get() : null;
}

// Override existing method (currently returns null)
@Override
public AccessControlListingManager getAccessControlListingManager() {
    return rbacServiceProvider != null ? rbacServiceProvider.get() : null;
}
```

### QueryContext Delegation
```java
// In QueryContext.java
public RbacService getRbacService() {
    return sabotQueryContext.getRbacService();
}
```

### SabotQueryContext Interface Addition
```java
// In SabotQueryContext.java -- add default method
default RbacService getRbacService() {
    return null;
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| EE-only handlers via reflection | OSS handler classes on classpath | Phase 5 (now) | DDL works without Enterprise Edition |
| getAccessControlListingManager() returns null | Returns RbacService | Phase 5 (now) | sys.roles/privileges/membership work |
| Six separate handler classes | Six handler classes (same count) | N/A | Each handler maps 1:1 to a SQL statement |

**Deprecated/outdated:**
- `SqlGrant`/`SqlRevoke` parser classes: These handle PROJECT/SYSTEM grants only. For VDS/Function grants, the grammar routes to `SqlGrantOnCatalog`/`SqlRevokeOnCatalog`.
- `GrantHandler`/`RevokeHandler` FQ class names: These would only be used for PROJECT/SYSTEM privilege grants. Not needed for our RBAC scope.

## Open Questions

1. **Should DDL be gated behind the feature flag?**
   - What we know: Enforcement in CatalogImpl is gated behind DremioConfig.RBAC_ENABLED. DDL creates roles/grants in the KV store.
   - What's unclear: Should `CREATE ROLE` fail when RBAC is disabled?
   - Recommendation: Allow DDL to work regardless of feature flag. This lets admins set up roles and grants before enabling enforcement. The flag only controls enforcement, not administration. This is the safest approach and matches how other systems work (e.g., you can create users even if authentication is disabled).

2. **Should non-admin users be allowed to query sys.roles/privileges/membership?**
   - What we know: Requirements say "ADMIN-only" for listing endpoints. The SystemTable infrastructure does not have per-table access control.
   - What's unclear: Whether system table queries should be filtered or blocked for non-admins.
   - Recommendation: For v1, allow all users to query system tables (read-only). The system table entries already exist and work for any user. Access control on system tables is a v2 concern (META-02 in requirements). DDL operations are admin-gated; querying is not.

3. **SqlNodeUtil.unwrap() usage**
   - What we know: Some handlers use `SqlNodeUtil.unwrap(sqlNode, TargetClass.class)` for safe casting with error messages.
   - What's unclear: Whether SqlNodeUtil is available in all handler paths.
   - Recommendation: Use SqlNodeUtil.unwrap() consistently -- it provides better error messages than raw casts.

## Sources

### Primary (HIGH confidence)
- **SqlCreateRole.java** (line 80-97): toDirectHandler() uses Class.forName("...RoleCreateHandler"), isCloud() branch
- **SqlDropRole.java** (line 80-97): toDirectHandler() loads RoleDropHandler
- **SqlGrantRole.java** (line 93-105): toDirectHandler() loads RoleGrantHandler
- **SqlRevokeRole.java** (line 93-105): toDirectHandler() loads RoleRevokeHandler
- **SqlGrantOnCatalog.java** (line 132-144): toDirectHandler() loads CatalogGrantHandler
- **SqlRevokeOnCatalog.java** (line 131-141): toDirectHandler() loads CatalogRevokeHandler
- **grant.ftl** (line 57-283): SQL grammar; VDS/FUNCTION cases keep isGrantOnCatalog=true, producing SqlGrantOnCatalog
- **role.ftl**: SQL grammar for CREATE ROLE, DROP ROLE, GRANT/REVOKE ROLE
- **SystemTable.java** (line 227-279): ROLES, PRIVILEGES, MEMBERSHIP entries delegate to AccessControlListingManager
- **SabotContext.java** (line 554-556): getAccessControlListingManager() returns null
- **RbacService.java**: Already implements AccessControlListingManager with getRoleInfo(), getPrivilegeInfo(), getMembershipInfo()
- **DACDaemonModule.java** (line 1029-1061): RbacService already bound via registry.bind()
- **SimpleDirectHandler.java**: Handler abstract base and Creator interface
- **SimpleCommandResult.java**: Return type with ok/summary fields
- **SysTableRoleInfo.java, SysTablePrivilegeInfo.java, SysTableMembershipInfo.java**: POJO schemas already defined

### Secondary (MEDIUM confidence)
- **ContextService.java** (line 294-375): SabotContext construction with 30+ params -- pattern for adding new Provider
- **QueryContext.java**: getDremioConfig(), getQueryUserName(), getSabotQueryContext() -- available to handlers
- **SabotQueryContext.java**: Interface with default methods -- supports adding getRbacService() with null default

## Metadata

**Confidence breakdown:**
- DDL handler dispatch pattern: HIGH -- verified directly in parser source code and grammar
- System table wiring: HIGH -- SystemTable enum entries verified, SabotContext null return confirmed
- SabotContext wiring approach: HIGH -- follows identical pattern to AccelerationManager/StatisticsService
- Handler implementation details: HIGH -- based on verified patterns from CreateFunctionHandler and AccelCreateReflectionHandler
- Feature flag gating decision: MEDIUM -- recommendation is reasonable but not locked by prior decisions

**Research date:** 2026-02-18
**Valid until:** 30 days (stable Dremio codebase, no breaking changes expected)
