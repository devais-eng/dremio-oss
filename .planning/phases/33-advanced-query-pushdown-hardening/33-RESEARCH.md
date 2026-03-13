# Phase 33: Advanced Query Pushdown Hardening - Research

**Researched:** 2026-03-13
**Domain:** Dremio JDBC plugin query pushdown (Calcite planner rules, SQL generation, PreparedStatement parameterization)
**Confidence:** HIGH

## Summary

This phase hardens and extends the JDBC pushdown infrastructure built in Phases 30-32. The current system pushes down WHERE (filter), projection (SELECT columns), and LIMIT to JDBC sources. Phase 33 adds PreparedStatement bind parameters for SQL injection safety, additional filter operators (IN, BETWEEN, arithmetic expressions, COALESCE, NULLIF), ORDER BY pushdown, TopN (ORDER BY + LIMIT combined), and aggregation pushdown (GROUP BY + COUNT/SUM/MIN/MAX/AVG).

The codebase is well-structured for extension. `JdbcScanPrel` is the central accumulator node that carries pushdown state (whereClause, limit, projectedColumns). New pushdown types require: (1) adding state fields to `JdbcScanPrel`, (2) adding clone helpers, (3) creating new `RelOptRule` subclasses that pattern-match Dremio physical plan nodes above `JdbcScanPrel`, (4) extending `SqlBuilder.buildSql()` to generate the new SQL clauses, and (5) registering the rules in `JdbcRulesFactory`. The pattern is identical to the existing filter/limit/projection pushdown rules.

**Primary recommendation:** Implement in three plans: (1) PreparedStatement bind parameters + RexToSqlString expression expansion (IN, BETWEEN, arithmetic, COALESCE, NULLIF); (2) ORDER BY + TopN pushdown; (3) aggregation pushdown (GROUP BY + aggregate functions). Each plan is self-contained and testable.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PUSH-01 | Aggregation pushdown (SUM, COUNT, AVG, MIN, MAX) | JdbcPushAggIntoScan rule matching HashAggPrel/StreamAggPrel above JdbcScanPrel; SqlBuilder generates GROUP BY + aggregate SELECT list |
| PUSH-02 | Sort pushdown (ORDER BY) | JdbcPushSortIntoScan rule matching SortPrel above JdbcScanPrel; SqlBuilder generates ORDER BY clause with field collation |
| PUSH-03 | TopN pushdown (ORDER BY + LIMIT combined) | JdbcPushTopNIntoScan rule matching LimitPrel(SortPrel(JdbcScanPrel)) or SortPrel above JdbcScanPrel that already has limit; combines sort + limit in SQL |
| [Hardening] PreparedStatement bind parameters | Refactor RexToSqlString to collect bind values into a List alongside SQL with ? placeholders; thread params through JdbcScanPrel -> JdbcGroupScan -> JdbcSubScan -> JdbcRecordReader -> stmt.setXxx() |
| [Pushdown] IN operator support | Calcite 1.22 decomposes IN to OR chains; handle SqlKind.IN if present and detect OR-of-EQUALS patterns; generate col IN (v1, v2, ...) SQL |
</phase_requirements>

## Standard Stack

### Core (Already in Codebase)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Apache Calcite | 1.22.0 (Dremio fork) | Planner framework, RexNode, RelOptRule, SqlKind | Foundation of all Dremio planning |
| Dremio Planner Physical | In-tree | SortPrel, LimitPrel, HashAggPrel, StreamAggPrel, TopNPrel | Physical plan nodes we match against |
| JDBC API | Java 11+ | PreparedStatement, ResultSetMetaData | Runtime query execution |

### No New Dependencies Required
This phase is pure planning-layer work. All needed APIs are already present in Calcite and the Dremio kernel.

## Architecture Patterns

### Current Infrastructure (Phase 30 Baseline)

```
JdbcScanPrel (physical scan node)
  - fields: schemaName, tableName, whereClause (String), limit (Integer)
  - clone helpers: cloneWithFilter(), cloneWithLimit(), cloneWithProject()
  - getPhysicalOperator() -> resolves SqlBuilder -> buildSql() -> JdbcGroupScan

SqlBuilder.buildSql(schema, table, projectedColumns, whereClause, limit) -> String
  - OracleSqlBuilder overrides for FETCH FIRST N ROWS ONLY

RexToSqlString (inner class of JdbcPushFilterIntoScan)
  - Converts RexNode -> SQL WHERE string
  - Supports: =, <>, <, <=, >, >=, AND, OR, NOT, IS NULL, IS NOT NULL, LIKE
  - Literals: inline string-escaped (no parameterization)
  - Returns null on unsupported -> filter stays in Dremio engine

JdbcRulesFactory registers rules in PHYSICAL phase:
  - JdbcScanPrule, JdbcPushFilterIntoScan, JdbcPushProjectIntoScan, JdbcPushLimitIntoScan
```

