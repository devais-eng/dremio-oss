# Phase 3: Service Layer - Research

**Researched:** 2026-02-17
**Domain:** RBAC service layer -- business logic for privilege checking, role lifecycle, membership management, bootstrap, and system table listing
**Confidence:** HIGH

<user_constraints>

## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Privilege Resolution
- OR logic: user has privilege if ANY of their roles has the matching grant
- hasPrivilege() returns boolean (true/false), not exception on denial -- caller decides how to handle denial
- ADMIN membership checked first (short-circuit): if user is ADMIN, return true immediately without checking grants
- No caching in v1: hit KV store on every hasPrivilege() call. Optimize later if needed
- Resolution order: check ADMIN -> collect user's explicit roles + PUBLIC -> check grants for all collected roles

#### PUBLIC Role Behavior
- PUBLIC is a synthetic constant (never stored in KV store) -- consistent with Phase 1 decision
- PUBLIC grants checked alongside explicit role grants in a single pass (not a separate fallback step)
- PUBLIC is grantable: admins can assign privileges to PUBLIC via `GRANT ... TO ROLE PUBLIC`, giving all users access
- PUBLIC is immutable: cannot be dropped, users cannot be removed from PUBLIC

#### Bootstrap Flow
- ADMIN membership assigned during FirstLoginSetupService (bootstrap user creation flow)
- One-time assignment: bootstrap sets ADMIN membership once, subsequent restarts read from KV store
- Fail-fast on startup: if RBAC enabled and ADMIN role has zero members, coordinator refuses to start with a clear error message telling admin to either assign an ADMIN member or disable RBAC
- ADMIN is immutable: cannot be dropped, it's a system constant. Only membership changes are allowed

#### Service Listing Data
- Built-in roles (ADMIN, PUBLIC) appear in sys.roles listings as system-created entries
- PUBLIC implicit memberships are NOT listed in sys.membership -- only explicit role assignments shown
- Service layer converts protos to simple POJOs for system table consumption (not raw proto objects)
- Listing endpoints restricted to ADMIN-only -- non-admin users cannot query sys.roles, sys.privileges, sys.membership

### Claude's Discretion
- AccessControlListingManager interface design (method signatures, POJO class structure)
- Internal organization of RbacService (single class vs helper classes)
- Exact fail-fast error message wording
- How bootstrap detects "first user" vs "subsequent restart"

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope

</user_constraints>

<phase_requirements>

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| ROLE-05 | Built-in ADMIN role exists at system startup and bypasses all privilege checks | ADMIN synthetic constant in RbacService; hasPrivilege() short-circuit pattern; fail-fast startup validation |
| ROLE-06 | Built-in PUBLIC role exists; all users implicitly belong to it without explicit membership | PUBLIC synthetic constant; single-pass grant collection that includes PUBLIC grants alongside explicit role grants |
| BOOT-01 | First user created via bootstrap flow automatically receives ADMIN role membership | OSSFirstLoginSetupService replacement that calls MembershipStore.add() for ADMIN; BootstrapResource integration point |
| ENFC-04 | ADMIN role members bypass all privilege checks | hasPrivilege() ADMIN-first check via MembershipStore lookup for ADMIN role ID |
| ENFC-05 | Grants to PUBLIC role apply to all users without explicit membership | GrantStore.listByRole("PUBLIC") included in grant collection for every hasPrivilege() call |

</phase_requirements>

## Summary

Phase 3 builds `RbacService` -- a service class in `com.dremio.exec.rbac` that orchestrates the three Phase 2 stores (RoleStore, GrantStore, MembershipStore) to implement RBAC business logic. The service has four distinct responsibility areas: (1) privilege resolution via `hasPrivilege()`, (2) role lifecycle management (create/delete with immutability guards for ADMIN/PUBLIC), (3) membership management (grant/revoke with immutability guards), and (4) system table data exposure via the existing `AccessControlListingManager` interface. All four areas are testable in isolation using the same `LocalKVStoreProvider` pattern established in Phase 2 tests.

