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
package com.dremio.dac.server;

import com.dremio.config.DremioConfig;
import com.dremio.dac.model.usergroup.UserName;
import com.dremio.dac.model.usergroup.UserResourcePath;
import com.dremio.dac.model.usergroup.UserUI;
import com.dremio.exec.rbac.RbacService;
import com.dremio.service.users.SystemUser;
import com.dremio.service.users.User;
import java.security.Principal;
import javax.annotation.Nullable;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.SecurityContext;

/** Dac security context. */
public class DACSecurityContext implements SecurityContext {

  private final UserUI user;
  private final ContainerRequestContext requestContext;
  @Nullable private final RbacService rbacService;
  @Nullable private final DremioConfig dremioConfig;

  /**
   * Full constructor. Pass non-null {@code rbacService} and {@code dremioConfig} to enable RBAC
   * admin membership checking in {@link #isUserInRole(String)}. Passing null for either falls back
   * to open-by-default (pre-RBAC) behavior.
   */
  public DACSecurityContext(
      final UserName userName,
      final User user,
      ContainerRequestContext requestContext,
      @Nullable RbacService rbacService,
      @Nullable DremioConfig dremioConfig) {
    this.user = new UserUI(new UserResourcePath(userName), userName, user);
    this.requestContext = requestContext;
    this.rbacService = rbacService;
    this.dremioConfig = dremioConfig;
  }

  /**
   * Backward-compatible 3-arg constructor. Delegates to the 5-arg constructor with null rbacService
   * and null dremioConfig, preserving pre-RBAC open-by-default behavior.
   */
  public DACSecurityContext(
      final UserName userName, final User user, ContainerRequestContext requestContext) {
    this(userName, user, requestContext, null, null);
  }

  @Override
  public Principal getUserPrincipal() {
    return user;
  }

  /**
   * Returns whether the current user is in the given role.
   *
   * <p>Only the {@code "admin"} role is mapped to RBAC admin membership. All other role strings
   * (including {@code "user"}) return {@code true} unconditionally — they represent "authenticated"
   * in the JAX-RS convention and are not stored in the RBAC store.
   *
   * <p>When RBAC is not wired ({@code rbacService} or {@code dremioConfig} is null) or is disabled
   * via the {@code services.rbac.enabled} flag, this method falls back to {@code true} to preserve
   * pre-RBAC open-by-default behavior.
   */
  @Override
  public boolean isUserInRole(String role) {
    // Only "admin" is mapped to RBAC membership. All other roles mean "authenticated".
    if (!"admin".equals(role)) {
      return true;
    }
    // RBAC not wired or disabled: fall back to open (pre-RBAC behavior)
    if (rbacService == null
        || dremioConfig == null
        || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
      return true;
    }
    // Delegate to RBAC store. UserUI implements Principal so user.getName() returns the username.
    return rbacService.isAdminMember(user.getName());
  }

  @Override
  public boolean isSecure() {
    return requestContext.getSecurityContext().isSecure();
  }

  @Override
  public String getAuthenticationScheme() {
    return requestContext.getSecurityContext().getAuthenticationScheme();
  }

  /**
   * Creates a security context for internal/system operations. The null rbacService ensures {@link
   * #isUserInRole(String)} always returns {@code true}, so system operations bypass all role
   * checks.
   */
  public static SecurityContext system() {
    return new DACSecurityContext(
        new UserName(SystemUser.SYSTEM_USERNAME), SystemUser.SYSTEM_USER, null, null, null);
  }
}
