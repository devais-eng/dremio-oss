---
phase: 28
slug: dacsecuritycontext-role-enforcement
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-11
---

# Phase 28 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 + Jersey Test Framework + in-process Dremio (`BaseTestServer`) |
| **Config file** | none — test server uses `DACDaemonModule` with config overrides map |
| **Quick run command** | `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration -DskipTests=false -q 2>&1 \| tail -20` |
| **Full suite command** | `mvn test -pl dac/backend -am -DskipTests=false -q` |
| **Estimated runtime** | ~120 seconds (integration tests with in-process server) |

---

## Sampling Rate

- **After every task commit:** Run `mvn compile -pl dac/backend -am -DskipTests -q` (compile check)
- **After every plan wave:** Run `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration,TestDACSecurityContext -DskipTests=false -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 28-01-01 | 01 | 1 | API-01 | unit | `mvn test -pl dac/backend -am -Dtest=TestDACSecurityContext -q` | ❌ W0 | ⬜ pending |
| 28-01-02 | 01 | 1 | API-01 | integration | `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration#testNonAdminCannotCreateUser -q` | ❌ W0 | ⬜ pending |
| 28-01-03 | 01 | 1 | API-01 | integration | `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration#testNonAdminCannotUpdateUser -q` | ❌ W0 | ⬜ pending |
| 28-01-04 | 01 | 1 | API-01 | integration | `mvn test -pl dac/backend -am -Dtest=TestRbacIntegration#testAdminCanCreateUser -q` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `dac/backend/src/test/java/com/dremio/dac/server/TestDACSecurityContext.java` — unit tests for `isUserInRole()` with null/non-null rbacService and RBAC flag combinations
- [ ] New test methods in `dac/backend/src/test/java/com/dremio/dac/server/TestRbacIntegration.java` — `testNonAdminCannotCreateUser()`, `testNonAdminCannotUpdateUser()`, `testAdminCanCreateUser()` integration tests

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Admin calls to all protected endpoints still succeed | API-01 | Regression coverage across all `@RolesAllowed` endpoints | Run UAT script `rbac-uat.sh` against running instance with admin and non-admin users |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
