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
package com.dremio.plugins.jdbc.schema;

import com.dremio.exec.record.BatchSchema;
import com.dremio.plugins.jdbc.pool.AdbcConnectionFactory;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.adbc.core.AdbcConnection;
import org.apache.arrow.adbc.core.AdbcException;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADBC-based schema discovery for JDBC data sources.
 *
 * <p>Uses {@link AdbcConnection#getObjects} and {@link AdbcConnection#getTableSchema} to enumerate
 * schemas, tables, and column types via the native ADBC driver (libpq for PostgreSQL). This is the
 * ADBC counterpart of {@link JdbcSchemaFetcher}, which uses JDBC {@link java.sql.DatabaseMetaData}.
 *
 * <p>The ADBC {@code getObjects()} call returns a nested Arrow schema:
 *
 * <pre>
 *   catalog_name: Utf8
 *   catalog_db_schemas: List&lt;Struct&gt;
 *     db_schema_name: Utf8
 *     db_schema_tables: List&lt;Struct&gt;
 *       table_name: Utf8
 *       table_type: Utf8
 *       ...
 * </pre>
 *
 * This class parses that nested structure to extract schema and table names.
 */
public class AdbcSchemaFetcher {

  private static final Logger logger = LoggerFactory.getLogger(AdbcSchemaFetcher.class);

  private final AdbcConnectionFactory factory;

  /**
   * Creates a new ADBC schema fetcher.
   *
   * @param factory the ADBC connection factory for obtaining connections
   */
  public AdbcSchemaFetcher(AdbcConnectionFactory factory) {
    this.factory = factory;
  }

  /**
   * Returns the names of all non-system schemas in the remote database.
   *
   * @return list of schema names; never null
   * @throws AdbcException if the remote database reports an error
   * @throws InterruptedException if the thread is interrupted while acquiring a connection
   */
  public List<String> listSchemas() throws AdbcException, InterruptedException {
    List<String> schemas = new ArrayList<>();
    try (AdbcConnection conn = factory.openConnection()) {
      try (ArrowReader reader =
          conn.getObjects(
              AdbcConnection.GetObjectsDepth.DB_SCHEMAS, null, null, null, null, null)) {
        while (reader.loadNextBatch()) {
          VectorSchemaRoot root = reader.getVectorSchemaRoot();
          extractSchemaNames(root, schemas);
        }
      }
    } catch (AdbcException | InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to list schemas via ADBC", e);
    }
    return schemas;
  }

  /**
   * Returns the table names (TABLE and VIEW) within the given schema.
   *
   * @param schemaName schema to enumerate
   * @return list of table names; never null
   * @throws AdbcException if the remote database reports an error
   * @throws InterruptedException if the thread is interrupted while acquiring a connection
   */
  public List<String> listTables(String schemaName) throws AdbcException, InterruptedException {
    List<String> tables = new ArrayList<>();
    try (AdbcConnection conn = factory.openConnection()) {
      try (ArrowReader reader =
          conn.getObjects(
              AdbcConnection.GetObjectsDepth.TABLES,
              null,
              schemaName,
              "%",
              new String[] {"TABLE", "VIEW"},
              null)) {
        while (reader.loadNextBatch()) {
          VectorSchemaRoot root = reader.getVectorSchemaRoot();
          extractTableNames(root, tables);
        }
      }
    } catch (AdbcException | InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to list tables via ADBC for schema: " + schemaName, e);
    }
    return tables;
  }

  /**
   * Determines whether a table identified by (schema, name) exists in the remote source.
   *
   * @param schemaName schema to check
   * @param tableName table to check
   * @return true if the table exists
   * @throws AdbcException if the remote database reports an error
   * @throws InterruptedException if the thread is interrupted while acquiring a connection
   */
  public boolean tableExists(String schemaName, String tableName)
      throws AdbcException, InterruptedException {
    try (AdbcConnection conn = factory.openConnection()) {
      try (ArrowReader reader =
          conn.getObjects(
              AdbcConnection.GetObjectsDepth.TABLES,
              null,
              schemaName,
              tableName,
              new String[] {"TABLE", "VIEW"},
              null)) {
        while (reader.loadNextBatch()) {
          VectorSchemaRoot root = reader.getVectorSchemaRoot();
          List<String> tables = new ArrayList<>();
          extractTableNames(root, tables);
          if (!tables.isEmpty()) {
            return true;
          }
        }
      }
    } catch (AdbcException | InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to check table existence via ADBC: " + schemaName + "." + tableName, e);
    }
    return false;
  }

  /**
   * Builds the Arrow {@link BatchSchema} for the given table using ADBC {@code getTableSchema()}.
   *
   * <p>Normalizes Arrow types to match Dremio expectations:
   *
   * <ul>
   *   <li>Strips timezone from Timestamp types (Dremio uses timezone-unaware timestamps)
   *   <li>Converts DateDay to DateMilli (Dremio uses DATEMILLI)
   * </ul>
   *
   * @param schemaName schema containing the table
   * @param tableName table whose column types to read
   * @return the Arrow schema for the table
   * @throws AdbcException if the remote database reports an error
   * @throws InterruptedException if the thread is interrupted while acquiring a connection
   */
  public BatchSchema getTableSchema(String schemaName, String tableName)
      throws AdbcException, InterruptedException {
    try (AdbcConnection conn = factory.openConnection()) {
      Schema arrowSchema = conn.getTableSchema(null, schemaName, tableName);
      List<Field> normalizedFields = new ArrayList<>();
      for (Field field : arrowSchema.getFields()) {
        normalizedFields.add(normalizeField(field));
      }
      return new BatchSchema(normalizedFields);
    } catch (AdbcException | InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to get table schema via ADBC: " + schemaName + "." + tableName, e);
    }
  }

  /**
   * Returns true if the given schema name should be excluded from listing.
   *
   * <p>Same filtering logic as {@link JdbcSchemaFetcher#isSystemSchema(String)}: filters
   * information_schema, pg_catalog, pg_toast*, pg_temp_*.
   *
   * @param schemaName the schema name to test
   * @return true if this is a system schema that should be hidden
   */
  protected boolean isSystemSchema(String schemaName) {
    if (schemaName == null) {
      return false;
    }
    String lower = schemaName.toLowerCase();
    return "information_schema".equals(lower)
        || "pg_catalog".equals(lower)
        || lower.startsWith("pg_toast")
        || lower.startsWith("pg_temp_");
  }

  // ---- Private helpers ----

  /**
   * Normalizes an Arrow field type to match Dremio expectations. Strips timezone from Timestamp
   * types and converts DateDay to DateMilli.
   */
  private Field normalizeField(Field field) {
    ArrowType type = field.getType();

    // Strip timezone from timestamps.
    if (type instanceof ArrowType.Timestamp) {
      ArrowType.Timestamp ts = (ArrowType.Timestamp) type;
      if (ts.getTimezone() != null) {
        ArrowType normalized = new ArrowType.Timestamp(ts.getUnit(), null);
        return new Field(
            field.getName(), new FieldType(field.isNullable(), normalized, null), null);
      }
    }

    // Convert DateDay to DateMilli.
    if (type instanceof ArrowType.Date) {
      ArrowType.Date d = (ArrowType.Date) type;
      if (d.getUnit() != DateUnit.MILLISECOND) {
        ArrowType normalized = new ArrowType.Date(DateUnit.MILLISECOND);
        return new Field(
            field.getName(), new FieldType(field.isNullable(), normalized, null), null);
      }
    }

    return field;
  }

  /**
   * Extracts schema names from the nested getObjects() result structure.
   *
   * <p>The structure is: catalog_name (Utf8), catalog_db_schemas (List of Struct with
   * db_schema_name). We iterate catalogs, then iterate the db_schemas list, extracting
   * db_schema_name and filtering system schemas.
   */
  private void extractSchemaNames(VectorSchemaRoot root, List<String> schemas) {
    int rowCount = root.getRowCount();
    if (rowCount == 0) {
      return;
    }

    // Find the catalog_db_schemas list vector.
    FieldVector dbSchemasVector = root.getVector("catalog_db_schemas");
    if (!(dbSchemasVector instanceof ListVector)) {
      logger.warn("Expected ListVector for catalog_db_schemas, got: {}", dbSchemasVector);
      return;
    }
    ListVector dbSchemasList = (ListVector) dbSchemasVector;

    for (int catalogIdx = 0; catalogIdx < rowCount; catalogIdx++) {
      if (dbSchemasList.isNull(catalogIdx)) {
        continue;
      }
      // Get the list of db_schema structs for this catalog.
      @SuppressWarnings("unchecked")
      List<Object> schemaStructs = (List<Object>) dbSchemasList.getObject(catalogIdx);
      if (schemaStructs == null) {
        continue;
      }

      // Access the struct vector inside the list.
      FieldVector dataVector = dbSchemasList.getDataVector();
      if (!(dataVector instanceof StructVector)) {
        logger.warn("Expected StructVector inside catalog_db_schemas list, got: {}", dataVector);
        continue;
      }
      StructVector structVector = (StructVector) dataVector;
      VarCharVector nameVector = (VarCharVector) structVector.getChild("db_schema_name");
      if (nameVector == null) {
        logger.warn("db_schema_name field not found in catalog_db_schemas struct");
        continue;
      }

      int startOffset = dbSchemasList.getOffsetBuffer().getInt(catalogIdx * 4L);
      int endOffset = dbSchemasList.getOffsetBuffer().getInt((catalogIdx + 1) * 4L);
      for (int i = startOffset; i < endOffset; i++) {
        if (!nameVector.isNull(i)) {
          String schemaName =
              new String(nameVector.get(i), java.nio.charset.StandardCharsets.UTF_8);
          if (!isSystemSchema(schemaName)) {
            schemas.add(schemaName);
          }
        }
      }
    }
  }

  /**
   * Extracts table names from the nested getObjects() result structure.
   *
   * <p>The structure is: catalog_name -> catalog_db_schemas (list) -> db_schema_name,
   * db_schema_tables (list) -> table_name, table_type. We iterate catalogs, then db_schemas, then
   * tables, extracting table names.
   */
  private void extractTableNames(VectorSchemaRoot root, List<String> tables) {
    int rowCount = root.getRowCount();
    if (rowCount == 0) {
      return;
    }

    // Find the catalog_db_schemas list vector.
    FieldVector dbSchemasVector = root.getVector("catalog_db_schemas");
    if (!(dbSchemasVector instanceof ListVector)) {
      logger.warn("Expected ListVector for catalog_db_schemas, got: {}", dbSchemasVector);
      return;
    }
    ListVector dbSchemasList = (ListVector) dbSchemasVector;

    for (int catalogIdx = 0; catalogIdx < rowCount; catalogIdx++) {
      if (dbSchemasList.isNull(catalogIdx)) {
        continue;
      }

      FieldVector schemaDataVector = dbSchemasList.getDataVector();
      if (!(schemaDataVector instanceof StructVector)) {
        continue;
      }
      StructVector schemaStructVector = (StructVector) schemaDataVector;

      int schemaStartOffset = dbSchemasList.getOffsetBuffer().getInt(catalogIdx * 4L);
      int schemaEndOffset = dbSchemasList.getOffsetBuffer().getInt((catalogIdx + 1) * 4L);

      for (int schemaIdx = schemaStartOffset; schemaIdx < schemaEndOffset; schemaIdx++) {
        // Get the db_schema_tables list from this schema struct.
        FieldVector tablesVector = schemaStructVector.getChild("db_schema_tables");
        if (!(tablesVector instanceof ListVector)) {
          continue;
        }
        ListVector tablesList = (ListVector) tablesVector;
        if (tablesList.isNull(schemaIdx)) {
          continue;
        }

        FieldVector tableDataVector = tablesList.getDataVector();
        if (!(tableDataVector instanceof StructVector)) {
          continue;
        }
        StructVector tableStructVector = (StructVector) tableDataVector;
        VarCharVector tableNameVector = (VarCharVector) tableStructVector.getChild("table_name");
        if (tableNameVector == null) {
          continue;
        }

        int tableStartOffset = tablesList.getOffsetBuffer().getInt(schemaIdx * 4L);
        int tableEndOffset = tablesList.getOffsetBuffer().getInt((schemaIdx + 1) * 4L);
        for (int tableIdx = tableStartOffset; tableIdx < tableEndOffset; tableIdx++) {
          if (!tableNameVector.isNull(tableIdx)) {
            String tableName =
                new String(tableNameVector.get(tableIdx), java.nio.charset.StandardCharsets.UTF_8);
            tables.add(tableName);
          }
        }
      }
    }
  }
}
