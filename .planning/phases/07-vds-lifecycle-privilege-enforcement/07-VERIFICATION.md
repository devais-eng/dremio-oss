---
phase: 07-vds-lifecycle-privilege-enforcement
verified: 2026-02-20T00:00:00Z
status: passed
score: 12/12 must-haves verified
re_verification: false
---

# Phase 7: VDS Lifecycle Privilege Enforcement Verification Report

**Phase Goal:** Users can only create, alter, and drop views when they hold the corresponding privilege — no view lifecycle operation succeeds without an explicit grant
**Verified:** 2026-02-20
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths (from Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A user without ALTER privilege who issues ALTER VIEW receives a permission denied error | VERIFIED | `validatePrivilege(path, SqlGrant.Privilege.ALTER)` called in `CreateOrUpdateViewHandler.createVersionedView()` (line 149) and `createView()` (line 179) under `if (isUpdate)` guard; also `updateVersionedDataset()` (line 1475) and `updateNonVersionedDataset()` (line 1698) in `CatalogServiceHelper`; error format is `"Permission denied: ALTER privilege required on '...'"` |
| 2 | A user without DROP privilege who issues DROP VIEW receives a permission denied error | VERIFIED | `validatePrivilege(path, SqlGrant.Privilege.DROP)` called in `DropViewHandler.toResult()` (line 55); `deleteDataset()` VIRTUAL_DATASET case (line 1742) and `deleteVersionedView()` (line 2058) in `CatalogServiceHelper`; DROP maps to "VDS" object type via `resolveRbacObjectType()` |
| 3 | A user without CREATE_VIEW privilege who issues CREATE VIEW receives a permission denied error | VERIFIED | `catalog.validateCreateViewPrivilege(resolvedViewPath)` called in `CreateOrUpdateViewHandler.toResult()` (line 105); `catalogSupplier.get().validateCreateViewPrivilege(namespaceKey)` called in `CatalogServiceHelper.createDataset()` (line 1295); uses container-scoped check via `viewKey.getParent().getSchemaPath()` |
| 4 | An ADMIN user can perform all three operations regardless of explicit grants | VERIFIED | Admin bypass lives in `RbacService.hasPrivilege()` via `isAdminMember()` short-circuit (line 127 of RbacService.java); `validatePrivilege()` and `validateCreateViewPrivilege()` also bypass on `SystemUser.isSystemUserName(userName)` at guard #2 |
| 5 | GRANT/REVOKE ALTER and GRANT/REVOKE DROP on a VDS are accepted by the SQL DDL layer | VERIFIED | `CatalogGrantHandler` accepts any `SqlGrant.Privilege` value (including ALTER, DROP) and passes `privilege.name()` to `rbacService.grantPrivilege()`; no whitelist filtering present — all privilege names flow through |

**Score: 5/5 success criteria truths verified**

---

### Required Artifacts (Plan 01)

| Artifact | Status | Evidence |
|----------|--------|----------|
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` | VERIFIED | "Permission denied" at lines 2840 and 2860; `validateCreateViewPrivilege` at line 2846; `case DROP:` at line 2877 in `resolveRbacObjectType()` |
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/DatasetCatalog.java` | VERIFIED | `void validateCreateViewPrivilege(NamespaceKey viewKey);` at line 264 |
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/SourceAccessChecker.java` | VERIFIED | `@Override public void validateCreateViewPrivilege(NamespaceKey viewKey)` at line 730, delegates to `delegate.validateCreateViewPrivilege(viewKey)` |
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/DelegatingCatalog.java` | VERIFIED | `@Override public void validateCreateViewPrivilege(NamespaceKey viewKey)` at line 561, delegates to `delegate.validateCreateViewPrivilege(viewKey)` |
| `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` | VERIFIED | "Permission denied" assertions in all denial tests; `validateCreateViewPrivilege` test methods at lines 1626, 1643, 1655; `myspace.myfolder` parent-path assertion at line 1639 |

### Required Artifacts (Plan 02)

| Artifact | Status | Evidence |
|----------|--------|----------|
| `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropViewHandler.java` | VERIFIED | `SqlGrant.Privilege.DROP` at line 55; no `Privilege.ALTER` (grep exit:1 — zero matches) |
| `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateOrUpdateViewHandler.java` | VERIFIED | `validateCreateViewPrivilege` at line 105; `Privilege.ALTER` at lines 149 and 179 under `if (isUpdate)` guards; `Privilege.CREATE_VIEW` — zero matches (replaced) |
| `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` | VERIFIED | `SqlGrant` import at line 98; `validateCreateViewPrivilege` at line 1295; `Privilege.ALTER` at lines 1475 and 1698; `Privilege.DROP` at lines 1742 and 2058 |

---

### Key Link Verification

| From | To | Via | Status | Evidence |
|------|-----|-----|--------|----------|
| `CatalogImpl.validateCreateViewPrivilege()` | `rbacService.hasPrivilege()` | `viewKey.getParent().getSchemaPath()` | WIRED | Line 2856: `String containerPath = viewKey.getParent().getSchemaPath()` then passed to `rbacService.hasPrivilege(...)` |
| `DatasetCatalog` interface | `CatalogImpl`, `SourceAccessChecker`, `DelegatingCatalog` | `void validateCreateViewPrivilege` signature | WIRED | Interface declares at line 264; CatalogImpl implements at 2846; SourceAccessChecker at 730; DelegatingCatalog at 561 |
| `DropViewHandler.toResult()` | `catalog.validatePrivilege(path, SqlGrant.Privilege.DROP)` | direct call before drop operation | WIRED | Line 55 in DropViewHandler; DROP confirmed, ALTER confirmed absent |
| `CreateOrUpdateViewHandler.createView/createVersionedView()` | `catalog.validatePrivilege(viewPath, SqlGrant.Privilege.ALTER)` | conditional call under `if (isUpdate)` | WIRED | Lines 148-150 (versioned), 178-180 (non-versioned); guard placed after `isUpdate &= exists` |
| `CreateOrUpdateViewHandler.toResult()` | `catalog.validateCreateViewPrivilege(resolvedViewPath)` | container-scoped call replacing old validatePrivilege(CREATE_VIEW) | WIRED | Line 105; `Privilege.CREATE_VIEW` absent (zero matches) |
| `CatalogServiceHelper.createDataset()` | `catalogSupplier.get().validateCreateViewPrivilege(namespaceKey)` | call before `viewCreatorFactoryProvider.get()...createView()` | WIRED | Line 1295; positioned before the viewCreatorFactory call at line 1302+ |
| `CatalogServiceHelper.deleteDataset() VIRTUAL_DATASET case` | `catalogSupplier.get().validatePrivilege(new NamespaceKey(...), SqlGrant.Privilege.DROP)` | call before `namespaceService.deleteDataset()` | WIRED | Line 1742; inside `case VIRTUAL_DATASET:` block before the delete call |
| `CatalogServiceHelper.deleteVersionedView()` | `catalogSupplier.get().validatePrivilege(namespaceKey, SqlGrant.Privilege.DROP)` | call before `catalogSupplier.get().dropView()` | WIRED | Line 2058; positioned before the try block containing dropView |

**Total enforcement call sites verified: 8** (1 DropViewHandler + 3 CreateOrUpdateViewHandler + 4 CatalogServiceHelper)

---

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|------------|-------------|-------------|--------|----------|
| LIFE-01 | 07-01, 07-02 | User can only ALTER a VDS if they have ALTER privilege | SATISFIED | ALTER check in `CreateOrUpdateViewHandler.createVersionedView()` + `createView()` (update paths); `CatalogServiceHelper.updateVersionedDataset()` + `updateNonVersionedDataset()` — all 4 enforcement points confirmed |
| LIFE-02 | 07-01, 07-02 | User can only DROP a VDS if they have DROP privilege | SATISFIED | DROP check in `DropViewHandler.toResult()` (fixes ALTER->DROP bug); `CatalogServiceHelper.deleteDataset()` VIRTUAL_DATASET case + `deleteVersionedView()` — all 3 enforcement points confirmed; DROP maps to "VDS" in `resolveRbacObjectType()` |
| LIFE-03 | 07-01, 07-02 | User can only CREATE a VDS if they have CREATE_VIEW privilege | SATISFIED | Container-scoped `validateCreateViewPrivilege()` replaces old direct call in `CreateOrUpdateViewHandler.toResult()`; `CatalogServiceHelper.createDataset()` also calls it; parent-path semantics (`viewKey.getParent().getSchemaPath()`) confirmed in implementation and tested |

**All 3 requirements satisfied. No orphaned requirements detected (LIFE-01, LIFE-02, LIFE-03 all claimed by both plans and traced to this phase in REQUIREMENTS.md).**

---

### Test Coverage Verification

**RBAC test methods in TestCatalogImpl.java (lines 1425+):**

| Test Method | Purpose | Assertion |
|-------------|---------|-----------|
| `testValidatePrivilege_rbacDisabled_noEnforcement` (1426) | RBAC OFF bypasses | No throw |
| `testValidatePrivilege_systemUser_bypasses` (1437) | System user bypasses | No throw |
| `testValidatePrivilege_noGrant_throwsPermissionDenied` (1447) | SELECT denied | "Permission denied" + "SELECT" |
| `testValidatePrivilege_withGrant_passes` (1463) | SELECT granted | No throw |
| `testValidatePrivilege_executeMapsToFunction` (1474) | EXECUTE -> FUNCTION object type | hasPrivilege called with "FUNCTION" |
| `testValidatePrivilege_executeDenied_throwsPermissionDenied` (1486) | EXECUTE denied | "Permission denied" + "EXECUTE" |
| `testValidatePrivilege_createViewMapsToVds` (1502) | CREATE_VIEW -> VDS object type | "Permission denied" + "CREATE_VIEW" |
| `testGetTable_vdsDenied_throwsPermissionDenied` (1519) | VDS getTable denied | "Permission denied" + "SELECT" |
| `testValidatePrivilege_definerRights_onlyOutermostChecked` (1537) | Definer rights bypass | No throw on inner |
| `testValidatePrivilege_nullDremioConfig_noEnforcement` (1554) | Null config bypasses | No throw |
| `testValidatePrivilege_alterDenied_throwsPermissionDenied` (1582) | **NEW** ALTER denied | "Permission denied" |
| `testValidatePrivilege_dropDenied_throwsPermissionDenied` (1598) | **NEW** DROP denied | "Permission denied" |
| `testValidatePrivilege_dropMapsToVds` (1614) | **NEW** DROP -> VDS | hasPrivilege called with "VDS" |
| `testValidateCreateViewPrivilege_denied_throwsPermissionDenied` (1626) | **NEW** CREATE_VIEW container denied | "Permission denied"; parent path "myspace.myfolder" verified |
| `testValidateCreateViewPrivilege_granted_passes` (1643) | **NEW** CREATE_VIEW container granted | No throw; "myspace.myfolder" verified |
| `testValidateCreateViewPrivilege_rbacDisabled_noEnforcement` (1655) | **NEW** CREATE_VIEW RBAC OFF | No throw |

**Total RBAC tests: 16 (10 existing updated + 6 new). No stale "not found" assertions in RBAC section.**

---

### Anti-Patterns Found

None. No TODO/FIXME/placeholder comments near enforcement points. No empty implementations. All 8 enforcement call sites make substantive calls to `validatePrivilege()` or `validateCreateViewPrivilege()` immediately before the mutating operation.

---

### Human Verification Required

None required for automated checks. The following items are noted as behavioral but can be confirmed from code structure alone:

1. **Admin bypass end-to-end** — The `RbacService.hasPrivilege()` `isAdminMember()` short-circuit ensures ADMIN users bypass all privilege checks. This is inherited by all 8 enforcement points. Verified by code path tracing.

2. **isUpdate guard correctness** — The ALTER check in `CreateOrUpdateViewHandler` fires only when the view pre-existed (`isUpdate &= exists` evaluated before the guard). This correctly avoids requiring ALTER privilege for pure CREATE operations. Verified by reading the conditional placement at lines 147-150 and 177-180.

---

### Commit Verification

All 4 documented commits exist in git history:

| Commit | Description |
|--------|-------------|
| `2a8cf2427` | feat(07-01): update validatePrivilege() error format, add DROP to resolveRbacObjectType(), add validateCreateViewPrivilege() to interface chain |
| `bb3c071ae` | test(07-01): update existing RBAC test assertions and add new tests for ALTER, DROP, CREATE_VIEW enforcement |
| `780a9c566` | fix(07-02): fix DropViewHandler privilege bug and add ALTER/CREATE_VIEW enforcement to CreateOrUpdateViewHandler |
| `75a62ad0f` | feat(07-02): add privilege enforcement to CatalogServiceHelper REST API VDS paths |

---

## Summary

Phase 7 goal is fully achieved. Every VDS lifecycle operation — create, alter, and drop — is now gated by an explicit privilege check across both SQL DDL and REST API paths.

**Infrastructure (Plan 01):**
- `validatePrivilege()` now throws `"Permission denied: {PRIVILEGE} privilege required on '{path}'"` (not the misleading "Table not found")
- `resolveRbacObjectType()` correctly maps DROP to "VDS" object type
- `validateCreateViewPrivilege()` implemented with container-scoped semantics (`viewKey.getParent().getSchemaPath()`) and propagated through the full `DatasetCatalog -> CatalogImpl -> SourceAccessChecker -> DelegatingCatalog` chain

**Call sites (Plan 02):**
- `DropViewHandler`: ALTER->DROP bug fixed; now correctly enforces DROP on SQL DROP VIEW
- `CreateOrUpdateViewHandler`: Container-scoped CREATE_VIEW on create path; ALTER on both versioned and non-versioned update paths
- `CatalogServiceHelper`: CREATE_VIEW on REST create; ALTER on versioned and non-versioned REST update; DROP on non-versioned and versioned REST delete
- PDS, table, and function paths intentionally untouched (out of scope)

All 16 RBAC unit tests pass assertions on "Permission denied" format; 6 new tests added covering ALTER, DROP, and container-scoped CREATE_VIEW scenarios.

---

_Verified: 2026-02-20_
_Verifier: Claude (gsd-verifier)_
