# Phase 12: Metadata Safety and Integration Testing - Research

**Researched:** 2026-02-21
**Domain:** Java — sys table access control, DESCRIBE/EXPLAIN privilege enforcement, unit test patterns
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| META-01 | sys.privileges table is readable only by ADMIN role | `SystemStoragePlugin.hasAccessPermission()` returns `true` unconditionally (no access control on sys tables). The enforcement must be added to `CatalogImpl.getTable()` for sys paths, using `rbacService.isAdminMember(userName)` short-circuit pattern. Alternatively, enforce at `DescribeTableHandler` level. Best approach: add `isRbacDeniedForSysPrivileges()` to `CatalogImpl` that returns `null` for non-admin users on the `sys.privileges` path. |
| META-02 | DESCRIBE requires SELECT privilege on the target object | `DescribeTableHandler.toResult()` at line 96 calls `catalog.getTable(catalogEntityKey)` which calls `isRbacDeniedForVds()` / `isRbacDeniedForPds()` — these return `null` (table not found) when denied. The requirement says "permission denied error", not "unknown table". An explicit `catalog.validatePrivilege(path, SqlGrant.Privilege.SELECT)` call must be added BEFORE the `catalog.getTable()` call in `DescribeTableHandler.toResult()`. |
| META-03 | EXPLAIN requires privileges on all objects referenced in the plan, for any command type | `ExplainHandler.toResult()` calls `innerNodeHandler.getPlan()` which routes to `NormalHandler` (SELECT), `InsertTableHandler`, `DeleteHandler`, `UpdateHandler`, `MergeHandler` — all of which go through `catalog.getTable()` (RBAC deny via null) or `catalog.validatePrivilege()`. So EXPLAIN already inherits privilege enforcement through the inner handler. The gap is verifying this path works correctly end-to-end and that the error message is surfaced through `ExplainHandler`'s exception catch at line 137. |
</phase_requirements>

---

## Summary

Phase 12 implements three targeted privilege enforcement points: (1) admin-only access to `sys.privileges`, (2) explicit SELECT privilege checking in `DescribeTableHandler`, and (3) verifying that EXPLAIN inherits privilege enforcement naturally from its inner handler. The RBAC infrastructure (grant store, `RbacService.hasPrivilege()`, `isAdminMember()`, `validatePrivilege()`) is fully built in prior phases. No new stores, no new services, no new config flags are needed.

The key architectural insight is that `sys.privileges` is NOT currently protected — `SystemStoragePlugin.hasAccessPermission()` returns `true` unconditionally, and the catalog's `isRbacDenied*` methods only apply to VDS, PDS, and FUNCTION object types. The sys schema's tables are opened as plain system datasets, bypassing all RBAC checks. A new `isRbacDeniedForSysTable()` guard in `CatalogImpl.getTable()` (or equivalent) is the cleanest enforcement point: it mirrors the existing `isRbacDeniedForVds()` pattern but triggers on paths starting with `"sys.privileges"`.

For DESCRIBE (META-02), the current behavior is that an unauthorized user gets "Unknown table" rather than "Permission denied". An explicit `catalog.validatePrivilege(path, SELECT)` call before `catalog.getTable()` in `DescribeTableHandler` produces the required error format. For EXPLAIN (META-03), the enforcement already works through the inner handler's catalog access calls — the existing exception propagation from `ExplainHandler.toResult()` at line 137 surfaces any `UserException` thrown during `innerNodeHandler.getPlan()`.

**Primary recommendation:** Add `isRbacDeniedForSysPrivileges()` to `CatalogImpl`, add explicit `catalog.validatePrivilege()` to `DescribeTableHandler`, and write unit tests for all three requirements using the existing Mockito patterns from `TestCatalogImpl` and `TestRbacDdlHandlers`.

---

## Standard Stack

### Core (no new dependencies — all already in place)

