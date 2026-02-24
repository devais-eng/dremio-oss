# Phase 11: Container Visibility Filtering - Research

**Researched:** 2026-02-21
**Domain:** Java — catalog listing endpoints, RBAC grant store, namespace service, container derivation from child grants
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| CONT-01 | Sources are only visible to non-admin users if they have access to at least one child object | `SourcesResource.getSources()` and `CatalogServiceHelper.getTopLevelCatalogItems()` both enumerate `sourceService.getSources()` with no RBAC filter. A new `isContainerVisibleToUser(containerPath, userName)` helper must scan grants and return true if any accessible object path starts with `containerPath + "."`. |
| CONT-02 | Spaces are only visible to non-admin users if they have access to at least one child object | Same pattern as CONT-01 but for `namespaceService.getSpaces()`. Both `CatalogServiceHelper.getTopLevelCatalogItems()` and `ResourceTreeResource.getSpaces()` iterate spaces with no filter. |
| CONT-03 | Folders are only visible to non-admin users if they have access to at least one child object | The existing `filterByVisibility()` in `CatalogServiceHelper` and `filterByRbacVisibility()` in `SpaceResource`/`HomeResource` return all folders unconditionally (`return true; // folders always visible`). This comment must be changed to a child-accessibility check. |
| CONT-04 | Full ancestor path is shown when user has access to a leaf object deep in the hierarchy | Container visibility derivation by path prefix means: if a user has SELECT on `myspace.folderA.folderB.myview`, then `myspace`, `myspace.folderA`, and `myspace.folderA.folderB` all become visible by the prefix check. No extra logic is needed beyond prefix matching. |
</phase_requirements>

---

## Summary

Container visibility filtering adds a derived visibility layer on top of the existing leaf-level grant model. The RBAC infrastructure (grant store, `RbacService.hasPrivilege()`, admin short-circuit) is fully built. The existing `filterByVisibility()` method in `CatalogServiceHelper` (added in Phases 7-10) already handles VDS and FUNCTION leaf items but explicitly passes all containers through. The gap is: (1) folders are always passed in `filterByVisibility()`, and (2) top-level sources and spaces are not filtered at all in the enumeration endpoints.

The core algorithmic problem is: "does a user have access to any object whose path starts with container-path X?" Since grants are keyed by exact object path (not prefix-indexed), the answer requires a scan of the user's accessible objects. The grant store's `listAll()` returns all grants; user grants can be narrowed to the user's roles via `membershipStore.listByUser()`. A new `RbacService.hasAccessibleChildUnderPath(userName, containerPath)` method that scans user-role grants and prefix-matches object paths implements this efficiently enough for the grant store sizes expected in v1.2.

There are five distinct call sites that enumerate containers without RBAC filtering:
1. `CatalogServiceHelper.getTopLevelCatalogItems()` — spaces and sources at the top level
2. `ResourceTreeResource.getSpaces()` — used by the SQL runner left panel
3. `ResourceTreeResource.getSources()` — used by the SQL runner left panel
4. `SourcesResource.getSources()` — the `/api/v2/sources` endpoint
5. `filterByVisibility()` in `CatalogServiceHelper`, `SpaceResource`, `HomeResource` — folders in all child listing calls

**Primary recommendation:** Add `hasAccessibleChildUnderPath(userName, containerPath)` to `RbacService`, then wire it into each of the five call sites. Container filtering uses prefix matching on objectPath dot-segments (e.g., `"myspace.folderA"` matches `"myspace.folderA.myview"`).

---

## Standard Stack

### Core (no new dependencies — everything already exists)

