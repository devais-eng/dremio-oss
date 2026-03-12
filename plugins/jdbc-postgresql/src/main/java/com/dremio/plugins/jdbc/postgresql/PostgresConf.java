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
import com.dremio.exec.catalog.conf.DisplayMetadata;
import com.dremio.exec.catalog.conf.EncryptionValidationMode;
import com.dremio.exec.catalog.conf.NotMetadataImpacting;
import com.dremio.exec.catalog.conf.Secret;
import com.dremio.exec.catalog.conf.SecretRef;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.plugins.jdbc.JdbcStoragePlugin;
import com.dremio.plugins.jdbc.conf.BaseJdbcConf;
import io.protostuff.Tag;
import java.util.Properties;
import javax.inject.Provider;

/**
 * Configuration for a PostgreSQL JDBC data source.
 *
 * <p>Extends {@link BaseJdbcConf} to supply PostgreSQL-specific connection details including
 * hostname, port, database name, authentication credentials, SSL/TLS options, and performance
 * tuning parameters.
 *
 * <p>The source type is {@code POSTGRES_DB}. The UI form is defined in
 * {@code postgres-layout.json}.
 */
@SourceType(value = "POSTGRES_DB", label = "PostgreSQL", uiConfig = "postgres-layout.json")
public class PostgresConf extends BaseJdbcConf<PostgresConf, JdbcStoragePlugin> {

  // -------------------------------------------------------------------------
  // Connection fields (Tags 10-12)
  // -------------------------------------------------------------------------

  /** Hostname or IP address of the PostgreSQL server. */
  @Tag(10)
  @DisplayMetadata(label = "Host")
  public String hostname;

  /** TCP port of the PostgreSQL server. Default: 5432. */
  @Tag(11)
  @DisplayMetadata(label = "Port")
  public int port = 5432;

  /** Name of the PostgreSQL database to connect to. */
  @Tag(12)
  @DisplayMetadata(label = "Database Name")
  public String databaseName;

  // -------------------------------------------------------------------------
  // Authentication fields (Tags 20-21)
  // -------------------------------------------------------------------------

  /** JDBC username for authenticating to the PostgreSQL server. */
  @Tag(20)
  @DisplayMetadata(label = "Username")
  public String username;

  /** JDBC password for authenticating to the PostgreSQL server. */
  @Tag(21)
  @Secret
  @DisplayMetadata(label = "Password")
  public SecretRef password;

  // -------------------------------------------------------------------------
  // Encryption fields (Tags 30-31)
  // -------------------------------------------------------------------------

  /** Whether to encrypt the connection to the PostgreSQL server using SSL/TLS. */
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
   * Maximum time in seconds that a single query may run before it is cancelled by the server.
   * 0 means no timeout.
   */
  @Tag(41)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Query timeout (seconds)")
  public int queryTimeoutSec = 0;

  // -------------------------------------------------------------------------
  // BaseJdbcConf abstract method implementations
  // -------------------------------------------------------------------------

  /**
   * Builds the JDBC URL for the configured PostgreSQL server.
   *
   * <p>When SSL is enabled, appends query parameters selecting the appropriate
   * {@code sslmode} for the configured {@link EncryptionValidationMode}.
   *
   * @return a {@code jdbc:postgresql://} URL string
   */
  @Override
  public String jdbcUrl() {
    StringBuilder url = new StringBuilder("jdbc:postgresql://");
    url.append(hostname).append(":").append(port).append("/").append(databaseName);
    if (useSsl) {
      String sslMode;
      if (encryptionValidationMode == null) {
        sslMode = "verify-full";
      } else {
        switch (encryptionValidationMode) {
          case CERTIFICATE_ONLY_VALIDATION:
            sslMode = "verify-ca";
            break;
          case NO_VALIDATION:
            sslMode = "require";
            break;
          case CERTIFICATE_AND_HOSTNAME_VALIDATION:
          default:
            sslMode = "verify-full";
            break;
        }
      }
      url.append("?ssl=true&sslmode=").append(sslMode);
    }
    return url.toString();
  }

  /**
   * Returns the fully-qualified PostgreSQL JDBC driver class name.
   *
   * @return {@code "org.postgresql.Driver"}
   */
  @Override
  public String driverClassName() {
    return "org.postgresql.Driver";
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
   * Returns additional JDBC connection properties for this PostgreSQL source.
   *
   * <p>When a positive {@link #queryTimeoutSec} is configured, sets the PostgreSQL
   * {@code options} property to {@code -c statement_timeout=<ms>} so the server enforces
   * a query time limit.
   *
   * @return a Properties object with any additional driver-level settings
   */
  @Override
  public Properties getConnectionProperties() {
    Properties props = new Properties();
    if (queryTimeoutSec > 0) {
      props.setProperty("options", "-c statement_timeout=" + (queryTimeoutSec * 1000));
    }
    return props;
  }

  // -------------------------------------------------------------------------
  // Plugin factory
  // -------------------------------------------------------------------------

  /**
   * Creates a {@link JdbcStoragePlugin} instance for this PostgreSQL source.
   *
   * <p>The returned plugin is wired with PostgreSQL-specific factory overrides in Task 3
   * (see {@code createSchemaFetcher} and {@code createRecordReader} overrides added to
   * the anonymous subclass).
   */
  @Override
  public JdbcStoragePlugin newPlugin(
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    return new JdbcStoragePlugin(this, name);
  }
}
