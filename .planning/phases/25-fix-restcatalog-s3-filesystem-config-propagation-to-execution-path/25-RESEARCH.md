# Phase 25: Fix RESTCATALOG S3 Filesystem Config Propagation — Research

**Researched:** 2026-03-11
**Domain:** Dremio RESTCATALOG plugin lifecycle, Hadoop Configuration management, S3 filesystem access path
**Confidence:** HIGH (all findings from direct codebase inspection)

## Summary

The bug is a lifecycle ordering problem in `IcebergCatalogPlugin`. The `fsConfAdapter` (which backs `getFsConfCopy()` and the `DatasetFileSystemCache`) is initialized in the **constructor** via `initializeFileSystemConfigurationAdapter()`. This method creates a bare Hadoop `Configuration` with standard filesystem class registrations and S3 performance settings (via `FileSystemConfUtil.S3_PROPS`), but it **does not** merge the plugin's `configPropertyList` (user-supplied catalog properties like `fs.s3a.endpoint`, `fs.s3a.access.key`).

The user-supplied properties from `configPropertyList` are merged into a `config` object inside `buildCatalogProperties()`, which is called lazily inside the `Supplier<Catalog>` lambda. That lambda is only invoked by `ExpiringCatalogCache.get()` when the catalog is first accessed — which happens asynchronously after `start()`. Because `DatasetFileSystemCache` is created in `start()` and uses `getFsConfCopy()` (a snapshot of `fsConfAdapter.getConfiguration()`), FS instances created before the first catalog access will have an incomplete configuration — missing the S3 endpoint, credentials, and path-style access flag required to reach MinIO/SeaweedFS.

The fix follows the exact pattern of `FileSystemPlugin.initializeFsConf()`: add an eager property-merging step in `IcebergCatalogPlugin.start()` (via a protected overridable hook) that runs **before** `createFSCache()`. `RestIcebergCatalogPlugin` provides its `configPropertyList` via the hook. This guarantees that `getFsConfCopy()` always returns a fully-populated configuration.

**Primary recommendation:** Add `protected List<Property> getConfigProperties()` hook to `IcebergCatalogPlugin`, override in `RestIcebergCatalogPlugin` to return `configPropertyList`, and call property merging in `start()` before `createFSCache()`. Add a unit test verifying that properties from `configPropertyList` appear in `getFsConfCopy()` immediately after `start()`.

## Architecture Patterns

### Verified Call Chain (from code inspection)

**Construction (wrong time — too early for properties):**
```
IcebergCatalogPlugin constructor
  -> initializeFileSystemConfigurationAdapter(optionManager)
     -> new Configuration()
     -> initializeHadoopConf(fsConf, optionManager)  // sets fs.classpath, S3_PROPS, WASB_PROPS etc.
     -> new IcebergCatalogFileSystemConfigurationAdapter(fsConf)
  // configPropertyList NOT YET available (subclass not constructed yet)
```

**start() sequence (current — broken):**
```
IcebergCatalogPlugin.start()
  1. validateOnStart()
  2. createCatalog(fsConfAdapter.getConfiguration())  // passes actual Configuration object
     -> RestIcebergCatalogPlugin.createCatalog(config)
        -> createRestCatalog(config)
           -> returns Supplier<Catalog> lambda (NOT called yet!)
              lambda body: buildCatalogProperties(config)  // lazy
  3. createFSCache()  // DatasetFileSystemCache with (noop) -> getFsConfCopy()
     // getFsConfCopy() = new Configuration(fsConfAdapter.getConfiguration())
     // fsConfAdapter.getConfiguration() DOES NOT YET have configPropertyList properties!
  4. isOpen.set(true)
```

**First catalog access (lazy — after start()):**
```
ExpiringCatalogCache.get()
  -> catalogSupplier.get()
     -> buildCatalogProperties(config)  // config is SAME object as fsConfAdapter.getConfiguration()
        -> config.set(p.name, p.value) for each p in configPropertyList  // writes to fsConfAdapter
```

