---
phase: 26-information-disclosure-fix
verified: 2026-03-11T16:00:00Z
status: passed
score: 3/3 must-haves verified
re_verification: false
---

# Phase 26: Information Disclosure Fix — Verification Report

**Phase Goal:** Non-admin users cannot use the Jobs page to enumerate all system usernames
**Verified:** 2026-03-11T16:00:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Non-admin user calling GET /api/v2/jobs/filters/users receives only their own username | VERIFIED | `searchUsers()` in `JobsFiltersResource.java` lines 96-108: callerName extracted from SecurityContext; RBAC guard returns single-item response containing only callerName when RBAC enabled and caller is non-admin |
| 2 | Admin user calling GET /api/v2/jobs/filters/users receives all system usernames | VERIFIED | Guard falls through to `userService.searchUsers(query, null, null, limit)` for admin or RBAC-disabled path (lines 111-114) — original behavior fully preserved |
| 3 | Non-admin user cannot enumerate other users by varying the filter query parameter | VERIFIED | Non-admin path applies `String.contains()` filter only against `callerName` (line 104); any filter value that does not match the caller's own name returns an empty list — confirmed by `testJobsFilterUsers_nonAdmin_filterQueryCannotEnumerateOthers` test |

**Score:** 3/3 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `dac/backend/src/main/java/com/dremio/dac/resource/JobsFiltersResource.java` | RBAC-scoped user search in jobs filter endpoint | VERIFIED | 117-line file; contains `SecurityContext`, `@Nullable RbacService`, `@Nullable DremioConfig` fields; `isAdminMember` guard present at line 102 |
| `dac/backend/src/test/java/com/dremio/dac/server/TestRbacIntegration.java` | Integration tests for jobs filter user enumeration | VERIFIED | Section 13 added at lines 558-619; three test methods present: `testJobsFilterUsers_admin_seesAllUsers`, `testJobsFilterUsers_nonAdmin_seesOnlySelf`, `testJobsFilterUsers_nonAdmin_filterQueryCannotEnumerateOthers` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `JobsFiltersResource.java` | `RbacService.isAdminMember()` | Injected `@Nullable RbacService` + `@Nullable DremioConfig` + `@Context SecurityContext` | WIRED | `rbacService.isAdminMember(callerName)` called at line 102; null-guards on rbacService and dremioConfig match established pattern from `JobsResource`; resource auto-registered by `RestServerV2` via `@RestResource` classpath scan |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| DISC-01 | 26-01-PLAN.md | Jobs page User filter shows only the current user's name for non-admin users, preventing enumeration of all system usernames | SATISFIED | `JobsFiltersResource.searchUsers()` RBAC guard confirmed in source; three integration tests in `TestRbacIntegration` Section 13 cover admin regression, non-admin isolation, and filter-param enumeration prevention; commits `aee9f3ac6` (fix) and `770ad8993` (test) verified in git history |

### Anti-Patterns Found

None detected. No TODO/FIXME/placeholder comments in either modified file. No stub return patterns. No empty handler bodies.

### Human Verification Required

#### 1. Jobs Page UI — User Filter Dropdown Rendering

**Test:** Log in as a non-admin user, navigate to the Jobs page, open the User filter dropdown or autocomplete, and observe the list.
**Expected:** Only the logged-in user's own username appears; no other system usernames are visible.
**Why human:** The fix is backend-only. The PLAN notes the frontend already hides the filter via `canViewUsersListing`, but the combination of frontend gating and the new backend restriction can only be confirmed by exercising the actual UI.

#### 2. Admin Regression — User Filter Shows All Users

**Test:** Log in as an admin, open the Jobs page User filter, and verify all user accounts appear in the dropdown.
**Expected:** All system usernames are listed, identical to behavior before this change.
**Why human:** Integration tests use `BaseTestServer` mock infrastructure. A live instance confirms no regression in the real filter UI.

### Gaps Summary

No gaps. All three observable truths are verified, both artifacts are substantive and wired, DISC-01 is fully satisfied, and no anti-patterns were found. The two human-verification items are regression-confirmation steps, not indicators of failure.

---

_Verified: 2026-03-11T16:00:00Z_
_Verifier: Claude (gsd-verifier)_
