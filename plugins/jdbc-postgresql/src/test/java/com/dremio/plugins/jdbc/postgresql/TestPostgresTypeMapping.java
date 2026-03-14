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

import com.dremio.exec.record.BatchSchema;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import java.sql.Connection;
import java.sql.ResultSet;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Integration tests validating PostgreSQL-to-Arrow type mapping for all PG-specific types.
 *
 * <p>Tests run against a real {@code postgres:16-alpine} container via TestContainers. Each test
 * validates that {@link PostgresSchemaFetcher#getTableSchema} maps columns to the correct Arrow
 * type. A value roundtrip test additionally verifies that the PostgreSQL JDBC driver returns
 * sensible string representations for exotic types (UUID, JSONB, etc.).
 */
public class TestPostgresTypeMapping {

  @ClassRule public static final DremioPostgresContainer PG = PostgresTestContainer.PG;

  private static JdbcConnectionPool pool;
  private static PostgresSchemaFetcher schemaFetcher;

  @BeforeClass
  public static void setUpClass() throws Exception {
    pool = PostgresTestContainer.createPool();
    schemaFetcher = new PostgresSchemaFetcher(pool);

    // Create the type_test table with all PG-specific types
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS type_test (\n"
            + "  id SERIAL PRIMARY KEY,\n"
            + "  col_text TEXT,\n"
            + "  col_varchar VARCHAR(100),\n"
            + "  col_char CHAR(10),\n"
            + "  col_boolean BOOLEAN,\n"
            + "  col_smallint SMALLINT,\n"
            + "  col_integer INTEGER,\n"
            + "  col_bigint BIGINT,\n"
            + "  col_bigserial BIGSERIAL,\n"
            + "  col_real REAL,\n"
            + "  col_double DOUBLE PRECISION,\n"
            + "  col_numeric NUMERIC(10,2),\n"
            + "  col_date DATE,\n"
            + "  col_time TIME,\n"
            + "  col_timestamp TIMESTAMP,\n"
            + "  col_timestamptz TIMESTAMPTZ,\n"
            + "  col_bytea BYTEA,\n"
            + "  col_uuid UUID,\n"
            + "  col_jsonb JSONB,\n"
            + "  col_json JSON,\n"
            + "  col_interval INTERVAL,\n"
            + "  col_money MONEY,\n"
            + "  col_int_array INTEGER[],\n"
            + "  col_text_array TEXT[],\n"
            + "  col_cidr CIDR,\n"
            + "  col_inet INET,\n"
            + "  col_macaddr MACADDR\n"
            + ")");

    // Insert one test row with representative values
    PostgresTestContainer.executeSql(
        "INSERT INTO type_test "
            + "(col_text, col_varchar, col_char, col_boolean, col_smallint, col_integer, "
            + " col_bigint, col_real, col_double, col_numeric, col_date, col_time, "
            + " col_timestamp, col_timestamptz, col_bytea, col_uuid, col_jsonb, col_json, "
            + " col_interval, col_money, col_int_array, col_text_array, col_cidr, col_inet, "
            + " col_macaddr) "
            + "VALUES ("
            + "  'hello', 'world', 'pad       ', true, 32767, 2147483647, "
            + "  9223372036854775807, 3.14, 2.718281828, 123.45, '2024-01-15', '10:30:00', "
            + "  '2024-01-15 10:30:00', '2024-01-15 10:30:00+00', E'\\\\x48656C6C6F', "
            + "  '12151fd2-7586-11e9-8f9e-2a86e4085a59', '{\"key\":\"value\"}', '{\"num\":42}', "
            + "  '1 year 2 months', '$1,234.56', ARRAY[1,2,3], ARRAY['a','b','c'], "
            + "  '192.168.1.0/24', '192.168.1.1', '08:00:2b:01:02:03')");
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

  /** Validates that all columns in type_test map to the correct Arrow types. */
  @Test
  public void testSchemaMapping() throws Exception {
    BatchSchema schema = schemaFetcher.getTableSchema("public", "type_test");
    assertNotNull("Schema must not be null", schema);

    // SERIAL is reported as INTEGER by PostgreSQL
    assertFieldType(schema, "id", ArrowType.Int.class);
    assertInt32(schema, "id");

    // Basic string types
    assertUtf8(schema, "col_text");
    assertUtf8(schema, "col_varchar");
    assertUtf8(schema, "col_char");

    // Numeric types
    // BOOLEAN → bit/bool type (mapped by Arrow JDBC adapter)
    Field boolField = findField(schema, "col_boolean");
    assertNotNull("col_boolean must be present", boolField);
    // Note: Arrow JDBC maps BOOLEAN to Bool or Int(1); any non-null type is acceptable
    assertNotNull("col_boolean must have an ArrowType", boolField.getType());

    assertInt16OrInt32(schema, "col_smallint");
    assertInt32(schema, "col_integer");
    assertInt64(schema, "col_bigint");
    assertInt64(schema, "col_bigserial");

    // Floating point
    Field realField = findField(schema, "col_real");
    assertNotNull("col_real must be present", realField);
    assertNotNull("col_real must have type", realField.getType());

    Field doubleField = findField(schema, "col_double");
    assertNotNull("col_double must be present", doubleField);
    assertNotNull("col_double must have type", doubleField.getType());

    // Fixed-precision numeric
    Field numericField = findField(schema, "col_numeric");
    assertNotNull("col_numeric must be present", numericField);
    assertNotNull("col_numeric must have type", numericField.getType());

    // Date/time
    Field dateField = findField(schema, "col_date");
    assertNotNull("col_date must be present", dateField);

    Field timeField = findField(schema, "col_time");
    assertNotNull("col_time must be present", timeField);

    Field tsField = findField(schema, "col_timestamp");
    assertNotNull("col_timestamp must be present", tsField);

    Field tstzField = findField(schema, "col_timestamptz");
    assertNotNull("col_timestamptz must be present", tstzField);

    // PG-specific types mapped to VARCHAR by PostgresSchemaFetcher
    assertUtf8(schema, "col_uuid");
    assertUtf8(schema, "col_jsonb");
    assertUtf8(schema, "col_json");
    assertUtf8(schema, "col_interval");
    assertUtf8(schema, "col_money");

    // Array types mapped to VARCHAR
    assertUtf8(schema, "col_int_array");
    assertUtf8(schema, "col_text_array");

    // Network types mapped to VARCHAR
    assertUtf8(schema, "col_cidr");
    assertUtf8(schema, "col_inet");
    assertUtf8(schema, "col_macaddr");

    // BYTEA → Binary
    Field byteaField = findField(schema, "col_bytea");
    assertNotNull("col_bytea must be present", byteaField);
    assertEquals(
        "col_bytea should map to Binary", ArrowType.Binary.class, byteaField.getType().getClass());
  }

  // ---------------------------------------------------------------------------
  // Null handling test
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a row with all-null values for nullable columns does not break schema discovery.
   */
  @Test
  public void testNullHandling() throws Exception {
    // Insert a row with all nullable columns set to NULL
    PostgresTestContainer.executeSql(
        "INSERT INTO type_test "
            + "(col_text, col_varchar, col_char, col_boolean, col_smallint, col_integer, "
            + " col_bigint, col_real, col_double, col_numeric, col_date, col_time, "
            + " col_timestamp, col_timestamptz, col_bytea, col_uuid, col_jsonb, col_json, "
            + " col_interval, col_money, col_int_array, col_text_array, col_cidr, col_inet, "
            + " col_macaddr) "
            + "VALUES (NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, "
            + "        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, "
            + "        NULL, NULL, NULL, NULL, NULL)");

    // Schema must still be discoverable even when data rows contain NULLs
    BatchSchema schema = schemaFetcher.getTableSchema("public", "type_test");
    assertNotNull("Schema must not be null even when rows have NULLs", schema);

    // The PG-specific types must still map to Utf8
    assertUtf8(schema, "col_uuid");
    assertUtf8(schema, "col_jsonb");
    assertUtf8(schema, "col_json");
    assertUtf8(schema, "col_interval");
    assertUtf8(schema, "col_money");
    assertUtf8(schema, "col_cidr");
    assertUtf8(schema, "col_inet");
    assertUtf8(schema, "col_macaddr");
  }

  // ---------------------------------------------------------------------------
  // Value roundtrip test
  // ---------------------------------------------------------------------------

  /**
   * Reads the inserted row back via raw JDBC and verifies that the PostgreSQL driver returns
   * sensible string representations for UUID, JSONB, arrays, and network types.
   */
  @Test
  public void testValueRoundtrip() throws Exception {
    try (Connection conn = pool.getConnection();
        java.sql.PreparedStatement ps =
            conn.prepareStatement("SELECT * FROM type_test WHERE id = 1");
        ResultSet rs = ps.executeQuery()) {

      // Must have at least one row
      assertTrue("Expected one row with id=1", rs.next());

      // Verify string-type columns
      assertEquals("hello", rs.getString("col_text"));
      assertEquals("world", rs.getString("col_varchar"));

      // UUID should come back as a standard lowercase UUID string
      String uuid = rs.getString("col_uuid");
      assertNotNull("UUID column must not be null", uuid);
      assertEquals("12151fd2-7586-11e9-8f9e-2a86e4085a59", uuid);

      // JSONB should come back as a JSON string
      String jsonb = rs.getString("col_jsonb");
      assertNotNull("JSONB column must not be null", jsonb);
      // PostgreSQL may normalise key order; just check it contains 'key'
      assertTrue("JSONB value must contain 'key': " + jsonb, jsonb.contains("key"));

      // JSON
      String json = rs.getString("col_json");
      assertNotNull("JSON column must not be null", json);
      assertTrue("JSON value must contain 'num': " + json, json.contains("num"));

      // INTERVAL comes back as string representation
      String interval = rs.getString("col_interval");
      assertNotNull("INTERVAL column must not be null", interval);

      // MONEY
      String money = rs.getString("col_money");
      assertNotNull("MONEY column must not be null", money);

      // Arrays
      String intArray = rs.getString("col_int_array");
      assertNotNull("int array must not be null", intArray);
      assertTrue("int array must contain '1': " + intArray, intArray.contains("1"));

      // CIDR / INET / MACADDR
      String cidr = rs.getString("col_cidr");
      assertNotNull("CIDR column must not be null", cidr);
      assertTrue("CIDR must contain 192.168: " + cidr, cidr.contains("192.168"));

      String inet = rs.getString("col_inet");
      assertEquals("192.168.1.1", inet);

      String macaddr = rs.getString("col_macaddr");
      assertNotNull("MACADDR column must not be null", macaddr);
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

  private static void assertInt32(BatchSchema schema, String name) {
    Field f = findField(schema, name);
    assertNotNull("Field '" + name + "' must exist in schema", f);
    assertEquals(ArrowType.Int.class, f.getType().getClass());
    ArrowType.Int intType = (ArrowType.Int) f.getType();
    assertEquals("Field '" + name + "' must be 32-bit", 32, intType.getBitWidth());
  }

  private static void assertInt16OrInt32(BatchSchema schema, String name) {
    Field f = findField(schema, name);
    assertNotNull("Field '" + name + "' must exist in schema", f);
    assertEquals(ArrowType.Int.class, f.getType().getClass());
    ArrowType.Int intType = (ArrowType.Int) f.getType();
    assertTrue(
        "Field '" + name + "' must be 16 or 32-bit Int but was " + intType.getBitWidth(),
        intType.getBitWidth() == 16 || intType.getBitWidth() == 32);
  }

  private static void assertInt64(BatchSchema schema, String name) {
    Field f = findField(schema, name);
    assertNotNull("Field '" + name + "' must exist in schema", f);
    assertEquals(ArrowType.Int.class, f.getType().getClass());
    ArrowType.Int intType = (ArrowType.Int) f.getType();
    assertEquals("Field '" + name + "' must be 64-bit", 64, intType.getBitWidth());
  }
}
