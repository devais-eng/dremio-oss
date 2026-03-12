---
phase: 32-jit-provisioning-role-mapping
plan: 01
subsystem: auth
tags: [keycloak, jwt, rbac, protobuf, java]

# Dependency graph
requires:
  - phase: 30-jwt-validation-infrastructure-config
    provides: OidcTokenValidator with JWKS-backed RS256 validation
  - phase: 31-rest-api-bearer-jwt-authentication
    provides: DACAuthFilter Keycloak JWT dispatch via eyJ discriminator

provides:
  - Membership.source proto field (field 5) for tagging Keycloak-synced memberships
  - RbacService.addMembership(userName, roleId, grantedBy, source) 4-arg overload
  - KeycloakTokenDetails immutable value class (username, email, realmRoles, expiresAt)
  - OidcTokenValidator.validateWithClaims() returning KeycloakTokenDetails with realm_access.roles
  - dremio-services-users dependency in keycloak pom.xml for JitUserProvisioner (Plan 02)

affects:
  - 32-02-jit-user-provisioner
  - 32-03-keycloak-role-syncer
  - 35-arrow-flight-keycloak-auth

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "3-arg addMembership delegates to 4-arg source-aware overload (backward-compatible extension)"
    - "validateWithClaims() as new entry point alongside validate() for COEX-02 compatibility"
    - "extractRealmRoles() safely parses nested realm_access.roles JSON object claim"

key-files:
  created:
    - services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakTokenDetails.java
  modified:
    - sabot/kernel/src/main/protobuf/rbac.proto
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
    - services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java
    - services/keycloak/pom.xml

key-decisions:
  - "Membership.source field 5 is proto3 string (default empty) — all existing records deserialize with source='' without migration"
  - "3-arg addMembership delegates to 4-arg overload — no code duplication, callers unaffected"
  - "validateWithClaims() is a separate method, not a replacement — DACAuthFilter continues calling validate() for COEX-02"
  - "extractRealmRoles() swallows ParseException and returns emptyList() — missing roles degrade gracefully, never crash the auth filter"

patterns-established:
  - "Source-tagged membership: pass source='keycloak' to addMembership to distinguish Keycloak-synced from manually-assigned roles"
  - "Extended JWT claim extraction: validateWithClaims() reuses jwtProcessor (same class, same field) without visibility changes"

requirements-completed: [ROLE-01, ROLE-02, ROLE-03]

# Metrics
duration: 15min
completed: 2026-03-12
---

# Phase 32 Plan 01: JIT Provisioning Foundation Summary

**Proto Membership.source field, RbacService 4-arg addMembership overload, KeycloakTokenDetails value class, and OidcTokenValidator.validateWithClaims() returning realm_access.roles**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-03-12T17:20:00Z
- **Completed:** 2026-03-12T17:38:00Z
- **Tasks:** 2
- **Files modified:** 5 (1 created, 4 modified)

## Accomplishments

- Added `string source = 5` to Membership proto (backwards-compatible; proto3 default is "" so all existing records deserialize without migration)
- Added `RbacService.addMembership(userName, roleId, grantedBy, source)` 4-arg overload; existing 3-arg delegates with `source=""`
- Created `KeycloakTokenDetails` immutable value class carrying username, email, realmRoles (never null), expiresAt
- Added `OidcTokenValidator.validateWithClaims()` extracting email and `realm_access.roles` from Keycloak JWTs
- Updated keycloak `pom.xml` with `dremio-services-users` (Plan 02 needs `UserGroupStoreBuilder`) and `mockito-core` (test scope for Plans 02/03)
- All 17 existing keycloak tests pass after changes (7 KeycloakConfig + 10 OidcTokenValidator)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Membership.source proto field and RbacService 4-arg addMembership overload** - `2465605f4` (feat)
2. **Task 2: Create KeycloakTokenDetails, add validateWithClaims(), update keycloak pom.xml** - `237437ab3` (feat)

**Plan metadata:** (docs commit below)

## Files Created/Modified

- `sabot/kernel/src/main/protobuf/rbac.proto` - Added `string source = 5` to Membership message
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` - Added 4-arg `addMembership` overload; 3-arg delegates to it
- `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakTokenDetails.java` - New immutable value class (username, email, realmRoles, expiresAt)
- `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java` - Added `validateWithClaims()` and `extractRealmRoles()` private helper
- `services/keycloak/pom.xml` - Added `dremio-services-users` and `mockito-core` (test scope)

## Decisions Made

- `Membership.source` uses proto3 string (default "") rather than an enum — allows adding new source values without proto schema changes
- `3-arg addMembership` delegates to `4-arg` rather than duplicating logic — single code path, no divergence risk
- `validateWithClaims()` is a separate method alongside `validate()` — `DACAuthFilter` continues calling `validate()` (COEX-02 compatibility; Phase 32 callers will use `validateWithClaims()`)
- `extractRealmRoles()` swallows `ParseException` and returns empty list — missing/malformed roles degrade gracefully rather than failing auth

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - both tasks compiled cleanly on first attempt and all 17 tests passed.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Plan 02 (JitUserProvisioner) can now import `KeycloakTokenDetails`, call `validateWithClaims()`, and use `RbacService.addMembership(4-arg)` with `source="keycloak"`
- Plan 03 (KeycloakRoleSyncer) can use `KeycloakTokenDetails.getRealmRoles()` for role mapping
- `dremio-services-users` dependency is in place for `UserGroupStoreBuilder` access in Plan 02
- `mockito-core` test dependency is in place for Plan 02/03 unit tests

---
*Phase: 32-jit-provisioning-role-mapping*
*Completed: 2026-03-12*

## Self-Check: PASSED

- FOUND: sabot/kernel/src/main/protobuf/rbac.proto
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
- FOUND: services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakTokenDetails.java
- FOUND: services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java
- FOUND: services/keycloak/pom.xml
- FOUND: .planning/phases/32-jit-provisioning-role-mapping/32-01-SUMMARY.md
- FOUND commit 2465605f4: feat(32-01): add Membership.source proto field and RbacService 4-arg addMembership overload
- FOUND commit 237437ab3: feat(32-01): add KeycloakTokenDetails, validateWithClaims(), and keycloak pom deps