### Pattern 1: Adding New Pushdown State to JdbcScanPrel

**What:** Every new pushdown type requires a new field on JdbcScanPrel plus a cloneWithXxx() method.
**When to use:** Always, for each pushdown category.

```java
// Example: adding ORDER BY state
public class JdbcScanPrel extends ScanPrelBase {
  private final String whereClause;
  private final Integer limit;
  private final String orderByClause;  // NEW
  // ...

  public JdbcScanPrel cloneWithOrderBy(String newOrderBy) {
    return new JdbcScanPrel(/*...all existing fields...*/, newOrderBy);
  }
}
```

**Key constraint:** The constructor parameter list will grow. Each clone helper preserves all OTHER state. This is the same pattern used by existing cloneWithFilter(), cloneWithLimit(), cloneWithProject().

### Pattern 2: Pushdown Rule Structure

**What:** Each pushdown rule extends `RelOptRule`, matches a physical Prel above JdbcScanPrel, extracts information from the Prel, and transforms it to a new JdbcScanPrel with the state absorbed.
**When to use:** For every pushdown type.

```java
// Pattern: PrelType(JdbcScanPrel) -> JdbcScanPrel with absorbed state
public class JdbcPushSortIntoScan extends RelOptRule {
  public static final RelOptRule INSTANCE = new JdbcPushSortIntoScan();

  private JdbcPushSortIntoScan() {
    super(
        RelOptHelper.some(SortPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
        "JdbcPushSortIntoScan");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    JdbcScanPrel scan = call.rel(1);
    return !scan.hasOrderBy();  // Don't push a second sort
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    SortPrel sort = call.rel(0);
    JdbcScanPrel scan = call.rel(1);
    // Extract collation fields -> ORDER BY clause
    String orderBy = collationToSql(sort.getCollation(), scan.getRowType());
    if (orderBy == null) return;
    call.transformTo(scan.cloneWithOrderBy(orderBy));
  }
}
```

### Pattern 3: PreparedStatement Parameterization

**What:** Instead of inlining literal values in SQL strings, use `?` placeholders and pass a list of typed parameter values through the operator chain.
**When to use:** For the PreparedStatement bind parameter refactoring.

Current flow:
```
RexToSqlString -> "age > 25 AND name = 'Alice'"  (string-escaped literals)
SqlBuilder.buildSql() -> complete SQL string
JdbcGroupScan(sql) -> JdbcSubScan(sql) -> JdbcRecordReader
  conn.prepareStatement(sql) -> stmt.executeQuery()
```

New flow:
```
RexToSqlString -> { sql: "age > ? AND name = ?", params: [25, "Alice"] }
SqlBuilder.buildSql() -> complete SQL string with ? placeholders
JdbcGroupScan(sql, params) -> JdbcSubScan(sql, params) -> JdbcRecordReader
  conn.prepareStatement(sql) -> stmt.setInt(1, 25) -> stmt.setString(2, "Alice") -> stmt.executeQuery()
```

### Pattern 4: Aggregation Pushdown (Most Complex)

**What:** Match HashAggPrel or StreamAggPrel above JdbcScanPrel. Extract group-by keys and aggregate calls. Generate `SELECT group_cols, AGG(col) FROM ... GROUP BY group_cols`.
**When to use:** For PUSH-01.

**Critical complexity:** The aggregation changes the output schema. The JdbcScanPrel's rowType must be updated to reflect the aggregated output (group-by columns + aggregate result columns), not the original table columns. This means the pushdown rule must create a scan with a new rowType.

```java
// Conceptual: AggPrel above JdbcScanPrel
// Input: HashAggPrel(groupSet={0}, aggCalls=[COUNT(), SUM($1)])
//          JdbcScanPrel(table="t", cols=[name, amount])
// Output: JdbcScanPrel with aggState carrying:
//   SELECT "name", COUNT(*), SUM("amount") FROM "schema"."t" GROUP BY "name"
//   and a new rowType reflecting [name, count_result, sum_result]
```

### Pattern 5: TopN as Combined Sort+Limit

**What:** Match SortPrel(JdbcScanPrel) where scan already has a limit, OR match LimitPrel(SortPrel(JdbcScanPrel)). Push ORDER BY into scan alongside the limit.
**When to use:** For PUSH-03.

