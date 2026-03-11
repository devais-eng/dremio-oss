# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1.4 — RBAC Issue Hardening

**Shipped:** 2026-03-11
**Phases:** 9 | **Plans:** 14

### What Was Built
- Admin-only User API enforcement via @RolesAllowed + fixed DACSecurityContext.isUserInRole()
- RBAC privilege enforcement across all mutation APIs (Catalog, Collaboration, Scripts, Folders, Reflections)
- RBAC-aware UI permission gates driven by corrected login response (Settings, Sources, Spaces, dataset/space context menus)
- RBAC-aware dataset counts (v2 SpaceResource + v3 CatalogServiceHelper), auto-grant on view creation, user-scoped sys tables
- TOCTOU vulnerability fix: ALTER check before rename mutation in Catalog API
- Information disclosure fix: Jobs user filter scoped to caller only

### What Worked
- UAT-driven issue identification: the 17 issues were discovered through systematic user acceptance testing, giving clear repro steps and fix targets
- Three-wave gap closure pattern: initial implementation (Phases 21-26), then UAT retest revealed deeper issues (Phases 27-29), each closing specific verified gaps
- Consistent RBAC guard pattern (three-way null guard + admin bypass + privilege check) reused across all new enforcement points
- Defense-in-depth approach: programmatic rbacService guards (Phase 22) + @RolesAllowed annotations (Phase 28) provide two independent enforcement layers

### What Was Inefficient
- DACSecurityContext.isUserInRole() was not discovered as always-returning-true until Phase 28 UAT retest — earlier investigation of the JAX-RS security chain would have caught this sooner
- v3 catalog API dataset count path (Phase 29) was missed in Phase 25 — the UI uses the v3 path, not the v2 SpaceResource. Better code path analysis during planning would have avoided the gap closure phase
- SysFlightScanCreator vs SystemTableScanCreator distinction not recognized until Phase 29 — production uses SysFlight plugin, not the legacy system table path

### Patterns Established
- RBAC guard pattern: `if (rbacService == null || dremioConfig == null || !dremioConfig.getBoolean(RBAC_ENABLED)) return;` + `if (rbacService.isAdminMember(userName)) return;` + privilege check
- Login response as single source of truth for UI permission gates: backend computes admin/permissions, frontend reads from localStorage
- TOCTOU prevention: always validate privilege BEFORE mutation, using current entity state (not requested state)
- Per-entity permission gating with admin-fallback: `isAdmin || entityPermissions?.get("canAlter")` for graceful degradation

### Key Lessons
1. JAX-RS @RolesAllowed annotations are only as good as the SecurityContext.isUserInRole() implementation — always verify the full enforcement chain end-to-end
2. When fixing RBAC, trace all code paths that serve the same UI feature (e.g., v2 and v3 API for dataset counts) — missing one path creates a gap
3. UAT retest after gap closure phases is essential — each fix wave can reveal new issues in the enforcement chain
4. TOCTOU vulnerabilities in privilege checks are subtle — the fix is always: validate before mutate, using current state

### Cost Observations
- Model mix: ~70% sonnet (execution), ~30% opus (planning, verification, audit)
- Sessions: 1 session (full milestone in single day)
- Notable: 9 phases executed in rapid succession with UAT-driven gap closure

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Phases | Plans | Key Change |
|-----------|--------|-------|------------|
| v1.0 | 6 | 15 | Initial RBAC foundation |
| v1.1 | 2 | 3 | Feature enablement (small scope) |
| v1.2 | 3 | 3 | CI/CD pipeline (ops scope) |
| v1.3 | 9 | 17 | Deep enforcement (privilege context) |
| v1.4 | 9 | 14 | UAT-driven hardening (issue-based) |

### Top Lessons (Verified Across Milestones)

1. Defense-in-depth is essential: @RolesAllowed + programmatic guards catch different failure modes (v1.4 Phase 28 proved this)
2. UAT after each wave reveals enforcement gaps that static code review misses (v1.0 audit, v1.3 audit, v1.4 three-wave gap closure)
3. Consistent guard patterns across all enforcement points reduce bugs and speed review (three-way null guard established in v1.0, reused through v1.4)
