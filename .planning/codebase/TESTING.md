# Testing Patterns

**Analysis Date:** 2026-02-17

## Test Framework

**Runner:**
- JUnit 4: `junit:junit:4.13.2` (dominant, ~1670 test files use `import static org.junit.Assert`)
- JUnit 5: `org.junit.jupiter:junit-jupiter-api:5.10.3` (growing, ~166 files use JUnit 5 Assertions, ~615 use AssertJ)
- JUnit 4 tests run under JUnit 5 via `junit-vintage-engine`
- Config: `pom.xml` at root, `maven-surefire-plugin:3.5.2` for unit tests, `maven-failsafe-plugin:3.5.2` for integration tests

**Assertion Libraries:**
- JUnit 4 `org.junit.Assert` (static import, legacy, still dominant)
- JUnit 5 `org.junit.jupiter.api.Assertions` (preferred for new JUnit 5 tests)
- AssertJ `org.assertj:assertj-core:3.27.3` (preferred modern approach, 615+ files)
- No Hamcrest usage detected

**Mocking:**
- Mockito `org.mockito:mockito-core:5.15.2` primary mock framework
- `mockito-junit-jupiter` for JUnit 5 integration

**Async Assertions:**
- Awaitility used for async/concurrent test assertions (in `BaseTestServer`, `TestReleasableBoundCommandPool`)

**Run Commands:**
```bash
# Run unit tests only
mvn test

# Skip unit tests
mvn test -DskipTests

# Run integration tests
mvn verify

# Skip integration tests
mvn verify -DskipITs

# Run single test class
mvn test -pl <module> -Dtest=TestClassName

# Run with timeout disabled (debug)
# Set VM.isDebugEnabled() = true to disable all timeouts automatically
```

## Test File Organization

**Location:**
- Co-located by module: `src/test/java/` within each Maven module
- Test resources: `src/test/resources/` within each module

**Naming:**
- Unit tests: `Test<Subject>.java` (prefix) or `<Subject>Test.java` (suffix)
  - Examples: `TestDremioClientConnectTearDown.java`, `ClusterIdResourceTest.java`
- Integration tests: `IT<Subject>.java` prefix, run by failsafe plugin
  - Examples: `ITBackupManager.java`, `ITHiveStorage.java`, `ITFlightSqlPreparedStatements.java`

**Structure:**
```
<module>/
  src/
    main/java/com/dremio/...
    test/java/com/dremio/...          # unit/integration tests
    test/resources/                    # test data, JSON fixtures, Parquet files
```

**Base test classes (key infrastructure):**
- `com.dremio.test.DremioTest` at `common/legacy/src/test/java/com/dremio/test/DremioTest.java`
  - Provides: default timeouts, memory tracking, `TestName` rule, log reporting, `objectMapper`
  - All Dremio tests should extend this for consistent infrastructure
- `com.dremio.BaseTestQuery` at `sabot/kernel/src/test/java/com/dremio/BaseTestQuery.java`
  - Full Sabot query engine integration; extends `ExecTest`
  - Used for SQL execution tests
- `com.dremio.dac.server.BaseTestServer` at `dac/backend/src/test/java/com/dremio/dac/server/BaseTestServer.java`
  - JUnit 4 version: full DAC server with REST client helpers (107+ subclasses)
  - Uses Awaitility for async assertions
- `com.dremio.dac.server.BaseTestServerJunit5` at `dac/backend/src/test/java/com/dremio/dac/server/BaseTestServerJunit5.java`
  - JUnit 5 version of BaseTestServer

## Test Structure

**JUnit 4 Suite Organization (dominant pattern):**
```java
@RunWith(MockitoJUnitRunner.Silent.class)   // for Mockito-based tests
public class TestReflectionService extends DremioTest {

  @Mock private SabotContext sabotContext;
  @Mock private JobsService jobsService;

  @Before
  public void setup() throws Exception { ... }

  @After
  public void tearDown() throws Exception { ... }

  @Test
  public void testSomeFeature() { ... }
}
```

**JUnit 5 Suite Organization (preferred for new tests):**
```java
@ExtendWith(MockitoExtension.class)
public class TestDatasetVersionMutator {

  @Mock private CatalogService catalogService;
  @Mock private LegacyKVStoreProvider kvStoreProvider;

  @BeforeEach
  void setup() { ... }

  @AfterEach
  void tearDown() { ... }

  @Test
  void testSomeFeature() { ... }

  @ParameterizedTest
  @MethodSource("provideArguments")
  void testParameterized(String input, boolean expected) { ... }

  static Stream<Arguments> provideArguments() {
    return Stream.of(
        Arguments.of("input1", true),
        Arguments.of("input2", false)
    );
  }
}
```