**In Dremio's physical planner**, TopN is created by `PushLimitToTopN` which matches `LimitPrel(SingleMergeExchangePrel(SortPrel))`. For JDBC, the scan has SINGLETON distribution, so no exchange is inserted. The planner tree for `ORDER BY col LIMIT 10` against a JDBC source will look like:

```
LimitPrel(fetch=10)
  SortPrel(collation=[$0 ASC])
    JdbcScanPrel(table)
```

Because JdbcScanPrel has SINGLETON distribution, LimitPrule requests SINGLETON on input, and SortPrule may either insert an exchange or not depending on single-mode detection. The simplest approach is two rules:
1. `JdbcPushSortIntoScan` absorbs SortPrel -> order by in scan
2. `JdbcPushLimitIntoScan` (existing) absorbs LimitPrel -> limit in scan
3. SqlBuilder combines ORDER BY + LIMIT in the correct SQL order

### Recommended Project Structure (New/Modified Files)

```
plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/
  RexToSqlString.java            # EXTRACT from JdbcPushFilterIntoScan, expand operators, add parameterization
  JdbcScanPrel.java              # ADD: orderByClause, aggregation state fields + clone helpers
  JdbcPushFilterIntoScan.java    # REFACTOR: use extracted RexToSqlString, pass params
  JdbcPushSortIntoScan.java      # NEW: absorb SortPrel -> ORDER BY
  JdbcPushAggIntoScan.java       # NEW: absorb HashAggPrel/StreamAggPrel -> GROUP BY + aggregates
  JdbcRulesFactory.java          # REGISTER new rules in PHYSICAL phase
  SqlBuilder.java                # ADD: orderByClause, groupByClause, aggregateFunctions params

plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/exec/
  JdbcGroupScan.java             # ADD: bindParams list
  JdbcSubScan.java               # ADD: bindParams list
  JdbcRecordReader.java          # ADD: stmt.setXxx() for bind params

plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/
  OracleSqlBuilder.java          # UPDATE: handle ORDER BY with FETCH FIRST syntax

plugins/jdbc-postgresql/src/test/java/
  TestPostgresPushdown.java      # EXPAND: tests for new pushdown types

plugins/jdbc-oracle/src/test/java/
  TestOraclePushdown.java        # EXPAND: tests for new pushdown types
```

### Anti-Patterns to Avoid

- **Hand-rolling SQL string concatenation for ORDER BY direction:** Use a structured approach (enum for ASC/DESC, null handling). Don't inline "ASC"/"DESC" strings without proper null-ordering handling (NULLS FIRST/NULLS LAST differs between PG and Oracle).
- **Pushing aggregation without verifying all agg functions are supported:** If one aggregate function is unsupported (e.g., COUNT(DISTINCT)), the entire aggregation must stay in Dremio. Partial pushdown of aggregates is incorrect.
- **Changing JdbcScanPrel's rowType without updating projectedColumns:** Aggregation changes the output schema. If you push SELECT SUM(x), the scan's rowType must reflect the new columns, and projectedColumns must be null/adjusted.
- **Forgetting PHASE_1of1 vs PHASE_1of2 for aggregation:** Dremio creates 2-phase aggregates (partial + final). Only push down PHASE_1of1 aggregates. If the aggregate is PHASE_1of2, it's a partial aggregate that cannot be pushed to the source.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SQL escaping for literals | Custom escaping logic | PreparedStatement `?` bind params | Structural SQL injection prevention; databases handle type coercion correctly |
| Collation direction strings | Inline "ASC"/"DESC" strings | Parse `RelFieldCollation.Direction` and `NullDirection` enums | Must handle ASCENDING, DESCENDING, CLUSTERED + FIRST, LAST, UNSPECIFIED null ordering |
| Aggregate function name mapping | Switch on function name strings | Map from `SqlKind` enum values | SqlKind.SUM, SqlKind.COUNT, etc. are stable API; function names vary |
| Oracle NULLS FIRST/LAST syntax | Separate Oracle sort handler | SqlBuilder.buildOrderByClause() override | Oracle and PG both support NULLS FIRST/LAST but have different defaults |

## Common Pitfalls

