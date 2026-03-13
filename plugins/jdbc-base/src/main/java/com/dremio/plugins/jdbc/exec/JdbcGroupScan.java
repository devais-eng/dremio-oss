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
package com.dremio.plugins.jdbc.exec;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.AbstractBase;
import com.dremio.exec.physical.base.GroupScan;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.PhysicalVisitor;
import com.dremio.exec.physical.base.SubScan;
import com.dremio.exec.planner.fragment.DistributionAffinity;
import com.dremio.exec.planner.fragment.ExecutionNodeMap;
import com.dremio.exec.proto.UserBitShared.CoreOperatorType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.schedule.SimpleCompleteWork;
import com.dremio.plugins.jdbc.planning.BindParam;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Physical scan group for single-node JDBC execution.
 *
 * <p>JDBC sources are always single-node: {@link #getMaxParallelizationWidth()} returns 1 and
 * {@link #getSplits} returns a single unit of work. The {@link #getSpecificScan} method creates a
 * {@link JdbcSubScan} that carries the SQL query, schema, and bind parameters to the executor
 * thread.
 *
 * <p>Modelled on {@code InfoSchemaGroupScan} -- the simplest GroupScan in the codebase.
 */
public class JdbcGroupScan extends AbstractBase implements GroupScan<SimpleCompleteWork> {

  private final String sql;
  private final List<SchemaPath> columns;
  private final BatchSchema schema;
  private final StoragePluginId pluginId;
  private final List<String> tableSchemaPath;
  private final List<BindParam> bindParams;

  /**
   * Creates a new group scan.
   *
   * @param props operator properties
   * @param sql the fully-formed SELECT statement
   * @param columns projected columns (null / empty = all)
   * @param schema the full table schema
   * @param pluginId identifies the owning JDBC storage plugin
   * @param tableSchemaPath path components used for catalog resolution
   * @param bindParams ordered bind parameters for PreparedStatement ? placeholders
   */
  @JsonCreator
  public JdbcGroupScan(
      @JsonProperty("props") OpProps props,
      @JsonProperty("sql") String sql,
      @JsonProperty("columns") List<SchemaPath> columns,
      @JsonProperty("schema") BatchSchema schema,
      @JsonProperty("pluginId") StoragePluginId pluginId,
      @JsonProperty("tableSchemaPath") List<String> tableSchemaPath,
      @JsonProperty("bindParams") List<BindParam> bindParams) {
    super(props);
    this.sql = sql;
    this.columns = columns;
    this.schema = schema;
    this.pluginId = pluginId;
    this.tableSchemaPath = tableSchemaPath;
    this.bindParams = bindParams != null ? bindParams : Collections.emptyList();
  }

  /**
   * Backward-compatible constructor without bindParams.
   */
  public JdbcGroupScan(
      OpProps props,
      String sql,
      List<SchemaPath> columns,
      BatchSchema schema,
      StoragePluginId pluginId,
      List<String> tableSchemaPath) {
    this(props, sql, columns, schema, pluginId, tableSchemaPath, Collections.emptyList());
  }

  public String getSql() {
    return sql;
  }

  public BatchSchema getSchema() {
    return schema;
  }

  public StoragePluginId getPluginId() {
    return pluginId;
  }

  public List<String> getTableSchemaPath() {
    return tableSchemaPath;
  }

  public List<BindParam> getBindParams() {
    return bindParams;
  }

  // -------------------------------------------------------------------------
  // GroupScan contract
  // -------------------------------------------------------------------------

  /** JDBC is single-node; prevent the planner from over-parallelizing. */
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

  @Override
  public int getOperatorType() {
    return CoreOperatorType.JDBC_SUB_SCAN_VALUE;
  }

  /** Returns a single unit of work representing the entire table scan. */
  @Override
  public Iterator<SimpleCompleteWork> getSplits(ExecutionNodeMap executionNodes) {
    return Collections.<SimpleCompleteWork>singletonList(new SimpleCompleteWork(1)).iterator();
  }

  /** Creates the {@link JdbcSubScan} that will be sent to the executor fragment. */
  @Override
  public SubScan getSpecificScan(List<SimpleCompleteWork> work) throws ExecutionSetupException {
    return new JdbcSubScan(props, schema, tableSchemaPath, sql, columns, pluginId, bindParams);
  }

  @Override
  public List<SchemaPath> getColumns() {
    return columns;
  }

  // -------------------------------------------------------------------------
  // PhysicalOperator boilerplate
  // -------------------------------------------------------------------------

  @Override
  public <T, X, E extends Throwable> T accept(PhysicalVisitor<T, X, E> physicalVisitor, X value)
      throws E {
    return physicalVisitor.visitGroupScan(this, value);
  }

  @Override
  public Iterator<PhysicalOperator> iterator() {
    return Collections.emptyIterator();
  }

  @Override
  public PhysicalOperator getNewWithChildren(List<PhysicalOperator> children)
      throws ExecutionSetupException {
    return this;
  }

  @Override
  public boolean equals(final Object other) {
    if (!(other instanceof JdbcGroupScan)) {
      return false;
    }
    JdbcGroupScan o = (JdbcGroupScan) other;
    return Objects.equal(sql, o.sql) && Objects.equal(pluginId, o.pluginId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(sql, pluginId);
  }

  @Override
  public String toString() {
    return "JdbcGroupScan[sql=" + sql + "]";
  }
}
