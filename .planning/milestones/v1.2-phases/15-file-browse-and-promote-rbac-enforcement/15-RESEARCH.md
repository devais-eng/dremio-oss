# Phase 15: File Browse and Promote RBAC Enforcement - Research

**Researched:** 2026-02-23
**Domain:** Java RBAC enforcement on REST file-browsing and dataset-promotion endpoints
**Confidence:** HIGH — all findings verified by direct source inspection of production code; no external libraries needed

---

## Summary

Phase 15 adds admin-only guards to two related but distinct capabilities: file system browsing and dataset promotion. Both capabilities are exposed through the legacy `/source/{sourceName}` REST resource (`SourceResource.java`) AND the newer Catalog API (`CatalogResource.java` → `CatalogServiceHelper.java`). Neither resource class currently has RBAC injection or admin checks for these operations.

The enforcement model is identical to what phases 11 and 13 established: inject `@Nullable RbacService` and `@Nullable DremioConfig` into the target class, apply the three-way null guard (`rbacService != null && dremioConfig != null && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)`), then check `rbacService.isAdminMember(userName)` before allowing the operation. Non-admin callers get `UserException.validationError().message("Permission denied: ...").buildSilently()`.

There is NO meaningful SQL path for file promotion (SHOW FILES exists as a SQL parse node but is dispatched to the generic `NormalHandler` and depends entirely on underlying filesystem permissions, not RBAC). The enforcements needed for this phase are purely in the REST layer.

**Primary recommendation:** Add admin guards to two classes — `SourceResource` for the legacy UI path and `CatalogServiceHelper.promoteToDataset()` for the Catalog API path. File browsing in `SourceResource.getSource()` and `SourceResource.getFolder()` also needs a guard. Pair each new guard with a unit test.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| FILE-01 | Non-admin users cannot browse files/folders within sources | `SourceResource.getSource()` (when `includeContents=true`) calls `sourceService.listSource()`, which iterates raw file-system entries — no RBAC check today. `SourceResource.getFolder()` calls `sourceService.getFolder()` — also unguarded. The Catalog API `CatalogResource.getCatalogItem()` / `getCatalogEntityFromNonPromotedFileOrFolder()` path in `CatalogServiceHelper` returns file/folder entities from `FileSystemPlugin.list()` without an admin check. Adding admin guards in `SourceResource` (the main UI path) and in `CatalogServiceHelper.getCatalogEntityFromNonPromotedFileOrFolder()` covers all access paths. |
| FILE-02 | Non-admin users cannot promote files/folders to datasets | Promotion has two entry points: (1) `SourceResource.saveFormatSettings()` (PUT `/source/{sourceName}/file_format/{path}`) and `SourceResource.saveFolderFormat()` (PUT `/source/{sourceName}/folder_format/{path}`), both call `sourceService.createPhysicalDataset()` — unguarded; (2) `CatalogResource.promoteToDataset()` (POST `/catalog/{id}`) delegates to `CatalogServiceHelper.promoteToDataset()` — unguarded. Both entry points need admin guards. |
</phase_requirements>

---

## Standard Stack

### Core (already present — no new dependencies)

| Class | File | Purpose |
|-------|------|---------|
| `SourceResource` | `dac/backend/src/main/java/com/dremio/dac/resource/SourceResource.java` | Legacy UI REST API — handles file browsing (getSource, getFolder, getFile) and promotion (saveFormatSettings, saveFolderFormat) |
| `CatalogServiceHelper` | `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` | Catalog API service layer — handles `promoteToDataset()` and non-promoted file/folder entity lookup |
| `RbacService` | `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` | `isAdminMember(String userName)` — admin bypass check |
| `DremioConfig` | `common/legacy/src/main/java/com/dremio/config/DremioConfig.java` | `RBAC_ENABLED` boolean flag |
| `UserException` | Common module | Error builder: `UserException.validationError().message("...").buildSilently()` |

### Reference Patterns (established in prior phases)

| Class | Phase | What to Copy |
|-------|-------|--------------|
| `SourcesResource` | Phase 11 | `@Nullable RbacService rbacService` + `@Nullable DremioConfig dremioConfig` injection; three-way null guard |
| `CatalogServiceHelper.createSource()` | Phase 13 | Admin guard before service call (`rbacService != null && dremioConfig != null && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED) && !rbacService.isAdminMember(userName)`) |
| `JobsResource` | Phase 11/12 | Same null guard + `isAdminMember` pattern in resource class |

