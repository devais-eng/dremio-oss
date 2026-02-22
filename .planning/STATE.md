# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-20)

**Core value:** Users can only access views, tables, and UDFs they've been explicitly granted access to, with deny-by-default policy, privilege context switching (definer rights for VDS and UDF), and admin bypass.
**Current focus:** v1.2 Privilege Context & Enforcement — Phase 12 COMPLETE (all plans done, integration tests green)

## Current Position

Phase: 12 of 12 (Metadata Safety and Integration Testing)
Plan: 03 of 03 complete (gap closure plan)
Status: v1.2 RBAC FULLY COMPLETE — all implementation plans + integration tests green
Last activity: 2026-02-22 — Phase 12 Plan 03 complete: 27 integration tests covering all v1.2 RBAC features pass (TestRbacIntegration)

Progress: [██████████] 100% (v1.2)

## Performance Metrics

**Velocity (v1.0 reference):**
- Total plans completed: 15 (v1.0)
- Average duration: ~30 min estimated
- Total execution time: ~7.5 hours

**By Phase (v1.0):**

| Phase | Plans | Status |
|-------|-------|--------|
| 1. Design and Proto Schema | 2 | Complete |
| 2. Persistence Layer | 2 | Complete |
| 3. Service Layer | 2 | Complete |
| 4. Catalog Enforcement and DI Wiring | 3 | Complete |
| 5. DDL Handlers and System Tables | 3 | Complete |
| 6. REST API and Access Path Hardening | 3 | Complete |

*v1.2 metrics will be tracked as phases complete*

**v1.2 Phase Metrics:**

| Phase-Plan | Duration (min) | Tasks | Files |
|------------|---------------|-------|-------|
| Phase 07 P01 | 5 | 2 | 5 |
| Phase 07 P02 | 8 | 2 | 3 |
| Phase 08 P01 | 15 | 2 | 4 |
| Phase 08 P02 | 4 | 2 | 3 |
| Phase 09 P01 | 2 | 2 | 3 |
| Phase 09 P02 | 2 | 2 | 1 |
| Phase 10 P01 | 10 | 2 | 3 |
| Phase 10 P02 | 2 | 2 | 2 |
| Phase 11 P01 | 3 | 2 | 2 |
| Phase 11 P02 | 5 | 2 | 6 |
| Phase 12 P01 | 10 | 2 | 2 |
| Phase 12 P02 | 3 | 2 | 3 |
| Phase 12 P03 | 60 | 2 | 2 |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting v1.2 work:

