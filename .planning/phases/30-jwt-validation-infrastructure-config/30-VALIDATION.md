---
phase: 30
slug: jwt-validation-infrastructure-config
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-12
---

# Phase 30 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 + JUnit 5 Jupiter (both present in project) |
| **Config file** | `pom.xml` — Maven Surefire picks up both |
| **Quick run command** | `mvn test -pl services/keycloak -am -DfailIfNoTests=false` |
| **Full suite command** | `mvn test -pl services/tokens,services/keycloak,dac/backend -am -DfailIfNoTests=false` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl services/keycloak -am -DfailIfNoTests=false`
- **After every plan wave:** Run `mvn test -pl services/tokens,services/keycloak,dac/backend -am -DfailIfNoTests=false`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 30-01-01 | 01 | 1 | CFG-01 | unit | `mvn test -pl dac/backend -Dtest=TestDACDaemonModuleKeycloak -am` | ❌ W0 | ⬜ pending |
| 30-01-02 | 01 | 1 | CFG-02 | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakConfig -am` | ❌ W0 | ⬜ pending |
| 30-01-03 | 01 | 1 | CFG-03 | unit | `mvn test -pl services/keycloak -Dtest=TestKeycloakConfig#testRoleSyncMode -am` | ❌ W0 | ⬜ pending |
| 30-02-01 | 02 | 1 | TKN-01 | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testValidToken -am` | ❌ W0 | ⬜ pending |
| 30-02-02 | 02 | 1 | TKN-01 | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testWrongIssuer -am` | ❌ W0 | ⬜ pending |
| 30-02-03 | 02 | 1 | TKN-01 | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testWrongAudience -am` | ❌ W0 | ⬜ pending |
| 30-02-04 | 02 | 1 | TKN-01 | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testExpiredToken -am` | ❌ W0 | ⬜ pending |
| 30-02-05 | 02 | 1 | TKN-01 | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testBadSignature -am` | ❌ W0 | ⬜ pending |
| 30-02-06 | 02 | 1 | TKN-03 | unit | `mvn test -pl services/keycloak -Dtest=TestOidcTokenValidator#testKeyRotationRefetch -am` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `services/keycloak/` — new Maven module (must be created and added to `services/pom.xml`)
- [ ] `services/keycloak/pom.xml` — modeled on `services/tokens/pom.xml`
- [ ] `services/keycloak/src/main/java/com/dremio/service/keycloak/KeycloakConfig.java`
- [ ] `services/keycloak/src/main/java/com/dremio/service/keycloak/OidcTokenValidator.java`
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestKeycloakConfig.java`
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcTokenValidator.java`

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Coordinator starts cleanly with `auth.type=keycloak` | CFG-01 | Full coordinator startup requires DACDaemon wiring | 1. Set config 2. Start coordinator 3. Check logs for no RuntimeException |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
