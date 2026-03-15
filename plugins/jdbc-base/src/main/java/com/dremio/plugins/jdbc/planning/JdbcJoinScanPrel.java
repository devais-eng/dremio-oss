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
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.planner.fragment.DistributionAffinity;
import com.dremio.exec.planner.physical.LeafPrel;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.planner.physical.PhysicalPlanCreator;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.plugins.jdbc.JdbcStoragePlugin;
import com.dremio.plugins.jdbc.exec.JdbcGroupScan;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import java.util.Iterator;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataType;

/**
 * Physical scan node that builds JOIN SQL at {@link #getPhysicalOperator} time for JDBC pushdown.
 *
 * <p>Produced by {@link JdbcPushJoinIntoScan.JdbcJoinScanPrule} during the PHYSICAL planning phase
 * by converting a {@link JdbcJoinScanDrel}. At operator-creation time, this node:
 *
 * <ol>
 *   <li>Resolves the plugin-specific {@link SqlBuilder} from the catalog service.
 *   <li>Calls {@link SqlBuilder#buildJoinSql} to produce the complete JOIN SQL.
 *   <li>Converts the combined output row type to a {@link BatchSchema}.
 *   <li>Creates a {@link JdbcGroupScan} with the JOIN SQL, merged projected columns, and bind
 *       parameters.
 * </ol>
 *
 * <p>This is a leaf node (no children); JDBC is always single-node so
 * {@code getMaxParallelizationWidth()} returns 1.
 */
public class JdbcJoinScanPrel extends AbstractRelNode implements LeafPrel {

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
   * Creates a new physical join scan node.
   *
   * @param cluster the rel opt cluster
   * @param traitSet trait set (should include {@link Prel#PHYSICAL} convention)
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
  public JdbcJoinScanPrel(
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
            ? Collections.unmodifiableList(new ArrayList<>(conditionBindParams))
            : Collections.emptyList();
    this.leftColumns = leftColumns != null ? leftColumns : Collections.emptyList();
    this.rightColumns = rightColumns != null ? rightColumns : Collections.emptyList();
    this.outputRowType = outputRowType;
    // Eagerly set the rowType field (same pattern as JdbcScanPrel with overrideRowType).
    this.rowType = outputRowType;
  }

  // -------------------------------------------------------------------------
  // Prel / LeafPrel contract
  // -------------------------------------------------------------------------

  @Override
  public int getMaxParallelizationWidth() {
    return 1;
  }

  @Override
  public int getMinParallelizationWidth() {
    return 1;
  }

  @Override
  public DistributionAffinity getDistributionAffinity() {
    return DistributionAffinity.SOFT;
  }

  /**
   * Returns a very low cost so the Volcano planner prefers the pushed-down JOIN over the
   * HashJoin(JdbcScan, JdbcScan) alternative. The source engine executes the join with its own
   * indexes and statistics, avoiding network transfer of both full tables.
   */
  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    return planner.getCostFactory().makeTinyCost();
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return 1.0;
  }

  /**
   * The JOIN SQL SELECT list is explicitly ordered (left columns then right columns), so no
   * post-scan column reordering is needed.
   */
  @Override
  public boolean needsFinalColumnReordering() {
    return false;
  }

  /** JDBC scans produce no selection vector. */
  @Override
  public SelectionVectorMode getEncoding() {
    return SelectionVectorMode.NONE;
  }

  @Override
  public SelectionVectorMode[] getSupportedEncodings() {
    return SelectionVectorMode.DEFAULT;
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitLeaf(this, value);
  }

  @Override
  protected RelDataType deriveRowType() {
    return outputRowType;
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new JdbcJoinScanPrel(
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
        .item("plugin", pluginId.getName())
        .item("leftTable", leftSchema + "." + leftTable)
        .item("rightTable", rightSchema + "." + rightTable)
        .item("joinType", joinType)
        .item("onClause", onClauseSql);
  }

  @Override
  public Iterator<Prel> iterator() {
    return Collections.emptyIterator();
  }

  /**
   * Assembles the final JOIN SQL via {@link SqlBuilder#buildJoinSql} and creates a
   * {@link JdbcGroupScan} physical operator for execution.
   *
   * <p>Resolves the plugin-specific {@link SqlBuilder} (e.g. {@link
   * com.dremio.plugins.jdbc.oracle.OracleSqlBuilder}) so that the JOIN SQL can use the correct
   * dialect for identifier quoting and alias syntax.
   */
  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    // Resolve the plugin-specific SqlBuilder (Oracle, Postgres, etc.)
    StoragePlugin rawPlugin =
        creator.getContext().getCatalogService().getSource(pluginId.getName());
    SqlBuilder sb =
        (rawPlugin instanceof JdbcStoragePlugin)
            ? ((JdbcStoragePlugin) rawPlugin).createSqlBuilder()
            : new SqlBuilder();

    // Build the JOIN SQL via the dialect-aware SqlBuilder.
    String sql =
        sb.buildJoinSql(
            leftSchema,
            leftTable,
            "t1",
            rightSchema,
            rightTable,
            "t2",
            SqlBuilder.joinTypeToSql(joinType),
            onClauseSql,
            leftColumns,
            rightColumns);

    // Derive the combined output schema from the Calcite row type.
    BatchSchema outputSchema = CalciteArrowHelper.fromCalciteRowType(outputRowType);

    // Build merged projected columns list (left + right).
    List<SchemaPath> mergedColumns = new ArrayList<>();
    mergedColumns.addAll(leftColumns);
    mergedColumns.addAll(rightColumns);
    if (mergedColumns.isEmpty()) {
      // Fallback: derive from outputSchema fields.
      for (org.apache.arrow.vector.types.pojo.Field f : outputSchema.getFields()) {
        mergedColumns.add(SchemaPath.getSimplePath(f.getName()));
      }
    }

    // tableSchemaPath for JdbcGroupScan: synthetic path for a join "table".
    List<String> tableSchemaPath = Collections.singletonList("_join_");

    return new JdbcGroupScan(
        creator.props(this, null, outputSchema),
        sql,
        mergedColumns,
        outputSchema,
        pluginId,
        tableSchemaPath,
        conditionBindParams);
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