| Component | Location | Purpose | Why Relevant |
|-----------|----------|---------|-------------|
| `RbacService` | `sabot/kernel/.../rbac/RbacService.java` | Central RBAC logic; has `isAdminMember()`, `hasPrivilege()` | New `hasAccessibleChildUnderPath()` method goes here |
| `GrantStore.listAll()` | `sabot/kernel/.../rbac/GrantStore.java:144` | Returns all grants (full table scan) | Needed for prefix scan; no indexed alternative exists in current KV store |
| `MembershipStore.listByUser()` | `sabot/kernel/.../rbac/MembershipStore.java:108` | Returns all role memberships for a user | Needed to know which roles a user holds |
| `CatalogServiceHelper.filterByVisibility()` | `dac/backend/.../service/catalog/CatalogServiceHelper.java:3127` | Existing VDS/FUNCTION filter; currently returns `true` for all containers | Must be extended to check containers |
| `CatalogServiceHelper.getTopLevelCatalogItems()` | `dac/backend/.../service/catalog/CatalogServiceHelper.java:352` | Top-level source + space enumeration | Must filter sources and spaces |
| `SpaceResource.filterByRbacVisibility()` | `dac/backend/.../resource/SpaceResource.java:161` | Filters children inside a space; folders pass through | Must extend folder case |
| `HomeResource.filterByRbacVisibility()` | `dac/backend/.../resource/HomeResource.java:598` | Same pattern as SpaceResource | Must extend folder case |
| `ResourceTreeResource.getSpaces()` | `dac/backend/.../resource/ResourceTreeResource.java:307` | Lists spaces for SQL runner panel | Must filter by container visibility |
| `ResourceTreeResource.getSources()` | `dac/backend/.../resource/ResourceTreeResource.java:336` | Lists sources for SQL runner panel | Must filter by container visibility |
| `SourcesResource.getSources()` | `dac/backend/.../resource/SourcesResource.java:69` | `/api/v2/sources` endpoint | Must filter by container visibility |
| `DremioConfig.RBAC_ENABLED` | `common/legacy/.../config/DremioConfig.java` | Feature flag — all filtering guarded by this | Used as gate in all existing RBAC checks |

### Supporting

| Component | Version/Location | Purpose | When to Use |
|-----------|---------|---------|-------------|
| `RbacConfig.KEY_SEP` (`"\|"`) | `sabot/kernel/.../rbac/RbacConfig.java` | Pipe delimiter in grant keys | Used in prefix-matching logic to avoid false matches (e.g., `"myspace.foo"` vs `"myspace.foobar"`) |
| `NamespaceKey.getSchemaPath()` | `services/namespace/.../NamespaceKey.java` | Dot-delimited path from namespace key | Used to derive container path strings |

---

## Architecture Patterns

### Recommended Code Structure

No new files needed. All changes are additions to existing classes:

```
sabot/kernel/src/main/java/com/dremio/exec/rbac/
  RbacService.java         ← add hasAccessibleChildUnderPath()

dac/backend/src/main/java/com/dremio/dac/service/catalog/
  CatalogServiceHelper.java ← extend filterByVisibility() + getTopLevelCatalogItems()

dac/backend/src/main/java/com/dremio/dac/resource/
  SourcesResource.java      ← filter getSources() loop
  SpaceResource.java        ← extend filterByRbacVisibility() folder case
  HomeResource.java         ← extend filterByRbacVisibility() folder case
  ResourceTreeResource.java ← filter getSpaces() and getSources()
```

### Pattern 1: Container Visibility Derivation (the core algorithm)

**What:** Determine whether a non-admin user has access to any object whose path is inside a container.
**When:** Every time a source, space, or folder would be shown to a non-admin user.

The object path format in the grant store is dot-delimited (e.g., `"mysource.schema.table"`, `"myspace.folderA.myview"`). A container path is a prefix of an object path. For a container at path `P`, a user has access to it if and only if at least one grant on any of the user's roles has `objectPath.startsWith(P + ".")`.

The dot + container path prefix check (`P + "."`) is essential to avoid false matches: a container `"myspace"` must not match grant path `"myspace2.view1"`.