**Consequence:** If `DatasetFileSystemCache.load()` is called between `start()` and first catalog access, `getFsConfCopy()` returns config WITHOUT `fs.s3a.endpoint`, `fs.s3a.access.key`, etc. The S3 filesystem tries AWS default credential chain, fails with: "Credentials for the Storage Provider must be valid and have required access" or "Invalid AWSCredentialsProvider".

### The Correct Pattern (FileSystemPlugin.initializeFsConf)

```java
// FileSystemPlugin.java — reference pattern
private void initializeFsConf() {
    fsConf.set(ExecConstants.ENABLE_S3_V2_CLIENT.getOptionName(), ...);
    List<Property> properties = getProperties();  // hook method
    if (properties != null) {
        for (Property prop : properties) {
            fsConf.set(prop.name, prop.value);
        }
    }
    // ...
}

@Override
public void start() throws IOException {
    initializeFsConf();  // FIRST — before any FS creation
    // ... rest of start()
}

protected List<Property> getProperties() {
    return config.getProperties();  // config is the concrete PluginConf
}
```

### Fix: Hook Method + Eager Property Merging

**Step 1:** Add hook to `IcebergCatalogPlugin`:

```java
// IcebergCatalogPlugin.java — new protected hook
/**
 * Returns plugin-specific properties to merge into the Hadoop Configuration
 * during start(), before the filesystem cache is created. Subclasses override
 * to supply their configPropertyList.
 * Base implementation returns empty list (no extra properties).
 */
protected List<Property> getConfigProperties() {
    return Collections.emptyList();
}

// New method (protected, callable from start())
protected void mergeConfigPropertiesIntoFsConf() {
    List<Property> properties = getConfigProperties();
    if (properties != null && !properties.isEmpty()) {
        Configuration conf = fsConfAdapter.getConfiguration();
        for (Property p : properties) {
            conf.set(p.name, p.value);
        }
    }
}
```

**Step 2:** Override in `RestIcebergCatalogPlugin`:

```java
// RestIcebergCatalogPlugin.java
@Override
protected List<Property> getConfigProperties() {
    return configPropertyList;  // already built from propertyList + secretPropertyList in constructor
}
```

**Step 3:** Call in `IcebergCatalogPlugin.start()` before `createFSCache()`:

```java
@Override
public void start() throws IOException {
    validateOnStart();
    mergeConfigPropertiesIntoFsConf();  // NEW — eagerly merge before FS cache creation
    catalogAccessor = createCatalog(fsConfAdapter.getConfiguration());
    hadoopFs = createFSCache();
    isOpen.set(true);
}
```

**Note:** `buildCatalogProperties()` in `RestIcebergCatalogPlugin` already calls `config.set()` inside its lambda. This becomes harmless redundancy after the fix — keep it as-is for backward compatibility.

### Two S3 Access Paths (Both Fixed by This Change)

| Path | Code | Needs Properties |
|------|------|-----------------|
| Planning-time FS | `DatasetFileSystemCache` → `getFsConfCopy()` → Hadoop `FileSystem.getFileSystemClass(scheme, fsConf)` | `fs.s3a.endpoint`, `fs.s3a.access.key`, `fs.s3a.secret.key`, `fs.s3a.path.style.access` |
| Execution-time FileIO | `createFS()` → `DatasetFileSystemCache.load()` → same `getFsConfCopy()` | Same as above |

Both paths go through `getFsConfCopy()` → `fsConfAdapter.getConfiguration()`. One fix covers both.

### S3 Property Naming Clarification

Two distinct property namespaces are involved:

