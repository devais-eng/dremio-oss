# Project Research Summary

**Project:** Dremio OSS — v1.5 Keycloak IdP Integration
**Domain:** OIDC Identity Provider integration into a Java/Jersey data platform with existing RBAC
**Researched:** 2026-03-12
**Confidence:** HIGH

## Executive Summary

Dremio OSS v1.5 adds Keycloak as a pluggable OIDC identity provider to a system that already has a complete internal-auth and deny-by-default RBAC stack (v1.0–v1.4). The integration is narrowly scoped to authentication: validate Keycloak-issued JWTs, initiate the OIDC authorization code flow from the backend, JIT-provision users on first login, and map Keycloak realm roles to Dremio RBAC roles. The authorization layer (RbacService, KVStore grants) stays entirely unchanged. All required JWT and OIDC libraries are already version-managed in the root pom.xml — no net-new dependencies are needed in the Maven build.

The recommended approach is a backend-driven OIDC flow: the UI adds a single "Login with SSO" button that hits a new `GET /api/v3/oidc/login` endpoint; Keycloak authenticates the user and redirects to a `GET /api/v3/oidc/callback` endpoint that exchanges the code for tokens, provisions the user if needed, syncs realm roles to RBAC, mints a standard Dremio opaque session token, and redirects the browser to the frontend. After that point the client carries a normal Dremio token and nothing downstream changes. REST and Arrow Flight clients presenting Keycloak Bearer JWTs are validated stateless-ly via a new `OidcTokenValidator` plugged into `DACAuthFilter` as a pre-check before the existing opaque token path.

The primary risks are: CSRF protection for the OIDC redirect flow (state parameter required); the token type ambiguity between Keycloak JWTs and Dremio opaque tokens in `DACAuthFilter` (discriminate on `eyJ` prefix); backward compatibility for internal admin users (the username/password form must remain active alongside SSO); JIT provisioning race conditions on concurrent first logins; and Keycloak role mapping silently overwriting manually-assigned RBAC roles. All of these have well-understood mitigations documented in PITFALLS.md and all follow patterns already present in the Dremio codebase.

---

## Key Findings

### Recommended Stack

The Dremio codebase already contains everything needed. `nimbus-jose-jwt` 9.41 is a compile dependency of `services/tokens`. `oauth2-oidc-sdk` 11.20 is declared in the root `pom.xml` dependency management block but not yet pulled as a compile dependency anywhere — the only Maven change needed is adding it to `dac/backend/pom.xml`. No Keycloak-specific Java adapters (keycloak-adapter-core, spring-security-oauth2-resource-server, pac4j) should be added; they pull Spring Security as a transitive dependency, conflicting with Dremio's Jersey/Guice setup. On the frontend, no new npm packages are needed: the SSO button performs a simple `window.location.assign(url)` to the backend-generated Keycloak redirect URL.

See `.planning/research/STACK.md` for the full integration point manifest and alternatives considered.

**Core technologies:**
- `nimbus-jose-jwt` 9.41: JWT validation via `RemoteJWKSet` + `DefaultJWTProcessor` — already in build, zero new dependencies
- `oauth2-oidc-sdk` 11.20: OIDC discovery and authorization code exchange — already version-managed, one `dac/backend/pom.xml` line to activate
- Jersey/JAX-RS (existing): new `OidcCallbackResource` for the OIDC redirect and callback endpoints — consistent with all other REST resources in the codebase
- Guice/SingletonRegistry (existing): DI wiring for new OIDC services — follows established `DACDaemonModule` binding pattern
- KVStore/RocksDB (existing): OIDC state parameter storage (short-lived TTL entries keyed by state UUID) — same pattern as existing opaque token storage

**What NOT to add:**
- `keycloak-adapter-core` or any Spring Security OAuth2 adapter — Spring classpath conflicts with Jersey/Guice
- `io.jsonwebtoken:jjwt-*` or `com.auth0:java-jwt` — Nimbus already in the build; adding a second JWT library creates version drift risk
- `keycloak-js` on the frontend — approaching deprecation; vendor-locked; not needed for backend-driven OIDC flow

