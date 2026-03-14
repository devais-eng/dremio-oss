---
phase: 35-join-intersect-except-single-engine-pushdown
plan: 01
subsystem: jdbc-planner
tags: [jdbc, join-pushdown, planner-rules, sql-generation]
dependency_graph:
  requires:
    - 33-01 (SqlBuilder, BindParam, RexToSqlString, RexToSqlResult)
    - 33-03 (JdbcScanPrel aggregation state, overrideRowType pattern)
    - 34-02 (JdbcStoragePlugin lifecycle, createSqlBuilder() method)
  provides:
    - JOIN pushdown planner infrastructure (JdbcJoinScanDrel, JdbcJoinScanPrel, JdbcPushJoinIntoScan)
    - Alias-aware JOIN condition converter (RexToJoinSqlString)
    - SqlBuilder.buildJoinSql() for multi-table JOIN SQL
  affects:
    - JdbcRulesFactory (LOGICAL now has 2 rules, PHYSICAL has 7 rules)
    - All integration tests that verify EXPLAIN output for JOIN queries
tech_stack:
  added:
    - JdbcJoinScanDrel (new logical leaf node)
    - JdbcJoinScanPrel (new physical leaf node)
    - RexToJoinSqlString (new alias-aware RexNode converter)
    - JdbcPushJoinIntoScan (new LOGICAL phase rule)
    - JdbcPushJoinIntoScan.JdbcJoinScanPrule (new PHYSICAL phase Drel->Prel converter)
  patterns:
    - Two-node logical/physical split: JdbcJoinScanDrel (raw components at LOGICAL) -> JdbcJoinScanPrel (SQL built at getPhysicalOperator() time)
    - Same-source guard via pluginId.getName().equals() -- avoids capabilities comparison
    - Schema/table extraction from TableMetadata.getName().getPathComponents() (identical to JdbcScanPrule)
    - Plugin-specific SqlBuilder resolved at getPhysicalOperator() time (same as JdbcScanPrel)
    - LeafPrel/AbstractRelNode pattern for nodes without single TableMetadata
key_files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcJoinScanDrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcJoinScanPrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/RexToJoinSqlString.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushJoinIntoScan.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestJdbcPushJoinIntoScan.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/SqlBuilder.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcRulesFactory.java
    - plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestJdbcRulesFactory.java
decisions:
  - JdbcJoinScanPrel extends AbstractRelNode+LeafPrel (not ScanPrelBase) -- ScanPrelBase requires single-table TableMetadata which a join scan does not have
  - needsFinalColumnReordering() returns false -- JOIN SQL SELECT list is explicitly ordered (left then right columns)
  - getEncoding() returns SelectionVectorMode.NONE -- same as all other JDBC scan nodes
  - accept(PrelVisitor) delegates to logicalVisitor.visitLeaf() -- standard pattern for leaf physical nodes
  - RexToJoinSqlString is standalone (not extending RexToSqlString) because convertInputRef() is private in the parent and cannot be overridden
  - SqlBuilder.buildJoinSql() is non-final (overridable) so OracleSqlBuilder can override AS alias syntax if Oracle integration tests fail
  - JdbcPushJoinIntoScan fires in LOGICAL phase -- at logical level JdbcScanDrel children have no WHERE/LIMIT/ORDER BY state (those are added by PHYSICAL-phase rules to JdbcScanPrel)
  - JdbcRulesFactory LOGICAL phase now returns [JdbcScanDrule, JdbcPushJoinIntoScan] (2 rules)
  - JdbcRulesFactory PHYSICAL phase now returns 7 rules including JdbcJoinScanPrule
metrics:
  duration_minutes: 15
  completed_date: "2026-03-14"
  tasks_completed: 2
  files_created: 5
  files_modified: 3
---

# Phase 35 Plan 01: JOIN Pushdown Planner Infrastructure Summary

**One-liner:** JOIN pushdown infrastructure: JdbcJoinScanDrel/Prel leaf nodes, alias-aware RexToJoinSqlString, LOGICAL-phase JdbcPushJoinIntoScan rule, and SqlBuilder.buildJoinSql() with AS-aliased table references.

## What Was Built

### Task 1: Core infrastructure (commit 8745201ff)

**`JdbcJoinScanDrel`** — Logical leaf node (AbstractRelNode + Rel) carrying raw join components: pluginId, leftSchema/leftTable, rightSchema/rightTable, joinType, onClauseSql, conditionBindParams, leftColumns, rightColumns, outputRowType. Produced at LOGICAL phase, carries no SQL — just the components needed to build it.

**`JdbcJoinScanPrel`** — Physical leaf node (AbstractRelNode + LeafPrel) with the same raw components. Implements the full Prel contract: `needsFinalColumnReordering()=false`, `getEncoding()=NONE`, `getSupportedEncodings()=DEFAULT`, `accept(PrelVisitor)` via visitLeaf(). In `getPhysicalOperator()`, resolves plugin-specific SqlBuilder from CatalogService, calls `sb.buildJoinSql()`, converts outputRowType to BatchSchema, builds merged projected column list, creates JdbcGroupScan.

**`RexToJoinSqlString`** — Standalone alias-aware converter. Constructor takes joinRowType, leftFieldCount, leftAlias ("t1"), rightAlias ("t2"). `convertInputRef(i)` uses `"t1"."field"` for `i < leftFieldCount` and `"t2"."field"` for `i >= leftFieldCount`. Handles comparisons, AND/OR/NOT, IS NULL/IS NOT NULL, and all literal types (same delegation as RexToSqlString).

