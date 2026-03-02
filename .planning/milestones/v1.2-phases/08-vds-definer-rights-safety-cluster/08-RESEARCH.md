# Phase 8: VDS Definer Rights Safety Cluster - Research

**Researched:** 2026-02-21
**Domain:** Java — ViewExpander, CatalogEntityOwnershipImpl, ViewExpansionContext, PlanCache, DatasetManager, CatalogImpl
**Confidence:** HIGH (all findings confirmed by direct code inspection)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| DEFN-01 | User can query a VDS they have SELECT on, even when underlying tables are not directly accessible to them (definer rights) | `ViewExpander.expandViewInternal()` already switches catalog identity to `viewOwner` via `builder.withUser(viewOwner)`. Mechanism works. Root cause: `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` returns `Optional.empty()` for VDS — fix one branch of the switch to return the owner. |
| DEFN-02 | VDS expansion runs under the last modifier's privileges, not the querying user's | Same fix as DEFN-01 populates `ViewTable.viewOwner`; `ViewExpander.expandViewInternal()` already calls `builder.withUser(viewOwner)` which creates a new CatalogImpl under the definer's identity. `isRbacDeniedForVds()` inside that scoped catalog uses `userName = definerName`. Comes for free once DEFN-01 owner is non-null. |
| DEFN-03 | VDS-over-VDS chains with different owners resolve correctly at each level | Each `ViewTable` carries its own `viewOwner`; `expandViewInternal()` creates a new builder/catalog scoped per view. The recursion is per-view, not per-query. Comes for free once DEFN-01 is fixed. |
| DEFN-04 | Plan cache correctly scopes to definer identity chain (different definers produce different cache entries) | `PlanCacheUtils.generateCacheKey()` only includes `context.getQueryUserName()` (when `isPlanCacheEnableSecuredUserBasedCaching()` is true), not the definer chain. Two queries with different definers but same SQL/schema produce the same cache key — plan cache must be disabled for queries involving VDS expansion with non-query-user definers, OR the definer chain must be included in the hash. |
| DEFN-05 | Deleted view owner causes an explicit permission error, not a silent fallback to query user | `ViewExpander.expandViewInternal()` line 128-133 catches `UserNotFoundException` and falls back to `viewExpansionContext.getQueryUser()` (the query user). This is the opposite of the required behavior. Fix: when RBAC is enabled and viewOwner is non-null and UserNotFoundException is caught, throw `UserException.planError()` with "View owner no longer exists: {viewPath}" message. |
| DEFN-06 | Cyclic VDS chains produce a clear validation error, not a StackOverflow | `ViewExpansionContext.userTokens` tracks token count by owner identity but NOT by view path. A cyclic chain (A→B→A) recurses indefinitely without detection. Fix: add a `Set<NamespaceKey>` (in-expansion path tracker) to `ViewExpansionContext`; in `reserveViewExpansionToken()`, detect if the view path is already in the set and throw `UserException.validationError()` with "Cyclic view dependency detected" message. |
</phase_requirements>

---

## Summary

Phase 8 implements VDS definer rights — view expansion under the last modifier's identity — plus six safety guards to prevent the pitfalls that make naive definer rights dangerous. The research confirms that Dremio already has the core definer rights infrastructure in place: `ViewExpander.expandViewInternal()` already switches catalog identity to the view owner via `builder.withUser(viewOwner)`, and `CatalogImpl.resolveCatalog(CatalogIdentity subject)` creates a new catalog scoped to the definer's identity. The enforcement gap is that `viewOwner` is always null for VDS because `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` returns `Optional.empty()` for `DatasetType.VIRTUAL_DATASET`, and the owner IS stored in `DatasetConfig.owner` when views are saved.

Once the owner bug is fixed, DEFN-01, DEFN-02, and DEFN-03 all work because the existing `ViewExpander → builder.withUser(viewOwner) → resolveCatalog(subject) → new CatalogImpl(definerIdentity)` chain propagates the definer identity into all nested `isRbacDeniedForVds()` checks. The remaining requirements (DEFN-04 through DEFN-06) each require a separate targeted fix: plan cache keying on definer chain identity, the UserNotFoundException fallback inversion, and cycle detection via a path set in `ViewExpansionContext`.

**Primary recommendation:** Fix in four parts: (1) `CatalogEntityOwnershipImpl` returns owner for VDS; (2) plan cache either disabled for definer-rights queries or keyed on owner chain; (3) `ViewExpander` throws explicit error instead of silently falling back on deleted owner; (4) `ViewExpansionContext` gains path-tracking set for cycle detection.

---

## Standard Stack

### Core (no new dependencies — everything already exists)

