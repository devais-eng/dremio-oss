# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-01)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** Planning next milestone

## Current Position

Milestone: All milestones complete. Next milestone TBD.
Status: All milestones complete (v1.0, v1.1, v1.2, v1.3). No active milestone.
Last activity: 2026-03-02 - Completed quick task 7: Apply all actionable Copilot review items (RbacService O(1) fixes + semicolon injection block).

## Shipped Milestones

- v1.0 Naive RBAC — 6 phases, 15 plans (shipped 2026-02-19)
- v1.1 Enable Iceberg REST Catalog — 2 phases, 3 plans (shipped 2026-02-20)
- v1.2 GitHub Actions Docker Distribution — 3 phases, 3 plans (shipped 2026-02-21)
- v1.3 Privilege Context & Enforcement — 9 phases, 17 plans (shipped 2026-02-24)

## Known Limitations / Future Improvements

- **UI search for promoted PDS**: Non-admin users cannot discover promoted PDS via the global UI search bar. Users CAN still see and query granted PDS via the SQL editor left panel.
- **Guard applies to all source types**: File browse/promote guards block non-admin access for ALL source types, including database/catalog sources where there are no files to browse.
- **bulkGetTables() PDS performance**: One listGrantsByObject() call per table in batch — needs profiling at scale.
- **Credential vending gap (v1.1)**: DremioFileIO discards vended credentials from Iceberg loadTable(); static fs.s3a.* workaround works for long-lived creds but fails for IAM/STS short-lived tokens.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 4 | merge develop and align .planning directory. | 2026-03-01 | da7cb6b2c | [4-merge-develop-and-align-planning-directo](./quick/4-merge-develop-and-align-planning-directo/) |
| 5 | remove .planning from .gitignore | 2026-03-01 | 6ca527087 | [5-remove-planning-from-gitignore](./quick/5-remove-planning-from-gitignore/) |
| 6 | Read the opened pull requests and evaluate the comments of copilot. | 2026-03-02 | — | [6-read-the-opened-pull-requests-and-evalua](./quick/6-read-the-opened-pull-requests-and-evalua/) |
| 7 | Apply all actionable Copilot review items (O(1) roleIds, precomputed-path overload, semicolon injection block). | 2026-03-02 | 246251057 | [7-apply-all-actionable-copilot-review-item](./quick/7-apply-all-actionable-copilot-review-item/) |

## Session Continuity

Last session: 2026-03-02
Stopped at: Quick task 7 complete — Applied 4 Copilot PR review items: RbacService List→Set O(1) roleIds, precomputed-path overload + 4 caller updates, semicolon injection block in validateUsername.
