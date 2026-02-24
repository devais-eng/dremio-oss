# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-24)

**Core value:** Users can only access views, tables, and UDFs they've been explicitly granted access to, with deny-by-default policy, privilege context switching (definer rights for VDS and UDF), and admin bypass.
**Current focus:** Planning next milestone

## Current Position

Milestone: v1.2 Privilege Context & Enforcement — SHIPPED 2026-02-24
Status: All milestones complete. Next milestone TBD.
Last activity: 2026-02-24 — v1.2 milestone archived and tagged

## Shipped Milestones

- v1.0 Naive RBAC — 6 phases, 15 plans (shipped 2026-02-19)
- v1.1 Enable Iceberg REST Catalog — 2 phases, 3 plans (shipped 2026-02-20)
- v1.2 Privilege Context & Enforcement — 9 phases, 17 plans (shipped 2026-02-24)

## Known Limitations / Future Improvements

- **UI search for promoted PDS**: Non-admin users cannot discover promoted PDS via the global UI search bar. Users CAN still see and query granted PDS via the SQL editor left panel.
- **Guard applies to all source types**: File browse/promote guards block non-admin access for ALL source types, including database/catalog sources where there are no files to browse.
- **bulkGetTables() PDS performance**: One listGrantsByObject() call per table in batch — needs profiling at scale.

## Session Continuity

Last session: 2026-02-24
Stopped at: v1.2 milestone completed and archived. Git tag v1.2 pending.
