---
phase: quick-10
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateEmptyTableHandler.java
  - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/query/CreateTableHandler.java
  - sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestCreateEmptyTableHandler.java
autonomous: true
requirements: [QUICK-10]

must_haves:
  truths:
    - "CREATE TABLE ... AT BRANCH on a non-versioned REST catalog source throws a VALIDATION UserException"
    - "CTAS ... AT BRANCH on a non-versioned REST catalog source throws a VALIDATION UserException"
    - "CREATE TABLE without AT BRANCH on any source proceeds normally (no regression)"
    - "CREATE TABLE AT BRANCH on a versioned source proceeds normally (no regression)"
  artifacts:
    - path: "sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateEmptyTableHandler.java"
      provides: "VersionedPlugin validation for AT BRANCH in CREATE TABLE"
      contains: "does not support AT"
    - path: "sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/query/CreateTableHandler.java"
      provides: "VersionedPlugin validation for AT BRANCH in CTAS"
      contains: "does not support AT"
    - path: "sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestCreateEmptyTableHandler.java"
      provides: "Unit tests for non-versioned source rejection"
      contains: "class TestCreateEmptyTableHandler"
  key_links:
    - from: "CreateEmptyTableHandler.java"
      to: "VersionedPlugin.class"
      via: "isWrapperFor check"
      pattern: "isWrapperFor.*VersionedPlugin"
    - from: "CreateTableHandler.java"
      to: "VersionedPlugin.class"
      via: "isWrapperFor check"
      pattern: "isWrapperFor.*VersionedPlugin"
---

<objective>
Fix CREATE TABLE and CTAS (CREATE TABLE ... AS SELECT) AT BRANCH validation for non-versioned sources.

Purpose: When AT BRANCH is specified on a non-versioned REST catalog source, the branch specification is silently ignored (getResolvedVersionContextIfVersioned returns null, validateResolvedVersionIsBranch(null) is a no-op). This gives the user a false sense that data was created on the specified branch when it was actually created on the default. The fix adds an early validation check (proven pattern from DropTableHandler in quick-9) that rejects AT BRANCH on non-versioned sources with a clear error message.

Output: Two fixed handler files and one test file.
</objective>

<execution_context>
@/home/filippo/.claude/get-shit-done/workflows/execute-plan.md
@/home/filippo/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateEmptyTableHandler.java
@sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/query/CreateTableHandler.java
@sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropTableHandler.java (lines 92-101 — proven fix pattern)
@sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropTableHandler.java (proven test pattern)
@sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/SqlCreateEmptyTable.java (getRefType returns VersionContext.Type, NOT ReferenceType)
@sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/SqlTableVersionSpec.java
</context>

<tasks>

<task type="auto">
  <name>Task 1: Add AT BRANCH validation to CreateEmptyTableHandler and CreateTableHandler</name>
  <files>
    sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/CreateEmptyTableHandler.java
    sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/query/CreateTableHandler.java
  </files>
  <action>
CRITICAL TYPE DIFFERENCE: SqlCreateEmptyTable.getRefType() returns `VersionContext.Type` (not `ReferenceType` like SqlDropTable). When no AT clause is specified, getRefType() returns `VersionContext.Type.NOT_SPECIFIED` (NOT null). The guard condition must check for both null AND NOT_SPECIFIED.

**CreateEmptyTableHandler.java** — In the `toResult` method, insert the validation block AFTER line 108 (`VersionContext sourceVersion = statementSourceVersion.orElse(sessionVersion);`) and BEFORE line 111 (`catalog.validatePrivilege`):

```java
// Validate that AT BRANCH/TAG/etc. is only used on versioned sources
if (sqlCreateEmptyTable.getRefType() != null
    && sqlCreateEmptyTable.getRefType() != VersionContext.Type.NOT_SPECIFIED) {
  StoragePlugin source = catalog.getSource(sourceName);
  if (source != null && !source.isWrapperFor(VersionedPlugin.class)) {
    throw UserException.validationError()
        .message(
            String.format(
                "Source [%s] does not support AT [%s] version specification for DDL operations",
                sourceName, sqlCreateEmptyTable.getRefType()))
        .buildSilently();
  }
}
```

