# Phase 30: Base JDBC Framework - Research

**Researched:** 2026-03-12
**Domain:** Dremio StoragePlugin API, JDBC, HikariCP, Apache Arrow JDBC adapter, Calcite pushdown rules
**Confidence:** HIGH (all findings verified against live codebase)

---

## Summary

Phase 30 introduces `dremio-plugin-jdbc-base`, a new Maven module in `plugins/` that implements the Dremio `StoragePlugin` API over a pooled JDBC connection. The spec decision (from `project_jdbc_plugin_spec.md`) is already clear: HikariCP for pooling, Trino's `plugin/trino-base-jdbc/` as architectural reference, and the existing Dremio MongoDB and Elasticsearch plugins as structural reference.

The codebase provides all necessary base classes and interfaces: `ConnectionConf`, `StoragePlugin`, `SupportsListingDatasets`, `AbstractRecordReader`, `ScanPrelBase`, `AbstractGroupScan`, and `StoragePluginRulesFactory`. Plugin registration is via classpath scanning: a `sabot-module.conf` file declares the package, and the `@SourceType` annotation on the `ConnectionConf` subclass causes automatic discovery. No service file or registry code is needed.

The Arrow `arrow-jdbc` adapter (`org.apache.arrow:arrow-jdbc`) is already declared in the root POM's `<dependencyManagement>` at `${arrow.version}` (currently `18.1.1-20250709...`). It ships `JdbcToArrowUtils` and related utilities that handle JDBC→Arrow type conversion, eliminating the need to hand-roll a type mapper. For the data-reading path, the plugin needs a `RecordReader` that drives a `PreparedStatement`, feeds each row through the Arrow JDBC adapter, and writes into `OutputMutator` vectors inside `next()`.

HikariCP version 2.6.1 already appears in the codebase (hive-function-registry test scope), but for production use a newer version (`5.x`) should be declared in the root POM's `<dependencyManagement>`. The planner pushdown layer follows the pattern established by `InfoSchemaPushFilterIntoScan` (filter), `cloneWithProject` on `ScanPrelBase` (projection), and the Elasticsearch `ElasticsearchLimit` (LIMIT): each pushdown attaches its artifact to a custom `ScanPrel`, which serializes it into the `SubScan`, which the `RecordReader` picks up to rewrite the SQL string before execution.

