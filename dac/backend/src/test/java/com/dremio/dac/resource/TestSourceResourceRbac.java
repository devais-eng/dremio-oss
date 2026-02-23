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
package com.dremio.dac.resource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.config.DremioConfig;
import com.dremio.dac.explore.QueryExecutor;
import com.dremio.dac.model.sources.FormatTools;
import com.dremio.dac.model.sources.SourceName;
import com.dremio.dac.server.BufferAllocatorFactory;
import com.dremio.dac.service.source.SourceService;
import com.dremio.exec.catalog.ConnectionReader;
import com.dremio.exec.catalog.SourceCatalog;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.store.CatalogService;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.file.FileFormat;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.service.reflection.ReflectionAdministrationService;
import java.security.Principal;
import javax.inject.Provider;
import javax.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for SourceResource RBAC guards on file browse and promote endpoints. */
public class TestSourceResourceRbac {

  private NamespaceService namespaceService;
  private ReflectionAdministrationService.Factory reflectionService;
  private SourceService sourceService;
  private SourceName sourceName;
  private QueryExecutor executor;
  private SecurityContext securityContext;
  private ConnectionReader connectionReader;
  private SourceCatalog sourceCatalog;
  private FormatTools formatTools;
  private BufferAllocatorFactory allocatorFactory;
  private OptionManager optionManager;
  private Provider<Orphanage.Factory> orphanageFactoryProvider;
  private CatalogService catalogService;
  private RbacService rbacService;
  private DremioConfig dremioConfig;

  @BeforeEach
  public void setup() throws Exception {
    namespaceService = mock(NamespaceService.class);
    reflectionService = mock(ReflectionAdministrationService.Factory.class);
    sourceService = mock(SourceService.class);
    sourceName = new SourceName("testSource");
    executor = mock(QueryExecutor.class);
    securityContext = mock(SecurityContext.class);
    connectionReader = mock(ConnectionReader.class);
    sourceCatalog = mock(SourceCatalog.class);
    formatTools = mock(FormatTools.class);
    allocatorFactory = mock(BufferAllocatorFactory.class);
    optionManager = mock(OptionManager.class);
    orphanageFactoryProvider = mock(Provider.class);
    catalogService = mock(CatalogService.class);
    rbacService = mock(RbacService.class);
    dremioConfig = mock(DremioConfig.class);

    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("testuser");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    // Prevent SourceNotFoundException in constructor
    SourceConfig sourceConfig = new SourceConfig();
    sourceConfig.setName("testSource");
    when(namespaceService.getSource(
            new com.dremio.service.namespace.NamespaceKey("testSource")))
        .thenReturn(sourceConfig);

    // Prevent NPE in getSource()
    when(catalogService.getSourceState("testSource")).thenReturn(SourceState.GOOD);

    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
  }

  private SourceResource createResourceRbacDisabled() throws Exception {
    return new SourceResource(
        namespaceService,
        reflectionService,
        sourceService,
        sourceName,
        executor,
        securityContext,
        connectionReader,
        sourceCatalog,
        formatTools,
        allocatorFactory,
        optionManager,
        orphanageFactoryProvider,
        catalogService,
        null,
        null);
  }

  private SourceResource createResourceRbacAdmin() throws Exception {
    when(rbacService.isAdminMember("testuser")).thenReturn(true);
    return new SourceResource(
        namespaceService,
        reflectionService,
        sourceService,
        sourceName,
        executor,
        securityContext,
        connectionReader,
        sourceCatalog,
        formatTools,
        allocatorFactory,
        optionManager,
        orphanageFactoryProvider,
        catalogService,
        rbacService,
        dremioConfig);
  }

  private SourceResource createResourceRbacNonAdmin() throws Exception {
    when(rbacService.isAdminMember("testuser")).thenReturn(false);
    return new SourceResource(
        namespaceService,
        reflectionService,
        sourceService,
        sourceName,
        executor,
        securityContext,
        connectionReader,
        sourceCatalog,
        formatTools,
        allocatorFactory,
        optionManager,
        orphanageFactoryProvider,
        catalogService,
        rbacService,
        dremioConfig);
  }

  // --- FILE-01: getSource browse guard ---

