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
import com.dremio.exec.catalog.conf.DisplayMetadata;
import com.dremio.exec.catalog.conf.EncryptionValidationMode;
import com.dremio.exec.catalog.conf.NotMetadataImpacting;
import com.dremio.exec.catalog.conf.Secret;
import com.dremio.exec.catalog.conf.SecretRef;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.plugins.jdbc.JdbcStoragePlugin;
import com.dremio.plugins.jdbc.conf.BaseJdbcConf;
import com.dremio.plugins.jdbc.exec.JdbcSubScan;
import com.dremio.plugins.jdbc.planning.SqlBuilder;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.plugins.jdbc.reader.JdbcRecordReader;
import com.dremio.plugins.jdbc.schema.JdbcSchemaFetcher;
import com.dremio.sabot.exec.context.OperatorContext;
import io.protostuff.Tag;
import java.util.Properties;
import javax.inject.Provider;

/**
 * Configuration for an Oracle JDBC data source.
 *
 * <p>Extends {@link BaseJdbcConf} to supply Oracle-specific connection details including
 * hostname, port, service name, authentication credentials, SSL/TLS options, and performance
 * tuning parameters.
 *
 * <p>The source type is {@code ORACLE_DB}. The UI form is defined in
 * {@code oracle-layout.json}.
 *
 * <p>Connections use the Oracle EZConnect URL format:
 * {@code jdbc:oracle:thin:@//host:port/serviceName}.
 */
@SourceType(value = "ORACLE_DB", label = "Oracle", uiConfig = "oracle-layout.json")
public class OracleConf extends BaseJdbcConf<OracleConf, JdbcStoragePlugin> {

  // -------------------------------------------------------------------------
  // Connection fields (Tags 10-12)
  // -------------------------------------------------------------------------

  /** Hostname or IP address of the Oracle server. */
  @Tag(10)
  @DisplayMetadata(label = "Host")
  public String hostname;

  /** TCP port of the Oracle listener. Default: 1521. */
  @Tag(11)
  @DisplayMetadata(label = "Port")
  public int port = 1521;

  /** Oracle service name to connect to (used in EZConnect URL format). */
  @Tag(12)
  @DisplayMetadata(label = "Service Name")
  public String serviceName;

  // -------------------------------------------------------------------------
  // Authentication fields (Tags 20-21)
  // -------------------------------------------------------------------------

  /** JDBC username for authenticating to the Oracle server. */
  @Tag(20)
  @DisplayMetadata(label = "Username")
  public String username;

  /** JDBC password for authenticating to the Oracle server. */
  @Tag(21)
  @Secret
  @DisplayMetadata(label = "Password")
  public SecretRef password;

  // -------------------------------------------------------------------------
  // Encryption fields (Tags 30-31)
  // -------------------------------------------------------------------------

  /** Whether to encrypt the connection to the Oracle server using SSL/TLS. */
  @Tag(30)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Encrypt Connection")
  public boolean useSsl = false;

  /**
   * Controls how the SSL certificate is validated when encryption is enabled.
   * Defaults to full certificate and hostname validation.
   */
  @Tag(31)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Encryption Validation Mode")
  public EncryptionValidationMode encryptionValidationMode =
      EncryptionValidationMode.CERTIFICATE_AND_HOSTNAME_VALIDATION;

  // -------------------------------------------------------------------------
  // Performance fields (Tags 40-41)
  // -------------------------------------------------------------------------

  /**
   * Number of rows to fetch per network round-trip for cursor-based result set streaming.
   * Default: 4096.
   */
  @Tag(40)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Record fetch size")
  public int fetchSize = 4096;

  /**
   * Maximum time in seconds that a single query may run before it is cancelled.
   * 0 means no timeout.
   */
  @Tag(41)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Query timeout (seconds)")
  public int queryTimeoutSec = 0;

  // -------------------------------------------------------------------------
  // Constructor
  // -------------------------------------------------------------------------

