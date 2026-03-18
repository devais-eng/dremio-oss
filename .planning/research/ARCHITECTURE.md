# Architecture Research

**Domain:** Keycloak OIDC Integration into Dremio OSS (v1.5)
**Researched:** 2026-03-12
**Confidence:** HIGH — derived entirely from reading actual production source code

---

## Standard Architecture

### System Overview: Current Auth Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENTS                                     │
│  ┌──────────────┐  ┌───────────────┐  ┌─────────────────────────┐   │
│  │   Web UI     │  │  REST API     │  │  JDBC/ODBC              │   │
│  │ (React/Redux)│  │  (curl/SDK)   │  │  (Arrow Flight SQL)     │   │
│  └──────┬───────┘  └───────┬───────┘  └────────────┬────────────┘   │
└─────────┼──────────────────┼───────────────────────┼────────────────┘
          │                  │                        │
          │ POST /api/v2/login│ _dremio{token} or      │ username+password
          │ ← opaque token   │ Bearer {opaque}         │ OR Bearer {opaque}
          ▼                  ▼                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    COORDINATOR NODE                                  │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                  WebServer (Jetty)                             │  │
│  │  ┌──────────────────────────────────────────────────────────┐  │  │
│  │  │               Jersey/JAX-RS Layer                        │  │  │
│  │  │  ┌─────────────────────────────────────────────────────┐ │  │  │
│  │  │  │    DACAuthFilter  (@Secured endpoints only)         │ │  │  │
│  │  │  │  TokenUtils.getAuthHeaderToken(ctx)                 │ │  │  │
│  │  │  │  tokenManager.validateToken(token)                  │ │  │  │
│  │  │  │  userService.getUser(userName)                      │ │  │  │
│  │  │  │  → DACSecurityContext(userName, user, ctx, ...)     │ │  │  │
│  │  │  └─────────────────────────────────────────────────────┘ │  │  │
│  │  │  ┌──────────────────┐  ┌──────────────────────────────┐  │  │  │
│  │  │  │LogInLogOutResource│  │  REST Resources              │  │  │  │
│  │  │  │POST /api/v2/login │  │  @RolesAllowed + rbacService │  │  │  │
│  │  │  │  userService.auth()│  │  DACSecurityContext.isAdmin()|  │  │  │
│  │  │  │  tokenManager.create│ └──────────────────────────────┘  │  │  │
│  │  │  └──────────────────┘                                      │  │  │
│  │  └──────────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌──────────────────────────┐  ┌────────────────────────────────┐   │
│  │   DremioFlightService    │  │  Core Services                 │   │
│  │   (JDBC/ODBC via Flight) │  │  ┌────────────────────────┐   │   │
│  │  DremioBearerToken       │  │  │ UserService            │   │   │
│  │  Authenticator:          │  │  │ (SimpleUserService)    │   │   │
│  │  - if Bearer: validate   │  │  │ authenticate(u,p)      │   │   │
│  │    tokenManager          │  │  │ createUser()           │   │   │
│  │  - else: BasicAuth       │  │  │ getUser()              │   │   │
│  │    DremioCredentialValid.│  │  └────────────────────────┘   │   │
│  │    userService.auth()    │  │  ┌────────────────────────┐   │   │
│  │    tokenManager.create() │  │  │ TokenManager           │   │   │
│  └──────────────────────────┘  │  │ (KVStore opaque tokens │   │   │
│                                │  │  + JWT signing/verify) │   │   │
│                                │  └────────────────────────┘   │   │
│                                │  ┌────────────────────────┐   │   │
│                                │  │ RbacService            │   │   │
│                                │  │ (RoleStore+GrantStore   │   │   │
│                                │  │  +MembershipStore)      │   │   │
│                                │  └────────────────────────┘   │   │
│                                └────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │    RocksDB / KVStore                                             │ │
│  │    tokens | users | roles | grants | memberships                │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### System Overview: Target Architecture (with Keycloak OIDC)

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENTS                                     │
│  ┌──────────────┐  ┌───────────────┐  ┌─────────────────────────┐   │
│  │   Web UI     │  │  REST API     │  │  JDBC/ODBC              │   │
│  │  "Login with │  │  Bearer KC    │  │  Bearer KC JWT via      │   │
│  │   SSO" btn   │  │  JWT directly │  │  Flight auth2           │   │
│  └──────┬───────┘  └───────┬───────┘  └────────────┬────────────┘   │
└─────────┼──────────────────┼───────────────────────┼────────────────┘
          │                  │                        │
          │ 302 to KC        │ KC access token         │ KC access token
          │ → callback →     │ validated stateless     │ validated stateless
          │ Dremio token     │                         │ → Dremio session token
          ▼                  ▼                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    COORDINATOR NODE                                  │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │              WebServer (Jetty + Jersey/JAX-RS)                 │  │