### Expected Features

See `.planning/research/FEATURES.md` for the full feature dependency graph and prioritization matrix.

**Must have (table stakes):**
- `services.coordinator.web.auth.type = "oidc"` config gating — all other features depend on this; follows existing `WEB_AUTH_TYPE` dispatch in `DACDaemonModule`
- OIDC Authorization Code Flow: `/api/v3/oidc/login` (redirect) + `/api/v3/oidc/callback` (code exchange + JIT + Dremio token) — required for Web UI users; `SSO_LANDING_PATH` stub already exists in `loginLogout.js`
- Keycloak JWT Bearer validation in `DACAuthFilter` — required for REST API clients presenting Keycloak access tokens directly
- JIT user provisioning on first login via `UserService.createUser()` with a locked sentinel password — required so Keycloak users exist in the KVStore
- Keycloak `realm_access.roles` → Dremio RBAC role sync on login — required for authorization to work for Keycloak users
- Keycloak token acceptance in Arrow Flight / `DremioBearerTokenAuthenticator` — required for JDBC/ODBC BI tool access
- Startup config validation (fetch `/.well-known/openid-configuration`) — fast-fail on misconfiguration

**Should have (differentiators):**
- Configurable role name prefix/mapping (`services.keycloak.role.prefix`) — reduces operator friction with Keycloak naming conventions; LOW complexity, MEDIUM value
- JWKS key rotation with automatic `kid`-based cache refresh — prevents outages during Keycloak key rotation; needs to be designed in Phase 1 even if tuning is v1.x
- Token exchange path: `POST /api/v3/login` accepting Keycloak Bearer token → Dremio session token — simplifies automation scripts
- Configurable Dremio session TTL for OIDC sessions — aligns Dremio session lifetime with Keycloak SSO session idle timeout

**Defer (v2+):**
- RP-Initiated Logout (Single Logout with Keycloak `end_session_endpoint`) — requires storing `id_token_hint` per session; HIGH complexity, LOW initial demand
- Multiple concurrent IdPs — deferred until use case is established
- SCIM user sync from Keycloak — JIT provisioning is sufficient for v1

### Architecture Approach

The integration adds a thin OIDC layer above the existing auth infrastructure without modifying its contracts. `DACAuthFilter` gets a pre-check branch: if `isOidcAuth()` and the Bearer token is a three-part base64 JWT, delegate to `OidcTokenValidator`; on failure, fall through to the existing `tokenManager.validateToken()` opaque path. After successful validation, `userService.getUser(userName)` and `DACSecurityContext` construction are unchanged. A new `OidcCallbackResource` JAX-RS resource handles the web redirect flow and calls `OidcJitProvisioner` for user creation and role sync. All new business logic lives in `dac/backend/service/oidc/`; JWT validation infrastructure lives in `services/tokens/oidc/` alongside the existing `JWTValidatorImpl`.

See `.planning/research/ARCHITECTURE.md` for complete data flow diagrams for all three client paths (Web UI, REST API, Arrow Flight) and the exact call graphs.

