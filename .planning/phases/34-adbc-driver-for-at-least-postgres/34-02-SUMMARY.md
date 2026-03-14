---
phase: 34-adbc-driver-for-at-least-postgres
plan: 02
subsystem: database
tags: [adbc, arrow, postgresql, jdbc-base, protocol-mode, schema-fetcher, storage-plugin]

# Dependency graph
requires:
  - phase: 34-adbc-driver-for-at-least-postgres
    plan: 01
    provides: ProtocolMode enum, AdbcConnectionFactory with two-phase lifecycle, AdbcRecordReader with Arrow batch transfer
provides:
  - AdbcSchemaFetcher for ADBC-based metadata discovery via getObjects() and getTableSchema()
  - JdbcStoragePlugin ADBC lifecycle management (start/close/resolve) with RootAllocator field
  - Protocol branching in JdbcScanCreator based on effective protocol mode
  - BaseJdbcConf protocolMode field at Tag(4) defaulting to AUTO
  - PostgresConf adbcUri() returning libpq-format connection URI
affects: [34-adbc-driver-for-at-least-postgres]

# Tech tracking
tech-stack:
  added: []
  patterns: [protocol mode resolution at source start, ADBC lifecycle management in storage plugin, nested Arrow getObjects() parsing, protocol branching at scan creation]

key-files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/schema/AdbcSchemaFetcher.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/BaseJdbcConf.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcScanCreator.java
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresConf.java

key-decisions:
  - "BaseJdbcConf protocolMode field at Tag(4) -- subclasses inherit, no redeclaration to avoid Protostuff serialization conflicts"
  - "AdbcSchemaFetcher parses nested Arrow getObjects() structure using ListVector offset buffers and StructVector child accessors"
  - "getProtocolMode()/adbcUri() methods added to BaseJdbcConf in Task 1 (deviation) because JdbcStoragePlugin.start() depends on them"
  - "AdbcException has no (String, Throwable) constructor -- fallback catch blocks use RuntimeException wrapper instead"

patterns-established:
  - "Protocol mode resolution at start(): probe ADBC availability once, cache effectiveProtocolMode for query-time branching"
  - "ADBC method branching pattern: if (effectiveProtocolMode == ADBC && adbcSchemaFetcher != null) in each metadata method"
  - "closeAdbcResources() helper: close factory, null fetcher, close allocator -- called from both close() and AUTO fallback"

requirements-completed: [ADBC-01]

# Metrics
duration: 14min
completed: 2026-03-14
---

# Phase 34 Plan 02: ADBC Plugin Wiring Summary

**AdbcSchemaFetcher for ADBC metadata discovery, JdbcStoragePlugin ADBC lifecycle with AUTO fallback, JdbcScanCreator protocol branching, and protocolMode config at Tag(4) with PostgreSQL adbcUri**

## Performance

- **Duration:** 14 min
- **Started:** 2026-03-14T02:10:56Z
- **Completed:** 2026-03-14T02:25:15Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- Created AdbcSchemaFetcher with listSchemas/listTables/tableExists using ADBC getObjects() nested Arrow parsing and getTableSchema() with Dremio type normalization (strip timezone, DateDay->DateMilli)
- Added full ADBC lifecycle management to JdbcStoragePlugin: RootAllocator, AdbcConnectionFactory, AdbcSchemaFetcher fields with protocol mode resolution in start() and cleanup in close()
- Wired protocol branching into JdbcScanCreator, listDatasetHandles, getDatasetHandle, getDatasetMetadata, and containerExists -- all branch on effectiveProtocolMode
- Added protocolMode field to BaseJdbcConf at Tag(4) with null-safe getter, and PostgresConf adbcUri() returning libpq-format postgresql:// URIs

## Task Commits

Each task was committed atomically:

1. **Task 1: AdbcSchemaFetcher and JdbcStoragePlugin ADBC lifecycle** - `b301cab0f` (feat)
2. **Task 2: JdbcScanCreator branching, BaseJdbcConf and PostgresConf protocolMode** - `e64756c69` (feat)

## Files Created/Modified
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/schema/AdbcSchemaFetcher.java` - NEW: ADBC-based schema discovery using getObjects() and getTableSchema()
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java` - ADBC lifecycle (start/close), protocol mode resolution, branching in metadata methods
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/BaseJdbcConf.java` - @Tag(4) protocolMode field, getProtocolMode(), adbcUri() base method
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/JdbcScanCreator.java` - Protocol branching to create AdbcRecordReader or JdbcRecordReader
- `plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresConf.java` - adbcUri() override returning postgresql://user:pass@host:port/db format

## Decisions Made
- BaseJdbcConf protocolMode at Tag(4) is the single declaration point -- PostgresConf and OracleConf inherit it without redeclaring, avoiding Protostuff serialization bugs with duplicate field names at different tag numbers
- AdbcSchemaFetcher parses the nested Arrow getObjects() result structure (catalog -> db_schemas list -> db_schema_tables list) using ListVector offset buffers and StructVector child accessors rather than getObject() deserialization
- getProtocolMode() and adbcUri() base methods added to BaseJdbcConf in Task 1 (plan had them in Task 2) because JdbcStoragePlugin.start() depends on them for compilation
- AdbcException has no simple (String, Throwable) constructor in ADBC 0.22.0 -- fallback catch blocks in AdbcSchemaFetcher use RuntimeException wrapper instead

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added getProtocolMode()/adbcUri() to BaseJdbcConf in Task 1**
- **Found during:** Task 1 (JdbcStoragePlugin compilation)
- **Issue:** JdbcStoragePlugin.start() calls conf.getProtocolMode() and conf.adbcUri(), but these methods were planned for Task 2 (BaseJdbcConf updates). Task 1 compilation failed without them.
- **Fix:** Added getProtocolMode() and adbcUri() methods to BaseJdbcConf as part of Task 1. Task 2 then upgraded getProtocolMode() to use the @Tag(4) field instead of hardcoded return.
- **Files modified:** plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/BaseJdbcConf.java
- **Verification:** Both tasks compile independently
- **Committed in:** b301cab0f (Task 1 commit)

**2. [Rule 1 - Bug] Fixed AdbcException constructor mismatch**
- **Found during:** Task 1 (AdbcSchemaFetcher compilation)
- **Issue:** Plan specified `throw new AdbcException("message", e)` but ADBC 0.22.0 AdbcException has no (String, Throwable) constructor -- requires 5+ arguments including AdbcStatusCode, sqlState, vendorCode.
- **Fix:** Changed generic catch blocks to throw RuntimeException instead of AdbcException. The specific AdbcException from ADBC API calls is still caught and re-thrown properly.
- **Files modified:** plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/schema/AdbcSchemaFetcher.java
- **Verification:** Compilation succeeds
- **Committed in:** b301cab0f (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** Task ordering adjustment and API constructor correction. No scope change.

## Issues Encountered
None beyond the deviations documented above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- ADBC execution path is fully wired: config -> plugin start (resolve mode) -> scan creator (branch on mode) -> AdbcRecordReader or JdbcRecordReader
- ADBC schema discovery (AdbcSchemaFetcher) is integrated into all metadata methods with fallback to JDBC
- Next plan (34-03) adds Docker native driver installation and integration tests
- Native ADBC driver (.so) must be installed in Docker image for runtime ADBC execution

## Self-Check: PASSED

- All 5 created/modified source files verified on disk
- Both task commits (b301cab0f, e64756c69) verified in git log

---
*Phase: 34-adbc-driver-for-at-least-postgres*
*Completed: 2026-03-14*
