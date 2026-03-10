---
phase: 23-catalogimpl-integration-and-at-branch-queries
verified: 2026-03-10T11:15:00Z
status: passed
score: 9/9 must-haves verified
must_haves:
  truths:
    - "SupportsBranchAwareRestCatalog interface exists with 3 methods: resolveVersionContext, getDatasetHandleForBranch, getDefaultBranch"
    - "RestIcebergCatalogPlugin.isWrapperFor(SupportsBranchAwareRestCatalog.class) returns true only when isNessieDetected is true"
    - "RestIcebergCatalogPlugin.isWrapperFor(SupportsBranchAwareRestCatalog.class) returns false when isNessieDetected is false"
    - "CatalogUtil.forATSpecifierAccess() returns true for AT BRANCH on Nessie-enabled RESTCATALOG sources"
    - "Plan cache excludes queries touching Nessie-enabled RESTCATALOG sources (same as native Nessie)"
    - "AT BRANCH 'dev' query on Nessie-enabled RESTCATALOG source dispatches to branch-aware code path, not the non-versioned error path"
    - "AT BRANCH query loads table via branch-scoped accessor (not default accessor)"
    - "Query without AT BRANCH on Nessie-enabled source follows existing non-versioned path (default accessor, no new code)"
    - "getDatasetHandleHelper also dispatches to branch-aware path for AT BRANCH queries"
  artifacts:
    - path: "sabot/kernel/src/main/java/com/dremio/exec/catalog/SupportsBranchAwareRestCatalog.java"
      provides: "Narrow 3-method interface extending Wrapper for branch-aware REST catalog access"
      contains: "interface SupportsBranchAwareRestCatalog extends Wrapper"
    - path: "plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java"
      provides: "Conditional SupportsBranchAwareRestCatalog implementation gated on isNessieDetected"
      contains: "implements SupportsBranchAwareRestCatalog"
    - path: "sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogUtil.java"
      provides: "Expanded forATSpecifierAccess gate recognizing SupportsBranchAwareRestCatalog"
      contains: "SupportsBranchAwareRestCatalog"
    - path: "sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheUtils.java"
      provides: "Plan cache exclusion for branch-aware REST catalog sources"
      contains: "SupportsBranchAwareRestCatalog"
    - path: "sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java"
      provides: "Three-way dispatch in getTableSnapshotHelper and getDatasetHandleHelper"
      contains: "getTableSnapshotForBranchAwareRestSource"
  key_links:
    - from: "RestIcebergCatalogPlugin"
      to: "SupportsBranchAwareRestCatalog"
      via: "implements + conditional isWrapperFor override"
    - from: "CatalogUtil.forATSpecifierAccess"
      to: "SupportsBranchAwareRestCatalog"
      via: "isWrapperFor check in requestedPluginSupportsBranchAwareRest"
    - from: "PlanCacheUtils.checkForVersionedTable"
      to: "CatalogUtil.requestedPluginSupportsBranchAwareRest"
      via: "direct method call"
    - from: "CatalogImpl.getTableSnapshotHelper"
      to: "SupportsBranchAwareRestCatalog"
      via: "isWrapperFor check as second dispatch branch"
    - from: "CatalogImpl.getTableSnapshotForBranchAwareRestSource"
      to: "SupportsBranchAwareRestCatalog.getDatasetHandleForBranch"
      via: "unwrap + method call to load table from branch-scoped accessor"
    - from: "CatalogImpl.getDatasetHandleHelper"
      to: "SupportsBranchAwareRestCatalog"
      via: "isWrapperFor check as second dispatch branch"
---

# Phase 23: CatalogImpl Integration and AT Branch Queries Verification Report

