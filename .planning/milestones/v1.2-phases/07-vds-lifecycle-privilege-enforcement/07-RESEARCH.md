# Phase 7: VDS Lifecycle Privilege Enforcement - Research

**Researched:** 2026-02-20
**Domain:** Java — CatalogImpl privilege enforcement, SQL DDL handlers, RBAC grant store
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Error messaging
- Errors MUST name the missing privilege and include the full view path
- Format: "Permission denied: {PRIVILEGE} privilege required on '{full.path.to.view}'"
- All three operations (ALTER VIEW, DROP VIEW, CREATE VIEW) use the same error format
- Whether to include a GRANT hint in the error message is Claude's discretion (check existing Dremio error patterns)

#### Rollout behavior
- Strict deny-by-default from day 1 — no log-only mode, no bootstrap grants, no kill switch
- Non-admin users without ALTER/DROP/CREATE_VIEW grants are immediately blocked when enforcement goes live
- Enforcement is always on when RBAC is enabled — no separate config flag for this feature
- SELECT grants still control visibility — users with SELECT can see views they can't modify
- CREATE_VIEW also requires an explicit grant (same strictness as ALTER/DROP)

#### CREATE_VIEW gap closure
- Enforce CREATE_VIEW at ALL code paths — SQL DDL, REST API, and any internal creation path
- No auto-granting of privileges to view creators — admin must explicitly grant everything (matches existing Dremio model)
- CREATE_VIEW is a **container-scoped** privilege: grant on a space or folder covers creating any view under that container
- Syntax: `GRANT CREATE_VIEW ON VDS "my_space" TO ROLE dev` — grants ability to create views anywhere under my_space
- Enforcement checks the parent container path of the view being created

#### Privilege naming & GRANT syntax
- Privilege names match the SqlGrant.Privilege enum exactly: 'ALTER', 'DROP', 'CREATE_VIEW'
- GRANT/REVOKE syntax follows the same `ON VDS` pattern as SELECT: `GRANT ALTER ON VDS space.my_view TO ROLE role`
- ALTER and DROP are **per-view only** (not container-scoped like CREATE_VIEW)
- One privilege per GRANT statement — no multi-privilege syntax
- Stored as plain strings in the grant store, consistent with v1.0

### Claude's Discretion
- Whether to include GRANT hint in error messages (check existing Dremio error patterns)
- Exact placement of validatePrivilege() calls in the code paths (technical implementation)
- How to resolve parent container path for CREATE_VIEW checks (implementation detail)
- Test structure and coverage approach

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| LIFE-01 | User can only ALTER (update) a VDS if they have ALTER privilege on it | `CreateOrUpdateViewHandler.toResult()` calls `validatePrivilege(..., CREATE_VIEW)` unconditionally — the update branch (CREATE OR REPLACE VIEW) must additionally validate ALTER. `DropViewHandler.toResult()` currently validates ALTER incorrectly for DROP (see findings). |
| LIFE-02 | User can only DROP a VDS if they have DROP privilege on it | `DropViewHandler.toResult()` at line 55 currently calls `validatePrivilege(path, SqlGrant.Privilege.ALTER)` — this must be changed to `SqlGrant.Privilege.DROP`. The `resolveRbacObjectType()` switch in `CatalogImpl` must gain a `DROP` case returning `"VDS"`. |
| LIFE-03 | User can only CREATE a VDS if they have CREATE_VIEW privilege (closes v1.0 enforcement gap) | `CreateOrUpdateViewHandler.toResult()` already has `validatePrivilege(..., CREATE_VIEW)` at line 105 (SQL DDL path). The REST API path in `CatalogServiceHelper.createView()` at line 1297 calls `viewCreatorFactory.get(...).createView()` directly without any `validatePrivilege()` call — this gap must be closed. The container-scoped check uses `key.getParent().getSchemaPath()` as objectPath. |
</phase_requirements>

