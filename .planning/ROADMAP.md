# Roadmap: Dremio OSS Naive RBAC

## Overview

This roadmap delivers deny-by-default role-based access control for Dremio OSS, closing the open-access gap where every user can query every view and call every UDF. The implementation proceeds from foundation (proto schema, packaging, feature flag) through persistence (KV stores for roles, grants, memberships), service logic (RbacService with ADMIN/PUBLIC built-ins), catalog enforcement (wiring validatePrivilege into CatalogImpl), SQL DDL interface (handlers for CREATE ROLE, GRANT, REVOKE, plus system tables), and finally access path hardening (REST API, DACSecurityContext fix). Each phase produces a testable artifact. The critical path is Phase 4 (catalog enforcement) where deny-by-default goes live.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Design and Proto Schema** - Lock namespace, key formats, proto3 messages, package structure, and feature flag definition
- [x] **Phase 2: Persistence Layer** - Three KV store creators for roles, grants, and memberships with full CRUD and persistence across restarts
- [x] **Phase 3: Service Layer** - RbacService with hasPrivilege(), ADMIN/PUBLIC built-in roles, bootstrap ADMIN assignment, and AccessControlListingManager
- [x] **Phase 4: Catalog Enforcement and DI Wiring** - Wire RbacService into CatalogImpl.validatePrivilege(), enforce SELECT/EXECUTE/CREATE_VIEW checks, system-user bypass, feature flag gating
- [ ] **Phase 5: DDL Handlers and System Tables** - Six handler classes for SQL DDL, SabotContext wiring for system tables, live sys.roles/sys.privileges/sys.membership queries
- [ ] **Phase 6: REST API and Access Path Hardening** - Nine REST endpoints for role and grant management

## Phase Details

### Phase 1: Design and Proto Schema
**Goal**: All foundational design decisions are locked and the proto schema, package structure, and feature flag exist as compilable artifacts -- unblocking all downstream phases
**Depends on**: Nothing (first phase)
**Requirements**: ENFC-09
**Success Criteria** (what must be TRUE):
  1. Proto3 file defines Role, Grant, and Membership messages that compile without errors and use no required fields
  2. KV store key format specifications are documented in code comments with composite-key patterns for grants (role+object+privilege)
  3. All new RBAC code lives under a package namespace that does not collide with Dremio Enterprise Edition packages
  4. A config-file feature flag (services.rbac.enabled, via DremioConfig) for RBAC enforcement exists, defaults to OFF, and requires coordinator restart to change
**Plans**: 2 plans

Plans:
- [x] 01-01-PLAN.md — Proto3 schema (rbac.proto) + config-file feature flag (DremioConfig.RBAC_ENABLED + dremio-reference.conf default)
- [x] 01-02-PLAN.md — Package scaffold: com.dremio.exec.rbac with RbacConfig constants, RoleStore, GrantStore, MembershipStore KVStoreCreationFunction stubs

### Phase 2: Persistence Layer
**Goal**: Roles, grants, and memberships can be created, read, updated, and deleted via KV stores, and all records survive coordinator restarts
**Depends on**: Phase 1
**Requirements**: ROLE-07, PRIV-07
**Success Criteria** (what must be TRUE):
  1. A role record written to the role store can be read back by name after a simulated restart (RocksDB persistence verified)
  2. A grant record written with a composite key (role + object_type + object_path + privilege) can be looked up by exact key in sub-millisecond time
  3. A membership record linking a user to a role can be queried by user_name to return all roles for that user
  4. All three stores use the current KVStore/KVStoreProvider API (not the deprecated LegacyKVStore) or the newest working exemplar available
  5. Unit tests cover CRUD operations and edge cases (duplicate role names, non-existent keys) for all three stores
**Plans**: 2 plans

