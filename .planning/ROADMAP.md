# Roadmap: Dremio OSS Naive RBAC

## Milestones

- ✅ **v1.0 Naive RBAC** — Phases 1-6 (shipped 2026-02-19)
- 🚧 **v1.2 Privilege Context & Enforcement** — Phases 7-12 (in progress)

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

### 🚧 v1.2 Privilege Context & Enforcement (In Progress)

**Milestone Goal:** Add privilege context switching (definer rights for VDS and UDF), SELECT grants on physical tables, container visibility filtering, complete VDS lifecycle privilege enforcement, and metadata safety checks. Every access path — views, tables, UDFs, containers, describe, explain — is governed by explicit grants with deny-by-default policy.

- [x] **Phase 7: VDS Lifecycle Privilege Enforcement** — Wire ALTER, DROP, and CREATE_VIEW enforcement into the three missing call sites; close the v1.0 enforcement gap
- [x] **Phase 8: VDS Definer Rights Safety Cluster** — Implement view expansion under the last modifier's identity, shipping all eight interdependent pitfall guards as a single atomic unit
- [x] **Phase 9: UDF Rights Verification and Owner Resolution** — Confirm and harden UDF definer semantics; fix CatalogEntityOwnershipImpl for FUNCTION type; add integration test coverage
- [x] **Phase 10: PDS SELECT Enforcement (Opt-in)** — Grant SELECT on physical tables to roles; enforce access on tables that have explicit grants; tables without grants remain universally accessible
- [x] **Phase 11: Container Visibility Filtering** — Sources, spaces, and folders are hidden from non-admin users unless they have access to at least one child object; full ancestor path is shown (completed 2026-02-21)
- [x] **Phase 12: Metadata Safety and Integration Testing** — Restrict sys.privileges to ADMIN; gate DESCRIBE and EXPLAIN on existing privilege model; end-to-end integration tests across all v1.2 features (completed 2026-02-21)

## Phase Details

### Phase 7: VDS Lifecycle Privilege Enforcement
**Goal**: Users can only create, alter, and drop views when they hold the corresponding privilege — no view lifecycle operation succeeds without an explicit grant
**Depends on**: Phase 6 (v1.0 complete)
**Requirements**: LIFE-01, LIFE-02, LIFE-03
**Success Criteria** (what must be TRUE):
  1. A user without ALTER privilege who issues `ALTER VIEW` receives a permission denied error, not a silent success
  2. A user without DROP privilege who issues `DROP VIEW` receives a permission denied error, not a silent success
  3. A user without CREATE_VIEW privilege who issues `CREATE VIEW` receives a permission denied error (closes v1.0 enforcement gap where createView() had no validatePrivilege() call)
  4. An ADMIN user can perform all three operations regardless of explicit grants (admin bypass preserved)
  5. GRANT/REVOKE ALTER and GRANT/REVOKE DROP on a VDS are accepted by the SQL DDL layer and persisted correctly in the grant store
**Plans:** 2 plans
Plans:
- [ ] 07-01-PLAN.md — Core enforcement infrastructure: error message format, DROP object type, validateCreateViewPrivilege() method + interface chain, updated tests
- [ ] 07-02-PLAN.md — Call site wiring: fix DropViewHandler, CreateOrUpdateViewHandler ALTER/CREATE_VIEW, CatalogServiceHelper REST API enforcement

### Phase 8: VDS Definer Rights Safety Cluster
**Goal**: View expansion runs under the last modifier's identity, enabling users to query views over tables they cannot directly access — with all eight pitfall guards active as a unit
**Depends on**: Phase 7
**Requirements**: DEFN-01, DEFN-02, DEFN-03, DEFN-04, DEFN-05, DEFN-06
**Success Criteria** (what must be TRUE):
  1. User B with SELECT on view V can successfully query V even when V's underlying physical table T is not directly accessible to B (definer's grants are used during expansion)
  2. A VDS-over-VDS chain with different owners at each level resolves correctly — each view expands under its own creator's identity, not the final querying user's identity
  3. Revoking the definer's SELECT on the underlying table immediately causes view queries to fail for all invokers (no stale grant snapshot; live KV store is checked at expansion time)
  4. Querying a view whose owner account has been deleted produces an explicit "View owner no longer exists" permission error, not a silent fallback to the query user's grants
  5. A cyclic VDS chain (view A references view B references view A) produces a clear validation error, not a StackOverflowError
**Plans:** 2 plans
Plans:
- [ ] 08-01-PLAN.md — Core definer rights activation: CatalogEntityOwnershipImpl VDS owner fix, ViewExpander deleted-owner error, ViewExpansionContext cycle detection
- [ ] 08-02-PLAN.md — Plan cache definer-rights bypass + comprehensive unit tests for DEFN-01 through DEFN-06

### Phase 9: UDF Rights Verification and Owner Resolution
**Goal**: UDF definer semantics are confirmed working, the owner resolution bug in CatalogEntityOwnershipImpl is fixed for FUNCTION type, and integration tests document the expected caller/body identity split
**Depends on**: Phase 8
**Requirements**: UDF-01, UDF-02, UDF-03
**Success Criteria** (what must be TRUE):
  1. A user with EXECUTE privilege on a UDF can call it successfully even when the UDF body references tables the calling user cannot directly access (body runs as UDF owner)
  2. A user without EXECUTE privilege on a UDF receives a permission denied error when attempting to call it
  3. CatalogEntityOwnershipImpl.getCatalogEntityOwner() returns the correct owner username for FUNCTION entity type (no longer returns Optional.empty())
