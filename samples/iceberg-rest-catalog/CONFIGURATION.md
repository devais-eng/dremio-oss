# Configuration Reference

All settings for the Dremio + Nessie + Lakekeeper + Polaris + MinIO stack.

## Services Overview

| Service | Image | Internal Port | Host Port | Role |
|---|---|---|---|---|
| minio | quay.io/minio/minio | 9000, 9090 | 9000, 9090 | S3-compatible object storage |
| nessie | ghcr.io/projectnessie/nessie:latest | 19120, 9000 | 19120, 9001 | Iceberg REST Catalog (versioned) |
| lakekeeper-db | postgres:16-alpine | 5432 | (none) | Lakekeeper metadata store |
| lakekeeper | quay.io/lakekeeper/catalog:latest-main | 8181 | 8282 | Iceberg REST Catalog |
| polaris | apache/polaris:latest | 8181, 8182 | 8383 | Iceberg REST Catalog (with built-in RBAC) |
| dremio | ghcr.io/devais-eng/dremio-oss:latest | 9047, 31010, 32010 | 9047, 31010, 32010 | SQL query engine |
| keycloak | quay.io/keycloak/keycloak:26.2 | 8080 | 8080 | OIDC provider (SSO only) |

## Startup Order

```
minio
  └─► minio-init (create buckets: demobucket, lakebucket, polarisbucket)
        ├─► nessie
        ├─► polaris
        │     └─► polaris-init (get token, create catalog, grant permissions)
        └─► lakekeeper-db
              └─► lakekeeper-migrate (DB schema)
                    └─► lakekeeper
                          └─► lakekeeper-init (bootstrap + warehouse)
                                └─► dremio
```

---

## MinIO

S3-compatible object storage. Stores the actual Parquet data files.

| Setting | Value | Description |
|---|---|---|
| `MINIO_ROOT_USER` | `minioadmin` | S3 access key |
| `MINIO_ROOT_PASSWORD` | `minioadmin` | S3 secret key |
| `MINIO_ADDRESS` | `:9000` | S3 API listen address |
| `MINIO_CONSOLE_ADDRESS` | `:9090` | Web console listen address |

### Buckets

Created by `minio-init`:

| Bucket | Owner | Contents |
|---|---|---|
| `demobucket` | Nessie | Iceberg table data (metadata + parquet files) |
| `lakebucket` | Lakekeeper | Iceberg table data (metadata + parquet files) |
| `polarisbucket` | Polaris | Iceberg table data (metadata + parquet files) |

---

## Nessie

Iceberg REST Catalog with git-like versioning. Stores catalog metadata in memory (ephemeral).

### Server Configuration

| Setting | Value | Description |
|---|---|---|
| `nessie.version.store.type` | `IN_MEMORY` | Metadata storage backend (lost on restart) |
| `nessie.server.authentication.enabled` | `false` | No auth in base mode |

### Warehouse Configuration

| Setting | Value | Description |
|---|---|---|
| `nessie.catalog.default-warehouse` | `warehouse` | Default warehouse name |
| `nessie.catalog.warehouses.warehouse.location` | `s3://demobucket/` | S3 root for table data |

### S3 / MinIO Connection

| Setting | Value | Description |
|---|---|---|
| `nessie.catalog.service.s3.default-options.endpoint` | `http://minio:9000/` | MinIO S3 API (needs `http://` and trailing `/`) |
| `nessie.catalog.service.s3.default-options.external-endpoint` | `http://minio:9000/` | External S3 endpoint for credential vending |
| `nessie.catalog.service.s3.default-options.path-style-access` | `true` | Use `host/bucket/key` (required for MinIO) |
| `nessie.catalog.service.s3.default-options.region` | `us-east-1` | AWS region |

### S3 Credentials

Nessie uses a secret URN pattern for credential indirection:

| Setting | Value |
|---|---|
| `nessie.catalog.service.s3.default-options.access-key` | `urn:nessie-secret:quarkus:nessie.catalog.secrets.access-key` |
| `nessie.catalog.secrets.access-key.name` | `minioadmin` |
| `nessie.catalog.secrets.access-key.secret` | `minioadmin` |

