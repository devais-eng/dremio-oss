---
phase: 31-rest-api-bearer-jwt-authentication
verified: 2026-03-12T17:30:00Z
status: passed
score: 5/5 must-haves verified
re_verification: false
---

# Phase 31: REST API Bearer JWT Authentication Verification Report

**Phase Goal:** REST API clients can authenticate with a Keycloak-issued Bearer JWT and reach protected endpoints, while Dremio opaque tokens and internal admin login continue to work unchanged
**Verified:** 2026-03-12T17:30:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| #   | Truth                                                                                               | Status     | Evidence                                                                                         |
|-----|-----------------------------------------------------------------------------------------------------|------------|--------------------------------------------------------------------------------------------------|
| 1   | A REST API request with Authorization: Bearer `<KC_JWT>` authenticates successfully (not 401)       | VERIFIED   | `DACAuthFilter.java:121-125` — eyJ-prefix check routes to `oidcTokenValidator.validate(tokenStr)` |
| 2   | A REST API request with a Dremio opaque token authenticates via TokenManager unchanged (no regression) | VERIFIED   | `DACAuthFilter.java:129-131` — non-eyJ tokens route directly to `tokenManager.validateToken()`  |
| 3   | A malformed or expired Keycloak JWT returns 401, not 500                                             | VERIFIED   | `DACAuthFilter.java:126-127` — `catch (ParseException | IllegalArgumentException e)` falls back to TokenManager; outer `catch (IllegalArgumentException e)` wraps in `NotAuthorizedException` |
| 4   | When auth.type=internal (oidcTokenValidator is null), all tokens route to TokenManager only           | VERIFIED   | `DACAuthFilter.java:121` — guard `oidcTokenValidator != null` ensures null field skips Keycloak path entirely |
| 5   | Local admin can still login with username/password when auth.type=keycloak (POST /apiv2/login untouched) | VERIFIED   | `LogInLogOutResource.java:93-94` — `@POST login()` has no `@Secured` annotation; DACAuthFilter never intercepts it |

**Score:** 5/5 truths verified

---

### Required Artifacts

| Artifact                                                                          | Expected                                              | Status     | Details                                                                                         |
|-----------------------------------------------------------------------------------|-------------------------------------------------------|------------|-------------------------------------------------------------------------------------------------|
| `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java`              | eyJ-discriminated token dispatch: Keycloak JWT vs Dremio opaque token; contains OidcTokenValidator | VERIFIED   | Line 29: `import com.dremio.service.keycloak.OidcTokenValidator`; Line 63: `@Inject @Nullable private OidcTokenValidator oidcTokenValidator`; Lines 121-132: full dispatch block |
| `dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterKeycloak.java` | Unit tests for all Keycloak JWT auth paths; min 80 lines | VERIFIED   | 239 lines; 6 test methods covering all declared behaviors; uses MockitoExtension + reflection injection |

---

### Key Link Verification

| From                                          | To                              | Via                                                      | Status     | Details                                                                               |
|-----------------------------------------------|---------------------------------|----------------------------------------------------------|------------|---------------------------------------------------------------------------------------|
| `DACAuthFilter.getUserNameFromToken()`        | `OidcTokenValidator.validate()` | eyJ prefix check + `@Inject @Nullable` field              | WIRED      | `DACAuthFilter.java:121-125` — `oidcTokenValidator.validate(tokenStr)` in eyJ branch  |
| `DACAuthFilter.getUserNameFromToken()`        | `TokenManager.validateToken()`  | fallback for non-eyJ tokens and Keycloak validation failures | WIRED      | `DACAuthFilter.java:127, 131` — two distinct fallback call sites confirmed             |
| `OidcTokenValidator.validate()` exceptions   | `NotAuthorizedException`        | `catch (ParseException | IllegalArgumentException)` in `getUserNameFromToken()` | WIRED      | `DACAuthFilter.java:126` — both checked and unchecked exceptions caught, fallback then outer catch produces `NotAuthorizedException` |
| `OidcTokenValidator` (keycloak mode)         | `DACDaemonModule` binding       | `registry.bind(OidcTokenValidator.class, ...)` in keycloak branch | WIRED      | `DACDaemonModule.java:2230-2236` — bound only when `auth.type=keycloak`; null in internal mode satisfies nullable guard |

---

### Requirements Coverage

| Requirement | Source Plan | Description                                                             | Status    | Evidence                                                                                          |
|-------------|-------------|-------------------------------------------------------------------------|-----------|---------------------------------------------------------------------------------------------------|
| TKN-02      | 31-01-PLAN  | DACAuthFilter accepts Keycloak Bearer tokens on REST API requests when auth.type=keycloak | SATISFIED | eyJ-discriminated dispatch block in `getUserNameFromToken()` + `testValidKeycloakJwtReturnsUsername` test passes |
| COEX-01     | 31-01-PLAN  | When auth.type=keycloak, local admin user can still login with username/password for bootstrap/recovery | SATISFIED | POST `/login` in `LogInLogOutResource` has no `@Secured` annotation; DACAuthFilter only intercepts `@Secured` resources; `testInternalAuthModeNoOidcValidator` test covers null-validator code path |
| COEX-02     | 31-01-PLAN  | Dremio-issued session tokens continue to work alongside Keycloak tokens (DACAuthFilter tries Keycloak first, falls back to Dremio token manager) | SATISFIED | Keycloak-first + TokenManager fallback on `ParseException|IllegalArgumentException`; `testOpaqueTokenUsesTokenManager` and `testKeycloakFailsFallsBackToTokenManager` confirm both cases |

No orphaned requirements. REQUIREMENTS.md traceability table maps TKN-02, COEX-01, COEX-02 exclusively to Phase 31, and all three are marked `[x]` (complete).

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | — | — | No anti-patterns found |

No TODOs, FIXMEs, placeholder returns, or stub implementations detected in either modified file.

---

### Human Verification Required

All five observable truths are verifiable through static analysis and test coverage. No human verification items identified.

The one item that cannot be verified without a running Keycloak instance — that a real Keycloak-issued RS256 JWT is accepted end-to-end — is covered by the Phase 30 integration tests (`TestOidcTokenValidator`) which test the validator against a live JWKS endpoint. The Phase 31 unit tests isolate and verify the dispatch wiring separately.

---

### Commit Evidence

All three commits documented in SUMMARY.md are present in git history:

| Commit      | Type | Description                                                         |
|-------------|------|---------------------------------------------------------------------|
| `101db067e` | test | RED — 6 failing tests for DACAuthFilter Keycloak JWT dispatch       |
| `608f1d992` | feat | GREEN — eyJ-discriminated dispatch added to DACAuthFilter           |
| `fe90abcc2` | docs | Plan metadata and SUMMARY.md committed                              |

---

### Gaps Summary

None. All must-haves are verified at all three levels (exists, substantive, wired).

The phase goal is fully achieved: REST API clients can authenticate with a Keycloak-issued Bearer JWT (`oidcTokenValidator` non-null + `eyJ` prefix), Dremio opaque tokens fall through to `TokenManager` unchanged, the `@Nullable` field guard ensures internal-mode deployments are unaffected, and the login endpoint (`POST /apiv2/login`) is outside the `@Secured` filter scope and cannot be broken by this change.

---

_Verified: 2026-03-12T17:30:00Z_
_Verifier: Claude (gsd-verifier)_