```java
// New method in RbacService:
/**
 * Returns true if the user has at least one grant on any object whose dot-delimited path starts
 * with the given container path prefix (e.g., "myspace" or "myspace.folderA").
 *
 * Used to determine container visibility: a container is visible if and only if at least one
 * accessible child object exists somewhere in the subtree.
 *
 * ADMIN members always return true (short-circuit).
 *
 * @param userName the user to check
 * @param containerPath dot-delimited container path (e.g., "myspace" or "myspace.folderA")
 * @return true if any accessible object exists under this container
 */
public boolean hasAccessibleChildUnderPath(String userName, String containerPath) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(userName));
    Preconditions.checkArgument(!Strings.isNullOrEmpty(containerPath));

    // Admin short-circuit
    if (isAdminMember(userName)) {
        return true;
    }

    // Collect user's roles + PUBLIC
    List<String> roleIds = membershipStore.listByUser(userName).stream()
        .map(Membership::getRoleId)
        .collect(Collectors.toList());
    roleIds.add(PUBLIC_ROLE_ID);

    String prefix = containerPath + ".";  // dot separator prevents "myspace" matching "myspace2.x"

    // Scan all grants, check if any object path is under this container for any of the user's roles
    for (Grant grant : grantStore.listAll()) {
        if (roleIds.contains(grant.getRoleId()) && grant.getObjectPath().startsWith(prefix)) {
            return true;
        }
    }
    return false;
}
```

**Performance note:** `grantStore.listAll()` is a full KV scan. For a small grant store (hundreds of entries, typical in v1.2), this is acceptable. The method is called once per container in a listing, not in hot query paths.

### Pattern 2: Top-Level Container Filtering (sources and spaces)

**Call site:** `CatalogServiceHelper.getTopLevelCatalogItems()` at lines 366-371.

Currently:
```java
for (SpaceConfig spaceConfig : namespaceService.getSpaces()) {
    topLevelItems.add(CatalogItem.fromSpaceConfig(spaceConfig));
}
for (SourceConfig sourceConfig : sourceService.getSources()) {
    topLevelItems.add(CatalogItem.fromSourceConfig(sourceConfig));
}
```

After Phase 11 (non-admin users get filtered):
```java
for (SpaceConfig spaceConfig : namespaceService.getSpaces()) {
    if (isContainerVisibleToUser(spaceConfig.getName(), userName)) {
        topLevelItems.add(CatalogItem.fromSpaceConfig(spaceConfig));
    }
}
for (SourceConfig sourceConfig : sourceService.getSources()) {
    if (isContainerVisibleToUser(sourceConfig.getName(), userName)) {
        topLevelItems.add(CatalogItem.fromSourceConfig(sourceConfig));
    }
}
```

Where `isContainerVisibleToUser(String containerName, String userName)` is a private helper that:
1. Returns true if `rbacService == null || dremioConfig == null || !RBAC_ENABLED`
2. Returns true if admin
3. Calls `rbacService.hasAccessibleChildUnderPath(userName, containerName)`

### Pattern 3: Folder Visibility in Child Listing (extending filterByVisibility)

**Call site:** `CatalogServiceHelper.filterByVisibility()` at line 3127. Currently the folder case returns `true`:

```java
// Current (folders always visible):
return true; // FOLDER, SPACE, SOURCE, HOME are always visible (containers).
```

After Phase 11:
```java
if (container.getType() == NameSpaceContainer.Type.FOLDER) {
    String folderPath = String.join(".", container.getFullPathList());
    return rbacService.hasAccessibleChildUnderPath(userName, folderPath);
}
// SPACE, SOURCE, HOME at child level — keep visible (they won't appear here in practice)
return true;
```

The same pattern applies to `SpaceResource.filterByRbacVisibility()` and `HomeResource.filterByRbacVisibility()`.

### Pattern 4: ResourceTreeResource Container Filtering

**Call sites:** `ResourceTreeResource.getSpaces()` and `ResourceTreeResource.getSources()`.

These methods have no `rbacService` or `dremioConfig` dependencies currently. They need both injected:

```java
// Current constructor injection:
public ResourceTreeResource(
    Provider<NamespaceService> namespaceService,
    @Context SecurityContext securityContext,
    SourceService sourceService,
    ConnectionReader connectionReader,
    CatalogService catalogService) { ... }

// After Phase 11 (add nullable RBAC deps):
public ResourceTreeResource(
    Provider<NamespaceService> namespaceService,
    @Context SecurityContext securityContext,
    SourceService sourceService,
    ConnectionReader connectionReader,
    CatalogService catalogService,
    @Nullable RbacService rbacService,
    @Nullable DremioConfig dremioConfig) { ... }
```

### Pattern 5: SourcesResource Container Filtering

`SourcesResource` currently iterates all sources with no filtering. It also needs `rbacService`, `dremioConfig`, and `securityContext` added to its constructor for the guard.

### Anti-Patterns to Avoid

- **Checking for PDS in container filter**: CONT-01 says "at least one child object" — PDS objects are always accessible (Phase 10 opt-in: no grants = universally accessible). So a source containing only PDS with no grants is still visible (correct per requirements, since PDS are always accessible). Don't filter containers based only on VDS grants; include PDS visibility logic.
- **Using `startsWith(containerPath)` without dot**: `"myspace"` would match `"myspace2.view"` if the dot separator is omitted. Always use `containerPath + "."` as the prefix.
- **Checking the wrong container path for nested folders**: For a folder at path `["myspace", "folderA", "folderB"]`, the container path is `"myspace.folderA.folderB"`, not `"folderB"`. Use `String.join(".", container.getFullPathList())`.
- **Calling `hasAccessibleChildUnderPath()` before admin check**: Always short-circuit for admin first to avoid unnecessary full grant store scans.
- **Forgetting `SourcesResource`**: The `/api/v2/sources` endpoint in `SourcesResource` is separate from `CatalogServiceHelper.getTopLevelCatalogItems()`. Both must be filtered independently.
- **Filtering HOME spaces**: The home space is personal — each user sees only their own home, not others'. The existing logic is correct. Do not add container visibility filtering to the home space row.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Grant prefix scan | Custom indexed store | `GrantStore.listAll()` + `String.startsWith()` | KV store is already scan-based by design (v1.0 locked decision); IndexedStore not used |
| User role resolution | Manual membership join | `membershipStore.listByUser(userName)` | Already implemented; returns all roles including PUBLIC is added separately |
| Admin bypass | Inline ADMIN check | `rbacService.isAdminMember(userName)` | Already implemented in `hasAccessibleChildUnderPath()`; never duplicate this check |
| RBAC feature gate | New config flag | `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` | Existing flag; same pattern as all other RBAC checks in this codebase |
| Container path derivation | Custom path builder | `String.join(".", container.getFullPathList())` | Already used in `isVisibleToUser()` for leaf objects |

---

## Common Pitfalls

### Pitfall 1: PDS Objects Are Always Accessible — Containers Containing Only PDS Must Stay Visible

**What goes wrong:** The `hasAccessibleChildUnderPath()` method scans grants stored in the grant store. PDS with no explicit grants (the typical case — Phase 10 opt-in means most PDS have no grants) leave no trace in the grant store. A source containing only un-granted PDS would appear to have no accessible children, causing it to be hidden from non-admin users even though those users CAN access its PDS.

**Why it happens:** `hasAccessibleChildUnderPath()` looks for grants under a prefix. PDS without grants have no grant records.

**How to avoid:** The container visibility check for sources must be: visible if `hasAccessibleChildUnderPath(userName, sourceName)` OR if the namespace has at least one PDS under this container with no grants (i.e., universally accessible PDS exist). The simplest implementation: if `namespaceService.getDatasetCount(containerKey, ...).getCount() > 0` AND the source has un-granted PDS, the container is visible.

**Alternative simpler approach:** For sources that are pure "PDS sources" (no VDS), always show the source. For spaces (which contain only VDS), apply strict filtering. For mixed containers, check both VDS grants AND PDS presence. This matches Phase 10's opt-in model: "tables with no grants remain universally accessible."

