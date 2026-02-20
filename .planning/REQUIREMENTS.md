# Requirements: Dremio OSS Enhancements

**Defined:** 2026-02-20
**Core Value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control and catalog connectivity.

## v1.1 Requirements

Requirements for enabling Iceberg REST Catalog. Each maps to roadmap phases.

### Plugin Wiring

- [ ] **WIRE-01**: Iceberg REST Catalog source type is discoverable by ConnectionReader via `@SourceType` annotation
- [ ] **WIRE-02**: Source creation form renders in Dremio UI with endpoint URI, catalog properties, and credential fields via `restcatalog-layout.json`

### Read Operations

- [ ] **READ-01**: User can browse namespaces in an Iceberg REST Catalog source
- [ ] **READ-02**: User can list tables within a namespace
- [ ] **READ-03**: User can SELECT from an Iceberg table via SQL and get query results

### Connectivity

- [ ] **CONN-01**: User can create an Iceberg REST Catalog source pointing to a Lakekeeper endpoint
- [ ] **CONN-02**: User can authenticate to the REST catalog using OAuth2/bearer token via catalog properties
- [ ] **CONN-03**: Storage credential vending from Lakekeeper propagates correctly through DremioFileIO for Parquet reads

## Future Requirements

Deferred to future release. Tracked but not in current roadmap.

### Write Operations

- **WRITE-01**: User can create tables in the Iceberg REST Catalog
- **WRITE-02**: User can insert data into Iceberg tables
- **WRITE-03**: User can create/modify views in the Iceberg REST Catalog
- **WRITE-04**: User can create/modify namespaces

### RBAC Integration

- **RBAC-01**: RBAC grants control access to Iceberg REST Catalog tables/views
- **RBAC-02**: hasAccessPermission() enforces real RBAC checks (currently no-op)

### Advanced Features

- **ADV-01**: View support validation (behind feature flag)
- **ADV-02**: Metadata caching tuning and validation
- **ADV-03**: Table rollback operations
- **ADV-04**: Partition spec and sort order support

## Out of Scope

| Feature | Reason |
|---------|--------|
| Write operations | v1.1 is read-only validation; write flags exist but are untested |
| RBAC for REST catalog | v1.0 RBAC covers VDS/UDF only; REST catalog integration deferred |
| Custom FileIO implementations | DremioFileIO is the existing pattern; no custom IO needed |
| Multiple REST catalog servers | One Lakekeeper instance is sufficient for validation |
| UI/UX polish | Functional form is sufficient; no custom React components |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| WIRE-01 | Phase 7 | Pending |
| WIRE-02 | Phase 7 | Pending |
| READ-01 | Phase 8 | Pending |
| READ-02 | Phase 8 | Pending |
| READ-03 | Phase 8 | Pending |
| CONN-01 | Phase 8 | Pending |
| CONN-02 | Phase 8 | Pending |
| CONN-03 | Phase 8 | Pending |

**Coverage:**
- v1.1 requirements: 8 total
- Mapped to phases: 8
- Unmapped: 0

---
*Requirements defined: 2026-02-20*
*Last updated: 2026-02-20 — traceability filled after roadmap creation*
