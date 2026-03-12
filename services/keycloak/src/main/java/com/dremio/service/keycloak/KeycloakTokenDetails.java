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

import java.util.Collections;
import java.util.List;

/**
 * Carries username, email, and realm_access.roles extracted from a validated Keycloak JWT.
 *
 * <p>Used by JitUserProvisioner (email for user creation) and KeycloakRoleSyncer (realmRoles for
 * sync). Immutable value object.
 */
public final class KeycloakTokenDetails {
  private final String username;
  private final String email; // may be null (service accounts)
  private final List<String> realmRoles; // from realm_access.roles; never null, may be empty
  private final long expiresAt; // epoch millis

  public KeycloakTokenDetails(
      String username, String email, List<String> realmRoles, long expiresAt) {
    this.username = username;
    this.email = email;
    this.realmRoles =
        realmRoles != null ? Collections.unmodifiableList(realmRoles) : Collections.emptyList();
    this.expiresAt = expiresAt;
  }

  public String getUsername() {
    return username;
  }

  public String getEmail() {
    return email;
  }

  public List<String> getRealmRoles() {
    return realmRoles;
  }

  public long getExpiresAt() {
    return expiresAt;
  }
}
