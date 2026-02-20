# Technology Stack — RBAC v1.1 (Privilege Context Switching + PDS SELECT + VDS Lifecycle)

**Project:** Dremio OSS RBAC v1.1
**Researched:** 2026-02-20
**Overall confidence:** HIGH (all findings based on direct codebase inspection)

---

## What this document covers

This is a milestone-scoped stack document. v1.0 already shipped the following (DO NOT re-research): flat role management, KV store persistence, REST API, system tables, SQL DDL, deny-by-default enforcement via `CatalogImpl.validatePrivilege()`.

This document covers exactly what stack additions or changes are needed for v1.1:

1. **VDS definer rights** — expand views under the view-creator's identity, not the query user
2. **UDF invoker rights** — enforce that the caller has EXECUTE on the UDF (already partially done); the UDF body already expands under the UDF owner identity
3. **SELECT on physical datasets (PDS)** — grant and enforce SELECT on promoted physical tables
4. **Container visibility filtering** — hide containers (SPACE, SOURCE, FOLDER) when the user has no grants on anything inside them
5. **VDS lifecycle privileges** — enforce ALTER and DROP on VDS beyond what already exists

---

## Existing hooks — what NOT to touch

The following already works correctly and must not be modified:

| Component | What it does | File |
|-----------|-------------|------|
| `ViewExpander.expandRelNode()` | Calls `builder.withUser(viewOwner)` if viewOwner is non-null. This IS the definer-rights hook. | `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/ViewExpander.java` L152-154 |
| `UserDefinedFunctionExpanderImpl.parseAndValidate()` | Calls `.withUser(dremioUserDefinedFunction.getOwner())`. This is invoker-rights-compliant: expansion runs as the UDF owner's identity. | `sabot/kernel/src/main/java/com/dremio/exec/ops/UserDefinedFunctionExpanderImpl.java` L136 |
| `CatalogImpl.validatePrivilege()` | Enforces CREATE_VIEW, ALTER, and other privileges via `rbacService.hasPrivilege()`. Already wired. | `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` L2816 |
| `CatalogImpl.isRbacDeniedForVds()` | Denies VDS access if no SELECT grant. Already filters all `getTable*()` calls. | `CatalogImpl.java` L2869 |
| `CatalogImpl.isRbacDeniedForFunction()` | Denies EXECUTE on UDF if no grant. | `CatalogImpl.java` L2905 |
| `CatalogServiceHelper.filterByVisibility()` | Filters VDS and FUNCTION in child listings. Already wired. | `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` L3115 |
| `CatalogServiceHelper.isFunctionVisibleToUser()` | Filters top-level UDF listing. Already wired. | `CatalogServiceHelper.java` L3146 |
| Grant key format | `role_id|object_type|object_path|privilege` — covers VDS, FUNCTION, and naturally extends to PDS | `RbacConfig.java` |

---

## 1. VDS Definer Rights

### Current state (verified by code inspection)

`DatasetManager.createTableFromVirtualDataset()` builds `ViewTable` with the owner from `getEntityOwner(CatalogEntityKey)` (line 922). This calls `CatalogEntityOwnershipImpl.getCatalogEntityOwner()`.

`CatalogEntityOwnershipImpl` at line 50-51 explicitly returns `Optional.empty()` for `VIRTUAL_DATASET`:

```java
case DATASET:
  final DatasetConfig dataset = nameSpaceContainer.getDataset();
  if (dataset.getType() == DatasetType.VIRTUAL_DATASET) {
    return Optional.empty();  // VDS owner is NOT read
  }
```

So `viewOwner` in `ViewTable` is null for all VDS. In `ViewExpander.expandRelNode()`:

```java
if (viewOwner != null) {
  builder = builder.withUser(viewOwner);   // SKIPPED when null
}
```

The expansion runs under the query user's identity. This is invoker semantics today.

The `DatasetConfig.owner` field DOES exist in the proto (`dataset.proto` line 34: `optional string owner = 3`). It is set when creating datasets via `WriterUpdater.setOwner(tableEntry.getUserName())`. It IS stored for physical datasets but not used for VDS by `CatalogEntityOwnershipImpl`.

### What to change

**Change `CatalogEntityOwnershipImpl.getCatalogEntityOwner()`** to also return the owner for VIRTUAL_DATASET:

```java
case DATASET:
  final DatasetConfig dataset = nameSpaceContainer.getDataset();
  if (dataset.getType() == DatasetType.VIRTUAL_DATASET) {
    // VDS definer rights: return the stored owner
    String owner = dataset.getOwner();
    if (owner != null && !owner.isEmpty()) {
      return Optional.of(new CatalogUser(owner));
    }
    return Optional.empty();  // no owner recorded (pre-v1.1 VDS)
  }
```

No other code changes needed. The `ViewExpander` already switches identity when `viewOwner` is non-null.

**Ensure `DatasetConfig.owner` is set on VDS creation.** Trace `DACViewCreatorFactory.ViewCreatorImpl.createView()` — it calls `tool.newUntitled()` which eventually calls `datasetVersionMutator.save()`. Verify `DatasetConfig.owner` is set to the creating user's name at the point of namespace write. If not, add `datasetConfig.setOwner(userName)` where the dataset is persisted.

**Files to touch:**
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java` — return owner for VDS (1-line change)
- `dac/backend/src/main/java/com/dremio/dac/service/datasets/DACViewCreatorFactory.java` — confirm `owner` field is written on create/update

**What NOT to change:** `ViewExpander.java`, `ViewExpansionContext.java`, `DatasetManager.java`. The machinery is there; only the ownership resolution is missing.

**Confidence:** HIGH. The hook exists, the field exists, the gap is a single conditional in `CatalogEntityOwnershipImpl`.

---

## 2. UDF Invoker Rights — EXECUTE Enforcement

### Current state (verified)

UDF body already expands under the UDF owner's identity — `UserDefinedFunctionExpanderImpl.parseAndValidate()` calls `.withUser(dremioUserDefinedFunction.getOwner())`. The `owner` for a UDF comes from `CatalogImpl.getUserDefinedFunctionOwner()` which delegates to `CatalogEntityOwnership.getCatalogEntityOwner()`.

For namespace-stored UDFs (non-Nessie), `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` handles `FUNCTION` type at line 56-58, returning `Optional.empty()`. This means UDF expansion also runs as the query user today, for the same reason as VDS.

For the EXECUTE enforcement (caller must have EXECUTE privilege), `CatalogImpl.isRbacDeniedForFunction()` is already wired in `getFunctions()`.

### What to change

**For EXECUTE enforcement:** Already complete. No changes needed.

**For UDF body expansion identity (if definer semantics are desired for UDFs):** Modify `CatalogEntityOwnershipImpl` to return the stored owner for FUNCTION type. The `FunctionConfig` proto has an `owner` field (verified via `UserDefinedFunctionServiceImpl` at line 148 which reads `getOwnerNameFromFunctionConfig(functionConfig)`). Return it as `CatalogUser`:

```java
case FUNCTION:
  FunctionConfig functionConfig = nameSpaceContainer.getFunction();
  String fnOwner = getOwnerNameFromFunctionConfig(functionConfig);
  if (fnOwner != null) {
    return Optional.of(new CatalogUser(fnOwner));
  }
  return Optional.empty();
```

However: UDF expansion under owner identity is already the design intent (the `withUser(owner)` call is in `parseAndValidate`). The gap is only whether the owner is correctly resolved. This is a v1.1 improvement, not a regression fix.

**Confidence:** HIGH for enforcement (already done). MEDIUM for owner resolution completeness (depends on whether `FunctionConfig` consistently stores `owner`).

---

## 3. SELECT Grants on Physical Datasets (PDS)

### Current state (verified)

`CatalogImpl.isRbacDeniedForVds()` at line 2871 explicitly skips enforcement for non-ViewTable:

```java
if (!(table instanceof ViewTable)) {
  return false;  // Physical datasets are NOT checked
}
```

`CatalogImpl.resolveRbacObjectType()` maps `SELECT` to `"VDS"` by default:

```java
case SELECT:
default:
  return "VDS";
