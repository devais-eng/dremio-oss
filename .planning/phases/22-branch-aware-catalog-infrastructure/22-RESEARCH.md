# Phase 22: Branch-Aware Catalog Infrastructure - Research

**Researched:** 2026-03-09
**Domain:** Per-branch RESTCatalog caching with Caffeine, branch-isolated table metadata, connection lifecycle management
**Confidence:** HIGH

## Summary

Phase 22 builds the infrastructure layer for branch-aware catalog access. The core challenge is that Iceberg's `RESTCatalog` bakes its `prefix` (which encodes the Nessie branch) into an immutable `ResourcePaths` object at initialization time. A single RESTCatalog instance can only talk to one branch. The solution is a bounded cache of per-branch `RESTCatalog` instances, each initialized with a branch-specific URI (e.g., `http://nessie:19120/iceberg/dev`).

Phase 21 already delivered: `enableNessie` config field (`@Tag(13)` on `RestIcebergCatalogPluginConfig`), `isNessieDetected()` and `getDefaultBranch()` getters on `RestIcebergCatalogPlugin`, and `getRestCatalogProperties()` on `IcebergRestCatalogAccessor`. These are the foundation this phase builds on.

The two requirements (INF-01 and INF-02) decompose cleanly: INF-01 is the per-branch RESTCatalog cache with bounded size, TTL eviction, and proper `close()` on eviction. INF-02 is table cache isolation -- since each branch gets its own `IcebergRestCatalogAccessor` instance (which has its own `LoadingCache<CatalogAccessorTableCacheKey, Table>` table cache), isolation is achieved architecturally by design with no modifications to the table cache key.

**Primary recommendation:** Create a `BranchAwareCatalogAccessorCache` using Caffeine `Cache<String, IcebergRestCatalogAccessor>` with `maximumSize`, `expireAfterAccess`, and a `removalListener` that calls `close()` on evicted entries. Each accessor wraps a distinct `RESTCatalog` instance initialized with `URI = {baseEndpoint}/{branchName}`. Add a `getCatalogAccessorForBranch(String branchName)` method on `RestIcebergCatalogPlugin` that queries this cache.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| INF-01 | Per-branch RESTCatalog instances are cached with bounded size and TTL eviction (no connection pool exhaustion) | Caffeine `Cache<String, IcebergRestCatalogAccessor>` with `maximumSize(20)`, `expireAfterAccess(30, MINUTES)`, and `removalListener` calling `close()` on evicted accessor. Each accessor wraps its own `ExpiringCatalogCache` -> `RESTCatalog` chain. Bounded cache prevents unbounded growth; close-on-eviction prevents connection pool exhaustion. |
| INF-02 | Table cache is isolated per branch (no cross-branch cache poisoning -- same table path on different branches returns correct data) | Each per-branch `IcebergRestCatalogAccessor` has its own private `LoadingCache<CatalogAccessorTableCacheKey, Table>` and `LoadingCache<CatalogAccessorTableCacheKey, View>` created in `AbstractRestCatalogAccessor` constructor. Since branches get separate accessor instances, their table caches are completely disjoint. No modification to `CatalogAccessorTableCacheKey` is needed. |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Caffeine | 3.2.0 | Branch accessor cache with bounded size, TTL, eviction listener | Already used in `AbstractRestCatalogAccessor` for table/view caches; managed in root `pom.xml` BOM; high-performance concurrent cache |
| Iceberg `RESTCatalog` | 1.7.0-custom | Per-branch catalog instances | Already the catalog implementation used by RESTCATALOG source; one instance per branch with branch-specific URI |
| `ExpiringCatalogCache` | Dremio internal | Inner cache for each per-branch RESTCatalog instance | Already used as the memoizing supplier pattern for the single default RESTCatalog; reused identically per branch |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| SLF4J | (transitive) | Logging cache events (creation, eviction, errors) | Always; log branch cache hits/misses at DEBUG, eviction at INFO |
| Guava `Preconditions` | 33.4.0-jre | Parameter validation | Validate branch name non-null/non-empty in `getCatalogAccessorForBranch()` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Caffeine `Cache` | `ConcurrentHashMap` + manual TTL | Caffeine provides built-in eviction, max-size, TTL, removal listeners; manual implementation would be error-prone and duplicate existing patterns |
| Per-branch `IcebergRestCatalogAccessor` | Shared accessor with branch in table cache key | Shared accessor would require modifying `CatalogAccessorTableCacheKey`, `AbstractRestCatalogAccessor`, and the view cache -- much more invasive; per-branch accessor gives isolation for free |
| `expireAfterAccess` | `expireAfterWrite` | `expireAfterAccess` is better: a frequently-used branch stays cached, idle branches get evicted. `expireAfterWrite` would force re-creation even for active branches |

