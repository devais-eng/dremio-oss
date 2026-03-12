---
phase: 25-fix-restcatalog-s3-filesystem-config-propagation-to-execution-path
verified: 2026-03-11T00:45:00Z
status: passed
score: 3/3 must-haves verified
re_verification: false
---

# Phase 25: Fix RESTCATALOG S3 Filesystem Config Propagation — Verification Report

**Phase Goal:** RESTCATALOG sources can read data from S3-compatible backends (MinIO, SeaweedFS, Azure) by propagating catalog properties into the execution-time Hadoop Configuration
**Verified:** 2026-03-11T00:45:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #   | Truth | Status | Evidence |
|-----|-------|--------|----------|
| 1   | Catalog properties (fs.s3a.endpoint, credentials, path-style-access, etc.) from configPropertyList are available in getFsConfCopy() immediately after plugin.start() | VERIFIED | `IcebergCatalogPlugin.start()` calls `mergeConfigPropertiesIntoFsConf()` at line 301 before `createFSCache()` at line 303; `RestIcebergCatalogPlugin.getConfigProperties()` returns `configPropertyList` (line 188-190); unit test `testConfigPropertyListIsPropagatedToFsConf` asserts all four properties appear in `getFsConfCopy()` |
| 2   | A RESTCATALOG source with S3/MinIO properties does not produce credential errors when creating filesystem instances | VERIFIED | Both planning-time (`DatasetFileSystemCache` via `getFsConfCopy()`) and execution-time (`createFS` -> `DatasetFileSystemCache.load()`) paths share the same `fsConfAdapter.getConfiguration()`, which is fully populated before `createFSCache()` is called; unit test confirms the eager merge works |
| 3   | Cloud-hosted REST catalogs using credential vending are unaffected — getConfigProperties() returns empty list in base class | VERIFIED | `IcebergCatalogPlugin.getConfigProperties()` returns `Collections.emptyList()` (line 162-164); `mergeConfigPropertiesIntoFsConf()` has `!properties.isEmpty()` guard (line 173) making it a no-op for non-REST subclasses; `RestIcebergCatalogPlugin` is the only production subclass |

**Score:** 3/3 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergCatalogPlugin.java` | `getConfigProperties()` hook + `mergeConfigPropertiesIntoFsConf()` called in `start()` before `createFSCache()` | VERIFIED | Hook at lines 162-164; merge method at lines 171-179; called in `start()` at line 301, before `createFSCache()` at line 303; `com.dremio.exec.catalog.conf.Property` import at line 42 |
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` | `@Override protected List<Property> getConfigProperties()` returning `configPropertyList` | VERIFIED | Override at lines 187-190; `configPropertyList` is `private final` field set in constructor at line 151 via `getConfigPropertyList(pluginConfig)` which merges `propertyList` + `secretPropertyList` |
| `plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/TestRestIcebergCatalogPlugin.java` | Unit test `testConfigPropertyListIsPropagatedToFsConf` verifying properties appear in `getFsConfCopy()` after `start()` | VERIFIED | Test at lines 703-739; uses real `RestIcebergCatalogPluginConfig`, calls `testPlugin.start()`, asserts all four properties (`fs.s3a.endpoint`, `fs.s3a.path.style.access`, `fs.s3a.access.key`, `fs.s3a.secret.key`) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `IcebergCatalogPlugin.start()` | `mergeConfigPropertiesIntoFsConf()` | direct call before `createFSCache()` | WIRED | Line 301: `mergeConfigPropertiesIntoFsConf()` at line 301; `createFSCache()` at line 303 — ordering confirmed |
| `mergeConfigPropertiesIntoFsConf()` | `getConfigProperties()` | polymorphic hook call | WIRED | Line 172: `List<Property> properties = getConfigProperties()` — dispatches to `RestIcebergCatalogPlugin.getConfigProperties()` at runtime |
| `RestIcebergCatalogPlugin.getConfigProperties()` | `configPropertyList` | return statement | WIRED | Lines 188-190: `return configPropertyList;` — field populated in constructor from both `propertyList` and `secretPropertyList` |
| `RestIcebergCatalogPlugin.start()` | `super.start()` | first line of override | WIRED | Line 169: `super.start();` — ensures merge runs before `createFSCache()` and before `detectNessieBackend()` |

