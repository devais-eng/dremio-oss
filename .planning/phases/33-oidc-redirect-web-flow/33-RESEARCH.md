# Phase 33: OIDC Redirect Web Flow - Research

**Researched:** 2026-03-12
**Domain:** OIDC Authorization Code Flow with PKCE, JAX-RS redirect endpoints, in-process state management, Nimbus oauth2-oidc-sdk
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| OIDC-01 | Web UI user can click "Login with SSO" and be redirected to Keycloak's login page (Authorization Code Flow) | `GET /apiv2/oidc/login` (RestResource, no @Secured): generate `code_verifier`, `code_challenge` (SHA256), `state` nonce; store both in `OidcStateStore`; build Keycloak authorization URL; return `Response.temporaryRedirect(authorizationUri)`. Nimbus `AuthorizationRequest.Builder` + `CodeChallenge.compute(verifier, S256)` constructs the URL. |
| OIDC-02 | After Keycloak authentication, callback exchanges code for tokens and mints a Dremio session token | `GET /apiv2/oidc/callback` (no @Secured): receives `code` + `state`; validates `state` against `OidcStateStore`; retrieves `code_verifier`; POSTs `TokenRequest` to Keycloak token endpoint (`{issuerUrl}/protocol/openid-connect/token`) using Nimbus; gets `OIDCTokens` from response; provisions user via `JitUserProvisioner`; syncs roles via `KeycloakRoleSyncer`; mints Dremio session token via `tokenManager.createToken()`; stores `id_token` in `OidcSessionStore`; redirects browser to `/login/sso/landing#token=<dremio_token>`. |
| OIDC-03 | OIDC flow uses state parameter + PKCE for CSRF protection | `state` is a random 32-byte SecureRandom nonce stored in `OidcStateStore` (keyed by state value); `code_verifier` (43–128 chars, BASE64URL) stored alongside; PKCE method is S256. Tampered or expired `state` → 400 Bad Request. |
| LOUT-02 | Logout stores `id_token_hint` during OIDC callback for use at logout time | After token exchange succeeds, store the `id_token` (JWT string) associated with the Dremio session token in `OidcSessionStore` (ConcurrentHashMap keyed by Dremio token). Phase 35 reads from this store for RP-Initiated Logout. |
</phase_requirements>

---

## Summary

Phase 33 is a pure backend phase: it adds two new JAX-RS endpoints (`GET /apiv2/oidc/login` and `GET /apiv2/oidc/callback`) in the `dac/backend` module that implement the Authorization Code Flow with PKCE. The endpoints live in a new class `OidcResource` annotated with `@RestResource` (the `/apiv2` path prefix) and must NOT carry `@Secured` — they are called by an unauthenticated browser before any Dremio session exists.

The flow is:
1. Browser hits `GET /apiv2/oidc/login` → server generates `(state, code_verifier, code_challenge)`, stores them in an in-process map, returns HTTP 302 to Keycloak authorization endpoint with `state`, `code_challenge`, `code_challenge_method=S256` query parameters.
2. Keycloak authenticates the user and redirects to `GET /apiv2/oidc/callback?code=…&state=…` → server validates `state`, retrieves stored `code_verifier`, calls Keycloak token endpoint to exchange code, validates the access token with the existing `OidcTokenValidator`, runs JIT provisioning and role sync (reusing Phase 32 classes), mints a Dremio session token, stores the `id_token` for Phase 35, and redirects browser to `/login/sso/landing#token=<dremio_token>`.
3. The UI SSO landing page (Phase 34) reads the fragment, stores the token in localStorage, and completes the login flow.

The `oauth2-oidc-sdk` 11.20 is already declared in the root BOM and its JAR is present in the build distribution. It provides `AuthorizationRequest.Builder`, `CodeChallenge`, `CodeVerifier`, `TokenRequest`, `AuthorizationCodeGrant`, and `OIDCTokenResponseParser` — precisely what is needed for step 1 and step 2.

