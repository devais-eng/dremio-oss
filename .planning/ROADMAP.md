# Roadmap: Dremio OSS Enhancements

## Milestones

- ✅ **v1.0 Naive RBAC** — Phases 1-6 (shipped 2026-02-19)
- ✅ **v1.1 Enable Iceberg REST Catalog** — Phases 7-8 (shipped 2026-02-20)
- ✅ **v1.2 GitHub Actions Docker Distribution** — Phases 9-11 (shipped 2026-02-21)
- ✅ **v1.3 Privilege Context & Enforcement** — Phases 12-20 (shipped 2026-02-24)
- 🚧 **v1.4 Nessie Branch-Aware REST Catalog** — Phases 21-24 (in progress)

## Phases

<details>
<summary>✅ v1.0 Naive RBAC (Phases 1-6) — SHIPPED 2026-02-19</summary>

- [x] Phase 1: Design and Proto Schema (2/2 plans) — completed 2026-02-17
- [x] Phase 2: Persistence Layer (2/2 plans) — completed 2026-02-17
- [x] Phase 3: Service Layer (2/2 plans) — completed 2026-02-17
- [x] Phase 4: Catalog Enforcement and DI Wiring (3/3 plans) — completed 2026-02-18
- [x] Phase 5: DDL Handlers and System Tables (3/3 plans) — completed 2026-02-18
- [x] Phase 6: REST API and Access Path Hardening (3/3 plans) — completed 2026-02-18

See `milestones/v1.0-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.1 Enable Iceberg REST Catalog (Phases 7-8) — SHIPPED 2026-02-20</summary>

- [x] Phase 7: Plugin Wiring (1/1 plans) — completed 2026-02-20
- [x] Phase 8: End-to-End Validation (2/2 plans) — completed 2026-02-20

See `milestones/v1.1-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.2 GitHub Actions Docker Distribution (Phases 9-11) — SHIPPED 2026-02-21</summary>

- [x] Phase 9: Maven Build in CI (1/1 plans) — completed 2026-02-20
- [x] Phase 10: Dockerfile Adaptation (1/1 plans) — completed 2026-02-20
- [x] Phase 11: ECR Authentication and Push (1/1 plans) — completed 2026-02-20

