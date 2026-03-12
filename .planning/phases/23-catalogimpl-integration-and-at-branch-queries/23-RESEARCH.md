# Phase 23: CatalogImpl Integration and AT BRANCH Queries - Research

**Researched:** 2026-03-10
**Domain:** CatalogImpl dispatch logic for AT BRANCH/TAG/COMMIT on RESTCATALOG sources, SupportsBranchAwareRestCatalog interface, plan cache safety
**Confidence:** HIGH

## Summary

Phase 23 is the critical integration phase that wires together the Phase 21 (config + detection) and Phase 22 (branch cache infrastructure) work to make AT BRANCH queries actually function end-to-end. The core challenge is that `CatalogImpl` dispatches AT BRANCH/TAG/COMMIT queries using a binary `isWrapperFor(VersionedPlugin.class)` check: versioned sources go down one path, non-versioned sources throw an error ("Source does not support AT BRANCH/TAG/COMMIT"). The RESTCATALOG source is currently non-versioned, so AT BRANCH queries produce this error.

The solution requires three coordinated changes: (1) Define a `SupportsBranchAwareRestCatalog` interface that `RestIcebergCatalogPlugin` implements when Nessie is detected, providing `resolveVersionContext()`, `getCatalogAccessorForBranch()`, and `getDefaultBranch()`. (2) Modify the dispatch logic in `CatalogImpl.getTableSnapshotHelper()` (and parallel methods like `getDatasetHandleHelper()`) to add a third code path that checks `isWrapperFor(SupportsBranchAwareRestCatalog.class)` before falling through to the non-versioned error. (3) Handle plan cache safety by making `SupportsBranchAwareRestCatalog` sources trigger the existing versioned-table exclusion logic in `PlanCacheUtils.checkForVersionedTable()`, which already skips caching entirely for versioned sources.

The `CatalogUtil.forATSpecifierAccess()` method is a critical gate: it currently returns `true` for AT specifiers only when the source `isWrapperFor(VersionedPlugin.class)` OR the context is a time-travel type (SNAPSHOT/TIMESTAMP). For branch-aware REST catalog sources, this method must also return `true` when the source `isWrapperFor(SupportsBranchAwareRestCatalog.class)` and the context is BRANCH/TAG/COMMIT. Without this change, `CatalogImpl.getTable(CatalogEntityKey)` would skip the `getTableSnapshot()` path entirely and fall through to `getTable(NamespaceKey)`, which ignores the version context.

For the default branch (BRQ-02), when no AT BRANCH is specified, the query must transparently use the server-defined default branch. This means the default RESTCATALOG accessor (created in Phase 21) already serves data from the Nessie default branch by design -- the base endpoint `http://nessie:19120/iceberg` resolves to the default branch. This makes CMP-02 ("Nessie-enabled source without AT BRANCH behaves identically to non-Nessie source") a natural consequence of the architecture, requiring no special code. However, BRQ-02 also requires "always fresh" default branch resolution -- meaning the default branch name must be re-read from the server, not cached across queries. For MVP, since the base endpoint implicitly uses the server's current default branch on each REST call, freshness is achieved without re-detecting.

