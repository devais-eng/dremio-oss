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
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link RexToSqlString}.
 *
 * <p>Validates that Calcite RexNode expressions are correctly converted to SQL WHERE clause
 * strings with bind parameters (? placeholders), and that unsupported expressions safely
 * return null (declining pushdown).
 */
public class TestRexToSqlString {

  private RelDataTypeFactory typeFactory;
  private RexBuilder rex;
  private RelDataType rowType;
  private RexToSqlString converter;

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

    converter = new RexToSqlString(rowType);
  }

  // ---- Simple comparisons ----

  @Test
  public void equalsIntLiteral() {
    // id = 42 -> "id" = ? with bind param 42
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(BigDecimal.valueOf(42)));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"id\" = ?", result.getSql());
    assertEquals(1, result.getParams().size());
    assertEquals(BigDecimal.valueOf(42), result.getParams().get(0).getValue());
  }

  @Test
  public void notEqualsStringLiteral() {
    // name <> 'admin' -> "name" <> ? with bind param 'admin'
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.NOT_EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1),
            rex.makeLiteral("admin"));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"name\" <> ?", result.getSql());
    assertEquals(1, result.getParams().size());
    assertEquals("admin", result.getParams().get(0).getValue());
    assertEquals(SqlTypeName.CHAR, result.getParams().get(0).getTypeName());
  }

  @Test
  public void greaterThan() {
    // age > 18
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.GREATER_THAN,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(18)));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"age\" > ?", result.getSql());
    assertEquals(1, result.getParams().size());
  }

  @Test
  public void lessThanOrEqual() {
    // age <= 65
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(65)));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"age\" <= ?", result.getSql());
    assertEquals(1, result.getParams().size());
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

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    // Should produce: ("age" > ?) AND ("active" = TRUE) with 1 bind param for 18
    assertEquals("(\"age\" > ?) AND (\"active\" = TRUE)", result.getSql());
    assertEquals(1, result.getParams().size());
    assertEquals(BigDecimal.valueOf(18), result.getParams().get(0).getValue());
  }

  @Test
  public void orCombinationConvertsToIn() {
    // id = 1 OR id = 2 -> "id" IN (?, ?) via OR-of-EQUALS detection
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

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"id\" IN (?, ?)", result.getSql());
    assertEquals(2, result.getParams().size());
    assertEquals(BigDecimal.valueOf(1), result.getParams().get(0).getValue());
    assertEquals(BigDecimal.valueOf(2), result.getParams().get(1).getValue());
  }

  @Test
  public void orWithDifferentColumnsStaysOr() {
    // id = 1 OR age = 2 -> standard OR (different columns, not convertible to IN)
    RexNode left =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(BigDecimal.valueOf(1)));
    RexNode right =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(2)));
    RexNode expr = rex.makeCall(SqlStdOperatorTable.OR, left, right);

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("(\"id\" = ?) OR (\"age\" = ?)", result.getSql());
    assertEquals(2, result.getParams().size());
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

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("NOT (\"active\" = TRUE)", result.getSql());
    assertTrue(result.getParams().isEmpty());
  }

  // ---- NULL checks ----

  @Test
  public void isNull() {
    // name IS NULL
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.IS_NULL,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"name\" IS NULL", result.getSql());
    assertTrue(result.getParams().isEmpty());
  }

  @Test
  public void isNotNull() {
    // name IS NOT NULL
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.IS_NOT_NULL,
            rex.makeInputRef(rowType.getFieldList().get(1).getType(), 1));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"name\" IS NOT NULL", result.getSql());
    assertTrue(result.getParams().isEmpty());
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

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"id\" = NULL", result.getSql());
    assertTrue(result.getParams().isEmpty());
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

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"name\" LIKE ?", result.getSql());
    assertEquals(1, result.getParams().size());
    assertEquals("%admin%", result.getParams().get(0).getValue());
  }

  // ---- Arithmetic operators ----

  @Test
  public void plusOperator() {
    // age + 1
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.PLUS,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(1)));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"age\" + ?", result.getSql());
    assertEquals(1, result.getParams().size());
  }

  @Test
  public void minusOperator() {
    // age - 5
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.MINUS,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(5)));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"age\" - ?", result.getSql());
    assertEquals(1, result.getParams().size());
  }

  @Test
  public void timesOperator() {
    // age * 2
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.MULTIPLY,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(2)));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"age\" * ?", result.getSql());
    assertEquals(1, result.getParams().size());
  }

  @Test
  public void divideOperator() {
    // age / 10
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.DIVIDE,
            rex.makeInputRef(rowType.getFieldList().get(2).getType(), 2),
            rex.makeExactLiteral(BigDecimal.valueOf(10)));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"age\" / ?", result.getSql());
    assertEquals(1, result.getParams().size());
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

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"active\" = TRUE", result.getSql());
    assertTrue(result.getParams().isEmpty());
  }

  @Test
  public void booleanFalseLiteral() {
    // active = FALSE
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(rowType.getFieldList().get(3).getType(), 3),
            rex.makeLiteral(false));

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"active\" = FALSE", result.getSql());
    assertTrue(result.getParams().isEmpty());
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

    RexToSqlResult result = converter.convert(expr);
    assertNotNull(result);
    assertEquals("\"age\" = ?", result.getSql());
    assertEquals(1, result.getParams().size());
    assertEquals(new BigDecimal("3.14"), result.getParams().get(0).getValue());
  }

  // ---- Column name quoting (injection via column ref) ----

  @Test
  public void columnNameWithDoubleQuotesEscaped() {
    // Column with double quote in name
    RelDataType injectedRowType =
        typeFactory
            .builder()
            .add("col\"inject", SqlTypeName.INTEGER)
            .build();

    RexToSqlString conv = new RexToSqlString(injectedRowType);

    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.EQUALS,
            rex.makeInputRef(injectedRowType.getFieldList().get(0).getType(), 0),
            rex.makeExactLiteral(BigDecimal.ONE));

    RexToSqlResult result = conv.convert(expr);
    assertNotNull(result);
    // Double quote in column name must be escaped: " -> ""
    assertEquals("\"col\"\"inject\" = ?", result.getSql());
    assertEquals(1, result.getParams().size());
  }

  // ---- Unsupported expressions return null ----

  @Test
  public void unsupportedOperatorReturnsNull() {
    // CASE WHEN ... is not supported -- should return null
    RexNode expr =
        rex.makeCall(
            SqlStdOperatorTable.CASE,
            rex.makeLiteral(true),
            rex.makeExactLiteral(BigDecimal.ONE),
            rex.makeExactLiteral(BigDecimal.ZERO));

    RexToSqlResult result = converter.convert(expr);
    assertNull("Unsupported operator should return null", result);
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

    RexToSqlResult result = converter.convert(expr);
    assertNull("AND with unsupported operand should return null", result);
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

    RexToSqlResult result = converter.convert(expr);
    assertNull("Out-of-bounds column reference should return null", result);
  }
}
