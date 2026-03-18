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

import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.service.users.SimpleUserService;
import com.dremio.service.users.proto.UID;
import com.dremio.service.users.proto.UserConfig;
import com.dremio.service.users.proto.UserInfo;
import com.dremio.service.users.proto.UserType;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.io.IOException;
import java.util.UUID;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates Dremio users of type {@code UserType.REMOTE} on first Keycloak login (JIT provisioning).
 *
 * <p>JIT-01: The provisioned user has the username from the {@code preferred_username} JWT claim
 * and the email from the {@code email} JWT claim.
 *
 * <p>JIT-02: The user is created with {@code UserType.REMOTE} and no {@code UserAuth} record,
 * ensuring they cannot log in via the username/password form ({@code
 * SimpleUserService.authenticate} rejects non-LOCAL users at line 354).
 *
 * <p>JIT-03: Concurrent or repeated calls to {@link #provision} are idempotent. If the underlying
 * KVStore write throws (e.g., due to a concurrent first-login race), the exception is swallowed and
 * the caller proceeds to read the already-created record via {@code getUser()}.
 */
public class JitUserProvisioner {

  private static final Logger logger = LoggerFactory.getLogger(JitUserProvisioner.class);

  private final Supplier<LegacyIndexedStore<UID, UserInfo>> userStore;

  /**
   * Constructs a {@code JitUserProvisioner} that lazily resolves the user KVStore on first use.
   *
   * @param kvStoreProvider provider for the KVStore; resolved via {@link
   *     SimpleUserService.UserGroupStoreBuilder}
   */
  public JitUserProvisioner(Provider<LegacyKVStoreProvider> kvStoreProvider) {
    this.userStore =
        Suppliers.memoize(
            () -> kvStoreProvider.get().getStore(SimpleUserService.UserGroupStoreBuilder.class));
  }

  /**
   * Creates a {@code UserType.REMOTE} user in the KVStore for the given Keycloak identity.
   *
   * <p>No {@code UserAuth} record is created; REMOTE users cannot authenticate via the
   * username/password form.
   *
   * <p>Idempotent: if the KVStore write fails due to a concurrent creation race, the exception is
   * logged at DEBUG level and silently swallowed. The caller should call {@code getUser(username)}
   * after this method to retrieve the canonical user record regardless of which thread won the
   * race.
   *
   * @param username Dremio username (from JWT {@code preferred_username} claim)
   * @param email user email (from JWT {@code email} claim); stored as {@code ""} if {@code null}
   * @throws IOException never thrown by this implementation; declared for caller compatibility
   */
  public void provision(String username, String email) throws IOException {
    UID uid = new UID(UUID.randomUUID().toString());
    UserConfig config =
        new UserConfig()
            .setUid(uid)
            .setUserName(username)
            .setEmail(email != null ? email : "")
            .setType(UserType.REMOTE)
            .setCreatedAt(System.currentTimeMillis())
            .setModifiedAt(System.currentTimeMillis())
            .setActive(true);
    UserInfo info = new UserInfo().setConfig(config);
    try {
      userStore.get().put(uid, info);
    } catch (Exception e) {
      // JIT-03: concurrent creation race -- if user now exists, that's fine.
      // Log at debug and return. The caller will re-fetch the user via getUser().
      logger.debug("JIT provisioning race for user '{}': {}", username, e.getMessage());
    }
  }
}