**Primary recommendation:** Add `OidcResource` class in `dac/backend`, `OidcStateStore` (in-process `ConcurrentHashMap`) and `OidcSessionStore` (in-process `ConcurrentHashMap`) in `services/keycloak`, wire all three in `DACDaemonModule` keycloak branch, and add `oauth2-oidc-sdk` as a dependency in `services/keycloak/pom.xml`.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.nimbusds:oauth2-oidc-sdk` | 11.20 (already in root BOM) | Build authorization URL, exchange code for tokens, parse OIDC token response | Already managed in BOM, JAR present in distribution; first use in project code |
| `com.nimbusds:nimbus-jose-jwt` | 9.41 (already in keycloak module) | `OidcTokenValidator.validateWithClaims()` to validate the access token returned by Keycloak | Used in Phase 30–32; re-used unchanged |
| JAX-RS `javax.ws.rs` | inherited | `@GET`, `@Path`, `@QueryParam`, `Response.temporaryRedirect()` for redirect endpoints | Existing pattern in all Dremio REST resources |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `java.security.SecureRandom` | JDK | Generate random `state` nonce and `code_verifier` | Built-in; no library needed |
| `java.util.concurrent.ConcurrentHashMap` | JDK | In-process state store for OIDC flow parameters | Sufficient for single-coordinator Dremio OSS; no external session store needed |
| `com.dremio.service.keycloak.JitUserProvisioner` | Phase 32 | Auto-create user on first SSO login | Already exists; same provisioning path as REST/Flight |
| `com.dremio.service.keycloak.KeycloakRoleSyncer` | Phase 32 | Sync realm_access.roles on login | Already exists; same sync path as REST/Flight |
| `com.dremio.service.tokens.TokenManager` | project | Mint Dremio session token after successful OIDC exchange | Already injected in `DACDaemonModule` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `ConcurrentHashMap` for state store | KVStore (RocksDB-backed) | KVStore would survive coordinator restart; ConcurrentHashMap loses pending SSO sessions on restart. Acceptable for Phase 33 — OIDC flows complete in < 5 minutes; restart during a flow is an edge case. KVStore is for Phase 35+ if needed. |
| URL fragment `#token=…` for token delivery | Query parameter `?token=…` in redirect | URL fragment is not sent to the server (HTTP spec), so the token never appears in Keycloak logs or server logs. Query params appear in server logs. Fragment is the correct choice for browser-based token delivery. |
| `oauth2-oidc-sdk` `AuthorizationRequest.Builder` | Manual `UriBuilder` string concatenation | Manual builds miss encoding edge cases; sdk handles proper percent-encoding of all parameters including PKCE challenge. |

**Installation:**
```xml
<!-- Add to services/keycloak/pom.xml dependencies -->
<dependency>
  <groupId>com.nimbusds</groupId>
  <artifactId>oauth2-oidc-sdk</artifactId>
</dependency>
```

---

## Architecture Patterns

### Recommended Project Structure

```
services/keycloak/src/main/java/com/dremio/service/keycloak/
├── OidcStateStore.java          # NEW: stores (state → {codeVerifier, expiry}) in ConcurrentHashMap
├── OidcSessionStore.java        # NEW: stores (dremioToken → idTokenString) for LOUT-02
│                                  (existing files: OidcTokenValidator, KeycloakConfig, JitUserProvisioner, KeycloakRoleSyncer)

dac/backend/src/main/java/com/dremio/dac/resource/
├── OidcResource.java            # NEW: GET /apiv2/oidc/login + GET /apiv2/oidc/callback
│                                  (existing: LogInLogOutResource.java — referenced for pattern)

services/keycloak/src/test/java/com/dremio/service/keycloak/
├── TestOidcStateStore.java      # NEW: unit: expiry, concurrency, missing-state
├── TestOidcSessionStore.java    # NEW: unit: put/get/remove

dac/backend/src/test/java/com/dremio/dac/resource/
├── TestOidcResource.java        # NEW: unit (Mockito): login redirect params, callback happy path,
│                                  tampered state → 400, state expiry → 400
```

### Pattern 1: Unauthenticated REST Endpoint (No @Secured)

**What:** Resources that do not carry `@Secured` at class or method level bypass `DACAuthFilter` (which only processes requests matched by `@Secured`). The login and callback endpoints are browser-facing and must not require an existing Dremio session.

**When to use:** Any endpoint that receives unauthenticated browser requests.

