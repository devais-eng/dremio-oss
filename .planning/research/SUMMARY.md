# Project Research Summary

**Project:** Dremio OSS Enhancements — Enable Iceberg REST Catalog
**Domain:** Storage plugin wiring — connecting an existing Iceberg REST Catalog plugin to Dremio's source discovery and UI registration system
**Researched:** 2026-02-20
**Confidence:** HIGH (stack, architecture, pitfalls — all codebase-verified) / MEDIUM (Lakekeeper specifics)

## Executive Summary

This milestone is fundamentally a wiring task, not an implementation task. The Iceberg REST Catalog plugin (`plugins/icebergcatalog/`) is fully implemented: the read path, the write path, caching, namespace filtering, view support, and credential vending all exist and are tested. The single gap blocking discoverability is a missing `@SourceType` annotation on `RestIcebergCatalogPluginConfig`. Without this annotation, Dremio's classpath scanner (`ConnectionReaderImpl.makeReader()`) never registers the class, the source type never appears in the UI picker or REST API, and all plugin logic is permanently unreachable. Adding one annotation and one accompanying UI layout JSON file (`restcatalog-layout.json`) constitutes the entire code-change surface for v1.1.

The recommended approach is two sequential code changes followed by end-to-end validation against a live Lakekeeper instance. Step 1: add `@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")` to `RestIcebergCatalogPluginConfig`. Step 2: create `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` with a form exposing endpoint URI, namespace allowlist, catalog properties, and secret credentials. The icon, feature flag gate, classpath scan package declaration, and plugin lifecycle are all already in place and require zero changes.

The primary risk is not the wiring itself but the end-to-end data path: credential vending. When Lakekeeper returns table locations (e.g., `s3://bucket/path/`) and optionally vends short-lived storage credentials, those credentials must propagate correctly through `DremioFileIO` to enable actual Parquet reads. The code replaces `RESTCatalog`'s `ResolvingFileIO` with `DremioFileIO`, which may silently drop credentials. This is the highest-risk integration point and the core validation question for Phase 2. All other pitfalls (namespace separator mismatches, flat namespace depth violations, token expiry on catalog cache refresh) are configuration-level issues, not code bugs.

---

## Key Findings

### Recommended Stack

The stack for v1.1 requires zero new Maven dependencies and zero new Maven modules. The Iceberg Java SDK (`org.apache.iceberg:iceberg-core` 1.7.0, custom Dremio build), the `@SourceType` annotation infrastructure, and Protostuff serialization for config fields are all already on the classpath. The `plugins/icebergcatalog/` module is already included in `plugins/pom.xml` and declared as a runtime dependency in `dac/daemon/pom.xml`.

For end-to-end validation, Lakekeeper (`quay.io/iceberg-catalog/iceberg-catalog`) is the recommended test catalog server. It is a production-grade, spec-compliant open-source Iceberg REST Catalog and supports anonymous mode for zero-configuration local testing. No Lakekeeper-specific client library is needed — the plugin uses the standard `org.apache.iceberg.rest.RESTCatalog` directly, which speaks the Iceberg REST spec.

**Core technologies:**
- `@SourceType` annotation (`com.dremio.exec.catalog.conf.SourceType`): the single hook that makes the plugin discoverable — already imported in S3, GCS, Nessie, and Elasticsearch plugins; one annotation, zero new imports required
- `restcatalog-layout.json`: JSON resource file consumed by `SourceTypeTemplate.fromSourceClass()` to render the UI configuration form; follows the `nessie-layout.json` structural pattern
- Lakekeeper Docker image (`quay.io/iceberg-catalog/iceberg-catalog`): end-to-end validation target; endpoint `http://localhost:8181/catalog` in anonymous mode
- Existing `IcebergRestCatalogAccessor` + `ExpiringCatalogCache` + `AbstractRestCatalogAccessor`: the full catalog integration layer, already implemented and unit-tested

