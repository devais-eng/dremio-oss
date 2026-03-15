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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Alias-aware converter for JOIN condition {@link RexNode} expressions.
 *
 * <p>This is a standalone class (not extending {@link RexToSqlString}) because the input-ref
 * handling for JOIN conditions requires two-sided aliasing: references with index &lt;
 * {@code leftFieldCount} are prefixed with {@code leftAlias} (e.g. {@code "t1"}), and references
 * with index &ge; {@code leftFieldCount} are prefixed with {@code rightAlias} (e.g. {@code "t2"}).
 *
 * <p>Supports: comparisons ({@code =, <>, <, <=, >, >=}), {@code AND}, {@code OR}, {@code NOT},
 * {@code IS NULL}, {@code IS NOT NULL}, and literal values as bind parameters.
 *
 * <p>Returns {@code null} if any component of the expression is unsupported, causing the JOIN
 * pushdown rule to decline.
 */
public final class RexToJoinSqlString {

  private final RelDataType joinRowType;
  private final int leftFieldCount;
  private final String leftAlias;
  private final String rightAlias;
  private final List<String> leftFieldNames;
  private final List<String> rightFieldNames;

  /**
   * Creates a new alias-aware JOIN condition converter.
   *
   * @param joinRowType the combined row type of the join (left fields followed by right fields)
   * @param leftFieldCount number of fields in the left table's row type
   * @param leftAlias table alias for left side references (e.g., "t1")
   * @param rightAlias table alias for right side references (e.g., "t2")
   * @param leftFieldNames original field names from the left table (before Calcite dedup)
   * @param rightFieldNames original field names from the right table (before Calcite dedup)
   */
  public RexToJoinSqlString(
      RelDataType joinRowType,
      int leftFieldCount,
      String leftAlias,
      String rightAlias,
      List<String> leftFieldNames,
      List<String> rightFieldNames) {
    this.joinRowType = joinRowType;
    this.leftFieldCount = leftFieldCount;
    this.leftAlias = leftAlias;
    this.rightAlias = rightAlias;
    this.leftFieldNames = leftFieldNames;
    this.rightFieldNames = rightFieldNames;
  }

  /** Backward-compatible constructor without original field names (uses Calcite names). */
  public RexToJoinSqlString(
      RelDataType joinRowType, int leftFieldCount, String leftAlias, String rightAlias) {
    this(joinRowType, leftFieldCount, leftAlias, rightAlias, null, null);
  }

  /**
   * Entry point: converts the given {@link RexNode} to a SQL expression with bind parameters, or
   * returns {@code null} if the expression cannot be translated.
   */
  public RexToSqlResult convert(RexNode node) {
    if (node instanceof RexCall) {
      return convertCall((RexCall) node);
    } else if (node instanceof RexInputRef) {
      return convertInputRef(((RexInputRef) node).getIndex());
    } else if (node instanceof RexLiteral) {
      return convertLiteral((RexLiteral) node);
    }
    return null;
  }

  private RexToSqlResult convertCall(RexCall call) {
    SqlKind kind = call.getKind();
    List<RexNode> operands = call.getOperands();

    switch (kind) {
      case EQUALS:
        return binaryOp(operands, "=");
      case NOT_EQUALS:
        return binaryOp(operands, "<>");
      case LESS_THAN:
        return binaryOp(operands, "<");
      case LESS_THAN_OR_EQUAL:
        return binaryOp(operands, "<=");
      case GREATER_THAN:
        return binaryOp(operands, ">");
      case GREATER_THAN_OR_EQUAL:
        return binaryOp(operands, ">=");
      case AND:
        return nAryOp(operands, "AND");
      case OR:
        return nAryOp(operands, "OR");
      case NOT:
        if (operands.size() == 1) {
          RexToSqlResult inner = convert(operands.get(0));
          return inner == null
              ? null
              : RexToSqlResult.literal("NOT (")
                  .merge(inner, "")
                  .merge(RexToSqlResult.literal(")"), "");
        }
        return null;
      case IS_NULL:
        if (operands.size() == 1) {
          RexToSqlResult col = convert(operands.get(0));
          return col == null ? null : col.merge(RexToSqlResult.literal(" IS NULL"), "");
        }
        return null;
      case IS_NOT_NULL:
        if (operands.size() == 1) {
          RexToSqlResult col = convert(operands.get(0));
          return col == null ? null : col.merge(RexToSqlResult.literal(" IS NOT NULL"), "");
        }
        return null;
      default:
        return null;
    }
  }

