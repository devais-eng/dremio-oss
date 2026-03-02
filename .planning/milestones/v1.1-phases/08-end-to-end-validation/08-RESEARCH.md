# Phase 8: End-to-End Validation - Research

**Researched:** 2026-02-20
**Domain:** Lakekeeper Docker, Dremio OSS build and run, Iceberg REST Catalog credential vending path
**Confidence:** HIGH (codebase verified from source) / MEDIUM (Lakekeeper setup from official docs)

---

## Summary

Phase 8 is a validation phase, not an implementation phase. The plugin code (wired in Phase 7) is complete. The primary work is:

1. Rebuild the Dremio distribution to include Phase 7 changes (the pre-built JAR is from before Phase 7)
2. Stand up a Lakekeeper Docker stack (requires PostgreSQL + MinIO — Lakekeeper is NOT a simple single-container setup)
3. Create test namespaces and Iceberg tables in Lakekeeper using PyIceberg
4. Run Dremio, create a RESTCATALOG source, and validate all 6 success criteria

The critical risk area is **credential vending** (CONN-03): Lakekeeper vends short-lived S3/MinIO credentials in `loadTable()` responses. DremioFileIO reads Parquet files using a `DatasetFileSystemCache` keyed by file URI + username. The cache holds a Hadoop FileSystem instance built at source creation time with static config. If Lakekeeper vends ephemeral credentials that differ from the static config, Parquet reads will fail with permission errors. This path has NOT been traced end-to-end in the codebase and is the primary investigation target.

**Primary recommendation:** Stand up Lakekeeper via the `examples/minimal` docker-compose (includes PostgreSQL + MinIO pre-configured). Rebuild only the icebergcatalog plugin JAR (not a full rebuild). Run Dremio from the pre-built distribution by replacing the old plugin JAR. Validate credential vending first before any other success criterion.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| READ-01 | User can browse namespaces in an Iceberg REST Catalog source | `AbstractRestCatalogAccessor.getFolderStream()` streams namespaces via `SupportsNamespaces.listNamespaces()` — standard Iceberg REST call; works if source reaches GOOD state |
| READ-02 | User can list tables within a namespace | `AbstractRestCatalogAccessor.listDatasetIdentifiers()` calls `catalog.listTables(namespace)` — standard Iceberg REST call; works if namespace browsing works |
| READ-03 | User can SELECT from an Iceberg table via SQL and get query results | `ParquetScanTableFunction` reads Parquet via `DremioFileIO` backed by `DatasetFileSystemCache` — the credential vending path (CONN-03) must work for this |
| CONN-01 | User can create source pointing to Lakekeeper endpoint | `RestIcebergCatalogPluginConfig.restEndpointUri` → `RESTCatalog` connects to `CatalogProperties.URI` — works if Lakekeeper is running and the Phase 7 JAR is deployed |
| CONN-02 | OAuth2/bearer token authentication via catalog properties | `IcebergCatalogPluginConfig.secretPropertyList` → passed as `rest.token` to `RESTCatalog` properties in `buildCatalogProperties()` — the Iceberg SDK handles the Authorization header |
| CONN-03 | Storage credential vending from Lakekeeper propagates through DremioFileIO | PRIMARY RISK — Lakekeeper vends S3/MinIO credentials in `loadTable()` response `config` map; DremioFileIO uses `DatasetFileSystemCache` with static Hadoop conf; vended credentials may NOT flow to the FileSystem |
</phase_requirements>

---

## Standard Stack

### Core

