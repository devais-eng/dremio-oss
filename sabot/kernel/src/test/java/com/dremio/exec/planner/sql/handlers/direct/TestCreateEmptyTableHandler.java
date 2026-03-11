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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.dataset.TableVersionType;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.VersionedPlugin;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.parser.DremioSqlColumnDeclaration;
import com.dremio.exec.planner.sql.parser.PartitionDistributionStrategy;
import com.dremio.exec.planner.sql.parser.SqlCreateEmptyTable;
import com.dremio.exec.planner.sql.parser.SqlTableVersionSpec;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.options.OptionManager;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.test.UserExceptionAssert;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/** Unit tests for CreateEmptyTableHandler AT BRANCH validation on non-versioned sources. */
@RunWith(MockitoJUnitRunner.class)
public class TestCreateEmptyTableHandler {

  @Mock private Catalog catalog;
  @Mock private UserSession userSession;
  @Mock private SqlHandlerConfig config;
  @Mock private QueryContext queryContext;
  @Mock private OptionManager optionManager;

  private CreateEmptyTableHandler handler;

  @Before
  public void setUp() {
    when(config.getContext()).thenReturn(queryContext);
    when(queryContext.getOptions()).thenReturn(optionManager);
    handler = new CreateEmptyTableHandler(catalog, config, userSession, false);
  }

  private SqlCreateEmptyTable buildSqlCreateEmptyTable(SqlTableVersionSpec versionSpec) {
    List<String> tablePath = List.of("mysource", "mytable");
    SqlIdentifier tblName = new SqlIdentifier(tablePath, SqlParserPos.ZERO);

    SqlNodeList fieldList = new SqlNodeList(SqlParserPos.ZERO);
    DremioSqlColumnDeclaration col = mock(DremioSqlColumnDeclaration.class);
    fieldList.add(col);

    SqlNodeList emptyList = new SqlNodeList(SqlParserPos.ZERO);
    SqlLiteral singleWriter = SqlLiteral.createBoolean(false, SqlParserPos.ZERO);

    return new SqlCreateEmptyTable(
        SqlParserPos.ZERO,
        tblName,
        fieldList,
        /* ifNotExists= */ false,
        PartitionDistributionStrategy.UNSPECIFIED,
        /* partitionTransforms= */ emptyList,
        /* formatOptions= */ emptyList,
        /* location= */ null,
        singleWriter,
        /* sortFieldList= */ emptyList,
        /* distributionColumns= */ emptyList,
        /* policy= */ null,
        /* tablePropertyNameList= */ emptyList,
        /* tablePropertyValueList= */ emptyList,
        versionSpec,
        /* clusterKeys= */ emptyList);
  }

  /**
   * When AT BRANCH is specified on a non-versioned source, CreateEmptyTableHandler must throw a
   * VALIDATION UserException instead of silently creating the table on the default branch.
   */
  @Test
  public void createTable_atBranch_nonVersionedSource_throwsValidation() throws Exception {
    List<String> tablePath = List.of("mysource", "mytable");
    NamespaceKey resolvedPath = new NamespaceKey(tablePath);

    SqlTableVersionSpec branchVersionSpec =
        new SqlTableVersionSpec(
            SqlParserPos.ZERO,
            TableVersionType.BRANCH,
            SqlLiteral.createCharString("dev", SqlParserPos.ZERO),
            null);

    SqlCreateEmptyTable sqlNode = buildSqlCreateEmptyTable(branchVersionSpec);

    when(catalog.resolveSingle(any(NamespaceKey.class))).thenReturn(resolvedPath);
    when(userSession.getSessionVersionForSource("mysource"))
        .thenReturn(VersionContext.NOT_SPECIFIED);

    StoragePlugin nonVersionedSource = mock(StoragePlugin.class);
    when(nonVersionedSource.isWrapperFor(VersionedPlugin.class)).thenReturn(false);
    when(catalog.getSource("mysource")).thenReturn(nonVersionedSource);

    UserExceptionAssert.assertThatThrownBy(
            () ->
                handler.toResult(
                    "CREATE TABLE mysource.mytable AT BRANCH dev (id INT)", sqlNode))
        .hasErrorType(VALIDATION)
        .hasMessageContaining(
            "does not support AT [BRANCH] version specification for DDL operations");
  }

  /**
   * When AT BRANCH is specified on a versioned source (VersionedPlugin), the handler should pass
   * the new validation check without throwing the "does not support AT" error.
   */
  @Test
  public void createTable_atBranch_versionedSource_doesNotThrowValidationError() throws Exception {
    List<String> tablePath = List.of("mysource", "mytable");
    NamespaceKey resolvedPath = new NamespaceKey(tablePath);

    SqlTableVersionSpec branchVersionSpec =
        new SqlTableVersionSpec(
            SqlParserPos.ZERO,
            TableVersionType.BRANCH,
            SqlLiteral.createCharString("dev", SqlParserPos.ZERO),
            null);

    SqlCreateEmptyTable sqlNode = buildSqlCreateEmptyTable(branchVersionSpec);

    when(catalog.resolveSingle(any(NamespaceKey.class))).thenReturn(resolvedPath);
    when(userSession.getSessionVersionForSource("mysource"))
        .thenReturn(VersionContext.NOT_SPECIFIED);

    StoragePlugin versionedSource = mock(StoragePlugin.class);
    when(versionedSource.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(catalog.getSource("mysource")).thenReturn(versionedSource);

    // The call may throw a different exception once past the new validation (e.g. downstream mocks
    // not configured), but it must NOT throw the "does not support AT" validation error.
    assertThatThrownBy(
            () ->
                handler.toResult(
                    "CREATE TABLE mysource.mytable AT BRANCH dev (id INT)", sqlNode))
        .satisfies(
            ex -> {
              String msg = ex.getMessage();
              if (msg != null) {
                org.assertj.core.api.Assertions.assertThat(msg)
                    .doesNotContain("does not support AT [BRANCH] version specification");
              }
            });
  }

  /**
   * When no AT clause is specified (NOT_SPECIFIED), the validation check must be skipped entirely
   * even for non-versioned sources. This confirms no regression on the normal code path.
   */
  @Test
  public void createTable_noAtClause_nonVersionedSource_doesNotThrowValidationError()
      throws Exception {
    List<String> tablePath = List.of("mysource", "mytable");
    NamespaceKey resolvedPath = new NamespaceKey(tablePath);

    SqlCreateEmptyTable sqlNode = buildSqlCreateEmptyTable(SqlTableVersionSpec.NOT_SPECIFIED);

    when(catalog.resolveSingle(any(NamespaceKey.class))).thenReturn(resolvedPath);
    when(userSession.getSessionVersionForSource("mysource"))
        .thenReturn(VersionContext.NOT_SPECIFIED);

    StoragePlugin nonVersionedSource = mock(StoragePlugin.class);

    // The call may throw a different exception downstream, but NOT the "does not support AT" error.
    assertThatThrownBy(
            () -> handler.toResult("CREATE TABLE mysource.mytable (id INT)", sqlNode))
        .satisfies(
            ex -> {
              String msg = ex.getMessage();
              if (msg != null) {
                org.assertj.core.api.Assertions.assertThat(msg)
                    .doesNotContain("does not support AT [BRANCH] version specification");
              }
            });
  }
}
