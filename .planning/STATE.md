# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-20)

**Core value:** Users can only access views, tables, and UDFs they've been explicitly granted access to, with deny-by-default policy, privilege context switching (definer rights for VDS and UDF), and admin bypass.
**Current focus:** v1.2 Privilege Context & Enforcement — Phase 8 Plan 01 complete, Phase 8 Plan 02 next

## Current Position

Phase: 8 of 12 (VDS Definer Rights Safety Cluster)
Plan: 01 of 03
Status: Plan 01 Complete
Last activity: 2026-02-21 — Phase 8 Plan 01 complete: activated VDS definer rights via CatalogEntityOwnershipImpl fix (DEFN-01/02/03), added cycle detection to ViewExpansionContext (DEFN-06), and added deleted-owner explicit error to ViewExpander (DEFN-05)

Progress: [██░░░░░░░░] 10% (v1.2)

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

### Pending Todos

None.

### Blockers/Concerns

- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available. Full Maven compile blocked until Java 21 JDK is installed.
- [Phase 8]: DEFN-04 (plan cache definer chain) remains open — plan cache key does not include definer chain; must be addressed in Phase 08 Plan 02 before Phase 8 is declared complete.
- [Phase 10]: bulkGetTables() performance with opt-in PDS check (one listGrantsByObject() call per table in batch) must be profiled before shipping.
- [Phase 08 P01 resolved]: VolcanoPlanner threading concern (P27) confirmed LOW risk — ViewExpansionContext is per-query, not shared; inExpansionPaths Set is single-threaded within planning.
- [Phase 08 P01 resolved]: DatasetConfig.owner write path confirmed via research (DatasetsUtil.toVirtualDatasetVersion → datasetConfig.setOwner()); owner IS populated on VDS save.

## Session Continuity

Last session: 2026-02-21
Stopped at: Completed 08-01-PLAN.md. Phase 8 Plan 01 complete. Ready to execute Phase 8 Plan 02 (plan cache definer chain fix, DEFN-04).
Resume file: .planning/phases/08-vds-definer-rights-safety-cluster/