Add these imports to CreateEmptyTableHandler.java:
- `com.dremio.exec.catalog.VersionedPlugin`
- `com.dremio.exec.store.StoragePlugin`

**CreateTableHandler.java** — In the `getPlan` method, insert the SAME validation block AFTER line 93 (`catalog.validatePrivilege(path, SqlGrant.Privilege.CREATE_TABLE);`) and BEFORE line 95 (the CatalogEntityKey builder):

```java
// Validate that AT BRANCH/TAG/etc. is only used on versioned sources
if (sqlCreateTable.getRefType() != null
    && sqlCreateTable.getRefType() != VersionContext.Type.NOT_SPECIFIED) {
  StoragePlugin source = catalog.getSource(sourceName);
  if (source != null && !source.isWrapperFor(VersionedPlugin.class)) {
    throw UserException.validationError()
        .message(
            String.format(
                "Source [%s] does not support AT [%s] version specification for DDL operations",
                sourceName, sqlCreateTable.getRefType()))
        .buildSilently();
  }
}
```

Add these imports to CreateTableHandler.java:
- `com.dremio.catalog.model.VersionContext`
- `com.dremio.exec.catalog.VersionedPlugin`
- `com.dremio.exec.store.StoragePlugin`

Note: `UserException` is already imported in both files.
  </action>
  <verify>
Run: `cd /home/filippo/PycharmProjects/dremio-oss && mvn compile -pl sabot/kernel -am -DskipTests -T1C -q 2>&1 | tail -5`
Compilation must succeed with no errors.
  </verify>
  <done>
Both handlers reject AT BRANCH/TAG/etc. on non-versioned sources with a VALIDATION UserException containing "does not support AT [BRANCH] version specification for DDL operations". Normal code path (no AT clause, or AT clause on versioned source) is unaffected.
  </done>
</task>

<task type="auto">
  <name>Task 2: Create unit tests for CreateEmptyTableHandler AT BRANCH validation</name>
  <files>
    sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestCreateEmptyTableHandler.java
  </files>
  <action>
Create a new test file following the proven pattern from TestDropTableHandler.java. The test constructs SqlCreateEmptyTable nodes and verifies the validation behavior.

IMPORTANT: SqlCreateEmptyTable's constructor is complex (15 args) and getRefType() derives the type from a SqlTableVersionSpec field. To construct a test node with AT BRANCH:

```java
SqlTableVersionSpec branchVersionSpec = new SqlTableVersionSpec(
    SqlParserPos.ZERO,
    TableVersionType.BRANCH,
    SqlLiteral.createCharString("dev", SqlParserPos.ZERO),
    null);
```

And for no AT clause (NOT_SPECIFIED):
```java
SqlTableVersionSpec notSpecified = SqlTableVersionSpec.NOT_SPECIFIED;
```

The CreateEmptyTableHandler constructor takes `(Catalog catalog, SqlHandlerConfig config, UserSession userSession, boolean ifNotExists)`. SqlHandlerConfig requires a QueryContext that provides getOptions(). Mock these.

Create three test methods:

1. `createTable_atBranch_nonVersionedSource_throwsValidation()`:
   - Build SqlCreateEmptyTable with a BRANCH SqlTableVersionSpec, tblName=["mysource","mytable"], fieldList with at least one DremioSqlColumnDeclaration (use a minimal one), all other node lists as empty SqlNodeList, singleWriter=false, ifNotExists=false, policy=null, location=null, partitionDistributionStrategy=UNSPECIFIED, clusterKeys=empty.
   - Mock catalog.resolveSingle to return NamespaceKey(["mysource","mytable"]).
   - Mock catalog.getSource("mysource") to return a StoragePlugin where isWrapperFor(VersionedPlugin.class) returns false.
   - Call handler.toResult(sql, sqlNode) and assert it throws UserException with errorType VALIDATION and message containing "does not support AT [BRANCH] version specification for DDL operations".
   - Use `UserExceptionAssert.assertThatThrownBy(...)` pattern from TestDropTableHandler.