**Installation:**
No new dependencies needed. Caffeine is already transitively available via `dremio-sabot-kernel` (explicitly imported in `AbstractRestCatalogAccessor`). No pom.xml changes required for Phase 22.

## Architecture Patterns

### Recommended Project Structure

No new files in new directories. All changes are within the existing `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/` package:

```
plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/
  BranchAwareCatalogAccessorCache.java   # NEW: Caffeine cache of per-branch accessors
  RestIcebergCatalogPlugin.java           # MODIFIED: add branch cache field, getCatalogAccessorForBranch()
  IcebergRestCatalogAccessor.java         # UNCHANGED (or minor: package-private constructor variant)
  AbstractRestCatalogAccessor.java        # UNCHANGED
  CatalogAccessorTableCacheKey.java       # UNCHANGED
  ExpiringCatalogCache.java               # UNCHANGED
```

### Pattern 1: Caffeine Cache with Eviction Listener for Resource Cleanup

**What:** A bounded, TTL-evicted cache of `IcebergRestCatalogAccessor` instances keyed by branch name. On eviction, the accessor's `close()` is called, which in turn closes the underlying `ExpiringCatalogCache` -> `RESTCatalog` (releasing HTTP connections and OAuth tokens).

**When to use:** Always when `isNessieDetected()` returns `true`. When `isNessieDetected()` is false, this cache is never created.

**Example:**
```java
// Source: Caffeine 3.2.0 API + existing AbstractRestCatalogAccessor.close() pattern
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

public class BranchAwareCatalogAccessorCache implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(BranchAwareCatalogAccessorCache.class);

  private final Cache<String, IcebergRestCatalogAccessor> cache;
  private final Function<String, IcebergRestCatalogAccessor> factory;

  public BranchAwareCatalogAccessorCache(
      int maxBranches,
      long expireAfterAccessSeconds,
      Function<String, IcebergRestCatalogAccessor> factory) {
    this.factory = factory;
    this.cache = Caffeine.newBuilder()
        .maximumSize(maxBranches)
        .expireAfterAccess(expireAfterAccessSeconds, TimeUnit.SECONDS)
        .removalListener((String key, IcebergRestCatalogAccessor accessor, RemovalCause cause) -> {
          if (accessor != null) {
            try {
              logger.info("Closing evicted branch accessor for branch '{}' (cause: {})", key, cause);
              accessor.close();
            } catch (Exception e) {
              logger.warn("Error closing evicted branch accessor for '{}'", key, e);
            }
          }
        })
        .build();
  }

  public IcebergRestCatalogAccessor getOrCreate(String branchName) {
    return cache.get(branchName, factory::apply);
  }

  @Override
  public void close() {
    cache.asMap().forEach((branch, accessor) -> {
      try {
        accessor.close();
      } catch (Exception e) {
        logger.warn("Error closing branch accessor for '{}'", branch, e);
      }
    });
    cache.invalidateAll();
    cache.cleanUp();
  }
}
```

### Pattern 2: Branch-Scoped RESTCatalog Instance Creation

**What:** Create a new `IcebergRestCatalogAccessor` for a specific branch by modifying the URI in the catalog properties to include the branch name as a path segment.

**When to use:** When the `BranchAwareCatalogAccessorCache` has a cache miss for a branch name.

