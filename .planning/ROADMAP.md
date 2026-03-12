# Roadmap: Dremio OSS Enhancements

## Milestones

- ✅ **v1.0 Naive RBAC** — Phases 1-6 (shipped 2026-02-19)
- ✅ **v1.1 Enable Iceberg REST Catalog** — Phases 7-8 (shipped 2026-02-20)
- ✅ **v1.2 GitHub Actions Docker Distribution** — Phases 9-11 (shipped 2026-02-21)
- ✅ **v1.3 Privilege Context & Enforcement** — Phases 12-20 (shipped 2026-02-24)
- ✅ **v1.4 RBAC Issue Hardening** — Phases 21-29 (shipped 2026-03-11)
- 🚧 **v1.5 Keycloak IdP Integration** — Phases 30-35 (in progress)

## Phases

<details>
<summary>✅ v1.0 Naive RBAC (Phases 1-6) — SHIPPED 2026-02-19</summary>

- [x] Phase 1: Design and Proto Schema (2/2 plans) — completed 2026-02-17
- [x] Phase 2: Persistence Layer (2/2 plans) — completed 2026-02-17
- [x] Phase 3: Service Layer (2/2 plans) — completed 2026-02-17
- [x] Phase 4: Catalog Enforcement and DI Wiring (3/3 plans) — completed 2026-02-18
- [x] Phase 5: DDL Handlers and System Tables (3/3 plans) — completed 2026-02-18
- [x] Phase 6: REST API and Access Path Hardening (3/3 plans) — completed 2026-02-18

See `milestones/v1.0-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.1 Enable Iceberg REST Catalog (Phases 7-8) — SHIPPED 2026-02-20</summary>

- [x] Phase 7: Plugin Wiring (1/1 plans) — completed 2026-02-20
- [x] Phase 8: End-to-End Validation (2/2 plans) — completed 2026-02-20

See `milestones/v1.1-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.2 GitHub Actions Docker Distribution (Phases 9-11) — SHIPPED 2026-02-21</summary>

- [x] Phase 9: Maven Build in CI (1/1 plans) — completed 2026-02-20
- [x] Phase 10: Dockerfile Adaptation (1/1 plans) — completed 2026-02-20
- [x] Phase 11: ECR Authentication and Push (1/1 plans) — completed 2026-02-20

