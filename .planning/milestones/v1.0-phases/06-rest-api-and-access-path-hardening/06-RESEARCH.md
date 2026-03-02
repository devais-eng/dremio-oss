# Phase 6: REST API and Access Path Hardening - Research

**Researched:** 2026-02-18
**Domain:** Dremio JAX-RS REST endpoints (Jersey/HK2), RBAC admin enforcement, catalog visibility filtering
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| REST-01 | REST endpoint to list all roles | RbacService.getRoleInfo() already returns all roles (ADMIN, PUBLIC, user-created); new RbacResource GET /rbac/roles wraps it |
| REST-02 | REST endpoint to create a role | RbacService.createRole(roleId, roleName, createdBy); new POST /rbac/roles |
| REST-03 | REST endpoint to delete a role | RbacService.deleteRole(roleId) with cascade; new DELETE /rbac/roles/{name} |
| REST-04 | REST endpoint to list members of a role | MembershipStore.listByRole(roleId) via RbacService; new GET /rbac/roles/{name}/members |
| REST-05 | REST endpoint to add a user to a role | RbacService.addMembership(userName, roleId, grantedBy); new POST /rbac/roles/{name}/members |
| REST-06 | REST endpoint to remove a user from a role | RbacService.removeMembership(userName, roleId); new DELETE /rbac/roles/{name}/members/{userName} |
| REST-07 | REST endpoint to list grants on an object | GrantStore needs new listByObject(objectType, objectPath) method; new GET /rbac/grants?objectType=...&objectPath=... |
| REST-08 | REST endpoint to grant a privilege | RbacService.grantPrivilege(roleId, objectType, objectPath, privilege, grantedBy); new POST /rbac/grants |
| REST-09 | REST endpoint to revoke a privilege | RbacService.revokePrivilege(roleId, objectType, objectPath, privilege); new DELETE /rbac/grants |
| META-03 | REST catalog API filtered by caller's effective grants | CatalogServiceHelper.getNamespaceChildrenForPath() and getTopLevelCatalogItems() must filter VDS/FUNCTION entries; RbacService.hasPrivilege() used per-item |
</phase_requirements>

## Summary

Phase 6 creates a new JAX-RS resource class (`RbacResource`) under the `/api/v3/rbac` path that exposes RBAC management operations via REST, and modifies the existing catalog listing path (`CatalogServiceHelper`) to filter out views and UDFs the current user lacks grants for.

The REST endpoints (REST-01 through REST-09) are straightforward CRUD wrappers around the existing `RbacService` methods. The service already has `createRole`, `deleteRole`, `addMembership`, `removeMembership`, `grantPrivilege`, `revokePrivilege`, and listing methods. The new REST resource needs admin-only enforcement, which cannot rely on the existing `@RolesAllowed` annotation because `DACSecurityContext.isUserInRole()` always returns `true` in Dremio OSS. Instead, admin enforcement must be done programmatically by calling `RbacService.isAdminMember(userName)` at the start of each endpoint method.

The catalog visibility filtering (META-03) requires modifying `CatalogServiceHelper` to check grants when listing namespace children. The key filtering points are: (1) `getNamespaceChildrenForPath()` which lists namespace entities via `namespaceService.list()` and converts them to `CatalogItem`s, and (2) `getTopLevelCatalogItems()` which lists spaces/sources/home/functions at the top level. For each DATASET (type=VIRTUAL_DATASET) and FUNCTION item, the code must check `rbacService.hasPrivilege(userName, privilege, objectType, objectPath)` and exclude items where the user has no grant. Folders, spaces, sources, and homes should remain visible (they are containers, not access-controlled objects).

**Primary recommendation:** Create a single `RbacResource` class annotated with `@APIResource` + `@Secured` + `@Path("/rbac")` in `com.dremio.dac.api` package, add a `listByObject` method to `GrantStore`, inject `RbacService` + `SecurityContext` + `DremioConfig` into `CatalogServiceHelper` for visibility filtering, and programmatically enforce admin checks using `RbacService.isAdminMember()`.

## Standard Stack

