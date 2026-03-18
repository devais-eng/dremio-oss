---
phase: 41-pgvector-sql-operators-l2-distance-inner-product-cosine-distance-registered-as-dremio-sql-functions-without-clashing-existing-operators
plan: 01
subsystem: database
tags: [pgvector, arrow, dremio-functions, FunctionTemplate, ListVector, Float4, distance]

# Dependency graph
requires:
  - phase: 40-pgvector-type-support
    provides: "LIST<FLOAT4> schema discovery and ListVector write branch in JdbcRecordReader"
provides:
  - "VectorDistanceFunctions.java with three @FunctionTemplate inner classes: L2Distance, CosineDistance, InnerProduct"
  - "l2_distance(LIST<FLOAT4>, LIST<FLOAT4>) registered as Dremio SQL function returning DOUBLE"
  - "cosine_distance(LIST<FLOAT4>, LIST<FLOAT4>) registered as Dremio SQL function returning DOUBLE"
  - "inner_product(LIST<FLOAT4>, LIST<FLOAT4>) registered as Dremio SQL function returning DOUBLE"
  - "TestVectorDistanceFunctions.java with 21 unit tests covering known-vector cases and all edge cases"
affects:
  - "42-pgvector-operator-pushdown"
  - "43-pgvector-uat"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@FunctionTemplate inner static class in plugin package (classpath-scanned, no manual wiring)"
    - "NullHandling.INTERNAL + manual !left.isSet() || left.readObject() == null check for FieldReader params"
    - "UnionListReader cast from FieldReader + lReader.size() dimension check before iteration"
    - "Direct Arrow vector construction in unit tests + reflection field injection (no Dremio server required)"
    - "FunctionErrorContextBuilder.builder().build() for standalone FunctionErrorContext in tests"

key-files:
  created:
    - "plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java"
    - "plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java"
  modified: []

key-decisions:
  - "NullHandling.INTERNAL required for FieldReader params — NULL_IF_NULL does not work with complex type readers"
  - "Functions placed in com.dremio.plugins.jdbc.postgresql (already classpath-scanned) — no sabot-module.conf changes needed"
  - "cosine_distance returns NULL (isSet=0) for zero-magnitude vectors instead of NaN/Infinity"
  - "inner_product returns negative dot product (-sum) matching pgvector <#> operator convention"
  - "Tests use direct ListVector construction + reflection injection — avoids running Dremio server or Docker container for pure math unit tests"
  - "UnionListReader obtained via new UnionListReader(listVector) constructor + setPosition(0) — not via listVector.getReader() to control position independently per test"

patterns-established:
  - "Dremio SQL function in plugin package: @FunctionTemplate inner static class, @Param FieldReader, @Output NullableFloat8Holder, @Inject FunctionErrorContext, NullHandling.INTERNAL"
  - "LIST iteration pattern: cast FieldReader to UnionListReader, check size(), while(lReader.next() && rReader.next()), call lReader.reader().readFloat()"
  - "Unit test pattern for @FunctionTemplate functions: RootAllocator + ListVector (low-level buffer writes) + reflection injection + FunctionErrorContextBuilder"

requirements-completed: [PGVEC-02]

# Metrics
duration: 9min
completed: 2026-03-18
---

# Phase 41 Plan 01: pgvector SQL Operators — VectorDistanceFunctions Summary

**Three pgvector distance functions registered as Dremio SQL functions via @FunctionTemplate classpath scan: l2_distance, cosine_distance, inner_product on LIST<FLOAT4> inputs, with 21 unit tests all passing**

## Performance

- **Duration:** 9 min
- **Started:** 2026-03-18T15:19:37Z
- **Completed:** 2026-03-18T15:28:50Z
- **Tasks:** 2
- **Files modified:** 2 (created)

## Accomplishments

- `VectorDistanceFunctions.java` implements three inner static classes annotated with `@FunctionTemplate`, each implementing `SimpleFunction`, discoverable at Dremio startup via the classpath scan already registered in `sabot-module.conf`
- All three functions handle NULL inputs (returns NULL), zero-magnitude cosine vectors (returns NULL), and dimension mismatch (throws `FunctionErrorContext` user-visible error)
- `TestVectorDistanceFunctions.java` with 21 unit tests covering known-value cases, same-vector cases, null-input cases, zero-magnitude cosine case, and dimension-mismatch cases for all three functions — all pass without a running server

## Task Commits

Per project instructions, commits are deferred until all plans in the phase pass.

## Files Created/Modified

- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java` — Three `@FunctionTemplate` inner classes: L2Distance, CosineDistance, InnerProduct; each with `@Param FieldReader left/right`, `@Output NullableFloat8Holder out`, `@Inject FunctionErrorContext errCtx`, and correct `eval()` logic
- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java` — 21 JUnit 4 unit tests using direct Arrow vector construction + reflection injection (no running Dremio server)

## Decisions Made

- **NullHandling.INTERNAL**: `FieldReader` params don't support `NULL_IF_NULL` (framework can't auto-detect null for complex types). Manual `!left.isSet() || left.readObject() == null` check in each `eval()`.
- **Plugin package placement**: Functions live in `com.dremio.plugins.jdbc.postgresql` (already classpath-scanned via `sabot-module.conf`). No configuration changes needed.
- **cosine_distance zero-magnitude**: Returns `NULL` (`out.isSet = 0`) when either vector has zero magnitude, matching conventional behavior and avoiding NaN/Infinity output.
- **inner_product sign convention**: Returns negative dot product (`-sum`) matching pgvector's `<#>` operator where lower = more similar.
- **Test approach**: Direct `ListVector` construction with low-level buffer writes + reflection-based field injection into function instances. Avoids Docker/Dremio server overhead for pure math unit tests. Uses `FunctionErrorContextBuilder.builder().build()` as the `FunctionErrorContext` implementation.
- **UnionListReader construction**: Used `new UnionListReader(listVector)` constructor + `setPosition(0)` rather than `listVector.getReader()` to create an independent reader per test with controlled position state.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Phase 42 (pgvector operator pushdown): The three SQL functions are now registered and callable from Dremio SQL. Phase 42 can add Volcano planner rules that translate `l2_distance`, `cosine_distance`, `inner_product` function calls into pgvector `<->`, `<=>`, `<#>` SQL operators when the input is a PostgreSQL table with a vector column.
- No blockers.

---
*Phase: 41-pgvector-sql-operators*
*Completed: 2026-03-18*

## Self-Check: PASSED

- FOUND: plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java
- FOUND: plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java
- FOUND: .planning/phases/41-pgvector-sql-operators-l2-distance-inner-product-cosine-distance-registered-as-dremio-sql-functions-without-clashing-existing-operators/41-01-SUMMARY.md
- VERIFIED: 3 @FunctionTemplate annotations with names: l2_distance, cosine_distance, inner_product
- VERIFIED: Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
- VERIFIED: BUILD SUCCESS (compile + test)
