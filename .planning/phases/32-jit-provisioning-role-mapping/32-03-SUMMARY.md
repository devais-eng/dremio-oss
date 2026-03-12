---
phase: 32-jit-provisioning-role-mapping
plan: 03
subsystem: auth
tags: [keycloak, jwt, rbac, java, mockito]

# Dependency graph
requires:
  - phase: 32-jit-provisioning-role-mapping/32-01
    provides: Membership.source proto field, RbacService 4-arg addMembership overload, KeycloakTokenDetails
  - phase: 32-jit-provisioning-role-mapping/32-02
    provides: JitUserProvisioner for context on sibling class

provides:
  - KeycloakRoleSyncer class with syncRoles(username, keycloakRoles) implementing additive/authoritative RBAC sync
  - Unit test suite TestKeycloakRoleSyncer covering ROLE-01 through ROLE-04

affects:
  - 32-04-dacauthfilter-jit-wiring (will inject KeycloakRoleSyncer into DACAuthFilter)
  - 35-arrow-flight-keycloak-auth (same role sync needed on Flight path)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "RoleStore.get(roleId) pre-filter before addMembership: avoids RbacEntityNotFoundException for unmapped Keycloak roles"
    - "Exception-safe syncRoles() wraps all logic in try-catch: role sync failure never surfaces as 401"
    - "keycloak-sourced membership filter via 'keycloak'.equals(m.getSource()): proto3 string default is '' not null"
    - "ADMIN_ROLE_ID bypass: synthetic built-in role skips roleStore.get() check"

key-files:
  created:
    - services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakRoleSyncer.java
    - services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakRoleSyncer.java
  modified:
    - services/keycloak/pom.xml

key-decisions:
  - "KeycloakRoleSyncer takes RoleStore directly (not RbacService.listAllRoles()) to pre-filter unmapped roles without adding a new method to RbacService"
  - "syncRolesInternal() separates exception-safe wrapper from actual logic — cleaner than try-catch inline"
  - "dremio-sabot-kernel added as explicit dependency to services/keycloak pom.xml (RbacService/RoleStore are in sabot-kernel)"

patterns-established:
  - "Role sync is idempotent by design: RbacEntityAlreadyExistsException from addMembership is silently swallowed"
  - "Only keycloak-sourced memberships (source='keycloak') are candidates for authoritative-mode revocation"

requirements-completed: [ROLE-01, ROLE-02, ROLE-03, ROLE-04]

# Metrics
duration: 20min
completed: 2026-03-12
---

# Phase 32 Plan 03: KeycloakRoleSyncer Summary

**Keycloak realm_access.roles to Dremio RBAC membership sync with additive/authoritative mode and source-tagged idempotency**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-03-12T17:45:00Z
- **Completed:** 2026-03-12T18:05:00Z
- **Tasks:** 2 (TDD: RED + GREEN)
- **Files modified:** 3 (2 created, 1 modified)

## Accomplishments

- Created `KeycloakRoleSyncer` with `syncRoles(username, keycloakRoles)` implementing additive and authoritative role sync modes
- Additive mode: grants Keycloak roles present in token; never removes manually-assigned memberships (source="")
- Authoritative mode: additionally revokes keycloak-sourced memberships (source="keycloak") not in current token
- ROLE-04: pre-filters Keycloak roles via `roleStore.get()` — unmapped roles silently skipped, no exceptions
- Exception-safe: `syncRoles()` never throws to caller; role sync failures logged at WARN and swallowed
- 9 unit tests covering all 7 plan behaviors + 2 additional edge cases (removal idempotency, exception safety)
- All 31 keycloak module tests pass (7 KeycloakConfig + 10 OidcTokenValidator + 5 JitUserProvisioner + 9 KeycloakRoleSyncer)

## Task Commits

Each TDD task was committed atomically:

1. **Task 1 (RED): Add failing tests for KeycloakRoleSyncer** - `2c36bd6a8` (test)
2. **Task 2 (GREEN): Implement KeycloakRoleSyncer** - `34cf95477` (feat)

**Plan metadata:** (docs commit below)

_Note: TDD tasks follow test → implementation commit pattern_

## Files Created/Modified

- `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakRoleSyncer.java` - New class: additive/authoritative role sync between Keycloak JWT roles and Dremio RBAC memberships
- `services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakRoleSyncer.java` - 9-test suite covering ROLE-01 through ROLE-04 including idempotency and exception-safety
- `services/keycloak/pom.xml` - Added `dremio-sabot-kernel` dependency for RbacService/RoleStore access

## Decisions Made

- `KeycloakRoleSyncer` takes `RoleStore` directly for ROLE-04 filtering: avoids adding a `listAllRoleIds()` method to `RbacService`; per-role `roleStore.get()` check is simpler and avoids bulk load on every login
- `dremio-sabot-kernel` added as explicit pom dependency: `RbacService` and `RoleStore` are in sabot-kernel, not in a services module; the keycloak module previously had no dependency on it
- `syncRolesInternal()` separates the exception-safe wrapper (`syncRoles`) from the actual logic: cleaner than nested try-catch and enables the inner method to declare `throws RbacEntityNotFoundException`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added dremio-sabot-kernel dependency to keycloak pom.xml**
- **Found during:** Task 1 (RED: test compilation)
- **Issue:** `RbacService`, `RoleStore`, `RbacEntityAlreadyExistsException`, `RbacEntityNotFoundException`, and `RbacProto.Membership` are in `sabot/kernel`. The keycloak pom.xml had no dependency on `dremio-sabot-kernel`. Test compilation failed with "package com.dremio.exec.rbac does not exist".
- **Fix:** Added `<dependency><groupId>com.dremio.sabot</groupId><artifactId>dremio-sabot-kernel</artifactId></dependency>` to services/keycloak/pom.xml
- **Files modified:** `services/keycloak/pom.xml`
- **Verification:** All 9 tests compiled and passed after adding the dependency
- **Committed in:** `2c36bd6a8` (RED test commit)

---

**Total deviations:** 1 auto-fixed (1 blocking missing dependency)
**Impact on plan:** The missing dependency was essential for compilation. No scope creep — the dependency is directly required by the classes used in KeycloakRoleSyncer.

## Issues Encountered

- Stale compiled `dremio-sabot-kernel.jar` did not have the 4-arg `addMembership` overload (added in Plan 32-01). Required running `mvn install -pl sabot/kernel -DskipTests` before the keycloak module tests could be compiled and run. This is a normal Maven multi-module workflow issue (not a code problem).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `KeycloakRoleSyncer` is fully tested and ready for injection into `DACAuthFilter`
- Plan 32-04 (DACAuthFilter JIT wiring) can inject `KeycloakRoleSyncer` and call `syncRoles(username, ktd.getRealmRoles())` after user lookup
- All three keycloak service classes (`JitUserProvisioner`, `KeycloakRoleSyncer`, `OidcTokenValidator`) are now complete

---
*Phase: 32-jit-provisioning-role-mapping*
*Completed: 2026-03-12*

## Self-Check: PASSED

- FOUND: services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakRoleSyncer.java
- FOUND: services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakRoleSyncer.java
- FOUND: .planning/phases/32-jit-provisioning-role-mapping/32-03-SUMMARY.md
- FOUND commit 2c36bd6a8: test(32-03): add failing tests for KeycloakRoleSyncer
- FOUND commit 34cf95477: feat(32-03): implement KeycloakRoleSyncer additive/authoritative RBAC sync
- FOUND commit f98fa53d8: docs(32-03): complete KeycloakRoleSyncer plan summary and state update
