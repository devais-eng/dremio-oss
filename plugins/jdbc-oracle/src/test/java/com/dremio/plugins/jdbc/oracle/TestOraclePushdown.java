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
package com.dremio.plugins.jdbc.oracle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dremio.common.expression.SchemaPath;
import com.dremio.plugins.jdbc.planning.SqlBuildRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Pushdown SQL verification tests for the Oracle JDBC connector.
 *
 * <p>Tests verify that {@link OracleSqlBuilder} generates correct SQL for filter, projection, and
 * row-limit pushdown when targeting Oracle. Two categories of tests are provided:
 * <ol>
 *   <li>Pure SQL generation tests that do not require a container — verify the produced SQL
 *       string is syntactically correct and contains the expected clauses.
 *       <strong>Critical:</strong> These tests verify that {@code FETCH FIRST N ROWS ONLY} is
 *       generated (not {@code LIMIT N}, which Oracle does not support).</li>
 *   <li>Container-based tests that execute the generated SQL against a real
 *       {@code gvenzl/oracle-xe:21-slim} container to confirm validity.</li>
 * </ol>
 *
 * <p>Container-based tests use {@link OracleTestContainer} as the shared infrastructure.
 * Oracle identifiers are UPPERCASE — filter columns use {@code "AGE"} not {@code "age"}.
 */
public class TestOraclePushdown {

  @ClassRule
  public static final DremioOracleContainer ORACLE = OracleTestContainer.ORACLE;

  private static final OracleSqlBuilder SQL_BUILDER = new OracleSqlBuilder();

  @BeforeClass
  public static void setUpClass() throws Exception {
    // Create a pushdown_test table for container-based tests
    OracleTestContainer.executeSql(
        "CREATE TABLE pushdown_test (\n"
            + "  id NUMBER(10) NOT NULL,\n"
            + "  name VARCHAR2(100) NOT NULL,\n"
            + "  age NUMBER(3),\n"
            + "  PRIMARY KEY (id)\n"
            + ")");

    // Insert 5 test rows using Oracle's multi-row INSERT ALL syntax
    OracleTestContainer.executeSql(
        "INSERT ALL\n"
            + "  INTO pushdown_test VALUES (1, 'Alice', 30)\n"
            + "  INTO pushdown_test VALUES (2, 'Bob', 25)\n"
            + "  INTO pushdown_test VALUES (3, 'Charlie', 35)\n"
            + "  INTO pushdown_test VALUES (4, 'Dave', 28)\n"
            + "  INTO pushdown_test VALUES (5, 'Eve', 40)\n"
            + "SELECT 1 FROM DUAL");
  }

  @AfterClass
  public static void tearDownClass() {
    // Container is shared; do not stop it here
  }

  // ---------------------------------------------------------------------------
  // Pure SQL generation tests (no container required)
  // ---------------------------------------------------------------------------