### API Endpoints

| Path | Description |
|---|---|
| `http://nessie:19120/iceberg/` | Iceberg REST Catalog API (used by Dremio RESTCATALOG plugin) |
| `http://nessie:19120/api/v2` | Native Nessie API (used by Dremio native Nessie plugin) |
| `http://nessie:9000/q/health/ready` | Health check |

### SSO Mode Overrides (docker-compose.sso.yml)

When Keycloak is enabled, these settings are added:

| Setting | Value | Description |
|---|---|---|
| `nessie.server.authentication.enabled` | `true` | Enable OIDC auth |
| `quarkus.oidc.auth-server-url` | `http://keycloak:8080/realms/iceberg` | Keycloak realm URL |
| `quarkus.oidc.client-id` | `client1` | OAuth2 client ID |
| `quarkus.oidc.credentials.secret` | `s3cr3t` | OAuth2 client secret |
| `quarkus.oidc.token.issuer` | `http://keycloak:8080/realms/iceberg` | Expected JWT issuer |

---

## Lakekeeper

Iceberg REST Catalog written in Rust. Requires PostgreSQL for metadata storage.

### PostgreSQL Backend (lakekeeper-db)

| Setting | Value | Description |
|---|---|---|
| `POSTGRES_USER` | `postgres` | Database user |
| `POSTGRES_PASSWORD` | `postgres` | Database password |
| `POSTGRES_DB` | `lakekeeper` | Database name |

### Server Configuration

Shared by both `lakekeeper-migrate` and `lakekeeper` services:

| Setting | Value | Description |
|---|---|---|
| `LAKEKEEPER__PG_ENCRYPTION_KEY` | `demo-encryption-key-not-for-prod` | Encryption key for secrets at rest |
| `LAKEKEEPER__PG_DATABASE_URL_READ` | `postgresql://postgres:postgres@lakekeeper-db:5432/lakekeeper` | Read connection string |
| `LAKEKEEPER__PG_DATABASE_URL_WRITE` | `postgresql://postgres:postgres@lakekeeper-db:5432/lakekeeper` | Write connection string |
| `LAKEKEEPER__BASE_URI` | `http://lakekeeper:8181` | Public base URI (returned in API responses) |
| `LAKEKEEPER__LISTEN_PORT` | `8181` | HTTP listen port |

### Commands

| Command | Description |
|---|---|
| `migrate` | Run database migrations, then exit |
| `serve` | Start the HTTP catalog server |

### Bootstrap (lakekeeper-init)

Lakekeeper requires a bootstrap call before any warehouse can be created:

```
POST /management/v1/bootstrap
{"accept-terms-of-use": true}
```

This creates the default project (`00000000-0000-0000-0000-000000000000`).

### Warehouse Configuration (lakekeeper-init)

```
POST /management/v1/warehouse
```

