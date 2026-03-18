# RBAC Pitfalls

**Research Date (v1.0):** 2026-02-17
**Research Date (v2.0 additions):** 2026-02-20
**Research Date (v1.5 Keycloak additions):** 2026-03-12
**Scope:**
- P1–P14: Adding deny-by-default RBAC to Dremio OSS — catalog-level enforcement via `CatalogImpl.validatePrivilege()` (v1.0)
- P15–P27: Adding privilege context switching (VDS definer rights, UDF invoker rights), table-level SELECT on PDS, container visibility filtering, and VDS lifecycle privileges (ALTER, DROP) to the existing v1.0 system (v2.0)
- P28–P41: Adding Keycloak OIDC as a pluggable IdP to the existing internal-auth + RBAC system (v1.5)

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

---

---
## v1.5 Keycloak OIDC Integration Pitfalls

**Domain:** Pluggable IdP (Keycloak OIDC) added to existing Java app with internal auth + RBAC
**Researched:** 2026-03-12
**Confidence:** HIGH (most pitfalls directly verified against Dremio source code)

---

## P28 — JWT Issuer and Audience Not Validated: Any Keycloak Token Works

**What goes wrong:**
The existing `JWTValidatorImpl` calls `jwtProcessor.process(jwt, null)` and then resolves the subject via `userResolverProvider.get().getUser(new UID(jwtClaimsSet.getSubject()))`. The `JWTProcessor` built by `JWTProcessorFactory` validates the signature and expiry but may not validate `iss` (issuer) or `aud` (audience) claims unless explicitly configured. Without issuer validation, a JWT from any Keycloak realm on any server — or from another OIDC provider entirely — passes signature verification if it uses the same key material. Without audience validation, a token issued for a different client application is accepted.

**Why it happens:**
Nimbus JOSE+JWT's `DefaultJWTProcessor` validates signature and expiry by default. Claims-set validation (issuer, audience, not-before) requires explicit addition of a `JWTClaimsSetVerifier` or equivalent. Developers wiring up JWKS validation focus on the key fetching and signature steps and miss the claims validation step.

**Consequences:**
Keycloak realm `A` issues tokens for client `frontend-app`. Keycloak realm `B` is a test realm with weaker policies. If both use the same JWKS endpoint or the validator is not checking `iss`, tokens from realm B are accepted by Dremio. Tokens issued for `frontend-app` are also accepted for `dremio-api` access even if the Dremio client scope was never granted to `frontend-app`.

**Prevention:**
1. When building the `JWTProcessor`, add a `DefaultJWTClaimsVerifier` configured with the expected issuer (`https://keycloak-host/realms/your-realm`) and the expected audience (the Dremio client ID in Keycloak, e.g., `dremio`). Reject tokens that do not match both values exactly.
2. Use Nimbus JOSE+JWT's `DefaultJWTClaimsVerifier` with `exactMatchClaims` set to `{iss: "<realm-url>", aud: "<client-id>"}`. Mark both `iss` and `aud` as required claims.
3. Store the expected issuer and client ID in `dremio.conf` (e.g., `services.keycloak.issuer-uri` and `services.keycloak.client-id`). Do not hardcode them.
4. Test: generate a token from a different realm; assert it is rejected with a clear "invalid issuer" error.

**Warning signs:**
- JWT validation accepts tokens from test realms or different environments.
- No `iss` or `aud` claim checks appear in the JWT validation path.

**Phase:** v1.5 Phase 1 (JWT validation infrastructure) — must be enforced before any Keycloak token is accepted.

---

## P29 — Clock Skew Causes Spurious Authentication Failures

**What goes wrong:**
JWT `exp` (expiration) and `nbf` (not-before) claims are validated against the server's system clock. Keycloak and Dremio may run on different hosts with small but non-zero clock differences. A 5-second clock difference means a token issued by Keycloak at T=0 with `nbf=T` may be rejected by Dremio at T=-3 (Dremio's clock is 3 seconds behind Keycloak). Token expiry within a few seconds of the true expiry also produces spurious rejections: a user who just received a fresh token gets 401 from Dremio.

**Why it happens:**
Default Nimbus JOSE+JWT clock tolerance is 0 seconds. Real-world Docker/Kubernetes deployments routinely have 1–10 second clock drift between containers. NTP synchronisation reduces but does not eliminate this.

**Consequences:**
Users experience intermittent "token expired" or "token not yet valid" errors immediately after login. The failures are not reproducible in dev environments where all processes run on the same host but appear in production where Keycloak and Dremio are on separate nodes.

**Prevention:**
1. Set a clock skew tolerance of 30 seconds when building the Nimbus `DefaultJWTClaimsVerifier`. Pass `new ClockSkewAware(30, TimeUnit.SECONDS)` or equivalent to the processor. This matches Keycloak's own default clock skew allowance.
2. Document the configured skew in `dremio.conf` as `services.keycloak.allowed-clock-skew-seconds` (default: 30). Expose it as a configuration option, not a hardcoded constant.
3. In production Docker deployments, configure chrony/NTP in all containers or use host clock sync. Clock skew tolerance is a defence-in-depth measure, not a substitute for time synchronisation.

