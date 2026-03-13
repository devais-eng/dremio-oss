# Phase 35: Arrow Flight JWT Authentication - Research

**Researched:** 2026-03-13
**Domain:** Arrow Flight auth provider, DremioFlightAuthProviderImpl, DremioCredentialValidator, DremioFlightServerBasicAuthValidator, OidcTokenValidator, JitUserProvisioner, KeycloakRoleSyncer
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| JDBC-01 | Arrow Flight credential validator accepts Keycloak JWT as password (detects `eyJ` prefix, validates via JWKS) | Intercept `password.startsWith("eyJ")` in both `DremioCredentialValidator.validate()` (auth2 mode) and `DremioFlightServerBasicAuthValidator.getToken()` (legacy mode) before the call to `userService.authenticate()`. Route to `OidcTokenValidator.validateWithClaims()` instead, then mint a Dremio session token via `TokenManager.createToken()`. |
| JDBC-02 | JIT provisioning triggers on JDBC/ODBC first login (user auto-created if not exists) | After `validateWithClaims()` succeeds, call `JitUserProvisioner.provision()` + `KeycloakRoleSyncer.syncRoles()` using the returned `KeycloakTokenDetails`. These classes already exist from Phase 32 and are bound in the HK2 registry under the Keycloak branch. The Flight auth classes need them injected. |
</phase_requirements>

---

## Summary

Phase 35 adds Keycloak JWT support to Dremio's Arrow Flight SQL endpoint. The implementation mirrors the pattern already working in the REST API path (`DACAuthFilter`): detect `eyJ`-prefixed passwords, validate them via `OidcTokenValidator.validateWithClaims()`, run JIT provisioning + role sync, then mint a Dremio session token so the Arrow Flight session machinery continues unchanged.

The Dremio Flight service supports two authentication modes:
- **auth2 mode** (default, `arrow.flight.auth2`): `DremioBearerTokenAuthenticator` wraps a `BasicCallHeaderAuthenticator` which uses `DremioCredentialValidator.validate(username, password)`. This is the active path on production.
- **legacy mode** (`legacy.arrow.flight.auth`): `BasicServerAuthHandler` wraps `DremioFlightServerBasicAuthValidator.getToken(username, password)`.

Both modes converge at a `username`/`password` pair before minting a Dremio token. The JWT detection and routing can be inserted into both validators. The cleanest approach follows `DACAuthFilter` exactly: check `password.startsWith("eyJ")`, call `validateWithClaims()`, provision user if needed, sync roles, then call `DremioFlightAuthUtils.createUserSessionWithTokenAndProperties()` instead of `authenticateCredentials()`.

**Primary recommendation:** Modify `DremioFlightAuthProviderImpl` to accept `OidcTokenValidator`, `JitUserProvisioner`, and `KeycloakRoleSyncer` as optional constructor parameters (passed from `DACDaemonModule`). Introduce `DremioFlightServerBasicAuthValidator` subclass and `DremioCredentialValidator` subclass (or update both directly) to implement JWT dispatch. Add `dremio-services-keycloak` as a dependency to `services/arrow-flight/pom.xml`.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `DremioFlightAuthProviderImpl` | project | Factory for auth handlers; entry point for wiring | Already used; `addAuthHandler()` dispatches to legacy or auth2 mode |
| `DremioCredentialValidator` | project | `validate(username, password)` called in auth2 mode | Already the credential check point for auth2 |
| `DremioFlightServerBasicAuthValidator` | project | `getToken(username, password)` called in legacy mode | Already the credential check point for legacy |
| `DremioFlightAuthUtils` | project | `authenticateCredentials()` + `createUserSessionWithTokenAndProperties()` | JWT path must call `createUserSessionWithTokenAndProperties()` directly (bypassing `authenticate()`) |
| `OidcTokenValidator` | project (Phase 30) | `validateWithClaims(jwtString)` returns `KeycloakTokenDetails` | Already bound in HK2 registry under Keycloak branch; injectable |
| `JitUserProvisioner` | project (Phase 32) | `provision(username, email)` creates `UserType.REMOTE` user on first login | Already implemented and bound in HK2 registry |
| `KeycloakRoleSyncer` | project (Phase 32) | `syncRoles(username, roles)` syncs `realm_access.roles` to RBAC | Already implemented and bound in HK2 registry |
| `TokenManager` | project | `createToken(username, null)` mints Dremio session token after JWT validation | Already passed to `DremioFlightAuthProviderImpl` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `KeycloakTokenDetails` | project (Phase 32) | Carries `username`, `email`, `realmRoles`, `expiresAt` | Returned by `OidcTokenValidator.validateWithClaims()`; use to drive JIT+sync |
| `CallStatus.UNAUTHENTICATED` | Arrow Flight | Flight exception for auth failures | Throw when JWT validation fails, same as existing `authenticateCredentials()` path |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Modifying `DremioCredentialValidator` directly | Creating a Keycloak-aware subclass | Direct modification is simpler — only one code path to maintain; subclass adds indirection without benefit |
| Constructor injection in validator classes | HK2 `@Inject @Nullable` in validators | Validators are instantiated manually in `DremioFlightAuthProviderImpl`; constructor injection is the right pattern here |
| Handling JWT in `DremioFlightAuthProviderImpl.addAuthHandler()` | Handling in validators | Validators are the correct place — they hold the username+password pair |

