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
import com.dremio.exec.planner.physical.PhysicalPlanCreator;
import com.dremio.exec.planner.physical.ScanPrelBase;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.TableMetadata;
import com.dremio.plugins.jdbc.JdbcStoragePlugin;
import com.dremio.plugins.jdbc.exec.JdbcGroupScan;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.adapter.jdbc.JdbcConvention;
import org.apache.calcite.adapter.jdbc.JdbcRules;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * Physical scan node for JDBC-backed tables in the Dremio planner.
 *
 * <p>Carries the pushdown state accumulated during the PHYSICAL planning phase as Calcite objects
 * (not SQL strings):
 *
 * <ul>
 *   <li>{@link #filterRex} -- Calcite {@link RexNode} pushed down from a FilterPrel
 *   <li>{@link #collation} -- Calcite {@link RelCollation} pushed down from a SortPrel
 *   <li>{@link #groupSet} -- Calcite {@link ImmutableBitSet} pushed down from an AggregatePrel
 *   <li>{@link #aggCalls} -- Calcite {@link AggregateCall} list pushed down from an AggregatePrel
 *   <li>{@link #limit} -- row limit pushed down from a LimitPrel
 *   <li>projected columns -- columns to project (inherited from ScanPrelBase)
 *   <li>{@link #sortKeyExpressions} -- function expressions for ORDER BY (e.g. UPPER(name))
 * </ul>
 *
 * <p>The {@link #getPhysicalOperator} method builds a Calcite JDBC convention subtree
 * ({@code JdbcCalciteLeaf -> JdbcFilter -> JdbcProject -> JdbcAggregate -> JdbcSort}) and renders
 * the final SQL string via {@link DremioJdbcImplementor}, which handles all dialect-specific
 * concerns (LIMIT vs FETCH FIRST, identifier quoting, AS keyword presence) automatically.
 */
public class JdbcScanPrel extends ScanPrelBase {

  private final String schemaName;
  private final String tableName;
  private final RexNode filterRex;
  private final RelCollation collation;
  private final Integer limit;
  private final ImmutableBitSet groupSet;
  private final List<AggregateCall> aggCalls;
  private final RelDataType overrideRowType;
  /** Post-aggregation filter (HAVING clause); null when not pushed. */
  private final RexNode havingRex;
  /**
   * Sort key expressions for ORDER BY (e.g. RexCall(UPPER, [RexInputRef(3)])). Non-null only
   * when ORDER BY uses function expressions pushed from a SortPrel or TopNPrel above a ProjectPrel.
   * Simple column-ref sorts still use {@link #collation} with null sortKeyExpressions.
   */
  private final List<RexNode> sortKeyExpressions;

  // -------------------------------------------------------------------------
  // Full constructor
  // -------------------------------------------------------------------------

  /**
   * Full constructor with all pushdown state as Calcite objects.
   *
   * @param filterRex Calcite RexNode filter condition (WHERE clause); null when no filter pushed
   * @param collation Calcite RelCollation for ORDER BY; null when no sort pushed
   * @param limit row limit (LIMIT / FETCH FIRST); null when not pushed
   * @param groupSet GROUP BY column indices; null when no aggregation pushed
   * @param aggCalls aggregate function calls; null when no aggregation pushed
   * @param overrideRowType when non-null, overrides the derived row type for aggregated output
   * @param havingRex post-aggregation filter (HAVING clause); null when not pushed
   * @param sortKeyExpressions sort key function expressions for ORDER BY; null for simple column-ref sorts
   */
  public JdbcScanPrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelOptTable table,
      StoragePluginId pluginId,
      TableMetadata dataset,
      List<SchemaPath> projectedColumns,
      double observedRowcountAdjustment,
      List<RelHint> hints,
      List<Info> runtimeFilters,
      String schemaName,
      String tableName,
      RexNode filterRex,
      RelCollation collation,
      Integer limit,
      ImmutableBitSet groupSet,
      List<AggregateCall> aggCalls,
      RelDataType overrideRowType,
      RexNode havingRex,
      List<RexNode> sortKeyExpressions) {
    super(
        cluster,
        traitSet,
        table,
        pluginId,
        dataset,
        projectedColumns,
        observedRowcountAdjustment,
        hints,
        runtimeFilters);
    this.schemaName = Preconditions.checkNotNull(schemaName, "schemaName");
    this.tableName = Preconditions.checkNotNull(tableName, "tableName");
    this.filterRex = filterRex;
    this.collation = collation;
    this.limit = limit;
    this.groupSet = groupSet;
    this.aggCalls = aggCalls != null ? ImmutableList.copyOf(aggCalls) : null;
    this.overrideRowType = overrideRowType;
    this.havingRex = havingRex;
    this.sortKeyExpressions = sortKeyExpressions != null ? ImmutableList.copyOf(sortKeyExpressions) : null;
    // Force the cached rowType in AbstractRelNode so Volcano sees the aggregated schema.
    if (overrideRowType != null) {
      this.rowType = overrideRowType;
    }
  }

  /**
   * Simple constructor with no pushdown state. All Calcite pushdown fields are null.
   */
  public JdbcScanPrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelOptTable table,
      StoragePluginId pluginId,
      TableMetadata dataset,
      List<SchemaPath> projectedColumns,
      double observedRowcountAdjustment,
      List<RelHint> hints,
      List<Info> runtimeFilters,
      String schemaName,
      String tableName) {
    this(
        cluster,
        traitSet,
        table,
        pluginId,
        dataset,
        projectedColumns,
        observedRowcountAdjustment,
        hints,
        runtimeFilters,
        schemaName,
        tableName,
        null,  // filterRex
        null,  // collation
        null,  // limit
        null,  // groupSet
        null,  // aggCalls
        null,  // overrideRowType
        null,  // havingRex
        null); // sortKeyExpressions
  }

  // -------------------------------------------------------------------------
  // Clone helpers used by pushdown rules
  // -------------------------------------------------------------------------

  /** Returns a new JdbcScanPrel with updated projected columns, preserving all pushdown state. */
  @Override
  public JdbcScanPrel cloneWithProject(List<SchemaPath> projection) {
    return new JdbcScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        projection,
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        filterRex,
        collation,
        limit,
        groupSet,
        aggCalls,
        overrideRowType,
        havingRex,
        sortKeyExpressions);
  }

  /**
   * Returns a new JdbcScanPrel with a filter RexNode pushed down.
   *
   * @param newFilterRex the Calcite RexNode representing the WHERE condition
   */
  public JdbcScanPrel cloneWithFilter(RexNode newFilterRex) {
    return new JdbcScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        newFilterRex,
        collation,
        limit,
        groupSet,
        aggCalls,
        overrideRowType,
        havingRex,
        sortKeyExpressions);
  }

  /** Returns a new JdbcScanPrel with an updated LIMIT, preserving all other pushdown state. */
  public JdbcScanPrel cloneWithLimit(int newLimit) {
    return new JdbcScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        filterRex,
        collation,
        newLimit,
        groupSet,
        aggCalls,
        overrideRowType,
        havingRex,
        sortKeyExpressions);
  }

  /**
   * Returns a new JdbcScanPrel with a sort collation pushed down.
   *
   * @param newCollation the Calcite RelCollation representing ORDER BY
   */
  public JdbcScanPrel cloneWithCollation(RelCollation newCollation) {
    return new JdbcScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        filterRex,
        newCollation,
        limit,
        groupSet,
        aggCalls,
        overrideRowType,
        havingRex,
        sortKeyExpressions);
  }

  /**
   * Returns a new JdbcScanPrel with aggregation pushed down.
   *
   * <p>When aggregation is active, projectedColumns are set to null because the SELECT list is
   * driven entirely by the {@code JdbcRules.JdbcAggregate} node built from {@code newGroupSet} and
   * {@code newAggCalls}. The groupSet and aggCall indices must already be normalized to full-table
   * column positions (done by {@link JdbcPushAggIntoScan} before calling this method).
   *
   * @param newGroupSet the GROUP BY column indices (normalized to full-table positions)
   * @param newAggCalls the aggregate function calls (normalized to full-table positions)
   * @param newRowType the aggregated output row type matching the AggregatePrel's output
   */
  public JdbcScanPrel cloneWithAggregation(
      ImmutableBitSet newGroupSet, List<AggregateCall> newAggCalls, RelDataType newRowType) {
    return new JdbcScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        null, // aggregation drives the SELECT list; individual column projection is irrelevant
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        filterRex,
        collation,
        limit,
        newGroupSet,
        newAggCalls,
        newRowType,
        havingRex,
        sortKeyExpressions);
  }

  /**
   * Returns a new JdbcScanPrel with a HAVING condition pushed down.
   *
   * <p>The HAVING condition references the aggregate output row type (group keys + agg functions).
   * It must only be called on a scan that already has aggregation pushed ({@link #hasAggregation()}).
   *
   * @param newHavingRex the post-aggregation filter condition (HAVING clause)
   */
  public JdbcScanPrel cloneWithHaving(RexNode newHavingRex) {
    return new JdbcScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        filterRex,
        collation,
        limit,
        groupSet,
        aggCalls,
        overrideRowType,
        newHavingRex,
        sortKeyExpressions);
  }

  /**
   * Returns a new JdbcScanPrel with sort key function expressions and collation pushed down.
   *
   * <p>This is used by JdbcPushSortWithExpressionsHep and JdbcPushTopNWithExpressionsHep when
   * the ORDER BY contains function expressions (e.g. ORDER BY UPPER(name)). The sort key
   * expressions are the actual RexNode trees extracted from the ProjectPrel at push time.
   *
   * @param newCollation the ORDER BY collation (field indices reference the extended project output)
   * @param sortExprs the sort key expression list (one RexNode per ORDER BY field)
   */
  public JdbcScanPrel cloneWithSortKeyExpressions(RelCollation newCollation, List<RexNode> sortExprs) {
    return new JdbcScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        filterRex,
        newCollation,
        limit,
        groupSet,
        aggCalls,
        overrideRowType,
        havingRex,
        sortExprs);
  }

  // -------------------------------------------------------------------------
  // Prel contract
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
  public boolean hasFilter() {
    return filterRex != null;
  }

  @Override
  public double getFilterReduction() {
    return filterRex == null ? super.getFilterReduction() : 0.15d;
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    double baseCount = super.estimateRowCount(mq);
    if (limit != null) {
      return Math.min(baseCount, limit);
    }
    return baseCount;
  }

  @Override
  public DistributionAffinity getDistributionAffinity() {
    return DistributionAffinity.SOFT;
  }

  @Override
  public RelDataType deriveRowType() {
    if (overrideRowType != null) {
      return overrideRowType;
    }
    return super.deriveRowType();
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    Preconditions.checkArgument(inputs == null || inputs.isEmpty());
    return new JdbcScanPrel(
        getCluster(),
        traitSet,
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        filterRex,
        collation,
        limit,
        groupSet,
        aggCalls,
        overrideRowType,
        havingRex,
        sortKeyExpressions);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    super.explainTerms(pw);
    pw.itemIf("schema", schemaName, schemaName != null);
    pw.itemIf("table", tableName, tableName != null);
    pw.itemIf("filter", filterRex, filterRex != null);
    pw.itemIf("collation", collation, collation != null);
    pw.itemIf("sortKeyExpressions", sortKeyExpressions, sortKeyExpressions != null);
    pw.itemIf("groupSet", groupSet, groupSet != null);
    pw.itemIf("aggCalls", aggCalls, aggCalls != null && !aggCalls.isEmpty());
    pw.itemIf("having", havingRex, havingRex != null);
    pw.itemIf("limit", limit, limit != null);
    return pw;
  }

  /**
   * Builds a Calcite JDBC convention subtree and renders it to SQL via {@link
   * DremioJdbcImplementor}, then creates the {@link JdbcGroupScan} physical operator for
   * execution.
   *
   * <p>The subtree is: {@code JdbcCalciteLeaf -> [JdbcFilter] -> [JdbcProject] ->
   * [JdbcAggregate] -> [JdbcSort]}, where each layer is only added when the corresponding
   * pushdown state is non-null.
   *
   * <p>Calcite handles all dialect-specific rendering automatically: {@code PostgresqlSqlDialect}
   * renders {@code LIMIT N}; {@code OracleSqlDialect} renders {@code FETCH FIRST N ROWS ONLY}. The
   * bind-params list passed to {@link JdbcGroupScan} is always empty because Calcite renders
   * literals inline (no {@code ?} placeholders).
   */
  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    // ---- 1. Resolve dialect -----------------------------------------------
    StoragePlugin rawPlugin =
        creator.getContext().getCatalogService().getSource(getPluginId().getName());
    JdbcStoragePlugin jdbcPlugin =
        (rawPlugin instanceof JdbcStoragePlugin) ? (JdbcStoragePlugin) rawPlugin : null;
    SqlDialect dialect =
        jdbcPlugin != null ? jdbcPlugin.createDialect() : PostgresqlSqlDialect.DEFAULT;

    // ---- 2. Create JdbcConvention and trait set ----------------------------
    JdbcConvention convention =
        JdbcConvention.of(dialect, null, "DREMIO_JDBC_" + getPluginId().getName());
    RelOptCluster cluster = getCluster();
    RelTraitSet jdbcTraitSet = cluster.traitSet().replace(convention);

    // ---- 3. Determine the full table row type for the leaf -----------------
    // The full (unfiltered, unprojected) table row type is needed so JdbcFilter
    // and JdbcProject can reference column indices correctly.
    // Captured as a final local so anonymous RexShuttle classes can reference it.
    final RelDataType fullTableRowType;
    {
      RelDataType tmp;
      try {
        tmp = getTable().getRowType();
      } catch (Exception e) {
        tmp = deriveRowType();
      }
      fullTableRowType = tmp;
    }

    // ---- 4. Build leaf -----------------------------------------------------
    JdbcCalciteLeaf leaf =
        new JdbcCalciteLeaf(cluster, jdbcTraitSet, fullTableRowType, schemaName, tableName);
    RelNode root = leaf;

    // ---- 5. Build projected-to-full-table index mapping -------------------
    // Used to build the JdbcProject's RexInputRef nodes (mapping projected position → full index).
    // filterRex, groupSet, and aggCall indices are already normalized to full-table positions
    // by JdbcPushFilterIntoScan and JdbcPushAggIntoScan at push time. No remap needed here.
    final int[] projToFull;
    if (getProjectedColumns() != null && !getProjectedColumns().isEmpty()) {
      projToFull = new int[getProjectedColumns().size()];
      for (int pi = 0; pi < getProjectedColumns().size(); pi++) {
        String colName = getProjectedColumns().get(pi).getRootSegment().getPath();
        int fullIdx = -1;
        for (int fi = 0; fi < fullTableRowType.getFieldCount(); fi++) {
          if (fullTableRowType.getFieldList().get(fi).getName().equalsIgnoreCase(colName)) {
            fullIdx = fi;
            break;
          }
        }
        projToFull[pi] = (fullIdx >= 0) ? fullIdx : pi; // fallback: identity
      }
    } else {
      projToFull = null;
    }

    // ---- 6. Wrap with JdbcFilter if filterRex present ---------------------
    // filterRex indices are already normalized to full-table positions by
    // JdbcPushFilterIntoScan. No remapping needed here.
    if (filterRex != null) {
      root = new JdbcRules.JdbcFilter(cluster, jdbcTraitSet, root, filterRex);
    }

    // ---- 7. Wrap with JdbcProject for column projection (no aggregation) --
    // JdbcProject uses full-table indices to select the projected columns.
    // It sits ABOVE the JdbcFilter so Calcite generates flat SQL:
    //   SELECT col1, col2 FROM "schema"."table" WHERE cond
    // rather than a subquery.
    // When aggregation is present, JdbcAggregate drives the SELECT list instead.
    if (!hasAggregation()) {
      if (getProjectedColumns() != null && !getProjectedColumns().isEmpty()) {
        // --- 7b. Simple column-ref projection path (existing behavior) ---
        RexBuilder rexBuilder = cluster.getRexBuilder();
        List<RexNode> projects = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
        for (int pi = 0; pi < getProjectedColumns().size(); pi++) {
          String colName = getProjectedColumns().get(pi).getRootSegment().getPath();
          int fullIdx = (projToFull != null) ? projToFull[pi] : pi;
          if (fullIdx < fullTableRowType.getFieldCount()) {
            org.apache.calcite.rel.type.RelDataTypeField fullField =
                fullTableRowType.getFieldList().get(fullIdx);
            projects.add(rexBuilder.makeInputRef(fullField.getType(), fullIdx));
            fieldNames.add(fullField.getName());
          }
        }
        if (!projects.isEmpty()) {
          RelDataType projRowType =
              cluster
                  .getTypeFactory()
                  .createStructType(
                      projects.stream().map(RexNode::getType).collect(Collectors.toList()),
                      fieldNames);
          root = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, root, projects, projRowType);
        }
      }
    }

    // ---- 8. Wrap with JdbcAggregate if aggregation pushed -----------------
    // groupSet and aggCalls indices are already normalized to full-table positions
    // by JdbcPushAggIntoScan at push time. No remapping needed here.
    if (hasAggregation()) {
      try {
        root =
            new JdbcRules.JdbcAggregate(
                cluster, jdbcTraitSet, root, groupSet, null, aggCalls);
      } catch (org.apache.calcite.rel.InvalidRelException e) {
        throw new IOException("Invalid aggregation for JDBC pushdown: " + e.getMessage(), e);
      }

      // Wrap JdbcAggregate with a JdbcProject that gives every output column an explicit
      // SQL alias matching the expected Dremio output column names (from overrideRowType).
      //
      // Without this, Calcite renders aggregate functions without aliases
      // (e.g. "COUNT(*)" instead of "COUNT(*) AS \"EXPR$1\""). The JDBC driver then returns
      // the column under the database's own default name ("count" in PostgreSQL, "COUNT(*)" in
      // Oracle), which JdbcRecordReader cannot match to the expected "EXPR$1" field name.
      //
      // The JdbcProject identity-projects each aggregate output field while assigning the
      // correct alias, generating "SELECT ... COUNT(*) AS \"EXPR$1\" ..." in the final SQL.
      //
      // For AVG, Calcite decomposes the aggregate into SUM/COUNT internally. If the
      // JdbcAggregate has more fields than overrideRowType (e.g. decomposed AVG → 2 cols but
      // Dremio expects 1 col), skip the rename and fall back to Dremio's own AVG handling.
      if (overrideRowType != null) {
        RelNode aggRoot = root;
        java.util.List<org.apache.calcite.rel.type.RelDataTypeField> aggFields =
            aggRoot.getRowType().getFieldList();
        java.util.List<org.apache.calcite.rel.type.RelDataTypeField> expectedFields =
            overrideRowType.getFieldList();

        if (aggFields.size() == expectedFields.size()) {
          RexBuilder rexBuilder = cluster.getRexBuilder();
          java.util.List<RexNode> renameProjects = new ArrayList<>();
          java.util.List<String> renameNames = new ArrayList<>();
          for (int i = 0; i < aggFields.size(); i++) {
            org.apache.calcite.rel.type.RelDataTypeField f = aggFields.get(i);
            renameProjects.add(rexBuilder.makeInputRef(f.getType(), i));
            renameNames.add(expectedFields.get(i).getName());
          }
          RelDataType renameRowType =
              cluster
                  .getTypeFactory()
                  .createStructType(
                      renameProjects.stream().map(RexNode::getType).collect(Collectors.toList()),
                      renameNames);
          root =
              new JdbcRules.JdbcProject(cluster, jdbcTraitSet, aggRoot, renameProjects, renameRowType);
        }
      }
    }

    // ---- 8.5. Wrap with JdbcFilter for HAVING if present ------------------
    // havingRex indices reference the aggregate output row type (group keys + agg functions).
    // No normalization needed — these are already correct for the JdbcAggregate output.
    // Placed AFTER the rename JdbcProject (which wraps JdbcAggregate) so the HAVING condition
    // indices match the rename project's output field positions.
    if (havingRex != null) {
      root = new JdbcRules.JdbcFilter(cluster, jdbcTraitSet, root, havingRex);
    }

    // ---- 9. Wrap with JdbcSort for ORDER BY and/or LIMIT ------------------
    if (collation != null || limit != null) {
      RexBuilder rexBuilder = cluster.getRexBuilder();
      RelCollation sortCollation = collation != null ? collation : RelCollations.EMPTY;
      RexNode fetchNode =
          limit != null
              ? rexBuilder.makeLiteral(
                  limit,
                  cluster.getTypeFactory().createSqlType(SqlTypeName.INTEGER),
                  true)
              : null;

      if (sortKeyExpressions != null && !sortKeyExpressions.isEmpty()
          && !sortCollation.getFieldCollations().isEmpty()) {
        // --- 9a. Function expression ORDER BY path ---
        // sortKeyExpressions contains RexNode trees (e.g. UPPER($3)) that reference the base
        // table or projected row type at push time. We must:
        //   Step 9a: Extend the current root's output with the sort key expressions
        //   Step 9b: Build JdbcSort referencing the extended column indices
        //   Step 9c: Trim back to original output width with an outer JdbcProject

        final int existingOutputWidth = root.getRowType().getFieldCount();
        final RelNode currentRoot = root;

        // Sort key expressions reference projected-column indices (matching root's output).
        // No remapping through projToFull is needed because the extended project is built
        // on top of root (which is already in projected-column space), not the full table.
        List<RexNode> remappedSortExprs = new ArrayList<>(sortKeyExpressions.size());
        for (RexNode sortExpr : sortKeyExpressions) {
          // Retype RexInputRef nodes to match root's output field types.
          RexNode retyped = sortExpr.accept(new RexShuttle() {
            @Override
            public RexNode visitInputRef(RexInputRef ref) {
              int idx = ref.getIndex();
              if (idx >= 0 && idx < existingOutputWidth) {
                return rexBuilder.makeInputRef(
                    currentRoot.getRowType().getFieldList().get(idx).getType(), idx);
              }
              return ref;
            }
          });
          remappedSortExprs.add(retyped);
        }

        // Step 9a: Build extended JdbcProject: [identity refs for existing output] + [sort key exprs]
        List<RexNode> extendedProjects = new ArrayList<>();
        List<String> extendedNames = new ArrayList<>();
        List<org.apache.calcite.rel.type.RelDataTypeField> rootFields = root.getRowType().getFieldList();
        for (int i = 0; i < existingOutputWidth; i++) {
          org.apache.calcite.rel.type.RelDataTypeField f = rootFields.get(i);
          extendedProjects.add(rexBuilder.makeInputRef(f.getType(), i));
          extendedNames.add(f.getName());
        }
        for (int k = 0; k < remappedSortExprs.size(); k++) {
          RexNode sortExpr = remappedSortExprs.get(k);
          extendedProjects.add(sortExpr);
          extendedNames.add("_sort_key_" + k);
        }
        RelDataType extendedRowType =
            cluster.getTypeFactory().createStructType(
                extendedProjects.stream().map(RexNode::getType).collect(Collectors.toList()),
                extendedNames);
        RelNode extendedRoot = new JdbcRules.JdbcProject(
            cluster, jdbcTraitSet, root, extendedProjects, extendedRowType);

        // Step 9b: Build JdbcSort with collation pointing to extended column indices.
        List<RelFieldCollation> extendedFieldCollations = new ArrayList<>();
        List<RelFieldCollation> originalFieldCollations = sortCollation.getFieldCollations();
        for (int k = 0; k < originalFieldCollations.size(); k++) {
          RelFieldCollation fc = originalFieldCollations.get(k);
          // Point to the appended sort key column: existingOutputWidth + k
          extendedFieldCollations.add(fc.withFieldIndex(existingOutputWidth + k));
        }
        RelCollation extendedCollation = RelCollations.of(extendedFieldCollations);
        RelNode sortedRoot = new JdbcRules.JdbcSort(
            cluster, jdbcTraitSet, extendedRoot, extendedCollation, null, fetchNode);

        // Step 9c: Trim back to original output width with an outer JdbcProject.
        List<RexNode> trimProjects = new ArrayList<>();
        List<String> trimNames = new ArrayList<>();
        List<org.apache.calcite.rel.type.RelDataTypeField> sortedFields =
            sortedRoot.getRowType().getFieldList();
        for (int i = 0; i < existingOutputWidth; i++) {
          org.apache.calcite.rel.type.RelDataTypeField f = sortedFields.get(i);
          trimProjects.add(rexBuilder.makeInputRef(f.getType(), i));
          trimNames.add(f.getName());
        }
        RelDataType trimRowType =
            cluster.getTypeFactory().createStructType(
                trimProjects.stream().map(RexNode::getType).collect(Collectors.toList()),
                trimNames);
        root = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, sortedRoot, trimProjects, trimRowType);
      } else {
        // --- 9b. Simple column-ref ORDER BY (existing behavior) ---
        root = new JdbcRules.JdbcSort(cluster, jdbcTraitSet, root, sortCollation, null, fetchNode);
      }
    }

    // ---- 10. Render SQL via DremioJdbcImplementor -------------------------
    JavaTypeFactory typeFactory = (JavaTypeFactory) cluster.getTypeFactory();
    DremioJdbcImplementor implementor = new DremioJdbcImplementor(dialect, typeFactory);
    SqlImplementor.Result result = implementor.implement(root);
    String sql = result.asStatement().toSqlString(dialect).getSql();

    // ---- 11. Resolve schema paths and output schema -----------------------
    List<String> tableSchemaPath = getTableMetadata().getName().getPathComponents();

    // When aggregation is active, the output schema is derived from the overrideRowType.
    if (hasAggregation() && overrideRowType != null) {
      BatchSchema aggSchema = CalciteArrowHelper.fromCalciteRowType(overrideRowType);
      List<SchemaPath> aggColumns = new ArrayList<>();
      for (org.apache.arrow.vector.types.pojo.Field f : aggSchema.getFields()) {
        aggColumns.add(SchemaPath.getSimplePath(f.getName()));
      }
      return new JdbcGroupScan(
          creator.props(this, getTableMetadata().getUser(), aggSchema),
          sql,
          aggColumns,
          aggSchema,
          getPluginId(),
          tableSchemaPath,
          Collections.emptyList());
    }

    // Schema may be null if the catalog hasn't completed a full metadata refresh yet.
    BatchSchema fullSchema = getTableMetadata().getSchema();
    if (fullSchema == null && rawPlugin instanceof JdbcStoragePlugin) {
      try {
        fullSchema =
            ((JdbcStoragePlugin) rawPlugin)
                .getSchemaFetcher()
                .getTableSchema(schemaName, tableName);
      } catch (java.sql.SQLException e) {
        throw new IOException("Failed to fetch schema for " + schemaName + "." + tableName, e);
      }
    }
    if (fullSchema == null) {
      throw new IOException(
          "Schema not available for "
              + schemaName
              + "."
              + tableName
              + ". Try refreshing the source metadata.");
    }

    return new JdbcGroupScan(
        creator.props(
            this, getTableMetadata().getUser(), fullSchema.maskAndReorder(getProjectedColumns())),
        sql,
        getProjectedColumns(),
        fullSchema,
        getPluginId(),
        tableSchemaPath,
        Collections.emptyList());
  }

  // -------------------------------------------------------------------------
  // Accessors
  // -------------------------------------------------------------------------

  public String getSchemaName() {
    return schemaName;
  }

  public String getTableName() {
    return tableName;
  }

  public RexNode getFilterRex() {
    return filterRex;
  }

  public RelCollation getCollation() {
    return collation;
  }

  public Integer getLimit() {
    return limit;
  }

  public ImmutableBitSet getGroupSet() {
    return groupSet;
  }

  public List<AggregateCall> getAggCalls() {
    return aggCalls;
  }

  /** Returns true when a sort ORDER BY has been pushed into this scan. */
  public boolean hasOrderBy() {
    return collation != null;
  }

  /** Returns true when aggregation has been pushed into this scan. */
  public boolean hasAggregation() {
    return groupSet != null;
  }

  /**
   * Returns the HAVING condition pushed down from a post-aggregation filter, or null if none.
   *
   * <p>The condition indices reference the aggregate output row type (group keys + agg functions),
   * not the full base table row type.
   */
  public RexNode getHavingRex() {
    return havingRex;
  }

  /** Returns true when a HAVING condition has been pushed into this scan. */
  public boolean hasHaving() {
    return havingRex != null;
  }

  /**
   * Returns the sort key function expressions for ORDER BY, or null if simple column-ref
   * ORDER BY is used (or no ORDER BY is pushed).
   */
  public List<RexNode> getSortKeyExpressions() {
    return sortKeyExpressions;
  }
}
