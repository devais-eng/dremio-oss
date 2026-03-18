---
phase: 34-web-ui-sso-button
plan: "01"
subsystem: auth
tags: [jax-rs, jersey, dac-config, unauthenticated-endpoint, sso, keycloak]

# Dependency graph
requires:
  - phase: 33-oidc-redirect-web-flow
    provides: OidcResource callback redirects to /login/sso/landing#token=<dremioToken>; DACConfig.isInternalUserAuth() pattern

provides:
  - Unauthenticated GET /api/v3/server-config endpoint returning {"authType":"keycloak"|"internal"}
  - ServerConfigResource.java auto-registered under /api/v3/ via @APIResource classpath scan
  - ServerConfig inner class (single-field JSON, no secrets exposed)

affects:
  - 34-02 (UI frontend — LoginFormContainer fetches this endpoint to decide SSO button visibility)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@APIResource without @Secured: unauthenticated pre-login endpoint pattern (same as OidcResource)"
    - "Static final inner class ServerConfig with @JsonCreator/@JsonProperty for clean JSON serialization"

key-files:
  created:
    - dac/backend/src/main/java/com/dremio/dac/resource/ServerConfigResource.java
    - dac/backend/src/test/java/com/dremio/dac/resource/TestServerConfigResource.java
  modified: []

key-decisions:
  - "ServerConfigResource returns only authType (not all DACConfig): unauthenticated endpoint must not expose issuer URLs, client IDs, or secrets"
  - "ServerConfig is a static final inner class (not a separate file): self-contained resource, no external consumers of this DTO"
  - "DACConfig injected via @Inject constructor (not field injection): follows existing resource patterns in dac/backend"

patterns-established:
  - "Unauthenticated @APIResource endpoint: annotate class with @APIResource + @Path, omit @Secured on class AND methods; Jersey auto-discovers and registers under /api/v3/"

requirements-completed: [UI-01, UI-02]

# Metrics
duration: 4min
completed: 2026-03-12
---

# Phase 34 Plan 01: ServerConfigResource — Unauthenticated Backend Config Endpoint Summary

**Unauthenticated GET /api/v3/server-config endpoint returning {"authType":"keycloak"|"internal"} so the pre-login UI can conditionally render the SSO button**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-12T19:13:36Z
- **Completed:** 2026-03-12T19:16:51Z
- **Tasks:** 1 (TDD: RED + GREEN)
- **Files modified:** 2

## Accomplishments
- Unauthenticated JAX-RS endpoint `GET /api/v3/server-config` auto-registered via `@APIResource` — no manual wiring needed
- Returns `{"authType":"keycloak"}` or `{"authType":"internal"}` based on `services.coordinator.web.auth.type` config key
- Minimal surface: single-field response, no secrets or configuration details exposed to unauthenticated callers
- 3 unit tests covering keycloak config, internal config, and single-field-only response assertion

## Task Commits

TDD execution — RED before GREEN:

1. **RED: TestServerConfigResource (3 failing tests)** - `554f389c2` (test)
2. **GREEN: ServerConfigResource implementation** - `fa233a2b3` (feat)

**Plan metadata:** (docs commit below)

_TDD: RED commit compiled and verified as compilation failure; GREEN commit produced `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`_

## Files Created/Modified
- `dac/backend/src/main/java/com/dremio/dac/resource/ServerConfigResource.java` — Unauthenticated GET /api/v3/server-config endpoint with @APIResource, @Path("/server-config"), @Inject DACConfig constructor; ServerConfig inner class
- `dac/backend/src/test/java/com/dremio/dac/resource/TestServerConfigResource.java` — 3 unit tests: keycloak auth type, internal auth type, single-field response guard

## Decisions Made
- `ServerConfigResource` returns only `authType` — no secrets, no issuer URLs, no client IDs are exposed via this unauthenticated endpoint
- `ServerConfig` is a `static final` inner class rather than a separate DTO file — the class is only used here and has no external consumers
- Mocking pattern uses `@Mock DACConfig dacConfig` + `@Mock DremioConfig dremioConfig` chained via `when(dacConfig.getConfig()).thenReturn(dremioConfig)` — mirrors KeycloakConfig test patterns without needing a real config file

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness
- `GET /api/v3/server-config` is ready to be consumed by `LoginFormContainer.jsx` in plan 34-02
- The endpoint is already auto-discovered by Jersey's classpath scan via `@APIResource` — no additional wiring needed in `DACDaemonModule`

---
*Phase: 34-web-ui-sso-button*
*Completed: 2026-03-12*
