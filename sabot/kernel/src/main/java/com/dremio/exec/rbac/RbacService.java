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

import com.dremio.exec.rbac.proto.RbacProto.Grant;
import com.dremio.exec.rbac.proto.RbacProto.Membership;
import com.dremio.exec.rbac.proto.RbacProto.Role;
import com.dremio.exec.store.sys.accesscontrol.AccessControlListingManager;
import com.dremio.exec.store.sys.accesscontrol.SysTableMembershipInfo;
import com.dremio.exec.store.sys.accesscontrol.SysTablePrivilegeInfo;
import com.dremio.exec.store.sys.accesscontrol.SysTableRoleInfo;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core RBAC business logic service.
 *
 * <p>Orchestrates {@link RoleStore}, {@link GrantStore}, and {@link MembershipStore} to provide
 * privilege resolution, role/membership lifecycle management, bootstrap ADMIN assignment, fail-fast
 * startup validation, and system table data exposure via {@link AccessControlListingManager}.
 *
 * <p>ADMIN and PUBLIC are synthetic built-in roles -- they are never stored in the role KV store.
 * ADMIN memberships ARE stored in the membership KV store. PUBLIC membership is implicit for all
 * users and is never stored.
 *
 * <p>Privilege resolution order (locked decision):
 * <ol>
 *   <li>Check if user is ADMIN member -- short-circuit return true</li>
 *   <li>Collect user's explicit roles + PUBLIC</li>
 *   <li>Check grants for all collected roles (OR logic)</li>
 * </ol>
 *
 * @see #hasPrivilege(String, String, String, String)
 * @see #assignBootstrapAdmin(String)
 * @see #validateAdminMembersExist()
 */
public class RbacService implements AccessControlListingManager {

  /** Synthetic built-in role: members bypass all privilege checks. */
  public static final String ADMIN_ROLE_ID = "ADMIN";

  /** Synthetic built-in role: all users implicitly belong to this role. */
  public static final String PUBLIC_ROLE_ID = "PUBLIC";

  private final RoleStore roleStore;
  private final GrantStore grantStore;
  private final MembershipStore membershipStore;

  /**
   * Constructs the RBAC service with its three store dependencies.
   *
   * <p>No DI annotations -- wiring is handled in Phase 4.
   */
  public RbacService(RoleStore roleStore, GrantStore grantStore, MembershipStore membershipStore) {
    this.roleStore = Preconditions.checkNotNull(roleStore, "roleStore must not be null");
    this.grantStore = Preconditions.checkNotNull(grantStore, "grantStore must not be null");
    this.membershipStore =
        Preconditions.checkNotNull(membershipStore, "membershipStore must not be null");
  }

