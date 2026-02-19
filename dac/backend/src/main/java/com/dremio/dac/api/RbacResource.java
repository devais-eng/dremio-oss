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

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import com.dremio.common.exceptions.UserException;
import com.dremio.config.DremioConfig;
import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.exec.rbac.RbacEntityAlreadyExistsException;
import com.dremio.exec.rbac.RbacEntityNotFoundException;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.store.sys.accesscontrol.SysTableRoleInfo;
import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

/**
 * JAX-RS REST resource for RBAC management at {@code /api/v3/rbac}.
 *
 * <p>Exposes 9 endpoints for managing roles, memberships, and grants:
 *
 * <ul>
 *   <li>GET /rbac/roles -- list all roles (REST-01)
 *   <li>POST /rbac/roles -- create a role (REST-02)
 *   <li>DELETE /rbac/roles/{name} -- delete a role (REST-03)
 *   <li>GET /rbac/roles/{name}/members -- list members of a role (REST-04)
 *   <li>POST /rbac/roles/{name}/members -- add a user to a role (REST-05)
 *   <li>DELETE /rbac/roles/{name}/members/{userName} -- remove a user from a role (REST-06)
 *   <li>GET /rbac/grants?objectType=...&objectPath=... -- list grants on an object (REST-07)
 *   <li>POST /rbac/grants -- grant a privilege (REST-08)
 *   <li>DELETE /rbac/grants?roleId=...&objectType=...&objectPath=...&privilege=... (REST-09)
 * </ul>
 *
 * <p><strong>Admin enforcement:</strong> All endpoints perform a programmatic admin check via
 * {@link RbacService#isAdminMember(String)}. The {@code @RolesAllowed} annotation is present only
 * for convention -- {@code DACSecurityContext.isUserInRole()} always returns {@code true} in Dremio
 * OSS and does not enforce access control.
 *
 * <p><strong>Feature flag:</strong> All endpoints check {@link DremioConfig#RBAC_ENABLED} via
 * {@link #requireRbacEnabled()} before processing.
 */
