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
import com.dremio.exec.planner.physical.SortPrel;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HEP-phase pushdown rule that absorbs a concrete {@link SortPrel} into the {@link JdbcScanPrel}
 * below it, storing the Calcite {@link org.apache.calcite.rel.RelCollation} on the scan so that
 * {@link DremioJdbcImplementor} renders the ORDER BY clause at {@link
 * JdbcScanPrel#getPhysicalOperator} time.
 *
 * <p>This rule complements {@link JdbcPushSortIntoScan} (which matches logical {@code
 * SortRel(JdbcScanDrel)} in the Volcano phase). The Volcano-phase rule handles the simple case
 * where the sort is directly above the logical scan. However, when other pushdown rules (e.g.
 * filter, project) fire first and consume the logical scan, the Volcano-phase sort rule cannot
 * compose with them because it produces a competing alternative rather than building on the
 * filtered scan.
 *
 * <p>After Volcano extracts the best plan, {@code SortPrel} exists as a concrete node in the plan
 * tree. In distributed mode (Dremio's default), exchange nodes are inserted between SortPrel and
 * JdbcScanPrel. This rule traverses through exchange nodes to find the underlying JdbcScanPrel and
 * stores the {@link org.apache.calcite.rel.RelCollation} on the scan, enabling combined WHERE +
 * ORDER BY pushdown.
 *
 * <p>Registered in {@code PHYSICAL_HEP} via {@link JdbcRulesFactory}.
 */
public final class JdbcPushSortIntoScanHep extends RelOptRule {

  private static final Logger logger = LoggerFactory.getLogger(JdbcPushSortIntoScanHep.class);

  public static final RelOptRule INSTANCE = new JdbcPushSortIntoScanHep();

  private JdbcPushSortIntoScanHep() {
    super(RelOptRule.operand(SortPrel.class, RelOptRule.any()), "JdbcPushSortIntoScanHep");
  }

  /** Unwrap a {@link HepRelVertex} to its underlying rel, or return the node as-is. */
  static RelNode unwrap(RelNode node) {
    return node instanceof HepRelVertex ? ((HepRelVertex) node).getCurrentRel() : node;
  }

  /**
   * Finds the first {@link JdbcScanPrel} reachable from {@code node} by traversing through at most
   * {@code maxDepth} single-input exchange nodes. Returns null if no JdbcScanPrel is found.
   */
  static JdbcScanPrel findJdbcScan(RelNode node, int maxDepth) {
    RelNode current = unwrap(node);
    for (int i = 0; i <= maxDepth; i++) {
      if (current instanceof JdbcScanPrel) {
        return (JdbcScanPrel) current;
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
    JdbcScanPrel scan = findJdbcScan(sort.getInput(), 2);
    if (scan == null) {
      return false;
    }
    if (scan.hasOrderBy()) {
      return false;
    }
    if (sort.offset != null) {
      return false;
    }
    return !sort.getCollation().getFieldCollations().isEmpty();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    SortPrel sort = call.rel(0);
    JdbcScanPrel scan = findJdbcScan(sort.getInput(), 2);
    if (scan == null) {
      return;
    }

    // Store RelCollation directly -- DremioJdbcImplementor renders ORDER BY at
    // getPhysicalOperator() time via JdbcRules.JdbcSort.
    call.transformTo(scan.cloneWithCollation(sort.getCollation()));
  }
}
