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
import org.apache.calcite.rel.core.JoinRelType;

/**
 * Constructs SQL SELECT statements for JDBC pushdown queries.
 *
 * <p>Assembles a SQL string from individual pushdown components:
 *
 * <ul>
 *   <li>Column projection (SELECT list)
 *   <li>Table reference (schema.table with double-quoted identifiers)
 *   <li>Optional WHERE clause from a pushed-down filter
 *   <li>Optional LIMIT clause from a pushed-down fetch limit
 * </ul>
 *
 * <p>Concrete JDBC connectors may subclass and override {@link #buildSql} to adapt the SQL dialect
 * (e.g. Oracle uses {@code FETCH FIRST n ROWS ONLY} instead of {@code LIMIT}).
 */
public class SqlBuilder {

  /**
   * Wraps an identifier in double quotes and escapes any embedded double quotes per the SQL
   * standard ({@code "} → {@code ""}).
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
   * <p>This is the backward-compatible signature; it delegates to {@link
   * #buildSql(SqlBuildRequest)} by constructing a request from the individual parameters.
   *
   * @param schemaName remote schema name (double-quoted in output)
   * @param tableName remote table name (double-quoted in output)
   * @param projectedColumns columns to project; if null or empty, {@code SELECT *} is used
   * @param whereClause SQL WHERE expression; appended as-is when non-null
   * @param limit row limit; appended as {@code LIMIT N} when non-null
   * @return syntactically complete SQL SELECT string
   */
  public String buildSql(
      String schemaName,
      String tableName,
      List<SchemaPath> projectedColumns,
      String whereClause,
      Integer limit) {
    return buildSql(
        SqlBuildRequest.builder()
            .schema(schemaName)
            .table(tableName)
            .projectedColumns(projectedColumns)
            .where(whereClause)
            .limit(limit)
            .build());
  }

  /**
   * Builds a SQL SELECT statement from a {@link SqlBuildRequest} DTO.
   *
   * <p>Assembles SQL in clause order: SELECT ... FROM ... WHERE ... GROUP BY ... ORDER BY ... LIMIT
   * N. Subclasses may override to adjust dialect-specific syntax (e.g. Oracle's FETCH FIRST instead
   * of LIMIT).
   *
   * @param request the pushdown request containing all SQL components
   * @return syntactically complete SQL SELECT string
   */
  public String buildSql(SqlBuildRequest request) {
    StringBuilder sb = new StringBuilder();

    // SELECT list
    appendSelectList(sb, request);

    // FROM clause
    sb.append(" FROM ");
    sb.append(quoteIdentifier(request.getSchemaName()));
    sb.append(".");
    sb.append(quoteIdentifier(request.getTableName()));

    // Optional WHERE clause
    String whereClause = request.getWhereClause();
    if (whereClause != null && !whereClause.isEmpty()) {
      sb.append(" WHERE ");
      sb.append(whereClause);
    }

    // Optional GROUP BY clause
    String groupBy = request.getGroupByClause();
    if (groupBy != null && !groupBy.isEmpty()) {
      sb.append(" GROUP BY ");
      sb.append(groupBy);
    }

    // Optional ORDER BY clause
    String orderBy = request.getOrderByClause();
    if (orderBy != null && !orderBy.isEmpty()) {
      sb.append(" ORDER BY ");
      sb.append(orderBy);
    }

    // Optional LIMIT clause
    appendLimit(sb, request.getLimit());

    return sb.toString();
  }

  /**
   * Appends the SELECT list to the builder. Uses selectExprs (for aggregation) when present,
   * otherwise uses projectedColumns, falling back to SELECT *.
   */
  protected void appendSelectList(StringBuilder sb, SqlBuildRequest request) {
    List<String> selectExprs = request.getSelectExprs();
    if (selectExprs != null && !selectExprs.isEmpty()) {
      sb.append("SELECT ");
      boolean first = true;
      for (String expr : selectExprs) {
        if (!first) {
          sb.append(", ");
        }
        sb.append(expr);
        first = false;
      }
    } else {
      List<SchemaPath> projectedColumns = request.getProjectedColumns();
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
    }
  }

  /**
   * Appends the LIMIT clause. Standard SQL uses {@code LIMIT N}. Subclasses override for
   * dialect-specific row-limiting syntax (e.g. Oracle's FETCH FIRST).
   */
  protected void appendLimit(StringBuilder sb, Integer limit) {
    if (limit != null) {
      sb.append(" LIMIT ");
      sb.append(limit);
    }
  }

  /**
   * Maps a Calcite {@link JoinRelType} to the corresponding SQL keyword fragment.
   *
   * @param joinType the Calcite join type
   * @return the SQL JOIN keyword, e.g. {@code "INNER JOIN"}
   * @throws IllegalArgumentException if the join type is unsupported
   */
  public static String joinTypeToSql(JoinRelType joinType) {
    switch (joinType) {
      case INNER:
        return "INNER JOIN";
      case LEFT:
        return "LEFT OUTER JOIN";
      case RIGHT:
        return "RIGHT OUTER JOIN";
      case FULL:
        return "FULL OUTER JOIN";
      default:
        throw new IllegalArgumentException("Unsupported join type: " + joinType);
    }
  }

