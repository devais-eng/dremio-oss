---
phase: 23-ui-global-admin-gates
plan: 02
subsystem: ui
tags: [rbac, settings-nav, admin-gate, route-guard, react, defense-in-depth]

# Dependency graph
requires:
  - phase: 23-ui-global-admin-gates
    plan: 01
    provides: "RBAC-aware login response with correct admin flag and SessionPermissions"
provides:
  - "Permission-filtered Settings navigation (non-admin sees only Support and Preferences)"
  - "Route-level UserIsAdmin guards on individual admin-only sub-pages"
  - "Defense-in-depth permission checks on UsersView Add User and Delete controls"
affects: [ui-admin-gates, settings-page, user-management]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Admin nav filtering: isAdmin && {...} with filter(Boolean) to conditionally include nav items"
    - "Route-level granular auth: UserIsAuthenticated at top-level, UserIsAdmin on individual admin routes"
    - "Defense-in-depth UI gates: check permissions at component level even when route is already guarded"

key-files:
  created: []
  modified:
    - "dac/ui/src/RouteMixin.jsx"
    - "dac/ui/src/pages/AdminPage/navSections.js"
    - "dac/ui/src/pages/AdminPage/subpages/UsersView.jsx"

key-decisions:
  - "Replaced top-level UserIsAdmin with UserIsAuthenticated to allow non-admin users to access Settings shell"
  - "Changed default IndexRedirect from nodeActivity to preferences so non-admin users land on accessible page"
  - "Used isAdmin && {...} pattern with existing filter(Boolean) for zero-overhead nav filtering"
  - "Added defense-in-depth canCreateUser and isUserAnAdmin checks to UsersView even though route is UserIsAdmin-guarded"

patterns-established:
  - "Granular route auth: UserIsAuthenticated at parent, UserIsAdmin on individual admin child routes"
  - "Nav section filtering: read admin status from localStorageUtils at render time, filter with conditional spread"

requirements-completed: [UI-01, UI-05]

# Metrics
duration: 2min
completed: 2026-03-11
---

# Phase 23 Plan 02: Settings Navigation Filtering and UsersView Admin Gates Summary

**Permission-filtered Settings navigation showing only Support and Preferences for non-admin users, with granular route guards and defense-in-depth UsersView controls**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-11T14:11:13Z
- **Completed:** 2026-03-11T14:13:26Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Settings route tree now uses UserIsAuthenticated at top level, allowing non-admin users to reach Support and Preferences
- Individual admin-only routes (NodeActivity, Engines, Advanced, Queues, QAssignments) wrapped with UserIsAdmin for per-route protection
- navSections.js filters admin-only items (NodeActivity, Engines, QueueControl, Users) so non-admin users see only Support and Preferences in the sidebar
- UsersView Add User button gated behind canCreateUser permission; Delete buttons gated behind isUserAnAdmin()
- Default Settings redirect changed to Preferences (accessible to all authenticated users)

## Task Commits

Each task was committed atomically:

1. **Task 1: Update RouteMixin to allow non-admin users to access Support and Preferences settings** - `e833c2617` (fix)
2. **Task 2: Filter navSections by user permissions and gate UsersView controls** - `8c185017c` (fix)

## Files Created/Modified
- `dac/ui/src/RouteMixin.jsx` - Replaced UserIsAdmin(AdminModals) with UserIsAuthenticated(AdminModals); wrapped 6 admin-only routes with UserIsAdmin; changed IndexRedirect to preferences
- `dac/ui/src/pages/AdminPage/navSections.js` - Added localStorageUtils import; filter admin-only nav items with isAdmin conditional
- `dac/ui/src/pages/AdminPage/subpages/UsersView.jsx` - Added localStorageUtils import; gate Add User button with canCreateUser; gate Delete button with isUserAnAdmin()

## Decisions Made
- Replaced top-level `UserIsAdmin` with `UserIsAuthenticated` rather than removing the wrapper entirely -- all settings pages still require authentication
- Changed default `IndexRedirect` from `nodeActivity` to `preferences` so non-admin users land on a page they can access (preferences is universally accessible)
- Used `isAdmin && {...}` pattern with existing `filter(Boolean)` in navSections.js -- minimal change leveraging an already-present pattern
- Added defense-in-depth checks to UsersView even though the route is already `UserIsAdmin`-guarded -- guards should be at both route and component level

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Phase 23 complete: login response is RBAC-aware (Plan 01) and UI navigation + controls respect permissions (Plan 02)
- Non-admin users now see only Support and Preferences in Settings, cannot reach admin-only pages, and cannot see Add User or Delete controls
- Admin users see all navigation items and controls (no regression)

## Self-Check: PASSED

All files exist, all commits verified.

---
*Phase: 23-ui-global-admin-gates*
*Completed: 2026-03-11*
