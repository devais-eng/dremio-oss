# Phase 13: Code Hardening - Research

**Researched:** 2026-02-23 (re-verified against current source code)
**Domain:** Java RBAC enforcement in CatalogImpl / DropViewHandler / CatalogServiceHelper
**Confidence:** HIGH — all findings verified directly from current source code; no library lookups needed

---

## Summary

Phase 13 closes three surgical code-level gaps identified by the v1.2 audit. All three fixes touch
existing production code that was already modified in prior phases. No new infrastructure is
needed — this is pure hardening of what already exists.

**Gap 1 (INT-01):** `CatalogImpl.getTable(String datasetId)` at line 1213 has a TODO comment
from Phase 10 noting that `isRbacDeniedForPds` was intentionally not applied here. Phase 13 must
add the PDS enforcement call. The challenge is that this method takes a raw string ID (not a
`NamespaceKey`), so the key must be derived from the resolved table's `getPath()`.

**Gap 2 (Finding 1 / LIFE-02 UX):** `DropViewHandler.toResult()` calls `validatePrivilege(DROP)`
at line 55, then calls `getTableNoColumnCount(path)` at lines 71-73. `getTableNoColumnCount`
internally calls `isRbacDeniedForVds` which checks SELECT. A user with DROP but not SELECT
gets past the DROP check, then `getTableNoColumnCount` returns null (isRbacDeniedForVds fires),
and the null table causes "Unknown view" at line 78. Fix: add `validatePrivilege(SELECT)` before
the `getTableNoColumnCount` call so the user gets "Permission denied: SELECT privilege required"
instead of "Unknown view".

**Gap 3 (Finding 4):** `CatalogServiceHelper.createSource()` at line 1850-1854 delegates to
`sourceService.createSource()` without an admin check. The REST endpoint `CatalogResource` is
`@RolesAllowed({"user", "admin"})`, so non-admin users can attempt source creation.
`SourceService.createSource()` at line 266-270 checks existence BEFORE any RBAC enforcement,
so "already exists" leaks before "permission denied". Fix: add an `isAdminMember` check in
`CatalogServiceHelper.createSource()` BEFORE calling `sourceService.createSource()`.

**Primary recommendation:** Three surgical edits to three existing methods; pair each with a unit
test documenting the new contract. No new classes, no new interfaces.

---

<phase_requirements>

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PDS-02 | PDS are only accessible to users with a SELECT grant (deny-by-default) | `isRbacDeniedForPds()` already exists at CatalogImpl line 2962; `getTable(String)` at line 1213 is the only call site not wired; fix derives NamespaceKey from resolved `table.getPath()` |
| LIFE-02 | User can only DROP a VDS if they have DROP privilege on it | DROP check exists at DropViewHandler line 55; gap is missing SELECT check before null-table "Unknown view" error at line 78; fix adds `validatePrivilege(SELECT)` after DROP check passes and before `getTableNoColumnCount` |

</phase_requirements>

---

## Standard Stack

### Core (already present — no new dependencies)

| Class | File | Purpose |
|-------|------|---------|
| `CatalogImpl` | `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` | PDS enforcement via `isRbacDeniedForPds()` and all getTable overloads |
| `DropViewHandler` | `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropViewHandler.java` | DROP VIEW DDL handler |
| `CatalogServiceHelper` | `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` | REST API catalog operations including createSource |
| `RbacService` | `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` | `isAdminMember()`, `hasPrivilege()` |
| `SqlGrant.Privilege` | SQL parser module | Privilege enum: SELECT, DROP, ALTER, CREATE_VIEW |

### Test Files

| Class | File | Purpose |
|-------|------|---------|
| `TestCatalogImpl` | `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` | Unit tests for CatalogImpl RBAC enforcement |
| `TestDescribeTableHandler` | `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDescribeTableHandler.java` | Template for handler-level validatePrivilege tests |
| `TestCatalogServiceHelper` | `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` | Has `rbacEnabledHelper` fixture; Finding 4 test goes here |

---

## Architecture Patterns

### Pattern 1: PDS Enforcement on getTable(String datasetId)