```

The grant key format `role_id|object_type|object_path|privilege` supports any object type string. Adding `"PDS"` as a new type requires no schema migration.

`SqlGrant.GrantType` already has `PDS` as an enum value (line 87). `SqlGrantOnCatalog` accepts it. `CatalogGrantHandler` passes the `GrantType.name()` directly to `rbacService.grantPrivilege()` — so `GRANT SELECT ON PDS my.table TO ROLE analyst` already stores `analyst|PDS|my.table|SELECT` in the grant store.

### What to change

**Change `isRbacDeniedForVds()` to also check PDS**, or create a separate `isRbacDeniedForPds()`:

```java
private boolean isRbacDeniedForPds(DremioTable table, NamespaceKey key) {
  if (table instanceof ViewTable) {
    return false;  // handled by isRbacDeniedForVds
  }
  // Only enforce RBAC on promoted physical datasets if explicitly configured
  if (dremioConfig == null || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
    return false;
  }
  if (SystemUser.isSystemUserName(userName)) {
    return false;
  }
  if (rbacService == null) {
    return false;
  }
  // Check if any PDS grant exists for this table.
  // If NO PDS grant is configured for this path at all, PDS remains visible (opt-in enforcement).
  List<Grant> grants = rbacService.listGrantsByObject("PDS", key.getSchemaPath());
  if (grants.isEmpty()) {
    return false;  // No PDS grants configured: default-allow (backward-compat)
  }
  return !rbacService.hasPrivilege(userName, "SELECT", "PDS", key.getSchemaPath());
}
```

This design choice matters: if you make PDS enforcement deny-by-default (like VDS), every existing physical dataset becomes invisible until an admin grants SELECT. That would be a breaking change for existing deployments. The safe approach is **opt-in PDS enforcement**: only restrict a PDS if at least one PDS grant exists for that path, which signals the admin has intentionally restricted it.

**Change `resolveRbacObjectType()`** to return `"PDS"` for the `PDS` grant type when validation is called from `validatePrivilege()`. Currently, `validatePrivilege()` is called for ALTER on non-VDS paths; the object type resolution needs to distinguish VDS from PDS by checking the actual dataset type.

**Add `listGrantsByObject(objectType, objectPath)` call** — already exists in `RbacService.listGrantsByObject()`. No new API needed.

**Files to touch:**
- `CatalogImpl.java` — add `isRbacDeniedForPds()`, call it from `getTableNoResolve()`, `getTableNoColumnCount()`, `getTable()`, `bulkGetTables()`
- `CatalogImpl.resolveRbacObjectType()` — distinguish VDS vs PDS by checking the actual dataset type
- `CatalogServiceHelper.isVisibleToUser()` — already passes physical datasets through unconditionally (line 3135-3136). For PDS visibility, apply same opt-in logic

**What NOT to change:** `RbacService`, `GrantStore`, `RbacConfig`. The storage layer already handles `"PDS"` as an object type.

**Confidence:** HIGH for storage (no changes needed). HIGH for enforcement design (opt-in is safer). MEDIUM for edge cases around Iceberg/versioned PDS paths where table resolution is more complex.

---

## 4. Container Visibility Filtering

### Current state (verified)

`CatalogServiceHelper.filterByVisibility()` (line 3115) filters VDS and FUNCTION items in child listings. Containers (`FOLDER`, `SPACE`, `SOURCE`, `HOME`) are always visible (line 3142).

Top-level listing at lines 365-376 shows SPACEs and SOURCEs always, and applies `isFunctionVisibleToUser()` for top-level functions only.

There is no concept of "a SPACE is only visible if the user has grants on something inside it."

### What to change — the right approach

**Do NOT implement recursive visibility propagation.** Computing "does the user have any grant anywhere under this space?" requires traversing the entire namespace subtree, which is O(N) where N is the total number of datasets. This will time out for large catalogs.

**The correct model** is content-based: show all containers but hide the leaf objects (VDS, UDFs) the user has no grants on. The container structure (spaces, folders, sources) is always visible. This is the existing behavior and it is correct.

**If a container-level SHOW privilege is needed** (e.g., `GRANT SHOW ON SPACE analytics TO ROLE analyst`), implement it as:
1. Add `"SHOW"` privilege to the grant key format — already in `SqlGrant.Privilege` enum (line 74)
2. Add a SPACE/SOURCE/FOLDER object type to the grant
3. Filter top-level space/source listing against a SHOW grant

This is a separate feature from what v1.0 shipped. Scope it as an explicit requirement, not an implicit part of VDS visibility. The risk is: if you add SHOW enforcement, every space becomes invisible until granted — same breaking-change risk as PDS deny-by-default.

**Recommended scope for v1.1:** Keep containers always visible. Ensure the leaf-level filtering (VDS, UDF) is correctly applied at all listing entry points, including:
- `CatalogServiceHelper.getChildrenForPath()` — already calls `filterByVisibility()` at line 1119
- `CatalogServiceHelper.getCatalogEntityByPath()` — verify it also calls filtering
- `CatalogServiceHelper.getTopLevelEntities()` — spaces and sources are unfiltered; top-level UDFs already filtered

**Files to touch:**
- `CatalogServiceHelper.java` — audit all listing paths for completeness; add PDS-grant-based filtering if implementing opt-in PDS enforcement

**Confidence:** HIGH for "keep containers visible" design decision. LOW for "container-level SHOW privilege" — would need deeper research on impact.

---

## 5. VDS Lifecycle Privileges (ALTER and DROP)

### Current state (verified)

`CatalogImpl.validatePrivilege()` is called with `SqlGrant.Privilege.ALTER` at line 2444 (`dropPrimaryKey`). The `resolveRbacObjectType()` maps ALTER to `"VDS"` by default. This means ALTER enforcement is active but only for `dropPrimaryKey`.

`CatalogImpl.dropView()` (line 1854) does NOT call `validatePrivilege()`. A user can drop any VDS they can access.

`CatalogImpl.updateView()` (line 1790) does NOT call `validatePrivilege()`. A user can update any VDS.

`CatalogImpl.createView()` (line 1740) relies on `CREATE_VIEW` being checked by the SQL handler before this method is called — but only if the handler was written to check it. Verify `CatalogGrantHandler` enforces this.

### What to change

**Add `validatePrivilege(key, SqlGrant.Privilege.DROP)` to `dropView()`:**

```java
public void dropView(final NamespaceKey key, ViewOptions viewOptions) throws IOException {
  validatePrivilege(key, SqlGrant.Privilege.DROP);  // ADD THIS
  switch (getRootType(key)) {
    ...
  }
}
```

**Add `validatePrivilege(key, SqlGrant.Privilege.ALTER)` to `updateView()`:**

```java
public void updateView(NamespaceKey key, ...) throws IOException {
  validatePrivilege(key, SqlGrant.Privilege.ALTER);  // ADD THIS
  ...
}
```

**Update `resolveRbacObjectType()`** to handle DROP and ALTER and return the correct object type. Currently, the switch maps everything unknown to `"VDS"`. That is correct for VDS, but needs an explicit case:

```java
private String resolveRbacObjectType(NamespaceKey key, SqlGrant.Privilege privilege) {
  switch (privilege) {
    case EXECUTE:
      return "FUNCTION";
    case CREATE_VIEW:
    case SELECT:
    case ALTER:
    case DROP:
    default:
      return "VDS";  // already correct for views; PDS ALTER would need a check here
  }
}
```

**Grant side:** `GRANT ALTER ON VDS path TO ROLE editor` — already works with the existing `CatalogGrantHandler` since it stores `editor|VDS|path|ALTER`. No changes needed in grant/revoke handlers.

**Files to touch:**
- `CatalogImpl.java` — add `validatePrivilege()` calls in `dropView()` and `updateView()`

**What NOT to change:** Grant storage, RbacService, handlers. The enforcement mechanism is already in place; only the call sites are missing.

**Confidence:** HIGH. This is a straightforward call-site addition.

---

## 6. Supporting APIs — Changes Summary

### APIs that already exist and work (no changes)

| API | Location | Used for |
|-----|----------|---------|
| `RbacService.hasPrivilege(user, privilege, objectType, objectPath)` | `RbacService.java` | All enforcement checks |
| `RbacService.listGrantsByObject(objectType, objectPath)` | `RbacService.java` | PDS opt-in check, REST display |
| `CatalogImpl.validatePrivilege(key, privilege)` | `CatalogImpl.java` | Lifecycle privilege enforcement |
| `CatalogImpl.isRbacDeniedForVds(table, key)` | `CatalogImpl.java` | VDS read access |
| `CatalogImpl.isRbacDeniedForFunction(key)` | `CatalogImpl.java` | UDF access |
| `CatalogServiceHelper.filterByVisibility(children)` | `CatalogServiceHelper.java` | Child listing filter |
| `ViewExpander.expandRelNode()` with `withUser(viewOwner)` | `ViewExpander.java` | Definer rights expansion |
| `UserDefinedFunctionExpanderImpl.parseAndValidate()` with `withUser(owner)` | `UserDefinedFunctionExpanderImpl.java` | UDF body expansion identity |
| Grant key format supports any `objectType` string | `RbacConfig.grantKey()` | PDS as `"PDS"`, VDS as `"VDS"` |
| `SqlGrant.GrantType.PDS` | `SqlGrant.java` L87 | SQL parser for PDS grants |
| `SqlGrant.Privilege.DROP`, `ALTER` | `SqlGrant.java` L57, 51 | SQL parser for lifecycle grants |
| `DatasetConfig.owner` field | `dataset.proto` L34 | VDS definer identity storage |
| `CatalogIdentity` / `CatalogUser` | `CatalogIdentity.java`, `CatalogUser.java` | Identity type for definer |

### New APIs needed

None. All required interfaces exist. The work is wiring existing hooks, not adding new APIs.

---

## 7. Concrete File Change Manifest

| File | Change | Risk |
|------|--------|------|
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java` | Return `Optional.of(new CatalogUser(owner))` for VIRTUAL_DATASET when `owner` is non-null | LOW — single conditional, backward-compatible (empty string falls through to existing null) |
| `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` | Add `isRbacDeniedForPds()`, call from all `getTable*()` methods; add `validatePrivilege()` in `dropView()` and `updateView()` | MEDIUM — touching hot path; needs careful guard conditions |
| `dac/backend/src/main/java/com/dremio/dac/service/catalog/CatalogServiceHelper.java` | Audit listing paths; extend `filterByVisibility()` for opt-in PDS filtering | LOW — additive; existing VDS filter is not changed |
| `dac/backend/src/main/java/com/dremio/dac/service/datasets/DACViewCreatorFactory.java` | Verify `DatasetConfig.owner` is written at VDS create/update | LOW — add one line if not already set; low blast radius |

