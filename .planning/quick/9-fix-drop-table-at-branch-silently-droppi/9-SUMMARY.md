---
phase: quick-9
plan: 01
subsystem: catalog
tags: [drop-table, versioned-plugin, validation, DDL, branch-aware]

requires:
  - phase: 21-configuration-and-nessie-detection
    provides: "VersionedPlugin interface and branch-aware infrastructure"
provides:
  - "VALIDATION UserException when AT BRANCH used on non-versioned source for DROP TABLE"
  - "Unit tests covering AT BRANCH validation in DropTableHandler"
affects: [24-multi-branch-queries-and-hardening]

tech-stack:
  added: []
  patterns: ["VersionedPlugin.isWrapperFor guard before DDL execution"]

key-files:
  created:
    - sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropTableHandler.java
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropTableHandler.java

key-decisions:
  - "Used DDL (not DML) in error message since DROP TABLE is DDL -- distinct from CatalogUtil DML message"
  - "Check placed after validatePrivilege but before version context construction to fail fast"
  - "Guard only fires when refType != null (AT clause explicitly specified)"

patterns-established:
  - "VersionedPlugin guard for DDL: check isWrapperFor(VersionedPlugin.class) before executing DDL with AT specifier"

requirements-completed: [QUICK-9]

duration: 5min
completed: 2026-03-11
---

# Quick Task 9: Fix DROP TABLE AT BRANCH Silently Dropping Tables Summary

**VersionedPlugin validation guard in DropTableHandler prevents silent table drops when AT BRANCH used on non-versioned REST catalog sources**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-11T10:53:01Z
- **Completed:** 2026-03-11T10:57:33Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added validation check in DropTableHandler.toResult() that throws VALIDATION UserException when AT BRANCH/TAG/COMMIT is used on a non-versioned source
- Created 3 unit tests covering: non-versioned source rejection, versioned source passthrough, and no-AT-clause regression
- Pattern mirrors existing CatalogUtil.getAndValidateSourceForTableManagement() approach

## Task Commits

Each task was committed atomically:

1. **Task 1: Add AT BRANCH validation for non-versioned sources in DropTableHandler** - `0def0e93e` (fix)
2. **Task 2: Add unit tests for DropTableHandler AT BRANCH validation** - `430f1abed` (test)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropTableHandler.java` - Added VersionedPlugin validation check and StoragePlugin/VersionedPlugin imports
- `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropTableHandler.java` - 3 unit tests for AT BRANCH validation

## Decisions Made
- Used "DDL operations" in error message (not "DML operations") since DROP TABLE is DDL -- intentionally distinct from the DML message in CatalogUtil
- Placed check after `validatePrivilege` but before `sourceName` extraction to fail fast after privilege check
- Guard only fires when `refType != null` so normal DROP TABLE (without AT clause) is completely unaffected

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Fix is self-contained; no follow-up tasks required
- Same pattern can be applied to other DDL handlers (CREATE TABLE, ALTER TABLE) if similar silent-ignore bugs are found

## Self-Check: PASSED

All files verified present. All commit hashes verified in git log.

---
*Quick Task: 9-fix-drop-table-at-branch-silently-droppi*
*Completed: 2026-03-11*
