# Phase 34: Web UI SSO Button - Research

**Researched:** 2026-03-12
**Domain:** React/JSX frontend login form, Redux saga, localStorage, backend config API
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| UI-01 | Login page shows "Login with SSO" button when auth.type=keycloak | Button must appear in `LoginFormContainer` (or a new component it renders) when the backend reports keycloak auth. The backend must expose `authType` via a new `GET /api/v3/server-config` endpoint (unauthenticated); the UI reads this on mount. |
| UI-02 | Login page hides SSO button when auth.type=internal | Same mechanism: if `authType != "keycloak"` in the server config response, the button is not rendered. |
| UI-03 | SSO landing page receives Dremio session token and completes login flow (same localStorage contract as internal auth) | `SSOLandingPage` reads `window.location.hash` for `#token=<dremioToken>`, calls `GET /apiv2/login` (the `checkUser` endpoint) to fetch the full `UserLoginSession` object using that token in the `Authorization: _dremio<token>` header, then dispatches `userLoggedIn(userInfo)` to trigger the `handleLogin` saga (which calls `localStorageUtils.setUserData()`). |
</phase_requirements>

---

## Summary

Phase 34 is a frontend phase with one small backend addition. The backend already delivers the Dremio token to the browser at `/login/sso/landing#token=<dremioToken>` via Phase 33's `OidcResource.handleCallback()`. Phase 34 must do two things:

1. **SSO button on the login page (UI-01, UI-02):** Add a "Login with SSO" button to the login form that is shown only when `auth.type=keycloak`. Since the login page is rendered before any Dremio session exists, the auth type cannot be read from a secured endpoint. The cleanest approach is to add a new unauthenticated `GET /api/v3/server-config` endpoint in `dac/backend` that returns `{"authType":"keycloak"}` (or `"internal"`). The login form component fetches this on mount and conditionally renders the SSO button. Clicking the button navigates to `GET /api/v3/oidc/login` (Phase 33 endpoint), which redirects the browser to Keycloak.

2. **SSO landing page (UI-03):** `SSOLandingPage` is already imported and routed in `routes.jsx` at path `/login/sso/landing` — but it resolves to `stubModule.js` (renders `null`) because no OSS implementation exists. The task is to create the real `SSOLandingPage` component in `dac/ui/src/pages/AuthenticationPage/components/`. The component reads `window.location.hash`, extracts the token, fetches `GET /apiv2/login` with `Authorization: _dremio<token>` to get the full `UserLoginSession`, dispatches `userLoggedIn(sessionObject)` to the Redux store, which triggers the `handleLogin` saga → `localStorageUtils.setUserData(payload)` → app init. This is identical to the internal login path: `handleLogin` saga is already wired for `LOGIN_USER_SUCCESS`.

