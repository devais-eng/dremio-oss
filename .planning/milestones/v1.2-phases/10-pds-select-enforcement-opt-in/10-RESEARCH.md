# Phase 10: PDS SELECT Enforcement (Opt-in) - Research

**Researched:** 2026-02-21
**Domain:** Java — CatalogImpl, RbacService, GrantStore, DremioConfig, CatalogGrantHandler, CatalogRevokeHandler, SqlGrant, SqlGrantOnCatalog
**Confidence:** HIGH (all findings confirmed by direct code inspection)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PDS-01 | Admin can GRANT/REVOKE SELECT on a physical table to a role | `GRANT SELECT ON PDS source.schema.table TO ROLE analyst` already parses correctly (grammar in `grant.ftl` maps `<PDS>` and `<TABLE>` to `SqlGrant.GrantType.PDS`). `CatalogGrantHandler` extracts `entityType.name()` as the `objectType` string and calls `rbacService.grantPrivilege(grantee, objectType, objectPath, privilege, grantedBy)`. Currently this produces object type `"PDS"`. PDS-01 is nearly ready; `CatalogGrantHandler`/`CatalogRevokeHandler` doc comments must be updated and a test for PDS grant must be added. |
| PDS-02 | PDS with explicit grants are only accessible to users with a SELECT grant (opt-in enforcement: PDS without any grants remain universally accessible) | `isRbacDeniedForVds()` in `CatalogImpl` explicitly skips non-`ViewTable` objects (`if (!(table instanceof ViewTable)) return false`). A new `isRbacDeniedForPds()` method must be added that: (1) checks the PDS feature flag; (2) calls `rbacService.listGrantsByObject("PDS", path)` to determine if any grants exist; (3) if no grants exist → allow (opt-in); (4) if grants exist → check `hasPrivilege(user, "SELECT", "PDS", path)`. This method must be called from all the same `getTable*` and `bulkGetTables` sites that currently call `isRbacDeniedForVds`. |
| PDS-03 | PDS grants use distinct "PDS" object type, separate from "VDS" grants (no key collision) | The grant key format is `{role_id}\|{object_type}\|{object_path}\|{privilege}` (see `RbacConfig.grantKey()`). Using `objectType = "PDS"` vs `objectType = "VDS"` guarantees no key collision even for the same path. `SqlGrant.GrantType.PDS` already exists and the grammar routes `ON PDS ...` to this type. `CatalogGrantHandler` already sets `objectType = entityType.name()` which will be `"PDS"`. The separation is automatic given the existing key format. |
</phase_requirements>

---

## Summary

Phase 10 adds opt-in SELECT enforcement on physical datasets (PDS). The research shows that most infrastructure already exists: the SQL grammar already parses `GRANT SELECT ON PDS path TO ROLE role`, the `GrantType.PDS` enum value exists in `SqlGrant`, the `CatalogGrantHandler` and `CatalogRevokeHandler` already write to the grant store with `objectType = entityType.name()` (producing `"PDS"`), and the `RbacConfig.grantKey()` format with `|` separator guarantees no collision between `"PDS"` and `"VDS"` grants on the same path.

The only production code gap is the enforcement side: `isRbacDeniedForVds()` in `CatalogImpl` explicitly returns `false` for any table that is not a `ViewTable` (i.e., all PDS). A new `isRbacDeniedForPds()` method must be added and called from every `getTable*` and `bulkGetTables` site. The opt-in check (no grants = universally accessible) requires a single call to `rbacService.listGrantsByObject("PDS", path)` — if the result is empty, skip all enforcement. The `listGrantsByObject()` method already exists in `RbacService` (it delegates to `GrantStore.listByObject()` which does a full scan and filters by `objectType` and `objectPath`).

A separate config flag `services.rbac.pds.enabled` is required (documented in prior decisions) so that PDS enforcement can be rolled out independently of the main VDS RBAC flag. This flag must be added to `DremioConfig` and checked in `isRbacDeniedForPds()`.

The VDS-over-PDS definer rights scenario (success criterion 4) requires no new code: Phase 8 already ensures that when a user queries a VDS, expansion runs under the definer's identity. The definer-scoped `CatalogImpl` calls `getTable(pdsKey)` which will invoke `isRbacDeniedForPds()` with `userName = definer.getName()`. If the definer has SELECT on the PDS, expansion succeeds even if the invoker does not.

**Primary recommendation:** Implement in two plans. Plan 1: add `RBAC_PDS_ENABLED` config key, new `isRbacDeniedForPds()` method in `CatalogImpl`, call sites in all `getTable*` variants and `bulkGetTables`, and `RbacService.hasAnyPdsGrant()` helper. Plan 2: unit tests for PDS-01/02/03 and the VDS-over-PDS definer rights scenario.

