# Phase 28: DACSecurityContext Role Enforcement - Research

**Researched:** 2026-03-11
**Domain:** JAX-RS Security Context / `@RolesAllowed` enforcement in Dremio OSS
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| API-01 | v3 User API `createUser()` and `updateUser()` require admin role | Annotations exist (`@RolesAllowed("admin")`) but are never enforced because `isUserInRole()` always returns `true`; fixing `DACSecurityContext` makes these annotations actually block non-admin callers |
| API-03 | Collaboration API `setTagsForEntity()` and `setWikiForEntity()` require ALTER privilege | Phase 22 added programmatic `rbacService.hasPrivilege()` guards — these are **not** affected by `isUserInRole()`; already enforced independently |
| API-04 | Scripts API `getScripts()` restricts `createdBy` to current user for non-admin | Phase 22 added programmatic guard — not affected by `isUserInRole()`; already enforced independently |
| API-05 | `SpaceFolderResource` `createFolder()` and `deleteFolder()` verify privilege | Phase 22 added programmatic guard — not affected by `isUserInRole()`; already enforced independently |
| API-06 | `ReflectionResource` create/edit/delete verify ALTER on dataset | Phase 22 added programmatic guard — not affected by `isUserInRole()`; already enforced independently |
</phase_requirements>

---

## Summary

Phase 21 added `@RolesAllowed("admin")` to `UserResource.createUser()` and `updateUser()`. UAT retest then revealed these annotations are inert: `DACSecurityContext.isUserInRole(String role)` unconditionally returns `true`, so every user appears to be in every role. Jersey's `RolesAllowedDynamicFeature` calls `SecurityContext.isUserInRole()` to enforce `@RolesAllowed` — if that method always returns `true`, all `@RolesAllowed` checks are bypassed at the container level.

The fix is surgical: `DACSecurityContext` must inject `RbacService` (nullable, same `@Nullable` pattern used in 8+ other resources) and implement `isUserInRole("admin")` to delegate to `rbacService.isAdminMember(userName)`. When RBAC is disabled or `rbacService` is null, the method must fall back to `true` to preserve the pre-RBAC behavior for all non-admin role checks.

API-03 through API-06 are listed as requirements for this phase but were already fixed in Phase 22 with direct `rbacService.hasPrivilege()` / `rbacService.isAdminMember()` calls. They are independent of `isUserInRole()`. This phase does not need to re-implement those guards — they already work. The only active gap is the `@RolesAllowed("admin")` annotations on `UserResource.createUser()` and `updateUser()` (API-01), which are rendered effective by fixing `isUserInRole()`.

**Primary recommendation:** Fix `DACSecurityContext.isUserInRole()` to call `rbacService.isAdminMember(userName)` for the `"admin"` role and return `true` for all other roles. Inject `RbacService` and `DremioConfig` as nullable constructor parameters following the established `@Nullable` pattern used throughout the codebase.

---

## Standard Stack

### Core
| Library/Type | Location | Purpose | Why Standard |
|---|---|---|---|
| `DACSecurityContext` | `dac/backend/src/main/java/com/dremio/dac/server/DACSecurityContext.java` | JAX-RS `SecurityContext` implementation set per-request by `DACAuthFilter` | The only `SecurityContext` used by DAC endpoints; its `isUserInRole()` is the sole implementation Jersey calls |
| `RbacService` | `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` | Core RBAC business logic; `isAdminMember(userName)` checks KV store | The canonical admin-check in all existing RBAC guards; always registered in `DACDaemonModule` |
| `DremioConfig` | `com/dremio/config/DremioConfig.java` | Feature flag access via `getBoolean(DremioConfig.RBAC_ENABLED)` | Used in every existing RBAC guard as the null-safe feature flag |
| `RolesAllowedDynamicFeature` | Jersey server | Intercepts JAX-RS requests and calls `SecurityContext.isUserInRole()` for `@RolesAllowed` | Registered in `DACAuthFilterFeature` — already wired, just not working due to `isUserInRole()` bug |

