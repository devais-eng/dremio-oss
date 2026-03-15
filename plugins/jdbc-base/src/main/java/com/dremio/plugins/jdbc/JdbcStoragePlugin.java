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
import com.dremio.connector.metadata.BasicDatasetHandle;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetSplit;
import com.dremio.connector.metadata.DatasetStats;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunk;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.plugins.jdbc.conf.BaseJdbcConf;
import com.dremio.plugins.jdbc.conf.ProtocolMode;
import com.dremio.plugins.jdbc.exec.JdbcSubScan;
import com.dremio.plugins.jdbc.planning.JdbcRulesFactory;
import com.dremio.plugins.jdbc.planning.SqlBuilder;
import com.dremio.plugins.jdbc.pool.AdbcConnectionFactory;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.plugins.jdbc.reader.AdbcRecordReader;
import com.dremio.plugins.jdbc.reader.JdbcRecordReader;
import com.dremio.plugins.jdbc.schema.AdbcSchemaFetcher;
import com.dremio.plugins.jdbc.schema.JdbcSchemaFetcher;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.arrow.adbc.core.AdbcException;
import org.apache.arrow.memory.RootAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base {@link StoragePlugin} implementation for JDBC-backed data sources.
 *
 * <p>Manages the lifecycle of a {@link JdbcConnectionPool} (HikariCP) and reports source health by
 * executing a configurable validation query against the pool.
 *
 * <p>Schema discovery is delegated to {@link JdbcSchemaFetcher} which reads {@link
 * java.sql.DatabaseMetaData} to enumerate schemas, tables, and column types and maps them to Arrow
 * types via the Apache Arrow JDBC adapter.
 *
 * <p>Concrete JDBC connectors extend this class, supplying a {@link BaseJdbcConf} subclass that
 * provides the JDBC URL and driver class name for their specific database engine.
 */
public class JdbcStoragePlugin implements StoragePlugin, SupportsListingDatasets {

  private static final Logger logger = LoggerFactory.getLogger(JdbcStoragePlugin.class);

  /** Default scan factor used when no statistics are available. */
  private static final double DEFAULT_SCAN_FACTOR = 1.0d;

  /** Approximate row count used as an estimate when statistics are not available. */
  private static final long UNKNOWN_ROW_COUNT = -1L;

  private final BaseJdbcConf<?, ?> conf;
  private final String name;
  private volatile JdbcConnectionPool pool;
  private volatile JdbcSchemaFetcher schemaFetcher;

  /** Dedicated allocator for ADBC native Arrow buffers. */
  private RootAllocator adbcAllocator;

  /** ADBC connection factory; null when running in JDBC mode. */
  private volatile AdbcConnectionFactory adbcFactory;

  /** ADBC-based schema fetcher; null when running in JDBC mode. */
  private volatile AdbcSchemaFetcher adbcSchemaFetcher;

  /** The resolved protocol mode after probing ADBC availability at start(). */
  private volatile ProtocolMode effectiveProtocolMode = ProtocolMode.JDBC;

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
   * Creates the schema fetcher for this plugin. Subclasses can override to return a
   * database-specific schema fetcher.
   *
   * @param pool the connection pool to pass to the schema fetcher
   * @return a new {@link JdbcSchemaFetcher} (or subclass) for this source
   */
  protected JdbcSchemaFetcher createSchemaFetcher(JdbcConnectionPool pool) {
    return new JdbcSchemaFetcher(pool);
  }

  /**
   * Creates the record reader for this plugin. Subclasses can override to return a
   * database-specific record reader (e.g., PostgresRecordReader with autoCommit=false).
   *
   * <p>This method is public so that {@link com.dremio.plugins.jdbc.exec.JdbcScanCreator} (in a
   * sibling package) can call it on the resolved plugin instance.
   *
   * @param ctx the operator context providing batch sizing and allocator
   * @param config the sub-scan carrying the SQL query and schema
   * @param pool the connection pool to acquire execution connections from
   * @return a new {@link JdbcRecordReader} (or subclass) for this source
   */
  public JdbcRecordReader createRecordReader(
      OperatorContext ctx, JdbcSubScan config, JdbcConnectionPool pool) {
    return new JdbcRecordReader(ctx, config, pool, conf.getQueryTimeoutSec());
  }

