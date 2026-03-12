---
phase: 21-configuration-and-nessie-detection
verified: 2026-03-09T22:30:00Z
status: passed
score: 10/10 must-haves verified
gaps: []
---

# Phase 21: Configuration and Nessie Detection Verification Report

**Phase Goal:** Users can configure a RESTCATALOG source for Nessie mode and the plugin correctly identifies Nessie backends and their default branch
**Verified:** 2026-03-09T22:30:00Z
**Status:** passed
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | RESTCATALOG source config has an enableNessie boolean field that defaults to false | VERIFIED | `RestIcebergCatalogPluginConfig.java` line 58: `public boolean enableNessie = false;` -- primitive boolean, correct default |
| 2 | enableNessie field persists across restarts via protostuff @Tag(13) serialization | VERIFIED | `RestIcebergCatalogPluginConfig.java` line 56: `@Tag(13)` annotation present; primitive `boolean` ensures backward-compatible deserialization (defaults to false for missing fields) |
| 3 | enableNessie toggle appears in the RESTCATALOG source configuration UI under a Nessie Options section | VERIFIED | `restcatalog-layout.json` lines 41-48: `"Nessie Options"` section with `"config.enableNessie"` element in General tab |
| 4 | Existing RESTCATALOG sources without enableNessie set continue to deserialize correctly with enableNessie=false | VERIFIED | Primitive `boolean` defaults to `false` in Java/protostuff; `detectNessieBackend()` returns immediately when `enableNessie=false` (line 163-164) |
| 5 | When enableNessie=true and the REST catalog backend is Nessie, the plugin detects it as Nessie and stores isNessieDetected=true | VERIFIED | `RestIcebergCatalogPlugin.java` lines 176-191: reads `nessie.is-nessie-catalog` from properties, sets `isNessieDetected = true` when value equals `"true"` |
| 6 | When enableNessie=true, the plugin reads the default branch name from nessie.default-branch.name in the RESTCatalog properties | VERIFIED | `RestIcebergCatalogPlugin.java` lines 179-187: reads `nessie.default-branch.name`, falls back to `"main"` if null/empty |
| 7 | When enableNessie=false (default), no detection code runs -- no new network calls, no new log messages, no new fields populated | VERIFIED | `RestIcebergCatalogPlugin.java` lines 163-164: `if (!enableNessie) { return; }` -- early return guard before any accessor/network calls |
| 8 | When enableNessie=true but the backend is NOT Nessie, the plugin logs a warning and sets isNessieDetected=false without crashing | VERIFIED | `RestIcebergCatalogPlugin.java` lines 192-199: else branch logs warning and sets `isNessieDetected = false` |
| 9 | When detection fails due to network error, the plugin logs a warning and continues with isNessieDetected=false and defaultBranch=main | VERIFIED | `RestIcebergCatalogPlugin.java` lines 200-208: broad `catch (Exception e)` sets `isNessieDetected = false` and `defaultBranch = DEFAULT_BRANCH_FALLBACK` ("main") |
| 10 | The detection uses the cached ExpiringCatalogCache path (not the raw catalogSupplier) to avoid creating throwaway RESTCatalog instances | VERIFIED | `IcebergRestCatalogAccessor.java` line 75: `getRestCatalogProperties()` calls `getCatalog()` which delegates to `icebergCatalogSupplier.get()` -- and `icebergCatalogSupplier` is set to `ExpiringCatalogCache` in constructor (line 44-47). Contrast with `getDefaultBaseLocation()` (line 62) which uses raw `catalogSupplier.get()` |

