# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-09)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.4 Nessie Branch-Aware REST Catalog -- Phase 25

## Current Position

Phase: 25 of 25 (Fix RESTCATALOG S3 Config Propagation)
Plan: 1 of 1 complete
Status: Complete
Last activity: 2026-03-11 — Completed 25-01: S3 filesystem config propagation fix

Progress: [██████████] 100%

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
| 24 | 3/TBD | ~120min total | 40min/plan |
| 25 | 1/1 | 8min | 8min |

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
- 22-01: Branch URI constructed by appending branchName to restEndpoint (URL-encoded in 24-03 deviation fix)
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
- [Phase 24]: 24-02: NessieContainer exposes getIcebergRestUri() and getNessieApiUri() separately for distinct use cases
- [Phase 24]: 24-02: Image pinned to ghcr.io/projectnessie/nessie:0.100.3 matching project nessie.version property
- [Phase 24]: 24-02: No test dependencies in nessie module itself (NessieContainer is a utility, not a test runner)
- [Phase 24]: 24-03: IT tests use Iceberg Java RESTCatalog client for setup (creates real S3 metadata) not fake Nessie v1 commits
- [Phase 24]: 24-03: NessieContainer uses APPLICATION_GLOBAL + AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY env vars (not STATIC secrets)
- [Phase 24]: 24-03: branchExists probe changed from namespaceExists(root) to datasetExists(probe_path) for real 400/404 differentiation
- [Phase 24]: 24-03: Branch names URL-encoded in createBranchScopedAccessor (slash -> %2F for feature/my-branch style names)
- 25-01: Eager merge in IcebergCatalogPlugin.start() via getConfigProperties() hook -- mirrors FileSystemPlugin.initializeFsConf() pattern
- 25-01: Base getConfigProperties() returns emptyList -- zero code path impact on non-REST subclasses
- 25-01: buildCatalogProperties() left unchanged in RestIcebergCatalogPlugin -- redundant after fix but preserves branch accessor compatibility

### Roadmap Evolution

- Phase 25 added: Fix RESTCATALOG S3 filesystem config propagation to execution path

### Pending Todos

None yet.

### Blockers/Concerns

- Commit hash as REST prefix: Does Nessie accept commit hashes in Iceberg REST URI prefix? (AT COMMIT deferred to future, but verify during Phase 22)
- OAuth token lifecycle: Each per-branch RESTCatalog may maintain its own OAuth2 session (verify during Phase 22)

## Session Continuity

Last session: 2026-03-11
Stopped at: Completed 25-01-PLAN.md (S3 filesystem config propagation fix). Phase 25 complete. v1.4 milestone complete.
Resume file: None