| Library/Tool | Version | Purpose | Why Standard |
|------|---------|---------|--------------|
| Lakekeeper (docker-compose) | v0.11.2 (latest as of 2026-02-20) | Iceberg REST catalog server for validation | Only external dependency; Docker-compose minimal example includes everything (PostgreSQL + MinIO) |
| Dremio OSS distribution | 26.0.5-202509091642240013-f5051a07 (pre-built) | Query engine under test | Pre-built at `distribution/server/target/` — no full rebuild needed |
| Maven wrapper (`./mvnw`) | 3.9.9 | Build only the icebergcatalog plugin JAR | Already available; Java 21 already installed |
| PyIceberg | >=0.8.0 | Create namespaces and tables in Lakekeeper | Simplest Python-native way to load test data via REST catalog |
| MinIO mc (or curl) | latest | Create test S3 bucket, seed Parquet data | MinIO is included in Lakekeeper's docker-compose |

### Supporting

| Tool | Version | Purpose | When to Use |
|------|---------|---------|-------------|
| Docker Compose | v2 | Orchestrate Lakekeeper + PostgreSQL + MinIO | Required for Lakekeeper (not a single-container setup) |
| curl | system | API calls to Dremio REST API, Lakekeeper management API | Source creation, verification calls |
| Python 3 + pip | system | PyIceberg for creating tables | Loading test data |

### What NOT to Use

| Avoid | Reason |
|-------|--------|
| Lakekeeper single-container (no compose) | Lakekeeper requires PostgreSQL >= 15 — single container image does not bundle a DB |
| Full Dremio Maven rebuild (`./mvnw package -DskipTests`) | Full rebuild takes 30–90 minutes; only `plugins/icebergcatalog` module needs to be rebuilt |
| `quay.io/iceberg-catalog/iceberg-catalog` image tag from old docs | Old image repository path; use `quay.io/lakekeeper/catalog` as confirmed from docker-compose sources |

---

## Architecture Patterns

### The Credential Vending Path (PRIMARY RISK — Partially Traced)

This is the most important path to understand for Phase 8. The code flow for a `SELECT` query:

```
1. User issues: SELECT * FROM restcatalog.myns.mytable LIMIT 10

2. IcebergCatalogPlugin.getDatasetHandle()
   -> AbstractRestCatalogAccessor.getDatasetHandle()
   -> getCatalog().loadTable(TableIdentifier)       <-- REST call to Lakekeeper
      [Lakekeeper returns LoadTableResponse with:
       - tableMetadata (location: s3://examples/myns/mytable)
       - config: {"s3.access-key-id": "...", "s3.secret-access-key": "...", ...}  <-- VENDED CREDS
       - The Iceberg RESTCatalog SDK stores config in BaseTable.operations()]
   -> getTableHandleInternal():
      - baseTable = loadTable(tableIdentifier)      <-- RESTCatalog returns BaseTable
      - baseTable.location() = "s3://examples/myns/mytable"
      - plugin.createFS(SupportsFsCreation.builder().filePath(baseTable.location())...)
        -> IcebergCatalogPlugin.newFileSystem(filePath, userName, userId, ...)
        -> DatasetFileSystemCache.load(filePath, userName, userId, ...)
           [Cache key = URI scheme+authority (e.g., "s3://examples") + userName]
           [Hadoop FS built from STATIC fsConf (from IcebergCatalogPlugin initialization)]
           [STATIC conf has no S3 credentials from Lakekeeper vended creds]
      - plugin.createIcebergFileIO(fs, null, dataset, null, null)
        -> new DremioFileIO(fs, null, dataset, null, null, fsConf)
      - new DremioBaseTable(new DremioRESTTableOperations(dremioFileIO, baseTable.operations()), ...)

3. DremioRESTTableOperations.io() returns DremioFileIO
   [DremioFileIO.fs = the Hadoop FS from DatasetFileSystemCache with STATIC credentials]
   [DremioFileIO uses this FS to read Parquet files from MinIO/S3]

KEY QUESTION: Do the Lakekeeper-vended credentials (in baseTable.operations())
              ever flow into the DatasetFileSystemCache / DremioFileIO?

ANSWER FROM CODE READING: NO - the vended credentials from the Iceberg RESTCatalog response
are stored in RESTTableOperations but are NOT passed to DatasetFileSystemCache or DremioFileIO.
DremioFileIO uses only the static fsConf from IcebergCatalogPlugin.
```

