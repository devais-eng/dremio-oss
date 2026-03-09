# Architecture Patterns -- Nessie Branch-Aware RESTCATALOG

**Domain:** Dremio OSS -- Adding AT BRANCH/TAG/COMMIT support to the RESTCATALOG source type for Nessie backends
**Researched:** 2026-03-09
**Confidence:** HIGH -- based on direct codebase analysis of Dremio OSS + Iceberg RESTCatalog bytecode decompilation + Nessie official documentation

---

## Executive Summary

The RESTCATALOG source currently creates a single `RESTCatalog` instance at initialization time. This instance has an immutable `prefix` baked into its `ResourcePaths`, which in turn is baked into every REST URL (e.g., `v1/{prefix}/namespaces/...`). For Nessie backends, the prefix encodes the branch/tag reference (e.g., `main`, `dev`). This means **a single RESTCatalog instance can only talk to one branch**.

The solution requires **caching multiple RESTCatalog instances keyed by branch reference**, not modifying the prefix per-request (which is architecturally impossible with the current Iceberg client). This is the same pattern Nessie's own Spark/Trino integrations use when they need to switch branches.

---

## Critical Finding: Prefix Immutability

### Evidence Chain (HIGH confidence -- verified via bytecode decompilation)

1. **`ResourcePaths`** stores `prefix` as `private final String prefix` (set once in constructor)
2. **`RESTSessionCatalog.initialize()`** calls `ResourcePaths.forCatalogProperties(mergedProps)` which reads `properties.get("prefix")` and creates a `new ResourcePaths(prefix)`
3. **`RESTSessionCatalog`** stores `private ResourcePaths paths` -- set once at line 476 of bytecode, never reassigned
4. **Every API call** (listTables, loadTable, listNamespaces, etc.) uses `this.paths.tables(ns)`, `this.paths.table(id)`, etc.
5. **The config endpoint** (`GET /v1/config`) returns the prefix in its response. For Nessie, when you initialize with URI `http://host:port/iceberg/main`, the config response returns `prefix: "main"`.

**Conclusion:** The prefix cannot be changed per-request. A new `RESTCatalog` instance must be created for each distinct branch/tag reference.

### Nessie URI-to-Branch Mapping

Nessie maps the Iceberg REST URI to a branch reference using this pattern:

```
Base:           http://host:port/iceberg
Default branch: http://host:port/iceberg          (uses Nessie's default branch)
Specific branch: http://host:port/iceberg/dev
Branch+warehouse: http://host:port/iceberg/dev|mywarehouse
Warehouse only:  http://host:port/iceberg/|mywarehouse
```

The part after `/iceberg/` becomes the REST prefix. All subsequent REST calls include this prefix in the URL path:
- `GET /v1/dev/namespaces` (list namespaces on branch `dev`)
- `GET /v1/dev/namespaces/myns/tables/mytable` (load table on branch `dev`)
- `GET /v1/config` returns `{ "prefix": "dev", ... }` for a catalog initialized with URI ending in `/dev`