**Primary recommendation:** Create `SupportsBranchAwareRestCatalog` interface in `sabot/kernel` module. Modify `CatalogImpl.getTableSnapshotHelper()`, `CatalogImpl.getDatasetHandleHelper()`, and `CatalogUtil.forATSpecifierAccess()` to recognize the new interface. Implement the interface on `RestIcebergCatalogPlugin` conditionally based on `isNessieDetected()`. For plan cache (INF-03), extend `PlanCacheUtils.checkForVersionedTable()` to also detect `SupportsBranchAwareRestCatalog` sources and skip caching -- the simplest correct approach for MVP.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BRQ-01 | User can SELECT from a table AT BRANCH `<name>` on a Nessie-enabled RESTCATALOG source | Modify `CatalogImpl.getTableSnapshotHelper()` to add a third dispatch branch for `SupportsBranchAwareRestCatalog`. The new path calls `getCatalogAccessorForBranch(branchName)` (from Phase 22 cache), then loads the table via the branch-scoped accessor. Also modify `CatalogUtil.forATSpecifierAccess()` to recognize the new interface so that `getTable(CatalogEntityKey)` routes to `getTableSnapshot()` instead of `getTable(NamespaceKey)`. |
| BRQ-02 | Queries without explicit AT BRANCH use the server-defined default branch (always fresh, never cached across queries) | The base RESTCATALOG endpoint (`http://nessie:19120/iceberg` without branch suffix) already resolves to the server's current default branch on every REST call. Queries without AT BRANCH follow the existing non-versioned `getTable(NamespaceKey)` path which uses `getCatalogAccessor()` (the default accessor), which points to the base endpoint. Freshness is inherent because the Nessie server resolves the default branch on each request. No new code needed beyond what Phase 21 established. |
| INF-03 | Plan cache correctly handles branch-aware queries (no stale cross-branch plan reuse) | Extend `PlanCacheUtils.checkForVersionedTable()` (or the `CatalogUtil.requestedPluginSupportsVersionedTables()` method it calls) to also return `true` for sources that `isWrapperFor(SupportsBranchAwareRestCatalog.class)`. This causes the plan cache to skip caching entirely for queries touching Nessie-enabled RESTCATALOG sources, matching the same behavior as native Nessie sources. The existing `NOT_PUT_VERSIONED_TABLE` event is reused. |
| CMP-02 | Nessie-enabled source without AT BRANCH behaves identically to current non-Nessie source (default branch serves same data) | Achieved architecturally: queries without AT BRANCH follow the existing non-versioned path through `DatasetManager.getTable()` -> `IcebergCatalogPlugin.getDatasetHandle()` -> `getCatalogAccessor()`. The default accessor uses the base endpoint which Nessie resolves to the default branch. This is identical to how non-Nessie sources work. The `SupportsBranchAwareRestCatalog` interface is only activated when AT BRANCH/TAG/COMMIT is specified. |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `CatalogImpl` | Dremio kernel | Central dispatch point for table resolution, AT specifier routing | All table lookups flow through this; the three-way dispatch (versioned/branch-aware/non-versioned) is the key integration point |
| `PlanCacheUtils` | Dremio kernel | Plan cache inclusion/exclusion logic | Already has `checkForVersionedTable()` pattern to exclude versioned sources; extend for branch-aware sources |
| `CatalogUtil` | Dremio kernel | Utility methods for AT specifier access detection, plugin support checks | `forATSpecifierAccess()` and `requestedPluginSupportsVersionedTables()` are the gates that determine query routing |
| `Wrapper` interface | dremio-common-core | `isWrapperFor()` / `unwrap()` pattern for plugin capability detection | Standard Dremio pattern; `VersionedPlugin`, `SupportsIcebergRootPointer`, etc. all use this |
| `TableVersionContext` / `TableVersionType` | services/catalog | Type-safe representation of AT BRANCH/TAG/COMMIT/SNAPSHOT/TIMESTAMP | Parser produces these; `isTimeTravelType()` distinguishes SNAPSHOT/TIMESTAMP from BRANCH/TAG/COMMIT |
| `VersionContext` / `ResolvedVersionContext` | services/catalog | Version context resolution chain | `VersionContext.ofBranch("dev")` created from `TableVersionContext.asVersionContext()` |
| Caffeine Cache | 3.2.0 | Branch accessor cache (from Phase 22) | Already in use; Phase 23 consumes it, does not modify |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| SLF4J Logger | (transitive) | Logging branch resolution, dispatch decisions | Debug logging for troubleshooting version resolution |
| Guava Preconditions | 33.4.0-jre | Argument validation in new interface implementations | Validate branch names, check Nessie detection state |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| New `SupportsBranchAwareRestCatalog` interface | Implement full `VersionedPlugin` on RestIcebergCatalogPlugin | VersionedPlugin has 25+ methods (branch CRUD, merge, listing, etc.) that use Nessie native API. RESTCATALOG speaks Iceberg REST, not Nessie API. Implementing VersionedPlugin would create a broken hybrid. The narrow 3-method interface is correct. |
| Skip plan cache for branch-aware sources (MVP) | Include branch name in plan cache key hash | Including branch in key is the optimal long-term solution but adds complexity (need to extract resolved branch from query context). Skipping is safe and matches existing VersionedPlugin behavior. Can optimize later. |
| Third dispatch branch in `getTableSnapshotHelper()` | Override `isWrapperFor(VersionedPlugin.class)` to return true | Would force RestIcebergCatalogPlugin into the full VersionedPlugin code path which calls `resolveVersionContext()` -> `VersionedPlugin.resolveVersionContext()` -> `NessieClient`. The REST catalog has no NessieClient, so this would crash. |

**No new dependencies needed for Phase 23.**

## Architecture Patterns

### Recommended Project Structure

```
sabot/kernel/src/main/java/com/dremio/exec/catalog/
  SupportsBranchAwareRestCatalog.java     # NEW: Narrow interface (3 methods)
  CatalogImpl.java                        # MODIFIED: Add third dispatch branch
  CatalogUtil.java                        # MODIFIED: Extend forATSpecifierAccess() and requestedPluginSupportsVersionedTables()

sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/
  PlanCacheUtils.java                     # MODIFIED: Extend checkForVersionedTable() for SupportsBranchAwareRestCatalog

plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/
  RestIcebergCatalogPlugin.java           # MODIFIED: Implement SupportsBranchAwareRestCatalog
```

### Pattern 1: SupportsBranchAwareRestCatalog Interface

**What:** A narrow interface (3 methods) that represents "a source whose tables can be scoped to a branch/tag via REST prefix manipulation."
**When to use:** Checked by `CatalogImpl` when dispatching AT BRANCH/TAG/COMMIT queries that are NOT time-travel types.
**Key constraint:** This interface extends `Wrapper` (not `VersionedPlugin`) to enable the `isWrapperFor()` / `unwrap()` pattern. The default `Wrapper.isWrapperFor()` implementation uses `instanceof`, so simply implementing the interface is sufficient -- no custom `isWrapperFor()` override needed on `RestIcebergCatalogPlugin`.

