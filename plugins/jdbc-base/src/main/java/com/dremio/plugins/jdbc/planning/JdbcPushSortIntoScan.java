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
import com.dremio.exec.planner.logical.SortRel;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.Prel;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.type.RelDataType;

/**
 * Pushdown rule that absorbs a logical {@link SortRel} into a physical {@link JdbcScanPrel}, so the
 * generated SQL includes an ORDER BY clause and the database returns rows pre-sorted.
 *
 * <p>This rule matches at the <b>logical</b> level ({@code SortRel} above {@link JdbcScanDrel})
 * rather than the physical level because Dremio's {@code SortPrule} does not explicitly create a
 * {@code SortPrel} — it relies on Volcano's trait enforcement mechanism. Enforcers created by
 * Volcano are not visible to rule matching, so a physical-level rule ({@code
 * SortPrel(JdbcScanPrel)}) would never fire.
 *
 * <p>By matching the logical sort above the logical JDBC scan, the rule produces a physical {@link
 * JdbcScanPrel} with the ORDER BY clause and the collation trait already set. Volcano then sees
 * that the sort group already has a physical implementation satisfying the required collation and
 * does not need to insert a {@code SortPrel} enforcer.
 *
 * <p>The rule extracts sort direction and null ordering from the {@link RelCollation} carried by
 * the SortRel and translates each field collation into a SQL ORDER BY expression with explicit
 * {@code NULLS FIRST} or {@code NULLS LAST} directives.
 *
 * <p>Registered in the {@code PHYSICAL} (Volcano) phase via {@link JdbcRulesFactory}.
 */
public final class JdbcPushSortIntoScan extends RelOptRule {

  public static final RelOptRule INSTANCE = new JdbcPushSortIntoScan();

  private JdbcPushSortIntoScan() {
    super(
        RelOptHelper.some(SortRel.class, RelOptHelper.any(JdbcScanDrel.class)),
        "JdbcPushSortIntoScan");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    SortRel sort = call.rel(0);
    // Reject sorts with a non-null offset (offset is not expressible in ORDER BY alone).
    if (sort.offset != null) {
      return false;
    }
    // Must have at least one sort field.
    return !sort.getCollation().getFieldCollations().isEmpty();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    SortRel sort = call.rel(0);
    JdbcScanDrel logicalScan = call.rel(1);

    String orderByExpr = collationToSql(sort.getCollation(), logicalScan.getRowType());
    if (orderByExpr == null) {
      return;
    }

    // Extract schema and table names from the dataset namespace key path (same as JdbcScanPrule).
    List<String> pathComponents = logicalScan.getTableMetadata().getName().getPathComponents();
    String schemaName;
    String tableName;
    if (pathComponents.size() >= 3) {
      schemaName = pathComponents.get(pathComponents.size() - 2);
      tableName = pathComponents.get(pathComponents.size() - 1);
    } else if (pathComponents.size() == 2) {
      schemaName = pathComponents.get(0);
      tableName = pathComponents.get(1);
    } else {
      schemaName = "";
      tableName = pathComponents.isEmpty() ? "" : pathComponents.get(0);
    }

    // Create a physical scan with ORDER BY and the collation trait.
    // SINGLETON distribution matches LimitPrule's requirement so limit pushdown still works.
    JdbcScanPrel physicalScan =
        new JdbcScanPrel(
            logicalScan.getCluster(),
            logicalScan
                .getTraitSet()
                .replace(Prel.PHYSICAL)
                .plus(DistributionTrait.SINGLETON)
                .plus(sort.getCollation()),
            logicalScan.getTable(),
            logicalScan.getPluginId(),
            logicalScan.getTableMetadata(),
            logicalScan.getProjectedColumns(),
            logicalScan.getObservedRowcountAdjustment(),
            logicalScan.getHintsAsList(),
            ImmutableList.of(),
            schemaName,
            tableName,
            null, // whereClause
            ImmutableList.of(), // bindParams
            orderByExpr, // orderByClause
            null); // limit

    call.transformTo(physicalScan);
  }

  /**
   * Translates a {@link RelCollation} into a SQL ORDER BY expression string.
   *
   * <p>Each field collation is rendered as {@code "column" ASC NULLS FIRST} with explicit null
   * ordering. The returned string does NOT include the {@code ORDER BY} keywords -- those are added
   * by {@link SqlBuilder}.
   *
   * @param collation the sort collation from the SortRel
   * @param rowType the row type of the scan (used for field name lookup)
   * @return the ORDER BY expression, or null if the collation cannot be expressed
   */
  static String collationToSql(RelCollation collation, RelDataType rowType) {
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
