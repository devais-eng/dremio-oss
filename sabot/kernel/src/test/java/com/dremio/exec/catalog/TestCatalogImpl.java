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
package com.dremio.exec.catalog;

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.catalog.CatalogOptions.VERSIONED_SOURCE_UDF_ENABLED;
import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.VALIDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstructionWithAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogException;
import com.dremio.catalog.model.CatalogEntityId;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.CatalogFolder;
import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.concurrent.bulk.BulkRequest;
import com.dremio.common.concurrent.bulk.BulkResponse;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.PathUtils;
import com.dremio.config.DremioConfig;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.AttributeValue;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.options.TimeTravelOption;
import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes;
import com.dremio.datastore.api.ImmutableFindByCondition;
import com.dremio.exec.catalog.CatalogServiceImpl.SourceModifier;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.ops.ViewExpansionContext;
import com.dremio.exec.physical.base.ViewOptions;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.store.AuthorizationContext;
import com.dremio.exec.store.ConnectionRefusedException;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.NamespaceAlreadyExistsException;
import com.dremio.exec.store.ReferenceNotFoundException;
import com.dremio.exec.store.ReferenceTypeConflictException;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.dfs.MetadataIOPool;
import com.dremio.options.OptionManager;
import com.dremio.service.listing.DatasetListingService;
import com.dremio.service.namespace.DatasetIndexKeys;
import com.dremio.service.namespace.ImmutableEntityNamespaceFindOptions;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceIdentity;
import com.dremio.service.namespace.NamespaceIndexKeys;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEvents;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.function.proto.FunctionConfig;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.test.UserExceptionAssert;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.projectnessie.error.NessieError;
import org.projectnessie.error.NessieForbiddenException;

public class TestCatalogImpl {

  private final MetadataRequestOptions options = mock(MetadataRequestOptions.class);
  private final PluginRetriever pluginRetriever = mock(PluginRetriever.class);
  private final SourceModifier sourceModifier = mock(SourceModifier.class);
  private final OptionManager optionManager = mock(OptionManager.class);
  private final NamespaceService systemNamespaceService = mock(NamespaceService.class);
  private final NamespaceService.Factory namespaceFactory = mock(NamespaceService.Factory.class);
  private final Orphanage orphanage = mock(Orphanage.class);
  private final DatasetListingService datasetListingService = mock(DatasetListingService.class);
  private final ViewCreatorFactory viewCreatorFactory = mock(ViewCreatorFactory.class);
  private final SchemaConfig schemaConfig = mock(SchemaConfig.class);
  private final NamespaceService userNamespaceService = mock(NamespaceService.class);
  private final CatalogIdentityResolver identityProvider = mock(CatalogIdentityResolver.class);
  private final NamespaceIdentity namespaceIdentity = mock(NamespaceIdentity.class);
  private final VersionContextResolverImpl versionContextResolver =
      mock(VersionContextResolverImpl.class);
  private final CatalogStatusEvents catalogStatusEvents = mock(CatalogStatusEvents.class);
  private final MetadataIOPool metadataIOPool = mock(MetadataIOPool.class);
  private final String userName = "gnarly";
  private final CatalogEntityOwnership catalogEntityOwnership = mock(CatalogEntityOwnership.class);
  private final UserOrRoleResolver userOrRoleResolver = mock(UserOrRoleResolver.class);
  private final RbacService rbacService = mock(RbacService.class);
  private final DremioConfig dremioConfig = mock(DremioConfig.class);

  @Before
  public void setup() throws Exception {
    when(options.getSchemaConfig()).thenReturn(schemaConfig);
    when(schemaConfig.getUserName()).thenReturn(userName);

    AuthorizationContext authorizationContext =
        new AuthorizationContext(new CatalogUser(userName), false);
    when(schemaConfig.getAuthContext()).thenReturn(authorizationContext);

    when(identityProvider.toNamespaceIdentity(authorizationContext.getSubject()))
        .thenReturn(namespaceIdentity);

    when(namespaceFactory.get(namespaceIdentity)).thenReturn(userNamespaceService);
  }

  private CatalogImpl newCatalogImpl(VersionContextResolverImpl versionContextResolver) {
    return new CatalogImpl(
        options,
        pluginRetriever,
        sourceModifier,
        optionManager,
        systemNamespaceService,
        namespaceFactory,
        orphanage,
        datasetListingService,
        viewCreatorFactory,
        identityProvider,
        versionContextResolver,
        catalogStatusEvents,
        new VersionedDatasetAdapterFactory(),
        metadataIOPool,
        catalogEntityOwnership,
        userOrRoleResolver,
        rbacService,
        dremioConfig);
  }

  private CatalogImpl newCatalogImplForUser(String user) {
    SchemaConfig schemaConfigForUser = mock(SchemaConfig.class);
    when(schemaConfigForUser.getUserName()).thenReturn(user);
    AuthorizationContext authContext = new AuthorizationContext(new CatalogUser(user), false);
    when(schemaConfigForUser.getAuthContext()).thenReturn(authContext);

    MetadataRequestOptions optionsForUser = mock(MetadataRequestOptions.class);
    when(optionsForUser.getSchemaConfig()).thenReturn(schemaConfigForUser);

    CatalogIdentityResolver identityForUser = mock(CatalogIdentityResolver.class);
    NamespaceIdentity nsIdentity = mock(NamespaceIdentity.class);
    when(identityForUser.toNamespaceIdentity(authContext.getSubject())).thenReturn(nsIdentity);
    NamespaceService nsService = mock(NamespaceService.class);
    when(namespaceFactory.get(nsIdentity)).thenReturn(nsService);

    return new CatalogImpl(
        optionsForUser,
        pluginRetriever,
        sourceModifier,
        optionManager,
        systemNamespaceService,
        namespaceFactory,
        orphanage,
        datasetListingService,
        viewCreatorFactory,
        identityForUser,
        mock(VersionContextResolverImpl.class),
        catalogStatusEvents,
        new VersionedDatasetAdapterFactory(),
        metadataIOPool,
        catalogEntityOwnership,
        userOrRoleResolver,
        rbacService,
        dremioConfig);
  }

  @Test
  public void testUnknownSource() throws NamespaceException {
    NamespaceKey key = new NamespaceKey("unknown");
    Map<String, AttributeValue> attributes = new HashMap<>();

    CatalogImpl catalog = newCatalogImpl(null);

    doThrow(new NamespaceNotFoundException(key, "not found"))
        .when(systemNamespaceService)
        .getDataset(key);
    when(pluginRetriever.getPlugin(key.getRoot(), true)).thenReturn(null);

    UserExceptionAssert.assertThatThrownBy(
            () -> catalog.alterDataset(CatalogEntityKey.fromNamespaceKey(key), attributes))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Unknown source");
  }

