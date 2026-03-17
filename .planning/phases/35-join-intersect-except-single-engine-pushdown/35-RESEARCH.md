# Phase 35: JOIN/INTERSECT/EXCEPT Single-Engine Pushdown + ADBC COPY Optimization - Research

**Researched:** 2026-03-14
**Domain:** Dremio JDBC plugin planner rules — JOIN pushdown, INTERSECT/EXCEPT SQL generation, ADBC inline literal escaping
**Confidence:** HIGH

## Summary

This phase has two distinct workstreams: (1) pushing JOIN operations to the JDBC source when all referenced tables share the same plugin instance, and (2) inlining BindParam literal values directly into SQL strings for ADBC mode so the PostgreSQL ADBC driver uses its fast COPY binary protocol rather than the Extended Query Protocol.

**Critical architectural finding:** Dremio's logical planner converts INTERSECT to `INTERSECT DISTINCT` via `CoreRules.INTERSECT_TO_DISTINCT` (which rewrites it as join + aggregate), and converts EXCEPT/MINUS via `MinusToJoin.RULE` (which rewrites it as aggregate + left join + filter). Both rewrites happen in `getPreLogicalCommonRules()` at the pre-logical phase, meaning by the time physical rules fire there are **no INTERSECT or MINUS nodes remaining in the plan tree** — only JoinRel/AggregatePrel/FilterPrel combinations. This means INTERSECT and EXCEPT pushdown IS actually JOIN pushdown: if the planner can push the join(s) produced by those rewrites, the SQL emitted to the JDBC source will contain native `JOIN` SQL, not `INTERSECT`/`EXCEPT` keywords. However, it is architecturally simpler and semantically cleaner to intercept at the **pre-logical** level (before the rewrites fire) using a new rule registered in `LOGICAL` phase that matches the `Intersect`/`Minus` calcite rel nodes directly, and generates a single `INTERSECT`/`EXCEPT` SQL subquery to the JDBC source. Alternatively, we can generate `INTERSECT` and `EXCEPT` SQL by matching the join patterns that the rewrites produce, but that is fragile. The recommended approach is a new **logical-phase rule** that fires BEFORE `INTERSECT_TO_DISTINCT` and `MinusToJoin`.

For JOIN pushdown, the rule must fire at the logical level (matching `JoinRel` above two `JdbcScanDrel` children with the same `pluginId`) or at the physical level (matching `HashJoinPrel`/`MergeJoinPrel`/`NestedLoopJoinPrel` above two `JdbcScanPrel` children with the same `pluginId`). The logical-level approach is simpler and cleaner.

For the ADBC COPY optimization, the change is in `AdbcRecordReader.setup()`: instead of translating `?` to `$N` and building a VectorSchemaRoot for bind, it inlines BindParam values as properly-escaped SQL literals, then executes without any bind parameters. This causes the ADBC PG driver to use the simple query protocol (which routes through COPY binary), not the Extended Query Protocol (which COPY cannot use).

**Primary recommendation:** Implement a new `JdbcPushJoinIntoScan` logical rule (matching `JoinRel(JdbcScanDrel, JdbcScanDrel)` same-pluginId), new `JdbcPushSetOpIntoScan` logical rule (matching Calcite `Intersect`/`Minus` above two `JdbcScanDrel` same-pluginId), a new `JdbcJoinScanPrel` physical node that emits multi-table SQL, and a `LiteralInliner` utility in `SqlBuilder` for ADBC mode.

## Phase Requirements

<phase_requirements>

| ID | Description | Research Support |
|----|-------------|-----------------|
| SETOP-01 | Push JOIN, INTERSECT, EXCEPT to JDBC source when all referenced tables share same plugin instance | Confirmed via JoinRel/Intersect/Minus class inspection; needs new logical rules + JdbcJoinScanPrel |
| ADBC-02 | Inline bind parameter literals into SQL string for ADBC execution to enable COPY binary protocol | Confirmed via AdbcRecordReader.setup() analysis; needs LiteralInliner + mode-switch in setup() |

</phase_requirements>

## Critical Architectural Finding: INTERSECT/EXCEPT Transformation

**INTERSECT** is rewritten by `CoreRules.INTERSECT_TO_DISTINCT` (Calcite built-in) before logical phase completes. The Calcite `Intersect` node (`org.apache.calcite.rel.core.Intersect`) exists in the initial plan produced by the SQL parser/validator, then is eliminated by this rule into a `DISTINCT` aggregate + join structure.

**EXCEPT/MINUS** is rewritten by `MinusToJoin.RULE` (Dremio custom, in `com.dremio.exec.planner.logical.rule.MinusToJoin`). The Calcite `Minus` node (`org.apache.calcite.rel.core.Minus`) similarly exists only until the pre-logical rules fire.

Both rules are in `getPreLogicalCommonRules()` at line 663-664 of `PlannerPhase.java`.

