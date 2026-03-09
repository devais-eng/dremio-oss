# Domain Pitfalls: Nessie Branch-Aware REST Catalog

**Domain:** Adding Nessie version-control features (AT BRANCH/TAG/COMMIT) to the existing RESTCATALOG source type in Dremio OSS
**Researched:** 2026-03-09
**Supersedes:** RBAC Pitfalls v2.0 (2026-02-20) -- previous pitfalls remain valid but are not repeated here
**Confidence:** HIGH (based on direct codebase analysis of the existing REST catalog plugin, Nessie client, plan cache, RBAC enforcement, and official Nessie REST Catalog documentation)

---

## Critical Pitfalls

Mistakes that cause incorrect query results, data corruption, or require rewrites.

---

### P1 -- Table Cache Poisoning Across Branches

**What goes wrong:**
The existing `AbstractRestCatalogAccessor` uses a `LoadingCache<CatalogAccessorTableCacheKey, Table>` where `CatalogAccessorTableCacheKey` is keyed only by `(userId, TableIdentifier)`. The `TableIdentifier` in Iceberg is `Namespace + tableName` -- it does NOT include any branch or reference information. When branch awareness is added, a query like `SELECT * FROM source.ns.table AT BRANCH dev` loads table metadata from the `dev` branch and caches it under `CatalogAccessorTableCacheKey("user1", TableIdentifier.of("ns","table"))`. A subsequent query `SELECT * FROM source.ns.table AT BRANCH main` hits the same cache key and returns the `dev` branch's table metadata. The user silently gets wrong data.

**Why it happens:**
The table cache was designed for a non-versioned REST catalog where a table path is globally unique. With branch awareness, the same table path resolves to different table metadata on different branches. The cache key must include the branch/reference to be correct.

**Consequences:**
- Silent wrong results -- the worst kind of bug. Users get data from the wrong branch with no error.
- The TTL-based expiration (default 3 seconds) limits the window but does NOT eliminate it. Two queries in rapid succession (common in dashboards, JOINs with CTEs) will reliably hit the stale entry.
- The `viewCache` has the identical problem for Iceberg views.

**Prevention:**
1. Add a `resolvedReference` field (branch name + commit hash, or just commit hash for immutability) to `CatalogAccessorTableCacheKey`. The cache key becomes `(userId, tableIdentifier, resolvedReference)`.
2. When `enableNessie` is `false`, the reference field is null/empty, preserving existing behavior.
3. For the `ExpiringCatalogCache` that caches the `RESTCatalog` instance itself: if the prefix changes between calls (different branches), the cached catalog may have been initialized with a different prefix. Either create per-prefix catalog instances or invalidate on prefix change.
4. Write a test: query table T on branch A, immediately query T on branch B, assert different metadata/snapshot IDs.

**Detection:**
- Integration test that queries the same table path on two branches within the cache TTL and compares snapshot IDs.
- Log the cache key at DEBUG level on every cache hit/miss. If you see a hit when you expected a miss, the key is wrong.

**Phase:** Phase 1 (Core Implementation) -- must be addressed before any branch-aware query can be trusted.

---

### P2 -- Plan Cache Serving Stale Plans Across Branch Contexts

**What goes wrong:**
The plan cache key (in `PlanCacheUtils.generateCacheKey()`) is built from: SQL text, RelNode toString, workload type, default schema, non-default options, executor topology, and optionally the username. It does NOT include any branch/version context. Two queries with identical SQL text but different AT BRANCH clauses generate the same cache key. The plan cache can return a physical plan that reads from the wrong branch's metadata location.

More subtly: the current code has a check `checkForVersionedTable()` that excludes queries touching `VersionedPlugin` sources from the plan cache. But the RESTCATALOG source does NOT implement `VersionedPlugin` -- it implements `SupportsIcebergRootPointer` + `SupportsIcebergMutablePlugin`. So the versioned-table exclusion does NOT fire for RESTCATALOG sources. Plans containing branch-specific metadata locations will be cached and reused incorrectly.

