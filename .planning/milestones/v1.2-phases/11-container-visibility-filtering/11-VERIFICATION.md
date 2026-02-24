---
phase: 11-container-visibility-filtering
verified: 2026-02-21T19:15:00Z
status: passed
score: 5/5 must-haves verified (Plan 01) + 5/5 must-haves verified (Plan 02)
gaps: []
human_verification:
  - test: "Non-admin user browsing catalog tree sees only spaces/sources containing accessible objects"
    expected: "Spaces/sources with no child grants are hidden; spaces/sources with at least one accessible VDS/FUNCTION are shown"
    why_human: "Full end-to-end REST API behavior across multiple endpoints requires running application"
  - test: "Nested folder visibility shows full ancestor chain"
    expected: "A grant on myspace.a.b.c.view causes folders a, b, c and space myspace to all appear"
    why_human: "Multi-level folder nesting behavior needs live API response verification"
---

# Phase 11: Container Visibility Filtering Verification Report

**Phase Goal:** Non-admin users see only the sources, spaces, and folders that contain at least one object they have access to -- the catalog tree reflects actual access, not the full hierarchy
**Verified:** 2026-02-21T19:15:00Z
**Status:** passed
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths (Plan 01)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A non-admin user sees only spaces containing at least one accessible VDS/FUNCTION in getTopLevelCatalogItems() | VERIFIED | CatalogServiceHelper.java:370 filters spaces via `hasChildUnder(accessiblePaths, spaceConfig.getName())` with batch `getUserAccessibleObjectPaths()` call at line 367 |
| 2 | A non-admin user sees only folders containing at least one accessible child object in isVisibleToUser() | VERIFIED | CatalogServiceHelper.java:3187-3189 checks `FOLDER` type and calls `rbacService.hasAccessibleChildUnderPath(userName, folderPath)` |
| 3 | An ADMIN user sees all spaces and folders regardless of grant coverage | VERIFIED | `getUserAccessibleObjectPaths()` returns null for admins (line 3141-3142); `hasAccessibleChildUnderPath()` has admin short-circuit (RbacService.java:405-407) |
| 4 | When RBAC is disabled, all containers remain visible (existing behavior) | VERIFIED | `getUserAccessibleObjectPaths()` returns null when RBAC disabled (line 3136-3139); `filterByVisibility()` returns unfiltered list when RBAC disabled (line 3161-3165) |
| 5 | A non-admin user sees only sources containing at least one accessible child object in getTopLevelCatalogItems() | VERIFIED | CatalogServiceHelper.java:376 filters sources via `hasChildUnder(accessiblePaths, sourceConfig.getName())` using same batch path scan |

**Score:** 5/5 truths verified