**Concrete recommendation:** In `hasAccessibleChildUnderPath()`, or in a container-specific helper, also check `namespaceService.getDatasetCount(new NamespaceKey(containerNameParts), ...)` — if there are any PDS-type datasets with no grant records, the container is visible. Alternatively: use `getAllDatasets(containerKey)` and for each PDS, check if `rbacService.hasAnyPdsGrant(pdsPath)` returns false (meaning universally accessible). This is more expensive but correct.

**Simplest safe approach for v1.2:** Only apply container filtering to Spaces (which contain only VDS). For Sources, keep them visible to all non-admin users always — this matches the existing behavior and Phase 10 semantics. Container filtering for Sources can be revisited when PDS-only-source visibility becomes a concrete requirement.

**Warning signs:** A non-admin user with SELECT on a VDS in a source reports that the source disappeared after Phase 11.

### Pitfall 2: Folder Ancestor Chain Must ALL Be Visible

**What goes wrong:** CONT-04 requires the full ancestor path. If a user has SELECT on `myspace.folderA.folderB.myview`, then `myspace.folderA.folderB` must be visible, AND `myspace.folderA` must be visible (so the user can navigate to `folderB`).

**Why it happens:** `filterByVisibility()` is called on a flat list of children at each level. For `folderA` to be visible, its subtree must contain something accessible. This is exactly what `hasAccessibleChildUnderPath("myspace.folderA")` checks (it returns true if `"myspace.folderA.folderB.myview"` is a grant target under that prefix). So the algorithm naturally handles ancestor chains.

**How to avoid:** No extra logic is needed — the prefix scan naturally handles the full ancestor chain. Each folder level is checked independently via its own `hasAccessibleChildUnderPath()` call. Verify this with a test where a user has a VDS deeply nested (3 levels down) and confirm all ancestor folders are visible.

**Warning signs:** A user can see a deeply nested VDS in the catalog tree but cannot navigate to it because intermediate folders are hidden.

### Pitfall 3: Duplicate `filterByRbacVisibility()` Implementations

**What goes wrong:** There are three separate implementations of the folder-pass-through logic:
1. `CatalogServiceHelper.filterByVisibility()` (the most important one)
2. `SpaceResource.filterByRbacVisibility()`
3. `HomeResource.filterByRbacVisibility()`

All three have `return true; // folders always visible` in the folder case. If only one is fixed and the others are missed, folder visibility is inconsistent depending on which code path is used.

**Why it happens:** Each REST resource has its own copy of the RBAC filter logic (not extracted to a shared utility).

**How to avoid:** Fix all three. Consider whether to extract `filterByVisibility()` from `CatalogServiceHelper` into a shared utility to prevent future drift. For Phase 11, updating all three is sufficient.

**Warning signs:** Folders are hidden in the main catalog view but still visible in the space view (or vice versa).

### Pitfall 4: Missing ResourceTreeResource Filtering

**What goes wrong:** `ResourceTreeResource` provides the left-panel catalog tree for the SQL runner UI. It has its own `getSpaces()` and `getSources()` methods that enumerate without RBAC filtering. If `CatalogServiceHelper` is fixed but `ResourceTreeResource` is not, non-admin users will still see all spaces and sources in the SQL runner panel.

**Why it happens:** `ResourceTreeResource` does not currently receive `rbacService` or `dremioConfig` via injection.

**How to avoid:** Add `@Nullable RbacService rbacService` and `@Nullable DremioConfig dremioConfig` to `ResourceTreeResource`'s constructor. Add private helper `isContainerVisible(String containerName)`. Apply in `getSpaces()` and `getSources()`.

**Warning signs:** UI catalog tree (managed by `ResourceTreeResource`) shows sources/spaces that don't appear in the `/api/v3/catalog` listing.

### Pitfall 5: Grant Store Full Scan on Every Container in a Listing

**What goes wrong:** `getTopLevelCatalogItems()` may list dozens of sources and spaces. Each call to `hasAccessibleChildUnderPath()` triggers a full `grantStore.listAll()` scan. For 50 containers × 1 full scan = 50 full KV scans per request.

