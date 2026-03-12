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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.math.BigDecimal;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link JdbcPushFilterIntoScan.RexToSqlString}.
 *
 * <p>Validates that Calcite RexNode expressions are correctly converted to SQL WHERE clause strings,
 * and that unsupported expressions safely return null (declining pushdown).
 */
public class TestRexToSqlString {

  private RelDataTypeFactory typeFactory;
  private RexBuilder rex;
  private RelDataType rowType;
  private JdbcPushFilterIntoScan.RexToSqlString converter;

  @Before
  public void setUp() {
    typeFactory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    rex = new RexBuilder(typeFactory);

    // Simulate a table with columns: id (INTEGER), name (VARCHAR), age (INTEGER), active (BOOLEAN)
    rowType =
        typeFactory
            .builder()
            .add("id", SqlTypeName.INTEGER)
            .add("name", SqlTypeName.VARCHAR, 255)
            .add("age", SqlTypeName.INTEGER)
            .add("active", SqlTypeName.BOOLEAN)
            .build();

    converter = new JdbcPushFilterIntoScan.RexToSqlString(rowType);
  }

  // ---- Simple comparisons ----

  @Test
  public void equalsIntLiteral() {
    // id = 42
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(BigDecimal.valueOf(42)));

    String sql = converter.convert(expr);
    assertEquals("\"id\" = 42", sql);
  }

  @Test
  public void notEqualsStringLiteral() {
    // name <> 'admin'
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.NOT_EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1),
            rex.makeLiteral("admin"));

    String sql = converter.convert(expr);
    assertEquals("\"name\" <> 'admin'", sql);
  }

  @Test
  public void greaterThan() {
    // age > 18
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.GREATER_THAN,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(18)));

    String sql = converter.convert(expr);
    assertEquals("\"age\" > 18", sql);
  }

  @Test
  public void lessThanOrEqual() {
    // age <= 65
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(65)));

    String sql = converter.convert(expr);
    assertEquals("\"age\" <= 65", sql);
  }

  // ---- Boolean operators ----

  @Test
  public void andCombination() {
    // age > 18 AND active = TRUE
    RexNode left =
        rex.makeCall(
            SqlStdOperatorTable.GREATER_THAN,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(18)));
    RexNode right =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(3).getType(), 3),
            rex.makeLiteral(true));
    RexNode expr = rex.makeCall(SqlStdOperatorTable.AND, left, right);

    String sql = converter.convert(expr);
    assertNotNull(sql);
    // Should produce: ("age" > 18) AND ("active" = TRUE)
    assertEquals("(\"age\" > 18) AND (\"active\" = TRUE)", sql);
  }

  @Test
  public void orCombination() {
    // id = 1 OR id = 2
    RexNode left =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(BigDecimal.valueOf(1)));
    RexNode right =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(BigDecimal.valueOf(2)));
    RexNode expr = rex.makeCall(SqlStdOperatorTable.OR, left, right);

    String sql = converter.convert(expr);
    assertNotNull(sql);
    assertEquals("(\"id\" = 1) OR (\"id\" = 2)", sql);
  }

  @Test
  public void notExpression() {
    // NOT (active = TRUE)
    RexNode inner =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(3).getType(), 3),
            rex.makeLiteral(true));
    RexNode expr = rex.makeCall(SqlStdOperatorTable.NOT, inner);

    String sql = converter.convert(expr);
    assertNotNull(sql);
    assertEquals("NOT (\"active\" = TRUE)", sql);
  }

  // ---- NULL checks ----

  @Test
  public void isNull() {
    // name IS NULL
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.IS_NULL,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1));

    String sql = converter.convert(expr);
    assertEquals("\"name\" IS NULL", sql);
  }

  @Test
  public void isNotNull() {
    // name IS NOT NULL
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.IS_NOT_NULL,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1));

    String sql = converter.convert(expr);
    assertEquals("\"name\" IS NOT NULL", sql);
  }

  // ---- NULL literal ----

  @Test
  public void nullLiteral() {
    // id = NULL
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 0),
            rex.makeNullLiteral(typeFactory.createSqlType(SqlTypeName.INTEGER)));

    String sql = converter.convert(expr);
    assertEquals("\"id\" = NULL", sql);
  }

  // ---- LIKE operator ----

  @Test
  public void likeOperator() {
    // name LIKE '%admin%'
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.LIKE,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1),
            rex.makeLiteral("%admin%"));

    String sql = converter.convert(expr);
    assertEquals("\"name\" LIKE '%admin%'", sql);
  }

  // ---- SQL injection via string literals ----

  @Test
  public void stringLiteralWithSingleQuoteEscaped() {
    // name = "O'Brien" — single quote must be escaped to ''
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1),
            rex.makeLiteral("O'Brien"));

    String sql = converter.convert(expr);
    assertEquals("\"name\" = 'O''Brien'", sql);
  }

  @Test
  public void stringLiteralWithSqlInjectionAttempt() {
    // name = "'; DROP TABLE users; --"
    // Must produce: "name" = '''; DROP TABLE users; --'
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1),
            rex.makeLiteral("'; DROP TABLE users; --"));

    String sql = converter.convert(expr);
    assertNotNull(sql);
    // Input: '; DROP TABLE users; --
    // Escaped: ''; DROP TABLE users; --
    // Wrapped: '''; DROP TABLE users; --'
    assertEquals("\"name\" = '''; DROP TABLE users; --'", sql);
  }

  @Test
  public void stringLiteralWithMultipleSingleQuotes() {
    // name = "it''s a 'test'"
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1),
            rex.makeLiteral("it''s a 'test'"));

    String sql = converter.convert(expr);
    assertNotNull(sql);
    // Each single quote becomes ''
    assertEquals("\"name\" = 'it''''s a ''test'''", sql);
  }

  // ---- Column name quoting (injection via column ref) ----

  @Test
  public void columnNameWithDoubleQuotesEscaped() {
    // Column with double quote in name: build a rowType with such a column
    RelDataType injectedRowType =
        typeFactory
            .builder()
            .add("col\"inject", SqlTypeName.INTEGER)
            .build();

    JdbcPushFilterIntoScan.RexToSqlString conv =
        new JdbcPushFilterIntoScan.RexToSqlString(injectedRowType);

    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(injectedRowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(BigDecimal.ONE));

    String sql = conv.convert(expr);
    // Double quote in column name must be escaped: " -> ""
    assertEquals("\"col\"\"inject\" = 1", sql);
  }

  // ---- Boolean literal ----

  @Test
  public void booleanTrueLiteral() {
    // active = TRUE
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(3).getType(), 3),
            rex.makeLiteral(true));

    String sql = converter.convert(expr);
    assertEquals("\"active\" = TRUE", sql);
  }

  @Test
  public void booleanFalseLiteral() {
    // active = FALSE
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(3).getType(), 3),
            rex.makeLiteral(false));

    String sql = converter.convert(expr);
    assertEquals("\"active\" = FALSE", sql);
  }

  // ---- Decimal literal ----

  @Test
  public void decimalLiteral() {
    // age = 3.14
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(new BigDecimal("3.14")));

    String sql = converter.convert(expr);
    assertEquals("\"age\" = 3.14", sql);
  }

  // ---- Unsupported expressions return null ----

  @Test
  public void unsupportedOperatorReturnsNull() {
    // CASE WHEN ... is not supported — should return null
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.CASE,
            rex.makeLiteral(true),
            rex.makeExactLiteral(BigDecimal.ONE),
            rex.makeExactLiteral(BigDecimal.ZERO));

    String sql = converter.convert(expr);
    assertNull("Unsupported operator should return null", sql);
  }

  @Test
  public void unsupportedInAndReturnsNullForWholeExpression() {
    // If one side of AND is unsupported, the whole expression returns null
    RexNode supported =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(BigDecimal.ONE));
    RexNode unsupported =
        rex.makeCall(
            SqlStdOperatorTable.CASE,
            rex.makeLiteral(true),
            rex.makeExactLiteral(BigDecimal.ONE),
            rex.makeExactLiteral(BigDecimal.ZERO));
    RexNode expr = rex.makeCall(SqlStdOperatorTable.AND, supported, unsupported);

    String sql = converter.convert(expr);
    assertNull("AND with unsupported operand should return null", sql);
  }

  // ---- Out-of-bounds input ref ----

  @Test
  public void outOfBoundsInputRefReturnsNull() {
    // InputRef index 99 on a 4-column row type
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 99),
            rex.makeExactLiteral(BigDecimal.ONE));

    String sql = converter.convert(expr);
    assertNull("Out-of-bounds column reference should return null", sql);
  }
}
