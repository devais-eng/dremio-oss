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
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.Prel;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LOGICAL-phase rule that pushes a {@link LogicalJoin} into a single {@link JdbcJoinScanDrel} when
 * both children are (or contain) JDBC scan nodes referencing tables on the same JDBC source.
 *
 * <p>The join ON condition is stored as a raw {@link RexNode} (not a SQL string). The PHYSICAL
 * phase ({@link JdbcJoinScanPrule}) converts the logical node to {@link JdbcJoinScanPrel}, which
 * builds a Calcite JdbcRel subtree and renders the final JOIN SQL via {@link DremioJdbcImplementor}
 * in {@code getPhysicalOperator()}.
 *
 * <p>Purpose: Avoid unnecessary data transfer for joins between tables on the same JDBC source. The
 * source engine computes the join using its own indexes and statistics, returning only the combined
 * result set.
 *
 * <p>This rule also handles EXCEPT pushdown. Dremio's {@code MinusToJoin} rule (PRE_LOGICAL phase)
 * rewrites {@code A EXCEPT B} into {@code Project -> Filter -> LogicalJoin(LEFT) -> Aggregate ->
 * Scan}. This rule matches the resulting {@code LogicalJoin} and pushes the entire join (including
 * aggregate subqueries) as a single SQL query to the JDBC source. The Filter and Project above the
 * join are applied by Dremio after receiving the join results.
 *
 * <p>INTERSECT pushdown is NOT supported because Calcite's {@code INTERSECT_TO_DISTINCT} rule
 * rewrites {@code A INTERSECT B} into {@code UNION ALL + GROUP BY + HAVING}, which does not produce
 * a {@code LogicalJoin} node for this rule to match. INTERSECT queries execute correctly but use two
 * separate scans with in-engine aggregation.
 */
public final class JdbcPushJoinIntoScan extends RelOptRule {

  private static final Logger logger = LoggerFactory.getLogger(JdbcPushJoinIntoScan.class);

  private final PushdownFunctionRegistry registry;

  public JdbcPushJoinIntoScan(PushdownFunctionRegistry registry) {
    super(
        operand(LogicalJoin.class, RelOptRule.any()),
        "JdbcPushJoinIntoScan");
    this.registry = registry;
  }