### Pitfall 1: Calcite 1.22 IN List Representation
**What goes wrong:** Assuming IN lists appear as `SqlKind.IN` nodes in the physical plan.
**Why it happens:** Dremio uses Calcite 1.22 (custom fork), which predates the SEARCH/Sarg optimization in Calcite 1.27+. In Calcite 1.22, `x IN (1, 2, 3)` is decomposed to `x = 1 OR x = 2 OR x = 3` at the logical planning stage.
**How to avoid:** Two approaches: (a) detect OR-of-EQUALS patterns where all comparisons reference the same column and reconstruct them as `col IN (v1, v2, v3)` for cleaner SQL; (b) handle `SqlKind.IN` in RexToSqlString anyway as a safety net, since Calcite _may_ preserve IN nodes in some code paths. The OR chain approach is the primary path.
**Warning signs:** Writing `case IN:` and it never matches in tests.

### Pitfall 2: Sort Pushdown with Exchange Nodes
**What goes wrong:** SortPrel is not directly above JdbcScanPrel; an exchange (SingleMergeExchangePrel) sits between them.
**Why it happens:** `SortPrule` may insert a `SingleMergeExchangePrel` to collect sorted data from distributed fragments. For SINGLETON scans, Volcano may still insert exchanges during plan optimization.
**How to avoid:** Register sort pushdown in both PHYSICAL and PHYSICAL_HEP phases (same pattern as limit pushdown). In PHYSICAL_HEP, the plan is more settled and exchanges may have been removed. Also, consider matching the 3-node pattern `SortPrel(ExchangePrel(JdbcScanPrel))` as an alternative.
**Warning signs:** Sort pushdown tests pass in isolation but the rule never fires in full Dremio execution.

### Pitfall 3: Aggregation Phase Confusion
**What goes wrong:** Pushing down a partial aggregate (PHASE_1of2) to the JDBC source, producing incorrect results.
**Why it happens:** `HashAggPrule` creates two-phase aggregates for distributed execution. PHASE_1of2 is a partial pre-aggregate that feeds into a PHASE_2of2 final aggregate. Only PHASE_1of1 (single-phase) should be pushed to JDBC.
**How to avoid:** In the `matches()` method, check if the aggregate is `HashAggPrel` or `StreamAggPrel` and inspect `getOperatorPhase() == PHASE_1of1`. Reject PHASE_1of2 and PHASE_2of2.
**Warning signs:** Aggregation results are wrong (partial counts, partial sums).

### Pitfall 4: COUNT(*) vs COUNT(col) Distinction
**What goes wrong:** Generating `COUNT("col")` when the aggregate is `COUNT(*)`, or vice versa.
**Why it happens:** In Calcite, `COUNT(*)` has `AggregateCall.getArgList()` empty (or containing a dummy literal), while `COUNT(col)` has the column index in the arg list.
**How to avoid:** Check `aggCall.getArgList().isEmpty()` for COUNT(*). For COUNT(col), resolve the column from the arg list index.
**Warning signs:** COUNT(*) returns incorrect results or SQL syntax error.

### Pitfall 5: Bind Parameter Ordering
**What goes wrong:** Bind parameter indices get out of sync between SQL `?` positions and `stmt.setXxx()` calls.
**Why it happens:** Complex WHERE clauses with AND/OR trees produce `?` markers in a depth-first traversal order. If the parameter list is built in a different order, values get assigned to wrong positions.
**How to avoid:** Use a single pass through the RexNode tree that simultaneously builds the SQL string and appends to the parameter list in order. The parameter list index always equals the count of `?` markers emitted so far.
**Warning signs:** Queries return wrong rows or type mismatch errors from the database.

### Pitfall 6: Oracle ORDER BY + FETCH FIRST Clause Order
**What goes wrong:** SQL syntax error in Oracle when ORDER BY and FETCH FIRST are in wrong order.
**Why it happens:** Oracle 12c+ syntax requires `ORDER BY col FETCH FIRST N ROWS ONLY` (ORDER BY before FETCH FIRST). The current OracleSqlBuilder doesn't have ORDER BY.
**How to avoid:** In SqlBuilder, always emit ORDER BY before LIMIT/FETCH FIRST. The method signature should accept both and order them correctly.
**Warning signs:** Oracle tests fail with ORA-00933 SQL command not properly ended.

### Pitfall 7: NULL Handling in ORDER BY
**What goes wrong:** Different null ordering between Dremio expectation and database default.
**Why it happens:** PostgreSQL default: NULLS LAST for ASC, NULLS FIRST for DESC. Oracle default: NULLS LAST for ASC, NULLS FIRST for DESC (same as PG but different from Calcite default). Calcite/Dremio has its own null ordering convention.
**How to avoid:** Always emit explicit `NULLS FIRST` or `NULLS LAST` in the ORDER BY clause based on the `RelFieldCollation.NullDirection` from the SortPrel.
**Warning signs:** Queries with NULL values return rows in unexpected order.

