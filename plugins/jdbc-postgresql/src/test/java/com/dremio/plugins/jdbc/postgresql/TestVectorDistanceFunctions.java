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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.dremio.exec.expr.fn.FunctionErrorContext;
import com.dremio.exec.expr.fn.FunctionErrorContextBuilder;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.impl.UnionListReader;
import org.apache.arrow.vector.holders.NullableFloat8Holder;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link VectorDistanceFunctions} — verifies correct computation of L2, cosine, and
 * inner-product distances on Arrow {@code LIST<FLOAT4>} inputs.
 *
 * <p>Tests instantiate the inner function classes directly (no running Dremio server required) and
 * construct Arrow {@link ListVector} instances in-process to supply inputs.
 *
 * <p>The approach:
 *
 * <ol>
 *   <li>Allocate a {@link RootAllocator}
 *   <li>Build {@link ListVector} instances with {@code Float4Vector} children using low-level Arrow
 *       buffer writes (same pattern as {@code JdbcRecordReader})
 *   <li>Obtain {@link UnionListReader} from each vector and position them on row 0
 *   <li>Inject readers + output holder into the function instance via reflection
 *   <li>Call {@code setup()} then {@code eval()} and assert output values
 * </ol>
 */
public class TestVectorDistanceFunctions {

  private static final double DELTA = 1e-6;

  private static final FunctionErrorContext ERR_CTX =
      FunctionErrorContextBuilder.builder().build();

  private BufferAllocator allocator;

  @Before
  public void setUp() {
    allocator = new RootAllocator(Long.MAX_VALUE);
  }

  @After
  public void tearDown() {
    allocator.close();
  }

  // =========================================================================
  // L2 distance tests
  // =========================================================================

  @Test
  public void testL2Distance_knownCase_1_2_vs_4_6() throws Exception {
    // sqrt((4-1)^2 + (6-2)^2) = sqrt(9 + 16) = sqrt(25) = 5.0
    double result = evalL2(new float[]{1f, 2f}, new float[]{4f, 6f});
    assertEquals(5.0, result, DELTA);
  }

  @Test
  public void testL2Distance_sameVector() throws Exception {
    // distance from vector to itself = 0
    double result = evalL2(new float[]{3f, 4f}, new float[]{3f, 4f});
    assertEquals(0.0, result, DELTA);
  }

  @Test
  public void testL2Distance_singleElement() throws Exception {
    // sqrt((5-0)^2) = 5.0
    double result = evalL2(new float[]{0f}, new float[]{5f});
    assertEquals(5.0, result, DELTA);
  }

  @Test
  public void testL2Distance_orthogonal3D() throws Exception {
    // [1,0,0] vs [0,1,0] -> sqrt(1+1+0) = sqrt(2)
    double result = evalL2(new float[]{1f, 0f, 0f}, new float[]{0f, 1f, 0f});
    assertEquals(java.lang.Math.sqrt(2.0), result, DELTA);
  }