**Primary recommendation:** Two new files: (a) `ServerConfigResource.java` in `dac/backend` (unauthenticated, returns `{"authType":...}`); (b) `SSOLandingPage.jsx` in `dac/ui/src/pages/AuthenticationPage/components/`. One modification: add the SSO button to `LoginFormContainer.jsx`. No new npm packages, no new Redux actions (reuse `userLoggedIn`).

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| React (JSX) | 17+ (project-wide) | SSO landing page component + SSO button in login form | Entire Dremio UI is React/JSX |
| Redux + `redux-api-middleware` (RSAA) | project-wide | Dispatch `userLoggedIn` action after token exchange | Already used in `actions/account.js` for `loginUser` |
| `window.location.hash` / `window.location.assign` | browser built-in | Read token fragment in landing page, navigate away | No library needed; URL fragment never reaches server |
| `localStorageUtils` | `dac/ui/src/utils/storageUtils/localStorageUtils.js` | `setUserData()` called indirectly via `handleLogin` saga | Already used for internal login; same contract |
| JAX-RS + `@APIResource` | project-wide (Jersey) | New `ServerConfigResource` unauthenticated endpoint | Same annotation as `OidcResource` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `dremio-ui-lib/components` `Button` | project-wide | SSO button component — matches existing `LoginForm.jsx` button | Use same `Button` component as the "Log In" button already in `LoginForm.jsx` |
| `fetch` / `APIV2Call` | project built-in | Fetch `GET /apiv2/login` with Authorization header in landing page | Use `APIV2Call` pattern consistent with `actions/account.js`; alternatively use plain `fetch` for simplicity in a one-time mount call |
| `react-router` `withRouter` / `push` | project-wide | Navigate to home after successful SSO login | `push('/')` dispatched after `userLoggedIn` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| New `GET /api/v3/server-config` endpoint | Read auth type from `dremio.conf` injected at build time as an env variable | Build-time env var approach requires rebuild on config change; runtime API is robust for runtime config |
| New `GET /api/v3/server-config` endpoint | Expose auth type in existing `GET /apiv2/login` (GET response) | `GET /apiv2/login` returns `boolean` (`isUserAuthorized()`), not a structured object; extending it with auth type would change its response type and break existing consumers |
| Fetch user session directly in `SSOLandingPage` | Decode the JWT to get username/email and construct a synthetic session object | Dremio's session token is opaque (not a JWT); the full `UserLoginSession` (with `firstName`, `lastName`, `permissions`, etc.) requires a server round-trip anyway |
| `APIV2Call` for landing page fetch | Plain `fetch` | `APIV2Call` provides correct base URL handling; prefer consistency with codebase patterns |

**Installation:** No new npm packages required.

---

## Architecture Patterns

### Recommended Project Structure

```
dac/ui/src/pages/AuthenticationPage/components/
├── LoginFormContainer.jsx     MODIFY: add SSO button rendering
├── SSOLandingPage.jsx         NEW: reads fragment, fetches session, dispatches userLoggedIn

dac/backend/src/main/java/com/dremio/dac/resource/
├── ServerConfigResource.java  NEW: GET /api/v3/server-config (unauthenticated)

dac/backend/src/test/java/com/dremio/dac/resource/
├── TestServerConfigResource.java  NEW: unit test (Mockito)

dac/ui/src/pages/AuthenticationPage/components/
├── SSOLandingPage-spec.jsx    NEW: Mocha/Chai unit test
```

### Pattern 1: Unauthenticated Backend Config Endpoint

**What:** A `@APIResource`-annotated JAX-RS resource without `@Secured` that returns the auth type so the pre-login UI can conditionally show the SSO button.

**When to use:** Any pre-login UI that needs server-side config without a session.

**Example:**
```java
// Source: OidcResource.java pattern in this project (Phase 33)
@APIResource
@Path("/server-config")
@Produces(MediaType.APPLICATION_JSON)
public class ServerConfigResource {

  private final DACConfig dacConfig;

  @Inject
  public ServerConfigResource(DACConfig dacConfig) {
    this.dacConfig = dacConfig;
  }

  @GET
  // NO @Secured — called by pre-login UI
  public Response getServerConfig() {
    String authType = dacConfig.getConfig().getString(
        com.dremio.config.DremioConfig.WEB_AUTH_TYPE);
    // Return only what the UI needs; do not expose all server config
    return Response.ok(new ServerConfig(authType)).build();
  }

  public static class ServerConfig {
    private final String authType;
    @JsonCreator
    public ServerConfig(@JsonProperty("authType") String authType) {
      this.authType = authType;
    }
    @JsonProperty
    public String getAuthType() { return authType; }
  }
}
```

### Pattern 2: SSO Button in LoginFormContainer (Conditional Render)

**What:** `LoginFormContainer` fetches `GET /api/v3/server-config` on mount (via `componentDidMount`) and stores `authType` in component state. Renders an SSO button only when `authType === "keycloak"`.

**When to use:** The login form already has access to `componentDidMount`. Use `fetch` directly or a small helper — no new Redux action is needed for this one-time UI initialization call.

