# Phase 37: Expression Pushdown — Functions, HAVING, ORDER BY Expressions (pgvector foundation) - Research

**Researched:** 2026-03-16
**Domain:** Dremio JDBC plugin planning — function expression pushdown, HAVING clause, ORDER BY with expressions, COUNT(DISTINCT), CAST in JdbcProject
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| EXPR-01 | Enable pushdown of SQL expressions containing standard functions (ROUND, CEIL/CEILING, FLOOR, ABS, UPPER, LOWER, TRIM, LENGTH/CHAR_LENGTH, SUBSTRING, EXTRACT, COALESCE, NULLIF, CAST) via a per-dialect PushdownFunctionRegistry whitelist. Covers HAVING clause, ORDER BY with expressions, COUNT(DISTINCT), and CAST in JdbcProject. All functions in an expression tree must be whitelisted or the entire operator declines. | Architecture documented: PushdownFunctionRegistry interface, RexNode visitor pattern for recursive whitelist checking, new JdbcScanPrel fields (havingRex, sortExpressions), new rules (JdbcPushHavingIntoScan, JdbcPushSortWithExpressionsHep), fixes to JdbcPushFilterIntoScan (agg guard), JdbcPushProjectIntoScan (function expressions), JdbcPushAggIntoScan (COUNT DISTINCT). |

</phase_requirements>

---

## Summary

Phase 37 extends the Calcite JDBC convention pipeline from Phase 36 to handle SQL expressions inside pushdown operators. The core mechanism is a `PushdownFunctionRegistry` — a per-dialect whitelist that controls which SQL functions may be pushed to the remote source. A `RexNode` visitor walks the expression tree recursively; if every node is either a column reference, a literal, a supported comparison/arithmetic operator, or a whitelisted function call, the expression is safe to push. If any node fails the check, the entire operator (project, aggregate, sort, filter) stays in Dremio's engine.

Six concrete feature areas compose this phase: (1) **HAVING pushdown** — a new `JdbcPushHavingIntoScan` rule matches `FilterPrel(JdbcScanPrel[hasAgg=true])` and stores the HAVING condition as a `havingRex` field on the scan; `getPhysicalOperator()` places a `JdbcFilter` node after `JdbcAggregate` in the Calcite subtree. (2) **COUNT(DISTINCT)** — remove the `isDistinct()` guard in `JdbcPushAggIntoScan`, keeping only the kind check; `JdbcRules.JdbcAggregate` already renders `COUNT(DISTINCT col)` correctly. (3) **ORDER BY with expressions** — a new HEP-phase rule matches `SortPrel(ProjectPrel(JdbcScanPrel))` when the ProjectPrel contains whitelisted function expressions, captures the sort key expressions from the ProjectPrel, and stores them as `List<RexNode> sortKeyExpressions` on the scan alongside the collation. (4) **CAST in JdbcProject** — fix `JdbcPushProjectIntoScan` to accept CAST and other whitelisted function expressions (not just `RexInputRef`) and store full `RexNode` project expressions on the scan. (5) **Function composition** — the whitelist visitor recurses into operands; a node like `UPPER(TRIM(col))` passes only if both UPPER and TRIM are whitelisted. (6) **Fix JdbcPushFilterIntoScan HAVING guard** — currently the filter rule would incorrectly push a HAVING condition into the WHERE position; add a guard `!scan.hasAggregation()` to `matches()`.

**Primary recommendation:** Build `PushdownFunctionRegistry` as a simple interface with one method `boolean isFunctionPushable(SqlOperator op)`. Implement it as `StandardFunctionRegistry` (base) and per-dialect subclasses. Use a `RexVisitorImpl<Boolean>` to recursively validate any `RexNode`. The existing Phase 36 `JdbcRel` subtree and `DremioJdbcImplementor` already render all standard SQL functions correctly — no SQL generation changes are needed, only planning-layer changes.

---

## Standard Stack

### Core (All already on classpath)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `calcite-core` (rex) | 1.22.0-202501311721330426-8b30dab9 | `RexVisitorImpl<Boolean>`, `RexCall`, `RexNode`, `SqlKind`, `SqlOperator` | Foundation for expression tree walking and operator identity checks |
| `calcite-core` (adapter/jdbc) | same | `JdbcRules.JdbcAggregate` (DISTINCT support), `JdbcRules.JdbcFilter` (HAVING position), `JdbcRules.JdbcProject` (function expressions) | Already used by Phase 36 pipeline; accepts any `RexNode` in project/filter expressions |
| `calcite-core` (sql/fun) | same | `SqlStdOperatorTable` (UPPER, LOWER, ABS, ROUND, SUBSTRING, CHAR_LENGTH, TRIM, COALESCE, NULLIF, FLOOR, CEIL, EXTRACT, CAST) | Function operators identified by `SqlKind` or `op.getName()` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `calcite-core` (sql/fun) | same | `OracleSqlOperatorTable.NVL` | Not needed — Dremio converts NVL to COALESCE at SQL parse time via convertlet; just whitelist COALESCE |
| Dremio `FilterPrel`, `AggregatePrel`, `ProjectPrel`, `SortPrel`, `TopNPrel` | in-tree kernel | Pattern matching in new pushdown rules | Already used by existing rules |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Per-dialect PushdownFunctionRegistry interface | Hard-coded function name set per rule | Registry is extensible (Phase 38 adds pgvector operators); hard-coded sets require modifying core rules per dialect |
| Recursive RexVisitorImpl whitelist check | Top-level-only check | Composition (UPPER(TRIM(col))) requires full recursive walk; top-level check misses nested function calls |

