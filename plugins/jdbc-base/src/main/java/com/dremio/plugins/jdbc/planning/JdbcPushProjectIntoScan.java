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
import com.dremio.exec.planner.physical.ProjectPrel;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;

/**
 * Pushdown rule that converts a {@link ProjectPrel} above a {@link JdbcScanPrel} into a SELECT
 * list carried by the scan node itself.
 *
 * <p>Two paths:
 * <ol>
 *   <li><strong>Simple column-ref path (existing):</strong> If all project expressions are
 *       {@link RexInputRef} nodes, the rule converts them to {@link SchemaPath}-based projected
 *       columns via {@link JdbcScanPrel#cloneWithProject(List)}.</li>
 *   <li><strong>Function expression path (new in Phase 37):</strong> If any expression is a
 *       non-trivial but whitelisted function call (UPPER, CAST, ROUND, etc.), the full
 *       {@link RexNode} list is stored via
 *       {@link JdbcScanPrel#cloneWithProjectExpressions(List, List)}. Non-whitelisted functions
 *       cause the rule to decline so Dremio evaluates them after fetching rows.</li>
 * </ol>
 *
 * <p>Constructor requires a {@link PushdownFunctionRegistry} for whitelist validation.
 * {@code INSTANCE} singleton is removed; the rule is instantiated in {@link JdbcRulesFactory}
 * with the appropriate registry.
 */
public final class JdbcPushProjectIntoScan extends RelOptRule {

  private final PushdownFunctionRegistry registry;

  public JdbcPushProjectIntoScan(PushdownFunctionRegistry registry) {
    super(
        RelOptHelper.some(ProjectPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushProjectIntoScan");
    this.registry = registry;
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    JdbcScanPrel scan = call.rel(1);
    // Don't push projection into a scan that already has aggregation pushed down.
    // The aggregated scan's SELECT list is driven by JdbcAggregate, not projectedColumns.
    return !scan.hasAggregation();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    ProjectPrel project = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    List<RexNode> projects = project.getProjects();
    List<RelDataTypeField> scanFields = scan.getRowType().getFieldList();

    boolean hasFunctionExpression = false;

    // First pass: validate all expressions.
    for (RexNode expr : projects) {
      if (expr instanceof RexInputRef) {
        // Simple column ref — always OK.
        continue;
      }
      // Non-trivial expression: check whitelist.
      if (!registry.isExpressionPushable(expr)) {
        // Non-whitelisted expression — decline pushdown entirely.
        return;
      }
      hasFunctionExpression = true;
    }

    if (hasFunctionExpression) {
      // --- Function expression path ---
      // Store the full RexNode list and output names for getPhysicalOperator() step 7a.
      List<String> outputNames = project.getRowType().getFieldNames();
      call.transformTo(scan.cloneWithProjectExpressions(projects, outputNames));
    } else {
      // --- Simple column-ref path ---
      // Convert each RexInputRef to a SchemaPath (existing behavior).
      List<SchemaPath> projectedColumns = new ArrayList<>(projects.size());
      for (RexNode expr : projects) {
        int index = ((RexInputRef) expr).getIndex();
        if (index < 0 || index >= scanFields.size()) {
          return;
        }
        projectedColumns.add(SchemaPath.getSimplePath(scanFields.get(index).getName()));
      }
      call.transformTo(scan.cloneWithProject(projectedColumns));
    }
  }
}
