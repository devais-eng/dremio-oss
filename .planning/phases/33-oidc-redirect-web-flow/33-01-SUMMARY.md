---
phase: 33-oidc-redirect-web-flow
plan: "01"
subsystem: auth
tags: [oidc, keycloak, oauth2-oidc-sdk, pkce, session-store, state-store, concurrent-hashmap]

# Dependency graph
requires:
  - phase: 32-jit-provisioning-role-mapping
    provides: JitUserProvisioner and KeycloakRoleSyncer already wired in DACDaemonModule keycloak branch
  - phase: 30-jwt-validation-infrastructure-config
    provides: OidcTokenValidator and KeycloakConfig bound in DACDaemonModule keycloak branch
provides:
  - OidcStateStore singleton bound in DACDaemonModule keycloak branch (state→codeVerifier with 5-min TTL, one-time-use)
  - OidcSessionStore singleton bound in DACDaemonModule keycloak branch (dremioToken→idToken for LOUT-02)
  - oauth2-oidc-sdk 11.20 declared in services/keycloak/pom.xml (available for Plan 02 OidcResource)
affects:
  - 33-02-oidc-resource (consumes OidcStateStore, OidcSessionStore, oauth2-oidc-sdk)
  - 35-rp-initiated-logout (consumes OidcSessionStore for id_token_hint)

# Tech tracking
tech-stack:
  added:
    - "com.nimbusds:oauth2-oidc-sdk:11.20 (managed by root BOM) — added to services/keycloak/pom.xml"
  patterns:
    - "OidcStateStore uses ConcurrentHashMap with lazy expiry cleanup on put() — no background thread required"
    - "Package-private TTL constructor pattern for testable time-sensitive classes"
    - "One-time-use state pattern via ConcurrentHashMap.remove() for CSRF prevention"

key-files:
  created:
    - services/keycloak/src/main/java/com/dremio/service/keycloak/OidcStateStore.java
    - services/keycloak/src/main/java/com/dremio/service/keycloak/OidcSessionStore.java
    - services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcStateStore.java
    - services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcSessionStore.java
  modified:
    - services/keycloak/pom.xml
    - dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java

key-decisions:
  - "OidcStateStore uses package-private TTL constructor (not Clock injection or Instant.now() mock) for test-friendly expiry — simpler than full Clock interface for a single TTL test"
  - "Lazy cleanup on put() removes expired entries opportunistically — avoids a background cleanup thread for an in-process ephemeral store"
  - "PendingFlow is a private static final class (not a record) — Java 11 target in this module; records require Java 16+"

patterns-established:
  - "Package-private constructor for testing time-sensitive behavior: OidcStateStore(long ttlMs)"
  - "Dremio test method naming: test* prefix required by checkstyle (not underscore_case)"

requirements-completed: [OIDC-03, LOUT-02]

# Metrics
duration: 6min
completed: 2026-03-12
---

# Phase 33 Plan 01: OIDC State and Session Stores Summary

**ConcurrentHashMap-backed OidcStateStore (5-min TTL, one-time-use PKCE state) and OidcSessionStore (id_token_hint for RP-Initiated Logout) wired as singletons in DACDaemonModule keycloak branch, with oauth2-oidc-sdk dependency added for Plan 02**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-12T17:41:11Z
- **Completed:** 2026-03-12T17:47:46Z
- **Tasks:** 2 (1 TDD with 2 commits + 1 wiring task)
- **Files modified:** 6

## Accomplishments

- OidcStateStore with TTL-based expiry (5-min default), one-time-use retrieval, and lazy cleanup — prevents OIDC state replay attacks (OIDC-03)
- OidcSessionStore mapping Dremio session tokens to Keycloak id_tokens — enables RP-Initiated Logout in Phase 35 (LOUT-02)
- 9 new unit tests (5 state store + 4 session store), all passing alongside 31 existing keycloak tests
- oauth2-oidc-sdk 11.20 available for OidcResource (Plan 02) without version conflict

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Add failing tests for OidcStateStore and OidcSessionStore** - `54426fcf0` (test)
2. **Task 1 GREEN: Implement OidcStateStore and OidcSessionStore** - `6712f8cc8` (feat)
3. **Task 2: Add oauth2-oidc-sdk dep and wire stores in DACDaemonModule** - `718b452db` (feat)

_Note: TDD task has two commits (test RED then feat GREEN)_

## Files Created/Modified

- `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcStateStore.java` - ConcurrentHashMap state store with TTL, one-time-use removeIfValid(), lazy expiry cleanup
- `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcSessionStore.java` - ConcurrentHashMap id_token store keyed by Dremio session token
- `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcStateStore.java` - 5 unit tests: put/remove, unknown state, one-time-use, TTL expiry, lazy cleanup
- `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcSessionStore.java` - 4 unit tests: put/get, unknown token, remove, overwrite
- `services/keycloak/pom.xml` - Added oauth2-oidc-sdk dependency (version managed by root BOM)
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` - Phase 33 store bindings in keycloak branch + import statements

## Decisions Made

- Java 11 target requires private static final class PendingFlow (not a record — records need Java 16+)
- Package-private TTL constructor chosen over Clock injection for simplicity — no need for full time abstraction with a single TTL field
- Lazy cleanup via `removeIf(expired)` on each `put()` avoids a background sweeper thread; adequate for a short-lived in-process store

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed test method names to comply with Dremio checkstyle**
- **Found during:** Task 2 (install phase triggered checkstyle)
- **Issue:** Plan specified snake_case test method names (e.g., `put_thenRemoveIfValid_returnsVerifier`) that violated Dremio's checkstyle pattern `^(test[a-zA-Z0-9_]*|[a-z][a-zA-Z0-9]*)$`
- **Fix:** Renamed all 9 test methods to use `test*` camelCase prefix (e.g., `testPutThenRemoveIfValidReturnsVerifier`)
- **Files modified:** TestOidcStateStore.java, TestOidcSessionStore.java
- **Verification:** `mvn install -pl services/keycloak -DskipTests` passes checkstyle; all 9 tests still pass
- **Committed in:** `718b452db` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug: checkstyle method naming)
**Impact on plan:** Minor rename only. Test semantics unchanged. Necessary for CI compliance.

## Issues Encountered

- `dac/backend` compile required `services/keycloak` to be installed to local Maven repo first (not just compiled) — `mvn install -pl services/keycloak -DskipTests` was used before `mvn compile -pl dac/backend`

## Next Phase Readiness

- Plan 02 (OidcResource): `OidcStateStore`, `OidcSessionStore`, and `oauth2-oidc-sdk` are all available via DI and classpath
- Both stores are injectable via `@Inject @Nullable` in any JAX-RS resource registered under the keycloak auth branch
- Phase 35 (RP-Initiated Logout): `OidcSessionStore.get(dremioToken)` provides `id_token_hint` after callback stores it

---
*Phase: 33-oidc-redirect-web-flow*
*Completed: 2026-03-12*
