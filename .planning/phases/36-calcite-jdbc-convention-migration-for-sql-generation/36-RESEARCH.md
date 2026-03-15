# Phase 36: Calcite JDBC Convention Migration for SQL Generation - Research

**Researched:** 2026-03-15
**Domain:** Calcite JDBC convention (adapter/jdbc), RelNode-to-SQL translation, Dremio JDBC pushdown pipeline
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| CALCITE-01 | Replace manual SqlBuilder SQL string generation with Calcite's JDBC convention (JdbcConvention) where the entire pushdown subtree is represented as Calcite JDBC adapter nodes and Calcite renders the final SQL via SqlDialect. Eliminates manual SQL construction, column aliasing bugs, and dialect-specific overrides. | Full architecture documented: JdbcImplementor, SqlDialect (PostgresqlSqlDialect, OracleSqlDialect), JdbcRules (JdbcFilter/Project/Join/Sort/Aggregate), all present in calcite-core 1.22.0 jar. Kernel hook point (DrelTransformer.pushDownJdbcQuery) identified. Plugin-side new Prel (JdbcCalciteSubtreePrel) design documented. |

</phase_requirements>

---

## Summary

Phase 36 migrates the manual `SqlBuilder` SQL string generation in the JDBC plugin to Calcite's built-in `JdbcImplementor` + `SqlDialect` pipeline. Currently, each pushdown rule (`JdbcPushFilterIntoScan`, `JdbcPushAggIntoScan`, etc.) converts Calcite `RexNode` trees to SQL strings by hand via `RexToSqlString` and `SqlBuilder`, storing those strings as fields on `JdbcScanPrel`. At `getPhysicalOperator()` time, `SqlBuilder.buildSql()` stitches the pieces together. This approach has produced aliasing bugs (self-join column dedup), dialect fragmentation (OracleSqlBuilder subclassing), and SQL injection surface (strings are concatenated, not AST-rendered).

The migration replaces that pipeline with Calcite's production-grade `adapter/jdbc` stack. Instead of accumulating SQL strings on `JdbcScanPrel`, the pushdown rules will build a proper Calcite rel-tree of `JdbcRules.JdbcFilter`, `JdbcRules.JdbcProject`, `JdbcRules.JdbcJoin`, `JdbcRules.JdbcSort`, `JdbcRules.JdbcAggregate` nodes under a `JdbcTableScan`-equivalent leaf. A new physical leaf node `JdbcCalciteSubtreePrel` will hold that rel-tree root. At `getPhysicalOperator()` time, `JdbcImplementor.implement(relRoot)` converts the entire subtree to a `SqlSelect` AST, and `result.asStatement().toSqlString(dialect)` renders the final SQL string.

The kernel change is minimal: `DrelTransformer.pushDownJdbcQuery()` already wraps scan nodes in `JdbcCrel` for the RELATIONAL_PLANNING phase; the ~10-line addition registers a new conversion rule in PHYSICAL phase that promotes a `JdbcScanDrel` (with its planned relational context) to a Calcite JDBC convention tree before Dremio's physical pushdown rules run. All existing Prel-level rules and the `JdbcGroupScan`/execution layer stay unchanged -- only the SQL string origin changes.

