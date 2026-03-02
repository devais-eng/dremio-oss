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
import com.dremio.exec.planner.sql.parser.SqlGrantOnCatalog;
import com.dremio.exec.rbac.RbacEntityAlreadyExistsException;
import com.dremio.exec.rbac.RbacEntityNotFoundException;
import com.dremio.exec.rbac.RbacService;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;

/**
 * Handler for GRANT privilege ON entity_type entity TO ROLE role SQL statement. Loaded via
 * Class.forName by SqlGrantOnCatalog.
 *
 * <p>Handles:
 *
 * <ul>
 *   <li>GRANT SELECT ON VDS path TO ROLE role
 *   <li>GRANT CREATE_VIEW ON VDS path TO ROLE role
 *   <li>GRANT EXECUTE ON FUNCTION path TO ROLE role
 * </ul>
 */
public class CatalogGrantHandler extends SimpleDirectHandler {
  private final QueryContext context;

  public CatalogGrantHandler(QueryContext context) {
    this.context = context;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    final SqlGrantOnCatalog grantNode = SqlNodeUtil.unwrap(sqlNode, SqlGrantOnCatalog.class);
    final String userName = context.getQueryUserName();
    final RbacService rbacService = context.getRbacService();

    if (rbacService == null || !rbacService.isAdminMember(userName)) {
      throw UserException.permissionError()
          .message("Only administrators can execute RBAC DDL statements.")
          .buildSilently();
    }

    final String grantee = grantNode.getGrantee().getSimple();
    final SqlGrant.GrantType entityType =
        grantNode.getEntityType().symbolValue(SqlGrant.GrantType.class);
    final String objectType = entityType.name();
    final String objectPath = String.join(".", grantNode.getEntity().names);

    for (SqlNode privNode : grantNode.getPrivilegeList()) {
      final SqlGrant.Privilege privilege =
          ((SqlLiteral) privNode).symbolValue(SqlGrant.Privilege.class);
      try {
        rbacService.grantPrivilege(grantee, objectType, objectPath, privilege.name(), userName);
      } catch (RbacEntityNotFoundException e) {
        throw UserException.validationError()
            .message("Role '%s' not found.", grantee)
            .buildSilently();
      } catch (RbacEntityAlreadyExistsException e) {
        throw UserException.validationError()
            .message(
                "Privilege %s on %s '%s' already granted to role '%s'.",
                privilege.name(), objectType, objectPath, grantee)
            .buildSilently();
      }
    }

    return Collections.singletonList(
        SimpleCommandResult.successful(
            "Privilege(s) granted on %s '%s' to role '%s'.", objectType, objectPath, grantee));
  }
}
