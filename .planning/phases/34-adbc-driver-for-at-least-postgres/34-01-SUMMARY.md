---
phase: 34-adbc-driver-for-at-least-postgres
plan: 01
subsystem: database
tags: [adbc, arrow, jni, postgresql, native-driver, jdbc-base]

# Dependency graph
requires:
  - phase: 33-advanced-query-pushdown-hardening
    provides: SqlBuilder with bind parameter support, JdbcSubScan with BindParam list
provides:
  - ProtocolMode enum (AUTO/JDBC/ADBC) for per-source protocol selection
  - AdbcConnectionFactory with two-phase lifecycle and Semaphore-bounded concurrency
  - AdbcRecordReader with full setup/next/close ADBC query execution lifecycle
  - SqlBuilder.jdbcToPostgresPlaceholders() utility for JDBC-to-PostgreSQL placeholder translation
affects: [34-adbc-driver-for-at-least-postgres]

# Tech tracking
tech-stack:
  added: [adbc-core 0.22.0, adbc-driver-jni 0.22.0]
  patterns: [two-phase factory lifecycle, semaphore-bounded concurrency, vector-by-vector batch transfer, placeholder translation at execution boundary]

key-files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/ProtocolMode.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/pool/AdbcConnectionFactory.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/AdbcRecordReader.java
  modified:
    - plugins/jdbc-base/pom.xml
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/SqlBuilder.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestSqlBuilder.java

key-decisions:
  - "ADBC deps added with full Arrow exclusions (<artifactId>*</artifactId>) to avoid 18.3.0 vs 18.1.1-dremio version conflict"
  - "BoundedAdbcConnection wrapper delegates all AdbcConnection methods and releases Semaphore permit on close()"
  - "AdbcRecordReader copies vectors (not zero-copy) because ArrowReader uses its own allocator separate from Dremio's"
  - "DECIMAL and TIME bind params converted to string (VarCharVector) for ADBC PG driver compatibility"

patterns-established:
  - "Two-phase factory: constructor stores config, open() creates native resources -- safe for probing availability before committing"
  - "Placeholder translation at execution boundary: SqlBuilder always emits ?, translation to $N happens only in ADBC path"
  - "Vector transfer pattern: type-specific copy with DateDay->DateMilli and TimeStampMicro->TimeStampMilli conversions"

requirements-completed: [ADBC-01]

# Metrics
duration: 11min
completed: 2026-03-14
---

# Phase 34 Plan 01: ADBC Infrastructure Summary

**ADBC core infrastructure with JNI driver deps, ProtocolMode enum, bounded AdbcConnectionFactory, placeholder translation, and AdbcRecordReader for native Arrow query execution**

## Performance

- **Duration:** 11 min
- **Started:** 2026-03-14T01:55:59Z
- **Completed:** 2026-03-14T02:07:04Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Added adbc-core and adbc-driver-jni 0.22.0 Maven dependencies with Arrow exclusions to prevent version conflicts with Dremio's Arrow 18.1.1-dremio fork
- Created ProtocolMode enum (AUTO/JDBC/ADBC), AdbcConnectionFactory with two-phase lifecycle and Semaphore-bounded concurrency, and SqlBuilder.jdbcToPostgresPlaceholders() utility
- Built AdbcRecordReader with full setup/next/close lifecycle: placeholder translation, bind parameter Arrow binding, JniStatement execution, ArrowReader batch consumption with vector-by-vector data transfer to Dremio OutputMutator vectors
- Added 4 unit tests for placeholder translation (all pass)

## Task Commits

Each task was committed atomically:

1. **Task 1: Maven dependencies, ProtocolMode enum, AdbcConnectionFactory, and placeholder translation** - `e3e1b07f8` (feat)
2. **Task 2: AdbcRecordReader -- ADBC query execution with Arrow batch transfer** - `de9e9ce0e` (feat)

## Files Created/Modified
- `plugins/jdbc-base/pom.xml` - Added adbc-core and adbc-driver-jni 0.22.0 dependencies with Arrow exclusions
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/conf/ProtocolMode.java` - NEW: Protocol selection enum (AUTO/JDBC/ADBC)
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/pool/AdbcConnectionFactory.java` - NEW: Two-phase ADBC connection factory with BoundedAdbcConnection wrapper
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/SqlBuilder.java` - Added jdbcToPostgresPlaceholders() static utility method
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/AdbcRecordReader.java` - NEW: ADBC record reader with bind param Arrow binding and vector transfer
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestSqlBuilder.java` - Added 4 placeholder translation unit tests

## Decisions Made
- ADBC dependencies use `<artifactId>*</artifactId>` exclusion to block all transitive Arrow artifacts, preventing conflict between ADBC's Arrow 18.3.0 and Dremio's Arrow 18.1.1-dremio
- BoundedAdbcConnection implements full AdbcConnection interface delegation (all 25+ methods) with Semaphore permit release in close() -- ensures concurrency bound even if connection is never used
- AdbcRecordReader copies data row-by-row from ADBC ArrowReader vectors to Dremio output vectors because ArrowReader uses its own allocator (no zero-copy possible across allocator boundaries)
- DECIMAL and TIME bind parameters converted to VarCharVector strings because ADBC PG driver does not support Decimal128 or Time binding natively

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed AdbcConnection API mismatch**
- **Found during:** Task 1 (AdbcConnectionFactory)
- **Issue:** Plan specified IsolationLevel and BulkIngestMode as inner types and getStatisticNames() returning Schema; actual ADBC 0.22.0 API has them as top-level types and getStatisticNames() returns ArrowReader
- **Fix:** Decompiled ADBC 0.22.0 JAR to get exact method signatures, rewrote BoundedAdbcConnection with all 25+ delegate methods matching actual API including cancel(), readPartition(), getCurrentCatalog/DbSchema, AdbcOptions methods
- **Files modified:** plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/pool/AdbcConnectionFactory.java
- **Verification:** Compilation succeeds with no errors
- **Committed in:** e3e1b07f8 (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** API signature correction required to match actual ADBC 0.22.0 library. No scope change.

## Issues Encountered
None beyond the API mismatch documented above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- ADBC infrastructure is in place: ProtocolMode, AdbcConnectionFactory, AdbcRecordReader all compile
- Next plan (34-02) can wire these into JdbcStoragePlugin and JdbcScanCreator for protocol mode resolution
- Native ADBC driver (.so) installation in Docker image still needed (34-03)

## Self-Check: PASSED

- All 4 created/modified source files verified on disk
- Both task commits (e3e1b07f8, de9e9ce0e) verified in git log

---
*Phase: 34-adbc-driver-for-at-least-postgres*
*Completed: 2026-03-14*
