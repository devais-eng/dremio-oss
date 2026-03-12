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
package com.dremio.service.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link KeycloakConfig}. */
public class TestKeycloakConfig {

  /**
   * Builds a Config using ConfigFactory.parseMap overlaid on the dremio-reference.conf defaults.
   * This provides the reference defaults plus the supplied overrides, matching production behavior.
   */
  private Config buildConfig(Map<String, Object> overrides) {
    Config overrideConfig = ConfigFactory.parseMap(overrides);
    Config reference = ConfigFactory.parseResources("dremio-reference.conf");
    return overrideConfig.withFallback(reference).resolve();
  }

  private Map<String, Object> validConfigMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("services.keycloak.issuer-url", "https://keycloak.example.com/realms/dremio");
    map.put("services.keycloak.client-id", "dremio-client");
    map.put("services.keycloak.client-secret", "super-secret");
    map.put("services.keycloak.redirect-uri", "https://dremio.example.com/oidc/callback");
    map.put("services.keycloak.role.sync-mode", "additive");
    return map;
  }

  @Test
  public void testValidConfigReadsAllFields() {
    Config config = buildConfig(validConfigMap());
    KeycloakConfig keycloakConfig = new KeycloakConfig(config);

    assertThat(keycloakConfig.getIssuerUrl())
        .isEqualTo("https://keycloak.example.com/realms/dremio");
    assertThat(keycloakConfig.getClientId()).isEqualTo("dremio-client");
    assertThat(keycloakConfig.getClientSecret()).isEqualTo("super-secret");
    assertThat(keycloakConfig.getRedirectUri())
        .isEqualTo("https://dremio.example.com/oidc/callback");
    assertThat(keycloakConfig.getRoleSyncMode()).isEqualTo("additive");
  }

  @Test
  public void testIsAdditiveReturnsTrueForAdditive() {
    Map<String, Object> map = validConfigMap();
    map.put("services.keycloak.role.sync-mode", "additive");
    Config config = buildConfig(map);
    KeycloakConfig keycloakConfig = new KeycloakConfig(config);

    assertThat(keycloakConfig.isAdditive()).isTrue();
  }

  @Test
  public void testIsAdditiveReturnsFalseForAuthoritative() {
    Map<String, Object> map = validConfigMap();
    map.put("services.keycloak.role.sync-mode", "authoritative");
    Config config = buildConfig(map);
    KeycloakConfig keycloakConfig = new KeycloakConfig(config);

    assertThat(keycloakConfig.isAdditive()).isFalse();
  }

  @Test
  public void testGetJwksUriReturnsCorrectPath() {
    Config config = buildConfig(validConfigMap());
    KeycloakConfig keycloakConfig = new KeycloakConfig(config);

    assertThat(keycloakConfig.getJwksUri())
        .isEqualTo(
            "https://keycloak.example.com/realms/dremio/protocol/openid-connect/certs");
  }

  @Test
  public void testBlankIssuerUrlThrowsIllegalArgumentException() {
    Map<String, Object> map = validConfigMap();
    map.put("services.keycloak.issuer-url", "");
    Config config = buildConfig(map);

    assertThatThrownBy(() -> new KeycloakConfig(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("issuer-url");
  }

  @Test
  public void testBlankClientIdThrowsIllegalArgumentException() {
    Map<String, Object> map = validConfigMap();
    map.put("services.keycloak.client-id", "");
    Config config = buildConfig(map);

    assertThatThrownBy(() -> new KeycloakConfig(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("client-id");
  }

  @Test
  public void testBlankClientSecretThrowsIllegalArgumentException() {
    Map<String, Object> map = validConfigMap();
    map.put("services.keycloak.client-secret", "");
    Config config = buildConfig(map);

    assertThatThrownBy(() -> new KeycloakConfig(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("client-secret");
  }
}
