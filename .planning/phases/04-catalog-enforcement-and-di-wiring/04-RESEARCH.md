# Phase 4: Catalog Enforcement and DI Wiring - Research

**Researched:** 2026-02-18
**Domain:** CatalogImpl privilege enforcement, Dremio DI wiring, system-user bypass
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Denial behavior
- Denied access returns "not found" error -- identical to querying a genuinely non-existent object (complete information hiding)
- User cannot distinguish between "object doesn't exist" and "object exists but I lack access"
- Consistent for both VDS (SELECT) and UDF (EXECUTE) -- denied EXECUTE looks like "function not found"
- Access denials logged server-side at WARN level for admin troubleshooting
- Log message is minimal: "RBAC: Access denied for user 'alice'" -- no object path or privilege type in log output

#### System bypass scope
- Only the $dremio$ system user bypasses RBAC -- no other special-casing for internal operations
- Use Dremio's existing SystemUser identification mechanism (whatever the codebase already provides)
- Feature flag checked first: if RBAC disabled, return immediately -- no system-user check needed
- ADMIN role members go through normal hasPrivilege() path (already implemented in Phase 3) -- CatalogImpl does NOT special-case ADMIN

#### DI wiring approach
- Claude researches CatalogImpl's construction path and follows the existing DI pattern for similar services
- RbacService registered following existing Dremio DI pattern (Claude discovers how NamespaceService, CatalogService etc. are bound and follows that)
- Enforcement logic lives directly in CatalogImpl.validatePrivilege() -- no separate enforcer class
- CatalogImpl checks feature flag first and returns early if RBAC disabled -- RbacService is never called when flag is OFF

### Claude's Discretion
- Exact DI wiring mechanism (constructor injection vs SabotContext lookup vs Guice binding) -- Claude researches and picks cleanest fit
- Test unit selection -- Claude decides whether to test validatePrivilege() directly or via catalog operations based on CatalogImpl testability
- Error message exact text -- must be indistinguishable from genuine "not found"

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| ENFC-01 | Deny-by-default -- a user with no applicable grant cannot SELECT a VDS | validatePrivilege() implementation throws "not found" when hasPrivilege returns false |
| ENFC-02 | A user with SELECT grant on a VDS (via any of their roles) can query that VDS | validatePrivilege() calls RbacService.hasPrivilege() and returns silently when true |
| ENFC-03 | A user with EXECUTE grant on a UDF (via any of their roles) can call that UDF | Same validatePrivilege() path with EXECUTE privilege and FUNCTION object type |
| ENFC-06 | System user ($dremio$) bypasses all privilege checks | SystemUser.isSystemUserName(userName) check in validatePrivilege() |
| ENFC-07 | Definer-rights model preserved -- privilege checked on outermost entity only | ViewExpander already resolves inner tables under view owner's identity via builder.withUser(viewOwner); no changes needed |
| ENFC-08 | CREATE_VIEW privilege checked for CREATE OR REPLACE VIEW | CreateOrUpdateViewHandler already calls validatePrivilege() -- change privilege from ALTER to CREATE_VIEW |
| BOOT-02 | System starts successfully with RBAC disabled -- no enforcement | Feature flag early return in validatePrivilege(); DI wiring with null-safe pattern |
</phase_requirements>

## Summary

Phase 4 wires the existing RbacService (Phase 3) into CatalogImpl.validatePrivilege() -- a method that currently exists as a no-op. The CatalogImpl is instantiated by CatalogServiceImpl.createCatalog() factory method, which already accepts 16 constructor parameters. The cleanest approach is to add RbacService as a 17th constructor parameter, following the exact same pattern used for OptionManager, NamespaceService, and other dependencies. CatalogServiceImpl already holds a reference to DremioConfig (for the feature flag) and receives all dependencies via the DACDaemonModule registry.bind() pattern.

