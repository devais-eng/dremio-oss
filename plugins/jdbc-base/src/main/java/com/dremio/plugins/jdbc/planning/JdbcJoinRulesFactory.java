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

import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.RulesFactory;
import com.dremio.options.OptionManager;
import java.util.Collection;
import java.util.Collections;
import org.apache.calcite.plan.RelOptRule;

/**
 * Injects the JDBC JOIN pushdown rule into the global LOGICAL planner phase via the {@link
 * RulesFactory} extension point.
 *
 * <p>Plugin-specific rules registered via {@link
 * com.dremio.exec.store.StoragePluginRulesFactory} can only match nodes belonging to
 * their plugin's convention. Cross-cutting nodes like {@code LogicalJoin} are not visible to plugin
 * rules. This factory uses the {@link RulesFactory} mechanism (discovered via classpath scanning)
 * to inject {@link JdbcPushJoinIntoScan} into the global LOGICAL rule set, where it can match
 * any {@code LogicalJoin} and check if both sides are JDBC scans from the same source.
 *
 * <p>The rule is safe for non-JDBC queries: its {@code matches()} returns false immediately if the
 * children are not JDBC scan nodes.
 */
public class JdbcJoinRulesFactory implements RulesFactory {

  @Override
  public Collection<RelOptRule> getRules(PlannerPhase phase, OptionManager options) {
    if (phase == PlannerPhase.LOGICAL) {
      return Collections.singletonList(JdbcPushJoinIntoScan.INSTANCE);
    }
    return Collections.emptyList();
  }
}