**What:** `getTable(String datasetId)` at CatalogImpl line 1213-1224 resolves either a time-travel
path (via `getTableForTimeTravel`) or a regular dataset (via `datasetManager.getTable(datasetId)`).
The current code returns the resolved table without calling `isRbacDeniedForPds`.

**Key constraint:** Unlike the `NamespaceKey`-based overloads, this method receives a raw string
ID. A `NamespaceKey` must be derived from the resolved table's `.getPath()` method after
resolution. If the table is null (not found), there is nothing to enforce.

**Exact current code (lines 1212-1224):**

```java
@Override
public DremioTable getTable(String datasetId) {
  // TODO: PDS enforcement not applied here -- review if this path needs isRbacDeniedForPds
  // enforcement
  VersionedDatasetId versionedDatasetId = VersionedDatasetId.tryParse(datasetId);
  final boolean isTimeTravelDataset =
      versionedDatasetId != null && versionedDatasetId.getVersionContext().isTimeTravelType();
  Span.current().setAttribute("dremio.catalog.getTable.isTimeTravelDataset", isTimeTravelDataset);
  if (isTimeTravelDataset) {
    return getTableForTimeTravel(versionedDatasetId);
  }
  return datasetManager.getTable(datasetId, options);
}
```

**Fixed version:**

```java
@Override
public DremioTable getTable(String datasetId) {
  VersionedDatasetId versionedDatasetId = VersionedDatasetId.tryParse(datasetId);
  final boolean isTimeTravelDataset =
      versionedDatasetId != null && versionedDatasetId.getVersionContext().isTimeTravelType();
  Span.current().setAttribute("dremio.catalog.getTable.isTimeTravelDataset", isTimeTravelDataset);
  final DremioTable table;
  if (isTimeTravelDataset) {
    table = getTableForTimeTravel(versionedDatasetId);
  } else {
    table = datasetManager.getTable(datasetId, options);
  }
  if (table != null && isRbacDeniedForPds(table, table.getPath())) {
    return null; // RBAC denied -- appear as "not found"
  }
  return table;
}
```

**Why `table.getPath()`:** `DremioTable.getPath()` returns `NamespaceKey` (interface contract,
used throughout CatalogImpl). The raw `datasetId` string is a serialized versioned JSON blob
like `{"tableKey":["source","table"],...}` — not a namespace path. Using it directly as a key
would be wrong. The resolved table's path is always correct.

**Note on VDS:** `isRbacDeniedForPds` starts with `if (table instanceof ViewTable) return false;`
so calling it on a VDS is safe — it is a no-op. The method is symmetric-safe by design.

**Confidence:** HIGH — verified from the exact source code lines; fix pattern identical to the 6
existing `isRbacDeniedForPds` call sites in the same file.

### Pattern 2: DROP VIEW Error Message Fix (DropViewHandler)

**What:** The root cause is ordering. `validatePrivilege(DROP)` passes (user has DROP), then
`getTableNoColumnCount` returns null because `isRbacDeniedForVds` fires (no SELECT), then the
null-table check at line 76-78 produces "Unknown view". The fix adds a SELECT privilege check
between the DROP check and the `getTableNoColumnCount` call.

**Exact current code (lines 52-90):**

```java
@Override
public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
  final SqlDropView dropView = SqlNodeUtil.unwrap(sqlNode, SqlDropView.class);
  NamespaceKey path = catalog.resolveSingle(dropView.getPath());
  catalog.validatePrivilege(path, SqlGrant.Privilege.DROP);  // line 55

  final DremioTable table;
  final String sourceName = path.getRoot();
  VersionContext statementSourceVersion = ...;
  final VersionContext sessionVersion = config.getContext().getSession().getSessionVersionForSource(sourceName);
  VersionContext sourceVersion = statementSourceVersion.orElse(sessionVersion);
  final ResolvedVersionContext version = CatalogUtil.resolveVersionContext(catalog, sourceName, sourceVersion);

  if (isVersioned(path)) {
    ...
    table = catalog.resolveCatalog(contextMap).getTableNoColumnCount(path);  // line 71
  } else {
    table = catalog.getTableNoColumnCount(path);  // line 73
  }

  if (dropView.shouldErrorIfViewDoesNotExist()) {
    if (table == null) {
      throw UserException.validationError().message("Unknown view [%s].", path).buildSilently(); // line 78 -- wrong
    }
    ...
  }
  ...
```