**However:** There is a subtle issue. `RestIcebergCatalogPlugin` only supports branch-aware access when `isNessieDetected()` returns true. If a source has `enableNessie=true` but the backend is not Nessie (or detection failed), `isNessieDetected()` is false. The `SupportsBranchAwareRestCatalog` interface should NOT be unconditionally implemented on the class. Instead, override `isWrapperFor()` and `unwrap()` on `RestIcebergCatalogPlugin` to return true/this for `SupportsBranchAwareRestCatalog.class` only when `isNessieDetected` is true.

**Example:**
```java
// In sabot/kernel -- the interface definition
public interface SupportsBranchAwareRestCatalog extends Wrapper {
    ResolvedVersionContext resolveVersionContext(VersionContext versionContext);
    CatalogAccessor getCatalogAccessorForBranch(String branchName);
    String getDefaultBranch();
}

// In RestIcebergCatalogPlugin -- conditional implementation
@Override
public boolean isWrapperFor(Class<?> clazz) {
    if (SupportsBranchAwareRestCatalog.class.equals(clazz)) {
        return isNessieDetected;
    }
    return super.isWrapperFor(clazz);
}

@Override
public <T> T unwrap(Class<T> clazz) {
    if (SupportsBranchAwareRestCatalog.class.equals(clazz) && isNessieDetected) {
        return clazz.cast(this);
    }
    return super.unwrap(clazz);
}
```

### Pattern 2: Three-Way Dispatch in CatalogImpl

**What:** The existing binary dispatch (`isWrapperFor(VersionedPlugin.class)` -> versioned path, else -> non-versioned path) becomes a three-way dispatch with a new middle branch for `SupportsBranchAwareRestCatalog`.

**When to use:** In every CatalogImpl method that currently dispatches on `isWrapperFor(VersionedPlugin.class)`: `getTableSnapshotHelper()`, `getDatasetHandleHelper()`, and related methods.

**Example:**
```java
// In CatalogImpl.getTableSnapshotHelper() (line ~794-805):
private DremioTable getTableSnapshotHelper(NamespaceKey key, TableVersionContext context) {
    final ManagedStoragePlugin plugin = pluginRetriever.getPlugin(key.getRoot(), false);
    if (plugin == null || plugin.getPlugin().isEmpty()) {
        return null;
    }

    if (plugin.getPlugin().get().isWrapperFor(VersionedPlugin.class)) {
        return getTableSnapshotForVersionedSource(plugin, key, context);
    } else if (plugin.getPlugin().get().isWrapperFor(SupportsBranchAwareRestCatalog.class)
               && context != null && !context.isTimeTravelType()) {
        return getTableSnapshotForBranchAwareRestSource(plugin, key, context);
    } else {
        return getTableSnapshotForNonVersionedSource(plugin, key, context);
    }
}
```

### Pattern 3: forATSpecifierAccess Gate Expansion

**What:** `CatalogUtil.forATSpecifierAccess()` determines whether `CatalogImpl.getTable(CatalogEntityKey)` should route through `getTableSnapshot()` (the AT-aware path) vs `getTable(NamespaceKey)` (the non-AT path). Currently it returns true only for `VersionedPlugin` sources or time-travel types. Must be expanded for `SupportsBranchAwareRestCatalog`.

**When to use:** Without this change, `SELECT * FROM source.ns.table AT BRANCH "dev"` on a Nessie-enabled RESTCATALOG source would bypass `getTableSnapshot()` entirely, falling through to `getTable(NamespaceKey)` which ignores the version context -- silently returning data from the default branch instead of the requested branch.

**Example:**
```java
// In CatalogUtil.forATSpecifierAccess() -- expand the check:
public static boolean forATSpecifierAccess(CatalogEntityKey catalogEntityKey, SourceCatalog catalog) {
    boolean isVersionedTable =
        requestedPluginSupportsVersionedTables(catalogEntityKey.getRootEntity(), catalog);
    boolean isBranchAwareRestCatalog =
        requestedPluginSupportsBranchAwareRest(catalogEntityKey.getRootEntity(), catalog);
    return ((catalogEntityKey.hasTableVersionContext())
        && (isVersionedTable || isBranchAwareRestCatalog
            || catalogEntityKey.getTableVersionContext().isTimeTravelType()));
}
```

### Pattern 4: Plan Cache Exclusion for Branch-Aware Sources

**What:** The plan cache `supportPlanCache()` method in `PlanCacheUtils` already checks `checkForVersionedTable()` which calls `CatalogUtil.requestedPluginSupportsVersionedTables()`. For branch-aware REST catalog sources, extend this detection to also exclude plans touching `SupportsBranchAwareRestCatalog` sources.

**When to use:** Always when the legacy plan cache is in use (which is the default: `QUERY_PLAN_USE_LEGACY_CACHE` defaults to `true`).

**Example:**
```java
// Option A: Extend CatalogUtil.requestedPluginSupportsVersionedTables() to also check SupportsBranchAwareRestCatalog
// This is cleaner because it makes ALL places that call this method automatically branch-aware-safe.
public static boolean requestedPluginSupportsVersionedTables(String sourceName, SourceCatalog catalog) {
    try {
        StoragePlugin source = catalog.getSource(sourceName);
        return source != null
            && (source.isWrapperFor(VersionedPlugin.class)
                || source.isWrapperFor(SupportsBranchAwareRestCatalog.class));
    } catch (UserException ignored) {
        return false;
    }
}

// Option B: Add a parallel method and check both in PlanCacheUtils
// This keeps the existing method unchanged but requires touching PlanCacheUtils.
```

