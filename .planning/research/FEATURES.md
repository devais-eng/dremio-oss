# Feature Research

**Domain:** Keycloak OIDC Identity Provider Integration for Java/Jersey web app
**Researched:** 2026-03-12
**Confidence:** HIGH (codebase analysis) / MEDIUM (external patterns)

---

## Context: What This Milestone Must Integrate With

The existing system provides:
- `UserService` / `SimpleUserService`: KVStore-backed local user accounts with BCrypt password hashing
- `Authenticator` interface with pluggable `AuthProvider` implementations (`LocalUsernamePasswordAuthProvider`)
- `DACAuthFilter`: JAX-RS `ContainerRequestFilter` that validates Dremio opaque/JWT session tokens from the `Authorization` header
- `LogInLogOutResource`: `POST /api/v3/login` — authenticates username+password, returns a `UserLoginSession` with a Dremio-issued session token
- `TokenManager` / `TokenManagerImplV2`: Issues and validates opaque + JWT tokens; uses nimbus-jose-jwt (v9.41) + oauth2-oidc-sdk (v11.20), already on classpath
- `DremioBearerTokenAuthenticator` (Arrow Flight auth2 mode): Validates Dremio Bearer tokens for JDBC/ODBC via Arrow Flight
- `DACDaemonModule`: Auth type dispatch — currently `"internal"` (local KVStore) or `"ldap"` (throws on OSS)
- `DACConfig.isInternalUserAuth()`: Returns true when `services.coordinator.web.auth.type = "internal"`
- RBAC: Flat roles in KVStore, `isAdminMember()`, deny-by-default — **authorization layer stays; only authentication changes**
- `WEB_AUTH_TYPE` config key dispatches auth type at startup
- Frontend: Redux-saga login flow, `LoginForm.jsx` (username/password form), `SSO_LANDING_PATH = "/login/sso/landing"` already defined in loginLogout.js (but unimplemented)

Key library finding: `com.nimbusds:nimbus-jose-jwt:9.41` and `com.nimbusds:oauth2-oidc-sdk:11.20` are already declared in the root pom.xml. JWKS fetching, JWT verification, and OIDC token parsing infrastructure is already available without new dependencies.

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features that are required for the Keycloak IdP integration to be usable. Missing any of these means the feature is incomplete.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Configuration flag to enable Keycloak auth (`services.coordinator.web.auth.type = "keycloak"`) | Every IdP integration is configuration-driven; must not require code changes to switch | LOW | Follows existing `WEB_AUTH_TYPE` dispatch pattern in `DACDaemonModule`; add `"keycloak"` as a third valid value |
| OIDC Authorization Code Flow redirect for Web UI ("Login with SSO") | Standard browser-based OIDC login; users expect a button that redirects to Keycloak login page | HIGH | Requires: `/api/v3/oauth/authorize` (redirects to Keycloak), callback endpoint `/api/v3/oauth/callback` (exchanges code for tokens), state+PKCE for CSRF protection; `SSO_LANDING_PATH` stub already exists in the UI |
| Keycloak JWT Bearer token validation for REST API | All REST clients (scripts, automation) use `Authorization: Bearer <token>`; when Keycloak is the IdP, they will pass Keycloak-issued access tokens | HIGH | Cannot reuse `TokenManagerImplV2` (it resolves users by Dremio UID from JWT `sub`); needs a parallel validation path that: (1) fetches Keycloak JWKS from `{issuer}/protocol/openid-connect/certs`, (2) validates signature+expiry, (3) extracts `preferred_username` claim, (4) resolves to Dremio user. nimbus-jose-jwt already on classpath |
| JIT (Just-In-Time) user provisioning on first Keycloak login | Users managed in Keycloak should not need manual creation in Dremio; first login creates the local user record | MEDIUM | Create user via `UserService.createUser()` on successful OIDC callback if `UserService.getUser(username)` throws `UserNotFoundException`; username sourced from `preferred_username` claim; email from `email` claim |
| Keycloak realm role → Dremio RBAC role mapping on login | Keycloak realm roles in `realm_access.roles` JWT claim must sync to Dremio RBAC role memberships | HIGH | On every login (not just first): (1) read `realm_access.roles` from Keycloak access token, (2) map to Dremio role names (configurable prefix/direct name mapping), (3) call `RbacService.grantRoleToUser()` / `revokeRoleFromUser()` to sync; roles not in Keycloak are revoked from Dremio on each login |
| ODBC/JDBC token-based auth with Keycloak tokens | Data analysts use BI tools (DBeaver, Tableau, etc.) via JDBC/Arrow Flight; they need to authenticate when Keycloak is the IdP | MEDIUM | Keycloak-issued access tokens passed as the password field in Arrow Flight basic auth; `DremioCredentialValidator` must detect token-shaped passwords and validate via Keycloak JWKS instead of `UserService.authenticate()`. Alternatively: accept Dremio-issued session tokens (opaque) obtained via the REST login endpoint as Bearer tokens |
| Dremio form-based login bypass/coexistence | Internal service accounts (CI pipelines, admin bootstrapping) may still need username+password auth even when Keycloak is enabled | MEDIUM | When `auth.type = keycloak`, `POST /api/v3/login` with username+password should still work for users that exist in the local KVStore (admin fallback), OR be explicitly disabled. Clear documentation and configuration needed |
| Config validation at startup | Misconfigured Keycloak settings (wrong issuer URL, invalid client secret) must fail fast with clear error messages | LOW | Fetch `{issuer}/.well-known/openid-configuration` at startup; verify JWKS endpoint reachable; log and throw on failure |

