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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dremio.exec.record.BatchSchema;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import java.sql.Connection;
import java.sql.ResultSet;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Integration tests validating Oracle-to-Arrow type mapping for all Oracle-specific types.
 *
 * <p>Tests run against a real {@code gvenzl/oracle-xe:21-slim} container via TestContainers. Each
 * test validates that {@link OracleSchemaFetcher#getTableSchema} maps columns to the correct Arrow
 * type. Covered types include:
 *
 * <ul>
 *   <li>NUMBER(p,s) → DECIMAL
 *   <li>Bare NUMBER → DOUBLE
 *   <li>FLOAT → DOUBLE (via scale=-127 sentinel)
 *   <li>BINARY_FLOAT → FLOAT4
 *   <li>BINARY_DOUBLE → FLOAT8
 *   <li>VARCHAR2, NVARCHAR2, CHAR → UTF8
 *   <li>CLOB, NCLOB → UTF8
 *   <li>BLOB, RAW → Binary
 *   <li>DATE → Timestamp
 *   <li>TIMESTAMP, TIMESTAMP WITH TIME ZONE → Timestamp
 * </ul>
 *
 * <p>In Oracle, all identifiers are returned as UPPERCASE by DatabaseMetaData. The test user schema
 * is {@code TEST_USER} and the table is {@code TYPE_TEST}.
 */
public class TestOracleTypeMapping {

  @ClassRule public static final DremioOracleContainer ORACLE = OracleTestContainer.ORACLE;

  private static JdbcConnectionPool pool;
  private static OracleSchemaFetcher schemaFetcher;

  @BeforeClass
  public static void setUpClass() throws Exception {
    pool = OracleTestContainer.createPool();
    schemaFetcher = new OracleSchemaFetcher(pool);

    // Create the type_test table with all Oracle-specific types
    OracleTestContainer.executeSql(
        "CREATE TABLE type_test (\n"
            + "  id NUMBER(10) NOT NULL,\n"
            + "  col_number_ps NUMBER(10,2),\n"
            + "  col_number_bare NUMBER,\n"
            + "  col_float FLOAT,\n"
            + "  col_binary_float BINARY_FLOAT,\n"
            + "  col_binary_double BINARY_DOUBLE,\n"
            + "  col_varchar2 VARCHAR2(100),\n"
            + "  col_nvarchar2 NVARCHAR2(100),\n"
            + "  col_char CHAR(10),\n"
            + "  col_clob CLOB,\n"
            + "  col_nclob NCLOB,\n"
            + "  col_blob BLOB,\n"
            + "  col_raw RAW(100),\n"
            + "  col_date DATE,\n"
            + "  col_timestamp TIMESTAMP(6),\n"
            + "  col_timestamp_tz TIMESTAMP(6) WITH TIME ZONE,\n"
            + "  col_timestamp_ltz TIMESTAMP(6) WITH LOCAL TIME ZONE,\n"
            + "  col_interval_ym INTERVAL YEAR(3) TO MONTH,\n"
            + "  col_interval_ds INTERVAL DAY(3) TO SECOND(6),\n"
            + "  PRIMARY KEY (id)\n"
            + ")");

    // Insert one test row with representative values using Oracle-specific syntax
    OracleTestContainer.executeSql(
        "INSERT INTO type_test VALUES ("
            + "1, 12345.67, 3.141592653589793, 2.71828, 3.14, 2.718281828459045,"
            + " 'hello', 'world', 'pad       ',"
            + " 'clob text value', 'nclob text value',"
            + " UTL_RAW.CAST_TO_RAW('binary data'),"
            + " UTL_RAW.CAST_TO_RAW('raw data'),"
            + " TO_DATE('2024-01-15 10:30:00', 'YYYY-MM-DD HH24:MI:SS'),"
            + " TIMESTAMP '2024-01-15 10:30:00.123456',"
            + " TIMESTAMP '2024-01-15 10:30:00.123 +05:30',"
            + " TIMESTAMP '2024-01-15 10:30:00.456789',"
            + " INTERVAL '2-6' YEAR TO MONTH,"
            + " INTERVAL '5 12:30:45.123456' DAY TO SECOND"
            + ")");
  }

  @AfterClass
  public static void tearDownClass() {
    if (pool != null) {
      pool.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Schema mapping test
  // ---------------------------------------------------------------------------

  /**
   * Validates that all columns in TYPE_TEST map to the correct Arrow types.
   *
   * <p>Oracle returns identifiers in UPPERCASE via DatabaseMetaData. The schema name is the
   * username in uppercase: {@code TEST_USER}. The table is {@code TYPE_TEST}.
   */
  @Test
  public void testSchemaMapping() throws Exception {
    // Oracle returns UPPERCASE identifiers; schema is test user's uppercase name
    BatchSchema schema = schemaFetcher.getTableSchema("TEST_USER", "TYPE_TEST");
    assertNotNull("Schema must not be null", schema);

    // NUMBER(10) with scale=0 — maps to Decimal(10,0)
    assertDecimal(schema, "ID");

    // NUMBER(10,2) — fixed-precision with scale → Decimal
    assertDecimal(schema, "COL_NUMBER_PS");

    // Bare NUMBER (precision=0, scale=0) — bare NUMBER → Double
    assertFloat8(schema, "COL_NUMBER_BARE");

    // FLOAT — reported by Oracle as NUMERIC with scale=-127 (sentinel) → Double
    assertFloat8(schema, "COL_FLOAT");

    // BINARY_FLOAT — Oracle JDBC type 100 → Float4 (single-precision)
    assertFloat4(schema, "COL_BINARY_FLOAT");

    // BINARY_DOUBLE — Oracle JDBC type 101 → Float8 (double-precision)
    assertFloat8(schema, "COL_BINARY_DOUBLE");

    // VARCHAR2 → Utf8
    assertUtf8(schema, "COL_VARCHAR2");

    // NVARCHAR2 → Utf8 (NVARCHAR maps via NVARCHAR handler in OracleSchemaFetcher)
    assertUtf8(schema, "COL_NVARCHAR2");

    // CHAR → Utf8
    assertUtf8(schema, "COL_CHAR");

    // CLOB → Utf8
    assertUtf8(schema, "COL_CLOB");

    // NCLOB → Utf8
    assertUtf8(schema, "COL_NCLOB");

    // BLOB → Binary
    assertBinary(schema, "COL_BLOB");

    // RAW → Binary
    assertBinary(schema, "COL_RAW");

    // DATE — Oracle DATE includes time component; driver reports as TIMESTAMP → Timestamp
    assertTimestamp(schema, "COL_DATE");

    // TIMESTAMP → Timestamp
    assertTimestamp(schema, "COL_TIMESTAMP");

    // TIMESTAMP WITH TIME ZONE — TZ dropped by OracleSchemaFetcher → Timestamp
    assertTimestamp(schema, "COL_TIMESTAMP_TZ");

    // TIMESTAMP WITH LOCAL TIME ZONE — TZ dropped → Timestamp
    assertTimestamp(schema, "COL_TIMESTAMP_LTZ");

    // INTERVAL YEAR TO MONTH → Interval(YEAR_MONTH)
    assertInterval(schema, "COL_INTERVAL_YM");

    // INTERVAL DAY TO SECOND → Interval(DAY_TIME)
    assertInterval(schema, "COL_INTERVAL_DS");
  }

  // ---------------------------------------------------------------------------
  // Null handling test
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a row with all-null values for nullable columns does not break schema discovery.
   */
  @Test
  public void testNullHandling() throws Exception {
    OracleTestContainer.executeSql("INSERT INTO type_test (id) VALUES (2)");

    // Schema must still be discoverable even when data rows contain NULLs
    BatchSchema schema = schemaFetcher.getTableSchema("TEST_USER", "TYPE_TEST");
    assertNotNull("Schema must not be null even when rows have NULLs", schema);

    // Key type mappings must still be correct regardless of null data rows
    assertUtf8(schema, "COL_VARCHAR2");
    assertUtf8(schema, "COL_CLOB");
    assertUtf8(schema, "COL_NCLOB");
    assertBinary(schema, "COL_BLOB");
    assertBinary(schema, "COL_RAW");
    // New types with null values must still map correctly
    assertTimestamp(schema, "COL_TIMESTAMP_LTZ");
    assertInterval(schema, "COL_INTERVAL_YM");
    assertInterval(schema, "COL_INTERVAL_DS");
  }

  /**
   * Verifies that a row with NULL INTERVAL and TIMESTAMP LTZ values can be read without errors.
   * The null row (id=2) was inserted in testNullHandling — all nullable columns are NULL.
   */
  @Test
  public void testNullIntervalAndTimestampLtzRoundtrip() throws Exception {
    // Insert a row with explicit NULL values for the new types
    OracleTestContainer.executeSql(
        "INSERT INTO type_test (id, col_timestamp_ltz, col_interval_ym, col_interval_ds) "
            + "VALUES (3, NULL, NULL, NULL)");

    try (Connection conn = pool.getConnection();
        java.sql.PreparedStatement ps =
            conn.prepareStatement("SELECT * FROM type_test WHERE id = 3");
        ResultSet rs = ps.executeQuery()) {

      assertTrue("Expected one row with id=3", rs.next());

      // NULL TIMESTAMP WITH LOCAL TIME ZONE
      java.sql.Timestamp tsLtz = rs.getTimestamp("COL_TIMESTAMP_LTZ");
      assertTrue("COL_TIMESTAMP_LTZ should be null for id=3", rs.wasNull());

      // NULL INTERVAL YEAR TO MONTH
      String intervalYm = rs.getString("COL_INTERVAL_YM");
      assertTrue("COL_INTERVAL_YM should be null for id=3", rs.wasNull() || intervalYm == null);

      // NULL INTERVAL DAY TO SECOND
      String intervalDs = rs.getString("COL_INTERVAL_DS");
      assertTrue("COL_INTERVAL_DS should be null for id=3", rs.wasNull() || intervalDs == null);
    }
  }

  // ---------------------------------------------------------------------------
  // Value roundtrip test
  // ---------------------------------------------------------------------------

  /**
   * Reads the inserted row back via raw JDBC and verifies that the Oracle JDBC driver returns
   * sensible values for key columns including VARCHAR2, NUMBER, and DATE.
   */
  @Test
  public void testValueRoundtrip() throws Exception {
    try (Connection conn = pool.getConnection();
        java.sql.PreparedStatement ps =
            conn.prepareStatement("SELECT * FROM type_test WHERE id = 1");
        ResultSet rs = ps.executeQuery()) {

      assertTrue("Expected one row with id=1", rs.next());

      // VARCHAR2 value
      assertEquals("hello", rs.getString("COL_VARCHAR2"));

      // NUMBER(10,2)
      assertEquals(12345.67, rs.getDouble("COL_NUMBER_PS"), 0.001);

      // DATE — Oracle DATE preserves time component
      java.sql.Timestamp date = rs.getTimestamp("COL_DATE");
      assertNotNull("COL_DATE must not be null", date);
      // Time component should be 10:30:00
      assertTrue("DATE should include time component (hour=10)", date.toString().contains("10:30"));

      // TIMESTAMP
      java.sql.Timestamp ts = rs.getTimestamp("COL_TIMESTAMP");
      assertNotNull("COL_TIMESTAMP must not be null", ts);

      // TIMESTAMP WITH LOCAL TIME ZONE — readable as Timestamp
      java.sql.Timestamp tsLtz = rs.getTimestamp("COL_TIMESTAMP_LTZ");
      assertNotNull("COL_TIMESTAMP_LTZ must not be null", tsLtz);

      // INTERVAL YEAR TO MONTH — returned as String
      String intervalYm = rs.getString("COL_INTERVAL_YM");
      assertNotNull("COL_INTERVAL_YM must not be null", intervalYm);
      assertTrue(
          "INTERVAL YM should contain year-month pattern (got: " + intervalYm + ")",
          intervalYm.contains("2") && intervalYm.contains("6"));

      // INTERVAL DAY TO SECOND — returned as String
      String intervalDs = rs.getString("COL_INTERVAL_DS");
      assertNotNull("COL_INTERVAL_DS must not be null", intervalDs);
      assertTrue(
          "INTERVAL DS should contain day-time pattern (got: " + intervalDs + ")",
          intervalDs.contains("5") && intervalDs.contains("12"));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Field findField(BatchSchema schema, String name) {
    for (Field f : schema.getFields()) {
      if (f.getName().equalsIgnoreCase(name)) {
        return f;
      }
    }
    return null;
  }

  private static void assertFieldType(
      BatchSchema schema, String name, Class<? extends ArrowType> expected) {
    Field f = findField(schema, name);
    assertNotNull("Field '" + name + "' must exist in schema", f);
    assertEquals("Field '" + name + "' has wrong Arrow type", expected, f.getType().getClass());
  }

  private static void assertUtf8(BatchSchema schema, String name) {
    assertFieldType(schema, name, ArrowType.Utf8.class);
  }

  private static void assertDecimal(BatchSchema schema, String name) {
    assertFieldType(schema, name, ArrowType.Decimal.class);
  }

  private static void assertFloat4(BatchSchema schema, String name) {
    Field f = findField(schema, name);
    assertNotNull("Field '" + name + "' must exist in schema", f);
    assertEquals(ArrowType.FloatingPoint.class, f.getType().getClass());
    ArrowType.FloatingPoint fp = (ArrowType.FloatingPoint) f.getType();
    assertEquals(
        "Field '" + name + "' must be single-precision",
        FloatingPointPrecision.SINGLE,
        fp.getPrecision());
  }

  private static void assertFloat8(BatchSchema schema, String name) {
    Field f = findField(schema, name);
    assertNotNull("Field '" + name + "' must exist in schema", f);
    assertEquals(ArrowType.FloatingPoint.class, f.getType().getClass());
    ArrowType.FloatingPoint fp = (ArrowType.FloatingPoint) f.getType();
    assertEquals(
        "Field '" + name + "' must be double-precision",
        FloatingPointPrecision.DOUBLE,
        fp.getPrecision());
  }

  private static void assertTimestamp(BatchSchema schema, String name) {
    assertFieldType(schema, name, ArrowType.Timestamp.class);
  }

  private static void assertBinary(BatchSchema schema, String name) {
    assertFieldType(schema, name, ArrowType.Binary.class);
  }

  private static void assertInterval(BatchSchema schema, String name) {
    assertFieldType(schema, name, ArrowType.Interval.class);
  }
}
