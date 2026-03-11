---
phase: quick-9
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropTableHandler.java
  - sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropTableHandler.java
autonomous: true
requirements: [QUICK-9]

must_haves:
  truths:
    - "DROP TABLE ... AT BRANCH on a non-versioned source throws a VALIDATION UserException"
    - "DROP TABLE ... AT BRANCH on a VersionedPlugin source continues to work normally"
    - "DROP TABLE without AT BRANCH on any source is unaffected"
  artifacts:
    - path: "sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropTableHandler.java"
      provides: "Validation check for AT BRANCH on non-versioned sources"
      contains: "does not support AT"
    - path: "sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropTableHandler.java"
      provides: "Unit tests for the AT BRANCH validation"
      contains: "dropTable_atBranch_nonVersionedSource_throwsValidation"
  key_links:
    - from: "DropTableHandler.java"
      to: "catalog.getSource()"
      via: "isWrapperFor(VersionedPlugin.class) check"
      pattern: "isWrapperFor\\(VersionedPlugin\\.class\\)"
---

<objective>
Fix DROP TABLE AT BRANCH silently dropping tables from the default branch on non-versioned REST catalog sources.

Purpose: When a user runs `DROP TABLE my_source.my_table AT BRANCH dev` on a non-versioned source (like a plain REST Iceberg catalog), the AT BRANCH clause is silently ignored and the table is dropped from the default branch. This is dangerous -- the user thinks they're targeting a specific branch but the table is actually dropped from main. The fix adds a validation check matching the pattern already used by INSERT/UPDATE/DELETE/ALTER operations.

Output: Patched DropTableHandler.java with validation + unit test.
</objective>

<execution_context>
@/home/filippo/.claude/get-shit-done/workflows/execute-plan.md
@/home/filippo/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropTableHandler.java
@sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogUtil.java (lines 521-543: getAndValidateSourceForTableManagement pattern)
@sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/SqlDropTable.java
@sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/ReferenceType.java
</context>

<tasks>

<task type="auto">
  <name>Task 1: Add AT BRANCH validation for non-versioned sources in DropTableHandler</name>
  <files>sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/direct/DropTableHandler.java</files>
  <action>
Add a validation check in DropTableHandler.toResult() after the path is resolved (after line 88) and before the drop executes. The check mirrors the existing pattern in CatalogUtil.getAndValidateSourceForTableManagement() (lines 532-543).

Specifically, after `catalog.validatePrivilege(path, Privilege.DROP);` (line 89) and before `final String sourceName = path.getRoot();` (line 90), add:

```java
if (dropTableNode.getRefType() != null) {
  StoragePlugin source = catalog.getSource(path.getRoot());
  if (source != null && !source.isWrapperFor(VersionedPlugin.class)) {
    throw UserException.validationError()
        .message(
            String.format(
                "Source [%s] does not support AT [%s] version specification for DDL operations",
                path.getRoot(), dropTableNode.getRefType()))
        .buildSilently();
  }
}
```

Add these imports to the file:
- `import com.dremio.exec.catalog.VersionedPlugin;`
- `import com.dremio.exec.store.StoragePlugin;`

Note: The message says "DDL operations" (not "DML operations") since DROP TABLE is DDL. This is intentional and distinct from the DML message in CatalogUtil.

The check placement (after path resolution but before version context construction) ensures:
1. The path is already resolved so path.getRoot() gives the correct source name
2. The validation error is thrown before any drop logic executes
3. The check only runs when AT BRANCH/TAG/COMMIT is explicitly specified (refType != null)
  </action>
  <verify>
Build the module to confirm compilation:
```bash
cd /home/filippo/PycharmProjects/dremio-oss && mvn compile -pl sabot/kernel -am -DskipTests -q 2>&1 | tail -5
```
Confirm no compilation errors.
  </verify>
  <done>DropTableHandler.java contains the VersionedPlugin validation check. When AT BRANCH is specified on a non-versioned source, a VALIDATION UserException is thrown with message matching "does not support AT [BRANCH] version specification for DDL operations".</done>
</task>

<task type="auto">
  <name>Task 2: Add unit tests for DropTableHandler AT BRANCH validation</name>
  <files>sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/direct/TestDropTableHandler.java</files>
  <action>
Create a new test class `TestDropTableHandler.java` using Mockito (consistent with TestCatalogImpl.java patterns in this codebase).

