---
phase: 33-oidc-redirect-web-flow
verified: 2026-03-12T00:00:00Z
status: passed
score: 13/13 must-haves verified
re_verification: false
---

# Phase 33: OIDC Redirect Web Flow Verification Report

**Phase Goal:** A browser user can initiate login via Keycloak's authorization code flow, and after Keycloak authentication, land back in Dremio with a valid session token — with CSRF protection throughout
**Verified:** 2026-03-12
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| #  | Truth                                                                                   | Status     | Evidence                                                                                                |
|----|-----------------------------------------------------------------------------------------|------------|---------------------------------------------------------------------------------------------------------|
| 1  | GET /api/v3/oidc/login returns 302 redirect to Keycloak auth endpoint                  | VERIFIED   | OidcResource.initiateLogin() returns `Response.status(FOUND).location(authRequest.toURI())` (line 134) |
| 2  | Redirect URL contains state, code_challenge, code_challenge_method=S256                | VERIFIED   | AuthorizationRequest.Builder with `.state(state).codeChallenge(verifier, S256)` (lines 121-130)        |
| 3  | GET /api/v3/oidc/callback exchanges code, provisions user, syncs roles, mints token     | VERIFIED   | handleCallback() calls JIT, roleSyncer, tokenManager.createToken, redirects to /login/sso/landing      |
| 4  | Callback with tampered or missing state returns 400                                     | VERIFIED   | `removeIfValid(state) == null` → 400; `state == null` → 400 (lines 158-165)                           |
| 5  | Callback with missing code returns 400                                                  | VERIFIED   | `code == null` → 400 (line 158); tested in testCallbackMissingCodeReturns400                           |
| 6  | id_token from token exchange stored in OidcSessionStore keyed by Dremio session token   | VERIFIED   | `oidcSessionStore.put(dremioToken.token, exchangeResult.idToken)` (line 207)                           |
| 7  | When Keycloak auth not configured, login endpoint returns 503                           | VERIFIED   | `if (oidcStateStore == null) return Response.status(503)` (line 111)                                   |
| 8  | OidcStateStore stores (state, codeVerifier) and returns codeVerifier on valid lookup    | VERIFIED   | put/removeIfValid implemented with ConcurrentHashMap; 5 tests pass                                     |
| 9  | OidcStateStore rejects expired state entries (>5 min TTL)                               | VERIFIED   | TTL-based expiry via PendingFlow.isExpired(); testRemoveIfValidExpiredStateReturnsNull passes           |
| 10 | OidcStateStore removes state on retrieval (one-time use, prevents replay)               | VERIFIED   | `pending.remove(state)` (atomic) in removeIfValid(); testRemoveIfValidSameStateTwice passes            |
| 11 | OidcSessionStore stores and retrieves id_token keyed by Dremio session token            | VERIFIED   | ConcurrentHashMap<String,String> with put/get/remove; 4 tests pass                                     |
| 12 | OidcSessionStore removal works for logout use                                           | VERIFIED   | `remove(dremioToken)` method present; testRemoveThenGetReturnsNull passes                              |
| 13 | Both stores bound as singletons in DACDaemonModule keycloak branch                      | VERIFIED   | Lines 2255-2260 in DACDaemonModule: `registry.bind(OidcStateStore.class, ...)` and `OidcSessionStore`  |

**Score:** 13/13 truths verified

---

## Required Artifacts

| Artifact                                                                                              | Expected                                  | Status     | Details                                                                                              |
|-------------------------------------------------------------------------------------------------------|-------------------------------------------|------------|------------------------------------------------------------------------------------------------------|
| `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcStateStore.java`                    | TTL state store, one-time-use             | VERIFIED   | 103 lines; ConcurrentHashMap + PendingFlow; package-private TTL constructor; put/removeIfValid       |
| `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcSessionStore.java`                  | id_token store for RP-Initiated Logout    | VERIFIED   | 65 lines; ConcurrentHashMap; put/get/remove all implemented                                          |
| `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcStateStore.java`                | Unit tests for state store (5 tests)      | VERIFIED   | 5 test methods covering put/remove, unknown state, one-time-use, expiry, lazy cleanup                |
| `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcSessionStore.java`              | Unit tests for session store (4 tests)    | VERIFIED   | 4 test methods covering put/get, unknown, remove, overwrite                                          |
| `dac/backend/src/main/java/com/dremio/dac/resource/OidcResource.java`                               | JAX-RS login + callback endpoints         | VERIFIED   | 278 lines; @APIResource @Path("/oidc"); no @Secured; initiateLogin() + handleCallback() implemented  |
| `dac/backend/src/test/java/com/dremio/dac/resource/TestOidcResource.java`                           | 10 unit tests with Mockito mocks          | VERIFIED   | 10 test methods; spy pattern for token exchange; reflection injection; all paths covered              |

---

## Key Link Verification

