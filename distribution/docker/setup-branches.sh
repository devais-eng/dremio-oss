#!/usr/bin/env bash
# =============================================================================
# Setup script for Nessie branch-aware REST catalog demo.
#
# Prerequisites: docker compose up -d  (minio + nessie + dremio running)
# Dependencies:  mc (MinIO client), curl, python3
#
# Creates:
#   MinIO IAM:
#     - "nessie-writer" (read-write)  — used by Nessie
#     - "dremio-reader" (read-only)   — for Dremio source config
#
#   Nessie branches (all from same ancestor on main):
#     main    →  analytics.customers
#     dev     →  analytics.orders      + inherits customers from main
#     staging →  analytics.products    + inherits customers from main
# =============================================================================
set -euo pipefail

MINIO_ENDPOINT="http://localhost:9000"
NESSIE_REST="http://localhost:19120"
NESSIE_API="$NESSIE_REST/api/v2"

# Nessie Iceberg REST prefix format: {ref}|{warehouse}
# The pipe must be URL-encoded as %7C
WAREHOUSE="warehouse"
ICE_BASE="$NESSIE_REST/iceberg/v1"

BUCKET="warehouse"
WRITER_USER="nessie-writer"
WRITER_PASS="nessie-writer-secret"
READER_USER="dremio-reader"
READER_PASS="dremio-reader-secret"

# Helper: build Nessie Iceberg REST prefix for a branch
ice_prefix() {
  local branch="$1"
  echo "${branch}%7C${WAREHOUSE}"
}

echo "=== Step 1: Configure MinIO client ==="
mc alias set local "$MINIO_ENDPOINT" minioadmin minioadmin --api s3v4

echo "=== Step 2: Create bucket ==="
mc mb "local/$BUCKET" --ignore-existing

echo "=== Step 3: Create IAM users ==="

# --- Writer user (read-write) — used by Nessie ---
mc admin user add local "$WRITER_USER" "$WRITER_PASS" 2>/dev/null || true

cat > /tmp/readwrite-policy.json <<'POLICY'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:*"],
      "Resource": ["arn:aws:s3:::warehouse", "arn:aws:s3:::warehouse/*"]
    }
  ]
}
POLICY
mc admin policy create local readwrite /tmp/readwrite-policy.json 2>/dev/null || true
mc admin policy attach local readwrite --user "$WRITER_USER" 2>/dev/null || true
echo "  Created user '$WRITER_USER' with read-write policy"

# --- Reader user (read-only) — for Dremio ---
mc admin user add local "$READER_USER" "$READER_PASS" 2>/dev/null || true

cat > /tmp/readonly-policy.json <<'POLICY'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:GetBucketLocation", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::warehouse", "arn:aws:s3:::warehouse/*"]
    }
  ]
}
POLICY
mc admin policy create local readonly /tmp/readonly-policy.json 2>/dev/null || true
mc admin policy attach local readonly --user "$READER_USER" 2>/dev/null || true
echo "  Created user '$READER_USER' with read-only policy"

echo ""
echo "=== Step 4: Wait for Nessie to be ready ==="
for i in $(seq 1 30); do
  if curl -sf "$NESSIE_API/config" > /dev/null 2>&1; then
    echo "  Nessie is ready"
    break
  fi
  echo "  Waiting for Nessie... ($i/30)"
  sleep 2
done

echo ""
echo "=== Step 5: Create namespace + table on main ==="

MAIN_PREFIX=$(ice_prefix "main")

echo "  Creating namespace 'analytics' on main..."
curl -sf -X POST "$ICE_BASE/$MAIN_PREFIX/namespaces" \
  -H "Content-Type: application/json" \
  -d '{"namespace": ["analytics"]}' | python3 -m json.tool
echo ""

