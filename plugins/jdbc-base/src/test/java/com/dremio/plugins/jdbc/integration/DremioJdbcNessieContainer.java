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
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Nessie Iceberg REST catalog container for Testcontainers.
 *
 * <p>Implements {@link DremioContainer} to satisfy the {@code DremioRestrictedTestcontainersUsage}
 * error-prone check. Provides a Nessie server with Iceberg REST catalog backed by MinIO S3 storage
 * for integration tests. Requires a MinIO container on the same Docker network with alias "minio".
 */
public final class DremioJdbcNessieContainer extends GenericContainer<DremioJdbcNessieContainer>
    implements DremioContainer {

  private static final String IMAGE = "ghcr.io/projectnessie/nessie:latest";

  /** Creates a new Nessie container configured for in-memory version store with S3/MinIO backend. */
  public DremioJdbcNessieContainer() {
    super(IMAGE);
    withExposedPorts(19120, 9000);
    withEnv("nessie.version.store.type", "IN_MEMORY");
    withEnv("nessie.catalog.default-warehouse", "warehouse");
    withEnv("nessie.catalog.warehouses.warehouse.location", "s3://warehouse/");
    withEnv("nessie.catalog.service.s3.default-options.endpoint", "http://minio:9000/");
    withEnv(
        "nessie.catalog.service.s3.default-options.external-endpoint", "http://minio:9000/");
    withEnv("nessie.catalog.service.s3.default-options.path-style-access", "true");
    withEnv("nessie.catalog.service.s3.default-options.region", "us-east-1");
    withEnv(
        "nessie.catalog.service.s3.default-options.access-key",
        "urn:nessie-secret:quarkus:nessie.catalog.secrets.access-key");
    withEnv("nessie.catalog.secrets.access-key.name", "minioadmin");
    withEnv("nessie.catalog.secrets.access-key.secret", "minioadmin");
    withEnv("nessie.server.authentication.enabled", "false");
    waitingFor(
        Wait.forHttp("/api/v2/config")
            .forPort(19120)
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofSeconds(120)));
  }
}
