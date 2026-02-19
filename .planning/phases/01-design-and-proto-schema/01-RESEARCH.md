# Phase 1: Design and Proto Schema - Research

**Researched:** 2026-02-17
**Domain:** Proto3 schema design, Dremio KV store patterns, config-file feature flags
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Package Namespace**
- All RBAC code lives inside the existing kernel module (`sabot/kernel`), not a new `services/rbac` module
- EE isolation required: use distinct key prefixes and non-overlapping packages to avoid conflicts with Dremio Enterprise Edition's own RBAC
- Class naming: Domain names only (RoleStore, not RbacRoleStore)

**KV Store Key Format**
- Composite key separator: pipe `|` (validated safe for UUIDs, dot-paths, privilege names)
- Store name prefix: `oss_rbac_` for all three stores (`oss_rbac_roles`, `oss_rbac_grants`, `oss_rbac_memberships`)
- Role IDs: slugified from role name (e.g., role name "analyst" -> role ID "analyst"), not UUIDs
- Role names are immutable — no rename support. DROP and re-CREATE to "rename"

**Feature Flag Behavior**
- RBAC toggle is a config file setting (dremio.conf), requires coordinator restart to change — not a runtime system option
- Default: OFF (RBAC disabled)
- On first enable with existing cluster: strict deny-by-default. All queries fail until admin configures grants. No auto-grant to PUBLIC
- Bootstrap: the first user created via the bootstrap flow automatically receives ADMIN role membership
- Fail-fast: system refuses to start if RBAC is enabled but ADMIN role has no members. Logs ERROR with clear message

**Proto Message Design**
- Privilege types stored as strings ("SELECT", "EXECUTE", "CREATE_VIEW"), not proto enum
- Object types stored as strings ("VDS", "FUNCTION"), not proto enum
- Role message includes metadata: role_name, created_by, created_at
- Grant message: role_id, object_type, object_path, privilege, granted_by, granted_at
- Membership message: user_name, role_id, granted_by, granted_at
- ADMIN and PUBLIC are synthetic constants — never written to the KV store. Code checks for them directly in hasPrivilege()

### Claude's Discretion
- Exact Java package name (options discussed: `com.dremio.exec.catalog.rbac` or `com.dremio.exec.rbac`)
- Class naming convention: use domain names (RoleStore, GrantStore, PermissionService) not Rbac-prefixed names
- Proto file location within sabot/kernel
- Exact dremio.conf property name for the RBAC toggle

### Deferred Ideas (OUT OF SCOPE)
- META-03 (catalog visibility filtering) — moved to Phase 6
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| ENFC-09 | Enforcement gated behind a feature flag (defaults to OFF); can be enabled via config file setting in dremio.conf (CONTEXT.md overrides "system option") | DremioConfig pattern is well-established; add a constant to DremioConfig and a key to dremio-reference.conf; read via `dremioConfig.getBoolean(...)` at coordinator startup |
</phase_requirements>

---

## Summary

Phase 1 establishes the foundational artifacts — a compilable proto3 schema, KV store key format specifications, and a config-file feature flag — that all downstream phases depend on. All work lands inside the existing `sabot/kernel` Maven module, which already has a fully configured `protobuf-maven-plugin` that compiles every `.proto` file in `src/main/protobuf/`. No new Maven module is required.

Dremio's KV store API uses `Format.ofProtobuf(MyMessage.class)` for proto3 values and `Format.ofString()` for string keys. The store creation pattern is a static inner class implementing `KVStoreCreationFunction<K, V>`, registered via class reference. For the grants store, the composite key (`role_id|object_type|object_path|privilege`) is encoded as a single pipe-delimited string and stored under `Format.ofString()`, rather than using the datastore's `Format.ofCompoundFormat` (which would add internal framework separator bytes and break human-readable key inspection).

