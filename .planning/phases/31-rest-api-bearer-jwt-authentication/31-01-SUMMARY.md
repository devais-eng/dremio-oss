---
phase: 31-rest-api-bearer-jwt-authentication
plan: "01"
subsystem: auth
tags: [jwt, keycloak, oidc, bearer, rest-api, tdd]

# Dependency graph
requires:
  - phase: 30-jwt-validation-infrastructure-config
    provides: "OidcTokenValidator (RS256 JWT validation, JWKS key rotation, preferred_username extraction), bound in DACDaemonModule"

provides:
  - "DACAuthFilter with eyJ-discriminated JWT dispatch: Keycloak JWT -> OidcTokenValidator, fallback to TokenManager"
  - "6 unit tests (TestDACAuthFilterKeycloak) covering TKN-02, COEX-01, COEX-02"

affects:
  - 32-role-mapping
  - 35-arrow-flight-bearer-jwt

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "eyJ prefix discrimination: use tokenStr.startsWith('eyJ') to distinguish compact JWTs from opaque tokens in auth filters"
    - "OIDC-first with TokenManager fallback: try OidcTokenValidator.validate(), catch ParseException|IllegalArgumentException, fall back to TokenManager.validateToken() for Dremio-issued JWTs"
    - "Nullable injection guard: @Inject @Nullable OidcTokenValidator - null means auth.type=internal, non-null means Keycloak mode"

key-files:
  created:
    - dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterKeycloak.java
  modified:
    - dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java

key-decisions:
  - "Fallback from OidcTokenValidator to TokenManager on ParseException|IllegalArgumentException: Dremio's TokenManager.createJwt() produces ES256 JWTs that also start with eyJ; Keycloak validator rejects them (wrong issuer/algorithm) so fallback is required for COEX-02"
  - "ParseException caught inside getUserNameFromToken() not in filter(): filter() only catches UserNotFoundException|NotAuthorizedException; ParseException is checked and would become 500 if it escaped"
  - "UriInfo mock required in tests: getUserNameFromToken() always reads getUriInfo() before branching even in non-TemporaryAccess path"

patterns-established:
  - "TDD with reflection-based field injection: DACAuthFilter uses @Inject field injection (not constructor injection), so tests inject mocked dependencies via java.lang.reflect.Field.setAccessible(true)"
  - "Bearer header mock pattern: when(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION.toLowerCase())).thenReturn('Bearer ' + token) for testing TokenUtils.getAuthHeaderToken()"

requirements-completed: [TKN-02, COEX-01, COEX-02]

# Metrics
duration: 8min
completed: 2026-03-12
---

# Phase 31 Plan 01: REST API Bearer JWT Authentication Summary

**eyJ-discriminated Keycloak JWT dispatch in DACAuthFilter: OidcTokenValidator-first with TokenManager fallback, 6 unit tests covering all auth paths**

## Performance

- **Duration:** 8 min
- **Started:** 2026-03-12T15:34:55Z
- **Completed:** 2026-03-12T16:43:00Z
- **Tasks:** 2 (TDD: RED + GREEN)
- **Files modified:** 2

## Accomplishments

- `DACAuthFilter.getUserNameFromToken()` now dispatches Keycloak JWTs (eyJ prefix + oidcTokenValidator non-null) to `OidcTokenValidator.validate()`, falling back to `TokenManager.validateToken()` for Dremio-issued JWTs that start with eyJ
- Opaque Dremio tokens (no eyJ prefix) and auth.type=internal mode (oidcTokenValidator=null) route exclusively to TokenManager — zero regression on existing flows
- ParseException from Nimbus JWT parsing is caught inside getUserNameFromToken() and converted to NotAuthorizedException (HTTP 401), never to 500

## Task Commits

Each task was committed atomically:

1. **Task 1: RED -- Write failing unit tests for DACAuthFilter Keycloak JWT dispatch** - `101db067e` (test)
2. **Task 2: GREEN -- Add eyJ-discriminated Keycloak JWT dispatch to DACAuthFilter** - `608f1d992` (feat)

**Plan metadata:** *(to be committed with SUMMARY.md)*

_Note: TDD tasks have separate RED and GREEN commits. Test file was reformatted by Spotless after GREEN task._

## Files Created/Modified

- `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` - Added `OidcTokenValidator` import, `JWT_COMPACT_PREFIX` constant, `@Inject @Nullable OidcTokenValidator oidcTokenValidator` field, and eyJ-discriminated dispatch block in the normal-resource branch of `getUserNameFromToken()`
- `dac/backend/src/test/java/com/dremio/dac/server/TestDACAuthFilterKeycloak.java` - Created: 6 unit tests using MockitoExtension + reflection-based field injection covering all Keycloak auth paths

## Decisions Made

- **Fallback not immediate 401 on OidcTokenValidator failure:** Dremio's `TokenManager.createJwt()` produces ES256 JWTs that start with `eyJ`. When Keycloak mode is active, these must still work. The Keycloak validator rejects them (wrong issuer/algorithm), so the fallback to `TokenManager.validateToken()` handles them correctly (COEX-02).
- **ParseException caught in getUserNameFromToken():** The existing `filter()` method only catches `UserNotFoundException | NotAuthorizedException`. `ParseException` is checked and would produce a 500 if it escaped `getUserNameFromToken()`. Catching it here and mapping to `NotAuthorizedException` (via the fallback path) guarantees 401.
- **UriInfo mock required in setUp():** `getUserNameFromToken()` always reads `requestContext.getUriInfo().getRequestUri().getPath()` before branching on TemporaryAccess. This must be mocked in all test cases.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Added UriInfo mock to test setUp()**
- **Found during:** Task 2 (GREEN — first test run)
- **Issue:** Tests failed with NullPointerException at `DACAuthFilter.java:95` — `requestContext.getUriInfo()` returned null because Mockito returns null for unstubbed methods, but `getUserNameFromToken()` calls `getUriInfo()` before branching on TemporaryAccess annotation, so all test paths hit it
- **Fix:** Added `@Mock UriInfo uriInfo` field and `when(requestContext.getUriInfo()).thenReturn(uriInfo)` + stubbed `getRequestUri()` and `getQueryParameters()` in `@BeforeEach setUp()`
- **Files modified:** `TestDACAuthFilterKeycloak.java`
- **Verification:** All 6 tests pass after fix
- **Committed in:** `608f1d992` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Required for test correctness. The production code behavior was correct; only the test setup was incomplete.

## Issues Encountered

- `mvn test-compile -pl dac/backend -am` failed due to an unrelated proto compilation issue in `dremio-services-accelerator` (imported proto not found). Resolved by running `mvn test-compile -pl dac/backend` without `-am` — the keycloak and dac/backend modules were already built and available in the local Maven repository.

## User Setup Required

None - no external service configuration required. This plan adds server-side auth filter dispatch only; integration with a running Keycloak instance is tested via TestOidcTokenValidator (Phase 30).

## Next Phase Readiness

- Phase 31 plan 01 complete: REST API now accepts Keycloak Bearer JWTs via `DACAuthFilter`
- Phase 32 (role mapping): `DACAuthFilter` now extracts the Keycloak `preferred_username`; the role mapping phase can rely on this username being present in the security context
- Phase 35 (Arrow Flight): same eyJ-discrimination pattern applies to Flight token validation — `OidcTokenValidator` is already injectable

---
*Phase: 31-rest-api-bearer-jwt-authentication*
*Completed: 2026-03-12*