### Differentiators (Competitive Advantage)

Features beyond the baseline that improve operator or user experience.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Configurable role name prefix/mapping for Keycloak roles | Keycloak realm roles are often prefixed (`dremio_admin`, `dremio_analyst`); operators want to strip prefix or map to Dremio role names | LOW | Simple `services.keycloak.role.prefix` config key; strip prefix before matching to Dremio role names |
| JWKS key rotation with cache refresh | Keycloak rotates signing keys without disrupting sessions; validator auto-fetches new JWKS when `kid` mismatch | MEDIUM | Standard nimbus-jose-jwt pattern: cache JWKS with TTL; on `kid` not found, refresh before rejecting. Pattern already implemented in `RemoteJWKSetManager` |
| Configurable admin role mapping (`services.keycloak.admin.role`) | Operators want to designate which Keycloak realm role maps to Dremio ADMIN membership | LOW | Config key with default value (e.g., `dremio-admin`); any user with this Keycloak role gets ADMIN membership synced in Dremio RBAC |
| POST /api/v3/login continues to work for all users (even Keycloak users) via a special "token exchange" path | Allows automated scripts to obtain a Dremio session token by presenting a Keycloak access token directly to the login endpoint | MEDIUM | Accept `Authorization: Bearer <keycloak_token>` on `POST /api/v3/login`; validate the Keycloak token, JIT-provision if needed, issue a Dremio session token. Decouples BI tool from needing to present Keycloak tokens directly to Arrow Flight |
| Logout with Keycloak session termination | When user logs out of Dremio UI, also terminate the Keycloak session (Single Logout) | HIGH | Keycloak supports OIDC RP-Initiated Logout (`end_session_endpoint`); redirect browser there on Dremio logout. Complex because requires storing the Keycloak `id_token_hint` for the session |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Per-request Keycloak token introspection (calling `/token/introspect` on every API request) | Seems like a way to get real-time token revocation | Adds 100ms+ of HTTP latency on every API call; Keycloak becomes a synchronous dependency for every request; kills performance | Validate JWT locally with cached JWKS; accept short token TTL as the revocation window. Only introspect if token is a reference token (opaque), not a JWT |
| Storing Keycloak access tokens in the Dremio KVStore | Might seem like a natural extension of the existing token store | Unnecessary: Keycloak JWTs are self-contained and verifiable without storage; storing them duplicates state and creates expiry sync problems | Validate Keycloak JWTs stateless-ly via JWKS; issue a short-lived Dremio session token after validation if needed |
| SAML 2.0 support alongside OIDC | Some enterprises use SAML instead of OIDC | Completely different protocol and library stack; doubles integration surface; Keycloak itself bridges SAML IdPs to OIDC for clients | Point Keycloak at the SAML IdP as an identity broker; Dremio always sees Keycloak/OIDC regardless |
| Replacing Dremio's RBAC with Keycloak Authorization Services | Keycloak has its own authorization engine (UMA, policies) | Keycloak Authorization Services is complex and Dremio already has a working RBAC layer backed by RocksDB; replacing it would require migrating all existing grants | Use Keycloak only for authentication and role transport (claims); keep all authorization in Dremio's RBAC system |
| Auto-creating Keycloak roles from Dremio RBAC | Round-tripping role management between Keycloak and Dremio | Creates a bidirectional sync problem; which system is authoritative?; roles created in Dremio by admins would need to appear in Keycloak | Treat Keycloak as authoritative for roles; map Keycloak realm roles → Dremio RBAC roles unidirectionally at login time |
| LDAP-via-Keycloak as a path for LDAP support in OSS | Keycloak can federate LDAP | This conflates the Keycloak IdP feature with LDAP; the existing OSS `WEB_AUTH_TYPE = ldap` throws at startup; fixing LDAP in OSS is a separate milestone | Defer LDAP-in-OSS; document that Keycloak can federate LDAP for operators who need it |

