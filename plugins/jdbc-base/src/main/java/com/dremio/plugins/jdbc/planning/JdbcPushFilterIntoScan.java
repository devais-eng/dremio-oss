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

import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.physical.FilterPrel;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Pushdown rule that converts a {@link FilterPrel} above a {@link JdbcScanPrel}
 * into a WHERE clause carried by the scan node itself (BASE-05).
 *
 * <p>The rule translates the filter's {@code RexNode} condition into a SQL
 * string using an inner {@link RexToSqlString} visitor. If any part of the
 * predicate cannot be translated (e.g. sub-queries, window functions, unsupported
 * operators), the rule declines to push down and leaves the FilterPrel in place
 * so Dremio handles it in-engine.
 */
public class JdbcPushFilterIntoScan extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushFilterIntoScan();

  private JdbcPushFilterIntoScan() {
    super(
        RelOptHelper.some(FilterPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushFilterIntoScan");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    JdbcScanPrel scan = call.rel(1);
    // Do not push a second filter if one is already present.
    return !scan.hasFilter();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    FilterPrel filter = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    String whereClause =
        new RexToSqlString(scan.getRowType()).convert(filter.getCondition());
    if (whereClause == null) {
      // Unsupported expression — do not push down.
      return;
    }

    call.transformTo(scan.cloneWithFilter(whereClause));
  }

  // -------------------------------------------------------------------------
  // RexNode → SQL WHERE expression converter
  // -------------------------------------------------------------------------

  /**
   * Converts a Calcite {@link RexNode} filter condition to a SQL WHERE expression string.
   *
   * <p>Supports a subset of SQL operators sufficient for common JDBC pushdown:
   * comparisons ({@code =, <>, <, <=, >, >=}), {@code AND}, {@code OR}, {@code NOT},
   * {@code IS NULL}, {@code IS NOT NULL}, and {@code LIKE}. Column references are
   * resolved from the scan's row type; literals are rendered as SQL constants.
   *
   * <p>Returns {@code null} if any component of the expression is unsupported.
   */
  static final class RexToSqlString {

    private final RelDataType rowType;

    RexToSqlString(RelDataType rowType) {
      this.rowType = rowType;
    }

    /**
     * Entry point: converts the given {@link RexNode} to a SQL expression string,
     * or returns {@code null} if the expression cannot be translated.
     */
    String convert(RexNode node) {
      if (node instanceof RexCall) {
        return convertCall((RexCall) node);
      } else if (node instanceof RexInputRef) {
        return convertInputRef((RexInputRef) node);
      } else if (node instanceof RexLiteral) {
        return convertLiteral((RexLiteral) node);
      }
      return null; // unsupported node type
    }

    private String convertCall(RexCall call) {
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
        case AND:
          return nAryOp(operands, "AND");
        case OR:
          return nAryOp(operands, "OR");
        case NOT:
          if (operands.size() == 1) {
            String inner = convert(operands.get(0));
            return inner == null ? null : "NOT (" + inner + ")";
          }
          return null;
        case IS_NULL:
          if (operands.size() == 1) {
            String col = convert(operands.get(0));
            return col == null ? null : col + " IS NULL";
          }
          return null;
        case IS_NOT_NULL:
          if (operands.size() == 1) {
            String col = convert(operands.get(0));
            return col == null ? null : col + " IS NOT NULL";
          }
          return null;
        default:
          return null; // unsupported operator — decline pushdown
      }
    }

    private String binaryOp(List<RexNode> operands, String op) {
      if (operands.size() != 2) {
        return null;
      }
      String left = convert(operands.get(0));
      String right = convert(operands.get(1));
      if (left == null || right == null) {
        return null;
      }
      return left + " " + op + " " + right;
    }

    private String nAryOp(List<RexNode> operands, String op) {
      if (operands.isEmpty()) {
        return null;
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < operands.size(); i++) {
        String part = convert(operands.get(i));
        if (part == null) {
          return null;
        }
        if (i > 0) {
          sb.append(" ").append(op).append(" ");
        }
        sb.append("(").append(part).append(")");
      }
      return sb.toString();
    }

    private String convertInputRef(RexInputRef ref) {
      int index = ref.getIndex();
      List<RelDataTypeField> fields = rowType.getFieldList();
      if (index < 0 || index >= fields.size()) {
        return null;
      }
      String fieldName = fields.get(index).getName();
      // Double-quote the column name for identifier safety.
      return "\"" + fieldName.replace("\"", "\"\"") + "\"";
    }

    private String convertLiteral(RexLiteral literal) {
      if (RexLiteral.isNullLiteral(literal)) {
        return "NULL";
      }
      SqlTypeName typeName = literal.getType().getSqlTypeName();
      switch (typeName) {
        case BOOLEAN:
          Boolean boolVal = (Boolean) literal.getValue();
          return boolVal == null ? "NULL" : (boolVal ? "TRUE" : "FALSE");
        case TINYINT:
        case SMALLINT:
        case INTEGER:
        case BIGINT:
        case FLOAT:
        case REAL:
        case DOUBLE:
        case DECIMAL: {
          BigDecimal numVal = (BigDecimal) literal.getValue();
          return numVal == null ? "NULL" : numVal.toPlainString();
        }
        case CHAR:
        case VARCHAR: {
          // RexLiteral stores VARCHAR/CHAR as NlsString; getValueAs(String.class) is safe.
          String strVal = literal.getValueAs(String.class);
          if (strVal == null) {
            return "NULL";
          }
          // Escape single quotes per SQL standard.
          return "'" + strVal.replace("'", "''") + "'";
        }
        case DATE: {
          Calendar cal = (Calendar) literal.getValue();
          if (cal == null) {
            return "NULL";
          }
          return String.format(
              "DATE '%04d-%02d-%02d'",
              cal.get(Calendar.YEAR),
              cal.get(Calendar.MONTH) + 1,
              cal.get(Calendar.DAY_OF_MONTH));
        }
        case TIME: {
          Calendar cal = (Calendar) literal.getValue();
          if (cal == null) {
            return "NULL";
          }
          return String.format(
              "TIME '%02d:%02d:%02d'",
              cal.get(Calendar.HOUR_OF_DAY),
              cal.get(Calendar.MINUTE),
              cal.get(Calendar.SECOND));
        }
        case TIMESTAMP: {
          Calendar cal = (Calendar) literal.getValue();
          if (cal == null) {
            return "NULL";
          }
          return String.format(
              "TIMESTAMP '%04d-%02d-%02d %02d:%02d:%02d'",
              cal.get(Calendar.YEAR),
              cal.get(Calendar.MONTH) + 1,
              cal.get(Calendar.DAY_OF_MONTH),
              cal.get(Calendar.HOUR_OF_DAY),
              cal.get(Calendar.MINUTE),
              cal.get(Calendar.SECOND));
        }
        default:
          return null; // unsupported literal type — decline pushdown
      }
    }
  }
}
