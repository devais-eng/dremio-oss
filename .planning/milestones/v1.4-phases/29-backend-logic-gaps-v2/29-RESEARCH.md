# Phase 29: Backend Logic Gaps v2 - Research

**Researched:** 2026-03-11
**Domain:** Dremio OSS RBAC enforcement — catalog API, system tables, privilege resolution
**Confidence:** HIGH

## Summary

Phase 29 closes the three remaining v1.4 LOGIC requirements that Phase 25 attempted but did not fully resolve in production. All three gaps have clear, isolated root causes confirmed through direct code inspection — no exploratory work is needed.

LOGIC-01: The v3 catalog API sidebar count (DetailType.datasetCount in CatalogServiceHelper) calls `namespaceService.getDatasetCount()` directly, bypassing the `filterByVisibility()` method that the v3 children listing uses. The fix is a narrow enum method replacement — compute count from RBAC-filtered children instead of the raw namespace count.

LOGIC-02: Phase 25-02 correctly implemented RBAC row filtering in `SystemTableScanCreator`, but production Dremio uses `SysFlightScanCreator` (ENABLE_SYSFLIGHT_SOURCE=true is the default). The row-filtering methods exist and are verified — they need to be ported verbatim to the production scan creator. The fix is a surgical port plus the same `accessControlListingManager instanceof RbacService` cast pattern.

LOGIC-03: `CatalogImpl.validateCreateViewPrivilege()` calls `rbacService.hasPrivilege(userName, "CREATE_VIEW", "VDS", containerPath)` but the REST API stores CREATE_VIEW grants with `objectType="SPACE"`. The key mismatch (`VDS` vs `SPACE`) means the privilege lookup always misses. The fix is a one-line string change.

**Primary recommendation:** All three fixes are surgical, single-file changes. Plan as three separate, independent tasks — they share no code paths and can be implemented and committed atomically.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Dataset count (LOGIC-01)
- Reuse Phase 25's approach: compute count from RBAC-filtered children list instead of raw `namespaceService.getDatasetCount()`
- Apply to the v3 `datasetCount` detail type in `CatalogServiceHelper.applyAdditionalInfoToContainers()`
- Phase 25 already established this pattern in the v2 `getSpace()` path

#### Sys table access (LOGIC-02)
- Policy unchanged from Phase 25: `sys.roles` admin-only; `sys.privileges` and `sys.membership` open to all with row scoping
- Row-filtering logic in `SystemTableScanCreator.filterRbacSystemTableByUser()` is correct — fix is in table resolution, not access policy
- Filter rules: `sys.membership` by `member_name = query user`; `sys.privileges` by `grantee in user's roleIds set`

#### CREATE_VIEW grant semantics (LOGIC-03)
- Root cause: `CatalogImpl.validateCreateViewPrivilege()` uses `objectType="VDS"` but grants stored via REST API use `objectType="SPACE"` — different KV keys
- Secondary mismatch: `CatalogImpl` uses parent folder path (`myspace.myfolder`) while `CatalogServiceHelper` uses top-level space name (`myspace`)
- Auto-grant logic (SELECT/ALTER/DROP on created view) uses `objectType="VDS"` and works correctly — only CREATE_VIEW resolution is broken

