---
phase: 09-udf-rights-verification-and-owner-resolution
plan: 02
subsystem: auth
tags: [udf, rbac, ownership, definer-rights, testing, function-config]

# Dependency graph
requires:
  - phase: 09-udf-rights-verification-and-owner-resolution
    plan: 01
    provides: FunctionConfig.owner field, CatalogEntityOwnershipImpl FUNCTION branch, UDF-01 and UDF-02 production code

provides:
  - 7 unit tests covering UDF-01, UDF-02, and UDF-03 in TestCatalogImpl.java
  - CatalogEntityOwnershipImpl FUNCTION branch verified for non-null owner, null owner, and empty-string owner
  - getFunctions() RBAC gate verified for deny (empty collection), allow, RBAC disabled, and system user bypass
affects:
  - Phase 12 (integration tests) — unit-level coverage is baseline before end-to-end tests

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "UDF test pattern: direct CatalogEntityOwnershipImpl instantiation with mock NamespaceService (same as VDS DEFN tests)"
    - "UDF-03 test pattern: set RBAC_ENABLED=true, mock hasPrivilege return value, call getFunctions(), assert on result + verify() interaction"
    - "var keyword used for getFunctions() return type to avoid importing Collection and org.apache.calcite.schema.Function"

key-files:
  created: []
  modified:
    - sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java

key-decisions:
  - "Added 4 UDF-03 tests instead of plan's 2: rbacDisabled and systemUser edge cases documented the isRbacDeniedForFunction() early-return paths not covered by the two core tests"
  - "Used var for getFunctions() return type to avoid importing Collection/Function — consistent with existing test at line 1114 which also doesn't capture the return value"
  - "FunctionConfig import added as com.dremio.service.namespace.function.proto.FunctionConfig — only needed in test file, CatalogEntityOwnershipImpl uses fully-qualified inline reference"

patterns-established:
  - "UDF ownership test: NameSpaceContainer.Type.FUNCTION + FunctionConfig + mock getEntityByPath — mirrors DATASET pattern exactly"
  - "UDF-03 enforcement test: dremioConfig RBAC_ENABLED + rbacService.hasPrivilege mock → getFunctions() call → assertThat(result).isEmpty() or verify(rbacService)"

requirements-completed: [UDF-01, UDF-02, UDF-03]

# Metrics
duration: 2min
completed: 2026-02-21
---

# Phase 9 Plan 02: UDF Rights Verification and Owner Resolution Summary

**7 unit tests added to TestCatalogImpl.java: 3 tests for CatalogEntityOwnershipImpl FUNCTION ownership resolution (UDF-02/UDF-01 prerequisite) and 4 tests for getFunctions() EXECUTE privilege enforcement including RBAC-disabled and system-user bypass edge cases (UDF-03)**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-21T14:40:47Z
- **Completed:** 2026-02-21T14:42:47Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments

- Added `import com.dremio.service.namespace.function.proto.FunctionConfig` to TestCatalogImpl.java
- 3 UDF-02/UDF-01 ownership tests: non-null owner returns `CatalogUser("alice")`, null owner returns empty, empty-string owner returns empty — exactly mirrors the DEFN-01/02/03 VDS ownership tests
- 4 UDF-03 enforcement tests: deny path returns empty collection, allow path passes RBAC gate, RBAC-disabled path skips check entirely, system user bypass path skips check — covers all four code paths in `isRbacDeniedForFunction()`

## Task Commits

Each task was committed atomically:

1. **Task 1: Add UDF ownership resolution tests (UDF-02 and UDF-01)** - `af1dc3984` (test)
2. **Task 2: Add EXECUTE privilege enforcement tests via getFunctions() (UDF-03)** - `b00793a12` (test)

## Files Created/Modified

- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` - Added `FunctionConfig` import and 7 new test methods in the `// ====== UDF rights verification tests (UDF-01, UDF-02, UDF-03) ======` section (lines 1989-2143)

## Decisions Made

- **Added 4 UDF-03 tests instead of plan's 2:** The two extra tests (`testGetFunctions_rbacDisabled_noRbacCheck` and `testGetFunctions_systemUser_bypassesRbacCheck`) cover the two early-return paths in `isRbacDeniedForFunction()` — RBAC feature flag off and system user. These paths have meaningful enforcement impact and were straightforward to add. The user emphasized thorough edge-case coverage.
- **Used `var` for getFunctions() return type:** Avoids importing both `java.util.Collection` and `org.apache.calcite.schema.Function`. Consistent with the existing test at line 1114 which also doesn't capture the return value. Java 21 `var` is idiomatic here.
- **FunctionConfig import added to test file:** The production code in `CatalogEntityOwnershipImpl` uses the fully-qualified class name inline (no import added there). The test needs the short name for readability.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added 2 additional edge-case tests for UDF-03**
- **Found during:** Task 2 (Add EXECUTE privilege enforcement tests via getFunctions())
- **Issue:** Plan specified 2 tests (deny path, allow path) but `isRbacDeniedForFunction()` has 4 distinct code paths: RBAC disabled, system user, RBAC denied, RBAC allowed. The plan's 2 tests only covered 2 of the 4 paths. The user emphasized thorough edge-case coverage.
- **Fix:** Added `testGetFunctions_rbacDisabled_noRbacCheck` and `testGetFunctions_systemUser_bypassesRbacCheck` covering the two early-return paths
- **Files modified:** sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java
- **Verification:** All 4 getFunctions tests reference correct paths in isRbacDeniedForFunction(); verifyNoInteractions(rbacService) asserts the feature flag and system user paths never reach hasPrivilege()
- **Committed in:** b00793a12 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 2 - missing critical edge case coverage)
**Impact on plan:** The extra two tests directly improve coverage of the enforcement gate. No scope creep — both tests follow the exact same pattern as the two plan-specified tests.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- UDF-01, UDF-02, UDF-03 all have unit test coverage in TestCatalogImpl.java
- All three UDF requirements are complete and verified with tests
- Phase 9 is fully complete — UDF owner field, owner stamping, owner resolution, and EXECUTE privilege enforcement are all implemented and tested
- Phase 10 (PDS SELECT enforcement) can proceed independently

---
*Phase: 09-udf-rights-verification-and-owner-resolution*
*Completed: 2026-02-21*

## Self-Check: PASSED

All files found:
- FOUND: sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java
- FOUND: .planning/phases/09-udf-rights-verification-and-owner-resolution/09-02-SUMMARY.md

All commits verified:
- FOUND: af1dc3984 (test(09-02): add UDF ownership resolution tests (UDF-02 and UDF-01))
- FOUND: b00793a12 (test(09-02): add EXECUTE privilege enforcement tests for getFunctions() (UDF-03))