Plans:
- [x] 02-01-PLAN.md — Implement CRUD method bodies in RoleStore, GrantStore, MembershipStore + RbacEntityNotFoundException and RbacEntityAlreadyExistsException exception classes
- [x] 02-02-PLAN.md — TDD unit tests for all three stores using LocalKVStoreProvider in-memory mode (create/get, duplicate throws, not-found throws, cascade delete, listByRole/listByUser)

### Phase 3: Service Layer
**Goal**: RbacService implements all business logic -- privilege checking, role lifecycle, membership management, ADMIN bypass, PUBLIC implicit membership, and bootstrap ADMIN assignment -- testable in isolation without catalog wiring
**Depends on**: Phase 2
**Requirements**: ROLE-05, ROLE-06, BOOT-01, ENFC-04, ENFC-05
**Success Criteria** (what must be TRUE):
  1. Calling hasPrivilege(username, SELECT, vdsPath) returns false when the user has no grants (deny-by-default verified in unit test)
  2. A user with the ADMIN role bypasses all privilege checks -- hasPrivilege() returns true regardless of specific grants
  3. All users implicitly belong to PUBLIC role -- a grant to PUBLIC applies to any user without explicit membership assignment
  4. The first user created through the bootstrap flow automatically receives ADMIN role membership
  5. RbacService implements AccessControlListingManager interface and returns role/grant/membership data suitable for system table consumption
**Plans**: 2 plans

Plans:
- [x] 03-01-PLAN.md — RbacService implementation: privilege resolution (hasPrivilege with ADMIN bypass + PUBLIC implicit), role/membership/grant lifecycle with immutability guards, bootstrap ADMIN assignment, fail-fast validation, AccessControlListingManager proto-to-POJO listing
- [x] 03-02-PLAN.md — Comprehensive unit tests for RbacService covering all 5 success criteria using LocalKVStoreProvider (deny-by-default, ADMIN bypass, PUBLIC grants, bootstrap, listing manager)

### Phase 4: Catalog Enforcement and DI Wiring
**Goal**: CatalogImpl.validatePrivilege() enforces real permission checks -- users without grants are denied access to VDS and UDFs, with system-user bypass and feature flag gating preserving existing behavior when disabled
**Depends on**: Phase 3
**Requirements**: ENFC-01, ENFC-02, ENFC-03, ENFC-06, ENFC-07, ENFC-08, BOOT-02
**Success Criteria** (what must be TRUE):
  1. A user with no SELECT grant on a VDS receives a "not found" error when querying it (deny-by-default enforced; error does not reveal object existence)
  2. A user with SELECT grant on a VDS (via any of their roles) can successfully query that VDS
  3. A user with EXECUTE grant on a UDF can call it; a user without the grant cannot
  4. A user with SELECT on a view that references other tables can query the view successfully -- inner tables resolve under the view owner's identity (definer-rights preserved)
  5. The system user ($dremio$) bypasses all privilege checks -- metadata sync, reflections, and internal jobs are unaffected by RBAC
  6. With the RBAC feature flag set to OFF (default), the system behaves identically to pre-RBAC Dremio -- no enforcement, no errors
  7. CREATE OR REPLACE VIEW is denied when the user lacks CREATE_VIEW privilege on the target path
**Plans**: 3 plans

Plans:
- [x] 04-01-PLAN.md — DI wiring (DACDaemonModule registers RbacService, CatalogServiceImpl threads it to CatalogImpl) + validatePrivilege() 3-step enforcement chain (flag, system-user, hasPrivilege)
- [x] 04-02-PLAN.md — SELECT/EXECUTE enforcement hooks in getTable/getFunctions paths (return null/empty for denied VDS/UDF) + CreateOrUpdateViewHandler ALTER-to-CREATE_VIEW fix
- [x] 04-03-PLAN.md — Unit tests for all RBAC enforcement paths in TestCatalogImpl (flag OFF, system user bypass, deny-by-default, allowed access, EXECUTE mapping, CREATE_VIEW mapping, definer-rights)

