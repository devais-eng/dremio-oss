# Pitfalls Research

**Domain:** Wiring an existing Iceberg REST Catalog plugin in Dremio OSS (v1.1)
**Researched:** 2026-02-20
**Confidence:** HIGH — findings derived directly from codebase analysis

---

## Critical Pitfalls

### Pitfall 1: Missing @SourceType Annotation Causes Silent Non-Discovery

**What goes wrong:**
`ConnectionReaderImpl.makeReader()` scans the classpath for classes annotated with `@SourceType` via `scanResult.getAnnotatedClasses(SourceType.class)`. Without the annotation, `RestIcebergCatalogPluginConfig` is invisible to this scan — no error is thrown, the source type simply does not appear in `GET /api/v3/catalog/source/type/list`, and the UI never offers Iceberg REST Catalog as a choice. Creating the source via the REST API with `"type": "RESTCATALOG"` will fail with a deserialization error from the `schemaByName` lookup.

The annotation must be on the concrete config class directly (`RestIcebergCatalogPluginConfig`), not on the abstract parent (`IcebergCatalogPluginConfig`). `ConnectionReaderImpl.getCandidateSources()` skips abstract classes explicitly:
```java
if (Modifier.isAbstract(input.getModifiers()) ...) { continue; }
```

**Why it happens:**
The plugin JAR is on the classpath (confirmed in `dac/daemon/pom.xml`) and `sabot-module.conf` registers the package for scanning. But classpath scanning finds `@SourceType` only. A class that inherits from a type that implements `ConnectionConf` but has no `@SourceType` of its own is invisible.

**How to avoid:**
Add `@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")` directly to `RestIcebergCatalogPluginConfig`. Verify after adding: call `GET /api/v3/catalog/source/type/RESTCATALOG` and confirm the type appears.

**Warning signs:**
- Source type missing from `GET /api/v3/catalog/source/type/list` response.
- `ConnectionReaderImpl.getConnectionConf("RESTCATALOG", ...)` throws `NullPointerException` or `MissingSourceTypeException`.
- No warning or error in Dremio logs — the absence is completely silent.

**Phase to address:**
Phase 1 (Wiring) — first line of code in the milestone.

---

### Pitfall 2: uiConfig File Not on Classpath Causes Silent Degraded UI

**What goes wrong:**
`SourceTypeTemplate.fromSourceClass()` loads the UI layout file using `sourceClass.getClassLoader().getResourceAsStream(type.uiConfig())`. If the file does not exist, it logs a `warn("Failed to load ui config file")` and returns a `SourceTypeTemplate` with `uiConfig = null`. The source becomes creatable via REST API but the UI "Add Source" dialog renders nothing — no fields at all, blank form. Users cannot configure the source through the UI.

The file name must match the `uiConfig` attribute in the annotation exactly and must be placed in `plugins/icebergcatalog/src/main/resources/`.

**Why it happens:**
The warn log is swallowed at INFO level in most deployments. The REST API returns an empty `uiConfig` field in the JSON, which the frontend silently ignores. The developer adds `@SourceType(... uiConfig = "restcatalog-layout.json")` but forgets to create the file.

**How to avoid:**
Create `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` before adding the annotation. Use `nessie-layout.json` as the structural reference. Confirm via `GET /api/v3/catalog/source/type/RESTCATALOG` that the `uiConfig` field in the response is non-null and contains the expected JSON layout.

**Warning signs:**
- `WARN: Failed to load ui config file [restcatalog-layout.json]` in server log.
- `GET /api/v3/catalog/source/type/RESTCATALOG` returns `"uiConfig": null`.
- UI "Add Source" form is blank or missing the Endpoint URI field.

**Phase to address:**
Phase 1 (Wiring) — create alongside the annotation.

---

### Pitfall 3: Feature Flag Default is TRUE — Plugin Active Without Explicit Enable

**What goes wrong:**
`RESTCATALOG_PLUGIN_ENABLED` defaults to `true` (`new TypeValidators.BooleanValidator("plugins.restcatalog.enabled", true)`). Once `@SourceType` is added, any user who can create sources can immediately create an Iceberg REST Catalog source — without the admin explicitly enabling it. For a v1.1 milestone targeting read-only validation, this means write operations through `RESTCATALOG_PLUGIN_MUTABLE_ENABLED` (also defaults `true`) are also live.

This is the opposite of v1.0 RBAC which defaulted to `false` (safe rollout). For v1.1, the risk is accepting connections before connectivity and auth are validated.

