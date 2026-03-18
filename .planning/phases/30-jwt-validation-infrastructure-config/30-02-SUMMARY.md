---
phase: 30-jwt-validation-infrastructure-config
plan: "02"
subsystem: keycloak-jwt-validation
tags: [keycloak, jwt, nimbus-jose-jwt, rs256, jwks, tdd-red-green, OidcTokenValidator]

# Dependency graph
requires:
  - phase: 30-01
    provides: "KeycloakConfig bean with getJwksUri(), getIssuerUrl(), getClientId(); keycloak branch in DACDaemonModule; dremio-services-keycloak module"
provides:
  - "OidcTokenValidator: validates Keycloak RS256 JWTs against JWKS endpoint"
  - "Key rotation support via JWKSourceBuilder unknown-kid auto-refresh"
  - "OidcTokenValidator bound in DACDaemonModule keycloak branch (registry)"
  - "10 TDD tests covering valid token, wrong issuer/audience, expired, bad signature, key rotation, malformed input, username extraction"
affects:
  - phase-31-dacauthfilter
  - phase-35-arrow-flight

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JWKSourceBuilder.create(url).retrying(true).build() for JWKS caching/rotation (Nimbus 9.28+ pattern)"
    - "DefaultJWTClaimsVerifier with exact-match issuer+audience and required claims set"
    - "@SuppressForbidden on test class for com.sun.net.httpserver.HttpServer (internal JDK API, lightest in-process HTTP server)"
    - "AtomicReference<String> for mutable JWKS document in key rotation test"

key-files:
  created:
    - services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java
    - services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcTokenValidator.java
  modified:
    - dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java

key-decisions:
  - "@SuppressForbidden used on TestOidcTokenValidator to allow com.sun.net.httpserver.HttpServer — lightest in-process JWKS server without adding WireMock or Jetty as test dependency"
  - "JWKSourceBuilder.retrying(true) only — no custom TTL or rate-limit configuration; Nimbus defaults (5-min cache, 30-s rate limit) are sufficient for Phase 30"
  - "OidcTokenValidator constructor stores ConfigurableJWTProcessor directly (no functional interface alias) — simpler type, avoids compiler ambiguity"

patterns-established:
  - "TDD RED: write test class referencing non-existent class, confirm compile failure, commit"
  - "TDD GREEN: implement class, run all tests, confirm 10/10 pass, commit"
  - "Use @SuppressForbidden for necessary com.sun.* usage in tests, with a Javadoc explaining why"

requirements-completed: [TKN-01, TKN-03]

# Metrics
duration: 8min
completed: 2026-03-12
---

# Phase 30 Plan 02: OidcTokenValidator Summary

**RS256 JWT validator using Nimbus JWKSourceBuilder with 10 TDD tests covering validation, key rotation auto-refresh, username extraction fallback, and DACDaemonModule wiring.**

## Performance

- **Duration:** ~8 minutes
- **Started:** 2026-03-12T15:00:52Z
- **Completed:** 2026-03-12T16:09:00Z
- **Tasks:** 3 (RED, GREEN, DACDaemonModule wire)
- **Files modified:** 3

## Accomplishments

- `OidcTokenValidator(jwksUri, issuer, audience)` validates Keycloak RS256 JWTs against JWKS endpoint
- Key rotation (TKN-03) handled automatically: `JWKSourceBuilder` re-fetches on unknown `kid`
- `preferred_username` extracted as Dremio username, falls back to `sub` per STATE.md decision
- `OidcTokenValidator` bound in `DACDaemonModule.setupUserService()` keycloak branch
- All 17 keycloak module tests pass (10 new OidcTokenValidator + 7 KeycloakConfig)

## Task Commits

Each task was committed atomically:

1. **Task 1: TDD RED — failing tests for OidcTokenValidator** - `9563df409` (test)
2. **Task 2: TDD GREEN — implement OidcTokenValidator** - `ebc4c9973` (feat)
3. **Task 3: Wire in DACDaemonModule + formatting + forbidden API fix** - `c30d7e89d` (feat)

_Note: TDD tasks have multiple commits (test → feat → wiring)_

