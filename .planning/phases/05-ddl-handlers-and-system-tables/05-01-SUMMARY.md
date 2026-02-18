---
phase: 05-ddl-handlers-and-system-tables
plan: 01
subsystem: auth
tags: [rbac, dependency-injection, sabot-context, query-context, provider, java]

# Dependency graph
requires:
  - phase: 03-service-layer
    provides: RbacService implementation with stores and business logic
  - phase: 04-catalog-enforcement-and-di-wiring
    provides: RbacService registered in DI registry (DACDaemonModule)
provides:
  - RbacService.isAdminMember() public method for handler admin-enforcement
  - Provider<RbacService> wired through SabotContext and ContextService
  - SabotContext.getAccessControlListingManager() returns RbacService (not null)
  - SabotContext.getRbacService() getter method
  - SabotQueryContext interface default getRbacService() returning null
  - QueryContext.getRbacService() delegation to sabotQueryContext
affects: [05-02-ddl-handlers, 05-03-system-tables, all DDL handler classes]

# Tech tracking
tech-stack:
  added: []
  patterns: [Provider lazy resolution pattern, last-param threading to minimize constructor risk]

key-files:
  created: []
  modified:
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
    - sabot/kernel/src/main/java/com/dremio/exec/server/SabotQueryContext.java
    - sabot/kernel/src/main/java/com/dremio/exec/server/SabotContext.java
    - sabot/kernel/src/main/java/com/dremio/exec/server/ContextService.java
    - sabot/kernel/src/main/java/com/dremio/exec/ops/QueryContext.java
    - dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java
    - sabot/kernel/src/test/java/com/dremio/exec/server/SabotNode.java

key-decisions:
  - "isAdminMember() changed from private to public — needed by DDL handler admin checks in plan 05-02"
  - "Provider<RbacService> added as LAST parameter to SabotContext and ContextService — minimizes risk of miscounting the 40+ existing constructor params"
  - "SabotQueryContext default getRbacService() returns null — safe for non-DAC test contexts that don't have RbacService available"
  - "SabotNode test harness passes Providers.of(null) — RBAC disabled by default in unit tests"
  - "getAccessControlListingManager() delegates to rbacServiceProvider — system tables sys.roles, sys.privileges, sys.membership now work without code changes"

patterns-established:
  - "Provider threading pattern: add as last param at each layer (DACDaemonModule -> ContextService -> SabotContext)"
  - "Test null pattern: test harnesses use Providers.of(null) for optional providers not needed in tests"

requirements-completed: [OBSV-01, OBSV-02, OBSV-03]

# Metrics
duration: 8min
completed: 2026-02-18
---

# Phase 5 Plan 01: RbacService Context Wiring Summary

**RbacService wired through Provider<RbacService> into SabotContext/ContextService/QueryContext chain, enabling DDL handlers and system tables (sys.roles, sys.privileges, sys.membership) to access RBAC data**

## Performance

- **Duration:** 8 min
- **Started:** 2026-02-18T00:00:00Z
- **Completed:** 2026-02-18T00:08:00Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments

- Made `RbacService.isAdminMember()` public so DDL handler classes can call it for admin-only enforcement
- Threaded `Provider<RbacService>` as the last constructor parameter through ContextService and SabotContext (following AccelerationManager/MetadataIOPool pattern)
- Overrode `SabotContext.getAccessControlListingManager()` to return the RbacService provider instead of null, making sys.roles, sys.privileges, and sys.membership system tables functional
- Added `QueryContext.getRbacService()` delegation method so DDL handlers can access RbacService via the query context
- Added default `getRbacService()` to the SabotQueryContext interface for safe non-DAC contexts

## Task Commits

Each task was committed atomically:

1. **Task 1: Make isAdminMember() public in RbacService** - `7366d725a` (feat)
2. **Task 2: Wire RbacService through SabotContext/ContextService/QueryContext chain** - `f2d07e35d` (feat)

## Files Created/Modified

- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` - Changed isAdminMember() from private to public
- `sabot/kernel/src/main/java/com/dremio/exec/server/SabotQueryContext.java` - Added default getRbacService() returning null
- `sabot/kernel/src/main/java/com/dremio/exec/server/SabotContext.java` - Added Provider<RbacService> field, constructor param, getRbacService() getter, getAccessControlListingManager() override
- `sabot/kernel/src/main/java/com/dremio/exec/server/ContextService.java` - Added Provider<RbacService> field, constructor param, pass-through to SabotContext
- `sabot/kernel/src/main/java/com/dremio/exec/ops/QueryContext.java` - Added getRbacService() delegation to sabotQueryContext
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` - Added registry.provider(RbacService.class) as last ContextService constructor arg
- `sabot/kernel/src/test/java/com/dremio/exec/server/SabotNode.java` - Added Providers.of(null) as last ContextService constructor arg

## Decisions Made

- `isAdminMember()` changed from private to public since DDL handlers in plan 05-02 need to call it directly on the RbacService instance
- Provider added as the LAST parameter at each call site to minimize risk of miscounting the 40+ existing constructor args
- Test harnesses use `Providers.of(null)` — RBAC is disabled in unit tests by default (DremioConfig null check guards execution)
- `getAccessControlListingManager()` returns `rbacServiceProvider.get()` — RbacService already implements AccessControlListingManager (confirmed during Phase 3)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Plan 05-01 wiring is complete and is the prerequisite for plan 05-02 (DDL handlers)
- 6 DDL handler classes (CreateRole, DropRole, GrantPrivilege, RevokePrivilege, AddMember, RemoveMember) can now be implemented using `QueryContext.getRbacService()`
- System tables sys.roles, sys.privileges, sys.membership are now wired and will return data when queried (no code changes needed in plan 05-03)
- No blockers for 05-02 or 05-03 execution

---
*Phase: 05-ddl-handlers-and-system-tables*
*Completed: 2026-02-18*
