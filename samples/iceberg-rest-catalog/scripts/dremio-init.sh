#!/usr/bin/env bash
# =============================================================================
# dremio-init.sh — Bootstrap Dremio with a RESTCATALOG source, sample data,
#                  spaces, views, roles, and RBAC grants.
#
# Runs as a one-shot docker-compose service after Dremio is healthy.
# Works in both SSO (Keycloak JWT) and internal auth modes.
# =============================================================================
set -euo pipefail

DREMIO_URL="${DREMIO_URL:-http://dremio:9047}"
KC_URL="${KC_URL:-http://keycloak:8080}"
KC_REALM="${KC_REALM:-iceberg}"
KC_CLIENT="${KC_CLIENT:-dremio-web}"
KC_SECRET="${KC_SECRET:-dremio-secret}"

# Default admin credentials
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin123}"

# ── Helpers ──────────────────────────────────────────────────────────────────

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

get_kc_token() {
  local user="$1" pass="$2"
  local resp token
  for attempt in $(seq 1 10); do
    resp=$(curl -s -X POST "${KC_URL}/realms/${KC_REALM}/protocol/openid-connect/token" \
      -d "grant_type=password" \
      -d "client_id=${KC_CLIENT}" \
      -d "client_secret=${KC_SECRET}" \
      -d "username=${user}" \
      -d "password=${pass}" 2>/dev/null || echo "{}")
    token=$(echo "$resp" | jq -r '.access_token // empty' 2>/dev/null || true)
    if [ -n "$token" ] && [ "$token" != "null" ]; then
      echo "$token"
      return 0
    fi
    echo "  Token attempt $attempt/10 failed, retrying..." >&2
    sleep 3
  done
  echo ""
}

# Run SQL and wait for job completion
run_sql() {
  local sql="$1" token="$2"
  local resp code body job_id status

  resp=$(curl -s -w "\n%{http_code}" \
    -X POST "${DREMIO_URL}/api/v3/sql" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${AUTH_HEADER}" \
    -d "{\"sql\": $(echo "$sql" | jq -Rs .)}")

  code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')

  if [ "$code" != "200" ]; then
    echo "    SQL ERROR (HTTP $code): $sql" >&2
    return 1
  fi

  job_id=$(echo "$body" | jq -r '.id // empty')
  [ -z "$job_id" ] && return 0

  for _ in $(seq 1 30); do
    status=$(curl -s \
      -H "Authorization: ${AUTH_HEADER}" \
      "${DREMIO_URL}/api/v3/job/${job_id}" \
      | jq -r '.jobState // empty')
    case "$status" in
      COMPLETED) return 0 ;;
      FAILED|CANCELED)
        echo "    SQL ${status}: $sql" >&2
        return 1
        ;;
    esac
    sleep 2
  done
  echo "    SQL TIMEOUT: $sql" >&2
  return 1
}

# Idempotent API POST (ignore 409 = already exists)
api_post() {
  local url="$1" token="$2" data="$3"
  curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$url" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${AUTH_HEADER}" \
    -d "$data"
}

# ── 1. Wait for Dremio ──────────────────────────────────────────────────────

wait_for "Dremio" "${DREMIO_URL}" 60

# Extra wait for Dremio to finish internal init after HTTP listener is up
echo "Waiting 5s for Dremio to fully initialize..."
sleep 5

# ── 2. Authenticate ─────────────────────────────────────────────────────────

echo "Authenticating..."

# Bootstrap first user (idempotent — 409 if exists, 404 in keycloak mode
# where the endpoint is disabled). Needed for internal auth mode; in keycloak
# mode JIT provisioning creates users on first JWT login.
echo "  Bootstrapping first user..."
BOOTSTRAP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X PUT "${DREMIO_URL}/apiv2/bootstrap/firstuser" \
  -H "Content-Type: application/json" \
  -d "{
    \"userName\": \"${ADMIN_USER}\",
    \"firstName\": \"Admin\",
    \"lastName\": \"User\",
    \"email\": \"admin@example.com\",
    \"createdAt\": 0,
    \"password\": \"${ADMIN_PASS}\"
  }")
case "$BOOTSTRAP_CODE" in
  200) echo "  First user created." ;;
  409) echo "  First user already exists." ;;
  *)   echo "  Bootstrap HTTP $BOOTSTRAP_CODE (continuing anyway)." ;;
