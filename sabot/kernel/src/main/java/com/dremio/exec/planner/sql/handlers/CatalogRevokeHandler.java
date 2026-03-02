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
package com.dremio.exec.planner.sql.handlers;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.handlers.direct.SimpleDirectHandler;
import com.dremio.exec.planner.sql.handlers.direct.SqlNodeUtil;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.planner.sql.parser.SqlRevokeOnCatalog;
import com.dremio.exec.rbac.RbacService;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;

/**
 * Handler for REVOKE privilege ON entity_type entity FROM ROLE role SQL statement. Loaded via
 * Class.forName by SqlRevokeOnCatalog.
 *
 * <p>Handles:
 *
 * <ul>
 *   <li>REVOKE SELECT ON VDS path FROM ROLE role
 *   <li>REVOKE CREATE_VIEW ON VDS path FROM ROLE role
 *   <li>REVOKE EXECUTE ON FUNCTION path FROM ROLE role
 * </ul>
 */
public class CatalogRevokeHandler extends SimpleDirectHandler {
  private final QueryContext context;

  public CatalogRevokeHandler(QueryContext context) {
    this.context = context;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    final SqlRevokeOnCatalog revokeNode = SqlNodeUtil.unwrap(sqlNode, SqlRevokeOnCatalog.class);
    final String userName = context.getQueryUserName();
    final RbacService rbacService = context.getRbacService();

    if (rbacService == null || !rbacService.isAdminMember(userName)) {
      throw UserException.permissionError()
          .message("Only administrators can execute RBAC DDL statements.")
          .buildSilently();
    }

    final String revokee = revokeNode.getRevokee().getSimple();
    final SqlGrant.GrantType entityType =
        revokeNode.getEntityType().symbolValue(SqlGrant.GrantType.class);
    final String objectType = entityType.name();
    final String objectPath = String.join(".", revokeNode.getEntity().names);

    for (SqlNode privNode : revokeNode.getPrivilegeList()) {
      final SqlGrant.Privilege privilege =
          ((SqlLiteral) privNode).symbolValue(SqlGrant.Privilege.class);
      rbacService.revokePrivilege(revokee, objectType, objectPath, privilege.name());
    }

    return Collections.singletonList(
        SimpleCommandResult.successful(
            "Privilege(s) revoked on %s '%s' from role '%s'.", objectType, objectPath, revokee));
  }
}