**Broken flow:**
```
validatePrivilege(DROP)   <- passes (user has DROP)
getTableNoColumnCount()   <- returns null (isRbacDeniedForVds fires, no SELECT)
if (table == null) -> "Unknown view [%s]"  <- WRONG message
```

**Fixed flow:**
```
validatePrivilege(DROP)   <- passes (user has DROP)
validatePrivilege(SELECT) <- throws "Permission denied: SELECT privilege required on 'path'"
getTableNoColumnCount()   <- never reached if SELECT missing
if (table == null) -> "Unknown view [%s]"  <- only reached when view genuinely absent
```

**Implementation — insert after line 55:**

```java
catalog.validatePrivilege(path, SqlGrant.Privilege.DROP);
// LIFE-02: SELECT is required to resolve the view before dropping it.
// Without this check, DROP-only users get "Unknown view" instead of "Permission denied".
catalog.validatePrivilege(path, SqlGrant.Privilege.SELECT);
```

**Why `validatePrivilege(SELECT)` is safe without an outer RBAC_ENABLED guard:**
The `validatePrivilege` method in `CatalogImpl` (line 2838) starts with:
```java
if (dremioConfig == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
  return;
}
```
When RBAC is disabled, the call is a no-op. No outer guard needed in DropViewHandler.

**`resolveRbacObjectType` for SELECT returns "VDS"** (CatalogImpl line 2897-2908), which is
correct — DROP VIEW always targets a VDS. A PDS at a path named like a view would go through
DROP TABLE, not DROP VIEW.

**Confidence:** HIGH — full method body traced; fix is one inserted line.

### Pattern 3: Source Creation Metadata Leak Fix (CatalogServiceHelper)

**What:** `CatalogResource` at line 56 is `@RolesAllowed({"user", "admin"})`. Non-admin users
can POST a `Source` entity. `CatalogServiceHelper.createSource()` at line 1850 goes directly to
`sourceService.createSource()` with no admin check. `SourceService.createSource()` checks
existence at lines 267-270 BEFORE any RBAC enforcement, so "already exists" is returned before
"permission denied".

**Exact current code (line 1850-1854):**

```java
private CatalogEntity createSource(
    Source source, SourceRefreshOption sourceRefreshOption, NamespaceAttribute... attributes)
    throws NamespaceException, ExecutionSetupException {
  SourceConfig sourceConfig =
      sourceService.createSource(source.toSourceConfig(), sourceRefreshOption, attributes);
```

**Fix — add admin check at the top:**

```java
private CatalogEntity createSource(
    Source source, SourceRefreshOption sourceRefreshOption, NamespaceAttribute... attributes)
    throws NamespaceException, ExecutionSetupException {
  // Phase 13 Finding 4: check admin status before existence check to prevent metadata leak.
  // Non-admin users must see "Permission denied" not "Source already exists".
  if (rbacService != null
      && dremioConfig != null
      && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    String userName = securityContext.getUserPrincipal().getName();
    if (!rbacService.isAdminMember(userName)) {
      throw UserException.validationError()
          .message("Permission denied: only administrators can create sources.")
          .buildSilently();
    }
  }
  SourceConfig sourceConfig =
      sourceService.createSource(source.toSourceConfig(), sourceRefreshOption, attributes);
```

**All dependencies verified available in `CatalogServiceHelper`:**

| Field | Line | Notes |
|-------|------|-------|
| `rbacService` | 282 | `@Nullable`, used at line 3141 (`rbacService.isAdminMember(userName)`) |
| `dremioConfig` | 283 | `@Nullable`, used at line 3138 (`dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)`) |
| `securityContext` | 271 | Used at lines 366, 878, 1158, etc. (`securityContext.getUserPrincipal().getName()`) |

The three-way null guard (`rbacService != null && dremioConfig != null && RBAC_ENABLED`) matches
the existing pattern used at lines 3136-3138 (`getUserAccessibleObjectPaths`) and
lines 3161-3163 (`isObjectVisible`) in the same file.

**RBAC_ENABLED guard is required:** When RBAC is off, non-admin users can create sources via
the existing Dremio role system (`@RolesAllowed`). The admin-only enforcement is a v1.2 RBAC
feature, not a general ACL.

