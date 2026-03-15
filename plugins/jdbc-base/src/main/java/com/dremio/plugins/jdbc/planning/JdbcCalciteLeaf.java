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

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.calcite.adapter.jdbc.JdbcImplementor;
import org.apache.calcite.adapter.jdbc.JdbcRel;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.parser.SqlParserPos;

/**
 * A leaf {@link JdbcRel} node that produces the {@code FROM} clause when a {@link JdbcImplementor}
 * renders a JDBC subtree to SQL.
 *
 * <p>This node represents a single physical table in the JDBC source. It extends {@link
 * AbstractRelNode} rather than {@code TableScan} to avoid the {@code RelOptTable} requirement that
 * {@code TableScan} imposes. The table reference is expressed directly as a two-part {@link
 * SqlIdentifier} ({@code "schemaName"."tableName"}), giving full control over quoting.
 *
 * <p>Used by {@link JdbcScanPrel#getPhysicalOperator} to serve as the base leaf in a Calcite JDBC
 * convention subtree ({@code JdbcCalciteLeaf -> JdbcFilter -> JdbcProject -> JdbcAggregate ->
 * JdbcSort}) that {@link DremioJdbcImplementor} renders to a SQL string via {@link
 * SqlImplementor.Result#asStatement()}.
 */
public class JdbcCalciteLeaf extends AbstractRelNode implements JdbcRel {

  private final String schemaName;
  private final String tableName;

  /**
   * Creates a new leaf node for the given table.
   *
   * @param cluster the planner cluster (provides type factory, RexBuilder, etc.)
   * @param traitSet the trait set for this node; must include a {@code JdbcConvention} trait
   * @param rowType the full row type of the table (all columns)
   * @param schemaName the remote schema (or catalogue) name
   * @param tableName the remote table name
   */
  public JdbcCalciteLeaf(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelDataType rowType,
      String schemaName,
      String tableName) {
    super(cluster, traitSet);
    this.schemaName = schemaName;
    this.tableName = tableName;
    // Eagerly set rowType in AbstractRelNode so the implementor sees the correct schema
    // without waiting for the lazy deriveRowType() call.
    this.rowType = rowType;
  }

  // -------------------------------------------------------------------------
  // JdbcRel contract
  // -------------------------------------------------------------------------

  /**
   * Produces the {@code FROM "schema"."table"} clause for this node.
   *
   * <p>{@link DremioJdbcImplementor#visit(JdbcCalciteLeaf)} delegates here, which means the
   * reflection dispatch in {@link org.apache.calcite.rel.rel2sql.RelToSqlConverter#dispatch} finds
   * the correct {@code visit} override in the implementor.
   *
   * @param implementor the JDBC implementor rendering the subtree
   * @return a {@link SqlImplementor.Result} anchored at the {@code FROM} clause for this table
   */
  @Override
  public SqlImplementor.Result implement(JdbcImplementor implementor) {
    SqlIdentifier tableRef =
        new SqlIdentifier(ImmutableList.of(schemaName, tableName), SqlParserPos.ZERO);
    return implementor.result(
        tableRef, ImmutableList.of(SqlImplementor.Clause.FROM), this, null);
  }

  // -------------------------------------------------------------------------
  // RelNode overrides
  // -------------------------------------------------------------------------

  @Override
  protected RelDataType deriveRowType() {
    // rowType was set eagerly in the constructor; return it directly.
    return rowType;
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert inputs == null || inputs.isEmpty();
    return new JdbcCalciteLeaf(getCluster(), traitSet, rowType, schemaName, tableName);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("schema", schemaName)
        .item("table", tableName);
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
}
