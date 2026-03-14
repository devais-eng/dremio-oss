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

import com.dremio.testcontainers.DremioContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL TestContainers container approved for use in Dremio integration tests.
 *
 * <p>Implements {@link DremioContainer} to satisfy the {@code DremioRestrictedTestcontainersUsage}
 * error-prone check, which requires that all {@code GenericContainer} subclasses also implement the
 * {@code DremioContainer} marker interface.
 *
 * <p>Wraps {@link PostgreSQLContainer} with a fixed {@code postgres:16-alpine} image and
 * pre-configured test credentials used by the JDBC PostgreSQL connector integration tests.
 */
public final class DremioPostgresContainer extends PostgreSQLContainer<DremioPostgresContainer>
    implements DremioContainer {

  private static final String IMAGE = "postgres:16-alpine";

  /** Creates a new container using {@code postgres:16-alpine}. */
  public DremioPostgresContainer() {
    super(IMAGE);
  }
}