---

## Architecture Patterns

### Pattern 1: Injecting RBAC into SourceResource

`SourceResource` currently has NO `RbacService` or `DremioConfig` injection. The class is constructed by HK2 (Jersey DI) via `@Inject` on its constructor. The same `@Nullable` injection pattern used in `SourcesResource` applies.

**Current constructor signature** (lines 113-143 of `SourceResource.java`):
```java
@Inject
public SourceResource(
    NamespaceService namespaceService,
    ReflectionAdministrationService.Factory reflectionService,
    SourceService sourceService,
    @PathParam("sourceName") SourceName sourceName,
    QueryExecutor executor,
    SecurityContext securityContext,
    ConnectionReader connectionReader,
    SourceCatalog sourceCatalog,
    FormatTools formatTools,
    BufferAllocatorFactory allocatorFactory,
    OptionManager optionManager,
    Provider<Orphanage.Factory> orphanageFactoryProvider,
    CatalogService catalogService)
    throws SourceNotFoundException {
```

**Required change:** Add `@Nullable RbacService rbacService` and `@Nullable DremioConfig dremioConfig` parameters (two new fields, two new constructor params, two new assignments). Follow `SourcesResource` exactly.

### Pattern 2: Admin Guard in SourceResource

Once injected, the guard helper method is identical to `SourcesResource.getUserAccessiblePaths()` but instead of returning accessible paths, it throws:

```java
// Source: SourcesResource.java pattern adapted for deny-throw semantics
private void requireAdmin(String operation) {
    if (rbacService != null
        && dremioConfig != null
        && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        String userName = securityContext.getUserPrincipal().getName();
        if (!rbacService.isAdminMember(userName)) {
            throw UserException.validationError()
                .message("Permission denied: only administrators can " + operation + ".")
                .buildSilently();
        }
    }
}
```

This guard is called at the TOP of each protected method before any business logic.

### Pattern 3: Admin Guard in CatalogServiceHelper.promoteToDataset()

`CatalogServiceHelper` already has `@Nullable RbacService rbacService` and `@Nullable DremioConfig dremioConfig` fields (injected, lines 282-283). The guard follows the exact same pattern as `createSource()` at line 1855:

```java
// Source: CatalogServiceHelper.java line 1855-1864 (createSource guard — copy this pattern)
if (rbacService != null
    && dremioConfig != null
    && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    String userName = securityContext.getUserPrincipal().getName();
    if (!rbacService.isAdminMember(userName)) {
        throw UserException.validationError()
            .message("Permission denied: only administrators can promote datasets.")
            .buildSilently();
    }
}
```

Add this block at the TOP of `promoteToDataset()` (line 1334) before the `Preconditions.checkArgument` call.

### Pattern 4: Admin Guard in CatalogServiceHelper.getCatalogEntityFromNonPromotedFileOrFolder()

This private method (line 469) serves the Catalog API path for browsing non-promoted files/folders (raw filesystem entries). It is called from `getCatalogEntityByPath()` at line 462 when the path resolves to a non-promoted file/folder in a source.

```java
// CatalogServiceHelper.java line 469
private @NotNull Optional<CatalogEntity> getCatalogEntityFromNonPromotedFileOrFolder(
    List<String> path, @Nullable CatalogPageToken pageToken, Integer maxChildren)
    throws NamespaceException {
    // ADD: admin guard here
    if (rbacService != null
        && dremioConfig != null
        && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        String userName = securityContext.getUserPrincipal().getName();
        if (!rbacService.isAdminMember(userName)) {
            throw UserException.validationError()
                .message("Permission denied: only administrators can browse source files.")
                .buildSilently();
        }
    }
    Optional<CatalogItem> internalItem = getInternalItemByPath(path);
    ...
}
```

### Pattern 5: File Browsing Guard Scope in SourceResource

Three methods in `SourceResource` handle file browsing:

1. `getSource()` at line 152 — when `includeContents=true`, calls `sourceService.listSource()`. Guard should block when `includeContents=true`. Note: `getSource()` also returns source metadata (name, state) which non-admins may need; only the `setContents()` call should be blocked OR the entire method can be blocked. **Recommended: block the entire `getSource()` call when RBAC is enabled and user is non-admin** — since displaying source metadata to non-admin users is already handled by `SourcesResource.getSources()` which filters by accessible paths.