- [v1.2 roadmap]: Phase 8 (definer rights) must complete before Phase 10 (PDS SELECT) — the definer's PDS access must resolve correctly through the isInDefinerContext flag
- [v1.2 roadmap]: Phase 11 (container visibility) is independent of Phases 8-10 — can be planned and executed after Phase 7 without blocking
- [v1.2 roadmap]: Phase 12 (integration tests) is last — all features must be present before end-to-end tests are meaningful
- [research]: PDS enforcement uses a separate config flag (services.rbac.pds.enabled) for independent rollout. When enabled, deny-by-default: users must have explicit SELECT grants (consistent with VDS model). Admin bypass via hasPrivilege() short-circuit
- [research]: isInDefinerContext flag pattern (P25) must be agreed before Phase 8 coding begins — it defines the boundary between outer caller checks and inner definer checks
- [Phase 07]: validatePrivilege() error format is 'Permission denied: {PRIVILEGE} privilege required on {path}' — no GRANT hint per locked decision
- [Phase 07]: validateCreateViewPrivilege() checks parent container path (viewKey.getParent().getSchemaPath()), not the view path itself
- [Phase 07]: DROP privilege maps to VDS object type in resolveRbacObjectType() (same group as ALTER, SELECT)
- [Phase 07 P02]: ALTER checks in createVersionedView()/createView() placed after isUpdate &= exists — check only fires when view truly exists and will be updated
- [Phase 07 P02]: DropViewHandler bug was ALTER->DROP, single-char fix closes LIFE-02 SQL DDL path
- [Phase 07 P02]: All 8 enforcement call sites wired: 4 in SQL DDL handlers, 4 in REST API paths
- [Phase 08 P01]: Removed VIRTUAL_DATASET type check entirely from CatalogEntityOwnershipImpl — both PDS and VDS share null/empty owner guard; activates DEFN-01/02/03 via existing ViewExpander identity-switching chain
- [Phase 08 P01]: ViewExpansionContext.reserveViewExpansionToken() signature changed to accept NamespaceKey viewPath — enables cycle detection (DEFN-06); only one production caller (ViewExpander)
- [Phase 08 P01]: DEFN-05 guard: rbacEnabled && viewOwner != null in catch(UserNotFoundException) block — explicit planError only when RBAC active and view had recorded owner; legacy null-owner still falls back
- [Phase 08 P01]: rbacEnabled wired as boolean constructor parameter to ViewExpander; SqlConverter reads context.getDremioConfig() with null guard (same pattern as CatalogImpl.validatePrivilege())
- [Phase 08]: containsDefinerRightsExpansion placed after versioned-table check in PlanCacheUtils.supportPlanCache() — conservative ordering; null guards for both viewTable and viewOwner ensure legacy VDS and non-view ExpansionNodes remain cacheable
- [Phase 08]: 12 unit tests added instead of 6 minimum: chained 3-owner scenario (User A owns V1, User B creates V2 from V1, User C queries V2) directly tested in testViewExpansion_chainedDefinerRights_noCycle
- [Phase 09 P01]: FunctionConfig.owner (field 9) stamped directly on FunctionConfig by UserDefinedFunctionCatalogImpl — NOT piped through UserDefinedFunctionSerde.toProto()/fromProto() (UserDefinedFunction Java class has no owner field)
- [Phase 09 P01]: FUNCTION case in CatalogEntityOwnershipImpl uses fully-qualified FunctionConfig class name to avoid ambiguity; mirrors DATASET case pattern exactly
- [Phase 09 P01]: Legacy UDFs with null owner fall back to Optional.empty() (query user identity) — consistent with DATASET pattern
- [Phase 09 P02]: Added 4 UDF-03 tests instead of plan's 2 — rbacDisabled and systemUser paths cover the two early-return paths in isRbacDeniedForFunction() missed by the core deny/allow tests
- [Phase 09 P02]: Used var for getFunctions() return type — avoids importing Collection/Function; consistent with existing test at line 1114
- [Phase 10 P01]: RBAC_PDS_ENABLED defaults to false — PDS enforcement is OFF until explicitly enabled, allowing RBAC_ENABLED=true + RBAC_PDS_ENABLED=false for VDS-only enforcement
- [Phase 10 P01]: hasAnyPdsGrant() uses 'PDS' object type string matching SqlGrant.GrantType.PDS.name() from CatalogGrantHandler persistence — opt-in semantics ensure tables with no grants remain universally accessible
- [Phase 10 P01]: isRbacDeniedForPds() placed immediately after isRbacDeniedForVds() with symmetric 6-step guard chain; TODO comment at getTable(String datasetId) documents out-of-scope path
- [Phase 10]: PDS enforcement tests cannot directly exercise isRbacDeniedForPds() via getTable — DatasetManager is internal, tests document RbacService call contract via never() and mock verifications
- [Phase 10]: DDL PDS tests mirror VDS pattern exactly: GrantType.PDS replaces GrantType.VDS, objectType 'PDS' used in all grantPrivilege/revokePrivilege verify() calls
- [Phase 11 P01]: Two container visibility methods: getAccessibleObjectPaths() (batch, one grant scan for all containers) and hasAccessibleChildUnderPath() (single container, admin short-circuit) -- per Research Pitfall 5
- [Phase 11 P01]: Sources filtered same as spaces in getTopLevelCatalogItems() -- deny-by-default PDS means grant prefix scan correctly identifies sources with no accessible children
- [Phase 11 P01]: getUserAccessibleObjectPaths() returns null for RBAC-disabled/admin as "show all" signal -- callers check accessiblePaths == null to skip filtering
- [Phase 11 P01]: Folder filtering in isVisibleToUser() uses per-folder hasAccessibleChildUnderPath() -- child folder listings are smaller cardinality than top-level
- [Phase 11 P02]: ResourceTreeResource and SourcesResource use getUserAccessiblePaths() returning null-as-show-all -- same pattern as CatalogServiceHelper from Plan 01
- [Phase 11 P02]: SourcesResource filters early in loop before creating SourceUI and fetching dataset counts -- avoids wasted work for hidden sources
- [Phase 11 P02]: 7 unit tests cover getAccessibleObjectPaths (3: granted paths, PUBLIC grants, empty grants) and hasAccessibleChildUnderPath (4: prefix matching, dot-boundary safety, admin bypass, deep nesting CONT-04)
- [Phase 12 P01]: isRbacDeniedForSysPrivileges uses isAdminMember() not hasPrivilege() -- sys.privileges access is role-based (admin membership), not grant-based
- [Phase 12 P01]: Guard fires before getTableHelper() in getTableNoResolve and getTableNoColumnCount (pre-fetch) for efficiency; table object not needed
- [Phase 12 P01]: equalsIgnoreCase for both 'sys' and 'privileges' to prevent case-variation bypass (SYS.PRIVILEGES, Sys.Privileges, etc.)
- [Phase 12 P02]: Soft-deny pattern in DESCRIBE: validatePrivilege() is VDS-only; PDS users get through via getTable() fallback (isRbacDeniedForPds allows); exception only re-thrown when both checks fail
- [Phase 12 P02]: UserException catch block added between AccessControlException and Exception catches to prevent planError wrapping of permission denied messages (Pitfall 5)
- [Phase 12 P02]: sys and INFORMATION_SCHEMA skip validatePrivilege entirely -- handled by isRbacDeniedForSysPrivileges in getTable()
- [Phase 12 P02]: META-03 required no code change -- ExplainHandler delegates to inner handlers; structural test documents contract
- [Phase 12 P03]: GRANT syntax is TO ROLE not TO USER; integration tests use USER_ROLE as intermediary role and GRANT ROLE USER_ROLE TO USER test_user
- [Phase 12 P03]: Admin bootstrap required via rbacService.assignBootstrapAdmin(ADMIN) when RBAC enabled and users created programmatically (not via REST bootstrap endpoint)
- [Phase 12 P03]: DROP VDS requires both SELECT and DROP grants -- DropViewHandler calls getTableNoColumnCount after validatePrivilege(DROP), isRbacDeniedForVds checks SELECT
- [Phase 12 P03]: services.rbac.pds.enabled key added to dremio-reference.conf to pass DACConfig.checkForInvalidPaths validation when RBAC_PDS_ENABLED passed via test config