**Primary recommendation:** Build `JdbcCalciteSubtreePrel` (a new physical leaf) that owns a `JdbcRel` subtree root and a `SqlDialect`. At `getPhysicalOperator()` time, use `new JdbcImplementor(dialect, typeFactory).implement(subtreeRoot).asStatement().toSqlString(dialect).getSql()` to render the SQL. All existing pushdown rules target this new prel and build `JdbcRules.*` nodes rather than string fragments.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `calcite-core` (adapter/jdbc) | 1.22.0-202501311721330426-8b30dab9 | JdbcConvention, JdbcImplementor, JdbcRules.*, JdbcRel | Already on classpath via `dremio-sabot-kernel` dependency; production-quality SQL AST rendering |
| `calcite-core` (sql/dialect) | same | PostgresqlSqlDialect, OracleSqlDialect, SqlDialect | Dialect-aware identifier quoting, FETCH FIRST vs LIMIT, AS keyword presence — all built-in |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.apache.calcite.rel.rel2sql.RelToSqlConverter` | same | Alternative to JdbcImplementor for non-JDBC trees | NOT used here — JdbcImplementor is the right entry for JdbcRel subtrees |
| `DremioRelToSqlConverter` (kernel) | in-codebase | Dremio's own RelToSql (used for materialization/explain) | NOT used for JDBC pushdown — has Dremio-internal concerns |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JdbcImplementor (Calcite) | DremioRelToSqlConverter | DremioRelToSqlConverter is tuned for Dremio internal AST (DremioRelNode); JdbcImplementor is built for exactly the JdbcRel hierarchy produced by JdbcRules |
| Full convention-based planning (register JdbcConvention in Volcano) | Manual rel-tree construction in new Prel | Convention-based planning requires Volcano to see and propagate JdbcConvention trait — too deep a kernel change. Manual construction in `JdbcCalciteSubtreePrel.getPhysicalOperator()` uses same classes without modifying the planner. |

**No Maven additions needed.** All required classes (`JdbcConvention`, `JdbcImplementor`, `JdbcRules.*`, `PostgresqlSqlDialect`, `OracleSqlDialect`) are already present in `calcite-core-1.22.0-*` which is already on the classpath.

---

## Architecture Patterns

### Current Architecture (to be replaced)

```
JdbcScanPrel {
  schemaName, tableName,
  whereClause: String,      ← RexToSqlString output
  bindParams: List<BindParam>,
  selectExprs: List<String>, ← hand-built agg strings
  groupByClause: String,
  orderByClause: String,
  limit: Integer,
  overrideRowType
}
  └─ getPhysicalOperator() → SqlBuilder.buildSql() → String → JdbcGroupScan(sql)

JdbcJoinScanPrel {
  leftSchema/Table, rightSchema/Table,
  onClauseSql: String,       ← RexToJoinSqlString output
  conditionBindParams,
  leftColumns, rightColumns, outputRowType
}
  └─ getPhysicalOperator() → SqlBuilder.buildJoinSql() → String → JdbcGroupScan(sql)
```

### Target Architecture (Phase 36)

```
JdbcCalciteSubtreePrel {
  relRoot: RelNode,           ← JdbcRules.JdbcFilter/Project/Join/Sort/Aggregate subtree
  dialect: SqlDialect,        ← PostgresqlSqlDialect or OracleSqlDialect
  pluginId: StoragePluginId,
  outputRowType: RelDataType,
  bindParams: List<BindParam> ← still needed for prepared statement execution
}
  └─ getPhysicalOperator() →
       JdbcImplementor(dialect, typeFactory).implement(relRoot)
         .asStatement().toSqlString(dialect).getSql()
       → JdbcGroupScan(sql)
```

### Recommended Project Structure (new files)

```
plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/planning/
├── JdbcCalciteSubtreePrel.java      # NEW: leaf Prel holding Calcite JdbcRel tree
├── JdbcCalciteLeaf.java             # NEW: JdbcTableScan analog (JdbcRel leaf for our tables)
├── JdbcDialectProvider.java         # NEW: interface: SqlDialect createDialect()
└── (existing files stay, SqlBuilder kept but no longer called from Prune pipeline)

plugins/jdbc-base/src/main/java/com/dremio/plugins/jdbc/
└── JdbcStoragePlugin.java           # Add: SqlDialect createDialect() (replaces createSqlBuilder())

plugins/jdbc-oracle/src/main/java/com/dremio/plugins/jdbc/oracle/
└── OracleConf.java / OraclePlugin   # Override createDialect() → OracleSqlDialect.DEFAULT

plugins/jdbc-postgresql/src/main/java/com/dremio/plugins/jdbc/postgresql/
└── PostgresConf.java / PostgresPlugin  # Override createDialect() → PostgresqlSqlDialect.DEFAULT
```

### Pattern 1: Building a JdbcRel Subtree (single-table case)

The leaf node for `JdbcRel` subtrees must implement `org.apache.calcite.adapter.jdbc.JdbcRel`. There is no stock `JdbcTableScan` in the Calcite adapter that we can use without a `JdbcSchema` (which needs a real DataSource). Instead, create a minimal `JdbcCalciteLeaf` that extends `TableScan` and implements `JdbcRel`:

```java
// Source: Calcite JdbcRules.java pattern (adapter/jdbc)
public class JdbcCalciteLeaf extends TableScan implements JdbcRel {
  private final JdbcConvention convention;
  private final String schemaName;
  private final String tableName;

