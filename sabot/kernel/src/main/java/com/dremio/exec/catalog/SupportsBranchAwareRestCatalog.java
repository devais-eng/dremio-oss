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
package com.dremio.exec.catalog;

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.Wrapper;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import java.util.Optional;

/**
 * Narrow interface for branch-aware REST catalog access. This is NOT the full VersionedPlugin (25+
 * methods). It exposes only the 3 methods needed for AT BRANCH/TAG query dispatch on
 * Nessie-enabled RESTCATALOG sources.
 *
 * <p>Implemented by RestIcebergCatalogPlugin, conditional on Nessie detection at startup.
 * CatalogImpl uses isWrapperFor(SupportsBranchAwareRestCatalog.class) to determine whether a
 * source supports branch-aware queries.
 */
public interface SupportsBranchAwareRestCatalog extends Wrapper {

  /**
   * Lightweight version context resolution. Maps unresolved VersionContext (BRANCH, TAG,
   * NOT_SPECIFIED) to a ResolvedVersionContext without a server round-trip. COMMIT resolution is
   * not supported for MVP.
   *
   * @param versionContext the unresolved version context from the query
   * @return resolved version context
   */
  ResolvedVersionContext resolveVersionContext(VersionContext versionContext);

  /**
   * Loads a dataset handle from a branch-scoped catalog accessor. Encapsulates the branch accessor
   * lookup and table load entirely within the plugin, avoiding module dependency issues between
   * kernel and plugin modules.
   *
   * @param branchName the branch to scope the table load to
   * @param datasetPath the entity path of the dataset
   * @param options dataset retrieval options
   * @return an optional dataset handle, empty if the dataset does not exist on the given branch
   */
  Optional<DatasetHandle> getDatasetHandleForBranch(
      String branchName, EntityPath datasetPath, GetDatasetOption... options);

  /**
   * Returns the server-defined default branch name discovered during source startup. Only
   * meaningful when isWrapperFor(SupportsBranchAwareRestCatalog.class) returns true.
   *
   * @return the default branch name (e.g., "main")
   */
  String getDefaultBranch();

  /**
   * Returns true if the given branch exists in this Nessie-backed catalog. Returns false for
   * branch-not-found. Only propagates network/auth errors as RuntimeException.
   *
   * @param branchName the branch name to check
   * @return true if the branch exists, false otherwise
   */
  boolean branchExists(String branchName);
}
