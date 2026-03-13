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
import com.google.common.base.Preconditions;
import java.util.List;

/**
 * Builder-pattern DTO encapsulating all pushdown components for {@link SqlBuilder}.
 *
 * <p>Centralizes the growing set of pushdown parameters (filter, projection, limit,
 * order by, group by, aggregation select expressions) into a single object so that
 * {@link SqlBuilder#buildSql(SqlBuildRequest)} has a stable signature as new pushdown
 * types are added.
 *
 * <p>Fields for ORDER BY, GROUP BY, and selectExprs are nullable placeholders used
 * by Plans 02 and 03 of the advanced pushdown phase.
 */
public final class SqlBuildRequest {

  private final String schemaName;
  private final String tableName;
  private final List<SchemaPath> projectedColumns;
  private final List<String> selectExprs;
  private final String whereClause;
  private final List<BindParam> bindParams;
  private final String groupByClause;
  private final String orderByClause;
  private final Integer limit;

  private SqlBuildRequest(Builder builder) {
    this.schemaName = Preconditions.checkNotNull(builder.schemaName, "schemaName is required");
    this.tableName = Preconditions.checkNotNull(builder.tableName, "tableName is required");
    this.projectedColumns = builder.projectedColumns;
    this.selectExprs = builder.selectExprs;
    this.whereClause = builder.whereClause;
    this.bindParams = builder.bindParams;
    this.groupByClause = builder.groupByClause;
    this.orderByClause = builder.orderByClause;
    this.limit = builder.limit;
  }

  public String getSchemaName() {
    return schemaName;
  }

  public String getTableName() {
    return tableName;
  }

  public List<SchemaPath> getProjectedColumns() {
    return projectedColumns;
  }

  /** Returns aggregation select expressions, or null if not an aggregation query. */
  public List<String> getSelectExprs() {
    return selectExprs;
  }

  public String getWhereClause() {
    return whereClause;
  }

  public List<BindParam> getBindParams() {
    return bindParams;
  }

  public String getGroupByClause() {
    return groupByClause;
  }

  public String getOrderByClause() {
    return orderByClause;
  }

  public Integer getLimit() {
    return limit;
  }

  /** Creates a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link SqlBuildRequest}. */
  public static final class Builder {
    private String schemaName;
    private String tableName;
    private List<SchemaPath> projectedColumns;
    private List<String> selectExprs;
    private String whereClause;
    private List<BindParam> bindParams;
    private String groupByClause;
    private String orderByClause;
    private Integer limit;

    private Builder() {}

    public Builder schema(String schemaName) {
      this.schemaName = schemaName;
      return this;
    }

    public Builder table(String tableName) {
      this.tableName = tableName;
      return this;
    }

    public Builder projectedColumns(List<SchemaPath> projectedColumns) {
      this.projectedColumns = projectedColumns;
      return this;
    }

    public Builder selectExprs(List<String> selectExprs) {
      this.selectExprs = selectExprs;
      return this;
    }

    public Builder where(String whereClause) {
      this.whereClause = whereClause;
      return this;
    }

    public Builder bindParams(List<BindParam> bindParams) {
      this.bindParams = bindParams;
      return this;
    }

    public Builder groupBy(String groupByClause) {
      this.groupByClause = groupByClause;
      return this;
    }

    public Builder orderBy(String orderByClause) {
      this.orderByClause = orderByClause;
      return this;
    }

    public Builder limit(Integer limit) {
      this.limit = limit;
      return this;
    }

    public SqlBuildRequest build() {
      return new SqlBuildRequest(this);
    }
  }
}