Feature flags in dremio.conf follow a uniform pattern: a `public static final String` constant in `DremioConfig` naming the property path, a default value entry in `dremio-reference.conf`, and `dremioConfig.getBoolean(DremioConfig.SOME_CONSTANT)` at the usage site. The `@Options` / `TypeValidators.BooleanValidator` pathway is for runtime system options settable without restart — the user explicitly chose the dremio.conf path instead.

**Primary recommendation:** Drop one proto file into `sabot/kernel/src/main/protobuf/rbac.proto`, wire two Java files (`DremioConfig` constant + `dremio-reference.conf` default), and ship three `KVStoreCreationFunction` inner classes. All Maven build infrastructure is already in place.

---

## Standard Stack

### Core

| Library / Component | Version / Location | Purpose | Why Standard |
|--------------------|--------------------|---------|--------------|
| `protobuf-maven-plugin` (xolstice) | Already in `sabot/kernel/pom.xml` | Compiles `.proto` → Java | Project-standard; all other kernel protos use it |
| `com.google.protobuf:protobuf-java` | 3.25.5 (root pom `protobuf.version`) | Runtime proto3 support | Project-wide standard |
| `com.dremio.datastore.api.KVStoreCreationFunction` | `/services/datastore/` | Defines and creates a KV store | Standard pattern for all Dremio stores using the new (non-Legacy) API |
| `com.dremio.datastore.format.Format` | `/services/datastore/` | Specifies key/value serialization format | Only supported way to configure KV store formats |
| `com.dremio.config.DremioConfig` | `common/legacy/.../DremioConfig.java` | Typed config constants + accessor for `dremio.conf` values | All config-file flags follow this pattern |
| TypeSafe Config (Lightbend) | Bundled via `dremio-reference.conf` hierarchy | Configuration file format | Required by DremioConfig; `dremio-reference.conf` provides defaults |

### Supporting

| Library / Component | Purpose | When to Use |
|--------------------|---------|-------------|
| `com.dremio.datastore.api.KVStoreProvider` | Injected to get store instances | Wherever a store is used (RoleStore, GrantStore, MembershipStore) |
| `javax.inject.Provider<KVStoreProvider>` | Lazy injection pattern | Use in constructor; resolve inside `start()` via `Suppliers.memoize()` — matches project convention |
| `google.protobuf.Timestamp` | Well-typed timestamp in proto | Available; however, `uint64` epoch-millis is the more common pattern in this codebase (see `script.proto`, `FunctionRPC.proto`) |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `Format.ofString()` for composite grant key | `Format.ofCompoundFormat(...)` with `KeyTriple` | `ofCompoundFormat` encodes keys with internal separators, not human-readable, harder to debug. String encoding with `|` is simpler and the user validated the separator is safe |
| `uint64` epoch-millis for timestamps | `google.protobuf.Timestamp` | Both work; `uint64` is the dominant pattern in this codebase (scripts service) and avoids a Timestamp import |
| New Maven module for RBAC | Add to `sabot/kernel` | New module adds build graph complexity; user decision locks kernel placement |

---

## Architecture Patterns

### Recommended Project Structure

```
sabot/kernel/src/main/protobuf/
└── rbac.proto                          # New: Role, Grant, Membership messages

sabot/kernel/src/main/java/com/dremio/exec/rbac/   (OR com.dremio.exec.catalog.rbac/)
├── RbacConfig.java                     # Constants: STORE names, KEY format helpers
├── RoleStore.java                      # KVStoreCreationFunction + store operations
├── GrantStore.java                     # KVStoreCreationFunction + store operations
└── MembershipStore.java                # KVStoreCreationFunction + store operations

common/legacy/src/main/resources/
└── dremio-reference.conf               # Add: services.rbac.enabled: false (or dremio.exec.rbac.enabled)

common/legacy/src/main/java/com/dremio/config/
└── DremioConfig.java                   # Add: RBAC_ENABLED constant
```