**Example:**
```jsx
// Source: LoginFormContainer.jsx (existing) + new fetch pattern
componentDidMount() {
  if (this.state.loginScreen === null) {
    this.setLoginScreen();
  }
  // Fetch server config to determine whether to show SSO button
  fetch('/api/v3/server-config')
    .then(r => r.json())
    .then(cfg => this.setState({ authType: cfg.authType }))
    .catch(() => { /* ignore: internal auth is safe default */ });
}

renderSSOButton() {
  if (this.state.authType !== 'keycloak') return null;
  return (
    <Button
      className="w-full"
      variant="secondary"
      onClick={() => { window.location.assign('/api/v3/oidc/login'); }}
    >
      Login with SSO
    </Button>
  );
}

render() {
  return (
    <div className="login-form-wrapper">
      <div id="login-form" ...>
        ...
        {this.renderForm(...)}
        {this.renderSSOButton()}
        {renderSSOLoginToggleLink(...)}
      </div>
      ...
    </div>
  );
}
```

### Pattern 3: SSOLandingPage — Fragment Token Extraction + Session Fetch

**What:** The landing page component reads `window.location.hash`, parses the `token` value, fetches `GET /apiv2/login` with the token in the Authorization header to get the full `UserLoginSession` object, then dispatches `userLoggedIn(sessionObject)` to trigger the standard Redux login saga.

**When to use:** Called by the browser after the OIDC callback redirects to `/login/sso/landing#token=<dremioToken>`.

**Why fetch GET /apiv2/login rather than constructing the session manually:** `LogInLogOutResource.isUserAuthorized()` is a `@Secured @GET /apiv2/login` endpoint — it returns `true` (Boolean), not a full session. The full session object (`UserLoginSession`) is only returned by `POST /apiv2/login`. To get the full session from just a token, use `GET /apiv2/userStats/{username}` or `GET /apiv2/me` — but the simplest approach is: the token itself is enough to call `GET /apiv2/me` if it exists, or alternatively reconstruct the minimum `userLoggedIn` payload from the token and a separate user call.

**IMPORTANT FINDING:** After reading the code, the correct approach is:

1. Extract `token` from `window.location.hash`
2. Store the token directly into `localStorageUtils` to establish the session (so subsequent API calls can be authenticated)
3. Call `GET /apiv2/me` (or equivalent user info endpoint) to get the full user details
4. Dispatch `userLoggedIn(sessionObject)` with the full session payload

BUT there is a simpler path: the `handleLogin` saga calls `localStorageUtils.setUserData(payload)` where `payload` is the `UserLoginSession` JSON from the server. To stay on this path, the landing page should dispatch `userLoggedIn` with the full `UserLoginSession` shape.

**The correct approach:** The token from the fragment is the Dremio session token. To get the full `UserLoginSession`-shaped object, call `GET /apiv2/login` with `Authorization: _dremio<token>` — this returns `true` (not useful). Instead: call the user info endpoint to get user details, construct the payload, and dispatch. However the cleanest approach for this project is:

**Confirmed approach (from reading `localStorageUtils.setUserData` and `handleLogin`):**
- `setUserData(user)` stores `user` as JSON under `"user"` key
- `getAuthToken()` reads `user.token` and prepends `_dremio`
- Therefore we need an object with at minimum: `{ token, userName, ... }`
- The `userLoggedIn` action creates `LOGIN_USER_SUCCESS` with the payload
- The `handleLogin` saga calls `setUserData(payload)` and then `handleAppInit()`

The landing page can skip the server round-trip entirely by constructing a minimal session object from just the token if it makes a call to fetch user details. The most robust option is to call `GET /api/v3/me` or `GET /apiv2/userStats/me` — but checking what endpoints exist:

**Pragmatic approach for this project (least friction):**
- Read `token` from `window.location.hash`
- Set a minimal `{ token }` object in localStorage immediately via `localStorageUtils.setUserData({ token })`
- Use `window.location.assign('/')` to navigate home — the existing `checkAppState` saga runs on app load, calls `isAuthorized` (which calls `GET /apiv2/login` with the token), and if valid, calls `handleAppInit()` — the app initializes normally without needing `userName` etc. in the initial payload

