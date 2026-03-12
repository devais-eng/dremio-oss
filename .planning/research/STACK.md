# Technology Stack — v1.5 Keycloak IdP Integration

**Project:** Dremio OSS — Keycloak OIDC Authentication
**Researched:** 2026-03-12
**Confidence:** HIGH (based on direct codebase inspection + verified external sources)

---

## Scope

This document covers ONLY what is new or changed for v1.5. Prior milestones (v1.0–v1.4) established:
KVStore RBAC, Jersey/JAX-RS REST layer, DACAuthFilter token pipeline, Flight auth2 bearer-token
flow, Protobuf serialization. Do not re-research or re-implement any of that.

The new work is:
1. Validate externally-issued Keycloak JWTs in the REST/Flight auth paths
2. Initiate the OIDC authorization code flow from the backend (redirect + callback)
3. JIT-provision Dremio users on first Keycloak login
4. Map Keycloak realm roles to Dremio RBAC roles
5. Surface an "SSO" button in the existing React login form

---

## Existing Auth Architecture (verified by inspection)

Understanding these integration points is required before adding anything.

### REST path: `DACAuthFilter` (primary entry point)

`DACAuthFilter.java` (`dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java`):
- Runs at `Priorities.AUTHENTICATION` for all `@Secured` endpoints
- Extracts the Bearer token from `Authorization` header via `TokenUtils.getAuthHeaderToken()`
- Calls `tokenManager.validateToken(token)` — this is the **single choke point** for REST auth
- Constructs `DACSecurityContext` from the returned `TokenDetails` (which carries the username)
- The validated username drives `UserService.getUser()` — the user must exist in Dremio's internal store

### Token management: `TokenManager` interface

`TokenManager.java` (`services/tokens/src/main/java/com/dremio/service/tokens/TokenManager.java`):
- `validateToken(String token)` returns `TokenDetails` or throws `IllegalArgumentException`
- `createToken(String username, String clientAddress)` mints a Dremio-internal opaque session token
- `createJwt(String username, String clientAddress)` mints a Dremio-internal ES256-signed JWT
- The current `JWTValidatorImpl` validates tokens signed by **Dremio's own key** — it checks `issuer` = Dremio's own server URL and `audience` = Dremio's cluster ID. It resolves the subject as a Dremio UID via `UserResolver.getUser(new UID(sub))`. **This will reject Keycloak JWTs outright** because issuer/audience don't match.

### JWT library already in the build

`nimbus-jose-jwt` **9.41** is declared in the root `pom.xml` and used by `services/tokens`. It includes:
- `com.nimbusds.jose.jwk.source.RemoteJWKSet` — fetches and caches a JWK set from a URL
- `com.nimbusds.jwt.proc.DefaultJWTProcessor` — validates a JWT using a configurable key selector
- `com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier` — validates `iss`, `aud`, `exp`, `nbf`, `iat`

`oauth2-oidc-sdk` **11.20** is also declared in the root `pom.xml` (dependency management only —
not yet pulled into any module). It provides:
- `OIDCProviderMetadata.resolve(Issuer)` — discovers JWKS URI and other endpoints from `/.well-known/openid-configuration`
- `AuthorizationCodeGrant` / `AuthorizationRequest` — constructs OIDC authorization code flow requests
- `OIDCTokenResponseParser` — parses the token response from Keycloak's token endpoint

### Flight path: `DremioBearerTokenAuthenticator`

`DremioBearerTokenAuthenticator.java` (`services/arrow-flight/src/main/java/com/dremio/service/flight/auth2/`):
- If no bearer token is present: does username/password validation via `DremioCredentialValidator`
- If a bearer token is present: calls `tokenManager.validateToken(bearerToken)` — same single choke point
- Adding Keycloak JWT support here means the same `tokenManager.validateToken()` extension covers Flight automatically

### Login endpoint: `LogInLogOutResource`

`LogInLogOutResource.java` (`dac/backend/src/main/java/com/dremio/dac/resource/`):
- `POST /login` — accepts `{userName, password}`, calls `userService.authenticate()`, mints a token, returns `UserLoginSession`
- For OIDC: a new redirect endpoint (`GET /login/sso`) and callback endpoint (`GET /login/sso/callback`) must be added, OR the existing `/login` endpoint must be extended with an SSO initiation path
- The returned `UserLoginSession` structure (token, userName, admin, permissions) is the contract the frontend localStorage depends on — the OIDC callback must produce the same shape