**Example:**
```java
// Source: Existing RestIcebergCatalogPlugin.createCatalog() + buildCatalogProperties() pattern

// In RestIcebergCatalogPlugin:
private IcebergRestCatalogAccessor createBranchScopedAccessor(String branchName) {
  Configuration config = getFsConfCopy();
  Map<String, String> properties = buildCatalogProperties(config);
  // Replace the URI with branch-scoped URI
  // e.g., "http://nessie:19120/iceberg" -> "http://nessie:19120/iceberg/dev"
  String branchUri = restEndpoint + "/" + branchName;
  properties.put(CatalogProperties.URI, branchUri);

  Supplier<Catalog> catalogSupplier = () ->
      CatalogUtil.loadCatalog(
          restCatalogImpl().getName(), catalogName(), properties, config);

  return new IcebergRestCatalogAccessor(
      catalogSupplier,
      optionManager,
      getAllowedNamespaces(),
      isRecursiveAllowedNamespaces());
}
```

### Pattern 3: Conditional Initialization in start()

**What:** Only create the `BranchAwareCatalogAccessorCache` when Nessie is detected. The cache is null when `enableNessie` is false or when the backend is not Nessie.

**When to use:** In `RestIcebergCatalogPlugin.start()`, after `detectNessieBackend()` confirms Nessie is present.

**Example:**
```java
// In RestIcebergCatalogPlugin:
private volatile BranchAwareCatalogAccessorCache branchAccessorCache;

@Override
public void start() throws IOException {
  super.start(); // Creates default catalogAccessor
  detectNessieBackend();
  if (isNessieDetected) {
    branchAccessorCache = new BranchAwareCatalogAccessorCache(
        maxBranchCacheSize,       // default 20
        branchCacheExpireSeconds, // default 1800 (matches RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS)
        this::createBranchScopedAccessor);
  }
}
```

### Anti-Patterns to Avoid

- **Sharing a single RESTCatalog instance across branches:** The prefix (`ResourcePaths`) is immutable after `RESTCatalog.initialize()`. Attempting to reuse one instance for multiple branches will silently query the wrong branch. Always create separate instances.

- **Unbounded cache of per-branch accessors:** Without `maximumSize`, querying N distinct branches creates N HTTP clients, each with its own connection pool. Use Caffeine's `maximumSize` to bound the cache.

- **Forgetting to call `close()` on evicted accessors:** Each `IcebergRestCatalogAccessor` wraps an `ExpiringCatalogCache` which wraps a `RESTCatalog` (which is `Closeable`). If evicted accessors are not closed, HTTP connections and OAuth tokens leak. Always use a `removalListener`.

- **Using `expireAfterWrite` for the branch cache:** This would force re-creating RESTCatalog instances for active branches on every TTL expiry, causing unnecessary config endpoint calls. Use `expireAfterAccess` so actively-used branches stay cached.

- **Modifying `CatalogAccessorTableCacheKey` to include branch:** This is unnecessary and invasive. Since each branch gets its own `IcebergRestCatalogAccessor` with its own `LoadingCache<CatalogAccessorTableCacheKey, Table>`, table cache isolation is automatic.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Bounded concurrent cache with TTL and eviction callbacks | Custom `ConcurrentHashMap` + `ScheduledExecutor` TTL | Caffeine `Cache.builder().maximumSize().expireAfterAccess().removalListener()` | Caffeine handles concurrent access, eviction ordering, and cleanup atomically; hand-rolled versions have race conditions |
| RESTCatalog instance creation | Direct HTTP client construction + config endpoint handshake | `CatalogUtil.loadCatalog()` with branch-modified properties | This is the exact factory method already used by the existing single-catalog path; it handles the full initialization lifecycle including config endpoint negotiation and OAuth token acquisition |
| Thread-safe lazy initialization of the branch cache | Double-checked locking or `synchronized` blocks | Caffeine `Cache.get(key, mappingFunction)` | Caffeine's `get()` with a mapping function is atomic and does not hold the cache lock during value computation |

**Key insight:** The entire Phase 22 infrastructure reuses existing patterns. The `IcebergRestCatalogAccessor` constructor, `ExpiringCatalogCache`, `CatalogUtil.loadCatalog()`, and Caffeine caches are all proven patterns in this codebase. The new code is essentially a thin layer that wraps them in a branch-keyed cache.

## Common Pitfalls

### Pitfall 1: RESTCatalog close() called while query is using it (ExpiringCatalogCache race)

