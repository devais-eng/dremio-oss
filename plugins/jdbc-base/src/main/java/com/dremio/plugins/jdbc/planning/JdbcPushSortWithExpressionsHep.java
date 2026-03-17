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

import com.dremio.exec.planner.physical.ExchangePrel;
import com.dremio.exec.planner.physical.ProjectPrel;
import com.dremio.exec.planner.physical.SortPrel;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;

/**
 * HEP-phase pushdown rule that absorbs a {@link SortPrel} whose sort keys are function expressions
 * (e.g. {@code ORDER BY UPPER(name)}) when the topology is
 * {@code SortPrel(ProjectPrel(JdbcScanPrel))}.
 *
 * <p>Complements {@link JdbcPushSortIntoScanHep}, which handles the simple
 * {@code SortPrel(JdbcScanPrel)} topology where sort keys are plain column references. This rule
 * fires when:
 * <ul>
 *   <li>The sort input contains a {@link ProjectPrel} above a {@link JdbcScanPrel}.</li>
 *   <li>At least one sort key expression from the ProjectPrel is a non-trivial function call
 *       (not a plain {@link RexInputRef}).</li>
 *   <li>All sort key expressions pass the {@link PushdownFunctionRegistry} whitelist check.</li>
 *   <li>The sort does not have an OFFSET clause.</li>
 *   <li>The scan does not already have ORDER BY pushed.</li>
 * </ul>
 *
 * <p>On match, the sort key expressions (RexNode trees from the ProjectPrel at the collation
 * field indices) are stored on the scan via
 * {@link JdbcScanPrel#cloneWithSortKeyExpressions(org.apache.calcite.rel.RelCollation, List)}.
 * At {@link JdbcScanPrel#getPhysicalOperator} time, step 9 extends the JdbcProject output with
 * these sort key expressions, builds JdbcSort, then trims back to the original output width.
 *
 * <p>Registered in {@code PHYSICAL_HEP} via {@link JdbcRulesFactory}.
 */
public final class JdbcPushSortWithExpressionsHep extends RelOptRule {

  private final PushdownFunctionRegistry registry;

  public JdbcPushSortWithExpressionsHep(PushdownFunctionRegistry registry) {
    super(RelOptRule.operand(SortPrel.class, RelOptRule.any()), "JdbcPushSortWithExpressionsHep");
    this.registry = registry;
  }

  /** Unwrap a {@link HepRelVertex} to its underlying rel, or return the node as-is. */
  static RelNode unwrap(RelNode node) {
    return node instanceof HepRelVertex ? ((HepRelVertex) node).getCurrentRel() : node;
  }

  /**
   * Traverses through at most {@code maxExchangeDepth} exchange nodes to find the first
   * {@link ProjectPrel}, then checks that the ProjectPrel's input (through at most 1 exchange)
   * is a {@link JdbcScanPrel}.
   *
   * @return array [ProjectPrel, JdbcScanPrel] or null if the pattern is not found
   */
  static RelNode[] findProjectAndScan(RelNode node, int maxExchangeDepth) {
    RelNode current = unwrap(node);

    // Traverse through at most maxExchangeDepth exchange nodes to find a ProjectPrel
    for (int i = 0; i <= maxExchangeDepth; i++) {
      if (current instanceof ProjectPrel) {
        // Found ProjectPrel; now check its input for a JdbcScanPrel (through at most 1 exchange)
        RelNode projInput = unwrap(((ProjectPrel) current).getInput());
        if (projInput instanceof JdbcScanPrel) {
          return new RelNode[]{current, projInput};
        }
        if (projInput instanceof ExchangePrel && projInput.getInputs().size() == 1) {
          RelNode innerInput = unwrap(projInput.getInput(0));
          if (innerInput instanceof JdbcScanPrel) {
            return new RelNode[]{current, innerInput};
          }
        }
        return null;
      }
      if (current instanceof ExchangePrel && current.getInputs().size() == 1) {
        current = unwrap(current.getInput(0));
      } else {
        return null;
      }
    }
    return null;
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    SortPrel sort = call.rel(0);

    // Must have field collations to push
    if (sort.getCollation().getFieldCollations().isEmpty()) {
      return false;
    }
    // OFFSET pushdown is not supported
    if (sort.offset != null) {
      return false;
    }

    RelNode[] pair = findProjectAndScan(sort.getInput(), 2);
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

    for (RelFieldCollation fc : sort.getCollation().getFieldCollations()) {
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

    // Only take over from JdbcPushSortIntoScanHep if there's at least one function sort key.
    return hasFunctionSortKey;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    SortPrel sort = call.rel(0);

    RelNode[] pair = findProjectAndScan(sort.getInput(), 2);
    if (pair == null) {
      return;
    }
    ProjectPrel projectPrel = (ProjectPrel) pair[0];
    JdbcScanPrel scan = (JdbcScanPrel) pair[1];

    List<RexNode> projects = projectPrel.getProjects();

    // Extract sort key expressions at each collation field index from the ProjectPrel.
    List<RexNode> sortKeyExprs = new ArrayList<>();
    for (RelFieldCollation fc : sort.getCollation().getFieldCollations()) {
      sortKeyExprs.add(projects.get(fc.getFieldIndex()));
    }

    // Store both the collation and the sort key expressions on the scan.
    JdbcScanPrel newScan = scan.cloneWithSortKeyExpressions(sort.getCollation(), sortKeyExprs);
    call.transformTo(newScan);
  }
}