---

## Summary

Phase 7 closes enforcement gaps in view lifecycle operations. The v1.0 codebase already contains the full RBAC infrastructure (`RbacService`, `GrantStore`, `validatePrivilege()` in `CatalogImpl`, `CatalogGrantHandler`, `CatalogRevokeHandler`) and the `SqlGrant.Privilege` enum already contains all three privilege names needed: `ALTER`, `DROP`, `CREATE_VIEW`. The work is surgical: three bug-fixes at call sites, plus a new error message format, plus test coverage.

The most critical finding is that the current code has TWO bugs in privilege naming, not just one gap. (1) `DropViewHandler` validates `ALTER` when it should validate `DROP`. (2) `CreateOrUpdateViewHandler` validates `CREATE_VIEW` for both the create and the update (CREATE OR REPLACE) paths — the update path must validate `ALTER` instead. The REST API path (`CatalogServiceHelper`) is the "v1.0 enforcement gap" for CREATE_VIEW: it bypasses `validatePrivilege()` entirely by calling `viewCreatorFactory.get(...).createView()` directly.

The container-scoped CREATE_VIEW check is the trickiest implementation detail. For a view being created at path `["myspace", "myfolder", "myview"]`, the privilege check must use `key.getParent().getSchemaPath()` (i.e., `"myspace.myfolder"`) not the full view path. This is consistent with the `NamespaceKey.getParent()` method which returns a key from `pathComponents[0..n-2]`.

**Primary recommendation:** Make three targeted call-site changes (DropViewHandler, CreateOrUpdateViewHandler update branch, CatalogServiceHelper createView), update the error message in `validatePrivilege()`, and add DROP to `resolveRbacObjectType()`. No new infrastructure is needed — the existing RBAC stack is complete.

---

## Standard Stack

### Core (no new dependencies — everything already exists)
| Component | Location | Purpose | Why Relevant |
|-----------|----------|---------|-------------|
| `CatalogImpl.validatePrivilege()` | `sabot/kernel/.../catalog/CatalogImpl.java:2816` | Central privilege enforcement gate | All three call sites delegate here; error message format lives here |
| `CatalogImpl.resolveRbacObjectType()` | `sabot/kernel/.../catalog/CatalogImpl.java:2848` | Maps privilege to RBAC object type string | Must add `DROP` case to return `"VDS"` |
| `SqlGrant.Privilege` enum | `sabot/kernel/.../sql/parser/SqlGrant.java:48` | Canonical privilege name constants | `ALTER`, `DROP`, `CREATE_VIEW` all present; use `.name()` for string |
| `DropViewHandler` | `sabot/kernel/.../handlers/direct/DropViewHandler.java:55` | SQL DDL `DROP VIEW` call site | Currently validates wrong privilege (`ALTER` instead of `DROP`) |
| `CreateOrUpdateViewHandler` | `sabot/kernel/.../handlers/direct/CreateOrUpdateViewHandler.java:105` | SQL DDL `CREATE VIEW` / `CREATE OR REPLACE VIEW` call site | CREATE validates `CREATE_VIEW` (correct); update path must validate `ALTER` |
| `CatalogServiceHelper` | `dac/backend/.../service/catalog/CatalogServiceHelper.java:1297` | REST API view creation path | Calls `viewCreatorFactory.get(...).createView()` with no `validatePrivilege()` call |
| `CatalogGrantHandler` / `CatalogRevokeHandler` | `sabot/kernel/.../handlers/` | GRANT/REVOKE DDL handlers | Already generic — accept any `SqlGrant.Privilege`; no changes needed |
| `RbacService.hasPrivilege()` | `sabot/kernel/.../rbac/RbacService.java:115` | Core privilege check with ADMIN short-circuit | Admin bypass already implemented; no changes needed |
| `NamespaceKey.getParent()` | `services/namespace/.../NamespaceKey.java:127` | Returns parent container key | Used to derive container path for container-scoped CREATE_VIEW check |

