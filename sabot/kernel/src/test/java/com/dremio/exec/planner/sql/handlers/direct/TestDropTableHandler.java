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
package com.dremio.exec.planner.sql.handlers.direct;

import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.VALIDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.TableMutationOptions;
import com.dremio.exec.catalog.VersionedPlugin;
import com.dremio.exec.planner.sql.parser.ReferenceType;
import com.dremio.exec.planner.sql.parser.SqlDropTable;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.test.UserExceptionAssert;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

/** Unit tests for DropTableHandler AT BRANCH validation on non-versioned sources. */
@RunWith(MockitoJUnitRunner.class)
public class TestDropTableHandler {

  @Mock private Catalog catalog;
  @Mock private UserSession userSession;
  private DropTableHandler handler;

  @Before
  public void setUp() {
    handler = new DropTableHandler(catalog, userSession);
  }

  /**
   * When AT BRANCH is specified on a non-versioned source, DropTableHandler must throw a VALIDATION
   * UserException instead of silently dropping the table from the default branch.
   */
  @Test
  public void dropTable_atBranch_nonVersionedSource_throwsValidation() {
    List<String> tablePath = List.of("mysource", "mytable");
    NamespaceKey resolvedPath = new NamespaceKey(tablePath);

    SqlDropTable dropTableNode =
        new SqlDropTable(
            SqlParserPos.ZERO,
            new SqlIdentifier(tablePath, SqlParserPos.ZERO),
            true, // shouldErrorIfTableDoesNotExist
            ReferenceType.BRANCH,
            new SqlIdentifier("dev", SqlParserPos.ZERO));

    when(catalog.resolveSingle(any(NamespaceKey.class))).thenReturn(resolvedPath);

    StoragePlugin nonVersionedSource = mock(StoragePlugin.class);
    when(nonVersionedSource.isWrapperFor(VersionedPlugin.class)).thenReturn(false);
    when(catalog.getSource("mysource")).thenReturn(nonVersionedSource);

    UserExceptionAssert.assertThatThrownBy(
            () -> handler.toResult("DROP TABLE mysource.mytable AT BRANCH dev", dropTableNode))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("does not support AT [BRANCH] version specification for DDL operations");
  }

  /**
   * When AT BRANCH is specified on a versioned source (VersionedPlugin), the drop should proceed
   * normally without validation error.
   */
  @Test
  public void dropTable_atBranch_versionedSource_proceedsNormally() throws Exception {
    List<String> tablePath = List.of("mysource", "mytable");
    NamespaceKey resolvedPath = new NamespaceKey(tablePath);

    SqlDropTable dropTableNode =
        new SqlDropTable(
            SqlParserPos.ZERO,
            new SqlIdentifier(tablePath, SqlParserPos.ZERO),
            true,
            ReferenceType.BRANCH,
            new SqlIdentifier("dev", SqlParserPos.ZERO));

    when(catalog.resolveSingle(any(NamespaceKey.class))).thenReturn(resolvedPath);

    StoragePlugin versionedSource = mock(StoragePlugin.class);
    when(versionedSource.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(catalog.getSource("mysource")).thenReturn(versionedSource);

    when(userSession.getSessionVersionForSource("mysource"))
        .thenReturn(VersionContext.NOT_SPECIFIED);

    ResolvedVersionContext resolvedVersion =
        ResolvedVersionContext.ofBranch("dev", "abc123");

    try (MockedStatic<CatalogUtil> catalogUtilMock = mockStatic(CatalogUtil.class)) {
      catalogUtilMock
          .when(
              () ->
                  CatalogUtil.resolveVersionContext(
                      any(Catalog.class), eq("mysource"), any(VersionContext.class)))
          .thenReturn(resolvedVersion);
      catalogUtilMock
          .when(() -> CatalogUtil.validateResolvedVersionIsBranch(any()))
          .then(invocation -> null);

      doNothing().when(catalog).dropTable(any(NamespaceKey.class), any(TableMutationOptions.class));

      List<SimpleCommandResult> results =
          handler.toResult("DROP TABLE mysource.mytable AT BRANCH dev", dropTableNode);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).ok).isTrue();
      assertThat(results.get(0).summary).contains("dropped");
    }
  }

  /**
   * When no AT BRANCH is specified (refType is null), the validation check must be skipped entirely
   * -- even for non-versioned sources. This confirms no regression on the normal code path.
   */
  @Test
  public void dropTable_noRefType_nonVersionedSource_proceedsNormally() throws Exception {
    List<String> tablePath = List.of("$scratch", "mytable");
    NamespaceKey resolvedPath = new NamespaceKey(tablePath);

    // refType=null, refValue=null -- normal DROP TABLE without AT clause
    SqlDropTable dropTableNode =
        new SqlDropTable(
            SqlParserPos.ZERO,
            new SqlIdentifier(tablePath, SqlParserPos.ZERO),
            true,
            null,
            null);

    // With $scratch root and null refType/refValue, takes the if-branch (line 73 condition:
    // path.getRoot().equals("$scratch")), so uses catalog.resolveSingle
    when(catalog.resolveSingle(any(NamespaceKey.class))).thenReturn(resolvedPath);

    when(userSession.getSessionVersionForSource("$scratch"))
        .thenReturn(VersionContext.NOT_SPECIFIED);

    try (MockedStatic<CatalogUtil> catalogUtilMock = mockStatic(CatalogUtil.class)) {
      // resolveVersionContext returns null for non-versioned sources
      catalogUtilMock
          .when(
              () ->
                  CatalogUtil.resolveVersionContext(
                      any(Catalog.class), eq("$scratch"), any(VersionContext.class)))
          .thenReturn(null);
      catalogUtilMock
          .when(() -> CatalogUtil.validateResolvedVersionIsBranch(any()))
          .then(invocation -> null);

      doNothing().when(catalog).dropTable(any(NamespaceKey.class), any(TableMutationOptions.class));

      List<SimpleCommandResult> results =
          handler.toResult("DROP TABLE $scratch.mytable", dropTableNode);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).ok).isTrue();
      assertThat(results.get(0).summary).contains("dropped");
    }
  }
}
