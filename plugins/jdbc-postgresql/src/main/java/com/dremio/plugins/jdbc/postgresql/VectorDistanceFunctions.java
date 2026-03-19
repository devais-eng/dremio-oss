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

import com.dremio.exec.expr.SimpleFunction;
import com.dremio.exec.expr.annotations.FunctionTemplate;
import com.dremio.exec.expr.annotations.Output;
import com.dremio.exec.expr.annotations.Param;
import com.dremio.exec.expr.fn.FunctionErrorContext;
import javax.inject.Inject;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.holders.NullableFloat8Holder;

/**
 * Dremio SQL functions for pgvector distance computations.
 *
 * <p>Three distance functions are registered via classpath scan (the package
 * {@code com.dremio.plugins.jdbc.postgresql} is already listed in the plugin's
 * {@code sabot-module.conf}):
 *
 * <ul>
 *   <li>{@link L2Distance} — {@code l2_distance(LIST<FLOAT4>, LIST<FLOAT4>)} → DOUBLE
 *   <li>{@link CosineDistance} — {@code cosine_distance(LIST<FLOAT4>, LIST<FLOAT4>)} → DOUBLE
 *   <li>{@link InnerProduct} — {@code inner_product(LIST<FLOAT4>, LIST<FLOAT4>)} → DOUBLE
 * </ul>
 *
 * <p>All three functions use {@link FunctionTemplate.NullHandling#INTERNAL}: {@code FieldReader}
 * params do not support {@code NULL_IF_NULL}. Null inputs are checked manually in {@code eval()}.
 *
 * <p>Dimension mismatch (lists of different lengths) throws a user-visible error via
 * {@link FunctionErrorContext}. A zero-magnitude vector in {@code cosine_distance} returns
 * {@code NULL} rather than {@code NaN} or {@code Infinity}.
 *
 * <p>Element types FLOAT4, FLOAT8, DECIMAL, INT, and BIGINT are all supported via
 * {@link #readAsDouble(FieldReader)}.
 */
public class VectorDistanceFunctions {

  /**
   * Reads a numeric value from a list element reader as a {@code double}, dispatching on
   * {@link org.apache.arrow.vector.types.Types.MinorType} so that FLOAT4, FLOAT8, DECIMAL,
   * INT, and BIGINT element types all work without throwing.
   *
   * <p>Falls back to {@code readFloat()} for any unrecognised type, preserving previous
   * behavior for legacy/unknown element types.
   */
  static double readAsDouble(FieldReader reader) {
    org.apache.arrow.vector.types.Types.MinorType type = reader.getMinorType();
    switch (type) {
      case FLOAT4:  return reader.readFloat();
      case FLOAT8:  return reader.readDouble();
      case DECIMAL: {
        java.math.BigDecimal bd = (java.math.BigDecimal) reader.readObject();
        return bd != null ? bd.doubleValue() : Double.NaN;
      }
      case INT:     return reader.readInteger();
      case BIGINT:  return reader.readLong();
      default:      return reader.readFloat(); // fallback for legacy behavior
    }
  }

  // -------------------------------------------------------------------------
  // l2_distance
  // -------------------------------------------------------------------------

  /**
   * Euclidean (L2) distance between two FLOAT4 vectors.
   *
   * <p>Result = sqrt(sum((a_i - b_i)^2))
   *
   * <p>Returns NULL if either input is NULL. Throws if the vectors have different lengths.
   */
  @FunctionTemplate(
      name = "l2_distance",
      scope = FunctionTemplate.FunctionScope.SIMPLE,
      nulls = FunctionTemplate.NullHandling.INTERNAL)
  public static class L2Distance implements SimpleFunction {

    @Param private FieldReader left;
    @Param private FieldReader right;
    @Output private NullableFloat8Holder out;
    @Inject private FunctionErrorContext errCtx;

    @Override
    public void setup() {}

    @Override
    public void eval() {
      // Manual null check required with NullHandling.INTERNAL + FieldReader params.
      if (!left.isSet() || left.readObject() == null
          || !right.isSet() || right.readObject() == null) {
        out.isSet = 0;
        return;
      }
      org.apache.arrow.vector.complex.impl.UnionListReader lReader =
          (org.apache.arrow.vector.complex.impl.UnionListReader) left;
      org.apache.arrow.vector.complex.impl.UnionListReader rReader =
          (org.apache.arrow.vector.complex.impl.UnionListReader) right;
      if (lReader.size() != rReader.size()) {
        throw errCtx.error()
            .message("l2_distance: vector dimension mismatch: %d vs %d",
                lReader.size(), rReader.size())
            .build();
      }
      double sum = 0.0;
      while (lReader.next() && rReader.next()) {
        if (!lReader.reader().isSet() || !rReader.reader().isSet()) {
          out.isSet = 0; // null element → undefined distance
          return;
        }
        double diff = readAsDouble(lReader.reader()) - readAsDouble(rReader.reader());
        sum += diff * diff;
      }
      out.isSet = 1;
      out.value = java.lang.Math.sqrt(sum);
    }
  }

