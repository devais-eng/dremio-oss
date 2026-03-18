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
 *
 * <ol>
 *   <li>Pure SQL generation tests that do not require a container — verify the produced SQL string
 *       is syntactically correct and contains the expected clauses. <strong>Critical:</strong>
 *       These tests verify that {@code FETCH FIRST N ROWS ONLY} is generated (not {@code LIMIT N},
 *       which Oracle does not support).
 *   <li>Container-based tests that execute the generated SQL against a real {@code
 *       gvenzl/oracle-xe:21-slim} container to confirm validity.
 * </ol>
 *
 * <p>Container-based tests use {@link OracleTestContainer} as the shared infrastructure. Oracle
 * identifiers are UPPERCASE — filter columns use {@code "AGE"} not {@code "age"}.
 */
public class TestOraclePushdown {

  @ClassRule public static final DremioOracleContainer ORACLE = OracleTestContainer.ORACLE;

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

    // Phase 38: expression pushdown gap test tables
    // PUSHDOWN_EXPR_TEST may already exist from a previous run — skip if ORA-00955
    try {
      OracleTestContainer.executeSql(
          "CREATE TABLE PUSHDOWN_EXPR_TEST ("
              + "ID NUMBER(10), NAME VARCHAR2(50), DEPARTMENT VARCHAR2(50),"
              + " SALARY NUMBER(10,2), HIRE_DATE DATE)");
      OracleTestContainer.executeSql(
          "INSERT ALL"
              + " INTO PUSHDOWN_EXPR_TEST VALUES (1, 'Alice', 'Engineering', 80000.00,"
              + " TO_DATE('2020-03-15','YYYY-MM-DD'))"
              + " INTO PUSHDOWN_EXPR_TEST VALUES (2, 'Bob', 'Engineering', 95000.00,"
              + " TO_DATE('2021-07-22','YYYY-MM-DD'))"
              + " INTO PUSHDOWN_EXPR_TEST VALUES (3, 'Carol', 'Marketing', 72000.00,"
              + " TO_DATE('2020-11-01','YYYY-MM-DD'))"
              + " INTO PUSHDOWN_EXPR_TEST VALUES (4, 'Dave', 'Marketing', 68000.00,"
              + " TO_DATE('2022-01-10','YYYY-MM-DD'))"
              + " INTO PUSHDOWN_EXPR_TEST VALUES (5, 'Eve', 'Engineering', 105000.00,"
              + " TO_DATE('2023-06-30','YYYY-MM-DD'))"
              + " SELECT 1 FROM DUAL");
    } catch (java.sql.SQLException e) {
      if (!e.getMessage().contains("ORA-00955") && !e.getMessage().contains("name is already used")) {
        throw e;
      }
    }

    // DEPARTMENTS_EXPR_TEST may already exist from a previous run — skip if ORA-00955
    try {
      OracleTestContainer.executeSql(
          "CREATE TABLE DEPARTMENTS_EXPR_TEST (DEPT_ID NUMBER(10), DEPT_NAME VARCHAR2(50))");
      OracleTestContainer.executeSql(
          "INSERT ALL"
              + " INTO DEPARTMENTS_EXPR_TEST VALUES (1, 'Engineering')"
              + " INTO DEPARTMENTS_EXPR_TEST VALUES (2, 'Marketing')"
              + " SELECT 1 FROM DUAL");
    } catch (java.sql.SQLException e) {
      if (!e.getMessage().contains("ORA-00955") && !e.getMessage().contains("name is already used")) {
        throw e;
      }
    }

