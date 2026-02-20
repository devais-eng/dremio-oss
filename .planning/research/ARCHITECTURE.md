# Architecture Research

**Domain:** Dremio OSS — Iceberg REST Catalog plugin integration (v1.1)
**Researched:** 2026-02-20
**Confidence:** HIGH (all findings directly verified from source code)

---

## Standard Architecture

### System Overview — Source Registration and Query Execution

The Dremio OSS plugin architecture has five distinct layers a new source must traverse, from
registration to query execution:

```
LAYER 1: SOURCE REGISTRATION (Classpath Scanning)

  @SourceType("RESTCATALOG", label=..., uiConfig=...)
         annotation on RestIcebergCatalogPluginConfig
  ConnectionReaderImpl.makeReader(scanResult)
    -> scans for @SourceType annotated classes
    -> builds Map<typeName, Schema> (schemaByName)
    -> ConnectionReader provides getAllConnectionConfs()

LAYER 2: SOURCE VISIBILITY (API / Feature Flags)

  DeprecatedSourceResource.isSourceTypeVisible("RESTCATALOG")
    -> case "RESTCATALOG": return RESTCATALOG_PLUGIN_ENABLED
  SourceVerifier.isSourceSupported(sourceType, optionManager)
    -> both checks must pass for source to appear in UI/API

  SourceTypeTemplate.fromSourceClass(...)
    -> loads icon: classLoader.getResource("RESTCATALOG.svg")
    -> loads UI config: classLoader.getResourceAsStream(uiConfig)

LAYER 3: PLUGIN LIFECYCLE (PluginConfig -> StoragePlugin)

  RestIcebergCatalogPluginConfig.newPlugin(...)
    -> new RestIcebergCatalogPlugin(this, sabotContext, name, idPrv)
  ManagedStoragePlugin calls config.newPlugin(...)
  PluginsManager.newPlugin(SourceConfig) manages lifecycle

  IcebergCatalogPlugin.start()
    -> validateOnStart(): checks RESTCATALOG_PLUGIN_ENABLED
    -> createCatalog(fsConf) -> IcebergRestCatalogAccessor
       (wraps RESTCatalog via ExpiringCatalogCache)
    -> createFSCache() -> DatasetFileSystemCache

LAYER 4: DATASET RESOLUTION (Metadata)

  IcebergCatalogPlugin.getDatasetHandle(EntityPath, options...)
    -> CatalogAccessor.getDatasetHandle(components, plugin, options)
    -> returns IcebergCatalogTableProvider (tables)
       or IcebergCatalogViewProvider (views, if viewsEnabled())

  IcebergCatalogPlugin.listDatasetHandles(options...)
    -> CatalogAccessor.listDatasetHandles(rootName, plugin)

  IcebergCatalogPlugin.listPartitionChunks(handle, options...)
    -> CatalogAccessor.listPartitionChunks(tableProvider, options)

  IcebergCatalogPlugin.getDatasetMetadata(handle, chunks, options)
    -> CatalogAccessor.getTableMetadata(tableProvider, options)
       or CatalogAccessor.getViewMetadata(handle) for views

LAYER 5: QUERY EXECUTION (Scan / Write)

  IcebergCatalogPlugin.getRulesFactoryClass()
    -> returns FileSystemRulesFactory (Iceberg/Parquet scan rules)

  IcebergCatalogPlugin.createScanTableFunction(...)
    -> ParquetScanTableFunction (reads Iceberg/Parquet data)

  IcebergCatalogPlugin.createSplitCreator(...)
    -> ParquetSplitCreator

  Write path (DML/DDL):
  RestIcebergCatalogPlugin.createNewTable(...)
    -> CreateParquetTableEntry (guarded by MUTABLE_ENABLED flag)
  RestIcebergCatalogPlugin.getIcebergModel(...)
    -> IcebergCatalogModel -> IcebergCatalogCommand
```