---

## Architecture Patterns

### Pattern 1: validatePrivilege() call site pattern (existing, HIGH confidence)

Every privilege enforcement point calls `catalog.validatePrivilege(key, SqlGrant.Privilege.PRIVILEGE)` before the operation. The method is a no-op when RBAC is disabled, throws `UserException.validationError()` when denied, and passes silently when granted.

```java
// Existing pattern in DropViewHandler.toResult() (line 55):
catalog.validatePrivilege(path, SqlGrant.Privilege.ALTER);  // BUG: should be DROP

// Existing pattern in CreateOrUpdateViewHandler.toResult() (line 105):
catalog.validatePrivilege(resolvedViewPath, SqlGrant.Privilege.CREATE_VIEW);
// BUG: missing ALTER check for update (CREATE OR REPLACE) path
```

### Pattern 2: Current error message format (v1.0 — needs update)

The current `validatePrivilege()` throws this message on denial:
```java
// CatalogImpl.java:2839 — current (to be updated):
throw UserException.validationError().message("Table '%s' not found", key).buildSilently();
```

The locked decision requires the new format:
```
"Permission denied: {PRIVILEGE} privilege required on '{full.path.to.view}'"
```

The new throw statement in `validatePrivilege()`:
```java
throw UserException.validationError()
    .message(
        "Permission denied: %s privilege required on '%s'",
        rbacPrivilege, objectPath)
    .buildSilently();
```

Note: Existing test `testValidatePrivilege_noGrant_throwsNotFound` and similar tests in `TestCatalogImpl` assert `hasMessageContaining("not found")` — these tests must be updated to match the new error format.

### Pattern 3: resolveRbacObjectType() switch (existing, needs DROP)

```java
// CatalogImpl.java:2848 — current:
private String resolveRbacObjectType(NamespaceKey key, SqlGrant.Privilege privilege) {
    switch (privilege) {
      case EXECUTE:
        return "FUNCTION";
      case CREATE_VIEW:
      case SELECT:
      case ALTER:
      default:
        return "VDS";
    }
}

// After Phase 7: add DROP to the VDS group
private String resolveRbacObjectType(NamespaceKey key, SqlGrant.Privilege privilege) {
    switch (privilege) {
      case EXECUTE:
        return "FUNCTION";
      case CREATE_VIEW:
      case SELECT:
      case ALTER:
      case DROP:
      default:
        return "VDS";
    }
}
```

### Pattern 4: Container-scoped CREATE_VIEW check

For CREATE_VIEW, the CONTEXT.md specifies: the privilege is granted on a **container** (space or folder), and enforcement checks the **parent container path**, not the view path itself.

Given view path `["my_space", "my_folder", "my_view"]`:
- `key.getSchemaPath()` = `"my_space.my_folder.my_view"` (the view — wrong for CREATE_VIEW)
- `key.getParent().getSchemaPath()` = `"my_space.my_folder"` (the container — correct for CREATE_VIEW)

The SQL DDL path in `CreateOrUpdateViewHandler.toResult()` already calls:
```java
catalog.validatePrivilege(resolvedViewPath, SqlGrant.Privilege.CREATE_VIEW);
```

Since `validatePrivilege()` calls `key.getSchemaPath()` for objectPath, and the existing v1.0 implementation stores CREATE_VIEW grants as `roleId|VDS|my_space.myview|CREATE_VIEW`, the container-scope semantics require that `validatePrivilege()` passes the **parent** path for CREATE_VIEW.

There are two implementation options:
1. **Option A** (recommended): Add a separate `validateCreateViewPrivilege(NamespaceKey viewKey)` method that calls `rbacService.hasPrivilege(..., "CREATE_VIEW", "VDS", viewKey.getParent().getSchemaPath())`. This keeps the existing `validatePrivilege()` signature clean.
2. **Option B**: Override `resolveRbacObjectPath()` to return the parent path when privilege is CREATE_VIEW, but this makes `validatePrivilege()` semantically surprising since the key passed in and the path checked would differ.

