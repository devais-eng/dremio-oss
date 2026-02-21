---
phase: 12-metadata-safety-and-integration-testing
plan: 01
subsystem: auth
tags: [rbac, catalog, sys-privileges, admin-only, CatalogImpl]

# Dependency graph
requires:
  - phase: 10-pds-select-enforcement
    provides: isRbacDeniedForPds() guard pattern and isRbacDeniedForFunction() guard pattern used as template
  - phase: 09-udf-ownership-and-execution-enforcement
    provides: isRbacDeniedForFunction() pattern established
provides:
  - "isRbacDeniedForSysPrivileges() method enforcing admin-only access to sys.privileges"
  - "All 6 getTable() RBAC call sites now also enforce sys.privileges guard"
  - "4 META-01 unit tests documenting the guard's contract"
affects: [phase 12 integration tests, sys.privileges access control, system table security]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "sys.privileges guard: key-path check -> RBAC_ENABLED flag -> SystemUser bypass -> rbacService null guard -> isAdminMember()"
    - "equalsIgnoreCase for both root ('sys') and leaf ('privileges') to handle case variation"
    - "Guard fires before table fetch (unlike VDS/PDS which check table type after fetch)"

key-files:
  created: []
  modified:
    - "sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java"
    - "sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java"

key-decisions:
  - "isRbacDeniedForSysPrivileges uses isAdminMember() not hasPrivilege() -- sys.privileges access is role-based (admin membership), not grant-based"
  - "Guard fires before getTableHelper() in getTableNoResolve and getTableNoColumnCount -- more efficient than post-fetch check since no table object needed"
  - "SystemStoragePlugin.hasAccessPermission() NOT modified -- enforcement is CatalogImpl-layer only, consistent with VDS/PDS pattern"
  - "equalsIgnoreCase for key path components -- prevents bypass via SYS.PRIVILEGES or Sys.Privileges capitalization (Pitfall 1 from research)"

patterns-established:
  - "sys.privileges guard placement: before datasetManager.getTable() call (unlike VDS/PDS which are after)"
  - "isAdminMember() used for system-table access (role-based), hasPrivilege() used for VDS/PDS/UDF (grant-based)"

requirements-completed: [META-01]

# Metrics
duration: 10min
completed: 2026-02-22
---

# Phase 12 Plan 01: sys.privileges Admin-Only Enforcement Summary

**isRbacDeniedForSysPrivileges() guard added to CatalogImpl with admin-role check via isAdminMember(), wired into all 6 getTable() RBAC call sites, with 4 META-01 unit tests**

## Performance

- **Duration:** 10 min
- **Started:** 2026-02-22T00:00:00Z
- **Completed:** 2026-02-22T00:10:00Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added `isRbacDeniedForSysPrivileges(NamespaceKey)` private method to CatalogImpl immediately after `isRbacDeniedForFunction()`, using the same guard-chain pattern
- Wired the guard into all 6 RBAC call sites: `getTableNoResolve()`, `getTableNoColumnCount()`, `getTable(NamespaceKey)` (2 branches), `getTable(CatalogEntityKey)`, and `bulkGetTables` ValueTransformer
- Added 4 META-01 unit tests verifying RBAC-disabled bypass, system user bypass, admin allowed, and non-admin denied

## Task Commits

Each task was committed atomically:

1. **Task 1: Add isRbacDeniedForSysPrivileges() and wire into all getTable() overloads** - `9ffee3a12` (feat)
2. **Task 2: Add META-01 unit tests to TestCatalogImpl** - `6389866a4` (test)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` - New `isRbacDeniedForSysPrivileges()` method + 6 call site wiring (51 insertions)
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` - 4 META-01 test methods (62 insertions)

## Decisions Made
- Used `isAdminMember()` not `hasPrivilege()` for sys.privileges -- access is admin-role-based, not grant-based. Grants would require manual GRANT statements; admin role is automatic for admin users.
- Guard fires before `getTableHelper()` for `getTableNoResolve` and `getTableNoColumnCount` (pre-fetch), but inside `table != null` check for `getTable(NamespaceKey)` resolved branch (consistent with VDS/PDS pattern there).
- Used `equalsIgnoreCase` for both `"sys"` and `"privileges"` to prevent bypass via case variation.
- `SystemStoragePlugin.hasAccessPermission()` NOT modified -- CatalogImpl is the right enforcement layer.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- sys.privileges META-01 requirement complete
- All RBAC enforcement layers now cover: VDS (SELECT), PDS (SELECT), UDFs (EXECUTE), sys.privileges (admin role)
- Ready for Phase 12 Plan 02 (integration tests / remaining META requirements)

## Self-Check: PASSED

- FOUND: `.planning/phases/12-metadata-safety-and-integration-testing/12-01-SUMMARY.md`
- FOUND: `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java`
- FOUND: `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java`
- FOUND: commit `9ffee3a12` (feat: isRbacDeniedForSysPrivileges)
- FOUND: commit `6389866a4` (test: META-01 unit tests)

---
*Phase: 12-metadata-safety-and-integration-testing*
*Completed: 2026-02-22*
