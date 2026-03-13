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

import com.dremio.common.expression.SchemaPath;
import com.dremio.plugins.jdbc.planning.SqlBuilder;
import java.util.List;

/**
 * Oracle-specific SQL builder that generates {@code FETCH FIRST N ROWS ONLY} instead of
 * the standard {@code LIMIT N} syntax.
 *
 * <p>Oracle Database 12c Release 1 (12.1) and later supports the SQL standard row-limiting
 * clause ({@code FETCH FIRST}). The proprietary {@code ROWNUM} approach is not used here
 * because it requires a subquery wrapper and interacts poorly with ORDER BY.
 *
 * <p>SELECT, FROM, and WHERE clauses are identical to the base {@link SqlBuilder}.
 */
public class OracleSqlBuilder extends SqlBuilder {

  /**
   * Builds a SQL SELECT statement using Oracle row-limiting syntax.
   *
   * <p>All clauses except the row limit are identical to {@link SqlBuilder#buildSql}.
   * The {@code LIMIT N} clause is replaced with {@code FETCH FIRST N ROWS ONLY} to
   * comply with Oracle SQL dialect.
   *
   * @param schemaName       remote schema name (double-quoted in output)
   * @param tableName        remote table name (double-quoted in output)
   * @param projectedColumns columns to project; if null or empty, {@code SELECT *} is used
   * @param whereClause      SQL WHERE expression; appended as-is when non-null
   * @param limit            row limit; appended as {@code FETCH FIRST N ROWS ONLY} when non-null
   * @return syntactically complete SQL SELECT string using Oracle dialect
   */
  @Override
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

    // Oracle 12c+ FETCH FIRST instead of LIMIT
    if (limit != null) {
      sb.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
    }

    return sb.toString();
  }
}
