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
package com.dremio.dac.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.config.DremioConfig;
import com.dremio.exec.rbac.RbacEntityAlreadyExistsException;
import com.dremio.exec.rbac.RbacEntityNotFoundException;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.rbac.proto.RbacProto.Grant;
import com.dremio.exec.rbac.proto.RbacProto.Membership;
import com.dremio.exec.store.sys.accesscontrol.SysTableRoleInfo;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link RbacResource} REST endpoints.
 *
 * <p>Tests are pure unit tests that call resource methods directly (NOT via HTTP). All dependencies
 * are mocked with Mockito. Covers:
 *
 * <ul>
 *   <li>Admin-only enforcement: non-admin gets UserException (permission error)
 *   <li>RBAC-disabled enforcement: gets UserException (unsupported error)
 *   <li>Happy path for all 9 endpoints (REST-01 through REST-09)
 *   <li>Error handling: 404, 409, 400 for each relevant endpoint
 * </ul>
 */
public class RbacResourceTest {

  private RbacService rbacService;
  private SecurityContext securityContext;
  private DremioConfig dremioConfig;
  private Principal principal;
  private RbacResource resource;

  @Before
  public void setUp() {
    rbacService = mock(RbacService.class);
    securityContext = mock(SecurityContext.class);
    dremioConfig = mock(DremioConfig.class);
    principal = mock(Principal.class);

    when(securityContext.getUserPrincipal()).thenReturn(principal);
    when(principal.getName()).thenReturn("admin_user");
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.isAdminMember("admin_user")).thenReturn(true);

