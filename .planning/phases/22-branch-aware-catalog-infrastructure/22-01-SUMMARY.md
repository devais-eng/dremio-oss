---
phase: 22-branch-aware-catalog-infrastructure
plan: 01
subsystem: catalog
tags: [caffeine, cache, nessie, iceberg-rest, branch-aware]

# Dependency graph
requires:
  - phase: 21-configuration-and-nessie-detection
    provides: enableNessie config, isNessieDetected/defaultBranch detection, RestIcebergCatalogPlugin lifecycle
provides:
  - BranchAwareCatalogAccessorCache with bounded Caffeine cache, TTL eviction, close-on-eviction
  - Branch cache support keys (max size, expire-after-access TTL)
  - getCatalogAccessorForBranch() public API on RestIcebergCatalogPlugin
  - createBranchScopedAccessor() factory producing per-branch IcebergRestCatalogAccessor with branch-scoped URI
  - Lifecycle integration (close branch cache before super.close())
affects: [23-branch-aware-query-routing, 24-testing-and-integration]

# Tech tracking
tech-stack:
  added: [caffeine-cache-for-branch-accessors]
  patterns: [per-branch-accessor-isolation, cache-eviction-resource-cleanup, conditional-initialization-behind-feature-flag]

key-files:
  created:
    - plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/BranchAwareCatalogAccessorCache.java
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/store/IcebergCatalogPluginOptions.java
    - plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java

key-decisions:
  - "expireAfterAccess (not expireAfterWrite) so actively-used branches stay cached"
  - "Branch cache initialized only when isNessieDetected=true (zero new code paths when Nessie not detected)"
  - "Branch URI constructed by appending branchName to restEndpoint (no URL encoding -- Nessie expects raw path segment)"
  - "close() calls branchAccessorCache.close() before super.close() to release branch resources first"

patterns-established:
  - "Per-branch accessor isolation: each branch gets its own IcebergRestCatalogAccessor with independent table/view caches"
  - "Caffeine removal listener for resource cleanup: evicted accessors have close() called to prevent connection pool exhaustion"

requirements-completed: [INF-01, INF-02]

# Metrics
duration: 5min
completed: 2026-03-09
---

# Phase 22 Plan 01: Branch-Aware Catalog Infrastructure Summary

**Caffeine-cached per-branch RESTCatalog infrastructure with bounded size, TTL eviction, close-on-eviction, and isolated table caches per branch**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-09T21:54:17Z
- **Completed:** 2026-03-09T21:59:52Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Created BranchAwareCatalogAccessorCache wrapping Caffeine Cache<String, IcebergRestCatalogAccessor> with bounded max size (default 20), expireAfterAccess TTL (default 30 min), and removal listener calling accessor.close() on eviction
- Added two support keys (RESTCATALOG_PLUGIN_NESSIE_BRANCH_CACHE_MAX_SIZE, RESTCATALOG_PLUGIN_NESSIE_BRANCH_CACHE_EXPIRE_AFTER_ACCESS_SECONDS) for runtime cache tuning
- Integrated branch cache into RestIcebergCatalogPlugin: conditional initialization in start(), getCatalogAccessorForBranch() public API, createBranchScopedAccessor() factory, and close() lifecycle cleanup
- Each per-branch accessor gets its own IcebergRestCatalogAccessor with isolated table/view LoadingCache (INF-02 satisfied by architecture)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add branch cache support keys and create BranchAwareCatalogAccessorCache** - `1480ce866` (feat)
2. **Task 2: Integrate branch cache into RestIcebergCatalogPlugin lifecycle** - `a3b4ff342` (feat)

## Files Created/Modified
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/BranchAwareCatalogAccessorCache.java` - Caffeine cache of per-branch IcebergRestCatalogAccessor instances with bounded size, TTL, and close-on-eviction
- `sabot/kernel/src/main/java/com/dremio/exec/store/IcebergCatalogPluginOptions.java` - Two new support keys for branch cache max size and expire-after-access TTL
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` - Branch cache field, start() initialization, factory method, public getter, close() override

## Decisions Made
- Used expireAfterAccess (not expireAfterWrite) so actively-used branches stay cached without unnecessary eviction
- Branch cache initialized only when isNessieDetected=true -- zero new code paths when Nessie is not detected (CMP-01 preserved)
- Branch URI constructed by appending branchName to restEndpoint without URL encoding -- Nessie Iceberg REST expects the raw branch name as a path segment
- close() calls branchAccessorCache.close() before super.close() to release branch resources before the default accessor

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Branch-aware catalog infrastructure complete and ready for Phase 23 (branch-aware query routing)
- getCatalogAccessorForBranch(branchName) returns a cached CatalogAccessor scoped to a specific Nessie branch
- Each branch accessor has isolated table/view caches preventing cross-branch cache poisoning

## Self-Check: PASSED

All files exist. All commits verified.

---
*Phase: 22-branch-aware-catalog-infrastructure*
*Completed: 2026-03-09*