  // implement(JdbcImplementor) is the key method:
  @Override
  public Result implement(JdbcImplementor implementor) {
    // Produce: SELECT * FROM "schema"."table"
    return implementor.result(
        new SqlIdentifier(
            ImmutableList.of(schemaName, tableName), SqlParserPos.ZERO),
        ImmutableList.of(SqlImplementor.Clause.FROM),
        this,
        null);
  }
}
```

### Pattern 2: JdbcImplementor Usage

```java
// Source: Calcite JdbcImplementor.implement() API
JdbcConvention convention = JdbcConvention.of(dialect, null, "JDBC");
// Build subtree: JdbcFilter(JdbcProject(JdbcCalciteLeaf))
RelNode subtreeRoot = new JdbcRules.JdbcFilter(
    cluster, traitSet.replace(convention),
    new JdbcRules.JdbcProject(cluster, traitSet.replace(convention), leafNode, projects, rowType),
    filterCondition);

// Render SQL:
JdbcImplementor implementor = new JdbcImplementor(dialect, (JavaTypeFactory) cluster.getTypeFactory());
JdbcImplementor.Result result = implementor.implement(subtreeRoot);
String sql = result.asStatement().toSqlString(dialect).getSql();
```

### Pattern 3: SqlDialect for PostgreSQL vs Oracle

```java
// PostgreSQL: standard LIMIT, AS keyword present, double-quote identifiers
SqlDialect pgDialect = PostgresqlSqlDialect.DEFAULT;
// pgDialect.allowsAs() == true (FROM t AS alias)
// pgDialect renders: LIMIT N

