# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-20)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control and catalog connectivity.
**Current focus:** v1.1 Enable Iceberg REST Catalog — Phase 8: End-to-End Validation

## Current Position

Phase: 8 of 8 (End-to-End Validation)
Plan: 2 of 2 in current phase (08-01 complete, 08-02 in progress)
Status: Infrastructure ready — validating end-to-end read path
Last activity: 2026-02-20 — Plan 08-01 complete; Lakekeeper + MinIO + Dremio stack running, test data seeded

Progress: [████████░░] ~85% (v1.0 complete; Phase 7 wiring done; Phase 8 infra ready, validation in progress)

## Performance Metrics

**Velocity (v1.0):**
- Total plans completed: 15
- Average duration: ~20 min
- Total execution time: ~5 hours

**By Phase (v1.0):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Design and Proto Schema | 2 | ~40 min | ~20 min |
| 2. Persistence Layer | 2 | ~40 min | ~20 min |
| 3. Service Layer | 2 | ~40 min | ~20 min |
| 4. Catalog Enforcement and DI Wiring | 3 | ~60 min | ~20 min |
| 5. DDL Handlers and System Tables | 3 | ~60 min | ~20 min |
| 6. REST API and Access Path Hardening | 3 | ~60 min | ~20 min |

**Recent Trend:** Stable

*Updated after each plan completion*
| Phase 07-plugin-wiring P01 | 2 | 2 tasks | 3 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [v1.1 Roadmap]: Two-phase structure — Phase 7 is pure wiring (2 artifacts, no new deps), Phase 8 is pure validation against Lakekeeper; no code expected in Phase 8 unless credential vending is broken
- [v1.1 Arch]: `@SourceType` annotation must go on `RestIcebergCatalogPluginConfig` (concrete class), not `IcebergCatalogPluginConfig` (abstract) — scanner skips abstract classes
- [v1.1 Arch]: `restcatalog-layout.json` must live in `plugins/icebergcatalog/src/main/resources/` — loaded via source class classloader
- [v1.1 Known gap]: `hasAccessPermission()` is a no-op TODO in `IcebergCatalogPlugin` — all Dremio users have full read access to all REST catalog tables; document as v1.1 known limitation
- [Phase 07-plugin-wiring]: @SourceType on concrete RestIcebergCatalogPluginConfig (not abstract parent); isVersioned=false; metadataRefresh.datasetDiscovery=false; no new constructors

### Pending Todos

None.

### Blockers/Concerns

- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available. Full Maven compile blocked until Java 21 JDK is installed.
- [Phase 8 — Research gap]: Credential vending path from `loadTable()` through `DremioFileIO` was not fully traced during research; may require a targeted fix if SELECT queries fail with permission errors. Starting point: `AbstractRestCatalogAccessor.getTableHandleInternal()`.
- [Phase 8 — Research gap]: Lakekeeper Docker image exact tag needs runtime verification at `quay.io/repository/iceberg-catalog/iceberg-catalog` before Phase 8 test plan is written.

## Session Continuity

Last session: 2026-02-20
Stopped at: Plan 08-01 complete — infrastructure running (Lakekeeper warehouse "demo", MinIO, testns.users 10 rows). Executing Plan 08-02 validation.
Resume file: None