**Package name recommendation:** Use `com.dremio.exec.rbac` (not `com.dremio.exec.catalog.rbac`). Rationale: RBAC is a cross-cutting concern, not catalog-specific. The `exec.catalog` package is very large and contains catalog orchestration logic unrelated to RBAC. The `exec.rbac` sub-package is immediately recognizable, matches the EE convention of using distinct sub-packages, and leaves `exec.catalog.rbac` free if EE ever needs it.

### Pattern 1: Proto3 File in Kernel

**What:** Place the proto file in `src/main/protobuf/rbac.proto`. The `protobuf-maven-plugin` already configured in `sabot/kernel/pom.xml` processes all `.proto` files in that directory automatically (no pom change needed for adding a new file).

**When to use:** Every time a proto3 message is needed in the kernel module.

**Example (from `vacuum-log.proto` and `query-state.proto`):**
```protobuf
// Source: /home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/protobuf/vacuum-log.proto
syntax = "proto3";

option java_package = "com.dremio.exec.rbac.proto";
option java_outer_classname = "RbacProto";
option optimize_for = SPEED;

package com.dremio.exec.rbac.proto;

message Role {
  string role_id   = 1;   // slugified role name, immutable
  string role_name = 2;
  string created_by = 3;
  uint64 created_at = 4;  // epoch millis, matches codebase convention
}

message Grant {
  string role_id      = 1;
  string object_type  = 2;  // stored as string: "VDS", "FUNCTION", etc.
  string object_path  = 3;  // dot-delimited path
  string privilege    = 4;  // stored as string: "SELECT", "EXECUTE", etc.
  string granted_by   = 5;
  uint64 granted_at   = 6;
}

message Membership {
  string user_name  = 1;
  string role_id    = 2;
  string granted_by = 3;
  uint64 granted_at = 4;
}
```

**Key proto3 rules observed in this codebase:**
- Always include `syntax = "proto3";` as the first line
- `java_outer_classname` groups all messages into one outer class
- `optimize_for = SPEED;` is used by all kernel proto files
- No `required` fields — proto3 has none; all fields are optional by default
- Use `uint64` for timestamps (matches `script.proto`, `FunctionRPC.proto` patterns)

### Pattern 2: KVStoreCreationFunction with Protobuf Value

**What:** A static inner class inside each store class that implements `KVStoreCreationFunction<K, V>` and builds the store with `Format.ofString()` for keys and `Format.ofProtobuf(MyMessage.class)` for values.

**When to use:** For any KV store that serializes proto3 messages (new API, not Legacy API).

**Example (based on `ScriptStoreImpl.StoreCreator` and `EmbeddedPointerStoreBuilder`):**
```java
// Source pattern: services/scripts/src/main/java/com/dremio/service/scripts/ScriptStoreImpl.java
//                 services/embedded-catalog/.../EmbeddedPointerStoreBuilder.java

public class RoleStore {
  public static final String STORE_NAME = "oss_rbac_roles";
  private final Supplier<KVStore<String, Role>> store;

  @Inject
  public RoleStore(Provider<KVStoreProvider> kvStoreProvider) {
    this.store = Suppliers.memoize(
        () -> kvStoreProvider.get().getStore(StoreCreator.class));
  }

  public static final class StoreCreator
      implements KVStoreCreationFunction<String, Role> {
    @Override
    public KVStore<String, Role> build(StoreBuildingFactory factory) {
      return factory
          .<String, Role>newStore()
          .name(STORE_NAME)                        // "oss_rbac_roles" — oss_ prefix for EE isolation
          .keyFormat(Format.ofString())             // role_id is a slugified string
          .valueFormat(Format.ofProtobuf(Role.class))
          .build();
    }
  }
}
```

**For GrantStore, the key is a composite string:**
```java
// Key format: "role_id|object_type|object_path|privilege"
// e.g.:  "analyst|VDS|schemas.my_view|SELECT"
// Separator | is safe: not present in slugified role IDs, dot-paths, or privilege names
public static String grantKey(String roleId, String objectType, String objectPath, String privilege) {
  return roleId + "|" + objectType + "|" + objectPath + "|" + privilege;
}
```

