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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.parser.SqlCreateRole;
import com.dremio.exec.planner.sql.parser.SqlDropRole;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.planner.sql.parser.SqlGrantOnCatalog;
import com.dremio.exec.planner.sql.parser.SqlGrantRole;
import com.dremio.exec.planner.sql.parser.SqlRevokeOnCatalog;
import com.dremio.exec.planner.sql.parser.SqlRevokeRole;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.store.sys.accesscontrol.AccessControlListingManager;
import java.util.Arrays;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for all 6 RBAC DDL handlers: RoleCreateHandler, RoleDropHandler, RoleGrantHandler,
 * RoleRevokeHandler, CatalogGrantHandler, CatalogRevokeHandler. Verifies correct RbacService method
 * calls, admin-only enforcement, and system table wiring contract.
 */
public class TestRbacDdlHandlers {

  private QueryContext queryContext;
  private RbacService rbacService;

  @Before
  public void setUp() {
    queryContext = mock(QueryContext.class);
    rbacService = mock(RbacService.class);
    when(queryContext.getRbacService()).thenReturn(rbacService);
    when(queryContext.getQueryUserName()).thenReturn("admin_user");
    when(rbacService.isAdminMember("admin_user")).thenReturn(true);
  }

  // -------------------------------------------------------------------------
  // Helper methods for constructing SqlNode instances
  // -------------------------------------------------------------------------

  private SqlIdentifier id(String name) {
    return new SqlIdentifier(name, SqlParserPos.ZERO);
  }

  private SqlIdentifier compoundId(String... parts) {
    return new SqlIdentifier(Arrays.asList(parts), SqlParserPos.ZERO);
  }

  private SqlNodeList privList(SqlGrant.Privilege... privs) {
    SqlNodeList list = new SqlNodeList(SqlParserPos.ZERO);
    for (SqlGrant.Privilege p : privs) {
      list.add(SqlLiteral.createSymbol(p, SqlParserPos.ZERO));
    }
    return list;
  }

  // -------------------------------------------------------------------------
  // RoleCreateHandler tests
  // -------------------------------------------------------------------------

  @Test
  public void testCreateRole_success() throws Exception {
    SqlCreateRole node = new SqlCreateRole(SqlParserPos.ZERO, id("analyst"));
    List<SimpleCommandResult> results =
        new RoleCreateHandler(queryContext).toResult("CREATE ROLE analyst", node);

    verify(rbacService).createRole("analyst", "analyst", "admin_user");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .containsIgnoringCase("analyst")
        .containsIgnoringCase("created");
  }

  @Test
  public void testCreateRole_nonAdmin_throws() {
    when(queryContext.getQueryUserName()).thenReturn("regular_user");
    when(rbacService.isAdminMember("regular_user")).thenReturn(false);

    SqlCreateRole node = new SqlCreateRole(SqlParserPos.ZERO, id("analyst"));

    assertThatThrownBy(
            () -> new RoleCreateHandler(queryContext).toResult("CREATE ROLE analyst", node))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only administrators");
  }

  @Test
  public void testCreateRole_nullRbacService_throws() {
    when(queryContext.getRbacService()).thenReturn(null);

    SqlCreateRole node = new SqlCreateRole(SqlParserPos.ZERO, id("analyst"));

    assertThatThrownBy(
            () -> new RoleCreateHandler(queryContext).toResult("CREATE ROLE analyst", node))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only administrators");
  }

  // -------------------------------------------------------------------------
  // RoleDropHandler tests
  // -------------------------------------------------------------------------

  @Test
  public void testDropRole_success() throws Exception {
    SqlDropRole node = new SqlDropRole(SqlParserPos.ZERO, id("analyst"));
    List<SimpleCommandResult> results =
        new RoleDropHandler(queryContext).toResult("DROP ROLE analyst", node);

    verify(rbacService).deleteRole("analyst");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .containsIgnoringCase("analyst")
        .containsIgnoringCase("dropped");
  }

  @Test
  public void testDropRole_nonAdmin_throws() {
    when(queryContext.getQueryUserName()).thenReturn("regular_user");
    when(rbacService.isAdminMember("regular_user")).thenReturn(false);

    SqlDropRole node = new SqlDropRole(SqlParserPos.ZERO, id("analyst"));

    assertThatThrownBy(() -> new RoleDropHandler(queryContext).toResult("DROP ROLE analyst", node))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only administrators");
  }

  // -------------------------------------------------------------------------
  // RoleGrantHandler tests
  // -------------------------------------------------------------------------