## Code Examples

### Example 1: Extracting Collation from SortPrel for ORDER BY

```java
// Source: Dremio codebase SortPrel.java / RelCollation API
private String collationToSql(RelCollation collation, RelDataType rowType) {
  List<RelDataTypeField> fields = rowType.getFieldList();
  StringBuilder sb = new StringBuilder();
  for (int i = 0; i < collation.getFieldCollations().size(); i++) {
    RelFieldCollation fc = collation.getFieldCollations().get(i);
    int fieldIndex = fc.getFieldIndex();
    if (fieldIndex < 0 || fieldIndex >= fields.size()) return null;

    if (i > 0) sb.append(", ");
    sb.append("\"").append(fields.get(fieldIndex).getName().replace("\"", "\"\"")).append("\"");

    // Direction
    switch (fc.getDirection()) {
      case ASCENDING:
      case STRICTLY_ASCENDING:
        sb.append(" ASC");
        break;
      case DESCENDING:
      case STRICTLY_DESCENDING:
        sb.append(" DESC");
        break;
      default:
        return null;  // CLUSTERED not pushable
    }

    // Null ordering
    switch (fc.nullDirection) {
      case FIRST:
        sb.append(" NULLS FIRST");
        break;
      case LAST:
        sb.append(" NULLS LAST");
        break;
      default:
        // UNSPECIFIED: use database default (don't emit)
        break;
    }
  }
  return sb.toString();
}
```

### Example 2: Extracting Aggregate Calls for GROUP BY + SELECT

```java
// Source: Calcite AggregateCall API + Dremio AggregatePrel
private AggPushdownResult extractAggregation(
    AggregatePrel agg, JdbcScanPrel scan) {

  // Only push single-phase aggregates
  if (agg.getOperatorPhase() != AggregatePrel.OperatorPhase.PHASE_1of1) {
    return null;
  }

  ImmutableBitSet groupSet = agg.getGroupSet();
  List<AggregateCall> aggCalls = agg.getAggCallList();
  RelDataType scanRowType = scan.getRowType();
  List<RelDataTypeField> scanFields = scanRowType.getFieldList();

  // Build GROUP BY column list
  List<String> groupByCols = new ArrayList<>();
  for (int idx : groupSet) {
    if (idx < 0 || idx >= scanFields.size()) return null;
    groupByCols.add(quoteId(scanFields.get(idx).getName()));
  }

  // Build aggregate SELECT expressions
  List<String> selectExprs = new ArrayList<>(groupByCols);
  for (AggregateCall call : aggCalls) {
    String aggSql = aggCallToSql(call, scanFields);
    if (aggSql == null) return null;  // Unsupported function
    selectExprs.add(aggSql);
  }

  return new AggPushdownResult(selectExprs, groupByCols);
}

private String aggCallToSql(AggregateCall call, List<RelDataTypeField> fields) {
  switch (call.getAggregation().getKind()) {
    case COUNT:
      if (call.getArgList().isEmpty()) return "COUNT(*)";
      return "COUNT(" + quoteId(fields.get(call.getArgList().get(0)).getName()) + ")";
    case SUM: case SUM0:
      return "SUM(" + quoteId(fields.get(call.getArgList().get(0)).getName()) + ")";
    case MIN:
      return "MIN(" + quoteId(fields.get(call.getArgList().get(0)).getName()) + ")";
    case MAX:
      return "MAX(" + quoteId(fields.get(call.getArgList().get(0)).getName()) + ")";
    case AVG:
      return "AVG(" + quoteId(fields.get(call.getArgList().get(0)).getName()) + ")";
    default:
      return null;  // Unsupported
  }
}
```

### Example 3: PreparedStatement Parameter Collection

```java
// New approach: RexToSqlString returns sql + params
public class RexToSqlResult {
  public final String sql;
  public final List<Object> params;  // ordered list of bind values
  public final List<SqlTypeName> paramTypes;  // for stmt.setXxx() dispatch

  RexToSqlResult(String sql, List<Object> params, List<SqlTypeName> paramTypes) {
    this.sql = sql;
    this.params = params;
    this.paramTypes = paramTypes;
  }
}

// In RexToSqlString:
private RexToSqlResult convertLiteral(RexLiteral literal) {
  if (RexLiteral.isNullLiteral(literal)) {
    return new RexToSqlResult("NULL", Collections.emptyList(), Collections.emptyList());
  }
  SqlTypeName typeName = literal.getType().getSqlTypeName();
  // Instead of inlining the value, emit ? and collect the value
  Object value = extractTypedValue(literal);
  return new RexToSqlResult("?",
      Collections.singletonList(value),
      Collections.singletonList(typeName));
}

// In JdbcRecordReader.setup():
stmt = conn.prepareStatement(config.getSql(), ...);
for (int i = 0; i < config.getBindParams().size(); i++) {
  setParameter(stmt, i + 1, config.getBindParams().get(i), config.getParamTypes().get(i));
}
rs = stmt.executeQuery();
```

