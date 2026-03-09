# Project Research Summary

**Project:** Dremio OSS v1.4 -- Nessie Branch-Aware REST Catalog
**Domain:** Version-controlled data catalog access -- extending the existing RESTCATALOG source type with Nessie AT BRANCH/TAG/COMMIT semantics
**Researched:** 2026-03-09
**Confidence:** HIGH (all findings from direct codebase analysis, bytecode decompilation of Iceberg 1.7.0, and official Nessie documentation)

## Executive Summary

This milestone adds Nessie version-control awareness (AT BRANCH, AT TAG, AT COMMIT) to Dremio's existing RESTCATALOG source type. The fundamental technical constraint driving the entire design is that the Iceberg `RESTCatalog` client's prefix is immutable after initialization -- the `ResourcePaths.prefix` field is `private final`, set once during `RESTSessionCatalog.initialize()`, and baked into every REST URL. Nessie encodes the branch reference in this prefix (e.g., prefix `dev` routes all requests to branch `dev`). This means a single `RESTCatalog` instance can only talk to one branch. The solution is a cache of per-branch `RESTCatalog` instances, each initialized with a URI that includes the branch name (e.g., `http://nessie:19120/iceberg/dev`), managed by a new `BranchAwareCatalogAccessorCache`.

The recommended approach avoids implementing the full `VersionedPlugin` interface (which has 25+ methods including branch CRUD and merge operations intended for the native Nessie API). Instead, a narrow `SupportsBranchAwareRestCatalog` interface is introduced with only three methods: `resolveVersionContext()`, `getCatalogAccessorForReference()`, and `getDefaultBranch()`. The RESTCATALOG plugin conditionally exposes this interface via the `isWrapperFor()` / `unwrap()` pattern when a new `enableNessie` config flag is true. A new dispatch branch in `CatalogImpl` routes queries with AT BRANCH/TAG/COMMIT to this interface, parallel to the existing `VersionedPlugin` path. This preserves complete backward compatibility -- non-Nessie RESTCATALOG sources are entirely unaffected.

The implementation requires zero new library dependencies. The Nessie client (`nessie-client` 0.100.3) and model types are already in Dremio's BOM; they just need to be declared in the `plugins/icebergcatalog` module POM. The critical risks are table cache poisoning across branches (same cache key, different data -- silent wrong results), plan cache serving stale plans from the wrong branch, and RESTCatalog connection pool exhaustion from unbounded per-branch catalog creation. All three are well-understood and have concrete prevention strategies documented in the research.

## Key Findings

### Recommended Stack

See `.planning/research/STACK.md` for full dependency analysis and integration architecture diagram.

No new third-party libraries enter the dependency tree. Everything needed is already in the Dremio BOM:

- **`nessie-client` (0.100.3):** Resolve branches/tags via Nessie API v2 -- needed for `resolveVersionContext()`, `getDefaultBranch()`, `listBranches()`, `listTags()`
- **`nessie-model` (0.100.3):** Reference, Branch, Tag domain types -- already used by `plugins/dataplane`
- **`iceberg-core` RESTCatalog (1.7.0-custom):** Per-branch catalog instances with branch-specific URI -- already the core of the RESTCATALOG plugin
- **`VersionContext` / `ResolvedVersionContext`:** Dremio's version model -- already imported by icebergcatalog plugin

The only POM change is declaring `nessie-client` and `nessie-model` as dependencies in `plugins/icebergcatalog/pom.xml` (versions managed by the existing `nessie-bom`).

### Expected Features

See `.planning/research/FEATURES.md` for full feature landscape, dependency graph, and complexity estimates.

**Must have (table stakes):**
- TS-8: `enableNessie` configuration toggle (gates all other features, keeps backward compat)
- TS-1: Auto-detect Nessie backend from config endpoint (`nessie.is-nessie-catalog=true`)
- TS-2: Default branch discovery from server (`nessie.default-branch.name` in config response)
- TS-3/TS-4/TS-5: AT BRANCH/TAG/COMMIT for SELECT queries (the core value proposition)
- TS-6: Fresh reference resolution per query (no stale branch pointers -- correctness requirement)
- TS-7: USE BRANCH/TAG/COMMIT session context (comes free once `VersionedPlugin`-like interface is implemented)