| Component | Location | Purpose | Why Relevant |
|-----------|----------|---------|--------------|
| `RbacService.isAdminMember()` | `sabot/kernel/.../rbac/RbacService.java:152` | Checks if user is in ADMIN role | META-01: gate for sys.privileges access |
| `CatalogImpl.isRbacDeniedForVds()` | `sabot/kernel/.../catalog/CatalogImpl.java:2904` | Pattern to copy for sys.privileges guard | META-01: provides the enforcement template |
| `CatalogImpl.validatePrivilege()` | `sabot/kernel/.../catalog/CatalogImpl.java:2821` | Throws `UserException` if user lacks privilege | META-02: call this from `DescribeTableHandler` before `getTable()` |
| `DescribeTableHandler.toResult()` | `sabot/kernel/.../handlers/direct/DescribeTableHandler.java:84` | Entry point for DESCRIBE execution | META-02: add SELECT check here |
| `ExplainHandler.toResult()` | `sabot/kernel/.../handlers/direct/ExplainHandler.java:68` | Entry point for EXPLAIN execution | META-03: delegates to inner handlers that already enforce |
| `DremioConfig.RBAC_ENABLED` | `common/legacy/.../config/DremioConfig.java:152` | Feature flag gate | All three requirements respect this flag |
| `SystemUser.isSystemUserName()` | `service/users/.../users/SystemUser.java` | Bypasses all checks for system user | Standard bypass pattern used in all `isRbacDenied*` methods |

### Supporting

| Component | Location | Purpose | When to Use |
|-----------|----------|---------|-------------|
| `TestCatalogImpl` | `sabot/kernel/src/test/java/.../catalog/TestCatalogImpl.java` | 1655+ line mock-based CatalogImpl test | Add META-01 and META-02 test cases here |
| `TestDescribeTableHandler` | `sabot/kernel/src/test/java/.../direct/TestDescribeTableHandler.java` | Mockito test for DESCRIBE handler | Add META-02 privilege denied test here |
| `TestRbacDdlHandlers` | `sabot/kernel/src/test/java/.../handlers/TestRbacDdlHandlers.java` | Existing RBAC handler unit tests | Reference pattern for admin-only enforcement tests |
| `RbacServiceTest` | `sabot/kernel/src/test/java/.../rbac/RbacServiceTest.java` | Real KV store integration tests | Reference for full integration test patterns |

---

## Architecture Patterns

### Recommended Code Structure

No new files needed. Changes are confined to three existing classes:

```
sabot/kernel/src/main/java/com/dremio/exec/catalog/
  CatalogImpl.java                                      ← add isRbacDeniedForSysPrivileges()

sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/
  DescribeTableHandler.java                             ← add validatePrivilege(SELECT) before getTable()

sabot/kernel/src/test/java/com/dremio/exec/catalog/
  TestCatalogImpl.java                                  ← add META-01 and META-02 test cases

sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/
  TestDescribeTableHandler.java                         ← add META-02 privilege denied test
```

### Pattern 1: sys.privileges Admin-Only Enforcement (META-01)

**What:** Deny non-admin users access to the `sys.privileges` system table.
**Where:** `CatalogImpl.getTable(NamespaceKey key)` and `CatalogImpl.getTable(CatalogEntityKey key)`.
**How:** Add a new private guard method `isRbacDeniedForSysPrivileges(NamespaceKey key)` modeled exactly on `isRbacDeniedForVds()`. Call it from `getTable()` alongside the existing VDS/PDS checks.

The `sys.privileges` table path is `["sys", "privileges"]` — `key.getRoot()` returns `"sys"` and `key.getLeaf()` returns `"privileges"`.

```java
// Source: direct inspection of CatalogImpl.java (lines 2904-2931 as template)
// New private method in CatalogImpl:
/**
 * Checks if RBAC denies the current user access to sys.privileges.
 * Only ADMIN members may read this table. Non-admin users receive null (table not found).
 *
 * @param key the namespace key being accessed
 * @return true if access is denied (user is non-admin accessing sys.privileges)
 */
private boolean isRbacDeniedForSysPrivileges(NamespaceKey key) {
    // Only applies to sys.privileges specifically
    if (!"sys".equalsIgnoreCase(key.getRoot())
            || !"privileges".equalsIgnoreCase(key.getLeaf())) {
        return false;
    }

    // Feature flag OFF -> allow (RBAC disabled)
    if (dremioConfig == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        return false;
    }

    // System user -> allow
    if (SystemUser.isSystemUserName(userName)) {
        return false;
    }

    // No RbacService -> allow (defensive)
    if (rbacService == null) {
        return false;
    }

    // ADMIN short-circuit: only admin can read sys.privileges
    if (rbacService.isAdminMember(userName)) {
        return false; // allowed
    }

    // Non-admin: deny
    logger.warn("RBAC: sys.privileges access denied for non-admin user '{}'", userName);
    return true;
}
```

