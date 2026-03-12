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
package com.dremio.dac.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dremio.config.DremioConfig;
import com.dremio.dac.server.DACConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ServerConfigResource} covering the unauthenticated
 * {@code GET /api/v3/server-config} endpoint.
 *
 * <p>Covers requirements UI-01 and UI-02.
 */
@ExtendWith(MockitoExtension.class)
public class TestServerConfigResource {

  @Mock private DACConfig dacConfig;
  @Mock private DremioConfig dremioConfig;

  private ServerConfigResource resource;

  @BeforeEach
  void setUp() {
    when(dacConfig.getConfig()).thenReturn(dremioConfig);
    resource = new ServerConfigResource(dacConfig);
  }

  /** UI-01: When auth.type=keycloak, GET /api/v3/server-config returns {"authType":"keycloak"}. */
  @Test
  void testGetServerConfigKeycloak() {
    when(dremioConfig.getString(DremioConfig.WEB_AUTH_TYPE)).thenReturn("keycloak");

    Response response = resource.getServerConfig();

    assertThat(response.getStatus()).isEqualTo(200);
    ServerConfigResource.ServerConfig body =
        (ServerConfigResource.ServerConfig) response.getEntity();
    assertThat(body.getAuthType()).isEqualTo("keycloak");
  }

  /** UI-02: When auth.type=internal (default), GET /api/v3/server-config returns {"authType":"internal"}. */
  @Test
  void testGetServerConfigInternal() {
    when(dremioConfig.getString(DremioConfig.WEB_AUTH_TYPE)).thenReturn("internal");

    Response response = resource.getServerConfig();

    assertThat(response.getStatus()).isEqualTo(200);
    ServerConfigResource.ServerConfig body =
        (ServerConfigResource.ServerConfig) response.getEntity();
    assertThat(body.getAuthType()).isEqualTo("internal");
  }

  /**
   * The JSON response contains exactly one field ("authType") — no secrets, no URLs, no client IDs
   * are exposed.
   */
  @Test
  void testServerConfigResponseHasOnlyAuthType() throws Exception {
    when(dremioConfig.getString(DremioConfig.WEB_AUTH_TYPE)).thenReturn("keycloak");

    Response response = resource.getServerConfig();

    ServerConfigResource.ServerConfig body =
        (ServerConfigResource.ServerConfig) response.getEntity();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode json = mapper.valueToTree(body);
    assertThat(json.size()).isEqualTo(1);
    assertThat(json.has("authType")).isTrue();
  }
}