**Example:**
```java
// Source: LogInLogOutResource.java — @POST login is not @Secured (only @GET isUserAuthorized is)
// Pattern: @RestResource + @Path without @Secured on the method
@RestResource
@Path("/oidc")
@Produces(MediaType.TEXT_PLAIN)
public class OidcResource {

  @GET
  @Path("/login")
  // NO @Secured — unauthenticated browser call
  public Response login() { ... }

  @GET
  @Path("/callback")
  // NO @Secured — unauthenticated browser call
  public Response callback(@QueryParam("code") String code,
                           @QueryParam("state") String state) { ... }
}
```

### Pattern 2: OIDC Authorization URL Construction with Nimbus

**What:** Use `AuthorizationRequest.Builder` from `oauth2-oidc-sdk` to build the Keycloak authorization URL with all required PKCE and state parameters.

**When to use:** In `OidcResource.login()`.

**Example:**
```java
// Source: Nimbus oauth2-oidc-sdk 11.20 — AuthorizationRequest, CodeChallenge, CodeVerifier
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;

CodeVerifier codeVerifier = new CodeVerifier(); // generates secure random verifier
CodeChallenge codeChallenge = CodeChallenge.compute(CodeChallengeMethod.S256, codeVerifier);
State state = new State();  // generates secure random state nonce

URI authorizationEndpoint = URI.create(
    keycloakConfig.getIssuerUrl() + "/protocol/openid-connect/auth");

AuthorizationRequest authRequest = new AuthorizationRequest.Builder(
        new ResponseType(ResponseType.Value.CODE),
        new ClientID(keycloakConfig.getClientId()))
    .endpointURI(authorizationEndpoint)
    .redirectionURI(URI.create(keycloakConfig.getRedirectUri()))
    .scope(new Scope("openid", "profile", "email"))
    .state(state)
    .codeChallenge(codeVerifier, CodeChallengeMethod.S256)
    .build();

URI authorizationUri = authRequest.toURI();
oidcStateStore.put(state.getValue(), codeVerifier.getValue());
return Response.temporaryRedirect(authorizationUri).build();
```

### Pattern 3: Authorization Code Exchange with Nimbus

**What:** POST to Keycloak token endpoint using Nimbus `TokenRequest` with `AuthorizationCodeGrant`.

**When to use:** In `OidcResource.callback()` after validating `state`.

**Example:**
```java
// Source: Nimbus oauth2-oidc-sdk 11.20 — TokenRequest, AuthorizationCodeGrant, OIDCTokenResponseParser
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;

URI tokenEndpoint = URI.create(
    keycloakConfig.getIssuerUrl() + "/protocol/openid-connect/token");

TokenRequest tokenRequest = new TokenRequest(
    tokenEndpoint,
    new ClientSecretBasic(
        new ClientID(keycloakConfig.getClientId()),
        new Secret(keycloakConfig.getClientSecret())),
    new AuthorizationCodeGrant(
        new AuthorizationCode(code),
        URI.create(keycloakConfig.getRedirectUri()),
        new CodeVerifier(storedVerifier)));

com.nimbusds.oauth2.sdk.http.HTTPResponse httpResponse =
    tokenRequest.toHTTPRequest().send();
OIDCTokenResponse tokenResponse =
    (OIDCTokenResponse) OIDCTokenResponseParser.parse(httpResponse);

if (!tokenResponse.indicatesSuccess()) {
  return Response.status(Response.Status.BAD_GATEWAY)
      .entity("Token exchange failed").build();
}

String accessTokenStr = tokenResponse.getOIDCTokens()
    .getAccessToken().getValue();
String idTokenStr = tokenResponse.getOIDCTokens()
    .getIDToken().serialize();
```

### Pattern 4: OidcStateStore — In-Process Expiring State

**What:** A `ConcurrentHashMap`-backed store that holds pending OIDC flow parameters. Entries expire after 5 minutes (OIDC flows that do not complete within 5 minutes are abandoned).

**When to use:** Created in `services/keycloak`, bound as singleton in `DACDaemonModule` keycloak branch.