### Auth type selection: `DACConfig.isInternalUserAuth()`

`DACConfig.java` reads `services.coordinator.web.auth.type` (constant `WEB_AUTH_TYPE`). Currently accepted values: `"internal"` only — anything else throws `RuntimeException` in `DACDaemonModule`. Adding `"oidc"` (or `"keycloak"`) as a new accepted type is the config-gating mechanism.

### UserService and JIT provisioning

`UserService.createUser(User userConfig, String authKey)` creates a user with a password. For JIT provisioning, a sentinel password (UUID or locked hash) is acceptable — the user will never authenticate with it directly. The `SimpleUserService.createUser()` at KVStore level writes to RocksDB. This is the right call site for JIT.

### Authenticator / AuthProvider (pluggable, not yet used by v1.4)

`AuthProvider.java` (`services/authenticator/`) defines `isSupported(tokenType)` and `validate(AuthRequest)`. `DremioAccessTokenAuthProvider` already implements it for Dremio's opaque access token. A `KeycloakJwtAuthProvider implements AuthProvider` would fit here cleanly, but note that `DACAuthFilter` does NOT currently go through this chain — it calls `TokenManager.validateToken()` directly. Either route `DACAuthFilter` through `Authenticator`, or add a Keycloak-aware branch directly in `DACAuthFilter`/`TokenManager`.

---

## Recommended Stack for v1.5

### Core Framework (unchanged)

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Java | 21 (build), 17 (runtime) | All new backend code | Existing project constraint |
| Jersey / JAX-RS | existing | New OIDC endpoint resources | Consistent with all existing REST resources |
| Guice / `SingletonRegistry` | existing | DI wiring for new services | Existing DI mechanism in Dremio |
| KVStore (RocksDB) | existing | No new stores needed | User records already in KVStore via `SimpleUserService` |

### JWT and OIDC Libraries (Java backend)

| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| `com.nimbusds:nimbus-jose-jwt` | **9.41** (already declared) | Validate Keycloak-issued JWTs using remote JWKS | Already in the build. `RemoteJWKSet` + `DefaultJWTProcessor` + `JWKSetBasedJWSKeySelector` handle RS256/ES256 from Keycloak's JWKS endpoint. Zero new dependency. |
| `com.nimbusds:oauth2-oidc-sdk` | **11.20** (already declared in depMgmt) | OIDC Discovery + authorization code flow + token exchange | Already version-managed. `OIDCProviderMetadata.resolve(Issuer)` fetches `/.well-known/openid-configuration` to get the JWKS URI and token endpoint. Avoids hardcoding Keycloak URLs. |

**Both libraries are ALREADY version-managed in the root pom.xml.** The only change needed is
adding `oauth2-oidc-sdk` as a compile dependency to the `dac/backend` module's `pom.xml` (it is
currently only in dependency management, not pulled as an actual dependency anywhere relevant).

`nimbus-jose-jwt` is already a compile dependency of `services/tokens`. The new OIDC validation
code that lives in `dac/backend` or a new `services/oidc` module can depend on `services/tokens`
(which already pulls it in transitively) or declare it directly.

### What NOT to add (Java)

| Library | Why Not |
|---------|---------|
| `keycloak-adapter-core` / `keycloak-spring-security-adapter` | These are Keycloak-specific Spring Security adapters. Dremio uses Jersey, not Spring. They pull in Spring context as a transitive dependency — massive classpath conflict. Nimbus does the same job without vendor lock-in. |
| `io.jsonwebtoken:jjwt-*` | Dremio already uses Nimbus for JWT. Adding JJWT would duplicate the JWT stack and risk version conflicts. Nimbus is the JOSE industry standard for Java and is already in the codebase. |
| `spring-security-oauth2-resource-server` | Pulls Spring Security as a whole; not compatible with Jersey/Guice setup. |
| `com.auth0:java-jwt` | Third JWT library redundant with existing Nimbus. |
| `pac4j-oidc` | High-level OIDC framework that requires a Pac4J security layer; conflicts with Dremio's `DACAuthFilter`-based approach. |

### Frontend Library (React UI)

| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| No new npm package | — | OIDC redirect from UI | The authorization code flow redirect is a simple browser navigation: `window.location.href = ssoInitUrl`. No JS OIDC library needed when the backend drives the flow. The backend returns an authorization URL at a new REST endpoint; the UI calls it and does `window.location.assign(url)`. |

**Why no `keycloak-js` or `oidc-client-ts`:** The cleanest architecture for this use case is
backend-driven OIDC. The UI has no OIDC state to manage — it sends the user to Keycloak, Keycloak
redirects to a Dremio backend callback URL, the backend exchanges the code for tokens, creates
a Dremio session token, and redirects the browser to the frontend with the Dremio token in a query
param or cookie. The frontend then stores it in localStorage exactly as it does today. This avoids
embedding OAuth client secrets in the browser, avoids PKCE complexity in an older React/Redux
codebase, and requires zero new npm dependencies.

`keycloak-js` is additionally approaching deprecation per the Keycloak maintainers and only works
with Keycloak — not a general OIDC solution. `oidc-client-ts` is the correct choice if a pure-SPA
PKCE flow is ever needed in the future, but for v1.5 it is over-engineering.

---

## Integration Points — What Changes vs. What Stays

### What stays exactly the same

| Component | Reason |
|-----------|--------|
| `DACAuthFilter.getUserNameFromToken()` | The Keycloak validation path can be added as a fallback branch: try Dremio internal token first, then try Keycloak JWT. Existing token validation path untouched. |
| `TokenManager.createToken() / createJwt()` | Dremio still mints its own session tokens after OIDC callback; the frontend always gets a Dremio token. |
| `UserService.getUser()` | Called in `DACAuthFilter` after token validation; JIT provisioning ensures the user always exists before this is called. |
| `DremioBearerTokenAuthenticator.validateBearer()` | Calls `tokenManager.validateToken()` — if the token manager's `validateToken()` method is extended to accept Keycloak JWTs, Flight gets support for free. |
| `UserLoginSession` response shape | OIDC callback endpoint must return the same JSON shape so the frontend `localStorageUtils.setUserData()` works without changes. |
| RBAC KVStore layer | No changes. Role mapping writes role memberships using the existing `rbacService.grantRoleToUser()` API. |

### What changes

| Component | Change | File(s) |
|-----------|--------|---------|
| `DACAuthFilter` or `TokenManager` | Add branch: if token looks like a Keycloak JWT (parse header, check `iss` == configured Keycloak issuer), route to `KeycloakJwtValidator`. Preserve existing Dremio token path as primary. | `DACAuthFilter.java` or new `TokenManagerImpl` subclass |
| `LogInLogOutResource` | Add `GET /login/sso` (returns `{redirectUrl}`) and `GET /login/sso/callback` (handles code exchange, JIT provision, return Dremio session token) | `LogInLogOutResource.java` or new `OidcResource.java` |
| `DACDaemonModule.setupUserService()` | Add `"oidc"` as a valid `WEB_AUTH_TYPE`; bind `KeycloakConfig` and `KeycloakJwtValidator` when enabled | `DACDaemonModule.java` |
| `DremioConfig` | Add new config key constants: `services.coordinator.web.auth.oidc.issuer-url`, `services.coordinator.web.auth.oidc.client-id`, `services.coordinator.web.auth.oidc.client-secret`, `services.coordinator.web.auth.oidc.role-mapping.enabled` | `DremioConfig.java` |
| `dremio-reference.conf` | Add OIDC config block (commented-out defaults) | `common/legacy/src/main/resources/dremio-reference.conf` |
| `dac/backend/pom.xml` | Add `oauth2-oidc-sdk` compile dependency (version inherited from root depMgmt) | `dac/backend/pom.xml` |
| React `LoginForm.jsx` | Add "Login with SSO" button that calls the new `GET /login/sso` endpoint | `LoginForm.jsx` |

### New classes to write