**Why it happens:**
The plugin was designed by the upstream team with `true` defaults because it is intended to be generally available. But for OSS enablement of an unvalidated source, defaulting to `true` means write paths are immediately available.

**How to avoid:**
For the read-only validation milestone, verify that the `validateOnStart()` guard works as designed: if `!optionManager.getOption(getEnableOption())` → throw `UnsupportedError`. If read-only is the goal, confirm mutable operations (CREATE TABLE, DROP TABLE, etc.) throw via the `RESTCATALOG_PLUGIN_MUTABLE_ENABLED` guard. Do not change the default — document that operators should set `plugins.restcatalog.mutable.enabled = false` if they want read-only behavior.

**Warning signs:**
- User creates a CTAS against the Lakekeeper source without hitting an error during the read-only validation phase.
- Mutable write operations succeed when they should be blocked.

**Phase to address:**
Phase 1 (Wiring) — verify the mutable flag behavior before validation testing starts.

---

### Pitfall 4: Namespace Separator Mismatch Between allowedNamespaces Config and Catalog Entries

**What goes wrong:**
`allowedNamespaces` in `RestIcebergCatalogPluginConfig` is a `List<String>` where each string represents a hierarchical namespace. The accessor splits each entry using `RESTCATALOG_ALLOWED_NS_SEPARATOR` (default regex `"\\."`), meaning the expected format for a two-level namespace is `"db.schema"`. Lakekeeper namespaces can themselves contain dots if namespace components are named with dots. A user configuring `allowedNamespaces = ["my.db"]` intends to allow the namespace `my.db` (a single-level namespace with a dot in the name), but the separator splits it into `["my", "db"]` — a two-level namespace. The table filtering will silently show empty or wrong results.

**Why it happens:**
The separator is a configurable option (`RESTCATALOG_ALLOWED_NS_SEPARATOR`) that defaults to `"\\."` (the dot). The `AbstractRestCatalogAccessor` constructor applies `s.split(separator)` where `separator` is the raw option string treated as a regex:
```java
Namespace.of(s.split(separator))
```
A user who wants to allow a namespace containing a literal dot has no obvious way to escape it.

**How to avoid:**
When configuring `allowedNamespaces` for Lakekeeper, use only namespaces whose names do not contain dots, or change the separator option (`plugins.restcatalog.allowed.ns.separator`) to a character that does not appear in namespace names (e.g., `"\u001f"` — the same unit separator used for `NAMESPACE_SEPARATOR` internally). Document this constraint clearly in the UI layout.

**Warning signs:**
- `allowedNamespaces` is set but no tables appear in the source browser.
- The namespace configured in `allowedNamespaces` exists in Lakekeeper but `listDatasetHandles` returns empty.
- Debug logging shows namespace filtering discarding all entries.

**Phase to address:**
Phase 2 (Validation against Lakekeeper) — caught during namespace browsing tests.

---

### Pitfall 5: ExpiringCatalogCache Requires Concrete RESTCatalog — Fails on Subclass

**What goes wrong:**
`ExpiringCatalogCache.get()` asserts:
```java
Preconditions.checkArgument(catalog instanceof RESTCatalog, "RESTCatalog instance expected");
```
`IcebergRestCatalogAccessor.checkStateInternal()` also asserts:
```java
Preconditions.checkState(catalog instanceof RESTCatalog, ...);
```
If authentication or Lakekeeper-specific wiring requires a different catalog implementation (e.g., a `SessionCatalog`, custom `BaseCatalog` subclass, or a proxied `RESTCatalog`), these hard `instanceof` checks will throw `IllegalArgumentException` at startup — not a clean `UserException`, but a raw Preconditions failure that surfaces as a generic connection error.

**Why it happens:**
The `restCatalogImpl()` method is protected and overridable, but the caching infrastructure hardcodes `RESTCatalog` class check. If a future auth wrapper or Lakekeeper-specific adapter is not a direct `RESTCatalog` instance, the cache will reject it.

**How to avoid:**
For Lakekeeper with OAuth2/bearer token auth, authentication is passed via catalog properties (`rest.auth.type = oauth2` or `rest.credential = <token>`) into the standard `RESTCatalog` — no custom class needed. Verify that the catalog returned by `CatalogUtil.loadCatalog()` is exactly `org.apache.iceberg.rest.RESTCatalog` and not a subclass. If using a custom catalog implementation for testing, ensure it extends `RESTCatalog`.

