# Project Research Summary

**Project:** Naive RBAC for Dremio OSS
**Domain:** Access control retrofit on an existing data lakehouse platform
**Researched:** 2026-02-17
**Confidence:** HIGH

## Executive Summary

Adding RBAC to Dremio OSS is a well-trodden problem in database systems, and the Dremio codebase is already prepared for it. The SQL DDL parsers (`SqlCreateRole`, `SqlGrant`, `SqlRevoke`, etc.) exist and parse correctly. The enforcement hook (`CatalogImpl.validatePrivilege()`) exists as a no-op. The system table schemas (`SysTableRoleInfo`, `SysTablePrivilegeInfo`, `SysTableMembershipInfo`) are defined with the right fields. The `AccessControlListingManager` interface is wired into `SabotContext` returning null. The handler dispatch uses intentional `Class.forName()` calls expecting handler classes that do not yet exist in OSS. In short: the skeleton is there, waiting for an implementation to fill it. The recommended approach follows existing Dremio patterns exactly -- proto3 values in RocksDB KV stores, `SingletonRegistry` service wiring, `Provider<>` deferred injection, and composite-key point lookups for the hot path.

The primary risk is not technical complexity but operational correctness: a deny-by-default system that activates without a bootstrapped ADMIN will lock out every user, including the administrator. The bootstrap sequence (P1), system-user bypass (P2), and definer-rights enforcement semantics (P3) are the three correctness concerns that must be resolved before any enforcement goes live. Secondary risks include access path gaps (REST and Flight endpoints bypassing catalog-level checks) and the `DACSecurityContext.isUserInRole()` time bomb that currently returns `true` for all roles.

The implementation decomposes cleanly into six phases: design decisions and proto schema first, then persistence, then service logic, then catalog enforcement, then DDL handlers and system tables, and finally REST/Flight hardening. Each phase produces a testable artifact. The total scope is moderate -- most code follows patterns that already exist in the codebase (ScriptStoreImpl for proto3 KV stores, SimpleUserService for indexed lookups, TokenStoreCreator for plain stores). The novel work is concentrated in wiring `RbacService` into `CatalogImpl` and getting the enforcement semantics right.

## Key Findings

### Recommended Stack

The entire stack is internal to Dremio. No new external dependencies are needed. Every component uses a pattern already established in the codebase with clear exemplars.

**Core technologies:**
- **proto3 + `Format.ofProtobuf()`**: Value serialization for all three KV stores (roles, grants, memberships) -- newer and safer than Protostuff; schema evolution is clean with no required fields
- **RocksDB via `LegacyKVStore` / `KVStore`**: Persistence layer -- already the standard for all Dremio service state; local sub-millisecond reads on the hot path
- **`LegacyIndexedStore` with `DocumentConverter`**: For roles (name lookup) and memberships (user lookup) -- Lucene-backed secondary indexes following `SimpleUserService` pattern
- **Plain `LegacyKVStore` (no index)**: For grants -- composite string key enables O(1) point lookups; no secondary index needed
- **Guava `Cache`**: Permission result caching with explicit invalidation on GRANT/REVOKE -- not cross-request initially; request-scoped is safer
- **`SingletonRegistry.bind()` in `DACDaemonModule`**: Service wiring -- not standard Guice modules; Dremio's lifecycle management pattern

**Critical version/API note:** PITFALLS.md (P14) flags the `LegacyKVStore` API as fully `@Deprecated`. The recommendation is to use the current `KVStore` / `KVStoreProvider` API where possible, but STACK.md exemplars (ScriptStoreImpl, TokenStoreCreator) still use the legacy API. Resolution: follow the newest working exemplar available at implementation time; do not block on API migration.

### Expected Features

**Must have (table stakes -- all 10 required for a functional system):**
- Role lifecycle: CREATE ROLE, DROP ROLE
- Role membership: GRANT ROLE TO USER, REVOKE ROLE FROM USER
- Privilege grants: GRANT SELECT ON VDS, GRANT EXECUTE ON FUNCTION, GRANT CREATE_VIEW ON VDS
- Deny-by-default policy (absence of check, not additional logic)
- Catalog-level enforcement in `CatalogImpl.validatePrivilege()`
- Built-in ADMIN role (bypass all checks)
- Built-in PUBLIC role (implicit membership for all users)
- System tables: sys.roles, sys.privileges, sys.membership
- SQL DDL interface (parsers exist; wire handlers)
- CREATE OR REPLACE privilege on VDS