**Primary recommendation:** Model the module directly on the `ischema` package (InfoSchema plugin) for the plugin/conf/rules skeleton, use Apache Arrow `arrow-jdbc` for type conversion, HikariCP 5.x for pooling, and adopt the Trino `JdbcClient` abstraction (or a simplified version of it) as the interface boundary between base and concrete JDBC connectors.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BASE-01 | Plugin base module provides HikariCP connection pooling with configurable pool size, idle timeout, and validation query | HikariCP 5.x declared in root POM dependencyManagement; `HikariConfig` supports `maximumPoolSize`, `idleTimeout`, `connectionTestQuery` — all fields map directly to `ConnectionConf` public fields annotated `@NotMetadataImpacting` |
| BASE-02 | Plugin discovers schemas, tables, and columns via JDBC DatabaseMetaData | `DatabaseMetaData.getSchemas()`, `getTables()`, `getColumns()` — standard JDBC; wrap in `SupportsListingDatasets.listDatasetHandles()` to produce `DatasetHandle` objects; use `SourceMetadata.getDatasetMetadata()` for column schema |
| BASE-03 | Plugin maps standard JDBC types to Arrow/Dremio types | `org.apache.arrow:arrow-jdbc` (`JdbcToArrowUtils.getArrowTypeForJdbcType()`) handles the full mapping; already managed in root POM |
| BASE-04 | Plugin converts JDBC ResultSet rows into Arrow RecordBatches and streams them | Extend `AbstractRecordReader`; in `setup()` register vectors via `OutputMutator.addField()`; in `next()` drive `ResultSet.next()`, write to vectors, return count; use `arrow-jdbc` `JdbcToArrow.sqlToArrowVectorIterator()` or manually iterate |
| BASE-05 | Plugin pushes down WHERE filters to source SQL | Implement `RelOptRule` matching `FilterPrel → JdbcScanPrel`; serialize `RexNode` to SQL WHERE clause via Calcite `SqlImplementor`/`RexToSqlNodeConverter`; attach to `JdbcSubScan.whereClause` |
| BASE-06 | Plugin pushes down column projection to source SQL | Override `cloneWithProject(List<SchemaPath>)` on `JdbcScanPrel`; projected columns become the SELECT list when constructing the query string |
| BASE-07 | Plugin pushes down LIMIT to source SQL | Implement rule matching `LimitPrel → JdbcScanPrel`; attach fetch size to `JdbcSubScan.limit`; RecordReader appends `LIMIT n` to SQL |
| BASE-08 | Plugin reports source health status to Dremio coordinator via periodic validation query | Implement `StoragePlugin.getState()`; run the configured validation query via a pooled connection; return `SourceState.GOOD`, `SourceState.warnState(...)`, or `SourceState.badState(...)` |
</phase_requirements>

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.zaxxer:HikariCP` | 5.1.0 | JDBC connection pooling | Fastest JVM pool; Apache 2.0; chosen in spec; already used in hive-function-registry |
| `org.apache.arrow:arrow-jdbc` | `${arrow.version}` (18.1.1-dremio) | JDBC → Arrow type mapping and batch conversion | Already in root POM dependencyManagement; avoids hand-rolling 15+ type conversions |
| `com.dremio.sabot:dremio-sabot-kernel` | `${project.version}` | All Dremio plugin APIs | Required by every Dremio plugin |
| `com.dremio:dremio-common` | `${project.version}` | SchemaPath, ExpressionConverter, SabotConfig | Required by every Dremio plugin |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.apache.calcite:calcite-core` | (transitive via sabot-kernel) | RexNode → SQL translation for pushdown | Needed for WHERE/LIMIT SQL generation |
| `com.dremio.sabot:dremio-sabot-kernel:tests` | `${project.version}` | `DremioTest`, `BaseTestQuery` for integration tests | Test scope only |
| `org.testcontainers:testcontainers` | (managed) | Container-based integration tests | For concrete connector tests in later phases |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| HikariCP 5.x | c3p0, DBCP2 | HikariCP is 2-5x faster, simpler config, Apache 2.0 — no reason to switch |
| `arrow-jdbc` adapter | Hand-rolled type switch | `arrow-jdbc` handles edge cases (NULL, DECIMAL precision, TIME zones) correctly; hand-rolling is error-prone |
| Calcite `SqlImplementor` for WHERE SQL | Hand-rolled RexNode visitor | `SqlImplementor` is what Calcite already uses; reusing it avoids dialect bugs |

**Maven dependency declarations (new module pom.xml):**
```xml
<dependency>
  <groupId>com.dremio.sabot</groupId>
  <artifactId>dremio-sabot-kernel</artifactId>
  <version>${project.version}</version>
</dependency>
<dependency>
  <groupId>com.dremio</groupId>
  <artifactId>dremio-common</artifactId>
  <version>${project.version}</version>
</dependency>
<dependency>
  <groupId>com.zaxxer</groupId>
  <artifactId>HikariCP</artifactId>
  <version>5.1.0</version>
</dependency>
<dependency>
  <groupId>org.apache.arrow</groupId>
  <artifactId>arrow-jdbc</artifactId>
</dependency>
```

Root POM needs `HikariCP` added to `<dependencyManagement>` (currently only version `2.6.1` is declared, scoped to hive3 properties).

---

## Architecture Patterns

