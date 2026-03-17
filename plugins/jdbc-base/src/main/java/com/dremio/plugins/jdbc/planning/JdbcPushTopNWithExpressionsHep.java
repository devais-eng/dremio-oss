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

import com.dremio.exec.planner.physical.ProjectPrel;
import com.dremio.exec.planner.physical.TopNPrel;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;

/**
 * HEP-phase pushdown rule that absorbs a {@link TopNPrel} whose sort keys are function expressions
 * (e.g. {@code ORDER BY UPPER(name) LIMIT K}) when the topology is
 * {@code TopNPrel(ProjectPrel(JdbcScanPrel))}.
 *
 * <p>Background: Dremio's {@code PushLimitToTopN} rule fires during the PHYSICAL (Volcano) phase
 * and converts {@code LimitPrel(SingleMergeExchangePrel(SortPrel))} into a {@link TopNPrel}. This
 * means that by the time {@code PHYSICAL_HEP} runs, the plan tree for
 * {@code ORDER BY expression LIMIT K} looks like:
 * <pre>
 *   TopNPrel(ProjectPrel(ExchangePrel(JdbcScanPrel)))
 * </pre>
 * rather than {@code SortPrel(ProjectPrel(...))}. {@link JdbcPushSortWithExpressionsHep} does
 * <em>not</em> match this topology because it matches {@link SortPrel}, not {@link TopNPrel}.
 * The existing {@link JdbcPushTopNIntoScanHep} also fails because its {@code findJdbcScan()}
 * only traverses {@code ExchangePrel} nodes and returns null for
 * {@code TopNPrel(ProjectPrel(...))}.
 *
 * <p>This rule handles the TopN+expression case specifically:
 * <ul>
 *   <li>The sort input contains a {@link ProjectPrel} above a {@link JdbcScanPrel}.</li>
 *   <li>At least one sort key expression from the ProjectPrel is a non-trivial function call.</li>
 *   <li>All sort key expressions pass the {@link PushdownFunctionRegistry} whitelist.</li>
 *   <li>The scan does not already have ORDER BY pushed.</li>
 * </ul>
 *
 * <p>On match, the sort key expressions and limit are stored on the scan via
 * {@link JdbcScanPrel#cloneWithSortKeyExpressions(org.apache.calcite.rel.RelCollation, List)}
 * followed by {@link JdbcScanPrel#cloneWithLimit(int)}.
 *
 * <p>Registered in {@code PHYSICAL_HEP} via {@link JdbcRulesFactory}.
 */
public final class JdbcPushTopNWithExpressionsHep extends RelOptRule {

  private final PushdownFunctionRegistry registry;

  public JdbcPushTopNWithExpressionsHep(PushdownFunctionRegistry registry) {
    super(RelOptRule.operand(TopNPrel.class, RelOptRule.any()), "JdbcPushTopNWithExpressionsHep");
    this.registry = registry;
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    TopNPrel topN = call.rel(0);

    // Must have field collations to push
    if (topN.getCollation().getFieldCollations().isEmpty()) {
      return false;
    }

    RelNode[] pair = JdbcPushSortWithExpressionsHep.findProjectAndScan(topN.getInput(), 2);
    if (pair == null) {
      return false;
    }
    ProjectPrel projectPrel = (ProjectPrel) pair[0];
    JdbcScanPrel scan = (JdbcScanPrel) pair[1];

    // Skip if scan already has ORDER BY
    if (scan.hasOrderBy()) {
      return false;
    }

    List<RexNode> projects = projectPrel.getProjects();
    boolean hasFunctionSortKey = false;

    for (RelFieldCollation fc : topN.getCollation().getFieldCollations()) {
      int fieldIdx = fc.getFieldIndex();
      if (fieldIdx < 0 || fieldIdx >= projects.size()) {
        return false;
      }
      RexNode sortKeyExpr = projects.get(fieldIdx);
      if (!(sortKeyExpr instanceof RexInputRef)) {
        hasFunctionSortKey = true;
      }
      // Validate pushability
      if (!registry.isExpressionPushable(sortKeyExpr)) {
        return false;
      }
    }

    // Only handle when there is at least one function sort key.
    // Simple column-ref sorts are handled by JdbcPushTopNIntoScanHep.
    return hasFunctionSortKey;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    TopNPrel topN = call.rel(0);

    RelNode[] pair = JdbcPushSortWithExpressionsHep.findProjectAndScan(topN.getInput(), 2);
    if (pair == null) {
      return;
    }
    ProjectPrel projectPrel = (ProjectPrel) pair[0];
    JdbcScanPrel scan = (JdbcScanPrel) pair[1];

    List<RexNode> projects = projectPrel.getProjects();

    // Extract sort key expressions at each collation field index from the ProjectPrel.
    List<RexNode> sortKeyExprs = new ArrayList<>();
    for (RelFieldCollation fc : topN.getCollation().getFieldCollations()) {
      sortKeyExprs.add(projects.get(fc.getFieldIndex()));
    }

    // Store sort key expressions + collation, then limit.
    JdbcScanPrel withSort = scan.cloneWithSortKeyExpressions(topN.getCollation(), sortKeyExprs);
    JdbcScanPrel withSortAndLimit = withSort.cloneWithLimit(topN.getLimit());
    call.transformTo(withSortAndLimit);
  }
}
