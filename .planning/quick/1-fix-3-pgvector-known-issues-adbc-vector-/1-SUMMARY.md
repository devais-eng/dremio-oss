---
phase: quick-fix-pgvector
plan: 01
subsystem: database
tags: [pgvector, arrow, distance-functions, knn-pushdown, decimal, float8]

requires:
  - phase: 41-pgvector-sql-operators
    provides: VectorDistanceFunctions with FLOAT4-only eval loops
  - phase: 42-pgvector-operator-pushdown
    provides: PgvectorKnnPushdownRule with positional column mapping

provides:
  - readAsDouble() helper dispatching on Arrow MinorType for FLOAT4/FLOAT8/DECIMAL/INT/BIGINT
  - Correct DECIMAL and FLOAT8 list element support in all three distance functions
  - Name-based column mapping in KNN pushdown wrapper project

affects: [pgvector, jdbc-postgresql, jdbc-base, distance-functions, knn-pushdown]

tech-stack:
  added: []
  patterns:
    - "readAsDouble() MinorType dispatch: replace unconditional readFloat() with switch-on-MinorType helper shared across all distance function eval() loops"
    - "Name-based field mapping: use equalsIgnoreCase() field name lookup instead of positional index for wrapper project construction"

key-files:
  created: []
  modified:
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java
    - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java

key-decisions:
  - "readAsDouble() placed as package-private static in VectorDistanceFunctions outer class — accessible from all three inner function classes without class hierarchy changes"
  - "DECIMAL case uses readObject() cast to BigDecimal then .doubleValue() — Arrow FieldReader has no readDecimal() method"
  - "KNN wrapper project uses equalsIgnoreCase() for field name matching — defensive against mixed-case column names from JDBC metadata"
  - "ADBC vector NULL issue deferred — requires binary protocol parsing, significant scope outside this quick fix"

patterns-established:
  - "MinorType dispatch pattern: always check reader.getMinorType() before reading numeric values from list element readers to support heterogeneous element types"
  - "Name-based column mapping: prefer getName().equalsIgnoreCase() over positional index for any wrapper project that bridges two differently-shaped row types"

requirements-completed: []

duration: 3min
completed: 2026-03-19
---

# Quick Fix 1: pgvector DECIMAL/FLOAT8 element support + KNN name-based column mapping

**Fixed two pgvector runtime bugs: DECIMAL/FLOAT8 list elements now work in all distance functions via MinorType dispatch, and KNN pushdown wrapper project uses name-based column mapping to prevent NULL corruption on non-trivial scan orderings.**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-19T11:10:52Z
- **Completed:** 2026-03-19T11:14:03Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Added `readAsDouble(FieldReader)` helper to `VectorDistanceFunctions` that dispatches on `MinorType` — FLOAT4, FLOAT8, DECIMAL, INT, BIGINT all handled without exception
- Replaced all bare `readFloat()` calls in the three `eval()` loops (L2Distance, CosineDistance, InnerProduct) with `readAsDouble()` — 3 call sites fixed
- Added `makeListVectorDecimal` and `makeListVectorFloat8` test helpers plus 2 new tests — `testL2Distance_decimalElements` and `testL2Distance_float8Elements` — all 26 tests pass
- Replaced positional index loop in `PgvectorKnnPushdownRule.onMatch()` with name-based `equalsIgnoreCase()` lookup — prevents silent NULL corruption when scan column order differs from TopN output order

## Task Commits

All tasks committed together per project convention (no per-task commits during phase execution):

1. **Task 1: readAsDouble() helper + DECIMAL/FLOAT8 eval loop fix** — VectorDistanceFunctions.java + test
2. **Task 2: KNN pushdown name-based column mapping** — PgvectorKnnPushdownRule.java

**Plan metadata:** (docs commit — see final commit hash)

## Files Created/Modified

- `plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java` — Added `readAsDouble()` static helper; replaced 3 `readFloat()` eval loop call sites
- `plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java` — Added `DecimalVector`/`Float8Vector` imports, `makeListVectorDecimal()`, `makeListVectorFloat8()` helpers, 2 new test methods
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java` — Replaced positional column mapping loop with `equalsIgnoreCase()` name-based lookup

## Decisions Made

- `readAsDouble()` uses `readObject()` + cast to `BigDecimal` for DECIMAL case — Arrow 18.1.1-dremio's `FieldReader` has no `readDecimal()` method
- `equalsIgnoreCase()` chosen over exact match for robustness against JDBC metadata returning mixed-case column names
- ADBC vector NULL issue explicitly deferred (requires binary protocol parsing — significant scope)

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `ARRAY[0.1, 0.9]` decimal literals in distance function calls no longer throw at eval() time
- Non-trivial scan column orderings (e.g., `[id, name, category_id, price, embedding]`) correctly preserved through KNN pushdown wrapper project
- ADBC vector NULL issue remains open for future work (requires binary protocol parsing)

## Self-Check

- [x] `VectorDistanceFunctions.java` exists and contains `readAsDouble`
- [x] `TestVectorDistanceFunctions.java` exists and contains `DECIMAL`
- [x] `PgvectorKnnPushdownRule.java` exists and contains `equalsIgnoreCase`
- [x] 26 tests pass (`TestVectorDistanceFunctions` — 24 existing + 2 new)
- [x] `plugins/jdbc-base` compiles cleanly
- [x] No bare `readFloat()` in eval() loop bodies (only in `readAsDouble()` helper)

## Self-Check: PASSED

---
*Phase: quick-fix-pgvector*
*Completed: 2026-03-19*