**Should have (differentiators -- defer to v1.1 or later):**
- REST API for role/grant management (2.1) -- useful for UI integration
- Privilege check caching (2.4) -- only if performance demands it
- Audit logging for RBAC DDL (2.6) -- compliance value

**Defer (v2+):**
- WITH GRANT OPTION / REVOKE CASCADE (2.2, 2.7) -- high complexity, delegation semantics
- Container grants / space-level inheritance (2.5) -- requires namespace traversal
- Object ownership model (2.8) -- depends on namespace metadata changes
- SHOW GRANTS / SHOW ROLES (2.3) -- syntactic sugar over system tables

**Anti-features (explicitly do NOT build):**
- Nested roles / role hierarchy
- DENY grants (negative permissions)
- Row-level security, column-level security (views handle this)
- Source-level or space-level permissions
- Physical dataset (PDS) permissions
- Planner-level enforcement (catalog-level is sufficient)

### Architecture Approach

The architecture is a clean layering: a persistence layer (three KV stores), a service layer (`RbacService` implementing business logic and `AccessControlListingManager`), and an enforcement layer (wired into the existing `CatalogImpl.validatePrivilege()` hook). The catalog stack is request-scoped (`CachingCatalog -> SourceAccessChecker -> CatalogImpl`), so `RbacService` must be injected as a singleton `Provider`, not constructed per request. DDL handlers are loaded reflectively by the existing parser infrastructure -- creating classes at the expected fully-qualified names is sufficient.

**Major components:**
1. **`rbac.proto`** (3 messages: Role, Grant, Membership) -- defines the storage schema
2. **RbacStore** (3 KV store creators) -- persistence for roles, grants, memberships with appropriate key designs
3. **RbacService** -- business logic: `hasPrivilege()`, `grantPrivilege()`, `revokePrivilege()`, role/membership CRUD; implements `AccessControlListingManager` for system tables
4. **DDL Handlers** (5 classes: RoleCreateHandler, RoleDropHandler, RoleGrantHandler, GrantHandler, RevokeHandler) -- wire SQL DDL to `RbacService`
5. **CatalogImpl enforcement** -- non-trivial wiring: add SELECT check in `getTable()`, EXECUTE check in UDF resolution, with system-user bypass and ADMIN short-circuit

**Key integration points (by file):**
- `CatalogImpl.java:2767` -- implement `validatePrivilege()`
- `CatalogImpl.java:289` -- add SELECT check after `getTable()`
- `UserDefinedFunctionCatalogImpl.java:156` -- add EXECUTE check
- `SabotContext.java:554` -- return real `AccessControlListingManager`
- `CatalogServiceImpl.java:958` -- inject `RbacService` into `CatalogImpl`
- `DACDaemonModule.java` -- bind all RBAC services

### Critical Pitfalls

1. **Bootstrap deadlock (P1)** -- Deny-by-default with no ADMIN means total lockout. Mitigate by auto-granting ADMIN to the first user via `BootstrapResource`, and/or use an `OptionManager` flag that defaults RBAC to OFF until explicitly enabled.

2. **System-user bypass (P2)** -- `SystemUser.SYSTEM_USERNAME` runs metadata sync, reflections, and internal jobs. Hard-code a bypass in `validatePrivilege()` when the catalog identity is the system user. Do not rely on role membership for this.

3. **Definer-rights confusion (P3)** -- Views use definer rights; the privilege check must happen once at the outer layer (when the user resolves the view), not on the inner tables resolved under the view owner's identity. Check privilege in `getTable()` before view expansion, not after.

4. **EE conflict (P8)** -- Enterprise Edition has its own RBAC. Namespace all OSS KV store keys distinctly. Do not modify SQL parsers. Place code in a non-overlapping package. Default the feature flag to OFF.

5. **Migration lock-out (P7)** -- Existing deployments have views with no grants. Before enabling enforcement, auto-grant PUBLIC SELECT on all existing views, or keep RBAC off by default and require explicit enablement.

## Implications for Roadmap

Based on combined research, the implementation decomposes into six phases ordered by dependency and risk.

### Phase 1: Design and Proto Schema
**Rationale:** Namespace, packaging, and key-format decisions must be locked before any persistence code. P8 (EE conflict) and P12 (schema evolution) are design-phase pitfalls.
**Delivers:** `rbac.proto` with 3 messages; key format specifications; package structure decisions; feature flag definition.
**Addresses:** Foundation for all features; P8 (EE namespace isolation), P12 (proto3 evolution safety).
**Avoids:** P8 by choosing non-colliding KV prefixes and package names; P12 by using proto3 with no required fields.

