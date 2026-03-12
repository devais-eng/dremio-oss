# Dremio OSS Enhancements

## What This Is

Enterprise-grade enhancements for Dremio OSS. v1.0 delivered deny-by-default RBAC for views and UDFs. v1.1 enabled the Iceberg REST Catalog source type, allowing Dremio OSS to connect to external Iceberg REST catalog servers (Lakekeeper, Nessie, Polaris) and query tables through standard SQL. v1.2 added GitHub Actions CI/CD to build Docker images of the custom fork and push them to GitHub Container Registry (GHCR) on release tags. v1.3 shipped privilege context switching (definer rights for VDS and UDF), SELECT grants on physical tables, container visibility filtering, VDS lifecycle privileges, metadata safety checks, and file browse/promote admin restrictions — completing deny-by-default enforcement across every access path. v1.4 hardened the RBAC system by closing all 17 verified issues: UI permission gates, backend API authorization holes, a TOCTOU vulnerability, information disclosure, and backend logic gaps — making RBAC production-ready.

## Core Value

Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- ✓ Flat role management: CREATE ROLE, DROP ROLE, GRANT ROLE TO USER, REVOKE ROLE FROM USER — v1.0
- ✓ Built-in ADMIN role that bypasses all RBAC checks — v1.0
- ✓ Built-in PUBLIC role that all users implicitly belong to — v1.0
- ✓ GRANT/REVOKE SELECT on VDS (views) to roles — v1.0
- ✓ GRANT/REVOKE CREATE OR REPLACE on VDS to roles — v1.0
- ✓ GRANT/REVOKE EXECUTE on UDFs to roles — v1.0
- ✓ Deny-by-default policy: no grant = no access (except ADMIN) — v1.0
- ✓ Catalog-level enforcement via CatalogImpl.validatePrivilege() — v1.0
- ✓ SELECT privilege check in dataset resolution path (getTable/getDataset) — v1.0
- ✓ EXECUTE privilege check in UDF resolution path (getFunction) — v1.0
- ✓ Persist roles, memberships, and grants in RocksDB via KVStore — v1.0
- ✓ Wire up existing GRANT/REVOKE SQL DDL (no longer throws UnsupportedError in OSS) — v1.0
- ✓ REST API endpoints for role and grant management (9 endpoints at /api/v3/rbac) — v1.0
- ✓ System tables populated: sys.roles, sys.privileges, sys.membership — v1.0
- ✓ Iceberg REST Catalog source type discoverable and creatable via UI/API — v1.1
- ✓ Read-only operations: browse namespaces, list tables, SELECT from tables — v1.1
- ✓ Validated end-to-end against Lakekeeper and Nessie — v1.1
- ✓ GitHub Actions workflow triggered on git tag push — v1.2
- ✓ Maven build (Java 21) produces server distribution tarball — v1.2
- ✓ Docker image built from tarball with Java 17 runtime — v1.2
- ✓ Docker image pushed to GitHub Container Registry (GHCR) via GITHUB_TOKEN — v1.2
- ✓ Image tagged with git tag (v-prefix stripped) + latest — v1.2
- ✓ VDS definer rights: view expansion under last modifier's identity with cycle detection and deleted-owner safety — v1.3
- ✓ UDF definer semantics: FunctionConfig owner stamping, FUNCTION owner resolution fix — v1.3
- ✓ SELECT on physical tables (PDS): opt-in deny-by-default via `services.rbac.pds.enabled` — v1.3
- ✓ Container visibility: sources/spaces/folders hidden unless user has child access — v1.3
- ✓ VDS lifecycle privileges: ALTER, DROP, CREATE_VIEW enforced at all call sites — v1.3
- ✓ DESCRIBE gating: follows SELECT privilege — v1.3
- ✓ EXPLAIN gating: requires privileges on all referenced objects — v1.3
- ✓ sys.privileges admin-only — v1.3
- ✓ File browse and promote restricted to admin users — v1.3
- ✓ PDS visibility filtering: non-granted PDS hidden from catalog tree when PDS enforcement enabled — v1.3
- ✓ Plan cache definer-rights bypass: different definer chains produce different cache entries — v1.3
- ✓ UI permission gates: admin-only controls hidden from non-admin users (Settings, Add Source, Add Space, Users) — v1.4
- ✓ UI context menu gates: dataset actions gated by RBAC, space settings gear admin-only — v1.4
- ✓ Backend API security: method-level RBAC on User, Catalog, Collaboration, Scripts, Folders, Reflections APIs — v1.4
- ✓ DACSecurityContext.isUserInRole() fixed: @RolesAllowed annotations now effective — v1.4
- ✓ RBAC-aware dataset counts (v2 + v3 API paths) — v1.4
- ✓ User-scoped sys.membership and sys.privileges for non-admin users — v1.4
- ✓ Auto-grant SELECT/ALTER/DROP on view creation — v1.4
- ✓ CREATE_VIEW privilege resolution with correct object type — v1.4
- ✓ TOCTOU vulnerability fixed: ALTER check before rename in Catalog API — v1.4
- ✓ Jobs user filter scoped to caller only for non-admin users — v1.4

