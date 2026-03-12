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
package com.dremio.plugins.jdbc.conf;

import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.catalog.conf.NotMetadataImpacting;
import com.dremio.exec.store.StoragePlugin;
import io.protostuff.Tag;
import java.util.Properties;

/**
 * Abstract base configuration for JDBC-based storage plugins.
 *
 * <p>Provides common HikariCP connection pool configuration fields that all
 * concrete JDBC connector configurations must inherit. Concrete subclasses
 * must annotate themselves with {@code @SourceType} and implement
 * {@link #jdbcUrl()} and {@link #driverClassName()}.
 *
 * @param <T> the concrete subclass type (for self-referential generics)
 * @param <P> the StoragePlugin type created by this conf
 */
public abstract class BaseJdbcConf<T extends BaseJdbcConf<T, P>, P extends StoragePlugin>
    extends ConnectionConf<T, P> {

  /** Maximum number of connections in the HikariCP pool. */
  @Tag(1)
  public int poolSize = 5;

  /** Time in milliseconds that a connection is allowed to sit idle before being evicted. */
  @Tag(2)
  @NotMetadataImpacting
  public int idleTimeoutMs = 600_000;

  /** SQL query executed to validate a connection from the pool before it is returned. */
  @Tag(3)
  @NotMetadataImpacting
  public String validationQuery = "SELECT 1";

  /**
   * Returns the JDBC username for the connection pool.
   * Concrete subclasses that require authentication should override this method.
   *
   * @return username string, or null to omit username from the pool configuration
   */
  public String getUsername() {
    return null;
  }

  /**
   * Returns the JDBC password for the connection pool.
   * Concrete subclasses that require authentication should override this method.
   *
   * @return plain-text password string, or null to omit password from the pool configuration
   */
  public String getPassword() {
    return null;
  }

  /**
   * Returns additional JDBC connection properties to be passed to the connection pool.
   * Concrete subclasses can override to supply database-specific properties (e.g., SSL parameters).
   *
   * @return a Properties object (never null); an empty Properties is the default
   */
  public Properties getConnectionProperties() {
    return new Properties();
  }

  /**
   * Returns the full JDBC URL for the target database.
   * Concrete implementations supply the driver-specific URL.
   *
   * @return JDBC URL string (never null)
   */
  public abstract String jdbcUrl();

  /**
   * Returns the fully-qualified JDBC driver class name.
   * May return null if the driver is auto-registered via the JDBC 4 service loader.
   *
   * @return driver class name, or null to rely on auto-discovery
   */
  public abstract String driverClassName();
}
