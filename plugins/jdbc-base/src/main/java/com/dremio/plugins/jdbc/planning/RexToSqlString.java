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
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Converts a Calcite {@link RexNode} filter condition to a SQL WHERE expression with
 * PreparedStatement bind parameters.
 *
 * <p>Returns {@link RexToSqlResult} containing the SQL string with {@code ?} placeholders and an
 * ordered list of {@link BindParam} values. Returns {@code null} if any component of the expression
 * is unsupported.
 *
 * <p>Supports: comparisons ({@code =, <>, <, <=, >, >=}), {@code AND}, {@code OR}, {@code NOT},
 * {@code IS NULL}, {@code IS NOT NULL}, {@code LIKE}, arithmetic ({@code +, -, *, /}), {@code IN},
 * {@code BETWEEN}, {@code COALESCE}, and {@code NULLIF}.
 *
 * <p>Literal values are emitted as {@code ?} bind parameters except for {@code NULL}, {@code TRUE},
 * and {@code FALSE} which remain inline as SQL keywords.
 *
 * <p>OR-of-EQUALS chains on the same column are automatically converted to {@code col IN (?, ?,
 * ...)} for cleaner SQL.
 */
public final class RexToSqlString {

  private final RelDataType rowType;

  public RexToSqlString(RelDataType rowType) {
    this.rowType = rowType;
  }