---

## Standard Stack

### Core (no new dependencies — everything already exists)

| Component | Location | Purpose | Why Relevant |
|-----------|----------|---------|-------------|
| `CatalogImpl` | `sabot/kernel/.../catalog/CatalogImpl.java` | Catalog with RBAC enforcement | Contains `isRbacDeniedForVds()` (the template for the new `isRbacDeniedForPds()`) and all `getTable*` / `bulkGetTables` call sites |
| `RbacService` | `sabot/kernel/.../rbac/RbacService.java` | Core RBAC logic: hasPrivilege, listGrantsByObject | `listGrantsByObject("PDS", path)` is the opt-in check; `hasPrivilege(user, "SELECT", "PDS", path)` is the enforcement check |
| `GrantStore` | `sabot/kernel/.../rbac/GrantStore.java` | KV store for grant records | `listByObject(objectType, objectPath)` does a full scan and filters; used by `listGrantsByObject()` |
| `DremioConfig` | `common/legacy/.../config/DremioConfig.java` | Config constants for feature flags | Must add `RBAC_PDS_ENABLED = "services.rbac.pds.enabled"` constant (parallel to `RBAC_ENABLED`) |
| `CatalogGrantHandler` | `sabot/kernel/.../sql/handlers/CatalogGrantHandler.java` | Handles `GRANT priv ON type entity TO ROLE` | Already works for PDS: `entityType.name()` produces `"PDS"`. Doc comment must be updated to include PDS. No logic change needed. |
| `CatalogRevokeHandler` | `sabot/kernel/.../sql/handlers/CatalogRevokeHandler.java` | Handles `REVOKE priv ON type entity FROM ROLE` | Same as CatalogGrantHandler — already works for PDS. |
| `SqlGrant.GrantType` | `sabot/kernel/.../sql/parser/SqlGrant.java` | Enum for entity types in GRANT statements | `GrantType.PDS` already exists. Grammar already maps `<PDS>` and `<TABLE>` to this enum value. |
| `RbacConfig` | `sabot/kernel/.../rbac/RbacConfig.java` | Key format utilities | `grantKey(roleId, objectType, objectPath, privilege)` with `|` separator already prevents collision between `"PDS"` and `"VDS"` types. |
| `ViewTable` | `sabot/kernel/.../planner/logical/ViewTable.java` | DremioTable for views | `isRbacDeniedForVds()` checks `instanceof ViewTable`. PDS tables are NOT `ViewTable` instances — this is the guard that currently lets all PDS through without enforcement. |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `SystemUser` | (existing) | `isSystemUserName()` check | Use same system-user bypass pattern as `isRbacDeniedForVds()` |
| `UserException.validationError()` | (existing Dremio) | Permission denied error | Use same pattern as VDS denial for throwing access denied on PDS |

---

## Architecture Patterns

### Pattern 1: isRbacDeniedForPds() — the new enforcement method

This method mirrors `isRbacDeniedForVds()` with three key differences:
1. It only fires for non-`ViewTable` tables (i.e., actual PDS).
2. It checks `services.rbac.pds.enabled` (a second flag) in addition to `services.rbac.enabled`.
3. The opt-in check: if `listGrantsByObject("PDS", path)` returns empty, allow access (no lockout).

**Template (based on existing `isRbacDeniedForVds()`):**

```java
// In CatalogImpl.java — new method
/**
 * Checks if RBAC denies the current user access to a PDS (physical dataset / table).
 * Returns true if access is denied, false if access is allowed.
 *
 * Opt-in enforcement: if no PDS grants exist for this table, access is allowed (backward compatible).
 * If at least one grant exists, only users with a matching SELECT grant can access the table.
 *
 * @param table the resolved table -- must be non-null
 * @param key the namespace key of the table
 * @return true if RBAC denies access to this PDS
 */
private boolean isRbacDeniedForPds(DremioTable table, NamespaceKey key) {
    // Only enforce on PDS (not views -- those are handled by isRbacDeniedForVds)
    if (table instanceof ViewTable) {
        return false;
    }

    // PDS feature flag OFF -> allow (independent rollout)
    if (dremioConfig == null
            || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)
            || !dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)) {
        return false;
    }

    // System user -> allow
    if (SystemUser.isSystemUserName(userName)) {
        return false;
    }

    // No RbacService -> allow (defensive)
    if (rbacService == null) {
        return false;
    }

    String objectPath = key.getSchemaPath();

    // Opt-in check: if no grants exist for this PDS, it's universally accessible
    if (rbacService.listGrantsByObject("PDS", objectPath).isEmpty()) {
        return false;
    }

    // At least one grant exists -- enforce SELECT
    if (!rbacService.hasPrivilege(userName, "SELECT", "PDS", objectPath)) {
        logger.warn("RBAC: PDS access denied for user '{}'", userName);
        return true;
    }

    return false;
}
```

