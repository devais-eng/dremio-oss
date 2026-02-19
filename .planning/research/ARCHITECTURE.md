# ARCHITECTURE.md — RBAC Integration with Dremio Catalog

**Research Date:** 2026-02-17
**Research Type:** Architecture dimension — how RBAC enforcement integrates with Dremio's catalog.

---

## 1. Existing Architecture (What Is Already There)

**The catalog stack (request-scoped, constructed per query):**
```
SQL text
  -> QueryContext.getCatalog()
     -> CachingCatalog
        -> SourceAccessChecker (blocks internal sources for non-system users)
           -> CatalogImpl   <-- enforcement hook lives here (no-op)
              -> DatasetManager  <-- resolves tables, no permission check
```

`CatalogServiceImpl.getCatalog(MetadataRequestOptions)` at line 941 constructs the stack. The username flows from `session.getCredentials().getUserName()` through `SchemaConfig` into `CatalogImpl.this.userName`.

**The no-op hook (`CatalogImpl.java:2767`):**
```java
@Override
public void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege) {
    // For the default implementation, don't validate privilege.
}
```

This is the primary enforcement integration point. It is already called for DDL mutations (e.g., `dropPrimaryKey()`). It needs to be extended to `getTable()` and UDF resolution.

**DDL parsers exist, handlers do not (in OSS):**
`SqlCreateRole`, `SqlDropRole`, `SqlGrantRole`, `SqlGrant`, `SqlRevoke` all parse correctly. Their `toDirectHandler()` loads handler classes by reflection — if the class is not found, they throw "Enterprise Edition only". The handler class names are already hardcoded: `RoleCreateHandler`, `RoleDropHandler`, `RoleGrantHandler`, `GrantHandler`.

**System tables already defined (`SystemTable.java`):**
`ROLES`, `PRIVILEGES`, and `MEMBERSHIP` enum values exist and delegate to `AccessControlListingManager`. `SabotContext.getAccessControlListingManager()` returns `null` today — wiring a real implementation makes all three system tables live.

**The `Privilege` enum (`SqlGrant.java:48-78`) already includes:** `SELECT`, `EXECUTE`, `ALTER`, `INSERT`, `DELETE`, `UPDATE`, `CREATE_TABLE`, `DROP`, `CREATE_ROLE`, `ALL`, and more.

---

## 2. New Components Needed

| Component | Location | Boundary |
|---|---|---|
| `RbacStore` | `com.dremio.exec.store.sys.accesscontrol.RbacStoreImpl` | Two `LegacyKVStore` tables: roles, grants |
| `RbacService` / `RbacServiceImpl` | Same package | Business logic: `hasPrivilege()`, role membership resolution |
| `AccessControlListingManagerImpl` | Same package | Implements `AccessControlListingManager`; reads from `RbacStore` |
| DDL Handlers (5x) | `com.dremio.exec.planner.sql.handlers.*` | One `SimpleDirectHandler` per DDL verb |
| REST Resources | `com.dremio.dac.resource.rbac.*` | Jersey JAX-RS; inject `RbacService` |

---

## 3. Data Flow: SELECT Query Permission Check

```
1. SQL arrives at coordinator
2. QueryContext built: userName = "alice"
3. CatalogServiceImpl.getCatalog() constructs CatalogImpl with userName="alice"
4. Planner calls catalog.getTable(NamespaceKey["myspace","myview"])
5. CatalogImpl.getTable() -> DatasetManager.getTable() -> DremioTable
6. [NEW] CatalogImpl calls validatePrivilege(key, SELECT)
7. validatePrivilege() calls rbacService.hasPrivilege("alice", SELECT, VDS, "myspace.myview")
8. RbacService:
   a. Gets roles for "alice" from RbacStore (always includes PUBLIC)
   b. Checks if any role has ADMIN -> bypass
   c. Checks grants for each role on this object
   d. Returns true/false
9. False -> throw UserException.permissionError()
10. True -> return DremioTable to planner
```

## 4. Data Flow: GRANT Statement

```
1. SQL: GRANT SELECT ON VDS myspace.myview TO ROLE analyst
2. SqlGrant.toDirectHandler() loads GrantHandler by reflection
3. GrantHandler.toResult():
   a. Extract privilege=SELECT, objectType=VDS, objectKey="myspace.myview", granteeType=ROLE, granteeName="analyst"
   b. Call rbacService.grantPrivilege(...)
   c. rbacService writes GrantEntry to RbacStore (LegacyKVStore.put())
   d. Return SimpleCommandResult("OK")
```

## 5. Data Flow: sys.roles Query

```
1. SQL: SELECT * FROM sys.roles
2. SystemTable.ROLES.getIterator(sabotContext, opCtx) called
3. sabotContext.getAccessControlListingManager() returns AccessControlListingManagerImpl [NEW - wired]
4. getRoleInfo() iterates RbacStore.listRoles() -> maps to SysTableRoleInfo
5. Returned as POJO iterator, scanned like any other system table
```

---

## 6. Build Order

**Phase 1 — Storage:** Proto schema -> `RbacStoreImpl` -> unit tests

**Phase 2 — Service:** `RbacServiceImpl` (hasPrivilege + membership resolution) + `AccessControlListingManagerImpl` -> unit tests

**Phase 3 — Catalog Enforcement:** Wire `RbacService` into `CatalogImpl.validatePrivilege()` + add SELECT check in `getTable()` + EXECUTE check in UDF resolution -> integration test

**Phase 4 — DDL Handlers:** `RoleCreateHandler`, `RoleDropHandler`, `RoleGrantHandler`, `GrantHandler`, `RevokeHandler` -> integration test

**Phase 5 — System Tables:** Bind `AccessControlListingManagerImpl` in `DACDaemonModule.build()`, override `SabotContext.getAccessControlListingManager()` -> verify sys.roles returns data

**Phase 6 — REST API:** Jersey resources in `dac/backend` -> HTTP integration test

**Phase 7 — DI Wiring:** All service bindings in `DACDaemonModule.build()` + pass `Provider<RbacService>` through `CatalogServiceImpl.createCatalog()` -> end-to-end system test

---

## 7. Key Integration Points (by file)

- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` line 2767 — implement `validatePrivilege()`
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` `getTable()` line 289 — add SELECT check after DatasetManager returns
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/udf/UserDefinedFunctionCatalogImpl.java` `getFunction()` line 156 — add EXECUTE check
- `sabot/kernel/src/main/java/com/dremio/exec/server/SabotContext.java` line 554 — return real `AccessControlListingManagerImpl`
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogServiceImpl.java` `createCatalog()` line 958 — inject `RbacService` into `CatalogImpl`
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` line 1469 (pattern) — bind `RbacStore`, `RbacService`, `AccessControlListingManagerImpl`
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/SqlCreateRole.java` — add `RoleCreateHandler` class at the hardcoded class name
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/SqlGrant.java` — add `GrantHandler` class at the hardcoded class name

## 8. Constraints

- **System user bypass**: `SystemUser.isSystemUserName(username)` must short-circuit all checks. Background tasks run as system user.
- **CatalogImpl is per-request**: Inject `RbacService` as a `Provider<RbacService>` so the singleton is not re-constructed per query.
- **Coordinator-only storage**: `LegacyKVStoreProvider` is only available on coordinators; bind `RbacStore` conditionally.
- **`validatePrivilege()` is `@Deprecated`**: Acceptable for OSS naive RBAC. The deprecation reflects Enterprise Edition moving to a different model; this hook is still the correct OSS integration point.

---

*Research: 2026-02-17. Based on analysis of Dremio OSS codebase at commit 799ccbda4.*