**Class-level setup pattern (JUnit 4):**
```java
@ClassRule
public static final ZkTestServerRule zkServerResource = new ZkTestServerRule("/css/dremio");

@ClassRule
public static final TestRule CLASS_TIMEOUT = TestTools.getTimeoutRule(200, TimeUnit.SECONDS);

@Before
public void setup() throws Exception { ... }

@After
public void tearDown() { ... }
```

**Timeout patterns:**
- JUnit 4: `@Rule public final TestRule timeoutRule = TestTools.getTimeoutRule(50, TimeUnit.SECONDS);` (from `DremioTest`)
- JUnit 4 class timeout: `@ClassRule public static final TestRule CLASS_TIMEOUT = TestTools.getTimeoutRule(1000, TimeUnit.SECONDS);`
- JUnit 5: configured globally in surefire: `junit.jupiter.execution.timeout.testable.method.default=2m`
- Debug mode: all timeouts disabled automatically when `VM.isDebugEnabled()` returns true
- `TestTools.getTimeoutRule()` at `common/legacy/src/main/java/com/dremio/common/util/TestTools.java`

## Mocking

**Framework:** Mockito 5.15.2

**JUnit 4 patterns:**
```java
// Option 1: Runner (preferred for @Mock fields)
@RunWith(MockitoJUnitRunner.Silent.class)
public class TestFoo {
  @Mock private SomeService someService;
}

// Option 2: Manual mock creation
SomeService service = mock(SomeService.class);
when(service.someMethod()).thenReturn(result);
```

**JUnit 5 patterns:**
```java
// Option 1: Extension (preferred)
@ExtendWith(MockitoExtension.class)
public class TestFoo {
  @Mock private SomeService someService;
  @InjectMocks private SubjectUnderTest subject;
}

// Option 2: Manual
SomeService service = mock(SomeService.class);
when(service.someMethod()).thenReturn(result);
```

**Common Mockito patterns:**
```java
// Stubbing
when(service.method(any())).thenReturn(value);
when(service.method(eq("specific"))).thenReturn(otherValue);

// Verification
verify(service, times(1)).method(any());
verify(service, never()).method(any());
verifyNoMoreInteractions(service);

// Argument capture
ArgumentCaptor<DeleteJobCountsRequest> captor =
    ArgumentCaptor.forClass(DeleteJobCountsRequest.class);
verify(jobsService).deleteJobCounts(captor.capture());
assertThat(captor.getValue().getReflectionIdCount()).isEqualTo(1);

// Spy
Mockito.spy(realObject);

// Static mocking (Mockito 3.4+)
try (MockedStatic<DatasetsUtil> ignored = mockStatic(DatasetsUtil.class)) { ... }

// Resetting (in tearDown)
Mockito.reset(mockObject);
```

**Inline mock cleanup:**
`ClearInlineMocksRule` (JUnit 4) / `ClearInlineMocksExtension` (JUnit 5) call `Mockito.framework().clearInlineMocks()` after each test class to prevent memory leaks. Both are auto-included via `DremioTest.CLEAR_INLINE_MOCKS`.

**What to Mock:**
- External service dependencies
- Database/storage backends
- Network connections
- Heavy infrastructure components (SabotContext, CatalogService, etc.)

**What NOT to Mock:**
- `UserException` (use real instances via builder)
- Value objects and DTOs
- Simple utility classes
- Things you own and can instantiate cheaply

## Fixtures and Factories

**Test Data:**
```java
// Reading test resources (standardized utility)
String json = readTestResourceAsString("path/to/file.json");  // from DremioTest

// Sample data population (integration tests)
// SampleDataPopulator at dac/backend/src/test/java/com/dremio/dac/server/test/SampleDataPopulator.java
// Used in BaseTestServer to populate reference datasets

// Classpath resources (Parquet test data)
// Referenced as cp."tpch/lineitem.parquet" in SQL queries
// Located in sabot/kernel/src/test/resources/

// TestTools.getWorkingPath() for file-system relative paths
private static final String WORKING_PATH = TestTools.getWorkingPath();
private static final String TEST_RES_PATH = WORKING_PATH + "/src/test/resources";
```