**Warning signs:**
- Source shows as `BAD` state immediately after creation.
- Dremio logs show `IllegalArgumentException: RESTCatalog instance expected` or `IllegalStateException: Catalog is not an instance of RESTCatalog`.
- `validateOnStart()` passes but `getState()` returns BAD.

**Phase to address:**
Phase 1 (Wiring) — caught at first source creation attempt.

---

### Pitfall 6: Lakekeeper OAuth2/Bearer Auth Must Be Passed as Catalog Properties, Not Hadoop Config

**What goes wrong:**
The `buildCatalogProperties()` method in `RestIcebergCatalogPlugin` accepts arbitrary `Property` entries from both `propertyList` and `secretPropertyList` and puts them into the catalog properties map. The Iceberg `RESTCatalog` reads OAuth2 credentials (`rest.auth.type`, `rest.credential`, `oauth2.server-uri`, `oauth2.scope`, `oauth2.credential`) from this map. If the user puts auth properties into `propertyList` instead of `secretPropertyList`, the bearer token will be stored in plaintext in Dremio's KV store and exposed in `GET /api/v3/catalog/source/{id}` responses.

Additionally, properties put into `conf.set(p.name, p.value)` on the Hadoop `Configuration` do not flow to the `RESTCatalog` auth layer — the auth layer reads from the catalog properties map, not Hadoop config. Putting auth properties only in Hadoop config will silently fail authentication (server returns 401, which manifests as a connection error).

**Why it happens:**
The code does both:
```java
config.set(p.name, p.value);         // Hadoop conf
properties.put(p.name, p.value);     // Catalog properties
```
But the distinction between `propertyList` and `secretPropertyList` is only about storage encryption in Dremio's KV store — both get merged into the same catalog properties map at runtime. The pitfall is UI-level: using the wrong input field.

**How to avoid:**
- Always put bearer token, OAuth2 credentials, and any sensitive auth values in `secretPropertyList` (labeled "Catalog Credentials" in the UI). This ensures they are encrypted at rest.
- For Lakekeeper with bearer token auth: use property name `rest.credential` = `<token>` in the secret properties.
- For Lakekeeper with OAuth2: use `rest.auth.type = oauth2`, `oauth2.server-uri = <token endpoint>`, `oauth2.credential = <client_id:client_secret>` in secret properties.
- Verify the source state is GOOD after creation — a 401 from Lakekeeper surfaces as `SourceState.BAD`.

**Warning signs:**
- Source created successfully (no startup error) but state shows BAD with "Could not connect... check credentials".
- Lakekeeper server logs show 401 Unauthorized on the initial catalog config request.
- Bearer token appears in plaintext in `GET /api/v3/catalog/source/{id}` response.

**Phase to address:**
Phase 2 (Validation against Lakekeeper) — first connection attempt.

---

### Pitfall 7: Dataset Depth Constraint Breaks Flat Namespace Configurations

**What goes wrong:**
`AbstractRestCatalogAccessor.namespaceFromDataset()` enforces:
```java
Preconditions.checkState(size >= 3, "A dataset must only be created underneath of a folder.");
```
The path components list is `[sourceName, namespace..., tableName]`. For a minimum valid path, this requires at least one namespace level between the source name and the table name — i.e., `[sourceName, namespace, tableName]` = 3 elements. A table at the Iceberg root namespace (`Namespace.empty()`) with path `[sourceName, tableName]` = 2 elements will throw an `IllegalStateException` during `datasetExists()`, `getDatasetHandle()`, or `getTableMetadata()`.

Lakekeeper (and most production Iceberg REST catalogs) require tables to be in at least one namespace. But if a Lakekeeper instance has tables directly under the root or if Dremio constructs a path with fewer than 3 components, the error is unchecked and surfaces as an internal error rather than a user-facing message.

**Why it happens:**
The constraint is a Preconditions check, not a graceful UserException. The `datasetExists()` method wraps only `BadRequestException` and `IllegalStateException` — but `IllegalStateException` from a Preconditions violation is not a `BadRequestException`. The path size check fires before the HTTP request is made.

**How to avoid:**
- Configure Lakekeeper with at least one namespace level for all tables: `my_namespace.my_table`, not `my_table`.
- When testing, validate that all Lakekeeper tables are in a non-root namespace.
- Do not attempt to browse root-level tables through Dremio's source browser.