**What goes wrong:** The existing `ExpiringCatalogCache.get()` has a race condition: it calls `invalidate()` (which calls `close()` on the old catalog) then creates a new one. If a query thread is actively using the old catalog (e.g., mid-way through `loadTable()`), the HTTP client is closed underneath it, causing connection errors.

**Why it happens:** `ExpiringCatalogCache.invalidate()` at line 46-56 calls `((Closeable) catalog).close()` immediately, without checking if any thread holds a reference to the old catalog.

**How to avoid:** For the branch cache layer (Caffeine `Cache`), the `removalListener` in Caffeine is called *after* the entry is removed from the cache. Active queries that already obtained a reference to the accessor will hold a strong reference, preventing the accessor from being garbage-collected even after cache eviction. However, the inner `ExpiringCatalogCache` within each accessor still has this race. For Phase 22 (infrastructure only, no queries routed yet), this is not triggered. Flag for Phase 23 when queries start flowing through.

**Warning signs:** `java.io.IOException: Connection pool shut down` or `java.lang.IllegalStateException: Connection pool has been shut down` in query logs.

### Pitfall 2: Branch name URL encoding

**What goes wrong:** Nessie branch names can contain `/` (e.g., `feature/auth-fix`), which when appended to the URI creates ambiguous path segments. The Nessie server may or may not handle URL-encoded branch names in the path.

**Why it happens:** Branch names follow Git conventions (e.g., `feature/xyz`, `bugfix/DX-12345`). When appended to `http://nessie:19120/iceberg/`, a branch like `feature/auth-fix` produces `http://nessie:19120/iceberg/feature/auth-fix` -- which the Nessie server may interpret as two path segments.

**How to avoid:** URL-encode the branch name before appending it to the URI. Use `java.net.URLEncoder.encode(branchName, StandardCharsets.UTF_8)` and replace `+` with `%20` (since we are encoding a path segment, not a query parameter). Verify with integration tests against Nessie with branches containing `/`.

**Warning signs:** HTTP 404 errors for branches with `/` in their names; silently connecting to wrong branch.

### Pitfall 3: OAuth token proliferation from many RESTCatalog instances

**What goes wrong:** Each `RESTCatalog` instance (created by `CatalogUtil.loadCatalog()`) initializes its own OAuth2 session by calling `GET /v1/config` and potentially refreshing tokens. With 20 cached branches, there are 20 active OAuth sessions. If the Nessie server has per-session rate limits, this can cause throttling.

**Why it happens:** The Iceberg `RESTSessionCatalog` creates a per-instance `AuthSession` during `initialize()`. There is no mechanism to share auth sessions across RESTCatalog instances.

**How to avoid:** Keep the branch cache bounded (`maximumSize(20)` is generous for typical usage). Monitor for HTTP 429 (Too Many Requests) responses from the Nessie server. If token proliferation becomes an issue, the max branch cache size can be reduced via a support key.

**Warning signs:** HTTP 429 responses; increased latency on branch cache misses; high token refresh rate in Nessie server logs.

### Pitfall 4: Stale default branch pointer in the branch accessor cache

**What goes wrong:** The `defaultBranch` is read once during `start()` from the config endpoint. If the Nessie admin changes the default branch on the server, the cached default branch name becomes stale. Queries without explicit `AT BRANCH` will continue using the old default.

**Why it happens:** Default branch is cached as a volatile field in `RestIcebergCatalogPlugin`, never refreshed.

**How to avoid:** This is an acceptable limitation for Phase 22 (infrastructure-only). Phase 23 can add a "refresh default branch on first query after N seconds" pattern. Document that changing the default branch on the Nessie server requires re-starting the RESTCATALOG source in Dremio to pick up the new default.

**Warning signs:** Queries return unexpected data after Nessie default branch change; user reports "wrong branch" when no `AT BRANCH` is specified.

## Code Examples

Verified patterns from the existing codebase:

### Creating a RESTCatalog instance (existing pattern)
```java
// Source: RestIcebergCatalogPlugin.java lines 396-399
protected Supplier<Catalog> createRestCatalog(Configuration config) {
  return () ->
      CatalogUtil.loadCatalog(
          restCatalogImpl().getName(), catalogName(), buildCatalogProperties(config), config);
}
```