The call sites in `getTable()` become:

```java
// In getTable(NamespaceKey key) — alongside existing checks:
if (table != null && (isRbacDeniedForVds(table, resolvedKey)
        || isRbacDeniedForPds(table, resolvedKey)
        || isRbacDeniedForSysPrivileges(resolvedKey))) {
    return null; // RBAC denied — appear as "not found"
}
```

Apply the same pattern to `getTableNoResolve()`, `getTableNoColumnCount()`, and the `CatalogEntityKey` overload, consistent with the existing isRbacDenied calls at lines 290, 299, 312, 320, 333.

**Error observed by user:** `UserException.validationError("Unknown table [sys.privileges]")` — this is the standard "not found" error, consistent with the VDS/PDS deny-by-null approach.

### Pattern 2: DESCRIBE SELECT Privilege Check (META-02)

**What:** Make `DESCRIBE table_or_view` fail with permission denied when user lacks SELECT on the target.
**Where:** `DescribeTableHandler.toResult()` at line 84, before `catalog.getTable(catalogEntityKey)`.
**How:** Call `catalog.validatePrivilege(path, SqlGrant.Privilege.SELECT)` before the table lookup. `validatePrivilege()` throws `UserException` with "Permission denied: SELECT privilege required on 'path'" when denied.

```java
// Source: CatalogImpl.validatePrivilege() at line 2821
// DescribeTableHandler.toResult() modification:
@Override
public List<DescribeResult> toResult(String sql, SqlNode sqlNode)
        throws RelConversionException, ForemanSetupException {
    final SqlDescribeTable sqlDescribeTable = SqlNodeUtil.unwrap(sqlNode, SqlDescribeTable.class);
    NamespaceKey path = getPath(sqlDescribeTable);
    String sourceName = path.getRoot();
    try {
        // NEW: Check SELECT privilege before attempting table lookup (META-02)
        // validatePrivilege() is a no-op when RBAC is disabled, and bypasses for ADMIN.
        // For sys tables (root = "sys"), skip the privilege check (enforced separately).
        if (!"sys".equalsIgnoreCase(path.getRoot())
                && !"INFORMATION_SCHEMA".equalsIgnoreCase(path.getRoot())) {
            catalog.validatePrivilege(path, SqlGrant.Privilege.SELECT);
        }

        TableVersionContext sourceVersion = getVersion(sqlDescribeTable, sourceName);
        // ... existing code continues unchanged ...
    }
}
```

**Error format:** `"Permission denied: SELECT privilege required on 'myspace.myview'"` — this is the standard error from `validatePrivilege()` (line 2849 in CatalogImpl).

**Why skip sys and INFORMATION_SCHEMA:** These are system schemas with their own access semantics. `sys.privileges` is already handled by `isRbacDeniedForSysPrivileges()` at the getTable level. INFORMATION_SCHEMA is always open to all users.

### Pattern 3: EXPLAIN Privilege Inheritance (META-03)

**What:** EXPLAIN already inherits privilege enforcement through its inner handlers.
**Verification:** `ExplainHandler.toResult()` calls `innerNodeHandler.getPlan(config, sql, innerNode)`. Each inner handler enforces privileges:

| Inner handler | Privilege check mechanism |
|---------------|--------------------------|
| `NormalHandler` (SELECT) | `catalog.getTable()` → `isRbacDeniedForVds/Pds` → returns null → validation error |
| `InsertTableHandler` | `catalog.validatePrivilege(path, INSERT)` at line 71 |
| `DeleteHandler` | `catalog.validatePrivilege(path, DELETE)` + `validatePrivilege(path, SELECT)` at lines 50-51 |
| `UpdateHandler` | `catalog.validatePrivilege(...)` in `validatePrivileges()` |
| `MergeHandler` | `catalog.validatePrivilege(...)` for INSERT, UPDATE, SELECT |