| Component | Location | Purpose | Why Relevant |
|-----------|----------|---------|-------------|
| `CatalogEntityOwnershipImpl` | `sabot/kernel/.../catalog/CatalogEntityOwnershipImpl.java` | Returns the owner of a catalog entity | Currently returns `Optional.empty()` for VDS — this is the root cause of DEFN-01/02/03 failure |
| `ViewExpander` | `sabot/kernel/.../planner/sql/ViewExpander.java` | Expands a VDS into its relational tree, switching to the view owner's catalog context | Contains the definer rights mechanism (`expandViewInternal`) and the DEFN-05 bug (UserNotFoundException fallback) |
| `ViewExpansionContext` | `sabot/kernel/.../ops/ViewExpansionContext.java` | Tracks which view owners are currently being expanded; issues/releases expansion tokens | Must gain cycle detection (DEFN-06): add a `Set<NamespaceKey>` path tracker |
| `ViewTable` | `sabot/kernel/.../planner/logical/ViewTable.java` | DremioTable for views, carries `@Nullable CatalogIdentity viewOwner` | Once owner is non-null, `ViewExpander` uses it to switch to the definer's catalog identity |
| `DatasetManager.createTableFromVirtualDataset()` | `sabot/kernel/.../catalog/DatasetManager.java:919` | Constructs `ViewTable` from `DatasetConfig`; passes owner from `getEntityOwner()` | `getEntityOwner()` returns null for VDS today because of `CatalogEntityOwnershipImpl` bug |
| `DatasetManager.getEntityOwner()` | `sabot/kernel/.../catalog/DatasetManager.java:1247` | Wraps `catalogEntityOwnership.getCatalogEntityOwner()` in RequestContext | Uses `CatalogEntityOwnershipImpl`; returns null for VDS |
| `CatalogImpl.resolveCatalog(CatalogIdentity)` | `sabot/kernel/.../catalog/CatalogImpl.java:1646` | Creates a new CatalogImpl scoped to the given identity | The definer catalog switch: creates a fresh CatalogImpl with `userName = definer.getName()` |
| `PlanCacheUtils.generateCacheKey()` | `sabot/kernel/.../planner/plancache/PlanCacheUtils.java:143` | Generates the plan cache key (SHA-256 hash) | Does NOT include the definer chain — DEFN-04 gap |
| `DatasetsUtil.toVirtualDatasetVersion()` | `dac/backend/.../util/DatasetsUtil.java:137` | Converts VirtualDatasetUI → DatasetConfig; sets `datasetConfig.setOwner(virtualDatasetUI.getOwner())` | Confirms owner IS written to DatasetConfig on save |
| `DatasetVersionResource.save()` | `dac/backend/.../explore/DatasetVersionResource.java:396` | Sets `vds.setOwner(securityContext.getUserPrincipal().getName())` | Confirms owner IS populated from the logged-in user on VDS create/update |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `CatalogUser` | (existing) | `CatalogIdentity` implementation for username-based identities | Use when constructing the definer identity from `DatasetConfig.getOwner()` |
| `UserException.planError()` | (existing Dremio) | Throws a plan-level error | Use for DEFN-05 deleted-owner error (same error type as view expansion failures) |
| `UserException.validationError()` | (existing Dremio) | Throws a validation error | Use for DEFN-06 cyclic chain error (same pattern as `validatePrivilege()`) |

---

## Architecture Patterns

### How Definer Rights Work (existing infrastructure — HIGH confidence)

The full call chain for view expansion under definer identity:

```
1. CatalogImpl.getTable(key) → isRbacDeniedForVds(table, key) [checks caller's SELECT on view V]
2. ViewTable.toRel() → throws UnsupportedOperationException (intentional)
3. ConvertedViewTable.toRel() → viewExpander.expandView(viewTable)
4. ViewExpander.expandViewInternal(viewTable):
   a. viewOwner = viewTable.getViewOwner()   // <-- currently null for VDS (the bug)
   b. token = viewExpansionContext.reserveViewExpansionToken(viewOwner)
   c. builder.withUser(viewOwner)            // switches to definer's identity
   d. catalog.resolvePlannerCatalog(viewOwner)
   e. metadataCatalog.resolveCatalog(viewOwner)
   f. new CatalogImpl(options.cloneWith(viewOwner,...))  // definer-scoped catalog
5. Inside definer-scoped catalog:
   - isRbacDeniedForVds(nestedTable, key) checks userName = definer.getName()
   - If definer has SELECT on underlying table T → access allowed
   - If definer lacks SELECT → denied (even if query user would have access)
```

Once `viewOwner` is non-null (DEFN-01 fix), this entire chain functions correctly.

### Pattern 1: CatalogEntityOwnershipImpl Fix (DEFN-01/02/03)

**What:** Add VDS owner return to `CatalogEntityOwnershipImpl.getCatalogEntityOwner()`.

**Current code (broken):**
```java
// CatalogEntityOwnershipImpl.java:50-53 — CURRENT (returns empty for VDS):
case DATASET:
  final DatasetConfig dataset = nameSpaceContainer.getDataset();
  if (dataset.getType() == DatasetType.VIRTUAL_DATASET) {
    return Optional.empty();   // BUG: should return the owner
  } else {
    return Optional.of(new CatalogUser(dataset.getOwner()));
  }
```

**Fixed code:**
```java
// CatalogEntityOwnershipImpl.java — AFTER FIX:
case DATASET:
  final DatasetConfig dataset = nameSpaceContainer.getDataset();
  if (dataset.getType() == DatasetType.VIRTUAL_DATASET) {
    // Return the last modifier's identity; null owner is a legitimate state (legacy VDS)
    String owner = dataset.getOwner();
    if (owner == null || owner.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new CatalogUser(owner));
  } else {
    return Optional.of(new CatalogUser(dataset.getOwner()));
  }
```

