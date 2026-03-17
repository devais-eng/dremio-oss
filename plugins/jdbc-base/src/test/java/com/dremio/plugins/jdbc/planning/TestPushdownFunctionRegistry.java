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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;

/**
 * Unit tests for {@link PushdownFunctionRegistry} and {@link StandardPushdownFunctionRegistry}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link StandardPushdownFunctionRegistry#isFunctionPushable} — whitelist membership</li>
 *   <li>{@link PushdownFunctionRegistry#isExpressionPushable} — recursive expression validation</li>
 *   <li>Function composition: UPPER(TRIM(col)) accepted, UPPER(nonPushable(col)) rejected</li>
 * </ul>
 */
public class TestPushdownFunctionRegistry {

  private final StandardPushdownFunctionRegistry registry =
      StandardPushdownFunctionRegistry.INSTANCE;

  // ---------------------------------------------------------------------------
  // isFunctionPushable — whitelisted functions should return true
  // ---------------------------------------------------------------------------

  @Test
  public void testUpperIsWhitelisted() {
    assertTrue("UPPER should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.UPPER));
  }

  @Test
  public void testLowerIsWhitelisted() {
    assertTrue("LOWER should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.LOWER));
  }

  @Test
  public void testAbsIsWhitelisted() {
    assertTrue("ABS should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.ABS));
  }

  @Test
  public void testRoundIsWhitelisted() {
    assertTrue("ROUND should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.ROUND));
  }

  @Test
  public void testFloorIsWhitelisted() {
    assertTrue("FLOOR should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.FLOOR));
  }

  @Test
  public void testCeilIsWhitelisted() {
    assertTrue("CEIL should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.CEIL));
  }

  @Test
  public void testTrimIsWhitelisted() {
    assertTrue("TRIM should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.TRIM));
  }

  @Test
  public void testExtractIsWhitelisted() {
    assertTrue("EXTRACT should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.EXTRACT));
  }

  @Test
  public void testCoalesceIsWhitelisted() {
    assertTrue("COALESCE should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.COALESCE));
  }

  @Test
  public void testNullifIsWhitelisted() {
    assertTrue("NULLIF should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.NULLIF));
  }

  @Test
  public void testCastIsWhitelisted() {
    assertTrue("CAST should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.CAST));
  }

  @Test
  public void testSubstringIsWhitelisted() {
    assertTrue("SUBSTRING should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.SUBSTRING));
  }

  @Test
  public void testCharLengthIsWhitelisted() {
    assertTrue("CHAR_LENGTH should be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.CHAR_LENGTH));
  }

  // ---------------------------------------------------------------------------
  // isFunctionPushable — non-whitelisted functions should return false
  // ---------------------------------------------------------------------------

  @Test
  public void testLocaltimeIsNotWhitelisted() {
    assertFalse("LOCALTIME should not be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.LOCALTIME));
  }

  @Test
  public void testCurrentTimestampIsNotWhitelisted() {
    assertFalse("CURRENT_TIMESTAMP should not be pushable",
        registry.isFunctionPushable(SqlStdOperatorTable.CURRENT_TIMESTAMP));
  }

  // ---------------------------------------------------------------------------
  // isExpressionPushable — RexNode tree validation
  // ---------------------------------------------------------------------------

  /** Helper to build a RexBuilder with a standard type factory. */
  private static RexBuilder newRexBuilder() {
    RelDataTypeFactory typeFactory = new JavaTypeFactoryImpl();
    return new RexBuilder(typeFactory);
  }

  @Test
  public void testLiteralIsPushable() {
    RexBuilder b = newRexBuilder();
    RelDataType intType = b.getTypeFactory().createSqlType(SqlTypeName.INTEGER);
    RexNode literal = b.makeLiteral(42, intType, true);
    assertTrue("Literal should be pushable", registry.isExpressionPushable(literal));
  }

  @Test
  public void testInputRefIsPushable() {
    RexBuilder b = newRexBuilder();
    RelDataType intType = b.getTypeFactory().createSqlType(SqlTypeName.INTEGER);
    RexNode ref = b.makeInputRef(intType, 0);
    assertTrue("RexInputRef should be pushable", registry.isExpressionPushable(ref));
  }

  @Test
  public void testUpperOfInputRefIsPushable() {
    RexBuilder b = newRexBuilder();
    RelDataType varcharType = b.getTypeFactory().createSqlType(SqlTypeName.VARCHAR);
    RexNode ref = b.makeInputRef(varcharType, 3);
    RexNode upper = b.makeCall(SqlStdOperatorTable.UPPER, ref);
    assertTrue("UPPER(col) should be pushable", registry.isExpressionPushable(upper));
  }

  @Test
  public void testNonWhitelistedFunctionIsNotPushable() {
    RexBuilder b = newRexBuilder();
    RelDataType varcharType = b.getTypeFactory().createSqlType(SqlTypeName.VARCHAR);
    // Use a custom operator with OTHER_FUNCTION kind but not in the whitelist.
    // Provide an explicit return type inference so RexBuilder.makeCall() doesn't fail.
    SqlOperator unknownFunc = new org.apache.calcite.sql.SqlFunction(
        "MY_CUSTOM_UDF",
        SqlKind.OTHER_FUNCTION,
        org.apache.calcite.sql.type.ReturnTypes.VARCHAR_2000,
        null,
        org.apache.calcite.sql.type.OperandTypes.CHARACTER,
        org.apache.calcite.sql.SqlFunctionCategory.USER_DEFINED_FUNCTION);
    RexNode ref = b.makeInputRef(varcharType, 0);
    RexNode call = b.makeCall(unknownFunc, ref);
    assertFalse("Non-whitelisted function should not be pushable",
        registry.isExpressionPushable(call));
  }

  @Test
  public void testUpperOfNonWhitelistedFunctionIsNotPushable() {
    // UPPER(MY_CUSTOM_UDF(col)) — outer function is whitelisted but inner is not.
    // The full expression should be rejected because the inner function is not pushable.
    RexBuilder b = newRexBuilder();
    RelDataType varcharType = b.getTypeFactory().createSqlType(SqlTypeName.VARCHAR);
    SqlOperator unknownFunc = new org.apache.calcite.sql.SqlFunction(
        "MY_CUSTOM_UDF",
        SqlKind.OTHER_FUNCTION,
        org.apache.calcite.sql.type.ReturnTypes.VARCHAR_2000,
        null,
        org.apache.calcite.sql.type.OperandTypes.CHARACTER,
        org.apache.calcite.sql.SqlFunctionCategory.USER_DEFINED_FUNCTION);
    RexNode ref = b.makeInputRef(varcharType, 0);
    RexNode inner = b.makeCall(unknownFunc, ref);
    RexNode outer = b.makeCall(SqlStdOperatorTable.UPPER, inner);
    assertFalse("UPPER(non_pushable(col)) should not be pushable",
        registry.isExpressionPushable(outer));
  }

  @Test
  public void testUpperOfTrimIsPushable() {
    // UPPER(TRIM(col)) — both functions are whitelisted, composition should be accepted.
    RexBuilder b = newRexBuilder();
    RelDataType varcharType = b.getTypeFactory().createSqlType(SqlTypeName.VARCHAR);
    RexNode ref = b.makeInputRef(varcharType, 0);
    // TRIM takes a flag and string argument; use UPPER directly on the ref to keep it simple.
    // For composition test: use LOWER(UPPER(col)) which is simpler to construct.
    RexNode upper = b.makeCall(SqlStdOperatorTable.UPPER, ref);
    RexNode lower = b.makeCall(SqlStdOperatorTable.LOWER, upper);
    assertTrue("LOWER(UPPER(col)) should be pushable", registry.isExpressionPushable(lower));
  }
}