The existing codebase provides clear integration points. The `AccessControlListingManager` interface in `com.dremio.exec.store.sys.accesscontrol` already defines three methods -- `getRoleInfo()`, `getPrivilegeInfo()`, `getMembershipInfo()` -- returning iterables of existing POJO classes (`SysTableRoleInfo`, `SysTablePrivilegeInfo`, `SysTableMembershipInfo`). These POJOs already have defined fields that the service must map to. The `Service` interface (`com.dremio.service.Service`) requires `start()` and `close()` lifecycle methods. The bootstrap integration point is `FirstLoginSetupService` in `com.dremio.dac.daemon`, currently a no-op in OSS (`OSSFirstLoginSetupService`).

The service itself stays in the `sabot/kernel` module as decided in Phase 1. Phase 4 will handle wiring into `DACDaemonModule` and `SabotContext`. This phase focuses purely on the service class, its constants, and comprehensive unit tests.

**Primary recommendation:** Implement RbacService as a single class that implements AccessControlListingManager, takes the three stores as constructor dependencies, and uses ADMIN_ROLE_ID/PUBLIC_ROLE_ID string constants for synthetic role checks. Test all five success criteria as separate, focused unit tests.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Dremio KV Store API | (project-internal) | RoleStore, GrantStore, MembershipStore from Phase 2 | Already built, tested, locked |
| AccessControlListingManager | (project-internal) | Interface for system table data | Existing interface in sabot/kernel |
| SysTableRoleInfo / SysTablePrivilegeInfo / SysTableMembershipInfo | (project-internal) | POJOs for system table rows | Existing classes with defined field schemas |
| com.dremio.service.Service | (project-internal) | Service lifecycle (start/close) | Standard Dremio service interface |
| Guava Preconditions | (project-bundled) | Input validation | Used consistently across all store classes |
| Proto3 (RbacProto) | (project-internal) | Role, Grant, Membership messages | Phase 1 proto schema |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| LocalKVStoreProvider | (project-internal) | In-memory KV store for tests | All unit tests |
| DremioTest.CLASSPATH_SCAN_RESULT | (project-internal) | Classpath scan for test KV store discovery | Test setUp() |
| AssertJ | (project-bundled) | Fluent test assertions | All test assertions |
| JUnit 4 | (project-bundled) | Test framework | @Test, @Before, @After |
| DremioConfig | (project-internal) | Read `services.rbac.enabled` flag | Fail-fast startup validation |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Single RbacService class | Separate PrivilegeChecker + RoleManager + MembershipManager | Over-engineering for v1; YAGNI. Service methods are small enough for one class |
| Implementing AccessControlListingManager directly | Separate adapter class | Unnecessary indirection; service already has all store references needed |

## Architecture Patterns

### Recommended File Structure
```
sabot/kernel/src/main/java/com/dremio/exec/rbac/
  RbacService.java              # Main service: implements AccessControlListingManager
  RbacConfig.java               # (Phase 1 -- unchanged) store names, key helpers
  RoleStore.java                # (Phase 2 -- unchanged)
  GrantStore.java               # (Phase 2 -- unchanged)
  MembershipStore.java          # (Phase 2 -- unchanged)
  RbacEntityNotFoundException.java   # (Phase 2 -- unchanged)
  RbacEntityAlreadyExistsException.java  # (Phase 2 -- unchanged)

sabot/kernel/src/test/java/com/dremio/exec/rbac/
  RbacServiceTest.java          # Comprehensive unit tests
  RoleStoreTest.java            # (Phase 2 -- unchanged)
  GrantStoreTest.java           # (Phase 2 -- unchanged)
  MembershipStoreTest.java      # (Phase 2 -- unchanged)
```