esac

# Detect SSO mode
SSO_MODE=false
if curl -sf "${KC_URL}/realms/${KC_REALM}" >/dev/null 2>&1; then
  SSO_MODE=true
fi

if [ "$SSO_MODE" = true ]; then
  echo "  SSO mode detected — trying Keycloak JWT"
  wait_for "Keycloak token endpoint" "${KC_URL}/realms/${KC_REALM}" 30
  TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")
  if [ -n "$TOKEN" ] && [ "$TOKEN" != "null" ]; then
    AUTH_HEADER="Bearer ${TOKEN}"
    echo "  Authenticated with Keycloak JWT."
  else
    echo "  WARNING: Keycloak JWT failed, falling back to Dremio native login."
    SSO_MODE=false
  fi
fi

if [ "$SSO_MODE" != true ]; then
  echo "  Using Dremio native login..."
  LOGIN_RESP=$(curl -s -X POST "${DREMIO_URL}/apiv2/login" \
    -H "Content-Type: application/json" \
    -d "{\"userName\": \"${ADMIN_USER}\", \"password\": \"${ADMIN_PASS}\"}")

  TOKEN=$(echo "$LOGIN_RESP" | jq -r '.token // empty')
  if [ -z "$TOKEN" ]; then
    echo "ERROR: Failed to login to Dremio" >&2
    exit 1
  fi
  AUTH_HEADER="_dremio${TOKEN}"
  echo "  Authenticated with Dremio token."
fi

# ── 3. Create RESTCATALOG source ────────────────────────────────────────────

echo "Creating RESTCATALOG source 'nessie_catalog'..."

if [ "$SSO_MODE" = true ]; then
  SECRET_PROPS='[
    { "name": "oauth2-server-uri", "value": "http://keycloak:8080/realms/iceberg/protocol/openid-connect/token" },
    { "name": "credential",        "value": "client1:s3cr3t" },
    { "name": "scope",             "value": "catalog sign" }
  ]'
else
  SECRET_PROPS='[]'
fi

