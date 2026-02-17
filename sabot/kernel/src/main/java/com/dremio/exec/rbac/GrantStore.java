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
import com.dremio.exec.rbac.proto.RbacProto.Grant;
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
 * KV store for RBAC grant records.
 *
 * <p>Store name: {@value RbacConfig#GRANTS_STORE}
 *
 * <p>Key: composite pipe-delimited string built via {@link RbacConfig#grantKey}.
 * Format: {@code {role_id}|{object_type}|{object_path}|{privilege}}
 * Example: {@code analyst|VDS|schemas.my_view|SELECT}
 *
 * <p>Uses {@code Format.ofString()} (NOT {@code Format.ofCompoundFormat()}) to preserve
 * human-readable key inspection during debugging.
 *
 * <p>Value: {@link Grant} proto3 message serialized via {@code Format.ofProtobuf}.
 */
public class GrantStore {

  private final Supplier<KVStore<String, Grant>> store;

  @Inject
  public GrantStore(Provider<KVStoreProvider> kvStoreProvider) {
    this.store = Suppliers.memoize(() -> kvStoreProvider.get().getStore(StoreCreator.class));
  }

  /**
   * Returns the Grant for the given composite key, or null if not found.
   * Build the key with {@link RbacConfig#grantKey}.
   *
   * @throws IllegalArgumentException if grantKey is null or empty
   */
  public Grant get(String grantKey) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(grantKey), "grantKey must not be null or empty");
    Document<String, Grant> doc = store.get().get(grantKey);
    return doc == null ? null : doc.getValue();
  }

  /**
   * Stores a grant. Throws if the grant key already exists.
   *
   * @throws IllegalArgumentException if grantKey or grant is null/empty
   * @throws RbacEntityAlreadyExistsException if the grant already exists
   */
  public void grant(String grantKey, Grant grant) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(grantKey), "grantKey must not be null or empty");
    Preconditions.checkNotNull(grant, "grant must not be null");
    try {
      store.get().put(grantKey, grant, KVStore.PutOption.CREATE);
    } catch (java.util.ConcurrentModificationException e) {
      throw new RbacEntityAlreadyExistsException("Grant already exists: " + grantKey, e);
    }
  }

  /**
   * Removes a grant. Throws if the grant key does not exist.
   *
   * @throws IllegalArgumentException if grantKey is null or empty
   * @throws RbacEntityNotFoundException if no grant exists for grantKey
   */
  public void revoke(String grantKey) throws RbacEntityNotFoundException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(grantKey), "grantKey must not be null or empty");
    if (store.get().get(grantKey) == null) {
      throw new RbacEntityNotFoundException("Grant not found: " + grantKey);
    }
    store.get().delete(grantKey);
  }

  /**
   * Returns all grants held by the given role. Key format: "{role_id}|...".
   * Uses scan-and-filter (not IndexedStore) per locked decision.
   *
   * @throws IllegalArgumentException if roleId is null or empty
   */
  public List<Grant> listByRole(String roleId) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
    String prefix = roleId + RbacConfig.KEY_SEP;
    return StreamSupport.stream(store.get().find().spliterator(), false)
        .filter(doc -> doc.getKey().startsWith(prefix))
        .map(Document::getValue)
        .collect(Collectors.toList());
  }

  /**
   * Returns all grants in the store. Intended for system table queries.
   */
  public List<Grant> listAll() {
    return StreamSupport.stream(store.get().find().spliterator(), false)
        .map(Document::getValue)
        .collect(Collectors.toList());
  }

  /**
   * Removes all grants for the given role. Package-private -- used only by RoleStore.delete() cascade.
   *
   * <p>Keys are collected to a list first to avoid ConcurrentModificationException on the
   * one-shot live iterator returned by {@code find()}.
   */
  void deleteByRole(String roleId) {
    String prefix = roleId + RbacConfig.KEY_SEP;
    StreamSupport.stream(store.get().find().spliterator(), false)
        .filter(doc -> doc.getKey().startsWith(prefix))
        .map(Document::getKey)
        .collect(Collectors.toList()) // collect to list first to avoid ConcurrentModificationException on the live iterator
        .forEach(key -> store.get().delete(key));
  }

  /**
   * KV store creator. The class name {@code StoreCreator} is the permanent store
   * identifier -- do NOT rename this class in any future phase.
   */
  public static final class StoreCreator implements KVStoreCreationFunction<String, Grant> {
    @Override
    public KVStore<String, Grant> build(StoreBuildingFactory factory) {
      return factory
          .<String, Grant>newStore()
          .name(RbacConfig.GRANTS_STORE)
          .keyFormat(Format.ofString())
          .valueFormat(Format.ofProtobuf(Grant.class))
          .build();
    }
  }
}
