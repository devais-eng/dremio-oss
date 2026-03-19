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
import com.dremio.plugins.jdbc.planning.LiteralInliner;
import com.dremio.plugins.jdbc.pool.AdbcConnectionFactory;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.scan.OutputMutator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.adbc.core.AdbcConnection;
import org.apache.arrow.adbc.core.AdbcStatement;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.calcite.sql.type.SqlTypeName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads rows from an ADBC query result using native Arrow buffers via JNI, and writes them into
 * Dremio's Arrow {@link ValueVector}s.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@link #setup} -- registers output vectors, opens an ADBC connection, translates SQL
 *       placeholders, binds parameters, and executes the query to obtain an {@link ArrowReader}.
 *   <li>{@link #next} -- loads the next Arrow batch from the reader and transfers data to the
 *       Dremio output vectors (copy, not zero-copy, because the ArrowReader uses its own
 *       allocator).
 *   <li>{@link #close} -- releases the ArrowReader, AdbcStatement, AdbcConnection, and any bind
 *       parameter VectorSchemaRoot.
 * </ol>
 *
 * <p>This reader is the ADBC counterpart of {@link JdbcRecordReader}. It uses the same {@link
 * JdbcSubScan} for SQL and bind parameters but executes via the native ADBC/JNI path instead of
 * JDBC.
 */
public class AdbcRecordReader extends AbstractRecordReader {

  private static final Logger logger = LoggerFactory.getLogger(AdbcRecordReader.class);

  private final JdbcSubScan config;
  private final AdbcConnectionFactory factory;

  /** Ordered map from column name to the Arrow vector that receives its values. */
  private final Map<String, ValueVector> vectors = new LinkedHashMap<>();

  private AdbcConnection conn;
  private AdbcStatement stmt;
  private ArrowReader arrowReader;
  private VectorSchemaRoot bindRoot;

  /**
   * Creates a new ADBC record reader.
   *
   * @param context operator context providing batch sizing and allocator
   * @param config the sub-scan carrying the SQL query, schema, and bind parameters
   * @param factory ADBC connection factory for obtaining connections
   */
  public AdbcRecordReader(
      OperatorContext context, JdbcSubScan config, AdbcConnectionFactory factory) {
    super(context, config.getColumns());
    this.config = config;
    this.factory = factory;
  }

  /**
   * Registers output vectors, opens an ADBC connection, translates SQL placeholders, binds
   * parameters, and executes the query.
   *
   * @throws RuntimeException if the ADBC connection, bind, or query execution fails
   */
  @Override
  public void setup(OutputMutator output) {
    BatchSchema fullSchema = config.getFullSchema();
    Collection<SchemaPath> projectedColumns = getColumns();

    // Determine the set of column names to project.
    List<String> colNames = new ArrayList<>();
    if (isStarQuery() || projectedColumns == null || projectedColumns.isEmpty()) {
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

    // Open ADBC connection and execute query.
    try {
      conn = factory.openConnection();
      stmt = conn.createStatement();

      // ADBC-02: Inline bind parameters as SQL literals to enable COPY binary protocol.
      // When stmt.bind() is used, the ADBC PG driver uses the Extended Query Protocol
      // (parse/bind/execute), which cannot use the faster COPY binary path. By inlining
      // literals directly into the SQL string, the driver uses the simple query protocol,
      // routing through the fast COPY binary path.
      List<BindParam> bindParams = config.getBindParams();
      boolean hasParams = bindParams != null && !bindParams.isEmpty();

      String adbcSql;
      if (hasParams) {
        // ADBC-02: Inline literals directly into SQL for COPY binary protocol.
        adbcSql = LiteralInliner.inlineBindParams(config.getSql(), bindParams);
        logger.debug(
            "ADBC-02: Inlined {} bind params into SQL for COPY binary protocol", bindParams.size());
        // Do NOT call stmt.bind() -- this is intentional for COPY binary.
      } else {
        adbcSql = config.getSql(); // No params to inline.
      }
      stmt.setSqlQuery(adbcSql);
      // Note: stmt.bind() is NOT called when params are inlined (ADBC-02).
      // The bindRoot field remains null (no VectorSchemaRoot allocation needed).

      // Execute query and obtain ArrowReader.
      AdbcStatement.QueryResult result = stmt.executeQuery();
      this.arrowReader = result.getReader();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while acquiring ADBC connection", e);
    } catch (Exception e) {
      throw new RuntimeException("Failed to execute ADBC query: " + config.getSql(), e);
    }
  }

  /**
   * Loads the next Arrow batch from the ADBC reader and transfers data to Dremio output vectors.
   *
   * @return the number of rows read; 0 means the result set is exhausted
   */
  @Override
  public int next() {
    try {
      if (!arrowReader.loadNextBatch()) {
        return 0;
      }
      VectorSchemaRoot batch = arrowReader.getVectorSchemaRoot();
      int rowCount = batch.getRowCount();

      // Transfer each column from ADBC batch to Dremio output vector.
      for (Map.Entry<String, ValueVector> entry : vectors.entrySet()) {
        String colName = entry.getKey();
        ValueVector dst = entry.getValue();

        // Find source vector by name (case-insensitive match).
        ValueVector src = findSourceVector(batch, colName);
        if (src == null) {
          logger.warn("Column '{}' not found in ADBC batch schema; leaving output null", colName);
          dst.setValueCount(rowCount);
          continue;
        }

        transferVector(src, dst, rowCount);
      }

      return rowCount;
    } catch (Exception e) {
      throw new RuntimeException("Error reading ADBC ArrowReader batch", e);
    }
  }

  /**
   * Releases the ArrowReader, AdbcStatement, AdbcConnection, and bind parameter root in
   * reverse-acquisition order.
   */
  @Override
  public void close() throws Exception {
    if (arrowReader != null) {
      try {
        arrowReader.close();
      } catch (Exception e) {
        logger.warn("Error closing ADBC ArrowReader", e);
      }
      arrowReader = null;
    }
    if (stmt != null) {
      try {
        stmt.close();
      } catch (Exception e) {
        logger.warn("Error closing ADBC Statement", e);
      }
      stmt = null;
    }
    if (conn != null) {
      try {
        conn.close();
      } catch (Exception e) {
        logger.warn("Error closing ADBC Connection", e);
      }
      conn = null;
    }
    if (bindRoot != null) {
      try {
        bindRoot.close();
      } catch (Exception e) {
        logger.warn("Error closing bind parameter VectorSchemaRoot", e);
      }
      bindRoot = null;
    }
  }

  // Expose for subclass use (same as JdbcRecordReader).
  @Override
  protected boolean isStarQuery() {
    return super.isStarQuery();
  }

  // ---- Private helpers ----

  /**
   * Finds a vector in the ADBC batch by column name, using case-insensitive matching.
   *
   * @param batch the ADBC batch root
   * @param colName the column name to find
   * @return the matching vector, or null if not found
   */
  private ValueVector findSourceVector(VectorSchemaRoot batch, String colName) {
    // Try exact match first.
    try {
      ValueVector v = batch.getVector(colName);
      if (v != null) {
        return v;
      }
    } catch (IllegalArgumentException ignored) {
      // Vector not found by exact name.
    }
    // Fall back to case-insensitive search.
    for (Field field : batch.getSchema().getFields()) {
      if (field.getName().equalsIgnoreCase(colName)) {
        return batch.getVector(field);
      }
    }
    return null;
  }

  /**
   * Copies data from an ADBC source vector to a Dremio destination vector, handling type
   * conversions for common type mismatches between ADBC native types and Dremio's expected types.
   *
   * <p>Key conversions:
   *
   * <ul>
   *   <li>ADBC DateDayVector -> Dremio DateMilliVector (day * 86400000L)
   *   <li>ADBC TimeStampMicroVector -> Dremio TimeStampMilliVector (micro / 1000)
   * </ul>
   */
  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  private void transferVector(ValueVector src, ValueVector dst, int rowCount) {
    // Clear validity bits from any previous batch — ScanOperator does NOT zero vectors
    // between next() calls. Without this, a non-null value at index N in batch K would
    // bleed through as a stale non-null in batch K+1 if index N is null in the new batch.
    dst.getValidityBuffer().setZero(0, dst.getValidityBuffer().capacity());
    if (logger.isTraceEnabled()) {
      logger.trace("transferVector: src={} dst={} rowCount={}",
          src.getClass().getSimpleName(), dst.getClass().getSimpleName(), rowCount);
    }
    // DateDayVector -> DateMilliVector conversion.
    if (src instanceof DateDayVector && dst instanceof DateMilliVector) {
      DateDayVector srcDate = (DateDayVector) src;
      DateMilliVector dstDate = (DateMilliVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!srcDate.isNull(i)) {
          dstDate.setSafe(i, srcDate.get(i) * 86400000L);
        }
      }
      dstDate.setValueCount(rowCount);
      return;
    }

    // TimeStampMicroVector -> TimeStampMilliVector conversion.
    if (src instanceof TimeStampMicroVector && dst instanceof TimeStampMilliVector) {
      TimeStampMicroVector srcTs = (TimeStampMicroVector) src;
      TimeStampMilliVector dstTs = (TimeStampMilliVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!srcTs.isNull(i)) {
          dstTs.setSafe(i, srcTs.get(i) / 1000);
        }
      }
      dstTs.setValueCount(rowCount);
      return;
    }

    // Same-type transfers: iterate and copy with setSafe.
    if (src instanceof IntVector && dst instanceof IntVector) {
      IntVector s = (IntVector) src;
      IntVector d = (IntVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          d.setSafe(i, s.get(i));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof BigIntVector && dst instanceof BigIntVector) {
      BigIntVector s = (BigIntVector) src;
      BigIntVector d = (BigIntVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          d.setSafe(i, s.get(i));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof Float4Vector && dst instanceof Float4Vector) {
      Float4Vector s = (Float4Vector) src;
      Float4Vector d = (Float4Vector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          d.setSafe(i, s.get(i));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof Float8Vector && dst instanceof Float8Vector) {
      Float8Vector s = (Float8Vector) src;
      Float8Vector d = (Float8Vector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          d.setSafe(i, s.get(i));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof BitVector && dst instanceof BitVector) {
      BitVector s = (BitVector) src;
      BitVector d = (BitVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          d.setSafe(i, s.get(i));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof VarCharVector && dst instanceof VarCharVector) {
      VarCharVector s = (VarCharVector) src;
      VarCharVector d = (VarCharVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          byte[] bytes = s.get(i);
          d.setSafe(i, bytes, 0, bytes.length);
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof VarBinaryVector && dst instanceof VarBinaryVector) {
      VarBinaryVector s = (VarBinaryVector) src;
      VarBinaryVector d = (VarBinaryVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          byte[] bytes = s.get(i);
          d.setSafe(i, bytes, 0, bytes.length);
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof DecimalVector && dst instanceof DecimalVector) {
      DecimalVector s = (DecimalVector) src;
      DecimalVector d = (DecimalVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          java.math.BigDecimal val = s.getObject(i);
          d.setSafe(i, val.setScale(d.getScale(), java.math.RoundingMode.HALF_UP));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof VarCharVector && dst instanceof DecimalVector) {
      // ADBC PG COPY binary may return numeric as string — convert to BigDecimal.
      VarCharVector s = (VarCharVector) src;
      DecimalVector d = (DecimalVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          String str = new String(s.get(i), StandardCharsets.UTF_8);
          java.math.BigDecimal val = new java.math.BigDecimal(str);
          d.setSafe(i, val.setScale(d.getScale(), java.math.RoundingMode.HALF_UP));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof BigIntVector && dst instanceof DecimalVector) {
      // ADBC PG may return numeric as BigInt when scale=0.
      BigIntVector s = (BigIntVector) src;
      DecimalVector d = (DecimalVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          java.math.BigDecimal val = java.math.BigDecimal.valueOf(s.get(i));
          d.setSafe(i, val.setScale(d.getScale(), java.math.RoundingMode.HALF_UP));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof IntVector && dst instanceof DecimalVector) {
      // ADBC PG may return small numeric as Int.
      IntVector s = (IntVector) src;
      DecimalVector d = (DecimalVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          java.math.BigDecimal val = java.math.BigDecimal.valueOf(s.get(i));
          d.setSafe(i, val.setScale(d.getScale(), java.math.RoundingMode.HALF_UP));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof Float8Vector && dst instanceof DecimalVector) {
      // ADBC PG may return numeric as Float8.
      Float8Vector s = (Float8Vector) src;
      DecimalVector d = (DecimalVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          java.math.BigDecimal val = java.math.BigDecimal.valueOf(s.get(i));
          d.setSafe(i, val.setScale(d.getScale(), java.math.RoundingMode.HALF_UP));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof DateMilliVector && dst instanceof DateMilliVector) {
      DateMilliVector s = (DateMilliVector) src;
      DateMilliVector d = (DateMilliVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          d.setSafe(i, s.get(i));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof TimeMilliVector && dst instanceof TimeMilliVector) {
      TimeMilliVector s = (TimeMilliVector) src;
      TimeMilliVector d = (TimeMilliVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          d.setSafe(i, s.get(i));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof TimeStampMilliVector && dst instanceof TimeStampMilliVector) {
      TimeStampMilliVector s = (TimeStampMilliVector) src;
      TimeStampMilliVector d = (TimeStampMilliVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          d.setSafe(i, s.get(i));
        }
      }
      d.setValueCount(rowCount);
    } else if (src instanceof ListVector && dst instanceof ListVector) {
      // pgvector: copy native Arrow ListVector<Float4> from ADBC batch to Dremio output.
      // The ADBC PG driver materializes vector(N) as ListVector<Float4> from binary wire format.
      ListVector srcList = (ListVector) src;
      ListVector dstList = (ListVector) dst;
      Float4Vector srcChild = (Float4Vector) srcList.getDataVector();
      Float4Vector dstChild = (Float4Vector) dstList.getDataVector();
      for (int i = 0; i < rowCount; i++) {
        if (!srcList.isNull(i)) {
          int srcStart = srcList.getOffsetBuffer().getInt((long) i * 4);
          int srcEnd = srcList.getOffsetBuffer().getInt((long) (i + 1) * 4);
          int dstStart = dstList.getOffsetBuffer().getInt((long) i * 4);
          int len = srcEnd - srcStart;
          for (int k = 0; k < len; k++) {
            if (!srcChild.isNull(srcStart + k)) {
              dstChild.setSafe(dstStart + k, srcChild.get(srcStart + k));
            }
          }
          dstList.getOffsetBuffer().setInt((long) (i + 1) * 4, dstStart + len);
          dstList.setNotNull(i);
        }
      }
      dstList.setValueCount(rowCount);
    } else if (src instanceof VarBinaryVector && dst instanceof ListVector) {
      // pgvector binary wire format: ADBC PG driver may return vector(N) as raw binary.
      // Format: 2 bytes dim count (uint16 BE) + 2 bytes unused + N*4 bytes float32 (BE).
      VarBinaryVector s = (VarBinaryVector) src;
      ListVector dstList = (ListVector) dst;
      Float4Vector dstChild = (Float4Vector) dstList.getDataVector();
      for (int i = 0; i < rowCount; i++) {
        if (!s.isNull(i)) {
          byte[] raw = s.get(i);
          if (raw.length >= 4) {
            int dim = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
            // Skip 2 bytes unused flags (raw[2], raw[3]).
            int dstStart = dstList.getOffsetBuffer().getInt((long) i * 4);
            int offset = 4; // start of float data
            for (int k = 0; k < dim && offset + 4 <= raw.length; k++) {
              int bits = ((raw[offset] & 0xFF) << 24)
                  | ((raw[offset + 1] & 0xFF) << 16)
                  | ((raw[offset + 2] & 0xFF) << 8)
                  | (raw[offset + 3] & 0xFF);
              dstChild.setSafe(dstStart + k, Float.intBitsToFloat(bits));
              offset += 4;
            }
            dstList.getOffsetBuffer().setInt((long) (i + 1) * 4, dstStart + dim);
            dstList.setNotNull(i);
          }
        }
      }
      dstList.setValueCount(rowCount);
    } else if (dst instanceof DecimalVector) {
      // Generic fallback for any source type -> DecimalVector via getObject().toString().
      DecimalVector d = (DecimalVector) dst;
      for (int i = 0; i < rowCount; i++) {
        if (!src.isNull(i)) {
          Object obj = src.getObject(i);
          if (obj != null) {
            java.math.BigDecimal val = new java.math.BigDecimal(obj.toString());
            d.setSafe(i, val.setScale(d.getScale(), java.math.RoundingMode.HALF_UP));
          }
        }
      }
      d.setValueCount(rowCount);
    } else {
      // Fallback: try to convert source to string and store in VarCharVector.
      if (dst instanceof VarCharVector) {
        VarCharVector d = (VarCharVector) dst;
        for (int i = 0; i < rowCount; i++) {
          if (!src.isNull(i)) {
            Object obj = src.getObject(i);
            if (obj != null) {
              byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
              d.setSafe(i, bytes, 0, bytes.length);
            }
          }
        }
        d.setValueCount(rowCount);
      } else {
        logger.warn(
            "Unsupported vector transfer: {} -> {}; leaving destination null",
            src.getClass().getSimpleName(),
            dst.getClass().getSimpleName());
        dst.setValueCount(rowCount);
      }
    }
  }

  /**
   * Builds a {@link VectorSchemaRoot} from bind parameters for ADBC statement binding.
   *
   * <p>Each bind parameter becomes a column in a single-row root. The column names follow the
   * PostgreSQL placeholder convention ({@code $1, $2, ...}).
   *
   * <p>Type mapping from Calcite SqlTypeName to Arrow:
   *
   * <ul>
   *   <li>INTEGER, TINYINT, SMALLINT -> Int(32, true)
   *   <li>BIGINT -> Int(64, true)
   *   <li>FLOAT, REAL -> FloatingPoint(SINGLE)
   *   <li>DOUBLE -> FloatingPoint(DOUBLE)
   *   <li>VARCHAR, CHAR -> Utf8
   *   <li>BOOLEAN -> Bool
   *   <li>DATE -> Date(DAY)
   *   <li>TIME -> Utf8 (string fallback)
   *   <li>TIMESTAMP -> Timestamp(MILLISECOND, null)
   *   <li>DECIMAL -> Utf8 (ADBC PG driver does not support Decimal128 binding)
   * </ul>
   */
  private VectorSchemaRoot buildBindRoot(BufferAllocator allocator, List<BindParam> params) {
    List<Field> fields = new ArrayList<>();
    for (int i = 0; i < params.size(); i++) {
      BindParam p = params.get(i);
      ArrowType arrowType = sqlTypeToArrowType(p.getTypeName());
      fields.add(new Field("$" + (i + 1), FieldType.nullable(arrowType), null));
    }
    Schema schema = new Schema(fields);
    VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
    root.allocateNew();

    for (int i = 0; i < params.size(); i++) {
      setBindValue(root.getVector(i), params.get(i), 0);
    }
    root.setRowCount(1);
    return root;
  }

  /** Maps a Calcite SQL type name to an Arrow type for bind parameter construction. */
  private ArrowType sqlTypeToArrowType(SqlTypeName typeName) {
    switch (typeName) {
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return new ArrowType.Int(32, true);
      case BIGINT:
        return new ArrowType.Int(64, true);
      case FLOAT:
      case REAL:
        return new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
      case DOUBLE:
        return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
      case VARCHAR:
      case CHAR:
        return ArrowType.Utf8.INSTANCE;
      case BOOLEAN:
        return ArrowType.Bool.INSTANCE;
      case DATE:
        return new ArrowType.Date(org.apache.arrow.vector.types.DateUnit.DAY);
      case TIME:
        // String fallback for TIME -- ADBC PG driver has limited TIME support.
        return ArrowType.Utf8.INSTANCE;
      case TIMESTAMP:
        return new ArrowType.Timestamp(TimeUnit.MILLISECOND, null);
      case DECIMAL:
        // Convert DECIMAL to string for ADBC PG driver (does not support Decimal128 binding).
        return ArrowType.Utf8.INSTANCE;
      default:
        return ArrowType.Utf8.INSTANCE;
    }
  }

  /** Sets a single bind parameter value in the given vector at the specified row index. */
  private void setBindValue(ValueVector vector, BindParam param, int index) {
    Object value = param.getValue();
    if (value == null) {
      // Leave as null (already allocated as nullable).
      return;
    }

    SqlTypeName typeName = param.getTypeName();
    switch (typeName) {
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        ((IntVector) vector).setSafe(index, ((Number) value).intValue());
        break;
      case BIGINT:
        ((BigIntVector) vector).setSafe(index, ((Number) value).longValue());
        break;
      case FLOAT:
      case REAL:
        ((Float4Vector) vector).setSafe(index, ((Number) value).floatValue());
        break;
      case DOUBLE:
        ((Float8Vector) vector).setSafe(index, ((Number) value).doubleValue());
        break;
      case BOOLEAN:
        ((BitVector) vector).setSafe(index, ((Boolean) value) ? 1 : 0);
        break;
      case DATE:
        // BindParam stores DATE as epoch millis (Long). DateDayVector needs days.
        if (value instanceof Number) {
          int days = (int) (((Number) value).longValue() / 86400000L);
          ((DateDayVector) vector).setSafe(index, days);
        }
        break;
      case TIMESTAMP:
        // BindParam stores TIMESTAMP as epoch millis (Long).
        if (value instanceof Number) {
          ((TimeStampMilliVector) vector).setSafe(index, ((Number) value).longValue());
        }
        break;
      case TIME:
      case DECIMAL:
      case VARCHAR:
      case CHAR:
      default:
        // All string-mapped types.
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        ((VarCharVector) vector).setSafe(index, bytes, 0, bytes.length);
        break;
    }
  }
}
