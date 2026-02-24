# Phase 14: Test Coverage and Documentation - Research

**Researched:** 2026-02-23
**Domain:** JUnit 4 unit test strengthening, REST API integration test patterns, REQUIREMENTS.md documentation
**Confidence:** HIGH

## Summary

Phase 14 is a debt-closure phase. Its four success criteria map to three distinct tech debt items identified during prior verification passes — one in TestCatalogImpl (PDS-02 unit tests), one in TestRbacIntegration (CONT-01/02 verified via SQL proxy instead of REST listing endpoint), and one in TestRbacDdlHandlers (ExplainHandler structural test instead of behavioral test). The fourth criterion (REQUIREMENTS.md traceability) was already satisfied during gap-closure planning and does NOT need work.

Each success criterion has a clearly identified existing test class, an existing production method under test, and a well-understood fix pattern. No new infrastructure is needed. All three test files are in the `sabot/kernel` and `dac/backend` modules, both of which already compile and run successfully. The primary risk in each case is getting mock wiring right for (1), getting the REST login/user context right for (2), and constructing a minimally-mockable `SqlHandlerConfig` for (3).

**Primary recommendation:** Write three targeted test additions — one using `MockedConstruction<DatasetManager>` to inject a non-null non-ViewTable so `isRbacDeniedForPds` is actually triggered, one adding a REST API catalog listing assertion to `TestRbacIntegration`, and one using a real mocked `SqlHandlerConfig` to call `ExplainHandler.toResult()` with a deny-by-null catalog. No production code changes needed.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PDS-02 | PDS are only accessible to users with a SELECT grant (deny-by-default) | Unit tests in TestCatalogImpl must exercise `isRbacDeniedForPds` with a non-null table. Use `MockedConstruction<DatasetManager>` to return a non-null, non-ViewTable mock so the guard chain actually evaluates to `true` (deny) or `false` (allow). |
| CONT-01 | Sources are only visible to non-admin users if they have access to at least one child object | `filterByRbacVisibility` in `SourcesResource.getSources()` is the targeted code path. Add a REST API test to `TestRbacIntegration` that calls `GET /api/v2/sources` and asserts on the JSON listing — source appears when user has a grant under it, hidden when they do not. |
| CONT-02 | Spaces are only visible to non-admin users if they have access to at least one child object | `getTopLevelCatalogItems` in `CatalogServiceHelper` handles spaces. Add a REST API test calling `GET /api/v3/catalog` and asserting on the listed items (space visible/hidden). |
| META-03 | EXPLAIN requires privileges on all objects referenced in the plan | `ExplainHandler.toResult()` is the entry point. Write a behavioral test by constructing an `ExplainHandler` with a mocked `SqlHandlerConfig` that provides a deny-by-null catalog; confirm the `toResult()` call propagates the permission error rather than succeeding. |
</phase_requirements>

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JUnit 4 | 4.13.x (project standard) | Test runner | Already used in all target test files |
| Mockito 4.x | Project standard | Mock construction, behavior verification | `MockedConstruction<DatasetManager>` already used at TestCatalogImpl line 448 |
| AssertJ 3.x | Project standard | Fluent assertions | Already used (`assertThat`, `containsIgnoringCase`, `isTrue`, `isFalse`) |
| BaseTestServer | Internal | In-process Dremio server for REST tests | Already extended by `TestRbacIntegration` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| MockedConstruction (Mockito) | Same as Mockito | Intercept `new DatasetManager(...)` constructor | Needed for PDS-02 to inject a non-null table into CatalogImpl.getTable() |
| javax.ws.rs.client (JAX-RS) | Project standard | HTTP client for REST assertions | Needed for CONT-01/02 REST endpoint tests |
| Jackson / GenericType | Project standard | Deserialize JSON responses | Needed to parse `/api/v2/sources` and `/api/v3/catalog` responses |

---

## Architecture Patterns

### Pattern 1: MockedConstruction for DatasetManager (PDS-02 fix)

**What:** `TestCatalogImpl` uses `mockConstructionWithAnswer(DatasetManager.class, ...)` to control what `getTable()` returns inside a `try` block. This is the only way to make `isRbacDeniedForPds` fire in a unit test, because the method only runs when `getTable` returns a non-null non-ViewTable.