**Major components:**
1. `OidcTokenValidator` (`services/tokens/oidc/`) — validates Keycloak RS256 JWTs against `RemoteJWKSet`; returns `TokenDetails{username, expiry}`; uses `preferred_username` claim (not `sub`)
2. `KeycloakJwksProvider` (`services/tokens/oidc/`) — wraps nimbus `RemoteJWKSet` with config-driven URL and `kid`-based refresh; does NOT reuse `JWTProcessorFactory` (hardcoded ES256/ImmutableJWKSet — wrong for Keycloak)
3. `OidcCallbackResource` (`dac/backend/resource/`) — JAX-RS resource for `/api/v3/oidc/login` (302 redirect) and `/api/v3/oidc/callback` (code exchange, JIT, Dremio token issuance, browser redirect)
4. `OidcJitProvisioner` (`dac/backend/service/oidc/`) — idempotent user creation + Keycloak realm role sync; calls existing `UserService.createUser()` and `RbacService.addMembership()` with system privileges; skips role sync when `services.rbac.enabled=false`
5. `KeycloakRoleMapper` (`dac/backend/service/oidc/`) — translates Keycloak `realm_access.roles` to Dremio RBAC role IDs using configurable mapping; ignores unmapped roles; uses additive-tagged sync to preserve manually-assigned memberships
6. `DACAuthFilter` (modified) — adds `isOidcAuth() && looksLikeJwt(token)` branch before existing opaque token path; falls through on validation failure for backward compatibility
7. `DremioBearerTokenAuthenticator` (modified) — adds parallel `looksLikeJwt()` branch routing to `OidcTokenValidator` before existing opaque token path
8. `DACDaemonModule` (modified) — adds `else if (dacConfig.isOidcAuth())` branch in `buildUserService()` binding OIDC services and registering `OidcCallbackResource`

### Critical Pitfalls

The PITFALLS.md covers 41 pitfalls across v1.0 (P1–P14), v2.0 (P15–P27), and v1.5 (P28–P41). The v1.5-specific pitfalls in implementation priority order:

1. **JWT issuer + audience not validated (P28)** — configure `DefaultJWTClaimsVerifier` with exact `iss` and `aud` match from config; without this any Keycloak realm's token is accepted. Must be in Phase 1 before any token is accepted.

2. **Token type ambiguity: Keycloak JWT vs Dremio opaque token (P41)** — discriminate on `eyJ` prefix (three-part base64 JWT) vs base-32 opaque token before calling any validator; adding Keycloak logic inside `TokenManagerImpl.validateToken()` violates single-responsibility and entangles the token manager with Keycloak config. Must be in Phase 1 design.

3. **CSRF state parameter missing from OIDC callback (P33)** — generate 128-bit random `state` using `SecureRandom`; store in KVStore with 5-minute TTL; validate on callback; omitting this enables OAuth code injection (login CSRF). Must be in Phase 4 (OIDC redirect flow).

4. **JIT provisioning required before first REST call, not only in callback (P34)** — `DACAuthFilter` calls `userService.getUser()` after token validation; if the user doesn't exist (new Keycloak user accessing REST directly without prior UI login), the call throws `UserNotFoundException` and returns 401. JIT must run in `DACAuthFilter` (or in the token validator as a side effect) for the REST/Flight paths, not only in `OidcCallbackResource`.

5. **Backward compatibility: internal admin must remain accessible (P38)** — enabling OIDC must not replace `LocalUsernamePasswordAuthProvider`; keep it active as a fallback; UI must show both "Login with SSO" and the username/password form simultaneously.

6. **Keycloak role mapping overwrites manually assigned RBAC roles (P32)** — use additive-plus-tagged sync: add memberships tagged `source=keycloak`; on re-login remove only `source=keycloak` memberships before re-adding from token; preserve memberships with other sources. Decide and document the model before coding starts.

7. **JWKS cache not refreshed after key rotation (P30)** — implement `kid`-based refresh: before rejecting an unknown `kid`, attempt a JWKS re-fetch once; use 1–4 hour TTL (not the existing 24-hour TTL used for internal JWKs). Design in Phase 1 even if the Keycloak key rotation test is deferred.

8. **Arrow Flight bypasses Keycloak JWT validation (P35)** — `DremioBearerTokenAuthenticator.validateBearer()` only calls `tokenManager.validateToken()` (KVStore lookup); Keycloak JWTs are not in the KVStore and fail silently. Must be explicitly handled in Phase 6.

9. **JIT provisioning race condition on concurrent first logins (P31)** — `check-then-create` pattern in `SimpleUserService` is not atomic; two concurrent first logins attempt `createUser()` simultaneously. Prevention: catch-and-ignore `UserAlreadyExistException` — never check-then-create.