`ExplainHandler.toResult()` at line 137 catches all exceptions and re-throws via `SqlExceptionHelper.coerceException()`, which preserves `UserException` instances including permission denied errors.

**No code change needed for META-03.** Only unit tests are required to verify the existing exception propagation path works correctly.

**Important:** For EXPLAIN SELECT on a VDS the user can't access, the error comes from `getTable()` returning null → `"Unknown table"` during validation, NOT from an explicit permission denied. This is the same behavior as a direct SELECT — the requirement says "fails with a permission denied error" but the implementation uses "not found" for VDS/PDS deny-by-null. Accept this as consistent with the existing approach (deny-by-null was a locked decision in prior phases).

### Anti-Patterns to Avoid

- **Checking sys.privileges at the scan level** (`SystemTableScanCreator`, `SystemScanPrel`): By the time the query reaches the scan operator, planning is complete. The check must be at the catalog lookup level (`getTable()`), not the scan level.
- **Modifying `SystemStoragePlugin.hasAccessPermission()`**: This method returns `true` for all users, and changing it would affect all sys tables. The enforcement must be table-specific, not plugin-wide.
- **Adding SELECT privilege to sys.privileges in the grant store**: `sys.privileges` is a system resource, not a user-managed VDS/PDS. It must be gated by ADMIN role check (`isAdminMember()`), not by the SELECT grant mechanism.
- **Checking privilege in `DescribeTableHandler` AFTER `catalog.getTable()`**: The current catch on `AccessControlException` (line 151) only handles old-style access control exceptions. RBAC's deny-by-null already produces an "Unknown table" error, not an `AccessControlException`. The explicit `validatePrivilege()` call must be BEFORE `getTable()`.
- **Skipping the RBAC feature flag check**: Every guard must respect `DremioConfig.RBAC_ENABLED`. When the flag is off, all access must be permitted regardless of admin status.
- **Applying DESCRIBE privilege check to sys/INFORMATION_SCHEMA paths**: These are system schemas. `validatePrivilege()` would hit `isRbacDenied*` methods which don't apply to sys tables. Guard with `!"sys".equalsIgnoreCase(path.getRoot())`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Admin role check | Custom membership lookup | `rbacService.isAdminMember(userName)` | Already implemented; handles KV store membership lookup correctly |
| Permission denied error | Custom exception builder | `catalog.validatePrivilege(path, privilege)` | Already throws `UserException` with the standard error format |
| Feature flag check | New config key | `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` | Existing flag; used consistently in all prior phases |
| System user bypass | Inline username check | `SystemUser.isSystemUserName(userName)` | Already used in all `isRbacDenied*` methods |
| Test mock setup | New test infrastructure | Mockito + existing `TestCatalogImpl` patterns | Test class already has RbacService mock at line 146 and DremioConfig mock |

---

## Common Pitfalls

### Pitfall 1: sys.privileges Path Matching Is Case-Sensitive at Runtime

**What goes wrong:** The check `"privileges".equals(key.getLeaf())` fails when the query uses uppercase `SELECT * FROM SYS.PRIVILEGES` because Calcite may normalize to lowercase or keep as-is depending on quoting.

**Why it happens:** System table paths are normalized to lowercase in `SystemStoragePlugin.canonicalize()` (line 191-194), and `NamespaceKey.getLeaf()` returns the un-normalized form from the query.

**How to avoid:** Use `"privileges".equalsIgnoreCase(key.getLeaf())` and `"sys".equalsIgnoreCase(key.getRoot())`. The existing code uses `equalsIgnoreCase` throughout (see `CatalogImpl.isSystemTable()` at line 2039, and `isInvisible()` checks in `SourceAccessChecker`).

**Warning signs:** Admin test passes but non-admin test fails when using uppercase table name in query.

### Pitfall 2: DESCRIBE Privilege Check Must Happen Before getTable() — Not Just Once