**Score:** 10/10 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` | enableNessie config field with @Tag(13) | VERIFIED | File exists (72 lines), contains `@Tag(13)` (line 56), `enableNessie = false` (line 58), `@DisplayMetadata(label = "Enable Nessie Version Control")` (line 57) |
| `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` | Nessie Options UI section with enableNessie toggle | VERIFIED | File exists (96 lines), valid JSON, contains "Nessie Options" section (line 41) with `"config.enableNessie"` propName (line 44) in General tab |
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java` | Nessie detection logic in start() override, isNessieDetected/defaultBranch fields, public getters | VERIFIED | File exists (989 lines), contains `detectNessieBackend` (line 159, 162), `start()` override (line 157), `isNessieDetected()` getter (line 215), `getDefaultBranch()` getter (line 223), volatile fields (lines 126-127), static constants (lines 114-116), class logger (line 111) |
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergRestCatalogAccessor.java` | Package-private getRestCatalogProperties() method using cached getCatalog() path | VERIFIED | File exists (87 lines), contains `getRestCatalogProperties()` (line 74) with package-private visibility, calls `getCatalog()` (line 75) not `catalogSupplier.get()` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `RestIcebergCatalogPluginConfig.java` | `restcatalog-layout.json` | propName references field name | WIRED | Layout JSON uses `"config.enableNessie"` (line 44) which maps to the `enableNessie` field in the config class |
| `RestIcebergCatalogPlugin.java` | `IcebergRestCatalogAccessor.java` | cast getCatalogAccessor() to IcebergRestCatalogAccessor, call getRestCatalogProperties() | WIRED | Line 174: `((IcebergRestCatalogAccessor) accessor).getRestCatalogProperties()` -- accessor obtained via `getCatalogAccessor()` (line 168) |
| `IcebergRestCatalogAccessor.getRestCatalogProperties()` | `AbstractRestCatalogAccessor.getCatalog()` | calls protected getCatalog() which uses ExpiringCatalogCache | WIRED | Line 75: `getCatalog()` calls `icebergCatalogSupplier.get()` where supplier is `ExpiringCatalogCache` (set in IcebergRestCatalogAccessor constructor lines 44-47) |
| `RestIcebergCatalogPlugin.start()` | `IcebergCatalogPlugin.start()` | super.start() call before detection | WIRED | Line 158: `super.start()` confirmed; parent `IcebergCatalogPlugin.start()` at line 273 creates catalogAccessor and sets isOpen=true |
| `RestIcebergCatalogPlugin` constructor | `RestIcebergCatalogPluginConfig.enableNessie` | field assignment in constructor | WIRED | Line 142: `this.enableNessie = pluginConfig.enableNessie;` |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| CFG-01 | 21-01 | User can enable Nessie mode via `enableNessie` boolean on RESTCATALOG source config (default false) | SATISFIED | `@Tag(13) public boolean enableNessie = false` in config class; "Nessie Options" UI section in layout JSON |
| CFG-02 | 21-02 | Plugin auto-detects Nessie backend from config endpoint response (`nessie.is-nessie-catalog=true`) | SATISFIED | `detectNessieBackend()` reads `nessie.is-nessie-catalog` from RESTCatalog properties; sets `isNessieDetected=true` when `"true"` |
| CFG-03 | 21-02 | Plugin auto-discovers default branch from Nessie server config (`nessie.default-branch.name`) | SATISFIED | `detectNessieBackend()` reads `nessie.default-branch.name` from properties; falls back to `"main"` if absent |
| CMP-01 | 21-01, 21-02 | Non-Nessie RESTCATALOG sources are completely unaffected (zero behavioral change when `enableNessie=false`) | SATISFIED | Primitive `boolean enableNessie = false` default ensures backward compatibility; `detectNessieBackend()` early-returns when `!enableNessie`; no other behavioral changes in modified files |

**Orphaned requirements check:** REQUIREMENTS.md maps CFG-01, CFG-02, CFG-03, CMP-01 to Phase 21. Plan 21-01 claims CFG-01, CMP-01. Plan 21-02 claims CFG-02, CFG-03, CMP-01. All four requirement IDs are accounted for with no orphans.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| RestIcebergCatalogPlugin.java | 267, 350, 431, 511, 548, 731, 777, 886, 959 | TODO comments | Info | All are pre-existing TODOs in code not modified by this phase; none in the new Nessie detection code (lines 110-225) |

No blocker or warning anti-patterns found in the newly added code.

### Human Verification Required

### 1. UI Toggle Rendering

**Test:** Open Dremio UI, navigate to Add Source > REST Catalog. Check the General tab for a "Nessie Options" section with an "Enable Nessie Version Control" toggle.
**Expected:** Toggle appears after "Namespace Filter" section, defaults to unchecked (false).
**Why human:** Cannot programmatically verify UI rendering from layout JSON alone; Dremio's UI framework interprets the JSON at runtime.

### 2. Nessie Detection with Real Backend

**Test:** Configure a RESTCATALOG source pointing to a real Nessie REST server with `enableNessie=true`. Start the source. Check logs for detection message.
**Expected:** Log message: `Nessie backend detected for source '...'. Default branch: main` (or whatever the server's default branch is).
**Why human:** Detection requires a live Nessie REST server responding to the `/v1/config` endpoint with `nessie.is-nessie-catalog=true` in the properties.

### 3. Non-Nessie Backend Graceful Handling

**Test:** Configure a RESTCATALOG source pointing to a non-Nessie REST catalog server (e.g., Polaris, Tabular) with `enableNessie=true`. Start the source.
**Expected:** Warning logged about backend not being Nessie; source starts successfully and functions normally for table queries.
**Why human:** Requires a live non-Nessie REST catalog server to verify graceful degradation.

### Gaps Summary

No gaps found. All 10 observable truths are verified against the actual codebase. All 4 artifacts exist, are substantive (not stubs), and are properly wired together. All 4 requirement IDs (CFG-01, CFG-02, CFG-03, CMP-01) are satisfied. No blocker anti-patterns in the new code.

The implementation is complete and correct:
- Config field uses primitive `boolean` with `@Tag(13)` for safe protostuff serialization
- UI layout JSON is valid and correctly references `config.enableNessie`
- Detection logic is properly gated behind `enableNessie` (CMP-01 compliance)
- Detection uses the cached `ExpiringCatalogCache` path, not raw `catalogSupplier`
- Error handling is robust with broad `Exception` catch ensuring source always starts
- Public getters (`isNessieDetected()`, `getDefaultBranch()`) are available for Phase 22+
- Volatile fields ensure cross-thread visibility of detection results

---

_Verified: 2026-03-09T22:30:00Z_
_Verifier: Claude (gsd-verifier)_