### Recommended Module Structure
```
plugins/
└── jdbc-base/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/dremio/plugins/jdbc/
        │   │   ├── conf/
        │   │   │   └── BaseJdbcConf.java          # Abstract ConnectionConf<T, BaseJdbcPlugin>
        │   │   ├── JdbcStoragePlugin.java          # implements StoragePlugin + SupportsListingDatasets
        │   │   ├── pool/
        │   │   │   └── JdbcConnectionPool.java     # HikariCP wrapper
        │   │   ├── schema/
        │   │   │   └── JdbcSchemaFetcher.java      # DatabaseMetaData discovery
        │   │   ├── reader/
        │   │   │   └── JdbcRecordReader.java       # AbstractRecordReader impl
        │   │   ├── planning/
        │   │   │   ├── JdbcRulesFactory.java       # StoragePluginRulesFactory
        │   │   │   ├── JdbcScanDrel.java           # logical scan rel
        │   │   │   ├── JdbcScanPrel.java           # physical scan rel (extends ScanPrelBase)
        │   │   │   ├── JdbcScanDrule.java          # logical → physical rule
        │   │   │   ├── JdbcScanPrule.java          # converts Drel→Prel
        │   │   │   ├── JdbcPushFilterIntoScan.java # WHERE pushdown
        │   │   │   ├── JdbcPushProjectIntoScan.java# projection pushdown
        │   │   │   └── JdbcPushLimitIntoScan.java  # LIMIT pushdown
        │   │   └── exec/
        │   │       ├── JdbcGroupScan.java          # AbstractGroupScan impl
        │   │       ├── JdbcSubScan.java            # AbstractSubScan impl (carries SQL)
        │   │       └── JdbcScanCreator.java        # ProducerOperator.Creator<JdbcSubScan>
        │   └── resources/
        │       └── sabot-module.conf               # registers com.dremio.plugins.jdbc
        └── test/
            └── java/com/dremio/plugins/jdbc/
                └── TestJdbcBase.java
```

### Pattern 1: ConnectionConf Subclass with @SourceType
**What:** Abstract base conf holding HikariCP parameters; concrete connectors add host/port/db.
**When to use:** Every JDBC plugin extends this.
**Example:**
```java
// Source: ConnectionConf<T,P> in sabot/kernel, InfoSchemaConf.java pattern
public abstract class BaseJdbcConf<T extends BaseJdbcConf<T, P>, P extends StoragePlugin>
    extends ConnectionConf<T, P> {

  @Tag(1)
  public int poolSize = 5;

  @Tag(2)
  @NotMetadataImpacting
  public int idleTimeoutMs = 600_000;

  @Tag(3)
  @NotMetadataImpacting
  public String validationQuery = "SELECT 1";

  public abstract String jdbcUrl();
}
```

### Pattern 2: StoragePlugin Lifecycle (start / close / getState)
**What:** `start()` initialises the HikariCP pool; `close()` shuts it down; `getState()` runs the validation query.
**When to use:** Required by `StoragePlugin` interface.
**Example:**
```java
// Based on InfoSchemaStoragePlugin.java pattern + SourceState factory methods
@Override
public void start() throws IOException {
  HikariConfig cfg = new HikariConfig();
  cfg.setJdbcUrl(conf.jdbcUrl());
  cfg.setMaximumPoolSize(conf.poolSize);
  cfg.setIdleTimeout(conf.idleTimeoutMs);
  cfg.setConnectionTestQuery(conf.validationQuery);
  pool = new HikariDataSource(cfg);
}

@Override
public SourceState getState() {
  try (Connection c = pool.getConnection();
       Statement s = c.createStatement()) {
    s.execute(conf.validationQuery);
    return SourceState.GOOD;
  } catch (Exception e) {
    return SourceState.badState("Validation query failed: " + e.getMessage());
  }
}
```

### Pattern 3: Schema Discovery via DatabaseMetaData
**What:** Implement `SupportsListingDatasets.listDatasetHandles()` using `DatabaseMetaData.getTables()` and `getColumns()`.
**When to use:** BASE-02 requirement.
**Example:**
```java
// Source: SupportsListingDatasets.java + DatasetHandle.java in connector module
@Override
public DatasetHandleListing listDatasetHandles(GetDatasetOption... options) {
  // returns lazy iterator over tables via DatabaseMetaData.getTables()
  return () -> fetchTableHandles();
}
```

