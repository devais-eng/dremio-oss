---
phase: 04-catalog-enforcement-and-di-wiring
plan: 01
subsystem: auth
tags: [rbac, catalog, di-wiring, privilege-enforcement, dremio-config, system-user-bypass]

# Dependency graph
requires:
  - phase: 03-service-layer
    provides: "RbacService with hasPrivilege() boolean resolution, RoleStore, GrantStore, MembershipStore"
  - phase: 01-design-and-proto-schema
    provides: "DremioConfig.RBAC_ENABLED feature flag constant, proto-generated Role/Grant/Membership classes"
provides:
  - "RbacService registered in DACDaemonModule via registry.bind() using Provider<KVStoreProvider>"
  - "CatalogServiceImpl receives Provider<RbacService> and passes RbacService to all CatalogImpl instances"
  - "CatalogImpl.validatePrivilege() with locked 3-step enforcement: flag check, system-user bypass, hasPrivilege deny-as-not-found"
  - "All 4 resolveCatalog() methods carry rbacService and dremioConfig through to new CatalogImpl instances"
  - "resolveRbacObjectType() mapping EXECUTE to FUNCTION, all others to VDS"
affects:
  - 04-02-unit-tests
  - 05-ddl-handlers
  - 06-catalog-integration

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Constructor injection: RbacService as 17th param, DremioConfig as 18th param to CatalogImpl"
    - "Null-safe RbacService: @Nullable field with null guard in validatePrivilege() for non-DAC test contexts"
    - "Feature-flag gating: dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED) as first check before any RBAC logic"
    - "Information hiding: denied access throws same 'Table not found' error as genuinely missing entities"
    - "DI wiring: RbacService created in DACDaemonModule with Provider<KVStoreProvider>, passed to CatalogServiceImpl as Provider<RbacService>"

key-files:
  created: []
  modified:
    - dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogServiceImpl.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java

key-decisions:
  - "RbacService registered in DACDaemonModule (not inside CatalogServiceImpl) because stores use non-legacy KVStoreProvider"
  - "RbacService is @Nullable in CatalogImpl to handle non-DAC test contexts where RbacService is unavailable"
  - "DremioConfig null check added as first guard (dremioConfig == null || !flag) for test contexts"
  - "rbacServiceProvider.get() called eagerly in createCatalog() (not lazy) matching existing pattern"
  - "Test call sites pass () -> null for RbacService provider -- RBAC disabled by default in tests"
  - "resolveRbacObjectType uses privilege-based mapping: EXECUTE -> FUNCTION, all others -> VDS"

patterns-established:
  - "3-step enforcement chain: (1) flag OFF -> return, (2) system user -> return, (3) hasPrivilege -> deny"
  - "Registry.bind + registry.provider pattern for RbacService DI lifecycle"
  - "resolveCatalog copy-through: all new CatalogImpl fields must be passed in all 4 resolveCatalog methods"

requirements-completed:
  - BOOT-02
  - ENFC-01
  - ENFC-02
  - ENFC-03
  - ENFC-06
  - ENFC-07

# Metrics
duration: 7min
completed: 2026-02-18
---

# Phase 4 Plan 01: Catalog Enforcement and DI Wiring Summary

**RbacService DI registration via DACDaemonModule, constructor injection through CatalogServiceImpl to CatalogImpl, and 3-step validatePrivilege() enforcement with feature-flag gating, system-user bypass, and deny-as-not-found information hiding**

## Performance

- **Duration:** 7 min
- **Started:** 2026-02-18T10:33:59Z
- **Completed:** 2026-02-18T10:41:10Z
- **Tasks:** 2
- **Files modified:** 11 (3 main source + 8 test files)

## Accomplishments
- Registered RbacService in DACDaemonModule using Provider<KVStoreProvider> (non-legacy), creating stores with lazy-initialized KV access
- Threaded RbacService through CatalogServiceImpl to CatalogImpl as constructor parameter, including all 4 resolveCatalog() copy-through methods
- Replaced no-op validatePrivilege() with the locked 3-step enforcement chain: feature flag check, system user bypass, RbacService.hasPrivilege() with "Table not found" denial
- Updated all 8 test call sites (SabotNode, TestCatalogServiceImpl, TestDatasetCatalogServiceImpl, TestMasterLessCatalogServiceImpl, TestMockedCatalogServiceImpl, TestCatalogImpl, ITDataplanePluginTestSetup, TestSystemStoragePluginInitializer) to pass null RbacService provider

