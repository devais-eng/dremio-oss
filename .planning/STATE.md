---
gsd_state_version: 1.0
milestone: v1.5
milestone_name: Keycloak IdP Integration
status: completed
stopped_at: Completed 35-01-PLAN.md
last_updated: "2026-03-13T11:13:31.433Z"
last_activity: "2026-03-13 — Completed 35-01: Arrow Flight JWT authentication with JIT provisioning and role sync (JDBC-01, JDBC-02)"
progress:
  total_phases: 6
  completed_phases: 6
  total_plans: 12
  completed_plans: 12
  percent: 100
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-12)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.5 Keycloak IdP Integration — Phase 35: Arrow Flight JWT Auth (final)

## Current Position

Phase: 35 of 35 (Arrow Flight JWT Authentication) — COMPLETE
Plan: 35-01 complete — JWT dispatch wired into all three Flight auth validators (auth2 credential, auth2 bearer, legacy basic); all 1 plan in phase 35 done
Status: Phase 35 complete — v1.5 Keycloak IdP Integration COMPLETE (all 6 phases, 12 plans shipped)
Last activity: 2026-03-13 — Completed 35-01: Arrow Flight JWT authentication with JIT provisioning and role sync (JDBC-01, JDBC-02)

Progress: [██████████] 100%

## Shipped Milestones

- v1.0 Naive RBAC — 6 phases, 15 plans (shipped 2026-02-19)
- v1.1 Enable Iceberg REST Catalog — 2 phases, 3 plans (shipped 2026-02-20)
- v1.2 GitHub Actions Docker Distribution — 3 phases, 3 plans (shipped 2026-02-21)
- v1.3 Privilege Context & Enforcement — 9 phases, 17 plans (shipped 2026-02-24)
- v1.4 RBAC Issue Hardening — 9 phases, 14 plans (shipped 2026-03-11)

## Accumulated Context

### Key Design Decisions (v1.5)

- Backend-driven OIDC flow: UI SSO button calls `GET /api/v3/oidc/login`; all token exchange happens server-side
- Token type discriminator: `eyJ` prefix distinguishes Keycloak JWTs from Dremio opaque tokens in DACAuthFilter
- JIT provisioning in DACAuthFilter (not only in callback): required for REST/Flight clients who skip the web flow
- Additive-tagged membership sync: `source=keycloak` tag preserves manually-assigned Dremio roles in additive mode
- `preferred_username` claim as Dremio identity (not `sub`): operator constraint — do not rename Keycloak users
- Internal auth remains active alongside Keycloak: `LocalUsernamePasswordAuthProvider` is never disabled
- KeycloakConfig accepts `Config` interface (not `DremioConfig` directly): enables clean unit testing; production code passes `dacConfig.getConfig()` which returns DremioConfig (implements Config)
- services/keycloak pom.xml does NOT use exec-maven-plugin/BuildTimeScan: no annotated classes to scan; use credentials module as pattern, not tokens module
- OidcTokenValidator uses JWKSourceBuilder.retrying(true) — no custom TTL/rate-limit needed; Nimbus defaults (5-min cache, 30-s rate limit) are sufficient for Phase 30
- TestOidcTokenValidator uses @SuppressForbidden for com.sun.net.httpserver.HttpServer — internal JDK API but lightest in-process JWKS server, avoids adding WireMock/Jetty as test dep
- OidcTokenValidator bound in DACDaemonModule as registry.bind(OidcTokenValidator.class, new OidcTokenValidator(jwksUri, issuerUrl, clientId)) — injectable by Phase 31 (DACAuthFilter) and Phase 35 (Arrow Flight)
- DACAuthFilter uses OIDC-first with TokenManager fallback for eyJ tokens: Dremio's own JWTs start with eyJ too; Keycloak validator rejects them (wrong issuer/algorithm) so fallback to TokenManager is required for COEX-02
- ParseException caught in getUserNameFromToken(), not filter(): filter() only catches UserNotFoundException|NotAuthorizedException; ParseException is checked and would produce 500 if it escaped
- Membership.source = 5 uses proto3 string default ("") so all existing records deserialize without migration; pass source="keycloak" in addMembership 4-arg overload for Keycloak-synced memberships
- validateWithClaims() is separate from validate() for COEX-02: DACAuthFilter calls validate(); Phase 32 JitUserProvisioner/KeycloakRoleSyncer call validateWithClaims() to get email and realm_access.roles
- extractRealmRoles() swallows ParseException and returns empty list — missing/malformed roles degrade gracefully, never crash auth filter
- JIT provisioning catches broad Exception (not UserAlreadyExistException) from LegacyIndexedStore.put(): store doesn't declare UserAlreadyExistException; broad catch handles concurrent-write race
- JitUserProvisioner.provision() declares throws IOException for DACAuthFilter caller compatibility even though implementation never throws it
- KeycloakRoleSyncer takes RoleStore directly for ROLE-04 filtering: avoids RbacEntityNotFoundException for unmapped Keycloak roles without adding new RbacService methods; roleStore.get(roleId)==null silently skips that role
- dremio-sabot-kernel added as explicit dependency to services/keycloak pom.xml: RbacService and RoleStore are in sabot/kernel, not in a services module; required for KeycloakRoleSyncer compilation
- KeycloakTokenDetails stored in ContainerRequestContext.setProperty (not DACAuthFilter instance field): DACAuthFilter is a HK2 singleton; per-request state must use the request context for thread safety
- getUserNameFromToken uses validateWithClaims() when jitProvisioner non-null, validate() otherwise: Phase 31 COEX behavior preserved for non-JIT deployments
- Role sync runs AFTER user provisioning in DACAuthFilter: user must exist in RBAC store before addMembership() can succeed
- OidcStateStore uses package-private TTL constructor for testing and lazy cleanup on put() — no background sweeper thread required for an in-process ephemeral store
- PendingFlow is a private static final class (not a record) — Java 11 target in keycloak module; records require Java 16+
- Dremio checkstyle requires test method names to match `^(test[a-zA-Z0-9_]*|[a-z][a-zA-Z0-9]*)$` — use `test*` camelCase prefix, not snake_case
- OidcResource uses Response.status(FOUND) (302) not Response.temporaryRedirect() (307) for auth redirects — browser auth flows conventionally use 302; JAX-RS temporaryRedirect() returns 307
- OidcResource.exchangeCodeForTokens() is protected (not private) for spy-based test stubbing — avoids adding a TokenExchangeClient interface just for test isolation
- Lenient Mockito stubs required in setUp() when some tests null out @Inject @Nullable fields — without lenient(), strict mode throws UnnecessaryStubbingException for stubs the null-field-branch tests never consume
- ServerConfigResource returns only authType (not all DACConfig): unauthenticated endpoint must not expose issuer URLs, client IDs, or secrets — only the string "keycloak" or "internal" is safe to expose pre-login
- SSOLandingPage uses static _navigate/_getHash for testable window.location access: jsdom marks window.location as non-configurable — cannot delete or redefine it; expose statics for sinon.stub() without touching the real location object
- LoginFormContainer SSO button: Button from dremio-ui-lib renders as ForwardRef in enzyme shallow mode (not 'Button' string); use find({variant:'secondary'}) to locate the SSO button in tests; static _navigate for click handler stubbing
- Arrow Flight JWT auth (Phase 35): Store Provider<OidcTokenValidator> (not raw instance) in DremioFlightAuthProviderImpl — Flight binding at line 1764 runs AFTER setupUserService() (line 831); lazy .get() in addAuthHandler() resolves at DremioFlightService.start() time; both auth2 (DremioCredentialValidator, DremioBearerTokenAuthenticator) and legacy (DremioFlightServerBasicAuthValidator) validators extended with eyJ-prefix dispatch; invalid JWT throws UNAUTHENTICATED FlightRuntimeException

