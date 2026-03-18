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
import com.dremio.plugins.jdbc.planning.BindParam;
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.IntervalDayVector;
import org.apache.arrow.vector.IntervalYearVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.sql.type.SqlTypeName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads rows from a JDBC {@link ResultSet} and writes them into Arrow {@link ValueVector}s.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@link #setup} — registers output vectors, acquires a pooled connection, prepares and
 *       executes the SQL from {@link JdbcSubScan#getSql()}.
 *   <li>{@link #next} — reads up to {@code numRowsPerBatch} rows from the open {@link ResultSet}
 *       and writes each column value into the corresponding Arrow vector.
 *   <li>{@link #close} — releases ResultSet, PreparedStatement, and Connection.
 * </ol>
 *
 * <p>Column binding uses <em>position-based</em> access (1-indexed {@link ResultSet#getXxx(int)}).
 * This is more robust than name-based access because it handles:
 * <ul>
 *   <li>Self-join duplicate column names (e.g. {@code id, name, ..., id, name} → renamed
 *       {@code id0, name0} in the schema but not in the ResultSet)
 *   <li>Aggregate column aliases that may differ between databases (e.g. {@code EXPR$1} vs
 *       {@code count})
 * </ul>
 *
 * <p>The position mapping strategy (built once after query execution):
 * <ol>
 *   <li>For each expected schema column (in order), try to find an unused ResultSet column
 *       with a matching name (case-insensitive).
 *   <li>If no matching name is found, fall back to the ordinal position (i+1).
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

  /** Ordered map from column name to the Arrow vector that receives its values (used in setup). */
  private final Map<String, ValueVector> vectors = new LinkedHashMap<>();

  /**
   * Ordered list of (ResultSet column position, Arrow vector) pairs.
   * Built once after query execution; used in {@link #next} for position-based reading.
   * Positions are 1-indexed (JDBC convention).
   */
  private List<Map.Entry<Integer, ValueVector>> columnPositions;

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
        Class<ValueVector> vectorClass = (Class<ValueVector>) TypeHelper.getValueVectorClass(field);
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
              config.getSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
      if (queryTimeoutSec > 0) {
        stmt.setQueryTimeout(queryTimeoutSec);
      }
      setBindParameters(stmt, config.getBindParams());
      stmt.setFetchSize(numRowsPerBatch);
      rs = stmt.executeQuery();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to execute JDBC query: " + config.getSql(), e);
    }

    // Build position mapping: map each expected vector to a ResultSet column position.
    // This handles self-join duplicate column names (e.g. "id0" not in ResultSet) and
    // aggregate column aliases that differ between databases.
    columnPositions = buildColumnPositions(rs, colNames, vectors);
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
        for (Map.Entry<Integer, ValueVector> entry : columnPositions) {
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
   * Builds the column-position mapping after query execution.
   *
   * <p>For each expected column name (from {@code colNames}), tries to find an unused ResultSet
   * column with a matching name (case-insensitive). Falls back to ordinal position if no matching
   * unused column exists.
   *
   * <p>This correctly handles:
   * <ul>
   *   <li>Normal scans: name-based matching succeeds for all columns
   *   <li>Self-joins: duplicate column names (e.g. {@code id} appears twice) — the second
   *       occurrence is matched by ordinal since its schema name ({@code id0}) differs
   *   <li>Aggregate renames: if the SQL alias matches the schema name, name-based match succeeds
   * </ul>
   *
   * @param rs the open result set (not yet advanced)
   * @param colNames the expected column names in schema order
   * @param vectors the registered vectors (in insertion order, same order as colNames)
   * @return ordered list of (1-indexed position, vector) pairs for use in {@link #next}
   */
  private static List<Map.Entry<Integer, ValueVector>> buildColumnPositions(
      ResultSet rs, List<String> colNames, Map<String, ValueVector> vectors) {

    List<Map.Entry<Integer, ValueVector>> result = new ArrayList<>();

    // Build case-insensitive name → position list from ResultSet metadata
    // (LinkedHashMap preserves order; list for multi-occurrence handling)
    java.util.Map<String, List<Integer>> rsNameToPositions = new LinkedHashMap<>();
    try {
      ResultSetMetaData meta = rs.getMetaData();
      int colCount = meta.getColumnCount();
      for (int col = 1; col <= colCount; col++) {
        String rsColName = meta.getColumnLabel(col); // alias if specified, else column name
        rsNameToPositions.computeIfAbsent(rsColName.toUpperCase(), k -> new ArrayList<>()).add(col);
      }
    } catch (SQLException e) {
      logger.warn("Failed to read ResultSet metadata for column mapping; using ordinal positions", e);
      // Fall back: assign 1-indexed positions by order
      int pos = 1;
      for (String colName : colNames) {
        ValueVector vec = vectors.get(colName);
        if (vec != null) {
          result.add(new AbstractMap.SimpleEntry<>(pos++, vec));
        }
      }
      return result;
    }

    // Track which positions are already assigned to handle duplicate column names
    Set<Integer> usedPositions = new HashSet<>();
    int ordinalPos = 1;

    for (String colName : colNames) {
      ValueVector vec = vectors.get(colName);
      if (vec == null) {
        // Column was skipped during registration (not in schema); skip here too
        ordinalPos++;
        continue;
      }

      // Try to find an unused ResultSet column with this name
      List<Integer> candidates = rsNameToPositions.get(colName.toUpperCase());
      int assignedPosition = -1;
      if (candidates != null) {
        for (int candidate : candidates) {
          if (!usedPositions.contains(candidate)) {
            assignedPosition = candidate;
            break;
          }
        }
      }

      if (assignedPosition < 0) {
        // Name not found or all matching positions used: fall back to ordinal position
        // This handles self-join columns like "id0" where ResultSet has "id" at position 5
        assignedPosition = ordinalPos;
        logger.debug(
            "Column '{}' not found in ResultSet by name; falling back to ordinal position {}",
            colName, ordinalPos);
      }

      usedPositions.add(assignedPosition);
      result.add(new AbstractMap.SimpleEntry<>(assignedPosition, vec));
      ordinalPos++;
    }

    return result;
  }

  /**
   * Sets bind parameters on the PreparedStatement before execution.
   *
   * <p>Dispatches to the correct {@code stmt.setXxx()} method based on each {@link
   * BindParam#getTypeName()}. Null values are handled via {@code stmt.setNull()} with {@link
   * java.sql.Types#NULL}.
   *
   * @param stmt the prepared statement to set parameters on
   * @param params ordered bind parameters (one per ? placeholder)
   * @throws SQLException if a database access error occurs
   */
  private void setBindParameters(PreparedStatement stmt, List<BindParam> params)
      throws SQLException {
    if (params == null || params.isEmpty()) {
      return;
    }
    for (int i = 0; i < params.size(); i++) {
      int paramIndex = i + 1; // JDBC uses 1-based indexing
      BindParam param = params.get(i);
      if (param.getValue() == null) {
        stmt.setNull(paramIndex, java.sql.Types.NULL);
        continue;
      }
      SqlTypeName typeName = param.getTypeName();
      Object value = param.getValue();
      switch (typeName) {
        case TINYINT:
        case SMALLINT:
        case INTEGER:
          stmt.setInt(paramIndex, ((Number) value).intValue());
          break;
        case BIGINT:
          stmt.setLong(paramIndex, ((Number) value).longValue());
          break;
        case FLOAT:
        case REAL:
          stmt.setFloat(paramIndex, ((Number) value).floatValue());
          break;
        case DOUBLE:
          stmt.setDouble(paramIndex, ((Number) value).doubleValue());
          break;
        case DECIMAL:
          if (value instanceof BigDecimal) {
            stmt.setBigDecimal(paramIndex, (BigDecimal) value);
          } else {
            stmt.setBigDecimal(paramIndex, new BigDecimal(value.toString()));
          }
          break;
        case CHAR:
        case VARCHAR:
          stmt.setString(paramIndex, value.toString());
          break;
        case DATE:
          if (value instanceof Number) {
            stmt.setDate(paramIndex, new java.sql.Date(((Number) value).longValue()));
          } else {
            stmt.setObject(paramIndex, value);
          }
          break;
        case TIME:
          if (value instanceof Number) {
            stmt.setTime(paramIndex, new java.sql.Time(((Number) value).longValue()));
          } else {
            stmt.setObject(paramIndex, value);
          }
          break;
        case TIMESTAMP:
          if (value instanceof Number) {
            stmt.setTimestamp(paramIndex, new java.sql.Timestamp(((Number) value).longValue()));
          } else {
            stmt.setObject(paramIndex, value);
          }
          break;
        default:
          stmt.setObject(paramIndex, value);
          break;
      }
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
   * row slot {@code index}.
   *
   * <p>Reads by <em>position</em> (1-indexed) rather than by name to handle duplicate column
   * names (e.g. self-join) and columns with aliases that differ from the schema field names.
   *
   * <p>Null values (as reported by {@link ResultSet#wasNull()}) are silently skipped — the vector
   * retains its default null/zero value for the slot.
   *
   * <p>Concrete connectors can override this method to handle database-specific types, custom
   * decimal mappings, or non-standard JDBC drivers.
   *
   * @param vec the Arrow vector that will receive the value
   * @param rs the active result set positioned on the current row
   * @param colPosition the 1-indexed ResultSet column position to read
   * @param index the slot within the vector to write into
   * @throws SQLException if the JDBC driver reports an error
   */
  protected void writeValue(ValueVector vec, ResultSet rs, int colPosition, int index)
      throws SQLException {
    if (vec instanceof IntVector) {
      int val = rs.getInt(colPosition);
      if (!rs.wasNull()) {
        ((IntVector) vec).setSafe(index, val);
      }
    } else if (vec instanceof BigIntVector) {
      long val = rs.getLong(colPosition);
      if (!rs.wasNull()) {
        ((BigIntVector) vec).setSafe(index, val);
      }
    } else if (vec instanceof Float4Vector) {
      float val = rs.getFloat(colPosition);
      if (!rs.wasNull()) {
        ((Float4Vector) vec).setSafe(index, val);
      }
    } else if (vec instanceof Float8Vector) {
      double val = rs.getDouble(colPosition);
      if (!rs.wasNull()) {
        ((Float8Vector) vec).setSafe(index, val);
      }
    } else if (vec instanceof BitVector) {
      boolean val = rs.getBoolean(colPosition);
      if (!rs.wasNull()) {
        ((BitVector) vec).setSafe(index, val ? 1 : 0);
      }
    } else if (vec instanceof VarCharVector) {
      String val = rs.getString(colPosition);
      if (!rs.wasNull() && val != null) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        ((VarCharVector) vec).setSafe(index, bytes, 0, bytes.length);
      }
    } else if (vec instanceof VarBinaryVector) {
      byte[] val = rs.getBytes(colPosition);
      if (!rs.wasNull() && val != null) {
        ((VarBinaryVector) vec).setSafe(index, val, 0, val.length);
      }
    } else if (vec instanceof DecimalVector) {
      BigDecimal val = rs.getBigDecimal(colPosition);
      if (!rs.wasNull() && val != null) {
        DecimalVector dv = (DecimalVector) vec;
        ((DecimalVector) vec)
            .setSafe(index, val.setScale(dv.getScale(), java.math.RoundingMode.HALF_UP));
      }
    } else if (vec instanceof DateMilliVector) {
      java.sql.Date val = rs.getDate(colPosition);
      if (!rs.wasNull() && val != null) {
        ((DateMilliVector) vec).setSafe(index, val.getTime());
      }
    } else if (vec instanceof TimeMilliVector) {
      java.sql.Time val = rs.getTime(colPosition);
      if (!rs.wasNull() && val != null) {
        // TimeMilliVector stores milliseconds since midnight.
        ((TimeMilliVector) vec).setSafe(index, (int) (val.getTime() % 86_400_000L));
      }
    } else if (vec instanceof TimeStampMilliVector) {
      java.sql.Timestamp val = rs.getTimestamp(colPosition);
      if (!rs.wasNull() && val != null) {
        ((TimeStampMilliVector) vec).setSafe(index, val.getTime());
      }
    } else if (vec instanceof IntervalYearVector) {
      // INTERVAL YEAR TO MONTH — read as string (e.g. "+02-06"), parse to total months.
      String val = rs.getString(colPosition);
      if (!rs.wasNull() && val != null) {
        ((IntervalYearVector) vec).setSafe(index, parseIntervalYearMonths(val));
      }
    } else if (vec instanceof IntervalDayVector) {
      // INTERVAL DAY TO SECOND — read as string (e.g. "+5 12:30:45.123"), parse to days+ms.
      String val = rs.getString(colPosition);
      if (!rs.wasNull() && val != null) {
        int[] dayMs = parseIntervalDayMillis(val);
        ((IntervalDayVector) vec).setSafe(index, dayMs[0], dayMs[1]);
      }
    } else if (vec instanceof ListVector) {
      // pgvector: parse pgjdbc vector text format "[x1,x2,...,xN]" into ListVector<Float4>.
      String val = rs.getString(colPosition);
      if (!rs.wasNull() && val != null) {
        ListVector lv = (ListVector) vec;
        // Strip surrounding brackets
        String trimmed = val.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
          trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split(",");
        // The child of the ListVector is a Float4Vector.
        // Each offset slot is 4 bytes (INT32) — Arrow ListVector offset buffer layout.
        Float4Vector childVec = (Float4Vector) lv.getDataVector();
        int startOffset = lv.getOffsetBuffer().getInt((long) index * 4);
        for (int i = 0; i < parts.length; i++) {
          float f = Float.parseFloat(parts[i].trim());
          childVec.setSafe(startOffset + i, f);
        }
        lv.getOffsetBuffer().setInt((long) (index + 1) * 4, startOffset + parts.length);
        lv.setNotNull(index);
      }
      // If null: ListVector slot stays null (validity bit 0 by default from allocateNew)
    } else {
      // Fallback: attempt to read as string and store as UTF-8 bytes (best-effort).
      String val = rs.getString(colPosition);
      if (!rs.wasNull() && val != null && vec instanceof VarCharVector) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        ((VarCharVector) vec).setSafe(index, bytes, 0, bytes.length);
      }
    }
  }

  /**
   * Backward-compatible name-based overload for subclasses that override column reading.
   *
   * <p>Subclasses that override database-specific type handling should override
   * {@link #writeValue(ValueVector, ResultSet, int, int)} instead.
   *
   * @deprecated Use {@link #writeValue(ValueVector, ResultSet, int, int)} (position-based).
   */
  @Deprecated
  protected void writeValue(ValueVector vec, ResultSet rs, String colName, int index)
      throws SQLException {
    // Delegate to position-based access using findColumn (best-effort)
    try {
      int colPosition = rs.findColumn(colName);
      writeValue(vec, rs, colPosition, index);
    } catch (SQLException e) {
      // If column not found by name, log and skip
      logger.warn("Column '{}' not found in ResultSet; skipping", colName);
    }
  }

  // Expose for subclass use.
  @Override
  protected boolean isStarQuery() {
    return super.isStarQuery();
  }

  /**
   * Parses an Oracle INTERVAL YEAR TO MONTH string (e.g. "+02-06", "-01-03") to total months.
   * Format: [+|-]YY-MM
   */
  static int parseIntervalYearMonths(String s) {
    if (s == null || s.isEmpty()) {
      return 0;
    }
    int sign = 1;
    String trimmed = s.trim();
    if (trimmed.startsWith("-")) {
      sign = -1;
      trimmed = trimmed.substring(1);
    } else if (trimmed.startsWith("+")) {
      trimmed = trimmed.substring(1);
    }
    String[] parts = trimmed.split("-");
    int years = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
    int months = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
    return sign * (years * 12 + months);
  }

  /**
   * Parses an Oracle INTERVAL DAY TO SECOND string (e.g. "+5 12:30:45.123000") to [days, millis].
   * Format: [+|-]D HH:MI:SS[.fractional]
   */
  static int[] parseIntervalDayMillis(String s) {
    if (s == null || s.isEmpty()) {
      return new int[] {0, 0};
    }
    int sign = 1;
    String trimmed = s.trim();
    if (trimmed.startsWith("-")) {
      sign = -1;
      trimmed = trimmed.substring(1);
    } else if (trimmed.startsWith("+")) {
      trimmed = trimmed.substring(1);
    }
    // Split into day part and time part at the space
    String[] dayTime = trimmed.split("\\s+", 2);
    int days = Integer.parseInt(dayTime[0]);
    int millis = 0;
    if (dayTime.length > 1) {
      String timePart = dayTime[1];
      // Split HH:MI:SS.frac
      String[] hms = timePart.split(":");
      int hours = hms.length > 0 ? Integer.parseInt(hms[0]) : 0;
      int minutes = hms.length > 1 ? Integer.parseInt(hms[1]) : 0;
      double seconds = hms.length > 2 ? Double.parseDouble(hms[2]) : 0;
      millis = (hours * 3600 + minutes * 60) * 1000 + (int) (seconds * 1000);
    }
    return new int[] {sign * days, sign * millis};
  }
}