### Pattern 1: Service implements AccessControlListingManager
**What:** RbacService directly implements AccessControlListingManager, providing both the RBAC business logic API and the system table listing API in one class.
**When to use:** When the service already holds all the data needed for listings (all three stores).
**Rationale:** The existing codebase follows this pattern -- `AccelerationListManagerImpl` directly implements `AccelerationListManager`. No adapter or delegation layer needed.
**Example:**
```java
// Source: Codebase pattern from AccelerationListManagerImpl
public class RbacService implements AccessControlListingManager {

  public static final String ADMIN_ROLE_ID = "ADMIN";
  public static final String PUBLIC_ROLE_ID = "PUBLIC";

  private final RoleStore roleStore;
  private final GrantStore grantStore;
  private final MembershipStore membershipStore;

  public RbacService(RoleStore roleStore, GrantStore grantStore, MembershipStore membershipStore) {
    this.roleStore = roleStore;
    this.grantStore = grantStore;
    this.membershipStore = membershipStore;
  }

  @Override
  public void start() throws Exception {
    // No-op for now; fail-fast validation happens separately
  }

  @Override
  public void close() throws Exception {
    // Stores are managed by KVStoreProvider lifecycle, not by this service
  }

  // ... business logic methods ...
  // ... AccessControlListingManager methods ...
}
```

### Pattern 2: Synthetic Constants for Built-in Roles
**What:** ADMIN and PUBLIC role IDs are `public static final String` constants on RbacService. They are never stored in the KV store. All code references these constants rather than hardcoded strings.
**When to use:** Every method that needs to check for built-in roles.
**Example:**
```java
public static final String ADMIN_ROLE_ID = "ADMIN";
public static final String PUBLIC_ROLE_ID = "PUBLIC";

public boolean isAdminMember(String userName) {
  String key = RbacConfig.membershipKey(userName, ADMIN_ROLE_ID);
  return membershipStore.get(key) != null;
}
```

### Pattern 3: hasPrivilege() Resolution Flow
**What:** Boolean privilege check following the locked resolution order.
**When to use:** Every privilege check at query time (Phase 6+).
**Example:**
```java
public boolean hasPrivilege(String userName, String privilege, String objectType, String objectPath) {
  // Step 1: ADMIN short-circuit
  if (isAdminMember(userName)) {
    return true;
  }

  // Step 2: Collect explicit roles + PUBLIC
  List<Membership> memberships = membershipStore.listByUser(userName);
  List<String> roleIds = memberships.stream()
      .map(Membership::getRoleId)
      .collect(Collectors.toList());
  roleIds.add(PUBLIC_ROLE_ID); // implicit PUBLIC membership

  // Step 3: Check grants for all roles (OR logic)
  for (String roleId : roleIds) {
    String grantKey = RbacConfig.grantKey(roleId, objectType, objectPath, privilege);
    if (grantStore.get(grantKey) != null) {
      return true;
    }
  }
  return false;
}
```

### Pattern 4: Immutability Guards
**What:** Prevent dropping ADMIN/PUBLIC roles and prevent removing users from PUBLIC.
**When to use:** Role deletion and membership removal operations.
**Example:**
```java
public void deleteRole(String roleId) throws RbacEntityNotFoundException {
  Preconditions.checkArgument(!ADMIN_ROLE_ID.equals(roleId),
      "Cannot drop built-in role: " + ADMIN_ROLE_ID);
  Preconditions.checkArgument(!PUBLIC_ROLE_ID.equals(roleId),
      "Cannot drop built-in role: " + PUBLIC_ROLE_ID);
  roleStore.delete(roleId, grantStore, membershipStore);
}
```