**Consequence:** To intercept before these rewrites, SETOP pushdown rules must be registered in the **LOGICAL phase** and must be registered BEFORE `INTERSECT_TO_DISTINCT` and `MinusToJoin`. In Dremio's architecture, `JdbcRulesFactory.getRules(LOGICAL)` is called when the LOGICAL rule set is assembled. However `getPreLogicalCommonRules()` fires separately. We need to understand whether plugin rules can fire before these builtin rules.

**Simpler alternative:** Generate `INTERSECT`/`EXCEPT` SQL from within the JOIN pushdown rule. When the JoinRel pattern that `INTERSECT_TO_DISTINCT` produces is detected (join + aggregate + distinct structure), emit native `INTERSECT` SQL to the JDBC source. This is feasible but complex to detect reliably. The cleanest approach is:

**Recommended approach: Two separate rule classes**

1. `JdbcPushJoinIntoScan` — matches `JoinRel` above two same-pluginId `JdbcScanDrel` children. Registered in LOGICAL phase. Emits a single SQL JOIN query.
2. `JdbcPushSetOpIntoScan` — matches `Intersect`/`Minus` above two same-pluginId `JdbcScanDrel` children. Registered in LOGICAL phase. Emits `INTERSECT`/`EXCEPT` SQL.

Both rules produce a `JdbcJoinScanPrel` (or `JdbcScanPrel` with a new `rawSql` field) at the physical level. The new physical node contains a pre-built SQL string and a merged output schema.

**Rule ordering:** In Dremio, plugin LOGICAL rules fire via Volcano optimizer. The pre-logical rules fire in a HEP planner BEFORE the Volcano LOGICAL phase. So `JdbcScanDrel` nodes (produced by `JdbcScanDrule`) only exist after the pre-logical HEP phase, which means `INTERSECT_TO_DISTINCT` and `MinusToJoin` have already fired. Therefore, SETOP pushdown via `JdbcScanDrel` children **cannot intercept before the rewrite**. The ONLY window to intercept is at the `ScanCrel` level — before `JdbcScanDrule` fires — but that requires pre-logical HEP registration that JDBC plugins cannot do.

**Actual conclusion:** INTERSECT and EXCEPT SQL keywords CANNOT be used in pushdown because those nodes are already rewritten before our rules can see `JdbcScanDrel` children. JOIN pushdown is feasible. To get INTERSECT/EXCEPT SQL, we would need to detect the Dremio Intersect/Minus nodes BEFORE they are rewritten — meaning at the pre-logical HEP phase, which plugin rules cannot participate in without kernel changes.

**SETOP-01 re-scoping:** Given the above, SETOP-01 must be understood as **JOIN pushdown** only (plus the joins that result from INTERSECT/EXCEPT rewrites being pushed if they match same-source JdbcScanPrel). The requirement says "INTERSECT between tables on the same JDBC source is pushed down (source computes set intersection)" — this can be satisfied by pushing the JOIN that INTERSECT produces. The user sees the same semantic result; only the SQL sent to the database uses JOIN instead of INTERSECT.

## Standard Stack

### Core (all pre-existing in codebase)

| Class | Version | Purpose | Why Standard |
|-------|---------|---------|--------------|
| `JdbcScanPrel` | existing | Physical scan node | All JDBC physical plans target this |
| `JdbcScanDrel` | existing | Logical scan node | Starting point for pushdown rule matching |
| `JdbcRulesFactory` | existing | Rule registration | Single registration point for all JDBC rules |
| `SqlBuilder` | existing | SQL string assembly | All SQL generation goes through here |
| `BindParam` | existing | Bind parameter model | Jackson-serializable, used in JDBC + ADBC path |
| `JdbcGroupScan` / `JdbcSubScan` | existing | Physical operator transport | Used by all existing JDBC scans |
| `AdbcRecordReader` | existing | ADBC execution path | Only place where ADBC mode is activated |
| `StoragePluginId.getName()` | existing | Source identity string | Used for same-source guard |
| `JoinRel` | kernel | Logical join (Dremio) | Only visible after pre-logical rewrites |
| `HashJoinPrel` / `MergeJoinPrel` | kernel | Physical join nodes | Match these for PHYSICAL-phase rules |

### New Classes to Create

| Class | Location | Purpose |
|-------|----------|---------|
| `JdbcJoinScanPrel` | `plugins/jdbc-base/.../planning/` | Physical node for multi-table JOIN SQL; extends `ScanPrelBase` or is standalone `Prel`; holds pre-built SQL + merged schema + pluginId + bindParams |
| `JdbcPushJoinIntoScan` | `plugins/jdbc-base/.../planning/` | Logical-level rule matching `JoinRel(JdbcScanDrel, JdbcScanDrel)` same-pluginId |
| `LiteralInliner` | `plugins/jdbc-base/.../planning/` | Converts `List<BindParam>` + SQL-with-`?` into SQL-with-inlined-escaped-literals |

## Architecture Patterns

### Pattern 1: New Physical Node for JOIN SQL

