---
phase: 36-calcite-jdbc-convention-migration-for-sql-generation
plan: "02"
subsystem: jdbc-planning
tags:
  - calcite
  - jdbc-convention
  - join-pushdown
  - sql-generation
  - rexnode
dependency_graph:
  requires:
    - 36-01
  provides:
    - JdbcJoinScanPrel.getPhysicalOperator() via JdbcImplementor + JdbcJoin subtree
    - JdbcJoinScanDrel.conditionRex (RexNode join condition storage)
    - JdbcPushJoinIntoScan stores raw RexNode from LogicalJoin.getCondition()
  affects:
    - plugins/jdbc-base
    - plugins/jdbc-oracle
    - plugins/jdbc-postgresql
tech_stack:
  added:
    - JdbcRules.JdbcJoin (calcite-core 1.22.0) -- join subtree node
    - JdbcRules.JdbcProject (calcite-core 1.22.0) -- optional alias renaming for self-joins
    - DremioJdbcImplementor -- renders complete JOIN SQL from JdbcRel subtree
  patterns:
    - JdbcRel subtree construction in getPhysicalOperator() for join case
    - RexNode join condition stored and passed through logical->physical transition
    - Inline literal rendering (no bind params for join pipeline)
key_files:
  created: []
  modified:
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcJoinScanPrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcJoinScanDrel.java
    - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/JdbcPushJoinIntoScan.java
decisions:
  - "JdbcJoinScanDrel replaces String onClauseSql + List<BindParam> conditionBindParams with single RexNode conditionRex -- eliminates RexToJoinSqlString from the join pipeline"
  - "JdbcJoinScanPrel.getPhysicalOperator() builds two JdbcCalciteLeaf nodes + JdbcRules.JdbcJoin instead of calling SqlBuilder.buildJoinSql() -- dialect-specific alias syntax (Oracle: no AS; Postgres: AS) handled automatically by SqlDialect"
  - "Optional JdbcProject wrapper for self-join dedup aliasing -- when outputRowType field names differ from JdbcJoin output names (e.g., department0 vs department), a JdbcProject renames the columns"
  - "JdbcGroupScan receives Collections.emptyList() for bindParams -- JdbcImplementor renders literals inline, no ? placeholders in JOIN SQL"
  - "SqlBuilder.buildJoinSql() and OracleSqlBuilder.buildJoinSql() are now dead code -- kept in place because existing unit tests (TestSqlBuilder) test them directly; Plan 36-03 may remove them"
metrics:
  duration: 11 min
  completed: "2026-03-15T17:50:00Z"
  tasks_completed: 2
  tasks_total: 2
  files_created: 0
  files_modified: 3
---

# Phase 36 Plan 02: JdbcJoinScanPrel Calcite JdbcJoin Migration Summary

Migrated `JdbcJoinScanPrel.getPhysicalOperator()` from `SqlBuilder.buildJoinSql()` to a Calcite JdbcRel subtree rendered by `DremioJdbcImplementor`, storing the join ON condition as a `RexNode` instead of a SQL string throughout the logical-to-physical pipeline.

## What Was Built

### Task 1: JdbcJoinScanDrel and JdbcPushJoinIntoScan — RexNode condition storage

**JdbcJoinScanDrel** (`plugins/jdbc-base/.../planning/JdbcJoinScanDrel.java`): Replaced the two string fields (`onClauseSql: String`, `conditionBindParams: List<BindParam>`) with a single `conditionRex: RexNode`. The constructor signature, `copy()`, `explainTerms()`, and `getConditionRex()` accessor were updated accordingly. Removed `getOnClauseSql()` and `getConditionBindParams()`.

**JdbcPushJoinIntoScan** (`plugins/jdbc-base/.../planning/JdbcPushJoinIntoScan.java`): In `onMatch()`, removed the `RexToJoinSqlString` conversion block entirely. The rule now passes `join.getCondition()` (raw RexNode) directly to `JdbcJoinScanDrel`. The logger message now uses `joinType.name()` instead of `SqlBuilder.joinTypeToSql(joinType)`, removing the last SqlBuilder reference from this class.