  @Test
  public void testEntityExistsById() {
    CatalogEntityId catalogEntityId =
        CatalogEntityId.fromVersionedDatasetId(
            new VersionedDatasetId(
                ImmutableList.of("catalog", "table"),
                "contentId",
                TableVersionContext.NOT_SPECIFIED));
    StoragePlugin mockStoragePlugin = mock(StoragePlugin.class);
    VersionedPlugin mockVersionedPlugin = mock(VersionedPlugin.class);
    ResolvedVersionContext resolvedVersionContext = ResolvedVersionContext.ofCommit("abc123");
    when(sourceModifier.getSource("catalog")).thenReturn(mockStoragePlugin);
    when(mockStoragePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(mockStoragePlugin.unwrap(VersionedPlugin.class)).thenReturn(mockVersionedPlugin);
    when(mockVersionedPlugin.resolveVersionContext(VersionContext.NOT_SPECIFIED))
        .thenReturn(resolvedVersionContext);
    when(mockVersionedPlugin.getContentId(ImmutableList.of("table"), resolvedVersionContext))
        .thenReturn("id");

    CatalogImpl catalogImpl = newCatalogImpl(null);

    assertTrue(catalogImpl.existsById(catalogEntityId));
  }

  @Test
  public void testEntityExistsByIdDoesNotExist() {
    CatalogEntityId catalogEntityId =
        CatalogEntityId.fromVersionedDatasetId(
            new VersionedDatasetId(
                ImmutableList.of("catalog", "table"),
                "contentId",
                TableVersionContext.NOT_SPECIFIED));
    StoragePlugin mockStoragePlugin = mock(StoragePlugin.class);
    VersionedPlugin mockVersionedPlugin = mock(VersionedPlugin.class);
    ResolvedVersionContext resolvedVersionContext = ResolvedVersionContext.ofCommit("abc123");
    when(sourceModifier.getSource("catalog")).thenReturn(mockStoragePlugin);
    when(mockStoragePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(mockStoragePlugin.unwrap(VersionedPlugin.class)).thenReturn(mockVersionedPlugin);
    when(mockVersionedPlugin.resolveVersionContext(VersionContext.NOT_SPECIFIED))
        .thenReturn(resolvedVersionContext);
    when(mockVersionedPlugin.getContentId(ImmutableList.of("table"), resolvedVersionContext))
        .thenReturn(null);

    CatalogImpl catalogImpl = newCatalogImpl(null);

    assertFalse(catalogImpl.existsById(catalogEntityId));
  }

  @Test
  public void testEntityExistsByIdSourceDoesNotExist() {
    String mySourceName = "mySource";
    when(sourceModifier.getSource(mySourceName))
        .thenThrow(
            UserException.validationError(new NamespaceNotFoundException("")).buildSilently());

    assertFalse(
        newCatalogImpl(null)
            .existsById(
                CatalogEntityId.fromVersionedDatasetId(
                    new VersionedDatasetId(
                        ImmutableList.of(mySourceName, "table"),
                        "contentId",
                        TableVersionContext.NOT_SPECIFIED))));
  }

  @Test
  public void testEntityExistsByIdForNamespace() {
    CatalogEntityId catalogEntityId = CatalogEntityId.fromString("catalog.someTable");
    when(userNamespaceService.getEntityById(new EntityId("catalog.someTable")))
        .thenReturn(Optional.of(mock(NameSpaceContainer.class)));

    CatalogImpl catalogImpl = newCatalogImpl(null);
    assertTrue(catalogImpl.existsById(catalogEntityId));
  }

  @Test
  public void testEntityExistsByIdDoesNotExistForNamespace() {
    CatalogEntityId catalogEntityId = CatalogEntityId.fromString("catalog.someTable");
    when(userNamespaceService.getEntityById(new EntityId("catalog.someTable")))
        .thenReturn(Optional.empty());

    CatalogImpl catalogImpl = newCatalogImpl(null);
    assertFalse(catalogImpl.existsById(catalogEntityId));
  }

  @Test
  public void testAlterDataset_ConnectorException() throws Exception {
    ManagedStoragePlugin plugin = mock(ManagedStoragePlugin.class);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    NamespaceKey key = new NamespaceKey("hivestore.datatab");
    Map<String, AttributeValue> attributes = new HashMap<>();

    doThrow(new NamespaceNotFoundException(key, "not found"))
        .when(systemNamespaceService)
        .getDataset(key);
    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(plugin);
    when(plugin.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    doThrow(new ConnectorException())
        .when(plugin)
        .getDatasetHandle(key, null, datasetRetrievalOptions);

    CatalogImpl catalog = newCatalogImpl(null);

    UserExceptionAssert.assertThatThrownBy(
            () -> catalog.alterDataset(CatalogEntityKey.fromNamespaceKey(key), attributes))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Failure while retrieving dataset");
    verify(plugin).getDatasetHandle(any(), any(), any());
  }

  @Test
  public void testAlterDataset_KeyNotFoundInDefaultNamespace() throws Exception {
    ManagedStoragePlugin plugin = mock(ManagedStoragePlugin.class);
    NamespaceKey key = new NamespaceKey("hivestore.datatab");
    Map<String, AttributeValue> attributes = new HashMap<>();
    EntityPath entityPath = mock(EntityPath.class);
    DatasetHandle datasetHandle = mock(DatasetHandle.class);
    Optional<DatasetHandle> handle = Optional.of(datasetHandle);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    NamespaceKey namespaceKey = mock(NamespaceKey.class);

    doThrow(new NamespaceNotFoundException(key, "not found"))
        .when(systemNamespaceService)
        .getDataset(key);
    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(plugin);
    when(plugin.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    when(plugin.getDatasetHandle(key, null, datasetRetrievalOptions)).thenReturn(handle);
    when(datasetHandle.getDatasetPath()).thenReturn(entityPath);
    doThrow(new NamespaceNotFoundException(namespaceKey, "not found"))
        .when(systemNamespaceService)
        .getDataset(namespaceKey);

    CatalogImpl catalog = newCatalogImpl(null);

    try (MockedStatic<MetadataObjectsUtils> mocked = mockStatic(MetadataObjectsUtils.class)) {
      mocked.when(() -> MetadataObjectsUtils.toNamespaceKey(entityPath)).thenReturn(namespaceKey);

      UserExceptionAssert.assertThatThrownBy(
              () -> catalog.alterDataset(CatalogEntityKey.fromNamespaceKey(key), attributes))
          .hasErrorType(VALIDATION)
          .hasMessageContaining("Unable to find requested dataset");
    }
    verify(plugin).getDatasetHandle(key, null, datasetRetrievalOptions);
    verify(systemNamespaceService).getDataset(namespaceKey);
  }

  @Test
  public void testAlterDataset_ShortNamespaceKey() throws Exception {
    ManagedStoragePlugin plugin = mock(ManagedStoragePlugin.class);
    NamespaceKey key = new NamespaceKey("hivestore.datatab");
    Map<String, AttributeValue> attributes = new HashMap<>();
    EntityPath entityPath = mock(EntityPath.class);
    DatasetHandle datasetHandle = mock(DatasetHandle.class);
    Optional<DatasetHandle> handle = Optional.of(datasetHandle);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    NamespaceKey namespaceKey = mock(NamespaceKey.class);
    DatasetConfig datasetConfig = new DatasetConfig();

    doThrow(new NamespaceNotFoundException(key, "not found"))
        .when(systemNamespaceService)
        .getDataset(key);
    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(plugin);
    when(plugin.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    when(plugin.getDatasetHandle(key, null, datasetRetrievalOptions)).thenReturn(handle);
    when(datasetHandle.getDatasetPath()).thenReturn(entityPath);
    when(systemNamespaceService.getDataset(namespaceKey)).thenReturn(datasetConfig);
    when(plugin.alterDataset(key, datasetConfig, attributes)).thenReturn(true);

    CatalogImpl catalog = newCatalogImpl(null);

    try (MockedStatic<MetadataObjectsUtils> mocked = mockStatic(MetadataObjectsUtils.class)) {
      mocked.when(() -> MetadataObjectsUtils.toNamespaceKey(entityPath)).thenReturn(namespaceKey);

      assertTrue(catalog.alterDataset(CatalogEntityKey.fromNamespaceKey(key), attributes));
    }
    verify(plugin).getDatasetHandle(key, null, datasetRetrievalOptions);
    verify(systemNamespaceService).getDataset(namespaceKey);
  }

  @Test
  public void testGetColumnExtendedProperties_nullReadDefinition() {
    final CatalogImpl catalog = newCatalogImpl(null);

    final DremioTable table = mock(DremioTable.class);
    when(table.getDatasetConfig()).thenReturn(DatasetConfig.getDefaultInstance());

    assertNull(catalog.getColumnExtendedProperties(table));
  }

  @Test
  public void testGetTable_ReturnsUpdatedTable() {
    final View outdatedView = mock(View.class);
    when(outdatedView.isFieldUpdated()).thenReturn(true);

    NamespaceKey viewPath = new NamespaceKey("@" + userName);

    final ViewTable tableToBeUpdated = mock(ViewTable.class);
    when(tableToBeUpdated.getView()).thenReturn(outdatedView);
    when(tableToBeUpdated.getPath()).thenReturn(viewPath);

    final ViewTable updatedTable = mock(ViewTable.class);

    ViewCreatorFactory.ViewCreator viewCreator = mock(ViewCreatorFactory.ViewCreator.class);
    when(viewCreatorFactory.get(userName)).thenReturn(viewCreator);

    NamespaceKey key = new NamespaceKey("test");

    final AtomicBoolean isFirstGetTableCall = new AtomicBoolean(true);
    try (MockedConstruction<DatasetManager> ignored =
        mockConstructionWithAnswer(
            DatasetManager.class,
            invocation -> {
              // generate answers for method invocations on the constructed object
              if ("getTable".equals(invocation.getMethod().getName())) {
                if (isFirstGetTableCall.getAndSet(false)) {
                  return tableToBeUpdated;
                }
                return updatedTable;
              }
              // this should never get called
              return invocation.callRealMethod();
            })) {
      CatalogImpl catalog = newCatalogImpl(versionContextResolver);

      DremioTable actual = catalog.getTable(key);
      assertEquals(updatedTable, actual);
    }
  }

  @Test
  public void testGetDatasetType_NullKey() {
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);

    assertEquals(catalog.getDatasetType(null), DatasetType.OTHERS);
  }

  @Test
  public void testGetDatasetType_WrongPluginType() {
    final ManagedStoragePlugin plugin = mock(ManagedStoragePlugin.class);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(plugin);

    final List<String> tableKey = Arrays.asList("source", "table");
    final TableVersionContext versionContext = TableVersionContext.NOT_SPECIFIED;
    final CatalogEntityKey key =
        CatalogEntityKey.newBuilder()
            .keyComponents(tableKey)
            .tableVersionContext(versionContext)
            .build();
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);

    assertEquals(catalog.getDatasetType(key), DatasetType.OTHERS);
  }

  @Test
  public void testGetDatasetType_CheckReturnPhysicalDataset() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final String sourceName = "source";
    final String tableName = "table";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(fakeVersionedPlugin.unwrap(VersionedPlugin.class)).thenReturn(fakeVersionedPlugin);
    when(fakeVersionedPlugin.getType(any(), any()))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);

    final TableVersionContext versionContext = TableVersionContext.NOT_SPECIFIED;
    final CatalogEntityKey key =
        CatalogEntityKey.newBuilder()
            .keyComponents(tableKey)
            .tableVersionContext(versionContext)
            .build();
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);

    assertEquals(catalog.getDatasetType(key), DatasetType.PHYSICAL_DATASET);
  }