Option A is preferred as it is explicit. It also allows the calling code to be clear about intent.

### Pattern 5: REST API CREATE_VIEW enforcement (CatalogServiceHelper)

The REST API path calls `viewCreatorFactory.get(securityContext.getUserPrincipal().getName()).createView(...)` at line 1297 without any privilege check. The `catalogSupplier.get()` is available in `CatalogServiceHelper` — the fix is to add a `validatePrivilege` call on the catalog before the factory call:

```java
// CatalogServiceHelper.java — before the createView call:
final NamespaceKey namespaceKey = new NamespaceKey(dataset.getPath());
catalogSupplier.get().validateCreateViewPrivilege(namespaceKey);
// then call viewCreatorFactory...
```

### Pattern 6: ALTER VIEW call site in CreateOrUpdateViewHandler

For `CREATE OR REPLACE VIEW` (the update path), `isUpdate` is true after `checkViewExistence()`. The `validatePrivilege(CREATE_VIEW)` fires for all paths before this branch. The fix is to add an additional ALTER check specifically for the update path:

```java
// In toResult(), after the existing CREATE_VIEW check:
catalog.validatePrivilege(resolvedViewPath, SqlGrant.Privilege.CREATE_VIEW);
// ... then in createView() private method, after isUpdate is determined:
if (isUpdate) {
    catalog.validatePrivilege(viewPath, SqlGrant.Privilege.ALTER);
}
```

However, the CONTEXT.md states the intent: "ALTER VIEW" should require ALTER privilege. The cleanest approach for the non-versioned path is to check ALTER **instead of** CREATE_VIEW for the update path. But since `validatePrivilege(CREATE_VIEW)` fires first (line 105) before we know `isUpdate`, the most practical fix is:
- Keep `CREATE_VIEW` check at line 105 (it fires for all CREATE VIEW statements)
- For the update path (CREATE OR REPLACE), also validate ALTER in the `createView()` private method

Note: For the versioned path (`createVersionedView()`), `isUpdate` is determined at line 147. The ALTER check should be placed after line 147 in the versioned path as well.

### Anti-Patterns to Avoid
- **Checking the wrong key for CREATE_VIEW**: Using `key.getSchemaPath()` instead of `key.getParent().getSchemaPath()` for CREATE_VIEW grants stores the wrong object path in the grant record and the check will never match grants stored on containers.
- **Forgetting the REST API path**: CatalogServiceHelper bypasses the SQL handler — it must have its own validatePrivilege call.
- **Missing the versioned view update path**: `createVersionedView()` is a separate code path from `createView()` — both need the ALTER check for the update branch.
- **Not updating existing tests**: `TestCatalogImpl` has tests asserting the old `"not found"` error message. These break when the error format changes.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Grant persistence | Custom store | Existing `GrantStore` + `RbacConfig.grantKey()` | Already implemented; stores as `roleId\|VDS\|path\|privilege` |
| GRANT DDL parsing | New SQL syntax | Existing `SqlGrantOnCatalog` + `CatalogGrantHandler` | Already generic; accepts any Privilege enum value; no parser changes needed |
| Admin bypass | Manual check | Existing `RbacService.hasPrivilege()` short-circuit | ADMIN check at step 1 of `hasPrivilege()` already implemented |
| RBAC feature toggle | New config flag | Existing `DremioConfig.RBAC_ENABLED` check in `validatePrivilege()` | Already gates all enforcement |

---

## Common Pitfalls

### Pitfall 1: Wrong error message in existing tests

**What goes wrong:** The current `validatePrivilege()` error is `"Table '%s' not found"`. Nine existing tests in `TestCatalogImpl` assert `hasMessageContaining("not found")`. If the error message changes to the mandated format (`"Permission denied: %s privilege required on '%s'"`), all those assertions fail.

