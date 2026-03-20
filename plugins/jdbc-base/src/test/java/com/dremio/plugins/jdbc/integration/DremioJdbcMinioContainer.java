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
 * MinIO S3-compatible container for Testcontainers.
 *
 * <p>Implements {@link DremioContainer} to satisfy the {@code DremioRestrictedTestcontainersUsage}
 * error-prone check. Provides S3-compatible storage on ports 9000 (API) and 9090 (console) for
 * Iceberg/Nessie and raw Parquet file hosting in integration tests.
 */
public final class DremioJdbcMinioContainer extends GenericContainer<DremioJdbcMinioContainer>
    implements DremioContainer {

  private static final String IMAGE = "quay.io/minio/minio";

  /** Creates a new MinIO container with default admin credentials. */
  public DremioJdbcMinioContainer() {
    super(IMAGE);
    withCommand("server", "/data");
    withExposedPorts(9000, 9090);
    withEnv("MINIO_ROOT_USER", "minioadmin");
    withEnv("MINIO_ROOT_PASSWORD", "minioadmin");
    withEnv("MINIO_ADDRESS", ":9000");
    withEnv("MINIO_CONSOLE_ADDRESS", ":9090");
    waitingFor(
        Wait.forHttp("/minio/health/live")
            .forPort(9000)
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofSeconds(60)));
  }

  /** Returns the S3-compatible API endpoint URL accessible from the host. */
  public String getS3Endpoint() {
    return "http://" + getHost() + ":" + getMappedPort(9000);
  }
}