  // ---------------------------------------------------------------------------
  // Service lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void start() throws Exception {
    // No-op: fail-fast validation is called separately by startup code in Phase 4
  }

  @Override
  public void close() throws Exception {
    // No-op: stores are managed by KVStoreProvider lifecycle
  }

  // ---------------------------------------------------------------------------
  // Privilege resolution
  // ---------------------------------------------------------------------------

  /**
   * Checks whether the given user has the specified privilege on the given object.
   *
   * <p>Resolution order:
   * <ol>
   *   <li>If user is an ADMIN member, return true immediately (short-circuit).</li>
   *   <li>Collect user's explicit role IDs + PUBLIC_ROLE_ID (implicit membership).</li>
   *   <li>For each role, check if a matching grant exists (OR logic). If any grant found, return
   *       true.</li>
   *   <li>If no grant found across all roles, return false (deny-by-default).</li>
   * </ol>
   *
   * @param userName the user to check
   * @param privilege the privilege name (e.g. "SELECT", "EXECUTE")
   * @param objectType the object type (e.g. "VDS", "FUNCTION")
   * @param objectPath the dot-delimited object path (e.g. "schemas.my_view")
   * @return true if user has the privilege, false otherwise
   */
  public boolean hasPrivilege(
      String userName, String privilege, String objectType, String objectPath) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(userName), "userName must not be null or empty");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(privilege), "privilege must not be null or empty");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(objectType), "objectType must not be null or empty");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(objectPath), "objectPath must not be null or empty");

    // Step 1: ADMIN short-circuit
    if (isAdminMember(userName)) {
      return true;
    }

    // Step 2: Collect user's explicit roles + PUBLIC (single pass, not fallback)
    List<String> roleIds =
        membershipStore.listByUser(userName).stream()
            .map(Membership::getRoleId)
            .collect(Collectors.toList());
    roleIds.add(PUBLIC_ROLE_ID);

    // Step 3: Check grants for all roles (OR logic)
    for (String roleId : roleIds) {
      String grantKey = RbacConfig.grantKey(roleId, objectType, objectPath, privilege);
      if (grantStore.get(grantKey) != null) {
        return true;
      }
    }

    // Deny-by-default
    return false;
  }

  /**
   * Checks whether the given user is a member of the ADMIN role.
   */
  private boolean isAdminMember(String userName) {
    String key = RbacConfig.membershipKey(userName, ADMIN_ROLE_ID);
    return membershipStore.get(key) != null;
  }

  // ---------------------------------------------------------------------------
  // Role lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Creates a new user-defined role. Built-in role IDs (ADMIN, PUBLIC) are rejected.
   *
   * @param roleId the slugified role identifier
   * @param roleName the human-readable role name
   * @param createdBy the user who created the role
   * @throws IllegalArgumentException if roleId matches a built-in role
   * @throws RbacEntityAlreadyExistsException if a role with this ID already exists
   */
  public void createRole(String roleId, String roleName, String createdBy) {
    Preconditions.checkArgument(
        !ADMIN_ROLE_ID.equals(roleId), "Cannot create built-in role: " + ADMIN_ROLE_ID);
    Preconditions.checkArgument(
        !PUBLIC_ROLE_ID.equals(roleId), "Cannot create built-in role: " + PUBLIC_ROLE_ID);

    Role role =
        Role.newBuilder()
            .setRoleId(roleId)
            .setRoleName(roleName)
            .setCreatedBy(createdBy)
            .setCreatedAt(System.currentTimeMillis())
            .build();
    roleStore.create(roleId, role);
  }

  /**
   * Deletes a user-defined role and cascades (removes all grants and memberships for it). Built-in
   * role IDs (ADMIN, PUBLIC) are rejected.
   *
   * @param roleId the role to delete
   * @throws IllegalArgumentException if roleId matches a built-in role
   * @throws RbacEntityNotFoundException if the role does not exist
   */
  public void deleteRole(String roleId) throws RbacEntityNotFoundException {
    Preconditions.checkArgument(
        !ADMIN_ROLE_ID.equals(roleId), "Cannot drop built-in role: " + ADMIN_ROLE_ID);
    Preconditions.checkArgument(
        !PUBLIC_ROLE_ID.equals(roleId), "Cannot drop built-in role: " + PUBLIC_ROLE_ID);
    roleStore.delete(roleId, grantStore, membershipStore);
  }

  // ---------------------------------------------------------------------------
  // Membership lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Adds a user to a role. PUBLIC is rejected (implicit membership). For ADMIN, the role existence
   * check is skipped (synthetic). For other roles, the role must exist in the store.
   *
   * @param userName the user to add
   * @param roleId the role to assign
   * @param grantedBy the user who granted the membership
   * @throws IllegalArgumentException if roleId is PUBLIC
   * @throws RbacEntityNotFoundException if the role does not exist (non-built-in roles only)
   * @throws RbacEntityAlreadyExistsException if the membership already exists
   */
  public void addMembership(String userName, String roleId, String grantedBy)
      throws RbacEntityNotFoundException {
    Preconditions.checkArgument(
        !PUBLIC_ROLE_ID.equals(roleId),
        "Cannot explicitly add membership to built-in role: PUBLIC");

    // ADMIN is synthetic -- skip role existence check. For other roles, verify existence.
    if (!ADMIN_ROLE_ID.equals(roleId)) {
      if (roleStore.get(roleId) == null) {
        throw new RbacEntityNotFoundException("Role not found: " + roleId);
      }
    }

    Membership membership =
        Membership.newBuilder()
            .setUserName(userName)
            .setRoleId(roleId)
            .setGrantedBy(grantedBy)
            .setGrantedAt(System.currentTimeMillis())
            .build();
    membershipStore.add(RbacConfig.membershipKey(userName, roleId), membership);
  }

  /**
   * Removes a user from a role. ADMIN and PUBLIC are rejected (built-in role memberships cannot be
   * removed via this method).
   *
   * @param userName the user to remove
   * @param roleId the role to remove from
   * @throws IllegalArgumentException if roleId is ADMIN or PUBLIC
   * @throws RbacEntityNotFoundException if the membership does not exist
   */
  public void removeMembership(String userName, String roleId) throws RbacEntityNotFoundException {
    Preconditions.checkArgument(
        !ADMIN_ROLE_ID.equals(roleId), "Cannot remove membership from built-in role: ADMIN");
    Preconditions.checkArgument(
        !PUBLIC_ROLE_ID.equals(roleId), "Cannot remove membership from built-in role: PUBLIC");
    membershipStore.remove(RbacConfig.membershipKey(userName, roleId));
  }

  // ---------------------------------------------------------------------------
  // Grant lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Grants a privilege on an object to a role. For built-in roles (ADMIN, PUBLIC), the role
   * existence check is skipped. For user-created roles, the role must exist in the store.
   *
   * @param roleId the role receiving the grant
   * @param objectType the object type (e.g. "VDS", "FUNCTION")
   * @param objectPath the dot-delimited object path
   * @param privilege the privilege name (e.g. "SELECT", "EXECUTE")
   * @param grantedBy the user who granted the privilege
   * @throws RbacEntityNotFoundException if the role does not exist (non-built-in roles only)
   * @throws RbacEntityAlreadyExistsException if the grant already exists
   */
  public void grantPrivilege(
      String roleId, String objectType, String objectPath, String privilege, String grantedBy)
      throws RbacEntityNotFoundException {
    // Skip role existence check for built-in roles (they are synthetic)
    if (!ADMIN_ROLE_ID.equals(roleId) && !PUBLIC_ROLE_ID.equals(roleId)) {
      if (roleStore.get(roleId) == null) {
        throw new RbacEntityNotFoundException("Role not found: " + roleId);
      }
    }

    Grant grant =
        Grant.newBuilder()
            .setRoleId(roleId)
            .setObjectType(objectType)
            .setObjectPath(objectPath)
            .setPrivilege(privilege)
            .setGrantedBy(grantedBy)
            .setGrantedAt(System.currentTimeMillis())
            .build();
    grantStore.grant(RbacConfig.grantKey(roleId, objectType, objectPath, privilege), grant);
  }

  /**
   * Revokes a privilege from a role on an object.
   *
   * @param roleId the role losing the grant
   * @param objectType the object type
   * @param objectPath the object path
   * @param privilege the privilege to revoke
   * @throws RbacEntityNotFoundException if the grant does not exist
   */
  public void revokePrivilege(
      String roleId, String objectType, String objectPath, String privilege)
      throws RbacEntityNotFoundException {
    grantStore.revoke(RbacConfig.grantKey(roleId, objectType, objectPath, privilege));
  }

  // ---------------------------------------------------------------------------
  // Bootstrap
  // ---------------------------------------------------------------------------

  /**
   * Creates ADMIN membership for the given user. Called during bootstrap (first user creation).
   *
   * <p>This method may throw {@link RbacEntityAlreadyExistsException} if called again for the same
   * user -- callers should handle idempotency.
   *
   * @param userName the user to assign as ADMIN
   */
  public void assignBootstrapAdmin(String userName) {
    Membership membership =
        Membership.newBuilder()
            .setUserName(userName)
            .setRoleId(ADMIN_ROLE_ID)
            .setGrantedBy("SYSTEM")
            .setGrantedAt(System.currentTimeMillis())
            .build();
    membershipStore.add(RbacConfig.membershipKey(userName, ADMIN_ROLE_ID), membership);
  }

  // ---------------------------------------------------------------------------
  // Fail-fast validation
  // ---------------------------------------------------------------------------

  /**
   * Validates that the ADMIN role has at least one member. Called during coordinator startup when
   * RBAC is enabled.
   *
   * @throws IllegalStateException if ADMIN has no members
   */
  public void validateAdminMembersExist() {
    List<Membership> adminMembers = membershipStore.listByRole(ADMIN_ROLE_ID);
    if (adminMembers.isEmpty()) {
      throw new IllegalStateException(
          "RBAC is enabled (services.rbac.enabled=true) but the ADMIN role has no members. "
              + "Either assign a user to the ADMIN role or set services.rbac.enabled=false "
              + "in dremio.conf. Coordinator will not start without an ADMIN member when RBAC "
              + "is enabled.");
    }
  }

  // ---------------------------------------------------------------------------
  // AccessControlListingManager implementation
  // ---------------------------------------------------------------------------

  /**
   * Returns role information for sys.roles. Includes synthetic ADMIN and PUBLIC roles (type
   * "SYSTEM") plus all user-created roles from the store (type "USER").
   */
  @Override
  public Iterable<SysTableRoleInfo> getRoleInfo() {
    List<SysTableRoleInfo> roles = new ArrayList<>();
    // Synthetic built-in roles
    roles.add(
        new SysTableRoleInfo(ADMIN_ROLE_ID, ADMIN_ROLE_ID, "SYSTEM", null, null, "SYSTEM"));
    roles.add(
        new SysTableRoleInfo(PUBLIC_ROLE_ID, PUBLIC_ROLE_ID, "SYSTEM", null, null, "SYSTEM"));
    // User-created roles from store
    for (Role role : roleStore.listAll()) {
      roles.add(
          new SysTableRoleInfo(
              role.getRoleId(), role.getRoleName(), "USER", null, null, role.getCreatedBy()));
    }
    return roles;
  }

  /**
   * Returns privilege information for sys.privileges. All grants from the store are returned as
   * POJOs with grantee_type "ROLE".
   */
  @Override
  public Iterable<SysTablePrivilegeInfo> getPrivilegeInfo() {
    List<SysTablePrivilegeInfo> privileges = new ArrayList<>();
    for (Grant grant : grantStore.listAll()) {
      privileges.add(
          new SysTablePrivilegeInfo(
              "ROLE",
              grant.getRoleId(),
              grant.getObjectType(),
              grant.getObjectPath(),
              grant.getPrivilege()));
    }
    return privileges;
  }

  /**
   * Returns membership information for sys.membership. Only explicit memberships from the store are
   * returned -- PUBLIC implicit memberships are NOT included (locked decision).
   */
  @Override
  public Iterable<SysTableMembershipInfo> getMembershipInfo() {
    List<SysTableMembershipInfo> memberships = new ArrayList<>();
    for (Membership membership : membershipStore.listAll()) {
      memberships.add(
          new SysTableMembershipInfo(
              membership.getRoleId(), membership.getUserName(), "USER"));
    }
    return memberships;
  }
}
