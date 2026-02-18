# Requirements: Dremio OSS Naive RBAC

**Defined:** 2026-02-17
**Core Value:** Users can only access views and UDFs they've been explicitly granted access to, with deny-by-default policy and admin bypass — closing the open-access gap in Dremio OSS.

## v1 Requirements

### Role Management

- [x] **ROLE-01**: Administrator can create named roles via `CREATE ROLE <name>` SQL
- [x] **ROLE-02**: Administrator can drop roles via `DROP ROLE <name>` SQL
- [x] **ROLE-03**: Administrator can assign users to roles via `GRANT ROLE <role> TO USER <user>` SQL
- [x] **ROLE-04**: Administrator can remove users from roles via `REVOKE ROLE <role> FROM USER <user>` SQL
- [ ] **ROLE-05**: Built-in ADMIN role exists at system startup and bypasses all privilege checks
- [ ] **ROLE-06**: Built-in PUBLIC role exists; all users implicitly belong to it without explicit membership
- [ ] **ROLE-07**: Roles and memberships persist across coordinator restarts (RocksDB KV store)

### Privilege Grants

- [x] **PRIV-01**: Administrator can grant SELECT on a VDS (view) to a role via `GRANT SELECT ON VDS <path> TO ROLE <role>`
- [x] **PRIV-02**: Administrator can revoke SELECT on a VDS from a role via `REVOKE SELECT ON VDS <path> FROM ROLE <role>`
- [x] **PRIV-03**: Administrator can grant CREATE_VIEW on a VDS to a role (controls CREATE OR REPLACE VIEW)
- [x] **PRIV-04**: Administrator can revoke CREATE_VIEW on a VDS from a role
- [x] **PRIV-05**: Administrator can grant EXECUTE on a UDF to a role
- [x] **PRIV-06**: Administrator can revoke EXECUTE on a UDF from a role
- [ ] **PRIV-07**: Privilege grants persist across coordinator restarts (RocksDB KV store)

### Enforcement

- [ ] **ENFC-01**: Deny-by-default — a user with no applicable grant cannot SELECT a VDS
- [ ] **ENFC-02**: A user with SELECT grant on a VDS (via any of their roles) can query that VDS
- [ ] **ENFC-03**: A user with EXECUTE grant on a UDF (via any of their roles) can call that UDF
- [ ] **ENFC-04**: ADMIN role members bypass all privilege checks
- [ ] **ENFC-05**: Grants to PUBLIC role apply to all users without explicit membership
- [ ] **ENFC-06**: System user (`$dremio$`) bypasses all privilege checks (internal operations unaffected)
- [ ] **ENFC-07**: Definer-rights model preserved — privilege is checked on the outermost entity only; inner tables in views resolve under the view owner's identity
- [ ] **ENFC-08**: CREATE_VIEW privilege is checked when a user executes CREATE OR REPLACE VIEW
- [ ] **ENFC-09**: Enforcement gated behind a feature flag (defaults to OFF); can be enabled via system option

### Bootstrap

- [ ] **BOOT-01**: First user created via bootstrap flow automatically receives ADMIN role membership
- [ ] **BOOT-02**: System starts successfully with RBAC disabled (default) — no enforcement, existing behavior preserved

### Observability

- [x] **OBSV-01**: `SELECT * FROM sys.roles` returns all defined roles (id, name, creator, created_at)
- [x] **OBSV-02**: `SELECT * FROM sys.privileges` returns all grants (role, object_type, object_path, privilege)
- [x] **OBSV-03**: `SELECT * FROM sys.membership` returns all role-user memberships

### SQL DDL

- [x] **DDL-01**: `CREATE ROLE` SQL statement executes successfully (wired to handler)
- [x] **DDL-02**: `DROP ROLE` SQL statement executes successfully
- [x] **DDL-03**: `GRANT ROLE TO USER` SQL statement executes successfully
- [x] **DDL-04**: `REVOKE ROLE FROM USER` SQL statement executes successfully
- [x] **DDL-05**: `GRANT <privilege> ON <type> <path> TO ROLE <role>` SQL statement executes successfully
- [x] **DDL-06**: `REVOKE <privilege> ON <type> <path> FROM ROLE <role>` SQL statement executes successfully

### REST API

- [ ] **REST-01**: REST endpoint to list all roles
- [ ] **REST-02**: REST endpoint to create a role
- [ ] **REST-03**: REST endpoint to delete a role
- [ ] **REST-04**: REST endpoint to list members of a role
- [ ] **REST-05**: REST endpoint to add a user to a role
- [ ] **REST-06**: REST endpoint to remove a user from a role
- [ ] **REST-07**: REST endpoint to list grants on an object
- [ ] **REST-08**: REST endpoint to grant a privilege
- [ ] **REST-09**: REST endpoint to revoke a privilege