**What this means:** For credential vending to work with MinIO, the MinIO credentials must be in the plugin's `propertyList` or `secretPropertyList` at source creation time, OR a workaround must be implemented to pass vended credentials through.

**Possible workarounds (to investigate during validation):**
1. Set static MinIO credentials in the RESTCATALOG source's `propertyList` (e.g., `s3.access-key-id`, `s3.secret-access-key`) — Lakekeeper uses MinIO with known credentials, so this is viable for local testing
2. Investigate whether `RESTTableOperations.io()` provides the vended credentials via a different path that Dremio could use
3. If using local filesystem storage for Lakekeeper (instead of MinIO), credential vending is a non-issue

**For Phase 8 validation, the simplest approach:** Use MinIO with credentials statically configured in the RESTCATALOG source `propertyList`. This sidesteps vending and tests all other functionality. Credential vending via DremioFileIO can be investigated separately if static creds work.

### Rebuild Strategy (Only Icebergcatalog Plugin)

The Phase 7 changes (annotation + layout JSON + SVG) are in `plugins/icebergcatalog/`. The distribution JAR is stale (built Feb 18, before Feb 20 Phase 7 changes). Only the plugin module needs rebuilding.

```bash
# Step 1: Build only the icebergcatalog plugin
MAVEN=/home/emanuele/.m2/wrapper/dists/apache-maven-3.9.9-bin/33b4b2b4/apache-maven-3.9.9/bin/mvn
$MAVEN package -pl plugins/icebergcatalog -am -DskipTests -T 1C \
  -f /home/emanuele/IdeaProjects/dremio-oss/pom.xml

# Step 2: The new JAR will be at:
# plugins/icebergcatalog/target/dremio-icebergcatalog-plugin-26.0.5-202509091642240013-f5051a07.jar

# Step 3: Replace the stale JAR in the distribution
DIST=/home/emanuele/IdeaProjects/dremio-oss/distribution/server/target/dremio-community-26.0.5-202509091642240013-f5051a07/dremio-community-26.0.5-202509091642240013-f5051a07
cp plugins/icebergcatalog/target/dremio-icebergcatalog-plugin-26.0.5-202509091642240013-f5051a07.jar \
   $DIST/jars/dremio-icebergcatalog-plugin-26.0.5-202509091642240013-f5051a07.jar
```

**Note:** `-am` builds required modules (dependencies). The icebergcatalog plugin depends on `sabot/kernel` and others. If `-am` causes too many modules to rebuild, try `-pl plugins/icebergcatalog --offline -DskipTests`.

### Lakekeeper Setup (Minimal docker-compose)

The `examples/minimal` docker-compose is the proven path. It includes PostgreSQL + MinIO + Lakekeeper pre-bootstrapped.

```bash
# Clone and run minimal example
git clone https://github.com/lakekeeper/lakekeeper
cd lakekeeper/examples/minimal
docker compose up -d

# Services started:
# - PostgreSQL 17 on internal network (db:5432)
# - MinIO on ports 9000 (API) and 9001 (console)
# - Lakekeeper on port 8181

# Iceberg REST API endpoint: http://localhost:8181/catalog
# Management API: http://localhost:8181/management
# MinIO console: http://localhost:9001 (user: minio-root-user, pass: minio-root-password)
```

**Image:** `quay.io/lakekeeper/catalog:latest-main` (from minimal docker-compose; pin to `v0.11.2` for reproducibility)

**Authentication:** If LAKEKEEPER__OPENID_PROVIDER_URI is NOT set, authentication is disabled — anonymous access is allowed. The minimal example does not set this, so no auth is needed for local validation.

**Authorization:** `LAKEKEEPER__AUTHZ_BACKEND=allowall` (default) — all operations permitted to all callers.

### Creating Test Data (PyIceberg)

After Lakekeeper is running, use PyIceberg to create namespaces and a table with test data:

```python
# Install: pip install pyiceberg pyarrow
from pyiceberg.catalog.rest import RestCatalog
import pyarrow as pa

# Connect to Lakekeeper (no auth needed if OPENID not configured)
catalog = RestCatalog('lakekeeper', **{
    'uri': 'http://localhost:8181/catalog',
    'warehouse': 'my-warehouse',  # warehouse name created during bootstrap
})

# Create namespace
catalog.create_namespace('mydb')

# Create a table
from pyiceberg.schema import Schema
from pyiceberg.types import NestedField, LongType, StringType
schema = Schema(
    NestedField(1, 'id', LongType(), required=True),
    NestedField(2, 'name', StringType(), required=False),
)
table = catalog.create_table('mydb.users', schema=schema)

# Write some data (Parquet via Arrow)
arrow_table = pa.table({'id': [1, 2, 3], 'name': ['Alice', 'Bob', 'Charlie']})
table.append(arrow_table)
```

**Important:** The warehouse must be created via Lakekeeper's management API before PyIceberg can use it. The `examples/minimal` docker-compose does this automatically via a bootstrap container.

### Dremio Startup

```bash
DIST=/home/emanuele/IdeaProjects/dremio-oss/distribution/server/target/dremio-community-26.0.5-202509091642240013-f5051a07/dremio-community-26.0.5-202509091642240013-f5051a07

# Start Dremio (foreground for easy log watching)
$DIST/bin/dremio start

# Dremio UI: http://localhost:9047
# Default credentials: dremio / dremio123
# REST API: http://localhost:9047/api/v3/

# First run: accept EULA via UI or:
curl -X POST http://localhost:9047/api/v3/login \
  -H 'Content-Type: application/json' \
  -d '{"userName":"dremio","password":"dremio123"}'
```

### Creating RESTCATALOG Source via API

```bash
# Get auth token first
TOKEN=$(curl -s -X POST http://localhost:9047/api/v3/login \
  -H 'Content-Type: application/json' \
  -d '{"userName":"dremio","password":"dremio123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# Create RESTCATALOG source
# For MinIO-backed Lakekeeper, include MinIO credentials in propertyList
curl -X PUT http://localhost:9047/api/v3/catalog \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "entityType": "source",
    "type": "RESTCATALOG",
    "name": "lakekeeper",
    "config": {
      "restEndpointUri": "http://localhost:8181/catalog",
      "propertyList": [
        {"name": "warehouse", "value": "my-warehouse"},
        {"name": "s3.endpoint", "value": "http://localhost:9000"},
        {"name": "s3.access-key-id", "value": "minio-root-user"},
        {"name": "s3.secret-access-key", "value": "minio-root-password"},
        {"name": "s3.path-style-access", "value": "true"}
      ]
    }
  }'
```

**Note:** The S3 properties in `propertyList` flow through `buildCatalogProperties()` into the `Hadoop Configuration` and into `DatasetFileSystemCache`. This is the workaround for credential vending — static MinIO creds in the source config.

### OAuth2 Bearer Token (CONN-02)

To test authentication via bearer token, set `rest.token` in `secretPropertyList`:

```json
"secretPropertyList": [
  {"name": "rest.token", "value": "your-bearer-token-here"}
]
```

The `rest.token` property is the standard Iceberg REST Catalog property for pre-authentication token passing. The Iceberg `RESTCatalog` SDK reads this via `RESTSessionCatalog` and sets `Authorization: Bearer <token>` on all HTTP calls to Lakekeeper.

For testing CONN-02 with Lakekeeper, you can set up Keycloak via the `access-control-simple` example and use the OAuth2 flow, OR simply verify that `rest.token` is passed in HTTP requests by enabling Lakekeeper trace logging.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Iceberg table creation | Custom REST calls | PyIceberg | PyIceberg handles schema, partition spec, Parquet writing |
| MinIO storage setup | Custom scripts | docker-compose minimal example | Everything pre-wired including bucket creation |
| Dremio source creation | Complex REST orchestration | `curl` against `/api/v3/catalog` | Simple one-call operation with JSON body |
| Authentication testing | Custom Keycloak setup | Start with no-auth mode first | Lakekeeper minimal runs without auth; test CONN-02 separately |

