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
package com.dremio.plugins.jdbc.postgresql;

import com.dremio.plugins.jdbc.planning.JdbcRulesFactory;
import com.google.common.collect.ImmutableSet;
import org.apache.calcite.plan.RelOptRule;

/**
 * PostgreSQL-specific rules factory that extends the base JDBC rules with pgvector support.
 *
 * <p>Adds {@link PgvectorKnnPushdownRule} to the PHYSICAL_HEP phase, enabling pushdown of
 * {@code ORDER BY l2_distance(embedding, ARRAY[...]) LIMIT K} to PostgreSQL as
 * {@code ORDER BY "embedding" <-> '[...]' LIMIT K} for HNSW index-accelerated nearest-neighbor
 * search.
 */
public class PostgresRulesFactory extends JdbcRulesFactory {

  @Override
  protected void addDialectSpecificRules(ImmutableSet.Builder<RelOptRule> rules) {
    rules.add(PgvectorKnnPushdownRule.INSTANCE);
  }
}
