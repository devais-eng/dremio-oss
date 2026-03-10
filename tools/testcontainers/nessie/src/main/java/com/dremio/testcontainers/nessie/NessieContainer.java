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
package com.dremio.testcontainers.nessie;

import com.dremio.testcontainers.DremioContainer;
import com.dremio.testcontainers.DremioTestcontainersUsageValidator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainer for Nessie server. Serves both the Nessie REST API ({@code /api/v2}) and the
 * Iceberg REST catalog ({@code /iceberg}) on port 19120.
 *
 * <p>Use {@link #getIcebergRestUri()} to get the Iceberg REST endpoint for RESTCATALOG source
 * configuration.
 */
public final class NessieContainer extends GenericContainer<NessieContainer>
    implements DremioContainer {

  private static final DockerImageName IMAGE =
      DockerImageName.parse("ghcr.io/projectnessie/nessie:0.100.3");
  private static final int NESSIE_PORT = 19120;

  private static final String DEFAULT_WAREHOUSE_NAME = "warehouse";

  public NessieContainer() {
    super(IMAGE);
    addExposedPort(NESSIE_PORT);
  }

  /**
   * Configures Nessie to use an S3-compatible object store (e.g., MinIO) as warehouse storage. Must
   * be called before the container is started. Both containers must share a Docker network.
   *
   * @param minioNetworkAlias the network alias of the MinIO container (e.g., "minio")
   * @param minioPort the MinIO S3 port inside the Docker network (typically 9000)
   * @param externalEndpoint the MinIO S3 endpoint reachable from the test JVM (e.g., {@code
   *     http://localhost:32789})
   * @param accessKey the MinIO access key
   * @param secretKey the MinIO secret key
   * @return this container for fluent chaining
   */
  public NessieContainer withS3Warehouse(
      String minioNetworkAlias,
      int minioPort,
      String externalEndpoint,
      String accessKey,
      String secretKey) {
    String internalEndpoint = String.format("http://%s:%d", minioNetworkAlias, minioPort);
    withEnv("NESSIE_CATALOG_DEFAULT_WAREHOUSE", DEFAULT_WAREHOUSE_NAME);
    withEnv(
        "NESSIE_CATALOG_WAREHOUSES__WAREHOUSE__LOCATION", "s3://" + DEFAULT_WAREHOUSE_NAME + "/");
    withEnv("NESSIE_CATALOG_SERVICE_S3_DEFAULT_OPTIONS_ENDPOINT", internalEndpoint);
    withEnv("NESSIE_CATALOG_SERVICE_S3_DEFAULT_OPTIONS_EXTERNAL_ENDPOINT", externalEndpoint);
    withEnv("NESSIE_CATALOG_SERVICE_S3_DEFAULT_OPTIONS_PATH_STYLE_ACCESS", "true");
    withEnv("NESSIE_CATALOG_SERVICE_S3_DEFAULT_OPTIONS_REGION", "us-east-1");
    withEnv("NESSIE_CATALOG_SERVICE_S3_DEFAULT_OPTIONS_AUTH_TYPE", "APPLICATION_GLOBAL");
    withEnv("AWS_ACCESS_KEY_ID", accessKey);
    withEnv("AWS_SECRET_ACCESS_KEY", secretKey);
    return this;
  }

  /** Returns the base URI, e.g., {@code http://host:mappedPort/}. */
  public String getBaseUri() {
    return String.format("http://%s:%d/", getHost(), getMappedPort(NESSIE_PORT));
  }

  /** Returns the Iceberg REST catalog endpoint, e.g., {@code http://host:mappedPort/iceberg}. */
  public String getIcebergRestUri() {
    return String.format("http://%s:%d/iceberg", getHost(), getMappedPort(NESSIE_PORT));
  }

  /** Returns the Nessie REST API endpoint, e.g., {@code http://host:mappedPort/api/v2}. */
  public String getNessieApiUri() {
    return String.format("http://%s:%d/api/v2", getHost(), getMappedPort(NESSIE_PORT));
  }

  @Override
  public void start() {
    DremioTestcontainersUsageValidator.validate();
    super.start();
  }

  @Override
  public void setDockerImageName(String dockerImageName) {
    throw new UnsupportedOperationException("Docker image name can not be changed");
  }
}