---

## Architecture Patterns

### How `@RolesAllowed` Enforcement Works in Dremio OSS

The enforcement chain is:

1. Request arrives at a `@Secured`-annotated resource.
2. `DACAuthFilterFeature.configure()` registers `DACAuthFilter` and `RolesAllowedDynamicFeature`.
3. `DACAuthFilter.filter()` validates the bearer token, looks up the `User`, and calls `requestContext.setSecurityContext(new DACSecurityContext(userName, userConfig, requestContext))`.
4. `RolesAllowedDynamicFeature` intercepts the request and evaluates any `@RolesAllowed` annotation by calling `securityContext.isUserInRole(role)` for each allowed role. If **any** call returns `true`, access is granted.
5. Since `DACSecurityContext.isUserInRole()` currently returns `true` unconditionally, step 4 never blocks any request.

**Fix:** Step 5 must call `rbacService.isAdminMember(userName)` when `role.equals("admin")`.

### DACSecurityContext Construction

`DACSecurityContext` is constructed in `DACAuthFilter.filter()`:

```java
requestContext.setSecurityContext(
    new DACSecurityContext(userName, userConfig, requestContext));
```

`DACAuthFilter` is a Jersey `ContainerRequestFilter` with `@Inject` fields. Adding `@Inject @Nullable RbacService rbacService` and `@Inject @Nullable DremioConfig dremioConfig` to `DACAuthFilter` is the straightforward path. Both types are registered in the `SingletonRegistry` by `DACDaemonModule` and are injectable via `DremioBinder`.

Alternatively, `DACSecurityContext` could expose a static factory or accept RbacService as a constructor argument passed from `DACAuthFilter`. The constructor-argument approach is cleaner (keeps `DACSecurityContext` a pure POJO, tested in isolation) and matches the existing pattern where `DACSecurityContext.system()` creates a synthetic instance.

### Established `@Nullable` RbacService Injection Pattern

Every resource added since Phase 21 follows this pattern exactly:

```java
// In resource constructor:
@Inject
public SomeResource(
    ...,
    @Nullable RbacService rbacService,
    @Nullable DremioConfig dremioConfig) {
  ...
  this.rbacService = rbacService;
  this.dremioConfig = dremioConfig;
}

// In guard method:
if (rbacService == null || dremioConfig == null
    || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
  return; // RBAC disabled or not wired
}
String userName = securityContext.getUserPrincipal().getName();
if (rbacService.isAdminMember(userName)) {
  return; // admin bypass
}
```

`DACAuthFilter` uses the same `@Inject` field pattern:
```java
@Inject private javax.inject.Provider<UserService> userService;
@Inject private TokenManager tokenManager;
@Inject private ResourceInfo resourceInfo;
```

### `isUserInRole()` Semantics to Preserve

The existing callers of `isUserInRole()` in `dac/backend` are:

| Call Site | Behavior with Fix | Notes |
|---|---|---|
| `com.dremio.dac.api.UserResource.addUser()` line 119: `context.isUserInRole("admin")` | Returns `true` only for admin users | This guard creates the home namespace entry only for admin callers — correct behavior preserved |
| `com.dremio.dac.resource.UserResource.checkUser()` line 84: `!securityContext.isUserInRole("admin")` | Returns `false` for admin, allowing admin to bypass own-user check | Correct — admin can modify any user; non-admin restricted to own record |
| `DACViewCreatorFactory` anonymous `SecurityContext`: `isUserInRole()` returns `true` | Uses its own anonymous `SecurityContext`, not `DACSecurityContext` — **not affected** | Isolated; this context is never set via `requestContext.setSecurityContext()` |
| `LocalAdmin` anonymous `SecurityContext`: `isUserInRole()` returns `false` | Uses its own anonymous `SecurityContext` — **not affected** | LocalAdmin binds its own context |
| `AccessLogFilter` delegation: calls through to the wrapped `SecurityContext` | Delegates to whatever context is set — will delegate to fixed `DACSecurityContext` | Works correctly |

