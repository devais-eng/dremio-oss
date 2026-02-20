# RBAC Features: Table Stakes vs. Differentiators vs. Anti-Features

**Research Date:** 2026-02-20
**Research Type:** Features dimension — privilege context switching, PDS SELECT, container visibility, and VDS lifecycle privileges for Dremio OSS RBAC v2.
**Milestone:** Subsequent — extending the v1.0 RBAC baseline.

---

## Framing: What v1.0 Built and What v2 Adds

v1.0 shipped a flat, deny-by-default RBAC system covering:
- SELECT on VDS, EXECUTE on UDF, CREATE_VIEW on VDS
- Flat roles (CREATE/DROP ROLE, GRANT/REVOKE ROLE TO USER)
- Built-in ADMIN (bypass all) and PUBLIC (implicit membership) roles
- RocksDB persistence, 9 REST endpoints, SQL DDL, and 3 system tables

**What v1.0 explicitly deferred** (as "Out of Scope") that this milestone now targets:

| Deferred Item | Now Adding |
|---|---|
| Physical dataset (PDS) permissions | SELECT on PDS |
| Source-level/space-level permissions | Container visibility filter |
| Definer-rights model (VDS) | True definer-rights via stored VDS owner |
| Invoker-rights model (UDF) | UDF invocation uses caller's identity (already effectively true — needs hardening) |
| ALTER/DROP lifecycle on VDS | ALTER VIEW, DROP VIEW as separate grantable privileges |

---

## Codebase Baseline: What the Code Actually Does Today

Understanding the existing implementation is prerequisite to classifying new features. Research is based on direct codebase analysis (HIGH confidence for all findings below).

### Definer Rights — Current State (Gap Found)

`DatasetManager.createTableFromVirtualDataset()` calls `getEntityOwner(CatalogEntityKey)` to populate `ViewTable.viewOwner`. However, `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` returns `Optional.empty()` for all `VIRTUAL_DATASET` type entries — meaning `viewOwner` is always `null` for VDS stored in spaces.

When `viewOwner` is null, `ViewExpander.expandViewInternal()` falls through to the catch branch (handling `UserNotFoundException`) and calls `expandRelNode(viewTable, delegatedUser, queryString)` where `delegatedUser` = the caller's identity. This means view expansion today uses the **caller's identity**, not the definer's — there are no true definer rights in v1.0.

The `ViewExpansionContext` + `ViewExpansionToken` infrastructure exists and is correct; what is missing is the `viewOwner` being set from the VDS definition's creator/last-modifier metadata.

### Invoker Rights — Current State (Already Correct)

UDF EXECUTE is checked at `CatalogImpl.getFunctions()` against the **caller's** identity:
```java
if (isRbacDeniedForFunction(resolvedPath != null ? resolvedPath : path.toNamespaceKey())) {
    return ImmutableList.of(); // RBAC denied
}
```
The check uses `this.userName` (the requesting user), not any stored owner. This is exactly the invoker-rights model. No gap here — the model is correct. The v2 work is hardening and documenting it, not changing it.

### PDS SELECT — Current State (Gap Found)

`CatalogImpl.isRbacDeniedForVds()` explicitly guards:
```java
if (!(table instanceof ViewTable)) {
    return false; // Only enforce RBAC on views (VDS), not physical datasets
}
```
Physical datasets (PDS) are always accessible regardless of grants. This is a deliberate v1 decision that this milestone reverses.

### Container Visibility — Current State (Gap Found)

`CatalogServiceHelper.isVisibleToUser()` returns `true` for all container types:
```java
// FOLDER, SPACE, SOURCE, HOME are always visible (containers).
return true;
```
Spaces, sources, folders, and home spaces are always listed for all users. VDS and UDF are filtered by grants; PDS is always visible.

### VDS Lifecycle Privileges — Current State (Gap Found)

`CatalogImpl.createView()` and `CatalogImpl.dropView()` do not call `validatePrivilege()`. The `ALTER` and `DROP` values exist in `SqlGrant.Privilege` and VDS exists in `SqlGrant.GrantType`. The grant storage and enforcement infrastructure exists; the call sites in the lifecycle methods are missing.

### DESCRIBE Follows SELECT — Current State (Already Correct)

