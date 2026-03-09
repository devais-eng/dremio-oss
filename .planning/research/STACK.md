# Technology Stack: Nessie Branch-Aware REST Catalog

**Project:** Dremio OSS -- RESTCATALOG Nessie Branch Awareness
**Milestone:** Adding AT BRANCH/TAG/COMMIT support to RESTCATALOG source type
**Researched:** 2026-03-09

## Executive Summary

The RESTCATALOG plugin can be made Nessie-aware with **zero new library dependencies**. The critical insight from research is that Nessie implements the Iceberg REST Catalog spec and encodes branch references in the REST URL prefix, using the format `{branch}` or `{branch}|{warehouse}` appended to the Iceberg REST endpoint URI. The existing Iceberg `RESTCatalog` Java client resolves this prefix at initialization time from the `prefix` key in catalog properties. The implementation strategy is to create branch-specific `RESTCatalog` instances with the appropriate prefix, rather than trying to mutate the prefix on a shared instance.

**Overall confidence:** HIGH -- all findings verified against codebase bytecode and official Nessie documentation.

---

## Question 1: What Nessie client libraries/APIs does Dremio already have?

### Existing Nessie Libraries (already in Dremio)

| Library | Version | Module | Purpose |
|---------|---------|--------|---------|
| `nessie-bom` (BOM) | `0.100.3` | Root `pom.xml` | Dependency management for all Nessie artifacts |
| `nessie-client` | `0.100.3` (via BOM) | `sabot/kernel`, `services/catalog`, `services/nessie-proxy`, `services/nessie-grpc/client` | Java HTTP client for Nessie REST API v2 |
| `nessie-model` | `0.100.3` (via BOM) | `plugins/dataplane` | Domain model (Branch, Tag, Reference, ContentKey, etc.) |
| `nessie-gc-base` | `0.100.3` (via BOM) | `plugins/dataplane` | GC infrastructure |
| `nessie-gc-iceberg` | `0.100.3` (via BOM) | `plugins/dataplane` | Iceberg-specific GC |
| `nessie-cel` | `0.4.4` | Root | CEL filter expressions for entry listing |
| `nessie-runner` | `0.32.2` | Test infrastructure | Test Nessie server runner |
| `iceberg-nessie` | `1.7.0-custom` | `plugins/dataplane` | Iceberg integration with Nessie native API |

**Confidence:** HIGH -- verified from `pom.xml` properties and dependency declarations.

### Key Dremio Nessie Abstractions

| Class/Interface | Module | Purpose |
|----------------|--------|---------|
| `NessieClient` (interface) | `sabot/kernel` | High-level Nessie operations: `resolveVersionContext()`, `getDefaultBranch()`, `listBranches()`, `listTags()`, `listEntries()`, etc. |
| `NessieClientImpl` | `sabot/kernel` | Default implementation wrapping `NessieApiV2` |
| `UsernameAwareNessieClientImpl` | `sabot/kernel` | Coordinator-only wrapper adding user context |
| `NessieApiV2` (Nessie library) | `nessie-client` | Low-level Nessie REST API v2 client interface |
| `NessieClientBuilder` | `nessie-client` | Builder for `NessieApiV2` instances via HTTP |
| `NessiePluginUtils` | `plugins/dataplane` | Helper: `getNessieRestClient()` creates `NessieApiV2` instances |
| `VersionContext` | `services/catalog` | Dremio model: BRANCH, TAG, COMMIT, NOT_SPECIFIED types |
| `ResolvedVersionContext` | `services/catalog` | Resolved version with commit hash |
| `VersionContextResolverImpl` | `sabot/kernel` | Resolves `VersionContext` -> `ResolvedVersionContext` via `VersionedPlugin` |

**Confidence:** HIGH -- verified by reading source code directly.

---

## Question 2: How does the Iceberg REST Catalog spec handle the `prefix` parameter? How does Nessie map branches to prefix?

### Iceberg REST Catalog Prefix Mechanism

The Iceberg REST Catalog spec uses a `prefix` parameter that gets inserted into all REST API URL paths. The Iceberg `ResourcePaths` class (verified from bytecode decompilation of `iceberg-core-1.7.0`) constructs URLs as:

