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
import java.util.Collections;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

/**
 * Physical scan node for JDBC-backed tables in the Dremio planner.
 *
 * <p>Carries the pushdown state accumulated during the PHYSICAL planning phase:
 *
 * <ul>
 *   <li>{@link #whereClause} -- SQL WHERE predicate pushed down from a FilterPrel
 *   <li>{@link #bindParams} -- ordered bind parameters for WHERE clause ? placeholders
 *   <li>{@link #selectExprs} -- aggregation SELECT expressions (e.g. COUNT(*), SUM("col"))
 *   <li>{@link #groupByClause} -- GROUP BY expression pushed down from an AggregatePrel
 *   <li>{@link #orderByClause} -- ORDER BY expression pushed down from a SortPrel
 *   <li>{@link #limit} -- row limit pushed down from a LimitPrel
 *   <li>projected columns -- columns to project (inherited from ScanPrelBase)
 * </ul>
 *
 * <p>The {@link #getPhysicalOperator} method assembles the final SQL string via {@link SqlBuilder}
 * and hands it to {@link JdbcGroupScan} for execution.
 */
public class JdbcScanPrel extends ScanPrelBase {

  private final String schemaName;
  private final String tableName;
  private final String whereClause;
  private final List<BindParam> bindParams;
  private final String orderByClause;
  private final Integer limit;
  private final List<String> selectExprs;
  private final String groupByClause;
  private final RelDataType overrideRowType;

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
      String whereClause,
      Integer limit) {
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
        whereClause,
        Collections.emptyList(),
        null,
        limit);
  }

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
      String whereClause,
      List<BindParam> bindParams,
      Integer limit) {
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
        whereClause,
        bindParams,
        null,
        limit);
  }

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
      String whereClause,
      List<BindParam> bindParams,
      String orderByClause,
      Integer limit) {
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
        whereClause,
        bindParams,
        orderByClause,
        limit,
        null,
        null,
        null);
  }

  /**
   * Full constructor with all pushdown state including aggregation fields.
   *
   * @param selectExprs aggregation SELECT expressions (e.g., COUNT(*), SUM("col")); null when no
   *     aggregation is pushed
   * @param groupByClause GROUP BY expression (e.g., "name", "category"); null when no grouping
   * @param overrideRowType when non-null, overrides the base-class derived rowType to reflect the
   *     aggregated output schema
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
      String whereClause,
      List<BindParam> bindParams,
      String orderByClause,
      Integer limit,
      List<String> selectExprs,
      String groupByClause,
      RelDataType overrideRowType) {
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
    this.whereClause = whereClause;
    this.bindParams =
        ImmutableList.copyOf(bindParams != null ? bindParams : Collections.emptyList());
    this.orderByClause = orderByClause;
    this.limit = limit;
    this.selectExprs = selectExprs != null ? ImmutableList.copyOf(selectExprs) : null;
    this.groupByClause = groupByClause;
    this.overrideRowType = overrideRowType;
    // Force the cached rowType in AbstractRelNode so Volcano sees the aggregated schema.
    // deriveRowType() is called lazily, but if the parent caches it during construction
    // (before overrideRowType is set), the wrong type gets registered.
    if (overrideRowType != null) {
      this.rowType = overrideRowType;
    }
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
        whereClause,
        bindParams,
        orderByClause,
        limit,
        selectExprs,
        groupByClause,
        overrideRowType);
  }

  /**
   * Returns a new JdbcScanPrel with an updated WHERE clause, preserving projection and limit.
   * Backward-compatible signature (no bind params).
   */
  public JdbcScanPrel cloneWithFilter(String newWhereClause) {
    return cloneWithFilter(newWhereClause, Collections.emptyList());
  }

  /**
   * Returns a new JdbcScanPrel with an updated WHERE clause and bind parameters, preserving all
   * other pushdown state.
   */
  public JdbcScanPrel cloneWithFilter(String newWhereClause, List<BindParam> newBindParams) {
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
        newWhereClause,
        newBindParams,
        orderByClause,
        limit,
        selectExprs,
        groupByClause,
        overrideRowType);
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
        whereClause,
        bindParams,
        orderByClause,
        newLimit,
        selectExprs,
        groupByClause,
        overrideRowType);
  }

  /** Returns a new JdbcScanPrel with an ORDER BY clause, preserving all other pushdown state. */
  public JdbcScanPrel cloneWithOrderBy(String newOrderBy) {
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
        whereClause,
        bindParams,
        newOrderBy,
        limit,
        selectExprs,
        groupByClause,
        overrideRowType);
  }

  /**
   * Returns a new JdbcScanPrel with aggregation pushdown state: custom SELECT expressions
   * (aggregate functions), a GROUP BY clause, and an overridden output rowType reflecting the
   * aggregated schema.
   *
   * <p>When aggregation is active, projectedColumns are set to null because the SELECT list is
   * driven entirely by {@code newSelectExprs}.
   *
   * @param newSelectExprs aggregate SELECT expressions (e.g., {@code "name"}, {@code COUNT(*)})
   * @param newGroupByClause GROUP BY expression (e.g., {@code "name", "category"})
   * @param newRowType the aggregated output row type matching the AggregatePrel's output
   * @return a new scan with aggregation state
   */
  public JdbcScanPrel cloneWithAggregation(
      List<String> newSelectExprs, String newGroupByClause, RelDataType newRowType) {
    return new JdbcScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        null,
        getCostAdjustmentFactor(),
        getHintsAsList(),
        getRuntimeFilters(),
        schemaName,
        tableName,
        whereClause,
        bindParams,
        orderByClause,
        limit,
        newSelectExprs,
        newGroupByClause,
        newRowType);
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
    return whereClause != null;
  }

  @Override
  public double getFilterReduction() {
    return whereClause == null ? super.getFilterReduction() : 0.15d;
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
        whereClause,
        bindParams,
        orderByClause,
        limit,
        selectExprs,
        groupByClause,
        overrideRowType);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    super.explainTerms(pw);
    pw.itemIf("schema", schemaName, schemaName != null);
    pw.itemIf("table", tableName, tableName != null);
    pw.itemIf("where", whereClause, whereClause != null);
    pw.itemIf("bindParams", bindParams.size(), !bindParams.isEmpty());
    pw.itemIf("selectExprs", selectExprs, selectExprs != null);
    pw.itemIf("groupBy", groupByClause, groupByClause != null);
    pw.itemIf("orderBy", orderByClause, orderByClause != null);
    pw.itemIf("limit", limit, limit != null);
    return pw;
  }

  /**
   * Assembles the final SQL query via {@link SqlBuilder} and creates the {@link JdbcGroupScan}
   * physical operator for execution.
   *
   * <p>Resolves the {@link JdbcStoragePlugin} for the source at plan time to obtain the correct
   * {@link SqlBuilder} dialect (e.g., Oracle uses FETCH FIRST instead of LIMIT). Falls back to a
   * base {@code SqlBuilder} if the plugin is unavailable.
   */
  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    StoragePlugin rawPlugin =
        creator.getContext().getCatalogService().getSource(getPluginId().getName());
    SqlBuilder sb =
        (rawPlugin instanceof JdbcStoragePlugin)
            ? ((JdbcStoragePlugin) rawPlugin).createSqlBuilder()
            : new SqlBuilder();

    SqlBuildRequest.Builder requestBuilder =
        SqlBuildRequest.builder()
            .schema(schemaName)
            .table(tableName)
            .projectedColumns(getProjectedColumns())
            .where(whereClause)
            .bindParams(bindParams)
            .selectExprs(selectExprs)
            .groupBy(groupByClause)
            .orderBy(orderByClause)
            .limit(limit);
    String sql = sb.buildSql(requestBuilder.build());

    List<String> tableSchemaPath = getTableMetadata().getName().getPathComponents();

    // When aggregation is active, the output schema is derived from the overrideRowType
    // (the aggregated schema) rather than the original table schema.
    if (hasAggregation() && overrideRowType != null) {
      BatchSchema aggSchema = CalciteArrowHelper.fromCalciteRowType(overrideRowType);
      // Build projected columns matching the aggregated field names so that
      // ScanOperator.setup() pre-materializes vectors for the correct columns.
      // Without this, ScanOperator sees an empty projection, materializes nothing,
      // and JdbcRecordReader.setup() triggers a SCHEMA_CHANGE when it adds the
      // aggregated output fields via OutputMutator.addField().
      List<SchemaPath> aggColumns = new java.util.ArrayList<>();
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
          bindParams);
    }

    // Schema may be null if the catalog hasn't completed a full metadata refresh yet.
    // Fall back to fetching directly from the plugin's schema fetcher.
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
        bindParams);
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

  public String getWhereClause() {
    return whereClause;
  }

  public List<BindParam> getBindParams() {
    return bindParams;
  }

  public String getOrderByClause() {
    return orderByClause;
  }

  public boolean hasOrderBy() {
    return orderByClause != null;
  }

  public Integer getLimit() {
    return limit;
  }

  public List<String> getSelectExprs() {
    return selectExprs;
  }

  public String getGroupByClause() {
    return groupByClause;
  }

  /** Returns true when aggregation has been pushed into this scan. */
  public boolean hasAggregation() {
    return selectExprs != null;
  }
}
