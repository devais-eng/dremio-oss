---
phase: 32-jit-provisioning-role-mapping
plan: 02
subsystem: auth
tags: [keycloak, jit, kvstore, protostuff, java, tdd, mockito]

# Dependency graph
requires:
  - phase: 32-jit-provisioning-role-mapping
    provides: dremio-services-users dependency in keycloak pom, mockito-core test dep, KeycloakTokenDetails, UserGroupStoreBuilder access (Plan 01)
  - phase: 30-jwt-validation-infrastructure-config
    provides: OidcTokenValidator with JWKS-backed RS256 validation

provides:
  - JitUserProvisioner.provision(username, email) - REMOTE user creation with race-safe idempotency
  - No UserAuth record for JIT users (REMOTE type blocks authenticate() at SimpleUserService line 354)
  - Null-safe email handling (stores empty string, never NPE)

affects:
  - 32-03-keycloak-role-syncer
  - 33-dac-auth-filter-jit-integration

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JIT provisioning via direct KVStore write: bypass createUser() to set UserType.REMOTE without password"
    - "Race-safe idempotency: catch Exception from put() and log at DEBUG rather than propagate"
    - "Memoized store lookup: Suppliers.memoize(() -> kvStoreProvider.get().getStore(UserGroupStoreBuilder.class))"
    - "TDD RED-GREEN: test file committed before implementation, compilation failure confirms RED state"

key-files:
  created:
    - services/keycloak/src/main/java/com/dremio/service/keycloak/JitUserProvisioner.java
    - services/keycloak/src/test/java/com/dremio/service/keycloak/TestJitUserProvisioner.java
  modified: []

key-decisions:
  - "Catch Exception (not UserAlreadyExistException) in provision(): LegacyIndexedStore.put() does not declare throws UserAlreadyExistException; catching the broad exception handles any concurrent-write failure gracefully"
  - "UserInfo.getAuth() returns null when not set (protostuff field, no hasAuth() method) -- test uses assertThat(getAuth()).isNull()"
  - "No pre-existence check before put(): last-write-wins upsert is acceptable for JIT; two concurrent provisions create same REMOTE user, caller re-fetches canonical record"
  - "IOException declared on provision() for caller compatibility even though implementation never throws it"

patterns-established:
  - "REMOTE user provisioning: build UserConfig directly with UserType.REMOTE, put into userStore -- never use SimpleUserService.createUser() (sets LOCAL type)"
  - "JIT idempotency pattern: swallow Exception from KVStore put(), log at DEBUG, caller reads winner via getUser()"

requirements-completed: [JIT-01, JIT-02, JIT-03]

# Metrics
duration: 10min
completed: 2026-03-12
---

# Phase 32 Plan 02: JIT User Provisioner Summary

**JitUserProvisioner creates Dremio REMOTE users on first Keycloak login via direct KVStore write, bypassing password validation, with race-safe idempotent concurrent-creation handling**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-03-12T16:42:22Z
- **Completed:** 2026-03-12T16:45:53Z
- **Tasks:** 2 (TDD: 1 RED + 1 GREEN)
- **Files modified:** 2 (1 test created, 1 implementation created)

## Accomplishments

- `JitUserProvisioner.provision(username, email)` creates `UserType.REMOTE` user in KVStore with no `UserAuth` record
- Null email stored as empty string (null-safe for service accounts and clients without email claims)
- Concurrent/duplicate provision calls do not throw (Exception caught, logged at DEBUG, caller proceeds to getUser())
- All 5 new tests green; all 17 previously passing keycloak tests still pass (22 total)
- TDD RED state confirmed by compilation failure (`cannot find symbol: class JitUserProvisioner`)

## Task Commits

Each task was committed atomically:

1. **Task 1 (RED): Failing tests for JitUserProvisioner** - `6c68d1bfc` (test)
2. **Task 2 (GREEN): JitUserProvisioner implementation** - `50d7e2d12` (feat)

**Plan metadata:** (docs commit below)

_Note: TDD plan — test file committed before implementation (RED), then implementation (GREEN)._

## Files Created/Modified

- `services/keycloak/src/test/java/com/dremio/service/keycloak/TestJitUserProvisioner.java` - 5 unit tests covering JIT-01, JIT-02, JIT-03 using Mockito-mocked LegacyIndexedStore
- `services/keycloak/src/main/java/com/dremio/service/keycloak/JitUserProvisioner.java` - REMOTE user creation with memoized store, null-safe email, idempotent exception handling

## Decisions Made

- **Catch `Exception` (not `UserAlreadyExistException`) in `provision()`:** `LegacyIndexedStore.put()` does not declare a checked `UserAlreadyExistException` — that exception is thrown by `SimpleUserService.createUser()` which we bypass. The broad `Exception` catch handles any runtime failure from a concurrent write race.
- **No `hasAuth()` method on `UserInfo`:** Protostuff-generated `UserInfo` uses plain field; absence of auth is tested via `assertThat(captured.getAuth()).isNull()`.
- **`IOException` declared but never thrown:** Keeps method signature compatible with callers (DACAuthFilter integration in Plan 33) that declare `throws IOException`.
- **No pre-existence check:** Direct `put()` without prior `findUserByUserName()` check avoids an extra KVStore read on every JIT provision; the concurrent race is handled by swallowing the write exception.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- `UserInfo.hasAuth()` does not exist (protostuff generates plain field, not proto3 `has_` method). Fixed in test to use `assertThat(captured.getAuth()).isNull()` before committing.
- Maven `-am` flag also builds `dremio-build-tools-configs` which has no tests and causes surefire failure. Used direct `-pl services/keycloak` without `-am` to target the correct module.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `JitUserProvisioner` is complete and ready for wiring in Plan 33 (DACAuthFilter JIT integration)
- The `provision()` signature matches what `DACAuthFilter.filter()` will call with `KeycloakTokenDetails.getEmail()`
- Plan 03 (`KeycloakRoleSyncer`) can proceed independently of Plan 02

---
*Phase: 32-jit-provisioning-role-mapping*
*Completed: 2026-03-12*

## Self-Check: PASSED

- FOUND: services/keycloak/src/main/java/com/dremio/service/keycloak/JitUserProvisioner.java
- FOUND: services/keycloak/src/test/java/com/dremio/service/keycloak/TestJitUserProvisioner.java
- FOUND: .planning/phases/32-jit-provisioning-role-mapping/32-02-SUMMARY.md
- FOUND commit 6c68d1bfc: test(32-02): add failing tests for JitUserProvisioner (JIT-01, JIT-02, JIT-03)
- FOUND commit 50d7e2d12: feat(32-02): implement JitUserProvisioner with REMOTE user creation (JIT-01, JIT-02, JIT-03)