---

## Feature Dependencies

```
[Existing RBAC system (v1.0–v1.4)]
    └──required by──> [Role mapping on login]
    └──required by──> [JIT user provisioning] (creates users that RBAC then governs)

[Config: auth.type = "keycloak"]
    └──enables──> [OIDC redirect login (Web UI)]
    └──enables──> [Keycloak JWT validation (REST API)]
    └──enables──> [OIDC-aware Arrow Flight auth (JDBC/ODBC)]

[Keycloak OIDC Authorization Code Flow]
    └──requires──> [OIDC callback endpoint /api/v3/oauth/callback]
    └──requires──> [State + PKCE parameter storage (server-side session or signed cookie)]
    └──produces──> [Keycloak access token (JWT)]
                       └──feeds──> [JIT user provisioning]
                       └──feeds──> [Role mapping on login]
                       └──feeds──> [Dremio session token issuance]

[Keycloak JWKS validation]
    └──required by──> [Keycloak JWT Bearer validation (REST API)]
    └──required by──> [ODBC/JDBC Keycloak token path]
    └──depends on──> [nimbus-jose-jwt (already on classpath)]

[JIT user provisioning]
    └──requires──> [UserService.createUser()]
    └──requires──> [preferred_username claim from Keycloak JWT]

[Role mapping on login]
    └──requires──> [realm_access.roles claim from Keycloak JWT]
    └──requires──> [RbacService.grantRoleToUser() / revokeRoleFromUser()]
    └──requires──> [Role names pre-created in Dremio RBAC]

[ODBC/JDBC Keycloak token path]
    └──requires──> [Keycloak JWKS validation]
    └──feeds into──> [DremioCredentialValidator or DremioBearerTokenAuthenticator]
                       └──requires──> [JIT user provisioning] (user may not exist yet)
```

### Dependency Notes

- **OIDC callback requires state/PKCE storage:** The OIDC Authorization Code Flow is not stateless — the server must correlate the callback's `code` and `state` parameters with the original authorization request. Dremio's existing architecture has no server-side session store. Options: (1) sign the state parameter and embed PKCE verifier in it (stateless, but more complex), (2) use a short-lived KVStore entry keyed by state UUID (simplest, consistent with existing patterns). Option 2 is recommended — the KVStore already handles TTL-based token entries.

- **JIT provisioning requires UserService.createUser() before role mapping:** The user must exist in the KVStore before `RbacService.grantRoleToUser()` can be called. Order matters: provision user, then sync roles.

- **Role mapping requires pre-created Dremio roles:** `RbacService.grantRoleToUser(role, user)` will fail if the Dremio role does not exist. The mapping must either: (a) auto-create Dremio roles when a Keycloak role is first seen, or (b) only map roles that already exist in Dremio. Option (b) is safer and simpler — operators pre-create Dremio roles and configure the mapping; unmapped Keycloak roles are ignored.

- **Keycloak JWT validation vs. Dremio JWT validation must not conflict:** `DACAuthFilter` calls `tokenManager.validateToken()`. When Keycloak tokens are presented as Bearer tokens, `validateToken()` will fail (the token was not issued by Dremio). The filter must first attempt Keycloak validation when `auth.type = keycloak`, and only fall back to the Dremio token manager for Dremio-issued session tokens. This is the critical integration point in `DACAuthFilter`.

- **`JWTValidatorImpl` is incompatible with Keycloak tokens:** It resolves users by `sub` claim treated as a Dremio `UID` (UUID). Keycloak `sub` is a Keycloak-internal UUID, not a Dremio UID. A new `KeycloakJWTValidator` must be written that resolves via `preferred_username` claim instead.

---

## MVP Definition

### Launch With (v1.5 — this milestone)

Minimum viable IdP integration — what's needed to use Keycloak as the authentication provider in a real deployment.