The critical architectural insight is that `validatePrivilege()` is called from two different code paths: (1) explicitly by DDL/DML handlers for operations like ALTER, CREATE_TABLE, DELETE, INSERT, MERGE, and (2) it MUST be called explicitly for SELECT on VDS. Currently, simple `SELECT * FROM view` queries do NOT call validatePrivilege -- the table is resolved via getTable() which returns null for non-existent entities. For RBAC enforcement on SELECT, the implementation must add explicit validatePrivilege() calls at VDS resolution points or within the validatePrivilege() method itself when called by handlers. Given the user decision that denied access returns "not found", the enforcement at the getTable level (returning null for denied VDS) achieves information hiding most naturally.

The definer-rights model (ENFC-07) requires NO new code -- ViewExpander already resolves inner tables under the view owner's identity via `builder.withUser(viewOwner)`. This means validatePrivilege is only called on the outermost entity by the query handler, and inner table lookups run as the view owner, naturally preserving definer-rights semantics.

**Primary recommendation:** Add RbacService + DremioConfig as constructor parameters to CatalogImpl, implement validatePrivilege() with feature-flag/system-user/hasPrivilege chain, and throw UserException matching existing "not found" format for denied access.

## Standard Stack

### Core (all existing, no new libraries)
| Component | Location | Purpose | Why Standard |
|-----------|----------|---------|--------------|
| CatalogImpl | `com.dremio.exec.catalog.CatalogImpl` | Implements Catalog interface, target for validatePrivilege() | The single catalog implementation class |
| CatalogServiceImpl | `com.dremio.exec.catalog.CatalogServiceImpl` | Factory creating CatalogImpl instances | Has DremioConfig field, creates catalogs with constructor injection |
| RbacService | `com.dremio.exec.rbac.RbacService` | Privilege resolution (Phase 3) | hasPrivilege(userName, privilege, objectType, objectPath) returns boolean |
| DremioConfig | `com.dremio.config.DremioConfig` | Feature flag access | `DremioConfig.RBAC_ENABLED = "services.rbac.enabled"` |
| SystemUser | `com.dremio.service.users.SystemUser` | System user identification | `SystemUser.isSystemUserName(username)` checks for "$dremio$" |
| SqlGrant.Privilege | `com.dremio.exec.planner.sql.parser.SqlGrant.Privilege` | Privilege enum | SELECT, EXECUTE, CREATE_VIEW, ALTER, etc. |
| UserException | `com.dremio.common.exceptions.UserException` | Error building for "not found" responses | Standard Dremio error mechanism |
| DACDaemonModule | `com.dremio.dac.daemon.DACDaemonModule` | DI wiring via registry.bind() | Where all services are registered |
| SingletonRegistry | `com.dremio.service.SingletonRegistry` | Service registry for DI | `registry.bind()`, `registry.provider()` pattern |

### Supporting
| Component | Location | Purpose | When to Use |
|-----------|----------|---------|-------------|
| SourceAccessChecker | `com.dremio.exec.catalog.SourceAccessChecker` | Decorator pattern for access control | Reference pattern: already has SystemUser bypass at line 612 |
| NamespaceKey | `com.dremio.service.namespace.NamespaceKey` | Object path representation | Used in validatePrivilege() signature |
| ViewExpander | `com.dremio.exec.planner.sql.ViewExpander` | Definer-rights view expansion | No changes needed -- already uses view owner identity |
| Mockito | test dependency | Mocking for unit tests | TestCatalogImpl pattern uses mock() for all 16 constructor params |

## Architecture Patterns

### Pattern 1: CatalogImpl Construction Path (CRITICAL -- resolves MEDIUM-confidence blocker)

**What:** CatalogImpl is NOT created via Guice injection. It is manually constructed by `CatalogServiceImpl.createCatalog()`:

```
DACDaemonModule.register()
  -> registry.bind(CatalogService.class, new CatalogServiceImpl(..., config, ...))
       CatalogServiceImpl stores: DremioConfig config, Provider<LegacyKVStoreProvider> kvStoreProvider

CatalogServiceImpl.getCatalog(requestOptions)
  -> SourceAccessChecker.secureIfNeeded(options, createCatalog(options))
     -> new CatalogImpl(options, retriever, sourceModifier, optionManager, ...)  // 16 params
```

**Key finding:** CatalogServiceImpl already holds DremioConfig as a field (`protected final DremioConfig config`). The RbacService can be created in CatalogServiceImpl and passed to CatalogImpl.