### Pattern 3: Config-File Feature Flag via DremioConfig

**What:** Add a `public static final String` constant to `DremioConfig` naming the HOCON path, add the default `false` value to `dremio-reference.conf`, and read it with `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)` at startup.

**When to use:** For settings that require a coordinator restart (not runtime-changeable). This is the pattern for `NESSIE_SERVICE_ENABLED_BOOLEAN`, `FLIGHT_SERVICE_ENABLED_BOOLEAN`, `JOBS_ENABLED_BOOL`, etc.

**Example (from `DremioConfig.java` and `dremio-reference.conf`):**
```java
// In DremioConfig.java — add alongside similar service-enable constants:
// Source: common/legacy/src/main/java/com/dremio/config/DremioConfig.java
public static final String RBAC_ENABLED = "services.rbac.enabled";
```

```hocon
# In dremio-reference.conf — add in the services block:
# Source: common/legacy/src/main/resources/dremio-reference.conf
services: {
  # ... existing ...
  rbac: {
    enabled: false   # RBAC enforcement; requires coordinator restart to change
  }
}
```

```java
// Usage in coordinator startup (e.g., DACDaemonModule or a new RbacService):
boolean rbacEnabled = config.getBoolean(DremioConfig.RBAC_ENABLED);
```

**Recommended property path: `services.rbac.enabled`**
Rationale: Matches the pattern of other service-toggle flags (`services.nessie.enabled`, `services.flight.enabled`, `services.coordinator.enabled`). The alternative `dremio.exec.rbac.enabled` is used for Java-system-property-style constants in `ExecConstants`; since this is a dremio.conf property, the `services.` namespace is appropriate and consistent.

### Anti-Patterns to Avoid

- **Using `TypeValidators.BooleanValidator` in `ExecConstants`:** That wires a runtime system option queryable via `OptionManager` and changeable without restart. The user explicitly chose dremio.conf for static configuration. Do not use `@Options` or `OptionManager` for the RBAC toggle.
- **Using `proto2` syntax:** Several older files in this repo use proto2 (`optional`, `required`). New files must use `proto3`. Proto3 has no `required` fields — every field is implicitly optional.
- **Using `Format.ofProtostuff` for new stores:** Protostuff is the legacy serialization format. The new API uses `Format.ofProtobuf`. Both work, but new stores should use protobuf (used by ScriptStoreImpl, SQLRunnerSessionStoreImpl, ReindexVersionStoreCreator).
- **Using `LegacyKVStoreCreationFunction`:** The non-Legacy `KVStoreCreationFunction` is the current API. Legacy stores use a separate provider (`LegacyKVStoreProvider`) and are from an older pattern.
- **Naming the store inner class without context:** Inner class must be the stable "key" used by `kvStoreProvider.get().getStore(MyStore.StoreCreator.class)`. Rename of the class = different store = data loss. Name it once and keep it.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Proto3 Java code generation | Custom code generator | `protobuf-maven-plugin` already in kernel pom | Already configured, handles all files in `src/main/protobuf/` automatically |
| Key/value serialization for KV store | Custom byte encoder | `Format.ofProtobuf(Class)` + `Format.ofString()` | Framework handles versioning, encoding, and type safety |
| Config file parsing | Custom HOCON parser | TypeSafe Config via `DremioConfig.getBoolean(key)` | Established pattern; config is already loaded and merged at startup |
| Composite key encoding | Custom separator scheme | Pipe `|` in a Format.ofString() key — already decided | Simpler than `ofCompoundFormat`, human-readable, validated safe |

**Key insight:** Every infrastructure piece (proto compilation, KV store, config loading) is already wired. Phase 1 adds data, not infrastructure.

---

## Common Pitfalls

