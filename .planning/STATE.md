# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-09)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.4 Nessie Branch-Aware REST Catalog -- Phase 24

## Current Position

Phase: 24 of 24 (End-to-End Testing)
Plan: 1 of TBD complete
Status: In Progress
Last activity: 2026-03-10 — Completed Phase 24, Plan 01 (Branch Error Handling)

Progress: [███████░░░] 65%

## Performance Metrics

**Velocity:**
- Total plans completed: 38 (across v1.0-v1.3)
- Average duration: ~15 min
- Total execution time: ~9.5 hours

**By Phase (v1.4):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 21 | 2/2 | 7min | 3.5min |
| 22 | 1/1 | 5min | 5min |
| 23 | 2/2 | 18min | 9min |
| 24 | 1/TBD | 8min | 8min |

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
- 22-01: expireAfterAccess (not expireAfterWrite) so actively-used branches stay cached
- 22-01: Branch cache initialized only when isNessieDetected=true (zero new paths when Nessie not detected)
- 22-01: Branch URI constructed by appending branchName to restEndpoint (no URL encoding)
- 22-01: close() calls branchAccessorCache.close() before super.close()
- 23-01: getDatasetHandleForBranch encapsulates branch accessor + table load within plugin to avoid kernel-to-plugin module dependency
- 23-01: Separate requestedPluginSupportsBranchAwareRest method (not extending requestedPluginSupportsVersionedTables) for semantic clarity
- 23-01: Lightweight resolveVersionContext with no server round-trip (empty commit hash, backend resolves via URI prefix)
- 23-02: No ConnectorException try/catch needed in branch-aware methods (interface does not declare checked exceptions)
- 23-02: CMP-02 and BRQ-02 satisfied by design (no AT BRANCH = existing non-versioned path, default accessor = base REST endpoint)
- 24-01: Used namespaceExists(List.of()) as branch probe (listNamespaces not in CatalogAccessor interface)
- 24-01: Branch-not-found wraps ReferenceNotFoundException to match UseVersionHandler/CatalogUtil native Nessie pattern
- 24-01: Table-not-found-on-branch does NOT wrap ReferenceNotFoundException (different error category)
- 24-01: BranchProbePluginMock overrides getCatalogAccessorForBranch (public) not createBranchScopedAccessor (private) for testability

### Pending Todos

None yet.

### Blockers/Concerns

- Commit hash as REST prefix: Does Nessie accept commit hashes in Iceberg REST URI prefix? (AT COMMIT deferred to future, but verify during Phase 22)
- OAuth token lifecycle: Each per-branch RESTCatalog may maintain its own OAuth2 session (verify during Phase 22)

## Session Continuity

Last session: 2026-03-10
Stopped at: Completed 24-01-PLAN.md (Branch Error Handling). Ready for Phase 24 Plan 02.
Resume file: None