  /**
   * SELECT * FROM "TEST_USER"."test_table" when no columns, no filter, no limit are provided.
   */
  @Test
  public void testSelectAllColumns() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, null);
    assertEquals("SELECT * FROM \"TEST_USER\".\"test_table\"", sql);
  }

  /**
   * SELECT projected columns when a column list is provided.
   */
  @Test
  public void testSelectProjectedColumns() {
    List<SchemaPath> cols = Arrays.asList(
        SchemaPath.getSimplePath("id"),
        SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", cols, null, null);
    assertEquals("SELECT \"id\", \"name\" FROM \"TEST_USER\".\"test_table\"", sql);
  }

  /**
   * SELECT * with a WHERE clause appended.
   */
  @Test
  public void testSelectWithWhereClause() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, "\"id\" = 1", null);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain the filter expression", sql.contains("\"id\" = 1"));
  }

  /**
   * SELECT * with a FETCH FIRST clause — critical: Oracle uses FETCH FIRST, not LIMIT.
   */
  @Test
  public void testSelectWithLimit() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, 100);
    assertTrue("SQL must contain FETCH FIRST", sql.contains("FETCH FIRST"));
    assertTrue("SQL must contain the limit value 100", sql.contains("100"));
    assertTrue("SQL must contain ROWS ONLY", sql.contains("ROWS ONLY"));
    // Exact Oracle row-limiting syntax check
    assertTrue("SQL must contain 'FETCH FIRST 100 ROWS ONLY'",
        sql.contains("FETCH FIRST 100 ROWS ONLY"));
  }

  /**
   * SELECT * with both WHERE and FETCH FIRST — WHERE must appear before FETCH FIRST.
   */
  @Test
  public void testSelectWithWhereAndLimit() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, "\"id\" > 5", 100);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain FETCH FIRST", sql.contains("FETCH FIRST"));
    assertTrue(
        "WHERE must appear before FETCH FIRST",
        sql.indexOf("WHERE") < sql.indexOf("FETCH FIRST"));
  }

  /**
   * SELECT projected columns with a WHERE filter.
   */
  @Test
  public void testSelectWithProjectionAndFilter() {
    List<SchemaPath> cols = Arrays.asList(
        SchemaPath.getSimplePath("id"),
        SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", cols, "\"name\" = 'foo'", null);
    assertTrue("SQL must project 'id'", sql.contains("\"id\""));
    assertTrue("SQL must project 'name'", sql.contains("\"name\""));
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain the filter", sql.contains("\"name\" = 'foo'"));
  }

  /**
   * Empty column list produces SELECT *.
   */
  @Test
  public void testEmptyProjectionProducesSelectStar() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", Collections.emptyList(), null, null);
    assertTrue("Empty projection should produce SELECT *", sql.startsWith("SELECT *"));
  }

  /**
   * Null WHERE clause produces no WHERE keyword.
   */
  @Test
  public void testNullWhereClauseOmitted() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, null);
    assertFalse("No WHERE should be present when filter is null", sql.contains("WHERE"));
  }

  /**
   * Null limit produces no FETCH FIRST keyword.
   */
  @Test
  public void testNullLimitOmitted() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, null);
    assertFalse("No FETCH FIRST should be present when limit is null", sql.contains("FETCH FIRST"));
  }

  /**
   * Critical: verify the word LIMIT never appears in Oracle SQL when a limit is provided.
   * Oracle does not support the LIMIT keyword — it uses FETCH FIRST N ROWS ONLY.
   */
  @Test
  public void testNoLimitKeyword() {
    // With limit
    String sqlWithLimit = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, 50);
    assertFalse(
        "Oracle SQL must NOT contain 'LIMIT' keyword: " + sqlWithLimit,
        sqlWithLimit.contains("LIMIT"));
    assertTrue("Oracle SQL must contain 'FETCH FIRST'", sqlWithLimit.contains("FETCH FIRST"));

    // With filter + limit
    String sqlWithBoth = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, "\"id\" > 1", 50);
    assertFalse(
        "Oracle SQL with filter+limit must NOT contain 'LIMIT': " + sqlWithBoth,
        sqlWithBoth.contains("LIMIT"));
  }

  // ---------------------------------------------------------------------------
  // Container-based execution tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that the generated SELECT * SQL executes successfully against Oracle.
   */
  @Test
  public void testPushdownQueryExecutesAgainstOracle() throws Exception {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", null, null, null);
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int rowCount = 0;
      while (rs.next()) {
        rowCount++;
      }
      assertEquals("Expected exactly 5 rows from pushdown_test", 5, rowCount);
    }
  }

  /**
   * Verifies that a WHERE filter pushdown returns only matching rows.
   *
   * <p>Filter: "AGE" > 28 — should match Alice (30), Charlie (35), Eve (40) = 3 rows.
   * Oracle identifiers are UPPERCASE — the filter uses {@code "AGE"} not {@code "age"}.
   */
  @Test
  public void testFilterPushdownResultCorrectness() throws Exception {
    // Oracle column names are UPPERCASE
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", null, "\"AGE\" > 28", null);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        int age = rs.getInt("AGE");
        assertTrue("All returned rows must have AGE > 28, got: " + age, age > 28);
        count++;
      }
      assertEquals("Expected exactly 3 rows with AGE > 28", 3, count);
    }
  }

  /**
   * Verifies that FETCH FIRST row-limit pushdown returns at most the specified number of rows.
   */
  @Test
  public void testLimitPushdownResultCorrectness() throws Exception {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", null, null, 2);
    assertTrue("SQL must contain FETCH FIRST 2 ROWS ONLY", sql.contains("FETCH FIRST 2 ROWS ONLY"));
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        count++;
      }
      assertEquals("FETCH FIRST 2 ROWS ONLY must return exactly 2 rows", 2, count);
    }
  }

  /**
   * Verifies that projected column pushdown returns only the requested columns.
   */
  @Test
  public void testProjectionPushdownResultCorrectness() throws Exception {
    // Project only the NAME column (Oracle UPPERCASE)
    List<SchemaPath> cols = Collections.singletonList(SchemaPath.getSimplePath("NAME"));
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", cols, null, null);
    assertTrue("SQL must project 'NAME'", sql.contains("\"NAME\""));
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have at least one row", rs.next());
      String name = rs.getString("NAME");
      assertNotNull("Projected NAME column must not be null", name);
    }
  }

  /**
   * Verifies combined projection + filter + FETCH FIRST limit pushdown against Oracle.
   */
  @Test
  public void testCombinedPushdownCorrectness() throws Exception {
    List<SchemaPath> cols = Arrays.asList(
        SchemaPath.getSimplePath("NAME"),
        SchemaPath.getSimplePath("AGE"));
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", cols, "\"AGE\" >= 30", 2);
    assertTrue("SQL must project 'NAME'", sql.contains("\"NAME\""));
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain FETCH FIRST", sql.contains("FETCH FIRST"));
    assertFalse("Oracle SQL must NOT contain 'LIMIT'", sql.contains("LIMIT"));
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        int age = rs.getInt("AGE");
        assertTrue("All returned rows must have AGE >= 30", age >= 30);
        count++;
      }
      assertTrue("FETCH FIRST 2 must return at most 2 rows", count <= 2);
    }
  }

  // ---------------------------------------------------------------------------
  // ORDER BY pushdown SQL generation tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that ORDER BY clause is generated via SqlBuildRequest for Oracle.
   * ORDER BY is dialect-independent; only the row-limiting syntax differs.
   */
  @Test
  public void testOrderByPushdownSqlGeneration() {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .orderBy("\"AGE\" ASC NULLS LAST")
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain ORDER BY", sql.contains("ORDER BY"));
    assertTrue("SQL must contain the sort expression",
        sql.contains("ORDER BY \"AGE\" ASC NULLS LAST"));
    // Oracle ORDER BY should NOT produce LIMIT keyword
    assertFalse("Oracle SQL with ORDER BY only must NOT contain LIMIT",
        sql.contains("LIMIT"));
  }

  /**
   * Verifies TopN (ORDER BY + FETCH FIRST) SQL generation for Oracle.
   * Must produce ORDER BY ... FETCH FIRST N ROWS ONLY (not LIMIT).
   */
  @Test
  public void testTopNPushdownSqlGeneration() {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .orderBy("\"AGE\" DESC NULLS FIRST")
        .limit(3)
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain ORDER BY", sql.contains("ORDER BY"));
    assertTrue("SQL must contain FETCH FIRST 3 ROWS ONLY",
        sql.contains("FETCH FIRST 3 ROWS ONLY"));
    assertTrue("ORDER BY must appear before FETCH FIRST",
        sql.indexOf("ORDER BY") < sql.indexOf("FETCH FIRST"));
    assertFalse("Oracle TopN SQL must NOT contain LIMIT keyword",
        sql.contains("LIMIT"));
  }

  /**
   * Critical Oracle-specific test: ORDER BY + limit generates FETCH FIRST, never LIMIT.
   */
  @Test
  public void testNoLimitKeywordWithOrderBy() {
    // ORDER BY only
    SqlBuildRequest requestOrderOnly = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .orderBy("\"AGE\" ASC NULLS LAST")
        .build();
    String sqlOrderOnly = SQL_BUILDER.buildSql(requestOrderOnly);
    assertFalse("Oracle SQL (ORDER BY only) must NOT contain LIMIT: " + sqlOrderOnly,
        sqlOrderOnly.contains("LIMIT"));

    // ORDER BY + limit
    SqlBuildRequest requestTopN = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .orderBy("\"AGE\" DESC NULLS FIRST")
        .limit(5)
        .build();
    String sqlTopN = SQL_BUILDER.buildSql(requestTopN);
    assertFalse("Oracle TopN SQL must NOT contain LIMIT: " + sqlTopN,
        sqlTopN.contains("LIMIT"));
    assertTrue("Oracle TopN SQL must contain FETCH FIRST", sqlTopN.contains("FETCH FIRST"));
  }

  // ---------------------------------------------------------------------------
  // ORDER BY container-based execution tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies ORDER BY execution against a real Oracle container.
   * Sorts by AGE ASC NULLS LAST: Bob(25), Dave(28), Alice(30), Charlie(35), Eve(40).
   */
  @Test
  public void testOrderByExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .orderBy("\"AGE\" ASC NULLS LAST")
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have at least one row", rs.next());
      int firstAge = rs.getInt("AGE");
      assertEquals("First row should have AGE=25 (Bob)", 25, firstAge);
      // Advance to last row
      int lastAge = firstAge;
      while (rs.next()) {
        lastAge = rs.getInt("AGE");
      }
      assertEquals("Last row should have AGE=40 (Eve)", 40, lastAge);
    }
  }

  /**
   * Verifies TopN (ORDER BY + FETCH FIRST) execution: top 2 by AGE DESC.
   * Should return Eve(40) and Charlie(35).
   */
  @Test
  public void testTopNExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .orderBy("\"AGE\" DESC NULLS FIRST")
        .limit(2)
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row AGE should be 40 (Eve)", 40, rs.getInt("AGE"));
      assertTrue("Must have second row", rs.next());
      assertEquals("Second row AGE should be 35 (Charlie)", 35, rs.getInt("AGE"));
      // Should be exactly 2 rows
      assertFalse("FETCH FIRST 2 should return exactly 2 rows", rs.next());
    }
  }

  // ---------------------------------------------------------------------------
  // Aggregation pushdown SQL generation tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies COUNT(*) SQL generation for Oracle (no GROUP BY).
   * Must not contain LIMIT keyword.
   */
  @Test
  public void testAggregationCountStarSqlGeneration() {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .selectExprs(Arrays.asList("COUNT(*)"))
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain SELECT COUNT(*)", sql.contains("SELECT COUNT(*)"));
    assertFalse("Oracle aggregate SQL must NOT contain LIMIT", sql.contains("LIMIT"));
    assertFalse("No GROUP BY expected for pure aggregate", sql.contains("GROUP BY"));
  }

  /**
   * Verifies GROUP BY SQL generation for Oracle.
   */
  @Test
  public void testAggregationGroupBySqlGeneration() {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .selectExprs(Arrays.asList("\"NAME\"", "COUNT(*)"))
        .groupBy("\"NAME\"")
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain GROUP BY", sql.contains("GROUP BY \"NAME\""));
    assertTrue("SQL must contain SELECT with NAME and COUNT",
        sql.contains("SELECT \"NAME\", COUNT(*)"));
  }

  // ---------------------------------------------------------------------------
  // Aggregation container-based execution tests
  // ---------------------------------------------------------------------------

  /**
   * COUNT(*) against Oracle container. Expected: 5 rows total.
   */
  @Test
  public void testAggregationCountStarExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .selectExprs(Arrays.asList("COUNT(*)"))
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      long count = rs.getLong(1);
      assertEquals("COUNT(*) should return 5", 5L, count);
      assertFalse("Should be exactly one result row", rs.next());
    }
  }

  /**
   * SUM(AGE) against Oracle container. Expected: 30+25+35+28+40 = 158.
   */
  @Test
  public void testAggregationSumExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .selectExprs(Arrays.asList("SUM(\"AGE\")"))
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      long sum = rs.getLong(1);
      assertEquals("SUM(AGE) should return 158", 158L, sum);
    }
  }

  /**
   * GROUP BY NAME with COUNT(*). All names are unique, so 5 groups.
   */
  @Test
  public void testAggregationGroupByExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .selectExprs(Arrays.asList("\"NAME\"", "COUNT(*)"))
        .groupBy("\"NAME\"")
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int groupCount = 0;
      while (rs.next()) {
        groupCount++;
        assertNotNull("Group key (NAME) must not be null", rs.getString(1));
      }
      assertEquals("Expected 5 groups (one per unique NAME)", 5, groupCount);
    }
  }

  /**
   * MIN, MAX, AVG aggregate functions against Oracle container.
   * MIN(AGE)=25, MAX(AGE)=40.
   */
  @Test
  public void testAggregationMinMaxAvgExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .selectExprs(Arrays.asList("MIN(\"AGE\")", "MAX(\"AGE\")", "AVG(\"AGE\")"))
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      int min = rs.getInt(1);
      int max = rs.getInt(2);
      double avg = rs.getDouble(3);
      assertEquals("MIN(AGE) should be 25", 25, min);
      assertEquals("MAX(AGE) should be 40", 40, max);
      assertTrue("AVG(AGE) should be approximately 31.6", avg >= 31.0 && avg <= 32.0);
    }
  }

  /**
   * COUNT(*) with WHERE filter and GROUP BY against Oracle.
   * WHERE AGE >= 30 matches Alice(30), Charlie(35), Eve(40) = 3 groups.
   */
  @Test
  public void testAggregationWithFilterAndGroupByExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request = SqlBuildRequest.builder()
        .schema("TEST_USER")
        .table("PUSHDOWN_TEST")
        .where("\"AGE\" >= 30")
        .selectExprs(Arrays.asList("\"NAME\"", "COUNT(*)"))
        .groupBy("\"NAME\"")
        .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain GROUP BY", sql.contains("GROUP BY"));
    assertTrue("WHERE must appear before GROUP BY",
        sql.indexOf("WHERE") < sql.indexOf("GROUP BY"));
    try (Connection conn = DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getUsername(),
            OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int groupCount = 0;
      while (rs.next()) {
        groupCount++;
      }
      assertEquals("Expected 3 groups with AGE >= 30", 3, groupCount);
    }
  }
}
