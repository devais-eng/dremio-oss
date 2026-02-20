# Feature Research: Iceberg REST Catalog Source (v1.1)

**Domain:** Iceberg REST Catalog source plugin — read-only, discoverable via UI, validated against Lakekeeper
**Researched:** 2026-02-20
**Confidence:** HIGH (codebase analysis) / MEDIUM (Lakekeeper specifics, WebFetch/WebSearch unavailable)

---

## Framing: What v1.1 "Enabling the REST Catalog" Means

The plugin is already fully implemented at `plugins/icebergcatalog/`. The implementation gap is discoverability and end-to-end validation, not functionality. This milestone has two deliverables:

1. **Make the source appear in Dremio's source picker UI** — add `@SourceType(value = "RESTCATALOG", ...)` to `RestIcebergCatalogPluginConfig` and create a UI layout JSON.
2. **Validate read-only operations end-to-end against Lakekeeper** — confirm that namespace browsing, table listing, and SELECT queries actually work.

The feature work is bounded. Write operations (mutable plugin methods) are behind a separate feature flag (`RESTCATALOG_PLUGIN_MUTABLE_ENABLED`, default `true` in options). For read-only v1.1, the focus is on what happens when that flag remains at its default or is explicitly disabled for validation purposes.

---

## Category 1: Table Stakes

*Must exist for the source to be minimally useful. Without these, users cannot do anything with the source.*

### 1.1 Source Discoverability via `@SourceType` Annotation

**What it is:** The `RestIcebergCatalogPluginConfig` class must carry `@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")` so that `ConnectionReaderImpl` discovers it via classpath scanning and registers it with the source picker.

**Why it's table stakes:** Without `@SourceType`, `ConnectionReaderImpl.getAllConnectionConfs()` never returns the class, `isSourceTypeVisible("RESTCATALOG")` in `DeprecatedSourceResource` already handles the feature flag gate, but there is no entry to gate. The source is completely invisible. All plugin logic exists but is unreachable from the UI.

**Confirmed from codebase:** `DeprecatedSourceResource.isSourceTypeVisible()` already has a case for `"RESTCATALOG"` checking `RESTCATALOG_PLUGIN_ENABLED`. The gate exists. The annotation is missing.

**Complexity:** Low. One annotation on one class, one JSON file.

**Dependencies:** None (it is the prerequisite for everything else).

### 1.2 UI Layout JSON for Source Configuration Form

**What it is:** A `restcatalog-layout.json` resource file that describes the connection form shown in the Dremio UI when adding a new Iceberg REST Catalog source. Must expose at minimum: Endpoint URI, optional Allowed Namespaces, optional Catalog Properties key-value pairs, optional Catalog Credentials key-value pairs.

**Why it's table stakes:** Without the layout JSON, `SourceTypeTemplate.fromSourceClass()` will either error or produce an empty/broken form. Users cannot supply the REST endpoint to connect.

**Fields already defined on `RestIcebergCatalogPluginConfig` and `IcebergCatalogPluginConfig`:**
- `restEndpointUri` (Tag 10) — the catalog endpoint URL (e.g., `http://lakekeeper:8181/catalog`)
- `allowedNamespaces` (Tag 11) — optional list of namespace strings to filter visibility
- `isRecursiveAllowedNamespaces` (Tag 12) — bool, whether allowed namespaces include subtrees
- `propertyList` (Tag 1) — generic key-value catalog properties (passed to Iceberg `RESTCatalog`)
- `secretPropertyList` (Tag 2) — secret key-value properties (auth tokens, etc.), marked `@Secret`
- `enableAsync` (Tag 3), `isCachingEnabled` (Tag 4), `maxCacheSpacePct` (Tag 5) — standard file I/O settings

**Authentication:** The Iceberg REST spec defines `credential` and `token` properties passed via the `propertyList`/`secretPropertyList` mechanism. There is no dedicated auth type selector in the existing config — authentication is entirely handled through the generic property key-value list. This matches how the plugin calls `CatalogUtil.loadCatalog()` with the property map passed directly to `RESTCatalog`.

**Complexity:** Low. Follows the established pattern (see `nessie-layout.json`, `nas-layout.json`).

**Dependencies:** 1.1 (annotation must name the file).

### 1.3 Namespace Browsing (listNamespaces)

**What it is:** When a user expands the source in Dremio's object browser, they see namespaces listed as folders. The implementation calls `SupportsNamespaces.listNamespaces()` recursively via `streamTablesRecursive()` and `getFolderStream()`.