| Class | Location | Responsibility |
|-------|----------|---------------|
| `KeycloakJwtValidator` | `dac/backend` or `services/oidc` | Builds a `DefaultJWTProcessor` backed by `RemoteJWKSet` (from `nimbus-jose-jwt`) pointed at Keycloak's JWKS URI. Validates `iss`, `exp`, `aud` claims. Extracts `preferred_username` claim as the Dremio username. |
| `OidcProviderConfig` | `dac/backend` | Value object loaded from `DremioConfig`: issuer URL, client ID, client secret, redirect URI, role mapping flag. Bootstrapped via `OIDCProviderMetadata.resolve()` from `oauth2-oidc-sdk` to get the JWKS URI and token endpoint. |
| `OidcCallbackHandler` | `dac/backend` | Handles `GET /login/sso/callback?code=...&state=...`. Exchanges code via `oauth2-oidc-sdk` `TokenRequest`. Validates the returned ID token. Calls `JitUserProvisioner`. Mints a Dremio session token via `TokenManager.createToken()`. Redirects browser to frontend with token. |
| `JitUserProvisioner` | `dac/backend` | Checks `UserService.getUser(username)` — if `UserNotFoundException`, calls `UserService.createUser()` with a locked password (UUID, never used for login). Optionally maps Keycloak `realm_access.roles` to Dremio RBAC roles. |

---

## Keycloak JWT Structure (reference)

Keycloak-issued access tokens contain:

```json
{
  "iss": "https://keycloak.example.com/realms/myrealm",
  "sub": "<keycloak-user-uuid>",
  "preferred_username": "alice",
  "email": "alice@example.com",
  "realm_access": {
    "roles": ["analyst", "offline_access", "uma_authorization"]
  },
  "exp": 1740000000,
  "iat": 1739996400
}
```

Key extraction points:
- **Username for Dremio**: `preferred_username` claim (not `sub`, which is a Keycloak UUID)
- **Roles for mapping**: `realm_access.roles` list
- **JWKS URI**: obtained from `<issuer>/.well-known/openid-configuration` → `jwks_uri` field
- **Algorithm**: RS256 by default (Keycloak can be configured for ES256 but RS256 is the standard)

The existing `JWTValidatorImpl` uses `sub` as a Dremio UID — do NOT reuse it for Keycloak tokens.
Write a separate `KeycloakJwtValidator` that reads `preferred_username` instead.

---

## Configuration Schema (new keys to add to `DremioConfig`)

```hocon
services.coordinator.web.auth {
  type: "internal"   # or "oidc" to enable Keycloak

  oidc {
    issuer-url: "https://keycloak.example.com/realms/myrealm"
    client-id: "dremio"
    client-secret: ""          # backend confidential client secret
    role-mapping.enabled: false  # map Keycloak realm roles to Dremio RBAC roles
  }
}
```

`redirect-uri` should be derived at runtime from Dremio's own base URL rather than hardcoded in
config — use `WebServerInfoProvider.getIssuer()` (already exists in the codebase).

---

## Maven Changes Required

### `dac/backend/pom.xml` — add one dependency

```xml
<dependency>
  <groupId>com.nimbusds</groupId>
  <artifactId>oauth2-oidc-sdk</artifactId>
  <!-- version inherited from root depMgmt: 11.20 -->
</dependency>
```

`nimbus-jose-jwt` is already a transitive dependency of `services/tokens` which `dac/backend`
depends on. No version change needed.

### New module `services/oidc` (optional, recommended for isolation)

If the OIDC validation logic grows beyond a few classes, isolating it in a new Maven module
`services/oidc` keeps the `services/tokens` module clean and avoids web-framework dependencies
bleeding into the token layer. The module would depend on `services/tokens` and `services/users`
and declare `oauth2-oidc-sdk` + `nimbus-jose-jwt` explicitly.

This is optional for v1.5 — starting inside `dac/backend` is fine and can be refactored later.

---

## Alternatives Considered