**When to use:** For the three tests that currently assert `verify(never()).hasPrivilege(...)` at construction time — these must be replaced with tests that pass a non-null mock table through the DatasetManager mock so the guard chain actually evaluates.

**Existing precedent at TestCatalogImpl lines 448–466:**
```java
// Source: sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java lines 448-466
try (MockedConstruction<DatasetManager> ignored =
    mockConstructionWithAnswer(
        DatasetManager.class,
        invocation -> {
          if ("getTable".equals(invocation.getMethod().getName())) {
            return tableToBeUpdated;  // non-null table returned
          }
          return invocation.callRealMethod();
        })) {
  CatalogImpl catalog = newCatalogImpl(versionContextResolver);
  DremioTable actual = catalog.getTable(key);
  assertEquals(updatedTable, actual);
}
```

**For PDS deny/bypass tests, the mock table must NOT be a ViewTable** (ViewTable check at line 2968 is the first guard — if it is a ViewTable, the method returns false immediately regardless of other flags):
```java
// A non-ViewTable that is treated as a PDS:
DremioTable pdsTable = mock(DremioTable.class);  // NOT mock(ViewTable.class)
when(pdsTable.getPath()).thenReturn(new NamespaceKey(Arrays.asList("source", "table")));
```

**Meaningful assertion for flag-off/bypass tests:**
- `testPdsAccess_pdsFeatureFlagOff_noEnforcement`: flag-off path means `getTable()` should return the table (not null). Use `assertThat(result).isNotNull()` AND `verify(rbacService, never()).hasPrivilege(...)`. The key improvement: the `never()` is now meaningful because we ARE calling `getTable()` with a non-null-returning DatasetManager.
- `testPdsAccess_rbacDisabled_noEnforcement`: same pattern with RBAC_ENABLED=false.
- `testPdsAccess_systemUser_bypassesPdsEnforcement`: same pattern with `newCatalogImplForUser("$dremio$")`.

**For deny and allow tests:**
- `testPdsAccess_userDenied`: `hasPrivilege` returns false → `getTable()` returns null (denied). Assert `result == null`.
- `testPdsAccess_userGranted`: `hasPrivilege` returns true → `getTable()` returns the table. Assert `result == pdsTable`.

### Pattern 2: REST API listing assertion in TestRbacIntegration (CONT-01/02 fix)

**What:** Call `GET /api/v2/sources` and `GET /api/v3/catalog` as the non-admin USER (after logging in), deserialize the response, and assert that a specific source/space is present or absent based on whether USER has a grant under it.

**Login flow in BaseTestServer:**
```java
// Source: BaseTestServer.java line 348-349
login(USER, PASSWORD);  // sets HTTP session for subsequent getBuilder() calls
// ... then use getBuilder(getHttpClient().getAPIv2().path("/sources")).buildGet()
login(ADMIN, PASSWORD); // restore admin session after assertions
```

**Sources endpoint (CONT-01) — existing pattern from TestSourcesResource:**
```java
// Source: dac/backend/src/test/java/com/dremio/dac/resource/TestSourcesResource.java line 33-44
String sources = expectSuccess(
    getBuilder(
        getHttpClient().getAPIv2().path("/sources")).buildGet(),
    String.class);
// assertThat(sources).contains(sourceName) or .doesNotContain(sourceName)
```

**Catalog endpoint (CONT-02) — existing pattern from TestServer:**
```java
// Source: dac/backend/src/test/java/com/dremio/dac/server/TestServer.java line 1206
Response invoke = getBuilder(getHttpClient().getAPIv3().path("catalog")).buildGet().invoke();
```

**Critical constraint:** TestRbacIntegration uses `initializeCluster(new DACDaemonModule(), o -> o, configs)` which is a full server with sources. The default cluster includes system sources (`sys`, `__home`, `__accelerator`, `__support`). Admin users always see everything. When logged in as USER with no grants, the sources listing should contain ONLY sources for which USER has at least one child grant. System sources may not be filtered (depends on admin-bypass for internal sources).