**Critical:** The `"user"` role check must still return `true`. `@RolesAllowed({"admin", "user"})` is used on all GET endpoints and allows any authenticated user. If `isUserInRole("user")` returned `false`, all those endpoints would also block non-admin users. The fix must be **only** for `"admin"` role.

### Anti-Patterns to Avoid

- **Do NOT** make `isUserInRole()` check `rbacService.isAdminMember()` for the `"user"` role. The `"user"` role is not stored in RBAC — it is a synthetic JAX-RS convention meaning "any authenticated user". Always return `true` for non-`"admin"` roles.
- **Do NOT** throw if `rbacService` is null or RBAC is disabled. Fall back to `true` to preserve pre-RBAC behavior (open-by-default when RBAC is not wired).
- **Do NOT** add the RBAC check inside the existing `DACSecurityContext` no-arg-based path (`system()` factory). The `system()` user is used for internal operations and should always pass all role checks.
- **Do NOT** change the `DACAuthFilter` to add the RBAC check inline — the security context should encapsulate this logic.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Admin membership check | Custom KV lookup | `rbacService.isAdminMember(userName)` | Already handles the composite key, null-safe; established pattern used in 10+ sites |
| Feature flag check | Duplicate config read | `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` | Consistent with all other guards; `DremioConfig.RBAC_ENABLED` is the canonical constant |
| Role-to-admin mapping | Separate role string table | Single `"admin".equals(role)` check | The only JAX-RS role string that maps to RBAC admin membership is `"admin"` |

---

## Common Pitfalls

### Pitfall 1: Breaking `@RolesAllowed({"admin", "user"})` GET Endpoints

**What goes wrong:** If `isUserInRole("user")` returns `false`, then every GET endpoint with `@RolesAllowed({"admin", "user"})` blocks non-admin users with HTTP 403.
**Why it happens:** `RolesAllowedDynamicFeature` grants access if ANY role in the list passes. If both `"admin"` and `"user"` return `false` for a non-admin, access is denied.
**How to avoid:** Return `true` for any role that is not `"admin"`. The `"user"` role means "authenticated" in Dremio's JAX-RS convention — it is not an RBAC role.
**Warning signs:** Test failures on `getUser()`, `getUserByName()`, any read endpoint annotated `@RolesAllowed({"admin", "user"})`.

### Pitfall 2: Null Pointer When RBAC Not Wired

**What goes wrong:** `rbacService.isAdminMember(userName)` throws NPE when `rbacService` is null (e.g., in test environments without full DI wiring).
**Why it happens:** `RbacService` is `@Nullable` injectable — test servers may not register it.
**How to avoid:** Check `rbacService != null` before calling. When null, fall back to `true` (pre-RBAC open-by-default).
**Warning signs:** `NullPointerException` in DACAuthFilter or DACSecurityContext during tests.

### Pitfall 3: Breaking `DACSecurityContext.system()`

**What goes wrong:** The `system()` factory creates a `DACSecurityContext` without a real user context. If `isUserInRole()` tries to call `getUserPrincipal().getName()` on a context created via `system()`, it may fail.
**Why it happens:** `system()` passes `SystemUser.SYSTEM_USERNAME` as the user name — this user is not in the RBAC KV store.
**How to avoid:** The `system()` context should return `true` for all roles (or the fix should only apply to contexts created via the normal constructor). Pass `rbacService` as null in the `system()` factory — the null guard will short-circuit to `true`.
**Warning signs:** Operations that use `DACSecurityContext.system()` (background tasks, DAC internal calls) fail with 403.

### Pitfall 4: `isUserInRole()` Called Before SecurityContext Is Set

**What goes wrong:** `RolesAllowedDynamicFeature` runs after authentication filters; if the security context is not set by `DACAuthFilter`, `getUserPrincipal()` could return null.
**Why it happens:** If the user token validation fails and `requestContext.abortWith(401)` is called, `isUserInRole()` is never reached — so this is safe. But the `system()` factory context has a non-null principal.
**How to avoid:** Guard `getUserPrincipal()` for null before calling `.getName()`. In `system()`, the principal is always non-null (it is `SystemUser.SYSTEM_USERNAME`).

