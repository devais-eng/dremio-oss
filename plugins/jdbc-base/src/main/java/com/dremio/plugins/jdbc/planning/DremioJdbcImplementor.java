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

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.adapter.jdbc.JdbcImplementor;
import org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.calcite.sql.SqlDialect;

/**
 * A Dremio-specific subclass of {@link JdbcImplementor} that handles our custom {@link
 * JdbcCalciteLeaf} node.
 *
 * <p>{@link JdbcImplementor} dispatches via {@link
 * org.apache.calcite.rel.rel2sql.RelToSqlConverter#dispatch}, which uses reflection to find a
 * {@code visit(ConcreteType)} method matching the node's runtime type. The stock {@link
 * JdbcImplementor} only defines {@code visit(JdbcTableScan)} for the leaf case; our leaf is a
 * {@link JdbcCalciteLeaf} (extends {@link org.apache.calcite.rel.AbstractRelNode}), so we must add
 * an explicit {@code visit(JdbcCalciteLeaf)} override here.
 *
 * <p>This class is used in both {@link JdbcScanPrel#getPhysicalOperator} (single-table pipeline)
 * and (in Plan 36-02) {@code JdbcJoinScanPrel#getPhysicalOperator} (join pipeline). Using a single
 * subclass ensures consistent dispatch for all Dremio-specific JdbcRel leaf nodes.
 */
public class DremioJdbcImplementor extends JdbcImplementor {

  /**
   * Creates a new implementor for the given SQL dialect and type factory.
   *
   * @param dialect the SQL dialect controlling identifier quoting and dialect-specific syntax
   * @param typeFactory the Java type factory from the planner cluster
   */
  public DremioJdbcImplementor(SqlDialect dialect, JavaTypeFactory typeFactory) {
    super(dialect, typeFactory);
  }

  /**
   * Handles the {@link JdbcCalciteLeaf} node by delegating to its own {@link
   * JdbcCalciteLeaf#implement(JdbcImplementor)} method, which produces the {@code FROM
   * "schema"."table"} clause.
   *
   * @param leaf the leaf node representing a single JDBC table
   * @return the {@code FROM} clause result
   */
  public SqlImplementor.Result visit(JdbcCalciteLeaf leaf) {
    return leaf.implement(this);
  }
}
