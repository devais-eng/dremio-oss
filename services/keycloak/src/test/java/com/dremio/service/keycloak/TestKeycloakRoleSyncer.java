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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.exec.rbac.RbacEntityAlreadyExistsException;
import com.dremio.exec.rbac.RbacEntityNotFoundException;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.rbac.RoleStore;
import com.dremio.exec.rbac.proto.RbacProto.Membership;
import com.dremio.exec.rbac.proto.RbacProto.Role;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for KeycloakRoleSyncer (ROLE-01, ROLE-02, ROLE-03, ROLE-04).
 *
 * <p>Uses Mockito mocks for RbacService, RoleStore, and KeycloakConfig so no running Dremio
 * instance is needed.
 */
class TestKeycloakRoleSyncer {

  private RbacService mockRbacService;
  private RoleStore mockRoleStore;
  private KeycloakConfig mockConfig;
  private KeycloakRoleSyncer syncer;

  @BeforeEach
  void setUp() {
    mockRbacService = mock(RbacService.class);
    mockRoleStore = mock(RoleStore.class);
    mockConfig = mock(KeycloakConfig.class);
    syncer = new KeycloakRoleSyncer(mockRbacService, mockRoleStore, mockConfig);

    // Default: additive mode
    when(mockConfig.isAdditive()).thenReturn(true);
    // Default: no existing memberships
    when(mockRbacService.listMembershipsByUser(anyString())).thenReturn(Collections.emptyList());
  }

  /**
   * Test 1 (ROLE-01): syncRoles("alice", ["analyst"]) where "analyst" exists as a Dremio role
   * -> addMembership("alice", "analyst", "keycloak", "keycloak") is called.
   */
  @Test
  void testRolesGrantedOnLogin() throws Exception {
    when(mockRoleStore.get("analyst")).thenReturn(mock(Role.class));

    syncer.syncRoles("alice", Arrays.asList("analyst"));

    verify(mockRbacService).addMembership("alice", "analyst", "keycloak", "keycloak");
  }

  /**
   * Test 2 (ROLE-02): Additive mode: user has manual membership (source="") for "editor" plus
   * Keycloak sends ["analyst"]. The "editor" membership must NOT be removed; "analyst" is added
   * with source="keycloak".
   */
  @Test
  void testAdditiveModePreservesManualRoles() throws Exception {
    when(mockConfig.isAdditive()).thenReturn(true);
    when(mockRoleStore.get("analyst")).thenReturn(mock(Role.class));

    // User already has a manually-assigned "editor" role (source = "" = proto3 default)
    Membership editorMembership =
        Membership.newBuilder()
            .setUserName("alice")
            .setRoleId("editor")
            .setGrantedBy("admin")
            .setSource("") // manually-assigned
            .build();
    when(mockRbacService.listMembershipsByUser("alice"))
        .thenReturn(Collections.singletonList(editorMembership));

    syncer.syncRoles("alice", Arrays.asList("analyst"));

    // "analyst" must be added
    verify(mockRbacService).addMembership("alice", "analyst", "keycloak", "keycloak");
    // "editor" must NOT be removed (additive mode preserves manual memberships)
    verify(mockRbacService, never()).removeMembership(eq("alice"), anyString());
  }

  /**
   * Test 3 (ROLE-03): Authoritative mode: user has a keycloak-sourced membership for "old-role"
   * and Keycloak sends ["analyst"]. "old-role" must be revoked; "analyst" must be added.
   */
  @Test
  void testAuthoritativeModeRevokesStaleRoles() throws Exception {
    when(mockConfig.isAdditive()).thenReturn(false);
    when(mockRoleStore.get("analyst")).thenReturn(mock(Role.class));

    // User already has a keycloak-sourced "old-role" membership
    Membership oldMembership =
        Membership.newBuilder()
            .setUserName("alice")
            .setRoleId("old-role")
            .setGrantedBy("keycloak")
            .setSource("keycloak")
            .build();
    when(mockRbacService.listMembershipsByUser("alice"))
        .thenReturn(Collections.singletonList(oldMembership));

    syncer.syncRoles("alice", Arrays.asList("analyst"));

    // "analyst" must be added (not yet present)
    verify(mockRbacService).addMembership("alice", "analyst", "keycloak", "keycloak");
    // "old-role" must be revoked (authoritative: keycloak-sourced but no longer in token)
    verify(mockRbacService).removeMembership("alice", "old-role");
  }

  /**
   * Test 4 (ROLE-03): Authoritative mode: user has a manual membership (source="") for "editor"
   * and Keycloak sends []. Even in authoritative mode, manually-assigned memberships must NOT be
   * removed.
   */
  @Test
  void testAuthoritativeModePreservesManualMemberships() throws Exception {
    when(mockConfig.isAdditive()).thenReturn(false);

    // User has a manually-assigned "editor" role
    Membership editorMembership =
        Membership.newBuilder()
            .setUserName("alice")
            .setRoleId("editor")
            .setGrantedBy("admin")
            .setSource("") // manually-assigned
            .build();
    when(mockRbacService.listMembershipsByUser("alice"))
        .thenReturn(Collections.singletonList(editorMembership));

    syncer.syncRoles("alice", Collections.emptyList());

    // "editor" must NOT be removed (only keycloak-sourced memberships are revoked)
    verify(mockRbacService, never()).removeMembership(eq("alice"), anyString());
  }