**Should have (differentiators):**
- D-1: Multi-branch JOINs (cross-branch comparison queries)
- D-2: SHOW BRANCHES/TAGS/LOGS commands (requires Nessie API v2 client)
- D-3: Namespace browsing per-branch in the UI
- D-4: Commit hash display in query profiles (likely works automatically)

**Defer (v2+):**
- AF-1: DML operations on branches (INSERT/UPDATE/DELETE AT BRANCH)
- AF-2: Branch/tag management (CREATE/DROP/MERGE BRANCH)
- AF-3: Views on branches
- AF-4: Combined Nessie versioning + Iceberg time travel syntax
- AF-5: Automatic branch sync/push/pull
- AF-6: `isVersioned=true` on the RESTCATALOG source type annotation (must remain false; use runtime `isWrapperFor` instead)

### Architecture Approach

See `.planning/research/ARCHITECTURE.md` for complete component hierarchy, data flow diagrams, and code-level modification specifications.

The architecture introduces a narrow interface (`SupportsBranchAwareRestCatalog`) rather than the full `VersionedPlugin`, with a per-branch catalog accessor cache. The key insight is that each branch gets its own `IcebergRestCatalogAccessor` wrapping its own `RESTCatalog` instance, and the table cache within each accessor is naturally scoped to one branch (no cache key changes needed within an accessor -- the scoping happens at the accessor selection level).

**New components:**
1. **`SupportsBranchAwareRestCatalog` interface** -- narrow contract: `resolveVersionContext()`, `getCatalogAccessorForReference()`, `getDefaultBranch()`
2. **`BranchAwareCatalogAccessorCache`** -- Caffeine cache of `CatalogAccessor` instances keyed by branch reference, with TTL and bounded size
3. **`NessieRestVersionContextResolver`** -- validates branch/tag existence by probing the Nessie REST config endpoint

**Modified components:**
1. **`RestIcebergCatalogPluginConfig`** -- add `enableNessieBranchAwareness` (Tag 13) and `defaultBranch` (Tag 14) fields
2. **`RestIcebergCatalogPlugin`** -- implement `SupportsBranchAwareRestCatalog` conditionally, add branch-scoped accessor factory
3. **`CatalogImpl`** -- add third dispatch branch: `isWrapperFor(SupportsBranchAwareRestCatalog.class)` between the existing VersionedPlugin and non-versioned paths
4. **`restcatalog-layout.json`** -- add Nessie options section in UI

### Critical Pitfalls

See `.planning/research/PITFALLS.md` for all 16 pitfalls with detection strategies and phase assignments.

1. **P1 -- Table cache poisoning across branches:** The existing `CatalogAccessorTableCacheKey` uses `(userId, TableIdentifier)` with no branch component. Same table path on two branches returns wrong cached metadata. **Prevention:** Use per-branch accessor instances so each branch has its own isolated table cache. Verify with test: query table T on branch A, then on branch B within the cache TTL, assert different snapshot IDs.

2. **P2 -- Plan cache serving stale plans across branch contexts:** The plan cache key does not include version context. `checkForVersionedTable()` only excludes `VersionedPlugin` sources, not `SupportsBranchAwareRestCatalog` sources. **Prevention:** For MVP, exclude Nessie-enabled RESTCATALOG sources from plan cache entirely (same pattern as native Nessie sources). Add branch to cache key as a follow-up optimization.

3. **P3 -- Default branch resolution race (stale default between planning and execution):** Branch pointers are mutable. The REST prefix sends the branch NAME, not a pinned commit hash. Between planning and execution, the branch HEAD can move, causing non-repeatable reads within a single query. **Prevention:** Resolve branch name to commit hash early via Nessie API; use commit hash in prefix if Nessie supports it. At minimum, ensure all table loads in a single query use the same resolved reference.

4. **P4 -- RESTCatalog connection pool exhaustion:** Each per-branch `RESTCatalog` instance creates its own HTTP client with connection pool. With N users x M branches, instances accumulate. **Prevention:** Bounded Caffeine cache (max ~10 entries) with TTL eviction and proper `close()` on removal. Most users access 2-3 branches actively.

