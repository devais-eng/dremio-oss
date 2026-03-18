---
phase: 30-jwt-validation-infrastructure-config
verified: 2026-03-12T00:00:00Z
status: passed
score: 5/5 must-haves verified
gaps: []
human_verification:
  - test: "Coordinator start with auth.type=keycloak and valid Keycloak config"
    expected: "Coordinator starts cleanly (no RuntimeException in logs) and logs 'Keycloak authentication is configured.'"
    why_human: "Requires a running Keycloak instance and a full Dremio coordinator start to verify end-to-end startup without crash"
---

# Phase 30: JWT Validation Infrastructure + Config Verification Report

**Phase Goal:** The OIDC foundation is in place — coordinator starts with `auth.type=keycloak` without crashing, and Keycloak JWTs can be validated against the JWKS endpoint in isolation
**Verified:** 2026-03-12
**Status:** PASSED
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (from ROADMAP Success Criteria)

| #  | Truth                                                                                       | Status     | Evidence                                                                                                    |
|----|---------------------------------------------------------------------------------------------|------------|-------------------------------------------------------------------------------------------------------------|
| 1  | Coordinator starts cleanly when `auth.type=keycloak` is set with valid config values         | VERIFIED   | DACDaemonModule.java:2218 — keycloak branch returns `true` instead of throwing; imports wired at lines 275-276 |
| 2  | `OidcTokenValidator.validate(jwtString)` returns valid `TokenDetails` for a well-formed RS256 JWT | VERIFIED   | OidcTokenValidator.java:96-112 — full Nimbus implementation; `testValidToken` passes                        |
| 3  | Token validation rejects wrong issuer, wrong audience, expired tokens with `IllegalArgumentException` | VERIFIED   | OidcTokenValidator.java:101-103 — `BadJOSEException/JOSEException` wrapped as `IllegalArgumentException`; `testWrongIssuer`, `testWrongAudience`, `testExpiredToken` all present |
| 4  | When Keycloak rotates its signing key, a token with the new `kid` succeeds after auto JWKS re-fetch | VERIFIED   | OidcTokenValidator.java:71 — `JWKSourceBuilder.retrying(true)`; `testKeyRotation()` exercises full rotation scenario |
| 5  | Role sync mode (`additive` vs `authoritative`) is readable from config                       | VERIFIED   | KeycloakConfig.java:111-113 — `isAdditive()` uses `"additive".equalsIgnoreCase(roleSyncMode)`; two tests confirm true/false return |

**Score:** 5/5 truths verified

---

### Required Artifacts

| Artifact                                                                                         | Expected                                      | Status     | Details                                                                            |
|--------------------------------------------------------------------------------------------------|-----------------------------------------------|------------|------------------------------------------------------------------------------------|
| `services/keycloak/pom.xml`                                                                      | Maven module definition for keycloak service  | VERIFIED   | 67 lines; `artifactId=dremio-services-keycloak`; deps include nimbus-jose-jwt, dremio-services-tokens, guava |
| `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakConfig.java`                | Typed config bean for Keycloak settings        | VERIFIED   | 114 lines (min_lines: 30); reads all 5 fields, Preconditions validation, `getJwksUri()`, `isAdditive()` |
| `common/legacy/src/main/java/com/dremio/config/DremioConfig.java`                               | Config path constants for Keycloak             | VERIFIED   | Lines 161-166: all 5 `KEYCLOAK_*` constants present after `RBAC_PDS_ENABLED`     |
| `common/legacy/src/main/resources/dremio-reference.conf`                                         | Default values for keycloak config block       | VERIFIED   | Lines 358-366: full `keycloak { }` block with empty-string defaults and `sync-mode: "additive"` |
| `services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakConfig.java`            | Unit tests for KeycloakConfig                  | VERIFIED   | 125 lines (min_lines: 40); 7 `@Test` methods covering all behaviors               |
| `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java`           | JWT validation against Keycloak JWKS endpoint  | VERIFIED   | 113 lines (min_lines: 40); `DefaultJWTProcessor`, `JWSVerificationKeySelector`, `JWKSourceBuilder`, `TokenDetails.of(...)` |
| `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcTokenValidator.java`       | Unit tests for OidcTokenValidator              | VERIFIED   | 312 lines (min_lines: 80); 10 `@Test` methods; in-process JWKS HTTP server via `com.sun.net.httpserver` |
| `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java`                          | Keycloak branch in setupUserService            | VERIFIED   | Lines 2218-2240: branch instantiates `SimpleUserService`, `KeycloakConfig`, `OidcTokenValidator`; all three bound in registry |
| `dac/backend/pom.xml`                                                                            | dremio-services-keycloak dependency            | VERIFIED   | Line 206: `dremio-services-keycloak` dependency present                           |

---

### Key Link Verification

| From                                                            | To                                                 | Via                                           | Status   | Details                                                                           |
|-----------------------------------------------------------------|----------------------------------------------------|-----------------------------------------------|----------|-----------------------------------------------------------------------------------|
| `KeycloakConfig.java`                                           | `DremioConfig.java`                                | `static import DremioConfig.KEYCLOAK_*`       | WIRED    | Lines 18-22: all 5 constants imported and used in constructor                     |
| `DACDaemonModule.java`                                          | `KeycloakConfig.java`                              | `new KeycloakConfig(dacConfig.getConfig())`   | WIRED    | Line 2227: instantiation + `registry.bind(KeycloakConfig.class, ...)` at 2228    |
| `OidcTokenValidator.java`                                       | `TokenDetails.java`                                | `TokenDetails.of(jwtString, username, expiresAt)` | WIRED    | Line 111: `return TokenDetails.of(jwtString, username, expiresAt)`                |
| `DACDaemonModule.java`                                          | `OidcTokenValidator.java`                          | `registry.bind(OidcTokenValidator.class, new OidcTokenValidator(...))` | WIRED    | Lines 2231-2236: instantiation with `keycloakConfig.getJwksUri()`, `getIssuerUrl()`, `getClientId()` |