  /**
   * Guards: both sides must have a single JdbcScanPrel leaf, and they must reference the same JDBC
   * source instance.
   *
   * <p>We match any LogicalJoin and walk each side to find JDBC scan leaves because intermediate
   * Project, Filter, or other single-input nodes may sit between the join and the scan nodes.
   *
   * <p>Uses {@code pluginId.getName()} (the catalog-unique source name) for identity comparison
   * rather than {@code pluginId.equals()}, which also compares capabilities and other metadata.
   */
  @Override
  public boolean matches(RelOptRuleCall call) {
    LogicalJoin join = call.rel(0);
    ScanRelBase left = findJdbcScan(join.getLeft(), registry);
    ScanRelBase right = findJdbcScan(join.getRight(), registry);
    if (left == null || right == null) {
      return false;
    }
    return left.getPluginId().getName().equals(right.getPluginId().getName());
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    LogicalJoin join = call.rel(0);
    ScanRelBase leftScan = findJdbcScan(join.getLeft(), registry);
    ScanRelBase rightScan = findJdbcScan(join.getRight(), registry);
    if (leftScan == null || rightScan == null) {
      return;
    }

    JoinRelType joinType = join.getJoinType();

    // Store the RAW RexNode condition -- JdbcImplementor will render it at physical time.
    // No more RexToJoinSqlString conversion.
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

    // Determine projected columns for each side.
    //
    // IMPORTANT: Always use join.getLeft().getRowType() (not leftScan.getProjectedColumns()) to
    // derive the column list. The join left/right input row types represent ALL columns that the
    // JOIN needs to produce — including columns required for WHERE filters above the join that
    // are not in the SELECT list (e.g. SALARY in "SELECT e.NAME, d.BUDGET ... WHERE e.SALARY > 100000").
    //
    // Using leftScan.getProjectedColumns() can omit such columns, creating a mismatch between:
    //   (a) the JdbcProject SQL (which selects only leftColumns + rightColumns), and
    //   (b) the outputRowType schema (which includes all join output fields).
    // The mismatch causes JdbcRecordReader to read columns at wrong positions, producing
    // type-conversion errors (e.g. "Fail to convert to internal representation" for Oracle).
    //
    // By deriving leftColumns from join.getLeft().getRowType(), we ensure that leftColumns
    // exactly matches the left portion of outputRowType = join.getRowType(), so the SQL
    // projection order aligns with the schema field order.
    //
    // When an intermediate LogicalProject with function expressions (e.g. CAST) exists between
    // the join and the scan, the join input row type has synthetic names (EXPR$0). The outer
    // SELECT project (step 6 in getPhysicalOperator) expects names from the project output,
    // so we still use join.getLeft().getRowType() here. The fix for correct column aliasing
    // is handled in getPhysicalOperator() by wrapping the leaf in a JdbcProject with the
    // stored project expressions.
    List<SchemaPath> leftProjectedCols = deriveColumnsFromRowType(join.getLeft().getRowType());
    List<SchemaPath> rightProjectedCols = deriveColumnsFromRowType(join.getRight().getRowType());

    // Detect intermediate Aggregate nodes between the join and the scans.
    // MinusToJoin (EXCEPT) wraps each input in Aggregate(GROUP BY all fields, COUNT(*)).
    // When present, we store the scan's real row type and aggregate info so that
    // getPhysicalOperator() can build JdbcAggregate(JdbcCalciteLeaf) instead of bare leaves.
    RelNode leftJoinInput = unwrapSubset(join.getLeft());
    RelNode rightJoinInput = unwrapSubset(join.getRight());

    RelDataType leftScanRowType = null;
    ImmutableBitSet leftAggGroupSet = null;
    List<AggregateCall> leftAggCalls = null;
    if (leftJoinInput instanceof Aggregate) {
      Aggregate agg = (Aggregate) leftJoinInput;
      leftScanRowType = leftScan.getRowType();
      leftAggGroupSet = agg.getGroupSet();
      leftAggCalls = agg.getAggCallList();
      logger.info("[JOIN-PUSH] Left input has Aggregate: groupSet={}, aggCalls={}",
          leftAggGroupSet, leftAggCalls);
    }

    RelDataType rightScanRowType = null;
    ImmutableBitSet rightAggGroupSet = null;
    List<AggregateCall> rightAggCalls = null;
    if (rightJoinInput instanceof Aggregate) {
      Aggregate agg = (Aggregate) rightJoinInput;
      rightScanRowType = rightScan.getRowType();
      rightAggGroupSet = agg.getGroupSet();
      rightAggCalls = agg.getAggCallList();
      logger.info("[JOIN-PUSH] Right input has Aggregate: groupSet={}, aggCalls={}",
          rightAggGroupSet, rightAggCalls);
    }

    // Detect intermediate LogicalProject nodes with non-trivial expressions (e.g. CAST).
    // When present, extract the project expressions so that getPhysicalOperator() can wrap
    // the leaf in a JdbcProject that transforms real scan columns into the leftInputRowType
    // expected by conditionRex. Also set the scanRowType to the real table row type so the
    // leaf is built with real column names (not synthetic EXPR$0 names).
    List<RexNode> leftProjectExprs = null;
    if (hasNonTrivialProject(join.getLeft())) {
      LogicalProject leftProj = findLogicalProject(join.getLeft());
      if (leftProj != null) {
        leftProjectExprs = leftProj.getProjects();
        if (leftScanRowType == null) {
          leftScanRowType = leftScan.getRowType();
        }
        logger.info("[JOIN-PUSH] Left input has non-trivial project (e.g. CAST); "
            + "storing {} project expressions, scanRowType={}",
            leftProjectExprs.size(), leftScanRowType);
      }
    }

    List<RexNode> rightProjectExprs = null;
    if (hasNonTrivialProject(join.getRight())) {
      LogicalProject rightProj = findLogicalProject(join.getRight());
      if (rightProj != null) {
        rightProjectExprs = rightProj.getProjects();
        if (rightScanRowType == null) {
          rightScanRowType = rightScan.getRowType();
        }
        logger.info("[JOIN-PUSH] Right input has non-trivial project (e.g. CAST); "
            + "storing {} project expressions, scanRowType={}",
            rightProjectExprs.size(), rightScanRowType);
      }
    }

    // Produce a logical JdbcJoinScanDrel at the LOGICAL phase.
    // The PHYSICAL phase will convert it to JdbcJoinScanPrel via JdbcJoinScanPrule
    // (registered in JdbcRulesFactory PHYSICAL).
    //
    // We pass the full input row types (all columns from each side) because the conditionRex
    // RexInputRef indices are built against these full row types, not just the projected columns.
    JdbcJoinScanDrel joinScanDrel =
        new JdbcJoinScanDrel(
            join.getCluster(),
            join.getCluster().traitSetOf(com.dremio.exec.planner.logical.Rel.LOGICAL),
            leftScan.getPluginId(),
            leftSchema,
            leftTable,
            rightSchema,
            rightTable,
            joinType,
            condition,
            leftProjectedCols,
            rightProjectedCols,
            join.getRowType(),
            join.getLeft().getRowType(),
            join.getRight().getRowType(),
            leftScanRowType,
            rightScanRowType,
            leftAggGroupSet,
            leftAggCalls,
            rightAggGroupSet,
            rightAggCalls,
            leftProjectExprs,
            rightProjectExprs);

    logger.info(
        "[JOIN-PUSH] Pushing JOIN to source '{}': {}.{} {} {}.{} ON {}",
        leftScan.getPluginId().getName(),
        leftSchema,
        leftTable,
        joinType.name(),
        rightSchema,
        rightTable,
        condition);

    call.transformTo(joinScanDrel);
  }

