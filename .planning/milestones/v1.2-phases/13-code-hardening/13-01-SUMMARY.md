---
phase: 13-code-hardening
plan: 01
subsystem: auth
tags: [rbac, pds-enforcement, privilege-validation, admin-guard, catalog-hardening]

# Dependency graph
requires:
  - phase: 10-pds-select-enforcement
    provides: "isRbacDeniedForPds() method and PDS enforcement pattern"
  - phase: 07-enforcement-wiring
    provides: "validatePrivilege() and DropViewHandler DDL enforcement"
  - phase: 11-container-visibility
    provides: "CatalogServiceHelper RBAC guard patterns (rbacService/dremioConfig null guards)"
provides:
  - "PDS enforcement in CatalogImpl.getTable(String datasetId) closing INT-01"
  - "SELECT privilege check in DropViewHandler before view resolution (LIFE-02 UX fix)"
  - "Admin-only guard in CatalogServiceHelper.createSource() preventing metadata leak (Finding 4)"
  - "Three unit tests documenting new enforcement contracts"
affects: [14-docs-and-final-testing]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Null-guard-then-enforce: always check table != null before isRbacDeniedForPds"
    - "Pre-resolution privilege check: validatePrivilege(SELECT) before getTableNoColumnCount to prevent misleading errors"
    - "Admin guard before service delegation: RBAC admin check before sourceService to prevent metadata leak"

key-files:
  created:
    - "sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropViewHandler.java"
  modified:
    - "sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java"
    - "sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropViewHandler.java"
    - "dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java"
    - "sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java"
    - "dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java"

key-decisions:
  - "getTable(String) uses table.getPath() for PDS enforcement key (datasetId is JSON blob, not namespace path)"
  - "DropViewHandler SELECT check placed immediately after DROP check, before any table resolution"
  - "CatalogServiceHelper admin guard uses three-way null guard matching existing getUserAccessibleObjectPaths pattern"

patterns-established:
  - "All getTable overloads now participate in PDS enforcement"
  - "DDL handlers requiring table resolution check both action privilege and SELECT privilege"

requirements-completed: [PDS-02, LIFE-02]

# Metrics
duration: 4min
completed: 2026-02-23
---

# Phase 13 Plan 01: Code Hardening Summary

**Three surgical RBAC enforcement fixes closing INT-01 (PDS getTable(String)), LIFE-02 (DROP VIEW SELECT check), and Finding 4 (createSource admin guard) with paired unit tests**

## Performance

- **Duration:** 4 min
- **Started:** 2026-02-23T13:26:14Z
- **Completed:** 2026-02-23T13:30:38Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- CatalogImpl.getTable(String datasetId) now enforces PDS RBAC via isRbacDeniedForPds after table resolution, closing INT-01
- DropViewHandler.toResult() adds validatePrivilege(SELECT) between DROP check and getTableNoColumnCount, so DROP-only users see "Permission denied" not "Unknown view"
- CatalogServiceHelper.createSource() checks isAdminMember before delegating to sourceService, preventing "Source already exists" metadata leak for non-admins
- Three unit tests document the exact security contracts of each fix

## Task Commits

Each task was committed atomically:

1. **Task 1: Apply three surgical production code fixes** - `20faaca40` (fix)
2. **Task 2: Add unit tests for all three code fixes** - `3c0ca2d72` (test)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` - getTable(String) refactored with isRbacDeniedForPds guard, TODO removed
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropViewHandler.java` - validatePrivilege(SELECT) added after DROP check
- `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` - Admin guard added to createSource() before sourceService delegation
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` - testGetTableByDatasetId_pdsEnforcement added
- `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropViewHandler.java` - New file with testDropView_dropOnlyUser_getsPermissionDenied
- `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` - testCreateSource_nonAdmin_getsPermissionDenied added

## Decisions Made
- Used `table.getPath()` for PDS enforcement key in getTable(String) -- the raw datasetId is a serialized JSON blob, not a namespace path
- SELECT check in DropViewHandler placed immediately after DROP check and before all version resolution logic -- ensures the check fires before any table lookup
- Admin guard in createSource uses the existing three-way null guard pattern (rbacService != null && dremioConfig != null && RBAC_ENABLED) consistent with getUserAccessibleObjectPaths

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- All three code-level gaps from the v1.2 audit are closed
- Every enforcement path in CatalogImpl, DDL handlers, and REST API now has RBAC coverage
- Ready for Phase 14 (docs and final testing)

## Self-Check: PASSED

All 7 files verified present. Both task commits (20faaca40, 3c0ca2d72) verified in git log.

---
*Phase: 13-code-hardening*
*Completed: 2026-02-23*
