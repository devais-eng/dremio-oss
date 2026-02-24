---
phase: 11-container-visibility-filtering
plan: "02"
subsystem: auth
tags: [rbac, catalog, container-visibility, prefix-matching, resource-tree, sources, spaces, folders]

# Dependency graph
requires:
  - phase: 11-container-visibility-filtering
    plan: "01"
    provides: "getAccessibleObjectPaths() and hasAccessibleChildUnderPath() in RbacService, container visibility in CatalogServiceHelper"
  - phase: 10-pds-select-enforcement-opt-in
    provides: "deny-by-default PDS model enabling uniform source filtering same as spaces"
provides:
  - "Folder visibility filtering in SpaceResource, HomeResource, SpaceFolderResource via hasAccessibleChildUnderPath()"
  - "Space and source visibility filtering in ResourceTreeResource SQL runner tree via batch path scan"
  - "Source visibility filtering in SourcesResource /api/v2/sources endpoint via batch path scan"
  - "7 unit tests for getAccessibleObjectPaths() and hasAccessibleChildUnderPath() contracts"
affects: [12-integration-tests]

# Tech tracking
tech-stack:
  added: []
  patterns: ["RBAC DI injection into resource classes with @Nullable RbacService + DremioConfig constructor parameters", "getUserAccessiblePaths() null-as-show-all pattern reused across ResourceTreeResource and SourcesResource"]

key-files:
  created: []
  modified:
    - "dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java"
    - "dac/backend/src/main/java/com/dremio/dac/resource/HomeResource.java"
    - "dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java"
    - "dac/backend/src/main/java/com/dremio/dac/resource/ResourceTreeResource.java"
    - "dac/backend/src/main/java/com/dremio/dac/resource/SourcesResource.java"
    - "sabot/kernel/src/test/java/com/dremio/exec/rbac/RbacServiceTest.java"

key-decisions:
  - "ResourceTreeResource and SourcesResource use getUserAccessiblePaths() returning null-as-show-all -- same pattern as CatalogServiceHelper from Plan 01"
  - "isContainerVisible() helper in ResourceTreeResource uses stream().anyMatch() for prefix matching -- small set, no performance concern"
  - "SourcesResource filters early in loop before creating SourceUI and fetching dataset counts -- avoids wasted work for hidden sources"

patterns-established:
  - "RBAC DI injection pattern: @Nullable RbacService + @Nullable DremioConfig constructor parameters, null-guard + config-check + admin-check before filtering"
  - "Container visibility in resource classes: folders use per-folder hasAccessibleChildUnderPath(), top-level containers use batch getAccessibleObjectPaths()"

requirements-completed: [CONT-01, CONT-02, CONT-03, CONT-04]

# Metrics
duration: 5min
completed: 2026-02-21
---

# Phase 11 Plan 02: Container Visibility Endpoint Wiring Summary

**Folder, space, and source visibility filtering wired into SpaceResource, HomeResource, SpaceFolderResource, ResourceTreeResource, and SourcesResource with 7 unit tests for RbacService container visibility contract**

## Performance

- **Duration:** 5 min
- **Started:** 2026-02-21T17:53:11Z
- **Completed:** 2026-02-21T17:58:19Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Wired folder visibility filtering into SpaceResource, HomeResource, and SpaceFolderResource via `hasAccessibleChildUnderPath()` in each `filterByRbacVisibility()` method
- Added RBAC DI injection to ResourceTreeResource with `getUserAccessiblePaths()` and `isContainerVisible()` helpers, filtering both spaces in `getSpaces()` and sources in `getSources()`
- Added RBAC DI injection to SourcesResource with source filtering in `getSources()` via batch path scan
- Added 7 unit tests to RbacServiceTest covering: batch path retrieval (3 tests), single-container prefix matching with dot-boundary safety and deep nesting (4 tests)

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire folder, space, and source filtering into 5 resource classes** - `a9e31724c` (feat)
2. **Task 2: Add unit tests for RbacService container visibility methods** - `d6e999096` (test)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java` - Added folder filtering via hasAccessibleChildUnderPath() in filterByRbacVisibility()
- `dac/backend/src/main/java/com/dremio/dac/resource/HomeResource.java` - Added folder filtering via hasAccessibleChildUnderPath() in filterByRbacVisibility()
- `dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java` - Added folder filtering via hasAccessibleChildUnderPath() in filterByRbacVisibility()
- `dac/backend/src/main/java/com/dremio/dac/resource/ResourceTreeResource.java` - Added RBAC DI injection, getUserAccessiblePaths(), isContainerVisible(), space and source filtering
- `dac/backend/src/main/java/com/dremio/dac/resource/SourcesResource.java` - Added RBAC DI injection, getUserAccessiblePaths(), source filtering in getSources()
- `sabot/kernel/src/test/java/com/dremio/exec/rbac/RbacServiceTest.java` - Added 7 unit tests for getAccessibleObjectPaths() and hasAccessibleChildUnderPath()

## Decisions Made
- ResourceTreeResource and SourcesResource use `getUserAccessiblePaths()` returning null-as-show-all -- same pattern established in CatalogServiceHelper from Plan 01
- `isContainerVisible()` helper in ResourceTreeResource uses `stream().anyMatch()` for prefix matching -- small set, no performance concern
- SourcesResource filters early in loop before creating SourceUI and fetching dataset counts -- avoids wasted work for hidden sources

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- All container listing paths now have uniform RBAC visibility filtering
- Phase 11 (Container Visibility Filtering) is complete -- both plans done
- Phase 12 integration tests can now test full end-to-end container visibility across all API endpoints

## Self-Check: PASSED

- FOUND: 11-02-SUMMARY.md
- FOUND: a9e31724c (Task 1 commit)
- FOUND: d6e999096 (Task 2 commit)

---
*Phase: 11-container-visibility-filtering*
*Completed: 2026-02-21*
