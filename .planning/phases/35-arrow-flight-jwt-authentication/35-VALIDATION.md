---
phase: 35
slug: arrow-flight-jwt-authentication
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-13
---

# Phase 35 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 (Maven Surefire) |
| **Config file** | `pom.xml` — Maven Surefire picks up JUnit 4 |
| **Quick run command** | `mvn test -pl services/arrow-flight -Dtest="TestDremioCredentialValidator,TestDremioBearerTokenAuthenticator,TestDremioFlightServerAuthValidator" -am -DfailIfNoTests=false` |
| **Full suite command** | `mvn test -pl services/arrow-flight -am -DfailIfNoTests=false` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl services/arrow-flight -Dtest="TestDremioCredentialValidator,TestDremioBearerTokenAuthenticator,TestDremioFlightServerAuthValidator" -am -DfailIfNoTests=false`
- **After every plan wave:** Run `mvn test -pl services/arrow-flight -am -DfailIfNoTests=false`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 35-01-01 | 01 | 1 | JDBC-01 | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioCredentialValidator#testValidateWithKeycloakJwt -am` | ❌ W0 | ⬜ pending |
| 35-01-02 | 01 | 1 | JDBC-01 | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioCredentialValidator#testValidateWithDremioPassword -am` | ✅ | ⬜ pending |
| 35-01-03 | 01 | 1 | JDBC-01 | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioFlightServerAuthValidator#testGetTokenWithKeycloakJwt -am` | ❌ W0 | ⬜ pending |
| 35-01-04 | 01 | 1 | JDBC-01 | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioBearerTokenAuthenticator#testValidateBearerWithKeycloakJwt -am` | ❌ W0 | ⬜ pending |
| 35-01-05 | 01 | 1 | JDBC-01 | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioBearerTokenAuthenticator#testValidateBearerWithValidToken -am` | ✅ | ⬜ pending |
| 35-01-06 | 01 | 1 | JDBC-02 | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioCredentialValidator#testValidateWithKeycloakJwtCallsJit -am` | ❌ W0 | ⬜ pending |
| 35-01-07 | 01 | 1 | JDBC-01 | unit | `mvn test -pl services/arrow-flight -Dtest=TestDremioCredentialValidator#testValidateWithInvalidKeycloakJwt -am` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `TestDremioCredentialValidator#testValidateWithKeycloakJwt` — JDBC-01: mock `OidcTokenValidator.validateWithClaims()`, verify returned `AuthResult.getPeerIdentity()` = keycloak username
- [ ] `TestDremioCredentialValidator#testValidateWithKeycloakJwtCallsJit` — JDBC-02: verify `jitProvisioner.provision()` is called
- [ ] `TestDremioCredentialValidator#testValidateWithInvalidKeycloakJwt` — JDBC-01: invalid JWT throws `FlightRuntimeException(UNAUTHENTICATED)`
- [ ] `TestDremioBearerTokenAuthenticator#testValidateBearerWithKeycloakJwt` — JDBC-01: `eyJ`-prefixed bearer validated via OIDC
- [ ] `TestDremioFlightServerAuthValidator#testGetTokenWithKeycloakJwt` — JDBC-01: legacy mode JWT dispatch
- [ ] `services/arrow-flight/pom.xml` — add `dremio-services-keycloak` runtime dependency

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Flight SQL client connects with Keycloak JWT token | JDBC-01 | Requires running Keycloak + Dremio + Flight SQL client | 1. Start Keycloak + Dremio via docker-compose 2. Get access token from Keycloak 3. Connect via Flight SQL JDBC with token as password 4. Execute query |
| JIT provisioning on first Flight SQL login | JDBC-02 | Requires full stack with Keycloak user not in Dremio | 1. Create new user in Keycloak 2. Connect via Flight SQL with their token 3. Verify user appears in Dremio admin UI |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
