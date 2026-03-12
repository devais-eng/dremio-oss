# Requirements: Dremio OSS Enhancements

**Defined:** 2026-03-12
**Core Value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.

## v1.5 Requirements

Requirements for Keycloak IdP Integration. Each maps to roadmap phases.

### Configuration

- [x] **CFG-01**: Operator can set `services.coordinator.web.auth.type = "keycloak"` to enable Keycloak authentication
- [x] **CFG-02**: Operator can configure Keycloak connection settings: issuer-url, client-id, client-secret, redirect-uri
- [x] **CFG-03**: Operator can configure role sync mode (additive or authoritative) via `services.keycloak.role.sync-mode`

### Token Validation

- [x] **TKN-01**: Dremio validates Keycloak-issued JWTs against Keycloak's JWKS endpoint (RS256 signature, expiry, issuer, audience)
- [x] **TKN-02**: DACAuthFilter accepts Keycloak Bearer tokens on REST API requests when auth.type=keycloak
- [x] **TKN-03**: JWKS cache auto-refreshes when a `kid` mismatch is detected (supports Keycloak key rotation)

### OIDC Login Flow

- [ ] **OIDC-01**: Web UI user can click "Login with SSO" and be redirected to Keycloak's login page (Authorization Code Flow)
- [ ] **OIDC-02**: After Keycloak authentication, callback exchanges code for tokens and mints a Dremio session token
- [ ] **OIDC-03**: OIDC flow uses state parameter + PKCE for CSRF protection

### JIT Provisioning

- [x] **JIT-01**: First Keycloak login auto-creates a Dremio user (username from `preferred_username`, email from `email` claim)
- [x] **JIT-02**: JIT-provisioned users have a locked sentinel password (cannot login via username/password form)
- [x] **JIT-03**: Concurrent first logins from the same Keycloak user do not cause duplicate user errors

### Role Mapping

- [x] **ROLE-01**: On each Keycloak login, `realm_access.roles` are synced to Dremio RBAC role memberships
- [x] **ROLE-02**: In additive mode, Keycloak roles are granted but existing Dremio-only roles are preserved
- [x] **ROLE-03**: In authoritative mode, Dremio roles not present in Keycloak `realm_access.roles` are revoked on login
- [ ] **ROLE-04**: Only pre-existing Dremio roles are mapped (unmapped Keycloak roles are silently ignored)

### Web UI

- [ ] **UI-01**: Login page shows "Login with SSO" button when auth.type=keycloak
- [ ] **UI-02**: Login page hides SSO button when auth.type=internal
- [ ] **UI-03**: SSO landing page receives Dremio session token and completes login flow (same localStorage contract as internal auth)

### JDBC/ODBC

- [ ] **JDBC-01**: Arrow Flight credential validator accepts Keycloak JWT as password (detects `eyJ` prefix, validates via JWKS)
- [ ] **JDBC-02**: JIT provisioning triggers on JDBC/ODBC first login (user auto-created if not exists)

### Internal Auth Coexistence

- [x] **COEX-01**: When auth.type=keycloak, local admin user can still login with username/password for bootstrap/recovery
- [x] **COEX-02**: Dremio-issued session tokens continue to work alongside Keycloak tokens (DACAuthFilter tries Keycloak first, falls back to Dremio token manager)

### Logout

- [ ] **LOUT-01**: When a Keycloak-authenticated user logs out of Dremio, their Keycloak session is also terminated (RP-Initiated Logout)
- [ ] **LOUT-02**: Logout stores `id_token_hint` during OIDC callback for use at logout time

## Future Requirements

Deferred to future release. Tracked but not in current roadmap.

### Configuration Enhancements

- **CFG-04**: Startup validation fetches OIDC discovery and verifies JWKS reachable; fails fast on misconfiguration
- **CFG-05**: Configurable role name prefix stripping (`services.keycloak.role.prefix`)
- **CFG-06**: Configurable admin role mapping key (`services.keycloak.admin.role`)

### Token Exchange

- **TKN-04**: POST /api/v3/login accepts Keycloak Bearer token and returns Dremio session token (simplifies automation scripts)

### Advanced Sync

- **ROLE-05**: SCIM user sync from Keycloak (proactive provisioning without login)
- **ROLE-06**: Multiple concurrent IdP support (Keycloak + internal simultaneously, not just fallback)

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Per-request Keycloak token introspection | Adds 100ms+ HTTP latency per API call; local JWT validation with cached JWKS is sufficient |
| Storing Keycloak access tokens in KVStore | Keycloak JWTs are self-contained; storing them duplicates state and creates expiry sync problems |
| SAML 2.0 support | Completely different protocol; Keycloak itself bridges SAML IdPs to OIDC for clients |
| Replacing Dremio RBAC with Keycloak Authorization Services | Dremio already has working RBAC; Keycloak for AuthN only, Dremio RBAC for AuthZ |
| Auto-creating Keycloak roles from Dremio RBAC | Creates bidirectional sync problem; Keycloak is authoritative for roles |
| LDAP-via-Keycloak | Conflates Keycloak IdP with LDAP support; separate milestone if needed |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CFG-01 | Phase 30 | Complete |
| CFG-02 | Phase 30 | Complete |
| CFG-03 | Phase 30 | Complete |
| TKN-01 | Phase 30 | Complete |
| TKN-03 | Phase 30 | Complete |
| TKN-02 | Phase 31 | Complete |
| COEX-01 | Phase 31 | Complete |
| COEX-02 | Phase 31 | Complete |
| JIT-01 | Phase 32 | Complete |
| JIT-02 | Phase 32 | Complete |
| JIT-03 | Phase 32 | Complete |
| ROLE-01 | Phase 32 | Complete |
| ROLE-02 | Phase 32 | Complete |
| ROLE-03 | Phase 32 | Complete |
| ROLE-04 | Phase 32 | Pending |
| OIDC-01 | Phase 33 | Pending |
| OIDC-02 | Phase 33 | Pending |
| OIDC-03 | Phase 33 | Pending |
| LOUT-02 | Phase 33 | Pending |
| UI-01 | Phase 34 | Pending |
| UI-02 | Phase 34 | Pending |
| UI-03 | Phase 34 | Pending |
| JDBC-01 | Phase 35 | Pending |
| JDBC-02 | Phase 35 | Pending |
| LOUT-01 | Phase 35 | Pending |

**Coverage:**
- v1.5 requirements: 25 total
- Mapped to phases: 25
- Unmapped: 0

---
*Requirements defined: 2026-03-12*
*Last updated: 2026-03-12 after roadmap creation — all 25 requirements mapped*
