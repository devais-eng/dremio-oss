---
phase: 04-catalog-enforcement-and-di-wiring
plan: 02
subsystem: auth
tags: [rbac, catalog, getTable, getFunctions, vds-enforcement, udf-enforcement, create-view, privilege]

# Dependency graph
requires:
  - phase: 04-catalog-enforcement-and-di-wiring
    plan: 01
    provides: "RbacService DI wiring, validatePrivilege() 3-step enforcement, resolveRbacObjectType()"
  - phase: 03-service-layer
    provides: "RbacService.hasPrivilege() boolean resolution"
provides:
  - "RBAC enforcement in getTable, getTableNoResolve, getTableNoColumnCount (SELECT on VDS)"
  - "RBAC enforcement in getFunctions (EXECUTE on UDF)"
  - "isRbacDeniedForVds() private helper for VDS access checks"
  - "isRbacDeniedForFunction() private helper for UDF access checks"
  - "CREATE_VIEW privilege check in CreateOrUpdateViewHandler"
affects:
  - 04-03-unit-tests
  - 05-ddl-handlers
  - 06-catalog-integration

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Post-resolution filtering: RBAC check after table/function resolution, returning null/empty to simulate not-found"
    - "ViewTable instanceof guard: only VDS (ViewTable) is RBAC-checked, PDS passes through"
    - "Consistent 3-guard pattern: flag OFF -> allow, system user -> allow, null rbacService -> allow"

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java

key-decisions:
  - "isRbacDeniedForVds checks instanceof ViewTable to skip PDS -- physical datasets are never RBAC-gated"
  - "getFunctions RBAC check at entry point (before version context resolution) for early rejection"
  - "RBAC denial returns null/empty (not exception) preserving 'not found' information hiding semantics"

patterns-established:
  - "Post-resolution RBAC: resolve entity first, then check privilege, return null/empty if denied"
  - "Same 3-guard chain (flag, system user, null service) in both isRbacDeniedForVds and isRbacDeniedForFunction"

requirements-completed:
  - ENFC-01
  - ENFC-02
  - ENFC-03
  - ENFC-08

# Metrics
duration: 2min
completed: 2026-02-18
---

# Phase 4 Plan 02: Catalog Enforcement Hooks Summary

**RBAC enforcement in getTable/getFunctions resolution paths with VDS SELECT checks, UDF EXECUTE checks, and CREATE_VIEW privilege fix in CreateOrUpdateViewHandler**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-18T10:44:09Z
- **Completed:** 2026-02-18T10:45:48Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added isRbacDeniedForVds() and isRbacDeniedForFunction() helper methods with consistent 3-guard pattern (flag OFF, system user, null service)
- Enforced SELECT privilege on VDS in all 3 getTable variants (getTable, getTableNoResolve, getTableNoColumnCount) with 4 RBAC check call sites
- Enforced EXECUTE privilege on UDFs in getFunctions entry point, returning empty collection when denied
- Fixed CreateOrUpdateViewHandler to check CREATE_VIEW privilege instead of ALTER

## Task Commits

Each task was committed atomically:

1. **Task 1: Add RBAC enforcement in getTable and getFunctions paths** - `24b177f00` (feat)
2. **Task 2: Fix CreateOrUpdateViewHandler to check CREATE_VIEW instead of ALTER** - `6e5a1c7b7` (fix)

**Plan metadata:** (this commit, docs)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` - Added isRbacDeniedForVds(), isRbacDeniedForFunction(), RBAC checks in getTable variants and getFunctions
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java` - Changed SqlGrant.Privilege.ALTER to SqlGrant.Privilege.CREATE_VIEW

## Decisions Made
- isRbacDeniedForVds uses `instanceof ViewTable` to distinguish VDS from PDS -- physical datasets are never subject to RBAC checks
- getFunctions RBAC check placed at the entry point (before version context resolution) for early rejection rather than post-filtering
- All denial paths return null (getTable) or empty collection (getFunctions) rather than throwing exceptions, preserving "not found" information hiding

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- All getTable and getFunctions resolution paths now enforce RBAC when the feature flag is enabled
- Simple SELECT queries on VDS and UDF function calls are subject to privilege checks
- PDS (physical datasets) remain unaffected
- CREATE OR REPLACE VIEW checks the correct CREATE_VIEW privilege
- Unit tests for these enforcement hooks should be added in subsequent plans
- DDL handler enforcement (Phase 5) can build on the validatePrivilege() + resolution path hooks now in place

---
*Phase: 04-catalog-enforcement-and-di-wiring*
*Completed: 2026-02-18*

## Self-Check: PASSED

- CatalogImpl.java: FOUND (isRbacDeniedForVds 5 occurrences, isRbacDeniedForFunction 2 occurrences)
- CreateOrUpdateViewHandler.java: FOUND (Privilege.CREATE_VIEW 1 occurrence, Privilege.ALTER 0 occurrences)
- 04-02-SUMMARY.md: FOUND
- Commit 24b177f00: FOUND (Task 1)
- Commit 6e5a1c7b7: FOUND (Task 2)
