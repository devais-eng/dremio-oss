---
milestone: v1.0
name: Dremio OSS Naive RBAC
audited: 2026-02-19
status: gaps_found
previous_audit: 2026-02-18
scores:
  requirements: 41/44
  phases: 6/6
  integration: 42/44
  flows: 6/8
gaps:
  requirements:
    - id: "ENFC-01"
      status: "partial"
      phase: "Phase 4"
      claimed_by_plans: ["04-01-PLAN.md", "04-02-PLAN.md", "04-03-PLAN.md"]
      completed_by_plans: ["04-01-SUMMARY.md", "04-02-SUMMARY.md"]
      verification_status: "passed at phase level, gaps found at integration level"
      evidence: "validatePrivilege() and isRbacDeniedForVds() correctly enforce deny-by-default in getTable(NamespaceKey), getTableNoResolve(), getTableNoColumnCount(). HOWEVER: (1) bulkGetTables() at CatalogImpl.java:340-378 does NOT call isRbacDeniedForVds() — bypass via bulk API. (2) getTable(CatalogEntityKey) AT-specifier path at lines 329-333 calls getTableSnapshot() without RBAC check — bypass for versioned sources."
    - id: "ENFC-02"
      status: "partial"
      phase: "Phase 4"
      claimed_by_plans: ["04-01-PLAN.md", "04-02-PLAN.md", "04-03-PLAN.md"]
      completed_by_plans: ["04-01-SUMMARY.md", "04-02-SUMMARY.md"]
      verification_status: "passed at phase level, gaps found at integration level"
      evidence: "SELECT grant allows access through normal getTable() paths. But bulkGetTables() and AT-specifier getTableSnapshot() return data without checking grants."
    - id: "ENFC-07"
      status: "partial"
      phase: "Phase 4"
      claimed_by_plans: ["04-01-PLAN.md", "04-03-PLAN.md"]
      completed_by_plans: ["04-01-SUMMARY.md"]
      verification_status: "passed at phase level, gap at AT-specifier path"
      evidence: "Definer-rights preserved for normal query paths. AT-specifier path in getTable(CatalogEntityKey) bypasses caller privilege check entirely for time-travel queries on versioned sources."
  integration:
    - "CatalogImpl.bulkGetTables() (lines 340-378) does not call isRbacDeniedForVds() — enforcement bypass for bulk table resolution"
    - "CatalogImpl.getTable(CatalogEntityKey) AT-specifier path (lines 329-333) calls getTableSnapshot() without RBAC check — bypass for time-travel queries on versioned sources"
  flows:
    - "Bulk table resolution flow: query planner uses bulkGetTables -> VDS returned without RBAC check -> unauthorized access possible"
    - "AT-specifier query flow: SELECT ... AT SNAPSHOT -> getTableSnapshot -> data returned without privilege verification"
tech_debt:
  - phase: "04-catalog-enforcement-and-di-wiring"
    items:
      - "CatalogImpl.bulkGetTables() missing isRbacDeniedForVds() — needs RBAC filtering in BulkResponse transform or post-processing"
      - "CatalogImpl.getTable(CatalogEntityKey) AT-specifier path needs isRbacDeniedForVds() wrapping getTableSnapshot() return"
      - "CatalogImpl null rbacService guard is fail-open (silently allows access) — acceptable for executor nodes but could mask wiring issues"
  - phase: "06-rest-api-and-access-path-hardening"
    items:
      - "Spaces always visible to all users in catalog REST API (by design) — may confuse users expecting hidden spaces"
      - "Catalog visibility pagination: filterByVisibility() applied after pagination trim — pages may be smaller than maxChildren"
---

# Milestone Audit: Dremio OSS Naive RBAC v1.0

**Audited:** 2026-02-19 (re-audit after BOOT-01 fix)
**Previous Audit:** 2026-02-18 (identified BOOT-01 wiring gap — since fixed in dd84d7da6)
**Status:** GAPS FOUND
**User-Reported Issue:** Non-admin user created after admin can query all spaces created by admin

---

## Executive Summary

All 6 phases completed and all phase-level verifications passed. The BOOT-01 gap identified in the previous audit (2026-02-18) has been fixed — `BootstrapResource.createUser()` now calls `assignBootstrapAdmin()`.