---

## Component Responsibilities

| Component | File | Responsibility |
|-----------|------|----------------|
| `RestIcebergCatalogPluginConfig` | `plugins/icebergcatalog/.../RestIcebergCatalogPluginConfig.java` | Holds user-facing configuration fields (endpoint URI, allowed namespaces, properties/secrets). Implements `newPlugin()` factory. **MISSING @SourceType** — primary gap to fix. |
| `RestIcebergCatalogPlugin` | `plugins/icebergcatalog/.../RestIcebergCatalogPlugin.java` | Concrete plugin: creates `IcebergRestCatalogAccessor`, implements DML (create/drop/alter table, views, folders), delegates to `IcebergCatalogModel` for Iceberg operations. Guards all mutable ops with `RESTCATALOG_PLUGIN_MUTABLE_ENABLED`. |
| `IcebergCatalogPlugin` (abstract) | `plugins/icebergcatalog/.../IcebergCatalogPlugin.java` | Base class: implements `StoragePlugin`, `SupportsListingDatasets`, `SupportsIcebergMutablePlugin`, `SupportsIcebergRestApi`, `SupportsMetadataVerify`. Owns start/close lifecycle, file system setup, partition/metadata methods, scan table function creation. |
| `IcebergCatalogPluginConfig` (abstract) | `plugins/icebergcatalog/.../IcebergCatalogPluginConfig.java` | Abstract base for all config: shared fields (propertyList, secretPropertyList, async settings, cache settings). Extends `ConnectionConf`, implements `AsyncStreamConf`, `MutablePluginConf`. |
| `IcebergRestCatalogAccessor` | `plugins/icebergcatalog/.../IcebergRestCatalogAccessor.java` | Adapts `RESTCatalog` (Iceberg SDK) into Dremio's `CatalogAccessor` interface. Uses `ExpiringCatalogCache` to manage catalog lifetime. Marked `@Deprecated` internally — still the active implementation for v1.1. |
| `AbstractRestCatalogAccessor` | `plugins/icebergcatalog/.../AbstractRestCatalogAccessor.java` | Core catalog operations: dataset listing, table/view handle creation, partition chunking, metadata reads, Caffeine-based table/view caching, namespace filtering. |
| `CatalogAccessor` (interface) | `plugins/icebergcatalog/.../CatalogAccessor.java` | Contract between `IcebergCatalogPlugin` and catalog implementations. Extends `SupportsIcebergDatasetCUD`, `SupportsIcebergFolderCUD`. |
| `IcebergCatalogTableProvider` | `plugins/icebergcatalog/.../IcebergCatalogTableProvider.java` | Implements `DatasetHandle` for tables: provides file config, split xattr (namespace/table/metadata path), dataset type `PHYSICAL_DATASET`. |
| `IcebergCatalogViewProvider` | `plugins/icebergcatalog/.../IcebergCatalogViewProvider.java` | Implements `ViewDatasetHandle` + `IcebergViewMetadata` for views: serializes view metadata into `DatasetConfig`/`VirtualDataset`. |
| `IcebergCatalogModel` | `plugins/icebergcatalog/.../IcebergCatalogModel.java` | Implements `IcebergModel`: bridges Dremio's table mutation API to Iceberg catalog operations (commit, rollback, DDL). Used by DML paths. |
| `ConnectionReaderImpl` | `sabot/kernel/.../ConnectionReaderImpl.java` | Classpath scanner for `@SourceType` annotated `ConnectionConf` subclasses. Without `@SourceType` on `RestIcebergCatalogPluginConfig`, the plugin is invisible to the system. |
| `DeprecatedSourceResource` | `dac/backend/.../DeprecatedSourceResource.java` | REST API for source types. Already has `case "RESTCATALOG": return RESTCATALOG_PLUGIN_ENABLED` in `isSourceTypeVisible()`. No change needed. |
| `IcebergCatalogPluginOptions` | `sabot/kernel/.../IcebergCatalogPluginOptions.java` | Feature flags: `RESTCATALOG_PLUGIN_ENABLED` (default: true), `RESTCATALOG_PLUGIN_MUTABLE_ENABLED` (default: true), caching options, catalog expiry settings. |

