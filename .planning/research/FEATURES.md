# Feature Landscape: Nessie Branch-Aware RESTCATALOG

**Domain:** Version-controlled data catalog access via Iceberg REST Catalog backed by Nessie
**Researched:** 2026-03-09
**Milestone:** Subsequent -- adding Nessie version-control features (AT BRANCH/TAG/COMMIT) to the existing RESTCATALOG source type in Dremio OSS

---

## Baseline: What Exists Today

### RESTCATALOG Source (Already Built)
- `RestIcebergCatalogPluginConfig` with endpoint URI, allowed namespaces, property lists
- `RestIcebergCatalogPlugin` extends `IcebergCatalogPlugin` (which implements `StoragePlugin`, NOT `VersionedPlugin`)
- `IcebergRestCatalogAccessor` wraps Iceberg `RESTCatalog` with namespace filtering, table/view caching
- Read-only browsing (SELECT) plus mutable operations behind `RESTCATALOG_PLUGIN_MUTABLE_ENABLED` flag
- No version context awareness -- all operations use whatever prefix the underlying `RESTCatalog` is configured with
- UI layout (`restcatalog-layout.json`): endpoint URI, namespace filter, catalog properties, cache options

### Native Nessie Source (Already Built -- Reference Implementation)
- `NessiePlugin` extends `DataplanePlugin` which implements `VersionedPlugin`
- Full `VersionedPlugin` interface: `resolveVersionContext()`, `listBranches()`, `listTags()`, `listReferences()`, `listChanges()`, `getDefaultBranch()`, `createBranch()`, `dropBranch()`, `mergeBranch()`, etc.
- `NessieClient` talks directly to Nessie API v2 for reference resolution
- `isVersioned = true` annotation on `NessiePluginConfig` `@SourceType`
- UI layout (`nessie-layout.json`): endpoint URL, auth type (NONE/BEARER/OAUTH2), storage provider config

### SQL Parser Infrastructure (Already Built)
- `SqlTableVersionSpec` / `TableVersionSpec` parse `AT BRANCH x`, `AT TAG x`, `AT COMMIT "hash"`, `AT SNAPSHOT 'id'`, `AT TIMESTAMP 'ts'`
- `VersionContext` model: types BRANCH, TAG, COMMIT, REF, NOT_SPECIFIED, plus *_AS_OF_TIMESTAMP variants
- `ResolvedVersionContext`: resolved version with commit hash
- `CatalogEntityKey` carries optional `TableVersionContext` alongside path components
- `VersionContextResolverImpl`: caching resolver that calls `VersionedPlugin.resolveVersionContext()` -- only works for sources implementing `VersionedPlugin`
- `UseVersionHandler`: `USE BRANCH x IN source` sets session-level `VersionContext` per source via `userSession.setSessionVersionForSource()`
- `ShowBranchesHandler`, `ShowTagsHandler`, `ShowLogsHandler`: all call `getVersionedPlugin(sourceName)` which requires `isWrapperFor(VersionedPlugin.class)`

### Nessie REST Catalog Config Endpoint
The Nessie server's `/iceberg/v1/config` endpoint returns (HIGH confidence, verified via Nessie GitHub issue #9224):

```json
{
  "defaults": {
    "prefix": "main",
    "warehouse": "s3://warehouse",
    "io-impl": "org.apache.iceberg.io.ResolvingFileIO"
  },
  "overrides": {
    "nessie.default-branch.name": "main",
    "nessie.is-nessie-catalog": "true",
    "nessie.prefix-pattern": "{ref}|{warehouse}",
    "nessie.core-base-uri": "http://host:19120/api/",
    "nessie.iceberg-base-uri": "http://host:19120/iceberg/",
    "nessie.catalog-base-uri": "http://host:19120/catalog/v1/"
  }
}
```

Key insight: Nessie uses the Iceberg REST `prefix` mechanism for branch switching. The prefix pattern `{ref}|{warehouse}` means a prefix like `main` maps to branch `main` with default warehouse, while `dev|sales` maps to branch `dev` with warehouse `sales`.

---

## Table Stakes

Features users expect when a RESTCATALOG source is marked as Nessie-aware. Missing any of these makes the feature feel broken or incomplete.