**Warning signs:**
- `nbf` or `exp` validation failures appear in logs only when Dremio and Keycloak are on separate hosts.
- Failures resolve themselves within 30–60 seconds without code changes.

**Phase:** v1.5 Phase 1 (JWT validation infrastructure).

---

## P30 — JWKS Cache Not Refreshed After Keycloak Key Rotation

**What goes wrong:**
Dremio's existing `RemoteJWKSetManager` uses a 24-hour JWKS cache TTL for the secondary coordinator's copy of the master coordinator's internal JWK set. The same pattern applied to external Keycloak JWKs means a key rotation in Keycloak (Keycloak recommends rotating keys periodically; it can also happen on restart) takes up to 24 hours to propagate. During that window, signature verification fails for all tokens signed with the new key.

**Why it happens:**
The 24-hour TTL was acceptable for the internal Dremio JWKS (which almost never rotates in practice). Keycloak keys can rotate more frequently, and a failed cluster upgrade or security incident may force immediate rotation. The existing cache does not implement the standard mitigation: re-fetch the JWKS when an unknown `kid` is encountered in an incoming token.

**Consequences:**
Keycloak rotates its signing key. All new tokens are signed with the new key. Dremio's JWKS cache still contains only the old key. All token validations fail until the cache expires. Users are locked out for up to 24 hours.

**Prevention:**
1. Implement the `kid`-based cache refresh pattern: before rejecting a token with "unknown key ID", attempt to refresh the JWKS from the Keycloak `jwks_uri` endpoint once. If the new key is found, update the cache and re-validate. If not, reject.
2. Use a shorter TTL for the external Keycloak JWKS cache: 1–4 hours is appropriate. Combine with negative-cache prevention (always attempt a refresh on unknown `kid` regardless of TTL).
3. Keycloak's JWKS endpoint returns `Cache-Control` headers. Honour them — the `max-age` directive from Keycloak should drive the cache TTL rather than a hardcoded constant.
4. Test: configure Dremio with Keycloak. Get a valid token. Rotate the Keycloak realm key (via Keycloak admin API). Issue a new token. Verify Dremio accepts it without a restart.

**Warning signs:**
- Token validation fails after any Keycloak restart or key rotation.
- Dremio log shows "unknown kid" errors for tokens that Keycloak confirms are valid.

**Phase:** v1.5 Phase 1 (JWKS fetching and caching) — design kid-refresh before implementing the cache.

---

## P31 — JIT Provisioning Race Condition: Duplicate User Creation on Concurrent First Logins

**What goes wrong:**
JIT provisioning creates a Dremio user account on first Keycloak login: the filter checks whether a user with the given username exists in `SimpleUserService`; if not, it calls `userService.createUser(...)`. Under concurrent load — two browser tabs logging in simultaneously, or a client retry — two concurrent requests may both observe "user not found" and both attempt `createUser`. `SimpleUserService.createUser()` uses KVStore `PUT_CREATE` semantics (via `LegacyIndexedStore`), so the second write may succeed, fail with a conflict exception, or silently overwrite the first write depending on the KV implementation path.

**Why it happens:**
The check-then-act pattern (`if !exists → create`) is not atomic in `SimpleUserService`. The method is not synchronized, and RocksDB's `PUT_CREATE` semantics are not exposed via `LegacyIndexedStore`. In the standard `SimpleUserService.createUser()`, a `getUser()` existence check is followed by a `put()` without a distributed lock.

**Consequences:**
Best case: second create fails with a duplicate exception and the second login request returns 500, but eventual retry succeeds. Worst case: two user records are created with the same username but different UIDs, corrupting the user store. RBAC membership records keyed by username are then ambiguous.

**Prevention:**
1. Implement JIT provisioning using a synchronized block or optimistic locking: attempt `createUser` unconditionally; catch `UserAlreadyExistsException`; on conflict, re-fetch and continue with the existing user. Do not check-then-create.
2. Alternatively: use a per-username lock (striped lock via `Striped<Lock>` from Guava, keyed on the Keycloak subject claim) to serialize first-login provisioning for the same user.
3. Make provisioning idempotent: if a user with the Keycloak `sub` already exists (identified by an external ID field or by username convention), return the existing user without error.
4. Test: simulate two simultaneous first-login requests for the same user under load. Assert: exactly one user record is created, both requests ultimately succeed.

**Warning signs:**
- Duplicate `UserAlreadyExistsException` logs on first login under load.
- Users appear in `sys.membership` twice with different UIDs.

**Phase:** v1.5 Phase 2 (JIT provisioning) — must be in the initial implementation, not a follow-up.

---

