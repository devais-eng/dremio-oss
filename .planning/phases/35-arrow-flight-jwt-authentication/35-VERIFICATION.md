---
phase: 35-arrow-flight-jwt-authentication
verified: 2026-03-13T00:00:00Z
status: passed
score: 5/5 must-haves verified
---

# Phase 35: Arrow Flight JWT Authentication Verification Report

**Phase Goal:** Arrow Flight SQL clients (e.g. columnar.tech DBC, any Flight SQL JDBC driver) can authenticate by passing a Keycloak JWT as the password, reusing the same JIT provisioning and role sync as the REST API path
**Verified:** 2026-03-13
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|---------|
| 1 | Flight SQL client with eyJ-prefixed password is authenticated and can execute queries | VERIFIED | `DremioCredentialValidator.validate()` (auth2 mode) and `DremioFlightServerBasicAuthValidator.getToken()` (legacy mode) both check `password.startsWith(JWT_COMPACT_PREFIX)` and dispatch to `OidcTokenValidator.validateWithClaims()` before the Dremio UserService path |
| 2 | First-time Keycloak user connecting via Flight SQL is auto-provisioned (JIT) | VERIFIED | `jitProvisioner.provision(ktd.getUsername(), ktd.getEmail())` called in `validateKeycloakJwt()` (DremioCredentialValidator), `validateKeycloakJwtAndCreateToken()` (DremioFlightServerBasicAuthValidator), and `validateBearer()` (DremioBearerTokenAuthenticator) |
| 3 | Dremio opaque token authentication via Flight SQL continues to work unchanged | VERIFIED | Non-eyJ passwords fall through to `DremioFlightAuthUtils.authenticateCredentials()` in all three validators; existing tests (`testAuthenticateWithValidCredentials`, `testValidateBearerWithValidToken`, `testGetTokenWithDremioPassword`) pass unchanged |
| 4 | Invalid Keycloak JWT in Flight auth returns UNAUTHENTICATED (not 500) | VERIFIED | All validators catch `ParseException \| IllegalArgumentException \| IOException` and throw `CallStatus.UNAUTHENTICATED.toRuntimeException()`; `testValidateWithInvalidKeycloakJwt` and `testGetTokenWithInvalidKeycloakJwt` confirm this |
| 5 | Bearer token re-sends of Keycloak JWTs on subsequent Flight calls are validated correctly | VERIFIED | `DremioBearerTokenAuthenticator.validateBearer()` checks `token.startsWith(JWT_COMPACT_PREFIX)` and routes to `OidcTokenValidator.validateWithClaims()`, minting a new Dremio session token; `testValidateBearerWithKeycloakJwt` confirms this |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `services/arrow-flight/src/main/java/com/dremio/service/flight/DremioFlightAuthProviderImpl.java` | 6-arg constructor accepting OidcTokenValidator, JitUserProvisioner, KeycloakRoleSyncer; backward-compatible 3-arg constructor | VERIFIED | 6-arg constructor present at lines 58-73; 3-arg constructor delegates via `() -> null` lambdas at lines 79-84; `OidcTokenValidator` imported and stored as `Provider<OidcTokenValidator>` field; `addAuthHandler()` resolves providers and passes instances to both validators |
| `services/arrow-flight/src/main/java/com/dremio/service/flight/auth2/DremioCredentialValidator.java` | JWT dispatch in validate() — eyJ prefix detection, validateWithClaims, JIT, role sync | VERIFIED | `JWT_COMPACT_PREFIX = "eyJ"` at line 39; `validate()` checks prefix at line 79; `validateKeycloakJwt()` calls `validateWithClaims()`, `provision()`, `syncRoles()` at lines 96-105 |
| `services/arrow-flight/src/main/java/com/dremio/service/flight/auth2/DremioBearerTokenAuthenticator.java` | JWT dispatch in validateBearer() — eyJ bearer tokens validated via OIDC | VERIFIED | `JWT_COMPACT_PREFIX = "eyJ"` at line 51; `validateBearer()` checks prefix at line 142; full JIT+roleSync+token-mint path at lines 143-157 |
| `services/arrow-flight/src/main/java/com/dremio/service/flight/auth/DremioFlightServerBasicAuthValidator.java` | JWT dispatch in getToken() and isValid() — legacy mode eyJ detection | VERIFIED | `JWT_COMPACT_PREFIX = "eyJ"` at line 49; `getToken()` checks prefix at line 99; `isValid()` checks prefix at line 117; both paths call `validateWithClaims()`, `provision()`, `syncRoles()` |
| `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` | Passes OidcTokenValidator/JitUserProvisioner/KeycloakRoleSyncer providers to DremioFlightAuthProviderImpl | VERIFIED | Lines 1767-1773: `new DremioFlightAuthProviderImpl(registry.provider(DremioConfig.class), registry.provider(UserService.class), registry.provider(TokenManager.class), registry.provider(OidcTokenValidator.class), registry.provider(JitUserProvisioner.class), registry.provider(KeycloakRoleSyncer.class))` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `DACDaemonModule.java` | `DremioFlightAuthProviderImpl` | `registry.provider(OidcTokenValidator.class)` passed to 6-arg constructor | WIRED | Confirmed at lines 1767-1773; `OidcTokenValidator` imported at line 280; lazy `registry.provider()` avoids the ordering problem (Flight binding at line 1764 runs before `setupUserService()` at ~2200, but `.get()` is called lazily in `addAuthHandler()` at service start time) |
| `DremioFlightAuthProviderImpl.addAuthHandler()` | `DremioCredentialValidator` constructor | passes Keycloak providers resolved from stored Provider fields | WIRED | `addAuthHandler()` resolves providers via `.get()` at lines 93-95, then passes to `new DremioBearerTokenAuthenticator(...)` at lines 110-116 which constructs `new DremioCredentialValidator(userServiceProvider, oidcTokenValidator, jitProvisioner, roleSyncer)` at lines 78-80 |
| `DremioCredentialValidator.validate()` | `OidcTokenValidator.validateWithClaims()` | eyJ prefix detection triggers OIDC path | WIRED | `password.startsWith(JWT_COMPACT_PREFIX)` at line 79 dispatches to `validateKeycloakJwt(password)` which calls `oidcTokenValidator.validateWithClaims(jwtString)` at line 97 |
| `DremioBearerTokenAuthenticator.validateBearer()` | `OidcTokenValidator.validateWithClaims()` | eyJ prefix detection on bearer token | WIRED | `token.startsWith(JWT_COMPACT_PREFIX)` at line 142 dispatches to `oidcTokenValidator.validateWithClaims(token)` at line 144 |
| `DremioFlightServerBasicAuthValidator.getToken()` | `OidcTokenValidator.validateWithClaims()` | eyJ prefix detection triggers OIDC path | WIRED | `password.startsWith(JWT_COMPACT_PREFIX)` at line 99 dispatches to `validateKeycloakJwtAndCreateToken(password)` which calls `oidcTokenValidator.validateWithClaims(jwtString)` at line 153 |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|---------|
| JDBC-01 | 35-01-PLAN.md | Arrow Flight credential validator accepts Keycloak JWT as password (detects `eyJ` prefix, validates via JWKS) | SATISFIED | `JWT_COMPACT_PREFIX = "eyJ"` present in all three validator classes; `OidcTokenValidator.validateWithClaims()` called on eyJ-prefixed passwords/tokens in `DremioCredentialValidator.validate()`, `DremioBearerTokenAuthenticator.validateBearer()`, and `DremioFlightServerBasicAuthValidator.getToken()` |
| JDBC-02 | 35-01-PLAN.md | JIT provisioning triggers on JDBC/ODBC first login (user auto-created if not exists) | SATISFIED | `jitProvisioner.provision(ktd.getUsername(), ktd.getEmail())` called in all three JWT auth paths; `testValidateWithKeycloakJwtCallsJitAndRoleSync` (TestDremioCredentialValidator) and `testGetTokenWithKeycloakJwtCallsJit` (TestDremioFlightServerBasicAuthValidator) verify this |

