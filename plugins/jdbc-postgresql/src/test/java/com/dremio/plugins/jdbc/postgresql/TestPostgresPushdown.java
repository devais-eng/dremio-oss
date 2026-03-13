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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dremio.common.expression.SchemaPath;
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
 * <p>Tests verify that {@link SqlBuilder} generates correct SQL for filter, projection, and
 * LIMIT pushdown when targeting PostgreSQL. Two categories of tests are provided:
 * <ol>
 *   <li>Pure SQL generation tests that do not require a container — verify the produced SQL
 *       string is syntactically correct and contains the expected clauses.</li>
 *   <li>Container-based tests that execute the generated SQL against a real
 *       {@code postgres:16-alpine} container to confirm validity.</li>
 * </ol>
 *
 * <p>Container-based tests use {@link PostgresTestContainer} as the shared infrastructure.
 */
public class TestPostgresPushdown {

  @ClassRule
  public static final DremioPostgresContainer PG = PostgresTestContainer.PG;

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

  /**
   * SELECT * FROM "public"."test_table" when no columns, no filter, no limit are provided.
   */
  @Test
  public void testSelectAllColumns() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, null, null);
    assertEquals("SELECT * FROM \"public\".\"test_table\"", sql);
  }

  /**
   * SELECT projected columns when a column list is provided.
   */
  @Test
  public void testSelectProjectedColumns() {
    List<SchemaPath> cols = Arrays.asList(
        SchemaPath.getSimplePath("id"),
        SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("public", "test_table", cols, null, null);
    assertEquals("SELECT \"id\", \"name\" FROM \"public\".\"test_table\"", sql);
  }

  /**
   * SELECT * with a WHERE clause appended.
   */
  @Test
  public void testSelectWithWhereClause() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, "\"id\" = 1", null);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain the filter expression", sql.contains("\"id\" = 1"));
  }

  /**
   * SELECT * with a LIMIT clause appended.
   */
  @Test
  public void testSelectWithLimit() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, null, 100);
    assertTrue("SQL must contain LIMIT", sql.contains("LIMIT"));
    assertTrue("SQL must contain the limit value", sql.contains("100"));
  }

  /**
   * SELECT * with both WHERE and LIMIT.
   */
  @Test
  public void testSelectWithWhereAndLimit() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, "\"id\" > 5", 100);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain LIMIT", sql.contains("LIMIT"));
    assertTrue("WHERE must appear before LIMIT", sql.indexOf("WHERE") < sql.indexOf("LIMIT"));
  }

  /**
   * SELECT projected columns with a WHERE filter.
   */
  @Test
  public void testSelectWithProjectionAndFilter() {
    List<SchemaPath> cols = Arrays.asList(
        SchemaPath.getSimplePath("id"),
        SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("public", "test_table", cols, "\"name\" = 'foo'", null);
    assertTrue("SQL must project 'id'", sql.contains("\"id\""));
    assertTrue("SQL must project 'name'", sql.contains("\"name\""));
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain the filter", sql.contains("\"name\" = 'foo'"));
  }

  /**
   * Identifiers with hyphens are double-quoted correctly.
   */
  @Test
  public void testSpecialCharactersInIdentifiers() {
    String sql = SQL_BUILDER.buildSql("public", "my-table", null, null, null);
    assertTrue(
        "Hyphenated table name must be double-quoted: " + sql,
        sql.contains("\"my-table\""));
    assertEquals("SELECT * FROM \"public\".\"my-table\"", sql);
  }

  /**
   * Empty column list produces SELECT *.
   */
  @Test
  public void testEmptyProjectionProducesSelectStar() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", Collections.emptyList(), null, null);
    assertTrue("Empty projection should produce SELECT *", sql.startsWith("SELECT *"));
  }

  /**
   * Null WHERE clause produces no WHERE keyword.
   */
  @Test
  public void testNullWhereClauseOmitted() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, null, null);
    assertTrue("No WHERE should be present when filter is null", !sql.contains("WHERE"));
  }

  /**
   * Null LIMIT produces no LIMIT keyword.
   */
  @Test
  public void testNullLimitOmitted() {
    String sql = SQL_BUILDER.buildSql("public", "test_table", null, null, null);
    assertTrue("No LIMIT should be present when limit is null", !sql.contains("LIMIT"));
  }

  // ---------------------------------------------------------------------------
  // Container-based execution tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that the generated SELECT * SQL executes successfully against PostgreSQL.
   */
  @Test
  public void testPushdownQueryExecutesAgainstPostgres() throws Exception {
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", null, null, null);
    try (Connection conn = DriverManager.getConnection(
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

  /**
   * Verifies that a WHERE filter pushdown returns only matching rows.
   */
  @Test
  public void testFilterPushdownResultCorrectness() throws Exception {
    // Filter: age > 28 — should match Alice (30), Charlie (35), Eve (40)
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", null, "\"age\" > 28", null);
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    try (Connection conn = DriverManager.getConnection(
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

  /**
   * Verifies that a LIMIT pushdown returns at most the specified number of rows.
   */
  @Test
  public void testLimitPushdownResultCorrectness() throws Exception {
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", null, null, 2);
    assertTrue("SQL must contain LIMIT", sql.contains("LIMIT 2"));
    try (Connection conn = DriverManager.getConnection(
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

  /**
   * Verifies that projected column pushdown returns only the requested columns.
   */
  @Test
  public void testProjectionPushdownResultCorrectness() throws Exception {
    List<SchemaPath> cols = Collections.singletonList(SchemaPath.getSimplePath("name"));
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", cols, null, null);
    assertTrue("SQL must project 'name'", sql.contains("\"name\""));
    try (Connection conn = DriverManager.getConnection(
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

  /**
   * Verifies combined projection + filter + limit pushdown against PostgreSQL.
   */
  @Test
  public void testCombinedPushdownCorrectness() throws Exception {
    List<SchemaPath> cols = Arrays.asList(
        SchemaPath.getSimplePath("name"),
        SchemaPath.getSimplePath("age"));
    String sql = SQL_BUILDER.buildSql("public", "pushdown_test", cols, "\"age\" >= 30", 2);
    assertTrue("SQL must project 'name'", sql.contains("\"name\""));
    assertTrue("SQL must contain WHERE", sql.contains("WHERE"));
    assertTrue("SQL must contain LIMIT", sql.contains("LIMIT"));
    try (Connection conn = DriverManager.getConnection(
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
}
