---
phase: 34-web-ui-sso-button
plan: "02"
subsystem: ui
tags: [react, enzyme, oidc, keycloak, localStorage, sinon, mocha]

# Dependency graph
requires:
  - phase: 33-oidc-redirect-web-flow
    provides: OidcResource.handleCallback() that redirects to /login/sso/landing#token=<dremioToken>
  - phase: 34-web-ui-sso-button (plan 01)
    provides: GET /api/v3/server-config returning authType (keycloak|internal)

provides:
  - SSOLandingPage.jsx at OSS component path — extracts token from URL fragment, stores via setUserData, navigates to /
  - LoginFormContainer.jsx with conditional SSO button — renders 'Login with SSO' only when authType==='keycloak'
  - 9 passing Mocha/Chai/enzyme unit tests covering all OIDC browser-side flows

affects:
  - routes.jsx (already imports SSOLandingPage via @inject — now resolves to real component instead of stubModule)
  - Phase 35 (JDBC long sessions documentation)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Static _navigate/_getHash on Component for testable window.location access (avoids jsdom non-configurable location)"
    - "Static _navigate on PureComponent for sinon-stubbable navigation without touching real window.location"
    - "Enzyme find by prop object {variant:'secondary'} to locate ForwardRef-wrapped library buttons in shallow render"

key-files:
  created:
    - dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage.jsx
    - dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage-spec.js
  modified:
    - dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer.jsx
    - dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer-spec.js

key-decisions:
  - "SSOLandingPage exposes static _navigate and _getHash methods for testability — jsdom window.location is non-configurable/non-deletable so sinon.stub(SSOLandingPage, '_navigate') is the only viable sinon pattern without test-env monkey-patching"
  - "LoginFormContainer._navigate is a static stub point for the same reason — SSO button onClick calls LoginFormContainer._navigate('/api/v3/oidc/login')"
  - "SSO button detected via find({variant:'secondary'}) in tests — Button from dremio-ui-lib renders as ForwardRef in enzyme shallow mode, not as 'Button' string"
  - "Server-config fetch is silent on error — network failure defaults to no SSO button (safe fallback for internal auth deployments)"

patterns-established:
  - "Static navigation stub pattern: expose _navigate as class static for sinon.stub() in enzyme shallow tests when window.location is not replaceable"
  - "Button selector pattern: use enzyme find({variant:'secondary'}) not find('Button') when dremio-ui-lib Button appears as ForwardRef in shallow render"

requirements-completed: [UI-01, UI-02, UI-03]

# Metrics
duration: 5min
completed: 2026-03-12
---

# Phase 34 Plan 02: Web UI SSO Button Summary

**SSOLandingPage token extraction + LoginFormContainer conditional SSO button completing the browser-side Keycloak OIDC login flow**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-12T19:19:57Z
- **Completed:** 2026-03-12T19:24:53Z
- **Tasks:** 2 (TDD: both RED then GREEN)
- **Files modified:** 4

## Accomplishments

- Created SSOLandingPage.jsx at the OSS component path — @inject resolver now serves the real component instead of stubModule.js (null render); on mount it parses `#token=<value>` from the URL fragment, decodes it, calls `localStorageUtils.setUserData({token})`, and navigates to `/` for a full page load that triggers `checkAppState`
- Added conditional SSO button to LoginFormContainer — fetches `/api/v3/server-config` in componentDidMount, stores `authType` in state, renders a secondary-variant "Login with SSO" button only when `authType==='keycloak'`; clicking navigates to `/api/v3/oidc/login` (the server-side Keycloak redirect endpoint)
- 9 passing unit tests across both specs (4 for SSOLandingPage, 5 for LoginFormContainer)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create SSOLandingPage spec and component** - `13ef8e358` (feat)
2. **Task 2: Add SSO button tests and implementation to LoginFormContainer** - `34e7f46be` (feat)

**Plan metadata:** (docs commit below)

_Note: TDD tasks — both tasks followed RED (tests fail) then GREEN (implementation passes) flow_

## Files Created/Modified

- `dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage.jsx` - New component: parses URL fragment token, calls setUserData, navigates to / or /login
- `dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage-spec.js` - 4 tests: token extraction, URI decoding, no-token redirect, loading render
- `dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer.jsx` - Modified: added fetch of server-config, authType state, renderSSOButton() method
- `dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer-spec.js` - Extended: 4 new SSO tests (keycloak/internal/null state + click navigation)

## Decisions Made

- **Static _navigate/_getHash stubs:** jsdom in the test env makes `window.location` non-configurable (cannot `delete window.location` or `Object.defineProperty` it). The cleanest pattern is exposing `static _navigate(url)` and `static _getHash()` on the component class so sinon can stub them without touching `window.location` directly.
- **Button selector via prop object:** `Button` from `dremio-ui-lib/components` renders as `ForwardRef` in enzyme shallow mode — `wrapper.find('Button')` returns 0 results. Use `wrapper.find({variant:'secondary'})` to locate the SSO button by its unique prop.
- **fetch for server-config, silent on error:** Network failure silently leaves `authType=null` so the SSO button stays hidden — safe default for internal auth deployments.

