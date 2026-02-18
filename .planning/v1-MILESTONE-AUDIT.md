---
milestone: v1
name: Dremio OSS Naive RBAC
audited: 2026-02-18
status: gaps_found
scores:
  requirements: 43/44
  phases: 6/6
  integration: 9/11
  flows: 4/6
gaps:
  requirements:
    - id: "BOOT-01"
      status: "partial"
      phase: "Phase 3"
      claimed_by_plans: ["03-01-PLAN.md"]
      completed_by_plans: ["03-01-SUMMARY.md (assignBootstrapAdmin method created)"]
      verification_status: "passed (method exists + unit tested)"
      evidence: "RbacService.assignBootstrapAdmin() exists and is unit-tested in RbacServiceTest. However no production caller invokes it. BootstrapResource.createUser() creates the first user but never calls assignBootstrapAdmin(). When RBAC is enabled, the first user has no ADMIN membership — creating a catch-22 where no ADMIN can ever be created without a pre-existing ADMIN."
  integration:
    - from: "BootstrapResource.createUser()"
      to: "RbacService.assignBootstrapAdmin()"
      issue: "No production code calls assignBootstrapAdmin(). RbacService is available via dContext.getRbacService() but is never accessed from BootstrapResource."
      affected_requirements: [BOOT-01, ROLE-01, ROLE-02, ROLE-03, ROLE-04, REST-01, REST-02, REST-03, REST-04, REST-05, REST-06, REST-07, REST-08, REST-09]
    - from: "DACDaemonModule / RbacService.start()"
      to: "RbacService.validateAdminMembersExist()"
      issue: "The startup fail-fast guard method exists and is documented but no production code calls it. Coordinator starts with RBAC enabled but zero ADMIN members without error."
      affected_requirements: [BOOT-01]
  flows:
    - name: "Admin setup flow"
      breaks_at: "BootstrapResource.createUser() does not call assignBootstrapAdmin() — first user has no ADMIN membership"
      affected_requirements: [BOOT-01, ROLE-01, ROLE-02, ROLE-03, ROLE-04]
    - name: "Catalog visibility pagination"
      breaks_at: "filterByVisibility() applied after pagination trim — pages may be smaller than maxChildren"
      severity: "Non-blocking v1 known limitation"
      affected_requirements: [META-03]
tech_debt:
  - phase: "01-design-and-proto-schema"
    items: []
  - phase: "02-persistence-layer"
    items: []
  - phase: "03-service-layer"
    items:
      - "validateAdminMembersExist() documented as startup guard but never called in production"
  - phase: "04-catalog-enforcement-and-di-wiring"
    items:
      - "Pre-existing TODOs in CatalogImpl.java (DX-65443, DX-44984) — unrelated to RBAC, pre-existing"
      - "Minor NPE risk in getFunctions() at line 1367 if resolvedPath==null but RBAC allows access (pre-existing code path)"
  - phase: "05-ddl-handlers-and-system-tables"
    items: []
  - phase: "06-rest-api-and-access-path-hardening"
    items:
      - "Pre-existing TODOs in CatalogServiceHelper.java (lines 1184, 1207, 1568, 1801, 1838, 2201, 2498) — unrelated to RBAC"
      - "Catalog visibility pagination: pages may be smaller than maxChildren after filtering (documented v1 limitation)"
      - "SUMMARY.md requirements-completed frontmatter not consistently filled in early phase plans (Phases 1-4)"
---

# Milestone Audit: Dremio OSS Naive RBAC v1

**Audited:** 2026-02-18
**Status:** ⚠ gaps_found

## Scores

| Dimension | Score | Notes |
|-----------|-------|-------|
| Requirements | 43/44 | BOOT-01 method exists but not called in production |
| Phases | 6/6 | All phases verified passed |
| Integration wiring | 9/11 | 2 missing connections found |
| E2E flows | 4/6 | Admin setup flow and catalog pagination broken |

## Phase Verification Summary

| Phase | Plans | VERIFICATION Status | Score |
|-------|-------|---------------------|-------|
| 01 Design and Proto Schema | 2/2 | ✓ passed | 4/4 criteria |
| 02 Persistence Layer | 2/2 | ✓ passed | 12/12 must-haves |
| 03 Service Layer | 2/2 | ✓ passed | 12/12 must-haves |
| 04 Catalog Enforcement and DI Wiring | 3/3 | ✓ passed | 7/7 must-haves |
| 05 DDL Handlers and System Tables | 3/3 | ✓ passed | 7/7 must-haves (19/19 requirements) |
| 06 REST API and Access Path Hardening | 3/3 | ✓ passed | 10/10 must-haves |

All 6 phases verified passed in isolation. The critical gap is a **cross-phase integration gap** not catchable by per-phase verification.

## Requirements Coverage (3-Source Cross-Reference)

### Satisfied (43/44)

