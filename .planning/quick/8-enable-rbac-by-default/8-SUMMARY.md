---
phase: quick-8
plan: 8
subsystem: auth
tags: [rbac, access-control, dremio-config, typesafe-config]

# Dependency graph
requires:
  - phase: v1.3
    provides: RBAC enforcement engine (RbacService, privilege context, PDS SELECT enforcement)
provides:
  - RBAC enforcement active by default on every fresh Dremio deployment
  - PDS SELECT enforcement active by default
affects: [deployment, access-control, onboarding]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "dremio-reference.conf is the lowest-priority default; operators override in dremio.conf"

key-files:
  created: []
  modified:
    - common/legacy/src/main/resources/dremio-reference.conf

key-decisions:
  - "Flip RBAC default from opt-in to opt-out: fresh deployments enforce access control without operator action"
  - "Remove (opt-in) qualifier from pds.enabled comment to reflect new default-on status"

patterns-established:
  - "Default-secure posture: ship with enforcement on, allow operators to disable rather than enable"

requirements-completed:
  - RBAC-DEFAULT-ON

# Metrics
duration: 5min
completed: 2026-03-02
---

# Quick Task 8: Enable RBAC by Default Summary

**`services.rbac.enabled` and `services.rbac.pds.enabled` flipped to `true` in `dremio-reference.conf`, making RBAC enforcement the default for all fresh Dremio OSS deployments.**

## Performance

- **Duration:** ~5 min
- **Started:** 2026-03-02T00:00:00Z
- **Completed:** 2026-03-02T00:05:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments

- RBAC enforcement is now active by default — no operator opt-in step required
- PDS SELECT enforcement is now active by default alongside RBAC
- Comment on `pds.enabled` updated to remove "(opt-in)" qualifier
- Zero test files in `dac/backend/src/test` reference `rbac.enabled` defaulting to false — no test updates needed

## Task Commits

1. **Task 1: Flip RBAC defaults to true in dremio-reference.conf** - `afb403227` (feat)

## Files Created/Modified

- `common/legacy/src/main/resources/dremio-reference.conf` - Changed both `services.rbac.enabled` and `services.rbac.pds.enabled` from `false` to `true`; updated pds comment

## Decisions Made

- Adopted a default-secure posture: enforcement is on by default, operators who need permissive behaviour explicitly disable it in their `dremio.conf`
- Verified no existing tests relied on the `false` default before flipping

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- RBAC is now fully default-on; the system ships production-ready with access control enforced
- Operators wanting the old permissive behaviour must add `services.rbac.enabled = false` to their `dremio.conf`

---
*Phase: quick-8*
*Completed: 2026-03-02*
