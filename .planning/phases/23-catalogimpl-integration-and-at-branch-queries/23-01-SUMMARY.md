---
phase: 23-catalogimpl-integration-and-at-branch-queries
plan: 01
subsystem: catalog
tags: [nessie, rest-catalog, branch-aware, iceberg, wrapper-pattern, plan-cache]

requires:
  - phase: 22-branch-aware-catalog-infrastructure
    provides: BranchAwareCatalogAccessorCache and getCatalogAccessorForBranch on RestIcebergCatalogPlugin
  - phase: 21-configuration-and-nessie-detection
    provides: enableNessie config, isNessieDetected flag, getDefaultBranch on RestIcebergCatalogPlugin
provides:
  - SupportsBranchAwareRestCatalog interface (3 methods) extending Wrapper
  - Conditional isWrapperFor/unwrap on RestIcebergCatalogPlugin gated on isNessieDetected
  - CatalogUtil.requestedPluginSupportsBranchAwareRest helper method
  - Expanded forATSpecifierAccess gate recognizing branch-aware REST sources
  - Plan cache exclusion for Nessie-enabled RESTCATALOG sources
affects: [23-02-PLAN, 24-testing]

tech-stack:
  added: []
  patterns: [narrow-interface-with-conditional-wrapper, encapsulated-branch-scoped-table-loading]

key-files:
  created:
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/SupportsBranchAwareRestCatalog.java
  modified:
    - plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogUtil.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheUtils.java

key-decisions:
  - "Used Object return type avoidance: getDatasetHandleForBranch encapsulates branch accessor + table load entirely within plugin, returning Optional<DatasetHandle> instead of exposing CatalogAccessor across module boundary"
  - "Separate requestedPluginSupportsBranchAwareRest method rather than extending requestedPluginSupportsVersionedTables to preserve semantic clarity between versioned and branch-aware concepts"
  - "Lightweight resolveVersionContext: no server round-trip, maps VersionContext type to ResolvedVersionContext directly with empty commit hash"

patterns-established:
  - "Narrow interface pattern: SupportsBranchAwareRestCatalog extends Wrapper with only 3 methods instead of full VersionedPlugin (25+)"
  - "Conditional wrapper: isWrapperFor returns true only when isNessieDetected, enabling runtime capability detection"
  - "Encapsulated module boundary: getDatasetHandleForBranch keeps CatalogAccessor usage within plugin module, caller in kernel only sees connector types"

requirements-completed: [INF-03, CMP-02]

duration: 10min
completed: 2026-03-10
---

# Phase 23 Plan 01: CatalogImpl Integration and AT Branch Queries Summary

**SupportsBranchAwareRestCatalog 3-method interface with conditional Wrapper pattern, CatalogUtil AT specifier gate expansion, and plan cache exclusion for Nessie-enabled RESTCATALOG sources**

## Performance

- **Duration:** 10 min
- **Started:** 2026-03-10T09:26:58Z
- **Completed:** 2026-03-10T09:37:40Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Created SupportsBranchAwareRestCatalog interface with resolveVersionContext, getDatasetHandleForBranch, and getDefaultBranch methods
- Implemented conditional isWrapperFor/unwrap on RestIcebergCatalogPlugin gated on isNessieDetected
- Expanded CatalogUtil.forATSpecifierAccess to route AT BRANCH queries on Nessie-enabled RESTCATALOG sources to the getTableSnapshot path
- Extended PlanCacheUtils.checkForVersionedTable to exclude branch-aware REST sources from plan cache (INF-03)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create SupportsBranchAwareRestCatalog interface and implement on RestIcebergCatalogPlugin** - `d6ae160d0` (feat)
2. **Task 2: Expand CatalogUtil AT specifier gate and PlanCacheUtils exclusion** - `1068ffb04` (feat)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/SupportsBranchAwareRestCatalog.java` - New 3-method interface extending Wrapper for branch-aware REST catalog access
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` - Implements SupportsBranchAwareRestCatalog with conditional isWrapperFor/unwrap, resolveVersionContext, and getDatasetHandleForBranch
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogUtil.java` - Added requestedPluginSupportsBranchAwareRest helper, expanded forATSpecifierAccess gate
- `sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheUtils.java` - Extended checkForVersionedTable to also detect branch-aware REST sources

## Decisions Made
- Used `getDatasetHandleForBranch(String, EntityPath, GetDatasetOption...)` instead of exposing `getCatalogAccessorForBranch` in the kernel-level interface. This encapsulates the branch accessor + table load entirely within the plugin module, avoiding a module dependency from kernel to plugin's CatalogAccessor type.
- Created a separate `requestedPluginSupportsBranchAwareRest` method rather than extending `requestedPluginSupportsVersionedTables`. This preserves semantic clarity: "versioned tables" (VersionedPlugin) and "branch-aware REST" (SupportsBranchAwareRestCatalog) are distinct concepts with different capabilities.
- `resolveVersionContext` uses lightweight mapping (no server round-trip). Branch/tag names are resolved directly with empty commit hash since the REST catalog backend handles the actual branch resolution via URI prefix.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Added @Override annotation on existing getDefaultBranch method**
- **Found during:** Task 1 (interface implementation)
- **Issue:** Error-prone MissingOverride check flagged the existing `getDefaultBranch()` method, which now implements SupportsBranchAwareRestCatalog.getDefaultBranch()
- **Fix:** Added `@Override` annotation to the existing method
- **Files modified:** RestIcebergCatalogPlugin.java
- **Verification:** Compilation succeeds with error-prone checks
- **Committed in:** d6ae160d0 (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Required by error-prone compiler check. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- SupportsBranchAwareRestCatalog interface is ready for CatalogImpl to unwrap and dispatch AT BRANCH queries (Plan 02)
- CatalogUtil gate ensures AT BRANCH on Nessie-enabled RESTCATALOG sources enters getTableSnapshot path
- Plan cache safety ensures no stale cross-branch results

---
*Phase: 23-catalogimpl-integration-and-at-branch-queries*
*Completed: 2026-03-10*

## Self-Check: PASSED

All files and commits verified:
- SupportsBranchAwareRestCatalog.java: FOUND
- Commit d6ae160d0: FOUND
- Commit 1068ffb04: FOUND
- 23-01-SUMMARY.md: FOUND
