#!/usr/bin/env bash
# seed.sh — Bootstrap Dremio first user and create a RESTCATALOG source
#            pointing to the Nessie Iceberg REST Catalog.
set -euo pipefail

DREMIO_URL="http://localhost:9047"
DREMIO_USER="admin"
DREMIO_PASS="admin123"

# ── Helpers ─────────────────────────────────────────────────────────────
wait_for() {
  local name="$1" url="$2" max="$3"
  printf "Waiting for %s " "$name"
  for i in $(seq 1 "$max"); do
    if curl -sf "$url" >/dev/null 2>&1; then
      printf " ready.\n"
      return 0
    fi
    printf "."
    sleep 2
  done
  printf " TIMEOUT\n"
  echo "ERROR: $name did not become ready after $((max * 2))s" >&2
  exit 1
}

# ── 1. Wait for services ───────────────────────────────────────────────
wait_for "MinIO"    "http://localhost:9000/minio/health/live" 30
wait_for "Keycloak" "http://localhost:8080/realms/iceberg"     60
wait_for "Nessie"   "http://localhost:9001/q/health/ready"    30
wait_for "Dremio"   "$DREMIO_URL"                             60

# Dremio needs extra time after the HTTP listener is up
echo "Waiting 10s for Dremio to fully initialize..."
sleep 10

# ── 2. Bootstrap first user ────────────────────────────────────────────
echo "Bootstrapping Dremio first user ($DREMIO_USER)..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X PUT "$DREMIO_URL/apiv2/bootstrap/firstuser" \
  -H "Content-Type: application/json" \
  -d "{
    \"userName\": \"$DREMIO_USER\",
    \"firstName\": \"Admin\",
    \"lastName\": \"User\",
    \"email\": \"admin@example.com\",
    \"createdAt\": 0,
    \"password\": \"$DREMIO_PASS\"
  }")

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "409" ]; then
  echo "  First user ready (HTTP $HTTP_CODE)."
else
  echo "  WARNING: Unexpected response $HTTP_CODE from bootstrap endpoint."
fi

# ── 3. Login ────────────────────────────────────────────────────────────
echo "Logging in to Dremio..."
LOGIN_RESP=$(curl -s -X POST "$DREMIO_URL/apiv2/login" \
  -H "Content-Type: application/json" \
  -d "{\"userName\": \"$DREMIO_USER\", \"password\": \"$DREMIO_PASS\"}")

TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" 2>/dev/null) || {
  echo "ERROR: Failed to extract token. Response: $LOGIN_RESP" >&2
  exit 1
}
echo "  Logged in."

# ── 4. Create RESTCATALOG source ────────────────────────────────────────
echo "Creating RESTCATALOG source 'nessie_catalog'..."
SOURCE_RESP=$(curl -s -w "\n%{http_code}" \
  -X PUT "$DREMIO_URL/apiv2/source/nessie_catalog" \
  -H "Content-Type: application/json" \
  -H "Authorization: _dremio${TOKEN}" \
  -d '{
    "name": "nessie_catalog",
    "config": {
      "restEndpointUri": "http://nessie:19120/iceberg/",
      "propertyList": [
        { "name": "warehouse",                     "value": "warehouse" },
        { "name": "fs.s3a.endpoint",                "value": "minio:9000" },
        { "name": "fs.s3a.access.key",              "value": "minioadmin" },
        { "name": "fs.s3a.secret.key",              "value": "minioadmin" },
        { "name": "fs.s3a.path.style.access",       "value": "true" },
        { "name": "fs.s3a.connection.ssl.enabled",   "value": "false" },
        { "name": "dremio.s3.compat",               "value": "true" },
        { "name": "fs.s3a.aws.credentials.provider", "value": "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider" }
      ],
      "secretPropertyList": [
        { "name": "oauth2-server-uri", "value": "http://keycloak:8080/realms/iceberg/protocol/openid-connect/token" },
        { "name": "credential",        "value": "client1:s3cr3t" },
        { "name": "scope",             "value": "catalog sign" }
      ],
      "enableAsync": true,
      "isCachingEnabled": true,
      "maxCacheSpacePct": 100
    },
    "type": "RESTCATALOG"
  }')

SOURCE_CODE=$(echo "$SOURCE_RESP" | tail -1)
SOURCE_BODY=$(echo "$SOURCE_RESP" | sed '$d')

if [ "$SOURCE_CODE" = "200" ]; then
  echo "  Source 'nessie_catalog' created successfully."
elif [ "$SOURCE_CODE" = "409" ]; then
  echo "  Source 'nessie_catalog' already exists (HTTP 409)."
else
  echo "  WARNING: Unexpected response $SOURCE_CODE."
  echo "  Body: $SOURCE_BODY"
fi

# ── 5. Seed sample data via PyIceberg (runs inside Docker) ──────────────
echo ""
echo "Seeding sample data (demo.customers, demo.orders)..."
docker compose --profile seed run --rm seed

# ── Done ────────────────────────────────────────────────────────────────
cat <<'EOF'

============================================================
  All services are running with sample data!

  Dremio UI:      http://localhost:9047
                  Login: admin / admin123

  MinIO Console:  http://localhost:9090
                  Login: minioadmin / minioadmin

  Keycloak:       http://localhost:8080
                  Login: admin / admin

  Nessie REST:    http://localhost:19120/iceberg/
                  (requires OAuth2 bearer token)

  Source 'nessie_catalog' is configured in Dremio.

  Try these queries:
    SELECT * FROM nessie_catalog.demo.customers
    SELECT * FROM nessie_catalog.demo.orders
    SELECT c.name, o.product, o.amount
      FROM nessie_catalog.demo.orders o
      JOIN nessie_catalog.demo.customers c ON o.customer_id = c.id
============================================================
EOF
