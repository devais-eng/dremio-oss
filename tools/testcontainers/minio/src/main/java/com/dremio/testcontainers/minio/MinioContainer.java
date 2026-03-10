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
package com.dremio.testcontainers.minio;

import com.dremio.testcontainers.DremioContainer;
import com.dremio.testcontainers.DremioTestcontainersUsageValidator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainer for MinIO S3-compatible object storage. Exposes the S3 API on port 9000.
 *
 * <p>Default credentials: {@code minioadmin} / {@code minioadmin}.
 */
public final class MinioContainer extends GenericContainer<MinioContainer>
    implements DremioContainer {

  private static final DockerImageName IMAGE =
      DockerImageName.parse("minio/minio:RELEASE.2024-01-16T16-07-38Z");
  private static final int MINIO_PORT = 9000;

  public static final String DEFAULT_ACCESS_KEY = "minioadmin";
  public static final String DEFAULT_SECRET_KEY = "minioadmin";

  public MinioContainer() {
    super(IMAGE);
    addExposedPort(MINIO_PORT);
    withEnv("MINIO_ROOT_USER", DEFAULT_ACCESS_KEY);
    withEnv("MINIO_ROOT_PASSWORD", DEFAULT_SECRET_KEY);
    withCommand("server /data");
    waitingFor(new HttpWaitStrategy().forPort(MINIO_PORT).forPath("/minio/health/ready"));
  }

  /** Returns the S3 endpoint URI, e.g., {@code http://host:mappedPort}. */
  public String getS3Endpoint() {
    return String.format("http://%s:%d", getHost(), getMappedPort(MINIO_PORT));
  }

  /** Returns the S3 port inside the container (9000). */
  public int getInternalPort() {
    return MINIO_PORT;
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
