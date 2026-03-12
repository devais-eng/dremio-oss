---
gsd_state_version: 1.0
milestone: v1.5
milestone_name: Open-Source RDBMS JDBC Plugin
status: in_progress
stopped_at: Completed 31-01-PLAN.md
last_updated: "2026-03-12T23:53:25Z"
last_activity: "2026-03-12 — Completed Phase 31 Plan 01 (PostgreSQL connector source code)"
progress:
  total_phases: 3
  completed_phases: 1
  total_plans: 6
  completed_plans: 4
  percent: 67
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-12)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.5 Phase 31 — PostgreSQL Connector

## Current Position

Phase: 31 of 32 (PostgreSQL Connector) — IN PROGRESS
Plan: 1 of 2+ complete (31-01 done; 31-02 integration tests next)
Status: In Progress
Last activity: 2026-03-12 — Completed 31-01 (PostgreSQL connector source code + base class hooks)

Progress: [██████░░░░] 67%

## Performance Metrics

**Velocity:**
- Total plans completed: 4 (v1.5)
- Average duration: 12.3 min
- Total execution time: 0.82 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 30-base-jdbc-framework P01 | 1 | 9 min | 9 min |
| 30-base-jdbc-framework P02 | 1 | 10 min | 10 min |
| 30-base-jdbc-framework P03 | 1 | 10 min | 10 min |
| 31-postgresql-connector P01 | 1 | 21 min | 21 min |

**Recent Trend:**
- Last 5 plans: 12.5 min avg
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

### Pending Todos

- [Hardening] Refactor WHERE pushdown to use PreparedStatement bind parameters (`?`) instead of string-escaped literals — eliminates SQL injection risk structurally rather than relying on correct escaping in RexToSqlString
- [Pushdown] Add IN operator support to RexToSqlString — check whether Calcite produces SqlKind.IN or rewrites to OR chains first; if IN nodes reach pushdown rules, handle them with per-value escaping via existing convertLiteral()

### Blockers/Concerns

None yet.

## Session Continuity

Last session: 2026-03-12
Stopped at: Completed 31-01-PLAN.md — PostgreSQL connector source code, base class hooks, module wiring
Resume file: None
