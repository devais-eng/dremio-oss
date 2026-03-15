---
phase: 36-calcite-jdbc-convention-migration-for-sql-generation
plan: "01"
subsystem: jdbc-planning
tags:
  - calcite
  - jdbc-convention
  - sql-generation
  - pushdown
  - dialect
dependency_graph:
  requires:
    - 35-join-intersect-except-single-engine-pushdown
  provides:
    - JdbcCalciteLeaf (JdbcRel leaf node for FROM clause)
    - DremioJdbcImplementor (custom JdbcImplementor subclass for dispatch)
    - JdbcStoragePlugin.createDialect() (SqlDialect factory method)
    - JdbcScanPrel Calcite object fields (filterRex, collation, groupSet, aggCalls)
    - JdbcScanPrel.getPhysicalOperator() via JdbcImplementor rendering
  affects:
    - plugins/jdbc-base
    - plugins/jdbc-oracle
    - plugins/jdbc-postgresql
tech_stack:
  added:
    - JdbcConvention (org.apache.calcite.adapter.jdbc) -- JDBC convention trait
    - JdbcRules.JdbcFilter/JdbcProject/JdbcAggregate/JdbcSort (calcite-core 1.22.0) -- subtree nodes
    - PostgresqlSqlDialect/OracleSqlDialect (calcite-core) -- dialect-specific SQL rendering
  patterns:
    - JdbcRel subtree construction in getPhysicalOperator()
    - DremioJdbcImplementor reflection dispatch for custom leaf node
    - Inline literal rendering (no bind params for single-table pipeline)
key_files:
  created:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcCalciteLeaf.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/DremioJdbcImplementor.java
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/JdbcStoragePlugin.java
    - plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/OracleConf.java
    - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/PostgresConf.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcScanPrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushFilterIntoScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushSortIntoScan.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushSortIntoScanHep.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushTopNIntoScanHep.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushAggIntoScan.java
decisions:
  - "JdbcCalciteLeaf extends AbstractRelNode (not TableScan) -- TableScan requires RelOptTable; AbstractRelNode is cleaner for our use case where we have direct schema/table name strings"
  - "DremioJdbcImplementor subclass needed -- stock JdbcImplementor only handles JdbcTableScan; our JdbcCalciteLeaf needs explicit visit() dispatch via reflection"
  - "Inline literals in WHERE clause (no bind params) -- JdbcImplementor renders literals inline via AST, eliminating RexToSqlString and BindParam from the single-table pipeline"
  - "JdbcScanPrel.bindParams removed from single-table pipeline -- JdbcGroupScan receives Collections.emptyList(); execution layer (JdbcRecordReader, AdbcRecordReader) unchanged"
  - "collationToSql() static method removed from JdbcPushSortIntoScan -- JdbcRules.JdbcSort.implement() handles ORDER BY rendering automatically including NULLS FIRST/LAST"
  - "JdbcJoinScanPrel still uses SqlBuilder -- Plan 36-02 will migrate join SQL generation; kept as-is to limit scope"
metrics:
  duration: 47 min
  completed: "2026-03-15T17:39:00Z"
  tasks_completed: 2
  tasks_total: 2
  files_created: 2
  files_modified: 9
---

# Phase 36 Plan 01: JdbcCalciteLeaf, createDialect(), and JdbcScanPrel Calcite Migration Summary

Replace `SqlBuilder.buildSql()` in `JdbcScanPrel.getPhysicalOperator()` with a proper Calcite JdbcRel subtree rendered by `DremioJdbcImplementor` + `SqlDialect`, storing Calcite objects (`RexNode`, `RelCollation`, `ImmutableBitSet`, `AggregateCall`) on `JdbcScanPrel` instead of SQL strings.

## What Was Built

### Task 1: JdbcCalciteLeaf, DremioJdbcImplementor, createDialect()

**JdbcCalciteLeaf** (`plugins/jdbc-base/.../planning/JdbcCalciteLeaf.java`): A custom `JdbcRel` leaf node extending `AbstractRelNode`. Implements `implement(JdbcImplementor)` to produce the `FROM "schema"."table"` clause via `SqlIdentifier`. Used as the base leaf in the single-table JdbcRel subtree. Avoids `TableScan` because that requires a non-null `RelOptTable` with full Calcite schema wiring.

**DremioJdbcImplementor** (`plugins/jdbc-base/.../planning/DremioJdbcImplementor.java`): Subclass of `JdbcImplementor` that adds `visit(JdbcCalciteLeaf)`. The stock `JdbcImplementor` dispatches via reflection to `visit(ConcreteType)` methods -- it only handles `JdbcTableScan` natively. By adding `visit(JdbcCalciteLeaf)` to our subclass, the reflection chain correctly routes to `leaf.implement(this)` for FROM clause generation.

