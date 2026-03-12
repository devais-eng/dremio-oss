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

# ── Helper: run SQL via Dremio REST API ───────────────────────────────
run_sql() {
  local sql="$1"
  local token="$2"
  local resp code body job_id

  resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$DREMIO_URL/api/v3/sql" \
    -H "Content-Type: application/json" \
    -H "Authorization: _dremio${token}" \
    -d "{\"sql\": \"$sql\"}")

  code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')

  if [ "$code" = "200" ]; then
    job_id=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || true)
    if [ -n "$job_id" ]; then
      # Wait for job completion (up to 30s)
      for _ in $(seq 1 15); do
        local status
        status=$(curl -s \
          -H "Authorization: _dremio${token}" \
          "$DREMIO_URL/api/v3/job/$job_id" \
          | python3 -c "import sys,json; print(json.load(sys.stdin).get('jobState',''))" 2>/dev/null || true)
        if [ "$status" = "COMPLETED" ]; then
          return 0
        elif [ "$status" = "FAILED" ] || [ "$status" = "CANCELED" ]; then
          echo "    SQL FAILED ($status): $sql" >&2
          return 1
        fi
        sleep 2
      done
      echo "    SQL TIMEOUT: $sql" >&2
      return 1
    fi
    return 0
  else
    echo "    SQL ERROR (HTTP $code): $sql" >&2
    echo "    $body" >&2
    return 1
  fi
}

# ── 6. Create spaces ──────────────────────────────────────────────────
echo ""
echo "Creating spaces..."

for SPACE in analytics engineering; do
  SPACE_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$DREMIO_URL/api/v3/catalog" \
    -H "Content-Type: application/json" \
    -H "Authorization: _dremio${TOKEN}" \
    -d "{\"entityType\": \"space\", \"name\": \"$SPACE\"}")

  if [ "$SPACE_CODE" = "200" ]; then
    echo "  Created space: $SPACE"
  elif [ "$SPACE_CODE" = "409" ] || [ "$SPACE_CODE" = "400" ]; then
    echo "  Space '$SPACE' already exists."
  else
    echo "  WARNING: space '$SPACE' returned HTTP $SPACE_CODE."
  fi
done

# ── 7. Create views in spaces ─────────────────────────────────────────
echo ""
echo "Creating views..."

run_sql "CREATE OR REPLACE VIEW analytics.customer_overview AS SELECT id, name, city FROM nessie_catalog.demo.customers" "$TOKEN" \
  && echo "  Created view: analytics.customer_overview" || true

run_sql "CREATE OR REPLACE VIEW analytics.order_summary AS SELECT o.order_id, c.name AS customer_name, o.product, o.amount FROM nessie_catalog.demo.orders o JOIN nessie_catalog.demo.customers c ON o.customer_id = c.id" "$TOKEN" \
  && echo "  Created view: analytics.order_summary" || true

run_sql "CREATE OR REPLACE VIEW analytics.revenue_by_city AS SELECT c.city, COUNT(*) AS order_count, SUM(o.amount) AS total_revenue FROM nessie_catalog.demo.orders o JOIN nessie_catalog.demo.customers c ON o.customer_id = c.id GROUP BY c.city" "$TOKEN" \
  && echo "  Created view: analytics.revenue_by_city" || true

run_sql "CREATE OR REPLACE VIEW engineering.raw_customers AS SELECT * FROM nessie_catalog.demo.customers" "$TOKEN" \
  && echo "  Created view: engineering.raw_customers" || true

run_sql "CREATE OR REPLACE VIEW engineering.raw_orders AS SELECT * FROM nessie_catalog.demo.orders" "$TOKEN" \
  && echo "  Created view: engineering.raw_orders" || true

# ── 8. Create roles ───────────────────────────────────────────────────
echo ""
echo "Creating roles..."

for ROLE in analysts engineers; do
  ROLE_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$DREMIO_URL/api/v3/rbac/roles" \
    -H "Content-Type: application/json" \
    -H "Authorization: _dremio${TOKEN}" \
    -d "{\"roleName\": \"$ROLE\"}")

  if [ "$ROLE_CODE" = "200" ]; then
    echo "  Created role: $ROLE"
  elif [ "$ROLE_CODE" = "409" ] || [ "$ROLE_CODE" = "400" ]; then
    echo "  Role '$ROLE' already exists."
  else
    echo "  WARNING: role '$ROLE' returned HTTP $ROLE_CODE."
  fi
done

# ── 9. Create users ───────────────────────────────────────────────────
echo ""
echo "Creating users..."

create_user() {
  local uname="$1" fname="$2" lname="$3" email="$4" pass="$5"
  local resp code body
  resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$DREMIO_URL/apiv2/user/$uname" \
    -H "Content-Type: application/json" \
    -H "Authorization: _dremio${TOKEN}" \
    -d "{
      \"userName\": \"$uname\",
      \"firstName\": \"$fname\",
      \"lastName\": \"$lname\",
      \"email\": \"$email\",
      \"createdAt\": 0,
      \"password\": \"$pass\"
    }")

  code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')

  if [ "$code" = "200" ]; then
    echo "  Created user: $uname ($pass)"
  elif [ "$code" = "409" ] || [ "$code" = "400" ]; then
    echo "  User '$uname' already exists."
  else
    echo "  WARNING: user '$uname' returned HTTP $code."
    echo "  Body: $body"
  fi
}