5. **P5 -- Branch names with special characters break URL routing:** Branch names like `feature/auth-fix` contain `/` which is ambiguous in URL path segments. The `|` separator between branch and warehouse creates another collision vector. **Prevention:** URL-encode branch names in the prefix path segment. Test with adversarial names: `feature/fix`, `branch|name`, `branch@user`.

## Implications for Roadmap

Based on combined research, the dependency order is clear: config toggle must come first (gates everything), then Nessie detection and default branch (prerequisite for all version operations), then the core VersionedPlugin-like interface and CatalogImpl integration (the hardest piece), then cache correctness and polish.

### Phase 1: Configuration and Nessie Detection

**Rationale:** The `enableNessie` toggle gates all subsequent features. Auto-detection from the config endpoint and default branch discovery are low-effort, low-risk, and required by every downstream feature. This phase establishes the connection to Nessie and validates that the REST catalog is Nessie-backed before any behavioral changes.

**Delivers:** New config fields in `RestIcebergCatalogPluginConfig`, UI layout update, Nessie backend detection via `nessie.is-nessie-catalog` in config response, default branch read from `nessie.default-branch.name`, `NessieApiV2` client instantiation using `nessie.core-base-uri` from config overrides.

**Addresses:** TS-8 (enableNessie toggle), TS-1 (auto-detect Nessie), TS-2 (default branch discovery)

**Avoids:** No critical pitfalls in this phase -- it is purely configuration and detection with no behavioral changes to query execution.

### Phase 2: Branch-Aware Catalog Infrastructure

**Rationale:** This is the core architectural work. The `SupportsBranchAwareRestCatalog` interface, `BranchAwareCatalogAccessorCache`, and the per-branch `RESTCatalog` instance factory must be built and tested before any query-level integration. This phase is high-effort and high-risk -- the P1 (cache poisoning) and P4 (connection pool exhaustion) pitfalls must be addressed here by design, not retroactively. The per-branch accessor isolation pattern is the key architectural decision that prevents P1.

**Delivers:** `SupportsBranchAwareRestCatalog` interface, `BranchAwareCatalogAccessorCache` with Caffeine cache (bounded size, TTL eviction, proper lifecycle), branch-scoped `IcebergRestCatalogAccessor` factory in `RestIcebergCatalogPlugin`, `NessieRestVersionContextResolver` for branch/tag validation. Unit tests for cache behavior (expiry, concurrent access, cleanup, proper close on eviction).

**Addresses:** Architecture foundation for TS-3/TS-4/TS-5/TS-6

**Avoids:** P1 (cache poisoning -- per-branch accessor isolation), P4 (connection pool exhaustion -- bounded cache with eviction), P13 (thread safety -- separate instances, no shared mutable state), P16 (catalog lifecycle -- proper close on eviction via removal listener)

### Phase 3: CatalogImpl Integration and AT BRANCH/TAG/COMMIT

**Rationale:** With the infrastructure in place, this phase wires the new interface into Dremio's query execution pipeline. The critical modification is in `CatalogImpl` -- adding a third dispatch branch for `SupportsBranchAwareRestCatalog` between the existing `VersionedPlugin` and non-versioned paths. This is the highest-risk change because `CatalogImpl` is the central dispatcher for all dataset resolution. Plan cache correctness (P2) must also be addressed here since queries will now flow through the new path.

**Delivers:** AT BRANCH, AT TAG, AT COMMIT working for SELECT queries. USE BRANCH/TAG/COMMIT session context (comes free via existing `UseVersionHandler` code path once the interface is implemented). Plan cache exclusion for Nessie-enabled RESTCATALOG sources. End-to-end integration tests against a real Nessie instance.

**Addresses:** TS-3 (AT BRANCH), TS-4 (AT TAG), TS-5 (AT COMMIT), TS-6 (fresh resolution), TS-7 (USE BRANCH session)

**Avoids:** P2 (plan cache -- exclude from cache for MVP), P3 (stale default -- resolve per-query, never cache default branch name across queries), P5 (URL encoding -- validate and encode branch names in prefix builder)

### Phase 4: Error Handling, Multi-Branch, and SHOW Commands

**Rationale:** With the read path working, this phase focuses on polish: rich error messages that distinguish "branch not found" from "table not found on this branch" (P10), multi-branch JOIN verification (D-1 likely works already but needs validation), and SHOW BRANCHES/TAGS/LOGS commands via the Nessie API v2 client (D-2). This phase also addresses the metadata refresh background task conflict (P11) by ensuring background refresh only runs against the default branch.

