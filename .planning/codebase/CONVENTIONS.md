# Coding Conventions

**Analysis Date:** 2026-02-17

## Naming Patterns

**Files:**
- Production classes: `PascalCase.java` matching the public class name (enforced by `OuterTypeFilename` checkstyle rule)
- Test classes: `Test<Subject>.java` prefix (e.g., `TestDremioClient.java`) or `<Subject>Test.java` suffix (e.g., `ClusterIdResourceTest.java`)
- Integration test classes: `IT<Subject>.java` prefix (e.g., `ITBackupManager.java`, `ITHiveStorage.java`)
- Abstract classes: `Abstract<Subject>.java` prefix (e.g., `AbstractSqlAccessor.java`, `AbstractClient.java`)

**Classes:**
- Service interfaces: `<Name>Service` extending `com.dremio.service.Service`
  - Examples: `SchedulerService`, `UserSessionService`, `SearchService`, `ProvisioningService`
- Service implementations: `<Name>ServiceImpl` (e.g., `CredentialsServiceImpl`, `TestUserSessionServiceImpl`)
- REST resource classes: `<Name>Resource` with `@RestResource` annotation (e.g., `SpaceFolderResource`, `ProvisioningResource`)
- Plugin configs: `<Name>Conf` extending `FileSystemConf<Conf, Plugin>` (e.g., `GCSConf`, `HomeFileConf`)
- Exception classes: `<Name>Exception` extending from `UserException` for user-facing errors

**Methods:**
- Production methods: `camelCase` starting with lowercase letter `^[a-z][a-zA-Z0-9]*$` (enforced by checkstyle `MethodName`)
- Test methods: `test<Description>` or underscore-allowed `^(test[a-zA-Z0-9_]*|[a-z][a-zA-Z0-9]*)$` (e.g., `testGenericRpcExResourceCleanUp`, `testWeighBasedSchedulingNotSupportedYet`)

**Constants:**
- `UPPER_CASE_WITH_UNDERSCORES` (enforced by checkstyle `ConstantName`)
- Exception: logger fields may be named `logger` (the constant name regex allows `^logger|[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$`)
- Example: `private static final String WORKING_PATH`, `private static final TestRule CLASS_TIMEOUT`

**Variables:**
- Instance fields: `camelCase`
- Local variables: `camelCase`
- Hidden fields (shadowing) are warned against by checkstyle

**Packages:**
- All packages under `com.dremio.*` base package
- Module packages follow the module structure (e.g., `com.dremio.service.reflection`, `com.dremio.dac.resource`)

## Code Style

**Formatting:**
- Tool: `.editorconfig` + Checkstyle enforcement via `build-tools/configs/src/main/resources/dremio-checkstyle/checkstyle-config.xml`
- Indentation: 2 spaces (`indent_size = 2`)
- Java continuation indent: 4 spaces (`continuation_indent_size = 4`)
- Line endings: LF only (`end_of_line = lf`, `NewlineAtEndOfFile` with `lf`)
- No tabs anywhere (`FileTabCharacter` check enforced)
- Trailing whitespace not allowed on any line
- Files must end with a newline (`insert_final_newline = true`)
- Max file length: 4000 lines (`FileLength` check)
- No Windows-style line endings (`\r`)

**Braces:**
- Always required (`NeedBraces` checkstyle check)
- No empty blocks without text comment (`EmptyBlock` with `option=text`)
- Empty catch blocks warned (`EmptyCatchBlock` severity=warning)

**Linting (Checkstyle):**
- Config: `build-tools/configs/src/main/resources/dremio-checkstyle/checkstyle-config.xml`
- Star imports not allowed except static member imports (`AvoidStarImport`)
- Prohibited legacy packages: `org.apache.commons.lang`, `org.apache.commons.math`, `org.apache.commons.collections` (use `.lang3`, `.math3`, `.collections4` equivalents)
- Prohibited packages: `org.jboss.netty`, `twill`, `hive` shaded variants
- `EqualsHashCode` enforced (both or neither)
- `StringLiteralEquality` enforced (use `.equals()` not `==`)
- `MissingOverride` enforced
- `MutableException` check active

**Error Prone (Custom Checks):**
- `LogStatementWithStringFormat` (WARNING): do not use `String.format()` inside log calls; use SLF4J `{}` placeholders instead
- `NoGuavaCacheUsage` (ERROR): use Caffeine cache instead of `CacheBuilder.build()`
- `DremioGRPCStreamObserverOnError`: enforces gRPC stream observer error handling
- `DremioRestrictedTestcontainersUsage`: restricts direct Testcontainers usage
- `ImplementBothUnwrapAndIsWrapperFor`: enforces implementing both methods together
- Custom checks live in `build-tools/errorprone/src/main/java/com/dremio/errorprone/`

## Import Organization

**Order (conventional Java style):**
1. Static imports
2. `java.*` / `javax.*`
3. Third-party libraries (alphabetical)
4. `com.dremio.*` internal imports

**Path Aliases:** None (standard Java package imports)

**Prohibited imports (enforced by checkstyle):**
- `org.apache.commons.lang.*` → use `org.apache.commons.lang3.*`
- `org.apache.commons.math.*` → use `org.apache.commons.math3.*`
- `org.apache.commons.collections.*` → use `org.apache.commons.collections4.*`
- `com.google.common.cache.CacheBuilder.build()` → use Caffeine cache (Error Prone ERROR)
- Star imports in non-generated code

