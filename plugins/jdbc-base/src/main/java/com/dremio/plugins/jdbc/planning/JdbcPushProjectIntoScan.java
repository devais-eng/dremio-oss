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
 * <p>Only handles simple column-ref projections ({@link RexInputRef} nodes). If any project
 * expression is not a simple column reference, the rule declines and Dremio evaluates the
 * expression locally after fetching the raw columns from the JDBC source.
 */
public final class JdbcPushProjectIntoScan extends RelOptRule {

  public static final JdbcPushProjectIntoScan INSTANCE = new JdbcPushProjectIntoScan();

  private JdbcPushProjectIntoScan() {
    super(
        RelOptHelper.some(ProjectPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushProjectIntoScan");
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

    // Only handle simple column-ref projections (all RexInputRef).
    List<SchemaPath> projectedColumns = new ArrayList<>(projects.size());
    for (RexNode expr : projects) {
      if (!(expr instanceof RexInputRef)) {
        // Non-trivial expression — decline pushdown entirely.
        return;
      }
      int index = ((RexInputRef) expr).getIndex();
      if (index < 0 || index >= scanFields.size()) {
        return;
      }
      projectedColumns.add(SchemaPath.getSimplePath(scanFields.get(index).getName()));
    }
    call.transformTo(scan.cloneWithProject(projectedColumns));
  }
}