**Why it's table stakes:** Users cannot find tables without namespace navigation. A source with no visible contents is useless.

**Already implemented:** `AbstractRestCatalogAccessor.streamCatalogNamespaces()`, `streamNamespaceWithPropertiesRecursive()`, `getFolderStream()`, and `IcebergCatalogPlugin.containerExists()`. Namespace listing is a fully working code path.

**Lakekeeper support:** Lakekeeper implements the full Iceberg REST Catalog specification including `GET /v1/namespaces` (list namespaces) and `GET /v1/namespaces/{namespace}` (get namespace). HIGH confidence — Lakekeeper is spec-compliant.

**Complexity:** Already implemented. Validation effort only.

### 1.4 Table Listing per Namespace (listTables)

**What it is:** Within each namespace, the user sees Iceberg tables. The implementation calls `Catalog.listTables(namespace)` via `streamCatalogTables()`.

**Why it's table stakes:** A namespace browser without tables is not useful.

**Already implemented:** `AbstractRestCatalogAccessor.streamCatalogTables()`, `listDatasetHandles()`.

**Lakekeeper support:** Lakekeeper implements `GET /v1/namespaces/{namespace}/tables` (list tables). HIGH confidence.

**Complexity:** Already implemented. Validation effort only.

### 1.5 Table Metadata Loading (loadTable)

**What it is:** When Dremio registers a table in its namespace KV store, it calls `getDatasetHandle()` which calls `loadTable()` to fetch the Iceberg table metadata (schema, partition spec, snapshots). This goes through the Caffeine table cache (`RESTCATALOG_PLUGIN_TABLE_CACHE_ENABLED`).

**Why it's table stakes:** Without metadata loading, Dremio cannot build query plans.

**Already implemented:** `AbstractRestCatalogAccessor.loadTable()`, `getTableHandleInternal()`, `DremioRESTTableOperations` wrapping `RESTTableOperations`. The implementation replaces `ResolvingFileIO` with `DremioFileIO` for Dremio's own file system abstractions.

**Lakekeeper support:** Lakekeeper implements `GET /v1/namespaces/{namespace}/tables/{table}` (load table). HIGH confidence.

**Complexity:** Already implemented. Validation focuses on FileIO credential delegation.

### 1.6 SELECT Query Execution (Parquet file reads)

**What it is:** After metadata is loaded, the user runs `SELECT * FROM mySource.myNamespace.myTable`. Dremio splits the Iceberg table into partition chunks via `listPartitionChunks()`, resolves Parquet file paths from the manifest, and reads files via `DremioFileIO` / `IcebergCatalogFileSystem` backed by the Hadoop filesystem cache (`DatasetFileSystemCache`).

**Why it's table stakes:** The entire purpose of adding this source type is to query data.

**Already implemented:** Full scan chain via `ParquetScanTableFunction`, `ParquetSplitCreator`, `IcebergCatalogPlugin.createScanTableFunction()`.

**Complexity:** Already implemented. Validation focus is on credential vending — Lakekeeper's ability to return presigned S3/Azure/GCS URLs that Dremio can use to read actual Parquet files.

**Critical dependency:** The REST catalog returns a table location (e.g., `s3://bucket/path/`). Dremio must be able to reach that storage. If Lakekeeper vends credentials (via the Iceberg REST credential vending spec), they arrive as properties in `loadTable()` response and must propagate correctly to `DremioFileIO`. This is the highest-risk integration point for v1.1.

### 1.7 Connection Health Check (getState)

**What it is:** Dremio periodically calls `getState()` to display source health in the UI. `IcebergCatalogPlugin.getState()` calls `getCatalogAccessor().checkState()`, which in `IcebergRestCatalogAccessor.checkStateInternal()` creates and immediately closes a new `RESTCatalog` instance as a connectivity probe.

**Why it's table stakes:** Users need to know if the source is up. A source that always shows "unknown" state is confusing.

**Already implemented.** `ExpiringCatalogCache` manages a cached `RESTCatalog` instance with a 30-minute expiry by default.

**Complexity:** Already implemented. Validate that Lakekeeper returns HTTP 200 on catalog init and that the probe doesn't create unnecessary load.

---

## Category 2: Differentiators

*Not required for read-only v1.1, but relevant for the milestone roadmap.*

### 2.1 Namespace Allowlist (Filtering Visible Namespaces)

