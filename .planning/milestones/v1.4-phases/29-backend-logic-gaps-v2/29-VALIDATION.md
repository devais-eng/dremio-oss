---
phase: 29
slug: backend-logic-gaps-v2
status: draft
nyquist_compliant: true
wave_0_complete: true
created: 2026-03-11
---

# Phase 29 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 + Mockito (Maven Surefire) |
| **Config file** | none — tests run via Maven |
| **Quick run command** | `mvn test -pl dac/backend -Dtest=TestCatalogServiceHelper -q` |
| **Full suite command** | `mvn test -pl dac/backend,sabot/kernel,plugins/sysflight -q` |
| **Estimated runtime** | ~90 seconds (unit tests across 3 modules) |

---

## Sampling Rate

- **After every task commit:** Run quick command for the module being changed
- **After every plan wave:** Run `mvn test -pl dac/backend,sabot/kernel,plugins/sysflight -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 29-01-00 | 01 | 1 | LOGIC-01, LOGIC-02 | scaffold | `ls TestSysFlightScanCreator.java TestCatalogServiceHelper.java` | W0 creates | ⬜ pending |
| 29-01-01 | 01 | 1 | LOGIC-01 | unit | `mvn test -pl dac/backend -Dtest=TestCatalogServiceHelper#testDatasetCount* -q` | ✅ W0 | ⬜ pending |
| 29-01-02 | 01 | 1 | LOGIC-02 | unit | `mvn test -pl plugins/sysflight -Dtest=TestSysFlightScanCreator -q` | ✅ W0 | ⬜ pending |
| 29-02-00 | 02 | 1 | LOGIC-03 | scaffold | `grep '"SPACE"' TestCatalogImpl.java` | ✅ existing | ⬜ pending |
| 29-02-01 | 02 | 1 | LOGIC-03 | unit | `mvn test -pl sabot/kernel -Dtest=TestCatalogImpl#testValidateCreateViewPrivilege* -q` | ✅ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] `plugins/sysflight/src/test/java/com/dremio/plugins/sysflight/TestSysFlightScanCreator.java` — covers LOGIC-02 (created by Plan 01, Task 0)
- [x] `dac/backend/src/test/java/com/dremio/dac/service/TestCatalogServiceHelper.java` — covers LOGIC-01 (new test methods added by Plan 01, Task 0)
- [x] `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` — covers LOGIC-03 (existing tests updated by Plan 02, Task 0)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Sidebar dataset count reflects RBAC-visible datasets | LOGIC-01 | Requires running UI against live server with RBAC grants | Log in as non-admin, check sidebar count matches granted datasets |
| Non-admin can query sys.membership | LOGIC-02 | Requires live server with RBAC enabled | `SELECT * FROM sys.membership` as non-admin user |
| CREATE_VIEW + auto-grant end-to-end | LOGIC-03 | Requires full SQL path with RBAC grants | Grant CREATE_VIEW, create view as non-admin, query it immediately |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** ready
