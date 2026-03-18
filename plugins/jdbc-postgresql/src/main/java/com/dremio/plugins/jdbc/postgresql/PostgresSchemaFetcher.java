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

import com.dremio.exec.record.BatchSchema;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.plugins.jdbc.schema.JdbcSchemaFetcher;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

/**
 * PostgreSQL-specific schema fetcher that overrides JDBC type mapping to correctly handle
 * PostgreSQL-native types not covered by the standard Arrow JDBC adapter.
 *
 * <p>Mapped types include:
 *
 * <ul>
 *   <li>UUID, JSONB, JSON, MONEY, INTERVAL, CIDR, INET, MACADDR, MACADDR8 → VARCHAR
 *   <li>HSTORE, TSVECTOR, TSQUERY → VARCHAR
 *   <li>Array types (prefix {@code _} or {@link Types#ARRAY}) → VARCHAR
 *   <li>Unconstrained NUMERIC (precision = 0) → DOUBLE
 *   <li>vector(N) (pgvector) → LIST&lt;FLOAT4&gt;
 * </ul>
 *
 * <p>System schemas excluded beyond the base class defaults include {@code pg_internal}.
 */
public class PostgresSchemaFetcher extends JdbcSchemaFetcher {

  /**
   * Creates a new schema fetcher backed by the supplied connection pool.
   *
   * @param pool the pool to use when acquiring connections for schema discovery
   */
  public PostgresSchemaFetcher(JdbcConnectionPool pool) {
    super(pool);
  }

  /**
   * Maps a JDBC type to the corresponding Arrow type for PostgreSQL columns.
   *
   * <p>PostgreSQL-specific types are mapped to {@link ArrowType.Utf8} (VARCHAR) to ensure safe data
   * transport. Array types — recognised either by the JDBC {@link Types#ARRAY} code or by type
   * names starting with {@code _} (the PostgreSQL convention) — are also mapped to VARCHAR in v1.5.
   * Unconstrained NUMERIC columns (precision = 0) are mapped to DOUBLE to avoid {@code DECIMAL(0,
   * 0)} errors that some drivers report.
   *
   * @param jdbcType the {@link java.sql.Types} constant
   * @param typeName the PostgreSQL-specific type name (may start with {@code _} for arrays)
   * @param precision the column precision (COLUMN_SIZE)
   * @param scale the column scale (DECIMAL_DIGITS)
   * @return the Arrow type to use for this column
   */
  @Override
  protected ArrowType mapJdbcType(int jdbcType, String typeName, int precision, int scale) {
    if (typeName != null) {
      String lower = typeName.toLowerCase();
      switch (lower) {
        case "uuid":
        case "jsonb":
        case "json":
        case "money":
        case "interval":
        case "cidr":
        case "inet":
        case "macaddr":
        case "macaddr8":
        case "hstore":
        case "tsvector":
        case "tsquery":
          return new ArrowType.Utf8();
        case "vector": // pgvector extension: vector(N) — mapped to LIST<FLOAT4>
          return ArrowType.List.INSTANCE;
        default:
          break;
      }
      // PostgreSQL array types use underscore prefix (e.g. _int4, _text, _varchar)
      if (lower.startsWith("_")) {
        return new ArrowType.Utf8();
      }
    }
    // JDBC array type code
    if (jdbcType == Types.ARRAY) {
      return new ArrowType.Utf8();
    }
    // Unconstrained NUMERIC (precision=0) — avoids DECIMAL(0,0) errors
    if (jdbcType == Types.NUMERIC && precision == 0) {
      return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    }
    return super.mapJdbcType(jdbcType, typeName, precision, scale);
  }

  /**
   * Builds the Arrow {@link BatchSchema} for the given table, intercepting the
   * {@link ArrowType#List} sentinel returned by {@link #mapJdbcType} for {@code vector} columns
   * and constructing a proper {@code LIST<FLOAT4>} field with the required child.
   *
   * <p>The base class creates {@code new Field(columnName, fieldType, null)} for every column. For
   * {@code ArrowType.List} we must pass a non-null children list — a single {@code $data$} child
   * field of type {@code FloatingPoint(SINGLE)}. This override handles that case and falls back to
   * the base-class logic for all other column types.
   *
   * @param schemaName schema containing the table
   * @param tableName table whose column types to read
   * @return the Arrow schema for the table
   * @throws SQLException if the remote database reports an error
   */
  @Override
  public BatchSchema getTableSchema(String schemaName, String tableName) throws SQLException {
    List<Field> fields = new ArrayList<>();
    try (Connection conn = getPool().getConnection()) {
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

          Field field;
          if (arrowType == ArrowType.List.INSTANCE) {
            // pgvector: build LIST<FLOAT4> with the required child field
            Field float4Child =
                new Field(
                    "$data$",
                    FieldType.nullable(
                        new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
                    null);
            field =
                new Field(
                    columnName,
                    new FieldType(nullable, ArrowType.List.INSTANCE, null),
                    Collections.singletonList(float4Child));
          } else {
            field = new Field(columnName, new FieldType(nullable, arrowType, null), null);
          }
          fields.add(field);
        }
      }
    }
    return new BatchSchema(fields);
  }

  /**
   * Returns true if the schema should be excluded from listing.
   *
   * <p>In addition to the schemas filtered by the base class (e.g. {@code information_schema},
   * {@code pg_catalog}, {@code pg_toast_*}, {@code pg_temp_*}), this implementation also excludes
   * {@code pg_internal}.
   *
   * @param schemaName the schema name to test
   * @return true if this is a system schema that should be hidden
   */
  @Override
  protected boolean isSystemSchema(String schemaName) {
    if (super.isSystemSchema(schemaName)) {
      return true;
    }
    if (schemaName == null) {
      return false;
    }
    return "pg_internal".equalsIgnoreCase(schemaName);
  }
}