The test class needs:
- Mocked `Catalog` (from `com.dremio.exec.catalog.Catalog`)
- Mocked `UserSession` (from `com.dremio.sabot.rpc.user.UserSession`)
- A `DropTableHandler` instance under test

Test cases:

1. `dropTable_atBranch_nonVersionedSource_throwsValidation`:
   - Create a `SqlDropTable` with `refType=ReferenceType.BRANCH`, `refValue=SqlIdentifier("dev")`, tableName=`SqlIdentifier(["mysource", "mytable"])`
   - Mock `catalog.resolveSingle(any(NamespaceKey.class))` to return `new NamespaceKey(List.of("mysource", "mytable"))`
   - Mock `catalog.getSource("mysource")` to return a `StoragePlugin` mock where `isWrapperFor(VersionedPlugin.class)` returns `false`
   - Call `handler.toResult("DROP TABLE mysource.mytable AT BRANCH dev", sqlDropTable)`
   - Assert it throws `UserException` with errorType `VALIDATION` and message containing "does not support AT [BRANCH] version specification for DDL operations"

2. `dropTable_atBranch_versionedSource_proceedsNormally`:
   - Same setup but `isWrapperFor(VersionedPlugin.class)` returns `true`
   - Mock `userSession.getSessionVersionForSource("mysource")` to return `VersionContext.NOT_SPECIFIED`
   - Mock `CatalogUtil.resolveVersionContext(...)` using mockStatic to return a `ResolvedVersionContext` for BRANCH "dev"
   - Mock `catalog.dropTable(any(), any())` to do nothing
   - Call `handler.toResult(...)` and assert it returns a successful result containing "dropped"

3. `dropTable_noRefType_nonVersionedSource_proceedsNormally`:
   - Create `SqlDropTable` with `refType=null`, `refValue=null`
   - Mock `catalog.resolveSingle(...)` for path resolution (since refType is null AND path root is not "$scratch", it will call `CatalogUtil.getResolvePathForTableManagement` -- mock that via mockStatic instead)
   - OR: set tableName root to "$scratch" to take the simpler code path. Actually, the simplest approach: use mockStatic for `CatalogUtil.getResolvePathForTableManagement` to return the resolved path, then mock `CatalogUtil.resolveVersionContext` and `CatalogUtil.validateResolvedVersionIsBranch`
   - Assert no `UserException` is thrown; the validation check is skipped because `refType == null`
   - Note: This test confirms the fix has no regression on the normal (no AT BRANCH) code path

Use `@ExtendWith(MockitoExtension.class)` (JUnit 5) or `@RunWith(MockitoJUnitRunner.class)` (JUnit 4). Check which the codebase uses -- TestCatalogImpl.java uses JUnit 4 (`@Test` from `org.junit.Test`), so use JUnit 4 with `MockitoJUnitRunner` or `@Rule MockitoRule`.

Use assertj `assertThatThrownBy` for exception assertions (consistent with existing test patterns).
  </action>
  <verify>
Run the specific test class:
```bash
cd /home/filippo/PycharmProjects/dremio-oss && mvn test -pl sabot/kernel -Dtest=com.dremio.exec.planner.sql.handlers.direct.TestDropTableHandler -DfailIfNoTests=false 2>&1 | tail -20
```
All 3 tests should pass.
  </verify>
  <done>TestDropTableHandler.java exists with 3 passing tests: (1) AT BRANCH on non-versioned source throws VALIDATION error, (2) AT BRANCH on versioned source proceeds normally, (3) no AT BRANCH on non-versioned source proceeds normally.</done>
</task>

</tasks>

<verification>
1. `mvn compile -pl sabot/kernel -am -DskipTests -q` completes without errors
2. `mvn test -pl sabot/kernel -Dtest=com.dremio.exec.planner.sql.handlers.direct.TestDropTableHandler` -- all 3 tests pass
3. Manual code review: the validation check in DropTableHandler mirrors CatalogUtil.getAndValidateSourceForTableManagement pattern
</verification>

<success_criteria>
- DROP TABLE ... AT BRANCH on a non-versioned source throws UserException.validationError with message "Source [X] does not support AT [BRANCH] version specification for DDL operations"
- DROP TABLE ... AT BRANCH on a VersionedPlugin source continues to work (no regression)
- DROP TABLE without AT BRANCH is completely unaffected (no regression)
- All unit tests pass
</success_criteria>

<output>
After completion, create `.planning/quick/9-fix-drop-table-at-branch-silently-droppi/9-SUMMARY.md`
</output>