**Confidence:** HIGH — all field names and usage patterns verified from exact source line numbers.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Admin check | Custom role check logic | `rbacService.isAdminMember(userName)` — already in this codebase |
| Error message format | New exception types | `UserException.validationError().message(...).buildSilently()` — used throughout CatalogImpl and CatalogServiceHelper |
| NamespaceKey from resolved table | Custom key adapter | `table.getPath()` — `DremioTable.getPath()` returns `NamespaceKey` |

---

## Common Pitfalls

### Pitfall 1: Wrong Key Type for getTable(String datasetId) PDS Enforcement

**What goes wrong:** Calling `isRbacDeniedForPds(table, new NamespaceKey(datasetId))` — the
raw string datasetId is a versioned JSON blob like `{"tableKey":["source","table"],...}`, not
a namespace path.

**Why it happens:** The method signature takes a String (a serialized dataset ID), not a key.

**How to avoid:** Always use `table.getPath()` (the resolved table's namespace key) as the
key argument to `isRbacDeniedForPds`. The table has already been resolved at this point.

**Warning sign:** `key.getSchemaPath()` returning a JSON blob instead of a dot-delimited path.

### Pitfall 2: Forgetting the Null Guard on the Resolved Table

**What goes wrong:** Calling `isRbacDeniedForPds(table, table.getPath())` before checking if
`table != null` — NPE at runtime when datasetId doesn't resolve to anything.

**How to avoid:** Always guard with `if (table != null && isRbacDeniedForPds(...))`.
This is the same guard pattern all 6 existing call sites use.

### Pitfall 3: Adding SELECT Check AFTER getTableNoColumnCount in DropViewHandler

**What goes wrong:** Placing `validatePrivilege(SELECT)` after the `getTableNoColumnCount` call.
The table is already null at that point for SELECT-less users, making the check unreachable.

**How to avoid:** The SELECT check must come BEFORE `getTableNoColumnCount`, immediately after
`validatePrivilege(DROP)` at line 55.

### Pitfall 4: Source Admin Check Missing RBAC_ENABLED Guard

**What goes wrong:** Always throwing "Permission denied" for non-admins even when RBAC is
disabled — breaking existing behavior where `@RolesAllowed` was the only enforcement.

**How to avoid:** Gate the admin check on `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)`.
Only enforce when RBAC is explicitly enabled. Use the same three-way guard as lines 3136-3138.

### Pitfall 5: validatePrivilege(SELECT) in DropViewHandler Using Wrong Object Type

**What goes wrong:** Concern that `resolveRbacObjectType` for SELECT might return "PDS" for
physical datasets.

**Why it's safe:** `resolveRbacObjectType` returns "VDS" for SELECT (line 2902-2906 in
CatalogImpl). DROP VIEW always targets views, not physical tables (those go through DROP TABLE).
A PDS would never reach `DropViewHandler`.

### Pitfall 6: dremioConfig Availability in CatalogServiceHelper.createSource()

**What goes wrong:** Accessing `dremioConfig` when it might not be injected. `CatalogServiceHelper`
has `@Nullable DremioConfig dremioConfig` at line 283. It is nullable.

**How to avoid:** Always guard with `if (rbacService != null && dremioConfig != null && ...)`.
This is the same pattern used in `getUserAccessibleObjectPaths()` at line 3136-3138.

### Pitfall 7: Testing DropViewHandler — SqlHandlerConfig is not directly mockable

**What goes wrong:** `SqlHandlerConfig` constructor requires `SqlConverter` and other complex
objects, making direct instantiation impractical in unit tests.

**How to avoid:** Mock the full chain: mock `SqlHandlerConfig` → `getContext()` → mock
`QueryContext` → `getCatalog()` → mock `Catalog`. Also mock `QueryContext.getSession()` →
mock `UserSession` → `getSessionVersionForSource()`. This is the same chain used in
`TestDescribeTableHandler`.

---

## Code Examples

### Gap 1: getTable(String datasetId) — Full Fixed Method

Source: `CatalogImpl.java` lines 1212-1224 (current) → replacement:

```java
@Override
public DremioTable getTable(String datasetId) {
  VersionedDatasetId versionedDatasetId = VersionedDatasetId.tryParse(datasetId);
  final boolean isTimeTravelDataset =
      versionedDatasetId != null && versionedDatasetId.getVersionContext().isTimeTravelType();
  Span.current().setAttribute("dremio.catalog.getTable.isTimeTravelDataset", isTimeTravelDataset);
  final DremioTable table;
  if (isTimeTravelDataset) {
    table = getTableForTimeTravel(versionedDatasetId);
  } else {
    table = datasetManager.getTable(datasetId, options);
  }
  if (table != null && isRbacDeniedForPds(table, table.getPath())) {
    return null; // RBAC denied -- appear as "not found"
  }
  return table;
}
```

The diff is: extract the `if/else` into a `final DremioTable table` variable, remove TODO comment,
add the `isRbacDeniedForPds` guard before the return.

### Gap 2: DropViewHandler — One-Line Fix

Source: `DropViewHandler.java` line 55 → insert after:

```java
catalog.validatePrivilege(path, SqlGrant.Privilege.DROP);
// LIFE-02: SELECT is required to resolve the view before dropping it.
// Without this check, DROP-only users get "Unknown view" instead of "Permission denied".
catalog.validatePrivilege(path, SqlGrant.Privilege.SELECT);
```

### Gap 3: CatalogServiceHelper.createSource() — Admin Guard

Source: `CatalogServiceHelper.java` line 1852 → insert before `sourceService.createSource(...)`:

```java
// Phase 13 Finding 4: check admin status before existence check to prevent metadata leak.
if (rbacService != null
    && dremioConfig != null
    && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
  String userName = securityContext.getUserPrincipal().getName();
  if (!rbacService.isAdminMember(userName)) {
    throw UserException.validationError()
        .message("Permission denied: only administrators can create sources.")
        .buildSilently();
  }
}
```

### Test Pattern for DropViewHandler (from TestDescribeTableHandler)

Source: `TestDescribeTableHandler.java` lines 200-226 — verified working pattern:

```java
@Test
public void testDropView_dropOnlyUser_getsPermissionDenied_notUnknownView() throws Exception {
  // LIFE-02: DROP-only user (no SELECT) must see "Permission denied" not "Unknown view"
  List<String> viewPath = List.of("myspace", "myview");
  when(catalog.resolveSingle(new NamespaceKey(viewPath))).thenReturn(new NamespaceKey(viewPath));
  when(session.getSessionVersionForSource("myspace")).thenReturn(VersionContext.NOT_SPECIFIED);
  // DROP passes, SELECT throws
  // catalog.validatePrivilege(path, DROP) -> mock default (no exception)
  doThrow(
          UserException.validationError()
              .message("Permission denied: SELECT privilege required on 'myspace.myview'")
              .buildSilently())
      .when(catalog)
      .validatePrivilege(new NamespaceKey(viewPath), SqlGrant.Privilege.SELECT);

  SqlDropView dropView = new SqlDropView(
      SqlParserPos.ZERO,
      new SqlIdentifier(viewPath, SqlParserPos.ZERO),
      true,  // shouldErrorIfViewDoesNotExist
      null,
      null);

  assertThatThrownBy(() -> dropViewHandler.toResult("DROP VIEW myspace.myview", dropView))
      .isInstanceOf(UserException.class)
      .hasMessageContaining("Permission denied")
      .hasMessageContaining("SELECT");
}
```

The `DropViewHandler` must be constructed from mocked `SqlHandlerConfig`:

```java
SqlHandlerConfig config = mock(SqlHandlerConfig.class);
QueryContext queryContext = mock(QueryContext.class);
Catalog catalog = mock(Catalog.class);
UserSession session = mock(UserSession.class);
when(config.getContext()).thenReturn(queryContext);
when(queryContext.getCatalog()).thenReturn(catalog);
when(queryContext.getSession()).thenReturn(session);
DropViewHandler handler = new DropViewHandler(config);
```

### Test Pattern for CatalogServiceHelper Finding 4 (from TestCatalogServiceHelper)

Source: `TestCatalogServiceHelper.java` lines 239-256 — `rbacEnabledHelper` is already set up with
`mock(RbacService.class)` and `when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true)`.

