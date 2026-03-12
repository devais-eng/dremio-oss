# Phase 24: Multi-Branch Queries and Hardening - Research

**Researched:** 2026-03-10
**Domain:** Cross-branch SQL query dispatch, error handling for branch-not-found vs table-not-found-on-branch, integration testing with Nessie
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Cross-branch query patterns:**
- All multi-table SQL patterns must work across branches: JOINs, UNION ALL, subqueries with different AT BRANCH clauses
- Same table from two different branches in one query (e.g., `src.ns.tbl AT BRANCH "main" JOIN src.ns.tbl AT BRANCH "dev"`) is an explicit test case — verify no alias collision or caching interference
- Mixed-source queries must be validated: JOIN between a Nessie-enabled RESTCATALOG source (with AT BRANCH) and a plain non-versioned source
- AT BRANCH only — AT TAG and AT COMMIT are out of scope for this phase

**Error message design:**
- Mimic native Nessie error patterns but improve table-not-found messages to include branch context
- Branch not found: follow Nessie pattern — "Requested Branch 'X' not found in source 'Y'"
- Table not found on branch: improve beyond native Nessie — "Table 'X' not found on branch 'Y' in source 'Z'" (native Nessie only says "Table 'X' not found")
- Always include source name in error messages for both branch-not-found and table-not-found-on-branch
- Reuse existing `ReferenceNotFoundException` exception type for branch-not-found errors (same as native Nessie)
- enableNessie=true but detection failed: generic "doesn't support AT BRANCH/TAG/COMMIT" error is sufficient (warning already logged at startup)

**Edge case handling:**
- Schema mismatch across branches (same table, different columns): let it fail naturally through Dremio's standard type mismatch / column-not-found errors — no special handling
- Branch deleted mid-query: no special handling, let REST API error propagate naturally
- Special characters in branch names: explicitly test common Git patterns — feature/my-branch (slash), release-1.0 (dot), names with spaces
- enableNessie=false regression: explicit regression test to confirm zero behavioral change after Phase 24 changes

**Testing strategy:**
- Both layers: unit tests for dispatch/error logic + integration tests against real Nessie for end-to-end validation
- Integration tests extend existing TestRestIcebergCatalogPlugin (already has Nessie/REST catalog infrastructure)
- Test data setup via Iceberg REST API with branch-prefixed URIs, with optional Spark-based writes for specific tests (no mandatory Spark dependency)
- Nessie server via testcontainers (self-contained Docker-based)

### Claude's Discretion

- Detection approach for branch-not-found vs table-not-found-on-branch (HTTP status parsing vs probing branch existence first)
- Exact testcontainers configuration and lifecycle management
- How to structure optional Spark-based test data setup without mandatory dependency

### Deferred Ideas (OUT OF SCOPE)

- AT TAG support — similar URI prefix approach, could be a follow-up phase
- AT COMMIT support — known concern about whether Nessie accepts commit hashes in REST URI prefix
- Branch-specific error for enableNessie=true + failed detection — currently generic "doesn't support versioning" is sufficient
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BRQ-03 | User can JOIN tables from different branches in a single query | Cross-branch JOINs, UNION ALLs, and subqueries all resolve via the same three-way dispatch already in CatalogImpl from Phase 23. Each table reference in a multi-table query produces a separate CatalogEntityKey lookup with its own TableVersionContext. The dispatch is per-table, so multiple tables in one query each independently route through `getTableSnapshotHelper` / `getDatasetHandleHelper`. No additional dispatch changes needed — the infrastructure handles this by design. |
</phase_requirements>

## Summary

Phase 24 is primarily a hardening phase: the underlying dispatch machinery for AT BRANCH queries was completed in Phase 23 (three-way dispatch in `CatalogImpl`, branch-scoped accessors from Phase 22, plan cache exclusion). Cross-branch JOINs, UNIONs, and subqueries work by design because CatalogImpl performs a separate table lookup per table reference — each gets its own `getTableSnapshotHelper` call with its own `TableVersionContext`. The per-branch Caffeine cache in `BranchAwareCatalogAccessorCache` prevents cross-branch accessor reuse (and thus cache poisoning). No architectural changes are needed for multi-table cross-branch queries.

