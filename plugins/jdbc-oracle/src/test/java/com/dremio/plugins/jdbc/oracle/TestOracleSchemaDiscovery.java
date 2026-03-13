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

import com.dremio.exec.record.BatchSchema;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import java.util.List;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Integration tests validating {@link OracleSchemaFetcher} schema discovery behaviour.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>listSchemas() returns the test user schema and excludes Oracle system schemas</li>
 *   <li>listTables() returns TABLE and VIEW entries within a user schema</li>
 *   <li>tableExists() returns correct boolean for existing / non-existing tables and views</li>
 *   <li>getTableSchema() returns the correct Arrow column types for discovered columns</li>
 * </ul>
 *
 * <p>In Oracle, schemas are tied to users. The test user's schema name equals the username in
 * uppercase: {@code TEST_USER}. There is no {@code CREATE SCHEMA} in Oracle — creating a user
 * implicitly creates a schema with the same name.
 *
 * <p>All tests run against a shared {@code gvenzl/oracle-xe:21-slim} container.
 */
public class TestOracleSchemaDiscovery {

  @ClassRule
  public static final DremioOracleContainer ORACLE = OracleTestContainer.ORACLE;

  private static JdbcConnectionPool pool;
  private static OracleSchemaFetcher schemaFetcher;

  @BeforeClass
  public static void setUpClass() throws Exception {
    pool = OracleTestContainer.createPool();
    schemaFetcher = new OracleSchemaFetcher(pool);

    // Create a table and view in the test user's schema for discovery tests
    OracleTestContainer.executeSql(
        "CREATE TABLE discovery_table (id NUMBER(10), name VARCHAR2(100))");
    OracleTestContainer.executeSql(
        "CREATE VIEW discovery_view AS SELECT * FROM discovery_table");
  }

  @AfterClass
  public static void tearDownClass() {
    if (pool != null) {
      pool.close();
    }
  }

  // ---------------------------------------------------------------------------
  // listSchemas tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that the test user's schema is listed and Oracle system schemas are excluded.
   *
   * <p>Oracle system schemas to verify as excluded include:
   * SYS, SYSTEM, CTXSYS, MDSYS, XDB, OUTLN, ORDSYS, etc.
   * These are defined in {@link OracleSchemaFetcher#ORACLE_SYSTEM_SCHEMAS}.
   */
  @Test
  public void testListSchemas() throws Exception {
    List<String> schemas = schemaFetcher.listSchemas();
    assertNotNull("schemas list must not be null", schemas);

    // The test user's schema must be present (Oracle returns uppercase)
    // Accept case-insensitive match since behaviour may vary
    boolean testUserFound = schemas.stream()
        .anyMatch(s -> s.equalsIgnoreCase("TEST_USER"));
    assertTrue("'TEST_USER' schema must be listed", testUserFound);

    // Oracle system schemas must be filtered out (case-insensitive check)
    for (String schema : schemas) {
      String lower = schema.toLowerCase();
      assertFalse("'SYS' must be excluded from listing", lower.equals("sys"));
      assertFalse("'SYSTEM' must be excluded from listing", lower.equals("system"));
      assertFalse("'CTXSYS' must be excluded from listing", lower.equals("ctxsys"));
      assertFalse("'MDSYS' must be excluded from listing", lower.equals("mdsys"));
      assertFalse("'XDB' must be excluded from listing", lower.equals("xdb"));
      assertFalse("'OUTLN' must be excluded from listing", lower.equals("outln"));
      assertFalse("'ORDSYS' must be excluded from listing", lower.equals("ordsys"));
      assertFalse("'WMSYS' must be excluded from listing", lower.equals("wmsys"));
    }
  }

  // ---------------------------------------------------------------------------
  // listTables tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that both TABLE and VIEW entries are returned for the test user's schema.
   */
  @Test
  public void testListTables() throws Exception {
    // Oracle uses uppercase schema names for the user
    List<String> tables = schemaFetcher.listTables("TEST_USER");
    assertNotNull("tables list must not be null", tables);

    // Oracle returns UPPERCASE table/view names
    boolean discoveryTableFound = tables.stream()
        .anyMatch(t -> t.equalsIgnoreCase("DISCOVERY_TABLE"));
    boolean discoveryViewFound = tables.stream()
        .anyMatch(t -> t.equalsIgnoreCase("DISCOVERY_VIEW"));

    assertTrue("'DISCOVERY_TABLE' must be in TEST_USER schema", discoveryTableFound);
    assertTrue("'DISCOVERY_VIEW' must be in TEST_USER schema", discoveryViewFound);
  }

  /**
   * Verifies that listTables returns an empty list (not an exception) for a non-existent schema.
   */
  @Test
  public void testListTablesNonExistentSchema() throws Exception {
    List<String> tables = schemaFetcher.listTables("NONEXISTENT");
    assertNotNull("non-existent schema table list must not be null", tables);
    assertTrue("non-existent schema must return no tables", tables.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // tableExists tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that tableExists returns true for an existing table.
   */
  @Test
  public void testTableExistsForExistingTable() throws Exception {
    assertTrue(
        "DISCOVERY_TABLE must exist in TEST_USER schema",
        schemaFetcher.tableExists("TEST_USER", "DISCOVERY_TABLE"));
  }

  /**
   * Verifies that tableExists returns true for a view (since getTables includes VIEW type).
   */
  @Test
  public void testTableExistsForView() throws Exception {
    assertTrue(
        "DISCOVERY_VIEW must be found by tableExists",
        schemaFetcher.tableExists("TEST_USER", "DISCOVERY_VIEW"));
  }

  /**
   * Verifies that tableExists returns false for a non-existent table name.
   */
  @Test
  public void testTableExistsForNonExistent() throws Exception {
    assertFalse(
        "NONEXISTENT must not be found",
        schemaFetcher.tableExists("TEST_USER", "NONEXISTENT"));
  }

  // ---------------------------------------------------------------------------
  // getTableSchema tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that getTableSchema returns the correct Arrow types for discovery_table columns.
   *
   * <p>Oracle returns identifiers in UPPERCASE; this method uses the uppercase table name.
   */
  @Test
  public void testGetTableSchemaColumns() throws Exception {
    BatchSchema schema = schemaFetcher.getTableSchema("TEST_USER", "DISCOVERY_TABLE");
    assertNotNull("schema must not be null", schema);

    List<Field> fields = schema.getFields();
    assertEquals("discovery_table must have exactly 2 fields", 2, fields.size());

    // Find fields by name (case-insensitive since Oracle returns UPPERCASE)
    Field idField = null;
    Field nameField = null;
    for (Field f : fields) {
      if (f.getName().equalsIgnoreCase("id")) {
        idField = f;
      } else if (f.getName().equalsIgnoreCase("name")) {
        nameField = f;
      }
    }

    assertNotNull("'ID' field must be present", idField);
    assertNotNull("'NAME' field must be present", nameField);

    // NUMBER(10) with scale=0 → Decimal
    assertEquals(ArrowType.Decimal.class, idField.getType().getClass());

    // VARCHAR2 → Utf8
    assertEquals(ArrowType.Utf8.class, nameField.getType().getClass());
  }
}
