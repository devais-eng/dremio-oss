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
import com.dremio.exec.planner.physical.FilterPrel;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;

/**
 * Pushdown rule that converts a pre-aggregation {@link FilterPrel} above a {@link JdbcScanPrel}
 * into a Calcite {@link RexNode} filter condition carried by the scan node itself (WHERE clause).
 *
 * <p>This rule handles only pre-aggregation filters (WHERE). Post-aggregation filters (HAVING)
 * are handled by {@link JdbcPushHavingIntoScan}. If the scan already has aggregation pushed,
 * this rule explicitly declines to prevent HAVING conditions from being incorrectly rendered
 * as WHERE clauses.
 *
 * <p>The filter condition is <em>normalized to full-table column indices</em> before storage.
 * This is necessary because the Volcano planner may fire {@link JdbcPushProjectIntoScan} before
 * this rule, setting the scan's row type to a projected subset. The FilterPrel's condition indices
 * are relative to its input's row type (potentially projected), but {@link
 * JdbcScanPrel#getPhysicalOperator} builds the Calcite subtree starting from a leaf with the
 * <em>full</em> table row type. Storing full-table indices avoids incorrect column references.
 *
 * <p>Only conditions that pass the {@link PushdownFunctionRegistry#isExpressionPushable} check
 * are pushed; conditions containing non-whitelisted functions are left in Dremio's engine.
 *
 * <p>At {@link JdbcScanPrel#getPhysicalOperator} time, the stored {@link RexNode} is passed
 * directly to {@code JdbcRules.JdbcFilter} and rendered to SQL by {@link DremioJdbcImplementor}.
 */
public final class JdbcPushFilterIntoScan extends RelOptRule {

  private final PushdownFunctionRegistry registry;

  /**
   * Creates a WHERE filter pushdown rule using the given function registry.
   *
   * @param registry per-dialect whitelist controlling which functions are safe to push
   */
  public JdbcPushFilterIntoScan(PushdownFunctionRegistry registry) {
    super(
        RelOptHelper.some(FilterPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushFilterIntoScan");
    this.registry = registry;
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    JdbcScanPrel scan = call.rel(1);
    // Do not push a second filter if one is already present.
    if (scan.hasFilter()) {
      return false;
    }
    // Do not push a filter above an aggregated scan as WHERE.
    // HAVING (post-aggregation filter) is handled by JdbcPushHavingIntoScan.
    if (scan.hasAggregation()) {
      return false;
    }
    return true;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    FilterPrel filter = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    // Normalize the filter condition: translate RexInputRef indices from the scan's
    // current row type (which may be a projected subset) to full-table column indices.
    // This ensures filterRex indices in getPhysicalOperator() always reference the
    // full-table leaf row type, regardless of what projection was pushed first.
    RexNode normalizedCondition =
        normalizeToFullTable(
            filter.getCondition(),
            scan.getProjectedColumns(),
            getFullTableRowType(scan),
            scan.getCluster().getRexBuilder());

    // Validate all functions in the WHERE condition against the whitelist.
    if (!registry.isExpressionPushable(normalizedCondition)) {
      return;
    }

    call.transformTo(scan.cloneWithFilter(normalizedCondition));
  }

  /**
   * Returns the full (unprojected) row type for the given scan node.
   *
   * <p>Uses the Calcite RelOptTable row type (which reflects the full schema). Falls back to
   * the scan's derived row type if the table row type is unavailable.
   */
  static RelDataType getFullTableRowType(JdbcScanPrel scan) {
    try {
      return scan.getTable().getRowType();
    } catch (Exception e) {
      return scan.deriveRowType();
    }
  }

  /**
   * Remaps RexInputRef indices in {@code condition} from the scan's current projected row type
   * to the full-table row type.
   *
   * <p>Each RexInputRef with index {@code i} in a projected-column row type refers to
   * {@code projectedColumns.get(i)}. We find that column's position in the full-table row type
   * and replace the RexInputRef with a new one at the full-table index.
   *
   * <p>If {@code projectedColumns} is null or empty (scan already uses full-table row type),
   * the condition is returned unchanged.
   *
   * @param condition the filter condition with projected-rowtype indices
   * @param projectedColumns the scan's current projected column list (may be null)
   * @param fullTableRowType the full (unprojected) table row type
   * @param rexBuilder the RexBuilder for constructing new RexInputRef nodes
   * @return the condition with indices remapped to the full-table row type
   */
  static RexNode normalizeToFullTable(
      RexNode condition,
      List<SchemaPath> projectedColumns,
      RelDataType fullTableRowType,
      RexBuilder rexBuilder) {

    if (projectedColumns == null || projectedColumns.isEmpty()) {
      // Scan uses full-table row type; indices already correct.
      return condition;
    }

    // Build projected-to-full mapping: projectedColumns[i] → full-table index.
    final int[] projToFull = new int[projectedColumns.size()];
    List<RelDataTypeField> fullFields = fullTableRowType.getFieldList();
    for (int pi = 0; pi < projectedColumns.size(); pi++) {
      String colName = projectedColumns.get(pi).getRootSegment().getPath();
      int fullIdx = -1;
      for (int fi = 0; fi < fullFields.size(); fi++) {
        if (fullFields.get(fi).getName().equalsIgnoreCase(colName)) {
          fullIdx = fi;
          break;
        }
      }
      projToFull[pi] = (fullIdx >= 0) ? fullIdx : pi; // fallback: identity
    }

    // Remap RexInputRef indices. Only remap indices within the projected range;
    // indices beyond the projected range are assumed to already be full-table indices.
    return condition.accept(
        new RexShuttle() {
          @Override
          public RexNode visitInputRef(RexInputRef inputRef) {
            int idx = inputRef.getIndex();
            if (idx >= 0 && idx < projToFull.length) {
              int fullIdx = projToFull[idx];
              RelDataTypeField fullField = fullTableRowType.getFieldList().get(fullIdx);
              return rexBuilder.makeInputRef(fullField.getType(), fullIdx);
            }
            return inputRef;
          }
        });
  }
}