**Example:**
```java
// Source: project pattern (similar to TokenManagerImpl.generator using SecureRandom)
public class OidcStateStore {
  // Entry: state nonce -> (codeVerifier, expiresAtEpochMs)
  private final ConcurrentHashMap<String, PendingFlow> pending = new ConcurrentHashMap<>();

  // 5-minute TTL — OIDC flows must complete within this window
  private static final long TTL_MS = 5 * 60 * 1000L;

  public void put(String state, String codeVerifier) {
    pending.put(state,
        new PendingFlow(codeVerifier, System.currentTimeMillis() + TTL_MS));
    // lazy cleanup: remove expired entries opportunistically
    pending.entrySet().removeIf(e -> e.getValue().isExpired());
  }

  // Returns codeVerifier if state is valid and not expired; null otherwise.
  // Removes the entry (one-time use).
  public String removeIfValid(String state) {
    PendingFlow entry = pending.remove(state);
    if (entry == null || entry.isExpired()) return null;
    return entry.codeVerifier();
  }
}
```

### Pattern 5: OidcSessionStore — id_token_hint Storage for LOUT-02

**What:** A `ConcurrentHashMap` keyed by Dremio session token value, storing the Keycloak `id_token` string for use during RP-Initiated Logout (Phase 35).

**When to use:** Created in `services/keycloak`, bound as singleton in `DACDaemonModule` keycloak branch.

```java
public class OidcSessionStore {
  private final ConcurrentHashMap<String, String> idTokensByDremioToken =
      new ConcurrentHashMap<>();

  public void put(String dremioToken, String idToken) {
    idTokensByDremioToken.put(dremioToken, idToken);
  }

  public String get(String dremioToken) {
    return idTokensByDremioToken.get(dremioToken);
  }

  public void remove(String dremioToken) {
    idTokensByDremioToken.remove(dremioToken);
  }
}
```

### Pattern 6: Token Delivery to Browser via URL Fragment

**What:** After minting the Dremio session token, redirect to `/login/sso/landing#token=<dremio_token>`. The `#` fragment is not sent to the server; the browser's JavaScript (Phase 34 `SSOLandingPage`) reads `window.location.hash`, stores the token in localStorage (via `localStorageUtils.setUserData()`), then navigates away.

**When to use:** Always for browser-based SSO token delivery. Never use a query parameter (it appears in server logs).

```java
// In OidcResource.callback() after minting Dremio token:
URI landingUri = URI.create("/login/sso/landing#token=" + dremioToken);
return Response.temporaryRedirect(landingUri).build();
```

**Note:** `TokenDetails.token` from `tokenManager.createToken()` is the opaque token string that the UI stores. The UI sends it as `Authorization: _dremio<token>` (per `localStorageUtils.getAuthToken()` which prepends `_dremio`).

### Anti-Patterns to Avoid

- **Putting @Secured on OidcResource endpoints:** Login/callback are pre-authentication — adding @Secured causes DACAuthFilter to return 401 before the handler runs.
- **Storing code_verifier in the browser (cookie or URL parameter):** The verifier must stay server-side. Sending it to the browser defeats PKCE's purpose.
- **Using `Response.ok()` with a `Location` header instead of `Response.temporaryRedirect()`:** JAX-RS `temporaryRedirect()` correctly sets 302 + `Location` header; do not construct the response manually.
- **Calling `tokenEndpoint.send()` in a JAX-RS filter thread:** `TokenRequest.toHTTPRequest().send()` is a blocking HTTP call. This is acceptable in a resource method (not a filter); JAX-RS resources are not on the critical filter path.
- **Leaking state entries on failed callbacks:** `removeIfValid()` removes the entry atomically; once consumed the state can never be replayed (prevents code injection attacks).
- **Not validating state before using code:** Always check `removeIfValid(state) != null` before calling the token endpoint.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Authorization URL construction | String concat with `+` | `AuthorizationRequest.Builder` from oauth2-oidc-sdk | Handles percent-encoding of all params including challenge |
| PKCE verifier/challenge generation | `SecureRandom.nextBytes()` + custom Base64URL | `CodeVerifier()` (auto-generates) + `CodeChallenge.compute(verifier, S256)` | Spec-compliant padding-free BASE64URL and SHA-256 hash built in |
| Token endpoint HTTP POST | `HttpURLConnection` + form encoding | `TokenRequest.toHTTPRequest().send()` | Form-encodes grant, handles client auth, returns typed `HTTPResponse` |
| OIDC token response parsing | JSON field extraction by string key | `OIDCTokenResponseParser.parse(httpResponse)` | Handles error responses, returns typed `OIDCTokenResponse` |
| State nonce generation | `UUID.randomUUID().toString()` | `new State()` from oauth2-oidc-sdk | Generates 256-bit SecureRandom value, URL-safe |

