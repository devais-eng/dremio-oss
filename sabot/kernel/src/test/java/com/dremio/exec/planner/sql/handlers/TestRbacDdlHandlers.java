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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.ExplainHandler;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.handlers.query.SqlToPlanHandler;
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
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.util.SqlString;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;

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
            .toResult("REVOKE SELECT ON PDS mysource.myschema.mytable FROM ROLE analyst", node);

    verify(rbacService).revokePrivilege("analyst", "PDS", "mysource.myschema.mytable", "SELECT");
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

  // -------------------------------------------------------------------------
  // META-03: EXPLAIN privilege inheritance verification
  // -------------------------------------------------------------------------

  /**
   * META-03 documents that EXPLAIN inherits privilege enforcement through its inner handlers.
   * ExplainHandler.toResult() delegates to NormalHandler (SELECT), InsertTableHandler (INSERT),
   * DeleteHandler (DELETE), UpdateHandler (UPDATE), MergeHandler (MERGE). Each inner handler
   * enforces privileges via catalog.getTable() (deny-by-null) or catalog.validatePrivilege()
   * (explicit throw). ExplainHandler catches all exceptions at line 137 via
   * SqlExceptionHelper.coerceException(), which preserves UserException instances.
   *
   * <p>No code change was needed for META-03 -- the enforcement already works. This test documents
   * the contract: ExplainHandler relies on inner handlers for privilege checks.
   */
  @Test
  public void testExplainHandler_inheritsPrivilegeEnforcement_fromInnerHandlers() {
    // META-03: EXPLAIN delegates to inner handlers that call catalog.getTable()
    // and catalog.validatePrivilege(). These methods enforce RBAC when enabled.
    // ExplainHandler.toResult() catches Exception at line 137 and re-throws via
    // SqlExceptionHelper.coerceException(), preserving UserException type.
    //
    // Inner handler enforcement paths:
    //   NormalHandler (SELECT) -> catalog.getTable() -> isRbacDeniedForVds/Pds -> null ->
    // validation
    // error
    //   InsertTableHandler     -> catalog.validatePrivilege(path, INSERT)
    //   DeleteHandler          -> catalog.validatePrivilege(path, DELETE) + validatePrivilege(path,
    // SELECT)
    //   UpdateHandler          -> catalog.validatePrivilege(...) in validatePrivileges()
    //   MergeHandler           -> catalog.validatePrivilege(...) for INSERT, UPDATE, SELECT
    //
    // This test asserts the architectural fact that ExplainHandler does NOT do its own
    // privilege checks -- it delegates entirely to the inner handler.
    // Full integration testing of EXPLAIN + RBAC requires a running query engine.
    assertThat(ExplainHandler.class.getDeclaredFields())
        .as(
            "ExplainHandler should not have its own RbacService field -- it delegates to inner handlers")
        .extracting(java.lang.reflect.Field::getName)
        .doesNotContain("rbacService");
  }

  // -------------------------------------------------------------------------
  // META-03: EXPLAIN behavioral test — exception propagation (Phase 14)
  // -------------------------------------------------------------------------

  /**
   * META-03 behavioral proof: ExplainHandler.toResult() propagates UserException from the inner
   * handler through SqlExceptionHelper.coerceException(). This test:
   *
   * <ol>
   *   <li>Uses MockedConstruction&lt;SqlHandlerConfig&gt; to intercept the {@code new
   *       SqlHandlerConfig(...)} call inside ExplainHandler's constructor. Without this,
   *       SqlHandlerConfig's constructor calls context.getPlanCacheCreator().resolve(...)
   *       and context.createPlannerNormalizerComponent(...), which NPE with plain mocks.
   *   <li>Subclasses ExplainHandler to override setupInnerHandlerForDefaultCase() to return a mock
   *       SqlToPlanHandler that throws UserException on getPlan().
   *   <li>Asserts that the UserException propagates through toResult().
   * </ol>
   *
   * <p>This replaces the structural assertion (no rbacService field) with behavioral proof that
   * permission errors are not swallowed.
   */
  @Test
  public void testExplainHandler_denied_propagatesPermissionError() throws Exception {
    // Create the permission error that inner handlers throw when catalog denies access
    UserException permissionDenied =
        UserException.permissionError()
            .message("Permission denied: SELECT privilege required on source.table")
            .buildSilently();

    // Create a mock SqlToPlanHandler that throws when getPlan() is called
    SqlToPlanHandler mockInnerHandler = mock(SqlToPlanHandler.class);
    doThrow(permissionDenied)
        .when(mockInnerHandler)
        .getPlan(any(SqlHandlerConfig.class), anyString(), any(SqlNode.class));

    // Create a mock SqlExplain with PHYSICAL depth and a SELECT-kind inner node
    SqlExplain mockExplain = mock(SqlExplain.class);
    SqlLiteral depthLiteral =
        SqlLiteral.createSymbol(SqlExplain.Depth.PHYSICAL, SqlParserPos.ZERO);
    when(mockExplain.operand(2)).thenReturn(depthLiteral);
    when(mockExplain.getDetailLevel()).thenReturn(null);

    // Inner node: a mock SqlNode with SELECT kind, stubbed for toSqlString()
    SqlNode innerSelectNode = mock(SqlNode.class);
    when(innerSelectNode.getKind()).thenReturn(SqlKind.SELECT);
    SqlString mockSqlString = mock(SqlString.class);
    when(mockSqlString.getSql()).thenReturn("SELECT * FROM source.table");
    when(innerSelectNode.toSqlString(any(org.apache.calcite.sql.SqlDialect.class)))
        .thenReturn(mockSqlString);
    when(mockExplain.operand(0)).thenReturn(innerSelectNode);

    // Configure the outerConfig that gets passed to ExplainHandler's constructor.
    // The constructor calls new SqlHandlerConfig(config.getContext(), ...) — we intercept
    // the new SqlHandlerConfig(...) via MockedConstruction to avoid the NPE blocker.
    SqlHandlerConfig outerConfig = mock(SqlHandlerConfig.class);
    when(outerConfig.getContext()).thenReturn(queryContext);
    when(outerConfig.getConverter()).thenReturn(null);
    when(outerConfig.getObserver())
        .thenReturn(com.dremio.exec.planner.observer.AttemptObservers.of());
    when(outerConfig.getMaterializations()).thenReturn(java.util.Optional.empty());

    try (MockedConstruction<SqlHandlerConfig> configConstruction =
        mockConstruction(
            SqlHandlerConfig.class,
            (configMock, ctx) -> {
              // Configure the intercepted SqlHandlerConfig mock so toResult() can
              // call config.setResultMode(), config.getContext().getOptions(), etc.
              when(configMock.getContext()).thenReturn(queryContext);
              when(configMock.getResultMode())
                  .thenReturn(
                      com.dremio.common.logical.PlanProperties.Generator.ResultMode.EXEC);
            })) {

      // Subclass ExplainHandler to inject our mock inner handler
      ExplainHandler handler =
          new ExplainHandler(outerConfig) {
            @Override
            protected SqlToPlanHandler setupInnerHandlerForDefaultCase() {
              return mockInnerHandler;
            }
          };

      // Verify MockedConstruction intercepted the constructor (no NPE)
      assertThat(configConstruction.constructed()).isNotEmpty();

      // The key assertion: UserException propagates through toResult()
      assertThatThrownBy(
              () ->
                  handler.toResult(
                      "EXPLAIN PLAN FOR SELECT * FROM source.table", mockExplain))
          .isInstanceOf(UserException.class)
          .hasMessageContaining("Permission denied");
    }
  }
}
