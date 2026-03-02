---
phase: 07-vds-lifecycle-privilege-enforcement
plan: 02
subsystem: catalog-rbac
tags: [rbac, catalog, privilege-enforcement, vds-lifecycle, ddl-handlers, rest-api]
dependency_graph:
  requires:
    - Plan 01 (validateCreateViewPrivilege() on DatasetCatalog interface chain, DROP in resolveRbacObjectType)
  provides:
    - DROP privilege enforcement in DropViewHandler.toResult() via SQL DDL
    - ALTER privilege enforcement in CreateOrUpdateViewHandler update paths (versioned + non-versioned) via SQL DDL
    - Container-scoped CREATE_VIEW check in CreateOrUpdateViewHandler.toResult() via SQL DDL
    - CREATE_VIEW privilege enforcement in CatalogServiceHelper.createDataset() via REST API
    - ALTER privilege enforcement in CatalogServiceHelper update paths (versioned + non-versioned) via REST API
    - DROP privilege enforcement in CatalogServiceHelper delete paths (non-versioned + versioned) via REST API
  affects:
    - All VDS lifecycle operations (create, update, delete) via both SQL DDL and REST API paths
tech_stack:
  added: []
  patterns:
    - call-before-mutation: privilege check immediately before the operation that mutates (drop/update/create)
    - conditional-alter-check: ALTER check inside isUpdate guard — only fires when view already exists
    - container-scoped-create: validateCreateViewPrivilege() checks parent path, not view path
key_files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropViewHandler.java
    - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java
    - dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java
decisions:
  - "Fixed ALTER->DROP bug in DropViewHandler without adding new logic — single char change closes LIFE-02 via SQL DDL"
  - "ALTER checks in createVersionedView() and createView() placed after isUpdate &= exists so the check only fires when the view truly exists and will be updated"
  - "SqlGrant import added to CatalogServiceHelper (was missing); all 5 enforcement points use the same catalogSupplier.get() pattern already established in the file"
metrics:
  duration_minutes: 8
  tasks_completed: 2
  files_modified: 3
  completed_date: "2026-02-20"
---

# Phase 7 Plan 02: VDS Lifecycle Privilege Enforcement Call Sites Summary

**One-liner:** Wired ALTER, DROP, and container-scoped CREATE_VIEW enforcement into all 8 SQL DDL and REST API VDS mutation call sites, closing every enforcement gap from Plan 01's infrastructure.

## What Was Built

Closed all enforcement gaps by wiring the Plan 01 infrastructure into every VDS lifecycle call site:

**SQL DDL handlers (3 call sites):**

1. **DropViewHandler.toResult()** — Fixed bug: `ALTER` -> `DROP` privilege. A DROP VIEW DDL command now correctly requires DROP privilege on the view, not ALTER. This was the only change needed in this file.

2. **CreateOrUpdateViewHandler.toResult()** — Switched from `catalog.validatePrivilege(resolvedViewPath, SqlGrant.Privilege.CREATE_VIEW)` to `catalog.validateCreateViewPrivilege(resolvedViewPath)`. This uses the container-scoped method from Plan 01, which checks the privilege on the parent space/folder path, not the view path itself (LIFE-03).

3. **CreateOrUpdateViewHandler.createVersionedView()** — Added `if (isUpdate) { catalog.validatePrivilege(viewPath, SqlGrant.Privilege.ALTER); }` after `isUpdate &= exists`. Fires only when the view exists and CREATE OR REPLACE is in effect (LIFE-01, versioned path).

4. **CreateOrUpdateViewHandler.createView()** — Same ALTER guard added in the non-versioned path, after `isUpdate &= exists` (LIFE-01, non-versioned path).

**REST API paths in CatalogServiceHelper (5 call sites with 4 new enforcement points — import also added):**

5. **createDataset()** — Added `catalogSupplier.get().validateCreateViewPrivilege(namespaceKey)` before the `viewCreatorFactoryProvider` call. Enforces CREATE_VIEW on the parent container before any view creation via REST (LIFE-03).

6. **updateVersionedDataset()** — Added `catalogSupplier.get().validatePrivilege(namespaceKey, SqlGrant.Privilege.ALTER)` before `catalogSupplier.get().updateView()`. Enforces ALTER on versioned view update (LIFE-01).

7. **updateNonVersionedDataset()** — Added ALTER check inside the `VIRTUAL_DATASET` else-if branch, before `catalogSupplier.get().updateView()`. Enforces ALTER on non-versioned view update (LIFE-01).

8. **deleteDataset() VIRTUAL_DATASET case** — Added `catalogSupplier.get().validatePrivilege(new NamespaceKey(config.getFullPathList()), SqlGrant.Privilege.DROP)` before `namespaceService.deleteDataset()`. Enforces DROP on non-versioned view delete (LIFE-02).

9. **deleteVersionedView()** — Added `catalogSupplier.get().validatePrivilege(namespaceKey, SqlGrant.Privilege.DROP)` before the try block containing `catalogSupplier.get().dropView()`. Enforces DROP on versioned view delete (LIFE-02).

PDS, table, and function delete paths (`deleteVersionedTable`, `deleteVersionedFunction`) were intentionally not touched — out of scope for Phase 7.

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 1 | 780a9c566 | fix(07-02): fix DropViewHandler privilege bug and add ALTER/CREATE_VIEW enforcement to CreateOrUpdateViewHandler |
| 2 | 75a62ad0f | feat(07-02): add privilege enforcement to CatalogServiceHelper REST API VDS paths |

## Verification Results

All 8 plan verification checks pass:

1. `Privilege.DROP` in DropViewHandler.java — 1 match (line 55). PASS.
2. `Privilege.ALTER` in DropViewHandler.java — 0 matches (bug fixed). PASS.
3. `validateCreateViewPrivilege` in CreateOrUpdateViewHandler.java — 1 match (line 105). PASS.
4. `Privilege.ALTER` in CreateOrUpdateViewHandler.java — 2 matches (createVersionedView + createView). PASS.
5. `Privilege.CREATE_VIEW` in CreateOrUpdateViewHandler.java — 0 matches (replaced by container-scoped call). PASS.
6. `validateCreateViewPrivilege` in CatalogServiceHelper.java — 1 match (createDataset). PASS.
7. `Privilege.ALTER` in CatalogServiceHelper.java — 2 matches (updateVersionedDataset + updateNonVersionedDataset). PASS.
8. `Privilege.DROP` in CatalogServiceHelper.java — 2 matches (deleteDataset VIRTUAL_DATASET + deleteVersionedView). PASS.

Total enforcement call sites across all 3 files: 8 (1 + 3 + 4 = 8), matching the plan specification.

## Deviations from Plan

None - plan executed exactly as written.

## Self-Check: PASSED

Files exist:
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropViewHandler.java
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java
- FOUND: dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java

Commits exist:
- FOUND: 780a9c566
- FOUND: 75a62ad0f