### Proto changes

None. `rbac.proto` does not need new message types. `dataset.proto` `owner` field already exists. No new KV stores, no new proto messages.

### SQL grammar changes

None. `PDS`, `ALTER`, `DROP`, `SHOW` are already in the grammar. `SqlGrantOnCatalog` and `SqlRevokeOnCatalog` already accept these tokens.

---

## 8. Alternatives Considered

| Decision | Chosen | Alternative | Why Not |
|----------|--------|-------------|---------|
| PDS enforcement | Opt-in (allow if no grants exist for the path) | Deny-by-default (like VDS) | Breaking: all existing physical datasets would disappear for non-admin users |
| Container visibility | Keep containers always visible | Recursive SHOW privilege check | O(N) subtree scan — unacceptable at scale |
| VDS owner resolution | Change `CatalogEntityOwnershipImpl` | Store owner in a new RBAC-specific store | Redundant; `DatasetConfig.owner` already exists and is the canonical source |
| UDF invoker semantics | Enforce via existing `isRbacDeniedForFunction()` | Enforce at the Calcite function expansion layer | `isRbacDeniedForFunction()` already covers all paths; planner layer would be duplicate |
| VDS DROP enforcement | Add call in `CatalogImpl.dropView()` | Add check in SQL handler before `dropView()` | Handler-only check misses REST API delete path |

---

## 9. Confidence Assessment

| Area | Level | Reason |
|------|-------|--------|
| VDS definer rights hook | HIGH | `ViewExpander.expandRelNode()` already does `withUser(viewOwner)`. Gap is only in `CatalogEntityOwnershipImpl` returning empty for VDS. |
| UDF owner resolution | MEDIUM | `FunctionConfig.owner` existence confirmed via `UserDefinedFunctionServiceImpl`; not traced through all UDF creation paths |
| PDS SELECT enforcement | HIGH for storage, MEDIUM for opt-in design | Grant key supports PDS; opt-in vs deny-by-default is a policy choice that needs explicit sign-off |
| Container visibility | HIGH | "Keep containers visible" is the correct design; recursive visibility would break at scale |
| VDS ALTER/DROP enforcement | HIGH | Pure call-site addition to existing `validatePrivilege()` mechanism |
| `DatasetConfig.owner` being written on VDS create | MEDIUM | Field exists but trace through `DACViewCreatorFactory` → `datasetVersionMutator.save()` was not fully verified |

---

*Research: 2026-02-20. Based on direct inspection of Dremio OSS codebase, branch `rbac`. All line numbers verified against live code.*
