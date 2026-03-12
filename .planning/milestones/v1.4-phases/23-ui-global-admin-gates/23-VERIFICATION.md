---
phase: 23-ui-global-admin-gates
verified: 2026-03-11T15:30:00Z
status: passed
score: 11/11 must-haves verified
---

# Phase 23: UI Global Admin Gates Verification Report

**Phase Goal:** Non-admin users see a UI that reflects only the actions they are authorized to take at the global navigation level
**Verified:** 2026-03-11T15:30:00Z
**Status:** passed
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

Truths are sourced from the `must_haves` in 23-01-PLAN.md and 23-02-PLAN.md, cross-referenced against the five Success Criteria defined in ROADMAP.md.

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Non-admin user login response has admin=false | VERIFIED | LogInLogOutResource.java:170 passes `isAdmin` (not hardcoded `true`) to UserLoginSession constructor; `isAdmin` is set to `rbacService.isAdminMember(userName)` at line 132 |
| 2 | Non-admin user login response has canCreateUser=false, canCreateRole=false, canCreateSource=false | VERIFIED | LogInLogOutResource.java:149-151 passes `isAdmin` for canCreateUser, canCreateRole, canCreateSource in SessionPermissions |
| 3 | Non-admin user login response has canManageNodeActivity=false, canManageEngines=false, canManageQueues=false | VERIFIED | LogInLogOutResource.java:153-155 passes `isAdmin` for canManageNodeActivity, canManageEngines, canManageQueues |
| 4 | Admin user login response has admin=true and all permissions=true (no regression) | VERIFIED | When RBAC is disabled (line 129 check), `isAdmin` defaults to `true` (line 127); when RBAC enabled, admins return true from `isAdminMember()` |
| 5 | Frontend sidebar hides Add Source button for non-admin users (via existing useCanAddSource check) | VERIFIED | LeftTreeUtils.ts:37 reads `canCreateSource` from `getUserPermissions()`; backend now sends `false` for non-admins |
| 6 | Frontend sidebar hides Add Space '+' button for non-admin users (via existing admin check in getAddSpaceHref) | VERIFIED | SpacesSection.jsx:70 reads `admin` from `getUserData()`; backend now sends `false` for non-admins |
| 7 | Frontend UserIsAdmin route guard redirects non-admin users away from admin-only settings pages | VERIFIED | RouteMixin.jsx wraps NodeActivity, Users, Advanced, Provisioning, Queues, QAssignments with `UserIsAdmin()` at per-route level (lines 47-69); authWrappers.jsx UserIsAdmin checks `userData.get("admin")` via `userUtils.isAdmin` |
| 8 | Non-admin user navigating to Settings sees only Support and Preferences tabs | VERIFIED | navSections.js:20 reads `isUserAnAdmin()`; lines 25-57 use `isAdmin && {...}` pattern with `.filter(Boolean)` -- only Support and Preferences are unconditional |
| 9 | Non-admin user cannot reach /settings/users, /settings/nodeActivity, /settings/engines, or /settings/advanced via direct URL | VERIFIED | RouteMixin.jsx:47-69 wraps those routes with `UserIsAdmin()` which redirects to "/" on admin=false |
| 10 | Admin user sees all 6 Settings sub-pages (no regression) | VERIFIED | navSections.js returns all 6 items when `isAdmin` is truthy; all routes are accessible under AdminPage for admin users |
| 11 | Non-admin user on Users page does not see Add User button or Delete buttons | VERIFIED | UsersView.jsx:157-159 checks `canCreateUser` from `getUserPermissions()` -- returns null if falsy; line 143 checks `isUserAnAdmin()` before rendering DeleteButton |