### TS-1: Auto-Detect Nessie Backend from Config Endpoint
| Aspect | Detail |
|--------|--------|
| **Why Expected** | Users should not need to manually know whether their REST catalog is Nessie-backed. The `nessie.is-nessie-catalog=true` in config response overrides is authoritative. |
| **Complexity** | Low |
| **Dependencies** | Existing `RESTCatalog` initialization path in `RestIcebergCatalogPlugin.createRestCatalog()` |
| **Implementation** | After loading the `RESTCatalog`, inspect `properties()` for `nessie.is-nessie-catalog`. If `true` and `enableNessie` config flag is `true`, activate version-aware behavior. |
| **Confidence** | HIGH -- config endpoint response verified from Nessie project source |

### TS-2: Default Branch Discovery from Server
| Aspect | Detail |
|--------|--------|
| **Why Expected** | The native Nessie source always resolves default branch from the server (`getNessieClient().getDefaultBranch()`). Users expect the RESTCATALOG to work the same way -- no hardcoded "main". |
| **Complexity** | Low |
| **Dependencies** | Config endpoint returns `nessie.default-branch.name` in overrides |
| **Implementation** | Read `nessie.default-branch.name` from `RESTCatalog.properties()`. Use this as the fallback when `VersionContext.NOT_SPECIFIED` is received. |
| **Confidence** | HIGH -- verified from config endpoint response structure |

### TS-3: AT BRANCH Syntax for SELECT Queries
| Aspect | Detail |
|--------|--------|
| **Why Expected** | This is the core value proposition. Users write `SELECT * FROM restcatalog_source.ns.table AT BRANCH dev` and get data from the `dev` branch. |
| **Complexity** | High |
| **Dependencies** | SQL parser (already built), `VersionedPlugin` interface, `CatalogEntityKey.tableVersionContext`, `VersionContextResolverImpl` |
| **Implementation** | The RESTCATALOG plugin must implement `VersionedPlugin` (or a subset). When a query arrives with `AT BRANCH dev`, the plugin must resolve this to a Nessie reference, then create/reconfigure a `RESTCatalog` instance whose prefix targets that branch. The prefix mechanism is `{ref}|{warehouse}` per Nessie convention. |
| **Key Detail** | The existing `VersionContextResolverImpl.Loader.load()` checks `isWrapperFor(VersionedPlugin.class)` -- if the plugin does not implement this interface, version resolution silently fails with "Source does not support versioning." |
| **Confidence** | HIGH -- verified from codebase analysis of `VersionContextResolverImpl`, `BaseVersionHandler`, `CatalogImpl` |

### TS-4: AT TAG Syntax for SELECT Queries
| Aspect | Detail |
|--------|--------|
| **Why Expected** | Tags are immutable snapshots in Nessie. Users use them for reproducible queries, audits, and point-in-time analysis. `SELECT * FROM source.table AT TAG v1.0` is standard. |
| **Complexity** | Medium (same mechanism as AT BRANCH, different reference type) |
| **Dependencies** | Same as TS-3 plus tag resolution via Nessie prefix |
| **Implementation** | Tag names work as prefixes in the same `{ref}|{warehouse}` pattern. The resolved reference is a tag name instead of branch name. |
| **Confidence** | HIGH |

### TS-5: AT COMMIT Syntax for SELECT Queries
| Aspect | Detail |
|--------|--------|
| **Why Expected** | Commit hashes provide exact reproducibility. `SELECT * FROM source.table AT COMMIT "abc123"` pins to a specific point in history. |
| **Complexity** | Medium |
| **Dependencies** | Same as TS-3 plus commit hash resolution. The Nessie REST prefix mechanism accepts commit hashes as refs. |
| **Implementation** | Commit hashes are valid ref values in Nessie prefixes. `VersionContext.ofCommit(hash)` already validates hex format and length <= 64. |
| **Confidence** | MEDIUM -- commit hash as prefix value needs verification against Nessie REST catalog behavior |

