---
phase: quick-7
plan: 01
subsystem: rbac, users
tags: [performance, security, filter-injection, O(1)-lookup, rbac]
dependency_graph:
  requires: [quick-6]
  provides: [O(1)-roleIds-lookup, precomputed-path-overload, semicolon-blocked-usernames]
  affects: [RbacService, SpaceResource, SpaceFolderResource, HomeResource, CatalogServiceHelper, UserServiceUtils]
tech_stack:
  added: []
  patterns: [precomputed-set-before-loop, overload-for-callers, deny-injection-at-registration]
key_files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
    - dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java
    - dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java
    - dac/backend/src/main/java/com/dremio/dac/resource/HomeResource.java
    - dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java
    - services/users/src/main/java/com/dremio/service/users/UserServiceUtils.java
decisions:
  - "roleIds collect to HashSet via Collectors.toCollection(HashSet::new) — avoids List.contains O(n) per grant"
  - "new overload hasAccessibleChildUnderPath(Set<String>, String) does in-memory prefix check with no store access"
  - "callers compute getAccessibleObjectPaths() once before the filter loop and pass Set to overload"
  - "isVisibleToUser in CatalogServiceHelper gains accessiblePaths as third parameter (private method, no external callers)"
  - "semicolons blocked in validateUsername alongside existing double-quote and colon guards"
metrics:
  duration: "~15 minutes"
  completed: "2026-03-02"
  tasks_completed: 3
  files_modified: 6
---

# Quick Task 7: Apply All Actionable Copilot Review Items Summary

**One-liner:** Set-based O(1) roleIds lookup in RbacService + precomputed-path overload for filter loops + semicolon injection block in validateUsername.

## What Was Built

Applied four actionable Copilot PR review items identified in quick task 6:

### Task 1: RbacService List→Set and precomputed-path overload (6c9838845)

Three targeted changes to `RbacService.java`:

1. `getAccessibleObjectPaths()`: roleIds collected into `HashSet` via `Collectors.toCollection(HashSet::new)`. The `.filter(grant -> roleIds.contains(...))` call is now O(1) per grant instead of O(n).

2. `hasAccessibleChildUnderPath(String, String)`: same List→Set fix. The inner loop's `roleIds.contains(grant.getRoleId())` is now O(1).

3. New overload `hasAccessibleChildUnderPath(Set<String> accessiblePaths, String containerPath)`: does only an in-memory prefix check (`accessiblePaths.stream().anyMatch(p -> p.startsWith(prefix))`). No store access — callers pre-compute once and call this in their filter loops.

### Task 2: Update callers to use precomputed-path overload (ee122425a)

Four callers updated to compute `getAccessibleObjectPaths()` once before their `children.stream()` call:

- `SpaceResource.filterByRbacVisibility()`: `Set<String> accessiblePaths` inserted before stream; folder check uses `hasAccessibleChildUnderPath(accessiblePaths, folderPath)`
- `SpaceFolderResource.filterByRbacVisibility()`: same pattern
- `HomeResource.filterByRbacVisibility()`: same pattern
- `CatalogServiceHelper.filterByVisibility()`: precomputes `accessiblePaths`, passes to `isVisibleToUser(container, userName, accessiblePaths)` as new third parameter

### Task 3: Block semicolons in validateUsername (246251057)

`UserServiceUtils.validateUsername()` updated to add `&& !input.contains(";")` alongside existing `"` and `:` guards. Prevents RSQL filter injection via the `usr==<username>` filter pattern in `JobsResource` and `JobsListingResource`.

## Success Criteria Met

- [x] `RbacService.getAccessibleObjectPaths()` uses `Set<String>` for roleIds (O(1) contains)
- [x] `RbacService.hasAccessibleChildUnderPath(String, String)` uses `Set<String>` for roleIds (O(1) contains)
- [x] Two-argument `hasAccessibleChildUnderPath(Set<String>, String)` overload exists in RbacService
- [x] All four callers precompute `accessiblePaths` once and use the Set overload
- [x] `UserServiceUtils.validateUsername()` rejects usernames containing `;`

## Verification Results

- Spot-check 4: `grep -n "hasAccessibleChildUnderPath(userName"` returns no results in any of the four caller files
- Spot-check 5: `grep -n 'contains(";")'` returns one result at `UserServiceUtils.java:48`
- `RbacService.java` compiles cleanly with direct javac (confirmed zero errors)
- `UserServiceUtils.java` compiles cleanly with direct javac (confirmed zero errors)
- Note: Full Maven build is blocked by a pre-existing infrastructure limitation (`-Xep` Error Prone flags require specific JDK config); this failure is pre-existing and unrelated to our changes, confirmed by stash/unstash test.

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 1 | `6c9838845` | `perf(quick-7): fix RbacService roleIds List→Set and add precomputed-path overload` |
| 2 | `ee122425a` | `perf(quick-7): update callers to use precomputed-path overload in filter loops` |
| 3 | `246251057` | `fix(quick-7): block semicolons in validateUsername to prevent RSQL filter injection` |

## Deviations from Plan

None - plan executed exactly as written.

## Self-Check: PASSED

All files exist. All commits exist. No missing items.
