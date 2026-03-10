---
phase: 25-fix-restcatalog-s3-filesystem-config-propagation-to-execution-path
plan: 01
subsystem: catalog
tags: [iceberg, restcatalog, s3, hadoop, filesystem, configuration]

# Dependency graph
requires:
  - phase: 24-multi-branch-queries-and-hardening
    provides: RestIcebergCatalogPlugin with configPropertyList (fs.s3a.* properties for S3 backends)
provides:
  - "Protected hook getConfigProperties() in IcebergCatalogPlugin for eager FS config merging"
  - "mergeConfigPropertiesIntoFsConf() called in start() before createFSCache()"
  - "RestIcebergCatalogPlugin.getConfigProperties() override returning configPropertyList"
  - "Unit test verifying configPropertyList propagation to getFsConfCopy() after start()"
affects: [DatasetFileSystemCache, DremioFileIO, createFS, getFsConfCopy, S3 filesystem access]

# Tech tracking
tech-stack:
  added: []
  patterns: ["Protected hook pattern for plugin-specific FS config merging (mirrors FileSystemPlugin.initializeFsConf())"]

key-files:
  created: []
  modified:
    - plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergCatalogPlugin.java
    - plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java
    - plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/TestRestIcebergCatalogPlugin.java

key-decisions:
  - "25-01: Eager merge in start() before createFSCache() mirrors FileSystemPlugin.initializeFsConf() -- not lazy via catalog supplier lambda"
  - "25-01: Protected hook getConfigProperties() returns emptyList in base class -- zero impact on non-REST subclasses"
  - "25-01: buildCatalogProperties() in RestIcebergCatalogPlugin left unchanged -- redundant but harmless, preserves branch accessor compatibility"
  - "25-01: mergeConfigPropertiesIntoFsConf() placed in IcebergCatalogPlugin (not subclass) -- fsConfAdapter is private final there"

patterns-established:
  - "Plugin FS config hook: protected getConfigProperties() + mergeConfigPropertiesIntoFsConf() called in start() before createFSCache()"

requirements-completed: ["N/A"]

# Metrics
duration: 8min
completed: 2026-03-11
---

# Phase 25 Plan 01: Fix RESTCATALOG S3 Filesystem Config Propagation Summary

**Eager merge of configPropertyList into Hadoop Configuration in IcebergCatalogPlugin.start() via getConfigProperties() hook, fixing S3 credential errors on RESTCATALOG sources backed by MinIO/SeaweedFS**

## Performance

- **Duration:** 8 min
- **Started:** 2026-03-11T00:23:23Z
- **Completed:** 2026-03-11T00:31:00Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Added `protected List<Property> getConfigProperties()` hook to IcebergCatalogPlugin (base returns emptyList)
- Added `protected void mergeConfigPropertiesIntoFsConf()` called in `start()` BEFORE `createFSCache()` -- fixes the lifecycle ordering bug
- Overrode `getConfigProperties()` in RestIcebergCatalogPlugin to return `configPropertyList` (merged propertyList + secretPropertyList)
- Added unit test `testConfigPropertyListIsPropagatedToFsConf` verifying fs.s3a.endpoint, fs.s3a.path.style.access, fs.s3a.access.key, fs.s3a.secret.key all appear in `getFsConfCopy()` after start()
- All 40 TestRestIcebergCatalogPlugin tests pass (including the new one)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add getConfigProperties hook and eager merge to IcebergCatalogPlugin, override in RestIcebergCatalogPlugin** - `1dafd13c6` (feat)
2. **Task 2: Add unit test verifying configPropertyList propagation to getFsConfCopy** - `41a0ff035` (test)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergCatalogPlugin.java` - Added `getConfigProperties()` hook, `mergeConfigPropertiesIntoFsConf()` method, call in `start()` before `createFSCache()`; added `com.dremio.exec.catalog.conf.Property` import
- `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` - Added `@Override protected List<Property> getConfigProperties()` returning `configPropertyList`
- `plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/TestRestIcebergCatalogPlugin.java` - Added `testConfigPropertyListIsPropagatedToFsConf` test and `Property` import

## Decisions Made
- Eager merge in `start()` mirrors `FileSystemPlugin.initializeFsConf()` exactly (the established pattern for FS config initialization)
- Base class returns `Collections.emptyList()` -- zero new code paths for non-REST subclasses
- `buildCatalogProperties()` left unchanged (redundant config.set() calls in lazy lambda are now harmless but preserved for branch accessor compatibility)
- `mergeConfigPropertiesIntoFsConf()` lives in `IcebergCatalogPlugin` because `fsConfAdapter` is private final there -- subclasses only provide the list via the hook

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- Maven version enforcement: system Maven 3.8.7 rejected; used `/home/filippo/.m2/wrapper/dists/apache-maven-3.9.9-bin/33b4b2b4/apache-maven-3.9.9/bin/mvn` with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` for all build/test steps. Not a code issue.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Phase 25 complete. The S3 filesystem config propagation bug is fixed.
- RESTCATALOG sources with S3/MinIO configPropertyList entries will now have fs.s3a.* properties in getFsConfCopy() immediately after start().
- No blockers. The fix is covered by unit test. A full end-to-end integration test (actual MinIO data file reads via RESTCATALOG) would provide additional confidence but is out of scope per the research doc.

---
*Phase: 25-fix-restcatalog-s3-filesystem-config-propagation-to-execution-path*
*Completed: 2026-03-11*

## Self-Check: PASSED

- IcebergCatalogPlugin.java: FOUND
- RestIcebergCatalogPlugin.java: FOUND
- TestRestIcebergCatalogPlugin.java: FOUND
- 25-01-SUMMARY.md: FOUND
- Commit 1dafd13c (Task 1): FOUND
- Commit 41a0ff035 (Task 2): FOUND