**Key insight:** The oauth2-oidc-sdk was included in the Dremio BOM for exactly this use case. The entire OIDC flow can be built with its builders without any custom HTTP or encoding code.

---

## Common Pitfalls

### Pitfall 1: State Expiry Window Too Short / Not Enforced

**What goes wrong:** If the state store has no TTL, abandoned flows accumulate forever (memory leak). If the TTL is too short (< 5 minutes), slow users who spend time on the Keycloak login page get a 400 at callback.

**Why it happens:** Browser SSO flows can take 1–3 minutes (password input, MFA, consent).

**How to avoid:** Use 5-minute TTL. Lazy cleanup on `put()` (remove expired entries) avoids a background cleanup thread. Do NOT use 30 seconds.

**Warning signs:** Users report "Login failed" when the Keycloak page takes more than N seconds.

### Pitfall 2: State Parameter Case Sensitivity / URL Encoding

**What goes wrong:** Keycloak echoes back the `state` parameter but may URL-encode or percent-encode it differently than how it was sent. Using raw string comparison can cause false mismatches.

**Why it happens:** URL encoding differences between Keycloak versions.

**How to avoid:** Use `State.getValue()` for storage key and decode the incoming `state` query param before lookup (`URLDecoder.decode(state, UTF_8)`). The Nimbus `State` class produces URL-safe values that do not need encoding, so round-trip is safe.

**Warning signs:** `state` lookup returns null for a perfectly valid flow.

### Pitfall 3: Nimbus TokenRequest.toHTTPRequest().send() Throws on Non-2xx

**What goes wrong:** `OIDCTokenResponseParser.parse()` does NOT throw on error responses — it returns an error-type response. If you call `.getOIDCTokens()` directly without checking `indicatesSuccess()`, you get a `ClassCastException`.

**Why it happens:** The parser returns a `TokenErrorResponse` instead of `OIDCTokenResponse` on error.

**How to avoid:** Always check `tokenResponse.indicatesSuccess()` before casting. Return HTTP 502 (Bad Gateway) to the browser if Keycloak's token endpoint returns an error.

**Warning signs:** `ClassCastException` in callback handler logged as 500.

### Pitfall 4: id_token vs access_token in OidcSessionStore

**What goes wrong:** Storing the `access_token` instead of `id_token` in `OidcSessionStore`. Phase 35 RP-Initiated Logout requires the `id_token` as `id_token_hint` per the OIDC spec.

**Why it happens:** `OIDCTokens.getAccessToken()` vs `OIDCTokens.getIDToken()` are easy to confuse.

**How to avoid:** Always use `tokenResponse.getOIDCTokens().getIDToken().serialize()` for `OidcSessionStore`. Use `getAccessToken().getValue()` only for the `OidcTokenValidator.validateWithClaims()` call.

**Warning signs:** Phase 35 logout fails with Keycloak 400 "invalid id_token_hint".

### Pitfall 5: OidcResource Constructor Injection vs @Inject Field Injection

**What goes wrong:** `OidcResource` uses constructor injection (like `LogInLogOutResource`) but `OidcStateStore` and `OidcSessionStore` are bound as singletons only in the keycloak auth branch. If they are constructor-injected without `@Nullable`, the Jersey runtime fails to instantiate the resource in non-keycloak mode.

**Why it happens:** Jersey instantiates all `@RestResource` classes regardless of auth mode.

**How to avoid:** Either (a) use `@Inject @Nullable` field injection for the keycloak-specific dependencies (consistent with `DACAuthFilter`), OR (b) guard the resource class registration in `RestServerV2.init()` behind an auth-type check. The simpler approach is `@Inject @Nullable` and returning 503 if keycloak is not configured.

**Warning signs:** `500 Internal Server Error` on `GET /apiv2/oidc/login` in internal auth mode.

