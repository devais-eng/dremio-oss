# Roadmap: Dremio OSS Enhancements

## Milestones

- ✅ **v1.0 Naive RBAC** — Phases 1-6 (shipped 2026-02-19)
- ✅ **v1.1 Enable Iceberg REST Catalog** — Phases 7-8 (shipped 2026-02-20)
- ✅ **v1.2 GitHub Actions Docker Distribution** — Phases 9-11 (shipped 2026-02-21)
- ✅ **v1.3 Privilege Context & Enforcement** — Phases 12-20 (shipped 2026-02-24)
- 🚧 **v1.4 RBAC Issue Hardening** — Phases 21-26 (in progress)

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

### v1.4 RBAC Issue Hardening (In Progress)

**Milestone Goal:** Fix all verified RBAC issues — UI permission gates, backend API authorization holes, and information disclosure bugs — to make the RBAC system production-ready.

- [x] **Phase 21: Backend API Critical Security** — Close write-path authorization holes in User API and Catalog API (completed 2026-03-11)
- [x] **Phase 22: Backend API High Security** — Add method-level auth to Collaboration, Scripts, Folders, and Reflections APIs (completed 2026-03-11)
- [x] **Phase 23: UI Global Admin Gates** — Hide admin-only Settings sub-pages, Add Source, and Add Space from non-admin users (completed 2026-03-11)
- [x] **Phase 24: UI Dataset and Space Context Gates** — Remove unauthorized dataset context menu actions and space settings gear (completed 2026-03-11)
- [x] **Phase 25: Backend Logic Fixes** — RBAC-aware dataset counts, accessible sys tables, and auto-grant on view creation (completed 2026-03-11)
- [ ] **Phase 26: Information Disclosure Fix** — Restrict Jobs page user filter to prevent username enumeration

## Phase Details

### Phase 21: Backend API Critical Security
**Goal**: Users without admin role cannot create, update, delete, or promote catalog items or user accounts through the v3 API
**Depends on**: Phase 20 (v1.3 complete)
**Requirements**: API-01, API-02
**Success Criteria** (what must be TRUE):
  1. A non-admin API call to `POST /api/v3/user` or `PUT /api/v3/user/{id}` returns 403
  2. A non-admin API call to create a catalog item returns 403 unless the user holds the required RBAC privilege on the target
  3. A non-admin API call to delete or promote a catalog item returns 403 unless the user holds ALTER or DROP privilege
  4. An admin API call to any of the above succeeds (no regression)
**Plans**: 1 plan
Plans:
- [x] 21-01-PLAN.md — Harden User API and Catalog API write paths with admin-only and RBAC privilege checks

### Phase 22: Backend API High Security
**Goal**: Collaboration, Scripts, Folders, and Reflections API endpoints enforce RBAC before mutating data
**Depends on**: Phase 21
**Requirements**: API-03, API-04, API-05, API-06
**Success Criteria** (what must be TRUE):
  1. A non-admin call to set tags or wiki on an entity returns 403 if the caller lacks ALTER privilege on that entity
  2. A non-admin call to `GET /api/v3/scripts` with `createdBy` set to another user's name returns only the caller's own scripts or an empty list
  3. A non-admin call to create or delete a folder in a space returns 403 if the caller lacks CREATE or ALTER privilege on the parent space
  4. A non-admin call to create, edit, or delete a reflection returns 403 if the caller lacks ALTER privilege on the underlying dataset
  5. Admin calls to all of the above succeed without regression
**Plans**: 2 plans
Plans:
- [x] 22-01-PLAN.md — Add RBAC enforcement to Collaboration API (ALTER on tags/wiki) and Scripts API (createdBy restriction)
- [x] 22-02-PLAN.md — Add RBAC enforcement to SpaceFolderResource (CREATE_FOLDER/ALTER on folders) and ReflectionResource (ALTER on dataset)

### Phase 23: UI Global Admin Gates
**Goal**: Non-admin users see a UI that reflects only the actions they are authorized to take at the global navigation level
**Depends on**: Phase 20 (v1.3 complete, can execute independently of Phase 21-22)
**Requirements**: UI-01, UI-02, UI-03, UI-05
**Success Criteria** (what must be TRUE):
  1. A non-admin user navigating to Settings sees no Users tab and no Add User or Delete User controls
  2. A non-admin user who lacks `canCreateSource` permission sees no Add Source button in the Sources panel
  3. A non-admin user sees no Add Space button in the sidebar Spaces panel
  4. A non-admin user navigating to Settings cannot reach Node Activity, Engines, Queue Control, or Users sub-pages (route is hidden or returns to home)
  5. Admin users see all of the above controls normally (no regression)
**Plans**: 2 plans
Plans:
- [x] 23-01-PLAN.md — Make login endpoint RBAC-aware: set admin flag and SessionPermissions based on actual role membership
- [x] 23-02-PLAN.md — Filter admin-only Settings nav items, update route guards to allow non-admin access to Support/Preferences, gate UsersView controls

### Phase 24: UI Dataset and Space Context Gates
**Goal**: Dataset context menus and space settings controls expose only the actions the current user is authorized to perform
**Depends on**: Phase 23
**Requirements**: UI-04, UI-06
**Success Criteria** (what must be TRUE):
  1. A non-admin user opening a dataset context menu sees no Delete, Rename, Move, Edit, or Settings items for datasets they have no privileges on
  2. A user with only SELECT on a dataset sees no destructive or mutating actions in the dataset context menu
  3. A non-admin user who lacks space management permissions sees no settings gear icon on the space
  4. A user with appropriate privileges still sees and can use the relevant context menu actions (no regression)
**Plans**: 2 plans
Plans:
- [ ] 24-01-PLAN.md — Gate dataset context menu actions (Edit, Rename, Move, Settings, Delete) behind RBAC entity permissions
- [ ] 24-02-PLAN.md — Hide space settings gear and Delete action from non-admin users in header, AllSpacesView, and space menu

### Phase 25: Backend Logic Fixes
**Goal**: Dataset counts, system table queries, and view creation reflect the caller's RBAC context correctly
**Depends on**: Phase 21
**Requirements**: LOGIC-01, LOGIC-02, LOGIC-03
**Success Criteria** (what must be TRUE):
  1. The dataset count displayed next to a space name matches the count of datasets the current user can actually see, not the total count
  2. A non-admin user can query `sys.membership` and receives rows scoped to their own memberships
  3. A non-admin user can query `sys.privileges` and receives rows scoped to their own grants
  4. After a user creates a view via Save as View, that user immediately holds SELECT, ALTER, and DROP privileges on the new view without any additional grant step
**Plans**: 2 plans
Plans:
- [x] 25-01-PLAN.md — RBAC-aware dataset count in SpaceResource and auto-grant privileges on view creation
- [x] 25-02-PLAN.md — User-scoped sys.membership and sys.privileges access for non-admin users

### Phase 26: Information Disclosure Fix
**Goal**: Non-admin users cannot use the Jobs page to enumerate all system usernames
**Depends on**: Phase 25
**Requirements**: DISC-01
**Success Criteria** (what must be TRUE):
  1. A non-admin user opening the Jobs page User filter sees only their own username in the dropdown or autocomplete list
  2. An admin user sees all usernames in the Jobs page User filter (no regression)
  3. A non-admin user cannot retrieve other users' job history by manipulating the User filter
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
| 26. Information Disclosure Fix | v1.4 | 0/TBD | Not started | - |