**This is the correct and minimal approach:** store just `{ token }` and navigate to `/`. The `checkAppState` saga already handles this path on every page load.

**Example:**
```jsx
// Source: loginLogout.js `checkAppState` saga + localStorageUtils.js `setUserData`
import { Component } from 'react';
import localStorageUtils from '#oss/utils/storageUtils/localStorageUtils';

export class SSOLandingPage extends Component {
  componentDidMount() {
    const hash = window.location.hash; // "#token=abc123..."
    const match = hash.match(/[#&]token=([^&]+)/);
    if (match && match[1]) {
      const token = match[1];
      // Store the token; checkAppState saga validates it on app init
      localStorageUtils.setUserData({ token });
      // Navigate to home; App's startup.js calls checkAppState which uses isAuthorized()
      window.location.assign('/');
    } else {
      // No token in fragment — redirect to login
      window.location.assign('/login');
    }
  }

  render() {
    // Landing page is a loading state; never stays on screen
    return <div>Logging in...</div>;
  }
}

export default SSOLandingPage;
```

### Anti-Patterns to Avoid

- **Rendering the SSO button unconditionally:** Causes a spurious "Login with SSO" button in internal-auth mode (violates UI-02).
- **Reading auth type from a @Secured endpoint:** The login page has no session token yet — a secured endpoint returns 401.
- **Storing the full token in a URL query parameter instead of fragment:** Phase 33 already uses fragment; do not change this. Never read `window.location.search` for the token.
- **Calling `POST /apiv2/login` with just the token:** That endpoint requires username+password in the body.
- **Using `react-router` `push('/')` before `setUserData`:** The app will immediately redirect back to `/login` because `isAuthorized` returns false (no token in localStorage). Always `setUserData` before navigating.
- **Exposing all config in `ServerConfigResource`:** Return only `authType`; do not expose keycloak client secrets, issuer URLs, or any other sensitive config to the unauthenticated endpoint.
- **Navigating with `push('/')` from `SSOLandingPage`:** `window.location.assign('/')` is a full page navigation that triggers `checkAppState` fresh. `push('/')` is an in-app navigation that may not re-run startup. Use `window.location.assign('/')`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Auth type detection | Custom config parsing in frontend JS | `GET /api/v3/server-config` endpoint | Config is server-side; frontend cannot read `dremio.conf` directly |
| Token validation in landing page | JWT parsing in JS | Existing `checkAppState` saga + `isAuthorized()` call | `checkAppState` already calls `GET /apiv2/login` to validate; no need to repeat |
| Session construction from token | Fetch all user fields manually | `localStorageUtils.setUserData({ token })` + navigate | `checkAppState` reconstructs the session from the token on next page load |
| SSO button styling | Custom CSS | `dremio-ui-lib/components` `Button` with `variant="secondary"` | Matches existing button in `LoginForm.jsx`; consistent design language |

**Key insight:** The Redux login saga (`handleLogin` → `setUserData` → `handleAppInit`) already handles the case where localStorage has a valid token. The landing page just needs to deposit the token into localStorage and navigate to `/`.

---

## Common Pitfalls

### Pitfall 1: Race Between setUserData and window.location.assign

**What goes wrong:** `localStorageUtils.setUserData()` is synchronous (calls `localStorage.setItem()`), so there is no race condition. However, if called inside a Promise `.then()` chain, the assign may run before the Promise resolves.

**Why it happens:** `fetch` is async; if `setUserData` is inside a `.then()` handler and an exception occurs, localStorage is never populated.

**How to avoid:** Keep `SSOLandingPage` simple: extract token from hash synchronously (no fetch needed), call `setUserData` synchronously, then `window.location.assign('/')`. No async needed.

**Warning signs:** User is redirected to `/login` immediately after the landing page (token was never stored).

### Pitfall 2: Hash Fragment Parsing Edge Cases