**Note:** `DatasetConfig.getOwner()` can return null for legacy VDS created before owner tracking was introduced. The null guard returns `Optional.empty()`, which propagates as `viewOwner = null` in `ViewTable`, which means `ViewExpander` keeps the query user's identity (legacy behavior, safe).

### Pattern 2: ViewExpander DEFN-05 Fix (Deleted Owner Error)

**What:** Replace the silent `UserNotFoundException` fallback with an explicit plan error when RBAC is enabled and the view owner is non-null.

**Current code (broken — silent fallback):**
```java
// ViewExpander.java:119-139 — CURRENT:
private RelRoot expandViewInternal(final ViewTable viewTable) {
  final @Nullable CatalogIdentity viewOwner = viewTable.getViewOwner();
  final String queryString = viewTable.getView().getSql();
  ViewExpansionContext.ViewExpansionToken token = null;
  try {
    token = viewExpansionContext.reserveViewExpansionToken(viewOwner);
    return expandRelNode(viewTable, viewOwner, queryString);
  } catch (RuntimeException e) {
    if (!(e.getCause() instanceof UserNotFoundException)) {
      throw e;
    }
    final CatalogIdentity delegatedUser = viewExpansionContext.getQueryUser();
    return expandRelNode(viewTable, delegatedUser, queryString);  // SILENT FALLBACK: WRONG
  } finally {
    if (token != null) {
      token.release();
    }
  }
}
```

**Fixed code:**
```java
// ViewExpander.java — AFTER FIX:
private RelRoot expandViewInternal(final ViewTable viewTable) {
  final @Nullable CatalogIdentity viewOwner = viewTable.getViewOwner();
  final String queryString = viewTable.getView().getSql();
  ViewExpansionContext.ViewExpansionToken token = null;
  try {
    token = viewExpansionContext.reserveViewExpansionToken(viewOwner);
    return expandRelNode(viewTable, viewOwner, queryString);
  } catch (RuntimeException e) {
    if (!(e.getCause() instanceof UserNotFoundException)) {
      throw e;
    }
    // DEFN-05: If RBAC is active and the view has a registered owner, the owner's deletion
    // must produce an explicit error, not a silent grant fallback to the query user.
    if (rbacEnabled && viewOwner != null) {
      throw UserException.planError(e)
          .message(
              "View owner '%s' no longer exists. Cannot expand view '%s'.",
              viewOwner.getName(), viewTable.getPath())
          .build(LOGGER);
    }
    // Legacy behavior (RBAC disabled or no owner stored): fall back to query user's identity.
    final CatalogIdentity delegatedUser = viewExpansionContext.getQueryUser();
    return expandRelNode(viewTable, delegatedUser, queryString);
  } finally {
    if (token != null) {
      token.release();
    }
  }
}
```

**Note:** `rbacEnabled` must be passed into `ViewExpander` from the planner configuration, or read from an `OptionManager` or a config boolean. The current `ViewExpander` constructor takes `SqlValidatorAndToRelContext.BuilderFactory`, `ViewExpansionContext`, and `AutoVDSFixer`. A fourth parameter `boolean rbacEnabled` is the cleanest approach. Alternatively, check a system property or pass the `DremioConfig` object.

### Pattern 3: ViewExpansionContext Cycle Detection (DEFN-06)

**What:** Add a `Set<String>` of in-expansion view paths to `ViewExpansionContext`; detect cycles in `reserveViewExpansionToken()`.

The current `reserveViewExpansionToken(CatalogIdentity viewOwner)` method signature does NOT include the view path. To detect cycles, we must either:
- **Option A (recommended):** Change signature to `reserveViewExpansionToken(CatalogIdentity viewOwner, NamespaceKey viewPath)` — adds the path parameter; tracks both owner and path.
- **Option B:** Add a parallel `Set<String>` and a separate method `checkForCycle(NamespaceKey viewPath)` called from `ViewExpander` before `reserveViewExpansionToken()`.

Option A requires updating all callers of `reserveViewExpansionToken()`. Let me check the callers:

The only caller is `ViewExpander.expandViewInternal()` at line 125. Option A is clean and minimal.

**Fixed ViewExpansionContext (relevant methods):**
```java
// ViewExpansionContext.java — add cycle detection:
private final Set<String> inExpansionPaths = new HashSet<>();

public ViewExpansionToken reserveViewExpansionToken(
    @Nullable CatalogIdentity viewOwner, NamespaceKey viewPath) {
  String pathKey = viewPath.getSchemaPath();
  if (!inExpansionPaths.add(pathKey)) {
    // Path already in expansion — cycle detected
    throw UserException.validationError()
        .message("Cyclic view dependency detected: view '%s' is referenced by itself.", pathKey)
        .buildSilently();
  }
  // ... existing token tracking logic ...
  return new ViewExpansionToken(viewOwner, viewPath);
}

private void releaseViewExpansionToken(ViewExpansionToken token) {
  inExpansionPaths.remove(token.viewPath.getSchemaPath());
  // ... existing owner tracking release ...
}

public class ViewExpansionToken {
  private final CatalogIdentity viewOwner;
  private final NamespaceKey viewPath;  // added
  // ...
}
```

