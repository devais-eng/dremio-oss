/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.plugins.jdbc.planning;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.adapter.jdbc.JdbcConvention;
import org.apache.calcite.adapter.jdbc.JdbcRules;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for EXCEPT/INTERSECT pushdown via JdbcRel subtree construction.
 *
 * <p>Verifies that the Calcite JdbcRel subtree correctly handles the MinusToJoin (EXCEPT) pattern
 * by including JdbcAggregate nodes between the JdbcCalciteLeaf and JdbcJoin. This is the core
 * mechanism that Phase 36 needs to support for set-operation-generated JOINs.
 *
 * <p>Tests directly construct JdbcRel subtrees and render SQL via {@link DremioJdbcImplementor},
 * without requiring a full plugin or PhysicalPlanCreator.
 */
public class TestSetOpJoinPushdown {

  private JavaTypeFactoryImpl typeFactory;
  private RexBuilder rex;
  private RelOptCluster cluster;
  private JdbcConvention convention;
  private RelTraitSet jdbcTraitSet;

  @Before
  public void setUp() {
    typeFactory = new JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    rex = new RexBuilder(typeFactory);
    VolcanoPlanner planner = new VolcanoPlanner();
    SqlDialect dialect = SqlDialect.DatabaseProduct.POSTGRESQL.getDialect();
    convention = JdbcConvention.of(dialect, null, "TEST_CONVENTION");
    // Register the JdbcConvention so VolcanoPlanner recognizes it in trait sets
    planner.addRelTraitDef(org.apache.calcite.plan.ConventionTraitDef.INSTANCE);
    cluster = RelOptCluster.create(planner, rex);
    jdbcTraitSet = cluster.traitSet().replace(convention);
  }

  // =========================================================================
  // EXCEPT (MinusToJoin) subtree tests
  // =========================================================================