## Deviations from Plan

**1. [Rule 1 - Bug] Test approach for window.location stubbing**
- **Found during:** Task 1 (SSOLandingPage TDD)
- **Issue:** Plan suggested `delete window.location` then redefine — jsdom marks `window.location` as non-configurable; both `delete` and `Object.defineProperty` throw `TypeError: Cannot delete/redefine property: location`
- **Fix:** Refactored SSOLandingPage to expose `static _navigate` and `static _getHash` for sinon stubbing; tests stub these statics instead of touching `window.location`
- **Files modified:** SSOLandingPage.jsx, SSOLandingPage-spec.js
- **Verification:** All 4 SSO landing page tests pass
- **Committed in:** `13ef8e358` (Task 1 commit)

**2. [Rule 1 - Bug] Button selector in enzyme shallow render**
- **Found during:** Task 2 (LoginFormContainer TDD)
- **Issue:** Plan specified `wrapper.find('Button').filterWhere(n => n.children().text().includes('Login with SSO'))` — Button from dremio-ui-lib renders as `ForwardRef` in shallow mode, so `find('Button')` returns 0 nodes
- **Fix:** Updated selector to `wrapper.find({variant:'secondary'})` which matches the unique prop on the SSO button
- **Files modified:** LoginFormContainer-spec.js
- **Verification:** All 5 LoginFormContainer tests pass
- **Committed in:** `34e7f46be` (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (both Rule 1 — implementation bugs found during TDD RED/GREEN cycle)
**Impact on plan:** Both fixes required for tests to work. No scope creep. All must-have truths are satisfied.

## Issues Encountered

### UAT Bug: SSO login stored token but app redirected to /login (FIXED)

**Root cause:** `OidcResource.handleCallback()` redirected to `/login/sso/landing#token=X` but did NOT include `userName`. The `SSOLandingPage` stored only `{token}` in localStorage. However, Dremio's `UserIsAuthenticated` wrapper (via `userUtils.isAuthenticated()`) requires `user.userName` to be present — the normal form login (`POST /apiv2/login`) returns `{token, userName, ...}` but the SSO redirect only passed the token.

**Fix:** `OidcResource.handleCallback()` now redirects to `/login/sso/landing#token=X&userName=Y` (URL-encoded). `SSOLandingPage` extracts both `token` and `userName` from the hash and stores `{token, userName}` via `setUserData()`. Tests updated in both `TestOidcResource.java` and `SSOLandingPage-spec.js`.

**Files modified:** `OidcResource.java`, `SSOLandingPage.jsx`, `TestOidcResource.java`, `SSOLandingPage-spec.js`

### Keycloak Realm Configuration Caveat

The `iceberg-realm.json` import wipes Keycloak's built-in OIDC scopes (`openid`, `profile`, `email`) when the `clientScopes` key is present in the JSON — even if the array is empty. The OIDC login flow requests `scope=openid profile email` and Keycloak rejects the request with `error=invalid_scope&error_description=Invalid+scopes%3A+openid+profile+email`.

**Workaround:** These scopes must be recreated via the Keycloak Admin API after realm import:
- `openid` scope needs: `oidc-sub-mapper` + `oidc-audience-mapper` (targeting the `dremio-web` client)
- `profile` scope needs: `oidc-usermodel-attribute-mapper` for `preferred_username`
- `email` scope needs: `oidc-usermodel-attribute-mapper` for `email`

The realm JSON should NOT redefine these built-in scopes. A post-import script or manual admin API call is required after `docker compose up` with a fresh Keycloak.

### Non-blocking: KeycloakRoleSyncer warning

`KeycloakRoleSyncer` logs `Role sync failed for user 'testuser': Unable to find injectable based on com.dremio.exec.rbac.RoleStore`. The `Provider<RoleStore>` lazy resolution works for constructor injection but HK2 may not resolve RoleStore at request time. Login succeeds due to graceful degradation (syncRoles catches all exceptions). Roles are not synced from Keycloak on SSO login — needs further investigation.

## User Setup Required

After `docker compose up`, if using a fresh Keycloak with the `iceberg-realm.json` import, the OIDC scopes must be recreated via the Keycloak Admin API (see Keycloak caveat above).

## Next Phase Readiness

- The complete Keycloak OIDC web login flow is wired and UAT-verified end-to-end: SSO button → `/api/v3/oidc/login` → Keycloak → `/api/v3/oidc/callback` → `/login/sso/landing#token=X&userName=Y` → SSOLandingPage → localStorage `{token, userName}` → app init → Dremio home page
- Phase 34 is complete (both plans done, UAT passed)
- Phase 35 (JDBC long-session documentation) is the remaining milestone task

---
*Phase: 34-web-ui-sso-button*
*Completed: 2026-03-12*
