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
import com.dremio.exec.rbac.proto.RbacProto.Membership;
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
 * KV store for RBAC membership records.
 *
 * <p>Store name: {@value RbacConfig#MEMBERSHIPS_STORE}
 *
 * <p>Key: composite pipe-delimited string built via {@link RbacConfig#membershipKey}.
 * Format: {@code {user_name}|{role_id}}
 * Example: {@code alice|analyst}
 *
 * <p>Uses {@code Format.ofString()} (NOT {@code Format.ofCompoundFormat()}) to preserve
 * human-readable key inspection during debugging.
 *
 * <p>Value: {@link Membership} proto3 message serialized via {@code Format.ofProtobuf}.
 */
public class MembershipStore {

  private final Supplier<KVStore<String, Membership>> store;

  @Inject
  public MembershipStore(Provider<KVStoreProvider> kvStoreProvider) {
    this.store = Suppliers.memoize(() -> kvStoreProvider.get().getStore(StoreCreator.class));
  }

  /**
   * Returns the Membership for the given composite key, or null if not found.
   * Build the key with {@link RbacConfig#membershipKey}.
   *
   * @throws IllegalArgumentException if membershipKey is null or empty
   */
  public Membership get(String membershipKey) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(membershipKey), "membershipKey must not be null or empty");
    Document<String, Membership> doc = store.get().get(membershipKey);
    return doc == null ? null : doc.getValue();
  }

  /**
   * Adds a membership. Throws if the membership key already exists.
   *
   * @throws IllegalArgumentException if membershipKey or membership is null/empty
   * @throws RbacEntityAlreadyExistsException if the membership already exists
   */
  public void add(String membershipKey, Membership membership) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(membershipKey), "membershipKey must not be null or empty");
    Preconditions.checkNotNull(membership, "membership must not be null");
    try {
      store.get().put(membershipKey, membership, KVStore.PutOption.CREATE);
    } catch (java.util.ConcurrentModificationException e) {
      throw new RbacEntityAlreadyExistsException("Membership already exists: " + membershipKey, e);
    }
  }

  /**
   * Removes a membership. Throws if the membership key does not exist.
   *
   * @throws IllegalArgumentException if membershipKey is null or empty
   * @throws RbacEntityNotFoundException if no membership exists for membershipKey
   */
  public void remove(String membershipKey) throws RbacEntityNotFoundException {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(membershipKey), "membershipKey must not be null or empty");
    if (store.get().get(membershipKey) == null) {
      throw new RbacEntityNotFoundException("Membership not found: " + membershipKey);
    }
    store.get().delete(membershipKey);
  }

  /**
   * Returns all memberships for the given userName. Key format: "{user_name}|{role_id}".
   * Filters keys starting with "userName|" (scan-and-filter, not IndexedStore).
   *
   * @throws IllegalArgumentException if userName is null or empty
   */
  public List<Membership> listByUser(String userName) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(userName), "userName must not be null or empty");
    String prefix = userName + RbacConfig.KEY_SEP;
    return StreamSupport.stream(store.get().find().spliterator(), false)
        .filter(doc -> doc.getKey().startsWith(prefix))
        .map(Document::getValue)
        .collect(Collectors.toList());
  }

  /**
   * Returns all memberships belonging to the given role. Key format: "{user_name}|{role_id}".
   * Filters keys ending with "|roleId" (scan-and-filter, not IndexedStore).
   *
   * @throws IllegalArgumentException if roleId is null or empty
   */
  public List<Membership> listByRole(String roleId) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(roleId), "roleId must not be null or empty");
    String suffix = RbacConfig.KEY_SEP + roleId;
    return StreamSupport.stream(store.get().find().spliterator(), false)
        .filter(doc -> doc.getKey().endsWith(suffix))
        .map(Document::getValue)
        .collect(Collectors.toList());
  }

  /**
   * Returns all memberships in the store. Intended for system table queries.
   */
  public List<Membership> listAll() {
    return StreamSupport.stream(store.get().find().spliterator(), false)
        .map(Document::getValue)
        .collect(Collectors.toList());
  }

  /**
   * Removes all memberships for the given role. Package-private -- used only by RoleStore.delete() cascade.
   *
   * <p>Keys are collected to a list first to avoid ConcurrentModificationException on the
   * one-shot live iterator returned by {@code find()}.
   */
  void deleteByRole(String roleId) {
    String suffix = RbacConfig.KEY_SEP + roleId;
    StreamSupport.stream(store.get().find().spliterator(), false)
        .filter(doc -> doc.getKey().endsWith(suffix))
        .map(Document::getKey)
        .collect(Collectors.toList()) // collect to list first to avoid ConcurrentModificationException on the live iterator
        .forEach(key -> store.get().delete(key));
  }

  /**
   * KV store creator. The class name {@code StoreCreator} is the permanent store
   * identifier -- do NOT rename this class in any future phase.
   */
  public static final class StoreCreator implements KVStoreCreationFunction<String, Membership> {
    @Override
    public KVStore<String, Membership> build(StoreBuildingFactory factory) {
      return factory
          .<String, Membership>newStore()
          .name(RbacConfig.MEMBERSHIPS_STORE)
          .keyFormat(Format.ofString())
          .valueFormat(Format.ofProtobuf(Membership.class))
          .build();
    }
  }
}
