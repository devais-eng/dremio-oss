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

import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.plugins.jdbc.schema.JdbcSchemaFetcher;
import java.sql.Types;
import java.util.Set;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;

/**
 * Oracle-specific schema fetcher that overrides JDBC type mapping to correctly handle Oracle-native
 * types not covered by the standard Arrow JDBC adapter.
 *
 * <p>Mapped types include:
 *
 * <ul>
 *   <li>BINARY_FLOAT (jdbcType 100) → FLOAT4 (single-precision floating point)
 *   <li>BINARY_DOUBLE (jdbcType 101) → FLOAT8 (double-precision floating point)
 *   <li>CLOB, NCLOB → VARCHAR
 *   <li>NVARCHAR (NVARCHAR2), NCHAR → VARCHAR
 *   <li>NUMBER with scale = -127 (Oracle FLOAT sentinel) → DOUBLE
 *   <li>NUMBER with precision = 0 (bare NUMBER) → DOUBLE
 *   <li>TIMESTAMP WITH TIME ZONE → TIMESTAMP (milliseconds, no TZ)
 * </ul>
 *
 * <p>Seventeen Oracle system schemas are excluded from dataset listing.
 */
public class OracleSchemaFetcher extends JdbcSchemaFetcher {

  /**
   * Oracle JDBC type code for BINARY_FLOAT (single-precision IEEE 754). Not defined in {@link
   * java.sql.Types}; specific to the Oracle JDBC driver. The driver may report this as 100
   * (OracleTypes.BINARY_FLOAT) or -100
   */
  private static final int ORACLE_BINARY_FLOAT_TYPE = 100;

  /**
   * Oracle JDBC type code for BINARY_DOUBLE (double-precision IEEE 754). Not defined in {@link
   * java.sql.Types}; specific to the Oracle JDBC driver.
   */
  private static final int ORACLE_BINARY_DOUBLE_TYPE = 101;

  /**
   * Oracle JDBC type code for TIMESTAMP WITH TIME ZONE. The Oracle driver uses -101
   * (OracleTypes.TIMESTAMPTZ) instead of the standard {@link
   * java.sql.Types#TIMESTAMP_WITH_TIMEZONE} (2014).
   */
  private static final int ORACLE_TIMESTAMPTZ_TYPE = -101;

  /**
   * Oracle JDBC type code for TIMESTAMP WITH LOCAL TIME ZONE. The Oracle driver uses -102
   * (OracleTypes.TIMESTAMPLTZ) instead of standard {@link java.sql.Types} codes.
   */
  private static final int ORACLE_TIMESTAMPLTZ_TYPE = -102;

  /**
   * Oracle JDBC type code for INTERVAL YEAR TO MONTH. Not defined in standard {@link
   * java.sql.Types}; specific to the Oracle JDBC driver.
   */
  private static final int ORACLE_INTERVAL_YM_TYPE = -103;

  /**
   * Oracle JDBC type code for INTERVAL DAY TO SECOND. Not defined in standard {@link
   * java.sql.Types}; specific to the Oracle JDBC driver.
   */
  private static final int ORACLE_INTERVAL_DS_TYPE = -104;

  /**
   * Scale value Oracle uses to represent FLOAT columns (e.g., {@code FLOAT(126)}) in JDBC metadata.
   * When scale == -127, the NUMBER column is a floating-point FLOAT type.
   */
  private static final int ORACLE_FLOAT_SCALE_SENTINEL = -127;

  /**
   * Oracle system schemas to exclude from dataset listing.
   *
   * <p>These schemas contain internal Oracle database objects and should never be surfaced to end
   * users as queryable datasets.
   */
  private static final Set<String> ORACLE_SYSTEM_SCHEMAS =
      Set.of(
          "sys",
          "system",
          "ctxsys",
          "mdsys",
          "xdb",
          "outln",
          "xs$null",
          "flows_files",
          "dvsys",
          "audsys",
          "dbsnmp",
          "gsmadmin_internal",
          "lbacsys",
          "orddata",
          "ordsys",
          "wmsys",
          "appqossys",
          "dbsfwuser",
          "olapsys");

  /**
   * Creates a new schema fetcher backed by the supplied connection pool.
   *
   * @param pool the pool to use when acquiring connections for schema discovery
   */
  public OracleSchemaFetcher(JdbcConnectionPool pool) {
    super(pool);
  }