**Why it happens:**
The plan cache was designed before branch-aware catalogs existed in the REST catalog path. The `VersionedPlugin` guard only protects the native Nessie source (which implements `VersionedPlugin`), not the REST catalog source that is being extended with branch awareness.

**Consequences:**
- User queries AT BRANCH `main` and gets a plan that references metadata files from branch `dev`. This can cause: wrong results, FileNotFoundExceptions (if the metadata file only exists on one branch), or silent data corruption if the file exists but has different content.
- The bug is intermittent and depends on cache timing, making it extremely hard to diagnose.

**Prevention:**
Three options, in order of preference:
1. **Best:** Add the AT BRANCH/TAG/COMMIT clause to the plan cache key hash. In `generateCacheKey()`, include the resolved version context string (e.g., `"BRANCH:dev:abc123hash"`) in the hasher. This allows caching of branch-specific plans correctly.
2. **Safe but conservative:** Exclude RESTCATALOG sources with `enableNessie=true` from the plan cache entirely, analogous to the `checkForVersionedTable()` exclusion. This is simpler but sacrifices caching performance.
3. **Simplest for Phase 1:** When the legacy plan cache is used (`QUERY_PLAN_USE_LEGACY_CACHE`), add a check: if ANY table in the plan comes from a RESTCATALOG source with Nessie enabled, reject the plan from cache. This mirrors what `checkForVersionedTable()` does for native Nessie sources.

**Detection:**
- Test: run `SELECT * FROM src.t AT BRANCH main`, run `SELECT * FROM src.t AT BRANCH dev`, verify the second query does NOT return a plan cache hit.
- Monitor `NOT_PUT_VERSIONED_TABLE` metric -- if it is zero for branch-aware queries, the exclusion is not working.

**Phase:** Phase 1 (Core Implementation) -- must be addressed alongside P1.

---

### P3 -- Default Branch Resolution Race: Stale Default Between Planning and Execution

**What goes wrong:**
The user requirement states "without AT BRANCH explicit, always default to main branch (defined by server: always)." The naive implementation resolves the default branch at query planning time by calling the Nessie server's config endpoint. Between planning and execution (which can be separated by seconds or longer for queued queries), the server's default branch can change. This creates a window where:
1. Planning resolves default to `main` at commit `abc123`.
2. Admin changes Nessie's default branch to `production`.
3. Execution runs with `main` baked into the plan, but the user expected `production`.

A more dangerous variant: between `resolveVersionContext()` and the actual table load via REST prefix, the branch pointer moves (someone commits to `main`). The prefix resolves to the current HEAD of `main`, not the HEAD at planning time. This means a query that started planning against snapshot X could execute against snapshot Y.

**Why it happens:**
Branch pointers are mutable by nature. Unlike the native Nessie source which resolves `VersionContext -> ResolvedVersionContext` (pinning to a specific commit hash), the REST catalog prefix mechanism sends the branch NAME in the URL, not a pinned commit hash. The Nessie REST Catalog server resolves the branch name to its current HEAD at the time of each REST call.

**Consequences:**
- Non-repeatable reads within a single query execution. A JOIN between two tables on the same branch could see different snapshots if the branch moved between the two table-load REST calls.
- Audit logs show the query ran on "main" but the actual data corresponds to a different commit than what was expected.

**Prevention:**
1. **Resolve early, pin the commit hash:** When the user says AT BRANCH `main` (or implicitly uses the default), call the Nessie API to resolve `main -> commit_hash_abc123`. Use that resolved hash in the REST prefix for ALL table loads in the query. Nessie's REST prefix supports `commitHash|warehouse` syntax for this purpose.
2. **Never cache the default branch name across queries.** Each query must ask the server "what is the default branch right now?" Fresh resolution per query.
3. If using branch name in prefix (not commit hash), at minimum ensure all table loads for a single query use the same resolved reference. Store the resolved reference in the query context / session state at planning time and reuse it.
4. Document that the REST catalog prefix format for Nessie is `branchName|warehouse` or `commitHash|warehouse` (pipe separator is mandatory for warehouse specification).