### Pattern 2: Call Sites — where to add PDS enforcement

All four `getTable*` methods and `bulkGetTables` already call `isRbacDeniedForVds()`. Add a parallel call to `isRbacDeniedForPds()` at each site using the same pattern:

**Current pattern (all four getTable variants):**
```java
if (table != null && isRbacDeniedForVds(table, key)) {
    return null; // RBAC denied -- appear as "not found"
}
```

**New pattern:**
```java
if (table != null && (isRbacDeniedForVds(table, key) || isRbacDeniedForPds(table, key))) {
    return null; // RBAC denied -- appear as "not found"
}
```

**Four call sites in `getTable*`:**
- `getTableNoResolve(NamespaceKey)` — line 290
- `getTableNoColumnCount(NamespaceKey)` — line 299
- `getTable(NamespaceKey)` — lines 312 and 320
- `getTable(CatalogEntityKey)` — line 332

**One call site in `bulkGetTables`:** The `ValueTransformer` lambda at line 379:
```java
if (isRbacDeniedForVds(table, resolvedKey) || isRbacDeniedForPds(table, resolvedKey)) {
    return Optional.empty(); // RBAC denied -- appear as "not found"
}
```

**Note:** `getTable(String datasetId)` at line 1195 delegates to `datasetManager.getTable(datasetId, options)` and does NOT call `isRbacDeniedForVds`. Check whether this path needs PDS enforcement too (it is an ID-based lookup used for time-travel; lower priority but should be reviewed).

### Pattern 3: DremioConfig — adding the PDS feature flag

Following the exact pattern of `RBAC_ENABLED`:

```java
// In DremioConfig.java (add near RBAC_ENABLED constant at line 152)

/** RBAC enforcement for physical datasets (PDS); independent of services.rbac.enabled.
 *  Defaults to false (OFF). Requires coordinator restart to change. */
public static final String RBAC_PDS_ENABLED = "services.rbac.pds.enabled";
```

The flag is checked in `isRbacDeniedForPds()` as a conjunction with `RBAC_ENABLED`:
- `RBAC_ENABLED = false` → no enforcement (VDS and PDS both off)
- `RBAC_ENABLED = true, RBAC_PDS_ENABLED = false` → VDS enforced, PDS universally accessible (safe default for existing deployments enabling RBAC for the first time)
- `RBAC_ENABLED = true, RBAC_PDS_ENABLED = true` → both enforced

### Pattern 4: Opt-in Logic — listGrantsByObject vs hasPrivilege

The opt-in check requires knowing whether ANY grant exists for a PDS, before checking if the specific user has a grant. The current `RbacService` has `listGrantsByObject(objectType, objectPath)` which returns a `List<Grant>`. Checking `.isEmpty()` is the correct pattern.

**Performance consideration (from prior decisions):** `listGrantsByObject()` does a full KV store scan and filters by `objectType` and `objectPath`. For a single `getTable()` call this is acceptable. For `bulkGetTables()` with N tables, this is N full scans. The prior decision notes this must be profiled before shipping.

**Mitigation options (for planner to decide):**
1. **Accept full scans for v1.2:** If the grant store is small (hundreds of grants), the full scan is fast. Profile under load, optimize later.
2. **Add `hasAnyPdsGrant(objectPath)` to `RbacService`:** Wraps `listGrantsByObject("PDS", objectPath).isEmpty()` with a short-circuit — same performance, better naming.
3. **Two-call pattern in `isRbacDeniedForPds()`:** Call `hasPrivilege(PUBLIC_ROLE_ID, "SELECT", "PDS", path)` — if PUBLIC has SELECT, all users have it (grants exist but everyone has access). This does not help with the opt-in check directly.

**Recommended approach:** Add `hasAnyPdsGrant(String objectPath)` to `RbacService` as a named helper that wraps `listGrantsByObject("PDS", objectPath).isEmpty()`. This makes `isRbacDeniedForPds()` readable and gives the planner a place to add caching or optimization later.

```java
// In RbacService.java — new helper
/**
 * Returns true if at least one PDS SELECT grant exists for the given object path.
 * Used for opt-in enforcement: tables with no grants remain universally accessible.
 */
public boolean hasAnyPdsGrant(String objectPath) {
    return !grantStore.listByObject("PDS", objectPath).isEmpty();
}
```

