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

import com.dremio.exec.rbac.RbacEntityAlreadyExistsException;
import com.dremio.exec.rbac.RbacEntityNotFoundException;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.rbac.RoleStore;
import com.dremio.exec.rbac.proto.RbacProto.Membership;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Syncs Keycloak {@code realm_access.roles} to Dremio RBAC memberships on each Keycloak login.
 *
 * <p>Supports two modes, controlled by {@link KeycloakConfig#isAdditive()}:
 *
 * <ul>
 *   <li><b>Additive:</b> Grants Keycloak roles that are absent in Dremio; never removes any
 *       membership. Manually-assigned roles (source={@code ""}) are always preserved.
 *   <li><b>Authoritative:</b> Grants new Keycloak roles AND revokes keycloak-sourced memberships
 *       ({@code source="keycloak"}) that are no longer present in the token. Manually-assigned
 *       memberships ({@code source=""}) are never touched.
 * </ul>
 *
 * <p>Role validation (ROLE-04): Only Keycloak roles that match an existing Dremio RBAC role are
 * granted. Unknown Keycloak roles (e.g. {@code realm-management}, {@code offline_access}) are
 * silently ignored. The built-in ADMIN role is exempt from the existence check.
 *
 * <p>Idempotency: Calling {@link #syncRoles} twice with the same roles produces no errors —
 * duplicate {@link RbacEntityAlreadyExistsException} is silently swallowed.
 *
 * <p>Exception safety: {@link #syncRoles} never throws to its caller. Any role sync failure is
 * logged at WARN level and the request continues normally.
 */
public class KeycloakRoleSyncer {

  private static final Logger logger = LoggerFactory.getLogger(KeycloakRoleSyncer.class);

  private final Provider<RbacService> rbacServiceProvider;
  private final Provider<RoleStore> roleStoreProvider;
  private final KeycloakConfig keycloakConfig;

  /**
   * Constructs a KeycloakRoleSyncer with lazy providers.
   *
   * <p>Providers are used because RbacService and RoleStore are bound later in the DACDaemonModule
   * lifecycle than the Keycloak bindings in setupUserService().
   *
   * @param rbacServiceProvider provider for the RBAC service for membership reads and writes
   * @param roleStoreProvider provider for the role store for role existence checks (ROLE-04)
   * @param keycloakConfig the Keycloak config supplying the sync mode
   */
  public KeycloakRoleSyncer(
      Provider<RbacService> rbacServiceProvider,
      Provider<RoleStore> roleStoreProvider,
      KeycloakConfig keycloakConfig) {
    this.rbacServiceProvider = rbacServiceProvider;
    this.roleStoreProvider = roleStoreProvider;
    this.keycloakConfig = keycloakConfig;
  }

  /**
   * Syncs {@code keycloakRoles} (from {@code realm_access.roles}) to Dremio RBAC memberships for
   * the given user.
   *
   * <p>This method is exception-safe: any error during sync is logged at WARN and silently
   * swallowed to ensure role sync failure never returns 401 to the API caller.
   *
   * @param username the Dremio username (from {@code preferred_username} claim)
   * @param keycloakRoles the list of role names from the Keycloak JWT
   */
  public void syncRoles(String username, List<String> keycloakRoles) {
    try {
      syncRolesInternal(username, keycloakRoles);
    } catch (Exception e) {
      // Role sync must never abort the request (ROLE-04 spirit: graceful degradation)
      logger.warn("Role sync failed for user '{}': {}", username, e.getMessage());
    }
  }

  private void syncRolesInternal(String username, List<String> keycloakRoles)
      throws RbacEntityNotFoundException {
    final RbacService rbacService = rbacServiceProvider.get();
    final RoleStore roleStore = roleStoreProvider.get();

    // ROLE-04: Filter to only roles that exist in Dremio RBAC.
    // ADMIN is a synthetic built-in role (not in roleStore) -- check separately.
    Set<String> validKeycloakRoles = new HashSet<>();
    for (String role : keycloakRoles) {
      if (RbacService.ADMIN_ROLE_ID.equals(role) || roleStore.get(role) != null) {
        validKeycloakRoles.add(role);
      } else {
        logger.debug("Keycloak role '{}' has no Dremio RBAC role -- skipping", role);
      }
    }

    // Get current memberships for the user and find which are already keycloak-sourced
    List<Membership> currentMemberships = rbacService.listMembershipsByUser(username);
    Set<String> keycloakSourcedRoles =
        currentMemberships.stream()
            .filter(m -> "keycloak".equals(m.getSource()))
            .map(Membership::getRoleId)
            .collect(Collectors.toSet());

    // Grant roles present in token but not yet keycloak-sourced in Dremio
    for (String roleId : validKeycloakRoles) {
      if (!keycloakSourcedRoles.contains(roleId)) {
        try {
          rbacService.addMembership(username, roleId, "keycloak", "keycloak");
        } catch (RbacEntityAlreadyExistsException e) {
          // Already exists (manually assigned or concurrent sync) -- silently ignore
          logger.debug("Membership already exists for {}:{} -- skipping", username, roleId);
        }
      }
    }

    // Authoritative mode: revoke keycloak-sourced roles no longer in the token (ROLE-03)
    // Additive mode: manually-assigned roles (source="") are never touched (ROLE-02)
    if (!keycloakConfig.isAdditive()) {
      for (String existingKcRole : keycloakSourcedRoles) {
        if (!validKeycloakRoles.contains(existingKcRole)) {
          try {
            rbacService.removeMembership(username, existingKcRole);
          } catch (RbacEntityNotFoundException e) {
            logger.debug(
                "Membership already removed for {}:{} -- skipping", username, existingKcRole);
          }
        }
      }
    }
  }
}
