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

import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.KVStoreCreationFunction;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.datastore.api.StoreBuildingFactory;
import com.dremio.datastore.format.Format;
import com.dremio.exec.rbac.proto.RbacProto.Membership;
import com.google.common.base.Suppliers;
import java.util.function.Supplier;
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

  // Phase 2 will add: get, put, delete, listByUser, listByRole methods here.

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
