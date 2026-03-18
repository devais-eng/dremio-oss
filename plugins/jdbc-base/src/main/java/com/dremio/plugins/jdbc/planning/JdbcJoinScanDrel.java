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
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.planner.logical.Rel;
import com.google.common.collect.ImmutableList;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * Logical join scan node carrying raw join components for JDBC pushdown.
 *
 * <p>Produced by {@link JdbcPushJoinIntoScan} at the LOGICAL planning phase when both sides of a
 * {@link com.dremio.exec.planner.logical.JoinRel} reference tables from the same JDBC source.
 *
 * <p>This is a leaf node (no children) that stores all raw join components needed to later build
 * the JOIN SQL at physical time: schema/table names, join type, ON condition as a {@link RexNode},
 * projected columns for each side, and the full input row types for each table.
 *
 * <p>The {@code leftInputRowType} and {@code rightInputRowType} store the FULL row types of the
 * left and right scan inputs. These are needed in {@code getPhysicalOperator()} to correctly
 * construct the Calcite leaf row types that match the RexInputRef indices in {@code conditionRex}.
 *
 * <p>The subsequent PHYSICAL phase converts this to {@link JdbcJoinScanPrel} via the
 * {@link JdbcPushJoinIntoScan.JdbcJoinScanPrule}, which renders the final JOIN SQL via
 * {@link DremioJdbcImplementor} in {@code getPhysicalOperator()}.
 */
public class JdbcJoinScanDrel extends AbstractRelNode implements Rel {

  private final StoragePluginId pluginId;
  private final String leftSchema;
  private final String leftTable;
  private final String rightSchema;
  private final String rightTable;
  private final JoinRelType joinType;
  private final RexNode conditionRex;
  private final List<SchemaPath> leftColumns;
  private final List<SchemaPath> rightColumns;
  private final RelDataType outputRowType;
  /**
   * Full row type of the left scan input (all columns). Used in {@code getPhysicalOperator()} to
   * build the Calcite leaf row type that matches the RexInputRef indices in {@code conditionRex}.
   */
  private final RelDataType leftInputRowType;
  /**
   * Full row type of the right scan input (all columns). Used in {@code getPhysicalOperator()} to
   * build the Calcite leaf row type that matches the RexInputRef indices in {@code conditionRex}.
   */
  private final RelDataType rightInputRowType;

  /**
   * Actual row type of the left table (from the scan node). When the join input wraps the scan
   * in an Aggregate (e.g., MinusToJoin for EXCEPT), this differs from leftInputRowType (which
   * includes the aggregate-added COUNT column). Null means leftScanRowType == leftInputRowType.
   */
  private final RelDataType leftScanRowType;

  /**
   * Actual row type of the right table (from the scan node). Null means same as rightInputRowType.
   */
  private final RelDataType rightScanRowType;

  /**
   * Aggregate group set for the left join input. Non-null when the left input is an Aggregate
   * wrapping a scan (e.g., MinusToJoin). Null means no aggregate.
   */
  private final ImmutableBitSet leftAggGroupSet;

  /** Aggregate calls for the left join input. Non-null when leftAggGroupSet is non-null. */
  private final List<AggregateCall> leftAggCalls;

  /** Aggregate group set for the right join input. Non-null when the right input is an Aggregate. */
  private final ImmutableBitSet rightAggGroupSet;

  /** Aggregate calls for the right join input. Non-null when rightAggGroupSet is non-null. */
  private final List<AggregateCall> rightAggCalls;

  /**
   * LogicalProject expressions for the left join input. Non-null when a non-trivial
   * LogicalProject (e.g. with CAST) sits between the join and the left scan.
   * Used in getPhysicalOperator() to wrap the leaf in a JdbcProject that transforms
   * real scan columns into the leftInputRowType expected by conditionRex.
   */
  private final List<RexNode> leftProjectExprs;

  /**
   * LogicalProject expressions for the right join input. Same as leftProjectExprs but
   * for the right side.
   */
  private final List<RexNode> rightProjectExprs;