### Phase 2: Persistence Layer (KV Stores)
**Rationale:** All service logic depends on the stores existing. The store pattern is well-documented (ScriptStoreImpl, TokenStoreCreator exemplars).
**Delivers:** Three KV store creators (roles with indexed name, grants with composite key, memberships with indexed user_name); unit tests for CRUD.
**Addresses:** Features 1.1 (role lifecycle storage), 1.2 (membership storage), 1.3 (grant storage).
**Avoids:** P14 by using the newest available KV API; P12 by following proto3 conventions.

### Phase 3: Service Layer (RbacService)
**Rationale:** Business logic depends on stores; enforcement depends on service. This phase delivers the testable core without any CatalogImpl changes.
**Delivers:** `RbacService` with `hasPrivilege()`, role CRUD, membership CRUD, grant/revoke logic; `AccessControlListingManager` implementation; Guava permission cache; ADMIN bypass and PUBLIC role logic; unit tests.
**Addresses:** Features 1.4 (deny-by-default), 1.6 (ADMIN role), 1.7 (PUBLIC role), 1.8 (system tables backing).
**Avoids:** P1 by implementing bootstrap ADMIN auto-grant logic; P2 by hard-coding system-user bypass; P5 by deferring cross-request caching.

### Phase 4: Catalog Enforcement and DI Wiring
**Rationale:** The highest-risk phase -- wiring `RbacService` into `CatalogImpl` and all catalog access paths. Must be done after the service is fully tested in isolation.
**Delivers:** Working `validatePrivilege()` with SELECT check in `getTable()`, EXECUTE check in UDF resolution, system-user bypass; `DACDaemonModule` bindings; `SabotContext` wiring; feature flag gating; end-to-end integration tests.
**Addresses:** Features 1.5 (catalog enforcement), 1.10 (CREATE OR REPLACE enforcement).
**Avoids:** P3 by checking privilege before view expansion; P9 by auditing internal catalog construction paths; P11 by returning NOT FOUND instead of FORBIDDEN for unauthorized objects.

### Phase 5: DDL Handlers and System Tables
**Rationale:** Handlers depend on a wired `RbacService` accessible via `QueryContext -> SabotContext`. System tables require `AccessControlListingManager` to be bound. Both are low-risk given the reflective dispatch and existing schema.
**Delivers:** 5 DDL handler classes (RoleCreateHandler, RoleDropHandler, RoleGrantHandler, GrantHandler, RevokeHandler); live sys.roles, sys.privileges, sys.membership; SQL DDL integration tests.
**Addresses:** Features 1.8 (system tables live), 1.9 (SQL DDL interface).
**Avoids:** P1 by ensuring bootstrap flow works before DDL is the only admin path.

### Phase 6: Access Path Hardening and Observability
**Rationale:** REST API and Flight gaps are real but secondary to catalog-level enforcement. This phase closes the bypass routes identified in P4, P10, and P13.
**Delivers:** Fixed `DACSecurityContext.isUserInRole()`; audited REST endpoints; INFORMATION_SCHEMA filtering; Flight session privilege enforcement; migration tooling for existing deployments.
**Addresses:** Differentiators 2.1 (REST API -- optional), 2.6 (audit logging -- optional); P4 (access path gaps), P10 (metadata leakage), P13 (isUserInRole time bomb).
**Avoids:** P7 by providing migration tooling; P13 by isolating isUserInRole fix from catalog enforcement.

### Phase Ordering Rationale

- **Phases 1-2 first** because all downstream work depends on stable proto schemas and working KV stores. These are low-risk, well-patterned, and produce testable artifacts.
- **Phase 3 before Phase 4** because the service layer must be independently tested before it is wired into the catalog hot path. Bugs in `hasPrivilege()` logic are much easier to find in unit tests than in end-to-end integration tests.
- **Phase 4 is the critical path** -- it contains the most risk (definer-rights semantics, system-user bypass, identity propagation) and produces the most value (actual enforcement). It should receive the most testing attention.
- **Phase 5 after Phase 4** because DDL handlers need the full DI wiring that Phase 4 establishes. System tables are low-risk given the existing schema and interface.
- **Phase 6 last** because REST/Flight hardening is important but not blocking for the core SQL enforcement path. It can ship incrementally.

### Research Flags

