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

import static org.junit.Assert.assertEquals;

import com.dremio.common.expression.SchemaPath;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Unit tests for {@link SqlBuilder}. */
public class TestSqlBuilder {

  private final SqlBuilder builder = new SqlBuilder();

  // ---- Basic SELECT generation ----

  @Test
  public void selectStarWhenNoColumns() {
    String sql = builder.buildSql("public", "users", null, null, null);
    assertEquals("SELECT * FROM \"public\".\"users\"", sql);
  }

  @Test
  public void selectStarWhenEmptyColumns() {
    String sql = builder.buildSql("public", "users", Collections.emptyList(), null, null);
    assertEquals("SELECT * FROM \"public\".\"users\"", sql);
  }

  @Test
  public void selectSpecificColumns() {
    List<SchemaPath> cols =
        Arrays.asList(SchemaPath.getSimplePath("id"), SchemaPath.getSimplePath("name"));
    String sql = builder.buildSql("public", "users", cols, null, null);
    assertEquals("SELECT \"id\", \"name\" FROM \"public\".\"users\"", sql);
  }

  @Test
  public void selectSingleColumn() {
    List<SchemaPath> cols = Collections.singletonList(SchemaPath.getSimplePath("email"));
    String sql = builder.buildSql("myschema", "accounts", cols, null, null);
    assertEquals("SELECT \"email\" FROM \"myschema\".\"accounts\"", sql);
  }

  // ---- WHERE clause ----

  @Test
  public void whereClauseAppended() {
    String sql = builder.buildSql("public", "users", null, "age > 18", null);
    assertEquals("SELECT * FROM \"public\".\"users\" WHERE age > 18", sql);
  }

  @Test
  public void emptyWhereClauseIgnored() {
    String sql = builder.buildSql("public", "users", null, "", null);
    assertEquals("SELECT * FROM \"public\".\"users\"", sql);
  }

  // ---- LIMIT clause ----

  @Test
  public void limitClauseAppended() {
    String sql = builder.buildSql("public", "users", null, null, 100);
    assertEquals("SELECT * FROM \"public\".\"users\" LIMIT 100", sql);
  }

  @Test
  public void limitZero() {
    String sql = builder.buildSql("public", "users", null, null, 0);
    assertEquals("SELECT * FROM \"public\".\"users\" LIMIT 0", sql);
  }

  // ---- Combined clauses ----

  @Test
  public void allClausesCombined() {
    List<SchemaPath> cols =
        Arrays.asList(SchemaPath.getSimplePath("id"), SchemaPath.getSimplePath("name"));
    String sql = builder.buildSql("public", "users", cols, "age > 18", 100);
    assertEquals(
        "SELECT \"id\", \"name\" FROM \"public\".\"users\" WHERE age > 18 LIMIT 100", sql);
  }

  @Test
  public void whereAndLimitWithoutProjection() {
    String sql = builder.buildSql("hr", "employees", null, "dept = 'ENG'", 50);
    assertEquals("SELECT * FROM \"hr\".\"employees\" WHERE dept = 'ENG' LIMIT 50", sql);
  }

  // ---- Identifier quoting / SQL injection prevention ----

  @Test
  public void identifierWithDoubleQuotesEscaped() {
    // A schema or table name containing double quotes should be escaped: " -> ""
    String sql = builder.buildSql("my\"schema", "my\"table", null, null, null);
    assertEquals("SELECT * FROM \"my\"\"schema\".\"my\"\"table\"", sql);
  }

  @Test
  public void columnNameWithDoubleQuotesEscaped() {
    List<SchemaPath> cols = Collections.singletonList(SchemaPath.getSimplePath("col\"name"));
    String sql = builder.buildSql("s", "t", cols, null, null);
    assertEquals("SELECT \"col\"\"name\" FROM \"s\".\"t\"", sql);
  }

  @Test
  public void schemaNameWithSqlInjectionAttempt() {
    // Attacker tries: schema"; DROP TABLE users; --
    // Should be safely quoted as "schema""; DROP TABLE users; --"
    String sql = builder.buildSql("\"; DROP TABLE users; --", "t", null, null, null);
    assertEquals(
        "SELECT * FROM \"\"\"; DROP TABLE users; --\".\"t\"", sql);
  }

  @Test
  public void tableNameWithSqlInjectionAttempt() {
    String sql = builder.buildSql("s", "t\"; DROP TABLE users;--", null, null, null);
    assertEquals(
        "SELECT * FROM \"s\".\"t\"\"; DROP TABLE users;--\"", sql);
  }

  @Test
  public void columnNameWithSqlInjectionAttempt() {
    // Injecting through a column name: col"; DROP TABLE x; --
    List<SchemaPath> cols =
        Collections.singletonList(SchemaPath.getSimplePath("col\"; DROP TABLE x; --"));
    String sql = builder.buildSql("s", "t", cols, null, null);
    assertEquals(
        "SELECT \"col\"\"; DROP TABLE x; --\" FROM \"s\".\"t\"", sql);
  }

  @Test
  public void identifierWithSingleQuoteNotSpecial() {
    // Single quotes in identifiers don't need special handling (they're double-quoted)
    String sql = builder.buildSql("it's", "o'table", null, null, null);
    assertEquals("SELECT * FROM \"it's\".\"o'table\"", sql);
  }

  @Test
  public void identifierWithBackslashPreserved() {
    String sql = builder.buildSql("path\\to", "my\\table", null, null, null);
    assertEquals("SELECT * FROM \"path\\to\".\"my\\table\"", sql);
  }

  @Test
  public void identifierWithSpacesPreserved() {
    String sql = builder.buildSql("my schema", "my table", null, null, null);
    assertEquals("SELECT * FROM \"my schema\".\"my table\"", sql);
  }

  @Test
  public void identifierWithSemicolonEscaped() {
    // Semicolons are safe inside double-quoted identifiers
    String sql = builder.buildSql("a;b", "c;d", null, null, null);
    assertEquals("SELECT * FROM \"a;b\".\"c;d\"", sql);
  }

  // ---- Dialect override hook ----

  @Test
  public void subclassCanOverrideSqlDialect() {
    SqlBuilder oracleBuilder =
        new SqlBuilder() {
          @Override
          public String buildSql(
              String schemaName,
              String tableName,
              List<SchemaPath> projectedColumns,
              String whereClause,
              Integer limit) {
            // Oracle uses FETCH FIRST N ROWS ONLY instead of LIMIT
            StringBuilder sb = new StringBuilder();
            if (projectedColumns == null || projectedColumns.isEmpty()) {
              sb.append("SELECT *");
            } else {
              sb.append("SELECT ");
              boolean first = true;
              for (SchemaPath col : projectedColumns) {
                if (!first) sb.append(", ");
                sb.append(quoteIdentifier(col.getRootSegment().getPath()));
                first = false;
              }
            }
            sb.append(" FROM ");
            sb.append(quoteIdentifier(schemaName)).append(".").append(quoteIdentifier(tableName));
            if (whereClause != null && !whereClause.isEmpty()) {
              sb.append(" WHERE ").append(whereClause);
            }
            if (limit != null) {
              sb.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
            }
            return sb.toString();
          }
        };

    String sql = oracleBuilder.buildSql("hr", "employees", null, null, 10);
    assertEquals("SELECT * FROM \"hr\".\"employees\" FETCH FIRST 10 ROWS ONLY", sql);
  }
}