**Space visibility:** Spaces are in the catalog listing. After creating `cont_vis_space` and granting SELECT on a view inside it, the space should appear in `GET /api/v3/catalog`. Without the grant, it should not appear. Use a unique space name to avoid confusion with other test spaces.

**Parsing strategy:** Rather than full JSON deserialization to `CatalogListResponse`, use `String.class` and `assertThat(body).contains(spaceName)` / `assertThat(body).doesNotContain(spaceName)`. This avoids model coupling and is consistent with `TestSourcesResource` patterns.

### Pattern 3: Behavioral ExplainHandler test (META-03 fix)

**What:** Construct an `ExplainHandler` with a mocked `SqlHandlerConfig`, pass a valid `EXPLAIN PLAN FOR SELECT` SQL node, and verify that when the inner `NormalHandler` fails with a permission error (catalog returns null for the referenced VDS), the exception propagates through `ExplainHandler.toResult()`.

**ExplainHandler structure:**
- Takes `SqlHandlerConfig` in constructor (line 63: `public ExplainHandler(SqlHandlerConfig config)`)
- `toResult(String sql, SqlNode sqlNode)` wraps all exceptions in `SqlExceptionHelper.coerceException()` which preserves `UserException` (line 137-139)
- Inner handler `NormalHandler.getPlan()` calls `config.getContext().getCatalog().getTable(key)` which returns null when RBAC denies → `UserException` "Unknown table"

**Challenge:** `SqlHandlerConfig` requires a `QueryContext`, which requires a `UserSession`, which requires an `OptionManager`, etc. — deep mock chain. The existing `TestRbacDdlHandlers` mocks this chain for `DescribeTableHandler` (line 26-506 in TestRbacDdlHandlers.java). Read that setup to replicate.

**Alternative approach (lower setup cost):** Rather than calling through `NormalHandler.getPlan()`, verify the behavior at the `SqlHandlerConfig.getContext().getCatalog()` level by configuring the mock catalog to throw a `UserException` when `getTable` is called with the EXPLAIN target path. This tests that `ExplainHandler.toResult()` propagates the exception rather than swallowing it.

**Existing TestRbacDdlHandlers test scaffolding to reuse:**
```
sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/TestRbacDdlHandlers.java
```
The class already mocks: `SqlHandlerConfig`, `QueryContext`, `UserSession`, `Catalog`, `OptionManager`. Adding one more test method that uses this same setup is the lowest-friction approach.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| DatasetManager injection | Custom subclass | `mockConstructionWithAnswer` | Already used in TestCatalogImpl line 448 |
| HTTP session for USER | Custom HTTP client | `login(USER, PASSWORD)` then `login(ADMIN, PASSWORD)` to restore | BaseTestServer manages the HTTP session |
| JSON response parsing | Jackson ObjectMapper | `String.class` + `contains()` | Sufficient for name-based assertions; avoids model coupling |
| SqlNode construction | Raw Calcite parser | Mock the inner handler interaction with the catalog | ExplainHandler delegates; testing the delegation chain via catalog mock is sufficient |

---

## Common Pitfalls

### Pitfall 1: verify(never()) before getTable() is called
**What goes wrong:** Tests `testPdsAccess_pdsFeatureFlagOff_noEnforcement`, `testPdsAccess_rbacDisabled_noEnforcement`, `testPdsAccess_systemUser_bypassesPdsEnforcement` create a `CatalogImpl` then call `verify(never())` without ever calling `catalog.getTable()`. The `never()` is trivially true — `isRbacDeniedForPds` is never triggered.
**Why it happens:** `isRbacDeniedForPds` is only called when `getTable*()` returns a non-null table. The unit test setup uses no DatasetManager mock, so `DatasetManager` either returns null or the test never reaches the RBAC guard.
**How to avoid:** Wrap the test body in `try (MockedConstruction<DatasetManager> ignored = mockConstructionWithAnswer(...))` that returns a non-null non-ViewTable mock from `getTable`. Then call `catalog.getTable(key)`, and the RBAC guard actually fires.
**Warning signs:** Test does `newCatalogImpl(...)` then immediately `verify(rbacService, never())` with no `catalog.getTable*(...)` call in between.

