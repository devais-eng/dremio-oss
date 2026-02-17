---
phase: 03-service-layer
plan: 01
subsystem: auth
tags: [rbac, privilege-resolution, access-control, dremio-service, kvstore]

# Dependency graph
requires:
  - phase: 02-persistence-layer
    provides: "RoleStore, GrantStore, MembershipStore with full CRUD, RbacConfig key utilities, RbacEntityNotFoundException, RbacEntityAlreadyExistsException"
  - phase: 01-design-and-proto-schema
    provides: "Proto-generated Role/Grant/Membership classes, RbacConfig constants, AccessControlListingManager interface, SysTable POJO classes"
provides:
  - "RbacService: core RBAC business logic implementing AccessControlListingManager"
  - "hasPrivilege(): boolean privilege resolution with ADMIN short-circuit, explicit roles + PUBLIC collection, OR-logic grant checking"
  - "createRole/deleteRole: role lifecycle with immutability guards for ADMIN/PUBLIC built-in roles"
  - "addMembership/removeMembership: membership lifecycle with guards (PUBLIC add rejected, ADMIN/PUBLIC remove rejected)"
  - "grantPrivilege/revokePrivilege: grant lifecycle with role existence validation (skip for built-ins)"
  - "assignBootstrapAdmin(): ADMIN membership creation for bootstrap user"
  - "validateAdminMembersExist(): fail-fast startup validation throwing IllegalStateException"
  - "getRoleInfo/getPrivilegeInfo/getMembershipInfo: AccessControlListingManager proto-to-POJO conversion"
  - "ADMIN_ROLE_ID and PUBLIC_ROLE_ID: public static final String constants for synthetic built-in roles"
affects:
  - 03-02-unit-tests
  - 04-di-wiring
  - 05-ddl-handlers
  - 06-catalog-integration

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ADMIN-first short-circuit in hasPrivilege(): check ADMIN membership before collecting roles"
    - "Single-pass role collection: explicit roles + PUBLIC in one list, not a fallback pattern"
    - "Immutability guards via Preconditions.checkArgument for built-in role operations"
    - "Role existence validation before grant/membership operations (skip for synthetic built-ins)"
    - "Proto-to-POJO conversion for AccessControlListingManager: synthetic ADMIN/PUBLIC as SYSTEM type, user roles as USER type"

key-files:
  created:
    - sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
  modified: []

key-decisions:
  - "Constructor takes RoleStore, GrantStore, MembershipStore with no DI annotations -- Phase 4 handles wiring"
  - "isAdminMember() is private (not package-private) -- only used internally by hasPrivilege()"
  - "Preconditions.checkNotNull on constructor parameters for fail-fast on null stores"
  - "assignBootstrapAdmin() uses SYSTEM as grantedBy -- distinguishes bootstrap from user-initiated membership"
  - "getRoleInfo() returns built-in roles with role_type=SYSTEM, user-created with role_type=USER"
  - "getMembershipInfo() returns only explicit memberships -- PUBLIC implicit membership excluded per locked decision"

patterns-established:
  - "Service-implements-interface pattern: RbacService directly implements AccessControlListingManager (no adapter)"
  - "Synthetic built-in role pattern: ADMIN/PUBLIC are string constants, never stored in KV, existence-checked by identity comparison"
  - "Guard-then-delegate pattern: validate inputs and immutability constraints, then delegate to store methods"

requirements-completed:
  - ROLE-05
  - ROLE-06
  - BOOT-01
  - ENFC-04
  - ENFC-05

# Metrics
duration: 2min
completed: 2026-02-17
---

# Phase 3 Plan 01: RbacService with Privilege Resolution, Lifecycle Management, and System Table Listing Summary

**RbacService implementing AccessControlListingManager with ADMIN-first privilege resolution, built-in role immutability guards, bootstrap ADMIN assignment, fail-fast validation, and proto-to-POJO system table conversion**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-17T16:57:08Z
- **Completed:** 2026-02-17T16:59:35Z
- **Tasks:** 1
- **Files modified:** 1 (1 created)

## Accomplishments
- Implemented hasPrivilege() with the locked 3-step resolution order: ADMIN short-circuit, explicit roles + PUBLIC collection, OR-logic grant checking across all roles
- Implemented role/membership/grant lifecycle methods with immutability guards preventing mutation of ADMIN and PUBLIC built-in roles
- Implemented bootstrap ADMIN assignment and fail-fast startup validation for coordinator safety
- Implemented AccessControlListingManager methods converting proto objects to SysTable POJOs with synthetic ADMIN/PUBLIC entries

## Task Commits

Each task was committed atomically:

1. **Task 1: Create RbacService with privilege resolution and role/membership lifecycle** - `aaf369897` (feat)

**Plan metadata:** (this commit, docs)

## Files Created/Modified
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java` - Core RBAC service: 411 lines implementing privilege resolution, role/membership/grant lifecycle, bootstrap, fail-fast validation, and AccessControlListingManager

## Decisions Made
- Constructor validates all three stores are non-null via Preconditions.checkNotNull for fail-fast initialization
- isAdminMember() is private rather than package-private -- it is only used by hasPrivilege() internally
- assignBootstrapAdmin() sets grantedBy to "SYSTEM" to distinguish bootstrap-created memberships from user-created ones
- Built-in roles appear in getRoleInfo() with role_type "SYSTEM" while user-created roles use "USER"
- getMembershipInfo() excludes PUBLIC implicit memberships per locked decision -- only explicit store memberships returned

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- RbacService is ready for comprehensive unit testing (Plan 03-02)
- All public methods have clear contracts: hasPrivilege returns boolean, lifecycle methods throw checked/unchecked exceptions per Phase 2 patterns
- Phase 4 DI wiring can inject RbacService constructor with Provider-based store instances
- AccessControlListingManager implementation is ready for SabotContext.getAccessControlListingManager() binding

---
*Phase: 03-service-layer*
*Completed: 2026-02-17*

## Self-Check: PASSED

- RbacService.java: FOUND
- Commit aaf369897: FOUND
