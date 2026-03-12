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
package com.dremio.plugins.jdbc;

import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.plugins.jdbc.conf.BaseJdbcConf;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Optional;
import javax.inject.Provider;

/**
 * Base {@link StoragePlugin} implementation for JDBC-backed data sources.
 *
 * <p>Manages the lifecycle of a {@link JdbcConnectionPool} (HikariCP) and reports source health
 * by executing a configurable validation query against the pool.
 *
 * <p>Concrete JDBC connectors extend this class, supplying a {@link BaseJdbcConf} subclass that
 * provides the JDBC URL and driver class name for their specific database engine.
 */
public class JdbcStoragePlugin implements StoragePlugin, SupportsListingDatasets {

  private final BaseJdbcConf<?, ?> conf;
  private final String name;
  private volatile JdbcConnectionPool pool;

  /**
   * Creates a new plugin instance.
   *
   * @param conf JDBC configuration, including pool settings and validation query
   * @param name the source name as registered in the Dremio catalog
   */
  public JdbcStoragePlugin(BaseJdbcConf<?, ?> conf, String name) {
    this.conf = conf;
    this.name = name;
  }

  /**
   * Initialises the connection pool. Called by the catalog during source activation.
   *
   * @throws IOException if pool initialisation fails
   */
  @Override
  public void start() throws IOException {
    pool = new JdbcConnectionPool(conf);
  }

  /**
   * Shuts down the connection pool. Called by the catalog during source deactivation.
   */
  @Override
  public void close() {
    if (pool != null) {
      pool.close();
      pool = null;
    }
  }

  /**
   * Reports the health of the source by executing the validation query.
   *
   * @return {@link SourceState#GOOD} if the validation query succeeds; a bad state otherwise
   */
  @Override
  public SourceState getState() {
    if (pool == null) {
      return SourceState.badState("Source not started");
    }
    try (Connection c = pool.getConnection();
        Statement s = c.createStatement()) {
      s.execute(conf.validationQuery);
      return SourceState.GOOD;
    } catch (SQLException e) {
      return SourceState.badState("Validation failed: " + e.getMessage());
    }
  }

  /**
   * Returns the connection pool managed by this plugin.
   *
   * <p>Used by {@code JdbcRecordReader} (plan 02) to obtain per-query connections.
   *
   * @return the active {@link JdbcConnectionPool}, or null if {@link #start()} has not been called
   */
  public JdbcConnectionPool getPool() {
    return pool;
  }

  // -------------------------------------------------------------------------
  // StoragePlugin interface — required stubs
  // -------------------------------------------------------------------------

  @Override
  public boolean hasAccessPermission(String user, NamespaceKey key, DatasetConfig datasetConfig) {
    return true;
  }

  @Override
  public SourceCapabilities getSourceCapabilities() {
    return SourceCapabilities.NONE;
  }

  @Override
  public Class<? extends StoragePluginRulesFactory> getRulesFactoryClass() {
    return StoragePluginRulesFactory.NoOpPluginRulesFactory.class;
  }

  // -------------------------------------------------------------------------
  // SupportsListingDatasets / SourceMetadata interface — stub implementations
  // These will be replaced with real JDBC DatabaseMetaData-based discovery in plan 02.
  // -------------------------------------------------------------------------

  @Override
  public DatasetHandleListing listDatasetHandles(GetDatasetOption... options) {
    return () -> Collections.emptyIterator();
  }

  @Override
  public Optional<DatasetHandle> getDatasetHandle(EntityPath datasetPath, GetDatasetOption... options)
      throws ConnectorException {
    return Optional.empty();
  }

  @Override
  public DatasetMetadata getDatasetMetadata(
      DatasetHandle datasetHandle,
      PartitionChunkListing chunkListing,
      GetMetadataOption... options)
      throws ConnectorException {
    throw new ConnectorException("Not implemented in base plugin");
  }

  @Override
  public PartitionChunkListing listPartitionChunks(
      DatasetHandle datasetHandle, ListPartitionChunkOption... options)
      throws ConnectorException {
    throw new ConnectorException("Not implemented in base plugin");
  }

  @Override
  public boolean containerExists(EntityPath containerPath, GetMetadataOption... options) {
    return false;
  }
}