### Pitfall 2: Using ViewTable mock for the PDS deny test
**What goes wrong:** `isRbacDeniedForPds` has a `instanceof ViewTable` check at line 2968 that short-circuits to `return false` (allow). If the DatasetManager mock returns a ViewTable, the PDS guard never fires even with correct flag setup.
**Why it happens:** VDS and PDS reuse the same `getTable()` method; the ViewTable type distinguishes them at runtime.
**How to avoid:** Use `mock(DremioTable.class)` (NOT `mock(ViewTable.class)`). The `DremioTable` interface is the base — a plain mock of it will NOT be instanceof ViewTable.

### Pitfall 3: Login session bleeds between REST tests
**What goes wrong:** After calling `login(USER, PASSWORD)`, all subsequent `getBuilder()` calls use the USER session. If the test fails before restoring `login(ADMIN, PASSWORD)`, later tests run as USER and fail unexpectedly.
**Why it happens:** `getHttpClient()` returns a shared client; `login()` mutates session state.
**How to avoid:** Use try/finally to restore admin login after USER-context assertions. TestRbacIntegration currently uses no try/finally — this is a correctness risk if assertions throw.

### Pitfall 4: REST catalog listing includes unexpected items
**What goes wrong:** `GET /api/v3/catalog` returns a top-level listing that may include system sources, home spaces, and other spaces created by concurrent tests. `assertThat(body).contains(spaceName)` may produce false positives if spaceName is a substring of another item name.
**Why it happens:** The test server is shared across all tests in `TestRbacIntegration`. Tests run sequentially (JUnit 4, single thread), but state from earlier tests persists.
**How to avoid:** Use sufficiently unique space names (`cont_vis_test_space_hidden` not just `hidden`) and use `assertThat(body).containsPattern("\"name\"\\s*:\\s*\"" + spaceName + "\"")` or simply check for the exact JSON string fragment including surrounding quotes. Even simpler: check for `"\"" + spaceName + "\""` (name with quotes).

### Pitfall 5: ExplainHandler behavioral test requires full SQL parse
**What goes wrong:** `ExplainHandler.toResult(String sql, SqlNode sqlNode)` requires a real `SqlNode` of kind `EXPLAIN`. Constructing one without the Calcite parser is complex.
**Why it happens:** The method calls `SqlNodeUtil.unwrap(sqlNode, SqlExplain.class)` which fails with a ClassCastException if sqlNode is not a SqlExplain.
**How to avoid:** Either (a) parse real SQL using the existing `SqlConverter` infrastructure available in `TestRbacDdlHandlers` — but this is heavyweight, or (b) test at a slightly different level: verify that `ExplainHandler` passes `config` (which contains the catalog) to the inner handler, and the inner handler fails when the catalog denies access. The integration test `TestRbacIntegration.testExplain_denied_withoutSelect` already proves the end-to-end behavioral path. The unit test just needs to be more specific than the current structural assertion. A minimal approach: mock `SqlExplain` to return depth PHYSICAL, kind ORDER (default case) so that NormalHandler is selected, then configure the mock catalog to throw UserException. The `SqlExceptionHelper.coerceException()` at line 137-139 of ExplainHandler re-throws UserException, so `assertThatThrownBy(() -> handler.toResult(sql, sqlNode)).isInstanceOf(UserException.class)` is the assertion.

---

## Code Examples

### PDS-02: Rewritten flag-off test using MockedConstruction

```java
// Source: Based on TestCatalogImpl.java line 448-466 (existing precedent)
@Test
public void testPdsAccess_pdsFeatureFlagOff_noEnforcement() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(false);

    // A non-null, non-ViewTable to make isRbacDeniedForPds actually run
    DremioTable pdsTable = mock(DremioTable.class);
    NamespaceKey key = new NamespaceKey(Arrays.asList("source", "table"));

    try (MockedConstruction<DatasetManager> ignored =
        mockConstructionWithAnswer(
            DatasetManager.class,
            invocation -> {
              if ("getTable".equals(invocation.getMethod().getName())) {
                return pdsTable;
              }
              return invocation.callRealMethod();
            })) {
      CatalogImpl catalog = newCatalogImpl(versionContextResolver);
      DremioTable result = catalog.getTable(key);
      // With PDS flag OFF: table returned (not denied), hasPrivilege never called
      assertThat(result).isEqualTo(pdsTable);
      verify(rbacService, never()).hasPrivilege(anyString(), eq("SELECT"), eq("PDS"), anyString());
    }
}
```

