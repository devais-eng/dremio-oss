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
package com.dremio.dac.api;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.common.exceptions.UserException;
import com.dremio.config.DremioConfig;
import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.service.collaboration.CollaborationHelper;
import com.dremio.dac.service.collaboration.Tags;
import com.dremio.dac.service.collaboration.Wiki;
import com.dremio.exec.rbac.RbacService;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

/** Collaboration API resource. */
@APIResource
@Secured
@RolesAllowed({"user", "admin"})
@Path("/catalog/{id}/collaboration")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class CollaborationResource {
  private final CollaborationHelper collaborationHelper;
  private final SecurityContext securityContext;
  @Nullable private final RbacService rbacService;
  @Nullable private final DremioConfig dremioConfig;
  private final NamespaceService namespaceService;

  @Inject
  public CollaborationResource(
      CollaborationHelper collaborationHelper,
      NamespaceService namespaceService,
      @Context SecurityContext securityContext,
      @Nullable RbacService rbacService,
      @Nullable DremioConfig dremioConfig) {
    this.collaborationHelper = collaborationHelper;
    this.namespaceService = namespaceService;
    this.securityContext = securityContext;
    this.rbacService = rbacService;
    this.dremioConfig = dremioConfig;
  }

  @GET
  @Path("/tag")
  public Tags getTagsForEntity(@PathParam("id") String id) throws NamespaceException {
    Optional<Tags> tags = collaborationHelper.getTags(id);
    return tags.orElseGet(() -> new Tags(null, null));
  }

  @POST
  @Path("/tag")
  public Tags setTagsForEntity(@PathParam("id") String id, Tags tags) throws NamespaceException {
    enforceAlterPrivilege(id);
    collaborationHelper.setTags(id, tags);

    return getTagsForEntity(id);
  }

  @GET
  @Path("/wiki")
  public Wiki getWikiForEntity(@PathParam("id") String id) throws NamespaceException {
    Optional<Wiki> wiki = collaborationHelper.getWiki(id);
    return wiki.orElseGet(() -> new Wiki("", null));
  }

  @POST
  @Path("/wiki")
  public Wiki setWikiForEntity(@PathParam("id") String id, Wiki wiki) throws NamespaceException {
    enforceAlterPrivilege(id);
    collaborationHelper.setWiki(id, wiki);

    return getWikiForEntity(id);
  }

  /**
   * Enforces ALTER privilege on the entity identified by {@code id} before allowing mutation.
   *
   * <p>Uses the three-way null guard established in Phase 21: if RBAC is not wired or not enabled,
   * the check is a no-op. Admin users bypass the check entirely. Versioned (Nessie) entities are
   * skipped because they have no namespace entry.
   */
  private void enforceAlterPrivilege(String id) {
    if (rbacService == null
        || dremioConfig == null
        || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
      return;
    }
    String userName = securityContext.getUserPrincipal().getName();
    if (rbacService.isAdminMember(userName)) {
      return;
    }
    // Versioned entities (Nessie) -- skip RBAC for now (no namespace entry)
    if (VersionedDatasetId.tryParse(id) != null) {
      return;
    }
    Optional<NameSpaceContainer> container = namespaceService.getEntityById(new EntityId(id));
    if (container.isEmpty()) {
      return; // entity not found -- let downstream throw
    }
    NameSpaceContainer entity = container.get();
    // Determine object type and path for privilege check
    String objectPath = String.join(".", entity.getFullPathList());
    String objectType;
    switch (entity.getType()) {
      case DATASET:
        objectType =
            entity.getDataset().getType() == DatasetType.VIRTUAL_DATASET ? "VDS" : "PDS";
        break;
      case FOLDER:
        // Folder ALTER requires privilege on parent space
        objectType = "SPACE";
        objectPath = entity.getFullPathList().get(0);
        break;
      case SPACE:
        objectType = "SPACE";
        break;
      case SOURCE:
        // Source tags/wiki mutation is admin-only
        throw UserException.validationError()
            .message("Permission denied: only administrators can modify source metadata.")
            .buildSilently();
      default:
        return; // HOME and other types -- allow
    }
    if (!rbacService.hasPrivilege(userName, "ALTER", objectType, objectPath)) {
      throw UserException.validationError()
          .message("Permission denied: ALTER privilege required on '%s'.", objectPath)
          .buildSilently();
    }
  }
}
