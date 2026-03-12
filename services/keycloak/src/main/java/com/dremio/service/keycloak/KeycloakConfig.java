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

import static com.dremio.config.DremioConfig.KEYCLOAK_CLIENT_ID;
import static com.dremio.config.DremioConfig.KEYCLOAK_CLIENT_SECRET;
import static com.dremio.config.DremioConfig.KEYCLOAK_ISSUER_URL;
import static com.dremio.config.DremioConfig.KEYCLOAK_REDIRECT_URI;
import static com.dremio.config.DremioConfig.KEYCLOAK_ROLE_SYNC_MODE;

import com.google.common.base.Preconditions;
import com.typesafe.config.Config;

/**
 * Typed configuration bean for Keycloak OIDC integration settings.
 *
 * <p>Reads all Keycloak-related properties from the DremioConfig and validates that the required
 * fields (issuer-url, client-id, client-secret) are non-blank at startup.
 */
public class KeycloakConfig {

  private final String issuerUrl;
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final String roleSyncMode;

  /**
   * Constructs a KeycloakConfig from the given config.
   *
   * @param config the DremioConfig (or any Config) containing keycloak settings
   * @throws IllegalArgumentException if issuer-url, client-id, or client-secret are blank
   */
  public KeycloakConfig(Config config) {
    this.issuerUrl = config.getString(KEYCLOAK_ISSUER_URL);
    this.clientId = config.getString(KEYCLOAK_CLIENT_ID);
    this.clientSecret = config.getString(KEYCLOAK_CLIENT_SECRET);
    this.redirectUri = config.getString(KEYCLOAK_REDIRECT_URI);
    this.roleSyncMode = config.getString(KEYCLOAK_ROLE_SYNC_MODE);

    Preconditions.checkArgument(
        !issuerUrl.isBlank(),
        "Keycloak configuration error: 'issuer-url' must not be blank. "
            + "Set services.keycloak.issuer-url in dremio.conf.");
    Preconditions.checkArgument(
        !clientId.isBlank(),
        "Keycloak configuration error: 'client-id' must not be blank. "
            + "Set services.keycloak.client-id in dremio.conf.");
    Preconditions.checkArgument(
        !clientSecret.isBlank(),
        "Keycloak configuration error: 'client-secret' must not be blank. "
            + "Set services.keycloak.client-secret in dremio.conf.");
  }

  /** Returns the Keycloak realm issuer URL, e.g. {@code https://keycloak.example.com/realms/dremio}. */
  public String getIssuerUrl() {
    return issuerUrl;
  }

  /** Returns the OAuth2 client ID registered in Keycloak. */
  public String getClientId() {
    return clientId;
  }

  /** Returns the OAuth2 client secret registered in Keycloak. */
  public String getClientSecret() {
    return clientSecret;
  }

  /** Returns the OAuth2 redirect URI (callback URL) registered in Keycloak. */
  public String getRedirectUri() {
    return redirectUri;
  }

  /** Returns the role synchronization mode: {@code "additive"} or {@code "authoritative"}. */
  public String getRoleSyncMode() {
    return roleSyncMode;
  }

  /**
   * Returns the Keycloak JWKS URI for public key retrieval.
   *
   * <p>Keycloak's JWKS endpoint is at {@code {issuerUrl}/protocol/openid-connect/certs}.
   */
  public String getJwksUri() {
    return issuerUrl + "/protocol/openid-connect/certs";
  }

  /**
   * Returns {@code true} if role sync mode is "additive" — Dremio's existing manually-assigned
   * roles are preserved and Keycloak roles are added on top.
   *
   * <p>Returns {@code false} for "authoritative" mode — Keycloak roles fully replace the user's
   * Dremio role memberships on every login.
   */
  public boolean isAdditive() {
    return "additive".equalsIgnoreCase(roleSyncMode);
  }
}