| Field | Value | Description |
|---|---|---|
| `warehouse-name` | `lakehouse` | Warehouse identifier (used in `?warehouse=` param and Dremio config) |
| `storage-profile.type` | `s3` | Storage type |
| `storage-profile.bucket` | `lakebucket` | S3 bucket for table data |
| `storage-profile.region` | `us-east-1` | AWS region |
| `storage-profile.endpoint` | `http://minio:9000` | MinIO S3 API (needs `http://`) |
| `storage-profile.path-style-access` | `true` | Required for MinIO |
| `storage-profile.flavor` | `minio` | S3-compatible mode |
| `storage-profile.sts-enabled` | `false` | Disable STS token vending (MinIO doesn't support it) |
| `storage-profile.remote-signing-enabled` | `false` | Disable remote signing (clients use own S3 creds) |
| `storage-credential.type` | `s3` | Credential type |
| `storage-credential.credential-type` | `access-key` | Static access key |
| `storage-credential.aws-access-key-id` | `minioadmin` | MinIO access key |
| `storage-credential.aws-secret-access-key` | `minioadmin` | MinIO secret key |

> **Important:** `sts-enabled: false` and `remote-signing-enabled: false` are critical for MinIO.
> Without these, Lakekeeper attempts to vend temporary S3 credentials via STS, which MinIO
> does not support, causing "Access Denied" errors when writing data.

### API Endpoints

| Path | Description |
|---|---|
| `http://lakekeeper:8181/catalog` | Iceberg REST Catalog API (used by Dremio) |
| `http://lakekeeper:8181/catalog/v1/config?warehouse=lakehouse` | Catalog config (returns routing prefix) |
| `http://lakekeeper:8181/management/v1/warehouse` | Warehouse management API |
| `http://lakekeeper:8181/management/v1/bootstrap` | Bootstrap API |

---

## Polaris

Iceberg REST Catalog with built-in RBAC (principals, roles, grants). Written in Java (Quarkus). Uses in-memory storage by default (no database required for dev).

### Server Configuration

| Setting | Value | Description |
|---|---|---|
| `AWS_REGION` | `us-east-1` | AWS region (required by S3 SDK) |
| `AWS_ACCESS_KEY_ID` | `minioadmin` | S3 credentials for Polaris server to access MinIO |
| `AWS_SECRET_ACCESS_KEY` | `minioadmin` | S3 secret key |
| `POLARIS_BOOTSTRAP_CREDENTIALS` | `POLARIS,root,s3cr3t` | `REALM,client_id,client_secret` — creates root principal on first boot |
| `polaris.realm-context.realms` | `POLARIS` | Comma-separated realm names |
| `quarkus.otel.sdk.disabled` | `true` | Disable OpenTelemetry (optional, reduces noise) |

### Authentication

Polaris always requires OAuth2 — there is no "no-auth" mode. For development, the `POLARIS_BOOTSTRAP_CREDENTIALS` env var creates a known root credential.

**Obtaining a token:**

```bash
TOKEN=$(curl -s http://polaris:8181/api/catalog/v1/oauth/tokens \
  --user root:s3cr3t \
  -H "Polaris-Realm: POLARIS" \
  -d "grant_type=client_credentials" \
  -d "scope=PRINCIPAL_ROLE:ALL" | jq -r .access_token)
```

All Management API calls require `Authorization: Bearer $TOKEN` and `Polaris-Realm: POLARIS` headers.

### Catalog Configuration (polaris-init)

**Step 1 — Create catalog:**

```
POST /api/management/v1/catalogs
```

| Field | Value | Description |
|---|---|---|
| `catalog.name` | `polaris_catalog` | Catalog identifier (used as `warehouse` in Dremio config) |
| `catalog.type` | `INTERNAL` | Polaris-managed catalog |
| `catalog.readOnly` | `false` | Allow write operations |
| `catalog.properties.default-base-location` | `s3://polarisbucket` | S3 root for table data |
| `catalog.storageConfigInfo.storageType` | `S3` | Storage type |
| `catalog.storageConfigInfo.endpoint` | `http://minio:9000` | S3 endpoint for external clients |
| `catalog.storageConfigInfo.endpointInternal` | `http://minio:9000` | S3 endpoint for Polaris itself (Docker network) |
| `catalog.storageConfigInfo.pathStyleAccess` | `true` | Required for MinIO |

> **Note:** Polaris has two endpoint fields: `endpoint` (what clients see) and `endpointInternal`
> (what Polaris uses internally). In Docker they're the same. In production with a public S3
> endpoint, they would differ.

**Step 2 — Grant permissions:**

```
PUT /api/management/v1/catalogs/polaris_catalog/catalog-roles/catalog_admin/grants
{"type": "catalog", "privilege": "CATALOG_MANAGE_CONTENT"}
```

Without this grant, table creation will fail with a permission error.

### API Endpoints

| Path | Description |
|---|---|
| `http://polaris:8181/api/catalog` | Iceberg REST Catalog API (used by Dremio) |
| `http://polaris:8181/api/catalog/v1/oauth/tokens` | OAuth2 token endpoint |
| `http://polaris:8181/api/management/v1/catalogs` | Catalog management API |
| `http://polaris:8181/api/management/v1/principals` | Principal management API |
| `http://polaris:8181/api/management/v1/principal-roles` | Role management API |
| `http://polaris:8182/q/health` | Health check (Quarkus management port) |

### Polaris Built-in RBAC

Polaris has its own access control system independent of Dremio:

```
Principals (service accounts)
  └─► Principal Roles (groups)
        └─► Catalog Roles (scoped permissions)
              └─► Grants (TABLE_READ, TABLE_WRITE, NAMESPACE_CREATE, etc.)
```

This is useful when multiple query engines (Dremio, Spark, Trino) access the same catalog — Polaris enforces permissions at the catalog level regardless of which engine connects.

---

## Dremio RESTCATALOG Source Configuration

All three catalogs use the same Dremio source type: **REST Catalog** (`RESTCATALOG`).

### Nessie Source (`nessie_catalog`)

**General:**

| Setting | Value |
|---|---|
| Source Type | REST Catalog |
| Name | `nessie_catalog` |
| Endpoint URI | `http://nessie:19120/iceberg/` |

**Catalog Properties:**

| Property | Value | Description |
|---|---|---|
| `warehouse` | `warehouse` | Nessie warehouse name |
| `fs.s3a.endpoint` | `minio:9000` | MinIO endpoint (**no** `http://` prefix) |
| `fs.s3a.access.key` | `minioadmin` | S3 access key |
| `fs.s3a.secret.key` | `minioadmin` | S3 secret key |
| `fs.s3a.path.style.access` | `true` | Required for MinIO |
| `fs.s3a.connection.ssl.enabled` | `false` | MinIO uses plain HTTP |
| `dremio.s3.compat` | `true` | S3-compatible mode |
| `fs.s3a.aws.credentials.provider` | `org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider` | Use static credentials |

**Secret Credentials:** (none in base mode)

**Secret Credentials (SSO mode only):**

| Property | Value | Description |
|---|---|---|
| `oauth2-server-uri` | `http://keycloak:8080/realms/iceberg/protocol/openid-connect/token` | Keycloak token endpoint |
| `credential` | `client1:s3cr3t` | OAuth2 client_id:client_secret |
| `scope` | `catalog sign` | OAuth2 scopes |

### Lakekeeper Source (`lakekeeper_catalog`)

**General:**

| Setting | Value |
|---|---|
| Source Type | REST Catalog |
| Name | `lakekeeper_catalog` |
| Endpoint URI | `http://lakekeeper:8181/catalog` |

**Catalog Properties:**

| Property | Value | Description |
|---|---|---|
| `warehouse` | `lakehouse` | Lakekeeper warehouse name |
| `fs.s3a.endpoint` | `minio:9000` | MinIO endpoint (**no** `http://` prefix) |
| `fs.s3a.access.key` | `minioadmin` | S3 access key |
| `fs.s3a.secret.key` | `minioadmin` | S3 secret key |
| `fs.s3a.path.style.access` | `true` | Required for MinIO |
| `fs.s3a.connection.ssl.enabled` | `false` | MinIO uses plain HTTP |
| `dremio.s3.compat` | `true` | S3-compatible mode |
| `fs.s3a.aws.credentials.provider` | `org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider` | Use static credentials |

**Secret Credentials:** (none)

### Polaris Source (`polaris_catalog`)

**General:**

| Setting | Value |
|---|---|
| Source Type | REST Catalog |
| Name | `polaris_catalog` |
| Endpoint URI | `http://polaris:8181/api/catalog` |

**Catalog Properties:**

| Property | Value | Description |
|---|---|---|
| `warehouse` | `polaris_catalog` | Polaris catalog name (acts as warehouse) |
| `header.Polaris-Realm` | `POLARIS` | Custom HTTP header sent with every request |
| `fs.s3a.endpoint` | `minio:9000` | MinIO endpoint (**no** `http://` prefix) |
| `fs.s3a.access.key` | `minioadmin` | S3 access key |
| `fs.s3a.secret.key` | `minioadmin` | S3 secret key |
| `fs.s3a.path.style.access` | `true` | Required for MinIO |
| `fs.s3a.connection.ssl.enabled` | `false` | MinIO uses plain HTTP |
| `dremio.s3.compat` | `true` | S3-compatible mode |
| `fs.s3a.aws.credentials.provider` | `org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider` | Use static credentials |

**Secret Credentials:**

| Property | Value | Description |
|---|---|---|
| `credential` | `root:s3cr3t` | OAuth2 client_id:client_secret (required, Polaris always needs auth) |
| `scope` | `PRINCIPAL_ROLE:ALL` | OAuth2 scope |

### Property Notes

The catalog properties serve **two purposes**:

1. **Iceberg REST client** properties (`warehouse`, `credential`, `scope`, `header.*`) — sent to the catalog server's HTTP API
2. **Dremio S3 filesystem** properties (`fs.s3a.*`, `dremio.s3.compat`) — used by Dremio to read Parquet files from MinIO

> **Critical:** The `fs.s3a.endpoint` value must be `minio:9000` without a protocol prefix.
> Dremio's S3 client adds the scheme based on `fs.s3a.connection.ssl.enabled`.
> Using `http://minio:9000` causes credential verification failures.

### Differences Between the Three Sources

| | Nessie | Lakekeeper | Polaris |
|---|---|---|---|
| Endpoint URI | `http://nessie:19120/iceberg/` | `http://lakekeeper:8181/catalog` | `http://polaris:8181/api/catalog` |
| Warehouse | `warehouse` | `lakehouse` | `polaris_catalog` |
| Data bucket | `s3://demobucket/` | `s3://lakebucket/` | `s3://polarisbucket/` |
| Auth required | No (base mode) | No | Yes (OAuth2 via `credential` + `scope`) |
| Custom headers | None | None | `header.Polaris-Realm: POLARIS` |
| S3 properties | Identical | Identical | Identical |

---

## Seed Data

### Nessie (`docker compose run --rm seed`)

Uses PyIceberg to create tables via the Iceberg REST API.

| Env Variable | Value | Description |
|---|---|---|
| `NESSIE_URI` | `http://nessie:19120/iceberg/` | Catalog endpoint |
| `WAREHOUSE` | `warehouse` | Warehouse name |
| `S3_ENDPOINT` | `http://minio:9000` | MinIO endpoint (PyIceberg needs `http://`) |
| `S3_ACCESS_KEY` | `minioadmin` | S3 access key |
| `S3_SECRET_KEY` | `minioadmin` | S3 secret key |

**Tables created:**

| Table | Columns | Rows |
|---|---|---|
| `demo.customers` | id, name, email, city | 5 |
| `demo.orders` | order_id, customer_id, product, amount | 6 |

### Lakekeeper (`docker compose run --rm seed-lakekeeper`)

| Env Variable | Value | Description |
|---|---|---|
| `CATALOG_URI` | `http://lakekeeper:8181/catalog` | Catalog endpoint |
| `WAREHOUSE` | `lakehouse` | Warehouse name |
| `S3_ENDPOINT` | `http://minio:9000` | MinIO endpoint (PyIceberg needs `http://`) |
| `S3_ACCESS_KEY` | `minioadmin` | S3 access key |
| `S3_SECRET_KEY` | `minioadmin` | S3 secret key |

**Tables created:**

| Table | Columns | Rows |
|---|---|---|
| `inventory.products` | product_id, name, category, price, stock | 6 |
| `inventory.warehouses` | warehouse_id, location, capacity | 3 |

### Polaris (`docker compose run --rm seed-polaris`)

| Env Variable | Value | Description |
|---|---|---|
| `CATALOG_URI` | `http://polaris:8181/api/catalog` | Catalog endpoint |
| `POLARIS_CLIENT_ID` | `root` | OAuth2 client ID |
| `POLARIS_CLIENT_SECRET` | `s3cr3t` | OAuth2 client secret |
| `POLARIS_REALM` | `POLARIS` | Polaris realm (sent as HTTP header) |
| `POLARIS_CATALOG` | `polaris_catalog` | Catalog name (used as warehouse) |
| `S3_ENDPOINT` | `http://minio:9000` | MinIO endpoint (PyIceberg needs `http://`) |
| `S3_ACCESS_KEY` | `minioadmin` | S3 access key |
| `S3_SECRET_KEY` | `minioadmin` | S3 secret key |

**Tables created:**

| Table | Columns | Rows |
|---|---|---|
| `logistics.shipments` | shipment_id, origin, destination, weight_kg, status | 5 |
| `logistics.carriers` | carrier_id, name, mode, rate_per_kg | 4 |

> **Note:** PyIceberg's s3fs client requires `http://` in the S3 endpoint, unlike
> Dremio's `fs.s3a.endpoint` which expects bare `minio:9000`.

---

## Endpoint Format Summary

Different clients expect different endpoint formats for the same MinIO instance:

| Client | Setting | Value | Why |
|---|---|---|---|
| Dremio (`fs.s3a.endpoint`) | Catalog property | `minio:9000` | Dremio adds scheme from `ssl.enabled` |
| Nessie (server) | Docker env | `http://minio:9000/` | Nessie expects full URL with trailing `/` |
| Lakekeeper (warehouse) | Management API | `http://minio:9000` | Lakekeeper expects full URL |
| Polaris (server) | Docker env `AWS_*` | N/A (uses SDK defaults) | Polaris reads `AWS_*` env vars; endpoint set in catalog `storageConfigInfo` |
| Polaris (catalog config) | Management API | `http://minio:9000` | Full URL in `endpoint` and `endpointInternal` |
| PyIceberg (seed scripts) | Python env | `http://minio:9000` | s3fs/boto3 expects full URL |

---

## Keycloak (SSO Mode Only)

Activated with `--profile sso`. Provides OIDC authentication for Dremio and OAuth2 for Nessie.

### Server Configuration

| Setting | Value | Description |
|---|---|---|
| `KC_BOOTSTRAP_ADMIN_USERNAME` | `admin` | Keycloak admin console user |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | `admin` | Keycloak admin console password |
| `KC_HOSTNAME` | `http://keycloak:8080` | Fixed hostname for JWT `iss` claim |
| `KC_HOSTNAME_STRICT` | `false` | Allow access from other hostnames |
| `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` | `true` | Dynamic backchannel hostname |

### Realm: `iceberg`

Imported from `keycloak/iceberg-realm.json`.

**OAuth2 Clients:**

| Client ID | Type | Secret | Purpose |
|---|---|---|---|
| `client1` | Service account | `s3cr3t` | Nessie OAuth2 (catalog access) |
| `dremio-web` | Browser + direct access | `dremio-secret` | Dremio SSO login |

**Users:**

| User | Password | Realm Role | Dremio Role |
|---|---|---|---|
| `admin` | `admin123` | `ADMIN` | Admin (via role sync) |
| `testuser` | `testpass` | `analysts` | analysts |
| `alice` | `alice123` | `analysts` | analysts |

### Host Configuration

For browser-based SSO login, add to `/etc/hosts`:
```
127.0.0.1 keycloak
```
Required because Keycloak JWTs use `keycloak:8080` as the issuer, and the browser must resolve this hostname for the OIDC redirect.

---

## Volumes

| Volume | Service | Contents |
|---|---|---|
| `minio-data` | minio | S3 bucket data (demobucket + lakebucket + polarisbucket) |
| `dremio-data` | dremio | Dremio metadata, KV store, job history |
| `lakekeeper-db-data` | lakekeeper-db | PostgreSQL data (Lakekeeper catalog metadata) |

To reset all state: `docker compose down -v`