**Recommendation:** Option A is simpler and more future-proof. Every place that asks "does this plugin support versioned tables?" should also recognize branch-aware REST sources.

### Anti-Patterns to Avoid

- **Making SupportsBranchAwareRestCatalog extend VersionedPlugin:** This would force implementation of 25+ methods. Keep the interfaces completely separate.

- **Implementing resolveVersionContext() by calling the Nessie API:** The RESTCATALOG speaks Iceberg REST, not Nessie API. Resolution for MVP should be lightweight: accept the branch name as-is (Nessie validates it when the REST call is made), returning a `ResolvedVersionContext` with the branch name. The Nessie server returns 404 if the branch does not exist.

- **Routing default-branch queries through the branch cache:** Queries without AT BRANCH should use the existing default `getCatalogAccessor()` path (the base endpoint). Do NOT route them through `getCatalogAccessorForBranch(defaultBranch)` because: (a) it creates an unnecessary extra RESTCatalog instance, (b) the base endpoint is already the default branch, (c) it would break CMP-02 by introducing a new code path for default queries.

- **Modifying the SQL parser:** The parser already produces `TableVersionContext(BRANCH, "dev")` for `AT BRANCH "dev"`. No parser changes needed.

- **Adding branch name to CatalogAccessorTableCacheKey:** Table cache isolation is already achieved by each branch having its own `IcebergRestCatalogAccessor` instance (Phase 22). Do not modify the cache key.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Version context resolution for REST catalog | Custom Nessie API client for branch validation | Lightweight resolution that accepts branch name at face value, lets Nessie REST validate on table load | The Nessie server validates the branch when the RESTCatalog makes its first call. No need for a separate validation round-trip. |
| Three-way dispatch in CatalogImpl | Custom routing layer outside CatalogImpl | Add to existing dispatch in `getTableSnapshotHelper()` / `getDatasetHandleHelper()` | CatalogImpl is the single point of truth for table resolution dispatch. Adding an external router would fight the architecture. |
| Plan cache exclusion | Custom plan cache key with branch dimension | Extend existing `checkForVersionedTable()` to also detect SupportsBranchAwareRestCatalog | The existing exclusion pattern is proven and safe. Custom key computation is an optimization for later. |
| Branch-aware table loading | New table loading pipeline | Reuse the existing `ManagedStoragePlugin.getDatasetHandle()` flow with a branch-scoped accessor | The existing pipeline (EntityPath -> getDatasetHandle -> DatasetHandle) works perfectly when the underlying accessor is branch-scoped. |

**Key insight:** The RESTCATALOG plugin already handles table loading correctly -- it just needs to be pointed at the right accessor (branch-scoped vs default). The CatalogImpl integration is about routing, not about reimplementing table loading.

## Common Pitfalls

### Pitfall 1: forATSpecifierAccess() not recognizing branch-aware REST sources

**What goes wrong:** If `CatalogUtil.forATSpecifierAccess()` is not updated, `CatalogImpl.getTable(CatalogEntityKey)` with an AT BRANCH specifier on a Nessie-enabled RESTCATALOG source falls through to `getTable(NamespaceKey)`, which ignores the version context entirely. The query silently returns data from the default branch instead of the requested branch.
**Why it happens:** `forATSpecifierAccess()` only checks `requestedPluginSupportsVersionedTables()` (which only checks `VersionedPlugin.class`) and `isTimeTravelType()`. BRANCH/TAG/COMMIT are NOT time-travel types, so the AT specifier is not recognized for non-VersionedPlugin sources.
**How to avoid:** Modify `forATSpecifierAccess()` to also check for `SupportsBranchAwareRestCatalog`. Alternatively, modify `requestedPluginSupportsVersionedTables()` to also check for the new interface.
**Warning signs:** Query with AT BRANCH returns same data as query without AT BRANCH. No error message, just silently wrong data.

### Pitfall 2: Infinite recursion in isWrapperFor/unwrap override

**What goes wrong:** If `RestIcebergCatalogPlugin.isWrapperFor()` calls `super.isWrapperFor()` which also tries to check `SupportsBranchAwareRestCatalog.class`, or if the conditional logic has a bug, the method could recurse infinitely or return incorrect results.
**Why it happens:** The default `Wrapper.isWrapperFor()` uses `clazz.isInstance(this)`. If `RestIcebergCatalogPlugin` implements `SupportsBranchAwareRestCatalog` directly (at the class level), `clazz.isInstance(this)` always returns true regardless of `isNessieDetected`. But we need it to be conditional.
**How to avoid:** Two approaches: (a) **Do NOT implement the interface on the class declaration.** Instead, override `isWrapperFor()` and `unwrap()` to handle `SupportsBranchAwareRestCatalog.class` manually. The class implements the interface's methods but does not declare `implements SupportsBranchAwareRestCatalog` in its signature. (b) **Implement the interface and override `isWrapperFor()`** to return false when `!isNessieDetected` for that specific class. Option (b) is cleaner because the Java type system enforces the method signatures.
**Warning signs:** `ClassCastException` in `unwrap()`, or AT BRANCH queries failing on non-Nessie sources that have RESTCATALOG type.

