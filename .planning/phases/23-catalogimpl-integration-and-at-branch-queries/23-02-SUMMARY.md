---
phase: 23-catalogimpl-integration-and-at-branch-queries
plan: 02
subsystem: catalog
tags: [nessie, rest-catalog, branch-aware, iceberg, dispatch, catalogimpl]

requires:
  - phase: 23-catalogimpl-integration-and-at-branch-queries
    plan: 01
    provides: SupportsBranchAwareRestCatalog interface, CatalogUtil gate expansion, plan cache exclusion
  - phase: 22-branch-aware-catalog-infrastructure
    provides: BranchAwareCatalogAccessorCache and getCatalogAccessorForBranch on RestIcebergCatalogPlugin
provides:
  - Three-way dispatch in CatalogImpl.getTableSnapshotHelper (VersionedPlugin -> SupportsBranchAwareRestCatalog -> non-versioned)
  - Three-way dispatch in CatalogImpl.getDatasetHandleHelper (matching pattern)
  - getTableSnapshotForBranchAwareRestSource helper method
  - getDatasetHandleForBranchAwareRestSource helper method
affects: [24-testing]

tech-stack:
  added: []
  patterns: [three-way-dispatch-with-capability-detection, branch-scoped-table-loading-via-plugin-interface]

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java

key-decisions:
  - "No ConnectorException try/catch needed: SupportsBranchAwareRestCatalog.getDatasetHandleForBranch does not declare checked exceptions, so catch block omitted for cleaner code"
  - "CMP-02 satisfied by design: queries without AT BRANCH follow getTable(NamespaceKey) path, never entering getTableSnapshotHelper dispatch"
  - "BRQ-02 satisfied by design: default accessor uses base REST endpoint, Nessie resolves to current default branch on every call"

patterns-established:
  - "Three-way dispatch: isWrapperFor(VersionedPlugin) -> isWrapperFor(SupportsBranchAwareRestCatalog) && non-time-travel -> non-versioned fallback"
  - "Branch-aware helper follows same MaterializedDatasetTableProvider pattern as versioned source path"

requirements-completed: [BRQ-01, BRQ-02, CMP-02]

duration: 8min
completed: 2026-03-10
---

# Phase 23 Plan 02: CatalogImpl Three-Way Dispatch for AT BRANCH Queries Summary

**Three-way dispatch in CatalogImpl.getTableSnapshotHelper and getDatasetHandleHelper routing AT BRANCH queries on Nessie-enabled RESTCATALOG sources through SupportsBranchAwareRestCatalog to branch-scoped accessors**

## Performance

- **Duration:** 8 min
- **Started:** 2026-03-10T09:40:20Z
- **Completed:** 2026-03-10T09:48:24Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments
- Added three-way dispatch to getTableSnapshotHelper: VersionedPlugin -> SupportsBranchAwareRestCatalog (non-time-travel) -> non-versioned
- Added matching three-way dispatch to getDatasetHandleHelper for branch-aware sources
- New getTableSnapshotForBranchAwareRestSource resolves branch context and loads table via plugin's encapsulated branch-scoped accessor
- New getDatasetHandleForBranchAwareRestSource returns DatasetHandle via same pattern
- Verified CMP-02 (no AT BRANCH = unchanged behavior) and BRQ-02 (default branch freshness) satisfied by design

## Task Commits

Each task was committed atomically:

1. **Task 1: Add three-way dispatch to getTableSnapshotHelper with new branch-aware source method** - `b90156a4d` (feat)
2. **Task 2: Add three-way dispatch to getDatasetHandleHelper for branch-aware sources** - `8dbe71c64` (feat)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` - Three-way dispatch in getTableSnapshotHelper and getDatasetHandleHelper, plus two new helper methods for branch-aware REST source table loading

## Decisions Made
- Omitted ConnectorException try/catch in branch-aware methods because `SupportsBranchAwareRestCatalog.getDatasetHandleForBranch` does not declare checked exceptions (the underlying accessor catch is encapsulated within the plugin implementation).
- CMP-02 requires no additional implementation: queries without AT BRANCH flow through `getTable(NamespaceKey)` -> `DatasetManager.getTable()` -> `IcebergCatalogPlugin.getDatasetHandle()` -> default accessor, never entering the three-way dispatch.
- BRQ-02 likewise requires no additional implementation: the default accessor uses the base REST endpoint, and Nessie resolves to its current default branch on every REST call.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- CatalogImpl integration is complete: AT BRANCH queries on Nessie-enabled RESTCATALOG sources dispatch through SupportsBranchAwareRestCatalog
- Phase 23 (both plans) is fully done: interface, wrapper pattern, CatalogUtil gate, plan cache exclusion, and CatalogImpl dispatch
- Ready for Phase 24 (end-to-end testing)

---
*Phase: 23-catalogimpl-integration-and-at-branch-queries*
*Completed: 2026-03-10*

## Self-Check: PASSED

All files and commits verified:
- CatalogImpl.java: FOUND
- Commit b90156a4d: FOUND
- Commit 8dbe71c64: FOUND
- 23-02-SUMMARY.md: FOUND
