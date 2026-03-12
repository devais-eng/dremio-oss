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

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process store mapping Dremio session tokens to Keycloak {@code id_token} strings.
 *
 * <p>The {@code id_token} is stored after a successful OIDC callback token exchange so that it can
 * be used as {@code id_token_hint} during RP-Initiated Logout (LOUT-02, Phase 35). Keyed by the
 * Dremio session token value — the same token delivered to the browser after SSO login.
 *
 * <p>This class is bound as a singleton in {@code DACDaemonModule} for the Keycloak auth branch.
 */
public class OidcSessionStore {

  private final ConcurrentHashMap<String, String> idTokensByDremioToken = new ConcurrentHashMap<>();

  /**
   * Stores the Keycloak {@code id_token} associated with a Dremio session token.
   *
   * <p>Overwrites any previously stored value for the same {@code dremioToken}.
   *
   * @param dremioToken the Dremio session token (from {@code TokenDetails.token})
   * @param idToken the serialized Keycloak {@code id_token} JWT string
   */
  public void put(String dremioToken, String idToken) {
    idTokensByDremioToken.put(dremioToken, idToken);
  }

  /**
   * Returns the Keycloak {@code id_token} associated with the given Dremio session token.
   *
   * @param dremioToken the Dremio session token to look up
   * @return the stored {@code id_token}, or {@code null} if not found
   */
  public String get(String dremioToken) {
    return idTokensByDremioToken.get(dremioToken);
  }

  /**
   * Removes the stored {@code id_token} for the given Dremio session token.
   *
   * <p>Called during logout to clean up session state (LOUT-02).
   *
   * @param dremioToken the Dremio session token whose associated id_token should be removed
   */
  public void remove(String dremioToken) {
    idTokensByDremioToken.remove(dremioToken);
  }
}
