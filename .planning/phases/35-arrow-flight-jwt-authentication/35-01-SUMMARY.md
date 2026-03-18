---
phase: 35-arrow-flight-jwt-authentication
plan: "01"
subsystem: arrow-flight-auth
tags: [flight, jwt, keycloak, jit-provisioning, role-sync]
dependency_graph:
  requires: [30-jwt-validation, 31-rest-api-bearer-jwt, 32-jit-provisioning-role-mapping]
  provides: [arrow-flight-jwt-auth, jdbc-odbc-keycloak-auth]
  affects: [arrow-flight-service, dac-daemon-module]
tech_stack:
  added: [dremio-services-keycloak (dependency to services/arrow-flight pom.xml)]
  patterns: [eyJ-prefix-dispatch, null-safe-provider-injection, jit-on-flight-auth, tdd-red-green]
key_files:
  created:
    - services/arrow-flight/src/test/java/com/dremio/service/flight/auth/TestDremioFlightServerBasicAuthValidator.java
  modified:
    - services/arrow-flight/pom.xml
    - services/arrow-flight/src/main/java/com/dremio/service/flight/DremioFlightAuthProviderImpl.java
    - services/arrow-flight/src/main/java/com/dremio/service/flight/auth2/DremioCredentialValidator.java
    - services/arrow-flight/src/main/java/com/dremio/service/flight/auth2/DremioBearerTokenAuthenticator.java
    - services/arrow-flight/src/main/java/com/dremio/service/flight/auth/DremioFlightServerBasicAuthValidator.java
    - dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java
    - services/arrow-flight/src/test/java/com/dremio/service/flight/BasicFlightAuthenticationTest.java
    - services/arrow-flight/src/test/java/com/dremio/service/flight/auth2/TestDremioCredentialValidator.java
    - services/arrow-flight/src/test/java/com/dremio/service/flight/auth2/TestDremioBearerTokenAuthenticator.java
decisions:
  - "Store Provider<OidcTokenValidator> (not raw instances) in DremioFlightAuthProviderImpl: Flight binding at line 1764 runs AFTER setupUserService() (line 831), so providers are always safe; lazy .get() in addAuthHandler() resolves at DremioFlightService.start() time"
  - "3-arg backward-compatible constructor uses () -> null lambdas (not null Provider refs): addAuthHandler() calls .get() on stored providers; null Provider would NPE"
  - "DACDaemonModule uses registry.provider() (not registry.lookup()): providers resolve lazily at Flight service start, not at binding time; both null-provider (internal auth) and actual (keycloak) branches work transparently"
  - "Both DremioBearerTokenAuthenticator validateBearer() and DremioFlightServerBasicAuthValidator isValid() extended for eyJ bearer reuse: clients may re-send Keycloak JWT as bearer on subsequent calls (confirmed pattern from DACAuthFilter)"
metrics:
  duration: "~7 minutes"
  completed_date: "2026-03-13"
  tasks_completed: 3
  tasks_total: 3
  files_modified: 9
  files_created: 1
---

# Phase 35 Plan 01: Arrow Flight JWT Authentication Summary

**One-liner:** Keycloak JWT dispatch added to all three Arrow Flight auth validators (auth2 credential, auth2 bearer, legacy basic) via eyJ-prefix detection routing to OidcTokenValidator + JIT + role sync.

## What Was Built

Flight SQL clients (JDBC/ODBC) can now authenticate with Keycloak access tokens directly. When a password or bearer token starts with `eyJ`, it is intercepted before the Dremio `UserService.authenticate()` call and routed through `OidcTokenValidator.validateWithClaims()`. First-time Keycloak users are auto-provisioned (JIT) and their roles synced to RBAC automatically.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | Foundation: pom.xml dep, DremioFlightAuthProviderImpl 6-arg constructor, DACDaemonModule wiring | a514a330f | pom.xml, DremioFlightAuthProviderImpl.java, DACDaemonModule.java |
| 2 | Auth2 mode: DremioCredentialValidator + DremioBearerTokenAuthenticator JWT dispatch + tests | 9a7ec2466 | DremioCredentialValidator.java, DremioBearerTokenAuthenticator.java, BasicFlightAuthenticationTest.java, TestDremioCredentialValidator.java, TestDremioBearerTokenAuthenticator.java |
| 3 | Legacy mode: DremioFlightServerBasicAuthValidator JWT dispatch + tests | 35caeec80 | DremioFlightServerBasicAuthValidator.java, TestDremioFlightServerBasicAuthValidator.java |

