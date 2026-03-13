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

/**
 * Pushdown rule that converts a {@link FilterPrel} above a {@link JdbcScanPrel}
 * into a WHERE clause carried by the scan node itself (BASE-05).
 *
 * <p>The rule translates the filter's {@code RexNode} condition into a SQL
 * string using the top-level {@link RexToSqlString} converter, which returns a
 * {@link RexToSqlResult} containing {@code ?} placeholders and ordered bind parameters.
 * If any part of the predicate cannot be translated, the rule declines to push down
 * and leaves the FilterPrel in place so Dremio handles it in-engine.
 */
public class JdbcPushFilterIntoScan extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushFilterIntoScan();

  private JdbcPushFilterIntoScan() {
    super(
        RelOptHelper.some(FilterPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushFilterIntoScan");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    JdbcScanPrel scan = call.rel(1);
    // Do not push a second filter if one is already present.
    return !scan.hasFilter();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    FilterPrel filter = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    RexToSqlResult result =
        new RexToSqlString(scan.getRowType()).convert(filter.getCondition());
    if (result == null) {
      // Unsupported expression -- do not push down.
      return;
    }

    call.transformTo(scan.cloneWithFilter(result.getSql(), result.getParams()));
  }
}