**Phase Goal:** Users can run SELECT queries with AT BRANCH syntax on Nessie-enabled RESTCATALOG sources, with correct default branch behavior and plan cache safety
**Verified:** 2026-03-10T11:15:00Z
**Status:** passed
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | SupportsBranchAwareRestCatalog interface exists with 3 methods: resolveVersionContext, getDatasetHandleForBranch, getDefaultBranch | VERIFIED | File exists at `sabot/kernel/.../SupportsBranchAwareRestCatalog.java` (67 lines), extends Wrapper, all 3 method signatures present with proper Javadoc |
| 2 | RestIcebergCatalogPlugin.isWrapperFor(SupportsBranchAwareRestCatalog.class) returns true only when isNessieDetected is true | VERIFIED | Line 270-271: `if (SupportsBranchAwareRestCatalog.class.equals(clazz)) { return isNessieDetected; }` |
| 3 | RestIcebergCatalogPlugin.isWrapperFor(SupportsBranchAwareRestCatalog.class) returns false when isNessieDetected is false | VERIFIED | Same code path: `return isNessieDetected` returns false when detection fails (lines 221, 229 set false on failure) |
| 4 | CatalogUtil.forATSpecifierAccess() returns true for AT BRANCH on Nessie-enabled RESTCATALOG sources | VERIFIED | Lines 302-312: `isBranchAwareRestCatalog` checked via `requestedPluginSupportsBranchAwareRest`, OR'd into condition with `isVersionedTable` and `isTimeTravelType()` |
| 5 | Plan cache excludes queries touching Nessie-enabled RESTCATALOG sources | VERIFIED | PlanCacheUtils lines 311-313: `CatalogUtil.requestedPluginSupportsBranchAwareRest(table.getPath().getRoot(), catalog)` added to `checkForVersionedTable()` |
| 6 | AT BRANCH query on Nessie-enabled RESTCATALOG source dispatches to branch-aware code path | VERIFIED | CatalogImpl lines 802-805: `isWrapperFor(SupportsBranchAwareRestCatalog.class) && context != null && !context.isTimeTravelType()` dispatches to `getTableSnapshotForBranchAwareRestSource` |
| 7 | AT BRANCH query loads table via branch-scoped accessor (not default accessor) | VERIFIED | CatalogImpl lines 887-917: unwraps to `SupportsBranchAwareRestCatalog`, calls `resolveVersionContext`, `getDatasetHandleForBranch` with resolved branch name, builds `MaterializedDatasetTableProvider` |
| 8 | Query without AT BRANCH on Nessie-enabled source follows existing non-versioned path | VERIFIED (by design) | `forATSpecifierAccess` requires `hasTableVersionContext()` which is false without AT BRANCH. Query enters `getTable(NamespaceKey)` -> DatasetManager -> default accessor. No new code executes. |
| 9 | getDatasetHandleHelper also dispatches to branch-aware path for AT BRANCH queries | VERIFIED | CatalogImpl lines 1034-1040: identical three-way dispatch pattern. `getDatasetHandleForBranchAwareRestSource` (lines 1110-1126) follows same unwrap/resolve/load pattern. |

**Score:** 9/9 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `sabot/kernel/.../SupportsBranchAwareRestCatalog.java` | 3-method interface extending Wrapper | VERIFIED | 67 lines, proper license header, Javadoc, `resolveVersionContext`, `getDatasetHandleForBranch`, `getDefaultBranch` |
| `plugins/icebergcatalog/.../RestIcebergCatalogPlugin.java` | Conditional SupportsBranchAwareRestCatalog implementation | VERIFIED | `implements SupportsBranchAwareRestCatalog` on class declaration (line 118), isWrapperFor/unwrap gated on isNessieDetected, resolveVersionContext handles BRANCH/TAG/NOT_SPECIFIED, getDatasetHandleForBranch delegates to branch accessor |
| `sabot/kernel/.../CatalogUtil.java` | Expanded forATSpecifierAccess gate | VERIFIED | `requestedPluginSupportsBranchAwareRest` helper (lines 148-156), `forATSpecifierAccess` expanded (lines 306-312) |
| `sabot/kernel/.../PlanCacheUtils.java` | Plan cache exclusion for branch-aware REST sources | VERIFIED | `checkForVersionedTable` extended (lines 311-313) with `requestedPluginSupportsBranchAwareRest` check |
| `sabot/kernel/.../CatalogImpl.java` | Three-way dispatch in getTableSnapshotHelper and getDatasetHandleHelper | VERIFIED | Both methods have VersionedPlugin -> SupportsBranchAwareRestCatalog -> non-versioned dispatch. Two new helper methods: `getTableSnapshotForBranchAwareRestSource` (887-917) and `getDatasetHandleForBranchAwareRestSource` (1110-1126) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| RestIcebergCatalogPlugin | SupportsBranchAwareRestCatalog | implements + conditional isWrapperFor | WIRED | Class declaration line 118, isWrapperFor at line 270, unwrap at line 278 |
| CatalogUtil.forATSpecifierAccess | SupportsBranchAwareRestCatalog | isWrapperFor check via helper | WIRED | Line 307 calls `requestedPluginSupportsBranchAwareRest` which calls `isWrapperFor(SupportsBranchAwareRestCatalog.class)` at line 152 |
| PlanCacheUtils.checkForVersionedTable | CatalogUtil.requestedPluginSupportsBranchAwareRest | direct method call | WIRED | Line 312: `CatalogUtil.requestedPluginSupportsBranchAwareRest(table.getPath().getRoot(), catalog)` |
| CatalogImpl.getTableSnapshotHelper | SupportsBranchAwareRestCatalog | isWrapperFor check | WIRED | Line 802: `plugin.getPlugin().get().isWrapperFor(SupportsBranchAwareRestCatalog.class)` |
| CatalogImpl.getTableSnapshotForBranchAwareRestSource | SupportsBranchAwareRestCatalog.getDatasetHandleForBranch | unwrap + method call | WIRED | Lines 889-899: unwrap, resolveVersionContext, getDatasetHandleForBranch, result used in MaterializedDatasetTableProvider |
| CatalogImpl.getDatasetHandleHelper | SupportsBranchAwareRestCatalog | isWrapperFor check | WIRED | Line 1037: matching dispatch condition |
| CatalogImpl.getDatasetHandleForBranchAwareRestSource | SupportsBranchAwareRestCatalog.getDatasetHandleForBranch | unwrap + method call | WIRED | Lines 1114-1123: unwrap, resolve, getDatasetHandleForBranch, return handle |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| BRQ-01 | 23-02 | User can SELECT from a table AT BRANCH on Nessie-enabled RESTCATALOG source | SATISFIED | CatalogImpl three-way dispatch routes AT BRANCH through SupportsBranchAwareRestCatalog to branch-scoped accessor. Complete code path verified: forATSpecifierAccess gate -> getTableSnapshotHelper dispatch -> getTableSnapshotForBranchAwareRestSource -> unwrap/resolve/load |
| BRQ-02 | 23-02 | Queries without AT BRANCH use server-defined default branch (always fresh) | SATISFIED (by design) | Without AT BRANCH, `hasTableVersionContext()` is false, flow enters `getTable(NamespaceKey)` -> existing DatasetManager path -> default CatalogAccessor -> base REST endpoint. Nessie resolves to current default branch on every REST call. No caching of default branch identity in query path. |
| INF-03 | 23-01 | Plan cache correctly handles branch-aware queries | SATISFIED | PlanCacheUtils.checkForVersionedTable extended at line 312 to detect SupportsBranchAwareRestCatalog sources. Uses existing NOT_PUT_VERSIONED_TABLE exclusion event. |
| CMP-02 | 23-01, 23-02 | Nessie-enabled source without AT BRANCH behaves identically to non-Nessie | SATISFIED (by design) | Three-way dispatch only activates for `context != null && !context.isTimeTravelType()`. Without version context, flow bypasses dispatch entirely. Default accessor path identical to non-Nessie. |