However, this re-audit triggered by user bug report reveals **2 new enforcement bypass paths** in CatalogImpl that were not caught by phase-level unit tests:
1. `bulkGetTables()` does not check RBAC
2. `getTable(CatalogEntityKey)` AT-specifier path does not check RBAC

Additionally, the **user-reported bug has a primary root cause: RBAC is disabled by default** (`services.rbac.enabled: false`). The user must explicitly enable it.

---

## User Bug Root Cause Analysis

**Reported:** "Created a user after the admin one and this is able to query all the spaces created from admin."

### Root Cause 1: RBAC is OFF by default (PRIMARY)

`services.rbac.enabled` defaults to `false` in `dremio-reference.conf` (lines 351-353). Unless explicitly set to `true` in the deployment's `dremio.conf` and the coordinator is restarted, **ALL RBAC enforcement is completely disabled**.

When RBAC is OFF:
- `CatalogImpl.validatePrivilege()` returns immediately (line 2807)
- `CatalogImpl.isRbacDeniedForVds()` returns false immediately (line 2865)
- `CatalogServiceHelper.filterByVisibility()` returns all children unfiltered (line 3116)

**Fix:** Add to deployment configuration:
```
services.rbac.enabled = true
```
Then restart the coordinator.

### Root Cause 2: Spaces are always visible (BY DESIGN)

Spaces, folders, sources, and homes are metadata containers. `CatalogServiceHelper.isVisibleToUser()` (line 3142) returns `true` for all container types. Only VDS (views) and FUNCTIONs are filtered by RBAC grants.

The user seeing spaces listed is **expected behavior**. Enforcement happens when they try to query a VDS inside a space (SELECT returns "Table not found").

### Root Cause 3: Bootstrap admin correctly wired (FIXED)

`BootstrapResource.createUser()` at lines 89-91 now calls `rbacService.assignBootstrapAdmin(userName)` (fixed in commit `dd84d7da6`). The first user gets ADMIN role membership.

---

## Phase Verification Summary

| Phase | Status | Score | Requirements |
|-------|--------|-------|-------------|
| 1. Design and Proto Schema | PASSED | 4/4 | ENFC-09 |
| 2. Persistence Layer | PASSED | 12/12 | ROLE-07, PRIV-07 |
| 3. Service Layer | PASSED | 12/12 | ROLE-05, ROLE-06, BOOT-01, ENFC-04, ENFC-05 |
| 4. Catalog Enforcement | PASSED | 7/7 | ENFC-01, ENFC-02, ENFC-03, ENFC-06, ENFC-07, ENFC-08, BOOT-02 |
| 5. DDL Handlers | PASSED | 7/7 | ROLE-01-04, PRIV-01-06, DDL-01-06, OBSV-01-03 |
| 6. REST API | PASSED | 10/10 | REST-01-09, META-03 |

All 6 phases verified passed in isolation. Gaps are **cross-phase integration issues** not catchable by per-phase verification.

---

## Requirements Coverage (Cross-Reference)

### Fully Satisfied (41/44)

| Requirement | Phase | Status |
|-------------|-------|--------|
| ENFC-09 | 1 | satisfied |
| ROLE-07 | 2 | satisfied |
| PRIV-07 | 2 | satisfied |
| ROLE-05 | 3 | satisfied |
| ROLE-06 | 3 | satisfied |
| BOOT-01 | 3 | satisfied (fixed since last audit) |
| ENFC-04 | 3 | satisfied |
| ENFC-05 | 3 | satisfied |
| ENFC-03 | 4 | satisfied |
| ENFC-06 | 4 | satisfied |
| ENFC-08 | 4 | satisfied |
| BOOT-02 | 4 | satisfied |
| ROLE-01 through ROLE-04 | 5 | satisfied |
| PRIV-01 through PRIV-06 | 5 | satisfied |
| DDL-01 through DDL-06 | 5 | satisfied |
| OBSV-01 through OBSV-03 | 5 | satisfied |
| REST-01 through REST-09 | 6 | satisfied |
| META-03 | 6 | satisfied |

### Partially Satisfied (3/44)