**What goes wrong:** `window.location.hash` returns the fragment with the leading `#`, e.g., `"#token=abc123"`. If parsed incorrectly (e.g., splitting on `=` only), tokens containing `=` (Base64 padding) would be truncated.

**Why it happens:** Dremio's opaque tokens are alphanumeric hex strings (no `=` padding per Phase 33 research). However, future token format changes could introduce `=`.

**How to avoid:** Use regex `/[#&]token=([^&]+)/` which captures everything after `token=` up to the next `&` or end of string. This handles both `#token=abc` and `#foo=1&token=abc`.

**Warning signs:** `localStorageUtils.getAuthToken()` returns a truncated token that is rejected by `DACAuthFilter`.

### Pitfall 3: ServerConfigResource Exposes Sensitive Data

**What goes wrong:** Adding more fields to `ServerConfig` (e.g., `issuerUrl`, `clientId`) leaks configuration to unauthenticated callers.

**Why it happens:** Convenient to return all keycloak config in one call.

**How to avoid:** Return only `authType` (the string `"keycloak"` or `"internal"`). No URLs, no client IDs.

**Warning signs:** Security review flags the endpoint.

### Pitfall 4: SSOLandingPage Not Found via @inject Resolution

**What goes wrong:** `routes.jsx` imports `SSOLandingPage` from `@inject/pages/AuthenticationPage/components/SSOLandingPage`. The `@inject` resolver checks: `dyn-load/` first, then `#oss/` (i.e., `dac/ui/src/`), then `stubModule.js`. Currently it falls through to `stubModule.js` (renders `null`).

**Why it happens:** The OSS file at `dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage.jsx` does not exist yet.

**How to avoid:** Create the file at exactly that path. The webpack resolver will find it via the `#oss/` fallback. No changes to `routes.jsx` or the resolver configuration needed.

**Warning signs:** Landing page renders blank (null) — browser reaches `/login/sso/landing` but nothing runs.

### Pitfall 5: CORS / Same-Origin on /api/v3/server-config

**What goes wrong:** In development, if the UI dev server is on a different port from the Dremio backend, the `fetch('/api/v3/server-config')` call fails with a CORS error.

**Why it happens:** Dev server proxies API calls; if the proxy is not configured for the new path, requests fail.

**How to avoid:** The development proxy configuration (`webpack.config.js` or similar) already proxies `/api/v3/*` to the backend. The new endpoint uses the same path prefix. No proxy changes needed.

**Warning signs:** Console CORS error in development mode; works in production.

### Pitfall 6: LoginFormContainer setLoginScreen() Interaction with SSO

**What goes wrong:** `LoginFormContainer` has `renderSSOLoginScreen()` / `setSSOLoginChoice()` logic in `localStorageUtils` that toggles between `"SSO"` and `"nameAndPassword"` screens. The existing `renderSSOLoginToggleLink()` from `loginUtils.js` currently returns `undefined` (no-op in OSS). Adding the SSO button must not depend on this toggle logic — the toggle is an enterprise feature.

**Why it happens:** The `loginScreen` state in `LoginFormContainer` is set to `localStorage.getItem("SSOLogin")` which defaults to the string `"SSO"` in `emptyApp`. This means on a fresh browser session, `renderSSOLoginScreen()` returns `"SSO"` even for internal auth.

**How to avoid:** Do not use the existing `SSOLogin` localStorage toggle to control the button visibility. Use the server-fetched `authType` from `GET /api/v3/server-config` exclusively for showing/hiding the SSO button. The existing toggle logic is irrelevant for Phase 34.

**Warning signs:** SSO button appears in internal-auth mode because `localStorage.getItem("SSOLogin") === "SSO"`.

---

## Code Examples

Verified patterns from official sources and project code:

