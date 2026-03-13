---
phase: 32-jit-provisioning-role-mapping
verified: 2026-03-12T18:30:00Z
status: passed
score: 13/13 must-haves verified
---

# Phase 32: JIT Provisioning + Role Mapping Verification Report

**Phase Goal:** A Keycloak user who has never logged into Dremio is automatically provisioned on their first API call or login, with Keycloak realm roles synced to Dremio RBAC memberships
**Verified:** 2026-03-12T18:30:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | Membership proto has a source field (field 5) that defaults to empty string | VERIFIED | `rbac.proto` line 62: `string source = 5;` with comment `"keycloak" for Keycloak-synced; "" for manually-assigned` |
| 2  | RbacService.addMembership() accepts an optional source parameter stored in the Membership | VERIFIED | `RbacService.java` lines 237-258: 4-arg overload builds Membership with `.setSource(source)`; 3-arg delegates to it with `source=""` |
| 3  | OidcTokenValidator.validateWithClaims() returns username, email, and realm_access.roles | VERIFIED | `OidcTokenValidator.java` lines 130-148: method present, parses all three, returns `new KeycloakTokenDetails(username, email, realmRoles, expiresAt)` |
| 4  | KeycloakTokenDetails carries username, email, realmRoles, and expiresAt | VERIFIED | `KeycloakTokenDetails.java`: immutable value class with all four fields and getters, null-safe realmRoles |
| 5  | services/keycloak pom.xml has dremio-services-users and dremio-sabot-kernel | VERIFIED | `pom.xml` lines 45, 50: both artifacts present |
| 6  | JitUserProvisioner.provision(username, email) creates a UserType.REMOTE user in the KVStore | VERIFIED | `JitUserProvisioner.java` lines 81-99: builds `UserConfig` with `.setType(UserType.REMOTE)`, puts into `userStore.get()` |
| 7  | The provisioned user has no UserAuth record (no password stored) | VERIFIED | `JitUserProvisioner.java`: only `UserInfo().setConfig(config)` is created; no `UserAuth` record built or stored. `TestJitUserProvisioner.java` line 104 asserts `captured.getAuth()` is null |
| 8  | Calling provision() for an already-existing user does not throw (idempotent, JIT-03) | VERIFIED | `JitUserProvisioner.java` lines 92-98: broad `catch (Exception e)` logs at DEBUG and returns; `TestJitUserProvisioner` test `testConcurrentProvisioningIsIdempotent` confirms |
| 9  | KeycloakRoleSyncer.syncRoles() grants Keycloak roles that match existing Dremio RBAC roles | VERIFIED | `KeycloakRoleSyncer.java` lines 116-124: calls `rbacService.addMembership(username, roleId, "keycloak", "keycloak")` for valid roles |
| 10 | In additive mode, manually-assigned Dremio memberships (source='') are never removed | VERIFIED | `KeycloakRoleSyncer.java` lines 129-140: removal only occurs when `!keycloakConfig.isAdditive()`, and only for memberships where `"keycloak".equals(m.getSource())` |
| 11 | In authoritative mode, Keycloak-sourced memberships not in current token are revoked | VERIFIED | `KeycloakRoleSyncer.java` lines 129-140: authoritative path calls `rbacService.removeMembership(username, existingKcRole)` for stale keycloak-sourced roles |
| 12 | DACAuthFilter.filter() provisions new Keycloak users on UserNotFoundException and syncs roles on every Keycloak request | VERIFIED | `DACAuthFilter.java` lines 74-113: UserNotFoundException catch with `jitProvisioner.provision()` + retry; `roleSyncer.syncRoles()` after every successful Keycloak-path user lookup |
| 13 | JitUserProvisioner and KeycloakRoleSyncer are bound in DACDaemonModule keycloak branch | VERIFIED | `DACDaemonModule.java` lines 2243-2251: `registry.bind(JitUserProvisioner.class, new JitUserProvisioner(...))` and `registry.bind(KeycloakRoleSyncer.class, new KeycloakRoleSyncer(...))` |

**Score:** 13/13 truths verified

---

### Required Artifacts

