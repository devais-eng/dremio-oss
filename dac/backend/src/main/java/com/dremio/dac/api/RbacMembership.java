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

import com.dremio.exec.rbac.proto.RbacProto.Membership;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response DTO for a single RBAC role membership. Used in /api/v3/rbac/roles/{name}/members. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RbacMembership {

  private final String userName;
  private final String roleId;
  private final String grantedBy;

  @JsonCreator
  public RbacMembership(
      @JsonProperty("userName") String userName,
      @JsonProperty("roleId") String roleId,
      @JsonProperty("grantedBy") String grantedBy) {
    this.userName = userName;
    this.roleId = roleId;
    this.grantedBy = grantedBy;
  }

  /** Maps a {@link Membership} proto message to an {@link RbacMembership} REST response DTO. */
  public static RbacMembership fromProto(Membership m) {
    return new RbacMembership(m.getUserName(), m.getRoleId(), m.getGrantedBy());
  }

  public String getUserName() {
    return userName;
  }

  public String getRoleId() {
    return roleId;
  }

  public String getGrantedBy() {
    return grantedBy;
  }
}