**No new Maven dependencies needed.** All Calcite APIs are already on classpath.

---

## Architecture Patterns

### Recommended Project Structure (new and modified files)

```
plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/
├── PushdownFunctionRegistry.java        NEW: interface isFunctionPushable(SqlOperator)
├── StandardPushdownFunctionRegistry.java NEW: base implementation with standard SQL functions
├── JdbcPushHavingIntoScan.java          NEW: FilterPrel(JdbcScanPrel[hasAgg]) -> HAVING
├── JdbcPushSortWithExpressionsHep.java  NEW: SortPrel(ProjectPrel(JdbcScanPrel)) -> expr sort
├── JdbcPushFilterIntoScan.java          MODIFY: add !scan.hasAggregation() guard to matches()
├── JdbcPushProjectIntoScan.java         MODIFY: accept whitelisted function expressions
├── JdbcPushAggIntoScan.java             MODIFY: remove isDistinct() guard for COUNT(DISTINCT)
└── JdbcScanPrel.java                    MODIFY: add havingRex, sortKeyExpressions fields + clones

plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/
└── PostgresConf.java                    POSSIBLY: override getPushdownRegistry() if PG needs extras

plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/
└── OracleConf.java                      POSSIBLY: override getPushdownRegistry() for Oracle extras
```

### Pattern 1: PushdownFunctionRegistry Interface

**What:** Per-dialect function whitelist with recursive RexNode validation.
**When to use:** Before any pushdown rule accepts an expression containing a `RexCall`.

```java
// Source: Design from codebase + Calcite SqlOperator API
public interface PushdownFunctionRegistry {
  /** Returns true if this operator can be pushed down to the remote source. */
  boolean isFunctionPushable(SqlOperator op);

  /**
   * Returns true when the entire RexNode tree is pushable:
   * all nodes are column refs, literals, comparison/arithmetic operators,
   * or whitelisted function calls.
   */
  default boolean isExpressionPushable(RexNode expr) {
    return expr.accept(new RexVisitorImpl<Boolean>(true) {
      @Override public Boolean visitInputRef(RexInputRef ref) { return true; }
      @Override public Boolean visitLiteral(RexLiteral lit)   { return true; }
      @Override public Boolean visitCall(RexCall call) {
        // CAST, comparison operators, arithmetic, and standard SQL expressions:
        // allow unconditionally for comparison/arithmetic (AND, OR, NOT, =, <, >, etc.)
        SqlKind kind = call.getKind();
        if (kind.belongsTo(SqlKind.COMPARISON)
            || kind.belongsTo(SqlKind.LOGICAL)
            || kind == SqlKind.PLUS || kind == SqlKind.MINUS
            || kind == SqlKind.TIMES || kind == SqlKind.DIVIDE
            || kind == SqlKind.IS_NULL || kind == SqlKind.IS_NOT_NULL
            || kind == SqlKind.LIKE || kind == SqlKind.BETWEEN
            || kind == SqlKind.IN || kind == SqlKind.NOT_IN
            || kind == SqlKind.CASE) {
          // recurse into operands
          return call.getOperands().stream().allMatch(op -> op.accept(this) == Boolean.TRUE);
        }
        // For function calls (UPPER, LOWER, CAST, EXTRACT, COALESCE, etc.):
        if (!isFunctionPushable(call.getOperator())) {
          return false;
        }
        // Recurse into function arguments
        return call.getOperands().stream().allMatch(op -> op.accept(this) == Boolean.TRUE);
      }
    }) == Boolean.TRUE;
  }
}
```

### Pattern 2: StandardPushdownFunctionRegistry

**What:** Concrete implementation whitelisting standard SQL functions valid on both PG and Oracle.

```java
// Source: Calcite SqlKind enum + SqlStdOperatorTable verified from calcite-core jar
public class StandardPushdownFunctionRegistry implements PushdownFunctionRegistry {

  public static final StandardPushdownFunctionRegistry INSTANCE =
      new StandardPushdownFunctionRegistry();

  // Well-known SqlKind values (no getName() lookup needed):
  private static final Set<SqlKind> WHITELISTED_KINDS = EnumSet.of(
      SqlKind.CAST,      // CAST(col AS type)
      SqlKind.FLOOR,     // FLOOR(x)
      SqlKind.CEIL,      // CEIL(x)
      SqlKind.TRIM,      // TRIM(x)
      SqlKind.LTRIM,     // LTRIM(x) -- variant
      SqlKind.RTRIM,     // RTRIM(x) -- variant
      SqlKind.EXTRACT,   // EXTRACT(YEAR FROM date)
      SqlKind.COALESCE,  // COALESCE(a, b, ...)
      SqlKind.NULLIF     // NULLIF(a, b)
  );

  // OTHER_FUNCTION operators identified by name:
  private static final Set<String> WHITELISTED_FUNCTION_NAMES = ImmutableSet.of(
      "UPPER", "LOWER", "ABS", "ROUND", "SUBSTRING", "CHAR_LENGTH", "CHARACTER_LENGTH",
      "LENGTH"  // dialect-specific alias for CHAR_LENGTH
  );

  @Override
  public boolean isFunctionPushable(SqlOperator op) {
    if (WHITELISTED_KINDS.contains(op.getKind())) {
      return true;
    }
    if (op.getKind() == SqlKind.OTHER_FUNCTION) {
      return WHITELISTED_FUNCTION_NAMES.contains(op.getName().toUpperCase(Locale.ROOT));
    }
    return false;
  }
}
```