  /**
   * Maps a JDBC type to the corresponding Arrow type for Oracle columns.
   *
   * <p>Oracle-specific type codes and semantics handled:
   *
   * <ul>
   *   <li>jdbcType 100 (BINARY_FLOAT) → single-precision float
   *   <li>jdbcType 101 (BINARY_DOUBLE) → double-precision float
   *   <li>CLOB / NCLOB → UTF-8 string
   *   <li>NVARCHAR / NCHAR → UTF-8 string
   *   <li>NUMERIC with scale = -127 → double (Oracle FLOAT sentinel)
   *   <li>NUMERIC with precision = 0 → double (bare NUMBER without precision)
   *   <li>TIMESTAMP_WITH_TIMEZONE → millisecond timestamp (timezone dropped)
   *   <li>TIMESTAMP WITH LOCAL TIME ZONE (jdbcType -102) → millisecond timestamp (TZ dropped)
   *   <li>INTERVAL YEAR TO MONTH (jdbcType -103) → Interval(YEAR_MONTH)
   *   <li>INTERVAL DAY TO SECOND (jdbcType -104) → Interval(DAY_TIME)
   * </ul>
   *
   * @param jdbcType the {@link java.sql.Types} constant (or Oracle-specific code)
   * @param typeName the Oracle-specific type name
   * @param precision the column precision (COLUMN_SIZE)
   * @param scale the column scale (DECIMAL_DIGITS)
   * @return the Arrow type to use for this column
   */
  @Override
  protected ArrowType mapJdbcType(int jdbcType, String typeName, int precision, int scale) {
    // Type-name based checks first — typeName is more specific than jdbcType codes.
    // Oracle FLOAT (NUMBER-based, 126 binary digits) shares the same JDBC type code as
    // BINARY_FLOAT in some driver versions, so typeName is the only reliable discriminator.
    if (typeName != null) {
      String lower = typeName.toLowerCase();
      switch (lower) {
        case "binary_float":
          return new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
        case "binary_double":
          return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        case "float":
          // Oracle FLOAT is NUMBER-based with up to 126 binary digits — map to DOUBLE
          return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        default:
          break;
      }
    }
    // Oracle BINARY_FLOAT — single-precision IEEE 754 (fallback for null typeName)
    if (jdbcType == ORACLE_BINARY_FLOAT_TYPE) {
      return new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
    }
    // Oracle BINARY_DOUBLE — double-precision IEEE 754 (fallback for null typeName)
    if (jdbcType == ORACLE_BINARY_DOUBLE_TYPE) {
      return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    }
    // Oracle TIMESTAMP WITH TIME ZONE — driver uses -101 instead of standard
    // Types.TIMESTAMP_WITH_TIMEZONE
    if (jdbcType == ORACLE_TIMESTAMPTZ_TYPE) {
      return new ArrowType.Timestamp(TimeUnit.MILLISECOND, null);
    }
    // CLOB / NCLOB — large character objects → VARCHAR
    if (jdbcType == Types.CLOB || jdbcType == Types.NCLOB) {
      return new ArrowType.Utf8();
    }
    // NVARCHAR2 / NCHAR — national character strings → VARCHAR
    if (jdbcType == Types.NVARCHAR || jdbcType == Types.NCHAR) {
      return new ArrowType.Utf8();
    }
    // Oracle FLOAT sentinel: NUMERIC with scale = -127 means it is a floating-point FLOAT type
    if (jdbcType == Types.NUMERIC && scale == ORACLE_FLOAT_SCALE_SENTINEL) {
      return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    }
    // Bare NUMBER without precision (precision = 0) → DOUBLE to avoid DECIMAL(0,0) errors
    if (jdbcType == Types.NUMERIC && precision == 0) {
      return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    }
    // TIMESTAMP WITH TIME ZONE — drop timezone, return as millisecond timestamp
    if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
      return new ArrowType.Timestamp(TimeUnit.MILLISECOND, null);
    }
    // Oracle TIMESTAMP WITH LOCAL TIME ZONE — driver uses -102 (OracleTypes.TIMESTAMPLTZ)
    if (jdbcType == ORACLE_TIMESTAMPLTZ_TYPE) {
      return new ArrowType.Timestamp(TimeUnit.MILLISECOND, null);
    }
    // Oracle INTERVAL YEAR TO MONTH → Arrow Interval(YEAR_MONTH)
    // Stored as total months (int32) in IntervalYearVector.
    if (jdbcType == ORACLE_INTERVAL_YM_TYPE) {
      return new ArrowType.Interval(org.apache.arrow.vector.types.IntervalUnit.YEAR_MONTH);
    }
    // Oracle INTERVAL DAY TO SECOND → Arrow Interval(DAY_TIME)
    // Stored as (days, milliseconds) pair in IntervalDayVector.
    if (jdbcType == ORACLE_INTERVAL_DS_TYPE) {
      return new ArrowType.Interval(org.apache.arrow.vector.types.IntervalUnit.DAY_TIME);
    }
    return super.mapJdbcType(jdbcType, typeName, precision, scale);
  }

  /**
   * Returns true if the schema should be excluded from listing.
   *
   * <p>This implementation replaces the base class check entirely (does NOT call {@code
   * super.isSystemSchema()}) because the base class filters PostgreSQL-specific system schemas that
   * do not apply to Oracle. The full Oracle system schema set is defined in {@link
   * #ORACLE_SYSTEM_SCHEMAS}.
   *
   * @param schemaName the schema name to test
   * @return true if this is an Oracle system schema that should be hidden
   */
  @Override
  protected boolean isSystemSchema(String schemaName) {
    return schemaName != null && ORACLE_SYSTEM_SCHEMAS.contains(schemaName.toLowerCase());
  }
}