### Claude's Discretion
- Choice of canonical objectType for CREATE_VIEW (standardize to whichever is most consistent with existing grant patterns)
- How to fix sys table resolution for non-admin (plugin-layer vs catalog-layer vs planner-layer fix)
- Whether to add an RBAC-aware count method to namespaceService or inline the filtered count

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| LOGIC-01 | Dataset count shown next to space names reflects only RBAC-visible datasets, not all datasets (Issue #4) | `DetailType.datasetCount.addInfo()` in CatalogServiceHelper replaces raw namespace call with filtered children count |
| LOGIC-02 | `sys.membership` and `sys.privileges` system tables are queryable by non-admin users with row-scoped data (Issue #5) | Port `filterRbacSystemTableByUser()` from SystemTableScanCreator to SysFlightScanCreator (production path) |
| LOGIC-03 | After creating a view via Save as View, the creator is automatically granted SELECT, ALTER, and DROP privileges on the new view (Issue #7) | Fix `"VDS"` to `"SPACE"` objectType in `validateCreateViewPrivilege()` so the privilege lookup matches stored grant key |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| RbacService | project-internal | Three-way null guard, privilege lookup, admin check | All RBAC enforcement in the codebase uses this interface |
| DremioConfig | project-internal | Feature flag lookup (`RBAC_ENABLED`, `RBAC_PDS_ENABLED`) | All RBAC guards check this before enforcing |
| AccessControlListingManager | project-internal | Plugin-layer interface that RbacService implements | Only way to reach RbacService from `SysFlightStoragePlugin.getSabotContext()` |

### No New Dependencies
All three fixes use existing project infrastructure. No new libraries, no Maven changes.

## Architecture Patterns

### Established Pattern: Three-Way Null Guard
Every RBAC guard in the codebase follows this exact sequence:

```java
// Source: SystemTableScanCreator.java, CatalogServiceHelper.filterByVisibility()
if (rbacService == null
    || dremioConfig == null
    || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
  return <passthrough>;  // pre-RBAC open behavior
}
String userName = <get current user>;
if (rbacService.isAdminMember(userName)) {
  return <passthrough>;  // admin sees all
}
// apply user-scoped logic here
```

### Established Pattern: instanceof Cast for Plugin Context
`PluginSabotContext` exposes `getAccessControlListingManager()` but not `getRbacService()`. The Phase 25-02 pattern (confirmed working) casts via instanceof:

```java
// Source: SystemTableScanCreator.java lines 78-86
AccessControlListingManager aclManager = sabotContext.getAccessControlListingManager();
if (aclManager == null
    || !(aclManager instanceof RbacService)
    || dremioConfig == null
    || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
  return iterator;
}
RbacService rbacService = (RbacService) aclManager;
```

### Established Pattern: RBAC-Aware Count from Children
Phase 25 plan 01 established the pattern for v2 `getSpace()`: fetch children, apply `filterByVisibility()`, count the result. The same pattern applies to LOGIC-01 in the v3 path.

```java
// Pattern established by Phase 25-01 in SpaceResource.getSpace()
// Children already fetched for RBAC filtering
List<NameSpaceContainer> visibleChildren = filterByVisibility(
    namespaceService.list(new NamespaceKey(builder.getPath())));
int count = (int) visibleChildren.stream()
    .filter(c -> c.getType() == NameSpaceContainer.Type.DATASET)
    .count();
```

In the `DetailType.datasetCount.addInfo()` context, `helper.namespaceService` and `helper.securityContext` are accessible. The `filterByVisibility()` method is private to `CatalogServiceHelper` — it must be called as `helper.filterByVisibility(children)` or the logic must be inlined.

### Anti-Patterns to Avoid
- **Patching the wrong scan creator again**: Always verify which plugin is active. `ENABLE_SYSFLIGHT_SOURCE=true` (default) → `SysFlightScanCreator`. Never touch `SystemTableScanCreator` for production fixes.
- **Adding getRbacService() to PluginSabotContext**: Rejected in Phase 25-02. Cross-cutting interface change avoided by `instanceof` cast.
- **Changing resolveRbacObjectType() for CREATE_VIEW**: That helper method maps privilege to objectType for VDS-path checks, not for container-level checks. Modifying it would break other privilege lookups. The fix is local to `validateCreateViewPrivilege()`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Sys table row filtering | Custom filter logic in SysFlightScanCreator | Port exact methods from SystemTableScanCreator | Code verified and committed in Phase 25-02; filter logic handles MEMBERSHIP and PRIVILEGES with correct field names |
| RBAC-aware children count | New method on NamespaceService | Call existing `namespaceService.list()` + `helper.filterByVisibility()` | filterByVisibility already handles all edge cases: VDS visibility, PDS enforcement, function visibility, folder reachability |
| Username extraction in scan context | User.getPrincipal().getName() | `config.getProps().getUserName()` | SysFlightSubScan.getProps() carries the submitting user's name — same API that SystemTableScanCreator uses |

## Common Pitfalls

### Pitfall 1: Wrong Scan Creator (LOGIC-02 root cause of Phase 25 failure)
**What goes wrong:** Patching `SystemTableScanCreator` when `SysFlightScanCreator` is the production path.
**Why it happens:** Both classes exist and look symmetric. `ENABLE_SYSFLIGHT_SOURCE` defaults to true, selecting `SysFlightPluginConf` and therefore `SysFlightScanCreator`.
**How to avoid:** Fix `SysFlightScanCreator.java` in `plugins/sysflight/`. Confirm: `CatalogServiceImpl` calls `new SysFlightPluginConf()` when option is true (the default).
**Warning signs:** UAT shows "Object not found" despite row-filtering code being present in SystemTableScanCreator.

### Pitfall 2: IllegalAccessException from MEMBERSHIP.getIterator() on null accessControlListingManager
**What goes wrong:** `SystemTable.MEMBERSHIP.getIterator()` calls `sabotContext.getAccessControlListingManager()` and throws `IllegalAccessException` if the result is null (e.g., non-coordinator executors).
**Why it happens:** `accessControlListingManager` is only wired on coordinator nodes. Executor nodes may have null.
**How to avoid:** The three-way null guard (checking `aclManager == null`) MUST come BEFORE calling `legacyTable.get().getIterator()`. In `SysFlightScanCreator`, the iterator is currently called unconditionally — the fix must restructure to guard before iterator creation, or wrap the getIterator call.
**Warning signs:** NullPointerException or IllegalAccessException stack traces from executor nodes during sys table scans.

### Pitfall 3: objectType Confusion in CatalogImpl (LOGIC-03)
**What goes wrong:** Multiple objectType values in play — `"VDS"` for dataset-level privileges, `"SPACE"` for container-level CREATE_VIEW grants, `"PDS"` for physical datasets.
**Why it happens:** `resolveRbacObjectType()` maps CREATE_VIEW to `"VDS"` (line 2906) which is correct for some contexts but wrong for the CREATE_VIEW check, where the grant target is the container (space/folder), not the new view.
**How to avoid:** Change ONLY `validateCreateViewPrivilege()` at line 2885. Do NOT change `resolveRbacObjectType()`. The objectType `"SPACE"` is what the REST API stores when granting CREATE_VIEW on a space.
**Warning signs:** CREATE_VIEW check always returns denied (false) even after a valid GRANT.

### Pitfall 4: Container Path vs Space Name Mismatch (LOGIC-03 secondary issue)
**What goes wrong:** `validateCreateViewPrivilege()` uses `viewKey.getParent().getSchemaPath()` which produces `myspace.myfolder` for nested views, but grants may be stored against top-level space name `myspace`.
**Why it happens:** `hasPrivilege()` does exact string matching on objectPath in the grant KV key. If the GRANT was issued on `myspace` but the check uses `myspace.myfolder`, no match.
**How to avoid:** Verify the grant storage path from `CatalogServiceHelper`. The REST API stores CREATE_VIEW grants using the space root name. The path passed to `validateCreateViewPrivilege()` must match. Research finding: need to confirm whether inherited grants work or only exact path match — use the top-level container name consistent with how REST API issues the grant.
**Warning signs:** CREATE_VIEW check still fails for views in folders even after the objectType fix.

## Code Examples

### SysFlightScanCreator — Current (BROKEN) Code
```java
// Source: plugins/sysflight/src/main/java/com/dremio/plugins/sysflight/SysFlightScanCreator.java
// Lines 38-63 — no RBAC filtering applied
public ProducerOperator create(FragmentExecutionContext fec, OperatorContext context, SysFlightSubScan config)
    throws ExecutionSetupException {
  final SysFlightStoragePlugin plugin = fec.getStoragePlugin(config.getPluginId());
  final Optional<SystemTable> legacyTable =
      plugin.getLegacyDataset(new EntityPath(config.getDatasetPath()));
  final RecordReader reader;
  if (legacyTable.isPresent()) {
    reader = new PojoRecordReader(
        legacyTable.get().getPojoClass(),
        legacyTable.get().getIterator(plugin.getSabotContext(), context),  // throws if aclManager null
        config.getColumns(),
        context.getTargetBatchSize());
  } else {
    reader = new SysFlightRecordReader(...);
  }
  return new ScanOperator(fec, config, context, RecordReaderIterator.from(reader));
}
```

### SystemTableScanCreator — Filtering Pattern to Port (VERIFIED WORKING)
```java
// Source: sabot/kernel/src/main/java/com/dremio/exec/store/sys/SystemTableScanCreator.java
// Lines 68-137 — full pattern to port to SysFlightScanCreator
private Iterator<?> filterRbacSystemTableByUser(
    Iterator<?> iterator, SystemTable table, SystemStoragePlugin plugin, SystemSubScan config) {
  if (table != SystemTable.PRIVILEGES && table != SystemTable.MEMBERSHIP) {
    return iterator;
  }
  PluginSabotContext sabotContext = plugin.getSabotContext();
  DremioConfig dremioConfig = sabotContext.getDremioConfig();
  AccessControlListingManager aclManager = sabotContext.getAccessControlListingManager();
  if (aclManager == null
      || !(aclManager instanceof RbacService)
      || dremioConfig == null
      || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    return iterator;
  }
  RbacService rbacService = (RbacService) aclManager;
  String userName = config.getProps().getUserName();
  if (userName == null || rbacService.isAdminMember(userName)) {
    return iterator;
  }
  if (table == SystemTable.MEMBERSHIP) {
    return filterMembershipByUser(iterator, userName);
  } else {
    Set<String> userRoleIds = rbacService.getUserRoleIds(userName);
    return filterPrivilegesByUserRoles(iterator, userRoleIds);
  }
}
```

### validateCreateViewPrivilege — Current (BROKEN) Code
```java
// Source: sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java line 2885
// BUG: "VDS" should be "SPACE" to match REST API grant storage
if (!rbacService.hasPrivilege(userName, "CREATE_VIEW", "VDS", containerPath)) {
```

### CatalogServiceHelper.filterByVisibility — Reusable RBAC Filter
```java
// Source: CatalogServiceHelper.java lines 3380-3394
private List<NameSpaceContainer> filterByVisibility(List<NameSpaceContainer> children) {
  if (rbacService == null
      || dremioConfig == null
      || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    return children;
  }
  String userName = securityContext.getUserPrincipal().getName();
  if (rbacService.isAdminMember(userName)) {
    return children;
  }
  Set<String> accessiblePaths = rbacService.getAccessibleObjectPaths(userName);
  return children.stream()
      .filter(c -> isVisibleToUser(c, userName, accessiblePaths))
      .collect(Collectors.toList());
}
```

### DetailType.datasetCount — Current (BROKEN) Code
```java
// Source: CatalogServiceHelper.java lines 222-245
datasetCount {
  @Override
  Stream<CatalogItem.Builder> addInfo(Stream<CatalogItem.Builder> items, final CatalogServiceHelper helper) {
    return items.map(builder -> {
      try {
        final BoundedDatasetCount datasetCount = helper.namespaceService.getDatasetCount(
            new NamespaceKey(builder.getPath()),
            BoundedDatasetCount.SEARCH_TIME_LIMIT_MS,
            BoundedDatasetCount.COUNT_LIMIT_TO_STOP_SEARCH);
        return builder
            .setDatasetCount(datasetCount.getCount())
            .setDatasetCountBounded(datasetCount.isCountBound() || datasetCount.isTimeBound());
      } catch (NamespaceException e) {
        throw new RuntimeException(e);
      }
    });
  }
},
```

For the fix, the `DetailType.datasetCount.addInfo()` method must replace the raw `getDatasetCount()` call with:
1. `helper.namespaceService.list(new NamespaceKey(builder.getPath()))` to get children
2. `helper.filterByVisibility(children)` to apply RBAC filtering (note: `filterByVisibility` is private — must either make it package-private or inline the guard logic)
3. Count DATASET-typed containers from the filtered result

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No sys table filtering | `SystemTableScanCreator` row filtering (Phase 25-02) | 2026-03-11 commit 143e4add1 | Only fixes legacy plugin path (ENABLE_SYSFLIGHT_SOURCE=false) |
| Admin-only sys.privileges | Open to all with row scoping | Phase 25-02 | Policy correct, production path still broken |
| Raw namespaceService.getDatasetCount() in v3 | Still raw (unfixed) | — | v2 path fixed in Phase 25-01; v3 path missed |

**Deprecated/outdated:**
- `SystemTableScanCreator`: Used only when `ENABLE_SYSFLIGHT_SOURCE=false`. Do NOT treat this as the production code path.

## Open Questions

1. **LOGIC-03 secondary: path granularity for CREATE_VIEW check**
   - What we know: `validateCreateViewPrivilege()` uses `viewKey.getParent().getSchemaPath()` which gives `myspace.myfolder` for nested views. REST API stores grants using the space root name `myspace`.
   - What's unclear: Does `rbacService.hasPrivilege()` support hierarchical/inherited path matching, or is it exact-match only? If exact-match only, the containerPath passed must match the grant path exactly.
   - Recommendation: Check `RbacConfig.grantKey()` and `hasPrivilege()` implementation. If exact-match only, the fix must use `viewKey.getRoot()` (top-level space name) rather than `viewKey.getParent().getSchemaPath()`.

2. **LOGIC-01: filterByVisibility visibility in enum context**
   - What we know: `filterByVisibility()` is a private method of `CatalogServiceHelper`. The `DetailType.datasetCount.addInfo()` enum method receives `helper` as a `CatalogServiceHelper` parameter and can call private methods because the enum is a nested type of `CatalogServiceHelper`.
   - What's unclear: Confirm Java access rules — inner enum of a class CAN call private methods of the enclosing class instance. If confirmed, `helper.filterByVisibility(children)` is valid.
   - Recommendation: Verify access compiles; if not, change `filterByVisibility` to package-private (remove `private`).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4 + Mockito (Maven Surefire) |
| Config file | none — tests run via Maven |
| Quick run command | `mvn test -pl dac/backend -Dtest=CatalogServiceHelperTest -q` |
| Full suite command | `mvn test -pl dac/backend,sabot/kernel,plugins/sysflight -q` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| LOGIC-01 | `DetailType.datasetCount.addInfo()` returns RBAC-filtered count for non-admin | unit | `mvn test -pl dac/backend -Dtest=CatalogServiceHelperTest -q` | Wave 0 |
| LOGIC-02 | `SysFlightScanCreator` applies row filtering for non-admin querying sys.membership | unit | `mvn test -pl plugins/sysflight -Dtest=SysFlightScanCreatorTest -q` | Wave 0 |
| LOGIC-02 | `SysFlightScanCreator` applies row filtering for non-admin querying sys.privileges | unit | `mvn test -pl plugins/sysflight -Dtest=SysFlightScanCreatorTest -q` | Wave 0 |
| LOGIC-02 | Admin user sees all rows in sys.membership and sys.privileges | unit | `mvn test -pl plugins/sysflight -Dtest=SysFlightScanCreatorTest -q` | Wave 0 |
| LOGIC-02 | RBAC disabled returns all rows unfiltered | unit | `mvn test -pl plugins/sysflight -Dtest=SysFlightScanCreatorTest -q` | Wave 0 |
| LOGIC-03 | `validateCreateViewPrivilege()` succeeds when SPACE-type CREATE_VIEW grant exists | unit | `mvn test -pl sabot/kernel -Dtest=CatalogImplTest -q` | Wave 0 |
| LOGIC-03 | `validateCreateViewPrivilege()` throws when no CREATE_VIEW grant exists | unit | `mvn test -pl sabot/kernel -Dtest=CatalogImplTest -q` | Wave 0 |

### Sampling Rate
- **Per task commit:** quick run command for the module being changed
- **Per wave merge:** full suite command
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `plugins/sysflight/src/test/java/com/dremio/plugins/sysflight/SysFlightScanCreatorTest.java` — covers LOGIC-02 (non-admin row filtering in production scan creator)
- [ ] `dac/backend/src/test/java/com/dremio/dac/service/catalog/CatalogServiceHelperTest.java` — covers LOGIC-01 (check if file already exists with relevant test methods, or add new test method)
- [ ] `sabot/kernel/src/test/java/com/dremio/exec/catalog/CatalogImplTest.java` — covers LOGIC-03 (check if file already exists, or add CREATE_VIEW objectType test)

## Sources

### Primary (HIGH confidence)
- Direct code inspection: `CatalogServiceHelper.java` lines 221-244, 282-283, 3380-3420 — LOGIC-01 exact broken code and reusable filter
- Direct code inspection: `SysFlightScanCreator.java` lines 38-63 — LOGIC-02 missing filter
- Direct code inspection: `SystemTableScanCreator.java` lines 68-137 — LOGIC-02 correct pattern to port
- Direct code inspection: `CatalogImpl.java` lines 2874-2894, 2901-2912 — LOGIC-03 broken objectType
- Direct code inspection: `CatalogServiceImpl.java` lines 295-363 — confirms ENABLE_SYSFLIGHT_SOURCE=true default
- Phase 25-02 SUMMARY.md — confirms Phase 25-02 modified SystemTableScanCreator (not SysFlightScanCreator)

### Secondary (MEDIUM confidence)
- UAT_RESULTS.md — confirms LOGIC-01, LOGIC-02, LOGIC-03 all fail at runtime after Phase 25; UAT timestamp 17:17 after Phase 25-02 commit at 16:03

### Tertiary (LOW confidence)
- None

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — verified through direct code inspection, no external libraries involved
- Architecture: HIGH — three-way null guard and instanceof cast patterns verified in committed code
- Pitfalls: HIGH — root causes verified by reading both the broken code and the UAT failure evidence
- LOGIC-01 fix approach: HIGH — filterByVisibility() exists and is called; count-from-children pattern established in Phase 25-01
- LOGIC-02 fix approach: HIGH — SysFlightScanCreator is confirmed production path; filtering code exists in SystemTableScanCreator and is directly portable
- LOGIC-03 fix approach: HIGH for objectType change; MEDIUM for path granularity question (requires one additional check of hasPrivilege implementation)

**Research date:** 2026-03-11
**Valid until:** 2026-04-10 (stable internal codebase, no external dependencies)
