---
phase: 32-jit-provisioning-role-mapping
plan: 04
subsystem: auth
tags: [keycloak, jwt, jit-provisioning, rbac, java, mockito]

# Dependency graph
requires:
  - phase: 32-jit-provisioning-role-mapping/32-01
    provides: KeycloakTokenDetails with username/email/realmRoles, OidcTokenValidator.validateWithClaims()
  - phase: 32-jit-provisioning-role-mapping/32-02
    provides: JitUserProvisioner.provision(username, email)
  - phase: 32-jit-provisioning-role-mapping/32-03
    provides: KeycloakRoleSyncer.syncRoles(username, roles)
  - phase: 31-rest-api-bearer-jwt-authentication/31-01
    provides: DACAuthFilter Keycloak JWT dispatch (eyJ discriminator, OidcTokenValidator wiring)

provides:
  - DACAuthFilter.filter() provisions new Keycloak users via JitUserProvisioner on UserNotFoundException
  - DACAuthFilter.filter() syncs realm_access.roles via KeycloakRoleSyncer on every Keycloak request
  - DACDaemonModule keycloak branch binds JitUserProvisioner and KeycloakRoleSyncer
  - KeycloakTokenDetails passed thread-safely via ContainerRequestContext property (not instance field)
  - TestDACAuthFilterJit: 7 integration tests covering full JIT + role sync filter flow

affects:
  - 35-arrow-flight-keycloak-auth (same JIT/sync pattern needed on Flight path)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ContainerRequestContext.setProperty/getProperty as per-request property bag for thread-safe token details in singleton filter"
    - "JIT provisioning: catch UserNotFoundException -> provision -> retry getUser() pattern"
    - "Role sync runs AFTER user lookup (user must exist before memberships can be added)"
    - "validateWithClaims() on Keycloak path when jitProvisioner non-null; validate() preserved for Phase 31 compatibility"
    - "Test mock property bag: doAnswer on setProperty/when on getProperty to simulate ContainerRequestContext property storage"

key-files:
  created:
    - dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterJit.java
  modified:
    - dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java
    - dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java

key-decisions:
  - "KeycloakTokenDetails stored in ContainerRequestContext property (not DACAuthFilter instance field): DACAuthFilter is a singleton, instance fields are shared across threads -- per Research Pitfall 1"
  - "getUserNameFromToken() uses validateWithClaims() when jitProvisioner non-null, validate() otherwise: preserves Phase 31 behavior (COEX-02) when JIT not active"
  - "role sync runs AFTER user lookup and provisioning: user must exist in RBAC store before membership records can be added"
  - "Test mock uses doAnswer to simulate property bag: ContainerRequestContext.setProperty/getProperty on Mockito mock don't correlate by default"

patterns-established:
  - "JIT provisioning gate: jitProvisioner != null AND ktd != null guards the Keycloak-only code path"
  - "IOException from provision wraps to NotAuthorizedException -> 401 (provisioning failure is auth failure)"
  - "Role sync on every Keycloak request (not just first login): roles evolve in Keycloak, sync must stay current"

requirements-completed: [JIT-01, JIT-02, JIT-03, ROLE-01]

# Metrics
duration: 10min
completed: 2026-03-12
---

# Phase 32 Plan 04: DACAuthFilter JIT Wiring Summary

**JIT user provisioning and Keycloak role sync wired into DACAuthFilter.filter() — new Keycloak users auto-provisioned on first REST API call, realm roles synced on every authenticated request**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-03-12T16:59:07Z
- **Completed:** 2026-03-12T17:08:35Z
- **Tasks:** 2 (TDD: RED + GREEN)
- **Files modified:** 3 (1 created, 2 modified)

## Accomplishments

- `DACAuthFilter` now calls `JitUserProvisioner.provision()` on `UserNotFoundException` (Keycloak path only), retries `getUser()` after provisioning — first REST API call for a new Keycloak user auto-creates their Dremio account
- `DACAuthFilter` calls `KeycloakRoleSyncer.syncRoles()` after every successful Keycloak-authenticated user lookup — roles stay in sync with Keycloak on every request without a separate sync job
- `DACDaemonModule` keycloak branch binds `JitUserProvisioner` and `KeycloakRoleSyncer` via `registry.bind()` — both are `@Inject @Nullable` in DACAuthFilter (inactive when auth.type=internal or ldap)
- `getUserNameFromToken()` upgraded to call `validateWithClaims()` when JIT is active — captures email and realm roles into `KeycloakTokenDetails` stored per-request via `ContainerRequestContext.setProperty()` (thread-safe singleton pattern)
- 7 new integration unit tests covering JIT provisioning, role sync, both in sequence, opaque token path bypass, fallback path bypass, provision retry success, and provision failure → 401
- All 13 DACAuthFilter tests pass (6 Phase 31 COEX tests + 7 new JIT/sync tests)
- All 31 keycloak module tests pass (Phase 30-32 suite: 7 KeycloakConfig + 10 OidcTokenValidator + 5 JitUserProvisioner + 9 KeycloakRoleSyncer)