// Oracle: FETCH FIRST N ROWS ONLY, no AS keyword in FROM
SqlDialect oracleDialect = OracleSqlDialect.DEFAULT;
// oracleDialect.allowsAs() == false (FROM t alias, no AS)
// OracleSqlDialect.unparseOffsetFetch() → FETCH FIRST N ROWS ONLY
```

**Confidence (HIGH):** Verified directly from Calcite jar — `OracleSqlDialect.allowsAs()` is an override returning `false`. `PostgresqlSqlDialect` does not override `allowsAs()` so it inherits the standard behavior (`true`). Both are verified as concrete classes in the `calcite-core-1.22.0-*` jar.

### Pattern 4: Kernel Hook — ~10 Lines in DrelTransformer

The goal is to hook into `pushDownJdbcQuery()` to add a PHYSICAL-phase conversion rule that, when the planner encounters `JdbcScanDrel`, converts it to a `JdbcCalciteSubtreePrel` root node (with no pushdown state yet). The subsequent `JdbcPushFilterIntoScan`, `JdbcPushProjectIntoScan`, etc. rules then operate on this new prel and build `JdbcRules.*` nodes instead of accumulating strings.

Alternatively — and simpler for minimal kernel change — the hook is purely in `JdbcScanPrule`. `JdbcScanPrule` converts `JdbcScanDrel` → `JdbcScanPrel` today. We can either:

1. **Keep `JdbcScanPrel` as an intermediate** and replace its `getPhysicalOperator()` to build the Calcite subtree at call time (fewest changes, no new Prel).
2. **Introduce `JdbcCalciteSubtreePrel`** as a new physical node produced by `JdbcScanPrule`, and have all pushdown rules target it (cleanest, most future-proof).

Option 1 (in-place `JdbcScanPrel` rewrite) minimizes new files and kernel changes. It means:
- `JdbcScanPrel.getPhysicalOperator()` builds a `JdbcRules.*` tree from its stored fields and calls `JdbcImplementor`.
- No structural change to `JdbcScanPrel` fields (or a gradual migration).
- `JdbcJoinScanPrel.getPhysicalOperator()` does the same for joins.

Option 2 is cleaner architecturally but requires more new code.

**Recommendation: Option 1 for Phase 36.** Rewrite `getPhysicalOperator()` in both `JdbcScanPrel` and `JdbcJoinScanPrel` to use `JdbcImplementor` while keeping all existing pushdown rule logic and prel fields. This is purely additive at the SQL generation boundary. No kernel change needed at all — the pushDownJdbcQuery pipeline and RELATIONAL_PLANNING phase are untouched.

### Pattern 5: The Actual ~10-line Kernel Hook (if needed)

If the user note "~10 lines in DrelTransformer" is a requirement (not just a description of minimal impact), the hook would be a new PHYSICAL conversion rule registered in `JdbcRulesFactory` that promotes each `JdbcScanPrel` to a new convention. But given "minimal changes to sabot/kernel" is the stated user constraint, **no kernel change is needed for the in-place getPhysicalOperator() rewrite pattern**.

### Anti-Patterns to Avoid

- **Using `DremioRelToSqlConverter` instead of `JdbcImplementor`:** DremioRelToSqlConverter does not handle `JdbcRel` implementations. It is for Dremio logical/physical rel trees. `JdbcImplementor` is the correct entry point.
- **Trying to use `JdbcSchema` + `JdbcTable`:** These require a real JDBC `DataSource` and schema wiring. We do not have a Calcite `JdbcSchema` object. Use direct `JdbcRules.*` node construction instead.
- **Reusing `RexToSqlString` output as bind params with Calcite SQL rendering:** Calcite renders literals inline in the SQL (no `?` placeholders). Phase 36 must decide: either use inline literals (no prepared statement), or keep `RexToSqlString` for WHERE clause generation and only use `JdbcImplementor` for structural composition (SELECT, FROM, JOIN, GROUP BY, ORDER BY, LIMIT/FETCH FIRST).
- **Mixing Calcite SQL rendering with manual string concatenation:** Once you call `JdbcImplementor`, let it own the entire SQL. Don't patch the output string after the fact.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SQL identifier quoting | `quoteIdentifier(id)` method in SqlBuilder | `SqlDialect.quoteIdentifier()` | Handles edge cases (embedded quotes, case sensitivity per dialect) |
| LIMIT vs FETCH FIRST dialect difference | OracleSqlBuilder.appendLimit() | OracleSqlDialect auto-renders `FETCH FIRST N ROWS ONLY` | Built into Calcite's OracleSqlDialect.unparseOffsetFetch() |
| AS keyword for table aliases | OracleSqlBuilder.buildJoinSql() override | OracleSqlDialect.allowsAs() == false | JdbcImplementor checks allowsAs() automatically |
| JOIN SQL column aliasing for self-joins | Manual dedup alias logic in SqlBuilder.buildJoinSql() | JdbcImplementor handles column names via row type field names | Calcite manages namespace and aliasing transparently |
| SELECT * vs explicit columns | Branch in SqlBuilder.appendSelectList() | JdbcRules.JdbcProject with identity projections or explicit column list | Cleaner: if no projection, use no JdbcProject node |
| GROUP BY + aggregate SELECT | Manual string building in JdbcPushAggIntoScan | JdbcRules.JdbcAggregate with AggregateCall list | Calcite renders COUNT(*), SUM("col") correctly per dialect |
| ORDER BY with NULLS FIRST/LAST | Manual string building in JdbcPushSortIntoScan | JdbcRules.JdbcSort with RelCollation | Calcite renders collation correctly per dialect |

**Key insight:** The entire manual SQL-building layer in `SqlBuilder`, `OracleSqlBuilder`, `RexToSqlString`, `RexToJoinSqlString`, and `LiteralInliner` exists to solve problems that Calcite's `JdbcImplementor` + `SqlDialect` already solve. The migration eliminates this layer.

---

## Common Pitfalls

### Pitfall 1: Bind Parameters vs Inline Literals

**What goes wrong:** `JdbcImplementor` renders literals inline (e.g., `WHERE age > 25`), not as `?` placeholders. The current `JdbcRecordReader` and `AdbcRecordReader` use `PreparedStatement.setXxx()` with `BindParam` list. If Phase 36 switches to inline literals, the reader must use `Statement.execute(sql)` instead of prepared statements.

**Why it happens:** Calcite's RelToSql converters are designed for one-shot SQL generation, not prepared statement templates. The `?` placeholder pattern was a custom choice in the current `RexToSqlString` implementation.

**How to avoid:** Two options:
1. **Use inline literals throughout** (simplest): `JdbcImplementor` renders the full SQL with literals inlined. Remove `BindParam` from the execution path. This is safe because Calcite renders AST nodes (not string interpolation), so no SQL injection risk.
2. **Keep bind params for WHERE only**: Keep `RexToSqlString` for filter condition generation (retains prepared statement safety), and use `JdbcImplementor` only for structural composition (FROM, GROUP BY, JOIN, ORDER BY, LIMIT). This is a hybrid approach — more complex but preserves prepared statement benefits.

**Recommendation:** Use inline literals (option 1) since the phase description explicitly states "No SQL injection risk — Calcite renders AST, never string-interpolates." This is the clean migration.

**Warning signs:** If `JdbcGroupScan.bindParams` list is non-empty after migration, something is wrong.

### Pitfall 2: JdbcImplementor Requires JdbcRel Nodes

**What goes wrong:** `JdbcImplementor.implement()` dispatches to `JdbcRel.implement(JdbcImplementor)`. If any node in the subtree does not implement `JdbcRel`, it will fail with a ClassCastException or `NullPointerException`.

**Why it happens:** `JdbcImplementor` extends `RelToSqlConverter` but overrides `dispatch()` to expect `JdbcRel` instances. Standard Calcite logical nodes (LogicalFilter, LogicalProject) do not implement `JdbcRel`.

**How to avoid:** Every node in the subtree passed to `JdbcImplementor.implement()` must be a `JdbcRules.*` node (or your own `JdbcRel` implementation). The leaf node must implement `JdbcRel.implement()` to produce the base `FROM` clause.

**Warning signs:** `ClassCastException: LogicalFilter cannot be cast to JdbcRel`.

### Pitfall 3: JdbcConvention Trait is Required

**What goes wrong:** `JdbcRules.JdbcFilter` constructor takes `RelTraitSet traitSet`. This trait set MUST include a `JdbcConvention` trait (via `traitSet.replace(convention)`). If you pass the Dremio `Prel.PHYSICAL` trait set unchanged, Calcite's convention-checking may reject it.

**Why it happens:** Each `JdbcRules.*` node checks its convention at construction time and in `copy()`.

**How to avoid:** Create a `JdbcConvention.of(dialect, null, "DREMIO_JDBC")` once per plugin and use `baseTraitSet.replace(convention)` when constructing all `JdbcRules.*` nodes. The convention object only needs to carry the `SqlDialect` — the `expression` parameter can be null (it's only used in Calcite's Linq4j enumerable path which we don't use).

**Warning signs:** `AssertionError` or convention mismatch in `JdbcRules.*` constructor.

### Pitfall 4: JavaTypeFactory Required by JdbcImplementor

**What goes wrong:** `JdbcImplementor(SqlDialect, JavaTypeFactory)` requires a `JavaTypeFactory`. In Dremio, `cluster.getTypeFactory()` returns `RelDataTypeFactory`, not `JavaTypeFactory`.

**Why it happens:** Calcite's `JdbcImplementor` inherits from `SqlImplementor` which uses `JavaTypeFactory` for type system operations.

**How to avoid:** Dremio's planner context provides a `JavaTypeFactory`. Access it via: `(JavaTypeFactory) cluster.getTypeFactory()` — Dremio uses `JavaTypeFactoryImpl` under the hood so the cast is safe. Alternatively, during `getPhysicalOperator(PhysicalPlanCreator creator)`, use `creator.getContext().getPlannerSettings()...` to find the right typeFactory. Check `PlannerCatalogImpl` for the actual factory type.

**Warning signs:** `ClassCastException: RelDataTypeFactoryImpl cannot be cast to JavaTypeFactory`.

### Pitfall 5: PostgresqlSqlDialect.DEFAULT vs Custom Context

**What goes wrong:** `PostgresqlSqlDialect.DEFAULT` uses `NullCollation.HIGH` and standard identifier quoting. If Dremio's type system emits different type names (e.g. for DECIMAL or TIMESTAMP), the generated CAST expressions may not be valid PostgreSQL syntax.

**Why it happens:** SqlDialect.getCastSpec() controls how Calcite renders CAST nodes. PostgresqlSqlDialect overrides this.

**How to avoid:** For Phase 36, avoid constructing CAST nodes in the pushed-down SQL. All filter conditions and projection columns are simple column references or standard arithmetic — CASTs are rare. If encountered, fall back to NOT pushing the expression.

**Warning signs:** `ERROR: cannot cast to pg_catalog.int4` or similar PostgreSQL errors.

### Pitfall 6: OracleSqlDialect FETCH FIRST Requires ORDER BY

**What goes wrong:** Oracle requires `ORDER BY` before `FETCH FIRST N ROWS ONLY`. If a LIMIT is pushed without a sort, Oracle may return non-deterministic results OR reject the query on older versions.

**Why it happens:** `OracleSqlDialect` renders `FETCH FIRST N ROWS ONLY` as the standard SQL:2008 row-limiting clause — it does not emit `ROWNUM`. But Oracle 11g and earlier do not support this; only Oracle 12c+ does.

**How to avoid:** Target Oracle 12c+. The existing `OracleSqlBuilder.appendLimit()` already uses `FETCH FIRST`, so behavior is unchanged. `OracleSqlDialect.unparseOffsetFetch()` handles this automatically.

**Warning signs:** `ORA-00933: SQL command not properly ended` on Oracle 11g.

### Pitfall 7: Self-Join Column Name Aliasing

**What goes wrong:** In a self-join (`employees e1 JOIN employees e2 ON e1.mgr = e2.id`), both sides have identical column names. Calcite's `JdbcRules.JdbcJoin.implement()` uses the row type field names to generate SELECT list aliases. If Dremio's rel-type dedup (e.g., `department0`) is not propagated into the `JdbcRules.JdbcJoin` row type, the aliases will be wrong.

**Why it happens:** The current `JdbcJoinScanPrel` explicitly reads `outputRowType.getFieldList()` and passes deduplicated field names to `SqlBuilder.buildJoinSql()`. The `JdbcRules.JdbcJoin` node uses its own `rowType` field for the same purpose.

**How to avoid:** When constructing `JdbcRules.JdbcJoin`, pass the same deduplicated `RelDataType` that the current `JdbcJoinScanPrel.outputRowType` carries. Calcite will use the field names from `rowType` for the SELECT list aliases. This is automatically correct.

**Warning signs:** ResultSet column mismatch errors when reading self-join results.

---

## Code Examples

### Building a Single-Table JdbcRel Subtree

```java
// Source: Verified from Calcite JdbcRules class structure in calcite-core 1.22.0