SOURCE_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${DREMIO_URL}/api/v3/catalog" \
  -H "Content-Type: application/json" \
  -H "Authorization: ${AUTH_HEADER}" \
  -d "{
    \"entityType\": \"source\",
    \"name\": \"nessie_catalog\",
    \"type\": \"RESTCATALOG\",
    \"config\": {
      \"restEndpointUri\": \"http://nessie:19120/iceberg/\",
      \"propertyList\": [
        { \"name\": \"warehouse\",                       \"value\": \"warehouse\" },
        { \"name\": \"fs.s3a.endpoint\",                  \"value\": \"minio:9000\" },
        { \"name\": \"fs.s3a.path.style.access\",         \"value\": \"true\" },
        { \"name\": \"fs.s3a.connection.ssl.enabled\",     \"value\": \"false\" },
        { \"name\": \"dremio.s3.compat\",                 \"value\": \"true\" },
        { \"name\": \"dremio.bucket.discovery.enabled\",   \"value\": \"false\" }
      ],
      \"secretPropertyList\": ${SECRET_PROPS},
      \"enableAsync\": true,
      \"isCachingEnabled\": true,
      \"maxCacheSpacePct\": 100
    }
  }")

case "$SOURCE_CODE" in
  200) echo "  Source created." ;;
  409) echo "  Source already exists." ;;
  *)   echo "  WARNING: HTTP $SOURCE_CODE" ;;
esac

# ── 3b. Create RESTCATALOG source for Lakekeeper ─────────────────────────────

echo "Creating RESTCATALOG source 'lakekeeper_catalog'..."

LAKE_SOURCE_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${DREMIO_URL}/api/v3/catalog" \
  -H "Content-Type: application/json" \
  -H "Authorization: ${AUTH_HEADER}" \
  -d "{
    \"entityType\": \"source\",
    \"name\": \"lakekeeper_catalog\",
    \"type\": \"RESTCATALOG\",
    \"config\": {
      \"restEndpointUri\": \"http://lakekeeper:8181/catalog\",
      \"propertyList\": [
        { \"name\": \"warehouse\",                       \"value\": \"lakehouse\" },
        { \"name\": \"fs.s3a.endpoint\",                  \"value\": \"minio:9000\" },
        { \"name\": \"fs.s3a.path.style.access\",         \"value\": \"true\" },
        { \"name\": \"fs.s3a.connection.ssl.enabled\",     \"value\": \"false\" },
        { \"name\": \"dremio.s3.compat\",                 \"value\": \"true\" },
        { \"name\": \"dremio.bucket.discovery.enabled\",   \"value\": \"false\" }
      ],
      \"secretPropertyList\": [],
      \"enableAsync\": true,
      \"isCachingEnabled\": true,
      \"maxCacheSpacePct\": 100
    }
  }")

case "$LAKE_SOURCE_CODE" in
  200) echo "  Source created." ;;
  409) echo "  Source already exists." ;;
  *)   echo "  WARNING: HTTP $LAKE_SOURCE_CODE" ;;
esac

# ── 4. Seed Iceberg data via PyIceberg ──────────────────────────────────────

echo ""
echo "Seeding Iceberg data (Nessie)..."
pip install --quiet pyiceberg[s3fs] pyarrow 2>/dev/null

python3 /scripts/seed-data.py

# ── 3c. Create RESTCATALOG source for Polaris ────────────────────────────────

echo "Creating RESTCATALOG source 'polaris_catalog'..."

POLARIS_SOURCE_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${DREMIO_URL}/api/v3/catalog" \
  -H "Content-Type: application/json" \
  -H "Authorization: ${AUTH_HEADER}" \
  -d "{
    \"entityType\": \"source\",
    \"name\": \"polaris_catalog\",
    \"type\": \"RESTCATALOG\",
    \"config\": {
      \"restEndpointUri\": \"http://polaris:8181/api/catalog\",
      \"propertyList\": [
        { \"name\": \"warehouse\",                       \"value\": \"polaris_catalog\" },
        { \"name\": \"header.Polaris-Realm\",             \"value\": \"POLARIS\" },
        { \"name\": \"fs.s3a.endpoint\",                  \"value\": \"minio:9000\" },
        { \"name\": \"fs.s3a.access.key\",                \"value\": \"minioadmin\" },
        { \"name\": \"fs.s3a.secret.key\",                \"value\": \"minioadmin\" },
        { \"name\": \"fs.s3a.path.style.access\",         \"value\": \"true\" },
        { \"name\": \"fs.s3a.connection.ssl.enabled\",     \"value\": \"false\" },
        { \"name\": \"dremio.s3.compat\",                 \"value\": \"true\" },
        { \"name\": \"fs.s3a.aws.credentials.provider\",   \"value\": \"org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider\" }
      ],
      \"secretPropertyList\": [
        { \"name\": \"credential\", \"value\": \"root:s3cr3t\" },
        { \"name\": \"scope\",      \"value\": \"PRINCIPAL_ROLE:ALL\" }
      ],
      \"enableAsync\": true,
      \"isCachingEnabled\": true,
      \"maxCacheSpacePct\": 100
    }
  }")

case "$POLARIS_SOURCE_CODE" in
  200) echo "  Source created." ;;
  409) echo "  Source already exists." ;;
  *)   echo "  WARNING: HTTP $POLARIS_SOURCE_CODE" ;;
esac

echo ""
echo "Seeding Iceberg data (Lakekeeper)..."
CATALOG_URI="http://lakekeeper:8181/catalog" \
  WAREHOUSE="lakehouse" \
  S3_ENDPOINT="http://minio:9000" \
  S3_ACCESS_KEY="minioadmin" \
  S3_SECRET_KEY="minioadmin" \
  python3 /scripts/seed-data-lakekeeper.py

echo ""
echo "Seeding Iceberg data (Polaris)..."
CATALOG_URI="http://polaris:8181/api/catalog" \
  POLARIS_CLIENT_ID="root" \
  POLARIS_CLIENT_SECRET="s3cr3t" \
  POLARIS_REALM="POLARIS" \
  POLARIS_CATALOG="polaris_catalog" \
  S3_ENDPOINT="http://minio:9000" \
  S3_ACCESS_KEY="minioadmin" \
  S3_SECRET_KEY="minioadmin" \
  python3 /scripts/seed-data-polaris.py

# ── 5. Create spaces ────────────────────────────────────────────────────────

# Refresh token (previous steps may have taken time)
if [ "$SSO_MODE" = true ]; then
  TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")
  AUTH_HEADER="Bearer ${TOKEN}"
fi

echo ""
echo "Creating spaces..."

for SPACE in analytics engineering; do
  code=$(api_post "${DREMIO_URL}/api/v3/catalog" "$TOKEN" \
    "{\"entityType\":\"space\",\"name\":\"$SPACE\"}")
  case "$code" in
    200) echo "  Created space: $SPACE" ;;
    409|400) echo "  Space '$SPACE' already exists." ;;
    *) echo "  WARNING: space '$SPACE' HTTP $code" ;;
  esac
done

# ── 6. Create views ─────────────────────────────────────────────────────────

echo ""
echo "Creating views..."

run_sql "CREATE OR REPLACE VIEW analytics.customer_overview AS SELECT id, name, city FROM nessie_catalog.demo.customers" "$TOKEN" \
  && echo "  Created: analytics.customer_overview" || true
run_sql "CREATE OR REPLACE VIEW analytics.order_summary AS SELECT o.order_id, c.name AS customer_name, o.product, o.amount FROM nessie_catalog.demo.orders o JOIN nessie_catalog.demo.customers c ON o.customer_id = c.id" "$TOKEN" \
  && echo "  Created: analytics.order_summary" || true
run_sql "CREATE OR REPLACE VIEW analytics.revenue_by_city AS SELECT c.city, COUNT(*) AS order_count, SUM(o.amount) AS total_revenue FROM nessie_catalog.demo.orders o JOIN nessie_catalog.demo.customers c ON o.customer_id = c.id GROUP BY c.city" "$TOKEN" \
  && echo "  Created: analytics.revenue_by_city" || true
run_sql "CREATE OR REPLACE VIEW engineering.raw_customers AS SELECT * FROM nessie_catalog.demo.customers" "$TOKEN" \
  && echo "  Created: engineering.raw_customers" || true
run_sql "CREATE OR REPLACE VIEW engineering.raw_orders AS SELECT * FROM nessie_catalog.demo.orders" "$TOKEN" \
  && echo "  Created: engineering.raw_orders" || true
run_sql "CREATE OR REPLACE VIEW engineering.raw_products AS SELECT * FROM lakekeeper_catalog.inventory.products" "$TOKEN" \
  && echo "  Created: engineering.raw_products" || true
run_sql "CREATE OR REPLACE VIEW engineering.raw_warehouses AS SELECT * FROM lakekeeper_catalog.inventory.warehouses" "$TOKEN" \
  && echo "  Created: engineering.raw_warehouses" || true
run_sql "CREATE OR REPLACE VIEW analytics.product_catalog AS SELECT product_id, name, category, price FROM lakekeeper_catalog.inventory.products" "$TOKEN" \
  && echo "  Created: analytics.product_catalog" || true
run_sql "CREATE OR REPLACE VIEW analytics.shipment_overview AS SELECT shipment_id, origin, destination, status FROM polaris_catalog.logistics.shipments" "$TOKEN" \
  && echo "  Created: analytics.shipment_overview" || true
run_sql "CREATE OR REPLACE VIEW engineering.raw_shipments AS SELECT * FROM polaris_catalog.logistics.shipments" "$TOKEN" \
  && echo "  Created: engineering.raw_shipments" || true
run_sql "CREATE OR REPLACE VIEW engineering.raw_carriers AS SELECT * FROM polaris_catalog.logistics.carriers" "$TOKEN" \
  && echo "  Created: engineering.raw_carriers" || true

# ── 7. Create roles ─────────────────────────────────────────────────────────

echo ""
echo "Creating roles..."

for ROLE in analysts engineers; do
  code=$(api_post "${DREMIO_URL}/api/v3/rbac/roles" "$TOKEN" \
    "{\"roleName\":\"$ROLE\"}")
  case "$code" in
    200) echo "  Created role: $ROLE" ;;
    409|400) echo "  Role '$ROLE' already exists." ;;
    *) echo "  WARNING: role '$ROLE' HTTP $code" ;;
  esac
done

# ── 8. Grant privileges ─────────────────────────────────────────────────────

echo ""
echo "Granting privileges..."

# analysts: SELECT on analytics views
run_sql "GRANT SELECT ON VDS analytics.customer_overview TO ROLE analysts" "$TOKEN" \
  && echo "  GRANT SELECT analytics.customer_overview -> analysts" || true
run_sql "GRANT SELECT ON VDS analytics.order_summary TO ROLE analysts" "$TOKEN" \
  && echo "  GRANT SELECT analytics.order_summary -> analysts" || true
run_sql "GRANT SELECT ON VDS analytics.revenue_by_city TO ROLE analysts" "$TOKEN" \
  && echo "  GRANT SELECT analytics.revenue_by_city -> analysts" || true

# engineers: SELECT on all views + CREATE_VIEW in engineering
run_sql "GRANT SELECT ON VDS engineering.raw_customers TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT engineering.raw_customers -> engineers" || true
run_sql "GRANT SELECT ON VDS engineering.raw_orders TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT engineering.raw_orders -> engineers" || true
run_sql "GRANT SELECT ON VDS analytics.customer_overview TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT analytics.customer_overview -> engineers" || true
run_sql "GRANT SELECT ON VDS analytics.order_summary TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT analytics.order_summary -> engineers" || true
run_sql "GRANT SELECT ON VDS analytics.revenue_by_city TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT analytics.revenue_by_city -> engineers" || true
run_sql "GRANT SELECT ON VDS analytics.product_catalog TO ROLE analysts" "$TOKEN" \
  && echo "  GRANT SELECT analytics.product_catalog -> analysts" || true
run_sql "GRANT SELECT ON VDS analytics.product_catalog TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT analytics.product_catalog -> engineers" || true
run_sql "GRANT SELECT ON VDS engineering.raw_products TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT engineering.raw_products -> engineers" || true
run_sql "GRANT SELECT ON VDS engineering.raw_warehouses TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT engineering.raw_warehouses -> engineers" || true
run_sql "GRANT SELECT ON VDS analytics.shipment_overview TO ROLE analysts" "$TOKEN" \
  && echo "  GRANT SELECT analytics.shipment_overview -> analysts" || true
run_sql "GRANT SELECT ON VDS analytics.shipment_overview TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT analytics.shipment_overview -> engineers" || true
run_sql "GRANT SELECT ON VDS engineering.raw_shipments TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT engineering.raw_shipments -> engineers" || true
run_sql "GRANT SELECT ON VDS engineering.raw_carriers TO ROLE engineers" "$TOKEN" \
  && echo "  GRANT SELECT engineering.raw_carriers -> engineers" || true

# ── Done ─────────────────────────────────────────────────────────────────────

cat <<'EOF'

============================================================
  Dremio initialization complete!

  Sources:
    nessie_catalog     (RESTCATALOG -> Nessie)
    lakekeeper_catalog (RESTCATALOG -> Lakekeeper)
    polaris_catalog    (RESTCATALOG -> Apache Polaris)

  Tables:
    nessie_catalog.demo.customers            (5 rows)
    nessie_catalog.demo.orders               (6 rows)
    lakekeeper_catalog.inventory.products    (6 rows)
    lakekeeper_catalog.inventory.warehouses  (3 rows)
    polaris_catalog.logistics.shipments      (5 rows)
    polaris_catalog.logistics.carriers       (4 rows)

  Spaces & Views:
    analytics/
      ├── customer_overview   (nessie)
      ├── order_summary       (nessie)
      ├── revenue_by_city     (nessie)
      ├── product_catalog     (lakekeeper)
      └── shipment_overview   (polaris)
    engineering/
      ├── raw_customers       (nessie)
      ├── raw_orders          (nessie)
      ├── raw_products        (lakekeeper)
      ├── raw_warehouses      (lakekeeper)
      ├── raw_shipments       (polaris)
      └── raw_carriers        (polaris)

  Roles & RBAC:
    analysts  -> SELECT on analytics.* views
    engineers -> SELECT on all views

  Cross-catalog join example:
    SELECT c.name, s.destination, s.status
    FROM nessie_catalog.demo.customers c,
         polaris_catalog.logistics.shipments s
    WHERE c.city = s.origin
============================================================
EOF