**Detection:**
- Test: start a query, inject a commit to the branch mid-query, verify the query sees a consistent snapshot.
- Log the resolved commit hash alongside every REST call. If two calls in the same query show different hashes for the same branch, this pitfall has struck.

**Phase:** Phase 2 (Reference Resolution) -- must be designed in Phase 1 but the consistency guarantee is Phase 2 work.

---

### P4 -- RESTCatalog Client Lifecycle: Connection Pool Exhaustion from Per-Branch Catalogs

**What goes wrong:**
The existing `ExpiringCatalogCache` caches a single `RESTCatalog` instance and reuses it. It is constructed with a specific URI (including any prefix). If branch awareness requires different prefixes per branch, the naive implementation creates a new `RESTCatalog` instance per branch. Each `RESTCatalog` instance internally creates its own HTTP client with connection pool, session management, and OAuth token handling. With N concurrent users querying M branches, this creates N*M `RESTCatalog` instances, each with its own connection pool.

The `ExpiringCatalogCache` has a 30-minute default expiry (`RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS = 1800`). During that window, catalog instances accumulate. Each instance holds HTTP connections, thread pool threads, and memory.

**Why it happens:**
The `ExpiringCatalogCache` was designed for a single catalog endpoint. It does not understand that the same Nessie server can be accessed via different prefixes for different branches. The `RESTCatalog` implementation in Iceberg Java creates per-instance HTTP clients.

**Consequences:**
- Thread pool exhaustion: each `RESTCatalog` creates a thread pool for async operations. 100 branches * connection pools = hundreds of idle threads.
- Connection pool exhaustion: each pool maintains minimum idle connections. The Nessie server sees massive connection counts.
- Memory pressure: `RESTCatalog` objects are not lightweight -- they cache session config, OAuth tokens, and metadata.
- Slow query startup: new catalog initialization involves an HTTP config exchange handshake.

**Prevention:**
1. **Share the HTTP client/session across branches.** The Iceberg `RESTCatalog` supports `CatalogProperties.URI` + prefix. Investigate whether a single `RESTCatalog` can change its prefix per-request, or whether the prefix is fixed at initialization. If fixed, use a pool of catalogs with LRU eviction.
2. **Bounded cache of per-branch catalogs.** Use a `LoadingCache<String, RESTCatalog>` keyed by branch/reference with a max size (e.g., 10-20 entries) and TTL eviction. Close evicted catalogs properly via their `Closeable.close()` method.
3. **Consider rewriting the REST calls directly** rather than going through `RESTCatalog` instances per branch. The Nessie REST Catalog endpoint structure is simple: `{base}/iceberg/{prefix}/v1/namespaces/{ns}/tables/{table}`. A single HTTP client can make calls with different prefix path segments.
4. If Nessie supports a `ref` query parameter instead of prefix-based routing for the Iceberg REST API, that would allow a single `RESTCatalog` instance to serve all branches. Verify with Nessie documentation.

**Detection:**
- Monitor thread count and HTTP connection count per Dremio coordinator. A spike after enabling Nessie features indicates catalog proliferation.
- Add metrics for catalog cache size and eviction rate.

**Phase:** Phase 1 (Core Implementation) -- architecture decision that must be made upfront.

---

### P5 -- Prefix Encoding: Branch Names with Special Characters Break URL Routing

**What goes wrong:**
Nessie branch names allow characters that are problematic in URL path segments: `/`, `@`, `#`, spaces, Unicode characters. The Nessie REST Catalog prefix format is `{branch}|{warehouse}` embedded in the URL path. If a branch name contains `/` (e.g., `feature/auth-fix`), the URL path becomes ambiguous: `/iceberg/feature/auth-fix|warehouse/v1/namespaces/...` -- the server interprets `feature` as one path segment and `auth-fix|warehouse` as another.