---

## Integration Points — What Needs Wiring

### Gap Analysis: Existing vs Missing

| Integration Point | Status | What's Needed |
|-------------------|--------|---------------|
| `@SourceType` annotation on `RestIcebergCatalogPluginConfig` | **MISSING** | Add `@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")` |
| Classpath scanning registration | **Blocked by above** | Automatic once `@SourceType` is added — `sabot-module.conf` already registers `com.dremio.plugins.icebergcatalog` package |
| `DeprecatedSourceResource.isSourceTypeVisible()` | **COMPLETE** | Already has `case "RESTCATALOG"` check at lines 231-232 |
| `RESTCATALOG_PLUGIN_ENABLED` feature flag | **COMPLETE** | Defined in `IcebergCatalogPluginOptions`, default is `true` |
| `IcebergRestCatalogAccessor` catalog creation | **COMPLETE** | `RestIcebergCatalogPlugin.createCatalog()` is implemented |
| Dataset handle resolution | **COMPLETE** | `getDatasetHandle()`, `listDatasetHandles()`, `getDatasetMetadata()` all implemented |
| Partition chunking | **COMPLETE** | `listPartitionChunks()` delegating to `CatalogAccessor` |
| Query execution (scan) | **COMPLETE** | `FileSystemRulesFactory`, `ParquetScanTableFunction`, `ParquetSplitCreator` all wired |
| DML/DDL operations | **COMPLETE** | All mutating operations in `RestIcebergCatalogPlugin` (guarded by `MUTABLE_ENABLED`) |
| UI layout config file | **MISSING** | `restcatalog-layout.json` must exist in `plugins/icebergcatalog/src/main/resources/` |
| Source icon | **AVAILABLE** | `RESTCATALOG.svg` already exists in `dac/ui-lib/icons/dremio/sources/` and `dremio-dark/sources/` |

---

## Architectural Patterns

### Pattern 1: @SourceType-Driven Registration

**What:** Every source plugin config class must have `@SourceType` to be discovered by
`ConnectionReaderImpl`. The annotation provides the type key (used everywhere as a string:
"RESTCATALOG"), display label, and UI layout config file path.

**When to use:** The annotation goes on the concrete `ConnectionConf` subclass — not the abstract
base. For the REST Catalog, that is `RestIcebergCatalogPluginConfig`.

**The fix:**
```java
// File: plugins/icebergcatalog/.../RestIcebergCatalogPluginConfig.java

import com.dremio.exec.catalog.conf.SourceType;

@SourceType(
    value = "RESTCATALOG",
    label = "Iceberg REST Catalog",
    uiConfig = "restcatalog-layout.json"
)
public class RestIcebergCatalogPluginConfig extends IcebergCatalogPluginConfig {
    // ... existing fields unchanged
}
```

**How discovery works:** `ConnectionReaderImpl.makeReader(scanResult)` calls
`scanResult.getAnnotatedClasses(SourceType.class)` — this returns all classpath-scanned classes
bearing `@SourceType`. The `sabot-module.conf` in the icebergcatalog plugin already declares
`dremio.classpath.scanning.packages += com.dremio.plugins.icebergcatalog`, so the class will be
found once annotated. Abstract classes are explicitly skipped by the scanner, so the annotation
must be on the concrete class.

### Pattern 2: UI Layout Config File

**What:** The `uiConfig` field in `@SourceType` points to a JSON resource file loaded by class
loader (`sourceClass.getClassLoader().getResourceAsStream(type.uiConfig())`). This file defines
the form fields, tabs, and UI metadata for the source configuration dialog. The file must be in
the plugin's `src/main/resources/` so it is on the plugin JAR's classpath.