### TS-6: Fresh Reference Resolution (No Stale Pointers)
| Aspect | Detail |
|--------|--------|
| **Why Expected** | Each query must resolve the branch/tag to its current commit hash. Stale cached references cause users to see old data and silently get wrong results -- the worst possible failure mode. |
| **Complexity** | Medium |
| **Dependencies** | `VersionContextResolverImpl` has a `LoadingCache` with no explicit TTL. The `RESTCatalog` caching in `ExpiringCatalogCache` has configurable expiry (`RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS`). |
| **Implementation** | Reference resolution (branch name to commit hash) must NOT be cached at the query level. The `RESTCatalog` itself may be reused (connection pooling), but the prefix (which encodes the branch) must be set per-query. This may require creating per-reference `RESTCatalog` instances or leveraging a catalog pool keyed by prefix. |
| **Key Risk** | The existing `ExpiringCatalogCache` caches a single catalog instance. Branch-aware access needs multiple catalogs or a way to switch prefixes on the fly. |
| **Confidence** | HIGH -- this is a fundamental correctness requirement |

### TS-7: USE BRANCH/TAG/COMMIT Session Context
| Aspect | Detail |
|--------|--------|
| **Why Expected** | The native Nessie source supports `USE BRANCH dev IN nessie_source` to set session defaults. Users expect the same for RESTCATALOG. This eliminates repeating `AT BRANCH` on every query. |
| **Complexity** | Low-Medium |
| **Dependencies** | `UseVersionHandler` already calls `getVersionedPlugin()` and `userSession.setSessionVersionForSource()`. The plugin must implement `VersionedPlugin` for this to work. |
| **Implementation** | Once the plugin implements `VersionedPlugin.resolveVersionContext()`, `USE BRANCH` works automatically through existing `UseVersionHandler` code path. |
| **Confidence** | HIGH -- verified from `UseVersionHandler` source |

### TS-8: enableNessie Configuration Toggle
| Aspect | Detail |
|--------|--------|
| **Why Expected** | Not all REST catalogs are Nessie-backed. The toggle (default `false`) keeps backward compatibility. Existing RESTCATALOG users are unaffected. |
| **Complexity** | Low |
| **Dependencies** | `RestIcebergCatalogPluginConfig` field definition |
| **Implementation** | Add `@Tag(13) @DisplayMetadata(label = "Enable Nessie Version Control") public boolean enableNessie = false;` to `RestIcebergCatalogPluginConfig`. When `false`, the plugin behaves exactly as today (no `VersionedPlugin` wrapper). Tag 13 is currently unused in the tag range 10-19 reserved for this config class. |
| **Confidence** | HIGH |

---

## Differentiators

Features that set this implementation apart. Not expected by default, but valued by power users.

### D-1: Multi-Branch Queries (Cross-Branch JOINs)
| Aspect | Detail |
|--------|--------|
| **Value Proposition** | `SELECT * FROM src.orders AT BRANCH main JOIN src.orders AT BRANCH dev ON ...` lets users compare data across branches, detect drift, and validate transformations before merging. This is one of the most powerful Nessie use cases. |
| **Complexity** | High |
| **Dependencies** | TS-3, TS-4, TS-5. Each table reference in a query carries its own `TableVersionContext`. The planner must resolve each independently. |
| **Implementation** | The existing native Nessie source already supports this via `DataplaneTestDefines.joinTablesQueryWithAtBranchSyntax()` -- each table gets its own `CatalogEntityKey` with a different `tableVersionContext`. The RESTCATALOG version needs the ability to create/retrieve `RESTCatalog` instances for different prefixes within the same query. |
| **Key Detail** | Verified from test code: `"Select * from %s.%s AT BRANCH %s INNER JOIN %s.%s AT BRANCH %s ON %s"` -- both table references can have different branches. |
| **Confidence** | HIGH -- this is proven to work in native Nessie source |

### D-2: SHOW BRANCHES/TAGS/LOGS Commands
| Aspect | Detail |
|--------|--------|
| **Value Proposition** | `SHOW BRANCHES IN restcatalog_source` lets users discover available branches without leaving SQL. `SHOW LOGS AT BRANCH dev IN source` shows commit history. |
| **Complexity** | Medium |
| **Dependencies** | `VersionedPlugin.listBranches()`, `listTags()`, `listChanges()`. These are mandatory methods on the interface, but their implementation requires a Nessie API client -- NOT the Iceberg REST catalog API. |
| **Implementation** | The Nessie REST catalog config endpoint provides `nessie.core-base-uri` (e.g., `http://host:19120/api/`). This URI can be used to instantiate a `NessieApiV2` client for branch/tag listing. Alternatively, the `nessie.iceberg-base-uri` can provide the same info through a catalog-level API. |
| **Key Design Decision** | This requires either: (a) embedding a Nessie API v2 client in the RESTCATALOG plugin, or (b) implementing these operations via the Iceberg REST catalog API if Nessie extends it. Option (a) is more reliable because the Nessie core API has a well-defined contract for these operations. |
| **Confidence** | MEDIUM -- Nessie core API URI availability confirmed, but implementation approach needs validation |