  /**
   * Creates a new logical join scan node.
   *
   * @param cluster the rel opt cluster
   * @param traitSet trait set (should include {@link Rel#LOGICAL} convention)
   * @param pluginId identifies the owning JDBC storage plugin (same for both sides)
   * @param leftSchema schema name for the left table
   * @param leftTable table name for the left table
   * @param rightSchema schema name for the right table
   * @param rightTable table name for the right table
   * @param joinType INNER, LEFT, RIGHT, or FULL join type
   * @param conditionRex the raw join ON condition as a Calcite {@link RexNode}
   * @param leftColumns projected columns from the left table
   * @param rightColumns projected columns from the right table
   * @param outputRowType the combined output row type of the join
   * @param leftInputRowType full row type of the left join input (may include aggregate columns)
   * @param rightInputRowType full row type of the right join input (may include aggregate columns)
   * @param leftScanRowType actual row type of the left table scan (null = same as leftInputRowType)
   * @param rightScanRowType actual row type of the right table scan (null = same as rightInputRowType)
   * @param leftAggGroupSet group set if left input is an Aggregate (null = no aggregate)
   * @param leftAggCalls aggregate calls if left input is an Aggregate (null = no aggregate)
   * @param rightAggGroupSet group set if right input is an Aggregate (null = no aggregate)
   * @param rightAggCalls aggregate calls if right input is an Aggregate (null = no aggregate)
   * @param leftProjectExprs LogicalProject expressions for left input (null = no project)
   * @param rightProjectExprs LogicalProject expressions for right input (null = no project)
   */
  public JdbcJoinScanDrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      StoragePluginId pluginId,
      String leftSchema,
      String leftTable,
      String rightSchema,
      String rightTable,
      JoinRelType joinType,
      RexNode conditionRex,
      List<SchemaPath> leftColumns,
      List<SchemaPath> rightColumns,
      RelDataType outputRowType,
      RelDataType leftInputRowType,
      RelDataType rightInputRowType,
      RelDataType leftScanRowType,
      RelDataType rightScanRowType,
      ImmutableBitSet leftAggGroupSet,
      List<AggregateCall> leftAggCalls,
      ImmutableBitSet rightAggGroupSet,
      List<AggregateCall> rightAggCalls,
      List<RexNode> leftProjectExprs,
      List<RexNode> rightProjectExprs) {
    super(cluster, traitSet);
    this.pluginId = pluginId;
    this.leftSchema = leftSchema;
    this.leftTable = leftTable;
    this.rightSchema = rightSchema;
    this.rightTable = rightTable;
    this.joinType = joinType;
    this.conditionRex = conditionRex;
    this.leftColumns = leftColumns != null ? leftColumns : Collections.emptyList();
    this.rightColumns = rightColumns != null ? rightColumns : Collections.emptyList();
    this.outputRowType = outputRowType;
    this.leftInputRowType = leftInputRowType;
    this.rightInputRowType = rightInputRowType;
    this.leftScanRowType = leftScanRowType;
    this.rightScanRowType = rightScanRowType;
    this.leftAggGroupSet = leftAggGroupSet;
    this.leftAggCalls = leftAggCalls;
    this.rightAggGroupSet = rightAggGroupSet;
    this.rightAggCalls = rightAggCalls;
    this.leftProjectExprs = leftProjectExprs != null ? ImmutableList.copyOf(leftProjectExprs) : null;
    this.rightProjectExprs = rightProjectExprs != null ? ImmutableList.copyOf(rightProjectExprs) : null;
  }

  @Override
  protected RelDataType deriveRowType() {
    return outputRowType;
  }

  /**
   * Returns a very low cost so the Volcano planner prefers the pushed-down JOIN over scanning both
   * tables separately and joining in-engine. The source engine handles the join with its own indexes
   * and statistics, transferring only the result set.
   */
  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    // Tiny cost: single remote call returning the joined result.
    return planner.getCostFactory().makeTinyCost();
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    // Conservative estimate: assume the result is smaller than either input.
    return 1.0;
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    // Leaf node: no inputs.
    return new JdbcJoinScanDrel(
        getCluster(),
        traitSet,
        pluginId,
        leftSchema,
        leftTable,
        rightSchema,
        rightTable,
        joinType,
        conditionRex,
        leftColumns,
        rightColumns,
        outputRowType,
        leftInputRowType,
        rightInputRowType,
        leftScanRowType,
        rightScanRowType,
        leftAggGroupSet,
        leftAggCalls,
        rightAggGroupSet,
        rightAggCalls,
        leftProjectExprs,
        rightProjectExprs);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("leftTable", leftSchema + "." + leftTable)
        .item("rightTable", rightSchema + "." + rightTable)
        .item("joinType", joinType)
        .item("condition", conditionRex);
  }

  // -------------------------------------------------------------------------
  // Accessors
  // -------------------------------------------------------------------------

  public StoragePluginId getPluginId() {
    return pluginId;
  }

  public String getLeftSchema() {
    return leftSchema;
  }

  public String getLeftTable() {
    return leftTable;
  }

  public String getRightSchema() {
    return rightSchema;
  }

  public String getRightTable() {
    return rightTable;
  }

  public JoinRelType getJoinType() {
    return joinType;
  }

  public RexNode getConditionRex() {
    return conditionRex;
  }

  public List<SchemaPath> getLeftColumns() {
    return leftColumns;
  }

  public List<SchemaPath> getRightColumns() {
    return rightColumns;
  }

  public RelDataType getLeftInputRowType() {
    return leftInputRowType;
  }

  public RelDataType getRightInputRowType() {
    return rightInputRowType;
  }

  /** Returns the actual left table scan row type, or null if same as leftInputRowType. */
  public RelDataType getLeftScanRowType() {
    return leftScanRowType;
  }

  /** Returns the actual right table scan row type, or null if same as rightInputRowType. */
  public RelDataType getRightScanRowType() {
    return rightScanRowType;
  }

  /** Returns the aggregate group set for the left input, or null if no aggregate. */
  public ImmutableBitSet getLeftAggGroupSet() {
    return leftAggGroupSet;
  }

  /** Returns the aggregate calls for the left input, or null if no aggregate. */
  public List<AggregateCall> getLeftAggCalls() {
    return leftAggCalls;
  }

  /** Returns the aggregate group set for the right input, or null if no aggregate. */
  public ImmutableBitSet getRightAggGroupSet() {
    return rightAggGroupSet;
  }

  /** Returns the aggregate calls for the right input, or null if no aggregate. */
  public List<AggregateCall> getRightAggCalls() {
    return rightAggCalls;
  }

  /** Returns the LogicalProject expressions for the left input, or null if no intermediate project. */
  public List<RexNode> getLeftProjectExprs() {
    return leftProjectExprs;
  }

  /** Returns the LogicalProject expressions for the right input, or null if no intermediate project. */
  public List<RexNode> getRightProjectExprs() {
    return rightProjectExprs;
  }
}