10. **Keycloak admin users not getting ADMIN membership (P40)** — `addMembership("ADMIN", ...)` must use a direct `MembershipStore.add()` call with `grantedBy="SYSTEM"`, not the RBAC-gated REST API or `RbacService.addMembership()` which requires the caller to already be ADMIN.

---

## Implications for Roadmap

Based on the combined research, the architecture document's suggested 6-phase build order is well-grounded in the codebase. Each phase compiles and deploys independently without breaking existing internal auth.

### Phase 1: JWT Validation Infrastructure + Config

**Rationale:** All other phases depend on a working `OidcTokenValidator`. This is pure library code with no wiring to existing classes — it can be built and unit-tested in isolation with a static test JWKS. Config constants and `isOidcAuth()` must exist before any other phase can compile. The `kid`-based JWKS refresh design must be established here even if Keycloak key rotation testing is deferred.

**Delivers:** `OidcTokenValidator.validate(jwtString)` returns `TokenDetails` or throws; `KeycloakJwksProvider` wraps nimbus `RemoteJWKSet` with config-driven URL and `kid`-based refresh; `DefaultJWTClaimsVerifier` configured with exact `iss`/`aud` match and 30-second clock skew tolerance; `OidcConfig` typed config wrapper; `DremioConfig` OIDC constants; `DACConfig.isOidcAuth()`; `DACDaemonModule` `oidc` branch (coordinator starts with `auth.type=oidc` without crashing, no functional auth yet); `dremio-reference.conf` OIDC config section; `dac/backend/pom.xml` `oauth2-oidc-sdk` dependency added

**Addresses:** Config + startup validation (table stakes), JWKS infrastructure (differentiator)

**Avoids:** P28 (issuer/audience baked in), P29 (clock skew tolerance), P30 (kid-based refresh design), P41 (token type discriminator established), P38 (no change to existing auth path yet)

**Research flag:** Skip research-phase. All nimbus APIs verified in codebase. `RemoteJWKSet` + `DefaultJWTProcessor` pattern confirmed via `JWTValidatorImpl` in `services/tokens`.

### Phase 2: DACAuthFilter OIDC Branch — REST API Bearer JWT

**Rationale:** Directly depends on Phase 1. Minimum slice that makes Keycloak JWT auth work for REST API clients against pre-existing users. No JIT provisioning yet — documents the "user must pre-exist" constraint as a temporary limitation to be lifted in Phase 3.

**Delivers:** `DACAuthFilter` modified with `isOidcAuth() && looksLikeJwt()` branch; `curl -H "Authorization: Bearer {KC_JWT}"` works for pre-provisioned users; 401 for unknown users (documented, expected); opaque Dremio token path completely unchanged

**Addresses:** Keycloak JWT Bearer validation for REST API (table stakes P1 feature)

**Avoids:** P41 (token type ambiguity resolved at discriminator), P28 (issuer check active via Phase 1 validator)

**Research flag:** Skip research-phase. Integration point verified: single `tokenManager.validateToken()` call in `DACAuthFilter.getUserNameFromToken()` is the choke point.

### Phase 3: JIT Provisioning + Role Mapping

**Rationale:** Depends on Phase 2 (establishes the DACAuthFilter integration point). JIT provisioning is a prerequisite for Phase 4 (the OIDC callback calls the provisioner) and for REST/Flight API clients who have never logged in via the UI (P34). The role mapping semantic decision (additive-tagged) must be locked in here.

**Delivers:** `OidcJitProvisioner` (idempotent `createUser` + additive-tagged role sync); JIT triggered in `DACAuthFilter` on `UserNotFoundException` for Keycloak tokens (P34 fix); `KeycloakRoleMapper` with configurable mappings; ADMIN membership provisioned via direct `MembershipStore.add()` with system privileges (P40); REST API clients with Keycloak tokens work on first call; role sync skips when `services.rbac.enabled=false`

**Addresses:** JIT user provisioning (table stakes), Keycloak realm role → RBAC sync (table stakes)