---

## Code Examples

### Current (Broken) Implementation

```java
// Source: dac/backend/src/main/java/com/dremio/dac/server/DACSecurityContext.java
@Override
public boolean isUserInRole(String role) {
  return true;  // BUG: always grants every role to every user
}
```

### Target Implementation Pattern

```java
// Pattern derived from established @Nullable RbacService guard across 8+ resources
// Source: ScriptsResource, CollaborationResource, ReflectionResource, SpaceFolderResource, etc.

@Override
public boolean isUserInRole(String role) {
  // Only "admin" role is mapped to RBAC admin membership.
  // "user" and all other roles mean "authenticated" -- always grant.
  if (!"admin".equals(role)) {
    return true;
  }
  // RBAC not wired or disabled: fall back to open (pre-RBAC behavior)
  if (rbacService == null || dremioConfig == null
      || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    return true;
  }
  // Check actual RBAC admin membership
  return rbacService.isAdminMember(user.getUserPrincipal().getName());
}
```

### DACAuthFilter Injection Extension

```java
// Established @Inject field pattern (from existing DACAuthFilter fields)
@Inject @Nullable private RbacService rbacService;
@Inject @Nullable private DremioConfig dremioConfig;

// In filter():
requestContext.setSecurityContext(
    new DACSecurityContext(userName, userConfig, requestContext, rbacService, dremioConfig));
```

### Updated DACSecurityContext Constructor

```java
// Extend existing constructor with two nullable parameters
public DACSecurityContext(
    final UserName userName,
    final User user,
    ContainerRequestContext requestContext,
    @Nullable RbacService rbacService,
    @Nullable DremioConfig dremioConfig) {
  this.user = new UserUI(new UserResourcePath(userName), userName, user);
  this.requestContext = requestContext;
  this.rbacService = rbacService;
  this.dremioConfig = dremioConfig;
}
```

### `system()` Factory — Keep Null for rbacService