  /**
   * Builds a SQL SELECT statement for a two-table JOIN query.
   *
   * <p>Assembles SQL of the form:
   *
   * <pre>{@code
   * SELECT "t1"."col1", "t1"."col2", "t2"."col3"
   * FROM "leftSchema"."leftTable" AS "t1"
   * INNER JOIN "rightSchema"."rightTable" AS "t2"
   * ON t1."join_col" = t2."join_col"
   * }</pre>
   *
   * <p>Table aliases prevent ambiguous column references when both tables share column names (a
   * common situation with join keys). Subclasses may override this method to adjust the alias
   * syntax for specific dialects (e.g. Oracle omits the {@code AS} keyword for table aliases in
   * some versions).
   *
   * @param leftSchema schema name for the left table
   * @param leftTable table name for the left table
   * @param leftAlias alias for the left table (e.g. "t1")
   * @param rightSchema schema name for the right table
   * @param rightTable table name for the right table
   * @param rightAlias alias for the right table (e.g. "t2")
   * @param joinTypeSql the SQL JOIN keyword fragment, e.g. {@code "INNER JOIN"} (from
   *     {@link #joinTypeToSql})
   * @param onClause the ON condition SQL with {@code ?} placeholders (from
   *     {@link RexToJoinSqlString})
   * @param leftColumns columns to project from the left table; if null/empty all left columns are
   *     omitted from the explicit SELECT list
   * @param rightColumns columns to project from the right table; if null/empty all right columns
   *     are omitted
   * @return syntactically complete JOIN SQL SELECT string
   */
  public String buildJoinSql(
      String leftSchema,
      String leftTable,
      String leftAlias,
      String rightSchema,
      String rightTable,
      String rightAlias,
      String joinTypeSql,
      String onClause,
      List<SchemaPath> leftColumns,
      List<SchemaPath> rightColumns) {

    StringBuilder sb = new StringBuilder();

    // SELECT list: "t1"."col1", "t1"."col2", ..., "t2"."col3", ...
    boolean hasColumns =
        (leftColumns != null && !leftColumns.isEmpty())
            || (rightColumns != null && !rightColumns.isEmpty());
    if (!hasColumns) {
      sb.append("SELECT *");
    } else {
      sb.append("SELECT ");
      boolean first = true;
      if (leftColumns != null) {
        for (SchemaPath col : leftColumns) {
          if (!first) {
            sb.append(", ");
          }
          sb.append(quoteIdentifier(leftAlias))
              .append(".")
              .append(quoteIdentifier(col.getRootSegment().getPath()));
          first = false;
        }
      }
      if (rightColumns != null) {
        for (SchemaPath col : rightColumns) {
          if (!first) {
            sb.append(", ");
          }
          sb.append(quoteIdentifier(rightAlias))
              .append(".")
              .append(quoteIdentifier(col.getRootSegment().getPath()));
          first = false;
        }
      }
    }

    // FROM "leftSchema"."leftTable" AS "leftAlias"
    sb.append(" FROM ")
        .append(quoteIdentifier(leftSchema))
        .append(".")
        .append(quoteIdentifier(leftTable))
        .append(" AS ")
        .append(quoteIdentifier(leftAlias));

    // {JOIN_TYPE} "rightSchema"."rightTable" AS "rightAlias"
    sb.append(" ")
        .append(joinTypeSql)
        .append(" ")
        .append(quoteIdentifier(rightSchema))
        .append(".")
        .append(quoteIdentifier(rightTable))
        .append(" AS ")
        .append(quoteIdentifier(rightAlias));

    // ON {onClause}
    sb.append(" ON ").append(onClause);

    return sb.toString();
  }

  /**
   * Translates JDBC {@code ?} placeholders to PostgreSQL {@code $1, $2, ...} notation.
   *
   * <p>This is used at the boundary between SQL generation and ADBC execution. The SqlBuilder and
   * RexToSqlString always emit {@code ?} (JDBC convention); this utility converts them to the
   * {@code $N} format required by the ADBC PostgreSQL driver (which uses libpq's native prepared
   * statement protocol).
   *
   * <p>This translation is safe because our SqlBuilder never generates {@code ?} inside string
   * literals -- all literal values are bind params, and column/table names are double-quoted
   * identifiers.
   *
   * @param sql the SQL string with {@code ?} placeholders
   * @param paramCount the number of bind parameters (used to bound translation)
   * @return the SQL string with {@code $1, $2, ...} placeholders
   */
  public static String jdbcToPostgresPlaceholders(String sql, int paramCount) {
    if (paramCount == 0) {
      return sql;
    }
    StringBuilder sb = new StringBuilder(sql.length() + paramCount * 2);
    int idx = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '?' && idx < paramCount) {
        idx++;
        sb.append('$').append(idx);
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
