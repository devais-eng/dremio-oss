#!/bin/sh
set -e

echo "Bootstrapping Lakekeeper..."
curl -sf -X POST http://lakekeeper:8181/management/v1/bootstrap \
  -H "Content-Type: application/json" \
  -d '{"accept-terms-of-use": true}' || echo "  Already bootstrapped."

echo "Creating Lakekeeper warehouse..."
curl -sf -X POST http://lakekeeper:8181/management/v1/warehouse \
  -H "Content-Type: application/json" \
  -d '{
    "warehouse-name": "lakehouse",
    "storage-profile": {
      "type": "s3",
      "bucket": "lakebucket",
      "region": "us-east-1",
      "endpoint": "http://minio:9000",
      "path-style-access": true,
      "flavor": "minio",
      "sts-enabled": true,
      "remote-signing-enabled": true
    },
    "storage-credential": {
      "type": "s3",
      "credential-type": "access-key",
      "aws-access-key-id": "minioadmin",
      "aws-secret-access-key": "minioadmin"
    }
  }'

echo ""
echo "Lakekeeper warehouse ready."