  /**
   * Creates a new OracleConf with an Oracle-appropriate validation query.
   */
  public OracleConf() {
    this.validationQuery = "SELECT 1 FROM DUAL";
  }

  // -------------------------------------------------------------------------
  // BaseJdbcConf abstract method implementations
  // -------------------------------------------------------------------------

  /**
   * Builds the JDBC URL for the configured Oracle server using EZConnect format.
   *
   * @return a {@code jdbc:oracle:thin:@//host:port/serviceName} URL string
   */
  @Override
  public String jdbcUrl() {
    return "jdbc:oracle:thin:@//" + hostname + ":" + port + "/" + serviceName;
  }

  /**
   * Returns the fully-qualified Oracle JDBC driver class name.
   *
   * @return {@code "oracle.jdbc.OracleDriver"}
   */
  @Override
  public String driverClassName() {
    return "oracle.jdbc.OracleDriver";
  }

  // -------------------------------------------------------------------------
  // Authentication hooks (override BaseJdbcConf defaults)
  // -------------------------------------------------------------------------

  /**
   * Returns the configured username, or null if not set.
   */
  @Override
  public String getUsername() {
    return username;
  }

  /**
   * Returns the plain-text password from the configured {@link SecretRef}, or null if not set.
   */
  @Override
  public String getPassword() {
    if (SecretRef.isNullOrEmpty(password)) {
      return null;
    }
    return password.get();
  }

  /**
   * Returns additional JDBC connection properties for this Oracle source.
   *
   * <p>When a positive {@link #queryTimeoutSec} is configured, sets the Oracle
   * {@code oracle.jdbc.ReadTimeout} property (in milliseconds) to enforce a read timeout.
   *
   * <p>When SSL is enabled, sets {@code oracle.net.ssl_server_dn_match} according to the
   * configured {@link EncryptionValidationMode}.
   *
   * @return a Properties object with any additional driver-level settings
   */
  @Override
  public Properties getConnectionProperties() {
    Properties props = new Properties();
    if (queryTimeoutSec > 0) {
      props.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(queryTimeoutSec * 1000));
    }
    if (useSsl) {
      boolean dnMatch = encryptionValidationMode == null
          || encryptionValidationMode == EncryptionValidationMode.CERTIFICATE_AND_HOSTNAME_VALIDATION;
      props.setProperty("oracle.net.ssl_server_dn_match", dnMatch ? "true" : "false");
    }
    return props;
  }

  // -------------------------------------------------------------------------
  // Plugin factory
  // -------------------------------------------------------------------------

  /**
   * Creates a {@link JdbcStoragePlugin} instance wired with Oracle-specific schema fetcher,
   * record reader, and SQL builder factory overrides.
   *
   * <p>The anonymous subclass overrides:
   * <ul>
   *   <li>{@code createSchemaFetcher} — returns an {@link OracleSchemaFetcher} for Oracle-native
   *       type mapping (NUMBER, BINARY_FLOAT/DOUBLE, CLOB/NCLOB, NVARCHAR2/NCHAR, etc.).</li>
   *   <li>{@code createRecordReader} — returns an {@link OracleRecordReader}.</li>
   *   <li>{@code createSqlBuilder} — returns an {@link OracleSqlBuilder} using
   *       {@code FETCH FIRST N ROWS ONLY} instead of {@code LIMIT}.</li>
   * </ul>
   */
  @Override
  public JdbcStoragePlugin newPlugin(
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    return new JdbcStoragePlugin(this, name) {
      @Override
      protected JdbcSchemaFetcher createSchemaFetcher(JdbcConnectionPool pool) {
        return new OracleSchemaFetcher(pool);
      }

      @Override
      public JdbcRecordReader createRecordReader(
          OperatorContext ctx, JdbcSubScan config, JdbcConnectionPool pool) {
        return new OracleRecordReader(ctx, config, pool);
      }

      @Override
      public SqlBuilder createSqlBuilder() {
        return new OracleSqlBuilder();
      }
    };
  }
}