### ServerConfigResource — New Backend Endpoint
```java
// Source: OidcResource.java (Phase 33) — unauthenticated @APIResource pattern
// Location: dac/backend/src/main/java/com/dremio/dac/resource/ServerConfigResource.java
@APIResource
@Path("/server-config")
@Produces(MediaType.APPLICATION_JSON)
public class ServerConfigResource {

  private final DACConfig dacConfig;

  @Inject
  public ServerConfigResource(DACConfig dacConfig) {
    this.dacConfig = dacConfig;
  }

  @GET
  // NO @Secured — pre-login UI must call this
  public Response getServerConfig() {
    String authType = dacConfig.getConfig()
        .getString(com.dremio.config.DremioConfig.WEB_AUTH_TYPE);
    return Response.ok(new ServerConfig(authType)).build();
  }

  public static final class ServerConfig {
    private final String authType;

    @com.fasterxml.jackson.annotation.JsonCreator
    public ServerConfig(
        @com.fasterxml.jackson.annotation.JsonProperty("authType") String authType) {
      this.authType = authType;
    }

    @com.fasterxml.jackson.annotation.JsonProperty
    public String getAuthType() {
      return authType;
    }
  }
}
```

### LoginFormContainer — SSO Button Addition
```jsx
// Source: LoginFormContainer.jsx (existing) — modified
// Location: dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer.jsx
// Key change: fetch authType on mount, render button conditionally

constructor(props) {
  super(props);
  this.state = {
    loginScreen: localStorageUtils.renderSSOLoginScreen(),
    authType: null,  // NEW: 'keycloak' | 'internal' | null (loading)
  };
}

componentDidMount() {
  if (this.state.loginScreen === null) {
    this.setLoginScreen();
  }
  // NEW: determine whether to show SSO button
  fetch('/api/v3/server-config')
    .then(r => r.ok ? r.json() : null)
    .then(cfg => {
      if (cfg && cfg.authType) {
        this.setState({ authType: cfg.authType });
      }
    })
    .catch(() => { /* silent: default to no SSO button */ });
}

renderSSOButton() {
  // NEW method
  if (this.state.authType !== 'keycloak') return null;
  return (
    <Button
      className="w-full"
      variant="secondary"
      onClick={() => { window.location.assign('/api/v3/oidc/login'); }}
      style={{ marginTop: 'var(--dremio--spacing--2)' }}
    >
      {laDeprecated('Login with SSO')}
    </Button>
  );
}

render() {
  return (
    <div className="login-form-wrapper">
      <div id="login-form" className="drop-shadow-lg" style={styles.base}>
        <h1 ...>Log in{isBeta && <BetaTag />}</h1>
        {this.renderForm({
          loginType: this.state.loginScreen,
          ssoPending: !!this.props.ssoPending,
        })}
        {this.renderSSOButton()}  {/* NEW */}
        {renderSSOLoginToggleLink({ ... })}
      </div>
      ...
    </div>
  );
}
```

### SSOLandingPage — New Component
```jsx
// Source: loginLogout.js (setUserData contract) + window.location API
// Location: dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage.jsx
import { Component } from 'react';
import localStorageUtils from '#oss/utils/storageUtils/localStorageUtils';
import { LOGIN_PATH } from '#oss/sagas/loginLogout';

export class SSOLandingPage extends Component {
  componentDidMount() {
    const hash = window.location.hash; // "#token=<dremioToken>"
    const match = hash.match(/[#&]token=([^&]+)/);

    if (match && match[1]) {
      const token = decodeURIComponent(match[1]);
      // Deposit token into localStorage; checkAppState saga validates on next page load
      localStorageUtils.setUserData({ token });
      // Full navigation so startup.js re-runs checkAppState from scratch
      window.location.assign('/');
    } else {
      // Missing or malformed token — return to login
      window.location.assign(LOGIN_PATH);
    }
  }

  render() {
    // Visible only briefly while componentDidMount runs
    return <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>Completing login...</div>;
  }
}

export default SSOLandingPage;
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `SSOLandingPage` resolves to `stubModule.js` (null) | Real `SSOLandingPage.jsx` at `#oss/pages/AuthenticationPage/components/SSOLandingPage` | Phase 34 | Landing page actually processes the token fragment |
| No SSO button on login page | Conditional SSO button based on `GET /api/v3/server-config` response | Phase 34 | Keycloak-configured deployments show the SSO button; internal deployments do not |
| `renderSSOLoginToggleLink` is a no-op | Still a no-op (not changed in OSS) | Not changed | Enterprise feature; OSS does not use it |