**`SqlBuilder.buildJoinSql()`** — Assembles `SELECT "t1"."col", "t2"."col" FROM "schema"."table" AS "t1" {JOIN_TYPE} "schema"."table" AS "t2" ON {onClause}`. Uses quoteIdentifier() for all names. Falls back to `SELECT *` when both column lists are empty.

**`SqlBuilder.joinTypeToSql()`** — Static helper mapping JoinRelType to SQL keywords: INNER→"INNER JOIN", LEFT→"LEFT OUTER JOIN", RIGHT→"RIGHT OUTER JOIN", FULL→"FULL OUTER JOIN".

### Task 2: Rule and tests (commit 4bbfaa87d)

**`JdbcPushJoinIntoScan`** — LOGICAL-phase rule matching `JoinRel(JdbcScanDrel, JdbcScanDrel)`. The `matches()` guard checks `left.getPluginId().getName().equals(right.getPluginId().getName())`. In `onMatch()`, extracts schema/table from `TableMetadata.getName().getPathComponents()` (identical pattern to JdbcScanPrule), converts join condition via RexToJoinSqlString, falls back gracefully if condition is unsupported.

**`JdbcPushJoinIntoScan.JdbcJoinScanPrule`** (static inner class) — PHYSICAL-phase rule converting JdbcJoinScanDrel to JdbcJoinScanPrel with `Prel.PHYSICAL + DistributionTrait.SINGLETON` traits.

**`JdbcRulesFactory`** — LOGICAL phase now returns `[JdbcScanDrule, JdbcPushJoinIntoScan]`. PHYSICAL phase now returns 7 rules including `JdbcJoinScanPrule`.

**`TestJdbcPushJoinIntoScan`** — 16 unit tests covering:
- INNER/LEFT OUTER/RIGHT OUTER/FULL OUTER JOIN SQL generation (exact string assertions)
- `joinTypeToSql()` for all 4 join types
- Cross-schema join SQL
- Column quoting with spaces and double quotes
- SELECT * fallback for empty column lists
- Same-source guard (same names pass, different names fail)
- RexToJoinSqlString: left column produces t1 alias, right column (index >= leftFieldCount) produces t2 alias
- Index offset correctness (Pitfall 3 from research)
- Declination for unsupported RexNode type (CASE expression returns null)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical functionality] Added `needsFinalColumnReordering()`, `getEncoding()`, `getSupportedEncodings()`, `accept(PrelVisitor)` methods to JdbcJoinScanPrel**

- **Found during:** Task 1 compilation
- **Issue:** Prel interface declares four abstract methods that must be implemented by non-abstract classes: `needsFinalColumnReordering()`, `getEncoding()`, `getSupportedEncodings()`, `accept(PrelVisitor<T,X,E>, X)`
- **Fix:** Added all four methods with appropriate values: `needsFinalColumnReordering()=false` (explicit SELECT order), `getEncoding()=NONE`, `getSupportedEncodings()=DEFAULT`, `accept()` delegates to `visitLeaf(this, value)`
- **Files modified:** `JdbcJoinScanPrel.java`
- **Commit:** 8745201ff

**2. [Rule 2 - Missing critical functionality] Updated TestJdbcRulesFactory to reflect new rule counts**

- **Found during:** Task 2
- **Issue:** TestJdbcRulesFactory asserted LOGICAL=1 rule and PHYSICAL=6 rules; after adding JdbcPushJoinIntoScan (LOGICAL) and JdbcJoinScanPrule (PHYSICAL), counts changed to 2 and 7
- **Fix:** Updated assertions: `logicalPhaseReturnsTwoRules()` and `physicalPhaseReturnsSevenRules()`
- **Files modified:** `TestJdbcRulesFactory.java`
- **Commit:** 4bbfaa87d

**3. [Rule 2 - Missing critical functionality] Used SelectionVectorMode from BatchSchema inner class (not Prel package)**

- **Found during:** Task 1 compilation
- **Issue:** `SelectionVectorMode` is an inner class of `BatchSchema` (`com.dremio.exec.record.BatchSchema.SelectionVectorMode`), not in the `com.dremio.exec.planner.physical` package
- **Fix:** Changed import to `com.dremio.exec.record.BatchSchema.SelectionVectorMode`
- **Files modified:** `JdbcJoinScanPrel.java`
- **Commit:** 8745201ff

## Self-Check: PASSED

### Created files exist:
- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcJoinScanDrel.java` — FOUND
- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcJoinScanPrel.java` — FOUND
- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/RexToJoinSqlString.java` — FOUND
- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushJoinIntoScan.java` — FOUND
- `/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-base/src/test/java/com/dremio/plugins/jdbc/planning/TestJdbcPushJoinIntoScan.java` — FOUND

### Commits exist:
- `8745201ff` — feat(35-01): add JdbcJoinScanDrel, JdbcJoinScanPrel, RexToJoinSqlString, and SqlBuilder.buildJoinSql() — FOUND
- `4bbfaa87d` — feat(35-01): add JdbcPushJoinIntoScan rule, register in JdbcRulesFactory, add unit tests — FOUND

### Verification commands passed:
- `mvn compile -pl plugins/jdbc-base` — BUILD SUCCESS
- `mvn test -pl plugins/jdbc-base -Dtest=TestJdbcPushJoinIntoScan` — 16 tests, 0 failures