### Pitfall 1: Changing Store Creator Class Name After First Deploy
**What goes wrong:** `KVStoreProvider.getStore(MyStore.StoreCreator.class)` uses the creator class as the store identifier. If the class is renamed, a different store is resolved, and the data in the old store becomes inaccessible.
**Why it happens:** The class literal is used as the store "key" internally.
**How to avoid:** Choose the class name once. Confirm it in PLAN.md before writing code. Do not refactor creator class names in any downstream phase.
**Warning signs:** Tests pass but old data is not found; store appears empty after rename.

### Pitfall 2: Proto Field Numbers Reused After Deletion
**What goes wrong:** Removing a proto field and reusing its field number causes incorrect deserialization of persisted data.
**Why it happens:** Proto serialization is tag-based; field number = wire tag.
**How to avoid:** Once field numbers are chosen in Phase 1, treat them as permanent. Mark removed fields as `reserved`. Since Phase 1 establishes the schema, get field numbers right from the start.
**Warning signs:** Garbled values when reading old records.

### Pitfall 3: Proto3 Defaults Masking "Not Set"
**What goes wrong:** In proto3, all fields have default values (0 for numbers, `""` for strings, `false` for booleans). There is no way to distinguish "field was not set" from "field was set to default value."
**Why it happens:** Proto3 removed the `hasField()` concept for scalar fields (only `oneof` and message types support it).
**How to avoid:** For fields where "not set" is semantically different from the default, use `string` rather than `bool` or `int64` (empty string is easily detectable), or use a wrapper message, or avoid storing proto fields that need null-vs-default distinction.
**Warning signs:** Code checks `message.getGrantedBy().equals("")` to detect uninitialized — that's correct for strings but document it.

### Pitfall 4: Wrong Config Namespace (exec. vs services.)
**What goes wrong:** Placing RBAC config under `dremio.exec.rbac.enabled` in `dremio-reference.conf` when the pattern for service toggles is `services.rbac.enabled`.
**Why it happens:** `ExecConstants` uses `dremio.exec.` prefixes for legacy SABot options; those are different from HOCON service-enable booleans.
**How to avoid:** Service on/off flags belong in `services.{name}.enabled`. Match the pattern of `services.nessie.enabled`, `services.flight.enabled`.
**Warning signs:** Config key not found at startup; NullPointerException when calling `getBoolean()` without a default.

### Pitfall 5: Confusing protostuff vs protobuf serialization
**What goes wrong:** Using `Format.ofProtostuff(MyMessage.class)` with a proto3-generated class (which is a `com.google.protobuf.Message`, not a `io.protostuff.Message`).
**Why it happens:** The codebase contains both protostuff-compiled classes (via `dremio-protostuff-maven-plugin`) and standard protobuf-compiled classes (via `protobuf-maven-plugin`). They are different APIs and different wire formats.
**How to avoid:** New proto3 `.proto` files compiled by `protobuf-maven-plugin` produce `com.google.protobuf.Message` subclasses. Always use `Format.ofProtobuf(MyClass.class)` with these.
**Warning signs:** Compile error — `MyClass` does not implement `io.protostuff.Message`.

---

## Code Examples

Verified patterns from the codebase:

### How DremioConfig boolean flags are read at startup

```java
// Source: dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java
if (isCoordinator && config.getBoolean(DremioConfig.NESSIE_SERVICE_ENABLED_BOOLEAN)) {
  // start nessie
}
// RBAC equivalent:
if (isCoordinator && config.getBoolean(DremioConfig.RBAC_ENABLED)) {
  // validate ADMIN role membership, fail-fast if empty
}
```

### How KVStoreCreationFunction is used with Format.ofProtobuf

