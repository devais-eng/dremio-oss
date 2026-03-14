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
import com.dremio.plugins.jdbc.planning.SqlBuilder;
import com.dremio.plugins.jdbc.pool.AdbcConnectionFactory;
import com.dremio.plugins.jdbc.schema.AdbcSchemaFetcher;
import java.util.List;
import org.apache.arrow.adbc.core.AdbcConnection;
import org.apache.arrow.adbc.core.AdbcStatement;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * ADBC integration tests for PostgreSQL.
 *
 * <p>Tests verify the full ADBC execution path end-to-end against a real PostgreSQL instance using
 * Testcontainers: connection lifecycle, basic query execution, bind parameter pushdown, schema
 * discovery, and type mapping.
 *
 * <p>These tests require the native ADBC PostgreSQL driver ({@code libadbc_driver_postgresql.so})
 * to be installed on the machine running the tests. If the native driver is not available, all tests
 * are skipped gracefully via {@link Assume#assumeTrue}.
 *
 * <p>The test class directly tests {@link AdbcConnectionFactory}, {@link AdbcSchemaFetcher}, and
 * ADBC statement execution without going through the full {@code JdbcStoragePlugin} or {@code
 * AdbcRecordReader} lifecycle, since those require an {@code OperatorContext} which is difficult to
 * construct in a unit test.
 */
public class TestPostgresAdbc {

  @ClassRule public static final DremioPostgresContainer PG = PostgresTestContainer.PG;

  private static boolean adbcAvailable;
  private static BufferAllocator allocator;
  // Use Object type to avoid class loading of ADBC types at test class init time.
  // The JNI native lib loading happens when AdbcConnectionFactory is first referenced,
  // which triggers UnsatisfiedLinkError if the native driver is missing. Using Object
  // defers class resolution until the fields are actually used in test methods.
  private static Object factory; // AdbcConnectionFactory
  private static Object schemaFetcherObj; // AdbcSchemaFetcher

  @BeforeClass
  public static void setUpClass() throws Exception {
    // Create the test table with various types and insert test data via JDBC.
    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS adbc_test (\n"
            + "    id INTEGER PRIMARY KEY,\n"
            + "    name VARCHAR(100),\n"
            + "    amount NUMERIC(10,2),\n"
            + "    is_active BOOLEAN,\n"
            + "    created_date DATE,\n"
            + "    created_at TIMESTAMP,\n"
            + "    score DOUBLE PRECISION,\n"
            + "    big_id BIGINT\n"
            + ")");

    PostgresTestContainer.executeSql(
        "INSERT INTO adbc_test VALUES "
            + "(1, 'Alice', 100.50, true, '2024-01-15', '2024-01-15 10:30:00', 95.5, 1000000001)"
            + " ON CONFLICT DO NOTHING");
    PostgresTestContainer.executeSql(
        "INSERT INTO adbc_test VALUES "
            + "(2, 'Bob', 200.75, false, '2024-02-20', '2024-02-20 14:45:00', 87.3, 1000000002)"
            + " ON CONFLICT DO NOTHING");
    PostgresTestContainer.executeSql(
        "INSERT INTO adbc_test VALUES "
            + "(3, 'Charlie', 350.00, true, '2024-03-10', '2024-03-10 09:15:00', 92.1, 1000000003)"
            + " ON CONFLICT DO NOTHING");

    // Only set up ADBC resources if the JNI driver is available.
    // The isAvailable() call triggers JNI native lib loading which may throw
    // UnsatisfiedLinkError or ExceptionInInitializerError if the native driver is missing.
    try {
      adbcAvailable = AdbcConnectionFactory.isAvailable();
    } catch (Throwable t) {
      // Native lib loading failed (UnsatisfiedLinkError, ExceptionInInitializerError, etc.)
      adbcAvailable = false;
    }

    if (adbcAvailable) {
      try {
        allocator = new RootAllocator(Long.MAX_VALUE);

        // Build a PostgreSQL ADBC connection URI from the testcontainer.
        String adbcUri =
            "postgresql://"
                + PostgresTestContainer.getUsername()
                + ":"
                + PostgresTestContainer.getPassword()
                + "@"
                + PG.getHost()
                + ":"
                + PG.getMappedPort(5432)
                + "/"
                + PG.getDatabaseName();

        AdbcConnectionFactory f = new AdbcConnectionFactory(adbcUri, allocator, 3);
        f.open();
        factory = f;

        schemaFetcherObj = new AdbcSchemaFetcher(f);
      } catch (Throwable t) {
        // Native PG driver (libadbc_driver_postgresql.so) not installed locally.
        // JNI bridge loaded but the database-specific driver is missing.
        adbcAvailable = false;
        if (allocator != null) {
          allocator.close();
          allocator = null;
        }
      }
    }
  }

  @AfterClass
  public static void tearDownClass() {
    if (factory != null) {
      ((AdbcConnectionFactory) factory).close();
      factory = null;
    }
    if (allocator != null) {
      allocator.close();
      allocator = null;
    }
  }

  /** Helper to get the typed factory. */
  private static AdbcConnectionFactory getFactory() {
    return (AdbcConnectionFactory) factory;
  }

  /** Helper to get the typed schema fetcher. */
  private static AdbcSchemaFetcher getSchemaFetcher() {
    return (AdbcSchemaFetcher) schemaFetcherObj;
  }

  /**
   * Guard: skip all ADBC tests if the native driver is not available. This ensures tests are
   * skipped gracefully in CI environments without the native driver.
   *
   * <p>We check the static flag set during {@code @BeforeClass} rather than calling {@link
   * AdbcConnectionFactory#isAvailable()} again, since that method triggers JNI native lib loading
   * which may produce noisy errors even when correctly falling back to "unavailable".
   */
  @Before
  public void checkAdbcAvailable() {
    Assume.assumeTrue(
        "ADBC native driver not available -- skipping ADBC integration tests",
        adbcAvailable);
  }

  // ---------------------------------------------------------------------------
  // Test 1: Connection lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Verifies that AdbcConnectionFactory can open a connection and close it without exceptions. This
   * validates the two-phase lifecycle: factory.open() was called in @BeforeClass, and here we verify
   * that individual connections can be created and released.
   */
  @Test
  public void testAdbcConnectionFactoryOpenClose() throws Exception {
    AdbcConnection conn = getFactory().openConnection();
    assertNotNull("ADBC connection must not be null", conn);
    conn.close();
    // No exception means success -- connection was opened and closed cleanly.
  }

  // ---------------------------------------------------------------------------
  // Test 2: Basic query execution
  // ---------------------------------------------------------------------------

  /**
   * Executes a SELECT query via ADBC and verifies that the correct rows and values are returned in
   * Arrow batches. Tests the full ADBC query path: connection -> statement -> executeQuery ->
   * ArrowReader -> batch data.
   */
  @Test
  public void testAdbcRecordReaderBasicQuery() throws Exception {
    try (AdbcConnection conn = getFactory().openConnection();
        AdbcStatement stmt = conn.createStatement()) {

      stmt.setSqlQuery("SELECT \"id\", \"name\" FROM \"public\".\"adbc_test\" ORDER BY \"id\"");
      AdbcStatement.QueryResult result = stmt.executeQuery();

      try (ArrowReader reader = result.getReader()) {
        int totalRows = 0;
        while (reader.loadNextBatch()) {
          VectorSchemaRoot root = reader.getVectorSchemaRoot();
          int rowCount = root.getRowCount();
          totalRows += rowCount;

          if (totalRows <= 3) {
            // Verify first batch values.
            IntVector idVec = (IntVector) root.getVector("id");
            VarCharVector nameVec = (VarCharVector) root.getVector("name");
            assertNotNull("id vector must exist", idVec);
            assertNotNull("name vector must exist", nameVec);

            // Check first row: id=1, name=Alice
            if (rowCount >= 1) {
              assertEquals("First row id should be 1", 1, idVec.get(0));
              assertEquals(
                  "First row name should be Alice",
                  "Alice",
                  new String(nameVec.get(0), java.nio.charset.StandardCharsets.UTF_8));
            }
          }
        }
        assertEquals("Expected exactly 3 rows from adbc_test", 3, totalRows);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Test 3: Placeholder translation and bind parameter pushdown
  // ---------------------------------------------------------------------------

  /**
   * Translates JDBC '?' placeholders to PostgreSQL '$N' notation using {@link
   * SqlBuilder#jdbcToPostgresPlaceholders}, then executes the parameterized query via ADBC with
   * bound parameters. Verifies that the filter is applied correctly.
   */
  @Test
  public void testAdbcPlaceholderTranslation() throws Exception {
    // Translate JDBC placeholders to PostgreSQL notation.
    String jdbcSql = "SELECT * FROM \"public\".\"adbc_test\" WHERE \"id\" > ?";
    String adbcSql = SqlBuilder.jdbcToPostgresPlaceholders(jdbcSql, 1);
    assertTrue(
        "Translated SQL must contain $1: " + adbcSql,
        adbcSql.contains("$1"));
    assertFalse(
        "Translated SQL must not contain ?: " + adbcSql,
        adbcSql.contains("?"));

    // Execute with bind parameter: id > 1 should return 2 rows (Bob, Charlie).
    try (AdbcConnection conn = getFactory().openConnection();
        AdbcStatement stmt = conn.createStatement()) {

      stmt.setSqlQuery(adbcSql);

      // Create a bind parameter root with a single IntVector for the $1 parameter.
      List<org.apache.arrow.vector.types.pojo.Field> fields = new java.util.ArrayList<>();
      fields.add(
          new org.apache.arrow.vector.types.pojo.Field(
              "$1",
              org.apache.arrow.vector.types.pojo.FieldType.nullable(
                  new ArrowType.Int(32, true)),
              null));
      org.apache.arrow.vector.types.pojo.Schema bindSchema =
          new org.apache.arrow.vector.types.pojo.Schema(fields);

      try (VectorSchemaRoot bindRoot = VectorSchemaRoot.create(bindSchema, allocator)) {
        bindRoot.allocateNew();
        ((IntVector) bindRoot.getVector("$1")).setSafe(0, 1);
        bindRoot.setRowCount(1);

        stmt.bind(bindRoot);

        AdbcStatement.QueryResult result = stmt.executeQuery();
        try (ArrowReader reader = result.getReader()) {
          int totalRows = 0;
          while (reader.loadNextBatch()) {
            totalRows += reader.getVectorSchemaRoot().getRowCount();
          }
          assertEquals("id > 1 should return 2 rows (Bob, Charlie)", 2, totalRows);
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Test 4: Schema discovery via ADBC
  // ---------------------------------------------------------------------------

  /**
   * Verifies ADBC schema discovery: getTableSchema returns correct fields, listSchemas includes
   * "public" but not system schemas, and listTables includes "adbc_test".
   */
  @Test
  public void testAdbcSchemaDiscovery() throws Exception {
    // Verify getTableSchema returns the expected fields.
    BatchSchema schema = getSchemaFetcher().getTableSchema("public", "adbc_test");
    assertNotNull("ADBC table schema must not be null", schema);
    assertTrue(
        "Schema must have at least 8 fields, got: " + schema.getFieldCount(),
        schema.getFieldCount() >= 8);

    // Verify specific fields exist (case-insensitive).
    assertFieldExists(schema, "id");
    assertFieldExists(schema, "name");
    assertFieldExists(schema, "amount");
    assertFieldExists(schema, "is_active");
    assertFieldExists(schema, "created_date");
    assertFieldExists(schema, "created_at");
    assertFieldExists(schema, "score");
    assertFieldExists(schema, "big_id");

    // Verify listSchemas includes "public" but not system schemas.
    List<String> schemas = getSchemaFetcher().listSchemas();
    assertNotNull("Schemas list must not be null", schemas);
    assertTrue("'public' schema must be listed", schemas.contains("public"));
    assertFalse("'pg_catalog' must be excluded", schemas.contains("pg_catalog"));
    assertFalse("'information_schema' must be excluded", schemas.contains("information_schema"));

    // Verify listTables includes "adbc_test".
    List<String> tables = getSchemaFetcher().listTables("public");
    assertNotNull("Tables list must not be null", tables);
    assertTrue("'adbc_test' must be listed in public schema", tables.contains("adbc_test"));
  }

  // ---------------------------------------------------------------------------
  // Test 5: Type mapping via ADBC
  // ---------------------------------------------------------------------------

  /**
   * Queries rows with various PostgreSQL types via ADBC and verifies that the Arrow batch contains
   * correctly typed vectors for INTEGER, BIGINT, DOUBLE PRECISION, VARCHAR, and BOOLEAN.
   */
  @Test
  public void testAdbcTypeMapping() throws Exception {
    try (AdbcConnection conn = getFactory().openConnection();
        AdbcStatement stmt = conn.createStatement()) {

      stmt.setSqlQuery(
          "SELECT \"id\", \"name\", \"score\", \"big_id\", \"is_active\" "
              + "FROM \"public\".\"adbc_test\" ORDER BY \"id\"");
      AdbcStatement.QueryResult result = stmt.executeQuery();

      try (ArrowReader reader = result.getReader()) {
        assertTrue("Must have at least one batch", reader.loadNextBatch());
        VectorSchemaRoot root = reader.getVectorSchemaRoot();
        assertTrue("Batch must have at least 1 row", root.getRowCount() >= 1);

        // Verify vector types.
        // INTEGER -> IntVector (32-bit) or SmallIntVector
        org.apache.arrow.vector.ValueVector idVec = root.getVector("id");
        assertNotNull("id vector must exist", idVec);
        assertTrue(
            "id should be IntVector, got: " + idVec.getClass().getSimpleName(),
            idVec instanceof IntVector);

        // VARCHAR -> VarCharVector
        org.apache.arrow.vector.ValueVector nameVec = root.getVector("name");
        assertNotNull("name vector must exist", nameVec);
        assertTrue(
            "name should be VarCharVector, got: " + nameVec.getClass().getSimpleName(),
            nameVec instanceof VarCharVector);

        // DOUBLE PRECISION -> Float8Vector
        org.apache.arrow.vector.ValueVector scoreVec = root.getVector("score");
        assertNotNull("score vector must exist", scoreVec);
        assertTrue(
            "score should be Float8Vector, got: " + scoreVec.getClass().getSimpleName(),
            scoreVec instanceof Float8Vector);

        // BIGINT -> BigIntVector
        org.apache.arrow.vector.ValueVector bigIdVec = root.getVector("big_id");
        assertNotNull("big_id vector must exist", bigIdVec);
        assertTrue(
            "big_id should be BigIntVector, got: " + bigIdVec.getClass().getSimpleName(),
            bigIdVec instanceof BigIntVector);

        // BOOLEAN -> BitVector
        org.apache.arrow.vector.ValueVector activeVec = root.getVector("is_active");
        assertNotNull("is_active vector must exist", activeVec);
        assertTrue(
            "is_active should be BitVector, got: " + activeVec.getClass().getSimpleName(),
            activeVec instanceof BitVector);

        // Verify actual values for the first row (Alice).
        assertEquals("First row id should be 1", 1, ((IntVector) idVec).get(0));
        assertEquals(
            "First row name should be Alice",
            "Alice",
            new String(((VarCharVector) nameVec).get(0), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(
            "First row score should be 95.5", 95.5, ((Float8Vector) scoreVec).get(0), 0.01);
        assertEquals(
            "First row big_id should be 1000000001",
            1000000001L,
            ((BigIntVector) bigIdVec).get(0));
        assertEquals("First row is_active should be true (1)", 1, ((BitVector) activeVec).get(0));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void assertFieldExists(BatchSchema schema, String fieldName) {
    boolean found = false;
    for (Field f : schema.getFields()) {
      if (f.getName().equalsIgnoreCase(fieldName)) {
        found = true;
        break;
      }
    }
    assertTrue("Field '" + fieldName + "' must exist in ADBC schema", found);
  }
}