```
v1/{prefix}/namespaces
v1/{prefix}/namespaces/{namespace}/tables
v1/{prefix}/namespaces/{namespace}/tables/{table}
v1/{prefix}/namespaces/{namespace}/views
v1/{prefix}/namespaces/{namespace}/views/{view}
```

The `prefix` is obtained from the catalog properties map (key: `"prefix"`) during `RESTCatalog.initialize()`. The `ResourcePaths` object is constructed once in `RESTSessionCatalog.initialize()` and stored in a `private final` field. **The prefix cannot be changed after initialization.**

**Confidence:** HIGH -- verified by decompiling `org.apache.iceberg.rest.ResourcePaths` from the actual JAR used by Dremio (`iceberg-core-1.7.0-5f7c992-20250730084652-3bf8b99.jar`).

### Nessie Branch-to-Prefix Mapping

Nessie implements the Iceberg REST Catalog spec and maps branch references through the REST URL path. The Nessie documentation specifies the format:

| Scenario | URI Format | Prefix Value |
|----------|------------|--------------|
| Default branch, default warehouse | `http://host:port/iceberg` | (empty/null) |
| Named warehouse only | `http://host:port/iceberg/\|warehouse` | `\|warehouse` |
| Named branch + default warehouse | `http://host:port/iceberg/mybranch` | `mybranch` |
| Named branch + named warehouse | `http://host:port/iceberg/mybranch\|warehouse` | `mybranch\|warehouse` |

The pipe character (`|`) is the mandatory delimiter between branch and warehouse segments.

**Critical design point from Nessie docs:** "Don't set `prefix` for Nessie. If you want to connect to Nessie using a different branch, append the branch or tag name to the `uri` parameter."

This means for Nessie, the branch is encoded either:
1. **As part of the URI itself** (appended to the base endpoint), OR
2. **As the prefix** returned by the server's config endpoint