// 1. Create a JdbcConvention for this plugin's dialect
JdbcConvention convention = JdbcConvention.of(dialect, null, "DREMIO_JDBC_" + pluginId.getName());

// 2. Create the leaf scan node
//    JdbcCalciteLeaf implements JdbcRel -- see Pattern 1 above
RelTraitSet jdbcTraitSet = cluster.traitSet().replace(convention);
JdbcCalciteLeaf leaf = new JdbcCalciteLeaf(cluster, jdbcTraitSet, relOptTable,
    convention, schemaName, tableName);

// 3. Optionally wrap with JdbcFilter
RelNode root = leaf;
if (filterCondition != null) {
  root = new JdbcRules.JdbcFilter(cluster, jdbcTraitSet, root, filterCondition);
}

// 4. Optionally wrap with JdbcProject (for column projection)
if (!isSelectStar) {
  List<RexNode> projects = buildColumnRefList(cluster, root.getRowType(), projectedColumns);
  root = new JdbcRules.JdbcProject(cluster, jdbcTraitSet, root, projects, projectedRowType);
}

// 5. Optionally wrap with JdbcSort
if (orderByCollation != null || limit != null) {
  RexNode limitNode = limit != null ? cluster.getRexBuilder().makeLiteral(limit, ...) : null;
  root = new JdbcRules.JdbcSort(cluster, jdbcTraitSet, root, orderByCollation, null, limitNode);
}