### Blockers/Concerns

- ~~Phase 34 (UI SSO button): frontend token delivery mechanism (URL fragment vs cookie) needs tracing in `loginLogout.js` / `localStorageUtils.setUserData()` before implementation plan~~ RESOLVED in 34-RESEARCH.md: use `setUserData({ token })` + `window.location.assign('/')` minimal approach
- ~~Phase 32 (role mapping): `source=keycloak` membership tag requires proto schema change to `rbac.proto`~~ RESOLVED in 32-01
- Phase 35 (JDBC long sessions): Keycloak's 5-min access token TTL incompatible with long-running BI connections; mitigation is documentation (exchange for Dremio session token via `POST /apiv2/login`)

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 4 | merge develop and align .planning directory. | 2026-03-01 | da7cb6b2c | [4-merge-develop-and-align-planning-directo](./quick/4-merge-develop-and-align-planning-directo/) |
| 5 | remove .planning from .gitignore | 2026-03-01 | 6ca527087 | [5-remove-planning-from-gitignore](./quick/5-remove-planning-from-gitignore/) |
| 6 | Read the opened pull requests and evaluate the comments of copilot. | 2026-03-02 | — | [6-read-the-opened-pull-requests-and-evalua](./quick/6-read-the-opened-pull-requests-and-evalua/) |
| 7 | Apply all actionable Copilot review items (O(1) roleIds, precomputed-path overload, semicolon injection block). | 2026-03-02 | 246251057 | [7-apply-all-actionable-copilot-review-item](./quick/7-apply-all-actionable-copilot-review-item/) |
| 8 | Enable RBAC and PDS SELECT enforcement by default (dremio-reference.conf). | 2026-03-02 | afb403227 | [8-enable-rbac-by-default](./quick/8-enable-rbac-by-default/) |

## Session Continuity

Last session: 2026-03-13T11:09:02.388Z
Stopped at: Completed 35-01-PLAN.md
Resume file: None