  /**
   * Creates the SQL builder for this plugin. Subclasses can override to return a database-specific
   * SQL builder (e.g., OracleSqlBuilder using FETCH FIRST instead of LIMIT).
   *
   * <p>This method is public so that {@link com.dremio.plugins.jdbc.planning.JdbcScanPrel} can call
   * it on the resolved plugin instance to obtain the correct SQL dialect.
   *
   * @return a new {@link SqlBuilder} (or subclass) for this source
   */
  public SqlBuilder createSqlBuilder() {
    return new SqlBuilder();
  }

  /**
   * Returns the resolved effective protocol mode for this source.
   *
   * @return the effective {@link ProtocolMode} after ADBC availability probing
   */
  public ProtocolMode getEffectiveProtocolMode() {
    return effectiveProtocolMode;
  }

  /**
   * Returns the ADBC connection factory, or null if running in JDBC mode.
   *
   * @return the {@link AdbcConnectionFactory}, or null
   */
  public AdbcConnectionFactory getAdbcFactory() {
    return adbcFactory;
  }

  /**
   * Creates the ADBC schema fetcher for this plugin. Subclasses can override to return a
   * database-specific ADBC schema fetcher.
   *
   * @param factory the ADBC connection factory to pass to the schema fetcher
   * @return a new {@link AdbcSchemaFetcher} (or subclass) for this source
   */
  protected AdbcSchemaFetcher createAdbcSchemaFetcher(AdbcConnectionFactory factory) {
    return new AdbcSchemaFetcher(factory);
  }

  /**
   * Creates the ADBC record reader for this plugin. Subclasses can override to return a
   * database-specific ADBC record reader.
   *
   * <p>This method is public so that {@link com.dremio.plugins.jdbc.exec.JdbcScanCreator} (in a
   * sibling package) can call it on the resolved plugin instance.
   *
   * @param ctx the operator context providing batch sizing and allocator
   * @param config the sub-scan carrying the SQL query and schema
   * @param factory the ADBC connection factory to acquire execution connections from
   * @return a new {@link AdbcRecordReader} (or subclass) for this source
   */
  public AdbcRecordReader createAdbcRecordReader(
      OperatorContext ctx, JdbcSubScan config, AdbcConnectionFactory factory) {
    return new AdbcRecordReader(ctx, config, factory);
  }

  /**
   * Initialises the connection pool and schema fetcher. Called by the catalog during source
   * activation.
   *
   * <p>When the configured protocol mode is AUTO or ADBC and the database supports ADBC (non-null
   * {@code adbcUri()}), attempts to initialize the ADBC backend. In AUTO mode, falls back to JDBC
   * gracefully if the native driver is unavailable. In ADBC mode, throws if initialization fails.
   *
   * @throws IOException if pool initialisation fails or ADBC mode is requested but unavailable
   */
  @Override
  public void start() throws IOException {
    pool = new JdbcConnectionPool(conf);
    schemaFetcher = createSchemaFetcher(pool);

    ProtocolMode configured = conf.getProtocolMode();
    String adbcUri = conf.adbcUri();
    if ((configured == ProtocolMode.ADBC || configured == ProtocolMode.AUTO) && adbcUri != null) {
      try {
        adbcAllocator = new RootAllocator();
        adbcFactory = new AdbcConnectionFactory(adbcUri, adbcAllocator, conf.poolSize);
        adbcFactory.open();
        // Schema discovery always uses JDBC (ADBC JNI 0.22.0 does not implement getObjects).
        // ADBC is used only for the query execution path (AdbcRecordReader).
        effectiveProtocolMode = ProtocolMode.ADBC;
        logger.info("ADBC backend initialized for source '{}'", name);
      } catch (Throwable e) {
        // Catch Throwable (not just Exception) because NoClassDefFoundError and
        // UnsatisfiedLinkError are Errors that occur when the native ADBC JNI
        // library is missing or incompatible with the system's libstdc++.
        if (configured == ProtocolMode.ADBC) {
          throw new IOException(
              "ADBC mode requested but native driver unavailable: " + e.getMessage(), e);
        }
        // AUTO: fall back to JDBC
        logger.info(
            "ADBC not available for source '{}', falling back to JDBC: {}", name, e.getMessage());
        effectiveProtocolMode = ProtocolMode.JDBC;
        closeAdbcResources();
      }
    } else {
      effectiveProtocolMode = ProtocolMode.JDBC;
    }
  }

