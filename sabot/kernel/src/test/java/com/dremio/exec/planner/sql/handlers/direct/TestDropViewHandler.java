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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.parser.SqlDropView;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.service.namespace.NamespaceKey;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for DropViewHandler RBAC enforcement. Follows TestDescribeTableHandler pattern for mock
 * setup.
 */
@RunWith(MockitoJUnitRunner.class)
public class TestDropViewHandler {

  @Mock private SqlHandlerConfig config;
  @Mock private QueryContext queryContext;
  @Mock private Catalog catalog;
  private DropViewHandler handler;

  @Before
  public void setUp() {
    when(config.getContext()).thenReturn(queryContext);
    when(queryContext.getCatalog()).thenReturn(catalog);
    handler = new DropViewHandler(config);
  }

  /**
   * LIFE-02: A user with DROP but not SELECT on a VDS must see "Permission denied: SELECT privilege
   * required" instead of "Unknown view". The SELECT check fires before getTableNoColumnCount(),
   * preventing the misleading "Unknown view" error.
   */
  @Test
  public void testDropView_dropOnlyUser_getsPermissionDenied() {
    List<String> viewPath = List.of("myspace", "myview");
    NamespaceKey path = new NamespaceKey(viewPath);
    when(catalog.resolveSingle(any(NamespaceKey.class))).thenReturn(path);

    // DROP passes (mock default -- no exception), SELECT throws
    doThrow(
            UserException.validationError()
                .message("Permission denied: SELECT privilege required on '%s'", path)
                .buildSilently())
        .when(catalog)
        .validatePrivilege(path, SqlGrant.Privilege.SELECT);

    SqlDropView dropView =
        new SqlDropView(
            SqlParserPos.ZERO,
            new SqlIdentifier(viewPath, SqlParserPos.ZERO),
            true, // shouldErrorIfViewDoesNotExist
            null,
            null);

    assertThatThrownBy(() -> handler.toResult("DROP VIEW myspace.myview", dropView))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("SELECT");

    // Verify getTableNoColumnCount was never called -- SELECT check prevents reaching table
    // resolution
    verify(catalog, never()).getTableNoColumnCount(any(NamespaceKey.class));
  }
}