No orphaned requirements found -- all 4 requirement IDs declared in PLAN frontmatter (BRQ-01, BRQ-02, INF-03, CMP-02) are accounted for in REQUIREMENTS.md traceability table as mapped to Phase 23.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| CatalogImpl.java | Various | Pre-existing TODO/DX-* comments | Info | All TODO comments in CatalogImpl.java are pre-existing (DX-65443, DX-44984, DX-91837, etc.) -- none introduced by this phase |
| RestIcebergCatalogPlugin.java | Various | Pre-existing TODO/DX-* comments | Info | All TODO comments are pre-existing (DX-99112, DX-99790, etc.) -- none introduced by this phase |

No blocker or warning anti-patterns found in phase 23 code. No stubs, no placeholder implementations, no empty handlers, no console.log-only implementations.

### Human Verification Required

### 1. End-to-End AT BRANCH Query

**Test:** Configure a Nessie-backed RESTCATALOG source, create table on branch "dev", run `SELECT * FROM source."ns"."table" AT BRANCH "dev"`
**Expected:** Query returns data from the "dev" branch, not the default branch
**Why human:** Requires running Nessie server and actual query execution; cannot verify data correctness through static analysis

### 2. Default Branch Freshness (BRQ-02)

**Test:** Run `SELECT * FROM source."ns"."table"` without AT BRANCH on a Nessie-enabled source, then change the default branch on the Nessie server, run again
**Expected:** Second query uses the new default branch without restarting the source
**Why human:** Requires runtime Nessie server state mutation and observing query behavior across multiple executions

### 3. Plan Cache Exclusion in Practice (INF-03)

**Test:** Run same AT BRANCH query twice, verify plan cache metrics show NOT_PUT_VERSIONED_TABLE event
**Expected:** Query is not cached, no stale plan served
**Why human:** Requires examining plan cache metrics or debug logging at runtime

### Gaps Summary

No gaps found. All 9 must-haves verified across both plans. All 4 requirements (BRQ-01, BRQ-02, INF-03, CMP-02) are satisfied. All artifacts exist, are substantive (non-stub implementations with proper logic), and are wired together through the full dispatch chain:

1. **Interface layer:** SupportsBranchAwareRestCatalog defines the contract (3 methods, extends Wrapper)
2. **Plugin layer:** RestIcebergCatalogPlugin implements conditionally, gated on runtime Nessie detection
3. **Gate layer:** CatalogUtil.forATSpecifierAccess routes AT BRANCH queries into the versioned dispatch path
4. **Dispatch layer:** CatalogImpl three-way dispatch in both getTableSnapshotHelper and getDatasetHandleHelper
5. **Cache layer:** PlanCacheUtils excludes branch-aware sources from plan cache

The code correctly avoids all pitfalls documented in the research: no VersionContextResolverImpl usage in branch-aware path (Pitfall 6), no ManagedStoragePlugin.getDatasetHandle bypass needed (Pitfall 3 -- encapsulated in plugin), CatalogUtil gate expanded (Pitfall 1), and plan cache extended (INF-03).

All 4 commits verified: d6ae160d0, 1068ffb04, b90156a4d, 8dbe71c64.

---

_Verified: 2026-03-10T11:15:00Z_
_Verifier: Claude (gsd-verifier)_
