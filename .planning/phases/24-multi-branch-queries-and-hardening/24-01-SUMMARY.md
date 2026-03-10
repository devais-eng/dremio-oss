---
phase: 24-multi-branch-queries-and-hardening
plan: 01
subsystem: catalog
tags: [nessie, iceberg-rest, error-handling, branch-aware, catalog]

# Dependency graph
requires:
  - phase: 23-catalogimpl-integration-and-at-branch-queries
    provides: CatalogImpl three-way dispatch to getTableSnapshotForBranchAwareRestSource and getDatasetHandleForBranchAwareRestSource
  - phase: 22-branch-aware-catalog-infrastructure
    provides: BranchAwareCatalogAccessorCache, getCatalogAccessorForBranch
provides:
  - branchExists(String) method on SupportsBranchAwareRestCatalog interface (4th method)
  - branchExists implementation in RestIcebergCatalogPlugin using namespaceExists probe
  - Branch-not-found error with ReferenceNotFoundException wrapping in CatalogImpl
  - Table-not-found-on-branch error with branch and source context in CatalogImpl
  - Unit tests for branchExists (true/false paths)
affects:
  - 24-02 (hardening/testing)
  - Future: AT COMMIT path may need similar branch validation pattern

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Branch validation before table lookup: check branchExists() before getDatasetHandleForBranch() to give specific error
    - ReferenceNotFoundException wrapping for branch-not-found: matches UseVersionHandler/CatalogUtil native Nessie pattern
    - Probe pattern for branch existence: call namespaceExists(List.of()) on branch-scoped accessor as lightweight validity check

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/SupportsBranchAwareRestCatalog.java
    - plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java
    - plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/TestRestIcebergCatalogPlugin.java

key-decisions:
  - "Used namespaceExists(List.of()) as lightweight branch probe (not listNamespaces which doesn't exist in CatalogAccessor)"
  - "Branch-not-found throws UserException.validationError wrapping ReferenceNotFoundException to match native Nessie error pattern"
  - "Table-not-found-on-branch does NOT wrap ReferenceNotFoundException (different error category)"
  - "BranchProbePluginMock overrides getCatalogAccessorForBranch (not createBranchScopedAccessor which is private) for testability"

patterns-established:
  - "Branch validation pattern: if (!branchPlugin.branchExists(branchName)) throw ReferenceNotFoundException-wrapped UserException"
  - "Error message formats locked: 'Requested Branch X not found in source Y' and 'Table X not found on branch Y in source Z'"

requirements-completed: [BRQ-03]

# Metrics
duration: 8min
completed: 2026-03-10
---

# Phase 24 Plan 01: Branch Error Handling Summary

**Actionable branch-not-found vs table-not-found-on-branch error messages for AT BRANCH queries on Nessie-enabled RESTCATALOG sources, matching native Nessie error patterns via ReferenceNotFoundException wrapping**

## Performance

- **Duration:** 8 min
- **Started:** 2026-03-10T16:50:00Z
- **Completed:** 2026-03-10T16:58:00Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Extended SupportsBranchAwareRestCatalog with branchExists(String) as the 4th method
- Implemented branchExists in RestIcebergCatalogPlugin using namespaceExists probe on branch-scoped accessor
- Added branch validation and explicit error messages to both CatalogImpl dispatch methods
- Unit tests verify branchExists returns true for valid branch and false for invalid branch (exception swallowed)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add branchExists to SupportsBranchAwareRestCatalog and implement in RestIcebergCatalogPlugin** - `4821a4223` (feat)
2. **Task 2: Wire branch-not-found and table-not-found-on-branch error messages in CatalogImpl** - `53be7e54d` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/SupportsBranchAwareRestCatalog.java` - Added 4th method branchExists(String)
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` - Implemented branchExists using namespaceExists probe
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` - Branch validation + error messages in getTableSnapshotForBranchAwareRestSource and getDatasetHandleForBranchAwareRestSource
- `plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/TestRestIcebergCatalogPlugin.java` - Added BranchProbePluginMock, createBranchProbePlugin, testBranchExistsReturnsTrueForValidBranch, testBranchExistsReturnsFalseForInvalidBranch

## Decisions Made
- Used `namespaceExists(List.of())` as the probe method in `branchExists` since `listNamespaces()` is not in the `CatalogAccessor` interface. `namespaceExists` makes a single REST call to Nessie and throws if the branch URI is invalid.
- Branch-not-found wraps `ReferenceNotFoundException` as the cause of the `UserException` to match the native Nessie `UseVersionHandler`/`CatalogUtil` pattern. This ensures callers catching `ReferenceNotFoundException` work consistently.
- Table-not-found-on-branch does NOT wrap `ReferenceNotFoundException` (it is a different error category from branch-not-found).
- `BranchProbePluginMock` overrides `getCatalogAccessorForBranch` (public method) rather than `createBranchScopedAccessor` (private) for testability.

## Deviations from Plan

None - plan executed exactly as written. The probe method was adapted from `listNamespaces()` to `namespaceExists(List.of())` as pre-approved in the plan's note: "Check the actual CatalogAccessor interface -- if `listNamespaces()` is not available, use [alternative]".

## Issues Encountered
- Test compilation required `-Dsurefire.failIfNoSpecifiedTests=false` when using `-am` flag (the flag causes parent modules to also run tests, and they don't have `TestRestIcebergCatalogPlugin`). Resolved with the additional surefire flag.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- BRQ-03 satisfied: AT BRANCH queries on nonexistent branches produce "Requested Branch 'X' not found in source 'Y'" instead of confusing generic errors
- Table-not-found-on-branch produces "Table 'X' not found on branch 'Y' in source 'Z'" for actionable debugging
- Error message formats are locked. Phase 24-02 (hardening/integration tests) can assert against these exact formats.

---
*Phase: 24-multi-branch-queries-and-hardening*
*Completed: 2026-03-10*

## Self-Check: PASSED

- SupportsBranchAwareRestCatalog.java: FOUND
- RestIcebergCatalogPlugin.java: FOUND
- CatalogImpl.java: FOUND
- 24-01-SUMMARY.md: FOUND
- Commit 4821a4223: FOUND
- Commit 53be7e54d: FOUND
