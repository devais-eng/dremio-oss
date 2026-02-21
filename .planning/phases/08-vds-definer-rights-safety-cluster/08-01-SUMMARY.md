---
phase: 08-vds-definer-rights-safety-cluster
plan: 01
subsystem: auth
tags: [rbac, definer-rights, view-expansion, cycle-detection, CatalogEntityOwnershipImpl, ViewExpansionContext, ViewExpander, SqlConverter]

# Dependency graph
requires:
  - phase: 07-vds-lifecycle-privilege-enforcement
    provides: validatePrivilege/validateCreateViewPrivilege/validateDropViewPrivilege enforced at all VDS lifecycle call sites
provides:
  - VDS owner resolution in CatalogEntityOwnershipImpl (DEFN-01/02/03 activated)
  - Cycle detection via inExpansionPaths Set in ViewExpansionContext (DEFN-06)
  - Deleted-owner explicit error in ViewExpander when RBAC enabled (DEFN-05)
  - rbacEnabled wiring from DremioConfig into ViewExpander via SqlConverter
affects:
  - 08-02 (plan cache definer chain fix, DEFN-04)
  - 08-03 (tests for DEFN-01 through DEFN-06)
  - 10-pds-select-enforcement (definer's PDS access must resolve through ViewExpander correctly)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "DEFN-05 pattern: catch UserNotFoundException, check rbacEnabled && viewOwner != null, throw planError instead of falling back"
    - "DEFN-06 pattern: Set<String> inExpansionPaths in ViewExpansionContext, add on reserve, remove on release"
    - "rbacEnabled injection: boolean 4th constructor parameter to ViewExpander; SqlConverter reads from context.getDremioConfig()"

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java
    - sabot/kernel/src/main/java/com/dremio/exec/ops/ViewExpansionContext.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/ViewExpander.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/SqlConverter.java

key-decisions:
  - "Removed VIRTUAL_DATASET early-return from CatalogEntityOwnershipImpl; both PDS and VDS now return owner when non-null — activates DEFN-01/02/03 for free via existing ViewExpander identity-switching chain"
  - "Null/empty owner guard in CatalogEntityOwnershipImpl returns Optional.empty() for legacy VDS — preserves backward-compatible fallback to query user identity"
  - "Changed reserveViewExpansionToken signature to (CatalogIdentity, NamespaceKey) — cleaner than parallel checkForCycle() method; only one caller (ViewExpander)"
  - "DEFN-05 guard uses rbacEnabled && viewOwner != null — only throws explicit error when RBAC is active AND the view had a recorded owner; null viewOwner (legacy) still falls back"
  - "rbacEnabled passed as boolean constructor parameter to ViewExpander; SqlConverter reads it with null guard on getDremioConfig() to handle test contexts"

patterns-established:
  - "Pattern: CatalogEntityOwnershipImpl returns owner for all DATASET types (PDS and VDS) when owner is non-null; null/empty falls back to Optional.empty()"
  - "Pattern: ViewExpansionContext tracks in-expansion view paths as Set<String> alongside owner tokens; both released on token.release()"

requirements-completed:
  - DEFN-01
  - DEFN-02
  - DEFN-03
  - DEFN-05
  - DEFN-06

# Metrics
duration: 15min
completed: 2026-02-21
---

# Phase 08 Plan 01: VDS Definer Rights Safety Cluster Summary

**VDS definer rights activated by fixing CatalogEntityOwnershipImpl owner resolution, with cycle detection (DEFN-06) and deleted-owner explicit error (DEFN-05) as mandatory safety guards**

## Performance

- **Duration:** 15 min
- **Started:** 2026-02-21T00:00:00Z
- **Completed:** 2026-02-21T00:15:00Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Fixed root cause of DEFN-01/02/03: removed `if (dataset.getType() == DatasetType.VIRTUAL_DATASET) { return Optional.empty(); }` from `CatalogEntityOwnershipImpl.getCatalogEntityOwner()`; VDS now returns the owner, activating the existing definer rights chain in `ViewExpander.expandViewInternal()` for free
- Added cycle detection (DEFN-06) to `ViewExpansionContext`: `Set<String> inExpansionPaths` tracks all views currently being expanded; `reserveViewExpansionToken()` throws `UserException.validationError()` with "Cyclic view dependency detected" if a path is re-entered; path is released when token is released
- Added DEFN-05 deleted-owner guard to `ViewExpander.expandViewInternal()`: when `rbacEnabled && viewOwner != null` and `UserNotFoundException` is caught, throws `UserException.planError()` instead of silently falling back to query user; legacy fallback preserved when RBAC is disabled or owner is null
- Wired `rbacEnabled` from `DremioConfig.RBAC_ENABLED` into `ViewExpander` constructor via `SqlConverter`, using null guard on `getDremioConfig()` for test context safety

## Task Commits

Each task was committed atomically:

1. **Task 1: Fix CatalogEntityOwnershipImpl VDS owner resolution and add ViewExpansionContext cycle detection** - `ae68ad655` (feat)
2. **Task 2: Add rbacEnabled flag to ViewExpander, fix DEFN-05 deleted-owner fallback, and wire through SqlConverter** - `74e320a37` (feat)

## Files Created/Modified

- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java` - Removed VIRTUAL_DATASET early-return; all DATASET types return owner when non-null; removed DatasetType import
- `sabot/kernel/src/main/java/com/dremio/exec/ops/ViewExpansionContext.java` - Added `inExpansionPaths` Set, updated `reserveViewExpansionToken` signature to accept `NamespaceKey viewPath`, updated `ViewExpansionToken` to store and release path
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/ViewExpander.java` - Added `boolean rbacEnabled` field and constructor parameter; DEFN-05 guard in `expandViewInternal()` catch block; updated `reserveViewExpansionToken` call to pass `viewTable.getPath()`
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/SqlConverter.java` - Added `DremioConfig` import; wired `rbacEnabled` from `context.getDremioConfig()` into `ViewExpander` constructor

## Decisions Made

- Removed the VIRTUAL_DATASET type check entirely rather than adding a new branch — both PDS and VDS share the same null/empty owner guard, which is cleaner and avoids future divergence
- Used `NamespaceKey.getSchemaPath()` (returns dot-delimited string) for the cycle detection path key — consistent with how view paths are logged elsewhere in the codebase
- The `ViewExpansionToken` inner class now stores `viewPath` to enable release without needing to track it externally in the context class
- Null guard `context.getDremioConfig() != null &&` in SqlConverter is the same pattern used by `CatalogImpl.validatePrivilege()` and other RBAC checks established in Phase 4

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- DEFN-01/02/03/05/06 requirements complete; definer rights are now active for non-versioned VDS
- DEFN-04 (plan cache definer chain) remains open — plan cache key does not include definer chain; queries involving VDS with non-query-user definers may serve stale cached plans across different definer identities
- Phase 08-02 should address DEFN-04 before Phase 08 is declared complete
- Phase 08-03 (RBAC tests) can now be executed — all production code is in place

---
*Phase: 08-vds-definer-rights-safety-cluster*
*Completed: 2026-02-21*

## Self-Check: PASSED

All files verified present:
- sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java - FOUND
- sabot/kernel/src/main/java/com/dremio/exec/ops/ViewExpansionContext.java - FOUND
- sabot/kernel/src/main/java/com/dremio/exec/planner/sql/ViewExpander.java - FOUND
- sabot/kernel/src/main/java/com/dremio/exec/planner/sql/SqlConverter.java - FOUND
- .planning/phases/08-vds-definer-rights-safety-cluster/08-01-SUMMARY.md - FOUND

All commits verified:
- ae68ad655 - FOUND (Task 1: CatalogEntityOwnershipImpl + ViewExpansionContext)
- 74e320a37 - FOUND (Task 2: ViewExpander + SqlConverter)