**Avoids:** P31 (race: catch-and-ignore `UserAlreadyExistException`), P32 (additive-tagged sync preserves manual grants), P34 (DACAuthFilter triggers JIT before `getUser()`), P37 (use `preferred_username` for display; document Keycloak username stability constraint), P40 (ADMIN membership via system-privilege path)

**Research flag:** Skip research-phase. `UserService.createUser()` and `RbacService.addMembership()` signatures verified. Idempotency and tagged-membership patterns are standard for this codebase.

### Phase 4: OIDC Redirect Web UI Flow

**Rationale:** Depends on Phase 3 (JIT must work before the callback can provision). Highest-complexity phase: introduces server-side state management (OIDC state parameter stored in KVStore) and a token exchange HTTP call to Keycloak. The re-validation strategy for Keycloak session revocation (P36) must be decided before this phase is designed.

**Delivers:** `OidcCallbackResource` with `GET /api/v3/oidc/login` (302 redirect with state generation) and `GET /api/v3/oidc/callback` (state validation, code exchange via `oauth2-oidc-sdk`, JIT, Dremio session token via `tokenManager.createToken()`, browser redirect to `/login/sso/landing`); OIDC state stored in KVStore with 5-minute TTL; configurable Dremio session TTL for OIDC sessions; `UserLoginSession` response shape preserved (same contract as internal auth)

**Addresses:** OIDC Authorization Code Flow for Web UI (table stakes), Dremio form-based login coexistence (table stakes)

**Avoids:** P33 (CSRF: 128-bit SecureRandom state, KVStore storage, exact validation on callback), P36 (configurable session TTL; document re-validation window decision), P38 (internal auth path entirely unchanged)

**Research flag:** Skip research-phase for the code-exchange mechanics (oauth2-oidc-sdk patterns standard). May need brief investigation into the `SSO_LANDING_PATH` stub in `loginLogout.js` and `localStorageUtils.setUserData()` to confirm the exact token delivery mechanism (URL fragment vs cookie) before writing the implementation plan for `OidcCallbackResource`'s redirect.

### Phase 5: Web UI "Login with SSO" Button

**Rationale:** Depends on Phase 4 (backend endpoints must exist first). Smallest phase — UI-only changes plus a config-discovery endpoint so the UI can decide dynamically whether to show the SSO button.

**Delivers:** `SsoButton.jsx` component; `LoginForm.jsx` modified to show SSO button alongside the existing username/password form (both always visible); `GET /api/v3/oidc/enabled` endpoint or OIDC flag in server info response; `SSO_LANDING_PATH` (`/login/sso/landing`) route implemented in `loginLogout.js` to read token from URL and complete login saga; internal login form remains fully functional as emergency fallback

**Addresses:** SSO button in Web UI (table stakes), internal admin coexistence (table stakes)

**Avoids:** P38 (both login paths visible simultaneously; SSO button is additive, not a replacement)

**Research flag:** Needs phase research. The Redux login saga in `loginLogout.js` and the interaction between `SSO_LANDING_PATH` and `localStorageUtils.setUserData()` should be traced before writing the implementation plan. MEDIUM confidence on the exact frontend token delivery and login completion flow.

### Phase 6: Arrow Flight OIDC Bearer Token — JDBC/ODBC

**Rationale:** Depends on Phase 3 (JIT provisioning must be in place). Independent of Phases 4 and 5 — can run in parallel with Phase 4 if bandwidth allows. This is the last integration surface and completes the three client paths.

**Delivers:** `DremioBearerTokenAuthenticator.authenticate()` modified with `isOidcAuth() && looksLikeJwt()` branch routing to `OidcTokenValidator`; Dremio session token issued after successful KC JWT validation; JDBC/ODBC BI tool connections work with Keycloak access tokens; P39 (5-minute token TTL incompatibility with long-running sessions) documented with mitigation: recommend exchanging Keycloak access token for a Dremio session token via `POST /apiv2/login` for long-running BI tool connections

