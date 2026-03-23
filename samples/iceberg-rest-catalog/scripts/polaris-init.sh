#!/bin/sh
set -e

POLARIS_URL="http://polaris:8181"
CLIENT_ID="root"
CLIENT_SECRET="s3cr3t"
REALM="POLARIS"

echo "Obtaining Polaris OAuth2 token..."
TOKEN=$(curl -sf "${POLARIS_URL}/api/catalog/v1/oauth/tokens" \
  --user "${CLIENT_ID}:${CLIENT_SECRET}" \
  -H "Polaris-Realm: ${REALM}" \
  -d "grant_type=client_credentials" \
  -d "scope=PRINCIPAL_ROLE:ALL" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

if [ -z "$TOKEN" ]; then
  echo "ERROR: Failed to obtain Polaris token" >&2
  exit 1
fi
echo "  Token obtained."

echo "Creating Polaris catalog 'polaris_catalog'..."
curl -sf -X POST "${POLARIS_URL}/api/management/v1/catalogs" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Polaris-Realm: ${REALM}" \
  -d '{
    "catalog": {
      "name": "polaris_catalog",
      "type": "INTERNAL",
      "readOnly": false,
      "properties": {
        "default-base-location": "s3://polarisbucket"
      },
      "storageConfigInfo": {
        "storageType": "S3",
        "endpoint": "http://minio:9000",
        "endpointInternal": "http://minio:9000",
        "pathStyleAccess": true
      }
    }
  }' || echo "  Catalog may already exist."

echo "Granting CATALOG_MANAGE_CONTENT to catalog_admin..."
curl -sf -X PUT \
  "${POLARIS_URL}/api/management/v1/catalogs/polaris_catalog/catalog-roles/catalog_admin/grants" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Polaris-Realm: ${REALM}" \
  -d '{"type": "catalog", "privilege": "CATALOG_MANAGE_CONTENT"}' || echo "  Grant may already exist."

echo ""
echo "Polaris catalog ready."