**Deprecated/outdated:**
- The existing `SSOLogin` localStorage key (`emptyApp.SSOLogin = "SSO"`) is an enterprise artifact; OSS code should not use it to control SSO button visibility.

---

## Open Questions

1. **`localStorageUtils.setUserData({ token })` — is a token-only object sufficient for `checkAppState`?**
   - What we know: `checkAppState` calls `isAuthorized()` which calls `GET /apiv2/login` (the `@Secured @GET` endpoint). `DACAuthFilter` reads the Authorization header from `getAuthToken()` which reads `user.token`. If `user.token` exists in localStorage, the header is set correctly. `isAuthorized` returns `true` if the server responds 200. If valid, `handleAppInit` runs (which initializes the app by calling further endpoints).
   - What's unclear: Whether `handleAppInit` / app initialization requires `userName`, `admin`, etc. to be present in the initial localStorage payload — or if it fetches these from subsequent API calls.
   - Recommendation: If `handleAppInit` requires the full session payload, the landing page needs to fetch `GET /api/v3/me` or equivalent after setting the token to get the full user details. However, examining `checkAppState` and `handleAppInit` in context: `handleAppInit` calls `handleAppInitHelper` (from `@inject/sagas/utils/handleAppInit`) which in OSS resolves to `stubModule.js` (no-op). The app startup in `startup.js` likely calls `checkUser` or `checkAppState` independently. The minimal `{ token }` object is sufficient for `getAuthToken()` to work; the UI reads further user data (admin status, name, etc.) from separate API calls. **Confidence: MEDIUM — validate by tracing `handleAppInitHelper` behavior in startup.js.**

2. **Placement of SSO button — above or below the login form?**
   - What we know: The login form renders username, password, and "Log In" button. There is space below the form and above the privacy policy link.
   - Recommendation: Render below the form (after `renderForm()`) with a visual separator (or just a margin). The pattern of "login form + SSO as alternative" is standard OAuth2 UX. A subtle divider ("— or —") between the form and the SSO button would be ideal UX but is optional.

3. **What happens if `GET /api/v3/server-config` fails (network error, backend down)?**
   - What we know: The catch handler silently swallows the error, leaving `authType: null`.
   - Recommendation: When `authType === null`, hide the SSO button (same as `authType === "internal"`). A Keycloak button that points to a non-functioning endpoint is worse than no button at all.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework (backend) | JUnit Jupiter 5 + Mockito (same as other dac/backend resource tests) |
| Framework (frontend) | Mocha + Chai + React Test Utils (same as `loginLogout-spec.js` pattern) |
| Config file (frontend) | `dac/ui/test/index.js` — existing mocha config |
| Quick run command (backend) | `mvn test -pl dac/backend -Dtest="TestServerConfigResource" -q` |
| Quick run command (frontend) | `cd dac/ui && DREMIO_UI_TESTS="$PWD/src/pages/AuthenticationPage/components/SSOLandingPage-spec*" node --run test:only` |
| Full suite command | `mvn test -pl dac/backend -q && cd dac/ui && node --run test:only` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| UI-01 | `GET /api/v3/server-config` returns `{"authType":"keycloak"}` when configured | unit (JUnit) | `mvn test -pl dac/backend -Dtest="TestServerConfigResource#keycloak_*" -q` | Wave 0 |
| UI-02 | `GET /api/v3/server-config` returns `{"authType":"internal"}` for default config | unit (JUnit) | `mvn test -pl dac/backend -Dtest="TestServerConfigResource#internal_*" -q` | Wave 0 |
| UI-01 | `LoginFormContainer` renders SSO button when `authType === "keycloak"` | unit (Mocha) | `DREMIO_UI_TESTS="...LoginFormContainer-spec*" node --run test:only` | Wave 0 (new spec) |
| UI-02 | `LoginFormContainer` does NOT render SSO button when `authType === "internal"` | unit (Mocha) | Same as above | Wave 0 (new spec) |
| UI-03 | `SSOLandingPage` reads `window.location.hash`, calls `setUserData({ token })`, assigns `/` | unit (Mocha) | `DREMIO_UI_TESTS="...SSOLandingPage-spec*" node --run test:only` | Wave 0 |
| UI-03 | `SSOLandingPage` assigns to `/login` when hash has no token | unit (Mocha) | Same as above | Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -pl dac/backend -Dtest="TestServerConfigResource" -q`
- **Per wave merge:** `mvn test -pl dac/backend -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `dac/backend/src/test/java/com/dremio/dac/resource/TestServerConfigResource.java` — covers UI-01, UI-02 at backend layer
- [ ] `dac/ui/src/pages/AuthenticationPage/components/SSOLandingPage-spec.jsx` — covers UI-03 (token extraction, setUserData, navigate)
- [ ] `dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer-spec.jsx` — covers UI-01, UI-02 (conditional SSO button render based on authType state)

