# Technology Stack — v1.1 Enable Iceberg REST Catalog

**Project:** Dremio OSS Enhancements
**Milestone:** v1.1 — Wire up existing Iceberg REST Catalog plugin for read-only use
**Researched:** 2026-02-20

---

## Summary

The stack for v1.1 requires **zero new dependencies and zero new Maven modules**. The plugin code is complete. What is missing is three artifacts that connect the implementation to Dremio's source registration system:

1. A `@SourceType` annotation on `RestIcebergCatalogPluginConfig` — the single hook that makes `ConnectionReaderImpl.makeReader()` discover the plugin
2. A UI layout JSON file in `plugins/icebergcatalog/src/main/resources/` — consumed by `SourceTypeTemplate.fromSourceClass()`
3. A Lakekeeper instance (Docker) for end-to-end validation

Everything else — classpath scanning, source type visibility gating, icon serving — is already wired to handle `"RESTCATALOG"` by type name.

---

## 1. The Core Wiring Mechanism (HIGH confidence)

### How Dremio discovers source plugins

`ConnectionReaderImpl.makeReader(ScanResult)` at `sabot/kernel/src/main/java/com/dremio/exec/catalog/ConnectionReaderImpl.java` scans classpath for all classes with `@SourceType`. For each non-abstract concrete class that extends `ConnectionConf`, it registers the class under its `@SourceType.value()` string.

The package `com.dremio.plugins.icebergcatalog` is already declared in `plugins/icebergcatalog/src/main/resources/sabot-module.conf`:

```
dremio.classpath.scanning.packages += com.dremio.plugins.icebergcatalog
```

This means `RestIcebergCatalogPluginConfig` is already scanned. It is not discovered only because it lacks `@SourceType`. The fix is one annotation.

### How the UI gates visibility

`DeprecatedSourceResource.isSourceTypeVisible()` at `dac/backend/src/main/java/com/dremio/dac/api/DeprecatedSourceResource.java` already has a case for `"RESTCATALOG"`:

```java
case "RESTCATALOG":
    return optionManager.getOption(RESTCATALOG_PLUGIN_ENABLED);
```

`RESTCATALOG_PLUGIN_ENABLED` defaults to `true` (`plugins.restcatalog.enabled`). The source type will appear in the UI source picker as soon as the annotation is present — no option changes needed.

### How icons are served

`SourceTypeTemplate.fromSourceClass()` loads `{sourceType.value()}.svg` from the classloader:

```java
final URL resource = sourceClass.getClassLoader().getResource(type.value() + ".svg");
```

`RESTCATALOG.svg` already exists at `dac/ui-lib/icons/dremio/sources/RESTCATALOG.svg` and is included in the frontend build output. The icon is served by the frontend asset pipeline, not the plugin's own resources. No action needed.

---

## 2. The `@SourceType` Annotation (HIGH confidence)