---

### Requirements Coverage

| Requirement | Source Plan | Description                                                                                          | Status    | Evidence                                                                           |
|-------------|-------------|------------------------------------------------------------------------------------------------------|-----------|------------------------------------------------------------------------------------|
| CFG-01      | 30-01-PLAN  | Operator can set `services.coordinator.web.auth.type = "keycloak"` to enable Keycloak authentication | SATISFIED | DACDaemonModule:2218 branch handles `"keycloak"` auth type cleanly (no crash)     |
| CFG-02      | 30-01-PLAN  | Operator can configure issuer-url, client-id, client-secret, redirect-uri                           | SATISFIED | KeycloakConfig reads all 4 connection fields; dremio-reference.conf has defaults; 5 KEYCLOAK_* constants in DremioConfig |
| CFG-03      | 30-01-PLAN  | Operator can configure role sync mode via `services.keycloak.role.sync-mode`                         | SATISFIED | KeycloakConfig:52,111-113 — reads `KEYCLOAK_ROLE_SYNC_MODE`, exposes `isAdditive()` |
| TKN-01      | 30-02-PLAN  | Dremio validates Keycloak-issued JWTs against JWKS endpoint (RS256, expiry, issuer, audience)        | SATISFIED | OidcTokenValidator uses RS256, `DefaultJWTClaimsVerifier` with issuer+audience+required claims; 4 rejection tests pass |
| TKN-03      | 30-02-PLAN  | JWKS cache auto-refreshes when a `kid` mismatch is detected                                          | SATISFIED | `JWKSourceBuilder.retrying(true)` at line 71; `testKeyRotation()` exercises full rotate-then-validate scenario |

**No orphaned requirements found.** All 5 phase-30 requirements (CFG-01, CFG-02, CFG-03, TKN-01, TKN-03) are claimed by plans and verified in the codebase.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | — | — | No anti-patterns found in phase artifacts |

No TODO/FIXME/PLACEHOLDER comments in any phase-30 source files. No empty return stubs. No console.log-only implementations.

---

### Human Verification Required

#### 1. Full Coordinator Start with Keycloak Auth

**Test:** Configure `dremio.conf` with `services.coordinator.web.auth.type = "keycloak"` and valid Keycloak connection values, then start the Dremio coordinator.
**Expected:** Coordinator starts cleanly. Log line `"Keycloak authentication is configured."` appears. No `RuntimeException` or `IllegalArgumentException` in startup logs.
**Why human:** Requires a running Dremio coordinator process and a reachable Keycloak instance (or pre-configured stub JWKS endpoint) to confirm end-to-end startup.

Note: All automated indicators are positive — the branch exists and compiles, `KeycloakConfig` validates required fields at construction, `OidcTokenValidator` validates JWKS URL format at construction. The startup-without-crash behavior is fully supported by the code; this test is listed for completeness, not because any gap was found.

---

### Commit Verification

All 5 plan-documented commits present in git history:

| Commit    | Description                                                     |
|-----------|-----------------------------------------------------------------|
| `e29865b` | feat(30-01): add services/keycloak module with KeycloakConfig bean and config constants |
| `4ec2b8a` | feat(30-01): wire keycloak auth type branch in DACDaemonModule.setupUserService |
| `9563df4` | test(30-02): add failing tests for OidcTokenValidator (RED)    |
| `ebc4c99` | feat(30-02): implement OidcTokenValidator — Keycloak RS256 JWT validation (GREEN) |
| `c30d7e8` | feat(30-02): wire OidcTokenValidator in DACDaemonModule keycloak branch |

---

### Summary

Phase 30 goal is fully achieved. The OIDC foundation is in place:

- **Config layer:** `services/keycloak` Maven module exists with `KeycloakConfig` bean reading all 5 OIDC fields from `DremioConfig` constants backed by `dremio-reference.conf` defaults. Fast-fail validation throws `IllegalArgumentException` at startup for blank required fields (CFG-01, CFG-02, CFG-03 satisfied).
- **Coordinator startup:** `DACDaemonModule.setupUserService()` has a working `"keycloak"` branch that binds `SimpleUserService`, `KeycloakConfig`, and `OidcTokenValidator` — no `RuntimeException` on `auth.type=keycloak` (CFG-01 satisfied).
- **JWT validation:** `OidcTokenValidator` uses Nimbus `JWKSourceBuilder` (5-min TTL, retrying) with RS256, issuer+audience claim verification. Returns `TokenDetails` with `preferred_username` (fallback to `sub`). 10 TDD tests cover: valid token, wrong issuer, wrong audience, expired, bad signature, key rotation, username fallback, malformed input (TKN-01, TKN-03 satisfied).
- **Key rotation:** `JWKSourceBuilder.retrying(true)` auto-refreshes on unknown `kid` — `testKeyRotation()` confirms this end-to-end with two RSA keypairs and a mutable in-process JWKS endpoint (TKN-03 satisfied).

Phase 31 consumers (`DACAuthFilter`, Arrow Flight) can inject `OidcTokenValidator.class` from the DI registry and call `validator.validate(bearerToken)`, handling `ParseException` (non-JWT input) and `IllegalArgumentException` (failed validation).

---

_Verified: 2026-03-12_
_Verifier: Claude (gsd-verifier)_
