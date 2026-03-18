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

import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.store.StoragePluginRulesFactory.StoragePluginTypeRulesFactory;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import org.apache.calcite.plan.RelOptRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link com.dremio.exec.store.StoragePluginRulesFactory} for JDBC-backed storage plugins.
 *
 * <p>Registers planner rules in the correct phases:
 *
 * <dl>
 *   <dt>LOGICAL
 *   <dd>{@link JdbcScanDrule} — converts the generic {@code ScanCrel} to a JDBC-specific {@link
 *       JdbcScanDrel}.
 *   <dd>{@link JdbcPushJoinIntoScan} — pushes {@code JoinRel} above two same-source
 *       {@link JdbcScanDrel} children into a single {@link JdbcJoinScanDrel}.
 *   <dt>PHYSICAL
 *   <dd>{@link JdbcScanPrule} — converts {@link JdbcScanDrel} to {@link JdbcScanPrel}.
 *   <dd>{@link JdbcPushJoinIntoScan.JdbcJoinScanPrule} — converts {@link JdbcJoinScanDrel} to
 *       {@link JdbcJoinScanPrel}.
 *   <dd>{@link JdbcPushFilterIntoScan} — pushes WHERE predicates into the scan.
 *   <dd>{@link JdbcPushProjectIntoScan} — narrows the SELECT list to projected columns.
 *   <dd>{@link JdbcPushAggIntoScan} — pushes GROUP BY and aggregate functions into the scan.
 *   <dd>{@link JdbcPushSortIntoScan} — pushes ORDER BY into the scan (logical-level, simple case).
 *   <dd>{@link JdbcPushLimitIntoScan} — pushes LIMIT into the scan.
 *   <dt>PHYSICAL_HEP
 *   <dd>{@link JdbcPushSortIntoScanHep} — absorbs concrete {@code SortPrel} into the scan, enabling
 *       combined WHERE + ORDER BY pushdown after filter pushdown in Volcano.
 *   <dd>{@link JdbcPushSortWithExpressionsHep} — absorbs {@code SortPrel(ProjectPrel(JdbcScanPrel))}
 *       when ORDER BY keys are function expressions (e.g. {@code ORDER BY UPPER(name)}).
 *   <dd>{@link JdbcPushTopNWithExpressionsHep} — same as above for TopNPrel.
 *   <dd>{@link JdbcPushAggWithExpressionsHep} — absorbs {@code AggregatePrel(ProjectPrel(JdbcScanPrel))}
 *       when GROUP BY keys or aggregate operands are function expressions (e.g.
 *       {@code GROUP BY EXTRACT(YEAR FROM hire_date)}, {@code SUM(salary * 1.1)}).
 *   <dd>{@link JdbcPushLimitIntoScan} — absorbs any remaining {@code LimitPrel}.
 * </dl>
 *
 * <p>Wired to {@link com.dremio.plugins.jdbc.JdbcStoragePlugin} via {@code
 * JdbcStoragePlugin.getRulesFactoryClass()}.
 */
public class JdbcRulesFactory extends StoragePluginTypeRulesFactory {

  private static final Logger logger = LoggerFactory.getLogger(JdbcRulesFactory.class);

  @Override
  public Set<RelOptRule> getRules(
      OptimizerRulesContext optimizerContext, PlannerPhase phase, SourceType pluginType) {

    logger.info("[JDBC-RULES-DEBUG] getRules called for phase: {}", phase);

    switch (phase) {
      case LOGICAL:
        // Convert the generic ScanCrel into the JDBC-specific logical scan node.
        // JOIN pushdown (JdbcPushJoinIntoScan) is injected globally via JdbcJoinRulesFactory.
        return ImmutableSet.<RelOptRule>of(new JdbcScanDrule(pluginType));

      case PHYSICAL:
        // Convert logical JDBC scan to physical, then apply pushdown optimisations.
        // JdbcJoinScanPrule converts JdbcJoinScanDrel -> JdbcJoinScanPrel (Drel->Prel).
        // Use StandardPushdownFunctionRegistry for all JDBC sources. Per-dialect subclasses
        // can override JdbcStoragePlugin.getPushdownFunctionRegistry() when needed.
        PushdownFunctionRegistry registry = StandardPushdownFunctionRegistry.INSTANCE;
        return ImmutableSet.<RelOptRule>of(
            JdbcScanPrule.INSTANCE,
            JdbcPushJoinIntoScan.JdbcJoinScanPrule.INSTANCE,
            new JdbcPushFilterIntoScan(registry),
            new JdbcPushHavingIntoScan(registry),
            JdbcPushProjectIntoScan.INSTANCE,
            JdbcPushAggIntoScan.INSTANCE,
            JdbcPushSortIntoScan.INSTANCE,
            JdbcPushLimitIntoScan.INSTANCE);

      case PHYSICAL_HEP:
        // After Volcano, concrete physical nodes exist. Absorb sort/topn/limit into scans.
        // JdbcPushSortWithExpressionsHep and JdbcPushTopNWithExpressionsHep handle the case
        // where ORDER BY sort keys are function expressions (e.g. ORDER BY UPPER(name)).
        PushdownFunctionRegistry hepRegistry = StandardPushdownFunctionRegistry.INSTANCE;
        return ImmutableSet.<RelOptRule>of(
            JdbcPushSortIntoScanHep.INSTANCE,
            JdbcPushTopNIntoScanHep.INSTANCE,
            new JdbcPushSortWithExpressionsHep(hepRegistry),
            new JdbcPushTopNWithExpressionsHep(hepRegistry),
            new JdbcPushAggWithExpressionsHep(hepRegistry),
            JdbcPushLimitIntoScan.INSTANCE,
            PgvectorKnnPushdownRule.INSTANCE);

      default:
        return ImmutableSet.of();
    }
  }

}