## Files Created/Modified

- `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java` - RS256 JWT validator using Nimbus JWKSourceBuilder; returns TokenDetails
- `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcTokenValidator.java` - 10 TDD tests with in-process JWKS HTTP server
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` - Added `registry.bind(OidcTokenValidator.class, new OidcTokenValidator(...))` and import

## Decisions Made

1. **`@SuppressForbidden` on TestOidcTokenValidator** — `com.sun.net.httpserver.HttpServer` is in the `jdk-non-portable` bundled signatures list. Rather than add WireMock/Jetty as a new test dependency, used `@SuppressForbidden` per the project convention. Javadoc on the class explains the rationale.

2. **No JWKSourceBuilder custom TTL or rate-limit** — Nimbus defaults (5-min cache, 30-s rate limit, `retrying(true)`) are sufficient. Configurable timeout can be added in Phase 31 if production testing reveals issues.

3. **`ConfigurableJWTProcessor<SecurityContext>` field type** — An initial attempt to alias the type with an inner `interface JWTProcessor` caused a compiler error ("not a functional interface"). Used the direct `ConfigurableJWTProcessor<SecurityContext>` type instead, which is cleaner.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Replaced inner-interface JWTProcessor alias with direct ConfigurableJWTProcessor type**
- **Found during:** Task 2 (GREEN phase, first compile attempt)
- **Issue:** `this.jwtProcessor = processor::process` caused "not a functional interface — multiple non-overriding abstract methods" because inner interface extending `JWTProcessor<C>` was ambiguous
- **Fix:** Field changed to `ConfigurableJWTProcessor<SecurityContext>` directly; method reference removed
- **Files modified:** `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java`
- **Verification:** Compiled and all 10 tests pass
- **Committed in:** `ebc4c9973` (GREEN commit)

**2. [Rule 2 - Missing Critical] Added `@SuppressForbidden` to TestOidcTokenValidator**
- **Found during:** Task 3 (install with `forbiddenapis:testCheck` phase)
- **Issue:** `com.sun.net.httpserver.HttpServer` flagged as non-portable internal JDK class by `jdk-non-portable` bundled signature; `mvn install` fails without suppression
- **Fix:** Added `@SuppressForbidden` annotation and `import com.dremio.common.SuppressForbidden` with Javadoc rationale
- **Files modified:** `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcTokenValidator.java`
- **Verification:** `mvn install -f services/keycloak/pom.xml -DskipTests` succeeds; all tests pass
- **Committed in:** `c30d7e89d` (DACDaemonModule wiring commit)

---

**Total deviations:** 2 auto-fixed (1 compiler error, 1 missing required annotation)
**Impact on plan:** Both auto-fixes necessary for correctness and build compliance. No scope creep.

## Issues Encountered

- Spotless formatting check caught style violations in both Java files after initial write (long lines, Javadoc formatting). Fixed with `mvn spotless:apply`. No logic changes.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 31 (DACAuthFilter): `OidcTokenValidator` is registered in the DI registry as `OidcTokenValidator.class`; inject it with `registry.lookup(OidcTokenValidator.class)` and call `validator.validate(bearerToken)` for tokens starting with `eyJ`
- Phase 35 (Arrow Flight): same injection pattern as Phase 31
- Both consumers must handle `ParseException` (non-JWT input) and `IllegalArgumentException` (failed validation) from `validate()`

---
*Phase: 30-jwt-validation-infrastructure-config*
*Completed: 2026-03-12*

## Self-Check: PASSED

| Check | Result |
|-------|--------|
| `OidcTokenValidator.java` exists | FOUND |
| `TestOidcTokenValidator.java` exists | FOUND |
| Commit `9563df409` (RED) exists | FOUND |
| Commit `ebc4c9973` (GREEN) exists | FOUND |
| Commit `c30d7e89d` (wire + fix) exists | FOUND |
| `OidcTokenValidator` import in DACDaemonModule | FOUND |
| `new OidcTokenValidator(...)` in DACDaemonModule keycloak branch | FOUND |
| All 10 TestOidcTokenValidator tests pass | PASS |
| dac/backend compiles | BUILD SUCCESS |
