# Milestones

## v1.0 Naive RBAC (Shipped: 2026-02-19)

**Phases completed:** 6 phases, 15 plans, 22 tasks
**Files affected:** 69 (48 created, 21 modified)
**Lines of code:** ~4,577 insertions (Java + Proto)
**Unit tests:** 133+
**Timeline:** 3 days (2026-02-17 to 2026-02-19)
**Git range:** dd84d7da6..2cc3b3c3d (56 commits on rbac branch)

**Delivered:** Deny-by-default role-based access control for Dremio OSS, closing the open-access gap where every authenticated user could query every view and call every UDF.

**Key accomplishments:**
- Deny-by-default catalog enforcement via CatalogImpl.validatePrivilege() with system-user bypass and feature flag gating
- Full SQL DDL interface: CREATE/DROP ROLE, GRANT/REVOKE ROLE TO USER, GRANT/REVOKE privilege ON VDS/FUNCTION (6 handler classes)
- RocksDB-backed persistence for roles, grants, and memberships via 3 dedicated KV stores
- REST API with 9 admin-only endpoints at /api/v3/rbac for role/membership/grant management
- Catalog visibility filtering across v2 and v3 REST APIs (non-admin users only see granted VDS/UDFs)
- System table observability: sys.roles, sys.privileges, sys.membership populated with live RBAC data

**Known gaps (fixed post-audit):**
- bulkGetTables() RBAC bypass — fixed in 64940d8de
- AT-specifier getTableSnapshot() RBAC bypass — fixed in 64940d8de
- v2 API visibility filtering — fixed in 64940d8de

**Archives:** `milestones/v1.0-ROADMAP.md`, `milestones/v1.0-REQUIREMENTS.md`, `milestones/v1.0-MILESTONE-AUDIT.md`

---


## v1.1 Enable Iceberg REST Catalog (Shipped: 2026-02-20)

**Phases completed:** 2 phases, 3 plans, 4 tasks
**Code files modified:** 3 (1 modified, 2 created)
**Lines of code:** 120 insertions (Java + JSON + SVG)
**Timeline:** 1 day (2026-02-20)
**Git range:** 37b035f80..079b07017

**Delivered:** Iceberg REST Catalog source type wired into Dremio OSS, enabling read-only connectivity to any Iceberg REST Catalog server (Lakekeeper, Nessie, Polaris) through standard SQL.

**Key accomplishments:**
- Added `@SourceType(value="RESTCATALOG")` annotation making the Iceberg REST Catalog plugin discoverable by Dremio's connection scanner
- Created `restcatalog-layout.json` with 3-tab UI form covering all 8 config fields (endpoint, namespaces, properties, credentials, caching)
- Validated end-to-end against Lakekeeper: source creation, namespace browsing, table listing, SELECT queries
- Validated OAuth2 bearer token authentication via `rest.token` catalog property
- Documented credential vending gap and established portable static `fs.s3a.*` workaround
- Validated plugin against both Lakekeeper and Nessie REST catalogs

### Known Gaps

- **CONN-03 (partial):** Credential vending from Lakekeeper `loadTable()` not propagated through DremioFileIO. Static `fs.s3a.*` credentials workaround validated. Fix point: `AbstractRestCatalogAccessor.getTableHandleInternal()`. Works for long-lived creds; fails for IAM/STS short-lived tokens.
- **hasAccessPermission() no-op:** All Dremio users have full read access to all REST catalog tables. RBAC integration deferred to future milestone.

**Archives:** `milestones/v1.1-ROADMAP.md`, `milestones/v1.1-REQUIREMENTS.md`, `milestones/v1.1-MILESTONE-AUDIT.md`

---


## v1.2 GitHub Actions Docker Distribution (Shipped: 2026-02-21)

**Phases completed:** 3 phases, 3 plans, 6 tasks
**Files affected:** 3 (1 created, 2 modified)
**Lines of code:** 86 insertions, 13 deletions (YAML + Dockerfile)
**Timeline:** 1 day (2026-02-20)
**Git range:** 35e40373b..fbc5a0b4e (5 commits on develop branch)

**Delivered:** End-to-end GitHub Actions CI/CD pipeline that builds Docker images of the custom Dremio OSS fork and pushes them to GitHub Container Registry (GHCR) on git tag pushes. (Originally targeted AWS ECR; switched to GHCR via quick tasks 1-3.)

**Key accomplishments:**
- GitHub Actions workflow triggered on `v*` tag pushes with version extraction, Java 21 setup, and Maven cache
- Maven build producing `distribution/server/target/dremio-community-{version}.tar.gz` with all build flags
- Multi-stage Dockerfile rewrite: busybox extractor + eclipse-temurin:17-jre-jammy runtime (replacing wget + Java 11 JDK)
- Staging directory pattern isolating Docker build context (tarball + Dockerfile only) from full repo
- GHCR authentication via `GITHUB_TOKEN` — no AWS credentials required
- Dual Docker image tagging: versioned tag from git tag + `latest` tag on every push

**Quick task follow-ups (post-milestone):**
- Quick-1: Fix ARG JAVA_IMAGE scope in Dockerfile (2026-02-25)
- Quick-2: Split docker-ecr workflow into separate build and docker jobs (2026-02-25)
- Quick-3: Switch push target from ECR to GHCR (2026-02-28)

**Archives:** `milestones/v1.2-ROADMAP.md`, `milestones/v1.2-REQUIREMENTS.md`, `milestones/v1.2-MILESTONE-AUDIT.md`

---


## v1.3 Privilege Context & Enforcement (Shipped: 2026-02-24)

**Phases completed:** 9 phases, 17 plans, 30 tasks
**Files affected:** 114 files
**Lines of code:** ~3,091 Java insertions
**Timeline:** 5 days (2026-02-20 to 2026-02-24)
**Git range:** 2a8cf2427..cf526f2cc (84 commits on rbac branch)

**Delivered:** Privilege context switching (definer rights for VDS and UDF), SELECT grants on physical tables, container visibility filtering, VDS lifecycle privileges, metadata safety checks, and file browse/promote admin restrictions — completing deny-by-default enforcement across every access path.

**Key accomplishments:**
- VDS lifecycle enforcement: ALTER, DROP, and CREATE_VIEW privileges enforced at all SQL DDL and REST API call sites
- Definer rights: view expansion runs under the last modifier's identity with cycle detection, deleted-owner safety, and plan cache definer-chain bypass
- UDF definer semantics: FunctionConfig owner stamping, CatalogEntityOwnershipImpl FUNCTION fix, EXECUTE enforcement verified
- PDS SELECT enforcement: opt-in deny-by-default SELECT grants on physical tables with separate `services.rbac.pds.enabled` flag
- Container visibility filtering: sources, spaces, and folders hidden unless user has access to at least one child; full ancestor path shown
- Metadata safety: sys.privileges admin-only, DESCRIBE/EXPLAIN gated on privilege model, file browse/promote restricted to admins
- UAT verified on Docker (port 19047) with 10/10 RBAC tests passing; PostgreSQL source tested with PDS enforcement

**Known gaps (from audit — all low severity):**
- DROP VDS requires SELECT + DROP grants (undocumented cross-phase constraint; misleading "Unknown view" error)
- getTable(String datasetId) PDS enforcement TODO (versioned/time-travel path, not main query path)
- UI search for promoted PDS blocked for non-admin users (guard applies to all source types)
- Container visibility tested via SQL proxy only, not REST API listing endpoints

**Archives:** `milestones/v1.3-ROADMAP.md`, `milestones/v1.3-REQUIREMENTS.md`, `milestones/v1.3-MILESTONE-AUDIT.md`

---

