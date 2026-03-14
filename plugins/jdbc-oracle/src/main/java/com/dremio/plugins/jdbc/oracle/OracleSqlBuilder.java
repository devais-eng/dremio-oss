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
import com.dremio.plugins.jdbc.planning.SqlBuildRequest;
import com.dremio.plugins.jdbc.planning.SqlBuilder;
import java.util.List;

/**
 * Oracle-specific SQL builder that generates {@code FETCH FIRST N ROWS ONLY} instead of the
 * standard {@code LIMIT N} syntax, and omits the {@code AS} keyword for table aliases in JOIN SQL.
 *
 * <p>Oracle Database 12c Release 1 (12.1) and later supports the SQL standard row-limiting clause
 * ({@code FETCH FIRST}). The proprietary {@code ROWNUM} approach is not used here because it
 * requires a subquery wrapper and interacts poorly with ORDER BY.
 *
 * <p>Oracle does NOT support the {@code AS} keyword for table aliases in {@code FROM} and
 * {@code JOIN} clauses. The correct Oracle alias syntax is {@code "schema"."table" "alias"} (space
 * + alias, no {@code AS} keyword). The base {@link SqlBuilder#buildJoinSql} uses {@code AS}; this
 * override produces Oracle-compatible alias syntax.
 *
 * <p>SELECT, FROM (single-table), WHERE, GROUP BY, and ORDER BY clauses are identical to the base
 * {@link SqlBuilder}. Only the row-limiting clause and JOIN alias syntax differ.
 */
public class OracleSqlBuilder extends SqlBuilder {

  /**
   * Builds a SQL SELECT statement using Oracle row-limiting syntax.
   *
   * <p>This is the backward-compatible 5-parameter signature. Delegates to {@link
   * #buildSql(SqlBuildRequest)}.
   */
  @Override
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
   * Appends Oracle's row-limiting clause ({@code FETCH FIRST N ROWS ONLY}) instead of the standard
   * {@code LIMIT N}. ORDER BY is emitted before FETCH FIRST per Oracle syntax.
   */
  @Override
  protected void appendLimit(StringBuilder sb, Integer limit) {
    if (limit != null) {
      sb.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
    }
  }

  /**
   * Builds a JOIN SQL statement using Oracle-compatible alias syntax (no {@code AS} keyword).
   *
   * <p>Oracle does not accept {@code "schema"."table" AS "alias"} in FROM/JOIN clauses; the correct
   * syntax is {@code "schema"."table" "alias"} (space-separated, no AS). This override replaces the
   * base implementation's {@code AS "alias"} with {@code "alias"} (space + alias only).
   *
   * <p>All other aspects (SELECT list, JOIN type, ON clause) are identical to the base
   * implementation.
   *
   * @param leftSchema  schema name for the left table
   * @param leftTable   table name for the left table
   * @param leftAlias   alias for the left table (e.g. "t1")
   * @param rightSchema schema name for the right table
   * @param rightTable  table name for the right table
   * @param rightAlias  alias for the right table (e.g. "t2")
   * @param joinTypeSql the SQL JOIN keyword fragment, e.g. {@code "INNER JOIN"}
   * @param onClause    the ON condition SQL
   * @param leftColumns columns to project from the left table
   * @param rightColumns columns to project from the right table
   * @return Oracle-compatible JOIN SQL SELECT string
   */
  @Override
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

    // SELECT list: "t1"."col1", ..., "t2"."col3", ...
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

    // FROM "leftSchema"."leftTable" "leftAlias"  (Oracle: no AS keyword)
    sb.append(" FROM ")
        .append(quoteIdentifier(leftSchema))
        .append(".")
        .append(quoteIdentifier(leftTable))
        .append(" ")
        .append(quoteIdentifier(leftAlias));

    // {JOIN_TYPE} "rightSchema"."rightTable" "rightAlias"  (Oracle: no AS keyword)
    sb.append(" ")
        .append(joinTypeSql)
        .append(" ")
        .append(quoteIdentifier(rightSchema))
        .append(".")
        .append(quoteIdentifier(rightTable))
        .append(" ")
        .append(quoteIdentifier(rightAlias));

    // ON {onClause}
    sb.append(" ON ").append(onClause);

    return sb.toString();
  }
}