**What NOT to add:**
- No Lakekeeper client library (plugin uses standard Iceberg `RESTCatalog`)
- No WireMock or MockServer (existing Mockito-based unit tests cover the code changes adequately)
- No Testcontainers for v1.1 (manual Docker validation is sufficient; an IT test is a nice-to-have, not required)

See `.planning/research/STACK.md` for full detail including tag number reservation and Docker setup scripts.

### Expected Features

The feature scope for v1.1 is intentionally narrow. The implementation is already complete; the milestone delivers discoverability plus validation evidence.

**Must have (table stakes — v1.1 launch):**
- `@SourceType` annotation on `RestIcebergCatalogPluginConfig` — without it the source is completely invisible; the sole blocker for all other features
- UI layout JSON (`restcatalog-layout.json`) — exposes endpoint URI, namespace allowlist, catalog properties, and secret credentials in the configuration form
- Namespace browsing validation (already implemented) — confirmed against Lakekeeper `GET /v1/namespaces`
- Table listing per namespace validation (already implemented) — confirmed against Lakekeeper `GET /v1/namespaces/{ns}/tables`
- SELECT query execution validation (already implemented) — depends on credential vending propagating correctly through `DremioFileIO`
- Connection health check validation (already implemented) — `getState()` returns GOOD against live Lakekeeper

**Should have (differentiators — already implemented, expose and validate):**
- Namespace allowlist filtering — UI-exposed via layout JSON; limits visible namespaces in large catalogs
- View support — behind `RESTCATALOG_VIEWS_SUPPORTED` flag (default `true`); Lakekeeper supports views as of v0.8+
- Metadata caching — Caffeine table cache (3s–120s TTL) plus 30-minute `RESTCatalog` instance cache; tunable via system options
- Credential vending compatibility — if Lakekeeper vends storage credentials, `DremioFileIO` must use them correctly

**Defer (v1.2+):**
- Write operation validation (CREATE TABLE, INSERT, DROP TABLE) — full implementation exists behind `RESTCATALOG_PLUGIN_MUTABLE_ENABLED`; out of scope for read-only v1.1
- Namespace mutation validation (create/update/delete) — behind `RESTCATALOG_FOLDERS_SUPPORTED`; defer until write path is validated
- Structured auth type selector in UI — generic `propertyList`/`secretPropertyList` mechanism is correct for v1.1; a structured selector requires tracking which auth methods each server supports
- Planner-level optimizations for REST catalog specifics — future performance optimization, not a correctness requirement

See `.planning/research/FEATURES.md` for the full feature dependency map and prioritization matrix.

### Architecture Approach

Dremio's plugin architecture traverses five discrete layers from registration to query execution: source registration (classpath scanning via `@SourceType`), source visibility (feature flag gating in `DeprecatedSourceResource`), plugin lifecycle (`newPlugin()` factory wrapped by `ManagedStoragePlugin`), dataset resolution (`getDatasetHandle`, `listDatasetHandles`, `getDatasetMetadata`), and query execution (`FileSystemRulesFactory`, `ParquetScanTableFunction`). All five layers are fully wired for the REST catalog — only Layer 1 is blocked by the missing annotation.

**Major components:**
1. `RestIcebergCatalogPluginConfig` — user-facing config; gap: missing `@SourceType`; holds endpoint URI, namespace allowlist, and properties/secrets; `newPlugin()` factory already implemented
2. `RestIcebergCatalogPlugin` — concrete plugin; creates accessor, implements full DML path guarded by `MUTABLE_ENABLED`; no changes needed for v1.1
3. `IcebergRestCatalogAccessor` / `AbstractRestCatalogAccessor` — adapts `RESTCatalog` (Iceberg SDK) into Dremio's `CatalogAccessor` interface; handles namespace filtering, Caffeine caching, and path depth enforcement
4. `ExpiringCatalogCache` — caches the `RESTCatalog` instance for 30 minutes (configurable); hardcodes `instanceof RESTCatalog` check — no custom catalog subclasses permitted
5. `ConnectionReaderImpl` — classpath scanner that discovers `@SourceType` classes; `DeprecatedSourceResource` already handles the `"RESTCATALOG"` visibility gate at lines 231-232