**What it is:** `RestIcebergCatalogPluginConfig.allowedNamespaces` and `isRecursiveAllowedNamespaces` let admins restrict which namespaces are visible in Dremio for a given source instance. When set, only namespaces matching the allowlist (and optionally their subtrees) appear.

**Value proposition:** Large catalogs may have thousands of namespaces. Operators can expose only a subset relevant to a given Dremio environment.

**Already implemented:** `AbstractRestCatalogAccessor` applies the allowlist filter in `streamTables()`, `streamViews()`, and `listDatasetIdentifiers()`.

**Complexity:** Already implemented. UI exposure via layout JSON.

### 2.2 View Support (Iceberg Views)

**What it is:** When `RESTCATALOG_VIEWS_SUPPORTED` is `true`, Dremio lists and reads Iceberg views from the catalog alongside tables. Views are handled via `ViewCatalog.listViews()` and `loadView()`.

**Value proposition:** Iceberg views are an emerging standard. Exposing them allows Dremio to federate view logic defined in other engines.

**Already implemented:** `AbstractRestCatalogAccessor.streamViews()`, `streamCatalogViews()`, `getViewHandleInternal()`, `loadView()`.

**Lakekeeper support:** Lakekeeper supports Iceberg views as of its v0.8+ releases (MEDIUM confidence — verified by reputation; WebFetch unavailable). The `ViewCatalog` interface is standard Iceberg.

**Complexity:** Already implemented. Behind `RESTCATALOG_VIEWS_SUPPORTED` feature flag.

**Flag default:** `true` in `CatalogOptions`. Enabling is the default; the flag allows emergency disable.

### 2.3 Metadata Caching (Table and Catalog Cache)

**What it is:** Two layers of caching:
- **Table cache (Caffeine):** `RESTCATALOG_PLUGIN_TABLE_CACHE_ENABLED`, size 10,000 items, 3-second expiry by default. Reduces per-query REST calls.
- **Catalog instance cache (`ExpiringCatalogCache`):** Caches the `RESTCatalog` instance itself (holds the OAuth2 session/token) for 30 minutes by default.

**Value proposition:** REST calls to the catalog are network I/O. Caching avoids redundant calls per query, especially during metadata sync operations that touch many tables.

**Already implemented.** Both cache layers are active by default.

**Complexity:** Already implemented. Tunable via system options.

### 2.4 Credential Vending (Lakekeeper-specific)

**What it is:** The Iceberg REST spec supports servers returning storage credentials when loading a table (the `credentials` section in `LoadTableResponse`). Lakekeeper supports credential vending, providing short-lived S3 credentials or Azure SAS tokens. These credentials propagate via the Iceberg `RESTCatalog` into `ResolvingFileIO`, which Dremio replaces with `DremioFileIO`.

**Value proposition:** Without credential vending, Dremio must have independent access to underlying storage. With it, Lakekeeper controls storage access centrally.

**Status:** The Iceberg Java library's `RESTCatalog` handles credential vending transparently when the server supports it. Whether `DremioFileIO` correctly picks up and uses these credentials is the key validation question for v1.1. The credential replacement in `getTableHandleInternal()` (replacing `ResolvingFileIO` with `DremioFileIO`) may drop credentials that `RESTCatalog` fetched. **This is the primary integration risk.**

**Complexity:** MEDIUM to HIGH. Likely requires investigation and possibly passing credentials through to `DremioFileIO` configuration.

### 2.5 Async I/O and Local Caching

**What it is:** `IcebergCatalogPluginConfig.enableAsync` enables asynchronous Parquet reads for throughput. `isCachingEnabled` and `maxCacheSpacePct` enable local disk caching of remote Parquet data.

**Value proposition:** Standard Dremio performance features for remote file sources.

**Already implemented.** `IcebergCatalogPlugin.createFS()` wraps the filesystem through `fileSystemWrapper` which handles async and caching based on config flags.

**Complexity:** Already implemented. Exposed in UI via layout JSON advanced options.

---

## Category 3: Anti-Features

*Things to explicitly NOT build or enable in v1.1 read-only validation.*

### 3.1 Write Operations (CREATE TABLE, INSERT, DROP TABLE, etc.)

**Why it seems useful:** `RestIcebergCatalogPlugin` fully implements `SupportsIcebergMutablePlugin` — create, insert, alter, truncate, rollback, add/drop columns, update properties.

