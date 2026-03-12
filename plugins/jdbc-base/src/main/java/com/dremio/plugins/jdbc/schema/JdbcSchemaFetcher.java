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
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.adapter.jdbc.JdbcFieldInfo;
import org.apache.arrow.adapter.jdbc.JdbcToArrowUtils;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates all {@link DatabaseMetaData} interactions for a JDBC data source.
 *
 * <p>Used by {@link com.dremio.plugins.jdbc.JdbcStoragePlugin} to enumerate schemas, tables, and
 * column schemas. Concrete connector subclasses can override {@link #isSystemSchema(String)} and
 * {@link #mapJdbcType(int, String, int, int)} to customise behaviour for a specific database.
 */
public class JdbcSchemaFetcher {

  private static final Logger logger = LoggerFactory.getLogger(JdbcSchemaFetcher.class);

  private final JdbcConnectionPool pool;

  /**
   * Creates a new schema fetcher backed by the supplied connection pool.
   *
   * @param pool the pool to use when acquiring connections
   */
  public JdbcSchemaFetcher(JdbcConnectionPool pool) {
    this.pool = pool;
  }

  /**
   * Returns the names of all non-system schemas in the remote database.
   *
   * @return list of schema names; never null
   * @throws SQLException if the remote database reports an error
   */
  public List<String> listSchemas() throws SQLException {
    List<String> schemas = new ArrayList<>();
    try (Connection conn = pool.getConnection()) {
      DatabaseMetaData meta = conn.getMetaData();
      try (ResultSet rs = meta.getSchemas()) {
        while (rs.next()) {
          String schemaName = rs.getString("TABLE_SCHEM");
          if (!isSystemSchema(schemaName)) {
            schemas.add(schemaName);
          }
        }
      }
    }
    return schemas;
  }

  /**
   * Returns the table names (TABLE and VIEW) within the given schema.
   *
   * @param schemaName schema to enumerate
   * @return list of table names; never null
   * @throws SQLException if the remote database reports an error
   */
  public List<String> listTables(String schemaName) throws SQLException {
    List<String> tables = new ArrayList<>();
    try (Connection conn = pool.getConnection()) {
      DatabaseMetaData meta = conn.getMetaData();
      try (ResultSet rs =
          meta.getTables(null, schemaName, "%", new String[] {"TABLE", "VIEW"})) {
        while (rs.next()) {
          tables.add(rs.getString("TABLE_NAME"));
        }
      }
    }
    return tables;
  }

  /**
   * Determines whether a table identified by (schema, name) actually exists in the remote source.
   *
   * @param schemaName schema to check
   * @param tableName table to check
   * @return true if the table exists
   * @throws SQLException if the remote database reports an error
   */
  public boolean tableExists(String schemaName, String tableName) throws SQLException {
    try (Connection conn = pool.getConnection()) {
      DatabaseMetaData meta = conn.getMetaData();
      try (ResultSet rs =
          meta.getTables(null, schemaName, tableName, new String[] {"TABLE", "VIEW"})) {
        return rs.next();
      }
    }
  }

  /**
   * Builds the Arrow {@link BatchSchema} for the given table by reading its column metadata from
   * {@link DatabaseMetaData#getColumns}.
   *
   * @param schemaName schema containing the table
   * @param tableName table whose column types to read
   * @return the Arrow schema for the table
   * @throws SQLException if the remote database reports an error
   */
  public BatchSchema getTableSchema(String schemaName, String tableName) throws SQLException {
    List<Field> fields = new ArrayList<>();
    try (Connection conn = pool.getConnection()) {
      DatabaseMetaData meta = conn.getMetaData();
      try (ResultSet rs = meta.getColumns(null, schemaName, tableName, "%")) {
        while (rs.next()) {
          String columnName = rs.getString("COLUMN_NAME");
          int jdbcType = rs.getInt("DATA_TYPE");
          String typeName = rs.getString("TYPE_NAME");
          int precision = rs.getInt("COLUMN_SIZE");
          int scale = rs.getInt("DECIMAL_DIGITS");
          boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));

          ArrowType arrowType = mapJdbcType(jdbcType, typeName, precision, scale);
          FieldType fieldType = new FieldType(nullable, arrowType, null);
          fields.add(new Field(columnName, fieldType, null));
        }
      }
    }
    return new BatchSchema(fields);
  }

  /**
   * Maps a JDBC type code to the corresponding Arrow type.
   *
   * <p>The default implementation delegates to {@link JdbcToArrowUtils#getArrowTypeFromJdbcType}
   * using {@link JdbcFieldInfo}. Concrete connectors can override this method to handle
   * database-specific type codes or names.
   *
   * @param jdbcType the {@link java.sql.Types} constant
   * @param typeName the database-specific type name
   * @param precision the column precision (COLUMN_SIZE)
   * @param scale the column scale (DECIMAL_DIGITS)
   * @return the Arrow type to use for this column
   */
  protected ArrowType mapJdbcType(int jdbcType, String typeName, int precision, int scale) {
    JdbcFieldInfo fieldInfo = new JdbcFieldInfo(jdbcType, precision, scale);
    ArrowType mapped =
        JdbcToArrowUtils.getArrowTypeFromJdbcType(fieldInfo, JdbcToArrowUtils.getUtcCalendar());
    if (mapped == null) {
      // Fall back to varchar for unknown types.
      logger.warn(
          "No Arrow mapping for JDBC type {} ({}); falling back to VARCHAR", jdbcType, typeName);
      return new ArrowType.Utf8();
    }
    return mapped;
  }

  /**
   * Returns true if the given schema name should be excluded from listing.
   *
   * <p>Concrete connectors can override to filter additional system/internal schemas.
   *
   * @param schemaName the schema name to test
   * @return true if this is a system schema that should be hidden
   */
  protected boolean isSystemSchema(String schemaName) {
    if (schemaName == null) {
      return false;
    }
    String lower = schemaName.toLowerCase();
    return lower.equals("information_schema")
        || lower.equals("pg_catalog")
        || lower.startsWith("pg_toast")
        || lower.startsWith("pg_temp_");
  }
}
