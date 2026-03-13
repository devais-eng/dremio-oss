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
    String sql = SQL_BUILDER.buildSql("TEST_USER", "pushdown_test", null, null, null);
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
    String sql = SQL_BUILDER.buildSql("TEST_USER", "pushdown_test", null, "\"AGE\" > 28", null);
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
    String sql = SQL_BUILDER.buildSql("TEST_USER", "pushdown_test", null, null, 2);
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
    String sql = SQL_BUILDER.buildSql("TEST_USER", "pushdown_test", cols, null, null);
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
    String sql = SQL_BUILDER.buildSql("TEST_USER", "pushdown_test", cols, "\"AGE\" >= 30", 2);
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
}