### Pending Todos

None.

### Blockers/Concerns

- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available. Full Maven compile blocked until Java 21 JDK is installed.
- [Phase 08 P02 resolved]: DEFN-04 (plan cache definer chain) complete — containsDefinerRightsExpansion() traverses the rel tree and bypasses cache when any ExpansionNode has a non-query-user definer; NOT_PUT_DEFINER_RIGHTS metric emitted.
- [Phase 10]: bulkGetTables() performance with opt-in PDS check (one listGrantsByObject() call per table in batch) must be profiled before shipping.
- [Phase 08 P01 resolved]: VolcanoPlanner threading concern (P27) confirmed LOW risk — ViewExpansionContext is per-query, not shared; inExpansionPaths Set is single-threaded within planning.
- [Phase 08 P01 resolved]: DatasetConfig.owner write path confirmed via research (DatasetsUtil.toVirtualDatasetVersion → datasetConfig.setOwner()); owner IS populated on VDS save.

## Session Continuity

Last session: 2026-02-22
Stopped at: Completed 12-03-PLAN.md. Phase 12 Plan 03 complete: 27 integration tests covering all v1.2 RBAC features (TestRbacIntegration). v1.2 RBAC fully complete.
Resume file: .planning/phases/12-metadata-safety-and-integration-testing/12-03-SUMMARY.md
