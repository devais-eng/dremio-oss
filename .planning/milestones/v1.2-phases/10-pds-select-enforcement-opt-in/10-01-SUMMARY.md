---
phase: 10-pds-select-enforcement-opt-in
plan: "01"
subsystem: catalog-rbac
tags: [rbac, pds, select-enforcement, opt-in, config, catalog]
dependency_graph:
  requires:
    - "09-01: UDF owner stamping (FunctionConfig.owner)"
    - "08-01: isInDefinerContext flag (definer-scoped CatalogImpl)"
  provides:
    - "PDS SELECT enforcement (opt-in) via isRbacDeniedForPds()"
    - "RBAC_PDS_ENABLED config flag for independent rollout"
    - "hasAnyPdsGrant() helper for backward-compatible opt-in semantics"
  affects:
    - "All getTable*/bulkGetTables code paths in CatalogImpl"
    - "Any definer-scoped CatalogImpl (VDS-over-PDS definer rights)"
tech_stack:
  added: []
  patterns:
    - "Opt-in enforcement: hasAnyPdsGrant() check before hasPrivilege() for backward compatibility"
    - "Dual-flag gating: RBAC_ENABLED && RBAC_PDS_ENABLED for independent rollout"
key_files:
  created: []
  modified:
    - common/legacy/src/main/java/com/dremio/config/DremioConfig.java
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java
decisions:
  - "RBAC_PDS_ENABLED defaults to false — PDS enforcement is OFF until explicitly enabled, allowing RBAC_ENABLED=true + RBAC_PDS_ENABLED=false for VDS-only enforcement"
  - "hasAnyPdsGrant() uses 'PDS' object type string matching SqlGrant.GrantType.PDS.name() from CatalogGrantHandler persistence"
  - "isRbacDeniedForPds() placed immediately after isRbacDeniedForVds() — symmetric pattern for readability"
  - "TODO comment at getTable(String datasetId) documents out-of-scope path per research guidance"
metrics:
  duration_min: 10
  completed_date: "2026-02-21"
  tasks_completed: 2
  files_modified: 3
---

# Phase 10 Plan 01: PDS SELECT Enforcement Production Code Summary

PDS SELECT enforcement with opt-in semantics: RBAC_PDS_ENABLED config flag, RbacService.hasAnyPdsGrant() helper, CatalogImpl.isRbacDeniedForPds() method with 6-step guard chain, wired into all 6 getTable*/bulkGetTables call sites.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Add RBAC_PDS_ENABLED config constant and RbacService.hasAnyPdsGrant() helper | f9593e2f8 | DremioConfig.java, RbacService.java |
| 2 | Add isRbacDeniedForPds() method and wire into all 6 call sites in CatalogImpl | a5a3d522d | CatalogImpl.java |

## What Was Built

### DremioConfig.RBAC_PDS_ENABLED

Added immediately after `RBAC_ENABLED` in `DremioConfig.java`:

```java
/** PDS SELECT enforcement (opt-in). Only active when RBAC_ENABLED is also true.
 *  Defaults to false. Requires coordinator restart to change. */
public static final String RBAC_PDS_ENABLED = "services.rbac.pds.enabled";
```

This enables independent rollout: `RBAC_ENABLED=true + RBAC_PDS_ENABLED=false` means VDS enforced, PDS universally accessible.

### RbacService.hasAnyPdsGrant()

Added after `listGrantsByObject()` in `RbacService.java`:

```java
public boolean hasAnyPdsGrant(String objectPath) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(objectPath), "objectPath must not be null or empty");
    return !grantStore.listByObject("PDS", objectPath).isEmpty();
}
```

Enables opt-in semantics: tables with no grants remain universally accessible.

### CatalogImpl.isRbacDeniedForPds()

Added immediately after `isRbacDeniedForVds()` — 6-step guard chain:

1. `if (table instanceof ViewTable) return false;` — VDS handled by isRbacDeniedForVds
2. `if (dremioConfig == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) return false;`
3. `if (!dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)) return false;`
4. `if (SystemUser.isSystemUserName(userName)) return false;`
5. `if (rbacService == null) return false;`
6. Opt-in: `if (!rbacService.hasAnyPdsGrant(objectPath)) return false;` then `hasPrivilege("SELECT", "PDS", ...)`

### Call Site Wiring (6 sites)

All `getTable*` and `bulkGetTables` paths now check both VDS and PDS denial:

- `getTableNoResolve(NamespaceKey)` — line 290
- `getTableNoColumnCount(NamespaceKey)` — line 299
- `getTable(NamespaceKey)` first resolved path — line 312
- `getTable(NamespaceKey)` second unresolved path — line 320
- `getTable(CatalogEntityKey)` AT-specifier path — line 332
- `bulkGetTables()` ValueTransformer lambda — line 379

## Verification Results

1. `isRbacDeniedForPds` occurrences in CatalogImpl: 8 (1 method definition + 6 call sites + 1 TODO comment)
2. `RBAC_PDS_ENABLED` in DremioConfig.java (definition) and CatalogImpl.java (usage) — confirmed
3. `hasAnyPdsGrant` in RbacService.java (definition) and CatalogImpl.java (usage) — confirmed
4. Opt-in check order: `hasAnyPdsGrant` at line 2967 BEFORE `hasPrivilege` at line 2972 — confirmed
5. `isRbacDeniedForVds` still has 6 call sites (no existing call sites removed) — confirmed

## Deviations from Plan

None — plan executed exactly as written. The verification count of 7 for `isRbacDeniedForPds` in the plan did not anticipate the TODO comment line (which added a 8th reference), but all 6 call sites and the 1 method definition are present as required.

## Self-Check: PASSED

Files exist:
- common/legacy/src/main/java/com/dremio/config/DremioConfig.java: FOUND
- sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java: FOUND
- sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java: FOUND

Commits exist:
- f9593e2f8: FOUND
- a5a3d522d: FOUND