### Active

<!-- Current scope. Building toward these. -->

## Current Milestone: v1.5 Keycloak IdP Integration

**Goal:** Make Dremio OSS authenticate users via Keycloak OIDC as a pluggable identity provider, with JIT provisioning, role mapping, and full login flow support (UI + API + ODBC/JDBC), while keeping internal auth and KVStore RBAC as the authorization layer.

**Target features:**
- Pluggable authentication backend: Keycloak OIDC or internal auth, selected by configuration
- Web UI OIDC redirect login ("Login with SSO") with fallback to Dremio's form for internal users
- REST API Bearer JWT validation for Keycloak-issued tokens
- ODBC/JDBC token-based authentication support
- JIT (Just-In-Time) user provisioning on first Keycloak login
- Automatic Keycloak realm role → Dremio RBAC role mapping

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Row-level security — complexity explosion, not needed for naive RBAC
- Column-level security — same; views already serve as column projection
- Nested roles (role contains role) — flat roles are sufficient for v1
- Full DML on PDS (INSERT/UPDATE/DELETE/MERGE) — only SELECT on tables for v1.3; full DML is future scope
- PDS CREATE for non-admin users — only admins can create physical tables in sources; non-admin PDS CREATE is future scope
- DENY grants (negative permissions) — adds complexity; deny-by-default achieves the same
- Ownership transfer (GRANT OWNERSHIP) — not needed for naive model
- Source-level or space-level explicit grants — container visibility is derived from child access, not directly grantable
- Offline mode — real-time catalog enforcement is the model
- Credential vending propagation — DremioFileIO uses static Hadoop Config; static creds workaround sufficient for v1.1
- ARM64 multi-platform Docker builds — 5-10x slower via QEMU; no stated deployment need
- Running tests in CI — tag push implies code is already tested; build-only pipeline
- Docker layer cache (GHA) — storage cost + IAM complexity not justified until baseline proven

## Context

**v1.0 RBAC:** Shipped with ~4,577 LOC Java across 69 files. All RBAC code in `com.dremio.exec.rbac` package (sabot/kernel module). Enforcement wired into CatalogImpl, DDL handlers, REST resources, and CatalogServiceHelper.

**v1.1 Iceberg REST Catalog:** `@SourceType(value="RESTCATALOG")` added to `RestIcebergCatalogPluginConfig`, `restcatalog-layout.json` created with 3-tab UI form, `RESTCATALOG.svg` icon at classpath root. 120 LOC across 3 files. Validated against Lakekeeper and Nessie. Static `fs.s3a.*` credentials needed as workaround for credential vending gap.