  /**
   * Converts a {@link RexInputRef} index to an alias-prefixed column reference.
   *
   * <p>Indices &lt; {@code leftFieldCount} map to the left table alias; indices &ge;
   * {@code leftFieldCount} map to the right table alias.
   */
  private RexToSqlResult convertInputRef(int index) {
    List<org.apache.calcite.rel.type.RelDataTypeField> fields = joinRowType.getFieldList();
    if (index < 0 || index >= fields.size()) {
      return null;
    }
    String alias;
    String fieldName;
    if (index < leftFieldCount) {
      alias = leftAlias;
      // Use original left table field name if available (avoids Calcite dedup suffixes)
      fieldName =
          (leftFieldNames != null && index < leftFieldNames.size())
              ? leftFieldNames.get(index)
              : fields.get(index).getName();
    } else {
      alias = rightAlias;
      int rightIdx = index - leftFieldCount;
      // Use original right table field name if available
      fieldName =
          (rightFieldNames != null && rightIdx < rightFieldNames.size())
              ? rightFieldNames.get(rightIdx)
              : fields.get(index).getName();
    }
    String quotedAlias = "\"" + alias.replace("\"", "\"\"") + "\"";
    String quotedField = "\"" + fieldName.replace("\"", "\"\"") + "\"";
    return RexToSqlResult.literal(quotedAlias + "." + quotedField);
  }

  private RexToSqlResult convertLiteral(RexLiteral literal) {
    if (RexLiteral.isNullLiteral(literal)) {
      return RexToSqlResult.literal("NULL");
    }
    SqlTypeName typeName = literal.getType().getSqlTypeName();
    switch (typeName) {
      case BOOLEAN:
        Boolean boolVal = (Boolean) literal.getValue();
        return boolVal == null
            ? RexToSqlResult.literal("NULL")
            : RexToSqlResult.literal(boolVal ? "TRUE" : "FALSE");
      case TINYINT:
      case SMALLINT:
      case INTEGER:
      case BIGINT:
      case FLOAT:
      case REAL:
      case DOUBLE:
      case DECIMAL:
        {
          BigDecimal numVal = (BigDecimal) literal.getValue();
          if (numVal == null) {
            return RexToSqlResult.literal("NULL");
          }
          return RexToSqlResult.param(new BindParam(numVal, typeName));
        }
      case CHAR:
      case VARCHAR:
        {
          String strVal = literal.getValueAs(String.class);
          if (strVal == null) {
            return RexToSqlResult.literal("NULL");
          }
          return RexToSqlResult.param(new BindParam(strVal, typeName));
        }
      case DATE:
        {
          Calendar cal = (Calendar) literal.getValue();
          if (cal == null) {
            return RexToSqlResult.literal("NULL");
          }
          java.sql.Date dateVal = new java.sql.Date(cal.getTimeInMillis());
          return RexToSqlResult.param(new BindParam(dateVal.getTime(), SqlTypeName.DATE));
        }
      case TIME:
        {
          Calendar cal = (Calendar) literal.getValue();
          if (cal == null) {
            return RexToSqlResult.literal("NULL");
          }
          java.sql.Time timeVal = new java.sql.Time(cal.getTimeInMillis());
          return RexToSqlResult.param(new BindParam(timeVal.getTime(), SqlTypeName.TIME));
        }
      case TIMESTAMP:
        {
          Calendar cal = (Calendar) literal.getValue();
          if (cal == null) {
            return RexToSqlResult.literal("NULL");
          }
          java.sql.Timestamp tsVal = new java.sql.Timestamp(cal.getTimeInMillis());
          return RexToSqlResult.param(new BindParam(tsVal.getTime(), SqlTypeName.TIMESTAMP));
        }
      default:
        return null;
    }
  }

  private RexToSqlResult binaryOp(List<RexNode> operands, String op) {
    if (operands.size() != 2) {
      return null;
    }
    RexToSqlResult left = convert(operands.get(0));
    RexToSqlResult right = convert(operands.get(1));
    if (left == null || right == null) {
      return null;
    }
    return left.merge(right, " " + op + " ");
  }

  private RexToSqlResult nAryOp(List<RexNode> operands, String op) {
    if (operands.isEmpty()) {
      return null;
    }
    List<BindParam> allParams = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < operands.size(); i++) {
      RexToSqlResult part = convert(operands.get(i));
      if (part == null) {
        return null;
      }
      if (i > 0) {
        sb.append(" ").append(op).append(" ");
      }
      sb.append("(").append(part.getSql()).append(")");
      allParams.addAll(part.getParams());
    }
    return new RexToSqlResult(sb.toString(), allParams);
  }
}
