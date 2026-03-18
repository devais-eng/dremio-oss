---
phase: 34-web-ui-sso-button
verified: 2026-03-12T00:00:00Z
status: human_needed
score: 3/3 must-haves verified
human_verification:
  - test: "Login page shows SSO button when auth.type=keycloak is set"
    expected: "A 'Login with SSO' button appears below the username/password form on the Dremio login page"
    why_human: "Conditional rendering driven by a live /api/v3/server-config fetch in componentDidMount — cannot verify the rendered DOM in a running browser programmatically"
  - test: "Login page hides SSO button when auth.type=internal"
    expected: "No SSO button is visible on the login page; only the standard username/password form appears"
    why_human: "Same as above — relies on the fetch response altering state in a live browser session"
  - test: "Clicking 'Login with SSO' completes the full OIDC flow and lands user on Dremio home page fully logged in"
    expected: "Browser redirects through Keycloak, returns to /login/sso/landing#token=X, SSOLandingPage stores token in localStorage, then navigates to / and the app initializes with a valid session"
    why_human: "End-to-end Keycloak redirect flow requires a running Keycloak instance and a browser; localStorage population and app init saga cannot be verified statically"
---

# Phase 34: Web UI SSO Button Verification Report

**Phase Goal:** The Dremio login page shows a "Login with SSO" button when Keycloak auth is configured, and the SSO landing page correctly completes the login saga in the browser
**Verified:** 2026-03-12
**Status:** human_needed (all automated checks passed — 3 items require browser verification)
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths (from ROADMAP.md Success Criteria)

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | When `auth.type=keycloak`, login page displays "Login with SSO" button | VERIFIED (automated + human needed) | `renderSSOButton()` returns a `<Button variant="secondary">Login with SSO</Button>` when `this.state.authType === 'keycloak'`; 3 unit tests cover keycloak/internal/null state |
| 2  | When `auth.type=internal`, the SSO button is absent | VERIFIED (automated + human needed) | `renderSSOButton()` returns `null` for any authType other than `"keycloak"`; test confirms absence for `"internal"` and `null` |
| 3  | Clicking "Login with SSO" initiates Keycloak redirect; after auth, browser lands on home page fully logged in (localStorage same as form login) | VERIFIED (automated + human needed) | Button onClick calls `LoginFormContainer._navigate('/api/v3/oidc/login')`; `OidcResource.handleCallback()` redirects to `/login/sso/landing#token=X`; `SSOLandingPage.componentDidMount()` parses fragment, calls `localStorageUtils.setUserData({token})`, navigates to `/` |

**Score:** 3/3 truths verified at code level; all 3 also require human browser verification for end-to-end behavior.

---

## Required Artifacts

### Plan 34-01 Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `dac/backend/src/main/java/com/dremio/dac/resource/ServerConfigResource.java` | Unauthenticated GET /api/v3/server-config endpoint | VERIFIED | 86 lines; `@APIResource @Path("/server-config") @Produces(APPLICATION_JSON)`; no `@Secured`; injects `DACConfig`; reads `DremioConfig.WEB_AUTH_TYPE`; returns `ServerConfig(authType)` |
| `dac/backend/src/test/java/com/dremio/dac/resource/TestServerConfigResource.java` | 3 unit tests for keycloak/internal/single-field response | VERIFIED | 95 lines; JUnit 5 + Mockito; 3 `@Test` methods (`testGetServerConfigKeycloak`, `testGetServerConfigInternal`, `testServerConfigResponseHasOnlyAuthType`); mocks `DACConfig` + `DremioConfig` chain |

### Plan 34-02 Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage.jsx` | SSO landing page — token extraction from URL fragment | VERIFIED | 59 lines; named + default export; `componentDidMount` parses `window.location.hash` via `/[#&]token=([^&]+)/`; calls `setUserData({token})`; navigates to `/` or fallback to `/login`; static `_navigate`/`_getHash` stubs for testability |
| `dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage-spec.js` | 4 Mocha/Chai tests for SSOLandingPage | VERIFIED | 66 lines; 4 `it()` blocks covering: token extraction + navigate `/`, URI decode, no-token redirect to `/login`, loading state render |
| `dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer.jsx` | Modified login form with conditional SSO button | VERIFIED | 133 lines; `componentDidMount` fetches `/api/v3/server-config`; stores `authType` in state; `renderSSOButton()` conditionally renders `<Button variant="secondary">`; `{this.renderSSOButton()}` placed in `render()` between form and toggle link |
| `dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer-spec.js` | Extended tests with 4 new SSO button tests | VERIFIED | 79 lines; 5 total tests (1 existing + 4 new in `describe("SSO button")`); uses `find({variant:'secondary'})` for enzyme ForwardRef button; stubs `LoginFormContainer._navigate` |

---

## Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `ServerConfigResource` | `DACConfig.getConfig().getString(WEB_AUTH_TYPE)` | `@Inject` constructor + direct call | WIRED | Line 48: `@Inject` constructor; line 63: `dacConfig.getConfig().getString(DremioConfig.WEB_AUTH_TYPE)` |
| `LoginFormContainer.jsx` | `/api/v3/server-config` | `fetch` in `componentDidMount` | WIRED | Lines 48–57: `fetch('/api/v3/server-config').then(r => r.ok ? r.json() : null).then(cfg => { if (cfg && cfg.authType) this.setState({authType:cfg.authType}) })` with silent catch |
| `LoginFormContainer.jsx` | `/api/v3/oidc/login` | `window.location.assign` on button click | WIRED | Line 79: `onClick={() => { LoginFormContainer._navigate('/api/v3/oidc/login'); }}` |
| `SSOLandingPage.jsx` | `localStorageUtils.setUserData` | direct import + call | WIRED | Line 17: `import localStorageUtils from '#oss/utils/storageUtils/localStorageUtils'`; line 36: `localStorageUtils.setUserData({ token })` |
| `SSOLandingPage.jsx` | `/` (home page) | `window.location.assign` after `setUserData` | WIRED | Line 37: `SSOLandingPage._navigate('/')` (delegates to `window.location.assign`) |
| `routes.jsx` | `SSOLandingPage` at `SSO_LANDING_PATH` | `@inject` resolver → `#oss/` fallback | WIRED | `routes.jsx` line 149: `<Route path={SSO_LANDING_PATH} component={SSOLandingPage} />`; `@inject` resolver checks `#oss/pages/AuthenticationPage/components/SSOLandingPage` before stubModule; OSS file exists so stub is not used |
| `OidcResource.handleCallback()` | `/login/sso/landing#token=X` | `Response.status(302).location(URI)` | WIRED | `OidcResource.java` line 209–210: `URI landingUri = URI.create("/login/sso/landing#token=" + dremioToken.token)` |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| UI-01 | 34-01-PLAN.md, 34-02-PLAN.md | Login page shows "Login with SSO" button when auth.type=keycloak | SATISFIED | `ServerConfigResource` returns `{"authType":"keycloak"}`; `LoginFormContainer` renders SSO button when `authType==="keycloak"` |
| UI-02 | 34-01-PLAN.md, 34-02-PLAN.md | Login page hides SSO button when auth.type=internal | SATISFIED | `renderSSOButton()` guards on `authType !== 'keycloak'` — returns null for `"internal"`, `null`, or any other value |
| UI-03 | 34-02-PLAN.md | SSO landing page receives Dremio session token and completes login flow (same localStorage contract as internal auth) | SATISFIED | `SSOLandingPage.componentDidMount` extracts token from hash, calls `localStorageUtils.setUserData({token})` — identical localStorage write path as form login; `getAuthToken()` reads `user.token` from same key |

All 3 requirements assigned to Phase 34 are accounted for. No orphaned requirements.

---

## Anti-Patterns Found

No anti-patterns detected in any phase 34 files.

| File | Pattern Checked | Result |
|------|----------------|--------|
| `ServerConfigResource.java` | TODO/FIXME, empty returns, placeholder content | Clean |
| `TestServerConfigResource.java` | TODO/FIXME, commented-out test bodies | Clean |
| `SSOLandingPage.jsx` | `return null`, empty handlers, placeholder text | Clean — `return null` in `renderSSOButton` is correct conditional logic, not a stub |
| `LoginFormContainer.jsx` | `console.log`-only handlers, `onSubmit` only preventing default | Clean — click handler calls `_navigate('/api/v3/oidc/login')` |

Note: `renderSSOButton()` returns `null` when authType is not `"keycloak"` — this is intentional conditional rendering, not a stub.

---

## Human Verification Required

### 1. SSO Button Appears for Keycloak Deployments

**Test:** Start Dremio with `services.coordinator.web.auth.type = "keycloak"` configured. Navigate to the login page in a browser.
**Expected:** A "Login with SSO" button appears below the username/password form, visually distinct (secondary button style).
**Why human:** The button only appears after `componentDidMount` fires a live `fetch('/api/v3/server-config')` and receives `{"authType":"keycloak"}`. This async fetch-then-setState chain cannot be verified without a running server and browser.

### 2. SSO Button Is Absent for Internal Auth Deployments

**Test:** Start Dremio with default config (no Keycloak configured; `auth.type` defaults to `"internal"`). Navigate to the login page.
**Expected:** No SSO button is visible. Only the standard username/password form is shown.
**Why human:** Same async fetch path as above. Also verifies the safe-fail behavior (fetch returns `{"authType":"internal"}` → button correctly hidden).

### 3. Full End-to-End SSO Login Flow

**Test:** With a working Keycloak instance configured, click "Login with SSO" on the Dremio login page. Complete authentication with Keycloak. Observe the browser.
**Expected:**
  1. Browser redirects to Keycloak's login page (via `/api/v3/oidc/login` → 302)
  2. After Keycloak authentication, browser lands on `/login/sso/landing#token=<value>`
  3. "Completing login..." text is briefly visible
  4. Browser navigates to `/` (full page load)
  5. Dremio home page loads fully authenticated — `localStorage["user"]` contains `{token: "<value>"}`, and `getAuthToken()` returns `_dremio<value>`, matching the same localStorage contract as form-based login
**Why human:** Full OIDC authorization code flow requires a live Keycloak instance, a real browser session, and verification of localStorage state — none of which are automatable with static analysis or unit tests.

---

## Gaps Summary

No gaps. All automated checks passed:

- Both backend artifacts (`ServerConfigResource.java`, `TestServerConfigResource.java`) exist, are substantive, and are wired via `@APIResource` classpath scan.
- All four frontend artifacts (`SSOLandingPage.jsx`, `SSOLandingPage-spec.js`, `LoginFormContainer.jsx`, `LoginFormContainer-spec.js`) exist and are substantive.
- All 7 key links verified — data flow is complete from button click through Keycloak redirect to localStorage population.
- All 3 requirement IDs (UI-01, UI-02, UI-03) are satisfied with implementation evidence.
- 4 commits verified in git history: `554f389c2` (RED), `fa233a2b3` (GREEN plan 01), `13ef8e358` (plan 02 SSOLandingPage), `34e7f46be` (plan 02 LoginFormContainer).
- No anti-patterns, stubs, or placeholders found.

The phase is code-complete. Status is `human_needed` because the end-to-end SSO flow (rendering, Keycloak redirect, localStorage completion) requires a running browser and Keycloak instance to validate empirically.

---

_Verified: 2026-03-12_
_Verifier: Claude (gsd-verifier)_
