---
phase: 33
slug: oidc-redirect-web-flow
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-12
---

# Phase 33 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit Jupiter 5 + AssertJ + Mockito (same as services/keycloak) |
| **Config file** | `services/keycloak/pom.xml` — junit-jupiter-api + junit-jupiter-engine already declared |
| **Quick run command** | `mvn test -pl services/keycloak -Dtest="TestOidcStateStore,TestOidcSessionStore" -q` |
| **Full suite command** | `mvn test -pl services/keycloak,dac/backend -q` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl services/keycloak -q`
- **After every plan wave:** Run `mvn test -pl services/keycloak,dac/backend -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 33-01-01 | 01 | 1 | OIDC-03 | unit | `mvn test -pl services/keycloak -Dtest=TestOidcStateStore -q` | ❌ W0 | ⬜ pending |
| 33-01-02 | 01 | 1 | LOUT-02 | unit | `mvn test -pl services/keycloak -Dtest=TestOidcSessionStore -q` | ❌ W0 | ⬜ pending |
| 33-02-01 | 02 | 2 | OIDC-01 | unit | `mvn test -pl dac/backend -Dtest="TestOidcResource#login_*" -q` | ❌ W0 | ⬜ pending |
| 33-02-02 | 02 | 2 | OIDC-02 | unit | `mvn test -pl dac/backend -Dtest="TestOidcResource#callback_*" -q` | ❌ W0 | ⬜ pending |
| 33-02-03 | 02 | 2 | OIDC-03 | unit | `mvn test -pl dac/backend -Dtest="TestOidcResource#callback_tamperedState_returns400,TestOidcResource#callback_missingState_returns400" -q` | ❌ W0 | ⬜ pending |
| 33-02-04 | 02 | 2 | LOUT-02 | unit | `mvn test -pl dac/backend -Dtest="TestOidcResource#callback_storesIdToken" -q` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcStateStore.java` — covers OIDC-03 state validation logic
- [ ] `services/keycloak/src/test/java/com/dremio/service/keycloak/TestOidcSessionStore.java` — covers LOUT-02 store semantics
- [ ] `dac/backend/src/test/java/com/dremio/dac/resource/TestOidcResource.java` — covers OIDC-01, OIDC-02, OIDC-03, LOUT-02 at resource layer

*Existing infrastructure covers test framework; only new test files needed.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Full browser redirect flow: login → Keycloak → callback → SSO landing | OIDC-01 + OIDC-02 | Requires live Keycloak + browser | 1. Navigate to `/api/v3/oidc/login`. 2. Verify redirect to Keycloak. 3. Authenticate. 4. Verify redirect to Dremio SSO landing with token in URL fragment. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
