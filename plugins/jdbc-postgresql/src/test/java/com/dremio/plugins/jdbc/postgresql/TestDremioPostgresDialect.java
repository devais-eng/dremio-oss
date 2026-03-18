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
package com.dremio.plugins.jdbc.postgresql;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.junit.Test;

/**
 * Unit tests for {@link DremioPostgresDialect#unparseCall} SQL rendering.
 *
 * <p>These tests construct {@link org.apache.calcite.sql.SqlCall} trees manually using Calcite's
 * node constructors and then invoke {@link DremioPostgresDialect#INSTANCE} to render them to SQL.
 * No database container is required — all assertions are purely string-based.
 *
 * <p>Six tests verify:
 * <ol>
 *   <li>{@code l2_distance(col, ARRAY[...])} renders as {@code col <-> '[...]'}</li>
 *   <li>{@code cosine_distance(col, ARRAY[...])} renders as {@code col <=> '[...]'}</li>
 *   <li>{@code inner_product(col, ARRAY[...])} renders as {@code col <#> '[...]'}</li>
 *   <li>Column vs column (no ARRAY literal) renders infix without pgvector string literal</li>
 *   <li>Non-distance function (UPPER) delegates to parent class rendering</li>
 *   <li>ARRAY literal values with scientific-notation risk are rendered as plain strings</li>
 * </ol>
 */
public class TestDremioPostgresDialect {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a {@link SqlFunction} operator for a named pgvector distance function.
   *
   * @param name the function name (e.g. "l2_distance")
   * @return a {@code SqlFunction} with {@code OTHER_FUNCTION} kind
   */
  private static SqlOperator distanceOp(String name) {
    return new SqlFunction(
        name,
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.DOUBLE_NULLABLE,
        null,
        OperandTypes.ANY_ANY,
        SqlFunctionCategory.USER_DEFINED_FUNCTION);
  }

  /**
   * Builds a three-element {@code ARRAY[a, b, c]} {@link SqlBasicCall}.
   *
   * @param a first element value as string (e.g. "1.0")
   * @param b second element value
   * @param c third element value
   * @return a {@code SqlBasicCall} with {@code ARRAY_VALUE_CONSTRUCTOR} operator
   */
  private static SqlBasicCall arrayLiteral(String a, String b, String c) {
    SqlNode[] elems = new SqlNode[] {
        SqlLiteral.createExactNumeric(a, SqlParserPos.ZERO),
        SqlLiteral.createExactNumeric(b, SqlParserPos.ZERO),
        SqlLiteral.createExactNumeric(c, SqlParserPos.ZERO)
    };
    return new SqlBasicCall(
        SqlStdOperatorTable.ARRAY_VALUE_CONSTRUCTOR, elems, SqlParserPos.ZERO);
  }

  /**
   * Builds a two-element {@code ARRAY[a, b]} with arbitrary string representations.
   */
  private static SqlBasicCall arrayLiteral2(String a, String b) {
    SqlNode[] elems = new SqlNode[] {
        SqlLiteral.createExactNumeric(a, SqlParserPos.ZERO),
        SqlLiteral.createExactNumeric(b, SqlParserPos.ZERO)
    };
    return new SqlBasicCall(
        SqlStdOperatorTable.ARRAY_VALUE_CONSTRUCTOR, elems, SqlParserPos.ZERO);
  }

