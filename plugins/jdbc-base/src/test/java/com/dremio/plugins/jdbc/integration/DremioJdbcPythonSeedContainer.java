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
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;

/**
 * Ephemeral Python container for seeding Iceberg tables via pyiceberg.
 *
 * <p>Implements {@link DremioContainer} to satisfy the {@code DremioRestrictedTestcontainersUsage}
 * error-prone check. Runs a Python script that uses pyiceberg to create Iceberg tables in the
 * Nessie REST catalog backed by MinIO S3 storage.
 */
public final class DremioJdbcPythonSeedContainer
    extends GenericContainer<DremioJdbcPythonSeedContainer> implements DremioContainer {

  private static final String IMAGE = "python:3.12-slim";

  /** Creates a new Python seed container with the given shell command. */
  public DremioJdbcPythonSeedContainer(String script) {
    super(IMAGE);
    withCommand("sh", "-c", script);
    withStartupTimeout(Duration.ofMinutes(5));
  }
}
