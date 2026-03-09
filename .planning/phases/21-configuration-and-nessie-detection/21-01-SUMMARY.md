---
phase: 21-configuration-and-nessie-detection
plan: 01
subsystem: api
tags: [protostuff, iceberg-rest-catalog, nessie, source-config, ui-layout]

# Dependency graph
requires: []
provides:
  - "enableNessie boolean config field on RestIcebergCatalogPluginConfig with @Tag(13)"
  - "Nessie Options UI section in restcatalog-layout.json General tab"
affects:
  - 21-02-nessie-detection
  - 22-branch-aware-catalog
  - 23-sql-and-api

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Protostuff @Tag sequential numbering for config fields"
    - "UI layout JSON section pattern for source config toggles"

key-files:
  created: []
  modified:
    - "plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java"
    - "plugins/icebergcatalog/src/main/resources/restcatalog-layout.json"

key-decisions:
  - "Used primitive boolean (not Boolean wrapper) for enableNessie to ensure backward-compatible deserialization defaulting to false"
  - "Placed Nessie Options in General tab (not Advanced Options) for discoverability"

patterns-established:
  - "Config fields for Nessie features gate on enableNessie boolean"
  - "Tag range 10-19 reserved for RestIcebergCatalogPluginConfig fields"

requirements-completed: [CFG-01, CMP-01]

# Metrics
duration: 3min
completed: 2026-03-09
---

# Phase 21 Plan 01: Add enableNessie Config Field Summary

**Protostuff @Tag(13) enableNessie boolean on RestIcebergCatalogPluginConfig with Nessie Options UI toggle in General tab**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-09T20:58:57Z
- **Completed:** 2026-03-09T21:02:42Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added `enableNessie` boolean config field with `@Tag(13)` defaulting to `false` for backward compatibility
- Added "Nessie Options" UI section to the RESTCATALOG source General tab
- Module compiles cleanly with no behavioral changes to existing sources

## Task Commits

Each task was committed atomically:

1. **Task 1: Add enableNessie field to RestIcebergCatalogPluginConfig** - `d6349fbb4` (feat)
2. **Task 2: Add Nessie Options section to restcatalog-layout.json** - `63e67b1ba` (feat)

## Files Created/Modified
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` - Added @Tag(13) enableNessie boolean field with DisplayMetadata, updated tag range comment
- `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` - Added Nessie Options section with config.enableNessie toggle in General tab

## Decisions Made
- Used primitive `boolean` (not `Boolean` wrapper) for `enableNessie` -- primitive boolean defaults to `false` in Java and protostuff handles it correctly for backward compatibility, so existing serialized configs without this field deserialize with `enableNessie=false`
- Placed "Nessie Options" section in the General tab after Namespace Filter for user discoverability, rather than burying in Advanced Options

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- `enableNessie` field is available for Plan 02 (Nessie detection logic) to gate on
- All subsequent Nessie branch-aware behavior can check `config.enableNessie` to activate new code paths
- Existing RESTCATALOG sources continue working unchanged (CMP-01 satisfied)

## Self-Check: PASSED

- FOUND: RestIcebergCatalogPluginConfig.java
- FOUND: restcatalog-layout.json
- FOUND: 21-01-SUMMARY.md
- FOUND: commit d6349fbb4
- FOUND: commit 63e67b1ba

---
*Phase: 21-configuration-and-nessie-detection*
*Plan: 01*
*Completed: 2026-03-09*