2. `createTable_atBranch_versionedSource_doesNotThrowValidationError()`:
   - Same SqlCreateEmptyTable construction with BRANCH spec.
   - Mock catalog.getSource("mysource") to return a StoragePlugin where isWrapperFor(VersionedPlugin.class) returns true.
   - The call should pass the new validation check without throwing the "does not support AT" error.
   - Since the full handler flow requires many more mocks (validatePrivilege, getEntityByPath, etc.), it is acceptable to just verify that the call gets PAST the new validation (e.g., it might throw a different exception later -- assert that the exception is NOT the "does not support AT" message).

3. `createTable_noAtClause_nonVersionedSource_doesNotThrowValidationError()`:
   - Build SqlCreateEmptyTable with SqlTableVersionSpec.NOT_SPECIFIED.
   - Mock catalog.getSource to return non-versioned source.
   - Verify the call gets past the new validation without the "does not support AT" error.

Import the necessary classes:
- `com.dremio.catalog.model.dataset.TableVersionType`
- `com.dremio.exec.planner.sql.parser.SqlTableVersionSpec`
- `com.dremio.exec.planner.sql.parser.SqlCreateEmptyTable`
- `com.dremio.exec.planner.sql.parser.DremioSqlColumnDeclaration`
- `com.dremio.exec.planner.sql.parser.PartitionDistributionStrategy`
- `com.dremio.exec.catalog.VersionedPlugin`
- `com.dremio.exec.store.StoragePlugin`
- `com.dremio.exec.ops.QueryContext`
- `com.dremio.exec.planner.sql.handlers.SqlHandlerConfig`
- `com.dremio.options.OptionManager`
- `com.dremio.test.UserExceptionAssert`
- Standard Mockito imports

For the DremioSqlColumnDeclaration in the field list, you can create a minimal one or mock it. The simplest approach: the validation error we're testing fires BEFORE fieldList is ever inspected, so the field list just needs to be non-empty to pass the parser check. Use:
```java
SqlNodeList fieldList = new SqlNodeList(SqlParserPos.ZERO);
// Add a minimal column declaration - we won't reach the point where it's parsed
DremioSqlColumnDeclaration col = mock(DremioSqlColumnDeclaration.class);
fieldList.add(col);
```

For SqlHandlerConfig mock chain:
```java
@Mock private SqlHandlerConfig config;
@Mock private QueryContext queryContext;
@Mock private OptionManager optionManager;

// In setUp:
when(config.getContext()).thenReturn(queryContext);
when(queryContext.getOptions()).thenReturn(optionManager);
```
  </action>
  <verify>
Run: `cd /home/filippo/PycharmProjects/dremio-oss && mvn test -pl sabot/kernel -Dtest=TestCreateEmptyTableHandler -DfailIfNoTests=false -T1C 2>&1 | tail -20`
All 3 tests must pass.
  </verify>
  <done>
TestCreateEmptyTableHandler.java exists with 3 passing tests: non-versioned source rejection, versioned source pass-through, and no-AT-clause pass-through. Tests confirm the fix works and does not regress normal paths.
  </done>
</task>

</tasks>

<verification>
1. `mvn compile -pl sabot/kernel -am -DskipTests -T1C -q` succeeds (no compilation errors)
2. `mvn test -pl sabot/kernel -Dtest=TestCreateEmptyTableHandler -DfailIfNoTests=false` all tests pass
3. `mvn test -pl sabot/kernel -Dtest=TestDropTableHandler -DfailIfNoTests=false` existing tests still pass (no regression)
</verification>

<success_criteria>
- CREATE TABLE ... AT BRANCH dev on non-versioned source throws: "Source [X] does not support AT [BRANCH] version specification for DDL operations"
- CTAS ... AT BRANCH dev on non-versioned source throws the same error
- CREATE TABLE without AT clause works normally on all sources
- CREATE TABLE AT BRANCH on versioned sources works normally
- All unit tests pass
</success_criteria>

<output>
After completion, create `.planning/quick/10-fix-create-table-and-ctas-at-branch-vali/10-SUMMARY.md`
</output>
