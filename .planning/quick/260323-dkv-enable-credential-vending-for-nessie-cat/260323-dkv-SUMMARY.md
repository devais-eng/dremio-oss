---
phase: quick
plan: 260323-dkv
subsystem: samples/iceberg-rest-catalog
tags: [nessie, credential-vending, sts, iceberg, s3, minio]
dependency_graph:
  requires: []
  provides: [NESSIE-CRED-VEND]
  affects: [samples/iceberg-rest-catalog]
tech_stack:
  added: []
  patterns:
    - Nessie client-side IAM (STS) credential vending via MinIO STS endpoint
    - Dremio RESTCATALOG source without static S3 credentials (vended-only pattern)
key_files:
  modified:
    - samples/iceberg-rest-catalog/docker-compose.yml
    - samples/iceberg-rest-catalog/scripts/dremio-init.sh
decisions:
  - "Use MinIO port 9000 as both S3 and STS endpoint (MinIO STS = S3 endpoint)"
  - "Add dremio.bucket.discovery.enabled=false to nessie_catalog (vended creds are warehouse-scoped, cannot list all buckets)"
  - "Polaris static keys retained unchanged (credential vending for Polaris is a separate concern)"
metrics:
  duration: "~85 seconds"
  completed_date: "2026-03-23"
  tasks_completed: 2
  files_modified: 2
---

# Quick Task 260323-dkv: Enable Credential Vending for Nessie Catalog Summary

**One-liner:** Nessie STS credential vending enabled via three env vars in docker-compose.yml and static S3 keys removed from Dremio nessie_catalog source config.

## Tasks Completed

| Task | Name | Commit | Files Modified |
|------|------|--------|----------------|
| 1 | Add Nessie STS credential vending env vars to docker-compose.yml | eafe52f2c | `samples/iceberg-rest-catalog/docker-compose.yml` |
| 2 | Update nessie_catalog source config in dremio-init.sh to use vended credentials | 413e2f799 | `samples/iceberg-rest-catalog/scripts/dremio-init.sh` |

## Changes Made

### Task 1 — docker-compose.yml

Added three env vars to the `nessie` service environment block, after the existing S3 credentials block:

```yaml
# Client-side credential vending via STS
- nessie.catalog.service.s3.default-options.client-iam.enabled=true
- nessie.catalog.service.s3.default-options.client-iam.assume-role=arn:aws:iam::000000000000:role/nessie
- nessie.catalog.service.s3.default-options.sts-endpoint=http://minio:9000
```

The `assume-role` ARN value is ignored by MinIO but required by the Nessie STS client. The STS endpoint is the same as the MinIO S3 endpoint (port 9000).

### Task 2 — dremio-init.sh

Updated the `nessie_catalog` source `propertyList`:

**Removed:**
- `fs.s3a.access.key` (minioadmin)
- `fs.s3a.secret.key` (minioadmin)
- `fs.s3a.aws.credentials.provider` (SimpleAWSCredentialsProvider)

**Added:**
- `dremio.bucket.discovery.enabled=false` (vended credentials are scoped to the warehouse path and cannot list all buckets)

**Retained unchanged:**
- `warehouse`, `fs.s3a.endpoint`, `fs.s3a.path.style.access`, `fs.s3a.connection.ssl.enabled`, `dremio.s3.compat`

The resulting nessie_catalog propertyList now matches the lakekeeper_catalog pattern.

## Verification Results

1. `grep "client-iam" docker-compose.yml` — shows `enabled=true` and `assume-role` env vars
2. `grep "sts-endpoint" docker-compose.yml` — shows `http://minio:9000`
3. nessie_catalog propertyList has NO `fs.s3a.access.key`, `fs.s3a.secret.key`, or `SimpleAWSCredentialsProvider`
4. nessie_catalog propertyList has `dremio.bucket.discovery.enabled=false`
5. polaris_catalog source config is UNCHANGED (static keys retained)
6. lakekeeper_catalog source config is UNCHANGED

## Deviations from Plan

None — plan executed exactly as written.

## Self-Check: PASSED

- `eafe52f2c` — feat(260323-dkv): add Nessie STS credential vending env vars to docker-compose.yml — FOUND
- `413e2f799` — feat(260323-dkv): update nessie_catalog source config to use vended credentials — FOUND
- `samples/iceberg-rest-catalog/docker-compose.yml` — FOUND (contains client-iam.enabled=true and sts-endpoint)
- `samples/iceberg-rest-catalog/scripts/dremio-init.sh` — FOUND (no static keys in nessie_catalog block)