```java
public static SecurityContext system() {
  // rbacService = null → isUserInRole() falls back to true → system context passes all role checks
  return new DACSecurityContext(
      new UserName(SystemUser.SYSTEM_USERNAME), SystemUser.SYSTEM_USER, null, null, null);
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|---|---|---|---|
| `isUserInRole()` returns `true` unconditionally | `isUserInRole("admin")` delegates to `rbacService.isAdminMember()` | Phase 28 (this phase) | `@RolesAllowed("admin")` on `createUser()`/`updateUser()` becomes effective |
| `@RolesAllowed` used as documentation only | `@RolesAllowed` enforced by Jersey | Phase 28 (this phase) | API-01 gap closure |

**Existing programmatic guards (Phases 21–22) are NOT deprecated.** They remain the primary enforcement for `CatalogServiceHelper`, `CollaborationResource`, `ScriptsResource`, `SpaceFolderResource`, and `ReflectionResource`. `isUserInRole()` enforcement is complementary — it fires at the container level before the method body, while programmatic guards fire inside the method body.

---

## Open Questions

1. **Should `isUserInRole()` be RBAC-flag-aware or always check admin?**
   - What we know: All other programmatic guards check `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` as part of the three-way guard.
   - What's unclear: If RBAC is disabled and `isUserInRole("admin")` returns `true`, `@RolesAllowed("admin")` methods remain open to all. This is the desired backward-compatible behavior.
   - Recommendation: Include the RBAC flag check — return `true` when RBAC is disabled so the method remains open to all users (consistent with the three-way guard pattern everywhere else).

2. **Does `RolesAllowedDynamicFeature` propagate the correct `SecurityContext`?**
   - What we know: `DACAuthFilter` calls `requestContext.setSecurityContext(...)` at `@Priority(Priorities.AUTHENTICATION)`. `RolesAllowedDynamicFeature` runs at `Priorities.AUTHORIZATION` (higher number = later). Jersey guarantees the security context set by the authentication filter is visible to the authorization filter.
   - Confidence: HIGH — this is standard Jersey behavior, and the `DACAuthFilterFeature` registers both in the correct order.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4 + Jersey Test Framework + in-process Dremio (`BaseTestServer`) |
| Config file | none — test server uses `DACDaemonModule` with config overrides map |
| Quick run command | `cd /home/emanuele/IdeaProjects/dremio-oss && mvn test -pl dac/backend -am -Dtest=TestRbacIntegration -DskipTests=false -q 2>&1 | tail -20` |
| Full suite command | `cd /home/emanuele/IdeaProjects/dremio-oss && mvn test -pl dac/backend -am -DskipTests=false -q` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| API-01 | `isUserInRole("admin")` returns `false` for non-admin | unit | `mvn test -pl dac/backend -am -Dtest=TestDACSecurityContext -q` | ❌ Wave 0 |
| API-01 | Non-admin `POST /api/v3/user` returns 403 | integration | `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration#testNonAdminCannotCreateUser -q` | ❌ Wave 0 |
| API-01 | Non-admin `PUT /api/v3/user/{id}` returns 403 | integration | `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration#testNonAdminCannotUpdateUser -q` | ❌ Wave 0 |
| API-01 | Admin `POST /api/v3/user` still succeeds | integration | `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration#testAdminCanCreateUser -q` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn compile -pl dac/backend -am -DskipTests -q` (compile only — fast)
- **Per wave merge:** `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration,TestDACSecurityContext -DskipTests=false -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `dac/backend/src/test/java/com/dremio/dac/server/TestDACSecurityContext.java` — unit tests for `isUserInRole()` with null/non-null rbacService and RBAC flag combinations
- [ ] New test methods in `dac/backend/src/test/java/com/dremio/dac/server/TestRbacIntegration.java` — `testNonAdminCannotCreateUser()`, `testNonAdminCannotUpdateUser()`, `testAdminCanCreateUser()` integration tests using `getHttpClient()` with non-admin bearer token

---

## Sources

### Primary (HIGH confidence)
- Direct source code inspection: `DACSecurityContext.java` — confirmed `isUserInRole()` returns `true` unconditionally (line 46)
- Direct source code inspection: `DACAuthFilterFeature.java` — confirmed `RolesAllowedDynamicFeature` is registered (line 39)
- Direct source code inspection: `DACAuthFilter.java` — confirmed construction of `DACSecurityContext` per request (line 63)
- Direct source code inspection: `RbacService.java` — confirmed `isAdminMember(String userName)` API (line 153)
- Direct source code inspection: `DACDaemonModule.java` — confirmed `RbacService` is bound in the DI registry (lines 1035-1036)
- Direct source code inspection: `DremioBinder.java` — confirmed `DACSecurityContext.class` bound to `SecurityContext.class` as `RequestScoped` (line 91)
- Direct source code inspection: `RbacResource.java` (line 74 comment) — project code explicitly documents that `isUserInRole()` always returns `true` and programmatic checks are used instead
- Cross-reference: `ScriptsResource.java`, `CollaborationResource.java`, `ReflectionResource.java`, `SpaceFolderResource.java` — 8+ files using the `@Nullable RbacService` injection pattern

### Secondary (MEDIUM confidence)
- UAT_RESULTS.md — confirmed FAIL for Issue #12: non-admin bob_guest successfully called `POST /api/v3/user` despite `@RolesAllowed("admin")` annotation being present after Phase 21

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all code read directly from source
- Architecture: HIGH — Jersey RolesAllowedDynamicFeature behavior is well-understood and confirmed by code path tracing
- Pitfalls: HIGH — derived from code analysis of all `isUserInRole()` call sites and the established `@Nullable` guard pattern

**Research date:** 2026-03-11
**Valid until:** 2026-06-11 (stable domain — only invalidated if Dremio upgrades Jersey or replaces DACAuthFilter)
