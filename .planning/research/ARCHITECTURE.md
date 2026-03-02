# Architecture Patterns — RBAC Milestone 2

**Domain:** Dremio OSS RBAC — privilege context switching, VDS lifecycle, PDS SELECT, container visibility
**Researched:** 2026-02-20
**Confidence:** HIGH — based on direct codebase analysis, all findings from source code

---

## Recommended Architecture

### Summary of What Already Exists (Do Not Rebuild)

The existing RBAC system (Milestone 1) is fully operational:

- `CatalogImpl.validatePrivilege(NamespaceKey, SqlGrant.Privilege)` — single enforcement point for DDL operations
- `CatalogImpl.isRbacDeniedForVds(DremioTable, NamespaceKey)` — SELECT enforcement for VDS via `instanceof ViewTable` check
- `CatalogImpl.isRbacDeniedForFunction(NamespaceKey)` — EXECUTE enforcement for UDFs
- `RbacService.hasPrivilege(userName, privilege, objectType, objectPath)` — privilege resolution
- Feature flag `DremioConfig.RBAC_ENABLED` and system user bypass are wired in each check

**What is NOT yet wired:**
- `resolveRbacObjectType()` maps `ALTER` and `SELECT` both to `"VDS"` — no PDS support
- Container listing (`getSpaces()`, `getSources()`, `getFolders()`) has no RBAC filtering
- `isRbacDeniedForVds` only checks `ViewTable` instances — `MaterializedDatasetTable` (PDS) bypasses

---

## Component Boundaries

| Component | Responsibility | Location | Communicates With |
|---|---|---|---|
| `CatalogImpl` | Enforcement dispatcher | `com.dremio.exec.catalog` | `RbacService`, `DatasetManager`, all handlers |
| `RbacService` | Privilege resolution (existing) | `com.dremio.exec.rbac` | `GrantStore`, `RoleStore`, `MembershipStore` |
| `ViewExpander` | VDS SQL expansion with view owner identity | `com.dremio.exec.planner.sql` | `SqlValidatorAndToRelContext.Builder` |
| `UserDefinedFunctionExpanderImpl` | UDF expansion with UDF owner identity | `com.dremio.exec.ops` | `SqlConverter`, `SqlValidatorAndToRelContext.Builder` |
| `ViewExpansionContext` | Token tracking during nested view expansion | `com.dremio.exec.ops` | `ViewExpander`, `QueryContext` |
| `PlannerCatalogImpl` | View validation/conversion during planning | `com.dremio.exec.ops` | `ViewExpander`, `CatalogImpl` |
| `DescribeTableHandler` | DESCRIBE TABLE command | `com.dremio.exec.planner.sql.handlers.direct` | `Catalog.getTable()` |
| `ExplainHandler` | EXPLAIN PLAN command | `com.dremio.exec.planner.sql.handlers.direct` | `SqlHandlerConfig`, full query planning pipeline |
| `DropViewHandler` | DROP VIEW command | `com.dremio.exec.planner.sql.handlers.direct` | `catalog.validatePrivilege(path, ALTER)` |
| `CreateOrUpdateViewHandler` | CREATE/ALTER VIEW command | `com.dremio.exec.planner.sql.handlers.direct` | `catalog.validatePrivilege(path, CREATE_VIEW)` |

---

## Integration Point 1: VDS Definer Rights (Already Exists — Understand, Do Not Break)

### How VDS Expansion Uses the View Owner Identity

**Confidence: HIGH** — read directly from `ViewExpander.java` and `UserDefinedFunctionExpanderImpl.java`.

The identity context switching for definer rights is **already implemented** in Dremio's architecture. The flow is:

```
SELECT * FROM my_view
  -> PlannerCatalogImpl.getValidatedTableWithSchema(key)
     -> CatalogImpl.getTable(key)              [RBAC check: does invoker have SELECT on VDS?]
        -> DatasetManager.getTable()           [returns ViewTable with viewOwner field]
     -> convertView(ViewTable)
        -> ViewExpander.expandView(ViewTable)
           -> expandViewInternal(viewTable)
              -> viewOwner = viewTable.getViewOwner()  [CatalogIdentity — the view definer]
              -> viewExpansionContext.reserveViewExpansionToken(viewOwner)
              -> expandRelNode(viewTable, viewOwner, queryString)
                 -> builder.withUser(viewOwner)         [SWITCH TO DEFINER IDENTITY]
                 -> sqlValidatorAndToRelContext = builder.build()
                 -> sqlValidatorAndToRelContext.validate(parsedNode)
                    -> during validation, nested table lookups use definer's identity
```

Key classes:
- `ViewTable.getViewOwner()` returns `@Nullable CatalogIdentity` — the stored view creator
- `ViewExpansionContext.reserveViewExpansionToken(viewOwner)` tracks depth of nested identity switches
- `SqlValidatorAndToRelContext.Builder.withUser(CatalogIdentity)` creates a new catalog bound to the definer's identity
- `Catalog.resolveCatalog(CatalogIdentity)` clones `CatalogImpl` with the new identity — this new catalog's `this.userName` is the definer