- [ ] `services.coordinator.web.auth.type = "keycloak"` config with required sub-keys (`issuer-url`, `client-id`, `client-secret`, `redirect-uri`) — foundational, all other features depend on this
- [ ] OIDC Authorization Code Flow: `/api/v3/oauth/authorize` + `/api/v3/oauth/callback` endpoints — required for Web UI users to log in
- [ ] Keycloak JWT validation in `DACAuthFilter` — required for REST API clients using Keycloak Bearer tokens
- [ ] JIT user provisioning on first login — required so Keycloak users exist in Dremio's KVStore after first login
- [ ] Keycloak realm role → Dremio RBAC role sync on every login — required for authorization to work for Keycloak-managed users
- [ ] Keycloak token acceptance in Arrow Flight / JDBC path — required for BI tools and ODBC/JDBC consumers
- [ ] Startup config validation (fetch `/.well-known/openid-configuration`) — required to fail fast on misconfiguration

### Add After Validation (v1.x)

- [ ] Configurable role name prefix/mapping — add when operators report friction with role name conventions
- [ ] JWKS key rotation with automatic cache refresh — add before first production deployment rotation event
- [ ] Token exchange path: `POST /api/v3/login` accepting Keycloak Bearer token → Dremio session token — add when automation scripts need simpler auth flow

### Future Consideration (v2+)

- [ ] RP-Initiated Logout (Single Logout with Keycloak session termination) — high complexity, limited user demand initially
- [ ] Multiple IdP support (Keycloak + internal simultaneously, not just fallback) — complex auth routing; defer until use case is clear
- [ ] SCIM user sync from Keycloak — proactive provisioning without login; defer, JIT is sufficient for v1

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Config + startup validation | HIGH (everything depends on it) | LOW | P1 |
| OIDC redirect login (Web UI) | HIGH | HIGH | P1 |
| Keycloak JWT Bearer validation (REST API) | HIGH | HIGH | P1 |
| JIT user provisioning | HIGH | MEDIUM | P1 |
| Keycloak realm role → RBAC sync | HIGH | HIGH | P1 |
| Keycloak token in Arrow Flight/JDBC | HIGH | MEDIUM | P1 |
| Role name prefix/mapping config | MEDIUM | LOW | P2 |
| JWKS cache auto-refresh on key rotation | MEDIUM | MEDIUM | P2 |
| Token exchange on POST /api/v3/login | MEDIUM | MEDIUM | P2 |
| RP-Initiated Logout | LOW | HIGH | P3 |
| Multiple concurrent IdPs | LOW | HIGH | P3 |

**Priority key:**
- P1: Must have for this milestone launch
- P2: Should have, add in follow-on phase or as milestone extension
- P3: Nice to have, future consideration

---

## Existing Codebase Integration Points (Critical for Implementation)

These are the exact locations where new code must hook in. Identified via direct codebase analysis (HIGH confidence).

| Integration Point | File | What Changes |
|-------------------|------|--------------|
| Auth type dispatch | `DACDaemonModule.java:2216` | Add `"keycloak"` branch: bind `KeycloakUserService` wrapper and `KeycloakJWTValidator` |
| Auth type check | `DACConfig.java:203` | `isInternalUserAuth()` must return false when auth.type=keycloak; add `isKeycloakAuth()` helper |
| Token validation in auth filter | `DACAuthFilter.java:getUserNameFromToken()` | When auth.type=keycloak: try Keycloak JWT first (new `KeycloakJWTValidator`), fall back to Dremio token manager for existing Dremio-issued session tokens |
| Arrow Flight credential validation | `DremioCredentialValidator.java:validate()` | Detect Keycloak JWT-shaped passwords (starts with `eyJ`); validate via `KeycloakJWTValidator` instead of `UserService.authenticate()` |
| Login response generation | `LogInLogOutResource.java:login()` | Either: keep as-is for internal users only when auth.type=keycloak, or add Keycloak token exchange path |
| New OIDC endpoints | New `OidcResource.java` | `/api/v3/oauth/authorize` (redirect) and `/api/v3/oauth/callback` (code exchange + JIT + role sync + session token issuance) |
| New config keys | `DremioConfig.java` | `KEYCLOAK_ISSUER_URL`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_REDIRECT_URI`, `KEYCLOAK_ADMIN_ROLE`, `KEYCLOAK_ROLE_PREFIX` |
| New config defaults | `dremio-reference.conf` | `keycloak` section under `services.coordinator.web` |
| UI SSO button | `LoginForm.jsx` + `LoginFormContainer.jsx` | Add "Login with SSO" button that calls `GET /api/v3/oauth/authorize`; hide when auth.type=internal |
| UI SSO landing | `loginLogout.js:SSO_LANDING_PATH` | Implement `/login/sso/landing` route that reads token from URL params and completes login saga |

---

## Token Flow Comparison: Internal Auth vs. Keycloak Auth

### Internal Auth (existing)

```
Browser → POST /api/v3/login {user, pass}
        → UserService.authenticate() verifies BCrypt
        → TokenManager.createToken() issues opaque Dremio token
        → DACAuthFilter validates Dremio token on each request