  /**
   * Test 5 (ROLE-04): syncRoles("alice", ["analyst", "realm-management", "offline_access"]) where
   * only "analyst" exists in Dremio -> only "analyst" is granted; "realm-management" and
   * "offline_access" are silently skipped.
   */
  @Test
  void testUnknownKeycloakRolesIgnored() throws Exception {
    // "analyst" exists; "realm-management" and "offline_access" do not
    when(mockRoleStore.get("analyst")).thenReturn(mock(Role.class));
    when(mockRoleStore.get("realm-management")).thenReturn(null);
    when(mockRoleStore.get("offline_access")).thenReturn(null);

    syncer.syncRoles("alice", Arrays.asList("analyst", "realm-management", "offline_access"));

    // Only "analyst" must be granted
    verify(mockRbacService).addMembership("alice", "analyst", "keycloak", "keycloak");
    // Unknown roles must not be granted
    verify(mockRbacService, never())
        .addMembership(anyString(), eq("realm-management"), anyString(), anyString());
    verify(mockRbacService, never())
        .addMembership(anyString(), eq("offline_access"), anyString(), anyString());
  }

  /**
   * Test 6 (ROLE-01): Idempotency -- syncRoles() called twice with same roles does not throw
   * RbacEntityAlreadyExistsException. The second call silently ignores the duplicate.
   */
  @Test
  void testSyncRolesIsIdempotent() throws Exception {
    when(mockRoleStore.get("analyst")).thenReturn(mock(Role.class));

    // Second call: "analyst" membership already exists, addMembership throws
    when(mockRbacService.listMembershipsByUser("alice")).thenReturn(Collections.emptyList());

    // First call succeeds normally
    syncer.syncRoles("alice", Arrays.asList("analyst"));
    verify(mockRbacService).addMembership("alice", "analyst", "keycloak", "keycloak");

    // Second call: addMembership throws RbacEntityAlreadyExistsException -- must not propagate
    org.mockito.Mockito.doThrow(new RbacEntityAlreadyExistsException("already exists"))
        .when(mockRbacService)
        .addMembership(anyString(), anyString(), anyString(), anyString());

    assertThatCode(() -> syncer.syncRoles("alice", Arrays.asList("analyst")))
        .doesNotThrowAnyException();
  }

  /**
   * Test 7 (ROLE-01): syncRoles("alice", ["ADMIN"]) where ADMIN is the built-in role
   * -> addMembership is called (ADMIN skips roleStore.get check).
   */
  @Test
  void testAdminRoleDoesNotRequireRoleStoreCheck() throws Exception {
    // ADMIN is built-in -- roleStore.get("ADMIN") must NOT be called
    syncer.syncRoles("alice", Arrays.asList("ADMIN"));

    verify(mockRbacService).addMembership("alice", "ADMIN", "keycloak", "keycloak");
    verify(mockRoleStore, never()).get("ADMIN");
  }

  /**
   * ROLE-04 safety net: syncRoles() must never throw to the caller, even if RbacService throws an
   * unexpected exception. Role sync failure must degrade gracefully.
   */
  @Test
  void testSyncRolesNeverThrowsToCaller() throws Exception {
    when(mockRoleStore.get("analyst")).thenReturn(mock(Role.class));
    // Simulate an unexpected runtime exception from rbacService
    when(mockRbacService.listMembershipsByUser("alice"))
        .thenThrow(new RuntimeException("unexpected store failure"));

    assertThatCode(() -> syncer.syncRoles("alice", Arrays.asList("analyst")))
        .doesNotThrowAnyException();
  }

  /**
   * ROLE-03: Authoritative mode -- stale keycloak role removal catches RbacEntityNotFoundException
   * (already removed concurrently) without throwing.
   */
  @Test
  void testAuthoritativeModeRemovalIdempotent() throws Exception {
    when(mockConfig.isAdditive()).thenReturn(false);
    when(mockRoleStore.get("analyst")).thenReturn(mock(Role.class));

    Membership oldMembership =
        Membership.newBuilder()
            .setUserName("alice")
            .setRoleId("old-role")
            .setGrantedBy("keycloak")
            .setSource("keycloak")
            .build();
    when(mockRbacService.listMembershipsByUser("alice"))
        .thenReturn(Collections.singletonList(oldMembership));

    // removeMembership throws (concurrent removal)
    org.mockito.Mockito.doThrow(new RbacEntityNotFoundException("not found"))
        .when(mockRbacService)
        .removeMembership("alice", "old-role");

    assertThatCode(() -> syncer.syncRoles("alice", Arrays.asList("analyst")))
        .doesNotThrowAnyException();
  }
}
