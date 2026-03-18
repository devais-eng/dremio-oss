# Phase 41: pgvector SQL Operators — Research

**Researched:** 2026-03-18
**Domain:** Dremio function registration via @FunctionTemplate; LIST<FLOAT4> iteration
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PGVEC-02 | Register `l2_distance`, `inner_product`, `cosine_distance` as real Dremio SQL functions that execute in-engine on `LIST<FLOAT4>` columns | Registration mechanism, LIST iteration, Float8 output type, FunctionTemplate pattern — all verified from OSS source |
</phase_requirements>

---

## Summary

Dremio's function registry is built by classpath scanning for classes annotated with `@FunctionTemplate`. Functions are implemented as inner static classes implementing `SimpleFunction`. No manual registration wiring is required: adding a class annotated `@FunctionTemplate` to any package that is included in the classpath scan automatically makes it available as a SQL function.

The `@Param FieldReader` type is the canonical way to accept LIST inputs. It matches any type including `LIST<FLOAT4>`. Inside `eval()`, the `FieldReader` is cast to `UnionListReader`, iterated with `while (listReader.next())`, and each float element is read via `listReader.reader().readFloat()`. The output holder is `NullableFloat8Holder` (DOUBLE), which is the appropriate precision for distance computations.

The function names `l2_distance`, `inner_product`, and `cosine_distance` do not exist anywhere in the OSS Dremio codebase. They are safe to register without namespace prefixing. Functions live either in `sabot/kernel/src/main/java/com/dremio/exec/expr/fn/impl/` (already scanned as `com.dremio.exec.expr`) or in the PostgreSQL plugin package `com.dremio.plugins.jdbc.postgresql` (already scanned via its `sabot-module.conf`). Putting them in the plugin is the cleaner separation — they belong to pgvector functionality.

**Primary recommendation:** Create `VectorDistanceFunctions.java` in `plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/` with three inner classes. Use `@Param FieldReader` for both inputs, `@Output NullableFloat8Holder`, `NullHandling.INTERNAL`, and implement null checking plus FLOAT4 list type validation in `eval()`.

---

## Standard Stack

### Core

| Component | Version | Purpose | Source |
|-----------|---------|---------|--------|
| `@FunctionTemplate` | OSS Dremio | Annotation marking a function for classpath scan registration | `sabot/kernel/src/main/java/com/dremio/exec/expr/annotations/FunctionTemplate.java` |
| `SimpleFunction` | OSS Dremio | Interface all simple (non-aggregate) functions implement | `com.dremio.exec.expr.SimpleFunction` |
| `FieldReader` | Apache Arrow | Type for `@Param` fields accepting LIST or complex inputs | `org.apache.arrow.vector.complex.reader.FieldReader` |
| `UnionListReader` | Apache Arrow | Concrete class used to iterate list elements | `org.apache.arrow.vector.complex.impl.UnionListReader` |
| `NullableFloat8Holder` | Apache Arrow | Output type for DOUBLE result | `org.apache.arrow.vector.holders.NullableFloat8Holder` |
| `FunctionErrorContext` | OSS Dremio | `@Inject`-able for throwing user-visible errors | `com.dremio.exec.expr.fn.FunctionErrorContext` |

### Not Needed

- `OutputDerivation` — only needed when output type depends on input type at planning time. For these functions the output is always `DOUBLE`, so `OutputDerivation.Default` (the annotation default) is correct.
- `@Workspace` — needed only for mutable per-call state. The arithmetic in `eval()` can be done with local variables (which are valid in generated code).
- `ArrowBuf` — only needed for buffer-backed outputs (strings, decimals). `NullableFloat8Holder` is value-based, no buffer needed.

---

## Architecture Patterns

### Where Functions Live

**Option A: `sabot/kernel` `fn/impl/array/` package** — used for general-purpose array functions like `array_sum`, `array_slice`. These functions work on any data source.

