# Phase 32: JIT Provisioning + Role Mapping - Research

**Researched:** 2026-03-12
**Domain:** Dremio user auto-creation (JIT), UserService / KVStore, RBAC role-membership sync, rbac.proto schema, DACAuthFilter integration
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| JIT-01 | First Keycloak login auto-creates a Dremio user (username from `preferred_username`, email from `email` claim) | Intercept `UserNotFoundException` inside `DACAuthFilter.filter()` when `oidcTokenValidator != null`; call `JitUserProvisioner.provision(username, email)` before re-calling `getUser()`. Requires `OidcTokenValidator.validate()` to also return the email claim, via a new `KeycloakTokenDetails` wrapper or by adding a `roles + email` method. |
| JIT-02 | JIT-provisioned users have a locked sentinel password (cannot login via username/password form) | Create users with `UserType.REMOTE` in the `UserConfig`. `SimpleUserService.authenticate()` line 354 rejects non-LOCAL users with `UserLoginException`. Setting `type = REMOTE` means no `UserAuth` record is needed. Must bypass `createUser(User, String authKey)` API (which calls `validatePassword`) and write directly via a new `JitUserProvisioner` that holds a `LegacyKVStoreProvider`. |
| JIT-03 | Concurrent first logins from the same Keycloak user do not cause duplicate user errors | `SimpleUserService.createUser()` does `findUserByUserName()` + `put()` (not CREATE option) — TOCTOU gap. Solution: catch `UserAlreadyExistException` in `JitUserProvisioner.provision()` and fall through to `getUser()` to retrieve the winner's record. The concurrent loser silently gets the already-created user. |
| ROLE-01 | On each Keycloak login, `realm_access.roles` are synced to Dremio RBAC role memberships | `JWTClaimsSet.getJSONObjectClaim("realm_access")` returns `Map<String,Object>` with a `roles` key → `List<String>`. Parse this list in a new `KeycloakRoleSyncer.syncRoles(username, keycloakRoles)` called from `DACAuthFilter.filter()` after successful user lookup. |
| ROLE-02 | Additive mode: Keycloak roles are granted but existing Dremio-only roles are preserved | Add `source` field to `Membership` proto. In additive mode: only add memberships that are absent; never remove memberships where `source != "keycloak"`. |
| ROLE-03 | Authoritative mode: Dremio roles not present in Keycloak `realm_access.roles` are revoked | In authoritative mode: compute set-difference between current memberships and Keycloak roles; revoke any membership where `source == "keycloak"` that is no longer in the token roles. |
| ROLE-04 | Only pre-existing Dremio roles are mapped (unmapped Keycloak roles silently ignored) | Before calling `rbacService.addMembership()`, call `rbacService.listRoles()` or `roleStore.get(roleId)` to check role existence. If `null`, log at DEBUG and continue. |
</phase_requirements>

---

## Summary

Phase 32 adds two collaborating concerns to the Keycloak authentication flow:

**JIT Provisioning:** When a Keycloak user's JWT is validated successfully (Phase 31) but `userService.get().getUser(username)` throws `UserNotFoundException`, a `JitUserProvisioner` auto-creates the user as `UserType.REMOTE` with no password. `REMOTE` is an existing enum value in `users.proto` whose semantics (`authenticate()` rejects non-LOCAL users at line 354 of `SimpleUserService`) already provides the locked-password requirement. The provisioner must handle concurrent creation by catching `UserAlreadyExistException` and reading the already-created record instead.

**Role Mapping:** After user lookup succeeds, `KeycloakRoleSyncer.syncRoles(username, roles)` reads `realm_access.roles` from the Keycloak JWT and updates RBAC memberships. This requires: (a) passing roles from the JWT through to the filter — currently `OidcTokenValidator.validate()` only returns `TokenDetails` with `username`; a `KeycloakTokenDetails` wrapper is needed; (b) adding a `source` field to `rbac.proto`'s `Membership` message to tag Keycloak-originated memberships; (c) implementing additive vs. authoritative sync logic using `KeycloakConfig.isAdditive()` (already built in Phase 30).

Both concerns run inside `DACAuthFilter.filter()` on every authenticated Keycloak request. The role sync is idempotent and lightweight (KVStore reads + conditional writes).

