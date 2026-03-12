# Phase 24: Multi-Branch Queries and Hardening - Context

**Gathered:** 2026-03-10
**Status:** Ready for planning

<domain>
## Phase Boundary

Cross-branch SQL queries (JOINs, UNIONs, subqueries with different AT BRANCH clauses) and branch-related error handling for Nessie-enabled RESTCATALOG sources. AT BRANCH only — AT TAG and AT COMMIT are deferred.

</domain>

<decisions>
## Implementation Decisions

### Cross-branch query patterns
- All multi-table SQL patterns must work across branches: JOINs, UNION ALL, subqueries with different AT BRANCH clauses
- Same table from two different branches in one query (e.g., `src.ns.tbl AT BRANCH "main" JOIN src.ns.tbl AT BRANCH "dev"`) is an explicit test case — verify no alias collision or caching interference
- Mixed-source queries must be validated: JOIN between a Nessie-enabled RESTCATALOG source (with AT BRANCH) and a plain non-versioned source
- AT BRANCH only — AT TAG and AT COMMIT are out of scope for this phase

### Error message design
- Mimic native Nessie error patterns but improve table-not-found messages to include branch context
- Branch not found: follow Nessie pattern — "Requested Branch 'X' not found in source 'Y'"
- Table not found on branch: improve beyond native Nessie — "Table 'X' not found on branch 'Y' in source 'Z'" (native Nessie only says "Table 'X' not found")
- Always include source name in error messages for both branch-not-found and table-not-found-on-branch
- Reuse existing `ReferenceNotFoundException` exception type for branch-not-found errors (same as native Nessie)
- enableNessie=true but detection failed: generic "doesn't support AT BRANCH/TAG/COMMIT" error is sufficient (warning already logged at startup)

### Edge case handling
- Schema mismatch across branches (same table, different columns): let it fail naturally through Dremio's standard type mismatch / column-not-found errors — no special handling
- Branch deleted mid-query: no special handling, let REST API error propagate naturally
- Special characters in branch names: explicitly test common Git patterns — feature/my-branch (slash), release-1.0 (dot), names with spaces
- enableNessie=false regression: explicit regression test to confirm zero behavioral change after Phase 24 changes

### Testing strategy
- Both layers: unit tests for dispatch/error logic + integration tests against real Nessie for end-to-end validation
- Integration tests extend existing TestRestIcebergCatalogPlugin (already has Nessie/REST catalog infrastructure)
- Test data setup via Iceberg REST API with branch-prefixed URIs, with optional Spark-based writes for specific tests (no mandatory Spark dependency)
- Nessie server via testcontainers (self-contained Docker-based)

### Claude's Discretion
- Detection approach for branch-not-found vs table-not-found-on-branch (HTTP status parsing vs probing branch existence first)
- Exact testcontainers configuration and lifecycle management
- How to structure optional Spark-based test data setup without mandatory dependency

</decisions>

<specifics>
## Specific Ideas

- Error messages should match native Nessie patterns from UseVersionHandler: "Requested Branch 'X' not found in source 'Y'" — consistent UX across both source types
- The improvement over native Nessie is specifically in table-not-found: adding branch context that native Nessie lacks
- Nessie detection relies on `nessie.is-nessie-catalog=true` from the Iceberg REST `/v1/config` endpoint — purely property-based, no Nessie-specific API calls

</specifics>

<deferred>
## Deferred Ideas

- AT TAG support — similar URI prefix approach, could be a follow-up phase
- AT COMMIT support — known concern about whether Nessie accepts commit hashes in REST URI prefix
- Branch-specific error for enableNessie=true + failed detection — currently generic "doesn't support versioning" is sufficient

</deferred>

---

*Phase: 24-multi-branch-queries-and-hardening*
*Context gathered: 2026-03-10*