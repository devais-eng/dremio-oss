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

import com.dremio.exec.calcite.logical.ScanCrel;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.store.common.SourceLogicalConverter;

/**
 * Planner rule that converts the generic Dremio {@code ScanCrel} to a JDBC-specific {@link
 * JdbcScanDrel} during the <em>LOGICAL</em> planning phase.
 *
 * <p>The rule is source-type-aware: it only fires when the scan's plugin type matches the {@link
 * SourceType} annotation provided at construction time. Concrete connectors register an instance of
 * this rule — parameterized with their own {@code @SourceType} value — via their {@code
 * StoragePluginRulesFactory}.
 *
 * <p>The resulting {@link JdbcScanDrel} is later converted to a physical {@link JdbcScanPrel} by
 * {@link JdbcScanPrule} during the PHYSICAL phase.
 */
public class JdbcScanDrule extends SourceLogicalConverter {

  /**
   * Creates a new rule for the given source type.
   *
   * @param sourceType the {@link SourceType} annotation from the concrete connector's conf class
   */
  public JdbcScanDrule(SourceType sourceType) {
    super(sourceType);
  }

  @Override
  public Rel convertScan(ScanCrel scan) {
    return new JdbcScanDrel(
        scan.getCluster(),
        scan.getTraitSet().plus(Rel.LOGICAL),
        scan.getTable(),
        scan.getPluginId(),
        scan.getTableMetadata(),
        scan.getProjectedColumns(),
        scan.getObservedRowcountAdjustment(),
        scan.getHints());
  }
}