### Pitfall 6: URL Fragment Not Supported by Response.temporaryRedirect()

**What goes wrong:** `java.net.URI` constructor throws `URISyntaxException` if the fragment contains characters not allowed in a URI fragment (e.g., `+` or `=` padding in Base64 tokens).

**Why it happens:** Dremio tokens are opaque random hex strings (no Base64 padding), so this is safe. Dremio JWT tokens start with `eyJ` (URL-safe). Fragment delivery is fine for both token types.

**How to avoid:** Construct the landing URI as `URI.create("/login/sso/landing#token=" + dremioToken)` — `URI.create()` does not validate fragment character safety beyond # separation. The token value from `TokenDetails.token` is a safe alphanumeric string from `SecureRandom`.

**Warning signs:** `IllegalArgumentException` in `URI.create()`.

---

## Code Examples

Verified patterns from official sources and project code:

### Login Endpoint Skeleton
```java
// Source: Nimbus AuthorizationRequest.Builder; JAX-RS Response.temporaryRedirect()
@GET
@Path("/login")
public Response initiateLogin() {
  if (oidcStateStore == null) {
    // Keycloak not configured — return 503
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .entity("Keycloak auth not enabled").build();
  }

  CodeVerifier codeVerifier = new CodeVerifier();
  State state = new State();

  URI authEndpoint = URI.create(
      keycloakConfig.getIssuerUrl() + "/protocol/openid-connect/auth");

  AuthorizationRequest authRequest = new AuthorizationRequest.Builder(
          new ResponseType(ResponseType.Value.CODE),
          new ClientID(keycloakConfig.getClientId()))
      .endpointURI(authEndpoint)
      .redirectionURI(URI.create(keycloakConfig.getRedirectUri()))
      .scope(new Scope("openid", "profile", "email"))
      .state(state)
      .codeChallenge(codeVerifier, CodeChallengeMethod.S256)
      .build();

  oidcStateStore.put(state.getValue(), codeVerifier.getValue());
  return Response.temporaryRedirect(authRequest.toURI()).build();
}
```

### Callback Endpoint Skeleton
```java
// Source: Nimbus TokenRequest; OIDCTokenResponseParser; project TokenManager.createToken()
@GET
@Path("/callback")
public Response handleCallback(
    @QueryParam("code") String code,
    @QueryParam("state") String state,
    @Context HttpServletRequest request) {

  if (state == null || code == null) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity("Missing code or state").build();
  }

  String storedVerifier = oidcStateStore.removeIfValid(state);
  if (storedVerifier == null) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity("Invalid or expired state").build();
  }

  // Exchange code for tokens
  URI tokenEndpoint = URI.create(
      keycloakConfig.getIssuerUrl() + "/protocol/openid-connect/token");
  TokenRequest tokenRequest = new TokenRequest(
      tokenEndpoint,
      new ClientSecretBasic(
          new ClientID(keycloakConfig.getClientId()),
          new Secret(keycloakConfig.getClientSecret())),
      new AuthorizationCodeGrant(
          new AuthorizationCode(code),
          URI.create(keycloakConfig.getRedirectUri()),
          new CodeVerifier(storedVerifier)));

  OIDCTokenResponse tokenResponse;
  try {
    tokenResponse = (OIDCTokenResponse)
        OIDCTokenResponseParser.parse(tokenRequest.toHTTPRequest().send());
  } catch (Exception e) {
    logger.warn("Token exchange HTTP error: {}", e.getMessage());
    return Response.status(Response.Status.BAD_GATEWAY).build();
  }

  if (!tokenResponse.indicatesSuccess()) {
    logger.warn("Keycloak token endpoint returned error: {}",
        tokenResponse.toErrorResponse().getErrorObject().getDescription());
    return Response.status(Response.Status.BAD_GATEWAY).build();
  }

  String accessToken = tokenResponse.getOIDCTokens().getAccessToken().getValue();
  String idToken = tokenResponse.getOIDCTokens().getIDToken().serialize();

  // Validate access token, get user info for JIT provisioning
  KeycloakTokenDetails ktd;
  try {
    ktd = oidcTokenValidator.validateWithClaims(accessToken);
  } catch (Exception e) {
    logger.warn("Access token validation failed after exchange: {}", e.getMessage());
    return Response.status(Response.Status.UNAUTHORIZED).build();
  }

  // JIT provision + role sync (reuse Phase 32 classes)
  try {
    jitProvisioner.provision(ktd.getUsername(), ktd.getEmail());
  } catch (IOException e) {
    // log and continue — user may already exist
  }
  roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles());

  // Mint Dremio session token
  TokenDetails dremioToken = tokenManager.createToken(
      ktd.getUsername(), request.getRemoteAddr());

  // Store id_token for Phase 35 RP-Initiated Logout (LOUT-02)
  oidcSessionStore.put(dremioToken.token, idToken);

  // Redirect browser to SSO landing page with token in fragment
  URI landingUri = URI.create("/login/sso/landing#token=" + dremioToken.token);
  return Response.temporaryRedirect(landingUri).build();
}
```