### Pattern 3: HAVING Pushdown — New JdbcScanPrel Field + New Rule

**What:** `havingRex` field stores the post-aggregation filter condition. New rule `JdbcPushHavingIntoScan` matches `FilterPrel(JdbcScanPrel[hasAgg=true])`.

```java
// JdbcScanPrel: add field
private final RexNode havingRex;  // null when no HAVING pushed

public boolean hasHaving() { return havingRex != null; }

public JdbcScanPrel cloneWithHaving(RexNode newHavingRex) {
  return new JdbcScanPrel(/* all fields */, newHavingRex, ...);
}

// getPhysicalOperator() step 8.5: after JdbcAggregate, before JdbcSort:
if (havingRex != null) {
  // havingRex references the AGGREGATE output row type (group keys + agg functions)
  // Indices match the aggregated output directly — no normalization needed.
  root = new JdbcRules.JdbcFilter(cluster, jdbcTraitSet, root, havingRex);
}
```

```java
// JdbcPushHavingIntoScan.matches():
JdbcScanPrel scan = call.rel(1);
return scan.hasAggregation() && !scan.hasHaving();

// JdbcPushHavingIntoScan.onMatch():
FilterPrel filter = call.rel(0);
JdbcScanPrel scan = call.rel(1);
// HAVING condition references aggregated output row — indices are already correct,
// no normalization needed (unlike WHERE filter which must be normalized to full-table indices).
// Validate all expressions are pushable:
if (!registry.isExpressionPushable(filter.getCondition())) return;
call.transformTo(scan.cloneWithHaving(filter.getCondition()));
```

### Pattern 4: Fix JdbcPushFilterIntoScan — HAVING Guard

**What:** Prevent WHERE filter rule from incorrectly pushing HAVING condition into WHERE position.

```java
// JdbcPushFilterIntoScan.matches() — add aggregation guard:
@Override
public boolean matches(RelOptRuleCall call) {
  JdbcScanPrel scan = call.rel(1);
  // Do not push a second filter if one is already present.
  if (scan.hasFilter()) return false;
  // Do not push a filter on top of an aggregated scan as a WHERE clause.
  // HAVING (FilterPrel above an aggregated scan) is handled by JdbcPushHavingIntoScan.
  if (scan.hasAggregation()) return false;
  return true;
}
```

### Pattern 5: ORDER BY with Expressions — HEP Phase Rule

**What:** `JdbcPushSortWithExpressionsHep` matches `SortPrel(ProjectPrel(JdbcScanPrel))` where ProjectPrel has whitelisted function expressions. Captures sort key expressions from the ProjectPrel at the collation indices and stores them as `List<RexNode>` on the scan.

The key insight: `RelCollation.getFieldCollations()` carries integer field indices into the ProjectPrel's output row. The ProjectPrel's `getProjects()` at those indices are the actual sort key expressions (e.g., `RexCall(UPPER, [RexInputRef(3)])`). We extract these expressions and store them separately from the collation.

```java
// JdbcScanPrel: add field
private final List<RexNode> sortKeyExpressions;  // null for simple column-ref sorts

// In getPhysicalOperator(), the sort step changes:
// If sortKeyExpressions != null:
//   Build a JdbcProject that adds sort key columns to the output
//   Then JdbcSort references those extra columns by index
// Then: the output row type of the JdbcScanPrel is declared without the extra sort key columns
// (they are internal to the generated SQL subquery if needed)
//
// Actually simpler: PostgreSQL and Oracle both support ORDER BY expressions not in SELECT.
// So we can use JdbcSort with sort keys as RexCall nodes directly, without adding them to SELECT.
// The JdbcSort.implement() in Calcite renders ORDER BY using getSortExps() which returns
// RexInputRef nodes into the input row. For expressions, we need to add them to a JdbcProject first.
//
// Implementation approach:
// If sortKeyExpressions contains non-RexInputRef nodes:
//   1. Build extended JdbcProject: [original projected cols..., sort_expr_1, sort_expr_2, ...]
//   2. Build JdbcSort with collation referencing extended indices
//   3. Build outer JdbcProject to trim back to original projected columns
//      (so SQL is: SELECT original_cols FROM (...) ORDER BY sort_expr)
// This matches Calcite's standard approach and is dialect-neutral.
```

**Sort expression normalization**: Sort collation field indices in `SortPrel` reference the `ProjectPrel`'s output row. The `ProjectPrel` output has the original columns plus any computed expression columns. At push time, extract:
- Sort key expressions: `projectPrel.getProjects().get(collationField.getFieldIndex())`
- These are the actual RexNode expressions (RexCall or RexInputRef)
- Store only the expressions, not the indices (indices are rebuilt during `getPhysicalOperator()`)

### Pattern 6: COUNT(DISTINCT) — Remove isDistinct Guard

**What:** Remove the `isDistinct()` check in `JdbcPushAggIntoScan.matches()` for `COUNT` kind only.

```java
// Current (Phase 33/36):
for (AggregateCall aggCall : agg.getAggCallList()) {
  if (aggCall.isDistinct()) {
    return false; // REJECT all DISTINCT aggregates
  }
}

// Phase 37 fix: allow COUNT(DISTINCT) but reject DISTINCT for other aggregates:
for (AggregateCall aggCall : agg.getAggCallList()) {
  if (aggCall.isDistinct()) {
    // Only COUNT(DISTINCT) is supported; SUM(DISTINCT), AVG(DISTINCT) etc. are not standard.
    if (aggCall.getAggregation().getKind() != SqlKind.COUNT) {
      return false;
    }
  }
}
```

