---
phase: quick-7
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
  - dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java
  - dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java
  - dac/backend/src/main/java/com/dremio/dac/resource/HomeResource.java
  - dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java
  - services/users/src/main/java/com/dremio/service/users/UserServiceUtils.java
autonomous: true
requirements: [COPILOT-1, COPILOT-2, COPILOT-3, COPILOT-4]

must_haves:
  truths:
    - "roleIds lookup in getAccessibleObjectPaths() is O(1) per grant (Set, not List)"
    - "hasAccessibleChildUnderPath() does not call grantStore.listAll() per container when called from resource filter loops"
    - "usernames containing semicolons are rejected at registration time"
  artifacts:
    - path: "sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java"
      provides: "Set-based roleIds in both container visibility methods + overload accepting precomputed Set<String>"
    - path: "services/users/src/main/java/com/dremio/service/users/UserServiceUtils.java"
      provides: "validateUsername blocks semicolons"
  key_links:
    - from: "SpaceResource / SpaceFolderResource / HomeResource / CatalogServiceHelper"
      to: "RbacService.hasAccessibleChildUnderPath(userName, folderPath, accessiblePaths)"
      via: "precomputed Set<String> passed from getAccessibleObjectPaths()"
      pattern: "hasAccessibleChildUnderPath.*accessiblePaths"
---

<objective>
Apply four actionable Copilot PR review items: two O(n) → O(1) performance fixes in RbacService, and one
security fix blocking semicolons in usernames to prevent filter-injection in JobsResource / JobsListingResource.

Purpose: Address security and performance defects identified in PR #4 code review.
Output: Modified RbacService.java, UserServiceUtils.java, and four callers of hasAccessibleChildUnderPath.
</objective>

<execution_context>
@/home/emanuele/.claude/get-shit-done/workflows/execute-plan.md
@/home/emanuele/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/quick/6-read-the-opened-pull-requests-and-evalua/6-SUMMARY.md
</context>

<tasks>

<task type="auto">
  <name>Task 1: Fix RbacService roleIds List→Set and add precomputed-path overload</name>
  <files>
    sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
  </files>
  <action>
Make three targeted changes to RbacService.java:

**Change 1 — getAccessibleObjectPaths(): List→Set for roleIds**

Lines 373-382 currently collect roleIds into a List, then call `.filter(grant -> roleIds.contains(...))` which is O(n) per grant. Change to Set:

```java
// BEFORE (lines 373-377):
List<String> roleIds =
    membershipStore.listByUser(userName).stream()
        .map(Membership::getRoleId)
        .collect(Collectors.toList());
roleIds.add(PUBLIC_ROLE_ID);

// AFTER:
Set<String> roleIds =
    membershipStore.listByUser(userName).stream()
        .map(Membership::getRoleId)
        .collect(Collectors.toCollection(java.util.HashSet::new));
roleIds.add(PUBLIC_ROLE_ID);
```

The `.filter(grant -> roleIds.contains(...))` call on line 380 is now O(1) per grant.

**Change 2 — hasAccessibleChildUnderPath(): List→Set for roleIds**

Lines 410-414 have the same List pattern inside the scan loop. Change to Set:

```java
// BEFORE (lines 410-414):
List<String> roleIds =
    membershipStore.listByUser(userName).stream()
        .map(Membership::getRoleId)
        .collect(Collectors.toList());
roleIds.add(PUBLIC_ROLE_ID);

// AFTER:
Set<String> roleIds =
    membershipStore.listByUser(userName).stream()
        .map(Membership::getRoleId)
        .collect(Collectors.toCollection(java.util.HashSet::new));
roleIds.add(PUBLIC_ROLE_ID);
```

**Change 3 — Add overload accepting precomputed Set&lt;String&gt; accessiblePaths**

The callers (SpaceResource, SpaceFolderResource, HomeResource, CatalogServiceHelper) call `hasAccessibleChildUnderPath(userName, folderPath)` inside a `.filter()` lambda that iterates over all children — each call triggers a full `grantStore.listAll()` scan. Add a new overload that accepts a precomputed set and does only an in-memory prefix check (no store access):

Add this method immediately after the existing `hasAccessibleChildUnderPath(String, String)` method (after line 425):