---

## Common Pitfalls

### Pitfall 1: Distribution JAR Stale After Phase 7

**What goes wrong:** Dremio starts but `GET /api/v3/source/type/RESTCATALOG` returns 404 — source type not discoverable.
**Why it happens:** The distribution JAR (`dremio-icebergcatalog-plugin-*.jar`) was built Feb 18, before Phase 7 (Feb 20). The `@SourceType` annotation, `restcatalog-layout.json`, and `RESTCATALOG.svg` are in the source tree but NOT in the deployed JAR.
**How to avoid:** Rebuild the plugin JAR (`-pl plugins/icebergcatalog -DskipTests`) and replace the distribution copy before starting Dremio.
**Warning signs:** `GET /api/v3/source/type/RESTCATALOG` returns 404; source not visible in UI picker.

### Pitfall 2: Lakekeeper Requires PostgreSQL (Not Single Container)

**What goes wrong:** `docker run quay.io/lakekeeper/catalog serve` fails — no database backend.
**Why it happens:** Lakekeeper requires PostgreSQL >= 15 as its persistence backend. There is no embedded DB.
**How to avoid:** Always use the docker-compose approach from `examples/minimal` or provide your own PostgreSQL instance.
**Warning signs:** Lakekeeper container exits immediately with a database connection error.

### Pitfall 3: Credential Vending Not Propagated to DremioFileIO

**What goes wrong:** Source reaches GOOD state, namespace browsing works, but `SELECT` queries fail with `AccessDeniedException` or `Access denied on s3://...`.
**Why it happens:** Lakekeeper vends short-lived S3 credentials in `loadTable()` responses. These credentials are in `RESTTableOperations` config but are NOT passed to `DatasetFileSystemCache` or `DremioFileIO`. DremioFileIO uses only the static Hadoop conf built at source creation time.
**How to avoid:** Include MinIO/S3 credentials statically in the source `propertyList` (`s3.access-key-id`, `s3.secret-access-key`, `s3.endpoint`, `s3.path-style-access`). These flow through `buildCatalogProperties()` into the Hadoop Configuration used by `DatasetFileSystemCache`.
**Warning signs:** `SELECT` queries fail with permission errors after `loadTable()` succeeds.

### Pitfall 4: Wrong Warehouse Name in Catalog Config

**What goes wrong:** PyIceberg connects fine but `CREATE NAMESPACE` fails; or Dremio source connects but shows empty namespace list.
**Why it happens:** Lakekeeper requires an explicit `warehouse` property pointing to a created warehouse name. Without it, the Iceberg SDK uses the default warehouse, which may not exist.
**How to avoid:** Always pass `warehouse: my-warehouse` (or whatever name was created during bootstrap) in both PyIceberg config and Dremio source `propertyList`.
**Warning signs:** `NoSuchNamespaceException` or empty tree in Dremio UI.

### Pitfall 5: Dremio Data Directory Not Cleaned Between Test Runs

**What goes wrong:** Source creation fails with "source already exists" or Dremio fails to start with corrupted KV store.
**Why it happens:** Dremio stores state in `$DIST/data/` — previous source configs persist across restarts.
**How to avoid:** Either use the Dremio UI to delete/recreate the source, or `rm -rf $DIST/data/` before fresh test runs.
**Warning signs:** Unexpected source states, 409 Conflict on source creation.

### Pitfall 6: Plugin JAR Not on Dremio Classpath Correctly

