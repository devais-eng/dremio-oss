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

import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.physical.FilterPrel;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rex.RexNode;

/**
 * Pushdown rule that converts a post-aggregation {@link FilterPrel} above a {@link JdbcScanPrel}
 * (that already has aggregation pushed) into a HAVING clause stored on the scan node itself.
 *
 * <p>This rule fires exclusively on {@code FilterPrel(JdbcScanPrel[hasAgg=true, !hasHaving])} —
 * i.e., a filter above an aggregated scan that does not yet have a HAVING condition. The sibling
 * rule {@link JdbcPushFilterIntoScan} handles pre-aggregation filters (WHERE) and explicitly
 * rejects the post-aggregation case.
 *
 * <p>The HAVING condition is stored as-is on the scan via
 * {@link JdbcScanPrel#cloneWithHaving(RexNode)}. The condition indices reference the aggregate
 * output row (group keys first, then aggregate functions) — no normalization to full-table
 * positions is needed or correct.
 *
 * <p>At {@link JdbcScanPrel#getPhysicalOperator} time, a {@code JdbcRules.JdbcFilter} wrapping
 * {@code JdbcRules.JdbcAggregate} is added to the Calcite subtree (step 8.5). Calcite's
 * {@link com.dremio.plugins.jdbc.planning.DremioJdbcImplementor} detects the filter position
 * (after the aggregate) and renders a {@code HAVING} clause rather than a {@code WHERE} clause.
 *
 * <p>Only conditions that pass the {@link PushdownFunctionRegistry#isExpressionPushable} check
 * are pushed; conditions containing non-whitelisted functions are left in Dremio's engine.
 */
public final class JdbcPushHavingIntoScan extends RelOptRule {

  private final PushdownFunctionRegistry registry;

  /**
   * Creates a HAVING pushdown rule using the given function registry.
   *
   * @param registry per-dialect whitelist controlling which functions are safe to push
   */
  public JdbcPushHavingIntoScan(PushdownFunctionRegistry registry) {
    super(
        RelOptHelper.some(FilterPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushHavingIntoScan");
    this.registry = registry;
  }

  /**
   * Fires only on {@code FilterPrel(JdbcScanPrel[hasAgg=true, !hasHaving])}.
   *
   * <ul>
   *   <li>If the scan does not have aggregation, this is a WHERE filter — handled by
   *       {@link JdbcPushFilterIntoScan}.</li>
   *   <li>If the scan already has a HAVING condition, do not push a second one.</li>
   * </ul>
   */
  @Override
  public boolean matches(RelOptRuleCall call) {
    JdbcScanPrel scan = call.rel(1);
    return scan.hasAggregation() && !scan.hasHaving();
  }

  /**
   * Stores the filter condition as a HAVING clause on the scan.
   *
   * <p>The condition is validated against the function registry first. If it contains any
   * non-whitelisted function call, the rule declines (returns without transforming).
   */
  @Override
  public void onMatch(RelOptRuleCall call) {
    FilterPrel filter = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    RexNode condition = filter.getCondition();

    // Validate all functions in the HAVING condition against the whitelist.
    // HAVING condition references aggregate output indices — no normalization needed.
    if (!registry.isExpressionPushable(condition)) {
      return;
    }

    call.transformTo(scan.cloneWithHaving(condition));
  }
}