```

### Keycloak Auth — Web UI (new)

```
Browser → GET /api/v3/oauth/authorize
        → Server generates state UUID + PKCE verifier, stores in KVStore (TTL=5min)
        → Server redirects to Keycloak authorize endpoint
        → Keycloak authenticates user (form, SSO cookie, etc.)
        → Keycloak → GET /api/v3/oauth/callback?code=...&state=...
        → Server: validate state, fetch PKCE verifier from KVStore
        → Server: POST to Keycloak token endpoint, exchange code for tokens
        → Server: validate Keycloak access token (JWKS)
        → Server: JIT-provision user if needed
        → Server: sync realm roles → Dremio RBAC
        → Server: TokenManager.createToken() issues Dremio session token
        → Server: redirect to /login/sso/landing?token=...
        → Browser: store Dremio token, complete login saga (same as internal auth)
        → DACAuthFilter validates Dremio session token on each subsequent request
```

### Keycloak Auth — REST API with Bearer Token (new)

```
REST Client → POST /api/v3/... with Authorization: Bearer <keycloak_jwt>
           → DACAuthFilter: Keycloak token path
           → KeycloakJWTValidator: fetch JWKS, validate signature+expiry
           → Extract preferred_username → resolve Dremio user (JIT if needed)
           → Sync roles on first use (or on each request — design choice)
           → Set DACSecurityContext with resolved user
```

### Keycloak Auth — JDBC/ODBC via Arrow Flight (new)

```
JDBC Driver → Arrow Flight basic auth: username="<any>", password="<keycloak_access_token>"
           → DremioCredentialValidator.validate()
           → Detect JWT shape (eyJ prefix), route to KeycloakJWTValidator
           → Validate token, extract username, JIT-provision if needed
           → TokenManager.createToken() → return Dremio Bearer token
           → Subsequent calls use Dremio Bearer token (same as today)
```

---

## Sources

- Codebase: `DACAuthFilter.java`, `DACDaemonModule.java`, `LogInLogOutResource.java`, `DACConfig.java`, `DACSecurityContext.java` (HIGH confidence — direct analysis)
- Codebase: `LocalUsernamePasswordAuthProvider.java`, `UserService.java`, `SimpleUserService.java` (HIGH confidence)
- Codebase: `TokenManager.java`, `TokenManagerImplV2.java`, `JWTValidatorImpl.java`, `RemoteJWKSetManager.java` (HIGH confidence)
- Codebase: `DremioFlightAuthProviderImpl.java`, `DremioBearerTokenAuthenticator.java`, `DremioCredentialValidator.java` (HIGH confidence)
- Codebase: `loginLogout.js`, `LoginForm.jsx` — UI login flow (HIGH confidence)
- Root `pom.xml`: `nimbus-jose-jwt:9.41`, `oauth2-oidc-sdk:11.20` confirmed on classpath (HIGH confidence)
- [Keycloak Securing Applications Guide](https://www.keycloak.org/docs/25.0.6/securing_apps/index.html) — OIDC Authorization Code Flow patterns (MEDIUM confidence)
- [Keycloak JWT structure: realm_access.roles claim](https://medium.com/@mohammad.h.zbib/solving-jwt-role-mapping-issues-in-spring-boot-with-keycloak-3f40db57216e) — realm_access.roles structure (MEDIUM confidence, verified pattern)
- [Dremio ODBC/JDBC token auth patterns](https://docs.dremio.com/current/security/authentication/) — `$token` as username pattern for PATs (MEDIUM confidence)
- [Keycloak forum: JIT user provisioning](https://forum.keycloak.org/t/just-in-time-user-provisioning/477) — JIT provisioning patterns (MEDIUM confidence)

---

*Feature research for: Keycloak OIDC IdP integration for Dremio OSS v1.5*
*Researched: 2026-03-12*