**When to use:** Required when the source has user-facing configuration. Without it, the UI
cannot render a configuration form for the source type.

**The fix:** Create `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json`.

**Reference structure (from nessie-layout.json pattern):**
```json
{
  "sourceType": "RESTCATALOG",
  "tags": [],
  "form": {
    "tabs": [
      {
        "name": "General",
        "isGeneral": true,
        "sections": [
          {
            "elements": [
              {
                "propName": "config.restEndpointUri",
                "errMsg": "Required"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

**Fields to expose** (from `RestIcebergCatalogPluginConfig` `@Tag` annotated fields):
- `config.restEndpointUri` (Tag 10) — endpoint URI, required
- `config.allowedNamespaces` (Tag 11) — optional list of allowed namespaces
- `config.isRecursiveAllowedNamespaces` (Tag 12) — boolean toggle, subtree inclusion
- `config.propertyList` (Tag 1, inherited) — arbitrary key-value catalog properties
- `config.secretPropertyList` (Tag 2, inherited) — secret credentials (bearer token, OAuth)
- `config.enableAsync` (Tag 3, inherited) — async Parquet access toggle
- `config.isCachingEnabled` (Tag 4, inherited) — local file caching toggle
- `config.maxCacheSpacePct` (Tag 5, inherited) — cache space percentage limit

### Pattern 3: Plugin Lifecycle via newPlugin()

**What:** `ConnectionConf.newPlugin(PluginSabotContext, name, Provider<StoragePluginId>)` is the
abstract factory method. `ManagedStoragePlugin` calls it when creating the actual plugin instance
from deserialized config.

**Already implemented:** `RestIcebergCatalogPluginConfig.newPlugin()` creates
`RestIcebergCatalogPlugin` correctly. No change needed.

### Pattern 4: Feature Flag Visibility — Two Independent Checks

**What:** Source visibility has two independent checks that both must pass:

1. `DeprecatedSourceResource.isSourceTypeVisible(sourceType)` — switches on source type string to
   return a per-type feature flag. The "RESTCATALOG" case already exists and returns
   `RESTCATALOG_PLUGIN_ENABLED`.

2. `SourceVerifier.isSourceSupported(sourceType, optionManager)` — a secondary check. The
   default `SourceVerifier.NO_OP` implementation always returns `true`.

**Plugin start guard:** `IcebergCatalogPlugin.validateOnStart()` also checks `getEnableOption()`
(which returns `RESTCATALOG_PLUGIN_ENABLED`) before allowing the plugin to start on coordinators.
This is already implemented in the base class. The executor node skips this check.

### Pattern 5: CatalogAccessor as Integration Seam

**What:** `IcebergCatalogPlugin` uses `CatalogAccessor` as an internal interface to isolate
itself from the concrete `RESTCatalog` implementation. `RestIcebergCatalogPlugin.createCatalog()`
returns an `IcebergRestCatalogAccessor` (which extends `AbstractRestCatalogAccessor`). The
accessor is set during `start()` and accessed via `getCatalogAccessor()` which throws
`UserException.sourceInBadState()` if the plugin is not started.

The `RESTCatalog` instance is created lazily via `Supplier<Catalog>` and cached via
`ExpiringCatalogCache` for `RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS` (default 1800s = 30min).
Table and view metadata are additionally cached in Caffeine caches within
`AbstractRestCatalogAccessor` with a 3-second TTL by default.

---

## Data Flow

### Source Creation to Query Execution (End-to-End)

```
User creates RESTCATALOG source via UI/API
    |
    v
DeprecatedSourceResource.addSource()
  - isSourceSupported("RESTCATALOG", optionManager) = true
  - sourceService.createSource(sourceConfig)
    |
    v