**Addresses:** Keycloak token acceptance in Arrow Flight / JDBC/ODBC (table stakes)

**Avoids:** P35 (Flight JWT bypass explicitly fixed), P39 (limitation documented with a concrete workaround)

**Research flag:** Skip research-phase. `DremioBearerTokenAuthenticator.validateBearer()` extension point verified. Pattern mirrors Phase 2 DACAuthFilter modification.

### Phase Ordering Rationale

- Phase 1 before everything: `OidcTokenValidator` and `isOidcAuth()` are compile-time prerequisites for all other phases
- Phase 2 before Phase 3: establishes the `DACAuthFilter` integration point that Phase 3 extends with JIT provisioning
- Phase 3 before Phase 4: `OidcCallbackResource` delegates to `OidcJitProvisioner`; JIT must be ready and tested
- Phase 4 before Phase 5: the UI SSO button has nothing to call without the backend redirect and callback endpoints
- Phase 6 is independent of Phases 4–5 and can run in parallel with Phase 4 if resourcing allows
- Internal auth (`LocalUsernamePasswordAuthProvider`, `POST /api/v2/login`, `DACAuthFilter` opaque token path) is untouched in every phase — the OIDC integration is entirely additive

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 5 (UI SSO button):** The `SSO_LANDING_PATH` stub and Redux login saga token delivery pattern should be traced before writing the implementation plan. Specifically: how does the existing saga store the token from a login redirect? Does `localStorageUtils.setUserData()` accept a token from a URL fragment? MEDIUM confidence on this specific flow.

Phases with standard patterns (skip research-phase):
- **Phase 1:** `RemoteJWKSet` + `DefaultJWTProcessor` + `DefaultJWTClaimsVerifier` is a well-documented, verified pattern. All library APIs confirmed in codebase alongside existing `JWTValidatorImpl`.
- **Phase 2:** Single filter branch change. `DACAuthFilter.getUserNameFromToken()` is the verified single choke point.
- **Phase 3:** `UserService.createUser()` and `RbacService.addMembership()` signatures verified. Idempotency and tagged-membership patterns are standard.
- **Phase 4:** `oauth2-oidc-sdk` token exchange API is standard; state-in-KVStore follows existing TTL token entry pattern.
- **Phase 6:** `DremioBearerTokenAuthenticator` extension point verified; pattern mirrors Phase 2.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Both `nimbus-jose-jwt` and `oauth2-oidc-sdk` versions verified in pom.xml. Frontend requires no npm packages. No new Maven dependencies except one line in `dac/backend/pom.xml`. |
| Features | HIGH | All seven integration points (DACAuthFilter, TokenManager, DremioBearerTokenAuthenticator, LogInLogOutResource, DACDaemonModule, DACConfig, loginLogout.js) verified by direct source inspection. `SSO_LANDING_PATH` stub confirmed in `loginLogout.js`. |
| Architecture | HIGH | All class names, method signatures, and file paths verified against production source code on branch `features/keycloak`. Build order derived from actual compile dependencies. Data flow for all three client paths (Web UI, REST API, Arrow Flight) fully traced. |
| Pitfalls | HIGH | 14 v1.5-specific pitfalls (P28–P41) verified against source code. Most trace to specific file+method combinations. P37 (`preferred_username` stability) is a known tradeoff, not an unknown risk. |

**Overall confidence:** HIGH

### Gaps to Address

- **Frontend token delivery mechanism (Phase 5):** The exact mechanism for passing the Dremio session token from the backend callback to the browser (URL fragment `#token=...` vs secure cookie vs URL query param) needs to be confirmed by tracing `SSO_LANDING_PATH` in `loginLogout.js` and `localStorageUtils.setUserData()`. URL query params appear in server logs and browser history; URL fragments do not reach the server; cookies require SameSite handling. The choice has security and UX implications.

