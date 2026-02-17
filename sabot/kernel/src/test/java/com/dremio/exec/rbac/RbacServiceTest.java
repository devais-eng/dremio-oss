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
package com.dremio.exec.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.exec.store.sys.accesscontrol.SysTableMembershipInfo;
import com.dremio.exec.store.sys.accesscontrol.SysTablePrivilegeInfo;
import com.dremio.exec.store.sys.accesscontrol.SysTableRoleInfo;
import com.dremio.test.DremioTest;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Comprehensive unit tests for {@link RbacService}.
 *
 * <p>Covers all five phase success criteria plus immutability guards, listing conversions,
 * bootstrap, and fail-fast validation. Uses {@link LocalKVStoreProvider} for real KV store
 * behavior (no mocking).
 */
public class RbacServiceTest {

  private LocalKVStoreProvider kvStoreProvider;
  private RoleStore roleStore;
  private GrantStore grantStore;
  private MembershipStore membershipStore;
  private RbacService rbacService;

  @Before
  public void setUp() throws Exception {
    kvStoreProvider =
        new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    kvStoreProvider.start();
    roleStore = new RoleStore(() -> kvStoreProvider);
    grantStore = new GrantStore(() -> kvStoreProvider);
    membershipStore = new MembershipStore(() -> kvStoreProvider);
    rbacService = new RbacService(roleStore, grantStore, membershipStore);
  }

  @After
  public void tearDown() throws Exception {
    kvStoreProvider.close();
  }

  // ---------------------------------------------------------------------------
  // Success Criteria 1: Deny by default (ENFC-04/ENFC-05)
  // ---------------------------------------------------------------------------