`JdbcRules.JdbcAggregate.implement()` already renders `COUNT(DISTINCT "col")` correctly for both PostgreSQL and Oracle. No SQL generation changes needed.

### Pattern 7: CAST in JdbcProject — Accept Function Expressions

**What:** Modify `JdbcPushProjectIntoScan` to accept any expression that passes `registry.isExpressionPushable()`, not just `RexInputRef`. Store full `RexNode` expressions (not just column names as `SchemaPath`) on `JdbcScanPrel`.

This requires a model change to `JdbcScanPrel`:
- Current: `projectedColumns: List<SchemaPath>` (column names only)
- Phase 37: add `projectExpressions: List<RexNode>` (null = use projectedColumns as column refs)

When `projectExpressions != null`, `getPhysicalOperator()` builds the `JdbcProject` using these expressions directly instead of constructing `RexInputRef` nodes from `projectedColumns`.

The output column names come from the `ProjectPrel`'s row type field names, not from `SchemaPath` names. Store them alongside the expressions.

```java
// JdbcScanPrel: new fields
private final List<RexNode> projectExpressions;    // null → use projectedColumns
private final List<String>  projectOutputNames;    // null → use projectedColumns names

// JdbcPushProjectIntoScan: modified
for (RexNode expr : projects) {
  if (expr instanceof RexInputRef) {
    // simple column ref — handled by existing SchemaPath path
    continue;
  }
  if (!registry.isExpressionPushable(expr)) {
    return; // decline — non-pushable expression
  }
  // All expressions are pushable
}
// If any expression is non-trivial, store the full RexNode list:
call.transformTo(scan.cloneWithProjectExpressions(
    projects, project.getRowType().getFieldNames()));
```

### Pattern 8: Function Whitelist Check at Rule Level

**What:** Each pushdown rule that evaluates filter conditions or expressions must check the whitelist before accepting.

- `JdbcPushFilterIntoScan`: check `registry.isExpressionPushable(filter.getCondition())`
- `JdbcPushHavingIntoScan`: check `registry.isExpressionPushable(filter.getCondition())`
- `JdbcPushProjectIntoScan`: check each expression
- `JdbcPushSortWithExpressionsHep`: check each sort key expression
- `JdbcPushAggIntoScan`: check `filterArg >= 0` (FILTER clause on agg) — these are new; check `aggCall.filterArg` is -1 (no FILTER clause) unless FILTER is also whitelisted

### Accessing PushdownFunctionRegistry in Rules

**Challenge**: Rules need the dialect-specific registry, but rules are registered globally without per-source state.

**Solution A** (recommended): Rules hold a reference to the registry passed at construction time. `JdbcRulesFactory.getRules()` receives `OptimizerRulesContext optimizerContext` — from context, look up the plugin by type, call `jdbcPlugin.getPushdownFunctionRegistry()`. Pass registry to each rule constructor.

**Solution B**: Rules fetch the registry at `onMatch()` time by navigating to the plugin from the scan node's `pluginId`. This is how `JdbcScanPrel.getPhysicalOperator()` fetches the `SqlDialect`. Less clean.

**Recommendation**: Implement Solution A. The rules pattern already passes `pluginType` to `getRules()` and the rule factory has access to `OptimizerRulesContext` which provides `getCatalogService().getSource(...)`.

### Anti-Patterns to Avoid

- **Pushing HAVING as WHERE**: Already documented — the guard in `JdbcPushFilterIntoScan.matches()` must include `!scan.hasAggregation()`. Without this guard, Calcite generates `SELECT dept, COUNT(*) FROM t WHERE COUNT(*) > 5 GROUP BY dept` — invalid SQL.
- **Storing sort collation indices that reference ProjectPrel row positions**: The collation field indices from `SortPrel` point into `ProjectPrel`'s output, not the base table. At push time, resolve them to actual `RexNode` expressions from `ProjectPrel.getProjects()`. Do NOT store the raw collation unchanged — the indices will be wrong when `getPhysicalOperator()` builds from the base table leaf.
- **Assuming HAVING condition indices reference full-table positions**: The `havingRex` indices reference the AGGREGATED output row (group keys first, then aggregate functions). This is different from `filterRex` which references full-table positions. Do NOT call `normalizeToFullTable()` on `havingRex`.
- **Whitelisting ORDER BY / HAVING without also whitelisting in WHERE**: Be consistent. A function that's safe to push in an ORDER BY is safe to push in a WHERE.
- **Checking only the top-level function, not operands**: `UPPER(nonPushableFunction(col))` should be declined. The recursive visitor handles this. Don't short-circuit at the outer call level.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SQL rendering of UPPER(), ROUND(), CAST(), etc. | Custom unparse methods in pushdown rules | `JdbcRules.JdbcProject.implement()` + `DremioJdbcImplementor` | Calcite already renders all standard SQL functions correctly for both PostgreSQL and Oracle via `SqlDialect` |
| HAVING clause SQL generation | Custom HAVING string building | `JdbcRules.JdbcFilter` placed AFTER `JdbcRules.JdbcAggregate` in the subtree | `JdbcImplementor` detects filter position relative to aggregate and renders `HAVING` automatically |
| COUNT(DISTINCT) SQL rendering | Custom `COUNT(DISTINCT col)` string | `JdbcRules.JdbcAggregate` with `AggregateCall.isDistinct() == true` | Calcite renders `COUNT(DISTINCT ...)` correctly for both dialects |
| ORDER BY expression SQL rendering | Custom ORDER BY string with expressions | `JdbcRules.JdbcSort` with sort key expressions via extended `JdbcProject` | Calcite renders arbitrary ORDER BY expressions including function calls |
| Recursive RexNode expression validation | Manual switch/case over node types | `RexVisitorImpl<Boolean>` with recursive `visitCall()` | Standard Calcite visitor handles all node types including nested calls |