- **`preferred_username` vs `sub` identity claim (acknowledged tradeoff — P37):** The codebase keys users by username string throughout `UserService` and `MembershipStore`. Using Keycloak `sub` (stable UUID) as the Dremio identity would require a mapping table or schema migration. For v1.5, `preferred_username` is the pragmatic choice with an explicit operator constraint documented: "do not rename Keycloak users without running the provided migration procedure." The `sub`-based stable identity model is the v2+ improvement.

- **Keycloak session revocation propagation window (P36):** The re-validation strategy for the UI OIDC flow must be decided before Phase 4 is designed. Options: (a) short session TTL (30–60 min, matching Keycloak SSO session idle timeout), (b) periodic background re-validation against Keycloak's token endpoint, (c) accept up to N-hour lag on deprovisioning. This is a security policy decision that needs operator input or an explicit product decision, not a technical gap.

- **ODBC/JDBC long-running session mitigation (P39):** Keycloak's default 5-minute access token TTL is incompatible with BI tool live connections. The v1.5 mitigation is documentation: recommend that BI tools and ETL clients exchange their Keycloak access token for a Dremio session token via `POST /apiv2/login` before establishing a long-running JDBC connection. A proper fix (server-side refresh token handling in the Flight authenticator) is a v1.x follow-on.

- **Additive-tagged membership proto change:** The `source=keycloak` tag on `Membership` records requires a proto schema change to the existing `Membership` message. This needs verification against the current `rbac.proto` definition before Phase 3 coding starts, to confirm whether it requires a migration or only a schema addition.

---

## Sources

### Primary (HIGH confidence — direct codebase inspection)
- `DACAuthFilter.java` — token validation pipeline, `getUserNameFromToken()`, single `tokenManager.validateToken()` choke point verified
- `DACDaemonModule.java` L2216-L2222 — `buildUserService()` auth type dispatch, `WEB_AUTH_TYPE` binding switch verified
- `DACConfig.java` — `isInternalUserAuth()` pattern; `WEB_AUTH_TYPE` constant verified
- `TokenManager.java`, `TokenManagerImplV2.java`, `JWTValidatorImpl.java` — internal JWT validation pipeline; `sub`-as-UID incompatibility with Keycloak confirmed
- `JWTProcessorFactory.java`, `JWKSetManager.java` — ES256/ImmutableJWKSet hardcoding confirmed; must NOT be reused for Keycloak
- `DremioBearerTokenAuthenticator.java` — Flight bearer auth, `validateBearer()` extension point verified
- `LogInLogOutResource.java` — login endpoint contract, `UserLoginSession` shape verified
- `UserService.java`, `SimpleUserService.java` — `createUser()` signature; locked-password pattern; `UserAlreadyExistException` verified
- `RbacService.java` — `addMembership()`, `RbacEntityAlreadyExistsException` verified
- `ViewExpander.java` L127–133 — deleted-definer fallback (P24 referenced)
- `loginLogout.js`, `LoginForm.jsx` — `SSO_LANDING_PATH = "/login/sso/landing"` stub confirmed
- Root `pom.xml` — `nimbus-jose-jwt:9.41`, `oauth2-oidc-sdk:11.20` verified in dependency management
- `services/tokens/pom.xml` — `nimbus-jose-jwt` compile dependency confirmed

### Secondary (MEDIUM confidence — official and community documentation)
- [Keycloak Securing Applications Guide](https://www.keycloak.org/docs/25.0.6/securing_apps/index.html) — OIDC Authorization Code Flow patterns
- [Nimbus JWT access token validation](https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens) — `RemoteJWKSet` + `JWKSourceBuilder` pattern
- [Keycloak OIDC layers](https://www.keycloak.org/securing-apps/oidc-layers) — JWKS endpoint format, token claims structure
- [oauth2-oidc-sdk Maven Central](https://central.sonatype.com/artifact/com.nimbusds/oauth2-oidc-sdk) — version 11.20 confirmed stable
- [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter) — keycloak-js deprecation trajectory confirmed

---
*Research completed: 2026-03-12*
*Ready for roadmap: yes*