**Phases likely needing deeper research during planning:**
- **Phase 4 (Catalog Enforcement):** The injection path from `DACDaemonModule` through `CatalogServiceImpl.createCatalog()` to `CatalogImpl` constructor needs concrete tracing. STACK.md rates this at MEDIUM confidence. The alternative (SabotContext service-locator lookup) is simpler but less clean. Also needs research: all code paths that create `Catalog` instances during job execution (P9).
- **Phase 6 (Access Path Hardening):** REST endpoint audit scope is unknown. `DatasetVersionResource` is a 1422-line God class that may bypass the catalog. `DACSecurityContext.isUserInRole()` audit of all `@RolesAllowed` annotations needs to be scoped.

**Phases with standard patterns (skip per-phase research):**
- **Phase 1 (Design/Proto):** proto3 schema design is well-documented; key formats are decided in STACK.md with HIGH confidence.
- **Phase 2 (Persistence):** Three concrete exemplars identified (ScriptStoreImpl, SimpleUserService, TokenStoreCreator). Copy-and-adapt.
- **Phase 3 (Service):** Standard service pattern with `SingletonRegistry` binding. Guava cache is straightforward.
- **Phase 5 (DDL Handlers):** Reflective dispatch pattern is documented; handler contract is `SimpleDirectHandler` with `toResult()` returning `SimpleCommandResult`.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Every recommendation has a concrete codebase exemplar. proto3, KV stores, SingletonRegistry -- all verified against live code. |
| Features | HIGH | Feature set derived from SQL standard (SQL:1999/2003), PostgreSQL 16, Snowflake, and Dremio's own enum/schema definitions. Clear table-stakes vs. differentiator boundaries. |
| Architecture | HIGH | Catalog stack, data flows, and integration points verified against specific file paths and line numbers in the codebase. |
| Pitfalls | HIGH | 14 pitfalls identified from codebase analysis, each with specific file references and prevention strategies. The bootstrap deadlock and definer-rights confusion are the highest-impact risks. |

**Overall confidence:** HIGH

The research is based entirely on direct analysis of the Dremio OSS codebase at commit `799ccbda4`, not on external documentation or inference. The existing hooks, parsers, schemas, and patterns are verified against specific files and line numbers. The one area of MEDIUM confidence is the exact injection path for `RbacService` into `CatalogImpl` (the constructor chain from `DACDaemonModule` to `CatalogImpl` has not been fully traced).

### Gaps to Address

- **CatalogImpl injection path:** The exact mechanism for getting `RbacService` into `CatalogImpl` needs validation during Phase 4 planning. Two options exist (constructor parameter vs. SabotContext lookup); the choice depends on the construction chain depth.
- **`KVStore` vs. `LegacyKVStore` API:** P14 flags the legacy API as deprecated; STACK.md exemplars still use it. The newest working exemplar at implementation time should be followed. This may require a quick audit of whether `KVStoreCreationFunction` (non-legacy) is usable for the RBAC stores.
- **Multi-coordinator cache invalidation:** Deferred by design (P5 recommends no cross-request caching for v1), but if performance demands it, the NATS pub/sub infrastructure needs evaluation.
- **Migration tooling scope:** P7 identifies the need for a migration step for existing deployments. The exact mechanism (startup flag, CLI command, automatic on first enable) needs to be decided during Phase 6 planning.
- **REST endpoint audit scope:** The number of REST resources that bypass `CatalogImpl` and go directly to `NamespaceService` is unknown. This audit is needed before Phase 6 can be scoped.

## Sources

### Primary (HIGH confidence)
- Dremio OSS codebase at commit `799ccbda4` -- all file paths, line numbers, and pattern exemplars verified directly
- `CatalogImpl.java` -- enforcement hook, identity propagation, validatePrivilege() no-op
- `ScriptStoreImpl.java` -- proto3 KV store exemplar
- `SimpleUserService.java` -- IndexedStore with DocumentConverter exemplar
- `TokenStoreCreator.java` -- minimal plain KV store exemplar
- `DACDaemonModule.java` -- SingletonRegistry service wiring pattern
- `SqlGrant.java`, `SqlCreateRole.java`, `SqlRevoke.java` -- parser and handler dispatch infrastructure
- `SystemTable.java` -- system table registration for roles/privileges/membership
- `AccessControlListingManager.java` -- interface already defined for system table backing
- `SabotContext.java` -- getAccessControlListingManager() returning null, ready to wire

### Secondary (MEDIUM confidence)
- SQL standard (SQL:1999, SQL:2003) -- RBAC semantics, definer-rights model
- PostgreSQL 16 documentation -- RBAC reference implementation patterns
- Snowflake RBAC, Databricks Unity Catalog, BigQuery IAM -- feature completeness benchmarks

---
*Research completed: 2026-02-17*
*Ready for roadmap: yes*