### Example 4: RexToSqlString Expanded Operator Support

```java
// Additional cases in convertCall() for Phase 33
case IN: {
  // Safety net: Calcite 1.22 usually decomposes IN to OR, but handle if present
  if (operands.size() < 2) return null;
  String col = convert(operands.get(0));
  if (col == null) return null;
  StringBuilder sb = new StringBuilder(col).append(" IN (");
  for (int i = 1; i < operands.size(); i++) {
    if (i > 1) sb.append(", ");
    String val = convert(operands.get(i));
    if (val == null) return null;
    sb.append(val);
  }
  sb.append(")");
  return sb.toString();
}

case BETWEEN: {
  if (operands.size() != 3) return null;
  String col = convert(operands.get(0));
  String lo = convert(operands.get(1));
  String hi = convert(operands.get(2));
  if (col == null || lo == null || hi == null) return null;
  return col + " BETWEEN " + lo + " AND " + hi;
}

case PLUS:
  return binaryOp(operands, "+");
case MINUS:
  return binaryOp(operands, "-");
case TIMES:
  return binaryOp(operands, "*");
case DIVIDE:
  return binaryOp(operands, "/");

case COALESCE: {
  StringBuilder sb = new StringBuilder("COALESCE(");
  for (int i = 0; i < operands.size(); i++) {
    if (i > 0) sb.append(", ");
    String arg = convert(operands.get(i));
    if (arg == null) return null;
    sb.append(arg);
  }
  sb.append(")");
  return sb.toString();
}

// NULLIF appears as SqlKind.NULLIF in Calcite:
case NULLIF: {
  if (operands.size() != 2) return null;
  String a = convert(operands.get(0));
  String b = convert(operands.get(1));
  if (a == null || b == null) return null;
  return "NULLIF(" + a + ", " + b + ")";
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| String-escaped literals in WHERE | PreparedStatement bind params (?) | Phase 33 | Eliminates SQL injection structurally |
| No ORDER BY pushdown | ORDER BY + NULLS FIRST/LAST | Phase 33 | Remote sort reduces data transfer |
| No aggregation pushdown | GROUP BY + COUNT/SUM/MIN/MAX/AVG | Phase 33 | Massive reduction in data transfer for analytic queries |
| Limited WHERE operators | IN, BETWEEN, arithmetic, COALESCE, NULLIF, CAST | Phase 33 | More filters pushed to source |

**What Trino does that we skip:**
- JOIN pushdown (same-source table co-location detection) -- Tier 2, deferred
- COUNT(DISTINCT) -- Tier 2, deferred
- Window functions -- Tier 3, nobody does it
- Subqueries -- Tier 3
- CASE/IF -- Tier 3

## Detailed Design Decisions

### 1. PreparedStatement Bind Parameters

**Approach:** The RexToSqlString visitor produces a `RexToSqlResult` containing the SQL string (with `?` placeholders) and an ordered list of parameter values + types. This result flows through:

1. `JdbcPushFilterIntoScan.onMatch()` gets `RexToSqlResult` instead of `String`
2. `JdbcScanPrel` stores both `whereClause` (String with ?) and `bindParams` (List<Object>) and `paramTypes` (List<SqlTypeName>)
3. `getPhysicalOperator()` passes them to `JdbcGroupScan`
4. `JdbcGroupScan` serializes them to `JdbcSubScan`
5. `JdbcRecordReader.setup()` calls `stmt.setXxx()` for each param before `executeQuery()`

**Serialization concern:** `bindParams` must be JSON-serializable. Use boxed Java types (Integer, Long, Double, String, Boolean, java.sql.Date/Time/Timestamp as strings with type tag). The `@JsonTypeInfo` annotation or a wrapper class can preserve type information across serialization.

**Alternative considered:** Keep string-escaping for non-string types and only parameterize strings. Rejected because parameterization is the industry standard and prevents all literal-based injection.

### 2. SqlBuilder Signature Evolution

Current:
```java
public String buildSql(String schemaName, String tableName,
    List<SchemaPath> projectedColumns, String whereClause, Integer limit)