**Plans:** 2 plans
Plans:
- [ ] 09-01-PLAN.md — Proto owner field + owner stamping in UserDefinedFunctionCatalogImpl + CatalogEntityOwnershipImpl FUNCTION branch fix
- [ ] 09-02-PLAN.md — Unit tests for UDF ownership resolution (UDF-02), definer activation (UDF-01), and EXECUTE enforcement (UDF-03)

### Phase 10: PDS SELECT Enforcement (Opt-in)
**Goal**: When PDS enforcement is enabled, users must have explicit SELECT grants to access physical tables — deny-by-default, consistent with the VDS model; admin bypass preserved
**Depends on**: Phase 8
**Requirements**: PDS-01, PDS-02, PDS-03
**Success Criteria** (what must be TRUE):
  1. An admin can issue `GRANT SELECT ON PDS source.schema.table TO ROLE analyst` and the grant is persisted with a distinct "PDS" object type key (no collision with VDS grants on same path)
  2. When PDS enforcement is enabled, users without a SELECT grant on a PDS receive a permission denied error (deny-by-default: no grant = no access)
  3. An ADMIN user can access all PDS regardless of explicit grants (admin bypass via hasPrivilege short-circuit)
  4. A user with SELECT on a VDS wrapping a PDS can query the view successfully because definer rights are used during expansion — even if the user has no direct PDS SELECT grant
**Plans:** 2/2 plans complete
Plans:
- [ ] 10-01-PLAN.md — Config flag (RBAC_PDS_ENABLED), RbacService.hasAnyPdsGrant() helper, CatalogImpl.isRbacDeniedForPds() method + 6 call site wiring
- [ ] 10-02-PLAN.md — Unit tests: 6 PDS enforcement tests in TestCatalogImpl + 2 PDS GRANT/REVOKE tests in TestRbacDdlHandlers

### Phase 11: Container Visibility Filtering
**Goal**: Non-admin users see only the sources, spaces, and folders that contain at least one object they have access to — the catalog tree reflects actual access, not the full hierarchy
**Depends on**: Phase 6 (independent of Phases 7-10; can follow Phase 6 directly)
**Requirements**: CONT-01, CONT-02, CONT-03, CONT-04
**Success Criteria** (what must be TRUE):
  1. A non-admin user with SELECT on one VDS inside a space sees that space in the catalog listing; spaces containing no accessible objects are hidden
  2. A non-admin user with SELECT on a VDS inside a nested folder sees the folder and all its ancestor containers up to the source root
  3. A non-admin user who has no access to any object inside a source does not see that source in catalog listings
  4. An ADMIN user sees all sources, spaces, and folders regardless of grant coverage
**Plans:** 2/2 plans complete
Plans:
- [ ] 11-01-PLAN.md — Core algorithm (RbacService getAccessibleObjectPaths + hasAccessibleChildUnderPath) + CatalogServiceHelper space/folder filtering
- [ ] 11-02-PLAN.md — Resource endpoint wiring (SpaceResource, HomeResource, SpaceFolderResource, ResourceTreeResource) + RbacService unit tests

### Phase 12: Metadata Safety and Integration Testing
**Goal**: sys.privileges is admin-only, DESCRIBE and EXPLAIN are gated on existing privilege grants, and end-to-end integration tests prove all v1.2 features work correctly together
**Depends on**: Phases 7, 8, 9, 10, 11 (all features must be present)
**Requirements**: META-01, META-02, META-03
**Success Criteria** (what must be TRUE):
  1. A non-admin user who queries `SELECT * FROM sys.privileges` receives a permission denied error; an ADMIN user can query it without restriction
  2. A user without SELECT on a VDS or PDS who issues `DESCRIBE table_or_view` receives a permission denied error (DESCRIBE inherits SELECT enforcement)
  3. An EXPLAIN command referencing objects the user does not have access to fails with a permission denied error; EXPLAIN succeeds only when the user holds all required privileges on all referenced objects
**Plans:** 2/2 plans complete
Plans:
- [ ] 12-01-PLAN.md — sys.privileges admin-only enforcement: isRbacDeniedForSysPrivileges() in CatalogImpl + 4 unit tests
- [ ] 12-02-PLAN.md — DESCRIBE SELECT privilege check in DescribeTableHandler + UserException handling + META-02/03 unit tests

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Design and Proto Schema | v1.0 | 2/2 | Complete | 2026-02-17 |
| 2. Persistence Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 3. Service Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 4. Catalog Enforcement and DI Wiring | v1.0 | 3/3 | Complete | 2026-02-18 |
| 5. DDL Handlers and System Tables | v1.0 | 3/3 | Complete | 2026-02-18 |
| 6. REST API and Access Path Hardening | v1.0 | 3/3 | Complete | 2026-02-18 |
| 7. VDS Lifecycle Privilege Enforcement | v1.2 | 2/2 | Complete | 2026-02-21 |
| 8. VDS Definer Rights Safety Cluster | v1.2 | 2/2 | Complete | 2026-02-21 |
| 9. UDF Rights Verification and Owner Resolution | v1.2 | 2/2 | Complete | 2026-02-21 |
| 10. PDS SELECT Enforcement (Opt-in) | v1.2 | Complete    | 2026-02-21 | 2026-02-21 |
| 11. Container Visibility Filtering | v1.2 | Complete    | 2026-02-21 | - |
| 12. Metadata Safety and Integration Testing | v1.2 | Complete    | 2026-02-21 | - |