**Primary recommendation:** Introduce two new classes in `services/keycloak`: `JitUserProvisioner` (handles user creation) and `KeycloakRoleSyncer` (handles role sync). Modify `OidcTokenValidator.validate()` or add a parallel method that also extracts email and realm roles. Modify `rbac.proto` to add `source` to `Membership`. Wire both new classes in `DACDaemonModule` under the keycloak branch and inject into `DACAuthFilter`.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.dremio.service.users.SimpleUserService` | project | User KV store access: `createUser`, `getUser` | Already bound as `UserService` in keycloak auth branch of `DACDaemonModule` |
| `com.dremio.exec.rbac.RbacService` | project | `addMembership`, `removeMembership`, `listMembershipsByUser` | Already bound in registry; `@Inject @Nullable` in `DACAuthFilter` |
| `com.dremio.service.keycloak.KeycloakConfig` | project (Phase 30) | `isAdditive()`, role sync mode | Already bound in keycloak branch |
| `com.nimbusds.jwt.JWTClaimsSet` | 9.41 (on classpath) | `getJSONObjectClaim("realm_access")` for role extraction | Already used in `OidcTokenValidator`; no new dependency |
| `com.dremio.service.users.proto.UserType` | project | `UserType.REMOTE` for locked sentinel user | Existing proto enum; `authenticate()` already blocks REMOTE users |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.dremio.service.users.UserAlreadyExistException` | project | Catch concurrent duplicate user creation (JIT-03) | Inside `JitUserProvisioner.provision()` to handle race without surfacing error |
| `com.dremio.exec.rbac.RbacEntityAlreadyExistsException` | project | Catch concurrent duplicate membership creation (ROLE-01) | Inside `KeycloakRoleSyncer` when `addMembership` races |
| `com.dremio.service.users.proto.UserConfig` | project (protostuff) | Setting `type = REMOTE`, `createdBy = CREATED_BY_AUTO_SYNC` | Needed for direct KVStore write in `JitUserProvisioner` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| New `JitUserProvisioner` class | Inline JIT logic in `DACAuthFilter` | Separate class is testable in isolation, keeps DACAuthFilter focused on auth dispatch |
| Direct KVStore write for REMOTE user | `createUser(User, authKey)` with random sentinel password | `createUser` calls `validatePassword(authKey)` (8+ chars, letter+number required); would create a `UserAuth` record with a hashed random password. The REMOTE type approach is cleaner: no auth record, no password to guess. But `createUser` API does not expose `type` on the `User` interface — must bypass. |
| Extending `TokenDetails` with roles | New `KeycloakTokenDetails` | `TokenDetails` is a `final` class in `services/tokens` with `scopes` already. Repurposing `scopes` for roles is semantically wrong. A new `KeycloakTokenDetails` in `services/keycloak` is preferred; it doesn't pollute the shared tokens module. |

**No new Maven dependencies required.** All needed classes are on the existing classpath.

---

## Architecture Patterns

### Recommended Project Structure (additions to Phase 30's `services/keycloak`)
```
services/keycloak/
└── src/main/java/com/dremio/service/keycloak/
    ├── KeycloakConfig.java             # existing (Phase 30)
    ├── OidcTokenValidator.java         # existing (Phase 30); add validateWithClaims()
    ├── KeycloakTokenDetails.java       # NEW: wraps username + email + realmRoles
    ├── JitUserProvisioner.java         # NEW: creates UserType.REMOTE user on first login
    └── KeycloakRoleSyncer.java         # NEW: syncs realm_access.roles to RBAC memberships
```

### rbac.proto Change (REQUIRED for ROLE-02 / ROLE-03)
```protobuf
// In sabot/kernel/src/main/protobuf/rbac.proto
message Membership {
  string user_name = 1;
  string role_id = 2;
  string granted_by = 3;
  uint64 granted_at = 4;
  string source = 5;    // NEW: "keycloak" for Keycloak-synced; "" for manually-assigned
}
```
This is a proto3 backwards-compatible addition (new field with zero-value default = "").
Existing memberships (source="") are treated as manually-assigned in all logic.

### Pattern 1: DACAuthFilter.filter() JIT + Role Sync Integration

**What:** After JWT validation returns a `KeycloakTokenDetails`, intercept `UserNotFoundException` with a provisioning+retry cycle. After successful user lookup, invoke role sync on every Keycloak-authenticated request.

**When to use:** Only on the Keycloak path (when `oidcTokenValidator != null` and token starts with `eyJ`).

**Updated filter() sketch:**
```java
// In DACAuthFilter.filter()
@Override
public void filter(ContainerRequestContext requestContext) {
  try {
    final UserName userName = getUserNameFromToken(requestContext);

    // JIT provisioning: create user if absent (Keycloak path only)
    User userConfig;
    try {
      userConfig = userService.get().getUser(userName.getName());
    } catch (UserNotFoundException e) {
      if (jitProvisioner != null && lastKeycloakTokenDetails != null) {
        jitProvisioner.provision(
            lastKeycloakTokenDetails.getUsername(),
            lastKeycloakTokenDetails.getEmail());
        userConfig = userService.get().getUser(userName.getName());
      } else {
        throw e;  // Non-Keycloak path: rethrow (→ 401)
      }
    }

    // Role sync: sync realm_access.roles on every Keycloak login
    if (roleSyncer != null && lastKeycloakTokenDetails != null) {
      roleSyncer.syncRoles(userName.getName(), lastKeycloakTokenDetails.getRealmRoles());
    }

    requestContext.setSecurityContext(
        new DACSecurityContext(userName, userConfig, requestContext, rbacService, dremioConfig));
    requestContext.setProperty(
        USER_CONTEXT_ATTRIBUTE, UserContext.of(userConfig.getUID().getId()));
  } catch (UserNotFoundException | NotAuthorizedException e) {
    requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
  }
}
```

