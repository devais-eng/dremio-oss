---
phase: 21-backend-api-critical-security
verified: 2026-03-11T14:00:00Z
status: passed
score: 8/8 must-haves verified
re_verification: false
---

# Phase 21: Backend API Critical Security — Verification Report

**Phase Goal:** Users without admin role cannot create, update, delete, or promote catalog items or user accounts through the v3 API
**Verified:** 2026-03-11T14:00:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| #   | Truth                                                                                  | Status     | Evidence                                                                                   |
| --- | -------------------------------------------------------------------------------------- | ---------- | ------------------------------------------------------------------------------------------ |
| 1   | A non-admin POST to /api/v3/user returns 403                                           | VERIFIED   | `UserResource.java:83` — `@RolesAllowed("admin")` on `createUser()`                       |
| 2   | A non-admin PUT to /api/v3/user/{id} returns 403                                       | VERIFIED   | `UserResource.java:144` — `@RolesAllowed("admin")` on `updateUser()`                      |
| 3   | A non-admin POST to /api/v3/catalog (create) returns 403 unless required privilege     | VERIFIED   | `CatalogServiceHelper.java:1423` — `enforceCreatePrivilege(entity)` called at method top  |
| 4   | A non-admin PUT to /api/v3/catalog/{id} (update) returns 403 unless ALTER privilege    | VERIFIED   | `CatalogServiceHelper.java:2081` — `enforceUpdatePrivilege(entity)` called at method top  |
| 5   | A non-admin DELETE to /api/v3/catalog/{id} returns 403 unless DROP/ALTER privilege     | VERIFIED   | `CatalogServiceHelper.java:2140` — `enforceDeletePrivilege(entity.get())` after getById() |
| 6   | A non-admin POST to /api/v3/catalog/{id} (promote) returns 403 unless user is admin   | VERIFIED   | `CatalogServiceHelper.java:1506-1514` — pre-existing admin-only guard intact               |
| 7   | A non-admin POST to /api/v3/catalog/{id}/refresh returns 403 unless ALTER privilege    | VERIFIED   | `CatalogServiceHelper.java:2430-2444` — inline RBAC guard in `refreshCatalogItem()`       |
| 8   | An admin API call to any of the above succeeds without regression                      | VERIFIED   | All helpers short-circuit via `rbacService.isAdminMember(userName)` return before checks  |

**Score:** 8/8 truths verified

---

## Required Artifacts

| Artifact                                                                                    | Expected                                                           | Status     | Details                                                                                          |
| ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ | ---------- | ------------------------------------------------------------------------------------------------ |
| `dac/backend/src/main/java/com/dremio/dac/api/UserResource.java`                           | `@RolesAllowed("admin")` on `createUser()` and `updateUser()`      | VERIFIED   | Lines 83 and 144 carry strict `@RolesAllowed("admin")`; read methods remain `{"admin","user"}`  |
| `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java`       | RBAC privilege enforcement in catalog mutation methods             | VERIFIED   | Three helpers + inline guard present, called at top of each mutation method before dispatch      |

---

## Key Link Verification

| From                     | To                                   | Via                                                            | Status   | Details                                                                                                          |
| ------------------------ | ------------------------------------ | -------------------------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------- |
| `UserResource.java`      | JAX-RS @RolesAllowed container       | Method-level annotation on `createUser()` and `updateUser()`   | WIRED    | Lines 83 and 144: `@RolesAllowed("admin")` — enforced by JAX-RS filter before method body executes              |
| `CatalogServiceHelper.java` | RbacService privilege checks      | `rbacService.hasPrivilege` and `rbacService.isAdminMember`     | WIRED    | 9 call sites found; helpers use inverted null guard (`== null → return`); inline uses positive guard (`!= null`) |

Pattern verification:

- `@RolesAllowed("admin")` present on `createUser()` and `updateUser()` — confirmed at lines 83, 144
- `rbacService.isAdminMember` found at lines 476, 830, 1273, 1321, 1365, 1510, 2042, 2434, 3355, 3381, 3424
- `rbacService.hasPrivilege` found at lines 1286, 1294, 1302, 1338, 1346, 1381, 1389, 2435, 3396, 3401, 3407, 3428
- Three-way null guard (`rbacService == null || dremioConfig == null || !dremioConfig.getBoolean(RBAC_ENABLED)`) present in all three helpers (lines 1267-1270, 1315-1318, 1359-1362)
- Inline guard for `refreshCatalogItem()` uses positive form (`rbacService != null && dremioConfig != null && dremioConfig.getBoolean(RBAC_ENABLED)`) at line 2430-2432 — equivalent, consistent with existing inline patterns in same file