The pipe character `|` in the prefix is the separator between branch and warehouse. If the branch name itself contains `|`, parsing breaks.

**Why it happens:**
Nessie allows flexible branch names following Git conventions (`feature/xyz`, `bugfix/DX-12345`). URL path encoding rules require special characters to be percent-encoded, but the Nessie Iceberg REST server may or may not expect percent-encoded branch names in the prefix.

**Consequences:**
- HTTP 404 errors for branches with `/` in their names.
- HTTP 400 "Invalid request path" errors.
- Silently connecting to the wrong branch if the prefix is parsed incorrectly (e.g., `feature` instead of `feature/auth-fix`).

**Prevention:**
1. **URL-encode branch names** in the prefix path segment. `feature/auth-fix` becomes `feature%2Fauth-fix`. Test that the Nessie server accepts percent-encoded branch names.
2. **Validate branch names at parse time.** When the user specifies `AT BRANCH "feature/auth-fix"`, validate that the branch name can be safely encoded in a URL. If not, produce a clear error message.
3. **Test with adversarial branch names:** `feature/fix`, `release-1.0`, `branch@user`, `branch#123`, `my branch`, `branch|name`, empty string, very long names (255+ chars).
4. Consider whether to disallow certain characters at the Dremio SQL parsing level and document the limitations.

**Detection:**
- Unit test with a matrix of branch name formats against the prefix builder function.
- Integration test that creates a Nessie branch with `/` in its name and queries it via the REST catalog.

**Phase:** Phase 2 (Reference Resolution) -- but the validation function should be written in Phase 1.

---

## Moderate Pitfalls

---

### P6 -- Metadata Staleness: Cached Table Metadata for a Branch That Has Moved

**What goes wrong:**
The `RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS` defaults to 3 seconds. The `ExpiringCatalogCache` for the catalog instance itself defaults to 1800 seconds (30 minutes). During the 30-minute catalog cache window, the catalog's internal session state is stale. Between the 3-second table cache windows, a branch can move forward (new commits).

The deeper issue: `isIcebergMetadataValid()` in `IcebergCatalogPlugin` compares `metadataFileLocation` from the catalog against the stored `DatasetConfig`. But with branches, the same table path on different branches has different metadata file locations. The validation check does not account for which branch the stored metadata came from.

**Why it happens:**
The metadata validation was designed for a single-version catalog. The `DatasetConfig` stored in the namespace KV store does not record which branch the metadata was fetched from.

**Consequences:**
- After a commit to a branch, queries continue to see old data until the table cache expires (3 seconds) AND the namespace metadata refresh runs.
- The `SourceMetadataManager` background refresh updates `DatasetConfig` in the KV store. If it refreshes from the default branch, datasets that were previously accessed on a non-default branch get their metadata overwritten with default branch metadata.

**Prevention:**
1. When `enableNessie` is true, consider disabling the namespace-level metadata caching for this source, or making the cache branch-aware by including the branch in the dataset key.
2. At minimum, do NOT store branch-specific metadata in the shared `DatasetConfig` KV store. Branch-aware queries should always go directly to the REST catalog, bypassing the KV store metadata.
3. The 3-second table cache TTL is already aggressive enough for most use cases. Document that branch-aware queries have eventual consistency within this window.

**Phase:** Phase 3 (Integration with Existing Metadata Pipeline).

---

### P7 -- Multi-Branch JOIN: Table Not Found on One Branch

