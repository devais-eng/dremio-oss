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

import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.plugins.jdbc.schema.JdbcSchemaFetcher;
import java.sql.Types;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;

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
