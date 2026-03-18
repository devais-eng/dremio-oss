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
package com.dremio.plugins.jdbc.integration;

import com.dremio.testcontainers.DremioContainer;
import org.testcontainers.containers.OracleContainer;

/**
 * Oracle XE Testcontainers wrapper for use in JDBC integration tests.
 *
 * <p>Implements {@link DremioContainer} to satisfy the {@code DremioRestrictedTestcontainersUsage}
 * error-prone check. Uses the {@code gvenzl/oracle-xe:21-slim} image with the TESTUSER application
 * user whose queries appear in V$SQL for pushdown verification.
 */
public final class DremioJdbcOracleContainer extends OracleContainer implements DremioContainer {

  private static final String IMAGE = "gvenzl/oracle-xe:21-slim";

  /** Creates a new Oracle XE container with TESTUSER/testpass credentials. */
  public DremioJdbcOracleContainer() {
    super(IMAGE);
    withUsername("TESTUSER").withPassword("testpass");
  }
}