**What goes wrong:**
A query like:
```sql
SELECT a.*, b.* FROM source.ns.tableA AT BRANCH main
JOIN source.ns.tableB AT BRANCH dev
```
If `tableB` does not exist on the `dev` branch, the REST catalog returns HTTP 404 / `NoSuchTableException`. The current error handling in `AbstractRestCatalogAccessor.getDatasetHandle()` returns `Optional.empty()` and logs a warning. The query planner then treats this as "table not found" with a generic validation error that does not mention the branch.

**Why it happens:**
The error path does not propagate branch context information. The `NoSuchTableException` from Iceberg does not include which branch was queried.

**Consequences:**
- Confusing error messages: "Table source.ns.tableB not found" when the table exists on `main` but not on `dev`. The user has no idea why it is not found.
- If the user forgets the AT BRANCH clause on one side of the JOIN, the two sides resolve to different branches (one explicit, one default). This is semantically valid but almost always a user mistake.

**Prevention:**
1. Wrap `NoSuchTableException` with branch context: "Table ns.tableB not found on branch dev."
2. When a query references multiple AT BRANCH clauses for the same source, log an INFO-level message noting the multi-branch access pattern. This helps with debugging.
3. Consider whether queries with mixed branches on the same source should emit a warning (not an error). This is a UX decision.

**Phase:** Phase 2 (Reference Resolution) -- error message improvement.

---

### P8 -- RBAC Does Not Gate Branch Access

**What goes wrong:**
The existing RBAC enforcement in `CatalogImpl.validatePrivilege()` checks privilege based on `NamespaceKey` (the dataset path). It does not include any branch or version context in the privilege check. A user with `SELECT` on `source.ns.table` can query that table on ANY branch, including branches that contain sensitive data (e.g., a `pii-unmasked` branch with raw PII data, while `main` has masked data).

The `hasAccessPermission()` method in `IcebergCatalogPlugin` currently returns `true` unconditionally (with a `// TODO: implement RBAC` comment).

**Why it happens:**
RBAC was designed for static namespace paths. Branches add a new dimension to the access control model that the current system does not represent.

**Consequences:**
- A user with access to a table on `main` can read unmasked PII data from a `development` branch if the same table exists with different data policies.
- No audit trail of which branch a user accessed -- the RBAC logs only record the table path.

**Prevention:**
1. **Phase 1: Accept the limitation and document it.** Branch-level RBAC is a feature unto itself. The initial implementation should document that branch access is not gated -- any user who can query the source can query any branch.
2. **Future: Branch-level privileges.** Add a new privilege dimension: `GRANT SELECT ON source AT BRANCH main TO user`. This requires extending the RBAC schema, the grant SQL syntax, and the privilege check in `validatePrivilege()`.
3. **Interim mitigation:** If security is a concern, configure the Nessie server itself with access controls (Nessie supports authorization rules). This pushes the branch-level access check to the catalog server rather than Dremio.

**Phase:** Document in Phase 1. Implement branch-level RBAC in a future milestone (not this one).

---

### P9 -- Definer Rights + Branch Context: Which Branch Does the VDS Resolve?

**What goes wrong:**
A VDS (virtual dataset/view) is created with SQL that references a branch:
```sql
CREATE VDS my_view AS SELECT * FROM source.ns.table AT BRANCH main
```
When another user queries `my_view`, the `ViewExpander` expands it under the view owner's identity (definer rights). But the branch resolution context is ambiguous:
1. Should it use the branch that was current when the VDS was created (`main` at commit `abc123`)?
2. Should it use the branch name `main` resolved at query time (which may point to a different commit)?
3. Should it inherit the querying user's session branch context?

The `ViewExpander` currently passes the `viewTable.getVersionContext()` through to the inner resolution. For native Nessie sources, this is handled by `VersionedDatasetAdapter`. For the REST catalog with Nessie enabled, there is no equivalent mechanism -- the branch was in the SQL text, not in the dataset metadata.

**Why it happens:**
The VDS stores the SQL string, not the resolved branch. The branch is embedded in the SQL text as `AT BRANCH main`. When the SQL is re-parsed during view expansion, the branch name is re-resolved against the current server state.