Sources:
- [Nessie Iceberg REST Guide](https://projectnessie.org/guides/iceberg-rest/)
- [Iceberg REST Catalog Spec](https://iceberg.apache.org/rest-catalog-spec/)
- Bytecode decompilation of `org.apache.iceberg.rest.RESTSessionCatalog` and `org.apache.iceberg.rest.ResourcePaths` from iceberg-core-1.7.0

---

## Current RESTCATALOG Architecture (Existing)

### Table Resolution Path: SQL Query to REST API Call

```
SQL: SELECT * FROM restcatalog_source.myns.mytable

1. Parser:      SqlNode parsed, no version context for RESTCATALOG (it's not a VersionedPlugin)
2. CatalogImpl: getTable(key) -> getDatasetConfig(key) or getDatasetHandle(key)
3. CatalogImpl: Checks `plugin.isWrapperFor(VersionedPlugin.class)` -> FALSE for RESTCATALOG
                -> Goes to NON-VERSIONED path
                -> If AT BRANCH/TAG/COMMIT specified, THROWS:
                   "Source '%s' does not support AT BRANCH/TAG/COMMIT specification"
4. IcebergCatalogPlugin.getDatasetHandle(EntityPath):
   -> getCatalogAccessor().getDatasetHandle(components, plugin, options)
5. AbstractRestCatalogAccessor.getDatasetHandle():
   -> tableIdentifierFromDataset(dataset) converts path to Iceberg TableIdentifier
   -> getCatalog().tableExists(tableIdentifier) via the single cached RESTCatalog
   -> getCatalog().loadTable(tableIdentifier)
6. RESTCatalog.loadTable():
   -> Uses ResourcePaths.table(identifier) = "v1/{prefix}/namespaces/{ns}/tables/{name}"
   -> HTTP GET to Nessie server
7. Response:     Table metadata returned, includes metadata-location
8. Plugin:       Wraps in DremioBaseTable with DremioFileIO, returns DatasetHandle
```

### Component Hierarchy

```
RestIcebergCatalogPluginConfig         (config: restEndpointUri, allowedNamespaces)
  |
  v
RestIcebergCatalogPlugin               (extends IcebergCatalogPlugin)
  |-- createCatalog(config) -> IcebergRestCatalogAccessor
  |-- buildCatalogProperties(config) -> {URI, CATALOG_IMPL, user props}
  |-- createRestCatalog(config) -> Supplier<Catalog>   [lazy, via CatalogUtil.loadCatalog()]
  |
  v
IcebergCatalogPlugin                    (implements StoragePlugin, SupportsIcebergRootPointer, etc.)
  |-- start() -> catalogAccessor = createCatalog(fsConf)
  |-- getDatasetHandle() -> catalogAccessor.getDatasetHandle()
  |-- listDatasetHandles() -> catalogAccessor.listDatasetHandles()
  |-- NOTE: Does NOT implement VersionedPlugin
  |
  v
IcebergRestCatalogAccessor (extends AbstractRestCatalogAccessor)
  |-- ExpiringCatalogCache (Supplier<Catalog>) -- single RESTCatalog, expires after N seconds
  |-- loadTable(tableId) -> via Caffeine table cache (keyed by userId + TableIdentifier)
  |-- All operations delegate to getCatalog() = ExpiringCatalogCache.get()
```

### Key Interfaces NOT Implemented by RESTCATALOG

| Interface | Implemented By | Significance |
|-----------|---------------|--------------|
| `VersionedPlugin` | `DataplanePlugin` (Nessie) | Provides `resolveVersionContext()`, branch/tag CRUD, version-aware listing |
| `Wrapper` for `VersionedPlugin` | `NessiePlugin` via `DataplanePlugin` | `isWrapperFor(VersionedPlugin.class)` gates all version-aware code paths in CatalogImpl |

---

## Native Nessie Source: How VersionContext is Consumed

### Interfaces and Methods

1. **`VersionedPlugin.resolveVersionContext(VersionContext)`** -- resolves branch name "dev" to commit hash
2. **`VersionContextResolver.resolveVersionContext(sourceName, VersionContext)`** -- cached resolution in `VersionContextResolverImpl`
3. **`CatalogImpl.getVersionContext(key, TableVersionContext)`** -- converts AT BRANCH/TAG/COMMIT to `VersionContext`
4. **`CatalogImpl.getVersionedDatasetAccessOptions(key, context)`** -- creates `VersionedDatasetAccessOptions` with resolved context
5. **`DataplanePlugin.getDatasetHandle(EntityPath, GetDatasetOption...)`** -- uses `VersionedDatasetAccessOptions` to load table at specific version

### Flow in Native Nessie Source

```
SQL: SELECT * FROM nessie_source.myns.mytable AT BRANCH dev

1. Parser:      Produces TableVersionContext(BRANCH, "dev")
2. CatalogImpl: getTableSnapshot(key, TableVersionContext)
3. CatalogImpl: plugin.isWrapperFor(VersionedPlugin.class) -> TRUE
                -> Goes to VERSIONED path (getTableSnapshotForVersionedSource)
4. CatalogImpl: resolveVersionContext("nessie_source", VersionContext.ofBranch("dev"))
                -> NessieClient.resolveVersionContext() -> ResolvedVersionContext(BRANCH, "dev", hash)
5. CatalogImpl: Builds VersionedDatasetAccessOptions with resolved context
6. DataplanePlugin.getDatasetHandle():
                -> Uses NessieClient API to fetch table content at specific hash
                -> Gets metadata-location from Nessie content
                -> Loads Iceberg TableMetadata from metadata-location
```

---

## Recommended Architecture: Branch-Aware RESTCATALOG

### Design Decision: RESTCatalog Instance Cache per Reference

Since the prefix is immutable, the RESTCATALOG plugin must maintain multiple `RESTCatalog` instances, one per branch/tag reference. This is implemented as a cache inside the `CatalogAccessor` layer.

### Option A: Implement VersionedPlugin on RestIcebergCatalogPlugin (REJECTED)

**Why rejected:** VersionedPlugin requires implementing ~30 methods (branch CRUD, listing, merge, etc.) that interact with the Nessie native API. The RESTCATALOG uses the Iceberg REST protocol, not the Nessie API. Implementing VersionedPlugin would mean duplicating all the NessieClient logic, which defeats the purpose. Additionally, `CatalogImpl` uses `isWrapperFor(VersionedPlugin.class)` to determine the entire code path, and mixing REST catalog semantics into the Nessie plugin code path would be invasive and fragile.

### Option B: New Intermediate Interface (RECOMMENDED)

Create a narrower interface that represents "a source whose tables can be scoped to a branch/tag/commit via the Iceberg REST prefix mechanism."

```java
/**
 * Marker for StoragePlugins that support branch-aware table loading
 * via REST catalog prefix manipulation (not native Nessie API).
 */
public interface SupportsBranchAwareRestCatalog {
    /**
     * Resolve a version context string to a valid reference for this catalog.
     * For Nessie REST: validates the branch/tag exists via list-references or config endpoint.
     */
    ResolvedVersionContext resolveVersionContext(VersionContext versionContext);

    /**
     * Get a CatalogAccessor scoped to a specific branch/tag reference.
     * Implementations should cache these accessors.
     */
    CatalogAccessor getCatalogAccessorForReference(String reference);

    /**
     * Get the default branch for this catalog.
     */
    String getDefaultBranch();
}
```

### Component Changes Overview

```
                        EXISTING                          NEW/MODIFIED
                        --------                          ------------
SQL Parser              TableVersionContext               (no change - already produces it)
                             |
                             v
CatalogImpl             isWrapperFor(VersionedPlugin)     + isWrapperFor(SupportsBranchAwareRestCatalog)
                             |                            |
                             v                            v
                        VERSIONED path               NEW BRANCH-AWARE REST path
                        (NessieClient)               (prefix-based catalog selection)
                             |                            |
                             v                            v
DataplanePlugin         resolveVersionContext         RestIcebergCatalogPlugin (modified)
                        getNessieContent()            + implements SupportsBranchAwareRestCatalog
                                                      + BranchAwareCatalogAccessorCache (new)
                                                           |
                                                           v
                                                      RESTCatalog per branch
                                                      (cached ExpiringCatalogCache instances)
```

---

## New Components

### 1. `SupportsBranchAwareRestCatalog` Interface

**Location:** `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/SupportsBranchAwareRestCatalog.java`

**Purpose:** Narrow interface for REST catalog plugins that can scope operations to a branch via prefix manipulation.

```java
public interface SupportsBranchAwareRestCatalog extends Wrapper {
    ResolvedVersionContext resolveVersionContext(VersionContext versionContext);
    CatalogAccessor getCatalogAccessorForReference(String reference);
    String getDefaultBranch();
}
```

### 2. `BranchAwareCatalogAccessorCache`

**Location:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/BranchAwareCatalogAccessorCache.java`

**Purpose:** Manages a cache of `CatalogAccessor` instances, each bound to a specific branch/tag prefix.

```java
public class BranchAwareCatalogAccessorCache implements AutoCloseable {
    // Cache<String reference, CatalogAccessor> -- e.g., "main" -> accessor with prefix "main"
    // Each accessor wraps a RESTCatalog initialized with URI + "/{reference}"
    // Expiry matches RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS

    CatalogAccessor getOrCreate(String reference, Supplier<CatalogAccessor> factory);
    CatalogAccessor getDefault(); // Uses default branch
    void invalidate(String reference);
    void close(); // Closes all cached accessors
}
```

**Key design choices:**
- Uses Caffeine cache with TTL matching `RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS`
- Maximum cache size configurable (new option, default ~10 branches)
- Each cached entry is a full `IcebergRestCatalogAccessor` with its own `ExpiringCatalogCache<RESTCatalog>`
- The factory lambda constructs a new `RESTCatalog` with URI = `{baseUri}/{reference}`

### 3. `NessieRestVersionContextResolver`

**Location:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/NessieRestVersionContextResolver.java`

**Purpose:** Resolves version context by probing the Nessie Iceberg REST endpoint. Unlike the native Nessie client which calls the Nessie API, this uses the Iceberg REST config endpoint to validate references.

```java
public class NessieRestVersionContextResolver {
    // For BRANCH "dev": Construct URI "{baseUri}/dev", call GET /v1/config
    //   - If 200: reference exists, extract prefix from response
    //   - If 404/error: reference not found
    // Returns ResolvedVersionContext with the validated reference

    // For TAG: same pattern with tag name
    // For COMMIT: Nessie REST doesn't directly support commit-based prefix,
    //   so commit resolution may need the Nessie API v2 endpoint as fallback
}
```

**IMPORTANT LIMITATION:** Nessie's Iceberg REST endpoint maps prefix to branch/tag names. Commit hash resolution (AT COMMIT) is NOT directly supported by the prefix mechanism. Options:
- **(a)** Only support AT BRANCH and AT TAG initially (defer AT COMMIT)
- **(b)** Use the Nessie API v2 endpoint (`/api/v2/trees`) alongside the REST catalog for commit resolution
- **(c)** If Nessie supports detached references (commit hashes as prefix), use them directly

**Recommendation:** Start with AT BRANCH and AT TAG only. AT COMMIT can be added later if Nessie supports it or via a separate Nessie API v2 connection.

---

## Modified Components

### 1. `RestIcebergCatalogPluginConfig` (Modified)

**File:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java`

**Changes:**
```java
@Tag(13)
@DisplayMetadata(label = "Enable Nessie Branch Awareness")
public boolean enableNessieBranchAwareness = false;

@Tag(14)
@DisplayMetadata(label = "Default Branch")
public String defaultBranch = "main";
```

**Rationale:** Opt-in flag so existing RESTCATALOG sources (non-Nessie backends like Polaris, Gravitino, etc.) are unaffected. The `defaultBranch` allows configuration of the default reference when no AT clause is specified.

**UI change:** Add new fields to `restcatalog-layout.json` under a "Nessie Options" section, visible only when branch awareness is enabled.

### 2. `RestIcebergCatalogPlugin` (Modified)

**File:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java`

**Changes:**
- Implement `SupportsBranchAwareRestCatalog` when `enableNessieBranchAwareness` is true
- Add `BranchAwareCatalogAccessorCache` field
- Override `getCatalogAccessor()` to return branch-scoped accessor when version context is available
- Add `getCatalogAccessorForReference(String ref)` method
- Add `resolveVersionContext(VersionContext)` method
- Modify `createRestCatalog(config)` to accept an optional reference parameter for URI construction

```java
public class RestIcebergCatalogPlugin extends IcebergCatalogPlugin
    implements SupportsBranchAwareRestCatalog {  // NEW: conditional implementation

    private BranchAwareCatalogAccessorCache branchCache; // NEW

    @Override
    public CatalogAccessor getCatalogAccessorForReference(String reference) {
        if (!isNessieBranchAware()) {
            return getCatalogAccessor(); // fallback to default
        }
        return branchCache.getOrCreate(reference,
            () -> createBranchScopedAccessor(reference));
    }

    private CatalogAccessor createBranchScopedAccessor(String reference) {
        String branchUri = restEndpoint + "/" + reference;
        // Build properties with URI pointing to branch-specific endpoint
        Map<String, String> props = new HashMap<>(buildCatalogProperties(getFsConfCopy()));
        props.put(CatalogProperties.URI, branchUri);
        Supplier<Catalog> catalogSupplier = () ->
            CatalogUtil.loadCatalog(restCatalogImpl().getName(), catalogName(), props, getFsConfCopy());
        return new IcebergRestCatalogAccessor(catalogSupplier, optionManager,
            getAllowedNamespaces(), isRecursiveAllowedNamespaces());
    }
}
```

### 3. `CatalogImpl` (Modified)

**File:** `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java`

**Changes needed at key decision point (line ~800):**

Current code:
```java
if (plugin.getPlugin().get().isWrapperFor(VersionedPlugin.class)) {
    return getTableSnapshotForVersionedSource(plugin, key, context);
} else {
    return getTableSnapshotForNonVersionedSource(plugin, key, context);
}
```

Modified code:
```java
if (plugin.getPlugin().get().isWrapperFor(VersionedPlugin.class)) {
    return getTableSnapshotForVersionedSource(plugin, key, context);
} else if (plugin.getPlugin().get().isWrapperFor(SupportsBranchAwareRestCatalog.class)
           && context != null && !context.isTimeTravelType()) {
    return getTableSnapshotForBranchAwareRestSource(plugin, key, context);
} else {
    return getTableSnapshotForNonVersionedSource(plugin, key, context);
}
```

The new `getTableSnapshotForBranchAwareRestSource` method:
```java
private DremioTable getTableSnapshotForBranchAwareRestSource(
    ManagedStoragePlugin plugin, NamespaceKey key, TableVersionContext context) {

    SupportsBranchAwareRestCatalog branchPlugin =
        plugin.getPlugin().get().unwrap(SupportsBranchAwareRestCatalog.class);

    // Resolve version context (validate branch/tag exists)
    VersionContext versionContext = context.asVersionContext();
    ResolvedVersionContext resolved = branchPlugin.resolveVersionContext(versionContext);

    // Get branch-scoped catalog accessor
    String reference = resolved.getRefName();
    CatalogAccessor accessor = branchPlugin.getCatalogAccessorForReference(reference);

    // Load table via branch-scoped accessor
    // ... (similar to existing getDatasetHandle flow but using scoped accessor)
}
```

**Additional modification points in CatalogImpl:**
- Line ~890: Remove the error throw for non-versioned sources when AT BRANCH is specified (gate it behind the new interface check)
- Line ~1003-1010: Similar branching for `getDatasetHandleHelper`

### 4. `AbstractRestCatalogAccessor` Table Cache (Modified)

**File:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/AbstractRestCatalogAccessor.java`

**Changes:**
- The existing `CatalogAccessorTableCacheKey` uses `(userId, TableIdentifier)`. When branch awareness is active, tables on different branches with the same name are different tables. Since each branch gets its own `CatalogAccessor` instance (via `BranchAwareCatalogAccessorCache`), the table cache within each accessor is naturally scoped to one branch. **No change needed to the cache key.**

---

## Data Flow: SQL to REST API (Branch-Aware)

```
SQL: SELECT * FROM restcatalog_source.myns.mytable AT BRANCH dev

1. Parser:          Produces TableVersionContext(BRANCH, "dev")
                    (no change from existing behavior)

2. CatalogImpl:     getTableSnapshot(key, TableVersionContext(BRANCH, "dev"))

3. CatalogImpl:     plugin.isWrapperFor(VersionedPlugin.class) -> FALSE
                    plugin.isWrapperFor(SupportsBranchAwareRestCatalog.class) -> TRUE (NEW)
                    context.isTimeTravelType() -> FALSE

4. CatalogImpl:     NEW CODE PATH: getTableSnapshotForBranchAwareRestSource()
                    -> branchPlugin.resolveVersionContext(VersionContext.ofBranch("dev"))
                    -> Validates "dev" branch exists via Nessie REST config endpoint
                    -> Returns ResolvedVersionContext(BRANCH, "dev", null)

5. Plugin:          branchPlugin.getCatalogAccessorForReference("dev")
                    -> BranchAwareCatalogAccessorCache.getOrCreate("dev", ...)
                    -> Cache MISS: creates new IcebergRestCatalogAccessor
                       with URI = "http://nessie:19120/iceberg/dev"
                    -> RESTCatalog.initialize() called
                       -> GET http://nessie:19120/iceberg/v1/config
                       -> Response: { "prefix": "dev", ... }
                       -> ResourcePaths created with prefix = "dev"

6. Accessor:        accessor.getDatasetHandle(["restcatalog_source", "myns", "mytable"], plugin)
                    -> tableIdentifierFromDataset() -> TableIdentifier("myns", "mytable")
                    -> getCatalog().loadTable(tableId)
                    -> RESTCatalog: GET http://nessie:19120/iceberg/v1/dev/namespaces/myns/tables/mytable
                    -> Returns table metadata for "mytable" on branch "dev"

7. Response:        DatasetHandle returned, table metadata loaded at branch "dev" snapshot
```

---

## Plan Cache Implications

### Current State

The current plan cache in `PlanCacheUtils` has this logic:
```java
if (optionResolver.getOption(PlannerSettings.QUERY_PLAN_USE_LEGACY_CACHE)
    && checkForVersionedTable(config)) {
    // NOT_PUT_VERSIONED_TABLE -- skip caching entirely
    return false;
}
```

For non-versioned sources (which RESTCATALOG currently is), plans ARE cached, and the cache key does not include version context information.

### Required Changes

When branch awareness is enabled, two queries for the same table on different branches must NOT share cached plans:

```sql
SELECT * FROM rc.myns.t AT BRANCH main    -- Plan A
SELECT * FROM rc.myns.t AT BRANCH dev     -- Plan B (different data!)
```

**Options (in order of preference):**

**Option 1: Include branch reference in plan cache key hash (RECOMMENDED)**

Modify `PlanCacheUtils.computeKey()` to include the resolved branch reference for tables from `SupportsBranchAwareRestCatalog` sources. The key hash already includes the SQL text (which includes `AT BRANCH dev`), so queries with explicit AT clauses will naturally have different cache keys. However, the session-level default branch must also be included:

```java
// In PlanCacheKey computation:
// For SupportsBranchAwareRestCatalog sources, include the resolved default branch
// so that "SELECT * FROM rc.t" with USE BRANCH dev vs USE BRANCH main get different keys
hasher.putString(resolvedBranchForSource, UTF_8);
```

**Option 2: Disable plan cache for branch-aware REST sources (SIMPLER, MVP)**

Follow the same pattern as versioned sources: detect `SupportsBranchAwareRestCatalog` in `checkForVersionedTable()` and return `false` to skip caching. This is simpler but sacrifices performance.

**Recommendation:** Start with Option 2 for MVP, then implement Option 1 as a follow-up optimization.

### Metadata Cache (KV Store)

The RESTCATALOG currently stores dataset metadata in the Dremio namespace KV store (unlike versioned sources which don't). When branch awareness is enabled, the same table path can map to different metadata on different branches. This needs careful handling:

- **Option A:** Prefix the namespace path with branch info (e.g., `__branch_dev__.myns.mytable`)
- **Option B:** Store only the default branch's metadata in KV, and always do fresh loads for non-default branches
- **Option C:** Add branch as a dimension in the KV store key

**Recommendation:** Option B for simplicity. Non-default branch access should always load fresh metadata from the REST catalog, bypassing the KV store cache. This avoids complex KV schema changes.

---

## RBAC Interaction with Branch-Specific Access

### Current State

`IcebergCatalogPlugin.hasAccessPermission()` returns `true` unconditionally with `// TODO: implement RBAC`. The RBAC enforcement for the RESTCATALOG source is entirely external (delegated to the remote catalog server via HTTP 401/403 responses).

### Branch-Aware RBAC Considerations

1. **Server-side RBAC:** Nessie supports branch-level authorization. When Dremio sends REST requests to a branch-scoped endpoint (e.g., `GET /v1/dev/namespaces/.../tables/...`), Nessie's server-side authorization decides whether the user can access that branch. This means branch-level RBAC is naturally delegated to Nessie.

2. **Dremio-side RBAC:** The existing Dremio RBAC system (privileges on sources/datasets) does not have a concept of per-branch privileges. If RBAC is enabled in Dremio:
   - `SELECT` privilege on the source grants access to ALL branches (since the source is the access unit)
   - Per-branch privilege checks would require a new privilege model, which is out of scope for this milestone

3. **Credential forwarding:** For Nessie to enforce per-user authorization, Dremio must forward user identity in REST requests. The current RESTCATALOG uses shared credentials (configured in the source). Per-user credential delegation is a separate concern.

**Recommendation:** For this milestone, treat branch-level RBAC as delegated to the Nessie server. No Dremio-side per-branch RBAC changes needed. Document this as a known limitation.

---

## Patterns to Follow

### Pattern 1: ExpiringCatalogCache (Existing, Extend)

**What:** Memoizing supplier with TTL-based expiration, already used for single RESTCatalog instance.
**When:** Use as the inner cache for each branch-scoped accessor in `BranchAwareCatalogAccessorCache`.
**Example:**
```java
// BranchAwareCatalogAccessorCache uses Caffeine externally, ExpiringCatalogCache internally
LoadingCache<String, CatalogAccessor> branchAccessors = Caffeine.newBuilder()
    .maximumSize(maxBranches)
    .expireAfterAccess(expireSeconds, TimeUnit.SECONDS)
    .removalListener((key, value, cause) -> closeAccessor(value))
    .build(reference -> createBranchScopedAccessor(reference));
```

### Pattern 2: CatalogImpl Version-Aware Dispatch (Existing, Extend)

**What:** CatalogImpl uses `isWrapperFor(VersionedPlugin.class)` to dispatch between versioned and non-versioned code paths.
**When:** Add a third dispatch branch for `SupportsBranchAwareRestCatalog`.
**Why this pattern:** Keeps the existing code paths unchanged, avoids breaking non-Nessie RESTCATALOG sources.

### Pattern 3: Opt-In Feature Flag (Existing Pattern)

**What:** All RESTCATALOG features are gated behind `BooleanValidator` options.
**When:** Gate branch awareness behind `enableNessieBranchAwareness` config + a system-level support key.
**Example:** `RESTCATALOG_PLUGIN_NESSIE_BRANCH_AWARE_ENABLED`

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Monkey-Patching RESTCatalog Internals

**What:** Trying to use reflection or custom subclass to change the `prefix` field on an existing `RESTCatalog` instance.
**Why bad:** The `paths` field in `RESTSessionCatalog` is private, the `prefix` in `ResourcePaths` is final. Any reflection-based approach would be fragile across Iceberg version upgrades and violates the class contract.
**Instead:** Create separate RESTCatalog instances per branch, as designed.

### Anti-Pattern 2: Full VersionedPlugin Implementation

**What:** Making `RestIcebergCatalogPlugin` implement `VersionedPlugin` to reuse all existing Nessie code paths.
**Why bad:** VersionedPlugin requires ~30 methods (branch CRUD, merge, listing changes, etc.) that use the Nessie native API. The RESTCATALOG speaks Iceberg REST, not Nessie API. This would create a confusing hybrid that mixes two different protocol layers.
**Instead:** Create the narrow `SupportsBranchAwareRestCatalog` interface.

### Anti-Pattern 3: Modifying URI at Runtime Without Re-initialization

**What:** Changing `CatalogProperties.URI` on an existing RESTCatalog and expecting it to work.
**Why bad:** The URI is read during `initialize()`, which calls `GET /v1/config`, negotiates auth tokens, creates `ResourcePaths`, etc. Changing the URI after init does not re-trigger any of this setup.
**Instead:** Create a new RESTCatalog instance with the updated URI.

---

## Build Order (Dependency-Based)

### Phase 1: Foundation (No Behavioral Changes)

1. **`SupportsBranchAwareRestCatalog` interface** -- Define the contract. No implementations yet.
2. **`BranchAwareCatalogAccessorCache`** -- Cache infrastructure for branch-scoped accessors.
3. **Unit tests** for cache behavior (expiry, concurrent access, cleanup).

### Phase 2: Plugin Integration

4. **Modify `RestIcebergCatalogPluginConfig`** -- Add `enableNessieBranchAwareness`, `defaultBranch` fields.
5. **Modify `RestIcebergCatalogPlugin`** -- Implement `SupportsBranchAwareRestCatalog`, wire `BranchAwareCatalogAccessorCache`.
6. **Add `NessieRestVersionContextResolver`** -- Implement branch validation via REST config endpoint.
7. **Modify `restcatalog-layout.json`** -- Add UI fields for Nessie options.
8. **Integration tests** -- Test branch-scoped accessor creation against a real Nessie instance.

### Phase 3: CatalogImpl Integration

9. **Modify `CatalogImpl.getTableSnapshotHelper()`** -- Add third dispatch branch.
10. **Modify `CatalogImpl.getTableSnapshotForNonVersionedSource()`** -- Remove the "does not support AT BRANCH" error for `SupportsBranchAwareRestCatalog` plugins.
11. **Add `CatalogImpl.getTableSnapshotForBranchAwareRestSource()`** -- New method for branch-aware table loading.
12. **Modify `CatalogImpl.getDatasetHandleHelper()`** -- Similar dispatch for dataset handle retrieval.
13. **End-to-end tests** -- SQL queries with AT BRANCH against Nessie REST.

### Phase 4: Cache and Polish

14. **Plan cache handling** -- Disable plan cache for branch-aware sources (MVP) or include branch in key.
15. **Metadata validity** -- Ensure `isIcebergMetadataValid()` works correctly with branch-scoped accessors.
16. **Error handling** -- Proper error messages for invalid branches, connection failures, etc.
17. **Documentation** -- Source configuration guide for Nessie branch awareness.

---

## Scalability Considerations

| Concern | At 2 Branches | At 10 Branches | At 100 Branches |
|---------|--------------|----------------|-----------------|
| Memory (RESTCatalog instances) | ~2MB | ~10MB | ~100MB (need eviction) |
| Connection overhead | Negligible | Moderate (10 HTTP sessions) | High (cache size limit critical) |
| Config endpoint calls | 2 calls at first access | 10 calls | 100 calls (throttling risk) |
| Table cache | 2x cache entries | 10x cache entries | Cap at cache size limit |

**Mitigation:** The `BranchAwareCatalogAccessorCache` Caffeine cache with `maximumSize(10)` and `expireAfterAccess(5min)` prevents unbounded growth. Most users access 2-3 branches actively.

---

## Open Questions Requiring Phase-Specific Research

1. **Commit hash as prefix:** Does Nessie support using a commit hash as the REST prefix? If so, AT COMMIT is trivially supported. If not, need a separate resolution mechanism. **Action:** Test against Nessie 0.96+ with a commit hash in the URI.

2. **Token refresh per branch:** Does each RESTCatalog instance maintain its own OAuth2 token session? If the Nessie server uses per-branch OAuth scoping, multiple instances may cause excessive token refresh. **Action:** Verify during integration testing.

3. **View handling:** Iceberg Views loaded via the REST catalog should also be branch-aware. The existing `IcebergCatalogViewProvider` needs to be verified that it works with branch-scoped accessors. **Action:** Include view tests in Phase 3.

4. **Metadata KV store:** When AT BRANCH is used, should the loaded metadata be stored in Dremio's namespace KV store? If yes, the key must include the branch. If no, every query incurs a REST round-trip. **Action:** Decide during Phase 4 based on performance testing.

---

## Sources

- Dremio OSS codebase (direct analysis):
  - `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/*.java`
  - `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java`
  - `sabot/kernel/src/main/java/com/dremio/exec/catalog/VersionedPlugin.java`
  - `sabot/kernel/src/main/java/com/dremio/exec/catalog/VersionContextResolverImpl.java`
  - `plugins/dataplane/src/main/java/com/dremio/plugins/dataplane/store/DataplanePlugin.java`
  - `plugins/dataplane/src/main/java/com/dremio/plugins/dataplane/store/NessiePlugin.java`
- Iceberg RESTCatalog bytecode (iceberg-core-1.7.0):
  - `org.apache.iceberg.rest.RESTCatalog` -- decompiled
  - `org.apache.iceberg.rest.RESTSessionCatalog` -- decompiled
  - `org.apache.iceberg.rest.ResourcePaths` -- decompiled, confirmed `prefix` is `private final`
- [Nessie Iceberg REST Configuration Guide](https://projectnessie.org/guides/iceberg-rest/)
- [Iceberg REST Catalog Spec](https://iceberg.apache.org/rest-catalog-spec/)
- [Nessie REST Catalog for Apache Iceberg (Dremio Blog)](https://www.dremio.com/blog/use-nessie-with-iceberg-rest-catalog/)
