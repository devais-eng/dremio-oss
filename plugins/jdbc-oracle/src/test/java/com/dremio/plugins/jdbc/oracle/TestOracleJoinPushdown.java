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
import static org.junit.Assert.assertTrue;

import com.dremio.common.expression.SchemaPath;
import com.dremio.plugins.jdbc.planning.SqlBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import org.apache.calcite.rel.core.JoinRelType;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Integration tests for Oracle JOIN pushdown.
 *
 * <p>Verifies that {@link OracleSqlBuilder} (or base {@link SqlBuilder}) generates syntactically
 * valid SQL for all four Oracle JOIN types (INNER, LEFT OUTER, RIGHT OUTER, FULL OUTER) and that
 * the generated SQL returns correct results when executed against a real Oracle XE container.
 *
 * <p>Oracle identifiers are UPPERCASE by default (DatabaseMetaData returns uppercase names). All
 * schema/table/column names in these tests use uppercase. Table aliases use Oracle syntax (no AS
 * keyword for table aliases in FROM clause if the OracleSqlBuilder override is present).
 *
 * <p>Oracle uses {@code INSERT ALL ... SELECT 1 FROM DUAL} for multi-row inserts.
 */
public class TestOracleJoinPushdown {

  @ClassRule public static final DremioOracleContainer ORACLE = OracleTestContainer.ORACLE;

  /**
   * Use OracleSqlBuilder for Oracle-specific JOIN SQL (no AS keyword for table aliases).
   *
   * <p>Oracle 12c and earlier do NOT support the AS keyword for table aliases in FROM clause. While
   * Oracle 21c may accept AS, using OracleSqlBuilder ensures compatibility across all supported
   * versions and matches the SQL that the Dremio planner will actually send to Oracle.
   */
  private static final OracleSqlBuilder SQL_BUILDER = new OracleSqlBuilder();

  @BeforeClass
  public static void setUpClass() throws Exception {
    // Drop tables if they exist (ignore errors)
    tryExecuteSql("DROP TABLE TEST_USER.ORDERS");
    tryExecuteSql("DROP TABLE TEST_USER.CUSTOMERS");

    // Create ORDERS table
    OracleTestContainer.executeSql(
        "CREATE TABLE TEST_USER.ORDERS ("
            + "  ORDER_ID NUMBER(10) PRIMARY KEY,"
            + "  CUSTOMER_ID NUMBER(10) NOT NULL,"
            + "  PRODUCT VARCHAR2(100) NOT NULL,"
            + "  AMOUNT NUMBER(10,2) NOT NULL,"
            + "  ORDER_DATE DATE NOT NULL"
            + ")");

    // Create CUSTOMERS table
    OracleTestContainer.executeSql(
        "CREATE TABLE TEST_USER.CUSTOMERS ("
            + "  CUSTOMER_ID NUMBER(10) PRIMARY KEY,"
            + "  NAME VARCHAR2(100) NOT NULL,"
            + "  CITY VARCHAR2(100) NOT NULL,"
            + "  TIER VARCHAR2(20) NOT NULL"
            + ")");

    // Insert customers using Oracle INSERT ALL syntax
    OracleTestContainer.executeSql(
        "INSERT ALL\n"
            + "  INTO TEST_USER.CUSTOMERS (CUSTOMER_ID, NAME, CITY, TIER) VALUES (1, 'Alice', 'NYC', 'gold')\n"
            + "  INTO TEST_USER.CUSTOMERS (CUSTOMER_ID, NAME, CITY, TIER) VALUES (2, 'Bob', 'LA', 'silver')\n"
            + "  INTO TEST_USER.CUSTOMERS (CUSTOMER_ID, NAME, CITY, TIER) VALUES (3, 'Carol', 'NYC', 'gold')\n"
            + "  INTO TEST_USER.CUSTOMERS (CUSTOMER_ID, NAME, CITY, TIER) VALUES (4, 'Dave', 'Chicago', 'bronze')\n"
            + "SELECT 1 FROM DUAL");
    OracleTestContainer.executeSql("COMMIT");

    // Insert orders using Oracle INSERT ALL syntax
    // Note: Oracle uses DATE 'YYYY-MM-DD' literal syntax
    OracleTestContainer.executeSql(
        "INSERT ALL\n"
            + "  INTO TEST_USER.ORDERS (ORDER_ID, CUSTOMER_ID, PRODUCT, AMOUNT, ORDER_DATE)"
            + "  VALUES (101, 1, 'Widget', 29.99, DATE '2024-01-15')\n"
            + "  INTO TEST_USER.ORDERS (ORDER_ID, CUSTOMER_ID, PRODUCT, AMOUNT, ORDER_DATE)"
            + "  VALUES (102, 1, 'Gadget', 49.99, DATE '2024-02-20')\n"
            + "  INTO TEST_USER.ORDERS (ORDER_ID, CUSTOMER_ID, PRODUCT, AMOUNT, ORDER_DATE)"
            + "  VALUES (103, 2, 'Widget', 29.99, DATE '2024-01-20')\n"
            + "  INTO TEST_USER.ORDERS (ORDER_ID, CUSTOMER_ID, PRODUCT, AMOUNT, ORDER_DATE)"
            + "  VALUES (104, 5, 'Doohickey', 9.99, DATE '2024-03-01')\n"
            + "SELECT 1 FROM DUAL");
    OracleTestContainer.executeSql("COMMIT");
  }

