#!/bin/sh
set -e

echo "Creating Lakekeeper warehouse..."

curl -sf -X POST http://lakekeeper:8181/management/v1/warehouse \
  -H "Content-Type: application/json" \
  -d '{
    "warehouse-name": "lakehouse",
    "project-id": "00000000-0000-0000-0000-000000000000",
    "storage-profile": {
      "type": "s3",
      "bucket": "lakebucket",
      "region": "us-east-1",
      "endpoint": "http://minio:9000",
      "path-style-access": true,
      "flavor": "minio",
      "sts-enabled": false
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