### Pattern 5: Listing Manager Proto-to-POJO Conversion
**What:** Convert RbacProto.Role/Grant/Membership to SysTableRoleInfo/SysTablePrivilegeInfo/SysTableMembershipInfo POJOs, including synthetic ADMIN/PUBLIC rows.
**When to use:** AccessControlListingManager method implementations.
**Example:**
```java
@Override
public Iterable<SysTableRoleInfo> getRoleInfo() {
  List<SysTableRoleInfo> roles = new ArrayList<>();
  // Add synthetic built-in roles
  roles.add(new SysTableRoleInfo(ADMIN_ROLE_ID, ADMIN_ROLE_ID, "SYSTEM", null, null, "SYSTEM"));
  roles.add(new SysTableRoleInfo(PUBLIC_ROLE_ID, PUBLIC_ROLE_ID, "SYSTEM", null, null, "SYSTEM"));
  // Add user-created roles from store
  for (Role role : roleStore.listAll()) {
    roles.add(new SysTableRoleInfo(
        role.getRoleId(), role.getRoleName(), "USER", null, null, role.getCreatedBy()));
  }
  return roles;
}
```

### Pattern 6: Test Structure (following Phase 2 pattern)
**What:** Use LocalKVStoreProvider for integration-style unit tests, matching the established store test pattern.
**When to use:** All RbacService tests.
**Example:**
```java
public class RbacServiceTest {
  private LocalKVStoreProvider kvStoreProvider;
  private RbacService rbacService;

  @Before
  public void setUp() throws Exception {
    kvStoreProvider = new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    kvStoreProvider.start();
    RoleStore roleStore = new RoleStore(() -> kvStoreProvider);
    GrantStore grantStore = new GrantStore(() -> kvStoreProvider);
    MembershipStore membershipStore = new MembershipStore(() -> kvStoreProvider);
    rbacService = new RbacService(roleStore, grantStore, membershipStore);
  }

  @After
  public void tearDown() throws Exception {
    kvStoreProvider.close();
  }
}
```

### Anti-Patterns to Avoid
- **Storing ADMIN/PUBLIC in KV store:** They are synthetic constants. Never call roleStore.create() for them. The service fabricates their existence in listing methods.
- **Throwing exceptions from hasPrivilege():** The locked decision says it returns boolean. The caller decides how to handle denial (Phase 6).
- **Caching privilege results:** Explicitly deferred. No in-memory caching, no memoization. Hit the store every time.
- **Checking PUBLIC as a fallback step:** PUBLIC grants are collected in the same pass as explicit role grants, not in a separate "if no explicit grants found, try PUBLIC" branch.
- **Listing PUBLIC memberships:** sys.membership only shows explicit assignments. PUBLIC implicit membership is never listed.
- **Mocking stores in tests:** Phase 2 locked the pattern as LocalKVStoreProvider, not custom HashMap mocks or Mockito.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| System table POJOs | Custom POJO classes | Existing SysTableRoleInfo, SysTablePrivilegeInfo, SysTableMembershipInfo | Already defined with correct field schemas; SystemTable enum already references them |
| Service lifecycle | Custom lifecycle management | `com.dremio.service.Service` interface (start/close) | Standard Dremio pattern; DACDaemonModule expects it |
| Input validation | Custom null/empty checks | `Preconditions.checkArgument` / `Preconditions.checkNotNull` from Guava | Phase 2 established this pattern |
| Composite key building | String concatenation inline | `RbacConfig.grantKey()` / `RbacConfig.membershipKey()` | Centralized, tested, consistent separator |
| Test KV store | HashMap-based mocks | `LocalKVStoreProvider` | Phase 2 locked decision; provides real KV store behavior |

**Key insight:** The existing codebase already provides all the infrastructure needed. The service layer is pure orchestration logic on top of Phase 2 stores and existing system table interfaces.

## Common Pitfalls

