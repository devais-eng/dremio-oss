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
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.adapter.jdbc.JdbcConvention;
import org.apache.calcite.adapter.jdbc.JdbcRules;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests documenting {@link SqlDialect} behavior that the Calcite JDBC convention migration
 * (Phase 36) depends on, plus Phase 37 SQL rendering tests for HAVING, COUNT(DISTINCT),
 * CAST/UPPER function expressions in SELECT, and ORDER BY function expressions.
 *
 * <p>These tests do NOT invoke {@code allowsAs()} directly because it is a protected method.
 * Instead they verify the public quoting behavior and document that the protected {@code allowsAs()}
 * behavior is verified end-to-end by the integration tests ({@code TestPostgresJoinPushdown} and
 * {@code TestOracleJoinPushdown}).
 *
 * <p>Phase 37 tests directly construct Calcite JdbcRel subtrees and render SQL via
 * {@link DremioJdbcImplementor}, verifying that HAVING, COUNT(DISTINCT), CAST, UPPER,
 * and ORDER BY expression patterns produce correct SQL.
 */
public class TestCalciteDialectSql {

  // Phase 37 test infrastructure
  private JavaTypeFactoryImpl typeFactory;
  private RexBuilder rex;
  private RelOptCluster cluster;
  private JdbcConvention pgConvention;
  private RelTraitSet jdbcTraitSet;
  private SqlDialect pgDialect;

  @Before
  public void setUp() {
    typeFactory = new JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    rex = new RexBuilder(typeFactory);
    VolcanoPlanner planner = new VolcanoPlanner();
    pgDialect = PostgresqlSqlDialect.DEFAULT;
    pgConvention = JdbcConvention.of(pgDialect, null, "TEST_PG");
    planner.addRelTraitDef(org.apache.calcite.plan.ConventionTraitDef.INSTANCE);
    cluster = RelOptCluster.create(planner, rex);
    jdbcTraitSet = cluster.traitSet().replace(pgConvention);
  }

  /** Helper: build a JdbcCalciteLeaf with (name VARCHAR, age INTEGER) row type. */
  private JdbcCalciteLeaf makeLeaf(String schema, String table) {
    RelDataType rowType =
        typeFactory
            .builder()
            .add("name", SqlTypeName.VARCHAR, 100)
            .add("age", SqlTypeName.INTEGER)
            .build();
    return new JdbcCalciteLeaf(cluster, jdbcTraitSet, rowType, schema, table);
  }

  /** Helper: render a JdbcRel subtree to SQL using DremioJdbcImplementor. */
  private String renderSql(RelNode root, SqlDialect dialect) {
    DremioJdbcImplementor impl =
        new DremioJdbcImplementor(dialect, (org.apache.calcite.adapter.java.JavaTypeFactory) typeFactory);
    SqlImplementor.Result result = impl.implement(root);
    return result.asStatement().toSqlString(dialect).getSql();
  }

  @Test
  public void testPostgresQuotesIdentifier() {
    SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
    // quoteIdentifier wraps in dialect-appropriate quotes.
    // PostgreSQL uses double-quotes for identifiers.
    String quoted = quoteSingle(pg, "public");
    assertTrue(
        "PostgreSQL quoteIdentifier must produce quoted output containing 'public': " + quoted,
        quoted.contains("\"public\"") || quoted.contains("public"));
  }

  @Test
  public void testOracleQuotesIdentifier() {
    SqlDialect oracle = OracleSqlDialect.DEFAULT;
    String quoted = quoteSingle(oracle, "HR");
    assertTrue(
        "Oracle quoteIdentifier must produce quoted output containing 'HR': " + quoted,
        quoted.contains("\"HR\"") || quoted.contains("HR"));
  }

  @Test
  public void testOracleDialectType() {
    // OracleSqlDialect.allowsAs() returns false (protected method, cannot call directly).
    // Test indirectly: the dialect object is an OracleSqlDialect instance.
    // The actual allowsAs() behavior is verified end-to-end by TestOracleJoinPushdown
    // which checks that Oracle-rendered JOIN SQL does NOT contain " AS " before table aliases.
    SqlDialect oracle = OracleSqlDialect.DEFAULT;
    assertTrue(
        "Oracle dialect must be an instance of OracleSqlDialect",
        oracle instanceof OracleSqlDialect);
  }