**v1.2 GitHub Actions Docker Distribution:** CI/CD pipeline: `.github/workflows/docker-ghcr.yml` (≈72 lines) triggers on `v*` tag push, builds tarball via Maven (Java 21), stages into Docker context, builds multi-stage image (busybox extractor + eclipse-temurin:17-jre-jammy runtime), authenticates to GitHub Container Registry (GHCR) via `GITHUB_TOKEN`, and pushes with versioned + latest tags. 86 insertions across 3 files. Requires GHCR repository/namespace and appropriate `GITHUB_TOKEN` permissions (no AWS-specific secrets).

**v1.3 Privilege Context & Enforcement:** Shipped with ~3,091 LOC Java across 114 files. Definer rights for VDS/UDF, PDS SELECT enforcement, container visibility filtering, VDS lifecycle privileges (ALTER/DROP/CREATE_VIEW), metadata safety (sys.privileges admin-only, DESCRIBE/EXPLAIN gating), file browse/promote admin restrictions. UAT verified on Docker (port 19047) with 10/10 RBAC tests + PostgreSQL source PDS enforcement (6/6 tests).

**v1.4 RBAC Issue Hardening:** Shipped with ~7,001 LOC across 60 files (Java + JSX/TS). Closed all 17 verified RBAC issues: @RolesAllowed enforcement via fixed DACSecurityContext.isUserInRole(), RBAC privilege checks on all mutation APIs (Catalog, Collaboration, Scripts, Folders, Reflections), UI permission gates (login response drives admin flag → Settings nav, Add Source/Space, dataset/space context menus), RBAC-aware dataset counts (v2 + v3 paths), user-scoped sys tables, auto-grant on view creation, TOCTOU vulnerability fix, and jobs user filter scoping. 16/16 requirements satisfied, 72/72 observable truths verified. Known limitation: UI-04 entity permissions over-restrictive in OSS (secure-by-default).

Build caveat: Maven build requires Java 21 (enforcer [21,22) range).

### Future candidates (from requirements backlog)
- RBAC: WITH GRANT OPTION, REVOKE CASCADE, container grants, INFORMATION_SCHEMA filtering, audit logging, privilege caching
- Iceberg REST Catalog: write operations, credential vending fix, RBAC integration, auth config validation, multi-catalog validation (Polaris, Unity, Gravitino)
- CI/CD: Docker layer cache (GHA cache), workflow step summary, tag format validation, ARM64 multi-platform builds, automated smoke test
- Full DML privileges on Iceberg tables (INSERT, UPDATE, DELETE, MERGE)
- Migration tooling for existing deployments
- Per-entity RBAC permissions in OSS backend entity responses (for selective UI-04 visibility)

## Constraints

