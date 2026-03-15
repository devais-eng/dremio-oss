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
import com.dremio.exec.planner.physical.PhysicalPlanCreator;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.plugins.jdbc.JdbcStoragePlugin;
import com.dremio.plugins.jdbc.exec.JdbcGroupScan;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.adapter.jdbc.JdbcConvention;
import org.apache.calcite.adapter.jdbc.JdbcRules;
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
import org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * Physical scan node that builds JOIN SQL at {@link #getPhysicalOperator} time for JDBC pushdown.
 *
 * <p>Produced by {@link JdbcPushJoinIntoScan.JdbcJoinScanPrule} during the PHYSICAL planning phase
 * by converting a {@link JdbcJoinScanDrel}. At operator-creation time, this node:
 *
 * <ol>
 *   <li>Resolves the plugin-specific {@link SqlDialect} via {@link JdbcStoragePlugin#createDialect()}.
 *   <li>Builds a Calcite JdbcRel subtree: two {@link JdbcCalciteLeaf} nodes connected by a
 *       {@link JdbcRules.JdbcJoin}, optionally wrapped with a {@link JdbcRules.JdbcProject} for
 *       column aliasing (e.g., dedup suffixes like {@code department0} for self-joins).
 *   <li>Renders the complete JOIN SQL via {@link DremioJdbcImplementor}. Dialect-specific syntax
 *       (Oracle omits AS keyword, PostgreSQL includes it) is handled automatically by the dialect.
 *   <li>Converts the combined output row type to a {@link BatchSchema}.
 *   <li>Creates a {@link JdbcGroupScan} with the JOIN SQL, merged projected columns, and an empty
 *       bind-params list (Calcite renders literals inline).
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
  private final RexNode conditionRex;
  private final List<SchemaPath> leftColumns;
  private final List<SchemaPath> rightColumns;
  private final RelDataType outputRowType;
  /**
   * Full row type of the left scan input. The conditionRex RexInputRef indices reference this
   * row type (all left columns), not just the projected leftColumns.
   */
  private final RelDataType leftInputRowType;
  /**
   * Full row type of the right scan input. The conditionRex RexInputRef indices for the right
   * side are offset by leftInputRowType.getFieldCount().
   */
  private final RelDataType rightInputRowType;

  /** Actual row type of the left table scan. Null means same as leftInputRowType. */
  private final RelDataType leftScanRowType;

  /** Actual row type of the right table scan. Null means same as rightInputRowType. */
  private final RelDataType rightScanRowType;

  /** Aggregate group set for the left join input. Null means no aggregate wrapping. */
  private final ImmutableBitSet leftAggGroupSet;

  /** Aggregate calls for the left join input. Null means no aggregate wrapping. */
  private final List<AggregateCall> leftAggCalls;

  /** Aggregate group set for the right join input. Null means no aggregate wrapping. */
  private final ImmutableBitSet rightAggGroupSet;

  /** Aggregate calls for the right join input. Null means no aggregate wrapping. */
  private final List<AggregateCall> rightAggCalls;

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
   * @param conditionRex the join ON condition as a Calcite {@link RexNode}
   * @param leftColumns projected columns from the left table
   * @param rightColumns projected columns from the right table
   * @param outputRowType the combined output row type of the join (all columns, before projection)
   * @param leftInputRowType full row type of the left join input (may include aggregate columns)
   * @param rightInputRowType full row type of the right join input (may include aggregate columns)
   * @param leftScanRowType actual left table scan row type (null = same as leftInputRowType)
   * @param rightScanRowType actual right table scan row type (null = same as rightInputRowType)
   * @param leftAggGroupSet aggregate group set for left input (null = no aggregate)
   * @param leftAggCalls aggregate calls for left input (null = no aggregate)
   * @param rightAggGroupSet aggregate group set for right input (null = no aggregate)
   * @param rightAggCalls aggregate calls for right input (null = no aggregate)
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
      List<AggregateCall> rightAggCalls) {
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
    this.leftInputRowType = leftInputRowType != null ? leftInputRowType : outputRowType;
    this.rightInputRowType = rightInputRowType != null ? rightInputRowType : outputRowType;
    this.leftScanRowType = leftScanRowType;
    this.rightScanRowType = rightScanRowType;
    this.leftAggGroupSet = leftAggGroupSet;
    this.leftAggCalls = leftAggCalls;
    this.rightAggGroupSet = rightAggGroupSet;
    this.rightAggCalls = rightAggCalls;
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
        rightAggCalls);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("plugin", pluginId.getName())
        .item("leftTable", leftSchema + "." + leftTable)
        .item("rightTable", rightSchema + "." + rightTable)
        .item("joinType", joinType)
        .item("condition", conditionRex);
  }

  @Override
  public Iterator<Prel> iterator() {
    return Collections.emptyIterator();
  }

  /**
   * Assembles the final JOIN SQL by building a Calcite JdbcRel subtree and rendering it via
   * {@link DremioJdbcImplementor}, then creates a {@link JdbcGroupScan} physical operator for
   * execution.
   *
   * <p>The subtree is: two {@link JdbcCalciteLeaf} nodes (one per table) connected by a
   * {@link JdbcRules.JdbcJoin}, optionally wrapped in a {@link JdbcRules.JdbcProject} when
   * the output column names differ from the table column names (e.g., self-join dedup suffixes
   * like {@code department0}).
   *
   * <p>Dialect-specific syntax is handled automatically: {@code OracleSqlDialect.allowsAs()==false}
   * causes table aliases to be rendered without the AS keyword; {@code PostgresqlSqlDialect} renders
   * them with AS. No manual SQL string manipulation is needed.
   */
  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    // ---- 1. Resolve dialect -----------------------------------------------
    StoragePlugin rawPlugin =
        creator.getContext().getCatalogService().getSource(pluginId.getName());
    JdbcStoragePlugin jdbcPlugin =
        (rawPlugin instanceof JdbcStoragePlugin) ? (JdbcStoragePlugin) rawPlugin : null;
    SqlDialect dialect =
        jdbcPlugin != null ? jdbcPlugin.createDialect() : PostgresqlSqlDialect.DEFAULT;

    // ---- 2. Create JdbcConvention and trait set ----------------------------
    JdbcConvention convention =
        JdbcConvention.of(dialect, null, "DREMIO_JDBC_" + pluginId.getName());
    RelOptCluster cluster = getCluster();
    RelTraitSet jdbcTraitSet = cluster.traitSet().replace(convention);
    RelDataTypeFactory typeFactory = cluster.getTypeFactory();

    // ---- 3. Build leaf nodes and optional aggregate wrappers -----------------
    // When the join input was a direct table scan, leftScanRowType is null and
    // leftInputRowType IS the table's row type. Use it directly as the leaf row type.
    //
    // When the join input was an Aggregate wrapping a scan (e.g., MinusToJoin for EXCEPT),
    // leftScanRowType holds the actual table row type, and leftInputRowType holds the
    // aggregate's output row type (table cols + COUNT). We must:
    //   (a) Create the leaf with the SCAN row type (actual table columns)
    //   (b) Wrap it in a JdbcAggregate to produce the aggregate output row type
    // This ensures conditionRex indices (built against aggregate output) map correctly.
    RelDataType leftLeafRowType =
        leftScanRowType != null ? leftScanRowType : leftInputRowType;
    RelDataType rightLeafRowType =
        rightScanRowType != null ? rightScanRowType : rightInputRowType;

    // ---- 4. Build leaf nodes and optional JdbcAggregate wrappers -----------
    JdbcCalciteLeaf leftLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, leftLeafRowType, leftSchema, leftTable);
    JdbcCalciteLeaf rightLeaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, rightLeafRowType, rightSchema, rightTable);

    // Wrap leaves in JdbcAggregate when set-operation rewrites added aggregation
    RelNode leftInput;
    if (leftAggGroupSet != null) {
      try {
        leftInput =
            new JdbcRules.JdbcAggregate(
                cluster,
                jdbcTraitSet,
                leftLeaf,
                leftAggGroupSet,
                com.google.common.collect.ImmutableList.of(leftAggGroupSet),
                leftAggCalls);
      } catch (org.apache.calcite.rel.InvalidRelException e) {
        throw new IOException("Invalid aggregate for left JDBC join input: " + e.getMessage(), e);
      }
    } else {
      leftInput = leftLeaf;
    }

    RelNode rightInput;
    if (rightAggGroupSet != null) {
      try {
        rightInput =
            new JdbcRules.JdbcAggregate(
                cluster,
                jdbcTraitSet,
                rightLeaf,
                rightAggGroupSet,
                com.google.common.collect.ImmutableList.of(rightAggGroupSet),
                rightAggCalls);
      } catch (org.apache.calcite.rel.InvalidRelException e) {
        throw new IOException("Invalid aggregate for right JDBC join input: " + e.getMessage(), e);
      }
    } else {
      rightInput = rightLeaf;
    }

    // ---- 5. Build JdbcJoin -------------------------------------------------
    // The conditionRex references column indices in the COMBINED row type
    // (left fields 0..leftCount-1, right fields leftCount..leftCount+rightCount-1).
    // When aggregates are present, leftInput/rightInput are JdbcAggregate nodes whose
    // output row types match leftInputRowType/rightInputRowType, so the indices align.
    RelNode joinNode;
    try {
      joinNode =
          new JdbcRules.JdbcJoin(
              cluster,
              jdbcTraitSet,
              leftInput,
              rightInput,
              conditionRex,
              Collections.emptySet(),
              joinType);
    } catch (org.apache.calcite.rel.InvalidRelException e) {
      throw new IOException("Invalid join for JDBC pushdown: " + e.getMessage(), e);
    }

    // ---- 6. Wrap with JdbcProject to select projected columns --------------
    // The JdbcJoin output row type is the FULL combined row type (all columns from both tables).
    // We need to project down to just the columns in leftColumns + rightColumns, with the output
    // names from outputRowType (which may have dedup suffixes like "department0" for self-joins).
    //
    // Mapping strategy:
    //   - For each leftColumn[i], find the field index in leftInputRowType by name.
    //     The combined join row type has left fields at indices 0..leftInputRowType.getFieldCount()-1.
    //   - For each rightColumn[i], find the field index in rightInputRowType by name.
    //     The combined join row type has right fields at indices
    //     leftInputRowType.getFieldCount()..leftInputRowType.getFieldCount()+rightInputRowType.getFieldCount()-1.
    // The output names come from outputRowType (left projected fields first, then right projected).
    RelNode root = joinNode;
    List<RelDataTypeField> joinFields = joinNode.getRowType().getFieldList();
    List<RelDataTypeField> expectedFields = outputRowType.getFieldList();
    int leftFullCount = leftInputRowType.getFieldCount();

    // Build index maps for fast lookup by name within each side's input row type.
    java.util.Map<String, Integer> leftNameToIdx = new java.util.LinkedHashMap<>();
    for (RelDataTypeField f : leftInputRowType.getFieldList()) {
      leftNameToIdx.putIfAbsent(f.getName(), f.getIndex());
    }
    java.util.Map<String, Integer> rightNameToIdx = new java.util.LinkedHashMap<>();
    for (RelDataTypeField f : rightInputRowType.getFieldList()) {
      rightNameToIdx.putIfAbsent(f.getName(), f.getIndex());
    }

    RexBuilder rexBuilder = cluster.getRexBuilder();
    List<RexNode> projects = new ArrayList<>();
    List<String> projNames = new ArrayList<>();

    // Project left columns (outputRowType fields 0..leftColumns.size()-1)
    for (int i = 0; i < leftColumns.size(); i++) {
      String colName = leftColumns.get(i).getRootSegment().getPath();
      Integer srcIdx = leftNameToIdx.get(colName);
      if (srcIdx == null) {
        // Fallback: use position in leftColumns
        srcIdx = i < leftFullCount ? i : 0;
      }
      int joinIdx = srcIdx; // left fields start at index 0 in the join row type
      RelDataTypeField joinField = joinFields.get(joinIdx);
      projects.add(rexBuilder.makeInputRef(joinField.getType(), joinIdx));
      projNames.add(i < expectedFields.size() ? expectedFields.get(i).getName() : colName);
    }

    // Project right columns (outputRowType fields leftColumns.size()..leftColumns.size()+rightColumns.size()-1)
    for (int i = 0; i < rightColumns.size(); i++) {
      String colName = rightColumns.get(i).getRootSegment().getPath();
      Integer srcIdx = rightNameToIdx.get(colName);
      if (srcIdx == null) {
        srcIdx = i < rightInputRowType.getFieldCount() ? i : 0;
      }
      int joinIdx = leftFullCount + srcIdx; // right fields start at leftFullCount
      RelDataTypeField joinField = joinFields.get(joinIdx);
      projects.add(rexBuilder.makeInputRef(joinField.getType(), joinIdx));
      int expectedIdx = leftColumns.size() + i;
      projNames.add(expectedIdx < expectedFields.size() ? expectedFields.get(expectedIdx).getName() : colName);
    }

    // Always add the project to select/rename columns.
    // Even if projecting all columns with the same names, we need the explicit SELECT list
    // so the generated SQL does not use SELECT * with ambiguous column names.
    RelDataType projRowType =
        typeFactory.createStructType(
            projects.stream().map(RexNode::getType).collect(Collectors.toList()), projNames);
    root = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, root, projects, projRowType);

    // ---- 7. Render SQL via DremioJdbcImplementor ---------------------------
    JavaTypeFactory jtf = (JavaTypeFactory) typeFactory;
    DremioJdbcImplementor implementor = new DremioJdbcImplementor(dialect, jtf);
    SqlImplementor.Result result = implementor.implement(root);
    String sql = result.asStatement().toSqlString(dialect).getSql();

    // ---- 8. Build output schema and JdbcGroupScan -------------------------
    // Use Calcite's row type directly for the output schema -- the field names
    // match the SQL column aliases (including dedup suffixes like "department0").
    BatchSchema outputSchema = CalciteArrowHelper.fromCalciteRowType(outputRowType);

    List<SchemaPath> mergedColumns = new ArrayList<>();
    for (org.apache.arrow.vector.types.pojo.Field f : outputSchema.getFields()) {
      mergedColumns.add(SchemaPath.getSimplePath(f.getName()));
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
        Collections.emptyList());
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

  public RelDataType getLeftScanRowType() {
    return leftScanRowType;
  }

  public RelDataType getRightScanRowType() {
    return rightScanRowType;
  }

  public ImmutableBitSet getLeftAggGroupSet() {
    return leftAggGroupSet;
  }

  public List<AggregateCall> getLeftAggCalls() {
    return leftAggCalls;
  }

  public ImmutableBitSet getRightAggGroupSet() {
    return rightAggGroupSet;
  }

  public List<AggregateCall> getRightAggCalls() {
    return rightAggCalls;
  }
}
