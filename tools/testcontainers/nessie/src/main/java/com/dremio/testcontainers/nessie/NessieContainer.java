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
 * Testcontainer for Nessie server. Serves both the Nessie REST API ({@code /api/v2})
 * and the Iceberg REST catalog ({@code /iceberg}) on port 19120.
 *
 * <p>Use {@link #getIcebergRestUri()} to get the Iceberg REST endpoint for
 * RESTCATALOG source configuration.
 */
public final class NessieContainer extends GenericContainer<NessieContainer>
    implements DremioContainer {

  private static final DockerImageName IMAGE =
      DockerImageName.parse("ghcr.io/projectnessie/nessie:0.100.3");
  private static final int NESSIE_PORT = 19120;

  public NessieContainer() {
    super(IMAGE);
    addExposedPort(NESSIE_PORT);
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