**Key insight:** The `JdbcImplementor` + `JdbcRules.*` pipeline from Phase 36 already knows how to render any `RexNode` that Calcite can represent. The only planning-layer work in Phase 37 is deciding WHICH expressions to allow through, and storing them correctly so `getPhysicalOperator()` can hand them to `JdbcImplementor`.

---

## Common Pitfalls

### Pitfall 1: HAVING Placed in WHERE Position
**What goes wrong:** `JdbcPushFilterIntoScan` fires on `FilterPrel(JdbcScanPrel[hasAgg=true])` and stores the HAVING condition as `filterRex`. `getPhysicalOperator()` places `JdbcFilter` BEFORE `JdbcAggregate`, generating `WHERE COUNT(*) > 5` — invalid SQL.
**Why it happens:** The current `matches()` guard only checks `!scan.hasFilter()`, not `!scan.hasAggregation()`.
**How to avoid:** Add `!scan.hasAggregation()` to `JdbcPushFilterIntoScan.matches()`. Implement `JdbcPushHavingIntoScan` separately for the post-aggregation case.
**Warning signs:** SQL parse error: `aggregate functions are not allowed in WHERE` (PostgreSQL) or `ORA-00934: group function is not allowed here` (Oracle).

### Pitfall 2: Sort Collation Indices Reference ProjectPrel Positions
**What goes wrong:** `SortPrel.getCollation().getFieldCollations()` returns indices into the `ProjectPrel`'s output row. If you store these indices directly on `JdbcScanPrel`, `getPhysicalOperator()` uses them against the base table leaf, selecting the wrong columns.
**Why it happens:** Dremio's planning inserts a `ProjectPrel` that adds computed sort key columns at specific positions. The `SortPrel` sees those computed columns, not the base table columns.
**How to avoid:** At push time, resolve each collation field index to the actual `RexNode` expression from `ProjectPrel.getProjects().get(fieldIndex)`. Store the expressions, not the indices. Rebuild the sort collation from scratch in `getPhysicalOperator()` to reference the extended JdbcProject columns.
**Warning signs:** Wrong column sorted, or `ArrayIndexOutOfBoundsException` when `JdbcSort` tries to reference a column index that doesn't exist in the base table.

### Pitfall 3: HAVING Condition Normalization
**What goes wrong:** Applying `normalizeToFullTable()` to the `havingRex` condition. The HAVING condition indices reference the aggregate output row (e.g., `$1 > 5` means column 1 of the GROUP BY + agg output), not the full base table. Normalizing to full-table positions remaps these to wrong indices.
**Why it happens:** The WHERE filter `normalizeToFullTable()` call was designed for base-table column references. HAVING references are already correct for the aggregate output row.
**How to avoid:** Store `havingRex` without normalization. In `getPhysicalOperator()`, the `JdbcFilter(HAVING)` is placed above `JdbcAggregate` whose output row type matches what the HAVING condition references.
**Warning signs:** `ORA-00904: "EXPR$1": invalid identifier` or wrong column in HAVING condition.

### Pitfall 4: Recursive Whitelist Check Short-Circuits on First Function Call
**What goes wrong:** Whitelist check passes as soon as it sees a whitelisted outer function, without checking the operands. `UPPER(nonPushableUDF(col))` gets pushed, causing a remote SQL error.
**Why it happens:** `visitCall()` returns `true` immediately upon finding the outer function in the whitelist, without recursing into `call.getOperands()`.
**How to avoid:** In `visitCall()`, always recurse into all operands after verifying the operator is pushable: `return isFunctionPushable(call.getOperator()) && call.getOperands().stream().allMatch(op -> op.accept(this) == Boolean.TRUE)`.
**Warning signs:** Remote SQL error for non-existent function.

### Pitfall 5: COUNT(DISTINCT) Without FILTER Clause
**What goes wrong:** Enabling `COUNT(DISTINCT)` without checking `aggCall.filterArg < 0`. If an aggregate has a FILTER clause (`COUNT(DISTINCT col) FILTER (WHERE condition)`), the generated SQL may be invalid for some dialects.
**Why it happens:** `AggregateCall.filterArg >= 0` indicates a FILTER clause on the aggregate. Not all JDBC dialects support `FILTER (WHERE ...)` on aggregate functions.
**How to avoid:** In `JdbcPushAggIntoScan.matches()`, reject any `AggregateCall` with `filterArg >= 0`. This is orthogonal to the DISTINCT support. `filterArg == -1` means no FILTER clause.
**Warning signs:** SQL syntax error near `FILTER` on Oracle or older PostgreSQL.