  // -------------------------------------------------------------------------
  // cosine_distance
  // -------------------------------------------------------------------------

  /**
   * Cosine distance between two FLOAT4 vectors.
   *
   * <p>Result = 1 - cosine_similarity = 1 - (dot(a, b) / (||a|| * ||b||))
   *
   * <p>Returns NULL if either input is NULL, or if either vector has zero magnitude
   * (avoids NaN / Infinity in output). Throws if the vectors have different lengths.
   */
  @FunctionTemplate(
      name = "cosine_distance",
      scope = FunctionTemplate.FunctionScope.SIMPLE,
      nulls = FunctionTemplate.NullHandling.INTERNAL)
  public static class CosineDistance implements SimpleFunction {

    @Param private FieldReader left;
    @Param private FieldReader right;
    @Output private NullableFloat8Holder out;
    @Inject private FunctionErrorContext errCtx;

    @Override
    public void setup() {}

    @Override
    public void eval() {
      if (!left.isSet() || left.readObject() == null
          || !right.isSet() || right.readObject() == null) {
        out.isSet = 0;
        return;
      }
      org.apache.arrow.vector.complex.impl.UnionListReader lReader =
          (org.apache.arrow.vector.complex.impl.UnionListReader) left;
      org.apache.arrow.vector.complex.impl.UnionListReader rReader =
          (org.apache.arrow.vector.complex.impl.UnionListReader) right;
      if (lReader.size() != rReader.size()) {
        throw errCtx.error()
            .message("cosine_distance: vector dimension mismatch: %d vs %d",
                lReader.size(), rReader.size())
            .build();
      }
      double dot = 0.0, normA = 0.0, normB = 0.0;
      while (lReader.next() && rReader.next()) {
        if (!lReader.reader().isSet() || !rReader.reader().isSet()) {
          out.isSet = 0; // null element → undefined distance
          return;
        }
        double ai = readAsDouble(lReader.reader());
        double bi = readAsDouble(rReader.reader());
        dot += ai * bi;
        normA += ai * ai;
        normB += bi * bi;
      }
      // Zero-magnitude vector: return null (conventional behavior, avoids NaN/Infinity).
      if (normA == 0.0 || normB == 0.0) {
        out.isSet = 0;
        return;
      }
      out.isSet = 1;
      out.value = 1.0 - (dot / (java.lang.Math.sqrt(normA) * java.lang.Math.sqrt(normB)));
    }
  }

  // -------------------------------------------------------------------------
  // inner_product
  // -------------------------------------------------------------------------

  /**
   * Inner product (dot product) distance for FLOAT4 vectors, using pgvector convention.
   *
   * <p>Result = -dot(a, b) — negative dot product so that lower values mean more similar,
   * matching pgvector's {@code <#>} operator semantics.
   *
   * <p>Returns NULL if either input is NULL. Throws if the vectors have different lengths.
   */
  @FunctionTemplate(
      name = "inner_product",
      scope = FunctionTemplate.FunctionScope.SIMPLE,
      nulls = FunctionTemplate.NullHandling.INTERNAL)
  public static class InnerProduct implements SimpleFunction {

    @Param private FieldReader left;
    @Param private FieldReader right;
    @Output private NullableFloat8Holder out;
    @Inject private FunctionErrorContext errCtx;

    @Override
    public void setup() {}

    @Override
    public void eval() {
      if (!left.isSet() || left.readObject() == null
          || !right.isSet() || right.readObject() == null) {
        out.isSet = 0;
        return;
      }
      org.apache.arrow.vector.complex.impl.UnionListReader lReader =
          (org.apache.arrow.vector.complex.impl.UnionListReader) left;
      org.apache.arrow.vector.complex.impl.UnionListReader rReader =
          (org.apache.arrow.vector.complex.impl.UnionListReader) right;
      if (lReader.size() != rReader.size()) {
        throw errCtx.error()
            .message("inner_product: vector dimension mismatch: %d vs %d",
                lReader.size(), rReader.size())
            .build();
      }
      double sum = 0.0;
      while (lReader.next() && rReader.next()) {
        if (!lReader.reader().isSet() || !rReader.reader().isSet()) {
          out.isSet = 0; // null element → undefined distance
          return;
        }
        sum += readAsDouble(lReader.reader()) * readAsDouble(rReader.reader());
      }
      out.isSet = 1;
      out.value = -sum;
    }
  }
}