// 6. Optionally wrap with JdbcAggregate
if (hasAggregation) {
  root = new JdbcRules.JdbcAggregate(cluster, jdbcTraitSet, root,
      groupSet, null, aggCalls);
}

// 7. Render SQL via JdbcImplementor
JavaTypeFactory typeFactory = (JavaTypeFactory) cluster.getTypeFactory();
JdbcImplementor implementor = new JdbcImplementor(dialect, typeFactory);
JdbcImplementor.Result result = implementor.implement(root);
String sql = result.asStatement().toSqlString(dialect).getSql();
```

### The JdbcCalciteLeaf implement() Method

```java
// Source: Pattern based on Calcite JdbcTableScan (which requires a JdbcTable/JdbcSchema)
// Our version works without JdbcSchema by directly constructing SqlIdentifier

@Override
public Result implement(JdbcImplementor implementor) {
  // Produce FROM "schemaName"."tableName"
  SqlIdentifier tableRef = new SqlIdentifier(
      ImmutableList.of(schemaName, tableName), SqlParserPos.ZERO);
  return implementor.result(tableRef,
      ImmutableList.of(SqlImplementor.Clause.FROM), this, null);
}
```

### Inline Literal Rendering (No Bind Params)

```java
// Source: Calcite RexToSqlConverter -- literals are rendered inline
// When JdbcFilter's condition RexNode contains a RexLiteral,
// JdbcImplementor renders it directly in the SQL via SqlDialect.
// Example: WHERE "age" > 25 (not WHERE "age" > ?)
// This is safe: Calcite uses AST nodes, not string interpolation.
```

### Verifying OracleSqlDialect.allowsAs()

```java
// Source: OracleSqlDialect.java in Calcite -- verified via javap
// OracleSqlDialect overrides:
//   protected boolean allowsAs() { return false; }
// This means JdbcImplementor will NOT emit AS keyword for table aliases.
// PostgresqlSqlDialect does NOT override allowsAs(), so it inherits true.
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual WHERE string via RexToSqlString | JdbcImplementor handles WHERE via JdbcFilter.implement() | Phase 36 | Eliminates custom RexToSqlString entirely for WHERE clauses |
| Manual GROUP BY string via JdbcPushAggIntoScan | JdbcAggregate.implement() renders GROUP BY + aggregate functions | Phase 36 | Eliminates manual aggregate string building |
| OracleSqlBuilder.appendLimit() override | OracleSqlDialect.unparseOffsetFetch() auto-renders FETCH FIRST | Phase 36 | OracleSqlBuilder class can be retired |
| Manual column dedup aliasing in buildJoinSql() | JdbcJoin.implement() uses rowType field names | Phase 36 | Self-join aliasing becomes automatic |

