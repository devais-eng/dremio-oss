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
import com.dremio.test.DremioTest;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link GrantStore} CRUD operations and edge cases. */
public class GrantStoreTest {

  private LocalKVStoreProvider kvStoreProvider;
  private GrantStore grantStore;

  @Before
  public void setUp() throws Exception {
    kvStoreProvider = new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    kvStoreProvider.start();
    grantStore = new GrantStore(() -> kvStoreProvider);
  }

  @After
  public void tearDown() throws Exception {
    kvStoreProvider.close();
  }

  // Helper: build a Grant proto
  private static Grant buildGrant(
      String roleId, String objectType, String objectPath, String privilege) {
    return Grant.newBuilder()
        .setRoleId(roleId)
        .setObjectType(objectType)
        .setObjectPath(objectPath)
        .setPrivilege(privilege)
        .build();
  }

  @Test
  public void testGrantAndGet() {
    Grant grant = buildGrant("analyst", "VDS", "schemas.my_view", "SELECT");
    String key = RbacConfig.grantKey("analyst", "VDS", "schemas.my_view", "SELECT");

    grantStore.grant(key, grant);

    Grant result = grantStore.get(key);
    assertThat(result).isNotNull();
    assertThat(result.getRoleId()).isEqualTo("analyst");
    assertThat(result.getObjectType()).isEqualTo("VDS");
    assertThat(result.getObjectPath()).isEqualTo("schemas.my_view");
    assertThat(result.getPrivilege()).isEqualTo("SELECT");
  }

  @Test
  public void testGetNonExistent() {
    String key = RbacConfig.grantKey("unknown", "VDS", "some.view", "SELECT");

    Grant result = grantStore.get(key);

    assertThat(result).isNull();
  }

  @Test
  public void testDuplicateGrantThrows() {
    Grant grant = buildGrant("analyst", "VDS", "schemas.my_view", "SELECT");
    String key = RbacConfig.grantKey("analyst", "VDS", "schemas.my_view", "SELECT");

    grantStore.grant(key, grant);

    assertThatThrownBy(() -> grantStore.grant(key, grant))
        .isInstanceOf(RbacEntityAlreadyExistsException.class);
  }

  @Test
  public void testRevokeExistingGrant() throws RbacEntityNotFoundException {
    Grant grant = buildGrant("analyst", "VDS", "schemas.my_view", "SELECT");
    String key = RbacConfig.grantKey("analyst", "VDS", "schemas.my_view", "SELECT");

    grantStore.grant(key, grant);
    grantStore.revoke(key);

    assertThat(grantStore.get(key)).isNull();
  }

  @Test
  public void testRevokeNonExistentThrows() {
    String key = RbacConfig.grantKey("unknown", "VDS", "some.view", "SELECT");

    assertThatThrownBy(() -> grantStore.revoke(key))
        .isInstanceOf(RbacEntityNotFoundException.class);
  }

  @Test
  public void testListByRole() {
    // Grant 2 grants for "analyst" and 1 for "admin"
    grantStore.grant(
        RbacConfig.grantKey("analyst", "VDS", "schemas.view1", "SELECT"),
        buildGrant("analyst", "VDS", "schemas.view1", "SELECT"));
    grantStore.grant(
        RbacConfig.grantKey("analyst", "VDS", "schemas.view2", "SELECT"),
        buildGrant("analyst", "VDS", "schemas.view2", "SELECT"));
    grantStore.grant(
        RbacConfig.grantKey("admin", "VDS", "schemas.view1", "SELECT"),
        buildGrant("admin", "VDS", "schemas.view1", "SELECT"));

    List<Grant> analystGrants = grantStore.listByRole("analyst");
    List<Grant> adminGrants = grantStore.listByRole("admin");

    assertThat(analystGrants).hasSize(2);
    assertThat(adminGrants).hasSize(1);
  }

