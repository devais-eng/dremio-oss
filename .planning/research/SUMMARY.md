# Project Research Summary

**Project:** Dremio OSS RBAC v1.1 — Privilege Context Switching, PDS SELECT, Container Visibility, VDS Lifecycle
**Domain:** Query engine access control — extending an existing deny-by-default RBAC system
**Researched:** 2026-02-20
**Confidence:** HIGH (all findings from direct codebase inspection on branch `rbac`, commit 2cc3b3c3d)

## Executive Summary

v1.0 shipped a functional deny-by-default RBAC system covering SELECT on VDS, EXECUTE on UDF, flat roles, and CREATE_VIEW. v1.1 extends this with four new capability areas: true definer rights for VDS (view expansion runs under the creator's identity), SELECT grants on physical tables (PDS), container visibility filtering (hide spaces/folders/sources the user has no access to), and VDS lifecycle privileges (enforce ALTER and DROP on views). Research was conducted exclusively by direct codebase analysis — every finding has a file path and line number behind it.

The single most important finding: the definer-rights expansion machinery already exists and is correct. `ViewExpander.expandRelNode()` already calls `builder.withUser(viewOwner)` to switch identity during view expansion. The only gap is that `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` returns `Optional.empty()` for `VIRTUAL_DATASET`, so `viewOwner` is always null today, meaning view expansion runs under the query user's identity (invoker semantics). This is a one-line fix in one method. Similarly, PDS enforcement is skipped by a deliberate guard (`if (!(table instanceof ViewTable)) return false`) and VDS lifecycle enforcement is simply missing call sites in `dropView()` and `updateView()`. Across all four features, there is no new storage layer, no new proto messages, and no SQL grammar changes required — all required APIs exist.

The key risk cluster is definer rights: eight pitfalls (P15, P16, P17, P22, P23, P24, P25, P27) are interdependent and must all be addressed in the same implementation phase. Most critically, the `isInDefinerContext` flag pattern (P25) must prevent the inner VDS privilege check from firing on nested views during definer expansion; the plan cache key must include the definer identity chain (P23); the `UserNotFoundException` fallback that silently re-expands as the query user must be replaced with an explicit error (P24); and EXPLAIN PLAN must be restricted to prevent physical table path leakage through the definer context (P22). Additionally, a critical semantic correction is needed from the original milestone scope: UDFs already use definer semantics, not invoker — `UserDefinedFunctionExpanderImpl.parseAndValidate()` explicitly calls `.withUser(owner)`. The v1.1 UDF work is verification and owner resolution hardening, not implementing a new invoker model.

## Key Findings

### Recommended Stack

No new technologies or external dependencies are needed for v1.1. All required APIs exist in the current codebase. The existing `RbacService.hasPrivilege()`, `CatalogImpl.validatePrivilege()`, and `CatalogImpl.isRbacDeniedForVds()` patterns are the complete enforcement toolkit. The KV store grant key format (`role_id|object_type|object_path|privilege`) already handles arbitrary object types including `"PDS"`. SQL grammar already parses `PDS`, `ALTER`, `DROP`, and `SHOW` tokens. `SqlGrant.GrantType.PDS` and `SqlGrant.Privilege.DROP` and `.ALTER` already exist as enum values.

See `.planning/research/STACK.md` for the full file-by-file change manifest.

**Files being modified (no new components):**
- `CatalogEntityOwnershipImpl.java` — return VDS owner instead of `Optional.empty()` for VIRTUAL_DATASET (1 conditional change)
- `CatalogImpl.java` — add `isRbacDeniedForPds()`, call `validatePrivilege()` in `dropView()` and `updateView()`, add `isInDefinerContext` flag, filter container listing methods
- `CatalogServiceHelper.java` — audit listing paths for completeness, extend `filterByVisibility()` for PDS opt-in
- `DACViewCreatorFactory.java` — verify `DatasetConfig.owner` is written at VDS create/update
- `ViewExpansionContext.java` — add cycle guard (`Set<NamespaceKey>`) for VDS-over-VDS chains
- `PlanCacheUtils.java` — include definer identity chain in cache key hash
- `ViewExpander.java` — replace `UserNotFoundException` fallback with explicit permission error

**What NOT to change:**
- `ViewExpander.expandRelNode()` — the `withUser(viewOwner)` call is already correct
- `RbacService.java` — all required interfaces already exist
- `rbac.proto` / `dataset.proto` — `DatasetConfig.owner` field already exists at proto L34
- SQL grammar — all tokens already parsed; `SqlGrantOnCatalog` and `SqlRevokeOnCatalog` already accept PDS, ALTER, DROP

### Expected Features

See `.planning/research/FEATURES.md` for the full feature analysis and dependency map.

**Must have (table stakes — v1.1 scope):**
- VDS definer rights: view expansion uses creator's identity (F1.1) — the security foundation that makes view-based access control meaningful; without this, users granted SELECT on a VDS fail at expansion if they lack underlying PDS access
- UDF rights verification and owner resolution hardening (F1.2) — UDFs already use definer semantics; v1.1 work is confirming owner resolution from `FunctionConfig` is consistent
- SELECT on PDS with opt-in enforcement (F1.3) — closes the bypass that lets users circumvent VDS restrictions by querying underlying physical tables directly
- Container visibility filtering — keep containers always visible, filter leaf objects (F1.4); no recursive tree walk
- ALTER VIEW privilege enforcement (F1.5) — separate from CREATE_VIEW; already parseable, just missing the call in `updateView()`
- DROP VIEW privilege enforcement (F1.6) — separate from ALTER; missing the call in `dropView()`
- CREATE_VIEW enforcement gap close (F1.7) — this call was never wired in `createView()` in v1.0; anyone can create a VDS regardless of grants

**Critical semantic correction on UDF scope:** The original milestone description requests "UDF invoker rights." Research shows UDFs already expand under the UDF owner's identity (definer semantics) via `UserDefinedFunctionExpanderImpl.parseAndValidate()` which calls `.withUser(dremioUdf.getOwner())`. This is not a gap to fix — it is the correct and intended design. v1.1 should document this explicitly and ensure `CatalogEntityOwnershipImpl` correctly returns the owner for `FUNCTION` type (currently also returns `Optional.empty()`).

**Should have (differentiators — after table stakes):**
- PDS migration strategy: auto-grant PUBLIC SELECT on existing PDS when enabling PDS enforcement (D2.2) — prevents rollout lockout
- Batch privilege checks and container visibility index (D2.3) — needed only at large catalog scale
- Visibility REST API for UI catalog tree rendering (D2.4) — depends on container visibility being complete
- DESCRIBE/EXPLAIN verification pass with integration tests (D2.1) — code is already correct; tests confirm it
- GRANT ALL ON VDS syntax expansion (D2.6) — ergonomics; `SqlGrant.Privilege.ALL` enum already exists

**Defer to v2+:**
- Per-view SQL SECURITY INVOKER toggle (A3.1) — adds per-view attribute complexity; no concrete use case
- Column-level grants (A3.2) — use views as column projection instead
- Row-level security (A3.3) — VDS already serves as row filter
- Container-level cascade grants, e.g., GRANT SELECT ON SPACE (A3.4) — path-prefix inheritance at check time is v2+ scope
- Nested roles (A3.5) — flat roles remain sufficient
- Cross-source impersonation integration with definer rights (A3.7) — connector-level concern, separate subsystem

### Architecture Approach

The architecture follows the existing enforcement pattern with surgical additions. The two enforcement entry points are already correct and must not be changed: `CatalogImpl.validatePrivilege()` for DDL operations (throws `UserException` on denial) and `CatalogImpl.isRbacDeniedForVds()` for read access (returns null on denial, no exception — this contract is critical). The definer-rights context switch works by `ViewExpander.expandViewInternal()` calling `builder.withUser(viewOwner)` which calls `catalog.resolveCatalog(viewOwner)` which produces a new `CatalogImpl` instance with `this.userName = viewOwner`. Any RBAC check inside that inner instance reads the definer's identity, not the caller's. Container listing (`getSources()`, `getSpaces()`, `getFolders()`) currently delegates to `userNamespaceService` with no RBAC filter; a post-retrieval filter needs to be added. The settled design for container visibility is to keep containers always visible and filter leaf objects — O(N) subtree scans are not acceptable at scale.

See `.planning/research/ARCHITECTURE.md` for complete data flow diagrams for all 4 features and the end-to-end VDS definer expansion trace.

**Major components and their v1.1 roles:**
1. `CatalogEntityOwnershipImpl` — the single gap in the definer rights chain; must return VDS owner for VIRTUAL_DATASET type
2. `CatalogImpl` — enforcement dispatcher; receives the `isInDefinerContext` flag, adds PDS check, wires lifecycle call sites, adds container listing filter
3. `ViewExpander` / `ViewExpansionContext` — minimal changes; add cycle guard to `ViewExpansionContext`, replace `UserNotFoundException` fallback in `ViewExpander`
4. `CatalogServiceHelper` — extend `filterByVisibility()` for PDS opt-in; audit all listing entry points for completeness
5. `PlanCacheUtils` — include definer identity chain in cache key; no other plan cache changes
6. `RbacService` / `GrantStore` — unchanged; existing `hasPrivilege()` and `listGrantsByObject()` APIs handle all new object types

### Critical Pitfalls

27 pitfalls are catalogued across v1.0 (P1–P14) and v1.1 (P15–P27). The 8 highest-severity v1.1 pitfalls that must ship together with definer rights as a single atomic implementation phase:

1. **P25 — Inner VDS check fires under definer (breaks VDS-over-VDS):** During definer expansion, the inner `CatalogImpl` resolves nested views. `isRbacDeniedForVds()` fires on those nested views against the definer's identity — but the definer never needed SELECT on the inner view. VDS-over-VDS queries fail entirely. Prevention: add `boolean isInDefinerContext` field to `CatalogImpl`; in `isRbacDeniedForVds()` return false when this flag is true (only `isRbacDeniedForPds()` checks run during definer expansion). Flag must propagate through nested `resolveCatalog()` calls.

2. **P23 — Plan cache excludes definer identity chain:** `PlanCacheUtils.generateCacheKey()` does not include view owner identities. Two users querying the same VDS may hit the same cached plan even if the definer's grants changed. Prevention: hash `getViewOwner()` usernames of all `ViewTable` nodes in the plan tree into the cache key; additionally call `LegacyPlanCache.invalidateCacheOnDataset()` when a view's owner changes.

3. **P24 — Deleted definer silently falls back to query user:** `ViewExpander.java` has an explicit `UserNotFoundException` catch block that falls back to `viewExpansionContext.getQueryUser()`. With deny-by-default RBAC, deleting a view owner silently grants the query user definer-level table access. Prevention: replace the fallback with `UserException.permissionError("View owner no longer exists; contact an administrator")`.

4. **P22 — EXPLAIN PLAN reveals physical table paths via definer context:** `EXPLAIN PLAN FOR SELECT * FROM my_view` returns the full physical plan including scan paths (e.g., `raw.customer_pii`) that the invoker may have no SELECT on. The definer's granted access to those tables is reflected in the plan but should not be disclosed to the invoker. Prevention: restrict `EXPLAIN PLAN PHYSICAL` to ADMIN users or users with direct SELECT on all referenced tables when RBAC is enabled; allow `EXPLAIN PLAN LOGICAL` for view-level access holders.

5. **P15 — Stale definer identity (frozen grant snapshot):** Store only the owner username string in `DatasetConfig.owner` — never store resolved grants. At expansion time, privilege checks run through the live `rbacService.hasPrivilege()` call against the current KV store. A revocation of the definer's underlying access must immediately break view queries.

6. **P16 — Wrong CatalogImpl instance in inner definer check:** During expansion, the outer CatalogImpl (caller's identity) and inner CatalogImpl (definer's identity) are both live on the call stack simultaneously. Passing the wrong reference via lambda or callback causes inner checks to fire under the caller's identity, making definer rights ineffective. Prevention: trace the exact call graph; add a `@VisibleForTesting` userName accessor to assert in tests.

7. **P17 — Missing cycle guard in VDS-over-VDS definer chain:** `ViewExpansionContext` counts per-owner tokens but does not detect revisiting the same view path. A cyclic view definition causes `StackOverflowError`. Prevention: add `Set<NamespaceKey>` to `ViewExpansionContext` tracking in-progress expansions; throw `UserException.validationError("View chain exceeds maximum depth")` on re-entry. Also add a hard depth counter (default max 50).

8. **P27 — ViewExpansionContext not thread-safe under parallel planning:** `ObjectIntHashMap` in `ViewExpansionContext` is mutable and not synchronized. If Calcite's VolcanoPlanner triggers parallel `ViewTable.toRel()` calls within a single query, concurrent token mutations produce `ConcurrentModificationException` or incorrect token counts. Prevention: audit whether parallel planning rule application reaches `ViewTable.toRel()`; if yes, switch to `ConcurrentHashMap<CatalogIdentity, AtomicInteger>`.

Additional active pitfalls from v1.0 that bear on v1.1:
- **P3 — Check once at the outer layer only:** SELECT check must use the caller's identity at `getTable()` entry; definer rights handle inner expansion. The outer check and inner expansion are separate layers — do not double-check.
- **P19 — Container visibility O(n) tree walk:** Use a single prefix-scan of the grant store (O(grants)), not a per-container recursive tree walk. Collect all matching path prefixes in one pass, intersect with the container list.
- **P21 — PDS grant key collision with VDS objectType:** Use `"PDS"` as a distinct `objectType` string for physical table grants, never `"VDS"`. The grant key `role_id|PDS|source.schema.table|SELECT` must be distinct from `role_id|VDS|space.view|SELECT` even if the paths match.

## Implications for Roadmap

Based on combined research, the dependency order is clear and follows a strict partial order: the definer-rights safety cluster must ship as a single unit (P22, P23, P24, P25 cannot be partially deployed); VDS lifecycle is independent and low-risk (pure call-site additions); PDS enforcement depends on definer rights being tested first (the definer's PDS access must be correctly resolved); and container visibility is fully independent.

### Phase 1: VDS Lifecycle and CREATE_VIEW Gap Close

**Rationale:** Three v1.0 gaps are the cheapest fixes with the highest coherence value. `CREATE_VIEW` enforcement was never wired in `createView()`. `dropView()` and `updateView()` have no `validatePrivilege()` call despite the infrastructure existing. These are 1–3 line additions each and make the existing privilege model internally consistent before adding new features. No new design decisions are required.

**Delivers:** Complete VDS privilege lifecycle (CREATE, ALTER, DROP all enforced); closes the v1.0 CREATE_VIEW enforcement gap; makes the privilege model semantically coherent.

**Addresses:** F1.5 (ALTER VIEW), F1.6 (DROP VIEW), F1.7 (CREATE_VIEW enforcement gap)

**Avoids:** P26 (ALTER/DROP grant stubs that activate silently — scan KV store for pre-existing ALTER/DROP grants before enabling enforcement in case test fixtures wrote them)

**Files:** `CatalogImpl.java` (add `validatePrivilege()` in `dropView()` and `updateView()`), `CatalogImpl.resolveRbacObjectType()` (add explicit ALTER/DROP cases), verify `CreateOrUpdateViewHandler` enforcement is wired correctly

**Research flag:** Standard patterns — skip research-phase. Pure call-site wiring of existing validated mechanism.

### Phase 2: VDS Definer Rights Safety Cluster

**Rationale:** Definer rights is the highest-value feature and the highest-risk. Eight pitfalls (P15, P16, P17, P22, P23, P24, P25, P27) are interdependent and must all be addressed in this phase — none can safely be deferred. The `isInDefinerContext` flag design (P25) must be agreed before a single line is written because it defines the boundary between outer caller checks and inner definer checks for the entire codebase. Phase 1 must be complete first so the lifecycle enforcement is known-correct when integration tests run.

**Delivers:** View expansion under creator's identity; VDS-over-VDS with different owners working correctly; plan cache correctly scoped to definer identity chains; safe deleted-owner handling; EXPLAIN PLAN leakage prevention; cycle detection in VDS chains.

**Addresses:** F1.1 (VDS definer rights)

**Must ship together:** P15 (live grants, not snapshots), P16 (correct CatalogImpl instance), P17 (cycle guard), P22 (EXPLAIN PLAN restriction), P23 (plan cache key), P24 (deleted definer error), P25 (isInDefinerContext flag), P27 (thread-safety audit)

**Files:** `CatalogEntityOwnershipImpl.java` (return VDS owner — primary 1-line fix), `CatalogImpl.java` (add `isInDefinerContext` field and propagation), `ViewExpansionContext.java` (add `Set<NamespaceKey>` cycle guard and depth counter), `ViewExpander.java` (replace `UserNotFoundException` fallback), `PlanCacheUtils.java` (definer identity in cache key hash), `DACViewCreatorFactory.java` (verify `DatasetConfig.owner` is written on VDS create/update)

**Research flag:** Needs research-phase during planning. The `isInDefinerContext` propagation semantics through nested `resolveCatalog()` calls need explicit design before coding. The threading model of Calcite's VolcanoPlanner at `ViewTable.toRel()` needs verification (affects P27 fix approach). The plan cache invalidation strategy on owner change needs a concrete design.

### Phase 3: UDF Rights Verification and Owner Resolution

**Rationale:** Research revealed that UDFs already use definer semantics — the `withUser(owner)` call is already in `parseAndValidate()`. The v1.1 UDF work is verifying that `CatalogEntityOwnershipImpl` correctly returns the owner for `FUNCTION` type (currently also returns `Optional.empty()`, same bug as VDS) and writing tests documenting the definer model. This shares the `CatalogEntityOwnershipImpl` fix location with Phase 2 and should come after Phase 2 is stable.

**Delivers:** Confirmed and tested UDF definer semantics; `FunctionConfig.owner` correctly resolved for all UDF creation paths; integration tests documenting expected behavior (caller needs EXECUTE; body runs as UDF owner).

**Addresses:** F1.2 (UDF rights — semantic confirmation + owner resolution hardening)

**Avoids:** P18 (unresolved UDF rights design — the answer is definer semantics by design; document and test, do not change the expansion path)

**Files:** `CatalogEntityOwnershipImpl.java` (add FUNCTION case alongside the VIRTUAL_DATASET fix), `UserDefinedFunctionExpanderImpl.java` (verify owner resolution and add logging), integration tests for EXECUTE enforcement + body expansion identity

**Research flag:** Standard patterns — skip research-phase. The code path is clear and well-understood from the Phase 2 investigation. Work is verification, owner resolution fix, and testing.

### Phase 4: PDS SELECT Enforcement (Opt-in)

**Rationale:** PDS enforcement must come after definer rights are confirmed working. If definer rights are not active, users granted SELECT on a VDS that wraps a PDS will fail at expansion because the definer's PDS grant is not checked. The opt-in design (restrict a PDS only if at least one PDS grant exists for that exact path) avoids the breaking-change risk of deny-by-default across all existing physical datasets.

**Delivers:** Admins can grant SELECT on physical tables to specific roles; PDS that have explicit grants configured are inaccessible to users without those grants; PDS without any grants remain universally accessible (backward compatible).

**Addresses:** F1.3 (PDS SELECT), D2.2 (PDS migration strategy)

**Avoids:** P21 (grant key collision — use `"PDS"` not `"VDS"` as objectType), P7 (migration lock-out — opt-in design prevents it by default)

**Key design decision that needs sign-off:** Use a separate `services.rbac.pds.enabled` config flag (not the existing `services.rbac.enabled` flag) to allow independent rollout. Without this, enabling VDS RBAC would simultaneously activate PDS enforcement, locking out all users from physical tables on day 1.

**Files:** `CatalogImpl.java` (add `isRbacDeniedForPds()`, wire into `getTable()`, `getTableNoResolve()`, `getTableNoColumnCount()`, `bulkGetTables()`), `CatalogImpl.resolveRbacObjectType()` (distinguish VDS vs PDS by checking actual table type), `CatalogServiceHelper.java` (extend `isVisibleToUser()` for opt-in PDS visibility filtering)

**Research flag:** Needs research-phase for the feature flag design and `bulkGetTables()` performance. The enforcement code pattern is standard (mirrors `isRbacDeniedForVds()`); the policy decisions (flag name, rollout migration, opt-in logic correctness with bulk operations) need explicit design review.

### Phase 5: Container Visibility Filtering

**Rationale:** Fully independent of all other phases. Can be developed in parallel with Phases 2–4 if resourcing allows. The design is settled: keep containers always visible, filter leaf objects. No recursive tree walk. No GRANT SHOW ON SPACE in this milestone.

**Delivers:** `filterByVisibility()` in `CatalogServiceHelper` correctly applied at all catalog listing entry points; `isVisibleToUser()` for container types uses prefix-scan of grant store rather than always returning true; pagination shortfall documented as known limitation.

**Addresses:** F1.4 (container visibility)

**Avoids:** P19 (O(n) tree walk — single prefix-scan of grant store, collect matching prefixes, intersect with container list), P20 (pagination shortfall — document as known behavior; long-term fix is filter-then-paginate which is out of scope for v1.1)

**Explicit non-scope:** Do NOT add "GRANT SHOW ON SPACE" or container-level cascade grants. Container visibility in this phase is a display filter, not an access grant. Document this distinction explicitly in code comments and operator documentation to prevent operator confusion (A3.4).

**Files:** `CatalogServiceHelper.java` (extend `filterByVisibility()`, modify `isVisibleToUser()` for FOLDER/SPACE/SOURCE, audit `getChildrenForPath()`, `getCatalogEntityByPath()`, `getTopLevelEntities()`), `CatalogImpl.java` (filter `getSources()`, `getSpaces()`, `getFolders()` return values)

**Research flag:** Standard patterns — skip research-phase. The design decision is settled (containers always visible, leaf filtering). Implementation follows the existing `filterByVisibility()` pattern.

### Phase 6: Integration Testing and DESCRIBE/EXPLAIN Verification

**Rationale:** Several v1.1 features are already correctly implemented and need only test coverage to confirm they work. DESCRIBE inherits SELECT via `catalog.getTable()` (no code change). EXPLAIN inherits through the full planning pipeline (no code change). This phase writes the complete integration test suite that proves all v1.1 features work correctly together, including the critical P3 correctness scenario.

**Delivers:** End-to-end integration tests: definer chain with multiple view owners; PDS enforcement under definer context; VDS-over-VDS with different owners; container visibility with pagination behavior documented; VDS lifecycle privilege enforcement; DESCRIBE/EXPLAIN enforcement inheritance; regression tests for all 8 P15–P25 pitfall scenarios.

**Addresses:** D2.1 (DESCRIBE/EXPLAIN verification)

**Critical test:** User B has SELECT on view V; definer A has SELECT on underlying PDS T; user B has no SELECT on T directly. Assert: query of V succeeds for B. Assert: direct `SELECT * FROM T` fails for B. Assert: revoking A's SELECT on T causes V queries to fail for B.

**Research flag:** Standard patterns — skip research-phase.

### Phase Ordering Rationale

- Phase 1 first because it is low-risk, makes the existing model coherent, and produces a clean baseline before the high-risk Phase 2 work begins. One phase, testable independently.
- Phase 2 (definer rights) before Phase 3 (UDF) because both share the `CatalogEntityOwnershipImpl` fix location, and the `isInDefinerContext` design from Phase 2 informs whether UDF expansion needs similar treatment.
- Phase 2 before Phase 4 (PDS) because the definer's PDS access must be correctly resolved — the inner `CatalogImpl` running as the definer must check PDS grants against the definer's identity, which requires Phase 2's `isInDefinerContext` flag and owner resolution to be working first.
- Phase 5 (container visibility) is fully independent and can run in parallel with Phases 2–4.
- Phase 6 last, as integration tests require all features to be present.

### Research Flags

Phases needing deeper research during planning:
- **Phase 2 (Definer Rights):** The `isInDefinerContext` propagation through nested `resolveCatalog()` chains is the key design question. Need to determine: does the flag survive re-entry into `resolveCatalog()` called from within definer expansion (for VDS-over-VDS)? Is Calcite's VolcanoPlanner single-threaded at `ViewTable.toRel()` invocation? What is the correct plan cache invalidation strategy when view ownership changes (hash all ViewOwner usernames vs. disable cache for views vs. call `invalidateCacheOnDataset()` on ownership change)? These answers drive the implementation architecture.
- **Phase 4 (PDS Enforcement):** Two decisions need sign-off before coding: (1) which config flag controls PDS enforcement activation (`services.rbac.pds.enabled` separate flag is recommended); (2) does the opt-in logic in `bulkGetTables()` require an additional `listGrantsByObject()` call per table in a batch, and what is the performance profile at reflection planning scale?

Phases with standard patterns (skip research-phase):
- **Phase 1 (VDS Lifecycle):** Pure call-site wiring of existing validated mechanism. All APIs verified. No design decisions.
- **Phase 3 (UDF Verification):** Code path is clear from Phase 2 investigation. Work is owner resolution fix and testing.
- **Phase 5 (Container Visibility):** Design decision settled. Follows existing `filterByVisibility()` pattern.
- **Phase 6 (Integration Tests):** Test writing. No design decisions.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All findings from direct codebase inspection. No external dependencies. No new APIs needed. All file paths and line numbers verified. |
| Features | HIGH | Feature gaps confirmed by code inspection (explicit `return false` guards, missing `validatePrivilege()` calls). Semantic correction on UDF rights (definer not invoker) confirmed from source. SQL standard behavioral references at MEDIUM. |
| Architecture | HIGH | All integration points verified by reading source files. End-to-end data flows traced for all 4 features. Component boundaries confirmed. |
| Pitfalls | HIGH for structural pitfalls (P1–P25 derived from code analysis) | P27 threading model of Calcite VolcanoPlanner is MEDIUM — inferred from architecture, not profiled or confirmed by test. |

**Overall confidence:** HIGH

### Gaps to Address

- **`DatasetConfig.owner` written on VDS create/update:** The field exists (proto L34) and `WriterUpdater.setOwner()` writes it for PDS. The trace through `DACViewCreatorFactory` → `datasetVersionMutator.save()` was not fully verified for the VDS path. Must be confirmed in Phase 2 before assuming the `CatalogEntityOwnershipImpl` fix produces non-null results.

- **`FunctionConfig.owner` completeness across UDF creation paths:** `UserDefinedFunctionServiceImpl` L148 reads the owner field. The trace confirming this field is consistently written across all UDF creation paths was not completed. Verify in Phase 3.

- **Calcite VolcanoPlanner threading at ViewTable.toRel():** P27 requires knowing whether view expansion can be triggered from parallel planning threads. This is a runtime behavior question that cannot be answered from static analysis. Audit this before committing to the `ViewExpansionContext` thread-safety approach in Phase 2.

- **PDS enforcement flag design:** The choice between `services.rbac.pds.enabled` (recommended separate flag) versus activating PDS enforcement via the existing `services.rbac.enabled` flag affects rollout safety for all existing deployments. Must be signed off before Phase 4 starts.

- **`bulkGetTables()` performance with opt-in PDS check:** The opt-in logic requires a `listGrantsByObject("PDS", path)` call per table. In `bulkGetTables()` this runs for every table in a batch (used by reflection planning). The performance profile needs measurement before shipping opt-in PDS enforcement.

- **AT-specifier + definer rights for versioned sources (Nessie):** For Nessie-backed VDS, does the view snapshot include the definer at the snapshot time-travel point? Does `VersionedDatasetAdapter` populate the owner field consistently? Not resolved by current research; defer or flag as a known gap in Phase 2.

## Sources

### Primary (HIGH confidence)
- Dremio OSS codebase, branch `rbac`, commit `2cc3b3c3d` — all findings verified by direct file inspection
- `CatalogEntityOwnershipImpl.java` L50–58 — explicit `Optional.empty()` for VIRTUAL_DATASET and FUNCTION
- `ViewExpander.java` L152–154 — `builder.withUser(viewOwner)` identity switch
- `UserDefinedFunctionExpanderImpl.java` L136 — `.withUser(dremioUdf.getOwner())` for UDF expansion
- `CatalogImpl.java` L2871 — `if (!(table instanceof ViewTable)) return false` PDS bypass
- `CatalogImpl.java` L2816 — `validatePrivilege()` enforcement hook
- `DatasetManager.java` L922 — `getEntityOwner(CatalogEntityKey)` VDS owner resolution path
- `CatalogServiceHelper.java` L3115, L3142 — `filterByVisibility()` and container always-visible return
- `CatalogImpl.java` L3514, L3539, L3668 — `getSources()`, `getFolders()`, `getSpaces()` unfiltered delegation
- `SqlGrant.java` L57, L51, L87 — DROP, ALTER, PDS enum values confirmed
- `dataset.proto` L34 — `optional string owner = 3` field confirmed
- `DropViewHandler.java` L55 — `validatePrivilege(path, ALTER)` already called
- `CreateOrUpdateViewHandler.java` L105 — `validatePrivilege(path, CREATE_VIEW)` already called
- `ViewExpansionContext.java` — `ObjectIntHashMap userTokens` mutable state, no cycle detection
- `PlanCacheUtils.generateCacheKey()` — absence of user identity or definer chain confirmed

### Secondary (MEDIUM confidence)
- SQL:1999 Section 11.53 — definer/invoker semantics for routines (training data, not verified against spec)
- SQL:2003 `SQL SECURITY DEFINER` default for views (training data cross-referenced across PostgreSQL, Oracle, MySQL, Snowflake)
- Container visibility conventions (PostgreSQL, Snowflake, Databricks Unity Catalog "at least one accessible descendant" rule) — training data, multiple sources agree

### Tertiary (LOW confidence)
- Calcite VolcanoPlanner threading behavior at `ViewTable.toRel()` invocation — inferred from architecture; not profiled or confirmed by test

---
*Research completed: 2026-02-20*
*Ready for roadmap: yes*