**Warning signs:**
- `IllegalStateException: A dataset must only be created underneath of a folder` in server logs.
- Source browser shows the source but clicking on it fails with an internal error.
- Tables visible via `GET /api/v3/catalog` but not queryable.

**Phase to address:**
Phase 2 (Validation against Lakekeeper) — caught during namespace browsing.

---

### Pitfall 8: Table Cache Served Per-User Blocks Staleness Detection

**What goes wrong:**
`AbstractRestCatalogAccessor` uses a per-user Caffeine cache keyed by `(userId, tableIdentifier)` with a default TTL of 3 seconds (minimum) and up to 120 seconds (`RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS`). During read-only validation against Lakekeeper, if a table schema is updated in Lakekeeper between two Dremio queries, the second query may read stale metadata from the cache and produce incorrect results. The cache is per-user, so different users querying the same table see different metadata if their cache entries are at different ages.

Additionally, `invalidateTableCacheForAllUsers()` iterates the entire cache map to find keys matching a table identifier — this is a full cache scan, which degrades proportionally with cache size if many tables are cached.

**Why it happens:**
The cache is designed for performance in a multi-user query environment. For validation testing where schema accuracy is the goal, the default 120-second TTL is too long to catch schema changes quickly.

**How to avoid:**
- During validation testing, set `plugins.restcatalog.table_cache.expire_after_write_seconds = 3` (the minimum) or disable table caching entirely with `plugins.restcatalog.table_cache.enabled = false`.
- In production, use `ALTER TABLE ... REFRESH METADATA` or the `ForceUpdateOption` path to bypass the cache when freshness is required.
- Do not rely on cache TTL expiry as the primary mechanism for detecting schema changes during testing.

**Warning signs:**
- Two successive `SELECT *` queries on the same table return different column counts.
- Schema changes made in Lakekeeper are not reflected in Dremio for up to 120 seconds.
- Debug logs show "cache miss" on first query but no miss on subsequent queries for the same table.

**Phase to address:**
Phase 2 (Validation against Lakekeeper) — configure TTL before starting validation tests.

---

### Pitfall 9: Catalog Expiry Closes and Reopens RESTCatalog — OAuth Token Not Refreshed

**What goes wrong:**
`ExpiringCatalogCache` holds a single `RESTCatalog` instance and re-creates it after `RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS` (default 1800 seconds = 30 minutes). When the cache expires, the old `RESTCatalog` is closed via `Closeable.close()` and a new one is created by calling `catalogSupplier.get()`. The new catalog re-reads properties and re-initializes auth.

The risk: if the OAuth2 access token in the catalog properties is a static bearer token stored at plugin creation time (from `secretPropertyList`), the new catalog will present the same token to Lakekeeper. If the token has expired in the meantime (e.g., short-lived tokens with 15-minute TTL), the new catalog creation will get a 401 from Lakekeeper and `ExpiringCatalogCache.get()` will throw from the `catalogSupplier.get()` call — the plugin enters BAD state.

**Why it happens:**
The `catalogSupplier` is a lambda that closes over the static `properties` map from `buildCatalogProperties()`. Properties are read once at plugin creation (in `RestIcebergCatalogPlugin.createRestCatalog()`). Token refresh is not implemented — there is no mechanism to re-read secrets from the KV store when the catalog is re-created.

**How to avoid:**
- For Lakekeeper validation, use long-lived tokens (personal access tokens or tokens with >2 hour TTL).
- For OAuth2 client credentials flow, use `rest.auth.type = oauth2` with `oauth2.server-uri` and `oauth2.credential` — the `RESTCatalog` handles token refresh internally when using the OAuth2 flow, unlike static bearer tokens.
- Avoid static short-lived bearer tokens in production.
- After catalog expiry (every 30 minutes), check source state and confirm it remains GOOD.

**Warning signs:**
- Source flips from GOOD to BAD approximately every 30 minutes.
- Lakekeeper logs show 401 errors starting at the catalog cache TTL boundary.
- Plugin restarts fix the issue temporarily (new token loaded on restart).