**Why it happens:** Tests were written to match v1.0 behavior and will not automatically update.

**How to avoid:** When updating the error message in `validatePrivilege()`, update all nine matching tests in `TestCatalogImpl` to assert the new format.

**Warning signs:** Test failures in `TestCatalogImpl.testValidatePrivilege_*_throwsNotFound` and `testGetTable_vdsDenied_returnsNotFound`.

### Pitfall 2: DropViewHandler validates ALTER — not DROP

**What goes wrong:** `DropViewHandler.toResult()` line 55 currently calls `validatePrivilege(path, SqlGrant.Privilege.ALTER)`. This means DROP VIEW is blocked by lack of ALTER grant, not lack of DROP grant. A user with DROP but not ALTER could never drop a view.

**Why it happens:** The original implementation reused ALTER as a catch-all lifecycle privilege.

**How to avoid:** Change line 55 to `SqlGrant.Privilege.DROP`. Also add `case DROP:` to `resolveRbacObjectType()` so DROP maps to `"VDS"`.

### Pitfall 3: CREATE OR REPLACE VIEW validates only CREATE_VIEW, not ALTER

**What goes wrong:** `CreateOrUpdateViewHandler.toResult()` calls `validatePrivilege(..., CREATE_VIEW)` once for all paths, before the `isUpdate` flag is determined. When the view already exists (`CREATE OR REPLACE VIEW`), the update path must require ALTER, but the CREATE_VIEW check passes regardless.

**Why it happens:** The privilege check was placed before the existence check — appropriate for create, inappropriate for replace.

**How to avoid:** Add a separate ALTER check in both `createView()` (non-versioned) and `createVersionedView()` (versioned) private methods, after `isUpdate` is known to be `true`.

### Pitfall 4: REST API skips validatePrivilege entirely

**What goes wrong:** `CatalogServiceHelper.createDataset()` (the path that creates VDS) calls `viewCreatorFactory.get(userName).createView(...)` directly without consulting `validatePrivilege()`. The SQL DDL path is protected but the API path is not.

**Why it happens:** `CatalogServiceHelper` has access to a `catalogSupplier` (the `Catalog`) but historically did not perform RBAC checks.

**How to avoid:** Add `catalogSupplier.get().validateCreateViewPrivilege(namespaceKey)` (or equivalent) before the `viewCreatorFactory.get(...).createView(...)` call at line 1297 and before the `updateView()` calls at lines 1470 and 1693.

### Pitfall 5: Container-scoped CREATE_VIEW uses wrong path

**What goes wrong:** `validatePrivilege()` uses `key.getSchemaPath()` as objectPath. For CREATE_VIEW, this is the view's own path (e.g., `"my_space.my_view"`). But the locked decision says CREATE_VIEW is granted on the **container** (`"my_space"`, not `"my_space.my_view"`). If the grant is stored as `roleId|VDS|my_space|CREATE_VIEW` but the check uses `"my_space.my_view"`, the check always fails.

**Why it happens:** `validatePrivilege()` was designed for per-object checks; container-scoped checks need the parent path.

**How to avoid:** For CREATE_VIEW specifically, pass `key.getParent()` (or its schema path) to the privilege check. Implement as a separate method `validateCreateViewPrivilege(NamespaceKey viewKey)` that internally checks `viewKey.getParent().getSchemaPath()`. Grant CLI example: `GRANT CREATE_VIEW ON VDS my_space TO ROLE dev` stores `dev|VDS|my_space|CREATE_VIEW`.

---

## Code Examples

### Example 1: Fixed DropViewHandler (LIFE-02)

```java
// DropViewHandler.java — toResult(), line 55:
// BEFORE (bug):
catalog.validatePrivilege(path, SqlGrant.Privilege.ALTER);
// AFTER (correct):
catalog.validatePrivilege(path, SqlGrant.Privilege.DROP);
```