**Option B: `plugins/jdbc-postgresql/` main package** — already scanned (`dremio.classpath.scanning.packages += "com.dremio.plugins.jdbc.postgresql"`). Better separation: pgvector distance functions are conceptually PostgreSQL-specific.

**Decision: Option B.** The functions work in-engine (no Postgres dependency), but their purpose is pgvector. Place them in `com.dremio.plugins.jdbc.postgresql` so they travel with the plugin.

### Project Structure

```
plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/
├── PostgresConf.java
├── PostgresRecordReader.java
├── PostgresSchemaFetcher.java
└── VectorDistanceFunctions.java   ← new file
```

No `sabot-module.conf` changes needed — the package is already registered.

### Pattern 1: Two-LIST-param function returning FLOAT8

```java
// Source: GArrayFunctions.java (generated), ArrayFunctions.java, ArrayFunctions equals/compare
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
    if (!left.isSet() || left.readObject() == null
        || !right.isSet() || right.readObject() == null) {
      out.isSet = 0;
      return;
    }
    if (left.getMinorType() != org.apache.arrow.vector.types.Types.MinorType.LIST
        || right.getMinorType() != org.apache.arrow.vector.types.Types.MinorType.LIST) {
      throw errCtx.error()
          .message("l2_distance requires LIST inputs. Got: %s, %s",
              left.getMinorType(), right.getMinorType())
          .build();
    }
    if (left.reader().getMinorType() != org.apache.arrow.vector.types.Types.MinorType.FLOAT4
        || right.reader().getMinorType() != org.apache.arrow.vector.types.Types.MinorType.FLOAT4) {
      throw errCtx.error()
          .message("l2_distance requires LIST<FLOAT4> inputs.")
          .build();
    }
    org.apache.arrow.vector.complex.impl.UnionListReader lReader =
        (org.apache.arrow.vector.complex.impl.UnionListReader) left;
    org.apache.arrow.vector.complex.impl.UnionListReader rReader =
        (org.apache.arrow.vector.complex.impl.UnionListReader) right;

    double sum = 0.0;
    while (lReader.next() && rReader.next()) {
      double diff = lReader.reader().readFloat() - rReader.reader().readFloat();
      sum += diff * diff;
    }
    out.isSet = 1;
    out.value = Math.sqrt(sum);
  }
}
```

### Pattern 2: FieldReader iteration over FLOAT4 list

The iteration is the same pattern used in the generated `GArrayFunctions.java` for `array_sum_FLOAT`:

```java
// Source: GArrayFunctions.java (generated from ArrayFunctions.java template, data/ArrayFunction.tdd)
org.apache.arrow.vector.complex.impl.UnionListReader listReader =
    (org.apache.arrow.vector.complex.impl.UnionListReader) in;
while (listReader.next()) {
    if (listReader.reader().readObject() != null) {
        float val = listReader.reader().readFloat();
        // use val...
    }
}
```

Key: `readFloat()` is the method for `FLOAT4` list elements. This is confirmed in `ArrayFunction.tdd` which maps `{arrowType: "FLOAT4", readValue: "readFloat"}`.

### Pattern 3: NullHandling.INTERNAL with manual null check

When using `FieldReader` params, `NullHandling.NULL_IF_NULL` cannot be used (the framework cannot auto-detect null for complex types). Use `NullHandling.INTERNAL` and check `!left.isSet() || left.readObject() == null` manually at the start of `eval()`.

### Anti-Patterns to Avoid

