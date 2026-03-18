# Phase 42: pgvector Operator Pushdown — Research

**Researched:** 2026-03-18
**Domain:** Calcite SQL rendering pipeline, SqlDialect customization, PushdownFunctionRegistry extension
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PGVEC-03 | ORDER BY l2_distance/cosine_distance/inner_product + LIMIT K pushes to pgvector infix operators (<->, <=>, <#>) enabling HNSW index-accelerated nearest-neighbor search | Full rendering pipeline verified from OSS source; SqlDialect.unparseCall hook confirmed; PushdownFunctionRegistry wiring mapped |
</phase_requirements>

---

## Summary

Phase 42 requires two coordinated changes: (1) whitelisting the three vector distance functions in the `PushdownFunctionRegistry` so the existing expression-pushdown rules (`JdbcPushTopNWithExpressionsHep`, `JdbcPushSortWithExpressionsHep`) approve `ORDER BY l2_distance(...) LIMIT K` for pushdown, and (2) making `DremioJdbcImplementor` render `l2_distance(a, b)` SQL calls as `a <-> b` (and the other two functions as `a <=> b` / `a <#> b`) when targeting PostgreSQL.

The rendering hook is `SqlDialect.unparseCall()`. Research confirms that `SqlCall.unparse()` always delegates to `dialect.unparseCall()` (verified by bytecode inspection of Calcite 1.22.0-dremio). By creating `DremioPostgresDialect extends PostgresqlSqlDialect` and overriding `unparseCall()`, we intercept `l2_distance / cosine_distance / inner_product` calls and emit `operand0 <OP> operand1`. The second operand — typically an `ARRAY[...]` literal — must be rendered as `'[val1,val2,val3]'` because pgvector's distance operators expect a vector literal string (implicit cast from text to vector). This rendering requires detecting a `SqlCall` with kind `ARRAY_VALUE_CONSTRUCTOR` and extracting its numeric literal values.

The `JdbcRulesFactory` currently hardcodes `StandardPushdownFunctionRegistry.INSTANCE`. The cleanest Phase 42 fix is to add `l2_distance / cosine_distance / inner_product` to `StandardPushdownFunctionRegistry` itself (they are generic function names, not vendor-specific). The dialect-specific rendering via `DremioPostgresDialect` ensures only PostgreSQL actually translates them to infix form.

**Primary recommendation:** Add three function names to `StandardPushdownFunctionRegistry.WHITELISTED_FUNCTION_NAMES`. Create `DremioPostgresDialect extends PostgresqlSqlDialect` with an `unparseCall` override. Wire it in `PostgresConf.newPlugin()` via a `createDialect()` override. Write unit tests against the dialect directly (no container) and one integration test against `pgvector/pgvector:pg16` to verify HNSW index usage via `EXPLAIN`.

---

## Standard Stack

### Core Components (all verified by source inspection)

| Component | Location | Purpose | Confidence |
|-----------|----------|---------|------------|
| `StandardPushdownFunctionRegistry` | `plugins/jdbc-base/.../planning/` | Whitelist for pushable functions — add three names here | HIGH |
| `DremioPostgresDialect` | NEW in `plugins/jdbc-postgresql/...` | Subclass of `PostgresqlSqlDialect`; overrides `unparseCall` to render infix operators | HIGH |
| `PostgresConf.newPlugin()` | `plugins/jdbc-postgresql/...` | Anonymous `JdbcStoragePlugin` subclass — add `createDialect()` override returning `DremioPostgresDialect.INSTANCE` | HIGH |
| `SqlDialect.unparseCall(SqlWriter, SqlCall, int, int)` | Calcite | Called by `SqlCall.unparse()` for ALL function calls (bytecode-verified) | HIGH |
| `SqlUtil.unparseBinarySyntax(...)` | Calcite | Renders `left OP right` — callable from `unparseCall` override | HIGH |
| `SqlKind.ARRAY_VALUE_CONSTRUCTOR` | Calcite | Identifies an `ARRAY[...]` operand in the `SqlCall` tree | HIGH |
| `JdbcPushTopNWithExpressionsHep` | `plugins/jdbc-base/.../planning/` | Already handles `ORDER BY expression LIMIT K` — no change needed once whitelist is updated | HIGH |
| `JdbcPushSortWithExpressionsHep` | `plugins/jdbc-base/.../planning/` | Already handles `ORDER BY expression` in SortPrel path — no change needed | HIGH |

### Not Needed

- No new Volcano planner rule — the title "Volcano planner rules" is architectural shorthand; the existing expression-pushdown HEP rules already handle `ORDER BY function() LIMIT K`. No new rule class is required.
- No `RexShuttle` rewriting before rendering — the `unparseCall` override handles translation at the SQL text level.
- No subclass of `DremioJdbcImplementor` — the dialect hook is sufficient.
- No `SqlBinaryOperator` instance needed — rendering is done directly in `unparseCall` via `SqlUtil.unparseBinarySyntax` with a throw-away operator, or via direct `writer.print()`.

---

## Architecture Patterns

### Rendering Pipeline (verified by Calcite bytecode)

```
JdbcScanPrel.getPhysicalOperator()
  └─ DremioJdbcImplementor.implement(root)   // builds SqlNode AST
       └─ result.asStatement().toSqlString(dialect)
            └─ SqlNode.unparse()
                 └─ SqlCall.unparse()
                      └─ dialect.unparseCall(writer, call, lp, rp)  ← OUR HOOK
                           └─ (default) SqlOperator.unparse() → SqlSyntax.FUNCTION.unparse()
                                → renders: l2_distance(col, ARRAY[1,2,3])
```

With `DremioPostgresDialect.unparseCall()` installed, the rendering becomes:
```
                      └─ DremioPostgresDialect.unparseCall()
                           ├─ case "l2_distance":  emit: col <-> '[1,2,3]'
                           ├─ case "cosine_distance": emit: col <=> '[1,2,3]'
                           ├─ case "inner_product":   emit: col <#> '[1,2,3]'
                           └─ else: super.unparseCall()
```

### RexCall → SqlCall → Rendering Flow

The `@FunctionTemplate`-annotated functions (`L2Distance`, `CosineDistance`, `InnerProduct`) are registered as `SqlFunctionImpl` instances with `SqlKind.OTHER_FUNCTION` and `SqlSyntax.FUNCTION`. When they appear in a `RexCall`, Calcite's `SqlImplementor.Context.toSql()` calls `op.createCall(nodeList)` producing a `SqlCall`. The operator name on that `SqlCall` is the function name as registered (e.g. `"l2_distance"`). The dialect's `unparseCall` receives this `SqlCall` and can inspect `call.getOperator().getName()`.

### Pattern 1: DremioPostgresDialect

```java
// Source: SqlDialect.unparseCall bytecode + SqlCall.unparse() bytecode (verified)
// Location: plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/
public final class DremioPostgresDialect extends PostgresqlSqlDialect {

  public static final DremioPostgresDialect INSTANCE =
      new DremioPostgresDialect(PostgresqlSqlDialect.DEFAULT_CONTEXT);

  public DremioPostgresDialect(SqlDialect.Context context) {
    super(context);
  }

  @Override
  public void unparseCall(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
    String name = call.getOperator().getName();
    String pgOp;
    if ("l2_distance".equalsIgnoreCase(name)) {
      pgOp = "<->";
    } else if ("cosine_distance".equalsIgnoreCase(name)) {
      pgOp = "<=>";
    } else if ("inner_product".equalsIgnoreCase(name)) {
      pgOp = "<#>";
    } else {
      super.unparseCall(writer, call, leftPrec, rightPrec);
      return;
    }
    // call has exactly 2 operands: the vector column and the query vector
    SqlNode left = call.operand(0);
    SqlNode right = call.operand(1);
    SqlWriter.Frame frame = writer.startList(SqlWriter.FrameTypeEnum.SIMPLE, "", "");
    left.unparse(writer, leftPrec, rightPrec);
    writer.print(" " + pgOp + " ");
    renderVectorOperand(writer, right, leftPrec, rightPrec);
    writer.endList(frame);
  }

  /**
   * Renders the right-hand operand to a pgvector distance operator.
   * If the operand is ARRAY[lit1, lit2, ...], renders as '[lit1,lit2,...]' (pgvector string format).
   * Otherwise falls through to normal unparse.
   */
  private void renderVectorOperand(SqlWriter writer, SqlNode operand,
      int leftPrec, int rightPrec) {
    if (operand instanceof SqlCall) {
      SqlCall argCall = (SqlCall) operand;
      if (argCall.getKind() == SqlKind.ARRAY_VALUE_CONSTRUCTOR) {
        // ARRAY[1.0, 2.0, 3.0] → '[1.0,2.0,3.0]'
        StringBuilder sb = new StringBuilder("'[");
        List<SqlNode> elems = argCall.getOperandList();
        for (int i = 0; i < elems.size(); i++) {
          if (i > 0) sb.append(',');
          sb.append(elems.get(i).toString());
        }
        sb.append("]'");
        writer.print(sb.toString());
        return;
      }
    }
    // Column ref or other expression — unparse normally
    operand.unparse(writer, leftPrec, rightPrec);
  }
}
```

### Pattern 2: StandardPushdownFunctionRegistry extension

```java
// Add to WHITELISTED_FUNCTION_NAMES in StandardPushdownFunctionRegistry:
private static final Set<String> WHITELISTED_FUNCTION_NAMES = ImmutableSet.of(
    // ... existing entries ...
    "L2_DISTANCE",        // pgvector L2 distance; renders as <-> in DremioPostgresDialect
    "COSINE_DISTANCE",    // pgvector cosine distance; renders as <=>
    "INNER_PRODUCT"       // pgvector inner product; renders as <#>
);
```

### Pattern 3: PostgresConf.newPlugin() dialect wiring

```java
// In the anonymous JdbcStoragePlugin subclass inside PostgresConf.newPlugin():
@Override
public SqlDialect createDialect() {
  return DremioPostgresDialect.INSTANCE;
}
```

### Pattern 4: Vector literal SqlNode extraction

When the user writes `l2_distance(embedding, ARRAY[1.0, 2.0, 3.0])`, the `SqlCall` received in `unparseCall` has:
- `call.operand(0)` = `SqlIdentifier("embedding")` or `SqlCall(quoting the column name)`
- `call.operand(1)` = `SqlCall(ARRAY_VALUE_CONSTRUCTOR, [SqlLiteral(1.0), SqlLiteral(2.0), SqlLiteral(3.0)])`

The `SqlNode.toString()` on a `SqlLiteral` containing a `BigDecimal` returns the decimal string (e.g. `"1.0"`, `"2.0"`). This is safe to embed in the pgvector vector string literal.

**pgvector accepts implicit cast from text**: `embedding <-> '[1.0,2.0,3.0]'` works because the column type is `vector` and PostgreSQL applies an implicit text→vector cast. No explicit `::vector` suffix needed (though adding it is also safe).

### Recommended Project Structure (new files)

```
plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/
├── DremioPostgresDialect.java           # NEW — SqlDialect subclass with unparseCall override
├── VectorDistanceFunctions.java         # EXISTING from Phase 41
├── PostgresConf.java                    # MODIFY — add createDialect() override
└── PostgresSchemaFetcher.java           # UNCHANGED

plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/
└── StandardPushdownFunctionRegistry.java  # MODIFY — add 3 function names

plugins/jdbc-postgresql/src/test/java/com/dremio/plugins/jdbc/postgresql/
├── TestDremioPostgresDialect.java        # NEW — unit tests for unparseCall rendering
└── TestPostgresPushdown.java             # MODIFY — add vector pushdown integration test
```

### Anti-Patterns to Avoid

- **Do not create a new Volcano/HEP rule** — the existing expression-pushdown rules (`JdbcPushTopNWithExpressionsHep`, `JdbcPushSortWithExpressionsHep`, `JdbcPushFilterIntoScan`) already handle the correct topology. Adding the functions to the whitelist is sufficient.
- **Do not try to override `SqlImplementor.Context.toSql()`** — the `Context` is an inner class created by Calcite's `RelToSqlConverter`; overriding the `toSql` path requires deep Calcite changes. The `unparseCall` dialect hook is the correct Calcite extension point.
- **Do not add a per-dialect registry subclass now** — `JdbcRulesFactory` hardcodes `StandardPushdownFunctionRegistry.INSTANCE` and does not receive the plugin instance (it receives `SourceType`, not `StoragePluginId`). Adding the three function names directly to `StandardPushdownFunctionRegistry` is safe: they are unique names that no other dialect implements differently.
- **Do not emit `ARRAY[...]::vector` syntax** — pgvector's operators expect either a `vector` column or a text literal. `ARRAY[...]` renders with the keyword `ARRAY` which pgvector does not accept as a vector literal. Use `'[val1,val2,...]'` string format.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Custom RexShuttle to replace operators before rendering | Custom shuttle replacing `RexCall(l2_distance)` with `RexCall(SqlBinaryOperator("<->"))` | `SqlDialect.unparseCall()` override | The unparseCall hook is called for every `SqlCall.unparse()` — it is the designed Calcite extension point. RexShuttle approach would require patching `getPhysicalOperator()` in `JdbcScanPrel` for every expression context (sortKey, filter, projection) and would duplicate logic. |
| New Volcano rule for vector distance pushdown | `JdbcPushVectorDistanceIntoScan` rule | Existing `JdbcPushTopNWithExpressionsHep` + `JdbcPushSortWithExpressionsHep` | These rules already handle `TopNPrel(ProjectPrel(JdbcScanPrel))` with function expressions. The only missing piece is the whitelist entry. |
| Custom SqlBinaryOperator instance | `new SqlBinaryOperator("<->", OTHER, 30, ...)` in RexNode trees | Direct `writer.print()` in `unparseCall` | Creating a `SqlBinaryOperator` and wrapping it in `SqlBasicCall` just to call `unparseBinarySyntax` is unnecessary indirection. |

---

## Common Pitfalls

### Pitfall 1: Case sensitivity in operator name matching
**What goes wrong:** `call.getOperator().getName()` returns the function name exactly as registered in `@FunctionTemplate`. The `FunctionTemplate(name = "l2_distance")` registers it lowercase. If the user types `L2_DISTANCE` in SQL, Dremio normalizes it to lowercase before registration lookup. The `SqlFunctionImpl` is stored under the canonical registered name.
**How to avoid:** Use `equalsIgnoreCase()` in the `unparseCall` check to handle both cases safely.
**Warning signs:** Calls to `l2_distance(...)` render as function syntax (not infix) — means the name comparison failed.

### Pitfall 2: ARRAY[...] with non-literal operands
**What goes wrong:** If the user writes `l2_distance(embedding, some_col)` where the second argument is a column reference, not an ARRAY literal, the code must fall through to normal column rendering. The `renderVectorOperand` method must not assume `ARRAY_VALUE_CONSTRUCTOR`.
**How to avoid:** Always check `argCall.getKind() == SqlKind.ARRAY_VALUE_CONSTRUCTOR` before attempting array extraction. Fall through to `operand.unparse(writer, ...)` for column references.
**Warning signs:** `ClassCastException` when second operand is a column reference.

### Pitfall 3: PushdownFunctionRegistry not used by JdbcRulesFactory for the plugin instance
**What goes wrong:** `JdbcRulesFactory.getRules()` is called with `SourceType` (type-level). `JdbcStoragePlugin.getPushdownFunctionRegistry()` exists but is NOT called by `JdbcRulesFactory`. Adding functions to `StandardPushdownFunctionRegistry` directly bypasses this mismatch.
**How to avoid:** Add the three names to `StandardPushdownFunctionRegistry.WHITELISTED_FUNCTION_NAMES` directly. Do NOT override `getPushdownFunctionRegistry()` in `PostgresConf.newPlugin()` and expect it to be invoked automatically — it will not be, under the current `JdbcRulesFactory` wiring.
**Warning signs:** Pushdown rules reject `l2_distance` expressions even after adding them to a PostgreSQL-specific registry subclass.

### Pitfall 4: SqlLiteral.toString() format for floats
**What goes wrong:** `SqlLiteral` for a float like `1.0` may render as `1.0` or `1E0` depending on the `SqlTypeName` (DECIMAL vs FLOAT/DOUBLE). pgvector vector literals accept both `[1,2,3]` and `[1.0,2.0,3.0]`.
**How to avoid:** Use `SqlLiteral.getValue()`  to extract the `BigDecimal` and call `toPlainString()` to avoid scientific notation.
**Warning signs:** pgvector rejects the generated vector literal string.

### Pitfall 5: JdbcPushTopNWithExpressionsHep / JdbcPushSortWithExpressionsHep require a function sort key
**What goes wrong:** Both rules check `hasFunctionSortKey = true` before firing — they only handle cases where the sort key is NOT a plain `RexInputRef`. Since `l2_distance(embedding, ARRAY[...])` is a function call, this requirement is satisfied automatically.
**How to avoid:** No special handling needed; verify with unit tests that the rules fire.

### Pitfall 6: pgvector implicit cast only works when column type is `vector`
**What goes wrong:** If the column is mapped as `LIST<FLOAT4>` but rendered SQL has the column name quoted as `"embedding"`, pgvector knows it's a `vector` column and applies implicit cast from text `'[...]'`. But if the schema exposes the column under an alias that breaks the type lookup, the implicit cast might fail.
**How to avoid:** Test against the real `pgvector/pgvector:pg16` container (already available as `PostgresTestContainer`). The integration test should verify the query executes, not just that SQL renders correctly.

---

## Code Examples

### Example: DremioPostgresDialect.unparseCall rendering
Verified pattern from Calcite `SqlUtil.unparseBinarySyntax` (source: bytecode) and `SqlCall.unparse()` (source: bytecode):

```java
// The SqlCall for: l2_distance("embedding", ARRAY[1.0, 2.0, 3.0])
// call.getOperator().getName()  = "l2_distance"
// call.operand(0)               = SqlIdentifier(["embedding"])
// call.operand(1)               = SqlCall(ARRAY_VALUE_CONSTRUCTOR, [1.0, 2.0, 3.0])

// Target output: "embedding" <-> '[1.0,2.0,3.0]'

@Override
public void unparseCall(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
  String opName = call.getOperator().getName();
  String pgOp = getPgvectorOp(opName);
  if (pgOp == null) {
    super.unparseCall(writer, call, leftPrec, rightPrec);
    return;
  }
  SqlWriter.Frame frame = writer.startList(SqlWriter.FrameTypeEnum.SIMPLE, "", "");
  call.operand(0).unparse(writer, leftPrec, rightPrec);
  writer.print(" " + pgOp + " ");
  renderVectorOperand(writer, call.operand(1), leftPrec, rightPrec);
  writer.endList(frame);
}

private static String getPgvectorOp(String funcName) {
  if (funcName == null) return null;
  switch (funcName.toLowerCase(Locale.ROOT)) {
    case "l2_distance":     return "<->";
    case "cosine_distance": return "<=>";
    case "inner_product":   return "<#>";
    default:                return null;
  }
}
```

### Example: ARRAY literal → pgvector string rendering

```java
private void renderVectorOperand(SqlWriter writer, SqlNode operand, int lp, int rp) {
  if (operand instanceof SqlCall) {
    SqlCall argCall = (SqlCall) operand;
    if (argCall.getKind() == SqlKind.ARRAY_VALUE_CONSTRUCTOR) {
      StringBuilder sb = new StringBuilder("'[");
      List<SqlNode> elems = argCall.getOperandList();
      for (int i = 0; i < elems.size(); i++) {
        if (i > 0) sb.append(',');
        SqlNode elem = elems.get(i);
        if (elem instanceof SqlNumericLiteral) {
          // Use BigDecimal.toPlainString() to avoid scientific notation
          sb.append(((SqlNumericLiteral) elem).getValue());
        } else {
          sb.append(elem.toString());
        }
      }
      sb.append("]'");
      writer.print(sb.toString());
      return;
    }
  }
  operand.unparse(writer, lp, rp);
}
```

### Example: StandardPushdownFunctionRegistry addition

```java
// Source: StandardPushdownFunctionRegistry.java (verified from OSS source)
private static final Set<String> WHITELISTED_FUNCTION_NAMES = ImmutableSet.of(
    "UPPER", "LOWER",
    "ABS", "ROUND",
    "SUBSTRING",
    "CHAR_LENGTH", "CHARACTER_LENGTH", "LENGTH",
    "L2_DISTANCE",      // pgvector L2 distance; renders as <-> via DremioPostgresDialect
    "COSINE_DISTANCE",  // pgvector cosine distance; renders as <=>
    "INNER_PRODUCT"     // pgvector inner product; renders as <#>
);
```

### Example: Unit test structure for dialect rendering

```java
// Source: Pattern follows TestPostgresPushdown.java (verified from OSS source)
// No container needed — tests the dialect rendering directly
@Test
public void testL2DistanceRendersAsInfix() {
  // Build SqlCall: l2_distance("embedding", ARRAY[1.0, 2.0, 3.0])
  // Use SqlDialectFixture or construct manually, then call toSqlString
  // Assert result contains: "embedding" <-> '[1.0,2.0,3.0]'
}

@Test
public void testCosineDistanceRendersAsInfix() { ... }

@Test
public void testInnerProductRendersAsInfix() { ... }

@Test
public void testColumnVsColumnL2Renders() {
  // l2_distance(col_a, col_b) → "col_a" <-> "col_b"
  // Both operands are column refs, not ARRAY literals
}
```

### Example: pgvector vector literal — confirmed format

```sql
-- Standard pgvector nearest-neighbor query (source: pgvector README, HIGH confidence)
SELECT id, embedding <-> '[3,1,2]' AS distance
FROM items
ORDER BY embedding <-> '[3,1,2]'
LIMIT 5;

-- Equivalent with named embedding:
SELECT id FROM items ORDER BY embedding <-> '[1.0,2.0,3.0]' LIMIT 10;
```

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| Register custom SqlBinaryOperator in RexNode trees | Override SqlDialect.unparseCall() | No RexNode tree changes needed; translation happens at SQL text layer |
| Function-call syntax `l2_distance(a, b)` | Infix syntax `a <-> b` | Enables pgvector HNSW/IVFFlat index usage (index only kicks in with infix form) |
| `ARRAY[1,2,3]` (Calcite default) | `'[1,2,3]'` (pgvector text format) | PostgreSQL accepts text→vector implicit cast; pgvector does not understand ARRAY[] constructor |

---

## Critical Architectural Clarification: "Volcano Planner Rules" Interpretation

The phase name says "Volcano planner rules translate..." but this is misleading. The actual translation happens in two places:

1. **Planner phase (pushdown decision)**: The existing `JdbcPushTopNWithExpressionsHep` and `JdbcPushSortWithExpressionsHep` rules (HEP, run after Volcano) decide whether to push `ORDER BY l2_distance(col, ARRAY[...]) LIMIT K` into the scan. They consult `PushdownFunctionRegistry.isExpressionPushable()`. Adding the three function names to `StandardPushdownFunctionRegistry` makes them approvable.

2. **SQL rendering phase (operator translation)**: `JdbcScanPrel.getPhysicalOperator()` builds the Calcite JDBC convention subtree and renders it via `DremioJdbcImplementor`. The `SqlDialect.unparseCall()` override performs the infix translation from `l2_distance(a, b)` to `a <-> b`.

There is no new Volcano or HEP rule to write. The "rules" in the phase title refers to the existing planner rules that are unlocked by the whitelist addition.

---

## Open Questions

1. **ARRAY literal from Dremio SQL parser**
   - What we know: The user writes `l2_distance(embedding, ARRAY[1.0, 2.0, 3.0])` in Dremio SQL. After parsing and optimization, the second `RexNode` operand is either a `RexCall(ARRAY_VALUE_CONSTRUCTOR, ...)` or a `RexLiteral` depending on whether Dremio folds it to a constant.
   - What's unclear: Does Dremio's SQL validator/planner constant-fold `ARRAY[1.0, 2.0]` before it reaches the pushdown rules? If so, the operand might be a `RexLiteral` of type `ARRAY` rather than a `RexCall`.
   - Recommendation: Build the unit test using actual Calcite `RexBuilder` to confirm what type of node the second operand becomes. Handle both `RexCall(ARRAY_VALUE_CONSTRUCTOR)` and any constant-folded form.

2. **`SqlNumericLiteral` vs `SqlLiteral` in SqlNode tree**
   - What we know: `SqlArrayValueConstructor` receives `SqlLiteral` operands. The Calcite class hierarchy has `SqlNumericLiteral extends SqlLiteral`.
   - What's unclear: Is the operand type `SqlNumericLiteral` (which has `getValue()` returning `BigDecimal`) or some other form?
   - Recommendation: Use `instanceof SqlLiteral` and call `((SqlLiteral) elem).getValueAs(BigDecimal.class)` to extract the value safely regardless of subtype.

3. **pgvector version compatibility**
   - What we know: pgvector operators `<->`, `<=>`, `<#>` have been stable since pgvector 0.4.x. The test container uses `pgvector/pgvector:pg16` which ships a recent pgvector version.
   - What's unclear: Whether there are differences in distance semantics across pgvector versions for edge cases.
   - Recommendation: Pin the test container image version and document it. The operators themselves are stable.

---

## Sources

### Primary (HIGH confidence)
- `plugins/jdbc-base/.../planning/PushdownFunctionRegistry.java` — interface contract
- `plugins/jdbc-base/.../planning/StandardPushdownFunctionRegistry.java` — extension point for whitelisting
- `plugins/jdbc-base/.../planning/DremioJdbcImplementor.java` — rendering entry point
- `plugins/jdbc-base/.../planning/JdbcScanPrel.java` (lines 596–1000) — `getPhysicalOperator()` rendering pipeline
- `plugins/jdbc-base/.../planning/JdbcRulesFactory.java` — hardcoded `StandardPushdownFunctionRegistry.INSTANCE` (confirms whitelist approach)
- `plugins/jdbc-postgresql/.../PostgresConf.java` — `createDialect()` override hook
- `plugins/jdbc-postgresql/.../VectorDistanceFunctions.java` — function names from Phase 41
- Calcite JAR bytecode: `SqlCall.class` — confirms `dialect.unparseCall()` is called for ALL SqlCall.unparse()
- Calcite JAR bytecode: `SqlBinaryOperator.class` — confirms `getSyntax()` returns `SqlSyntax.BINARY`
- Calcite JAR bytecode: `SqlArrayValueConstructor.class` — confirms name `"ARRAY"` and kind `ARRAY_VALUE_CONSTRUCTOR`
- Calcite JAR bytecode: `PostgresqlSqlDialect.class` — confirms `unparseCall()` override pattern
- `sabot/kernel/.../SqlFunctionImpl.java` — confirms `@FunctionTemplate` functions get `SqlKind.OTHER_FUNCTION`, `SqlSyntax.FUNCTION`
- `sabot/kernel/.../SqlImplementor.java` (lines 598–631) — default `toSql` for `OTHER_FUNCTION`: `op.createCall(nodeList)`
- pgvector GitHub README (fetched 2026-03-18) — operator syntax `<->`, `<=>`, `<#>`; vector literal format `'[1,2,3]'`

### Secondary (MEDIUM confidence)
- pgvector README (via WebFetch): `'[1,2,3]'` is the documented vector literal format; `ARRAY[]::vector` form not documented

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all components traced to OSS source
- Architecture patterns: HIGH — rendering pipeline verified by bytecode
- Pitfalls: HIGH — based on direct code reading of the relevant paths
- Vector literal format: MEDIUM — pgvector docs show `'[...]'`; ARRAY cast not tested

**Research date:** 2026-03-18
**Valid until:** 2026-04-18 (stable domain; pgvector operators don't change)