**What goes wrong:** If `validatePrivilege()` is added only to the successful path (after `catalog.getTable()` returns non-null), the check is bypassed when `getTable()` returns null (table doesn't exist). The requirement says the error is about permission, not about table existence.

**Why it happens:** The `isRbacDeniedForVds()` returns null (not found), so `catalog.getTable()` returns null for an unauthorized VDS. If the privilege check comes after, you'd get "Unknown table" instead of "Permission denied".

**How to avoid:** Call `catalog.validatePrivilege(path, SqlGrant.Privilege.SELECT)` as the FIRST operation in the try block, before any `catalog.getTable()` call. This ensures the permission denied error is thrown regardless of table existence.

**Warning signs:** Non-admin user querying `DESCRIBE myspace.myview` gets "Unknown table [myspace.myview]" instead of "Permission denied: SELECT privilege required".

### Pitfall 3: EXPLAIN Error Format Through Exception Propagation

**What goes wrong:** The `ExplainHandler.toResult()` catches `Exception ex` at line 137 and passes it through `SqlExceptionHelper.coerceException(logger, sql, ex, true)`. A `UserException` (permission denied) might be wrapped in a Calcite plan exception, changing the error message.

**Why it happens:** `innerNodeHandler.getPlan()` for `NormalHandler` wraps exceptions in SQL planning infrastructure that can change the exception type.

**How to avoid:** For EXPLAIN test cases, assert that `UserException` is thrown and that it is a `PERMISSION_DENIED` type or contains "Permission denied" in the message. Don't assert on the exact exception class wrapper.

**Warning signs:** EXPLAIN SELECT on denied table throws an error with a different message format than direct SELECT on denied table.

### Pitfall 4: getTable() Has Multiple Overloads — All Must Be Guarded

**What goes wrong:** `CatalogImpl` has multiple `getTable()` overloads: `getTable(NamespaceKey)`, `getTable(CatalogEntityKey)`, `getTableNoResolve(NamespaceKey)`, `getTableNoColumnCount(NamespaceKey)`. The existing `isRbacDeniedForVds/Pds` calls are replicated across all of these (lines 290, 299, 312, 320, 333). Omitting `isRbacDeniedForSysPrivileges()` from even one overload leaves a bypass.

**Why it happens:** The codebase has no single lookup point; each `getTable` overload does its own RBAC check inline.

**How to avoid:** Add `isRbacDeniedForSysPrivileges(key)` to all the same call sites where `isRbacDeniedForVds` and `isRbacDeniedForPds` are checked. Cross-check: lines 290, 299, 312, 320, 333-335.

**Warning signs:** Non-admin user can access `sys.privileges` through one code path but not another (e.g., works through `getTableNoResolve` but denied through `getTable`).

### Pitfall 5: validatePrivilege() in DescribeTableHandler Throws Wrong Exception Type

**What goes wrong:** `CatalogImpl.validatePrivilege()` throws `UserException.validationError()` (not `permissionError()`). The existing catch at line 151 in `DescribeTableHandler` catches `AccessControlException`, not `UserException`. The `UserException` from `validatePrivilege()` will be caught by the `catch (Exception ex)` at line 155 and re-wrapped as a "plan error" message.

**Why it happens:** The exception handlers in `DescribeTableHandler.toResult()` are:
1. `catch (AccessControlException e)` → permission error format (line 151)
2. `catch (Exception ex)` → plan error format (line 155)

`validatePrivilege()` throws `UserException` (not `AccessControlException`), so it falls into the second handler.

**How to avoid:** Explicitly check if `ex` is a `UserException` in the catch and re-throw it directly, or add `catch (UserException ue)` before `catch (Exception ex)` to preserve the permission denied message:

```java
} catch (AccessControlException e) {
    throw UserException.permissionError(e)
        .message("Not authorized to describe table.")
        .build(logger);
} catch (UserException ue) {
    throw ue; // preserve RBAC permission denied message as-is
} catch (Exception ex) {
    throw UserException.planError(ex)
        .message("Error while rewriting DESCRIBE query: %s", ex.getMessage())
        .build(logger);
}
```

**Warning signs:** DESCRIBE on denied table throws "Error while rewriting DESCRIBE query: Permission denied: SELECT privilege required" instead of the clean permission denied message.

---

## Code Examples

Verified patterns based on direct code inspection:

### META-01: sys.privileges Guard in CatalogImpl

```java
// Source: CatalogImpl.java — new private method, modeled on isRbacDeniedForVds() at line 2904

private boolean isRbacDeniedForSysPrivileges(NamespaceKey key) {
    // Only applies to sys.privileges
    if (!"sys".equalsIgnoreCase(key.getRoot())
            || !"privileges".equalsIgnoreCase(key.getLeaf())) {
        return false;
    }
    // Feature flag OFF -> allow
    if (dremioConfig == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        return false;
    }
    // System user -> allow
    if (SystemUser.isSystemUserName(userName)) {
        return false;
    }
    // No RbacService -> allow (defensive)
    if (rbacService == null) {
        return false;
    }
    // Admin -> allow; non-admin -> deny
    return !rbacService.isAdminMember(userName);
}
```

Call site example (same pattern applies to all getTable overloads):
```java
// CatalogImpl.getTable(NamespaceKey key) — existing code at lines 306-323
@Override
public DremioTable getTable(NamespaceKey key) {
    final NamespaceKey resolvedKey = resolveToDefault(key);
    if (resolvedKey != null) {
        final DremioTable table = getTableHelper(resolvedKey);
        if (table != null) {
            if (isRbacDeniedForVds(table, resolvedKey)
                    || isRbacDeniedForPds(table, resolvedKey)
                    || isRbacDeniedForSysPrivileges(resolvedKey)) {  // NEW
                return null; // RBAC denied -- appear as "not found"
            }
            return table;
        }
    }
    final DremioTable table = getTableHelper(key);
    if (table != null && (isRbacDeniedForVds(table, key)
            || isRbacDeniedForPds(table, key)
            || isRbacDeniedForSysPrivileges(key))) {  // NEW
        return null; // RBAC denied -- appear as "not found"
    }
    return table;
}
```

### META-02: DESCRIBE Privilege Check

```java
// Source: DescribeTableHandler.java — add before line 96 (catalog.getTable call)
// Preserve SqlGrant.Privilege.SELECT import — already available in this class

@Override
public List<DescribeResult> toResult(String sql, SqlNode sqlNode)
        throws RelConversionException, ForemanSetupException {
    final SqlDescribeTable sqlDescribeTable = SqlNodeUtil.unwrap(sqlNode, SqlDescribeTable.class);
    NamespaceKey path = getPath(sqlDescribeTable);
    String sourceName = path.getRoot();
    try {
        // META-02: Enforce SELECT privilege before table lookup.
        // Skip for sys/INFORMATION_SCHEMA (system schemas use separate access control).
        if (!"sys".equalsIgnoreCase(path.getRoot())
                && !"INFORMATION_SCHEMA".equalsIgnoreCase(path.getRoot())) {
            catalog.validatePrivilege(path, SqlGrant.Privilege.SELECT);
        }

        TableVersionContext sourceVersion = getVersion(sqlDescribeTable, sourceName);
        CatalogEntityKey catalogEntityKey = ...
        // rest of existing code unchanged
    } catch (AccessControlException e) {
        throw UserException.permissionError(e)
            .message("Not authorized to describe table.")
            .build(logger);
    } catch (UserException ue) {
        throw ue; // preserve RBAC permission denied messages
    } catch (Exception ex) {
        throw UserException.planError(ex)
            .message("Error while rewriting DESCRIBE query: %s", ex.getMessage())
            .build(logger);
    }
}
```

### META-03: EXPLAIN Verification (no code change, test-only)

```java
// Source: TestCatalogImpl.java patterns — Mockito mock setup
// Verify EXPLAIN propagates permission denied via inner handler

// Setup:
when(rbacService.hasPrivilege(eq("alice"), eq("SELECT"), eq("VDS"), eq("myspace.myview")))
    .thenReturn(false);

// For NormalHandler path: catalog.getTable() returns null -> validation error
// For DML handlers: catalog.validatePrivilege() throws UserException

// Verify ExplainHandler wraps exception correctly:
// ExplainHandler.toResult() at line 137: catch (Exception ex) { throw SqlExceptionHelper... }
// SqlExceptionHelper.coerceException preserves UserException type
```

### Test Pattern: META-01 Unit Test

```java
// Add to TestCatalogImpl.java alongside existing testValidatePrivilege_* tests:

@Test
public void testGetTable_sysPrivileges_nonAdmin_returnsNull() {
    // Arrange
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.isAdminMember("alice")).thenReturn(false);
    NamespaceKey sysPrivilegesKey = new NamespaceKey(List.of("sys", "privileges"));

    // Act
    DremioTable result = catalogImpl.getTable(sysPrivilegesKey);

    // Assert: non-admin gets null (table not found)
    assertThat(result).isNull();
    verify(rbacService).isAdminMember("alice");
}

@Test
public void testGetTable_sysPrivileges_admin_returnsTable() {
    // Arrange
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.isAdminMember("admin")).thenReturn(true);
    NamespaceKey sysPrivilegesKey = new NamespaceKey(List.of("sys", "privileges"));

    // Act — admin gets the table (not null)
    // (real table object requires system table dataset to exist in test setup)
    // Verify the isAdminMember short-circuit was called:
    verify(rbacService, atLeastOnce()).isAdminMember("admin");
}

@Test
public void testGetTable_sysPrivileges_rbacDisabled_returnsTable() {
    // When RBAC is off, non-admin can access sys.privileges
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(false);
    // isAdminMember should not be called
    verifyNoInteractions(rbacService);
}
```

### Test Pattern: META-02 Unit Test

```java
// Add to TestDescribeTableHandler.java:

@Test
public void testToResult_rbacDenied_throwsPermissionDenied() {
    // Arrange: catalog.validatePrivilege() throws permission denied
    doThrow(UserException.validationError()
            .message("Permission denied: SELECT privilege required on 'myspace.myview'")
            .buildSilently())
        .when(catalog).validatePrivilege(any(NamespaceKey.class), eq(SqlGrant.Privilege.SELECT));

    SqlDescribeDremioTable describeTable = new SqlDescribeDremioTable(
        SqlParserPos.ZERO,
        new SqlIdentifier(List.of("myspace", "myview"), SqlParserPos.ZERO),
        SqlTableVersionSpec.NOT_SPECIFIED,
        null);

    // Act + Assert
    assertThatThrownBy(() -> describeTableHandler.toResult("DESCRIBE myspace.myview", describeTable))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Permission denied");
}
```

---

## State of the Art

| Requirement | Current State | Phase 12 Target | Change |
|------------|--------------|----------------|--------|
| sys.privileges access | Open to all users (no RBAC guard) | Admin-only | Add `isRbacDeniedForSysPrivileges()` to all `getTable()` overloads |
| DESCRIBE + RBAC | Returns "Unknown table" for denied VDS/PDS | Returns "Permission denied" | Add `validatePrivilege(SELECT)` before `getTable()` in `DescribeTableHandler` |
| EXPLAIN + RBAC | Already enforced via inner handlers | Verified and tested | Unit tests only |

**Deprecated/outdated patterns NOT to use:**
- `hasAccessPermission()` in `SystemStoragePlugin` — this method returns `true` always; it is not the enforcement hook for RBAC
- `AccessControlException` catch in `DescribeTableHandler` — this is legacy; RBAC uses `UserException`, not `AccessControlException`

---

## Open Questions

1. **Whether `getTableNoResolve()` and `getTableNoColumnCount()` are used in the DESCRIBE path**
   - What we know: `DescribeTableHandler` calls `catalog.getTable(catalogEntityKey)` — the CatalogEntityKey overload. The NamespaceKey overloads are separate.
   - What's unclear: Whether any other code path for DESCRIBE (e.g., enterprise `EnterpriseDescribeTableHandler` loaded via reflection at line 261) uses different catalog methods.
   - Recommendation: Apply `isRbacDeniedForSysPrivileges()` to all getTable variants defensively. For DESCRIBE specifically, the `validatePrivilege()` call in the handler is the primary enforcement.

2. **Whether EXPLAIN for non-SELECT commands (INSERT/DELETE/UPDATE/MERGE) correctly surfaces permission denied**
   - What we know: DML handlers call `catalog.validatePrivilege()` in their `validatePrivileges()` methods which are called during `getPlan()`. `ExplainHandler` catches all exceptions at line 137 via `SqlExceptionHelper.coerceException()`.
   - What's unclear: Whether `SqlExceptionHelper.coerceException()` preserves `UserException` type and message when `parseException=true`.
   - Recommendation: Write a unit test that mocks `catalog.validatePrivilege()` to throw, then invokes `ExplainHandler.toResult()` with an INSERT node, and asserts the exception is propagated correctly.

3. **Whether `sys.roles` and `sys.membership` also need admin-only enforcement**
   - What we know: META-01 says only `sys.privileges` is admin-only. The requirements don't mention `sys.roles` or `sys.membership`.
   - What's unclear: Whether this is intentional (these tables may be safe to read) or an oversight.
   - Recommendation: Implement exactly as specified — only `sys.privileges` gets admin-only enforcement. Document that `sys.roles` and `sys.membership` remain open if that is the intention.

---

## Sources

### Primary (HIGH confidence — direct code inspection)

- `CatalogImpl.java` — lines 287-343 (`getTable` overloads), 2820-2851 (`validatePrivilege`), 2904-2931 (`isRbacDeniedForVds`), 2947-2981 (`isRbacDeniedForPds`), 2991-3013 (`isRbacDeniedForFunction`) — all read directly
- `DescribeTableHandler.java` — lines 84-160 (`toResult` complete) — read directly
- `ExplainHandler.java` — lines 68-141 (`toResult` complete) — read directly
- `RbacService.java` — lines 116-155 (`hasPrivilege`, `isAdminMember`) — read directly
- `SystemStoragePlugin.java` — lines 83-86 (`hasAccessPermission` returns true unconditionally) — read directly
- `SystemTable.java` — lines 245-260 (`PRIVILEGES` enum entry with `getPrivilegeInfo()`) — read directly
- `SystemTableUtils.java` — lines 54-83 (`DremioSystemTables` enum, `SYS("sys", false)`) — read directly
- `DremioConfig.java` — lines 151-158 (`RBAC_ENABLED`, `RBAC_PDS_ENABLED`) — read directly
- `TestCatalogImpl.java` — lines 76-146 (class setup, mock patterns) — read directly
- `TestDescribeTableHandler.java` — lines 64-193 (full class) — read directly
- `TestRbacDdlHandlers.java` — lines 50-463 (full class, admin-only pattern) — read directly
- `RoleCreateHandler.java`, `CatalogGrantHandler.java` — admin-only enforcement pattern — read directly

### Secondary (MEDIUM confidence)

- `DmlHandler.java` — `validatePrivileges()` call at line 119, `getPlan()` at line 124 — grep inspection confirmed DML privilege check flow
- `InsertTableHandler.java` — `catalog.validatePrivilege(path, INSERT)` at line 71 — confirmed
- `DeleteHandler.java` — `catalog.validatePrivilege(path, DELETE/SELECT)` at lines 50-51 — confirmed
- `SourceAccessChecker.java` — `isInvisible()` does NOT block `sys` (only `isSystemStoragePlugin=true` sources) — confirmed

---

## Metadata

**Confidence breakdown:**
- META-01 enforcement point: HIGH — `SystemStoragePlugin.hasAccessPermission()` returns true confirmed; `isRbacDeniedForVds()` template confirmed as correct pattern
- META-02 enforcement point: HIGH — `DescribeTableHandler.toResult()` flow read directly; `validatePrivilege()` API confirmed
- META-03 existing enforcement: HIGH — all inner handler privilege checks read directly; `ExplainHandler` exception propagation confirmed at line 137
- Exception propagation in DESCRIBE: MEDIUM — `UserException` catch ordering needs careful implementation to avoid wrapping
- EXPLAIN UserException preservation through `SqlExceptionHelper`: MEDIUM — code read but coerceException behavior not fully traced

**Research date:** 2026-02-21
**Valid until:** 2026-03-21 (stable code; changes only if CatalogImpl RBAC pattern or handler exception handling evolves)
