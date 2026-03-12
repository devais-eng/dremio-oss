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

Open **http://localhost:9047** and navigate to the `nessie_catalog` source.

## Services

| Service  | URL                        | Credentials            |
|----------|----------------------------|------------------------|
| Dremio   | http://localhost:9047       | admin / admin123       |
| MinIO    | http://localhost:9090       | minioadmin / minioadmin|
| Keycloak | http://localhost:8080/admin | admin / admin          |
| Nessie   | http://localhost:19120      | OAuth2 via Keycloak    |

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
