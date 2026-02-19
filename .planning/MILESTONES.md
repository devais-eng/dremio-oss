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

