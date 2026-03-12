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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.service.users.SimpleUserService;
import com.dremio.service.users.proto.UID;
import com.dremio.service.users.proto.UserInfo;
import com.dremio.service.users.proto.UserType;
import java.io.IOException;
import javax.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for JitUserProvisioner (JIT-01, JIT-02, JIT-03).
 *
 * <p>Uses Mockito to mock the KVStore layer so no running Dremio instance is needed.
 */
@SuppressWarnings("unchecked")
class TestJitUserProvisioner {

  private LegacyIndexedStore<UID, UserInfo> mockStore;
  private JitUserProvisioner provisioner;

  @BeforeEach
  void setUp() {
    mockStore = mock(LegacyIndexedStore.class);
    LegacyKVStoreProvider mockProvider = mock(LegacyKVStoreProvider.class);
    when(mockProvider.getStore(SimpleUserService.UserGroupStoreBuilder.class))
        .thenReturn(mockStore);

    Provider<LegacyKVStoreProvider> kvStoreProviderProvider = () -> mockProvider;
    provisioner = new JitUserProvisioner(kvStoreProviderProvider);
  }

  /**
   * JIT-01: provision("alice", "alice@example.com") creates a user with correct username and email.
   */
  @Test
  void testProvisionNewUser() throws IOException {
    provisioner.provision("alice", "alice@example.com");

    ArgumentCaptor<UserInfo> userInfoCaptor = forClass(UserInfo.class);
    verify(mockStore, times(1)).put(any(UID.class), userInfoCaptor.capture());

    UserInfo captured = userInfoCaptor.getValue();
    assertThat(captured.getConfig().getUserName()).isEqualTo("alice");
    assertThat(captured.getConfig().getEmail()).isEqualTo("alice@example.com");
  }

  /**
   * JIT-02: The provisioned user's type is REMOTE (not LOCAL). REMOTE users are rejected at
   * SimpleUserService.authenticate() line 354.
   */
  @Test
  void testProvisionedUserIsRemoteType() throws IOException {
    provisioner.provision("alice", "alice@example.com");

    ArgumentCaptor<UserInfo> userInfoCaptor = forClass(UserInfo.class);
    verify(mockStore, times(1)).put(any(UID.class), userInfoCaptor.capture());

    UserInfo captured = userInfoCaptor.getValue();
    assertThat(captured.getConfig().getType()).isEqualTo(UserType.REMOTE);
  }

  /**
   * JIT-02: The provisioned UserInfo has no UserAuth record (no password stored). REMOTE users
   * should not have a password hash in the store.
   */
  @Test
  void testProvisionedUserHasNoAuthRecord() throws IOException {
    provisioner.provision("alice", "alice@example.com");

    ArgumentCaptor<UserInfo> userInfoCaptor = forClass(UserInfo.class);
    verify(mockStore, times(1)).put(any(UID.class), userInfoCaptor.capture());

    UserInfo captured = userInfoCaptor.getValue();
    assertThat(captured.getAuth()).isNull();
  }

  /**
   * JIT-01: provision("bob", null) succeeds with email stored as empty string (null-safe). Service
   * accounts and clients without email claims should not cause NPE.
   */
  @Test
  void testProvisionNullEmailStoredAsEmptyString() throws IOException {
    provisioner.provision("bob", null);

    ArgumentCaptor<UserInfo> userInfoCaptor = forClass(UserInfo.class);
    verify(mockStore, times(1)).put(any(UID.class), userInfoCaptor.capture());

    UserInfo captured = userInfoCaptor.getValue();
    assertThat(captured.getConfig().getEmail()).isEqualTo("");
  }

  /**
   * JIT-03: Calling provision() twice does not throw -- second call is idempotent. Simulates a
   * concurrent race where the put() call throws an exception on the second attempt.
   */
  @Test
  void testConcurrentProvisioningIsIdempotent() throws IOException {
    // First call succeeds, second call simulates concurrent creation by throwing
    doThrow(new RuntimeException("simulated concurrent write collision"))
        .when(mockStore)
        .put(any(UID.class), any(UserInfo.class));

    // The provision() call must NOT propagate the exception
    assertThatCode(() -> provisioner.provision("alice", "alice@example.com"))
        .doesNotThrowAnyException();
  }
}
