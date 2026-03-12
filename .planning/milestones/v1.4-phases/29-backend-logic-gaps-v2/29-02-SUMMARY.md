---
phase: 29-backend-logic-gaps-v2
plan: 02
subsystem: auth
tags: [rbac, catalog, create-view, privilege-check, java]

# Dependency graph
requires:
  - phase: 25-backend-logic-fixes
    provides: "validateCreateViewPrivilege() initial implementation in CatalogImpl"
provides:
  - "Fixed CREATE_VIEW privilege lookup using objectType=SPACE and containerPath=viewKey.getRoot()"
  - "Tests asserting SPACE objectType and top-level space path for CREATE_VIEW checks"
affects: [integration-tests, rbac-enforcement, sql-create-view]

# Tech tracking
tech-stack:
  added: []
  patterns: [TDD RED-GREEN, privilege-key-alignment]

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java
    - sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java

key-decisions:
  - "Use viewKey.getRoot() (top-level space name) instead of getParent().getSchemaPath() to match REST API grant storage format"
  - "objectType must be SPACE not VDS to match the key format used by CatalogServiceHelper.enforceCreatePrivilege()"

patterns-established:
  - "Privilege lookup objectType and path must match the exact format used when the grant was stored"

requirements-completed: [LOGIC-03]

# Metrics
duration: 5min
completed: 2026-03-11
---

# Phase 29 Plan 02: CREATE_VIEW Privilege Fix Summary

**Fixed KV key mismatch in validateCreateViewPrivilege() — objectType changed from "VDS" to "SPACE" and containerPath changed from getParent().getSchemaPath() to getRoot(), aligning the lookup key with the REST API grant storage format**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-11T20:36:42Z
- **Completed:** 2026-03-11T20:41:46Z
- **Tasks:** 2 (Task 0 TDD RED + Task 1 TDD GREEN)
- **Files modified:** 2

## Accomplishments

- Updated two TestCatalogImpl test methods to assert SPACE objectType and "myspace" path (TDD RED)
- Fixed CatalogImpl.validateCreateViewPrivilege() with two one-line changes to resolve KV key mismatch
- All 3 validateCreateViewPrivilege tests pass GREEN after the production fix
- Non-admin users with CREATE_VIEW grant on a space can now successfully create views via SQL

## Task Commits

Each task was committed atomically:

1. **Task 0: Update tests to assert SPACE objectType (TDD RED)** - `23914067d` (test)
2. **Task 1: Fix objectType and containerPath in validateCreateViewPrivilege** - `8ab2ecd18` (feat)

_Note: TDD tasks have two commits — test (RED) then feat (GREEN)_

## Files Created/Modified

- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` - Fixed validateCreateViewPrivilege(): objectType "VDS"->"SPACE", containerPath getParent().getSchemaPath()->getRoot()
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` - Updated two test methods to assert correct post-fix values (SPACE, "myspace")

## Decisions Made

- Use `viewKey.getRoot()` (top-level space name) instead of `viewKey.getParent().getSchemaPath()` — the REST API stores grants using `ds.getPath().get(0)` which is equivalent to `getRoot()`, not the full parent path
- objectType must be `"SPACE"` not `"VDS"` — CatalogServiceHelper.enforceCreatePrivilege() stores CREATE_VIEW with objectType="SPACE", and RbacConfig.grantKey() does exact string matching, so the lookup objectType must match the stored objectType exactly
- resolveRbacObjectType() left untouched — it maps privileges for VDS-level SELECT/ALTER/DROP checks which correctly use "VDS"; CREATE_VIEW is a space-level grant

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - both changes were minimal one-liners as planned. The root cause (KV key mismatch) was well-understood from the plan context.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LOGIC-03 closed: CREATE_VIEW privilege check in CatalogImpl now matches the REST API grant storage format
- Non-admin users can create views in spaces where they have been granted CREATE_VIEW
- Remaining phase 29 plans (03+) can proceed with SysFlight row filtering

---
*Phase: 29-backend-logic-gaps-v2*
*Completed: 2026-03-11*

## Self-Check: PASSED

- FOUND: 29-02-SUMMARY.md
- FOUND: CatalogImpl.java (modified)
- FOUND: TestCatalogImpl.java (modified)
- FOUND commit: 23914067d (test TDD RED)
- FOUND commit: 8ab2ecd18 (feat GREEN fix)
