# Plan 15-01 Summary: Production Guards

## What was done
Added admin-only RBAC guards to all file browse and dataset promotion endpoints.

### SourceResource.java
- Added `@Nullable RbacService` and `@Nullable DremioConfig` constructor injection
- Added `requireAdmin(String operation)` helper method with three-way null guard pattern
- Added 3 FILE-01 browse guards: `getSource()`, `getFolder()`, `getFile()`
- Added 2 FILE-02 promote guards: `saveFormatSettings()`, `saveFolderFormat()`

### CatalogServiceHelper.java
- Added 2 FILE-01 browse guards: `getCatalogEntityFromNonPromotedFileOrFolder()`, `getCatalogEntityFromCatalogItem()`
- Added 1 FILE-02 promote guard: `promoteToDataset()`
- Used existing `rbacService` and `dremioConfig` fields (no constructor changes)

## Verification
- `grep -c "requireAdmin" SourceResource.java` = 6 (1 method + 5 calls)
- `grep -c "only administrators can" CatalogServiceHelper.java` = 4 (3 new + 1 existing createSource)
- All guards use identical three-way null guard pattern
- All guards throw `UserException.validationError()` with "Permission denied" message