### DACDaemonModule Wiring Addition
```java
// Source: DACDaemonModule.java setupUserService() — keycloak branch (existing)
// Add after existing KeycloakRoleSyncer binding:
OidcStateStore oidcStateStore = new OidcStateStore();
registry.bind(OidcStateStore.class, oidcStateStore);

OidcSessionStore oidcSessionStore = new OidcSessionStore();
registry.bind(OidcSessionStore.class, oidcSessionStore);
// OidcResource is auto-registered by @RestResource scan in RestServerV2;
// its @Inject fields are satisfied by the above bindings.
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Custom PKCE implementation | Nimbus `CodeVerifier` + `CodeChallenge.compute()` | oauth2-oidc-sdk added to BOM | No custom crypto code needed |
| Session state in browser cookie | In-process `ConcurrentHashMap` with TTL | Phase 33 design decision | Simpler; safe for single-coordinator OSS deployment |
| Token passed as query param | Token in URL fragment `#token=…` | Phase 33 design decision | Token not logged server-side; standard OAuth2 implicit-mode convention |

**Deprecated/outdated:**
- OIDC implicit flow: Removed in OAuth 2.1; use Authorization Code + PKCE. Keycloak still supports implicit mode but it should not be used.
- `response_type=token` (implicit): Never use; use `response_type=code`.

---

## Open Questions

1. **`/apiv2` vs `/api/v3` path prefix for OidcResource**
   - What we know: `@RestResource` maps to `/apiv2`; `@APIResource` maps to `/api/v3`. The phase success criterion says `GET /api/v3/oidc/login` and `GET /api/v3/oidc/callback`. Existing project code (LogInLogOutResource) uses `/apiv2/login`.
   - What's unclear: The success criterion specifies `/api/v3/oidc/*` but the login endpoint uses `/apiv2/login`. The OIDC resource is browser-facing (not a public programmatic API) — either prefix works.
   - Recommendation: Use `@APIResource` (maps to `/api/v3`) to match the success criterion exactly. Register in `APIServer` via the scan, same as `RbacResource`. This is also more consistent with new endpoint additions — `/apiv2` is the legacy path.

2. **KeycloakConfig.getTokenEndpointUri() — construct or discover via OIDC discovery**
   - What we know: The token endpoint for Keycloak is always `{issuerUrl}/protocol/openid-connect/token` and the auth endpoint is `{issuerUrl}/protocol/openid-connect/auth`. These are Keycloak-specific well-known paths.
   - What's unclear: Whether to fetch OIDC discovery document to get the endpoints (more portable) or hardcode the Keycloak path (simpler).
   - Recommendation: Hardcode the Keycloak paths in `KeycloakConfig` (same pattern as `getJwksUri()` already does). Add `getTokenEndpointUri()` and `getAuthorizationEndpointUri()` getters. Discovery is a future CFG-04 enhancement.

3. **Homespace creation in callback — call `CatalogServiceHelper.ensureUserHasHomespace()`?**
   - What we know: `LogInLogOutResource.login()` calls `CatalogServiceHelper.ensureUserHasHomespace()` after authenticating. Phase 32 (JIT in DACAuthFilter) does not create a homespace.
   - What's unclear: Should the OIDC callback create the homespace? Or is this deferred to the first authenticated REST API call that triggers DACAuthFilter?
   - Recommendation: Mirror the `LogInLogOutResource.login()` behavior: call `ensureUserHasHomespace()` in the callback after `jitProvisioner.provision()`. This ensures the home directory exists before the UI renders. Log failures but do not abort the login flow.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit Jupiter 5 + AssertJ + Mockito (same as services/keycloak) |