2. `getFolder()` at line 231 — lists folder contents for file-system sources. Full block for non-admins.

3. `getFile()` at line 310 — returns a single file entity. Full block for non-admins.

### Anti-Patterns to Avoid

- **Anti-pattern: guarding only `includeContents=true` case** — If the guard only fires when `includeContents=true`, non-admin users can still call `getSource(includeContents=false)` and get source metadata. This is acceptable but exposes more information than needed. Block the whole method to be consistent with "non-admins cannot interact with raw source file system".
- **Anti-pattern: modifying SourceService** — The guard belongs at the resource layer (request entry point), not buried in `SourceService`. Service layer should remain policy-agnostic.
- **Anti-pattern: forgetting the three-way null guard** — Omitting the null checks on `rbacService` or `dremioConfig` will cause NullPointerExceptions in deployments where RBAC is not enabled (null injection).

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Admin check logic | Custom role-checking code | `rbacService.isAdminMember(userName)` | Already exists with correct ADMIN role semantics |
| Error response | Custom exception types | `UserException.validationError().message(...).buildSilently()` | Established error format from prior phases; mapped by Jersey exception mappers |
| RBAC enable check | Custom flag reading | `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` | Established config key; guards must be no-ops when RBAC is disabled |

---

## Common Pitfalls

### Pitfall 1: SourceResource Constructor Throws SourceNotFoundException

**What goes wrong:** `SourceResource` constructor (line 128) declares `throws SourceNotFoundException`. Adding parameters to the constructor requires updating the constructor signature — but if the DI framework is misconfigured, the resource will not be instantiated and the endpoint will 500.

**How to avoid:** Follow the exact same `@Nullable` injection pattern as `SourcesResource`. HK2 supports `@Nullable` for optional bindings. No configuration changes needed.

### Pitfall 2: Missing Test for RBAC Disabled Case

**What goes wrong:** A test that only covers RBAC-enabled blocking forgets to verify that the guard is a no-op when `rbacService == null` or `dremioConfig.getBoolean(RBAC_ENABLED) == false`. The production code must pass through cleanly in the common (non-RBAC) case.

**How to avoid:** For each new guard, write three test cases: (1) RBAC disabled → no block, (2) RBAC enabled + admin → no block, (3) RBAC enabled + non-admin → throws UserException.

### Pitfall 3: CatalogServiceHelper Already Has RBAC Fields

**What goes wrong:** A developer might add NEW `RbacService` and `DremioConfig` fields to `CatalogServiceHelper` thinking they don't exist, creating duplicate fields and a broken constructor.

**How to avoid:** `CatalogServiceHelper` already has `@Nullable private final RbacService rbacService` (line 282) and `@Nullable private final DremioConfig dremioConfig` (line 283). DO NOT add new fields. Only add new guard calls using the existing fields.

### Pitfall 4: getChildrenForPath Includes Non-Promoted Files

**What goes wrong:** The `CatalogResource.getCatalogItem()` → `CatalogServiceHelper.getCatalogEntityById()` → `getCatalogEntityFromCatalogItem()` → `getListingForInternalItem()` → `getChildrenForPath()` path also lists file children. If the guard is only in `getCatalogEntityFromNonPromotedFileOrFolder()` (called from the by-path endpoint), the by-id path is still open.

**How to avoid:** Also guard `getCatalogEntityFromCatalogItem()` at line 813, which handles both files and folders from internal IDs. OR alternatively, guard `getInternalItemByPath()` at line 856 which is the common entry for all non-promoted file/folder access in the Catalog API.

**Recommended: guard `getCatalogEntityFromNonPromotedFileOrFolder()` AND `getCatalogEntityFromCatalogItem()`** — these two methods together cover all Catalog API browse paths for non-promoted entities.

### Pitfall 5: SQL SHOW FILES Path is Not Affected

**What goes wrong:** Developer spends time trying to find a dedicated `ShowFilesHandler` to add a guard to, or tries to intercept in `CatalogImpl`.

**How to avoid:** `SqlShowFiles` is NOT dispatched to a dedicated direct handler in `CommandCreator.java`. The `OTHER` case falls through to `NormalHandler` which processes it as a standard SQL query through the file system schema layer. This path uses OS-level file permissions (impersonation), not RBAC. It is out of scope for Phase 15 based on the success criteria: "access paths (REST API, SQL, UI)" in success criterion 4 refers to the same underlying REST API that the UI uses, not SQL DDL. No SQL-layer changes are needed.