### Pitfall 1: ADMIN Membership Check vs Role Existence Check
**What goes wrong:** Checking if the ADMIN *role* exists (it's synthetic, never in KV store) instead of checking if the user has an ADMIN *membership* (which IS stored in KV store).
**Why it happens:** Confusion between "ADMIN role is synthetic" and "ADMIN membership is stored."
**How to avoid:** ADMIN/PUBLIC as roles are never in RoleStore. But memberships like `alice|ADMIN` ARE stored in MembershipStore. The hasPrivilege() check looks up `membershipStore.get(membershipKey(userName, ADMIN_ROLE_ID))`.
**Warning signs:** Test that creates ADMIN role in RoleStore -- that's wrong.

### Pitfall 2: PUBLIC Grant Key Prefix Collision
**What goes wrong:** PUBLIC role grants stored as `PUBLIC|VDS|...` could theoretically collide with a user-created role that happens to start with "PUBLIC".
**Why it happens:** GrantStore uses prefix-based filtering.
**How to avoid:** The key separator `|` prevents this. `GrantStore.listByRole("PUBLIC")` uses prefix `PUBLIC|`, which won't match a role like `PUBLIC_VIEWERS` because that would have prefix `PUBLIC_VIEWERS|`. The pipe delimiter is the boundary. Phase 2 tests already validated this boundary safety for `dev` vs `devops`.
**Warning signs:** None expected -- this is already safe by design.

### Pitfall 3: Forgetting PUBLIC in Grant Collection
**What goes wrong:** hasPrivilege() only checks explicit role grants, missing PUBLIC grants.
**Why it happens:** PUBLIC is implicit -- easy to forget adding it to the role list.
**How to avoid:** After collecting explicit roles from MembershipStore, always append PUBLIC_ROLE_ID to the list before iterating grants. The unit test must verify that a user with NO explicit roles but with a PUBLIC grant gets access.
**Warning signs:** Test `testPublicGrantAppliesWithoutExplicitMembership` fails.

### Pitfall 4: Bootstrap Race Condition
**What goes wrong:** RBAC is enabled, bootstrap user is created via BootstrapResource, but ADMIN membership is not yet assigned, and a fail-fast check runs before bootstrap completes.
**Why it happens:** Fail-fast happens at coordinator startup, but first user creation happens later (via REST API call).
**How to avoid:** The fail-fast check should be lenient during first-ever startup. If `hasAnyUser()` returns false (no users exist yet), the fail-fast check should pass -- because the bootstrap hasn't happened yet. Only fail if there ARE users but ADMIN has zero members.
**Warning signs:** Clean-install startup fails with "ADMIN has no members" before the admin has a chance to create the first user.

### Pitfall 5: Listing Built-in Roles with Wrong Type
**What goes wrong:** ADMIN/PUBLIC shown in sys.roles with role_type="USER" instead of "SYSTEM".
**Why it happens:** Using the same conversion path for built-in and user-created roles.
**How to avoid:** Built-in roles are fabricated separately in getRoleInfo() with role_type="SYSTEM". User-created roles from RoleStore get role_type="USER" (or another appropriate value).
**Warning signs:** sys.roles query shows ADMIN with role_type="USER".

### Pitfall 6: Immutability Guard on Membership Removal for PUBLIC
**What goes wrong:** Allowing `removeMembership(user, PUBLIC)` which would be meaningless since PUBLIC membership is implicit.
**Why it happens:** No guard on PUBLIC membership removal.
**How to avoid:** Since PUBLIC memberships are never stored in MembershipStore (they're implicit), a remove call would hit `RbacEntityNotFoundException`. The service should guard this explicitly with a clearer error message: "Cannot remove membership from built-in role: PUBLIC".
**Warning signs:** Confusing exception message when trying to remove PUBLIC membership.

## Code Examples

Verified patterns from the existing codebase:

### Store Instantiation (from Phase 2 tests)
```java
// Source: sabot/kernel/src/test/java/com/dremio/exec/rbac/RoleStoreTest.java
kvStoreProvider = new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
kvStoreProvider.start();
roleStore = new RoleStore(() -> kvStoreProvider);
grantStore = new GrantStore(() -> kvStoreProvider);
membershipStore = new MembershipStore(() -> kvStoreProvider);
```

### Proto Building (from Phase 2 tests)
```java
// Source: sabot/kernel/src/test/java/com/dremio/exec/rbac/RoleStoreTest.java
Role role = Role.newBuilder().setRoleId(roleId).setRoleName(roleName).build();
Grant grant = Grant.newBuilder()
    .setRoleId(roleId).setObjectType(objectType)
    .setObjectPath(objectPath).setPrivilege(privilege).build();
Membership membership = Membership.newBuilder()
    .setUserName(userName).setRoleId(roleId).build();
```

### SysTableRoleInfo Construction (from existing POJO)
```java
// Source: sabot/kernel/.../accesscontrol/SysTableRoleInfo.java
new SysTableRoleInfo(
    role_id,     // String
    role_name,   // String
    role_type,   // String ("SYSTEM" or "USER")
    owner_id,    // String (nullable)
    owner_type,  // String (nullable)
    created_by   // String
);
```

### SysTablePrivilegeInfo Construction (from existing POJO)
```java
// Source: sabot/kernel/.../accesscontrol/SysTablePrivilegeInfo.java
new SysTablePrivilegeInfo(
    grantee_type,  // String ("ROLE")
    grantee,       // String (role name)
    object_type,   // String ("VDS", "FUNCTION")
    object,        // String (object path)
    privilege      // String ("SELECT", "EXECUTE")
);
```

### SysTableMembershipInfo Construction (from existing POJO)
```java
// Source: sabot/kernel/.../accesscontrol/SysTableMembershipInfo.java
new SysTableMembershipInfo(
    role_name,    // String
    member_name,  // String (username)
    member_type   // String ("USER")
);
```

### AccessControlListingManager Interface (existing)
```java
// Source: sabot/kernel/.../accesscontrol/AccessControlListingManager.java
public interface AccessControlListingManager extends Service {
  Iterable<SysTableMembershipInfo> getMembershipInfo();
  Iterable<SysTablePrivilegeInfo> getPrivilegeInfo();
  Iterable<SysTableRoleInfo> getRoleInfo();
}
```

### How SystemTable Consumes the Listing Manager (existing)
```java
// Source: sabot/kernel/.../sys/SystemTable.java (lines 227-279)
ROLES(false, SysTableRoleInfo.class, "roles") {
  @Override
  public Iterator<?> getIterator(
      final PluginSabotContext sabotContext, final OperatorContext operatorContext) {
    try {
      final AccessControlListingManager accessControlListingManager =
          sabotContext.getAccessControlListingManager();
      if (accessControlListingManager == null) {
        throw new IllegalAccessException("Unable to retrieve sys.roles.");
      }
      return accessControlListingManager.getRoleInfo().iterator();
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }
},
// Same pattern for PRIVILEGES and MEMBERSHIP
```

### Service Lifecycle Pattern (from AccelerationListManagerImpl)
```java
// Source: services/accelerator/.../AccelerationListManagerImpl.java
public class AccelerationListManagerImpl implements AccelerationListManager {
  @Override
  public void start() {} // no-op is acceptable

  @Override
  public void close() throws Exception {} // no-op is acceptable
}
```

### DACDaemonModule Registration Pattern (for reference -- Phase 4)
```java
// Source: dac/backend/.../DACDaemonModule.java (line 1469-1479)
final AccelerationListManagerImpl accelerationListManager =
    new AccelerationListManagerImpl(
        registry.provider(LegacyKVStoreProvider.class),
        registry.provider(ReflectionStatusService.class),
        // ... more providers ...
    );
registry.bind(AccelerationListManager.class, accelerationListManager);
```

### Bootstrap Hook Point (existing, currently no-op)
```java
// Source: dac/backend/.../DACDaemonModule.java (line 1641)
registry.bind(FirstLoginSetupService.class, OSSFirstLoginSetupService.NOOP_INSTANCE);

// Source: dac/backend/.../FirstLoginSetupService.java
public interface FirstLoginSetupService {
  void setup(String username);
}

// Source: dac/backend/.../BootstrapResource.java -- creates first user but does NOT call FirstLoginSetupService
// The setup() method is never invoked in current OSS code.
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| SabotContext.getAccessControlListingManager() returns null | Phase 3 provides RbacService implementation | This phase | System tables sys.roles, sys.privileges, sys.membership will return data |
| OSSFirstLoginSetupService is a no-op | Phase 3 provides bootstrap ADMIN assignment in this hook | This phase | First user gets ADMIN automatically |
| No privilege checking capability | hasPrivilege() available for Phase 6 wiring | This phase | Foundation for query-time enforcement |

## Discretion Recommendations

### AccessControlListingManager Interface
**Recommendation:** RbacService directly implements AccessControlListingManager. No separate adapter needed. The three methods (`getRoleInfo()`, `getPrivilegeInfo()`, `getMembershipInfo()`) are already defined and the POJO classes already exist. RbacService maps protos to POJOs and injects synthetic ADMIN/PUBLIC entries.

### Internal Organization of RbacService
**Recommendation:** Single class. The service has about 10-12 methods total:
- `hasPrivilege(userName, privilege, objectType, objectPath)` -- boolean
- `isAdminMember(userName)` -- boolean, package-private helper
- `createRole(roleId, roleName, createdBy)` -- delegates to RoleStore with immutability guard
- `deleteRole(roleId)` -- delegates to RoleStore with immutability guard
- `grantPrivilege(roleId, objectType, objectPath, privilege, grantedBy)` -- delegates to GrantStore
- `revokePrivilege(roleId, objectType, objectPath, privilege)` -- delegates to GrantStore
- `addMembership(userName, roleId, grantedBy)` -- delegates to MembershipStore with PUBLIC guard
- `removeMembership(userName, roleId)` -- delegates to MembershipStore with ADMIN/PUBLIC guard
- `getRoleInfo()` -- AccessControlListingManager
- `getPrivilegeInfo()` -- AccessControlListingManager
- `getMembershipInfo()` -- AccessControlListingManager
- `validateAdminMembersExist()` -- fail-fast startup check
- `assignBootstrapAdmin(userName)` -- called from bootstrap flow

This is under 200 lines of real logic. A single class is appropriate.

### Fail-Fast Error Message
**Recommendation:**
```
RBAC is enabled (services.rbac.enabled=true) but the ADMIN role has no members.
Either assign a user to the ADMIN role or set services.rbac.enabled=false in dremio.conf.
Coordinator will not start without an ADMIN member when RBAC is enabled.
```

### Bootstrap "First User" Detection
**Recommendation:** The bootstrap flow is triggered via `BootstrapResource.createUser()` when `hasAnyUser()` returns false. The RBAC service should provide a method `assignBootstrapAdmin(String userName)` that creates the ADMIN membership in MembershipStore. The `OSSFirstLoginSetupService` should be replaced (or extended) to call this method.

**Important discovery:** `FirstLoginSetupService.setup(String username)` is currently never actually invoked in OSS code. `BootstrapResource.createUser()` creates the user but doesn't call `FirstLoginSetupService.setup()`. Two approaches:
1. **Hook into BootstrapResource** -- modify `createUser()` to call `rbacService.assignBootstrapAdmin()` after user creation
2. **Make FirstLoginSetupService actually get called** -- add a call to it in BootstrapResource, then replace the no-op impl

Approach 1 is simpler for Phase 3 scope. However, since Phase 3 is about building the service in isolation (testable without catalog wiring), the service just needs the `assignBootstrapAdmin()` method. The actual wiring into BootstrapResource happens in Phase 4. The fail-fast validation method `validateAdminMembersExist()` is also just a method on the service, called by coordinator startup code in Phase 4.

For Phase 3, both `assignBootstrapAdmin()` and `validateAdminMembersExist()` are public methods tested via unit tests. Wiring is deferred.

## Open Questions

1. **Should createRole() validate that the roleId doesn't collide with ADMIN/PUBLIC?**
   - What we know: ADMIN and PUBLIC are not stored in KV store. A user could theoretically create a role with roleId "ADMIN" or "PUBLIC" and it would succeed at the store level.
   - What's unclear: Should the service reject this at creation time?
   - Recommendation: YES -- guard against it. `createRole()` should reject roleId matching ADMIN_ROLE_ID or PUBLIC_ROLE_ID with an IllegalArgumentException. This prevents confusion in listings and grant resolution.

2. **Should grantPrivilege() validate the role exists before granting?**
   - What we know: GrantStore doesn't check role existence; it just stores the key.
   - What's unclear: Should grants to non-existent roles be allowed?
   - Recommendation: YES, validate. For built-in roles (ADMIN, PUBLIC), skip the RoleStore check (they're synthetic). For user-created roles, verify `roleStore.get(roleId) != null` before granting. This prevents orphan grants.

3. **Should addMembership() validate the role exists?**
   - What we know: MembershipStore doesn't check role existence.
   - What's unclear: Same as grants -- should membership to non-existent roles be allowed?
   - Recommendation: YES, validate. ADMIN membership is valid (it's a built-in). PUBLIC membership should be rejected (implicit, not stored). Other roles should be verified in RoleStore.

## Sources

### Primary (HIGH confidence)
- `sabot/kernel/src/main/java/com/dremio/exec/store/sys/accesscontrol/AccessControlListingManager.java` -- existing interface, method signatures verified
- `sabot/kernel/src/main/java/com/dremio/exec/store/sys/accesscontrol/SysTableRoleInfo.java` -- POJO field schema verified (6 fields)
- `sabot/kernel/src/main/java/com/dremio/exec/store/sys/accesscontrol/SysTablePrivilegeInfo.java` -- POJO field schema verified (5 fields)
- `sabot/kernel/src/main/java/com/dremio/exec/store/sys/accesscontrol/SysTableMembershipInfo.java` -- POJO field schema verified (3 fields)
- `sabot/kernel/src/main/java/com/dremio/exec/store/sys/SystemTable.java` -- ROLES/PRIVILEGES/MEMBERSHIP enum entries, consumption pattern verified
- `sabot/kernel/src/main/java/com/dremio/exec/server/SabotContext.java` -- getAccessControlListingManager() returns null, confirmed
- `sabot/kernel/src/main/java/com/dremio/exec/rbac/*` -- all Phase 2 store classes and tests verified
- `sabot/kernel/src/main/protobuf/rbac.proto` -- proto schema verified
- `common/legacy/src/main/java/com/dremio/service/Service.java` -- Service interface (start + AutoCloseable)
- `common/legacy/src/main/java/com/dremio/config/DremioConfig.java` -- RBAC_ENABLED constant verified
- `dac/backend/src/main/java/com/dremio/dac/daemon/FirstLoginSetupService.java` -- bootstrap interface verified
- `dac/backend/src/main/java/com/dremio/dac/daemon/OSSFirstLoginSetupService.java` -- no-op implementation verified
- `dac/backend/src/main/java/com/dremio/dac/resource/BootstrapResource.java` -- first user creation flow verified; does NOT call FirstLoginSetupService.setup()
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` -- service registration patterns verified
- `services/accelerator/src/main/java/com/dremio/service/accelerator/AccelerationListManagerImpl.java` -- listing manager implementation pattern verified

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all components are existing project-internal classes verified in source
- Architecture: HIGH -- patterns directly derived from existing codebase (AccelerationListManagerImpl, store tests)
- Pitfalls: HIGH -- based on analysis of actual code paths, store key formats, and bootstrap flow
- Integration points: HIGH -- all interfaces and their consumption verified in source

**Research date:** 2026-02-17
**Valid until:** 2026-03-17 (stable domain -- internal project, no external dependency changes expected)
