---
gsd_state_version: 1.0
milestone: v1.5
milestone_name: Open-Source RDBMS JDBC Plugin
status: complete
stopped_at: Completed 35-03-PLAN.md
last_updated: "2026-03-14T21:35:00Z"
last_activity: "2026-03-14 — Completed Phase 35 Plan 03 (JOIN pushdown integration tests: TestPostgresJoinPushdown 13 tests, TestOracleJoinPushdown 5 tests, Docker UAT test-uat-35.sh 18 tests; OracleSqlBuilder AS alias fix)"
progress:
  total_phases: 5
  completed_phases: 5
  total_plans: 15
  completed_plans: 15
  percent: 100
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-12)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** Phase 35 — JOIN/INTERSECT/EXCEPT single-engine pushdown

## Current Position

Phase: 35 of 35 (JOIN/INTERSECT/EXCEPT single-engine pushdown)
Plan: 3 of 3 complete (35-03 JOIN pushdown integration tests + Docker UAT script)
Status: COMPLETE — All phases and plans done
Last activity: 2026-03-14 — Completed 35-03 (TestPostgresJoinPushdown 13 tests, TestOracleJoinPushdown 5 tests, test-uat-35.sh Docker UAT 18 tests, OracleSqlBuilder AS alias fix)

Progress: [██████████] 100% (15 of 15 plans complete)

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

*Updated after each plan completion*

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

### Pending Todos

None.

### Roadmap Evolution

- Phase 33 added: Advanced Query Pushdown Hardening
- Phase 34 added: adbc driver for (at least) postgres
- Phase 35 added: JOIN/UNION/INTERSECT/EXCEPT single-engine pushdown

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-03-14
Stopped at: Completed 35-03-PLAN.md — JOIN pushdown integration tests (TestPostgresJoinPushdown, TestOracleJoinPushdown, test-uat-35.sh Docker UAT, OracleSqlBuilder AS alias fix)
Resume file: None
