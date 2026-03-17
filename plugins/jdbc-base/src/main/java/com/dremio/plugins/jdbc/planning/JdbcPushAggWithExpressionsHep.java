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

import com.dremio.exec.planner.physical.AggregatePrel;
import com.dremio.exec.planner.physical.ProjectPrel;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * HEP-phase pushdown rule that absorbs an {@link AggregatePrel} whose GROUP BY keys or aggregate
 * operands are function expressions (e.g. {@code GROUP BY EXTRACT(YEAR FROM hire_date)} or
 * {@code SUM(salary * 1.1)}) when the topology is
 * {@code AggregatePrel(ProjectPrel(JdbcScanPrel))}.
 *
 * <p>Complements {@link JdbcPushAggIntoScan}, which handles the simple
 * {@code AggregatePrel(JdbcScanPrel)} topology where GROUP BY keys are plain column references.
 * This rule fires when:
 * <ul>
 *   <li>The aggregate input contains a {@link ProjectPrel} above a {@link JdbcScanPrel}.</li>
 *   <li>At least one GROUP BY key expression or aggregate operand expression in the ProjectPrel is
 *       a non-trivial function call (not a plain {@link RexInputRef}).</li>
 *   <li>All GROUP BY key expressions and aggregate operand expressions pass the
 *       {@link PushdownFunctionRegistry} whitelist check.</li>
 *   <li>The scan does not already have aggregation pushed.</li>
 *   <li>The aggregate is a single-phase {@code PHASE_1of1} aggregate.</li>
 *   <li>The aggregate uses only supported aggregate functions (COUNT, SUM, SUM0, MIN, MAX, AVG).</li>
 * </ul>
 *
 * <p>On match, the GROUP BY key expressions and aggregate operand expressions are stored on the
 * scan via {@link JdbcScanPrel#cloneWithAggregationExpressions}. At
 * {@link JdbcScanPrel#getPhysicalOperator} time, step 8 uses the extend/aggregate/trim pattern:
 * <ol>
 *   <li>Extends the JdbcProject output with the function expression columns.</li>
 *   <li>Builds JdbcAggregate referencing the extended column indices.</li>
 *   <li>Trims back to the original aggregation output width with an outer JdbcProject.</li>
 * </ol>
 *
 * <p>Also handles bare aggregates (no GROUP BY, empty groupSet) by allowing the aggregate to push
 * even when there are no group key expressions — the aggregate operand expressions alone are
 * sufficient to trigger this rule.
 *
 * <p>Registered in {@code PHYSICAL_HEP} via {@link JdbcRulesFactory}.
 */
public final class JdbcPushAggWithExpressionsHep extends RelOptRule {

  private final PushdownFunctionRegistry registry;

  public JdbcPushAggWithExpressionsHep(PushdownFunctionRegistry registry) {
    super(RelOptRule.operand(AggregatePrel.class, RelOptRule.any()),
        "JdbcPushAggWithExpressionsHep");
    this.registry = registry;
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    AggregatePrel agg = call.rel(0);

    // Only push single-phase aggregates. Two-phase partial/final aggregates are
    // incorrect to push to a single JDBC source.
    if (agg.getOperatorPhase() != AggregatePrel.OperatorPhase.PHASE_1of1) {
      return false;
    }

    // Find the ProjectPrel(JdbcScanPrel) pattern below this aggregate.
    RelNode[] pair = JdbcPushSortWithExpressionsHep.findProjectAndScan(agg.getInput(), 2);
    if (pair == null) {
      return false;
    }
    JdbcScanPrel scan = (JdbcScanPrel) pair[1];

    // Do not push a second aggregation into a scan that already has one.
    if (scan.hasAggregation()) {
      return false;
    }

    // Allow COUNT(DISTINCT col) but reject DISTINCT for other aggregate kinds.
    // Also reject any aggregate with a FILTER (WHERE ...) clause (filterArg >= 0).
    for (AggregateCall aggCall : agg.getAggCallList()) {
      if (aggCall.filterArg >= 0) {
        return false; // FILTER (WHERE ...) on aggregate — not supported
      }
      if (aggCall.isDistinct()) {
        // Only COUNT(DISTINCT) is supported; SUM(DISTINCT), AVG(DISTINCT) etc. are not.
        if (aggCall.getAggregation().getKind() != SqlKind.COUNT) {
          return false;
        }
      }
    }

    // Reject unsupported aggregate functions.
    for (AggregateCall aggCall : agg.getAggCallList()) {
      SqlKind kind = aggCall.getAggregation().getKind();
      switch (kind) {
        case COUNT:
        case SUM:
        case SUM0:
        case MIN:
        case MAX:
        case AVG:
          break;
        default:
          return false;
      }
    }

    // Inspect the ProjectPrel expressions to check for non-trivial expressions at
    // GROUP BY key positions and aggregate operand positions.
    ProjectPrel projectPrel = (ProjectPrel) pair[0];
    List<RexNode> projExprs = projectPrel.getProjects();
    ImmutableBitSet groupSet = agg.getGroupSet();
    boolean hasFunctionExpression = false;

    // Check GROUP BY key positions.
    for (int bit : groupSet) {
      if (bit < 0 || bit >= projExprs.size()) {
        return false;
      }
      RexNode expr = projExprs.get(bit);
      if (!(expr instanceof RexInputRef)) {
        hasFunctionExpression = true;
      }
      if (!registry.isExpressionPushable(expr)) {
        return false;
      }
    }

    // Check aggregate operand positions.
    for (AggregateCall aggCall : agg.getAggCallList()) {
      for (int argIdx : aggCall.getArgList()) {
        if (argIdx < 0 || argIdx >= projExprs.size()) {
          return false;
        }
        RexNode expr = projExprs.get(argIdx);
        if (!(expr instanceof RexInputRef)) {
          hasFunctionExpression = true;
        }
        if (!registry.isExpressionPushable(expr)) {
          return false;
        }
      }
    }

    // Only fire when at least one function expression exists; otherwise the existing
    // Volcano-phase JdbcPushAggIntoScan.INSTANCE handles the simple column-ref case.
    return hasFunctionExpression;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    AggregatePrel agg = call.rel(0);

    RelNode[] pair = JdbcPushSortWithExpressionsHep.findProjectAndScan(agg.getInput(), 2);
    if (pair == null) {
      return;
    }
    ProjectPrel projectPrel = (ProjectPrel) pair[0];
    JdbcScanPrel scan = (JdbcScanPrel) pair[1];

    List<RexNode> projExprs = projectPrel.getProjects();
    ImmutableBitSet groupSet = agg.getGroupSet();
    RelDataType newRowType = agg.getRowType();

    // Separate GROUP BY key expressions into:
    //   - Simple column refs: record the full-table index (RexInputRef.getIndex())
    //   - Function expressions: collect as groupKeyExpressions
    // Build a new groupSet remapped to full-table indices (extended columns for functions).
    final int fullTableFieldCount = scan.getRowType().getFieldCount();
    // extended index counter starts after all full-table columns
    int extendedIdx = fullTableFieldCount;

    ImmutableBitSet.Builder newGroupSetBuilder = ImmutableBitSet.builder();
    List<RexNode> groupKeyExpressions = new ArrayList<>();

    for (int bit : groupSet) {
      RexNode expr = projExprs.get(bit);
      if (expr instanceof RexInputRef) {
        // Simple column ref: use the underlying scan column index directly.
        newGroupSetBuilder.set(((RexInputRef) expr).getIndex());
      } else {
        // Function expression: assign to extended column.
        newGroupSetBuilder.set(extendedIdx++);
        groupKeyExpressions.add(expr);
      }
    }
    ImmutableBitSet newGroupSet = newGroupSetBuilder.build();

    // Remap aggregate call argument indices.
    // Function expression operands get assigned to extended columns.
    List<AggregateCall> newAggCalls = new ArrayList<>(agg.getAggCallList().size());
    List<RexNode> aggOperandExpressions = new ArrayList<>();

    for (AggregateCall aggCall : agg.getAggCallList()) {
      List<Integer> newArgs = new ArrayList<>(aggCall.getArgList().size());
      for (int argIdx : aggCall.getArgList()) {
        RexNode expr = projExprs.get(argIdx);
        if (expr instanceof RexInputRef) {
          newArgs.add(((RexInputRef) expr).getIndex());
        } else {
          // Function expression: assign to next extended column.
          aggOperandExpressions.add(expr);
          newArgs.add(extendedIdx++);
        }
      }
      newAggCalls.add(aggCall.copy(newArgs, aggCall.filterArg));
    }

    // Store on the scan. getPhysicalOperator() will build the extend/aggregate/trim subtree.
    JdbcScanPrel newScan = scan.cloneWithAggregationExpressions(
        newGroupSet,
        newAggCalls,
        newRowType,
        groupKeyExpressions.isEmpty() ? null : groupKeyExpressions,
        aggOperandExpressions.isEmpty() ? null : aggOperandExpressions);

    // The replacement must have the same row type as the AggregatePrel being replaced.
    // Wrap in a ProjectPrel that preserves the agg output shape.
    ProjectPrel wrapper = ProjectPrel.create(
        newScan.getCluster(),
        newScan.getTraitSet(),
        newScan,
        projectPrel.getProjects(),
        projectPrel.getRowType());
    call.transformTo(wrapper);
  }
}