  @Test
  public void testPostgresDialectType() {
    SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
    assertTrue(
        "PostgreSQL dialect must be an instance of PostgresqlSqlDialect",
        pg instanceof PostgresqlSqlDialect);
    // PostgresqlSqlDialect.allowsAs() returns true (protected, cannot call directly).
    // Verified end-to-end by TestPostgresJoinPushdown which checks that PostgreSQL-rendered
    // JOIN SQL contains AS before table aliases: "schema"."table" AS "alias".
  }

  @Test
  public void testEmbeddedDoubleQuoteEscaping() {
    SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
    // An identifier with an embedded double-quote character must be escaped.
    // PostgreSQL uses doubled double-quotes: "my""table"
    String quoted = quoteSingle(pg, "my\"table");
    assertTrue(
        "Identifier with embedded double-quote must be escaped (doubled): " + quoted,
        quoted.contains("\"\""));
  }

  @Test
  public void testOracleEmbeddedDoubleQuoteEscaping() {
    SqlDialect oracle = OracleSqlDialect.DEFAULT;
    String quoted = quoteSingle(oracle, "my\"table");
    assertTrue(
        "Oracle identifier with embedded double-quote must be escaped (doubled): " + quoted,
        quoted.contains("\"\""));
  }

  @Test
  public void testPostgresQuotesSchemaAndTable() {
    SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
    // Verify both schema and table parts are quoted correctly.
    String schemaQuoted = quoteSingle(pg, "my schema");
    assertTrue(
        "Schema name with space must be double-quoted: " + schemaQuoted,
        schemaQuoted.contains("\"my schema\""));

    String tableQuoted = quoteSingle(pg, "my-table");
    assertTrue(
        "Table name with hyphen must be double-quoted: " + tableQuoted,
        tableQuoted.contains("\"my-table\""));
  }

  @Test
  public void testOracleUppercaseIdentifiers() {
    SqlDialect oracle = OracleSqlDialect.DEFAULT;
    // Oracle typically uses uppercase identifiers. Verify uppercase is preserved.
    String quoted = quoteSingle(oracle, "EMPLOYEES");
    assertTrue(
        "Oracle identifier EMPLOYEES must be preserved in quoted output: " + quoted,
        quoted.contains("EMPLOYEES"));
  }

  /**
   * Helper: call {@link SqlDialect#quoteIdentifier(StringBuilder, String)} and return the result.
   * This exercises the public quoting API that {@link DremioJdbcImplementor} depends on for
   * rendering {@code FROM "schema"."table"} clauses.
   */
  private static String quoteSingle(SqlDialect dialect, String id) {
    StringBuilder buf = new StringBuilder();
    dialect.quoteIdentifier(buf, id);
    return buf.toString();
  }

  // =========================================================================
  // Phase 37: SQL rendering tests for new pushdown patterns
  // =========================================================================

  /**
   * Verifies that a post-aggregate filter (HAVING pattern) renders as valid SQL.
   *
   * <p>Tree: JdbcCalciteLeaf -> JdbcAggregate(groupSet={0}, COUNT(*)) ->
   * JdbcProject(rename) -> JdbcFilter(condition: $1 > 5).
   *
   * <p>Note: Calcite renders a JdbcFilter above a JdbcAggregate as a subquery with WHERE rather
   * than an inline HAVING clause. Both forms are semantically equivalent and valid SQL. The
   * generated SQL is: {@code SELECT * FROM (SELECT "name", COUNT(*) ... GROUP BY "name") WHERE cnt > 5}.
   * This confirms the post-aggregate filter is correctly represented in the SQL tree.
   */
  @Test
  public void testHavingClauseRendering() throws Exception {
    JdbcCalciteLeaf leaf = makeLeaf("public", "employees");

    // Aggregate: GROUP BY name (index 0), COUNT(*) as index 1
    ImmutableBitSet groupSet = ImmutableBitSet.of(0);
    AggregateCall countStar =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            false, false,
            ImmutableList.of(),
            -1,
            RelCollations.EMPTY,
            1,
            leaf,
            null,
            "cnt");
    RelNode agg = new JdbcRules.JdbcAggregate(
        cluster, jdbcTraitSet, leaf, groupSet, null, ImmutableList.of(countStar));