### Catalog Visibility

- [ ] **META-03**: REST catalog API filtered by caller's effective grants — users only see views/UDFs they have grants on

## v2 Requirements

### Advanced Grant Semantics

- **ADV-01**: WITH GRANT OPTION — delegate privilege granting to non-admin roles
- **ADV-02**: REVOKE CASCADE — revoking from a grantor cascades to their grantees
- **ADV-03**: Container grants — GRANT SELECT on a space/folder applies to all contained objects

### Metadata Filtering

- **META-01**: INFORMATION_SCHEMA.VIEWS filtered by caller's effective grants
- **META-02**: sys.privileges restricted to ADMIN or own-grants-only for regular users
- ~~**META-03**: REST catalog API filtered by caller's effective grants~~ (moved to v1)

### Audit and Observability

- **AUDT-01**: Audit log entries for all RBAC DDL operations (CREATE ROLE, GRANT, REVOKE, etc.)
- **AUDT-02**: SHOW GRANTS SQL command (syntactic sugar over sys.privileges)
- **AUDT-03**: SHOW ROLES SQL command (syntactic sugar over sys.roles)

### Migration

- **MIGR-01**: Migration tooling to auto-grant PUBLIC SELECT on all existing views when enabling RBAC
- **MIGR-02**: DACSecurityContext.isUserInRole() returns real role membership instead of always true

## Out of Scope

| Feature | Reason |
|---------|--------|
| Nested roles (role hierarchy) | Flat roles sufficient for v1; recursive resolution adds complexity |
| DENY grants (negative permissions) | Deny-by-default already achieves the goal; DENY adds confusing priority resolution |
| Row-level security (RLS) | Views already serve as row-filtering mechanism |
| Column-level security (CLS) | Views are the column projection mechanism |
| Source-level or space-level permissions | Requires namespace path traversal; per-object grants sufficient for v1 |
| Physical dataset (PDS) permissions | Definer-rights model makes PDS permissions redundant |
| Planner-level enforcement | Catalog-level enforcement covers all access paths |
| Object ownership transfer | Not needed for naive RBAC model |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| ROLE-01 | Phase 5 | Done |
| ROLE-02 | Phase 5 | Done |
| ROLE-03 | Phase 5 | Done |
| ROLE-04 | Phase 5 | Done |
| ROLE-05 | Phase 3 | Done |
| ROLE-06 | Phase 3 | Done |
| ROLE-07 | Phase 2 | Done |
| PRIV-01 | Phase 5 | Done |
| PRIV-02 | Phase 5 | Done |
| PRIV-03 | Phase 5 | Done |
| PRIV-04 | Phase 5 | Done |
| PRIV-05 | Phase 5 | Done |
| PRIV-06 | Phase 5 | Done |
| PRIV-07 | Phase 2 | Done |
| ENFC-01 | Phase 4 | Done |
| ENFC-02 | Phase 4 | Done |
| ENFC-03 | Phase 4 | Done |
| ENFC-04 | Phase 3 | Done |
| ENFC-05 | Phase 3 | Done |
| ENFC-06 | Phase 4 | Done |
| ENFC-07 | Phase 4 | Done |
| ENFC-08 | Phase 4 | Done |
| ENFC-09 | Phase 1 | Done |
| BOOT-01 | Phase 3 | Done |
| BOOT-02 | Phase 4 | Done |
| OBSV-01 | Phase 5 | Done |
| OBSV-02 | Phase 5 | Done |
| OBSV-03 | Phase 5 | Done |
| DDL-01 | Phase 5 | Done |
| DDL-02 | Phase 5 | Done |
| DDL-03 | Phase 5 | Done |
| DDL-04 | Phase 5 | Done |
| DDL-05 | Phase 5 | Done |
| DDL-06 | Phase 5 | Done |
| REST-01 | Phase 6 | Pending |
| REST-02 | Phase 6 | Pending |
| REST-03 | Phase 6 | Pending |
| REST-04 | Phase 6 | Pending |
| REST-05 | Phase 6 | Pending |
| REST-06 | Phase 6 | Pending |
| REST-07 | Phase 6 | Pending |
| REST-08 | Phase 6 | Pending |
| REST-09 | Phase 6 | Pending |
| META-03 | Phase 6 | Pending |

**Coverage:**
- v1 requirements: 44 total
- Mapped to phases: 44
- Unmapped: 0

---
*Requirements defined: 2026-02-17*
*Last updated: 2026-02-17 after Phase 1 discussion (META-03 moved to v1 Phase 6)*
