---
phase: 07-plugin-wiring
verified: 2026-02-20T08:25:53Z
status: human_needed
score: 3/3 must-haves verified
re_verification: false
human_verification:
  - test: "GET /api/v3/catalog/source/type/RESTCATALOG returns HTTP 200"
    expected: "Response body contains non-null sourceType descriptor with icon field populated"
    why_human: "Requires Dremio server running; cannot verify API response programmatically without a live instance"
  - test: "Dremio UI source picker shows 'Iceberg REST Catalog' with RESTCATALOG.svg icon"
    expected: "Source type appears in the Add Source dialog with correct label and icon"
    why_human: "UI rendering requires browser + running Dremio server; visual verification needed"
  - test: "Source creation form renders all configuration fields with no blank form"
    expected: "General tab shows endpoint URI (required) and namespace filter fields; Catalog Properties tab shows propertyList and secretPropertyList; Advanced Options tab shows enableAsync and cache controls"
    why_human: "Form rendering depends on SourceTypeTemplate.fromSourceClass() classloader resolution at runtime"
  - test: "Source created against valid Lakekeeper endpoint reaches GOOD health state"
    expected: "Plugin lifecycle completes without error; source health shows GOOD in UI"
    why_human: "Requires live Lakekeeper instance and full Dremio startup; integration test"
---

# Phase 7: Plugin Wiring Verification Report

**Phase Goal:** The Iceberg REST Catalog source type is discoverable by Dremio and presents a usable configuration form in the UI
**Verified:** 2026-02-20T08:25:53Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | ConnectionReader discovers RESTCATALOG as a registered source type at startup | VERIFIED | `@SourceType(value = "RESTCATALOG", ...)` on concrete `RestIcebergCatalogPluginConfig`; `sabot-module.conf` registers `com.dremio.plugins.icebergcatalog` for classpath scan; annotation absent from abstract parent |
| 2 | Source creation form renders endpoint URI, namespace filter, catalog properties, secret credentials, and advanced options tabs | VERIFIED | `restcatalog-layout.json` is valid JSON with 3 tabs covering all 8 config fields; all `config.*` propNames match actual Java fields in `RestIcebergCatalogPluginConfig` and `IcebergCatalogPluginConfig` |
| 3 | RESTCATALOG.svg icon is returned in the API source type descriptor (non-null icon field) | VERIFIED | `RESTCATALOG.svg` (8220 bytes) at classpath root `plugins/icebergcatalog/src/main/resources/`; identical to source at `dac/ui-lib/icons/dremio/sources/RESTCATALOG.svg` (diff: no differences) |

**Score:** 3/3 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java` | `@SourceType` annotation making plugin discoverable | VERIFIED | Annotation present on line 27: `@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")`; no explicit constructors added |
| `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` | UI form descriptor with all config fields | VERIFIED | Valid JSON; `sourceType: "RESTCATALOG"`; 3 tabs; all 8 `config.*` propNames present |
| `plugins/icebergcatalog/src/main/resources/RESTCATALOG.svg` | Icon for API source type descriptor | VERIFIED | File exists (8220 bytes); real SVG content (XML header confirmed); identical to ui-lib source |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `RestIcebergCatalogPluginConfig.java` | `restcatalog-layout.json` | `@SourceType(uiConfig = "restcatalog-layout.json")` | WIRED | Pattern `uiConfig.*=.*restcatalog-layout\.json` found on annotation line |
| `restcatalog-layout.json` | `RestIcebergCatalogPluginConfig.java` field `restEndpointUri` | `config.restEndpointUri` propName reference | WIRED | `"propName": "config.restEndpointUri"` in JSON; `public String restEndpointUri` in Java class |
| `ConnectionReaderImpl` classpath scan | `RestIcebergCatalogPluginConfig.java` | `@SourceType` on concrete class; `sabot-module.conf` package registration | WIRED | `@SourceType(value = "RESTCATALOG", ...)` on non-abstract class; `sabot-module.conf` contains `dremio.classpath.scanning.packages += com.dremio.plugins.icebergcatalog` |
| `restcatalog-layout.json` config fields | Parent class `IcebergCatalogPluginConfig` Java fields | `config.propertyList`, `config.secretPropertyList`, `config.enableAsync`, `config.isCachingEnabled`, `config.maxCacheSpacePct` | WIRED | All 5 parent fields verified present in `IcebergCatalogPluginConfig.java` |
| `RESTCATALOG.svg` | API source type descriptor icon | Classloader resource load at `RESTCATALOG.svg` classpath root | WIRED | File at `src/main/resources/RESTCATALOG.svg` (classpath root); `SourceTypeTemplate.fromSourceClass()` loads via `sourceClass.getClassLoader().getResource("RESTCATALOG.svg")` |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| WIRE-01 | 07-01-PLAN.md | Iceberg REST Catalog source type is discoverable by ConnectionReader via `@SourceType` annotation | SATISFIED | `@SourceType(value = "RESTCATALOG", ...)` on concrete `RestIcebergCatalogPluginConfig`; package in `sabot-module.conf` scan list; annotation absent from abstract parent `IcebergCatalogPluginConfig` |
| WIRE-02 | 07-01-PLAN.md | Source creation form renders in Dremio UI with endpoint URI, catalog properties, and credential fields via `restcatalog-layout.json` | SATISFIED | `restcatalog-layout.json` valid JSON with all 8 config fields across 3 tabs; all propNames match actual Java fields; `uiConfig` pointer in `@SourceType` connects annotation to JSON |