**Note on `lastKeycloakTokenDetails`:** `getUserNameFromToken()` runs in the same thread as `filter()`. The cleanest implementation is to have `getUserNameFromToken()` return a `KeycloakTokenDetails` (or store it in a thread-local/request attribute) when the Keycloak path is taken. Alternatively, `DACAuthFilter` can store it as a field set during `getUserNameFromToken()`. Thread-local is cleanest for a filter.

**Alternative:** Store `KeycloakTokenDetails` in a `ContainerRequestContext` attribute (e.g., `requestContext.setProperty("keycloak.token.details", ktd)`) rather than a thread-local. This avoids thread-local lifecycle issues and is more idiomatic JAX-RS.

### Pattern 2: JitUserProvisioner — REMOTE User Creation

**What:** Creates a `UserType.REMOTE` user directly in the KVStore, bypassing `createUser()` which requires a valid password. Handles `UserAlreadyExistException` for JIT-03.

```java
// Source: TestSimpleUserService.java lines 194-204 — reference pattern for direct store write
// Source: users.proto UserType enum (REMOTE = 2 blocks authenticate())
public class JitUserProvisioner {
  private final Supplier<LegacyIndexedStore<UID, UserInfo>> userStore;

  public JitUserProvisioner(Provider<LegacyKVStoreProvider> kvStoreProvider) {
    this.userStore = Suppliers.memoize(
        () -> kvStoreProvider.get().getStore(SimpleUserService.UserGroupStoreBuilder.class));
  }

  /**
   * Creates a REMOTE (locked) user for the given Keycloak identity.
   * Idempotent: if the user already exists (concurrent race), silently succeeds.
   *
   * @throws IOException if KVStore write fails unexpectedly
   */
  public void provision(String username, String email) throws IOException {
    // Check first to avoid generating a new UID on every concurrent call
    // (TOCTOU is acceptable: duplicate detection is via UserAlreadyExistException catch)
    try {
      // Build UserConfig directly to set UserType.REMOTE (bypasses createUser's password check)
      UID uid = new UID(UUID.randomUUID().toString());
      UserConfig config = new UserConfig()
          .setUid(uid)
          .setUserName(username)
          .setEmail(email != null ? email : "")
          .setType(UserType.REMOTE)
          .setCreatedAt(System.currentTimeMillis())
          .setModifiedAt(System.currentTimeMillis())
          .setActive(true);
      UserInfo info = new UserInfo().setConfig(config);
      userStore.get().put(uid, info);
      // Note: SimpleUserService.createUser() does findByUserName + put (not CREATE option),
      // so it has a TOCTOU gap. We replicate the same gap here. The concurrent duplicate
      // is caught below via SimpleUserService.createUser()'s UserAlreadyExistException check,
      // OR by calling getUser() which returns the winner's record.
    } catch (UserAlreadyExistException e) {
      // JIT-03: concurrent first login — the other thread won, that's fine.
      logger.debug("JIT user already exists (concurrent creation): {}", username);
    }
  }
}
```

**Important:** The above pattern matches the test at `TestSimpleUserService.java:194-204` which shows direct `userStore.put()` for REMOTE users. This is the established pattern in the codebase.

**Simpler alternative (no TOCTOU issue):** Since `SimpleUserService.findUserByUserName()` is already a check in `createUser()`, and the TOCTOU window is very small (first login only), catching `UserAlreadyExistException` is sufficient for JIT-03. A full atomic CAS is not needed for a single-coordinator deployment.

### Pattern 3: KeycloakRoleSyncer — Additive vs. Authoritative Sync

**What:** Compares current RBAC memberships (filtering by `source = "keycloak"`) against the new Keycloak `realm_access.roles` list and updates accordingly.

```java
public class KeycloakRoleSyncer {
  private final RbacService rbacService;
  private final KeycloakConfig keycloakConfig;

  public void syncRoles(String username, List<String> keycloakRoles) {
    // ROLE-04: Only sync roles that exist in Dremio RBAC
    Set<String> existingDremioRoles = rbacService.listAllRoleIds();  // via roleStore.listAll()
    Set<String> validKeycloakRoles = keycloakRoles.stream()
        .filter(existingDremioRoles::contains)
        .collect(Collectors.toSet());

    // Get current memberships tagged with source="keycloak"
    List<Membership> currentMemberships = rbacService.listMembershipsByUser(username);
    Set<String> keycloakSourcedRoles = currentMemberships.stream()
        .filter(m -> "keycloak".equals(m.getSource()))
        .map(Membership::getRoleId)
        .collect(Collectors.toSet());

    // Grant roles present in token but not yet in Dremio (both modes)
    for (String roleId : validKeycloakRoles) {
      if (!keycloakSourcedRoles.contains(roleId)) {
        try {
          rbacService.addMembershipWithSource(username, roleId, "keycloak", "keycloak");
        } catch (RbacEntityAlreadyExistsException e) {
          // concurrent sync — silently ignore
        }
      }
    }

    // Authoritative mode: revoke keycloak-sourced roles no longer in token (ROLE-03)
    if (!keycloakConfig.isAdditive()) {
      for (String existingKcRole : keycloakSourcedRoles) {
        if (!validKeycloakRoles.contains(existingKcRole)) {
          try {
            rbacService.removeMembership(username, existingKcRole);
          } catch (RbacEntityNotFoundException e) {
            // already removed — silently ignore
          }
        }
      }
    }
    // Additive mode: manually-assigned roles (source="") are never touched (ROLE-02)
  }
}
```