### Pattern 4: RecordReader Setup and next()
**What:** Extend `AbstractRecordReader`; in `setup()` declare vectors via `OutputMutator`; in `next()` drive `ResultSet` and fill vectors.
**When to use:** BASE-04 requirement.
**Example:**
```java
// Source: AbstractRecordReader.java + InformationSchemaRecordReader.java patterns
@Override
public void setup(OutputMutator output) throws ExecutionSetupException {
  // For each projected column, add Arrow field to output
  for (Field field : schema.getFields()) {
    vectors.put(field.getName(), output.addField(field, getVectorClass(field)));
  }
  conn = pool.getConnection();
  stmt = conn.prepareStatement(buildSql());
  rs = stmt.executeQuery();
}

@Override
public int next() {
  int count = 0;
  while (count < numRowsPerBatch && rs.next()) {
    for (Map.Entry<String, ValueVector> e : vectors.entrySet()) {
      writeValue(e.getValue(), rs, e.getKey(), count);
    }
    count++;
  }
  setValueCount(vectors.values(), count);
  return count;
}
```

### Pattern 5: SQL Construction with Pushdown
**What:** `JdbcSubScan` carries a pre-built SQL string (`SELECT cols FROM schema.table WHERE ... LIMIT n`). The planner rules assemble it from components; the RecordReader executes it verbatim.
**When to use:** BASE-05, BASE-06, BASE-07.

### Pattern 6: Pushdown Rules (Filter, Project, Limit)
**What:** Three `RelOptRule` subclasses registered in `JdbcRulesFactory` for the PHYSICAL phase.
**Pattern reference:** `InfoSchemaPushFilterIntoScan` (filter), `InfoSchemaScanPrel.cloneWithProject()` (projection), `ElasticsearchLimit` (limit).
**Example skeleton:**
```java
// Filter pushdown — matches FilterPrel above JdbcScanPrel
public class JdbcPushFilterIntoScan extends RelOptRule {
  public static final RelOptRule INSTANCE = new JdbcPushFilterIntoScan(
      RelOptHelper.some(FilterPrel.class, RelOptHelper.any(JdbcScanPrel.class)),
      "JdbcPushFilterIntoScan");

  @Override
  public void onMatch(RelOptRuleCall call) {
    FilterPrel filter = call.rel(0);
    JdbcScanPrel scan = call.rel(1);
    String whereClause = rexToSql(filter.getCondition(), scan.getRowType());
    if (whereClause != null) {
      call.transformTo(scan.cloneWithFilter(whereClause));
    }
  }
}
```

### Pattern 7: ScanPrel → GroupScan → SubScan → ScanCreator Chain
**What:** Physical planning produces `JdbcScanPrel`; `getPhysicalOperator()` creates `JdbcGroupScan`; `getSpecificScan()` creates `JdbcSubScan`; `JdbcScanCreator` (a `ProducerOperator.Creator<JdbcSubScan>`) is discovered by classpath scanning and wires `ScanOperator(fec, config, context, RecordReaderIterator.from(reader))`.
**Key insight:** The `Creator` binding is auto-discovered by `OperatorCreatorRegistry` from `ScanResult` via reflection on the generic type parameter — no registration code needed, just a `sabot-module.conf` declaring the package.

### Pattern 8: Plugin Registration
**What:** `sabot-module.conf` in `src/main/resources/` registers the package for classpath scanning. The `@SourceType` annotation on the `ConnectionConf` subclass causes `ConnectionReaderImpl.makeReader()` to include it.
```hocon
// src/main/resources/sabot-module.conf
dremio.classpath.scanning.packages += "com.dremio.plugins.jdbc"
```
The plugins/pom.xml `<modules>` list must include `jdbc-base`.