No orphaned requirements: REQUIREMENTS.md maps only WIRE-01 and WIRE-02 to Phase 7, both claimed by 07-01-PLAN.md and both satisfied.

### Anti-Patterns Found

No anti-patterns detected. Scanned all 3 phase-modified files for TODO, FIXME, XXX, HACK, PLACEHOLDER, empty implementations, and console.log stubs — all clear.

### Human Verification Required

#### 1. API Source Type Endpoint

**Test:** Start Dremio and call `GET /api/v3/catalog/source/type/RESTCATALOG` with a valid auth token.
**Expected:** HTTP 200 with a JSON body containing a non-null `icon` field and `sourceType: "RESTCATALOG"`.
**Why human:** Requires a running Dremio server; the classloader resolution of the SVG and the full `SourceTypeTemplate.fromSourceClass()` chain cannot be verified statically.

#### 2. UI Source Picker Display

**Test:** Open Dremio UI, navigate to Add Source, and look for "Iceberg REST Catalog" in the source picker list.
**Expected:** Source type appears with the RESTCATALOG.svg icon and the label "Iceberg REST Catalog".
**Why human:** UI rendering depends on the client receiving the source type list from the API and rendering the icon — visual browser verification required.

#### 3. Configuration Form Field Rendering

**Test:** Click "Iceberg REST Catalog" in the source picker to open the creation form.
**Expected:** General tab shows an "Endpoint URI" required field and a "Namespace Filter" section with an "Add namespace" control. Catalog Properties tab shows property list and secret credentials sections. Advanced Options tab shows async toggle and cache controls.
**Why human:** Form rendering is driven by `restcatalog-layout.json` parsed at runtime by the UI framework; the correctness of `uiType: "value_list"` and `checkboxController` behavior requires visual inspection.

#### 4. Plugin Lifecycle Health Check (Integration)

**Test:** Create a RESTCATALOG source pointing to a valid Lakekeeper endpoint.
**Expected:** Source health reaches GOOD state; no errors in Dremio coordinator logs during plugin initialization.
**Why human:** Requires a live Lakekeeper instance; `RESTCATALOG_PLUGIN_ENABLED` support option must be set to true; full integration environment needed.

### Gaps Summary

No gaps found. All automated must-haves are verified:

- `@SourceType` annotation is present on the correct (concrete) class with the exact values specified in the plan.
- `restcatalog-layout.json` is valid JSON, contains `sourceType: "RESTCATALOG"`, and covers all 8 config fields (`restEndpointUri`, `allowedNamespaces`, `isRecursiveAllowedNamespaces`, `propertyList`, `secretPropertyList`, `enableAsync`, `isCachingEnabled`, `maxCacheSpacePct`) across 3 tabs.
- `RESTCATALOG.svg` is a real 8220-byte SVG at classpath root, identical to the ui-lib source.
- All three key links are wired: annotation → JSON (`uiConfig`), JSON propNames → Java fields, classpath scanner → plugin class.
- No `@SourceType` on the abstract parent. No explicit constructors added.
- Both commits (`37b035f80`, `0b319652d`) exist in git history.
- WIRE-01 and WIRE-02 are the only requirements mapped to Phase 7 and both are satisfied.

The four human verification items are runtime/integration behaviors that cannot be confirmed without a live Dremio instance.

---

_Verified: 2026-02-20T08:25:53Z_
_Verifier: Claude (gsd-verifier)_
