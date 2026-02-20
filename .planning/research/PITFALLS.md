# RBAC Pitfalls

**Research Date (v1.0):** 2026-02-17
**Research Date (v2.0 additions):** 2026-02-20
**Scope:**
- P1–P14: Adding deny-by-default RBAC to Dremio OSS — catalog-level enforcement via `CatalogImpl.validatePrivilege()` (v1.0)
- P15–P27: Adding privilege context switching (VDS definer rights, UDF invoker rights), table-level SELECT on PDS, container visibility filtering, and VDS lifecycle privileges (ALTER, DROP) to the existing v1.0 system (v2.0)

---

## P1 — The Bootstrap Deadlock: Who Grants the First ADMIN?

**Description:**
Deny-by-default means no user has any privilege until someone grants it. But `GRANT ROLE ADMIN TO USER alice` is itself an operation that requires ADMIN. If the `validatePrivilege()` enforcement is enabled before any ADMIN assignment exists, every user including the first one is locked out — including from the UI and REST API.

**Warning Signs:**
- New cluster fresh-installs fail to complete setup because the first login attempt hits a privilege check before any grant exists.
- Integration tests that wipe KV state and restart see "access denied" on the very first SQL query.

**Prevention Strategy:**
1. Wire the bootstrap sequence to the existing `BootstrapResource` (`dac/backend/src/main/java/com/dremio/dac/resource/BootstrapResource.java`) — the first user created via `/bootstrap/firstuser` must atomically receive the ADMIN role in the same transaction as user creation.
2. Alternatively, use an `Option` flag (via `OptionManager` / `ExecConstants`) that defaults to `false` (RBAC off). RBAC enforcement only activates after the flag is explicitly set. This gives operators a safe window to configure grants before enforcement starts.
3. At startup, if the RBAC KV store is empty and RBAC is enabled, log a loud ERROR and refuse to start (fail-fast) rather than silently accepting deny-all.

**Phase:** v1.0 Implementation — Phase 1, before any enforcement goes live.

---

## P2 — SYSTEM_USERNAME as a Silent Backdoor

**Description:**
Dremio has a `SystemUser.SYSTEM_USERNAME` that already bypasses authorization in dozens of places. `CatalogUtil.getSystemCatalog()`, `MetadataSynchronizer`, `ViewExpander.stringToRelRootAsSystemUser()`, and reflection/materialization jobs all run as the system user. If `validatePrivilege()` naively checks only username-based grants without an explicit system-user bypass, these internal operations will break. If the bypass is too broad, attackers who can impersonate the system user get free access.

**Warning Signs:**
- Metadata refresh jobs fail after enabling RBAC (`MetadataSynchronizer` uses `SYSTEM_USERNAME`).
- Reflection/acceleration refreshes fail (ReflectionManager calls catalog as system user).
- Dataset lineage updates (`MetadataSynchronizer.updateDatasetLineageMetadata()`) throw access denied.

**Prevention Strategy:**
1. Make `validatePrivilege()` an explicit no-op when the catalog was constructed with `CatalogUser.from(SystemUser.SYSTEM_USERNAME)` — check the `SchemaConfig` identity at the entry point.
2. Do not rely on role membership for the system user — hard-code the bypass in `validatePrivilege()` rather than granting ADMIN to the system user (granting would create a persistent record that could confuse audits).
3. Audit all `CatalogUtil.getSystemCatalog()` call sites before enabling enforcement to confirm none of them originate from user-controlled inputs.

**Phase:** v1.0 Implementation — before any enforcement check is active.

---

## P3 — The Definer-Rights Confusion: Check Once at the Outer Layer Only

**Description:**
Views use definer rights: `ViewExpander` calls `builder.withUser(viewOwner)` so inner table resolution runs under the view creator's identity, not the caller's. This is the correct SQL standard behavior and the security model is sound — but implementing it wrong is easy. Common mistakes: (a) checking `SELECT` privilege on both the outer view AND inner tables for the end-user, which breaks definer rights; (b) failing to check the outer view at all because the planner has already resolved it through definer rights; (c) not checking privilege at the point where the view is first looked up (`getTable`/`getDataset`) before the view expander takes over.

**Warning Signs:**
- A user with `SELECT` on view `V` cannot query it because the inner table access check fails under their identity.
- A user with no grants on view `V` can query the underlying table directly if that table was promoted and has no privilege check.
- UDF calls fail because `UserDefinedFunctionExpanderImpl` switches identity before privilege check is evaluated.

**Prevention Strategy:**
1. Place the `SELECT` privilege check in `DatasetManager` at the point where the `DremioTable` is first resolved for the calling user — before the view expansion machinery runs. The check must use the caller's identity, not the view owner's.
2. For UDFs, check `EXECUTE` privilege before `UserDefinedFunctionExpanderImpl` switches to the definer identity. The check point is `CatalogImpl.getFunction()`.
3. Write a test: user A creates view V over table T; user B has `SELECT` on V but no grant on T; confirm user B can query V and that the inner table read uses user A's identity.

**Phase:** v1.0 Implementation — the most critical correctness test to have before enabling enforcement.

---

## P4 — Access Path Gaps: SQL is Not the Only Door

**Description:**
Dremio has at least four distinct client paths: SQL via JDBC (port 31010), REST API (port 9047), Arrow Flight (port 32010), and internal gRPC (Fabric, port 45678). `CatalogImpl.validatePrivilege()` is in the catalog layer, which is shared — but some REST endpoints bypass the catalog entirely and interact with `NamespaceService` or `DatasetVersionResource` directly. `DACSecurityContext.isUserInRole()` currently returns `true` unconditionally for all roles, which means any JAX-RS `@RolesAllowed` annotation on REST resources is effectively disabled.

**Warning Signs:**
- A user denied `SELECT` on a view via SQL can still retrieve its definition via `GET /api/v3/catalog/{id}`.
- Arrow Flight clients connecting directly don't hit the same code path as JDBC.
- `DatasetVersionResource` (1422 lines, flagged as a God class) performs dataset operations without going through `CatalogImpl`.