**Phase to address:**
Phase 2 (Validation against Lakekeeper) — test with token TTL awareness.

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Skipping UI layout JSON (no `uiConfig`) | Faster wiring, source usable via REST API | UI form is blank; users cannot create source without knowing exact property names | Never — the layout is mandatory for a usable source |
| Using `propertyList` for auth tokens instead of `secretPropertyList` | Simpler setup | Tokens stored plaintext in KV store, exposed via REST API | Never |
| Leaving `RESTCATALOG_PLUGIN_MUTABLE_ENABLED = true` during read-only validation | No config change needed | Write operations accepted and attempted against catalog | Never during v1.1 read-only phase; set to `false` if read-only is required |
| Omitting `allowedNamespaces` filter | All namespaces visible immediately | Full recursive namespace scan on every metadata refresh — expensive on large Lakekeeper instances | Acceptable for local testing; set namespaces in production |
| Using `IcebergRestCatalogAccessor` (marked `@Deprecated`) | It's what `RestIcebergCatalogPlugin.createCatalog()` uses today | Signals the class will be replaced; future changes may not maintain backward compatibility | Acceptable for v1.1; track the deprecation |

---

## Integration Gotchas

Common mistakes when connecting to Lakekeeper specifically.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Lakekeeper auth | Passing `Authorization: Bearer <token>` as a custom HTTP header via propertyList | Use `rest.credential = <token>` as a catalog property — `RESTCatalog` adds the header automatically |
| Lakekeeper endpoint | Using the base URL without `/v1` suffix (e.g., `http://lakekeeper:8080`) | Lakekeeper expects `http://lakekeeper:8080/catalog` — the Iceberg REST spec base; verify with Lakekeeper docs |
| Lakekeeper namespaces | Creating tables at root level (`Namespace.empty()`) | All tables must be in at least one namespace; Dremio enforces minimum path depth of 3 (`[source, ns, table]`) |
| Lakekeeper warehouse | Not specifying `warehouse` property for multi-warehouse deployments | Set `warehouse = <warehouse-name>` in `propertyList` when connecting to a specific warehouse |
| Lakekeeper OAuth2 | Using `rest.auth.type = bearer` with a rotating token | Use `rest.auth.type = oauth2` with client credentials so the `RESTCatalog` handles token refresh |
| File system access | Assuming Dremio can reach the table data storage directly | Lakekeeper vends table locations (S3/GCS/ADLS paths) — Dremio needs separate cloud storage credentials configured as Hadoop config properties |

---

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Recursive namespace scan without `allowedNamespaces` | Metadata refresh takes minutes; Lakekeeper rate-limited | Set `allowedNamespaces` to the specific namespaces needed | >100 namespaces in Lakekeeper |
| No table cache TTL tuning | Every query hits Lakekeeper's HTTP API for metadata | Leave cache enabled (default); tune TTL based on schema change frequency | >50 concurrent users |
| Full cache scan in `invalidateTableCacheForAllUsers()` | Cache invalidation becomes slow | Acceptable for v1.1 scale; redesign cache key structure later if needed | >10,000 cache entries |
| Catalog re-creation every 30 min with token exchange overhead | Brief latency spikes at 30-minute intervals | Use OAuth2 client credentials to minimize per-creation cost | Not a concern at v1.1 scale |

---

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| `hasAccessPermission()` is a `// TODO: implement RBAC` no-op | All users can see all tables in the Iceberg REST Catalog source regardless of Dremio RBAC grants | Documented limitation for v1.1; enforce access at the Lakekeeper level using its native auth for now |
| Secrets in `propertyList` instead of `secretPropertyList` | Bearer tokens exposed in GET API responses and stored plaintext in RocksDB | Always use `secretPropertyList` for any credential, token, or password |
| Missing warehouse scoping | A user of source A can query tables in warehouse B if namespaces overlap | Use `allowedNamespaces` to limit visibility to the intended warehouse's namespaces |
| No TLS validation on REST endpoint | Man-in-the-middle possible if `http://` is used | Use `https://` for the REST endpoint in production; `http://` only acceptable for localhost testing |