**Why it happens:** The grant store has no prefix index; `listAll()` is the only enumeration method.

**How to avoid:** Compute the user's accessible paths ONCE per request, then do in-memory prefix matching:

```java
// Compute once:
Set<String> userAccessiblePaths = getUserAccessibleObjectPaths(userName);

// Then per-container check is O(paths) not O(grants):
private boolean hasChildUnderPath(Set<String> accessiblePaths, String containerPath) {
    String prefix = containerPath + ".";
    return accessiblePaths.stream().anyMatch(p -> p.startsWith(prefix));
}
```

Where `getUserAccessibleObjectPaths()` does one `grantStore.listAll()` scan filtered to the user's roles.

This is the recommended approach for `getTopLevelCatalogItems()` and similar batch-listing calls.

---

## Code Examples

Verified patterns based on direct code inspection:

### Full Container Filter Helper (per-request, single grant scan)

```java
// In CatalogServiceHelper — new private helper:
/**
 * Returns the set of object paths accessible to the given user (from any of their role grants).
 * Used for container visibility filtering in top-level listings.
 * Returns null if RBAC is disabled or user is admin (caller should treat null as "show all").
 */
@Nullable
private Set<String> getUserAccessibleObjectPaths(String userName) {
    if (rbacService == null || dremioConfig == null
            || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        return null; // RBAC disabled — show everything
    }
    if (rbacService.isAdminMember(userName)) {
        return null; // admin — show everything
    }
    return rbacService.getAccessibleObjectPaths(userName);
}

// Then in getTopLevelCatalogItems():
String userName = securityContext.getUserPrincipal().getName();
Set<String> accessiblePaths = getUserAccessibleObjectPaths(userName);

for (SpaceConfig spaceConfig : namespaceService.getSpaces()) {
    if (accessiblePaths == null || hasChildUnder(accessiblePaths, spaceConfig.getName())) {
        topLevelItems.add(CatalogItem.fromSpaceConfig(spaceConfig));
    }
}
for (SourceConfig sourceConfig : sourceService.getSources()) {
    if (accessiblePaths == null || hasChildUnder(accessiblePaths, sourceConfig.getName())) {
        topLevelItems.add(CatalogItem.fromSourceConfig(sourceConfig));
    }
}
```

### New RbacService Methods

```java
// Option A: hasAccessibleChildUnderPath (single container check, simple)
public boolean hasAccessibleChildUnderPath(String userName, String containerPath) {
    if (isAdminMember(userName)) return true;
    List<String> roleIds = membershipStore.listByUser(userName).stream()
        .map(Membership::getRoleId).collect(Collectors.toList());
    roleIds.add(PUBLIC_ROLE_ID);
    String prefix = containerPath + ".";
    for (Grant grant : grantStore.listAll()) {
        if (roleIds.contains(grant.getRoleId()) && grant.getObjectPath().startsWith(prefix)) {
            return true;
        }
    }
    return false;
}

// Option B: getAccessibleObjectPaths (batch — preferred for top-level listings)
public Set<String> getAccessibleObjectPaths(String userName) {
    List<String> roleIds = membershipStore.listByUser(userName).stream()
        .map(Membership::getRoleId).collect(Collectors.toList());
    roleIds.add(PUBLIC_ROLE_ID);
    return grantStore.listAll().stream()
        .filter(grant -> roleIds.contains(grant.getRoleId()))
        .map(Grant::getObjectPath)
        .collect(Collectors.toSet());
}
```

### Folder Case in filterByVisibility (CatalogServiceHelper)

```java
// In filterByVisibility() — replace the "FOLDER is always visible" comment:

// Before (Phase 10):
// FOLDER, SPACE, SOURCE, HOME are always visible (containers).
return true;

// After (Phase 11):
if (container.getType() == NameSpaceContainer.Type.FOLDER) {
    String folderPath = String.join(".", container.getFullPathList());
    return rbacService.hasAccessibleChildUnderPath(userName, folderPath);
}
// SPACE, SOURCE, HOME at child level are treated as visible (they rarely appear here)
return true;
```

