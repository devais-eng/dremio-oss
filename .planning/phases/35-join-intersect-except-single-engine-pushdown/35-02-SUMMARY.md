---
phase: 35-join-intersect-except-single-engine-pushdown
plan: 02
subsystem: jdbc-adbc
tags: [adbc, literal-inliner, copy-binary, sql-injection, bind-params]
dependency_graph:
  requires: [35-01]
  provides: [LiteralInliner, AdbcRecordReader-COPY-binary-path]
  affects: [AdbcRecordReader]
tech_stack:
  added: []
  patterns: [literal-inlining, single-quote-doubling, simple-query-protocol]
key_files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/LiteralInliner.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestLiteralInliner.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/AdbcRecordReader.java
    - .mvn/maven.config
decisions:
  - "LiteralInliner uses single-quote doubling only (no backslash escaping) — PostgreSQL standard_conforming_strings=on since 9.1"
  - "AdbcRecordReader.setup() does NOT call stmt.bind() when params present — COPY binary requires simple query protocol"
  - "buildBindRoot/setBindValue/sqlTypeToArrowType methods retained in AdbcRecordReader for future Extended Query Protocol path"
  - "Maven 3.9.9 rejects # comment lines in .mvn/maven.config — removed comments, kept only -Drevision= flag"
metrics:
  duration: 5min
  completed_date: "2026-03-14"
  tasks: 2
  files: 4
---

# Phase 35 Plan 02: LiteralInliner for ADBC COPY Binary Protocol Summary

**One-liner:** LiteralInliner inlines all SQL bind parameter types as properly-escaped SQL literals into AdbcRecordReader, bypassing stmt.bind() and enabling the PostgreSQL ADBC driver's fast COPY binary path.

## What Was Built

### LiteralInliner utility (`LiteralInliner.java`)

A utility class in `com.dremio.plugins.jdbc.planning` that converts SQL-with-`?`-placeholders to SQL-with-inlined-literals. Two methods:

- `inlineBindParams(String sql, List<BindParam> params)` — iterates SQL character by character, replacing each `?` with the corresponding `toSqlLiteral()` result.
- `toSqlLiteral(BindParam param)` — dispatches by `SqlTypeName`:
  - `NULL` value → `NULL` keyword
  - TINYINT/SMALLINT/INTEGER/BIGINT → raw `value.toString()`
  - FLOAT/REAL/DOUBLE/DECIMAL → raw `value.toString()`
  - VARCHAR/CHAR → `'escaped'` (single-quote doubling only, no backslash escaping)
  - BOOLEAN → `TRUE` / `FALSE`
  - DATE → `DATE 'YYYY-MM-DD'` (from epoch millis via `java.sql.Date`)
  - TIME → `TIME 'HH:mm:ss'` (from epoch millis via `java.sql.Time`)
  - TIMESTAMP → `TIMESTAMP 'YYYY-MM-DD HH:mm:ss...'` (from epoch millis via `java.sql.Timestamp`)
  - default → safe `'escaped'` fallback

### TestLiteralInliner (`TestLiteralInliner.java`)

34 unit tests covering:
- Type formatting: all 11 SQL types + NULL
- String escaping: single-quote doubling, multiple quotes, backslash pass-through, empty string
- SQL injection safety: single-quote breakout, semicolons, SQL keywords, UNION SELECT, backslash-quote combination, nested quotes
- Full inlining: no params, null params, empty params, single param, multiple params, mixed types

### AdbcRecordReader.setup() modification

Replaced the `jdbcToPostgresPlaceholders() + stmt.bind()` path with `LiteralInliner.inlineBindParams()`. When bind params are present, the SQL is now fully inlined before calling `stmt.setSqlQuery()`, and `stmt.bind()` is never called. This routes execution through the PostgreSQL simple query protocol, enabling COPY binary format for faster data transfer.

## Decisions Made

1. **Single-quote doubling only** — PostgreSQL `standard_conforming_strings=on` (default since 9.1) means backslash has no special meaning in standard `'...'` string literals. Only `E'...'` strings treat backslash as an escape. No backslash escaping needed or wanted.

2. **stmt.bind() removed** — The ADBC PG driver uses the Extended Query Protocol (parse/bind/execute) when `stmt.bind()` is called, which cannot use COPY binary. By omitting `stmt.bind()`, the simple query protocol is used instead.

3. **buildBindRoot() retained** — The bind-root/bind-value/arrow-type methods are kept as dead code for a potential future Extended Query Protocol path. Removing them would foreclose that option.

4. **maven.config comment strip** — Maven 3.9.9 treats each line in `.mvn/maven.config` as a CLI argument. Comment lines starting with `#` are parsed as argument tokens and cause a `ParseException`. Removed the comments and kept only the `-Drevision=` flag.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed wrong test expectation in testSqlInjectionBackslashQuote**
- **Found during:** Task 1 test run
- **Issue:** The test expected `"'\\'''; DROP TABLE--'"` (4 single quotes around `\`) but the actual correct output for input `\'; DROP TABLE--` is `'\''; DROP TABLE--` (backslash literal + doubled quote). The Java string escaping in the test expectation was incorrect.
- **Fix:** Updated expected value to `"'\\''; DROP TABLE--'"` matching the actual correct SQL output
- **Files modified:** `TestLiteralInliner.java`
- **Commit:** 784481b47

**2. [Rule 2 - Missing] Removed unused SqlBuilder import from AdbcRecordReader**
- **Found during:** Task 2
- **Issue:** After removing `SqlBuilder.jdbcToPostgresPlaceholders()` call, the `SqlBuilder` import became unused. While not a compiler error (just a warning), it's misleading.
- **Fix:** Removed the unused import
- **Files modified:** `AdbcRecordReader.java`

## Self-Check: PASSED

Files verified:
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/LiteralInliner.java` — FOUND
- `plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestLiteralInliner.java` — FOUND
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/reader/AdbcRecordReader.java` — FOUND (modified)

Commits verified:
- `784481b47` — feat(35-02): add LiteralInliner utility
- `408a4dd8f` — feat(35-02): wire LiteralInliner into AdbcRecordReader