  @Test
  public void testGrantRole_success() throws Exception {
    SqlGrantRole node =
        new SqlGrantRole(
            SqlParserPos.ZERO,
            id("analyst"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.USER, SqlParserPos.ZERO),
            id("alice"));
    List<SimpleCommandResult> results =
        new RoleGrantHandler(queryContext).toResult("GRANT ROLE analyst TO USER alice", node);

    verify(rbacService).addMembership("alice", "analyst", "admin_user");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary).contains("analyst").contains("alice");
  }

  @Test
  public void testGrantRole_nonAdmin_throws() {
    when(queryContext.getQueryUserName()).thenReturn("regular_user");
    when(rbacService.isAdminMember("regular_user")).thenReturn(false);

    SqlGrantRole node =
        new SqlGrantRole(
            SqlParserPos.ZERO,
            id("analyst"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.USER, SqlParserPos.ZERO),
            id("alice"));

    assertThatThrownBy(
            () ->
                new RoleGrantHandler(queryContext)
                    .toResult("GRANT ROLE analyst TO USER alice", node))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only administrators");
  }

  // -------------------------------------------------------------------------
  // RoleRevokeHandler tests
  // -------------------------------------------------------------------------

  @Test
  public void testRevokeRole_success() throws Exception {
    SqlRevokeRole node =
        new SqlRevokeRole(
            SqlParserPos.ZERO,
            id("analyst"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.USER, SqlParserPos.ZERO),
            id("alice"));
    List<SimpleCommandResult> results =
        new RoleRevokeHandler(queryContext).toResult("REVOKE ROLE analyst FROM USER alice", node);

    verify(rbacService).removeMembership("alice", "analyst");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary).contains("analyst").contains("alice");
  }

  @Test
  public void testRevokeRole_nonAdmin_throws() {
    when(queryContext.getQueryUserName()).thenReturn("regular_user");
    when(rbacService.isAdminMember("regular_user")).thenReturn(false);

    SqlRevokeRole node =
        new SqlRevokeRole(
            SqlParserPos.ZERO,
            id("analyst"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.USER, SqlParserPos.ZERO),
            id("alice"));

    assertThatThrownBy(
            () ->
                new RoleRevokeHandler(queryContext)
                    .toResult("REVOKE ROLE analyst FROM USER alice", node))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only administrators");
  }

  // -------------------------------------------------------------------------
  // CatalogGrantHandler tests
  // -------------------------------------------------------------------------

  @Test
  public void testCatalogGrant_selectOnVds_success() throws Exception {
    SqlGrantOnCatalog node =
        new SqlGrantOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.SELECT),
            SqlLiteral.createSymbol(SqlGrant.GrantType.VDS, SqlParserPos.ZERO),
            compoundId("myspace", "myview"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);
    List<SimpleCommandResult> results =
        new CatalogGrantHandler(queryContext)
            .toResult("GRANT SELECT ON VDS myspace.myview TO ROLE analyst", node);

    verify(rbacService).grantPrivilege("analyst", "VDS", "myspace.myview", "SELECT", "admin_user");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .contains("VDS")
        .contains("myspace.myview")
        .contains("analyst");
  }

  @Test
  public void testCatalogGrant_executeOnFunction_success() throws Exception {
    SqlGrantOnCatalog node =
        new SqlGrantOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.EXECUTE),
            SqlLiteral.createSymbol(SqlGrant.GrantType.FUNCTION, SqlParserPos.ZERO),
            compoundId("myspace", "myfunc"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);
    List<SimpleCommandResult> results =
        new CatalogGrantHandler(queryContext)
            .toResult("GRANT EXECUTE ON FUNCTION myspace.myfunc TO ROLE analyst", node);

    verify(rbacService)
        .grantPrivilege("analyst", "FUNCTION", "myspace.myfunc", "EXECUTE", "admin_user");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .contains("FUNCTION")
        .contains("myspace.myfunc")
        .contains("analyst");
  }

  @Test
  public void testCatalogGrant_nonAdmin_throws() {
    when(queryContext.getQueryUserName()).thenReturn("regular_user");
    when(rbacService.isAdminMember("regular_user")).thenReturn(false);

    SqlGrantOnCatalog node =
        new SqlGrantOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.SELECT),
            SqlLiteral.createSymbol(SqlGrant.GrantType.VDS, SqlParserPos.ZERO),
            compoundId("myspace", "myview"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);

    assertThatThrownBy(
            () ->
                new CatalogGrantHandler(queryContext)
                    .toResult("GRANT SELECT ON VDS myspace.myview TO ROLE analyst", node))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only administrators");
  }

  // -------------------------------------------------------------------------
  // CatalogRevokeHandler tests
  // -------------------------------------------------------------------------

  @Test
  public void testCatalogRevoke_selectOnVds_success() throws Exception {
    SqlRevokeOnCatalog node =
        new SqlRevokeOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.SELECT),
            SqlLiteral.createSymbol(SqlGrant.GrantType.VDS, SqlParserPos.ZERO),
            compoundId("myspace", "myview"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);
    List<SimpleCommandResult> results =
        new CatalogRevokeHandler(queryContext)
            .toResult("REVOKE SELECT ON VDS myspace.myview FROM ROLE analyst", node);

    verify(rbacService).revokePrivilege("analyst", "VDS", "myspace.myview", "SELECT");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .contains("VDS")
        .contains("myspace.myview")
        .contains("analyst");
  }

  @Test
  public void testCatalogRevoke_executeOnFunction_success() throws Exception {
    SqlRevokeOnCatalog node =
        new SqlRevokeOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.EXECUTE),
            SqlLiteral.createSymbol(SqlGrant.GrantType.FUNCTION, SqlParserPos.ZERO),
            compoundId("myspace", "myfunc"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);
    List<SimpleCommandResult> results =
        new CatalogRevokeHandler(queryContext)
            .toResult("REVOKE EXECUTE ON FUNCTION myspace.myfunc FROM ROLE analyst", node);

    verify(rbacService).revokePrivilege("analyst", "FUNCTION", "myspace.myfunc", "EXECUTE");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .contains("FUNCTION")
        .contains("myspace.myfunc")
        .contains("analyst");
  }

  @Test
  public void testCatalogRevoke_nonAdmin_throws() {
    when(queryContext.getQueryUserName()).thenReturn("regular_user");
    when(rbacService.isAdminMember("regular_user")).thenReturn(false);

    SqlRevokeOnCatalog node =
        new SqlRevokeOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.SELECT),
            SqlLiteral.createSymbol(SqlGrant.GrantType.VDS, SqlParserPos.ZERO),
            compoundId("myspace", "myview"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);

    assertThatThrownBy(
            () ->
                new CatalogRevokeHandler(queryContext)
                    .toResult("REVOKE SELECT ON VDS myspace.myview FROM ROLE analyst", node))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only administrators");
  }

  // -------------------------------------------------------------------------
  // PDS GRANT and REVOKE tests (PDS-01, PDS-03)
  // -------------------------------------------------------------------------

  @Test
  public void testCatalogGrant_selectOnPds_success() throws Exception {
    // PDS-01: Admin can GRANT SELECT on a physical table to a role.
    // PDS-03: The grant is stored with objectType "PDS" (distinct from "VDS").
    SqlGrantOnCatalog node =
        new SqlGrantOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.SELECT),
            SqlLiteral.createSymbol(SqlGrant.GrantType.PDS, SqlParserPos.ZERO),
            compoundId("mysource", "myschema", "mytable"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);
    List<SimpleCommandResult> results =
        new CatalogGrantHandler(queryContext)
            .toResult("GRANT SELECT ON PDS mysource.myschema.mytable TO ROLE analyst", node);

    verify(rbacService)
        .grantPrivilege("analyst", "PDS", "mysource.myschema.mytable", "SELECT", "admin_user");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .contains("PDS")
        .contains("mysource.myschema.mytable")
        .contains("analyst");
  }

  @Test
  public void testCatalogRevoke_selectOnPds_success() throws Exception {
    // PDS-01: Admin can REVOKE SELECT on a physical table from a role.
    // PDS-03: The revoke targets objectType "PDS" (distinct from "VDS").
    SqlRevokeOnCatalog node =
        new SqlRevokeOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.SELECT),
            SqlLiteral.createSymbol(SqlGrant.GrantType.PDS, SqlParserPos.ZERO),
            compoundId("mysource", "myschema", "mytable"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);
    List<SimpleCommandResult> results =
        new CatalogRevokeHandler(queryContext)
            .toResult(
                "REVOKE SELECT ON PDS mysource.myschema.mytable FROM ROLE analyst", node);

    verify(rbacService)
        .revokePrivilege("analyst", "PDS", "mysource.myschema.mytable", "SELECT");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .contains("PDS")
        .contains("mysource.myschema.mytable")
        .contains("analyst");
  }

  // -------------------------------------------------------------------------
  // System table wiring verification
  // -------------------------------------------------------------------------

  @Test
  public void testRbacService_implementsAccessControlListingManager() {
    assertThat(AccessControlListingManager.class.isAssignableFrom(RbacService.class)).isTrue();
  }
}