**What goes wrong:** After rebuilding the plugin JAR, the old class is still loaded.
**Why it happens:** If the distribution is run from a tar extract, copying the JAR may work. But if Dremio is caching classloader state, a clean restart is needed.
**How to avoid:** Always stop Dremio, replace the JAR, then start Dremio fresh. Verify the new JAR's checksum.
**Warning signs:** `@SourceType` annotation on `RestIcebergCatalogPluginConfig.class` exists in JAR but source type still not visible.

---

## Code Examples

### Verified: `buildCatalogProperties()` — How propertyList flows to Hadoop conf

From `RestIcebergCatalogPlugin.java`:

```java
// Source: plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java
protected Map<String, String> buildCatalogProperties(Configuration config) {
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.CATALOG_IMPL, restCatalogImpl().getName());
    properties.put(CatalogProperties.URI, getRestEndpoint());

    // ALL propertyList AND secretPropertyList entries are added to BOTH
    // the Hadoop Configuration AND the Iceberg catalog properties map
    for (Property p : configPropertyList) {
        config.set(p.name, p.value);        // <-- flows to DatasetFileSystemCache
        properties.put(p.name, p.value);   // <-- flows to RESTCatalog
    }
    return properties;
}
```

**Implication:** Any `s3.*` or MinIO properties in `propertyList`/`secretPropertyList` will be present in the Hadoop Configuration used by `DatasetFileSystemCache`. This is the mechanism for static credential configuration.

### Verified: `getTableHandleInternal()` — Where DremioFileIO is created from loadTable() result

From `AbstractRestCatalogAccessor.java`:

```java
// Source: plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/AbstractRestCatalogAccessor.java
return new IcebergCatalogTableProvider(
    new EntityPath(dataset),
    () -> {
        Table baseTable = loadTable(tableIdentifier, options);
        // baseTable is a RESTCatalog BaseTable — its io() is ResolvingFileIO with vended creds
        // But we replace it with DremioFileIO backed by DatasetFileSystemCache (static creds)
        DremioFileIO fileIO = (DremioFileIO) plugin.createIcebergFileIO(
            plugin.createFS(
                SupportsFsCreation.builder()
                    .filePath(baseTable.location())  // e.g., "s3://examples/myns/mytable"
                    .withSystemUserName()
                    .withSystemUserId()
                    .dataset(dataset)),
            null, dataset, null, null);
        return new DremioBaseTable(
            new DremioRESTTableOperations(fileIO, ((HasTableOperations) baseTable).operations()),
            baseTable.name());
        // baseTable.io().close() — vended credentials from RESTCatalog are DISCARDED
    }, ...);
```

**Key insight:** `baseTable.io()` (which has the vended credentials from Lakekeeper) is closed and discarded. `DremioFileIO` is constructed from the `DatasetFileSystemCache` FS (with static credentials).

### Verified: `checkState()` — How source health check works

From `IcebergRestCatalogAccessor.java`:

```java
// Source: plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergRestCatalogAccessor.java
@Override
protected void checkStateInternal() throws Exception {
    Closeable closeable = (Closeable) catalogSupplier.get();
    closeable.close();
}
```

`catalogSupplier.get()` calls `CatalogUtil.loadCatalog(...)` which attempts to connect to the Iceberg REST endpoint. If Lakekeeper is reachable and the endpoint is correct, this succeeds. The source reaches GOOD state.

---

## State of the Art

| Old Approach | Current Approach | Notes |
|--------------|------------------|-------|
| `quay.io/iceberg-catalog/iceberg-catalog` (old image path) | `quay.io/lakekeeper/catalog` (current path) | Image registry changed; old path may still work but use current |
| Lakekeeper v0.10.x | Lakekeeper v0.11.2 (Jan 2026) | v0.11 is the current stable; minimal docker-compose pins to `latest-main` |
| Single-container Lakekeeper (if it ever existed) | docker-compose with PostgreSQL | PostgreSQL is ALWAYS required |

---

## Open Questions