### CONT-01: REST API sources listing in TestRbacIntegration

```java
// Source: BaseTestServer.java login pattern + TestSourcesResource.java sources GET pattern
@Test
public void testContainerVisibility_sourcesListing_userSeesOnlyAccessibleSources() {
    // USER has no grants under any source at this point
    // Login as USER and check /sources listing
    try {
        login(USER, PASSWORD);
        String sources = expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("/sources")).buildGet(),
            String.class);
        // USER has no grants under "cp" source — cp should NOT appear
        // Note: system sources (__home, __accelerator, etc.) may be filtered or not
        // depending on admin-bypass behavior for internal sources.
        // At minimum, verify the response is a valid JSON sources list.
        assertThat(sources).isNotNull();
        // After granting access under a source, it should appear:
        // (grant setup here if needed)
    } finally {
        login(ADMIN, PASSWORD); // always restore admin session
    }
}
```

### CONT-02: REST API catalog listing for space visibility

```java
// Source: TestServer.java line 1206-1207 pattern
@Test
public void testContainerVisibility_catalogListing_spaceHiddenWithoutAccess() {
    createSpaceIfNotExists("cont_hidden_space_14");
    runSqlAsAdmin("CREATE VIEW cont_hidden_space_14.v1 AS SELECT 1 AS id");
    // No grants to USER_ROLE

    try {
        login(USER, PASSWORD);
        String catalog = expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildGet(),
            String.class);
        // Space with no accessible children should not appear
        assertThat(catalog).doesNotContain("\"cont_hidden_space_14\"");
    } finally {
        login(ADMIN, PASSWORD);
    }
}

@Test
public void testContainerVisibility_catalogListing_spaceVisibleWithAccess() {
    createSpaceIfNotExists("cont_visible_space_14");
    runSqlAsAdmin("CREATE VIEW cont_visible_space_14.v1 AS SELECT 1 AS id");
    grantSelectOnVds("cont_visible_space_14.v1");

    try {
        login(USER, PASSWORD);
        String catalog = expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildGet(),
            String.class);
        assertThat(catalog).contains("\"cont_visible_space_14\"");
    } finally {
        login(ADMIN, PASSWORD);
    }
}
```

### META-03: ExplainHandler behavioral test

The approach: add to `TestRbacDdlHandlers` (which already has `SqlHandlerConfig`, `QueryContext`, `Catalog` mocks). Configure `catalog.getTable()` to return null (deny-by-null). Call `handler.toResult()` with a mocked `SqlExplain` sqlNode. Assert that a `UserException` propagates.

```java
// Location: TestRbacDdlHandlers.java — add new test alongside existing testExplainHandler_*
// Key challenge: SqlHandlerConfig needs QueryContext -> Catalog interaction.
// The existing describe test setup at TestRbacDdlHandlers already wires this chain.
// Replicate that setup for the EXPLAIN case.

@Test
public void testExplainHandler_denied_propagatesPermissionError() {
    // When inner NormalHandler calls catalog.getTable(key) and gets null,
    // a "Table/View not found" UserException propagates through ExplainHandler.toResult().
    // ExplainHandler.toResult() line 137: catch(Exception ex) -> SqlExceptionHelper.coerceException()
    // UserException subclasses are preserved by coerceException.

    // The structural test (testExplainHandler_inheritsPrivilegeEnforcement_fromInnerHandlers)
    // already documents that ExplainHandler has no rbacService field.
    // This test proves the behavioral consequence: EXPLAIN on a denied table fails.

    // Integration-level proof already in TestRbacIntegration.testExplain_denied_withoutSelect.
    // Unit test approach: verify via a mock SqlHandlerConfig that configures the catalog
    // to throw UserException, then confirm ExplainHandler re-throws it.

    // See TestRbacDdlHandlers constructor for how config/context/catalog mocks are set up.
    // The test must construct ExplainHandler(config) and call toResult(sql, mockSqlExplain).
    // The SqlExplain mock must satisfy: unwrap -> SqlExplain, operand(2) -> SqlLiteral(PHYSICAL),
    // operand(0) -> SqlNode with kind ORDER_BY or SELECT (default case for NormalHandler).
}
```