**Note on LOUT-01:** The REQUIREMENTS.md traceability table assigns LOUT-01 (RP-Initiated Logout) to Phase 35 with status "Pending". This is intentional and expected — the ROADMAP Scope notes for Phase 35 explicitly state "RP-Initiated Logout deferred — can be added as a separate phase if needed". The 35-01-PLAN.md frontmatter correctly does NOT claim LOUT-01. This is a documented deferral, not a gap in Phase 35.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| None in Phase 35 modified files | — | — | — | — |

The only TODOs found are in pre-existing files not modified by Phase 35 (`DremioFlightService.java:297`, `TokenCacheFlightSessionManager.java:55`, `RunQueryResponseHandler.java:104`, `DremioFlightAuthUtils.java:103`). All are pre-existing Dremio issue-tracker references, not implementation stubs.

### Human Verification Required

#### 1. End-to-End Flight SQL Connection with Keycloak JWT

**Test:** Connect a Flight SQL JDBC driver (e.g. columnar.tech DBC or Apache Arrow Flight JDBC) to the running Dremio instance with `auth.type=keycloak`. Set password to a valid Keycloak access token (obtained from `POST /realms/iceberg/protocol/openid-connect/token`). Execute a query.
**Expected:** Connection succeeds, query returns results, Dremio user is created if new.
**Why human:** Requires a running Dremio + Keycloak environment; Flight SQL JDBC driver connection can't be verified statically.

#### 2. Opaque Token Fallback Unchanged

**Test:** Connect a Flight SQL client using Dremio username + password (not a JWT). Verify connection succeeds as before.
**Expected:** No regression in existing Dremio password-based Flight authentication.
**Why human:** Requires runtime environment; static analysis confirms the fallthrough path is present but live behavior needs confirmation.

### Gaps Summary

No gaps found. All five observable truths are verified against the actual codebase. All required artifacts exist, are substantive (not stubs), and are fully wired. All key links are confirmed present in the implementation. Requirements JDBC-01 and JDBC-02 are both satisfied with unit test evidence. The LOUT-01 "Pending" traceability entry is a documented deferral per the ROADMAP scope notes, not a Phase 35 obligation.

**Commits verified:**
- `a514a330f` — Task 1: Foundation (pom.xml dep, DremioFlightAuthProviderImpl 6-arg constructor, DACDaemonModule wiring)
- `9a7ec2466` — Task 2: Auth2 validators (DremioCredentialValidator + DremioBearerTokenAuthenticator JWT dispatch + tests)
- `35caeec80` — Task 3: Legacy validator (DremioFlightServerBasicAuthValidator JWT dispatch + tests)

---

_Verified: 2026-03-13_
_Verifier: Claude (gsd-verifier)_
