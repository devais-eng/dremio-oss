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
package com.dremio.plugins.jdbc.planning;

import java.util.List;

/**
 * Utility that replaces {@code ?} placeholders in a SQL string with properly-escaped SQL literal
 * representations of {@link BindParam} values.
 *
 * <p>This is used by the ADBC execution path to avoid the PostgreSQL Extended Query Protocol
 * (parse/bind/execute). When bind parameters are set via {@code AdbcStatement.bind()}, the ADBC PG
 * driver uses the Extended Query Protocol, which cannot use the faster COPY binary path. By
 * inlining literals directly into the SQL string, the driver uses the simple query protocol,
 * routing through the fast COPY binary path (ADBC-02).
 *
 * <p><b>Security note:</b> String values are escaped using SQL standard single-quote doubling
 * ({@code '} → {@code ''}). Backslashes are NOT escaped because PostgreSQL uses
 * {@code standard_conforming_strings=on} by default since 9.1, meaning backslash is not a special
 * character in standard {@code '...'} string literals. Numeric and boolean types are inlined
 * directly as their string representation — no injection risk exists there.
 *
 * <p>The JDBC execution path ({@link com.dremio.plugins.jdbc.reader.JdbcRecordReader}) is
 * completely unaffected — it continues to use {@code PreparedStatement} with {@code ?} bind
 * parameters.
 */
public final class LiteralInliner {

  private LiteralInliner() {
    // utility class — not instantiable
  }

  /**
   * Replaces each {@code ?} placeholder in the SQL with the corresponding {@link BindParam}'s
   * properly-escaped SQL literal representation.
   *
   * <p>If {@code params} is null or empty, the original SQL is returned unchanged.
   *
   * @param sql the SQL string with {@code ?} placeholders
   * @param params the bind parameters to inline; must have at most as many elements as there are
   *     {@code ?} occurrences in {@code sql}
   * @return the SQL string with all {@code ?} placeholders replaced by inlined SQL literals
   */
  public static String inlineBindParams(String sql, List<BindParam> params) {
    if (params == null || params.isEmpty()) {
      return sql;
    }

    StringBuilder sb = new StringBuilder(sql.length() + params.size() * 8);
    int paramIdx = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '?' && paramIdx < params.size()) {
        sb.append(toSqlLiteral(params.get(paramIdx++)));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Converts a single {@link BindParam} to its SQL literal string representation.
   *
   * <p>Package-visible for unit testing.
   *
   * @param param the bind parameter to convert
   * @return the SQL literal string (e.g. {@code 42}, {@code 'hello'}, {@code DATE '2024-01-15'})
   */
  static String toSqlLiteral(BindParam param) {
    Object value = param.getValue();

    // NULL literal — SQL keyword, safe for any type.
    if (value == null) {
      return "NULL";
    }

    org.apache.calcite.sql.type.SqlTypeName typeName = param.getTypeName();
    if (typeName == null) {
      // Unknown type — safe string fallback with escaping.
      return escapedString(value.toString());
    }

    switch (typeName) {
      case TINYINT:
      case SMALLINT:
      case INTEGER:
      case BIGINT:
        // Integer numerics — raw representation, no injection risk.
        return value.toString();

      case FLOAT:
      case REAL:
      case DOUBLE:
        // Floating-point numerics — raw representation.
        return value.toString();

      case DECIMAL:
        // Decimal/BigDecimal — raw representation, no injection risk.
        return value.toString();

      case BOOLEAN:
        // Boolean — SQL keywords TRUE / FALSE.
        if (value instanceof Boolean) {
          return ((Boolean) value) ? "TRUE" : "FALSE";
        }
        return value.toString().equalsIgnoreCase("true") ? "TRUE" : "FALSE";

      case VARCHAR:
      case CHAR:
        // String — single-quote escape only (no backslash escaping per standard_conforming_strings).
        return escapedString(value.toString());

      case DATE:
        // Stored as epoch millis (Long) — format as DATE 'YYYY-MM-DD'.
        return "DATE '" + new java.sql.Date(((Number) value).longValue()).toString() + "'";

      case TIME:
        // Stored as epoch millis (Long) — format as TIME 'HH:mm:ss'.
        return "TIME '" + new java.sql.Time(((Number) value).longValue()).toString() + "'";

      case TIMESTAMP:
        // Stored as epoch millis (Long) — format as TIMESTAMP 'YYYY-MM-DD HH:mm:ss.SSS'.
        return "TIMESTAMP '"
            + new java.sql.Timestamp(((Number) value).longValue()).toString()
            + "'";

      default:
        // Safe fallback for unknown types — treat as string with escaping.
        return escapedString(value.toString());
    }
  }

  /**
   * Wraps a string value in SQL single quotes, escaping any embedded single quotes by doubling
   * them ({@code '} → {@code ''}).
   *
   * <p>Backslashes are NOT escaped. PostgreSQL uses {@code standard_conforming_strings=on} by
   * default since 9.1, meaning backslash has no special meaning in standard {@code '...'} string
   * literals.
   *
   * @param s the raw string value
   * @return the SQL-escaped string literal including surrounding single quotes
   */
  private static String escapedString(String s) {
    // Count single quotes to compute the exact capacity needed.
    int quoteCount = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\'') {
        quoteCount++;
      }
    }
    // Capacity: 2 surrounding quotes + each embedded ' becomes '' (quoteCount extra chars).
    StringBuilder sb = new StringBuilder(s.length() + 2 + quoteCount);
    sb.append('\'');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\'') {
        sb.append("''"); // double the single quote
      } else {
        sb.append(c);
      }
    }
    sb.append('\'');
    return sb.toString();
  }
}