**Prevention Strategy:**
1. Update `DACSecurityContext.isUserInRole()` (`dac/backend/src/main/java/com/dremio/dac/server/DACSecurityContext.java`) to call the RBAC store for real role lookups rather than returning `true`.
2. Audit all `@Path` REST resources in `dac/backend/src/main/java/com/dremio/dac/resource/` for direct `NamespaceService` calls that bypass `CatalogImpl`.
3. Arrow Flight session creation (`DremioFlightAuthUtils`) authenticates via tokens — verify the catalog instance created per Flight session is the same privilege-enforcing instance used by SQL queries.
4. After the first enforcement build, run a systematic access test: for each privilege type, attempt access via SQL, REST, and Flight independently.

**Phase:** v1.0 Implementation — Phase 2 after catalog-layer checks are done; REST/Flight gaps are second priority but must not be deferred past MVP.

---

## P5 — Cache Invalidation: Grants Change, Cached Decisions Don't

**Description:**
`CachingCatalog` (`sabot/kernel/src/main/java/com/dremio/exec/catalog/CachingCatalog.java`) caches catalog state per query. If privilege decisions are cached (e.g., a positive access check cached for a user), a subsequent `REVOKE` of that privilege will not take effect until the cache expires. In multi-coordinator deployments, the problem is worse: the token invalidation bug already noted in CONCERNS.md (`TokenManagerImpl` cache is per-coordinator and not broadcast) applies equally to any per-coordinator grant cache.

**Warning Signs:**
- After revoking a grant, the affected user continues to access the resource for several minutes.
- Coordinator 2 still allows access after a revoke issued on Coordinator 1.

**Prevention Strategy:**
1. Do not cache grant decisions across query boundaries. The per-query `CachingCatalog` is acceptable because it lives only for one query's lifetime — do not add a longer-lived privilege cache initially.
2. If a grant cache is introduced later for performance (see P6), implement invalidation via NATS pub/sub (`services/pubsub-nats/`) — the infrastructure already exists for distributed messaging.
3. RocksDB reads for grant lookups are local and sub-millisecond at the record count expected for v1 (thousands of grants). Defer caching until profiling shows it is necessary.

**Phase:** v1.0 Implementation — avoid the problem by not caching; revisit in optimization phase.

---

## P6 — Performance: A KV Lookup on Every Catalog Resolution

**Description:**
`validatePrivilege()` will be called on every `getTable`/`getDataset`/`getFunction` resolution, which happens for every table reference in every query — including multi-table joins, subqueries, and reflection-rewrite substitution. For a complex query with 20 table references, that is 20+ RocksDB lookups per query. The existing `BatchLookupOptimiser` in `NamespaceService` already exists because individual namespace lookups were a bottleneck. Adding unbatched KV reads inside the hot planning path is a known anti-pattern in this codebase.

**Warning Signs:**
- Query planning latency increases by more than 5ms per table reference after enabling RBAC.
- Profiling shows `validatePrivilege` appearing in query planning flame graphs.

**Prevention Strategy:**
1. For v1, accept the KV reads — at the scale of OSS deployments (tens not thousands of concurrent queries), RocksDB local reads will not be the bottleneck.
2. Implement a request-scoped grant cache keyed by `(userId, privilege, entityKey)` — populated on first check per query, evicted when the query ends. This is safe because no grant changes during a single query's planning phase.
3. Batch the privilege check for all tables in a query plan using a set lookup rather than per-table sequential reads.
4. Do not implement cross-request caching (see P5) until a multi-coordinator invalidation mechanism is in place.

**Phase:** Optimization — after correctness is validated; monitor before optimizing.

---

## P7 — The Migration Lock-Out: Existing Views and UDFs Have No Owner

**Description:**
Enabling deny-by-default on an existing deployment means every view and UDF that was created before RBAC existed has no owner and no grants. All existing queries will fail immediately. Views stored in `NamespaceService` have a `VirtualDataset.getOwner()` field — if that field is empty or null for legacy datasets, the definer-rights model breaks and the privilege check has no valid entity to match against.

**Warning Signs:**
- Turning on RBAC flag on an existing cluster breaks all existing view queries.
- `VirtualDataset.getOwner()` returns null or empty string for views created before RBAC was added.
- UDF owner field (`DremioScalarUserDefinedFunction.getOwner()`) is unpopulated.

**Prevention Strategy:**
1. Before enabling enforcement, run a migration job that reads all `VirtualDataset` records from the namespace store and backfills an empty owner with the username that created the dataset (using job history if available, or defaulting to a known admin user).
2. Implement a "grant PUBLIC SELECT on all existing views" migration step that runs atomically with enabling RBAC. The PUBLIC role (which all users implicitly belong to) serves as the open-access default for pre-existing datasets.
3. Provide an `--rbac-migrate` command or startup flag that runs the migration before enforcement activates, with a dry-run mode that reports what would change.

**Phase:** Migration — must be completed before production deployment of enforcement.

---

## P8 — EE Conflict: Clobbering the Enterprise RBAC

**Description:**
Dremio Enterprise Edition has its own production-grade RBAC implementation that also implements `validatePrivilege()`. The OSS build and EE build share the same `CatalogImpl.java`. The OSS no-op is replaced by the EE implementation via dependency injection or class override. If the OSS RBAC implementation creates conflicting KV store keys, protobuf types, or SQL grammar changes, it will corrupt EE deployments or cause merge conflicts that block upstream synchronization.

**Warning Signs:**
- KV store key prefixes for OSS RBAC tables clash with EE key prefixes.
- Protobuf message names added for OSS RBAC conflict with EE proto definitions.
- SQL grammar changes for `GRANT`/`REVOKE` handlers conflict with EE handler registration.

**Prevention Strategy:**
1. Namespace all OSS RBAC KV store keys with a distinct prefix (e.g., `"oss_rbac_"`) that will not collide with EE key spaces.
2. Do not modify the `SqlGrant` / `SqlCreateRole` parsers — they already parse correctly. Only add the handler implementations that dispatch from the existing no-op or `UnsupportedOperationException` handlers.
3. Place all new RBAC code under a package that EE does not touch: `com.dremio.exec.catalog.rbac` or similar. The `validatePrivilege()` override in EE should remain the canonical implementation; OSS should provide a distinct, independently testable one.
4. Before merging, confirm that the OSS RBAC feature flag (`Option`) defaults to `false` so EE deployments (which have their own RBAC active) are unaffected.

**Phase:** Design — namespace and packaging decisions must be made before writing any persistence code.

---

## P9 — The Implicit ADMIN: Internal System Operations That Must Not Be Blocked

