---
phase: 07-vds-lifecycle-privilege-enforcement
plan: 01
subsystem: catalog-rbac
tags: [rbac, catalog, privilege-enforcement, vds-lifecycle]
dependency_graph:
  requires: []
  provides:
    - validateCreateViewPrivilege(NamespaceKey) on DatasetCatalog interface and CatalogImpl
    - "Permission denied" error format in validatePrivilege()
    - DROP privilege mapped to VDS object type in resolveRbacObjectType()
  affects:
    - Plan 02 (wires validateCreateViewPrivilege() into DDL handlers and REST API call sites)
tech_stack:
  added: []
  patterns:
    - container-scoped privilege check using viewKey.getParent().getSchemaPath()
    - 3-guard chain pattern (flag off, system user, null service) reused in validateCreateViewPrivilege()
key_files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/DatasetCatalog.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/SourceAccessChecker.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/DelegatingCatalog.java
    - sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java
decisions:
  - "Error format is 'Permission denied: {PRIVILEGE} privilege required on {path}' — no GRANT hint per locked decision"
  - "validateCreateViewPrivilege() checks parent container path (viewKey.getParent().getSchemaPath()), not the view path itself"
  - "DROP maps to VDS object type (same as ALTER, SELECT) — not a separate object type"
metrics:
  duration_minutes: 5
  tasks_completed: 2
  files_modified: 5
  completed_date: "2026-02-20"
---

# Phase 7 Plan 01: VDS Lifecycle Privilege Enforcement Infrastructure Summary

**One-liner:** Enforcement foundation with "Permission denied" error format, DROP-to-VDS mapping, and container-scoped validateCreateViewPrivilege() across DatasetCatalog interface chain.

## What Was Built

Built the enforcement infrastructure that Plan 02 wires into DDL handlers and REST API call sites:

1. **Updated validatePrivilege() error format** — replaces misleading "Table not found" with `"Permission denied: {PRIVILEGE} privilege required on '{path}'"`. Includes privilege name and object path in both the exception message and the structured logger warning.

2. **Added DROP to resolveRbacObjectType()** — DROP now falls into the VDS group alongside ALTER, SELECT, and CREATE_VIEW. This is the correct mapping for view drop operations.

3. **Added validateCreateViewPrivilege(NamespaceKey viewKey)** — new method implementing a container-scoped CREATE_VIEW check. Uses `viewKey.getParent().getSchemaPath()` to evaluate the privilege on the parent space/folder, not on the view path itself. Follows the same 3-guard chain (flag off, system user, null service) as validatePrivilege().

4. **Propagated through interface chain** — DatasetCatalog interface declares `void validateCreateViewPrivilege(NamespaceKey viewKey)`. SourceAccessChecker and DelegatingCatalog both delegate to the wrapped catalog. CatalogImpl provides the implementation.

5. **Updated and expanded TestCatalogImpl** — 4 existing denial tests renamed and updated to assert `"Permission denied"` instead of `"not found"`. 6 new tests added: ALTER denied, DROP denied, DROP maps to VDS, CREATE_VIEW container-scoped denied (verifies parent path "myspace.myfolder" is checked, not "myspace.myfolder.myview"), CREATE_VIEW granted, CREATE_VIEW with RBAC disabled.

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 1 | 2a8cf2427 | feat(07-01): update validatePrivilege() error format, add DROP to resolveRbacObjectType(), add validateCreateViewPrivilege() to interface chain |
| 2 | bb3c071ae | test(07-01): update existing RBAC test assertions and add new tests for ALTER, DROP, CREATE_VIEW enforcement |

## Verification Results

All 9 plan verification checks pass:
1. "Permission denied" in CatalogImpl — 2 occurrences (validatePrivilege + validateCreateViewPrivilege)
2. "case DROP" in CatalogImpl resolveRbacObjectType() — confirmed
3. validateCreateViewPrivilege in CatalogImpl — confirmed
4. validateCreateViewPrivilege in DatasetCatalog interface — confirmed
5. validateCreateViewPrivilege in SourceAccessChecker — confirmed (delegation)
6. validateCreateViewPrivilege in DelegatingCatalog — confirmed (delegation)
7. "Permission denied" in TestCatalogImpl — 7 assertion occurrences across all denial tests
8. validateCreateViewPrivilege in TestCatalogImpl — 3 test method call sites
9. "myspace.myfolder" in TestCatalogImpl — confirmed parent path assertion in container-scoped tests

## Deviations from Plan

None - plan executed exactly as written.

## Self-Check: PASSED

Files exist:
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/catalog/DatasetCatalog.java
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/catalog/SourceAccessChecker.java
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/catalog/DelegatingCatalog.java
- FOUND: sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java

Commits exist:
- FOUND: 2a8cf2427
- FOUND: bb3c071ae
