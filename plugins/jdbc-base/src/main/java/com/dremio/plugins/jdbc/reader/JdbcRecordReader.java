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
package com.dremio.plugins.jdbc.reader;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.expr.TypeHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.AbstractRecordReader;
import com.dremio.plugins.jdbc.exec.JdbcSubScan;
import com.dremio.plugins.jdbc.pool.JdbcConnectionPool;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.scan.OutputMutator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads rows from a JDBC {@link ResultSet} and writes them into Arrow {@link ValueVector}s.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #setup} — registers output vectors, acquires a pooled connection, prepares and
 *       executes the SQL from {@link JdbcSubScan#getSql()}.
 *   <li>{@link #next} — reads up to {@code numRowsPerBatch} rows from the open {@link ResultSet}
 *       and writes each column value into the corresponding Arrow vector.
 *   <li>{@link #close} — releases ResultSet, PreparedStatement, and Connection.
 * </ol>
 *
 * <p>The {@link #writeValue} helper is protected so that concrete connectors can override it to
 * handle database-specific types or custom mappings.
 */
public class JdbcRecordReader extends AbstractRecordReader {

  private static final Logger logger = LoggerFactory.getLogger(JdbcRecordReader.class);

  private final JdbcSubScan config;
  private final JdbcConnectionPool pool;
  private final int queryTimeoutSec;

  private Connection conn;
  private PreparedStatement stmt;
  private ResultSet rs;

  /** Ordered map from column name to the Arrow vector that receives its values. */
  private final Map<String, ValueVector> vectors = new LinkedHashMap<>();

  /**
   * Creates a new reader.
   *
   * @param context operator context providing batch sizing and allocator
   * @param config the sub-scan carrying the SQL query and schema
   * @param pool connection pool to acquire execution connections from
   * @param queryTimeoutSec maximum query execution time in seconds; 0 means no timeout
   */
  public JdbcRecordReader(
      OperatorContext context, JdbcSubScan config, JdbcConnectionPool pool, int queryTimeoutSec) {
    super(context, config.getColumns());
    this.config = config;
    this.pool = pool;
    this.queryTimeoutSec = queryTimeoutSec;
  }

  /**
   * Registers output vectors, acquires a pooled connection, and executes the query.
   *
   * @throws RuntimeException (wrapping SQLException) if the connection or query fails
   */
  @Override
  public void setup(OutputMutator output) {
    BatchSchema fullSchema = config.getFullSchema();
    Collection<SchemaPath> projectedColumns = getColumns();

    // Determine the set of column names to project.
    List<String> colNames = new ArrayList<>();
    if (isStarQuery() || projectedColumns == null || projectedColumns.isEmpty()) {
      // Read all columns from the full schema.
      for (Field field : fullSchema.getFields()) {
        colNames.add(field.getName());
      }
    } else {
      for (SchemaPath path : projectedColumns) {
        colNames.add(path.getAsUnescapedPath());
      }
    }

    // Register each projected column with the output mutator.
    for (String colName : colNames) {
      java.util.Optional<Field> fieldOpt = fullSchema.findFieldIgnoreCase(colName);
      if (!fieldOpt.isPresent()) {
        logger.warn("Projected column '{}' not found in full schema; skipping", colName);
        continue;
      }
      Field field = fieldOpt.get();
      try {
        @SuppressWarnings("unchecked")
        Class<ValueVector> vectorClass =
            (Class<ValueVector>) TypeHelper.getValueVectorClass(field);
        ValueVector vec = output.addField(field, vectorClass);
        vectors.put(field.getName(), vec);
      } catch (Exception e) {
        throw new RuntimeException(
            "Failed to add output field '" + colName + "' to OutputMutator", e);
      }
    }

    // Acquire connection and execute.
    try {
      conn = pool.getConnection();
      configureConnection(conn);
      stmt =
          conn.prepareStatement(
              config.getSql(),
              ResultSet.TYPE_FORWARD_ONLY,
              ResultSet.CONCUR_READ_ONLY);
      if (queryTimeoutSec > 0) {
        stmt.setQueryTimeout(queryTimeoutSec);
      }
      stmt.setFetchSize(numRowsPerBatch);
      rs = stmt.executeQuery();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to execute JDBC query: " + config.getSql(), e);
    }
  }

  /**
   * Reads up to {@code numRowsPerBatch} rows from the open {@link ResultSet} and writes them into
   * the registered Arrow vectors.
   *
   * @return the number of rows read; 0 means the result set is exhausted
   */
  @Override
  public int next() {
    int count = 0;
    try {
      while (count < numRowsPerBatch) {
        if (!rs.next()) {
          break;
        }
        for (Map.Entry<String, ValueVector> entry : vectors.entrySet()) {
          writeValue(entry.getValue(), rs, entry.getKey(), count);
        }
        count++;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Error reading JDBC ResultSet", e);
    }

    // Commit value counts to all vectors.
    for (ValueVector vec : vectors.values()) {
      vec.setValueCount(count);
    }
    return count;
  }

  /**
   * Releases the {@link ResultSet}, {@link PreparedStatement}, and {@link Connection} in
   * reverse-acquisition order.
   */
  @Override
  public void close() throws Exception {
    if (rs != null) {
      try {
        rs.close();
      } catch (SQLException e) {
        logger.warn("Error closing JDBC ResultSet", e);
      }
      rs = null;
    }
    if (stmt != null) {
      try {
        stmt.close();
      } catch (SQLException e) {
        logger.warn("Error closing JDBC PreparedStatement", e);
      }
      stmt = null;
    }
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException e) {
        logger.warn("Error closing JDBC Connection", e);
      }
      conn = null;
    }
  }

  /**
   * Called after a connection is obtained from the pool but before the query is executed.
   * Subclasses can override to configure connection-level settings (e.g., autoCommit, session
   * parameters).
   *
   * @param conn the newly obtained connection to configure
   * @throws SQLException if a database access error occurs
   */
  protected void configureConnection(Connection conn) throws SQLException {
    // No-op by default; subclasses override for database-specific configuration.
  }

  /**
   * Writes a single column value from the current {@link ResultSet} row into the Arrow vector at
   * position {@code index}.
   *
   * <p>Null values (as reported by {@link ResultSet#wasNull()}) are silently skipped — the vector
   * retains its default null/zero value for the slot.
   *
   * <p>Concrete connectors can override this method to handle database-specific types, custom
   * decimal mappings, or non-standard JDBC drivers.
   *
   * @param vec the Arrow vector that will receive the value
   * @param rs the active result set positioned on the current row
   * @param colName the column name to read
   * @param index the slot within the vector to write into
   * @throws SQLException if the JDBC driver reports an error
   */
  protected void writeValue(ValueVector vec, ResultSet rs, String colName, int index)
      throws SQLException {
    if (vec instanceof IntVector) {
      int val = rs.getInt(colName);
      if (!rs.wasNull()) {
        ((IntVector) vec).setSafe(index, val);
      }
    } else if (vec instanceof BigIntVector) {
      long val = rs.getLong(colName);
      if (!rs.wasNull()) {
        ((BigIntVector) vec).setSafe(index, val);
      }
    } else if (vec instanceof Float4Vector) {
      float val = rs.getFloat(colName);
      if (!rs.wasNull()) {
        ((Float4Vector) vec).setSafe(index, val);
      }
    } else if (vec instanceof Float8Vector) {
      double val = rs.getDouble(colName);
      if (!rs.wasNull()) {
        ((Float8Vector) vec).setSafe(index, val);
      }
    } else if (vec instanceof BitVector) {
      boolean val = rs.getBoolean(colName);
      if (!rs.wasNull()) {
        ((BitVector) vec).setSafe(index, val ? 1 : 0);
      }
    } else if (vec instanceof VarCharVector) {
      String val = rs.getString(colName);
      if (!rs.wasNull() && val != null) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        ((VarCharVector) vec).setSafe(index, bytes, 0, bytes.length);
      }
    } else if (vec instanceof VarBinaryVector) {
      byte[] val = rs.getBytes(colName);
      if (!rs.wasNull() && val != null) {
        ((VarBinaryVector) vec).setSafe(index, val, 0, val.length);
      }
    } else if (vec instanceof DecimalVector) {
      BigDecimal val = rs.getBigDecimal(colName);
      if (!rs.wasNull() && val != null) {
        DecimalVector dv = (DecimalVector) vec;
        ((DecimalVector) vec).setSafe(index, val.setScale(dv.getScale(), java.math.RoundingMode.HALF_UP));
      }
    } else if (vec instanceof DateMilliVector) {
      java.sql.Date val = rs.getDate(colName);
      if (!rs.wasNull() && val != null) {
        ((DateMilliVector) vec).setSafe(index, val.getTime());
      }
    } else if (vec instanceof TimeMilliVector) {
      java.sql.Time val = rs.getTime(colName);
      if (!rs.wasNull() && val != null) {
        // TimeMilliVector stores milliseconds since midnight.
        ((TimeMilliVector) vec).setSafe(index, (int) (val.getTime() % 86_400_000L));
      }
    } else if (vec instanceof TimeStampMilliVector) {
      java.sql.Timestamp val = rs.getTimestamp(colName);
      if (!rs.wasNull() && val != null) {
        ((TimeStampMilliVector) vec).setSafe(index, val.getTime());
      }
    } else {
      // Fallback: attempt to read as string and store as UTF-8 bytes (best-effort).
      String val = rs.getString(colName);
      if (!rs.wasNull() && val != null && vec instanceof VarCharVector) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        ((VarCharVector) vec).setSafe(index, bytes, 0, bytes.length);
      }
    }
  }

  // Expose for subclass use.
  @Override
  protected boolean isStarQuery() {
    return super.isStarQuery();
  }
}
