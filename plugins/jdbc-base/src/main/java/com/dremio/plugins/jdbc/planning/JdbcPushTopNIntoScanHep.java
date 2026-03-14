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

import com.dremio.exec.planner.physical.TopNPrel;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;

/**
 * HEP-phase pushdown rule that absorbs a {@link TopNPrel} into the {@link JdbcScanPrel} below it,
 * pushing both ORDER BY and LIMIT into the generated SQL.
 *
 * <p>TopNPrel is created by Dremio's {@code PushLimitToTopN} rule during the Volcano phase when a
 * LIMIT sits above a Sort. It combines the sort collation and row limit into a single operator.
 * TopNPrel extends SinglePrel (not SortPrel), so the sort pushdown rule does not match it.
 *
 * <p>Like {@link JdbcPushSortIntoScanHep}, this rule traverses through exchange nodes to find the
 * underlying JdbcScanPrel, enabling combined WHERE + ORDER BY + LIMIT pushdown.
 *
 * <p>Registered in {@code PHYSICAL_HEP} via {@link JdbcRulesFactory}.
 */
public final class JdbcPushTopNIntoScanHep extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushTopNIntoScanHep();

  private JdbcPushTopNIntoScanHep() {
    super(RelOptRule.operand(TopNPrel.class, RelOptRule.any()), "JdbcPushTopNIntoScanHep");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    TopNPrel topN = call.rel(0);
    JdbcScanPrel scan = JdbcPushSortIntoScanHep.findJdbcScan(topN.getInput(), 2);
    if (scan == null) {
      return false;
    }
    if (scan.hasOrderBy()) {
      return false;
    }
    return !topN.getCollation().getFieldCollations().isEmpty();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    TopNPrel topN = call.rel(0);
    JdbcScanPrel scan = JdbcPushSortIntoScanHep.findJdbcScan(topN.getInput(), 2);
    if (scan == null) {
      return;
    }

    String orderByExpr =
        JdbcPushSortIntoScan.collationToSql(topN.getCollation(), scan.getRowType());
    if (orderByExpr == null) {
      return;
    }

    JdbcScanPrel withOrderBy = scan.cloneWithOrderBy(orderByExpr);
    JdbcScanPrel withOrderByAndLimit = withOrderBy.cloneWithLimit(topN.getLimit());
    call.transformTo(withOrderByAndLimit);
  }
}