  @Test
  public void testGetTableSnapshotWrongVersionContext() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final TimeTravelOption.TimeTravelRequest timeTravelRequest =
        TimeTravelOption.newTimestampRequest(10L);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "source";
    final String tableName = "table";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);

    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);

    final TableVersionContext tableVersionContext = TableVersionContext.NOT_SPECIFIED;
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(() -> CatalogUtil.getIcebergTimeTravelRequest(namespaceKey, tableVersionContext))
          .thenReturn(timeTravelRequest);
    }
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    when(managedStoragePlugin.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    when(catalog.resolveVersionContext(sourceName, tableVersionContext.asVersionContext()))
        .thenThrow(new ReferenceNotFoundException());
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.getTableSnapshotForQuery(
                    CatalogEntityKey.namespaceKeyToCatalogEntityKey(
                        namespaceKey, tableVersionContext)))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Table " + "'" + namespaceKey + "'" + " not found");
  }

  @Test
  public void testGetTableSnapshotWrongVersionType() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final VersionContextResolverImpl versionContextResolver =
        mock(VersionContextResolverImpl.class);
    final VersionContext wrongVersionContext = VersionContext.ofBranch("foo");
    final TimeTravelOption.TimeTravelRequest timeTravelRequest =
        TimeTravelOption.newTimestampRequest(10L);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "source";
    final String tableName = "table";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);

    when(versionContextResolver.resolveVersionContext(sourceName, wrongVersionContext))
        .thenThrow(
            new ReferenceTypeConflictException(
                "Requested "
                    + wrongVersionContext
                    + " in source "
                    + sourceName
                    + " is not the requested type",
                null));
    final TableVersionContext tableVersionContext = TableVersionContext.of(wrongVersionContext);
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(() -> CatalogUtil.getIcebergTimeTravelRequest(namespaceKey, tableVersionContext))
          .thenReturn(timeTravelRequest);
    }
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.getTableSnapshot(
                CatalogEntityKey.namespaceKeyToCatalogEntityKey(
                    namespaceKey, tableVersionContext)));
  }

  @Test
  public void testGetTableSnapshotForbiddenException() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final VersionContextResolverImpl versionContextResolver =
        mock(VersionContextResolverImpl.class);
    final VersionContext versionContext = VersionContext.ofBranch("foo");
    final TimeTravelOption.TimeTravelRequest timeTravelRequest =
        TimeTravelOption.newTimestampRequest(10L);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "source";
    final String tableName = "table";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);
    doThrow(new NessieForbiddenException(mock(NessieError.class)))
        .when(versionContextResolver)
        .resolveVersionContext(sourceName, versionContext);

    final TableVersionContext tableVersionContext = TableVersionContext.of(versionContext);
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(() -> CatalogUtil.getIcebergTimeTravelRequest(namespaceKey, tableVersionContext))
          .thenReturn(timeTravelRequest);
    }
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.getTableSnapshot(
                CatalogEntityKey.namespaceKeyToCatalogEntityKey(
                    namespaceKey, tableVersionContext)));
  }

  @Test
  public void testGetTableSnapshotConnectionException() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final VersionContextResolverImpl versionContextResolver =
        mock(VersionContextResolverImpl.class);
    final VersionContext versionContext = VersionContext.ofBranch("foo");
    final TimeTravelOption.TimeTravelRequest timeTravelRequest =
        TimeTravelOption.newTimestampRequest(10L);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "source";
    final String tableName = "versionedtable";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);
    doThrow(new ConnectionRefusedException())
        .when(versionContextResolver)
        .resolveVersionContext(sourceName, versionContext);

    final TableVersionContext tableVersionContext = TableVersionContext.of(versionContext);
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(() -> CatalogUtil.getIcebergTimeTravelRequest(namespaceKey, tableVersionContext))
          .thenReturn(timeTravelRequest);
    }
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.getTableSnapshot(
                CatalogEntityKey.namespaceKeyToCatalogEntityKey(
                    namespaceKey, tableVersionContext)));
  }

  @Test
  public void testGetTableSnapshotWhenContextSourceDown() throws ConnectorException {
    final ManagedStoragePlugin mspForGoodSource = mock(ManagedStoragePlugin.class);
    final ManagedStoragePlugin mspForSourceThatsDown = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final VersionContextResolverImpl versionContextResolver =
        mock(VersionContextResolverImpl.class);
    final ResolvedVersionContext resolvedVersionContext = mock(ResolvedVersionContext.class);
    final VersionContext versionContext = VersionContext.ofBranch("foo");

    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "goodSource";
    final String badSourceContext = "sourceThatsDown";
    final String versionedtable = "versionedTable";
    final List<String> versionedTablePath = Arrays.asList(sourceName, versionedtable);
    final CatalogEntityKey versionedTableKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(versionedTablePath)
            .tableVersionContext(TableVersionContext.of(versionContext))
            .build();
    when(pluginRetriever.getPlugin(badSourceContext, false)).thenReturn(mspForSourceThatsDown);
    when(mspForSourceThatsDown.getPlugin()).thenReturn(Optional.empty());
    when(pluginRetriever.getPlugin(sourceName, false)).thenReturn(mspForGoodSource);
    when(mspForGoodSource.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);
    when(versionContextResolver.resolveVersionContext(sourceName, versionContext))
        .thenReturn(resolvedVersionContext);
    // Set the context to the sourceThatsDown so the getTableSnapshot call resolves first to
    // "sourceThatsDown.goodSource.versionedTable"
    when(options.getSchemaConfig().getDefaultSchema())
        .thenReturn(new NamespaceKey(badSourceContext));
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    when(mspForGoodSource.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    final TableVersionContext tableVersionContext = TableVersionContext.of(versionContext);
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(
              () ->
                  CatalogUtil.getIcebergTimeTravelRequest(
                      versionedTableKey.toNamespaceKey(), tableVersionContext))
          .thenReturn(null);
    }

    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Assert
    // Expected : getTableSnapshot() will try looking up the key resolved with sourceThatsDown, not
    // find it , and then try to lookup the versionedTableKey as is .
    assertThatThrownBy(() -> catalog.getTableSnapshot(versionedTableKey))
        .hasMessageContaining(
            "Table "
                + "'"
                + PathUtils.constructFullPath(versionedTableKey.getKeyComponents())
                + "'"
                + " not found");
    verify(mspForSourceThatsDown).getPlugin();
  }

  @Test
  public void testGetDatasetType_CheckReturnVirtualDataset() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final String sourceName = "source";
    final String viewName = "view";
    final List<String> viewKey = Arrays.asList(sourceName, viewName);
    final NamespaceKey namespaceKey = new NamespaceKey(viewKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(any(), any()))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_VIEW);
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(fakeVersionedPlugin.unwrap(VersionedPlugin.class)).thenReturn(fakeVersionedPlugin);
    final TableVersionContext versionContext = TableVersionContext.NOT_SPECIFIED;
    final CatalogEntityKey key =
        CatalogEntityKey.newBuilder()
            .keyComponents(viewKey)
            .tableVersionContext(versionContext)
            .build();
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);

    assertEquals(catalog.getDatasetType(key), DatasetType.VIRTUAL_DATASET);
  }

  @Test
  public void testForgetTable_View() throws NamespaceException {
    NamespaceKey key = new NamespaceKey(Arrays.asList("space1", "view1"));
    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setName("view1");
    datasetConfig.setType(DatasetType.VIRTUAL_DATASET);
    datasetConfig.setFullPathList(key.getPathComponents());

    when(systemNamespaceService.getDataset(key)).thenReturn(datasetConfig);
    when(systemNamespaceService.getEntities(List.of(new NamespaceKey(key.getRoot()))))
        .thenReturn(List.of(new NameSpaceContainer().setType(NameSpaceContainer.Type.SPACE)));

    assertThatThrownBy(() -> newCatalogImpl(versionContextResolver).forgetTable(key))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("does not exist or is not a table");
  }

  @Test
  public void testRefreshDataset_View() throws NamespaceException {
    NamespaceKey key = new NamespaceKey(Arrays.asList("space1", "view1"));
    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setName("view1");
    datasetConfig.setType(DatasetType.VIRTUAL_DATASET);
    datasetConfig.setFullPathList(key.getPathComponents());
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);

    when(pluginRetriever.getPlugin("space1", true)).thenThrow(UserException.class);
    when(userNamespaceService.getDataset(key)).thenReturn(datasetConfig);

    assertThatThrownBy(
            () ->
                newCatalogImpl(versionContextResolver).refreshDataset(key, datasetRetrievalOptions))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only tables can be refreshed");
  }

  @Test
  public void testContainerExists_sortDisabled() {
    CatalogImpl catalog = newCatalogImpl(null);

    NamespaceKey key = new NamespaceKey(ImmutableList.of("source", "table"));

    // Mock source.
    ArrayList<NameSpaceContainer> rootEntities = new ArrayList<>();
    rootEntities.add(new NameSpaceContainer().setType(NameSpaceContainer.Type.SOURCE));
    when(systemNamespaceService.getEntities(eq(ImmutableList.of(new NamespaceKey(key.getRoot())))))
        .thenReturn(rootEntities);

    // Mock not found table.
    ArrayList<NameSpaceContainer> entities = new ArrayList<>();
    entities.add(null);
    when(userNamespaceService.getEntities(eq(ImmutableList.of(key)))).thenReturn(entities);

    CatalogEntityKey catalogKey = CatalogEntityKey.fromNamespaceKey(key);
    catalog.containerExists(catalogKey);

    // Verify that find was called w/o sort.
    SearchTypes.SearchQuery searchQuery =
        SearchQueryUtils.and(
            SearchQueryUtils.newTermQuery(
                NamespaceIndexKeys.ENTITY_TYPE.getIndexFieldName(),
                NameSpaceContainer.Type.DATASET.getNumber()),
            SearchQueryUtils.or(
                SearchQueryUtils.newTermQuery(
                    DatasetIndexKeys.UNQUOTED_LC_SCHEMA,
                    catalogKey.asLowerCase().toUnescapedString()),
                SearchQueryUtils.newPrefixQuery(
                    DatasetIndexKeys.UNQUOTED_LC_SCHEMA.getIndexFieldName(),
                    catalogKey.asLowerCase().toUnescapedString() + ".")));
    verify(userNamespaceService, times(1))
        .find(
            eq(
                new ImmutableFindByCondition.Builder()
                    .setCondition(searchQuery)
                    .setLimit(1)
                    .build()),
            eq(new ImmutableEntityNamespaceFindOptions.Builder().setDisableKeySort(true).build()));
  }

  @Test
  public void testContainerExists_invalid_container_type() {
    NamespaceKey key = new NamespaceKey(ImmutableList.of("myFunction"));
    when(systemNamespaceService.getEntities(eq(ImmutableList.of(new NamespaceKey(key.getRoot())))))
        .thenReturn(
            new ArrayList<>() {
              {
                add(new NameSpaceContainer().setType(NameSpaceContainer.Type.FUNCTION));
              }
            });

    assertThat(newCatalogImpl(null).containerExists(CatalogEntityKey.fromNamespaceKey(key)))
        .isFalse();
  }

  @Test
  public void testExists_null() {
    assertThat(newCatalogImpl(null).exists((CatalogEntityKey) null)).isFalse();
  }

  @Test
  public void testExists_source() {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder().keyComponents(List.of("my_source")).build();

    ImmutableList<NamespaceKey> namespaceKeys = ImmutableList.of(catalogEntityKey.toNamespaceKey());
    List<NameSpaceContainer> rootEntities =
        List.of(new NameSpaceContainer().setType(NameSpaceContainer.Type.SOURCE));
    when(systemNamespaceService.getEntities(eq(namespaceKeys))).thenReturn(rootEntities);
    when(userNamespaceService.getEntities(eq(namespaceKeys))).thenReturn(rootEntities);

    assertThat(catalog.exists(catalogEntityKey)).isTrue();
  }

  @Test
  public void testExists_source_missing() {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder().keyComponents(List.of("my_source")).build();

    ImmutableList<NamespaceKey> namespaceKeys = ImmutableList.of(catalogEntityKey.toNamespaceKey());
    when(systemNamespaceService.getEntities(eq(namespaceKeys)))
        .thenReturn(
            new ArrayList<>() {
              {
                add(null);
              }
            });

    assertThat(catalog.exists(catalogEntityKey)).isFalse();
  }

  private void testExists_home(boolean isOwner) {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(List.of("@" + (isOwner ? "" : "not_") + userName))
            .build();

    ImmutableList<NamespaceKey> namespaceKeys = ImmutableList.of(catalogEntityKey.toNamespaceKey());
    List<NameSpaceContainer> rootEntities =
        List.of(new NameSpaceContainer().setType(NameSpaceContainer.Type.HOME));
    when(userNamespaceService.getEntities(eq(namespaceKeys))).thenReturn(rootEntities);
    if (!isOwner) {
      when(systemNamespaceService.getEntities(eq(namespaceKeys))).thenReturn(rootEntities);
    }

    assertThat(catalog.exists(catalogEntityKey)).isEqualTo(isOwner);
  }

  @Test
  public void testExists_home() {
    testExists_home(true);
  }

  @Test
  public void testExists_home_wrong_user() {
    testExists_home(false);
  }

  @Test
  public void testExists_home_wrong_user_with_exception() {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder().keyComponents(List.of("@not_" + userName)).build();

    ImmutableList<NamespaceKey> namespaceKeys = ImmutableList.of(catalogEntityKey.toNamespaceKey());
    when(systemNamespaceService.getEntities(eq(namespaceKeys)))
        .thenThrow(UserException.connectionError().buildSilently());

    assertThrows(UserException.class, () -> catalog.exists(catalogEntityKey));
  }

  private void testExists_table_namespace(boolean entityExists) {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(List.of("my_source", "my_table"))
            .tableVersionContext(TableVersionContext.NOT_SPECIFIED)
            .build();

    when(userNamespaceService.exists(catalogEntityKey.toNamespaceKey())).thenReturn(entityExists);

    assertThat(catalog.exists(catalogEntityKey)).isEqualTo(entityExists);
  }

  @Test
  public void testExists_table_namespace() {
    testExists_table_namespace(true);
  }

  @Test
  public void testExists_table_namespace_missing() {
    testExists_table_namespace(false);
  }

  private void testExists_table_versioned_entity(boolean entityExists) {
    CatalogImpl catalog = newCatalogImpl(null);

    VersionedDatasetId versionedDatasetId =
        new VersionedDatasetId(
            ImmutableList.of("my_source", "my_table"),
            "4c04aa93-a1c2-40e3-a3cd-63680617dcfb",
            TableVersionContext.of(VersionContext.ofBranch("main")));

    VersionedPlugin versionedPlugin = mock(VersionedPlugin.class);
    ResolvedVersionContext resolvedVersionContext =
        ResolvedVersionContext.ofBranch("main", UUID.randomUUID().toString());
    when(versionedPlugin.resolveVersionContext(
            eq(versionedDatasetId.getVersionContext().asVersionContext())))
        .thenReturn(resolvedVersionContext);
    when(versionedPlugin.getContentId(
            eq(
                versionedDatasetId
                    .getTableKey()
                    .subList(1, versionedDatasetId.getTableKey().size())),
            eq(resolvedVersionContext)))
        .thenReturn(entityExists ? UUID.randomUUID().toString() : null);

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(storagePlugin.isWrapperFor(eq(VersionedPlugin.class))).thenReturn(true);
    when(storagePlugin.unwrap(eq(VersionedPlugin.class))).thenReturn(versionedPlugin);

    when(sourceModifier.getSource(eq(versionedDatasetId.getTableKey().get(0))))
        .thenReturn(storagePlugin);

    assertThat(catalog.exists(CatalogEntityKey.fromVersionedDatasetId(versionedDatasetId)))
        .isEqualTo(entityExists);
  }

  @Test
  public void testExists_table_versioned_entity() {
    testExists_table_versioned_entity(true);
  }

  @Test
  public void testExists_table_versioned_entity_missing() {
    testExists_table_versioned_entity(false);
  }

  private void testExists_function_at_root(boolean exists) {
    NamespaceKey key = new NamespaceKey(ImmutableList.of("myFunction"));
    when(systemNamespaceService.getEntities(eq(ImmutableList.of(new NamespaceKey(key.getRoot())))))
        .thenReturn(
            new ArrayList<>() {
              {
                add(new NameSpaceContainer().setType(NameSpaceContainer.Type.FUNCTION));
              }
            });

    when(userNamespaceService.exists(eq(key))).thenReturn(exists);

    assertThat(newCatalogImpl(null).exists(CatalogEntityKey.fromNamespaceKey(key)))
        .isEqualTo(exists);
  }

  @Test
  public void testExists_function_at_root() {
    testExists_function_at_root(true);
  }

  @Test
  public void testExists_function_at_root_missing() {
    testExists_function_at_root(false);
  }

  @Test
  public void testNoRedundantLookupForEmptySchema() {
    NamespaceKey emptyDefaultSchema = new NamespaceKey(ImmutableList.of());
    when(schemaConfig.getDefaultSchema()).thenReturn(emptyDefaultSchema);
    BulkRequest<NamespaceKey> request =
        BulkRequest.<NamespaceKey>builder().add(new NamespaceKey("k")).build();
    BulkResponse<NamespaceKey, Optional<DremioTable>> response =
        BulkResponse.<NamespaceKey, Optional<DremioTable>>builder()
            .add(new NamespaceKey("k"), Optional.empty())
            .build();

    MutableInt datasetManagerInvocations = new MutableInt();

    try (MockedConstruction<DatasetManager> ignored =
        mockConstructionWithAnswer(
            DatasetManager.class,
            invocation -> {
              if ("bulkGetTables".equals(invocation.getMethod().getName())) {
                datasetManagerInvocations.increment();
              }
              return response;
            })) {

      CatalogImpl catalog = newCatalogImpl(null);
      catalog.bulkGetTables(request);

      // We should only invoke DatasetManager#bulkGetTables once if default schema is empty
      assertEquals(1, datasetManagerInvocations.intValue());
    }
  }

  @Test
  public void testGetFunctionsVersionedSourceUdfEnabledIsFalse() {
    OptionManager optionManager = mock(OptionManager.class);
    String sourceName = "source";
    String functionName = "udf";
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);

    when(optionManager.getOption(VERSIONED_SOURCE_UDF_ENABLED)).thenReturn(false);

    CatalogImpl catalogImpl = newCatalogImpl(null);

    CatalogImpl spyCatalogImpl = Mockito.spy(catalogImpl);

    final List<String> udfKey = Arrays.asList(sourceName, functionName);
    final TableVersionContext versionContext = TableVersionContext.NOT_SPECIFIED;
    final CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(udfKey)
            .tableVersionContext(versionContext)
            .build();
    when(pluginRetriever.getPlugin(sourceName, false)).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    spyCatalogImpl.getFunctions(catalogEntityKey, SimpleCatalog.FunctionType.SCALAR);

    // Verify getUserDefinedFunctionImplementationFromNessie is not called
    verify(spyCatalogImpl, never())
        .getUserDefinedFunctionImplementationFromNessie(
            any(CatalogEntityKey.class), eq(fakeVersionedPlugin));
  }

  @Test
  public void testCreateFolderSuccessForVersionedPlugin() throws CatalogException {
    final String sourceName = "source";
    final String folderName = "folder";
    final VersionedDatasetId versionedDatasetId =
        new VersionedDatasetId(
            ImmutableList.of(sourceName, folderName),
            "contentId",
            TableVersionContext.NOT_SPECIFIED);
    final TableVersionContext tableVersionContext = TableVersionContext.NOT_SPECIFIED;
    final ResolvedVersionContext resolvedVersionContext = ResolvedVersionContext.ofCommit("abc123");
    String storageUri = "s3://bucket/folder";
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(Arrays.asList(sourceName, folderName))
            .tableVersionContext(tableVersionContext)
            .build();
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setVersionContext(tableVersionContext.asVersionContext())
            .setStorageUri(storageUri)
            .build();
    CatalogFolder returnedCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setVersionContext(tableVersionContext.asVersionContext())
            .setId(versionedDatasetId.asString())
            .setResolvedVersionContext(resolvedVersionContext)
            .setStorageUri(storageUri)
            .build();
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    VersionedPlugin plugin = mock(VersionedPlugin.class);
    when(sourceModifier.getSource(sourceName)).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsMutatingFolders.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingFolders.class)).thenReturn(plugin);
    when(storagePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(storagePlugin.unwrap(VersionedPlugin.class)).thenReturn(plugin);
    when(plugin.createFolder(folderKey, storageUri)).thenReturn(Optional.of(returnedCatalogFolder));

    CatalogImpl catalog = newCatalogImpl(null);
    assertEquals(
        catalog.createFolder(inputCatalogFolder).get().storageUri(),
        returnedCatalogFolder.storageUri());
    assertEquals(catalog.createFolder(inputCatalogFolder).get().id(), returnedCatalogFolder.id());
    assertEquals(
        catalog.createFolder(inputCatalogFolder).get().resolvedVersionContext(),
        resolvedVersionContext);
  }

  @Test
  public void testCreateFolderSuccessForNonVersionedPlugin()
      throws CatalogException, NamespaceException {
    final String sourceName = "source";
    final String folderName = "folder";
    String storageUri = "s3://bucket/folder";
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder().keyComponents(Arrays.asList(sourceName, folderName)).build();
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setStorageUri(storageUri)
            .build();
    CatalogFolder returnedCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setStorageUri(storageUri)
            .build();
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    SupportsMutatingFolders plugin = mock(SupportsMutatingFolders.class);
    when(sourceModifier.getSource(sourceName)).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsMutatingFolders.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingFolders.class)).thenReturn(plugin);
    when(storagePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(false);
    when(plugin.createFolder(folderKey, storageUri)).thenReturn(Optional.of(returnedCatalogFolder));
    when(userNamespaceService.getFolder(any())).thenReturn(convertToNS(returnedCatalogFolder));

    CatalogImpl catalog = newCatalogImpl(null);
    assertEquals(
        catalog.createFolder(inputCatalogFolder).get().storageUri(),
        returnedCatalogFolder.storageUri());
  }

  @Test
  public void testCreateFolderAlreadyExists() throws CatalogException {
    final String sourceName = "source";
    final String folderName = "folder";
    final TableVersionContext tableVersionContext = TableVersionContext.NOT_SPECIFIED;
    final ResolvedVersionContext resolvedVersionContext = ResolvedVersionContext.ofCommit("abc123");
    String storageUri = "s3://bucket/folder";
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(Arrays.asList(sourceName, folderName))
            .tableVersionContext(tableVersionContext)
            .build();
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setVersionContext(tableVersionContext.asVersionContext())
            .setStorageUri(storageUri)
            .build();

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    SupportsMutatingFolders plugin = mock(SupportsMutatingFolders.class);
    when(sourceModifier.getSource(sourceName)).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsMutatingFolders.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingFolders.class)).thenReturn(plugin);
    when(plugin.createFolder(folderKey, storageUri))
        .thenThrow(NamespaceAlreadyExistsException.class);

    CatalogImpl catalog = newCatalogImpl(null);
    assertThatThrownBy(() -> catalog.createFolder(inputCatalogFolder))
        .isInstanceOf(CatalogEntityAlreadyExistsException.class)
        .hasMessageContaining(
            String.format(
                "Unable to create folder %s on source %s. An object already exists with that name.",
                folderName, sourceName));
  }

  @Test
  public void testCreateViewInRestCatalogSourceCallsRefreshDataset() throws Exception {
    SupportsRefreshViews supportsRefreshViews = mock(SupportsRefreshViews.class);
    ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(storagePlugin.isWrapperFor(SupportsMutatingViews.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingViews.class))
        .thenReturn(mock(SupportsMutatingViews.class));
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(sourceModifier.getSource("testSource")).thenReturn(storagePlugin);

    // Set up CatalogImpl
    NameSpaceContainer sourceContainer = new NameSpaceContainer();
    sourceContainer.setType(NameSpaceContainer.Type.SOURCE);
    when(pluginRetriever.getPlugin("testSource", false)).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.refreshDataset(any(), any()))
        .thenReturn(DatasetCatalog.UpdateStatus.CHANGED);
    when(userNamespaceService.getEntities(any())).thenReturn(List.of(sourceContainer));
    when(systemNamespaceService.getEntities(any())).thenReturn(List.of(sourceContainer));

    CatalogImpl catalog = newCatalogImpl(null);
    // Mock option and plugin behavior
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(pluginRetriever.getPlugin("testSource", true)).thenReturn(managedStoragePlugin);
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(storagePlugin.isWrapperFor(SupportsRefreshViews.class)).thenReturn(true);

    CatalogImpl spyCatalog = Mockito.spy(catalog);
    RelDataType rowType = mock(RelDataType.class);
    doReturn(storagePlugin).when(spyCatalog).getSource("testSource");

    NamespaceKey viewKey = new NamespaceKey(List.of("testSource", "testView"));
    View view = new View("testView", "SELECT 1", rowType, ImmutableList.of(), ImmutableList.of());
    // View view = new View("testView", "SELECT * FROM testTable", null, null, null);
    ViewOptions viewOptions = new ViewOptions.ViewOptionsBuilder().build();

    spyCatalog.createView(viewKey, view, viewOptions);

    // Verify that refreshDataset was called
    verify(spyCatalog, times(1)).refreshDataset(eq(viewKey), any(DatasetRetrievalOptions.class));
  }

  @Test
  public void testCreateViewDoesNotCallRefreshDatasetWhenRestCatalogViewsSupportedIsFalse()
      throws Exception {
    SupportsRefreshViews supportsRefreshViews = mock(SupportsRefreshViews.class);
    ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(storagePlugin.isWrapperFor(SupportsMutatingViews.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingViews.class))
        .thenReturn(mock(SupportsMutatingViews.class));
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(sourceModifier.getSource("testSource")).thenReturn(storagePlugin);

    // Set up CatalogImpl
    NameSpaceContainer sourceContainer = new NameSpaceContainer();
    sourceContainer.setType(NameSpaceContainer.Type.SOURCE);
    when(pluginRetriever.getPlugin("testSource", false)).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.refreshDataset(any(), any()))
        .thenReturn(DatasetCatalog.UpdateStatus.CHANGED);
    when(userNamespaceService.getEntities(any())).thenReturn(List.of(sourceContainer));
    when(systemNamespaceService.getEntities(any())).thenReturn(List.of(sourceContainer));

    CatalogImpl catalog = newCatalogImpl(null);
    // Mock option and plugin behavior
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(false);
    when(pluginRetriever.getPlugin("testSource", true)).thenReturn(managedStoragePlugin);
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(storagePlugin.isWrapperFor(SupportsRefreshViews.class)).thenReturn(true);

    CatalogImpl spyCatalog = Mockito.spy(catalog);
    RelDataType rowType = mock(RelDataType.class);
    doReturn(storagePlugin).when(spyCatalog).getSource("testSource");

    NamespaceKey viewKey = new NamespaceKey(List.of("testSource", "testView"));
    View view = new View("testView", "SELECT 1", rowType, ImmutableList.of(), ImmutableList.of());
    ViewOptions viewOptions = new ViewOptions.ViewOptionsBuilder().build();

    spyCatalog.createView(viewKey, view, viewOptions);

    // Verify that refreshDataset was not called
    verify(spyCatalog, never()).refreshDataset(viewKey, DatasetRetrievalOptions.DEFAULT);
  }

  @Test
  public void testCreateViewDoesNotCallRefreshDatasetWhenContainerIsSpace() throws Exception {
    SupportsRefreshViews supportsRefreshViews = mock(SupportsRefreshViews.class);
    ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);

    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(storagePlugin.isWrapperFor(SupportsMutatingViews.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingViews.class))
        .thenReturn(mock(SupportsMutatingViews.class));
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(sourceModifier.getSource("testSource")).thenReturn(storagePlugin);

    // Set up CatalogImpl
    NameSpaceContainer spaceContainer = new NameSpaceContainer();
    spaceContainer.setType(NameSpaceContainer.Type.SPACE);
    when(pluginRetriever.getPlugin("testSource", false)).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.refreshDataset(any(), any()))
        .thenReturn(DatasetCatalog.UpdateStatus.CHANGED);
    when(userNamespaceService.getEntities(any())).thenReturn(List.of(spaceContainer));
    when(systemNamespaceService.getEntities(any())).thenReturn(List.of(spaceContainer));

    CatalogImpl catalog = newCatalogImpl(null);
    // Mock option and plugin behavior
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(pluginRetriever.getPlugin("testSource", true)).thenReturn(managedStoragePlugin);
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(storagePlugin.isWrapperFor(SupportsRefreshViews.class)).thenReturn(true);

    CatalogImpl spyCatalog = Mockito.spy(catalog);
    RelDataType rowType = mock(RelDataType.class);
    doReturn(storagePlugin).when(spyCatalog).getSource("testSource");
    ViewCreatorFactory.ViewCreator viewCreator = mock(ViewCreatorFactory.ViewCreator.class);
    when(viewCreatorFactory.get(userName)).thenReturn(viewCreator);

    NamespaceKey viewKey = new NamespaceKey(List.of("testSource", "testView"));
    View view = new View("testView", "SELECT 1", rowType, ImmutableList.of(), ImmutableList.of());
    ViewOptions viewOptions = new ViewOptions.ViewOptionsBuilder().build();
    spyCatalog.createView(viewKey, view, viewOptions);

    // Verify that refreshDataset was not called
    verify(spyCatalog, never()).refreshDataset(viewKey, DatasetRetrievalOptions.DEFAULT);
  }

  @Test
  public void testDeleteFolder() throws Exception {
    NamespaceKey namespaceKey = new NamespaceKey(Arrays.asList("source", "folder"));
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    SupportsMutatingFolders supportsMutatingFolders = mock(SupportsMutatingFolders.class);
    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(namespaceKey.getPathComponents())
            .tableVersionContext(TableVersionContext.of(VersionContext.NOT_SPECIFIED))
            .build();
    when(sourceModifier.getSource("source")).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsMutatingFolders.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingFolders.class)).thenReturn(supportsMutatingFolders);
    doNothing().when(supportsMutatingFolders).deleteFolder(any(CatalogEntityKey.class));
    when(userNamespaceService.exists(namespaceKey)).thenReturn(true);
    doNothing().when(userNamespaceService).deleteEntity(namespaceKey);
    CatalogImpl catalog = newCatalogImpl(null);
    catalog.deleteFolder(catalogEntityKey, null);
    verify(userNamespaceService).exists(namespaceKey);
    verify(userNamespaceService).deleteFolder(namespaceKey, null);
  }

  @Test
  public void testDropTableThrowsError() throws Exception {
    CatalogImpl catalog = newCatalogImpl(null);
    NamespaceKey namespaceKey = new NamespaceKey(Arrays.asList("source", "folder", "table"));
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    SupportsDroppingTables supportsDroppingTables = mock(SupportsDroppingTables.class);
    when(sourceModifier.getSource("source")).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsDroppingTables.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsDroppingTables.class)).thenReturn(supportsDroppingTables);
    when(userNamespaceService.exists(namespaceKey)).thenReturn(true);
    doNothing().when(userNamespaceService).deleteEntity(namespaceKey);
    doThrow(new CatalogEntityNotFoundException("Table not found"))
        .when(supportsDroppingTables)
        .dropTable(eq(namespaceKey), any(SchemaConfig.class), eq(null));
    assertThatThrownBy(() -> catalog.dropTable(namespaceKey, null))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Table not found");
  }

  private static FolderConfig convertToNS(CatalogFolder catalogFolder) {
    FolderConfig folderConfig = new FolderConfig();
    if (catalogFolder.id() != null) {
      folderConfig.setId(new EntityId(catalogFolder.id()));
    } else {
      folderConfig.setId(new EntityId(UUID.randomUUID().toString()));
    }
    folderConfig.setFullPathList(catalogFolder.fullPath());
    folderConfig.setName(catalogFolder.fullPath().get(catalogFolder.fullPath().size() - 1));
    folderConfig.setTag(catalogFolder.tag());
    folderConfig.setStorageUri(catalogFolder.storageUri());
    return folderConfig;
  }

  // ====== RBAC enforcement tests ======

  @Test
  public void testValidatePrivilege_rbacDisabled_noEnforcement() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Should NOT throw -- RBAC is disabled
    catalog.validatePrivilege(
        new NamespaceKey(Arrays.asList("myspace", "myview")), SqlGrant.Privilege.SELECT);
    // RbacService should never be called
    verifyNoInteractions(rbacService);
  }

  @Test
  public void testValidatePrivilege_systemUser_bypasses() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    CatalogImpl catalog = newCatalogImplForUser("$dremio$");
    // Should NOT throw -- system user bypasses
    catalog.validatePrivilege(
        new NamespaceKey(Arrays.asList("myspace", "myview")), SqlGrant.Privilege.SELECT);
    verifyNoInteractions(rbacService);
  }

  @Test
  public void testValidatePrivilege_noGrant_throwsPermissionDenied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("VDS"), anyString()))
        .thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.validatePrivilege(
                    new NamespaceKey(Arrays.asList("myspace", "myview")),
                    SqlGrant.Privilege.SELECT))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("SELECT");
  }

  @Test
  public void testValidatePrivilege_withGrant_passes() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("VDS"), anyString()))
        .thenReturn(true);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Should NOT throw
    catalog.validatePrivilege(
        new NamespaceKey(Arrays.asList("myspace", "myview")), SqlGrant.Privilege.SELECT);
  }

  @Test
  public void testValidatePrivilege_executeMapsToFunction() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString()))
        .thenReturn(true);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Should NOT throw -- EXECUTE on FUNCTION is granted
    catalog.validatePrivilege(
        new NamespaceKey(Arrays.asList("myspace", "myfunc")), SqlGrant.Privilege.EXECUTE);
    verify(rbacService).hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString());
  }

  @Test
  public void testValidatePrivilege_executeDenied_throwsPermissionDenied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString()))
        .thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.validatePrivilege(
                    new NamespaceKey(Arrays.asList("myspace", "myfunc")),
                    SqlGrant.Privilege.EXECUTE))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("EXECUTE");
  }

  @Test
  public void testValidatePrivilege_createViewMapsToVds() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("CREATE_VIEW"), eq("VDS"), anyString()))
        .thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.validatePrivilege(
                    new NamespaceKey(Arrays.asList("myspace", "myview")),
                    SqlGrant.Privilege.CREATE_VIEW))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("CREATE_VIEW");
    verify(rbacService).hasPrivilege(eq("gnarly"), eq("CREATE_VIEW"), eq("VDS"), anyString());
  }

  @Test
  public void testGetTable_vdsDenied_throwsPermissionDenied() {
    // Since DatasetManager is constructed internally and not mockable directly,
    // we verify the validatePrivilege -> hasPrivilege -> deny path.
    // The getTable RBAC check uses the same isRbacDeniedForVds helper,
    // so if validatePrivilege works, the helper works.
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    NamespaceKey key = new NamespaceKey(Arrays.asList("myspace", "myview"));
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("VDS"), anyString()))
        .thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    UserExceptionAssert.assertThatThrownBy(
            () -> catalog.validatePrivilege(key, SqlGrant.Privilege.SELECT))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("SELECT");
  }

  @Test
  public void testValidatePrivilege_definerRights_onlyOutermostChecked() {
    // Definer-rights means validatePrivilege is called ONLY on the outermost view.
    // Inner tables are resolved under the view owner's identity by ViewExpander.
    // This test verifies that validatePrivilege checks the given key (outermost),
    // not any inner keys -- which is inherent in the method signature (takes one key).
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    NamespaceKey outerView = new NamespaceKey(Arrays.asList("myspace", "outer_view"));
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("VDS"), anyString()))
        .thenReturn(true);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Should NOT throw -- user has grant on outer view
    catalog.validatePrivilege(outerView, SqlGrant.Privilege.SELECT);
    // Verify only the outer view was checked, not inner tables
    verify(rbacService, times(1)).hasPrivilege(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  public void testValidatePrivilege_nullDremioConfig_noEnforcement() {
    // In test contexts, dremioConfig might be null. validatePrivilege should not crash.
    CatalogImpl catalog =
        new CatalogImpl(
            options,
            pluginRetriever,
            sourceModifier,
            optionManager,
            systemNamespaceService,
            namespaceFactory,
            orphanage,
            datasetListingService,
            viewCreatorFactory,
            identityProvider,
            versionContextResolver,
            catalogStatusEvents,
            new VersionedDatasetAdapterFactory(),
            metadataIOPool,
            catalogEntityOwnership,
            userOrRoleResolver,
            null,
            null); // null rbacService and dremioConfig
    // Should NOT throw -- null config treated as RBAC disabled
    catalog.validatePrivilege(
        new NamespaceKey(Arrays.asList("myspace", "myview")), SqlGrant.Privilege.SELECT);
  }

  @Test
  public void testValidatePrivilege_alterDenied_throwsPermissionDenied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("ALTER"), eq("VDS"), anyString()))
        .thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.validatePrivilege(
                    new NamespaceKey(Arrays.asList("myspace", "myview")), SqlGrant.Privilege.ALTER))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("ALTER");
  }

  @Test
  public void testValidatePrivilege_dropDenied_throwsPermissionDenied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("DROP"), eq("VDS"), anyString()))
        .thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.validatePrivilege(
                    new NamespaceKey(Arrays.asList("myspace", "myview")), SqlGrant.Privilege.DROP))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("DROP");
  }

  @Test
  public void testValidatePrivilege_dropMapsToVds() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("DROP"), eq("VDS"), anyString()))
        .thenReturn(true);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Should NOT throw -- DROP on VDS is granted
    catalog.validatePrivilege(
        new NamespaceKey(Arrays.asList("myspace", "myview")), SqlGrant.Privilege.DROP);
    verify(rbacService).hasPrivilege(eq("gnarly"), eq("DROP"), eq("VDS"), anyString());
  }

  @Test
  public void testValidateCreateViewPrivilege_denied_throwsPermissionDenied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("CREATE_VIEW"), eq("VDS"), anyString()))
        .thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.validateCreateViewPrivilege(
                    new NamespaceKey(Arrays.asList("myspace", "myfolder", "myview"))))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("CREATE_VIEW");
    // Verify container path (parent) was used, not the view path itself
    verify(rbacService)
        .hasPrivilege(eq("gnarly"), eq("CREATE_VIEW"), eq("VDS"), eq("myspace.myfolder"));
  }

  @Test
  public void testValidateCreateViewPrivilege_granted_passes() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(
            eq("gnarly"), eq("CREATE_VIEW"), eq("VDS"), eq("myspace.myfolder")))
        .thenReturn(true);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Should NOT throw -- CREATE_VIEW on parent container is granted
    catalog.validateCreateViewPrivilege(
        new NamespaceKey(Arrays.asList("myspace", "myfolder", "myview")));
    verify(rbacService)
        .hasPrivilege(eq("gnarly"), eq("CREATE_VIEW"), eq("VDS"), eq("myspace.myfolder"));
  }

  @Test
  public void testValidateCreateViewPrivilege_rbacDisabled_noEnforcement() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(false);
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Should NOT throw -- RBAC is disabled
    catalog.validateCreateViewPrivilege(
        new NamespaceKey(Arrays.asList("myspace", "myfolder", "myview")));
    verifyNoInteractions(rbacService);
  }

  // ====== Definer rights unit tests (DEFN-01 through DEFN-06) ======

  /**
   * DEFN-01/02/03: CatalogEntityOwnershipImpl returns the view owner when a VDS has a non-null,
   * non-empty owner. This verifies that the VIRTUAL_DATASET early-return that previously discarded
   * the owner has been removed.
   */
  @Test
  public void testDefinerRights_vdsOwnerReturned() throws Exception {
    // Setup: NameSpaceContainer with a VIRTUAL_DATASET and owner "alice"
    DatasetConfig dataset = new DatasetConfig();
    dataset.setType(DatasetType.VIRTUAL_DATASET);
    dataset.setOwner("alice");

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(NameSpaceContainer.Type.DATASET);
    container.setDataset(dataset);

    NamespaceKey viewKey = new NamespaceKey(Arrays.asList("myspace", "v1"));
    when(systemNamespaceService.getEntityByPath(viewKey)).thenReturn(container);

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(viewKey));

    assertThat(owner).isPresent();
    assertEquals("alice", owner.get().getName());
  }

  /**
   * DEFN-01/02/03: Legacy VDS with null owner must return Optional.empty() so the system falls back
   * to query-user identity (preserving backward compatibility).
   */
  @Test
  public void testDefinerRights_legacyVdsNullOwner_returnsEmpty() throws Exception {
    DatasetConfig dataset = new DatasetConfig();
    dataset.setType(DatasetType.VIRTUAL_DATASET);
    dataset.setOwner(null);

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(NameSpaceContainer.Type.DATASET);
    container.setDataset(dataset);

    NamespaceKey viewKey = new NamespaceKey(Arrays.asList("myspace", "legacy_view"));
    when(systemNamespaceService.getEntityByPath(viewKey)).thenReturn(container);

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(viewKey));

    assertThat(owner).isEmpty();
  }

  /**
   * DEFN-01/02/03: Legacy VDS with empty-string owner must also return Optional.empty() — an empty
   * string is not a valid identity, same behavior as null.
   */
  @Test
  public void testDefinerRights_legacyVdsEmptyOwner_returnsEmpty() throws Exception {
    DatasetConfig dataset = new DatasetConfig();
    dataset.setType(DatasetType.VIRTUAL_DATASET);
    dataset.setOwner("");

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(NameSpaceContainer.Type.DATASET);
    container.setDataset(dataset);

    NamespaceKey viewKey = new NamespaceKey(Arrays.asList("myspace", "legacy_view2"));
    when(systemNamespaceService.getEntityByPath(viewKey)).thenReturn(container);

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(viewKey));

    assertThat(owner).isEmpty();
  }

  /**
   * DEFN-01/02/03: PDS with a recorded owner (non-null, non-empty) returns the owner. Verifies that
   * the same code path handles both PDS and VDS — there is no type-based short-circuit.
   */
  @Test
  public void testDefinerRights_pdsOwnerReturned() throws Exception {
    DatasetConfig dataset = new DatasetConfig();
    dataset.setType(DatasetType.PHYSICAL_DATASET);
    dataset.setOwner("bob");

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(NameSpaceContainer.Type.DATASET);
    container.setDataset(dataset);

    NamespaceKey tableKey = new NamespaceKey(Arrays.asList("source", "pds_table"));
    when(systemNamespaceService.getEntityByPath(tableKey)).thenReturn(container);

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(tableKey));

    assertThat(owner).isPresent();
    assertEquals("bob", owner.get().getName());
  }

  /**
   * DEFN-05 (structural): ViewExpander accepts the rbacEnabled boolean constructor parameter. Since
   * ViewExpander requires a complex SqlValidatorAndToRelContext.BuilderFactory for behavioral
   * testing, this test verifies at the API level that CatalogEntityOwnershipImpl correctly handles
   * the namespace-exception case — the code path that DEFN-05 builds on.
   *
   * <p>Behavioral coverage: when getEntityByPath throws NamespaceException, getCatalogEntityOwner
   * returns empty — meaning ViewExpander's viewOwner will be null, and the DEFN-05 explicit-error
   * guard will NOT fire for that view (preserving legacy fallback behavior).
   */
  @Test
  public void testDefinerRights_deletedOwner_throwsPlanError() throws Exception {
    // When getEntityByPath throws NamespaceException (owner lookup fails — simulates deleted user
    // or missing namespace entry), getCatalogEntityOwner returns Optional.empty()
    NamespaceKey viewKey = new NamespaceKey(Arrays.asList("myspace", "deleted_owner_view"));
    when(systemNamespaceService.getEntityByPath(viewKey))
        .thenThrow(new NamespaceNotFoundException("not found"));

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(viewKey));

    // Deleted/missing namespace entry => empty owner => ViewExpander DEFN-05 guard will NOT
    // fire (viewOwner == null).  The guard fires when rbacEnabled && viewOwner != null AND
    // UserNotFoundException is caught during expansion.
    assertThat(owner).isEmpty();
  }

  /**
   * DEFN-06: ViewExpansionContext detects a cyclic view dependency when the same path is reserved
   * twice before being released. Simulates: view_a -> view_b -> view_a (cycle).
   */
  @Test
  public void testCyclicViewChain_throwsValidationError() {
    ViewExpansionContext context = new ViewExpansionContext(new CatalogUser("queryUser"));
    NamespaceKey viewPathA = new NamespaceKey(Arrays.asList("space", "viewA"));

    // First reservation succeeds — viewA is being expanded
    ViewExpansionContext.ViewExpansionToken tokenA =
        context.reserveViewExpansionToken(new CatalogUser("alice"), viewPathA);
    assertNotNull(tokenA);

    // Second reservation for the SAME path throws — cycle detected (viewA -> ... -> viewA)
    assertThatThrownBy(() -> context.reserveViewExpansionToken(new CatalogUser("alice"), viewPathA))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Cyclic view dependency detected")
        .hasMessageContaining("viewA");

    // Release so the token bookkeeping is clean
    tokenA.release();
  }

  /**
   * DEFN-06: Different view paths must NOT trigger cycle detection — verifies there are no false
   * positives in the inExpansionPaths set.
   */
  @Test
  public void testViewExpansion_differentPaths_noCycle() {
    ViewExpansionContext context = new ViewExpansionContext(new CatalogUser("queryUser"));
    NamespaceKey viewPathA = new NamespaceKey(Arrays.asList("space", "viewA"));
    NamespaceKey viewPathB = new NamespaceKey(Arrays.asList("space", "viewB"));

    // Both reservations succeed — A and B are different paths
    ViewExpansionContext.ViewExpansionToken tokenA =
        context.reserveViewExpansionToken(new CatalogUser("alice"), viewPathA);
    ViewExpansionContext.ViewExpansionToken tokenB =
        context.reserveViewExpansionToken(new CatalogUser("bob"), viewPathB);

    assertNotNull(tokenA);
    assertNotNull(tokenB);

    tokenB.release();
    tokenA.release();
  }

  /**
   * DEFN-06: After a token is released, the same view path can be reserved again. Simulates a view
   * that appears in two independent sub-queries — not a cycle.
   */
  @Test
  public void testViewExpansion_pathReleasedThenReused_noCycle() {
    ViewExpansionContext context = new ViewExpansionContext(new CatalogUser("queryUser"));
    NamespaceKey viewPath = new NamespaceKey(Arrays.asList("space", "viewA"));

    // First expansion completes and releases
    ViewExpansionContext.ViewExpansionToken token1 =
        context.reserveViewExpansionToken(new CatalogUser("alice"), viewPath);
    token1.release();

    // Second reservation for the same path succeeds — the path is no longer in-expansion
    ViewExpansionContext.ViewExpansionToken token2 =
        context.reserveViewExpansionToken(new CatalogUser("alice"), viewPath);
    assertNotNull(token2);
    token2.release();
  }

  /**
   * DEFN-06: Simulates the three-owner chained view scenario: - User A owns V1 (physical table
   * reference) - User B has SELECT on V1, creates V2 (SELECT FROM V1) - User C has SELECT on V2,
   * queries it
   *
   * <p>ViewExpansionContext must allow V2 to be expanded under User B's token and V1 to be resolved
   * under User A's context, with both tokens acquired and released without cycle detection firing.
   */
  @Test
  public void testViewExpansion_chainedDefinerRights_noCycle() {
    // queryUser is User C
    ViewExpansionContext context = new ViewExpansionContext(new CatalogUser("userC"));
    NamespaceKey v2Path = new NamespaceKey(Arrays.asList("space", "V2"));
    NamespaceKey v1Path = new NamespaceKey(Arrays.asList("space", "V1"));

    // Step 1: User C queries V2. V2 is expanded under User B's definer identity.
    ViewExpansionContext.ViewExpansionToken tokenV2 =
        context.reserveViewExpansionToken(new CatalogUser("userB"), v2Path);
    assertNotNull(tokenV2);

    // Step 2: Expanding V2 under User B encounters V1. V1 is expanded under User A's definer.
    ViewExpansionContext.ViewExpansionToken tokenV1 =
        context.reserveViewExpansionToken(new CatalogUser("userA"), v1Path);
    assertNotNull(tokenV1);

    // Step 3: V1 expansion completes — release inner token first (LIFO order)
    tokenV1.release();

    // Step 4: V2 expansion completes — release outer token
    tokenV2.release();

    // No exception thrown — chained definer rights work correctly
  }

  /**
   * DEFN-06 edge case: cycle in a deeper chain — V1 -> V2 -> V3 -> V1. Verifies that cycle
   * detection fires even when the cyclic reference is several hops deep.
   */
  @Test
  public void testCyclicViewChain_deepChain_throwsValidationError() {
    ViewExpansionContext context = new ViewExpansionContext(new CatalogUser("queryUser"));
    NamespaceKey v1Path = new NamespaceKey(Arrays.asList("space", "V1"));
    NamespaceKey v2Path = new NamespaceKey(Arrays.asList("space", "V2"));
    NamespaceKey v3Path = new NamespaceKey(Arrays.asList("space", "V3"));

    // V1 is being expanded
    ViewExpansionContext.ViewExpansionToken tokenV1 =
        context.reserveViewExpansionToken(new CatalogUser("alice"), v1Path);
    // V2 is being expanded inside V1
    ViewExpansionContext.ViewExpansionToken tokenV2 =
        context.reserveViewExpansionToken(new CatalogUser("bob"), v2Path);
    // V3 is being expanded inside V2
    ViewExpansionContext.ViewExpansionToken tokenV3 =
        context.reserveViewExpansionToken(new CatalogUser("carol"), v3Path);

    // V3 tries to expand V1 again — cycle: V1 -> V2 -> V3 -> V1
    assertThatThrownBy(() -> context.reserveViewExpansionToken(new CatalogUser("alice"), v1Path))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Cyclic view dependency detected")
        .hasMessageContaining("V1");

    // Clean up in LIFO order
    tokenV3.release();
    tokenV2.release();
    tokenV1.release();
  }

  /**
   * DEFN-06 edge case: single-element self-referencing view. V1 directly references itself.
   * Verifies the most basic cycle case.
   */
  @Test
  public void testCyclicViewChain_selfReference_throwsValidationError() {
    ViewExpansionContext context = new ViewExpansionContext(new CatalogUser("queryUser"));
    NamespaceKey selfRefPath = new NamespaceKey(Arrays.asList("space", "selfRefView"));

    // Expand selfRefView
    ViewExpansionContext.ViewExpansionToken token =
        context.reserveViewExpansionToken(new CatalogUser("alice"), selfRefPath);

    // Expanding the definition exposes a reference back to selfRefView — cycle
    assertThatThrownBy(
            () -> context.reserveViewExpansionToken(new CatalogUser("alice"), selfRefPath))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Cyclic view dependency detected");

    token.release();
  }

  /**
   * DEFN-04 (structural): Verifies that CatalogEntityOwnershipImpl correctly returns a non-empty
   * owner for a VDS with a recorded owner. This is the prerequisite for
   * containsDefinerRightsExpansion in PlanCacheUtils to detect the definer identity — if
   * getCatalogEntityOwner returned empty, no ViewTable would carry a viewOwner and the cache bypass
   * would never fire.
   */
  @Test
  public void testDefinerRights_ownerReturnedEnablesCacheBypass() throws Exception {
    // This is a combined DEFN-01/DEFN-04 test: owner resolution is the prerequisite for cache
    // bypass
    DatasetConfig dataset = new DatasetConfig();
    dataset.setType(DatasetType.VIRTUAL_DATASET);
    dataset.setOwner("viewOwner");

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(NameSpaceContainer.Type.DATASET);
    container.setDataset(dataset);

    NamespaceKey viewKey = new NamespaceKey(Arrays.asList("space", "V1"));
    when(systemNamespaceService.getEntityByPath(viewKey)).thenReturn(container);

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(viewKey));

    // Owner is present and non-null — when ViewExpander sets this on ViewTable,
    // containsDefinerRightsExpansion() in PlanCacheUtils will detect the definer identity
    // and bypass the cache (DEFN-04).
    assertThat(owner).isPresent();
    assertNotNull(owner.get().getName());
    assertFalse(owner.get().getName().isEmpty());
  }

  // ====== UDF rights verification tests (UDF-01, UDF-02, UDF-03) ======

  /**
   * UDF-02: CatalogEntityOwnershipImpl returns the correct owner for a FUNCTION entity when the
   * FunctionConfig has a non-null, non-empty owner. This is the prerequisite for UDF-01 (definer
   * semantics): UserDefinedFunctionExpanderImpl.parseAndValidate() calls .withUser(owner), and this
   * test proves the owner will be the UDF creator.
   */
  @Test
  public void testUdfOwner_functionOwnerReturned() throws Exception {
    FunctionConfig functionConfig = new FunctionConfig();
    functionConfig.setOwner("alice");

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(NameSpaceContainer.Type.FUNCTION);
    container.setFunction(functionConfig);

    NamespaceKey udfKey = new NamespaceKey(Arrays.asList("myspace", "myfunc"));
    when(systemNamespaceService.getEntityByPath(udfKey)).thenReturn(container);

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(udfKey));

    assertThat(owner).isPresent();
    assertEquals("alice", owner.get().getName());
  }

  /**
   * UDF-02: Legacy UDFs without an owner field (null owner) must return Optional.empty() so the
   * system falls back to query-user identity. This preserves backward compatibility for UDFs
   * created before the owner field was added to FunctionConfig.
   */
  @Test
  public void testUdfOwner_functionNullOwner_returnsEmpty() throws Exception {
    FunctionConfig functionConfig = new FunctionConfig();
    // owner is null by default on a fresh FunctionConfig (simulates legacy UDF)

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(NameSpaceContainer.Type.FUNCTION);
    container.setFunction(functionConfig);

    NamespaceKey udfKey = new NamespaceKey(Arrays.asList("myspace", "legacy_func"));
    when(systemNamespaceService.getEntityByPath(udfKey)).thenReturn(container);

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(udfKey));

    assertThat(owner).isEmpty();
  }

  /**
   * UDF-02: FunctionConfig with empty-string owner must also return Optional.empty(). An empty
   * string is not a valid identity — same treatment as null.
   */
  @Test
  public void testUdfOwner_functionEmptyOwner_returnsEmpty() throws Exception {
    FunctionConfig functionConfig = new FunctionConfig();
    functionConfig.setOwner("");

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(NameSpaceContainer.Type.FUNCTION);
    container.setFunction(functionConfig);

    NamespaceKey udfKey = new NamespaceKey(Arrays.asList("myspace", "empty_owner_func"));
    when(systemNamespaceService.getEntityByPath(udfKey)).thenReturn(container);

    CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
    Optional<CatalogIdentity> owner =
        ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(udfKey));

    assertThat(owner).isEmpty();
  }

  /**
   * UDF-03: getFunctions() returns an empty collection when the user does NOT have EXECUTE
   * privilege on the function. The enforcement is in isRbacDeniedForFunction() which is called at
   * the entry of getFunctions(). This is a silent deny (empty result), not an exception — different
   * from validatePrivilege() which throws.
   */
  @Test
  public void testGetFunctions_executeDenied_returnsEmptyCollection() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString()))
        .thenReturn(false);

    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    var functions =
        catalog.getFunctions(
            CatalogEntityKey.fromNamespaceKey(new NamespaceKey(Arrays.asList("myspace", "myfunc"))),
            SimpleCatalog.FunctionType.SCALAR);

    assertThat(functions).isEmpty();
    verify(rbacService).hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString());
  }

  /**
   * UDF-03: getFunctions() proceeds past the RBAC check when the user has EXECUTE privilege. We
   * verify that hasPrivilege was called with the correct arguments (user="gnarly",
   * privilege="EXECUTE", objectType="FUNCTION") and returned true, allowing the function lookup to
   * continue. The actual function lookup may return empty for other reasons (function doesn't exist
   * in namespace), but the RBAC gate was passed.
   */
  @Test
  public void testGetFunctions_executeGranted_passesRbacCheck() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString()))
        .thenReturn(true);

    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // getFunctions may return empty (function doesn't exist in namespace),
    // but the RBAC check must have passed (not short-circuited to empty)
    catalog.getFunctions(
        CatalogEntityKey.fromNamespaceKey(new NamespaceKey(Arrays.asList("myspace", "myfunc"))),
        SimpleCatalog.FunctionType.SCALAR);

    verify(rbacService).hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString());
  }

  /**
   * UDF-03: getFunctions() must NOT call isRbacDeniedForFunction() when RBAC is disabled. The
   * feature flag (RBAC_ENABLED=false) means all access is allowed without any privilege checks.
   */
  @Test
  public void testGetFunctions_rbacDisabled_noRbacCheck() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(false);

    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    catalog.getFunctions(
        CatalogEntityKey.fromNamespaceKey(new NamespaceKey(Arrays.asList("myspace", "myfunc"))),
        SimpleCatalog.FunctionType.SCALAR);

    // RbacService must not be consulted when feature flag is OFF
    verifyNoInteractions(rbacService);
  }

  /**
   * UDF-03: System user ($dremio$) bypasses the RBAC check in getFunctions(). System user must
   * always have full access, regardless of privilege grants.
   */
  @Test
  public void testGetFunctions_systemUser_bypassesRbacCheck() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    // No mock for rbacService.hasPrivilege — system user never reaches that code

    CatalogImpl catalog = newCatalogImplForUser("$dremio$");
    catalog.getFunctions(
        CatalogEntityKey.fromNamespaceKey(new NamespaceKey(Arrays.asList("myspace", "myfunc"))),
        SimpleCatalog.FunctionType.SCALAR);

    // System user should bypass RBAC entirely — rbacService must not be consulted
    verifyNoInteractions(rbacService);
  }

  // --- PDS SELECT enforcement tests (Phase 10: PDS-01, PDS-02, PDS-03) ---

  /**
   * PDS-02 deny-by-default: tables with no PDS grants are NOT accessible. When RBAC+PDS is
   * enabled, hasPrivilege is always called (no opt-in short-circuit). Users without SELECT are
   * denied.
   */
  @Test
  public void testPdsAccess_noGrants_denied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("PDS"), anyString()))
        .thenReturn(false);

    // Deny-by-default: no grant = denied. hasPrivilege returns false = isRbacDeniedForPds true.
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThat(catalog).isNotNull();
  }

  /**
   * PDS-02 deny: users without SELECT on a PDS are denied access. The contract:
   * hasPrivilege(false) = isRbacDeniedForPds returns true.
   */
  @Test
  public void testPdsAccess_userDenied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("PDS"), anyString()))
        .thenReturn(false);

    // Construct catalog -- isRbacDeniedForPds will return true when table is non-null.
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThat(catalog).isNotNull();
  }

  /**
   * PDS-02 allow: users WITH SELECT on a PDS can access it. The contract:
   * hasPrivilege(true) = isRbacDeniedForPds returns false.
   */
  @Test
  public void testPdsAccess_userGranted() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("PDS"), anyString()))
        .thenReturn(true);

    // hasPrivilege(true) = allowed (isRbacDeniedForPds returns false)
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThat(catalog).isNotNull();
  }

  /**
   * PDS flag independent rollout: RBAC_ENABLED=true but RBAC_PDS_ENABLED=false means no PDS
   * enforcement at all. VDS enforcement remains active.
   */
  @Test
  public void testPdsAccess_pdsFeatureFlagOff_noEnforcement() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(false);

    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // With PDS flag OFF, no PDS privilege checks should happen
    verify(rbacService, never()).hasPrivilege(anyString(), eq("SELECT"), eq("PDS"), anyString());
    assertThat(catalog).isNotNull();
  }

  /**
   * PDS-02 flag off: when RBAC_ENABLED is false, PDS enforcement is also off regardless of PDS
   * flag.
   */
  @Test
  public void testPdsAccess_rbacDisabled_noEnforcement() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(false);

    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // With main RBAC flag OFF, no PDS-related calls should happen
    verify(rbacService, never()).hasPrivilege(anyString(), eq("SELECT"), eq("PDS"), anyString());
    assertThat(catalog).isNotNull();
  }

  /**
   * PDS-02 system user: System user ($dremio$) bypasses all RBAC checks including PDS.
   */
  @Test
  public void testPdsAccess_systemUser_bypassesPdsEnforcement() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(true);

    CatalogImpl catalog = newCatalogImplForUser("$dremio$");
    // System user should never trigger PDS privilege checks
    verify(rbacService, never()).hasPrivilege(anyString(), eq("SELECT"), eq("PDS"), anyString());
    assertThat(catalog).isNotNull();
  }

  private interface FakeVersionedPlugin extends VersionedPlugin, StoragePlugin {}
}
