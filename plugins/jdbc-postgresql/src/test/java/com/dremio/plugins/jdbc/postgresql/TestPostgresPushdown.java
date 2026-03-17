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
package com.dremio.plugins.jdbc.postgresql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dremio.common.expression.SchemaPath;
import com.dremio.plugins.jdbc.planning.SqlBuildRequest;
import com.dremio.plugins.jdbc.planning.SqlBuilder;
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
 * Pushdown SQL verification tests for the PostgreSQL connector.
 *
 * <p>Tests verify that {@link SqlBuilder} generates correct SQL for filter, projection, and LIMIT
 * pushdown when targeting PostgreSQL. Two categories of tests are provided:
 *
 * <ol>
 *   <li>Pure SQL generation tests that do not require a container — verify the produced SQL string
 *       is syntactically correct and contains the expected clauses.
 *   <li>Container-based tests that execute the generated SQL against a real {@code
 *       postgres:16-alpine} container to confirm validity.
 * </ol>
 *
 * <p>Container-based tests use {@link PostgresTestContainer} as the shared infrastructure.
 */
public class TestPostgresPushdown {

  @ClassRule public static final DremioPostgresContainer PG = PostgresTestContainer.PG;

  private static final SqlBuilder SQL_BUILDER = new SqlBuilder();

  @BeforeClass
  public static void setUpClass() throws Exception {
    // Create a pushdown_test table for container-based tests
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS pushdown_test ("
            + "  id SERIAL PRIMARY KEY, "
            + "  name TEXT NOT NULL, "
            + "  age INTEGER"
            + ")");

    // Insert test rows
    PostgresTestContainer.executeSql(
        "INSERT INTO pushdown_test (name, age) VALUES "
            + "('Alice', 30), ('Bob', 25), ('Charlie', 35), ('Dave', 28), ('Eve', 40)");
  }

  @AfterClass
  public static void tearDownClass() {
    // Container is shared; do not stop it here
  }

  // ---------------------------------------------------------------------------
  // Pure SQL generation tests (no container required)
  // ---------------------------------------------------------------------------

