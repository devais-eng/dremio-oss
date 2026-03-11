---
gsd_state_version: 1.0
milestone: v1.4
milestone_name: RBAC Issue Hardening
status: completed
stopped_at: Completed 24-ui-dataset-and-space-context-gates-02-PLAN.md
last_updated: "2026-03-11T14:37:14.972Z"
last_activity: "2026-03-11 — Phase 24 Plan 02: Space settings gear and Delete admin gates"
progress:
  total_phases: 6
  completed_phases: 4
  total_plans: 7
  completed_plans: 7
  percent: 100
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-11)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.4 RBAC Issue Hardening — Phase 24 complete (UI dataset/space context gates)

## Current Position

Milestone: v1.4 RBAC Issue Hardening
Phase: 24 of 26 (UI Dataset and Space Context Gates)
Plan: 02 of 02 complete
Status: Phase 24 complete
Last activity: 2026-03-11 — Phase 24 Plan 02: Space settings gear and Delete admin gates

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

## Session Continuity

Last session: 2026-03-11T14:37:14.969Z
Stopped at: Completed 24-ui-dataset-and-space-context-gates-02-PLAN.md
