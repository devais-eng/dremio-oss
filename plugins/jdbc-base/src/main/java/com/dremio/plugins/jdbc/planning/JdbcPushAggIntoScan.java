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
import com.dremio.exec.planner.physical.AggregatePrel;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * Pushdown rule that absorbs a single-phase {@link AggregatePrel} (either {@code HashAggPrel} or
 * {@code StreamAggPrel}) into the {@link JdbcScanPrel} below it, so the generated SQL includes a
 * GROUP BY clause and aggregate functions in the SELECT list.
 *
 * <p>Only {@link AggregatePrel.OperatorPhase#PHASE_1of1 PHASE_1of1} aggregates are pushed down.
 * Two-phase distributed aggregates ({@code PHASE_1of2} and {@code PHASE_2of2}) are rejected because
 * pushing a partial aggregate to a single JDBC source would produce incorrect results.
 *
 * <p>Supported aggregate functions: COUNT, SUM, SUM0, MIN, MAX, AVG. DISTINCT aggregates are not
 * supported in v1 and cause the rule to decline.
 *
 * <p>The rule replaces the AggregatePrel with a JdbcScanPrel that carries aggregation-specific
 * state: {@code selectExprs} (the aggregate SELECT list) and {@code groupByClause}. The scan's
 * output rowType is updated to match the aggregated schema expected by the optimizer.
 *
 * <p>Registered in the {@code PHYSICAL} planner phase via {@link JdbcRulesFactory}. Not registered
 * in {@code PHYSICAL_HEP} because aggregation is a structural transformation that changes the
 * rowType and benefits from cost-based decisions in the Volcano planner.
 */
public final class JdbcPushAggIntoScan extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushAggIntoScan();

  private JdbcPushAggIntoScan() {
    super(
        RelOptHelper.some(AggregatePrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushAggIntoScan");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    AggregatePrel agg = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    // Only push single-phase aggregates. Two-phase partial/final aggregates are
    // incorrect to push to a single JDBC source.
    if (agg.getOperatorPhase() != AggregatePrel.OperatorPhase.PHASE_1of1) {
      return false;
    }

    // Do not push a second aggregation into a scan that already has one.
    if (scan.hasAggregation()) {
      return false;
    }

    // Reject DISTINCT aggregates (not supported in v1).
    for (AggregateCall aggCall : agg.getAggCallList()) {
      if (aggCall.isDistinct()) {
        return false;
      }
    }

    // Reject unsupported aggregate functions.
    for (AggregateCall aggCall : agg.getAggCallList()) {
      SqlKind kind = aggCall.getAggregation().getKind();
      switch (kind) {
        case COUNT:
        case SUM:
        case SUM0:
        case MIN:
        case MAX:
        case AVG:
          break;
        default:
          return false;
      }
    }

    return true;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    AggregatePrel agg = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    RelDataType scanRowType = scan.getRowType();
    List<RelDataTypeField> scanFields = scanRowType.getFieldList();
    ImmutableBitSet groupSet = agg.getGroupSet();
    List<AggregateCall> aggCalls = agg.getAggCallList();

    // Build GROUP BY column list.
    List<String> groupByCols = new ArrayList<>();
    for (int idx : groupSet) {
      if (idx < 0 || idx >= scanFields.size()) {
        return; // Out of bounds -- decline.
      }
      groupByCols.add(quoteField(scanFields.get(idx)));
    }
    String groupByClause = groupByCols.isEmpty() ? null : String.join(", ", groupByCols);

    // The output rowType must match the AggregatePrel's expected output.
    RelDataType newRowType = agg.getRowType();
    List<RelDataTypeField> outFields = newRowType.getFieldList();

    // Build SELECT expressions: group-by columns first, then aggregate expressions.
    // Each expression is aliased to match the Calcite output field name so that the
    // JDBC ResultSet column labels align with the schema expected by JdbcRecordReader.
    List<String> selectExprs = new ArrayList<>();
    int outIdx = 0;
    for (String groupCol : groupByCols) {
      String alias = quoteIdentifier(outFields.get(outIdx++).getName());
      selectExprs.add(groupCol + " AS " + alias);
    }
    for (AggregateCall aggCall : aggCalls) {
      String expr = aggCallToSql(aggCall, scanFields);
      if (expr == null) {
        return; // Unsupported aggregate -- decline.
      }
      String alias = quoteIdentifier(outFields.get(outIdx++).getName());
      selectExprs.add(expr + " AS " + alias);
    }

    JdbcScanPrel newScan = scan.cloneWithAggregation(selectExprs, groupByClause, newRowType);
    call.transformTo(newScan);
  }

  /**
   * Translates an {@link AggregateCall} into a SQL aggregate expression string.
   *
   * @param aggCall the aggregate call to translate
   * @param fields the field list from the scan's row type
   * @return the SQL expression, or null if the aggregate is unsupported
   */
  private static String aggCallToSql(AggregateCall aggCall, List<RelDataTypeField> fields) {
    SqlKind kind = aggCall.getAggregation().getKind();
    switch (kind) {
      case COUNT:
        if (aggCall.getArgList().isEmpty()) {
          return "COUNT(*)";
        } else {
          int argIdx = aggCall.getArgList().get(0);
          if (argIdx < 0 || argIdx >= fields.size()) {
            return null;
          }
          return "COUNT(" + quoteField(fields.get(argIdx)) + ")";
        }
      case SUM:
      case SUM0:
        return singleArgAgg("SUM", aggCall, fields);
      case MIN:
        return singleArgAgg("MIN", aggCall, fields);
      case MAX:
        return singleArgAgg("MAX", aggCall, fields);
      case AVG:
        return singleArgAgg("AVG", aggCall, fields);
      default:
        return null;
    }
  }

  /** Builds a single-argument aggregate function expression: {@code FUNC("column")}. */
  private static String singleArgAgg(
      String funcName, AggregateCall aggCall, List<RelDataTypeField> fields) {
    if (aggCall.getArgList().isEmpty()) {
      return null;
    }
    int argIdx = aggCall.getArgList().get(0);
    if (argIdx < 0 || argIdx >= fields.size()) {
      return null;
    }
    return funcName + "(" + quoteField(fields.get(argIdx)) + ")";
  }

  /** Quotes a field name with double-quote escaping per the SQL standard. */
  private static String quoteField(RelDataTypeField field) {
    return quoteIdentifier(field.getName());
  }

  /** Quotes an identifier with double-quote escaping per the SQL standard. */
  private static String quoteIdentifier(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }
}