**Consequences:**
- Non-deterministic view behavior: the same VDS returns different results depending on when it is queried (because `main` moves).
- If the VDS was created to capture a "point in time" view via `AT COMMIT abc123`, the commit hash is in the SQL text and is stable. But if it uses `AT BRANCH`, it is always live.
- The definer's identity resolves the branch, but the definer may not have intended the branch to float forward.

**Prevention:**
1. **Accept this behavior as by-design for AT BRANCH** (it is consistent with how the native Nessie source works). A VDS with `AT BRANCH main` always resolves to the current HEAD of main.
2. **Document it clearly:** Users who want pinned views should use `AT COMMIT {hash}` or `AT TAG {tag}` instead of `AT BRANCH`.
3. For definer rights, the branch resolution should use the view owner's session context, not the querying user's. This is already the case because `ViewExpander` switches identity before re-parsing the SQL.
4. Write a test: create a VDS with `AT BRANCH main`, commit to `main`, query the VDS, verify it sees the new data.

**Phase:** Phase 3 (VDS/View Integration).

---

### P10 -- Error Handling: Nessie 404 vs REST Catalog 404 Confusion

**What goes wrong:**
When a branch does not exist, Nessie's REST Catalog endpoint returns HTTP 404 with a body indicating `NessieReferenceNotFoundException`. When a table does not exist on a valid branch, Nessie also returns HTTP 404 with `NoSuchTableException`. The Iceberg `RESTCatalog` client maps both to generic Iceberg exceptions, losing the distinction between "branch not found" and "table not found on this branch."

The existing error handling in `AbstractRestCatalogAccessor.datasetExists()` catches `BadRequestException` with "Invalid request path" but does not handle branch-not-found specifically.

**Why it happens:**
The Iceberg REST Catalog spec defines error codes generically. Nessie layers its own error semantics on top. The `RESTCatalog` Java client does not expose Nessie-specific error codes.

**Consequences:**
- User gets "Table not found" when the actual problem is "Branch not found."
- User gets "Invalid request path" for a branch name with special characters but thinks the table path is wrong.
- Retry logic that is appropriate for "table not found" (e.g., metadata refresh) fires for "branch not found" (wasted work).

**Prevention:**
1. When `enableNessie` is true, intercept HTTP 404 responses and inspect the error body for Nessie-specific error codes (`REFERENCE_NOT_FOUND` vs `CONTENT_NOT_FOUND`).
2. Translate to specific Dremio exceptions: `ReferenceNotFoundException` for branch/tag/commit not found, `NoSuchTableException` for table not found on valid branch.
3. Surface the branch/reference name in the error message: "Branch 'dev' not found in source 'my_nessie_catalog'" or "Table 'ns.table' not found on branch 'dev'."
4. For the special case of prefix format errors (malformed branch name in URL), catch the `BadRequestException` and produce a message suggesting the branch name may contain unsupported characters.

**Phase:** Phase 2 (Reference Resolution).

---

### P11 -- Metadata Refresh Background Task Conflicts with Branch-Aware Access

**What goes wrong:**
The `SourceMetadataManager` runs periodic background metadata refreshes. For RESTCATALOG sources, it calls `listDatasetHandles()` and `getDatasetHandle()` on the plugin to discover and refresh table metadata. These calls go through the `CatalogAccessor` which uses the catalog's configured prefix. If the prefix is the default branch, the background refresh only discovers and caches metadata for the default branch.

When a user queries a non-default branch, the metadata may not exist in the namespace KV store. The `SourceMetadataManager.handleDatasetRefresh()` path tries to reconcile with stored metadata and may either:
1. Fail to find the dataset and return "not found."
2. Find the dataset from a previous default-branch refresh and return stale/wrong metadata.

**Why it happens:**
The background refresh is not branch-aware. It runs against one branch (the default) and populates the KV store with that branch's metadata.

