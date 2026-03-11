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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.config.DremioConfig;
import com.dremio.dac.model.usergroup.UserName;
import com.dremio.exec.rbac.RbacService;
import com.dremio.service.users.SimpleUser;
import com.dremio.service.users.User;
import javax.ws.rs.core.SecurityContext;
import org.junit.Test;

/**
 * Unit tests for {@link DACSecurityContext#isUserInRole(String)} covering all RBAC flag and
 * null-guard combinations. Phase 28: DACSecurityContext role enforcement.
 */
public class TestDACSecurityContext {

  private static final String BOB = "bob";
  private static final String ALICE = "alice";

  private static User buildUser(String userName) {
    return SimpleUser.newBuilder().setUserName(userName).build();
  }

  private DACSecurityContext context(
      String userName, RbacService rbacService, DremioConfig dremioConfig) {
    return new DACSecurityContext(
        new UserName(userName), buildUser(userName), null, rbacService, dremioConfig);
  }

  // ---------------------------------------------------------------------------
  // admin role — RBAC enabled, non-admin user → must return false
  // ---------------------------------------------------------------------------

  @Test
  public void testIsUserInRole_admin_rbacEnabled_nonAdmin_returnsFalse() {
    RbacService rbacService = mock(RbacService.class);
    DremioConfig dremioConfig = mock(DremioConfig.class);
    when(rbacService.isAdminMember(BOB)).thenReturn(false);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);

    DACSecurityContext ctx = context(BOB, rbacService, dremioConfig);
    assertFalse("Non-admin user should not be in admin role when RBAC is enabled",
        ctx.isUserInRole("admin"));
  }

  // ---------------------------------------------------------------------------
  // admin role — RBAC enabled, admin user → must return true
  // ---------------------------------------------------------------------------

  @Test
  public void testIsUserInRole_admin_rbacEnabled_admin_returnsTrue() {
    RbacService rbacService = mock(RbacService.class);
    DremioConfig dremioConfig = mock(DremioConfig.class);
    when(rbacService.isAdminMember(ALICE)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);

    DACSecurityContext ctx = context(ALICE, rbacService, dremioConfig);
    assertTrue("Admin user should be in admin role when RBAC is enabled",
        ctx.isUserInRole("admin"));
  }

  // ---------------------------------------------------------------------------
  // admin role — rbacService null → fallback to true (open-by-default)
  // ---------------------------------------------------------------------------

  @Test
  public void testIsUserInRole_admin_rbacServiceNull_returnsTrue() {
    DremioConfig dremioConfig = mock(DremioConfig.class);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);

    DACSecurityContext ctx = context(BOB, null, dremioConfig);
    assertTrue("Null rbacService should fall back to true (open-by-default)",
        ctx.isUserInRole("admin"));
  }

  // ---------------------------------------------------------------------------
  // admin role — dremioConfig null → fallback to true (open-by-default)
  // ---------------------------------------------------------------------------

  @Test
  public void testIsUserInRole_admin_dremioConfigNull_returnsTrue() {
    RbacService rbacService = mock(RbacService.class);
    when(rbacService.isAdminMember(BOB)).thenReturn(false);

    DACSecurityContext ctx = context(BOB, rbacService, null);
    assertTrue("Null dremioConfig should fall back to true (open-by-default)",
        ctx.isUserInRole("admin"));
  }

  // ---------------------------------------------------------------------------
  // admin role — RBAC disabled → fallback to true (backward-compatible)
  // ---------------------------------------------------------------------------

  @Test
  public void testIsUserInRole_admin_rbacDisabled_returnsTrue() {
    RbacService rbacService = mock(RbacService.class);
    DremioConfig dremioConfig = mock(DremioConfig.class);
    when(rbacService.isAdminMember(BOB)).thenReturn(false);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(false);

    DACSecurityContext ctx = context(BOB, rbacService, dremioConfig);
    assertTrue("RBAC disabled should fall back to true (backward-compatible)",
        ctx.isUserInRole("admin"));
  }

  // ---------------------------------------------------------------------------
  // "user" role — always returns true regardless of admin membership
  // ---------------------------------------------------------------------------

  @Test
  public void testIsUserInRole_user_returnsTrue() {
    RbacService rbacService = mock(RbacService.class);
    DremioConfig dremioConfig = mock(DremioConfig.class);
    // non-admin user
    when(rbacService.isAdminMember(BOB)).thenReturn(false);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);

    DACSecurityContext ctx = context(BOB, rbacService, dremioConfig);
    assertTrue("'user' role should always return true for any authenticated user",
        ctx.isUserInRole("user"));
  }

  // ---------------------------------------------------------------------------
  // any other role string — always returns true
  // ---------------------------------------------------------------------------

  @Test
  public void testIsUserInRole_otherRole_returnsTrue() {
    RbacService rbacService = mock(RbacService.class);
    DremioConfig dremioConfig = mock(DremioConfig.class);
    when(rbacService.isAdminMember(BOB)).thenReturn(false);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);

    DACSecurityContext ctx = context(BOB, rbacService, dremioConfig);
    assertTrue("Any non-'admin' role string should always return true",
        ctx.isUserInRole("some_custom_role"));
  }

  // ---------------------------------------------------------------------------
  // system() factory — null rbacService → isUserInRole("admin") returns true
  // ---------------------------------------------------------------------------

  @Test
  public void testSystem_isUserInRole_admin_returnsTrue() {
    SecurityContext systemCtx = DACSecurityContext.system();
    assertTrue("system() context must return true for 'admin' role (null rbacService fallback)",
        systemCtx.isUserInRole("admin"));
  }
}
