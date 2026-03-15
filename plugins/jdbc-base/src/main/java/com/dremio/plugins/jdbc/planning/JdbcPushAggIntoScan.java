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

import com.dremio.common.expression.SchemaPath;
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
 * {@code StreamAggPrel}) into the {@link JdbcScanPrel} below it, storing the Calcite
 * {@link ImmutableBitSet} groupSet and {@link AggregateCall} list directly on the scan.
 *
 * <p>GroupSet bits and AggregateCall argument indices are <em>normalized to full-table column
 * indices</em> before storage. The AggregatePrel's groupSet and aggCall indices reference the
 * scan's current row type (which may be projected). Since {@link JdbcScanPrel#getPhysicalOperator}
 * builds the Calcite subtree from a full-table leaf, normalizing here ensures correct GROUP BY
 * column mapping regardless of earlier project pushdowns.
 *
 * <p>At {@link JdbcScanPrel#getPhysicalOperator} time, these Calcite objects are used to
 * construct a {@code JdbcRules.JdbcAggregate} node, and {@link DremioJdbcImplementor} renders
 * the GROUP BY clause and aggregate functions (COUNT, SUM, etc.) in the SELECT list automatically,
 * respecting the target dialect's SQL syntax.
 *
 * <p>Only {@link AggregatePrel.OperatorPhase#PHASE_1of1 PHASE_1of1} aggregates are pushed down.
 * Two-phase distributed aggregates ({@code PHASE_1of2} and {@code PHASE_2of2}) are rejected because
 * pushing a partial aggregate to a single JDBC source would produce incorrect results.
 *
 * <p>Supported aggregate functions: COUNT, SUM, SUM0, MIN, MAX, AVG. DISTINCT aggregates are not
 * supported in v1 and cause the rule to decline.
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

    ImmutableBitSet groupSet = agg.getGroupSet();
    List<AggregateCall> aggCallList = agg.getAggCallList();
    RelDataType newRowType = agg.getRowType();

    // Normalize groupSet and aggCall indices from the scan's current row type
    // (possibly projected) to full-table indices.
    RelDataType fullTableRowType = JdbcPushFilterIntoScan.getFullTableRowType(scan);
    List<SchemaPath> projCols = scan.getProjectedColumns();

    if (projCols != null && !projCols.isEmpty()) {
      // Build projected-to-full mapping
      int[] projToFull = buildProjToFull(projCols, fullTableRowType);

      // Remap groupSet bits
      ImmutableBitSet.Builder gsBuilder = ImmutableBitSet.builder();
      for (int bit : groupSet) {
        gsBuilder.set(bit < projToFull.length ? projToFull[bit] : bit);
      }
      groupSet = gsBuilder.build();

      // Remap AggregateCall argument indices
      List<AggregateCall> remapped = new ArrayList<>(aggCallList.size());
      for (AggregateCall ac : aggCallList) {
        List<Integer> newArgs = new ArrayList<>(ac.getArgList().size());
        for (int arg : ac.getArgList()) {
          newArgs.add(arg < projToFull.length ? projToFull[arg] : arg);
        }
        remapped.add(ac.copy(newArgs, ac.filterArg));
      }
      aggCallList = remapped;
    }

    // Store the Calcite objects directly -- DremioJdbcImplementor will render GROUP BY
    // and aggregate SELECT expressions at getPhysicalOperator() time.
    // Note: projectedColumns is set to null because the aggregation node drives the
    // SELECT list; the individual column projection is irrelevant at this point.
    JdbcScanPrel newScan = scan.cloneWithAggregation(groupSet, aggCallList, newRowType);
    call.transformTo(newScan);
  }

  /**
   * Builds a mapping from projected-column position to full-table column position.
   *
   * @param projCols the projected column list
   * @param fullRowType the full (unprojected) table row type
   * @return projToFull[i] = full-table index of projCols.get(i)
   */
  private static int[] buildProjToFull(List<SchemaPath> projCols, RelDataType fullRowType) {
    int[] projToFull = new int[projCols.size()];
    List<RelDataTypeField> fullFields = fullRowType.getFieldList();
    for (int pi = 0; pi < projCols.size(); pi++) {
      String colName = projCols.get(pi).getRootSegment().getPath();
      int fullIdx = -1;
      for (int fi = 0; fi < fullFields.size(); fi++) {
        if (fullFields.get(fi).getName().equalsIgnoreCase(colName)) {
          fullIdx = fi;
          break;
        }
      }
      projToFull[pi] = (fullIdx >= 0) ? fullIdx : pi; // fallback: identity
    }
    return projToFull;
  }
}