**Fixed ViewExpander call site:**
```java
// ViewExpander.expandViewInternal() — add viewPath to token reservation:
token = viewExpansionContext.reserveViewExpansionToken(viewOwner, viewTable.getPath());
```

### Pattern 4: Plan Cache Definer Chain (DEFN-04)

The plan cache key in `PlanCacheUtils.generateCacheKey()` (line 156-180) hashes: SQL string, rel node string, workload type, default schema, query user (optional), options, and executor count. It does NOT hash the definer chain.

**The problem:** Two users querying the same view `V` (owned by `alice`) get the same plan cache entry. If user B later queries view `V2` (owned by `bob`) using the same SQL over different tables, and alice and bob happen to produce the same query plan text, they would share a cache entry. More subtly: if the definer is changed (via a CREATE OR REPLACE VIEW that updates the owner), the old cached plan — which was valid under the old definer's grants — would be served to new queries, bypassing the revocation check.

**Options:**
1. **Include definer chain in cache key hash** — accurate but complex (must collect all view owners encountered during planning).
2. **Disable plan cache for queries involving definer-rights views** — simple and safe; add a check like `containsDefinerRightsView(relNode)` in `supportPlanCache()`.
3. **Cache per (queryUser, viewOwnerChain) tuple** — equivalent to option 1 but phrased differently.

**Recommended approach (Option 2 — conservative):** Mark all queries touching at least one VDS with a non-query-user definer as uncacheable. Implementation: after view expansion, if any `ExpansionNode` in the rel tree has a `viewOwner != queryUser`, return false from `supportPlanCache()`. This is the safest approach for v1.2 and can be relaxed later.

**Alternative (Option 1 — performant):** Hash the ordered list of `(viewPath, definerName)` pairs from all `ExpansionNode`s in the rel tree. Adds to `generateCacheKey()` without changing `supportPlanCache()`.

### Pattern 5: `DatasetConfig.owner` Population Confirmation (MEDIUM confidence)

The owner write path for non-versioned VDS:
```
DatasetVersionResource.save()
  → vds.setOwner(securityContext.getUserPrincipal().getName())  [line 396]
  → datasetService.put(vds)
  → DatasetVersionMutator.put(ds)
  → toVirtualDatasetVersion(ds)
  → datasetConfig.setOwner(virtualDatasetUI.getOwner())  [DatasetsUtil.java:151]
  → catalog.addOrUpdateDataset(path, datasetConfig)
  → NamespaceService.addOrUpdateDataset() persists DatasetConfig
```

Confirmed: `DatasetConfig.owner` is written on VDS save as the logged-in user. The `DatasetConfig` is persisted to the namespace KV store. `DatasetManager.createTableFromVirtualDataset()` reads from this config at line 922: `getEntityOwner(CatalogEntityKey.of(fullPathList))` → `catalogEntityOwnership.getCatalogEntityOwner(key)` → `CatalogEntityOwnershipImpl.getCatalogEntityOwner(key)` which currently discards the owner for VDS. The fix is in `CatalogEntityOwnershipImpl`.

### Pattern 6: `isRbacDeniedForVds` Interaction with Definer Rights

When `ViewExpander.expandRelNode()` calls `builder.withUser(viewOwner).build()`, it creates a new `CatalogImpl` via `resolveCatalog(viewOwner)`. This new CatalogImpl has:
- `userName = viewOwner.getName()`
- Same `rbacService` (the singleton RBAC service)
- Same `dremioConfig` (feature flags)

When this definer-scoped CatalogImpl's `getTable(underlyingKey)` is called for a physical table `T`:
```java
// isRbacDeniedForVds() returns false for physical tables (non-ViewTable)
if (!(table instanceof ViewTable)) {
  return false;  // PDS always allowed (until Phase 10)
}
```

For Phase 8, physical tables (PDS) are not RBAC-enforced (Phase 10). So DEFN-01's "underlying physical table T is not directly accessible to B" scenario is about Phase 10 PDS enforcement interacting with definer rights, not about Phase 8 itself. In Phase 8, the only RBAC checks during expansion are on nested VDS (views within views).

**The DEFN-01 test scenario:** User B has SELECT on view V. View V's SQL references physical table T. When V is expanded under the definer's identity, `getTable(T)` is called in the definer-scoped catalog. Since T is a PDS, `isRbacDeniedForVds()` returns false (not a ViewTable). So the expansion succeeds regardless of whether B can access T directly. This is correct — definer rights on VDS over PDS work correctly even without Phase 10, because PDS is not RBAC-enforced yet. The test for DEFN-01 therefore requires user B to have SELECT on V and NOT have SELECT on V's underlying table T (another VDS). The scenario works once owner is populated.

### Anti-Patterns to Avoid

