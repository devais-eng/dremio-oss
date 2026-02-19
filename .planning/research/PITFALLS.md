# RBAC Retrofit Pitfalls

**Research Date:** 2026-02-17
**Scope:** Adding deny-by-default RBAC to Dremio OSS — catalog-level enforcement via `CatalogImpl.validatePrivilege()`

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

**Phase:** Implementation — Phase 1, before any enforcement goes live.

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

**Phase:** Implementation — before any enforcement check is active.

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

**Phase:** Implementation — the most critical correctness test to have before enabling enforcement.

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

**Phase:** Implementation — Phase 2 after catalog-layer checks are done; REST/Flight gaps are second priority but must not be deferred past MVP.

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

**Phase:** Implementation — avoid the problem by not caching; revisit in optimization phase.

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

**Phase:** Implementation — audit before enabling enforcement.

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

**Phase:** Implementation — Phase 2; can be deferred from MVP but must be tracked.

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

**Phase:** Implementation — bake into the initial `validatePrivilege()` implementation.

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

**Phase:** Implementation — Phase 2, after catalog-level enforcement is stable.

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

**Phase:** Implementation — first line of code constraint.

---

## Summary Table

| # | Pitfall | Phase |
|---|---------|-------|
| P1 | Bootstrap deadlock: who grants the first ADMIN | Phase 1 |
| P2 | SYSTEM_USERNAME as a silent backdoor | Phase 1 |
| P3 | Definer-rights confusion: checking the wrong layer | Phase 1 |
| P4 | Access path gaps: SQL is not the only door | Phase 2 |
| P5 | Cache invalidation when grants change | Phase 1 |
| P6 | Performance: KV lookup on every catalog resolution | Optimization |
| P7 | Migration lock-out: existing views/UDFs have no owner | Migration |
| P8 | EE conflict: clobbering the Enterprise RBAC | Design |
| P9 | Implicit ADMIN: internal operations blocked by wrong identity | Phase 1 |
| P10 | INFORMATION_SCHEMA leaks object existence | Phase 2 |
| P11 | Error messages reveal object existence | Phase 1 |
| P12 | KV store schema evolution: protobuf changes break records | Design |
| P13 | DACSecurityContext.isUserInRole() time bomb | Phase 2 |
| P14 | Using the deprecated LegacyKVStore API | Phase 1 |

---

*Pitfall research: 2026-02-17*