**Location:**
- Test JSON fixtures: `src/test/resources/*.json` per module
- Parquet/data files: `sabot/kernel/src/test/resources/` (TPCH data, parquet samples)
- SQL query files: `src/test/resources/*.sql` per module

**Proto test data:**
- Build test proto instances using generated builders: `NodeEndpoint.newBuilder().build()`

## Coverage

**Requirements:** Not enforced (no explicit coverage threshold found in pom.xml)

**JaCoCo:** Configured in `pom.xml` via `jacoco-maven-plugin`
```bash
# Generate coverage report
mvn verify   # JaCoCo reports generated in target/site/jacoco/

# Integration test coverage
# Separate JaCoCo agent configuration for IT tests (prepare-agent-integration goal)
```

## Test Types

**Unit Tests (`Test*.java`):**
- Run by `maven-surefire-plugin`
- Fast, no external infrastructure
- Scope: single class or small collaboration
- Two variants:
  - Pure unit with mocks: extend nothing or extend `DremioTest`
  - Query/execution unit: extend `BaseTestQuery` for in-memory Sabot engine

**Integration Tests (`IT*.java`):**
- Run by `maven-failsafe-plugin` during `verify` phase
- May require external services (ZooKeeper, Hive metastore, etc.)
- Examples: `ITHiveStorage`, `ITBackupManager`, `ITFlightSqlPreparedStatements`
- Same JVM args as unit tests

**Full Server Tests:**
- Extend `BaseTestServer` or `BaseTestServerJunit5`
- Start a complete DACDaemon in-process
- 107+ test classes use this pattern
- Use REST client helpers to call API endpoints
- Use Awaitility for async operations:
  ```java
  Awaitility.await()
      .atMost(30, TimeUnit.SECONDS)
      .until(() -> someConditionMet());
  ```

**Cluster/ZooKeeper Tests:**
- Use `ZkTestServerRule` from `tools/zookeeper-test-runner/`
  ```java
  @ClassRule
  public static final ZkTestServerRule zkServerResource = new ZkTestServerRule("/css/dremio");
  ```

**Parameterized Tests:**
- JUnit 4: `@RunWith(Parameterized.class)` with `@Parameters` (older style)
- JUnit 5: `@ParameterizedTest` with `@MethodSource`, `@ValueSource`, `@CsvSource` (preferred for new tests)
  ```java
  @ParameterizedTest
  @MethodSource("provideTestCases")
  void testCase(String input, Response.Status expectedStatus) { ... }

  static Stream<Arguments> provideTestCases() {
    return Stream.of(
      Arguments.of("case1", Response.Status.OK),
      Arguments.of("case2", Response.Status.BAD_REQUEST)
    );
  }
  ```

## Common Patterns

**Exception Testing (preferred AssertJ approach):**
```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

assertThatThrownBy(() -> service.method(badInput))
    .isInstanceOf(UserException.class)
    .hasMessageContaining("expected message");
```

**Exception Testing (older JUnit 4 try/catch style still present):**
```java
try {
  client.connect();
  fail("Expected exception");
} catch (RpcException e) {
  assertTrue(client.isCleanUpResourcesCalled);
}
```

**Async Testing:**
```java
import org.awaitility.Awaitility;

Awaitility.await()
    .atMost(Duration.ofSeconds(30))
    .pollInterval(Duration.ofMillis(100))
    .until(() -> taskCompleted.get());
```

**Buffer/Memory management in tests (Arrow allocator):**
```java
// Tests using Arrow vectors must manage allocator lifecycle
// 1249+ test files reference BufferAllocator
BufferAllocator allocator = DremioRootAllocator.create(...);
// use allocator
allocator.close();
```

**SQL query testing (extends BaseTestQuery):**
```java
public class TestSomeQuery extends BaseTestQuery {
  @Test
  public void testQuery() throws Exception {
    test("SELECT * FROM cp.\"tpch/nation.parquet\"");
  }

  @Test
  public void testQueryWithError() {
    assertThatThrownBy(() -> test("INVALID SQL"))
        .isInstanceOf(UserException.class);
  }
}
```

**Displaying name in JUnit 5:**
```java
@Test
@DisplayName("When channel closed, do not close the context")
void handleChannelClosed() throws Exception { ... }
```

**Mock inner class (manual mock by subclassing):**
```java
private static class MockDremioClient extends DremioClient {
  private boolean isCleanUpResourcesCalled;

  @Override
  public void cleanUpResources() {
    isCleanUpResourcesCalled = true;
  }
}
```

---

*Testing analysis: 2026-02-17*