**For RBAC Milestone 2:** The definer-rights switch already works at planning time. The **only new requirement** is that during definer expansion, the nested `CatalogImpl` (bound to the definer's identity) correctly checks the definer's RBAC privileges on any VDS/PDS the view references. If the current PDS SELECT feature is added (see Integration Point 4), definer expansion will automatically enforce it for the definer's access to the underlying PDS.

**No code change needed in the expansion path itself.** The work is in what the definer-identity catalog checks.

### Where viewOwner is Stored

`ViewTable.viewOwner` is populated from:
1. Non-versioned: `DatasetManager` retrieves the stored `DatasetConfig` owner field via `CatalogEntityOwnership.getCatalogEntityOwner(key)` (see `CatalogImpl.getUserDefinedFunctionOwner()` pattern)
2. Versioned (Nessie): `VDS` created from Nessie metadata; owner is tracked in `VersionedDatasetAdapter`

For the OSS KV-store path (spaces/home), views are stored via `ViewCreatorFactory` which stores the creating user's identity in the `View` proto.

---

## Integration Point 2: UDF Invoker Rights (Already Exists — Understand, Do Not Break)

### How UDF Expansion Uses Owner Identity

**Confidence: HIGH** — read directly from `UserDefinedFunctionExpanderImpl.java` (lines 130-141).

UDF expansion uses **owner's identity** (invoker rights are NOT the current pattern — Dremio UDFs run as owner):

```
SELECT my_udf(x) FROM my_table
  -> CatalogImpl.getFunctions(path, SCALAR)      [RBAC check: does invoker have EXECUTE?]
     -> isRbacDeniedForFunction(key) -> hasPrivilege(userName, "EXECUTE", "FUNCTION", path)
     -> getUserDefinedScalarFunctions(path)
        -> getUserDefinedFunctionOwner(path)      [fetches stored creator identity]
        -> new DremioScalarUserDefinedFunction(owner, udf)
  -> UdfConvertlet / TabularUserDefinedFunctionExpanderRule
     -> UserDefinedFunctionExpanderImpl.expandScalar(dremioUdf)
        -> parseAndValidate(dremioUdf, sqlNode)
           -> builder.withUser(dremioUdf.getOwner())   [SWITCH TO UDF OWNER IDENTITY]
           -> builder.build().validateAndConvertForExpression(...)
```

The check is: invoker must have EXECUTE (already enforced via `isRbacDeniedForFunction`), then expansion runs as the UDF owner (definer semantics). This is Dremio's current behavior.

**For RBAC Milestone 2:** If the goal is true invoker rights (UDF body runs as invoker, not owner), this would require changing `parseAndValidate` to NOT call `.withUser(owner)`, or passing both identities. This is architecturally invasive. The simpler and safer interpretation is:
- Keep owner-based expansion (existing)
- Add EXECUTE privilege enforcement (already done in Milestone 1)
- Document that Dremio UDFs have definer semantics by design

**Recommended: Do not change UDF expansion semantics. Milestone 2 EXECUTE enforcement is already in place.**

---

## Integration Point 3: Container Visibility Filtering

### How Catalog Listing Works

**Confidence: HIGH** — read from `CatalogImpl.java` listing methods.

Container listing methods delegate to `userNamespaceService` (a `NamespaceService` instance) with **no RBAC filtering**:

```java
// CatalogImpl.java
public List<SourceConfig> getSources() {
    return userNamespaceService.getSources();   // No RBAC filter
}

public List<SpaceConfig> getSpaces() {
    return userNamespaceService.getSpaces();    // No RBAC filter
}

public List<FolderConfig> getFolders(NamespaceKey rootPath) {
    return userNamespaceService.getFolders(rootPath);  // No RBAC filter
}
```

The `InformationSchemaCatalogImpl` (accessed via `iscDelegate`) also returns unfiltered results for `listCatalogs()`, `listSchemata()`, `listTables()`, `listViews()`.

The `listSchemas()` method queries the namespace index and returns schema names without privilege checks.

### Where to Add Container Visibility Filtering

There are two levels to consider:

**Level 1 — Source/Space visibility (coarse-grained):**
`CatalogImpl.getSources()` and `getSpaces()` return all items. For container visibility, the approach is to filter the returned list against user privileges.

**Level 2 — Dataset/View visibility (already handled):**
`getTable()` already calls `isRbacDeniedForVds()` — invisible VDS returns null (appears not found). The planner sees no VDS it cannot access.

**New component needed: `RbacVisibilityFilter`**

```
CatalogImpl.getSources()
  -> userNamespaceService.getSources()   [all sources]
  -> RbacVisibilityFilter.filterSources(sources, userName)
     -> for each source: rbacService.hasPrivilege(userName, "SELECT", "SOURCE", sourceName)
     -> return only visible sources
```

However, "SOURCE" visibility may not be the right granularity. A more practical approach: a container is visible if the user has any privilege on any object within it. This requires a broader scan.

**Simpler alternative (recommended for Milestone 2):** Containers (sources, spaces) are always visible. Only datasets within them are filtered. This matches Snowflake's behavior where a database is visible even if you have no tables in it. This avoids the expensive recursive visibility scan.

**Object type for grants:** `"SOURCE"`, `"SPACE"`, `"FOLDER"` as object types for container-level grants.

### listDatasets / listSchemas

The `listDatasets(NamespaceKey)` method (line 1341) uses a namespace index query and returns all datasets. For dataset-level visibility filtering, this would require post-filtering the iterator — an O(N) operation where N is the total dataset count.

**Approach:** Add RBAC filter to the iterator returned by `listDatasets()` and `listSchemas()` using `RbacService.hasPrivilege()` per entry. For large catalogs this is expensive but acceptable for Milestone 2.

---

## Integration Point 4: Table-Level SELECT for PDS

### Current State: PDS Bypasses RBAC

**Confidence: HIGH** — confirmed from `CatalogImpl.isRbacDeniedForVds()` source code.

The current `isRbacDeniedForVds()` method (line 2869) explicitly skips non-ViewTable instances:

```java
private boolean isRbacDeniedForVds(DremioTable table, NamespaceKey key) {
    // Only enforce RBAC on views (VDS), not physical datasets
    if (!(table instanceof ViewTable)) {
        return false;   // PDS always allowed
    }
    // ... VDS check
}
```

`MaterializedDatasetTable` (PDS) does not extend `ViewTable`, so it always passes through.

### What Changes for PDS SELECT Enforcement

Two things must change:

**1. Add `isRbacDeniedForPds()` method in `CatalogImpl`:**

```java
private boolean isRbacDeniedForPds(DremioTable table, NamespaceKey key) {
    if (table instanceof ViewTable) {
        return false;  // handled by isRbacDeniedForVds
    }
    if (!isPdsRbacEnabled()) return false;     // separate flag or same flag
    if (SystemUser.isSystemUserName(userName)) return false;
    if (rbacService == null) return false;

    // Check SELECT on the PDS using object type "PDS" or "TABLE"
    return !rbacService.hasPrivilege(userName, "SELECT", "PDS", key.getSchemaPath());
}
```

**2. Call it from the same call sites as `isRbacDeniedForVds()`:**

```java
// In getTable(NamespaceKey key):
if (table != null && (isRbacDeniedForVds(table, key) || isRbacDeniedForPds(table, key))) {
    return null;
}
```

Or merge into a single `isRbacDenied(table, key)` that handles both cases.

**Object type for PDS grants:** Use `"PDS"` (or `"TABLE"`) as the `objectType` string in `RbacService.hasPrivilege()`. Grant key becomes `roleId::PDS::source.schema.table::SELECT`.

**Important:** The `resolveRbacObjectType()` method currently maps `SELECT` to `"VDS"` — this must be updated to distinguish VDS vs PDS based on the actual table type.

### DESCRIBE and EXPLAIN PLAN

**DESCRIBE TABLE** — `DescribeTableHandler.toResult()` (line 96) calls `catalog.getTable(catalogEntityKey)`. Since `getTable()` already calls the RBAC checks (after your fix), DESCRIBE will automatically get the right behavior: if the user cannot SELECT the table, `getTable()` returns null and DESCRIBE returns "Unknown table". **No additional changes needed in DescribeTableHandler.**

**EXPLAIN PLAN** — `ExplainHandler.toResult()` delegates to the full planning pipeline (same as SELECT). The planning pipeline calls `getTable()` through `PlannerCatalogImpl` which calls `CatalogImpl.getTable()`. RBAC enforcement is inherited. **No additional changes needed in ExplainHandler.**

---

## Integration Point 5: VDS Lifecycle Privileges (ALTER, DROP)

### Current State

**Confidence: HIGH** — confirmed from handler source code.

Both ALTER (via `CREATE OR REPLACE VIEW`) and DROP VIEW already call `validatePrivilege`:

```java
// DropViewHandler.java line 55:
catalog.validatePrivilege(path, SqlGrant.Privilege.ALTER);

// CreateOrUpdateViewHandler.java line 105:
catalog.validatePrivilege(resolvedViewPath, SqlGrant.Privilege.CREATE_VIEW);
```

`validatePrivilege()` in `CatalogImpl` (line 2816) maps `ALTER` to `"VDS"` as object type. So `GRANT ALTER ON VDS myspace.myview TO ROLE r` already works with the existing framework.

**What is missing:** The `resolveRbacObjectType()` method maps both `ALTER` and `SELECT` to `"VDS"`. There is no special handling for `DROP` vs `ALTER`. The current code at line 2854:

```java
case ALTER:
default:
    return "VDS";
```

**`DROP` privilege:** `DropTableHandler` calls `validatePrivilege(path, Privilege.DROP)` (not `ALTER`). The current `resolveRbacObjectType()` falls through to `default: return "VDS"` which correctly routes it. A `GRANT DROP ON VDS` grant would enforce this.

**For ALTER VIEW specifically:** The `ALTER VIEW ... SET TBLPROPERTIES` path uses `CatalogImpl.alterView()` or equivalent via a separate call chain. The code at line 2287 shows `ALTER_VIEW_PROPERTIES` goes through `createOrUpdateView` → which calls `validatePrivilege(..., CREATE_VIEW)` — may need a dedicated `ALTER` check here rather than reusing `CREATE_VIEW`.

---

## Data Flow: New Features End-to-End

### Flow 1: SELECT on PDS

```
SELECT * FROM source.schema.my_table
  -> Planner: CatalogImpl.getTable(NamespaceKey["source","schema","my_table"])
     -> DatasetManager.getTable() -> MaterializedDatasetTable (not ViewTable)
     -> isRbacDeniedForVds(table, key) -> returns false (not ViewTable)  [existing]
     -> [NEW] isRbacDeniedForPds(table, key)
        -> rbacService.hasPrivilege(userName, "SELECT", "PDS", "source.schema.my_table")
        -> false (denied) -> return null (appears as "table not found")
        -> true (allowed) -> return MaterializedDatasetTable to planner
```

### Flow 2: VDS Expansion with Definer Access to PDS

```
SELECT * FROM my_space.my_view  [view SQL: SELECT * FROM source.my_table]
  -> CatalogImpl.getTable(["my_space","my_view"])
     -> ViewTable with viewOwner = CatalogIdentity("view_creator")
     -> isRbacDeniedForVds: rbacService.hasPrivilege(invoker, "SELECT", "VDS", "my_space.my_view")
        -> allowed: invoker has VDS SELECT grant
  -> PlannerCatalogImpl.convertView(ViewTable)
     -> ViewExpander.expandView(ViewTable)
        -> expandRelNode(viewTable, viewOwner="view_creator", sql)
           -> builder.withUser(CatalogUser("view_creator"))
           -> new CatalogImpl with userName="view_creator"
           -> validate("SELECT * FROM source.my_table") with definer's catalog
              -> CatalogImpl(view_creator).getTable(["source","my_table"])
                 -> MaterializedDatasetTable
                 -> [NEW] isRbacDeniedForPds: rbacService.hasPrivilege("view_creator", "SELECT", "PDS", "source.my_table")
                    -> view_creator must have PDS SELECT; invoker's PDS access is irrelevant
```

### Flow 3: Container Listing with Visibility

```
SHOW SCHEMAS  [or UI browse request]
  -> CatalogImpl.getSpaces() -> userNamespaceService.getSpaces()  [all spaces]
  -> [NEW] RbacVisibilityFilter.filterContainers(spaces, userName, rbacService)
     -> For each space: rbacService.hasPrivilege(userName, "SELECT", "SPACE", spaceName)
     -> Return only permitted spaces
```

### Flow 4: DROP VIEW Privilege Check

```
DROP VIEW my_space.my_view
  -> DropViewHandler.toResult()
     -> catalog.validatePrivilege(path, SqlGrant.Privilege.ALTER)
        -> CatalogImpl.validatePrivilege():
           -> rbacPrivilege = "ALTER"
           -> rbacObjectType = resolveRbacObjectType(key, ALTER) = "VDS"
           -> rbacService.hasPrivilege(userName, "ALTER", "VDS", "my_space.my_view")
           -> false -> throw UserException (permission denied)
           -> true -> proceed with drop
```

---

## New vs Modified Components

### Modified (Existing Files, Surgical Changes)

| File | Change | Line Range |
|---|---|---|
| `CatalogImpl.java` | Add `isRbacDeniedForPds()` method; call from all `getTable` variants | ~2870 area |
| `CatalogImpl.java` | Update `resolveRbacObjectType()` to return `"PDS"` for physical tables | ~2848 |
| `CatalogImpl.java` | Filter `getSources()`, `getSpaces()`, `getFolders()` results | ~3514, 3668, 3539 |
| `CatalogImpl.java` | Add `"SOURCE"` and `"SPACE"` to `resolveRbacObjectType()` or new method | ~2848 |
| `RbacService.java` | No changes needed — `hasPrivilege()` already accepts any objectType string | — |
| `GrantStore.java` / `RbacConfig.java` | Possibly add object type constants | — |

### New Components

| Component | Purpose | Location |
|---|---|---|
| `RbacVisibilityFilter` | Static helper: filter container lists against RBAC grants | `com.dremio.exec.rbac` |
| Grant DDL changes | Support `"PDS"`, `"SOURCE"`, `"SPACE"`, `"FOLDER"` as grantable object types | SQL parser, GrantPrivilegeHandler |
| SQL grammar extension | `GRANT SELECT ON PDS source.schema.table TO ROLE r` syntax | `SqlGrantPrivilege.java` or equivalent |

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Checking DatasetConfig.getType() Instead of instanceof

**What goes wrong:** Using `datasetConfig.getType() == DatasetType.PHYSICAL_DATASET` to detect PDS.

**Why bad:** `DatasetConfig` may be null (for snapshot/versioned tables). The `instanceof ViewTable` check is safe; `getDatasetConfig().getType()` may NPE.

**Instead:** Use `instanceof ViewTable` for VDS detection. For PDS, use `!(table instanceof ViewTable)` as the positive condition, with null guard on `table` first.

### Anti-Pattern 2: Changing ViewExpander Expansion Identity

**What goes wrong:** Changing `withUser(viewOwner)` to `withUser(queryUser)` to implement "true invoker rights."

**Why bad:** This breaks the existing security model where view owners can grant access to underlying sources without exposing direct PDS access. All existing tests will break.

**Instead:** Keep existing definer-rights expansion. If invoker rights are needed for a specific UDF scenario, scope it to that UDF only with a new flag.

### Anti-Pattern 3: O(N) Visibility Check in `listDatasets()` Without Caching

**What goes wrong:** Calling `rbacService.hasPrivilege()` for every dataset in a namespace scan of a large catalog.

**Why bad:** `listDatasets()` may return thousands of entries. One RocksDB read per entry is not acceptable at large scale.

**Instead:** Batch the check by fetching all grants for the current user once (`rbacService.listGrantsByObject()` or a user-grants query), then filter in-memory. Or defer dataset-level filtering to query time (table access) rather than listing time.

### Anti-Pattern 4: Using `validatePrivilege()` for PDS Read Enforcement

**What goes wrong:** Calling `validatePrivilege(key, SELECT)` in `getTable()` instead of the existing `isRbacDeniedForPds()` pattern.

**Why bad:** `validatePrivilege()` throws an exception. For `getTable()`, the contract is to return null (silent deny). Using an exception here would break callers that use getTable() for existence checks, not just privilege checks.

**Instead:** Use the silent `isRbacDeniedForPds()` pattern (returns boolean, caller returns null), matching the existing `isRbacDeniedForVds()` approach.

---

## Scalability Considerations

| Concern | At 100 users | At 10K users | Notes |
|---|---|---|---|
| PDS SELECT check per query | Single RocksDB get, negligible | Same — KV lookup | Key is `roleId::PDS::path::SELECT` |
| Container listing filter | O(containers), fast | O(containers), fast | Small number of sources/spaces |
| Dataset listing filter | O(datasets) per user | O(datasets) — potential bottleneck | Consider lazy/query-time filtering |
| Grant key cardinality | Low | May need composite key scan | Monitor RocksDB key count |

---

## Build Order for Milestone 2

Build order respects the dependency graph: enforcement changes must come before UI/listing changes, and new grant types must be parseable before they can be enforced.

**Step 1 — Extend grant object types (parser + store)**
Enable `"PDS"`, `"SOURCE"`, `"SPACE"`, `"FOLDER"` as valid object types in `GrantPrivilegeHandler` and `RevokePrivilegeHandler`. Add validation that the object exists. Update `resolveRbacObjectType()` in `CatalogImpl`. Enables: GRANT SELECT ON PDS ... syntax.

**Step 2 — PDS SELECT enforcement**
Add `isRbacDeniedForPds()` to `CatalogImpl`. Wire into all `getTable()` variants and `bulkGetTables()`. Unit test with physical dataset. This is a self-contained change that does not touch VDS or UDF paths.

**Step 3 — DESCRIBE / EXPLAIN verification**
Verify (no code change expected) that DESCRIBE TABLE and EXPLAIN PLAN inherit PDS SELECT enforcement through `getTable()`. Add integration tests.

**Step 4 — VDS lifecycle privileges (ALTER, DROP)**
The ALTER and DROP paths already call `validatePrivilege()` with the correct privilege. The only work is ensuring `"ALTER"` and `"DROP"` map correctly in `resolveRbacObjectType()` for VDS objects. Add integration tests for `DROP VIEW` and `CREATE OR REPLACE VIEW` with RBAC.

**Step 5 — Container visibility**
Add optional filtering to `getSources()` and `getSpaces()`. Introduce `"SOURCE"` and `"SPACE"` object types. Start with opt-in (flag or no-op if no SOURCE grants exist) to avoid breaking the default open-access behavior for sources.

**Step 6 — Integration tests end-to-end**
Test definer-rights flow: user A has SELECT on VDS, VDS owner has SELECT on PDS, user A cannot directly access PDS. Verify expansion succeeds (definer's access is used).

---

## Key Files by Integration Point

| Feature | Primary Files | Method |
|---|---|---|
| VDS definer rights (understand/verify) | `ViewExpander.java:119-169` | `expandViewInternal()` |
| VDS definer rights (understand/verify) | `ViewExpansionContext.java:93-107` | `reserveViewExpansionToken()` |
| VDS definer rights (understand/verify) | `SqlValidatorAndToRelContext.java` | `Builder.withUser()` |
| UDF owner expansion (understand/verify) | `UserDefinedFunctionExpanderImpl.java:129-141` | `parseAndValidate()` |
| PDS SELECT enforcement (NEW) | `CatalogImpl.java:2869-2895` | Add `isRbacDeniedForPds()` |
| PDS SELECT enforcement (NEW) | `CatalogImpl.java:2848-2857` | Update `resolveRbacObjectType()` |
| PDS SELECT enforcement (NEW) | `CatalogImpl.java:288-388` | All `getTable()` overloads |
| DESCRIBE (no change expected) | `DescribeTableHandler.java:96` | Uses `catalog.getTable()` |
| EXPLAIN (no change expected) | `ExplainHandler.java` | Delegates to full planning pipeline |
| DROP VIEW (existing, verify) | `DropViewHandler.java:55` | `validatePrivilege(path, ALTER)` |
| CREATE VIEW (existing, verify) | `CreateOrUpdateViewHandler.java:105` | `validatePrivilege(path, CREATE_VIEW)` |
| Container listing (NEW) | `CatalogImpl.java:3614-3670` | `getSources()`, `getSpaces()`, `getFolders()` |
| Grant type extension (NEW) | `GrantPrivilegeHandler.java` | Add `"PDS"`, `"SOURCE"`, `"SPACE"` |
| Grant type extension (NEW) | `RevokePrivilegeHandler.java` | Same |

---

## Confidence Assessment

| Area | Confidence | Basis |
|---|---|---|
| VDS definer rights (existing path) | HIGH | Read `ViewExpander.java`, `ViewExpansionContext.java`, `PlannerCatalogImpl.java` |
| UDF expansion with owner identity | HIGH | Read `UserDefinedFunctionExpanderImpl.java:136` — explicit `withUser(owner)` |
| PDS bypass (current behavior) | HIGH | Read `isRbacDeniedForVds` line 2871: `!(table instanceof ViewTable)` returns false |
| Container listing (no filter) | HIGH | Read `getSources()`, `getSpaces()` — direct namespace delegate, no filter |
| DESCRIBE inherits table check | HIGH | `DescribeTableHandler:96` calls `catalog.getTable()` which does RBAC |
| EXPLAIN inherits planning checks | HIGH | Delegates to full planning pipeline; `getTable()` is called through normal path |
| ALTER/DROP privilege (existing) | HIGH | Read `DropViewHandler:55`, `CreateOrUpdateViewHandler:105` |

---

*Research date: 2026-02-20. Based on direct analysis of Dremio OSS codebase at git branch `rbac` (commit 2cc3b3c3d).*