See `milestones/v1.2-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.3 Privilege Context & Enforcement (Phases 12-20) — SHIPPED 2026-02-24</summary>

- [x] Phase 12: VDS Lifecycle Privilege Enforcement (2/2 plans) — completed 2026-02-21
- [x] Phase 13: VDS Definer Rights Safety Cluster (2/2 plans) — completed 2026-02-21
- [x] Phase 14: UDF Rights Verification and Owner Resolution (2/2 plans) — completed 2026-02-21
- [x] Phase 15: PDS SELECT Enforcement (Opt-in) (2/2 plans) — completed 2026-02-21
- [x] Phase 16: Container Visibility Filtering (2/2 plans) — completed 2026-02-21
- [x] Phase 17: Metadata Safety and Integration Testing (3/3 plans) — completed 2026-02-21
- [x] Phase 18: Code Hardening (1/1 plan) — completed 2026-02-23
- [x] Phase 19: Test Coverage and Documentation (1/1 plan) — completed 2026-02-23
- [x] Phase 20: File Browse and Promote RBAC Enforcement (2/2 plans) — completed 2026-02-23

See `milestones/v1.3-ROADMAP.md` for full phase details.

</details>

<details>
<summary>✅ v1.4 RBAC Issue Hardening (Phases 21-29) — SHIPPED 2026-03-11</summary>

- [x] Phase 21: Backend API Critical Security (1/1 plans) — completed 2026-03-11
- [x] Phase 22: Backend API High Security (2/2 plans) — completed 2026-03-11
- [x] Phase 23: UI Global Admin Gates (2/2 plans) — completed 2026-03-11
- [x] Phase 24: UI Dataset and Space Context Gates (2/2 plans) — completed 2026-03-11
- [x] Phase 25: Backend Logic Fixes (2/2 plans) — completed 2026-03-11
- [x] Phase 26: Information Disclosure Fix (1/1 plans) — completed 2026-03-11
- [x] Phase 27: Catalog API TOCTOU Fix (1/1 plans) — completed 2026-03-11
- [x] Phase 28: DACSecurityContext Role Enforcement (1/1 plans) — completed 2026-03-11
- [x] Phase 29: Backend Logic Gaps v2 (2/2 plans) — completed 2026-03-11

See `milestones/v1.4-ROADMAP.md` for full phase details.

</details>

### 🚧 v1.5 Keycloak IdP Integration (In Progress)

**Milestone Goal:** Make Dremio OSS authenticate users via Keycloak OIDC as a pluggable identity provider, with JIT provisioning, role mapping, and full login flow support (UI + API + JDBC/ODBC), while keeping internal auth and KVStore RBAC as the authorization layer.

- [x] **Phase 30: JWT Validation Infrastructure + Config** — OIDC foundation: config constants, OidcTokenValidator, JWKS provider with kid-based refresh, DACDaemonModule wiring (completed 2026-03-12)
- [x] **Phase 31: REST API Bearer JWT Authentication** — DACAuthFilter OIDC branch accepting Keycloak JWTs, token type discriminator, internal auth coexistence (completed 2026-03-12)
- [ ] **Phase 32: JIT Provisioning + Role Mapping** — OidcJitProvisioner, KeycloakRoleMapper, additive-tagged sync, DACAuthFilter JIT trigger
- [ ] **Phase 33: OIDC Redirect Web Flow** — OidcCallbackResource (login + callback endpoints), state/PKCE, code exchange, Dremio session issuance, id_token_hint storage
- [ ] **Phase 34: Web UI SSO Button** — LoginForm SSO button, config-discovery endpoint, SSO landing page completes login saga
- [ ] **Phase 35: Arrow Flight JDBC/ODBC + RP-Initiated Logout** — DremioBearerTokenAuthenticator OIDC branch, JDBC JIT provisioning, Keycloak session termination on logout

## Phase Details

### Phase 30: JWT Validation Infrastructure + Config
**Goal**: The OIDC foundation is in place — coordinator starts with `auth.type=keycloak` without crashing, and Keycloak JWTs can be validated against the JWKS endpoint in isolation
**Depends on**: Nothing (first v1.5 phase)
**Requirements**: CFG-01, CFG-02, CFG-03, TKN-01, TKN-03
**Success Criteria** (what must be TRUE):
  1. Coordinator starts cleanly when `services.coordinator.web.auth.type = "keycloak"` is set with valid issuer-url, client-id, client-secret, and redirect-uri config values
  2. `OidcTokenValidator.validate(jwtString)` returns a valid `TokenDetails` for a well-formed Keycloak RS256 JWT signed by the configured JWKS endpoint
  3. Token validation rejects JWTs with wrong issuer, wrong audience, or expired expiry with a clear error (not a 500)
  4. When Keycloak rotates its signing key, a token signed with the new `kid` succeeds after one automatic JWKS re-fetch (no server restart required)
  5. Role sync mode (`additive` vs `authoritative`) is readable from config and accessible to downstream components
**Plans:** 2/2 plans complete
Plans:
- [x] 30-01-PLAN.md — Maven module + KeycloakConfig + config constants + DACDaemonModule keycloak branch
- [x] 30-02-PLAN.md — OidcTokenValidator TDD (RS256 JWT validation against JWKS endpoint)

### Phase 31: REST API Bearer JWT Authentication
**Goal**: REST API clients can authenticate with a Keycloak-issued Bearer JWT and reach protected endpoints, while Dremio opaque tokens and internal admin login continue to work unchanged
**Depends on**: Phase 30
**Requirements**: TKN-02, COEX-01, COEX-02
**Success Criteria** (what must be TRUE):
  1. `curl -H "Authorization: Bearer <KC_JWT>"` against a protected REST endpoint returns 200 for a pre-provisioned user (not 401)
  2. `curl -H "Authorization: Bearer <DREMIO_OPAQUE_TOKEN>"` continues to work without any change in behavior
  3. A request with a malformed or expired Keycloak JWT returns 401, not 500
  4. The local admin user can log in with username and password (form-based) when `auth.type=keycloak` is active
**Plans:** 1/1 plans complete
Plans:
- [x] 31-01-PLAN.md — TDD: DACAuthFilter eyJ-discriminated Keycloak JWT dispatch + unit tests

### Phase 32: JIT Provisioning + Role Mapping
**Goal**: A Keycloak user who has never logged into Dremio is automatically provisioned on their first API call or login, with Keycloak realm roles synced to Dremio RBAC memberships
**Depends on**: Phase 31
**Requirements**: JIT-01, JIT-02, JIT-03, ROLE-01, ROLE-02, ROLE-03, ROLE-04
**Success Criteria** (what must be TRUE):
  1. A Keycloak user making their first REST API call (no prior Dremio account) is auto-created with `preferred_username` as username and `email` claim as email — the API call succeeds without manual admin intervention
  2. The JIT-provisioned user cannot log in via the username/password form (sentinel locked password is rejected)
  3. Two simultaneous first REST calls from the same new Keycloak user result in exactly one Dremio user record (no duplicate user error surfaced to either caller)
  4. Keycloak `realm_access.roles` that match existing Dremio RBAC roles are granted to the user on every login; roles not present in Dremio are silently ignored
  5. In additive mode, manually-assigned Dremio role memberships not in the Keycloak token are preserved after re-login
  6. In authoritative mode, Dremio role memberships not present in the current Keycloak token are revoked on re-login
**Plans:** 1/4 plans executed
Plans:
- [ ] 32-01-PLAN.md — Foundation: Membership.source proto field, RbacService source overload, KeycloakTokenDetails, validateWithClaims()
- [ ] 32-02-PLAN.md — TDD: JitUserProvisioner (REMOTE user creation with race-safe idempotency)
- [ ] 32-03-PLAN.md — TDD: KeycloakRoleSyncer (additive/authoritative RBAC membership sync)
- [ ] 32-04-PLAN.md — DACAuthFilter JIT+sync wiring, DACDaemonModule bindings, integration tests

### Phase 33: OIDC Redirect Web Flow
**Goal**: A browser user can initiate login via Keycloak's authorization code flow, and after Keycloak authentication, land back in Dremio with a valid session token — with CSRF protection throughout
**Depends on**: Phase 32
**Requirements**: OIDC-01, OIDC-02, OIDC-03, LOUT-02
**Success Criteria** (what must be TRUE):
  1. Hitting `GET /api/v3/oidc/login` returns a 302 redirect to Keycloak's authorization endpoint with `state`, `code_challenge`, and `code_challenge_method` parameters present
  2. After authenticating with Keycloak, the callback `GET /api/v3/oidc/callback` exchanges the code for tokens, provisions the user if needed, and redirects the browser to the Dremio SSO landing page with a valid Dremio session token
  3. A callback request with a tampered or missing `state` parameter returns 400 (not a successful login)
  4. The `id_token_hint` from the OIDC callback is stored server-side and associated with the Dremio session (available for logout use in Phase 35)
**Plans**: TBD

### Phase 34: Web UI SSO Button
**Goal**: The Dremio login page shows a "Login with SSO" button when Keycloak auth is configured, and the SSO landing page correctly completes the login saga in the browser
**Depends on**: Phase 33
**Requirements**: UI-01, UI-02, UI-03
**Success Criteria** (what must be TRUE):
  1. When `auth.type=keycloak`, the Dremio login page displays a "Login with SSO" button alongside the username/password form
  2. When `auth.type=internal`, the "Login with SSO" button is absent from the login page
  3. Clicking "Login with SSO" initiates the Keycloak redirect flow and, after successful Keycloak authentication, the browser lands on the Dremio home page fully logged in (localStorage populated with the same session data as a form-based login)
**Plans**: TBD

### Phase 35: Arrow Flight JDBC/ODBC + RP-Initiated Logout
**Goal**: JDBC/ODBC clients can authenticate with a Keycloak JWT as the password, and logging out of Dremio also terminates the user's Keycloak SSO session
**Depends on**: Phase 32
**Requirements**: JDBC-01, JDBC-02, LOUT-01
**Success Criteria** (what must be TRUE):
  1. An Arrow Flight / JDBC client connecting with a Keycloak access token as the password (detected by `eyJ` prefix) is authenticated and can execute queries
  2. A Keycloak user connecting via JDBC for the first time (no prior Dremio account) is auto-provisioned via JIT — the connection succeeds without manual admin intervention
  3. When a Keycloak-authenticated user clicks logout in Dremio, their Keycloak SSO session is terminated (RP-Initiated Logout), and subsequent Keycloak-SSO-initiated logins require re-authentication with Keycloak
**Plans**: TBD

## Quick Tasks

Ad-hoc tasks outside the milestone phase structure. See `.planning/quick/` for details.

| # | Description | Date | Status |
|---|-------------|------|--------|
| 1 | Fix Github Actions docker build ARG JAVA_IMAGE scope | 2026-02-25 | Done |
| 2 | Split docker-ecr workflow into build and docker jobs | 2026-02-25 | Done |
| 3 | Switch Docker push from ECR to GHCR | 2026-02-28 | Done |
| 4 | Merge develop into rbac and align .planning directory | 2026-03-01 | Done |

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Design and Proto Schema | v1.0 | 2/2 | Complete | 2026-02-17 |
| 2. Persistence Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 3. Service Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 4. Catalog Enforcement and DI Wiring | v1.0 | 3/3 | Complete | 2026-02-18 |
| 5. DDL Handlers and System Tables | v1.0 | 3/3 | Complete | 2026-02-18 |
| 6. REST API and Access Path Hardening | v1.0 | 3/3 | Complete | 2026-02-18 |
| 7. Plugin Wiring | v1.1 | 1/1 | Complete | 2026-02-20 |
| 8. End-to-End Validation | v1.1 | 2/2 | Complete | 2026-02-20 |
| 9. Maven Build in CI | v1.2 | 1/1 | Complete | 2026-02-20 |
| 10. Dockerfile Adaptation | v1.2 | 1/1 | Complete | 2026-02-20 |
| 11. ECR Authentication and Push | v1.2 | 1/1 | Complete | 2026-02-20 |
| 12. VDS Lifecycle Privilege Enforcement | v1.3 | 2/2 | Complete | 2026-02-21 |
| 13. VDS Definer Rights Safety Cluster | v1.3 | 2/2 | Complete | 2026-02-21 |
| 14. UDF Rights Verification and Owner Resolution | v1.3 | 2/2 | Complete | 2026-02-21 |
| 15. PDS SELECT Enforcement (Opt-in) | v1.3 | 2/2 | Complete | 2026-02-21 |
| 16. Container Visibility Filtering | v1.3 | 2/2 | Complete | 2026-02-21 |
| 17. Metadata Safety and Integration Testing | v1.3 | 3/3 | Complete | 2026-02-21 |
| 18. Code Hardening | v1.3 | 1/1 | Complete | 2026-02-23 |
| 19. Test Coverage and Documentation | v1.3 | 1/1 | Complete | 2026-02-23 |
| 20. File Browse and Promote RBAC Enforcement | v1.3 | 2/2 | Complete | 2026-02-23 |
| 21. Backend API Critical Security | v1.4 | 1/1 | Complete | 2026-03-11 |
| 22. Backend API High Security | v1.4 | 2/2 | Complete | 2026-03-11 |
| 23. UI Global Admin Gates | v1.4 | 2/2 | Complete | 2026-03-11 |
| 24. UI Dataset and Space Context Gates | v1.4 | 2/2 | Complete | 2026-03-11 |
| 25. Backend Logic Fixes | v1.4 | 2/2 | Complete | 2026-03-11 |
| 26. Information Disclosure Fix | v1.4 | 1/1 | Complete | 2026-03-11 |
| 27. Catalog API TOCTOU Fix | v1.4 | 1/1 | Complete | 2026-03-11 |
| 28. DACSecurityContext Role Enforcement | v1.4 | 1/1 | Complete | 2026-03-11 |
| 29. Backend Logic Gaps v2 | v1.4 | 2/2 | Complete | 2026-03-11 |
| 30. JWT Validation Infrastructure + Config | v1.5 | 2/2 | Complete | 2026-03-12 |
| 31. REST API Bearer JWT Authentication | v1.5 | 1/1 | Complete | 2026-03-12 |
| 32. JIT Provisioning + Role Mapping | 1/4 | In Progress|  | - |
| 33. OIDC Redirect Web Flow | v1.5 | 0/TBD | Not started | - |
| 34. Web UI SSO Button | v1.5 | 0/TBD | Not started | - |
| 35. Arrow Flight JDBC/ODBC + RP-Initiated Logout | v1.5 | 0/TBD | Not started | - |
