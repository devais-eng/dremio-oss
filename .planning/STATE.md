# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-20)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control and catalog connectivity.
**Current focus:** v1.1 Enable Iceberg REST Catalog — Phase 8: End-to-End Validation

## Current Position

Phase: 8 of 8 (End-to-End Validation)
Plan: 2 of 2 in current phase (all complete)
Status: Phase 8 complete — all 6 success criteria validated; awaiting verification
Last activity: 2026-02-20 — Plan 08-02 complete; RESTCATALOG source creation, browsing, SELECT, OAuth2, credential workaround all validated

Progress: [█████████░] ~95% (v1.0 complete; Phase 7+8 complete; awaiting phase verification)

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
- [Phase 8 — RESOLVED]: Credential vending from Lakekeeper `loadTable()` NOT propagated through `DremioFileIO` — confirmed by code trace. Workaround: static `fs.s3a.*` creds in `propertyList`. Known v1.1 limitation for IAM/STS.
- [Phase 8 — RESOLVED]: Lakekeeper Docker image `quay.io/lakekeeper/catalog:latest-main` used successfully via minimal example compose.
- [Phase 8 — Discovery]: `fs.s3a.endpoint` must be without protocol (e.g., `localhost:9000` not `http://localhost:9000`); `fs.s3a.connection.ssl.enabled=false` for HTTP; explicit `SimpleAWSCredentialsProvider` required; `dremio.s3.compat=true` for MinIO.

## Session Continuity

Last session: 2026-02-20
Stopped at: Phase 8 plans complete — all 6 success criteria validated. Awaiting phase verification.
Resume file: None
