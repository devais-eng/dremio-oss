---
name: test-rest-catalog
description: Deploy a REST catalog, register it as a RESTCATALOG source in Dremio, and validate read operations
argument-hint: "<catalog-name> (e.g., nessie, lakekeeper, polaris, gravitino, unity)"
allowed-tools:
  - Read
  - Write
  - Edit
  - Glob
  - Grep
  - Bash
  - Task
  - AskUserQuestion
  - WebSearch
  - WebFetch
---

<objective>
Automated end-to-end validation of the Dremio RESTCATALOG plugin against any Iceberg REST Catalog server.

**What this does:**
1. Research deployment of the specified REST catalog
2. Create a Docker Compose (or alternative) to run it locally
3. Seed test data (namespace + table with sample rows)
4. Obtain a Dremio admin token
5. Register the catalog as a RESTCATALOG source in Dremio
6. Validate: source health, namespace browsing, table listing, SELECT query
7. Report pass/fail results

**Prerequisites:**
- Dremio must be running locally on port 9047
- Docker must be available
- Python 3 with `pyiceberg`, `pyarrow`, and `s3fs` installed (for data seeding)
</objective>

<context>
**Argument:** $ARGUMENTS — the REST catalog name to test (e.g., "nessie", "lakekeeper", "polaris", "gravitino", "unity")

If no argument given, ask the user which catalog to test.

**Known catalog recipes:**

### Nessie
- Image: `ghcr.io/projectnessie/nessie:latest`
- Port: 19120
- Iceberg REST endpoint: `http://localhost:19120/iceberg/`
- Needs: MinIO for storage (S3-compatible)
- Auth: disabled for testing (`nessie.server.authentication.enabled=false`)
- Warehouse config is server-side (`nessie.catalog.warehouses.warehouse.location=s3://warehouse/`)
- S3 creds configured server-side via secrets indirection pattern

### Lakekeeper
- Image: `quay.io/lakekeeper/catalog:latest-main`
- Port: 8181
- Iceberg REST endpoint: `http://localhost:8181/catalog/`
- Needs: PostgreSQL + MinIO
- Requires bootstrap: accept terms + create warehouse via management API
- Warehouse name configured via management API after startup

### Polaris (Apache, formerly Snowflake)
- Image: `apache/polaris:latest` (or `polarisoss/polaris:latest`)
- Port: 8181
- Iceberg REST endpoint: `http://localhost:8181/api/catalog`
- Needs: MinIO for storage
- Requires bootstrap: create principal, catalog, grants via management API
- OAuth2 client credentials auth

### Gravitino (Apache)
- Image: `apache/gravitino:latest`
- Port: 8090
- Iceberg REST endpoint: `http://localhost:9001/iceberg/`
- Gravitino acts as meta-catalog; Iceberg REST is one backend
- Needs: separate catalog registration via Gravitino API

### Unity Catalog (Databricks)
- Image: `unitycatalog/unitycatalog:latest`
- Port: 8080
- Iceberg REST endpoint: `http://localhost:8080/api/2.1/unity-catalog/iceberg`
- Local filesystem storage (no MinIO needed for basic test)

