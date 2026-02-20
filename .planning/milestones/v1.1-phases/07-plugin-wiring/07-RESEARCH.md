# Phase 7: Plugin Wiring - Research

**Researched:** 2026-02-20
**Domain:** Dremio storage plugin registration and UI form wiring
**Confidence:** HIGH

## Summary

Phase 7 requires wiring the already-implemented `RestIcebergCatalogPlugin` into Dremio's plugin discovery system so it becomes discoverable via the API and usable from the UI. The plugin code is complete. Only two artifacts are missing: (1) the `@SourceType` annotation on `RestIcebergCatalogPluginConfig`, and (2) a `restcatalog-layout.json` UI form descriptor placed in the plugin's resources directory.

Dremio discovers plugins via classpath scanning. The `sabot-module.conf` file in the icebergcatalog plugin already declares `com.dremio.plugins.icebergcatalog` for scanning. The `ConnectionReaderImpl.getCandidateSources()` scans for `@SourceType`-annotated classes and skips abstract classes and interfaces — confirming the prior decision that `@SourceType` must go on `RestIcebergCatalogPluginConfig` (concrete), not the abstract `IcebergCatalogPluginConfig`. Once annotated, the class will be picked up automatically and `DeprecatedSourceResource` already has the `isSourceTypeVisible("RESTCATALOG")` switch case returning the feature-flag option value.

The `restcatalog-layout.json` must live in `plugins/icebergcatalog/src/main/resources/` because `SourceTypeTemplate.fromSourceClass()` loads it via `sourceClass.getClassLoader().getResourceAsStream(type.uiConfig())`. The layout JSON uses `config.<fieldName>` property references mapped to the public fields of `RestIcebergCatalogPluginConfig` and its parent `IcebergCatalogPluginConfig`. All required fields already exist: `restEndpointUri` (tag 10), `allowedNamespaces` (tag 11), `propertyList` (tag 1), `secretPropertyList` (tag 2).

The SVG icon `RESTCATALOG.svg` already exists at `dac/ui-lib/icons/dremio/sources/RESTCATALOG.svg` and is bundled into the `dac/ui` jar for frontend use. `SourceTypeTemplate.fromSourceClass()` also attempts to load `RESTCATALOG.svg` via the plugin classloader at classpath root — if not found, the API returns `null` for the `icon` field (which the backend logs as a warning but does not fail). The frontend icon in the UI picker comes from the `dac/ui` bundle, not the API icon field, so the UI will show the icon correctly regardless.

**Primary recommendation:** Add `@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")` to `RestIcebergCatalogPluginConfig`, then create `restcatalog-layout.json` in `plugins/icebergcatalog/src/main/resources/`. Optionally copy `RESTCATALOG.svg` to the same resources dir so the API icon field is non-null.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| WIRE-01 | Iceberg REST Catalog source type is discoverable by ConnectionReader via @SourceType annotation | `ConnectionReaderImpl.getCandidateSources()` scans for `@SourceType`-annotated concrete classes via classpath scan. `sabot-module.conf` already declares `com.dremio.plugins.icebergcatalog` package. Adding `@SourceType` to `RestIcebergCatalogPluginConfig` is the single required change. |
| WIRE-02 | Source creation form renders in Dremio UI with endpoint URI, catalog properties, and credential fields via restcatalog-layout.json | `SourceTypeTemplate.fromSourceClass()` loads the file named in `uiConfig` attribute via `sourceClass.getClassLoader().getResourceAsStream()`. File must be in `plugins/icebergcatalog/src/main/resources/`. JSON uses `config.fieldName` references. All required fields exist in the config classes. |
</phase_requirements>

---

## Standard Stack

### Core
| Component | Location | Purpose | Why Standard |
|-----------|----------|---------|--------------|
| `@SourceType` annotation | `com.dremio.exec.catalog.conf.SourceType` | Marks a `ConnectionConf` subclass as a discoverable plugin type | Used by every Dremio plugin; required for classpath scanning |
| `restcatalog-layout.json` | `plugins/icebergcatalog/src/main/resources/` | UI form descriptor | Loaded by `SourceTypeTemplate.fromSourceClass()` via classloader; same mechanism as NAS, S3, Nessie |
| `sabot-module.conf` | Already exists in plugin resources | Declares package for scanning | Already present and correct |

### Supporting
| Component | Location | Purpose |
|-----------|----------|---------|
| `RESTCATALOG.svg` (optional) | Copy to `plugins/icebergcatalog/src/main/resources/RESTCATALOG.svg` | Populates the `icon` field in the API source type descriptor. If absent, API returns `null` icon (UI still shows icon from its own bundle). |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `restcatalog-layout.json` | Leave `uiConfig = ""` (no layout) | Form still renders using auto-generated property list from Java fields, but required fields ordering, grouping, and validation are missing. Success criterion 3 requires the form "renders fields for..." — layout JSON is required. |

