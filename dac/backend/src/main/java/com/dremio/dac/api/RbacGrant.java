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

import com.dremio.exec.rbac.proto.RbacProto.Grant;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response DTO for a single RBAC privilege grant. Used in /api/v3/rbac/grants responses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RbacGrant {

  private final String roleId;
  private final String objectType;
  private final String objectPath;
  private final String privilege;
  private final String grantedBy;

  @JsonCreator
  public RbacGrant(
      @JsonProperty("roleId") String roleId,
      @JsonProperty("objectType") String objectType,
      @JsonProperty("objectPath") String objectPath,
      @JsonProperty("privilege") String privilege,
      @JsonProperty("grantedBy") String grantedBy) {
    this.roleId = roleId;
    this.objectType = objectType;
    this.objectPath = objectPath;
    this.privilege = privilege;
    this.grantedBy = grantedBy;
  }

  /** Maps a {@link Grant} proto message to an {@link RbacGrant} REST response DTO. */
  public static RbacGrant fromProto(Grant g) {
    return new RbacGrant(
        g.getRoleId(), g.getObjectType(), g.getObjectPath(), g.getPrivilege(), g.getGrantedBy());
  }

  public String getRoleId() {
    return roleId;
  }

  public String getObjectType() {
    return objectType;
  }

  public String getObjectPath() {
    return objectPath;
  }

  public String getPrivilege() {
    return privilege;
  }

  public String getGrantedBy() {
    return grantedBy;
  }
}
