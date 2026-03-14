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
package com.dremio.plugins.jdbc.pool;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.apache.arrow.adbc.core.AdbcConnection;
import org.apache.arrow.adbc.core.AdbcDatabase;
import org.apache.arrow.adbc.core.AdbcDriver;
import org.apache.arrow.adbc.core.AdbcException;
import org.apache.arrow.adbc.core.AdbcInfoCode;
import org.apache.arrow.adbc.core.AdbcStatement;
import org.apache.arrow.adbc.core.BulkIngestMode;
import org.apache.arrow.adbc.core.IsolationLevel;
import org.apache.arrow.adbc.core.TypedKey;
import org.apache.arrow.adbc.driver.jni.JniDriver;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating ADBC connections to a database via the JNI bridge.
 *
 * <p>Uses a two-phase lifecycle:
 *
 * <ol>
 *   <li><b>Constructor</b> -- stores the connection URI, allocator, and max-connections limit. The
 *       factory is inert after construction.
 *   <li><b>{@link #open()}</b> -- creates the {@link JniDriver} and {@link AdbcDatabase}. After
 *       open(), the factory is ready to create connections.
 * </ol>
 *
 * <p>Concurrency is bounded by a {@link Semaphore} with {@code maxConnections} permits, reusing the
 * same user-facing config that controls HikariCP pool size. Each call to {@link #openConnection()}
 * acquires a permit; the permit is released when the returned connection is closed.
 *
 * <p>Connections are <b>not pooled</b> -- each query gets a fresh {@link AdbcConnection}. The
 * {@link AdbcDatabase} is created once per source lifecycle and reused across connections.
 */
public class AdbcConnectionFactory implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(AdbcConnectionFactory.class);

  private final String connectionUri;
  private final BufferAllocator allocator;
  private final Semaphore permits;

  private JniDriver driver;
  private AdbcDatabase database;

  /**
   * Creates a new factory. Does NOT create the JniDriver or AdbcDatabase yet.
   *
   * @param connectionUri PostgreSQL connection URI (e.g.
   *     {@code postgresql://user:pass@host:5432/dbname})
   * @param allocator Arrow buffer allocator from the Dremio operator context
   * @param maxConnections maximum concurrent ADBC connections (same as HikariCP pool size)
   */
  public AdbcConnectionFactory(
      String connectionUri, BufferAllocator allocator, int maxConnections) {
    this.connectionUri = connectionUri;
    this.allocator = allocator;
    this.permits = new Semaphore(maxConnections);
  }

  /**
   * Creates the JniDriver and AdbcDatabase. After this call, the factory is ready to create
   * connections via {@link #openConnection()}.
   *
   * @throws AdbcException if the native driver is unavailable or the database cannot be opened
   */
  public void open() throws AdbcException {
    this.driver = new JniDriver(allocator);
    Map<String, Object> params = new HashMap<>();
    JniDriver.PARAM_DRIVER.set(params, "adbc_driver_postgresql");
    AdbcDriver.PARAM_URI.set(params, connectionUri);
    this.database = driver.open(params);
    logger.info(
        "ADBC connection factory opened for URI: {}",
        connectionUri.replaceAll("://[^@]*@", "://<credentials>@"));
  }

  /**
   * Returns the Arrow allocator used by this factory's JNI driver. Bind parameter
   * VectorSchemaRoots must be created with this allocator (not the OperatorContext's)
   * because the C Data Interface requires buffers to share the same allocator root.
   */
  public BufferAllocator getAllocator() {
    return allocator;
  }

  /**
   * Creates a new ADBC connection, bounded by the concurrency semaphore.
   *
   * <p>Blocks if the maximum number of concurrent connections has been reached. The returned
   * connection is wrapped in a {@link BoundedAdbcConnection} that releases the semaphore permit
   * when closed.
   *
   * @return a bounded ADBC connection
   * @throws AdbcException if the database connection fails
   * @throws InterruptedException if the thread is interrupted while waiting for a permit
   */
  public AdbcConnection openConnection() throws AdbcException, InterruptedException {
    permits.acquire();
    try {
      AdbcConnection conn = database.connect();
      return new BoundedAdbcConnection(conn, permits);
    } catch (Exception e) {
      permits.release();
      throw e;
    }
  }

  /**
   * Checks whether the ADBC JNI driver is available on the classpath.
   *
   * @return true if {@code org.apache.arrow.adbc.driver.jni.JniDriver} can be loaded
   */
  public static boolean isAvailable() {
    try {
      Class.forName("org.apache.arrow.adbc.driver.jni.JniDriver");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** Closes the AdbcDatabase, releasing all native resources. */
  @Override
  public void close() {
    if (database != null) {
      try {
        database.close();
      } catch (Exception e) {
        logger.warn("Error closing ADBC database", e);
      }
      database = null;
    }
    driver = null;
  }

  /**
   * Delegating wrapper around {@link AdbcConnection} that releases a semaphore permit when closed.
   *
   * <p>All methods delegate to the wrapped connection. {@link #close()} first closes the delegate,
   * then releases the permit, ensuring the permit is always returned even if the connection was
   * never used.
   */
  static class BoundedAdbcConnection implements AdbcConnection {

    private final AdbcConnection delegate;
    private final Semaphore permits;

    BoundedAdbcConnection(AdbcConnection delegate, Semaphore permits) {
      this.delegate = delegate;
      this.permits = permits;
    }

    @Override
    public void cancel() throws AdbcException {
      delegate.cancel();
    }

    @Override
    public void commit() throws AdbcException {
      delegate.commit();
    }

    @Override
    public AdbcStatement createStatement() throws AdbcException {
      return delegate.createStatement();
    }

    @Override
    public AdbcStatement bulkIngest(String targetTableName, BulkIngestMode mode)
        throws AdbcException {
      return delegate.bulkIngest(targetTableName, mode);
    }

    @Override
    public ArrowReader readPartition(ByteBuffer descriptor) throws AdbcException {
      return delegate.readPartition(descriptor);
    }

    @Override
    public ArrowReader getInfo(int[] infoCodes) throws AdbcException {
      return delegate.getInfo(infoCodes);
    }

    @Override
    public ArrowReader getInfo(AdbcInfoCode[] infoCodes) throws AdbcException {
      return delegate.getInfo(infoCodes);
    }

    @Override
    public ArrowReader getInfo() throws AdbcException {
      return delegate.getInfo();
    }

    @Override
    public ArrowReader getObjects(
        GetObjectsDepth depth,
        String catalogPattern,
        String dbSchemaPattern,
        String tableNamePattern,
        String[] tableTypes,
        String columnNamePattern)
        throws AdbcException {
      return delegate.getObjects(
          depth, catalogPattern, dbSchemaPattern, tableNamePattern, tableTypes, columnNamePattern);
    }

    @Override
    public ArrowReader getStatistics(
        String catalogPattern,
        String dbSchemaPattern,
        String tableNamePattern,
        boolean approximate)
        throws AdbcException {
      return delegate.getStatistics(
          catalogPattern, dbSchemaPattern, tableNamePattern, approximate);
    }

    @Override
    public ArrowReader getStatisticNames() throws AdbcException {
      return delegate.getStatisticNames();
    }

    @Override
    public Schema getTableSchema(String catalog, String dbSchema, String tableName)
        throws AdbcException {
      return delegate.getTableSchema(catalog, dbSchema, tableName);
    }

    @Override
    public ArrowReader getTableTypes() throws AdbcException {
      return delegate.getTableTypes();
    }

    @Override
    public void rollback() throws AdbcException {
      delegate.rollback();
    }

    @Override
    public boolean getAutoCommit() throws AdbcException {
      return delegate.getAutoCommit();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws AdbcException {
      delegate.setAutoCommit(autoCommit);
    }

    @Override
    public String getCurrentCatalog() throws AdbcException {
      return delegate.getCurrentCatalog();
    }

    @Override
    public void setCurrentCatalog(String catalog) throws AdbcException {
      delegate.setCurrentCatalog(catalog);
    }

    @Override
    public String getCurrentDbSchema() throws AdbcException {
      return delegate.getCurrentDbSchema();
    }

    @Override
    public void setCurrentDbSchema(String dbSchema) throws AdbcException {
      delegate.setCurrentDbSchema(dbSchema);
    }

    @Override
    public boolean getReadOnly() throws AdbcException {
      return delegate.getReadOnly();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws AdbcException {
      delegate.setReadOnly(readOnly);
    }

    @Override
    public IsolationLevel getIsolationLevel() throws AdbcException {
      return delegate.getIsolationLevel();
    }

    @Override
    public void setIsolationLevel(IsolationLevel level) throws AdbcException {
      delegate.setIsolationLevel(level);
    }

    @Override
    public <T> T getOption(TypedKey<T> key) throws AdbcException {
      return delegate.getOption(key);
    }

    @Override
    public <T> void setOption(TypedKey<T> key, T value) throws AdbcException {
      delegate.setOption(key, value);
    }

    @Override
    public void close() throws Exception {
      try {
        delegate.close();
      } finally {
        permits.release();
      }
    }
  }
}