**Delivers:** Rich branch-context error messages. SHOW BRANCHES/TAGS/LOGS working. Multi-branch JOINs verified. Background metadata refresh safely scoped to default branch. Namespace browsing per-branch documented as a known limitation (UI browse always shows default branch).

**Addresses:** D-1 (multi-branch JOINs), D-2 (SHOW commands), D-3 (namespace browsing -- documented limitation), D-4 (commit hash in profiles)

**Avoids:** P7 (table not found on one branch -- rich error messages), P10 (Nessie 404 vs REST Catalog 404 confusion), P11 (background refresh conflicts)

### Phase 5: Metadata Pipeline Integration and Hardening

**Rationale:** This phase addresses the remaining moderate pitfalls around metadata staleness, VDS interaction with branch context, and RBAC documentation. The metadata KV store interaction (P6, P11) requires careful handling -- non-default branch queries should bypass the KV store entirely and go directly to the REST catalog. VDS + definer rights interaction with branch context (P9) needs documentation and testing.

**Delivers:** Metadata validity checks (`isIcebergMetadataValid()`) working correctly with branch-scoped accessors. Non-default branch queries bypass KV store metadata cache. VDS behavior with AT BRANCH documented (branch in SQL text re-resolves at query time -- by design). Branch-level RBAC documented as delegated to Nessie server. Session branch context scoping verified.

**Addresses:** P6 (metadata staleness), P9 (VDS + definer + branch context), P8 (RBAC documentation), P12 (session context scoping), P15 (namespace listing documentation)

### Phase Ordering Rationale

- Phase 1 first because it is the lowest risk and gates everything else. It produces a testable "can we detect Nessie?" baseline before any behavioral changes.
- Phase 2 before Phase 3 because the cache infrastructure must exist and be tested before CatalogImpl integration -- building the infrastructure inside CatalogImpl simultaneously is a recipe for entangled bugs.
- Phase 3 is the critical path and highest risk. It touches `CatalogImpl`, the most sensitive class in the codebase. All table stakes features activate here.
- Phase 4 after Phase 3 because SHOW commands and error handling are polish on top of a working read path. Multi-branch JOINs depend on the per-branch accessor pattern being stable.
- Phase 5 last because metadata pipeline integration is the most subtle and hardest to test. It requires all other features to be working to exercise the edge cases (VDS + branch, background refresh + branch, metadata validity + branch).

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 2:** The `BranchAwareCatalogAccessorCache` lifecycle design needs explicit specification. How does `close()` interact with active queries holding references to accessors? The `ExpiringCatalogCache` already has a close-during-use race (P16) -- the per-branch design must not amplify it. Also: does Nessie support commit hashes as REST prefix values? This determines whether AT COMMIT is trivially supported or needs a separate resolution mechanism.
- **Phase 3:** The `CatalogImpl` modification is the riskiest change. The exact location and branching logic for `getTableSnapshotHelper()`, `getDatasetHandleHelper()`, and the removal of the "source does not support AT BRANCH" error need precise code-level design. The plan cache exclusion logic needs to account for `checkForVersionedTable()` not firing for non-`VersionedPlugin` sources.

Phases with standard patterns (skip research-phase):
- **Phase 1:** Pure config field additions and REST endpoint inspection. Well-documented, no design decisions.
- **Phase 4:** Error handling follows existing patterns. SHOW commands follow the existing `ShowBranchesHandler` pattern. Multi-branch JOINs are proven to work in the native Nessie source.
- **Phase 5:** Metadata pipeline decisions are documented in the research. The recommendation (bypass KV store for non-default branches) is straightforward.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Zero new dependencies. All libraries verified in BOM. POM changes are trivial declarations. Bytecode decompilation confirmed prefix immutability. |
| Features | HIGH | All table stakes features verified against existing code paths. Feature dependency graph is clear. Complexity estimates grounded in codebase analysis. |
| Architecture | HIGH | Per-branch catalog instance pattern validated against Iceberg RESTCatalog internals. `CatalogImpl` dispatch pattern verified. Narrow interface approach avoids VersionedPlugin complexity. |
| Pitfalls | HIGH for structural pitfalls (P1-P5 from code/bytecode analysis). MEDIUM for P3 (commit hash as prefix) and P14 (AT COMMIT support). | P3 consistency guarantee and P14 commit hash prefix support need runtime verification against Nessie server. |