---

## Architecture Patterns

### Recommended Project Structure
```
plugins/icebergcatalog/src/main/
├── java/com/dremio/plugins/icebergcatalog/store/
│   └── RestIcebergCatalogPluginConfig.java   # ADD @SourceType here
└── resources/
    ├── sabot-module.conf                      # already exists
    ├── restcatalog-layout.json                # CREATE this
    └── RESTCATALOG.svg                        # COPY from dac/ui-lib (optional)
```

### Pattern 1: @SourceType Annotation
**What:** Annotate the concrete `ConnectionConf` subclass with `@SourceType`, providing the type string (matches what's in `isSourceTypeVisible()`), a human label, and the UI config filename.

**When to use:** Every plugin that should appear in source picker.

**Example (from `NASConf.java`):**
```java
// Source: /home/emanuele/IdeaProjects/dremio-oss/plugins/nas/src/main/java/com/dremio/exec/store/dfs/NASConf.java
@SourceType(value = "NAS", uiConfig = "nas-layout.json")
public class NASConf extends FileSystemConf<NASConf, FileSystemPlugin<NASConf>> {
```

**For RESTCATALOG:**
```java
// Source: /home/emanuele/IdeaProjects/dremio-oss/plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPluginConfig.java
@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")
public class RestIcebergCatalogPluginConfig extends IcebergCatalogPluginConfig {
```

**Verified constraints from `SourceType.java`:**
- `value`: the type string used in `getAllConnectionConfs()` map key and in `isSourceTypeVisible()`
- `label`: display name in UI picker
- `uiConfig`: filename loaded via classloader — must be classpath-root in plugin JAR
- `configurable()`: defaults to `true` — source appears in picker and is configurable
- `listable()`: defaults to `true` — source appears in the list endpoint
- `isVersioned`: defaults to `false` — correct for REST catalog

### Pattern 2: Layout JSON Structure
**What:** A JSON file that controls how the UI renders the source configuration form. The `sourceType` field must match the `@SourceType` value string.

**When to use:** Any plugin needing form field grouping, ordering, secret masking, validation, or conditional UI.

**Minimal viable structure (from `nas-layout.json`):**
```json
{
  "sourceType": "NAS",
  "metadataRefresh": {
    "isFileSystemSource": true
  },
  "form": {
    "tabs": [
      {
        "name": "General",
        "isGeneral": true,
        "sections": [
          {
            "name": "Connection",
            "elements": [
              { "propName": "config.path", "validate": { "isRequired": true } }
            ]
          }
        ]
      }
    ]
  }
}
```

**For RESTCATALOG, the fields to wire:**

From `RestIcebergCatalogPluginConfig` (extends `IcebergCatalogPluginConfig`):
- `config.restEndpointUri` — @Tag(10), endpoint URI, required
- `config.allowedNamespaces` — @Tag(11), list of namespaces, not required (null = all)
- `config.isRecursiveAllowedNamespaces` — @Tag(12), boolean toggle
- `config.propertyList` — @Tag(1), `List<Property>`, catalog properties
- `config.secretPropertyList` — @Tag(2), `List<Property>`, `@Secret`, catalog credentials
- `config.enableAsync` — @Tag(3), boolean, Advanced Options
- `config.isCachingEnabled` — @Tag(4), boolean, Advanced Options
- `config.maxCacheSpacePct` — @Tag(5), integer, Advanced Options

**propName for `List<Property>` fields:** Use `"propName": "config.propertyList"` without `[]`. The UI auto-detects `property_list` type from the Java field type `List<Property>`.

**propName for `List<String>` fields:** Use `"propName": "config.allowedNamespaces[]"` with `[]` suffix and `"uiType": "value_list"`.

**secure field for `secretPropertyList`:** The `@Secret` annotation controls secret masking in protostuff. The layout JSON uses `"secure": true` on elements to indicate secret masking in the UI — but for `List<Property>` typed as secretPropertyList, the `@Secret` annotation on the Java field is the primary mechanism.

**metadataRefresh:** RESTCATALOG uses manual metadata refresh (not filesystem scan). Use:
```json
"metadataRefresh": {
  "datasetDiscovery": false,
  "isFileSystemSource": false
}
```
This is consistent with a catalog-based source (not filesystem-based like NAS/S3).

### Pattern 3: Classpath Scanning Registration
**What:** The `sabot-module.conf` declares packages to scan. No change needed — the package `com.dremio.plugins.icebergcatalog` is already declared.

**Verification path:**
1. `ConnectionReaderImpl.getCandidateSources()` → calls `scanResult.getAnnotatedClasses(SourceType.class)`
2. Filters abstract/interface classes (skipped) and non-`ConnectionConf` subclasses (skipped)
3. `RestIcebergCatalogPluginConfig` is concrete and extends `ConnectionConf` via `IcebergCatalogPluginConfig` → will be picked up

### Anti-Patterns to Avoid
- **Annotating the abstract class**: `IcebergCatalogPluginConfig` is abstract. `ConnectionReaderImpl.getCandidateSources()` explicitly checks `Modifier.isAbstract(input.getModifiers())` and skips such classes with a WARN log. Only annotate the concrete `RestIcebergCatalogPluginConfig`.
- **Wrong `uiConfig` filename**: If the `uiConfig` attribute does not exactly match a classpath-root resource, `SourceTypeTemplate` logs a warning and returns `null` for `uiConfig`. The form will render using auto-generated property list, not the layout. This will NOT break startup but will silently produce a poor UI.
- **Placing layout JSON in wrong location**: Must be in `src/main/resources/` (at root, not in a subdirectory) so it ends up at classpath root in the JAR.
- **Using `isVersioned = true`**: Do not set `isVersioned = true` on `RestIcebergCatalogPluginConfig`. The Nessie plugin uses this for versioned catalog behavior. The REST catalog is not a versioned catalog in Dremio's sense.
- **Forgetting `no-arg constructor`**: `SourceTypeTemplate.fromSourceClass()` calls `sourceClass.getConstructor().newInstance()` to read default values. `RestIcebergCatalogPluginConfig` must have an accessible no-arg constructor. Currently it has none declared — Java will generate a default one since there's no other constructor. Verify this does not break.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Plugin discovery registration | Custom registry | `@SourceType` + classpath scan | Already implemented in `ConnectionReaderImpl`; the scan happens automatically at startup |
| Form field type detection | Custom type mapping | Rely on Java field type introspection | `SourceTypeTemplate` already maps `List<Property>` → `property_list`, `List<String>` → `value_list`, `boolean` → `boolean`, `int` → `number`, etc. |
| Visibility/feature-flag gating | New switch statement | Existing switch in `DeprecatedSourceResource.isSourceTypeVisible()` | `RESTCATALOG` case already exists and returns `optionManager.getOption(RESTCATALOG_PLUGIN_ENABLED)` |

**Key insight:** Plugin registration in Dremio is entirely annotation-driven plus one JSON file. There is no manual registry, no DI binding to add, no protobuf enum to extend.

---

## Common Pitfalls

### Pitfall 1: Abstract Class Skip
**What goes wrong:** `@SourceType` added to `IcebergCatalogPluginConfig` (abstract) instead of `RestIcebergCatalogPluginConfig`.
**Why it happens:** Developer sees abstract class as the "base config" and annotates it there.
**How to avoid:** Always annotate the concrete class. The scanner logs `"Expected a concrete implementation of SourceConf"` and moves on — no exception thrown, plugin silently not registered.
**Warning signs:** `GET /api/v3/source/type/RESTCATALOG` returns 404.

### Pitfall 2: Layout JSON Not Found
**What goes wrong:** `uiConfig = "restcatalog-layout.json"` but file is missing or in wrong location.
**Why it happens:** File placed in a subdirectory (e.g. `resources/layouts/`) instead of at resources root.
**How to avoid:** Place at `src/main/resources/restcatalog-layout.json` — this maps to classpath root.
**Warning signs:** API returns `uiConfig: null`; UI shows blank or auto-generated form.

### Pitfall 3: Wrong propName Prefix
**What goes wrong:** Layout JSON uses `"propName": "restEndpointUri"` (no `config.` prefix).
**Why it happens:** Looking at field name only.
**How to avoid:** All propNames must use `"config."` prefix — this is the Jackson serialization path used by the UI. See every existing layout JSON as evidence.
**Warning signs:** Form renders but fields are empty or not bound to config.

### Pitfall 4: `secretPropertyList` Secure Handling
**What goes wrong:** Secret properties shown in cleartext in UI.
**Why it happens:** `@Secret` on the Java field controls protostuff redaction but the UI needs `"secure": true` on elements OR the field type needs to be detected as a property_list-with-secrets.
**How to avoid:** For `List<Property>` fields annotated with `@Secret`, the Java field annotation is what drives `clearSecrets()` in serialization. The UI form for a `property_list` renders each property value as a password field when the field is annotated `@Secret`. Verify by checking the NESSIE layout — it does not need `"secure"` on `config.propertyList` because the type detection handles it.
**Warning signs:** Secrets visible in network tab or API responses.

### Pitfall 5: `no-arg constructor` Requirement
**What goes wrong:** `SourceTypeTemplate.fromSourceClass()` throws `NoSuchMethodException` when trying to instantiate `RestIcebergCatalogPluginConfig` to read default values.
**Why it happens:** If a constructor with parameters is added to the class (Java no longer generates the default no-arg constructor).
**How to avoid:** `RestIcebergCatalogPluginConfig` currently has no declared constructors → Java provides a public no-arg constructor. This is fine. Do not add parameterized constructors without also declaring a no-arg one.
**Warning signs:** WARN in logs from `SourceTypeTemplate`; `elements` field is `null` in API response.

---

## Code Examples

Verified patterns from codebase:

### Complete @SourceType annotation (from NessiePluginConfig.java)
```java
// Source: /home/emanuele/IdeaProjects/dremio-oss/plugins/dataplane/src/main/java/com/dremio/plugins/dataplane/store/NessiePluginConfig.java
@SourceType(value = "NESSIE", label = "Nessie", uiConfig = "nessie-layout.json", isVersioned = true)
public class NessiePluginConfig extends AbstractDataplanePluginConfig {
```

### Minimal layout JSON skeleton
```json
{
  "sourceType": "RESTCATALOG",
  "tags": [],
  "metadataRefresh": {
    "datasetDiscovery": false
  },
  "form": {
    "tabs": [
      {
        "name": "General",
        "isGeneral": true,
        "sections": [
          {
            "name": "Connection",
            "elements": [
              {
                "propName": "config.restEndpointUri",
                "validate": { "isRequired": true },
                "errMsg": "Endpoint URI is required"
              }
            ]
          },
          {
            "name": "Namespace Filter",
            "elements": [
              {
                "propName": "config.allowedNamespaces[]",
                "uiType": "value_list",
                "emptyLabel": "No namespaces added (all namespaces visible)",
                "addLabel": "Add namespace",
                "validate": { "isRequired": false }
              },
              {
                "propName": "config.isRecursiveAllowedNamespaces",
                "validate": { "isRequired": false }
              }
            ]
          }
        ]
      },
      {
        "name": "Catalog Properties",
        "sections": [
          {
            "elements": [
              {
                "emptyLabel": "No properties added",
                "addLabel": "Add property",
                "propName": "config.propertyList"
              }
            ]
          },
          {
            "name": "Secret Credentials",
            "elements": [
              {
                "emptyLabel": "No credentials added",
                "addLabel": "Add credential",
                "propName": "config.secretPropertyList"
              }
            ]
          }
        ]
      },
      {
        "name": "Advanced Options",
        "sections": [
          {
            "elements": [
              { "propName": "config.enableAsync" }
            ]
          },
          {
            "name": "Cache Options",
            "checkboxController": "enableAsync",
            "elements": [
              { "propName": "config.isCachingEnabled" },
              { "propName": "config.maxCacheSpacePct" }
            ]
          }
        ]
      }
    ]
  }
}
```

### ConnectionReaderImpl classpath scan (read-only reference)
```java
// Source: /home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/java/com/dremio/exec/catalog/ConnectionReaderImpl.java
protected static Collection<Class<? extends ConnectionConf<?, ?>>> getCandidateSources(ScanResult scanResult) {
  for (Class<?> input : scanResult.getAnnotatedClasses(SourceType.class)) {
    if (Modifier.isAbstract(input.getModifiers())
        || Modifier.isInterface(input.getModifiers())
        || !ConnectionConf.class.isAssignableFrom(input)) {
      logger.warn("Expected a concrete implementation of SourceConf.");
      continue;
    }
    candidates.add((Class<? extends ConnectionConf<?, ?>>) input);
  }
}
```

### SourceTypeTemplate icon and layout loading (read-only reference)
```java
// Source: /home/emanuele/IdeaProjects/dremio-oss/dac/backend/src/main/java/com/dremio/dac/api/SourceTypeTemplate.java
// icon: loaded via classloader at classpath root
final URL resource = sourceClass.getClassLoader().getResource(type.value() + ".svg");
// layout: loaded via classloader at classpath root
final InputStream inputStream = sourceClass.getClassLoader().getResourceAsStream(type.uiConfig());
```

### Existing visibility wiring (already present - read-only)
```java
// Source: /home/emanuele/IdeaProjects/dremio-oss/dac/backend/src/main/java/com/dremio/dac/api/DeprecatedSourceResource.java
case "RESTCATALOG":
  return optionManager.getOption(RESTCATALOG_PLUGIN_ENABLED);
```

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| Manual plugin registry entries | `@SourceType` + `sabot-module.conf` classpath scan | No manual registration needed anywhere |
| Hardcoded form in UI | `uiConfig` layout JSON | Form schema is plugin-owned and classpath-loaded |

**Deprecated/outdated:**
- Legacy `LegacySourceType` enum: exists for backwards compat. New plugins use string-based type. RESTCATALOG does not need an entry here.

---

## Open Questions

1. **Icon field in API response**
   - What we know: `RESTCATALOG.svg` exists in `dac/ui-lib/icons/dremio/sources/RESTCATALOG.svg` but NOT at classpath root in any plugin JAR. The `SourceTypeTemplate` code loads from classloader root → will return `null` for icon field.
   - What's unclear: Whether the success criterion "displays RESTCATALOG.svg icon" requires the API icon field to be non-null, or just that the UI renders it (UI renders from its own bundle regardless).
   - Recommendation: Copy `RESTCATALOG.svg` from `dac/ui-lib/icons/dremio/sources/RESTCATALOG.svg` to `plugins/icebergcatalog/src/main/resources/RESTCATALOG.svg`. This makes the API icon field non-null and is defensive. Cost is near-zero.

2. **`metadataRefresh` settings in layout JSON**
   - What we know: RESTCATALOG plugin uses `MutablePluginConf` but is not a filesystem source.
   - What's unclear: Whether `datasetDiscovery: false` is correct or should be `true` for REST catalog sources.
   - Recommendation: Use `datasetDiscovery: false` (catalog drives schema, not filesystem scanning). This is consistent with catalog-based sources vs. filesystem-based ones.

3. **Integration test coverage**
   - What we know: `TestDeprecatedSourceResource` tests `GET /source/type` and `GET /source/type/{name}` and validates icon loading for `FAKESOURCE`. There are no existing RESTCATALOG-specific wiring tests.
   - What's unclear: Whether the planner should include a smoke test that calls the actual endpoint.
   - Recommendation: Add a unit test in `TestRestIcebergCatalogPluginConfig` or a new test class that verifies `ConnectionReader.getAllConnectionConfs().containsKey("RESTCATALOG")` using `DremioTest.CLASSPATH_SCAN_RESULT`. This is cheap and directly validates WIRE-01.

---

## Sources

### Primary (HIGH confidence)
- Codebase inspection: `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/java/com/dremio/exec/catalog/ConnectionReaderImpl.java` — full classpath scan logic verified
- Codebase inspection: `/home/emanuele/IdeaProjects/dremio-oss/dac/backend/src/main/java/com/dremio/dac/api/SourceTypeTemplate.java` — icon and layout loading verified
- Codebase inspection: `/home/emanuele/IdeaProjects/dremio-oss/dac/backend/src/main/java/com/dremio/dac/api/DeprecatedSourceResource.java` — RESTCATALOG visibility switch already present
- Codebase inspection: `/home/emanuele/IdeaProjects/dremio-oss/plugins/icebergcatalog/src/main/resources/sabot-module.conf` — package already declared
- JAR inspection: `dremio-icebergcatalog-plugin-*.jar`, `dremio-dataplane-plugin-*.jar`, `dremio-s3-plugin-*.jar` — verified no SVGs in plugin JARs; layout JSON at classpath root
- Codebase inspection: `RestIcebergCatalogPluginConfig.java`, `IcebergCatalogPluginConfig.java` — all config fields enumerated
- `jar tf dremio-dac-ui-*.jar | grep RESTCATALOG` — confirmed RESTCATALOG.svg in ui bundle at `rest/dremio_static/static/icons/dremio/sources/RESTCATALOG.svg`

### Secondary (MEDIUM confidence)
- Multiple layout JSON files cross-referenced: `nas-layout.json`, `s3-layout.json`, `nessie-layout.json`, `awsglue-layout.json` — consistent `config.` prefix pattern for propNames

### Tertiary (LOW confidence)
- `metadataRefresh.datasetDiscovery` semantics: inferred from comparison with Nessie vs S3 layouts; not verified against UI source code

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — verified from actual source code and JAR inspection
- Architecture: HIGH — patterns confirmed from 4+ existing plugin implementations
- Pitfalls: HIGH (abstract class skip), HIGH (layout path), MEDIUM (secretPropertyList UI handling)

**Research date:** 2026-02-20
**Valid until:** 2026-03-22 (stable codebase; Dremio plugin wiring pattern is mature)
