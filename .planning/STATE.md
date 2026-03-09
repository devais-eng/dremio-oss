# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-09)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.4 Nessie Branch-Aware REST Catalog -- Phase 22

## Current Position

Phase: 22 of 24 (Branch-Aware Catalog)
Plan: 0 of TBD complete
Status: Ready
Last activity: 2026-03-09 — Completed Phase 21 (Configuration and Nessie Detection)

Progress: [██░░░░░░░░] 20%

## Performance Metrics

**Velocity:**
- Total plans completed: 38 (across v1.0-v1.3)
- Average duration: ~15 min
- Total execution time: ~9.5 hours

**By Phase (v1.4):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 21 | 2/2 | 7min | 3.5min |
| 22 | TBD | - | - |
| 23 | TBD | - | - |
| 24 | TBD | - | - |

## Shipped Milestones

- v1.0 Naive RBAC — 6 phases, 15 plans (shipped 2026-02-19)
- v1.1 Enable Iceberg REST Catalog — 2 phases, 3 plans (shipped 2026-02-20)
- v1.2 GitHub Actions Docker Distribution — 3 phases, 3 plans (shipped 2026-02-21)
- v1.3 Privilege Context & Enforcement — 9 phases, 17 plans (shipped 2026-02-24)

## Accumulated Context

### Decisions

- v1.4 architecture: Narrow `SupportsBranchAwareRestCatalog` interface (3 methods) instead of full `VersionedPlugin` (25+ methods)
- v1.4 caching: Per-branch `RESTCatalog` instances in Caffeine cache (bounded, TTL eviction)
- v1.4 plan cache: Exclude Nessie-enabled RESTCATALOG sources entirely for MVP (same as native Nessie)
- v1.4 compatibility: `enableNessie=false` (default) means zero new code paths execute
- 21-01: Used primitive boolean (not Boolean wrapper) for enableNessie for backward-compatible protostuff deserialization
- 21-01: Placed Nessie Options in General tab (not Advanced Options) for discoverability
- 21-02: Used volatile fields for isNessieDetected/defaultBranch for cross-thread visibility from start() to query threads
- 21-02: Broad Exception catch in detectNessieBackend() ensures source startup never fails due to detection
- 21-02: Used cached getCatalog() path (ExpiringCatalogCache) for property reads, not raw catalogSupplier

### Pending Todos

None yet.

### Blockers/Concerns

- Commit hash as REST prefix: Does Nessie accept commit hashes in Iceberg REST URI prefix? (AT COMMIT deferred to future, but verify during Phase 22)
- OAuth token lifecycle: Each per-branch RESTCatalog may maintain its own OAuth2 session (verify during Phase 22)

## Session Continuity

Last session: 2026-03-09
Stopped at: Completed Phase 21 (21-02-PLAN.md, Nessie detection). Ready for Phase 22.
Resume file: None