│  │                                                                │  │
│  │  NEW: GET /api/v3/oidc/login    → 302 redirect to Keycloak    │  │
│  │  NEW: GET /api/v3/oidc/callback → exchange code, JIT, token   │  │
│  │                                                                │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │         DACAuthFilter (MODIFIED)                        │  │  │
│  │  │  TokenUtils.getAuthHeaderToken() [unchanged]            │  │  │
│  │  │  if Bearer JWT && isOidcMode():                         │  │  │
│  │  │    OidcTokenValidator.validate(jwt) → TokenDetails      │  │  │
│  │  │  else:                                                  │  │  │
│  │  │    tokenManager.validateToken(token)  [unchanged path]  │  │  │
│  │  │  userService.getUser(userName)  [unchanged]             │  │  │
│  │  │  DACSecurityContext(...)  [unchanged]                   │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                                                                │  │
│  │  LogInLogOutResource: internal auth path UNCHANGED             │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌──────────────────────────┐  ┌────────────────────────────────┐   │
│  │   DremioFlightService    │  │  Core Services                 │   │
│  │   (JDBC/ODBC)            │  │  ┌────────────────────────┐   │   │
│  │  DremioBearerToken       │  │  │ UserService (MODIFIED) │   │   │
│  │  Authenticator (MODIFIED)│  │  │ JIT: createUser on     │   │   │
│  │  if Bearer && isOidcMode │  │  │ first OIDC callback    │   │   │
│  │  && looksLikeJwt():      │  │  └────────────────────────┘   │   │
│  │    OidcTokenValidator    │  │  ┌────────────────────────┐   │   │
│  │    → Dremio session token│  │  │ TokenManager (UNCHANGED│   │   │
│  │  else: existing path     │  │  └────────────────────────┘   │   │
│  └──────────────────────────┘  │  ┌────────────────────────┐   │   │
│                                │  │ RbacService (UNCHANGED) │   │   │
│  NEW ─────────────────────     │  │ Role sync at JIT time  │   │   │
│  │ OidcTokenValidator │        │  └────────────────────────┘   │   │
│  │ RemoteJWKSet (KC)  │        │  ┌────────────────────────┐   │   │
│  │ RS256 validation   │        │  │ OidcTokenValidator     │   │   │
│  │ returns TokenDetails        │  │ [NEW]                  │   │   │
│  └────────────────────         │  │ nimbus RemoteJWKSet    │   │   │
│                                │  │ RS256, iss check       │   │   │
│  NEW ─────────────────────     │  └────────────────────────┘   │   │
│  │ OidcJitProvisioner │        │  ┌────────────────────────┐   │   │
│  │ KeycloakRoleMapper │        │  │ OidcJitProvisioner     │   │   │
│  └────────────────────         │  │ [NEW]                  │   │   │
│                                │  └────────────────────────┘   │   │
│                                └────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │    RocksDB / KVStore (UNCHANGED)                                 │ │
│  │    tokens | users | roles | grants | memberships                │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
          │
          │ JWKS fetch (startup + on key rotation)
          ▼
┌─────────────────────────────────┐
│   Keycloak                      │
│  /realms/{realm}/               │
│  protocol/openid-connect/certs  │
│  protocol/openid-connect/token  │
│  protocol/openid-connect/auth   │
└─────────────────────────────────┘
```

---

## Component Responsibilities

### Existing Components — What Changes

| Component | Current Responsibility | Change for v1.5 |
|-----------|------------------------|-----------------|
| `DACAuthFilter` | Validates opaque token via `tokenManager.validateToken()`, loads user, builds `DACSecurityContext` | Add branch: if Bearer JWT and OIDC mode, delegate to `OidcTokenValidator` instead of `tokenManager` |
| `TokenUtils.getAuthHeaderToken()` | Extracts `Bearer` or `_dremio` token from `Authorization` header | No change needed — already extracts bearer tokens and returns `(TokenType.BEARER, jwt)` |
| `LogInLogOutResource` | POST /api/v2/login credential check + opaque token issuance | No change — internal auth path stays intact as fallback |
| `UserService` / `SimpleUserService` | Manages KVStore-persisted users; `authenticate()` for password check | No interface change. Callers (`OidcJitProvisioner`) use existing `createUser()` + `getUser()` |
| `DACSecurityContext` | JAX-RS `SecurityContext`; `isUserInRole("admin")` delegates to `RbacService` | No change — consumes `userName` string; agnostic to how it was authenticated |
| `RbacService` | Privilege enforcement, membership management | No change. `OidcJitProvisioner` calls existing `addMembership()` for role sync |
| `DremioBearerTokenAuthenticator` | Flight auth2 bearer token validation via `tokenManager.validateToken()` | Add branch: if OIDC mode and token looks like a 3-part JWT, call `OidcTokenValidator` before `tokenManager` |
| `DACDaemonModule` | Wires `UserService` based on `services.coordinator.web.auth.type` | Add `oidc` branch in `buildUserService()`: binds `OidcTokenValidator`, `OidcJitProvisioner`, registers OIDC REST resource |
| `DremioConfig` | TypeSafe Config constants | Add constants: `services.coordinator.web.auth.oidc.*` |
| `DACConfig` | Typed accessor for config | Add `isOidcAuth()` method mirroring existing `isInternalUserAuth()` |

### New Components to Build

| Component | Location | Responsibility |
|-----------|----------|----------------|
| `OidcTokenValidator` | `services/tokens/src/.../oidc/` | Validates Keycloak-issued RS256 JWTs against remote JWKS. Returns `TokenDetails` (username extracted from `preferred_username` claim, expiry from `exp`). Uses nimbus-jose-jwt `RemoteJWKSet` (nimbus already in `services/tokens` pom). |
| `KeycloakJwksProvider` | `services/tokens/src/.../oidc/` | Fetches and caches Keycloak's public JWKS from `{issuerUrl}/protocol/openid-connect/certs`. Wraps nimbus `RemoteJWKSet` with config-driven URL. Handles key rotation via nimbus's built-in refresh. |
| `OidcCallbackResource` | `dac/backend/.../resource/` | GET /api/v3/oidc/login — builds Keycloak OIDC authorization URL and 302 redirects browser. GET /api/v3/oidc/callback — exchanges authorization code for tokens, calls `OidcJitProvisioner`, issues Dremio opaque token via `tokenManager.createToken()`, redirects UI to `/` |
| `OidcJitProvisioner` | `dac/backend/.../service/oidc/` | On OIDC callback: (1) `userService.getUser()` to detect new user, (2) `userService.createUser()` if `UserNotFoundException`, (3) `KeycloakRoleMapper.map(realmRoles)` then `rbacService.addMembership()` for each mapped role. Must be idempotent. |
| `KeycloakRoleMapper` | `dac/backend/.../service/oidc/` | Translates Keycloak realm role names (from JWT `realm_access.roles` claim) to Dremio RBAC role IDs. Reads `services.coordinator.web.auth.oidc.role_mappings` and `admin_role` from config. |
| `OidcConfig` | `dac/backend/.../service/oidc/` | Typed wrapper for OIDC config: `issuerUrl`, `clientId`, `clientSecret`, `redirectUri`, `adminRole`, `roleMappings`. Read once at startup. |

---

## Recommended Project Structure

```
services/tokens/src/main/java/com/dremio/service/tokens/
└── oidc/
    ├── OidcTokenValidator.java         # NEW: validates KC JWT, returns TokenDetails
    └── KeycloakJwksProvider.java       # NEW: fetches+caches KC JWKS via RemoteJWKSet

dac/backend/src/main/java/com/dremio/dac/
├── resource/
│   └── OidcCallbackResource.java       # NEW: /api/v3/oidc/login + /api/v3/oidc/callback
├── service/
│   └── oidc/
│       ├── OidcConfig.java             # NEW: typed config wrapper
│       ├── OidcJitProvisioner.java     # NEW: user creation + role sync
│       └── KeycloakRoleMapper.java     # NEW: role name translation
└── server/
    └── DACAuthFilter.java              # MODIFIED: add OIDC JWT branch
        DACConfig.java                  # MODIFIED: add isOidcAuth()
        DACDaemonModule.java            # MODIFIED: add oidc binding branch

dac/ui/src/pages/AuthenticationPage/
└── components/
    ├── LoginForm.jsx                   # MODIFIED: add SSO button when OIDC enabled
    └── SsoButton.jsx                   # NEW: SSO redirect trigger

common/legacy/src/main/resources/
└── dremio-reference.conf               # MODIFIED: add oidc config section defaults

common/legacy/src/main/java/.../config/
└── DremioConfig.java                   # MODIFIED: add OIDC config key constants
```

### Structure Rationale

- `services/tokens/oidc/`: Token validation belongs with token services, not with web resources. Nimbus dependency is already present in that module. Reuses the pattern established by `JWTValidatorImpl` and `JWKSetManager`.
- `dac/backend/service/oidc/`: Business logic (JIT, role mapping) belongs in the service layer, not the resource layer. Resources are thin HTTP adapters.
- `OidcCallbackResource` as a JAX-RS resource: no new servlet needed — fits the existing Jersey dispatch already used by `LogInLogOutResource`, `UserResource`, etc.
- Modify `DACAuthFilter` rather than creating a new filter: it is already at `AUTHENTICATION` priority and is the single chokepoint for all REST auth. Adding a branch is lower risk than introducing filter ordering complexity.

---

## Architectural Patterns

### Pattern 1: Auth Mode Dispatch in DACAuthFilter

**What:** Check `isOidcMode()` at filter time. If OIDC and header is Bearer, try `OidcTokenValidator` first; fall back to `tokenManager.validateToken()` for opaque tokens.

**When to use:** Every request bearing a `Bearer` token when OIDC mode is enabled.

**Trade-offs:** Opaque `_dremio`-prefixed tokens and opaque Bearer tokens (from `tokenManager.createToken()`) always go through `tokenManager` regardless of mode. This preserves backward compatibility for admin users with internally-issued tokens even when OIDC is enabled.

**Example:**
```java
// In DACAuthFilter.getUserNameFromToken()
Tuple<TokenUtils.TokenType, String> tokenTuple = TokenUtils.getAuthHeaderToken(requestContext);
if (tokenTuple != null
    && tokenTuple.first == TokenUtils.TokenType.BEARER
    && dacConfig.isOidcAuth()) {
  try {
    TokenDetails details = oidcTokenValidator.validate(tokenTuple.second);
    TokenInfo.setContext(requestContext, details);
    return new UserName(details.username);
  } catch (IllegalArgumentException oidcFailure) {
    // Not a valid KC JWT — fall through to opaque token path
  }
}
// Existing opaque token path unchanged
tokenDetails = tokenManager.validateToken(tokenTuple.second);
```

### Pattern 2: JIT Provisioning on OIDC Callback, Not on Every Request

**What:** Provision the user once in `OidcCallbackResource.handleCallback()`. After provisioning, issue a Dremio opaque token and redirect. All subsequent web UI requests use the opaque token.

**When to use:** OIDC callback endpoint only. Not in `DACAuthFilter`.

**Why not provision in DACAuthFilter:** The filter runs on every authenticated request. Conditionally calling `createUser()` there adds KVStore writes (or at minimum a read for existence check) to the hot path for every KC JWT request. The callback-once model means provisioning happens at most once per user per browser session.

**Consequence:** REST/Flight API clients sending KC JWTs directly must either (a) have done one web UI login first to provision themselves, or (b) be pre-provisioned by an admin. This is an acceptable constraint for v1.5.

**Future extension:** If API-client JIT is required, add it narrowly in `OidcTokenValidator.validate()` — it has all the JWT claims needed. Keep it as a separate ticket.

### Pattern 3: Role Mapping via Snapshot Sync at JIT Time

**What:** On first OIDC login, read Keycloak realm roles from the JWT `realm_access.roles` claim. Map them through `KeycloakRoleMapper` to Dremio RBAC role IDs. Call `rbacService.addMembership()` for each mapped role.

**When to use:** Inside `OidcJitProvisioner.provision()`, called from the callback.

**Idempotency:** `rbacService.addMembership()` throws `RbacEntityAlreadyExistsException` on duplicates — catch and ignore. User creation similarly catches `UserAlreadyExistException`.

**Why not sync on every login:** Synchronizing memberships on every token validation adds KVStore writes to the hot path. Snapshot-at-JIT is sufficient for v1.5. Role drift (user's KC roles change after first login) can be addressed in a future milestone by re-syncing at callback time (not filter time).

**Keycloak JWT claim for roles:** `realm_access.roles` is the standard Keycloak structure for realm-level roles. Example claim:
```json
{
  "realm_access": {
    "roles": ["dremio-admin", "analyst"]
  },
  "preferred_username": "alice"
}
```

### Pattern 4: JWKS Remote Fetch with nimbus RemoteJWKSet

**What:** At construction time, build a nimbus `RemoteJWKSet` pointed at `{issuerUrl}/protocol/openid-connect/certs`. Configure `DefaultJWTProcessor` with a `JWSVerificationKeySelector` using RS256 (Keycloak's default signing algorithm).

**When to use:** Every `OidcTokenValidator.validate()` call.

**Key difference from internal JWT path:** Internal Dremio JWTs use ES256 with `ImmutableJWKSet` (keys are local). Keycloak JWTs use RS256 with `RemoteJWKSet` (keys are remote). Do not reuse `JWTProcessorFactory` — it hardcodes ES256 and `ImmutableJWKSet`.

**Claim verification:** Configure `DefaultJWTClaimsVerifier` to require:
- `iss` == `{issuerUrl}/realms/{realm}` (exact match, from config)
- `exp` is in the future (automatic in nimbus)
- `preferred_username` is present and non-null (required for username extraction)

---

## Data Flow

### Flow 1: Web UI SSO Login (New)

```
User clicks "Login with SSO"
    ↓
Browser → GET /api/v3/oidc/login
    ↓
OidcCallbackResource.initiateLogin()
  builds: {issuerUrl}/protocol/openid-connect/auth
          ?client_id={clientId}
          &redirect_uri={dremio_base}/api/v3/oidc/callback
          &response_type=code
          &scope=openid profile
  → 302 redirect to Keycloak
    ↓
Browser → Keycloak login page
    ↓ user authenticates
Keycloak → GET /api/v3/oidc/callback?code={authCode}
    ↓
OidcCallbackResource.handleCallback()
  1. POST to {issuerUrl}/protocol/openid-connect/token
     body: code={authCode}&client_id=...&client_secret=...&grant_type=authorization_code
     response: {access_token, id_token, ...}
  2. OidcTokenValidator.validate(access_token) → TokenDetails{username, expiry}
  3. OidcJitProvisioner.provision(userName, realmRoles):
     a. userService.getUser(userName) → if UserNotFoundException:
        userService.createUser(SimpleUser.newBuilder()
          .setUserName(userName).setEmail(claims.email)...build())
     b. KeycloakRoleMapper.map(realmRoles) → Set<String> dremioRoleIds
        for each roleId: rbacService.addMembership(userName, roleId, "OIDC")
           (catch RbacEntityAlreadyExistsException → ignore)
  4. tokenManager.createToken(userName, clientAddress) → TokenDetails
  5. 302 redirect to / with token
     (token delivery: URL fragment #token=... or secure cookie — TBD in implementation)
    ↓
Web UI loads with Dremio opaque token → existing auth flow unchanged from here
```

### Flow 2: REST API with Keycloak JWT (Modified DACAuthFilter Path)

```
curl -H "Authorization: Bearer {KC_ACCESS_TOKEN}" /api/v3/catalog
    ↓
DACAuthFilter.filter()
  TokenUtils.getAuthHeaderToken(ctx) → (BEARER, "{jwt}")
  dacConfig.isOidcAuth() → true
  OidcTokenValidator.validate("{jwt}"):
    RemoteJWKSet.get(header.kid) → RSA public key
    DefaultJWTProcessor.process(jwt, null) → JWTClaimsSet
    check iss == configured issuerUrl, check exp
    return TokenDetails{username=claims.preferred_username, expiry=claims.exp}
  userService.getUser(userName) → User [must exist in KVStore — provisioned via callback or by admin]
  setSecurityContext(new DACSecurityContext(userName, user, ctx, rbacService, config))
    ↓
JAX-RS resource executes with correct security context [UNCHANGED from here]
All RBAC enforcement continues via DACSecurityContext.isUserInRole() + rbacService.hasPrivilege()
```

### Flow 3: JDBC/ODBC (Arrow Flight) with Keycloak JWT

```
JDBC driver sends Bearer {KC_ACCESS_TOKEN} in Authorization header
    ↓
DremioBearerTokenAuthenticator.authenticate(incomingHeaders)
  bearerToken = AuthUtilities.getValueFromAuthHeader(headers, BEARER_PREFIX)
  if bearerToken != null:
    if dacConfig.isOidcAuth() && looksLikeJwt(bearerToken):  // 3-part base64 check
      OidcTokenValidator.validate(bearerToken) → TokenDetails{username}
      DremioFlightAuthUtils.createUserSessionWithTokenAndProperties(
          tokenManagerProvider, username)   // creates Dremio session token
      return AuthResult with session token [peer identity = session token]
    else:
      tokenManagerProvider.get().validateToken(bearerToken)  [existing opaque path]
```

### Flow 4: Internal Auth (Completely Unchanged)

```
POST /api/v2/login {"userName": "admin", "password": "..."}
    ↓
LogInLogOutResource.login()  [no changes to this class]
  userService.authenticate(userName, password)
  tokenManager.createToken(userName, clientAddress) → TokenDetails
  return UserLoginSession{token, userName, admin, permissions, ...}
    ↓
All subsequent requests: Authorization: _dremio{token} or Bearer {opaqueToken}
  DACAuthFilter:
    TokenUtils.getAuthHeaderToken() → (CUSTOM, token) or (BEARER, opaqueToken)
    isOidcAuth() && BEARER → OidcTokenValidator.validate() → fails (not a JWT)
    falls through to tokenManager.validateToken() [unchanged]
```

---

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| Keycloak JWKS endpoint | `RemoteJWKSet` HTTP GET at startup, cached in memory, refreshed on `UnresolvableKeyException` | URL: `{issuerUrl}/protocol/openid-connect/certs`. Must be network-reachable from coordinator. Timeout config needed. |
| Keycloak token endpoint | HTTP POST (Apache HttpClient or OkHttp) from `OidcCallbackResource` for authorization code exchange | URL: `{issuerUrl}/protocol/openid-connect/token`. Only for web UI callback flow; REST/Flight clients send JWTs directly without code exchange. |
| Keycloak authorization endpoint | HTTP 302 redirect from browser, constructed by `OidcCallbackResource.initiateLogin()` | URL: `{issuerUrl}/protocol/openid-connect/auth`. Browser-only. Dremio server never directly calls this. |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `DACAuthFilter` → `OidcTokenValidator` | Direct method call via `@Inject` | Guard with `dacConfig.isOidcAuth()` check before any KC JWT validation attempt |
| `OidcCallbackResource` → `OidcJitProvisioner` | Direct method call | `OidcCallbackResource` is a JAX-RS resource; injects `OidcJitProvisioner` via `@Inject` |
| `OidcJitProvisioner` → `UserService` | Existing `UserService.createUser()` + `UserService.getUser()` | No `UserService` interface changes. Catch `UserAlreadyExistException` for idempotency. |
| `OidcJitProvisioner` → `RbacService` | Existing `RbacService.addMembership()` | Catch `RbacEntityAlreadyExistsException` — membership may exist from a prior login. Skip if RBAC is disabled (`services.rbac.enabled=false`). |
| `OidcCallbackResource` → `TokenManager` | Existing `tokenManager.createToken()` | Issues a standard Dremio opaque token after successful OIDC callback. Decouples Dremio session lifetime from KC token lifetime. |
| `DACDaemonModule` → OIDC services | Binding in `buildUserService()` method | Extend the existing `if (dacConfig.isInternalUserAuth())` block: add `else if (dacConfig.isOidcAuth())` branch that binds `OidcTokenValidator`, `OidcJitProvisioner`, `KeycloakRoleMapper` and registers `OidcCallbackResource`. |

---

## Config Changes

New config keys to add to `dremio-reference.conf` and `DremioConfig.java`:

```hocon
# In dremio-reference.conf, under services.coordinator.web.auth:
auth: {
  type: "internal"        # existing default; new valid value: "oidc"
  oidc: {
    issuer_url: ""        # e.g. http://keycloak:8080/realms/dremio
    client_id: ""
    client_secret: ""
    redirect_uri: ""      # e.g. https://dremio:9047/api/v3/oidc/callback
    admin_role: "dremio-admin"    # KC realm role name that maps to Dremio ADMIN role
    role_mappings: {}     # map: keycloak_role_name -> dremio_rbac_role_id
                          # e.g. { analyst: "ANALYST", dremio-admin: "ADMIN" }
  }
}
```

New constants in `DremioConfig.java`:
```java
public static final String OIDC_ISSUER_URL  = "services.coordinator.web.auth.oidc.issuer_url";
public static final String OIDC_CLIENT_ID   = "services.coordinator.web.auth.oidc.client_id";
public static final String OIDC_CLIENT_SECRET = "services.coordinator.web.auth.oidc.client_secret";
public static final String OIDC_REDIRECT_URI = "services.coordinator.web.auth.oidc.redirect_uri";
public static final String OIDC_ADMIN_ROLE  = "services.coordinator.web.auth.oidc.admin_role";
public static final String OIDC_ROLE_MAPPINGS = "services.coordinator.web.auth.oidc.role_mappings";
```

New method in `DACConfig.java`:
```java
public boolean isOidcAuth() {
    return "oidc".equals(config.getString(WEB_AUTH_TYPE));
}
```

---

## Suggested Build Order

Each phase compiles and deploys independently. No phase breaks existing functionality.

### Phase 1: OidcTokenValidator + JWKS infrastructure

Build `OidcTokenValidator` and `KeycloakJwksProvider` in `services/tokens/oidc/`. Unit-testable with a static test JWKS. No wiring to existing classes.

Deliverable: `OidcTokenValidator.validate(jwtString)` returns `TokenDetails` or throws `IllegalArgumentException`. Tests pass with a test KC-style RS256 JWT.

Dependencies: nimbus-jose-jwt (already in `services/tokens` pom). No new Maven dependencies needed.

### Phase 2: Config + DACDaemonModule wiring

Add OIDC config constants to `DremioConfig`. Add `isOidcAuth()` to `DACConfig`. Add `oidc` branch in `DACDaemonModule.buildUserService()` that binds `OidcTokenValidator`. Coordinator starts when `auth.type=oidc` without crashing.

Deliverable: Coordinator with `auth.type=oidc` starts cleanly. No functional auth yet.

### Phase 3: DACAuthFilter OIDC branch — REST API Bearer JWT

Modify `DACAuthFilter.getUserNameFromToken()`: when auth type is OIDC and header is `Bearer`, try `OidcTokenValidator`; fall through to opaque path on failure. Wire `OidcTokenValidator` injection into `DACAuthFilter`.

Deliverable: `curl -H "Authorization: Bearer {KC_JWT}" /api/v3/catalog` works for pre-existing users. 401 for unknown users (expected — no JIT here).

### Phase 4: OidcCallbackResource + OidcJitProvisioner — Web UI SSO login

Build `OidcCallbackResource`, `OidcJitProvisioner`, `KeycloakRoleMapper`, `OidcConfig`. Register `OidcCallbackResource` in the Jersey app. Implement: GET /api/v3/oidc/login (redirect), GET /api/v3/oidc/callback (code exchange + JIT + Dremio token issuance).

Deliverable: Web UI OIDC login flow works end-to-end. New user is created in Dremio KVStore on first login. RBAC roles are assigned per config mapping.

### Phase 5: Web UI "Login with SSO" button

Add `SsoButton.jsx` and modify `LoginForm.jsx` to conditionally show it. Add a backend endpoint (e.g., `GET /api/v3/oidc/enabled`) or include OIDC status in the server info response, so the UI can decide whether to show the SSO button without hardcoding config.

Deliverable: Complete web UI SSO login flow. Internal login form remains visible as fallback (admin users who are not in Keycloak can still log in with username+password).

### Phase 6: Arrow Flight OIDC Bearer Token — JDBC/ODBC

Modify `DremioBearerTokenAuthenticator.authenticate()`: when OIDC mode and bearer token is a 3-part base64 string (JWT), call `OidcTokenValidator` and create a Dremio session token for the resulting username.

Deliverable: JDBC/ODBC clients using Keycloak access tokens as password can connect.

---

## Anti-Patterns

### Anti-Pattern 1: Modifying UserService Interface for OIDC

**What people do:** Add `authenticateOidc(String jwt)` or `createOrGetOidcUser()` to `UserService`.

**Why it's wrong:** `UserService` is a large interface already implemented by `SimpleUserService` and `ExecutorUserService`. Modifying it requires updating both. The existing `createUser()` + `getUser()` API is sufficient — OIDC provisioning is a caller concern, not a `UserService` responsibility.

**Do this instead:** `OidcJitProvisioner` is a separate service that calls the existing `UserService` methods. `UserService` stays clean and unchanged.

### Anti-Pattern 2: Storing Keycloak JWTs as Dremio Session Tokens

**What people do:** On OIDC login, store the Keycloak JWT itself in `TokenManager` as the session token.

**Why it's wrong:** Keycloak JWTs expire on Keycloak's schedule (typically 5 minutes). They can be revoked at Keycloak without Dremio knowing. The KVStore then holds tokens Dremio cannot invalidate. Keycloak JWTs are designed for Keycloak's resource server ecosystem, not as opaque Dremio session tokens.

**Do this instead:** For the web UI callback flow, issue a fresh Dremio opaque token via `tokenManager.createToken()` after successful OIDC validation. This decouples Dremio session lifetime from KC token lifetime. For REST/Flight direct KC JWT path, do stateless validation only — no KV store write.

### Anti-Pattern 3: Accepting Any Validly-Signed Bearer JWT

**What people do:** Accept any JWT whose signature validates, without checking the `iss` claim.

**Why it's wrong:** A JWT issued by a different Keycloak realm, or by Dremio's own `SystemJWKSetManager` (ES256), would be accepted. The `iss` claim is the only cryptographic link between a JWT and a specific Keycloak realm.

**Do this instead:** Configure `DefaultJWTClaimsVerifier` with an exact `iss` match from config. Reject JWTs with any other issuer.

### Anti-Pattern 4: JIT Provisioning in DACAuthFilter

**What people do:** Call `userService.createUser()` inside `DACAuthFilter` for every Bearer JWT request where the user doesn't exist yet.

**Why it's wrong:** Every REST API call from a new user triggers a KVStore write in the hot path. Race conditions when two requests from the same new user arrive simultaneously both attempt `createUser()`. The filter's contract is to authenticate, not to provision.

**Do this instead:** Provision only in `OidcCallbackResource.handleCallback()`. For REST API clients, require pre-provisioning (admin creates user or user does one web login first).

### Anti-Pattern 5: Coupling OIDC Auth to RBAC Being Enabled

**What people do:** Skip OIDC or refuse to start when `services.rbac.enabled=false`.

**Why it's wrong:** Authentication (who are you?) and authorization (what can you do?) are independent layers. The "RBAC is additive, gated behind a flag" principle from v1.0 must be preserved. A deployment should be able to use Keycloak for authentication with RBAC disabled.

**Do this instead:** In `OidcJitProvisioner.provision()`, check `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` before calling `rbacService.addMembership()`. Skip role sync silently when RBAC is disabled. OIDC authentication works regardless of RBAC state.

### Anti-Pattern 6: Using ES256 JWTProcessorFactory for Keycloak JWTs

**What people do:** Reuse the existing `JWTProcessorFactory` to build the processor for Keycloak tokens.

**Why it's wrong:** `JWTProcessorFactory` is hardcoded to ES256 algorithm and `ImmutableJWKSet` (Dremio's local keys). Keycloak issues RS256 JWTs and uses a remote JWKS endpoint.

**Do this instead:** Build a separate `DefaultJWTProcessor` in `OidcTokenValidator` configured with `JWSAlgorithm.RS256` and nimbus `RemoteJWKSet` pointed at the Keycloak JWKS URL.

---

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| 1-50 users | Single coordinator, JWKS fetched at startup and cached — no scaling concern. JIT provisions happen once per user. |
| 50-1000 users | JWKS cache operates in memory; remote JWKS fetch adds latency only on startup and key rotation events. `RemoteJWKSet` uses background refresh to avoid blocking. |
| 1000+ users | If role sync at JIT time becomes a bottleneck (many roles per user, large number of logins), consider async provisioning. At this scale, also review KVStore key count for role memberships. |

### Scaling Priorities

1. **JWKS cache miss during key rotation:** If Keycloak rotates keys, all in-flight JWT validations fail simultaneously, triggering concurrent JWKS re-fetches. Nimbus `RemoteJWKSet` has a preemptive refresh option — use it with a TTL well below Keycloak's key rotation period.
2. **JIT write concurrency:** Two simultaneous requests from the same new user both call `createUser()`. The second gets `UserAlreadyExistException`. Catch and ignore — fully idempotent by design.

---

## Sources

All findings from direct source code inspection. No external references.

- `DACAuthFilter.java` — token validation pipeline, `getUserNameFromToken()`, injection points
- `DACSecurityContext.java` — security context construction, `isUserInRole()` RBAC delegation
- `DACDaemonModule.java` lines 2192-2223 — `buildUserService()` auth type dispatch pattern
- `DACConfig.java` — `isInternalUserAuth()` pattern to copy for `isOidcAuth()`
- `LogInLogOutResource.java` — login flow, `tokenManager.createToken()`, `UserLoginSession` construction
- `TokenManager.java` — `createToken()`, `validateToken()` signatures
- `TokenManagerImpl.java` / `TokenManagerImplV2.java` — opaque token + JWT issuance
- `JWTValidatorImpl.java` — nimbus `DefaultJWTProcessor` usage pattern for JWT validation
- `JWTProcessorFactory.java` — ES256 / `ImmutableJWKSet` usage (do NOT reuse for KC tokens)
- `JWKSetManager.java` / `SystemJWKSetManager.java` — existing JWKS infrastructure, nimbus imports
- `DremioBearerTokenAuthenticator.java` — Flight bearer auth, `validateBearer()` extension point
- `DremioFlightAuthProvider.java` — Flight auth provider interface
- `RbacService.java` — `addMembership()`, `assignBootstrapAdmin()`, `RbacEntityAlreadyExistsException`
- `UserService.java` / `BasicAuthenticator.java` / `LocalUsernamePasswordAuthProvider.java` — internal auth chain
- `dremio-reference.conf` lines 117-119 — `auth.type: "internal"` default
- `TokenUtils.java` — `getAuthHeaderToken()`, `TokenType.BEARER` extraction
- `AuthenticationPage.jsx` / `LoginForm.jsx` / `account.js` — frontend login entry point
- `services/tokens/pom.xml` — `nimbus-jose-jwt` dependency confirmation

---

*Architecture research for: Keycloak OIDC Integration into Dremio OSS (v1.5)*
*Researched: 2026-03-12*