## Task Commits

Each task was committed atomically:

1. **Task 1: Register RbacService in DACDaemonModule and thread through CatalogServiceImpl to CatalogImpl constructor** - `4b5ae090f` (feat)
2. **Task 2: Implement validatePrivilege() with 3-step enforcement chain** - `755421249` (feat)

**Plan metadata:** (this commit, docs)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` - RbacService registration via registry.bind() with KVStoreProvider-backed stores
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogServiceImpl.java` - Provider<RbacService> field, constructor parameter, passed to createCatalog()
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` - rbacService + dremioConfig fields, validatePrivilege() 3-step enforcement, resolveRbacObjectType() helper
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogImpl.java` - Updated newCatalogImpl() to pass null rbacService and dremioConfig
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestCatalogServiceImpl.java` - Added () -> null for rbacServiceProvider
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestDatasetCatalogServiceImpl.java` - Added () -> null for rbacServiceProvider
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestMasterLessCatalogServiceImpl.java` - Added () -> null for rbacServiceProvider
- `sabot/kernel/src/test/java/com/dremio/exec/catalog/TestMockedCatalogServiceImpl.java` - Added () -> null for rbacServiceProvider
- `sabot/kernel/src/test/java/com/dremio/exec/server/SabotNode.java` - Added () -> null for rbacServiceProvider
- `plugins/dataplane-tests/src/test/java/com/dremio/exec/catalog/dataplane/test/ITDataplanePluginTestSetup.java` - Added () -> null for rbacServiceProvider
- `dac/backend/src/test/java/com/dremio/dac/daemon/TestSystemStoragePluginInitializer.java` - Added () -> null for rbacServiceProvider

## Decisions Made
- RbacService is created and registered in DACDaemonModule (not inside CatalogServiceImpl) because the RBAC stores require Provider<KVStoreProvider> (non-legacy), while CatalogServiceImpl only has Provider<LegacyKVStoreProvider>
- RbacService is @Nullable in CatalogImpl because non-DAC contexts (SabotNode tests) may not have RBAC available
- Added defensive dremioConfig == null check as the very first guard in validatePrivilege() to handle test contexts where DremioConfig is not provided
- Test files pass () -> null (lambda returning null) rather than a bare null to match the Provider<RbacService> type
- resolveRbacObjectType() maps by privilege type (EXECUTE -> FUNCTION, all others -> VDS) rather than by inspecting the namespace entity type

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated all test call sites for CatalogServiceImpl and CatalogImpl constructors**
- **Found during:** Task 1
- **Issue:** 8 test files and SabotNode.java construct CatalogServiceImpl or CatalogImpl directly and would fail with the new constructor parameter
- **Fix:** Added null RbacService provider as the last argument to all call sites
- **Files modified:** SabotNode.java, TestCatalogServiceImpl.java, TestDatasetCatalogServiceImpl.java, TestMasterLessCatalogServiceImpl.java, TestMockedCatalogServiceImpl.java, TestCatalogImpl.java, ITDataplanePluginTestSetup.java, TestSystemStoragePluginInitializer.java
- **Verification:** All call sites match the new constructor signature
- **Committed in:** 4b5ae090f (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Auto-fix was necessary to maintain constructor compatibility across all test call sites. No scope creep.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- validatePrivilege() is now a live enforcement point -- DDL handlers that already call it (DELETE, ALTER, MERGE, INSERT, CREATE OR REPLACE VIEW) will enforce RBAC when the feature flag is enabled
- Unit tests for validatePrivilege() behavior should be added in Plan 04-02
- SELECT enforcement for simple queries and UDF EXECUTE enforcement require additional hook points (identified in research as open questions for Phase 5)
- Definer-rights model (ENFC-07) preserved by existing ViewExpander behavior -- no changes needed

---
*Phase: 04-catalog-enforcement-and-di-wiring*
*Completed: 2026-02-18*

## Self-Check: PASSED

- DACDaemonModule.java: FOUND
- CatalogServiceImpl.java: FOUND (Provider<RbacService> verified)
- CatalogImpl.java: FOUND (rbacService 10 occurrences, dremioConfig 5 occurrences)
- 04-01-SUMMARY.md: FOUND
- Commit 4b5ae090f: FOUND (Task 1)
- Commit 755421249: FOUND (Task 2)
