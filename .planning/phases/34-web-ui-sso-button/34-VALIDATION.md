---
phase: 34
slug: web-ui-sso-button
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-03-12
---

# Phase 34 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (backend)** | JUnit Jupiter 5 + Mockito |
| **Framework (frontend)** | Mocha + Chai + Enzyme (shallow) + Sinon |
| **Config file (frontend)** | `dac/ui/test/index.js` |
| **Quick run command (backend)** | `mvn test -pl dac/backend -Dtest="TestServerConfigResource" -q` |
| **Quick run command (frontend — SSOLandingPage)** | `cd dac/ui && DREMIO_UI_TESTS="$PWD/src/pages/AuthenticationPage/components/SSOLandingPage-spec*" node --run test:only` |
| **Quick run command (frontend — LoginFormContainer)** | `cd dac/ui && DREMIO_UI_TESTS="$PWD/src/pages/AuthenticationPage/components/LoginFormContainer-spec*" node --run test:only` |
| **Full suite command** | `mvn test -pl dac/backend -q && cd dac/ui && node --run test:only` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick run command for the relevant layer (backend or frontend)
- **After every plan wave:** Run full suite command
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 34-01-01 | 01 | 1 | UI-01, UI-02 | unit (JUnit) | `mvn test -pl dac/backend -Dtest="TestServerConfigResource" -q` | W0 (created by plan 01 TDD) | pending |
| 34-02-01 | 02 | 1 | UI-03 | unit (Mocha) | `DREMIO_UI_TESTS="...SSOLandingPage-spec*" node --run test:only` | W0 (created by plan 02 Task 1) | pending |
| 34-02-02 | 02 | 1 | UI-01, UI-02 | unit (Mocha) | `DREMIO_UI_TESTS="...LoginFormContainer-spec*" node --run test:only` | exists (extended by plan 02 Task 2) | pending |

*Status: pending / green / red / flaky*

---

## Wave 0 Requirements

- [ ] `dac/backend/src/test/java/com/dremio/dac/resource/TestServerConfigResource.java` — covers UI-01, UI-02 at backend layer (created by plan 01 TDD RED step)
- [ ] `dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage-spec.js` — covers UI-03 (token extraction, setUserData, navigate) (created by plan 02 Task 1 RED step)
- [ ] `dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer-spec.js` — covers UI-01, UI-02 (conditional SSO button render based on authType state) (already exists; extended by plan 02 Task 2 RED step)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| SSO button visible on login page when Keycloak configured | UI-01 | Visual verification in running app | 1. Start Dremio with `auth.type=keycloak` 2. Navigate to `/login` 3. Verify "Login with SSO" button is visible |
| SSO button hidden on login page when internal auth | UI-02 | Visual verification in running app | 1. Start Dremio with default config 2. Navigate to `/login` 3. Verify no SSO button |
| Full SSO flow end-to-end | UI-03 | Requires running Keycloak + browser interaction | 1. Click "Login with SSO" 2. Authenticate in Keycloak 3. Verify redirect to Dremio home, logged in |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify with Mocha/JUnit run commands
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references — each plan task writes its own spec as RED step
- [x] No watch-mode flags
- [x] Feedback latency < 30s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