## P32 — Keycloak Role Mapping Overwrites Manually Assigned Dremio RBAC Roles

**What goes wrong:**
Keycloak role-to-RBAC-role mapping at login time faces a fundamental question: should Keycloak roles be additive (added to the user's existing Dremio roles) or authoritative (replacing the user's current Dremio roles entirely on each login)? The pitfall is implementing an authoritative sync: every login removes the user's current Dremio role memberships and re-adds only those derived from Keycloak roles. This silently discards any RBAC roles that a Dremio admin assigned manually after the user's last login.

**Why it happens:**
The simplest implementation is a "delete all memberships + re-add from token" loop, which is authoritative. Developers choose it because it avoids stale role accumulation. But it conflicts with Dremio's existing administrative model where admins independently manage role memberships via `GRANT ROLE`.

**Consequences:**
Admin manually grants `analyst-role` to user Alice via `GRANT ROLE analyst-role TO USER alice`. Alice logs out. Admin rotates her Keycloak roles, removing the `analyst` realm role. Alice logs back in. The authoritative sync removes `analyst-role` (correctly). But it also removes a manually-added `data-admin-role` that was not Keycloak-derived — the admin's manual grant is lost silently.

**Prevention:**
1. Use additive mapping only: on login, add Dremio role memberships for any Keycloak roles that are not already present. Never remove existing Dremio memberships during login.
2. Use a naming convention to distinguish Keycloak-derived memberships from manually-assigned ones (e.g., store a `source: "keycloak"` field in the Membership proto). On login, remove only memberships marked `source: "keycloak"` and re-add from the token. This makes the Keycloak-derived portion authoritative while preserving manual assignments.
3. Document the chosen model explicitly. Do not change it silently between versions.
4. Test: assign a manual RBAC role to user Alice. Simulate a login that maps a different Keycloak role. Assert: the manual role is still present after login.

**Warning signs:**
- Manually assigned Dremio RBAC roles disappear after the user's next Keycloak login.
- Admins report that their `GRANT ROLE` commands are "reversed" by the SSO login.

**Phase:** v1.5 Phase 3 (role mapping) — the mapping semantics must be decided and documented before coding.

---

## P33 — OIDC Redirect Flow Missing CSRF State Validation

**What goes wrong:**
The OIDC authorization code flow requires the server to generate a cryptographically random `state` parameter, store it in the user's session, redirect to Keycloak with it, and validate it when the callback arrives. Skipping state validation or using a static/predictable state value enables CSRF attacks: an attacker can force a victim's browser to complete an OAuth flow using the attacker's authorization code, logging the victim into the attacker's Keycloak account within Dremio.

**Why it happens:**
Developers implementing the callback endpoint focus on extracting the authorization code and exchanging it for tokens. The state parameter is perceived as optional because Keycloak does not enforce it server-side. JAX-RS resource implementations without session-scoped state store may skip the round-trip validation step.

**Consequences:**
CSRF login attack (account takeover via OAuth code injection). The attacker can attach their Dremio session to a victim's browser, then observe the victim's queries or impersonate them.

**Prevention:**
1. Generate a 128-bit random `state` value (using `SecureRandom`) at the start of the authorization code flow. Store it in a signed HTTP cookie or server-side session keyed on a session ID.
2. In the Keycloak callback endpoint, extract the `state` query parameter and compare it to the stored value. Reject any callback where the state does not match exactly.
3. Use PKCE (`code_challenge`/`code_verifier`) as a complementary measure for public clients. For Dremio's server-side callback, state is the primary CSRF defence.
4. Dremio's existing `TokenUtils.getAuthHeaderToken()` pattern handles token extraction but has no concept of OAuth state. The state management must be a new, independent mechanism.
5. Test: initiate an OIDC flow. Intercept the callback and change the `state` parameter. Assert: Dremio returns 400 Bad Request and does not issue a session token.

**Warning signs:**
- The callback endpoint does not read a `state` query parameter.
- State is generated as a static constant or based on predictable values like username or timestamp.

**Phase:** v1.5 Phase 4 (OIDC redirect UI flow) — state management is mandatory, not optional.

---

## P34 — DACAuthFilter Requires User in SimpleUserService: JIT Must Complete Before First REST Call

**What goes wrong:**
`DACAuthFilter.filter()` calls `userService.get().getUser(userName.getName())` after validating the token. If the token is valid but the user does not exist in `SimpleUserService` (e.g., a Keycloak user who has never logged in via the UI), `getUser` throws `UserNotFoundException` and the filter returns 401. JIT provisioning that creates users during the OIDC login flow works for the UI, but REST API clients that present a Keycloak Bearer token directly (without going through the OIDC redirect flow) will fail on the first request because the user does not yet exist.

**Why it happens:**
The `DACAuthFilter` was written for internal auth where every user exists in the KV store. The Keycloak auth path exchanges a token for a username (via JWT claims) before the filter runs, but does not trigger JIT provisioning. The filter assumes the user exists; if not, it fails.