- **Null viewOwner fallback when RBAC is enabled:** When `DatasetConfig.owner` is null (legacy VDS), returning `Optional.empty()` from `getCatalogEntityOwner()` is correct — the view has no recorded definer and the query user's identity is used. But when the owner IS recorded and the user has been deleted, the fallback must be an error (DEFN-05), not silent continuation.
- **Re-checking caller's SELECT during expansion:** Do NOT check the query user's SELECT on nested tables during view expansion. The definer-scoped catalog already handles this. Double-checking would incorrectly block expansion when the definer has access but the caller does not.
- **Thread-safety assumption for ViewExpansionContext:** `ViewExpansionContext` is created per-query in `QueryContext`. It is NOT shared across queries. Thread safety is not a concern for the `inExpansionPaths` set. The prior concern (P27 in additional_context) about VolcanoPlanner threading at `ViewTable.toRel()` is confirmed LOW risk because `ConvertedViewTable.toRel()` either returns the cached `relNode` directly or re-calls `viewExpander.expandView()` — both paths are single-threaded within the planning phase.
- **Plan cache invalidation on owner update:** When a view is updated via `CREATE OR REPLACE VIEW`, the owner may change (the new modifier becomes the owner). The plan cache entry based on the old plan may persist. Disabling caching for definer-rights views (Option 2 for DEFN-04) avoids stale entries.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Catalog identity switching | Custom impersonation | `CatalogImpl.resolveCatalog(CatalogIdentity)` | Already creates a correctly scoped CatalogImpl; all RBAC checks in the new instance use the definer's userName |
| View expansion token management | Custom stack/counter | `ViewExpansionContext.ViewExpansionToken` | Existing token mechanism; just add path tracking |
| Deleted-user detection | Custom user lookup at expansion time | Catch existing `UserNotFoundException` from `builder.withUser()` path | UserService already throws `UserNotFoundException`; no extra lookup needed |
| Grant live checking | Grant snapshot/cache | Live KV store check in `RbacService.hasPrivilege()` | Already reads from KVStore on every call (no stale snapshot) — DEFN-03 "live check" requirement already satisfied by the existing grantStore.get() |

---

## Common Pitfalls

### Pitfall 1: viewOwner is always null for non-versioned VDS — the root cause

**What goes wrong:** All DEFN-01/02/03 semantics fail silently because `ViewTable.viewOwner` is null. `ViewExpander.expandViewInternal()` calls `builder.withUser(null)` which in `SqlValidatorAndToRelContext.Builder.withUser()` calls `catalog.resolvePlannerCatalog(null)` — the null identity means the query user's catalog is used.

**Why it happens:** `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` has an explicit early return for VIRTUAL_DATASET type (line 51): `if (dataset.getType() == DatasetType.VIRTUAL_DATASET) { return Optional.empty(); }`.

**How to avoid:** Fix the one branch in `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` for VIRTUAL_DATASET. Return `Optional.of(new CatalogUser(dataset.getOwner()))` when `dataset.getOwner()` is non-null. Add null/empty guard for legacy VDS.

**Warning signs:** Unit test for definer rights: user B with SELECT on V, definer (alice) with SELECT on T2 — if B can also expand V over T2 when alice has no grants, definer rights are not applied (viewOwner is still null).

### Pitfall 2: Silent UserNotFoundException fallback (DEFN-05)

**What goes wrong:** When a view owner's account is deleted, `ViewExpander.expandViewInternal()` catches `UserNotFoundException` and silently falls back to the query user's grants. The query user may or may not have access to underlying tables — the result is unpredictable and security-relevant.

**Why it happens:** The original `expandViewInternal()` was designed for a trust-based system (not RBAC), where falling back to the query user was safe. In RBAC mode, the fallback bypasses all definer-rights intent.

**How to avoid:** In the `catch (RuntimeException e)` block, check if `rbacEnabled && viewOwner != null` before falling back. If both are true, throw an explicit `UserException.planError()` instead of the delegated expansion.

**Warning signs:** A user who shouldn't have access to underlying tables can suddenly query a view after the view owner's account is deleted.

### Pitfall 3: Plan cache serves stale plans across different definer chains (DEFN-04)

**What goes wrong:** User B queries view V1 (owned by alice). Plan is cached. User C queries view V2 (owned by bob) with the same SQL. Plan cache returns alice's plan. If alice's plan involved specific access patterns (e.g., direct table scan that alice had SELECT on), user C may see results they shouldn't, or a permission denied that shouldn't apply to them.

**Why it happens:** Cache key doesn't include definer chain (only query user when `isPlanCacheEnableSecuredUserBasedCaching()` is true).

**How to avoid:** Either include the definer chain in the hash, or disable caching for any query that involves view expansion under a non-query-user definer. The conservative approach (disable for affected queries) is safer and simpler for v1.2.

**Warning signs:** Users see correct query results but from another user's view expansion. Can be detected by comparing `ExpansionNode.viewOwner` against `queryUser` in the plan.

### Pitfall 4: Cycle detection crashes with StackOverflowError (DEFN-06)

**What goes wrong:** Views A and B each reference the other. `ViewExpander.expandViewInternal()` for A calls `getTable(B)`, which triggers `expandViewInternal()` for B, which calls `getTable(A)`, etc. The JVM stack overflows.

**Why it happens:** `ViewExpansionContext` tracks token counts by owner identity, not by view path. A cycle involving views with different owners would be counted correctly per owner but the recursive expansion never terminates.

**How to avoid:** Add a `Set<String>` of view path strings to `ViewExpansionContext`. Before reserving a token, check if the view path is already in the set. If so, throw `UserException.validationError()`.