### Pitfall 3: getTableSnapshotForBranchAwareRestSource loading table through wrong code path

**What goes wrong:** The new `getTableSnapshotForBranchAwareRestSource()` method must load the table through the branch-scoped accessor, but the existing `ManagedStoragePlugin.getDatasetHandle()` always calls `plugin.getPlugin().get().getDatasetHandle()` which uses `getCatalogAccessor()` (the default accessor). Even with a branch-scoped accessor created, the actual table load goes through the default accessor.
**Why it happens:** `IcebergCatalogPlugin.getDatasetHandle(EntityPath)` hardcodes `getCatalogAccessor().getDatasetHandle(...)`. There's no mechanism to tell the plugin "use this specific accessor for this request."
**How to avoid:** The new method in CatalogImpl should NOT go through `ManagedStoragePlugin.getDatasetHandle()`. Instead, it should: (1) Get the branch name from the TableVersionContext, (2) Call `plugin.unwrap(SupportsBranchAwareRestCatalog.class).getCatalogAccessorForBranch(branchName)`, (3) Call `accessor.getDatasetHandle(components, plugin, options)` directly. This bypasses the default accessor routing entirely.
**Warning signs:** AT BRANCH queries return data from the default branch (same as no AT BRANCH). This is the same symptom as Pitfall 1 but caused at a different level.

### Pitfall 4: resolveVersionContext() failing for valid branches

**What goes wrong:** If `resolveVersionContext()` tries to validate the branch by making a REST call before table loading, and the validation fails (network error, timeout), the query fails even though the branch is valid.
**Why it happens:** Over-engineering the resolution -- trying to pre-validate branches via separate REST calls.
**How to avoid:** For MVP, `resolveVersionContext()` should be lightweight: accept the branch name, wrap it in a `ResolvedVersionContext(BRANCH, branchName, "")` (no commit hash available via REST). Let the actual table load (which goes through the branch-scoped RESTCatalog) fail with a clear error if the branch does not exist. The Nessie server will return a proper error (404) which becomes a user-facing "branch not found" message.
**Warning signs:** "Branch not found" errors for valid branches due to transient network issues during pre-validation.

### Pitfall 5: Plan cache serving stale results for cross-branch queries

**What goes wrong:** Without plan cache exclusion, a query `SELECT * FROM source.ns.table AT BRANCH "main"` gets cached. A subsequent query `SELECT * FROM source.ns.table AT BRANCH "dev"` has the same SQL text (minus the branch name in the AT clause), so if the plan cache key includes the SQL text, different branches produce different keys. BUT: if the AT clause is processed as part of version resolution (not in the SQL text), the SQL texts could be identical.
**Why it happens:** The plan cache key hash includes `sqlNode.toSqlString()`, which does include the AT BRANCH clause. So explicit AT BRANCH queries should naturally get different cache keys. However, the real risk is the legacy cache validation (`checkIfAllDatasetValid()`) which uses `lastModified` timestamps from the namespace KV store. Branch-scoped queries may not have KV store entries, causing validation to return null/false unpredictably.
**How to avoid:** Exclude Nessie-enabled RESTCATALOG sources from plan cache entirely (same as native Nessie). This is achieved by extending `requestedPluginSupportsVersionedTables()` or adding a parallel check in `checkForVersionedTable()`.
**Warning signs:** Stale query results when switching between branches; plan cache validation errors in logs.

### Pitfall 6: VersionContextResolverImpl cache holding stale branch resolutions

**What goes wrong:** `VersionContextResolverImpl` uses a `LoadingCache<(sourceName, VersionContext), ResolvedVersionContext>`. Its `Loader` calls `source.unwrap(VersionedPlugin.class).resolveVersionContext()`. For `SupportsBranchAwareRestCatalog` sources, this unwrap will fail because the source is not a `VersionedPlugin`.
**Why it happens:** The existing `VersionContextResolverImpl.Loader` only knows about `VersionedPlugin`. If `CatalogImpl` tries to resolve version context through this resolver for a branch-aware REST source, it will throw "Source does not support versioning."
**How to avoid:** The new `getTableSnapshotForBranchAwareRestSource()` method must NOT go through `VersionContextResolverImpl`. It should call `SupportsBranchAwareRestCatalog.resolveVersionContext()` directly, bypassing the per-query caching resolver. This is fine because the resolution is lightweight (no Nessie API call, just wrapping the branch name).
**Warning signs:** "Source does not support versioning" error when using AT BRANCH on a Nessie-enabled RESTCATALOG source.

## Code Examples

Verified patterns from codebase analysis:

### SupportsBranchAwareRestCatalog Interface Definition

