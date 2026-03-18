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
import com.dremio.exec.planner.logical.SortRel;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.Prel;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelCollation;

/**
 * Pushdown rule that absorbs a logical {@link SortRel} into a physical {@link JdbcScanPrel},
 * storing the Calcite {@link RelCollation} directly on the scan so that {@link
 * DremioJdbcImplementor} can render the ORDER BY clause at {@link
 * JdbcScanPrel#getPhysicalOperator} time.
 *
 * <p>This rule matches at the <b>logical</b> level ({@code SortRel} above {@link JdbcScanDrel})
 * rather than the physical level because Dremio's {@code SortPrule} does not explicitly create a
 * {@code SortPrel} — it relies on Volcano's trait enforcement mechanism. Enforcers created by
 * Volcano are not visible to rule matching, so a physical-level rule ({@code
 * SortPrel(JdbcScanPrel)}) would never fire.
 *
 * <p>By matching the logical sort above the logical JDBC scan, the rule produces a physical {@link
 * JdbcScanPrel} with the ORDER BY collation and the collation trait already set. Volcano then sees
 * that the sort group already has a physical implementation satisfying the required collation and
 * does not need to insert a {@code SortPrel} enforcer.
 *
 * <p>The {@link RelCollation} is stored as-is on the scan. At {@code getPhysicalOperator()} time,
 * {@code JdbcRules.JdbcSort.implement()} renders it including explicit {@code NULLS FIRST} or
 * {@code NULLS LAST} directives, automatically respecting the target dialect's SQL syntax.
 *
 * <p>Registered in the {@code PHYSICAL} (Volcano) phase via {@link JdbcRulesFactory}.
 */
public final class JdbcPushSortIntoScan extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushSortIntoScan();

  private JdbcPushSortIntoScan() {
    super(
        RelOptHelper.some(SortRel.class, RelOptHelper.any(JdbcScanDrel.class)),
        "JdbcPushSortIntoScan");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    SortRel sort = call.rel(0);
    // Reject sorts with a non-null offset (offset is not expressible in ORDER BY alone).
    if (sort.offset != null) {
      return false;
    }
    // Must have at least one sort field.
    return !sort.getCollation().getFieldCollations().isEmpty();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    SortRel sort = call.rel(0);
    JdbcScanDrel logicalScan = call.rel(1);

    RelCollation sortCollation = sort.getCollation();

    // Extract schema and table names from the dataset namespace key path (same as JdbcScanPrule).
    List<String> pathComponents = logicalScan.getTableMetadata().getName().getPathComponents();
    String schemaName;
    String tableName;
    if (pathComponents.size() >= 3) {
      schemaName = pathComponents.get(pathComponents.size() - 2);
      tableName = pathComponents.get(pathComponents.size() - 1);
    } else if (pathComponents.size() == 2) {
      schemaName = pathComponents.get(0);
      tableName = pathComponents.get(1);
    } else {
      schemaName = "";
      tableName = pathComponents.isEmpty() ? "" : pathComponents.get(0);
    }

    // Create a physical scan with the RelCollation stored as a Calcite object.
    // SINGLETON distribution matches LimitPrule's requirement so limit pushdown still works.
    JdbcScanPrel physicalScan =
        new JdbcScanPrel(
            logicalScan.getCluster(),
            logicalScan
                .getTraitSet()
                .replace(Prel.PHYSICAL)
                .plus(DistributionTrait.SINGLETON)
                .plus(sortCollation),
            logicalScan.getTable(),
            logicalScan.getPluginId(),
            logicalScan.getTableMetadata(),
            logicalScan.getProjectedColumns(),
            logicalScan.getObservedRowcountAdjustment(),
            logicalScan.getHintsAsList(),
            ImmutableList.of(),
            schemaName,
            tableName,
            null,           // filterRex
            sortCollation,  // collation stored as Calcite object
            null,           // limit
            null,           // groupSet
            null,           // aggCalls
            null,           // overrideRowType
            null,           // havingRex
            null,           // sortKeyExpressions
            null,           // groupKeyExpressions
            null);          // aggOperandExpressions

    call.transformTo(physicalScan);
  }
}