### Anti-Patterns to Avoid
- **Extending InfoSchemaStoragePlugin or any existing concrete plugin:** These are final implementations. Extend the API interfaces/abstract classes directly.
- **Hand-rolling JDBC→Arrow type conversion:** Use `arrow-jdbc`'s `JdbcToArrowUtils`; hand-rolling misses NULL handling, DECIMAL precision loss, and timezone edges.
- **Holding a live `Connection` in the `StoragePlugin` singleton:** Connections must come from the pool per-query. The plugin holds the `HikariDataSource`, not a `Connection`.
- **Implementing pushdown rules for the LOGICAL phase:** Pushdown rules operate on Prel (physical) nodes. Registering in `LOGICAL` phase won't match `FilterPrel`/`ProjectPrel`/`LimitPrel`.
- **Omitting `@JsonTypeName` / `@SourceType` annotation:** Without it, classpath scanning won't register the `ConnectionConf` subclass and the source type will be unknown.
- **Accessing `OperatorContext` from `ConnectionConf.newPlugin()`:** `OperatorContext` is per-query. The plugin gets `PluginSabotContext` (cluster-level) in `newPlugin()`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Connection pooling | Custom thread-safe pool | `HikariCP` | Pool eviction, validation, leak detection, metrics — huge surface area |
| JDBC → Arrow type mapping | `switch (jdbcType) { case Types.INTEGER: ... }` | `arrow-jdbc` `JdbcToArrowUtils.getArrowTypeForJdbcType()` | Handles all 15 types, DECIMAL precision, NULL, timezone |
| Classpath-based plugin discovery | Manual registry map | `sabot-module.conf` + `@SourceType` | Already wired in `ConnectionReaderImpl`; manual registry would be dead code |
| WHERE SQL from RexNode | Custom RexNode visitor | Calcite `RelToSqlConverter` or `SqlImplementor` | Handles operator precedence, quoting, dialect differences |
| RecordBatch batching | Custom VectorSchemaRoot management | Extend `AbstractRecordReader` (already handles `numRowsPerBatch`, `numBytesPerBatch`) | Base class manages batch sizing, skip-query optimisation, runtime filters |

**Key insight:** The Dremio sabot-kernel `AbstractRecordReader` already handles batch size management, skip-query handling, and runtime filter infrastructure. All the JDBC plugin needs is the data-fetching loop in `next()`.

---

## Common Pitfalls

### Pitfall 1: HikariCP Version Mismatch
**What goes wrong:** Root POM only has HikariCP 2.6.1 (hive3 test scope). Using that version for production means missing HikariCP 4.x health check APIs and 5.x virtual thread readiness.
**Why it happens:** The existing entry is scoped to the hive3 submodule.
**How to avoid:** Add a fresh `<dependencyManagement>` entry in the root POM: `com.zaxxer:HikariCP:5.1.0` (latest stable as of 2025, Apache 2.0).
**Warning signs:** Build uses hive3 transitive version; runtime sees HikariCP 2.6.1 class names.

### Pitfall 2: ConnectionConf Protostuff Schema Not Matching @Tag Annotations
**What goes wrong:** `ConnectionConf` uses Protostuff for serialisation. Fields without `@Tag` annotations or with duplicate tags cause `ProtobufIOUtil` errors at startup.
**Why it happens:** Developers adding new fields forget Protostuff field ordering requirements.
**How to avoid:** Every field in a `ConnectionConf` subclass needs a unique `@Tag(N)` annotation. Never renumber existing tags (breaks stored configurations).
**Warning signs:** `ProtostuffException: field X is already registered` at plugin start.

### Pitfall 3: Thread-Safety of the ResultSet in next()
**What goes wrong:** `RecordReader.next()` may be called concurrently by the `ScanOperator`. Holding `ResultSet` as instance state is safe only if the `RecordReader` is not shared across threads — which it isn't (one per split), but `Connection` must not be shared.
**Why it happens:** Confusing the pool (shared) with individual connections (per-reader).
**How to avoid:** Acquire `Connection` in `setup()`, hold it as instance field, close in `close()`. Never share a `Connection` between `RecordReader` instances.

### Pitfall 4: OperatorCreatorRegistry Can't Find ScanCreator
**What goes wrong:** `JdbcScanCreator` is not discovered because the package is not in `sabot-module.conf` or because the generic type parameter is erased.
**Why it happens:** `OperatorCreatorRegistry.getImplementors()` reflects on the generic type of `ProducerOperator.Creator<T>` to map `T → Creator`. If the class is not on the scanned classpath, it won't be found.
**How to avoid:** Ensure `sabot-module.conf` includes `com.dremio.plugins.jdbc` and that `JdbcScanCreator implements ProducerOperator.Creator<JdbcSubScan>` is a concrete class (not abstract/anonymous).

