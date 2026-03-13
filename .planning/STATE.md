---
gsd_state_version: 1.0
milestone: v1.5
milestone_name: Open-Source RDBMS JDBC Plugin
status: in_progress
stopped_at: Completed 33-02-PLAN.md
last_updated: "2026-03-13T15:34:26Z"
last_activity: "2026-03-13 — Completed Phase 33 Plan 02 (ORDER BY and TopN pushdown)"
progress:
  total_phases: 4
  completed_phases: 1
  total_plans: 9
  completed_plans: 8
  percent: 89
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-12)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.5 Phase 33 — Advanced Query Pushdown Hardening

## Current Position

Phase: 33 of 33 (Advanced Query Pushdown Hardening)
Plan: 2 of 3 complete (33-02 ORDER BY and TopN pushdown)
Status: In Progress
Last activity: 2026-03-13 — Completed 33-02 (ORDER BY pushdown rule, TopN via sequential sort-then-limit, PG/Oracle integration tests)

Progress: [████████░░] 89% (8 of 9 plans complete)

## Performance Metrics

**Velocity:**
- Total plans completed: 8 (v1.5)
- Average duration: 13.5 min
- Total execution time: 1.80 hours

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

**Recent Trend:**
- Last 5 plans: 13.6 min avg
- Trend: stable

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

### Pending Todos

None — ORDER BY and TopN pushdown completed in Phase 33-02.

### Roadmap Evolution

- Phase 33 added: Advanced Query Pushdown Hardening

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-03-13
Stopped at: Completed 33-02-PLAN.md — ORDER BY pushdown rule, TopN via sequential sort-then-limit, PG/Oracle integration tests
Resume file: None