### D-3: Namespace Browsing Per-Branch
| Aspect | Detail |
|--------|--------|
| **Value Proposition** | When browsing the RESTCATALOG source in the UI, users should see namespaces/tables for the selected branch. Different branches may have different tables/schemas. |
| **Complexity** | Medium |
| **Dependencies** | TS-3, TS-7. The `IcebergCatalogPlugin.listDatasetHandles()` and namespace listing must be branch-aware. |
| **Implementation** | Dataset listing goes through `CatalogAccessor.listNamespaces()` and `CatalogAccessor.listTableIdentifiers()`. These call the Iceberg REST catalog which is prefix-bound. The listing must use the session-default branch prefix or allow UI-driven branch selection. |
| **Confidence** | MEDIUM -- depends on how the UI passes version context for browsing vs. querying |

### D-4: Commit Hash Display in Query Profiles
| Aspect | Detail |
|--------|--------|
| **Value Proposition** | After a versioned query executes, the query profile should show which commit hash was resolved, enabling audit trails and reproducibility. |
| **Complexity** | Low |
| **Dependencies** | Already implemented in native Nessie source. `ITDatasetVersionContext` tests verify that `queriedDatasets.get(0).getVersionContext()` returns the serialized `TableVersionContext` with resolved branch. |
| **Implementation** | This works automatically once `VersionedPlugin.resolveVersionContext()` returns a `ResolvedVersionContext` with a commit hash. The planner propagates this through to the query profile. |
| **Confidence** | HIGH |

---

## Anti-Features

Features to explicitly NOT build in this milestone. Each has a clear reason for exclusion.

### AF-1: DML Operations on Branches (INSERT/UPDATE/DELETE/MERGE AT BRANCH)
| Aspect | Detail |
|--------|--------|
| **Why Avoid** | The milestone scope is read-only SELECT. DML on branches requires transactional commit semantics, conflict resolution, and the full Nessie commit API. The complexity increase is disproportionate to the value for this milestone. |
| **What to Do Instead** | Focus on read-only access. DML can be added in a future milestone once the read path is proven. |

### AF-2: Branch/Tag Management (CREATE/DROP/MERGE BRANCH)
| Aspect | Detail |
|--------|--------|
| **Why Avoid** | The `VersionedPlugin` interface includes `createBranch()`, `dropBranch()`, `mergeBranch()`, `createTag()`, `dropTag()`, `assignBranch()`, `assignTag()`. Implementing these requires the Nessie core API v2 client and goes beyond read-only access. |
| **What to Do Instead** | Implement `VersionedPlugin` but throw `UnsupportedOperationException` for mutation methods. The `SHOW` commands and `resolveVersionContext()` are the read-only subset. |

### AF-3: Views on Branches
| Aspect | Detail |
|--------|--------|
| **Why Avoid** | Per project context: "No views on branches for now." Nessie views have complex ownership, SQL dialect, and metadata format considerations. The existing RESTCATALOG view support (`createOrUpdateView`, `dropView`) operates on the default prefix only. |
| **What to Do Instead** | Versioned view support can be added after table access is stable. The existing view code path remains unversioned even when `enableNessie` is true. |