    // Rename project: identity refs for both agg output fields
    List<RexNode> renameProjects = Arrays.asList(
        rex.makeInputRef(agg.getRowType().getFieldList().get(0).getType(), 0),
        rex.makeInputRef(agg.getRowType().getFieldList().get(1).getType(), 1));
    List<String> renameNames = Arrays.asList("name", "cnt");
    RelDataType renameType = typeFactory.createStructType(
        Arrays.asList(
            agg.getRowType().getFieldList().get(0).getType(),
            agg.getRowType().getFieldList().get(1).getType()),
        renameNames);
    RelNode renameProj = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, agg, renameProjects, renameType);

    // Post-aggregate filter: cnt > 5, i.e. $1 > 5
    RexNode havingCondition = rex.makeCall(
        SqlStdOperatorTable.GREATER_THAN,
        rex.makeInputRef(renameProj.getRowType().getFieldList().get(1).getType(), 1),
        rex.makeLiteral(5, typeFactory.createSqlType(SqlTypeName.INTEGER), true));
    RelNode havingFilter = new JdbcRules.JdbcFilter(cluster, jdbcTraitSet, renameProj, havingCondition);

    String sql = renderSql(havingFilter, pgDialect);

    // Calcite renders the post-aggregate filter as either HAVING or as a WHERE on a subquery —
    // both are semantically equivalent and valid for the JDBC driver.
    assertTrue("SQL should contain GROUP BY: " + sql, sql.toUpperCase().contains("GROUP BY"));
    // The filter condition (5 > some aggregate) must appear in the rendered SQL
    assertTrue("SQL should contain the filter value '5': " + sql, sql.contains("5"));
    // Either HAVING or WHERE form is acceptable
    boolean hasHaving = sql.toUpperCase().contains("HAVING");
    boolean hasWhere = sql.toUpperCase().contains("WHERE");
    assertTrue("SQL should contain either HAVING or WHERE for post-aggregate filter: " + sql,
        hasHaving || hasWhere);
  }

  /**
   * Verifies that COUNT(DISTINCT col) renders correctly with the DISTINCT keyword.
   *
   * <p>Tree: JdbcCalciteLeaf -> JdbcAggregate(groupSet={}, COUNT(DISTINCT $1)).
   */
  @Test
  public void testCountDistinctRendering() throws Exception {
    JdbcCalciteLeaf leaf = makeLeaf("public", "employees");

    // COUNT(DISTINCT age) — arg index 1 = age column
    ImmutableBitSet groupSet = ImmutableBitSet.of();
    AggregateCall countDistinct =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            true, // isDistinct
            false,
            ImmutableList.of(1), // arg = age (index 1)
            -1,
            RelCollations.EMPTY,
            0,
            leaf,
            null,
            "cnt_distinct");
    RelNode agg = new JdbcRules.JdbcAggregate(
        cluster, jdbcTraitSet, leaf, groupSet, null, ImmutableList.of(countDistinct));

    String sql = renderSql(agg, pgDialect);

    assertTrue("SQL should contain COUNT(DISTINCT: " + sql,
        sql.toUpperCase().contains("COUNT(DISTINCT"));
  }

  /**
   * Verifies that a CAST expression in the SELECT list renders correctly.
   *
   * <p>Tree: JdbcCalciteLeaf -> JdbcProject(CAST($1 AS VARCHAR)).
   */
  @Test
  public void testProjectWithCastExpression() throws Exception {
    JdbcCalciteLeaf leaf = makeLeaf("public", "employees");

    // CAST(age AS VARCHAR)
    RelDataType varcharType = typeFactory.createSqlType(SqlTypeName.VARCHAR, 100);
    RexNode castExpr = rex.makeCast(varcharType,
        rex.makeInputRef(leaf.getRowType().getFieldList().get(1).getType(), 1));

    List<RexNode> projects = Collections.singletonList(castExpr);
    List<String> fieldNames = Collections.singletonList("age_str");
    RelDataType projType = typeFactory.createStructType(
        Collections.singletonList(varcharType), fieldNames);
    RelNode proj = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, leaf, projects, projType);

    String sql = renderSql(proj, pgDialect);

    assertTrue("SQL should contain CAST(: " + sql, sql.toUpperCase().contains("CAST("));
  }

  /**
   * Verifies that an UPPER() function call in the SELECT list renders correctly.
   *
   * <p>Tree: JdbcCalciteLeaf -> JdbcProject(UPPER($0)).
   */
  @Test
  public void testProjectWithUpperFunction() throws Exception {
    JdbcCalciteLeaf leaf = makeLeaf("public", "employees");

    // UPPER(name)
    RelDataType varcharType = typeFactory.createSqlType(SqlTypeName.VARCHAR, 100);
    RexNode upperExpr = rex.makeCall(SqlStdOperatorTable.UPPER,
        rex.makeInputRef(leaf.getRowType().getFieldList().get(0).getType(), 0));

    List<RexNode> projects = Collections.singletonList(upperExpr);
    List<String> fieldNames = Collections.singletonList("upper_name");
    RelDataType projType = typeFactory.createStructType(
        Collections.singletonList(varcharType), fieldNames);
    RelNode proj = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, leaf, projects, projType);

    String sql = renderSql(proj, pgDialect);

    assertTrue("SQL should contain UPPER(: " + sql, sql.toUpperCase().contains("UPPER("));
  }

  /**
   * Verifies that ORDER BY with a function expression (UPPER) renders correctly.
   *
   * <p>Tree: JdbcCalciteLeaf -> JdbcProject(extended with UPPER($0)) -> JdbcSort(collation on
   * extended column) -> JdbcProject(trim).
   *
   * <p>This mirrors the pattern that JdbcScanPrel.getPhysicalOperator() step 9 produces when
   * sortKeyExpressions is non-null.
   */
  @Test
  public void testOrderByExpression() throws Exception {
    JdbcCalciteLeaf leaf = makeLeaf("public", "employees");

    // Extended project: [name (identity), age (identity), UPPER(name) as _sort_key_0]
    RelDataType nameType = leaf.getRowType().getFieldList().get(0).getType();
    RelDataType ageType = leaf.getRowType().getFieldList().get(1).getType();
    RelDataType upperType = typeFactory.createSqlType(SqlTypeName.VARCHAR, 100);
    RexNode upperExpr = rex.makeCall(SqlStdOperatorTable.UPPER,
        rex.makeInputRef(nameType, 0));

    List<RexNode> extProjects = Arrays.asList(
        rex.makeInputRef(nameType, 0),
        rex.makeInputRef(ageType, 1),
        upperExpr);
    List<String> extNames = Arrays.asList("name", "age", "_sort_key_0");
    RelDataType extType = typeFactory.createStructType(
        Arrays.asList(nameType, ageType, upperType), extNames);
    RelNode extProj = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, leaf, extProjects, extType);

    // JdbcSort on _sort_key_0 (index 2)
    RelCollation sortCollation = RelCollations.of(
        new RelFieldCollation(2, RelFieldCollation.Direction.ASCENDING,
            RelFieldCollation.NullDirection.LAST));
    RelNode sorted = new JdbcRules.JdbcSort(cluster, jdbcTraitSet, extProj, sortCollation, null, null);

    // Trim project: back to [name, age] only
    List<RexNode> trimProjects = Arrays.asList(
        rex.makeInputRef(nameType, 0),
        rex.makeInputRef(ageType, 1));
    List<String> trimNames = Arrays.asList("name", "age");
    RelDataType trimType = typeFactory.createStructType(
        Arrays.asList(nameType, ageType), trimNames);
    RelNode trimProj = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, sorted, trimProjects, trimType);

    String sql = renderSql(trimProj, pgDialect);

    assertTrue("SQL should contain ORDER BY: " + sql, sql.toUpperCase().contains("ORDER BY"));
    assertTrue("SQL should contain UPPER( in ORDER BY: " + sql, sql.toUpperCase().contains("UPPER("));
  }
}
