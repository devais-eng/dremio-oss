---
phase: 08-end-to-end-validation
plan: 02
subsystem: infra
tags: [iceberg, rest-catalog, lakekeeper, minio, s3, dremio]

requires:
  - phase: 08-end-to-end-validation/01
    provides: "Running Lakekeeper + MinIO + Dremio stack with test data"
provides:
  - "All 6 Phase 8 success criteria validated end-to-end"
  - "Working RESTCATALOG source config recipe for Lakekeeper + MinIO"
  - "Credential vending gap documented as v1.1 known limitation"
affects: []

tech-stack:
  added: []
  patterns: [fs-s3a-property-names, endpoint-without-protocol]

key-files:
  created: []
  modified: []

key-decisions:
  - "fs.s3a.* Hadoop property names required (not Iceberg s3.* names) — Dremio S3FileSystem reads from Hadoop Configuration"
  - "fs.s3a.endpoint must be without protocol prefix (localhost:9000 not http://localhost:9000) — Dremio appends http:// or https:// based on fs.s3a.connection.ssl.enabled"
  - "fs.s3a.connection.ssl.enabled=false required for HTTP MinIO endpoints"
  - "fs.s3a.aws.credentials.provider=org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider needed to use static access key/secret"
  - "dremio.s3.compat=true for S3-compatible storage (MinIO)"
  - "Credential vending from Lakekeeper loadTable() not propagated through DremioFileIO — static creds workaround validated, documented as v1.1 limitation"
  - "minio hostname must resolve on host (added to /etc/hosts) because Lakekeeper vends Docker-internal hostnames"

patterns-established:
  - "RESTCATALOG source config recipe: warehouse + fs.s3a.endpoint (no protocol) + fs.s3a.access.key + fs.s3a.secret.key + fs.s3a.path.style.access + fs.s3a.connection.ssl.enabled + SimpleAWSCredentialsProvider + dremio.s3.compat"

requirements-completed: [READ-01, READ-02, READ-03, CONN-01, CONN-02, CONN-03]

duration: ~30min
completed: 2026-02-20
---

# Plan 08-02: End-to-End Validation Summary

**All 6 success criteria validated: source creation, namespace browsing, table listing, SELECT queries, OAuth2 token auth, and static credential workaround for storage access**

## Performance

- **Duration:** ~30 min
- **Completed:** 2026-02-20
- **Tasks:** 2 (both human-verify checkpoints)
- **Files modified:** 0 (pure validation, no code changes)

## Accomplishments
- RESTCATALOG source pointing to Lakekeeper reaches GOOD health state (CONN-01)
- Namespace `testns` browsable via Dremio API and UI (READ-01)
- Table `users` listed under `testns` (READ-02)
- `SELECT * FROM lakekeeper.testns.users LIMIT 10` returns 10 rows with id (BIGINT), name (VARCHAR), city (VARCHAR) (READ-03)
- Source with `rest.token` in `secretPropertyList` connects and reads data — bearer token silently accepted by Lakekeeper (CONN-02)
- Static MinIO credentials via `fs.s3a.*` properties enable Parquet reads — credential vending non-propagation documented as v1.1 known limitation (CONN-03)

## Task Commits

No code commits — pure validation plan. All results documented in this summary.

## Files Created/Modified
None — validation only.

## Decisions Made
- `fs.s3a.*` Hadoop property names required (not Iceberg `s3.*` names) because Dremio's `S3FileSystem` reads from Hadoop Configuration, not Iceberg `S3FileIOProperties`
- `fs.s3a.endpoint` must be without protocol prefix (`localhost:9000` not `http://localhost:9000`) — Dremio prepends protocol based on `fs.s3a.connection.ssl.enabled`
- `fs.s3a.aws.credentials.provider=org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider` must be explicitly set for static access key authentication
- `dremio.s3.compat=true` needed for S3-compatible storage (MinIO)
- `minio` hostname added to `/etc/hosts → 127.0.0.1` because Lakekeeper vends Docker-internal hostnames in credential vending responses

## Deviations from Plan

### Auto-fixed Issues

**1. S3 endpoint format**
- **Found during:** Task 1 (source creation)
- **Issue:** `fs.s3a.endpoint=http://localhost:9000` caused connection timeout — Dremio appends protocol based on ssl.enabled flag
- **Fix:** Changed to `fs.s3a.endpoint=localhost:9000` with `fs.s3a.connection.ssl.enabled=false`

**2. Iceberg vs Hadoop property names**
- **Found during:** Task 1 (SELECT query)
- **Issue:** `s3.endpoint`, `s3.access-key-id`, etc. (Iceberg names) don't propagate to Dremio's S3FileSystem — "Credentials for the Storage Provider" error
- **Fix:** Switched to `fs.s3a.*` Hadoop property names — these flow through `buildCatalogProperties()` into the Hadoop Configuration used by `DatasetFileSystemCache`

**3. Docker hostname resolution**
- **Found during:** PyIceberg seeding (pre-validation)
- **Issue:** Lakekeeper vends `http://minio:9000` (Docker-internal hostname) in credential vending responses — not resolvable from host
- **Fix:** Added `127.0.0.1 minio` to `/etc/hosts`

---

**Total deviations:** 3 auto-fixed (all configuration/infrastructure)
**Impact on plan:** No scope creep. All fixes were necessary to establish connectivity between host-based Dremio and Docker-based Lakekeeper/MinIO.

## Issues Encountered
- StarRocks container in Lakekeeper minimal example failed to start — resolved by removing from docker-compose
- Lakekeeper bootstrap required (terms acceptance + warehouse creation) when started fresh

## Known Limitations (v1.1)
- **Credential vending non-propagation:** Lakekeeper vends S3 credentials in `loadTable()` response, but `DremioFileIO` uses only static Hadoop Configuration from `propertyList`. Works for long-lived creds (MinIO); does NOT work for IAM/STS short-lived tokens. Fix point: `AbstractRestCatalogAccessor.getTableHandleInternal()`.
- **hasAccessPermission() no-op:** All Dremio users have full read access to all REST catalog tables.

## Next Phase Readiness
- Phase 8 validation complete — all success criteria met
- Ready for phase completion and milestone closure

---
*Phase: 08-end-to-end-validation*
*Completed: 2026-02-20*
