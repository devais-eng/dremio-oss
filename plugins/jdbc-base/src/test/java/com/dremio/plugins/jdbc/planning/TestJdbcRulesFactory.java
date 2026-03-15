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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.planner.PlannerPhase;
import java.util.Set;
import org.apache.calcite.plan.RelOptRule;
import org.junit.Test;

/** Unit tests for {@link JdbcRulesFactory}. */
public class TestJdbcRulesFactory {

  private final JdbcRulesFactory factory = new JdbcRulesFactory();

  @SourceType(value = "JDBC_TEST", configurable = false)
  private static final class DummyConf {}

  private static final SourceType TEST_SOURCE_TYPE =
      DummyConf.class.getAnnotation(SourceType.class);

  @Test
  public void logicalPhaseReturnsOneRule() {
    Set<RelOptRule> rules = factory.getRules(null, PlannerPhase.LOGICAL, TEST_SOURCE_TYPE);
    // JOIN pushdown is injected globally via JdbcJoinRulesFactory, not via JdbcRulesFactory.
    assertEquals("LOGICAL phase should return exactly 1 rule", 1, rules.size());

    boolean hasScanDrule = false;
    for (RelOptRule rule : rules) {
      if (rule instanceof JdbcScanDrule) {
        hasScanDrule = true;
      }
    }
    assertTrue("LOGICAL should include JdbcScanDrule", hasScanDrule);
  }

  @Test
  public void physicalPhaseReturnsSixRules() {
    Set<RelOptRule> rules = factory.getRules(null, PlannerPhase.PHYSICAL, (SourceType) null);
    assertEquals("PHYSICAL phase should return exactly 7 rules", 7, rules.size());

    boolean hasPrule = false;
    boolean hasFilter = false;
    boolean hasProject = false;
    boolean hasLimit = false;
    boolean hasSort = false;
    boolean hasAgg = false;
    for (RelOptRule rule : rules) {
      if (rule instanceof JdbcScanPrule) {
        hasPrule = true;
      }
      if (rule instanceof JdbcPushFilterIntoScan) {
        hasFilter = true;
      }
      if (rule instanceof JdbcPushProjectIntoScan) {
        hasProject = true;
      }
      if (rule instanceof JdbcPushLimitIntoScan) {
        hasLimit = true;
      }
      if (rule instanceof JdbcPushSortIntoScan) {
        hasSort = true;
      }
      if (rule instanceof JdbcPushAggIntoScan) {
        hasAgg = true;
      }
    }
    assertTrue("PHYSICAL should include JdbcScanPrule", hasPrule);
    assertTrue("PHYSICAL should include JdbcPushFilterIntoScan", hasFilter);
    assertTrue("PHYSICAL should include JdbcPushProjectIntoScan", hasProject);
    assertTrue("PHYSICAL should include JdbcPushLimitIntoScan", hasLimit);
    assertTrue("PHYSICAL should include JdbcPushSortIntoScan", hasSort);
    assertTrue("PHYSICAL should include JdbcPushAggIntoScan", hasAgg);
  }

  @Test
  public void joinPlanningPhaseReturnsEmpty() {
    Set<RelOptRule> rules =
        factory.getRules(null, PlannerPhase.JOIN_PLANNING_MULTI_JOIN, (SourceType) null);
    assertTrue("Non-LOGICAL/PHYSICAL phases should return empty set", rules.isEmpty());
  }

  @Test
  public void reduceExpressionPhaseReturnsEmpty() {
    Set<RelOptRule> rules =
        factory.getRules(null, PlannerPhase.REDUCE_EXPRESSIONS, (SourceType) null);
    assertTrue("REDUCE_EXPRESSIONS should return empty set", rules.isEmpty());
  }
}