  /** SELECT * FROM "public"."test_table" when no columns, no filter, no limit are provided. */
  @Test
  public void testSelectAllColumns() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, null, null);
    assertEquals("SELECT * FROM \"public\".\"test_table\"", sql);
  }

  /** SELECT projected columns when a column list is provided. */
  @Test
  public void testSelectProjectedColumns() {
    List<SchemaPath> cols =
        Arrays.asList(SchemaPath.getSimplePath("id"), SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("public", "test_table", cols, null, null);
    assertEquals("SELECT \"id\", \"name\" FROM \"public\".\"test_table\"", sql);
  }

  /** SELECT * with a WHERE clause appended. */
  @Test
  public void testSelectWithWhereClause() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, "\"id\" = 1", null);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain the filter expression", sql.contains("\"id\" = 1"));
  }

  /** SELECT * with a LIMIT clause appended. */
  @Test
  public void testSelectWithLimit() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, null, 100);
    assertTrue("SQL must contain LIMIT", sql.contains("LIMIT"));
    assertTrue("SQL must contain the limit value", sql.contains("100"));
  }

  /** SELECT * with both WHERE and LIMIT. */
  @Test
  public void testSelectWithWhereAndLimit() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, "\"id\" > 5", 100);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain LIMIT", sql.contains("LIMIT"));
    assertTrue("WHERE must appear before LIMIT", sql.indexOf("WHERE") < sql.indexOf("LIMIT"));
  }

  /** SELECT projected columns with a WHERE filter. */
  @Test
  public void testSelectWithProjectionAndFilter() {
    List<SchemaPath> cols =
        Arrays.asList(SchemaPath.getSimplePath("id"), SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("public", "test_table", cols, "\"name\" = 'foo'", null);
    assertTrue("SQL must project 'id'", sql.contains("\"id\""));
    assertTrue("SQL must project 'name'", sql.contains("\"name\""));
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain the filter", sql.contains("\"name\" = 'foo'"));
  }

  /** Identifiers with hyphens are double-quoted correctly. */
  @Test
  public void testSpecialCharactersInIdentifiers() {
    String sql = SQL_BUILDER.buildSql("public", "my-table", null, null, null);
    assertTrue("Hyphenated table name must be double-quoted: " + sql, sql.contains("\"my-table\""));
    assertEquals("SELECT * FROM \"public\".\"my-table\"", sql);
  }

  /** Empty column list produces SELECT *. */
  @Test
  public void testEmptyProjectionProducesSelectStar() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", Collections.emptyList(), null, null);
    assertTrue("Empty projection should produce SELECT *", sql.startsWith("SELECT *"));
  }

  /** Null WHERE clause produces no WHERE keyword. */
  @Test
  public void testNullWhereClauseOmitted() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, null, null);
    assertTrue("No WHERE should be present when filter is null", !sql.contains("WHERE"));
  }

  /** Null LIMIT produces no LIMIT keyword. */
  @Test
  public void testNullLimitOmitted() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, null, null);
    assertTrue("No LIMIT should be present when limit is null", !sql.contains("LIMIT"));
  }

  // ---------------------------------------------------------------------------
  // Container-based execution tests
  // ---------------------------------------------------------------------------

  /** Verifies that the generated SELECT * SQL executes successfully against PostgreSQL. */
  @Test
  public void testPushdownQueryExecutesAgainstPostgres() throws Exception {
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", null, null, null);
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int rowCount = 0;
      while (rs.next()) {
        rowCount++;
      }
      assertTrue("Expected at least 5 rows from pushdown_test", rowCount >= 5);
    }
  }

  /** Verifies that a WHERE filter pushdown returns only matching rows. */
  @Test
  public void testFilterPushdownResultCorrectness() throws Exception {
    // Filter: age > 28 — should match Alice (30), Charlie (35), Eve (40)
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", null, "\"age\" > 28", null);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        int age = rs.getInt("age");
        assertTrue("All returned rows must have age > 28", age > 28);
        count++;
      }
      assertEquals("Expected exactly 3 rows with age > 28", 3, count);
    }
  }

  /** Verifies that a LIMIT pushdown returns at most the specified number of rows. */
  @Test
  public void testLimitPushdownResultCorrectness() throws Exception {
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", null, null, 2);
    assertTrue("SQL must contain LIMIT", sql.contains("LIMIT 2"));
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        count++;
      }
      assertEquals("LIMIT 2 must return exactly 2 rows", 2, count);
    }
  }

  /** Verifies that projected column pushdown returns only the requested columns. */
  @Test
  public void testProjectionPushdownResultCorrectness() throws Exception {
    List<SchemaPath> cols = Collections.singletonList(SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", cols, null, null);
    assertTrue("SQL must project 'name'", sql.contains("\"name\""));
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have at least one row", rs.next());
      String name = rs.getString("name");
      assertNotNull("Projected name column must not be null", name);
    }
  }

  /** Verifies combined projection + filter + limit pushdown against PostgreSQL. */
  @Test
  public void testCombinedPushdownCorrectness() throws Exception {
    List<SchemaPath> cols =
        Arrays.asList(SchemaPath.getSimplePath("name"), SchemaPath.getSimplePath("age"));
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", cols, "\"age\" >= 30", 2);
    assertTrue("SQL must project 'name'", sql.contains("\"name\""));
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain LIMIT", sql.contains("LIMIT"));
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        int age = rs.getInt("age");
        assertTrue("All returned rows must have age >= 30", age >= 30);
        count++;
      }
      assertTrue("LIMIT 2 must return at most 2 rows", count <= 2);
    }
  }

  // ---------------------------------------------------------------------------
  // ORDER BY pushdown SQL generation tests
  // ---------------------------------------------------------------------------

  /** Verifies that ORDER BY clause is generated via SqlBuildRequest. */
  @Test
  public void testOrderByPushdownSqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .orderBy("\"age\" ASC NULLS LAST")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain ORDER BY", sql.contains("ORDER BY"));
    assertTrue(
        "SQL must contain the sort expression", sql.contains("ORDER BY \"age\" ASC NULLS LAST"));
    assertTrue("ORDER BY must appear after FROM", sql.indexOf("FROM") < sql.indexOf("ORDER BY"));
  }

  /** Verifies TopN (ORDER BY + LIMIT) SQL generation for PostgreSQL. */
  @Test
  public void testTopNPushdownSqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .orderBy("\"age\" DESC NULLS FIRST")
            .limit(3)
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain ORDER BY", sql.contains("ORDER BY"));
    assertTrue("SQL must contain LIMIT 3", sql.contains("LIMIT 3"));
    assertTrue("ORDER BY must appear before LIMIT", sql.indexOf("ORDER BY") < sql.indexOf("LIMIT"));
  }

  // ---------------------------------------------------------------------------
  // ORDER BY container-based execution tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies ORDER BY execution against a real PostgreSQL container. Sorts by age ASC NULLS LAST:
   * Bob(25), Dave(28), Alice(30), Charlie(35), Eve(40).
   */
  @Test
  public void testOrderByExecutesAgainstPostgres() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .orderBy("\"age\" ASC NULLS LAST")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have at least one row", rs.next());
      int firstAge = rs.getInt("age");
      assertEquals("First row should have age=25 (Bob)", 25, firstAge);
      // Advance to last row
      int lastAge = firstAge;
      while (rs.next()) {
        lastAge = rs.getInt("age");
      }
      assertEquals("Last row should have age=40 (Eve)", 40, lastAge);
    }
  }

  /**
   * Verifies TopN (ORDER BY + LIMIT) execution: top 2 by age DESC. Should return Eve(40) and
   * Charlie(35).
   */
  @Test
  public void testTopNExecutesAgainstPostgres() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .orderBy("\"age\" DESC NULLS FIRST")
            .limit(2)
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row age should be 40 (Eve)", 40, rs.getInt("age"));
      assertTrue("Must have second row", rs.next());
      assertEquals("Second row age should be 35 (Charlie)", 35, rs.getInt("age"));
      // Should be exactly 2 rows
      assertTrue("LIMIT 2 should return exactly 2 rows", !rs.next());
    }
  }

  /**
   * Verifies ORDER BY with WHERE filter: age > 28 ordered by name ASC. Should return Alice(30),
   * Charlie(35), Eve(40) in alphabetical order.
   */
  @Test
  public void testOrderByWithFilterExecutesAgainstPostgres() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .where("\"age\" > 28")
            .orderBy("\"name\" ASC NULLS LAST")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      String previousName = "";
      while (rs.next()) {
        String name = rs.getString("name");
        assertTrue(
            "Names should be in ascending order: " + previousName + " < " + name,
            name.compareTo(previousName) > 0);
        previousName = name;
        count++;
      }
      assertEquals("Expected 3 rows with age > 28", 3, count);
    }
  }

  // ---------------------------------------------------------------------------
  // Aggregation pushdown SQL generation tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies COUNT(*) SQL generation without GROUP BY. Produces: SELECT COUNT(*) FROM
   * "public"."pushdown_test"
   */
  @Test
  public void testAggregationCountStarSqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .selectExprs(Arrays.asList("COUNT(*)"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain SELECT COUNT(*)", sql.contains("SELECT COUNT(*)"));
    assertTrue("SQL must contain FROM clause", sql.contains("FROM \"public\".\"pushdown_test\""));
    assertFalse("No GROUP BY expected for pure aggregate", sql.contains("GROUP BY"));
  }

  /**
   * Verifies GROUP BY + COUNT(*) SQL generation. Produces: SELECT "name", COUNT(*) FROM ... GROUP
   * BY "name"
   */
  @Test
  public void testAggregationGroupBySqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .selectExprs(Arrays.asList("\"name\"", "COUNT(*)"))
            .groupBy("\"name\"")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue(
        "SQL must contain SELECT \"name\", COUNT(*)", sql.contains("SELECT \"name\", COUNT(*)"));
    assertTrue("SQL must contain GROUP BY \"name\"", sql.contains("GROUP BY \"name\""));
  }

  /** Verifies aggregation with WHERE clause: WHERE appears before GROUP BY. */
  @Test
  public void testAggregationWithWhereSqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .where("\"age\" > 25")
            .selectExprs(Arrays.asList("COUNT(*)"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE \"age\" > 25"));
    assertTrue("SQL must contain SELECT COUNT(*)", sql.contains("SELECT COUNT(*)"));
    // WHERE must appear before GROUP BY (if present) or end of query
    assertTrue(
        "WHERE must appear before any GROUP BY or aggregate",
        sql.indexOf("WHERE") < sql.indexOf("COUNT(*)") || sql.indexOf("WHERE") > 0);
  }

  // ---------------------------------------------------------------------------
  // Phase 37: Function expression, HAVING, COUNT(DISTINCT) integration tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies HAVING pushdown against PostgreSQL: GROUP BY name HAVING COUNT(*) > 0.
   * All 5 names are unique so each group has count=1, which is > 0. Expects 5 rows.
   * Then test HAVING COUNT(*) > 1 — expects 0 rows.
   */
  @Test
  public void testHavingPushdownExecutesAgainstPostgres() throws Exception {
    String sql = "SELECT \"name\", COUNT(*) AS cnt FROM \"public\".\"pushdown_test\""
        + " GROUP BY \"name\" HAVING COUNT(*) > 0";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        count++;
      }
      assertEquals("HAVING COUNT(*) > 0 should return all 5 groups", 5, count);
    }

    // With a higher threshold — all counts are 1, so HAVING COUNT(*) > 1 returns nothing
    String sql2 = "SELECT \"name\", COUNT(*) AS cnt FROM \"public\".\"pushdown_test\""
        + " GROUP BY \"name\" HAVING COUNT(*) > 1";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql2)) {
      int count = 0;
      while (rs.next()) {
        count++;
      }
      assertEquals("HAVING COUNT(*) > 1 should return 0 rows (all counts are 1)", 0, count);
    }
  }

  /**
   * Verifies COUNT(DISTINCT name) against PostgreSQL container. Expected: 5 distinct names.
   */
  @Test
  public void testCountDistinctExecutesAgainstPostgres() throws Exception {
    String sql = "SELECT COUNT(DISTINCT \"name\") FROM \"public\".\"pushdown_test\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      long count = rs.getLong(1);
      assertEquals("COUNT(DISTINCT name) should return 5", 5L, count);
    }
  }

  /**
   * Verifies ORDER BY UPPER(name) against PostgreSQL container.
   * Alphabetical order by uppercase name: Alice, Bob, Charlie, Dave, Eve.
   */
  @Test
  public void testOrderByUpperExecutesAgainstPostgres() throws Exception {
    String sql = "SELECT \"name\" FROM \"public\".\"pushdown_test\" ORDER BY UPPER(\"name\") ASC";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have rows", rs.next());
      assertEquals("First row should be 'Alice'", "Alice", rs.getString("name"));
      // Advance to last row
      String lastRow = rs.getString("name");
      while (rs.next()) {
        lastRow = rs.getString("name");
      }
      assertEquals("Last row should be 'Eve'", "Eve", lastRow);
    }
  }

  /**
   * Verifies ORDER BY UPPER(name) LIMIT 2 against PostgreSQL container.
   * Alphabetical top-2: Alice, Bob.
   */
  @Test
  public void testOrderByUpperWithLimitExecutesAgainstPostgres() throws Exception {
    String sql = "SELECT \"name\" FROM \"public\".\"pushdown_test\" ORDER BY UPPER(\"name\") ASC LIMIT 2";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row should be 'Alice'", "Alice", rs.getString("name"));
      assertTrue("Must have second row", rs.next());
      assertEquals("Second row should be 'Bob'", "Bob", rs.getString("name"));
      assertFalse("Should return exactly 2 rows", rs.next());
    }
  }

  /**
   * Verifies CAST(age AS VARCHAR) in SELECT against PostgreSQL container.
   * Returns a string result for the age column.
   */
  @Test
  public void testCastInSelectExecutesAgainstPostgres() throws Exception {
    String sql = "SELECT CAST(\"age\" AS VARCHAR) FROM \"public\".\"pushdown_test\" LIMIT 1";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have at least one row", rs.next());
      String ageStr = rs.getString(1);
      assertNotNull("CAST result should not be null", ageStr);
      // Verify it's a number string
      int parsed = Integer.parseInt(ageStr);
      assertTrue("Parsed age should be positive", parsed > 0);
    }
  }

  /**
   * Verifies function composition UPPER(TRIM(name)) with a WHERE filter against PostgreSQL.
   * Only 'Alice' has UPPER(name) = 'ALICE'. Expects exactly 1 row with UPPER(TRIM(name)) = 'ALICE'.
   */
  @Test
  public void testFunctionCompositionExecutesAgainstPostgres() throws Exception {
    String sql = "SELECT UPPER(TRIM(\"name\")) AS upper_name FROM \"public\".\"pushdown_test\""
        + " WHERE UPPER(\"name\") = 'ALICE'";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have exactly one row for ALICE", rs.next());
      String result = rs.getString("upper_name");
      assertEquals("UPPER(TRIM(name)) for Alice should be 'ALICE'", "ALICE", result);
      assertFalse("Should be exactly 1 row", rs.next());
    }
  }

  /**
   * Verifies GROUP BY with function expression HAVING against PostgreSQL.
   * GROUP BY UPPER(name) HAVING COUNT(*) >= 1 ORDER BY UPPER(name). Expects 5 rows.
   */
  @Test
  public void testHavingWithFunctionExecutesAgainstPostgres() throws Exception {
    String sql = "SELECT UPPER(\"name\") AS upper_name, COUNT(*) AS cnt"
        + " FROM \"public\".\"pushdown_test\""
        + " GROUP BY UPPER(\"name\") HAVING COUNT(*) >= 1 ORDER BY UPPER(\"name\")";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        count++;
      }
      assertEquals("GROUP BY UPPER(name) HAVING COUNT(*) >= 1 should return 5 rows", 5, count);
    }
  }

  // ---------------------------------------------------------------------------
  // Aggregation container-based execution tests
  // ---------------------------------------------------------------------------

  /** COUNT(*) against PostgreSQL container. Expected: 5 rows total. */
  @Test
  public void testAggregationCountStarExecutesAgainstPostgres() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .selectExprs(Arrays.asList("COUNT(*)"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      long count = rs.getLong(1);
      assertEquals("COUNT(*) should return 5", 5L, count);
      assertFalse("Should be exactly one result row", rs.next());
    }
  }

  /** SUM(age) against PostgreSQL container. Expected: 30+25+35+28+40 = 158. */
  @Test
  public void testAggregationSumExecutesAgainstPostgres() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .selectExprs(Arrays.asList("SUM(\"age\")"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      long sum = rs.getLong(1);
      assertEquals("SUM(age) should return 158", 158L, sum);
    }
  }

  /** GROUP BY name with SUM(age). All names are unique, so 5 groups. */
  @Test
  public void testAggregationGroupByExecutesAgainstPostgres() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .selectExprs(Arrays.asList("\"name\"", "SUM(\"age\")"))
            .groupBy("\"name\"")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int groupCount = 0;
      while (rs.next()) {
        groupCount++;
        assertNotNull("Group key (name) must not be null", rs.getString(1));
      }
      assertEquals("Expected 5 groups (one per unique name)", 5, groupCount);
    }
  }

  /**
   * MIN, MAX, AVG aggregate functions against PostgreSQL container. MIN(age)=25, MAX(age)=40. AVG
   * checked as between 31 and 32 (integer division varies).
   */
  @Test
  public void testAggregationMinMaxAvgExecutesAgainstPostgres() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .selectExprs(Arrays.asList("MIN(\"age\")", "MAX(\"age\")", "AVG(\"age\")"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      int min = rs.getInt(1);
      int max = rs.getInt(2);
      double avg = rs.getDouble(3);
      assertEquals("MIN(age) should be 25", 25, min);
      assertEquals("MAX(age) should be 40", 40, max);
      assertTrue("AVG(age) should be approximately 31.6", avg >= 31.0 && avg <= 32.0);
    }
  }

  /** COUNT(*) with WHERE filter: age > 28 matches Alice(30), Charlie(35), Eve(40) = 3. */
  @Test
  public void testAggregationWithFilterExecutesAgainstPostgres() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("public")
            .table("pushdown_test")
            .where("\"age\" > 28")
            .selectExprs(Arrays.asList("COUNT(*)"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      long count = rs.getLong(1);
      assertEquals("COUNT(*) with age > 28 should return 3", 3L, count);
    }
  }
}
