---
gsd_state_version: 1.0
milestone: v1.5
milestone_name: Open-Source RDBMS JDBC Plugin
status: completed
stopped_at: Completed 39-01-PLAN.md — Tier 2 Docker integration tests (35 tests, all pushdown patterns verified via PG logs + Oracle V$SQL)
last_updated: "2026-03-18T13:10:29Z"
last_activity: 2026-03-18 — Completed 39-01 (Tier 2 integration test suite: 35 tests, DremioJdbcContainer + PG/Oracle/ADBC pushdown verification via live Dremio planner stack)
progress:
  total_phases: 9
  completed_phases: 9
  total_plans: 24
  completed_plans: 24
  percent: 100
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-12)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** Phase 39 — Docker-based planner integration tests (COMPLETE)

## Current Position

Phase: 39 of 39 (Docker-based planner integration tests — full pushdown verification with ADBC)
Plan: 1 of 1 complete — PHASE COMPLETE
Status: COMPLETE
Last activity: 2026-03-18 — Completed 39-01 (Tier 2 Docker integration tests: 35 pushdown verification tests via live Dremio planner + PG logs + Oracle V$SQL)

Progress: [██████████] 100% (24 of 24 plans complete)

## Performance Metrics

**Velocity:**
- Total plans completed: 12 (v1.5+)
- Average duration: 12.3 min
- Total execution time: 2.46 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 30-base-jdbc-framework P01 | 1 | 9 min | 9 min |
| 30-base-jdbc-framework P02 | 1 | 10 min | 10 min |
| 30-base-jdbc-framework P03 | 1 | 10 min | 10 min |
| 31-postgresql-connector P01 | 1 | 21 min | 21 min |
| 31-postgresql-connector P02 | 1 | 17 min | 17 min |
| 32-oracle-connector P01 | 1 | 10 min | 10 min |
| 32-oracle-connector P02 | 1 | 4 min | 4 min |
| 33-advanced-query-pushdown-hardening P01 | 1 | 14 min | 14 min |
| 33-advanced-query-pushdown-hardening P02 | 1 | 17 min | 17 min |
| 33-advanced-query-pushdown-hardening P03 | 1 | 5 min | 5 min |
| 34-adbc-driver-for-at-least-postgres P01 | 1 | 11 min | 11 min |
| 34-adbc-driver-for-at-least-postgres P02 | 1 | 14 min | 14 min |

**Recent Trend:**
- Last 5 plans: 12.2 min avg
- Trend: stable

| 35-join-intersect-except-pushdown P01 | 1 | 15 min | 15 min |
| 35-join-intersect-except-pushdown P02 | 1 | 5 min | 5 min |
| 35-join-intersect-except-pushdown P03 | 1 | 22 min | 22 min |
| 36-calcite-jdbc-convention-migration P01 | 1 | 47 min | 47 min |
| 36-calcite-jdbc-convention-migration P02 | 1 | 11 min | 11 min |
| 36-calcite-jdbc-convention-migration P03 | 1 | 285 min | 285 min |
| 37-expression-pushdown P01 | 1 | 16 min | 16 min |
| 37-expression-pushdown P02 | 1 | 90 min | 90 min |

*Updated after each plan completion*
| Phase 38-expression-pushdown P01 | 45 | 2 tasks | 7 files |
| Phase 38 P02 | 18 | 2 tasks | 2 files |
| Phase 38 P03 | 3 | 2 tasks | 4 files |
| Phase 39-docker-integration-tests P01 | 25 | 2 tasks | 5 files |

## Shipped Milestones

- v1.0 Naive RBAC — 6 phases, 15 plans (shipped 2026-02-19)
- v1.1 Enable Iceberg REST Catalog — 2 phases, 3 plans (shipped 2026-02-20)
- v1.2 GitHub Actions Docker Distribution — 3 phases, 3 plans (shipped 2026-02-21)
- v1.3 Privilege Context & Enforcement — 9 phases, 17 plans (shipped 2026-02-24)
- v1.4 RBAC Issue Hardening — 9 phases, 14 plans (shipped 2026-03-11)

## Accumulated Context

### Decisions

