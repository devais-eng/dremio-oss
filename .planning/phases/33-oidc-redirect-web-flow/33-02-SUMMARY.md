---
phase: 33-oidc-redirect-web-flow
plan: "02"
subsystem: auth
tags: [oidc, keycloak, oauth2-oidc-sdk, pkce, jax-rs, api-resource, jit-provisioning, role-sync, session-token]

# Dependency graph
requires:
  - phase: 33-01-oidc-stores-foundation
    provides: OidcStateStore and OidcSessionStore singletons bound in DACDaemonModule keycloak branch, oauth2-oidc-sdk 11.20 on classpath
  - phase: 32-jit-provisioning-role-mapping
    provides: JitUserProvisioner and KeycloakRoleSyncer bound in DACDaemonModule keycloak branch
  - phase: 30-jwt-validation-infrastructure-config
    provides: OidcTokenValidator and KeycloakConfig bound in DACDaemonModule keycloak branch
provides:
  - OidcResource JAX-RS resource registered under /api/v3/oidc/* via @APIResource classpath scan
  - GET /api/v3/oidc/login — 302 redirect to Keycloak with PKCE code_challenge (OIDC-01, OIDC-03)
  - GET /api/v3/oidc/callback — full OIDC code exchange flow: code exchange -> JIT provision -> role sync -> homespace -> Dremio token mint -> 302 to landing page (OIDC-02, LOUT-02)
  - OidcTokenExchangeResult inner class for testable token exchange extraction
affects:
  - 34-ui-sso-button (consumes /api/v3/oidc/login endpoint and /login/sso/landing redirect target)
  - 35-rp-initiated-logout (reads OidcSessionStore populated by callback; consumes id_token_hint)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Protected exchangeCodeForTokens() method for spy-based test mocking without interface overhead"
    - "Static inner OidcTokenExchangeResult class as a package-private DTO — avoids HTTP in unit tests"
    - "Response.status(FOUND).location(...) for 302 redirect — semantically clearer than temporaryRedirect() which returns 307"
    - "lenient() Mockito stubs in @BeforeEach for keycloakConfig getters — avoids UnnecessaryStubbingException when some tests null out the oidcStateStore field"

key-files:
  created:
    - dac/backend/src/main/java/com/dremio/dac/resource/OidcResource.java
    - dac/backend/src/test/java/com/dremio/dac/resource/TestOidcResource.java
  modified: []

key-decisions:
  - "Response.status(FOUND) for 302 instead of Response.temporaryRedirect() (307) — browsers treat them differently; 302 is the conventional redirect for auth flows"
  - "No @Secured on OidcResource — these endpoints are intentionally unauthenticated browser endpoints used before the user has a Dremio session token"
  - "Protected exchangeCodeForTokens() for spy-based mocking — avoids adding a TokenExchangeClient interface just for test isolation; spy pattern is lighter"
  - "lenient() stubs in setUp() for keycloakConfig getters — avoids UnnecessaryStubbingException in tests that null out oidcStateStore (keycloak disabled case)"

patterns-established:
  - "Response.status(Response.Status.FOUND).location(uri).build() for 302 redirects in auth flows"
  - "Spy-stub pattern for protected HTTP-calling methods: extract method -> spy -> doReturn(stubResult)"
  - "Lenient Mockito stubs in setUp() when some tests replace @Nullable fields with null to test disabled-service branches"

requirements-completed: [OIDC-01, OIDC-02, OIDC-03, LOUT-02]

# Metrics
duration: 9min
completed: 2026-03-12
---

# Phase 33 Plan 02: OidcResource OIDC Login and Callback Endpoints Summary

**OIDC Authorization Code Flow with PKCE: GET /api/v3/oidc/login initiates Keycloak redirect, GET /api/v3/oidc/callback exchanges code for tokens, JIT-provisions user, syncs roles, mints Dremio session, stores id_token for RP-Initiated Logout**

## Performance

- **Duration:** 9 min
- **Started:** 2026-03-12T18:51:44Z
- **Completed:** 2026-03-12T19:00:49Z
- **Tasks:** 1 TDD task (2 commits: test RED + feat GREEN)
- **Files modified:** 2

## Accomplishments

- OidcResource with `@APIResource @Path("/oidc")`, no `@Secured` — properly registered under /api/v3/oidc/* and accessible without authentication
- `GET /api/v3/oidc/login` generates PKCE code_verifier+state, builds Nimbus AuthorizationRequest with S256 code challenge, stores state in OidcStateStore, returns 302 to Keycloak auth endpoint
- `GET /api/v3/oidc/callback` validates one-time-use state, exchanges code via Nimbus TokenRequest, validates access token via OidcTokenValidator, JIT-provisions user, syncs roles, ensures homespace, mints Dremio session token, stores id_token in OidcSessionStore (LOUT-02), redirects 302 to /login/sso/landing#token=...
- 10 unit tests covering all success and error paths (OIDC-01, OIDC-02, OIDC-03, LOUT-02)

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Add failing tests for OidcResource login and callback endpoints** - `84d56ec45` (test)
2. **Task 1 GREEN: Implement OidcResource OIDC login and callback endpoints** - `b0afe9bf1` (feat)

_Note: TDD task has two commits (test RED then feat GREEN)_

## Files Created/Modified

- `dac/backend/src/main/java/com/dremio/dac/resource/OidcResource.java` - JAX-RS resource with login+callback OIDC endpoints, protected exchangeCodeForTokens(), and OidcTokenExchangeResult inner class
- `dac/backend/src/test/java/com/dremio/dac/resource/TestOidcResource.java` - 10 Mockito unit tests using reflection injection and spy pattern for token exchange isolation

## Decisions Made

- `Response.status(Response.Status.FOUND)` (302) instead of `Response.temporaryRedirect()` (307) — JAX-RS `temporaryRedirect()` returns 307, but browser auth redirects conventionally use 302; tests expected 302 per plan spec
- `protected exchangeCodeForTokens()` method pattern enables spy-based stubbing without adding a separate interface or constructor injection for a single HTTP call
- Mockito `lenient()` stubs for `keycloakConfig` getters in `@BeforeEach` — required because `testLoginKeycloakNotConfiguredReturns503` nulls out `oidcStateStore` and returns before keycloakConfig is accessed, triggering strict mode UnnecessaryStubbingException

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed HTTP redirect status 307 → 302 for login and callback endpoints**
- **Found during:** Task 1 GREEN (test execution)
- **Issue:** JAX-RS `Response.temporaryRedirect()` returns HTTP 307 (Temporary Redirect), but the plan specifies 302 and browser auth flows conventionally use 302; tests failed with `expected 302 but was 307`
- **Fix:** Changed `Response.temporaryRedirect(uri).build()` to `Response.status(Response.Status.FOUND).location(uri).build()` in both `initiateLogin()` and `handleCallback()`
- **Files modified:** OidcResource.java
- **Verification:** All 10 tests pass; login and callback both return 302
- **Committed in:** `b0afe9bf1` (Task 1 GREEN commit)

**2. [Rule 1 - Bug] Fixed UnnecessaryStubbingException for keycloakConfig stubs in setUp()**
- **Found during:** Task 1 GREEN (test execution)
- **Issue:** Mockito strict mode rejects stubs in `@BeforeEach` that aren't consumed by every test; `testLoginKeycloakNotConfiguredReturns503` returns 503 before calling `keycloakConfig.getIssuerUrl()` etc., causing 8 test failures
- **Fix:** Changed `when(keycloakConfig.getX()).thenReturn(...)` to `lenient().when(keycloakConfig.getX()).thenReturn(...)` in `setUp()`
- **Files modified:** TestOidcResource.java
- **Verification:** All 10 tests pass with zero unnecessary-stubbing errors
- **Committed in:** `b0afe9bf1` (Task 1 GREEN commit, after spotless)

---

**Total deviations:** 2 auto-fixed (Rule 1 - Bug: 302 vs 307 redirect status; Rule 1 - Bug: Mockito lenient stubs)
**Impact on plan:** Both fixes necessary for correctness and test compliance. No scope creep.

## Issues Encountered

- `TestRbacIntegration` has a **pre-existing** HK2 injection failure (UnsatisfiedDependencyException for OidcTokenValidator/JitUserProvisioner/KeycloakRoleSyncer in DACAuthFilter) that predates this plan — verified by stashing our changes and confirming the same failure. Not related to OidcResource.

## Next Phase Readiness

- Phase 34 (UI SSO Button): `/api/v3/oidc/login` endpoint is live; the UI SSO button only needs to navigate to this URL; `/login/sso/landing` landing page needs to extract the `#token=...` fragment and call `setUserData()`
- Phase 35 (RP-Initiated Logout): `OidcSessionStore` is populated by callback with `(dremioToken, idToken)` mapping; logout endpoint can call `oidcSessionStore.get(dremioToken)` to retrieve `id_token_hint` for Keycloak's `/protocol/openid-connect/logout` endpoint

---
*Phase: 33-oidc-redirect-web-flow*
*Completed: 2026-03-12*