@APIResource
@Secured
@RolesAllowed({"user", "admin"}) // No-op: isUserInRole() always returns true; programmatic check
@Path("/rbac")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class RbacResource {

  private final RbacService rbacService;
  private final SecurityContext securityContext;
  private final DremioConfig dremioConfig;

  @Inject
  public RbacResource(
      RbacService rbacService, SecurityContext securityContext, DremioConfig dremioConfig) {
    this.rbacService = rbacService;
    this.securityContext = securityContext;
    this.dremioConfig = dremioConfig;
  }

  // ---------------------------------------------------------------------------
  // Role endpoints
  // ---------------------------------------------------------------------------

  /**
   * Lists all RBAC roles (synthetic ADMIN, PUBLIC, and user-created).
   *
   * @return list of all roles (REST-01)
   */
  @GET
  @Path("/roles")
  public ResponseList<RbacRole> listRoles() {
    requireRbacEnabled();
    requireAdmin();
    List<RbacRole> roles = new ArrayList<>();
    for (SysTableRoleInfo info : rbacService.getRoleInfo()) {
      roles.add(RbacRole.fromSysTableRoleInfo(info));
    }
    return new ResponseList<>(roles);
  }

  /**
   * Creates a new user-defined role. The role ID is the slugified role name (role ID = name per
   * locked decision; Dremio OSS does not support separate slug vs display name).
   *
   * @param request request body containing the role name
   * @return the created role (REST-02)
   * @throws BadRequestException if role name is null/empty or matches a built-in role
   * @throws WebApplicationException (409) if a role with the same name already exists
   */
  @POST
  @Path("/roles")
  public RbacRole createRole(CreateRoleRequest request) {
    requireRbacEnabled();
    requireAdmin();
    if (request == null || Strings.isNullOrEmpty(request.getRoleName())) {
      throw new BadRequestException("Request body must include a non-empty 'roleName'");
    }
    String roleName = request.getRoleName();
    try {
      rbacService.createRole(roleName, roleName, getUserName());
    } catch (RbacEntityAlreadyExistsException e) {
      throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    return new RbacRole(roleName, roleName, "USER", getUserName());
  }

  /**
   * Deletes a user-defined role (with cascade: removes all grants and memberships for it).
   *
   * @param name the role ID/name to delete
   * @return 204 No Content (REST-03)
   * @throws NotFoundException if the role does not exist
   * @throws BadRequestException if the name is a built-in role (ADMIN, PUBLIC)
   */
  @DELETE
  @Path("/roles/{name}")
  public Response deleteRole(@PathParam("name") String name) {
    requireRbacEnabled();
    requireAdmin();
    try {
      rbacService.deleteRole(name);
    } catch (RbacEntityNotFoundException e) {
      throw new NotFoundException(e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    return Response.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // Membership endpoints
  // ---------------------------------------------------------------------------

  /**
   * Lists all explicit members of the given role.
   *
   * @param name the role ID/name
   * @return list of memberships (REST-04)
   */
  @GET
  @Path("/roles/{name}/members")
  public ResponseList<RbacMembership> listMembers(@PathParam("name") String name) {
    requireRbacEnabled();
    requireAdmin();
    List<RbacMembership> memberships = new ArrayList<>();
    for (com.dremio.exec.rbac.proto.RbacProto.Membership m : rbacService.listMembersByRole(name)) {
      memberships.add(RbacMembership.fromProto(m));
    }
    return new ResponseList<>(memberships);
  }

  /**
   * Adds a user to a role.
   *
   * @param name the role ID/name
   * @param request request body containing the userName to add
   * @return the created membership (REST-05)
   * @throws NotFoundException if the role does not exist
   * @throws WebApplicationException (409) if the membership already exists
   */
  @POST
  @Path("/roles/{name}/members")
  public RbacMembership addMember(@PathParam("name") String name, AddMemberRequest request) {
    requireRbacEnabled();
    requireAdmin();
    if (request == null || Strings.isNullOrEmpty(request.getUserName())) {
      throw new BadRequestException("Request body must include a non-empty 'userName'");
    }
    String userName = request.getUserName();
    try {
      rbacService.addMembership(userName, name, getUserName());
    } catch (RbacEntityNotFoundException e) {
      throw new NotFoundException(e.getMessage());
    } catch (RbacEntityAlreadyExistsException e) {
      throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    return new RbacMembership(userName, name, getUserName());
  }

  /**
   * Removes a user from a role.
   *
   * @param name the role ID/name
   * @param userName the user to remove
   * @return 204 No Content (REST-06)
   * @throws NotFoundException if the membership does not exist
   * @throws BadRequestException if the role is a built-in role (ADMIN, PUBLIC)
   */
  @DELETE
  @Path("/roles/{name}/members/{userName}")
  public Response removeMember(
      @PathParam("name") String name, @PathParam("userName") String userName) {
    requireRbacEnabled();
    requireAdmin();
    try {
      rbacService.removeMembership(userName, name);
    } catch (RbacEntityNotFoundException e) {
      throw new NotFoundException(e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    return Response.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // Grant endpoints
  // ---------------------------------------------------------------------------

  /**
   * Lists all grants on a specific object (across all roles).
   *
   * @param objectType the object type (e.g. "VDS", "FUNCTION") -- required query parameter
   * @param objectPath the dot-delimited object path (e.g. "myspace.myview") -- required query param
   * @return list of grants on the object (REST-07)
   * @throws BadRequestException if objectType or objectPath is null/empty
   */
  @GET
  @Path("/grants")
  public ResponseList<RbacGrant> listGrants(
      @QueryParam("objectType") String objectType, @QueryParam("objectPath") String objectPath) {
    requireRbacEnabled();
    requireAdmin();
    if (Strings.isNullOrEmpty(objectType)) {
      throw new BadRequestException("Query parameter 'objectType' is required");
    }
    if (Strings.isNullOrEmpty(objectPath)) {
      throw new BadRequestException("Query parameter 'objectPath' is required");
    }
    List<RbacGrant> grants = new ArrayList<>();
    for (com.dremio.exec.rbac.proto.RbacProto.Grant g :
        rbacService.listGrantsByObject(objectType, objectPath)) {
      grants.add(RbacGrant.fromProto(g));
    }
    return new ResponseList<>(grants);
  }

  /**
   * Grants a privilege on an object to a role.
   *
   * @param request request body with roleId, objectType, objectPath, privilege
   * @return the created grant (REST-08)
   * @throws NotFoundException if the role does not exist
   * @throws WebApplicationException (409) if the grant already exists
   */
  @POST
  @Path("/grants")
  public RbacGrant grantPrivilege(GrantRequest request) {
    requireRbacEnabled();
    requireAdmin();
    if (request == null) {
      throw new BadRequestException("Request body is required");
    }
    if (Strings.isNullOrEmpty(request.getRoleId())) {
      throw new BadRequestException("Request body must include a non-empty 'roleId'");
    }
    if (Strings.isNullOrEmpty(request.getObjectType())) {
      throw new BadRequestException("Request body must include a non-empty 'objectType'");
    }
    if (Strings.isNullOrEmpty(request.getObjectPath())) {
      throw new BadRequestException("Request body must include a non-empty 'objectPath'");
    }
    if (Strings.isNullOrEmpty(request.getPrivilege())) {
      throw new BadRequestException("Request body must include a non-empty 'privilege'");
    }
    try {
      rbacService.grantPrivilege(
          request.getRoleId(),
          request.getObjectType(),
          request.getObjectPath(),
          request.getPrivilege(),
          getUserName());
    } catch (RbacEntityNotFoundException e) {
      throw new NotFoundException(e.getMessage());
    } catch (RbacEntityAlreadyExistsException e) {
      throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
    }
    return new RbacGrant(
        request.getRoleId(),
        request.getObjectType(),
        request.getObjectPath(),
        request.getPrivilege(),
        getUserName());
  }

  /**
   * Revokes a privilege from a role on an object.
   *
   * <p>Uses query parameters (NOT request body) for the 4 grant key components, following the
   * pattern of DELETE /catalog/{id}?tag=... in CatalogResource.
   *
   * @param roleId required query parameter
   * @param objectType required query parameter
   * @param objectPath required query parameter
   * @param privilege required query parameter
   * @return 204 No Content (REST-09)
   * @throws BadRequestException if any query parameter is null/empty
   * @throws NotFoundException if the grant does not exist
   */
  @DELETE
  @Path("/grants")
  public Response revokePrivilege(
      @QueryParam("roleId") String roleId,
      @QueryParam("objectType") String objectType,
      @QueryParam("objectPath") String objectPath,
      @QueryParam("privilege") String privilege) {
    requireRbacEnabled();
    requireAdmin();
    if (Strings.isNullOrEmpty(roleId)) {
      throw new BadRequestException("Query parameter 'roleId' is required");
    }
    if (Strings.isNullOrEmpty(objectType)) {
      throw new BadRequestException("Query parameter 'objectType' is required");
    }
    if (Strings.isNullOrEmpty(objectPath)) {
      throw new BadRequestException("Query parameter 'objectPath' is required");
    }
    if (Strings.isNullOrEmpty(privilege)) {
      throw new BadRequestException("Query parameter 'privilege' is required");
    }
    try {
      rbacService.revokePrivilege(roleId, objectType, objectPath, privilege);
    } catch (RbacEntityNotFoundException e) {
      throw new NotFoundException(e.getMessage());
    }
    return Response.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Guards every endpoint against RBAC being disabled. Throws an unsupported error (400) if the
   * RBAC feature flag is off.
   */
  private void requireRbacEnabled() {
    if (!dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
      throw UserException.unsupportedError()
          .message("RBAC is not enabled. Set services.rbac.enabled=true in dremio.conf")
          .buildSilently();
    }
  }

  /**
   * Guards every endpoint against non-admin access. Throws a permission error (403) if the current
   * user is not a member of the ADMIN role.
   *
   * <p>Uses {@link RbacService#isAdminMember(String)} rather than {@code @RolesAllowed("admin")}
   * because {@code DACSecurityContext.isUserInRole()} always returns {@code true} and cannot be
   * used for actual access control.
   */
  private void requireAdmin() {
    String userName = securityContext.getUserPrincipal().getName();
    if (!rbacService.isAdminMember(userName)) {
      throw UserException.permissionError()
          .message("Only ADMIN role members can access RBAC management endpoints")
          .buildSilently();
    }
  }

  /** Returns the name of the currently authenticated user from the JAX-RS security context. */
  private String getUserName() {
    return securityContext.getUserPrincipal().getName();
  }
}
