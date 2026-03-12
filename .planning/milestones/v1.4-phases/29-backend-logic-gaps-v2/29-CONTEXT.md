# Phase 29: Backend Logic Gaps v2 - Context

**Gathered:** 2026-03-11
**Status:** Ready for planning
**Source:** Prior decisions (Phase 25) + codebase scouting

<domain>
## Phase Boundary

Fix three remaining backend logic gaps identified during UAT retest:
1. v3 catalog API dataset count ignores RBAC (sidebar shows unfiltered counts)
2. sys.membership and sys.privileges not queryable by non-admin (table resolution fails before row-filter is reached)
3. CREATE_VIEW privilege not resolved due to objectType mismatch in KV store key

</domain>

<decisions>
## Implementation Decisions

### Dataset count (LOGIC-01)
- Reuse Phase 25's approach: compute count from RBAC-filtered children list instead of raw `namespaceService.getDatasetCount()`
- Apply to the v3 `datasetCount` detail type in `CatalogServiceHelper.applyAdditionalInfoToContainers()`
- Phase 25 already established this pattern in the v2 `getSpace()` path

### Sys table access (LOGIC-02)
- Policy unchanged from Phase 25: `sys.roles` admin-only; `sys.privileges` and `sys.membership` open to all with row scoping
- Row-filtering logic in `SystemTableScanCreator.filterRbacSystemTableByUser()` is correct — fix is in table resolution, not access policy
- Filter rules: `sys.membership` by `member_name = query user`; `sys.privileges` by `grantee in user's roleIds set`

### CREATE_VIEW grant semantics (LOGIC-03)
- Root cause: `CatalogImpl.validateCreateViewPrivilege()` uses `objectType="VDS"` but grants stored via REST API use `objectType="SPACE"` — different KV keys
- Secondary mismatch: `CatalogImpl` uses parent folder path (`myspace.myfolder`) while `CatalogServiceHelper` uses top-level space name (`myspace`)
- Auto-grant logic (SELECT/ALTER/DROP on created view) uses `objectType="VDS"` and works correctly — only CREATE_VIEW resolution is broken

### Claude's Discretion
- Choice of canonical objectType for CREATE_VIEW (standardize to whichever is most consistent with existing grant patterns)
- How to fix sys table resolution for non-admin (plugin-layer vs catalog-layer vs planner-layer fix)
- Whether to add an RBAC-aware count method to namespaceService or inline the filtered count

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `CatalogServiceHelper.filterByVisibility()` (line 1146-1154): Already filters children by RBAC grants — can be reused for dataset count
- `SystemTableScanCreator.filterRbacSystemTableByUser()` (lines 51-101): Row-level filter already implemented, just needs table resolution fixed
- `CreateOrUpdateViewHandler.autoGrantCreatorPrivileges()` (lines 550-606): Auto-grant logic works — only the prerequisite CREATE_VIEW check is broken

### Established Patterns
- `@Nullable RbacService` injection: Used in 10+ resources, always with null/disabled/enabled three-way guard
- `DremioConfig.RBAC_ENABLED` feature flag: All RBAC guards check this before enforcing
- Grant key format: `roleId|objectType|objectPath|privilege` in `RbacConfig.grantKey()`

### Integration Points
- `CatalogServiceHelper` lines 221-244: `datasetCount` detail type enum — where RBAC-aware count must be added
- `CatalogImpl` line 2885: `validateCreateViewPrivilege()` — must match the objectType used by grant storage
- `SystemStoragePlugin` / `SysFlightStoragePlugin`: Table registration chain — where non-admin resolution breaks

### Root Cause Files
| Gap | File | Line | Issue |
|-----|------|------|-------|
| LOGIC-01 | CatalogServiceHelper.java | 221-244 | `namespaceService.getDatasetCount()` ignores RBAC |
| LOGIC-02 | SystemStoragePlugin.java | 84-86, 144-146 | Tables listed, `hasAccessPermission=true`, but resolution fails for non-admin |
| LOGIC-03 | CatalogImpl.java | 2885 | `objectType="VDS"` vs stored `"SPACE"` — key mismatch |

</code_context>

<specifics>
## Specific Ideas

No specific requirements — prior decisions from Phase 25 and clear root causes from scouting guide implementation.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 29-backend-logic-gaps-v2*
*Context gathered: 2026-03-11*