**Key architectural constraint — two placement rules:** The `@SourceType` annotation must go on the concrete config class (`RestIcebergCatalogPluginConfig`), not the abstract base (`IcebergCatalogPluginConfig`), because the scanner explicitly skips abstract classes. The layout JSON must be placed in the plugin's own `src/main/resources/` directory because `SourceTypeTemplate.fromSourceClass()` uses the source class's own classloader to load it.

See `.planning/research/ARCHITECTURE.md` for the full 5-layer flow diagram, data flow diagrams, and build order.

### Critical Pitfalls

1. **Missing `@SourceType` causes silent non-discovery** — the source type does not appear anywhere; no error, no warning, no log entry. Prevention: add the annotation to `RestIcebergCatalogPluginConfig` (concrete class only). Verification: `GET /api/v3/catalog/source/type/RESTCATALOG` returns HTTP 200.

2. **`restcatalog-layout.json` absent from plugin classpath causes silent blank UI form** — `SourceTypeTemplate` logs a `WARN` and returns `null` for `uiConfig`; the UI form renders empty with no fields. Prevention: create the file in `plugins/icebergcatalog/src/main/resources/`. Verification: `GET /api/v3/catalog/source/type/RESTCATALOG` returns non-null `uiConfig` JSON.

3. **Credential vending gap — `DremioFileIO` may drop credentials from `loadTable()` response** — the code replaces `RESTCatalog`'s `ResolvingFileIO` with `DremioFileIO` in `getTableHandleInternal()`. If Lakekeeper vends short-lived S3/Azure/GCS credentials in the `loadTable()` response, those credentials must reach `DremioFileIO`. If dropped, SELECT queries against credential-vended storage produce permission errors. Prevention: trace the credential propagation path during Phase 2 validation; fix if needed.

4. **Namespace separator mismatch silently filters out all namespaces** — `allowedNamespaces` entries are split by `RESTCATALOG_ALLOWED_NS_SEPARATOR` (default regex `"\."`). A namespace named `"my.db"` (dot in name) gets split into a two-level namespace `["my", "db"]` — not the intended single-level namespace. Prevention: use namespace names without dots, or change the separator option to a character that doesn't appear in namespace names.

5. **OAuth2 token expiry causes plugin to flip to BAD state every 30 minutes** — `ExpiringCatalogCache` re-creates the `RESTCatalog` instance after 30 minutes using the same static properties; if a static bearer token has expired, re-creation receives a 401 and the plugin enters BAD state. Prevention: use OAuth2 client credentials flow (`rest.auth.type = oauth2`) so `RESTCatalog` handles token refresh internally, or use long-lived tokens for validation testing.

See `.planning/research/PITFALLS.md` for 9 pitfalls total, each with recovery steps, warning signs, and a pitfall-to-phase mapping table.

---

## Implications for Roadmap

Research strongly supports a two-phase structure. Phase 1 is minimal code changes (2 artifacts, approximately 50–80 lines of JSON, one annotation line). Phase 2 is pure validation effort — no new code expected unless credential vending is broken.

### Phase 1: Plugin Wiring (Registration and UI)

**Rationale:** The annotation is the prerequisite for everything. No Lakekeeper connection can be attempted until the source type is registered. Both deliverables are pure Dremio-internal changes verified with high confidence from codebase analysis — no external system required to complete or test this phase.

**Delivers:** A source type visible in the UI picker that renders a configuration form, accepts a REST endpoint URI and credentials, and creates a source that reaches GOOD state on a successful Lakekeeper connection. Feature flag gating confirmed working. Integration test verifying "RESTCATALOG" appears in `ConnectionReaderImpl.getAllConnectionConfs()`.

**Addresses:** Table stakes 1.1 (annotation) and 1.2 (layout JSON) from FEATURES.md; Patterns 1 and 2 from ARCHITECTURE.md.

