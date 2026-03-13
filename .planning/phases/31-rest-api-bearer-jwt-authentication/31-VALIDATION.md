---
phase: 31
slug: rest-api-bearer-jwt-authentication
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-03-12
---

# Phase 31 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 Jupiter (consistent with Phase 30 pattern) |
| **Config file** | `pom.xml` — Maven Surefire picks up both JUnit 4 and 5 |
| **Quick run command** | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak -am -DfailIfNoTests=false` |
| **Full suite command** | `mvn test -pl services/keycloak,dac/backend -am -DfailIfNoTests=false` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak -am -DfailIfNoTests=false`
- **After every plan wave:** Run `mvn test -pl services/keycloak,dac/backend -am -DfailIfNoTests=false`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 31-01-01 | 01 | 1 | TKN-02 | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testValidKeycloakJwtReturnsUsername -am` | ❌ W0 | ⬜ pending |
| 31-01-02 | 01 | 1 | TKN-02 | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testMalformedJwtThrowsNotAuthorized -am` | ❌ W0 | ⬜ pending |
| 31-01-03 | 01 | 1 | TKN-02 | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testExpiredJwtThrowsNotAuthorized -am` | ❌ W0 | ⬜ pending |
| 31-01-04 | 01 | 1 | COEX-02 | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testOpaqueTokenUsesTokenManager -am` | ❌ W0 | ⬜ pending |
| 31-01-05 | 01 | 1 | COEX-02 | unit | `mvn test -pl dac/backend -Dtest=TestDACAuthFilterKeycloak#testInternalAuthModeNoOidcValidator -am` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterKeycloak.java` — unit tests for modified `getUserNameFromToken()` with mocked OidcTokenValidator and TokenManager
- [ ] `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` — add `@Inject @Nullable OidcTokenValidator` field and `eyJ`-discriminated dispatch

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Local admin login with username/password when auth.type=keycloak | COEX-01 | Requires full coordinator startup with keycloak config | 1. Set auth.type=keycloak 2. POST /apiv2/login with admin creds 3. Verify 200 response |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
