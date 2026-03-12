# Phase 21: Configuration and Nessie Detection - Research

**Researched:** 2026-03-09 (forced re-research)
**Domain:** Dremio OSS RESTCATALOG plugin configuration extension + Nessie backend auto-detection
**Confidence:** HIGH

## Summary

Phase 21 is the foundation phase for v1.4 -- it adds an `enableNessie` boolean config field to the RESTCATALOG source, implements auto-detection of Nessie backends by reading the config endpoint response, and discovers the default branch name from server config. This phase makes ZERO behavioral changes to the query execution path. When `enableNessie=false` (the default), absolutely no new code paths execute.

The implementation is straightforward and low-risk. The existing `RestIcebergCatalogPluginConfig` uses protobuf `@Tag` annotations for field serialization; tags 1-5 are taken by the parent `IcebergCatalogPluginConfig`, tags 10-12 are taken by `RestIcebergCatalogPluginConfig`, and tags 13+ are available in the 10-19 range reserved for this class. The Iceberg `RESTCatalog.properties()` method exposes the merged config endpoint response properties, which include `nessie.is-nessie-catalog=true` and `nessie.default-branch.name=main` when the backend is Nessie. The detection logic reads these properties from the already-initialized `RESTCatalog` instance -- no additional HTTP calls are needed beyond the one `RESTCatalog` already makes during `initialize()`.

A critical implementation detail: the `RESTCatalog` is lazily initialized inside `ExpiringCatalogCache` -- it is NOT created during `IcebergCatalogPlugin.start()`. The `start()` method creates the `CatalogAccessor` (which wraps an `ExpiringCatalogCache`), but the actual `RESTCatalog` instance is only created on the first call to `ExpiringCatalogCache.get()`. Detection logic must therefore explicitly trigger catalog initialization by calling through to `getCatalog()`, which in turn calls `ExpiringCatalogCache.get()`.