**Score:** 11/11 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `dac/backend/src/main/java/com/dremio/dac/resource/LogInLogOutResource.java` | RBAC-aware login response with correct admin flag and SessionPermissions | VERIFIED | Contains `isAdminMember` call (line 132), `isAdmin` variable drives all 12 SessionPermissions flags and the admin field in UserLoginSession; no hardcoded `true` remains for any permission |
| `dac/ui/src/pages/AdminPage/navSections.js` | Permission-filtered settings navigation sections | VERIFIED | Contains `isUserAnAdmin()` check (line 20), uses `isAdmin && {...}` pattern with `filter(Boolean)` for 4 admin-only sections; imports `localStorageUtils` |
| `dac/ui/src/pages/AdminPage/subpages/UsersView.jsx` | Permission-gated Add User button and Delete controls | VERIFIED | Contains `canCreateUser` check in `renderAddUsersButton` (line 157-159) and `isUserAnAdmin()` check for DeleteButton (line 143); imports `localStorageUtils` |
| `dac/ui/src/RouteMixin.jsx` | Granular route-level auth with UserIsAuthenticated at top, UserIsAdmin per admin route | VERIFIED | Top-level uses `UserIsAuthenticated(AdminModals)` (line 37); six admin-only sub-routes wrapped with `UserIsAdmin()` (lines 47, 50, 54, 58, 67, 70); Support, Preferences, Acceleration, Activation remain unwrapped; IndexRedirect goes to `preferences` (line 40) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| LogInLogOutResource.java | RbacService.isAdminMember() | dContext.getRbacService() | WIRED | Line 130: `dContext.getRbacService()`, line 132: `rbacService.isAdminMember(userConfig.getUserName())`. SabotContext.java:320 confirms `getRbacService()` method exists. |
| LogInLogOutResource.java | SessionPermissions constructor | isAdmin boolean drives permission flags | WIRED | Lines 142-159: `new SessionPermissions(...)` with `isAdmin` for 12 of 16 params; first 4 remain option-driven |
| LogInLogOutResource.java | DremioConfig.RBAC_ENABLED | dremioConfig.getBoolean() | WIRED | Line 129: `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` -- guards RBAC query for backward compat. DremioConfig.java:152 confirms constant exists. |
| UserLoginSession.admin field | Frontend localStorage user.admin | Login saga stores payload in localStorage | WIRED | localStorageUtils.js:100 reads `isAdmin.admin`; authWrappers.jsx uses `userUtils.isAdmin` which reads `userData.get("admin")` (userUtils.js:27) |
| navSections.js | localStorage user.permissions | localStorageUtils.getUserPermissions() | WIRED | Line 20: `localStorageUtils?.isUserAnAdmin()` directly reads stored admin flag |
| navSections.js filter | AdminPageView.jsx sections prop | getSectionsConfig() promise resolution | WIRED | AdminPage.jsx calls the default export and passes sections to AdminPageView |
| UsersView.jsx permission check | localStorage user.admin | localStorageUtils.isUserAnAdmin() | WIRED | Line 143: `localStorageUtils?.isUserAnAdmin()` for Delete; line 158: `localStorageUtils?.getUserPermissions()?.canCreateUser` for Add User |
| RouteMixin UserIsAuthenticated | authWrappers.jsx | import statement | WIRED | Line 19: `import { UserIsAdmin, UserIsAuthenticated } from "#oss/components/Auth/authWrappers"` |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| UI-01 | 23-01, 23-02 | Non-admin users cannot access the Settings > Users page or see Add User / Delete User controls | SATISFIED | Backend sends admin=false and canCreateUser=false for non-admins (LogInLogOutResource.java:149,170). RouteMixin.jsx:50 wraps Users with UserIsAdmin. UsersView.jsx:157-159 gates Add User with canCreateUser, line 143 gates Delete with isUserAnAdmin(). |
| UI-02 | 23-01 | Non-admin users cannot see the Add Source button unless they have canCreateSource permission | SATISFIED | Backend sends canCreateSource=false for non-admins (LogInLogOutResource.java:151). Existing useCanAddSource() in LeftTreeUtils.ts:37 reads this and hides button. |
| UI-03 | 23-01 | Non-admin users cannot see the Add Space button in the sidebar | SATISFIED | Backend sends admin=false for non-admins (LogInLogOutResource.java:170). Existing getAddSpaceHref() in SpacesSection.jsx:70 checks admin flag and returns empty string. |
| UI-05 | 23-02 | Non-admin users cannot access admin-only Settings sub-pages (Node Activity, Engines, Queue Control, Users) | SATISFIED | navSections.js filters those items from nav (lines 25-57). RouteMixin.jsx wraps those routes with UserIsAdmin (lines 47,54,58,67,70) redirecting non-admins to "/". |