### Pitfall 6: Non-Standard EXTRACT Syntax per Dialect
**What goes wrong:** Calcite renders `EXTRACT(YEAR FROM col)` using the standard ISO SQL syntax. Oracle accepts this. PostgreSQL accepts this. However, Dremio may internally represent date part extraction differently (e.g., as `YEAR(col)` function calls from Gandiva). If `EXTRACT` arrives as an `OTHER_FUNCTION` named `YEAR` rather than `SqlKind.EXTRACT`, the whitelist check may fail.
**Why it happens:** Dremio's SQL parser may route some `EXTRACT` calls through `DremioSqlOperatorTable` functions.
**How to avoid:** During testing, verify the actual `SqlKind` of `EXTRACT` nodes in the plan. Add both `SqlKind.EXTRACT` AND the name `"EXTRACT"` to the whitelist. Integration test with `SELECT EXTRACT(YEAR FROM date_col) FROM t`.
**Warning signs:** EXTRACT not pushed despite being in whitelist.

---

## Code Examples

### RexNode Whitelist Visitor (recursive)
```java
// Source: Calcite RexVisitorImpl API + SqlKind enum — verified from calcite-core jar
public class PushdownExpressionChecker extends RexVisitorImpl<Boolean> {
  private final PushdownFunctionRegistry registry;

  PushdownExpressionChecker(PushdownFunctionRegistry registry) {
    super(true); // deep = true for recursive visiting
    this.registry = registry;
  }

  @Override public Boolean visitInputRef(RexInputRef ref)  { return Boolean.TRUE; }
  @Override public Boolean visitLiteral(RexLiteral lit)    { return Boolean.TRUE; }

  @Override
  public Boolean visitCall(RexCall call) {
    SqlKind kind = call.getKind();
    // Comparison, logic, arithmetic, IS NULL — always pushable if operands are:
    if (kind.belongsTo(SqlKind.COMPARISON) || kind.belongsTo(SqlKind.LOGICAL)
        || kind == SqlKind.PLUS || kind == SqlKind.MINUS
        || kind == SqlKind.TIMES || kind == SqlKind.DIVIDE
        || kind == SqlKind.IS_NULL || kind == SqlKind.IS_NOT_NULL
        || kind == SqlKind.LIKE || kind == SqlKind.BETWEEN
        || kind == SqlKind.IN || kind == SqlKind.NOT_IN
        || kind == SqlKind.NOT || kind == SqlKind.CASE) {
      return visitOperands(call);
    }
    // Function calls — check whitelist then recurse:
    if (!registry.isFunctionPushable(call.getOperator())) {
      return Boolean.FALSE;
    }
    return visitOperands(call);
  }

  private Boolean visitOperands(RexCall call) {
    for (RexNode op : call.getOperands()) {
      if (op.accept(this) != Boolean.TRUE) return Boolean.FALSE;
    }
    return Boolean.TRUE;
  }
}
```

### JdbcPushHavingIntoScan — Core Logic
```java
// Pattern: FilterPrel(JdbcScanPrel[hasAgg=true, !hasHaving])
// Source: Analogy to JdbcPushFilterIntoScan, verified against existing codebase
@Override
public boolean matches(RelOptRuleCall call) {
  JdbcScanPrel scan = call.rel(1);
  return scan.hasAggregation() && !scan.hasHaving();
}

@Override
public void onMatch(RelOptRuleCall call) {
  FilterPrel filter = call.rel(0);
  JdbcScanPrel scan = call.rel(1);
  RexNode condition = filter.getCondition();
  // HAVING condition references aggregate output row — no normalization.
  // Validate functions in condition:
  if (!registry.isExpressionPushable(condition)) return;
  call.transformTo(scan.cloneWithHaving(condition));
}
```

### getPhysicalOperator() — HAVING Position
```java
// Source: JdbcScanPrel.java existing implementation — verified
// Existing order: leaf -> [JdbcFilter WHERE] -> [JdbcProject] -> [JdbcAggregate] -> [JdbcSort]
// Phase 37 adds HAVING after JdbcAggregate:
// leaf -> [JdbcFilter WHERE] -> [JdbcProject] -> [JdbcAggregate] -> [JdbcFilter HAVING] -> [JdbcSort]

// Step 8.5 (new, after JdbcAggregate step):
if (havingRex != null) {
  // havingRex indices already reference the aggregate output row type — no remapping needed.
  root = new JdbcRules.JdbcFilter(cluster, jdbcTraitSet, root, havingRex);
}
```

### getPhysicalOperator() — JdbcProject with Function Expressions
```java
// Source: JdbcScanPrel.java step 7 — extends existing JdbcProject building
// When projectExpressions != null (function expressions pushed), use them directly:
if (projectExpressions != null && !projectExpressions.isEmpty()) {
  RelDataType projRowType = cluster.getTypeFactory().createStructType(
      projectExpressions.stream().map(RexNode::getType).collect(Collectors.toList()),
      projectOutputNames);
  root = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, root, projectExpressions, projRowType);
}
```

