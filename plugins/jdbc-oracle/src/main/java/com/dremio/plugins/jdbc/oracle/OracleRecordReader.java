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

import com.dremio.plugins.jdbc.exec.JdbcSubScan;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.plugins.jdbc.reader.JdbcRecordReader;
import com.dremio.sabot.exec.context.OperatorContext;

/**
 * Oracle-specific record reader for JDBC scan execution.
 *
 * <p>Extends {@link JdbcRecordReader} as a named class to provide an Oracle-specific
 * read path entry point. The base reader handles Oracle types correctly via
 * {@code rs.getTimestamp()} for TIMESTAMP, {@code rs.getString()} for CLOB, and
 * {@code rs.getBytes()} for BLOB.
 *
 * <p>No method overrides are needed for v1.5. This class exists as a named extension
 * point for future Oracle-specific read-path optimizations (e.g., Oracle-specific
 * cursor configuration or LOB streaming).
 */
public class OracleRecordReader extends JdbcRecordReader {

  /**
   * Creates a new Oracle record reader.
   *
   * @param context operator context providing batch sizing and allocator
   * @param config  the sub-scan carrying the SQL query and schema
   * @param pool    connection pool to acquire execution connections from
   * @param queryTimeoutSec maximum query execution time in seconds; 0 means no timeout
   */
  public OracleRecordReader(
      OperatorContext context, JdbcSubScan config, JdbcConnectionPool pool, int queryTimeoutSec) {
    super(context, config, pool, queryTimeoutSec);
  }
}