The existing `JdbcScanPrel` is tightly coupled to a single table (has `schemaName`, `tableName` fields with `Preconditions.checkNotNull`). A JOIN query spanning two tables cannot be expressed within the existing node structure without invasive changes.

**Recommended:** Create `JdbcJoinScanPrel` as a new physical node that:
- Implements `Prel` and `LeafPrel` (no child inputs needed — it is a leaf)
- Holds: `String sql` (pre-built JOIN SQL), `List<BindParam> bindParams`, `StoragePluginId pluginId`, `BatchSchema outputSchema`, `List<SchemaPath> projectedColumns`
- `getPhysicalOperator()` creates `JdbcGroupScan` with the pre-built SQL (exactly like `JdbcScanPrel` does)
- `getMaxParallelizationWidth()` returns 1 (same as `JdbcScanPrel`)

```java
// Source: codebase analysis of JdbcScanPrel.getPhysicalOperator()
public class JdbcJoinScanPrel extends AbstractRelNode implements LeafPrel {
  private final String sql;
  private final List<BindParam> bindParams;
  private final StoragePluginId pluginId;
  private final BatchSchema outputSchema;
  private final List<SchemaPath> projectedColumns;

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    return new JdbcGroupScan(
        creator.props(this, "anonymous", outputSchema),
        sql, projectedColumns, outputSchema, pluginId,
        Collections.emptyList(), bindParams);
  }
}
```

**Alternative (simpler if sufficient):** Add a `rawSql` field to the existing `JdbcScanPrel` that, when non-null, bypasses the `SqlBuilder.buildSql()` call entirely. This avoids a new class but pollutes `JdbcScanPrel` with special-case logic. The separate class is cleaner.

### Pattern 2: JOIN Rule at Logical Level

Match `JoinRel` above two `JdbcScanDrel` children where `left.getPluginId().getName().equals(right.getPluginId().getName())`.

```java
// Pattern in JdbcPushJoinIntoScan
super(RelOptHelper.some(JoinRel.class,
      RelOptHelper.any(JdbcScanDrel.class),
      RelOptHelper.any(JdbcScanDrel.class)),
      "JdbcPushJoinIntoScan");
```

