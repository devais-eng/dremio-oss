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
package com.dremio.plugins.icebergcatalog.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.base.Preconditions;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Bounded cache of per-branch {@link IcebergRestCatalogAccessor} instances. Each branch gets its
 * own accessor (and therefore its own isolated table/view cache), preventing cross-branch cache
 * poisoning.
 *
 * <p>Evicted accessors are closed via the removal listener to release their underlying RESTCatalog
 * connection pools.
 */
public class BranchAwareCatalogAccessorCache implements AutoCloseable {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(BranchAwareCatalogAccessorCache.class);

  private final Cache<String, IcebergRestCatalogAccessor> cache;
  private final Function<String, IcebergRestCatalogAccessor> factory;

  /**
   * Creates a new branch accessor cache.
   *
   * @param maxBranches maximum number of per-branch accessors to cache
   * @param expireAfterAccessSeconds TTL for idle branches (uses expireAfterAccess so
   *     actively-used branches stay cached)
   * @param factory function that creates a new {@link IcebergRestCatalogAccessor} for a given
   *     branch name
   */
  public BranchAwareCatalogAccessorCache(
      int maxBranches,
      long expireAfterAccessSeconds,
      Function<String, IcebergRestCatalogAccessor> factory) {
    this.factory = factory;
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maxBranches)
            .expireAfterAccess(expireAfterAccessSeconds, TimeUnit.SECONDS)
            .removalListener(
                (@Nullable String key,
                    @Nullable IcebergRestCatalogAccessor accessor,
                    RemovalCause cause) -> {
                  if (accessor != null) {
                    logger.info(
                        "Closing evicted branch accessor for branch '{}' (cause: {})", key, cause);
                    try {
                      accessor.close();
                    } catch (Exception e) {
                      logger.warn(
                          "Failed to close evicted branch accessor for branch '{}'", key, e);
                    }
                  }
                })
            .build();
  }

  /**
   * Returns the cached accessor for the given branch, creating one if absent.
   *
   * @param branchName non-null, non-empty branch name
   * @return the cached or newly created accessor for the branch
   */
  public IcebergRestCatalogAccessor getOrCreate(String branchName) {
    Preconditions.checkArgument(
        branchName != null && !branchName.isEmpty(), "branchName must not be null or empty");
    return cache.get(branchName, factory::apply);
  }

  @Override
  public void close() {
    cache
        .asMap()
        .forEach(
            (branch, accessor) -> {
              try {
                accessor.close();
              } catch (Exception e) {
                logger.warn("Failed to close branch accessor for branch '{}'", branch, e);
              }
            });
    cache.invalidateAll();
    cache.cleanUp();
  }
}