**Classes that become dead code after Phase 36:**
- `SqlBuilder.java` (can be kept for backward compat but no longer called from pipeline)
- `OracleSqlBuilder.java` (dialect differences handled by OracleSqlDialect)
- `RexToSqlString.java` (filter translation handled by JdbcImplementor)
- `RexToJoinSqlString.java` (join ON clause handled by JdbcImplementor)
- `LiteralInliner.java` (literals handled inline by Calcite)
- `SqlBuildRequest.java` (no longer needed if SqlBuilder is retired)
- `BindParam.java` (no longer needed if using inline literals)

**Note:** Retiring `BindParam` requires updating `JdbcGroupScan`, `JdbcSubScan`, `JdbcRecordReader`, and `AdbcRecordReader`. The phase description says "test code may change but test logic and expected outcomes must not" — the bind param removal affects the execution layer, not just planning. The planner can decide to phase this out or keep it as an empty list.

---

## Open Questions

1. **Bind param removal scope**
   - What we know: Calcite renders literals inline; bind params are the alternative prepared-statement approach
   - What's unclear: Should Phase 36 remove `BindParam` from `JdbcGroupScan`/reader entirely, or leave the field as an empty list for now?
   - Recommendation: Leave `BindParam` list in `JdbcGroupScan` as `Collections.emptyList()`. Do not change the execution layer (JdbcRecordReader, AdbcRecordReader) in this phase. This minimizes risk. The "no bind params" outcome is that `PreparedStatement.execute(sql)` effectively works the same as `Statement.execute(sql)` when there are no parameters.

2. **JdbcCalciteLeaf RelOptTable dependency**
   - What we know: `TableScan` requires a `RelOptTable` argument. Our `JdbcScanDrel` has one. `JdbcScanPrel` also has one (via `ScanPrelBase`). The leaf node can pass it through.
   - What's unclear: Does `JdbcImplementor` ever call back to `RelOptTable` methods that require a real Calcite schema? Specifically, does `JdbcImplementor.Result` use the table's `getQualifiedName()` or our explicit `SqlIdentifier`?
   - Recommendation: Use explicit `SqlIdentifier(ImmutableList.of(schemaName, tableName), pos)` in `implement()` rather than relying on the `RelOptTable` qualified name. This gives us direct control over quoting.

3. **PostgresqlSqlDialect vs custom dialect with $N placeholders**
   - What we know: The ADBC execution path uses `$1, $2, ...` placeholders (PostgreSQL protocol). The existing `SqlBuilder.jdbcToPostgresPlaceholders()` converts `?` → `$N`. If Phase 36 uses inline literals, this becomes moot for all paths.
   - What's unclear: If any future path still needs prepared statements with ADBC, does Calcite have a way to render `$N` style placeholders?
   - Recommendation: With inline literals (no bind params), this is not a concern for Phase 36. Document for future reference only.

4. **JdbcRules.JdbcCalcRule vs JdbcFilter/JdbcProject/JdbcSort**
   - What we know: `JdbcRules.JdbcCalc` is a Calc node (merged Project + Filter). It might render more efficiently.
   - What's unclear: Does using `JdbcFilter(JdbcProject(...))` nesting work correctly? Does JdbcImplementor handle both orders?
   - Recommendation: Use `JdbcFilter` below `JdbcProject` (filter first, then project) to match SQL semantics. JdbcImplementor handles nesting via its `visitChild()` mechanism. Verify with a simple test before proceeding.

