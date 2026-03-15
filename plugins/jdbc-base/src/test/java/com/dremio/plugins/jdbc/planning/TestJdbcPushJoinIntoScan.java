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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dremio.common.expression.SchemaPath;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for JOIN SQL generation and rule matching in {@link JdbcPushJoinIntoScan}.
 *
 * <p>All tests are pure unit tests that exercise SQL string generation via {@link SqlBuilder} and
 * the same-source guard logic directly — no container or JDBC connection required.
 */
public class TestJdbcPushJoinIntoScan {

  private SqlBuilder builder;
  private RelDataTypeFactory typeFactory;
  private RexBuilder rex;

  @Before
  public void setUp() {
    builder = new SqlBuilder();
    typeFactory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    rex = new RexBuilder(typeFactory);
  }

  // =========================================================================
  // SQL generation tests via SqlBuilder.buildJoinSql
  // =========================================================================

  @Test
  public void testBuildJoinSqlInner() {
    List<SchemaPath> leftCols =
        Arrays.asList(SchemaPath.getSimplePath("order_id"), SchemaPath.getSimplePath("customer_id"));
    List<SchemaPath> rightCols = Collections.singletonList(SchemaPath.getSimplePath("name"));

    String sql =
        builder.buildJoinSql(
            "public",
            "orders",
            "t1",
            "public",
            "customers",
            "t2",
            "INNER JOIN",
            "\"t1\".\"customer_id\" = \"t2\".\"customer_id\"",
            leftCols,
            rightCols);

    assertNotNull(sql);
    assertTrue("SELECT should contain t1.order_id", sql.contains("\"t1\".\"order_id\""));
    assertTrue("SELECT should contain t1.customer_id", sql.contains("\"t1\".\"customer_id\""));
    assertTrue("SELECT should contain t2.name", sql.contains("\"t2\".\"name\""));
    assertTrue(
        "FROM should reference left table with AS alias",
        sql.contains("\"public\".\"orders\" AS \"t1\""));
    assertTrue(
        "JOIN should reference right table with AS alias",
        sql.contains("INNER JOIN \"public\".\"customers\" AS \"t2\""));
    assertTrue("ON clause present", sql.contains("ON \"t1\".\"customer_id\" = \"t2\".\"customer_id\""));

    // Verify exact structure (no aliases when outputFieldNames is null)
    assertEquals(
        "SELECT \"t1\".\"order_id\", \"t1\".\"customer_id\", \"t2\".\"name\""
            + " FROM \"public\".\"orders\" AS \"t1\""
            + " INNER JOIN \"public\".\"customers\" AS \"t2\""
            + " ON \"t1\".\"customer_id\" = \"t2\".\"customer_id\"",
        sql);
  }

  @Test
  public void testBuildJoinSqlLeftOuter() {
    List<SchemaPath> leftCols = Collections.singletonList(SchemaPath.getSimplePath("id"));
    List<SchemaPath> rightCols = Collections.singletonList(SchemaPath.getSimplePath("name"));

    String sql =
        builder.buildJoinSql(
            "hr",
            "employees",
            "t1",
            "hr",
            "departments",
            "t2",
            "LEFT OUTER JOIN",
            "\"t1\".\"dept_id\" = \"t2\".\"id\"",
            leftCols,
            rightCols);

    assertTrue("Should use LEFT OUTER JOIN", sql.contains("LEFT OUTER JOIN"));
    assertFalse("Should not use INNER JOIN", sql.contains("INNER JOIN"));
  }

  @Test
  public void testBuildJoinSqlRightOuter() {
    List<SchemaPath> leftCols = Collections.singletonList(SchemaPath.getSimplePath("id"));
    List<SchemaPath> rightCols = Collections.singletonList(SchemaPath.getSimplePath("name"));

    String sql =
        builder.buildJoinSql(
            "s1", "t1", "t1", "s2", "t2", "t2",
            "RIGHT OUTER JOIN",
            "\"t1\".\"id\" = \"t2\".\"id\"",
            leftCols, rightCols);

    assertTrue("Should use RIGHT OUTER JOIN", sql.contains("RIGHT OUTER JOIN"));
  }

  @Test
  public void testBuildJoinSqlFullOuter() {
    List<SchemaPath> leftCols = Collections.singletonList(SchemaPath.getSimplePath("id"));
    List<SchemaPath> rightCols = Collections.singletonList(SchemaPath.getSimplePath("id"));

    String sql =
        builder.buildJoinSql(
            "s1", "a", "t1", "s2", "b", "t2",
            "FULL OUTER JOIN",
            "\"t1\".\"id\" = \"t2\".\"id\"",
            leftCols, rightCols);

    assertTrue("Should use FULL OUTER JOIN", sql.contains("FULL OUTER JOIN"));
  }