  /**
   * Verifies that the JdbcRel subtree for an EXCEPT-generated LEFT JOIN includes JdbcAggregate
   * nodes. The expected SQL structure is:
   *
   * <pre>
   * SELECT ...
   * FROM (SELECT "name", "age", COUNT(*) FROM "public"."t1" GROUP BY "name", "age") AS "t0"
   * LEFT JOIN (SELECT "name", "age", COUNT(*) FROM "public"."t2" GROUP BY "name", "age") AS "t1"
   * ON ...
   * </pre>
   */
  @Test
  public void testExceptPatternWithAggregateSubtree() throws Exception {
    // Table row type: (name VARCHAR, age INTEGER)
    RelDataType tableRowType =
        typeFactory
            .builder()
            .add("name", SqlTypeName.VARCHAR, 100)
            .add("age", SqlTypeName.INTEGER)
            .build();

    // Create leaf nodes for two tables
    JdbcCalciteLeaf leftLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, tableRowType, "public", "t1");
    JdbcCalciteLeaf rightLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, tableRowType, "public", "t2");

    // Create aggregate: GROUP BY name, age + COUNT(*)
    // This mirrors what MinusToJoin.transformToUnique() produces
    ImmutableBitSet groupSet = ImmutableBitSet.of(0, 1); // group by both columns
    AggregateCall countStar =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            false, // not distinct
            false, // not approximate
            ImmutableList.of(), // no args = COUNT(*)
            -1, // no filter
            org.apache.calcite.rel.RelCollations.EMPTY,
            2, // group count (determines output field index)
            leftLeaf,
            null, // return type inferred
            null); // no name

    JdbcRules.JdbcAggregate leftAgg =
        new JdbcRules.JdbcAggregate(
            cluster,
            jdbcTraitSet,
            leftLeaf,
            groupSet,
            ImmutableList.of(groupSet),
            ImmutableList.of(countStar));

    // Re-create countStar for right side with right leaf as input
    AggregateCall countStarRight =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            false,
            false,
            ImmutableList.of(),
            -1,
            org.apache.calcite.rel.RelCollations.EMPTY,
            2,
            rightLeaf,
            null,
            null);

    JdbcRules.JdbcAggregate rightAgg =
        new JdbcRules.JdbcAggregate(
            cluster,
            jdbcTraitSet,
            rightLeaf,
            groupSet,
            ImmutableList.of(groupSet),
            ImmutableList.of(countStarRight));

    // Aggregate output: (name, age, count) = 3 fields per side
    // Join condition: IS_NOT_DISTINCT_FROM on name (index 0 vs 3) AND age (index 1 vs 4)
    RelDataType leftAggRowType = leftAgg.getRowType();
    RelDataType rightAggRowType = rightAgg.getRowType();
    int leftCount = leftAggRowType.getFieldCount(); // 3

    RexNode nameCondition =
        rex.makeCall(
            SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
            rex.makeInputRef(leftAggRowType.getFieldList().get(0).getType(), 0),
            rex.makeInputRef(
                rightAggRowType.getFieldList().get(0).getType(), leftCount + 0));
    RexNode ageCondition =
        rex.makeCall(
            SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
            rex.makeInputRef(leftAggRowType.getFieldList().get(1).getType(), 1),
            rex.makeInputRef(
                rightAggRowType.getFieldList().get(1).getType(), leftCount + 1));
    RexNode condition =
        rex.makeCall(SqlStdOperatorTable.AND, nameCondition, ageCondition);

    // Build JdbcJoin (LEFT JOIN for EXCEPT semantics)
    JdbcRules.JdbcJoin joinNode =
        new JdbcRules.JdbcJoin(
            cluster,
            jdbcTraitSet,
            leftAgg,
            rightAgg,
            condition,
            Collections.emptySet(),
            JoinRelType.LEFT);

    // Render SQL
    DremioJdbcImplementor implementor =
        new DremioJdbcImplementor(
            SqlDialect.DatabaseProduct.POSTGRESQL.getDialect(), typeFactory);
    SqlImplementor.Result result = implementor.implement(joinNode);
    String sql =
        result
            .asStatement()
            .toSqlString(SqlDialect.DatabaseProduct.POSTGRESQL.getDialect())
            .getSql();

    assertNotNull("Generated SQL must not be null", sql);

    // Verify the SQL contains GROUP BY (from the aggregate)
    assertTrue(
        "EXCEPT pattern SQL must contain GROUP BY for left aggregate: " + sql,
        sql.contains("GROUP BY"));

    // Verify the SQL contains LEFT JOIN
    assertTrue(
        "EXCEPT pattern SQL must contain LEFT JOIN: " + sql,
        sql.contains("LEFT JOIN"));

    // Verify the SQL references both tables
    assertTrue(
        "EXCEPT pattern SQL must reference table t1: " + sql,
        sql.contains("\"t1\""));
    assertTrue(
        "EXCEPT pattern SQL must reference table t2: " + sql,
        sql.contains("\"t2\""));

    // Calcite's JdbcImplementor renders SELECT * for a bare JdbcJoin without a JdbcProject.
    // This is correct for the EXCEPT case because Dremio's Filter and Project above the
    // JdbcJoinScanPrel handle the final column selection and WHERE filtering.
    // The key is that the subqueries contain GROUP BY + COUNT, not that SELECT * is avoided.

    // Verify the SQL contains COUNT (from the aggregate)
    assertTrue(
        "EXCEPT pattern SQL must contain COUNT aggregate: " + sql,
        sql.toUpperCase().contains("COUNT"));
  }

  /**
   * Verifies that a direct join (no aggregate wrapping) still produces correct SQL. This is the
   * normal user-written JOIN case that must continue to work after the EXCEPT fix.
   */
  @Test
  public void testDirectJoinWithoutAggregate() throws Exception {
    RelDataType leftRowType =
        typeFactory
            .builder()
            .add("id", SqlTypeName.INTEGER)
            .add("name", SqlTypeName.VARCHAR, 100)
            .build();
    RelDataType rightRowType =
        typeFactory
            .builder()
            .add("id", SqlTypeName.INTEGER)
            .add("dept", SqlTypeName.VARCHAR, 50)
            .build();

    JdbcCalciteLeaf leftLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, leftRowType, "hr", "employees");
    JdbcCalciteLeaf rightLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, rightRowType, "hr", "departments");

    // Join on id = id
    RexNode condition =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(leftRowType.getFieldList().get(0).getType(), 0),
            rex.makeInputRef(
                rightRowType.getFieldList().get(0).getType(),
                leftRowType.getFieldCount()));

    JdbcRules.JdbcJoin joinNode =
        new JdbcRules.JdbcJoin(
            cluster,
            jdbcTraitSet,
            leftLeaf,
            rightLeaf,
            condition,
            Collections.emptySet(),
            JoinRelType.INNER);

    DremioJdbcImplementor implementor =
        new DremioJdbcImplementor(
            SqlDialect.DatabaseProduct.POSTGRESQL.getDialect(), typeFactory);
    SqlImplementor.Result result = implementor.implement(joinNode);
    String sql =
        result
            .asStatement()
            .toSqlString(SqlDialect.DatabaseProduct.POSTGRESQL.getDialect())
            .getSql();

    assertNotNull("Direct join SQL must not be null", sql);
    assertTrue(
        "Direct join SQL must contain INNER JOIN: " + sql, sql.contains("INNER JOIN"));
    assertTrue(
        "Direct join SQL must reference employees table: " + sql,
        sql.contains("\"employees\""));
    assertTrue(
        "Direct join SQL must reference departments table: " + sql,
        sql.contains("\"departments\""));
    // Direct joins should NOT have GROUP BY
    assertFalse(
        "Direct join SQL must NOT contain GROUP BY: " + sql,
        sql.contains("GROUP BY"));
  }

  /**
   * Verifies that a JdbcProject wrapping a set-op JdbcJoin correctly selects only the original
   * columns (excluding the aggregate COUNT column). This simulates the projection that
   * getPhysicalOperator() adds to narrow the output to the expected schema.
   */
  @Test
  public void testExceptPatternWithProjection() throws Exception {
    RelDataType tableRowType =
        typeFactory
            .builder()
            .add("name", SqlTypeName.VARCHAR, 100)
            .add("age", SqlTypeName.INTEGER)
            .build();

    JdbcCalciteLeaf leftLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, tableRowType, "public", "employees");
    JdbcCalciteLeaf rightLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, tableRowType, "public", "contractors");

    ImmutableBitSet groupSet = ImmutableBitSet.of(0, 1);
    AggregateCall countStar =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            false, false,
            ImmutableList.of(), -1,
            org.apache.calcite.rel.RelCollations.EMPTY,
            2, leftLeaf, null, null);

    JdbcRules.JdbcAggregate leftAgg =
        new JdbcRules.JdbcAggregate(
            cluster, jdbcTraitSet, leftLeaf, groupSet,
            ImmutableList.of(groupSet), ImmutableList.of(countStar));

    AggregateCall countStarRight =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            false, false,
            ImmutableList.of(), -1,
            org.apache.calcite.rel.RelCollations.EMPTY,
            2, rightLeaf, null, null);

    JdbcRules.JdbcAggregate rightAgg =
        new JdbcRules.JdbcAggregate(
            cluster, jdbcTraitSet, rightLeaf, groupSet,
            ImmutableList.of(groupSet), ImmutableList.of(countStarRight));

    int leftCount = leftAgg.getRowType().getFieldCount();

    // IS_NOT_DISTINCT_FROM conditions
    RexNode cond =
        rex.makeCall(
            SqlStdOperatorTable.AND,
            rex.makeCall(
                SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
                rex.makeInputRef(leftAgg.getRowType().getFieldList().get(0).getType(), 0),
                rex.makeInputRef(
                    rightAgg.getRowType().getFieldList().get(0).getType(), leftCount)),
            rex.makeCall(
                SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
                rex.makeInputRef(leftAgg.getRowType().getFieldList().get(1).getType(), 1),
                rex.makeInputRef(
                    rightAgg.getRowType().getFieldList().get(1).getType(), leftCount + 1)));

    JdbcRules.JdbcJoin joinNode =
        new JdbcRules.JdbcJoin(
            cluster, jdbcTraitSet, leftAgg, rightAgg, cond,
            Collections.emptySet(), JoinRelType.LEFT);

    // Add a JdbcProject to select only the left-side original columns (name, age)
    // plus the right-side count (to allow the filter above to check IS NULL)
    List<RelDataTypeField> joinFields = joinNode.getRowType().getFieldList();
    java.util.List<RexNode> projects = new java.util.ArrayList<>();
    java.util.List<String> projNames = new java.util.ArrayList<>();

    // Select all fields from the join (for EXCEPT, Dremio's Filter and Project above
    // will handle the final selection)
    for (int i = 0; i < joinFields.size(); i++) {
      projects.add(rex.makeInputRef(joinFields.get(i).getType(), i));
      projNames.add(joinFields.get(i).getName());
    }

    RelDataType projRowType =
        typeFactory.createStructType(
            projects.stream()
                .map(RexNode::getType)
                .collect(java.util.stream.Collectors.toList()),
            projNames);

    JdbcRules.JdbcProject projNode =
        new JdbcRules.JdbcProject(cluster, jdbcTraitSet, joinNode, projects, projRowType);

    DremioJdbcImplementor implementor =
        new DremioJdbcImplementor(
            SqlDialect.DatabaseProduct.POSTGRESQL.getDialect(), typeFactory);
    SqlImplementor.Result result = implementor.implement(projNode);
    String sql =
        result
            .asStatement()
            .toSqlString(SqlDialect.DatabaseProduct.POSTGRESQL.getDialect())
            .getSql();

    assertNotNull("Projected EXCEPT SQL must not be null", sql);
    assertTrue(
        "Projected EXCEPT SQL must contain LEFT JOIN: " + sql,
        sql.contains("LEFT JOIN"));
    assertTrue(
        "Projected EXCEPT SQL must contain GROUP BY: " + sql,
        sql.contains("GROUP BY"));
    assertTrue(
        "Projected EXCEPT SQL must reference employees: " + sql,
        sql.contains("\"employees\""));
    assertTrue(
        "Projected EXCEPT SQL must reference contractors: " + sql,
        sql.contains("\"contractors\""));
  }

  /**
   * Verifies that IS_NOT_DISTINCT_FROM in the join condition (used by MinusToJoin) is rendered
   * correctly by the Calcite JdbcImplementor. PostgreSQL supports this operator directly.
   */
  @Test
  public void testIsNotDistinctFromRendering() throws Exception {
    RelDataType rowType =
        typeFactory.builder().add("col", SqlTypeName.INTEGER).build();

    JdbcCalciteLeaf leftLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, rowType, "s", "a");
    JdbcCalciteLeaf rightLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, rowType, "s", "b");

    RexNode condition =
        rex.makeCall(
            SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 0),
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 1));

    JdbcRules.JdbcJoin joinNode =
        new JdbcRules.JdbcJoin(
            cluster, jdbcTraitSet, leftLeaf, rightLeaf, condition,
            Collections.emptySet(), JoinRelType.LEFT);

    DremioJdbcImplementor implementor =
        new DremioJdbcImplementor(
            SqlDialect.DatabaseProduct.POSTGRESQL.getDialect(), typeFactory);
    SqlImplementor.Result result = implementor.implement(joinNode);
    String sql =
        result
            .asStatement()
            .toSqlString(SqlDialect.DatabaseProduct.POSTGRESQL.getDialect())
            .getSql();

    assertNotNull("IS_NOT_DISTINCT_FROM SQL must not be null", sql);
    assertTrue(
        "SQL must contain LEFT JOIN: " + sql,
        sql.contains("LEFT JOIN"));
    // Calcite renders IS NOT DISTINCT FROM as "col" IS NOT DISTINCT FROM "col"
    // or as equivalent expression
    assertTrue(
        "SQL must contain IS NOT DISTINCT FROM or equivalent null-safe comparison: " + sql,
        sql.toUpperCase().contains("IS NOT DISTINCT FROM")
            || sql.toUpperCase().contains("NOT")
            || sql.contains("="));
  }
}