- **Using `NullHandling.NULL_IF_NULL` with `FieldReader` params.** The null check framework only works with typed holders like `NullableFloat4Holder`. With `FieldReader`, always use `INTERNAL` and check yourself.
- **Allocating `@Workspace` ValueHolders.** Unlike Decimal functions (`DecimalArrayFunctions.java` allocates `NullableDecimalHolder sum`), Float8 math needs no holder — use a local `double` variable in `eval()`.
- **Calling `listReader.next()` before iterating.** `ArrayHelper.getFirstListPosition` calls `next()` to advance past a header element when working across multiple rows in slicing functions. For simple iteration in `eval()`, start `while (listReader.next())` directly — this is the correct pattern from `GArrayFunctions`.
- **Forgetting to reset list readers after iteration.** The framework handles this via `inputStartPositionMap` in `BaseFunctionHolder` — it saves and restores the position. The function does not need to call `reset()` manually.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Function registration | Manual SqlOperator wiring | `@FunctionTemplate` classpath scan | FunctionRegistry automatically picks up annotated classes via `ScanResult` |
| Float reading from list | Manual ByteBuffer arithmetic | `listReader.reader().readFloat()` | Arrow's `FieldReader` handles endianness and null correctly |
| Error messaging | Raw RuntimeException | `@Inject FunctionErrorContext errCtx; throw errCtx.error().message(...).build()` | User-visible error with context; FunctionErrorContext is injectable per FunctionContext contract |
| Output type inference | Custom OutputDerivation | Leave as `OutputDerivation.Default` | Default returns `baseReturn` from annotation — for DOUBLE output with NullableFloat8Holder that is always correct |

---

## Common Pitfalls

### Pitfall 1: Classpath scan not finding the function class

**What goes wrong:** Function is compiled but never registered. Calling the SQL function gives "No match found for function".

**Why it happens:** The package is not in `dremio.classpath.scanning.packages` in any loaded `sabot-module.conf`.

**How to avoid:** Verify the package is scanned. `com.dremio.plugins.jdbc.postgresql` is registered in `plugins/jdbc-postgresql/src/main/resources/sabot-module.conf`. The inner static class of `VectorDistanceFunctions.java` gets discovered because the parent package is scanned.

**Warning signs:** No exception at startup; function just resolves to "not found" at query time.

### Pitfall 2: Two FieldReader functions collide with existing array functions

**What goes wrong:** `FunctionRegistry` throws `AssertionError: Conflicting functions with similar signature found`.

**Why it happens:** The registry hashes on `functionName + input types`. Two FieldReader params produce identical type strings (`"LATE_BIND_TYPELATE_BIND_TYPE"`). If `l2_distance` is not already registered, no conflict. But if two overloads with the same FieldReader signature are registered, it explodes.

**How to avoid:** Use unique function names. `l2_distance`, `inner_product`, `cosine_distance` are verified absent from the codebase. Don't create overloads — one class per function name.

### Pitfall 3: Iterating two lists with mismatched sizes

**What goes wrong:** Shorter list exhausts while longer list still has elements. `while (lReader.next() && rReader.next())` stops at the shorter list, silently producing incorrect results for mismatched vectors.

**Why it happens:** Logical conjunction short-circuits.

**How to avoid:** After the loop, check if both readers are exhausted. Or: check `lReader.size() != rReader.size()` before iterating and throw via `errCtx`. This is the right behavior for vector distance — mismatched dimensions are an error.

### Pitfall 4: Using `readObject()` instead of `readFloat()`

**What goes wrong:** Returns `Float` as `Object`, requires unboxing. In the generated code pattern, `readObject()` is used only for null checks. `readFloat()` is the typed accessor.

**How to avoid:** Always call `listReader.reader().readFloat()` for `FLOAT4` elements, not `(Float) listReader.reader().readObject()`.

### Pitfall 5: Wrong null check pattern for FieldReader

**What goes wrong:** `out.isSet = 0; return;` sets the output to null and returns, but the list reader internal state is consumed, causing incorrect results on subsequent calls within the same batch.

**Why it happens:** FieldReader tracks position across calls within a batch. If `eval()` returns early before completing iteration, the reader's position is preserved by the framework's reset (see `BaseFunctionHolder` `inputStartPositionMap`). The framework resets position after each `eval()` call, so early return is safe.

