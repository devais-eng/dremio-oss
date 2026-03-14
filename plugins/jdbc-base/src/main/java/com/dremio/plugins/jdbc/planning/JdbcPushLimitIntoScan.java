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
import com.dremio.exec.planner.physical.LimitPrel;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;

/**
 * Pushdown rule that absorbs a {@link LimitPrel} into the {@link JdbcScanPrel} below it, so the
 * generated SQL includes a LIMIT (PostgreSQL) or FETCH FIRST (Oracle) clause and fewer rows cross
 * the network.
 *
 * <p>Only pure row limits with no non-zero offset are pushed down. When the scan already carries a
 * limit the minimum of the two values is used, so repeated application of the rule is idempotent.
 *
 * <p>Registered in both {@code PHYSICAL} (Volcano) and {@code PHYSICAL_HEP} phases. The Volcano
 * match works because {@link JdbcScanPrule} creates the scan with {@link
 * com.dremio.exec.planner.physical.DistributionTrait#SINGLETON}, which is the same distribution
 * {@code LimitPrule} requires on its input — so no exchange is inserted between the two nodes and
 * the direct parent-child pattern matches. The HEP registration acts as a safety net.
 */
public final class JdbcPushLimitIntoScan extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushLimitIntoScan();

  private JdbcPushLimitIntoScan() {
    super(
        RelOptHelper.some(LimitPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushLimitIntoScan");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    LimitPrel limit = call.rel(0);
    // Only push down limits with no offset or with a zero offset.
    RexNode offset = limit.getOffset();
    if (offset != null && RexLiteral.intValue(offset) != 0) {
      return false;
    }
    // Must have a fetch clause to push.
    return limit.getFetch() != null;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    LimitPrel limitPrel = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    int fetchCount = RexLiteral.intValue(limitPrel.getFetch());

    // If the scan already carries a limit, take the minimum.
    Integer existingLimit = scan.getLimit();
    int newLimit = existingLimit == null ? fetchCount : Math.min(existingLimit, fetchCount);

    call.transformTo(scan.cloneWithLimit(newLimit));
  }
}