### Example 2: New error message in CatalogImpl.validatePrivilege()

```java
// CatalogImpl.java:2837-2840 — BEFORE:
if (!rbacService.hasPrivilege(userName, rbacPrivilege, rbacObjectType, objectPath)) {
    logger.warn("RBAC: Access denied for user '{}'", userName);
    throw UserException.validationError().message("Table '%s' not found", key).buildSilently();
}

// AFTER (mandated format):
if (!rbacService.hasPrivilege(userName, rbacPrivilege, rbacObjectType, objectPath)) {
    logger.warn("RBAC: Access denied for user '{}' — privilege {} on {}", userName, rbacPrivilege, objectPath);
    throw UserException.validationError()
        .message("Permission denied: %s privilege required on '%s'", rbacPrivilege, objectPath)
        .buildSilently();
}
```

### Example 3: validateCreateViewPrivilege() for container-scoped check

```java
// New method in CatalogImpl (or added to Catalog interface):
public void validateCreateViewPrivilege(NamespaceKey viewKey) {
    if (dremioConfig == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        return;
    }
    if (SystemUser.isSystemUserName(userName)) {
        return;
    }
    if (rbacService == null) {
        return;
    }
    // Container-scoped: check parent, not the view itself
    String containerPath = viewKey.getParent().getSchemaPath();
    if (!rbacService.hasPrivilege(userName, "CREATE_VIEW", "VDS", containerPath)) {
        logger.warn("RBAC: Access denied for user '{}' — privilege CREATE_VIEW on {}", userName, containerPath);
        throw UserException.validationError()
            .message("Permission denied: CREATE_VIEW privilege required on '%s'", containerPath)
            .buildSilently();
    }
}
```

Note: This must also be added to the `Catalog` interface so that `CatalogServiceHelper` can call it via `catalogSupplier.get()`.

### Example 4: ALTER check in CreateOrUpdateViewHandler (non-versioned update path)

```java
// CreateOrUpdateViewHandler.createView() — after isUpdate is determined:
private List<SimpleCommandResult> createView(...) {
    boolean isUpdate = createView.getReplace();
    boolean exists = checkViewExistence(newViewName, isUpdate, CatalogEntityKey.fromNamespaceKey(viewPath));
    isUpdate &= exists;
    if (isUpdate) {
        catalog.validatePrivilege(viewPath, SqlGrant.Privilege.ALTER);
    }
    // ... rest of method
}
```

### Example 5: GRANT ALTER / GRANT DROP — already works

```sql
-- These work via the existing CatalogGrantHandler — no parser changes needed:
GRANT ALTER ON VDS my_space.my_view TO ROLE editor
GRANT DROP ON VDS my_space.my_view TO ROLE editor
REVOKE ALTER ON VDS my_space.my_view FROM ROLE editor
REVOKE DROP ON VDS my_space.my_view FROM ROLE editor
GRANT CREATE_VIEW ON VDS my_space TO ROLE developer
```

The `CatalogGrantHandler` iterates `grantNode.getPrivilegeList()` and calls `rbacService.grantPrivilege(grantee, objectType, objectPath, privilege.name(), userName)`. Since `SqlGrant.Privilege.ALTER`, `DROP`, and `CREATE_VIEW` are all in the enum, the existing parser and handler accept them without modification.

### Example 6: Test pattern for new enforcement (modeled on existing tests)

