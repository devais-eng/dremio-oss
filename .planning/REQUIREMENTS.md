# Requirements: Dremio OSS Enhancements

**Defined:** 2026-03-09
**Core Value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.

## v1.4 Requirements

Requirements for Nessie Branch-Aware REST Catalog milestone. Each maps to roadmap phases.

### Configuration

- [ ] **CFG-01**: User can enable Nessie mode via `enableNessie` boolean on RESTCATALOG source config (default false)
- [ ] **CFG-02**: Plugin auto-detects Nessie backend from config endpoint response (`nessie.is-nessie-catalog=true`)
- [ ] **CFG-03**: Plugin auto-discovers default branch from Nessie server config (`nessie.default-branch.name`)

### Branch Queries

- [ ] **BRQ-01**: User can SELECT from a table AT BRANCH `<name>` on a Nessie-enabled RESTCATALOG source
- [ ] **BRQ-02**: Queries without explicit AT BRANCH use the server-defined default branch (always fresh, never cached across queries)
- [ ] **BRQ-03**: User can JOIN tables from different branches in a single query

### Infrastructure

- [ ] **INF-01**: Per-branch RESTCatalog instances are cached with bounded size and TTL eviction (no connection pool exhaustion)
- [ ] **INF-02**: Table cache is isolated per branch (no cross-branch cache poisoning — same table path on different branches returns correct data)
- [ ] **INF-03**: Plan cache correctly handles branch-aware queries (no stale cross-branch plan reuse)

### Compatibility

- [ ] **CMP-01**: Non-Nessie RESTCATALOG sources are completely unaffected (zero behavioral change when `enableNessie=false`)
- [ ] **CMP-02**: Nessie-enabled source without AT BRANCH behaves identically to current non-Nessie source (default branch serves same data)

## Future Requirements

Deferred to future milestones. Tracked but not in current roadmap.

### Version Control Queries

- **BRQ-04**: User can SELECT from a table AT TAG `<name>`
- **BRQ-05**: User can SELECT from a table AT COMMIT `<hash>`
- **BRQ-06**: User can set session-level branch context with USE BRANCH `<name>`

### Discovery

- **DSC-01**: User can run SHOW BRANCHES to list all Nessie branches
- **DSC-02**: User can run SHOW TAGS to list all Nessie tags
- **DSC-03**: User can run SHOW LOGS to view Nessie commit history

### Branch Management

- **MGT-01**: User can CREATE BRANCH via SQL
- **MGT-02**: User can DROP BRANCH via SQL
- **MGT-03**: User can MERGE BRANCH via SQL

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| DML on branches (INSERT/UPDATE/DELETE AT BRANCH) | Read-only milestone; write operations are future scope |
| Views on branches (CREATE VDS AT BRANCH) | Adds complexity with definer rights interaction; not needed for data retrieval |
| Branch/tag management (CREATE/DROP/MERGE BRANCH) | Focus is on querying, not catalog management |
| Combined Nessie versioning + Iceberg time travel | Ambiguous semantics; needs design before implementation |
| Automatic branch sync/push/pull | Server-side concern, not a query engine feature |
| Per-branch RBAC (grant access to specific branches) | Delegated to Nessie server-side authorization; Dremio grants are path-based |
| Namespace browsing per-branch in UI | UI always shows default branch; branch switching via SQL only |
| `isVersioned=true` on RESTCATALOG source annotation | Must remain false; use runtime `isWrapperFor()` instead to avoid breaking non-Nessie sources |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CFG-01 | Phase 21 | Pending |
| CFG-02 | Phase 21 | Pending |
| CFG-03 | Phase 21 | Pending |
| BRQ-01 | Phase 23 | Pending |
| BRQ-02 | Phase 23 | Pending |
| BRQ-03 | Phase 24 | Pending |
| INF-01 | Phase 22 | Pending |
| INF-02 | Phase 22 | Pending |
| INF-03 | Phase 23 | Pending |
| CMP-01 | Phase 21 | Pending |
| CMP-02 | Phase 23 | Pending |

**Coverage:**
- v1.4 requirements: 11 total
- Mapped to phases: 11
- Unmapped: 0

---
*Requirements defined: 2026-03-09*
*Last updated: 2026-03-09 after roadmap creation*