  /**
   * Renders a two-argument {@code SqlBasicCall} via {@link DremioPostgresDialect} and returns
   * the result as a string.
   *
   * @param op the function operator
   * @param left the first operand
   * @param right the second operand
   * @return the rendered SQL string
   */
  private static String render(SqlOperator op, SqlNode left, SqlNode right) {
    SqlNode[] operands = new SqlNode[] {left, right};
    SqlBasicCall call = new SqlBasicCall(op, operands, SqlParserPos.ZERO);
    SqlPrettyWriter writer = new SqlPrettyWriter(DremioPostgresDialect.INSTANCE);
    DremioPostgresDialect.INSTANCE.unparseCall(writer, call, 0, 0);
    return writer.toString();
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  /**
   * Test 1: {@code l2_distance(embedding, ARRAY[1.0, 2.0, 3.0])} renders as
   * {@code "embedding" <-> '[1.0,2.0,3.0]'}.
   *
   * <p>Verifies that the infix {@code <->} operator appears and that the function name
   * {@code l2_distance} does NOT appear in the output.
   */
  @Test
  public void testL2DistanceWithArrayLiteralRendersInfix() {
    SqlIdentifier col = new SqlIdentifier("embedding", SqlParserPos.ZERO);
    SqlBasicCall arr = arrayLiteral("1.0", "2.0", "3.0");
    String sql = render(distanceOp("l2_distance"), col, arr);

    assertTrue("SQL must contain <->: " + sql, sql.contains("<->"));
    assertTrue("SQL must contain pgvector literal '[: " + sql, sql.contains("'["));
    assertFalse("SQL must NOT contain 'l2_distance': " + sql, sql.contains("l2_distance"));
  }

  /**
   * Test 2: {@code cosine_distance(embedding, ARRAY[1.0, 2.0, 3.0])} renders with {@code <=>}.
   */
  @Test
  public void testCosineDistanceRendersInfix() {
    SqlIdentifier col = new SqlIdentifier("embedding", SqlParserPos.ZERO);
    SqlBasicCall arr = arrayLiteral("1.0", "2.0", "3.0");
    String sql = render(distanceOp("cosine_distance"), col, arr);

    assertTrue("SQL must contain <=>: " + sql, sql.contains("<=>"));
    assertTrue("SQL must contain pgvector literal '[: " + sql, sql.contains("'["));
    assertFalse("SQL must NOT contain 'cosine_distance': " + sql, sql.contains("cosine_distance"));
  }

  /**
   * Test 3: {@code inner_product(embedding, ARRAY[1.0, 2.0, 3.0])} renders with {@code <#>}.
   */
  @Test
  public void testInnerProductRendersInfix() {
    SqlIdentifier col = new SqlIdentifier("embedding", SqlParserPos.ZERO);
    SqlBasicCall arr = arrayLiteral("1.0", "2.0", "3.0");
    String sql = render(distanceOp("inner_product"), col, arr);

    assertTrue("SQL must contain <#>: " + sql, sql.contains("<#>"));
    assertTrue("SQL must contain pgvector literal '[: " + sql, sql.contains("'["));
    assertFalse("SQL must NOT contain 'inner_product': " + sql, sql.contains("inner_product"));
  }

  /**
   * Test 4: Both operands are column references (no ARRAY literal). Verifies that the infix
   * operator still appears, and that no pgvector string literal {@code '[} is emitted.
   *
   * <p>This is the column vs column form: {@code col_a <-> col_b}.
   */
  @Test
  public void testColumnVsColumnL2RendersInfix() {
    SqlIdentifier colA = new SqlIdentifier("col_a", SqlParserPos.ZERO);
    SqlIdentifier colB = new SqlIdentifier("col_b", SqlParserPos.ZERO);
    String sql = render(distanceOp("l2_distance"), colA, colB);

    assertTrue("SQL must contain <->: " + sql, sql.contains("<->"));
    assertFalse("SQL must NOT contain pgvector literal '[: " + sql, sql.contains("'["));
    assertTrue("SQL must contain col_a: " + sql, sql.contains("col_a"));
    assertTrue("SQL must contain col_b: " + sql, sql.contains("col_b"));
  }

  /**
   * Test 5: Non-distance function {@code UPPER("name")} is delegated to the parent class and
   * rendered as function-call syntax — not as an infix expression.
   */
  @Test
  public void testNonDistanceFunctionDelegatesToSuper() {
    SqlIdentifier col = new SqlIdentifier("name", SqlParserPos.ZERO);
    // Build UPPER(name) — a single-operand call
    SqlNode[] operands = new SqlNode[] {col};
    SqlBasicCall call = new SqlBasicCall(
        SqlStdOperatorTable.UPPER, operands, SqlParserPos.ZERO);
    SqlPrettyWriter writer = new SqlPrettyWriter(DremioPostgresDialect.INSTANCE);
    DremioPostgresDialect.INSTANCE.unparseCall(writer, call, 0, 0);
    String sql = writer.toString();

    assertTrue("SQL must contain UPPER: " + sql, sql.toUpperCase().contains("UPPER"));
    assertFalse("SQL must NOT contain <->: " + sql, sql.contains("<->"));
    assertFalse("SQL must NOT contain <=>: " + sql, sql.contains("<=>"));
    assertFalse("SQL must NOT contain <#>: " + sql, sql.contains("<#>"));
  }

  /**
   * Test 6: ARRAY literal values that would produce scientific notation with {@code toString()}
   * are rendered as plain decimal strings to satisfy pgvector's literal format.
   *
   * <p>Uses {@code 100} (stored in a BigDecimal that might render as {@code 1E+2} for
   * approximate types) to verify the output is {@code 100}, not {@code 1E+2} or similar.
   */
  @Test
  public void testArrayLiteralAvoidsSciNotation() {
    // 100 is stored exactly; in any case toPlainString() should give "100"
    SqlIdentifier col = new SqlIdentifier("embedding", SqlParserPos.ZERO);
    SqlBasicCall arr = arrayLiteral2("100", "0.25");
    String sql = render(distanceOp("l2_distance"), col, arr);

    assertTrue("SQL must contain <->: " + sql, sql.contains("<->"));
    // The pgvector literal must not contain 'E' notation
    // Extract the literal portion '[...]' from the SQL
    int start = sql.indexOf("'[");
    int end = sql.indexOf("]'", start);
    if (start >= 0 && end >= 0) {
      String literal = sql.substring(start, end + 2);
      assertFalse(
          "pgvector literal must not contain scientific notation 'E': " + literal,
          literal.contains("E") || literal.contains("e"));
      assertTrue(
          "pgvector literal must contain '100': " + literal,
          literal.contains("100"));
      assertTrue(
          "pgvector literal must contain '0.25': " + literal,
          literal.contains("0.25"));
    } else {
      assertTrue("SQL must contain a pgvector literal '[...': " + sql, start >= 0 && end >= 0);
    }
  }
}