**Dremio RESTCATALOG source config pattern:**
- `restEndpointUri`: the Iceberg REST endpoint URL
- `propertyList`: catalog properties including `fs.s3a.*` for S3 storage access
- `secretPropertyList`: for sensitive properties like `rest.token`
- Static `fs.s3a.*` credentials needed (Dremio doesn't propagate vended credentials)
</context>

<process>

## Step 1: Determine Target Catalog

If `$ARGUMENTS` is empty or not recognized, ask:

```
AskUserQuestion(
  header="Catalog",
  question="Which Iceberg REST Catalog do you want to test?",
  options: [
    "Nessie (Recommended)" — "Well-tested, simple setup, Iceberg REST at /iceberg/",
    "Lakekeeper" — "Native Iceberg REST spec, needs PostgreSQL + MinIO",
    "Polaris" — "Apache Polaris, OAuth2 auth, needs bootstrap",
    "Other" — "Provide Docker image and endpoint details"
  ]
)
```

Set `CATALOG_NAME` from argument or selection.

## Step 2: Research Deployment

**If catalog is in the known recipes above**, use those details directly.

**If catalog is unknown or "Other":**
1. Use WebSearch to find: `"<catalog-name> iceberg rest catalog docker" site:github.com OR site:hub.docker.com`
2. Identify: Docker image, port, Iceberg REST endpoint path, required dependencies
3. Present findings and confirm with user before proceeding

## Step 3: Create Infrastructure

Create a working directory at `/tmp/dremio-test-<catalog-name>/`.

Generate `docker-compose.yml` based on the catalog recipe:
- Include MinIO if the catalog needs S3 storage
- Include PostgreSQL if the catalog needs it
- Use healthchecks where possible
- Avoid port conflicts with existing services (check with `docker ps` first)

Generate `seed-data.py` using PyIceberg:
```python
catalog = load_catalog("<name>", **{
    "type": "rest",
    "uri": "<iceberg-rest-endpoint>",
    # S3 creds if needed for client-side writes
})
catalog.create_namespace("testns")
table = catalog.create_table("testns.products", schema=...)
table.append(data)  # 5 sample rows
```

## Step 4: Start and Seed

```bash
cd /tmp/dremio-test-<catalog-name>
docker compose up -d
# Wait for service health
# Run any bootstrap steps (e.g., Lakekeeper warehouse creation, Polaris principal setup)
python3 seed-data.py
```

## Step 5: Get Dremio Token

```bash
# Ask user for credentials if not known
TOKEN=$(curl -s http://localhost:9047/apiv2/login \
  -H "Content-Type: application/json" \
  -d '{"userName":"<user>","password":"<pass>"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
```

If login fails, ask user for correct Dremio credentials:
```
AskUserQuestion(header="Dremio Auth", question="What are your Dremio admin credentials?", ...)
```

## Step 6: Register Source in Dremio

```bash
curl -s -X POST "http://localhost:9047/api/v3/catalog" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "entityType": "source",
    "name": "<catalog-name>_rest",
    "type": "RESTCATALOG",
    "config": {
      "restEndpointUri": "<iceberg-rest-endpoint>",
      "propertyList": [
        // fs.s3a.* properties for MinIO/S3 access
      ],
      "secretPropertyList": [
        // rest.token if OAuth2 needed
      ],
      "enableAsync": false,
      "isCachingEnabled": true,
      "maxCacheSpacePct": 100
    }
  }'
```

Verify response shows `"state": {"status": "good"}`.

If source creation fails:
- Check Dremio coordinator logs: `docker logs dremio 2>&1 | tail -30` or local logs
- Common issues: endpoint not reachable, auth failure, missing catalog properties
- Report error and suggest fix

## Step 7: Validate Read Operations

Run these checks sequentially and collect results:

### 7a. Source Health
```bash
curl -s "http://localhost:9047/api/v3/catalog/by-path/<source-name>" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['state']['status'])"
```
Expected: `good`

### 7b. Namespace Browsing
```bash
curl -s "http://localhost:9047/api/v3/catalog/by-path/<source-name>" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "
import sys,json; d=json.load(sys.stdin)
for c in d.get('children',[]): print(f\"{c['type']}: {'/'.join(c['path'])}\")"
```
Expected: `testns` appears as CONTAINER/FOLDER

### 7c. Table Listing
```bash
curl -s "http://localhost:9047/api/v3/catalog/by-path/<source-name>/testns" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "
import sys,json; d=json.load(sys.stdin)
for c in d.get('children',[]): print(f\"{c['type']}: {'/'.join(c['path'])}\")"
```
Expected: `products` appears as DATASET

### 7d. SELECT Query
```bash
JOB=$(curl -s -X POST "http://localhost:9047/api/v3/sql" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sql": "SELECT * FROM <source-name>.testns.products"}')
JOB_ID=$(echo "$JOB" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
# Poll until COMPLETED/FAILED
# Fetch results
curl -s "http://localhost:9047/api/v3/job/$JOB_ID/results" -H "Authorization: Bearer $TOKEN"
```
Expected: 5 rows with columns id (BIGINT), name (VARCHAR), city (VARCHAR)

## Step 8: Report Results

Present results as a table:

```
## Test Results: <catalog-name> via RESTCATALOG

| Check               | Status  | Details                    |
|---------------------|---------|----------------------------|
| Docker stack        | PASS    | All containers healthy     |
| Data seeding        | PASS    | testns.products: 5 rows    |
| Source creation      | PASS    | State: good                |
| Namespace browsing   | PASS    | testns visible             |
| Table listing        | PASS    | products visible           |
| SELECT query         | PASS    | 5 rows returned            |

Infrastructure: /tmp/dremio-test-<catalog-name>/
Teardown: docker compose -f /tmp/dremio-test-<catalog-name>/docker-compose.yml down
```

If any check failed, report the failure with error details and suggest troubleshooting steps.

</process>

<error_handling>

**Docker not available:** Tell user to install Docker and retry.
**Port conflict:** Check `docker ps` and `ss -tlnp` for conflicts. Use alternative ports.
**Dremio not running:** Tell user to start Dremio first.
**PyIceberg not installed:** Run `pip install pyiceberg pyarrow s3fs` and retry.
**Seed data fails:** Check if catalog is healthy, storage is accessible. Show logs.
**Source creation fails:** Check Dremio logs, verify endpoint reachability from host.
**SELECT fails:** Check fs.s3a.* properties, hostname resolution, storage connectivity.

</error_handling>

<cleanup>
The Docker stack is left running for the user to explore. Remind them:
```
Teardown: docker compose -f /tmp/dremio-test-<catalog-name>/docker-compose.yml down -v
```
</cleanup>
