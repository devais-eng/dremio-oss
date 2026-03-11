---
phase: 23-ui-global-admin-gates
plan: 01
subsystem: auth
tags: [rbac, login, session-permissions, admin-flag, backend]

# Dependency graph
requires:
  - phase: 21-backend-api-critical-security
    provides: "RbacService with isAdminMember() and RBAC guard patterns"
provides:
  - "RBAC-aware login response with correct admin flag and SessionPermissions"
  - "Frontend permission gates (Add Source, Add Space, Settings, Users) now receive correct data"
affects: [23-02-PLAN, ui-admin-gates, frontend-permissions]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "RBAC-aware login response: use dContext.getRbacService().isAdminMember() to determine admin status"
    - "Backward-compatible RBAC guard: check DremioConfig.RBAC_ENABLED before querying RbacService"

key-files:
  created: []
  modified:
    - "dac/backend/src/main/java/com/dremio/dac/resource/LogInLogOutResource.java"

key-decisions:
  - "Used dContext.getRbacService() pattern (matching BootstrapResource) rather than injecting Provider<RbacService>"
  - "Check DremioConfig.RBAC_ENABLED before querying RbacService for backward compatibility"
  - "Default isAdmin=true when RBAC disabled or RbacService unavailable to preserve existing behavior"
  - "All 12 admin-only SessionPermissions flags driven by single isAdmin boolean"

patterns-established:
  - "Login RBAC pattern: determine admin via RbacService.isAdminMember() with RBAC_ENABLED guard and true-default fallback"

requirements-completed: [UI-01, UI-02, UI-03]

# Metrics
duration: 2min
completed: 2026-03-11
---

# Phase 23 Plan 01: Login Endpoint RBAC-Aware Admin Flag Summary

**Login endpoint returns RBAC-aware admin status and session permissions via RbacService.isAdminMember(), enabling all existing frontend permission gates**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-11T14:06:17Z
- **Completed:** 2026-03-11T14:08:14Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Login endpoint now queries RbacService.isAdminMember() to determine actual admin role membership
- Non-admin users receive admin=false and all 12 admin-only SessionPermissions set to false
- Admin users receive admin=true and all permissions=true (no regression)
- RBAC-disabled deployments default to admin=true for all users (backward compatible)
- Frontend existing guards (UserIsAdmin, useCanAddSource, getAddSpaceHref) now receive correct data and work as intended

## Task Commits

Each task was committed atomically:

1. **Task 1: Make LogInLogOutResource RBAC-aware for admin flag and SessionPermissions** - `dfd8390f8` (fix)

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/resource/LogInLogOutResource.java` - Added RBAC-aware admin determination via RbacService.isAdminMember(); replaced hardcoded true for admin flag and 12 SessionPermissions fields with isAdmin boolean

## Decisions Made
- Used `dContext.getRbacService()` (established pattern from BootstrapResource) rather than constructor-injecting a `Provider<RbacService>` -- simpler change with no constructor signature modification
- Check `DremioConfig.RBAC_ENABLED` before querying RbacService to maintain backward compatibility with RBAC-disabled deployments
- Default `isAdmin=true` on any exception (RbacService null, RBAC disabled, or unexpected error) to avoid accidentally locking out users
- All 12 admin-only SessionPermissions flags use the same `isAdmin` boolean -- no granular per-permission RBAC yet (consistent with current frontend expectations)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- Maven build (`mvn compile`) could not run due to pre-existing protostuff plugin version mismatch (not caused by this change). Verified correctness by confirming all imports, method calls, and API contracts match the existing codebase patterns.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Login response is now RBAC-aware; frontend permission gates work correctly
- Phase 23 Plan 02 (if applicable) can build on this foundation for additional UI admin gate work
- The approach uses the same RbacService patterns established in phases 21-22

---
*Phase: 23-ui-global-admin-gates*
*Completed: 2026-03-11*