**Consequences:**
- Tables that only exist on non-default branches are never discovered by the background refresh.
- Tables that exist on multiple branches have their KV-store metadata overwritten with whatever the last refresh branch saw.

**Prevention:**
1. **Do not use the KV-store metadata path for branch-aware queries.** When `enableNessie` is true and an explicit branch is specified, bypass the namespace KV store entirely and go directly to the REST catalog with the appropriate prefix.
2. The background refresh should only run against the default branch (or not at all for Nessie-enabled sources). Non-default branches should be purely on-demand.
3. Consider disabling the background metadata refresh entirely for Nessie-enabled RESTCATALOG sources, since the REST catalog itself is the source of truth and the KV store is just a cache.

**Phase:** Phase 3 (Integration with Existing Metadata Pipeline).

---

## Minor Pitfalls

---

### P12 -- Session-Level Branch Context Leaking Between Queries

**What goes wrong:**
If the implementation uses session-level state to track the "current branch" (similar to how the native Nessie source supports `USE BRANCH main`), forgetting to clear or scope this state can cause one query to inherit the branch from a previous query in the same session.

**Prevention:**
1. For the RESTCATALOG source with Nessie, the branch should be specified per-query via `AT BRANCH/TAG/COMMIT` syntax. Do not introduce session-level branch state in Phase 1.
2. If session-level branch defaults are added later, ensure they are scoped to the source name (not global) and reset on session close.

**Phase:** Phase 1 -- design decision.

---

### P13 -- Thread Safety of RESTCatalog Prefix Switching

**What goes wrong:**
If a single `RESTCatalog` instance is shared and the prefix is changed per-request (rather than creating per-prefix instances), concurrent queries on different branches can see each other's prefix. The `RESTCatalog` in Iceberg stores the prefix as instance state, not per-request state.

**Prevention:**
1. Verify that the Iceberg `RESTCatalog` is thread-safe with respect to prefix. Examine the source code -- `RESTCatalog.initialize()` sets the prefix once.
2. Do NOT share a single `RESTCatalog` instance across branches. Use either per-branch instances (with connection pooling concerns per P4) or construct the REST URL directly without using `RESTCatalog` prefix support.

**Phase:** Phase 1 -- architecture decision.

---

### P14 -- AT COMMIT with a Hash Not on the Default Branch

**What goes wrong:**
A user specifies `AT COMMIT abc123` where `abc123` is a commit that exists on branch `dev` but is not an ancestor of `main`. The Nessie REST Catalog endpoint may or may not support commit-hash-based access independent of a branch context. If the prefix mechanism requires a branch name, there may be no way to express "load this table at this specific commit hash without naming a branch."

**Prevention:**
1. Verify with Nessie documentation whether the REST Catalog prefix supports commit hashes directly (e.g., `abc123|warehouse` as the prefix).
2. If commit-hash-based prefix is not supported, resolve the commit hash to a branch that contains it (or use the Nessie API directly to find which branches contain the commit).
3. If the feature is not supportable via REST prefix, document the limitation: "AT COMMIT is only supported for commits on the currently configured branch" or defer commit-hash support.

**Phase:** Phase 2 (Reference Resolution) -- requires API capability verification.

---

### P15 -- Namespace Listing Ignores Branch Context

**What goes wrong:**
The `listDatasetHandles()` and `listDatasetIdentifiers()` methods in `AbstractRestCatalogAccessor` call `getCatalog().listTables(namespace)` and `getCatalog().listNamespaces(namespace)`. These calls go through the `RESTCatalog` which uses its configured prefix (branch). If the user browses the source in the Dremio UI, they see tables from the default branch only. There is no UI mechanism to browse a different branch.