**Warning signs:** StackOverflowError in planning logs mentioning `expandViewInternal` or `ViewExpander`.

### Pitfall 5: `DatasetConfig.owner` null for legacy VDS

**What goes wrong:** Legacy VDS created before owner tracking was added have `DatasetConfig.owner = null`. After the `CatalogEntityOwnershipImpl` fix, calling `new CatalogUser(null)` would cause a NullPointerException in downstream code.

**Why it happens:** Owner field was not always set in earlier Dremio versions.

**How to avoid:** Guard with `if (owner == null || owner.isEmpty()) return Optional.empty();` before constructing `new CatalogUser(owner)`. This falls back to the query user's identity for legacy VDS, which is the pre-Phase-8 behavior.

**Warning signs:** NullPointerException in `CatalogUser.getName()` or `SchemaConfig` construction during view expansion.

---

## Code Examples

### Example 1: CatalogEntityOwnershipImpl fix for DEFN-01/02/03

```java
// File: sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java

// BEFORE (returns Optional.empty() for VDS — breaks definer rights):
case DATASET:
  final DatasetConfig dataset = nameSpaceContainer.getDataset();
  if (dataset.getType() == DatasetType.VIRTUAL_DATASET) {
    return Optional.empty();
  } else {
    return Optional.of(new CatalogUser(dataset.getOwner()));
  }

// AFTER (returns owner for VDS when recorded):
case DATASET:
  final DatasetConfig dataset = nameSpaceContainer.getDataset();
  final String owner = dataset.getOwner();
  if (owner == null || owner.isEmpty()) {
    return Optional.empty(); // Legacy VDS without recorded owner
  }
  return Optional.of(new CatalogUser(owner));
  // Note: PDS and VDS both return the owner now
```

### Example 2: ViewExpander DEFN-05 fix (deleted owner → explicit error)

```java
// File: sabot/kernel/src/main/java/com/dremio/exec/planner/sql/ViewExpander.java

// In the catch block of expandViewInternal():
} catch (RuntimeException e) {
  if (!(e.getCause() instanceof UserNotFoundException)) {
    throw e;
  }
  // DEFN-05: When RBAC is enabled and the view has a recorded owner,
  // a deleted owner must produce an explicit error (not a silent fallback).
  if (rbacEnabled && viewOwner != null) {
    throw UserException.planError(e)
        .message(
            "View owner '%s' no longer exists. Cannot expand view '%s'. "
                + "The view must be updated or ownership transferred before it can be queried.",
            viewOwner.getName(),
            viewTable.getPath().getSchemaPath())
        .build(LOGGER);
  }
  // Legacy/RBAC-disabled behavior: fall back to query user's identity.
  final CatalogIdentity delegatedUser = viewExpansionContext.getQueryUser();
  return expandRelNode(viewTable, delegatedUser, queryString);
}
```

**How to get `rbacEnabled` into ViewExpander:** The simplest approach is to add a `boolean rbacEnabled` field set in the constructor. `ViewExpander` is constructed in `PlannerCatalogImpl` and `QueryContext` paths — both have access to `DremioConfig`. Alternative: inject an `OptionManager` and check a system option. Either works; a constructor parameter is the least invasive.

### Example 3: ViewExpansionContext cycle detection (DEFN-06)

```java
// File: sabot/kernel/src/main/java/com/dremio/exec/ops/ViewExpansionContext.java

// Add field:
private final Set<String> inExpansionPaths = new HashSet<>();

// Change signature of reserveViewExpansionToken to accept view path:
public ViewExpansionToken reserveViewExpansionToken(
    @Nullable CatalogIdentity viewOwner, NamespaceKey viewPath) {

  // DEFN-06: Detect cycles before issuing a token
  String pathKey = viewPath.getSchemaPath();
  if (!inExpansionPaths.add(pathKey)) {
    throw UserException.validationError()
        .message(
            "Cyclic view dependency detected: view '%s' references itself "
                + "through a chain of view definitions.",
            pathKey)
        .buildSilently();
  }

  // Existing token tracking logic (unchanged):
  int totalTokens = 1;
  if (!Objects.equals(catalogIdentity, viewOwner)) {
    if (userTokens.containsKey(viewOwner)) {
      totalTokens += userTokens.get(viewOwner);
    }
    userTokens.put(viewOwner, totalTokens);
    logger.debug("Issued view expansion token for user '{}'", viewOwner);
  }
  return new ViewExpansionToken(viewOwner, viewPath);
}

private void releaseViewExpansionToken(ViewExpansionToken token) {
  // Release path FIRST (before the owner count decrement)
  inExpansionPaths.remove(token.viewPath.getSchemaPath());

  // Existing owner token release logic (unchanged):
  final CatalogIdentity viewOwner = token.viewOwner;
  if (Objects.equals(catalogIdentity, viewOwner)) {
    return;
  }
  // ... existing release logic ...
}

// Add viewPath field to ViewExpansionToken:
public class ViewExpansionToken {
  private final CatalogIdentity viewOwner;
  private final NamespaceKey viewPath;  // NEW

  ViewExpansionToken(CatalogIdentity viewOwner, NamespaceKey viewPath) {
    this.viewOwner = viewOwner;
    this.viewPath = viewPath;
  }
  // ... release() unchanged ...
}
```