---

## Code Examples

### Example 1: SourceResource New Fields and Constructor Addition

```java
// Source: SourcesResource.java lines 62-81 (reference pattern)
// Add to SourceResource:
@Nullable private final RbacService rbacService;
@Nullable private final DremioConfig dremioConfig;

// In constructor, add two new params after CatalogService:
@Nullable RbacService rbacService,
@Nullable DremioConfig dremioConfig

// In constructor body:
this.rbacService = rbacService;
this.dremioConfig = dremioConfig;
```

### Example 2: Browse Guard in SourceResource.getSource()

```java
// Source: established pattern from CatalogServiceHelper.createSource() (line 1855)
@GET
@Produces(MediaType.APPLICATION_JSON)
public SourceUI getSource(...) throws Exception {
    // FILE-01: non-admin users cannot browse file contents
    if (rbacService != null
        && dremioConfig != null
        && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        String userName = securityContext.getUserPrincipal().getName();
        if (!rbacService.isAdminMember(userName)) {
            throw UserException.validationError()
                .message("Permission denied: only administrators can browse source files.")
                .buildSilently();
        }
    }
    // ... existing code ...
}
```

### Example 3: Promote Guard in CatalogServiceHelper.promoteToDataset()

```java
// Source: CatalogServiceHelper.java line 1334
public Dataset promoteToDataset(String targetId, Dataset dataset)
    throws NamespaceException, UnsupportedOperationException {
    // FILE-02: non-admin users cannot promote files/folders to datasets
    if (rbacService != null
        && dremioConfig != null
        && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        String userName = securityContext.getUserPrincipal().getName();
        if (!rbacService.isAdminMember(userName)) {
            throw UserException.validationError()
                .message("Permission denied: only administrators can promote datasets.")
                .buildSilently();
        }
    }
    Preconditions.checkArgument(
        dataset.getType() == Dataset.DatasetType.PHYSICAL_DATASET, ...);
    // ... existing code ...
}
```

### Example 4: Promote Guard in SourceResource.saveFormatSettings() and saveFolderFormat()

```java
// Source: SourceResource.java lines 371-391, 480-500
@PUT
@Path("/file_format/{path: .*}")
public FileFormatUI saveFormatSettings(FileFormat fileFormat, @PathParam("path") String path)
    throws NamespaceException, SourceNotFoundException {
    // FILE-02: non-admin users cannot promote files to datasets
    requireAdmin("promote files to datasets");
    // ... existing code ...
}

@PUT
@Path("/folder_format/{path: .*}")
public FileFormatUI saveFolderFormat(FileFormat fileFormat, @PathParam("path") String path)
    throws NamespaceException, SourceNotFoundException {
    // FILE-02: non-admin users cannot promote folders to datasets
    requireAdmin("promote folders to datasets");
    // ... existing code ...
}
```

### Example 5: Unit Test Pattern for SourceResource Guards

```java
// Source: TestCatalogServiceHelper.java setup pattern (lines 240-257)
// New test class: TestSourceResourceRbac.java
@Test
public void testGetSource_nonAdmin_rbacEnabled_throws() throws Exception {
    // Setup: rbacEnabled=true, user is not admin
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.isAdminMember("user")).thenReturn(false);
    // ... construct SourceResource with mocked deps ...
    assertThatThrownBy(() -> sourceResource.getSource(true, false, null, null))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Permission denied");
}

@Test
public void testGetSource_admin_rbacEnabled_passes() throws Exception {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.isAdminMember("admin")).thenReturn(true);
    // ... should not throw ...
}

@Test
public void testGetSource_rbacDisabled_passes() throws Exception {
    // rbacService == null OR dremioConfig returns false -> no block
    // ... should not throw ...
}
```

---

## Complete Map of Methods to Guard

### FILE-01: File Browse Guards

| Class | Method | Line | Operation |
|-------|--------|------|-----------|
| `SourceResource` | `getSource()` | 152 | Lists source top-level contents when `includeContents=true` |
| `SourceResource` | `getFolder()` | 231 | Lists folder contents |
| `SourceResource` | `getFile()` | 310 | Returns a single file entity |
| `CatalogServiceHelper` | `getCatalogEntityFromNonPromotedFileOrFolder()` | 469 | Catalog API browse of non-promoted file/folder (by-path route) |
| `CatalogServiceHelper` | `getCatalogEntityFromCatalogItem()` | 813 | Catalog API browse of file/folder (by-id route and sub-listing) |