**DI wiring recommendation (Claude's discretion decision):** Constructor injection into CatalogImpl, with RbacService created in CatalogServiceImpl from KVStoreProvider. This is the cleanest fit because:
1. CatalogServiceImpl already has `Provider<LegacyKVStoreProvider> kvStoreProvider` and `DremioConfig config`
2. All other CatalogImpl dependencies are passed via constructor params
3. No Guice module changes needed -- just modify the factory method
4. The stores take `Provider<KVStoreProvider>` (not `Provider<LegacyKVStoreProvider>`), so check if they're compatible

**Important:** The stores use `com.dremio.datastore.api.KVStoreProvider` while CatalogServiceImpl has `com.dremio.datastore.api.LegacyKVStoreProvider`. These may be different types. The stores' `@Inject` constructors take `Provider<KVStoreProvider>` (the non-legacy one). The DACDaemonModule likely also registers a `KVStoreProvider`. The RbacService should be registered in DACDaemonModule using `registry.bind()` with its own `Provider<KVStoreProvider>`.

### Pattern 2: validatePrivilege() No-Op Implementation

**What:** The current implementation at line 2767 is a deliberate no-op:
```java
@Override
public void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege) {
  // For the default implementation, don't validate privilege.
}
```

**Signature:** `void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege)` -- defined in `DatasetCatalog` interface (line 255), marked `@Deprecated`. The method throws nothing by signature but callers catch `UserException`.

**Call chain:** SourceAccessChecker -> DelegatingCatalog -> CatalogImpl (all delegate down to CatalogImpl).

### Pattern 3: DACDaemonModule Registry Pattern

**What:** Services are registered using `SingletonRegistry.bind()` with explicit construction:
```java
// Pattern from DACDaemonModule line 1025-1048:
registry.bind(CatalogService.class, new CatalogServiceImpl(
    registry.provider(SabotContext.class),
    registry.provider(SchedulerService.class),
    // ... 18 more providers/config args ...
    config,
    roles,
    // ...
));
```

**For RbacService wiring:** Create and register RbacService in DACDaemonModule, then provide it to CatalogServiceImpl via constructor.

### Pattern 4: System User Bypass

**What:** `SystemUser.isSystemUserName(username)` checks if a string equals `"$dremio$"` (the constant `UserConstants.SYSTEM_USERNAME`).

**Existing usage in SourceAccessChecker.secureIfNeeded():**
```java
public static Catalog secureIfNeeded(MetadataRequestOptions options, Catalog delegate) {
  return options.getSchemaConfig().exposeInternalSources()
          || SystemUser.isSystemUserName(options.getSchemaConfig().getUserName())
      ? delegate
      : new SourceAccessChecker(options, delegate);
}
```

**For validatePrivilege():** CatalogImpl has `this.userName = options.getSchemaConfig().getUserName()` in its constructor. Use `SystemUser.isSystemUserName(this.userName)` for the system user bypass check.

### Pattern 5: "Not Found" Error Format

**What:** The existing "not found" errors in CatalogImpl use these formats:
```java
// Table not found (line 502, 734, 915):
UserException.validationError().message("Table '%s' not found", key).buildSilently();

// Table not found with exception context (line 1903):
UserException.validationError(ex).message("Table [%s] not found.", key).build(logger);
```

**For RBAC denial:** Throw the same format: `UserException.validationError().message("Table '%s' not found", key).buildSilently()` -- this makes denied access indistinguishable from non-existent objects.

### Pattern 6: Self-Reference in resolveCatalog()

**CRITICAL:** CatalogImpl has FOUR `resolveCatalog()` methods that create new CatalogImpl instances by calling `new CatalogImpl(...)`. Every constructor parameter must be carried forward:
- `resolveCatalog(Map<String, VersionContext>)` -- line 1558
- `resolveCatalogResetContext(String, VersionContext)` -- line 1581
- `resolveCatalog(CatalogIdentity)` -- line 1602
- `resolveCatalog(NamespaceKey)` -- line 1624

**Impact:** When adding RbacService and DremioConfig as constructor params, ALL FOUR resolveCatalog methods must pass the new parameters through to the new CatalogImpl instance. Missing any one will silently disable RBAC enforcement for resolved catalogs.

### Recommended Project Structure Changes

```
sabot/kernel/src/main/java/com/dremio/exec/
  catalog/
    CatalogImpl.java                    # MODIFY: add rbacService + dremioConfig params, implement validatePrivilege()
    CatalogServiceImpl.java             # MODIFY: create RbacService, pass to CatalogImpl
  rbac/
    RbacService.java                    # EXISTS (Phase 3) -- no changes

dac/backend/src/main/java/com/dremio/dac/daemon/
    DACDaemonModule.java                # MODIFY: register RbacService, pass to CatalogServiceImpl

sabot/kernel/src/test/java/com/dremio/exec/catalog/
    TestCatalogImpl.java                # MODIFY or ADD: test validatePrivilege() with mock RbacService
```

### Anti-Patterns to Avoid
- **Separate enforcer class:** User decision locks enforcement in CatalogImpl.validatePrivilege() directly -- no RbacEnforcer class
- **ADMIN special-casing in CatalogImpl:** ADMIN bypass is already in RbacService.hasPrivilege() -- CatalogImpl must NOT duplicate this
- **Static DremioConfig access:** Do NOT use DremioConfig.create() or static methods -- pass DremioConfig via constructor like CatalogServiceImpl already does
- **Forgetting resolveCatalog:** Missing the RbacService parameter in any of the 4 resolveCatalog self-copy methods silently disables RBAC for resolved catalogs

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| System user detection | Custom "$dremio$" string comparison | `SystemUser.isSystemUserName(userName)` | Already standardized across codebase, constant defined in UserConstants |
| Feature flag reading | Custom config parsing | `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` | DremioConfig extends NestedConfig which delegates to Typesafe Config |
| "Not found" error | Custom exception class | `UserException.validationError().message("Table '%s' not found", key).buildSilently()` | Must match existing error format exactly for information hiding |
| Privilege enum | New RBAC-specific enum | `SqlGrant.Privilege.SELECT / EXECUTE / CREATE_VIEW` | Already used by all existing handlers |

**Key insight:** The enforcement layer must use existing Dremio abstractions (SystemUser, DremioConfig, UserException, SqlGrant.Privilege) to remain indistinguishable from the existing authorization path. Custom solutions would create detectable behavioral differences.

## Common Pitfalls

### Pitfall 1: Forgetting resolveCatalog() Copy-Through
**What goes wrong:** RbacService is not passed to the new CatalogImpl created in resolveCatalog(), silently disabling RBAC for view expansion and catalog identity switches.
**Why it happens:** CatalogImpl has 4 resolveCatalog methods that each create a new instance with `new CatalogImpl(...)`. It's easy to add the parameter to the constructor and the factory but forget the self-referencing methods.
**How to avoid:** After modifying the constructor, search for ALL `new CatalogImpl(` inside CatalogImpl.java (currently lines 1558, 1581, 1602, 1624) and verify each passes the new parameters.
**Warning signs:** Tests pass for direct validatePrivilege calls but fail when testing through view expansion or catalog.resolveCatalog().

### Pitfall 2: SELECT Not Enforced for Simple Queries
**What goes wrong:** Simple `SELECT * FROM vds` queries bypass RBAC because NormalHandler does NOT call validatePrivilege for SELECT.
**Why it happens:** Only DML handlers (DELETE, MERGE, INSERT) explicitly call validatePrivilege(key, SELECT). For simple SELECT queries, table resolution happens in DatasetManager.getTable() which returns null for non-existent tables. There is no explicit validatePrivilege(SELECT) call in the NormalHandler/SqlToRelTransformer flow.
**How to avoid:** Either: (a) intercept in getTable/getTableHelper to return null for denied VDS (simulating "not found"), or (b) add validatePrivilege(SELECT) calls in the resolution path. Option (a) is more natural for information hiding but requires detecting VDS type vs PDS. Option (b) requires identifying the right hook point.
**Recommendation:** Override getTable to check RBAC before returning results for VIRTUAL_DATASET type entries. This naturally returns null (not found) for denied VDS without changing the error path.

### Pitfall 3: CREATE_VIEW Handler Uses ALTER, Not CREATE_VIEW
**What goes wrong:** ENFC-08 requires CREATE_VIEW privilege for CREATE OR REPLACE VIEW, but `CreateOrUpdateViewHandler` (line 105) calls `catalog.validatePrivilege(resolvedViewPath, SqlGrant.Privilege.ALTER)`.
**Why it happens:** The OSS code was designed for EE privilege model where ALTER covers view creation.
**How to avoid:** Change the handler to call validatePrivilege with CREATE_VIEW instead of ALTER. Note: this changes behavior for EE too, so consider whether this should be conditional on the RBAC feature flag.
**Recommendation:** In the validatePrivilege implementation, map CREATE_VIEW checks correctly. Since we control validatePrivilege, we can accept ALTER from the handler and internally check CREATE_VIEW, OR we can modify the handler. Modifying the handler is cleaner and more explicit.

### Pitfall 4: KVStoreProvider vs LegacyKVStoreProvider
**What goes wrong:** Store constructors use `Provider<KVStoreProvider>` (non-legacy) but CatalogServiceImpl has `Provider<LegacyKVStoreProvider>`.
**Why it happens:** Dremio has two KVStore APIs -- the legacy one and the newer one. The RBAC stores from Phase 2 use the non-legacy `KVStoreProvider`.
**How to avoid:** Register RbacService (with its stores) in DACDaemonModule where `Provider<KVStoreProvider>` is available, NOT inside CatalogServiceImpl. Pass the pre-built RbacService to CatalogServiceImpl as a Provider.
**Warning signs:** ClassCastException or NoSuchBindingException at runtime.

### Pitfall 5: Null RbacService When RBAC Disabled
**What goes wrong:** If RBAC is disabled and RbacService is not created, passing null causes NullPointerException when RBAC is later enabled.
**Why it happens:** Optimization of not creating RbacService when disabled.
**How to avoid:** ALWAYS create and register RbacService regardless of feature flag state. The feature flag check happens in validatePrivilege() -- an early return means RbacService is never called, so it's costless. The stores are lazy-initialized (Suppliers.memoize) so no KV store lookups happen until RbacService methods are actually invoked.
**Warning signs:** NPE when toggling services.rbac.enabled in dremio.conf and restarting.

### Pitfall 6: UDF/EXECUTE Enforcement Missing
**What goes wrong:** UDF execution is not blocked because getFunctions() does not call validatePrivilege.
**Why it happens:** Like getTable for SELECT, getFunctions() does not have an existing privilege check hook.
**How to avoid:** Add RBAC check in the function resolution path. CatalogImpl.getFunctions() and getScalarFunction/getTabularFunction methods need RBAC checks that return empty collections for denied UDFs.

## Code Examples

### Example 1: validatePrivilege() Implementation
```java
// Source: Synthesized from CatalogImpl.java analysis
@Override
public void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege) {
  // (1) Feature flag OFF -> return immediately
  if (!dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    return;
  }

  // (2) System user -> return immediately
  if (SystemUser.isSystemUserName(userName)) {
    return;
  }

  // (3) Map privilege to RBAC objectType/privilege strings
  String rbacPrivilege = privilege.name(); // "SELECT", "EXECUTE", "CREATE_VIEW"
  String rbacObjectType = resolveObjectType(key); // "VDS", "FUNCTION", etc.
  String objectPath = key.getSchemaPath(); // dot-delimited path

  // (4) Call RbacService
  if (!rbacService.hasPrivilege(userName, rbacPrivilege, rbacObjectType, objectPath)) {
    logger.warn("RBAC: Access denied for user '{}'", userName);
    // Throw indistinguishable from "not found"
    throw UserException.validationError()
        .message("Table '%s' not found", key)
        .buildSilently();
  }
}
```

### Example 2: CatalogImpl Constructor Modification
```java
// Source: CatalogImpl.java constructor pattern, lines 219-276
CatalogImpl(
    MetadataRequestOptions options,
    PluginRetriever pluginRetriever,
    CatalogServiceImpl.SourceModifier sourceModifier,
    OptionManager optionManager,
    NamespaceService systemNamespaceService,
    NamespaceService.Factory namespaceFactory,
    Orphanage orphanage,
    DatasetListingService datasetListingService,
    ViewCreatorFactory viewCreatorFactory,
    CatalogIdentityResolver identityResolver,
    VersionContextResolverImpl versionContextResolverImpl,
    CatalogStatusEvents catalogStatusEvents,
    VersionedDatasetAdapterFactory versionedDatasetAdapterFactory,
    MetadataIOPool metadataIOPool,
    CatalogEntityOwnership catalogEntityOwnership,
    UserOrRoleResolver userOrRoleResolver,
    // NEW parameters:
    @Nullable RbacService rbacService,
    DremioConfig dremioConfig) {
  // ... existing assignments ...
  this.rbacService = rbacService;
  this.dremioConfig = dremioConfig;
}
```

### Example 3: CatalogServiceImpl.createCatalog() Modification
```java
// Source: CatalogServiceImpl.java createCatalog() at line 958-988
protected Catalog createCatalog(
    MetadataRequestOptions requestOptions,
    CatalogIdentityResolver identityProvider,
    NamespaceService.Factory namespaceServiceFactory,
    CatalogEntityOwnership catalogEntityOwnership,
    UserOrRoleResolver userOrRoleResolver) {
  // ... existing code ...
  return new CatalogImpl(
      requestOptions,
      retriever,
      new SourceModifier(requestOptions.getSchemaConfig().getAuthContext().getSubject()),
      optionManager,
      // ... 12 more existing params ...
      // NEW:
      rbacService,   // Field added to CatalogServiceImpl
      config);       // Already exists as field
}
```

### Example 4: DACDaemonModule RbacService Registration
```java
// Source: Following DACDaemonModule registry.bind() pattern from lines 1025-1048
// Register RBAC stores and service
Provider<KVStoreProvider> kvStoreProvider = registry.provider(KVStoreProvider.class);
RoleStore roleStore = new RoleStore(kvStoreProvider);
GrantStore grantStore = new GrantStore(kvStoreProvider);
MembershipStore membershipStore = new MembershipStore(kvStoreProvider);
RbacService rbacService = new RbacService(roleStore, grantStore, membershipStore);
registry.bind(RbacService.class, rbacService);

// Then modify CatalogServiceImpl constructor to accept Provider<RbacService>
registry.bind(
    CatalogService.class,
    new CatalogServiceImpl(
        // ... existing params ...
        registry.provider(RbacService.class)));  // NEW
```

### Example 5: Test Pattern (from TestCatalogImpl)
```java
// Source: TestCatalogImpl.java lines 119-173
// Existing test mocks all 16 constructor params with Mockito.mock()
private final RbacService rbacService = mock(RbacService.class);       // NEW
private final DremioConfig dremioConfig = mock(DremioConfig.class);    // NEW

private CatalogImpl newCatalogImpl(VersionContextResolverImpl versionContextResolver) {
  return new CatalogImpl(
      options, pluginRetriever, sourceModifier, optionManager,
      systemNamespaceService, namespaceFactory, orphanage, datasetListingService,
      viewCreatorFactory, identityProvider, versionContextResolver,
      catalogStatusEvents, new VersionedDatasetAdapterFactory(),
      metadataIOPool, catalogEntityOwnership, userOrRoleResolver,
      rbacService, dremioConfig);  // NEW
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No OSS privilege enforcement | EE-only enforcement via GrantHandler | Historical | OSS validatePrivilege() is always no-op |
| EE uses SqlGrant.Privilege.ALTER for views | RBAC needs CREATE_VIEW for CREATE OR REPLACE VIEW | Phase 4 | Must modify CreateOrUpdateViewHandler |
| CatalogImpl has 16 constructor params | Phase 4 adds 2 more (17-18) | Phase 4 | Growing constructor -- acceptable given existing pattern |

**Design considerations:**
- `validatePrivilege` is marked `@Deprecated` in `DatasetCatalog` interface -- this is the EE approach marking it for future removal. We are deliberately implementing it in OSS for RBAC.
- SourceAccessChecker delegates to CatalogImpl -- the call chain is: CachingCatalog -> SourceAccessChecker -> CatalogImpl. Our implementation in CatalogImpl is the terminal point.

## Open Questions

1. **SELECT enforcement for simple queries**
   - What we know: NormalHandler does NOT call validatePrivilege for SELECT. DML handlers (DELETE, MERGE) DO call it.
   - What's unclear: Whether to add RBAC checks in getTable() (return null for denied VDS) or add an explicit validatePrivilege(SELECT) hook in the query planning flow.
   - Recommendation: Add checks in getTable()/getTableHelper() that return null for VDS when user lacks SELECT privilege. This achieves natural "not found" behavior. PDS (physical datasets) are out of scope and should pass through.

2. **UDF EXECUTE enforcement hook**
   - What we know: getFunctions() returns Collections of Function. There is no existing validatePrivilege call in the UDF resolution path.
   - What's unclear: The exact code path from SQL `SELECT my_func(x)` through function resolution.
   - Recommendation: Add RBAC check in getFunctions()/getUserDefinedFunction() that returns empty for denied UDFs. This mimics "function not found" behavior.

3. **KVStoreProvider type compatibility**
   - What we know: Stores use `Provider<KVStoreProvider>`. CatalogServiceImpl has `Provider<LegacyKVStoreProvider>`.
   - What's unclear: Whether these are the same runtime type or require separate registration.
   - Recommendation: Register RbacService in DACDaemonModule using the same `KVStoreProvider` that is registered in the registry, separate from CatalogServiceImpl's legacy provider.

4. **CreateOrUpdateViewHandler ALTER vs CREATE_VIEW**
   - What we know: The handler calls `validatePrivilege(resolvedViewPath, SqlGrant.Privilege.ALTER)` at line 105. ENFC-08 requires CREATE_VIEW.
   - What's unclear: Whether changing this handler affects EE behavior.
   - Recommendation: Modify the handler to use CREATE_VIEW. In OSS without RBAC enabled, validatePrivilege is a no-op, so the change is harmless. If EE concern exists, make the change conditional on a feature flag.

## Sources

### Primary (HIGH confidence)
- `CatalogImpl.java` -- constructor (lines 219-276), validatePrivilege (line 2767), resolveCatalog methods (lines 1558-1620)
- `CatalogServiceImpl.java` -- createCatalog factory (lines 958-988), DremioConfig field (line 149), DACDaemonModule binding (lines 1025-1048)
- `RbacService.java` -- hasPrivilege signature (lines 113-146), constructor (line 70)
- `SystemUser.java` -- isSystemUserName() (line 45), SYSTEM_USERNAME = "$dremio$"
- `DremioConfig.java` -- RBAC_ENABLED constant (line 152)
- `SourceAccessChecker.java` -- secureIfNeeded() system user bypass (lines 610-614)
- `SqlGrant.java` -- Privilege enum (lines 48-78), GrantType enum (lines 85-96)
- `TestCatalogImpl.java` -- test pattern with 16 mocked constructor params (lines 119-173)
- `ViewExpander.java` -- definer-rights via builder.withUser(viewOwner) (line 152)
- `dremio-reference.conf` -- `services.rbac.enabled: false` (line 351-352)
- `DACDaemonModule.java` -- registry.bind() pattern for CatalogService (lines 1025-1048)

### Secondary (MEDIUM confidence)
- `CreateOrUpdateViewHandler.java` -- ALTER privilege usage at line 105 (needs change to CREATE_VIEW)
- `NormalHandler.java` -- confirms no validatePrivilege call for SELECT queries

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all components verified by reading source code
- Architecture: HIGH -- CatalogImpl construction path fully traced, DI pattern confirmed
- Pitfalls: HIGH -- all identified through concrete code analysis
- DI wiring: HIGH -- registry.bind() pattern confirmed in DACDaemonModule
- SELECT enforcement gap: MEDIUM -- confirmed NormalHandler lacks validatePrivilege(SELECT), solution requires design decision
- UDF enforcement gap: MEDIUM -- getFunctions() path partially traced, solution requires design decision

**Research date:** 2026-02-18
**Valid until:** 2026-03-18 (Dremio OSS code is stable; no external library updates)
