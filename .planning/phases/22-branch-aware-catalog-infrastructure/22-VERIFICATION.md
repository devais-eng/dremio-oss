---
phase: 22-branch-aware-catalog-infrastructure
verified: 2026-03-09T23:15:00Z
status: passed
score: 6/6 must-haves verified
re_verification: false
---

# Phase 22: Branch-Aware Catalog Infrastructure Verification Report

**Phase Goal:** Per-branch RESTCatalog instances are managed in a bounded cache with branch-isolated table metadata, ready for query integration
**Verified:** 2026-03-09T23:15:00Z
**Status:** passed
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Requesting a catalog accessor for a branch returns a RESTCatalog instance initialized with that branch's URI prefix | VERIFIED | `createBranchScopedAccessor()` (RestIcebergCatalogPlugin.java:259-274) builds `branchUri = restEndpoint + "/" + branchName`, sets `CatalogProperties.URI`, creates `IcebergRestCatalogAccessor` |
| 2 | Repeated requests for the same branch return the cached instance (not a new one) | VERIFIED | `BranchAwareCatalogAccessorCache.getOrCreate()` (line 87) uses `cache.get(branchName, factory::apply)` -- Caffeine's load-if-absent pattern returns the same instance |
| 3 | The branch cache is bounded by max entries and evicts stale entries by TTL | VERIFIED | Caffeine builder uses `.maximumSize(maxBranches).expireAfterAccess(expireAfterAccessSeconds, TimeUnit.SECONDS)` (BranchAwareCatalogAccessorCache.java:57-59) |
| 4 | Evicted RESTCatalog instances have close() called to prevent connection pool exhaustion | VERIFIED | `removalListener` at lines 60-75 calls `accessor.close()` in try/catch with warn-level logging on failure |
| 5 | Each per-branch accessor has its own isolated table cache (no cross-branch cache poisoning) | VERIFIED | Each `IcebergRestCatalogAccessor` extends `AbstractRestCatalogAccessor` which creates its own `LoadingCache<CatalogAccessorTableCacheKey, Table> tableCache` and `LoadingCache<CatalogAccessorTableCacheKey, View> viewCache` in constructor (AbstractRestCatalogAccessor.java:98-99, 112-131) |
| 6 | When isNessieDetected() is false, no branch cache is created and no new code paths execute | VERIFIED | `start()` wraps cache creation in `if (isNessieDetected)` (line 163); `detectNessieBackend()` returns early when `!enableNessie` (line 180-182) |

**Score:** 6/6 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `sabot/kernel/src/main/java/com/dremio/exec/store/IcebergCatalogPluginOptions.java` | Branch cache support keys (max size, TTL) | VERIFIED | Contains `RESTCATALOG_PLUGIN_NESSIE_BRANCH_CACHE_MAX_SIZE` (default 20, max 1000) and `RESTCATALOG_PLUGIN_NESSIE_BRANCH_CACHE_EXPIRE_AFTER_ACCESS_SECONDS` (default 1800, max 7200) at lines 65-72 |
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/BranchAwareCatalogAccessorCache.java` | Caffeine cache of per-branch IcebergRestCatalogAccessor instances | VERIFIED | 105 lines. Implements `AutoCloseable`. Caffeine `Cache<String, IcebergRestCatalogAccessor>` with bounded size, TTL, removalListener, `getOrCreate()`, `close()`. No TODOs, no stubs, no placeholders |
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` | Branch cache initialization, factory method, lifecycle, public getter | VERIFIED | Contains volatile `branchAccessorCache` field (line 130), conditional init in `start()` (163-176), `getCatalogAccessorForBranch()` public API (251-257), `createBranchScopedAccessor()` factory (259-274), `close()` override (277-287) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `RestIcebergCatalogPlugin.start()` | `BranchAwareCatalogAccessorCache constructor` | Conditional initialization after `detectNessieBackend()` | WIRED | `if (isNessieDetected) { branchAccessorCache = new BranchAwareCatalogAccessorCache(...)` at lines 163-169 |
| `RestIcebergCatalogPlugin.getCatalogAccessorForBranch()` | `BranchAwareCatalogAccessorCache.getOrCreate()` | Delegates branch lookup to cache | WIRED | `return branchAccessorCache.getOrCreate(branchName)` at line 256 |
| `BranchAwareCatalogAccessorCache removalListener` | `IcebergRestCatalogAccessor.close()` | Caffeine eviction callback | WIRED | `accessor.close()` at line 68, within removalListener lambda |
| `RestIcebergCatalogPlugin.close()` | `BranchAwareCatalogAccessorCache.close()` | Lifecycle cleanup | WIRED | `branchAccessorCache.close()` at line 280, before `super.close()` at line 286 |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| INF-01 | 22-01-PLAN | Per-branch RESTCatalog instances are cached with bounded size and TTL eviction (no connection pool exhaustion) | SATISFIED | Caffeine cache with `maximumSize()`, `expireAfterAccess()`, and `removalListener` calling `accessor.close()` on eviction |
| INF-02 | 22-01-PLAN | Table cache is isolated per branch (no cross-branch cache poisoning -- same table path on different branches returns correct data) | SATISFIED | Each branch gets its own `IcebergRestCatalogAccessor` instance which extends `AbstractRestCatalogAccessor`, creating independent `LoadingCache<CatalogAccessorTableCacheKey, Table>` and `LoadingCache<CatalogAccessorTableCacheKey, View>` per instance |

No orphaned requirements found. REQUIREMENTS.md maps exactly INF-01 and INF-02 to Phase 22, and both are covered by plan 22-01.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none) | - | - | - | No TODO, FIXME, placeholder, stub, or empty implementation patterns found in any phase-modified file |

### Human Verification Required

### 1. Branch-scoped URI correctness against Nessie REST API

**Test:** Start Dremio with a Nessie-backed RESTCATALOG source, query a table on a non-default branch using `getCatalogAccessorForBranch("dev")`, and verify the REST requests go to the correct branch-scoped URI.
**Expected:** Nessie REST API receives requests at `{baseUri}/dev/...` and returns branch-specific table metadata.
**Why human:** Requires a running Nessie server to validate the URI convention is correct and Nessie accepts the branch-scoped prefix.

### 2. Cache eviction actually closes RESTCatalog connections

**Test:** Configure branch cache with `max_size=2`, access 3 different branches, and verify the first branch's accessor is evicted and its underlying RESTCatalog connection pool is released.
**Expected:** Log message "Closing evicted branch accessor for branch '...' (cause: SIZE)" appears, and no connection leak occurs.
**Why human:** Requires runtime observation of connection pool behavior and log output.

### Gaps Summary

No gaps found. All 6 observable truths are verified against the actual codebase. All 3 artifacts exist, are substantive (no stubs or placeholders), and are properly wired. All 4 key links are confirmed present with matching code patterns. Both requirements (INF-01, INF-02) are satisfied. No anti-patterns detected.

The implementation matches the PLAN specification exactly. Both commits (1480ce866, a3b4ff342) are verified in git history with correct file changes.

---

_Verified: 2026-03-09T23:15:00Z_
_Verifier: Claude (gsd-verifier)_
