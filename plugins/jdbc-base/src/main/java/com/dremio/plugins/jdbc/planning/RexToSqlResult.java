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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of converting a Calcite {@code RexNode} to a SQL WHERE expression.
 *
 * <p>Carries the SQL string with {@code ?} placeholders and an ordered list of
 * {@link BindParam} values (one per placeholder). This replaces the previous plain
 * {@code String} return from the converter, enabling PreparedStatement parameterization.
 */
public final class RexToSqlResult {

  private final String sql;
  private final List<BindParam> params;

  /**
   * Creates a new result.
   *
   * @param sql the SQL expression fragment with ? placeholders
   * @param params ordered list of bind values, one per ? in the sql
   */
  public RexToSqlResult(String sql, List<BindParam> params) {
    this.sql = sql;
    this.params = params != null ? Collections.unmodifiableList(new ArrayList<>(params)) : Collections.emptyList();
  }

  /** Returns the SQL expression fragment (may contain ? placeholders). */
  public String getSql() {
    return sql;
  }

  /** Returns the ordered bind parameters (one per ? placeholder). */
  public List<BindParam> getParams() {
    return params;
  }

  /**
   * Creates a result for a non-parameterized SQL fragment (no bind params).
   *
   * @param sql the literal SQL expression
   * @return a result with empty params list
   */
  public static RexToSqlResult literal(String sql) {
    return new RexToSqlResult(sql, Collections.emptyList());
  }

  /**
   * Creates a result for a single {@code ?} placeholder with one bind parameter.
   *
   * @param param the bind parameter for the placeholder
   * @return a result with sql="?" and a single param
   */
  public static RexToSqlResult param(BindParam param) {
    return new RexToSqlResult("?", Collections.singletonList(param));
  }

  /**
   * Merges this result with another, concatenating the SQL strings with the given
   * separator and appending the params lists.
   *
   * @param other the result to merge with
   * @param separator the string to place between the two SQL fragments
   * @return a new merged result
   */
  public RexToSqlResult merge(RexToSqlResult other, String separator) {
    String mergedSql = this.sql + separator + other.sql;
    List<BindParam> mergedParams = new ArrayList<>(this.params.size() + other.params.size());
    mergedParams.addAll(this.params);
    mergedParams.addAll(other.params);
    return new RexToSqlResult(mergedSql, mergedParams);
  }

  @Override
  public String toString() {
    return "RexToSqlResult{sql='" + sql + "', params=" + params + "}";
  }
}
