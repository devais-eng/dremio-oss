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

import com.dremio.common.expression.SchemaPath;
import java.util.List;

/**
 * Constructs SQL SELECT statements for JDBC pushdown queries.
 *
 * <p>Assembles a SQL string from individual pushdown components:
 * <ul>
 *   <li>Column projection (SELECT list)</li>
 *   <li>Table reference (schema.table with double-quoted identifiers)</li>
 *   <li>Optional WHERE clause from a pushed-down filter</li>
 *   <li>Optional LIMIT clause from a pushed-down fetch limit</li>
 * </ul>
 *
 * <p>Concrete JDBC connectors may subclass and override {@link #buildSql} to
 * adapt the SQL dialect (e.g. Oracle uses {@code FETCH FIRST n ROWS ONLY}
 * instead of {@code LIMIT}).
 */
public class SqlBuilder {

  /**
   * Wraps an identifier in double quotes and escapes any embedded double quotes
   * per the SQL standard ({@code "} → {@code ""}).
   *
   * @param id the identifier to quote
   * @return the double-quoted identifier string
   */
  protected String quoteIdentifier(String id) {
    return "\"" + id.replace("\"", "\"\"") + "\"";
  }

  /**
   * Builds a SQL SELECT statement from the provided pushdown components.
   *
   * @param schemaName       remote schema name (double-quoted in output)
   * @param tableName        remote table name (double-quoted in output)
   * @param projectedColumns columns to project; if null or empty, {@code SELECT *} is used
   * @param whereClause      SQL WHERE expression; appended as-is when non-null
   * @param limit            row limit; appended as {@code LIMIT N} when non-null
   * @return syntactically complete SQL SELECT string
   */
  public String buildSql(
      String schemaName,
      String tableName,
      List<SchemaPath> projectedColumns,
      String whereClause,
      Integer limit) {

    StringBuilder sb = new StringBuilder();

    // SELECT list
    if (projectedColumns == null || projectedColumns.isEmpty()) {
      sb.append("SELECT *");
    } else {
      sb.append("SELECT ");
      boolean first = true;
      for (SchemaPath col : projectedColumns) {
        if (!first) {
          sb.append(", ");
        }
        sb.append(quoteIdentifier(col.getRootSegment().getPath()));
        first = false;
      }
    }

    // FROM clause
    sb.append(" FROM ");
    sb.append(quoteIdentifier(schemaName));
    sb.append(".");
    sb.append(quoteIdentifier(tableName));

    // Optional WHERE clause
    if (whereClause != null && !whereClause.isEmpty()) {
      sb.append(" WHERE ");
      sb.append(whereClause);
    }

    // Optional LIMIT clause
    if (limit != null) {
      sb.append(" LIMIT ");
      sb.append(limit);
    }

    return sb.toString();
  }
}