### ExpiringCatalogCache wrapping a catalog supplier (existing pattern)
```java
// Source: IcebergRestCatalogAccessor.java lines 43-47
super(
    new ExpiringCatalogCache(
        catalogSupplier,
        optionsManager.getOption(RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS),
        TimeUnit.SECONDS),
    optionsManager,
    allowedNamespaces,
    isRecursiveAllowedNamespaces);
```

### Caffeine cache with TTL (existing pattern in AbstractRestCatalogAccessor)
```java
// Source: AbstractRestCatalogAccessor.java lines 112-127
this.tableCache =
    Caffeine.newBuilder()
        .maximumSize(optionsManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_SIZE_ITEMS))
        .expireAfterWrite(
            optionsManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS),
            TimeUnit.SECONDS)
        .build(
            catalogAccessorTableCacheKey -> {
              return getCatalog().loadTable(catalogAccessorTableCacheKey.tableIdentifier());
            });
```

### CatalogAccessor lifecycle (existing close pattern)
```java
// Source: AbstractRestCatalogAccessor.java lines 546-552
@Override
public void close() throws Exception {
  if (icebergCatalogSupplier instanceof Closeable) {
    ((Closeable) icebergCatalogSupplier).close();
  }
  tableCache.invalidateAll();
  tableCache.cleanUp();
}
```

### Building catalog properties with modified URI (new pattern for this phase)
```java
// Derived from: RestIcebergCatalogPlugin.buildCatalogProperties() lines 378-394
// + Nessie branch URI format from https://projectnessie.org/guides/iceberg-rest/
private Map<String, String> buildBranchCatalogProperties(Configuration config, String branchName) {
  Map<String, String> properties = buildCatalogProperties(config);
  // Nessie branch encoding: append branch to base URI
  // e.g., "http://nessie:19120/iceberg" + "/" + "dev" = "http://nessie:19120/iceberg/dev"
  String branchUri = restEndpoint + "/" + branchName;
  properties.put(CatalogProperties.URI, branchUri);
  return properties;
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Single RESTCatalog per source | Per-branch RESTCatalog instances in bounded cache | Phase 22 (this phase) | Enables branch-aware queries in Phase 23 |
| Table cache key: `(userId, TableIdentifier)` | Same key, but each branch gets its own cache instance | Phase 22 (this phase) | Eliminates cross-branch cache poisoning (INF-02) |
| `ExpiringCatalogCache` for single catalog | `ExpiringCatalogCache` per-branch inside `BranchAwareCatalogAccessorCache` | Phase 22 (this phase) | Branch catalog lifecycle managed independently |

**Existing patterns preserved:**
- `IcebergCatalogPlugin.getCatalogAccessor()` continues to return the default accessor (for non-Nessie queries and background metadata refresh)
- `AbstractRestCatalogAccessor` table cache behavior unchanged
- `ExpiringCatalogCache` TTL behavior unchanged

## Open Questions

1. **Commit hash as REST prefix**
   - What we know: Nessie Iceberg REST supports commit hashes in the prefix (confirmed from Nessie source code analysis -- `decodePrefix()` method calls `resolveReferencePathElement()` which resolves branches, tags, and commit hashes)
   - What's unclear: Whether Dremio's AT COMMIT feature needs to work in Phase 22, or if it's deferred to Phase 23+
   - Recommendation: Phase 22 infrastructure should support any string as a branch cache key (including commit hashes). The actual AT COMMIT resolution is Phase 23 scope. **The cache can use commit hashes as keys with no changes.**

2. **Nessie config endpoint response for branch-scoped URIs**
   - What we know: When `RESTCatalog` is initialized with URI `http://host/iceberg/dev`, the Nessie server's config endpoint (`GET /v1/config`) returns `{ "defaults": { "prefix": "dev" } }`. The `RESTCatalog` then uses this prefix in all subsequent REST calls.
   - What's unclear: Does the config endpoint return different OAuth tokens or capabilities per branch?
   - Recommendation: Assume config endpoint response is branch-independent for auth/capabilities. Each per-branch RESTCatalog will call its own config endpoint on initialization -- this is expected and correct behavior.