The two substantive additions for this phase are error handling and integration testing. Error handling requires distinguishing "branch does not exist" from "table does not exist on a valid branch" — these produce different user-facing messages. The error detection strategy is a critical design decision (Claude's discretion): either probe branch existence first via a Nessie REST call (proactive) or catch the specific exception thrown by the Iceberg REST catalog when the branch URI is invalid (reactive). The proactive approach is simpler but adds a round-trip; the reactive approach avoids extra latency but requires understanding what exception the Iceberg REST catalog surfaces for an invalid branch.

Testing requires a real Nessie server for integration tests. The project uses the Nessie quarkus runner JAR (via `nessie-compatibility-common`) rather than a Docker-based testcontainer for its native Nessie integration tests. However, the context decision specifies testcontainers for this phase. Given the project's existing `DremioRestrictedTestcontainersUsage` errorprone rule (which requires `DremioContainer` interface and JUnit 5 integration test classes), a new `NessieContainer` wrapper following the existing pattern (similar to `NatsContainer` and `OpenSearchContainer`) must be created before integration tests can use testcontainers.

**Primary recommendation:** Use the reactive error detection approach — add a probing method `checkBranchExists(String branchName)` on `SupportsBranchAwareRestCatalog` that calls the Nessie REST `/config` endpoint for the branch-scoped URI (which is fast and lightweight), call it before `getDatasetHandleForBranch` only when the dataset handle returns empty, to distinguish "branch not found" from "table not found on branch". Create a `NessieContainer` following the project's testcontainers pattern and name integration test classes with `IT` suffix.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `CatalogImpl` | Dremio kernel | Three-way dispatch already in place (Phase 23) | No changes needed for multi-table queries — dispatch is per-table, each lookup independent |
| `SupportsBranchAwareRestCatalog` | Dremio kernel | Interface for branch-aware REST catalog (Phase 23) | Must add error-related methods for branch-not-found detection |
| `BranchAwareCatalogAccessorCache` | icebergcatalog plugin | Per-branch Caffeine cache (Phase 22) | Cache isolation already prevents cross-branch table cache interference |
| `ReferenceNotFoundException` | `sabot/kernel/src/main/java/com/dremio/exec/store/` | Exception type for branch-not-found errors | Locked decision: reuse same exception as native Nessie |
| `UserException` | Dremio | Structured error messages propagated to UI | `UserException.validationError().message(...).buildSilently()` is the standard pattern |
| Testcontainers | 1.x (transitive via project) | Docker-based Nessie server for integration tests | Locked decision; project already uses this infrastructure for other modules |
| Nessie Iceberg REST API | 0.100.3 | Target server for integration tests | Already in project as `nessie.version=0.100.3`, quarkus runner JAR exists |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.apache.iceberg.exceptions.NoSuchTableException` | 1.7.0 (dremio fork) | Thrown by Iceberg REST catalog for table-not-found | Catch in `getDatasetHandleForBranch` to distinguish table-not-found from branch-not-found |
| `DremioContainer` marker interface | project internal | Required by `DremioRestrictedTestcontainersUsage` errorprone rule | Any new testcontainer class MUST implement this interface |
| `DremioTestcontainersUsageValidator` | project internal | Validates testcontainers are only used in IT classes | Integration test class names MUST start or end with `IT` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Testcontainers for Nessie | Nessie quarkus runner via `nessie-compatibility-common` (existing dataplane-tests pattern) | Quarkus runner is more mature in project, but locked decision specifies testcontainers |
| Probe branch existence before `getDatasetHandleForBranch` | Catch `RESTException` / `NoSuchNamespaceException` from `tableExists()` | Reactive (exception-catching) avoids extra round-trip but is fragile: exception type depends on Nessie server behavior for an invalid branch URI |
| Dedicated NessieContainer in `tools/testcontainers/nessie` | Inline container creation in test | Separate module follows project pattern (NatsContainer, ZookeeperContainer, OpenSearchContainer all have their own tool module) |

## Architecture Patterns

### Cross-Branch JOIN/UNION: Why It Works Without Changes

Multi-table queries work by design because CatalogImpl resolves each table reference independently:

```
SELECT * FROM src.ns.tableA AT BRANCH "main"
       JOIN src.ns.tableB AT BRANCH "dev" ON TRUE
```

The SQL planner produces TWO `CatalogEntityKey` lookups:
1. `src.ns.tableA` with `TableVersionContext(BRANCH, "main")`
2. `src.ns.tableB` with `TableVersionContext(BRANCH, "dev")`

Each goes through `CatalogImpl.getTableSnapshot()` -> `getTableSnapshotHelper()` -> `getTableSnapshotForBranchAwareRestSource()` -> `SupportsBranchAwareRestCatalog.getDatasetHandleForBranch()`.

Branch accessors are cached by branch name in `BranchAwareCatalogAccessorCache`. The "main" accessor and "dev" accessor are separate cached instances with separate table caches. There is NO alias collision or caching interference by design.

The same table from two branches (the alias collision test case):
```
SELECT * FROM src.ns.tbl AT BRANCH "main" a JOIN src.ns.tbl AT BRANCH "dev" b ON TRUE
```
Both `src.ns.tbl` references produce separate `CatalogEntityKey` instances with different `TableVersionContext`. The separate accessor instances (one for "main", one for "dev") ensure different catalog instances are used. The planner aliases (`a`, `b`) handle SQL-level name disambiguation.

**Confidence: HIGH** — verified by examining CatalogImpl dispatch flow and BranchAwareCatalogAccessorCache isolation.

### Error Detection: Reactive vs. Proactive

The locked decision requires two distinct error messages:
1. "Requested Branch 'X' not found in source 'Y'" — branch doesn't exist
2. "Table 'X' not found on branch 'Y' in source 'Z'" — branch exists but table doesn't

**The problem**: `AbstractRestCatalogAccessor.getDatasetHandle()` calls `getCatalog().tableExists(tableIdentifier)`. When the branch URI is invalid (e.g., `http://nessie:19120/iceberg/nonexistent-branch`), the Iceberg REST Catalog's `RESTSessionCatalog.tableExists()`:
- Makes a REST call to Nessie using the branch-scoped URI
- Nessie returns a 404 or error response
- The `TableErrorHandler` converts it to `NoSuchTableException` or `NoSuchNamespaceException`
- `tableExists()` catches `NoSuchTableException` and returns `false`

Result: branch-not-found looks identical to table-not-found — both return `Optional.empty()` from `getDatasetHandleForBranch`.

**Recommendation: Proactive branch probe** — add a `validateBranch(String branchName)` method call before invoking `getDatasetHandleForBranch`. The probe calls the Nessie config endpoint (`GET /iceberg/{branch}/v1/config`) which is lightweight and cheap. If the branch doesn't exist, Nessie returns a non-200 response, allowing detection before the expensive table lookup.

Alternative (Reactive approach) is possible but brittle: catch `NoSuchNamespaceException` (which `tableExists()` does NOT catch internally) from the first call that touches the REST catalog. However, whether the first call throws `NoSuchNamespaceException` vs. silently returning false depends on the specific Iceberg REST call sequence and error mapping — this is behavior that can change with Iceberg version updates.

**Implementation**:
- Add `validateBranchExists(String branchName)` to `SupportsBranchAwareRestCatalog` interface
- Implement in `RestIcebergCatalogPlugin` by calling `getCatalogAccessorForBranch(branchName).checkState()` or a lightweight REST probe
- In `CatalogImpl.getTableSnapshotForBranchAwareRestSource()`: call `validateBranchExists()` BEFORE `getDatasetHandleForBranch()`
- If validation fails: throw `UserException.validationError().message("Requested Branch '%s' not found in source '%s'", branchName, sourceName).buildSilently()`
- If validation succeeds but `getDatasetHandleForBranch` returns empty: return null (caller sees table as not found, triggering "Table 'X' not found on branch 'Y'..." message)

However, a simpler and cleaner approach: in `getDatasetHandleForBranch` (plugin implementation), before calling `accessor.getDatasetHandle()`, make a call that verifies branch existence -- e.g., call the REST catalog's `/v1/config` endpoint which always returns the catalog configuration. This call fails fast if the branch URI prefix is invalid.

**Open question**: What does Nessie return when `GET /v1/tables?ns=...` is called against a nonexistent branch URI prefix? The answer determines whether the proactive probe is needed. This requires integration testing to verify the actual Nessie behavior.

### Error Message Patterns

**Native Nessie pattern (from `UseVersionHandler.java`):**
```java
throw UserException.validationError(e)
    .message("Requested %s not found in source %s.", requestedVersion, sourceName)
    .buildSilently();
```

For `VersionContext.ofBranch("dev")`, `requestedVersion.toString()` produces `"Branch 'dev'"`, giving: "Requested Branch 'dev' not found in source 'mysource'."

**For RESTCATALOG branch-not-found** (locked decision: same pattern):
```java
throw UserException.validationError()
    .message("Requested Branch '%s' not found in source '%s'.", branchName, sourceName)
    .buildSilently();
```

**For RESTCATALOG table-not-found-on-branch** (locked decision: improved over native Nessie):
```java
return null; // Let caller translate null to user-facing "table not found"
```
Or, if the dispatch needs to produce a specific message:
```java
throw UserException.validationError()
    .message("Table '%s' not found on branch '%s' in source '%s'.", tableName, branchName, sourceName)
    .buildSilently();
```

The native Nessie path returns null for "table not found" and the upstream error handling at the SQL layer translates that to "Table not found" with the full path. For branch-aware sources, to include branch context, the error should be thrown explicitly in `getTableSnapshotForBranchAwareRestSource()` when `handle.isEmpty()`.

**Source name access**: The `ManagedStoragePlugin` has `getName().getName()` which returns the source name. This is available in `getTableSnapshotForBranchAwareRestSource(plugin, key, context)` as `plugin.getName().getName()`.

### Testcontainers Pattern for NessieContainer

The project enforces a strict testcontainers pattern via the `DremioRestrictedTestcontainersUsage` errorprone check:
1. Any new `GenericContainer` subclass MUST implement `DremioContainer`
2. `DremioTestcontainersUsageValidator.validate()` MUST be called in `start()`
3. The class name must be specific and immutable (no `setDockerImageName` allowed)
4. Integration test classes must start or end with `IT`

**NessieContainer pattern** (following `NatsContainer` / `ZookeeperContainer` model):

```java
// In tools/testcontainers/nessie/src/main/java/com/dremio/testcontainers/nessie/NessieContainer.java
public final class NessieContainer extends GenericContainer<NessieContainer>
    implements DremioContainer {

  private static final DockerImageName IMAGE =
      DockerImageName.parse("ghcr.io/projectnessie/nessie:0.100.3");
  private static final int NESSIE_PORT = 19120;

  public NessieContainer() {
    super(IMAGE);
    addExposedPort(NESSIE_PORT);
    withEnv("QUARKUS_PROFILE", "prod");  // or appropriate config
  }

  public String getBaseUri() {
    return String.format("http://%s:%d/", getHost(), getMappedPort(NESSIE_PORT));
  }

  public String getIcebergRestUri() {
    return getBaseUri() + "iceberg";
  }

  @Override
  public void start() {
    DremioTestcontainersUsageValidator.validate();
    super.start();
  }

  @Override
  public void setDockerImageName(String dockerImageName) {
    throw new UnsupportedOperationException("Docker image name can not be changed");
  }
}
```

**WARNING**: The project's errorprone `DremioRestrictedTestcontainersUsage` rule currently blocks use of `GenericContainer` unless `DremioContainer` is implemented. This is enforced at compile time. Any test creating a raw `GenericContainer<...>` (including anonymous subclasses) will fail compilation.

**Nessie Docker image**: The correct image for Nessie 0.100.3 is `ghcr.io/projectnessie/nessie:0.100.3`. This serves both the Nessie API and Iceberg REST on the same port (`19120`) under different path prefixes (`/api/v2` for Nessie, `/iceberg` for Iceberg REST).

**Integration test class naming**: MUST use `IT` prefix or suffix to satisfy `DremioTestcontainersUsageValidator.isIntegrationTestClass()`. Example: `ITRestIcebergCatalogBranchAware` or `RestIcebergCatalogBranchAwareIT`.

**JUnit 5 requirement**: `DremioTestcontainersUsageValidator` uses JUnit 5's `TestExecutionListener` mechanism. Tests must use JUnit 5 (Jupiter) annotations. The existing `TestRestIcebergCatalogPlugin` uses JUnit 4 (`@RunWith(MockitoJUnitRunner.class)`) and `BaseTestQuery`. The integration test must be JUnit 5 to satisfy the testcontainers validator.

### Test Data Setup

Since tests cannot use Spark (locked decision: no mandatory Spark dependency), table creation must be done via the Iceberg REST API directly:

```java
// Set up REST catalog client pointing to a specific branch
Map<String, String> properties = Map.of(
    CatalogProperties.URI, nessieContainer.getIcebergRestUri() + "/main",
    CatalogProperties.CATALOG_IMPL, "org.apache.iceberg.rest.RESTCatalog"
);
RESTCatalog restCatalog = new RESTCatalog();
restCatalog.initialize("test", properties);

// Create table
Schema schema = new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));
restCatalog.createTable(TableIdentifier.of("ns", "table1"), schema);

// Create branch "dev" via Nessie API
NessieApiV2 nessieApi = NessieClientBuilder.createClientBuilder("HTTP", null)
    .withUri(nessieContainer.getBaseUri() + "api/v2")
    .build(NessieApiV2.class);
nessieApi.createReference()
    .reference(Branch.of("dev", mainBranch.getHash()))
    .create();
```

### Anti-Patterns to Avoid

- **Implementing getDatasetHandle error detection inside AbstractRestCatalogAccessor**: Error messages belong at the CatalogImpl level (where source name is available), not inside the accessor layer. The accessor should remain exception-transparent or throw informative exceptions; CatalogImpl translates to UserException.

- **Creating raw GenericContainer in integration tests**: Will fail the errorprone check. Always use a `NessieContainer` class implementing `DremioContainer`.

- **Using JUnit 4 in integration tests**: The testcontainers validator requires JUnit 5 class detection. Mix of JUnit 4 and 5 in the same test class is unsupported.

- **Catching all RuntimeExceptions broadly**: When handling branch-not-found, only catch the specific exception type that indicates a missing branch (after determining empirically what Nessie/Iceberg throws). Broad catches hide real errors.

- **Re-using the same accessor cache entry for validation**: The `getCatalogAccessorForBranch()` is lazy — the accessor is only created on first use. A probe via the accessor may trigger a full `RESTCatalog` initialization. If this is expensive, consider probing via the raw Nessie REST API (`/api/v2/trees/{branch}`) instead.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Branch existence check | Custom HTTP client calling Nessie API directly | Either (a) call `getCatalogAccessorForBranch()` and check the REST catalog's config endpoint, or (b) let the Iceberg REST catalog's exception propagate through | The branch-scoped accessor is already configured with the right URL and auth; re-implementing HTTP calls would duplicate that |
| Nessie server for tests | Mock/stub Nessie responses | Real Nessie via NessieContainer (Docker) | Mock approach would not validate actual Nessie behavior for branch existence errors, special character encoding, etc. |
| Multi-branch JOIN dispatch | New coordination layer | The Phase 23 three-way dispatch already handles it | Each table lookup is independent; no new orchestration needed |
| Special character URL encoding | Custom URL encoder | Java's `URLEncoder` or `UriBuilder` (already used by Iceberg REST client) | Iceberg REST client handles encoding internally when constructing request URLs |

## Common Pitfalls

### Pitfall 1: BranchAwareCatalogAccessorCache Creates Accessor for Invalid Branch

**What goes wrong:** `getCatalogAccessorForBranch("nonexistent")` creates and caches an `IcebergRestCatalogAccessor` pointing at `http://nessie:19120/iceberg/nonexistent`. The accessor creation succeeds because it only builds a `RESTCatalog` supplier — no network call is made at creation time. The error only surfaces when the first REST call is made (e.g., `tableExists()`).

**Why it happens:** The cache uses a `Supplier<Catalog>` pattern (lazy initialization). The `IcebergRestCatalogAccessor` stores the supplier and only calls it when `getCatalog()` is first invoked. This means an invalid branch gets cached as a "valid" entry.

**How to avoid:** The branch existence probe must happen BEFORE `getCatalogAccessorForBranch()` is called, or the probe must be part of a method that creates the accessor but validates first. Do NOT evict the cache entry on branch-not-found — the eviction is expensive and the fact that the branch doesn't exist should be surfaced via error, not silently retried.

**Warning signs:** Branch-not-found errors appear to "succeed" on the first call, then fail on subsequent calls inconsistently (due to cache eviction timing).

### Pitfall 2: Special Characters in Branch Names and URL Encoding

**What goes wrong:** A branch named `feature/my-feature` produces URI `http://nessie:19120/iceberg/feature/my-feature`. The Iceberg REST catalog concatenates this as part of the base URI, which means the path component `feature/my-feature` is treated as two path segments `feature` and `my-feature`. Nessie's REST catalog route doesn't match.

**Why it happens:** `createBranchScopedAccessor(String branchName)` in `RestIcebergCatalogPlugin` constructs `branchUri = restEndpoint + "/" + branchName`. If `branchName` contains `/`, the resulting URI has extra path segments.

**How to avoid:** URL-encode the branch name before appending: `branchUri = restEndpoint + "/" + URLEncoder.encode(branchName, StandardCharsets.UTF_8)`. But verify first what Nessie's Iceberg REST server expects — it may accept the raw slash (treating the full `feature/my-feature` as the branch ref name) or require encoding. Empirically test with the integration test.

**Warning signs:** Integration test with `feature/my-branch` branch name returns "branch not found" even when the branch exists.

### Pitfall 3: Error Message Missing Source Name in getTableSnapshotForBranchAwareRestSource

**What goes wrong:** The error message "Requested Branch 'X' not found in source 'Y'" requires the source name. Inside `getTableSnapshotForBranchAwareRestSource(ManagedStoragePlugin plugin, NamespaceKey key, TableVersionContext context)`, the source name is available as `plugin.getName().getName()`. If the method only has `branchPlugin` (the unwrapped interface), it loses the source name.

**Why it happens:** The `SupportsBranchAwareRestCatalog` interface does not expose a `getName()` method. The source name is on `ManagedStoragePlugin`.

**How to avoid:** Keep the `ManagedStoragePlugin plugin` parameter in `getTableSnapshotForBranchAwareRestSource()` (it's already there from Phase 23 implementation) and use `plugin.getName().getName()` when constructing error messages. Do NOT try to get the name from the `SupportsBranchAwareRestCatalog` interface.

**Warning signs:** Error message shows "null" or "unknown" for source name.

### Pitfall 4: Integration Test Testcontainers Validation Failure

**What goes wrong:** Integration test class fails to start with `IllegalStateException: Testcontainers usage is not enabled` or `IllegalStateException: Testcontainers is only allowed with JUnit 5 integration tests`.

**Why it happens:** Two separate validations in `DremioTestcontainersUsageValidator`:
1. `dremio.testcontainers.enabled` system property must be `true`
2. Test class name must start with `IT` or end with `IT`

The second check uses `CURRENT_TEST_CLASS` ThreadLocal populated by `DremioTestcontainersUsageValidator.executionStarted()`, which is a JUnit 5 `TestExecutionListener`. If the test uses JUnit 4, this mechanism doesn't fire.

**How to avoid:** Name the integration test class with `IT` prefix (`ITRestIcebergCatalogBranchAware`) or suffix (`RestIcebergCatalogBranchAwareIT`). Use JUnit 5 annotations (`@Test` from `org.junit.jupiter.api`). Set `dremio.testcontainers.enabled=true` in the Maven Surefire/Failsafe plugin configuration.

**Warning signs:** All container-using tests fail at the `NessieContainer.start()` call with `IllegalStateException`.

### Pitfall 5: Table-Not-Found Error Message Requires Explicit Throw

**What goes wrong:** When `getDatasetHandleForBranch` returns `Optional.empty()` (table doesn't exist on the branch), the method returns `null`. The caller in the SQL planning layer eventually reports "Unknown table" or "Object not found" without branch context.

**Why it happens:** The `getTableSnapshotForBranchAwareRestSource` method in Phase 23 returns `null` when `handle.isEmpty()`:
```java
if (handle.isEmpty()) {
    return null;
}
```
Returning null causes the upstream caller (`CatalogImpl.getTable()`) to treat it as "not found" with a generic message.

**How to avoid:** When `handle.isEmpty()` AND branch validation succeeded (branch exists), throw an explicit `UserException.validationError()` with the branch-context message. This requires knowing the table path (available from `key.getPathComponents()`) and branch name (from `resolved.getRefName()`):
```java
if (handle.isEmpty()) {
    throw UserException.validationError()
        .message("Table '%s' not found on branch '%s' in source '%s'.",
            key.toUnescapedString(), branchName, plugin.getName().getName())
        .buildSilently();
}
```

**Warning signs:** Query with AT BRANCH "main" on a nonexistent table produces "Object 'table' not found within 'source'" instead of "Table 'source.ns.table' not found on branch 'main' in source 'source'".

### Pitfall 6: enableNessie=false Regression From Error Handling Changes

**What goes wrong:** Changes to `getTableSnapshotForBranchAwareRestSource()` or `getDatasetHandleForBranchAwareRestSource()` accidentally affect non-Nessie sources. Since `isWrapperFor(SupportsBranchAwareRestCatalog.class)` returns `false` for `enableNessie=false` sources, the branch-aware code path is never entered. BUT any change to `CatalogUtil.requestedPluginSupportsBranchAwareRest()` or `PlanCacheUtils.checkForVersionedTable()` could add overhead to ALL queries.

**Why it happens:** Phase 24 adds no new dispatch conditions (the three-way dispatch already exists from Phase 23). However, if the error handling changes also modify `getTableSnapshotForNonVersionedSource()` or similar general methods, regressions can occur.

**How to avoid:** Strictly limit Phase 24 changes to methods that are ONLY called when `isWrapperFor(SupportsBranchAwareRestCatalog.class)` is true: `getTableSnapshotForBranchAwareRestSource()` and `getDatasetHandleForBranchAwareRestSource()`. No changes to shared paths.

**Warning signs:** Tests for non-Nessie RESTCATALOG sources fail after Phase 24 changes.

## Code Examples

Verified patterns from codebase analysis:

### Native Nessie Error Message Pattern (UseVersionHandler)

```java
// Source: sabot/kernel/.../handlers/UseVersionHandler.java line 76-79
try {
    resolvedVersionContext = versionedPlugin.resolveVersionContext(requestedVersion);
} catch (ReferenceNotFoundException e) {
    throw UserException.validationError(e)
        .message("Requested %s not found in source %s.", requestedVersion, sourceName)
        .buildSilently();
}
// VersionContext.ofBranch("dev").toString() -> "Branch 'dev'"
// Full message: "Requested Branch 'dev' not found in source 'mysource'."
```

### Branch-Not-Found Error in getTableSnapshotForBranchAwareRestSource

```java
// To be added in CatalogImpl.getTableSnapshotForBranchAwareRestSource():
private DremioTable getTableSnapshotForBranchAwareRestSource(
        ManagedStoragePlugin plugin, NamespaceKey key, TableVersionContext tableVersionContext) {
    SupportsBranchAwareRestCatalog branchPlugin =
        plugin.getPlugin().get().unwrap(SupportsBranchAwareRestCatalog.class);

    VersionContext versionContext = tableVersionContext.asVersionContext();
    ResolvedVersionContext resolved = branchPlugin.resolveVersionContext(versionContext);
    String branchName = resolved.getRefName();
    String sourceName = plugin.getName().getName();

    // Validate branch exists (returns false if branch not found, throws only on network error)
    if (!branchPlugin.branchExists(branchName)) {
        throw UserException.validationError()
            .message("Requested Branch '%s' not found in source '%s'.", branchName, sourceName)
            .buildSilently();
    }

    EntityPath entityPath = new EntityPath(key.getPathComponents());
    Optional<DatasetHandle> handle = branchPlugin.getDatasetHandleForBranch(branchName, entityPath);

    if (handle.isEmpty()) {
        throw UserException.validationError()
            .message("Table '%s' not found on branch '%s' in source '%s'.",
                key.toUnescapedString(), branchName, sourceName)
            .buildSilently();
    }

    // ... build MaterializedDatasetTableProvider as in Phase 23 ...
}
```

### SupportsBranchAwareRestCatalog Extended with branchExists

```java
// New method to add to the interface:
public interface SupportsBranchAwareRestCatalog extends Wrapper {
    ResolvedVersionContext resolveVersionContext(VersionContext versionContext);
    Optional<DatasetHandle> getDatasetHandleForBranch(String branchName, EntityPath datasetPath, GetDatasetOption... options);
    String getDefaultBranch();

    /**
     * Returns true if the branch exists in this Nessie-backed catalog.
     * Does NOT throw -- returns false for branch-not-found, propagates only network/auth errors.
     */
    boolean branchExists(String branchName);
}

// Implementation in RestIcebergCatalogPlugin:
@Override
public boolean branchExists(String branchName) {
    try {
        CatalogAccessor accessor = getCatalogAccessorForBranch(branchName);
        // A lightweight probe: try to list tables with empty namespace.
        // If branch doesn't exist, Nessie returns an error (400 or 404)
        // that the Iceberg REST catalog surfaces as an exception.
        accessor.getDatasetHandle(
            List.of(getName(), "probe_namespace", "probe_table"), this);
        // If we get here (even empty result), the branch URI is valid
        return true;
    } catch (/* specific exception type */ e) {
        return false;
    }
}
```

**IMPORTANT**: The specific exception type that indicates "branch not found" vs "network error" must be determined empirically via integration testing. Candidate exception types from Iceberg:
- `org.apache.iceberg.exceptions.RESTException` (catchall for unexpected HTTP responses)
- `org.apache.iceberg.exceptions.ServiceFailureException` (HTTP 500/503)
- `org.apache.iceberg.exceptions.NoSuchNamespaceException` (HTTP 404 from `TableErrorHandler`)

A probing approach without making a data-layer call: use the Nessie REST API directly (via the Nessie client) if the icebergcatalog module adds `nessie-client` as a test dependency. However, adding Nessie client as a compile dependency to the icebergcatalog plugin would create an undesirable tight coupling. The Iceberg-level probe is preferred.

### NessieContainer Testcontainers Pattern

```java
// Location: tools/testcontainers/nessie/src/main/java/.../NessieContainer.java
// Following the NatsContainer pattern exactly

package com.dremio.testcontainers.nessie;

import com.dremio.testcontainers.DremioContainer;
import com.dremio.testcontainers.DremioTestcontainersUsageValidator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public final class NessieContainer extends GenericContainer<NessieContainer>
    implements DremioContainer {

  private static final DockerImageName IMAGE =
      DockerImageName.parse("ghcr.io/projectnessie/nessie:0.100.3");
  private static final int NESSIE_PORT = 19120;

  public NessieContainer() {
    super(IMAGE);
    addExposedPort(NESSIE_PORT);
  }

  public String getBaseUri() {
    return String.format("http://%s:%d/", getHost(), getMappedPort(NESSIE_PORT));
  }

  /** Iceberg REST endpoint, e.g. http://host:port/iceberg */
  public String getIcebergRestUri() {
    return getBaseUri() + "iceberg";
  }

  @Override
  public void start() {
    DremioTestcontainersUsageValidator.validate();
    super.start();
  }

  @Override
  public void setDockerImageName(String dockerImageName) {
    throw new UnsupportedOperationException("Docker image name can not be changed");
  }
}
```

### Integration Test Class Structure

```java
// Location: plugins/icebergcatalog/src/test/java/.../store/ITRestIcebergCatalogBranchAware.java
// JUnit 5, IT prefix, uses NessieContainer

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class ITRestIcebergCatalogBranchAware {

  @Container
  private static final NessieContainer nessie = new NessieContainer();

  private static RestIcebergCatalogPlugin plugin;

  @BeforeAll
  static void setUp() throws Exception {
    RestIcebergCatalogPluginConfig config = new RestIcebergCatalogPluginConfig();
    config.path = nessie.getIcebergRestUri();
    config.enableNessie = true;
    // ... create plugin using config, start it
    // ... create test data via Iceberg REST API
  }

  @Test
  void testJoinAcrossBranches() throws Exception {
    // Arrange: create table on "main", create "dev" branch, add data on "dev"
    // Act: run SELECT ... AT BRANCH "main" JOIN ... AT BRANCH "dev"
    // Assert: results combine data from both branches
  }

  @Test
  void testBranchNotFoundError() {
    // Arrange: query with AT BRANCH "nonexistent"
    // Act/Assert: UserException with "Requested Branch 'nonexistent' not found in source"
  }

  @Test
  void testTableNotFoundOnBranchError() {
    // Arrange: valid branch, table doesn't exist on that branch
    // Act/Assert: UserException with "Table 'X' not found on branch 'Y' in source 'Z'"
  }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| AT BRANCH throws error on RESTCATALOG | AT BRANCH dispatches to branch-scoped accessor (Phase 23) | Phase 23 complete | Phase 24 builds on this: adds error handling and integration tests |
| No branch-not-found vs table-not-found distinction | Explicit error messages with branch context | Phase 24 (this phase) | Users get actionable error messages |
| No integration tests for RESTCATALOG+Nessie branch queries | Integration tests against real Nessie server | Phase 24 (this phase) | End-to-end validation of the entire AT BRANCH stack |
| `SupportsBranchAwareRestCatalog` has 3 methods | Adds `branchExists()` as 4th method | Phase 24 (this phase) | Enables branch-not-found detection at CatalogImpl level |

**Existing patterns preserved (regression safety):**
- `enableNessie=false`: `isWrapperFor(SupportsBranchAwareRestCatalog.class)` returns false, zero new code executes
- Non-AT-BRANCH queries on Nessie-enabled source: still go through `getTable(NamespaceKey)` path, unchanged
- Native Nessie sources: completely unaffected, no shared code paths

## Open Questions

1. **What exception does Nessie's Iceberg REST return for an invalid branch URI?**
   - What we know: The Iceberg `TableErrorHandler` handles 404 from Nessie REST as `NoSuchTableException` (caught by `tableExists()`, returns false) or `NoSuchNamespaceException` (not caught, propagates). Which one Nessie returns for an invalid branch depends on the specific endpoint called and Nessie's error mapping.
   - What's unclear: Whether `tableExists()` silently returns false (hiding the branch error) or throws an uncaught exception when the branch is invalid.
   - Recommendation: Write an early integration test (first task in the plan) that queries a nonexistent branch and observes what exception propagates to the `getDatasetHandleForBranch` caller. This determines whether the reactive or proactive approach is needed.

2. **How should the `branchExists` probe be implemented without network round-trip overhead?**
   - What we know: `getCatalogAccessorForBranch(branchName)` is already cached (Caffeine). A probe via the accessor makes one REST call. For the happy path (valid branch), this adds one extra round-trip per FIRST query on a branch (subsequent queries use the cached accessor).
   - What's unclear: Whether the extra round-trip is acceptable for the happy path, given that branch accessors are cached.
   - Recommendation: Accept the extra round-trip for the first query on a branch. After the first successful probe, the accessor is cached and subsequent `branchExists` calls could also be cached (e.g., cache successful branch probes alongside the accessor). For MVP, the single extra round-trip on first use is acceptable.

3. **Should `branchExists()` be added to the `SupportsBranchAwareRestCatalog` interface or handled entirely within `CatalogImpl`?**
   - What we know: The `SupportsBranchAwareRestCatalog` interface is in `sabot/kernel`. The branch existence probe needs access to the accessor (in `plugins/icebergcatalog`). Keeping the probe logic in the plugin keeps the kernel independent of plugin internals.
   - Recommendation: Add `branchExists(String branchName)` to the interface. Implement in `RestIcebergCatalogPlugin`. This is consistent with the pattern established by `getDatasetHandleForBranch`.

4. **Where to create the NessieContainer module?**
   - What we know: Existing testcontainers tools live in `tools/testcontainers/{name}/` as separate Maven modules. The icebergcatalog module can reference this module as a test dependency.
   - Recommendation: Create `tools/testcontainers/nessie/` module following the `nats` module as a template. Add as test dependency to `plugins/icebergcatalog/pom.xml`. The module must include `tools/testcontainers/core` as a compile dependency.

5. **Does the Nessie Docker image support the Iceberg REST protocol on the same port as the Nessie API?**
   - What we know: Nessie 0.100.3 serves both the Nessie REST API (`/api/v2`) and the Iceberg REST catalog (`/iceberg`) on the same port (default: 19120). This is the expected behavior based on Nessie's architecture.
   - What's unclear: Whether any additional Quarkus configuration is needed to enable the Iceberg REST endpoint in the Docker image.
   - Recommendation: Start with the default Docker image and no extra configuration. If the Iceberg REST endpoint is not available, add the Quarkus configuration via `withEnv("QUARKUS_NESSIE_CATALOG_ENABLED", "true")` or equivalent.

## Sources

### Primary (HIGH confidence)
- `CatalogImpl.java` (lines 887-917, 1110-1126) — Phase 23 implementation of `getTableSnapshotForBranchAwareRestSource` and `getDatasetHandleForBranchAwareRestSource`; confirmed three-way dispatch is per-table, handles multi-table queries by design
- `BranchAwareCatalogAccessorCache.java` — Caffeine cache implementation; confirmed separate instances per branch, lazy accessor creation on first REST call
- `RestIcebergCatalogPlugin.java` (lines 258-315) — `getCatalogAccessorForBranch`, `getDatasetHandleForBranch`, `isWrapperFor` implementation; confirmed from Phase 23
- `AbstractRestCatalogAccessor.java` (lines 350-364) — `getDatasetHandle` calls `tableExists()` then returns `Optional.empty()` if not found; does NOT propagate branch-related exceptions
- `UseVersionHandler.java` (lines 76-79) — Native Nessie error message pattern: "Requested Branch 'X' not found in source 'Y'"
- `ReferenceNotFoundException.java` — Simple unchecked runtime exception; confirmed for branch-not-found reuse
- `DremioRestrictedTestcontainersUsage.java` and `DremioTestcontainersUsageValidator.java` — Confirmed testcontainers constraints: must implement `DremioContainer`, must be JUnit 5, class name must start/end with `IT`
- `NatsContainer.java` — Template pattern for `DremioContainer` implementation
- `ErrorHandlers.class` (bytecode analysis) — Confirmed `TableErrorHandler` maps HTTP 404 to `NoSuchTableException`/`NoSuchNamespaceException`; `DefaultErrorHandler` maps unhandled codes to `RESTException`; 404 NOT handled by `DefaultErrorHandler` default case
- `RESTSessionCatalog.class` (bytecode analysis) — `tableExists()` catches `NoSuchTableException` and returns false; if branch is invalid and Nessie returns 404 as `NoSuchTableException`, branch-not-found is silently swallowed

### Secondary (MEDIUM confidence)
- `ITDataplanePluginJoin.java` — Existing cross-branch JOIN tests for native Nessie plugin; confirmed test patterns for multi-table AT BRANCH queries (both branches on same and different tables)
- `DataplaneTestDefines.java` (lines 1136-1162) — SQL query templates for JOIN patterns with AT BRANCH; directly usable as reference for Phase 24 test queries
- Nessie 0.100.3 Docker image (`ghcr.io/projectnessie/nessie:0.100.3`) — confirmed in project's pom.xml; both Nessie API and Iceberg REST on port 19120 is standard Nessie behavior

### Tertiary (LOW confidence)
- URL encoding behavior for branch names with `/` in Nessie Iceberg REST — needs empirical verification; the `createBranchScopedAccessor` method does string concatenation without encoding
- Exact exception type from `tableExists()` when branch is invalid — requires integration test to determine; theoretical analysis based on `TableErrorHandler` bytecode only

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all types and infrastructure verified from direct codebase analysis
- Multi-branch JOIN architecture: HIGH — confirmed that Phase 23 three-way dispatch is per-table, no additional work needed for BRQ-03
- Error handling approach: MEDIUM — `branchExists` method design is sound, but exact exception behavior needs integration test to confirm (LOW confidence on specific exception type)
- Testcontainers pattern: HIGH — `DremioContainer` requirements fully documented from source code; NessieContainer pattern verified against `NatsContainer` template
- Test data setup: HIGH — Iceberg REST API approach is verified and standard; no Spark dependency needed

**Research date:** 2026-03-10
**Valid until:** 2026-04-10 (30 days — stable domain; `ErrorHandlers` bytecode valid for Iceberg 1.7.0-dremio-fork version pinned in pom.xml)
