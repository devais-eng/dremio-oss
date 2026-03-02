---
phase: 14-test-coverage-and-documentation
plan: 01
subsystem: testing
tags: [mockito, mockConstruction, REST-API, integration-tests, PDS, ExplainHandler, RBAC]

# Dependency graph
requires:
  - phase: 10-pds-select-enforcement
    provides: PDS enforcement code (isRbacDeniedForPds) and original PDS tests
  - phase: 11-container-visibility
    provides: Container visibility filtering for REST API listing endpoints
  - phase: 12-metadata-safety-and-integration-testing
    provides: ExplainHandler structural test and integration test infrastructure
provides:
  - 6 rewritten PDS unit tests with MockedConstruction<DatasetManager> and catalog.getTable() behavioral assertions
  - 3 REST API container visibility integration tests (GET /api/v3/catalog, GET /api/v2/sources)
  - 1 behavioral ExplainHandler test proving UserException propagation via MockedConstruction<SqlHandlerConfig>
  - REQUIREMENTS.md verification (all 22 v1.2 requirements Satisfied)
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "MockedConstruction<DatasetManager> for exercising CatalogImpl.getTable() through isRbacDeniedForPds"
    - "MockedConstruction<SqlHandlerConfig> to bypass SqlHandlerConfig constructor NPE in ExplainHandler tests"
    - "Before/after REST API assertion structure for proving RBAC visibility changes"
    - "Subclass-override pattern for injecting mock SqlToPlanHandler into ExplainHandler"

key-files:
  created: []
  modified:
    - sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java
    - sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/TestRbacDdlHandlers.java
    - dac/backend/src/test/java/com/dremio/dac/server/TestRbacIntegration.java

key-decisions:
  - "PDS tests use getTable(NamespaceKey) not getTableNoResolve -- exercises full code path including resolveToDefault null fallback"
  - "DremioTable mock (not ViewTable) avoids isRbacDeniedForPds short-circuit at ViewTable instanceof check"
  - "ExplainHandler test uses MockedConstruction<SqlHandlerConfig> (Option D from research) not deep mock chain -- avoids brittle coupling to SqlHandlerConfig constructor internals"
  - "Sources listing test uses before/after assertion structure to handle cp source being a system-bypass source"
  - "SqlString import corrected to org.apache.calcite.sql.util.SqlString (not org.apache.calcite.sql.SqlString)"

patterns-established:
  - "MockedConstruction for bypassing complex constructors in handler/catalog tests"
  - "Before/after REST API listing assertions for proving grant-driven visibility changes"

requirements-completed: [PDS-02, CONT-01, CONT-02, META-03]

# Metrics
duration: 10min
completed: 2026-02-23
---

# Phase 14 Plan 01: Test Coverage Summary

**Rewritten 6 PDS tests with MockedConstruction behavioral assertions, added 3 REST API container visibility integration tests, and added ExplainHandler UserException propagation behavioral test**

## Performance

- **Duration:** 10 min
- **Started:** 2026-02-23T14:41:10Z
- **Completed:** 2026-02-23T14:51:53Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Rewrote all 6 PDS unit tests to use MockedConstruction<DatasetManager> with catalog.getTable(key) calls, replacing vacuously-true verify(never()) assertions with meaningful result assertions (null for denied, pdsTable for allowed/bypassed)
- Added 3 REST API container visibility tests using GET /api/v3/catalog and GET /api/v2/sources endpoints with login-as-user and try/finally admin restoration
- Added behavioral ExplainHandler test proving UserException propagation through toResult() using MockedConstruction<SqlHandlerConfig> to avoid constructor NPE
- Verified REQUIREMENTS.md: all 22 v1.2 requirements confirmed Satisfied

## Task Commits

Each task was committed atomically:

1. **Task 1: Rewrite PDS unit tests** - `60686fdd1` (test)
2. **Task 2: REST API container visibility + ExplainHandler behavioral test** - `908a08a43` (test)