3. **Branch cache size tuning**
   - What we know: Most users access 2-3 branches actively. Edge case: CI/CD systems querying many feature branches.
   - What's unclear: Optimal max size.
   - Recommendation: Default to 20 as `maximumSize`, configurable via a new support key `plugins.restcatalog.nessie.branch_cache.max_size`. This is generous for typical use and bounded enough to prevent resource exhaustion.

4. **Integration with `IcebergCatalogPlugin.close()`**
   - What we know: `IcebergCatalogPlugin.close()` calls `catalogAccessor.close()` for the default accessor. The branch accessor cache is a separate resource.
   - What's unclear: N/A -- the solution is clear.
   - Recommendation: Override `close()` in `RestIcebergCatalogPlugin` to close both `super.close()` (default accessor) and `branchAccessorCache.close()` (all branch accessors).

## Configuration Options

New support keys needed for this phase:

| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `plugins.restcatalog.nessie.branch_cache.max_size` | PositiveLong | 20 | Maximum number of per-branch RESTCatalog instances to cache |
| `plugins.restcatalog.nessie.branch_cache.expire_after_access_seconds` | PositiveLong | 1800 | TTL for idle branch accessor entries (matches `RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS` default) |

These should be added to `IcebergCatalogPluginOptions.java` following the existing naming pattern.

## Sources

### Primary (HIGH confidence)
- `AbstractRestCatalogAccessor.java` (lines 92-157) -- Caffeine cache pattern, constructor signature, `CatalogAccessorTableCacheKey` usage confirms per-instance isolation
- `ExpiringCatalogCache.java` (full file, 85 lines) -- Memoizing supplier with TTL, `Closeable` implementation, `invalidate()` behavior
- `IcebergRestCatalogAccessor.java` (full file, 87 lines) -- Constructor that wraps `ExpiringCatalogCache`, package-private `getRestCatalogProperties()`
- `RestIcebergCatalogPlugin.java` (lines 110-417) -- Constructor, `start()`, `detectNessieBackend()`, `createCatalog()`, `buildCatalogProperties()`, `createRestCatalog()` -- all the extension points for Phase 22
- `CatalogAccessorTableCacheKey.java` (full file, 71 lines) -- Cache key structure: `(userId, TableIdentifier)` only, no branch dimension
- `IcebergCatalogPluginOptions.java` (full file, 65 lines) -- Existing option key patterns, `RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS` default value (1800)
- `IcebergCatalogPlugin.java` (close/start methods) -- Lifecycle management: `start()` creates `catalogAccessor`, `close()` calls `catalogAccessor.close()`
- Caffeine 3.2.0 (managed in root `pom.xml` line 776-778) -- Version confirmed, already imported by `AbstractRestCatalogAccessor`

### Secondary (MEDIUM confidence)
- [Nessie Iceberg REST Configuration Guide](https://projectnessie.org/guides/iceberg-rest/) -- URI format: `{base}/iceberg/{branch}`, pipe separator for warehouse `{branch}|{warehouse}`, "Don't set prefix for Nessie"
- Nessie `IcebergApiV1ResourceBase.java` source (GitHub, fetched via WebFetch) -- `decodePrefix()` confirms commit hashes are supported as prefix values alongside branches and tags

### Tertiary (LOW confidence)
- OAuth2 token isolation per RESTCatalog instance -- based on training data about Iceberg `RESTSessionCatalog` creating per-instance `AuthSession`. Not verified against bytecode for the specific Dremio-customized Iceberg 1.7.0 JAR. Monitor for issues during integration testing.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all libraries already in use, no new dependencies
- Architecture: HIGH -- per-branch accessor cache is architecturally simple, reuses existing patterns
- Table cache isolation: HIGH -- verified from `AbstractRestCatalogAccessor` constructor that each instance gets its own Caffeine `LoadingCache`
- Pitfalls: HIGH -- identified from direct codebase analysis of `ExpiringCatalogCache.invalidate()`, URL encoding requirements, OAuth session lifecycle
- Configuration options: HIGH -- follows existing `IcebergCatalogPluginOptions` naming pattern exactly

**Research date:** 2026-03-09
**Valid until:** 2026-04-09 (stable domain -- Caffeine API, Iceberg RESTCatalog prefix mechanism, and Nessie URI format are all mature)
