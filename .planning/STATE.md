---
gsd_state_version: 1.0
milestone: v1.5
milestone_name: Keycloak IdP Integration
status: in_progress
stopped_at: Completed 31-01-PLAN.md
last_updated: "2026-03-12T16:43:00Z"
last_activity: "2026-03-12 — Completed 31-01: DACAuthFilter eyJ-discriminated Keycloak JWT dispatch + 6 unit tests (TKN-02, COEX-01, COEX-02)"
progress:
  total_phases: 6
  completed_phases: 2
  total_plans: 4
  completed_plans: 4
  percent: 67
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-12)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.5 Keycloak IdP Integration — Phase 30: JWT Validation Infrastructure + Config

## Current Position

Phase: 31 of 35 (REST API Bearer JWT Authentication)
Plan: 31-01 complete
Status: Plan 31-01 complete — ready for Phase 32
Last activity: 2026-03-12 — Completed 31-01: DACAuthFilter eyJ-discriminated Keycloak JWT dispatch + 6 unit tests (TKN-02, COEX-01, COEX-02)

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

### Blockers/Concerns

- Phase 34 (UI SSO button): frontend token delivery mechanism (URL fragment vs cookie) needs tracing in `loginLogout.js` / `localStorageUtils.setUserData()` before implementation plan — MEDIUM confidence on exact flow
- Phase 32 (role mapping): `source=keycloak` membership tag requires proto schema change to `rbac.proto` — verify before coding starts
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

Last session: 2026-03-12T16:43:00Z
Stopped at: Completed 31-01-PLAN.md
Resume file: None