| Config file | `services/keycloak/pom.xml` — junit-jupiter-api + junit-jupiter-engine already declared |
| Quick run command | `mvn test -pl services/keycloak -Dtest="TestOidcStateStore,TestOidcSessionStore" -q` |
| Full suite command | `mvn test -pl services/keycloak,dac/backend -q` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| OIDC-01 | `GET /api/v3/oidc/login` returns 302 with `state`, `code_challenge`, `code_challenge_method=S256` in redirect Location | unit | `mvn test -pl dac/backend -Dtest="TestOidcResource#login_*" -q` | Wave 0 |
| OIDC-02 | Callback exchanges code, provisions user, mints Dremio token, redirects to `/login/sso/landing#token=...` | unit | `mvn test -pl dac/backend -Dtest="TestOidcResource#callback_*" -q` | Wave 0 |
| OIDC-03 | Tampered `state` → 400; expired `state` → 400; missing `state` → 400 | unit | `mvn test -pl dac/backend -Dtest="TestOidcResource#callback_tamperedState_returns400,TestOidcResource#callback_missingState_returns400" -q` | Wave 0 |
| LOUT-02 | `id_token` stored in `OidcSessionStore` keyed by Dremio token after successful callback | unit | `mvn test -pl dac/backend -Dtest="TestOidcResource#callback_storesIdToken"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -pl services/keycloak -q`
- **Per wave merge:** `mvn test -pl services/keycloak,dac/backend -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcStateStore.java` — covers OIDC-03 state validation logic
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcSessionStore.java` — covers LOUT-02 store semantics
- [ ] `dac/backend/src/test/java/com/dremio/dac/resource/TestOidcResource.java` — covers OIDC-01, OIDC-02, OIDC-03, LOUT-02 at resource layer (Mockito for Nimbus/TokenManager)

---

## Sources

### Primary (HIGH confidence)
- Project source: `services/keycloak/src/main/java/com/dremio/service/keycloak/` — all Phase 30–32 classes verified by reading
- Project source: `dac/backend/src/main/java/com/dremio/dac/resource/LogInLogOutResource.java` — unauthenticated login pattern
- Project source: `dac/backend/src/main/java/com/dremio/dac/server/APIServer.java` — `/api/v3` resource registration via `@APIResource` scan
- Project source: `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` lines 2220–2254 — keycloak branch DI wiring pattern
- Project source: `dac/ui/src/sagas/loginLogout.js` — `SSO_LANDING_PATH = "/login/sso/landing"` confirmed
- Project source: `dac/ui/src/utils/storageUtils/localStorageUtils.js` — `setUserData()` + `getAuthToken()` → `_dremio` prefix confirmed
- JAR inspection: `/home/emanuele/.m2/repository/com/nimbusds/oauth2-oidc-sdk/11.20/oauth2-oidc-sdk-11.20.jar` — confirmed classes: `AuthorizationRequest`, `CodeChallenge`, `CodeVerifier`, `TokenRequest`, `AuthorizationCodeGrant`, `OIDCTokenResponseParser`, `OIDCTokenResponse`
- Root BOM: `pom.xml` line 2574 — `oauth2-oidc-sdk` 11.20 declared; `nimbus-jose-jwt` 9.41 declared

### Secondary (MEDIUM confidence)
- Nimbus oauth2-oidc-sdk API derived from JAR class inspection (11.20); patterns consistent with library version in use

### Tertiary (LOW confidence)
- None — all critical findings verified against project source or JAR

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — BOM versions confirmed, JAR class inventory verified
- Architecture patterns: HIGH — derived directly from existing project code (DACDaemonModule, LogInLogOutResource, APIServer, existing Keycloak classes)
- Pitfalls: HIGH — derived from Nimbus SDK behavior (verified by class inspection) and project conventions (filter patterns, exception handling)

**Research date:** 2026-03-12
**Valid until:** 2026-04-12 (Nimbus stable; Keycloak OIDC endpoints are spec-stable)