## Task Commits

TDD workflow produced two commits:

1. **Task 1 (RED): Add failing tests for JIT + role sync** - `0284d0178` (test)
2. **Task 1 (GREEN): Wire JIT + role sync into DACAuthFilter and DACDaemonModule** - `3c1ee2c13` (feat)

**Plan metadata:** (docs commit below)

_Note: TDD tasks follow test → implementation commit pattern. Both tasks (filter wiring + DI bindings) were integrated into a single TDD cycle per plan design._

## Files Created/Modified

- `dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterJit.java` - 7-test suite covering JIT-01, JIT-03, ROLE-01 and non-Keycloak path isolation
- `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` - JIT provisioning in filter(), role sync after user lookup, validateWithClaims() in getUserNameFromToken(), new JitUserProvisioner/KeycloakRoleSyncer @Inject fields
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` - JitUserProvisioner and KeycloakRoleSyncer bindings in keycloak branch

## Decisions Made

- `KeycloakTokenDetails` stored in `ContainerRequestContext.setProperty("keycloak.token.details", ktd)` rather than instance field: `DACAuthFilter` is a HK2 singleton shared across all request threads; per-request state must use the request context
- `getUserNameFromToken()` selects `validateWithClaims()` vs `validate()` based on `jitProvisioner != null`: preserves exact Phase 31 behavior for non-JIT deployments; no conditional compile flags needed
- Role sync runs AFTER user provisioning (and after the retry getUser()): the RBAC membership store requires the user to exist before `addMembership()` can succeed
- Test mock uses `doAnswer` on `setProperty` + `when(getProperty)` answer: Mockito mock's `setProperty()` is a void method that doesn't correlate with `getProperty()` by default; simulating a property bag required explicit answer wiring

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed ContainerRequestContext mock property bag for tests**
- **Found during:** Task 2 (GREEN: running tests after implementation)
- **Issue:** Tests for JIT and role sync failed because `requestContext.getProperty("keycloak.token.details")` returned `null` — Mockito mock's `setProperty()` doesn't correlate with `getProperty()` by default
- **Fix:** Added `doAnswer` to simulate HashMap-backed property bag in `@BeforeEach`; `setProperty` stores in local `HashMap`, `getProperty` reads from same map via `thenAnswer`
- **Files modified:** `TestDACAuthFilterJit.java`
- **Verification:** All 7 tests pass including tests that require ktd to be non-null
- **Committed in:** `3c1ee2c13` (GREEN implementation commit)

**2. [Rule 1 - Bug] Removed unused `eq` import after spotless apply**
- **Found during:** Task 2 (spotless formatting pass)
- **Issue:** `import static org.mockito.ArgumentMatchers.eq` was in original test draft but not used after simplifying test assertions
- **Fix:** `mvn spotless:apply` automatically removed unused import
- **Files modified:** `TestDACAuthFilterJit.java`
- **Committed in:** `3c1ee2c13` (included in GREEN commit)

---

**Total deviations:** 2 auto-fixed (both Rule 1 - test correctness bugs)
**Impact on plan:** Both fixes were essential for test correctness and code quality. No scope creep.

## Issues Encountered

- `services/keycloak` module needed reinstalling to local Maven repo (stale JAR from previous session). Required `mvn spotless:apply -pl services/keycloak` then `mvn install -pl services/keycloak -DskipTests` before `dac/backend` tests could compile. This is normal Maven multi-module workflow (not a code issue).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 32 (JIT Provisioning + Role Mapping) is now complete: all 4 plans done
  - 32-01: KeycloakTokenDetails + proto Membership.source field + 4-arg addMembership
  - 32-02: JitUserProvisioner (KV store user creation)
  - 32-03: KeycloakRoleSyncer (additive/authoritative RBAC sync)
  - 32-04: DACAuthFilter wiring (this plan)
- A new Keycloak user's first REST API call auto-provisions their account and syncs their roles end-to-end
- Phase 33+ can proceed knowing the full Keycloak auth + JIT + RBAC sync chain is operational
- Phase 35 (Arrow Flight) will need the same JIT/sync pattern applied to the Flight auth handler

---
*Phase: 32-jit-provisioning-role-mapping*
*Completed: 2026-03-12*

## Self-Check: PASSED

- FOUND: dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java
- FOUND: dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java
- FOUND: dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterJit.java
- FOUND: .planning/phases/32-jit-provisioning-role-mapping/32-04-SUMMARY.md
- FOUND commit 0284d017: test(32-04): add failing tests for DACAuthFilter JIT + role sync wiring
- FOUND commit 3c1ee2c1: feat(32-04): wire JIT + role sync into DACAuthFilter and DACDaemonModule