    resource = new RbacResource(rbacService, securityContext, dremioConfig);
  }

  // ---------------------------------------------------------------------------
  // Helper builders
  // ---------------------------------------------------------------------------

  private SysTableRoleInfo buildRoleInfo(String roleId, String type) {
    return new SysTableRoleInfo(roleId, roleId, type, null, null, "admin_user");
  }

  private Membership buildMembership(String userName, String roleId) {
    return Membership.newBuilder()
        .setUserName(userName)
        .setRoleId(roleId)
        .setGrantedBy("admin_user")
        .build();
  }

  private Grant buildGrant(String roleId, String objectType, String objectPath, String privilege) {
    return Grant.newBuilder()
        .setRoleId(roleId)
        .setObjectType(objectType)
        .setObjectPath(objectPath)
        .setPrivilege(privilege)
        .setGrantedBy("admin_user")
        .build();
  }

  // ---------------------------------------------------------------------------
  // Admin enforcement tests
  // ---------------------------------------------------------------------------

  @Test
  public void testListRoles_nonAdminReturns403() {
    when(rbacService.isAdminMember("admin_user")).thenReturn(false);

    assertThatThrownBy(() -> resource.listRoles())
        .isInstanceOf(UserException.class)
        .satisfies(
            e -> assertThat(((UserException) e).getErrorType().name()).isEqualTo("PERMISSION"));
  }

  @Test
  public void testCreateRole_nonAdminReturns403() {
    when(rbacService.isAdminMember("admin_user")).thenReturn(false);

    assertThatThrownBy(() -> resource.createRole(new CreateRoleRequest("analyst")))
        .isInstanceOf(UserException.class)
        .satisfies(
            e -> assertThat(((UserException) e).getErrorType().name()).isEqualTo("PERMISSION"));
  }

  // ---------------------------------------------------------------------------
  // RBAC-disabled tests
  // ---------------------------------------------------------------------------

  @Test
  public void testListRoles_rbacDisabledReturnsError() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(false);

    assertThatThrownBy(() -> resource.listRoles())
        .isInstanceOf(UserException.class)
        .satisfies(
            e ->
                assertThat(((UserException) e).getErrorType().name())
                    .isEqualTo("UNSUPPORTED_OPERATION"));
  }

  // ---------------------------------------------------------------------------
  // Happy path tests
  // ---------------------------------------------------------------------------

  /** REST-01: GET /rbac/roles returns all roles. */
  @Test
  public void testListRoles_returnsAllRoles() {
    List<SysTableRoleInfo> roleInfos =
        Arrays.asList(
            buildRoleInfo("ADMIN", "SYSTEM"),
            buildRoleInfo("PUBLIC", "SYSTEM"),
            buildRoleInfo("analyst", "USER"));
    when(rbacService.getRoleInfo()).thenReturn(roleInfos);

    ResponseList<RbacRole> response = resource.listRoles();

    assertThat(response.getData()).hasSize(3);
  }

  /** REST-02: POST /rbac/roles creates a role and returns it. */
  @Test
  public void testCreateRole_createsAndReturns() {
    RbacRole result = resource.createRole(new CreateRoleRequest("analyst"));

    verify(rbacService).createRole("analyst", "analyst", "admin_user");
    assertThat(result.getRoleId()).isEqualTo("analyst");
    assertThat(result.getType()).isEqualTo("USER");
  }

  /** REST-03: DELETE /rbac/roles/{name} deletes successfully and returns 204. */
  @Test
  public void testDeleteRole_deletesSuccessfully() throws RbacEntityNotFoundException {
    Response response = resource.deleteRole("analyst");

    verify(rbacService).deleteRole("analyst");
    assertThat(response.getStatus()).isEqualTo(204);
  }

  /** REST-04: GET /rbac/roles/{name}/members returns the members list. */
  @Test
  public void testListMembers_returnsMembers() {
    List<Membership> memberships =
        Arrays.asList(buildMembership("alice", "analyst"), buildMembership("bob", "analyst"));
    when(rbacService.listMembersByRole("analyst")).thenReturn(memberships);

    ResponseList<RbacMembership> response = resource.listMembers("analyst");

    assertThat(response.getData()).hasSize(2);
  }

  /** REST-05: POST /rbac/roles/{name}/members adds a user and returns the membership. */
  @Test
  public void testAddMember_addsSuccessfully() throws Exception {
    RbacMembership result = resource.addMember("analyst", new AddMemberRequest("alice"));

    verify(rbacService).addMembership("alice", "analyst", "admin_user");
    assertThat(result.getUserName()).isEqualTo("alice");
    assertThat(result.getRoleId()).isEqualTo("analyst");
  }

  /** REST-06: DELETE /rbac/roles/{name}/members/{userName} removes a member and returns 204. */
  @Test
  public void testRemoveMember_removesSuccessfully() throws RbacEntityNotFoundException {
    Response response = resource.removeMember("analyst", "alice");

    verify(rbacService).removeMembership("alice", "analyst");
    assertThat(response.getStatus()).isEqualTo(204);
  }

  /** REST-07: GET /rbac/grants?objectType=...&objectPath=... returns grants for the object. */
  @Test
  public void testListGrants_returnsGrants() {
    List<Grant> grants =
        Collections.singletonList(buildGrant("analyst", "VDS", "space.view1", "SELECT"));
    when(rbacService.listGrantsByObject("VDS", "space.view1")).thenReturn(grants);

    ResponseList<RbacGrant> response = resource.listGrants("VDS", "space.view1");

    assertThat(response.getData()).hasSize(1);
    assertThat(response.getData().get(0).getRoleId()).isEqualTo("analyst");
  }

  /** REST-08: POST /rbac/grants grants a privilege and returns the grant. */
  @Test
  public void testGrantPrivilege_grantsSuccessfully() throws Exception {
    GrantRequest request = new GrantRequest("analyst", "VDS", "space.view1", "SELECT");

    RbacGrant result = resource.grantPrivilege(request);

    verify(rbacService).grantPrivilege("analyst", "VDS", "space.view1", "SELECT", "admin_user");
    assertThat(result.getRoleId()).isEqualTo("analyst");
    assertThat(result.getObjectType()).isEqualTo("VDS");
    assertThat(result.getPrivilege()).isEqualTo("SELECT");
  }

  /** REST-09: DELETE /rbac/grants?... revokes privilege and returns 204. */
  @Test
  public void testRevokePrivilege_revokesSuccessfully() throws RbacEntityNotFoundException {
    Response response = resource.revokePrivilege("analyst", "VDS", "space.view1", "SELECT");

    verify(rbacService).revokePrivilege("analyst", "VDS", "space.view1", "SELECT");
    assertThat(response.getStatus()).isEqualTo(204);
  }

  // ---------------------------------------------------------------------------
  // Error handling tests
  // ---------------------------------------------------------------------------

  @Test
  public void testDeleteRole_notFoundReturns404() throws RbacEntityNotFoundException {
    doThrow(new RbacEntityNotFoundException("Role not found: analyst"))
        .when(rbacService)
        .deleteRole("analyst");

    assertThatThrownBy(() -> resource.deleteRole("analyst")).isInstanceOf(NotFoundException.class);
  }

  @Test
  public void testCreateRole_duplicateReturns409() {
    doThrow(new RbacEntityAlreadyExistsException("Role already exists: analyst"))
        .when(rbacService)
        .createRole("analyst", "analyst", "admin_user");

    assertThatThrownBy(() -> resource.createRole(new CreateRoleRequest("analyst")))
        .isInstanceOf(WebApplicationException.class)
        .satisfies(
            e ->
                assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(409));
  }

  @Test
  public void testListGrants_missingObjectTypeReturns400() {
    assertThatThrownBy(() -> resource.listGrants(null, "space.view1"))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  public void testListGrants_missingObjectPathReturns400() {
    assertThatThrownBy(() -> resource.listGrants("VDS", null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  public void testRevokePrivilege_missingRoleIdReturns400() {
    assertThatThrownBy(() -> resource.revokePrivilege(null, "VDS", "space.view1", "SELECT"))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  public void testRevokePrivilege_notFoundReturns404() throws RbacEntityNotFoundException {
    doThrow(new RbacEntityNotFoundException("Grant not found"))
        .when(rbacService)
        .revokePrivilege("analyst", "VDS", "space.view1", "SELECT");

    assertThatThrownBy(() -> resource.revokePrivilege("analyst", "VDS", "space.view1", "SELECT"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  public void testCreateRole_nullBodyReturns400() {
    assertThatThrownBy(() -> resource.createRole(null)).isInstanceOf(BadRequestException.class);
  }

  @Test
  public void testAddMember_nullBodyReturns400() {
    assertThatThrownBy(() -> resource.addMember("analyst", null))
        .isInstanceOf(BadRequestException.class);
  }
}