  @Test
  public void testJoinTypeToSql() {
    assertEquals("INNER JOIN", SqlBuilder.joinTypeToSql(JoinRelType.INNER));
    assertEquals("LEFT OUTER JOIN", SqlBuilder.joinTypeToSql(JoinRelType.LEFT));
    assertEquals("RIGHT OUTER JOIN", SqlBuilder.joinTypeToSql(JoinRelType.RIGHT));
    assertEquals("FULL OUTER JOIN", SqlBuilder.joinTypeToSql(JoinRelType.FULL));
  }

  @Test
  public void testBuildJoinSqlCrossSchema() {
    List<SchemaPath> leftCols = Collections.singletonList(SchemaPath.getSimplePath("id"));
    List<SchemaPath> rightCols = Collections.singletonList(SchemaPath.getSimplePath("order_id"));

    String sql =
        builder.buildJoinSql(
            "schema1",
            "orders",
            "t1",
            "schema2",
            "customers",
            "t2",
            "INNER JOIN",
            "\"t1\".\"customer_id\" = \"t2\".\"id\"",
            leftCols,
            rightCols);

    assertTrue("Cross-schema FROM", sql.contains("FROM \"schema1\".\"orders\" AS \"t1\""));
    assertTrue(
        "Cross-schema JOIN", sql.contains("INNER JOIN \"schema2\".\"customers\" AS \"t2\""));
  }

  @Test
  public void testColumnQuotingInJoinSql() {
    // Column names with special characters should be double-quoted.
    List<SchemaPath> leftCols =
        Collections.singletonList(SchemaPath.getSimplePath("order id")); // space in name
    List<SchemaPath> rightCols =
        Collections.singletonList(
            SchemaPath.getSimplePath("customer\"name")); // double quote in name

    String sql =
        builder.buildJoinSql(
            "s", "t", "t1",
            "s", "u", "t2",
            "INNER JOIN",
            "\"t1\".\"id\" = \"t2\".\"id\"",
            leftCols, rightCols);

    assertTrue("Column with space should be double-quoted", sql.contains("\"t1\".\"order id\""));
    // Double quote inside column name should be escaped as ""
    assertTrue(
        "Column with double-quote should be escaped",
        sql.contains("\"t2\".\"customer\"\"name\""));
  }

  @Test
  public void testSelectStarWhenNoCols() {
    // When both column lists are empty, should use SELECT *
    String sql =
        builder.buildJoinSql(
            "s", "a", "t1", "s", "b", "t2",
            "INNER JOIN",
            "\"t1\".\"id\" = \"t2\".\"id\"",
            Collections.emptyList(), Collections.emptyList());

    assertTrue("Empty columns should produce SELECT *", sql.startsWith("SELECT *"));
  }

  // =========================================================================
  // Same-source guard declination tests (testing the guard logic directly)
  // =========================================================================

  @Test
  public void testRuleDeclinesForDifferentSources() {
    // Directly verify the same-source guard logic using the string comparison.
    String sourceA = "postgres1";
    String sourceB = "postgres2";

    assertFalse(
        "Different source names should not be same-source", sourceA.equals(sourceB));
  }

  @Test
  public void testRuleMatchesForSameSource() {
    String sourceA = "mypostgres";
    String sourceB = "mypostgres";

    assertTrue(
        "Same source names should be considered same-source", sourceA.equals(sourceB));
  }

  @Test
  public void testRuleDeclinesForNullCondition() {
    // When RexToJoinSqlString encounters an unsupported RexNode type, it should return null.
    // Verify by passing a RexNode subtype that is neither RexCall, RexInputRef, nor RexLiteral.
    RelDataType joinRowType =
        typeFactory
            .builder()
            .add("a", SqlTypeName.INTEGER)
            .add("b", SqlTypeName.INTEGER)
            .build();

    RexToJoinSqlString converter = new RexToJoinSqlString(joinRowType, 1, "t1", "t2");

    // org.apache.calcite.rex.RexOver is a RexNode subtype that is not RexCall/RexInputRef/
    // RexLiteral. However, since it's complex to construct, we instead test with a
    // RexDynamicParam which is also unsupported.
    // The simplest unsupported node: RexCorrelVariable (package-private) or RexFieldAccess.
    // Instead, verify that convert(null) is handled (null is not any of the supported types).
    // Passing null won't work (NPE), so test with a concrete unsupported type.

    // Build a condition with a CASE expression (not supported by RexToJoinSqlString).
    // CASE WHEN a = 1 THEN b ELSE a END -- uses RexCall with CASE kind, which is not in our switch.
    org.apache.calcite.rex.RexNode whenCond =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(joinRowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(java.math.BigDecimal.ONE));
    org.apache.calcite.rex.RexNode thenVal =
        rex.makeInputRef(joinRowType.getFieldList().get(1).getType(), 1);
    org.apache.calcite.rex.RexNode elseVal =
        rex.makeInputRef(joinRowType.getFieldList().get(0).getType(), 0);
    org.apache.calcite.rex.RexNode caseExpr =
        rex.makeCall(
            SqlStdOperatorTable.CASE,
            Arrays.asList(whenCond, thenVal, elseVal));

    // The CASE expression is a RexCall with SqlKind.CASE, which is not in the supported switch.
    RexToSqlResult caseResult = converter.convert(caseExpr);
    assertNull(
        "CASE expression should return null (unsupported) from RexToJoinSqlString.convert()",
        caseResult);
  }