- **Tech stack**: Must use existing Dremio patterns — KVStore for persistence, protobuf for serialization, Jersey/JAX-RS for REST
- **Compatibility**: Must not break existing Dremio OSS functionality — RBAC is additive, gated behind feature flag
- **View expansion model**: Definer rights must integrate with Dremio's existing view expansion identity model, not replace it

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Catalog-level enforcement only (v1.0) | Covers all access paths (SQL + REST), simpler than dual-layer, consistent with EE approach | ✓ Good — v1.3 added definer rights within catalog enforcement layer |
| Deny by default | More secure than allow-by-default; standard practice for access control systems | ✓ Good — clean security model |
| Flat roles only | Simplicity; nested roles add resolution complexity without clear v1 value | ✓ Good — sufficient for OSS use case |
| Views as security boundary (v1.0) | Definer rights model makes inner-table checks redundant; standard SQL behavior | ✓ Good — v1.3 shipped both: definer rights on views + opt-in PDS SELECT enforcement |
| KV Store (RocksDB) persistence | Consistent with Dremio's existing metadata storage patterns | ✓ Good — survives restarts, uses existing infra |
| Wire up existing SQL DDL | GRANT/REVOKE/CREATE ROLE parsers already exist; avoids reinventing SQL grammar | ✓ Good — zero parser changes needed |
| Feature flag defaults to OFF | Safe deployment — existing behavior preserved until admin explicitly enables RBAC | ✓ Good — no surprises on upgrade |
| ADMIN and PUBLIC as synthetic constants | Never written to KV store; simplifies bootstrap and immutability | ✓ Good — clean separation |
| oss_rbac_ KV store prefix | Isolates from Dremio EE namespace; pipe separator for composite keys | ✓ Good — no collisions |
| Role IDs = slugified names (not UUIDs) | Human-readable keys, immutable (no rename support) | ✓ Good — simple lookup |
| No privilege caching in v1 | Hit KV store every hasPrivilege() call; simplicity over performance | ⚠️ Revisit — may need caching at scale |
| DDL works when RBAC flag is OFF | Admins set up roles/grants before enabling enforcement | ✓ Good — enables staged rollout |
| Separate PDS enforcement flag | `services.rbac.pds.enabled` independent of `services.rbac.enabled` | ✓ Good — allows VDS-only RBAC rollout first |
| Definer rights via ViewExpander identity | Reuse existing view expansion identity model, not a new privilege layer | ✓ Good — minimal code, maximum integration |
| File browse/promote admin-only | Guard all source types (not just file-based) for simplicity | ⚠️ Revisit — over-restrictive for database/catalog sources |
| Container visibility from grants | Derive container visibility from child grants, not explicit container grants | ✓ Good — no new grant type needed |
| @SourceType on concrete class only | ConnectionReaderImpl.getCandidateSources() skips abstract classes | ✓ Good — scanner finds it correctly |
| Static fs.s3a.* credentials workaround | DremioFileIO discards vended credentials from loadTable(); static Hadoop Config is the only path | ⚠️ Revisit — need credential vending propagation for IAM/STS |
| Validate against multiple REST catalogs | Nessie + Lakekeeper confirms plugin is spec-compliant, not server-specific | ✓ Good — portable across implementations |
| Tag-only CI trigger (no branch builds) | Avoids polluting registry with dev images; tag push implies code is tested | ✓ Good — clean release-only pipeline |
| Multi-stage Dockerfile (busybox extractor) | Eliminates tarball layer from final image; busybox is minimal and discarded | ✓ Good — clean image layers |
| eclipse-temurin:17-jre-jammy runtime | JRE (not JDK) for production; Java 17 matches runtime needs | ✓ Good — smaller image, appropriate runtime |
| Staging directory for Docker context | Sends only tarball + Dockerfile to daemon, not full repo (~2GB) | ✓ Good — fast builds, minimal context |
| Dual tagging: versioned + latest | Enables both pinned and floating image references | ✓ Good — standard Docker practice |
| Switch to GHCR from ECR | No AWS credentials required; GITHUB_TOKEN is sufficient; simpler for OSS forks | ✓ Good — zero external cloud dependency |
| Programmatic RBAC guards over @RolesAllowed (v1.4) | rbacService.hasPrivilege() provides entity-level checks; @RolesAllowed is complementary admin-only layer | ✓ Good — defense-in-depth, both layers enforced |
| RBAC-aware login response (v1.4) | Backend sends correct admin flag and SessionPermissions; frontend reads localStorage | ✓ Good — single source of truth, all UI gates driven by login response |
| Admin-only fallback for UI-04 entity permissions (v1.4) | OSS backend doesn't populate per-entity permissions; isAdmin || undefined = isAdmin for admin, false for non-admin | ⚠️ Revisit — secure but over-restrictive for non-admin users with explicit grants |
| TOCTOU fix: validate before mutate (v1.4) | ALTER check on current path before rename prevents privilege escalation via path manipulation | ✓ Good — eliminates race condition |
| DACSecurityContext 5-arg constructor (v1.4) | New constructor accepts RbacService + DremioConfig; 3-arg backward-compat preserved for all non-auth-filter callers | ✓ Good — zero breakage, clean upgrade path |

---
*Last updated: 2026-03-12 after starting milestone v1.5 Keycloak IdP Integration*