```java
/**
 * Overload accepting a precomputed set of accessible object paths (from
 * {@link #getAccessibleObjectPaths}). Does only an in-memory prefix check — no store access.
 *
 * <p>Use this overload when filtering multiple containers in a loop to avoid repeated
 * {@code grantStore.listAll()} scans. The caller is responsible for the ADMIN short-circuit and
 * for pre-computing the paths via {@code getAccessibleObjectPaths(userName)}.
 *
 * @param accessiblePaths precomputed set returned by {@link #getAccessibleObjectPaths}
 * @param containerPath dot-delimited container path (e.g., "myspace" or "myspace.folderA")
 * @return true if any accessible path starts with containerPath + "."
 */
public boolean hasAccessibleChildUnderPath(Set<String> accessiblePaths, String containerPath) {
  Preconditions.checkArgument(
      !Strings.isNullOrEmpty(containerPath), "containerPath must not be null or empty");
  String prefix = containerPath + ".";
  return accessiblePaths.stream().anyMatch(p -> p.startsWith(prefix));
}
```

Update the import block: `java.util.List` is no longer needed in the methods above (it is still used by `listMembersByRole`, `listGrantsByObject`, `validateAdminMembersExist`, and the sys-table methods) — leave `List` import in place. Add `java.util.HashSet` only if not already transitively available; prefer `Collectors.toCollection(HashSet::new)` which requires only `java.util.HashSet`. Since the file already imports `java.util.Set` (line 29), add `import java.util.HashSet;` after `import java.util.ArrayList;` on line 27.
  </action>
  <verify>
    cd /home/emanuele/IdeaProjects/dremio-oss && mvn -pl sabot/kernel -am -q test -Dtest=RbacServiceTest 2>&1 | tail -20
  </verify>
  <done>RbacServiceTest passes. RbacService compiles with Set-based roleIds and the new two-argument overload present.</done>
</task>

<task type="auto">
  <name>Task 2: Update callers to use precomputed-path overload</name>
  <files>
    dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java
    dac/backend/src/main/java/com/dremio/dac/resource/SpaceFolderResource.java
    dac/backend/src/main/java/com/dremio/dac/resource/HomeResource.java
    dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java
  </files>
  <action>
In each of the four callers, compute the accessible paths once before the filter loop and use the
two-argument overload. The pattern is identical in all four files.

**SpaceResource.java (around lines 167-192):**

Before `children.stream()`, insert:

```java
Set<String> accessiblePaths = rbacService.getAccessibleObjectPaths(userName);
```

Then change inside the `.filter()` lambda:

```java
// BEFORE:
return rbacService.hasAccessibleChildUnderPath(userName, folderPath);

// AFTER:
return rbacService.hasAccessibleChildUnderPath(accessiblePaths, folderPath);
```

Add import `java.util.Set;` if not already present.

**SpaceFolderResource.java (around lines 195-219):**

Same pattern. The RBAC-disabled / admin short-circuit (`return children;`) already fires before the stream, so insert `Set<String> accessiblePaths = rbacService.getAccessibleObjectPaths(userName);` immediately before `children.stream()` and replace the `hasAccessibleChildUnderPath(userName, folderPath)` call with `hasAccessibleChildUnderPath(accessiblePaths, folderPath)`.

Note: SpaceFolderResource's filter block does not have a leading `isAdminMember` guard at the stream level (the outer method already returned early for admin). Verify the `userName` variable is in scope at the insertion point; if the method reads it from `securityContext.getUserPrincipal().getName()` earlier, reuse that.

**HomeResource.java (around lines 605-630):**

Same pattern. The method already has `rbacService.isAdminMember(userName)` guard before the stream. Insert `Set<String> accessiblePaths = rbacService.getAccessibleObjectPaths(userName);` immediately before `children.stream()` and replace the `hasAccessibleChildUnderPath(userName, folderPath)` call.

**CatalogServiceHelper.java (around lines 3205-3242):**

The `filterByVisibility` method calls `isVisibleToUser(c, userName)` inside the stream, and `isVisibleToUser` calls `hasAccessibleChildUnderPath(userName, folderPath)`. Change `filterByVisibility` to precompute and pass the set down:

1. In `filterByVisibility`, after the admin short-circuit and before `children.stream()`, add:
   ```java
   Set<String> accessiblePaths = rbacService.getAccessibleObjectPaths(userName);
   ```
2. Change the stream call to pass it along:
   ```java
   return children.stream()
       .filter(c -> isVisibleToUser(c, userName, accessiblePaths))
       .collect(Collectors.toList());
   ```
3. Add `Set<String> accessiblePaths` as a third parameter to `isVisibleToUser` (private method, no external callers):
   ```java
   private boolean isVisibleToUser(NameSpaceContainer container, String userName, Set<String> accessiblePaths) {
   ```
4. Inside `isVisibleToUser`, replace `rbacService.hasAccessibleChildUnderPath(userName, folderPath)` with `rbacService.hasAccessibleChildUnderPath(accessiblePaths, folderPath)`.

Add import `java.util.Set;` if not already present in CatalogServiceHelper.
  </action>
  <verify>
    cd /home/emanuele/IdeaProjects/dremio-oss && mvn -pl dac/backend -am -q compile 2>&1 | tail -30
  </verify>
  <done>dac/backend compiles cleanly. No remaining calls to the single-argument hasAccessibleChildUnderPath inside filter lambdas — each call site now uses the precomputed-set overload.</done>
</task>

<task type="auto">
  <name>Task 3: Block semicolons in validateUsername to prevent filter injection</name>
  <files>
    services/users/src/main/java/com/dremio/service/users/UserServiceUtils.java
  </files>
  <action>
The `validateUsername` method currently blocks `"` and `:` (per comment DX-8156) but not `;`. Usernames are
concatenated into RSQL filter strings as `"usr==" + userName` in JobsResource and JobsListingResource; a
username containing `;` allows injecting additional filter clauses.

Update the method to also block `;`:

```java
// BEFORE:
public static boolean validateUsername(String input) throws IllegalArgumentException {
  // DX-8156: These two characters `":` currently cause trouble, particularly for constructing SQL
  // queries.
  return input != null
      && !input.isEmpty()
      && !input.contains(String.valueOf('"'))
      && !input.contains(":");
}

// AFTER:
public static boolean validateUsername(String input) throws IllegalArgumentException {
  // DX-8156: These characters cause trouble for SQL query construction and RSQL filter injection.
  // `;` is blocked to prevent filter injection via the usr== filter in JobsResource /
  // JobsListingResource (Copilot review item #3/#4, PR #4).
  return input != null
      && !input.isEmpty()
      && !input.contains(String.valueOf('"'))
      && !input.contains(":")
      && !input.contains(";");
}
```

No import changes needed.
  </action>
  <verify>
    cd /home/emanuele/IdeaProjects/dremio-oss && mvn -pl services/users -am -q test 2>&1 | tail -20
  </verify>
  <done>services/users tests pass. validateUsername rejects any input containing a semicolon.</done>
</task>

</tasks>

<verification>
1. RbacServiceTest passes (Task 1 verify command).
2. dac/backend compiles cleanly with no callers using single-arg hasAccessibleChildUnderPath inside filter lambdas (Task 2 verify command).
3. services/users tests pass (Task 3 verify command).
4. Spot-check: `grep -n "hasAccessibleChildUnderPath(userName" dac/backend/src/main/java/com/dremio/dac/resource/SpaceResource.java` returns no results.
5. Spot-check: `grep -n 'contains(";")' services/users/src/main/java/com/dremio/service/users/UserServiceUtils.java` returns one result.
</verification>

<success_criteria>
- RbacService.getAccessibleObjectPaths() and hasAccessibleChildUnderPath() both use Set&lt;String&gt; for roleIds (O(1) contains).
- A two-argument hasAccessibleChildUnderPath(Set&lt;String&gt;, String) overload exists in RbacService.
- All four callers (SpaceResource, SpaceFolderResource, HomeResource, CatalogServiceHelper) precompute accessiblePaths once and use the Set overload.
- UserServiceUtils.validateUsername() rejects usernames containing `;`.
- All existing tests pass.
</success_criteria>

<output>
After completion, create `.planning/quick/7-apply-all-actionable-copilot-review-item/7-SUMMARY.md`
</output>