create_user "alice"   "Alice"   "Analyst"   "alice@example.com"   "alice123"
create_user "bob"     "Bob"     "Engineer"  "bob@example.com"     "bob12345"
create_user "charlie" "Charlie" "Viewer"    "charlie@example.com" "charlie123"

# ── 10. Assign users to roles ─────────────────────────────────────────
echo ""
echo "Assigning users to roles..."

assign_role() {
  local role="$1" user="$2"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$DREMIO_URL/api/v3/rbac/roles/$role/members" \
    -H "Content-Type: application/json" \
    -H "Authorization: _dremio${TOKEN}" \
    -d "{\"userName\": \"$user\"}")

  if [ "$code" = "200" ] || [ "$code" = "204" ]; then
    echo "  $user -> $role"
  elif [ "$code" = "409" ]; then
    echo "  $user already in $role."
  else
    echo "  WARNING: assigning $user to $role returned HTTP $code."
  fi
}

assign_role "analysts"  "alice"
assign_role "analysts"  "charlie"
assign_role "engineers" "bob"

# ── 11. Grant privileges ──────────────────────────────────────────────
echo ""
echo "Granting privileges..."

grant_privilege() {
  local role="$1" obj_type="$2" obj_path="$3" priv="$4"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$DREMIO_URL/api/v3/rbac/grants" \
    -H "Content-Type: application/json" \
    -H "Authorization: _dremio${TOKEN}" \
    -d "{
      \"roleId\": \"$role\",
      \"objectType\": \"$obj_type\",
      \"objectPath\": \"$obj_path\",
      \"privilege\": \"$priv\"
    }")

  if [ "$code" = "200" ] || [ "$code" = "204" ]; then
    echo "  GRANT $priv ON $obj_type $obj_path TO ROLE $role"
  elif [ "$code" = "409" ]; then
    echo "  (already granted) $priv ON $obj_type $obj_path TO ROLE $role"
  else
    echo "  WARNING: grant returned HTTP $code for $priv on $obj_path to $role."
  fi
}

# analysts role: SELECT on analytics views
grant_privilege "analysts" "VDS" "analytics.customer_overview" "SELECT"
grant_privilege "analysts" "VDS" "analytics.order_summary"     "SELECT"
grant_privilege "analysts" "VDS" "analytics.revenue_by_city"   "SELECT"

# engineers role: SELECT on both spaces' views
grant_privilege "engineers" "VDS" "engineering.raw_customers"    "SELECT"
grant_privilege "engineers" "VDS" "engineering.raw_orders"       "SELECT"
grant_privilege "engineers" "VDS" "analytics.customer_overview"  "SELECT"
grant_privilege "engineers" "VDS" "analytics.order_summary"      "SELECT"
grant_privilege "engineers" "VDS" "analytics.revenue_by_city"    "SELECT"

# engineers can also create views in engineering space
grant_privilege "engineers" "SPACE" "engineering" "CREATE_VIEW"

# ── Done ────────────────────────────────────────────────────────────────
cat <<'EOF'

============================================================
  All services are running with sample data and RBAC!

  Dremio UI:      http://localhost:9047

  ┌──────────────────────────────────────────────────────────┐
  │  USERS & CREDENTIALS                                     │
  │                                                          │
  │  admin   / admin123    (admin — full access)             │
  │  alice   / alice123    (role: analysts)                  │
  │  bob     / bob12345    (role: engineers)                 │
  │  charlie / charlie123  (role: analysts)                  │
  └──────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │  SPACES & VIEWS                                          │
  │                                                          │
  │  analytics/                                              │
  │    ├── customer_overview   (id, name, city)              │
  │    ├── order_summary       (order_id, customer, product) │
  │    └── revenue_by_city     (city, count, revenue)        │
  │                                                          │
  │  engineering/                                            │
  │    ├── raw_customers       (all customer columns)        │
  │    └── raw_orders          (all order columns)           │
  └──────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │  RBAC PERMISSIONS                                        │
  │                                                          │
  │  analysts  → SELECT on analytics.* views                 │
  │  engineers → SELECT on all views + CREATE_VIEW           │
  │              in engineering space                         │
  │  charlie   → analysts role (same as alice)               │
  │                                                          │
  │  Test: login as alice, try SELECT on engineering.*       │
  │        → should be denied (no grant)                     │
  └──────────────────────────────────────────────────────────┘

  MinIO Console:  http://localhost:9090
                  Login: minioadmin / minioadmin

  Keycloak:       http://localhost:8080
                  Login: admin / admin
============================================================
EOF
