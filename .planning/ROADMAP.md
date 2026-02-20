# Roadmap: Dremio OSS Enhancements

## Milestones

- ✅ **v1.0 Naive RBAC** — Phases 1-6 (shipped 2026-02-19)
- 🚧 **v1.1 Enable Iceberg REST Catalog** — Phases 7-8 (in progress)

## Phases

<details>
<summary>✅ v1.0 Naive RBAC (Phases 1-6) — SHIPPED 2026-02-19</summary>

- [x] Phase 1: Design and Proto Schema (2/2 plans) — completed 2026-02-17
- [x] Phase 2: Persistence Layer (2/2 plans) — completed 2026-02-17
- [x] Phase 3: Service Layer (2/2 plans) — completed 2026-02-17
- [x] Phase 4: Catalog Enforcement and DI Wiring (3/3 plans) — completed 2026-02-18
- [x] Phase 5: DDL Handlers and System Tables (3/3 plans) — completed 2026-02-18
- [x] Phase 6: REST API and Access Path Hardening (3/3 plans) — completed 2026-02-18

See `milestones/v1.0-ROADMAP.md` for full phase details.

</details>

### 🚧 v1.1 Enable Iceberg REST Catalog (In Progress)

**Milestone Goal:** Wire the existing Iceberg REST Catalog plugin into Dremio's source discovery system and validate read-only operations against a live Lakekeeper instance.

- [x] **Phase 7: Plugin Wiring** - Add `@SourceType` annotation and UI layout JSON to make the REST catalog source type discoverable and configurable (completed 2026-02-20)
- [ ] **Phase 8: End-to-End Validation** - Validate namespace browsing, table listing, SELECT queries, and credential vending against a live Lakekeeper instance

## Phase Details

### Phase 7: Plugin Wiring
**Goal**: The Iceberg REST Catalog source type is discoverable by Dremio and presents a usable configuration form in the UI
**Depends on**: Nothing (v1.1 starting phase; all plugin code already exists)
**Requirements**: WIRE-01, WIRE-02
**Success Criteria** (what must be TRUE):
  1. `GET /api/v3/catalog/source/type/RESTCATALOG` returns HTTP 200 with a non-null source type descriptor
  2. The Dremio UI source picker displays "Iceberg REST Catalog" as a creatable source type with the RESTCATALOG.svg icon
  3. The source creation form renders fields for endpoint URI, namespace allowlist, catalog properties, and secret credentials — no blank form
  4. A source created pointing to a valid Lakekeeper endpoint reaches GOOD health state (plugin lifecycle completes without error)
**Plans:** 1/1 plans complete
Plans:
- [ ] 07-01-PLAN.md — Add @SourceType annotation, create restcatalog-layout.json, copy RESTCATALOG.svg to plugin resources

### Phase 8: End-to-End Validation
**Goal**: Read-only operations against a live Lakekeeper Iceberg REST Catalog work correctly — namespaces browse, tables list, and SELECT queries return results
**Depends on**: Phase 7
**Requirements**: READ-01, READ-02, READ-03, CONN-01, CONN-02, CONN-03
**Success Criteria** (what must be TRUE):
  1. User can create a source pointing to a local Lakekeeper Docker instance (`http://localhost:8181/catalog`) and the source reaches GOOD state
  2. User can browse namespaces in the Dremio UI tree for the Iceberg REST Catalog source (Lakekeeper `GET /v1/namespaces` response reflected)
  3. User can list tables within a namespace in the Dremio UI tree (Lakekeeper `GET /v1/namespaces/{ns}/tables` response reflected)
  4. `SELECT * FROM restcatalog.namespace.tablename LIMIT 10` executes and returns rows from an Iceberg table backed by Parquet files
  5. A source authenticated via OAuth2 bearer token (set via `rest.token` catalog property) successfully connects — source reaches GOOD state and all read operations work
  6. Storage credentials vended by Lakekeeper in `loadTable()` responses propagate through DremioFileIO — Parquet reads succeed without storage permission errors
**Plans:** 2 plans
Plans:
- [ ] 08-01-PLAN.md — Rebuild plugin JAR, stand up Lakekeeper Docker stack, seed test data, start Dremio
- [ ] 08-02-PLAN.md — Validate source creation, namespace browsing, table listing, SELECT queries, OAuth2 auth, and credential vending

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Design and Proto Schema | v1.0 | 2/2 | Complete | 2026-02-17 |
| 2. Persistence Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 3. Service Layer | v1.0 | 2/2 | Complete | 2026-02-17 |
| 4. Catalog Enforcement and DI Wiring | v1.0 | 3/3 | Complete | 2026-02-18 |
| 5. DDL Handlers and System Tables | v1.0 | 3/3 | Complete | 2026-02-18 |
| 6. REST API and Access Path Hardening | v1.0 | 3/3 | Complete | 2026-02-18 |
| 7. Plugin Wiring | v1.1 | Complete    | 2026-02-20 | - |
| 8. End-to-End Validation | v1.1 | 0/? | Not started | - |
