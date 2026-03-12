# Iceberg REST Catalog Demo

Dremio OSS + Nessie (Iceberg REST Catalog) + MinIO (S3 storage) + Keycloak (OIDC auth).

## Architecture

```
 ┌──────────┐       ┌──────────┐       ┌───────┐
 │  Dremio  │──REST─│  Nessie  │──S3──▶│ MinIO │
 │  :9047   │       │  :19120  │       │ :9000 │
 └──────────┘       └──────────┘       └───────┘
                         │
                    ┌──────────┐
                    │ Keycloak │
                    │  :8080   │
                    └──────────┘
```

## Prerequisites

- Docker Engine 20+
- Docker Compose v2
- `curl`, `python3` (for the seed script)

## Quick Start

```bash
# Start all services
docker compose up -d

# Wait ~90 seconds for all services to become healthy, then:
bash scripts/seed.sh
```

The seed script will:
1. Bootstrap the Dremio admin user
2. Create a `nessie_catalog` RESTCATALOG source pre-configured with OAuth2 and S3 credentials
3. Seed sample tables (`demo.customers`, `demo.orders`) via PyIceberg
4. Create spaces (`analytics`, `engineering`) with views
5. Create roles (`analysts`, `engineers`) and users with RBAC grants

Open **http://localhost:9047** and navigate to the `nessie_catalog` source.

## Services

| Service  | URL                        | Credentials            |
|----------|----------------------------|------------------------|
| Dremio   | http://localhost:9047       | admin / admin123       |
| MinIO    | http://localhost:9090       | minioadmin / minioadmin|
| Keycloak | http://localhost:8080/admin | admin / admin          |
| Nessie   | http://localhost:19120      | OAuth2 via Keycloak    |

## Users & RBAC

| User    | Password     | Role       | Access                                          |
|---------|-------------|------------|--------------------------------------------------|
| admin   | admin123    | (admin)    | Full access to everything                        |
| alice   | alice123    | analysts   | SELECT on `analytics.*` views                    |
| bob     | bob12345    | engineers  | SELECT on all views + CREATE_VIEW in engineering |
| charlie | charlie123  | analysts   | SELECT on `analytics.*` views (same as alice)    |

### Spaces & Views

| Space         | View               | Description                    |
|---------------|--------------------|--------------------------------|
| analytics     | customer_overview  | id, name, city                 |
| analytics     | order_summary      | order_id, customer, product    |
| analytics     | revenue_by_city    | city, order_count, revenue     |
| engineering   | raw_customers      | all customer columns           |
| engineering   | raw_orders         | all order columns              |

### Testing RBAC

Login as `alice` — she can query `analytics.customer_overview` but NOT `engineering.raw_customers` (denied).
Login as `bob` — he can query all views and create new views in the `engineering` space.

## Manual Source Creation (UI)

If you prefer to create the source through the Dremio web UI instead of the seed script:

1. Go to http://localhost:9047 and log in
2. Click **Add Source** → **REST Catalog**
3. Set **Endpoint URI** to `http://nessie:19120/iceberg/`
4. In **Catalog Properties**, add:
   | Property                       | Value                                                    |
   |--------------------------------|----------------------------------------------------------|
   | warehouse                      | warehouse                                                |
   | fs.s3a.endpoint                | minio:9000                                               |
   | fs.s3a.access.key              | minioadmin                                               |
   | fs.s3a.secret.key              | minioadmin                                               |
   | fs.s3a.path.style.access       | true                                                     |
   | fs.s3a.connection.ssl.enabled  | false                                                    |
   | dremio.s3.compat               | true                                                     |
   | fs.s3a.aws.credentials.provider| org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider     |
5. In **Secret Credentials**, add:
   | Property           | Value                                                                        |
   |--------------------|------------------------------------------------------------------------------|
   | oauth2-server-uri  | http://keycloak:8080/realms/iceberg/protocol/openid-connect/token            |
   | credential         | client1:s3cr3t                                                               |
   | scope              | catalog sign                                                                 |
6. Click **Save**

## Nessie API

Get a token and query the Nessie REST Catalog:

```bash
# Obtain OAuth2 token from Keycloak
TOKEN=$(curl -s -X POST \
  http://localhost:8080/realms/iceberg/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=client1&client_secret=s3cr3t&scope=catalog sign" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# List namespaces
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:19120/iceberg/v1/namespaces

# Nessie API v2
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:19120/api/v2/trees
```

## Troubleshooting

**Nessie returns 401 Unauthorized**
Keycloak may still be starting. Check `docker compose logs keycloak` and wait for `Listening on: http://0.0.0.0:8080`.

**Source shows BAD state in Dremio**
Check `docker compose logs dremio` for connection errors. Verify Nessie is healthy:
`curl http://localhost:9001/q/health/ready`

**S3 / MinIO errors**
Ensure the `demobucket` was created: `docker compose logs minio-init`. You can also check via the MinIO console at http://localhost:9090.

**Why are S3 credentials in the source properties?**
Dremio's `DremioFileIO` replaces the Iceberg SDK's `ResolvingFileIO`, so credential vending from Nessie does not automatically propagate to the Hadoop FileSystem used for Parquet reads. Static S3 credentials must be provided in the source catalog properties.

## Cleanup

```bash
docker compose down -v
```

The `-v` flag removes named volumes (MinIO data, Dremio data).
