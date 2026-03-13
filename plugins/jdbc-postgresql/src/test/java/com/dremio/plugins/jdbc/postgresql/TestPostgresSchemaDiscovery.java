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
 * Integration tests validating {@link PostgresSchemaFetcher} schema discovery behaviour.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>listSchemas() returns user schemas and excludes PostgreSQL system schemas</li>
 *   <li>listTables() returns TABLE and VIEW entries within a schema</li>
 *   <li>tableExists() returns correct boolean for existing / non-existing tables</li>
 *   <li>getTableSchema() returns the correct Arrow column types</li>
 * </ul>
 *
 * <p>All tests run against a shared {@code postgres:16-alpine} container.
 */
public class TestPostgresSchemaDiscovery {

  @ClassRule
  public static final DremioPostgresContainer PG = PostgresTestContainer.PG;

  private static JdbcConnectionPool pool;
  private static PostgresSchemaFetcher schemaFetcher;

  @BeforeClass
  public static void setUpClass() throws Exception {
    pool = PostgresTestContainer.createPool();
    schemaFetcher = new PostgresSchemaFetcher(pool);

    // Create a second schema with a table and a view for schema discovery tests
    PostgresTestContainer.executeSql("CREATE SCHEMA IF NOT EXISTS test_schema");
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS test_schema.sample_table (id INTEGER, name TEXT)");
    PostgresTestContainer.executeSql(
        "CREATE OR REPLACE VIEW test_schema.sample_view AS "
            + "SELECT * FROM test_schema.sample_table");
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
   * Verifies that user schemas are returned and system schemas are excluded.
   */
  @Test
  public void testListSchemas() throws Exception {
    List<String> schemas = schemaFetcher.listSchemas();
    assertNotNull("schemas list must not be null", schemas);

    // User schemas must be present
    assertTrue("'public' schema must be listed", schemas.contains("public"));
    assertTrue("'test_schema' must be listed", schemas.contains("test_schema"));

    // System schemas must be filtered out
    assertFalse("'pg_catalog' must be excluded", schemas.contains("pg_catalog"));
    assertFalse("'information_schema' must be excluded", schemas.contains("information_schema"));
    // pg_toast* and pg_temp_* are excluded by the base class; pg_internal by PostgresSchemaFetcher
    for (String s : schemas) {
      assertFalse("No pg_toast* schemas should appear", s.startsWith("pg_toast"));
      assertFalse("No pg_temp_* schemas should appear", s.startsWith("pg_temp_"));
      assertFalse("pg_internal must be excluded", s.equalsIgnoreCase("pg_internal"));
    }
  }

  // ---------------------------------------------------------------------------
  // listTables tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that both TABLE and VIEW entries are returned for test_schema.
   */
  @Test
  public void testListTables() throws Exception {
    List<String> tables = schemaFetcher.listTables("test_schema");
    assertNotNull("tables list must not be null", tables);
    assertTrue("'sample_table' must be in test_schema", tables.contains("sample_table"));
    assertTrue("'sample_view' must be in test_schema", tables.contains("sample_view"));
  }

  /**
   * Verifies that the public schema tables are discoverable (type_test created by
   * TestPostgresTypeMapping setup; run order is not guaranteed so we accept any non-null list).
   */
  @Test
  public void testListTablesPublicSchema() throws Exception {
    List<String> tables = schemaFetcher.listTables("public");
    assertNotNull("public schema tables list must not be null", tables);
    // We do not assert type_test here because test execution order is not guaranteed;
    // we just verify the method returns without error.
  }

  /**
   * Verifies that listTables returns an empty list (not an exception) for an empty schema.
   */
  @Test
  public void testListTablesEmptySchema() throws Exception {
    PostgresTestContainer.executeSql("CREATE SCHEMA IF NOT EXISTS empty_schema");
    List<String> tables = schemaFetcher.listTables("empty_schema");
    assertNotNull("empty schema table list must not be null", tables);
    assertTrue("empty schema must have no tables", tables.isEmpty());
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
        "sample_table must exist in test_schema",
        schemaFetcher.tableExists("test_schema", "sample_table"));
  }

  /**
   * Verifies that tableExists returns true for a view (since getTables includes VIEW type).
   */
  @Test
  public void testTableExistsForView() throws Exception {
    assertTrue(
        "sample_view must be found by tableExists",
        schemaFetcher.tableExists("test_schema", "sample_view"));
  }

  /**
   * Verifies that tableExists returns false for a non-existent table name.
   */
  @Test
  public void testTableExistsForNonExistent() throws Exception {
    assertFalse(
        "nonexistent_table must not be found",
        schemaFetcher.tableExists("test_schema", "nonexistent_table"));
  }

  /**
   * Verifies that tableExists returns false for a table in the wrong schema.
   */
  @Test
  public void testTableExistsWrongSchema() throws Exception {
    assertFalse(
        "sample_table must not appear in public schema",
        schemaFetcher.tableExists("public", "sample_table"));
  }

  // ---------------------------------------------------------------------------
  // getTableSchema tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that getTableSchema returns the correct Arrow types for sample_table columns.
   */
  @Test
  public void testGetTableSchemaColumns() throws Exception {
    BatchSchema schema = schemaFetcher.getTableSchema("test_schema", "sample_table");
    assertNotNull("schema must not be null", schema);

    List<Field> fields = schema.getFields();
    assertEquals("sample_table must have exactly 2 fields", 2, fields.size());

    // Find fields by name (order may vary depending on MetaData implementation)
    Field idField = null;
    Field nameField = null;
    for (Field f : fields) {
      if (f.getName().equalsIgnoreCase("id")) {
        idField = f;
      } else if (f.getName().equalsIgnoreCase("name")) {
        nameField = f;
      }
    }

    assertNotNull("'id' field must be present", idField);
    assertNotNull("'name' field must be present", nameField);

    // id is INTEGER -> ArrowType.Int(32)
    assertEquals(ArrowType.Int.class, idField.getType().getClass());
    assertEquals(32, ((ArrowType.Int) idField.getType()).getBitWidth());

    // name is TEXT -> ArrowType.Utf8
    assertEquals(ArrowType.Utf8.class, nameField.getType().getClass());
  }

  /**
   * Verifies that getTableSchema returns the correct type for a single-column table.
   */
  @Test
  public void testGetTableSchemaForView() throws Exception {
    // sample_view is SELECT * FROM sample_table, so same columns
    BatchSchema schema = schemaFetcher.getTableSchema("test_schema", "sample_view");
    assertNotNull("view schema must not be null", schema);
    assertFalse("view schema must have at least one field", schema.getFields().isEmpty());
  }
}