| Decision | Chosen | Alternative | Why Not |
|----------|--------|-------------|---------|
| JWT validation library | Nimbus (already in build) | Auth0 java-jwt, JJWT | Nimbus is already present; adding a second JWT library creates version drift risk. Nimbus is the industry standard for JOSE/JWT in Java (used by Spring Security, Quarkus, Keycloak itself). |
| OIDC code exchange library | `oauth2-oidc-sdk` (already version-managed) | Manual HTTP calls to Keycloak token endpoint | Manual calls require parsing JSON responses and handling errors without type safety. `oauth2-oidc-sdk` is purpose-built for this and is already in the dependency management block — zero additional version to track. |
| Auth flow type | Backend authorization code flow | SPA-side PKCE (oidc-client-ts/keycloak-js) | Backend flow: client secret never leaves server, callback handling is server-side, Dremio always issues its own session token (frontend localStorage unchanged), works for REST + Flight + ODBC/JDBC uniformly. SPA PKCE would only help the browser UI and would require an npm package plus Redux saga changes. |
| Token representation in Dremio | Dremio-internal session token minted after OIDC callback | Pass-through Keycloak access token as the Dremio session token | Pass-through means Keycloak token expiry drives Dremio session expiry, requiring refresh token handling and storing refresh tokens. Minting a Dremio token at callback keeps the session model simple and identical to internal auth. |
| Username extraction | `preferred_username` claim | `sub` (Keycloak UUID) or `email` | `preferred_username` is the human-readable Keycloak username; matches what an admin would type. `sub` is a UUID — doesn't match how Dremio users are identified. `email` is not always unique across realms. |
| JIT provisioning location | `UserService.createUser()` with locked password | New OIDC-specific user store | Using the existing `UserService` means OIDC users are visible in the normal user list, can have RBAC roles assigned to them, and require no schema changes. The locked password is never used since OIDC auth bypasses password check entirely. |

---

## Confidence Assessment

| Area | Level | Reason |
|------|-------|--------|
| Nimbus availability | HIGH | Verified in `services/tokens/pom.xml` and `pom.xml` depMgmt; version 9.41 in use |
| `oauth2-oidc-sdk` availability | HIGH | Verified in root `pom.xml` depMgmt at version 11.20; not yet a compile dependency anywhere relevant |
| `DACAuthFilter` integration point | HIGH | Read source; single `tokenManager.validateToken()` call is the correct place to intercept |
| Flight auth integration | HIGH | `DremioBearerTokenAuthenticator.validateBearer()` calls same `tokenManager.validateToken()` |
| `WEB_AUTH_TYPE` as the config gate | HIGH | `DACDaemonModule` uses it as the only service binding switch |
| JIT provisioning via `UserService.createUser()` | HIGH | Method exists; `authKey` parameter accepts any string (locked password pattern is standard) |
| Keycloak JWT claim structure | HIGH | Standard OIDC + Keycloak documentation, widely verified |
| UI change scope | MEDIUM | `LoginForm.jsx` is clear but the surrounding Redux saga flow for SSO callback needs tracing |
| ODBC/JDBC support | MEDIUM | ODBC/JDBC clients that send a Keycloak JWT as the bearer token will work via the same `tokenManager.validateToken()` path once Keycloak JWT support is added; clients that only do username/password won't work without Keycloak token pre-negotiation (out of scope) |

---

## Sources

- Codebase: `services/tokens/pom.xml` — nimbus-jose-jwt 9.41 verified
- Codebase: root `pom.xml` depMgmt — oauth2-oidc-sdk 11.20 verified
- Codebase: `DACAuthFilter.java`, `TokenManager.java`, `JWTValidatorImpl.java` — auth pipeline verified
- Codebase: `LogInLogOutResource.java` — login endpoint contract verified
- Codebase: `DACDaemonModule.java` L2216-L2222 — `WEB_AUTH_TYPE` binding switch verified
- Codebase: `DremioBearerTokenAuthenticator.java` — Flight auth pipeline verified
- Codebase: `DremioConfig.java` L80 — `WEB_AUTH_TYPE` constant verified
- Codebase: `SimpleUserService.java` — `createUser()` signature verified
- [Nimbus JWT access token validation](https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens) — `RemoteJWKSet` + `JWKSourceBuilder` pattern (MEDIUM confidence, official docs)
- [Keycloak OIDC layers](https://www.keycloak.org/securing-apps/oidc-layers) — Keycloak JWKS endpoint format, token claims structure (HIGH confidence, official docs)
- [Keycloak JWT verification](https://skycloak.io/blog/how-to-verify-a-keycloak-issued-access-token-on-the-backend/) — `realm_access.roles` claim format (MEDIUM confidence, community)
- [oauth2-oidc-sdk Maven Central](https://central.sonatype.com/artifact/com.nimbusds/oauth2-oidc-sdk) — version 11.20 confirmed as stable (HIGH confidence)
- [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter) — deprecation trajectory for keycloak-js confirmed (MEDIUM confidence, official docs)

---

*Stack research for: Dremio OSS v1.5 Keycloak IdP Integration*
*Researched: 2026-03-12*