### FILE-02: Promote Guards

| Class | Method | Line | Operation |
|-------|--------|------|-----------|
| `SourceResource` | `saveFormatSettings()` | 371 | PUT `file_format` — promotes file to PDS |
| `SourceResource` | `saveFolderFormat()` | 480 | PUT `folder_format` — promotes folder to PDS |
| `CatalogServiceHelper` | `promoteToDataset()` | 1334 | Catalog API POST `/{id}` — promotes file/folder |

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No RBAC on file browsing | Admin-only file browsing when RBAC enabled | Phase 15 (now) | Non-admin users blocked from raw file system exploration |
| No RBAC on dataset promotion | Admin-only promotion when RBAC enabled | Phase 15 (now) | Non-admin users blocked from promoting files/folders to PDS |
| SourcesResource: full source list | SourcesResource: filtered by accessible paths | Phase 11 | Sources visibility already gated; now file contents within sources also gated |

---

## Open Questions

1. **Should `SourceResource.getSource()` block entirely or only block when `includeContents=true`?**
   - What we know: The method returns source metadata (name, state, datasetCount) AND optionally file contents. Non-admins may legitimately need source metadata for display purposes (e.g., showing a source in the sidebar with its state but no file listing).
   - What's unclear: The UI flow for non-admin users when RBAC is enabled — does the source tile need metadata?
   - Recommendation: Block the entire `getSource()` call for simplicity and consistency. Non-admin users interact with sources only through datasets they have been granted access to. If this causes UI breakage, the planner can scope the guard to only fire when `includeContents=true`.

2. **Does `getFolder()` for promoted folders also need a guard?**
   - What we know: `getFolder()` in `SourceResource` returns contents of source folders. For promoted folders (already PDS), the folder is in the namespace and returns dataset children — not raw file listing.
   - What's unclear: Whether the success criteria for FILE-01 ("list folder contents") includes promoted folders.
   - Recommendation: Guard applies to ALL `getFolder()` calls regardless of promotion status, since the method returns raw file-system entries for unpromoted sources and is the same code path.

3. **Is the `getFile()` endpoint (GET `/source/{sourceName}/file/{path}`) actually browsing?**
   - What we know: `getFile()` returns a single `File` entity with its format settings. It is the equivalent of "clicking on a file" in the UI.
   - Recommendation: Guard it (FILE-01 scope). A non-admin should not be able to inspect raw file entities in a source.

---

## Sources

### Primary (HIGH confidence)

- Direct source inspection of `SourceResource.java` (dac/backend/src/main/java/com/dremio/dac/resource/SourceResource.java) — all methods and current injection pattern verified
- Direct source inspection of `CatalogServiceHelper.java` (lines 282-314, 469-478, 813-839, 1334-1417, 1855-1864, 3146-3219) — existing RBAC fields, promote method, browse methods, established guard pattern
- Direct source inspection of `CatalogResource.java` — promoteToDataset endpoint at line 119 calls `catalogServiceHelper.promoteToDataset()`
- Direct source inspection of `SourcesResource.java` — reference implementation of `@Nullable RbacService` and `@Nullable DremioConfig` injection
- Direct source inspection of `CommandCreator.java` (lines 644-764) — confirmed SqlShowFiles falls through to NormalHandler; no dedicated direct handler
- Direct source inspection of `TestCatalogServiceHelper.java` (lines 167-257) — existing `rbacEnabledHelper` test fixture pattern

### Secondary (MEDIUM confidence)

- `TestRbacIntegration.java` — integration test infrastructure (`BaseTestServer`, `runSqlAsAdmin`, `runSqlExpectingFailure` helpers) verified as working pattern for integration tests

---

## Metadata

**Confidence breakdown:**
- Target methods: HIGH — verified by direct line-by-line source inspection
- Injection pattern: HIGH — copied from SourcesResource which is working in production
- SQL path analysis: HIGH — confirmed `SqlShowFiles` has no direct handler, falls through to NormalHandler
- Test approach: HIGH — `TestCatalogServiceHelper.rbacEnabledHelper` fixture is the exact model to follow

**Research date:** 2026-02-23
**Valid until:** 2026-03-25 (stable codebase; changes are incremental)
