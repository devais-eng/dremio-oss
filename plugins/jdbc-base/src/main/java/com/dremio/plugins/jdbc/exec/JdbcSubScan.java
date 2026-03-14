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

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.AbstractSubScan;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.proto.UserBitShared.CoreOperatorType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.plugins.jdbc.planning.BindParam;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.Collections;
import java.util.List;

/**
 * Serializable sub-scan that carries all the information needed by {@link JdbcRecordReader} to
 * execute a single JDBC query fragment.
 *
 * <p>The {@code sql} field holds the fully constructed SELECT statement with {@code ?}
 * placeholders. The {@code bindParams} field carries the ordered bind parameter values that
 * correspond to the placeholders. These are set on the {@link java.sql.PreparedStatement} before
 * execution.
 *
 * <p>JSON serialization follows the same {@code @JsonTypeName} / {@code @JsonCreator} pattern used
 * by {@code InfoSchemaSubScan} and {@code ElasticsearchSubScan}.
 */
@JsonTypeName("jdbc-sub-scan")
public class JdbcSubScan extends AbstractSubScan {

  private final String sql;
  private final List<SchemaPath> columns;
  private final StoragePluginId pluginId;
  private final List<BindParam> bindParams;

  /**
   * JSON deserialization constructor.
   *
   * @param props operator properties (memory, parallelism metadata)
   * @param fullSchema the full table schema (Arrow BatchSchema)
   * @param tableSchemaPath the table path used for catalog look-ups
   * @param sql the SQL query to execute (may contain ? placeholders)
   * @param columns projected columns, or null / star for all columns
   * @param pluginId reference to the owning JDBC storage plugin
   * @param bindParams ordered bind parameters for PreparedStatement ? placeholders
   */
  @JsonCreator
  public JdbcSubScan(
      @JsonProperty("props") OpProps props,
      @JsonProperty("fullSchema") BatchSchema fullSchema,
      @JsonProperty("tableSchemaPath") List<String> tableSchemaPath,
      @JsonProperty("sql") String sql,
      @JsonProperty("columns") List<SchemaPath> columns,
      @JsonProperty("pluginId") StoragePluginId pluginId,
      @JsonProperty("bindParams") List<BindParam> bindParams) {
    super(props, fullSchema, tableSchemaPath);
    this.sql = sql;
    this.columns = columns != null ? columns : Collections.emptyList();
    this.pluginId = pluginId;
    this.bindParams = bindParams != null ? bindParams : Collections.emptyList();
  }

  /** Backward-compatible constructor without bindParams. */
  public JdbcSubScan(
      OpProps props,
      BatchSchema fullSchema,
      List<String> tableSchemaPath,
      String sql,
      List<SchemaPath> columns,
      StoragePluginId pluginId) {
    this(props, fullSchema, tableSchemaPath, sql, columns, pluginId, Collections.emptyList());
  }

  /** The SQL SELECT statement that this sub-scan should execute. */
  public String getSql() {
    return sql;
  }

  @Override
  public List<SchemaPath> getColumns() {
    return columns;
  }

  /** The storage plugin that owns the pool needed to execute {@link #getSql()}. */
  public StoragePluginId getPluginId() {
    return pluginId;
  }

  /** Returns the ordered bind parameters for PreparedStatement ? placeholders. */
  public List<BindParam> getBindParams() {
    return bindParams;
  }

  @Override
  public int getOperatorType() {
    return CoreOperatorType.JDBC_SUB_SCAN_VALUE;
  }

  @Override
  @JsonProperty("fullSchema")
  public BatchSchema getFullSchema() {
    return super.getFullSchema();
  }
}