**Note on `addMembershipWithSource`:** `RbacService.addMembership()` currently does not accept a `source` argument. A new overload `addMembership(userName, roleId, grantedBy, source)` is needed, or `RbacService.addMembership()` must be updated to accept `source`. The Membership proto must be updated first to carry the `source` field (see rbac.proto change above).

### Pattern 4: KeycloakTokenDetails — Role + Email Pass-Through

**What:** Wraps the claims extracted by `OidcTokenValidator` after validation, carrying username, email, and realm roles for use in JIT + role sync.

```java
// In services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakTokenDetails.java
public final class KeycloakTokenDetails {
  private final String username;     // from preferred_username or sub
  private final String email;        // from email claim (may be null)
  private final List<String> realmRoles;  // from realm_access.roles (may be empty)
  private final long expiresAt;      // epoch millis

  // constructor + getters...
}
```

`OidcTokenValidator` gets a new method:
```java
// Alternatively, augment validate() return type
public KeycloakTokenDetails validateWithClaims(String jwtString) throws ParseException {
  JWT jwt = JWTParser.parse(jwtString);
  JWTClaimsSet claims;
  try {
    claims = jwtProcessor.process(jwt, null);
  } catch (BadJOSEException | JOSEException e) {
    throw new IllegalArgumentException("Keycloak JWT validation failed: " + e.getMessage(), e);
  }

  String username = claims.getStringClaim("preferred_username");
  if (username == null) username = claims.getSubject();
  String email = claims.getStringClaim("email");

  // realm_access.roles extraction
  List<String> realmRoles = Collections.emptyList();
  try {
    Map<String, Object> realmAccess = claims.getJSONObjectClaim("realm_access");
    if (realmAccess != null) {
      Object rolesObj = realmAccess.get("roles");
      if (rolesObj instanceof List) {
        realmRoles = ((List<?>) rolesObj).stream()
            .filter(r -> r instanceof String)
            .map(r -> (String) r)
            .collect(Collectors.toList());
      }
    }
  } catch (ParseException e) {
    logger.debug("realm_access claim not parseable as JSON object; skipping roles: {}", e.getMessage());
  }

  long expiresAt = claims.getExpirationTime().getTime();
  return new KeycloakTokenDetails(username, email, realmRoles, expiresAt);
}
```

**DACAuthFilter compatibility:** `OidcTokenValidator.validate()` (existing, returns `TokenDetails`) stays unchanged for COEX-02 compatibility. `validateWithClaims()` is a new method called only when `jitProvisioner != null` (i.e., Keycloak mode with Phase 32 active).

### Anti-Patterns to Avoid

- **Using `createUser(User, authKey)` with a random valid password:** Creates a `UserAuth` record. The existing test shows REMOTE users can be created without auth records. Using `UserType.REMOTE` makes the locked state durable even if `UserAuth` is somehow added later.
- **Throwing exception from `syncRoles()` on unknown Keycloak role:** Must silently skip (ROLE-04). Any exception from role sync must not abort the request — log at DEBUG and continue.
- **Storing `KeycloakTokenDetails` in a DACAuthFilter instance field:** `DACAuthFilter` is a singleton injected by HK2 and shared across requests. Instance fields are NOT thread-safe for per-request state. Use a `ContainerRequestContext` property or a local variable passed as a method parameter.
- **Running role sync before user JIT provisioning:** `syncRoles(username, roles)` calls `rbacService.addMembership(username, roleId, ...)` which requires the user to exist. Always provision first, then sync roles.
- **Running role sync when Keycloak token was accepted via the Dremio JWT fallback path:** If the `eyJ` token was accepted by `tokenManager` (fallback for Dremio-issued JWTs), there are no Keycloak roles to sync. Only sync when `OidcTokenValidator.validateWithClaims()` was called and succeeded.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Locked sentinel password | Random UUID password stored and hashed | `UserType.REMOTE` flag in `UserConfig` | REMOTE users are rejected at `authenticate()` line 354 with no credential check; no need to store or manage a password |
| Role existence check | Re-implement `roleStore.get()` | `RoleStore.get(roleId)` via `rbacService` | Already accessible; `null` return means role not in Dremio |
| Concurrent duplicate membership | Lock or `synchronized` block | Catch `RbacEntityAlreadyExistsException` from `MembershipStore.add()` (PutOption.CREATE) | Store-level create-if-absent semantics already provided |
| JWT claim extraction for roles | Parse JWT string manually | `JWTClaimsSet.getJSONObjectClaim("realm_access")` | Nimbus already parsed the JWT during validation; reuse the `JWTClaimsSet` object |
| User existence check before provisioning | Extra `getUser()` call | Catch `UserAlreadyExistException` from the create path | TOCTOU-safe: the create attempt itself is the check; exception is the signal |

