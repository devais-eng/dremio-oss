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

/**
 * Constants and key-format utilities for the RBAC KV stores.
 *
 * <p>Store name prefix {@code oss_rbac_} isolates these stores from Dremio Enterprise
 * Edition's own RBAC implementation, which uses a different prefix.
 *
 * <p>ADMIN and PUBLIC are synthetic constants handled in code -- they are never
 * written to any KV store.
 */
public final class RbacConfig {

  /** Store name for the roles KV store. */
  public static final String ROLES_STORE = "oss_rbac_roles";

  /** Store name for the grants KV store. */
  public static final String GRANTS_STORE = "oss_rbac_grants";

  /** Store name for the memberships KV store. */
  public static final String MEMBERSHIPS_STORE = "oss_rbac_memberships";

  /** Separator for composite KV store keys. Safe for slugified role IDs, dot-paths, and privilege names. */
  public static final String KEY_SEP = "|";

  private RbacConfig() {}

  /**
   * Builds the composite key for a grant record.
   *
   * <p>Format: {@code {role_id}|{object_type}|{object_path}|{privilege}}
   * <br>Example: {@code analyst|VDS|schemas.my_view|SELECT}
   *
   * <p>Uses {@link com.dremio.datastore.format.Format#ofString()} in the store (NOT
   * {@code Format.ofCompoundFormat()}) to keep keys human-readable for debugging.
   *
   * <p>Key components:
   * <ul>
   *   <li>{@code roleId} -- slugified role name (e.g. "analyst")</li>
   *   <li>{@code objectType} -- plain string: "VDS", "FUNCTION"</li>
   *   <li>{@code objectPath} -- dot-delimited path (e.g. "schemas.my_view")</li>
   *   <li>{@code privilege} -- plain string: "SELECT", "EXECUTE", "CREATE_VIEW"</li>
   * </ul>
   */
  public static String grantKey(
      String roleId, String objectType, String objectPath, String privilege) {
    return roleId + KEY_SEP + objectType + KEY_SEP + objectPath + KEY_SEP + privilege;
  }

  /**
   * Builds the composite key for a membership record.
   *
   * <p>Format: {@code {user_name}|{role_id}}
   * <br>Example: {@code alice|analyst}
   */
  public static String membershipKey(String userName, String roleId) {
    return userName + KEY_SEP + roleId;
  }
}