  @AfterClass
  public static void tearDownClass() {
    tryExecuteSql("DROP TABLE TEST_USER.ORDERS");
    tryExecuteSql("DROP TABLE TEST_USER.CUSTOMERS");
  }

  private static void tryExecuteSql(String sql) {
    try {
      OracleTestContainer.executeSql(sql);
    } catch (Exception e) {
      // Ignore errors (e.g., table does not exist during drop)
    }
  }

  private static int countRows(String sql) throws Exception {
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
      return count;
    }
  }

  // ---------------------------------------------------------------------------
  // JOIN SQL generation + execution tests
  // ---------------------------------------------------------------------------

  /**
   * INNER JOIN: ORDERS JOIN CUSTOMERS ON CUSTOMER_ID. Oracle identifiers are uppercase. Only orders
   * 101, 102, 103 match (order 104 has CUSTOMER_ID=5 which does NOT exist in CUSTOMERS). Expected:
   * 3 rows.
   *
   * <p>The OracleSqlBuilder.buildJoinSql() omits the AS keyword for table aliases (Oracle 12c
   * compatibility): FROM "TEST_USER"."ORDERS" "t1" INNER JOIN "TEST_USER"."CUSTOMERS" "t2"
   */
  @Test
  public void testInnerJoinSql() throws Exception {
    String joinTypeSql = SqlBuilder.joinTypeToSql(JoinRelType.INNER);
    assertEquals("INNER JOIN", joinTypeSql);

    String sql =
        SQL_BUILDER.buildJoinSql(
            "TEST_USER",
            "ORDERS",
            "t1",
            "TEST_USER",
            "CUSTOMERS",
            "t2",
            joinTypeSql,
            "\"t1\".\"CUSTOMER_ID\" = \"t2\".\"CUSTOMER_ID\"",
            Arrays.asList(
                SchemaPath.getSimplePath("ORDER_ID"),
                SchemaPath.getSimplePath("PRODUCT")),
            Arrays.asList(SchemaPath.getSimplePath("NAME")));

    assertTrue("SQL must contain INNER JOIN", sql.contains("INNER JOIN"));
    assertTrue("SQL must contain ON clause", sql.contains("ON"));
    assertTrue("SQL must reference TEST_USER.ORDERS", sql.contains("\"TEST_USER\".\"ORDERS\""));
    assertTrue("SQL must reference TEST_USER.CUSTOMERS", sql.contains("\"TEST_USER\".\"CUSTOMERS\""));

    int rowCount = countRows(sql);
    assertEquals(
        "INNER JOIN should return 3 rows (orders 101, 102, 103 match customers)", 3, rowCount);
  }

  /**
   * LEFT OUTER JOIN: all orders, with NULL customer fields for order 104 (CUSTOMER_ID=5 not in
   * CUSTOMERS). Expected: 4 rows.
   */
  @Test
  public void testLeftOuterJoinSql() throws Exception {
    String sql =
        SQL_BUILDER.buildJoinSql(
            "TEST_USER",
            "ORDERS",
            "t1",
            "TEST_USER",
            "CUSTOMERS",
            "t2",
            SqlBuilder.joinTypeToSql(JoinRelType.LEFT),
            "\"t1\".\"CUSTOMER_ID\" = \"t2\".\"CUSTOMER_ID\"",
            Arrays.asList(
                SchemaPath.getSimplePath("ORDER_ID"),
                SchemaPath.getSimplePath("CUSTOMER_ID")),
            Arrays.asList(SchemaPath.getSimplePath("NAME")));

    assertTrue("SQL must contain LEFT OUTER JOIN", sql.contains("LEFT OUTER JOIN"));

    int rowCount = countRows(sql);
    assertEquals(
        "LEFT OUTER JOIN should return 4 rows (all orders, order 104 has null customer)", 4, rowCount);
  }

  /**
   * RIGHT OUTER JOIN: all customers appear, with NULL order fields for customers who have no
   * orders. Customer 1 (Alice) has 2 orders; customer 2 (Bob) has 1 order; customers 3 and 4 have
   * no orders. Expected: at least 4 rows.
   */
  @Test
  public void testRightOuterJoinSql() throws Exception {
    String sql =
        SQL_BUILDER.buildJoinSql(
            "TEST_USER",
            "ORDERS",
            "t1",
            "TEST_USER",
            "CUSTOMERS",
            "t2",
            SqlBuilder.joinTypeToSql(JoinRelType.RIGHT),
            "\"t1\".\"CUSTOMER_ID\" = \"t2\".\"CUSTOMER_ID\"",
            Arrays.asList(SchemaPath.getSimplePath("ORDER_ID")),
            Arrays.asList(
                SchemaPath.getSimplePath("CUSTOMER_ID"),
                SchemaPath.getSimplePath("NAME")));

    assertTrue("SQL must contain RIGHT OUTER JOIN", sql.contains("RIGHT OUTER JOIN"));

    // All customers (4: Alice, Bob, Carol, Dave) appear:
    // Alice => 2 rows, Bob => 1 row, Carol => 1 NULL-order row, Dave => 1 NULL-order row = 5 rows
    int rowCount = countRows(sql);
    assertTrue(
        "RIGHT OUTER JOIN should return at least 4 rows (all 4 customers), got: " + rowCount,
        rowCount >= 4);
  }

  /**
   * FULL OUTER JOIN: all rows from both tables, with NULLs for unmatched rows on both sides.
   * Oracle supports FULL OUTER JOIN natively. Expected: at least 6 rows.
   */
  @Test
  public void testFullOuterJoinSql() throws Exception {
    String sql =
        SQL_BUILDER.buildJoinSql(
            "TEST_USER",
            "ORDERS",
            "t1",
            "TEST_USER",
            "CUSTOMERS",
            "t2",
            SqlBuilder.joinTypeToSql(JoinRelType.FULL),
            "\"t1\".\"CUSTOMER_ID\" = \"t2\".\"CUSTOMER_ID\"",
            Arrays.asList(SchemaPath.getSimplePath("ORDER_ID")),
            Arrays.asList(SchemaPath.getSimplePath("NAME")));

    assertTrue("SQL must contain FULL OUTER JOIN", sql.contains("FULL OUTER JOIN"));

    // Matched: 3 rows (orders 101, 102, 103)
    // Unmatched orders: 1 row (order 104, no matching customer)
    // Unmatched customers: 2 rows (Carol, Dave have no orders)
    // Total: 6 rows
    int rowCount = countRows(sql);
    assertTrue(
        "FULL OUTER JOIN should return at least 6 rows (matched + unmatched both sides), got: "
            + rowCount,
        rowCount >= 6);
  }

  /**
   * Verifies that Oracle JOIN SQL uses uppercase identifiers wrapped in double quotes. This matches
   * the identifier format returned by Oracle's DatabaseMetaData (all uppercase).
   */
  @Test
  public void testJoinWithOracleUppercaseIdentifiers() throws Exception {
    String sql =
        SQL_BUILDER.buildJoinSql(
            "TEST_USER",
            "ORDERS",
            "t1",
            "TEST_USER",
            "CUSTOMERS",
            "t2",
            "INNER JOIN",
            "\"t1\".\"CUSTOMER_ID\" = \"t2\".\"CUSTOMER_ID\"",
            Arrays.asList(SchemaPath.getSimplePath("ORDER_ID")),
            Arrays.asList(SchemaPath.getSimplePath("NAME")));

    // All identifiers must be uppercase and double-quoted
    assertTrue("SQL must contain uppercase double-quoted schema: \"TEST_USER\"",
        sql.contains("\"TEST_USER\""));
    assertTrue("SQL must contain uppercase double-quoted table: \"ORDERS\"",
        sql.contains("\"ORDERS\""));
    assertTrue("SQL must contain uppercase double-quoted table: \"CUSTOMERS\"",
        sql.contains("\"CUSTOMERS\""));
    assertTrue("SQL must contain uppercase double-quoted column: \"ORDER_ID\"",
        sql.contains("\"ORDER_ID\""));
    assertTrue("SQL must contain uppercase double-quoted column: \"NAME\"",
        sql.contains("\"NAME\""));
    assertTrue("SQL must contain uppercase double-quoted column: \"CUSTOMER_ID\"",
        sql.contains("\"CUSTOMER_ID\""));

    // Verify it actually executes (uppercase identifiers are valid Oracle SQL)
    int rowCount = countRows(sql);
    assertEquals("Uppercase identifier INNER JOIN should return 3 rows", 3, rowCount);
  }
}