**Primary recommendation:** Add `enableNessie` as `@Tag(13)` on `RestIcebergCatalogPluginConfig`, perform Nessie detection in `RestIcebergCatalogPlugin.start()` by triggering catalog initialization and reading `RESTCatalog.properties()`, and store the detected default branch name and Nessie status as instance fields for later use by Phases 22-24. No new dependencies are required for this phase.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| CFG-01 | User can enable Nessie mode via `enableNessie` boolean on RESTCATALOG source config (default false) | Add `@Tag(13) public boolean enableNessie = false` to `RestIcebergCatalogPluginConfig` + add "Nessie Options" section to `restcatalog-layout.json`. Tag 13 verified available (10-12 taken). See [Pattern 1](#pattern-1-protobuf-tag-config-field) and [Code Example 3](#adding-config-field-with-tag-annotation). |
| CFG-02 | Plugin auto-detects Nessie backend from config endpoint response (`nessie.is-nessie-catalog=true`) | After `start()` creates the `CatalogAccessor`, explicitly trigger `ExpiringCatalogCache.get()` to initialize the `RESTCatalog`, then call `((RESTCatalog) catalog).properties().get("nessie.is-nessie-catalog")`. The `RESTCatalog` already calls `GET /v1/config` during its `initialize()` and stores the merged properties. See [Pattern 3](#pattern-3-post-start-detection-via-restcatalog-properties) and [Code Example 1](#reading-nessie-properties-from-restcatalog). |
| CFG-03 | Plugin auto-discovers default branch from Nessie server config (`nessie.default-branch.name`) | Read `nessie.default-branch.name` from `RESTCatalog.properties()` at the same point as CFG-02. See [Code Example 2](#reading-default-branch-from-config-properties). |
| CMP-01 | Non-Nessie RESTCATALOG sources are completely unaffected (zero behavioral change when `enableNessie=false`) | Guard all new code behind `if (config.enableNessie)` checks. The default value is `false`, so existing sources never enter new code paths. `IcebergCatalogPlugin.start()` remains unchanged when `enableNessie` is false -- the detection method returns immediately. See [Pitfall 1](#pitfall-1-accidental-activation-on-non-nessie-sources). |
</phase_requirements>

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `iceberg-core` (`RESTCatalog`) | 1.7.0-custom (jar: `iceberg-core-1.7.0-5f7c992`) | Existing REST catalog client; `properties()` exposes merged config endpoint response | Already the core of the RESTCATALOG plugin; `AbstractRestCatalogAccessor.getCatalog()` returns the cached instance |
| Protostuff `@Tag` | Dremio's standard | Config field serialization for `ConnectionConf` subclasses | Used by all Dremio source plugins; tags 1-5 used by `IcebergCatalogPluginConfig`, tags 10-12 by `RestIcebergCatalogPluginConfig` |
| Caffeine cache | 3.x (via Dremio BOM) | Existing table cache in `AbstractRestCatalogAccessor` | Already used; no changes needed this phase |
| SLF4J Logger | 1.7.x (via Dremio BOM) | Logging warnings when Nessie detection fails or properties are absent | Standard Dremio logging, already imported in `IcebergCatalogPlugin` |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `nessie-client` | 0.100.3 (via BOM) | NOT needed this phase; will be needed in Phase 22 for branch resolution | Future phase only |
| `nessie-model` | 0.100.3 (via BOM) | NOT needed this phase | Future phase only |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Reading `RESTCatalog.properties()` for detection | Making a separate HTTP GET to `/iceberg/v1/config` | Unnecessary; the `RESTCatalog` already fetches config during `initialize()` and exposes it via `properties()`. A separate call would duplicate network traffic and bypass the client's merge logic for defaults/overrides. |
| `@Tag` on config class for `enableNessie` | System-level `OptionValidator` (support key) | Config-level is correct; this is a per-source setting, not system-wide. System-level options like `plugins.restcatalog.enabled` control whether the entire source type is available; `enableNessie` controls a per-source behavior. |
| Detecting in plugin constructor | Detecting in `start()` method | Constructor runs before `start()` and before the catalog is created. The `RESTCatalog` is not available until after `start()` calls `createCatalog()`. Constructor-time detection is impossible. |

**No new dependencies needed for Phase 21.**

## Architecture Patterns

### Recommended Project Structure

```
plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/
  RestIcebergCatalogPluginConfig.java    (MODIFIED: add enableNessie field @Tag(13))
  RestIcebergCatalogPlugin.java          (MODIFIED: add Nessie detection in start(), store results)
  IcebergRestCatalogAccessor.java        (UNCHANGED this phase)
  ExpiringCatalogCache.java              (UNCHANGED this phase)
  AbstractRestCatalogAccessor.java       (UNCHANGED this phase)
  IcebergCatalogPlugin.java              (UNCHANGED this phase -- start() calls subclass createCatalog())

plugins/icebergcatalog/src/main/resources/
  restcatalog-layout.json                (MODIFIED: add "Nessie Options" section with enableNessie checkbox)
```

### Pattern 1: Protobuf Tag Config Field

**What:** Adding a new persisted config field to a `ConnectionConf` subclass using the `@Tag` + `@DisplayMetadata` pattern.
**When to use:** Whenever a new user-configurable property needs to persist across restarts.
**Key facts verified from source code:**
- `IcebergCatalogPluginConfig` (parent): Tags 1-5 used (propertyList, secretPropertyList, enableAsync, isCachingEnabled, maxCacheSpacePct)
- `RestIcebergCatalogPluginConfig`: Tags 10-12 used (restEndpointUri, allowedNamespaces, isRecursiveAllowedNamespaces)
- Tag range 10-19 is reserved for `RestIcebergCatalogPluginConfig` (comment at line 30-32 of both classes)
- Tag 13 is the next available

**Key constraint:** Tag numbers MUST be unique within the class hierarchy and MUST NOT be reused. Once a field is serialized with a tag number, changing the tag causes deserialization errors for existing configs.

### Pattern 2: UI Layout JSON Section

**What:** Adding UI fields to the source configuration form in `restcatalog-layout.json`.
**When to use:** Whenever a new config field should be visible in the Dremio UI.
**Existing layout structure (verified):**
- Tab "General" with sections: "Connection" (restEndpointUri), "Namespace Filter" (allowedNamespaces, isRecursiveAllowedNamespaces)
- Tab "Catalog Properties" with sections for propertyList and secretPropertyList
- Tab "Advanced Options" with enableAsync and Cache Options (controlled by `checkboxController: "enableAsync"`)

**Placement for new section:** Add as a new section within the "General" tab, after the "Namespace Filter" section. The `checkboxController` pattern is NOT needed for Phase 21 since we only have a single boolean toggle with no dependent fields (no sub-fields need to appear/disappear based on the toggle). Note: if Phase 22 adds additional Nessie-specific options, a `checkboxController` can be added then.

### Pattern 3: Post-Start Detection via RESTCatalog Properties

**What:** Reading server-provided properties from an already-initialized `RESTCatalog` instance to detect capabilities.
**When to use:** When the server provides metadata about itself in the config endpoint response.

**Critical implementation detail -- Initialization timing:**

```
IcebergCatalogPlugin.start():                    // Line 273-278 of IcebergCatalogPlugin.java
  -> validateOnStart()                           // Checks support key
  -> catalogAccessor = createCatalog(fsConf)     // Creates IcebergRestCatalogAccessor
     -> RestIcebergCatalogPlugin.createCatalog() // Line 146-157
        -> creates Supplier<Catalog> (LAZY)      // Lambda wrapping CatalogUtil.loadCatalog()
        -> new IcebergRestCatalogAccessor(supplier, ...)
           -> new ExpiringCatalogCache(supplier)  // catalog=null, NOT yet initialized
  -> hadoopFs = createFSCache()
  -> isOpen.set(true)

  // At this point, RESTCatalog does NOT exist yet.
  // ExpiringCatalogCache.catalog == null
  // First call to ExpiringCatalogCache.get() will:
  //   1. Call catalogSupplier.get()
  //   2. Which calls CatalogUtil.loadCatalog()
  //   3. Which calls RESTCatalog.initialize()
  //   4. Which calls GET /v1/config to the server
  //   5. Which populates the properties map
```

**Detection must happen AFTER `start()` completes (or at the end of `start()`).**

**Recommended approach:** Override `start()` in `RestIcebergCatalogPlugin`:
```
RestIcebergCatalogPlugin.start():
  -> super.start()                               // Creates catalogAccessor, sets isOpen=true
  -> if (config.enableNessie):
       -> detectNessieBackend()                   // NEW method
          -> getCatalogAccessor()                 // Returns the accessor (isOpen is true)
          -> accessor triggers getCatalog()       // AbstractRestCatalogAccessor.getCatalog()
             -> ExpiringCatalogCache.get()        // Creates RESTCatalog, calls initialize()
          -> cast to RESTCatalog, call properties()
          -> read "nessie.is-nessie-catalog"
          -> read "nessie.default-branch.name"
          -> store in instance fields
```

**Important detail about `IcebergRestCatalogAccessor`:** This class stores TWO references to catalog suppliers:
1. `this.catalogSupplier` (raw, creates new instance each call) -- used in `getDefaultBaseLocation()` and `checkStateInternal()`
2. `super.icebergCatalogSupplier` (the `ExpiringCatalogCache`) -- used in `getCatalog()` for all table operations

For detection, use `getCatalog()` (the cached path), NOT the raw supplier. This avoids creating an extra `RESTCatalog` instance.

**Existing pattern proving this works:** `IcebergRestCatalogAccessor.getDefaultBaseLocation()` (line 61-67) already reads `RESTCatalog.properties()`:
```java
Catalog catalog = catalogSupplier.get();
Preconditions.checkState(catalog instanceof RESTCatalog, "...");
Map<String, String> props = ((RESTCatalog) catalog).properties();
return props.get(DEFAULT_BASE_LOCATION);
```

### Anti-Patterns to Avoid

- **Making a separate HTTP call to detect Nessie:** The `RESTCatalog` already fetches `/v1/config` during `initialize()`. Do not duplicate this call. Read from `properties()` instead.
- **Adding detection logic to the config class:** Detection is runtime behavior, not configuration. The config class (`RestIcebergCatalogPluginConfig`) is a pure data holder with `@Tag` annotations. Keep detection in the plugin class.
- **Validating Nessie detection at config save time:** The REST endpoint may not be reachable when the user saves config. Detection must happen at `start()` time only.
- **Storing mutable state in the config class:** Config classes are immutable data holders. Store detection results (`isNessieDetected`, `defaultBranch`) in `RestIcebergCatalogPlugin` instance fields.
- **Using the raw `catalogSupplier` for detection:** `IcebergRestCatalogAccessor` has a raw `catalogSupplier` field that creates a NEW catalog on each call. Use `getCatalog()` (which goes through `ExpiringCatalogCache`) to avoid creating a throwaway instance. However, note that `getCatalog()` is a `protected` method on `AbstractRestCatalogAccessor` -- the detection code in the plugin will need to access the catalog differently (see Code Examples below).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Config endpoint HTTP call | Custom HTTP client to call `/v1/config` | `RESTCatalog.properties()` after initialization | RESTCatalog already calls the config endpoint internally during `initialize()` and exposes merged properties (defaults + overrides from server) |
| Config persistence | Custom KV store logic for `enableNessie` | `@Tag` annotation on `ConnectionConf` field | Dremio's protostuff serialization handles persistence automatically across restarts |
| Config endpoint response parsing | Custom JSON parser for config response | Properties map from `RESTCatalog.properties()` | The Iceberg client already parses the JSON response and merges defaults/overrides per the REST catalog spec |
| Property key constants | Hardcoded string literals scattered through code | Constants in a shared class (or inline with comments) | `"nessie.is-nessie-catalog"` and `"nessie.default-branch.name"` should be defined as constants for reuse in Phases 22-24 |

**Key insight:** The Iceberg `RESTCatalog` client does all the heavy lifting for config endpoint interaction. Phase 21 just needs to read from the already-populated properties map. The `RESTSessionCatalog.initialize()` method fetches `GET /v1/config`, receives `{ "defaults": {...}, "overrides": {...} }`, merges these with client-provided properties (overrides win), and stores the result. `RESTCatalog.properties()` delegates to `RESTSessionCatalog.properties()` which delegates to `BaseSessionCatalog.properties()` returning this merged map.

## Common Pitfalls

### Pitfall 1: Accidental Activation on Non-Nessie Sources

**What goes wrong:** If Nessie detection code runs unconditionally (not gated behind `enableNessie`), non-Nessie RESTCATALOG sources (Polaris, Gravitino, Lakekeeper in non-Nessie mode) could be affected. The detection code might try to read Nessie-specific properties that don't exist, or worse, the initialization could add overhead.
**Why it happens:** Developer adds detection code to `start()` without checking the `enableNessie` flag first.
**How to avoid:** The FIRST line of `detectNessieBackend()` MUST be `if (!config.enableNessie) return;`. Additionally, every piece of new code in Phase 21 must be gated behind the `enableNessie` flag.
**Warning signs:** Any new code path that executes without an `enableNessie` check. The test for CMP-01 should verify that `start()` on a non-Nessie source does NOT trigger `ExpiringCatalogCache.get()` eagerly.
**Verification:** Start a RESTCATALOG source with `enableNessie=false` (default). Confirm no new log messages, no new network calls, no new fields populated.

### Pitfall 2: Tag Number Collision

**What goes wrong:** Using a tag number already taken by another field in the class hierarchy causes protostuff deserialization errors. Existing configs become unreadable.
**Why it happens:** The tag range 10-19 is reserved for `RestIcebergCatalogPluginConfig`, but someone could accidentally use a tag that conflicts with a field added in a parallel branch.
**How to avoid:** Use tag 13 (verified available -- tags 10, 11, 12 are taken by `restEndpointUri`, `allowedNamespaces`, `isRecursiveAllowedNamespaces`). Document the tag allocation in the code comment at the top of the class (existing comment on lines 30-32 already shows the range convention).
**Warning signs:** Deserialization errors on startup after adding the field. Existing RESTCATALOG sources failing to load.

### Pitfall 3: Properties Not Available Because Catalog Not Yet Initialized

**What goes wrong:** Calling `RESTCatalog.properties()` before `RESTCatalog.initialize()` has completed returns an empty or null map. The `ExpiringCatalogCache` uses lazy initialization -- the `RESTCatalog` is created by its `Supplier` only when first accessed via `.get()`.
**Why it happens:** Developer assumes the catalog exists after `createCatalog()` returns. In reality, `createCatalog()` only creates the `CatalogAccessor` wrapper with a lazy `Supplier<Catalog>`. The actual `RESTCatalog` is not created until `ExpiringCatalogCache.get()` is called.
**How to avoid:** In the detection method, explicitly trigger catalog initialization by calling something that invokes `getCatalog()` on the accessor. Since `AbstractRestCatalogAccessor.getCatalog()` is `protected`, the detection code in the plugin should access properties through the accessor's public API or by casting. The simplest approach: access properties through the accessor since `IcebergRestCatalogAccessor.getDefaultBaseLocation()` already demonstrates this pattern with `((RESTCatalog) catalogSupplier.get()).properties()`.
**Warning signs:** `NullPointerException` or empty properties map when reading Nessie config values.

### Pitfall 4: Config Endpoint Does Not Return Nessie Properties (Non-Nessie Backend)

**What goes wrong:** When `enableNessie=true` but the REST catalog backend is NOT Nessie (e.g., Polaris, Gravitino, Unity), `properties().get("nessie.is-nessie-catalog")` returns `null`. If the code does not handle this gracefully, it could throw NPE or enter an inconsistent state.
**Why it happens:** The `enableNessie` flag is user-set. Users might enable it by mistake on a non-Nessie source.
**How to avoid:** After reading properties, check for `"nessie.is-nessie-catalog"` == `"true"` (String comparison, not boolean). If absent or not `"true"`, log a WARNING and set the internal `isNessieDetected` flag to `false`. Do NOT throw an error -- the source should still work normally as a non-Nessie REST catalog. Log clearly: "enableNessie is true but backend does not appear to be Nessie (nessie.is-nessie-catalog not found in config response). Nessie features will be disabled."
**Warning signs:** Source enters bad state when `enableNessie=true` on a non-Nessie backend.

### Pitfall 5: Default Branch Name Assumption

**What goes wrong:** Hardcoding `"main"` as the default branch instead of reading it from the server.
**Why it happens:** Most Nessie deployments use `main` as default, so it seems safe to hardcode.
**How to avoid:** Always read `nessie.default-branch.name` from `RESTCatalog.properties()`. If not present, fall back to `"main"` with a warning log. Never skip the server lookup.
**Warning signs:** Tests pass with Nessie's default config but fail when the server is configured with a different default branch (e.g., `"production"`).

### Pitfall 6: Detection Failure Prevents Source From Starting

**What goes wrong:** If detection throws an unexpected exception (e.g., network timeout, server error during `ExpiringCatalogCache.get()`), the entire `start()` method fails and the source goes into a bad state.
**Why it happens:** The detection code triggers catalog initialization, which involves a network call to `GET /v1/config`. If the server is temporarily unreachable during startup, this call will fail.
**How to avoid:** Wrap the entire detection block in a try-catch. If detection fails, log a WARNING and set `isNessieDetected = false`, `defaultBranch = "main"`. Do NOT let detection failure prevent the source from starting. The source should still be usable; detection can be retried later (or the catalog will be re-initialized by `ExpiringCatalogCache` on the next access after expiry).
**Warning signs:** RESTCATALOG source fails to start with network errors related to `/v1/config` when `enableNessie=true`.

## Code Examples

Verified patterns from codebase analysis:

### Reading Nessie Properties from RESTCatalog

```java
// Source: Verified via bytecode decompilation of RESTCatalog (iceberg-core-1.7.0)
//
// Chain: RESTCatalog.properties()
//   -> RESTSessionCatalog.properties()
//   -> BaseSessionCatalog.properties()
//   -> returns the merged properties map set during initialize()
//
// RESTSessionCatalog.initialize() merges properties as follows:
//   1. Client-provided properties (lowest priority)
//   2. Server config endpoint "defaults" (medium priority)
//   3. Server config endpoint "overrides" (highest priority)
//
// For Nessie, the "overrides" section includes:
//   "nessie.is-nessie-catalog" = "true"
//   "nessie.default-branch.name" = "main" (or whatever the server's default is)
//   "nessie.prefix-pattern" = "{ref}|{warehouse}"
//   "nessie.core-base-uri" = "http://host:19120/api/"
//   "nessie.iceberg-base-uri" = "http://host:19120/iceberg/"
//   "nessie.catalog-base-uri" = "http://host:19120/catalog/v1/"
//
// IMPORTANT: properties() returns String values, not typed values.
// "nessie.is-nessie-catalog" is the STRING "true", not a boolean.

// In RestIcebergCatalogPlugin, after the catalog accessor is created:
private void detectNessieBackend() {
    if (!((RestIcebergCatalogPluginConfig) config).enableNessie) {
        return; // CMP-01: no new code runs when enableNessie=false
    }

    try {
        // Trigger lazy catalog initialization
        // This calls ExpiringCatalogCache.get() -> CatalogUtil.loadCatalog()
        // -> RESTCatalog.initialize() -> GET /v1/config
        CatalogAccessor accessor = getCatalogAccessor();
        // Need to get the underlying RESTCatalog to read properties
        // Use the accessor's catalog access pattern
        // (see Code Example 4 for the recommended approach)
        Map<String, String> props = getRestCatalogProperties(accessor);

        String isNessieCatalog = props.get("nessie.is-nessie-catalog");
        if ("true".equals(isNessieCatalog)) {
            this.isNessieDetected = true;
            String branch = props.get("nessie.default-branch.name");
            if (branch == null || branch.isEmpty()) {
                logger.warn("Nessie backend detected but default branch name not found. "
                    + "Falling back to 'main'.");
                branch = "main";
            }
            this.defaultBranch = branch;
            logger.info("Nessie backend detected. Default branch: {}", this.defaultBranch);
        } else {
            logger.warn("enableNessie=true but backend does not appear to be Nessie "
                + "(nessie.is-nessie-catalog not found in config response). "
                + "Nessie features will be disabled.");
            this.isNessieDetected = false;
        }
    } catch (Exception e) {
        logger.warn("Failed to detect Nessie backend. Nessie features will be disabled.", e);
        this.isNessieDetected = false;
        this.defaultBranch = "main";
    }
}
```

### Reading Default Branch from Config Properties

```java
// Source: Nessie config endpoint response structure
// (verified via GitHub issue #9224 and official Nessie documentation)

String defaultBranch = props.get("nessie.default-branch.name");
if (defaultBranch == null || defaultBranch.isEmpty()) {
    logger.warn("Nessie backend detected but default branch name not found in config. "
        + "Falling back to 'main'.");
    defaultBranch = "main";
}
```

### Adding Config Field with Tag Annotation

```java
// Source: Existing pattern in RestIcebergCatalogPluginConfig.java (tags 10-12)
// and IcebergCatalogPluginConfig.java (tags 1-5)

// Tags 1-5:  IcebergCatalogPluginConfig (parent)
// Tags 10-12: RestIcebergCatalogPluginConfig (existing)
// Tag 13: NEW - enableNessie

@Tag(13)
@DisplayMetadata(label = "Enable Nessie Version Control")
public boolean enableNessie = false;
```

### Accessing RESTCatalog Properties from the Plugin

```java
// The challenge: RESTCatalog.properties() requires casting the Catalog to RESTCatalog.
// AbstractRestCatalogAccessor.getCatalog() is protected, not accessible from the plugin.
//
// Two approaches:
//
// Approach A: Add a method to CatalogAccessor interface or IcebergRestCatalogAccessor
//   (Clean but adds API surface)
//
// Approach B: Use the existing createRestCatalog() supplier pattern
//   RestIcebergCatalogPlugin already has access to createRestCatalog(config) which
//   returns a Supplier<Catalog>. We can store this supplier and use it.
//   BUT this creates a NEW catalog instance (expensive).
//
// Approach C (RECOMMENDED): Add a package-private method to IcebergRestCatalogAccessor
//   that returns the properties from the cached catalog.

// In IcebergRestCatalogAccessor.java:
Map<String, String> getRestCatalogProperties() {
    Catalog catalog = getCatalog(); // Uses ExpiringCatalogCache (lazy init, cached)
    Preconditions.checkState(
        catalog instanceof RESTCatalog, "Catalog is not an instance of RESTCatalog");
    return ((RESTCatalog) catalog).properties();
}

// In RestIcebergCatalogPlugin.java (same package):
private Map<String, String> getRestCatalogProperties(CatalogAccessor accessor) {
    Preconditions.checkState(
        accessor instanceof IcebergRestCatalogAccessor,
        "Expected IcebergRestCatalogAccessor");
    return ((IcebergRestCatalogAccessor) accessor).getRestCatalogProperties();
}
```

### Existing Pattern: getDefaultBaseLocation() Already Reads RESTCatalog Properties

```java
// Source: IcebergRestCatalogAccessor.java, lines 61-67
// This existing code demonstrates the pattern of reading RESTCatalog.properties()
// NOTE: This uses the RAW catalogSupplier (creates new instance each time),
// NOT the ExpiringCatalogCache. For detection, prefer the cached approach.

@Override
public String getDefaultBaseLocation() {
    Catalog catalog = catalogSupplier.get();  // RAW supplier, creates new instance
    Preconditions.checkState(
        catalog instanceof RESTCatalog, "Catalog is not an instance of RESTCatalog");
    Map<String, String> props = ((RESTCatalog) catalog).properties();
    return props.get(DEFAULT_BASE_LOCATION);
}
```

### UI Layout JSON for New Section

```json
{
  "name": "Nessie Options",
  "elements": [
    {
      "propName": "config.enableNessie",
      "validate": { "isRequired": false }
    }
  ]
}
```

**Placement:** Insert this section after the "Namespace Filter" section within the "General" tab of `restcatalog-layout.json`.

### Overriding start() in RestIcebergCatalogPlugin

```java
// Source: IcebergCatalogPlugin.start() (lines 273-278) does:
//   validateOnStart() -> catalogAccessor = createCatalog(fsConf) -> hadoopFs = createFSCache() -> isOpen.set(true)
//
// RestIcebergCatalogPlugin should override start() to add detection AFTER super.start():

@Override
public void start() throws IOException {
    super.start();  // Creates catalogAccessor, sets isOpen=true
    detectNessieBackend();  // Reads properties from RESTCatalog (lazily initialized)
}
```

**Note:** `IcebergCatalogPlugin.start()` is NOT declared `final`, so it can be overridden.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Separate `NessiePlugin` for Nessie access | v1.4 extends `RESTCATALOG` to be Nessie-aware | v1.4 (current) | Reuses existing REST catalog infrastructure instead of duplicating the entire native Nessie client stack |
| Manual branch config in source settings | Auto-detect from config endpoint | v1.4 (current) | No user configuration needed for default branch; user only sets `enableNessie=true` |
| `isVersioned=true` on source type annotation | Runtime `isWrapperFor()` check based on `enableNessie` flag | v1.4 (this decision) | Keeps non-Nessie RESTCATALOG sources unaffected; new `SupportsBranchAwareRestCatalog` interface in Phase 22 |

**Deprecated/outdated:**
- None for this phase -- all code changes are additive.

## Open Questions

1. **What happens if RESTCatalog.properties() does not include Nessie overrides?**
   - What we know: Nessie 0.100.3 includes `nessie.is-nessie-catalog` and `nessie.default-branch.name` in its config endpoint overrides. The Iceberg `RESTSessionCatalog.initialize()` merges these into the properties map.
   - What's unclear: Whether ALL versions of Nessie include these properties. Older Nessie versions (pre-0.70) may not.
   - Recommendation: Handle gracefully -- if properties are absent, log a warning and treat as if Nessie is not detected. Do not crash. This is a safe default.

2. **Should we validate enableNessie=true against actual Nessie detection during start()?**
   - What we know: The user sets `enableNessie=true` manually. The backend might not actually be Nessie.
   - What's unclear: Whether a mismatch should be an error (source fails to start) or a warning (source starts but Nessie features disabled).
   - Recommendation: Warning, not error. Log clearly: "enableNessie is set to true but the backend does not appear to be a Nessie catalog. Nessie features will be disabled." This avoids breaking sources that the user misconfigured. The `isNessieDetected` field captures the actual runtime status independently of the user's `enableNessie` setting.

3. **How are the detected Nessie properties exposed to later phases (22-24)?**
   - What we know: Phase 22 needs the default branch name. Phase 23 needs to know if Nessie is active.
   - What's unclear: The exact API shape for exposing this state.
   - Recommendation: Add `isNessieDetected()` and `getDefaultBranch()` methods to `RestIcebergCatalogPlugin`. These will be used by Phase 22 when implementing `SupportsBranchAwareRestCatalog`. The methods should be `public` since they will be called from `CatalogImpl` (in the `sabot/kernel` module) after `isWrapperFor()` check.

4. **Should Nessie property key strings be constants?**
   - What we know: The keys `"nessie.is-nessie-catalog"`, `"nessie.default-branch.name"` will be used in multiple phases.
   - Recommendation: Define them as constants in `RestIcebergCatalogPlugin` (or a new small utility class) for reuse. This prevents typos and makes grep-ability easy.

## Sources

### Primary (HIGH confidence)
- `RestIcebergCatalogPluginConfig.java` -- current config class, tag allocation verified (tags 10-12 used for restEndpointUri/allowedNamespaces/isRecursiveAllowedNamespaces, tag 13 available)
- `RestIcebergCatalogPlugin.java` -- plugin lifecycle, `createCatalog()` and `createRestCatalog()` flow, constructor stores `restEndpoint` from config
- `IcebergCatalogPlugin.java` -- parent class, `start()` lifecycle (lines 273-278: validateOnStart, createCatalog, createFSCache, isOpen), `getCatalogAccessor()` accessor (lines 163-171: checks isOpen then returns catalogAccessor)
- `IcebergCatalogPluginConfig.java` -- parent config, tags 1-5 used (propertyList, secretPropertyList, enableAsync, isCachingEnabled, maxCacheSpacePct)
- `IcebergRestCatalogAccessor.java` -- existing `getDefaultBaseLocation()` demonstrates `RESTCatalog.properties()` usage pattern (lines 61-67); constructor passes raw supplier + ExpiringCatalogCache to super
- `ExpiringCatalogCache.java` -- lazy initialization pattern: constructor sets `catalog=null`, `expirationNanos=0`; `get()` checks expiration and calls supplier on first/expired access
- `AbstractRestCatalogAccessor.java` -- `getCatalog()` (line 159) delegates to `icebergCatalogSupplier.get()` which is the ExpiringCatalogCache; table/view caches use Caffeine
- `IcebergCatalogPluginOptions.java` -- system-level options for RESTCATALOG plugin (RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS default 1800s, etc.)
- `restcatalog-layout.json` -- current UI layout with 3 tabs (General, Catalog Properties, Advanced Options); `checkboxController` pattern used for Cache Options
- `Wrapper.java` (common/core) -- `isWrapperFor()` / `unwrap()` interface: default impl uses `instanceof` / `clazz.cast(this)`. StoragePlugin extends Wrapper.
- `CatalogImpl.java` -- lines 794-805: `getTableSnapshotHelper()` dispatches on `isWrapperFor(VersionedPlugin.class)`; lines 883-896: `getTableSnapshotForNonVersionedSource()` throws "Source does not support AT BRANCH/TAG/COMMIT" when `!context.isTimeTravelType()`; lines 989-1001: `getDatasetHandleHelper()` has same dispatch pattern
- `TableVersionContext.java` / `TableVersionType.java` -- `isTimeTravelType()` returns true only for SNAPSHOT_ID and TIMESTAMP; BRANCH/TAG/COMMIT/REFERENCE are NOT time travel types

### Secondary (MEDIUM confidence)
- [Nessie GitHub Issue #9224](https://github.com/projectnessie/nessie/issues/9224) -- config endpoint response structure with `nessie.is-nessie-catalog`, `nessie.default-branch.name`, `nessie.core-base-uri`
- [Nessie Iceberg REST Configuration Guide](https://projectnessie.org/guides/iceberg-rest/) -- prefix format, URI conventions
- Bytecode decompilation of `RESTCatalog`, `RESTSessionCatalog`, `BaseSessionCatalog` from `iceberg-core-1.7.0-5f7c992`: confirmed `RESTCatalog.properties()` -> `RESTSessionCatalog.properties()` -> `BaseSessionCatalog.properties()` which returns the merged properties map

### Tertiary (LOW confidence)
- Exact list of Nessie versions that include `nessie.is-nessie-catalog` in config response -- needs runtime verification against specific server versions

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- zero new dependencies, all existing code patterns verified directly from source
- Architecture: HIGH -- pure config addition + property reading from already-initialized RESTCatalog, no behavioral changes to query path
- Pitfalls: HIGH -- all pitfalls identified from direct codebase analysis of initialization timing, catalog lifecycle, and CatalogImpl dispatch logic
- Code examples: HIGH -- verified from source code line numbers and existing pattern in `IcebergRestCatalogAccessor.getDefaultBaseLocation()`

**Research date:** 2026-03-09
**Valid until:** 2026-04-09 (30 days -- stable domain, config patterns don't change)