### AF-4: AT SNAPSHOT/AT TIMESTAMP (Iceberg Time Travel) Conflation
| Aspect | Detail |
|--------|--------|
| **Why Avoid** | `AT SNAPSHOT` and `AT TIMESTAMP` are Iceberg-level time travel (snapshot IDs within a single table's history). `AT BRANCH/TAG/COMMIT` is Nessie-level versioning (catalog-wide). These are orthogonal concepts. Conflating them creates user confusion and implementation complexity. |
| **What to Do Instead** | AT SNAPSHOT/TIMESTAMP already work for RESTCATALOG via `TimeTravelOption` in the existing code path. Keep them separate. A combined syntax like `SELECT * FROM t AT BRANCH dev AT SNAPSHOT '123'` is not in scope. |

### AF-5: Automatic Branch Sync / Push / Pull
| Aspect | Detail |
|--------|--------|
| **Why Avoid** | Nessie supports multi-tenant repositories with conflict resolution. Adding automatic sync mechanisms introduces operational complexity and potential data loss scenarios. |
| **What to Do Instead** | The RESTCATALOG reads Nessie state as-is. Branch management happens through external tools (Nessie CLI, other clients). |

### AF-6: isVersioned=true on RESTCATALOG Source Type
| Aspect | Detail |
|--------|--------|
| **Why Avoid** | The `@SourceType(isVersioned = true)` annotation on `NessiePluginConfig` is used by `DACDaemonModule.getVersionedSourceTypes()` to globally identify versioned sources. Setting this on RESTCATALOG would make ALL RESTCATALOG sources appear versioned, even non-Nessie ones. |
| **What to Do Instead** | Keep `@SourceType(isVersioned = false)` on `RestIcebergCatalogPluginConfig`. Instead, make the plugin conditionally expose the `VersionedPlugin` wrapper based on the runtime `enableNessie` flag. The `isWrapperFor(VersionedPlugin.class)` check is instance-level, not type-level. |

---

## Feature Dependencies

```
TS-8 (enableNessie toggle)
  |
  v
TS-1 (auto-detect Nessie from config endpoint)
  |
  v
TS-2 (default branch discovery)
  |
  +---> TS-3 (AT BRANCH) --+---> D-1 (multi-branch JOINs)
  |                         |
  +---> TS-4 (AT TAG) ------+---> D-4 (commit hash in profiles)
  |                         |
  +---> TS-5 (AT COMMIT) ---+
  |
  +---> TS-6 (fresh resolution)
  |
  +---> TS-7 (USE BRANCH session) ---> D-3 (namespace browsing per-branch)
  |
  +---> D-2 (SHOW BRANCHES/TAGS/LOGS) [requires Nessie core API client]
```

### Critical Path
The critical dependency chain is:

1. **TS-8** (enableNessie) must come first -- it gates all other features
2. **TS-1** (auto-detect) + **TS-2** (default branch) must come next -- they establish the Nessie connection
3. **TS-3** (AT BRANCH) is the core feature -- implementing `VersionedPlugin` on the RESTCATALOG plugin
4. TS-4, TS-5, TS-6, TS-7 flow naturally from TS-3's implementation
5. **D-1** (multi-branch JOINs) works once TS-3 supports per-table-reference prefix switching
6. **D-2** (SHOW commands) is an independent track requiring a Nessie API client

### Key Implementation Dependency: VersionedPlugin Interface
The `VersionedPlugin` interface has 25+ methods. The RESTCATALOG plugin needs to implement only a subset for read-only access:

**Must implement (read path):**
- `resolveVersionContext(VersionContext)` -- core resolution logic
- `getDefaultBranch()` -- fallback for NOT_SPECIFIED
- `listBranches()`, `listTags()`, `listReferences()` -- for SHOW commands
- `listChanges(VersionContext)` -- for SHOW LOGS
- `commitExists(String)` -- for AT COMMIT validation
- `getType(catalogKey, version)` -- for entity type resolution
- `getContentId(catalogKey, version)` -- for content identification
- `listEntries(...)`, `listEntriesPage(...)` -- for namespace browsing

**Must stub (throw UnsupportedOperationException):**
- `createBranch()`, `dropBranch()`, `mergeBranch()`, `assignBranch()`
- `createTag()`, `dropTag()`, `assignTag()`
- All mutation methods inherited from `FunctionManagingPlugin`, `SupportsMutatingFolders`

---

## MVP Recommendation

### Phase 1: Core Version-Aware Access
Prioritize in order:
1. **TS-8** -- enableNessie toggle in config (trivial, gates everything)
2. **TS-1** -- Auto-detect Nessie from config endpoint (low effort, high value)
3. **TS-2** -- Default branch discovery (low effort, required by all downstream)
4. **TS-3 + TS-4 + TS-5** -- AT BRANCH/TAG/COMMIT for SELECT (the core feature, implement together)
5. **TS-6** -- Fresh reference resolution (correctness, must ship with TS-3)
6. **TS-7** -- USE BRANCH session context (comes free once VersionedPlugin is implemented)

### Phase 2: Polish and Discoverability
Defer to after Phase 1 is validated:
1. **D-1** -- Multi-branch JOINs (verify works, may need catalog pool)
2. **D-2** -- SHOW BRANCHES/TAGS/LOGS (requires Nessie API v2 client integration)
3. **D-3** -- Namespace browsing per-branch (UI integration)
4. **D-4** -- Commit hash in query profiles (likely works automatically)

### What NOT to Build
- AF-1 through AF-6 are explicitly out of scope. Stub mutation methods to fail clearly.

---

## Key Architecture Decision: VersionedPlugin Implementation Strategy

The central question is how `RestIcebergCatalogPlugin` (which extends `IcebergCatalogPlugin`) can also implement `VersionedPlugin`.

**Option A: Direct implementation** -- `RestIcebergCatalogPlugin implements VersionedPlugin` directly. This is cleanest but means the REST catalog plugin class becomes much larger.

**Option B: Wrapper/delegate** -- Create a `NessieAwareRestCatalogPlugin` that wraps `RestIcebergCatalogPlugin` and delegates `VersionedPlugin` methods to a Nessie client. The `isWrapperFor(VersionedPlugin.class)` / `unwrap(VersionedPlugin.class)` pattern already supports this.

**Option C: Conditional wrapper** -- Override `isWrapperFor()` and `unwrap()` on `RestIcebergCatalogPlugin` to conditionally expose a `VersionedPlugin` implementation only when `enableNessie` is true. This is the lightest touch and avoids creating a new plugin class.

**Recommendation: Option C** because:
- Keeps backward compatibility (non-Nessie RESTCATALOG sources unchanged)
- Works with the existing `VersionContextResolverImpl` and `BaseVersionHandler` code paths
- The `Wrapper` interface in Dremio already supports this pattern
- Minimizes class hierarchy changes

---

## Implementation Complexity Summary

| Feature | Complexity | Effort | Risk |
|---------|-----------|--------|------|
| TS-8 enableNessie toggle | Low | 1-2 hours | None |
| TS-1 Auto-detect Nessie | Low | 2-4 hours | None |
| TS-2 Default branch | Low | 2-4 hours | None |
| TS-3 AT BRANCH | High | 2-4 days | Prefix/catalog management |
| TS-4 AT TAG | Medium | 4-8 hours | Same as TS-3 |
| TS-5 AT COMMIT | Medium | 4-8 hours | Commit hash as prefix |
| TS-6 Fresh resolution | Medium | 1-2 days | Cache invalidation |
| TS-7 USE BRANCH | Low-Medium | 4-8 hours | Comes with VersionedPlugin |
| D-1 Multi-branch JOINs | High | 1-2 days | Catalog instance pooling |
| D-2 SHOW commands | Medium | 1-2 days | Nessie API v2 client setup |
| D-3 Per-branch browsing | Medium | 1-2 days | UI integration |
| D-4 Commit hash in profiles | Low | 2-4 hours | Likely automatic |

**Total estimated effort: 2-3 weeks** for all table stakes + differentiators.

---

## Sources

- Codebase analysis: `RestIcebergCatalogPlugin.java`, `RestIcebergCatalogPluginConfig.java`, `IcebergCatalogPlugin.java`, `DataplanePlugin.java`, `NessiePlugin.java`, `NessiePluginConfig.java`, `VersionedPlugin.java`, `VersionContext.java`, `VersionContextResolverImpl.java`, `UseVersionHandler.java`, `BaseVersionHandler.java`, `ShowBranchesHandler.java`, `CatalogImpl.java`, `DataplaneTestDefines.java`, `ITDatasetVersionContext.java`
- [Nessie Iceberg REST Configuration Guide](https://projectnessie.org/guides/iceberg-rest/)
- [Nessie GitHub Issue #9224 - Config endpoint response structure](https://github.com/projectnessie/nessie/issues/9224)
- [Nessie Server Configuration](https://projectnessie.org/nessie-latest/configuration/)
