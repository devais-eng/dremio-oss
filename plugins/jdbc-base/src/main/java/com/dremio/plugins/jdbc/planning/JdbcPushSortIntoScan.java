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
import com.dremio.exec.planner.physical.SortPrel;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.type.RelDataType;

/**
 * Pushdown rule that absorbs a {@link SortPrel} into the {@link JdbcScanPrel}
 * below it, so the generated SQL includes an ORDER BY clause and the database
 * returns rows pre-sorted.
 *
 * <p>The rule extracts sort direction and null ordering from the
 * {@link RelCollation} carried by the SortPrel and translates each field
 * collation into a SQL ORDER BY expression with explicit {@code NULLS FIRST}
 * or {@code NULLS LAST} directives.
 *
 * <p>Unsupported sort directions (e.g. {@code CLUSTERED}) cause the rule to
 * decline gracefully rather than produce incorrect SQL.
 *
 * <p><b>TopN mechanism:</b> TopN (ORDER BY + LIMIT) is achieved through
 * sequential rule firing. This rule absorbs the sort into the scan first,
 * then {@link JdbcPushLimitIntoScan} fires to absorb the limit. The
 * {@link SqlBuilder} combines both ORDER BY and LIMIT in the correct clause
 * order.
 *
 * <p>Registered in both {@code PHYSICAL} (Volcano) and {@code PHYSICAL_HEP}
 * phases via {@link JdbcRulesFactory}.
 */
public final class JdbcPushSortIntoScan extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushSortIntoScan();

  private JdbcPushSortIntoScan() {
    super(
        RelOptHelper.some(SortPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushSortIntoScan");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    JdbcScanPrel scan = call.rel(1);
    // Do not push a second ORDER BY into a scan that already has one.
    if (scan.hasOrderBy()) {
      return false;
    }
    SortPrel sort = call.rel(0);
    // Reject sorts with a non-null offset (offset is not expressible in ORDER BY alone).
    if (sort.offset != null) {
      return false;
    }
    // Must have at least one sort field.
    return !sort.getCollation().getFieldCollations().isEmpty();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    SortPrel sort = call.rel(0);
    JdbcScanPrel scan = call.rel(1);

    String orderByExpr = collationToSql(sort.getCollation(), scan.getRowType());
    if (orderByExpr == null) {
      // Unsupported direction (e.g. CLUSTERED) -- decline transformation.
      return;
    }

    call.transformTo(scan.cloneWithOrderBy(orderByExpr));
  }

  /**
   * Translates a {@link RelCollation} into a SQL ORDER BY expression string.
   *
   * <p>Each field collation is rendered as {@code "column" ASC NULLS FIRST} with
   * explicit null ordering. The returned string does NOT include the
   * {@code ORDER BY} keywords -- those are added by {@link SqlBuilder}.
   *
   * @param collation the sort collation from the SortPrel
   * @param rowType the row type of the scan (used for field name lookup)
   * @return the ORDER BY expression, or null if the collation cannot be expressed
   */
  private static String collationToSql(RelCollation collation, RelDataType rowType) {
    List<RelFieldCollation> fieldCollations = collation.getFieldCollations();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fieldCollations.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      RelFieldCollation fc = fieldCollations.get(i);
      int fieldIndex = fc.getFieldIndex();

      // Validate field index within bounds.
      if (fieldIndex < 0 || fieldIndex >= rowType.getFieldCount()) {
        return null;
      }

      // Quote the field name using double-quote escaping.
      String fieldName = rowType.getFieldList().get(fieldIndex).getName();
      sb.append("\"").append(fieldName.replace("\"", "\"\"")).append("\"");

      // Direction
      switch (fc.direction) {
        case ASCENDING:
        case STRICTLY_ASCENDING:
          sb.append(" ASC");
          break;
        case DESCENDING:
        case STRICTLY_DESCENDING:
          sb.append(" DESC");
          break;
        default:
          // CLUSTERED or unknown -- cannot express in SQL ORDER BY.
          return null;
      }

      // Null direction
      switch (fc.nullDirection) {
        case FIRST:
          sb.append(" NULLS FIRST");
          break;
        case LAST:
          sb.append(" NULLS LAST");
          break;
        case UNSPECIFIED:
          // Let the database use its default null ordering.
          break;
        default:
          break;
      }
    }
    return sb.toString();
  }
}