No orphaned requirements found. REQUIREMENTS.md maps UI-01, UI-02, UI-03, UI-05 to Phase 23 and all four are covered.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| UsersView.jsx | 16 | `// TODO to Vasyl, need to use Radium` | Info | Pre-existing TODO from original codebase; not introduced by this phase; no functional impact |

No blocker or warning anti-patterns found in any modified files.

### Human Verification Required

### 1. Non-admin Login Response

**Test:** Log in as a non-admin user (one who is NOT a member of the ADMIN role) and inspect the network response from `POST /login`.
**Expected:** Response JSON has `admin: false` and `permissions.canCreateUser: false`, `permissions.canCreateSource: false`, `permissions.canManageNodeActivity: false`, etc.
**Why human:** Requires a running Dremio instance with RBAC enabled and a non-admin user account.

### 2. Add Source Button Hidden

**Test:** As a non-admin user, navigate to the Home page and look at the Sources panel in the left sidebar.
**Expected:** No "Add Source" (+) button is visible.
**Why human:** Visual UI behavior dependent on runtime localStorage state.

### 3. Add Space Button Hidden

**Test:** As a non-admin user, look at the Spaces panel in the left sidebar.
**Expected:** No "Add Space" (+) button is visible.
**Why human:** Visual UI behavior dependent on runtime localStorage state.

### 4. Settings Nav Filtering

**Test:** As a non-admin user, navigate to `/settings`.
**Expected:** Only "Support" and "Preferences" tabs appear in the Settings sidebar. No "Node Activity", "Engines", "Queue Control", or "Users" tabs.
**Why human:** Visual UI behavior; requires running frontend with correct localStorage state.

### 5. Admin-Only Route Redirect

**Test:** As a non-admin user, directly navigate to `/settings/users`, `/settings/nodeActivity`, `/settings/engines`, and `/settings/advanced`.
**Expected:** Each URL redirects back to "/" (home page).
**Why human:** Requires running React Router with auth wrappers active.

### 6. UsersView Controls Hidden

**Test:** As a non-admin user, attempt to reach the Users page (e.g., by manually navigating before the redirect kicks in, or by temporarily removing the route guard).
**Expected:** No "Add User" button visible. No "Delete" buttons visible on user rows.
**Why human:** Component-level behavior dependent on runtime permissions state.

### 7. Admin User No Regression

**Test:** Log in as an admin user and verify: all 6 Settings tabs visible, Add Source button visible, Add Space button visible, Add User and Delete buttons on Users page visible.
**Expected:** Full functionality preserved for admin users.
**Why human:** End-to-end regression test requiring running instance.

### Gaps Summary

No gaps found. All 11 observable truths are verified against the actual codebase. The four requirements (UI-01, UI-02, UI-03, UI-05) are fully satisfied.

The implementation follows a clean architecture:
- **Plan 01** fixed the root cause in the backend: `LogInLogOutResource.login()` now queries `RbacService.isAdminMember()` to determine actual admin status, replacing hardcoded `true` values for both the `admin` flag and all 12 admin-only `SessionPermissions` fields.
- **Plan 02** added UI-layer improvements: `navSections.js` filters admin-only Settings nav items, `RouteMixin.jsx` uses granular `UserIsAdmin` per-route instead of top-level blocking (allowing non-admins to access Support and Preferences), and `UsersView.jsx` has defense-in-depth permission checks on Add User and Delete controls.

All three commits exist and are verified: `dfd8390f8`, `e833c2617`, `8c185017c`.

---

_Verified: 2026-03-11T15:30:00Z_
_Verifier: Claude (gsd-verifier)_