  /**
   * Entry point: converts the given {@link RexNode} to a SQL expression with bind parameters, or
   * returns {@code null} if the expression cannot be translated.
   */
  public RexToSqlResult convert(RexNode node) {
    if (node instanceof RexCall) {
      return convertCall((RexCall) node);
    } else if (node instanceof RexInputRef) {
      return convertInputRef((RexInputRef) node);
    } else if (node instanceof RexLiteral) {
      return convertLiteral((RexLiteral) node);
    }
    return null; // unsupported node type
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
      case LIKE:
        return binaryOp(operands, "LIKE");
      case PLUS:
        return binaryOp(operands, "+");
      case MINUS:
        return binaryOp(operands, "-");
      case TIMES:
        return binaryOp(operands, "*");
      case DIVIDE:
        return binaryOp(operands, "/");
      case AND:
        return nAryOp(operands, "AND");
      case OR:
        return convertOr(operands);
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
      case IN:
        return convertIn(operands);
      case BETWEEN:
        return convertBetween(operands);
      case OTHER_FUNCTION:
        return convertOtherFunction(call, operands);
      default:
        return null; // unsupported operator -- decline pushdown
    }
  }

  /**
   * Handles OR operands. Before falling back to standard OR handling, attempts to detect
   * OR-of-EQUALS on the same column and convert to IN.
   */
  private RexToSqlResult convertOr(List<RexNode> operands) {
    RexToSqlResult inResult = tryConvertToIn(operands);
    if (inResult != null) {
      return inResult;
    }
    return nAryOp(operands, "OR");
  }

  /**
   * Checks if ALL operands are EQUALS calls where one side is a RexInputRef referencing the SAME
   * column index. If so, reconstructs as "col" IN (?, ?, ...).
   */
  private RexToSqlResult tryConvertToIn(List<RexNode> operands) {
    if (operands.size() < 2) {
      return null;
    }

    Integer commonIndex = null;
    List<RexNode> valueNodes = new ArrayList<>();

    for (RexNode operand : operands) {
      if (!(operand instanceof RexCall)) {
        return null;
      }
      RexCall call = (RexCall) operand;
      if (call.getKind() != SqlKind.EQUALS || call.getOperands().size() != 2) {
        return null;
      }
      RexNode left = call.getOperands().get(0);
      RexNode right = call.getOperands().get(1);

      // Determine which side is the column ref and which is the value.
      int colIndex;
      RexNode valueNode;
      if (left instanceof RexInputRef && !(right instanceof RexInputRef)) {
        colIndex = ((RexInputRef) left).getIndex();
        valueNode = right;
      } else if (right instanceof RexInputRef && !(left instanceof RexInputRef)) {
        colIndex = ((RexInputRef) right).getIndex();
        valueNode = left;
      } else {
        return null; // both sides are refs or both are literals
      }

      if (commonIndex == null) {
        commonIndex = colIndex;
      } else if (commonIndex != colIndex) {
        return null; // different columns -- not convertible to single IN
      }
      valueNodes.add(valueNode);
    }

    // All operands reference the same column; build IN expression.
    RexToSqlResult colResult = convertInputRef(commonIndex);
    if (colResult == null) {
      return null;
    }

    List<BindParam> allParams = new ArrayList<>(colResult.getParams());
    StringBuilder sb = new StringBuilder(colResult.getSql());
    sb.append(" IN (");

    for (int i = 0; i < valueNodes.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      RexToSqlResult valResult = convert(valueNodes.get(i));
      if (valResult == null) {
        return null;
      }
      sb.append(valResult.getSql());
      allParams.addAll(valResult.getParams());
    }
    sb.append(")");

    return new RexToSqlResult(sb.toString(), allParams);
  }

  /**
   * Handles SqlKind.IN if Calcite preserves it (safety net). First operand is the column,
   * subsequent operands are values.
   */
  private RexToSqlResult convertIn(List<RexNode> operands) {
    if (operands.size() < 2) {
      return null;
    }

    RexToSqlResult colResult = convert(operands.get(0));
    if (colResult == null) {
      return null;
    }

    List<BindParam> allParams = new ArrayList<>(colResult.getParams());
    StringBuilder sb = new StringBuilder(colResult.getSql());
    sb.append(" IN (");

    for (int i = 1; i < operands.size(); i++) {
      if (i > 1) {
        sb.append(", ");
      }
      RexToSqlResult valResult = convert(operands.get(i));
      if (valResult == null) {
        return null;
      }
      sb.append(valResult.getSql());
      allParams.addAll(valResult.getParams());
    }
    sb.append(")");

    return new RexToSqlResult(sb.toString(), allParams);
  }

  /**
   * Handles BETWEEN: 3 operands -- col BETWEEN low AND high. Note: some Calcite versions represent
   * BETWEEN as AND(>=, <=), but we handle the explicit BETWEEN node as a safety net.
   */
  private RexToSqlResult convertBetween(List<RexNode> operands) {
    if (operands.size() != 3) {
      return null;
    }

    RexToSqlResult col = convert(operands.get(0));
    RexToSqlResult low = convert(operands.get(1));
    RexToSqlResult high = convert(operands.get(2));
    if (col == null || low == null || high == null) {
      return null;
    }

    // col BETWEEN low AND high
    List<BindParam> allParams = new ArrayList<>();
    allParams.addAll(col.getParams());
    allParams.addAll(low.getParams());
    allParams.addAll(high.getParams());
    String sql = col.getSql() + " BETWEEN " + low.getSql() + " AND " + high.getSql();
    return new RexToSqlResult(sql, allParams);
  }

  /** Handles OTHER_FUNCTION calls such as COALESCE and NULLIF. */
  private RexToSqlResult convertOtherFunction(RexCall call, List<RexNode> operands) {
    String funcName = call.getOperator().getName().toUpperCase();

    switch (funcName) {
      case "COALESCE":
        return convertFunctionCall("COALESCE", operands);
      case "NULLIF":
        if (operands.size() == 2) {
          return convertFunctionCall("NULLIF", operands);
        }
        return null;
      default:
        return null;
    }
  }

  /** Converts a function call of the form FUNC(arg1, arg2, ...). */
  private RexToSqlResult convertFunctionCall(String funcName, List<RexNode> operands) {
    if (operands.isEmpty()) {
      return null;
    }

    List<BindParam> allParams = new ArrayList<>();
    StringBuilder sb = new StringBuilder(funcName);
    sb.append("(");

    for (int i = 0; i < operands.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      RexToSqlResult argResult = convert(operands.get(i));
      if (argResult == null) {
        return null;
      }
      sb.append(argResult.getSql());
      allParams.addAll(argResult.getParams());
    }
    sb.append(")");

    return new RexToSqlResult(sb.toString(), allParams);
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

  private RexToSqlResult convertInputRef(RexInputRef ref) {
    return convertInputRef(ref.getIndex());
  }

  private RexToSqlResult convertInputRef(int index) {
    List<RelDataTypeField> fields = rowType.getFieldList();
    if (index < 0 || index >= fields.size()) {
      return null;
    }
    String fieldName = fields.get(index).getName();
    // Double-quote the column name for identifier safety.
    String quotedName = "\"" + fieldName.replace("\"", "\"\"") + "\"";
    return RexToSqlResult.literal(quotedName);
  }

  private RexToSqlResult convertLiteral(RexLiteral literal) {
    if (RexLiteral.isNullLiteral(literal)) {
      return RexToSqlResult.literal("NULL");
    }
    SqlTypeName typeName = literal.getType().getSqlTypeName();
    switch (typeName) {
      case BOOLEAN:
        // Boolean literals stay inline as SQL keywords (TRUE/FALSE), not injection risk.
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
        return null; // unsupported literal type -- decline pushdown
    }
  }
}
