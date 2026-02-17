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
import com.dremio.exec.rbac.proto.RbacProto.Grant;
import com.dremio.exec.rbac.proto.RbacProto.Membership;
import com.dremio.exec.rbac.proto.RbacProto.Role;
import com.dremio.test.DremioTest;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link RoleStore} CRUD operations and edge cases. */
public class RoleStoreTest {

  private LocalKVStoreProvider kvStoreProvider;
  private RoleStore roleStore;
  private GrantStore grantStore;
  private MembershipStore membershipStore;

  @Before
  public void setUp() throws Exception {
    kvStoreProvider =
        new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    kvStoreProvider.start();
    roleStore = new RoleStore(() -> kvStoreProvider);
    grantStore = new GrantStore(() -> kvStoreProvider);
    membershipStore = new MembershipStore(() -> kvStoreProvider);
  }

  @After
  public void tearDown() throws Exception {
    kvStoreProvider.close();
  }

  // Helper: build a simple Role proto
  private static Role buildRole(String roleId, String roleName) {
    return Role.newBuilder().setRoleId(roleId).setRoleName(roleName).build();
  }

  // Helper: build a Grant proto
  private static Grant buildGrant(String roleId, String objectType, String objectPath, String privilege) {
    return Grant.newBuilder()
        .setRoleId(roleId)
        .setObjectType(objectType)
        .setObjectPath(objectPath)
        .setPrivilege(privilege)
        .build();
  }

  // Helper: build a Membership proto
  private static Membership buildMembership(String userName, String roleId) {
    return Membership.newBuilder().setUserName(userName).setRoleId(roleId).build();
  }

  @Test
  public void testCreateAndGet() {
    Role role = buildRole("analyst", "Analyst");
    roleStore.create("analyst", role);

    Role result = roleStore.get("analyst");

    assertThat(result).isNotNull();
    assertThat(result.getRoleId()).isEqualTo("analyst");
    assertThat(result.getRoleName()).isEqualTo("Analyst");
  }

  @Test
  public void testGetNonExistent() {
    Role result = roleStore.get("unknown");

    assertThat(result).isNull();
  }

  @Test
  public void testDuplicateRoleThrows() {
    Role role = buildRole("analyst", "Analyst");
    roleStore.create("analyst", role);

    assertThatThrownBy(() -> roleStore.create("analyst", role))
        .isInstanceOf(RbacEntityAlreadyExistsException.class);
  }

  @Test
  public void testDeleteExistingRole() throws RbacEntityNotFoundException {
    Role role = buildRole("analyst", "Analyst");
    roleStore.create("analyst", role);

    roleStore.delete("analyst", grantStore, membershipStore);

    assertThat(roleStore.get("analyst")).isNull();
  }

  @Test
  public void testDeleteNonExistentThrows() {
    assertThatThrownBy(() -> roleStore.delete("unknown", grantStore, membershipStore))
        .isInstanceOf(RbacEntityNotFoundException.class);
  }

  @Test
  public void testCascadeDelete() throws RbacEntityNotFoundException {
    // Create a role
    roleStore.create("analyst", buildRole("analyst", "Analyst"));

    // Grant a privilege for the role
    String grantKey = RbacConfig.grantKey("analyst", "VDS", "schemas.my_view", "SELECT");
    grantStore.grant(grantKey, buildGrant("analyst", "VDS", "schemas.my_view", "SELECT"));

    // Add a membership for the role
    String membershipKey = RbacConfig.membershipKey("alice", "analyst");
    membershipStore.add(membershipKey, buildMembership("alice", "analyst"));

    // Verify they exist before deletion
    assertThat(grantStore.get(grantKey)).isNotNull();
    assertThat(membershipStore.get(membershipKey)).isNotNull();

    // Delete the role (should cascade)
    roleStore.delete("analyst", grantStore, membershipStore);

    // Role is gone
    assertThat(roleStore.get("analyst")).isNull();
    // Grant is gone (cascade)
    assertThat(grantStore.get(grantKey)).isNull();
    // Membership is gone (cascade)
    assertThat(membershipStore.get(membershipKey)).isNull();
  }

  @Test
  public void testListAll() {
    roleStore.create("analyst", buildRole("analyst", "Analyst"));
    roleStore.create("admin", buildRole("admin", "Admin"));
    roleStore.create("developer", buildRole("developer", "Developer"));

    List<Role> roles = roleStore.listAll();

    assertThat(roles).hasSize(3);
  }

  @Test
  public void testInputValidation_nullRoleId() {
    Role role = buildRole("analyst", "Analyst");

    assertThatThrownBy(() -> roleStore.create(null, role))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
