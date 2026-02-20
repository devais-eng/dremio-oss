---
phase: 06-rest-api-and-access-path-hardening
plan: "02"
subsystem: api
tags: [rbac, catalog, visibility-filtering, hk2, java]

# Dependency graph
requires:
  - phase: 04-catalog-enforcement-and-di-wiring
    provides: RbacService with hasPrivilege() and isAdminMember() methods
  - phase: 05-ddl-handlers-and-system-tables
    provides: RbacService wired into SabotContext and DI registry
provides:
  - CatalogServiceHelper with RBAC-gated catalog listing (VDS and FUNCTION visibility filtering)
  - filterByVisibility() and isVisibleToUser() filtering infrastructure
  - Admin short-circuit and RBAC-disabled passthrough in all listing paths
affects:
  - 06-03-PLAN (REST resource can assume catalog listing already filters by grants)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Post-fetch stream filter for namespace listing results
    - Null-safe RBAC guard: null rbacService or dremioConfig disables filtering
    - isFunctionVisibleToUser() for top-level function loop (no NameSpaceContainer wrapper needed)

key-files:
  created: []
  modified:
    - dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java
    - dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java
    - dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelperForVersioned.java

key-decisions:
  - "RbacService and DremioConfig added as last two @Nullable constructor params -- follows Phase 5 'last param' pattern, minimizes risk"
  - "filterByVisibility() applied AFTER pagination trim in getNamespaceChildrenForPath() -- known v1 limitation: pages may be smaller than maxChildren when items filtered out"
  - "Separate isFunctionVisibleToUser(FunctionConfig) helper for getTopLevelCatalogItems() loop -- FunctionConfig is available directly, no NameSpaceContainer wrapping needed"
  - "PDS (physical datasets) always visible -- only VIRTUAL_DATASET type is access-controlled per locked v1 decision"
  - "Test call sites pass null, null for RbacService and DremioConfig -- disables RBAC filtering in test contexts"

patterns-established:
  - "filterByVisibility pattern: null guard -> RBAC disabled guard -> admin short-circuit -> stream filter"

requirements-completed:
  - META-03

# Metrics
duration: 2min
completed: 2026-02-18
---

# Phase 6 Plan 02: Catalog Visibility Filtering Summary

**CatalogServiceHelper gains RBAC-gated listing: non-admin users see only VDS with SELECT grant and functions with EXECUTE grant; PDS, folders, spaces, sources, and homes remain always visible.**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-18T09:37:06Z
- **Completed:** 2026-02-18T09:39:00Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Injected RbacService and DremioConfig into CatalogServiceHelper via @Inject constructor (last two @Nullable params)
- Added filterByVisibility() that post-filters namespace children: null/disabled RBAC passthrough, admin short-circuit, then per-item isVisibleToUser() check
- Added isVisibleToUser() that gates VIRTUAL_DATASET by SELECT and FUNCTION by EXECUTE; PDS and containers always pass
- Added isFunctionVisibleToUser() for the top-level function loop in getTopLevelCatalogItems()
- Applied filterByVisibility() in getNamespaceChildrenForPath() after pagination trim
- Updated all 3 test constructor call sites with null, null to compile with new signature

## Task Commits

Each task was committed atomically:

1. **Task 1: Inject RbacService and DremioConfig, add visibility filtering** - `4b7bd7ca3` (feat)
2. **Task 2: Update test constructor call sites** - `3c4960bf2` (chore)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` - New constructor params, filterByVisibility(), isVisibleToUser(), isFunctionVisibleToUser(), filtering applied in listing paths
- `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` - Two call sites updated with null, null
- `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelperForVersioned.java` - One call site updated with null, null

## Decisions Made
- RbacService and DremioConfig added as last two @Nullable params following Phase 5 "add as LAST parameter" convention
- filterByVisibility() applied post-pagination-trim: pages may be smaller than maxChildren when items are filtered -- documented v1 limitation
- Separate isFunctionVisibleToUser(FunctionConfig) instead of wrapping FunctionConfig in a NameSpaceContainer for the top-level function loop

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- META-03 complete: catalog listing filtering is live for non-admin users
- Plan 06-03 (REST RBAC resource) can be executed independently -- catalog filtering is already in place
- Known v1 limitation documented: pagination may return fewer items than requested when RBAC filters are active

---
*Phase: 06-rest-api-and-access-path-hardening*
*Completed: 2026-02-18*
