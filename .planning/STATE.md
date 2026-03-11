---
gsd_state_version: 1.0
milestone: v1.4
milestone_name: RBAC Issue Hardening
status: completed
stopped_at: Completed 29-01-PLAN.md
last_updated: "2026-03-11T20:35:19.429Z"
last_activity: "2026-03-11 — Phase 28 Plan 01: Fix DACSecurityContext.isUserInRole() with RBAC admin check (API-01 gap closure)"
progress:
  total_phases: 9
  completed_phases: 8
  total_plans: 14
  completed_plans: 13
  percent: 100
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-11)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.5 RBAC Gap Closure — Phase 28 complete

## Current Position

Milestone: v1.5 RBAC Gap Closure (Phase 28+)
Phase: 28 of 28 (DACSecurityContext Role Enforcement) -- COMPLETE
Plan: 1 of 1 complete
Status: Phase 28 plan 01 complete — @RolesAllowed("admin") enforcement now active
Last activity: 2026-03-11 — Phase 28 Plan 01: Fix DACSecurityContext.isUserInRole() with RBAC admin check (API-01 gap closure)

Progress: [██████████] 100%

## Shipped Milestones

- v1.0 Naive RBAC — 6 phases, 15 plans (shipped 2026-02-19)
- v1.1 Enable Iceberg REST Catalog — 2 phases, 3 plans (shipped 2026-02-20)
- v1.2 GitHub Actions Docker Distribution — 3 phases, 3 plans (shipped 2026-02-21)
- v1.3 Privilege Context & Enforcement — 9 phases, 17 plans (shipped 2026-02-24)

## Known Limitations / Future Improvements

- **UI search for promoted PDS**: Non-admin users cannot discover promoted PDS via the global UI search bar.
- **Guard applies to all source types**: File browse/promote guards block non-admin access for ALL source types, including database/catalog sources.
- **bulkGetTables() PDS performance**: One listGrantsByObject() call per table in batch — needs profiling at scale.
- **Credential vending gap (v1.1)**: DremioFileIO discards vended credentials from Iceberg loadTable(); static fs.s3a.* workaround works for long-lived creds but fails for IAM/STS short-lived tokens.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 4 | merge develop and align .planning directory. | 2026-03-01 | da7cb6b2c | [4-merge-develop-and-align-planning-directo](./quick/4-merge-develop-and-align-planning-directo/) |
| 5 | remove .planning from .gitignore | 2026-03-01 | 6ca527087 | [5-remove-planning-from-gitignore](./quick/5-remove-planning-from-gitignore/) |
| 6 | Read the opened pull requests and evaluate the comments of copilot. | 2026-03-02 | — | [6-read-the-opened-pull-requests-and-evalua](./quick/6-read-the-opened-pull-requests-and-evalua/) |
| 7 | Apply all actionable Copilot review items (O(1) roleIds, precomputed-path overload, semicolon injection block). | 2026-03-02 | 246251057 | [7-apply-all-actionable-copilot-review-item](./quick/7-apply-all-actionable-copilot-review-item/) |
| 8 | Enable RBAC and PDS SELECT enforcement by default (dremio-reference.conf). | 2026-03-02 | afb403227 | [8-enable-rbac-by-default](./quick/8-enable-rbac-by-default/) |

## Decisions

- (25-01) Compute dataset count from RBAC-filtered children list instead of namespaceService.getDatasetCount() for non-admin users
- (25-01) Always fetch children list in getSpace() regardless of includeContents flag since RBAC filtering requires it
- (25-01) Grant view creator privileges to ALL explicit roles with PUBLIC fallback
- (25-01) Added listMembershipsByUser() to RbacService for efficient user role lookup
- (25-02) Cast AccessControlListingManager to RbacService via instanceof instead of adding getRbacService() to PluginSabotContext interface
- (25-02) Filter sys.membership by member_name equals query user; filter sys.privileges by grantee in user's roleIds set
- (25-02) Only sys.roles remains admin-only; sys.privileges and sys.membership open to all users with row scoping
- [Phase 26-01]: Non-admin users receive only their own username from /api/v2/jobs/filters/users regardless of filter query param
- [Phase 26-01]: Admin or RBAC-disabled path in JobsFiltersResource preserves original userService.searchUsers() behavior exactly
- [Phase 27-01]: Validate ALTER privilege against current dataset path (currentDatasetConfig.getFullPathList()) not requested path to prevent privilege escalation via path manipulation
- [Phase 27-01]: Single ALTER check at start of VDS branch — removed duplicate late-positioned check to fix TOCTOU vulnerability in updateNonVersionedDataset()
- [Phase 28-01]: user.getName() (UserUI implements Principal) instead of user.getUserPrincipal().getName() — UserUI has no getUserPrincipal() method
- [Phase 28-01]: Only "admin" role delegates to rbacService.isAdminMember(); all other roles including "user" return true unconditionally to preserve @RolesAllowed({"admin","user"}) GET endpoint behavior
- [Phase 28-01]: 3-arg DACSecurityContext constructor preserved for backward compatibility; all non-auth-filter call sites (TestResource, SampleDataPopulatorService, TestMultiMaster, TestMasterDown, TestCollaborationHelper) unchanged
- [Phase 29-01]: Use namespaceService.list() + filterByVisibility() in DetailType.datasetCount instead of getDatasetCount() — reuses existing RBAC filter, ensures count matches visible children
- [Phase 29-01]: Make SysFlightScanCreator filter methods package-private for direct unit testing without full scan operator infrastructure

## Session Continuity

Last session: 2026-03-11T20:35:19.426Z
Stopped at: Completed 29-01-PLAN.md
