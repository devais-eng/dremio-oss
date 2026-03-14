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

import com.dremio.plugins.jdbc.conf.BaseJdbcConf;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Thin wrapper around a {@link HikariDataSource} that provides a pooled JDBC connection for JDBC
 * storage plugins.
 *
 * <p>One instance is held by {@link com.dremio.plugins.jdbc.JdbcStoragePlugin} for the lifetime of
 * the source. Connections are obtained per-query and closed by callers.
 */
public class JdbcConnectionPool implements AutoCloseable {

  private final HikariDataSource dataSource;

  /**
   * Creates a new connection pool from the supplied JDBC configuration.
   *
   * @param conf JDBC connector configuration providing URL, driver, pool size and idle timeout
   */
  public JdbcConnectionPool(BaseJdbcConf<?, ?> conf) {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(conf.jdbcUrl());
    if (conf.driverClassName() != null) {
      hikariConfig.setDriverClassName(conf.driverClassName());
    }
    hikariConfig.setMaximumPoolSize(conf.poolSize);
    hikariConfig.setIdleTimeout(conf.idleTimeoutMs);
    hikariConfig.setConnectionTestQuery(conf.validationQuery);
    String username = conf.getUsername();
    if (username != null) {
      hikariConfig.setUsername(username);
    }
    String password = conf.getPassword();
    if (password != null) {
      hikariConfig.setPassword(password);
    }
    Properties connProps = conf.getConnectionProperties();
    if (connProps != null) {
      for (String key : connProps.stringPropertyNames()) {
        hikariConfig.addDataSourceProperty(key, connProps.getProperty(key));
      }
    }
    this.dataSource = new HikariDataSource(hikariConfig);
  }

  /**
   * Obtains a live {@link Connection} from the pool.
   *
   * @return a valid connection; the caller is responsible for closing it
   * @throws SQLException if a database access error occurs
   */
  public Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  /** Shuts down the underlying {@link HikariDataSource}, releasing all pooled connections. */
  @Override
  public void close() {
    dataSource.close();
  }
}
