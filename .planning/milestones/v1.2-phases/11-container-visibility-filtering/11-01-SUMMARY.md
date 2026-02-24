---
phase: 11-container-visibility-filtering
plan: "01"
subsystem: auth
tags: [rbac, catalog, container-visibility, prefix-matching, grant-scan]

# Dependency graph
requires:
  - phase: 07-vds-lifecycle-privilege-enforcement
    provides: "filterByVisibility() and isVisibleToUser() in CatalogServiceHelper for VDS/FUNCTION leaf filtering"
  - phase: 10-pds-select-enforcement-opt-in
    provides: "hasAnyPdsGrant() in RbacService, deny-by-default PDS model enabling uniform container filtering"
provides:
  - "getAccessibleObjectPaths() in RbacService for batch grant path collection"
  - "hasAccessibleChildUnderPath() in RbacService for single-container child accessibility check"
  - "Container visibility filtering in CatalogServiceHelper.getTopLevelCatalogItems() for spaces and sources"
  - "Folder visibility filtering in CatalogServiceHelper.isVisibleToUser() via hasAccessibleChildUnderPath()"
affects: [11-02, 12-integration-tests]

# Tech tracking
tech-stack:
  added: []
  patterns: ["Container visibility derivation from leaf grants via dot-delimited prefix matching", "Batch grant scan with in-memory prefix matching for top-level listings", "Single-container grant scan for child-listing folder checks"]

key-files:
  created: []
  modified:
    - "sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java"
    - "dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java"

key-decisions:
  - "Two methods instead of one: getAccessibleObjectPaths() for batch top-level use, hasAccessibleChildUnderPath() for single-folder checks -- avoids N full grant scans per top-level listing"
  - "Sources filtered same as spaces in getTopLevelCatalogItems() -- deny-by-default PDS means grant prefix scan works correctly for sources too"
  - "getUserAccessibleObjectPaths() returns null for RBAC-disabled and admin -- null means show all, avoids redundant checks in callers"
  - "Folder filtering uses hasAccessibleChildUnderPath() per-folder (single grant scan) rather than batch set -- folder listing is smaller cardinality"

patterns-established:
  - "Container visibility derivation: containerPath + '.' prefix prevents false matches (e.g., 'myspace' vs 'myspace2')"
  - "Batch vs single-container pattern: batch getAccessibleObjectPaths() for top-level, single hasAccessibleChildUnderPath() for child folders"
  - "Null-as-show-all pattern: getUserAccessibleObjectPaths() returns null when filtering should be skipped"

requirements-completed: [CONT-01, CONT-02, CONT-03, CONT-04]

# Metrics
duration: 3min
completed: 2026-02-21
---

# Phase 11 Plan 01: Container Visibility Filtering Core Summary

**Dot-delimited prefix matching on grant object paths to derive container visibility from leaf grants, with batch scan for top-level spaces/sources and per-folder scan for child listings**

## Performance

- **Duration:** 3 min
- **Started:** 2026-02-21T17:46:05Z
- **Completed:** 2026-02-21T17:49:24Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added `getAccessibleObjectPaths()` to RbacService for batch collection of all grant paths for a user's roles + PUBLIC
- Added `hasAccessibleChildUnderPath()` to RbacService for single-container child accessibility check with admin short-circuit
- Wired space and source filtering into `CatalogServiceHelper.getTopLevelCatalogItems()` using batch accessible paths set
- Extended `isVisibleToUser()` to filter folders via `hasAccessibleChildUnderPath()` instead of always returning true

## Task Commits

Each task was committed atomically:

1. **Task 1: Add getAccessibleObjectPaths() and hasAccessibleChildUnderPath() to RbacService** - `cf35397b4` (feat)
2. **Task 2: Wire container visibility into CatalogServiceHelper top-level and folder filtering** - `396d70cc3` (feat)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` - Added two container visibility methods: getAccessibleObjectPaths() (batch) and hasAccessibleChildUnderPath() (single container)
- `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` - Added getUserAccessibleObjectPaths() and hasChildUnder() helpers; filtered spaces/sources in getTopLevelCatalogItems(); filtered folders in isVisibleToUser()

## Decisions Made
- Two methods instead of one: `getAccessibleObjectPaths()` for batch top-level use (one grant scan for all containers), `hasAccessibleChildUnderPath()` for single-folder checks (avoids N full grant scans per top-level listing) -- per Research Pitfall 5
- Sources filtered same as spaces -- deny-by-default PDS model (Phase 10) means grant prefix scan correctly identifies sources with no accessible children
- `getUserAccessibleObjectPaths()` returns null for RBAC-disabled/admin as "show all" signal -- callers check `accessiblePaths == null` to skip filtering
- Folder filtering uses per-folder `hasAccessibleChildUnderPath()` rather than batch set -- folder listings are smaller cardinality than top-level

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Core container visibility algorithm is in place for the main catalog API path (`/api/v3/catalog`)
- Plan 02 will extend container filtering to remaining call sites: ResourceTreeResource, SourcesResource, SpaceResource, HomeResource
- Phase 12 integration tests can now test container visibility end-to-end

## Self-Check: PASSED

- FOUND: 11-01-SUMMARY.md
- FOUND: cf35397b4 (Task 1 commit)
- FOUND: 396d70cc3 (Task 2 commit)

---
*Phase: 11-container-visibility-filtering*
*Completed: 2026-02-21*