**No new Maven dependencies on the classpath** beyond the `dremio-services-keycloak` pom.xml entry.

**pom.xml addition required (services/arrow-flight/pom.xml):**
```xml
<dependency>
  <groupId>com.dremio.services</groupId>
  <artifactId>dremio-services-keycloak</artifactId>
  <version>${project.version}</version>
</dependency>
```

---

## Architecture Patterns

### How the Two Auth Modes Work (existing)

**auth2 mode (default):**
```
Client connects → DremioBearerTokenAuthenticator.authenticate(CallHeaders)
  → if no bearer token: BasicCallHeaderAuthenticator → DremioCredentialValidator.validate(username, password)
      → DremioFlightAuthUtils.authenticateCredentials() → userService.authenticate(username, password)
      → if ok: DremioFlightAuthUtils.createUserSessionWithTokenAndProperties() → Dremio token
  → if bearer token present: tokenManager.validateToken(bearerToken)
```

**legacy mode:**
```
Client connects → BasicServerAuthHandler → DremioFlightServerBasicAuthValidator.getToken(username, password)
  → DremioFlightAuthUtils.authenticateAndCreateToken() → userService.authenticate() + createToken()
```

### JWT Dispatch Insertion Point

For **both modes**, the insertion point is the `(username, password)` credential pair, when `password.startsWith("eyJ")`:

```java
// Instead of:
return userServiceProvider.get().authenticate(username, password);

// Do:
if (password.startsWith("eyJ") && oidcTokenValidator != null) {
    // Keycloak JWT path
    KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(password);
    // JIT provisioning (JDBC-02)
    if (jitProvisioner != null) {
        jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
    }
    // Role sync
    if (roleSyncer != null) {
        roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());
    }
    // Mint Dremio token (same path as successful password auth)
    return DremioFlightAuthUtils.createUserSessionWithTokenAndProperties(
        tokenManagerProvider, ktd.getUsername());
} else {
    // Existing path
    return DremioFlightAuthUtils.authenticateCredentials(userServiceProvider, username, password, logger);
}
```

### Recommended Project Structure (additions)

The `DremioFlightAuthProviderImpl` constructor is extended; both validator classes get a new overload or are updated to accept optional Keycloak components:

```
services/arrow-flight/
├── pom.xml                                         # ADD: dremio-services-keycloak dependency
└── src/main/java/com/dremio/service/flight/
    ├── DremioFlightAuthProviderImpl.java            # MODIFY: accept OidcTokenValidator, JitUserProvisioner, KeycloakRoleSyncer
    ├── auth/
    │   └── DremioFlightServerBasicAuthValidator.java # MODIFY: JWT dispatch in getToken()
    └── auth2/
        └── DremioCredentialValidator.java           # MODIFY: JWT dispatch in validate()
        └── DremioBearerTokenAuthenticator.java      # MODIFY: pass keycloak components to validator

dac/backend/
└── src/main/java/com/dremio/dac/daemon/
    └── DACDaemonModule.java                         # MODIFY: pass OidcTokenValidator etc. to DremioFlightAuthProviderImpl
```

### Pattern 1: DremioFlightAuthProviderImpl Constructor Extension

**What:** Add optional Keycloak fields. When Keycloak is active (`auth.type = keycloak`), `DACDaemonModule` passes the already-bound instances; when not active, they are `null`.

**When to use:** Always — `null` means "Keycloak disabled", which is the existing behavior.

```java
// Source: DremioFlightAuthProviderImpl.java (current constructor for reference)
public class DremioFlightAuthProviderImpl implements DremioFlightAuthProvider {

  private final Provider<DremioConfig> configProvider;
  private final Provider<UserService> userServiceProvider;
  private final Provider<TokenManager> tokenManagerProvider;
  @Nullable private final OidcTokenValidator oidcTokenValidator;
  @Nullable private final JitUserProvisioner jitProvisioner;
  @Nullable private final KeycloakRoleSyncer roleSyncer;

  // Keycloak-aware constructor (use from DACDaemonModule when Keycloak active)
  public DremioFlightAuthProviderImpl(
      Provider<DremioConfig> configProvider,
      Provider<UserService> userServiceProvider,
      Provider<TokenManager> tokenManagerProvider,
      @Nullable OidcTokenValidator oidcTokenValidator,
      @Nullable JitUserProvisioner jitProvisioner,
      @Nullable KeycloakRoleSyncer roleSyncer) {
    this.configProvider = configProvider;
    this.userServiceProvider = userServiceProvider;
    this.tokenManagerProvider = tokenManagerProvider;
    this.oidcTokenValidator = oidcTokenValidator;
    this.jitProvisioner = jitProvisioner;
    this.roleSyncer = roleSyncer;
  }

  // Backward-compatible 3-arg constructor delegates with null Keycloak fields
  public DremioFlightAuthProviderImpl(
      Provider<DremioConfig> configProvider,
      Provider<UserService> userServiceProvider,
      Provider<TokenManager> tokenManagerProvider) {
    this(configProvider, userServiceProvider, tokenManagerProvider, null, null, null);
  }
  // ...
}
```

