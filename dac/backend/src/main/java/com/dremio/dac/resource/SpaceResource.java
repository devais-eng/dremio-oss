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

import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.SPACE;

import com.dremio.common.exceptions.UserException;
import com.dremio.config.DremioConfig;
import com.dremio.dac.annotations.RestResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.explore.model.Dataset;
import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.explore.model.DatasetResourcePath;
import com.dremio.dac.explore.model.DatasetVersionResourcePath;
import com.dremio.dac.model.namespace.NamespaceTree;
import com.dremio.dac.model.spaces.Space;
import com.dremio.dac.model.spaces.SpaceName;
import com.dremio.dac.model.spaces.SpacePath;
import com.dremio.dac.proto.model.dataset.VirtualDatasetUI;
import com.dremio.dac.service.collaboration.CollaborationHelper;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.errors.DatasetNotFoundException;
import com.dremio.dac.service.errors.FileNotFoundException;
import com.dremio.dac.service.errors.SpaceNotFoundException;
import com.dremio.exec.rbac.RbacService;
import com.dremio.service.namespace.BoundedDatasetCount;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.space.proto.SpaceConfig;
import java.security.AccessControlException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Rest resource for spaces. */
@RestResource
@Secured
@RolesAllowed({"admin", "user"})
@Path("/space/{spaceName}")
public class SpaceResource {
  private static final Logger logger = LoggerFactory.getLogger(SpaceResource.class);

  private final NamespaceService namespaceService;
  private final DatasetVersionMutator datasetService;
  private final CollaborationHelper collaborationService;
  private final SpaceName spaceName;
  private final SpacePath spacePath;
  @Nullable private final RbacService rbacService;
  @Nullable private final DremioConfig dremioConfig;
  private final SecurityContext securityContext;

  @Inject
  public SpaceResource(
      NamespaceService namespaceService,
      DatasetVersionMutator datasetService,
      CollaborationHelper collaborationService,
      @PathParam("spaceName") SpaceName spaceName,
      @Context SecurityContext securityContext,
      @Nullable RbacService rbacService,
      @Nullable DremioConfig dremioConfig) {
    this.namespaceService = namespaceService;
    this.datasetService = datasetService;
    this.collaborationService = collaborationService;
    this.spaceName = spaceName;
    this.spacePath = new SpacePath(spaceName);
    this.securityContext = securityContext;
    this.rbacService = rbacService;
    this.dremioConfig = dremioConfig;
  }

  protected Space newSpace(SpaceConfig spaceConfig, NamespaceTree contents, int datasetCount)
      throws Exception {
    return Space.newInstance(spaceConfig, contents, datasetCount);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Space getSpace(
      @QueryParam("includeContents") @DefaultValue("true") boolean includeContents)
      throws Exception {
    try {
      SpaceConfig config = namespaceService.getSpace(spacePath.toNamespaceKey());
      List<NameSpaceContainer> children =
          namespaceService.list(spacePath.toNamespaceKey(), null, Integer.MAX_VALUE);
      children = filterByRbacVisibility(children);

      int datasetCount;
      if (rbacService == null
          || dremioConfig == null
          || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)
          || rbacService.isAdminMember(securityContext.getUserPrincipal().getName())) {
        // Optimized path: use namespace count for admin/RBAC-off
        datasetCount =
            namespaceService
                .getDatasetCount(
                    spacePath.toNamespaceKey(),
                    BoundedDatasetCount.SEARCH_TIME_LIMIT_MS,
                    BoundedDatasetCount.COUNT_LIMIT_TO_STOP_SEARCH)
                .getCount();
      } else {
        // RBAC-aware path: count datasets from filtered children
        datasetCount =
            (int)
                children.stream()
                    .filter(c -> c.getType() == NameSpaceContainer.Type.DATASET)
                    .count();
      }

      NamespaceTree contents = null;
      if (includeContents) {
        contents = newNamespaceTree(children);
      }
      return newSpace(config, contents, datasetCount);
    } catch (AccessControlException e) {
      throw UserException.validationError(e)
          .message("Access denied to view space contents.")
          .buildSilently();
    } catch (NamespaceNotFoundException nfe) {
      throw new SpaceNotFoundException(spacePath.getSpaceName().getName(), nfe);
    }
  }

  @GET
  @Path("dataset/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public Dataset getDataset(@PathParam("path") String path)
      throws NamespaceException, FileNotFoundException, DatasetNotFoundException {
    DatasetPath datasetPath = DatasetPath.fromURLPath(spaceName, path);
    final DatasetConfig datasetConfig = namespaceService.getDataset(datasetPath.toNamespaceKey());
    final VirtualDatasetUI vds =
        datasetService.get(datasetPath, datasetConfig.getVirtualDataset().getVersion());
    return Dataset.newInstance(
        new DatasetResourcePath(datasetPath),
        new DatasetVersionResourcePath(datasetPath, vds.getVersion()),
        datasetPath.getDataset(),
        vds.getSql(),
        vds,
        datasetService.getJobsCount(datasetPath.toNamespaceKey()),
        null);
  }

  protected NamespaceTree newNamespaceTree(List<NameSpaceContainer> children)
      throws DatasetNotFoundException, NamespaceException {
    return NamespaceTree.newInstance(datasetService, children, SPACE, collaborationService);
  }

  private List<NameSpaceContainer> filterByRbacVisibility(List<NameSpaceContainer> children) {
    if (rbacService == null
        || dremioConfig == null
        || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
      return children;
    }
    String userName = securityContext.getUserPrincipal().getName();
    if (rbacService.isAdminMember(userName)) {
      return children;
    }
    Set<String> accessiblePaths = rbacService.getAccessibleObjectPaths(userName);
    return children.stream()
        .filter(
            c -> {
              if (c.getType() == NameSpaceContainer.Type.DATASET) {
                DatasetConfig ds = c.getDataset();
                if (ds.getType() == DatasetType.VIRTUAL_DATASET) {
                  String objectPath = String.join(".", c.getFullPathList());
                  return rbacService.hasPrivilege(userName, "SELECT", "VDS", objectPath);
                }
                return true; // PDS always visible
              }
              if (c.getType() == NameSpaceContainer.Type.FUNCTION) {
                String objectPath = String.join(".", c.getFullPathList());
                return rbacService.hasPrivilege(userName, "EXECUTE", "FUNCTION", objectPath);
              }
              if (c.getType() == NameSpaceContainer.Type.FOLDER) {
                String folderPath = String.join(".", c.getFullPathList());
                return rbacService.hasAccessibleChildUnderPath(accessiblePaths, folderPath);
              }
              return true; // spaces, sources at child level
            })
        .collect(Collectors.toList());
  }
}