  @Test
  public void testHasPrivilege_denyByDefault() {
    // No grants, no memberships -- hasPrivilege must return false
    boolean result = rbacService.hasPrivilege("alice", "SELECT", "VDS", "schemas.my_view");

    assertThat(result).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Success Criteria 2: ADMIN bypass (ROLE-05, ENFC-04)
  // ---------------------------------------------------------------------------

  @Test
  public void testHasPrivilege_adminBypass() {
    // Assign alice as ADMIN via bootstrap -- no specific grants needed
    rbacService.assignBootstrapAdmin("alice");

    boolean result = rbacService.hasPrivilege("alice", "SELECT", "VDS", "schemas.my_view");

    assertThat(result).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Success Criteria 3: PUBLIC implicit membership (ROLE-06, ENFC-05)
  // ---------------------------------------------------------------------------

  @Test
  public void testHasPrivilege_publicGrant() throws RbacEntityNotFoundException {
    // Grant SELECT on VDS to PUBLIC -- bob has NO explicit memberships
    rbacService.grantPrivilege(
        RbacService.PUBLIC_ROLE_ID, "VDS", "schemas.my_view", "SELECT", "admin");

    boolean result = rbacService.hasPrivilege("bob", "SELECT", "VDS", "schemas.my_view");

    assertThat(result).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Success Criteria 4: Bootstrap ADMIN assignment (BOOT-01)
  // ---------------------------------------------------------------------------

  @Test
  public void testAssignBootstrapAdmin() {
    rbacService.assignBootstrapAdmin("alice");

    // After bootstrap, alice should pass privilege check via ADMIN bypass
    boolean result = rbacService.hasPrivilege("alice", "SELECT", "VDS", "anything");

    assertThat(result).isTrue();
  }

  @Test
  public void testAssignBootstrapAdmin_duplicate() {
    rbacService.assignBootstrapAdmin("alice");

    // Second call for same user should throw
    assertThatThrownBy(() -> rbacService.assignBootstrapAdmin("alice"))
        .isInstanceOf(RbacEntityAlreadyExistsException.class);
  }

  // ---------------------------------------------------------------------------
  // Success Criteria 5: AccessControlListingManager (ROLE-05, ROLE-06)
  // ---------------------------------------------------------------------------

  @Test
  public void testGetRoleInfo_builtInRoles() {
    // No user-created roles -- should return exactly ADMIN and PUBLIC
    List<SysTableRoleInfo> roles =
        StreamSupport.stream(rbacService.getRoleInfo().spliterator(), false)
            .collect(Collectors.toList());

    assertThat(roles).hasSize(2);

    List<String> roleIds =
        roles.stream().map(SysTableRoleInfo::getRole_id).collect(Collectors.toList());
    assertThat(roleIds).containsExactlyInAnyOrder(RbacService.ADMIN_ROLE_ID, RbacService.PUBLIC_ROLE_ID);

    // Both should be SYSTEM type
    for (SysTableRoleInfo role : roles) {
      assertThat(role.getRole_type()).isEqualTo("SYSTEM");
    }
  }

  @Test
  public void testGetRoleInfo_withUserRoles() {
    rbacService.createRole("analyst", "Analyst", "admin");

    List<SysTableRoleInfo> roles =
        StreamSupport.stream(rbacService.getRoleInfo().spliterator(), false)
            .collect(Collectors.toList());

    assertThat(roles).hasSize(3);

    // Find the user-created role
    SysTableRoleInfo analystRole =
        roles.stream()
            .filter(r -> "analyst".equals(r.getRole_id()))
            .findFirst()
            .orElse(null);
    assertThat(analystRole).isNotNull();
    assertThat(analystRole.getRole_type()).isEqualTo("USER");
    assertThat(analystRole.getCreated_by()).isEqualTo("admin");

    // Built-in roles still present as SYSTEM
    List<String> systemRoleIds =
        roles.stream()
            .filter(r -> "SYSTEM".equals(r.getRole_type()))
            .map(SysTableRoleInfo::getRole_id)
            .collect(Collectors.toList());
    assertThat(systemRoleIds).containsExactlyInAnyOrder(RbacService.ADMIN_ROLE_ID, RbacService.PUBLIC_ROLE_ID);
  }

  @Test
  public void testGetPrivilegeInfo() throws RbacEntityNotFoundException {
    rbacService.createRole("analyst", "Analyst", "admin");
    rbacService.grantPrivilege("analyst", "VDS", "schemas.my_view", "SELECT", "admin");

    List<SysTablePrivilegeInfo> privileges =
        StreamSupport.stream(rbacService.getPrivilegeInfo().spliterator(), false)
            .collect(Collectors.toList());

    assertThat(privileges).hasSize(1);
    SysTablePrivilegeInfo priv = privileges.get(0);
    assertThat(priv.getGrantee_type()).isEqualTo("ROLE");
    assertThat(priv.getGrantee()).isEqualTo("analyst");
    assertThat(priv.getObject_type()).isEqualTo("VDS");
    assertThat(priv.getObject()).isEqualTo("schemas.my_view");
    assertThat(priv.getPrivilege()).isEqualTo("SELECT");
  }

  @Test
  public void testGetMembershipInfo_excludesPublic() throws RbacEntityNotFoundException {
    rbacService.createRole("analyst", "Analyst", "admin");
    rbacService.addMembership("alice", "analyst", "admin");

    List<SysTableMembershipInfo> memberships =
        StreamSupport.stream(rbacService.getMembershipInfo().spliterator(), false)
            .collect(Collectors.toList());

    // Should contain only the explicit analyst membership
    assertThat(memberships).hasSize(1);
    SysTableMembershipInfo info = memberships.get(0);
    assertThat(info.getRole_name()).isEqualTo("analyst");
    assertThat(info.getMember_name()).isEqualTo("alice");
    assertThat(info.getMember_type()).isEqualTo("USER");

    // No PUBLIC entry should exist
    List<String> roleNames =
        memberships.stream().map(SysTableMembershipInfo::getRole_name).collect(Collectors.toList());
    assertThat(roleNames).doesNotContain("PUBLIC");
  }

  // ---------------------------------------------------------------------------
  // Privilege resolution detail tests
  // ---------------------------------------------------------------------------

  @Test
  public void testHasPrivilege_explicitRoleGrant() throws RbacEntityNotFoundException {
    rbacService.createRole("analyst", "Analyst", "admin");
    rbacService.addMembership("alice", "analyst", "admin");
    rbacService.grantPrivilege("analyst", "VDS", "schemas.my_view", "SELECT", "admin");

    boolean result = rbacService.hasPrivilege("alice", "SELECT", "VDS", "schemas.my_view");

    assertThat(result).isTrue();
  }

  @Test
  public void testHasPrivilege_wrongPrivilege() throws RbacEntityNotFoundException {
    rbacService.createRole("analyst", "Analyst", "admin");
    rbacService.addMembership("alice", "analyst", "admin");
    rbacService.grantPrivilege("analyst", "VDS", "schemas.my_view", "SELECT", "admin");

    // EXECUTE is not granted -- should be denied
    boolean result = rbacService.hasPrivilege("alice", "EXECUTE", "VDS", "schemas.my_view");

    assertThat(result).isFalse();
  }

  @Test
  public void testHasPrivilege_multipleRolesOrLogic() throws RbacEntityNotFoundException {
    rbacService.createRole("analyst", "Analyst", "admin");
    rbacService.createRole("viewer", "Viewer", "admin");
    rbacService.addMembership("alice", "analyst", "admin");
    rbacService.addMembership("alice", "viewer", "admin");
    // Grant SELECT on different paths to different roles
    rbacService.grantPrivilege("analyst", "VDS", "schemas.view_a", "SELECT", "admin");
    rbacService.grantPrivilege("viewer", "VDS", "schemas.view_b", "SELECT", "admin");

    // alice should have access to both paths via OR logic across roles
    assertThat(rbacService.hasPrivilege("alice", "SELECT", "VDS", "schemas.view_a")).isTrue();
    assertThat(rbacService.hasPrivilege("alice", "SELECT", "VDS", "schemas.view_b")).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Immutability guard tests
  // ---------------------------------------------------------------------------

  @Test
  public void testCreateRole_rejectsAdmin() {
    assertThatThrownBy(() -> rbacService.createRole("ADMIN", "Admin", "anyone"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testCreateRole_rejectsPublic() {
    assertThatThrownBy(() -> rbacService.createRole("PUBLIC", "Public", "anyone"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testDeleteRole_rejectsAdmin() {
    assertThatThrownBy(() -> rbacService.deleteRole("ADMIN"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testDeleteRole_rejectsPublic() {
    assertThatThrownBy(() -> rbacService.deleteRole("PUBLIC"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testAddMembership_rejectsPublic() {
    assertThatThrownBy(() -> rbacService.addMembership("alice", "PUBLIC", "admin"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testRemoveMembership_rejectsAdmin() {
    assertThatThrownBy(() -> rbacService.removeMembership("alice", "ADMIN"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testRemoveMembership_rejectsPublic() {
    assertThatThrownBy(() -> rbacService.removeMembership("alice", "PUBLIC"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------
  // Fail-fast validation tests
  // ---------------------------------------------------------------------------

  @Test
  public void testValidateAdminMembersExist_throwsWhenEmpty() {
    // No ADMIN members exist
    assertThatThrownBy(() -> rbacService.validateAdminMembersExist())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADMIN role has no members");
  }

  @Test
  public void testValidateAdminMembersExist_passesWithMember() {
    rbacService.assignBootstrapAdmin("alice");

    // Should not throw
    rbacService.validateAdminMembersExist();
  }

  // ---------------------------------------------------------------------------
  // Role existence validation tests
  // ---------------------------------------------------------------------------

  @Test
  public void testGrantPrivilege_rejectsNonExistentRole() {
    assertThatThrownBy(
            () ->
                rbacService.grantPrivilege("nonexistent", "VDS", "path", "SELECT", "admin"))
        .isInstanceOf(RbacEntityNotFoundException.class);
  }

  @Test
  public void testAddMembership_rejectsNonExistentRole() {
    assertThatThrownBy(() -> rbacService.addMembership("alice", "nonexistent", "admin"))
        .isInstanceOf(RbacEntityNotFoundException.class);
  }

  @Test
  public void testAddMembership_allowsAdminRole() throws RbacEntityNotFoundException {
    // ADMIN is a valid built-in role -- should NOT throw
    rbacService.addMembership("alice", "ADMIN", "admin");

    // Verify the membership was actually created
    boolean result = rbacService.hasPrivilege("alice", "SELECT", "VDS", "anything");
    assertThat(result).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Revoke privilege test
  // ---------------------------------------------------------------------------

  @Test
  public void testRevokePrivilege() throws RbacEntityNotFoundException {
    rbacService.createRole("analyst", "Analyst", "admin");
    rbacService.addMembership("alice", "analyst", "admin");
    rbacService.grantPrivilege("analyst", "VDS", "schemas.my_view", "SELECT", "admin");

    // Alice should have privilege before revoke
    assertThat(rbacService.hasPrivilege("alice", "SELECT", "VDS", "schemas.my_view")).isTrue();

    // Revoke the privilege
    rbacService.revokePrivilege("analyst", "VDS", "schemas.my_view", "SELECT");

    // Alice should no longer have the privilege
    assertThat(rbacService.hasPrivilege("alice", "SELECT", "VDS", "schemas.my_view")).isFalse();
  }
}
