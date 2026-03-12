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
import com.dremio.exec.planner.physical.Prel;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;

/**
 * Planner rule that converts a {@link JdbcScanDrel} logical scan node to a
 * {@link JdbcScanPrel} physical scan node during the <em>PHYSICAL</em> planning phase.
 *
 * <p>The schema name and table name are extracted from the last two components of
 * the dataset's namespace key path so the {@link JdbcScanPrel} can use them to
 * construct the {@code FROM "schema"."table"} clause via {@link SqlBuilder}.
 *
 * <p>Registered in {@link JdbcRulesFactory} for the PHYSICAL phase alongside
 * the pushdown rules.
 */
public class JdbcScanPrule extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcScanPrule();

  private JdbcScanPrule() {
    super(RelOptHelper.any(JdbcScanDrel.class), "JdbcScanPrule");
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    JdbcScanDrel logicalScan = call.rel(0);

    // Extract schema and table names from the dataset namespace key path.
    // Path components: [sourceName, schemaName, tableName] or [sourceName, tableName]
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

    JdbcScanPrel physicalScan =
        new JdbcScanPrel(
            logicalScan.getCluster(),
            logicalScan.getTraitSet().replace(Prel.PHYSICAL),
            logicalScan.getTable(),
            logicalScan.getPluginId(),
            logicalScan.getTableMetadata(),
            logicalScan.getProjectedColumns(),
            logicalScan.getObservedRowcountAdjustment(),
            logicalScan.getHintsAsList(),
            ImmutableList.of(),
            schemaName,
            tableName,
            null,
            null);

    call.transformTo(physicalScan);
  }
}