PluginsManager.newPlugin(sourceConfig)
  - connectionReader.getConnectionConf(sourceConfig)
    returns RestIcebergCatalogPluginConfig (deserialized from KV store)
  - config.newPlugin(sabotContext, name, idProvider)
    returns RestIcebergCatalogPlugin
  - ManagedStoragePlugin wraps it
    |
    v
RestIcebergCatalogPlugin.start()
  - validateOnStart(): checks RESTCATALOG_PLUGIN_ENABLED = true
  - createCatalog(fsConf):
    builds IcebergRestCatalogAccessor(
        createRestCatalog(config) = lazy Supplier<RESTCatalog>,
        optionManager,
        allowedNamespaces,
        isRecursiveAllowedNamespaces)
  - createFSCache(): DatasetFileSystemCache
  - isOpen = true
    |
    v
User runs: SELECT * FROM restcatalog_source.namespace.table
    |
    v
IcebergCatalogPlugin.getDatasetHandle(EntityPath[restcatalog, namespace, table])
  - CatalogAccessor.getDatasetHandle([...], plugin, options)
  - looks up table in RESTCatalog via ExpiringCatalogCache
  - returns IcebergCatalogTableProvider (DatasetHandle)
    |
    v
IcebergCatalogPlugin.listPartitionChunks(handle)
  - CatalogAccessor.listPartitionChunks(tableProvider, options)
    |
    v
IcebergCatalogPlugin.getDatasetMetadata(handle, chunks)
  - CatalogAccessor.getTableMetadata(tableProvider, options)
  - returns DatasetMetadata with schema, stats
    |
    v
Query planner uses FileSystemRulesFactory (getRulesFactoryClass())
  - generates physical plan with Parquet scan operators
    |
    v
IcebergCatalogPlugin.createScanTableFunction(fec, ctx, props, config)
  - ParquetScanTableFunction reads Iceberg Parquet files
  - file system access via DatasetFileSystemCache -> IcebergCatalogFileSystem
    |
    v
Results returned to user
```

### DML Write Path (CREATE TABLE AS SELECT)

```
User runs: CREATE TABLE restcatalog.ns.new_table AS SELECT ...
    |
    v
RestIcebergCatalogPlugin.createNewTable(tableSchemaPath, schemaConfig, icebergTableProps, writerOptions, ...)
  - checks RESTCATALOG_PLUGIN_MUTABLE_ENABLED = true
  - getNewTableLocationFromCatalog(writerOptions, dataset)
    -> checks writerOptions.tableLocation (user-specified LOCATION clause)
    -> falls back to CatalogAccessor.getDatasetLocationFromExistingNamespaceLocationUri(dataset)
  - sets icebergTableProps.tableLocation, tableName, databaseName
  - returns CreateParquetTableEntry(...)
    |
    v
RestIcebergCatalogPlugin.getIcebergModel(tableProps, userName, ctx, fileIO, userId)
  - new IcebergCatalogModel(null, fsConf, fileIO, ctx, null, this, dataset, userName, userId)
    |
    v
IcebergCatalogModel.createTableTransaction() / commitTableTransaction()
  - CatalogAccessor.createIcebergTableOperationsForCtas(...)
  - commits via RESTCatalog Iceberg SDK
```

---

## Build Order for Changes

The dependency chain dictates this order:

### Step 1 — Add `@SourceType` annotation (highest priority, unblocks everything else)

**File:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java`

**Change:** Add annotation before the class declaration:
```java
import com.dremio.exec.catalog.conf.SourceType;

@SourceType(
    value = "RESTCATALOG",
    label = "Iceberg REST Catalog",
    uiConfig = "restcatalog-layout.json"
)
public class RestIcebergCatalogPluginConfig extends IcebergCatalogPluginConfig {
```

**Effect:** `ConnectionReaderImpl.makeReader()` picks up the class during startup classpath scan
and registers "RESTCATALOG" in `getAllConnectionConfs()`. Without this, the plugin is completely
invisible — no API, no UI, no source creation.

