---
phase: 21-configuration-and-nessie-detection
plan: 02
subsystem: api
tags: [iceberg-rest-catalog, nessie, nessie-detection, rest-catalog-properties, plugin-lifecycle]

# Dependency graph
requires:
  - phase: 21-01
    provides: "enableNessie boolean config field on RestIcebergCatalogPluginConfig"
provides:
  - "Nessie backend auto-detection via RESTCatalog config properties in start()"
  - "isNessieDetected() and getDefaultBranch() public getters on RestIcebergCatalogPlugin"
  - "Package-private getRestCatalogProperties() on IcebergRestCatalogAccessor using cached catalog path"
affects:
  - 22-branch-aware-catalog
  - 23-sql-and-api
  - 24-testing

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Detection in start() override after super.start() for access to initialized catalogAccessor"
    - "Volatile fields for cross-thread visibility of detection results"
    - "Cached ExpiringCatalogCache path for property reads (not raw catalogSupplier)"
    - "Broad exception catch in detection to ensure source startup never fails"

key-files:
  created: []
  modified:
    - "plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java"
    - "plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergRestCatalogAccessor.java"

key-decisions:
  - "Used volatile fields for isNessieDetected/defaultBranch since written in start() but read from query threads in Phase 22+"
  - "Broad Exception catch in detectNessieBackend() ensures source startup never fails due to detection errors"
  - "Used cached getCatalog() path (ExpiringCatalogCache) for property reads instead of raw catalogSupplier to avoid throwaway RESTCatalog instances"

patterns-established:
  - "Detection results accessed via public getters isNessieDetected()/getDefaultBranch() on RestIcebergCatalogPlugin"
  - "Property keys nessie.is-nessie-catalog and nessie.default-branch.name as Nessie REST config protocol"

requirements-completed: [CFG-02, CFG-03, CMP-01]

# Metrics
duration: 4min
completed: 2026-03-09
---

# Phase 21 Plan 02: Nessie Backend Detection Summary

**Nessie auto-detection in RestIcebergCatalogPlugin.start() reading nessie.is-nessie-catalog and default branch from RESTCatalog config properties via cached accessor path**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-09T21:05:04Z
- **Completed:** 2026-03-09T21:09:22Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added package-private `getRestCatalogProperties()` to `IcebergRestCatalogAccessor` using the cached `ExpiringCatalogCache` path (not the raw `catalogSupplier`)
- Implemented `detectNessieBackend()` in `RestIcebergCatalogPlugin.start()` override, fully gated behind `enableNessie` (CMP-01)
- Detection reads `nessie.is-nessie-catalog` and `nessie.default-branch.name` from the RESTCatalog config response properties
- Graceful degradation: non-Nessie backends and network errors log warnings without preventing source startup
- Public `isNessieDetected()` and `getDefaultBranch()` getters ready for Phase 22+ consumption

## Task Commits

Each task was committed atomically:

1. **Task 1: Add getRestCatalogProperties() and enableNessie field** - `5d6464650` (feat)
2. **Task 2: Implement Nessie detection in start() with public getters** - `df94a9682` (feat)

## Files Created/Modified
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergRestCatalogAccessor.java` - Added package-private `getRestCatalogProperties()` method that reads RESTCatalog properties via cached `getCatalog()` path
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` - Added `start()` override with `detectNessieBackend()`, volatile detection fields, static property key constants, class logger, and public getters

## Decisions Made
- Used `volatile` for `isNessieDetected` and `defaultBranch` fields because they are written during `start()` but may be read from different threads during query execution in later phases
- Broad `Exception` catch in `detectNessieBackend()` ensures the source always starts successfully even if detection fails (network error, unexpected response format, etc.)
- Used the cached `getCatalog()` path via `IcebergRestCatalogAccessor.getRestCatalogProperties()` instead of the raw `catalogSupplier.get()` to avoid creating throwaway `RESTCatalog` instances that would make redundant `GET /v1/config` calls

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- `isNessieDetected()` and `getDefaultBranch()` are available for Phase 22 to gate branch-aware `RESTCatalog` instance creation
- Detection is complete and stable -- Phase 22 can cast `StoragePlugin` to `RestIcebergCatalogPlugin` and check these getters
- The `enableNessie=false` (default) code path is completely untouched (CMP-01 satisfied)

## Self-Check: PASSED

- FOUND: RestIcebergCatalogPlugin.java
- FOUND: IcebergRestCatalogAccessor.java
- FOUND: 21-02-SUMMARY.md
- FOUND: commit 5d6464650
- FOUND: commit df94a9682

---
*Phase: 21-configuration-and-nessie-detection*
*Plan: 02*
*Completed: 2026-03-09*