  @Test
  public void testL2Distance_nullLeft() throws Exception {
    NullableFloat8Holder out = evalL2Holder(null, new float[]{1f, 2f});
    assertEquals("null left should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testL2Distance_nullRight() throws Exception {
    NullableFloat8Holder out = evalL2Holder(new float[]{1f, 2f}, null);
    assertEquals("null right should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testL2Distance_dimensionMismatch() throws Exception {
    assertThrows(RuntimeException.class,
        () -> evalL2(new float[]{1f, 2f}, new float[]{1f, 2f, 3f}));
  }

  // =========================================================================
  // Cosine distance tests
  // =========================================================================

  @Test
  public void testCosineDistance_parallelVectors() throws Exception {
    // [1,0] vs [2,0] — identical direction, cosine similarity = 1, distance = 0
    double result = evalCosine(new float[]{1f, 0f}, new float[]{2f, 0f});
    assertEquals(0.0, result, DELTA);
  }

  @Test
  public void testCosineDistance_orthogonalVectors() throws Exception {
    // [1,0] vs [0,1] — perpendicular, cosine similarity = 0, distance = 1
    double result = evalCosine(new float[]{1f, 0f}, new float[]{0f, 1f});
    assertEquals(1.0, result, DELTA);
  }

  @Test
  public void testCosineDistance_antiParallel() throws Exception {
    // [1,0] vs [-1,0] — opposite direction, cosine similarity = -1, distance = 2
    double result = evalCosine(new float[]{1f, 0f}, new float[]{-1f, 0f});
    assertEquals(2.0, result, DELTA);
  }

  @Test
  public void testCosineDistance_zeroMagnitudeLeft() throws Exception {
    // [0,0] vs [1,2] — zero-magnitude left, should return null
    NullableFloat8Holder out = evalCosineHolder(new float[]{0f, 0f}, new float[]{1f, 2f});
    assertEquals("zero-magnitude should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testCosineDistance_zeroMagnitudeRight() throws Exception {
    // [1,2] vs [0,0] — zero-magnitude right, should return null
    NullableFloat8Holder out = evalCosineHolder(new float[]{1f, 2f}, new float[]{0f, 0f});
    assertEquals("zero-magnitude should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testCosineDistance_nullLeft() throws Exception {
    NullableFloat8Holder out = evalCosineHolder(null, new float[]{1f, 0f});
    assertEquals("null left should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testCosineDistance_nullRight() throws Exception {
    NullableFloat8Holder out = evalCosineHolder(new float[]{1f, 0f}, null);
    assertEquals("null right should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testCosineDistance_dimensionMismatch() throws Exception {
    assertThrows(RuntimeException.class,
        () -> evalCosine(new float[]{1f, 2f}, new float[]{1f, 2f, 3f}));
  }

  // =========================================================================
  // Inner product tests
  // =========================================================================

  @Test
  public void testInnerProduct_knownCase() throws Exception {
    // [1,2,3] . [4,5,6] = 4+10+18 = 32, inner_product = -32
    double result = evalInner(new float[]{1f, 2f, 3f}, new float[]{4f, 5f, 6f});
    assertEquals(-32.0, result, DELTA);
  }

  @Test
  public void testInnerProduct_sameUnit() throws Exception {
    // [1,1] . [1,1] = 2, inner_product = -2
    double result = evalInner(new float[]{1f, 1f}, new float[]{1f, 1f});
    assertEquals(-2.0, result, DELTA);
  }

  @Test
  public void testInnerProduct_zeroDot() throws Exception {
    // [0,0] . [1,2] = 0, inner_product = -(0) = 0
    double result = evalInner(new float[]{0f, 0f}, new float[]{1f, 2f});
    assertEquals(0.0, result, DELTA);
  }

  @Test
  public void testInnerProduct_nullLeft() throws Exception {
    NullableFloat8Holder out = evalInnerHolder(null, new float[]{1f, 2f});
    assertEquals("null left should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testInnerProduct_nullRight() throws Exception {
    NullableFloat8Holder out = evalInnerHolder(new float[]{1f, 2f}, null);
    assertEquals("null right should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testInnerProduct_dimensionMismatch() throws Exception {
    assertThrows(RuntimeException.class,
        () -> evalInner(new float[]{1f, 2f}, new float[]{1f}));
  }

  // =========================================================================
  // Null element tests — a null inside the list should produce null output
  // =========================================================================

  @Test
  public void testL2Distance_nullElement() throws Exception {
    NullableFloat8Holder out = evalWithNullElement(
        new VectorDistanceFunctions.L2Distance(),
        new Float[]{1f, null, 3f}, new Float[]{4f, 5f, 6f});
    assertEquals("null element should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testCosineDistance_nullElement() throws Exception {
    NullableFloat8Holder out = evalWithNullElement(
        new VectorDistanceFunctions.CosineDistance(),
        new Float[]{1f, 2f, null}, new Float[]{4f, 5f, 6f});
    assertEquals("null element should produce isSet=0", 0, out.isSet);
  }

  @Test
  public void testInnerProduct_nullElement() throws Exception {
    NullableFloat8Holder out = evalWithNullElement(
        new VectorDistanceFunctions.InnerProduct(),
        new Float[]{null, 2f, 3f}, new Float[]{4f, 5f, 6f});
    assertEquals("null element should produce isSet=0", 0, out.isSet);
  }

  // =========================================================================
  // Helpers — L2
  // =========================================================================

  private double evalL2(float[] left, float[] right) throws Exception {
    NullableFloat8Holder out = evalL2Holder(left, right);
    assertEquals("Expected non-null output", 1, out.isSet);
    return out.value;
  }

  private NullableFloat8Holder evalL2Holder(float[] left, float[] right) throws Exception {
    VectorDistanceFunctions.L2Distance fn = new VectorDistanceFunctions.L2Distance();
    return evalFunction(fn, left, right);
  }

  // =========================================================================
  // Helpers — Cosine
  // =========================================================================

  private double evalCosine(float[] left, float[] right) throws Exception {
    NullableFloat8Holder out = evalCosineHolder(left, right);
    assertEquals("Expected non-null output", 1, out.isSet);
    return out.value;
  }

  private NullableFloat8Holder evalCosineHolder(float[] left, float[] right) throws Exception {
    VectorDistanceFunctions.CosineDistance fn = new VectorDistanceFunctions.CosineDistance();
    return evalFunction(fn, left, right);
  }

  // =========================================================================
  // Helpers — Inner product
  // =========================================================================

  private double evalInner(float[] left, float[] right) throws Exception {
    NullableFloat8Holder out = evalInnerHolder(left, right);
    assertEquals("Expected non-null output", 1, out.isSet);
    return out.value;
  }

  private NullableFloat8Holder evalInnerHolder(float[] left, float[] right) throws Exception {
    VectorDistanceFunctions.InnerProduct fn = new VectorDistanceFunctions.InnerProduct();
    return evalFunction(fn, left, right);
  }

  // =========================================================================
  // Core evaluation helper
  // =========================================================================

  /**
   * Injects left/right readers and output holder into a {@link com.dremio.exec.expr.SimpleFunction}
   * implementation via reflection, then calls {@code setup()} and {@code eval()}.
   *
   * <p>A {@code null} float array produces a null FieldReader (isSet=0). A non-null array produces
   * a reader positioned at row 0 of a ListVector containing those floats.
   *
   * @param fn the function instance to evaluate
   * @param leftFloats float values for the left vector (null = null input)
   * @param rightFloats float values for the right vector (null = null input)
   * @return the populated output holder after eval()
   */
  private NullableFloat8Holder evalFunction(
      com.dremio.exec.expr.SimpleFunction fn, float[] leftFloats, float[] rightFloats)
      throws Exception {

    NullableFloat8Holder out = new NullableFloat8Holder();

    // Allocate and populate vectors. We need to keep them alive during eval().
    try (ListVector leftVec = makeListVectorOrNull(leftFloats);
        ListVector rightVec = makeListVectorOrNull(rightFloats)) {

      // Build readers positioned at row 0.
      UnionListReader leftReader = makeReader(leftVec, leftFloats != null);
      UnionListReader rightReader = makeReader(rightVec, rightFloats != null);

      // Inject fields via reflection.
      injectField(fn, "left", leftReader);
      injectField(fn, "right", rightReader);
      injectField(fn, "out", out);
      injectField(fn, "errCtx", ERR_CTX);

      fn.setup();
      fn.eval();
    }
    return out;
  }

  /**
   * Creates a {@link ListVector} containing a single-row FLOAT4 list, or a null-row list if
   * {@code floats} is null.
   *
   * <p>Uses the same low-level buffer layout as {@code JdbcRecordReader}: offset buffer slot at
   * {@code (index+1)*4} is set to start + count, child {@code Float4Vector} values written at
   * positions {@code start} through {@code start+count-1}.
   */
  private ListVector makeListVectorOrNull(float[] floats) {
    // Build the Arrow field descriptor for LIST<FLOAT4>.
    org.apache.arrow.vector.types.pojo.Field float4Field =
        new org.apache.arrow.vector.types.pojo.Field(
            "$data$",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            null);
    org.apache.arrow.vector.types.pojo.Field listField =
        new org.apache.arrow.vector.types.pojo.Field(
            "v",
            FieldType.nullable(ArrowType.List.INSTANCE),
            java.util.Collections.singletonList(float4Field));

    ListVector vec = new ListVector(listField, allocator, null);
    // Initialize child with the correct type.
    vec.initializeChildrenFromFields(java.util.Collections.singletonList(float4Field));
    vec.setInitialCapacity(1);
    vec.allocateNew();

    if (floats != null) {
      // Row 0: not null.
      Float4Vector childVec = (Float4Vector) vec.getDataVector();
      int startOffset = vec.getOffsetBuffer().getInt(0L); // offset[0] = 0 after allocateNew
      for (int i = 0; i < floats.length; i++) {
        childVec.setSafe(startOffset + i, floats[i]);
      }
      // offset[1] = startOffset + floats.length
      vec.getOffsetBuffer().setInt(4L, startOffset + floats.length);
      vec.setNotNull(0);
    }
    // If floats == null: row 0 remains null (validity bit 0 by default).

    vec.setValueCount(1);
    return vec;
  }

  /**
   * Creates an {@link UnionListReader} from the given vector, positioned at row 0.
   */
  private static UnionListReader makeReader(ListVector vec, boolean hasValue) {
    UnionListReader reader = new UnionListReader(vec);
    reader.setPosition(0);
    return reader;
  }

  /**
   * Evaluates a function with Float[] inputs where null entries represent null list elements.
   */
  private NullableFloat8Holder evalWithNullElement(
      com.dremio.exec.expr.SimpleFunction fn, Float[] leftFloats, Float[] rightFloats)
      throws Exception {
    NullableFloat8Holder out = new NullableFloat8Holder();
    try (ListVector leftVec = makeListVectorWithNulls(leftFloats);
        ListVector rightVec = makeListVectorWithNulls(rightFloats)) {
      UnionListReader leftReader = new UnionListReader(leftVec);
      leftReader.setPosition(0);
      UnionListReader rightReader = new UnionListReader(rightVec);
      rightReader.setPosition(0);
      injectField(fn, "left", leftReader);
      injectField(fn, "right", rightReader);
      injectField(fn, "out", out);
      injectField(fn, "errCtx", ERR_CTX);
      fn.setup();
      fn.eval();
    }
    return out;
  }

  /**
   * Creates a ListVector with a single row where Float[] entries can be null (null elements).
   */
  private ListVector makeListVectorWithNulls(Float[] floats) {
    org.apache.arrow.vector.types.pojo.Field float4Field =
        new org.apache.arrow.vector.types.pojo.Field(
            "$data$",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            null);
    org.apache.arrow.vector.types.pojo.Field listField =
        new org.apache.arrow.vector.types.pojo.Field(
            "v",
            FieldType.nullable(ArrowType.List.INSTANCE),
            java.util.Collections.singletonList(float4Field));
    ListVector vec = new ListVector(listField, allocator, null);
    vec.initializeChildrenFromFields(java.util.Collections.singletonList(float4Field));
    vec.setInitialCapacity(1);
    vec.allocateNew();
    Float4Vector childVec = (Float4Vector) vec.getDataVector();
    int startOffset = vec.getOffsetBuffer().getInt(0L);
    for (int i = 0; i < floats.length; i++) {
      if (floats[i] != null) {
        childVec.setSafe(startOffset + i, floats[i]);
      }
      // null elements: validity bit stays 0 (default)
    }
    vec.getOffsetBuffer().setInt(4L, startOffset + floats.length);
    vec.setNotNull(0);
    vec.setValueCount(1);
    return vec;
  }

  /**
   * Sets a private field on the function instance via reflection.
   */
  @com.dremio.common.SuppressForbidden // Reflection needed to inject @Param/@Output/@Inject fields into SimpleFunction for unit testing
  private static void injectField(Object target, String fieldName, Object value)
      throws NoSuchFieldException, IllegalAccessException {
    // Walk class hierarchy to handle inner-class field declarations.
    Class<?> clazz = target.getClass();
    while (clazz != null) {
      try {
        java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
        return;
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new NoSuchFieldException("Field '" + fieldName + "' not found in " + target.getClass());
  }
}