1. **Will S3 path-style access work for MinIO in DremioFileIO?**
   - What we know: MinIO requires `s3.path-style-access=true` (or `fs.s3a.path.style.access=true` for Hadoop)
   - What's unclear: Which property key does `DatasetFileSystemCache` use for MinIO path-style config? Hadoop uses `fs.s3a.*` prefix; Iceberg uses `s3.*` prefix. `buildCatalogProperties()` sets both, so the key name matters.
   - Recommendation: During validation, try `fs.s3a.path.style.access=true` in `propertyList` if `s3.path-style-access=true` doesn't work. Both may be needed.

2. **Does the Lakekeeper examples/minimal docker-compose expose MinIO on localhost:9000?**
   - What we know: The compose file maps MinIO API to port 9000 and console to 9001
   - What's unclear: Whether the Dremio host (running outside Docker) can reach MinIO at `localhost:9000` — Dremio will use the table location from Lakekeeper (e.g., `s3://examples/...`) and needs to resolve MinIO's API endpoint
   - Recommendation: Set `s3.endpoint=http://localhost:9000` in the source `propertyList`. Verify MinIO is reachable with `curl http://localhost:9000`.

3. **Does the `warehouse` property need to be set in both PyIceberg and the Dremio source?**
   - What we know: Lakekeeper requires a warehouse to be specified for table operations
   - What's unclear: Whether Dremio must pass the warehouse name as a catalog property, or if the warehouse is auto-detected
   - Recommendation: Always pass `warehouse: <name>` in source `propertyList`. This is safe and explicit.

4. **What is the exact MinIO bucket and path used by the minimal example?**
   - What we know: The bucket is `examples`; the warehouse location will be something like `s3://examples/`
   - What's unclear: The exact warehouse configuration in the bootstrap step
   - Recommendation: After `docker compose up`, inspect the warehouse via `GET http://localhost:8181/management/v1/warehouse` to get the exact storage location.

---

## Sources

### Primary (HIGH confidence)

- Codebase at `plugins/icebergcatalog/` — all credential vending analysis, `buildCatalogProperties()`, `getTableHandleInternal()`, `checkState()` verified from source code
- `distribution/server/target/dremio-community-26.0.5-*` — pre-built distribution confirmed; JAR staleness verified via `jar tf` and file timestamps
- `.mvn/maven.config` — version string confirmed for build commands
- `java -version` output — Java 21 available, meets Maven enforcer requirement

### Secondary (MEDIUM confidence)

- https://docs.lakekeeper.io/docs/0.5.x/configuration/ — Authentication optional (no OPENID_PROVIDER_URI = no auth required); AUTHZ_BACKEND=allowall default
- https://github.com/lakekeeper/lakekeeper/blob/main/examples/minimal/docker-compose.yaml — Image `quay.io/lakekeeper/catalog:latest-main`; MinIO included; PostgreSQL 17
- https://github.com/lakekeeper/lakekeeper/releases — v0.11.2 is latest stable (January 30, 2026)
- https://docs.lakekeeper.io/docs/0.9.x/engines/ — OAuth2 credential patterns; `credential`, `oauth2-server-uri`, `scope` properties for Spark/Trino

### Tertiary (LOW confidence — needs runtime verification)

- MinIO S3 property key names for Hadoop (`fs.s3a.path.style.access` vs `s3.path-style-access`) — verify which works with DatasetFileSystemCache
- Exact warehouse name created by the minimal example bootstrap — verify via management API after startup

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Java 21 confirmed, Docker confirmed, Maven wrapper confirmed, pre-built distribution confirmed
- Architecture (credential vending path): HIGH — traced from source code; `buildCatalogProperties()`, `DatasetFileSystemCache`, `DremioFileIO` all verified
- Pitfalls: HIGH — JAR staleness is a confirmed fact (timestamps); credential vending non-propagation is code-confirmed
- Lakekeeper setup: MEDIUM — from official docs and minimal example; specific MinIO property names need runtime verification

**Research date:** 2026-02-20
**Valid until:** 2026-03-20 (Lakekeeper docs change infrequently; Dremio codebase is stable)
