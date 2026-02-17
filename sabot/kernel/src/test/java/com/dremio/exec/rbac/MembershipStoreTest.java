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
import com.dremio.exec.rbac.proto.RbacProto.Membership;
import com.dremio.test.DremioTest;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link MembershipStore} CRUD operations and edge cases. */
public class MembershipStoreTest {

  private LocalKVStoreProvider kvStoreProvider;
  private MembershipStore membershipStore;

  @Before
  public void setUp() throws Exception {
    kvStoreProvider =
        new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    kvStoreProvider.start();
    membershipStore = new MembershipStore(() -> kvStoreProvider);
  }

  @After
  public void tearDown() throws Exception {
    kvStoreProvider.close();
  }

  // Helper: build a Membership proto
  private static Membership buildMembership(String userName, String roleId) {
    return Membership.newBuilder().setUserName(userName).setRoleId(roleId).build();
  }

  @Test
  public void testAddAndGet() {
    Membership membership = buildMembership("alice", "analyst");
    String key = RbacConfig.membershipKey("alice", "analyst");

    membershipStore.add(key, membership);

    Membership result = membershipStore.get(key);
    assertThat(result).isNotNull();
    assertThat(result.getUserName()).isEqualTo("alice");
    assertThat(result.getRoleId()).isEqualTo("analyst");
  }

  @Test
  public void testGetNonExistent() {
    String key = RbacConfig.membershipKey("unknown", "analyst");

    Membership result = membershipStore.get(key);

    assertThat(result).isNull();
  }

  @Test
  public void testDuplicateMembershipThrows() {
    Membership membership = buildMembership("alice", "analyst");
    String key = RbacConfig.membershipKey("alice", "analyst");

    membershipStore.add(key, membership);

    assertThatThrownBy(() -> membershipStore.add(key, membership))
        .isInstanceOf(RbacEntityAlreadyExistsException.class);
  }

  @Test
  public void testRemoveExistingMembership() throws RbacEntityNotFoundException {
    Membership membership = buildMembership("alice", "analyst");
    String key = RbacConfig.membershipKey("alice", "analyst");

    membershipStore.add(key, membership);
    membershipStore.remove(key);

    assertThat(membershipStore.get(key)).isNull();
  }

  @Test
  public void testRemoveNonExistentThrows() {
    String key = RbacConfig.membershipKey("unknown", "analyst");

    assertThatThrownBy(() -> membershipStore.remove(key))
        .isInstanceOf(RbacEntityNotFoundException.class);
  }

  @Test
  public void testListByUser() {
    // alice is in analyst and admin; bob is only in analyst
    membershipStore.add(
        RbacConfig.membershipKey("alice", "analyst"), buildMembership("alice", "analyst"));
    membershipStore.add(
        RbacConfig.membershipKey("alice", "admin"), buildMembership("alice", "admin"));
    membershipStore.add(
        RbacConfig.membershipKey("bob", "analyst"), buildMembership("bob", "analyst"));

    List<Membership> aliceMemberships = membershipStore.listByUser("alice");
    List<Membership> bobMemberships = membershipStore.listByUser("bob");

    assertThat(aliceMemberships).hasSize(2);
    assertThat(bobMemberships).hasSize(1);
  }

  @Test
  public void testListByRole() {
    // alice→analyst, bob→analyst, carol→admin
    membershipStore.add(
        RbacConfig.membershipKey("alice", "analyst"), buildMembership("alice", "analyst"));
    membershipStore.add(
        RbacConfig.membershipKey("bob", "analyst"), buildMembership("bob", "analyst"));
    membershipStore.add(
        RbacConfig.membershipKey("carol", "admin"), buildMembership("carol", "admin"));

    List<Membership> analystMembers = membershipStore.listByRole("analyst");
    List<Membership> adminMembers = membershipStore.listByRole("admin");

    assertThat(analystMembers).hasSize(2);
    assertThat(adminMembers).hasSize(1);
  }

  @Test
  public void testListAll() {
    membershipStore.add(
        RbacConfig.membershipKey("alice", "analyst"), buildMembership("alice", "analyst"));
    membershipStore.add(
        RbacConfig.membershipKey("bob", "analyst"), buildMembership("bob", "analyst"));
    membershipStore.add(
        RbacConfig.membershipKey("carol", "admin"), buildMembership("carol", "admin"));

    List<Membership> all = membershipStore.listAll();

    assertThat(all).hasSize(3);
  }
}