```java
// Source: services/scripts/src/main/java/com/dremio/service/scripts/ScriptStoreImpl.java (line 131-141)
public static final class StoreCreator implements IndexedStoreCreationFunction<String, Script> {
  @Override
  public IndexedStore<String, Script> build(StoreBuildingFactory factory) {
    return factory
        .<String, Script>newStore()
        .name(STORE_NAME)
        .keyFormat(Format.ofString())
        .valueFormat(Format.ofProtobuf(Script.class))
        .buildIndexed(new ScriptDocumentConverter());
  }
}
// For a plain KVStore (no indexing needed for roles/grants/memberships in Phase 1):
// Source: services/embedded-catalog/.../EmbeddedPointerStoreBuilder.java
public KVStore<String, String> build(StoreBuildingFactory factory) {
  return factory
      .<String, String>newStore()
      .name("embedded_pointers")
      .keyFormat(Format.ofString())
      .valueFormat(Format.ofString())
      .build();   // not buildIndexed
}
```

### How a store is resolved via its creator class

```java
// Source: services/scripts/src/main/java/com/dremio/service/scripts/ScriptStoreImpl.java (line 56-58)
store = Suppliers.memoize(
    () -> kvStoreProvider.get().getStore(ScriptStoreImpl.StoreCreator.class));
```

### Proto3 file header pattern (kernel standard)

```protobuf
// Source: sabot/kernel/src/main/protobuf/vacuum-log.proto (lines 16-24)
syntax = "proto3";

option java_package = "com.dremio.exec.store.iceberg.logging";
option java_outer_classname = "VacuumLogProto";
option optimize_for = SPEED;

package com.dremio.exec.store.iceberg.logging.proto;
```

### DremioConfig constant naming convention