## Files Created/Modified
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` - 6 PDS tests rewritten with MockedConstruction<DatasetManager> and getTable() assertions
- `sabot/kernel/src/test/java/com/dremio/exec/planner/sql/handlers/TestRbacDdlHandlers.java` - Added ExplainHandler behavioral test with MockedConstruction<SqlHandlerConfig> and subclass override
- `dac/backend/src/test/java/com/dremio/dac/server/TestRbacIntegration.java` - Added Section 12 with 3 REST API container visibility tests

## Decisions Made
- Used `getTable(NamespaceKey)` for PDS tests instead of `getTableNoResolve` -- exercises full CatalogImpl code path including resolveToDefault null fallback and isRbacDeniedForSysPrivileges short-circuit
- Used `DremioTable` mock (not `ViewTable`) to avoid `isRbacDeniedForPds` short-circuiting at the `table instanceof ViewTable` check at line 2968 of CatalogImpl
- Chose MockedConstruction<SqlHandlerConfig> (Option D from Phase 14 research) for ExplainHandler test to avoid brittle deep-mock chain of SqlHandlerConfig constructor dependencies (PlanCacheCreator, PlannerNormalizerComponent, etc.)
- Sources listing test uses before/after assertion structure with conditional logging because the "cp" source may bypass RBAC filtering as a system source
- Fixed SqlString import to correct package `org.apache.calcite.sql.util.SqlString`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] SqlString import package correction**
- **Found during:** Task 2 (ExplainHandler behavioral test)
- **Issue:** Plan referenced `org.apache.calcite.sql.SqlString` but the actual Calcite class is `org.apache.calcite.sql.util.SqlString`
- **Fix:** Corrected import to `org.apache.calcite.sql.util.SqlString`
- **Files modified:** TestRbacDdlHandlers.java
- **Verification:** Import resolves to correct Calcite class
- **Committed in:** 908a08a43 (Task 2 commit)

**2. [Rule 1 - Bug] Added SqlNode.toSqlString() stub to prevent NPE**
- **Found during:** Task 2 (ExplainHandler behavioral test)
- **Issue:** Mock SqlNode returns null for `toSqlString(CalciteSqlDialect.DEFAULT)`, causing NPE at `.getSql()` before reaching the getPlan() exception throw
- **Fix:** Added `SqlString mockSqlString = mock(SqlString.class)` with `when(innerSelectNode.toSqlString(any(SqlDialect.class))).thenReturn(mockSqlString)` stub
- **Files modified:** TestRbacDdlHandlers.java
- **Verification:** toSqlString() returns mock SqlString, getPlan() is reached and throws UserException as expected
- **Committed in:** 908a08a43 (Task 2 commit)

**3. [Rule 1 - Bug] Fixed AttemptObservers type mismatch**
- **Found during:** Task 2 (ExplainHandler behavioral test)
- **Issue:** Plan used `AbstractAttemptObserver.NOOP` for outerConfig.getObserver() but the return type is `AttemptObservers`, not `AttemptObserver`
- **Fix:** Changed to `AttemptObservers.of()` which returns the correct type
- **Files modified:** TestRbacDdlHandlers.java
- **Verification:** Type-safe return for the mock stub
- **Committed in:** 908a08a43 (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (2 bug fixes, 1 blocking)
**Impact on plan:** All auto-fixes necessary for test correctness. No scope creep.

## Issues Encountered
None - all planned test patterns worked correctly with the deviations noted above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 14 is the final phase in the v1.2 milestone
- All 22 v1.2 RBAC requirements are Satisfied per REQUIREMENTS.md traceability
- No production code changes in this phase -- test-only improvements
- v1.2 milestone complete: all 14 phases executed

## Self-Check: PASSED

- All 3 modified test files exist on disk
- Both task commits (60686fdd1, 908a08a43) found in git log
- 6 PDS tests use MockedConstruction<DatasetManager> (9 total occurrences including pre-existing tests)
- 3 REST API container visibility test methods confirmed
- 1 ExplainHandler behavioral test method confirmed

---
*Phase: 14-test-coverage-and-documentation*
*Completed: 2026-02-23*
