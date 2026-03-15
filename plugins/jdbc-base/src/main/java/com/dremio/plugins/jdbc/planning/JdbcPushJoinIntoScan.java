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
import com.dremio.exec.calcite.logical.ScanCrel;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.logical.RelOptHelper;
import org.apache.calcite.plan.RelOptUtil;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.Prel;
import org.apache.calcite.rel.logical.LogicalJoin;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rex.RexNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PHYSICAL-phase rule that pushes a {@link JoinPrel} into a single {@link JdbcJoinScanPrel} when
 * both children are (or contain) {@link JdbcScanPrel} nodes referencing tables on the same JDBC
 * source.
 *
 * <p>The rule fires at the PHYSICAL planning phase. It matches any {@link JoinPrel} (which covers
 * HashJoinPrel, MergeJoinPrel, and NestedLoopJoinPrel) and walks each side to find {@link
 * JdbcScanPrel} leaves through intermediate nodes like ProjectPrel and SingleMergeExchangePrel.
 *
 * <p>Purpose: Avoid unnecessary data transfer for joins between tables on the same JDBC source. The
 * source engine computes the join using its own indexes and statistics, returning only the combined
 * result set.
 *
 * <p>The produced {@link JdbcJoinScanPrel} builds the actual JOIN SQL in {@code
 * getPhysicalOperator()}.
 */
public final class JdbcPushJoinIntoScan extends RelOptRule {

  private static final Logger logger = LoggerFactory.getLogger(JdbcPushJoinIntoScan.class);

  public static final RelOptRule INSTANCE = new JdbcPushJoinIntoScan();

  private JdbcPushJoinIntoScan() {
    super(
        operand(LogicalJoin.class, RelOptRule.any()),
        "JdbcPushJoinIntoScan");
  }

  /**
   * Guards: both sides must have a single JdbcScanPrel leaf, and they must reference the same JDBC
   * source instance.
   *
   * <p>We match any JoinPrel and walk each side to find JdbcScanPrel leaves because intermediate
   * ProjectPrel, SingleMergeExchangePrel, or other single-input nodes may sit between the JoinPrel
   * and the scan nodes.
   *
   * <p>Uses {@code pluginId.getName()} (the catalog-unique source name) for identity comparison
   * rather than {@code pluginId.equals()}, which also compares capabilities and other metadata.
   */
  @Override
  public boolean matches(RelOptRuleCall call) {
    LogicalJoin join = call.rel(0);
    ScanRelBase left = findJdbcScan(join.getLeft());
    ScanRelBase right = findJdbcScan(join.getRight());
    if (left == null || right == null) {
      return false;
    }
    boolean sameSource = left.getPluginId().getName().equals(right.getPluginId().getName());
    if (sameSource) {
      logger.info("[JOIN-PUSH] same-source match: {}", left.getPluginId().getName());
    }
    return sameSource;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    LogicalJoin join = call.rel(0);
    ScanRelBase leftScan = findJdbcScan(join.getLeft());
    ScanRelBase rightScan = findJdbcScan(join.getRight());
    if (leftScan == null || rightScan == null) {
      return;
    }

    JoinRelType joinType = join.getJoinType();
    RexNode condition = join.getCondition();

    // Extract schema and table from TableMetadata path components.
    List<String> leftPath = leftScan.getTableMetadata().getName().getPathComponents();
    String leftSchema =
        leftPath.size() >= 3
            ? leftPath.get(leftPath.size() - 2)
            : (leftPath.size() == 2 ? leftPath.get(0) : "");
    String leftTable =
        leftPath.size() >= 2
            ? leftPath.get(leftPath.size() - 1)
            : (leftPath.isEmpty() ? "" : leftPath.get(0));

    List<String> rightPath = rightScan.getTableMetadata().getName().getPathComponents();
    String rightSchema =
        rightPath.size() >= 3
            ? rightPath.get(rightPath.size() - 2)
            : (rightPath.size() == 2 ? rightPath.get(0) : "");
    String rightTable =
        rightPath.size() >= 2
            ? rightPath.get(rightPath.size() - 1)
            : (rightPath.isEmpty() ? "" : rightPath.get(0));

    // Convert join condition to alias-aware SQL using RexToJoinSqlString.
    // Pass original field names from each side to avoid Calcite dedup suffixes
    // (e.g., "department0") appearing in the generated SQL.
    int leftFieldCount = leftScan.getRowType().getFieldCount();
    List<String> leftNames = new ArrayList<>();
    for (org.apache.calcite.rel.type.RelDataTypeField f : leftScan.getRowType().getFieldList()) {
      leftNames.add(f.getName());
    }
    List<String> rightNames = new ArrayList<>();
    for (org.apache.calcite.rel.type.RelDataTypeField f : rightScan.getRowType().getFieldList()) {
      rightNames.add(f.getName());
    }
    RexToJoinSqlString converter =
        new RexToJoinSqlString(
            join.getRowType(), leftFieldCount, "t1", "t2", leftNames, rightNames);
    RexToSqlResult condResult = converter.convert(condition);
    if (condResult == null) {
      logger.info("[JOIN-PUSH] Unsupported join condition — declining pushdown");
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

    // Produce a logical JdbcJoinScanDrel at the LOGICAL phase.
    // The PHYSICAL phase will convert it to JdbcJoinScanPrel via JdbcJoinScanPrule
    // (registered in JdbcRulesFactory PHYSICAL).
    JdbcJoinScanDrel joinScanDrel =
        new JdbcJoinScanDrel(
            join.getCluster(),
            join.getCluster().traitSetOf(com.dremio.exec.planner.logical.Rel.LOGICAL), // LOGICAL convention
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

    logger.info(
        "[JOIN-PUSH] Pushing JOIN to source '{}': {}.{} {} {}.{} ON {}",
        leftScan.getPluginId().getName(),
        leftSchema,
        leftTable,
        SqlBuilder.joinTypeToSql(joinType),
        rightSchema,
        rightTable,
        condResult.getSql());

    call.transformTo(joinScanDrel);
  }

  /**
   * Walks down the RelNode tree to find a single JdbcScanPrel leaf. Traverses through intermediate
   * nodes like ProjectPrel, SingleMergeExchangePrel that sit between the JoinPrel and the scan.
   * Returns null if no JdbcScanPrel is found or if the tree branches.
   */
  /**
   * Walks down the RelNode tree to find a JDBC scan leaf (JdbcScanDrel or ScanCrel with a JDBC
   * plugin). Returns the found ScanRelBase or null if not found. Traverses through single-input
   * intermediate nodes (Project, Filter, etc.).
   */
  private static ScanRelBase findJdbcScan(RelNode node) {
    // Unwrap Volcano's RelSubset to get the best/original rel
    if (node instanceof org.apache.calcite.plan.volcano.RelSubset) {
      org.apache.calcite.plan.volcano.RelSubset subset =
          (org.apache.calcite.plan.volcano.RelSubset) node;
      // Try all rels in the equivalence set
      for (RelNode rel : subset.getRelList()) {
        ScanRelBase found = findJdbcScan(rel);
        if (found != null) {
          return found;
        }
      }
      return null;
    }
    if (node instanceof JdbcScanDrel) {
      return (JdbcScanDrel) node;
    }
    if (node instanceof ScanCrel) {
      return (ScanCrel) node;
    }
    // Walk through single-input nodes (Project, Filter, etc.)
    if (node.getInputs().size() == 1) {
      return findJdbcScan(node.getInput(0));
    }
    return null;
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