**Call site in ViewExpander:**
```java
// ViewExpander.expandViewInternal():
token = viewExpansionContext.reserveViewExpansionToken(viewOwner, viewTable.getPath());
```

### Example 4: Plan cache fix for DEFN-04 (conservative approach)

```java
// File: sabot/kernel/src/main/java/com/dremio/exec/planner/plancache/PlanCacheUtils.java

// In supportPlanCache(), add check for definer-rights views:
public static boolean supportPlanCache(
    SqlHandlerConfig config,
    SqlNode sqlNode,
    List<SqlOperator> uncacheableFunctions,
    RelNode relNode) {

  // ... existing checks ...

  // DEFN-04: Disable plan cache when definer rights are active
  // (view is expanded under a non-query-user identity)
  String queryUser = config.getContext().getQueryUserName();
  if (containsDefinerRightsExpansion(relNode, queryUser)) {
    plannerEventBus.dispatch(
        new PlanCacheEvent(NOT_PUT_DEFINER_RIGHTS,
            "Query contains views expanded under definer identity."));
    return false;
  }

  return true;
}

// Helper to check for definer-rights expansion:
private static boolean containsDefinerRightsExpansion(RelNode relNode, String queryUser) {
  if (relNode instanceof ExpansionNode) {
    ExpansionNode expansionNode = (ExpansionNode) relNode;
    ViewTable viewTable = expansionNode.getViewTable();
    CatalogIdentity viewOwner = viewTable.getViewOwner();
    if (viewOwner != null && !viewOwner.getName().equals(queryUser)) {
      return true; // Non-query-user definer found
    }
  }
  for (RelNode child : relNode.getInputs()) {
    if (containsDefinerRightsExpansion(child, queryUser)) {
      return true;
    }
  }
  return false;
}
```

**Note:** A new `PlanCacheMetrics.QueryOutcome.NOT_PUT_DEFINER_RIGHTS` constant must be added to `PlanCacheMetrics`.

### Example 5: RBAC tests for DEFN-01 through DEFN-06

Test structure to add in `TestCatalogImpl` or a new `TestVdsDefinerRights`:

```java
// DEFN-01: User B with SELECT on V can query V even when B lacks SELECT on underlying VDS T
@Test
public void testDefinerRights_invokerLacksTableAccess_viewExpandsUnderDefiner() {
  // Setup: alice owns V (SELECT on T), bob has SELECT on V but not T
  // When: bob queries V
  // Then: V expands under alice's identity; T access check uses alice's grants -> allowed
}

// DEFN-05: Deleted view owner -> explicit error
@Test
public void testDefinerRights_deletedOwner_throwsExplicitError() {
  // Setup: view V has owner "alice" (deleted user); RBAC enabled
  // When: any user queries V
  // Then: UserException.planError() with "View owner 'alice' no longer exists"
}

// DEFN-06: Cyclic VDS chain -> clear validation error
@Test
public void testCyclicViewChain_throwsValidationError() {
  // Setup: view A references view B; view B references view A
  // When: any user queries A or B
  // Then: UserException.validationError() with "Cyclic view dependency detected"
}
```

---

## Key Files Summary

| File | Path | Purpose | Change Required |
|------|------|---------|-----------------|
| `CatalogEntityOwnershipImpl.java` | `sabot/kernel/.../catalog/` | Ownership lookup | Fix VIRTUAL_DATASET branch to return owner (DEFN-01/02/03) |
| `ViewExpander.java` | `sabot/kernel/.../planner/sql/` | View expansion with identity switching | Fix UserNotFoundException fallback to throw explicit error (DEFN-05); add `rbacEnabled` parameter |
| `ViewExpansionContext.java` | `sabot/kernel/.../ops/` | Token-based expansion tracker | Add cycle detection with `Set<String>` path tracker; change `reserveViewExpansionToken()` signature to accept `NamespaceKey` (DEFN-06) |
| `PlanCacheUtils.java` | `sabot/kernel/.../planner/plancache/` | Plan cache key generation | Add definer-rights check to `supportPlanCache()` (DEFN-04) |
| `PlanCacheMetrics.java` | `sabot/kernel/.../planner/plancache/` | Plan cache outcome constants | Add `NOT_PUT_DEFINER_RIGHTS` constant (DEFN-04) |
| `TestCatalogImpl.java` or new file | `sabot/kernel/src/test/.../` | RBAC unit tests | Add tests for DEFN-01 through DEFN-06 |

---

## Open Questions

1. **How to inject `rbacEnabled` into ViewExpander (DEFN-05)**
   - What we know: `ViewExpander` constructor takes `BuilderFactory`, `ViewExpansionContext`, `AutoVDSFixer`. It does NOT currently have access to `DremioConfig` or `OptionManager`.
   - What's unclear: Whether `BuilderFactory` or the `SqlValidatorAndToRelContext` builder chain provides a way to access config.
   - Recommendation: Add `boolean rbacEnabled` as a 4th constructor parameter to `ViewExpander`. The construction site in `QueryContext` or `PlannerCatalogImpl` has access to `DremioConfig.RBAC_ENABLED`. Grep for `new ViewExpander(` to find all construction sites before implementing.