## Architecture

```
DACDaemonModule (line 1764)
  └─ DremioFlightAuthProviderImpl(6-arg, Provider<OidcTokenValidator>, Provider<JitUserProvisioner>, Provider<KeycloakRoleSyncer>)
       └─ addAuthHandler() → resolves providers, passes to:
            ├─ FLIGHT_AUTH2_AUTH_MODE: DremioBearerTokenAuthenticator(oidcTokenValidator, jitProvisioner, roleSyncer)
            │    ├─ initial auth → DremioCredentialValidator.validate(): eyJ? → validateKeycloakJwt()
            │    └─ bearer reuse → validateBearer(): eyJ? → OidcTokenValidator + mint Dremio token
            └─ FLIGHT_LEGACY_AUTH_MODE: DremioFlightServerBasicAuthValidator(oidcTokenValidator, ...)
                 ├─ getToken(): eyJ? → validateKeycloakJwtAndCreateToken()
                 └─ isValid(): eyJ? → OidcTokenValidator + mint new Dremio token
```

## JWT Auth Flow (all three validators)

1. `password.startsWith("eyJ") && oidcTokenValidator != null`
2. `ktd = oidcTokenValidator.validateWithClaims(jwtString)` — verifies sig, issuer, audience, expiry
3. `jitProvisioner.provision(ktd.getUsername(), ktd.getEmail())` — auto-create user if missing (JDBC-02)
4. `roleSyncer.syncRoles(ktd.getUsername(), ktd.getRealmRoles())` — sync RBAC memberships
5. `DremioFlightAuthUtils.createUserSessionWithTokenAndProperties(tokenManagerProvider, username)` — mint Dremio session token
6. Catch `ParseException | IllegalArgumentException | IOException` → `UNAUTHENTICATED` FlightRuntimeException

## Test Results

```
TestDremioCredentialValidator:        6 tests — PASS (JWT auth, JIT+roleSync, invalid JWT, no-OIDC-config, existing Dremio tests)
TestDremioBearerTokenAuthenticator:   5 tests — PASS (Keycloak JWT bearer, Dremio opaque token, existing tests)
TestDremioFlightServerBasicAuthValidator: 6 tests — PASS (JWT getToken, JIT call, invalid JWT, Dremio password, isValid JWT, isValid Dremio)
TestDremioFlightServerAuthValidator:  4 tests — PASS (existing, unchanged)
Full arrow-flight suite:            ALL PASS (no regressions)
```

## Requirements Satisfied

| Req ID | Description | Status |
|--------|-------------|--------|
| JDBC-01 | Arrow Flight credential validator accepts Keycloak JWT as password | DONE |
| JDBC-02 | JIT provisioning triggers on JDBC/ODBC first login | DONE |

## Deviations from Plan

None — plan executed exactly as written. The ordering concern from RESEARCH.md (Pitfall 2: setupUserService before Flight binding) was resolved exactly as the plan recommended: using `Provider<OidcTokenValidator>` lazy resolution stored as fields in `DremioFlightAuthProviderImpl`, resolved via `.get()` in `addAuthHandler()`.

## Known Limitations (documented, not fixed)

- Keycloak access token TTL (~5 min) is shorter than Dremio session token TTL (24h). Long-running BI connections should use `POST /apiv2/login` to exchange the Keycloak JWT for a long-lived Dremio token. This is tracked in STATE.md and is out of scope for Phase 35.

## Self-Check: PASSED

Created files:
- [x] `services/arrow-flight/src/test/java/com/dremio/service/flight/auth/TestDremioFlightServerBasicAuthValidator.java` — EXISTS

Modified files:
- [x] `services/arrow-flight/pom.xml` — contains dremio-services-keycloak
- [x] `DremioFlightAuthProviderImpl.java` — contains OidcTokenValidator
- [x] `DremioCredentialValidator.java` — contains JWT_COMPACT_PREFIX
- [x] `DremioBearerTokenAuthenticator.java` — contains JWT_COMPACT_PREFIX
- [x] `DremioFlightServerBasicAuthValidator.java` — contains JWT_COMPACT_PREFIX
- [x] `DACDaemonModule.java` — contains OidcTokenValidator provider

Commits:
- [x] a514a330f — Task 1 foundation
- [x] 9a7ec2466 — Task 2 auth2 validators
- [x] 35caeec80 — Task 3 legacy validator