### Core (all existing, no new libraries)
| Component | Location | Purpose | Why Standard |
|-----------|----------|---------|--------------|
| @APIResource | `com.dremio.dac.annotations.APIResource` | Marks class for auto-registration in /api/v3/* server | All v3 REST resources use this |
| @Secured | `com.dremio.dac.annotations.Secured` | Enables DACAuthFilter (token validation) | Required for authenticated endpoints |
| @Path | `javax.ws.rs.Path` | JAX-RS path mapping | Standard REST pattern |
| SecurityContext | `javax.ws.rs.core.SecurityContext` | Request-scoped user identity | Injected by DremioBinder (DACSecurityContext) |
| RbacService | `com.dremio.exec.rbac.RbacService` | RBAC business logic (already in SingletonRegistry) | Direct DI injection into REST resource |
| DremioConfig | `com.dremio.config.DremioConfig` | Feature flag check (RBAC_ENABLED) | Already in SingletonRegistry |
| ResponseList | `com.dremio.dac.api.ResponseList` | Standard v3 API list wrapper (data + errors) | Used by CatalogResource, ReflectionResource, etc. |
| CatalogServiceHelper | `com.dremio.dac.service.catalog.CatalogServiceHelper` | Catalog listing logic (to modify for META-03) | Central listing helper for CatalogResource |
| GrantStore | `com.dremio.exec.rbac.GrantStore` | Grant KV store (needs new listByObject method) | Existing store; add one query method |
| UserException | `com.dremio.common.exceptions.UserException` | Standard error building | For 403/404 responses |

### Key Dremio Patterns Used
| Pattern | Example | Purpose |
|---------|---------|---------|
| `@APIResource` + `@Secured` | CatalogResource.java | Auto-scanned into APIServer's /api/v3/* path |
| `@Inject` constructor injection | CatalogResource, UsersResource | Jersey HK2 injects from SingletonRegistry via DremioBinder |
| `SecurityContext.getUserPrincipal().getName()` | CatalogServiceHelper line 337 | Gets current user's name |
| `@RolesAllowed("admin")` | BackupResource, PutSourceResource | Annotation exists but isUserInRole() always returns true |
| Jackson JSON serialization | All v3 REST resources | DACJacksonJaxbJsonFeature auto-registers |
| `ResponseList<T>` | CatalogResource.listTopLevelCatalog | Standard list wrapper for v3 API responses |

## Architecture Patterns

### Pattern 1: New @APIResource REST Resource (v3 path)

**What:** Create a new resource class annotated with `@APIResource` to be auto-scanned into the `/api/v3/*` server.

**How the auto-scan works:**
1. `APIServer.init()` calls `result.getAnnotatedClasses(APIResource.class)` to find all classes annotated with `@APIResource`
2. Each found class is registered with Jersey via `register(resource)`
3. `DremioBinder` provides DI bindings from `SingletonRegistry` into HK2
4. The resource is accessible at `/api/v3/{@Path value}`

**Pattern to follow:**
```java
// Source: CatalogResource.java (existing pattern)
@APIResource
@Secured
@RolesAllowed({"user", "admin"})  // NOTE: effectively no-op, see CRITICAL finding below
@Path("/rbac")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class RbacResource {
  private final RbacService rbacService;
  private final SecurityContext securityContext;
  private final DremioConfig dremioConfig;

  @Inject
  public RbacResource(
      RbacService rbacService,
      SecurityContext securityContext,
      DremioConfig dremioConfig) {
    this.rbacService = rbacService;
    this.securityContext = securityContext;
    this.dremioConfig = dremioConfig;
  }
}
```

### CRITICAL Finding: @RolesAllowed Is a No-Op

**What:** `DACSecurityContext.isUserInRole(String role)` always returns `true` (line 45 of DACSecurityContext.java). This means `@RolesAllowed("admin")` provides NO actual access control in Dremio OSS.

**Impact:** The RBAC REST endpoints CANNOT rely on `@RolesAllowed("admin")` for admin-only enforcement. Admin checks MUST be done programmatically.

**How to enforce admin-only access:**
```java
private void requireAdmin() {
  String userName = securityContext.getUserPrincipal().getName();
  if (!rbacService.isAdminMember(userName)) {
    throw UserException.permissionError()
        .message("Only ADMIN role members can access RBAC management endpoints")
        .buildSilently();
  }
}
```

This method should be called at the start of every RBAC endpoint method. It uses `RbacService.isAdminMember()` which checks the membership KV store.

**Feature flag gating:** REST endpoints should also check `DremioConfig.RBAC_ENABLED`:
```java
private void requireRbacEnabled() {
  if (!dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    throw UserException.unsupportedError()
        .message("RBAC is not enabled. Set services.rbac.enabled=true in dremio.conf")
        .buildSilently();
  }
}
```

### Pattern 2: DI Injection for RbacService

**What:** RbacService is already registered in `SingletonRegistry` via `registry.bind(RbacService.class, rbacServiceInstance)` in `DACDaemonModule` (line 1036). The `DremioBinder` iterates all bindings and exposes them to HK2.

**Result:** `@Inject RbacService rbacService` works in any REST resource constructor without additional wiring.

**Similarly available:** `DremioConfig`, `SabotContext`, `SecurityContext` (request-scoped via CatalogFactory pattern).

### Pattern 3: Catalog Visibility Filtering (META-03)

**What:** Filter VDS and FUNCTION items from catalog listing results based on the current user's effective grants.

**Where to intercept:** Two key methods in `CatalogServiceHelper`:

1. **`getNamespaceChildrenForPath()`** (line 1087-1114): Lists namespace entities and converts them to `CatalogItem`s. This is the main listing path for spaces, folders, and their children. The filter should be applied after `namespaceService.list()` returns results and before they are added to the builder.

2. **`getTopLevelCatalogItems()`** (line 343-374): Lists top-level items (home, spaces, sources, functions). The functions listed here need filtering.

3. **`getChildrenForSourcePath()`** (line 1117+): Lists children within source paths. For versioned sources, this includes views and UDFs.

**What to filter:**
- `NameSpaceContainer.Type.DATASET` where `DatasetType == VIRTUAL_DATASET` -> check SELECT privilege
- `NameSpaceContainer.Type.FUNCTION` -> check EXECUTE privilege
- `NameSpaceContainer.Type.FOLDER`, `SPACE`, `SOURCE`, `HOME` -> ALWAYS visible (containers)
- `NameSpaceContainer.Type.DATASET` where `DatasetType != VIRTUAL_DATASET` (physical datasets) -> ALWAYS visible (not access-controlled in v1)

**Implementation approach:**
```java
// In CatalogServiceHelper, add a filtering method:
private boolean isVisibleToUser(NameSpaceContainer container) {
  if (rbacService == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    return true; // RBAC disabled, show everything
  }
  String userName = securityContext.getUserPrincipal().getName();
  if (rbacService.isAdminMember(userName)) {
    return true; // Admins see everything
  }
  if (container.getType() == Type.DATASET
      && container.getDataset().getType() == DatasetType.VIRTUAL_DATASET) {
    String objectPath = String.join(".", container.getFullPathList());
    return rbacService.hasPrivilege(userName, "SELECT", "VDS", objectPath);
  }
  if (container.getType() == Type.FUNCTION) {
    String objectPath = String.join(".", container.getFullPathList());
    return rbacService.hasPrivilege(userName, "EXECUTE", "FUNCTION", objectPath);
  }
  return true; // Folders, sources, spaces, homes, physical datasets are visible
}
```

**Injection needed:** `CatalogServiceHelper` constructor needs `RbacService` and `DremioConfig` injected. Since `CatalogServiceHelper` is bound via `bindToSelf` in `DremioBinder`, and `RbacService` + `DremioConfig` are in the registry, they can be added as constructor parameters with `@Inject`.

### Pattern 4: REST Request/Response DTOs

**What:** Simple POJO classes with Jackson annotations for REST API request/response bodies.

**Placement:** In `com.dremio.dac.api` package alongside other API models (CatalogItem, Dataset, Source, etc.).

**DTOs needed:**
- `RbacRole` - Response DTO for role data (roleId, roleName, type, createdBy, createdAt)
- `RbacMembership` - Response DTO for membership (userName, roleId, grantedBy, grantedAt)
- `RbacGrant` - Response DTO for grant (roleId, objectType, objectPath, privilege, grantedBy, grantedAt)
- `CreateRoleRequest` - Request body for POST /rbac/roles (roleName)
- `AddMemberRequest` - Request body for POST /rbac/roles/{name}/members (userName)
- `GrantRequest` - Request body for POST /rbac/grants (roleId, objectType, objectPath, privilege)
- `RevokeRequest` - Request body for DELETE /rbac/grants (roleId, objectType, objectPath, privilege)

### Pattern 5: Exception Mapping

**What:** Map RBAC exceptions to appropriate HTTP status codes.

| Exception | HTTP Status | When |
|-----------|-------------|------|
| `RbacEntityNotFoundException` | 404 Not Found | Role/grant/membership not found |
| `RbacEntityAlreadyExistsException` | 409 Conflict | Duplicate role/grant/membership |
| `IllegalArgumentException` | 400 Bad Request | Invalid input (built-in role names, null values) |
| `UserException.permissionError()` | 403 Forbidden | Non-admin user accessing RBAC endpoints |
| `UserException.unsupportedError()` | 400 Bad Request | RBAC feature flag is OFF |

Dremio's existing `DACExceptionMapperFeature` handles `UserException` -> HTTP response mapping. For `RbacEntityNotFoundException` and `RbacEntityAlreadyExistsException`, the REST resource should catch them and throw appropriate JAX-RS exceptions (`NotFoundException`, `WebApplicationException` with 409).

### Recommended Project Structure
```
dac/backend/src/main/java/com/dremio/dac/api/
  RbacResource.java           # New: REST resource class
  RbacRole.java               # New: Role response DTO
  RbacMembership.java         # New: Membership response DTO
  RbacGrant.java              # New: Grant response DTO
  CreateRoleRequest.java      # New: Create role request body
  AddMemberRequest.java       # New: Add member request body
  GrantRequest.java           # New: Grant privilege request body

sabot/kernel/src/main/java/com/dremio/exec/rbac/
  GrantStore.java             # Modified: add listByObject() method
  RbacService.java            # Modified: add listGrantsByObject() method (delegates to GrantStore)

dac/backend/src/main/java/com/dremio/dac/service/catalog/
  CatalogServiceHelper.java   # Modified: add visibility filtering in getNamespaceChildrenForPath() and getTopLevelCatalogItems()

dac/backend/src/test/java/com/dremio/dac/api/
  RbacResourceTest.java       # New: REST endpoint unit tests

sabot/kernel/src/test/java/com/dremio/exec/rbac/
  GrantStoreTest.java         # Modified: add tests for listByObject()
```

### Anti-Patterns to Avoid
- **Do NOT use `@RolesAllowed("admin")` for actual admin enforcement:** It does nothing because `isUserInRole()` always returns true.
- **Do NOT create a separate API server:** RBAC endpoints should be in the existing `/api/v3/*` server via `@APIResource` annotation.
- **Do NOT make CatalogServiceHelper a subclass/override:** Modify the existing class directly.
- **Do NOT filter physical datasets (PDS/promoted):** Only VDS and FUNCTION are access-controlled in v1.
- **Do NOT block folder/space visibility:** Containers remain visible even if all their contents are hidden.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON serialization | Custom serializers | Jackson (already configured via DACJacksonJaxbJsonFeature) | All v3 resources use it |
| Token auth/session validation | Custom auth filter | DACAuthFilter (triggered by @Secured) | Already handles token -> user resolution |
| Exception -> HTTP mapping | Custom error handlers | DACExceptionMapperFeature + JAX-RS exceptions | Standard Dremio pattern |
| Dependency injection | Manual singleton access | HK2 @Inject (via DremioBinder from SingletonRegistry) | All REST resources use this |
| Admin role check | Query UserService | RbacService.isAdminMember(userName) | Our RBAC admin, not Dremio's original concept |

## Common Pitfalls

### Pitfall 1: Assuming @RolesAllowed Enforces Admin Access
**What goes wrong:** Developer adds `@RolesAllowed("admin")` to the RBAC resource and assumes it blocks non-admin users.
**Why it happens:** `DACSecurityContext.isUserInRole()` returns `true` for ALL roles.
**How to avoid:** Always use programmatic `RbacService.isAdminMember()` checks.
**Warning signs:** Tests pass even when the test user is not an admin.

### Pitfall 2: Missing Feature Flag Check on REST Endpoints
**What goes wrong:** RBAC endpoints work even when `services.rbac.enabled=false`.
**Why it happens:** Forgetting to check DremioConfig before processing.
**How to avoid:** Call `requireRbacEnabled()` at the start of each endpoint method.
**Warning signs:** Endpoints return data when RBAC is disabled.

### Pitfall 3: GrantStore listByObject Is a Full Scan
**What goes wrong:** Performance degrades with many grants because `listByObject` must scan all entries.
**Why it happens:** The grant key format puts `role_id` first: `{role_id}|{object_type}|{object_path}|{privilege}`. Object-based queries cannot use prefix matching.
**How to avoid:** Accept scan-and-filter for v1 (consistent with existing `listByRole` pattern). Document as a known limitation. Performance is acceptable for OSS scale (hundreds, not millions, of grants).
**Warning signs:** Slow responses on the grants listing endpoint.

### Pitfall 4: Visibility Filtering Breaks Pagination in CatalogServiceHelper
**What goes wrong:** `getNamespaceChildrenForPath` uses `maxChildren + 1` to detect if there are more pages. If filtering removes items after pagination, the returned count may be less than `maxChildren` even when more items exist.
**Why it happens:** The filter is applied AFTER the namespace service returns paginated results.
**How to avoid:** For v1, apply filter post-fetch and accept that pages may be smaller than requested. This is a known limitation. The alternative (re-fetching until page is full) is complex and not needed for OSS scale.
**Warning signs:** Fewer items than maxChildren even when more exist.

### Pitfall 5: CatalogServiceHelper Constructor Signature Change
**What goes wrong:** Adding RbacService + DremioConfig parameters to CatalogServiceHelper constructor breaks existing code that constructs it.
**Why it happens:** CatalogServiceHelper is constructed by HK2 via `bindToSelf` in DremioBinder, which uses @Inject to resolve all constructor parameters.
**How to avoid:** Since CatalogServiceHelper uses `@Inject`, adding new parameters that are already in the DI registry works automatically. However, verify that no code manually constructs CatalogServiceHelper (search for `new CatalogServiceHelper`).
**Warning signs:** Build failures due to missing constructor arguments.

### Pitfall 6: Forgetting to Handle RBAC Admin Short-Circuit in Visibility Filter
**What goes wrong:** Admin users can't see items because the filter blocks them.
**Why it happens:** Not checking isAdminMember before applying the filter.
**How to avoid:** Always short-circuit: admin users see everything. Also short-circuit: RBAC disabled means no filtering.
**Warning signs:** Admin users see empty spaces.

### Pitfall 7: Object Path Format Mismatch Between REST and Grant Keys
**What goes wrong:** REST endpoint passes object path in format "space.folder.view" but grants store keys use a different format.
**Why it happens:** Inconsistency between how paths are represented in namespace (List<String>) vs grants (dot-delimited string).
**How to avoid:** Use consistent `String.join(".", pathList)` for object paths, matching the format used in CatalogImpl.validatePrivilege() which uses `key.getSchemaPath()`. Verify against existing grant key format in RbacConfig.grantKey().
**Warning signs:** hasPrivilege() always returns false even when grants exist.

## Code Examples

### Example 1: REST Resource Pattern (GET endpoint)
```java
// Source: Modeled on CatalogResource.java, UsersResource.java
@APIResource
@Secured
@RolesAllowed({"user", "admin"})
@Path("/rbac")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class RbacResource {
  private final RbacService rbacService;
  private final SecurityContext securityContext;
  private final DremioConfig dremioConfig;

  @Inject
  public RbacResource(
      RbacService rbacService,
      SecurityContext securityContext,
      DremioConfig dremioConfig) {
    this.rbacService = rbacService;
    this.securityContext = securityContext;
    this.dremioConfig = dremioConfig;
  }

  @GET
  @Path("/roles")
  public ResponseList<RbacRole> listRoles() {
    requireRbacEnabled();
    requireAdmin();
    List<RbacRole> roles = new ArrayList<>();
    for (SysTableRoleInfo info : rbacService.getRoleInfo()) {
      roles.add(RbacRole.fromSysTableRoleInfo(info));
    }
    return new ResponseList<>(roles);
  }

  private void requireRbacEnabled() {
    if (!dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
      throw UserException.unsupportedError()
          .message("RBAC is not enabled")
          .buildSilently();
    }
  }

  private void requireAdmin() {
    String userName = securityContext.getUserPrincipal().getName();
    if (!rbacService.isAdminMember(userName)) {
      throw UserException.permissionError()
          .message("Only ADMIN role members can access RBAC management endpoints")
          .buildSilently();
    }
  }
}
```

### Example 2: GrantStore.listByObject Pattern
```java
// Source: Modeled on existing GrantStore.listByRole() pattern
public List<Grant> listByObject(String objectType, String objectPath) {
  Preconditions.checkArgument(!Strings.isNullOrEmpty(objectType));
  Preconditions.checkArgument(!Strings.isNullOrEmpty(objectPath));
  return StreamSupport.stream(store.get().find().spliterator(), false)
      .filter(doc -> {
        Grant grant = doc.getValue();
        return objectType.equals(grant.getObjectType())
            && objectPath.equals(grant.getObjectPath());
      })
      .map(Document::getValue)
      .collect(Collectors.toList());
}
```

### Example 3: Visibility Filter in CatalogServiceHelper
```java
// Applied inside getNamespaceChildrenForPath() after namespaceService.list()
private List<NameSpaceContainer> filterByVisibility(List<NameSpaceContainer> children) {
  if (rbacService == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    return children;
  }
  String userName = securityContext.getUserPrincipal().getName();
  if (rbacService.isAdminMember(userName)) {
    return children;
  }
  return children.stream()
      .filter(c -> isVisibleToUser(c, userName))
      .collect(Collectors.toList());
}

private boolean isVisibleToUser(NameSpaceContainer container, String userName) {
  if (container.getType() == NameSpaceContainer.Type.DATASET) {
    DatasetConfig ds = container.getDataset();
    if (ds.getType() == DatasetType.VIRTUAL_DATASET) {
      String objectPath = String.join(".", container.getFullPathList());
      return rbacService.hasPrivilege(userName, "SELECT", "VDS", objectPath);
    }
  }
  if (container.getType() == NameSpaceContainer.Type.FUNCTION) {
    String objectPath = String.join(".", container.getFullPathList());
    return rbacService.hasPrivilege(userName, "EXECUTE", "FUNCTION", objectPath);
  }
  return true; // All other types (FOLDER, SPACE, SOURCE, HOME, PDS) are visible
}
```

### Example 4: DTO Classes
```java
// Source: Modeled on CatalogItem.java pattern (Jackson-serialized POJO)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RbacRole {
  private final String roleId;
  private final String roleName;
  private final String type;       // "SYSTEM" or "USER"
  private final String createdBy;

  @JsonCreator
  public RbacRole(
      @JsonProperty("roleId") String roleId,
      @JsonProperty("roleName") String roleName,
      @JsonProperty("type") String type,
      @JsonProperty("createdBy") String createdBy) {
    this.roleId = roleId;
    this.roleName = roleName;
    this.type = type;
    this.createdBy = createdBy;
  }
  // getters...
}
```

### Example 5: RbacService.listMembersByRole (Exposing MembershipStore Data)
```java
// RbacService currently does not expose listMembersByRole().
// Add a wrapper method:
public List<Membership> listMembersByRole(String roleId) {
  return membershipStore.listByRole(roleId);
}

// Similarly for listGrantsByObject:
public List<Grant> listGrantsByObject(String objectType, String objectPath) {
  return grantStore.listByObject(objectType, objectPath);
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| @RolesAllowed for admin access | Programmatic isAdminMember() check | This phase | Cannot use annotation-based security for RBAC admin |
| No catalog filtering | Filter VDS/FUNCTION by grants | This phase | Users see only items they have grants for |
| DDL-only RBAC management | DDL + REST RBAC management | This phase | UI can call REST endpoints |

**Existing but NOT to be changed:**
- DACSecurityContext.isUserInRole() -- always returns true (leave as-is)
- DACAuthFilter -- handles token validation (leave as-is)
- CatalogImpl.validatePrivilege() -- already enforces at query time (leave as-is, META-03 is LISTING-time filtering)

## Open Questions

1. **Pagination with visibility filtering**
   - What we know: `getNamespaceChildrenForPath` uses `maxChildren + 1` to detect next pages. Post-fetch filtering may return fewer than `maxChildren` items.
   - What's unclear: Whether the UI handles pages with fewer items than requested gracefully.
   - Recommendation: Accept smaller pages for v1. Document as known limitation. The UI already handles variable page sizes (e.g., from source listing paths). If this becomes an issue, a follow-up can implement a "refill" loop.

2. **Revoke endpoint: DELETE with body or query params?**
   - What we know: HTTP DELETE with a request body is technically valid but poorly supported by some clients. The grant key has 4 components (roleId, objectType, objectPath, privilege).
   - What's unclear: Best practice for Dremio's API style.
   - Recommendation: Use query parameters for DELETE /rbac/grants (roleId, objectType, objectPath, privilege as query params). This follows the pattern of `DELETE /catalog/{id}?tag=...` in CatalogResource.

3. **getTopLevelCatalogItems filtering**
   - What we know: This method lists spaces, sources, home, and top-level functions. Spaces and sources are containers (always visible). Functions need filtering.
   - What's unclear: Whether all top-level items should be visible or only items the user has grants for.
   - Recommendation: Filter only FUNCTION items at the top level. Keep spaces, sources, and home always visible.

## Sources

### Primary (HIGH confidence)
- `DACSecurityContext.java` - Verified isUserInRole() returns true always (line 45-46)
- `APIServer.java` - Verified @APIResource auto-scan pattern for /api/v3/* (line 29, 44)
- `DremioBinder.java` - Verified SingletonRegistry -> HK2 binding pattern (line 59-97)
- `DACDaemonModule.java` - Verified registry.bind(RbacService.class) at line 1036
- `CatalogResource.java` - Verified @APIResource + @Inject constructor pattern
- `CatalogServiceHelper.java` - Verified getNamespaceChildrenForPath(), getTopLevelCatalogItems() listing logic
- `GrantStore.java` - Verified listByRole() scan-and-filter pattern
- `RbacService.java` - Verified all CRUD methods exist, isAdminMember() is public
- `rbac.proto` - Verified Grant message fields (role_id, object_type, object_path, privilege)
- `CatalogImpl.java` - Verified validatePrivilege() pattern and RBAC_ENABLED check
- `DACAuthFilter.java` - Verified token-based auth flow
- `UsersResource.java` - Verified @RolesAllowed("admin") pattern for admin-only resources
- `ResourceTreeResource.java` - Verified namespace listing via NamespaceService.list()

### Secondary (MEDIUM confidence)
- `BaseTestServer.java`, `DACHttpClient.java` - Verified test patterns (getAPIv3(), getCatalogApi())
- `TestCatalogResource.java` - Verified integration test patterns

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All components verified in source code
- Architecture (REST resource pattern): HIGH - Follows exact existing CatalogResource pattern
- Architecture (visibility filtering): HIGH - Filtering points identified with line numbers
- Pitfalls: HIGH - isUserInRole() no-op verified directly in source, pagination impact analyzed
- GrantStore extension: HIGH - Follows existing listByRole() scan-and-filter pattern

**Research date:** 2026-02-18
**Valid until:** 2026-03-18 (stable codebase, no external dependencies)