The rule must:
1. Verify both scans share the same pluginId name
2. Translate the join condition `RexNode` to SQL (both sides' columns need table-aliased references because both tables appear in the FROM clause)
3. Build: `SELECT [projected cols] FROM "schema1"."table1" t1 [JOIN_TYPE] JOIN "schema2"."table2" t2 ON t1."col" = t2."col" [WHERE ...] [LIMIT ...]`
4. Produce a `JdbcJoinScanPrel` with the assembled SQL

**Join condition translation:** The join condition uses `RexInputRef` with indices 0..N-1 (left table columns) and N..N+M-1 (right table columns). The existing `RexToSqlString` only handles single-table `rowType`. For join conditions, column references need to be aliased (`t1."col"` vs `t2."col"`). A new `RexToJoinSqlString` or extended version is needed.

### Pattern 3: ADBC Literal Inlining

The ADBC PG driver uses `COPY` binary format (fast path) when the query goes through the **simple query protocol**. When bind parameters are used via `stmt.bind()`, the driver uses the **Extended Query Protocol** (parse/bind/execute cycle), which COPY cannot use.

**To force COPY:** Execute the SQL without any `$N` placeholders, with all literal values inlined as properly escaped SQL strings. The `AdbcRecordReader.setup()` currently:
1. Translates `?` → `$N`
2. Calls `stmt.bind(bindRoot)`
3. Calls `stmt.executeQuery()`

**ADBC-02 change:** When `protocolMode == ADBC`, instead:
1. Inline all BindParam values into the SQL string (replacing `?` with escaped literals)
2. Call `stmt.setSqlQuery(inlinedSql)` (no bind)
3. Call `stmt.executeQuery()`

The `LiteralInliner` must:
- Replace each `?` placeholder in order with the corresponding `BindParam` value
- Use proper SQL escaping:
  - VARCHAR/CHAR: single-quote wrap with doubling of internal single quotes (`'` → `''`) and backslash handling
  - Numbers (INTEGER, BIGINT, FLOAT, DOUBLE, DECIMAL): raw numeric string, no quotes
  - BOOLEAN: `TRUE` / `FALSE`
  - DATE: `DATE 'YYYY-MM-DD'` format
  - TIME: `TIME 'HH:mm:ss.SSS'` format
  - TIMESTAMP: `TIMESTAMP 'YYYY-MM-DD HH:mm:ss.SSS'` format
  - NULL: `NULL` keyword

**SQL injection safety:** All string values come from `RexLiteral` nodes controlled by the Calcite planner (not user input). However the spec requires bulletproof escaping. The escaping rules for PostgreSQL single-quoted strings:
- Escape single quote: `'` → `''`
- Backslash in standard SQL mode is NOT special (only special in `E''` strings)
- Using standard `'...'` quoting with `'` → `''` is sufficient and safe

```java
// Source: PostgreSQL documentation + SQL standard
public static String inlineParams(String sql, List<BindParam> params) {
  // Replace each ? with the corresponding param's inlined representation
  int paramIdx = 0;
  StringBuilder sb = new StringBuilder();
  for (int i = 0; i < sql.length(); i++) {
    char c = sql.charAt(i);
    if (c == '?' && paramIdx < params.size()) {
      sb.append(formatLiteral(params.get(paramIdx++)));
    } else {
      sb.append(c);
    }
  }
  return sb.toString();
}

private static String formatLiteral(BindParam p) {
  if (p.getValue() == null) return "NULL";
  switch (p.getTypeName()) {
    case VARCHAR: case CHAR:
      // Escape single quotes only; backslash is safe in standard quoting
      String escaped = p.getValue().toString().replace("'", "''");
      return "'" + escaped + "'";
    case TINYINT: case SMALLINT: case INTEGER: case BIGINT:
    case FLOAT: case REAL: case DOUBLE: case DECIMAL:
      return p.getValue().toString(); // plain number, no injection risk
    case BOOLEAN:
      return ((Boolean) p.getValue()) ? "TRUE" : "FALSE";
    case DATE:
      // Stored as epoch millis (Long)
      java.sql.Date d = new java.sql.Date(((Number) p.getValue()).longValue());
      return "DATE '" + d.toString() + "'"; // yields YYYY-MM-DD
    case TIME:
      java.sql.Time t = new java.sql.Time(((Number) p.getValue()).longValue());
      return "TIME '" + t.toString() + "'";
    case TIMESTAMP:
      java.sql.Timestamp ts = new java.sql.Timestamp(((Number) p.getValue()).longValue());
      return "TIMESTAMP '" + ts.toString() + "'";
    default:
      String s = p.getValue().toString().replace("'", "''");
      return "'" + s + "'";
  }
}
```

### Pattern 4: Same-Source Guard

Compare `StoragePluginId.getName()` (returns `SourceConfig.getName()` — the user-visible source name, globally unique in Dremio catalog). Two `JdbcScanDrel`/`JdbcScanPrel` nodes with the same plugin name are guaranteed to be on the same JDBC source instance.

```java
// Source: StoragePluginId.getName() returns config.getName()
private static boolean sameSource(JdbcScanDrel left, JdbcScanDrel right) {
  return left.getPluginId().getName().equals(right.getPluginId().getName());
}
```

### Pattern 5: JOIN SQL Generation

The generated SQL for a JOIN must use table aliases to avoid ambiguous column references:

```sql
SELECT "t1"."col_a", "t2"."col_b"
FROM "schema1"."table1" AS "t1"
INNER JOIN "schema2"."table2" AS "t2"
ON "t1"."join_col" = "t2"."join_col"
WHERE "t1"."filter_col" > ?
```

**Join type mapping:**
- `JoinRelType.INNER` → `INNER JOIN`
- `JoinRelType.LEFT` → `LEFT OUTER JOIN`
- `JoinRelType.RIGHT` → `RIGHT OUTER JOIN`
- `JoinRelType.FULL` → `FULL OUTER JOIN`

**Column index mapping in join condition:** In a `JoinRel` condition, `RexInputRef(i)` with `i < leftRowType.getFieldCount()` refers to the left table; `i >= leftRowType.getFieldCount()` refers to the right table. The right table index offset is `leftRowType.getFieldCount()`.

### Anti-Patterns to Avoid

- **Using `JdbcScanPrel.cloneWithXxx()` to carry the join SQL**: These methods are designed for single-table pushdown. The join SQL cannot be expressed via existing clone methods.
- **Trying to intercept INTERSECT/EXCEPT at physical level**: By the time physical rules fire, Calcite `Intersect`/`Minus` nodes are fully rewritten to joins+aggregates. Match must happen earlier.
- **Matching all JoinPrel subclasses individually**: The physical JOIN rule needs to match `HashJoinPrel`, `MergeJoinPrel`, AND `NestedLoopJoinPrel`. A logical-level rule on `JoinRel` handles all three with one rule.
- **Checking `StoragePluginId.equals()`**: The `equals()` method compares the full `SourceConfig` and `capabilities` objects (hash includes both). Safer to use `.getName().equals()` for source identity since `SourceConfig.getName()` is the catalog-unique source name.
- **Inlining literals for JDBC mode**: The `LiteralInliner` must ONLY be used in ADBC mode. JDBC mode must continue using `PreparedStatement` bind parameters for correctness and SQL injection safety.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Join condition RexNode → SQL | Custom recursive translator | Extend existing `RexToSqlString` with a table-alias-aware subclass | `RexToSqlString` already handles all operator types; just add alias-prefix to `convertInputRef()` |
| SQL identifier quoting | Custom escaping | `SqlBuilder.quoteIdentifier()` | Already handles double-quote escaping correctly |
| Same-source detection | Custom plugin comparison | `StoragePluginId.getName().equals()` | Globally unique catalog name is the right identity |
| Physical operator creation | Bespoke operator | `JdbcGroupScan` (existing) | All JDBC physical execution goes through `JdbcGroupScan` → `JdbcSubScan` → `JdbcRecordReader` or `AdbcRecordReader` |
| ADBC connection management | New connection handling | `AdbcConnectionFactory` (existing) | Already handles semaphore-bounded connections |

**Key insight:** The entire JDBC execution path (`JdbcGroupScan` → `JdbcSubScan` → `AdbcRecordReader`) already accepts arbitrary SQL strings. JOIN SQL works with zero changes in the execution layer — only the SQL generation and physical node representation need new code.

## Common Pitfalls

### Pitfall 1: INTERSECT/EXCEPT Already Rewritten
**What goes wrong:** Writing a rule that matches `Intersect` or `Minus` above `JdbcScanDrel` and registering it in the LOGICAL phase — the rule never fires because those nodes no longer exist.
**Why it happens:** `CoreRules.INTERSECT_TO_DISTINCT` and `MinusToJoin.RULE` are in `getPreLogicalCommonRules()` which runs as a HEP planner phase BEFORE the Volcano LOGICAL phase where plugin rules fire.
**How to avoid:** Scope SETOP pushdown to JOIN pushdown only. Document clearly that when a user writes `A INTERSECT B` over same-source tables, the planner rewrites it to a join, and that join gets pushed — giving correct semantics, not using INTERSECT SQL keywords.
**Warning signs:** Rule fires for Parquet scans but never for JDBC scans; inspection shows rule matched 0 times.

### Pitfall 2: Column Reference Ambiguity in JOIN SQL
**What goes wrong:** SQL like `SELECT "col" FROM "s1"."t1" INNER JOIN "s2"."t2" ON "col" = "col"` causes `ERROR: column reference "col" is ambiguous`.
**Why it happens:** Without table aliases, column references in SELECT, ON clause, and WHERE clause are ambiguous when both tables share column names (which is common in join keys).
**How to avoid:** Always use table aliases (`t1`, `t2`) in all column references. Alias the tables themselves as `"schema"."table" AS "t1"`.
**Warning signs:** Integration tests fail with "ambiguous column reference" errors.

### Pitfall 3: RexInputRef Index Offset for Right Table
**What goes wrong:** Translating `RexInputRef(N+k)` as column `k` from the left table instead of column `k` from the right table.
**Why it happens:** In a join's row type, the right table's fields start at index `leftRowType.getFieldCount()`. A `RexInputRef(leftCount + k)` is the k-th column of the right table.
**How to avoid:** In `RexToJoinSqlString`, track `leftFieldCount`. For index `< leftFieldCount`, prefix with `"t1"`. For index `>= leftFieldCount`, prefix with `"t2"`, using `index - leftFieldCount`.
**Warning signs:** Tests pass for symmetrical joins (same column name positions) but fail for cross-type comparisons.

### Pitfall 4: ADBC Inline Escaping Breaks Backslash
**What goes wrong:** Escaping backslash in string values with `\\` when the SQL uses standard quoting mode. PostgreSQL's standard `'...'` strings do NOT treat `\` as an escape character (unless `standard_conforming_strings = off`, which is ON by default since PostgreSQL 9.1). Escaping `\` with `\\` would double the backslashes.
**Why it happens:** Developer confuses PostgreSQL's `E'...'` escape-string syntax with standard string literals.
**How to avoid:** Use only `'` → `''` for string escaping. Do NOT escape backslashes. Test with strings containing `\` to verify they round-trip correctly.
**Warning signs:** Strings with `\` fail to round-trip; `\\` appears in output where `\` was expected.

### Pitfall 5: Inlining Literals in JDBC Mode
**What goes wrong:** Accidentally using `LiteralInliner` for JDBC-mode execution, causing performance regression (no PreparedStatement caching) and defeating the purpose of bind parameters.
**Why it happens:** `LiteralInliner` is called from a shared code path.
**How to avoid:** Add a mode guard in `AdbcRecordReader.setup()`. Only call `LiteralInliner` when `bindParams` is non-empty AND the current mode is ADBC. `JdbcRecordReader` must never see `LiteralInliner`.
**Warning signs:** JDBC mode tests show slower performance or SQL injection test failures.

### Pitfall 6: JOIN Pushdown with Existing Single-Table Pushdowns
**What goes wrong:** A JOIN between two `JdbcScanPrel` nodes where one already has a pushed-down WHERE clause — the WHERE clause expressions use `RexInputRef` indices relative to the single table's row type, but in the join SQL context the column needs a table alias prefix.
**Why it happens:** The WHERE clause string was computed as `"col" = ?` for the single-table case. When embedded in a JOIN context it becomes ambiguous.
**How to avoid:** When building JOIN SQL from a rule that matches `JoinRel(JdbcScanDrel, JdbcScanDrel)` (logical level), the scans don't yet have WHERE clauses (those are added in the PHYSICAL phase). The logical-level join rule fires BEFORE filter pushdown, so the `JdbcScanDrel` children don't carry WHERE clauses. This is actually the right behavior — the JOIN SQL contains no WHERE, and filter pushdown rules will not fire on the `JdbcJoinScanPrel` (since those rules match `FilterPrel above JdbcScanPrel`, not `FilterPrel above JdbcJoinScanPrel`). A separate plan may be needed to allow filter pushdown into the join scan (deferred to v2).
**Warning signs:** Tests with WHERE + JOIN show the WHERE being evaluated in Dremio instead of pushed to the source.

### Pitfall 7: Schema for JdbcJoinScanPrel
**What goes wrong:** `getPhysicalOperator()` needs a `BatchSchema` for the output, but `JdbcJoinScanPrel` is a new node without a single `TableMetadata` to derive the schema from.
**Why it happens:** Existing scans derive schema from `TableMetadata.getSchema()`. A join scan's schema is the merged output of two tables.
**How to avoid:** At rule-fire time, derive the merged schema from the two `JdbcScanDrel` children's row types by converting the Calcite `RelDataType` to `BatchSchema` using `CalciteArrowHelper.fromCalciteRowType(joinRowType)`. Store this pre-computed schema in the `JdbcJoinScanPrel` constructor.
**Warning signs:** `NullPointerException` in `getPhysicalOperator()` when trying to access schema.

## Code Examples

### Checking Same-Source for JOIN Rule

```java
// Source: codebase analysis of StoragePluginId + JdbcScanDrel
@Override
public boolean matches(RelOptRuleCall call) {
  JoinRel join = call.rel(0);
  JdbcScanDrel left = call.rel(1);
  JdbcScanDrel right = call.rel(2);
  // Same source name means same physical JDBC connection pool
  return left.getPluginId().getName().equals(right.getPluginId().getName());
}
```

### Column Reference Translation for JOIN Condition

```java
// Extend RexToSqlString with alias awareness
public final class RexToJoinSqlString extends RexToSqlString {
  private final int leftFieldCount;
  private final String leftAlias;
  private final String rightAlias;

  // Override convertInputRef to prefix with table alias
  @Override
  protected RexToSqlResult convertInputRefByIndex(int index, RelDataType rowType) {
    String alias;
    int tableRelativeIndex;
    if (index < leftFieldCount) {
      alias = leftAlias; // e.g. "t1"
      tableRelativeIndex = index;
    } else {
      alias = rightAlias; // e.g. "t2"
      tableRelativeIndex = index - leftFieldCount;
    }
    // Get field name from join rowType (not single-table rowType)
    String fieldName = rowType.getFieldList().get(index).getName();
    // Use alias."fieldName" instead of just "fieldName"
    String quotedAlias = "\"" + alias.replace("\"", "\"\"") + "\"";
    String quotedField = "\"" + fieldName.replace("\"", "\"\"") + "\"";
    return RexToSqlResult.literal(quotedAlias + "." + quotedField);
  }
}
```

Note: `RexToSqlString.convertInputRef` is currently `private`. The subclass may need to change the parent's method to `protected` OR duplicate the lookup logic.

### LiteralInliner for ADBC Mode

```java
// Source: BindParam contract + PostgreSQL string literal rules
public static String inlineBindParams(String sql, List<BindParam> params) {
  if (params == null || params.isEmpty()) return sql;
  StringBuilder sb = new StringBuilder(sql.length());
  int paramIdx = 0;
  for (int i = 0; i < sql.length(); i++) {
    char c = sql.charAt(i);
    if (c == '?' && paramIdx < params.size()) {
      sb.append(toSqlLiteral(params.get(paramIdx++)));
    } else {
      sb.append(c);
    }
  }
  return sb.toString();
}

private static String toSqlLiteral(BindParam p) {
  if (p.getValue() == null) return "NULL";
  switch (p.getTypeName()) {
    case TINYINT: case SMALLINT: case INTEGER: case BIGINT:
    case FLOAT: case REAL: case DOUBLE:
      return p.getValue().toString();
    case DECIMAL: {
      // BigDecimal.toString() gives safe numeric representation
      return p.getValue().toString();
    }
    case VARCHAR: case CHAR: {
      // Standard SQL quoting: escape ' → '' only. No backslash escaping.
      String escaped = p.getValue().toString().replace("'", "''");
      return "'" + escaped + "'";
    }
    case BOOLEAN:
      return ((Boolean) p.getValue()) ? "TRUE" : "FALSE";
    case DATE: {
      // BindParam stores DATE as epoch millis (Long)
      java.sql.Date d = new java.sql.Date(((Number) p.getValue()).longValue());
      return "DATE '" + d.toString() + "'"; // YYYY-MM-DD
    }
    case TIME: {
      java.sql.Time t = new java.sql.Time(((Number) p.getValue()).longValue());
      return "TIME '" + t.toString() + "'";
    }
    case TIMESTAMP: {
      java.sql.Timestamp ts = new java.sql.Timestamp(((Number) p.getValue()).longValue());
      return "TIMESTAMP '" + ts.toString() + "'";
    }
    default: {
      String escaped = p.getValue().toString().replace("'", "''");
      return "'" + escaped + "'";
    }
  }
}
```

### ADBC Mode Switch in AdbcRecordReader.setup()

```java
// Source: AdbcRecordReader.setup() analysis
List<BindParam> bindParams = config.getBindParams();
boolean hasParams = bindParams != null && !bindParams.isEmpty();

String adbcSql;
if (hasParams) {
  // ADBC-02: Inline literals for COPY binary protocol compatibility.
  // Do NOT use Extended Query Protocol bind (which blocks COPY path).
  adbcSql = LiteralInliner.inlineBindParams(config.getSql(), bindParams);
  // No stmt.bind() call — execute as simple query
} else {
  adbcSql = config.getSql(); // No params to inline
}
stmt.setSqlQuery(adbcSql);
// stmt.bind() is NOT called -- important for COPY protocol
AdbcStatement.QueryResult result = stmt.executeQuery();
```

### SQL Injection Safety Test Patterns

```java
// Unit test: verify malicious strings cannot escape quotes
@Test
public void testSingleQuoteEscape() {
  BindParam p = new BindParam("O'Malley", SqlTypeName.VARCHAR);
  String result = LiteralInliner.toSqlLiteral(p);
  assertEquals("'O''Malley'", result); // Single quote doubled
  assertFalse(result.contains("' OR '1'='1"));
}

@Test
public void testSemicolonInString() {
  BindParam p = new BindParam("val; DROP TABLE users; --", SqlTypeName.VARCHAR);
  String result = LiteralInliner.toSqlLiteral(p);
  assertTrue(result.startsWith("'") && result.endsWith("'"));
  // Semicolons are not special inside single-quoted strings
}

@Test
public void testSqlKeywordsInString() {
  BindParam p = new BindParam("SELECT * FROM users", SqlTypeName.VARCHAR);
  String result = LiteralInliner.toSqlLiteral(p);
  assertEquals("'SELECT * FROM users'", result);
}
```

### SQL Generation for JOIN

```java
// Source: analysis of SqlBuilder.buildSql() pattern
private String buildJoinSql(
    String leftAlias, String leftSchema, String leftTable, List<SchemaPath> leftCols,
    String rightAlias, String rightSchema, String rightTable, List<SchemaPath> rightCols,
    JoinRelType joinType, String onClause,
    String whereClause, Integer limit) {

  StringBuilder sb = new StringBuilder("SELECT ");

  // SELECT list: t1."col1", t1."col2", t2."col3", ...
  List<String> selectItems = new ArrayList<>();
  for (SchemaPath col : leftCols) {
    selectItems.add(quoteIdent(leftAlias) + "." + quoteIdent(col.getRootSegment().getPath()));
  }
  for (SchemaPath col : rightCols) {
    selectItems.add(quoteIdent(rightAlias) + "." + quoteIdent(col.getRootSegment().getPath()));
  }
  sb.append(String.join(", ", selectItems));

  // FROM
  sb.append(" FROM ").append(quoteIdent(leftSchema)).append(".").append(quoteIdent(leftTable));
  sb.append(" AS ").append(quoteIdent(leftAlias));

  // JOIN type
  sb.append(" ").append(joinTypeToSql(joinType)).append(" JOIN ");
  sb.append(quoteIdent(rightSchema)).append(".").append(quoteIdent(rightTable));
  sb.append(" AS ").append(quoteIdent(rightAlias));

  // ON clause
  sb.append(" ON ").append(onClause);

  // Optional WHERE
  if (whereClause != null) {
    sb.append(" WHERE ").append(whereClause);
  }

  return sb.toString();
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No JOIN pushdown | JOIN pushdown in Phase 35 | Phase 35 | Joins between same-source JDBC tables execute natively |
| ADBC uses Extended Query Protocol (bind) | ADBC inlines literals for simple query protocol | Phase 35 (ADBC-02) | PostgreSQL ADBC uses COPY binary path for all queries including filtered ones |
| INTERSECT/EXCEPT handled by Dremio engine (after LogicalToJoin rewrite) | INTERSECT/EXCEPT effects achieved via pushed JOINs | Phase 35 | Same semantic result; SQL uses JOIN not INTERSECT keywords |

**Deprecated/outdated:**
- `SqlBuilder.jdbcToPostgresPlaceholders()`: Still needed for JDBC mode, but bypassed in ADBC-02 mode. NOT deleted — it is the fallback.

## Open Questions

1. **Can the same `JdbcJoinScanPrel` be enhanced with WHERE clause support?**
   - What we know: The logical-level JOIN rule fires before filter pushdown, so initial version has no WHERE.
   - What's unclear: Whether we can add a `JdbcPushFilterIntoJoinScan` rule that pushes `FilterPrel` above `JdbcJoinScanPrel` into the join's WHERE clause. The challenge is column aliasing in the WHERE expression.
   - Recommendation: Defer to v2. In v1, filters remain in Dremio engine for JOIN queries.

2. **Oracle FULL OUTER JOIN support**
   - What we know: Oracle supports `FULL OUTER JOIN` syntax since Oracle 9i. Oracle's JDBC driver handles it.
   - What's unclear: Whether our `OracleSqlBuilder` needs any override for JOIN SQL construction.
   - Recommendation: No override needed for basic JOIN. Test in integration tests.

3. **INTERSECT ALL / EXCEPT ALL SQL semantics**
   - What we know: Calcite's `Minus.all = true` is not handled by `MinusToJoin.RULE` (it returns early). `UNION ALL` is not being pushed (per user decision). INTERSECT ALL is similarly complex.
   - What's unclear: Whether INTERSECT ALL / EXCEPT ALL queries can occur after rewrites produce patterns matching our rules.
   - Recommendation: Decline pushdown for `all=true` variants. The JOIN patterns from `INTERSECT_TO_DISTINCT` are only for `DISTINCT` semantics.

4. **Cross-schema same-source JOIN**
   - What we know: In Dremio catalog, each JDBC source exposes multiple schemas. Two scans on `mypostgres.schema1.table1` and `mypostgres.schema2.table2` share `pluginId.getName() = "mypostgres"`.
   - What's unclear: Whether the generated SQL `FROM "schema1"."table1" JOIN "schema2"."table2"` is valid in all supported databases.
   - Recommendation: Both PostgreSQL and Oracle support cross-schema joins within the same database. Generate with `"schema"."table"` qualification (already the pattern used in single-table SQL). This is safe.

5. **Reflections interference**
   - What we know: Success criterion 5 says cached/reflected queries must not be interfered with. Reflections use `AccelerationDetails` attached to plans and are matched by Dremio's reflection substitution mechanism.
   - What's unclear: Whether `JdbcJoinScanPrel` needs to handle `RuntimeFilteredRel` like `ScanPrelBase` does.
   - Recommendation: `JdbcJoinScanPrel` should implement the `Prel` interface but NOT `RuntimeFilteredRel` (reflections don't apply to JDBC sources). The rule's `matches()` guard must ensure neither scan has reflection metadata attached. For safety, check `scan.getTableMetadata()` — if the dataset has `DatasetConfig.getAccelerationId()` set, skip pushdown.

## Sources

### Primary (HIGH confidence)
- Codebase: `PlannerPhase.java` lines 663-664 — `INTERSECT_TO_DISTINCT` and `MinusToJoin.RULE` in pre-logical common rules
- Codebase: `MinusToJoin.java` — full implementation of EXCEPT-to-join rewrite
- Codebase: `JdbcScanPrel.java` — all pushdown state fields and clone methods
- Codebase: `AdbcRecordReader.java` — current ADBC bind parameter path
- Codebase: `SqlBuilder.java` — `jdbcToPostgresPlaceholders()` and identifier quoting
- Codebase: `BindParam.java` — type names and value serialization
- Codebase: `JdbcRulesFactory.java` — LOGICAL/PHYSICAL/PHYSICAL_HEP phase rule registration
- Codebase: `StoragePluginId.java` — `getName()` returns `SourceConfig.getName()` (catalog-unique)
- Codebase: `IntersectTest.java` — confirms INTERSECT is supported by Dremio engine
- Codebase: `JoinPrel.java`, `HashJoinPrel.java` — physical join node structure

### Secondary (MEDIUM confidence)
- PostgreSQL documentation: standard SQL string quoting (`'` → `''`) is sufficient for injection prevention; no backslash escaping needed in default `standard_conforming_strings=on` mode
- ADBC PG driver behavior: `stmt.bind()` triggers Extended Query Protocol, which prevents COPY binary path. This is consistent with the ADBC-02 requirement statement and the design rationale in the phase description.

### Tertiary (LOW confidence — needs validation)
- Rule ordering claim (plugin LOGICAL rules fire after `getPreLogicalCommonRules()`): inferred from the architecture of `PlannerPhase.java` using `getPreLogicalCommonRules()` as a separate HEP pass. Needs confirmation via a test that verifies INTERSECT node is no longer visible to plugin rules.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all classes confirmed by direct codebase reading
- Architecture (INTERSECT/EXCEPT rewritten before plugin rules): HIGH — confirmed via PlannerPhase.java
- Architecture (JOIN rule at logical level): HIGH — JoinRel and JdbcScanDrel confirmed
- Architecture (ADBC literal inlining): HIGH — AdbcRecordReader.setup() fully read
- Pitfalls (column aliasing): HIGH — standard SQL JOIN semantics
- Pitfalls (backslash escaping): MEDIUM — PostgreSQL docs referenced, not verified with Context7

**Research date:** 2026-03-14
**Valid until:** 2026-04-14 (codebase is stable; 30-day validity applies)
