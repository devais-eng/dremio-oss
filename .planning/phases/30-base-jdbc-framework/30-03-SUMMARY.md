---
phase: 30-base-jdbc-framework
plan: 03
subsystem: database
tags: [jdbc, calcite, planner, pushdown, filter, projection, limit, rules-factory, dremio-plugin]

# Dependency graph
requires:
  - dremio-plugin-jdbc-base module (plan 01)
  - JdbcConnectionPool, BaseJdbcConf (plan 01)
  - JdbcGroupScan, JdbcSubScan, JdbcScanCreator (plan 02)
provides:
  - JdbcScanDrel — JDBC-specific logical scan node (ScanCrel -> JdbcScanDrel via JdbcScanDrule)
  - JdbcScanPrel — physical scan node carrying pushed-down WHERE, projection, and LIMIT state
  - JdbcScanDrule — SourceLogicalConverter registering in the LOGICAL planner phase
  - JdbcScanPrule — RelOptRule converting JdbcScanDrel to JdbcScanPrel in PHYSICAL phase
  - JdbcPushFilterIntoScan — WHERE clause pushdown with RexToSqlString converter
  - JdbcPushProjectIntoScan — SELECT list narrowing from ProjectPrel RexInputRef projections
  - JdbcPushLimitIntoScan — LIMIT N pushdown from LimitPrel fetch count
  - JdbcRulesFactory — StoragePluginTypeRulesFactory registering all rules in correct phases
  - SqlBuilder — parameterized SQL assembler with double-quoted identifier safety
affects: [30-04, 30-05, concrete JDBC connectors]

# Tech tracking
tech-stack:
  added:
    - "Calcite RexNode visitor pattern for RexToSqlString WHERE clause conversion"
  patterns:
    - "SourceLogicalConverter subclass (JdbcScanDrule) receives SourceType at construction — no singleton because base conf has no @SourceType"
    - "JdbcScanPrule as companion Prule (JdbcScanDrel -> JdbcScanPrel) registered in PHYSICAL alongside pushdown rules"
    - "cloneWithFilter/cloneWithProject/cloneWithLimit immutable-clone pattern on JdbcScanPrel"
    - "RexToSqlString declines unsupported expressions by returning null — rule bails out cleanly"

key-files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/SqlBuilder.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanDrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanDrule.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrule.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushFilterIntoScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushProjectIntoScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushLimitIntoScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java — getRulesFactoryClass() now returns JdbcRulesFactory.class

key-decisions:
  - "JdbcScanDrule is not a singleton — it takes SourceType at construction so JdbcRulesFactory creates new JdbcScanDrule(pluginType) per getRules() call, matching ElasticScanRule pattern"
  - "JdbcScanPrule added as companion to handle Drel->Prel conversion in PHYSICAL phase — plan omitted it but it is required for the full pipeline to function"
  - "RexToSqlString returns null for any unsupported RexNode type — JdbcPushFilterIntoScan bails out cleanly rather than producing incorrect SQL"
  - "LimitPrel offset=0 guard added in JdbcPushLimitIntoScan.matches() — non-zero offsets cannot be expressed as LIMIT alone"
  - "SqlBuilder.quoteIdentifier() escapes internal double-quotes per SQL standard ('' -> '')"

patterns-established:
  - "Pattern: Pushdown rule clone-with-xxx immutable pattern — each clone preserves unmodified fields"
  - "Pattern: JdbcRulesFactory LOGICAL=scan conversion, PHYSICAL=conversion+pushdowns (mirrors ElasticRulesFactory)"
  - "Pattern: Filter pushdown declines gracefully on unsupported operators instead of throwing"

requirements-completed:
  - BASE-05
  - BASE-06
  - BASE-07

# Metrics
duration: 10min
completed: 2026-03-12
---

# Phase 30 Plan 03: Planning Layer — Pushdown Rules and SQL Assembly Summary

**Calcite planner rules pushing WHERE, SELECT-list projection, and LIMIT into JDBC scans via immutable JdbcScanPrel clones assembled into SQL by SqlBuilder**

## Performance

- **Duration:** 10 min
- **Started:** 2026-03-12T21:45:37Z
- **Completed:** 2026-03-12T21:55:00Z
- **Tasks:** 2
- **Files modified:** 10 (9 created, 1 updated)

## Accomplishments