### Phase 5: DDL Handlers and System Tables
**Goal**: Users can manage roles, memberships, and grants entirely through SQL statements, and can inspect RBAC state via system table queries
**Depends on**: Phase 4
**Requirements**: ROLE-01, ROLE-02, ROLE-03, ROLE-04, PRIV-01, PRIV-02, PRIV-03, PRIV-04, PRIV-05, PRIV-06, DDL-01, DDL-02, DDL-03, DDL-04, DDL-05, DDL-06, OBSV-01, OBSV-02, OBSV-03
**Success Criteria** (what must be TRUE):
  1. `CREATE ROLE analyst` executes successfully and the role appears in `SELECT * FROM sys.roles`
  2. `GRANT ROLE analyst TO USER alice` executes and the membership appears in `SELECT * FROM sys.membership`
  3. `GRANT SELECT ON VDS myspace.myview TO ROLE analyst` executes and the grant appears in `SELECT * FROM sys.privileges`
  4. `REVOKE SELECT ON VDS myspace.myview FROM ROLE analyst` removes the grant, and the user can no longer query the view (when enforcement is enabled)
  5. `DROP ROLE analyst` removes the role and all associated memberships and grants
  6. `GRANT EXECUTE ON FUNCTION myspace.myfunc TO ROLE analyst` executes successfully
  7. All six DDL statements (CREATE ROLE, DROP ROLE, GRANT ROLE, REVOKE ROLE, GRANT privilege, REVOKE privilege) no longer throw "Enterprise Edition only"
**Plans**: 3 plans

Plans:
- [ ] 05-01-PLAN.md — SabotContext/ContextService/QueryContext wiring for RbacService provider + isAdminMember() visibility change (enables system tables and handler access)
- [ ] 05-02-PLAN.md — 6 DDL handler classes (RoleCreateHandler, RoleDropHandler, RoleGrantHandler, RoleRevokeHandler, CatalogGrantHandler, CatalogRevokeHandler) at exact FQCNs expected by SQL parsers
- [ ] 05-03-PLAN.md — Unit tests for all 6 DDL handlers (success paths, admin-only enforcement, correct RbacService calls) + system table wiring verification

### Phase 6: REST API and Access Path Hardening
**Goal**: Roles, memberships, and grants are manageable via REST endpoints suitable for UI integration, and catalog browsing is filtered by the user's effective grants
**Depends on**: Phase 5
**Requirements**: REST-01, REST-02, REST-03, REST-04, REST-05, REST-06, REST-07, REST-08, REST-09, META-03
**Success Criteria** (what must be TRUE):
  1. `GET /api/v3/rbac/roles` returns a list of all defined roles
  2. `POST /api/v3/rbac/roles` creates a new role and returns its details
  3. `DELETE /api/v3/rbac/roles/{name}` deletes a role
  4. `GET /api/v3/rbac/roles/{name}/members` returns the members of a role; `POST` and `DELETE` on the members sub-resource add/remove users
  5. `GET /api/v3/rbac/grants?object=...` returns grants for an object; `POST` and `DELETE` grant/revoke privileges
  6. REST endpoints require ADMIN role -- non-admin users receive 403 Forbidden
  7. Users browsing the catalog via REST API only see views and UDFs they have grants on (catalog visibility filtering)
**Plans**: TBD

Plans:
- [ ] 06-01: TBD
- [ ] 06-02: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5 -> 6

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Design and Proto Schema | 2/2 | Complete | 2026-02-17 |
| 2. Persistence Layer | 2/2 | Complete | 2026-02-17 |
| 3. Service Layer | 2/2 | Complete | 2026-02-17 |
| 4. Catalog Enforcement and DI Wiring | 3/3 | Complete | 2026-02-18 |
| 5. DDL Handlers and System Tables | 0/3 | Not started | - |
| 6. REST API and Access Path Hardening | 0/2 | Not started | - |