**Suppressing checkstyle:**
```java
// CHECKSTYLE:OFF <CheckName>
... code ...
// CHECKSTYLE:ON <CheckName>
```

## Error Handling

**Primary Pattern: `UserException` builder**

`com.dremio.common.exceptions.UserException` extends `RuntimeException` and is the primary user-facing exception type. Use its static builder methods to create typed errors:

```java
// For data errors
throw UserException.dataReadError(e)
    .message("Could not read file: %s", path)
    .addContext("dataset", datasetName)
    .build(logger);

// For validation errors
throw UserException.validationError()
    .message("Invalid query: %s", reason)
    .build(logger);

// For system errors
throw UserException.systemError(e)
    .message("Unexpected internal error")
    .build(logger);
```

**Preconditions (Guava):**
Used extensively for argument/state validation in production code (103+ files):
```java
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

checkArgument(value > 0, "Value must be positive: %s", value);
checkNotNull(param, "param must not be null");
checkState(isStarted, "Service must be started first");
```

**Exception propagation (Guava Throwables):**
```java
Throwables.throwIfUnchecked(cause);
Throwables.propagateIfPossible(e, IOException.class);
Throwables.propagateIfInstanceOf(e.getCause(), SQLException.class);
```

**Checked exceptions:**
- Service methods broadly throw `Exception` or specific checked exceptions
- 5000+ method signatures declare `throws Exception` or `throws IOException`

## Logging

**Framework:** SLF4J with `org.slf4j.Logger`

**Declaration pattern (private static final):**
```java
// Pattern 1: inline via full class reference
private static final org.slf4j.Logger logger =
    org.slf4j.LoggerFactory.getLogger(MyClass.class);

// Pattern 2: import-based (also common)
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
private static final Logger logger = LoggerFactory.getLogger(MyClass.class);
```

**Log message pattern:**
- Use SLF4J `{}` parameter substitution, NOT `String.format()` (enforced by Error Prone check):
```java
// CORRECT
logger.info("Processing dataset: {}", datasetName);
logger.warn("Query {} exceeded timeout of {} seconds", queryId, timeout);

// WRONG - Error Prone will flag this as a warning
logger.info(String.format("Processing dataset: %s", datasetName));
```

**Test logging:** `com.dremio.TestReporter` logger used in `DremioTest` base class to report test outcomes with memory statistics.

## Comments

**When to Comment:**
- Javadoc required on public types at class level (checkstyle `JavadocType` enforced for `scope=public`)
- `JavadocStyle` enforced (first sentence style)
- `@param` tags optional (`allowMissingParamTags=true`)
- Complex logic benefits from inline comments
- `TODO(DX-NNNNN):` pattern used for tracked work items (e.g., `// TODO(DX-98540): Refactor to call into CatalogFolder`)

**VisibleForTesting:**
Methods/fields with reduced visibility for test access must be annotated:
```java
@com.google.common.annotations.VisibleForTesting
package-private void internalMethod() { ... }
```
This is also whitelisted in checkstyle `VisibilityModifier` check.

## Function Design

**Size:** No explicit line limit enforced, but file max is 4000 lines

**Parameters:** No explicit limit, but Guava Preconditions used for validation

**Return Values:**
- `java.util.Optional` used extensively (542+ files) for nullable returns
- Guava `com.google.common.base.Optional` is not used (0 occurrences)
- Guava `ImmutableList`, `ImmutableMap`, `ImmutableSet` used heavily for return types (1255+ files)

## Module Design

**Service Lifecycle:**
All services implement `com.dremio.service.Service`:
```java
// com/dremio/service/Service.java
public interface Service extends AutoCloseable {
  void start() throws Exception;
}
```
Services have `start()` and `close()` lifecycle methods. Use `AutoCloseables` helper for multi-resource cleanup.

**Dependency Injection:**
- Guice (`com.google.inject`) for DI in server modules (`DACDaemonModule`, `DremioBinder`)
- `javax.inject.Provider<T>` for lazy/deferred dependencies (343+ production files)
- `@Inject` annotation for constructor/field injection

**Immutable Value Objects:**
- Immutables library (`@Immutable`) used for value objects (e.g., `YarnPropsApi`, `Containers`, `DynamicConfig`)
- Generated classes use `Immutable<Name>` naming pattern
- Jackson integration for JSON serialization (612+ files use Jackson annotations)

**REST API:**
- JAX-RS (`javax.ws.rs`) with Jersey implementation (776+ `javax.ws.rs` imports)
- Resources annotated with `@RestResource`, `@Secured`, `@RolesAllowed`
- Located under `dac/backend/src/main/java/com/dremio/dac/resource/`

**Protobuf/Protostuff:**
- Protobuf (`com.google.protobuf`) and Protostuff (`io.protostuff`) for wire protocol and persistence (383+ files)
- Generated proto classes in `protocol/` module

**Plugin Architecture:**
- Storage plugins extend typed base classes: `FileSystemPlugin<Conf>`, `MayBeDistFileSystemPlugin<Conf>`
- Config classes extend `FileSystemConf<Conf, Plugin>`
- SPI via `@AutoService` annotation for discovery

---

*Convention analysis: 2026-02-17*