**How to avoid:** Returning early with `out.isSet = 0` is safe — the framework handles position reset.

---

## Code Examples

### Complete L2Distance implementation (verified pattern)

```java
// Source: pattern from GArrayFunctions.java (generated), ArrayFunctions.java (two FieldReader params),
// FunctionConverter.java (FieldReader -> isFieldReader mapping),
// TypeCastRules.java line 875 (FieldReader matches any type)

package com.dremio.plugins.jdbc.postgresql;

import com.dremio.exec.expr.SimpleFunction;
import com.dremio.exec.expr.annotations.FunctionTemplate;
import com.dremio.exec.expr.annotations.Output;
import com.dremio.exec.expr.annotations.Param;
import com.dremio.exec.expr.fn.FunctionErrorContext;
import javax.inject.Inject;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.holders.NullableFloat8Holder;

public class VectorDistanceFunctions {

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
        double diff = lReader.reader().readFloat() - rReader.reader().readFloat();
        sum += diff * diff;
      }
      out.isSet = 1;
      out.value = java.lang.Math.sqrt(sum);
    }
  }

  // cosine_distance and inner_product follow the identical structural pattern
}
```

### Math for the three functions

```java
// L2 distance (Euclidean): sqrt(sum((a_i - b_i)^2))
double diff = lReader.reader().readFloat() - rReader.reader().readFloat();
sum += diff * diff;
// out.value = Math.sqrt(sum);

// Inner product (dot product): sum(a_i * b_i)
sum += lReader.reader().readFloat() * (double) rReader.reader().readFloat();
// out.value = sum;

// Cosine distance: 1 - cosine_similarity = 1 - (dot / (||a|| * ||b||))
// needs three accumulators: dot, normA, normB
double ai = lReader.reader().readFloat();
double bi = rReader.reader().readFloat();
dot += ai * bi;
normA += ai * ai;
normB += bi * bi;
// out.value = 1.0 - (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
```

### Classpath scan configuration (already in place)

```hocon
# plugins/jdbc-postgresql/src/main/resources/sabot-module.conf
# Already registers the package:
dremio.classpath.scanning.packages += "com.dremio.plugins.jdbc.postgresql"
```

No changes needed to any `.conf` file.

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| Codegen templates (`.tdd` + `.ftl`) for typed array functions | Hand-written Java for specialist functions | Codegen is used when many types need the same logic. For three specific distance functions on `LIST<FLOAT4>`, hand-written Java is appropriate and is what the `DecimalArrayFunctions.java` pattern shows. |
| Typed `@Param NullableFloat4Holder` for scalar float params | `@Param FieldReader` for LIST params | There is no `NullableListOfFloat4Holder`. Lists are always accepted via `FieldReader`. |

---

## Open Questions

1. **Two-reader dual iteration correctness with Dremio batch processing**
   - What we know: The framework resets FieldReader position after each `eval()` call (see `BaseFunctionHolder` `inputStartPositionMap`). Both readers get saved and restored.
   - What's unclear: Whether `lReader.next()` advancing past the iteration affects `rReader`'s saved start position, since they are independent readers.
   - Recommendation: The `ArrayFunctions.java` `ArrayEquals` uses two `FieldReader` params and iterates both — this is the direct precedent. Follow that pattern.

2. **`inner_product` naming**
   - What we know: No `inner_product` exists in OSS code. pgvector SQL uses `<#>` and `<=>` operators in addition to named functions.
   - What's unclear: Whether any future Dremio version might add a generic `inner_product`. The name is pgvector-conventional and safe for now.
   - Recommendation: Use `inner_product` as-is. The conflict detection in `FunctionRegistry` will catch any future collision at server startup time.

3. **cosine_distance edge case: zero-magnitude vectors**
   - What we know: Division by zero when one vector is all-zeros.
   - What's unclear: What behavior is expected — null result, error, or infinity.
   - Recommendation: Return null (`out.isSet = 0`) when either norm is zero, matching conventional behavior. Include a comment.