### COUNT(DISTINCT) — AggregateCall Guard Fix
```java
// Source: JdbcPushAggIntoScan.matches() — current code (Phase 36)
// Current (rejects all DISTINCT):
for (AggregateCall aggCall : agg.getAggCallList()) {
  if (aggCall.isDistinct()) return false;
}
// Phase 37 fix (allows COUNT(DISTINCT), rejects others, also rejects FILTER clause):
for (AggregateCall aggCall : agg.getAggCallList()) {
  if (aggCall.filterArg >= 0) return false; // FILTER (WHERE ...) on aggregate — not supported
  if (aggCall.isDistinct()) {
    if (aggCall.getAggregation().getKind() != SqlKind.COUNT) return false;
  }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| JdbcPushFilterIntoScan pushes all FilterPrel above JdbcScanPrel | Add `!scan.hasAggregation()` guard | Phase 37 | Prevents HAVING-as-WHERE bug |
| isDistinct() check blocks all DISTINCT aggregates | Allow COUNT(DISTINCT) | Phase 37 | `SELECT dept, COUNT(DISTINCT id) FROM t GROUP BY dept` now pushes |
| JdbcPushProjectIntoScan accepts only RexInputRef | Accept whitelisted function expressions | Phase 37 | CAST and function expressions in SELECT list push to source |
| ORDER BY with expressions: stays in Dremio engine | JdbcPushSortWithExpressionsHep pushes sort key expressions | Phase 37 | `ORDER BY UPPER(name) LIMIT K` becomes single source SQL |
| HAVING stays in Dremio engine (executed after fetch) | JdbcPushHavingIntoScan pushes HAVING condition | Phase 37 | `GROUP BY dept HAVING COUNT(*) > 5` becomes single source SQL |

**Not changed in Phase 37:**
- Phase 36 JdbcImplementor / DremioJdbcImplementor / JdbcCalciteLeaf — no changes needed; they already render any JdbcRel tree correctly.
- Phase 36 OracleSqlDialect / PostgresqlSqlDialect — no changes needed; standard functions render correctly via both.
- `JdbcPushAggIntoScan` aggregate kind whitelist (COUNT, SUM, SUM0, MIN, MAX, AVG) — unchanged.

---

## Open Questions

1. **How does the PushdownFunctionRegistry reach pushdown rules at construction time?**
   - What we know: `JdbcRulesFactory.getRules()` receives `OptimizerRulesContext optimizerContext` and `SourceType pluginType`. The context can look up the plugin by type/name.
   - What's unclear: Can `optimizerContext` reliably retrieve the plugin instance and call `getPushdownFunctionRegistry()` at `getRules()` time? Or does the catalog service need to be called differently?
   - Recommendation: Test by checking `optimizerContext.getCatalogService().getSource(...)` — the same call `JdbcScanPrel.getPhysicalOperator()` uses. If it returns the plugin, use it to get the registry. If unavailable at rule-factory time, fall back to `StandardPushdownFunctionRegistry.INSTANCE` for all JDBC sources.

2. **ORDER BY expression + JdbcProject interaction with JdbcScanPrel output row type**
   - What we know: When sort key expressions are added as extra columns to `JdbcProject`, they must be visible in the generated SQL but should NOT appear in the scan's declared output (Dremio's outer plan trims them via the trim-project pattern).
   - What's unclear: Does `JdbcScanPrel` need to track which columns are "internal sort key columns" vs. "output columns"? Or does the `JdbcSort` + trim outer project handle this automatically?
   - Recommendation: Build an integration test first. If Dremio's trim-project already removes extra columns, no extra tracking is needed. If not, add an `outputColumnCount` field to indicate how many columns are actual output vs. sort-key-only.

3. **EXTRACT rendering dialect differences**
   - What we know: `EXTRACT(YEAR FROM col)` is standard SQL; both PG and Oracle support it. But Calcite may sometimes use `FLOOR(col TO YEAR)` as an alternative representation.
   - What's unclear: Does Dremio's planner convert `YEAR(col)` or `DATEPART('year', col)` to `SqlKind.EXTRACT` or to `OTHER_FUNCTION`?
   - Recommendation: Add an integration test with `EXTRACT(YEAR FROM hire_date)` and inspect the actual `SqlKind` in the plan via the pushdown validator. If `OTHER_FUNCTION`, add `"EXTRACT"` to the name whitelist in addition to `SqlKind.EXTRACT`.

4. **WhitelistedFunctions in WHERE filter context (Phase 37 vs current Phase 36 behavior)**
   - What we know: Currently `JdbcPushFilterIntoScan` pushes any `RexNode` condition without checking function content. The `JdbcRules.JdbcFilter` + `DremioJdbcImplementor` will attempt to render any function in the WHERE condition.
   - What's unclear: Should Phase 37 retroactively add function whitelist checks to the existing WHERE filter pushdown? If a user writes `WHERE UPPER(name) = 'ALICE'`, is this pushed today (before Phase 37)? If yes, it may work but is risky.
   - Recommendation: Add the whitelist check to `JdbcPushFilterIntoScan` in Phase 37 as well. This makes behavior consistent and prevents accidental pushdown of non-standard functions in WHERE clauses. Document explicitly that this is a tightening of Phase 33/36 behavior.

---

## Sources

### Primary (HIGH confidence)
- Codebase source code read directly — all Phase 36 files in `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/`:
  - `JdbcScanPrel.java` — full constructor, `getPhysicalOperator()` step order verified
  - `JdbcPushFilterIntoScan.java` — current `matches()` logic (no aggregation guard)
  - `JdbcPushAggIntoScan.java` — current `isDistinct()` rejection, `filterArg` field
  - `JdbcPushProjectIntoScan.java` — current `RexInputRef`-only acceptance
  - `JdbcPushSortIntoScanHep.java` — current sort collation storage and `findJdbcScan()` pattern
  - `JdbcRulesFactory.java` — rule registration phases verified
  - `DremioJdbcImplementor.java` — `visit(JdbcCalciteLeaf)` dispatch confirmed
- `calcite-core-1.22.0-202501311721330426-8b30dab9.jar` — verified via javap:
  - `org.apache.calcite.rex.RexCall` — `op`, `operands`, `getKind()`, `accept()` methods
  - `org.apache.calcite.rex.RexVisitorImpl<R>` — `visitCall()`, `visitInputRef()`, `visitLiteral()` API
  - `org.apache.calcite.sql.SqlKind` — `FLOOR`, `CEIL`, `TRIM`, `LTRIM`, `RTRIM`, `EXTRACT`, `COALESCE`, `NULLIF`, `CAST`, `OTHER_FUNCTION`, `NVL` all present
  - `org.apache.calcite.sql.fun.SqlStdOperatorTable` — `UPPER`, `LOWER`, `ABS`, `ROUND`, `SUBSTRING`, `CHAR_LENGTH`, `COALESCE`, `NULLIF`, `FLOOR`, `CEIL`, `EXTRACT`, `TRIM` all present
  - `org.apache.calcite.sql.fun.OracleSqlOperatorTable` — `NVL` present as SqlFunction
  - `org.apache.calcite.rel.core.AggregateCall` — `isDistinct()`, `filterArg` field, `copy()` methods
  - `org.apache.calcite.adapter.jdbc.JdbcRules.JdbcAggregate` — constructor signature confirmed
  - `org.apache.calcite.rel.core.Sort` — `getSortExps()` returns `List<RexNode>` (RexInputRef into input row), `getCollation()` returns `RelCollation`
  - `org.apache.calcite.rel.RelFieldCollation` — `getFieldIndex()` is an int field index, not a RexNode expression
- `sabot/kernel/.../AggregatePrel.java` — `OperatorPhase` enum, `PHASE_1of1` guard confirmed
- `sabot/kernel/.../SortPrel.java` — extends `SortRelBase`, `getCollation()` confirmed
- `DremioCompositeSqlOperatorTable.java` — NVL converted to COALESCE via convertlet at SQL parse time (line 112-114), confirmed

### Secondary (MEDIUM confidence)
- Dremio planning architecture knowledge from Phase 33/36 research documents — verified against actual code in this research

### Tertiary (LOW confidence)
- Claim that PostgreSQL and Oracle both allow ORDER BY expressions not in SELECT list — requires integration test verification

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all Calcite classes verified from jar; codebase read directly
- Architecture: HIGH — all existing pushdown rules read and understood; new patterns are direct extensions
- Pitfalls: HIGH — HAVING-as-WHERE bug discovered from direct code reading; all others derive from Calcite API behavior verified from jar
- Open questions: MEDIUM — integration test needed for EXTRACT and ORDER BY expression column trimming

**Research date:** 2026-03-16
**Valid until:** 2026-06-16 (calcite-core version is pinned; Dremio codebase is the limiting factor)

---

## Appendix: JdbcScanPrel Field Changes Summary

Current fields:
- `filterRex: RexNode` — WHERE condition (null if none)
- `collation: RelCollation` — ORDER BY sort (null if none)
- `limit: Integer` — LIMIT value (null if none)
- `groupSet: ImmutableBitSet` — GROUP BY columns (null if none)
- `aggCalls: List<AggregateCall>` — aggregate functions (null if none)
- `overrideRowType: RelDataType` — aggregated output type (null if none)
- `projectedColumns: List<SchemaPath>` — projected column names (null = SELECT *)

New fields for Phase 37:
- `havingRex: RexNode` — HAVING condition (null if none) — post-aggregate filter
- `projectExpressions: List<RexNode>` — project expressions when functions involved (null = use projectedColumns as simple refs)
- `projectOutputNames: List<String>` — output column names for projectExpressions (null when projectExpressions is null)
- `sortKeyExpressions: List<RexNode>` — sort key expressions when ORDER BY uses functions (null = use collation column indices directly)

New `getPhysicalOperator()` step order:
```
1.  Resolve dialect
2.  Create JdbcConvention + trait set
3.  Determine full table row type
4.  Build JdbcCalciteLeaf
5.  Build projToFull index mapping (for WHERE normalization)
6.  Wrap with JdbcFilter (WHERE) — only if filterRex != null AND !hasAggregation()
    OR: filterRex is placed here regardless; HAVING is separate (see step 8.5)