---

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Source discoverable via API:** Verify `GET /api/v3/catalog/source/type/RESTCATALOG` returns HTTP 200 with a non-null body — not just that the annotation compiles.
- [ ] **UI layout present:** Verify `GET /api/v3/catalog/source/type/RESTCATALOG` returns `uiConfig` with non-null JSON, not `null`.
- [ ] **Source state GOOD after creation:** Verify `GET /api/v3/catalog/source/{id}/state` returns `"status": "good"` after creating a Lakekeeper source — source creation can succeed while the actual connection fails.
- [ ] **Namespace browsing works:** Navigate the source in the Dremio UI schema browser and confirm namespaces from Lakekeeper appear — the catalog may connect but return empty results due to `allowedNamespaces` misconfiguration.
- [ ] **Table listing returns results:** Confirm at least one table appears under a namespace — namespace browsing can work while table listing fails due to path depth issues.
- [ ] **SELECT query executes:** Run `SELECT * FROM "source"."namespace"."table" LIMIT 10` and get actual rows — metadata resolution can succeed while data scan fails due to missing file system credentials.
- [ ] **Mutable operations blocked:** Attempt a `CREATE TABLE ... AS SELECT` against the source and confirm it throws `UnsupportedOperationException` (if `RESTCATALOG_PLUGIN_MUTABLE_ENABLED = false`), not a 500 error.
- [ ] **Feature flag defaults confirmed:** Check that `RESTCATALOG_PLUGIN_ENABLED` is `true` (source can be created) and `RESTCATALOG_PLUGIN_MUTABLE_ENABLED` is `true` (write ops live) — verify expected behavior for each.

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Missing `@SourceType` annotation | LOW | Add annotation, rebuild JAR, restart Dremio — no data migration needed |
| Missing `uiConfig` JSON | LOW | Create the file, rebuild JAR, restart Dremio — no data loss |
| Wrong auth property placement (token in propertyList) | MEDIUM | Delete source, recreate with token in secretPropertyList — existing KV record with plaintext token must be deleted manually |
| Namespace separator mismatch causing empty results | LOW | Update source config with corrected `allowedNamespaces` format — no restart needed |
| Plugin in BAD state due to expired token | LOW | Update source credentials via PUT `/api/v3/catalog/source/{id}` with fresh token — no restart needed |
| Tables not visible due to flat namespace (depth < 3) | LOW | Move tables to a proper namespace in Lakekeeper; no Dremio change needed |

---

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Missing `@SourceType` annotation (P1) | Phase 1: Wiring | `GET /api/v3/catalog/source/type/RESTCATALOG` returns 200 |
| Missing `uiConfig` JSON file (P2) | Phase 1: Wiring | Response includes non-null `uiConfig` JSON |
| Feature flag defaults understanding (P3) | Phase 1: Wiring | Document mutable vs read-only behavior before testing |
| Namespace separator mismatch (P4) | Phase 2: Validation | Namespace browsing returns correct Lakekeeper namespaces |
| ExpiringCatalogCache RESTCatalog constraint (P5) | Phase 1: Wiring | Source reaches GOOD state on first connection |
| Lakekeeper auth via catalog properties (P6) | Phase 2: Validation | Source GOOD state confirmed; Lakekeeper logs show 200 responses |
| Dataset depth constraint (P7) | Phase 2: Validation | Tables appear in browser; SELECT queries succeed |
| Table cache TTL during testing (P8) | Phase 2: Validation | Cache TTL reduced before validation tests start |
| OAuth2 token refresh on catalog expiry (P9) | Phase 2: Validation | Source state checked 30+ minutes after creation |

---

## Sources

- Codebase analysis: `/home/emanuele/IdeaProjects/dremio-oss/plugins/icebergcatalog/src/`
  - `RestIcebergCatalogPlugin.java` — plugin wiring, auth property merging, catalog creation
  - `RestIcebergCatalogPluginConfig.java` — config fields, missing `@SourceType` confirmation
  - `IcebergCatalogPlugin.java` — feature flag guard, `hasAccessPermission` TODO, `validateOnStart`
  - `AbstractRestCatalogAccessor.java` — namespace filtering, table cache, path depth constraint
  - `ExpiringCatalogCache.java` — RESTCatalog instanceof check, catalog expiry behavior
  - `IcebergCatalogPluginOptions.java` — feature flag defaults (all TRUE)
  - `CatalogOptions.java` — `RESTCATALOG_VIEWS_SUPPORTED`, `RESTCATALOG_FOLDERS_SUPPORTED` defaults (TRUE)
  - `IcebergCatalogPluginUtils.java` — `NAMESPACE_SEPARATOR = "\u001f"` (unit separator char)
- Codebase analysis: `dac/backend/src/main/java/com/dremio/dac/api/SourceTypeTemplate.java`
  - ClassLoader resource lookup, silent warn on missing `uiConfig` file
- Codebase analysis: `sabot/kernel/src/main/java/com/dremio/exec/catalog/ConnectionReaderImpl.java`
  - `@SourceType` classpath scanning mechanism, abstract class exclusion
- Reference plugin: `plugins/dataplane/src/main/resources/nessie-layout.json` — UI layout structure

---
*Pitfall research for: Dremio OSS v1.1 Iceberg REST Catalog wiring against Lakekeeper*
*Researched: 2026-02-20*