**Consequences:**
A new Keycloak user with a valid Bearer token calls `GET /api/v3/catalog`. The token is valid. But `getUser` fails. 401 is returned. The user must first log in via the UI before REST API access works, which breaks automation and service accounts.

**Prevention:**
1. Add a JIT provisioning hook inside `DACAuthFilter` (or in the Keycloak token validation provider) that runs before `getUser`: if `UserNotFoundException` is caught and the token is a Keycloak JWT, attempt to provision the user from the JWT claims, then retry `getUser`.
2. Alternatively: move JIT provisioning to the token validation layer. The `KeycloakAuthProvider.validate()` method (to be created) should provision the user as a side effect of successful validation, before returning `AuthResult`.
3. Ensure the provisioning step is idempotent (see P31) — concurrent REST requests from a new user must not produce duplicate user records.
4. Test: create a Keycloak user who has never logged in. Issue a Bearer token directly from Keycloak. Call `GET /api/v3/catalog` with that token. Assert: 200 OK, user is created in Dremio on first call.

**Warning signs:**
- REST API clients with Keycloak tokens return 401 until the user logs in via the web UI.
- `UserNotFoundException` logs appear for users who exist in Keycloak but have never used the UI.

**Phase:** v1.5 Phase 2 (JIT provisioning) — the `DACAuthFilter` integration must be explicit in the design.

---

## P35 — Arrow Flight Authentication Bypasses Keycloak Token Validation

**What goes wrong:**
`DremioBearerTokenAuthenticator.validateBearer()` calls `tokenManagerProvider.get().validateToken(token)`. This validates only Dremio-internal opaque tokens stored in the KVStore. A Keycloak JWT presented to the Flight endpoint is not an opaque Dremio token — it will fail `tokenManager.validateToken()` with "invalid token" even if it is a perfectly valid Keycloak JWT. JDBC/ODBC clients that acquire a Keycloak access token and attempt to use it as a Flight Bearer token will be rejected.

**Why it happens:**
`TokenManagerImpl.validateToken()` looks up the token string as a KV store key. Keycloak JWTs are not stored in the KV store (they are stateless JWTs). The Flight authenticator was designed for Dremio's opaque token model and has no JWT awareness.

**Consequences:**
ODBC/JDBC connections using Keycloak tokens fail at the Flight level even when REST API access works. This blocks Keycloak-authenticated BI tool connections (Tableau, Power BI, DBeaver via Flight). The error message "invalid token" gives no indication that the token type is wrong.

**Prevention:**
1. Extend `DremioBearerTokenAuthenticator.validateBearer()` to try Dremio opaque token validation first. If it fails with "invalid token", attempt Keycloak JWT validation as a fallback using the `KeycloakAuthProvider` (or `JWTValidatorImpl` configured for Keycloak keys).
2. Alternatively: implement a new `CallHeaderAuthenticator` variant that handles both token types and select between them based on the token format (a Keycloak JWT starts with `eyJ` and is parseable as a JWT; a Dremio opaque token is a base-32 string).
3. For JDBC clients, document that they must exchange the Keycloak access token for a Dremio session token via `POST /apiv2/login` before establishing a JDBC connection, if direct JWT auth is not supported.
4. Test: obtain a Keycloak access token. Present it as a Bearer token to the Flight endpoint (`arrow-flight-client --bearer <token>`). Assert: connection is established, not rejected.

**Warning signs:**
- Flight/JDBC clients work with internal Dremio tokens but fail immediately with Keycloak tokens.
- `DremioBearerTokenAuthenticator` logs "Bearer token validation failed" for Keycloak JWTs.

**Phase:** v1.5 Phase 5 (Flight/JDBC integration) — must be explicitly addressed before declaring JDBC support complete.

---

## P36 — Keycloak Token Expiry vs Dremio Session Token Mismatch

**What goes wrong:**
When a user logs in via Keycloak OIDC, Dremio creates an internal session token via `tokenManager.createToken(username, clientAddress)`. This Dremio session token has its own expiry (default 30 hours per `TOKEN_EXPIRATION_TIME_MINUTES`). The Keycloak access token that initiated the login has a much shorter expiry (default 5 minutes in Keycloak). After the Keycloak token expires, the user's Dremio session token may still be valid for 29 more hours. If Dremio does not verify the Keycloak token's validity on each request (only on login), the Dremio session remains active even after the Keycloak session is revoked, user is deprovisioned in Keycloak, or password is changed.

**Why it happens:**
Dremio's internal auth model issues a long-lived session token at login time and never re-validates the upstream credential after that. This is appropriate for internal auth (password changes are relatively rare) but breaks the expected SSO behaviour where revoking a Keycloak session should propagate to all connected applications immediately.

**Consequences:**
Admin disables a Keycloak user (e.g., employee termination). The user's Dremio session token remains valid for up to 30 more hours. The user continues querying data despite being deprovisioned in the identity provider.