7.  Wrap with JdbcProject — use projectExpressions if non-null, else use projectedColumns
8.  Wrap with JdbcAggregate — if hasAggregation()
8.5 Wrap with JdbcFilter (HAVING) — if havingRex != null
9.  Wrap with JdbcSort — if collation != null or limit != null, using sortKeyExpressions
10. Render SQL via DremioJdbcImplementor
11. Resolve schema paths and output schema
```

## Appendix: New and Modified Rules Summary

| Rule | Phase | Pattern | Change |
|------|-------|---------|--------|
| `JdbcPushFilterIntoScan` | PHYSICAL | `FilterPrel(JdbcScanPrel[!hasAgg])` | Add `!scan.hasAggregation()` to `matches()` |
| `JdbcPushProjectIntoScan` | PHYSICAL | `ProjectPrel(JdbcScanPrel)` | Accept whitelisted function expressions, not just `RexInputRef` |
| `JdbcPushAggIntoScan` | PHYSICAL | `AggregatePrel(JdbcScanPrel)` | Remove `isDistinct()` blanket rejection; allow `COUNT(DISTINCT)` |
| `JdbcPushHavingIntoScan` | PHYSICAL | `FilterPrel(JdbcScanPrel[hasAgg, !hasHaving])` | NEW: push HAVING condition as `havingRex` |
| `JdbcPushSortWithExpressionsHep` | PHYSICAL_HEP | `SortPrel(ProjectPrel(JdbcScanPrel))` with function exprs | NEW: push sort key expressions from ProjectPrel + collation |