- `SqlBuilder.buildSql()` assembles `SELECT "col1", "col2" FROM "schema"."table" WHERE ... LIMIT N` with double-quoted identifier escaping for SQL-standard safety
- `JdbcScanDrel` provides the JDBC-specific logical representation of a scan; `JdbcScanDrule` (a `SourceLogicalConverter`) converts `ScanCrel` → `JdbcScanDrel` during LOGICAL planning
- `JdbcScanPrel` carries the full pushdown state (projected columns, WHERE clause, LIMIT); `JdbcScanPrule` converts `JdbcScanDrel` → `JdbcScanPrel` during PHYSICAL planning
- `JdbcPushFilterIntoScan` converts `FilterPrel` → `JdbcScanPrel.cloneWithFilter()` using `RexToSqlString`, which handles 11 operators (=, <>, <, <=, >, >=, LIKE, AND, OR, NOT, IS NULL/NOT NULL) and declines gracefully on unsupported ones
- `JdbcPushProjectIntoScan` narrows the SELECT list by mapping `RexInputRef` indices to column names; non-trivial expressions abort the pushdown
- `JdbcPushLimitIntoScan` pushes `LimitPrel.getFetch()` into `JdbcScanPrel.cloneWithLimit()`; takes the minimum if a limit already exists; guards against non-zero offsets
- `JdbcRulesFactory` registers LOGICAL rules (JdbcScanDrule) and PHYSICAL rules (JdbcScanPrule + 3 pushdowns); `JdbcStoragePlugin.getRulesFactoryClass()` now returns `JdbcRulesFactory.class`
- `JdbcScanPrel.getPhysicalOperator()` calls `SqlBuilder.buildSql()` then creates `JdbcGroupScan` — the planning-to-execution bridge is complete

## Task Commits

Each task was committed atomically:

1. **Task 1: Implement JdbcScanDrel, JdbcScanPrel, JdbcScanDrule, and SqlBuilder** - `0418d73a8` (feat)
2. **Task 2: Implement pushdown rules and JdbcRulesFactory** - `2cf19ffa5` (feat)

**Plan metadata:** `(pending)` (docs: complete plan)

## Files Created/Modified

- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/SqlBuilder.java` — created; parameterized SQL assembler with double-quoted identifier safety
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanDrel.java` — created; logical scan node extending ScanRelBase + Rel.LOGICAL
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java` — created; physical scan node with pushdown state and clone helpers
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanDrule.java` — created; SourceLogicalConverter for LOGICAL phase
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrule.java` — created; Drel-to-Prel companion rule for PHYSICAL phase
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushFilterIntoScan.java` — created; WHERE pushdown with RexToSqlString inner class
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushProjectIntoScan.java` — created; SELECT list narrowing from ProjectPrel
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushLimitIntoScan.java` — created; LIMIT N pushdown from LimitPrel
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java` — created; StoragePluginTypeRulesFactory
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java` — updated; getRulesFactoryClass() returns JdbcRulesFactory.class

## Decisions Made

- **JdbcScanDrule is not a singleton:** `BaseJdbcConf` carries no `@SourceType` annotation (plan 01 decision). `JdbcScanDrule` extends `SourceLogicalConverter`, which requires a `SourceType` at construction. `JdbcRulesFactory` creates `new JdbcScanDrule(pluginType)` per `getRules()` call — matching the `ElasticScanRule` pattern exactly.
- **JdbcScanPrule added (deviation Rule 2):** The plan listed only three pushdown rules for the PHYSICAL phase, but `JdbcScanDrel` → `JdbcScanPrel` conversion also happens in PHYSICAL. Without `JdbcScanPrule`, the pipeline would stall at the logical-to-physical boundary. Added and registered in PHYSICAL alongside the pushdown rules.
- **RexToSqlString null-bail pattern:** Any unsupported `RexNode` type returns `null`, which propagates up the visitor and causes `JdbcPushFilterIntoScan.onMatch()` to return without calling `call.transformTo()`, leaving the `FilterPrel` in place for in-engine evaluation.
- **LimitPrel offset guard:** `JdbcPushLimitIntoScan.matches()` rejects offsets ≠ 0 because `LIMIT N` alone cannot represent `OFFSET M LIMIT N`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added JdbcScanPrule for Drel-to-Prel conversion**
- **Found during:** Task 2, JdbcRulesFactory implementation
- **Issue:** Plan specified only three pushdown rules for PHYSICAL phase. But without a rule to convert `JdbcScanDrel` (logical) to `JdbcScanPrel` (physical), the planning pipeline would stall — pushdown rules operate on `JdbcScanPrel`, which would never exist.
- **Fix:** Implemented `JdbcScanPrule` (matches `JdbcScanDrel.class`, produces `JdbcScanPrel`) and registered it in the PHYSICAL rule set alongside the three pushdown rules.
- **Files modified:** `JdbcScanPrule.java` (created), `JdbcRulesFactory.java` (PHYSICAL set includes `JdbcScanPrule.INSTANCE`)
- **Commit:** `0418d73a8` / `2cf19ffa5`

---

**Total deviations:** 1 auto-fixed (missing critical functionality)
**Impact on plan:** The fix is essential — the pipeline cannot function without Drel→Prel conversion. No scope creep.

## Issues Encountered

None beyond the JdbcScanPrule gap documented above.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Complete planning layer: `ScanCrel → JdbcScanDrel → JdbcScanPrel → JdbcGroupScan → JdbcSubScan → JdbcRecordReader`
- Concrete JDBC connectors (e.g. PostgreSQL) only need to subclass `BaseJdbcConf` with `@SourceType` and pass the `SourceType` to `JdbcRulesFactory` via `JdbcScanDrule`
- `SqlBuilder` is designed to be subclassable for dialect overrides (e.g. Oracle `FETCH FIRST n ROWS ONLY`)
- Phase 30 planning layer is complete — next phases can build on this foundation for concrete connector implementations

## Self-Check: PASSED

---
*Phase: 30-base-jdbc-framework*
*Completed: 2026-03-12*