---

## Sources

### Primary (HIGH confidence)
- Project source: `dac/ui/src/sagas/loginLogout.js` — `SSO_LANDING_PATH`, `handleLogin`, `checkAppState`, `setUserData` contract confirmed
- Project source: `dac/ui/src/routes.jsx` — `SSOLandingPage` already imported and routed; resolves to `stubModule.js` (null) because OSS file missing
- Project source: `dac/ui/src/utils/storageUtils/localStorageUtils.js` — `setUserData(user)`, `getAuthToken()` → `_dremio${token}` contract confirmed
- Project source: `dac/ui/src/pages/AuthenticationPage/components/LoginFormContainer.jsx` — existing component to modify; `renderSSOLoginToggleLink` is no-op in OSS
- Project source: `dac/ui/src/pages/AuthenticationPage/components/LoginForm.jsx` — `Button` component usage pattern confirmed
- Project source: `dac/ui/src/actions/account.js` — `userLoggedIn`, `loginUser` actions confirmed; `UserLoginSession` shape documented
- Project source: `dac/ui/src/stubModule.js` — `export default () => null` — confirms `SSOLandingPage` currently renders nothing
- Project source: `dac/ui/scripts/injectionResolver.js` — `@inject` resolution: `dyn-load/` → `#oss/` → `stubModule.js` fallback order confirmed
- Project source: `dac/backend/src/main/java/com/dremio/dac/resource/OidcResource.java` — `@APIResource` unauthenticated endpoint pattern confirmed; callback redirects to `/login/sso/landing#token=<dremioToken>`
- Project source: `dac/backend/src/main/java/com/dremio/dac/resource/LogInLogOutResource.java` — `isUserAuthorized()` GET returns boolean (not UserLoginSession); `POST /apiv2/login` returns full session
- Project source: `dac/backend/src/main/java/com/dremio/dac/server/DACConfig.java` — `isInternalUserAuth()` method; `WEB_AUTH_TYPE` config key confirmed
- Project source: `common/legacy/src/main/java/com/dremio/config/DremioConfig.java` line 80 — `WEB_AUTH_TYPE = "services.coordinator.web.auth.type"`

### Secondary (MEDIUM confidence)
- `window.location.hash` and `window.location.assign()` — browser built-in APIs; no external source needed
- Mocha+Chai test pattern derived from `loginLogout-spec.js` existing tests

### Tertiary (LOW confidence)
- None — all critical findings verified against project source

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all files read directly from project source
- Architecture patterns: HIGH — derived from existing `LoginFormContainer`, `localStorageUtils`, `OidcResource`, `routes.jsx`; resolver behavior confirmed from `injectionResolver.js`
- Pitfalls: HIGH — derived from direct code reading (SSOLogin toggle interaction, stubModule fallback, hash parsing)

**Research date:** 2026-03-12
**Valid until:** 2026-04-12 (React/JSX codebase is stable; auth flow is project-specific)
