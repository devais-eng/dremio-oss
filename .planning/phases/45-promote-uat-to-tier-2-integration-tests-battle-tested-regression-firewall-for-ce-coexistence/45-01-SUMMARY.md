---
phase: 45-promote-uat-to-tier-2-integration-tests-battle-tested-regression-firewall-for-ce-coexistence
plan: 01
subsystem: testing
tags: [testcontainers, minio, nessie, iceberg, s3, pgvector, multi-source, integration-tests, oracle, adbc]

# Dependency graph
requires:
  - phase: 39-docker-integration-tests
    provides: DremioJdbcContainer, DremioJdbcPgContainer, DremioJdbcOracleContainer, TestDremioJdbcIntegration (41 tests)
  - phase: 44-pgvector-gap-closure
    provides: KNN distance pushdown, ADBC vector transfer
provides:
  - DremioJdbcMinioContainer (S3-compatible storage for Testcontainers)
  - DremioJdbcNessieContainer (Iceberg REST catalog for Testcontainers)
  - Multi-source UAT seed methods (products, orders, order_items, customers, regions, Iceberg, S3/CSV)
  - 6 UAT source JSON builders (pg_jdbc, pg_adbc, oracle_src, nessie_rest, nessie_ver, s3_parquet)
  - 25 new testUatS{1-4} @Test methods (same-source pushdown, ADBC vs JDBC, cross-source, pgvector)
affects: [45-02-PLAN, future-ce-coexistence-regression]

# Tech tracking
tech-stack:
  added: [io.minio:minio:8.5.7]
  patterns: [multi-source-testcontainers, csv-promote-api, dremio-sql-ctas-iceberg-seeding]

key-files:
  created:
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcMinioContainer.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcNessieContainer.java
  modified:
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/TestDremioJdbcIntegration.java
    - plugins/jdbc-base/pom.xml

key-decisions:
  - "MinIO client SDK (io.minio:minio:8.5.7) chosen for bucket creation and file upload — lightweight, purpose-built for S3-compatible APIs"
  - "CSV upload to MinIO with Dremio promote API instead of Parquet — avoids heavyweight Hadoop/Parquet writer dependencies in test classpath"
  - "Iceberg tables seeded via Dremio SQL CTAS into nessie_rest source — most reliable approach using Dremio's own catalog integration"
  - "ADBC KNN nearest test added as 25th test to reach plan's target count (plan listed 24 concrete tests but stated 25 total)"

patterns-established:
  - "Multi-source Testcontainers: MinIO+Nessie containers with shared Docker network, order-based ClassRule startup"
  - "UAT test naming: testUatS{section}{description} prefix distinguishes from existing test methods"
  - "CSV file seeding: upload CSV to MinIO, promote via Dremio file_format API with Text type"
  - "Source JSON builders: per-source static method returning raw JSON string for Dremio REST API"

requirements-completed: [TEST-02]

# Metrics
duration: 7min
completed: 2026-03-20
---

# Phase 45 Plan 01: Multi-Source Container Infrastructure + 25 UAT S1-S4 Tests Summary

**MinIO/Nessie Testcontainers + 6 Dremio source configs + 25 multi-source UAT regression tests covering PG/Oracle pushdown, ADBC protocol, cross-source joins, and pgvector semantic search**

## Performance

- **Duration:** 7 min
- **Started:** 2026-03-20T09:13:34Z
- **Completed:** 2026-03-20T09:20:39Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- DremioJdbcMinioContainer and DremioJdbcNessieContainer provide S3 and Iceberg REST catalog infrastructure for integration tests
- Complete multi-source UAT data model seeded: PG products/orders/order_items (with 4-dim pgvector embeddings), Oracle CUSTOMERS/REGIONS, Iceberg product_categories/monthly_sales, S3 CSV files
- 25 new testUatS{1-4} tests compile: 6 same-source pushdown (3 PG + 3 Oracle V$SQL), 4 ADBC vs JDBC protocol, 3 cross-source PG x Oracle, 12 pgvector semantic search
- All 41 existing tests preserved unchanged; total 66 @Test methods

## Task Commits

Both tasks committed together (per user constraint):

1. **Task 1 + Task 2: Container infrastructure, seed methods, source configs, 25 tests** - `4f1ba6012` (feat)

## Files Created/Modified
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcMinioContainer.java` - MinIO S3-compatible container wrapper implementing DremioContainer
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/DremioJdbcNessieContainer.java` - Nessie Iceberg REST catalog container wrapper implementing DremioContainer
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/integration/TestDremioJdbcIntegration.java` - Expanded with MINIO/NESSIE @ClassRule fields, 6 UAT seed methods, 6 source JSON builders, 25 new @Test methods
- `plugins/jdbc-base/pom.xml` - Added io.minio:minio:8.5.7 test dependency

## Decisions Made
- **MinIO client SDK chosen over AWS SDK**: io.minio:minio:8.5.7 is lightweight (300KB) and purpose-built for S3-compatible APIs; AWS SDK v2 is heavy
- **CSV upload instead of Parquet**: Avoids Hadoop/Parquet writer dependencies in test classpath. Dremio's file_format/promote API supports Text/CSV format detection. S3 source reads CSV with header extraction
- **Iceberg seeding via Dremio SQL CTAS**: Most reliable approach since it uses Dremio's own nessie_rest catalog integration. No need for PyIceberg or external catalog client in Java tests
- **25th test added (ADBC KNN nearest)**: Plan listed 24 concrete test methods but stated 25 total. Added testUatS4AdbcKnnNearestElectronics to verify ADBC path produces correct KNN results

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Plan arithmetic: 11 Section 4 tests listed but stated 12**
- **Found during:** Task 2
- **Issue:** Plan text said "SECTION 4: PGVECTOR SEMANTIC SEARCH (12 tests)" and "Total: 6 + 4 + 3 + 12 = 25" but only listed 11 concrete test methods (3+2+5+1=11)
- **Fix:** Added testUatS4AdbcKnnNearestElectronics to reach the stated 25 total
- **Files modified:** TestDremioJdbcIntegration.java
- **Verification:** grep -c "@Test" confirms 66 total (41 existing + 25 new)
- **Committed in:** 4f1ba6012

---

**Total deviations:** 1 auto-fixed (1 bug in plan arithmetic)
**Impact on plan:** Minimal — one additional test method added to match stated count. No scope creep.

## Issues Encountered
- ErrorProne requires Java 17+; system default is Java 11. Resolved by using JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 for compilation. This is a pre-existing environment condition, not a plan issue.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Container infrastructure ready for Plan 45-02 to add Sections 5-9 tests (function composition, Iceberg/Nessie, cross-source semantic search, edge cases, non-JDBC source validation)
- All 6 Dremio source configurations are in place for the full multi-source UAT
- Iceberg and S3 seeding patterns established and reusable

## Self-Check: PASSED

- All created files exist (DremioJdbcMinioContainer.java, DremioJdbcNessieContainer.java, 45-01-SUMMARY.md)
- Commit 4f1ba6012 verified in git log
- @Test count: 66 (>= 66 required)
- UAT test count: 25 (exactly 25 required)

---
*Phase: 45-promote-uat-to-tier-2-integration-tests-battle-tested-regression-firewall-for-ce-coexistence*
*Completed: 2026-03-20*