**Simpler META-03 alternative:** Rather than constructing a full SqlExplain mock, verify via reflection + behavior test that the delegation chain is correct. The `testExplainHandler_inheritsPrivilegeEnforcement_fromInnerHandlers` test already proves structurally that there is no `rbacService` field. The integration test proves behavior. A behavioral unit test that fully drives through `toResult()` requires significant Calcite mock scaffolding (SqlExplain, SqlLiteral, SqlExplain.Depth, etc.) — this may have higher setup cost than value delivered.

**Recommended approach for META-03:** Add a behavioral test that mocks the `Catalog` to throw `UserException.validationError("Permission denied")` when any `getTable()` is called. Configure the `SqlHandlerConfig` chain so that the inner handler can call `catalog.getTable()`. Then assert `assertThatThrownBy(() -> handler.toResult(sql, mockSqlExplain)).isInstanceOf(UserException.class)`. This replaces the structural assertion with an actual exception-propagation test. The SqlExplain mock creation is the hardest part — see Pitfall 5.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `verify(never()).hasPrivilege(...)` at construction time (no getTable call) | `mockConstructionWithAnswer(DatasetManager.class, ...)` returning non-null table then `assertThat(result).isEqualTo(pdsTable)` + `verify(never())` | Phase 14 | Meaningful: verify() is no longer trivially true |
| SQL proxy for container visibility tests | REST listing endpoint assertions | Phase 14 | Directly tests `filterByRbacVisibility` code path (not a proxy) |
| Structural test (no `rbacService` field assertion) for ExplainHandler | Behavioral test (exception propagates from inner handler through ExplainHandler.toResult()) | Phase 14 | Proves the runtime behavior, not just the class structure |

---

## Open Questions

1. **What JSON structure does `GET /api/v3/catalog` return?**
   - What we know: It returns a list of catalog items. `TestServer` parses it as `Response` and checks headers. `TestDatasetProfiles` uses `GenericType<Space>` for POSTs.
   - What's unclear: The exact top-level JSON structure for the GET (list of catalog items). Does it wrap items in `{"data": [...]}` or return a flat array?
   - Recommendation: During plan execution, do a GET and print the response body. Use `String.contains("spaceName")` which works regardless of structure. If the planner needs to count items, a `Response` GET + `readEntity(String.class)` + regex is sufficient.

2. **Are system sources (`sys`, `__home`, `__accelerator`) excluded from USER's visible sources list?**
   - What we know: `SourcesResource.getSources()` filters using `accessiblePaths`. System sources are internal; admin users have no restriction (admin bypass). Non-admin users with no grants under system sources should not see them.
   - What's unclear: Whether system sources are special-cased to bypass filtering (they have no VDS grants for non-admin users, so they'd be hidden under the filter logic).
   - Recommendation: The integration test should create an NAS source (or use the existing `cp` classpath source if it exists in the test setup), grant access under it, and verify it appears. Do NOT rely on system sources appearing/disappearing — their behavior may differ.
   - Note: `TestRbacIntegration.init()` starts the cluster but does not register any external sources. The `cp` source is the classpath source that Dremio provides by default. PDS grants are on `cp`. Check if `cp` is present in the test cluster with `getSources()` before asserting.

3. **Can SqlExplain be mocked with Mockito for META-03?**
   - What we know: `SqlExplain` extends `SqlCall` in Apache Calcite. It is not final.
   - What's unclear: Whether Calcite's class hierarchy allows Mockito to proxy the class (some Calcite AST classes use final methods).
   - Recommendation: If full SqlExplain mocking fails, use a simpler assertion: mock the `innerNodeHandler` delegation by stubbing the catalog to throw UserException when `getCatalogEntityByPath()` or `getTable()` is called. The exact approach depends on how deep into NormalHandler the test must go. If SqlExplain cannot be mocked, the integration test in TestRbacIntegration already provides behavioral proof; the unit test can verify the catch-and-rethrow mechanism at the ExplainHandler level by injecting a mock that throws UserException directly at the `innerNodeHandler.getPlan()` level via a subclass.

