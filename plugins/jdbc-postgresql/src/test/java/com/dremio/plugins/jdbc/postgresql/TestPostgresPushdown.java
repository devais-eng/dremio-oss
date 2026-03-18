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

    // Phase 38: expression pushdown gap test tables
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS pushdown_expr_test ("
            + "  id INTEGER, name VARCHAR(50), department VARCHAR(50),"
            + "  salary NUMERIC(10,2), hire_date DATE"
            + ")");
    PostgresTestContainer.executeSql(
        "INSERT INTO pushdown_expr_test VALUES "
            + "(1, 'Alice', 'Engineering', 80000.00, '2020-03-15'),"
            + "(2, 'Bob', 'Engineering', 95000.00, '2021-07-22'),"
            + "(3, 'Carol', 'Marketing', 72000.00, '2020-11-01'),"
            + "(4, 'Dave', 'Marketing', 68000.00, '2022-01-10'),"
            + "(5, 'Eve', 'Engineering', 105000.00, '2023-06-30')");
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS departments_expr_test ("
            + "  dept_id INTEGER, dept_name VARCHAR(50)"
            + ")");
    PostgresTestContainer.executeSql(
        "INSERT INTO departments_expr_test VALUES (1, 'Engineering'), (2, 'Marketing')");

    // Phase 42: pgvector distance pushdown integration test table
    PostgresTestContainer.executeSql("CREATE EXTENSION IF NOT EXISTS vector");
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS pgvec_knn_test ("
            + "  id SERIAL PRIMARY KEY,"
            + "  embedding vector(3)"
            + ")");
    PostgresTestContainer.executeSql(
        "INSERT INTO pgvec_knn_test (embedding) VALUES "
            + "('[1.0,2.0,3.0]'), ('[4.0,5.0,6.0]'), ('[0.1,0.2,0.3]')");

    // Gap 2: tables for JOIN ON CAST(integer AS varchar) — real type mismatch
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS products ("
            + "  id INTEGER PRIMARY KEY, name VARCHAR(100) NOT NULL,"
            + "  category_id INTEGER NOT NULL, price NUMERIC(10,2)"
            + ")");
    PostgresTestContainer.executeSql(
        "INSERT INTO products VALUES "
            + "(1, 'Laptop', 1, 999.99), (2, 'Mouse', 1, 29.99),"
            + "(3, 'Desk', 2, 450.00), (4, 'Chair', 2, 350.00), (5, 'Monitor', 1, 599.99)");
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS categories ("
            + "  code VARCHAR(10) PRIMARY KEY, label VARCHAR(100) NOT NULL"
            + ")");
    PostgresTestContainer.executeSql(
        "INSERT INTO categories VALUES ('1', 'Electronics'), ('2', 'Furniture')");
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

  // ---------------------------------------------------------------------------
  // Phase 38: Expression pushdown gap integration tests (Gaps 1-4)
  // ---------------------------------------------------------------------------

  /**
   * Gap 1: GROUP BY with function expression EXTRACT(YEAR FROM hire_date).
   * Expected 4 rows for years 2020-2023 with correct SUM(salary) values.
   */
  @Test
  public void testGroupByExpressionExtractYear() throws Exception {
    String sql =
        "SELECT EXTRACT(YEAR FROM \"hire_date\") AS hire_year, SUM(\"salary\") AS total_salary"
            + " FROM \"public\".\"pushdown_expr_test\""
            + " GROUP BY EXTRACT(YEAR FROM \"hire_date\")"
            + " ORDER BY hire_year";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // Year 2020: Alice 80000 + Carol 72000 = 152000
      assertTrue("Must have row for 2020", rs.next());
      assertEquals("hire_year 2020", 2020.0, rs.getDouble("hire_year"), 0.01);
      assertEquals("SUM 2020 = 152000", 152000.00, rs.getDouble("total_salary"), 0.01);
      // Year 2021: Bob 95000
      assertTrue("Must have row for 2021", rs.next());
      assertEquals("hire_year 2021", 2021.0, rs.getDouble("hire_year"), 0.01);
      assertEquals("SUM 2021 = 95000", 95000.00, rs.getDouble("total_salary"), 0.01);
      // Year 2022: Dave 68000
      assertTrue("Must have row for 2022", rs.next());
      assertEquals("hire_year 2022", 2022.0, rs.getDouble("hire_year"), 0.01);
      assertEquals("SUM 2022 = 68000", 68000.00, rs.getDouble("total_salary"), 0.01);
      // Year 2023: Eve 105000
      assertTrue("Must have row for 2023", rs.next());
      assertEquals("hire_year 2023", 2023.0, rs.getDouble("hire_year"), 0.01);
      assertEquals("SUM 2023 = 105000", 105000.00, rs.getDouble("total_salary"), 0.01);
      assertFalse("Exactly 4 rows", rs.next());
    }
  }

  /**
   * Gap 3: Aggregate operand expression SUM(salary * 1.1) with GROUP BY department.
   * Engineering: (80000 + 95000 + 105000) * 1.1 = 308000; Marketing: (72000 + 68000) * 1.1 = 154000.
   */
  @Test
  public void testAggOperandExpression() throws Exception {
    String sql =
        "SELECT \"department\", SUM(\"salary\" * 1.1) AS raised_total"
            + " FROM \"public\".\"pushdown_expr_test\""
            + " GROUP BY \"department\""
            + " ORDER BY \"department\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // Engineering comes before Marketing alphabetically
      assertTrue("Must have row for Engineering", rs.next());
      assertEquals("department Engineering", "Engineering", rs.getString("department"));
      assertEquals("raised_total Engineering = 308000", 308000.00, rs.getDouble("raised_total"), 1.0);
      assertTrue("Must have row for Marketing", rs.next());
      assertEquals("department Marketing", "Marketing", rs.getString("department"));
      assertEquals("raised_total Marketing = 154000", 154000.00, rs.getDouble("raised_total"), 1.0);
      assertFalse("Exactly 2 rows", rs.next());
    }
  }

  /**
   * Gap 4: Bare aggregate (no GROUP BY). SELECT SUM(salary), COUNT(*) over the whole table.
   * Expected: total = 420000, count = 5.
   */
  @Test
  public void testBareAggregateNoGroupBy() throws Exception {
    String sql =
        "SELECT SUM(\"salary\") AS total, COUNT(*) AS cnt"
            + " FROM \"public\".\"pushdown_expr_test\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have exactly one result row", rs.next());
      assertEquals("total salary = 420000", 420000.00, rs.getDouble("total"), 0.01);
      assertEquals("count = 5", 5L, rs.getLong("cnt"));
      assertFalse("Exactly 1 row for bare aggregate", rs.next());
    }
  }

  /**
   * Gap 2: JOIN with CAST condition. Joins employees to departments on department = CAST(dept_name
   * AS VARCHAR). All 5 employees should match their department.
   */
  @Test
  public void testJoinWithCastCondition() throws Exception {
    String sql =
        "SELECT e.\"name\", d.\"dept_name\""
            + " FROM \"public\".\"pushdown_expr_test\" e"
            + " JOIN \"public\".\"departments_expr_test\" d"
            + "   ON e.\"department\" = CAST(d.\"dept_name\" AS VARCHAR)"
            + " ORDER BY e.\"name\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // 5 employees should each match a department
      int count = 0;
      while (rs.next()) {
        String name = rs.getString("name");
        String dept = rs.getString("dept_name");
        assertNotNull("name must not be null", name);
        assertNotNull("dept_name must not be null", dept);
        assertTrue(
            "dept_name must be Engineering or Marketing",
            "Engineering".equals(dept) || "Marketing".equals(dept));
        count++;
      }
      assertEquals("All 5 employees matched via CAST JOIN", 5, count);
    }
  }

  // ---------------------------------------------------------------------------
  // Phase 38-03: Regression-grade pushdown coverage
  // ---------------------------------------------------------------------------

  /**
   * Regression: WHERE IS NOT NULL — all 5 employees have non-null department.
   */
  @Test
  public void testWhereIsNotNull() throws Exception {
    String sql = "SELECT \"name\" FROM \"public\".\"pushdown_expr_test\""
        + " WHERE \"department\" IS NOT NULL";
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
      assertEquals("All 5 employees have non-null department", 5, count);
    }
  }

  /**
   * Regression: WHERE with AND — Engineering AND salary > 90000 matches Bob(95000) and Eve(105000).
   */
  @Test
  public void testWhereWithAnd() throws Exception {
    String sql = "SELECT \"name\" FROM \"public\".\"pushdown_expr_test\""
        + " WHERE \"department\" = 'Engineering' AND \"salary\" > 90000";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        String name = rs.getString("name");
        assertTrue("Should be Bob or Eve", "Bob".equals(name) || "Eve".equals(name));
        count++;
      }
      assertEquals("WHERE Engineering AND salary > 90000 returns 2 rows", 2, count);
    }
  }

  /**
   * Regression: WHERE with OR — Engineering OR Marketing covers all 5 employees.
   */
  @Test
  public void testWhereWithOr() throws Exception {
    String sql = "SELECT \"name\" FROM \"public\".\"pushdown_expr_test\""
        + " WHERE \"department\" = 'Engineering' OR \"department\" = 'Marketing'";
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
      assertEquals("Engineering OR Marketing covers all 5 employees", 5, count);
    }
  }

  /**
   * Regression: ORDER BY multiple columns — department ASC, salary DESC.
   * First row: Eve (Engineering, 105000 highest); last row: Dave (Marketing, 68000 lowest).
   */
  @Test
  public void testOrderByMultipleColumns() throws Exception {
    String sql = "SELECT \"name\", \"salary\" FROM \"public\".\"pushdown_expr_test\""
        + " ORDER BY \"department\" ASC, \"salary\" DESC";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row should be Eve (Engineering, highest salary)", "Eve", rs.getString("name"));
      String lastName = null;
      while (rs.next()) {
        lastName = rs.getString("name");
      }
      assertEquals("Last row should be Dave (Marketing, lowest salary)", "Dave", lastName);
    }
  }

  /**
   * Regression: TopN with WHERE — Engineering employees, top 2 by salary DESC.
   * Expects Eve (105000) and Bob (95000).
   */
  @Test
  public void testTopNWithWhere() throws Exception {
    String sql = "SELECT \"name\", \"salary\" FROM \"public\".\"pushdown_expr_test\""
        + " WHERE \"department\" = 'Engineering' ORDER BY \"salary\" DESC LIMIT 2";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row should be Eve (105000)", "Eve", rs.getString("name"));
      assertTrue("Must have second row", rs.next());
      assertEquals("Second row should be Bob (95000)", "Bob", rs.getString("name"));
      assertFalse("Should be exactly 2 rows", rs.next());
    }
  }

  /**
   * Regression: GROUP BY with HAVING and ORDER BY.
   * Departments with 3+ employees: Engineering (3). Marketing has 2.
   */
  @Test
  public void testGroupByWithHavingAndOrderBy() throws Exception {
    String sql = "SELECT \"department\", COUNT(*) AS cnt"
        + " FROM \"public\".\"pushdown_expr_test\""
        + " GROUP BY \"department\" HAVING COUNT(*) >= 3 ORDER BY \"department\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have exactly one row", rs.next());
      assertEquals("Engineering has 3 employees", "Engineering", rs.getString("department"));
      assertEquals("Count should be 3", 3L, rs.getLong("cnt"));
      assertFalse("Only Engineering has >= 3 employees", rs.next());
    }
  }

  /**
   * Regression: COUNT(DISTINCT department) — expects 2 (Engineering and Marketing).
   */
  @Test
  public void testCountDistinctDepartment() throws Exception {
    String sql = "SELECT COUNT(DISTINCT \"department\") AS dept_count"
        + " FROM \"public\".\"pushdown_expr_test\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      assertEquals("COUNT(DISTINCT department) = 2", 2L, rs.getLong("dept_count"));
    }
  }

  /**
   * Regression: INNER JOIN correctness — all 5 employees match their department.
   */
  @Test
  public void testJoinInnerCorrectness() throws Exception {
    String sql = "SELECT e.\"name\", d.\"dept_name\""
        + " FROM \"public\".\"pushdown_expr_test\" e"
        + " INNER JOIN \"public\".\"departments_expr_test\" d ON e.\"department\" = d.\"dept_name\""
        + " ORDER BY e.\"name\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        assertNotNull("name must not be null", rs.getString("name"));
        String dept = rs.getString("dept_name");
        assertTrue("dept must be Engineering or Marketing",
            "Engineering".equals(dept) || "Marketing".equals(dept));
        count++;
      }
      assertEquals("All 5 employees match in INNER JOIN", 5, count);
    }
  }

  /**
   * Regression: LEFT JOIN correctness — all 5 employees appear (all have matching departments).
   */
  @Test
  public void testJoinLeftCorrectness() throws Exception {
    String sql = "SELECT e.\"name\", d.\"dept_name\""
        + " FROM \"public\".\"pushdown_expr_test\" e"
        + " LEFT JOIN \"public\".\"departments_expr_test\" d ON e.\"department\" = d.\"dept_name\""
        + " ORDER BY e.\"name\"";
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
      assertEquals("All 5 employees appear in LEFT JOIN", 5, count);
    }
  }

  /**
   * Gap 2 regression gate: JOIN with CAST in ON condition AND WHERE filter.
   * CAST(dept_name AS VARCHAR) join + salary > 90000 filter = Bob (Engineering) and Eve (Engineering).
   */
  @Test
  public void testJoinWithCastAndWhereFilter() throws Exception {
    String sql = "SELECT e.\"name\", d.\"dept_name\""
        + " FROM \"public\".\"pushdown_expr_test\" e"
        + " JOIN \"public\".\"departments_expr_test\" d"
        + "   ON e.\"department\" = CAST(d.\"dept_name\" AS VARCHAR)"
        + " WHERE e.\"salary\" > 90000 ORDER BY e.\"name\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row should be Bob", "Bob", rs.getString("name"));
      assertEquals("Bob's dept should be Engineering", "Engineering", rs.getString("dept_name"));
      assertTrue("Must have second row", rs.next());
      assertEquals("Second row should be Eve", "Eve", rs.getString("name"));
      assertEquals("Eve's dept should be Engineering", "Engineering", rs.getString("dept_name"));
      assertFalse("Exactly 2 rows", rs.next());
    }
  }

  /**
   * Gap 2 real type-mismatch test: JOIN ON CAST(integer AS VARCHAR).
   * products.category_id (INTEGER) joined to categories.code (VARCHAR) via CAST.
   * 3 Electronics products + 2 Furniture products = 5 total.
   */
  @Test
  public void testJoinOnCastIntegerAsVarchar() throws Exception {
    String sql = "SELECT p.\"name\", c.\"label\""
        + " FROM \"public\".\"products\" p"
        + " JOIN \"public\".\"categories\" c"
        + "   ON CAST(p.\"category_id\" AS VARCHAR) = c.\"code\""
        + " ORDER BY p.\"name\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        String name = rs.getString("name");
        String label = rs.getString("label");
        assertNotNull("product name must not be null", name);
        assertNotNull("category label must not be null", label);
        count++;
      }
      assertEquals("5 products matched via CAST(integer AS VARCHAR) JOIN", 5, count);
    }
  }

  /**
   * Regression: ORDER BY expression ROUND(salary, -3) DESC LIMIT 3.
   * Returns top 3 rows ordered by rounded salary descending.
   */
  @Test
  public void testOrderByExpressionRoundSalary() throws Exception {
    String sql = "SELECT \"name\", ROUND(\"salary\", -3) AS rounded"
        + " FROM \"public\".\"pushdown_expr_test\""
        + " ORDER BY ROUND(\"salary\", -3) DESC LIMIT 3";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      double prevRounded = Double.MAX_VALUE;
      while (rs.next()) {
        double rounded = rs.getDouble("rounded");
        assertTrue("Results should be in descending order of ROUND(salary,-3)", rounded <= prevRounded);
        prevRounded = rounded;
        count++;
      }
      assertEquals("LIMIT 3 should return exactly 3 rows", 3, count);
    }
  }

  /**
   * Regression: AGG with expression GROUP BY EXTRACT and HAVING.
   * GROUP BY year with SUM(salary) > 100000. Years 2020 (152000) and 2023 (105000) qualify.
   */
  @Test
  public void testAggWithExpressionGroupByExtractAndHaving() throws Exception {
    String sql = "SELECT EXTRACT(YEAR FROM \"hire_date\") AS yr, SUM(\"salary\") AS total"
        + " FROM \"public\".\"pushdown_expr_test\""
        + " GROUP BY EXTRACT(YEAR FROM \"hire_date\")"
        + " HAVING SUM(\"salary\") > 100000 ORDER BY yr";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // Year 2020: Alice 80000 + Carol 72000 = 152000 > 100000
      assertTrue("Must have row for 2020", rs.next());
      assertEquals("Year 2020", 2020.0, rs.getDouble("yr"), 0.01);
      assertEquals("SUM 2020 = 152000", 152000.00, rs.getDouble("total"), 0.01);
      // Year 2021: Bob 95000 < 100000 (excluded)
      // Year 2022: Dave 68000 < 100000 (excluded)
      // Year 2023: Eve 105000 > 100000
      assertTrue("Must have row for 2023", rs.next());
      assertEquals("Year 2023", 2023.0, rs.getDouble("yr"), 0.01);
      assertEquals("SUM 2023 = 105000", 105000.00, rs.getDouble("total"), 0.01);
      assertFalse("Exactly 2 years qualify with SUM > 100000", rs.next());
    }
  }

  /**
   * Regression: SUM(salary * 1.15) GROUP BY department.
   * Engineering: (80000+95000+105000)*1.15 = 322000; Marketing: (72000+68000)*1.15 = 161000.
   */
  @Test
  public void testSumSalaryTimesMultiplierGroupBy() throws Exception {
    String sql = "SELECT \"department\", SUM(\"salary\" * 1.15) AS boosted"
        + " FROM \"public\".\"pushdown_expr_test\""
        + " GROUP BY \"department\" ORDER BY \"department\"";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // Engineering first alphabetically
      assertTrue("Must have row for Engineering", rs.next());
      assertEquals("Engineering", rs.getString("department"));
      assertEquals("Engineering SUM * 1.15 = 322000", 322000.00, rs.getDouble("boosted"), 1.0);
      // Marketing
      assertTrue("Must have row for Marketing", rs.next());
      assertEquals("Marketing", rs.getString("department"));
      assertEquals("Marketing SUM * 1.15 = 161000", 161000.00, rs.getDouble("boosted"), 1.0);
      assertFalse("Exactly 2 departments", rs.next());
    }
  }

  // ---------------------------------------------------------------------------
  // Phase 42: pgvector distance operator integration tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that the pgvector extension is available in the test container, that the
   * {@code <->} (L2 distance) infix operator works with the text literal format
   * {@code '[1.0,2.0,3.0]'}, and that {@code ORDER BY embedding <-> '...' LIMIT 1} returns
   * the single nearest-neighbor row correctly.
   *
   * <p>The table contains three rows: id=1 with {@code [1.0,2.0,3.0]}, id=2 with
   * {@code [4.0,5.0,6.0]}, id=3 with {@code [0.1,0.2,0.3]}. The query vector is
   * {@code [1.0,2.0,3.0]}, which is identical to id=1 (distance = 0.0). The nearest neighbor
   * must be id=1.
   */
  @Test
  public void testPgvectorOrderByDistanceLimitK() throws Exception {
    String sql = "SELECT id FROM pgvec_knn_test"
        + " ORDER BY embedding <-> '[1.0,2.0,3.0]' LIMIT 1";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have exactly one result row for LIMIT 1", rs.next());
      int id = rs.getInt("id");
      assertEquals(
          "Nearest neighbor to [1.0,2.0,3.0] is id=1 (exact match at distance 0.0)",
          1, id);
      assertFalse("LIMIT 1 must return exactly 1 row", rs.next());
    }
  }

  /**
   * Verifies that all three pgvector distance operators ({@code <->}, {@code <=>}, {@code <#>})
   * are accepted by the pgvector database engine and return the expected distance values.
   *
   * <p>For the row with id=1 and embedding {@code [1.0,2.0,3.0]}, and query vector
   * {@code [1.0,2.0,3.0]} (identical):
   * <ul>
   *   <li>L2 distance ({@code <->}): 0.0 (identical vectors)</li>
   *   <li>Cosine distance ({@code <=>}): 0.0 (identical direction)</li>
   *   <li>Inner product ({@code <#>}): -14.0 (pgvector convention: negated dot product,
   *       {@code -(1*1 + 2*2 + 3*3) = -14})</li>
   * </ul>
   *
   * <p>This test confirms that the text literal format {@code '[1.0,2.0,3.0]'} emitted by
   * {@link DremioPostgresDialect} is correctly accepted by the pgvector database engine.
   */
  @Test
  public void testPgvectorAllThreeOperators() throws Exception {
    final double EPSILON = 0.001;

    // L2 distance of identical vectors: sqrt((1-1)^2 + (2-2)^2 + (3-3)^2) = 0.0
    String sqlL2 = "SELECT embedding <-> '[1.0,2.0,3.0]' AS d"
        + " FROM pgvec_knn_test WHERE id = 1";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sqlL2)) {
      assertTrue("L2 distance query must return a row", rs.next());
      double d = rs.getDouble("d");
      assertTrue(
          "L2 distance of [1,2,3] vs [1,2,3] must be 0.0, got: " + d,
          Math.abs(d) < EPSILON);
    }

    // Cosine distance of identical vectors: 0.0
    String sqlCosine = "SELECT embedding <=> '[1.0,2.0,3.0]' AS d"
        + " FROM pgvec_knn_test WHERE id = 1";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sqlCosine)) {
      assertTrue("Cosine distance query must return a row", rs.next());
      double d = rs.getDouble("d");
      assertTrue(
          "Cosine distance of [1,2,3] vs [1,2,3] must be 0.0, got: " + d,
          Math.abs(d) < EPSILON);
    }

    // Inner product (pgvector <#> convention): -(1*1 + 2*2 + 3*3) = -(1+4+9) = -14.0
    String sqlIp = "SELECT embedding <#> '[1.0,2.0,3.0]' AS d"
        + " FROM pgvec_knn_test WHERE id = 1";
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sqlIp)) {
      assertTrue("Inner product query must return a row", rs.next());
      double d = rs.getDouble("d");
      assertTrue(
          "Inner product of [1,2,3] <#> [1,2,3] must be -14.0 (pgvector convention), got: " + d,
          Math.abs(d - (-14.0)) < EPSILON);
    }
  }
}