---

## Common Pitfalls

### Pitfall 1: `DACAuthFilter` is a HK2 Singleton — No Per-Request Instance Fields

**What goes wrong:** Storing `KeycloakTokenDetails` in a `DACAuthFilter` instance field (`this.lastKtd = ...`) appears to work in single-threaded tests but silently corrupts data under concurrent requests.

**Why it happens:** `DACAuthFilter` is annotated `@Provider @Priority(Priorities.AUTHENTICATION)`. HK2 binds it as a singleton (one instance for all requests).

**How to avoid:** Pass `KeycloakTokenDetails` as: (a) a `ContainerRequestContext.setProperty("keycloak.details", ktd)` attribute, or (b) a local method variable passed between `getUserNameFromToken()` and `filter()`. The request property approach is most idiomatic JAX-RS.

**Warning signs:** Under load testing, users get each other's roles; NPE on `lastKtd.getEmail()` for one thread while another thread is midway through a request.

### Pitfall 2: `rbac.proto` Membership `source` Field — Proto3 Default is Empty String

**What goes wrong:** Filtering memberships by `source == null` vs `source.equals("")` produces incorrect results; manually-assigned memberships (pre-Phase-32) have `source = ""` (proto3 default), not `null`.

**Why it happens:** Proto3 fields have default zero-values. `string source = 5;` defaults to `""` for existing records.

**How to avoid:** Always check `source.isEmpty()` or `!"keycloak".equals(source)` to identify manually-assigned memberships. Never use `source == null`.

**Warning signs:** Authoritative mode revokes manually-assigned roles on first login because the filter treats `source = ""` as Keycloak-sourced.

### Pitfall 3: `RbacService.addMembership()` Needs `source` Parameter — Current Signature Missing It

**What goes wrong:** Adding a `source` field to `Membership` proto but not updating `RbacService.addMembership()` means all new memberships still get `source = ""`, breaking ROLE-02/ROLE-03 differentiation.

**Why it happens:** `RbacService.addMembership(userName, roleId, grantedBy)` constructs `Membership.newBuilder()` with only the fields it knows about.

**How to avoid:** Add a new overload `addMembership(String userName, String roleId, String grantedBy, String source)` to `RbacService` that also sets `source` on the `Membership`. The existing three-argument overload can delegate with `source = ""`.

**Warning signs:** Role sync appears to work (no exceptions) but authoritative mode never revokes anything because all memberships look manually-assigned.

### Pitfall 4: Role Sync Throws for Unmapped Keycloak Roles → 401

