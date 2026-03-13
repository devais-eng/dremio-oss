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
 * Oracle-specific SQL builder that generates {@code FETCH FIRST N ROWS ONLY} instead of
 * the standard {@code LIMIT N} syntax.
 *
 * <p>Oracle Database 12c Release 1 (12.1) and later supports the SQL standard row-limiting
 * clause ({@code FETCH FIRST}). The proprietary {@code ROWNUM} approach is not used here
 * because it requires a subquery wrapper and interacts poorly with ORDER BY.
 *
 * <p>SELECT, FROM, WHERE, GROUP BY, and ORDER BY clauses are identical to the base
 * {@link SqlBuilder}. Only the row-limiting clause differs.
 */
public class OracleSqlBuilder extends SqlBuilder {

  /**
   * Builds a SQL SELECT statement using Oracle row-limiting syntax.
   *
   * <p>This is the backward-compatible 5-parameter signature. Delegates to
   * {@link #buildSql(SqlBuildRequest)}.
   */
  @Override
  public String buildSql(
      String schemaName,
      String tableName,
      List<SchemaPath> projectedColumns,
      String whereClause,
      Integer limit) {
    return buildSql(SqlBuildRequest.builder()
        .schema(schemaName)
        .table(tableName)
        .projectedColumns(projectedColumns)
        .where(whereClause)
        .limit(limit)
        .build());
  }

  /**
   * Appends Oracle's row-limiting clause ({@code FETCH FIRST N ROWS ONLY}) instead of
   * the standard {@code LIMIT N}. ORDER BY is emitted before FETCH FIRST per Oracle syntax.
   */
  @Override
  protected void appendLimit(StringBuilder sb, Integer limit) {
    if (limit != null) {
      sb.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
    }
  }
}