### Step 2 — Create UI layout config (required for source form rendering)

**File:** `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json`

**Change:** New file — does not exist yet. Must be in `src/main/resources/` so it is loadable
from the plugin's class loader (`sourceClass.getClassLoader().getResourceAsStream(type.uiConfig())`
in `SourceTypeTemplate.fromSourceClass()`).

**Effect:** The source configuration dialog renders in the UI. Without this, `SourceTypeTemplate`
logs a warning and returns `null` for `uiConfig`, which may prevent the UI from showing the
source type form correctly.

### Step 3 — Verify (no code changes, but must validate)

After Steps 1 and 2, verify:

- `sabot-module.conf` already contains `dremio.classpath.scanning.packages += com.dremio.plugins.icebergcatalog` — no change needed
- `DeprecatedSourceResource.isSourceTypeVisible("RESTCATALOG")` already returns `RESTCATALOG_PLUGIN_ENABLED` — no change needed
- `RESTCATALOG_PLUGIN_ENABLED` defaults to `true` in `IcebergCatalogPluginOptions` — no change needed
- `RESTCATALOG.svg` exists in `dac/ui-lib/icons/dremio/sources/` and `dremio-dark/sources/` — served on classpath via `dac/ui` module, no change needed
- `newPlugin()` factory in `RestIcebergCatalogPluginConfig` creates `RestIcebergCatalogPlugin` — no change needed

### Step 4 — Add integration test

**Purpose:** Verify end-to-end registration.

**Pattern:** A test using `ConnectionReaderImpl` to scan the classpath and confirm "RESTCATALOG"
appears in `getAllConnectionConfs()`. See `TestRestIcebergCatalogPluginConfig` for how to
construct the plugin in a test context. See `TestSourceTypeTemplate` in dac/backend tests for
how to verify source type template construction.

---

## Component Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `RestIcebergCatalogPluginConfig` to `RestIcebergCatalogPlugin` | `newPlugin()` factory call | Config is serialized to KV store; plugin is instantiated at runtime by `ManagedStoragePlugin` |
| `IcebergCatalogPlugin` to `CatalogAccessor` | Direct method calls, initialized in `start()` | `getCatalogAccessor()` guards against uninitialized state with `UserException.sourceInBadState()` |
| `CatalogAccessor` to `RESTCatalog` (Iceberg SDK) | `ExpiringCatalogCache` wraps lazy `Supplier<Catalog>` | Catalog object has a configured TTL (`RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS`, default 1800s) |
| `IcebergCatalogPlugin` to `DatasetFileSystemCache` | Direct call to create `IcebergCatalogFileSystem` | Per-dataset file system instances with expiry (`RESTCATALOG_PLUGIN_FILE_SYSTEM_EXPIRE_AFTER_WRITE_MINUTES`, default 5min) |
| `DeprecatedSourceResource` to `ConnectionReader` | `getAllConnectionConfs()` — map built from classpath scan | Populated at startup; without `@SourceType` on config, no "RESTCATALOG" entry exists in this map |
| `SourceTypeTemplate` to plugin resources | ClassLoader `getResource(typeName + ".svg")` and `getResourceAsStream(uiConfig)` | Both `RESTCATALOG.svg` and `restcatalog-layout.json` must be on classpath |

---

## Anti-Patterns

### Anti-Pattern 1: Putting @SourceType on the Abstract Base Class

**What people do:** Add `@SourceType` to `IcebergCatalogPluginConfig` (the abstract parent).

**Why it's wrong:** `ConnectionReaderImpl.getCandidateSources()` explicitly skips abstract classes
(`Modifier.isAbstract(input.getModifiers())`). The annotation is silently ignored and the plugin
is never registered.

**Do this instead:** `@SourceType` goes on `RestIcebergCatalogPluginConfig` — the concrete class
— only.