### Pattern 5: VDS-over-PDS Definer Rights (Success Criterion 4)

This works automatically once PDS enforcement is active. The chain:

1. User B queries VDS `myspace.my_view` (which SELECTs from `mysource.schema.table`).
2. `isRbacDeniedForVds(myView, key)` checks B's SELECT on VDS — passes.
3. `ViewExpander.expandViewInternal(myView)` switches catalog to definer Alice.
4. In Alice's catalog, `getTable("mysource.schema.table")` is called.
5. `isRbacDeniedForPds(pdsTable, pdsKey)` runs with `userName = "alice"`.
6. If `hasAnyPdsGrant("mysource.schema.table")` is false → allow (no PDS grants, universal access).
7. If grants exist: check `hasPrivilege("alice", "SELECT", "PDS", "mysource.schema.table")` → if Alice has SELECT, expansion succeeds. User B's SELECT on the PDS is irrelevant.

**This requires no new code.** The definer rights chain from Phase 8 already handles step 3-7 correctly. The only requirement is that `isRbacDeniedForPds()` uses `userName` (which equals the definer's name in the definer-scoped `CatalogImpl`).

### Anti-Patterns to Avoid

- **Checking PDS grants inside `isRbacDeniedForVds()`:** The current method guards with `!(table instanceof ViewTable)`. Adding PDS logic there would be confusing. Keep them as separate methods.
- **Using `DatasetType` instead of `instanceof ViewTable`:** `isRbacDeniedForVds()` uses `instanceof ViewTable` as its PDS/VDS discriminator. `isRbacDeniedForPds()` uses the inverse: `if (table instanceof ViewTable) return false`. This is consistent.
- **Enforcing PDS before opt-in check:** Always do the `listGrantsByObject` opt-in check FIRST. Calling `hasPrivilege` without checking if any grants exist would deny all users access to unprotected tables (incorrect, breaks backward compatibility).
- **Applying PDS enforcement to definer-scoped catalogs for VDS owners:** This is correct behavior — if the VDS owner has SELECT on the PDS, expansion succeeds. The PDS enforcement applies to the catalog user (`userName`), which in the definer-scoped catalog is the definer's name. No special handling needed.
- **Single flag for both VDS and PDS:** The separate `RBAC_PDS_ENABLED` flag is required (prior decision) to allow independent rollout. Do not make PDS enforcement conditional only on `RBAC_ENABLED`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Object type collision prevention | Custom namespace/prefix scheme | Existing `grantKey()` format with `\|` separator and distinct `"PDS"` string | The key format already prevents collision; `"PDS\|path\|priv"` vs `"VDS\|path\|priv"` cannot collide |
| Opt-in "any grant" check | Custom scan | `RbacService.listGrantsByObject("PDS", path).isEmpty()` | The scan is already implemented in `GrantStore.listByObject()`; do not duplicate it |
| PDS vs VDS discrimination | DatasetConfig.getType() lookup | `instanceof ViewTable` (same pattern as existing `isRbacDeniedForVds()`) | Simpler and already used; avoids extra namespace lookup |
| SQL parser changes for PDS | Custom SQL grammar | `SqlGrant.GrantType.PDS` + existing grammar | Already parses `GRANT SELECT ON PDS path TO ROLE role`; no grammar change needed |

---

## Common Pitfalls

### Pitfall 1: Opt-in Check Before User Check (order matters)

**What goes wrong:** Calling `hasPrivilege(user, "SELECT", "PDS", path)` before `listGrantsByObject("PDS", path).isEmpty()` causes deny for all users on tables with no PDS grants (because no grants means no user-role match, so `hasPrivilege` returns false).

**Why it happens:** Developer adds PDS enforcement mirroring VDS enforcement without reading the opt-in requirement.

**How to avoid:** In `isRbacDeniedForPds()`, always check `listGrantsByObject("PDS", path).isEmpty()` FIRST. Only proceed to `hasPrivilege()` if the list is non-empty.

**Warning signs:** All PDS tables become inaccessible after enabling `RBAC_PDS_ENABLED`, even tables with no grants configured.

### Pitfall 2: PDS Flag Not Checked in All Paths

**What goes wrong:** `isRbacDeniedForPds()` is added to `getTable()` but not to `getTableNoResolve()`, `getTableNoColumnCount()`, or `bulkGetTables()`. A user denied via `getTable()` can access the same table via a planning path that uses `bulkGetTables()`.

**Why it happens:** Looking at only the primary `getTable()` entry point.

**How to avoid:** Grep for all calls to `isRbacDeniedForVds()` in `CatalogImpl` (lines 290, 299, 312, 320, 332, 379) and add the parallel PDS call at every site.

**Warning signs:** RBAC tests pass for `getTable()` but fail for queries that use batch planning paths.

### Pitfall 3: listGrantsByObject Performance in bulkGetTables

**What goes wrong:** `bulkGetTables()` processes N tables. If each table triggers one `listGrantsByObject()` call (full scan), and the grant store has M grants, the cost is O(N * M) per query planning cycle.

**Why it happens:** Full scan in `GrantStore.listByObject()` — this is a known accepted trade-off (prior decision: must be profiled before shipping).

**How to avoid:** Profile before releasing Phase 10. If the grant store is small (e.g., < 1000 grants) and N is small (e.g., < 50 tables per query), the cost is acceptable. If either grows, add a `Set<String>` cache of "which PDS paths have at least one grant" preloaded at `CatalogImpl` construction time or refreshed periodically.

**Warning signs:** Planning latency increases proportionally to the number of tables referenced in queries.

### Pitfall 4: `resolveRbacObjectType()` Returns "VDS" for PDS

**What goes wrong:** `resolveRbacObjectType(key, privilege)` in `CatalogImpl` (line 2870) returns `"VDS"` for SELECT, ALTER, DROP (the default case). If `validatePrivilege()` is ever called with a PDS key and SELECT privilege, it will check `hasPrivilege(user, "SELECT", "VDS", path)` — checking VDS grants on a PDS path, which will not find PDS grants.

**Why it happens:** `resolveRbacObjectType()` has no way to distinguish PDS from VDS using only the `NamespaceKey` and `Privilege`. It defaults to `"VDS"`.

**How to avoid:** `validatePrivilege()` is currently only called from lifecycle enforcement contexts (ALTER, DROP view handlers; CREATE_VIEW). It is NOT called from `getTable()` or `bulkGetTables()`. The PDS enforcement path uses `isRbacDeniedForPds()` directly (not `validatePrivilege()`), so this pitfall does NOT apply to Phase 10. Document this: Phase 10 does NOT change `resolveRbacObjectType()`. If a future phase adds `validatePrivilege()` calls for PDS, that method must be updated then.

### Pitfall 5: System User Bypass Missing

**What goes wrong:** `isRbacDeniedForPds()` is implemented without the `SystemUser.isSystemUserName(userName)` bypass. Internal Dremio system processes that read physical tables directly (e.g., metadata refresh) are blocked.

**Why it happens:** Copy-paste error — omitting the system user check present in `isRbacDeniedForVds()` and `isRbacDeniedForFunction()`.

**How to avoid:** Follow the same guard pattern as the existing methods:
1. Feature flag check
2. System user bypass
3. Null rbacService guard
4. Opt-in check
5. User privilege check

---

## Code Examples

### Example 1: New isRbacDeniedForPds() method

```java
// File: sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java
// Add after isRbacDeniedForVds() (around line 2919)

/**
 * Checks if RBAC denies the current user SELECT access to a PDS (physical dataset / table).
 * Returns true if access is denied, false if access is allowed.
 *
 * <p>Opt-in enforcement: tables with no PDS grants are universally accessible (backward compatible).
 * Once at least one grant exists for a table, only users with a matching SELECT grant can access it.
 *
 * <p>Only fires when both {@code services.rbac.enabled} and {@code services.rbac.pds.enabled} are
 * true. This allows independent rollout of PDS enforcement after VDS enforcement is established.
 *
 * @param table the resolved table -- must be non-null
 * @param key the namespace key of the table
 * @return true if RBAC denies access to this PDS
 */
private boolean isRbacDeniedForPds(DremioTable table, NamespaceKey key) {
    // Only enforce on PDS, not views (handled by isRbacDeniedForVds)
    if (table instanceof ViewTable) {
        return false;
    }

    // Main RBAC flag OFF -> allow
    if (dremioConfig == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
        return false;
    }

    // PDS-specific flag OFF -> allow (independent rollout)
    if (!dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)) {
        return false;
    }

    // System user -> allow
    if (SystemUser.isSystemUserName(userName)) {
        return false;
    }

    // No RbacService -> allow (defensive)
    if (rbacService == null) {
        return false;
    }

    String objectPath = key.getSchemaPath();

    // Opt-in check: tables with no PDS grants are universally accessible
    if (!rbacService.hasAnyPdsGrant(objectPath)) {
        return false;
    }

    // At least one grant exists -- enforce SELECT for this user
    if (!rbacService.hasPrivilege(userName, "SELECT", "PDS", objectPath)) {
        logger.warn("RBAC: PDS access denied for user '{}'", userName);
        return true;
    }

    return false;
}
```

### Example 2: DremioConfig — adding RBAC_PDS_ENABLED constant

```java
// File: common/legacy/src/main/java/com/dremio/config/DremioConfig.java
// Add after RBAC_ENABLED (around line 152)

/** RBAC enforcement; requires coordinator restart to change. Defaults to false (OFF). */
public static final String RBAC_ENABLED = "services.rbac.enabled";

/** PDS SELECT enforcement (opt-in). Only active when RBAC_ENABLED is also true.
 *  Defaults to false. Requires coordinator restart to change. */
public static final String RBAC_PDS_ENABLED = "services.rbac.pds.enabled";
```

### Example 3: RbacService — hasAnyPdsGrant helper

```java
// File: sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacService.java
// Add after listGrantsByObject()

/**
 * Returns true if at least one PDS SELECT grant exists for the given object path.
 *
 * <p>Used for opt-in PDS enforcement: a table with no grants is universally accessible. Only
 * when at least one grant exists does per-user enforcement apply.
 *
 * @param objectPath the dot-delimited object path (e.g. "mysource.schema.table")
 * @return true if any grant exists for this PDS path
 */
public boolean hasAnyPdsGrant(String objectPath) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(objectPath), "objectPath must not be null or empty");
    return !grantStore.listByObject("PDS", objectPath).isEmpty();
}
```

### Example 4: CatalogImpl call site changes (all five locations)

```java
// getTableNoResolve(NamespaceKey key):
if (table != null && (isRbacDeniedForVds(table, key) || isRbacDeniedForPds(table, key))) {
    return null; // RBAC denied -- appear as "not found"
}

// getTableNoColumnCount(NamespaceKey key):
if (table != null && (isRbacDeniedForVds(table, key) || isRbacDeniedForPds(table, key))) {
    return null; // RBAC denied -- appear as "not found"
}

// getTable(NamespaceKey key) — first check (resolvedKey != null branch, line 312):
if (isRbacDeniedForVds(table, resolvedKey) || isRbacDeniedForPds(table, resolvedKey)) {
    // ... existing denial handling
}

// getTable(NamespaceKey key) — second check (line 320):
if (table != null && (isRbacDeniedForVds(table, key) || isRbacDeniedForPds(table, key))) {
    // ... existing denial handling
}

// getTable(CatalogEntityKey catalogEntityKey) — line 332:
if (table != null && (isRbacDeniedForVds(table, namespaceKey) || isRbacDeniedForPds(table, namespaceKey))) {
    // ... existing denial handling
}

// bulkGetTables() ValueTransformer lambda (line 379):
if (isRbacDeniedForVds(table, resolvedKey) || isRbacDeniedForPds(table, resolvedKey)) {
    return Optional.empty(); // RBAC denied -- appear as "not found"
}
```

### Example 5: Unit test pattern for PDS-02 (opt-in enforcement)

```java
// In TestCatalogImpl.java

@Test
public void testPdsAccess_noGrants_universallyAccessible() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(true);
    // No grants exist for this PDS
    when(rbacService.hasAnyPdsGrant(anyString())).thenReturn(false);

    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // validatePrivilege on a PDS path should pass (universal access when no grants)
    // Direct test via isRbacDeniedForPds logic: use a mock PDS table
    // Verify hasPrivilege is never called (short-circuit at opt-in check)
    verifyNoInteractions(rbacService); // or verify only hasAnyPdsGrant was called
}

@Test
public void testPdsAccess_grantsExist_userDenied_throwsPermissionDenied() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(true);
    when(rbacService.hasAnyPdsGrant(anyString())).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("PDS"), anyString()))
        .thenReturn(false);

    // Attempt to access PDS -- should be denied
    CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Test via getTable returning null (silent deny) or via validatePrivilege
}

@Test
public void testPdsAccess_grantsExist_userGranted_allowed() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(true);
    when(rbacService.hasAnyPdsGrant(anyString())).thenReturn(true);
    when(rbacService.hasPrivilege(eq("gnarly"), eq("SELECT"), eq("PDS"), anyString()))
        .thenReturn(true);

    // Attempt to access PDS -- should be allowed
}

@Test
public void testPdsAccess_pdsFeatureFlagOff_universallyAccessible() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_PDS_ENABLED)).thenReturn(false);

    // Even with grants existing, the PDS flag off means no enforcement
    verifyNoInteractions(rbacService);
}
```

### Example 6: CatalogGrantHandler — confirming PDS grant works (test only)

```java
// In TestRbacDdlHandlers.java — add test for PDS grant

@Test
public void testCatalogGrant_selectOnPds_success() throws Exception {
    SqlGrantOnCatalog node =
        new SqlGrantOnCatalog(
            SqlParserPos.ZERO,
            privList(SqlGrant.Privilege.SELECT),
            SqlLiteral.createSymbol(SqlGrant.GrantType.PDS, SqlParserPos.ZERO),
            compoundId("mysource", "myschema", "mytable"),
            SqlLiteral.createSymbol(SqlGrant.GranteeType.ROLE, SqlParserPos.ZERO),
            id("analyst"),
            null,
            null);
    List<SimpleCommandResult> results =
        new CatalogGrantHandler(queryContext)
            .toResult("GRANT SELECT ON PDS mysource.myschema.mytable TO ROLE analyst", node);

    verify(rbacService).grantPrivilege(
        "analyst", "PDS", "mysource.myschema.mytable", "SELECT", "admin_user");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).ok).isTrue();
    assertThat(results.get(0).summary)
        .contains("PDS")
        .contains("mysource.myschema.mytable")
        .contains("analyst");
}
```

---

## Key Files Summary

| File | Path | Change Required |
|------|------|-----------------|
| `DremioConfig.java` | `common/legacy/.../config/DremioConfig.java` | Add `RBAC_PDS_ENABLED = "services.rbac.pds.enabled"` constant |
| `RbacService.java` | `sabot/kernel/.../rbac/RbacService.java` | Add `hasAnyPdsGrant(String objectPath)` helper |
| `CatalogImpl.java` | `sabot/kernel/.../catalog/CatalogImpl.java` | Add `isRbacDeniedForPds()` method; update 5 call sites in `getTable*` and `bulkGetTables` |
| `CatalogGrantHandler.java` | `sabot/kernel/.../sql/handlers/CatalogGrantHandler.java` | Update doc comment to include PDS; no logic change |
| `CatalogRevokeHandler.java` | `sabot/kernel/.../sql/handlers/CatalogRevokeHandler.java` | Update doc comment to include PDS; no logic change |
| `TestCatalogImpl.java` | `sabot/kernel/src/test/.../catalog/TestCatalogImpl.java` | Add unit tests for PDS-01, PDS-02, PDS-03, VDS-over-PDS scenario |
| `TestRbacDdlHandlers.java` | `sabot/kernel/src/test/.../handlers/TestRbacDdlHandlers.java` | Add PDS GRANT/REVOKE tests |

---

## State of the Art

| Area | Current State | After Phase 10 | Impact |
|------|--------------|----------------|--------|
| PDS access | All PDS universally accessible regardless of grants | PDS universally accessible unless at least one grant exists; then per-user enforcement | Opt-in locking of specific tables |
| SQL GRANT/REVOKE PDS | Grammar parses correctly; `CatalogGrantHandler` stores with `"PDS"` objectType; but enforcement ignores it | Grants stored and enforced | PDS-01 and PDS-03 complete |
| `isRbacDeniedForVds` PDS path | Returns false immediately for non-ViewTable | Unchanged; parallel `isRbacDeniedForPds()` handles PDS | Clean separation |
| `resolveRbacObjectType()` | Returns "VDS" for SELECT (no PDS awareness) | Unchanged — not used in PDS enforcement path | No change needed for Phase 10 |
| VDS-over-PDS definer rights | VDS expansion under definer sees PDS as universally accessible (no PDS grants) | Same behavior unless PDS has grants; if grants exist, definer's SELECT on PDS is checked | Seamless — definer rights from Phase 8 apply |

**Already working, no change needed:**
- `grant.ftl` grammar: `<PDS>` and `<TABLE>` already map to `SqlGrant.GrantType.PDS`
- `SqlGrant.GrantType.PDS`: already exists in the enum
- `CatalogGrantHandler.toResult()`: already extracts `entityType.name()` as objectType string (produces `"PDS"`)
- `CatalogRevokeHandler.toResult()`: same — produces `"PDS"` objectType on revoke
- `RbacConfig.grantKey()`: key format already prevents `"PDS"` vs `"VDS"` collision
- `GrantStore.listByObject()`: filters by objectType and objectPath — correctly finds PDS grants
- `RbacService.hasPrivilege()`: accepts any objectType string — works for `"PDS"` without change

---

## Open Questions

1. **Should `getTable(String datasetId)` at line 1195 enforce PDS?**
   - What we know: This method uses an ID-based lookup (for time-travel queries) and does NOT call `isRbacDeniedForVds`. It bypasses the namespace key path.
   - What's unclear: Whether this path can be reached for non-time-travel queries and represents a bypass vector.
   - Recommendation: Investigate the callers of `getTable(String datasetId)`. If it is only used for time-travel (Iceberg time travel, versioned snapshots), it may be acceptable to defer PDS enforcement for this path. Add a TODO comment in the code.

2. **`dremioConfig.getBoolean()` default value for `RBAC_PDS_ENABLED`**
   - What we know: `DremioConfig.getBoolean()` will return false for unknown keys or keys with no configured value.
   - What's unclear: Whether the default must be explicitly set in `dremio.conf` or a default config file, or if `false` from missing key is sufficient.
   - Recommendation: Document the config key in the code comment. The defensive `false` default from `getBoolean()` for an unset key is correct behavior (flag off by default). No default config entry is needed, but a comment explaining the flag should be added to the constant declaration.

3. **bulkGetTables performance with opt-in PDS check**
   - What we know: Each PDS table in a bulk request triggers one `listGrantsByObject()` full-scan call. The scan is O(total grants in store).
   - What's unclear: Typical query planning fan-out (how many tables per query) and typical grant store size in production.
   - Recommendation: Profile before shipping Phase 10. If performance is acceptable under realistic workloads, ship as-is. If not, add a "PDS grant cache" (e.g., a `Set<String>` of locked PDS paths preloaded at startup or refreshed on grant/revoke operations). This is a v2 optimization.

4. **`CatalogGrantHandler` and `CatalogRevokeHandler` doc comments**
   - What we know: Both currently mention only VDS, FUNCTION, and CREATE_VIEW in their Javadoc `@see` lists.
   - What's unclear: Whether doc-only changes belong in Plan 1 (production code) or Plan 2 (tests).
   - Recommendation: Include in Plan 1 alongside the production changes — they are trivial one-line updates.

---

## Sources

### Primary (HIGH confidence — direct code inspection)

- `CatalogImpl.java` lines 288-395: all `getTable*` and `bulkGetTables` call sites confirmed; `isRbacDeniedForVds()` at lines 2892-2919 is the template
- `CatalogImpl.java` line 2870-2881: `resolveRbacObjectType()` returns `"VDS"` for SELECT — confirmed does NOT apply to PDS enforcement path
- `RbacService.java` lines 115-148: `hasPrivilege()` accepts any `objectType` string — confirmed works for `"PDS"` without change
- `RbacService.java` lines 334-336: `listGrantsByObject()` delegates to `grantStore.listByObject()` — confirmed
- `GrantStore.java` lines 129-141: `listByObject()` full scan confirmed; filters by `objectType` and `objectPath` proto fields
- `RbacConfig.java` lines 64-67: `grantKey()` format `{role}\|{objectType}\|{objectPath}\|{privilege}` — no collision between `"PDS"` and `"VDS"` confirmed
- `grant.ftl` lines 77-87: `<PDS>` and `<TABLE>` map to `SqlGrant.GrantType.PDS` — grammar confirmed
- `SqlGrant.java` lines 85-96: `GrantType.PDS` enum value exists — confirmed
- `CatalogGrantHandler.java` lines 63-76: `entityType.name()` as objectType string — produces `"PDS"` — confirmed
- `CatalogRevokeHandler.java` lines 63-76: same pattern as CatalogGrantHandler — confirmed
- `DremioConfig.java` line 152: `RBAC_ENABLED = "services.rbac.enabled"` — the template for `RBAC_PDS_ENABLED`
- `CatalogImpl.java` line 2894: `if (!(table instanceof ViewTable)) return false` — the current PDS bypass confirmed

---

## Metadata

**Confidence breakdown:**
- Grammar (PDS grant already parses): HIGH — confirmed in `grant.ftl`, `SqlGrant.GrantType.PDS`
- Handler (CatalogGrantHandler already stores as "PDS"): HIGH — confirmed by code inspection
- Key collision prevention (grantKey format): HIGH — confirmed by `RbacConfig.grantKey()` format
- isRbacDeniedForPds() design: HIGH — directly modeled on confirmed `isRbacDeniedForVds()` pattern
- Opt-in logic (listGrantsByObject().isEmpty()): HIGH — method exists and correct
- VDS-over-PDS definer rights: HIGH — confirmed via Phase 8 research (definer-scoped CatalogImpl propagates userName)
- bulkGetTables performance concern: MEDIUM — concern is real; severity depends on workload

**Research date:** 2026-02-21
**Valid until:** 2026-03-21 (stable code; changes only if CatalogImpl or RbacService evolves)