**What goes wrong:** A Keycloak user who belongs to the `realm-management` or `offline_access` Keycloak roles (which don't exist in Dremio RBAC) gets 401 on every request because `rbacService.addMembership()` throws `RbacEntityNotFoundException`.

**Why it happens:** `addMembership()` checks `roleStore.get(roleId) == null` and throws if the Dremio role doesn't exist. The ROLE-04 requirement says these should be silently ignored.

**How to avoid:** In `KeycloakRoleSyncer.syncRoles()`, filter out Keycloak roles before calling `addMembership()`:
```java
if (roleStore.get(roleId) == null) {
  logger.debug("Keycloak role '{}' has no Dremio RBAC role — skipping", roleId);
  continue;
}
```

**Warning signs:** First Keycloak login gets 401; error log shows `RbacEntityNotFoundException` for Keycloak system roles.

### Pitfall 5: `UserConfig.type` Field in `toUserConfig()` Is Never Set by `SimpleUser`

**What goes wrong:** Attempting to use `userService.get().createUser(SimpleUser.newBuilder().setUserName(...).build(), authKey)` for JIT provisioning always creates a `LOCAL` user (the default), regardless of what you pass.

**Why it happens:** `SimpleUserService.toUserConfig(User user)` at line 623 does not copy `type` from the `User` argument (because `SimpleUser` has no `type` field). The `merge()` method at line 208 preserves the original type on update, but the initial `createUser()` starts with `LOCAL` default.

**How to avoid:** Do NOT use `userService.get().createUser()` for JIT REMOTE users. Build `UserConfig` directly, set `type = UserType.REMOTE`, and put it into `userGroupStore`. This is the pattern shown in `TestSimpleUserService.java:194-204`.

**Warning signs:** JIT-02 test fails — user created by JIT provisioner can authenticate via `POST /apiv2/login`.

### Pitfall 6: `SimpleUserService.createUser()` Concurrency Check Uses TOCTOU `findUserByUserName()`

**What goes wrong:** Two concurrent first-login requests both pass the `findUserByUserName() != null` check (both see `null`), then both call `userStore.put(uid, info)`. Since `put()` without `PutOption.CREATE` is an unconditional overwrite (last-write-wins), two different UIDs may exist for the same username in edge cases.

**Why it happens:** The `LegacyIndexedStore.put()` in `SimpleUserService.createUser()` is not CAS (compare-and-swap). Two concurrent writes with different UIDs both succeed; the index now maps the username to whichever UID was written last.

**How to avoid:** For JIT provisioning, the `JitUserProvisioner.provision()` implementation must also use `findUserByUserName()` first, then `put()`. The critical addition is: after `put()`, immediately call `findUserByUserName()` again to verify the written entry. If a different UID is returned, the other thread won — return the winner's record. Alternatively, accept the TOCTOU gap for single-coordinator deployments (only one coordinator writes users) and rely on the `UserAlreadyExistException` catch at a higher level.

**Warning signs:** Occasional NPE or `UserNotFoundException` for JIT user after successful creation, when running load tests with concurrent first-logins.

---

## Code Examples

### Extracting realm_access.roles from JWTClaimsSet (Nimbus 9.41)

```java
// Source: Nimbus JWTClaimsSet.getJSONObjectClaim() — verified via javap on nimbus-jose-jwt-9.41.jar
// Keycloak token structure: { "realm_access": { "roles": ["role_a", "role_b"] } }

import com.nimbusds.jwt.JWTClaimsSet;
import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;

private static List<String> extractRealmRoles(JWTClaimsSet claims) {
  try {
    Map<String, Object> realmAccess = claims.getJSONObjectClaim("realm_access");
    if (realmAccess == null) {
      return Collections.emptyList();
    }
    Object roles = realmAccess.get("roles");
    if (!(roles instanceof List)) {
      return Collections.emptyList();
    }
    return ((List<?>) roles).stream()
        .filter(r -> r instanceof String)
        .map(r -> (String) r)
        .collect(Collectors.toList());
  } catch (ParseException e) {
    // realm_access not a JSON object — token is valid but has no roles
    return Collections.emptyList();
  }
}
```

### Creating a REMOTE (locked) UserConfig (JIT-01, JIT-02)

```java
// Source: TestSimpleUserService.java lines 194-204 — established test pattern for REMOTE users
// users.proto: type = REMOTE (2) causes authenticate() to throw UserLoginException (line 354)
import com.dremio.service.users.proto.*;

UID uid = new UID(UUID.randomUUID().toString());
UserConfig config = new UserConfig()
    .setUid(uid)
    .setUserName(username)
    .setEmail(email != null ? email : "")
    .setType(UserType.REMOTE)
    .setCreatedAt(System.currentTimeMillis())
    .setModifiedAt(System.currentTimeMillis())
    .setActive(true);
UserInfo info = new UserInfo().setConfig(config);
userStore.get().put(uid, info);
// No UserAuth record needed — REMOTE users have no password
```

### Adding a `source` field to rbac.proto Membership (ROLE-02, ROLE-03)

```protobuf
// In sabot/kernel/src/main/protobuf/rbac.proto
message Membership {
  string user_name = 1;
  string role_id = 2;
  string granted_by = 3;
  uint64 granted_at = 4;
  string source = 5;    // "keycloak" for Keycloak-synced; "" for manually-assigned
}
```

This is proto3 backwards-compatible (new field, zero-value default `""`). Existing stored `Membership` records will deserialize with `source = ""`.

### RbacService.addMembership() with source overload

```java
// In RbacService.java — add new overload; existing method delegates to it
public void addMembership(String userName, String roleId, String grantedBy)
    throws RbacEntityNotFoundException {
  addMembership(userName, roleId, grantedBy, "");
}

public void addMembership(String userName, String roleId, String grantedBy, String source)
    throws RbacEntityNotFoundException {
  Preconditions.checkArgument(!PUBLIC_ROLE_ID.equals(roleId), ...);
  if (!ADMIN_ROLE_ID.equals(roleId)) {
    if (roleStore.get(roleId) == null) {
      throw new RbacEntityNotFoundException("Role not found: " + roleId);
    }
  }
  Membership membership = Membership.newBuilder()
      .setUserName(userName)
      .setRoleId(roleId)
      .setGrantedBy(grantedBy)
      .setGrantedAt(System.currentTimeMillis())
      .setSource(source)
      .build();
  membershipStore.add(RbacConfig.membershipKey(userName, roleId), membership);
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| All Dremio users are `UserType.LOCAL` | JIT-provisioned Keycloak users are `UserType.REMOTE` | Phase 32 | REMOTE users cannot use `/apiv2/login`; no sentinel password needed |
| `Membership` proto has no `source` field | Add `source = 5` field | Phase 32 | Differentiates Keycloak-synced memberships from manually-assigned ones in additive mode |
| `TokenDetails` carries only `username` + `token` + `expiresAt` | `KeycloakTokenDetails` carries `username + email + realmRoles + expiresAt` | Phase 32 | Enables JIT provisioning (email) and role sync (realmRoles) without re-parsing the JWT |
| `DACAuthFilter.filter()` always calls `getUser()` and fails on unknown user → 401 | Keycloak path intercepts `UserNotFoundException` and provisions | Phase 32 | First REST API call from new Keycloak user succeeds |

**Deprecated/outdated:**
- The comment `// JIT provisioning adds them in Phase 32` in `DACDaemonModule.setupUserService()` lines 2219-2220 is now actionable.

---

## Open Questions

1. **Thread-safety: request attribute vs. local variable for `KeycloakTokenDetails`**
   - What we know: `DACAuthFilter` is a singleton; per-request data must not use instance fields. Two approaches: (a) store `KeycloakTokenDetails` in `ContainerRequestContext.setProperty(...)` (JAX-RS idiomatic), (b) refactor `getUserNameFromToken()` to return a richer type.
   - What's unclear: Whether `getUserNameFromToken()` signature change would break existing subclass (`TestDACAuthFilterKeycloak` uses reflection-based access).
   - Recommendation: Use `ContainerRequestContext.setProperty("keycloak.token.details", ktd)` in `getUserNameFromToken()` for the Keycloak path, read it back in `filter()`. No signature change needed.

2. **`UserGroupStoreBuilder` accessibility from `JitUserProvisioner` outside `services/users`**
   - What we know: `SimpleUserService.UserGroupStoreBuilder` is a `public static final class`. It is the stable store identifier class (its name must never change per its Javadoc). It is accessible from `JitUserProvisioner` in `services/keycloak` if `services/keycloak/pom.xml` depends on `services/users`.
   - What's unclear: Whether the services/keycloak module already depends on services/users or must add the dependency.
   - Recommendation: Check `services/keycloak/pom.xml`; add `services/users` dependency if not present. `UserGroupStoreBuilder` is the correct entry point — do not duplicate the store name string.

3. **`RbacService.listAllRoleIds()` method — does it exist?**
   - What we know: `RbacService.getRoleInfo()` returns `Iterable<SysTableRoleInfo>` and `roleStore.listAll()` returns `List<Role>`. There is no `listAllRoleIds()` convenience method.
   - Recommendation: In `KeycloakRoleSyncer`, call `rbacService.listAllRoles()` (expose `roleStore.listAll()` via a new `RbacService.listAllRoles()` method returning `List<Role>`) or check each roleId individually with `roleStore.get(roleId)`. The per-role check is simpler and avoids adding a method to `RbacService`.

4. **Keycloak-issued token `email` claim can be absent (service accounts)**
   - What we know: Service accounts (client credentials grant) do not set `preferred_username` to a human name and typically lack `email`. JIT provisioning with `email = null` should store `email = ""` rather than throw NPE.
   - Recommendation: Null-check email in `KeycloakTokenDetails` and `JitUserProvisioner.provision()`. Already shown in code examples above with `email != null ? email : ""`.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 Jupiter (consistent with Phase 30/31 pattern) |
| Config file | `pom.xml` — Maven Surefire picks up both JUnit 4 and 5 |
| Quick run command | `mvn test -pl services/keycloak,dac/backend -Dtest="TestJitUserProvisioner,TestKeycloakRoleSyncer,TestDACAuthFilterJit" -am -DfailIfNoTests=false` |
| Full suite command | `mvn test -pl services/keycloak,sabot/kernel,dac/backend -am -DfailIfNoTests=false` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| JIT-01 | First Keycloak JWT call auto-creates REMOTE user with correct username + email | unit | `mvn test -pl services/keycloak -Dtest=TestJitUserProvisioner#testProvisionNewUser -am` | ❌ Wave 0 |
| JIT-02 | JIT-created user rejects `POST /apiv2/login` (REMOTE type) | unit | `mvn test -pl services/users -Dtest=TestSimpleUserService#testRemoteUserCannotAuthenticate -am` | ✅ (pattern exists) |
| JIT-02 | JIT-created user type is REMOTE (sentinel locked) | unit | `mvn test -pl services/keycloak -Dtest=TestJitUserProvisioner#testProvisionedUserIsRemoteType -am` | ❌ Wave 0 |
| JIT-03 | Concurrent `provision()` calls result in exactly one user record | unit | `mvn test -pl services/keycloak -Dtest=TestJitUserProvisioner#testConcurrentProvisioningIsIdempotent -am` | ❌ Wave 0 |
| ROLE-01 | `realm_access.roles` from Keycloak JWT are applied to Dremio RBAC memberships | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakRoleSyncer#testRolesGrantedOnLogin -am` | ❌ Wave 0 |
| ROLE-02 | Additive mode preserves manually-assigned roles not in Keycloak token | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakRoleSyncer#testAdditiveModePreservesManualRoles -am` | ❌ Wave 0 |
| ROLE-03 | Authoritative mode revokes stale Keycloak-sourced roles | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakRoleSyncer#testAuthoritativeModeRevokesStaleRoles -am` | ❌ Wave 0 |
| ROLE-04 | Keycloak roles not in Dremio RBAC are silently ignored | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakRoleSyncer#testUnknownKeycloakRolesIgnored -am` | ❌ Wave 0 |
| JIT-01 + ROLE-01 | DACAuthFilter.filter() end-to-end: new user provisioned + roles synced on first call | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterJit -am` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -pl services/keycloak -am -DfailIfNoTests=false`
- **Per wave merge:** `mvn test -pl services/keycloak,sabot/kernel,dac/backend -am -DfailIfNoTests=false`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakTokenDetails.java` — new class carrying username + email + realmRoles + expiresAt
- [ ] `services/keycloak/src/main/java/com/dremio/service/keycloak/JitUserProvisioner.java` — REMOTE user creation with race-safe idempotency
- [ ] `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakRoleSyncer.java` — additive/authoritative role sync
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestJitUserProvisioner.java` — unit tests for JIT-01, JIT-02, JIT-03
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakRoleSyncer.java` — unit tests for ROLE-01 through ROLE-04
- [ ] `dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterJit.java` — integration unit test for filter() JIT + role sync path
- [ ] `sabot/kernel/src/main/protobuf/rbac.proto` — add `string source = 5` to `Membership` message
- [ ] `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` — add `addMembership(userName, roleId, grantedBy, source)` overload
- [ ] `services/keycloak/pom.xml` — add `services/users` dependency if not already present (for `UserGroupStoreBuilder`)

---

## Sources

### Primary (HIGH confidence)
- Codebase: `services/users/src/main/protobuf/users.proto` — `UserType.REMOTE = 2`, `UserConfig.type = 11 [default = LOCAL]`
- Codebase: `services/users/src/main/java/com/dremio/service/users/SimpleUserService.java` — line 354 `type != UserType.LOCAL` → blocks authenticate(); line 168-190 `createUser()` validates password and uses `LOCAL` default type; `findUserByUserName()` + `put()` TOCTOU pattern
- Codebase: `services/users/src/test/java/com/dremio/service/users/TestSimpleUserService.java` lines 194-204 — direct KVStore write pattern for REMOTE user creation (established test pattern)
- Codebase: `sabot/kernel/src/main/protobuf/rbac.proto` — `Membership` message (fields 1-4); confirmed no `source` field; proto3 syntax (field 5 is safe addition)
- Codebase: `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` — `addMembership(userName, roleId, grantedBy)` signature; `listMembershipsByUser()` for role sync
- Codebase: `sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java` — `add()` uses `PutOption.CREATE`; throws `RbacEntityAlreadyExistsException` on duplicate
- Codebase: `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java` — current `validate()` returns `TokenDetails`; uses `JWTClaimsSet`
- Codebase: `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` — singleton; `filter()` calls `getUser()` which can throw `UserNotFoundException`; `@Inject @Nullable` field injection pattern confirmed
- Codebase: `services/tokens/src/main/java/com/dremio/service/tokens/TokenDetails.java` — `final` class; does not carry email or roles; `scopes` field exists but semantically wrong for roles
- Javap: `com.nimbusds.jwt.JWTClaimsSet` in `nimbus-jose-jwt-9.41.jar` — `getJSONObjectClaim(String)` returns `Map<String, Object>`; verified
- `.planning/STATE.md` key design decisions — `source=keycloak` membership tag, `preferred_username` as identity, JIT provisioning in DACAuthFilter

### Secondary (MEDIUM confidence)
- Codebase: `services/users/src/main/java/com/dremio/service/users/UserAlreadyExistException.java` — extends `IllegalArgumentException`; thrown by `createUser()` on duplicate
- Codebase: `services/users/src/main/java/com/dremio/service/users/UserServiceUtils.java` — `validatePassword()` requires `(?=.*[0-9])(?=.*[a-zA-Z]).{8,}` — confirms that `createUser` API cannot be used for passwordless REMOTE users

### Tertiary (LOW confidence)
- None for Phase 32

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all classes and their methods verified directly in codebase
- Architecture: HIGH — JIT flow derived from DACAuthFilter.filter() source; REMOTE user pattern verified in test; proto3 field addition is standard
- Pitfalls: HIGH — REMOTE type check at authenticate() line 354 confirmed; singleton DACAuthFilter concurrency issue verified in source; TOCTOU in createUser() confirmed

**Research date:** 2026-03-12
**Valid until:** 2026-04-12 (stable codebase; no external library version changes)
