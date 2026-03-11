---
phase: 26-information-disclosure-fix
plan: 01
subsystem: api
tags: [rbac, security, jobs, information-disclosure, rest-api]

# Dependency graph
requires:
  - phase: 25-backend-logic-fixes
    provides: RbacService.isAdminMember() pattern established and tested
provides:
  - RBAC-scoped GET /api/v2/jobs/filters/users endpoint (non-admin sees only self)
  - Integration tests for jobs filter user enumeration (DISC-01)
affects: [jobs-ui, rbac-testing]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Inject SecurityContext + @Nullable RbacService + @Nullable DremioConfig into JAX-RS resource to scope responses by RBAC admin status"

key-files:
  created: []
  modified:
    - dac/backend/src/main/java/com/dremio/dac/resource/JobsFiltersResource.java
    - dac/backend/src/test/java/com/dremio/dac/server/TestRbacIntegration.java

key-decisions:
  - "Non-admin users receive only their own username from /api/v2/jobs/filters/users regardless of the filter query param value"
  - "Admin or RBAC-disabled path preserves original userService.searchUsers() behavior exactly"
  - "Filter query match for non-admin uses String.contains() on callerName (same logic as admin path)"

patterns-established:
  - "RBAC guard pattern for REST resources: inject SecurityContext + @Nullable RbacService + @Nullable DremioConfig, check rbacService != null && dremioConfig != null && dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED) && !rbacService.isAdminMember(callerName)"

requirements-completed: [DISC-01]

# Metrics
duration: 4min
completed: 2026-03-11
---

# Phase 26 Plan 01: Information Disclosure Fix Summary

**RBAC guard on GET /api/v2/jobs/filters/users — non-admin users now receive only their own username, preventing enumeration of all system accounts (DISC-01)**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-11T15:24:00Z
- **Completed:** 2026-03-11T15:28:12Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Patched `JobsFiltersResource.searchUsers()` with the established RBAC guard pattern (mirrors `JobsResource` and `JobsListingResource` exactly)
- Non-admin callers now receive only a single-item response containing their own username
- Admin or RBAC-disabled deployments receive the full `userService.searchUsers()` result (no behavioral change)
- Added Section 13 to `TestRbacIntegration` with three tests proving the DISC-01 fix: admin sees all, non-admin sees only self, filter param cannot enumerate others

## Task Commits

Each task was committed atomically:

1. **Task 1: Add RBAC scoping to JobsFiltersResource.searchUsers()** - `aee9f3ac6` (fix)
2. **Task 2: Add integration tests for jobs filter user enumeration** - `770ad8993` (test)

**Plan metadata:** _(docs commit follows)_

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/resource/JobsFiltersResource.java` - Injected SecurityContext, RbacService, DremioConfig; added RBAC-scoped guard in searchUsers()
- `dac/backend/src/test/java/com/dremio/dac/server/TestRbacIntegration.java` - Section 13 with three integration tests for DISC-01

## Decisions Made
- Non-admin users receive only their own username from `/api/v2/jobs/filters/users` regardless of the filter query param value — the param is only checked against the caller's own name using `String.contains()`
- Admin or RBAC-disabled path preserves original `userService.searchUsers()` behavior exactly, ensuring no regression
- `@Nullable` annotations on `RbacService` and `DremioConfig` constructor params ensure backward compatibility when RBAC is not wired in the DI container

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

The `mvn compile -pl dac/backend -am -q -DskipTests` command listed in the plan's verify step fails with a pre-existing `InvalidPluginDescriptorException` from the `dremio-protostuff-maven-plugin`. This is unrelated to our changes:
- Error exists on the unmodified codebase (confirmed by stash test)
- Our `JobsFiltersResource.java` compiles without errors when tested with `mvn compile -pl dac/backend -o`
- `TestRbacIntegration.java` compiles without errors when tested with `mvn test-compile -pl dac/backend -o`

## User Setup Required

None - no external service configuration required.

## Self-Check: PASSED

- JobsFiltersResource.java: FOUND
- TestRbacIntegration.java: FOUND
- 26-01-SUMMARY.md: FOUND
- Commit aee9f3ac6 (fix): FOUND
- Commit 770ad8993 (test): FOUND

## Next Phase Readiness

- Phase 26 Plan 01 complete — DISC-01 closed
- No additional plans in Phase 26 per ROADMAP
- Phase 26 (Information Disclosure Fix) is complete

---
*Phase: 26-information-disclosure-fix*
*Completed: 2026-03-11*
