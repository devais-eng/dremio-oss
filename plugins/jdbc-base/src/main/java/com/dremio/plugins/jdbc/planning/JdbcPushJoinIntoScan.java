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
import com.dremio.exec.planner.logical.JoinRel;
import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.Prel;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rex.RexNode;

/**
 * LOGICAL-phase rule that pushes a {@link JoinRel} into a single {@link JdbcJoinScanDrel} when
 * both children are {@link JdbcScanDrel} nodes referencing tables on the same JDBC source.
 *
 * <p>The rule fires at the LOGICAL planning phase. At this phase, the child nodes are clean
 * {@link JdbcScanDrel} instances — no WHERE, LIMIT, ORDER BY, or aggregation state is present
 * (those are PHYSICAL-phase concerns added to {@link JdbcScanPrel}). This means no declination
 * guards for pushdown state are needed; only the same-source check is required.
 *
 * <p>Purpose: Avoid unnecessary data transfer for joins between tables on the same JDBC source.
 * The source engine computes the join using its own indexes and statistics, returning only the
 * combined result set.
 *
 * <p>The produced {@link JdbcJoinScanDrel} carries the raw join components. The companion
 * {@link JdbcJoinScanPrule} converts it to a {@link JdbcJoinScanPrel} at the PHYSICAL phase, which
 * builds the actual JOIN SQL in {@code getPhysicalOperator()}.
 */
public final class JdbcPushJoinIntoScan extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushJoinIntoScan();

  private JdbcPushJoinIntoScan() {
    super(
        RelOptHelper.some(
            JoinRel.class,
            RelOptHelper.any(JdbcScanDrel.class),
            RelOptHelper.any(JdbcScanDrel.class)),
        "JdbcPushJoinIntoScan");
  }

  /**
   * Guards: both sides must reference the same JDBC source instance.
   *
   * <p>Uses {@code pluginId.getName()} (the catalog-unique source name) for identity comparison
   * rather than {@code pluginId.equals()}, which also compares capabilities and other metadata.
   */
  @Override
  public boolean matches(RelOptRuleCall call) {
    JdbcScanDrel left = call.rel(1);
    JdbcScanDrel right = call.rel(2);
    return left.getPluginId().getName().equals(right.getPluginId().getName());
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    JoinRel join = call.rel(0);
    JdbcScanDrel leftScan = call.rel(1);
    JdbcScanDrel rightScan = call.rel(2);

    JoinRelType joinType = join.getJoinType();
    RexNode condition = join.getCondition();

    // Extract schema and table from TableMetadata path components.
    // Path components: [sourceName, schemaName, tableName] or [sourceName, tableName]
    // This is the exact same pattern used in JdbcScanPrule.onMatch().
    List<String> leftPath =
        leftScan.getTableMetadata().getName().getPathComponents();
    String leftSchema;
    String leftTable;
    if (leftPath.size() >= 3) {
      leftSchema = leftPath.get(leftPath.size() - 2);
      leftTable = leftPath.get(leftPath.size() - 1);
    } else if (leftPath.size() == 2) {
      leftSchema = leftPath.get(0);
      leftTable = leftPath.get(1);
    } else {
      leftSchema = "";
      leftTable = leftPath.isEmpty() ? "" : leftPath.get(0);
    }

    List<String> rightPath =
        rightScan.getTableMetadata().getName().getPathComponents();
    String rightSchema;
    String rightTable;
    if (rightPath.size() >= 3) {
      rightSchema = rightPath.get(rightPath.size() - 2);
      rightTable = rightPath.get(rightPath.size() - 1);
    } else if (rightPath.size() == 2) {
      rightSchema = rightPath.get(0);
      rightTable = rightPath.get(1);
    } else {
      rightSchema = "";
      rightTable = rightPath.isEmpty() ? "" : rightPath.get(0);
    }

    // Convert join condition to alias-aware SQL using RexToJoinSqlString.
    int leftFieldCount = leftScan.getRowType().getFieldCount();
    RexToJoinSqlString converter =
        new RexToJoinSqlString(join.getRowType(), leftFieldCount, "t1", "t2");
    RexToSqlResult condResult = converter.convert(condition);
    if (condResult == null) {
      // Unsupported condition -- decline pushdown.
      return;
    }

    // Determine projected columns for each side.
    List<SchemaPath> leftProjectedCols = leftScan.getProjectedColumns();
    if (leftProjectedCols == null) {
      leftProjectedCols = deriveColumnsFromRowType(leftScan.getRowType());
    }
    List<SchemaPath> rightProjectedCols = rightScan.getProjectedColumns();
    if (rightProjectedCols == null) {
      rightProjectedCols = deriveColumnsFromRowType(rightScan.getRowType());
    }

    JdbcJoinScanDrel joinScanDrel =
        new JdbcJoinScanDrel(
            join.getCluster(),
            leftScan.getTraitSet(), // keep LOGICAL convention
            leftScan.getPluginId(),
            leftSchema,
            leftTable,
            rightSchema,
            rightTable,
            joinType,
            condResult.getSql(),
            condResult.getParams(),
            leftProjectedCols,
            rightProjectedCols,
            join.getRowType());

    call.transformTo(joinScanDrel);
  }

  /** Derives a column list from the row type field names. */
  private static List<SchemaPath> deriveColumnsFromRowType(
      org.apache.calcite.rel.type.RelDataType rowType) {
    List<SchemaPath> cols = new ArrayList<>();
    for (org.apache.calcite.rel.type.RelDataTypeField field : rowType.getFieldList()) {
      cols.add(SchemaPath.getSimplePath(field.getName()));
    }
    return cols;
  }

  // =========================================================================
  // Companion PHYSICAL-phase rule: JdbcJoinScanDrel -> JdbcJoinScanPrel
  // =========================================================================

  /**
   * Converts a {@link JdbcJoinScanDrel} logical node to a {@link JdbcJoinScanPrel} physical node
   * during the PHYSICAL planning phase.
   *
   * <p>Registered in {@link JdbcRulesFactory} alongside the other Drel-to-Prel conversion rules.
   */
  public static final class JdbcJoinScanPrule extends RelOptRule {

    public static final RelOptRule INSTANCE = new JdbcJoinScanPrule();

    private JdbcJoinScanPrule() {
      super(RelOptHelper.any(JdbcJoinScanDrel.class), "JdbcJoinScanPrule");
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
      JdbcJoinScanDrel logical = call.rel(0);
      JdbcJoinScanPrel physical =
          new JdbcJoinScanPrel(
              logical.getCluster(),
              logical
                  .getTraitSet()
                  .replace(Prel.PHYSICAL)
                  .plus(DistributionTrait.SINGLETON),
              logical.getPluginId(),
              logical.getLeftSchema(),
              logical.getLeftTable(),
              logical.getRightSchema(),
              logical.getRightTable(),
              logical.getJoinType(),
              logical.getOnClauseSql(),
              logical.getConditionBindParams(),
              logical.getLeftColumns(),
              logical.getRightColumns(),
              logical.getRowType());
      call.transformTo(physical);
    }
  }
}
