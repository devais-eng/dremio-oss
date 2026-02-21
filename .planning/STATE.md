# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-20)

**Core value:** Users can only access views, tables, and UDFs they've been explicitly granted access to, with deny-by-default policy, privilege context switching (definer rights for VDS and UDF), and admin bypass.
**Current focus:** v1.2 Privilege Context & Enforcement — Phase 7 complete, Phase 8 next

## Current Position

Phase: 7 of 12 (VDS Lifecycle Privilege Enforcement)
Plan: 02 of 02
Status: Complete
Last activity: 2026-02-20 — Phase 7 Plan 02 complete: wired ALTER, DROP, CREATE_VIEW enforcement into all SQL DDL and REST API VDS lifecycle call sites

Progress: [█░░░░░░░░░] 5% (v1.2)

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

### Pending Todos

None.

### Blockers/Concerns

- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available. Full Maven compile blocked until Java 21 JDK is installed.
- [Phase 8]: Calcite VolcanoPlanner threading at ViewTable.toRel() is unconfirmed (LOW confidence) — must audit before committing to ViewExpansionContext thread-safety approach (P27 fix).
- [Phase 8]: DatasetConfig.owner written on VDS create/update path must be confirmed via DACViewCreatorFactory → datasetVersionMutator.save() trace before assuming CatalogEntityOwnershipImpl fix produces non-null results.
- [Phase 10]: bulkGetTables() performance with opt-in PDS check (one listGrantsByObject() call per table in batch) must be profiled before shipping.

## Session Continuity

Last session: 2026-02-20
Stopped at: Completed 07-02-PLAN.md. Phase 7 complete. Ready to plan/execute Phase 8 (definer rights).
Resume file: .planning/phases/
