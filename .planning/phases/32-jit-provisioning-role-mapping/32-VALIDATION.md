---
phase: 32
slug: jit-provisioning-role-mapping
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-12
---

# Phase 32 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 Jupiter (consistent with Phase 30/31 pattern) |
| **Config file** | `pom.xml` — Maven Surefire picks up both JUnit 4 and 5 |
| **Quick run command** | `mvn test -pl services/keycloak,dac/backend -Dtest="TestJitUserProvisioner,TestKeycloakRoleSyncer,TestDACAuthFilterJit" -am -DfailIfNoTests=false` |
| **Full suite command** | `mvn test -pl services/keycloak,sabot/kernel,dac/backend -am -DfailIfNoTests=false` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl services/keycloak -am -DfailIfNoTests=false`
- **After every plan wave:** Run `mvn test -pl services/keycloak,sabot/kernel,dac/backend -am -DfailIfNoTests=false`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 32-01-01 | 01 | 1 | JIT-01 | unit | `mvn test -pl services/keycloak -Dtest=TestJitUserProvisioner#testProvisionNewUser -am` | ❌ W0 | ⬜ pending |
| 32-01-02 | 01 | 1 | JIT-02 | unit | `mvn test -pl services/keycloak -Dtest=TestJitUserProvisioner#testProvisionedUserIsRemoteType -am` | ❌ W0 | ⬜ pending |
| 32-01-03 | 01 | 1 | JIT-02 | unit | `mvn test -pl services/users -Dtest=TestSimpleUserService#testRemoteUserCannotAuthenticate -am` | ✅ (pattern exists) | ⬜ pending |
| 32-01-04 | 01 | 1 | JIT-03 | unit | `mvn test -pl services/keycloak -Dtest=TestJitUserProvisioner#testConcurrentProvisioningIsIdempotent -am` | ❌ W0 | ⬜ pending |
| 32-02-01 | 02 | 1 | ROLE-01 | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakRoleSyncer#testRolesGrantedOnLogin -am` | ❌ W0 | ⬜ pending |
| 32-02-02 | 02 | 1 | ROLE-02 | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakRoleSyncer#testAdditiveModePreservesManualRoles -am` | ❌ W0 | ⬜ pending |
| 32-02-03 | 02 | 1 | ROLE-03 | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakRoleSyncer#testAuthoritativeModeRevokesStaleRoles -am` | ❌ W0 | ⬜ pending |
| 32-02-04 | 02 | 1 | ROLE-04 | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakRoleSyncer#testUnknownKeycloakRolesIgnored -am` | ❌ W0 | ⬜ pending |
| 32-03-01 | 03 | 2 | JIT-01 + ROLE-01 | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterJit -am` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestJitUserProvisioner.java` — stubs for JIT-01, JIT-02, JIT-03
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakRoleSyncer.java` — stubs for ROLE-01, ROLE-02, ROLE-03, ROLE-04
- [ ] `dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterJit.java` — integration unit test for filter() JIT + role sync path
- [ ] `sabot/kernel/src/main/protobuf/rbac.proto` — add `string source = 5` to `Membership` message
- [ ] `services/keycloak/pom.xml` — add `services/users` dependency if not already present

*Existing infrastructure covers test framework; only new test files and proto extension needed.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| End-to-end: Keycloak user's first REST call provisions user + syncs roles | JIT-01 + ROLE-01 | Requires live Keycloak + Dremio deployment | 1. Create Keycloak user with realm role matching Dremio RBAC role. 2. Call Dremio REST API with bearer token. 3. Verify user created and role assigned in Dremio UI. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
