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
package com.dremio.plugins.jdbc.postgresql;

import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.plugins.jdbc.conf.BaseJdbcConf;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.inject.Provider;

/**
 * Shared TestContainers helper for PostgreSQL integration tests.
 *
 * <p>Provides a static {@link DremioPostgresContainer} instance that can be referenced from JUnit 4
 * test classes via {@code @ClassRule}. Helper methods expose pool creation and raw-SQL execution
 * for test setup.
 *
 * <p>Usage:
 *
 * <pre>
 * &#64;ClassRule
 * public static final DremioPostgresContainer PG = PostgresTestContainer.PG;
 * </pre>
 */
public final class PostgresTestContainer {

  /**
   * The shared {@code postgres:16-alpine} container.
   *
   * <p>Referenced by test classes via {@code @ClassRule}. Uses {@link DremioPostgresContainer}
   * which implements the {@code DremioContainer} marker interface required by the project's {@code
   * DremioRestrictedTestcontainersUsage} error-prone check.
   */
  public static final DremioPostgresContainer PG =
      (DremioPostgresContainer)
          new DremioPostgresContainer()
              .withDatabaseName("dremio_test")
              .withUsername("test")
              .withPassword("test")
              .withStartupAttempts(3);

  private PostgresTestContainer() {
    // utility class
  }

  /** Returns the JDBC URL for the running container. */
  public static String getJdbcUrl() {
    return PG.getJdbcUrl();
  }

  /** Returns the fixed test username. */
  public static String getUsername() {
    return "test";
  }

  /** Returns the fixed test password. */
  public static String getPassword() {
    return "test";
  }

  /**
   * Creates a {@link JdbcConnectionPool} configured to connect to the running container.
   *
   * <p>Uses a minimal named {@link BaseJdbcConf} subclass ({@link TestJdbcConf}) that delegates to
   * the container's JDBC URL and credentials. The returned pool must be closed by the caller.
   *
   * @return a live connection pool pointed at the test container
   */
  public static JdbcConnectionPool createPool() {
    TestJdbcConf conf = new TestJdbcConf();
    conf.poolSize = 3;
    conf.validationQuery = "SELECT 1";
    return new JdbcConnectionPool(conf);
  }

  /**
   * Executes a SQL statement directly against the container (bypasses the connection pool).
   *
   * <p>Useful for test setup: creating tables, inserting seed data, creating schemas.
   *
   * @param sql the SQL statement to execute
   * @throws SQLException if a database access error occurs
   */
  public static void executeSql(String sql) throws SQLException {
    try (Connection conn =
            DriverManager.getConnection(PG.getJdbcUrl(), getUsername(), getPassword());
        Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
    }
  }

  // ---------------------------------------------------------------------------
  // Internal named conf class — required because BaseJdbcConf<T, P> has a
  // self-referential bound (T extends BaseJdbcConf<T,P>) that anonymous classes
  // with wildcard type arguments cannot satisfy.
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link BaseJdbcConf} implementation backed by the test container.
   *
   * <p>This class exists solely to satisfy the self-referential generic bound on {@code
   * BaseJdbcConf<T extends BaseJdbcConf<T,P>, P>}. The {@link #newPlugin} method is not used in
   * tests.
   */
  static final class TestJdbcConf extends BaseJdbcConf<TestJdbcConf, StoragePlugin> {

    @Override
    public String jdbcUrl() {
      return PG.getJdbcUrl();
    }

    @Override
    public String driverClassName() {
      return "org.postgresql.Driver";
    }

    @Override
    public String getUsername() {
      return PostgresTestContainer.getUsername();
    }

    @Override
    public String getPassword() {
      return PostgresTestContainer.getPassword();
    }

    @Override
    public StoragePlugin newPlugin(
        PluginSabotContext context, String name, Provider<StoragePluginId> idProvider) {
      throw new UnsupportedOperationException("TestJdbcConf.newPlugin() must not be called");
    }
  }
}