```

New:
```java
public String buildSql(String schemaName, String tableName,
    List<SchemaPath> projectedColumns, String whereClause,
    String orderByClause, Integer limit)
// OR for aggregation:
public String buildSql(String schemaName, String tableName,
    List<String> selectExprs, String whereClause,
    String groupByClause, String orderByClause, Integer limit)
```

**Recommendation:** Rather than adding parameters one by one, introduce a `SqlBuildRequest` (or similar DTO) that encapsulates all pushdown components. This avoids method signature bloat and makes OracleSqlBuilder overrides cleaner.

```java
public class SqlBuildRequest {
  String schemaName;
  String tableName;
  List<SchemaPath> projectedColumns;  // null when aggregation used
  List<String> selectExprs;           // null when simple projection
  String whereClause;
  String groupByClause;
  String orderByClause;
  Integer limit;
  List<Object> bindParams;
  List<SqlTypeName> paramTypes;
}
```

### 3. ORDER BY Clause SQL Generation

SQL order: `SELECT ... FROM ... WHERE ... GROUP BY ... ORDER BY ... LIMIT/FETCH`

Oracle: `SELECT ... FROM ... WHERE ... GROUP BY ... ORDER BY ... FETCH FIRST N ROWS ONLY`
PostgreSQL: `SELECT ... FROM ... WHERE ... GROUP BY ... ORDER BY ... LIMIT N`

Both PostgreSQL and Oracle support `NULLS FIRST` / `NULLS LAST`. Always emit explicit null ordering to avoid cross-database behavioral differences.

### 4. Aggregation RowType Handling

When pushing aggregation, the JdbcScanPrel's output rowType changes from the table schema to the aggregated schema. Options:

**Option A (recommended):** Create a new JdbcScanPrel with a custom rowType derived from the group-by columns and aggregate result types. The aggregate result column names should use synthetic names (e.g., `EXPR$0`, `EXPR$1`) matching what Calcite expects.

**Option B:** Create a JdbcAggScanPrel subclass that extends JdbcScanPrel and overrides getRowType(). More code, less cohesive.

Recommendation: Option A -- keep single JdbcScanPrel class, add `aggSelectExprs` and `groupByClause` fields, and when those are set, buildSql uses them instead of projectedColumns.

### 5. Aggregation vs. Filter Interaction

Aggregation must absorb the filter if one exists. The SQL generation order is:
```sql
SELECT group_cols, AGG(cols)
FROM schema.table
WHERE filter            -- from prior filter pushdown
GROUP BY group_cols
ORDER BY sort_cols      -- from sort pushdown
LIMIT N                 -- from limit pushdown
```

If the scan already has a WHERE clause when aggregation is pushed, that's fine -- the WHERE is applied before GROUP BY, which is correct SQL semantics.

### 6. Calcite 1.22 IN List Detection

In Calcite 1.22, `x IN (1, 2, 3)` is rewritten to `x = 1 OR x = 2 OR x = 3` during SQL-to-RelNode conversion. To generate cleaner IN-list SQL:

**Detection strategy:** When visiting an OR node, check if all children are EQUALS calls with the same column reference on one side. If so, reconstruct as `col IN (v1, v2, v3)`.

```java
// In RexToSqlString.convertCall() for OR:
case OR:
  // Try to detect IN pattern first
  String inClause = tryConvertToIn(operands);
  if (inClause != null) return inClause;
  // Fallback to standard OR
  return nAryOp(operands, "OR");