```java
// In TestCatalogImpl — new tests for ALTER and DROP:
@Test
public void testValidatePrivilege_alter_denied_throwsPermissionDenied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("ALTER"), eq("VDS"), anyString()))
        .thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.validatePrivilege(
                    new NamespaceKey(Arrays.asList("myspace", "myview")),
                    SqlGrant.Privilege.ALTER))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("ALTER");
}

// In TestRbacDdlHandlers — new test for GRANT ALTER / GRANT DROP:
@Test
public void testCatalogGrant_alterOnVds_success() throws Exception {
    SqlGrantOnCatalog node =
        new SqlGrantOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.ALTER),
            SqlLiteral.createSymbol(SqlGrant.GrantType.VDS, SqlParserPos.ZERO),
            compoundId("myspace", "myview"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("editor"),
            null,
            null);
    new CatalogGrantHandler(queryContext).toResult("GRANT ALTER ON VDS myspace.myview TO ROLE editor", node);
    verify(rbacService).grantPrivilege("editor", "VDS", "myspace.myview", "ALTER", "admin_user");
}
```

---

## Exact Call Sites to Change

### Summary table of all changes

| File | Location | Current Code | Required Change |
|------|----------|-------------|-----------------|
| `CatalogImpl.java` | `validatePrivilege()` line 2839 | `"Table '%s' not found"` error | New format: `"Permission denied: %s privilege required on '%s'"` |
| `CatalogImpl.java` | `resolveRbacObjectType()` line 2848 | `ALTER` case but no `DROP` case | Add `case DROP:` returning `"VDS"` |
| `CatalogImpl.java` | new method | (does not exist) | Add `validateCreateViewPrivilege(NamespaceKey)` for container-scoped check |
| `Catalog.java` (interface) | method signature | (does not exist) | Add `validateCreateViewPrivilege(NamespaceKey)` to interface |
| `DropViewHandler.java` | `toResult()` line 55 | `SqlGrant.Privilege.ALTER` | Change to `SqlGrant.Privilege.DROP` |
| `CreateOrUpdateViewHandler.java` | `createView()` private method | no ALTER check | Add `if (isUpdate) catalog.validatePrivilege(viewPath, SqlGrant.Privilege.ALTER)` |
| `CreateOrUpdateViewHandler.java` | `createVersionedView()` line 150 | no ALTER check on update | Add `if (isUpdate) catalog.validatePrivilege(viewPath, SqlGrant.Privilege.ALTER)` |
| `CreateOrUpdateViewHandler.java` | `toResult()` line 105 | `CREATE_VIEW` check (correct for create, wrong for replace) | Replace with `validateCreateViewPrivilege()` (container-scoped) for create; keep ALTER for replace |
| `CatalogServiceHelper.java` | `createDataset()`/VDS path line ~1297 | no privilege check | Add `catalogSupplier.get().validateCreateViewPrivilege(namespaceKey)` |
| `CatalogServiceHelper.java` | `updateVersionedView()` line ~1470 | no privilege check | Add `catalogSupplier.get().validatePrivilege(namespaceKey, SqlGrant.Privilege.ALTER)` |
| `CatalogServiceHelper.java` | non-versioned update path line ~1693 | no privilege check | Add `catalogSupplier.get().validatePrivilege(namespaceKey, SqlGrant.Privilege.ALTER)` |
| `TestCatalogImpl.java` | all `hasMessageContaining("not found")` assertions | old error format | Update to `"Permission denied"` and privilege name assertions |

---

## Grant Hint in Error Messages (Claude's Discretion)

**Finding:** The existing Dremio codebase does NOT include GRANT hints in `UserException` messages. Searching for "GRANT" in error messages yields no results in the existing privilege-enforcement code. The standard pattern is a terse denial message without remediation instructions.

**Recommendation:** Do NOT include a GRANT hint in the error message. Reasons:
1. Consistent with all other Dremio error messages (no precedent for GRANT hints).
2. The `buildSilently()` variant is used, which means the message is terse by design (not a user-facing tutorial).
3. The mandated format `"Permission denied: {PRIVILEGE} privilege required on '{full.path.to.view}'"` is already informative enough for an admin to diagnose and issue the appropriate GRANT.

---

## Open Questions

