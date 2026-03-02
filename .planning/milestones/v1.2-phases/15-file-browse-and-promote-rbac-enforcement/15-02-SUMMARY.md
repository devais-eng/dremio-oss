# Plan 15-02 Summary: Unit Tests

## What was done
Created unit tests for all 8 RBAC admin guards introduced in Plan 01.

### TestSourceResourceRbac.java (NEW — 15 tests)
- 3 tests x 5 methods (getSource, getFolder, getFile, saveFormatSettings, saveFolderFormat)
- Each method tested for: RBAC disabled (no block), admin (no block), non-admin (Permission denied)
- Uses direct SourceResource construction with mocked dependencies
- Non-admin tests use `assertThatThrownBy` with `UserException` + "Permission denied" assertions
- Admin/disabled tests use try-catch with `assertNotPermissionDenied` helper

### TestCatalogServiceHelper.java (6 new tests)
- 3 tests for `promoteToDataset` guard (disabled, admin, non-admin)
- 3 tests for browse guard via `getCatalogEntityByPath` → `getCatalogEntityFromNonPromotedFileOrFolder`
- Uses existing `rbacEnabledHelper` fixture with mocked RbacService/DremioConfig
- `getCatalogEntityFromCatalogItem` guard covered indirectly (called by `getCatalogEntityFromNonPromotedFileOrFolder`)

## Verification
- All 21 tests pass: `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`
- TestSourceResourceRbac: 15 tests, TestCatalogServiceHelper: 6 new tests
- Pre-existing failure `testGetNamespaceChildren_nonAdmin_foldersAlwaysVisible` is unrelated (confirmed by stash test)