**Avoids:**
- Pitfall 1 (missing `@SourceType`) — resolved by the annotation
- Pitfall 2 (missing layout JSON) — resolved by creating the file
- Pitfall 5 (ExpiringCatalogCache `instanceof RESTCatalog` check) — confirmed by source reaching GOOD state on first connection
- Anti-pattern: annotation on abstract base class — annotation goes on `RestIcebergCatalogPluginConfig` only
- Anti-pattern: layout JSON in wrong module — file goes in `plugins/icebergcatalog/src/main/resources/`

**Code changes:** 2 files, no new dependencies, no new Maven modules.

### Phase 2: End-to-End Validation (Lakekeeper)

**Rationale:** The plugin reads and writes data; wiring without validation provides no confidence that the data path actually works. Credential vending is the highest-risk integration point and cannot be assessed without a live Lakekeeper instance. This phase is inherently sequential after Phase 1.

**Delivers:** Validated evidence that namespace browsing, table listing, and SELECT queries work against a real Iceberg REST catalog. Documented behavior for credential vending, feature flag defaults, mutable operation gating, and cache TTL configuration. A "looks done but isn't" checklist verified against all 8 items in PITFALLS.md.

**Validates (existing code — no new changes expected unless credential vending is broken):**
- Table stakes 1.3–1.7 from FEATURES.md: namespace browsing, table listing, table metadata loading, SELECT execution, health check
- Credential vending (2.4 from FEATURES.md): may require a targeted fix to `DremioFileIO` credential propagation in `getTableHandleInternal()` if SELECT queries fail with permission errors

**Security caveat:** `hasAccessPermission()` is a `// TODO: implement RBAC` no-op in `IcebergCatalogPlugin` — all Dremio users can see all tables in any Iceberg REST Catalog source regardless of Dremio RBAC grants. Document as a known v1.1 limitation; enforce access at the Lakekeeper level using Lakekeeper's native authorization for now.

**Avoids:**
- Pitfall 4 (namespace separator): test with non-dotted namespace names
- Pitfall 6 (Lakekeeper auth via catalog properties): always use `secretPropertyList` for credentials; never `propertyList`
- Pitfall 7 (dataset depth constraint): create all test tables in at least one namespace, never at root
- Pitfall 8 (table cache TTL): set `plugins.restcatalog.table_cache.expire_after_write_seconds = 3` before starting validation tests
- Pitfall 9 (OAuth2 token expiry): verify source state is still GOOD more than 30 minutes after creation

### Phase Ordering Rationale

- Phase 1 must come first: the annotation is the prerequisite for all source creation; without it no Lakekeeper connection can be attempted from the UI or API.
- Phase 2 must follow Phase 1: validation requires a working source type registration.
- No Phase 3 for v1.1: write operations (CREATE TABLE, INSERT, DROP TABLE) and namespace mutations are explicitly out of scope. The existing code must not be deleted, but these paths are not validated in this milestone.

### Research Flags

**Phases likely needing deeper investigation during execution:**

- **Phase 2 — credential vending path:** The exact code path from `loadTable()` response through `IcebergCatalogTableProvider.getFileConfig()` to `DatasetFileSystemCache` initialization was not fully traced during research. If SELECT queries against credential-vended Lakekeeper storage fail with permission errors, the investigation starting point is `AbstractRestCatalogAccessor.getTableHandleInternal()` where `ResolvingFileIO` is replaced with `DremioFileIO`. This may require a targeted code fix — estimated MEDIUM complexity.

- **Phase 2 — Lakekeeper Docker setup:** The exact Docker image tag and environment variable names for anonymous mode are MEDIUM confidence (WebFetch was unavailable during research). Verify the current release at `https://quay.io/repository/iceberg-catalog/iceberg-catalog` before writing the Phase 2 test plan.

**Phases with standard patterns (no additional research needed):**