**Confidence:** HIGH -- verified from [official Nessie documentation](https://projectnessie.org/guides/iceberg-rest/).

### Implication for Implementation

Since `RESTCatalog`'s prefix is immutable after initialization, you **cannot change the branch on a single `RESTCatalog` instance**. The approach must be:

**Option A (Recommended): Branch-specific RESTCatalog instances**
- Create a new `RESTCatalog` instance per branch, with the branch name baked into the `CatalogProperties.URI` (e.g., `http://host:port/iceberg/mybranch`)
- Cache these instances with expiration (the `ExpiringCatalogCache` pattern already exists)
- The `RESTCatalog` will call the Nessie server's config endpoint, which returns the appropriate prefix

**Option B: Manipulate the URI at construction time**
- Instead of setting `prefix`, append the branch name to the endpoint URI when creating the catalog
- This follows the Nessie docs recommendation exactly

Both options converge on the same mechanism: **create a new `Supplier<Catalog>` that includes the branch name in the URI**.

---

## Question 3: What Nessie REST API endpoints are relevant?

### For Branch/Reference Resolution (Nessie API v2)

These endpoints are used by the Nessie Java client (`NessieApiV2`), **not** the Iceberg REST client:

| Endpoint | Method | Purpose | Dremio Usage |
|----------|--------|---------|-------------|
| `/api/v2/config` | GET | Server configuration, including default branch and spec version | `NessiePluginUtils.validateNessieSpecificationVersion()` |
| `/api/v2/trees` | GET | List all references (branches + tags) | `NessieClient.listBranches()`, `listTags()`, `listReferences()` |
| `/api/v2/trees/{ref}` | GET | Get a specific reference by name | `NessieClient.resolveVersionContext()` |
| `/api/v2/trees/{ref}/entries` | GET | List entries (tables/views/namespaces) on a ref | `NessieClient.listEntries()` |

### For Table Operations (Iceberg REST Catalog -- used by Nessie's Iceberg REST integration)

These are the standard Iceberg REST endpoints, served by Nessie at `/iceberg/`:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/iceberg/v1/config` | GET | Catalog configuration (returns `prefix`, `defaults`, `overrides`) |
| `/iceberg/v1/{prefix}/namespaces` | GET | List namespaces |
| `/iceberg/v1/{prefix}/namespaces/{ns}/tables` | GET | List tables in namespace |
| `/iceberg/v1/{prefix}/namespaces/{ns}/tables/{table}` | GET | Load table metadata |
| `/iceberg/v1/{prefix}/namespaces/{ns}/views` | GET | List views in namespace |
| `/iceberg/v1/{prefix}/namespaces/{ns}/views/{view}` | GET | Load view metadata |

**For our read-only use case**, only the GET endpoints matter.

**Confidence:** HIGH -- endpoints verified from Nessie documentation and Iceberg REST spec.

---

## Question 4: Does Dremio's existing Nessie source use the Nessie Java client or raw REST? What can we reuse?

### Existing Nessie Source Architecture

The existing **NESSIE source type** (`NessiePlugin` / `DataplanePlugin`) uses a **dual-client architecture**:

1. **Nessie Java Client (`NessieApiV2`)** -- for version control operations:
   - Built via `NessieClientBuilder.createClientBuilder("HTTP", null).withUri(...)` in `NessiePluginUtils.getNessieRestClient()`
   - Wrapped in `NessieClientImpl` then `UsernameAwareNessieClientImpl`
   - Used for: `resolveVersionContext()`, `getDefaultBranch()`, `listBranches()`, `listTags()`, `createBranch()`, `commitTable()`, etc.
   - Talks to Nessie API v2 (`/api/v2/...`)

2. **Direct Iceberg metadata file reading** -- for table data:
   - Uses `StaticTableOperations` with metadata location from Nessie content
   - Reads Iceberg metadata JSON files directly from object storage (S3/ADLS/GCS)
   - Does **NOT** use the Iceberg REST Catalog client at all

### Existing RESTCATALOG Source Architecture

The **RESTCATALOG source type** (`RestIcebergCatalogPlugin`) uses:

1. **Iceberg `RESTCatalog` client** -- for everything:
   - Built via `CatalogUtil.loadCatalog(RESTCatalog.class.getName(), ...)` in `RestIcebergCatalogPlugin.createRestCatalog()`
   - Properties include `CatalogProperties.URI` and `CatalogProperties.CATALOG_IMPL`
   - Cached in `ExpiringCatalogCache` with configurable TTL
   - Talks to Iceberg REST endpoints (`/v1/config`, `/v1/{prefix}/namespaces/...`, etc.)

### What We Can Reuse

| Component | From | Can Reuse? | How |
|-----------|------|-----------|-----|
| `NessieClientBuilder` + `NessieApiV2` | `plugins/dataplane` | YES | For resolving version context, getting default branch, listing branches/tags |
| `NessieClient` interface | `sabot/kernel` | PARTIAL | Reuse `resolveVersionContext()` and `getDefaultBranch()`. Do NOT need commit/merge/write operations |
| `NessieClientImpl` | `sabot/kernel` | YES | Lightweight wrapper, just needs `NessieApiV2` + `OptionManager` |
| `VersionContext` / `ResolvedVersionContext` | `services/catalog` | YES | Already imported by icebergcatalog plugin |
| `VersionContextResolverImpl` | `sabot/kernel` | YES | Already works with any `VersionedPlugin` implementation |
| `RESTCatalog` (Iceberg) | `iceberg-core` | YES | Already used by RESTCATALOG -- just need branch-specific instances |
| `ExpiringCatalogCache` | `plugins/icebergcatalog` | YES | Adapt to cache per-branch catalog instances |
| `AbstractRestCatalogAccessor` | `plugins/icebergcatalog` | YES | Base for branch-aware accessor |
| `NessiePluginUtils.getNessieRestClient()` | `plugins/dataplane` | YES | Factory for NessieApiV2 instances |

### What NOT to Reuse / Duplicate

| Component | Why Not |
|-----------|---------|
| `DataplanePlugin` | Massive class (~2000 lines), tightly coupled to native Nessie source, handles write operations, GC, UDFs |
| `IcebergNessieVersionedModel` | Uses native Nessie API for table operations, not REST catalog |
| `TransientIcebergMetadataProvider` | Specific to native Nessie metadata caching pattern |
| `NessieDataplaneCache*` | Complex caching infrastructure for native Nessie, overkill for REST prefix approach |

**Confidence:** HIGH -- all verified by reading source code.

---

## Question 5: Are there Iceberg library classes/interfaces for versioned catalog operations?

### Iceberg Built-in Versioning Support: Minimal

The Iceberg library itself has **no built-in concept of branch/version-aware catalogs**. The `Catalog`, `SupportsNamespaces`, and `ViewCatalog` interfaces are all version-agnostic. There is no `VersionedCatalog` interface in Iceberg core.

The `iceberg-nessie` module exists but it provides `NessieCatalog`, which talks to the **native Nessie API** (not the Iceberg REST spec). This is what the existing NESSIE source type uses.

### Relevant Iceberg Classes

| Class | Module | Relevance |
|-------|--------|-----------|
| `RESTCatalog` | `iceberg-core` | The client we use. Immutable prefix after init. |
| `RESTSessionCatalog` | `iceberg-core` | Internal delegate of RESTCatalog. Has `ResourcePaths paths` field. |
| `ResourcePaths` | `iceberg-core` | Constructs URLs with prefix. `forCatalogProperties(map)` reads `"prefix"` key. |
| `CatalogProperties` | `iceberg-core` | Constants: `URI`, `CATALOG_IMPL`, `TABLE_DEFAULT_PREFIX`, `TABLE_OVERRIDE_PREFIX`. No `PREFIX` constant -- the key is literal string `"prefix"`. |
| `CatalogUtil.loadCatalog()` | `iceberg-core` | Factory method used by `RestIcebergCatalogPlugin.createRestCatalog()`. |

### Key Finding: No `CatalogProperties.PREFIX` Constant

The `CatalogProperties` class in Iceberg 1.7.0 (Dremio's version) has `TABLE_DEFAULT_PREFIX` and `TABLE_OVERRIDE_PREFIX` but **not** a `PREFIX` constant. The `ResourcePaths.forCatalogProperties()` method reads the literal key `"prefix"` from the properties map. This prefix is typically returned by the server's config endpoint response, not set by the client.

**Confidence:** HIGH -- verified by bytecode decompilation of the actual JAR.

---

## Recommended Stack for This Milestone

### NO New Dependencies Required

The entire feature can be built using libraries already in the Dremio dependency tree:

| Category | Technology | Version | Already In | Purpose |
|----------|-----------|---------|-----------|---------|
| Nessie Java Client | `org.projectnessie.nessie:nessie-client` | `0.100.3` | `sabot/kernel` | Resolve branches/tags, get default branch |
| Nessie Model | `org.projectnessie.nessie:nessie-model` | `0.100.3` | `plugins/dataplane` | Reference, Branch, Tag types |
| Iceberg REST Client | `org.apache.iceberg:iceberg-core` (RESTCatalog) | `1.7.0-custom` | `sabot/kernel` (transitive) | Branch-specific catalog instances |
| Version Model | `com.dremio:dremio-services-catalog-api` (VersionContext) | `${project.version}` | `services/catalog` | VersionContext, ResolvedVersionContext |

### Dependencies to Add to `plugins/icebergcatalog/pom.xml`

```xml
<!-- Nessie client for branch resolution -->
<dependency>
  <groupId>org.projectnessie.nessie</groupId>
  <artifactId>nessie-client</artifactId>
  <!-- Version managed by nessie-bom in root pom.xml: 0.100.3 -->
</dependency>

<!-- Nessie model for Reference/Branch/Tag types -->
<dependency>
  <groupId>org.projectnessie.nessie</groupId>
  <artifactId>nessie-model</artifactId>
  <!-- Version managed by nessie-bom in root pom.xml: 0.100.3 -->
</dependency>
```

These are **existing Dremio dependencies** (already in the BOM), just not yet declared in the icebergcatalog plugin module. No new third-party libraries enter the dependency tree.

### What NOT to Add

| Library | Why Not |
|---------|---------|
| `iceberg-nessie` | Uses native Nessie API for table ops; we use Iceberg REST instead |
| `nessie-gc-base` / `nessie-gc-iceberg` | Only needed for GC operations; read-only scope |
| `nessie-compatibility-*` | Test-only for Nessie server compatibility testing |
| Any new HTTP client | `nessie-client` already bundles HTTP client support |

---

## Integration Architecture

### How the Pieces Fit Together

```
SQL: SELECT * FROM restcatalog_source.ns.table AT BRANCH dev

                    +-------------------+
                    |  SQL Parser       |
                    |  (AT BRANCH dev)  |
                    +--------+----------+
                             |
                             v
                    +-------------------+
                    | VersionContext     |
                    | .ofBranch("dev")   |
                    +--------+----------+
                             |
                             v
                    +-------------------+
                    | VersionContext     |
                    | ResolverImpl       |
                    | (calls plugin's   |
                    | resolveVersion())  |
                    +--------+----------+
                             |
                             v
              +--------------+--------------+
              |  NessieAwareRestPlugin      |
              |  (new subclass or extended  |
              |   RestIcebergCatalogPlugin) |
              +--------------+--------------+
                             |
              +--------------+--------------+
              |                             |
              v                             v
    +------------------+         +---------------------+
    | NessieApiV2      |         | RESTCatalog         |
    | (resolve branch  |         | (per-branch instance|
    |  to commit hash) |         |  with URI including |
    |                  |         |  branch name)       |
    +------------------+         +---------------------+
              |                             |
              v                             v
    +------------------+         +---------------------+
    | Nessie Server    |         | Nessie Iceberg REST |
    | /api/v2/trees    |         | /iceberg/dev/v1/... |
    +------------------+         +---------------------+
```

### Key Design Decisions

1. **Branch in URI, not prefix property**: Follow Nessie docs -- append branch name to the URI, let the server's config endpoint return the prefix.

2. **One RESTCatalog per branch**: Since `ResourcePaths` is immutable, create separate instances. Cache them in a branch-keyed `ExpiringCatalogCache` variant.

3. **NessieApiV2 for branch resolution only**: Use the Nessie Java client exclusively for `getDefaultBranch()`, `resolveVersionContext()`, and `listBranches()`/`listTags()`. All table/namespace operations go through the Iceberg REST client.

4. **Implement `VersionedPlugin` partially**: The RESTCATALOG plugin needs to implement `resolveVersionContext()` to participate in Dremio's version resolution pipeline, but does NOT need write operations (commitTable, mergeBranch, etc.).

---

## Sources

- [Nessie Iceberg REST Configuration Guide](https://projectnessie.org/guides/iceberg-rest/) -- prefix format, branch-to-URI mapping
- Dremio OSS codebase (direct source analysis):
  - `/plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` -- current REST catalog plugin
  - `/plugins/dataplane/src/main/java/com/dremio/plugins/dataplane/store/NessiePlugin.java` -- existing Nessie client usage
  - `/sabot/kernel/src/main/java/com/dremio/plugins/NessieClient.java` -- Nessie client interface
  - `/sabot/kernel/src/main/java/com/dremio/exec/catalog/VersionContextResolverImpl.java` -- version resolution pipeline
- Iceberg bytecode analysis:
  - `iceberg-core-1.7.0-5f7c992-20250730084652-3bf8b99.jar` -- `ResourcePaths`, `RESTCatalog`, `CatalogProperties` decompiled
- [Nessie REST API spec](https://projectnessie.org/develop/rest/)
- [Iceberg REST Catalog spec](https://iceberg.apache.org/rest-catalog-spec/)

---

## Confidence Assessment

| Area | Confidence | Reason |
|------|------------|--------|
| Nessie versions in Dremio | HIGH | Read directly from `pom.xml` |
| Prefix immutability in RESTCatalog | HIGH | Verified by bytecode decompilation of `ResourcePaths` |
| Nessie branch-to-prefix mapping | HIGH | Verified from official Nessie docs |
| No new dependencies needed | HIGH | All libraries already in Dremio BOM |
| Integration with VersionContextResolver | HIGH | Verified from source code of `VersionContextResolverImpl` |
| VersionedPlugin partial implementation | MEDIUM | Need to verify which methods are required vs optional at compile time |
