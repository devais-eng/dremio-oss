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
package com.dremio.dac.resource;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import com.dremio.config.DremioConfig;
import com.dremio.dac.annotations.RestResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.model.sources.SourceUI;
import com.dremio.dac.model.sources.Sources;
import com.dremio.dac.service.source.SourceService;
import com.dremio.exec.catalog.ConnectionReader;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.store.CatalogService;
import com.dremio.service.namespace.BoundedDatasetCount;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.source.proto.SourceConfig;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;

/** Resource for information about sources. */
@RestResource
@Secured
@RolesAllowed({"admin", "user"})
@Path("/sources")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class SourcesResource {
  private final NamespaceService namespaceService;
  private final SourceService sourceService;
  private final ConnectionReader connectionReader;
  private final CatalogService catalogService;
  private final SecurityContext securityContext;
  @Nullable private final RbacService rbacService;
  @Nullable private final DremioConfig dremioConfig;

  @Inject
  public SourcesResource(
      NamespaceService namespaceService,
      SourceService sourceService,
      ConnectionReader connectionReader,
      CatalogService catalogService,
      @Context SecurityContext securityContext,
      @Nullable RbacService rbacService,
      @Nullable DremioConfig dremioConfig) {
    this.namespaceService = namespaceService;
    this.sourceService = sourceService;
    this.connectionReader = connectionReader;
    this.catalogService = catalogService;
    this.securityContext = securityContext;
    this.rbacService = rbacService;
    this.dremioConfig = dremioConfig;
  }

  @GET
  public Sources getSources(
      @QueryParam("includeDatasetCount") @DefaultValue("true") boolean includeDatasetCount)
      throws Exception {
    final Sources sources = new Sources();
    Set<String> accessiblePaths = getUserAccessiblePaths();
    for (SourceConfig sourceConfig : sourceService.getSources()) {
      if (accessiblePaths != null) {
        String prefix = sourceConfig.getName() + ".";
        if (accessiblePaths.stream().noneMatch(p -> p.startsWith(prefix))) {
          continue;
        }
      }

      SourceUI source = newSource(sourceConfig);

      if (includeDatasetCount) {
        BoundedDatasetCount datasetCount =
            namespaceService.getDatasetCount(
                new NamespaceKey(source.getName()),
                BoundedDatasetCount.SEARCH_TIME_LIMIT_MS,
                BoundedDatasetCount.COUNT_LIMIT_TO_STOP_SEARCH);
        source.setNumberOfDatasets(datasetCount.getCount());
        source.setDatasetCountBounded(datasetCount.isCountBound() || datasetCount.isTimeBound());
      }

      SourceState state = catalogService.getSourceState(sourceConfig.getName());
      source.setState(state);
      source.setSourceChangeState(sourceConfig.getSourceChangeState());
      source.setLastModifiedAt(sourceConfig.getLastModifiedAt());

      sources.add(source);
    }
    return sources;
  }

  @Nullable
  private Set<String> getUserAccessiblePaths() {
    if (rbacService == null
        || dremioConfig == null
        || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
      return null;
    }
    String userName = securityContext.getUserPrincipal().getName();
    if (rbacService.isAdminMember(userName)) {
      return null;
    }
    return rbacService.getAccessibleObjectPaths(userName);
  }

  /** Response class for metadata impacting requests */
  public class MetadataImpactingResponse {
    private final boolean isMetadataImpacting;

    MetadataImpactingResponse(boolean isMetadataImpacting) {
      this.isMetadataImpacting = isMetadataImpacting;
    }

    public boolean getIsMetadataImpacting() {
      return isMetadataImpacting;
    }
  }

  @POST
  @Path("isMetadataImpacting")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public MetadataImpactingResponse isMetadataImpacating(SourceUI sourceUI) {
    final SourceConfig sourceConfig = sourceUI.asSourceConfig();
    return new MetadataImpactingResponse(
        catalogService.isSourceConfigMetadataImpacting(sourceConfig));
  }

  protected SourceUI newSource(SourceConfig sourceConfig) throws Exception {
    return SourceUI.get(sourceConfig, connectionReader);
  }
}