  @Test
  public void testListByRole_prefixSafety() {
    // roleId "dev" and roleId "devops" both have grants
    // listByRole("dev") must NOT return devops grants (prefix boundary safety)
    grantStore.grant(
        RbacConfig.grantKey("dev", "VDS", "schemas.view1", "SELECT"),
        buildGrant("dev", "VDS", "schemas.view1", "SELECT"));
    grantStore.grant(
        RbacConfig.grantKey("devops", "VDS", "schemas.view2", "SELECT"),
        buildGrant("devops", "VDS", "schemas.view2", "SELECT"));

    List<Grant> devGrants = grantStore.listByRole("dev");
    List<Grant> devopsGrants = grantStore.listByRole("devops");

    // "dev" must only return 1 grant, not the "devops" grant
    assertThat(devGrants).hasSize(1);
    assertThat(devGrants.get(0).getRoleId()).isEqualTo("dev");
    // "devops" must return its own 1 grant
    assertThat(devopsGrants).hasSize(1);
    assertThat(devopsGrants.get(0).getRoleId()).isEqualTo("devops");
  }

  @Test
  public void testListAll() {
    grantStore.grant(
        RbacConfig.grantKey("analyst", "VDS", "schemas.view1", "SELECT"),
        buildGrant("analyst", "VDS", "schemas.view1", "SELECT"));
    grantStore.grant(
        RbacConfig.grantKey("analyst", "FUNCTION", "my_udf", "EXECUTE"),
        buildGrant("analyst", "FUNCTION", "my_udf", "EXECUTE"));
    grantStore.grant(
        RbacConfig.grantKey("admin", "VDS", "schemas.view1", "SELECT"),
        buildGrant("admin", "VDS", "schemas.view1", "SELECT"));

    List<Grant> all = grantStore.listAll();

    assertThat(all).hasSize(3);
  }

  // ---------------------------------------------------------------------------
  // listByObject tests
  // ---------------------------------------------------------------------------

  @Test
  public void testListByObject_returnsMatchingGrants() {
    // 2 grants for VDS "space.view1" (different roles) and 1 for "space.view2"
    grantStore.grant(
        RbacConfig.grantKey("analyst", "VDS", "space.view1", "SELECT"),
        buildGrant("analyst", "VDS", "space.view1", "SELECT"));
    grantStore.grant(
        RbacConfig.grantKey("dev", "VDS", "space.view1", "SELECT"),
        buildGrant("dev", "VDS", "space.view1", "SELECT"));
    grantStore.grant(
        RbacConfig.grantKey("analyst", "VDS", "space.view2", "SELECT"),
        buildGrant("analyst", "VDS", "space.view2", "SELECT"));

    List<Grant> results = grantStore.listByObject("VDS", "space.view1");

    assertThat(results).hasSize(2);
    assertThat(results).extracting(Grant::getRoleId).containsExactlyInAnyOrder("analyst", "dev");
  }

  @Test
  public void testListByObject_returnsEmptyForNoMatches() {
    // Add grants for a different object
    grantStore.grant(
        RbacConfig.grantKey("analyst", "VDS", "space.other_view", "SELECT"),
        buildGrant("analyst", "VDS", "space.other_view", "SELECT"));

    List<Grant> results = grantStore.listByObject("VDS", "nonexistent.view");

    assertThat(results).isEmpty();
  }

  @Test
  public void testListByObject_filtersCorrectlyByObjectType() {
    // Same path, different object types
    grantStore.grant(
        RbacConfig.grantKey("analyst", "VDS", "space.obj", "SELECT"),
        buildGrant("analyst", "VDS", "space.obj", "SELECT"));
    grantStore.grant(
        RbacConfig.grantKey("analyst", "FUNCTION", "space.obj", "EXECUTE"),
        buildGrant("analyst", "FUNCTION", "space.obj", "EXECUTE"));

    List<Grant> vdsResults = grantStore.listByObject("VDS", "space.obj");
    List<Grant> functionResults = grantStore.listByObject("FUNCTION", "space.obj");

    // VDS query should return only VDS grant
    assertThat(vdsResults).hasSize(1);
    assertThat(vdsResults.get(0).getObjectType()).isEqualTo("VDS");

    // FUNCTION query should return only FUNCTION grant
    assertThat(functionResults).hasSize(1);
    assertThat(functionResults.get(0).getObjectType()).isEqualTo("FUNCTION");
  }
}