- [Phase 30-base-jdbc-framework P01]: HikariCP 5.1.0 added to root POM dependencyManagement separate from hive3's 2.6.1 test-scope entry
- [Phase 30-base-jdbc-framework P01]: BaseJdbcConf has no @SourceType — concrete connector subclasses carry that annotation
- [Phase 30-base-jdbc-framework P01]: JdbcStoragePlugin metadata methods are stubbed; DatabaseMetaData discovery deferred to plan 02
- [Phase 30-base-jdbc-framework P02]: BatchSchema.findFieldIgnoreCase() returns Optional<Field> — null-check in plan must use .isPresent() / .get()
- [Phase 30-base-jdbc-framework P02]: JdbcGroupScan extends AbstractBase + GroupScan<SimpleCompleteWork> (not AbstractGroupScan) to avoid TableMetadata dependency
- [Phase 30-base-jdbc-framework P02]: Single-partition JDBC: listPartitionChunks() returns PartitionChunk.of(DatasetSplit.of(0L, 0L))
- [Phase 30]: JdbcScanDrule is not a singleton: created per-call with SourceType from JdbcRulesFactory, matching ElasticScanRule pattern
- [Phase 30]: JdbcScanPrule added for Drel->Prel PHYSICAL conversion (plan omission auto-fixed)
- [Phase 31-01]: createRecordReader() is public (not protected) in JdbcStoragePlugin — JdbcScanCreator is in a sibling package and cannot use protected access
- [Phase 31-01]: SecretRef.get() used directly for getPassword() rather than toConfiguration() (which requires a hadoop-style prefix string)
- [Phase 31-01]: PostgresConf.newPlugin() uses anonymous JdbcStoragePlugin subclass to wire factory overrides without a named PostgresStoragePlugin class
- [Phase 31-01]: Tag numbers start at 10 in PostgresConf (10-41); BaseJdbcConf Tags 1-3 (poolSize/idleTimeoutMs/validationQuery) satisfy PG-01 maxIdleConns/idleTimeSec requirements
- [Phase 31-postgresql-connector]: DremioPostgresContainer wraps PostgreSQLContainer implementing DremioContainer marker — satisfies DremioRestrictedTestcontainersUsage error-prone rule that cannot be suppressed
- [Phase 31-postgresql-connector]: TestJdbcConf is a named static inner class not anonymous — BaseJdbcConf<T extends BaseJdbcConf<T,P>, P> self-referential bound cannot be satisfied by anonymous classes with wildcard type args
- [Phase 32-oracle-connector]: OracleSchemaFetcher.isSystemSchema() does NOT call super — base filters PG-specific schemas; full replacement avoids false positives for Oracle
- [Phase 32-oracle-connector]: JdbcScanPrel removes sqlBuilder field entirely; resolves plugin-provided SqlBuilder at getPhysicalOperator() time via PhysicalPlanCreator
- [Phase 32-oracle-connector]: Oracle FLOAT sentinel: NUMERIC with scale=-127 mapped to DOUBLE; bare NUMBER with precision=0 also mapped to DOUBLE
- [Phase 32-oracle-connector]: OracleConf.validationQuery set to SELECT 1 FROM DUAL in constructor to override BaseJdbcConf default
- [Phase 32-oracle-connector P02]: Oracle test schema/table names are UPPERCASE (TEST_USER, TYPE_TEST, DISCOVERY_TABLE) — DatabaseMetaData returns uppercase identifiers for Oracle
- [Phase 32-oracle-connector P02]: testNoLimitKeyword() in TestOraclePushdown is the critical Oracle-specific correctness gate — asserts LIMIT never appears in generated SQL when a row limit is requested
- [Phase 32-oracle-connector P02]: Oracle multi-row insert uses INSERT ALL...INTO...INTO...SELECT 1 FROM DUAL syntax (not PostgreSQL-style VALUES (r1),(r2))
- [Phase 33-01]: NULL, TRUE, FALSE literals stay inline (SQL keywords, not injection risk) — only data literals parameterized
- [Phase 33-01]: Date/Time/Timestamp values stored as epoch millis Long in BindParam for Jackson serialization
- [Phase 33-01]: OracleSqlBuilder overrides appendLimit() hook method instead of full buildSql(SqlBuildRequest) — reduces duplication
- [Phase 33-01]: OR-of-EQUALS on same column auto-converts to IN (?, ?, ...) for cleaner SQL
- [Phase 33-01]: SqlBuildRequest includes future-facing fields (selectExprs, groupByClause, orderByClause) for Plans 02/03
- [Phase 33-02]: TopN uses sequential rule firing (sort absorbed first, then limit) rather than single combined rule -- reuses existing JdbcPushLimitIntoScan
- [Phase 33-02]: SortPrel.offset/fetch always null in Dremio (SortRelBase asserts this) -- no secondary TopN path needed
- [Phase 33-02]: Backward-compatible JdbcScanPrel constructors: old 2-arg and 3-arg delegate to full 4-arg with orderByClause
- [Phase 33-03]: Only PHASE_1of1 aggregates pushed -- 2-phase partial/final produce incorrect results against single JDBC source
- [Phase 33-03]: DISTINCT aggregates rejected in v1 -- graceful decline rather than incorrect SQL
- [Phase 33-03]: Aggregation registered in PHYSICAL only (not PHYSICAL_HEP) -- structural rowType change benefits from cost-based Volcano decisions
- [Phase 33-03]: overrideRowType mechanism in JdbcScanPrel: deriveRowType() returns override when set, enabling aggregated schema different from base table
- [Phase 34-01]: ADBC deps (0.22.0) added with full Arrow exclusions (<artifactId>*</artifactId>) to avoid 18.3.0 vs 18.1.1-dremio version conflict
- [Phase 34-01]: BoundedAdbcConnection wrapper delegates all AdbcConnection methods and releases Semaphore permit on close()
- [Phase 34-01]: AdbcRecordReader copies vectors (not zero-copy) because ArrowReader uses its own allocator separate from Dremio's
- [Phase 34-01]: DECIMAL and TIME bind params converted to string (VarCharVector) for ADBC PG driver compatibility
- [Phase 34-02]: BaseJdbcConf protocolMode at Tag(4) -- subclasses inherit, no redeclaration to avoid Protostuff serialization conflicts
- [Phase 34-02]: AdbcSchemaFetcher parses nested Arrow getObjects() structure using ListVector offset buffers and StructVector child accessors
- [Phase 34-02]: AdbcException has no simple (String, Throwable) constructor in 0.22.0 -- fallback catch blocks use RuntimeException wrapper
- [Phase 35]: JdbcJoinScanPrel extends AbstractRelNode+LeafPrel (not ScanPrelBase) -- ScanPrelBase requires single-table TableMetadata which a join scan does not have
- [Phase 35]: JdbcPushJoinIntoScan fires in LOGICAL phase -- JdbcScanDrel children at logical level have no WHERE/LIMIT/ORDER BY state (those are added by PHYSICAL-phase rules)
- [Phase 35]: SqlBuilder.buildJoinSql() is non-final (overridable) so OracleSqlBuilder can override AS alias syntax if needed
- [Phase 35-02]: LiteralInliner uses single-quote doubling only (no backslash escaping) — PostgreSQL standard_conforming_strings=on since 9.1
- [Phase 35-02]: AdbcRecordReader.setup() does NOT call stmt.bind() when params present — COPY binary requires simple query protocol; buildBindRoot/setBindValue retained for future use
- [Phase 35-02]: Maven 3.9.9 rejects # comment lines in .mvn/maven.config — removed comments, kept only -Drevision= flag
- [Phase 35-03]: OracleSqlBuilder.buildJoinSql() overrides base to omit AS keyword — Oracle rejects AS for table aliases in FROM/JOIN (ORA-00933); space-separated alias: "schema"."table" "alias"
- [Phase 35-03]: INTERSECT semantics verified via INNER JOIN DISTINCT; EXCEPT semantics via LEFT JOIN + IS NULL filter
- [Phase 36-01]: JdbcCalciteLeaf extends AbstractRelNode (not TableScan) -- TableScan requires non-null RelOptTable; AbstractRelNode is cleaner for direct schema/table string control
- [Phase 36-01]: DremioJdbcImplementor subclass needed -- stock JdbcImplementor only handles JdbcTableScan; our JdbcCalciteLeaf needs explicit visit(JdbcCalciteLeaf) dispatch via reflection
- [Phase 36-01]: Inline literals in WHERE clause (no bind params) -- JdbcImplementor renders literals via AST nodes; JdbcGroupScan receives Collections.emptyList() for bindParams
- [Phase 36-01]: collationToSql() static method removed -- JdbcRules.JdbcSort.implement() handles ORDER BY rendering automatically including NULLS FIRST/LAST per dialect
- [Phase 36-02]: JdbcJoinScanDrel replaces String onClauseSql + List<BindParam> conditionBindParams with single RexNode conditionRex -- eliminates RexToJoinSqlString from the join pipeline
- [Phase 36-02]: JdbcJoinScanPrel.getPhysicalOperator() builds JdbcCalciteLeaf+JdbcJoin subtree rendered by DremioJdbcImplementor -- OracleSqlDialect.allowsAs()==false handles AS-less aliases automatically
- [Phase 36-02]: SqlBuilder.buildJoinSql() and OracleSqlBuilder.buildJoinSql() are now dead code -- kept because unit tests test them directly; Plan 36-03 may remove them
- [Phase 36-03]: Filter/agg index normalization must happen at push time (in each pushdown rule), not at getPhysicalOperator() -- Volcano planner rule firing order means the scan row type can change between push and build time
- [Phase 36-03]: JdbcAggregate always wrapped with renaming JdbcProject -- Calcite renders aggregates without AS aliases; explicit aliases required for JdbcRecordReader column matching
- [Phase 36-03]: JdbcRecordReader uses position-based ResultSet reading -- name-based fails for self-joins (duplicate names) and aggregate alias differences; buildColumnPositions() with ordinal fallback handles all cases
- [Phase 36-03]: JdbcPushJoinIntoScan derives leftColumns from join.getLeft().getRowType() not leftScan.getProjectedColumns() -- scan's projection may exclude WHERE-only columns causing SQL/schema field order mismatch
- [Phase 37-01]: SqlKind.LOGICAL does not exist in Calcite 1.22.0 — replaced with explicit AND/OR/NOT individual kind checks in PushdownFunctionRegistry.isExpressionPushable()
- [Phase 37-01]: JdbcPushFilterIntoScan changed from singleton INSTANCE to constructor injection (takes PushdownFunctionRegistry) — INSTANCE singleton removed; instantiated in JdbcRulesFactory with StandardPushdownFunctionRegistry.INSTANCE
- [Phase 37-01]: COUNT(DISTINCT) now allowed in JdbcPushAggIntoScan — DISTINCT check changed from blanket rejection to kind check (only COUNT(DISTINCT) accepted); filterArg >= 0 (FILTER clause) always rejected
- [Phase 38-expression-pushdown P01]: JdbcPushJoinIntoScan INSTANCE singleton removed; replaced with constructor-injected PushdownFunctionRegistry in JdbcJoinRulesFactory
- [Phase 38-expression-pushdown P01]: extend/aggregate/trim pattern added to JdbcScanPrel.getPhysicalOperator() step 8a for GROUP BY and AGG operand function expressions (_group_key_N, _agg_operand_N columns)
- [Phase 38-expression-pushdown P01]: LOGICAL case in JdbcRulesFactory kept with only JdbcScanDrule — JOIN rule stays in JdbcJoinRulesFactory to avoid duplicate registration
- [Phase 38-02]: Oracle CAST(x AS VARCHAR) rejected — must use VARCHAR2(n) with explicit length; test uses VARCHAR2(50) for DEPT_NAME join condition
- [Phase 38-02]: Docker Engine 29+ / Testcontainers 1.20.4 API version incompatibility fixed via -Dapi.version=1.46 system property (shaded dockerjava defaults to 1.32, Docker 29 requires min 1.40)
- [Phase 38-03]: Gap 2 fix: leftProjectedCols/rightProjectedCols use scan row type when hasNonTrivialProject() detects intermediate CAST/function expressions — leftInputRowType/rightInputRowType stay as join input row type for conditionRex RexInputRef index alignment
- [Phase 38-03]: Regression tests execute direct JDBC SQL against Testcontainer (not via Dremio planner) — validates SQL correctness at plugin level; Docker UAT validates full planner pipeline separately
- [Phase 38-03]: testJoinWithCastAndWhereFilter is the definitive Gap 2 regression gate: CAST in ON condition combined with WHERE salary filter, passes on both PG (52 tests) and Oracle (48 tests)
- [Phase 39-01]: OracleContainer.withNetwork/withNetworkAliases return OracleContainer type (not subclass) — configure in static block after field construction, then assign to @ClassRule field
- [Phase 39-01]: ORACLE_PASSWORD in testcontainers gvenzl/oracle-xe == ORACLE.getPassword() (OracleContainer.configure() sets ORACLE_PASSWORD = password field) — do not hardcode "orapass"
- [Phase 39-01]: Oracle sqlplus V$SQL queries written to /tmp/*.sql via echo >> chains inside sh -c — avoids $ shell expansion of v$sql reference in execInContainer strings
- [Phase 39-01]: PG JDBC driver + ojdbc11 needed as test-scoped deps in jdbc-base/pom.xml for local seeding before Dremio source creation

### Pending Todos

None.

### Roadmap Evolution

- Phase 33 added: Advanced Query Pushdown Hardening
- Phase 34 added: adbc driver for (at least) postgres
- Phase 35 added: JOIN/UNION/INTERSECT/EXCEPT single-engine pushdown
- Phase 36 added: Calcite JDBC convention migration for SQL generation
- Phase 37 added: Expression pushdown for functions, HAVING, and ORDER BY expressions (pgvector foundation)
- Phase 38 added: Expression pushdown gap closure — AGG with expressions, JOIN with functions (PG + Oracle)
- Phase 39 added: Docker-based planner integration tests — full pushdown verification with ADBC

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-03-18T13:10:29Z
Stopped at: Completed 39-01-PLAN.md — Tier 2 Docker integration tests (35 tests, PG logs + Oracle V$SQL pushdown verification)
Resume file: None