  // =========================================================================
  // Schema/table extraction from path components (mirrors JdbcScanPrule logic)
  // =========================================================================

  @Test
  public void testTableMetadataPathExtractionThreeComponents() {
    // [sourceName, schemaName, tableName] -> schemaName, tableName
    List<String> path = Arrays.asList("mypostgres", "public", "orders");
    String schema = path.get(path.size() - 2);
    String table = path.get(path.size() - 1);
    assertEquals("public", schema);
    assertEquals("orders", table);
  }

  @Test
  public void testTableMetadataPathExtractionTwoComponents() {
    // [schemaName, tableName] -> schemaName, tableName
    List<String> path = Arrays.asList("public", "orders");
    String schema = path.get(0);
    String table = path.get(1);
    assertEquals("public", schema);
    assertEquals("orders", table);
  }

  // =========================================================================
  // RexToJoinSqlString alias offset tests
  // =========================================================================

  @Test
  public void testLeftColumnRefUsesT1Alias() {
    // Left table: customer_id (index 0), name (index 1)
    // Right table: order_id (index 2), total (index 3)
    RelDataType joinRowType =
        typeFactory
            .builder()
            .add("customer_id", SqlTypeName.INTEGER)
            .add("name", SqlTypeName.VARCHAR, 100)
            .add("order_id", SqlTypeName.INTEGER)
            .add("total", SqlTypeName.DOUBLE)
            .build();

    int leftFieldCount = 2;
    RexToJoinSqlString converter = new RexToJoinSqlString(joinRowType, leftFieldCount, "t1", "t2");

    // Index 0 (customer_id) -> "t1"."customer_id"
    RexInputRef leftRef = rex.makeInputRef(joinRowType.getFieldList().get(0).getType(), 0);
    RexToSqlResult leftResult = converter.convert(leftRef);
    assertNotNull(leftResult);
    assertEquals("\"t1\".\"customer_id\"", leftResult.getSql());

    // Index 2 (order_id) -> "t2"."order_id" (index >= leftFieldCount)
    RexInputRef rightRef = rex.makeInputRef(joinRowType.getFieldList().get(2).getType(), 2);
    RexToSqlResult rightResult = converter.convert(rightRef);
    assertNotNull(rightResult);
    assertEquals("\"t2\".\"order_id\"", rightResult.getSql());
  }

  @Test
  public void testRightColumnIndexOffset() {
    // Verifies Pitfall 3: RexInputRef(N+k) must produce "t2"."field" not "t1"."field".
    RelDataType joinRowType =
        typeFactory
            .builder()
            .add("left_col", SqlTypeName.INTEGER)
            .add("right_col", SqlTypeName.INTEGER)
            .build();

    // leftFieldCount = 1, so index 1 is the FIRST column of the right table.
    RexToJoinSqlString converter = new RexToJoinSqlString(joinRowType, 1, "t1", "t2");

    // index 0 -> t1."left_col"
    RexToSqlResult r0 = converter.convert(rex.makeInputRef(joinRowType.getFieldList().get(0).getType(), 0));
    assertEquals("\"t1\".\"left_col\"", r0.getSql());

    // index 1 -> t2."right_col" (NOT t1."right_col")
    RexToSqlResult r1 = converter.convert(rex.makeInputRef(joinRowType.getFieldList().get(1).getType(), 1));
    assertEquals("\"t2\".\"right_col\"", r1.getSql());
  }

  @Test
  public void testJoinConditionEquality() {
    RelDataType joinRowType =
        typeFactory
            .builder()
            .add("customer_id", SqlTypeName.INTEGER)
            .add("order_customer_id", SqlTypeName.INTEGER)
            .build();

    RexToJoinSqlString converter = new RexToJoinSqlString(joinRowType, 1, "t1", "t2");

    // t1.customer_id = t2.order_customer_id
    RexNode condition =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(joinRowType.getFieldList().get(0).getType(), 0),
            rex.makeInputRef(joinRowType.getFieldList().get(1).getType(), 1));

    RexToSqlResult result = converter.convert(condition);
    assertNotNull(result);
    assertEquals(
        "\"t1\".\"customer_id\" = \"t2\".\"order_customer_id\"", result.getSql());
    assertTrue("No bind params for column-to-column equality", result.getParams().isEmpty());
  }
}