**Overall confidence:** HIGH

### Gaps to Address

- **Commit hash as REST prefix:** Does Nessie accept a commit hash (e.g., `abc123def`) in the Iceberg REST URI prefix for AT COMMIT support? If not, AT COMMIT requires a separate Nessie API v2 call to resolve the commit to a branch, then use that branch's prefix. Test against Nessie 0.96+ during Phase 2.

- **OAuth token lifecycle per branch:** Each per-branch `RESTCatalog` instance may maintain its own OAuth2 token session. If the Nessie server uses per-branch OAuth scoping, multiple instances may cause excessive token refresh overhead. Verify during Phase 2 integration testing.

- **`CatalogImpl.getTableSnapshotHelper()` exact modification points:** The research identifies the general approach (add a third dispatch branch) but the exact line numbers and branching logic in `CatalogImpl` need precise design during Phase 3 planning. `CatalogImpl` is ~4000 lines with multiple interleaved code paths.

- **Metadata KV store interaction for branch-aware queries:** The recommendation is to bypass the KV store for non-default branches, but the exact mechanism (skip `SourceMetadataManager` entirely vs. return "always invalid" from `isIcebergMetadataValid()`) needs design during Phase 5.

- **View handling with branch-scoped accessors:** Iceberg Views loaded via the REST catalog should also be branch-aware. The `IcebergCatalogViewProvider` was not fully traced through the branch-scoped accessor path. Verify during Phase 3 integration testing.

- **RBAC interaction with branch access:** Branch-level RBAC is delegated to Nessie server-side authorization. If the Dremio RBAC system is enabled, SELECT privilege on the source grants access to all branches. Per-branch privilege checks are out of scope. Document this as a known limitation.

## Sources

### Primary (HIGH confidence)
- Dremio OSS codebase (direct source analysis): `RestIcebergCatalogPlugin.java`, `RestIcebergCatalogPluginConfig.java`, `IcebergCatalogPlugin.java`, `AbstractRestCatalogAccessor.java`, `CatalogImpl.java`, `VersionedPlugin.java`, `VersionContextResolverImpl.java`, `DataplanePlugin.java`, `NessiePlugin.java`, `NessieClient.java`, `NessieClientImpl.java`, `NessiePluginUtils.java`, `UseVersionHandler.java`, `ShowBranchesHandler.java`, `PlanCacheUtils.java`, `ExpiringCatalogCache.java`, `CatalogAccessorTableCacheKey.java`
- Iceberg bytecode decompilation (iceberg-core-1.7.0): `ResourcePaths` (confirmed `prefix` is `private final`), `RESTSessionCatalog` (confirmed `paths` field set once during `initialize()`), `RESTCatalog`, `CatalogProperties`
- [Nessie Iceberg REST Configuration Guide](https://projectnessie.org/guides/iceberg-rest/) -- prefix format, branch-to-URI mapping, `{ref}|{warehouse}` pattern
- [Nessie REST API spec](https://projectnessie.org/develop/rest/) -- v2 API endpoints for branch/tag resolution
- Root `pom.xml` -- `nessie-bom` version 0.100.3 confirmed

### Secondary (MEDIUM confidence)
- [Nessie GitHub Issue #9224](https://github.com/projectnessie/nessie/issues/9224) -- config endpoint response structure with `nessie.is-nessie-catalog`, `nessie.default-branch.name`, `nessie.core-base-uri`
- [Iceberg REST Catalog Spec](https://iceberg.apache.org/rest-catalog-spec/) -- general prefix behavior, error code structure
- [Dremio Blog: Nessie with Iceberg REST Catalog](https://www.dremio.com/blog/use-nessie-with-iceberg-rest-catalog/) -- architectural overview

### Tertiary (LOW confidence)
- Commit hash as Nessie REST prefix value -- inferred from Nessie documentation mentioning "detached references" but not explicitly confirmed for the Iceberg REST endpoint. Needs runtime verification.
- Nessie per-branch OAuth scoping behavior -- not documented; needs integration testing.

---
*Research completed: 2026-03-09*
*Ready for roadmap: yes*