**JdbcJoinScanPrule** (nested class in JdbcPushJoinIntoScan): Updated to pass `logical.getConditionRex()` (single RexNode) instead of `logical.getOnClauseSql() + logical.getConditionBindParams()`.

### Task 2: JdbcJoinScanPrel — JdbcRel subtree + JdbcImplementor rendering

**JdbcJoinScanPrel** (`plugins/jdbc-base/.../planning/JdbcJoinScanPrel.java`): Complete rewrite of fields and `getPhysicalOperator()`.

**Fields replaced:**
- REMOVED: `onClauseSql: String`, `conditionBindParams: List<BindParam>`
- ADDED: `conditionRex: RexNode`

**`getPhysicalOperator()` new implementation:**

1. Resolves `SqlDialect` via `jdbcPlugin.createDialect()` (same pattern as JdbcScanPrel)
2. Creates `JdbcConvention` and `jdbcTraitSet`
3. Builds per-leaf row types from `leftColumns`/`rightColumns` names and `outputRowType` field types
4. Creates two `JdbcCalciteLeaf` nodes (left table, right table)
5. Builds `JdbcRules.JdbcJoin(cluster, jdbcTraitSet, leftLeaf, rightLeaf, conditionRex, emptySet, joinType)`
6. If `outputRowType` field names differ from `JdbcJoin` output names (self-join dedup case), wraps with `JdbcRules.JdbcProject` that renames columns to match `outputRowType`
7. Renders SQL via `DremioJdbcImplementor.implement(root).asStatement().toSqlString(dialect)`
8. Builds `BatchSchema` from `outputRowType` via `CalciteArrowHelper.fromCalciteRowType()`
9. Creates `JdbcGroupScan` with `Collections.emptyList()` for bindParams

**Oracle dialect behavior:** `OracleSqlDialect.allowsAs() == false` causes table aliases to be rendered without the AS keyword (`"schema"."table" "alias"`), exactly as required by Oracle. This is automatic — no `OracleSqlBuilder.buildJoinSql()` override needed.

**Self-join handling:** When the same table appears on both sides, Calcite's dedup mechanism assigns suffixed names (e.g., `department0`) to disambiguate. The optional `JdbcProject` wrapper renames the join output columns to match these dedup names so the downstream `BatchSchema` aligns.

## Verification Results

```
mvn compile -pl plugins/jdbc-base -DskipTests -am -T4 \
  -Ddremio.enforcer.java-maven-versions.skip=true
→ BUILD SUCCESS

mvn compile -pl plugins/jdbc-base,plugins/jdbc-oracle,plugins/jdbc-postgresql \
  -DskipTests -am -T4 -Ddremio.enforcer.java-maven-versions.skip=true
→ BUILD SUCCESS
```

All three JDBC modules compile cleanly.

## Deviations from Plan

None -- plan executed exactly as written.

Both tasks were implemented as described. The `buildJoinSql()` methods in `SqlBuilder.java` and `OracleSqlBuilder.java` remain in place as dead code (they have existing unit tests and are kept for Plan 36-03 cleanup).

## Self-Check

```
grep "conditionRex" JdbcJoinScanDrel.java → FOUND (field, constructor, copy, explainTerms, accessor)
grep "conditionRex" JdbcJoinScanPrel.java → FOUND (field, constructor, copy, explainTerms, accessor, getPhysicalOperator)
grep "conditionRex" JdbcPushJoinIntoScan.java → FOUND (in comment and JdbcJoinScanPrule.onMatch)
grep "onClauseSql" JdbcJoinScanDrel.java → NOT FOUND (removed)
grep "conditionBindParams" JdbcJoinScanDrel.java → NOT FOUND (removed)
grep "RexToJoinSqlString" JdbcPushJoinIntoScan.java → NOT FOUND (removed from pipeline)
grep "JdbcCalciteLeaf" JdbcJoinScanPrel.java → FOUND (two leaf nodes built)
grep "JdbcRules.JdbcJoin" JdbcJoinScanPrel.java → FOUND (join node built)
grep "DremioJdbcImplementor" JdbcJoinScanPrel.java → FOUND (SQL rendering)
grep "Collections.emptyList" JdbcJoinScanPrel.java → FOUND (bindParams for JdbcGroupScan)
```

## Self-Check: PASSED
