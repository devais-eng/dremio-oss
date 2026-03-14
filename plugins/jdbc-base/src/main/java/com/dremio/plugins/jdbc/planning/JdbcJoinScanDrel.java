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
import java.util.Collections;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataType;

/**
 * Logical join scan node carrying raw join components for JDBC pushdown.
 *
 * <p>Produced by {@link JdbcPushJoinIntoScan} at the LOGICAL planning phase when both sides of a
 * {@link com.dremio.exec.planner.logical.JoinRel} reference tables from the same JDBC source.
 *
 * <p>This is a leaf node (no children) that stores all raw join components needed to later build
 * the JOIN SQL at physical time: schema/table names, join type, ON clause SQL, bind params, and
 * projected columns for each side.
 *
 * <p>The subsequent PHYSICAL phase converts this to {@link JdbcJoinScanPrel} via the
 * {@link JdbcPushJoinIntoScan.JdbcJoinScanPrule}, which resolves the plugin-specific
 * {@link SqlBuilder} and builds the final JOIN SQL in {@code getPhysicalOperator()}.
 */
public class JdbcJoinScanDrel extends AbstractRelNode implements Rel {

  private final StoragePluginId pluginId;
  private final String leftSchema;
  private final String leftTable;
  private final String rightSchema;
  private final String rightTable;
  private final JoinRelType joinType;
  private final String onClauseSql;
  private final List<BindParam> conditionBindParams;
  private final List<SchemaPath> leftColumns;
  private final List<SchemaPath> rightColumns;
  private final RelDataType outputRowType;

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
   * @param onClauseSql the ON condition as a SQL string with ? placeholders
   * @param conditionBindParams bind parameters for the ON clause ? placeholders
   * @param leftColumns projected columns from the left table
   * @param rightColumns projected columns from the right table
   * @param outputRowType the combined output row type of the join
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
      String onClauseSql,
      List<BindParam> conditionBindParams,
      List<SchemaPath> leftColumns,
      List<SchemaPath> rightColumns,
      RelDataType outputRowType) {
    super(cluster, traitSet);
    this.pluginId = pluginId;
    this.leftSchema = leftSchema;
    this.leftTable = leftTable;
    this.rightSchema = rightSchema;
    this.rightTable = rightTable;
    this.joinType = joinType;
    this.onClauseSql = onClauseSql;
    this.conditionBindParams =
        conditionBindParams != null
            ? Collections.unmodifiableList(conditionBindParams)
            : Collections.emptyList();
    this.leftColumns = leftColumns != null ? leftColumns : Collections.emptyList();
    this.rightColumns = rightColumns != null ? rightColumns : Collections.emptyList();
    this.outputRowType = outputRowType;
  }

  @Override
  protected RelDataType deriveRowType() {
    return outputRowType;
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
        onClauseSql,
        conditionBindParams,
        leftColumns,
        rightColumns,
        outputRowType);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("leftTable", leftSchema + "." + leftTable)
        .item("rightTable", rightSchema + "." + rightTable)
        .item("joinType", joinType)
        .item("onClause", onClauseSql);
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

  public String getOnClauseSql() {
    return onClauseSql;
  }

  public List<BindParam> getConditionBindParams() {
    return conditionBindParams;
  }

  public List<SchemaPath> getLeftColumns() {
    return leftColumns;
  }

  public List<SchemaPath> getRightColumns() {
    return rightColumns;
  }
}