```java
// Location: sabot/kernel/src/main/java/com/dremio/exec/catalog/SupportsBranchAwareRestCatalog.java
// This is a NEW file.

package com.dremio.exec.catalog;

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.Wrapper;

/**
 * Narrow interface for StoragePlugins that support branch-aware table access
 * via Iceberg REST prefix manipulation (not native Nessie API).
 *
 * <p>Unlike {@link VersionedPlugin} which requires 25+ methods for full Nessie
 * integration (branch CRUD, merge, listing, etc.), this interface provides only
 * the 3 methods needed for read-only branch-scoped table loading via the
 * Iceberg REST protocol.
 */
public interface SupportsBranchAwareRestCatalog extends Wrapper {

    /**
     * Resolve a version context for this catalog. For REST-based catalogs,
     * this is a lightweight operation -- the branch name is accepted as-is
     * and validation happens on the actual REST call.
     */
    ResolvedVersionContext resolveVersionContext(VersionContext versionContext);

    /**
     * Get a CatalogAccessor scoped to a specific branch. The accessor is
     * cached (bounded, TTL-evicted) and has its own isolated table cache.
     */
    CatalogAccessor getCatalogAccessorForBranch(String branchName);

    /**
     * Get the server-defined default branch name.
     */
    String getDefaultBranch();
}
```

### RestIcebergCatalogPlugin Implementation (Conditional isWrapperFor)

```java
// In RestIcebergCatalogPlugin.java -- add these methods alongside existing code

// The class declaration adds: implements SupportsBranchAwareRestCatalog
// public class RestIcebergCatalogPlugin extends IcebergCatalogPlugin
//     implements SupportsBranchAwareRestCatalog {

@Override
public boolean isWrapperFor(Class<?> clazz) {
    if (SupportsBranchAwareRestCatalog.class.equals(clazz)) {
        return isNessieDetected;  // Only advertise when Nessie is confirmed
    }
    return super.isWrapperFor(clazz);
}

@Override
public <T> T unwrap(Class<T> clazz) {
    if (SupportsBranchAwareRestCatalog.class.equals(clazz) && isNessieDetected) {
        return clazz.cast(this);
    }
    return super.unwrap(clazz);
}

@Override
public ResolvedVersionContext resolveVersionContext(VersionContext versionContext) {
    // Lightweight resolution -- accept branch/tag name at face value.
    // Nessie validates it when the branch-scoped RESTCatalog makes its first REST call.
    switch (versionContext.getType()) {
        case BRANCH:
        case BRANCH_AS_OF_TIMESTAMP:
            return ResolvedVersionContext.ofBranch(versionContext.getValue(), "");
        case TAG:
        case TAG_AS_OF_TIMESTAMP:
            return ResolvedVersionContext.ofTag(versionContext.getValue(), "");
        case NOT_SPECIFIED:
            return ResolvedVersionContext.ofBranch(getDefaultBranch(), "");
        default:
            throw UserException.validationError()
                .message("AT %s is not supported on RESTCATALOG sources with Nessie",
                    versionContext.getType())
                .buildSilently();
    }
}

// getCatalogAccessorForBranch() already exists from Phase 22
// getDefaultBranch() already exists from Phase 21
```

### CatalogImpl.getTableSnapshotHelper() Modified Dispatch

```java
// Source: CatalogImpl.java lines 794-805 (currently)
// MODIFIED to add third branch:

private DremioTable getTableSnapshotHelper(NamespaceKey key, TableVersionContext context) {
    final ManagedStoragePlugin plugin = pluginRetriever.getPlugin(key.getRoot(), false);
    if (plugin == null || plugin.getPlugin().isEmpty()) {
        return null;
    }

    if (plugin.getPlugin().get().isWrapperFor(VersionedPlugin.class)) {
        return getTableSnapshotForVersionedSource(plugin, key, context);
    } else if (plugin.getPlugin().get().isWrapperFor(SupportsBranchAwareRestCatalog.class)
               && context != null && !context.isTimeTravelType()) {
        return getTableSnapshotForBranchAwareRestSource(plugin, key, context);
    } else {
        return getTableSnapshotForNonVersionedSource(plugin, key, context);
    }
}
```

### New getTableSnapshotForBranchAwareRestSource() Method

```java
// NEW method in CatalogImpl

private DremioTable getTableSnapshotForBranchAwareRestSource(
        ManagedStoragePlugin plugin,
        NamespaceKey key,
        TableVersionContext tableVersionContext) {
    SupportsBranchAwareRestCatalog branchPlugin =
        plugin.getPlugin().get().unwrap(SupportsBranchAwareRestCatalog.class);

    // Resolve version context (lightweight -- no server round-trip)
    VersionContext versionContext = tableVersionContext.asVersionContext();
    if (!versionContext.isSpecified()) {
        // No AT clause: use default branch
        versionContext = VersionContext.ofBranch(branchPlugin.getDefaultBranch());
    }
    ResolvedVersionContext resolved = branchPlugin.resolveVersionContext(versionContext);

    // Get branch-scoped accessor
    String branchName = resolved.getRefName();
    CatalogAccessor accessor = branchPlugin.getCatalogAccessorForBranch(branchName);

    // Load table via branch-scoped accessor
    List<String> components = key.getPathComponents();
    if (components.size() < 3) {
        return null;
    }
    Optional<DatasetHandle> handle = accessor.getDatasetHandle(
        components, plugin.getPlugin().get(), /* options */ );

    if (handle.isEmpty()) {
        return null;
    }

    // Build MaterializedDatasetTable similar to non-versioned path
    DatasetRetrievalOptions retrievalOptions = plugin.getDefaultRetrievalOptions();
    return new MaterializedDatasetTableProvider(
            null,
            handle.get(),
            plugin.getPlugin().get(),
            plugin.getId(),
            options.getSchemaConfig(),
            retrievalOptions)
        .get();
}
```