### Test Pattern (unit test for RbacService, JUnit 5 + Mockito)

```java
@Test
void hasAccessibleChildUnderPath_userHasGrantUnderContainer_returnsTrue() {
    // Grant: analyst|VDS|myspace.folderA.myview|SELECT
    Grant grant = Grant.newBuilder()
        .setRoleId("analyst")
        .setObjectType("VDS")
        .setObjectPath("myspace.folderA.myview")
        .setPrivilege("SELECT")
        .build();
    when(grantStore.listAll()).thenReturn(List.of(grant));
    when(membershipStore.listByUser("alice")).thenReturn(
        List.of(Membership.newBuilder().setRoleId("analyst").build()));
    when(membershipStore.get(RbacConfig.membershipKey("alice", "ADMIN"))).thenReturn(null);

    assertThat(rbacService.hasAccessibleChildUnderPath("alice", "myspace")).isTrue();
    assertThat(rbacService.hasAccessibleChildUnderPath("alice", "myspace.folderA")).isTrue();
    assertThat(rbacService.hasAccessibleChildUnderPath("alice", "myspace.folderB")).isFalse();
    assertThat(rbacService.hasAccessibleChildUnderPath("alice", "myspace2")).isFalse();
}

@Test
void hasAccessibleChildUnderPath_adminUser_alwaysReturnsTrue() {
    when(membershipStore.get(RbacConfig.membershipKey("admin", "ADMIN")))
        .thenReturn(Membership.newBuilder().build());
    assertThat(rbacService.hasAccessibleChildUnderPath("admin", "any.container")).isTrue();
    verifyNoInteractions(grantStore); // no scan needed
}
```

---

## Call Sites Inventory

Every place that lists containers for users — complete list from codebase inspection:

| File | Method | Container Type | Current Behavior | Required Change |
|------|--------|---------------|-----------------|-----------------|
| `CatalogServiceHelper.java:352` | `getTopLevelCatalogItems()` | Spaces + Sources | No RBAC filter | Filter spaces and sources |
| `CatalogServiceHelper.java:3127` | `filterByVisibility()` | Folders | Always visible | Check `hasAccessibleChildUnderPath()` |
| `SpaceResource.java:161` | `filterByRbacVisibility()` | Folders | Always visible | Check `hasAccessibleChildUnderPath()` |
| `HomeResource.java:598` | `filterByRbacVisibility()` | Folders | Always visible | Check `hasAccessibleChildUnderPath()` |
| `ResourceTreeResource.java:307` | `getSpaces()` | Spaces | No RBAC filter | Filter spaces |
| `ResourceTreeResource.java:336` | `getSources()` | Sources | No RBAC filter | Filter sources |
| `SourcesResource.java:73` | `getSources()` loop | Sources | No RBAC filter | Filter sources |

Note: `CatalogResource.java:74` calls `catalogServiceHelper.getTopLevelCatalogItems()` — already covered by fixing `CatalogServiceHelper`.

---

## State of the Art

| Old Approach | Current Approach (Phase 11 target) | Notes |
|--------------|-----------------------------------|-------|
| Containers always visible | Containers visible only when they contain accessible objects | The core change |
| No grant store scan for container check | Single full scan per listing + in-memory prefix match | Acceptable for v1.2 grant store sizes |
| Leaf-only RBAC (VDS, PDS, FUNCTION) | Derived container visibility from leaf grants | Container visibility is never directly grantable (out of scope) |

---

## Open Questions

1. **PDS-containing source visibility (CONT-01 precision)**
   - What we know: Phase 10's opt-in PDS model means PDS with no explicit grants are universally accessible. A source containing only un-granted PDS would have no grant records under its prefix.
   - What's unclear: Should a source with only un-granted PDS be visible to non-admin users? The requirements say "if they have access to at least one child object" — un-granted PDS are accessible to all users, so yes, the source should be visible.
   - Recommendation: For sources, consider treating "has any PDS with no grants" as implicitly accessible. The safest v1.2 approach: always show sources to non-admin users (keep existing behavior), and only apply container filtering to Spaces. If source-level filtering is needed in the future, implement PDS-presence check separately. Document this as a Phase 11 scoping decision.