**Why to exclude in v1.1:** The milestone is specifically read-only validation. Enabling writes without end-to-end storage write validation is a correctness risk. `RESTCATALOG_PLUGIN_MUTABLE_ENABLED` controls this. The flag defaults to `true` but write paths should remain untested until v1.2.

**What to do instead:** Keep `RESTCATALOG_PLUGIN_MUTABLE_ENABLED` at default `true` (don't break existing behavior), but scope v1.1 testing exclusively to read operations. Document write operations as out-of-scope for this milestone.

### 3.2 Folder (Namespace) Create/Update/Delete

**Why it seems useful:** `RestIcebergCatalogPlugin` implements `SupportsMutatingFolders` — create, update, delete namespaces.

**Why to exclude in v1.1:** Same rationale as 3.1. Namespace mutations are behind `RESTCATALOG_FOLDERS_SUPPORTED` (default `true`). Out of scope for read-only validation.

### 3.3 Multi-Instance Source Configuration (Different Lakekeeper Warehouses)

**Why it seems useful:** One Dremio instance might connect to multiple Lakekeeper warehouses simultaneously.

**Why not in v1.1:** Single-instance validation is sufficient. Multi-instance behavior follows automatically from the plugin framework (each source instance is independent).

### 3.4 Authentication Type Selector in UI

**Why it seems useful:** Nessie has a structured auth selector (NONE/BEARER/OAUTH2). An Iceberg REST Catalog source might need similar.

**Why not in v1.1:** The Iceberg REST spec is intentionally agnostic about auth configuration — auth properties (Bearer tokens, OAuth2 credentials) are passed as raw key-value properties to `RESTCatalog` which handles the auth protocol internally. A structured selector would require understanding which auth methods each server implementation supports, creating a maintenance burden. The generic `propertyList`/`secretPropertyList` mechanism is the correct approach for v1.1.

### 3.5 Planner-Level Query Optimization for REST Catalog Specifics

**Why it seems useful:** Could optimize query plans knowing the catalog is a REST source.

**Why not in v1.1:** The existing `FileSystemRulesFactory` / `ParquetScanTableFunction` rules work correctly. Catalog-specific planner rules are a future optimization, not a correctness requirement.

---

## Feature Dependency Map

```
1.1 @SourceType Annotation (discoverability)
    └──enables──> 1.2 UI Layout JSON (source configuration form)
                      └──enables──> User can configure the source
                                        └──requires──> 1.3 Namespace Browsing (already implemented)
                                                           └──requires──> 1.4 Table Listing (already implemented)
                                                                              └──requires──> 1.5 Table Metadata Loading (already implemented)
                                                                                                 └──requires──> 1.6 SELECT Query Execution (already implemented)

1.7 Connection Health Check (already implemented)
    └──depends on──> 1.1 (source must exist to check)

2.1 Namespace Allowlist (already implemented, exposed via 1.2)
2.2 View Support (already implemented, behind feature flag)
2.3 Metadata Caching (already implemented)
2.4 Credential Vending (investigation required — may need fix)
2.5 Async I/O + Caching (already implemented, exposed via 1.2)
```

### Dependency Notes

- **1.1 is the sole blocker:** The entire plugin works today if you could create a source via API. The annotation makes UI-based source creation possible.
- **1.2 is a usability dependency on 1.1:** Without the layout JSON, the source type exists but shows a broken or empty form.
- **1.3-1.7 are already implemented:** No code changes needed for the read path. Validation effort only.
- **2.4 (Credential Vending) may unblock 1.6:** If Lakekeeper returns storage credentials and `DremioFileIO` doesn't pick them up, SELECT queries will fail with permission errors. This must be confirmed during end-to-end testing.

---

## MVP Definition for v1.1

### Launch With (v1.1)

- [ ] **1.1 @SourceType annotation on `RestIcebergCatalogPluginConfig`** — without this nothing works
- [ ] **1.2 `restcatalog-layout.json` UI layout** — enables user configuration of endpoint URI and credentials
- [ ] **End-to-end validation: namespace browsing** — confirms Lakekeeper `listNamespaces` round-trip
- [ ] **End-to-end validation: table listing** — confirms Lakekeeper `listTables` round-trip
- [ ] **End-to-end validation: SELECT query** — confirms table load, scan, and Parquet read works

### Validate During v1.1 (Not New Code, But Must Pass)

- [ ] **`ExpiringCatalogCache` health check** — getState() returns GOOD against live Lakekeeper
- [ ] **Credential vending compatibility** — if Lakekeeper vends storage credentials, DremioFileIO uses them correctly (or explicit workaround documented)
- [ ] **Feature flag gating** — `RESTCATALOG_PLUGIN_ENABLED = false` blocks source creation correctly

### Add After Validation (v1.2+)

- [ ] **Write operations end-to-end** — CREATE TABLE AS SELECT, INSERT INTO, DROP TABLE against Lakekeeper
- [ ] **View support validation** — confirm Lakekeeper views are listed and readable
- [ ] **Namespace mutation validation** — create/update/delete namespace via Dremio UI

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| 1.1 @SourceType annotation | HIGH | LOW (one annotation) | P1 |
| 1.2 UI layout JSON | HIGH | LOW (JSON file) | P1 |
| 1.3-1.5 Read path validation | HIGH | LOW (existing code, test effort) | P1 |
| 1.6 SELECT validation | HIGH | LOW-MEDIUM (credential vending risk) | P1 |
| 1.7 Health check validation | MEDIUM | LOW | P1 |
| 2.1 Namespace allowlist (expose in UI) | MEDIUM | LOW (already implemented) | P2 |
| 2.2 View support (validate) | MEDIUM | LOW (already implemented) | P2 |
| 2.3 Metadata caching (tuning) | LOW | LOW | P3 |
| 2.4 Credential vending fix | HIGH (if broken) | MEDIUM | P1 if broken / P3 if works |
| 2.5 Async I/O (expose in UI) | LOW | LOW | P2 |

---

## Lakekeeper-Specific Capabilities

**Confidence: MEDIUM** (WebFetch unavailable; based on Iceberg REST spec knowledge and Lakekeeper's documented spec-compliance)

| Capability | Iceberg REST Spec | Lakekeeper Status | Notes |
|------------|------------------|-------------------|-------|
| List namespaces (GET /v1/namespaces) | Required | Supported | Core spec operation |
| Create namespace (POST /v1/namespaces) | Required | Supported | Behind RESTCATALOG_FOLDERS_SUPPORTED |
| Get namespace metadata | Required | Supported | Used to get `location` property |
| List tables (GET /v1/namespaces/{ns}/tables) | Required | Supported | Core spec operation |
| Load table (GET /v1/namespaces/{ns}/tables/{table}) | Required | Supported | Returns metadata location |
| Create table (POST /v1/namespaces/{ns}/tables) | Required | Supported | Behind RESTCATALOG_PLUGIN_MUTABLE_ENABLED |
| Drop table (DELETE ...) | Required | Supported | Behind RESTCATALOG_PLUGIN_MUTABLE_ENABLED |
| List views (GET /v1/namespaces/{ns}/views) | Optional | Supported (v0.8+) | Behind RESTCATALOG_VIEWS_SUPPORTED |
| Credential vending (loadTable credentials) | Optional | Supported | Key validation point for v1.1 |
| OAuth2 / Bearer token auth | Outside spec | Supported | Configured via propertyList |
| Multi-warehouse routing | Outside spec | Supported | Via `warehouse` property in propertyList |

**Lakekeeper-specific configuration:** Lakekeeper uses `warehouse` as a property to route to a specific warehouse within the catalog. This must be documented in the UI as a catalog property key-value pair (not a first-class field).

---

## Sources

- **Codebase analysis (HIGH confidence):**
  - `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` — confirms @SourceType is absent
  - `dac/backend/src/main/java/com/dremio/dac/api/DeprecatedSourceResource.java` — confirms "RESTCATALOG" is already gated in `isSourceTypeVisible()`
  - `sabot/kernel/src/main/java/com/dremio/exec/store/IcebergCatalogPluginOptions.java` — all feature flags and their defaults
  - `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogOptions.java` — RESTCATALOG_VIEWS_SUPPORTED, RESTCATALOG_FOLDERS_SUPPORTED
  - `sabot/kernel/src/main/java/com/dremio/exec/catalog/conf/SourceType.java` — annotation schema
  - `sabot/kernel/src/main/java/com/dremio/exec/catalog/ConnectionReaderImpl.java` — classpath scanning uses @SourceType

- **Iceberg REST Catalog spec knowledge (MEDIUM confidence):**
  - Apache Iceberg REST Catalog specification (training knowledge, August 2025 cutoff)
  - Lakekeeper reputation as spec-compliant REST catalog server

---

*Feature research for: Dremio OSS Iceberg REST Catalog source, v1.1 read-only enablement*
*Researched: 2026-02-20*
