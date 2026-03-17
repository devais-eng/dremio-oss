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

import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;

/**
 * Per-dialect whitelist that controls which SQL functions may be pushed to the remote source.
 *
 * <p>Implementations return {@code true} from {@link #isFunctionPushable} for each function
 * operator they support. The default {@link #isExpressionPushable} method performs a recursive
 * walk of the entire {@link RexNode} tree, accepting only:
 * <ul>
 *   <li>Column references ({@link RexInputRef})</li>
 *   <li>Literals ({@link RexLiteral})</li>
 *   <li>Comparison, logical, and arithmetic operators (always safe)</li>
 *   <li>Function calls where every operand is also pushable and the operator is whitelisted</li>
 * </ul>
 *
 * <p>Any other node type (e.g. {@code RexFieldAccess}, {@code RexDynamicParam}) is rejected.
 *
 * <p>The primary implementation is {@link StandardPushdownFunctionRegistry}. Dialect-specific
 * subclasses can extend it to add database-specific functions (e.g. pgvector operators).
 */
public interface PushdownFunctionRegistry {

  /**
   * Returns {@code true} if the given SQL operator can be pushed down to the remote source.
   *
   * <p>This method is called by the default {@link #isExpressionPushable} implementation for
   * each {@link RexCall} node whose kind is not one of the unconditionally-allowed operators
   * (comparison, logical, arithmetic, IS NULL / IS NOT NULL, LIKE, BETWEEN, IN, NOT, CASE).
   *
   * @param op the SQL operator to check
   * @return {@code true} if the operator is safe to push down
   */
  boolean isFunctionPushable(SqlOperator op);

  /**
   * Returns {@code true} when the entire {@link RexNode} tree rooted at {@code expr} is safe
   * to push down to the remote source.
   *
   * <p>The recursive walk accepts:
   * <ul>
   *   <li>{@link RexInputRef} — column reference, always safe</li>
   *   <li>{@link RexLiteral} — constant value, always safe</li>
   *   <li>{@link RexCall} with comparison/logical/arithmetic kind — safe if all operands are safe</li>
   *   <li>{@link RexCall} for a whitelisted function — safe if all operands are safe</li>
   * </ul>
   * Any other node type or unwhitelisted function causes the entire expression to be rejected.
   *
   * @param expr the expression to validate
   * @return {@code true} if every node in the tree is pushable
   */
  default boolean isExpressionPushable(RexNode expr) {
    return expr.accept(new RexVisitorImpl<Boolean>(true) {
      @Override
      public Boolean visitInputRef(RexInputRef ref) {
        return Boolean.TRUE;
      }

      @Override
      public Boolean visitLiteral(RexLiteral lit) {
        return Boolean.TRUE;
      }

      @Override
      public Boolean visitCall(RexCall call) {
        SqlKind kind = call.getKind();
        // Comparison, logical, and arithmetic operators are always pushable
        // as long as their operands are also pushable.
        if (kind.belongsTo(SqlKind.COMPARISON)
            || kind == SqlKind.AND
            || kind == SqlKind.OR
            || kind == SqlKind.NOT
            || kind == SqlKind.PLUS
            || kind == SqlKind.MINUS
            || kind == SqlKind.TIMES
            || kind == SqlKind.DIVIDE
            || kind == SqlKind.IS_NULL
            || kind == SqlKind.IS_NOT_NULL
            || kind == SqlKind.LIKE
            || kind == SqlKind.BETWEEN
            || kind == SqlKind.IN
            || kind == SqlKind.NOT_IN
            || kind == SqlKind.CASE) {
          return visitOperands(call);
        }
        // For all other calls (function calls, CAST, TRIM, etc.),
        // check the whitelist before recursing into operands.
        if (!isFunctionPushable(call.getOperator())) {
          return Boolean.FALSE;
        }
        return visitOperands(call);
      }

      private Boolean visitOperands(RexCall call) {
        for (RexNode operand : call.getOperands()) {
          if (operand.accept(this) != Boolean.TRUE) {
            return Boolean.FALSE;
          }
        }
        return Boolean.TRUE;
      }
    }) == Boolean.TRUE;
  }
}
