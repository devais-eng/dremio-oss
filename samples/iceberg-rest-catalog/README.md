# Iceberg REST Catalog Demo

Dremio OSS + Nessie (Iceberg REST Catalog) + MinIO (S3 storage), with optional Keycloak SSO.

## Architecture

```
 ┌──────────┐       ┌──────────┐       ┌───────┐
 │  Dremio  │──REST─│  Nessie  │──S3──▶│ MinIO │
 │  :9047   │       │  :19120  │       │ :9000 │
 └──────────┘       └──────────┘       └───────┘
                         │
                    ┌──────────┐
                    │ Keycloak │  (SSO mode only)
                    │  :8080   │
                    └──────────┘
```

## Prerequisites

- Docker Engine 20+
- Docker Compose v2
- `curl`, `python3` (for the seed script)

## Quick Start

### Internal auth (no Keycloak)

```bash
docker compose up -d
```

Dremio uses its built-in user/password authentication. Nessie runs without auth.

### SSO with Keycloak

```bash
docker compose -f docker-compose.yml -f docker-compose.sso.yml up --profile sso -d
```

This starts everything including Keycloak and the `keycloak-init` service which
automatically creates the required client scopes and role mappers.

### Seed sample data

After all services are healthy (~90 seconds):

```bash
# Internal auth mode
docker compose run --rm seed

# SSO mode
docker compose -f docker-compose.yml -f docker-compose.sso.yml run --rm seed
```

The seed script creates:
1. A `demo` namespace with `demo.customers` (5 rows) and `demo.orders` (6 rows)

Open **http://localhost:9047** and create a Dremio admin user via the first-user form.

## Services

| Service       | URL                        | Credentials            | Mode |
|---------------|----------------------------|------------------------|------|
| Dremio        | http://localhost:9047       | (created at first run) | both |
| MinIO Console | http://localhost:9090       | minioadmin / minioadmin| both |
| Nessie        | http://localhost:19120      | (no auth / OAuth2)     | both |
| Keycloak      | http://localhost:8080/admin | admin / admin          | SSO  |

## Keycloak Users (SSO mode)

| User     | Password  | Realm Role | Description                          |
|----------|-----------|------------|--------------------------------------|
| admin    | admin123  | ADMIN      | Maps to Dremio admin via role sync   |
| testuser | testpass  | analysts   | Regular user, analyst role           |
| alice    | alice123  | analysts   | Regular user, analyst role           |

The `keycloak-init` service automatically:
- Creates `catalog` and `sign` scopes (for Nessie OAuth2)
- Creates a `roles` scope with a realm-role mapper (`realm_access.roles` claim)
- Assigns scopes to the appropriate clients (`client1`, `dremio-web`)

> **Note:** The first Dremio user must be created via the bootstrap form at
> http://localhost:9047 before SSO login works. After that, Keycloak users
> can log in via the "Sign in with SSO" button.

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
5. In **Secret Credentials**, add (SSO mode only):
   | Property           | Value                                                                        |
   |--------------------|------------------------------------------------------------------------------|
   | oauth2-server-uri  | http://keycloak:8080/realms/iceberg/protocol/openid-connect/token            |
   | credential         | client1:s3cr3t                                                               |
   | scope              | catalog sign                                                                 |
6. Click **Save**

## Nessie API

Get a token and query the Nessie REST Catalog:

```bash
# SSO mode — obtain OAuth2 token from Keycloak
TOKEN=$(curl -s -X POST \
  http://localhost:8080/realms/iceberg/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=client1&client_secret=s3cr3t&scope=catalog sign" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# List namespaces
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:19120/iceberg/v1/namespaces

# Internal auth mode — no token needed
curl http://localhost:19120/iceberg/v1/namespaces
```

## Troubleshooting

**Nessie returns 401 Unauthorized (SSO mode)**
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
# or for SSO mode:
docker compose -f docker-compose.yml -f docker-compose.sso.yml down --profile sso -v
```

The `-v` flag removes named volumes (MinIO data, Dremio data).
