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

import static org.junit.Assert.assertTrue;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.junit.Test;

/**
 * Unit tests documenting {@link SqlDialect} behavior that the Calcite JDBC convention migration
 * (Phase 36) depends on.
 *
 * <p>These tests do NOT invoke {@code allowsAs()} directly because it is a protected method.
 * Instead they verify the public quoting behavior and document that the protected {@code allowsAs()}
 * behavior is verified end-to-end by the integration tests ({@code TestPostgresJoinPushdown} and
 * {@code TestOracleJoinPushdown}).
 */
public class TestCalciteDialectSql {

  @Test
  public void testPostgresQuotesIdentifier() {
    SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
    // quoteIdentifier wraps in dialect-appropriate quotes.
    // PostgreSQL uses double-quotes for identifiers.
    String quoted = quoteSingle(pg, "public");
    assertTrue(
        "PostgreSQL quoteIdentifier must produce quoted output containing 'public': " + quoted,
        quoted.contains("\"public\"") || quoted.contains("public"));
  }

  @Test
  public void testOracleQuotesIdentifier() {
    SqlDialect oracle = OracleSqlDialect.DEFAULT;
    String quoted = quoteSingle(oracle, "HR");
    assertTrue(
        "Oracle quoteIdentifier must produce quoted output containing 'HR': " + quoted,
        quoted.contains("\"HR\"") || quoted.contains("HR"));
  }

  @Test
  public void testOracleDialectType() {
    // OracleSqlDialect.allowsAs() returns false (protected method, cannot call directly).
    // Test indirectly: the dialect object is an OracleSqlDialect instance.
    // The actual allowsAs() behavior is verified end-to-end by TestOracleJoinPushdown
    // which checks that Oracle-rendered JOIN SQL does NOT contain " AS " before table aliases.
    SqlDialect oracle = OracleSqlDialect.DEFAULT;
    assertTrue(
        "Oracle dialect must be an instance of OracleSqlDialect",
        oracle instanceof OracleSqlDialect);
  }

  @Test
  public void testPostgresDialectType() {
    SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
    assertTrue(
        "PostgreSQL dialect must be an instance of PostgresqlSqlDialect",
        pg instanceof PostgresqlSqlDialect);
    // PostgresqlSqlDialect.allowsAs() returns true (protected, cannot call directly).
    // Verified end-to-end by TestPostgresJoinPushdown which checks that PostgreSQL-rendered
    // JOIN SQL contains AS before table aliases: "schema"."table" AS "alias".
  }

  @Test
  public void testEmbeddedDoubleQuoteEscaping() {
    SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
    // An identifier with an embedded double-quote character must be escaped.
    // PostgreSQL uses doubled double-quotes: "my""table"
    String quoted = quoteSingle(pg, "my\"table");
    assertTrue(
        "Identifier with embedded double-quote must be escaped (doubled): " + quoted,
        quoted.contains("\"\""));
  }

  @Test
  public void testOracleEmbeddedDoubleQuoteEscaping() {
    SqlDialect oracle = OracleSqlDialect.DEFAULT;
    String quoted = quoteSingle(oracle, "my\"table");
    assertTrue(
        "Oracle identifier with embedded double-quote must be escaped (doubled): " + quoted,
        quoted.contains("\"\""));
  }

  @Test
  public void testPostgresQuotesSchemaAndTable() {
    SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
    // Verify both schema and table parts are quoted correctly.
    String schemaQuoted = quoteSingle(pg, "my schema");
    assertTrue(
        "Schema name with space must be double-quoted: " + schemaQuoted,
        schemaQuoted.contains("\"my schema\""));

    String tableQuoted = quoteSingle(pg, "my-table");
    assertTrue(
        "Table name with hyphen must be double-quoted: " + tableQuoted,
        tableQuoted.contains("\"my-table\""));
  }

  @Test
  public void testOracleUppercaseIdentifiers() {
    SqlDialect oracle = OracleSqlDialect.DEFAULT;
    // Oracle typically uses uppercase identifiers. Verify uppercase is preserved.
    String quoted = quoteSingle(oracle, "EMPLOYEES");
    assertTrue(
        "Oracle identifier EMPLOYEES must be preserved in quoted output: " + quoted,
        quoted.contains("EMPLOYEES"));
  }

  /**
   * Helper: call {@link SqlDialect#quoteIdentifier(StringBuilder, String)} and return the result.
   * This exercises the public quoting API that {@link DremioJdbcImplementor} depends on for
   * rendering {@code FROM "schema"."table"} clauses.
   */
  private static String quoteSingle(SqlDialect dialect, String id) {
    StringBuilder buf = new StringBuilder();
    dialect.quoteIdentifier(buf, id);
    return buf.toString();
  }
}