  /**
   * Unwraps a Volcano {@link org.apache.calcite.plan.volcano.RelSubset} to its best or original
   * RelNode. Returns the node unchanged if it is not a RelSubset.
   */
  private static RelNode unwrapSubset(RelNode node) {
    if (node instanceof org.apache.calcite.plan.volcano.RelSubset) {
      org.apache.calcite.plan.volcano.RelSubset subset =
          (org.apache.calcite.plan.volcano.RelSubset) node;
      for (RelNode rel : subset.getRelList()) {
        if (!(rel instanceof org.apache.calcite.plan.volcano.RelSubset)) {
          return rel;
        }
      }
    }
    return node;
  }

  /**
   * Walks down the RelNode tree to find a JDBC scan leaf (JdbcScanDrel or ScanCrel with a JDBC
   * plugin). Returns the found ScanRelBase or null if not found. Traverses through single-input
   * intermediate nodes (Project, Filter, Aggregate, etc.).
   *
   * <p>When an intermediate {@link LogicalProject} is encountered, its expressions are validated
   * against the {@code registry} whitelist. Simple column refs ({@link RexInputRef}) are always
   * allowed. Whitelisted function expressions (e.g. {@code CAST(col AS type)}) are also allowed
   * since {@link PushdownFunctionRegistry#isExpressionPushable} returns true for them.
   * Non-whitelisted expressions (e.g. custom UDFs) cause the traversal to return null, declining
   * the join pushdown safely.
   *
   * @param node the current RelNode to inspect
   * @param registry the pushdown function whitelist registry
   * @return the JDBC scan leaf, or null if not found or a non-whitelisted expression blocks traversal
   */
  static ScanRelBase findJdbcScan(RelNode node, PushdownFunctionRegistry registry) {
    // Unwrap Volcano's RelSubset to get the best/original rel
    if (node instanceof org.apache.calcite.plan.volcano.RelSubset) {
      org.apache.calcite.plan.volcano.RelSubset subset =
          (org.apache.calcite.plan.volcano.RelSubset) node;
      for (RelNode rel : subset.getRelList()) {
        ScanRelBase found = findJdbcScan(rel, registry);
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
    // Validate intermediate LogicalProject expressions against the registry whitelist.
    // Simple column refs (RexInputRef) are always allowed.
    // Whitelisted function expressions (e.g. CAST) are also allowed — Gap 2 fix.
    // Non-whitelisted expressions (e.g. custom UDFs) decline the pushdown safely.
    if (node instanceof LogicalProject) {
      LogicalProject project = (LogicalProject) node;
      for (RexNode expr : project.getProjects()) {
        if (!(expr instanceof RexInputRef) && !registry.isExpressionPushable(expr)) {
          logger.debug(
              "[JOIN-PUSH] Declining pushdown: intermediate Project has non-whitelisted expression: {}",
              expr);
          return null;
        }
      }
      // All projections are simple column refs or whitelisted expressions — safe to walk through
      return findJdbcScan(project.getInput(), registry);
    }
    // Walk through single-input nodes (Filter, Aggregate, etc.)
    if (node.getInputs().size() == 1) {
      return findJdbcScan(node.getInput(0), registry);
    }
    return null;
  }

  /**
   * Returns true if there is an intermediate {@link LogicalProject} with at least one non-trivial
   * (non-{@link RexInputRef}) expression on the path from {@code node} to the JDBC scan leaf.
   *
   * <p>Used in {@code onMatch()} to determine whether to derive projected columns from the scan's
   * row type (instead of the join input row type) when the path contains function expressions.
   *
   * @param node the RelNode to start walking from
   * @return true if any intermediate LogicalProject has a non-RexInputRef expression
   */
  private static boolean hasNonTrivialProject(RelNode node) {
    if (node instanceof org.apache.calcite.plan.volcano.RelSubset) {
      org.apache.calcite.plan.volcano.RelSubset subset =
          (org.apache.calcite.plan.volcano.RelSubset) node;
      for (RelNode rel : subset.getRelList()) {
        if (hasNonTrivialProject(rel)) {
          return true;
        }
      }
      return false;
    }
    if (node instanceof JdbcScanDrel || node instanceof ScanCrel) {
      return false;
    }
    if (node instanceof LogicalProject) {
      LogicalProject project = (LogicalProject) node;
      for (RexNode expr : project.getProjects()) {
        if (!(expr instanceof RexInputRef)) {
          return true;
        }
      }
      return hasNonTrivialProject(project.getInput());
    }
    if (node.getInputs().size() == 1) {
      return hasNonTrivialProject(node.getInput(0));
    }
    return false;
  }

  /**
   * Walks down the RelNode tree to find the first {@link LogicalProject} with at least one
   * non-trivial (non-{@link RexInputRef}) expression. Traverses through RelSubset and single-input
   * nodes. Returns the LogicalProject or null if not found.
   *
   * @param node the RelNode to start walking from
   * @return the first non-trivial LogicalProject, or null
   */
  private static LogicalProject findLogicalProject(RelNode node) {
    if (node instanceof org.apache.calcite.plan.volcano.RelSubset) {
      org.apache.calcite.plan.volcano.RelSubset subset =
          (org.apache.calcite.plan.volcano.RelSubset) node;
      for (RelNode rel : subset.getRelList()) {
        LogicalProject found = findLogicalProject(rel);
        if (found != null) {
          return found;
        }
      }
      return null;
    }
    if (node instanceof JdbcScanDrel || node instanceof org.apache.calcite.rel.core.TableScan) {
      return null;
    }
    if (node instanceof LogicalProject) {
      LogicalProject project = (LogicalProject) node;
      for (RexNode expr : project.getProjects()) {
        if (!(expr instanceof RexInputRef)) {
          return project;
        }
      }
      // All trivial — keep walking
      return findLogicalProject(project.getInput());
    }
    if (node.getInputs().size() == 1) {
      return findLogicalProject(node.getInput(0));
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
              logical.getConditionRex(),
              logical.getLeftColumns(),
              logical.getRightColumns(),
              logical.getRowType(),
              logical.getLeftInputRowType(),
              logical.getRightInputRowType(),
              logical.getLeftScanRowType(),
              logical.getRightScanRowType(),
              logical.getLeftAggGroupSet(),
              logical.getLeftAggCalls(),
              logical.getRightAggGroupSet(),
              logical.getRightAggCalls(),
              logical.getLeftProjectExprs(),
              logical.getRightProjectExprs());
      call.transformTo(physical);
    }
  }
}