**Prevention:**
1. For the UI OIDC flow: store the Keycloak refresh token alongside the Dremio session. On each request (or periodically, e.g., every 15 minutes), attempt a silent token refresh via Keycloak's token endpoint. If the refresh fails (user deprovisioned, session revoked), invalidate the Dremio session token immediately.
2. For REST API Bearer token auth: do not issue a long-lived Dremio session token. Instead, validate the Keycloak JWT on every request (stateless validation is fast via JWKS). The JWT's own `exp` claim enforces the session lifetime.
3. Make Dremio's session token TTL configurable per auth provider: `services.keycloak.session-ttl-minutes` (recommend 30–60 minutes for Keycloak sessions, matching Keycloak's SSO session idle timeout).
4. Test: log in via Keycloak, obtain a Dremio session token. Disable the user in Keycloak admin. Verify that Dremio rejects the next request within the configured re-validation window.

**Warning signs:**
- Deprovisioned Keycloak users can still query Dremio for many hours after deprovisioning.
- Dremio session tokens outlive the Keycloak session that created them without re-validation.

**Phase:** v1.5 Phase 4 (session management) — the re-validation strategy must be decided before shipping the UI login flow.

---

## P37 — Username Claim Mismatch: `preferred_username` vs `sub` vs Email

**What goes wrong:**
Dremio identifies users by username string (e.g., in `MembershipStore` key `alice|analyst`). When provisioning or looking up a Keycloak user, the implementation must choose which JWT claim maps to the Dremio username. Three common choices each have failure modes:
- `preferred_username`: human-readable, but can change in Keycloak (admin can rename a user). Changing `preferred_username` creates a new Dremio user and abandons all RBAC memberships of the old one.
- `sub`: stable UUID, never changes, but not human-readable. Dremio admins cannot identify users by UUID in `sys.membership` or role management SQL.
- `email`: changes on email address updates; not unique if email reuse is permitted.

**Why it happens:**
The `preferred_username` claim is the intuitive choice (it looks like a username) and is the most commonly used in tutorials and examples. The stability problem of `preferred_username` is not obvious until an actual username change occurs in production.

**Consequences:**
If `preferred_username` is used and an admin renames a Keycloak user from `alice` to `alice.smith`: Dremio creates a new user `alice.smith` with no RBAC roles. All grants and memberships for `alice` are orphaned. Data access is lost until an admin re-assigns all roles.

