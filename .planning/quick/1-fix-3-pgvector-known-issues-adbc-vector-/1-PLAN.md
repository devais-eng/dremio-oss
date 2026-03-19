---
phase: quick-fix-pgvector
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java
  - plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java
  - plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java
autonomous: true
requirements: []

must_haves:
  truths:
    - "l2_distance/cosine_distance/inner_product accept LIST<DECIMAL> and LIST<FLOAT8> inputs without exception"
    - "KNN pushdown wrapper project maps scan columns by name, not position, so non-embedding columns are not NULL"
  artifacts:
    - path: "plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java"
      provides: "readAsDouble() helper + updated eval() loops in all three functions"
      contains: "readAsDouble"
    - path: "plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java"
      provides: "DECIMAL and FLOAT8 list element test coverage"
      contains: "DECIMAL"
    - path: "plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java"
      provides: "name-based column mapping in onMatch() wrapper project"
      contains: "getFieldList().stream().filter"
  key_links:
    - from: "VectorDistanceFunctions.eval()"
      to: "FieldReader.readFloat()"
      via: "readAsDouble() dispatch on MinorType"
      pattern: "readAsDouble"
    - from: "PgvectorKnnPushdownRule.onMatch()"
      to: "scanFields"
      via: "field name lookup instead of positional index"
      pattern: "getName\\(\\)"
---

<objective>
Fix two confirmed pgvector runtime bugs:

1. Distance functions crash with DECIMAL/FLOAT8 list elements — `readFloat()` is called unconditionally regardless of element type. Fix by dispatching on `MinorType` in a shared helper.

2. KNN pushdown wrapper project maps columns by position. When scan column order differs from TopN's expected output order (e.g., scan returns `[id, name, category_id, price, embedding]` but TopN expects `[id, name, price, category_id, distance]`), positional mismatch causes non-embedding columns to be silently replaced with NULL literals. Fix by matching on field name.

ADBC vector NULL issue is deferred (requires binary protocol parsing — significant scope).

Purpose: Make DECIMAL-typed query vectors and non-trivial column orderings work correctly.
Output: Updated VectorDistanceFunctions.java, PgvectorKnnPushdownRule.java, updated tests.
</objective>

<execution_context>
@/home/filippo/.claude/get-shit-done/workflows/execute-plan.md
@/home/filippo/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java
@/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java
@/home/filippo/PycharmProjects/dremio-oss/plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java
</context>

<tasks>

<task type="auto">
  <name>Task 1: Add readAsDouble() helper and fix distance function eval() loops to support DECIMAL and FLOAT8 element types</name>
  <files>
    plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java
    plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/TestVectorDistanceFunctions.java
  </files>
  <action>
In VectorDistanceFunctions.java, add a package-private static helper method inside the outer class (not inside any inner class):

```java
static double readAsDouble(org.apache.arrow.vector.complex.reader.FieldReader reader) {
  org.apache.arrow.vector.types.Types.MinorType type = reader.getMinorType();
  switch (type) {
    case FLOAT4: return reader.readFloat();
    case FLOAT8: return reader.readDouble();
    case DECIMAL: {
      java.math.BigDecimal bd = (java.math.BigDecimal) reader.readObject();
      return bd != null ? bd.doubleValue() : Double.NaN;
    }
    case INT:    return reader.readInteger();
    case BIGINT: return reader.readLong();
    default:     return reader.readFloat(); // fallback for legacy behavior
  }
}
```

Then in all three eval() methods (L2Distance, CosineDistance, InnerProduct), replace every call to `lReader.reader().readFloat()` and `rReader.reader().readFloat()` with `readAsDouble(lReader.reader())` and `readAsDouble(rReader.reader())` respectively.

In L2Distance.eval():
- Change: `double diff = lReader.reader().readFloat() - rReader.reader().readFloat();`
- To: `double diff = readAsDouble(lReader.reader()) - readAsDouble(rReader.reader());`

In CosineDistance.eval():
- Change: `double ai = lReader.reader().readFloat(); double bi = rReader.reader().readFloat();`
- To: `double ai = readAsDouble(lReader.reader()); double bi = readAsDouble(rReader.reader());`

In InnerProduct.eval():
- Change: `sum += (double) lReader.reader().readFloat() * (double) rReader.reader().readFloat();`
- To: `sum += readAsDouble(lReader.reader()) * readAsDouble(rReader.reader());`

In TestVectorDistanceFunctions.java, add the following imports (if not already present):
```java
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.types.pojo.ArrowType;
```

Add a helper method `makeListVectorDecimal(double[] values)` that builds a LIST<DECIMAL(10,5)> vector using DecimalVector as the child (same pattern as makeListVectorOrNull but with DecimalVector instead of Float4Vector, using `childVec.setSafe(i, java.math.BigDecimal.valueOf(values[i]).setScale(5, java.math.RoundingMode.HALF_UP))`).

Add a helper method `makeListVectorFloat8(double[] values)` that builds a LIST<FLOAT8> vector using Float8Vector as child (same pattern, `childVec.setSafe(i, values[i])`).

Add two test methods for L2Distance:

```java
@Test
public void testL2Distance_decimalElements() throws Exception {
  // [0.1, 0.9] vs [0.4, 0.5]: diff1=0.3, diff2=0.4 -> sqrt(0.09+0.16) = sqrt(0.25) = 0.5
  // Use makeListVectorDecimal + evalFunctionRaw variant
  NullableFloat8Holder out = new NullableFloat8Holder();
  try (ListVector lv = makeListVectorDecimal(new double[]{0.1, 0.9});
       ListVector rv = makeListVectorDecimal(new double[]{0.4, 0.5})) {
    UnionListReader lr = new UnionListReader(lv); lr.setPosition(0);
    UnionListReader rr = new UnionListReader(rv); rr.setPosition(0);
    VectorDistanceFunctions.L2Distance fn = new VectorDistanceFunctions.L2Distance();
    injectField(fn, "left", lr);
    injectField(fn, "right", rr);
    injectField(fn, "out", out);
    injectField(fn, "errCtx", ERR_CTX);
    fn.setup(); fn.eval();
  }
  assertEquals(1, out.isSet);
  assertEquals(0.5, out.value, 1e-4);
}

@Test
public void testL2Distance_float8Elements() throws Exception {
  // [1.0, 2.0] vs [4.0, 6.0]: sqrt(9+16)=5.0
  NullableFloat8Holder out = new NullableFloat8Holder();
  try (ListVector lv = makeListVectorFloat8(new double[]{1.0, 2.0});
       ListVector rv = makeListVectorFloat8(new double[]{4.0, 6.0})) {
    UnionListReader lr = new UnionListReader(lv); lr.setPosition(0);
    UnionListReader rr = new UnionListReader(rv); rr.setPosition(0);
    VectorDistanceFunctions.L2Distance fn = new VectorDistanceFunctions.L2Distance();
    injectField(fn, "left", lr);
    injectField(fn, "right", rr);
    injectField(fn, "out", out);
    injectField(fn, "errCtx", ERR_CTX);
    fn.setup(); fn.eval();
  }
  assertEquals(1, out.isSet);
  assertEquals(5.0, out.value, 1e-4);
}
```

Also add the `DecimalVector` and `Float8Vector` field descriptor setup in the helper methods using appropriate ArrowType: `ArrowType.Decimal(10, 5, 128)` for DECIMAL and `new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)` for FLOAT8.
  </action>
  <verify>
    cd /home/filippo/PycharmProjects/dremio-oss && mvn test -pl plugins/jdbc-postgresql -Dtest=TestVectorDistanceFunctions -q 2>&1 | tail -20
  </verify>
  <done>All existing tests plus the two new DECIMAL/FLOAT8 tests pass. No `readFloat()` calls remain directly in the eval() loops of the three inner classes.</done>
</task>

<task type="auto">
  <name>Task 2: Fix KNN pushdown wrapper project to map columns by name instead of position</name>
  <files>
    plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java
  </files>
  <action>
In PgvectorKnnPushdownRule.onMatch(), replace the entire wrapper project construction loop (lines 196-209, the `for (int i = 0; i < topNFields.size(); i++)` loop) with a name-based lookup:

```java
for (int i = 0; i < topNFields.size(); i++) {
  var topNField = topNFields.get(i);
  // Find scan field with matching name (case-insensitive for robustness)
  int scanIdx = -1;
  for (int j = 0; j < scanFields.size(); j++) {
    if (scanFields.get(j).getName().equalsIgnoreCase(topNField.getName())) {
      scanIdx = j;
      break;
    }
  }
  if (scanIdx >= 0) {
    // Matched by name — use the scan field's actual type
    wrapperProjects.add(rexBuilder.makeInputRef(scanFields.get(scanIdx).getType(), scanIdx));
  } else {
    // No matching scan field (e.g. the distance column EXPR$1) — produce typed null.
    // Preserve the TopN field's exact nullability so downstream type checks pass.
    wrapperProjects.add(rexBuilder.makeNullLiteral(topNField.getType()));
  }
  wrapperNames.add(topNField.getName());
}
```

This replaces the old positional-index-based check (`scanFields.get(i).getType().getSqlTypeName() == topNField.getType().getSqlTypeName()`). The old code failed silently when scan columns `[id, name, category_id, price, embedding]` were compared positionally against TopN fields `[id, name, price, category_id, distance]`: `category_id` (index 2) would match `price`'s type by coincidence or not, leading to wrong or null values.

No other changes to the file — only replace the for-loop body.
  </action>
  <verify>
    cd /home/filippo/PycharmProjects/dremio-oss && mvn test -pl plugins/jdbc-base -Dtest=TestCalciteDialectSql,PgvectorKnnPushdownRuleTest -q 2>&1 | tail -20
    # If PgvectorKnnPushdownRuleTest doesn't exist, just compile:
    cd /home/filippo/PycharmProjects/dremio-oss && mvn compile -pl plugins/jdbc-base -q 2>&1 | tail -10
  </verify>
  <done>plugins/jdbc-base compiles cleanly. The wrapper project loop uses `equalsIgnoreCase` name matching. No positional type-comparison logic remains.</done>
</task>

</tasks>

<verification>
1. `mvn test -pl plugins/jdbc-postgresql -Dtest=TestVectorDistanceFunctions` — all tests green including new DECIMAL/FLOAT8 cases.
2. `mvn compile -pl plugins/jdbc-base` — compiles without errors.
3. Grep confirms no bare `readFloat()` in eval() loop bodies: `grep -n "readFloat" plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/VectorDistanceFunctions.java` should only show the `readAsDouble` helper, not direct calls in eval().
4. Grep confirms name-based lookup: `grep -n "equalsIgnoreCase" plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/PgvectorKnnPushdownRule.java` should return a match.
</verification>

<success_criteria>
- DECIMAL array literals like `ARRAY[0.1, 0.9]` no longer throw when passed to l2_distance/cosine_distance/inner_product.
- KNN pushdown correctly preserves all non-embedding, non-distance columns when scan column order differs from TopN output order.
- All existing unit tests pass.
</success_criteria>

<output>
After completion, create `.planning/quick/1-fix-3-pgvector-known-issues-adbc-vector-/1-SUMMARY.md` following the summary template.
</output>
