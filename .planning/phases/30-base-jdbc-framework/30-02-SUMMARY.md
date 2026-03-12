---
phase: 30-base-jdbc-framework
plan: 02
subsystem: database
tags: [jdbc, arrow, schema-discovery, record-reader, group-scan, sub-scan, scan-creator, dremio-plugin]

# Dependency graph
requires:
  - dremio-plugin-jdbc-base module (plan 01)
  - JdbcConnectionPool and BaseJdbcConf (plan 01)
provides:
  - JdbcSchemaFetcher — DatabaseMetaData-based schema/table/column discovery
  - JdbcStoragePlugin fully implements SupportsListingDatasets
  - JdbcSubScan — serializable sub-scan carrying SQL query string
  - JdbcGroupScan — single-node GroupScan wrapping JdbcSubScan
  - JdbcRecordReader — JDBC ResultSet to Arrow vector conversion
  - JdbcScanCreator — ProducerOperator.Creator binding for the execution engine
affects: [30-03, 30-04, 30-05]

# Tech tracking
tech-stack:
  added:
    - "org.apache.arrow.adapter.jdbc.JdbcToArrowUtils — JDBC-to-Arrow type mapping via JdbcFieldInfo"
  patterns:
    - "JdbcSchemaFetcher with isSystemSchema()/mapJdbcType() protected hooks for concrete connector overrides"
    - "AbstractSubScan + @JsonTypeName('jdbc-sub-scan') + @JsonCreator pattern for operator serialization"
    - "GroupScan<SimpleCompleteWork> with getMaxParallelizationWidth()=1 for single-node JDBC execution"
    - "AbstractRecordReader with setup()/next()/close() + TypeHelper.getValueVectorClass() for vector registration"

key-files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/schema/JdbcSchemaFetcher.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcSubScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcGroupScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcScanCreator.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/JdbcRecordReader.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java — real SupportsListingDatasets implementation, schemaFetcher field

key-decisions:
  - "BatchSchema.findFieldIgnoreCase() returns Optional<Field> — guarded with .isPresent() before .get()"
  - "JdbcSubScan stores tableSchemaPath as List<String> for AbstractSubScan's referencedTables; getFullSchema() is @JsonIgnore to avoid double-serialization with AbstractSubScan"
  - "JdbcGroupScan models after InfoSchemaGroupScan (not AbstractGroupScan) to avoid TableMetadata dependency that does not apply to JDBC full-table scans"
  - "DatasetStats.of(UNKNOWN_ROW_COUNT, false, DEFAULT_SCAN_FACTOR) used in getDatasetMetadata() — JDBC provides no row count without COUNT(*)"
  - "PartitionChunk.of(DatasetSplit.of(0L, 0L)) used in listPartitionChunks() — JDBC tables are non-partitioned single-split"
  - "writeValue() protected in JdbcRecordReader — concrete connectors (e.g. Postgres JSONB) can override per-column type dispatch"

patterns-established:
  - "Pattern: JdbcSchemaFetcher protected hooks allow connector-specific schema filter and type overrides without reimplementing all of DatabaseMetaData"
  - "Pattern: JdbcSubScan @JsonTypeName registration + JdbcScanCreator ProducerOperator.Creator for Dremio operator registry wiring"

requirements-completed:
  - BASE-02
  - BASE-03
  - BASE-04

# Metrics
duration: 10min
completed: 2026-03-12
---

# Phase 30 Plan 02: Schema Discovery and Execution Pipeline Summary

**DatabaseMetaData-based schema enumeration with Arrow type mapping wired into JdbcStoragePlugin, plus the full JdbcSubScan → JdbcGroupScan → JdbcRecordReader → JdbcScanCreator execution pipeline**

## Performance

- **Duration:** 10 min
- **Started:** 2026-03-12T21:32:04Z
- **Completed:** 2026-03-12T21:42:17Z
- **Tasks:** 2
- **Files modified:** 6 (5 created, 1 updated)

## Accomplishments