---

## Detailed Finding: What Each Tech Debt Item Actually Needs

### Tech Debt 1 — PDS-02: trivially-true verify(never()) tests

**Files affected:** `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java`

**Current broken tests:**
- `testPdsAccess_pdsFeatureFlagOff_noEnforcement` (line 2199)
- `testPdsAccess_rbacDisabled_noEnforcement` (line 2214)
- `testPdsAccess_systemUser_bypassesPdsEnforcement` (line 2225)

**Why they are broken:** Each test constructs a `CatalogImpl` via `newCatalogImpl(versionContextResolver)` or `newCatalogImplForUser(user)` and then immediately calls `verify(rbacService, never()).hasPrivilege(...)`. No `catalog.getTable()` is ever called, so `isRbacDeniedForPds` is never triggered. The `never()` assertion is vacuously true — it would pass even if `isRbacDeniedForPds` had no bypass logic at all.

**Also broken:** `testPdsAccess_noGrants_denied` (line 2151), `testPdsAccess_userDenied` (line 2167), `testPdsAccess_userGranted` (line 2183) — these configure mocks but only assert `catalog != null`.

**Fix:** Use `MockedConstruction<DatasetManager>` to return a non-null, non-ViewTable mock from `getTable()`. Then call `catalog.getTable(key)` inside the try block. For bypass tests, assert `result == pdsTable` (table visible through bypass). For deny tests, assert `result == null`. For never-call tests, the `verify(never())` is now meaningful because `isRbacDeniedForPds` was actually invoked.

**The pattern from line 448 already exists in the file** — it uses `mockConstructionWithAnswer`. There is no need to add new imports.

### Tech Debt 2 — CONT-01/CONT-02: container visibility via SQL proxy

**Files affected:** `dac/backend/src/test/java/com/dremio/dac/server/TestRbacIntegration.java`

**Current tests:**
- `testContainerVisibility_spaceHidden_withoutAccess` (line 356): verifies `SELECT * FROM hidden_space.admin_only_view` fails. This tests VDS access control (isRbacDeniedForVds), NOT space visibility in the catalog listing.
- `testContainerVisibility_spaceVisible_withAccess` (line 367): verifies `SELECT * FROM visible_space.shared_view` succeeds. Tests VDS access, not space listing.
- `testContainerVisibility_adminSeesAll` (line 377): tests admin bypass for VDS access.

**What they miss:** None of the three tests call any REST API listing endpoint. They do not verify that `getTopLevelCatalogItems()` in `CatalogServiceHelper` (which calls `filterByRbacVisibility`) actually filters the catalog listing. The `filterByRbacVisibility` method in `SpaceResource`, `HomeResource`, `SpaceFolderResource` is also untested by these. The `SourcesResource.getSources()` RBAC filter is completely untested end-to-end.

**Fix:** Add 2-4 new tests to `TestRbacIntegration` that:
1. Log in as USER via `login(USER, PASSWORD)`.
2. Call `GET /api/v3/catalog` and assert a space name appears/doesn't appear based on grant status.
3. Call `GET /api/v2/sources` and assert a source name appears/doesn't appear.
4. Restore admin session with `login(ADMIN, PASSWORD)` in a try/finally.

**Key constraint:** `RBAC_ENABLED=true` is already set in `initializeCluster()`. The REST endpoints read this from DremioConfig to decide whether to filter. The filter is ACTIVE for non-admin users. The test just needs to verify the filter output via the REST response.

### Tech Debt 3 — META-03: structural test for ExplainHandler

**Files affected:** `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/TestRbacDdlHandlers.java`

**Current test:**
```java
// line 481-504
public void testExplainHandler_inheritsPrivilegeEnforcement_fromInnerHandlers() {
    assertThat(ExplainHandler.class.getDeclaredFields())
        .extracting(java.lang.reflect.Field::getName)
        .doesNotContain("rbacService");
}
```

**What it proves:** ExplainHandler has no `rbacService` field at class definition time (static structural fact).
**What it doesn't prove:** That ExplainHandler actually propagates permission errors from inner handlers at runtime.

**Fix options (in order of increasing setup cost):**