### CatalogUtil.forATSpecifierAccess() Modified

```java
// Source: CatalogUtil.java lines 285-290 (currently)
// MODIFIED to recognize branch-aware REST sources:

public static boolean forATSpecifierAccess(
        CatalogEntityKey catalogEntityKey, SourceCatalog catalog) {
    boolean isVersionedTable =
        requestedPluginSupportsVersionedTables(catalogEntityKey.getRootEntity(), catalog);
    // NEW: also check for SupportsBranchAwareRestCatalog
    boolean isBranchAwareTable =
        requestedPluginSupportsBranchAwareRest(catalogEntityKey.getRootEntity(), catalog);
    return ((catalogEntityKey.hasTableVersionContext())
        && (isVersionedTable || isBranchAwareTable
            || catalogEntityKey.getTableVersionContext().isTimeTravelType()));
}

// NEW helper method:
public static boolean requestedPluginSupportsBranchAwareRest(
        String sourceName, SourceCatalog catalog) {
    try {
        StoragePlugin source = catalog.getSource(sourceName);
        return source != null
            && source.isWrapperFor(SupportsBranchAwareRestCatalog.class);
    } catch (UserException ignored) {
        return false;
    }
}
```

### PlanCacheUtils.checkForVersionedTable() Extended

```java
// Source: PlanCacheUtils.java lines 307-321 (currently)
// MODIFIED to also check SupportsBranchAwareRestCatalog:

private static boolean checkForVersionedTable(SqlHandlerConfig sqlHandlerConfig) {
    PlannerCatalog plannerCatalog = sqlHandlerConfig.getConverter().getPlannerCatalog();
    Catalog catalog = sqlHandlerConfig.getContext().getCatalog();
    for (DremioTable table : plannerCatalog.getAllRequestedTables()) {
        if (CatalogUtil.requestedPluginSupportsVersionedTables(table.getPath(), catalog)
            || CatalogUtil.requestedPluginSupportsBranchAwareRest(
                   table.getPath().getRoot(), catalog)) {
            return true;
        }
    }
    return false;
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Binary dispatch: VersionedPlugin vs non-versioned | Three-way dispatch: VersionedPlugin vs SupportsBranchAwareRestCatalog vs non-versioned | Phase 23 (this phase) | Enables AT BRANCH queries on RESTCATALOG sources without implementing full VersionedPlugin |
| RESTCATALOG AT BRANCH throws error | RESTCATALOG AT BRANCH loads from branch-scoped accessor | Phase 23 (this phase) | New capability: branch-aware queries on REST catalog sources |
| Plan cache includes all non-versioned sources | Plan cache excludes branch-aware REST sources | Phase 23 (this phase) | Prevents stale cross-branch plan reuse (INF-03) |
| `forATSpecifierAccess()` only recognizes VersionedPlugin | `forATSpecifierAccess()` also recognizes SupportsBranchAwareRestCatalog | Phase 23 (this phase) | AT BRANCH specifiers correctly route through getTableSnapshot() |

**Existing patterns preserved:**
- `IcebergCatalogPlugin.getDatasetHandle()` continues to use `getCatalogAccessor()` for non-AT-BRANCH queries
- `DatasetManager.getTable()` path for default queries remains unchanged
- `VersionedPlugin` code paths completely unaffected
- `VersionContextResolverImpl` continues to only serve `VersionedPlugin` sources

## Open Questions

1. **Should `resolveVersionContext()` make a validation call to the Nessie server?**
   - What we know: Native Nessie sources call `NessieClient.resolveVersionContext()` which contacts the Nessie API v2 to validate branch existence and get the commit hash. The RESTCATALOG has no Nessie API client.
   - What's unclear: Whether deferred validation (let the RESTCatalog call fail with 404) gives acceptable error messages.
   - Recommendation: For MVP, do lightweight resolution (no server call). The Nessie server returns a clear error when an invalid branch is used in the REST prefix (e.g., `NoSuchReferenceException` wrapped in REST 404). Test that this error surfaces as a meaningful user-facing message ("Branch 'xyz' not found on source 'mysource'"), and add a catch clause in `getTableSnapshotForBranchAwareRestSource()` to convert REST errors into proper `UserException.validationError()` messages.

2. **How many dispatch points in CatalogImpl need modification?**
   - What we know: The primary dispatch points are:
     - `getTableSnapshotHelper()` (line 794) -- main table loading for AT queries
     - `getDatasetHandleHelper()` (line 989) -- dataset handle retrieval
     - `getTableSnapshotForNonVersionedSource()` (line 883) -- the error-throwing path for non-versioned sources with AT BRANCH
     - `mFunctionTableForPlugin()` (line 561) -- metadata function dispatch
     - `getConfigByCanonicalKey()` (line 2071) -- config retrieval that short-circuits for versioned plugins
   - Recommendation: For MVP, modify `getTableSnapshotHelper()` and `forATSpecifierAccess()`. The `getDatasetHandleHelper()` may also need modification if it's called for AT BRANCH queries. `mFunctionTableForPlugin()` can be deferred (metadata functions on branches are not a Phase 23 requirement). `getConfigByCanonicalKey()` should remain unchanged -- branch-aware REST sources still store metadata in the namespace KV for the default branch.

3. **What about `CatalogEntityKey` resolution for branch-aware sources?**
   - What we know: `CatalogEntityKey` carries `TableVersionContext`. For versioned sources, `CatalogImpl.getDatasetIdForVersionedSource()` uses `VersionedPlugin.getContentId()`. For branch-aware REST sources, there is no content ID.
   - What's unclear: Whether any callers rely on `getDatasetId()` for branch-aware REST source tables.
   - Recommendation: For MVP, `getDatasetIdForVersionedSource()` does not need modification because it checks `isWrapperFor(VersionedPlugin.class)` (line 446) and the branch-aware REST source is NOT a `VersionedPlugin`. Branch-aware tables will get their dataset ID from the standard non-versioned path if needed.

4. **Should the `ResolvedVersionContext` for REST branches include a commit hash?**
   - What we know: Native Nessie resolution produces `ResolvedVersionContext(BRANCH, "dev", "abc123hash")`. The RESTCATALOG has no way to obtain the commit hash via the Iceberg REST protocol.
   - What's unclear: Whether any downstream code requires a non-empty commit hash from `ResolvedVersionContext`.
   - Recommendation: Use empty string `""` for the commit hash in `ResolvedVersionContext`. Verify that downstream code handles this gracefully. If any code requires a non-empty hash, use a placeholder like `"unresolved"`.

## Sources

### Primary (HIGH confidence)
- `CatalogImpl.java` -- lines 337-356: `getTable(CatalogEntityKey)` dispatch via `forATSpecifierAccess()`; lines 767-805: `getTableSnapshot()` -> `getTableSnapshotHelper()` three-way dispatch point; lines 807-881: `getTableSnapshotForVersionedSource()` pattern; lines 883-896: `getTableSnapshotForNonVersionedSource()` error path for AT BRANCH on non-versioned sources; lines 989-1001: `getDatasetHandleHelper()` parallel dispatch; lines 1127-1155: `getVersionContext()` resolution logic
- `CatalogUtil.java` -- lines 115-139: `requestedPluginSupportsVersionedTables()` (checks `VersionedPlugin.class`); lines 285-290: `forATSpecifierAccess()` gate
- `PlanCacheUtils.java` -- lines 104-150: `supportPlanCache()` with versioned table check at line 138; lines 307-321: `checkForVersionedTable()` iteration over requested tables
- `VersionedPlugin.java` -- full interface: 25+ methods demonstrating why full implementation is wrong for RESTCATALOG
- `Wrapper.java` -- default `isWrapperFor()` uses `instanceof`; default `unwrap()` uses `cast`. Both can be overridden.
- `TableVersionType.java` -- `isTimeTravel()` returns true only for SNAPSHOT_ID and TIMESTAMP; BRANCH/TAG/COMMIT are NOT time travel
- `TableVersionContext.java` -- `asVersionContext()` converts BRANCH to `VersionContext.ofBranch(name)`, TAG to `VersionContext.ofTag(name)`
- `VersionContextResolverImpl.java` -- `Loader.load()` at line 90-108: only works for `VersionedPlugin` sources; throws for non-versioned
- `RestIcebergCatalogPlugin.java` -- current state with Phase 21/22 additions: `isNessieDetected()`, `getDefaultBranch()`, `getCatalogAccessorForBranch()`, `branchAccessorCache`, `isWrapperFor()` not yet overridden
- `IcebergCatalogPlugin.java` -- `getDatasetHandle(EntityPath)` at lines 178-187: always uses `getCatalogAccessor()`, no way to pass branch-scoped accessor
- `BranchAwareCatalogAccessorCache.java` -- `getOrCreate(branchName)` returns cached or new `IcebergRestCatalogAccessor`
- `MetadataRequestOptions.java` -- `getVersionForSource()` at line 209: returns session-level version context for a source (used for implicit branch)

### Secondary (MEDIUM confidence)
- `PlannerSettings.java` -- `QUERY_PLAN_USE_LEGACY_CACHE` defaults to `true` (line 747-748), meaning the versioned table check in `supportPlanCache()` is active by default
- `PlanCacheValidationUtils.java` -- validation uses `datasetConfig.getLastModified()` which may be null for branch-scoped tables not in KV store, causing validation to fail (returns null -> invalidates cache entry). This is additional defense against stale plans.

### Tertiary (LOW confidence)
- `ResolvedVersionContext` constructor behavior with empty commit hash -- needs runtime verification that downstream code (e.g., VersionedDatasetAdapter, InformationSchema) does not require non-empty hash. Flag for integration testing.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- zero new dependencies, all integration points identified from direct codebase analysis
- Architecture: HIGH -- three-way dispatch pattern directly modeled on existing two-way pattern; all code paths traced end-to-end
- Plan cache: HIGH -- existing `checkForVersionedTable()` pattern is the proven approach; extension is straightforward
- Pitfalls: HIGH -- all identified from direct analysis of dispatch logic, accessor routing, and version context resolution; Pitfall 3 (wrong accessor) is the most critical and requires careful implementation

**Research date:** 2026-03-10
**Valid until:** 2026-04-10 (30 days -- stable domain, CatalogImpl dispatch patterns are well-established)