**Prevention:**
1. For Phase 1, document that the UI browse/listing always shows the default branch.
2. For Phase 2+, consider a branch selector in the UI (similar to GitHub's branch dropdown).
3. Ensure that `listDatasetIdentifiers()` respects the prefix/branch context when called from a query context (not just UI browsing).

**Phase:** Phase 3 (UI Integration) -- acceptable limitation for Phase 1.

---

### P16 -- ExpiringCatalogCache Closes Catalog During Active Queries

**What goes wrong:**
The `ExpiringCatalogCache.get()` method checks expiration and calls `invalidate()` which closes the old `RESTCatalog` via `((Closeable) catalog).close()`. If a query is actively using that catalog instance (e.g., mid-way through loading table metadata), the underlying HTTP client is closed and the query fails with a connection error.

This is an existing bug, but branch awareness makes it worse because creating per-branch catalogs multiplies the number of catalogs being created/closed, increasing the likelihood of a close-during-use race.

**Prevention:**
1. Use reference counting or `CompletableFuture`-based lifecycle management: do not close a catalog instance until all active users have released their reference.
2. At minimum, do not call `close()` on the old instance inside `invalidate()`. Let the old instance be GC'd (it will eventually be closed by the finalizer or the HTTP client timeout). This trades memory for safety.
3. With branch awareness, use a `Cache<String, RESTCatalog>` with `removalListener` that closes on eviction, and ensure queries hold a strong reference to the catalog instance they are using.

**Phase:** Phase 1 -- should be fixed as part of the catalog lifecycle redesign for multi-branch support.

---

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Phase 1: enableNessie config + basic prefix routing | P1 (cache poisoning), P4 (client lifecycle), P13 (thread safety) | Design cache key and catalog lifecycle before writing any code. These are architectural decisions. |
| Phase 2: AT BRANCH/TAG/COMMIT parsing + resolution | P3 (stale default), P5 (URL encoding), P10 (error messages), P14 (commit hash support) | Resolve references early, pin commit hashes, validate branch names at parse time. |
| Phase 3: Integration with existing metadata pipeline | P6 (metadata staleness), P9 (VDS + definer), P11 (background refresh), P15 (namespace listing) | Bypass KV store for branch-aware queries. Do not store branch-specific metadata in shared KV state. |
| Phase 4: RBAC integration | P2 (plan cache), P8 (branch-level access control) | Add version context to plan cache key. Document branch access is not RBAC-gated in Phase 1. |
| Phase 5: Multi-branch queries | P7 (table not found on one branch) | Rich error messages with branch context. Warn on mixed-branch queries. |

---

## Sources

- Codebase analysis: `AbstractRestCatalogAccessor.java` (table cache, cache key structure), `CatalogAccessorTableCacheKey.java` (cache key without branch), `PlanCacheUtils.java` (plan cache key generation, versioned table exclusion), `ExpiringCatalogCache.java` (catalog lifecycle), `RestIcebergCatalogPlugin.java` (plugin config, catalog creation), `IcebergCatalogPlugin.java` (`hasAccessPermission` TODO), `CatalogImpl.java` (RBAC validatePrivilege, resolveVersionContext), `NessieClientImpl.java` (content cache with branch-aware key), `ViewExpander.java` (definer rights, version context propagation), `IcebergCatalogPluginOptions.java` (cache TTL defaults)
- [Nessie Iceberg REST Configuration Guide](https://projectnessie.org/guides/iceberg-rest/) -- prefix format, branch/warehouse separator, URL structure
- [Nessie + Iceberg + Spark](https://projectnessie.org/iceberg/spark/) -- table@branch syntax, SQL escaping requirements
- [Apache Iceberg CatalogProperties](https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/CatalogProperties.java) -- REST prefix property
- [Iceberg REST Catalog Spec](https://iceberg.apache.org/rest-catalog-spec/) -- error code structure
- [Dremio Community: NessieNotFoundException](https://community.dremio.com/t/nessienotfoundexception-requested-contents-do-not-exist-for-specified-reference/8425) -- HTTP 404 error semantics
