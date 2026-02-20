---
phase: 07-plugin-wiring
plan: 01
subsystem: infra
tags: [dremio, iceberg, rest-catalog, plugin-wiring, source-type, annotation, ui-layout]

# Dependency graph
requires:
  - phase: milestones/enable_iceberg_rest_catalog
    provides: "RestIcebergCatalogPluginConfig and IcebergCatalogPluginConfig with all 8 config fields already implemented"
provides:
  - "@SourceType annotation on RestIcebergCatalogPluginConfig making RESTCATALOG discoverable via ConnectionReaderImpl classpath scan"
  - "restcatalog-layout.json at classpath root wiring all 8 config fields across 3 UI tabs"
  - "RESTCATALOG.svg at classpath root ensuring non-null API icon field"
affects: [07-plugin-wiring, 08-validation]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@SourceType annotation on concrete ConnectionConf subclass for plugin discovery"
    - "restcatalog-layout.json UI form descriptor loaded via classloader at classpath root"
    - "SVG icon at classpath root for non-null API source type descriptor icon field"

key-files:
  created:
    - plugins/icebergcatalog/src/main/resources/restcatalog-layout.json
    - plugins/icebergcatalog/src/main/resources/RESTCATALOG.svg
  modified:
    - plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java

key-decisions:
  - "@SourceType placed on concrete RestIcebergCatalogPluginConfig (not abstract IcebergCatalogPluginConfig) — scanner skips abstract classes"
  - "isVersioned not set (defaults false) — REST catalog is not a versioned catalog in Dremio's sense"
  - "metadataRefresh.datasetDiscovery=false — catalog-based source, not filesystem-based"
  - "allowedNamespaces[] uses value_list uiType with [] suffix; propertyList/secretPropertyList use property_list without [] suffix"

patterns-established:
  - "Pattern: all propNames in layout JSON use config. prefix matching Jackson serialization path"
  - "Pattern: List<Property> fields are auto-detected as property_list by SourceTypeTemplate; @Secret on Java field drives secret masking"
  - "Pattern: Advanced Options tab uses checkboxController to conditionally show/hide Cache Options section"

requirements-completed: [WIRE-01, WIRE-02]

# Metrics
duration: 2min
completed: 2026-02-20
---

# Phase 7 Plan 01: Plugin Wiring Summary

**@SourceType annotation + restcatalog-layout.json wiring RESTCATALOG into Dremio's classpath scanner and UI form renderer with 8 config fields across 3 tabs**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-20T08:20:34Z
- **Completed:** 2026-02-20T08:21:55Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Added `@SourceType(value="RESTCATALOG", label="Iceberg REST Catalog", uiConfig="restcatalog-layout.json")` to `RestIcebergCatalogPluginConfig` — plugin is now discoverable by `ConnectionReaderImpl.getCandidateSources()` via classpath scan
- Created `restcatalog-layout.json` at classpath root with 3-tab form covering all 8 config fields: General (endpoint URI, namespace filter), Catalog Properties (propertyList, secretPropertyList), Advanced Options (enableAsync, caching)
- Copied `RESTCATALOG.svg` from `dac/ui-lib/icons/dremio/sources/` to plugin classpath root — API source type descriptor now returns non-null icon field

## Task Commits

Each task was committed atomically:

1. **Task 1: Add @SourceType annotation and create layout JSON** - `37b035f80` (feat)
2. **Task 2: Copy RESTCATALOG.svg to plugin resources** - `0b319652d` (feat)

**Plan metadata:** `[pending]` (docs: complete plan)

## Files Created/Modified
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` - Added `@SourceType` annotation and `import com.dremio.exec.catalog.conf.SourceType`
- `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` - New: UI form descriptor with 3 tabs and all 8 config fields
- `plugins/icebergcatalog/src/main/resources/RESTCATALOG.svg` - New: Icon copied from `dac/ui-lib/icons/dremio/sources/RESTCATALOG.svg`

## Decisions Made
- `@SourceType` placed on concrete `RestIcebergCatalogPluginConfig`, not abstract `IcebergCatalogPluginConfig` — `ConnectionReaderImpl.getCandidateSources()` explicitly checks `Modifier.isAbstract()` and skips abstract classes
- `isVersioned` left at default `false` — REST catalog is not a versioned catalog in Dremio's sense (Nessie uses `isVersioned = true`; RESTCATALOG must not)
- `metadataRefresh.datasetDiscovery: false` — consistent with catalog-based sources; metadata is driven by the catalog, not filesystem scanning
- No new constructors added — Java generates default no-arg constructor automatically; `SourceTypeTemplate.fromSourceClass()` requires this for default value reading

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required. Java 21 is needed for full Maven compile (pre-existing blocker documented in STATE.md), but the wiring artifacts are purely declarative and can be verified at runtime.

## Next Phase Readiness

Phase 8 (Validation) is now unblocked:
- `RESTCATALOG` source type will be discovered by `ConnectionReaderImpl` at startup
- Source creation form will render via `restcatalog-layout.json` with all configuration fields
- API source type descriptor will return non-null icon field
- `DeprecatedSourceResource.isSourceTypeVisible("RESTCATALOG")` returns `optionManager.getOption(RESTCATALOG_PLUGIN_ENABLED)` — already wired

Remaining concerns for Phase 8 (from STATE.md):
- Credential vending path through `DremioFileIO` not fully traced — may need targeted fix if SELECT queries fail with permission errors
- Lakekeeper Docker image tag needs runtime verification before Phase 8 test plan

---
*Phase: 07-plugin-wiring*
*Completed: 2026-02-20*

## Self-Check: PASSED

All files verified present:
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` - FOUND
- `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` - FOUND
- `plugins/icebergcatalog/src/main/resources/RESTCATALOG.svg` - FOUND
- `.planning/phases/07-plugin-wiring/07-01-SUMMARY.md` - FOUND

All commits verified present:
- `37b035f80` (Task 1: @SourceType + layout JSON) - FOUND
- `0b319652d` (Task 2: RESTCATALOG.svg) - FOUND
