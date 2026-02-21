# Requirements: Dremio OSS RBAC

**Defined:** 2026-02-20
**Core Value:** Users can only access views, tables, and UDFs they've been explicitly granted access to, with deny-by-default policy, privilege context switching (definer rights for VDS and UDF), and admin bypass.

## v1.2 Requirements

Requirements for v1.2 milestone. Each maps to roadmap phases.

### VDS Definer Rights

- [ ] **DEFN-01**: User can query a VDS they have SELECT on, even when underlying tables are not directly accessible to them (definer rights)
- [ ] **DEFN-02**: VDS expansion runs under the last modifier's privileges, not the querying user's
- [ ] **DEFN-03**: VDS-over-VDS chains with different owners resolve correctly at each level
- [ ] **DEFN-04**: Plan cache correctly scopes to definer identity chain (different definers produce different cache entries)
- [ ] **DEFN-05**: Deleted view owner causes an explicit permission error, not a silent fallback to query user
- [ ] **DEFN-06**: Cyclic VDS chains produce a clear validation error, not a StackOverflow

### VDS Lifecycle

- [ ] **LIFE-01**: User can only ALTER (update) a VDS if they have ALTER privilege on it
- [ ] **LIFE-02**: User can only DROP a VDS if they have DROP privilege on it
- [ ] **LIFE-03**: User can only CREATE a VDS if they have CREATE_VIEW privilege (closes v1.0 enforcement gap)

### PDS SELECT

- [ ] **PDS-01**: Admin can GRANT/REVOKE SELECT on a physical table to a role
- [ ] **PDS-02**: PDS with explicit grants are only accessible to users with a SELECT grant (opt-in enforcement: PDS without any grants remain universally accessible)
- [ ] **PDS-03**: PDS grants use distinct "PDS" object type, separate from "VDS" grants (no key collision)

### UDF Rights

- [ ] **UDF-01**: UDF execution uses definer semantics (body runs as UDF creator, verified and tested)
- [ ] **UDF-02**: CatalogEntityOwnershipImpl correctly returns owner for FUNCTION type
- [ ] **UDF-03**: User needs EXECUTE privilege to call a UDF (existing enforcement, integration test coverage added)

### Container Visibility

- [ ] **CONT-01**: Sources are only visible to non-admin users if they have access to at least one child object
- [ ] **CONT-02**: Spaces are only visible to non-admin users if they have access to at least one child object
- [ ] **CONT-03**: Folders are only visible to non-admin users if they have access to at least one child object
- [ ] **CONT-04**: Full ancestor path is shown when user has access to a leaf object deep in the hierarchy

### Metadata Safety

- [ ] **META-01**: sys.privileges table is readable only by ADMIN role
- [ ] **META-02**: DESCRIBE requires SELECT privilege on the target object
- [ ] **META-03**: EXPLAIN requires privileges on all objects referenced in the plan, for any command type (SELECT, CREATE, INSERT, etc.)

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Privilege Delegation

- **DELEG-01**: WITH GRANT OPTION allows delegating privilege granting
- **DELEG-02**: REVOKE CASCADE removes downstream delegated grants

### Audit & Operations

- **AUDIT-01**: Audit logging for RBAC DDL operations (GRANT, REVOKE, CREATE/DROP ROLE)
- **AUDIT-02**: Migration tooling for existing deployments enabling RBAC

### Extended Privileges

- **EXTPR-01**: Full DML privileges on Iceberg tables (INSERT, UPDATE, DELETE, MERGE)
- **EXTPR-02**: PDS CREATE privilege for non-admin users
- **EXTPR-03**: Container-level cascade grants (GRANT SELECT ON SPACE)
- **EXTPR-04**: DACSecurityContext.isUserInRole() real implementation

### Advanced Features

- **ADVNC-01**: Nested roles (role contains role)
- **ADVNC-02**: Per-view SQL SECURITY INVOKER toggle
- **ADVNC-03**: INFORMATION_SCHEMA filtering by user grants

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Row-level security | Complexity explosion; views already serve as row filters |
| Column-level security | Views already serve as column projection |
| DENY grants (negative permissions) | Deny-by-default achieves the same without complexity |
| Ownership transfer (GRANT OWNERSHIP) | Not needed for naive model |
| Container-level explicit grants | Container visibility is derived from child access, not directly grantable |
| Offline mode | Real-time catalog enforcement is the model |
| Per-view SQL SECURITY INVOKER | No concrete use case; adds per-view attribute complexity |
| Cross-source impersonation integration | Connector-level concern, separate subsystem |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| DEFN-01 | Phase 8 | Pending |
| DEFN-02 | Phase 8 | Pending |
| DEFN-03 | Phase 8 | Pending |
| DEFN-04 | Phase 8 | Pending |
| DEFN-05 | Phase 8 | Pending |
| DEFN-06 | Phase 8 | Pending |
| LIFE-01 | Phase 7 | Pending |
| LIFE-02 | Phase 7 | Pending |
| LIFE-03 | Phase 7 | Pending |
| PDS-01 | Phase 10 | Pending |
| PDS-02 | Phase 10 | Pending |
| PDS-03 | Phase 10 | Pending |
| UDF-01 | Phase 9 | Pending |
| UDF-02 | Phase 9 | Pending |
| UDF-03 | Phase 9 | Pending |
| CONT-01 | Phase 11 | Pending |
| CONT-02 | Phase 11 | Pending |
| CONT-03 | Phase 11 | Pending |
| CONT-04 | Phase 11 | Pending |
| META-01 | Phase 12 | Pending |
| META-02 | Phase 12 | Pending |
| META-03 | Phase 12 | Pending |

**Coverage:**
- v1.2 requirements: 22 total
- Mapped to phases: 22
- Unmapped: 0

---
*Requirements defined: 2026-02-20*
*Last updated: 2026-02-20 — traceability filled after roadmap creation*
