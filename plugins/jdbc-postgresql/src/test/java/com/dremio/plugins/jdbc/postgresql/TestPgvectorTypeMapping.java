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
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Integration tests validating pgvector {@code vector(N)} column type mapping to
 * {@code LIST<FLOAT4>} (Arrow {@code ArrowType.List} with a {@code FloatingPoint(SINGLE)} child).
 *
 * <p>Tests run against a real {@code pgvector/pgvector:pg16} container via TestContainers. The
 * pgvector extension must be enabled before creating vector columns ({@code CREATE EXTENSION IF NOT
 * EXISTS vector}).
 *
 * <p>Three tests are provided:
 * <ol>
 *   <li>{@link #testSchemaDiscoveryVector} — {@code embedding vector(3)} maps to
 *       {@code ArrowType.List} with {@code FloatingPoint(SINGLE)} child
 *   <li>{@link #testVectorValueRoundtrip} — pgjdbc returns the text format {@code [x,y,z]}
 *       for vector columns via {@link ResultSet#getString}
 *   <li>{@link #testVectorNullHandling} — a NULL vector row does not change the schema type
 * </ol>
 */
public class TestPgvectorTypeMapping {

  @ClassRule public static final DremioPostgresContainer PG = PostgresTestContainer.PG;

  private static JdbcConnectionPool pool;
  private static PostgresSchemaFetcher schemaFetcher;

  @BeforeClass
  public static void setUpClass() throws Exception {
    pool = PostgresTestContainer.createPool();
    schemaFetcher = new PostgresSchemaFetcher(pool);

    // MUST be the first DDL statement — vector type does not exist without the extension
    PostgresTestContainer.executeSql("CREATE EXTENSION IF NOT EXISTS vector");

    PostgresTestContainer.executeSql(
        "CREATE TABLE IF NOT EXISTS vector_test ("
            + "  id SERIAL PRIMARY KEY,"
            + "  embedding vector(3),"
            + "  label TEXT"
            + ")");

    PostgresTestContainer.executeSql(
        "INSERT INTO vector_test (embedding, label) VALUES"
            + " ('[1.0,2.0,3.0]', 'row1'),"
            + " ('[0.1,0.2,0.3]', 'row2')");
  }

  @AfterClass
  public static void tearDownClass() {
    if (pool != null) {
      pool.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Schema discovery test
  // ---------------------------------------------------------------------------

  /**
   * Validates that a {@code vector(3)} column is discovered as {@code LIST<FLOAT4>}:
   * <ul>
   *   <li>Field type class is {@link ArrowType.List}
   *   <li>Exactly one child field exists
   *   <li>Child field type is {@link ArrowType.FloatingPoint} with precision {@code SINGLE}
   *   <li>Field {@code label} (TEXT) is still {@link ArrowType.Utf8}
   * </ul>
   */
  @Test
  public void testSchemaDiscoveryVector() throws Exception {
    BatchSchema schema = schemaFetcher.getTableSchema("public", "vector_test");
    assertNotNull("Schema must not be null", schema);

    // embedding must be a LIST
    Field embeddingField = findField(schema, "embedding");
    assertEquals(
        "vector(3) column must map to ArrowType.List",
        ArrowType.List.class,
        embeddingField.getType().getClass());

    // Must have exactly one child field
    assertEquals(
        "LIST field must have exactly one child",
        1,
        embeddingField.getChildren().size());

    // Child must be FloatingPoint(SINGLE)
    Field child = embeddingField.getChildren().get(0);
    assertEquals(
        "Child field must be FloatingPoint",
        ArrowType.FloatingPoint.class,
        child.getType().getClass());
    ArrowType.FloatingPoint fpType = (ArrowType.FloatingPoint) child.getType();
    assertEquals(
        "Child FloatingPoint precision must be SINGLE (float32)",
        FloatingPointPrecision.SINGLE,
        fpType.getPrecision());

    // label must still be Utf8
    Field labelField = findField(schema, "label");
    assertEquals(
        "TEXT column must map to ArrowType.Utf8",
        ArrowType.Utf8.class,
        labelField.getType().getClass());
  }

  // ---------------------------------------------------------------------------
  // Value roundtrip test
  // ---------------------------------------------------------------------------

  /**
   * Validates that pgjdbc returns the text format {@code [x,y,z]} for vector columns via
   * {@link ResultSet#getString}, and that the values can be parsed as floats.
   */
  @Test
  public void testVectorValueRoundtrip() throws Exception {
    try (Connection conn = pool.getConnection();
        java.sql.PreparedStatement ps =
            conn.prepareStatement("SELECT embedding FROM vector_test ORDER BY id");
        ResultSet rs = ps.executeQuery()) {

      // Row 1: '[1.0,2.0,3.0]'
      assertTrue("Expected at least one row", rs.next());
      String vectorText = rs.getString("embedding");
      assertNotNull("embedding must not be null", vectorText);
      assertTrue("Must start with '[': " + vectorText, vectorText.startsWith("["));
      assertTrue("Must end with ']': " + vectorText, vectorText.endsWith("]"));

      String inner = vectorText.substring(1, vectorText.length() - 1);
      String[] parts = inner.split(",");
      assertEquals("Must have 3 elements", 3, parts.length);

      float v0 = Float.parseFloat(parts[0].trim());
      float v1 = Float.parseFloat(parts[1].trim());
      float v2 = Float.parseFloat(parts[2].trim());
      assertTrue("Element 0 must be ~1.0", Math.abs(v0 - 1.0f) < 0.001f);
      assertTrue("Element 1 must be ~2.0", Math.abs(v1 - 2.0f) < 0.001f);
      assertTrue("Element 2 must be ~3.0", Math.abs(v2 - 3.0f) < 0.001f);
    }
  }

  // ---------------------------------------------------------------------------
  // Null handling test
  // ---------------------------------------------------------------------------

  /**
   * Validates that inserting a NULL vector value does not affect schema discovery — the
   * {@code embedding} field is still reported as {@code ArrowType.List}.
   */
  @Test
  public void testVectorNullHandling() throws Exception {
    PostgresTestContainer.executeSql(
        "INSERT INTO vector_test (embedding, label) VALUES (NULL, 'null-row')");

    BatchSchema schema = schemaFetcher.getTableSchema("public", "vector_test");
    assertNotNull("Schema must not be null after NULL insert", schema);

    Field embeddingField = findField(schema, "embedding");
    assertEquals(
        "vector(3) column must still map to ArrowType.List after NULL insert",
        ArrowType.List.class,
        embeddingField.getType().getClass());
  }

  // ---------------------------------------------------------------------------
  // Helper
  // ---------------------------------------------------------------------------

  /**
   * Finds a field by name (case-insensitive) in the schema, throwing {@link AssertionError} if not
   * found.
   *
   * @param schema the schema to search
   * @param name the field name to find
   * @return the matching field
   */
  private static Field findField(BatchSchema schema, String name) {
    return schema
        .findFieldIgnoreCase(name)
        .orElseThrow(
            () -> new AssertionError("Field '" + name + "' not found in schema: " + schema));
  }
}
