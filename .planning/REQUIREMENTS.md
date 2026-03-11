# Requirements: Dremio OSS Enhancements

**Defined:** 2026-03-11
**Core Value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.

## v1.4 Requirements

Requirements for RBAC Issue Hardening. Each maps to roadmap phases.

### UI Permission Gates

- [ ] **UI-01**: Non-admin users cannot access the Settings > Users page or see Add User / Delete User controls (Issue #1)
- [ ] **UI-02**: Non-admin users cannot see the Add Source button unless they have `canCreateSource` permission (Issue #2)
- [ ] **UI-03**: Non-admin users cannot see the Add Space button in the sidebar unless they have space creation permission (Issue #3)
- [ ] **UI-04**: Non-admin users cannot see Delete, Rename, Move, Edit, or Settings context menu items on datasets they lack privileges for (Issues #6, #10)
- [ ] **UI-05**: Non-admin users cannot access admin-only Settings sub-pages (Node Activity, Engines, Queue Control, Users) (Issue #9)
- [ ] **UI-06**: Non-admin users cannot access the space settings gear icon without space management permissions (Issue #11)

### Backend API Security

- [x] **API-01**: The v3 User API `createUser()` and `updateUser()` methods require admin role (Issue #12)
- [x] **API-02**: The v3 Catalog API `createCatalogItem()`, `updateCatalogItem()`, `deleteCatalogItem()`, `promoteToDataset()`, and `refreshCatalogItem()` enforce RBAC privileges (Issue #13)
- [x] **API-03**: The Collaboration API `setTagsForEntity()` and `setWikiForEntity()` require ALTER privilege on the target entity (Issue #14)
- [x] **API-04**: The Scripts API `getScripts()` restricts the `createdBy` parameter to the current user for non-admin users (Issue #15)
- [ ] **API-05**: The `SpaceFolderResource` `createFolder()` and `deleteFolder()` methods verify ALTER/CREATE privilege on the parent space (Issue #16)
- [ ] **API-06**: The `ReflectionResource` `createReflection()`, `editReflection()`, and `deleteReflection()` methods verify ALTER privilege on the underlying dataset (Issue #17)

### Backend Logic

- [ ] **LOGIC-01**: Dataset count shown next to space names reflects only RBAC-visible datasets, not all datasets (Issue #4)
- [ ] **LOGIC-02**: `sys.membership` and `sys.privileges` system tables are queryable by non-admin users (showing filtered or full data per policy) (Issue #5)
- [ ] **LOGIC-03**: After creating a view via Save as View, the creator is automatically granted SELECT, ALTER, and DROP privileges on the new view (Issue #7)

### Information Disclosure

- [ ] **DISC-01**: The Jobs page User filter shows only the current user's name for non-admin users, preventing enumeration of all system usernames (Issue #8)

## Future Requirements

Deferred to future release. Tracked but not in current roadmap.

### RBAC Enhancements

- **RBAC-01**: WITH GRANT OPTION for delegated privilege management
- **RBAC-02**: REVOKE CASCADE for cascading privilege removal
- **RBAC-03**: Container-level explicit grants (source/space grants)
- **RBAC-04**: INFORMATION_SCHEMA filtering by privilege
- **RBAC-05**: Audit logging for RBAC operations
- **RBAC-06**: Privilege caching for performance at scale

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Ownership model (object creator = owner) | Would require new privilege type and data model changes; auto-grant in LOGIC-03 is sufficient for v1.4 |
| Per-user effective privileges API endpoint | Nice-to-have for UI but adds complexity; optimistic UI approach (show, handle 403) is acceptable for now |
| Row-level security | Complexity explosion, not needed for naive RBAC |
| Column-level security | Views already serve as column projection |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| UI-01 | Phase 23 | Pending |
| UI-02 | Phase 23 | Pending |
| UI-03 | Phase 23 | Pending |
| UI-04 | Phase 24 | Pending |
| UI-05 | Phase 23 | Pending |
| UI-06 | Phase 24 | Pending |
| API-01 | Phase 21 | Complete |
| API-02 | Phase 21 | Complete |
| API-03 | Phase 22 | Complete |
| API-04 | Phase 22 | Complete |
| API-05 | Phase 22 | Pending |
| API-06 | Phase 22 | Pending |
| LOGIC-01 | Phase 25 | Pending |
| LOGIC-02 | Phase 25 | Pending |
| LOGIC-03 | Phase 25 | Pending |
| DISC-01 | Phase 26 | Pending |

**Coverage:**
- v1.4 requirements: 16 total
- Mapped to phases: 16
- Unmapped: 0

---
*Requirements defined: 2026-03-11*
*Last updated: 2026-03-11 — traceability filled after roadmap creation*