Option A (recommended): Add a test to `TestRbacDdlHandlers` that mocks the `SqlHandlerConfig` -> `QueryContext` -> `Catalog` chain (already present for other tests in the class), configures `catalog.getTable(any())` to return null (simulating deny-by-null for VDS), and then calls `new ExplainHandler(config).toResult(sql, explainSqlNode)`. Assert that a `UserException` is thrown. The hardest part is constructing the `explainSqlNode` — see Pitfall 5.

Option B (fallback): Verify that `ExplainHandler` catches exceptions from `innerNodeHandler.getPlan()` and re-throws them. This can be done by: (1) creating a mock `SqlToPlanHandler`, (2) configuring it to throw `UserException`, (3) subclassing `ExplainHandler` to override `setupInnerHandlerForDefaultCase()` to return the mock handler, (4) calling `toResult()`, (5) asserting `UserException` propagates. This avoids the SqlExplain parsing problem entirely.

Option C (minimal): The integration test `testExplain_denied_withoutSelect` in TestRbacIntegration already provides behavioral proof at full system level. If Option A and B both have excessive setup cost, add a comment to the structural test referencing the integration test as the behavioral proof, and consider success criterion #3 met by the combination. However, the phase explicitly requires "a behavioral test proving META-03 enforcement."

### Tech Debt 4 — REQUIREMENTS.md traceability

**Status: ALREADY SATISFIED.** The REQUIREMENTS.md was updated during gap-closure planning. Current state:
- All 22 v1.2 requirements show `[x]` (Satisfied) in the Requirements section
- The Traceability table at the bottom shows "Satisfied" for all 22 requirements
- Last updated line: `*Last updated: 2026-02-23 — all 22 v1.2 requirements marked Satisfied per milestone audit*`
- Coverage: `v1.2 requirements: 22 total / Mapped to phases: 22 / Unmapped: 0`

**No work needed for success criterion #4.** The plan should include a verification step confirming this, not a fix step.

---

## Sources

### Primary (HIGH confidence)
- Direct code inspection of `TestCatalogImpl.java` lines 2145-2256 — PDS test methods verified
- Direct code inspection of `CatalogImpl.java` lines 2966-3001 — `isRbacDeniedForPds` full implementation
- Direct code inspection of `TestRbacIntegration.java` lines 356-383 — container visibility tests verified
- Direct code inspection of `SourcesResource.java` lines 84-116 — `filterByRbacVisibility` in source listing
- Direct code inspection of `CatalogServiceHelper.java` lines 352-392 — `getTopLevelCatalogItems` space/source filtering
- Direct code inspection of `ExplainHandler.java` lines 68-148 — delegation chain confirmed
- Direct code inspection of `TestRbacDdlHandlers.java` lines 481-504 — structural test confirmed
- Phase 10 VERIFICATION.md `## Anti-Patterns Found` — original tech debt documented
- Phase 11 VERIFICATION.md `## Gaps Summary` and `## Human Verification Required` — REST API gap documented
- Phase 12 VERIFICATION.md `## Truth 3` — META-03 structural-only verification documented
- `REQUIREMENTS.md` lines 95-128 — all 22 requirements confirmed Satisfied

### Secondary (MEDIUM confidence)
- `TestSourcesResource.java` — verified REST GET /sources pattern
- `BaseClientUtils.java` lines 105-115 — verified `expectSuccess` method signature
- `BaseTestServer.java` lines 344-353 — verified `login()` method signature
- `TestCatalogImpl.java` lines 448-466 — verified `MockedConstruction<DatasetManager>` precedent

---

## Metadata

**Confidence breakdown:**
- PDS-02 fix: HIGH — MockedConstruction precedent exists in same file at line 448; imports already present
- CONT-01/02 fix: HIGH — REST patterns exist in TestSourcesResource and TestServer; login pattern documented in BaseTestServer
- META-03 fix: MEDIUM — SqlExplain mock complexity is uncertain; Option B (subclass override) is HIGH confidence as a fallback
- REQUIREMENTS.md status: HIGH — directly read and confirmed all 22 satisfied

**Research date:** 2026-02-23
**Valid until:** 2026-03-25 (stable codebase, no external dependencies)
