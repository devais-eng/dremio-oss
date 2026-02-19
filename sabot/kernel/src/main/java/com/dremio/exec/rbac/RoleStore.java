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

import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.KVStoreCreationFunction;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.datastore.api.StoreBuildingFactory;
import com.dremio.datastore.format.Format;
import com.dremio.exec.rbac.proto.RbacProto.Role;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Suppliers;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.inject.Inject;
import javax.inject.Provider;

/**
 * KV store for RBAC role records.
 *
 * <p>Store name: {@value RbacConfig#ROLES_STORE}
 *
 * <p>Key: role_id (slugified role name, e.g. "analyst"). Role names are immutable -- no rename
 * support; DROP and re-CREATE to rename.
 *
 * <p>Value: {@link Role} proto3 message serialized via {@code Format.ofProtobuf}.
 */
public class RoleStore {

  private final Supplier<KVStore<String, Role>> store;

  @Inject
  public RoleStore(Provider<KVStoreProvider> kvStoreProvider) {
    this.store = Suppliers.memoize(() -> kvStoreProvider.get().getStore(StoreCreator.class));
  }

  /**
   * Returns the Role with the given roleId, or null if not found.
   *
   * @throws IllegalArgumentException if roleId is null or empty
   */
  public Role get(String roleId) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
    Document<String, Role> doc = store.get().get(roleId);
    return doc == null ? null : doc.getValue();
  }

  /**
   * Creates a new role. Throws if a role with this roleId already exists.
   *
   * @throws IllegalArgumentException if roleId or role is null/empty
   * @throws RbacEntityAlreadyExistsException if a role with this roleId already exists
   */
  public void create(String roleId, Role role) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
    Preconditions.checkNotNull(role, "role must not be null");
    try {
      store.get().put(roleId, role, KVStore.PutOption.CREATE);
    } catch (java.util.ConcurrentModificationException e) {
      throw new RbacEntityAlreadyExistsException("Role already exists: " + roleId, e);
    }
  }

  /**
   * Deletes the role with the given roleId. Cascades: removes all related grants and memberships.
   *
   * @throws IllegalArgumentException if roleId is null or empty
   * @throws RbacEntityNotFoundException if no role with this roleId exists
   */
  public void delete(String roleId, GrantStore grantStore, MembershipStore membershipStore)
      throws RbacEntityNotFoundException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
    if (store.get().get(roleId) == null) {
      throw new RbacEntityNotFoundException("Role not found: " + roleId);
    }
    // Cascade: remove all grants and memberships for this role before deleting the role
    grantStore.deleteByRole(roleId);
    membershipStore.deleteByRole(roleId);
    store.get().delete(roleId);
  }

  /** Returns all roles in the store. Intended for system table queries. */
  public List<Role> listAll() {
    return StreamSupport.stream(store.get().find().spliterator(), false)
        .map(Document::getValue)
        .collect(Collectors.toList());
  }

  /**
   * KV store creator. The class name {@code StoreCreator} is the permanent store identifier -- do
   * NOT rename this class in any future phase.
   */
  public static final class StoreCreator implements KVStoreCreationFunction<String, Role> {
    @Override
    public KVStore<String, Role> build(StoreBuildingFactory factory) {
      return factory
          .<String, Role>newStore()
          .name(RbacConfig.ROLES_STORE)
          .keyFormat(Format.ofString())
          .valueFormat(Format.ofProtobuf(Role.class))
          .build();
    }
  }
}