- `JdbcSchemaFetcher` enumerates schemas via `DatabaseMetaData.getSchemas()`, filters system schemas (`information_schema`, `pg_catalog`, etc.) via overridable `isSystemSchema()`, and maps JDBC types to Arrow using `JdbcToArrowUtils.getArrowTypeFromJdbcType()` via overridable `mapJdbcType()`
- `JdbcStoragePlugin` now provides real `listDatasetHandles()` / `getDatasetHandle()` / `getDatasetMetadata()` / `listPartitionChunks()` backed by the new schema fetcher
- `JdbcSubScan` carries the SQL query string, full table schema, projected columns, and plugin reference; serialized via `@JsonCreator` / `@JsonProperty` / `@JsonTypeName("jdbc-sub-scan")`; `getOperatorType()` returns `CoreOperatorType.JDBC_SUB_SCAN_VALUE` (47)
- `JdbcGroupScan` enforces single-node execution (`getMaxParallelizationWidth()` = 1) and creates `JdbcSubScan` via `getSpecificScan()`
- `JdbcRecordReader.setup()` registers Arrow vectors using `TypeHelper.getValueVectorClass()` and executes the SQL via `PreparedStatement` with `setFetchSize(numRowsPerBatch)` to avoid OOM on large tables
- `JdbcRecordReader.next()` covers all 12 type families: BOOLEAN, INT, BIGINT, FLOAT, DOUBLE, VARCHAR, VARBINARY, DECIMAL, DATE, TIME, TIMESTAMP, plus a fallback to VARCHAR for unknown types
- `JdbcScanCreator` obtains the plugin from `FragmentExecutionContext`, creates `JdbcRecordReader`, and wraps it in `ScanOperator` via `RecordReaderIterator.from(reader)`

## Task Commits

Each task was committed atomically:

1. **Task 1: JdbcSchemaFetcher + SupportsListingDatasets** - `a55264b87` (feat)
2. **Task 2: JdbcSubScan, JdbcGroupScan, JdbcRecordReader, JdbcScanCreator** - `ae788c984` (feat)

**Plan metadata:** `(pending)` (docs: complete plan)

## Files Created/Modified

- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/schema/JdbcSchemaFetcher.java` — created; DatabaseMetaData discovery + JDBC-to-Arrow type mapping
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java` — updated; real SupportsListingDatasets, schemaFetcher field
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcSubScan.java` — created; serializable sub-scan
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcGroupScan.java` — created; single-node GroupScan
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcScanCreator.java` — created; ProducerOperator.Creator
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/JdbcRecordReader.java` — created; ResultSet-to-Arrow batch reader

## Decisions Made

- **BatchSchema.findFieldIgnoreCase() Optional:** The method returns `Optional<Field>` rather than nullable `Field`. Used `.isPresent()` guard in the record reader setup loop.
- **JdbcGroupScan not extending AbstractGroupScan:** `AbstractGroupScan` requires a `TableMetadata` dependency that applies to split-based sources. JDBC uses the simpler `AbstractBase + GroupScan<SimpleCompleteWork>` pattern from `InfoSchemaGroupScan`.
- **Single-partition JDBC:** `listPartitionChunks()` returns one `PartitionChunk.of(DatasetSplit.of(0L, 0L))` to satisfy the catalog contract; JDBC tables have no data-locality partitioning.
- **Unknown row count in DatasetStats:** `DatasetStats.of(-1L, false, 1.0d)` — JDBC provides no row count without a `COUNT(*)` query; negative record count signals to the planner to use other estimates.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed BatchSchema.findFieldIgnoreCase() Optional return type**
- **Found during:** Task 2, JdbcRecordReader compilation
- **Issue:** Plan specified `field == null` null check, but the method returns `Optional<Field>` not a nullable Field
- **Fix:** Changed to `.isPresent()` / `.get()` pattern
- **Files modified:** `JdbcRecordReader.java`
- **Commit:** `ae788c984`

## Issues Encountered

- `BatchSchema.findFieldIgnoreCase()` returns `Optional<Field>` — the plan's null-check pattern did not compile. Auto-fixed per Rule 1.
- Maven requires explicit `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` to pass the Java 21 enforcer check (pre-existing environment issue).

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- `JdbcStoragePlugin` is fully functional for schema discovery
- `JdbcSubScan` / `JdbcGroupScan` / `JdbcScanCreator` / `JdbcRecordReader` provide the complete data path for plan 03 (concrete PostgreSQL connector)
- Plan 03 can subclass `BaseJdbcConf` with `@SourceType` and override `isSystemSchema()` / `mapJdbcType()` for Postgres-specific behaviour

## Self-Check: PASSED

All created files exist on disk. All task commits verified in git log:
- a55264b87: feat(30-02): implement JdbcSchemaFetcher and wire SupportsListingDatasets
- ae788c984: feat(30-02): implement JDBC execution pipeline (RecordReader, GroupScan, SubScan, ScanCreator)

---
*Phase: 30-base-jdbc-framework*
*Completed: 2026-03-12*
