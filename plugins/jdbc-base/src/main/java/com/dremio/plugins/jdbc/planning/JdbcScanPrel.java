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
import com.dremio.exec.store.TableMetadata;
import com.dremio.plugins.jdbc.exec.JdbcGroupScan;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.hint.RelHint;

/**
 * Physical scan node for JDBC-backed tables in the Dremio planner.
 *
 * <p>Carries the pushdown state accumulated during the PHYSICAL planning phase:
 * <ul>
 *   <li>{@link #whereClause} — SQL WHERE predicate pushed down from a FilterPrel</li>
 *   <li>{@link #limit} — row limit pushed down from a LimitPrel</li>
 *   <li>projected columns — columns to project (inherited from ScanPrelBase)</li>
 * </ul>
 *
 * <p>The {@link #getPhysicalOperator} method assembles the final SQL string via
 * {@link SqlBuilder} and hands it to {@link JdbcGroupScan} for execution.
 */
public class JdbcScanPrel extends ScanPrelBase {

  private final String schemaName;
  private final String tableName;
  private final String whereClause;
  private final Integer limit;
  private final SqlBuilder sqlBuilder;

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
    this.limit = limit;
    this.sqlBuilder = new SqlBuilder();
  }

  // -------------------------------------------------------------------------
  // Clone helpers used by pushdown rules
  // -------------------------------------------------------------------------

  /**
   * Returns a new JdbcScanPrel with updated projected columns, preserving filter and limit.
   */
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
        limit);
  }

  /**
   * Returns a new JdbcScanPrel with an updated WHERE clause, preserving projection and limit.
   */
  public JdbcScanPrel cloneWithFilter(String newWhereClause) {
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
        limit);
  }

  /**
   * Returns a new JdbcScanPrel with an updated LIMIT, preserving filter and projection.
   */
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
        newLimit);
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
  public DistributionAffinity getDistributionAffinity() {
    return DistributionAffinity.SOFT;
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
        limit);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    super.explainTerms(pw);
    pw.itemIf("schema", schemaName, schemaName != null);
    pw.itemIf("table", tableName, tableName != null);
    pw.itemIf("where", whereClause, whereClause != null);
    pw.itemIf("limit", limit, limit != null);
    return pw;
  }

  /**
   * Assembles the final SQL query via {@link SqlBuilder} and creates the
   * {@link JdbcGroupScan} physical operator for execution.
   */
  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    String sql = sqlBuilder.buildSql(schemaName, tableName, getProjectedColumns(), whereClause, limit);
    List<String> tableSchemaPath = getTableMetadata().getName().getPathComponents();
    return new JdbcGroupScan(
        creator.props(
            this,
            getTableMetadata().getUser(),
            getTableMetadata().getSchema().maskAndReorder(getProjectedColumns())),
        sql,
        getProjectedColumns(),
        getTableMetadata().getSchema(),
        getPluginId(),
        tableSchemaPath);
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

  public Integer getLimit() {
    return limit;
  }
}