**File to modify:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java`

**Exact annotation to add:**

```java
@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")
public class RestIcebergCatalogPluginConfig extends IcebergCatalogPluginConfig {
```

**Rationale for each parameter:**

- `value = "RESTCATALOG"` — This exact string is required. `DeprecatedSourceResource.isSourceTypeVisible()` already matches on `"RESTCATALOG"` (line 231). Using any other value would cause the visibility gate to fall to the `default: return true` branch and bypass feature flag control.
- `label = "Iceberg REST Catalog"` — Human-readable name shown in the UI source picker. Follows the pattern of `label = "Amazon S3"`, `label = "Nessie"`, `label = "Elasticsearch"`.
- `uiConfig = "restcatalog-layout.json"` — Points to the UI layout file. If omitted, the UI falls back to reflecting `@Tag`-annotated fields directly. Use `uiConfig` to control field ordering and grouping.
- `configurable = true` (default) — Source can be created/edited via UI. Do not set to false.
- `listable = true` (default) — Source appears in the source type picker. Do not set to false.
- `isVersioned = false` (default) — REST catalog is not a versioned catalog like Nessie. Correct.
- `externalQuerySupported = false` (default) — REST catalog does not support external SQL passthrough.

**Pattern references:**
- `@SourceType(value = "NAS", uiConfig = "nas-layout.json")` — NASConf, simplest pattern
- `@SourceType(value = "NESSIE", label = "Nessie", uiConfig = "nessie-layout.json", isVersioned = true)` — NessiePluginConfig
- `@SourceType(value = "ELASTIC", label = "Elasticsearch", uiConfig = "elastic-storage-layout.json")` — ElasticStoragePluginConfig

---

## 3. The UI Layout JSON (HIGH confidence)

**File to create:** `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json`

This file is loaded by `SourceTypeTemplate.fromSourceClass()` via `sourceClass.getClassLoader().getResourceAsStream(type.uiConfig())`. It must be in `src/main/resources/` to land on the plugin's classpath.

**Minimum viable layout** for read-only v1.1 (maps to `RestIcebergCatalogPluginConfig` and `IcebergCatalogPluginConfig` `@Tag`-annotated fields):

```json
{
  "sourceType": "RESTCATALOG",
  "metadataRefresh": {
    "isFileSystemSource": false
  },
  "form": {
    "tabs": [
      {
        "name": "General",
        "isGeneral": true,
        "sections": [
          {
            "name": "Connection",
            "elements": [
              {
                "propName": "config.restEndpointUri",
                "label": "Endpoint URI",
                "placeholder": "https://catalog.example.com/catalog",
                "errMsg": "Required",
                "validate": {
                  "isRequired": true
                }
              }
            ]
          },
          {
            "name": "Namespace Filter (optional)",
            "elements": [
              {
                "propName": "config.allowedNamespaces",
                "emptyLabel": "All namespaces visible",
                "addLabel": "Add namespace"
              },
              {
                "propName": "config.isRecursiveAllowedNamespaces"
              }
            ]
          }
        ]
      },
      {
        "name": "Advanced Options",
        "sections": [
          {
            "name": "Catalog Properties",
            "elements": [
              {
                "propName": "config.propertyList",
                "emptyLabel": "No properties added",
                "addLabel": "Add property"
              }
            ]
          },
          {
            "name": "Catalog Credentials",
            "elements": [
              {
                "propName": "config.secretPropertyList",
                "emptyLabel": "No credentials added",
                "addLabel": "Add credential"
              }
            ]
          },
          {
            "name": "Cache Options",
            "elements": [
              {
                "propName": "config.isCachingEnabled"
              },
              {
                "propName": "config.maxCacheSpacePct"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

**Key design decisions for this layout:**

- `"isFileSystemSource": false` — REST catalog is not a filesystem source. The Nessie layout sets this to `true` because Nessie actually has a filesystem storage backend; REST catalog does not.
- `config.restEndpointUri` — The mandatory field. Maps to `@Tag(10) public String restEndpointUri` in `RestIcebergCatalogPluginConfig`.
- `config.propertyList` — Maps to `@Tag(1) public List<Property> propertyList` in `IcebergCatalogPluginConfig`. Used to pass bearer tokens, warehouse names, or other Iceberg catalog properties.
- `config.secretPropertyList` — Maps to `@Tag(2) @Secret public List<Property> secretPropertyList`. Used for OAuth2 client secrets or API keys. Secret fields are redacted in logs and API responses.
- Async (`enableAsync`) omitted from v1.1 layout — can be added once read-only path is validated. It is on the `IcebergCatalogPluginConfig` base class but irrelevant for initial wiring.

**propName field naming convention:** All propNames use the `config.` prefix followed by the Java field name exactly. This is consistent across all layout files (Nessie uses `config.nessieEndpoint`, S3 uses `config.credentialType`).

---

## 4. Lakekeeper for End-to-End Validation (MEDIUM confidence — based on Lakekeeper public docs and project context)

### Why Lakekeeper

Lakekeeper is a production-grade open-source Iceberg REST catalog server (Apache-2.0). It is the primary target for v1.1 validation because:
- It implements the Iceberg REST Catalog spec completely
- It supports anonymous (no-auth) mode for quick testing
- Its Docker image is the simplest compliant REST catalog to stand up

### Docker setup for manual end-to-end validation

Lakekeeper's official image is `quay.io/iceberg-catalog/iceberg-catalog`. The simplest local setup:

```bash
# Start Lakekeeper in anonymous mode (no auth, in-memory storage)
docker run -d \
  --name lakekeeper \
  -p 8181:8181 \
  quay.io/iceberg-catalog/iceberg-catalog:latest \
  serve

# Lakekeeper REST endpoint is at:
# http://localhost:8181/catalog
```

When configuring the source in Dremio, use:
- `Endpoint URI`: `http://localhost:8181/catalog`
- No credentials needed for anonymous mode

**For persistent storage with a warehouse on local filesystem:**

```bash
docker run -d \
  --name lakekeeper \
  -p 8181:8181 \
  -e ICEBERG_REST__BASE_URI=http://localhost:8181 \
  -e ICEBERG_REST__WAREHOUSE_PATH=/warehouse \
  -v /tmp/lakekeeper-warehouse:/warehouse \
  quay.io/iceberg-catalog/iceberg-catalog:latest
```

**MEDIUM confidence on exact image tag and env vars** — these are derived from Lakekeeper documentation patterns. Verify the exact current release tag at `https://quay.io/repository/iceberg-catalog/iceberg-catalog` before using in a test plan.

### Automated test strategy (no Testcontainers for unit tests)

For v1.1 read-only validation, the existing test pattern is adequate:

1. **Unit tests (existing):** All in `TestRestIcebergCatalogPlugin` and `TestRestCatalogAccessor` — mock-based, Mockito. Already exist and pass. No changes needed.

2. **End-to-end validation (manual/Docker):** Start Lakekeeper via Docker, register source via Dremio UI or `PUT /api/v3/catalog` API, run SQL queries.

3. **Integration test (optional, IT suffix):** If automated integration test is desired, follow the `NatsContainerIT` pattern: JUnit 5 class ending in `IT`, `@Testcontainers` annotation, use `GenericContainer` from `testcontainers-java` with the Lakekeeper image. Requires adding `testcontainer` to `plugins/icebergcatalog/pom.xml` as test scope.

The `DremioTestcontainersUsageValidator` requires:
- Class name ends with `IT`
- System property `dremio.testcontainers.enabled=true` is set
- System property `dremio.testcontainers.validate.skip=true` OR tests run under the approved testcontainers infrastructure

For v1.1 scope (read-only validation), a Testcontainers IT test is a nice-to-have, not required. Manual Docker validation is sufficient for the milestone.

### Lakekeeper warehouse initialization for test data

After starting Lakekeeper, create a test warehouse and table:

```bash
# Create warehouse via Lakekeeper management API
curl -X POST http://localhost:8181/management/v1/warehouse \
  -H 'Content-Type: application/json' \
  -d '{"name": "test-warehouse", "location": "file:///warehouse"}'

# Then use PyIceberg or spark-sql to create tables in the REST catalog
pip install pyiceberg
python3 -c "
from pyiceberg.catalog.rest import RestCatalog
catalog = RestCatalog('test', **{'uri': 'http://localhost:8181/catalog', 'warehouse': 'test-warehouse'})
catalog.create_namespace('mydb')
from pyiceberg.schema import Schema
from pyiceberg.types import NestedField, StringType, LongType
schema = Schema(NestedField(1, 'id', LongType()), NestedField(2, 'name', StringType()))
catalog.create_table('mydb.users', schema)
"
```

---

## 5. No New Dependencies Required (HIGH confidence)

The following are already present and sufficient:

| Library | Version | Location | Status |
|---------|---------|----------|--------|
| `org.apache.iceberg:iceberg-core` | 1.7.0 (custom Dremio build) | `dremio-sabot-kernel` transitive | Already available |
| `org.apache.iceberg:iceberg-api` | 1.7.0 | same | Already available |
| `RESTCatalog` class | Iceberg 1.7.0 | `org.apache.iceberg.rest.RESTCatalog` | Already imported in `RestIcebergCatalogPlugin` |
| `ConnectionConf` | Dremio internal | `sabot/kernel` | Base class already extended |
| `@SourceType` annotation | Dremio internal | `com.dremio.exec.catalog.conf.SourceType` | Already imported in S3, GCS, Nessie, etc. |
| `io.protostuff.Tag` | Protostuff | transitive | Already on `IcebergCatalogPluginConfig` fields |
| `DisplayMetadata` | Dremio internal | Already on config fields | No change |

**What NOT to add:**
- Do not add Lakekeeper client library — the plugin uses `org.apache.iceberg.rest.RESTCatalog` directly, which speaks standard Iceberg REST spec. No Lakekeeper-specific client needed.
- Do not add WireMock or MockServer — existing tests use Mockito to mock `CatalogAccessor`. The `TestRestIcebergCatalogPlugin` test already covers the plugin layer. Adding an HTTP-level mock for Lakekeeper's REST API would test Iceberg's RESTCatalog client, not our plugin.
- Do not add testcontainers to the plugin's pom for v1.1 — unit tests are sufficient for the annotation + layout wiring validation. Manual Docker validation covers the end-to-end path.

---

## 6. Tag Number Reservation (HIGH confidence)

`IcebergCatalogPluginConfig` reserves tags 1-9. `RestIcebergCatalogPluginConfig` has comment:

```java
// 1-9   - IcebergCatalogPluginConfig
// 10-19 - RestIcebergCatalogPluginConfig
// 20-109 - Reserved by other plugins
```

Current fields use tags 10, 11, 12. Next available tag in `RestIcebergCatalogPluginConfig`: `@Tag(13)`. If new config fields are needed, use tags 13-19.

---

## 7. File Change Summary

| File | Change | Why |
|------|--------|-----|
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` | Add `@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")` | Makes plugin discoverable by classpath scanner |
| `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` | Create new file with UI layout | Required by `SourceTypeTemplate.fromSourceClass()` when `uiConfig` is non-empty |
| `plugins/icebergcatalog/pom.xml` | No change | All dependencies already present |
| `plugins/icebergcatalog/src/main/resources/sabot-module.conf` | No change | Already declares the package for scanning |

All other existing code — `IcebergRestCatalogAccessor`, `ExpiringCatalogCache`, `DatasetFileSystemCache`, `IcebergCatalogPlugin` lifecycle methods — requires no changes for the read-only wiring milestone.

---

## 8. Validation Checklist

After adding the annotation and layout JSON:

1. **Classpath scanning:** `GET /api/v3/source/type` should return `RESTCATALOG` in the source type list
2. **Icon:** Source type list entry should include the SVG icon content (served from frontend assets)
3. **UI layout:** `GET /api/v3/source/type/RESTCATALOG` should return the `uiConfig` JSON inline
4. **Source creation:** `PUT /api/v3/catalog` with `{"entityType":"source","type":"RESTCATALOG","name":"myrest","config":{"restEndpointUri":"http://localhost:8181/catalog"}}` should succeed
5. **Namespace browsing:** Source should appear in `sys.sources`, namespace listing should return Lakekeeper namespaces
6. **Table query:** `SELECT * FROM myrest.mydb.users LIMIT 10` should return rows

---

*Research: 2026-02-20. Based on analysis of Dremio OSS codebase at current HEAD (milestones/enable_iceberg_rest_catalog branch). Lakekeeper setup at MEDIUM confidence — verify Docker image tag before use.*