| Requirement | Phase | Issue |
|-------------|-------|-------|
| ENFC-01 | 4 | Deny-by-default enforced in getTable(NamespaceKey) but **NOT** in bulkGetTables() or AT-specifier getTableSnapshot() |
| ENFC-02 | 4 | SELECT grant checked in normal paths but **NOT** in bulkGetTables() or AT-specifier paths |
| ENFC-07 | 4 | Definer-rights check preserved in normal paths but **NOT** in AT-specifier path (time-travel queries skip privilege check entirely) |

### Orphaned Requirements

None — all 44 requirements appear in at least one phase VERIFICATION.md.

---

## Integration Gaps

### GAP-1: `bulkGetTables()` Bypasses RBAC (Medium Severity)

**File:** `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java:340-378`
**Affected:** ENFC-01, ENFC-02

`isRbacDeniedForVds()` is called in `getTableNoResolve()`, `getTableNoColumnCount()`, and `getTable(NamespaceKey)` — but NOT in `bulkGetTables()`. Any query path using the bulk API can retrieve VDS records without RBAC checks.

**Fix:** Add RBAC filtering in the `BulkResponse.transform()` callback or post-process the response:
```java
// In bulkGetTables(), after getting unresolvedKeyResponses:
// Filter out RBAC-denied VDS entries
```

### GAP-2: AT-Specifier `getTableSnapshot` Path Bypasses RBAC (Low Severity)

**File:** `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java:326-338`
**Affected:** ENFC-01, ENFC-02, ENFC-07

When `forATSpecifierAccess()` returns true (time-travel queries with AT SNAPSHOT), `getTableSnapshot()` is called and its result returned without any RBAC check. Only affects versioned sources with AT-specifier queries.

**Fix:** Wrap the `getTableSnapshot()` return with an RBAC check:
```java
DremioTable result = getTableSnapshot(catalogEntityKey);
if (result != null && isRbacDeniedForVds(result, catalogEntityKey.toNamespaceKey())) {
    return null;
}
return result;
```

---

## DI Chain Verification (All Wired)

```
DACDaemonModule
  +-- RoleStore, GrantStore, MembershipStore (from KVStoreProvider)
  +-- RbacService (from stores) -> registry.bind()
  +-- CatalogServiceImpl receives Provider<RbacService>
  |     +-- CatalogImpl receives RbacService + DremioConfig
  +-- ContextService receives Provider<RbacService>
  |     +-- SabotContext receives Provider<RbacService>
  |           +-- getRbacService() -> DDL handlers, BootstrapResource
  |           +-- getAccessControlListingManager() -> system tables
  +-- RbacResource receives RbacService via HK2 DI (@Inject)
```

All connections verified as wired in production code.

---

## Previous Audit Gaps — Resolution Status

| Gap from 2026-02-18 Audit | Status | Resolution |
|---------------------------|--------|-----------|
| BOOT-01: BootstrapResource not calling assignBootstrapAdmin() | FIXED | Commit dd84d7da6 wired the call |
| validateAdminMembersExist() not called at startup | ACCEPTED | Startup guard is defense-in-depth; bootstrap wiring is the real fix |
| Catalog pagination returns fewer items after filter | ACCEPTED | Documented v1 limitation |

---

## Tech Debt Summary

| Phase | Items |
|-------|-------|
| Phase 4 | `bulkGetTables()` missing RBAC; AT-specifier path missing RBAC; null rbacService is fail-open |
| Phase 6 | Spaces always visible (by design); pagination shrinks after filter |

**Total: 5 items across 2 phases (2 are enforcement gaps, 3 are known limitations)**

---

## Conclusion

The RBAC system is **architecturally complete** with 41/44 requirements fully satisfied. The user's reported issue is primarily caused by **RBAC being disabled by default** (feature flag OFF) and **spaces being visible by design** (containers, not data objects).

Two enforcement bypass paths exist (`bulkGetTables` and AT-specifier) that need patching before RBAC can be considered fully hardened. These were not caught by phase-level unit tests because they test enforcement methods in isolation, not all call sites that should invoke them.

---

_Audited: 2026-02-19_
_Auditor: Claude (gsd-audit-milestone)_
