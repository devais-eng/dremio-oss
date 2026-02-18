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

import com.dremio.exec.store.sys.accesscontrol.SysTableRoleInfo;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response DTO for a single RBAC role. Used in GET /api/v3/rbac/roles responses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RbacRole {

  private final String roleId;
  private final String roleName;
  private final String type; // "SYSTEM" or "USER"
  private final String createdBy;

  @JsonCreator
  public RbacRole(
      @JsonProperty("roleId") String roleId,
      @JsonProperty("roleName") String roleName,
      @JsonProperty("type") String type,
      @JsonProperty("createdBy") String createdBy) {
    this.roleId = roleId;
    this.roleName = roleName;
    this.type = type;
    this.createdBy = createdBy;
  }

  /**
   * Maps a {@link SysTableRoleInfo} system table record to an {@link RbacRole} REST response DTO.
   */
  public static RbacRole fromSysTableRoleInfo(SysTableRoleInfo info) {
    return new RbacRole(info.role_id, info.role_name, info.role_type, info.created_by);
  }

  public String getRoleId() {
    return roleId;
  }

  public String getRoleName() {
    return roleName;
  }

  public String getType() {
    return type;
  }

  public String getCreatedBy() {
    return createdBy;
  }
}