| Requirement | Phase | VERIFICATION | SUMMARY frontmatter | REQUIREMENTS.md | Status |
|-------------|-------|-------------|---------------------|-----------------|--------|
| ENFC-09 | 1 | passed | (empty) | [x] | ✓ satisfied |
| ROLE-07 | 2 | passed | (empty) | [x] | ✓ satisfied |
| PRIV-07 | 2 | passed | (empty) | [x] | ✓ satisfied |
| ROLE-05 | 3 | passed | (empty) | [x] | ✓ satisfied |
| ROLE-06 | 3 | passed | (empty) | [x] | ✓ satisfied |
| BOOT-02 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ENFC-01 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ENFC-02 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ENFC-03 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ENFC-04 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ENFC-05 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ENFC-06 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ENFC-07 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ENFC-08 | 4 | passed | (empty) | [x] | ✓ satisfied |
| ROLE-01 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| ROLE-02 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| ROLE-03 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| ROLE-04 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| PRIV-01 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| PRIV-02 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| PRIV-03 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| PRIV-04 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| PRIV-05 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| PRIV-06 | 5 | passed | listed | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| DDL-01 | 5 | passed | listed | [x] | ✓ satisfied |
| DDL-02 | 5 | passed | listed | [x] | ✓ satisfied |
| DDL-03 | 5 | passed | listed | [x] | ✓ satisfied |
| DDL-04 | 5 | passed | listed | [x] | ✓ satisfied |
| DDL-05 | 5 | passed | listed | [x] | ✓ satisfied |
| DDL-06 | 5 | passed | listed | [x] | ✓ satisfied |
| OBSV-01 | 5 | passed | listed | [x] | ✓ satisfied |
| OBSV-02 | 5 | passed | listed | [x] | ✓ satisfied |
| OBSV-03 | 5 | passed | listed | [x] | ✓ satisfied |
| REST-01 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| REST-02 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| REST-03 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| REST-04 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| REST-05 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| REST-06 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| REST-07 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| REST-08 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| REST-09 | 6 | passed | (empty) | [x] | ✓ satisfied (wired, blocked by BOOT-01 in production) |
| META-03 | 6 | passed | (empty) | [x] | ✓ satisfied |

### Partial (1/44)

| Requirement | Phase | Issue |
|-------------|-------|-------|
| BOOT-01 | 3 | Method `assignBootstrapAdmin()` exists and is unit-tested but has no production caller. `BootstrapResource.createUser()` never calls it. |

### Orphaned Requirements

None — all 44 requirements appear in at least one phase VERIFICATION.md.

## Critical Gaps

### Gap 1: BOOT-01 — Bootstrap Not Wired to BootstrapResource (BLOCKING)

**What's missing:** `BootstrapResource.createUser()` does not call `RbacService.assignBootstrapAdmin()`.

**Impact:** When `services.rbac.enabled=true`, the first user created through setup:
1. Has no ADMIN role membership
2. Cannot execute any DDL (CREATE ROLE, GRANT, etc.) — `isAdminMember()` returns false → 403
3. Cannot call any REST endpoint — `requireAdmin()` → 403
4. **Catch-22:** Only an ADMIN can create the first ADMIN, but no ADMIN exists

**Affected in production (when RBAC enabled):** BOOT-01, plus all admin-gated flows across ROLE-01 through ROLE-04, PRIV-01 through PRIV-06, REST-01 through REST-09

**Fix (5 minutes):** In `BootstrapResource.java`:
```java
// After: newUser = userService.createUser(newUser, userForm.getPassword());
RbacService rbacService = dContext.getRbacService();
if (rbacService != null) {
    rbacService.assignBootstrapAdmin(newUser.getUserName());
}
```

### Gap 2: Startup Guard Non-Functional (Non-blocking)

**What's missing:** `RbacService.validateAdminMembersExist()` is never called at startup.

**Impact:** Coordinator starts successfully even when `services.rbac.enabled=true` with zero ADMIN members. The fail-fast guard is a no-op in production.

**Severity:** Non-blocking (system starts, but RBAC is unusable without BOOT-01 fix anyway).

## Confirmed Correct Integration (10 points)

| # | Integration | Requirements |
|---|-------------|--------------|
| 1 | DACDaemonModule → RbacService DI → all consumers (CatalogImpl, ContextService, SabotContext, QueryContext, RbacResource, CatalogServiceHelper) | All phases |
| 2 | `sabot-module.conf` com.dremio.exec.rbac scanning → KV store discovery | ROLE-07, PRIV-07 |
| 3 | All 6 SQL parser `Class.forName()` FQCNs match actual handler classes | DDL-01 through DDL-06 |
| 4 | CatalogImpl.validatePrivilege() → RbacService.hasPrivilege() → stores → proto | ENFC-01 through ENFC-08 |
| 5 | sys.roles/privileges/membership → AccessControlListingManager → RbacService → stores | OBSV-01 through OBSV-03 |
| 6 | RbacResource @APIResource → APIServer auto-scan → /api/v3/rbac/* | REST-01 through REST-09 |
| 7 | CatalogServiceHelper.filterByVisibility() → RbacService.hasPrivilege() | META-03 |
| 8 | Feature flag dremio-reference.conf services.rbac.enabled=false → all enforcement points | ENFC-09, BOOT-02 |
| 9 | ADMIN bypass in isAdminMember() → hasPrivilege() short-circuit | ENFC-04, ROLE-05 |
| 10 | PUBLIC implicit membership via PUBLIC_ROLE_ID in hasPrivilege() role list | ENFC-05, ROLE-06 |

## Tech Debt Summary

| Phase | Items |
|-------|-------|
| 03 Service Layer | `validateAdminMembersExist()` documented as startup guard but never called |
| 04 Catalog Enforcement | Pre-existing TODOs in CatalogImpl (DX-65443, DX-44984); minor NPE risk in getFunctions() line 1367 (pre-existing) |
| 06 REST API | Pre-existing TODOs in CatalogServiceHelper; catalog pagination returns fewer items than maxChildren after visibility filter (documented v1 limitation); SUMMARY frontmatter inconsistencies in early phases |

**Total tech debt items:** 6 (none blocking)

## Conclusion

The Dremio OSS Naive RBAC implementation is **architecturally complete** — all wiring, enforcement, DDL handlers, system tables, REST endpoints, and catalog filtering are correctly implemented and unit-tested. One critical production gap was identified by cross-phase integration analysis:

**The first user created during Dremio setup does not receive ADMIN role membership when RBAC is enabled.** This is a 5-minute fix in `BootstrapResource.java`.

All other integration points (10/11) are correctly wired. E2E flows work except where blocked by this single missing call.