---

## Sources

### Primary (HIGH confidence — verified from OSS source code)

| File | What Was Verified |
|------|-------------------|
| `sabot/kernel/src/main/java/com/dremio/exec/expr/annotations/FunctionTemplate.java` | Annotation fields: `name`, `names`, `scope`, `nulls`, `derivation`, enums |
| `sabot/kernel/src/main/java/com/dremio/exec/expr/fn/FunctionRegistry.java` | Classpath scan registration: `classpathScan.getAnnotatedClasses(FunctionTemplate.class.getName())` |
| `sabot/kernel/src/main/resources/sabot-module.conf` | `annotations += com.dremio.exec.expr.annotations.FunctionTemplate`; `packages: com.dremio.exec.expr`, `com.dremio.plugins` |
| `plugins/jdbc-postgresql/src/main/resources/sabot-module.conf` | `dremio.classpath.scanning.packages += "com.dremio.plugins.jdbc.postgresql"` |
| `sabot/kernel/src/main/java/com/dremio/exec/expr/fn/FunctionConverter.java` | `FieldReader` -> `createFieldReaderRef` -> `isFieldReader = true` (line 102-104) |
| `sabot/kernel/src/main/java/com/dremio/exec/resolver/TypeCastRules.java` | `@Param FieldReader will match any type` (line 874-875) |
| `sabot/kernel/src/main/java/com/dremio/exec/expr/fn/BaseFunctionHolder.java` | FieldReader position save/restore for list iteration; `createFieldReaderRef` returns `CompleteType.LATE` |
| `sabot/kernel/target/generated-sources/fmpp/com/dremio/exec/expr/fn/impl/GArrayFunctions.java` | `readFloat()` call for FLOAT4 list element; `while (listReader.next())` pattern; `FLOAT4` type check |
| `sabot/kernel/src/main/codegen/data/ArrayFunction.tdd` | Maps `FLOAT4` → `readFloat`, `NullableFloat4Holder`, `NullableFloat8Holder` for sum |
| `sabot/kernel/src/main/java/com/dremio/exec/expr/fn/impl/ArrayFunctions.java` | Two-`FieldReader`-param precedent (`ArrayEquals`, `ArrayNotEquals`, `ArrayCompareToNullsHigh`) |
| `sabot/kernel/src/main/java/com/dremio/exec/expr/fn/impl/GeoFunctions.java` | `Float8Holder` output pattern for a scalar distance function |
| `sabot/kernel/src/main/java/com/dremio/exec/expr/fn/FunctionImplementationRegistry.java` | Classpath scan drives `FunctionRegistry`; `com.dremio.plugins` sub-package functions discovered automatically |

### Negative Findings (HIGH confidence — verified absent)

- `l2_distance` — not found in any OSS `.java` file
- `inner_product` — not found in any OSS `.java` file
- `cosine_distance` — not found in any OSS `.java` file
- Any `@FunctionTemplate` in `plugins/jdbc-postgresql/` — none exist; no precedent but no barrier either

---

## Metadata

**Confidence breakdown:**
- Function registration mechanism: HIGH — verified in FunctionRegistry, FunctionConverter, sabot-module.conf
- FieldReader matching any LIST type: HIGH — TypeCastRules line 874 comment
- Float4 list iteration via readFloat(): HIGH — GArrayFunctions.java generated output
- Two FieldReader params working together: HIGH — ArrayFunctions.java ArrayEquals direct precedent
- NullableFloat8Holder for FLOAT8 output: HIGH — GeoFunctions.java, MathFunctions.java
- Placing functions in postgresql plugin package: HIGH — sabot-module.conf already scans it
- Function name safety (no clashes): HIGH — exhaustive grep across entire codebase found zero matches

**Research date:** 2026-03-18
**Valid until:** 2026-06-18 (stable framework, 90-day estimate)
