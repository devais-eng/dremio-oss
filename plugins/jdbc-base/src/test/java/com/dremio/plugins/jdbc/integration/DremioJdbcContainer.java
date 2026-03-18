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
 * Testcontainers wrapper for the {@code dremio-oss:jdbc-test} Docker image.
 *
 * <p>Implements {@link DremioContainer} to satisfy the {@code DremioRestrictedTestcontainersUsage}
 * error-prone check. The image is pre-built locally; if not available the test class uses {@code
 * Assume.assumeTrue} to skip rather than fail.
 */
public final class DremioJdbcContainer extends GenericContainer<DremioJdbcContainer>
    implements DremioContainer {

  private static final String IMAGE = "dremio-oss:jdbc-test";

  /** Creates a new container using the {@code dremio-oss:jdbc-test} image. */
  public DremioJdbcContainer() {
    super(IMAGE);
    withExposedPorts(9047)
        .waitingFor(
            Wait.forHttp("/apiv2/server_status")
                .forPort(9047)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
  }

  /** Returns the HTTP base URL for the Dremio REST API. */
  public String getDremioUrl() {
    return "http://" + getHost() + ":" + getMappedPort(9047);
  }
}