| Namespace | Example | Used By |
|-----------|---------|---------|
| `s3.*` (Iceberg) | `s3.access-key-id`, `s3.endpoint` | Iceberg REST catalog client for credential vending |
| `fs.s3a.*` (Hadoop) | `fs.s3a.access.key`, `fs.s3a.endpoint` | Hadoop S3AFileSystem (Dremio's DatasetFileSystemCache) |

Users must configure **both** in `configPropertyList` if they want the RESTCATALOG source to:
1. Authenticate to the REST catalog server (Iceberg properties)
2. Read Iceberg data files directly from S3 (Hadoop properties)

The fix ensures **all** `configPropertyList` entries (both namespaces) reach `fsConfAdapter` eagerly.

### fsConfAdapter Accessibility

`fsConfAdapter` is `private final` in `IcebergCatalogPlugin`. `RestIcebergCatalogPlugin` cannot access it directly. The hook/merge pattern keeps the mutation inside `IcebergCatalogPlugin`, which has access. Subclasses only need to provide the list via `getConfigProperties()`.

## Key Files and Their Roles

| File | Role | Change Needed |
|------|------|---------------|
| `IcebergCatalogPlugin.java` | Base plugin with `start()`, `fsConfAdapter`, `createFSCache()` | Add `getConfigProperties()` hook + `mergeConfigPropertiesIntoFsConf()` + call in `start()` |
| `RestIcebergCatalogPlugin.java` | Has `configPropertyList` from user-supplied properties | Override `getConfigProperties()` to return `configPropertyList` |
| `TestRestIcebergCatalogPlugin.java` | Unit tests | Add test: `configPropertyList` properties appear in `getFsConfCopy()` after `start()` |
| `IcebergCatalogFileSystemConfigurationAdapter.java` | Wrapper around Hadoop Configuration | No change needed |
| `DatasetFileSystemCache.java` | Creates FS instances from config | No change needed |
| `DremioFileIO.java` | Execution-time FileIO | No change needed |

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Lazy property merging via catalog access | Complex catalog initialization hooks | Simple eager merge in `start()` | Simpler, predictable, mirrors FileSystemPlugin |
| Property validation | Custom S3 property validator | None — pass through as-is | Properties are user-configured; same approach as FileSystemPlugin |

## Common Pitfalls

### Pitfall 1: Ordering of super.start() in RestIcebergCatalogPlugin

**What goes wrong:** If the merge is added only to `IcebergCatalogPlugin.start()`, and `RestIcebergCatalogPlugin.start()` calls `super.start()`, the merge happens correctly. But if someone adds property merging AFTER `super.start()` in the subclass, the FS cache is already created without properties.

**How to avoid:** The merge MUST be inside `IcebergCatalogPlugin.start()` (the base), before `createFSCache()`. The hook (`getConfigProperties()`) provides the list.

### Pitfall 2: buildCatalogProperties still modifies config — is that a problem?

**What goes wrong:** After the fix, `buildCatalogProperties()` still calls `config.set()` lazily. This is now redundant (properties already set in start()) but harmless. Could cause confusion in code review.

**How to avoid:** Add a comment explaining the redundancy is intentional for backward compatibility. Do NOT remove `config.set()` from `buildCatalogProperties()` — it makes `createBranchScopedAccessor()` work correctly (branch accessors use a copy, not the main fsConf).

### Pitfall 3: createBranchScopedAccessor uses getFsConfCopy()

`createBranchScopedAccessor()` calls `getFsConfCopy()` which creates a new copy at call time. After the fix, this copy already has all properties. The branch accessor's `buildCatalogProperties()` then sets them again (redundant but harmless). The branch accessor's `Supplier<Catalog>` captures the copy, so its config is independent from `fsConfAdapter`. This is correct behavior.

### Pitfall 4: Test setup bypasses real start() via mock overrides

**What goes wrong:** `TestRestIcebergCatalogPlugin` uses `RestIcebergCatalogPluginMock` which overrides `createCatalog()` and `getHadoopFileSystemCache()`. If `getConfigProperties()` is added but not called through `start()`, the unit test for property propagation would need to call `plugin.start()` (which it already does in `setUp()`).

**How to avoid:** The new unit test should construct a plugin with a non-empty `propertyList`, call `start()`, then assert `getFsConfCopy().get("fs.s3a.endpoint")` equals the expected value. This follows the same pattern as `testEnableS3V2ClientIsPropagated`.

## Test Strategy

### New Unit Test (TestRestIcebergCatalogPlugin)

```java
@Test
public void testConfigPropertyListIsPropagatedToFsConf() throws Exception {
    RestIcebergCatalogPluginConfig config = new RestIcebergCatalogPluginConfig();
    config.propertyList = new ArrayList<>();
    Property ep = new Property();
    ep.name = "fs.s3a.endpoint";
    ep.value = "http://minio:9000";
    config.propertyList.add(ep);
    config.secretPropertyList = new ArrayList<>();
    when(mockPluginConfig...) // use standard mocks

    RestIcebergCatalogPlugin testPlugin = new RestIcebergCatalogPluginMock(config, ...);
    testPlugin.start();

    Configuration conf = testPlugin.getFsConfCopy();
    assertThat(conf.get("fs.s3a.endpoint")).isEqualTo("http://minio:9000");
}
```

This test FAILS before the fix, PASSES after. It directly verifies the bug is resolved.

### Optional: SecretPropertyList Test

Verify that properties from `secretPropertyList` are also propagated (they're merged into `configPropertyList` by `getConfigPropertyList()` in the constructor).

### No New IT Tests Needed

The existing `ITRestIcebergCatalogBranchAware` tests do not exercise `DatasetFileSystemCache` directly (they test catalog operations, not data file reads). The S3 filesystem propagation fix would only be visibly tested by a full end-to-end query against MinIO data files, which is out of scope for this phase. The unit test above is sufficient.

## Scope Boundaries

**In scope:**
- `IcebergCatalogPlugin.start()` — add hook call + merge method
- `RestIcebergCatalogPlugin` — override `getConfigProperties()`
- `TestRestIcebergCatalogPlugin` — add unit test for property propagation

**Out of scope:**
- Changes to `DatasetFileSystemCache`, `DremioFileIO`, `IcebergCatalogFileSystemConfigurationAdapter`
- Full end-to-end integration test with actual S3 data reads
- Changes to `buildCatalogProperties()` (leave existing behavior intact)
- Adding fs.s3a.* ↔ s3.* property translation (out of scope — user responsibility)

## State of the Art

| Aspect | Current (Buggy) | Fixed |
|--------|-----------------|-------|
| Property merging timing | Lazy (inside `Supplier<Catalog>` lambda, first catalog access) | Eager (in `start()`, before `createFSCache()`) |
| Pattern | Ad-hoc via `buildCatalogProperties()` side effects | Explicit `initializeFsConf()`-style hook, mirrors `FileSystemPlugin` |
| Test coverage | No test for property propagation to fsConf | Unit test verifies `getFsConfCopy()` contains configPropertyList entries |

## Sources

### Primary (HIGH confidence)
- Direct inspection: `/home/filippo/PycharmProjects/dremio-oss/plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergCatalogPlugin.java`
- Direct inspection: `/home/filippo/PycharmProjects/dremio-oss/plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java`
- Direct inspection: `/home/filippo/PycharmProjects/dremio-oss/sabot/kernel/src/main/java/com/dremio/exec/store/dfs/FileSystemPlugin.java` (reference pattern)
- Direct inspection: `/home/filippo/PycharmProjects/dremio-oss/plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/dfs/DatasetFileSystemCache.java`
- Direct inspection: `/home/filippo/PycharmProjects/dremio-oss/plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/ExpiringCatalogCache.java`
- Direct inspection: `/home/filippo/PycharmProjects/dremio-oss/plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergCatalogPluginConfig.java`

## Metadata

**Confidence breakdown:**
- Bug root cause: HIGH — verified by tracing exact code paths through constructor, start(), and lazy supplier
- Fix approach: HIGH — mirrors FileSystemPlugin.initializeFsConf() exactly
- Test strategy: HIGH — follows existing testEnableS3V2ClientIsPropagated pattern

**Research date:** 2026-03-11
**Valid until:** Until IcebergCatalogPlugin.start() or RestIcebergCatalogPlugin refactoring changes the lifecycle