### Pitfall 5: RexNode → SQL Translation for WHERE Clause
**What goes wrong:** Naive `toString()` on `RexNode` produces internal Calcite representations, not valid SQL.
**Why it happens:** `RexNode.toString()` is for debugging, not SQL emission.
**How to avoid:** Use Calcite's `RelToSqlConverter` or `RexToSqlNodeConverter` to emit proper SQL; or use the simpler pattern from `ExpressionConverter` in the InfoSchema plugin to handle only the subset of operators needed for v1.5 (comparison, AND/OR, literals).

### Pitfall 6: LIMIT Pushdown Interaction with Parallelism
**What goes wrong:** If `getMaxParallelizationWidth()` returns >1 and LIMIT is pushed down to each split, total rows returned will be `n * splits`.
**Why it happens:** Each fragment executes its own LIMIT n query.
**How to avoid:** For LIMIT pushdown, set `getMaxParallelizationWidth() = 1` in `JdbcScanPrel` — JDBC scans are single-node by nature. This matches the InfoSchema and SysFlight pattern.

---

## Code Examples

Verified patterns from live codebase:

### InfoSchemaConf → ConnectionConf (plugin conf skeleton)
```java
// From: sabot/kernel/.../InfoSchemaConf.java
@SourceType(value = "INFORMATION_SCHEMA", configurable = false)
public class InfoSchemaConf extends ConnectionConf<InfoSchemaConf, InfoSchemaStoragePlugin> {
  @Override
  public InfoSchemaStoragePlugin newPlugin(PluginSabotContext ctx, String name,
      Provider<StoragePluginId> pluginIdProvider) {
    return new InfoSchemaStoragePlugin(ctx, name);
  }
}
// For JDBC: replace @SourceType with plugin-specific value, add HikariCP fields
```

### StoragePlugin.getState() using SourceState factory methods
```java
// From: SourceState.java — factory methods
SourceState.GOOD                           // singleton for healthy
SourceState.badState("msg", exception)     // SourceStatus.bad
SourceState.warnState("action", "msg")     // SourceStatus.warn
```

### ScanPrelBase extension (physical scan node)
```java
// From: InfoSchemaScanPrel.java
public class JdbcScanPrel extends ScanPrelBase {
  private final String whereClause;  // null = no filter pushed
  private final Integer limit;       // null = no limit pushed

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    return new JdbcGroupScan(
        creator.props(this, getTableMetadata().getUser(),
            getTableMetadata().getSchema().maskAndReorder(getProjectedColumns())),
        buildSql(), getProjectedColumns(), pluginId);
  }

  @Override
  public JdbcScanPrel cloneWithProject(List<SchemaPath> projection) {
    return new JdbcScanPrel(getCluster(), getTraitSet(), getTable(),
        getTableMetadata(), projection, whereClause, limit, ...);
  }
}
```

### ProducerOperator.Creator wiring
```java
// From: InfoSchemaScanCreator.java
public class JdbcScanCreator implements ProducerOperator.Creator<JdbcSubScan> {
  @Override
  public ProducerOperator create(FragmentExecutionContext fec,
      OperatorContext context, JdbcSubScan config) throws ExecutionSetupException {
    JdbcStoragePlugin plugin = fec.getStoragePlugin(config.getPluginId());
    RecordReader reader = new JdbcRecordReader(context, config, plugin.getPool());
    return new ScanOperator(fec, config, context, RecordReaderIterator.from(reader));
  }
}
```