  /**
   * Shuts down ADBC resources and the connection pool. Called by the catalog during source
   * deactivation.
   */
  @Override
  public void close() {
    closeAdbcResources();
    if (pool != null) {
      pool.close();
      pool = null;
    }
    schemaFetcher = null;
  }

  /** Cleans up ADBC resources (factory, schema fetcher, allocator) in safe order. */
  private void closeAdbcResources() {
    if (adbcFactory != null) {
      try {
        adbcFactory.close();
      } catch (Exception e) {
        logger.warn("Error closing ADBC factory", e);
      }
      adbcFactory = null;
    }
    adbcSchemaFetcher = null;
    if (adbcAllocator != null) {
      try {
        adbcAllocator.close();
      } catch (Exception e) {
        logger.warn("Error closing ADBC allocator", e);
      }
      adbcAllocator = null;
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
   * <p>Used by {@code JdbcRecordReader} to obtain per-query connections.
   *
   * @return the active {@link JdbcConnectionPool}, or null if {@link #start()} has not been called
   */
  public JdbcConnectionPool getPool() {
    return pool;
  }

  /**
   * Returns the schema fetcher managed by this plugin.
   *
   * @return the active {@link JdbcSchemaFetcher}, or null if {@link #start()} has not been called
   */
  public JdbcSchemaFetcher getSchemaFetcher() {
    return schemaFetcher;
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
    return JdbcRulesFactory.class;
  }

  // -------------------------------------------------------------------------
  // SupportsListingDatasets / SourceMetadata interface
  // -------------------------------------------------------------------------

  /**
   * Lists all tables in the remote source as {@link DatasetHandle} objects.
   *
   * <p>Each handle carries an {@link EntityPath} with two components: schema name and table name.
   * Tables from system schemas (e.g. {@code information_schema}) are excluded by the schema
   * fetcher.
   */
  @Override
  public DatasetHandleListing listDatasetHandles(GetDatasetOption... options)
      throws ConnectorException {
    // Schema discovery always uses JDBC (ADBC JNI 0.22.0 does not implement getObjects).
    if (schemaFetcher == null) {
      return () -> Collections.emptyIterator();
    }

    try {
      List<DatasetHandle> handles = new ArrayList<>();
      List<String> schemas = schemaFetcher.listSchemas();
      for (String schema : schemas) {
        List<String> tables = schemaFetcher.listTables(schema);
        for (String table : tables) {
          EntityPath path = new EntityPath(List.of(name, schema, table));
          handles.add(new BasicDatasetHandle(path));
        }
      }
      Iterator<DatasetHandle> it = handles.iterator();
      return () -> it;
    } catch (SQLException e) {
      throw new ConnectorException(
          "Failed to list datasets from JDBC source: " + e.getMessage(), e);
    }
  }

  private DatasetHandleListing listDatasetHandlesAdbc() throws ConnectorException {
    try {
      List<DatasetHandle> handles = new ArrayList<>();
      List<String> schemas = adbcSchemaFetcher.listSchemas();
      for (String schema : schemas) {
        List<String> tables = adbcSchemaFetcher.listTables(schema);
        for (String table : tables) {
          EntityPath path = new EntityPath(List.of(name, schema, table));
          handles.add(new BasicDatasetHandle(path));
        }
      }
      Iterator<DatasetHandle> it = handles.iterator();
      return () -> it;
    } catch (AdbcException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ConnectorException(
          "Failed to list datasets from ADBC source: " + e.getMessage(), e);
    }
  }

  /**
   * Returns a handle for the dataset at the given path, or {@link Optional#empty()} if the table
   * does not exist.
   */
  @Override
  public Optional<DatasetHandle> getDatasetHandle(
      EntityPath datasetPath, GetDatasetOption... options) throws ConnectorException {
    List<String> components = datasetPath.getComponents();
    if (components.size() < 2) {
      return Optional.empty();
    }
    String schema = components.get(components.size() - 2);
    String table = components.get(components.size() - 1);

    // Schema discovery always uses JDBC (ADBC JNI 0.22.0 does not implement getObjects).
    if (schemaFetcher == null) {
      return Optional.empty();
    }
    try {
      if (schemaFetcher.tableExists(schema, table)) {
        return Optional.of(new BasicDatasetHandle(datasetPath));
      }
      return Optional.empty();
    } catch (SQLException e) {
      throw new ConnectorException(
          "Failed to check table existence for " + datasetPath + ": " + e.getMessage(), e);
    }
  }

  /**
   * Returns the Arrow schema for the dataset identified by the handle.
   *
   * <p>The schema is discovered via {@link JdbcSchemaFetcher#getTableSchema}.
   */
  @Override
  public DatasetMetadata getDatasetMetadata(
      DatasetHandle datasetHandle, PartitionChunkListing chunkListing, GetMetadataOption... options)
      throws ConnectorException {
    EntityPath path = datasetHandle.getDatasetPath();
    List<String> components = path.getComponents();
    if (components.size() < 2) {
      throw new ConnectorException("Invalid dataset path: " + path);
    }
    String schema = components.get(components.size() - 2);
    String table = components.get(components.size() - 1);

    // Schema discovery always uses JDBC (ADBC JNI 0.22.0 does not implement
    // getObjects/getTableSchema).
    try {
      BatchSchema batchSchema = schemaFetcher.getTableSchema(schema, table);
      DatasetStats stats = DatasetStats.of(UNKNOWN_ROW_COUNT, false, DEFAULT_SCAN_FACTOR);
      return DatasetMetadata.of(stats, batchSchema);
    } catch (SQLException e) {
      throw new ConnectorException("Failed to read schema for " + path + ": " + e.getMessage(), e);
    }
  }

  /**
   * Returns a single-partition listing for the given dataset handle.
   *
   * <p>JDBC tables are not partitioned; a single {@link PartitionChunk} covering the full table is
   * returned.
   */
  @Override
  public PartitionChunkListing listPartitionChunks(
      DatasetHandle datasetHandle, ListPartitionChunkOption... options) throws ConnectorException {
    List<PartitionChunk> chunks =
        Collections.singletonList(PartitionChunk.of(DatasetSplit.of(0L, 0L)));
    return () -> chunks.iterator();
  }

  @Override
  public boolean containerExists(EntityPath containerPath, GetMetadataOption... options) {
    List<String> components = containerPath.getComponents();
    if (components.size() < 1) {
      return false;
    }
    String schema = components.get(components.size() - 1);

    // Schema discovery always uses JDBC (ADBC JNI 0.22.0 does not implement getObjects).
    if (schemaFetcher == null) {
      return false;
    }
    try {
      return schemaFetcher.listSchemas().contains(schema);
    } catch (SQLException e) {
      logger.warn("Failed to check container existence for {}: {}", containerPath, e.getMessage());
      return false;
    }
  }
}
