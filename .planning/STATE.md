# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-09)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.4 Nessie Branch-Aware REST Catalog -- Phase 21

## Current Position

Phase: 21 of 24 (Configuration and Nessie Detection)
Plan: 1 of 2 complete
Status: Executing
Last activity: 2026-03-09 — Completed 21-01 (enableNessie config field)

Progress: [█░░░░░░░░░] 10%

## Performance Metrics

**Velocity:**
- Total plans completed: 38 (across v1.0-v1.3)
- Average duration: ~15 min
- Total execution time: ~9.5 hours

**By Phase (v1.4):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 21 | 1/2 | 3min | 3min |
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

### Pending Todos

None yet.

### Blockers/Concerns

- Commit hash as REST prefix: Does Nessie accept commit hashes in Iceberg REST URI prefix? (AT COMMIT deferred to future, but verify during Phase 22)
- OAuth token lifecycle: Each per-branch RESTCatalog may maintain its own OAuth2 session (verify during Phase 22)

## Session Continuity

Last session: 2026-03-09
Stopped at: Completed 21-01-PLAN.md (enableNessie config field). Ready for 21-02.
Resume file: None