**Prevention:**
1. Use `sub` (stable) as the internal user identifier (Dremio's UID field). Use `preferred_username` as the display name only.
2. Map Keycloak `sub` to the Dremio `UID.id` field. Store `preferred_username` as `User.userName` for display purposes. On login, look up the user by `sub` (not by username) to detect renames.
3. If the existing `UserService` and `MembershipStore` keying on username strings cannot be changed without a large refactor, document the limitation explicitly: Keycloak usernames must not change for existing Dremio users. Provide a migration procedure for username changes.
4. Test: provision user with `sub=abc123`, `preferred_username=alice`. Rename user in Keycloak to `alice.smith`. Log in again. Assert: the same Dremio user record is returned (matched by `sub`), username field updated to `alice.smith`, RBAC memberships preserved.

**Warning signs:**
- Keycloak user renames create duplicate Dremio user records.
- RBAC role memberships are lost after a Keycloak username change.

**Phase:** v1.5 Phase 2 (JIT provisioning) — the identity claim mapping decision is foundational and difficult to change later.

---

## P38 — Backward Compatibility Break: Existing Internal Users Cannot Log In After Keycloak Is Enabled

**What goes wrong:**
When Keycloak OIDC is enabled via config flag, the authentication path changes. If the implementation replaces the internal auth provider entirely rather than adding Keycloak as an additional option, existing users with internal Dremio passwords are locked out. The ADMIN user (whose account was created during `BootstrapResource` flow) cannot log in, which also blocks emergency access.

**Why it happens:**
The pluggable auth model requires careful design: both the UI login form (internal auth) and the SSO button (Keycloak) must work simultaneously. A naive implementation that routes all auth through Keycloak ignores the pre-existing `LocalUsernamePasswordAuthProvider` path.

**Consequences:**
After enabling `services.keycloak.enabled=true`, the admin user created during bootstrap can no longer log in (their password is in Dremio's KV store, not in Keycloak). RBAC bootstrap admin is locked out. Emergency fallback access is eliminated.

**Prevention:**
1. Implement Keycloak as an additional `AuthProvider` (via the existing `AuthProvider` interface), not a replacement. The `Authenticator` should try providers in order: (1) Keycloak JWT validation, (2) internal Dremio password auth. The first provider to return a valid `AuthResult` wins.
2. Keep `LocalUsernamePasswordAuthProvider` active regardless of Keycloak config. Document this explicitly: the internal admin account always works as an emergency backdoor.
3. Add a UI configuration element that shows both "Login with SSO" (Keycloak redirect) and the internal username/password form simultaneously. The form is for internal users; the SSO button is for Keycloak users. Do not hide the form when Keycloak is enabled.
4. Test: enable Keycloak. Log in as the bootstrap admin with internal password. Assert: login succeeds. Log in as a Keycloak user via SSO. Assert: login succeeds.

**Warning signs:**
- Enabling Keycloak config breaks the bootstrap admin login.
- The internal login form disappears from the UI when Keycloak is configured.

**Phase:** v1.5 Phase 1 (config flag and provider plugging) — backward compatibility must be in the initial design.

---

## P39 — ODBC/JDBC Token Refresh Is Not Automatic: Long-Running Sessions Break

**What goes wrong:**
ODBC/JDBC clients (BI tools, ETL pipelines, notebooks) establish a connection and hold it for the duration of a session or workload. The connection authenticates once (either with a Keycloak access token or by exchanging credentials for a Dremio session token). Keycloak access tokens expire in 5 minutes by default. If the ODBC/JDBC client presents the access token directly to the Flight endpoint and the token expires mid-session, subsequent queries on the same connection fail with 401. The ODBC driver does not automatically refresh the token.

**Why it happens:**
Arrow Flight's auth model is: authenticate once at connection time, use the returned bearer token for all subsequent RPC calls. The Flight client does not have a built-in hook for token refresh. Keycloak's access token lifetime is much shorter than a typical BI tool session.

**Consequences:**
A Tableau or Power BI live connection queries Dremio every few minutes. After 5 minutes, the initial Keycloak token expires. The next query fails. The user sees a connection error and must re-authenticate manually. This is unacceptable for live dashboards.

**Prevention:**
1. For JDBC/ODBC clients that support it: document that users must exchange their Keycloak access token for a Dremio session token via `POST /apiv2/login` (which issues a long-lived Dremio token). The Dremio session token (default 30 hours) outlasts the Keycloak access token's validity window.
2. For the Flight endpoint: implement token refresh on the server side — if the client presents a Keycloak refresh token alongside the expired access token, exchange it for a new access token transparently. This requires the refresh token to be stored server-side during login.
3. Configure Keycloak access token lifetime to match expected session durations for machine clients (e.g., extend to 1–8 hours for service accounts in the Keycloak realm, separate from the user-facing realm settings).
4. Document the limitation: Keycloak access tokens with 5-minute TTL are incompatible with long-running JDBC connections unless a token refresh mechanism is implemented.

**Warning signs:**
- JDBC connections fail after exactly 5 minutes (Keycloak's default access token TTL).
- BI tool live dashboards drop connection periodically without user action.

**Phase:** v1.5 Phase 5 (JDBC/ODBC integration) — document the limitation in Phase 1, resolve in Phase 5.

---

## P40 — JIT-Provisioned Users Are Not Assigned to ADMIN: Keycloak Admin Cannot Administer Dremio

**What goes wrong:**
A Keycloak user with the `admin` realm role logs in for the first time. JIT provisioning creates their Dremio account. Keycloak role mapping maps `admin` → Dremio `ADMIN` role. But the `MembershipStore.add()` call fails because `ADMIN` membership requires the calling user to be an ADMIN themselves — there is a chicken-and-egg problem if the provisioning code is subject to RBAC checks.

More concretely: `addMembership(userName, "ADMIN", grantedBy)` stores the membership in KV with `grantedBy = "SYSTEM"` (acceptable) but the bootstrap ADMIN validation (`validateAdminMembersExist()`) may have already passed at startup and the new ADMIN member is invisible to startup checks.

**Why it happens:**
JIT provisioning runs in the request filter, after startup validation has completed. The `addMembership` call itself has no RBAC guard (it is called from the provisioning code, not from a user-initiated SQL command). But if any part of the provisioning flow calls through the RBAC-gated REST API or requires the calling user to be in ADMIN, it fails.

**Consequences:**
A Keycloak user mapped to the Dremio ADMIN role can log in successfully (JIT provisioning creates their user record), but their ADMIN membership is not created (role mapping fails silently). They can authenticate but cannot administer Dremio. The only ADMIN user remains the bootstrap user.

**Prevention:**
1. Implement role mapping as a direct `MembershipStore.add()` call (bypassing the RBAC-gated `RbacService.addMembership()` method), with explicit `grantedBy = "KEYCLOAK"` or `"SYSTEM"`. Provisioning code runs with system privileges.
2. Add a unit test: JIT-provision a user with Keycloak `admin` role. Assert: `rbacService.isAdminMember(username)` returns `true` after provisioning.
3. Never route JIT provisioning through the user-facing REST API endpoints (e.g., `POST /api/v3/rbac/roles/ADMIN/members`). Those endpoints require the caller to be ADMIN.

**Warning signs:**
- Keycloak users with the `admin` role can log in but see "access denied" for admin operations.
- `sys.membership` does not contain an ADMIN entry for Keycloak-provisioned admin users.

**Phase:** v1.5 Phase 3 (role mapping) — the bypass must be explicit in the provisioning design.

---

## P41 — Token Validation Ambiguity: Keycloak JWT vs Dremio Opaque Token Cannot Be Distinguished

**What goes wrong:**
`DACAuthFilter` calls `TokenUtils.getAuthHeaderToken()` which returns a token string from the `Authorization: Bearer <token>` header. It then calls `tokenManager.validateToken(token)`. With Keycloak integration, two valid token formats exist: Dremio opaque tokens (random base-32 strings, stored in KV) and Keycloak JWTs (base64url-encoded JWS, start with `eyJ`). If the code path does not distinguish between them before calling `validateToken()`, Keycloak JWTs are passed to `TokenManagerImpl.validateToken()` which does a KV lookup — the JWT is not in the KV store, so the lookup fails with "invalid token", and Keycloak users are rejected at the REST API layer.

**Why it happens:**
The existing filter was designed for a single token type. Adding a second type requires a discriminator step. Developers may attempt to add Keycloak validation inside `TokenManagerImpl.validateToken()` itself (branching on JWT parse success), which violates the single-responsibility principle and entangles the token manager with Keycloak configuration.

**Consequences:**
Keycloak Bearer tokens are rejected at the REST API even after all other Keycloak integration pieces are in place, because the entry point in `DACAuthFilter` routes them to the wrong validator.

**Prevention:**
1. Add a token type discriminator before calling `validateToken()`: attempt to parse the token as a JWT using `JWTParser.parse()`. If it succeeds and the issuer matches the configured Keycloak issuer, route to `KeycloakJWTValidator`. If it fails (not a JWT), route to `tokenManager.validateToken()` (Dremio opaque token).
2. Alternatively: implement a new `AuthProvider` for Keycloak tokens and register it in the `Authenticator` chain. The `Authenticator` already has the `isSupported(tokenType)` dispatch pattern via `AuthProvider`. Add `token_type = "keycloak_jwt"` as a recognized type.
3. Do not modify `TokenManagerImpl` to understand JWTs. Keep the token type boundary clean.
4. Test: POST to a `@Secured` endpoint with a Dremio opaque token. Assert: 200. POST with a Keycloak JWT. Assert: 200. POST with a garbage string. Assert: 401.

**Warning signs:**
- "Invalid token" errors appear for Keycloak JWTs at REST endpoints that work fine for Dremio tokens.
- `TokenManagerImpl.validateToken()` is invoked with a JWT string (observable in debug logs).

**Phase:** v1.5 Phase 1 (auth provider plugging) — the discriminator is the first code change required.

---

## Technical Debt Patterns (v1.5 Keycloak)

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Hard-code Keycloak issuer URL | Simpler initial config | Cannot switch realms or move Keycloak without restart | Never — externalize to dremio.conf from day one |
| Use `preferred_username` as UID | Human-readable logs | All RBAC memberships lost on username rename | Only if username changes are forbidden by policy (document this) |
| Skip `state` parameter validation | Simpler callback handler | CSRF attack vector enabled | Never |
| Authoritative Keycloak role sync (delete + re-add) | Simpler sync logic | Manual RBAC grants lost on next login | Only if no manual RBAC grants are ever used |
| Issue Dremio session token at Keycloak login, never re-validate | Existing session infra reused | Deprovisioned users remain active for 30 hours | Acceptable only if session TTL is reduced to ≤60 minutes |
| Skip JIT provisioning for REST API token path | Fewer code paths | REST API clients with new Keycloak tokens get 401 | Never — REST is a primary access path |

---

## Security Mistakes (v1.5 Keycloak-specific)

| Mistake | Risk | Prevention |
|---------|------|------------|
| Accept tokens without issuer validation | Tokens from any Keycloak realm accepted | Configure `DefaultJWTClaimsVerifier` with exact issuer |
| Accept tokens without audience validation | Tokens issued for other clients accepted | Add `aud` claim check for Dremio client ID |
| Store Keycloak client secret in dremio.conf plaintext | Secret exposure in config files | Use environment variable or secrets manager reference |
| Allow `preferred_username` to be empty/null | Dremio user with blank username created | Reject tokens where `preferred_username` is absent or empty |
| Expose OIDC callback endpoint without HTTPS | Authorization code interceptable in transit | Enforce HTTPS for the callback redirect URI in Keycloak client config |
| Not validating `nonce` claim for ID tokens | Token replay attacks | Store and validate nonce for authorization code flows using ID tokens |

---

## "Looks Done But Isn't" Checklist (v1.5 Keycloak)

- [ ] **Keycloak JWT validation:** Often missing issuer and audience checks — verify `DefaultJWTClaimsVerifier` is configured with both `iss` and `aud` exact-match claims
- [ ] **JWKS caching:** Often missing `kid`-based refresh — verify that an unknown `kid` triggers a JWKS re-fetch before rejecting the token
- [ ] **JIT provisioning:** Often missing concurrency protection — verify that concurrent first logins for the same user produce exactly one user record
- [ ] **Flight/JDBC Bearer auth:** Often missing Keycloak JWT routing — verify that `DremioBearerTokenAuthenticator` handles Keycloak JWTs, not just Dremio opaque tokens
- [ ] **OIDC state validation:** Often present but validating wrong session — verify the state is stored in a server-side session tied to the initiating request, not a client-side cookie alone
- [ ] **Backward compatibility:** Often broken on first attempt — verify bootstrap admin can log in with internal password after Keycloak is enabled
- [ ] **Role mapping bootstrap:** Often missing ADMIN membership creation — verify Keycloak admin-mapped users have `isAdminMember()` = true after JIT provisioning

---

## Pitfall-to-Phase Mapping (v1.5 Keycloak)

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| P28 — Missing issuer/audience validation | Phase 1: JWT validation infrastructure | Unit test: token from wrong realm rejected |
| P29 — Clock skew failures | Phase 1: JWT validation infrastructure | Integration test: token validated with 30s skew tolerance |
| P30 — Stale JWKS after key rotation | Phase 1: JWKS caching | Integration test: key rotation followed by token validation |
| P31 — JIT race condition | Phase 2: JIT provisioning | Load test: 10 concurrent first logins, 1 user record created |
| P32 — Role mapping overwrites manual grants | Phase 3: Role mapping design | Test: manual grant survives next SSO login |
| P33 — Missing CSRF state validation | Phase 4: OIDC redirect UI flow | Security test: modified `state` rejected |
| P34 — REST API 401 for new Keycloak users | Phase 2: JIT provisioning | Test: direct Bearer token API call for new user succeeds |
| P35 — Flight bypasses Keycloak validation | Phase 5: Flight/JDBC integration | Test: Keycloak JWT accepted by Flight endpoint |
| P36 — Session outlives Keycloak revocation | Phase 4: Session management | Test: deprovisioned user rejected within TTL window |
| P37 — Username claim instability | Phase 2: JIT provisioning | Test: username rename preserves RBAC memberships |
| P38 — Internal auth broken when Keycloak enabled | Phase 1: Auth provider plugging | Test: bootstrap admin logs in after Keycloak enabled |
| P39 — JDBC token expiry under long sessions | Phase 5: JDBC/ODBC integration | Document + test: 5-min token fails, Dremio session token works |
| P40 — JIT admin membership not created | Phase 3: Role mapping | Test: Keycloak admin user has ADMIN membership after provision |
| P41 — Token type discrimination | Phase 1: Auth provider plugging | Test: Keycloak JWT and Dremio opaque token both accepted at REST |

---

## Sources

- Dremio OSS source: `services/tokens/src/main/java/com/dremio/service/tokens/jwt/JWTValidatorImpl.java` — JWT validation via Nimbus JOSE+JWT, subject-based user resolution
- Dremio OSS source: `services/tokens/src/main/java/com/dremio/service/tokens/jwks/RemoteJWKSetManager.java` — 24-hour JWKS cache, `kid`-based cache refresh pattern
- Dremio OSS source: `dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java` — token validation → `getUser()` dependency, `UserNotFoundException` → 401 path
- Dremio OSS source: `services/arrow-flight/src/main/java/com/dremio/service/flight/auth2/DremioBearerTokenAuthenticator.java` — opaque token-only Flight auth
- Dremio OSS source: `services/users/src/main/java/com/dremio/service/users/SimpleUserService.java` — user creation via KV store, no atomic check-and-create
- Dremio OSS source: `sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java` — `PUT_CREATE` semantics, username-keyed membership records
- Keycloak GitHub issue #8966 — clock skew in JWT client authentication: https://github.com/keycloak/keycloak/issues/8966
- Keycloak GitHub issue #38819 — audience validation strictness: https://github.com/keycloak/keycloak/issues/38819
- Nimbus JOSE+JWT docs — validating JWT access tokens: https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens
- Auth0 docs — state parameter and CSRF prevention: https://auth0.com/docs/secure/attack-protection/state-parameters
- RFC 9700 — OAuth 2.0 Security Best Current Practice: https://datatracker.ietf.org/doc/rfc9700/
- Keycloak JWKS caching discussion #14152: https://github.com/keycloak/keycloak/discussions/14152

---

*Pitfalls research for: Keycloak OIDC integration into Dremio OSS with existing RBAC*
*Sections P1–P14: RBAC v1.0 — 2026-02-17*
*Sections P15–P27: RBAC v2.0 (definer rights, PDS, container visibility) — 2026-02-20*
*Sections P28–P41: Keycloak OIDC v1.5 integration — 2026-03-12*