| From                  | To                                  | Via                                               | Status   | Details                                                                   |
|-----------------------|-------------------------------------|---------------------------------------------------|----------|---------------------------------------------------------------------------|
| DACDaemonModule.java  | OidcStateStore.java                 | `registry.bind(OidcStateStore.class, new OidcStateStore())` | WIRED  | Lines 2256-2257 in keycloak branch; imports at lines 278-279            |
| DACDaemonModule.java  | OidcSessionStore.java               | `registry.bind(OidcSessionStore.class, new OidcSessionStore())` | WIRED | Lines 2259-2260 in keycloak branch; import at line 278                 |
| OidcResource.java     | OidcStateStore.java                 | `@Inject @Nullable` field; oidcStateStore.put() + removeIfValid() | WIRED | Lines 83, 132, 162 — both methods called in production code           |
| OidcResource.java     | OidcSessionStore.java               | `@Inject @Nullable` field; oidcSessionStore.put() | WIRED  | Lines 85, 207 — stored after successful token exchange                   |
| OidcResource.java     | Keycloak token endpoint             | `tokenRequest.toHTTPRequest().send()` (line 240)  | WIRED    | Wrapped in protected exchangeCodeForTokens() for test isolation           |
| OidcResource.java     | OidcTokenValidator.java             | `@Inject @Nullable`; oidcTokenValidator.validateWithClaims() | WIRED | Line 182 — access token validated after token exchange                |
| OidcResource.java     | JitUserProvisioner.java             | `@Inject @Nullable`; jitUserProvisioner.provision() | WIRED  | Line 189 — JIT provision called with username+email from token details   |
| OidcResource.java     | TokenManager                        | `@Inject`; tokenManager.createToken()             | WIRED    | Line 205 — mints Dremio session token; result used for landing redirect   |

---

## Requirements Coverage

| Requirement | Source Plan | Description                                                                                  | Status    | Evidence                                                                      |
|-------------|-------------|----------------------------------------------------------------------------------------------|-----------|-------------------------------------------------------------------------------|
| OIDC-01     | 33-02       | Web UI user can click "Login with SSO" and be redirected to Keycloak (Authorization Code Flow) | SATISFIED | GET /api/v3/oidc/login returns 302 to `{issuerUrl}/protocol/openid-connect/auth` with full OAuth2 params |
| OIDC-02     | 33-02       | After Keycloak authentication, callback exchanges code for tokens and mints a Dremio session token | SATISFIED | handleCallback() completes full code exchange -> JIT -> role sync -> tokenManager.createToken() -> 302 to /login/sso/landing#token= |
| OIDC-03     | 33-01, 33-02 | OIDC flow uses state parameter + PKCE for CSRF protection                                    | SATISFIED | OidcStateStore provides one-time-use, TTL-enforced state storage; code_challenge with S256 in AuthorizationRequest |
| LOUT-02     | 33-01, 33-02 | Logout stores id_token_hint during OIDC callback for use at logout time                      | SATISFIED | oidcSessionStore.put(dremioToken.token, exchangeResult.idToken) at OidcResource line 207; OidcSessionStore.get() available for Phase 35 |

All 4 requirements for Phase 33 are satisfied. No orphaned requirements detected.

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | None | — | No stubs, placeholders, empty implementations, or TODO/FIXME found in phase artifacts |

The `return null` at OidcResource line 253 is a legitimate "Keycloak returned an error response" branch inside `exchangeCodeForTokens()`, not a stub — the caller handles it with a 502 response.

---

## Human Verification Required

### 1. End-to-End Browser Flow

**Test:** With a running Keycloak instance configured, open a browser and navigate to `GET /api/v3/oidc/login`. Follow the redirect to Keycloak, authenticate, and observe the callback.
**Expected:** Browser is redirected to `/login/sso/landing#token=<valid-dremio-token>` after authenticating with Keycloak.
**Why human:** Requires a live Keycloak instance and browser; the Nimbus `toHTTPRequest().send()` code exchange path is not covered by unit tests (it is stubbed via spy).

### 2. Token Fragment Security

**Test:** Observe the Network tab in browser DevTools during the callback redirect.
**Expected:** The Dremio session token is delivered in the URL fragment (`#token=...`), not as a query parameter (`?token=...`), ensuring the token is not sent to the server in subsequent requests or logged in server access logs.
**Why human:** Requires browser DevTools observation to confirm fragment vs. query parameter behavior.

### 3. Concurrent Flow Isolation

**Test:** Initiate two OIDC login flows simultaneously from two browser tabs. Complete both flows.
**Expected:** Each flow completes independently with its own session token; neither flow can use the other's state nonce.
**Why human:** Concurrency behavior under real browser load requires runtime testing.

---

## Commit Verification

All 5 commits from phase summaries confirmed in git history:

| Commit    | Description                                                          |
|-----------|----------------------------------------------------------------------|
| 54426fcf0 | test(33-01): add failing tests for OidcStateStore and OidcSessionStore |
| 6712f8cc8 | feat(33-01): implement OidcStateStore and OidcSessionStore           |
| 718b452db | feat(33-01): add oauth2-oidc-sdk dep and wire OidcStateStore/OidcSessionStore in DACDaemonModule |
| 84d56ec45 | test(33-02): add failing tests for OidcResource login and callback endpoints |
| b0afe9bf1 | feat(33-02): implement OidcResource OIDC login and callback endpoints |

TDD pattern confirmed: RED commit precedes GREEN commit for both plans.

---

## Gaps Summary

None. All must-haves verified. Phase goal is achieved.

---

_Verified: 2026-03-12_
_Verifier: Claude (gsd-verifier)_