| Artifact | Provides | Status | Details |
|----------|----------|--------|---------|
| `sabot/kernel/src/main/protobuf/rbac.proto` | Membership.source field (field 5) | VERIFIED | Present at line 62; proto3 string default = "" |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` | addMembership(4-arg) overload | VERIFIED | Lines 237-258; 3-arg delegates at lines 218-221 |
| `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakTokenDetails.java` | Immutable value class | VERIFIED | 57 lines; all 4 fields + getters |
| `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java` | validateWithClaims() | VERIFIED | Lines 130-168; method + extractRealmRoles() helper |
| `services/keycloak/src/main/java/com/dremio/service/keycloak/JitUserProvisioner.java` | REMOTE user creation | VERIFIED | 100 lines; substantive implementation (no stubs) |
| `services/keycloak/src/test/java/com/dremio/service/keycloak/TestJitUserProvisioner.java` | 5 unit tests JIT-01/02/03 | VERIFIED | 137 lines; covers username, email, REMOTE type, no auth record, null email, idempotency |
| `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakRoleSyncer.java` | Additive/authoritative role sync | VERIFIED | 142 lines; substantive implementation |
| `services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakRoleSyncer.java` | 9 unit tests ROLE-01/02/03/04 | VERIFIED | 261 lines; covers all 7 plan behaviors + 2 edge cases |
| `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` | JIT provisioning + role sync in filter() | VERIFIED | Lines 68-69: `@Inject @Nullable` jitProvisioner/roleSyncer; lines 82-105: full JIT+sync flow |
| `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` | JitUserProvisioner and KeycloakRoleSyncer DI bindings | VERIFIED | Lines 2243-2251: both bound in keycloak branch |
| `dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterJit.java` | 7 integration unit tests | VERIFIED | 330 lines; covers JIT-01, JIT-03, ROLE-01, opaque path bypass, fallback bypass, provision failure |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `RbacService.addMembership(4-arg)` | `Membership.setSource(source)` | proto builder | VERIFIED | `RbacService.java` line 256: `.setSource(source)` |
| `OidcTokenValidator.validateWithClaims()` | `KeycloakTokenDetails` | return type | VERIFIED | Line 147: `return new KeycloakTokenDetails(username, email, realmRoles, expiresAt)` |
| `JitUserProvisioner.provision()` | `LegacyIndexedStore.put()` | `userStore.get().put(uid, info)` | VERIFIED | `JitUserProvisioner.java` line 93: `userStore.get().put(uid, info)` |
| `JitUserProvisioner` | `SimpleUserService.UserGroupStoreBuilder` | store creation function | VERIFIED | Line 62: `kvStoreProvider.get().getStore(SimpleUserService.UserGroupStoreBuilder.class)` |
| `KeycloakRoleSyncer.syncRoles()` | `RbacService.addMembership(4-arg)` | grants with source='keycloak' | VERIFIED | Line 119: `rbacService.addMembership(username, roleId, "keycloak", "keycloak")` |
| `KeycloakRoleSyncer.syncRoles()` | `RbacService.removeMembership()` | revokes stale keycloak roles (authoritative) | VERIFIED | Line 133: `rbacService.removeMembership(username, existingKcRole)` |
| `KeycloakRoleSyncer.syncRoles()` | `RoleStore.get(roleId)` | ROLE-04 existence check | VERIFIED | Line 100: `roleStore.get(role) != null` before adding |
| `DACAuthFilter.filter()` | `JitUserProvisioner.provision()` | catch UserNotFoundException -> provision -> retry | VERIFIED | Lines 87-94: `jitProvisioner.provision(ktd.getUsername(), ktd.getEmail())` then retry `getUser()` |
| `DACAuthFilter.filter()` | `KeycloakRoleSyncer.syncRoles()` | after successful user lookup on Keycloak path | VERIFIED | Lines 103-105: `roleSyncer.syncRoles(userName.getName(), ktd.getRealmRoles())` |
| `DACAuthFilter.getUserNameFromToken()` | `OidcTokenValidator.validateWithClaims()` | when jitProvisioner is non-null | VERIFIED | Lines 160-164: `oidcTokenValidator.validateWithClaims(tokenStr)` when `jitProvisioner != null` |
| `DACDaemonModule.setupUserService()` | `JitUserProvisioner constructor` | registry.bind in keycloak branch | VERIFIED | Lines 2243-2245: `new JitUserProvisioner(registry.provider(LegacyKVStoreProvider.class))` |
| `KeycloakTokenDetails` | stored thread-safely in ContainerRequestContext | `requestContext.setProperty("keycloak.token.details", ktd)` | VERIFIED | Lines 163-164: setProperty/getProperty pattern used; not an instance field |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| JIT-01 | 32-02, 32-04 | First Keycloak login auto-creates Dremio user (username from preferred_username, email from email claim) | SATISFIED | `JitUserProvisioner.provision()` builds UserConfig with username+email; `DACAuthFilter` calls it with `ktd.getUsername()`, `ktd.getEmail()`; `TestJitUserProvisioner.testProvisionNewUser` confirms |
| JIT-02 | 32-02 | JIT-provisioned users have locked sentinel (REMOTE type, cannot login via password) | SATISFIED | `JitUserProvisioner.java` line 87: `.setType(UserType.REMOTE)`; no UserAuth record created; `TestJitUserProvisioner` tests `testProvisionedUserIsRemoteType` and `testProvisionedUserHasNoAuthRecord` confirm |
| JIT-03 | 32-02, 32-04 | Concurrent first logins from same Keycloak user do not cause duplicate errors | SATISFIED | `JitUserProvisioner.java` lines 92-98: broad `catch (Exception e)` swallows race failures; `DACAuthFilter` test `testJitProvisionSucceedsAndUserIsRetried` confirms retry-after-provision works |
| ROLE-01 | 32-01, 32-03, 32-04 | On each Keycloak login, realm_access.roles synced to Dremio RBAC memberships | SATISFIED | `KeycloakRoleSyncer.syncRoles()` called on every Keycloak-authenticated `DACAuthFilter` request; `TestDACAuthFilterJit.testExistingKeycloakUserRolesAreSynced` confirms |
| ROLE-02 | 32-01, 32-03 | Additive mode: Keycloak roles granted, existing Dremio-only roles preserved | SATISFIED | `KeycloakRoleSyncer.java` lines 129-140: removal gated on `!keycloakConfig.isAdditive()` AND `"keycloak".equals(source)`; `TestKeycloakRoleSyncer.testAdditiveModePreservesManualRoles` confirms |
| ROLE-03 | 32-01, 32-03 | Authoritative mode: Dremio roles not in Keycloak realm_access.roles revoked on login | SATISFIED | `KeycloakRoleSyncer.java` lines 129-140: `removeMembership` called for keycloak-sourced roles absent from current token; `TestKeycloakRoleSyncer.testAuthoritativeModeRevokesStaleRoles` confirms; manual memberships preserved |
| ROLE-04 | 32-03 | Only pre-existing Dremio roles mapped; unmapped Keycloak roles silently ignored | SATISFIED | `KeycloakRoleSyncer.java` lines 98-105: `roleStore.get(role) != null` pre-filter; `TestKeycloakRoleSyncer.testUnknownKeycloakRolesIgnored` confirms realm-management/offline_access skipped |

All 7 Phase 32 requirements (JIT-01, JIT-02, JIT-03, ROLE-01, ROLE-02, ROLE-03, ROLE-04) are satisfied. No orphaned requirements were found — REQUIREMENTS.md traceability table maps exactly these 7 IDs to Phase 32, all marked Complete.

---

### Anti-Patterns Found

No anti-patterns detected across any Phase 32 implementation files:

- No TODO/FIXME/PLACEHOLDER comments in implementation files
- No stub implementations (`return null`, `return {}`, `return []`, empty handlers)
- All catch blocks log meaningfully (DEBUG or WARN) before returning or continuing
- No empty `{}` method bodies
- No `console.log`-only handlers

---

### Human Verification Required

None of the automated checks have gaps. The following items remain inherently human-verifiable but are out of scope for static analysis:

1. **End-to-end JIT flow with a real Keycloak instance**
   - Test: Configure Keycloak, create a new realm user, make a REST API call with their JWT
   - Expected: User auto-created in Dremio with REMOTE type; subsequent password login fails
   - Why human: Requires a running Keycloak + Dremio environment

2. **Role sync mode toggle behavior (additive vs. authoritative)**
   - Test: Change `services.keycloak.role.sync-mode` from `additive` to `authoritative`, login again with roles changed in Keycloak
   - Expected: Stale Keycloak-sourced Dremio roles revoked; manually-assigned roles preserved
   - Why human: Requires runtime configuration and real Keycloak role changes

---

## Commit Verification

All implementation commits confirmed in git log:

| Commit | Description |
|--------|-------------|
| `2465605f4` | feat(32-01): add Membership.source proto field and RbacService 4-arg addMembership overload |
| `237437ab3` | feat(32-01): add KeycloakTokenDetails, validateWithClaims(), and keycloak pom deps |
| `6c68d1bfc` | test(32-02): add failing tests for JitUserProvisioner (TDD RED) |
| `50d7e2d12` | feat(32-02): implement JitUserProvisioner with REMOTE user creation |
| `2c36bd6a8` | test(32-03): add failing tests for KeycloakRoleSyncer (TDD RED) |
| `34cf95477` | feat(32-03): implement KeycloakRoleSyncer additive/authoritative RBAC sync |
| `0284d0178` | test(32-04): add failing tests for DACAuthFilter JIT + role sync wiring (TDD RED) |
| `3c1ee2c13` | feat(32-04): wire JIT + role sync into DACAuthFilter and DACDaemonModule |

---

## Summary

Phase 32 goal is fully achieved. All three layers are present, substantive, and wired:

**Layer 1 — Foundation contracts (Plan 01):** `Membership.source` proto field enables source-tagged memberships. `RbacService.addMembership(4-arg)` stores the source. `KeycloakTokenDetails` carries email and realm roles. `OidcTokenValidator.validateWithClaims()` extracts them from the JWT.

**Layer 2 — Core services (Plans 02 + 03):** `JitUserProvisioner` creates REMOTE users in the KVStore without passwords, idempotently. `KeycloakRoleSyncer` grants matching roles on login and, in authoritative mode, revokes stale keycloak-sourced roles while preserving manually-assigned ones.

**Layer 3 — Integration (Plan 04):** `DACAuthFilter` calls `validateWithClaims()` on the Keycloak path, stores `KeycloakTokenDetails` per-request (thread-safe), catches `UserNotFoundException` to trigger JIT provisioning, retries `getUser()`, then syncs roles. `DACDaemonModule` binds both services in the keycloak branch. The entire flow is exercised by 7 integration unit tests.

A new Keycloak user's first REST API call will auto-create their Dremio account and sync their Keycloak realm roles to Dremio RBAC.

---

_Verified: 2026-03-12T18:30:00Z_
_Verifier: Claude (gsd-verifier)_