**Description:**
Internal operations that run as the system user (reflections, metadata sync, schema refresh) are covered by P2. But there is a subtler case: jobs submitted on behalf of a user but executed by an internal service. `LocalJobsService` has `// TODO (DX-17909): Add and use username in request` comments — meaning authorization username is currently a no-op in job cancellation and retrieval flows. If RBAC privilege checks are added to catalog access during job execution but the username is not correctly propagated through the job service, internal operations will fail with permission denied under the wrong identity.

**Warning Signs:**
- `DX-17909` comment still present in `LocalJobsService` at lines 3084 and 1534.
- Scheduled refresh jobs (reflection refresh, metadata sync) fail with access denied after enabling RBAC.
- Job cancellation by admin fails because the job was recorded under `SYSTEM_USERNAME` but queried under a user identity.

**Prevention Strategy:**
1. Do not add privilege checks inside the job execution path (fragment execution, `LocalJobsService`). Privilege is checked once at query submission time in the catalog layer.
2. Audit any code path that creates a `Catalog` instance during job execution: confirm those all either use `SYSTEM_USERNAME` (and are exempt by P2's bypass) or correctly carry the original submitting user's identity.
3. Flag `DX-17909` as a dependency risk — the authorization username gap in job service means audit logging of who did what will be inaccurate even if access control is correct.

**Phase:** v1.0 Implementation — audit before enabling enforcement.

---

## P10 — INFORMATION_SCHEMA and sys Tables: Privilege Leakage Through Metadata

**Description:**
A user who is denied `SELECT` on view `V` should not be able to discover `V`'s existence, schema, or SQL definition via `INFORMATION_SCHEMA.VIEWS`, `sys.privileges`, or the REST catalog API. Failing to filter these metadata results by the caller's grants is a privilege escalation: even without data access, schema information reveals business logic, column names, and join relationships.

**Warning Signs:**
- `SELECT * FROM INFORMATION_SCHEMA.VIEWS` returns views the user has no `SELECT` grant on.
- `GET /api/v3/catalog` lists datasets the user cannot query.
- `sys.privileges` is readable by all users (it should only be readable by ADMIN or by users querying their own grants).

**Prevention Strategy:**
1. Filter `INFORMATION_SCHEMA.VIEWS` and `INFORMATION_SCHEMA.TABLES` results by the calling user's effective grants — only return rows for entities the user can actually access.
2. For v1, restrict `sys.privileges`, `sys.roles`, and `sys.membership` to ADMIN-only read access. Regular users can see only their own rows.
3. Apply the same `validatePrivilege(SELECT)` check in the `InformationSchemaCatalog` implementation for view/function listing endpoints.

**Phase:** v1.0 Implementation — Phase 2; can be deferred from MVP but must be tracked.

---

## P11 — Broad Error Messages That Reveal Object Existence

**Description:**
If `validatePrivilege()` throws "You do not have SELECT privilege on view `finance.revenue_2024`", a user without access has confirmed that `finance.revenue_2024` exists. The correct behavior for deny-by-default is to return `NOT FOUND` (not `FORBIDDEN`) for entities the user has no `SELECT` on — identical to how the object would appear if it did not exist. This prevents enumeration attacks.

**Warning Signs:**
- Access-denied exceptions include the full path of the denied object.
- Error messages say "Access denied to `X`" vs "Object `X` not found" depending on whether the object exists.

**Prevention Strategy:**
1. In `validatePrivilege()`, throw `UserException.validationError().message("Object not found")` rather than a permission-denied message. Use Dremio's existing `UserException` patterns — consistent with how `CatalogEntityNotFoundException` is surfaced.
2. Reserve permission-denied messages for operations where the user already knows the object exists (e.g., `DROP VIEW` when you own it, but ADMIN revoked your DDL privilege).
3. This is specifically important for `getTable`/`getDataset` in the dataset resolution path — those already return `null` for not-found; a privilege failure should also return `null` (not found) rather than throw.

**Phase:** v1.0 Implementation — bake into the initial `validatePrivilege()` implementation.

---

## P12 — KV Store Schema Evolution: Protobuf Changes Cannot Break Existing Records

**Description:**
RBAC grant records, role records, and membership records will be stored in RocksDB via protobuf serialization. Dremio uses `ProtostuffSerializer` for some stores and direct protobuf for others. If field numbers are reused, required fields are added, or enum values are removed in a schema update, existing records in RocksDB will fail to deserialize — silently returning nulls or throwing on read. This is particularly dangerous for RBAC because a deserialization failure on a grant record could default to "no grant" (deny) or crash the coordinator.

**Warning Signs:**
- After a version upgrade, privilege checks fail for users who had explicit grants before the upgrade.
- RocksDB records from the previous version throw protobuf parse errors in logs.

**Prevention Strategy:**
1. Use proto3 for all new RBAC message types — all fields are optional by default, which is safe for forward/backward evolution.
2. Never reuse field numbers in proto definitions, even after removing a field.
3. Test deserialization of records written by the previous version as part of the upgrade integration test suite.
4. Choose a well-defined key schema (e.g., `{storePrefix}/{entityId}/{userId}/{privilege}`) so that key parsing is also version-stable.

**Phase:** Design — before writing any protobuf definitions.

---

## P13 — The DACSecurityContext `isUserInRole()` Time Bomb

**Description:**
`DACSecurityContext.isUserInRole(String role)` currently returns `true` for every role check. This means every `@RolesAllowed("admin")` annotation on REST resources has been silently ineffective. When RBAC is enabled and `isUserInRole()` is updated to return real results, any JAX-RS resource that was relying on the broken implementation to allow all access will suddenly enforce role checks. This could break REST-based admin operations that legitimate admin users depend on — if their role name does not exactly match the string passed to `isUserInRole()`.

**Warning Signs:**
- REST endpoints that previously worked for all users return 403 after `isUserInRole()` is fixed.
- REST admin endpoints become inaccessible to the ADMIN role because the role name string does not match the expected JAX-RS role string.

**Prevention Strategy:**
1. Before fixing `isUserInRole()`, audit all `@RolesAllowed` annotations in REST resources to catalog what role strings are expected.
2. Map JAX-RS role strings to RBAC role names explicitly. The ADMIN built-in role should be the canonical answer for `@RolesAllowed("admin")`.
3. Fix `isUserInRole()` in a separate, isolated change from the `validatePrivilege()` implementation. Treat it as a REST authorization layer distinct from the catalog layer.

**Phase:** v1.0 Implementation — Phase 2, after catalog-level enforcement is stable.

---

## P14 — Legacy KV Store API: Don't Add Debt to a Deprecated Layer

**Description:**
Dremio's `LegacyKVStore` / `LegacyKVStoreProvider` are fully `@Deprecated` but still in active use. If RBAC store classes are implemented using the deprecated API, they join the cleanup debt pile. More concretely, if the legacy API is removed in a future cleanup, RBAC code written against it breaks.

**Warning Signs:**
- RBAC store implementation imports `com.dremio.datastore.api.LegacyKVStore` or `LegacyKVStoreProvider`.

**Prevention Strategy:**
1. Implement RBAC stores using the current `KVStore` / `KVStoreProvider` API (`com.dremio.datastore.api.KVStore`), not the legacy layer.
2. If existing RBAC-adjacent infrastructure (e.g., `AccessControlListingManager`) uses the legacy API, implement the RBAC store independently and do not extend the legacy code.
3. Use `KVStoreCreationFunction` as the standard store registration pattern, consistent with `TokenManagerImpl` and `NamespaceServiceImpl`.

**Phase:** v1.0 Implementation — first line of code constraint.

---

## P15 — Privilege Escalation via Stale Definer Identity

**What goes wrong:**
VDS definer rights require that when user B queries view V (owned by user A), the inner table lookups run under A's identity. If user A's grants are revoked *after* view V is created, V should become inaccessible to everyone — because the definer no longer has the required privilege. The pitfall is implementing definer rights by snapshot: caching or materialising A's grants at view-creation time (e.g., storing them in the `VirtualDataset` proto) rather than resolving A's live grants at query time.

**Why it happens:**
The temptation is to store the definer's effective grants in the `VirtualDataset` proto alongside the owner field to avoid KV lookups during expansion. This produces a frozen-grant snapshot that survives revocations.

**Consequences:**
User A is revoked SELECT on table T. V is defined as `SELECT * FROM T` (owned by A). User B queries V. If grants were snapshotted, B still gets data. The admin's revocation of A's grant on T has no effect on V's queries until the VDS is manually invalidated.

**Prevention:**
1. Never store resolved grants in VDS metadata. Store only the owner identity (username string). At expansion time, the privilege check against A's current grants runs through the live KV store path: `rbacService.hasPrivilege(viewOwner, "SELECT", objectType, objectPath)`.
2. The `ViewExpander.expandViewInternal()` already switches to the `viewOwner` identity via `builder.withUser(viewOwner)`. The privilege check must happen inside the new catalog instance that `resolveCatalog(viewOwner)` creates — not before the switch. Confirm `CatalogImpl.isRbacDeniedForVds()` runs against the definer's `userName` field, not the outer caller's.
3. Write the regression test: GRANT SELECT on T to role_A. User A creates V = SELECT * FROM T. REVOKE SELECT on T from role_A. User B queries V. Expect: access denied (definer A cannot read T anymore).

**Warning signs:**
- Grant revocations don't break existing view queries immediately.
- Integration tests that revoke a definer's grant still pass against V.

**Phase:** v2.0 Definer Rights Implementation — Phase 1.

---

## P16 — Definer-Rights Check Against the Wrong CatalogImpl Instance

**What goes wrong:**
In `isRbacDeniedForVds()`, the check `rbacService.hasPrivilege(userName, ...)` uses the `CatalogImpl.userName` field — the field is set at construction time and is immutable for the life of a `CatalogImpl` instance. During VDS expansion, `ViewExpander` calls `builder.withUser(viewOwner)` which calls `resolveCatalog(viewOwner)` which creates a **new** `CatalogImpl` instance with `userName = viewOwner`. Inner table lookups go through this new instance. If the v2.0 implementation incorrectly attaches the privilege check on the inner table to the outer `CatalogImpl` (the caller's instance), the inner table check runs under the caller's identity, not the definer's.

**Why it happens:**
In Java, `this.userName` in `CatalogImpl` is the field that `isRbacDeniedForVds()` reads. The outer and inner `CatalogImpl` instances are both live in the call stack simultaneously during view expansion. It is easy to pass the wrong `CatalogImpl` reference — especially via lambdas or callbacks — and have the inner check fire against the outer instance's `userName`.

**Consequences:**
User B has SELECT on view V but not on table T. Definer A has SELECT on T. The inner table check fires under B's identity. B is denied T and therefore denied V. Definer rights don't work at all — V is effectively inaccessible to anyone who doesn't also have direct T access.

**Prevention:**
1. Trace the exact call graph: `ViewExpander.expandViewInternal()` → `builder.withUser(viewOwner)` → `catalog.resolveCatalog(viewOwner)` → new `CatalogImpl` with `userName = viewOwner`. Verify that inner `getTable()` calls go through this new `CatalogImpl` and that `isRbacDeniedForVds()` inside it reads `this.userName` = viewOwner.
2. Add a `@VisibleForTesting` accessor for `CatalogImpl.userName` and assert its value in the inner table check test.
3. Do not share `CatalogImpl` instances across concurrently running privilege context switches. Each `withUser()` call must produce a fresh instance.

**Warning signs:**
- User with SELECT on V but not T cannot query V even when definer has SELECT on T.
- Adding a direct SELECT on T to user B's role fixes the V query — the outer check is running when it should not.

**Phase:** v2.0 Definer Rights Implementation — Phase 1.

---

## P17 — Recursive VDS Chain: Missing Cycle Guard in Definer Context Stack

**What goes wrong:**
VDS-over-VDS is legal: V3 = SELECT * FROM V2, V2 = SELECT * FROM V1, V1 = SELECT * FROM T. Each view can have a different owner. The existing `ViewExpansionContext` tracks per-owner token counts (the `userTokens: ObjectIntHashMap`) and issues/releases tokens at each expansion level. This is a token-count mechanism, not a cycle-detection mechanism. A cyclic view definition (V1 references V2 which references V1) will recurse until a `StackOverflowError`. The token counter never detects that V1 is being expanded again.

**Why it happens:**
`ViewExpansionContext.reserveViewExpansionToken()` increments a counter per owner but never asserts a depth limit or detects revisiting the same view path. The cycle guard is absent from the existing code at `sabot/kernel/src/main/java/com/dremio/exec/ops/ViewExpansionContext.java`.

**Consequences:**
A user creates a cyclic view chain. Any query of any view in the chain causes a coordinator thread to stack-overflow. This is a denial-of-service vector; with definer-rights context switching it becomes harder to detect because each hop switches identity, obscuring the cycle in logs.

**Prevention:**
1. Before implementing multi-hop definer rights, add a `Set<NamespaceKey>` to `ViewExpansionContext` tracking which view paths are currently being expanded. Before expanding a view, assert the path is not already in the set; add it on entry, remove it on exit (use `try/finally`).
2. Add a hard depth counter as a secondary guard: throw `UserException.validationError("View chain exceeds maximum depth")` if depth > configurable max (default 50).
3. Test: create V1 = SELECT * FROM V2, V2 = SELECT * FROM V1 (requires DDL bypass or direct KV write). Assert query returns a clear error, not a stack overflow.

**Warning signs:**
- `java.lang.StackOverflowError` in coordinator logs with `ViewExpander` or `ViewTable.toRel` on the stack.
- Coordinator thread count creeps up and never drops during a workload of complex VDS chains.

**Phase:** v2.0 Definer Rights Implementation — Phase 1. Must be addressed before or alongside definer rights.

---

## P18 — UDF Invoker vs Definer Rights: Unresolved Design Produces Security Holes

**What goes wrong:**
v1.0 checks `EXECUTE` privilege on a UDF before invoking it (in `CatalogImpl.getFunctions()` / `isRbacDeniedForFunction()`). For v2.0, the question is whether UDF bodies run under invoker rights (caller's identity) or definer rights (UDF creator's identity). This distinction is critical for security:
- **Invoker rights:** body runs as the calling user. User cannot see tables they don't have SELECT on, even inside the UDF body. The EXECUTE check in `isRbacDeniedForFunction()` is correct as-is.
- **Definer rights:** body runs as the UDF creator. A user with EXECUTE on the UDF gets the creator's table access inside the body. This is a designed privilege escalation and must be explicit.

Implementing definer rights for UDFs without documenting the decision creates a silent privilege escalation: users with EXECUTE can read tables they cannot directly SELECT.

**Why it happens:**
The SQL standard defines both models. Dremio's current `UserDefinedFunctionExpanderImpl` may default to one without documenting it. The existing `ViewExpander.stringToRelRootAsSystemUser()` uses the system user for UDF body expansion — indicating that some UDF paths already run as a non-caller identity.

**Consequences:**
If definer rights are accidentally implemented for UDFs: user B has EXECUTE on UDF `f`. UDF `f` is owned by A. `f` body reads table T (A has SELECT on T, B does not). B executes `f` and reads T indirectly.

**Prevention:**
1. Document clearly in the phase design: are Dremio OSS UDFs invoker-rights or definer-rights? Do not implement both simultaneously.
2. If invoker-rights: no context switch. Inner table reads inside UDF expansion go through the caller's `CatalogImpl` instance. The existing EXECUTE check is sufficient.
3. If definer-rights: the context switch must mirror the VDS pattern. EXECUTE check uses the invoker's identity. Inner expansion uses the definer's identity. The same stale-grant risk (P15) and wrong-identity risk (P16) apply.
4. Never allow a UDF to recursively call itself without a depth counter (same issue as P17 for VDS chains).

**Warning signs:**
- UDF bodies that query tables the invoking user cannot see directly succeed.
- No explicit documentation in the codebase of which rights model UDFs use.

**Phase:** v2.0 UDF Rights Implementation — requires design decision before any code is written.

---

## P19 — Container Visibility: O(n) Tree Walk Per Listing Request

**What goes wrong:**
Container visibility (a space or folder is visible if the user has access to at least one child) requires computing the transitive union of accessible descendants. The naive implementation walks the entire namespace subtree for each listing call, checking RBAC on every leaf node. `CatalogImpl.listSchemas()` delegates to `listSchemata(searchQuery)` which queries the namespace index. The index returns all schema entries regardless of user permissions. A filter step is applied per-result. If the filter requires a `hasPrivilege()` call per entry, and `hasPrivilege()` is a KV round-trip (`GrantStore.get()`), the list operation scales linearly with the number of datasets under the container.

**Why it happens:**
The grant key format is `{role_id}|{object_type}|{object_path}|{privilege}`. There is no index by object path prefix. To answer "does user have any grant under container X", the only option with the current `GrantStore` is a full scan filtered by prefix — which is the same O(grants) scan that `listByRole()` already does.

**Consequences:**
`SHOW SCHEMAS` or `SHOW TABLES` in a large space causes a cascade of thousands of KV reads per listing. For concurrent queries this multiplies. The coordinator appears to hang on `SHOW SCHEMAS` for large spaces.

**Prevention:**
1. Do not implement per-row privilege checks inside `listSchemas()` for v2.0. Use a two-phase approach: (a) retrieve the full set of containers, (b) apply the visibility filter by checking only whether the user has any grant whose `object_path` starts with the container's path prefix.
2. The visibility check should scan the grant store once (O(grants)), not once per container. Collect all matching path prefixes in a single pass, then intersect with the container list.
3. For v2.0 MVP: implement container visibility as a prefix-based grant check. A container is visible if any of the user's roles has a grant whose `object_path` starts with the container's path. This requires a prefix scan of the grant store keyed by role. For the expected grant count in OSS (hundreds to low thousands), this is acceptable.
4. Profile with 5,000 grants before shipping the feature.

**Warning signs:**
- `SHOW SCHEMAS` in a large space takes more than 500ms.
- Coordinator CPU spikes during `listSchemas()` calls with RBAC enabled.
- Flame graphs show `GrantStore.get()` inside the listing loop.

**Phase:** v2.0 Container Visibility Implementation — Phase 2.

---

## P20 — Container Visibility: Pagination Shortfall Made Worse

**What goes wrong:**
v1.0 already has a known limitation: paginated list results can return fewer items than the requested page size when RBAC filters remove denied items from a full page. v2.0 container visibility adds a second filter layer: first, VDS-level RBAC filters items within containers; second, container-level visibility filtering removes containers. If both filters apply to a paginated result, the shortfall becomes severe: a page of 100 items might return 3 after both filters, with no way for the client to request the next actual page of filtered results.

**Why it happens:**
Dremio's namespace pagination is cursor-based over the unfiltered namespace. The RBAC filter runs post-retrieval. If the namespace returns page [item 1 … item 100] and RBAC removes 97 of them, the client sees 3 items but the cursor advances past item 100.

**Consequences:**
A user browsing a space with 10,000 datasets (mostly RBAC-denied) sees only a few items per page and must page through many pages to find all accessible items. REST API clients that fetch the first page and stop assume there are only a few datasets in the space.

**Prevention:**
1. Accept this limitation for v2.0 MVP. Document it as a known behaviour.
2. Long-term fix: change the list implementation to filter-then-paginate (pull items until the page is full of allowed items, not until the namespace cursor moves a page).
3. For container visibility specifically: query the namespace for containers that have a corresponding grant record rather than querying all containers and then filtering.
4. Add a test that documents the shortfall: create a space with 100 datasets, grant SELECT on 3 of them, list with page size 10, assert that iterating all pages retrieves exactly 3.

**Warning signs:**
- REST API clients report missing datasets.
- `GET /api/v3/catalog` with a small page size returns far fewer items than the total.

**Phase:** v2.0 Container Visibility — Phase 2. Document before implementing.

---

## P21 — Table-Level SELECT on PDS: Grant Key Collision with VDS objectType

**What goes wrong:**
v1.0's `resolveRbacObjectType()` returns `"VDS"` for `SELECT` and `"FUNCTION"` for `EXECUTE`. Physical datasets (PDS) are explicitly excluded from v1.0 RBAC checks in `isRbacDeniedForVds()` via the `!(table instanceof ViewTable)` guard. Adding table-level SELECT for PDS requires a new object type string. The pitfall is using the same `"VDS"` string for both PDS and VDS grants, creating a namespace collision where a grant intended for a view inadvertently covers a physical table at the same path, or vice versa.

**Why it happens:**
The grant key format is `{role_id}|{objectType}|{objectPath}|{privilege}`. If VDS and PDS at the same path both use `objectType = "VDS"`, the grant key is identical. Granting SELECT on the VDS also grants it on the PDS.

**Consequences:**
Admin grants SELECT on VDS `reports.q1_summary`. A PDS also named `reports.q1_summary` exists in a different source. The PDS is now accessible to anyone with the VDS grant. When the VDS is dropped and a PDS promoted at the same path, the new PDS inherits the old VDS grant — the admin did not intend to grant PDS access.

**Prevention:**
1. Introduce `"PDS"` as a distinct `objectType` string for physical table grants. Update `resolveRbacObjectType()` to return `"PDS"` when checking physical tables and `"VDS"` for views.
2. The `isRbacDeniedForVds()` guard already distinguishes `ViewTable` vs non-`ViewTable`. Add a parallel `isRbacDeniedForPds()` method that fires only for non-`ViewTable` objects.
3. Existing grants stored in the KV store use `"VDS"` as the object type for views. Do not change this string — it is the persistent key component. Only add `"PDS"` for new grants on physical tables.
4. Test: grant SELECT on VDS `space.view1` (objectType=VDS). Assert that a user cannot SELECT physical table `space.view1` (objectType=PDS) without an explicit PDS grant, even if the paths match.

**Warning signs:**
- GRANT on a view inadvertently allows access to a co-located physical table.
- Unexpected access after a VDS is replaced by a PDS at the same path.

**Phase:** v2.0 Table-Level PDS SELECT — Phase 3.

---

## P22 — EXPLAIN PLAN Reveals Physical Table Names the User Cannot See

**What goes wrong:**
`EXPLAIN PLAN FOR SELECT * FROM my_view` triggers full query planning. `ExplainHandler.toResult()` runs the full pipeline — parse, validate, convert, optimise — and returns plan text built from `RelOptUtil.toString(logicalPlan)` or `innerNodeHandler.getTextPlan()`. The physical plan includes scan operators with physical table paths. If user B has SELECT on `my_view` but not on the underlying table `raw.customer_pii`, the EXPLAIN output reveals `raw.customer_pii` as a scan target. Neither `RelOptUtil.toString()` nor the physical plan text builder filters by the requesting user's privileges on referenced physical objects.

**Why it happens:**
The plan cache and plan text were designed before RBAC. With definer rights, B can query `my_view` without SELECT on `raw.customer_pii`, but the EXPLAIN reveals the underlying path anyway.

**Consequences:**
Information leakage: users can discover physical table names, source names, and join structure by running EXPLAIN PLAN on any view they have SELECT on, even when the underlying data is strictly access-controlled.

**Prevention:**
1. Decide before implementing definer rights: should EXPLAIN PLAN be allowed on a view when the invoker has SELECT on the view but not on underlying tables? The secure answer is: yes for the view's logical output (schema only), no for the full physical plan that exposes underlying table paths.
2. For v2.0 MVP: restrict `EXPLAIN PLAN PHYSICAL` to users who have direct SELECT on all referenced underlying tables, or restrict it to ADMINs. Allow `EXPLAIN PLAN LOGICAL` which does not expose physical scan paths.
3. At minimum: add an ADMIN-only option flag for `EXPLAIN PLAN PHYSICAL` that defaults off for non-ADMINs when RBAC is enabled.
4. This must be addressed in the same phase as Definer Rights. Do not ship definer rights without addressing this.

**Warning signs:**
- Non-admin users run `EXPLAIN PLAN FOR SELECT * FROM view` and see physical table paths in output.
- Source/table names that are supposedly hidden appear in job profiles or plan output.

**Phase:** v2.0 Definer Rights Implementation — Phase 1 (must ship together).

---

## P23 — Plan Cache Key Does Not Include Definer Identity: Cross-User Plan Reuse

**What goes wrong:**
`PlanCacheUtils.generateCacheKey()` hashes: SQL text, workload type, dataset versions, reflection hashes, and query options. It does not include the requesting user's identity or the definer identity chain of any expanded views. When user A and user B issue the same SQL (`SELECT * FROM my_view`), they may receive the same cached plan. If the cached plan was built for user A (whose definer grants produced a particular physical plan) and user B has a different definer chain, the cached plan may be physically incorrect for B or may bypass privilege checks that would fire during fresh planning.

**Why it happens:**
The cache was designed before RBAC existed. Query identity was not a cache dimension because all users could access all data. With definer rights, the physical plan depends on which user owns which view.

**Consequences:**
User A queries V (owner is userA, plan cached). UserA's grants change (or V is transferred to userB). User C queries V. Cache hit. Plan still uses userA's physical table path choices, built under userA's privilege context — without checking userB's (the new definer's) grants.

**Prevention:**
1. Add the view owner identity chain as an additional hash input in `PlanCacheUtils.generateCacheKey()`. For each `ViewTable` referenced in the plan's `RelNode` tree, hash the `getViewOwner()` username. This ensures plans built under different definer chains get different cache keys.
2. Or: disable the plan cache for queries that involve at least one `ViewTable` with a non-null, non-system `viewOwner`. This is conservative but safe for v2.0.
3. When a view's owner changes, call `invalidateCacheOnDataset(datasetId)` — the `LegacyPlanCache.invalidateCacheOnDataset()` method exists for exactly this purpose.
4. Test: user A queries V (plan cached). Change V's owner to userB (different grants). User A queries V again. Assert: cache miss, fresh plan built under userB's definer context.

**Warning signs:**
- Privilege changes to a view's definer do not cause re-planning for subsequent queries.
- `EXPLAIN PLAN` returns a cached plan that references a user who no longer owns the view.

**Phase:** v2.0 Definer Rights Implementation — Phase 1. Cache safety must be verified before the cache is left enabled with definer rights active.

---

## P24 — Deleted Definer: Silent Fallback to Query User Bypasses Security Boundary

**What goes wrong:**
`ViewExpander.expandViewInternal()` has a `UserNotFoundException` fallback at lines 127–133: if the view owner's account no longer exists in Dremio, the expansion falls back to the query user's identity. This fallback was added for continuity. With deny-by-default RBAC in place, the fallback effectively grants the query user full definer-level access to the underlying tables whenever the owner's account is deleted — bypassing the principle that a deleted owner should cause view access to fail.

**Exact code location:**
```java
// ViewExpander.java
} catch (RuntimeException e) {
  if (!(e.getCause() instanceof UserNotFoundException)) {
    throw e;
  }
  final CatalogIdentity delegatedUser = viewExpansionContext.getQueryUser();
  return expandRelNode(viewTable, delegatedUser, queryString);
}
```

**Consequences:**
Admin deletes user A (the view owner). User B queries V. The fallback triggers. V expands under B's identity. B can now read V's underlying tables if B has sufficient grants. This silently changes the security boundary without operator awareness.

**Prevention:**
1. For v2.0, replace the `UserNotFoundException` fallback with an explicit `UserException.permissionError()`: "View owner no longer exists; view access has been suspended. Contact an administrator."
2. This is a breaking change from existing fallback behaviour. Document it in the v2.0 release notes.
3. Alternatively: require ownership transfer before user deletion. Add a pre-delete check in the user deletion REST handler that blocks deletion if any views are owned by the user being deleted.
4. Test: create view V owned by userA. Delete userA. User B queries V. Assert: access denied with a clear error, not a silent fallback to B's identity.

**Warning signs:**
- Deleting a user account does not break views owned by that user.
- Queries to orphaned views succeed silently.

**Phase:** v2.0 Definer Rights Implementation — Phase 1. The fallback must be changed before definer rights are activated.

---

## P25 — Backwards Compatibility: Inner VDS Check Fires Under Definer Preventing VDS-over-VDS

**What goes wrong:**
v1.0 enforcement fires `isRbacDeniedForVds()` on every `ViewTable` resolved via `getTable()` — including inner views resolved during view expansion. When definer rights are active, the inner catalog instance (running as the definer userA) resolves an inner view W (owned by userC). The inner catalog checks whether userA has SELECT on W. UserA doesn't — userA just owns the outer view V. The query fails with an opaque "access denied" during expansion, even though the intent is for definer rights to bypass inter-view privilege checks.

**Why it happens:**
`CatalogImpl.isRbacDeniedForVds()` is a blanket check on all `ViewTable` objects, regardless of whether the current catalog instance is running in "definer expansion mode" or "direct user query mode". The `resolveCatalog(viewOwner)` call creates a new `CatalogImpl` with a different `userName` but identical privilege enforcement logic.

**Consequences:**
VDS-over-VDS queries fail with definer rights enabled. Error says "access denied on view W" when the failing user (the definer) actually owns the outer view and never needed SELECT on W.

**Prevention:**
1. Add a `boolean isInDefinerContext` field to `CatalogImpl`. Default: false. Set to true when `resolveCatalog(viewOwner)` is called for view expansion (not for general user impersonation).
2. In `isRbacDeniedForVds()`: if `isInDefinerContext` is true, return false — skip VDS privilege checks during definer expansion. Only `isRbacDeniedForPds()` (table-level PDS checks) should run in definer context.
3. The `isInDefinerContext` flag must propagate through any further `resolveCatalog()` calls made during expansion (inner views expanding inner views).
4. Test: V = SELECT * FROM W WHERE ...; W = SELECT * FROM table T. V owned by userA. W owned by userB. User C has SELECT on V only. Assert: C can query V, inner expansion under userA does not require userA to have SELECT on W, inner expansion under userB does not require userB to have SELECT on V.

**Warning signs:**
- VDS-over-VDS queries fail with definer rights enabled but succeed with definer rights disabled.
- Error says "access denied on view W" when the failing user owns W or is the definer of the outer view.

**Phase:** v2.0 Definer Rights Implementation — Phase 1. This interaction must be designed before coding starts.

---

## P26 — VDS Lifecycle Privileges: Pre-Existing ALTER/DROP Grant Stubs Activate Silently

**What goes wrong:**
In v1.0, `resolveRbacObjectType()` defaults to `"VDS"` for `ALTER` and `DROP` in the switch default case. This means the enforcement path for ALTER and DROP already exists in `validatePrivilege()` — but no grants for these privileges exist in the KV store because v1.0 never exposed ALTER/DROP grant management. If v2.0 adds ALTER/DROP grant support, any grant written directly to the KV store during v1.0 testing (e.g., via REST API or integration test fixtures) will activate immediately without any code change.

**Consequences:**
Low probability in production; higher risk in test environments where grants were written manually. A test environment may have unexpected ALTER/DROP grants that silently become effective when v2.0 enforcement checks for them.

**Prevention:**
1. Before enabling v2.0 ALTER/DROP enforcement, scan existing grant stores for entries with `privilege = "ALTER"` or `privilege = "DROP"`. Log a warning for each found record.
2. Confirm that `CREATE_VIEW` privilege is scoped to the **parent container** (the space), and ALTER/DROP are scoped to the **view path itself**. These are different objectPath values — verify the enforcement code checks the correct path for each operation.
3. The `dropPrimaryKey` method in `CatalogImpl` already calls `validatePrivilege(table, SqlGrant.Privilege.ALTER)`. Confirm this pattern is followed consistently for all ALTER-requiring operations on VDS.

**Warning signs:**
- Test environments have unexpected ALTER/DROP grants in the KV store from manual testing.
- DROP VIEW succeeds for a user who was never explicitly granted DROP privilege.

**Phase:** v2.0 VDS Lifecycle Privileges — Phase 4.

---

## P27 — Definer Context Stack: ViewExpansionContext Is Not Thread-Safe Under Parallel Planning

**What goes wrong:**
`ViewExpansionContext` maintains mutable state (`userTokens: ObjectIntHashMap`) that is created per-query (one instance per `QueryContext`). This state is designed for single-threaded view expansion. If Calcite's planning rules trigger view expansion on parallel threads within a single query (parallel rule application), both threads call `reserveViewExpansionToken()` and `releaseViewExpansionToken()` on the same `ViewExpansionContext` instance. `ObjectIntHashMap` is not thread-safe.

**Why it happens:**
`ViewExpansionContext` was designed under the assumption that view expansion is single-threaded (depth-first, one view at a time). Calcite's VolcanoPlanner uses a priority queue for rule application; whether this triggers parallel `ViewTable.toRel()` calls depends on the planning configuration.

**Consequences:**
`ConcurrentModificationException` or incorrect token counts in `userTokens` under concurrent planning. A definer context that was entered is never exited (token not released), causing subsequent expansions under that definer to fail with Preconditions assertion errors (`"Given user doesn't exist in User Token store"`).

**Prevention:**
1. Audit whether Calcite's planning phases that trigger `ViewTable.toRel()` can run in parallel within a single query. If yes, add synchronization to `ViewExpansionContext` or replace `ObjectIntHashMap` with `ConcurrentHashMap<CatalogIdentity, AtomicInteger>`.
2. Add a `@NotThreadSafe` annotation to `ViewExpansionContext` if the planning audit confirms single-threaded access — document the assumption explicitly.
3. For v2.0, if parallel planning is confirmed to be single-threaded at the point of view expansion, document this with a code comment and add a defensive assertion on the calling thread ID.

**Warning signs:**
- Intermittent `ConcurrentModificationException` in `ViewExpansionContext` under concurrent query load.
- Preconditions assertions fire in `releaseViewExpansionToken()` under parallel planning workloads.

**Phase:** v2.0 Definer Rights Implementation — Phase 1. Verify threading model before adding per-definer state.

---

## Summary Table

| # | Pitfall | Milestone | Phase |
|---|---------|-----------|-------|
| P1 | Bootstrap deadlock: who grants the first ADMIN | v1.0 | Phase 1 |
| P2 | SYSTEM_USERNAME as a silent backdoor | v1.0 | Phase 1 |
| P3 | Definer-rights confusion: checking the wrong layer | v1.0 | Phase 1 |
| P4 | Access path gaps: SQL is not the only door | v1.0 | Phase 2 |
| P5 | Cache invalidation when grants change | v1.0 | Phase 1 |
| P6 | Performance: KV lookup on every catalog resolution | v1.0 | Optimization |
| P7 | Migration lock-out: existing views/UDFs have no owner | v1.0 | Migration |
| P8 | EE conflict: clobbering the Enterprise RBAC | v1.0 | Design |
| P9 | Implicit ADMIN: internal operations blocked by wrong identity | v1.0 | Phase 1 |
| P10 | INFORMATION_SCHEMA leaks object existence | v1.0 | Phase 2 |
| P11 | Error messages reveal object existence | v1.0 | Phase 1 |
| P12 | KV store schema evolution: protobuf changes break records | v1.0 | Design |
| P13 | DACSecurityContext.isUserInRole() time bomb | v1.0 | Phase 2 |
| P14 | Using the deprecated LegacyKVStore API | v1.0 | Phase 1 |
| P15 | Stale definer identity: frozen grant snapshot | v2.0 | Phase 1 |
| P16 | Wrong CatalogImpl instance in inner definer check | v2.0 | Phase 1 |
| P17 | Missing cycle guard in VDS-over-VDS definer chain | v2.0 | Phase 1 |
| P18 | UDF invoker vs definer rights: unresolved design | v2.0 | Phase 1 |
| P19 | Container visibility: O(n) tree walk per listing | v2.0 | Phase 2 |
| P20 | Container visibility amplifies pagination shortfall | v2.0 | Phase 2 |
| P21 | PDS SELECT grant key collides with VDS objectType | v2.0 | Phase 3 |
| P22 | EXPLAIN PLAN reveals physical table names via definer | v2.0 | Phase 1 |
| P23 | Plan cache key excludes definer identity | v2.0 | Phase 1 |
| P24 | Deleted definer falls back silently to query user | v2.0 | Phase 1 |
| P25 | Inner VDS check fires under definer, breaks VDS-over-VDS | v2.0 | Phase 1 |
| P26 | ALTER/DROP grant stubs activate silently in v2.0 | v2.0 | Phase 4 |
| P27 | ViewExpansionContext not thread-safe under parallel planning | v2.0 | Phase 1 |

---

## v2.0 Integration Pitfalls with v1.0

| v1.0 Design Decision | v2.0 Impact | What to Verify |
|----------------------|-------------|----------------|
| `isRbacDeniedForVds()` fires on every `getTable()` | During definer expansion, inner views trigger this check under the definer's identity | Add `isInDefinerContext` flag (P25) |
| `isRbacDeniedForVds()` skips PDS (`!(table instanceof ViewTable)`) | When adding PDS SELECT, must add a parallel check — the skip is no longer universal | Add `isRbacDeniedForPds()` method (P21) |
| `resolveRbacObjectType()` defaults to `"VDS"` for ALTER/DROP | ALTER/DROP stubs already exist; activating them requires only a grant being present | Scan for pre-existing ALTER/DROP grants (P26) |
| No plan cache user-scoping | Plan cache does not include user identity or definer chain | Hash definer chain into cache key (P23) |
| `UserNotFoundException` fallback in `ViewExpander` | Silently changes security boundary when definer is deleted | Replace with explicit error (P24) |
| Grant store scan is O(grants) for all listing operations | Container visibility adds another listing query layer | Use prefix scan, not per-row check (P19) |

---

*v1.0 pitfall research: 2026-02-17. v2.0 pitfall research: 2026-02-20. Codebase: rbac branch, commit 2cc3b3c3d.*