### Backward Compatibility Verification

| Concern | Check | Result |
|---------|-------|--------|
| `buildCatalogProperties()` in `RestIcebergCatalogPlugin` unmodified | Lines 535-551 — still calls `config.set(p.name, p.value)` inside lazy lambda | CONFIRMED — redundant but harmless, preserves branch accessor compatibility |
| Only `RestIcebergCatalogPlugin` is affected | Only one production subclass of `IcebergCatalogPlugin` exists | CONFIRMED — searched entire codebase |
| Non-REST plugins unchanged | `IcebergCatalogPlugin.getConfigProperties()` returns `Collections.emptyList()` + `!isEmpty()` guard | CONFIRMED — zero new code paths for non-REST subclasses |

### Requirements Coverage

No requirement IDs were specified for this phase (plan declares `requirements: ["N/A"]`).

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `IcebergCatalogPlugin.java` | 254 | `// TODO: implement RBAC` | Info | Pre-existing, unrelated to phase 25 |
| `RestIcebergCatalogPlugin.java` | 424, 507, 588, 668, 705, 888, 934, 1043, 1116 | `// TODO: DX-XXXXX` | Info | Pre-existing tech debt tickets, all unrelated to phase 25 |

No blockers or warnings from phase 25 changes.

### Human Verification Required

#### 1. End-to-end SELECT query on MinIO-backed RESTCATALOG source

**Test:** Configure a RESTCATALOG source in Dremio pointed at Nessie+MinIO with `fs.s3a.endpoint`, `fs.s3a.access.key`, `fs.s3a.secret.key`, and `fs.s3a.path.style.access=true` in the source's property list. Create an Iceberg table with actual Parquet data files in MinIO. Execute `SELECT * FROM <source>.<namespace>.<table>`.
**Expected:** Query returns data without "Credentials for the Storage Provider" or "Invalid AWSCredentialsProvider" errors.
**Why human:** Requires a running Dremio coordinator + executor + Nessie + MinIO stack. The unit test verifies the property propagation mechanism, but end-to-end data file reads through S3AFileSystem are out of scope for automated verification here.

#### 2. Credential vending regression check

**Test:** Configure a cloud-hosted REST catalog source (e.g., Polaris, Unity Catalog) that uses credential vending (no `fs.s3a.*` properties in the property list). Execute a query.
**Expected:** Queries work as before — credential vending tokens are used, not static credentials.
**Why human:** Requires a live cloud catalog endpoint. The code path is verified safe (empty list guard), but behavioral confirmation requires a real credential-vending catalog.

### Gaps Summary

No gaps. All three must-have truths are verified, all artifacts are substantive and wired, all key links are confirmed. The phase goal is achieved:

- The lifecycle ordering bug (configPropertyList merged lazily inside Supplier lambda, too late for DatasetFileSystemCache creation) is fixed by the eager `mergeConfigPropertiesIntoFsConf()` call in `start()`.
- The fix follows the established `FileSystemPlugin.initializeFsConf()` pattern.
- Zero impact on non-REST subclasses (only `RestIcebergCatalogPlugin` exists and only it overrides `getConfigProperties()`).
- Unit test coverage directly demonstrates the fix: properties from both `propertyList` and `secretPropertyList` appear in `getFsConfCopy()` after `start()`.
- Both commits (`1dafd13c6`, `41a0ff035`) exist and are scoped correctly.

---

_Verified: 2026-03-11T00:45:00Z_
_Verifier: Claude (gsd-verifier)_
