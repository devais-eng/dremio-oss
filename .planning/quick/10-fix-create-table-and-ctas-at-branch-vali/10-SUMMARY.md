---
phase: quick-10
plan: 01
subsystem: sql-handlers
tags: [validation, at-branch, create-table, ctas, non-versioned-sources, quick-fix]
dependency_graph:
  requires: [quick-9]
  provides: [create-table-at-branch-validation, ctas-at-branch-validation]
  affects: [CreateEmptyTableHandler, CreateTableHandler]
tech_stack:
  added: []
  patterns: [isWrapperFor-VersionedPlugin-guard]
key_files:
  created:
    - sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestCreateEmptyTableHandler.java
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateEmptyTableHandler.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/query/CreateTableHandler.java
decisions:
  - "Used VersionContext.Type.NOT_SPECIFIED check (not just null) because SqlCreateEmptyTable.getRefType() returns NOT_SPECIFIED (not null) when no AT clause is specified"
  - "Validation placed in toResult() (CreateEmptyTableHandler) and getPlan() (CreateTableHandler) after VersionContext resolution but before CatalogEntityKey builder -- mirrors DropTableHandler pattern"
metrics:
  duration: "3 min"
  completed: "2026-03-11"
  tasks_completed: 2
  files_modified: 3
---

# Quick Task 10: Fix CREATE TABLE and CTAS AT BRANCH Validation Summary

**One-liner:** Added VersionedPlugin guard in CreateEmptyTableHandler and CreateTableHandler rejecting AT BRANCH/TAG/etc. on non-versioned REST catalog sources with a clear VALIDATION UserException.

## What Was Done

CREATE TABLE ... AT BRANCH and CTAS ... AT BRANCH on non-versioned REST catalog sources were silently ignoring the branch specification (getResolvedVersionContextIfVersioned returns null, validateResolvedVersionIsBranch(null) is a no-op). This gave users a false sense that data was created on the specified branch when it was actually created on the default.

The fix adds an early validation check in both handlers that rejects AT BRANCH/TAG/etc. on non-versioned sources with a clear error message, using the proven pattern from DropTableHandler (quick-9).

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | Add AT BRANCH validation to CreateEmptyTableHandler and CreateTableHandler | 0c6c354af | CreateEmptyTableHandler.java, CreateTableHandler.java |
| 2 | Create unit tests for CreateEmptyTableHandler AT BRANCH validation | 15c852a53 | TestCreateEmptyTableHandler.java |

## Key Implementation Detail

`SqlCreateEmptyTable.getRefType()` returns `VersionContext.Type` (not `ReferenceType` like `SqlDropTable`). When no AT clause is specified it returns `VersionContext.Type.NOT_SPECIFIED` (not null). The guard checks for both:

```java
if (sqlCreateEmptyTable.getRefType() != null
    && sqlCreateEmptyTable.getRefType() != VersionContext.Type.NOT_SPECIFIED) {
  StoragePlugin source = catalog.getSource(sourceName);
  if (source != null && !source.isWrapperFor(VersionedPlugin.class)) {
    throw UserException.validationError()
        .message(...)
        .buildSilently();
  }
}
```

## Deviations from Plan

None -- plan executed exactly as written.

## Self-Check

Files exist:
- sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateEmptyTableHandler.java - FOUND
- sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/query/CreateTableHandler.java - FOUND
- sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestCreateEmptyTableHandler.java - FOUND

Commits verified:
- 0c6c354af: fix(quick-10): add AT BRANCH validation...
- 15c852a53: test(quick-10): add unit tests...

All 3 tests in TestCreateEmptyTableHandler pass. TestDropTableHandler regression: all 3 tests pass.

## Self-Check: PASSED
