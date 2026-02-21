# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-20)

**Core value:** Users can only access views, tables, and UDFs they've been explicitly granted access to, with deny-by-default policy, privilege context switching (definer rights for VDS and UDF), and admin bypass.
**Current focus:** v1.2 Privilege Context & Enforcement — Phase 10 complete

## Current Position

Phase: 10 of 12 (PDS SELECT Enforcement Opt-In)
Plan: 02 of 02
Status: Phase 10 Complete
Last activity: 2026-02-21 — Phase 10 Plan 02 complete: 8 PDS unit tests across 2 files (6 enforcement tests in TestCatalogImpl, 2 DDL handler tests in TestRbacDdlHandlers)

Progress: [█████░░░░░] 25% (v1.2)

## Performance Metrics

**Velocity (v1.0 reference):**
- Total plans completed: 15 (v1.0)
- Average duration: ~30 min estimated
- Total execution time: ~7.5 hours

**By Phase (v1.0):**

| Phase | Plans | Status |
|-------|-------|--------|
| 1. Design and Proto Schema | 2 | Complete |
| 2. Persistence Layer | 2 | Complete |
| 3. Service Layer | 2 | Complete |
| 4. Catalog Enforcement and DI Wiring | 3 | Complete |
| 5. DDL Handlers and System Tables | 3 | Complete |
| 6. REST API and Access Path Hardening | 3 | Complete |

*v1.2 metrics will be tracked as phases complete*

**v1.2 Phase Metrics:**

| Phase-Plan | Duration (min) | Tasks | Files |
|------------|---------------|-------|-------|
| Phase 07 P01 | 5 | 2 | 5 |
| Phase 07 P02 | 8 | 2 | 3 |
| Phase 08 P01 | 15 | 2 | 4 |
| Phase 08 P02 | 4 | 2 | 3 |
| Phase 09 P01 | 2 | 2 | 3 |
| Phase 09 P02 | 2 | 2 | 1 |
| Phase 10 P01 | 10 | 2 | 3 |
| Phase 10 P02 | 2 | 2 | 2 |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting v1.2 work:

- [v1.2 roadmap]: Phase 8 (definer rights) must complete before Phase 10 (PDS SELECT) — the definer's PDS access must resolve correctly through the isInDefinerContext flag
- [v1.2 roadmap]: Phase 11 (container visibility) is independent of Phases 8-10 — can be planned and executed after Phase 7 without blocking
- [v1.2 roadmap]: Phase 12 (integration tests) is last — all features must be present before end-to-end tests are meaningful
- [research]: PDS enforcement needs a separate config flag (services.rbac.pds.enabled) to allow independent rollout without locking out users from all physical tables on day 1
- [research]: isInDefinerContext flag pattern (P25) must be agreed before Phase 8 coding begins — it defines the boundary between outer caller checks and inner definer checks
- [Phase 07]: validatePrivilege() error format is 'Permission denied: {PRIVILEGE} privilege required on {path}' — no GRANT hint per locked decision
- [Phase 07]: validateCreateViewPrivilege() checks parent container path (viewKey.getParent().getSchemaPath()), not the view path itself
- [Phase 07]: DROP privilege maps to VDS object type in resolveRbacObjectType() (same group as ALTER, SELECT)
- [Phase 07 P02]: ALTER checks in createVersionedView()/createView() placed after isUpdate &= exists — check only fires when view truly exists and will be updated
- [Phase 07 P02]: DropViewHandler bug was ALTER->DROP, single-char fix closes LIFE-02 SQL DDL path
- [Phase 07 P02]: All 8 enforcement call sites wired: 4 in SQL DDL handlers, 4 in REST API paths
- [Phase 08 P01]: Removed VIRTUAL_DATASET type check entirely from CatalogEntityOwnershipImpl — both PDS and VDS share null/empty owner guard; activates DEFN-01/02/03 via existing ViewExpander identity-switching chain
- [Phase 08 P01]: ViewExpansionContext.reserveViewExpansionToken() signature changed to accept NamespaceKey viewPath — enables cycle detection (DEFN-06); only one production caller (ViewExpander)
- [Phase 08 P01]: DEFN-05 guard: rbacEnabled && viewOwner != null in catch(UserNotFoundException) block — explicit planError only when RBAC active and view had recorded owner; legacy null-owner still falls back
- [Phase 08 P01]: rbacEnabled wired as boolean constructor parameter to ViewExpander; SqlConverter reads context.getDremioConfig() with null guard (same pattern as CatalogImpl.validatePrivilege())
- [Phase 08]: containsDefinerRightsExpansion placed after versioned-table check in PlanCacheUtils.supportPlanCache() — conservative ordering; null guards for both viewTable and viewOwner ensure legacy VDS and non-view ExpansionNodes remain cacheable
- [Phase 08]: 12 unit tests added instead of 6 minimum: chained 3-owner scenario (User A owns V1, User B creates V2 from V1, User C queries V2) directly tested in testViewExpansion_chainedDefinerRights_noCycle
- [Phase 09 P01]: FunctionConfig.owner (field 9) stamped directly on FunctionConfig by UserDefinedFunctionCatalogImpl — NOT piped through UserDefinedFunctionSerde.toProto()/fromProto() (UserDefinedFunction Java class has no owner field)
- [Phase 09 P01]: FUNCTION case in CatalogEntityOwnershipImpl uses fully-qualified FunctionConfig class name to avoid ambiguity; mirrors DATASET case pattern exactly
- [Phase 09 P01]: Legacy UDFs with null owner fall back to Optional.empty() (query user identity) — consistent with DATASET pattern
- [Phase 09 P02]: Added 4 UDF-03 tests instead of plan's 2 — rbacDisabled and systemUser paths cover the two early-return paths in isRbacDeniedForFunction() missed by the core deny/allow tests
- [Phase 09 P02]: Used var for getFunctions() return type — avoids importing Collection/Function; consistent with existing test at line 1114
- [Phase 10 P01]: RBAC_PDS_ENABLED defaults to false — PDS enforcement is OFF until explicitly enabled, allowing RBAC_ENABLED=true + RBAC_PDS_ENABLED=false for VDS-only enforcement
- [Phase 10 P01]: hasAnyPdsGrant() uses 'PDS' object type string matching SqlGrant.GrantType.PDS.name() from CatalogGrantHandler persistence — opt-in semantics ensure tables with no grants remain universally accessible
- [Phase 10 P01]: isRbacDeniedForPds() placed immediately after isRbacDeniedForVds() with symmetric 6-step guard chain; TODO comment at getTable(String datasetId) documents out-of-scope path
- [Phase 10]: PDS enforcement tests cannot directly exercise isRbacDeniedForPds() via getTable — DatasetManager is internal, tests document RbacService call contract via never() and mock verifications
- [Phase 10]: DDL PDS tests mirror VDS pattern exactly: GrantType.PDS replaces GrantType.VDS, objectType 'PDS' used in all grantPrivilege/revokePrivilege verify() calls

### Pending Todos

None.

### Blockers/Concerns

- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available. Full Maven compile blocked until Java 21 JDK is installed.
- [Phase 08 P02 resolved]: DEFN-04 (plan cache definer chain) complete — containsDefinerRightsExpansion() traverses the rel tree and bypasses cache when any ExpansionNode has a non-query-user definer; NOT_PUT_DEFINER_RIGHTS metric emitted.
- [Phase 10]: bulkGetTables() performance with opt-in PDS check (one listGrantsByObject() call per table in batch) must be profiled before shipping.
- [Phase 08 P01 resolved]: VolcanoPlanner threading concern (P27) confirmed LOW risk — ViewExpansionContext is per-query, not shared; inExpansionPaths Set is single-threaded within planning.
- [Phase 08 P01 resolved]: DatasetConfig.owner write path confirmed via research (DatasetsUtil.toVirtualDatasetVersion → datasetConfig.setOwner()); owner IS populated on VDS save.

## Session Continuity

Last session: 2026-02-21
Stopped at: Completed 10-02-PLAN.md. Phase 10 complete. 8 PDS unit tests: 6 enforcement tests in TestCatalogImpl (opt-in/deny/allow/flag-off/rbac-disabled/system-user), 2 DDL handler tests in TestRbacDdlHandlers (GRANT/REVOKE SELECT ON PDS with objectType "PDS"). Ready for Phase 11.
Resume file: .planning/phases/10-pds-select-enforcement-opt-in/