### Pattern 2: DACDaemonModule Wiring

**What:** Pass the already-bound Keycloak instances to `DremioFlightAuthProviderImpl` only in the Keycloak branch.

```java
// In DACDaemonModule, replace the existing Flight auth provider binding:
// BEFORE:
registry.bind(
    DremioFlightAuthProvider.class,
    new DremioFlightAuthProviderImpl(
        registry.provider(DremioConfig.class),
        registry.provider(UserService.class),
        registry.provider(TokenManager.class)));

// AFTER: Always pass providers; Keycloak ones are null when not configured
registry.bind(
    DremioFlightAuthProvider.class,
    new DremioFlightAuthProviderImpl(
        registry.provider(DremioConfig.class),
        registry.provider(UserService.class),
        registry.provider(TokenManager.class),
        registry.lookup(OidcTokenValidator.class),   // null when not Keycloak
        registry.lookup(JitUserProvisioner.class),   // null when not Keycloak
        registry.lookup(KeycloakRoleSyncer.class))); // null when not Keycloak
```

**Timing constraint:** `DremioFlightAuthProvider` is bound at line 1764-1770 of `DACDaemonModule`. Keycloak components are bound inside `setupUserService()` which is called earlier (line ~2212). Therefore Keycloak objects ARE available when the Flight binding runs. Use `registry.lookup()` (not `registry.provider()`) since the value is needed immediately, not lazily.

**Important:** The null-provider bindings for `OidcTokenValidator`, `JitUserProvisioner`, `KeycloakRoleSyncer` (lines 2222-2224 in the internal auth branch) ensure `registry.lookup()` never throws — it returns `null` for those types when not Keycloak-configured.

### Pattern 3: DremioCredentialValidator (auth2 mode)

**What:** Override `validate()` to detect JWT passwords and route to Keycloak validation + JIT + role sync.

