---
phase: 44-pgvector-gap-closure-knn-distance-projection-pushdown-adbc-vector-binary-format-parsing
plan: 01
subsystem: database
tags: [pgvector, knn, adbc, arrow, listvector, pushdown, projection]

# Dependency graph
requires:
  - phase: 42-pgvector-operator-pushdown
    provides: PgvectorKnnPushdownRule for ORDER BY + LIMIT pushdown of distance functions
  - phase: 34-adbc-driver-for-at-least-postgres
    provides: AdbcRecordReader with vector transfer infrastructure
provides:
  - KNN distance projection returns actual computed distance values instead of NULL
  - ADBC path transfers pgvector vector(N) columns as float arrays (ListVector<Float4>)
  - VarBinaryVector-to-ListVector fallback for pgvector binary wire format parsing
affects: [pgvector, adbc, knn-queries, embedding-search]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ListVector element-level copy using offset buffer arithmetic for ADBC Arrow transfers"
    - "pgvector binary wire format parsing: 2B dim (uint16 BE) + 2B flags + N*4B float32 (BE)"

key-files:
  created: []
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/AdbcRecordReader.java

key-decisions:
  - "distanceCall RexNode emitted directly in wrapper ProjectPrel (already in scope from onMatch) instead of recomputing"
  - "ListVector transfer uses element-level Float4Vector copy (not zero-copy) consistent with AdbcRecordReader allocator separation"
  - "VarBinaryVector->ListVector fallback added defensively for ADBC PG driver versions that may return raw binary instead of native Arrow"

patterns-established:
  - "ListVector offset buffer: literal 4 (INT32 width) for getInt/setInt calls"
  - "pgvector binary wire format: 2-byte dimension count, 2-byte unused flags, N*4-byte big-endian float32"

requirements-completed: [PGVEC-GAP-01, PGVEC-GAP-02]

# Metrics
duration: 19min
completed: 2026-03-19
---

# Phase 44 Plan 01: pgvector Gap Closure Summary

**KNN distance projection emits computed distanceCall RexNode + ADBC ListVector/VarBinary pgvector transfer support**

## Performance

- **Duration:** 19 min
- **Started:** 2026-03-19T22:05:52Z
- **Completed:** 2026-03-19T22:25:09Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- PgvectorKnnPushdownRule now emits the actual `distanceCall` RexNode for the EXPR$1 distance column instead of a null literal, enabling users to SELECT distance values in KNN queries
- AdbcRecordReader.transferVector() handles ListVector->ListVector (native Arrow) and VarBinaryVector->ListVector (pgvector binary wire format) transfers for pgvector embedding columns via ADBC
- All 204 jdbc-base unit+integration tests pass with 0 failures
- Docker UAT: 30/30 PG+ADBC tests pass (Sections 1-8), including all 9 pgvector pushdown tests (Section 8)

## Task Commits

Both tasks committed together (per user constraint: no individual task commits):

1. **Task 1: Fix KNN distance projection** + **Task 2: Add ListVector transfer** - `7652fad9e` (fix)

## Files Created/Modified
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java` - Emit distanceCall instead of makeNullLiteral for unmatched EXPR$1 distance column in wrapper ProjectPrel
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/AdbcRecordReader.java` - Add ListVector->ListVector and VarBinaryVector->ListVector transfer branches + trace-level diagnostic logging

## Decisions Made
- `distanceCall` variable was already in scope at the insertion point (built at lines 147-150 of onMatch), so no additional computation or variable construction needed -- single-line replacement
- ListVector element-level copy pattern (not zero-copy) consistent with AdbcRecordReader's design: ADBC ArrowReader uses a separate allocator from Dremio's, requiring data copy
- VarBinaryVector->ListVector fallback added defensively: current ADBC PG driver (0.22.0) materializes vector(N) as native Arrow ListVector, but future versions could return raw binary wire bytes

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Docker UAT Oracle tests (11 failures) are pre-existing: the regression test script (`test-regression.sh`) does not seed Oracle EMPLOYEES/DEPARTMENTS tables; it only seeds PG tables. Oracle tables are assumed to exist from prior UAT runs. This is not related to our code changes.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- pgvector KNN distance projection and ADBC vector transfer gaps are closed
- All pgvector functionality (type support, operators, pushdown, KNN, ADBC) is complete
- Ready for production use of pgvector semantic search through Dremio

---
*Phase: 44-pgvector-gap-closure-knn-distance-projection-pushdown-adbc-vector-binary-format-parsing*
*Completed: 2026-03-19*