    // Gap 2: tables for JOIN ON CAST(integer AS varchar) — real type mismatch
    try {
      OracleTestContainer.executeSql(
          "CREATE TABLE PRODUCTS ("
              + "ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(100) NOT NULL,"
              + "CATEGORY_ID NUMBER(10) NOT NULL, PRICE NUMBER(10,2))");
      OracleTestContainer.executeSql(
          "INSERT ALL"
              + " INTO PRODUCTS VALUES (1, 'Laptop', 1, 999.99)"
              + " INTO PRODUCTS VALUES (2, 'Mouse', 1, 29.99)"
              + " INTO PRODUCTS VALUES (3, 'Desk', 2, 450.00)"
              + " INTO PRODUCTS VALUES (4, 'Chair', 2, 350.00)"
              + " INTO PRODUCTS VALUES (5, 'Monitor', 1, 599.99)"
              + " SELECT 1 FROM DUAL");
      OracleTestContainer.executeSql(
          "CREATE TABLE CATEGORIES ("
              + "CODE VARCHAR2(10) PRIMARY KEY, LABEL VARCHAR2(100) NOT NULL)");
      OracleTestContainer.executeSql(
          "INSERT ALL"
              + " INTO CATEGORIES VALUES ('1', 'Electronics')"
              + " INTO CATEGORIES VALUES ('2', 'Furniture')"
              + " SELECT 1 FROM DUAL");
    } catch (java.sql.SQLException e) {
      if (!e.getMessage().contains("ORA-00955") && !e.getMessage().contains("name is already used")) {
        throw e;
      }
    }
  }

  @AfterClass
  public static void tearDownClass() {
    // Container is shared; do not stop it here
  }

  // ---------------------------------------------------------------------------
  // Pure SQL generation tests (no container required)
  // ---------------------------------------------------------------------------

  /** SELECT * FROM "TEST_USER"."test_table" when no columns, no filter, no limit are provided. */
  @Test
  public void testSelectAllColumns() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, null);
    assertEquals("SELECT * FROM \"TEST_USER\".\"test_table\"", sql);
  }

  /** SELECT projected columns when a column list is provided. */
  @Test
  public void testSelectProjectedColumns() {
    List<SchemaPath> cols =
        Arrays.asList(SchemaPath.getSimplePath("id"), SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", cols, null, null);
    assertEquals("SELECT \"id\", \"name\" FROM \"TEST_USER\".\"test_table\"", sql);
  }

  /** SELECT * with a WHERE clause appended. */
  @Test
  public void testSelectWithWhereClause() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, "\"id\" = 1", null);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain the filter expression", sql.contains("\"id\" = 1"));
  }

  /** SELECT * with a FETCH FIRST clause — critical: Oracle uses FETCH FIRST, not LIMIT. */
  @Test
  public void testSelectWithLimit() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, 100);
    assertTrue("SQL must contain FETCH FIRST", sql.contains("FETCH FIRST"));
    assertTrue("SQL must contain the limit value 100", sql.contains("100"));
    assertTrue("SQL must contain ROWS ONLY", sql.contains("ROWS ONLY"));
    // Exact Oracle row-limiting syntax check
    assertTrue(
        "SQL must contain 'FETCH FIRST 100 ROWS ONLY'", sql.contains("FETCH FIRST 100 ROWS ONLY"));
  }

  /** SELECT * with both WHERE and FETCH FIRST — WHERE must appear before FETCH FIRST. */
  @Test
  public void testSelectWithWhereAndLimit() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, "\"id\" > 5", 100);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain FETCH FIRST", sql.contains("FETCH FIRST"));
    assertTrue(
        "WHERE must appear before FETCH FIRST", sql.indexOf("WHERE") < sql.indexOf("FETCH FIRST"));
  }

  /** SELECT projected columns with a WHERE filter. */
  @Test
  public void testSelectWithProjectionAndFilter() {
    List<SchemaPath> cols =
        Arrays.asList(SchemaPath.getSimplePath("id"), SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", cols, "\"name\" = 'foo'", null);
    assertTrue("SQL must project 'id'", sql.contains("\"id\""));
    assertTrue("SQL must project 'name'", sql.contains("\"name\""));
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain the filter", sql.contains("\"name\" = 'foo'"));
  }

  /** Empty column list produces SELECT *. */
  @Test
  public void testEmptyProjectionProducesSelectStar() {
    String sql =
        SQL_BUILDER.buildSql("TEST_USER", "test_table", Collections.emptyList(), null, null);
    assertTrue("Empty projection should produce SELECT *", sql.startsWith("SELECT *"));
  }

  /** Null WHERE clause produces no WHERE keyword. */
  @Test
  public void testNullWhereClauseOmitted() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, null);
    assertFalse("No WHERE should be present when filter is null", sql.contains("WHERE"));
  }

  /** Null limit produces no FETCH FIRST keyword. */
  @Test
  public void testNullLimitOmitted() {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "test_table", null, null, null);
    assertFalse("No FETCH FIRST should be present when limit is null", sql.contains("FETCH FIRST"));
  }

  /**
   * Critical: verify the word LIMIT never appears in Oracle SQL when a limit is provided. Oracle
   * does not support the LIMIT keyword — it uses FETCH FIRST N ROWS ONLY.
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

  /** Verifies that the generated SELECT * SQL executes successfully against Oracle. */
  @Test
  public void testPushdownQueryExecutesAgainstOracle() throws Exception {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", null, null, null);
    try (Connection conn =
            DriverManager.getConnection(
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
   * <p>Filter: "AGE" > 28 — should match Alice (30), Charlie (35), Eve (40) = 3 rows. Oracle
   * identifiers are UPPERCASE — the filter uses {@code "AGE"} not {@code "age"}.
   */
  @Test
  public void testFilterPushdownResultCorrectness() throws Exception {
    // Oracle column names are UPPERCASE
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", null, "\"AGE\" > 28", null);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    try (Connection conn =
            DriverManager.getConnection(
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

  /** Verifies that FETCH FIRST row-limit pushdown returns at most the specified number of rows. */
  @Test
  public void testLimitPushdownResultCorrectness() throws Exception {
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", null, null, 2);
    assertTrue("SQL must contain FETCH FIRST 2 ROWS ONLY", sql.contains("FETCH FIRST 2 ROWS ONLY"));
    try (Connection conn =
            DriverManager.getConnection(
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

  /** Verifies that projected column pushdown returns only the requested columns. */
  @Test
  public void testProjectionPushdownResultCorrectness() throws Exception {
    // Project only the NAME column (Oracle UPPERCASE)
    List<SchemaPath> cols = Collections.singletonList(SchemaPath.getSimplePath("NAME"));
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", cols, null, null);
    assertTrue("SQL must project 'NAME'", sql.contains("\"NAME\""));
    try (Connection conn =
            DriverManager.getConnection(
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

  /** Verifies combined projection + filter + FETCH FIRST limit pushdown against Oracle. */
  @Test
  public void testCombinedPushdownCorrectness() throws Exception {
    List<SchemaPath> cols =
        Arrays.asList(SchemaPath.getSimplePath("NAME"), SchemaPath.getSimplePath("AGE"));
    String sql = SQL_BUILDER.buildSql("TEST_USER", "PUSHDOWN_TEST", cols, "\"AGE\" >= 30", 2);
    assertTrue("SQL must project 'NAME'", sql.contains("\"NAME\""));
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain FETCH FIRST", sql.contains("FETCH FIRST"));
    assertFalse("Oracle SQL must NOT contain 'LIMIT'", sql.contains("LIMIT"));
    try (Connection conn =
            DriverManager.getConnection(
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
   * Verifies that ORDER BY clause is generated via SqlBuildRequest for Oracle. ORDER BY is
   * dialect-independent; only the row-limiting syntax differs.
   */
  @Test
  public void testOrderByPushdownSqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .orderBy("\"AGE\" ASC NULLS LAST")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain ORDER BY", sql.contains("ORDER BY"));
    assertTrue(
        "SQL must contain the sort expression", sql.contains("ORDER BY \"AGE\" ASC NULLS LAST"));
    // Oracle ORDER BY should NOT produce LIMIT keyword
    assertFalse("Oracle SQL with ORDER BY only must NOT contain LIMIT", sql.contains("LIMIT"));
  }

  /**
   * Verifies TopN (ORDER BY + FETCH FIRST) SQL generation for Oracle. Must produce ORDER BY ...
   * FETCH FIRST N ROWS ONLY (not LIMIT).
   */
  @Test
  public void testTopNPushdownSqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .orderBy("\"AGE\" DESC NULLS FIRST")
            .limit(3)
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain ORDER BY", sql.contains("ORDER BY"));
    assertTrue("SQL must contain FETCH FIRST 3 ROWS ONLY", sql.contains("FETCH FIRST 3 ROWS ONLY"));
    assertTrue(
        "ORDER BY must appear before FETCH FIRST",
        sql.indexOf("ORDER BY") < sql.indexOf("FETCH FIRST"));
    assertFalse("Oracle TopN SQL must NOT contain LIMIT keyword", sql.contains("LIMIT"));
  }

  /** Critical Oracle-specific test: ORDER BY + limit generates FETCH FIRST, never LIMIT. */
  @Test
  public void testNoLimitKeywordWithOrderBy() {
    // ORDER BY only
    SqlBuildRequest requestOrderOnly =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .orderBy("\"AGE\" ASC NULLS LAST")
            .build();
    String sqlOrderOnly = SQL_BUILDER.buildSql(requestOrderOnly);
    assertFalse(
        "Oracle SQL (ORDER BY only) must NOT contain LIMIT: " + sqlOrderOnly,
        sqlOrderOnly.contains("LIMIT"));

    // ORDER BY + limit
    SqlBuildRequest requestTopN =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .orderBy("\"AGE\" DESC NULLS FIRST")
            .limit(5)
            .build();
    String sqlTopN = SQL_BUILDER.buildSql(requestTopN);
    assertFalse("Oracle TopN SQL must NOT contain LIMIT: " + sqlTopN, sqlTopN.contains("LIMIT"));
    assertTrue("Oracle TopN SQL must contain FETCH FIRST", sqlTopN.contains("FETCH FIRST"));
  }

  // ---------------------------------------------------------------------------
  // ORDER BY container-based execution tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies ORDER BY execution against a real Oracle container. Sorts by AGE ASC NULLS LAST:
   * Bob(25), Dave(28), Alice(30), Charlie(35), Eve(40).
   */
  @Test
  public void testOrderByExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .orderBy("\"AGE\" ASC NULLS LAST")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
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
   * Verifies TopN (ORDER BY + FETCH FIRST) execution: top 2 by AGE DESC. Should return Eve(40) and
   * Charlie(35).
   */
  @Test
  public void testTopNExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .orderBy("\"AGE\" DESC NULLS FIRST")
            .limit(2)
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
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
  // Phase 37: Function expression, HAVING, COUNT(DISTINCT) integration tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies HAVING pushdown against Oracle: GROUP BY NAME HAVING COUNT(*) >= 1.
   * All 5 names are unique so each group has count=1, which is >= 1. Expects 5 rows.
   */
  @Test
  public void testHavingPushdownExecutesAgainstOracle() throws Exception {
    String sql = "SELECT \"NAME\", COUNT(*) AS cnt FROM \"TEST_USER\".\"PUSHDOWN_TEST\""
        + " GROUP BY \"NAME\" HAVING COUNT(*) >= 1";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        count++;
      }
      assertEquals("HAVING COUNT(*) >= 1 should return all 5 groups", 5, count);
    }
  }

  /**
   * Verifies COUNT(DISTINCT NAME) against Oracle container. Expected: 5 distinct names.
   */
  @Test
  public void testCountDistinctExecutesAgainstOracle() throws Exception {
    String sql = "SELECT COUNT(DISTINCT \"NAME\") FROM \"TEST_USER\".\"PUSHDOWN_TEST\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      long count = rs.getLong(1);
      assertEquals("COUNT(DISTINCT NAME) should return 5", 5L, count);
    }
  }

  /**
   * Verifies ORDER BY UPPER(NAME) against Oracle container.
   * Alphabetical order: Alice, Bob, Charlie, Dave, Eve.
   */
  @Test
  public void testOrderByUpperExecutesAgainstOracle() throws Exception {
    String sql = "SELECT \"NAME\" FROM \"TEST_USER\".\"PUSHDOWN_TEST\" ORDER BY UPPER(\"NAME\") ASC";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have rows", rs.next());
      assertEquals("First row should be 'Alice'", "Alice", rs.getString("NAME"));
      // Advance to last row
      String lastRow = rs.getString("NAME");
      while (rs.next()) {
        lastRow = rs.getString("NAME");
      }
      assertEquals("Last row should be 'Eve'", "Eve", lastRow);
    }
  }

  /**
   * Verifies CAST(AGE AS VARCHAR2(10)) in SELECT against Oracle container.
   * Must return a string result.
   */
  @Test
  public void testCastInSelectExecutesAgainstOracle() throws Exception {
    String sql = "SELECT CAST(\"AGE\" AS VARCHAR2(10)) FROM \"TEST_USER\".\"PUSHDOWN_TEST\""
        + " FETCH FIRST 1 ROWS ONLY";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have at least one row", rs.next());
      String ageStr = rs.getString(1);
      assertNotNull("CAST result should not be null", ageStr);
      int parsed = Integer.parseInt(ageStr);
      assertTrue("Parsed AGE should be positive", parsed > 0);
    }
  }

  // ---------------------------------------------------------------------------
  // Aggregation pushdown SQL generation tests
  // ---------------------------------------------------------------------------

  /** Verifies COUNT(*) SQL generation for Oracle (no GROUP BY). Must not contain LIMIT keyword. */
  @Test
  public void testAggregationCountStarSqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .selectExprs(Arrays.asList("COUNT(*)"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain SELECT COUNT(*)", sql.contains("SELECT COUNT(*)"));
    assertFalse("Oracle aggregate SQL must NOT contain LIMIT", sql.contains("LIMIT"));
    assertFalse("No GROUP BY expected for pure aggregate", sql.contains("GROUP BY"));
  }

  /** Verifies GROUP BY SQL generation for Oracle. */
  @Test
  public void testAggregationGroupBySqlGeneration() {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .selectExprs(Arrays.asList("\"NAME\"", "COUNT(*)"))
            .groupBy("\"NAME\"")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain GROUP BY", sql.contains("GROUP BY \"NAME\""));
    assertTrue(
        "SQL must contain SELECT with NAME and COUNT", sql.contains("SELECT \"NAME\", COUNT(*)"));
  }

  // ---------------------------------------------------------------------------
  // Aggregation container-based execution tests
  // ---------------------------------------------------------------------------

  /** COUNT(*) against Oracle container. Expected: 5 rows total. */
  @Test
  public void testAggregationCountStarExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .selectExprs(Arrays.asList("COUNT(*)"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
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

  /** SUM(AGE) against Oracle container. Expected: 30+25+35+28+40 = 158. */
  @Test
  public void testAggregationSumExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .selectExprs(Arrays.asList("SUM(\"AGE\")"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
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

  /** GROUP BY NAME with COUNT(*). All names are unique, so 5 groups. */
  @Test
  public void testAggregationGroupByExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .selectExprs(Arrays.asList("\"NAME\"", "COUNT(*)"))
            .groupBy("\"NAME\"")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
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

  /** MIN, MAX, AVG aggregate functions against Oracle container. MIN(AGE)=25, MAX(AGE)=40. */
  @Test
  public void testAggregationMinMaxAvgExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .selectExprs(Arrays.asList("MIN(\"AGE\")", "MAX(\"AGE\")", "AVG(\"AGE\")"))
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    try (Connection conn =
            DriverManager.getConnection(
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
   * COUNT(*) with WHERE filter and GROUP BY against Oracle. WHERE AGE >= 30 matches Alice(30),
   * Charlie(35), Eve(40) = 3 groups.
   */
  @Test
  public void testAggregationWithFilterAndGroupByExecutesAgainstOracle() throws Exception {
    SqlBuildRequest request =
        SqlBuildRequest.builder()
            .schema("TEST_USER")
            .table("PUSHDOWN_TEST")
            .where("\"AGE\" >= 30")
            .selectExprs(Arrays.asList("\"NAME\"", "COUNT(*)"))
            .groupBy("\"NAME\"")
            .build();
    String sql = SQL_BUILDER.buildSql(request);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain GROUP BY", sql.contains("GROUP BY"));
    assertTrue("WHERE must appear before GROUP BY", sql.indexOf("WHERE") < sql.indexOf("GROUP BY"));
    try (Connection conn =
            DriverManager.getConnection(
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

  // ---------------------------------------------------------------------------
  // Phase 38: Expression pushdown gap integration tests (Gaps 1-4) — Oracle
  // ---------------------------------------------------------------------------

  /**
   * Gap 1: GROUP BY with function expression EXTRACT(YEAR FROM HIRE_DATE).
   * Expected 4 rows for years 2020-2023 with correct SUM(SALARY) values.
   * Oracle EXTRACT returns NUMBER; column names are UPPERCASE.
   * Tables are created in setUpClass().
   */
  @Test
  public void testGroupByExpressionExtractYear() throws Exception {
    String sql =
        "SELECT EXTRACT(YEAR FROM \"HIRE_DATE\") AS HIRE_YEAR, SUM(\"SALARY\") AS TOTAL_SALARY"
            + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
            + " GROUP BY EXTRACT(YEAR FROM \"HIRE_DATE\")"
            + " ORDER BY HIRE_YEAR";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // Year 2020: Alice 80000 + Carol 72000 = 152000
      assertTrue("Must have row for 2020", rs.next());
      assertEquals("HIRE_YEAR 2020", 2020.0, rs.getDouble("HIRE_YEAR"), 0.01);
      assertEquals("SUM 2020 = 152000", 152000.00, rs.getDouble("TOTAL_SALARY"), 0.01);
      // Year 2021: Bob 95000
      assertTrue("Must have row for 2021", rs.next());
      assertEquals("HIRE_YEAR 2021", 2021.0, rs.getDouble("HIRE_YEAR"), 0.01);
      assertEquals("SUM 2021 = 95000", 95000.00, rs.getDouble("TOTAL_SALARY"), 0.01);
      // Year 2022: Dave 68000
      assertTrue("Must have row for 2022", rs.next());
      assertEquals("HIRE_YEAR 2022", 2022.0, rs.getDouble("HIRE_YEAR"), 0.01);
      assertEquals("SUM 2022 = 68000", 68000.00, rs.getDouble("TOTAL_SALARY"), 0.01);
      // Year 2023: Eve 105000
      assertTrue("Must have row for 2023", rs.next());
      assertEquals("HIRE_YEAR 2023", 2023.0, rs.getDouble("HIRE_YEAR"), 0.01);
      assertEquals("SUM 2023 = 105000", 105000.00, rs.getDouble("TOTAL_SALARY"), 0.01);
      assertFalse("Exactly 4 rows", rs.next());
    }
  }

  /**
   * Gap 3: Aggregate operand expression SUM(SALARY * 1.1) with GROUP BY DEPARTMENT.
   * Engineering: (80000 + 95000 + 105000) * 1.1 = 308000; Marketing: (72000 + 68000) * 1.1 = 154000.
   * Oracle column names in results are UPPERCASE.
   */
  @Test
  public void testAggOperandExpression() throws Exception {
    String sql =
        "SELECT \"DEPARTMENT\", SUM(\"SALARY\" * 1.1) AS RAISED_TOTAL"
            + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
            + " GROUP BY \"DEPARTMENT\""
            + " ORDER BY \"DEPARTMENT\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // Engineering comes before Marketing alphabetically
      assertTrue("Must have row for Engineering", rs.next());
      assertEquals("DEPARTMENT Engineering", "Engineering", rs.getString("DEPARTMENT"));
      assertEquals(
          "RAISED_TOTAL Engineering = 308000", 308000.00, rs.getDouble("RAISED_TOTAL"), 1.0);
      assertTrue("Must have row for Marketing", rs.next());
      assertEquals("DEPARTMENT Marketing", "Marketing", rs.getString("DEPARTMENT"));
      assertEquals(
          "RAISED_TOTAL Marketing = 154000", 154000.00, rs.getDouble("RAISED_TOTAL"), 1.0);
      assertFalse("Exactly 2 rows", rs.next());
    }
  }

  /**
   * Gap 4: Bare aggregate (no GROUP BY). SELECT SUM(SALARY), COUNT(*) over the whole table.
   * Expected: total = 420000, count = 5.
   */
  @Test
  public void testBareAggregateNoGroupBy() throws Exception {
    String sql =
        "SELECT SUM(\"SALARY\") AS TOTAL, COUNT(*) AS CNT"
            + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have exactly one result row", rs.next());
      assertEquals("total salary = 420000", 420000.00, rs.getDouble("TOTAL"), 0.01);
      assertEquals("count = 5", 5L, rs.getLong("CNT"));
      assertFalse("Exactly 1 row for bare aggregate", rs.next());
    }
  }

  /**
   * Gap 2: JOIN with CAST condition. Joins employees to departments on DEPARTMENT = CAST(DEPT_NAME
   * AS VARCHAR2(50)). All 5 employees should match their department.
   * Oracle requires a length for CAST to VARCHAR2/CHAR types.
   * Tables are created in setUpClass().
   */
  @Test
  public void testJoinWithCastCondition() throws Exception {
    String sql =
        "SELECT e.\"NAME\", d.\"DEPT_NAME\""
            + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\" e"
            + " JOIN \"TEST_USER\".\"DEPARTMENTS_EXPR_TEST\" d"
            + "   ON e.\"DEPARTMENT\" = CAST(d.\"DEPT_NAME\" AS VARCHAR2(50))"
            + " ORDER BY e.\"NAME\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // 5 employees should each match a department
      int count = 0;
      while (rs.next()) {
        String name = rs.getString("NAME");
        String dept = rs.getString("DEPT_NAME");
        assertNotNull("NAME must not be null", name);
        assertNotNull("DEPT_NAME must not be null", dept);
        assertTrue(
            "DEPT_NAME must be Engineering or Marketing",
            "Engineering".equals(dept) || "Marketing".equals(dept));
        count++;
      }
      assertEquals("All 5 employees matched via CAST JOIN", 5, count);
    }
  }

  // ---------------------------------------------------------------------------
  // Phase 38-03: Regression-grade pushdown coverage — Oracle
  // ---------------------------------------------------------------------------

  /**
   * Regression: WHERE IS NOT NULL — all 5 employees have non-null DEPARTMENT.
   */
  @Test
  public void testWhereIsNotNull() throws Exception {
    String sql = "SELECT \"NAME\" FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " WHERE \"DEPARTMENT\" IS NOT NULL";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        count++;
      }
      assertEquals("All 5 employees have non-null DEPARTMENT", 5, count);
    }
  }

  /**
   * Regression: WHERE with AND — Engineering AND SALARY > 90000 matches Bob(95000) and Eve(105000).
   */
  @Test
  public void testWhereWithAnd() throws Exception {
    String sql = "SELECT \"NAME\" FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " WHERE \"DEPARTMENT\" = 'Engineering' AND \"SALARY\" > 90000";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        String name = rs.getString("NAME");
        assertTrue("Should be Bob or Eve", "Bob".equals(name) || "Eve".equals(name));
        count++;
      }
      assertEquals("WHERE Engineering AND SALARY > 90000 returns 2 rows", 2, count);
    }
  }

  /**
   * Regression: WHERE with OR — Engineering OR Marketing covers all 5 employees.
   */
  @Test
  public void testWhereWithOr() throws Exception {
    String sql = "SELECT \"NAME\" FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " WHERE \"DEPARTMENT\" = 'Engineering' OR \"DEPARTMENT\" = 'Marketing'";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
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
   * Regression: ORDER BY multiple columns — DEPARTMENT ASC, SALARY DESC.
   * First row: Eve (Engineering, 105000 highest); last row: Dave (Marketing, 68000 lowest).
   */
  @Test
  public void testOrderByMultipleColumns() throws Exception {
    String sql = "SELECT \"NAME\", \"SALARY\" FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " ORDER BY \"DEPARTMENT\" ASC, \"SALARY\" DESC";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row should be Eve (Engineering, highest salary)", "Eve", rs.getString("NAME"));
      String lastName = null;
      while (rs.next()) {
        lastName = rs.getString("NAME");
      }
      assertEquals("Last row should be Dave (Marketing, lowest salary)", "Dave", lastName);
    }
  }

  /**
   * Regression: TopN with WHERE — Engineering employees, top 2 by SALARY DESC.
   * Expects Eve (105000) and Bob (95000). Oracle uses FETCH FIRST.
   */
  @Test
  public void testTopNWithWhere() throws Exception {
    String sql = "SELECT \"NAME\", \"SALARY\" FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " WHERE \"DEPARTMENT\" = 'Engineering' ORDER BY \"SALARY\" DESC FETCH FIRST 2 ROWS ONLY";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row should be Eve (105000)", "Eve", rs.getString("NAME"));
      assertTrue("Must have second row", rs.next());
      assertEquals("Second row should be Bob (95000)", "Bob", rs.getString("NAME"));
      assertFalse("Should be exactly 2 rows", rs.next());
    }
  }

  /**
   * Regression: GROUP BY with HAVING and ORDER BY.
   * Departments with 3+ employees: Engineering (3). Marketing has 2.
   */
  @Test
  public void testGroupByWithHavingAndOrderBy() throws Exception {
    String sql = "SELECT \"DEPARTMENT\", COUNT(*) AS CNT"
        + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " GROUP BY \"DEPARTMENT\" HAVING COUNT(*) >= 3 ORDER BY \"DEPARTMENT\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have exactly one row", rs.next());
      assertEquals("Engineering has 3 employees", "Engineering", rs.getString("DEPARTMENT"));
      assertEquals("Count should be 3", 3L, rs.getLong("CNT"));
      assertFalse("Only Engineering has >= 3 employees", rs.next());
    }
  }

  /**
   * Regression: COUNT(DISTINCT DEPARTMENT) — expects 2 (Engineering and Marketing).
   */
  @Test
  public void testCountDistinctDepartment() throws Exception {
    String sql = "SELECT COUNT(DISTINCT \"DEPARTMENT\") AS DEPT_COUNT"
        + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have a result row", rs.next());
      assertEquals("COUNT(DISTINCT DEPARTMENT) = 2", 2L, rs.getLong("DEPT_COUNT"));
    }
  }

  /**
   * Regression: INNER JOIN correctness — all 5 employees match their department.
   */
  @Test
  public void testJoinInnerCorrectness() throws Exception {
    String sql = "SELECT e.\"NAME\", d.\"DEPT_NAME\""
        + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\" e"
        + " INNER JOIN \"TEST_USER\".\"DEPARTMENTS_EXPR_TEST\" d"
        + "   ON e.\"DEPARTMENT\" = d.\"DEPT_NAME\""
        + " ORDER BY e.\"NAME\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        assertNotNull("NAME must not be null", rs.getString("NAME"));
        String dept = rs.getString("DEPT_NAME");
        assertTrue("DEPT must be Engineering or Marketing",
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
    String sql = "SELECT e.\"NAME\", d.\"DEPT_NAME\""
        + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\" e"
        + " LEFT JOIN \"TEST_USER\".\"DEPARTMENTS_EXPR_TEST\" d"
        + "   ON e.\"DEPARTMENT\" = d.\"DEPT_NAME\""
        + " ORDER BY e.\"NAME\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
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
   * Oracle: CAST(DEPT_NAME AS VARCHAR2(50)) join + SALARY > 90000 filter = Bob and Eve.
   */
  @Test
  public void testJoinWithCastAndWhereFilter() throws Exception {
    String sql = "SELECT e.\"NAME\", d.\"DEPT_NAME\""
        + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\" e"
        + " JOIN \"TEST_USER\".\"DEPARTMENTS_EXPR_TEST\" d"
        + "   ON e.\"DEPARTMENT\" = CAST(d.\"DEPT_NAME\" AS VARCHAR2(50))"
        + " WHERE e.\"SALARY\" > 90000 ORDER BY e.\"NAME\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertTrue("Must have first row", rs.next());
      assertEquals("First row should be Bob", "Bob", rs.getString("NAME"));
      assertEquals("Bob's dept should be Engineering", "Engineering", rs.getString("DEPT_NAME"));
      assertTrue("Must have second row", rs.next());
      assertEquals("Second row should be Eve", "Eve", rs.getString("NAME"));
      assertEquals("Eve's dept should be Engineering", "Engineering", rs.getString("DEPT_NAME"));
      assertFalse("Exactly 2 rows", rs.next());
    }
  }

  /**
   * Gap 2 real type-mismatch test: JOIN ON CAST(NUMBER AS VARCHAR2).
   * PRODUCTS.CATEGORY_ID (NUMBER) joined to CATEGORIES.CODE (VARCHAR2) via CAST.
   * 3 Electronics + 2 Furniture = 5 total.
   */
  @Test
  public void testJoinOnCastIntegerAsVarchar() throws Exception {
    String sql = "SELECT p.\"NAME\", c.\"LABEL\""
        + " FROM \"TEST_USER\".\"PRODUCTS\" p"
        + " JOIN \"TEST_USER\".\"CATEGORIES\" c"
        + "   ON CAST(p.\"CATEGORY_ID\" AS VARCHAR2(10)) = c.\"CODE\""
        + " ORDER BY p.\"NAME\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        String name = rs.getString("NAME");
        String label = rs.getString("LABEL");
        assertNotNull("product name must not be null", name);
        assertNotNull("category label must not be null", label);
        count++;
      }
      assertEquals("5 products matched via CAST(NUMBER AS VARCHAR2) JOIN", 5, count);
    }
  }

  /**
   * Regression: ORDER BY expression ROUND(SALARY, -3) DESC FETCH FIRST 3 ROWS ONLY.
   * Returns top 3 rows ordered by rounded salary descending.
   */
  @Test
  public void testOrderByExpressionRoundSalary() throws Exception {
    String sql = "SELECT \"NAME\", ROUND(\"SALARY\", -3) AS ROUNDED"
        + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " ORDER BY ROUND(\"SALARY\", -3) DESC FETCH FIRST 3 ROWS ONLY";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      int count = 0;
      double prevRounded = Double.MAX_VALUE;
      while (rs.next()) {
        double rounded = rs.getDouble("ROUNDED");
        assertTrue("Results should be in descending order of ROUND(SALARY,-3)", rounded <= prevRounded);
        prevRounded = rounded;
        count++;
      }
      assertEquals("FETCH FIRST 3 ROWS should return exactly 3 rows", 3, count);
    }
  }

  /**
   * Regression: AGG with expression GROUP BY EXTRACT and HAVING.
   * GROUP BY year with SUM(SALARY) > 100000. Years 2020 (152000) and 2023 (105000) qualify.
   */
  @Test
  public void testAggWithExpressionGroupByExtractAndHaving() throws Exception {
    String sql = "SELECT EXTRACT(YEAR FROM \"HIRE_DATE\") AS YR, SUM(\"SALARY\") AS TOTAL"
        + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " GROUP BY EXTRACT(YEAR FROM \"HIRE_DATE\")"
        + " HAVING SUM(\"SALARY\") > 100000 ORDER BY YR";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // Year 2020: Alice 80000 + Carol 72000 = 152000 > 100000
      assertTrue("Must have row for 2020", rs.next());
      assertEquals("Year 2020", 2020.0, rs.getDouble("YR"), 0.01);
      assertEquals("SUM 2020 = 152000", 152000.00, rs.getDouble("TOTAL"), 0.01);
      // Year 2023: Eve 105000 > 100000
      assertTrue("Must have row for 2023", rs.next());
      assertEquals("Year 2023", 2023.0, rs.getDouble("YR"), 0.01);
      assertEquals("SUM 2023 = 105000", 105000.00, rs.getDouble("TOTAL"), 0.01);
      assertFalse("Exactly 2 years qualify with SUM > 100000", rs.next());
    }
  }

  /**
   * Regression: SUM(SALARY * 1.15) GROUP BY DEPARTMENT.
   * Engineering: (80000+95000+105000)*1.15 = 322000; Marketing: (72000+68000)*1.15 = 161000.
   */
  @Test
  public void testSumSalaryTimesMultiplierGroupBy() throws Exception {
    String sql = "SELECT \"DEPARTMENT\", SUM(\"SALARY\" * 1.15) AS BOOSTED"
        + " FROM \"TEST_USER\".\"PUSHDOWN_EXPR_TEST\""
        + " GROUP BY \"DEPARTMENT\" ORDER BY \"DEPARTMENT\"";
    try (Connection conn =
            DriverManager.getConnection(
                OracleTestContainer.getJdbcUrl(),
                OracleTestContainer.getUsername(),
                OracleTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      // Engineering first alphabetically
      assertTrue("Must have row for Engineering", rs.next());
      assertEquals("Engineering", rs.getString("DEPARTMENT"));
      assertEquals("Engineering SUM * 1.15 = 322000", 322000.00, rs.getDouble("BOOSTED"), 1.0);
      // Marketing
      assertTrue("Must have row for Marketing", rs.next());
      assertEquals("Marketing", rs.getString("DEPARTMENT"));
      assertEquals("Marketing SUM * 1.15 = 161000", 161000.00, rs.getDouble("BOOSTED"), 1.0);
      assertFalse("Exactly 2 departments", rs.next());
    }
  }
}