2. **`reserveViewExpansionToken()` callers beyond ViewExpander**
   - What we know: One confirmed caller in `ViewExpander.expandViewInternal()` at line 125.
   - What's unclear: Whether any test code or other production code calls `reserveViewExpansionToken()` directly.
   - Recommendation: Grep for `reserveViewExpansionToken` before changing the signature. If there are other callers, update them all or add an overloaded variant.

3. **Plan cache Option 1 vs Option 2 for DEFN-04**
   - What we know: Option 2 (disable caching for definer-rights queries) is safe and simple. Option 1 (include definer chain in hash) preserves caching performance.
   - What's unclear: How often VDS definer-rights queries are expected in practice (performance impact of Option 2).
   - Recommendation: Start with Option 2 for correctness. Profile under load if needed before considering Option 1. The `NOT_PUT_DEFINER_RIGHTS` metric will show cache bypass frequency.

4. **`ViewTable.getViewOwner()` returns null for versioned VDS**
   - What we know: For versioned VDS (Nessie sources), `VersionedDatasetAdapter.java:207` calls `viewConfig.setOwner(catalogIdentity.getName())` when `VERSIONED_SOURCE_VIEW_DELEGATION_ENABLED` is true. The `ViewTable` is constructed with a `CatalogIdentity` from `getCatalogIdentity()` in `VersionedDatasetAdapter`.
   - What's unclear: Whether Phase 8's `CatalogEntityOwnershipImpl` fix interacts with versioned VDS — `DatasetManager.createTableFromVirtualDataset()` is for non-versioned VDS; versioned VDS goes through `getTableSnapshotForVersionedSource()`.
   - Recommendation: Focus Phase 8 fix on non-versioned VDS (namespace-backed VDS in spaces/home). Versioned VDS already has a separate owner resolution path that does not go through `CatalogEntityOwnershipImpl`.

5. **DEFN-03 test with null viewOwner at intermediate level**
   - What we know: In a VDS-over-VDS chain, if an intermediate view has a null owner (legacy VDS), expansion falls back to the query user for that level. The chain continues with the query user's identity at that level.
   - What's unclear: Whether this "partial definer chain" behavior is acceptable or should produce an error.
   - Recommendation: Accept the partial chain behavior for Phase 8. Document it as the expected behavior for legacy VDS in the test suite. An intermediate null owner is treated as "inherit caller's identity at this level."

---

## Sources

### Primary (HIGH confidence — direct code inspection)

- `CatalogEntityOwnershipImpl.java` — VIRTUAL_DATASET branch returns `Optional.empty()` confirmed at line 51
- `ViewExpander.java` — `expandViewInternal()` fallback on `UserNotFoundException` confirmed at lines 128-133; `builder.withUser(viewOwner)` at line 153
- `ViewExpansionContext.java` — token tracking by owner (no path tracking) confirmed; `userTokens` map at line 72
- `DatasetManager.java:919` — `createTableFromVirtualDataset()` passes `getEntityOwner()` result as `viewOwner` to `ViewTable`
- `DatasetManager.java:1247` — `getEntityOwner()` delegates to `catalogEntityOwnership.getCatalogEntityOwner()` (returns null for VDS)
- `CatalogImpl.java:1646` — `resolveCatalog(CatalogIdentity)` creates fresh CatalogImpl with `userName = subject.getName()`
- `PlanCacheUtils.java:143-181` — `generateCacheKey()` hashes SQL, relNode, workload, schema, optionally query user (NOT definer chain)
- `DatasetsUtil.java:151` — `datasetConfig.setOwner(virtualDatasetUI.getOwner())` confirms owner write on save
- `DatasetVersionResource.java:396` — `vds.setOwner(securityContext.getUserPrincipal().getName())` confirms owner set from logged-in user
- `CatalogImpl.java:2816-2863` — `validatePrivilege()` and `validateCreateViewPrivilege()` — Phase 7 confirmed complete
- `ViewTable.java` — `@Nullable CatalogIdentity viewOwner` field; `getViewOwner()` returns it directly

### Secondary (MEDIUM confidence)

- `VersionedDatasetAdapter.java:207` — versioned VDS sets owner when `VERSIONED_SOURCE_VIEW_DELEGATION_ENABLED` (separate path, not affected by Phase 8 fix)

---

## Metadata

**Confidence breakdown:**
- Root cause (CatalogEntityOwnershipImpl VDS branch): HIGH — confirmed by direct inspection
- Definer rights mechanism (ViewExpander.withUser chain): HIGH — confirmed by direct inspection
- Owner population on save (DatasetConfig.owner): HIGH — traced through DatasetVersionResource → DatasetsUtil → namespace
- Cycle detection approach (ViewExpansionContext path set): HIGH — design is straightforward given current token structure
- Plan cache definer chain (DEFN-04): HIGH — generateCacheKey() inspected; confirmed no definer hashing
- DEFN-05 (UserNotFoundException fallback): HIGH — confirmed at lines 128-133 of ViewExpander
- Thread safety concern (P27): LOW — confirmed single-threaded planning; ViewExpansionContext is per-query, no shared state

**Research date:** 2026-02-21
**Valid until:** 2026-03-21 (stable code; changes only if view expansion or RBAC implementation evolves)
