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

import com.dremio.plugins.jdbc.exec.JdbcSubScan;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.plugins.jdbc.reader.JdbcRecordReader;
import com.dremio.sabot.exec.context.OperatorContext;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * PostgreSQL-specific record reader that enables cursor-based result set streaming.
 *
 * <p>PostgreSQL's JDBC driver only streams result sets in chunks (honouring
 * {@link java.sql.Statement#setFetchSize}) when the connection is in a transaction (i.e.
 * {@code autoCommit = false}). Without this override the driver loads the entire result set into
 * client memory regardless of the requested fetch size, causing OOM errors on large tables.
 *
 * <p>Setting {@code autoCommit = false} is safe here because the JDBC connection is used only for
 * read queries (SELECT), and is discarded after the scan completes.
 */
public class PostgresRecordReader extends JdbcRecordReader {

  /**
   * Creates a new PostgreSQL record reader.
   *
   * @param context operator context providing batch sizing and allocator
   * @param config the sub-scan carrying the SQL query and schema
   * @param pool connection pool to acquire execution connections from
   * @param queryTimeoutSec maximum query execution time in seconds; 0 means no timeout
   */
  public PostgresRecordReader(
      OperatorContext context, JdbcSubScan config, JdbcConnectionPool pool, int queryTimeoutSec) {
    super(context, config, pool, queryTimeoutSec);
  }

  /**
   * Disables auto-commit on the connection to enable PostgreSQL cursor-based fetching.
   *
   * <p>This must be called before {@code prepareStatement}, which is guaranteed by the base
   * class {@link JdbcRecordReader#setup} implementation.
   *
   * @param conn the newly obtained connection to configure
   * @throws SQLException if a database access error occurs
   */
  @Override
  protected void configureConnection(Connection conn) throws SQLException {
    conn.setAutoCommit(false);
  }
}