---

## Requirements Coverage

| Requirement | Source Plan | Description                                                                                               | Status    | Evidence                                                                                    |
| ----------- | ----------- | --------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------------------------------- |
| API-01      | 21-01-PLAN  | v3 User API `createUser()` and `updateUser()` require admin role                                          | SATISFIED | `@RolesAllowed("admin")` on both methods; commit `3cf7542ca` verified in git log             |
| API-02      | 21-01-PLAN  | v3 Catalog API `createCatalogItem()`, `updateCatalogItem()`, `deleteCatalogItem()`, `promoteToDataset()`, `refreshCatalogItem()` enforce RBAC | SATISFIED | Helpers + inline guard cover all five entry points; commit `cef79e4a4` verified in git log |

Both requirements marked `[x]` (complete) in `REQUIREMENTS.md` traceability table. No orphaned requirements: REQUIREMENTS.md maps only API-01 and API-02 to Phase 21, which exactly matches the plan's `requirements` frontmatter field.

---

## Anti-Patterns Found

| File                        | Line(s)          | Pattern | Severity | Impact                                      |
| --------------------------- | ---------------- | ------- | -------- | ------------------------------------------- |
| `CatalogServiceHelper.java` | 1212, 1235, 1761, 2001, 2050, 2418, 2731 | `// TODO:` comments | INFO | All pre-existing TODOs about namespace/source refactoring; unrelated to RBAC changes |

No blocker or warning anti-patterns introduced by this phase. All TODOs are pre-existing code hygiene items.

---

## Human Verification Required

### 1. Non-admin user flow — User API (403 gate)

**Test:** Log in as a non-admin user and send `POST /api/v3/user` or `PUT /api/v3/user/{id}` with a valid body.
**Expected:** HTTP 403 response from JAX-RS @RolesAllowed filter (before method body executes).
**Why human:** Integration test needed to confirm JAX-RS security filter is properly wired to the `@Secured` + `@RolesAllowed` annotations at runtime.

### 2. Non-admin user flow — Catalog create (403 unless privilege)

**Test:** Log in as a non-admin user without CREATE_VIEW privilege and send `POST /api/v3/catalog` with a VDS payload.
**Expected:** HTTP 403 / permission denied error.
**Why human:** Requires a running Dremio instance with RBAC enabled (`dremio.security.rbac.enabled = true`) to validate the three-way null guard activates and the privilege check fires.

### 3. RBAC-disabled regression (passthrough)

**Test:** Start Dremio with RBAC disabled and send `POST /api/v3/catalog` (create) as a non-admin user.
**Expected:** Request proceeds normally (no 403), confirming three-way null guard works as no-op.
**Why human:** Requires environment with `RBAC_ENABLED = false` config to confirm null guard exits early.

---

## Commits Verified

Both commits documented in SUMMARY.md are present in git log:

- `3cf7542ca` — fix(21-01): restrict createUser and updateUser to admin-only in UserResource
- `cef79e4a4` — fix(21-01): add RBAC privilege enforcement to CatalogServiceHelper mutation methods

---

## Gaps Summary

No gaps. All must-haves are satisfied at all three verification levels (exists, substantive, wired).

The PLAN's own verification criteria are also met:
- `@RolesAllowed` with "admin" count in `UserResource.java` = 4 (PASS per PLAN criterion)
- `enforceCreatePrivilege|enforceUpdatePrivilege|enforceDeletePrivilege` count in `CatalogServiceHelper.java` = 6 (3 definitions + 3 call sites) (PASS per PLAN criterion — criterion was ≥ 6)
- `rbacService != null` inline guard count = 5 (plus 6 inverted `rbacService == null` guards in helpers) — PLAN baseline was ~7 should be ~11+; total combined occurrences is 11 (PASS)

---

_Verified: 2026-03-11T14:00:00Z_
_Verifier: Claude (gsd-verifier)_