See `milestones/v1.2-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.3 Privilege Context & Enforcement (Phases 12-20) — SHIPPED 2026-02-24</summary>

- [x] Phase 12: VDS Lifecycle Privilege Enforcement (2/2 plans) — completed 2026-02-21
- [x] Phase 13: VDS Definer Rights Safety Cluster (2/2 plans) — completed 2026-02-21
- [x] Phase 14: UDF Rights Verification and Owner Resolution (2/2 plans) — completed 2026-02-21
- [x] Phase 15: PDS SELECT Enforcement (Opt-in) (2/2 plans) — completed 2026-02-21
- [x] Phase 16: Container Visibility Filtering (2/2 plans) — completed 2026-02-21
- [x] Phase 17: Metadata Safety and Integration Testing (3/3 plans) — completed 2026-02-21
- [x] Phase 18: Code Hardening (1/1 plan) — completed 2026-02-23
- [x] Phase 19: Test Coverage and Documentation (1/1 plan) — completed 2026-02-23
- [x] Phase 20: File Browse and Promote RBAC Enforcement (2/2 plans) — completed 2026-02-23

See `milestones/v1.3-ROADMAP.md` for full phase details.

</details>

### v1.4 Nessie Branch-Aware REST Catalog (In Progress)

**Milestone Goal:** Enable Nessie version-control features (AT BRANCH) through the existing RESTCATALOG source type, allowing multi-branch queries against Nessie REST catalog servers.

- [x] **Phase 21: Configuration and Nessie Detection** - enableNessie toggle, auto-detect Nessie backend, discover default branch (completed 2026-03-09)
- [x] **Phase 22: Branch-Aware Catalog Infrastructure** - Per-branch RESTCatalog cache with Caffeine, branch-isolated table caching (completed 2026-03-09)
- [x] **Phase 23: CatalogImpl Integration and AT BRANCH Queries** - Wire SupportsBranchAwareRestCatalog into query pipeline, plan cache safety (completed 2026-03-10)
- [ ] **Phase 24: Multi-Branch Queries and Hardening** - Cross-branch JOINs, error handling, edge case hardening

## Phase Details

### Phase 21: Configuration and Nessie Detection
**Goal**: Users can configure a RESTCATALOG source for Nessie mode and the plugin correctly identifies Nessie backends and their default branch
**Depends on**: Nothing (first phase of v1.4)
**Requirements**: CFG-01, CFG-02, CFG-03, CMP-01
**Success Criteria** (what must be TRUE):
  1. User can create a RESTCATALOG source with `enableNessie=true` via UI or API, and the config persists across restarts
  2. Plugin auto-detects that the REST catalog is Nessie-backed by inspecting the config endpoint response for `nessie.is-nessie-catalog=true`
  3. Plugin reads the default branch name from the Nessie server config (`nessie.default-branch.name`) without any user-provided branch configuration
  4. Existing RESTCATALOG sources with `enableNessie=false` (or unset) behave exactly as before -- no new code paths execute, no new network calls, no behavioral changes
**Plans**: 2 plans

Plans:
- [ ] 21-01-PLAN.md — Add enableNessie config field and UI layout section
- [ ] 21-02-PLAN.md — Implement Nessie backend detection and default branch discovery

### Phase 22: Branch-Aware Catalog Infrastructure
**Goal**: Per-branch RESTCatalog instances are managed in a bounded cache with branch-isolated table metadata, ready for query integration
**Depends on**: Phase 21
**Requirements**: INF-01, INF-02
**Success Criteria** (what must be TRUE):
  1. Requesting a catalog accessor for a branch returns a RESTCatalog instance initialized with that branch's URI prefix, and repeated requests for the same branch return the cached instance
  2. The branch cache is bounded (max entries) and evicts stale entries by TTL, calling `close()` on evicted RESTCatalog instances to prevent connection pool exhaustion
  3. Table metadata loaded via one branch's accessor is never returned for a different branch -- each per-branch accessor has its own isolated table cache
**Plans**: 1 plan

Plans:
- [ ] 22-01-PLAN.md — Per-branch RESTCatalog cache with Caffeine, plugin lifecycle integration

### Phase 23: CatalogImpl Integration and AT BRANCH Queries
**Goal**: Users can run SELECT queries with AT BRANCH syntax on Nessie-enabled RESTCATALOG sources, with correct default branch behavior and plan cache safety
**Depends on**: Phase 22
**Requirements**: BRQ-01, BRQ-02, INF-03, CMP-02
**Success Criteria** (what must be TRUE):
  1. User can execute `SELECT * FROM nessie_source.namespace.table AT BRANCH "dev"` and get data from the `dev` branch
  2. User can execute `SELECT * FROM nessie_source.namespace.table` (no AT BRANCH) and get data from the server-defined default branch, with the default branch resolved fresh per query (never cached across queries)
  3. Running the same query twice against a branch where data changed between executions returns the updated data (plan cache does not serve stale cross-branch results)
  4. A Nessie-enabled source queried without AT BRANCH returns identical results to a non-Nessie source pointing at the same catalog endpoint (default branch serves same data as unversioned access)
**Plans**: 2 plans

Plans:
- [ ] 23-01-PLAN.md — Interface definition, plugin implementation, CatalogUtil gate expansion, plan cache safety
- [ ] 23-02-PLAN.md — CatalogImpl three-way dispatch for AT BRANCH queries

### Phase 24: Multi-Branch Queries and Hardening
**Goal**: Users can join tables across different branches in a single query, with clear error messages for branch-related failures
**Depends on**: Phase 23
**Requirements**: BRQ-03
**Success Criteria** (what must be TRUE):
  1. User can execute a JOIN between `table_a AT BRANCH "main"` and `table_b AT BRANCH "dev"` in a single SELECT query and get correct results from both branches
  2. When a user references a branch that does not exist, the error message clearly states the branch was not found (not a generic "table not found" error)
  3. When a user references a table that exists on one branch but not another, the error message distinguishes "table not found on branch X" from "branch X not found"
**Plans**: TBD

Plans:
- [ ] 24-01: TBD
- [ ] 24-02: TBD

## Quick Tasks

Ad-hoc tasks outside the milestone phase structure. See `.planning/quick/` for details.

| # | Description | Date | Status |
|---|-------------|------|--------|
| 1 | Fix Github Actions docker build ARG JAVA_IMAGE scope | 2026-02-25 | ✅ Done |
| 2 | Split docker-ecr workflow into build and docker jobs | 2026-02-25 | ✅ Done |
| 3 | Switch Docker push from ECR to GHCR | 2026-02-28 | ✅ Done |
| 4 | Merge develop into rbac and align .planning directory | 2026-03-01 | ✅ Done |

## Progress

**Execution Order:**
Phases execute in numeric order: 21 → 22 → 23 → 24

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Design and Proto Schema | v1.0 | 2/2 | Complete | 2026-02-17 |
| 2. Persistence Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 3. Service Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 4. Catalog Enforcement and DI Wiring | v1.0 | 3/3 | Complete | 2026-02-18 |
| 5. DDL Handlers and System Tables | v1.0 | 3/3 | Complete | 2026-02-18 |
| 6. REST API and Access Path Hardening | v1.0 | 3/3 | Complete | 2026-02-18 |
| 7. Plugin Wiring | v1.1 | 1/1 | Complete | 2026-02-20 |
| 8. End-to-End Validation | v1.1 | 2/2 | Complete | 2026-02-20 |
| 9. Maven Build in CI | v1.2 | 1/1 | Complete | 2026-02-20 |
| 10. Dockerfile Adaptation | v1.2 | 1/1 | Complete | 2026-02-20 |
| 11. ECR Authentication and Push | v1.2 | 1/1 | Complete | 2026-02-20 |
| 12. VDS Lifecycle Privilege Enforcement | v1.3 | 2/2 | Complete | 2026-02-21 |
| 13. VDS Definer Rights Safety Cluster | v1.3 | 2/2 | Complete | 2026-02-21 |
| 14. UDF Rights Verification and Owner Resolution | v1.3 | 2/2 | Complete | 2026-02-21 |
| 15. PDS SELECT Enforcement (Opt-in) | v1.3 | 2/2 | Complete | 2026-02-21 |
| 16. Container Visibility Filtering | v1.3 | 2/2 | Complete | 2026-02-21 |
| 17. Metadata Safety and Integration Testing | v1.3 | 3/3 | Complete | 2026-02-21 |
| 18. Code Hardening | v1.3 | 1/1 | Complete | 2026-02-23 |
| 19. Test Coverage and Documentation | v1.3 | 1/1 | Complete | 2026-02-23 |
| 20. File Browse and Promote RBAC Enforcement | v1.3 | 2/2 | Complete | 2026-02-23 |
| 21. Configuration and Nessie Detection | v1.4 | Complete    | 2026-03-09 | - |
| 22. Branch-Aware Catalog Infrastructure | v1.4 | Complete    | 2026-03-09 | - |
| 23. CatalogImpl Integration and AT BRANCH Queries | v1.4 | Complete    | 2026-03-10 | - |
| 24. Multi-Branch Queries and Hardening | v1.4 | 0/? | Not started | - |