---

## Sources

### Primary (HIGH confidence)
- `calcite-core-1.22.0-202501311721330426-8b30dab9.jar` (on classpath at `/home/filippo/.m2/repository/org/apache/calcite/calcite-core/`) — verified via `javap`:
  - `org.apache.calcite.adapter.jdbc.JdbcConvention` — `of(SqlDialect, Expression, String)` factory method
  - `org.apache.calcite.adapter.jdbc.JdbcImplementor` — `implement(RelNode)` returns `Result`
  - `org.apache.calcite.adapter.jdbc.JdbcRules.JdbcFilter/JdbcProject/JdbcJoin/JdbcSort/JdbcAggregate` — all present, constructors verified
  - `org.apache.calcite.sql.dialect.PostgresqlSqlDialect` — `DEFAULT` field, `allowsAs()` not overridden
  - `org.apache.calcite.sql.dialect.OracleSqlDialect` — `DEFAULT` field, `allowsAs()` overridden to `false`
  - `org.apache.calcite.rel.rel2sql.RelToSqlConverter` — `visit(Join/Filter/Project/Sort/Aggregate)` methods
  - `org.apache.calcite.rel.rel2sql.SqlImplementor.Result` — `asStatement()`, `asSelect()`, `builder()` methods
- Codebase source read directly (HIGH confidence):
  - `DrelTransformer.java` — `pushDownJdbcQuery()` pipeline, `JdbcCrel` wrapping, `RELATIONAL_PLANNING` phase
  - `JdbcScanPrel.java` — current state: all SQL fields + `getPhysicalOperator()` calling `SqlBuilder.buildSql()`
  - `JdbcJoinScanPrel.java` — current state: calling `SqlBuilder.buildJoinSql()`
  - `JdbcStoragePlugin.java` — `createSqlBuilder()` factory method (to be replaced by `createDialect()`)
  - `SqlBuilder.java`, `OracleSqlBuilder.java` — full implementation of what is being replaced
  - `RexToSqlString.java`, `BindParam.java` — execution layer components

### Secondary (MEDIUM confidence)
- Calcite documentation patterns for JdbcAdapter (general knowledge, verified against jar API)

### Tertiary (LOW confidence)
- None

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all Calcite classes present in jar, APIs verified via javap
- Architecture: HIGH — current codebase fully read, migration path is clear
- Pitfalls: HIGH — based on direct code reading and Calcite API verification

**Research date:** 2026-03-15
**Valid until:** 2026-06-15 (calcite-core version is pinned; Dremio codebase is the limiting factor)

---

## Appendix: Key File Inventory

**Files to modify (plugin module only, except ~10 kernel lines):**
- `plugins/jdbc-base/.../planning/JdbcScanPrel.java` — replace `getPhysicalOperator()` to use JdbcImplementor
- `plugins/jdbc-base/.../planning/JdbcJoinScanPrel.java` — replace `getPhysicalOperator()` to use JdbcImplementor
- `plugins/jdbc-base/.../JdbcStoragePlugin.java` — add `createDialect()` method
- `plugins/jdbc-oracle/.../OracleConf.java` (or OraclePlugin) — override `createDialect()` → OracleSqlDialect.DEFAULT
- `plugins/jdbc-postgresql/.../PostgresConf.java` (or PostgresPlugin) — override `createDialect()` → PostgresqlSqlDialect.DEFAULT

**Files to add (plugin module only):**
- `plugins/jdbc-base/.../planning/JdbcCalciteLeaf.java` — JdbcRel leaf node implementing `implement(JdbcImplementor)`

**Files that become dead code (keep, don't delete yet):**
- `SqlBuilder.java`, `OracleSqlBuilder.java`, `RexToSqlString.java`, `RexToJoinSqlString.java`, `LiteralInliner.java`, `SqlBuildRequest.java`
- `BindParam.java` — keep in place; `JdbcGroupScan` still accepts it; just pass empty list

**Tests to update (test logic unchanged, test code adapts):**
- `TestSqlBuilder.java` — becomes test of legacy class; or update to test `JdbcImplementor` output
- `TestJdbcPushJoinIntoScan.java` — verify join SQL structure via Calcite output
- `TestPostgresPushdown.java`, `TestOraclePushdown.java` — integration tests; expected SQL strings may change format (e.g., `"public"."users"` becomes `"public"."users"` — same), but results must be identical
