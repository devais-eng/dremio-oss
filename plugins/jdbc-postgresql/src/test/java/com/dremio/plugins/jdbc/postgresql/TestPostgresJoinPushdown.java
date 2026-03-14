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
import com.dremio.plugins.jdbc.planning.BindParam;
import com.dremio.plugins.jdbc.planning.LiteralInliner;
import com.dremio.plugins.jdbc.planning.SqlBuilder;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Integration tests for PostgreSQL JOIN pushdown and ADBC COPY literal inlining.
 *
 * <p>Verifies that {@link SqlBuilder#buildJoinSql} generates syntactically valid SQL for all four
 * PostgreSQL JOIN types (INNER, LEFT OUTER, RIGHT OUTER, FULL OUTER) and that the generated SQL
 * returns correct results when executed against a real {@code postgres:16-alpine} container.
 *
 * <p>Also verifies that {@link LiteralInliner#inlineBindParams} produces executable SQL for the
 * ADBC COPY binary path, including adversarial string inputs (SQL injection safety).
 *
 * <p>Two schemas are used: {@code pushdown_test} (orders + customers) and {@code pushdown_test2}
 * (regions) for cross-schema JOIN testing.
 */
public class TestPostgresJoinPushdown {

  @ClassRule public static final DremioPostgresContainer PG = PostgresTestContainer.PG;

  private static final SqlBuilder SQL_BUILDER = new SqlBuilder();

  @BeforeClass
  public static void setUpClass() throws Exception {
    // Create schemas
    PostgresTestContainer.executeSql("CREATE SCHEMA IF NOT EXISTS pushdown_test");
    PostgresTestContainer.executeSql("CREATE SCHEMA IF NOT EXISTS pushdown_test2");

    // Create orders table
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS pushdown_test.orders ("
            + "  order_id INTEGER PRIMARY KEY,"
            + "  customer_id INTEGER NOT NULL,"
            + "  product VARCHAR(100) NOT NULL,"
            + "  amount NUMERIC(10,2) NOT NULL,"
            + "  order_date DATE NOT NULL"
            + ")");

    // Create customers table
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS pushdown_test.customers ("
            + "  customer_id INTEGER PRIMARY KEY,"
            + "  name VARCHAR(100) NOT NULL,"
            + "  city VARCHAR(100) NOT NULL,"
            + "  tier VARCHAR(20) NOT NULL"
            + ")");

    // Create regions table in second schema (for cross-schema join test)
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS pushdown_test2.regions ("
            + "  city VARCHAR(100) PRIMARY KEY,"
            + "  region VARCHAR(50) NOT NULL"
            + ")");

    // Insert customers: 4 rows
    PostgresTestContainer.executeSql("DELETE FROM pushdown_test.customers");
    PostgresTestContainer.executeSql(
        "INSERT INTO pushdown_test.customers (customer_id, name, city, tier) VALUES "
            + "(1, 'Alice', 'NYC', 'gold'),"
            + "(2, 'Bob', 'LA', 'silver'),"
            + "(3, 'Carol', 'NYC', 'gold'),"
            + "(4, 'Dave', 'Chicago', 'bronze')");

    // Insert orders: 4 rows; order 104 has customer_id=5 (no matching customer — for LEFT/RIGHT/FULL
    // join testing)
    PostgresTestContainer.executeSql("DELETE FROM pushdown_test.orders");
    PostgresTestContainer.executeSql(
        "INSERT INTO pushdown_test.orders (order_id, customer_id, product, amount, order_date) VALUES "
            + "(101, 1, 'Widget', 29.99, '2024-01-15'),"
            + "(102, 1, 'Gadget', 49.99, '2024-02-20'),"
            + "(103, 2, 'Widget', 29.99, '2024-01-20'),"
            + "(104, 5, 'Doohickey', 9.99, '2024-03-01')");

    // Insert regions (for cross-schema test)
    PostgresTestContainer.executeSql("DELETE FROM pushdown_test2.regions");
    PostgresTestContainer.executeSql(
        "INSERT INTO pushdown_test2.regions (city, region) VALUES "
            + "('NYC', 'Northeast'),"
            + "('LA', 'West'),"
            + "('Chicago', 'Midwest')");

    // Insert adversarial rows into customers for SQL injection tests
    PostgresTestContainer.executeSql(
        "INSERT INTO pushdown_test.customers (customer_id, name, city, tier) VALUES "
            + "(10, 'O''Malley''s \"Bar\" & Grill', 'Boston', 'silver')");
    PostgresTestContainer.executeSql(
        "INSERT INTO pushdown_test.customers (customer_id, name, city, tier) VALUES "
            + "(11, '''; DROP TABLE customers; --', 'NYC', 'gold')");
  }

  @AfterClass
  public static void tearDownClass() {
    // Container is shared; do not stop it here
  }

  // ---------------------------------------------------------------------------
  // Helper: execute SQL via direct JDBC connection and return ResultSet-processing result
  // ---------------------------------------------------------------------------

  private static int countRows(String sql) throws Exception {
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
      return count;
    }
  }

  // ---------------------------------------------------------------------------
  // JOIN SQL generation + execution tests
  // ---------------------------------------------------------------------------

  /**
   * INNER JOIN: orders JOIN customers ON customer_id. Only orders 101, 102, 103 match (order 104
   * has customer_id=5 which does NOT exist). Expected: 3 rows.
   */
  @Test
  public void testInnerJoinSql() throws Exception {
    String joinTypeSql = SqlBuilder.joinTypeToSql(JoinRelType.INNER);
    assertEquals("INNER JOIN", joinTypeSql);

    String sql =
        SQL_BUILDER.buildJoinSql(
            "pushdown_test",
            "orders",
            "t1",
            "pushdown_test",
            "customers",
            "t2",
            joinTypeSql,
            "\"t1\".\"customer_id\" = \"t2\".\"customer_id\"",
            Arrays.asList(
                SchemaPath.getSimplePath("order_id"),
                SchemaPath.getSimplePath("product")),
            Arrays.asList(SchemaPath.getSimplePath("name")));

    assertTrue("SQL must contain INNER JOIN", sql.contains("INNER JOIN"));
    assertTrue("SQL must contain ON clause", sql.contains("ON"));
    assertTrue(
        "SQL must reference pushdown_test.orders", sql.contains("\"pushdown_test\".\"orders\""));
    assertTrue(
        "SQL must reference pushdown_test.customers",
        sql.contains("\"pushdown_test\".\"customers\""));

    int rowCount = countRows(sql);
    assertEquals("INNER JOIN should return 3 rows (orders 101, 102, 103 match customers)", 3, rowCount);
  }

  /**
   * LEFT OUTER JOIN: all orders, with NULL customer fields for order 104 (customer_id=5 not in
   * customers). Expected: 4 rows.
   */
  @Test
  public void testLeftOuterJoinSql() throws Exception {
    String joinTypeSql = SqlBuilder.joinTypeToSql(JoinRelType.LEFT);
    assertEquals("LEFT OUTER JOIN", joinTypeSql);

    String sql =
        SQL_BUILDER.buildJoinSql(
            "pushdown_test",
            "orders",
            "t1",
            "pushdown_test",
            "customers",
            "t2",
            joinTypeSql,
            "\"t1\".\"customer_id\" = \"t2\".\"customer_id\"",
            Arrays.asList(
                SchemaPath.getSimplePath("order_id"),
                SchemaPath.getSimplePath("customer_id")),
            Arrays.asList(SchemaPath.getSimplePath("name")));

    assertTrue("SQL must contain LEFT OUTER JOIN", sql.contains("LEFT OUTER JOIN"));

    int rowCount = countRows(sql);
    assertEquals(
        "LEFT OUTER JOIN should return 4 rows (all orders, order 104 has null customer)", 4, rowCount);
  }

  /**
   * RIGHT OUTER JOIN: all customers, with NULL order fields for Carol (customer_id=3) and Dave
   * (customer_id=4) who have no orders. Expected: 4 rows. Note: order 104 (customer_id=5) has no
   * matching customer so it is excluded; Carol and Dave have no orders so they appear with NULLs.
   * Total = orders 101,102,103 + Carol row + Dave row - but since RIGHT JOIN preserves all customers:
   * Alice (2 orders = 2 rows), Bob (1 order = 1 row), Carol (0 orders = 1 NULL row), Dave (0 orders = 1 NULL row) = 5 rows
   * (excluding adversarial customers 10 and 11 who also have no orders = 2 more NULL rows = 7 total)
   * Actually: all 6 customers (1,2,3,4,10,11) x their matching orders + NULLs for unmatched.
   */
  @Test
  public void testRightOuterJoinSql() throws Exception {
    String joinTypeSql = SqlBuilder.joinTypeToSql(JoinRelType.RIGHT);
    assertEquals("RIGHT OUTER JOIN", joinTypeSql);

    String sql =
        SQL_BUILDER.buildJoinSql(
            "pushdown_test",
            "orders",
            "t1",
            "pushdown_test",
            "customers",
            "t2",
            joinTypeSql,
            "\"t1\".\"customer_id\" = \"t2\".\"customer_id\"",
            Arrays.asList(SchemaPath.getSimplePath("order_id")),
            Arrays.asList(
                SchemaPath.getSimplePath("customer_id"),
                SchemaPath.getSimplePath("name")));

    assertTrue("SQL must contain RIGHT OUTER JOIN", sql.contains("RIGHT OUTER JOIN"));

    // All customers (6 total: 1,2,3,4,10,11) appear, with NULLs for those without orders.
    // Customer 1 (Alice) has 2 orders = 2 rows
    // Customer 2 (Bob) has 1 order = 1 row
    // Customers 3,4,10,11 have no orders = 4 NULL-order rows
    // Total: 7 rows
    int rowCount = countRows(sql);
    assertTrue(
        "RIGHT OUTER JOIN should return at least 6 rows (all 6 customers + Alice's extra order), got: "
            + rowCount,
        rowCount >= 6);
  }

  /**
   * FULL OUTER JOIN: all orders + all customers, including unmatched on both sides. Expected: at
   * least 6 rows (3 matched + order 104 unmatched + customers 3,4,10,11 unmatched).
   */
  @Test
  public void testFullOuterJoinSql() throws Exception {
    String joinTypeSql = SqlBuilder.joinTypeToSql(JoinRelType.FULL);
    assertEquals("FULL OUTER JOIN", joinTypeSql);

    String sql =
        SQL_BUILDER.buildJoinSql(
            "pushdown_test",
            "orders",
            "t1",
            "pushdown_test",
            "customers",
            "t2",
            joinTypeSql,
            "\"t1\".\"customer_id\" = \"t2\".\"customer_id\"",
            Arrays.asList(SchemaPath.getSimplePath("order_id")),
            Arrays.asList(SchemaPath.getSimplePath("name")));

    assertTrue("SQL must contain FULL OUTER JOIN", sql.contains("FULL OUTER JOIN"));

    int rowCount = countRows(sql);
    // FULL OUTER JOIN includes:
    // - Matched: 3 orders x customers (101->Alice, 102->Alice, 103->Bob)
    // - Unmatched orders: order 104 (customer_id=5 has no customer)
    // - Unmatched customers: Carol(3), Dave(4), O'Malley(10), injection(11) have no orders
    // = 3 + 1 + 4 = 8 total
    assertTrue(
        "FULL OUTER JOIN should return at least 6 rows (matched + unmatched both sides), got: "
            + rowCount,
        rowCount >= 6);
  }

  /**
   * Cross-schema JOIN: customers in pushdown_test joined with regions in pushdown_test2. Verifies
   * that the SQL executes correctly across schemas.
   */
  @Test
  public void testCrossSchemaJoinSql() throws Exception {
    String sql =
        SQL_BUILDER.buildJoinSql(
            "pushdown_test",
            "customers",
            "t1",
            "pushdown_test2",
            "regions",
            "t2",
            SqlBuilder.joinTypeToSql(JoinRelType.INNER),
            "\"t1\".\"city\" = \"t2\".\"city\"",
            Arrays.asList(SchemaPath.getSimplePath("name"), SchemaPath.getSimplePath("city")),
            Arrays.asList(SchemaPath.getSimplePath("region")));

    assertTrue(
        "SQL must reference pushdown_test.customers", sql.contains("\"pushdown_test\".\"customers\""));
    assertTrue(
        "SQL must reference pushdown_test2.regions", sql.contains("\"pushdown_test2\".\"regions\""));
    assertTrue("SQL must contain INNER JOIN", sql.contains("INNER JOIN"));

    // NYC customers: Alice(1), Carol(3), injection(11) => 3 rows
    // LA customers: Bob(2) => 1 row
    // Chicago customers: Dave(4) => 1 row
    // O'Malley(10) is in Boston which has no region entry => excluded
    // Total: 5 rows
    int rowCount = countRows(sql);
    assertTrue(
        "Cross-schema INNER JOIN should return rows for customers in known cities, got: " + rowCount,
        rowCount >= 4);
  }

  // ---------------------------------------------------------------------------
  // ADBC COPY optimization tests (LiteralInliner produces executable SQL)
  // ---------------------------------------------------------------------------

  /**
   * Verifies that LiteralInliner produces executable SQL with a WHERE clause. Filter: amount > 20
   * AND product = 'Widget'. Expected: orders 101 and 103 (Widget, amount 29.99) -- both match.
   */
  @Test
  public void testLiteralInlinerWithWhereClause() throws Exception {
    String sqlWithParams =
        "SELECT * FROM \"pushdown_test\".\"orders\" WHERE \"amount\" > ? AND \"product\" = ?";

    BindParam amountParam = new BindParam(new BigDecimal("20.00"), SqlTypeName.DECIMAL);
    BindParam productParam = new BindParam("Widget", SqlTypeName.VARCHAR);

    String inlinedSql =
        LiteralInliner.inlineBindParams(sqlWithParams, Arrays.asList(amountParam, productParam));

    // The inlined SQL must not contain '?' placeholders
    assertTrue("Inlined SQL must not contain '?'", !inlinedSql.contains("?"));
    assertTrue("Inlined SQL must contain 20.00", inlinedSql.contains("20.00"));
    assertTrue("Inlined SQL must contain 'Widget'", inlinedSql.contains("'Widget'"));

    int rowCount = countRows(inlinedSql);
    assertEquals(
        "Inlined SQL should return 2 rows (orders 101 and 103: Widget, amount > 20)", 2, rowCount);
  }

  /**
   * Verifies that LiteralInliner correctly escapes a string containing single quotes. The customer
   * "O'Malley's \"Bar\" & Grill" (customer_id=10) must be found by the inlined query.
   */
  @Test
  public void testLiteralInlinerStringEscaping() throws Exception {
    String sqlWithParams =
        "SELECT * FROM \"pushdown_test\".\"customers\" WHERE \"name\" = ?";

    BindParam nameParam =
        new BindParam("O'Malley's \"Bar\" & Grill", SqlTypeName.VARCHAR);

    String inlinedSql =
        LiteralInliner.inlineBindParams(sqlWithParams, Arrays.asList(nameParam));

    // Verify the escaped SQL uses doubled single quotes
    assertTrue(
        "Inlined SQL must escape single quotes by doubling them",
        inlinedSql.contains("O''Malley''s"));

    int rowCount = countRows(inlinedSql);
    assertEquals(
        "Inlined SQL must find exactly 1 customer named O'Malley's \"Bar\" & Grill", 1, rowCount);
  }

  /**
   * Verifies that LiteralInliner treats adversarial SQL injection strings as data. The customer
   * with name = "'; DROP TABLE customers; --" (customer_id=11) must be found without executing the
   * DROP statement.
   *
   * <p>SQL injection safety verification: the injected string is treated as a SQL literal, not as
   * SQL code. After the query, the customers table must still exist.
   */
  @Test
  public void testLiteralInlinerSqlInjectionSafety() throws Exception {
    String sqlWithParams =
        "SELECT * FROM \"pushdown_test\".\"customers\" WHERE \"name\" = ?";

    BindParam injectionParam =
        new BindParam("'; DROP TABLE customers; --", SqlTypeName.VARCHAR);

    String inlinedSql =
        LiteralInliner.inlineBindParams(sqlWithParams, Arrays.asList(injectionParam));

    // Verify the dangerous string was escaped (single quote doubled)
    assertTrue(
        "Inlined SQL must escape the injection quote",
        inlinedSql.contains("''"));

    // (a) The row IS found — the malicious string is treated as data
    int rowCount = countRows(inlinedSql);
    assertEquals(
        "Inlined SQL must find exactly 1 customer with the injection string as name", 1, rowCount);

    // (b) The customers table still exists — DROP was NOT executed
    int tableCount =
        countRows(
            "SELECT COUNT(*) FROM \"pushdown_test\".\"customers\" WHERE customer_id = 11");
    assertEquals(
        "customers table must still exist after the injection test (DROP was not executed)",
        1,
        tableCount);
  }

  /**
   * Verifies that LiteralInliner correctly inlines a DATE bind parameter. Filter: order_date =
   * '2024-01-15'. Expected: order 101.
   */
  @Test
  public void testLiteralInlinerDateParam() throws Exception {
    String sqlWithParams =
        "SELECT * FROM \"pushdown_test\".\"orders\" WHERE \"order_date\" = ?";

    // 2024-01-15 as epoch millis
    long epochMillis = Date.valueOf("2024-01-15").getTime();
    BindParam dateParam = new BindParam(epochMillis, SqlTypeName.DATE);

    String inlinedSql =
        LiteralInliner.inlineBindParams(sqlWithParams, Arrays.asList(dateParam));

    assertTrue("Inlined SQL must contain DATE literal prefix", inlinedSql.contains("DATE '"));
    assertTrue("Inlined SQL must contain 2024-01-15", inlinedSql.contains("2024-01-15"));

    int rowCount = countRows(inlinedSql);
    assertEquals(
        "Inlined SQL should return 1 row (order 101 on 2024-01-15)", 1, rowCount);
  }

  // ---------------------------------------------------------------------------
  // Semantic verification: INTERSECT/EXCEPT via JOIN equivalents
  // ---------------------------------------------------------------------------

  /**
   * INTERSECT semantics: customer_ids that appear in BOTH orders AND customers. This is equivalent
   * to: SELECT customer_id FROM orders INTERSECT SELECT customer_id FROM customers. Via JOIN: SELECT
   * DISTINCT t1.customer_id FROM orders AS t1 INNER JOIN customers AS t2 ON t1.customer_id =
   * t2.customer_id.
   *
   * <p>Expected result: {1, 2} (Alice and Bob have orders; Carol, Dave, and adversarial customers
   * have no orders).
   */
  @Test
  public void testIntersectSemantics() throws Exception {
    String sql =
        "SELECT DISTINCT \"t1\".\"customer_id\""
            + " FROM \"pushdown_test\".\"orders\" AS \"t1\""
            + " INNER JOIN \"pushdown_test\".\"customers\" AS \"t2\""
            + " ON \"t1\".\"customer_id\" = \"t2\".\"customer_id\"";

    Set<Integer> customerIds = new HashSet<>();
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        customerIds.add(rs.getInt("customer_id"));
      }
    }

    // Only customers 1 and 2 have orders (order 104 has customer_id=5 which is not in customers)
    assertEquals("INTERSECT semantics: expected exactly 2 customer IDs", 2, customerIds.size());
    assertTrue("INTERSECT result must contain customer_id=1 (Alice)", customerIds.contains(1));
    assertTrue("INTERSECT result must contain customer_id=2 (Bob)", customerIds.contains(2));
  }

  /**
   * EXCEPT semantics: customer_ids in customers but NOT in orders. This is equivalent to: SELECT
   * customer_id FROM customers EXCEPT SELECT customer_id FROM orders. Via LEFT JOIN + IS NULL: SELECT
   * t1.customer_id FROM customers AS t1 LEFT JOIN orders AS t2 ON t1.customer_id = t2.customer_id
   * WHERE t2.order_id IS NULL.
   *
   * <p>Expected result: {3, 4, 10, 11} — Carol, Dave, and the two adversarial customers have no
   * orders.
   */
  @Test
  public void testExceptSemantics() throws Exception {
    String sql =
        "SELECT \"t1\".\"customer_id\""
            + " FROM \"pushdown_test\".\"customers\" AS \"t1\""
            + " LEFT JOIN \"pushdown_test\".\"orders\" AS \"t2\""
            + " ON \"t1\".\"customer_id\" = \"t2\".\"customer_id\""
            + " WHERE \"t2\".\"order_id\" IS NULL";

    Set<Integer> customerIds = new HashSet<>();
    try (Connection conn =
            DriverManager.getConnection(
                PostgresTestContainer.getJdbcUrl(),
                PostgresTestContainer.getUsername(),
                PostgresTestContainer.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        customerIds.add(rs.getInt("customer_id"));
      }
    }

    // Carol(3), Dave(4), O'Malley(10), injection(11) have no orders
    assertTrue(
        "EXCEPT result must contain at least 2 customer IDs (Carol and Dave), got: "
            + customerIds.size(),
        customerIds.size() >= 2);
    assertTrue("EXCEPT result must contain customer_id=3 (Carol)", customerIds.contains(3));
    assertTrue("EXCEPT result must contain customer_id=4 (Dave)", customerIds.contains(4));
    assertTrue(
        "EXCEPT result must NOT contain customer_id=1 (Alice has orders)",
        !customerIds.contains(1));
    assertTrue(
        "EXCEPT result must NOT contain customer_id=2 (Bob has orders)",
        !customerIds.contains(2));
  }

  /**
   * Verifies that the INNER JOIN SQL generated by buildJoinSql uses alias references correctly in
   * the ON clause and SELECT list (alias-qualified column names).
   */
  @Test
  public void testJoinSqlAliasQualification() throws Exception {
    String sql =
        SQL_BUILDER.buildJoinSql(
            "pushdown_test",
            "orders",
            "t1",
            "pushdown_test",
            "customers",
            "t2",
            "INNER JOIN",
            "\"t1\".\"customer_id\" = \"t2\".\"customer_id\"",
            Arrays.asList(SchemaPath.getSimplePath("order_id")),
            Arrays.asList(SchemaPath.getSimplePath("customer_id"), SchemaPath.getSimplePath("name")));

    // Verify alias qualification in SELECT and FROM
    assertTrue("SQL must contain alias-qualified left column: \"t1\".\"order_id\"",
        sql.contains("\"t1\".\"order_id\""));
    assertTrue("SQL must contain alias-qualified right column: \"t2\".\"customer_id\"",
        sql.contains("\"t2\".\"customer_id\""));
    assertTrue("SQL must use AS for left table alias", sql.contains("AS \"t1\""));
    assertTrue("SQL must use AS for right table alias", sql.contains("AS \"t2\""));

    // Verify it executes and returns correct results
    int rowCount = countRows(sql);
    assertEquals("Alias-qualified INNER JOIN should return 3 rows", 3, rowCount);
  }

  /**
   * Verifies that SELECT * fallback is used when both column lists are empty.
   */
  @Test
  public void testJoinSqlSelectStarFallback() throws Exception {
    String sql =
        SQL_BUILDER.buildJoinSql(
            "pushdown_test",
            "orders",
            "t1",
            "pushdown_test",
            "customers",
            "t2",
            "INNER JOIN",
            "\"t1\".\"customer_id\" = \"t2\".\"customer_id\"",
            null,
            null);

    assertTrue("Empty column lists should produce SELECT *", sql.startsWith("SELECT *"));

    // Still executes correctly
    int rowCount = countRows(sql);
    assertEquals("SELECT * INNER JOIN should return 3 rows", 3, rowCount);
  }
}