```java
// Source: common/legacy/src/main/java/com/dremio/config/DremioConfig.java (lines 143-144)
public static final String NESSIE_SERVICE_ENABLED_BOOLEAN = "services.nessie.enabled";
public static final String FLIGHT_SERVICE_ENABLED_BOOLEAN = "services.flight.enabled";
// RBAC equivalent:
public static final String RBAC_ENABLED = "services.rbac.enabled";
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `LegacyKVStoreCreationFunction` + `LegacyStoreBuildingFactory` | `KVStoreCreationFunction` + `StoreBuildingFactory` | Ongoing migration in this repo | New stores MUST use the non-Legacy API |
| `Format.ofProtostuff(Class)` for protostuff-compiled classes | `Format.ofProtobuf(Class)` for proto3 protobuf-compiled classes | Ongoing migration | RBAC stores use proto3 + `Format.ofProtobuf` |
| Runtime system options (`TypeValidators.BooleanValidator`) for feature gates | Config-file settings (`dremio-reference.conf` + `DremioConfig`) for restart-required settings | Project preference | RBAC feature flag MUST be config-file based |

**Deprecated/outdated:**
- `proto2` syntax: All new proto files in this project use `proto3`. Older files (`iceberg.proto`, protocol protos) still use proto2 but new ones do not.
- `LegacyKVStoreCreationFunction`: Retained for old stores; do not use for new stores.

---

## Open Questions

1. **Exact package name: `com.dremio.exec.rbac` vs `com.dremio.exec.catalog.rbac`**
   - What we know: Both are free (no existing OSS or EE files in either package were found). The `exec.catalog` package is very crowded (~90 files).
   - What's unclear: Whether EE uses `exec.catalog.rbac` internally (cannot verify from OSS tree).
   - Recommendation: Use `com.dremio.exec.rbac`. It is shorter, unambiguous, and leaves `exec.catalog.rbac` free for EE if needed. The planner should lock this choice in PLAN.md.

2. **Bootstrap: where to hook ADMIN membership assignment for the first user**
   - What we know: `FirstLoginSetupService.setup(username)` is called on first login (OSS implementation is a no-op). `SampleDataPopulatorService.addDefaultUser()` creates the test user. The real first-user creation goes through `UserResource.createUser()`.
   - What's unclear: Whether Phase 1 needs to wire the bootstrap hook at all (the context says "first user created via bootstrap flow automatically receives ADMIN role membership" — but this is runtime behavior, not just schema).
   - Recommendation: Phase 1 only establishes the schema and flag. The bootstrap hook implementation belongs in Phase 2 or later (store wiring). The planner should note this boundary.

3. **`dremio-reference.conf` default location for RBAC flag**
   - What we know: All existing service booleans (`services.nessie.enabled`, `services.flight.enabled`) live in the `services` block of `dremio-reference.conf` in `common/legacy/src/main/resources/`.
   - What's unclear: None — this is clear.
   - Recommendation: Add `services.rbac.enabled: false` to the existing `services` block in `common/legacy/src/main/resources/dremio-reference.conf`. Add `RBAC_ENABLED = "services.rbac.enabled"` to `DremioConfig.java`.

---

## Sources

### Primary (HIGH confidence)

Verified by reading actual source files in the repository:

- `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/pom.xml` — confirmed `protobuf-maven-plugin` processes all files in `src/main/protobuf/` automatically; no pom change needed for adding a new proto file
- `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/protobuf/vacuum-log.proto` — proto3 file template (java_package, java_outer_classname, optimize_for, uint64 for timestamps)
- `/home/emanuele/IdeaProjects/dremio-oss/sabot/kernel/src/main/protobuf/query-state.proto` — confirmed proto3 pattern in kernel
- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/format/Format.java` — confirmed `Format.ofProtobuf(Class)`, `Format.ofString()`, `Format.ofCompoundFormat(...)` APIs
- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/api/KVStoreCreationFunction.java` — interface for new KV store creation
- `/home/emanuele/IdeaProjects/dremio-oss/services/datastore/src/main/java/com/dremio/datastore/api/StoreBuildingFactory.java` — builder interface
- `/home/emanuele/IdeaProjects/dremio-oss/services/scripts/src/main/java/com/dremio/service/scripts/ScriptStoreImpl.java` — canonical example of KVStoreCreationFunction + Format.ofProtobuf + Suppliers.memoize
- `/home/emanuele/IdeaProjects/dremio-oss/services/embedded-catalog/src/main/java/com/dremio/service/embedded/catalog/EmbeddedPointerStoreBuilder.java` — minimal KVStoreCreationFunction example
- `/home/emanuele/IdeaProjects/dremio-oss/common/legacy/src/main/java/com/dremio/config/DremioConfig.java` — confirmed `NESSIE_SERVICE_ENABLED_BOOLEAN = "services.nessie.enabled"` and `FLIGHT_SERVICE_ENABLED_BOOLEAN` patterns; confirmed `getBoolean()` accessor
- `/home/emanuele/IdeaProjects/dremio-oss/common/legacy/src/main/resources/dremio-reference.conf` — confirmed `services:` block structure; confirmed `dremio.` prefix for non-service properties
- `/home/emanuele/IdeaProjects/dremio-oss/dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` — confirmed `config.getBoolean(DremioConfig.NESSIE_SERVICE_ENABLED_BOOLEAN)` usage pattern at startup
- `/home/emanuele/IdeaProjects/dremio-oss/services/scripts/src/main/proto/script.proto` — proto3 service-level proto example (java_package, java_outer_classname, uint64 for timestamps)
- `/home/emanuele/IdeaProjects/dremio-oss/pom.xml` — confirmed protobuf version 3.25.5, grpc version 1.70.0

---

## Metadata

**Confidence breakdown:**
- Proto3 schema pattern: HIGH — read multiple kernel proto files; `protobuf-maven-plugin` config verified in pom.xml
- KV store API: HIGH — read Format.java, KVStoreCreationFunction, StoreBuildingFactory, multiple real store creators
- Config-file feature flag: HIGH — read DremioConfig.java, dremio-reference.conf, usage in DACDaemonModule
- Package namespace recommendation: MEDIUM — no EE source available to verify non-collision, but no OSS conflict found
- Bootstrap hook location: MEDIUM — FirstLoginSetupService found but Phase 1 boundary is schema-only

**Research date:** 2026-02-17
**Valid until:** 2026-03-19 (30 days — stable codebase patterns)
