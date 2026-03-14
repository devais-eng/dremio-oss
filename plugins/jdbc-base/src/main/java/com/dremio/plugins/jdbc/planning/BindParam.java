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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Immutable bind parameter for PreparedStatement parameterization in JDBC pushdown queries.
 *
 * <p>Each {@code BindParam} represents a single {@code ?} placeholder in the generated SQL. The
 * {@link #value} carries the Java-boxed literal (Integer, Long, Double, String, BigDecimal,
 * Boolean, java.sql.Date/Time/Timestamp, or null), and {@link #typeName} provides the Calcite type
 * tag that {@link com.dremio.plugins.jdbc.reader.JdbcRecordReader} uses to dispatch to the correct
 * {@code PreparedStatement.setXxx()} method.
 *
 * <p>JSON-serializable via Jackson for transport through the operator chain (JdbcGroupScan ->
 * JdbcSubScan).
 */
public final class BindParam {

  private final Object value;
  private final SqlTypeName typeName;

  /**
   * Creates a new bind parameter.
   *
   * @param value the literal value (boxed Java type or null)
   * @param typeName the Calcite SQL type for dispatch to stmt.setXxx()
   */
  @JsonCreator
  public BindParam(
      @JsonProperty("value") Object value, @JsonProperty("typeName") SqlTypeName typeName) {
    this.value = value;
    this.typeName = typeName;
  }

  /** Returns the literal value (may be null). */
  @JsonProperty("value")
  public Object getValue() {
    return value;
  }

  /** Returns the Calcite SQL type name used for PreparedStatement dispatch. */
  @JsonProperty("typeName")
  public SqlTypeName getTypeName() {
    return typeName;
  }

  @Override
  public String toString() {
    return "BindParam{value=" + value + ", typeName=" + typeName + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BindParam)) {
      return false;
    }
    BindParam other = (BindParam) o;
    return java.util.Objects.equals(value, other.value) && typeName == other.typeName;
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(value, typeName);
  }
}
