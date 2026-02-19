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
import com.dremio.exec.planner.sql.parser.SqlRevokeRole;
import com.dremio.exec.rbac.RbacService;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlNode;

/**
 * Handler for REVOKE ROLE ... FROM USER ... SQL statement. Loaded via Class.forName by
 * SqlRevokeRole.
 */
public class RoleRevokeHandler extends SimpleDirectHandler {
  private final QueryContext context;

  public RoleRevokeHandler(QueryContext context) {
    this.context = context;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    final SqlRevokeRole revokeRole = SqlNodeUtil.unwrap(sqlNode, SqlRevokeRole.class);
    final String roleName = revokeRole.getRoleToRevoke().getSimple();
    final String revokeeName = revokeRole.getRevokee().getSimple();
    final String userName = context.getQueryUserName();
    final RbacService rbacService = context.getRbacService();

    if (rbacService == null || !rbacService.isAdminMember(userName)) {
      throw UserException.permissionError()
          .message("Only administrators can execute RBAC DDL statements.")
          .buildSilently();
    }

    rbacService.removeMembership(revokeeName, roleName);
    return Collections.singletonList(
        SimpleCommandResult.successful("Role '%s' revoked from user '%s'.", roleName, revokeeName));
  }
}