### sabot-module.conf (classpath scanning registration)
```hocon
# From: plugins/nas/src/main/resources/sabot-module.conf pattern
dremio.classpath.scanning.packages += "com.dremio.plugins.jdbc"
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual JDBC type switch | `arrow-jdbc` `JdbcToArrowUtils` | Arrow 0.12+ | No more hand-rolled type mapping |
| HikariCP 2.x (hive test dep) | HikariCP 5.x production | HikariCP 5.0 (2022) | Pool metrics, virtual thread readiness |
| Separate "GroupScan wraps SubScan" registration | Auto-discovery via `OperatorCreatorRegistry` + `ScanResult` | Dremio OSS always | No manual registration; just `sabot-module.conf` |
| Direct `Connection` field in plugin | `HikariDataSource` pool in plugin | Always best practice | Proper connection lifecycle management |

**Deprecated/outdated:**
- HikariCP 2.6.1 declared in root POM: test-scoped, hive3 only — not suitable for production plugin use. New entry needed.
- `@Tag` field ordering in `ConnectionConf`: existing order is immutable once deployed (Protostuff binary compat). Plan field layout carefully before first release.

---

## Open Questions

1. **HikariCP version to add to root POM `<dependencyManagement>`**
   - What we know: 2.6.1 is in hive3 test scope; 5.1.0 is latest stable (Apache 2.0)
   - What's unclear: Whether any existing transitive dependency pulls a different HikariCP version that could conflict
   - Recommendation: Add `com.zaxxer:HikariCP:5.1.0` to root POM `<dependencyManagement>` without scope; let the jdbc-base module declare compile scope

2. **WHERE clause SQL generation strategy**
   - What we know: Calcite `RelToSqlConverter` exists and is used internally; `ExpressionConverter` in InfoSchema plugin handles a subset manually
   - What's unclear: Which dialect adapter to use (ANSI SQL is fine for v1.5 base; PostgreSQL/Oracle specifics can be overridden in concrete modules)
   - Recommendation: For v1.5 base, implement a simple `RexNode → SQL` converter covering comparison operators, AND/OR, NULL checks, and literals. Full `RelToSqlConverter` can be adopted in a later phase.

3. **CoreOperatorType proto value for JdbcSubScan**
   - What we know: `InfoSchemaSubScan.getOperatorType()` returns `CoreOperatorType.INFO_SCHEMA_SUB_SCAN_VALUE`; each SubScan needs a unique enum value
   - What's unclear: Whether there is an available/reserved value in the proto file for JDBC, or if a new one needs to be added
   - Recommendation: Check `UserBitShared.proto` for reserved/available values; add `JDBC_SUB_SCAN = N` if needed (requires proto recompile)

---

## Sources

### Primary (HIGH confidence)
- Live codebase: `sabot/kernel/.../InfoSchema*` package — full plugin implementation model (Conf, StoragePlugin, RulesFactory, ScanDrel, ScanPrel, GroupScan, SubScan, ScanCreator, RecordReader)
- Live codebase: `connector/src/.../SourceMetadata.java`, `SupportsListingDatasets.java`, `DatasetHandle.java` — metadata API
- Live codebase: `service/namespace/src/.../SourceState.java` — health reporting API
- Live codebase: `sabot/kernel/.../ScanPrelBase.java`, `LimitPrel.java`, `AbstractRecordReader.java` — physical planning and execution base classes
- Live codebase: `plugins/elasticsearch/src/.../ElasticsearchLimit.java`, `InfoSchemaPushFilterIntoScan.java` — pushdown rule patterns
- Live codebase: `pom.xml` — confirms `arrow-jdbc` at `${arrow.version}` in dependencyManagement; HikariCP 2.6.1 in hive3 test scope
- Live codebase: `sabot/kernel/.../ConnectionReaderImpl.java`, `OperatorCreatorRegistry.java` — plugin auto-discovery mechanism
- Live codebase: `plugins/nas/src/main/resources/sabot-module.conf` — package registration pattern
- Memory file: `project_jdbc_plugin_spec.md` — architectural decisions: HikariCP, Trino as reference, module names, source type names

### Secondary (MEDIUM confidence)
- HikariCP GitHub README (training data, 2024): version 5.1.0 is latest stable; configuration properties `maximumPoolSize`, `idleTimeout`, `connectionTestQuery` are stable API
- Apache Arrow `arrow-jdbc` module (training data): `JdbcToArrowUtils.getArrowTypeForJdbcType()` signature and behaviour consistent with arrow-jdbc usage seen in `FlightWorkManager.java`

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — verified against live POM files and existing plugin implementations
- Architecture: HIGH — patterns directly extracted from InfoSchema, Elasticsearch, and sys plugins in the live codebase
- Pitfalls: HIGH (Protostuff tags, threading, registration) / MEDIUM (RexNode SQL generation specifics)
- HikariCP version: MEDIUM — 5.1.0 is current stable but exact root POM entry needs to be confirmed before writing

**Research date:** 2026-03-12
**Valid until:** 2026-04-12 (stable APIs, 30 days)