**Return type change:** `validate()` returns `AuthResult` (Arrow Flight's `CallHeaderAuthenticator.AuthResult`). When JWT path succeeds, we return `authResult::getUserName` with the `ktd.getUsername()` as the peer identity.

```java
// In DremioCredentialValidator (auth2 mode)
private static final String JWT_COMPACT_PREFIX = "eyJ";

@Nullable private final OidcTokenValidator oidcTokenValidator;
@Nullable private final JitUserProvisioner jitProvisioner;
@Nullable private final KeycloakRoleSyncer roleSyncer;
private final Provider<TokenManager> tokenManagerProvider;

@Override
public AuthResult validate(String username, String password) {
  if (oidcTokenValidator != null && password.startsWith(JWT_COMPACT_PREFIX)) {
    return validateKeycloakJwt(password);
  }
  // Existing path
  com.dremio.service.users.AuthResult authResult =
      DremioFlightAuthUtils.authenticateCredentials(userServiceProvider, username, password, LOGGER);
  return authResult::getUserName;
}

private AuthResult validateKeycloakJwt(String jwtString) {
  try {
    KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(jwtString);
    if (jitProvisioner != null) {
      jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
    }
    if (roleSyncer != null) {
      roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());
    }
    final String resolvedUsername = ktd.getUsername();
    return () -> resolvedUsername;
  } catch (Exception e) {
    LOGGER.error("Keycloak JWT validation failed in Flight credential validator", e);
    throw CallStatus.UNAUTHENTICATED.withCause(e)
        .withDescription("Keycloak JWT validation failed: " + e.getMessage())
        .toRuntimeException();
  }
}
```

**Note on `DremioBearerTokenAuthenticator`:** After `DremioCredentialValidator.validate()` returns the `AuthResult`, `DremioBearerTokenAuthenticator.getAuthResultWithBearerToken()` calls `DremioFlightAuthUtils.createUserSessionWithTokenAndProperties(tokenManagerProvider, username)` — this mints a Dremio token. No change needed in `DremioBearerTokenAuthenticator` itself; the Keycloak username is already in `authResult.getPeerIdentity()`.

### Pattern 4: DremioFlightServerBasicAuthValidator (legacy mode)

**What:** `getToken(username, password)` must detect JWT and route to Keycloak path.

```java
// In DremioFlightServerBasicAuthValidator (legacy mode)
private static final String JWT_COMPACT_PREFIX = "eyJ";

@Override
public byte[] getToken(String username, String password) {
  if (oidcTokenValidator != null && password.startsWith(JWT_COMPACT_PREFIX)) {
    return validateKeycloakJwtAndCreateToken(password);
  }
  // Existing path
  final String token = DremioFlightAuthUtils.authenticateAndCreateToken(
      userServiceProvider, tokenManagerProvider, dremioFlightSessionsManager,
      username, password, LOGGER);
  return token.getBytes(UTF_8);
}

private byte[] validateKeycloakJwtAndCreateToken(String jwtString) {
  try {
    KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(jwtString);
    if (jitProvisioner != null) {
      jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
    }
    if (roleSyncer != null) {
      roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());
    }
    String token = DremioFlightAuthUtils.createUserSessionWithTokenAndProperties(
        tokenManagerProvider, ktd.getUsername());
    return token.getBytes(UTF_8);
  } catch (Exception e) {
    LOGGER.error("Keycloak JWT validation failed in Flight basic auth validator", e);
    throw CallStatus.UNAUTHENTICATED.withCause(e)
        .withDescription("Keycloak JWT validation failed: " + e.getMessage())
        .toRuntimeException();
  }
}
```

### Token Lifetime Mismatch (documented, not fixed in this phase)

Keycloak access tokens have a 5-minute default TTL. Long-running BI connections may have the initial Dremio token still valid but the Keycloak JWT used to create it has expired. This is already tracked as a known concern in `STATE.md`. Mitigation: users can exchange their Keycloak token for a longer-lived Dremio session token via `POST /apiv2/login` (once the Dremio user exists after first login). This is a documentation concern, not a code concern for this phase.

### Anti-Patterns to Avoid

- **Calling `userService.authenticate()` with the JWT as password:** `authenticate()` calls `UserServiceUtils.slowEquals(authKey, hash)` on the JWT string — it will always return false and throw `UserLoginException`. The JWT must be intercepted BEFORE `authenticate()` is called.
- **Storing Keycloak components in `DremioCredentialValidator` as a singleton field mutated after construction:** `DremioCredentialValidator` is instantiated once inside `DremioBearerTokenAuthenticator`'s constructor. Pass all Keycloak components via constructor, not via setter.
- **Calling `jitProvisioner.provision()` but not catching exceptions:** `provision()` declares `throws IOException` but never actually throws; wrap in a try-catch anyway to prevent any unexpected exception from crashing the Flight auth call with a non-UNAUTHENTICATED status.
- **Not providing a backward-compatible 3-arg constructor on `DremioFlightAuthProviderImpl`:** The existing 3-arg constructor is called from tests. Keep it; it delegates to the new 6-arg one with `null` for Keycloak fields.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JWT signature/issuer/expiry validation | Custom JWT parsing in Flight code | `OidcTokenValidator.validateWithClaims()` | Already built, tested, and battle-proven in Phase 30-32; includes JWKS caching and key rotation |
| User auto-creation on first Flight login | Custom KVStore write in Flight code | `JitUserProvisioner.provision()` | Already handles concurrent races, `UserType.REMOTE`, and email-null cases |
| Role sync on Flight login | Custom RBAC sync in Flight code | `KeycloakRoleSyncer.syncRoles()` | Already handles additive/authoritative modes, unknown Keycloak roles, and race conditions |
| Dremio session token minting | Custom token creation | `DremioFlightAuthUtils.createUserSessionWithTokenAndProperties()` | Existing utility; creates `TokenDetails` via `TokenManager` |

**Key insight:** The entire Keycloak auth machinery (validate → JIT → sync → token) is fully reusable from Phase 32. Phase 35 only needs to wire it into the Flight credential validators.

---

## Common Pitfalls

### Pitfall 1: registry.lookup() vs registry.provider() Ordering

**What goes wrong:** `registry.provider(OidcTokenValidator.class)` returns a provider that calls `lookup()` lazily. If `OidcTokenValidator` is bound as `null` (internal auth branch) and you try `provider.get()` on a null-bound provider, behavior depends on implementation — may NPE silently.

**Why it happens:** The null-provider bindings at lines 2222-2224 bind `() -> null` as the provider. `registry.provider(OidcTokenValidator.class).get()` returns `null`. This is safe if the code guards on `oidcTokenValidator != null`.

**How to avoid:** Use `registry.lookup(OidcTokenValidator.class)` (eager resolution, returns `null`) at the Flight binding site (line 1764). Store the result as a `@Nullable` field. The `null` check in the credential validator guards the JWT path correctly.

**Warning signs:** `NullPointerException` in `OidcTokenValidator.validateWithClaims()` when Keycloak is not configured and a user connects via username+password.

### Pitfall 2: `DremioFlightAuthProviderImpl` Bound Before `setupUserService()` Completes

**What goes wrong:** `DremioFlightAuthProviderImpl` is bound at `isCoordinator && FLIGHT_SERVICE_ENABLED_BOOLEAN` (line 1764). `setupUserService()` (which binds Keycloak components) is called later. If `registry.lookup()` is called before `setupUserService()` runs, `OidcTokenValidator` may not yet be in the registry.

**Why it happens:** Module initialization order matters in `DACDaemonModule`.

**How to avoid:** Check the actual call order in `DACDaemonModule`. Grep for the invocation of `setupUserService()` — it is called at line ~2200 (in the `if (isCoordinator)` block). The Flight binding at line 1764 comes before that in the source file but both are in the same `setup()` method. Check: `setupUserService()` is called in `DACDaemonModule.setup()` as part of user service setup; the Flight binding at 1764 is also in `setup()`. The source line order determines execution order.

**Verified:** Looking at the file, `setupUserService()` (`setupUserService(registry, dacConfig, isCoordinator, isMaster, isDistributedMaster)`) is called at approximately line 2200+, AFTER the Flight binding at line 1764. This means `registry.lookup(OidcTokenValidator.class)` at line 1764 will fail (class not yet bound).

**Resolution:** Use `registry.provider(OidcTokenValidator.class)` (lazy resolution) in `DremioFlightAuthProviderImpl`, NOT `registry.lookup()`. Store as `Provider<OidcTokenValidator>` and call `.get()` on first use. OR restructure: move the Flight binding AFTER `setupUserService()`. The cleanest option is to use lazy providers for all Keycloak components:

```java
// In DACDaemonModule, AFTER setupUserService() is called:
registry.bind(
    DremioFlightAuthProvider.class,
    new DremioFlightAuthProviderImpl(
        registry.provider(DremioConfig.class),
        registry.provider(UserService.class),
        registry.provider(TokenManager.class),
        registry.provider(OidcTokenValidator.class).get(),   // safe because setupUserService ran
        registry.provider(JitUserProvisioner.class).get(),   // safe
        registry.provider(KeycloakRoleSyncer.class).get())); // safe
```

To do this cleanly, move (or duplicate) the Flight auth provider binding to after `setupUserService()` is called in `DACDaemonModule.setup()`. The planner must determine exact line ordering. Alternatively, store `Provider<OidcTokenValidator>` in `DremioFlightAuthProviderImpl` and call `.get()` inside `addAuthHandler()` — this works because `addAuthHandler()` is called during `DremioFlightService.start()`, which is after all bindings are complete.

**Recommended resolution:** Store `Provider<OidcTokenValidator>`, `Provider<JitUserProvisioner>`, `Provider<KeycloakRoleSyncer>` in `DremioFlightAuthProviderImpl` and resolve lazily in `addAuthHandler()`. This is the same lazy-provider pattern used in `KeycloakRoleSyncer` itself.

### Pitfall 3: JWT Payload is `password` Field in Basic Auth

**What goes wrong:** Arrow Flight Basic auth sends `username:password` in the Authorization header. Flight SQL clients (JDBC, DBC) typically set `username = keycloak_username` and `password = keycloak_jwt`. Some clients may send a blank username and the full JWT as the password, or set username = "token" and password = JWT.

**Why it happens:** The Flight SQL spec does not mandate how JWT bearer tokens map to username/password fields. Different clients behave differently.

**How to avoid:** The `eyJ` prefix detection is on the `password` field (matches JWT compact serialization prefix for `{"typ":"JWT","alg":...}` or `{"alg":"RS256",...}`). The `username` from Basic auth is IGNORED when the JWT path is taken — username is extracted from `preferred_username` inside the JWT itself. This is the correct approach: the JWT is self-describing.

**Warning signs:** Flight client connects with JWT but gets rejected because username in the Basic auth header doesn't match `preferred_username` in the JWT.

### Pitfall 4: `DremioBearerTokenAuthenticator` Token Validation Path (Bearer Reuse)

**What goes wrong:** After initial JWT auth, `DremioBearerTokenAuthenticator.validateBearer(token)` validates subsequent calls. It calls `tokenManagerProvider.get().validateToken(token)`. If a Keycloak JWT (not a Dremio token) is passed as a Bearer header on subsequent calls, `tokenManager.validateToken()` will reject it.

**Why it happens:** The Dremio token minted after JWT validation is an opaque Dremio token, not the original JWT. But some clients may re-send the original Keycloak JWT as the Bearer token on every call.

**How to avoid:** The existing `validateBearer()` path should also be extended to handle `eyJ`-prefixed bearer tokens. When `bearerToken.startsWith("eyJ")` and `oidcTokenValidator != null`, validate it as a Keycloak JWT. This applies to `DremioBearerTokenAuthenticator.validateBearer()` only in auth2 mode. The legacy mode (`isValid(byte[])` in `DremioFlightServerBasicAuthValidator`) has the same issue.

**Confirmed pattern from DACAuthFilter:** REST API already handles this — `DACAuthFilter.getUserNameFromToken()` checks `tokenStr.startsWith("eyJ")` and tries OIDC first. The same check must be in the Flight bearer validation path.

**Resolution:** Also update `DremioBearerTokenAuthenticator.validateBearer()` to detect `eyJ` prefix and call `oidcTokenValidator.validateWithClaims()` when applicable.

### Pitfall 5: `ParseException` Not Caught in Keycloak JWT Path

**What goes wrong:** `OidcTokenValidator.validateWithClaims(jwtString)` can throw `ParseException` (from `JWTParser.parse()`). `ParseException` is checked. The Arrow Flight credential validator doesn't know about `ParseException` and catches only `RuntimeException`.

**Why it happens:** `validateWithClaims()` declares `throws ParseException`.

**How to avoid:** Wrap the JWT validation call in a try-catch that catches both `ParseException` and `IllegalArgumentException` (thrown by `validateWithClaims()` on bad signature/issuer/aud):
```java
try {
  KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(jwtString);
  ...
} catch (ParseException | IllegalArgumentException e) {
  throw CallStatus.UNAUTHENTICATED.withCause(e)
      .withDescription("Keycloak JWT validation failed: " + e.getMessage())
      .toRuntimeException();
}
```

---

## Code Examples

### auth2 mode: DremioCredentialValidator with JWT dispatch

```java
// Source: DremioCredentialValidator.java (current) + DACAuthFilter.java pattern
// Note: OidcTokenValidator, JitUserProvisioner, KeycloakRoleSyncer passed via constructor
private static final String JWT_COMPACT_PREFIX = "eyJ";

@Override
public AuthResult validate(String username, String password) {
  // JDBC-01: Detect Keycloak JWT in the password field
  if (oidcTokenValidator != null && password.startsWith(JWT_COMPACT_PREFIX)) {
    try {
      KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(password);
      // JDBC-02: JIT provisioning
      if (jitProvisioner != null) {
        jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
      }
      // Role sync (follows JIT provisioning — user must exist first)
      if (roleSyncer != null) {
        roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());
      }
      final String resolvedUsername = ktd.getUsername();
      return () -> resolvedUsername;
    } catch (ParseException | IllegalArgumentException | IOException e) {
      LOGGER.error("Keycloak JWT validation failed for Flight connection", e);
      throw CallStatus.UNAUTHENTICATED
          .withCause(e)
          .withDescription("Keycloak JWT auth failed: " + e.getMessage())
          .toRuntimeException();
    }
  }
  // Existing Dremio username/password path unchanged
  com.dremio.service.users.AuthResult authResult =
      DremioFlightAuthUtils.authenticateCredentials(
          userServiceProvider, username, password, LOGGER);
  return authResult::getUserName;
}
```

### auth2 mode: DremioBearerTokenAuthenticator — extended validateBearer()

```java
// Source: DremioBearerTokenAuthenticator.java (current validateBearer) + DACAuthFilter eyJ pattern
@VisibleForTesting
AuthResult validateBearer(String token) {
  // JDBC-01: Bearer tokens starting with eyJ may be Keycloak JWTs (not Dremio tokens)
  if (oidcTokenValidator != null && token.startsWith(JWT_COMPACT_PREFIX)) {
    try {
      KeycloakTokenDetails ktd = oidcTokenValidator.validateWithClaims(token);
      if (jitProvisioner != null) {
        jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
      }
      if (roleSyncer != null) {
        roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());
      }
      // Mint a Dremio session token for this connection
      String dremioToken = DremioFlightAuthUtils.createUserSessionWithTokenAndProperties(
          tokenManagerProvider, ktd.getUsername());
      return createAuthResultWithBearerToken(dremioToken);
    } catch (ParseException | IllegalArgumentException | IOException e) {
      LOGGER.error("Keycloak JWT bearer validation failed in Flight", e);
      throw CallStatus.UNAUTHENTICATED.toRuntimeException();
    }
  }
  // Existing Dremio token path
  try {
    tokenManagerProvider.get().validateToken(token);
    return createAuthResultWithBearerToken(token);
  } catch (IllegalArgumentException e) {
    LOGGER.error("Bearer token validation failed.", e);
    throw CallStatus.UNAUTHENTICATED.toRuntimeException();
  }
}
```

### DACDaemonModule — updated Flight provider binding

```java
// Source: DACDaemonModule.java lines 1764-1770 (current)
// Move this block to AFTER setupUserService() is called, or use provider.get() when values are needed

// In the Keycloak branch of setupUserService() — AFTER all keycloak bindings:
// (at the bottom of the keycloak if-block, around line 2263+)
// OR: move the Flight binding to after the setupUserService() call.

// Simplest approach: move Flight provider binding after setupUserService()
// so Keycloak components are already bound when we call provider.get():
registry.bind(
    DremioFlightAuthProvider.class,
    new DremioFlightAuthProviderImpl(
        registry.provider(DremioConfig.class),
        registry.provider(UserService.class),
        registry.provider(TokenManager.class),
        registry.provider(OidcTokenValidator.class),   // Provider — resolved lazily at start()
        registry.provider(JitUserProvisioner.class),   // Provider — resolved lazily at start()
        registry.provider(KeycloakRoleSyncer.class))); // Provider — resolved lazily at start()
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Flight only supports Dremio username+password auth | Flight accepts Keycloak JWTs as password | Phase 35 | Flight SQL JDBC clients can authenticate with Keycloak tokens directly |
| JIT provisioning only in REST (DACAuthFilter) | JIT provisioning also in Flight credential validators | Phase 35 | First Flight connection from new Keycloak user auto-creates Dremio account |

**No deprecated items** — existing `DremioFlightServerBasicAuthValidator` and `DremioCredentialValidator` are extended, not replaced.

---

## Open Questions

1. **Exact position of Flight auth provider binding vs setupUserService() in DACDaemonModule**
   - What we know: Flight binding is at line ~1764; `setupUserService()` call is around line 2200. Flight binding runs BEFORE `setupUserService()`.
   - What's unclear: Whether to move the binding or use lazy providers throughout.
   - Recommendation: Store `Provider<OidcTokenValidator>`, `Provider<JitUserProvisioner>`, `Provider<KeycloakRoleSyncer>` in `DremioFlightAuthProviderImpl` fields. Resolve via `.get()` inside `createBasicAuthValidator()` / `addAuthHandler()`. This is safe because `addAuthHandler()` is called from `DremioFlightService.start()`, which runs after all bindings are complete. The planner should confirm line order before deciding.

2. **Username field handling when client sends JWT as password**
   - What we know: Most Flight SQL JDBC drivers send the Keycloak username as `username` and JWT as `password` in Basic auth. The username field is available but redundant (JWT already carries `preferred_username`).
   - Recommendation: Ignore the `username` field when the JWT path is taken. Use `ktd.getUsername()` as the canonical identity. This matches DACAuthFilter behavior.

3. **Session token lifetime vs Keycloak JWT lifetime**
   - What we know: Keycloak access tokens expire in ~5 minutes (configurable). The Dremio session token created by `createToken()` has its own TTL (24h default). Flight connections typically establish once then hold the Dremio bearer token.
   - Recommendation: Document (in phase notes) that the Dremio token issued after JWT login has a longer lifetime than the JWT itself. This is the intended behavior per STATE.md.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4 (consistent with existing `services/arrow-flight` tests — uses `@Before`, `@Test`, no Jupiter annotations) |
| Config file | `pom.xml` — Maven Surefire picks up JUnit 4 |
| Quick run command | `mvn test -pl services/arrow-flight -Dtest="TestDremioCredentialValidator,TestDremioBearerTokenAuthenticator,TestDremioFlightServerAuthValidator" -am -DfailIfNoTests=false` |
| Full suite command | `mvn test -pl services/arrow-flight -am -DfailIfNoTests=false` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| JDBC-01 | `DremioCredentialValidator.validate()` accepts `eyJ`-prefixed password via OidcTokenValidator | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioCredentialValidator#testValidateWithKeycloakJwt -am` | ❌ Wave 0 |
| JDBC-01 | `DremioCredentialValidator.validate()` falls back to Dremio auth for non-JWT password | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioCredentialValidator#testValidateWithDremioPassword -am` | ✅ (testAuthenticateWithValidCredentials) |
| JDBC-01 | `DremioFlightServerBasicAuthValidator.getToken()` accepts `eyJ`-prefixed password | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioFlightServerAuthValidator#testGetTokenWithKeycloakJwt -am` | ❌ Wave 0 |
| JDBC-01 | `DremioBearerTokenAuthenticator.validateBearer()` accepts Keycloak JWT bearer token | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioBearerTokenAuthenticator#testValidateBearerWithKeycloakJwt -am` | ❌ Wave 0 |
| JDBC-01 | `DremioBearerTokenAuthenticator.validateBearer()` still accepts Dremio opaque tokens | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioBearerTokenAuthenticator#testValidateBearerWithValidToken -am` | ✅ (testValidateBearerWithValidToken) |
| JDBC-02 | `DremioCredentialValidator.validate()` calls `jitProvisioner.provision()` for unknown user JWT | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioCredentialValidator#testValidateWithKeycloakJwtCallsJit -am` | ❌ Wave 0 |
| JDBC-01 | Invalid Keycloak JWT in Flight throws UNAUTHENTICATED FlightRuntimeException | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioCredentialValidator#testValidateWithInvalidKeycloakJwt -am` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -pl services/arrow-flight -Dtest="TestDremioCredentialValidator,TestDremioBearerTokenAuthenticator,TestDremioFlightServerAuthValidator" -am -DfailIfNoTests=false`
- **Per wave merge:** `mvn test -pl services/arrow-flight -am -DfailIfNoTests=false`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

Test methods to add to existing test files:
- [ ] `TestDremioCredentialValidator#testValidateWithKeycloakJwt` — JDBC-01: mock `OidcTokenValidator.validateWithClaims()`, verify returned `AuthResult.getPeerIdentity()` = keycloak username
- [ ] `TestDremioCredentialValidator#testValidateWithKeycloakJwtCallsJit` — JDBC-02: verify `jitProvisioner.provision()` is called
- [ ] `TestDremioCredentialValidator#testValidateWithInvalidKeycloakJwt` — JDBC-01: invalid JWT throws `FlightRuntimeException(UNAUTHENTICATED)`
- [ ] `TestDremioBearerTokenAuthenticator#testValidateBearerWithKeycloakJwt` — JDBC-01: `eyJ`-prefixed bearer validated via OIDC
- [ ] `TestDremioFlightServerAuthValidator#testGetTokenWithKeycloakJwt` — JDBC-01: legacy mode JWT dispatch

pom.xml addition:
- [ ] `services/arrow-flight/pom.xml` — add `dremio-services-keycloak` runtime dependency

New constructor in `DremioFlightAuthProviderImpl`:
- [ ] 6-arg constructor accepting `Provider<OidcTokenValidator>`, `Provider<JitUserProvisioner>`, `Provider<KeycloakRoleSyncer>` (existing 3-arg constructor delegates with null providers)

---

## Sources

### Primary (HIGH confidence)
- Codebase: `services/arrow-flight/src/main/java/com/dremio/service/flight/DremioFlightAuthProviderImpl.java` — current constructor (3-arg), `addAuthHandler()` dispatches to legacy/auth2 mode
- Codebase: `services/arrow-flight/src/main/java/com/dremio/service/flight/auth2/DremioCredentialValidator.java` — `validate(username, password)` calls `DremioFlightAuthUtils.authenticateCredentials()`
- Codebase: `services/arrow-flight/src/main/java/com/dremio/service/flight/auth2/DremioBearerTokenAuthenticator.java` — `authenticate()` and `validateBearer()` — bearer token path
- Codebase: `services/arrow-flight/src/main/java/com/dremio/service/flight/auth/DremioFlightServerBasicAuthValidator.java` — `getToken()` calls `DremioFlightAuthUtils.authenticateAndCreateToken()`
- Codebase: `services/arrow-flight/src/main/java/com/dremio/service/flight/utils/DremioFlightAuthUtils.java` — `authenticateCredentials()`, `createUserSessionWithTokenAndProperties()`, `createToken()`
- Codebase: `services/arrow-flight/src/main/java/com/dremio/service/flight/DremioFlightService.java` — `start()` calls `authProvider.get().addAuthHandler(builder, ...)` after all bindings
- Codebase: `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` — lines 1764-1770 (Flight auth provider binding), lines 2220-2261 (Keycloak bindings in setupUserService), lines 2222-2224 (null provider bindings for internal auth)
- Codebase: `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` — `JWT_COMPACT_PREFIX = "eyJ"` pattern, `getUserNameFromToken()` logic for REST path (reference implementation)
- Codebase: `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java` — `validateWithClaims()` signature and return type
- Codebase: `services/keycloak/src/main/java/com/dremio/service/keycloak/JitUserProvisioner.java` — `provision(username, email)` signature
- Codebase: `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakRoleSyncer.java` — `syncRoles(username, roles)` signature
- Codebase: `services/arrow-flight/pom.xml` — confirmed `dremio-services-keycloak` NOT present; must add
- Test: `services/arrow-flight/src/test/java/com/dremio/service/flight/TestDremioFlightService.java` — confirms default auth mode is `FLIGHT_AUTH2_AUTH_MODE`
- Test: `services/arrow-flight/src/test/java/com/dremio/service/flight/BasicFlightAuthenticationTest.java` — test base class pattern for auth unit tests (mock `UserService`, `TokenManager`, `DremioFlightSessionsManager`)

### Secondary (MEDIUM confidence)
- `.planning/STATE.md` key design decisions — `eyJ` prefix discriminator pattern, confirmed for Phase 35 wiring

### Tertiary (LOW confidence)
- None for Phase 35

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all classes verified directly in codebase; exact method signatures confirmed
- Architecture: HIGH — auth flow traced end-to-end through both legacy and auth2 modes; insertion point unambiguous
- Pitfalls: HIGH — ordering issue (Flight binding before setupUserService) verified in DACDaemonModule source; JIT/roleSync integration pattern confirmed from Phase 32 code

**Research date:** 2026-03-13
**Valid until:** 2026-04-13 (stable codebase; no external library changes)