`DescribeTableHandler.toResult()` calls `catalog.getTable(catalogEntityKey)`. Since `getTable()` already has RBAC checks via `isRbacDeniedForVds()`, DESCRIBE is already gated by SELECT — no additional work needed. A user who cannot SELECT a VDS cannot DESCRIBE it.

### EXPLAIN — Current State (Already Correct)

EXPLAIN runs through the full query planning stack (SqlConverter, ViewExpander) using the same catalog. Since table resolution goes through `getTable()`, EXPLAIN inherits SELECT enforcement. The enforcement is already in the right place.

---

## Category 1: Table Stakes

*Must have for this milestone to be useful. These are the features specifically called out in the milestone scope.*

### 1.1 VDS Definer Rights: View Expansion Uses Creator's Identity

**What it is:** When a user queries a VDS, the expansion of that VDS's SQL definition resolves inner tables using the VDS creator's (or last modifier's) identity, not the querying user's. The querying user only needs SELECT on the VDS itself; the definer's grants cover inner PDS/VDS access.

**Why it's table stakes for this milestone:** Without this, the SELECT-on-VDS model is security-broken: a user granted SELECT on a VDS would fail during expansion if they lack access to the underlying PDS. Definer rights are the mechanism that makes view-based access control work.

**SQL standard basis:** SQL:1999 Section 11.53 defines `SQL SECURITY DEFINER` for routines. PostgreSQL, Oracle, MySQL, and Snowflake all implement this model for views. Views expand under the owner's privileges by default in SQL:2003 and most ANSI-compliant engines. (MEDIUM confidence — standard reference from training data, not verified against spec text.)

**How it works in this codebase:**
1. At VDS creation/update time, record the creator/last-modifier username in the VDS metadata (e.g., `DatasetConfig.VirtualDataset.owner` field or a separate field).
2. `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` must return this identity for `VIRTUAL_DATASET` type (currently returns `Optional.empty()`).
3. `DatasetManager.createTableFromVirtualDataset()` then sets `viewOwner` on `ViewTable` to a non-null identity.
4. `ViewExpander.expandViewInternal()` then calls `expandRelNode(viewTable, viewOwner, queryString)` with the definer's identity, which constructs the `SqlValidatorAndToRelContext` with that user — inner table resolution uses the definer's catalog, not the caller's.

**Complexity:** Medium. The planner infrastructure (ViewExpansionContext, ViewExpander) is ready. The gap is writing and reading the `owner` field on VDS metadata. This requires: (a) hooking into `createView()` to persist `userName` on the VDS config, (b) extending `CatalogEntityOwnershipImpl` to read that field for VDS, and (c) verifying that the inner catalog instance built with the definer's identity is privilege-checked against the definer's grants.

**Dependencies:** v1.0 SELECT on VDS enforcement. No new storage primitives needed.

**Interaction with PDS SELECT (1.3):** If PDS SELECT is also enforced, definer rights become load-bearing: the definer must have SELECT on the underlying PDS. Operators must grant SELECT on PDS to the VDS creator (or to a role they hold) when creating views over physical tables.

**Confidence:** HIGH (all details derived from direct codebase analysis).

---

### 1.2 UDF Invoker Rights: Caller's Privileges Used During UDF Execution

**What it is:** When a user calls a UDF, and that UDF's body accesses tables or other objects, those inner accesses are resolved using the **caller's** identity — not the UDF creator's. The caller needs both EXECUTE on the UDF and SELECT on any table the UDF body accesses.

**Why it's table stakes:** This is already how v1.0 works (see baseline analysis above). The task for v2 is: (a) documenting this as the explicit model, (b) confirming no code path breaks the invoker model, and (c) writing tests that verify it.

**SQL standard basis:** SQL:2003 defines `SQL SECURITY INVOKER` as the alternate to DEFINER for routines. Most analytical engines (Snowflake, BigQuery, SparkSQL) default UDFs to invoker rights because UDF bodies in analytical systems typically don't access raw tables directly — they operate on data already in the query plan. (MEDIUM confidence — standard reference from training data.)

**Complexity:** Low. No code change. This is verification, documentation, and test coverage.

**Note:** If UDF bodies in Dremio can reference named tables (e.g., `SELECT * FROM my_space.my_table` inside a UDF body), then invoker rights means the caller must have SELECT on that table. This is the correct and expected behavior but has UX implications: operators must grant SELECT on any table a UDF body reads to every role that receives EXECUTE on the UDF.

**Dependencies:** v1.0 EXECUTE on UDF enforcement (already complete).

**Confidence:** HIGH (confirmed from code; `getFunctions()` uses `this.userName`).

---

### 1.3 SELECT Privilege on Physical Datasets (PDS)

**What it is:** Extend RBAC enforcement to physical datasets (raw tables promoted from sources). A user must have SELECT granted on a PDS to query it, just like VDS.

**Why it's table stakes:** Without this, any user can bypass view-based access control by directly querying the underlying physical table. A user denied SELECT on a VDS (the filtered view) can run `SELECT * FROM source.schema.raw_table` and see everything. This is a fundamental security hole if the goal is data access control beyond "hide the view name."

**SQL standard basis:** Table-level SELECT grants are the most basic form of SQL privilege, defined in SQL:1992 and every subsequent revision. (HIGH confidence.)

**How it works in this codebase:**
- `CatalogImpl.isRbacDeniedForVds()` currently returns `false` immediately for non-ViewTable instances.
- A new `isRbacDeniedForPds(DremioTable table, NamespaceKey key)` method (or an extension of the existing check) must be added.
- The check must be gated on `table instanceof NamespaceTable` (or the equivalent PDS type) in addition to ViewTable.
- The `hasPrivilege()` call uses object type `"PDS"` to distinguish from `"VDS"`.

**Complexity:** Medium. The enforcement pattern is identical to VDS. The additional surface is:
- All `getTable*()` variants that currently short-circuit non-ViewTable.
- `bulkGetTables()` transformer must also include PDS denial.
- New grant storage entries with `objectType = "PDS"`.
- SQL DDL: `GRANT SELECT ON PDS <path> TO ROLE <role>`.
- REST API: support `PDS` as entity type in grant endpoints.

**Important constraint:** The PUBLIC role currently has implicit SELECT on all PDS (because `isRbacDeniedForVds()` returns false for non-ViewTable). Adding PDS enforcement will deny all users who haven't been explicitly granted. Migration plan: either auto-grant PUBLIC SELECT on all existing PDS when enabling PDS enforcement, or add a separate feature flag `services.rbac.pds.enabled`.

**Dependencies:** 1.1 (definer rights) — if definer rights are properly implemented, the VDS definer must hold SELECT on the PDS. PDS enforcement must come after or alongside definer rights to avoid breaking existing views.

**Confidence:** HIGH (derived from code analysis of `isRbacDeniedForVds()` guard).

---

### 1.4 Container Visibility Filtering: Sources/Spaces/Folders

**What it is:** Sources, spaces, and folders (containers) are only shown to a user if that user has access to at least one object within them (directly or transitively). The full ancestor path of any visible object must be visible.

**Why it's table stakes:** Currently all containers are always visible to all users. A user who has SELECT on `my_space.view_a` but nothing in `restricted_space` can still see `restricted_space` in the catalog listing. This confuses users and leaks existence information.

**Expected behavior from SQL standard perspective:** SQL standards do not specify catalog listing visibility rules. The convention in commercial databases is the "at least one accessible descendant" rule. PostgreSQL does not list schemas containing no visible objects. Snowflake hides databases and schemas when the user has no access to any child. Databricks Unity Catalog implements the same rule. (MEDIUM confidence — convention from training data across multiple systems.)

**How it works in this codebase:**
- `CatalogServiceHelper.filterByVisibility()` filters the `children` list returned by `namespaceService.list()`.
- `isVisibleToUser()` currently returns `true` for all container types.
- The fix: for container types (FOLDER, SPACE, SOURCE), recursively check whether the container has any accessible descendant.
- This is expensive if done naively. The standard implementation uses "does any grant exist with a path prefix matching this container?" — a prefix scan over the grant store.

**Complexity:** Medium-to-High.
- Simple approach: for each container, scan all grants where `objectPath` has a prefix matching the container path. O(grants) per container lookup. Acceptable for small grant sets.
- Scalable approach: add a container-level visibility index (set of container paths that have at least one grant). Updated on every GRANT/REVOKE. O(1) lookup.
- Edge case: the "full ancestor path shown" rule means if a user has SELECT on `space_a.folder_b.view_c`, then `space_a`, `space_a.folder_b`, and `space_a.folder_b.view_c` must all be visible.

**Interaction with pagination:** The v1 audit noted that `filterByVisibility()` is applied after pagination trim, so pages may be smaller than `maxChildren`. This pre-existing issue becomes more impactful when containers are also filtered.

**Dependencies:** v1.0 grant storage (prefix scan over existing `GrantStore`). Does not require new storage.

**Confidence:** HIGH (mechanism derived from codebase; behavior convention is MEDIUM).

---

### 1.5 ALTER VIEW Privilege (Separate from CREATE_VIEW)

**What it is:** A grantable privilege that controls who can alter (modify the SQL definition of) an existing VDS. Distinct from `CREATE_VIEW` (which governs creation) and `DROP` (which governs deletion).

**Why it's table stakes:** Without this, there is no way to allow a non-admin user to modify their own views without also allowing them to drop any view or create new ones. The lifecycle of a view has three distinct operations: create, modify, drop — each should be independently grantable.

**SQL standard basis:** SQL:1999 Section 11.10 defines `ALTER VIEW` as a distinct DDL operation. Oracle, PostgreSQL, and Snowflake all require the caller to be the owner or have explicit ALTER privilege on the view. (MEDIUM confidence — training data.)

**How it works in this codebase:**
- `CatalogImpl.createView()` handles both CREATE and CREATE OR REPLACE (ALTER). Currently no `validatePrivilege()` call.
- Add `validatePrivilege(key, SqlGrant.Privilege.ALTER)` inside `createView()` when `viewOptions.getActionType() == ViewOptions.ActionType.ALTER_VIEW_PROPERTIES` (an ALTER, not a fresh CREATE).
- For initial CREATE: check `CREATE_VIEW` privilege (already in v1.0 grants, but not enforced in `createView()`).
- `SqlGrant.Privilege.ALTER` exists. `SqlGrant.GrantType.VDS` exists. Infrastructure is complete.

**Complexity:** Low. One `validatePrivilege()` call in `createView()` for ALTER path, gated by `viewOptions.getActionType()`.

**Dependencies:** v1.0 privilege storage and `validatePrivilege()` implementation.

**Confidence:** HIGH (code path confirmed from `createView()` and `ViewOptions.ActionType`).

---

### 1.6 DROP VIEW Privilege (Separate from ALTER)

**What it is:** A grantable privilege that controls who can drop (delete) an existing VDS.

**Why it's table stakes:** Paired with 1.5. DROP is destructive and must be independently revocable.

**SQL standard basis:** `DROP` is a standard SQL privilege. (HIGH confidence.)

**How it works in this codebase:**
- `CatalogImpl.dropView()` currently has no `validatePrivilege()` call.
- Add `validatePrivilege(key, SqlGrant.Privilege.DROP)` at the start of `dropView()`.
- `SqlGrant.Privilege.DROP` exists. `SqlGrant.GrantType.VDS` exists.

**Complexity:** Low. One line addition. Mirror of ALTER VIEW (1.5).

**Dependencies:** v1.0 privilege storage and `validatePrivilege()` implementation.

**Confidence:** HIGH (code path confirmed).

---

### 1.7 CREATE_VIEW Privilege Enforcement (Completing v1.0 Gap)

**What it is:** The `CREATE_VIEW` privilege was grantable in v1.0 (via `SqlGrant.Privilege.CREATE_VIEW`) but `createView()` never calls `validatePrivilege()`. This means anyone can create views regardless of grants.

**Why it's table stakes:** PRIV-03 and ENFC-08 were v1.0 requirements that were marked satisfied but the enforcement call is absent from `createView()`. This is an existing gap that must be closed before ALTER/DROP enforcement is added, or the privilege model is incoherent.

**Complexity:** Low. One `validatePrivilege(key, SqlGrant.Privilege.CREATE_VIEW)` call in the CREATE path of `createView()`.

**Dependencies:** v1.0 privilege storage.

**Confidence:** HIGH (confirmed by absence of `validatePrivilege` in `createView()` via codebase search).

---

## Category 2: Differentiators

*Valuable but not essential for the milestone's core security model.*

### 2.1 DESCRIBE and EXPLAIN Privilege: Verification Pass

**What it is:** Confirm and test that DESCRIBE (via `DescribeTableHandler`) and EXPLAIN (via full planner path) inherit SELECT enforcement correctly.

**Why it's a differentiator:** The code analysis shows DESCRIBE already goes through `catalog.getTable()` which has RBAC checks. EXPLAIN also uses the full catalog. This is not new feature work — it is verification and test coverage. The feature is already there.

**Complexity:** Low. Write integration tests asserting: non-granted user gets "table not found" on DESCRIBE; non-granted user cannot EXPLAIN a query referencing an unganted VDS.

**Dependencies:** v1.0 enforcement. No code changes expected.

---

### 2.2 SELECT on PDS with Migration Strategy

**What it is:** The full feature from Table Stakes 1.3, but the "differentiator" aspect is the migration tooling: auto-grant PUBLIC SELECT on all existing PDS when the feature is enabled, preventing a rollout from locking out all users.

**Why it's a differentiator:** The migration is valuable but complex. The core enforcement (1.3) is table stakes; the migration smoothing is a differentiator because the system is still useful without it (administrators can manually grant).

**Complexity:** Medium. Requires a startup migration job that iterates all PDS in namespace service and inserts PUBLIC SELECT grants. Must be idempotent and gated on a separate config flag.

---

### 2.3 Batch Privilege Checks for Container Visibility

**What it is:** Instead of O(containers) individual grant lookups to compute container visibility, maintain a pre-built index (set of container path prefixes with at least one grant). Updated transactionally on GRANT/REVOKE.

**Why it's a differentiator:** The simple approach (prefix scan per container) works for small catalogs. The index approach scales to large catalogs with many containers.

**Complexity:** Medium. Requires modifying GRANT/REVOKE handlers to maintain the index. Adds complexity to the persistence layer.

---

### 2.4 Visibility API for Ancestor Path Expansion

**What it is:** A REST API endpoint that returns the set of visible containers for the current user, for use by the UI's catalog tree rendering.

**Why it's a differentiator:** The current filterByVisibility() approach requires the client to list children and rely on filtering. A direct "what can I see?" API is more efficient for tree rendering.

**Complexity:** Medium. New REST endpoint; depends on container visibility logic (1.4).

---

### 2.5 Audit Logging for Lifecycle Privilege Checks

**What it is:** Record in the audit log when ALTER VIEW or DROP VIEW is denied due to missing privilege.

**Why it's a differentiator:** Useful for security monitoring but not required for correctness.

**Complexity:** Low-to-medium. Depends on whether an audit log infrastructure exists.

---

### 2.6 GRANT ALL Syntax on VDS

**What it is:** `GRANT ALL ON VDS <path> TO ROLE <role>` expands to SELECT + ALTER + DROP in a single statement.

**Why it's a differentiator:** Pure ergonomics. The individual grants are sufficient.

**Complexity:** Low. `SqlGrant.Privilege.ALL` already exists in the enum; handler expansion needed.

---

## Category 3: Anti-Features

*Deliberately NOT in this milestone. Each entry explains why.*

### 3.1 VDS Security Invoker Mode (Optional per View)

**What it is:** An option on CREATE VIEW to choose `SQL SECURITY INVOKER` instead of the default definer mode.

**Why NOT:** Adds significant complexity — every view resolution must check the view's security mode attribute. Invoker mode on VDS combined with PDS SELECT enforcement would require the querying user to have SELECT on every table the view touches, defeating the abstraction purpose of views. Definer-rights-only is the correct default for a data lakehouse. Do not add a per-view toggle until there is a specific use case that requires it.

### 3.2 Column-Level Grants (GRANT SELECT (col1, col2))

**Why NOT:** Views already serve as column projection. Column-level GRANT adds privilege evaluation complexity proportional to query width. Use `CREATE VIEW AS SELECT col1, col2 FROM t` instead.

### 3.3 Row-Level Security (GRANT with WHERE policy)

**Why NOT:** Views and VDS already serve as row-filtering mechanism. Policy-based RLS adds a separate policy engine.

### 3.4 Source-Level or Space-Level GRANT (Container Grants)

**What it is:** `GRANT SELECT ON SPACE my_space TO ROLE reader` — the grant applies to all VDS in the space.

**Why NOT in this milestone:** Container grants are a large feature (requires path-prefix inheritance at privilege check time for every table resolution). The container visibility feature (1.4) only hides containers; it does not grant through them. Container-level grants are a v3 feature. Container visibility filtering is additive to per-object grants, not a replacement.

**Confusion risk:** Operators may expect that granting visibility to a container implies access to its contents. Document explicitly that container visibility is a display filter, not an access grant.

### 3.5 Nested Roles (Role Hierarchy)

**Why NOT:** Already excluded in v1.0. Flat roles remain sufficient. Recursive privilege resolution adds O(depth) cost per access check.

### 3.6 DENY Grants (Explicit Denial)

**Why NOT:** Deny-by-default already achieves the security goal. DENY adds confusing priority resolution (does DENY override GRANT to PUBLIC?). Never add DENY.

### 3.7 Cross-Source View Definer Rights (Impersonation Chain)

**What it is:** A VDS that joins tables across two impersonation-enabled sources would need the view definer's identity to have impersonation credentials on both sources.

**Why NOT:** This is a separate impersonation subsystem (`ImpersonationConf`, `getAccessUserName()`). Definer rights for the RBAC check layer (1.1) does not need to solve cross-source impersonation. Keep the scope to the RBAC identity context; impersonation is a connector-level concern.

---

## Feature Dependency Map

```
v1.0 baseline (SELECT on VDS, EXECUTE on UDF, flat roles, ADMIN/PUBLIC)
  |
  +-> 1.7 CREATE_VIEW enforcement (closes v1.0 gap -- do first)
  |
  +-> 1.5 ALTER VIEW privilege enforcement
  |     (requires 1.7 to be coherent)
  |
  +-> 1.6 DROP VIEW privilege enforcement
  |     (parallel with 1.5)
  |
  +-> 1.1 VDS Definer Rights
  |     - store owner on VDS metadata
  |     - CatalogEntityOwnershipImpl reads VDS owner
  |     - ViewExpander uses definer's catalog identity
  |
  +-> 1.3 SELECT on PDS
  |     - extend isRbacDeniedForVds() to NamespaceTable
  |     - new PDS grant type in storage
  |     - AFTER 1.1 (definer must hold PDS grants)
  |     - NEEDS migration plan before enabling
  |
  +-> 1.4 Container Visibility Filtering
  |     - prefix-scan grants for container path
  |     - modify isVisibleToUser() for FOLDER/SPACE/SOURCE
  |     - independent of 1.1-1.3
  |
  +-> 1.2 UDF Invoker Rights (verify + test only)
  |     - no code changes
  |     - integration tests
  |
  +-> 2.1 DESCRIBE/EXPLAIN verification (test only)
        - no code changes
        - integration tests

Differentiators (optional, after table stakes):
  2.2 PDS migration strategy -> depends on 1.3
  2.3 Container visibility index -> depends on 1.4
  2.4 Visibility REST API -> depends on 1.4
```

---

## Complexity Summary Table

| Feature | Category | Complexity | Key Dependency | New Storage? |
|---------|----------|-----------|----------------|--------------|
| 1.1 VDS definer rights | Table stakes | Medium | v1.0 grants, VDS metadata | No (reuse DatasetConfig) |
| 1.2 UDF invoker rights | Table stakes | Low (verify only) | v1.0 EXECUTE enforcement | No |
| 1.3 SELECT on PDS | Table stakes | Medium | 1.1 definer rights first | Yes (PDS grant entries) |
| 1.4 Container visibility | Table stakes | Medium-High | v1.0 grant store | No (prefix scan) |
| 1.5 ALTER VIEW privilege | Table stakes | Low | v1.0 validatePrivilege() | No |
| 1.6 DROP VIEW privilege | Table stakes | Low | v1.0 validatePrivilege() | No |
| 1.7 CREATE_VIEW enforcement | Table stakes | Low (gap close) | v1.0 privilege store | No |
| 2.1 DESCRIBE/EXPLAIN verify | Differentiator | Low (tests only) | v1.0 enforcement | No |
| 2.2 PDS migration strategy | Differentiator | Medium | 1.3 | No |
| 2.3 Container visibility index | Differentiator | Medium | 1.4 | Yes (prefix index) |
| 2.4 Visibility REST API | Differentiator | Medium | 1.4 | No |
| 2.5 Audit logging for lifecycle | Differentiator | Low-medium | Audit infra | No |
| 2.6 GRANT ALL on VDS | Differentiator | Low | v1.0 DDL handlers | No |

---

## Recommended Build Order (Table Stakes Only)

1. **Close v1.0 gap first (1.7):** Add CREATE_VIEW enforcement in `createView()`. Without this, the privilege model is incoherent (you can grant CREATE_VIEW but it is never checked). One call, one test class.

2. **VDS lifecycle (1.5, 1.6 in parallel with 1.7):** ALTER VIEW + DROP VIEW enforcement. Same pattern: `validatePrivilege()` in `createView()` ALTER branch and in `dropView()`. Low effort, high security value.

3. **Definer rights (1.1):** Store VDS creator on `DatasetConfig.VirtualDataset`, extend `CatalogEntityOwnershipImpl`, verify `ViewExpander` uses definer identity. This changes the security model — must be tested with integration tests covering: (a) caller has SELECT on VDS, definer has SELECT on underlying PDS, query succeeds; (b) caller lacks SELECT on PDS directly, query succeeds because definer has it; (c) definer loses SELECT on PDS, view queries fail even for granted callers.

4. **PDS SELECT (1.3):** After definer rights are confirmed working. Extend `isRbacDeniedForVds()` to cover `NamespaceTable`. Add PDS as grant object type. Coordinate with migration plan to avoid lockout.

5. **Container visibility (1.4):** Can be done in parallel with 3-4. Modify `isVisibleToUser()` to prefix-scan grants for container path. Integration-test with containers that have no descendants vs. containers with one accessible VDS.

6. **UDF invoker rights verify (1.2):** Write integration tests confirming caller identity is used for UDF EXECUTE check. No code change expected.

---

## Open Questions

These require phase-specific research or design decisions before implementation:

1. **Which field stores the VDS creator?** `DatasetConfig` has an `owner` field used for PDS files; for VDS it appears unused. Need to confirm the proto definition and whether `DatasetConfig.setOwner()` or a VDS-specific field is the right place. Should this be "first creator" or "last modifier"? (Convention: "last modifier" matches Oracle and PostgreSQL's `ALTER VIEW` ownership behavior — the definer is whoever last modified the view.)

2. **PDS enforcement rollout:** Is there a separate feature flag `services.rbac.pds.enabled` or does PDS enforcement activate with the same `services.rbac.enabled` flag? If the same flag, enabling RBAC (for VDS) also activates PDS enforcement, which denies all non-admin users PDS access on day 1. A separate flag or auto-grant migration is mandatory.

3. **Container visibility performance:** What is the expected scale of the grant store? If a deployment has 10,000 VDS and 500 containers, the naive prefix scan is 500 * 10,000 = 5,000,000 comparisons per catalog listing. The index approach (2.3) may be required at this scale.

4. **Definer identity persistence across restores:** If the coordinator database is restored from a backup, are user accounts consistent with VDS owner fields? What happens when the owner user is deleted?

5. **AT-specifier + definer rights:** The v1 audit noted `getTable(CatalogEntityKey)` AT-specifier path calls `getTableSnapshot()`. For versioned sources, does the view snapshot include the owner/definer at the snapshot point? Need to verify versioned source behavior with definer rights.

---

*Research: 2026-02-20. Derived from direct codebase analysis (CatalogImpl.java, DatasetManager.java, ViewExpander.java, ViewExpansionContext.java, CatalogEntityOwnershipImpl.java, CatalogServiceHelper.java, SqlGrant.java, RbacService.java) at HIGH confidence. SQL standard references and cross-database behavioral conventions at MEDIUM confidence (training data, not verified against spec text or official docs due to tool restrictions).*