**createDialect()** added to:
- `JdbcStoragePlugin.createDialect()` -- returns `PostgresqlSqlDialect.DEFAULT` (base default)
- `OracleConf` anonymous subclass -- overrides to `OracleSqlDialect.DEFAULT`
- `PostgresConf` anonymous subclass -- overrides to `PostgresqlSqlDialect.DEFAULT`

### Task 2: JdbcScanPrel Field Migration + Pushdown Rule Updates

**JdbcScanPrel** completely rewritten:
- Old string fields (`whereClause`, `bindParams`, `orderByClause`, `selectExprs`, `groupByClause`) **removed**
- New Calcite object fields added: `RexNode filterRex`, `RelCollation collation`, `ImmutableBitSet groupSet`, `List<AggregateCall> aggCalls`
- New clone methods: `cloneWithFilter(RexNode)`, `cloneWithCollation(RelCollation)`, `cloneWithAggregation(ImmutableBitSet, List<AggregateCall>, RelDataType)`
- Old string clone methods removed: `cloneWithFilter(String)`, `cloneWithFilter(String, List<BindParam>)`, `cloneWithOrderBy(String)`, `cloneWithAggregation(List<String>, String, RelDataType)`
- `getPhysicalOperator()` rewritten to build JdbcRel subtree: `JdbcCalciteLeaf -> [JdbcFilter] -> [JdbcProject] -> [JdbcAggregate] -> [JdbcSort]`, rendered by `DremioJdbcImplementor.implement()`. `JdbcGroupScan` receives `Collections.emptyList()` for bindParams.
- `hasAggregation()` now checks `groupSet != null` (was `selectExprs != null`)
- `hasFilter()` now checks `filterRex != null` (was `whereClause != null`)

**Pushdown rule updates:**
- `JdbcPushFilterIntoScan`: stores `filter.getCondition()` (RexNode) instead of converting via `RexToSqlString`
- `JdbcPushSortIntoScan`: stores `sort.getCollation()` (RelCollation) instead of `collationToSql()` string; `collationToSql()` static method removed
- `JdbcPushSortIntoScanHep`: uses `scan.cloneWithCollation(sort.getCollation())` instead of `scan.cloneWithOrderBy(orderByExpr)`
- `JdbcPushTopNIntoScanHep`: uses `scan.cloneWithCollation(topN.getCollation())` instead of `scan.cloneWithOrderBy(orderByExpr)`
- `JdbcPushAggIntoScan`: stores `agg.getGroupSet()` + `agg.getAggCallList()` directly; all manual SQL string building removed (`aggCallToSql()`, `singleArgAgg()`, `quoteField()`, `quoteIdentifier()` methods removed)

## Verification Results

```
mvn compile -pl plugins/jdbc-base,plugins/jdbc-oracle,plugins/jdbc-postgresql -DskipTests -am -T4
→ BUILD SUCCESS

mvn test -pl plugins/jdbc-base -Dtest="TestSqlBuilder,TestRexToSqlString,TestLiteralInliner" -DfailIfNoTests=false
→ Tests run: 81, Failures: 0, Errors: 0, Skipped: 0
→ BUILD SUCCESS
```

All three JDBC modules compile cleanly. All 81 legacy unit tests pass (these test `SqlBuilder`, `RexToSqlString`, and `LiteralInliner` directly -- the legacy classes are kept in place and tested but no longer called from the single-table pipeline).

## Deviations from Plan

None -- plan executed exactly as written.

The plan specified both Tasks 1 and 2 to be implemented, and both were completed as described. The `DremioJdbcImplementor` class (mentioned in the Task 1 action section) was created as a new file alongside `JdbcCalciteLeaf.java`.

## Self-Check

```
[ -f plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcCalciteLeaf.java ]  → FOUND
[ -f plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/DremioJdbcImplementor.java ] → FOUND
grep "createDialect" JdbcStoragePlugin.java → FOUND (1 occurrence)
grep "OracleSqlDialect.DEFAULT" OracleConf.java → FOUND
grep "PostgresqlSqlDialect.DEFAULT" PostgresConf.java → FOUND
grep "DremioJdbcImplementor" JdbcScanPrel.java → FOUND
grep "filterRex" JdbcScanPrel.java → FOUND
grep "collation" JdbcScanPrel.java → FOUND
grep "groupSet" JdbcScanPrel.java → FOUND
grep "cloneWithFilter(filter.getCondition())" JdbcPushFilterIntoScan.java → FOUND
grep "cloneWithCollation" JdbcPushSortIntoScanHep.java → FOUND
grep "cloneWithCollation" JdbcPushTopNIntoScanHep.java → FOUND
grep "cloneWithAggregation(groupSet, aggCallList, newRowType)" JdbcPushAggIntoScan.java → FOUND
```

## Self-Check: PASSED