### Anti-Pattern 2: Placing the UI Layout JSON in the Wrong Location

**What people do:** Create `restcatalog-layout.json` in `dac/backend/src/main/resources/` or
a test resources directory.

**Why it's wrong:** `SourceTypeTemplate.fromSourceClass()` uses
`sourceClass.getClassLoader().getResourceAsStream(type.uiConfig())` — the classloader of
`RestIcebergCatalogPluginConfig`, which is the icebergcatalog plugin JAR. The file must be in
that plugin's `src/main/resources/`.

**Do this instead:** `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json`

### Anti-Pattern 3: Thinking RESTCATALOG_PLUGIN_ENABLED=false Is the Blocker

**What people do:** Assume the plugin is disabled by a feature flag, try to enable it, and expect
the plugin to appear.

**Why it's wrong:** The flag defaults to `true`. The actual blocker is the missing `@SourceType`
annotation. Even with the flag `true`, there is no entry in `getAllConnectionConfs()` for
"RESTCATALOG" without the annotation.

**Do this instead:** Add `@SourceType` first. All feature flag gates are already in place and
already default to enabled.

### Anti-Pattern 4: Creating a New Maven Module or Adding Dependencies

**What people do:** Assume the plugin code needs to move to a different module or requires new
dependencies.

**Why it's wrong:** The icebergcatalog plugin is already a separate Maven module included in
`plugins/pom.xml` and declared as a dependency in `dac/daemon/pom.xml`. All plugin code is
co-located and on the runtime classpath.

**Do this instead:** All changes stay within `plugins/icebergcatalog/` — one annotation in
`RestIcebergCatalogPluginConfig.java` and one new JSON resource file. No new modules, no
dependency changes.

---

## Scaling Considerations

This is an integration of existing code, not a new system design. Runtime scaling behavior is
governed by the existing options in `IcebergCatalogPluginOptions`:

| Concern | Configuration | Default |
|---------|---------------|---------|
| REST Catalog connection TTL | `RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS` | 1800s (30min) |
| Table metadata cache TTL | `RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS` | 3s |
| Table metadata cache size | `RESTCATALOG_PLUGIN_TABLE_CACHE_SIZE_ITEMS` | 10,000 items |
| File system expiry | `RESTCATALOG_PLUGIN_FILE_SYSTEM_EXPIRE_AFTER_WRITE_MINUTES` | 5min |
| Multiple sources | Each source: independent `ManagedStoragePlugin`, independent caches, independent catalog connections | Per-instance |

---

## Sources

All findings are HIGH confidence — verified directly from source code, no external sources needed.

Key files examined:
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` — missing `@SourceType` confirmed
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` — full DML implementation confirmed complete
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergCatalogPlugin.java` — lifecycle, dataset resolution, scan table function confirmed
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/ConnectionReaderImpl.java` — classpath scanning mechanism confirmed; abstract class skip logic confirmed
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/conf/SourceType.java` — annotation fields confirmed
- `dac/backend/src/main/java/com/dremio/dac/api/DeprecatedSourceResource.java` — RESTCATALOG visibility case confirmed at lines 231-232
- `sabot/kernel/src/main/java/com/dremio/exec/store/IcebergCatalogPluginOptions.java` — all flags default `true` confirmed
- `plugins/icebergcatalog/src/main/resources/sabot-module.conf` — classpath scanning package registration confirmed
- `dac/backend/src/main/java/com/dremio/dac/api/SourceTypeTemplate.java` — icon (`{typeName}.svg`) and layout (`uiConfig`) loading mechanism confirmed
- `dac/ui-lib/icons/dremio/sources/RESTCATALOG.svg` — icon already exists confirmed
- `plugins/dataplane/src/main/resources/nessie-layout.json` — reference layout structure examined

---

*Architecture research for: Dremio OSS Iceberg REST Catalog plugin wiring (v1.1)*
*Researched: 2026-02-20*