2. **Whether `CatalogServiceHelper` needs refactoring to avoid duplicate filter logic**
   - What we know: Three classes (`CatalogServiceHelper`, `SpaceResource`, `HomeResource`) have their own `filterByRbacVisibility()` copies.
   - What's unclear: Whether to extract to a shared utility class or keep duplicates for simplicity.
   - Recommendation: For Phase 11, update all three independently. Add a `// TODO: extract to shared RBAC filter utility` comment for Phase 12 or later.

3. **ResourceTreeResource constructor injection: DI framework compatibility**
   - What we know: `ResourceTreeResource` uses HK2/Jersey `@Inject` with `@Context SecurityContext`. `@Nullable` injection for optional services works by precedent (see `SpaceResource` which already uses `@Nullable RbacService rbacService`).
   - What's unclear: Whether `ResourceTreeResource` is registered the same way as `SpaceResource` in `RestServerV2.java` (or another registry).
   - Recommendation: Follow the `SpaceResource` injection pattern exactly — `@Nullable RbacService rbacService` and `@Nullable DremioConfig dremioConfig` via constructor injection. Verify `ResourceTreeResource` is registered in the same Jersey application as `SpaceResource`.

---

## Sources

### Primary (HIGH confidence — direct code inspection)

- `CatalogServiceHelper.java` — lines 352-385 (`getTopLevelCatalogItems`), 1027-1128 (`getChildrenForPath`, `getNamespaceChildrenForPath`, `filterByVisibility`), 3127-3170 (`filterByVisibility`, `isFunctionVisibleToUser`) — read in full
- `SpaceResource.java` — lines 161-189 (`filterByRbacVisibility`) — read in full
- `HomeResource.java` — lines 598-626 (`filterByRbacVisibility`) — read in full
- `ResourceTreeResource.java` — lines 307-345 (`getSpaces`, `getSources`) — read in full
- `SourcesResource.java` — lines 69-94 (`getSources`) — read in full
- `RbacService.java` — lines 115-148 (`hasPrivilege`), full class — read in full
- `GrantStore.java` — `listAll()`, `listByObject()`, `listByRole()` — read in full
- `MembershipStore.java` — `listByUser()` — read in full
- `RbacConfig.java` — key format constants — read in full
- `CatalogResource.java` — line 74 (`listTopLevelCatalog` calls `getTopLevelCatalogItems`) — confirmed call chain
- `TestCatalogServiceHelper.java` — header/imports — confirmed test class structure
- `REQUIREMENTS.md` — CONT-01 through CONT-04 — confirmed requirement wording

### Secondary (MEDIUM confidence)

- `ROADMAP.md` — Phase 11 description and success criteria confirmed
- `.planning/codebase/ARCHITECTURE.md` — Catalog layer + Namespace service description verified
- `.planning/codebase/TESTING.md` — JUnit 5 + Mockito patterns verified

---

## Metadata

**Confidence breakdown:**
- Call site inventory: HIGH — all 7 call sites found and read directly
- Core algorithm (prefix scan): HIGH — `GrantStore.listAll()` confirmed, grant key format confirmed
- PDS source visibility edge case: MEDIUM — Phase 10 semantics understood but interaction with CONT-01 requires a planning decision
- ResourceTreeResource DI injection: MEDIUM — pattern confirmed from `SpaceResource` but `ResourceTreeResource` DI registration not verified
- Performance of full scan approach: HIGH — consistent with existing Phase 10 `hasAnyPdsGrant()` (also full scan)

**Research date:** 2026-02-21
**Valid until:** 2026-03-21 (stable code; changes only if RBAC implementation or catalog listing logic evolves)