```java
@Test
public void testCreateSource_nonAdmin_getsPermissionDenied_notAlreadyExists() throws Exception {
  // Finding 4: non-admin must see "Permission denied" not the source-existence leakage
  when(rbacService.isAdminMember("user")).thenReturn(false);

  Source source = new Source();
  source.setName("newsource");
  source.setType("NAS");

  assertThatThrownBy(
          () -> rbacEnabledHelper.createCatalogItem(
              source, SourceRefreshOption.BACKGROUND_DATASETS_CREATION))
      .isInstanceOf(UserException.class)
      .hasMessageContaining("Permission denied")
      .hasMessageContaining("administrators");

  // Verify sourceService.createSource was never called (no metadata leak)
  verify(sourceService, never()).createSource(any(), any(), any());
}
```

---

## File Inventory

### Files to Modify (Production)

| File | Change | Gap Closed |
|------|--------|-----------|
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` | Refactor `getTable(String datasetId)` to extract table variable, remove TODO comment, add `isRbacDeniedForPds` guard | INT-01 (PDS-02) |
| `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropViewHandler.java` | Add `validatePrivilege(SELECT)` after `validatePrivilege(DROP)` at line 55 | Finding 1 (LIFE-02 UX) |
| `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` | Add admin check in `createSource()` before `sourceService.createSource()` | Finding 4 |

### Files to Modify (Tests)

| File | Change | What It Tests |
|------|--------|--------------|
| `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` | Add test: `getTable(String)` with PDS enforcement wired up | INT-01 |
| `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropViewHandler.java` (new file, OR add to `TestDescribeTableHandler.java` directory) | Add test: DROP VIEW with DROP-only user gets "Permission denied" not "Unknown view" | Finding 1 |
| `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` | Add test using `rbacEnabledHelper`: non-admin gets "Permission denied" on createSource | Finding 4 |

**Note on DropViewHandler test location:** `TestDescribeTableHandler.java` is in
`handlers/direct/` and uses the same mock-Catalog pattern. A new `TestDropViewHandler.java`
in the same package is cleanest. Alternatively, the test can be added to the existing
`TestRbacDdlHandlers.java` with additional Catalog/SqlHandlerConfig mocking — but that file
currently only tests handlers that take `QueryContext` directly, so a new file is preferred.

---

## Key Verified Facts

### Fact 1: isRbacDeniedForPds is NOT called from getTable(String datasetId)

Verified from `CatalogImpl.java` — grep for `isRbacDeniedForPds` returns 8 occurrences:
- 1 method definition at line 2962
- 6 call sites (lines 293, 305, 320, 331, 346, 397)
- 1 TODO comment at line 1214

None of the 6 call sites is `getTable(String datasetId)`. The TODO at line 1214 explicitly
confirms enforcement is absent.

### Fact 2: isRbacDeniedForPds uses deny-by-default

Current code at lines 2988-2994:
```java
if (!rbacService.hasPrivilege(userName, "SELECT", "PDS", objectPath)) {
  logger.warn("RBAC: PDS access denied for user '{}'", userName);
  return true;
}
```
No `hasAnyPdsGrant` opt-in call. Phase 13 requirement PDS-02 "deny-by-default" is already
correctly implemented. Phase 13 only needs to extend it to the missing `getTable(String)` call site.

### Fact 3: DropViewHandler line numbers verified

- Line 55: `catalog.validatePrivilege(path, SqlGrant.Privilege.DROP);`
- Lines 71-73: `getTableNoColumnCount(path)` call
- Lines 76-78: null table check with "Unknown view" error
- Fix: insert `catalog.validatePrivilege(path, SqlGrant.Privilege.SELECT);` after line 55

### Fact 4: CatalogServiceHelper fields confirmed

- `securityContext` (line 271): `SecurityContext`, `getUserPrincipal().getName()` widely used
- `rbacService` (line 282): `@Nullable RbacService`, `isAdminMember()` at line 3141
- `dremioConfig` (line 283): `@Nullable DremioConfig`, `RBAC_ENABLED` at line 3138

### Fact 5: validatePrivilege has internal RBAC_ENABLED guard

```java
public void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege) {
  if (dremioConfig == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    return;  // lines 2838-2840
  }
  ...
}
```
Calling `validatePrivilege(SELECT)` unconditionally in DropViewHandler is safe — RBAC off means
no-op.

### Fact 6: TestCatalogServiceHelper has rbacEnabledHelper ready for Finding 4 tests

`rbacEnabledHelper` is constructed at lines 243-256 with:
- `rbacService = mock(RbacService.class)`
- `dremioConfig = mock(DremioConfig.class)` with `RBAC_ENABLED` returning true
- `securityContext` principal returning "user"

The Finding 4 test only needs to add `when(rbacService.isAdminMember("user")).thenReturn(false)`
and assert the exception — all infrastructure is already there.

### Fact 7: TestDescribeTableHandler provides exact test pattern for DropViewHandler

Lines 200-226 show the verified working pattern:
- `doThrow(UserException...)` to mock `validatePrivilege` failure
- Assert via `assertThatThrownBy(...).isInstanceOf(UserException.class).hasMessageContaining(...)`
- The DropViewHandler test requires constructing through mocked `SqlHandlerConfig` chain
  (config → context → catalog, context → session)

---

## Open Questions

1. **Can `getTable(String datasetId)` be tested meaningfully with null DatasetManager output?**
   - What we know: `datasetManager.getTable(datasetId, options)` returns null for non-existent
     IDs in unit test setup (DatasetManager is internal and not easily mocked)
   - What's unclear: Whether a mock DatasetManager can return a non-null table in test setup
   - Recommendation: The test should verify that when `datasetManager` returns a non-null table
     and `rbacService.hasPrivilege` is mocked to return false, `isRbacDeniedForPds` fires and
     the method returns null. This requires the test to exercise the path through a mock. The
     existing PDS tests at lines 2144-2233 in `TestCatalogImpl` verify `isRbacDeniedForPds`
     fires correctly. The new test for `getTable(String)` may be a structural test (verify the
     method body contains the enforcement) or a mock-based test via DatasetManager injection.

2. **Should the DropViewHandler test be in a new file or added to TestRbacDdlHandlers?**
   - What we know: `TestRbacDdlHandlers` tests handlers that use `QueryContext` directly; it
     does not currently mock `Catalog` or `SqlHandlerConfig`. Adding DropViewHandler tests there
     would require expanding the mock setup significantly.
   - Recommendation: New `TestDropViewHandler.java` in `handlers/direct/` package, following
     `TestDescribeTableHandler` pattern. Same package means access to same imports.

---

## Sources

### Primary (HIGH confidence — verified by direct code read)

- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` — `getTable(String)` at
  lines 1212-1224, `isRbacDeniedForPds()` at lines 2962-2997, `validatePrivilege()` at lines
  2836-2867, `resolveRbacObjectType()` at lines 2897-2908
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropViewHandler.java` —
  Full method body (119 lines), DROP check at line 55, getTableNoColumnCount at lines 71/73,
  "Unknown view" at line 78
- `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` —
  `createSource()` at lines 1850-1875, field declarations at lines 271/282/283, RBAC guard
  pattern at lines 3136-3138, `isAdminMember()` usage at line 3141
- `dac/backend/src/main/java/com/dremio/dac/api/CatalogResource.java` — `@RolesAllowed({"user",
  "admin"})` at line 56, POST endpoint at lines 104-115
- `dac/backend/src/main/java/com/dremio/dac/service/source/SourceService.java` — existence check
  at lines 266-270 before any RBAC enforcement
- `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDescribeTableHandler.java` —
  test pattern for `validatePrivilege`-based tests at lines 200-226
- `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` —
  `rbacEnabledHelper` setup at lines 239-256, existing RBAC test patterns at lines 2332-2404

---

## Metadata

**Confidence breakdown:**
- Gap 1 (INT-01): HIGH — code read confirmed missing call site; fix pattern identical to 6 existing sites
- Gap 2 (Finding 1): HIGH — full method body traced from exact line numbers; fix is one line
- Gap 3 (Finding 4): HIGH — field availability confirmed by line numbers; pattern matches existing admin checks in same file
- Test patterns: HIGH — verified working test code in same codebase

**Research date:** 2026-02-23
**Valid until:** Stable (changes only if CatalogImpl, DropViewHandler, or CatalogServiceHelper are refactored)