```

This is an optimization, not a correctness issue. The OR chain works fine but generates less efficient SQL.

## Open Questions

1. **CAST pushdown dialect differences**
   - What we know: PostgreSQL uses `CAST(x AS type)` standard syntax; Oracle uses both CAST and TO_NUMBER/TO_CHAR/TO_DATE
   - What's unclear: Which CAST targets are safe to push (VARCHAR/INT/DECIMAL/DATE are likely safe; complex types are not)
   - Recommendation: Start with no CAST pushdown. Add it as a follow-up if real-world queries need it. RexToSqlString returns null for CAST nodes initially.

2. **Aggregation pushdown when scan already has projection**
   - What we know: If projection pushdown already narrowed the SELECT list, aggregation must work with those columns
   - What's unclear: Whether Calcite/Dremio always places the aggregation above the projection or vice versa
   - Recommendation: In practice, the physical plan is `AggPrel(ProjectPrel(JdbcScanPrel))` or `AggPrel(JdbcScanPrel-with-projection)`. If the scan already has projection, the aggregate column indices should align with the projected columns. Test this carefully.

3. **DISTINCT handling in aggregation**
   - What we know: `COUNT(DISTINCT col)` is a common operation. Dremio's `MoreRelOptUtil.containsUnsupportedDistinctCall()` rejects some DISTINCT aggregates for 2-phase planning.
   - What's unclear: Whether COUNT(DISTINCT) appears as a single-phase aggregate with isDistinct()=true
   - Recommendation: Initially reject all DISTINCT aggregates (return null from pushdown). Add COUNT(DISTINCT) support later after validating the plan structure.

## Planner Node Reference

Key Dremio physical plan nodes relevant to pushdown rules:

| Node | Class | Key Methods | Matching Pattern |
|------|-------|-------------|-----------------|
| Filter | `FilterPrel` | `getCondition()` -> RexNode | `FilterPrel(JdbcScanPrel)` |
| Projection | `ProjectPrel` | `getProjects()` -> List<RexNode> | `ProjectPrel(JdbcScanPrel)` |
| Limit | `LimitPrel` | `getFetch()`, `getOffset()` -> RexNode | `LimitPrel(JdbcScanPrel)` |
| Sort | `SortPrel` | `getCollation()` -> RelCollation | `SortPrel(JdbcScanPrel)` |
| HashAggregate | `HashAggPrel` | `getGroupSet()`, `getAggCallList()`, `getOperatorPhase()` | `HashAggPrel(JdbcScanPrel)` |
| StreamAggregate | `StreamAggPrel` | same as HashAggPrel | `StreamAggPrel(JdbcScanPrel)` |
| TopN | `TopNPrel` | `limit`, `collation` | Created by PushLimitToTopN; not directly relevant for JDBC pushdown |

**RelFieldCollation key enums:**
- `Direction`: ASCENDING, DESCENDING, STRICTLY_ASCENDING, STRICTLY_DESCENDING, CLUSTERED
- `NullDirection`: FIRST, LAST, UNSPECIFIED

**AggregateCall key methods:**
- `getAggregation().getKind()` -> SqlKind (COUNT, SUM, MIN, MAX, AVG, etc.)
- `getArgList()` -> List<Integer> (column indices; empty for COUNT(*))
- `isDistinct()` -> boolean
- `isApproximate()` -> boolean
- `getName()` -> String (output field name)

## Sources

### Primary (HIGH confidence)
- `plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/` -- all existing pushdown infrastructure
- `sabot/kernel/src/main/java/com/dremio/exec/planner/physical/SortPrel.java` -- sort physical node
- `sabot/kernel/src/main/java/com/dremio/exec/planner/physical/HashAggPrel.java` -- hash aggregate physical node
- `sabot/kernel/src/main/java/com/dremio/exec/planner/physical/AggregatePrel.java` -- aggregate base with OperatorPhase enum
- `sabot/kernel/src/main/java/com/dremio/exec/planner/physical/TopNPrel.java` -- TopN physical node
- `sabot/kernel/src/main/java/com/dremio/exec/planner/physical/PushLimitToTopN.java` -- Dremio's TopN creation pattern
- `sabot/kernel/src/main/java/com/dremio/exec/planner/physical/LimitPrule.java` -- Limit conversion (enforces SINGLETON)
- `sabot/kernel/src/main/java/com/dremio/exec/planner/physical/SortPrule.java` -- Sort conversion
- `sabot/kernel/src/main/java/com/dremio/exec/planner/physical/HashAggPrule.java` -- Aggregate conversion (2-phase creation)
- Calcite version: 1.22.0 (custom Dremio fork) -- confirmed in pom.xml

### Secondary (MEDIUM confidence)
- Trino JDBC connector framework design patterns (referenced from project memory, not directly verified in this session)
- Calcite SqlKind enum for operator support (verified against codebase usage)

## Metadata

**Confidence breakdown:**
- PreparedStatement refactoring: HIGH -- clear codebase path, well-understood Java/JDBC pattern
- RexToSqlString expansion: HIGH -- existing code in JdbcPushFilterIntoScan is the template; adding switch cases
- ORDER BY pushdown: HIGH -- SortPrel structure and RelCollation API verified in codebase
- TopN pushdown: HIGH -- combination of existing sort + limit pushdown; plan tree verified
- Aggregation pushdown: MEDIUM -- most complex; RowType transformation and AggregateCall parsing need careful implementation. OperatorPhase check is critical.
- IN list detection: MEDIUM -- Calcite 1.22 behavior (OR decomposition) confirmed but OR-to-IN reconstruction is a heuristic

**Research date:** 2026-03-13
**Valid until:** 2026-04-13 (stable -- Dremio Calcite fork and planner APIs change infrequently)