### Observable Truths (Plan 02)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Folders inside spaces are hidden from non-admin users if they contain no accessible child objects (SpaceResource, SpaceFolderResource) | VERIFIED | SpaceResource.java:186-188 and SpaceFolderResource.java:212-214 check FOLDER type and call `rbacService.hasAccessibleChildUnderPath(userName, folderPath)` |
| 2 | Folders inside home spaces are hidden from non-admin users if they contain no accessible child objects (HomeResource) | VERIFIED | HomeResource.java:623-625 checks FOLDER type and calls `rbacService.hasAccessibleChildUnderPath(userName, folderPath)` |
| 3 | Spaces are hidden from non-admin users in the SQL runner resource tree if they contain no accessible child objects (ResourceTreeResource) | VERIFIED | ResourceTreeResource.java:341-343 calls `getUserAccessiblePaths()` and `isContainerVisible(spaceConfig.getName(), accessiblePaths)` in getSpaces() |
| 4 | Sources are hidden from non-admin users in the SQL runner resource tree and /api/v2/sources endpoint | VERIFIED | ResourceTreeResource.java:373-375 filters sources in getSources(); SourcesResource.java:88-94 filters sources in getSources() with early continue before SourceUI creation |
| 5 | Unit tests verify getAccessibleObjectPaths() and hasAccessibleChildUnderPath() contracts including admin bypass, prefix matching, and dot-boundary safety | VERIFIED | RbacServiceTest.java:378-455 contains 7 test methods: 3 for getAccessibleObjectPaths (granted paths, PUBLIC grants, empty grants) + 4 for hasAccessibleChildUnderPath (prefix matching with dot-boundary safety, admin bypass, PUBLIC grants, deep nesting ancestor chain) |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` | getAccessibleObjectPaths() and hasAccessibleChildUnderPath() methods | VERIFIED | Both methods present with correct signatures, precondition checks, admin short-circuit, prefix+dot matching logic |
| `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` | Space/source filtering in getTopLevelCatalogItems, folder filtering in isVisibleToUser | VERIFIED | getUserAccessibleObjectPaths() helper (line 3135), hasChildUnder() helper (line 3151), filtering in both loops (lines 370, 376), folder case in isVisibleToUser (line 3187-3189) |
| `dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java` | Folder filtering in filterByRbacVisibility() | VERIFIED | FOLDER case at line 186-188, method called at line 124 |
| `dac/backend/src/main/java/com/dremio/dac/resource/HomeResource.java` | Folder filtering in filterByRbacVisibility() | VERIFIED | FOLDER case at line 623-625, method called at lines 236 and 518 |
| `dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java` | Folder filtering in filterByRbacVisibility() | VERIFIED | FOLDER case at line 212-214, method called at line 111 |
| `dac/backend/src/main/java/com/dremio/dac/resource/ResourceTreeResource.java` | Space and source filtering via RBAC injection | VERIFIED | RBAC DI injection (lines 74-75, 84-85, 91-92), getUserAccessiblePaths() (line 317), isContainerVisible() (line 330), getSpaces() filtered (line 341-343), getSources() filtered (line 373-375) |
| `dac/backend/src/main/java/com/dremio/dac/resource/SourcesResource.java` | Source filtering via RBAC injection | VERIFIED | RBAC DI injection (lines 62-63, 72-73, 79-80), getUserAccessiblePaths() (line 120), source filtering in getSources() (lines 88-94) with early continue |
| `sabot/kernel/src/test/java/com/dremio/exec/rbac/RbacServiceTest.java` | Unit tests for container visibility methods | VERIFIED | 7 test methods at lines 378-455, using real KVStore-backed RbacService instances, asserting correct behavior |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| CatalogServiceHelper.java | RbacService.java | getAccessibleObjectPaths() for batch filtering + hasAccessibleChildUnderPath() for folders | WIRED | Line 3144 calls `rbacService.getAccessibleObjectPaths(userName)`, line 3189 calls `rbacService.hasAccessibleChildUnderPath(userName, folderPath)` |
| SpaceResource.java / HomeResource.java / SpaceFolderResource.java | RbacService.hasAccessibleChildUnderPath() | FOLDER case in filterByRbacVisibility | WIRED | SpaceResource:188, HomeResource:625, SpaceFolderResource:214 all call `rbacService.hasAccessibleChildUnderPath(userName, folderPath)` |
| ResourceTreeResource.java | RbacService.getAccessibleObjectPaths() | Constructor injection + getSpaces()/getSources() filter | WIRED | Constructor injection at line 84-85, field at line 74; getSpaces() at line 341 and getSources() at line 373 use getUserAccessiblePaths() which calls getAccessibleObjectPaths() at line 327 |
| SourcesResource.java | RbacService.getAccessibleObjectPaths() | Constructor injection + getSources() filter | WIRED | Constructor injection at line 72-73, field at line 62; getSources() at line 88 calls getUserAccessiblePaths() which calls getAccessibleObjectPaths() at line 130 |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| CONT-01 | 11-01, 11-02 | Sources are only visible to non-admin users if they have access to at least one child object | SATISFIED | CatalogServiceHelper:376 (v3 API), ResourceTreeResource:373-375 (SQL runner), SourcesResource:88-94 (v2 API) all filter sources by accessible child paths |
| CONT-02 | 11-01, 11-02 | Spaces are only visible to non-admin users if they have access to at least one child object | SATISFIED | CatalogServiceHelper:370 (v3 API), ResourceTreeResource:341-343 (SQL runner) filter spaces by accessible child paths |
| CONT-03 | 11-01, 11-02 | Folders are only visible to non-admin users if they have access to at least one child object | SATISFIED | CatalogServiceHelper:3187-3189, SpaceResource:186-188, HomeResource:623-625, SpaceFolderResource:212-214 all check FOLDER type via hasAccessibleChildUnderPath() |
| CONT-04 | 11-01, 11-02 | Full ancestor path is shown when user has access to a leaf object deep in the hierarchy | SATISFIED | Prefix matching with dot-boundary (`containerPath + "."`) naturally matches all ancestor containers. Unit test `testHasAccessibleChildUnderPath_deepNesting_allAncestorsVisible` (RbacServiceTest:444-454) explicitly verifies 4-level deep nesting: myspace, myspace.a, myspace.a.b, myspace.a.b.c all return true for a grant on myspace.a.b.c.deepview |

No orphaned requirements found -- all 4 CONT-* requirements mapped to Phase 11 in REQUIREMENTS.md are claimed by plan frontmatters and verified.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| ResourceTreeResource.java | 95, 117, 142 | TODO: DX-94503 showing UDFs in SQL Runner | Info | Pre-existing TODO, not introduced by Phase 11; unrelated to container visibility |

No blocker or warning-level anti-patterns found. No stub implementations, no empty handlers, no placeholder returns, no console.log-only implementations.

### Commit Verification

All 4 commits from SUMMARY documents verified in git history:

| Commit | Message | Status |
|--------|---------|--------|
| cf35397b4 | feat(11-01): add container visibility methods to RbacService | VERIFIED |
| 396d70cc3 | feat(11-01): wire container visibility into CatalogServiceHelper top-level and folder filtering | VERIFIED |
| a9e31724c | feat(11-02): wire container visibility filtering into remaining resource endpoints | VERIFIED |
| d6e999096 | test(11-02): add unit tests for RbacService container visibility methods | VERIFIED |

### Human Verification Required

### 1. End-to-End Catalog Browsing as Non-Admin

**Test:** Log in as a non-admin user with SELECT on one VDS in space "analytics" but no grants on anything in space "finance". Browse the catalog tree via both the UI and REST API.
**Expected:** Space "analytics" appears in the catalog listing; space "finance" does not appear. Sources with no accessible PDS children are hidden.
**Why human:** Full REST API response shape and multi-endpoint consistency (v2 sources, v3 catalog, SQL runner tree) requires running the application and hitting all endpoints.

### 2. Nested Folder Ancestor Chain Visibility

**Test:** Create a deep folder structure (space > folderA > folderB > folderC > view). Grant SELECT on the view to a non-admin user. Browse the space contents at each level.
**Expected:** The space, folderA, folderB, and folderC all appear in listings. Sibling folders with no accessible children do not appear.
**Why human:** Multi-level folder traversal across different API endpoints (SpaceResource children, SpaceFolderResource children) needs live application verification.

### Gaps Summary

No gaps found. All observable truths are verified. All 8 artifacts pass three-level verification (exists, substantive, wired). All 4 key links are wired. All 4 requirements (CONT-01 through CONT-04) are satisfied. No blocker anti-patterns detected.

The implementation covers all container listing paths:
- **v3 Catalog API:** CatalogServiceHelper.getTopLevelCatalogItems() (spaces + sources), isVisibleToUser() (folders)
- **v2 Sources API:** SourcesResource.getSources() (sources)
- **SQL Runner Resource Tree:** ResourceTreeResource.getSpaces() (spaces), ResourceTreeResource.getSources() (sources)
- **Space/Home/Folder child listings:** SpaceResource, HomeResource, SpaceFolderResource filterByRbacVisibility() (folders)

Unit tests (7 methods) cover the core algorithm contract including admin bypass, PUBLIC grants, dot-boundary safety, and deep nesting ancestor chain visibility.

---

_Verified: 2026-02-21T19:15:00Z_
_Verifier: Claude (gsd-verifier)_