echo "  Creating table 'analytics.customers' on main..."
curl -sf -X POST "$ICE_BASE/$MAIN_PREFIX/namespaces/analytics/tables" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "customers",
    "stage-create": false,
    "schema": {
      "type": "struct",
      "schema-id": 0,
      "fields": [
        {"id": 1, "name": "id", "type": "long", "required": true},
        {"id": 2, "name": "name", "type": "string", "required": false},
        {"id": 3, "name": "email", "type": "string", "required": false}
      ]
    }
  }' | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'  OK: {d[\"metadata\"][\"location\"]}')"

echo ""
echo "=== Step 6: Create branches from main (same ancestor) ==="
MAIN_HASH=$(curl -sf "$NESSIE_API/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
echo "  Main hash: $MAIN_HASH"

echo "  Creating branch 'dev' from main..."
curl -sf -X POST "$NESSIE_API/trees?name=dev&type=BRANCH" \
  -H "Content-Type: application/json" \
  -d "{\"type\":\"BRANCH\",\"name\":\"main\",\"hash\":\"$MAIN_HASH\"}" > /dev/null
echo "  OK"

echo "  Creating branch 'staging' from main..."
curl -sf -X POST "$NESSIE_API/trees?name=staging&type=BRANCH" \
  -H "Content-Type: application/json" \
  -d "{\"type\":\"BRANCH\",\"name\":\"main\",\"hash\":\"$MAIN_HASH\"}" > /dev/null
echo "  OK"

echo ""
echo "=== Step 7: Create branch-specific tables ==="

DEV_PREFIX=$(ice_prefix "dev")
STAGING_PREFIX=$(ice_prefix "staging")

echo "  Creating table 'analytics.orders' on dev..."
curl -sf -X POST "$ICE_BASE/$DEV_PREFIX/namespaces/analytics/tables" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "orders",
    "stage-create": false,
    "schema": {
      "type": "struct",
      "schema-id": 0,
      "fields": [
        {"id": 1, "name": "order_id", "type": "long", "required": true},
        {"id": 2, "name": "customer_id", "type": "long", "required": false},
        {"id": 3, "name": "amount", "type": "double", "required": false}
      ]
    }
  }' | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'  OK: {d[\"metadata\"][\"location\"]}')"

echo "  Creating table 'analytics.products' on staging..."
curl -sf -X POST "$ICE_BASE/$STAGING_PREFIX/namespaces/analytics/tables" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "products",
    "stage-create": false,
    "schema": {
      "type": "struct",
      "schema-id": 0,
      "fields": [
        {"id": 1, "name": "product_id", "type": "long", "required": true},
        {"id": 2, "name": "name", "type": "string", "required": false},
        {"id": 3, "name": "price", "type": "double", "required": false}
      ]
    }
  }' | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'  OK: {d[\"metadata\"][\"location\"]}')"

echo ""
echo "=== Step 8: Verify branch contents ==="
echo ""
echo "Tables on main:"
curl -sf "$ICE_BASE/$MAIN_PREFIX/namespaces/analytics/tables" | python3 -m json.tool
echo ""
echo "Tables on dev:"
curl -sf "$ICE_BASE/$DEV_PREFIX/namespaces/analytics/tables" | python3 -m json.tool
echo ""
echo "Tables on staging:"
curl -sf "$ICE_BASE/$STAGING_PREFIX/namespaces/analytics/tables" | python3 -m json.tool

echo ""
echo "============================================="
echo " SETUP COMPLETE"
echo "============================================="
echo ""
echo "Dremio UI:   http://localhost:9047"
echo "MinIO UI:    http://localhost:9001  (minioadmin / minioadmin)"
echo "Nessie API:  http://localhost:19120/api/v2"
echo ""
echo "Add a RESTCATALOG source in Dremio with:"
echo "  Name:           nessie_catalog"
echo "  REST Endpoint:  http://nessie:19120/iceberg"
echo "  Enable Nessie:  true"
echo "  Properties:"
echo "    s3.endpoint         = http://minio:9000"
echo "    s3.access-key-id    = $READER_USER"
echo "    s3.secret-access-key= $READER_PASS"
echo "    s3.path-style-access= true"
echo ""
echo "Test queries:"
echo "  SELECT * FROM nessie_catalog.analytics.customers"
echo "  SELECT * FROM nessie_catalog.analytics.customers AT BRANCH \"dev\""
echo "  SELECT * FROM nessie_catalog.analytics.orders AT BRANCH \"dev\""
echo "  SELECT * FROM nessie_catalog.analytics.products AT BRANCH \"staging\""
echo ""
echo "Branch layout:"
echo "  main    -> customers"
echo "  dev     -> customers + orders     (branched from main)"
echo "  staging -> customers + products   (branched from main)"
echo ""