  @Test
  public void testGetSource_rbacDisabled_allowed() throws Exception {
    SourceResource resource = createResourceRbacDisabled();
    // Guard is no-op when rbacService is null — method proceeds to downstream logic
    // Any non-Permission-denied exception is acceptable
    try {
      resource.getSource(true, false, null, null);
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
  }

  @Test
  public void testGetSource_admin_allowed() throws Exception {
    SourceResource resource = createResourceRbacAdmin();
    try {
      resource.getSource(true, false, null, null);
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
    verify(rbacService).isAdminMember("testuser");
  }

  @Test
  public void testGetSource_nonAdmin_blocked() throws Exception {
    SourceResource resource = createResourceRbacNonAdmin();
    assertThatThrownBy(() -> resource.getSource(true, false, null, null))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("browse source files");
  }

  // --- FILE-01: getFolder browse guard ---

  @Test
  public void testGetFolder_rbacDisabled_allowed() throws Exception {
    SourceResource resource = createResourceRbacDisabled();
    try {
      resource.getFolder("test/folder", true, false, null, null);
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
  }

  @Test
  public void testGetFolder_admin_allowed() throws Exception {
    SourceResource resource = createResourceRbacAdmin();
    try {
      resource.getFolder("test/folder", true, false, null, null);
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
    verify(rbacService).isAdminMember("testuser");
  }

  @Test
  public void testGetFolder_nonAdmin_blocked() throws Exception {
    SourceResource resource = createResourceRbacNonAdmin();
    assertThatThrownBy(() -> resource.getFolder("test/folder", true, false, null, null))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("browse source files");
  }

  // --- FILE-01: getFile browse guard ---

  @Test
  public void testGetFile_rbacDisabled_allowed() throws Exception {
    SourceResource resource = createResourceRbacDisabled();
    try {
      resource.getFile("test/file.csv");
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
  }

  @Test
  public void testGetFile_admin_allowed() throws Exception {
    SourceResource resource = createResourceRbacAdmin();
    try {
      resource.getFile("test/file.csv");
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
    verify(rbacService).isAdminMember("testuser");
  }

  @Test
  public void testGetFile_nonAdmin_blocked() throws Exception {
    SourceResource resource = createResourceRbacNonAdmin();
    assertThatThrownBy(() -> resource.getFile("test/file.csv"))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("browse source files");
  }

  // --- FILE-02: saveFormatSettings promote guard ---

  @Test
  public void testSaveFormatSettings_rbacDisabled_allowed() throws Exception {
    SourceResource resource = createResourceRbacDisabled();
    FileFormat fileFormat = mock(FileFormat.class);
    try {
      resource.saveFormatSettings(fileFormat, "test/file.csv");
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
  }

  @Test
  public void testSaveFormatSettings_admin_allowed() throws Exception {
    SourceResource resource = createResourceRbacAdmin();
    FileFormat fileFormat = mock(FileFormat.class);
    try {
      resource.saveFormatSettings(fileFormat, "test/file.csv");
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
    verify(rbacService).isAdminMember("testuser");
  }

  @Test
  public void testSaveFormatSettings_nonAdmin_blocked() throws Exception {
    SourceResource resource = createResourceRbacNonAdmin();
    FileFormat fileFormat = mock(FileFormat.class);
    assertThatThrownBy(() -> resource.saveFormatSettings(fileFormat, "test/file.csv"))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("promote files to datasets");
  }

  // --- FILE-02: saveFolderFormat promote guard ---

  @Test
  public void testSaveFolderFormat_rbacDisabled_allowed() throws Exception {
    SourceResource resource = createResourceRbacDisabled();
    FileFormat fileFormat = mock(FileFormat.class);
    try {
      resource.saveFolderFormat(fileFormat, "test/folder");
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
  }

  @Test
  public void testSaveFolderFormat_admin_allowed() throws Exception {
    SourceResource resource = createResourceRbacAdmin();
    FileFormat fileFormat = mock(FileFormat.class);
    try {
      resource.saveFolderFormat(fileFormat, "test/folder");
    } catch (Exception e) {
      assertNotPermissionDenied(e);
    }
    verify(rbacService).isAdminMember("testuser");
  }

  @Test
  public void testSaveFolderFormat_nonAdmin_blocked() throws Exception {
    SourceResource resource = createResourceRbacNonAdmin();
    FileFormat fileFormat = mock(FileFormat.class);
    assertThatThrownBy(() -> resource.saveFolderFormat(fileFormat, "test/folder"))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("promote files to datasets");
  }

  /**
   * Helper: asserts that an exception is NOT a Permission denied UserException. Other exceptions
   * from incomplete mock wiring are acceptable — we only verify that the RBAC guard did not fire.
   */
  private void assertNotPermissionDenied(Exception e) {
    if (e instanceof UserException) {
      String msg = e.getMessage();
      if (msg != null && msg.contains("Permission denied")) {
        throw new AssertionError("Expected no Permission denied, but got: " + msg, e);
      }
    }
  }
}