- **Phase 1 (wiring):** All mechanisms verified directly from codebase with HIGH confidence. Reference implementations are Nessie (`nessie-layout.json`, `NessiePluginConfig`), S3, and Elasticsearch plugins. The pattern is copy-and-adapt.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All technologies verified from codebase; zero new dependencies confirmed from `pom.xml` and import analysis |
| Features | HIGH (code) / MEDIUM (Lakekeeper) | All read path features verified from source files; Lakekeeper spec compliance derived from training knowledge (WebFetch unavailable during research) |
| Architecture | HIGH | All 5 layers verified directly from source files; `ConnectionReaderImpl`, `DeprecatedSourceResource`, `SourceTypeTemplate`, and `ExpiringCatalogCache` all examined |
| Pitfalls | HIGH | All 9 pitfalls derived from codebase — Preconditions checks, instanceof assertions, cache TTL defaults, property merging logic, and path depth constraint all read from source code |

**Overall confidence:** HIGH for Phase 1 (pure Dremio internals, fully verified). MEDIUM for Phase 2 (depends on Lakekeeper runtime behavior and credential vending path not exhaustively traced through `DremioFileIO`).

### Gaps to Address

- **Credential vending path trace:** Whether `IcebergCatalogTableProvider.getFileConfig()` propagates credentials from the Iceberg `LoadTableResponse` into the Hadoop `Configuration` used by `DatasetFileSystemCache` was not confirmed during research. Must be verified during Phase 2 execution. If SELECT queries fail with permission errors on credential-vended storage, this is the first place to look.

- **Lakekeeper Docker image tag:** The exact current release tag needs verification at `quay.io/repository/iceberg-catalog/iceberg-catalog` before the Phase 2 test plan is written. Research used `latest` as a placeholder.

- **`IcebergRestCatalogAccessor` deprecation path:** The class is marked `@Deprecated` internally but is the active implementation. The planned replacement class was not identified during research. Acceptable for v1.1; track as a future cleanup item.

- **`hasAccessPermission()` TODO:** The no-op RBAC implementation means all Dremio users have full read access to all Iceberg REST Catalog tables. Documented limitation for v1.1; must be tracked for a future milestone.

---

## Sources

### Primary (HIGH confidence — direct codebase analysis)

- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` — confirms `@SourceType` is absent; full field and tag inventory
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` — full DML implementation, auth property merging, catalog creation
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/IcebergCatalogPlugin.java` — lifecycle, `validateOnStart`, `hasAccessPermission` TODO, scan table function wiring
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/AbstractRestCatalogAccessor.java` — namespace filtering, table cache, path depth constraint
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/ExpiringCatalogCache.java` — `RESTCatalog instanceof` check, catalog expiry behavior
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/ConnectionReaderImpl.java` — classpath scanning mechanism, abstract class exclusion
- `sabot/kernel/src/main/java/com/dremio/exec/store/IcebergCatalogPluginOptions.java` — all feature flag defaults (all `true`)
- `dac/backend/src/main/java/com/dremio/dac/api/DeprecatedSourceResource.java` — `"RESTCATALOG"` visibility gate confirmed at lines 231-232
- `dac/backend/src/main/java/com/dremio/dac/api/SourceTypeTemplate.java` — icon and layout JSON loading mechanism via source class classloader
- `dac/ui-lib/icons/dremio/sources/RESTCATALOG.svg` — icon already exists in both light and dark variants
- `plugins/icebergcatalog/src/main/resources/sabot-module.conf` — `com.dremio.plugins.icebergcatalog` package already registered for scanning
- `plugins/dataplane/src/main/resources/nessie-layout.json` — reference UI layout structure

### Secondary (MEDIUM confidence — training knowledge and spec inference)

- Apache Iceberg REST Catalog specification — credential vending behavior, namespace operations, `loadTable()` response shape
- Lakekeeper project reputation as spec-compliant REST catalog — namespace, table, and view support claims
- `quay.io/repository/iceberg-catalog/iceberg-catalog` — Docker image (exact current tag needs runtime verification)

---

*Research completed: 2026-02-20*
*Ready for roadmap: yes*