1. **`Catalog` interface change for `validateCreateViewPrivilege()`**
   - What we know: `CatalogServiceHelper` uses `catalogSupplier.get()` which returns a `Catalog` interface. The method must be on the interface to be callable.
   - What's unclear: Whether there are other implementations of `Catalog` that would need the method (e.g., test doubles, wrapper catalogs like `DelegatingCatalog`).
   - Recommendation: Grep for all `implements Catalog` before planning to identify impacted classes. Add a default no-op implementation to the interface if there are many non-`CatalogImpl` implementations, to limit blast radius.

2. **Non-versioned REST API update path**
   - What we know: `CatalogServiceHelper` has two update paths: `updateVersionedView()` at line 1470 (versioned) and a non-versioned path at line 1693 calling `updateView()`.
   - What's unclear: Whether the non-versioned update path is reached via the same entry point as the versioned one or via a separate REST endpoint.
   - Recommendation: Read both paths carefully during planning to ensure ALTER is enforced in both.

3. **`viewCreatorFactory` CREATE_VIEW path in REST API**
   - What we know: Line 1297 calls `viewCreatorFactory.get(userName).createView(...)` which is a separate path from `catalogSupplier.get().createView(...)`.
   - What's unclear: Whether `viewCreatorFactory.get().createView()` internally calls `catalogSupplier.get().createView()` or bypasses the catalog entirely.
   - Recommendation: Trace the `ViewCreatorFactory.ViewCreator.createView()` implementation to confirm whether adding `validateCreateViewPrivilege()` before the factory call is sufficient or whether the factory itself also needs instrumentation.

---

## Sources

### Primary (HIGH confidence — direct code inspection)
- `CatalogImpl.java` — `validatePrivilege()`, `resolveRbacObjectType()`, `createView()`, `updateView()`, `dropView()` — read in full
- `DropViewHandler.java` — read in full; confirmed `ALTER` bug at line 55
- `CreateOrUpdateViewHandler.java` — read in full; confirmed `CREATE_VIEW` validation at line 105; missing ALTER for update paths
- `CatalogServiceHelper.java` — lines 1280-1320, 1435-1471, 1670-1699 — confirmed no `validatePrivilege()` calls
- `SqlGrant.java` — enum values confirmed: `ALTER`, `DROP`, `CREATE_VIEW` all present
- `RbacService.java` — `hasPrivilege()` ADMIN short-circuit confirmed
- `GrantStore.java` — grant key format confirmed: `roleId|objectType|objectPath|privilege`
- `RbacConfig.java` — key separator and store names confirmed
- `CatalogGrantHandler.java`, `CatalogRevokeHandler.java` — generic handlers confirmed; no changes needed
- `SqlGrantOnCatalog.java`, `SqlRevokeOnCatalog.java` — generic parsers confirmed; no changes needed
- `TestCatalogImpl.java` — existing test structure and error message assertions confirmed
- `TestRbacDdlHandlers.java` — handler test structure confirmed; shows the test pattern for GRANT ALTER/DROP
- `NamespaceKey.java` — `getParent()`, `getSchemaPath()` confirmed

### Secondary (MEDIUM confidence)
- CONTEXT.md decisions — locked constraints applied throughout

---

## Metadata

**Confidence breakdown:**
- Call site locations: HIGH — confirmed by direct code inspection
- Grant/Revoke DDL path: HIGH — CatalogGrantHandler confirmed generic; no parser changes needed
- Container-scoped CREATE_VIEW path resolution: HIGH — `NamespaceKey.getParent().getSchemaPath()` confirmed
- REST API gap: HIGH — CatalogServiceHelper lines 1297, 1470, 1693 confirmed without privilege checks
- Error message update impact: HIGH — 9 affected tests identified in TestCatalogImpl
- Catalog interface impact: MEDIUM — other Catalog implementations not fully inventoried

**Research date:** 2026-02-20
**Valid until:** 2026-03-20 (stable code; changes only if RBAC implementation evolves)
