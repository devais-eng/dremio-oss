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
package com.dremio.plugins.jdbc.oracle;

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
 * Shared TestContainers helper for Oracle XE integration tests.
 *
 * <p>Provides a static {@link DremioOracleContainer} instance that can be referenced from JUnit 4
 * test classes via {@code @ClassRule}. Helper methods expose pool creation and raw-SQL execution
 * for test setup.
 *
 * <p>Usage:
 *
 * <pre>
 * &#64;ClassRule
 * public static final DremioOracleContainer ORACLE = OracleTestContainer.ORACLE;
 * </pre>
 *
 * <p>Oracle XE uses the default PDB service name {@code xepdb1}. The test user is created by
 * TestContainers using the {@code withUsername}/{@code withPassword} configuration; the user's
 * schema name in Oracle matches the username in uppercase: {@code TEST_USER}.
 */
public final class OracleTestContainer {

  /**
   * The shared {@code gvenzl/oracle-xe:21-slim} container.
   *
   * <p>Referenced by test classes via {@code @ClassRule}. Uses {@link DremioOracleContainer} which
   * implements the {@code DremioContainer} marker interface required by the project's {@code
   * DremioRestrictedTestcontainersUsage} error-prone check.
   *
   * <p>Note: {@code withDatabaseName} is NOT called — the Oracle XE container uses the default PDB
   * service name {@code xepdb1} which is already baked into the JDBC URL returned by {@link
   * DremioOracleContainer#getJdbcUrl()}.
   */
  public static final DremioOracleContainer ORACLE =
      (DremioOracleContainer)
          new DremioOracleContainer()
              .withUsername("test_user")
              .withPassword("test_pass")
              .withStartupAttempts(3);

  private OracleTestContainer() {
    // utility class
  }

  /** Returns the JDBC URL for the running container. */
  public static String getJdbcUrl() {
    return ORACLE.getJdbcUrl();
  }

  /** Returns the fixed test username. */
  public static String getUsername() {
    return "test_user";
  }

  /** Returns the fixed test password. */
  public static String getPassword() {
    return "test_pass";
  }

  /**
   * Creates a {@link JdbcConnectionPool} configured to connect to the running Oracle container.
   *
   * <p>Uses a minimal named {@link BaseJdbcConf} subclass ({@link TestJdbcConf}) that delegates to
   * the container's JDBC URL and credentials. The returned pool must be closed by the caller.
   *
   * @return a live connection pool pointed at the test Oracle container
   */
  public static JdbcConnectionPool createPool() {
    TestJdbcConf conf = new TestJdbcConf();
    conf.poolSize = 3;
    conf.validationQuery = "SELECT 1 FROM DUAL";
    return new JdbcConnectionPool(conf);
  }

  /**
   * Executes a SQL statement directly against the Oracle container (bypasses the connection pool).
   *
   * <p>Useful for test setup: creating tables, inserting seed data.
   *
   * @param sql the SQL statement to execute
   * @throws SQLException if a database access error occurs
   */
  public static void executeSql(String sql) throws SQLException {
    try (Connection conn =
            DriverManager.getConnection(ORACLE.getJdbcUrl(), getUsername(), getPassword());
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
   * Minimal {@link BaseJdbcConf} implementation backed by the Oracle test container.
   *
   * <p>This class exists solely to satisfy the self-referential generic bound on {@code
   * BaseJdbcConf<T extends BaseJdbcConf<T,P>, P>}. The {@link #newPlugin} method is not used in
   * tests.
   */
  static final class TestJdbcConf extends BaseJdbcConf<TestJdbcConf, StoragePlugin> {

    @Override
    public String jdbcUrl() {
      return ORACLE.getJdbcUrl();
    }

    @Override
    public String driverClassName() {
      return "oracle.jdbc.OracleDriver";
    }

    @Override
    public String getUsername() {
      return OracleTestContainer.getUsername();
    }

    @Override
    public String getPassword() {
      return OracleTestContainer.getPassword();
    }

    @Override
    public StoragePlugin newPlugin(
        PluginSabotContext context, String name, Provider<StoragePluginId> idProvider) {
      throw new UnsupportedOperationException("TestJdbcConf.newPlugin() must not be called");
    }
  }
}
