# Phase 9: UDF Rights Verification and Owner Resolution - Research

**Researched:** 2026-02-21
**Domain:** Java — CatalogEntityOwnershipImpl, FunctionConfig proto, UserDefinedFunctionSerde, UserDefinedFunctionExpanderImpl, CatalogImpl, CreateFunctionHandler
**Confidence:** HIGH (all findings confirmed by direct code inspection)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| UDF-01 | UDF execution uses definer semantics (body runs as UDF creator, verified and tested) | `UserDefinedFunctionExpanderImpl.parseAndValidate()` already calls `.withUser(dremioUserDefinedFunction.getOwner())`. The mechanism is in place. It receives the fallback identity `CatalogUser(userName)` (the query user) because `getCatalogEntityOwner()` returns `Optional.empty()` for FUNCTION. Fix: make the FUNCTION case return the real stored owner. Requires adding `owner` field to `FunctionConfig` proto and writing it at UDF creation time. |
| UDF-02 | CatalogEntityOwnershipImpl correctly returns owner for FUNCTION type | `CatalogEntityOwnershipImpl.getCatalogEntityOwner()` switch on FUNCTION type immediately returns `Optional.empty()` (line 58-60 in current code). Fix: extract `FunctionConfig` from the container, read its `owner` field, guard on null/empty, return `Optional.of(new CatalogUser(owner))`. Parallel to the existing DATASET case. Prerequisite: `FunctionConfig.owner` must exist (proto change + serde change). |
| UDF-03 | User needs EXECUTE privilege to call a UDF (existing enforcement, integration test coverage added) | `CatalogImpl.getFunctions()` already calls `isRbacDeniedForFunction()` which calls `rbacService.hasPrivilege(userName, "EXECUTE", "FUNCTION", key.getSchemaPath())`. The enforcement is live. No new production code needed. Need: tests that (a) verify EXECUTE-denied returns empty list from `getFunctions()`, and (b) verify EXECUTE-granted allows the function to be found. |
</phase_requirements>

---

## Summary

Phase 9 fixes two separate but related gaps: (1) the `FunctionConfig` protobuf has no `owner` field so UDF ownership cannot be persisted, and (2) `CatalogEntityOwnershipImpl` returns `Optional.empty()` for the `FUNCTION` namespace type. Both gaps together cause `getUserDefinedFunctionOwner()` to fall back to `CatalogUser(userName)` — the query user — instead of the UDF creator. This means UDF expansion in `UserDefinedFunctionExpanderImpl` runs under the caller's identity, defeating definer semantics.

The fix has three parts. First, add `optional string owner = 9` to `function.proto` and regenerate. Second, propagate the creator's username into `FunctionConfig` at creation time in `CreateFunctionHandler` / `UserDefinedFunctionSerde.toProto()`. Third, fix the FUNCTION branch in `CatalogEntityOwnershipImpl` to read `getFunction().getOwner()` and return a non-empty `Optional<CatalogIdentity>` when the owner is present.

The `EXECUTE` enforcement (UDF-03) is already fully wired in `CatalogImpl.getFunctions()` via `isRbacDeniedForFunction()`. The only work needed for UDF-03 is integration test coverage documenting the behavior.

**Primary recommendation:** Fix the proto first, then the serde, then `CreateFunctionHandler` to stamp the creator username, then `CatalogEntityOwnershipImpl`, then add tests for all three requirements. Plan as two tasks: (1) storage plumbing + ownership impl, (2) integration tests.

---

## Standard Stack

### Core (no new dependencies — everything already exists)

| Component | Location | Purpose | Why Relevant |
|-----------|----------|---------|-------------|
| `function.proto` | `services/namespace/src/main/proto/function.proto` | Protostuff schema for `FunctionConfig` | Must gain `optional string owner = 9` — this is the root gap. Currently defines only `id`, `name`, `tag`, `full_path`, `created_at`, `last_modified`, `function_definitions`, `return_type`. No `owner` field. |
| `UserDefinedFunctionSerde` | `sabot/kernel/.../udf/UserDefinedFunctionSerde.java` | `fromProto()` / `toProto()` conversions between `UserDefinedFunction` (Java) and `FunctionConfig` (proto) | `toProto()` must write the owner; `fromProto()` currently ignores any owner field. The Java `UserDefinedFunction` class itself does NOT have an `owner` field — the owner is a separate identity concern resolved at lookup time via `getCatalogEntityOwner()`. |
| `CreateFunctionHandler` | `sabot/kernel/.../handlers/direct/CreateFunctionHandler.java` | Handles `CREATE FUNCTION` SQL | Must stamp the creator's username onto `FunctionConfig.owner` at UDF creation time. The handler has access to `context.getQueryUserName()`. |
| `CatalogEntityOwnershipImpl` | `sabot/kernel/.../catalog/CatalogEntityOwnershipImpl.java` | Returns `CatalogIdentity` owner for a namespace entity | FUNCTION case (lines 57-60) returns `Optional.empty()`. Must be changed to: `final FunctionConfig func = nameSpaceContainer.getFunction(); final String owner = func.getOwner(); if (owner == null || owner.isEmpty()) return Optional.empty(); return Optional.of(new CatalogUser(owner));` |
| `CatalogImpl.getUserDefinedFunctionOwner()` | `sabot/kernel/.../catalog/CatalogImpl.java:1445` | Calls `getCatalogEntityOwner()` and falls back to `CatalogUser(userName)` | This is the call site that feeds the owner into `DremioScalarUserDefinedFunction` / `DremioTabularUserDefinedFunction`. No change needed here once `getCatalogEntityOwner()` returns correctly. |
| `UserDefinedFunctionExpanderImpl.parseAndValidate()` | `sabot/kernel/.../ops/UserDefinedFunctionExpanderImpl.java:129` | Expands a UDF body by calling `.withUser(dremioUserDefinedFunction.getOwner())` | This is the definer rights mechanism. It already works. Once the owner is non-null and correct, body expansion runs under the UDF creator's identity. NO CHANGE NEEDED here. |
| `CatalogImpl.isRbacDeniedForFunction()` | `sabot/kernel/.../catalog/CatalogImpl.java:2928` | Checks `rbacService.hasPrivilege(userName, "EXECUTE", "FUNCTION", key.getSchemaPath())` | EXECUTE enforcement is already live. Called from `getFunctions()` before any UDF lookup proceeds. Returns empty list when denied. NO CHANGE NEEDED here. |
| `TestCatalogImpl` | `sabot/kernel/src/test/java/.../catalog/TestCatalogImpl.java` | Unit tests for CatalogImpl and CatalogEntityOwnershipImpl | Must add: FUNCTION owner returned by `CatalogEntityOwnershipImpl`, EXECUTE denied returns empty from `getFunctions()`, EXECUTE granted allows function lookup, integration test for definer body expansion. |

---

## Architecture Patterns

### Key Invariant: Owner Stored in Proto, Resolved at Lookup

For VDS (DATASET type), owner is stored in `DatasetConfig.owner` (field 3 of `dataset.proto`). At lookup, `CatalogEntityOwnershipImpl` reads it from the `NameSpaceContainer`. The same pattern must be applied for FUNCTION.

```
CREATE FUNCTION statement
    → CreateFunctionHandler.toResult()
        → context.getQueryUserName()  ← creator username available here
        → UserDefinedFunctionSerde.toProto(udf)  ← must write owner here
        → FunctionConfig.setOwner(creatorUsername)
        → userNamespaceService.addOrUpdateFunction(key, functionConfig)

Later, at query time: myspace.myfunc(args)
    → CatalogImpl.getFunctions(path, SCALAR)
        → isRbacDeniedForFunction(key)  ← EXECUTE check (UDF-03, already working)
        → getUserDefinedScalarFunctions(key)
            → getUserDefinedFunctionOwner(key)
                → getCatalogEntityOwner(key)
                    → CatalogEntityOwnershipImpl.getCatalogEntityOwner()
                        → nameSpaceContainer.getFunction().getOwner()  ← reads proto field
                        → return Optional.of(new CatalogUser(owner))   ← UDF-02 fix
                → new DremioScalarUserDefinedFunction(owner, udf)
    → UserDefinedFunctionExpanderImpl.parseAndValidate(udf)
        → builder.withUser(dremioUdf.getOwner())  ← definer identity injected (UDF-01)
        → expansion runs under creator's catalog identity
```

### Pattern 2: Proto Field Addition (protostuff style)

Dremio uses protostuff (not standard protobuf). Adding a field to a protostuff `.proto` file is backward-compatible if the field is `optional` and given a new field number. Existing stored functions (without the field) will deserialize with `owner = null`. The null guard in `CatalogEntityOwnershipImpl` handles this gracefully (returns `Optional.empty()` → falls back to query user, same behavior as today).

```
// In services/namespace/src/main/proto/function.proto
message FunctionConfig {
  optional EntityId id = 1;
  optional string name = 2;
  optional string tag = 3;
  repeated string full_path = 4;
  optional int64 created_at = 5;
  optional int64 last_modified = 6;
  repeated FunctionDefinition function_definitions = 7;
  optional ReturnType return_type = 8;
  optional string owner = 9;   // NEW FIELD — creator username
}
```

After adding the field, the protostuff maven plugin regenerates the Java class with `getOwner()` / `setOwner(String)` methods. No migration needed — old records will have `null` owner (graceful fallback).

### Pattern 3: CreateFunctionHandler — Stamping the Owner

The `extractUdf()` method in `CreateFunctionHandler` constructs a `UserDefinedFunction` but does NOT include owner. The simplest fix is to write the owner directly onto the `FunctionConfig` in the `createOrUpdateFunction` path, after the `UserDefinedFunctionSerde.toProto(userDefinedFunction)` call:

```java
// In UserDefinedFunctionCatalogImpl.createOrUpdateFunction() (non-plugin path)
FunctionConfig newFunctionConfig = UserDefinedFunctionSerde.toProto(userDefinedFunction);
// Stamp the creator's username as the owner (only on first creation, not update)
if (!isUpdate && schemaConfig.getUserName() != null) {
    newFunctionConfig.setOwner(schemaConfig.getUserName());
}
```

Alternatively, add `owner` as a constructor parameter to `UserDefinedFunction` and pipe it through `UserDefinedFunctionSerde.toProto()`. The inline approach in `UserDefinedFunctionCatalogImpl` is simpler and avoids touching the `UserDefinedFunction` Java data class, which has no owner field and is not designed for it.

**Decision point for planner:** The simplest approach is to stamp `owner` on the `FunctionConfig` inside `UserDefinedFunctionCatalogImpl.createOrUpdateFunction()` using `schemaConfig.getUserName()`. This avoids changing the `UserDefinedFunction` Java value class. On `isUpdate = true`, the owner should be preserved from the existing `FunctionConfig` (read old config first, copy the owner field over to the new config).

### Anti-Patterns to Avoid

- **Changing `UserDefinedFunction` Java class to carry `owner`:** Unnecessary indirection — the Java class is a data transfer object. Owner resolution happens at the identity layer (`CatalogEntityOwnershipImpl`), not inside the UDF data model.
- **Checking for `EXECUTE` at the UDF expansion time in the expander:** The EXECUTE check is already at `getFunctions()` entry — enforcing it again in the expander would double-fire and make the error appear at a wrong stack frame.
- **Skipping proto change:** `owner` cannot be derived from any other stored field. There is no alternative storage location for the creator username in `FunctionConfig`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Resolving creator identity at call time | Custom `getCurrentUserResolver` or security context lookups | `context.getQueryUserName()` / `schemaConfig.getUserName()` | These are the established patterns for getting the executing user; already used throughout the codebase |
| Schema migration for old `FunctionConfig` records | Migration task | Null guard in `CatalogEntityOwnershipImpl` | `optional` protostuff fields default to `null` on old records; the existing null guard produces graceful fallback (query-user identity) |
| New EXECUTE enforcement code | Custom pre-call check | Existing `isRbacDeniedForFunction()` in `CatalogImpl.getFunctions()` | Already fully implemented and tested for `validatePrivilege()` path |

---

## Common Pitfalls

### Pitfall 1: Proto Regeneration Not Triggered

**What goes wrong:** Field is added to `function.proto` but Maven does not regenerate the Java class because a stale target exists.
**Why it happens:** Incremental builds skip regeneration if proto timestamp hasn't changed or if target is cached.
**How to avoid:** Run `mvn generate-sources` in the `services/namespace` module, or do a full `mvn clean` before building. Verify the generated class has `getOwner()` / `setOwner()` before proceeding.
**Warning signs:** Compilation error "cannot find symbol: method getOwner()" on `FunctionConfig`.

### Pitfall 2: Owner Overwritten on UPDATE

**What goes wrong:** `isUpdate = true` path writes the query user as owner, replacing the original creator.
**Why it happens:** Calling `newFunctionConfig.setOwner(schemaConfig.getUserName())` unconditionally.
**How to avoid:** On update, read the old `FunctionConfig`, copy its `owner` field to the new config. Pattern already used for `setTag` / `setId` in `UserDefinedFunctionCatalogImpl.createOrUpdateFunction()` (lines 115-119).
**Warning signs:** After `CREATE OR REPLACE FUNCTION`, the owner changes to whoever ran the REPLACE statement.

### Pitfall 3: CatalogEntityOwnershipImpl Reads Wrong Container Field

**What goes wrong:** Code calls `nameSpaceContainer.getDataset()` instead of `nameSpaceContainer.getFunction()` in the FUNCTION branch.
**Why it happens:** Copy-paste from the DATASET branch without updating the accessor.
**How to avoid:** The FUNCTION branch must call `nameSpaceContainer.getFunction()` (returns `FunctionConfig`) which has `getOwner()`. `getDataset()` would return `null` for a FUNCTION container and throw NPE.
**Warning signs:** NPE in `getCatalogEntityOwner()` when called with a FUNCTION entity key.

### Pitfall 4: Definer Semantics Already Work — Over-Engineering Expected

**What goes wrong:** Planner adds complex definer-rights wiring for UDFs modeled on the VDS `ViewExpander` approach.
**Why it happens:** Not noticing that `UserDefinedFunctionExpanderImpl.parseAndValidate()` ALREADY calls `.withUser(dremioUserDefinedFunction.getOwner())` on line 136. The definer mechanism is complete.
**How to avoid:** Read `UserDefinedFunctionExpanderImpl` before planning. The only missing piece is ensuring `getOwner()` returns the real creator identity (via `CatalogEntityOwnershipImpl`), not the fallback query user.
**Warning signs:** Plans that add a new `withUser()` call or a new catalog-switching mechanism inside the UDF expander.

### Pitfall 5: Missing Test for getFunctions() EXECUTE Denial

**What goes wrong:** Tests only cover `validatePrivilege(Privilege.EXECUTE)` (which tests the throw-on-deny path) but miss the `getFunctions()` deny path (which returns empty list, not exception).
**Why it happens:** The two enforcement points have different behaviors: `validatePrivilege` throws, `isRbacDeniedForFunction` returns empty list (silent deny).
**How to avoid:** Write separate tests for both paths. UDF-03 integration test must cover: (a) `getFunctions()` returns empty when EXECUTE denied, (b) `getFunctions()` returns the function when EXECUTE granted.

---

## Code Examples

### Fix: CatalogEntityOwnershipImpl FUNCTION branch

```java
// In CatalogEntityOwnershipImpl.getCatalogEntityOwner()
case FUNCTION:
{
  final FunctionConfig function = nameSpaceContainer.getFunction();
  final String owner = function.getOwner();
  if (owner == null || owner.isEmpty()) {
    return Optional.empty(); // Legacy UDF without recorded owner
  }
  return Optional.of(new CatalogUser(owner));
}
```

### Fix: FunctionConfig owner stamped at creation

```java
// In UserDefinedFunctionCatalogImpl.createOrUpdateFunction()
FunctionConfig newFunctionConfig = UserDefinedFunctionSerde.toProto(userDefinedFunction);
if (isUpdate) {
  try {
    FunctionConfig oldFunctionConfig = userNamespaceService.getFunction(key.toNamespaceKey());
    newFunctionConfig
        .setTag(oldFunctionConfig.getTag())
        .setId(oldFunctionConfig.getId())
        .setOwner(oldFunctionConfig.getOwner()); // preserve original creator on update
  } catch (NamespaceException ignore) {
    // tag/id/owner not copied over if existing function not found
  }
} else {
  // First creation: stamp the creator
  newFunctionConfig.setOwner(schemaConfig.getUserName());
}
userNamespaceService.addOrUpdateFunction(key.toNamespaceKey(), newFunctionConfig);
```

### Test: CatalogEntityOwnershipImpl FUNCTION owner returned (UDF-02)

```java
@Test
public void testUdfOwner_functionOwnerReturned() throws Exception {
  FunctionConfig functionConfig = new FunctionConfig();
  functionConfig.setOwner("alice");

  NameSpaceContainer container = new NameSpaceContainer();
  container.setType(NameSpaceContainer.Type.FUNCTION);
  container.setFunction(functionConfig);

  NamespaceKey udfKey = new NamespaceKey(Arrays.asList("myspace", "myfunc"));
  when(systemNamespaceService.getEntityByPath(udfKey)).thenReturn(container);

  CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
  Optional<CatalogIdentity> owner =
      ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(udfKey));

  assertThat(owner).isPresent();
  assertEquals("alice", owner.get().getName());
}

@Test
public void testUdfOwner_functionNullOwner_returnsEmpty() throws Exception {
  FunctionConfig functionConfig = new FunctionConfig();
  functionConfig.setOwner(null);

  NameSpaceContainer container = new NameSpaceContainer();
  container.setType(NameSpaceContainer.Type.FUNCTION);
  container.setFunction(functionConfig);

  NamespaceKey udfKey = new NamespaceKey(Arrays.asList("myspace", "legacy_func"));
  when(systemNamespaceService.getEntityByPath(udfKey)).thenReturn(container);

  CatalogEntityOwnershipImpl ownership = new CatalogEntityOwnershipImpl(systemNamespaceService);
  Optional<CatalogIdentity> owner =
      ownership.getCatalogEntityOwner(CatalogEntityKey.fromNamespaceKey(udfKey));

  assertThat(owner).isEmpty();
}
```

### Test: isRbacDeniedForFunction via getFunctions() (UDF-03)

```java
@Test
public void testGetFunctions_executeDenied_returnsEmptyCollection() {
  when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
  when(rbacService.hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString()))
      .thenReturn(false);

  CatalogImpl catalog = newCatalogImpl(versionContextResolver);
  Collection<Function> functions =
      catalog.getFunctions(
          CatalogEntityKey.fromNamespaceKey(new NamespaceKey(Arrays.asList("myspace", "myfunc"))),
          SimpleCatalog.FunctionType.SCALAR);

  assertThat(functions).isEmpty();
  verify(rbacService).hasPrivilege(eq("gnarly"), eq("EXECUTE"), eq("FUNCTION"), anyString());
}
```

---

## State of the Art

| Area | Current State | After Phase 9 | Impact |
|------|--------------|---------------|--------|
| UDF ownership storage | `FunctionConfig` proto has no `owner` field; `getOwnerNameFromFunctionConfig()` returns `null` | `FunctionConfig` gains `optional string owner = 9`; field written at creation | Owner survives restart and serialization |
| Owner resolution for UDFs | `CatalogEntityOwnershipImpl` returns `Optional.empty()` for FUNCTION → fallback to query user | Returns `Optional.of(new CatalogUser(owner))` when owner non-null | `getUserDefinedFunctionOwner()` returns real creator identity |
| UDF definer semantics | `UserDefinedFunctionExpanderImpl.withUser(getOwner())` gets query-user fallback → body runs as caller | Gets real UDF creator → body runs as creator | Definer semantics actually activated |
| EXECUTE enforcement | `isRbacDeniedForFunction()` live in `CatalogImpl.getFunctions()` — no tests | Same code, new integration tests | UDF-03 documented and verified |

**Existing, no change needed:**
- `UserDefinedFunctionExpanderImpl.parseAndValidate()`: already calls `.withUser(owner)` on line 136
- `CatalogImpl.isRbacDeniedForFunction()`: already calls `rbacService.hasPrivilege(userName, "EXECUTE", "FUNCTION", ...)`
- `CatalogImpl.getFunctions()`: already calls `isRbacDeniedForFunction()` and returns empty on deny
- `CatalogImpl.getUserDefinedFunctionOwner()`: already calls `getCatalogEntityOwner()` with `orElseGet(() -> new CatalogUser(userName))`

---

## Open Questions

1. **Does `UserDefinedFunctionSerde.toProto()` set owner, or does `UserDefinedFunctionCatalogImpl` set it directly on the `FunctionConfig`?**
   - What we know: `toProto()` converts from the Java `UserDefinedFunction` class, which has no `owner` field.
   - What's unclear: Whether to add `owner` to `UserDefinedFunction` (Java) or stamp it separately in `createOrUpdateFunction()`.
   - Recommendation: Stamp directly in `UserDefinedFunctionCatalogImpl.createOrUpdateFunction()` after calling `toProto()`, using `schemaConfig.getUserName()`. This avoids changing the `UserDefinedFunction` Java class.

2. **Should UPDATE preserve or replace the owner?**
   - What we know: For VDS, `DatasetVersionResource.save()` replaces the owner with the current user; but `DatasetsUtil.toVirtualDatasetVersion()` reads the owner from the UI object, which may carry the original.
   - What's unclear: Business intent for UDF.
   - Recommendation: Preserve original creator on UPDATE (copy from old `FunctionConfig`), consistent with the principle that "owner = creator". A user updating a UDF they don't own should not steal ownership.

3. **Are there integration tests for `getFunctions()` EXECUTE enforcement beyond `validatePrivilege` unit tests?**
   - What we know: Tests for `validatePrivilege(EXECUTE)` exist (lines 1475–1501 in `TestCatalogImpl.java`). No tests exist for `isRbacDeniedForFunction()` path.
   - What's unclear: Whether the planner wants separate test methods or combined.
   - Recommendation: Add distinct test methods for `getFunctions()` deny (empty result) and allow (returns function) paths.

---

## Sources

### Primary (HIGH confidence)
- Direct code inspection — `CatalogEntityOwnershipImpl.java` lines 57-63: FUNCTION branch returns `Optional.empty()`
- Direct code inspection — `function.proto`: no `owner` field present (confirmed by listing all 8 fields)
- Direct code inspection — `UserDefinedFunctionServiceImpl.getOwnerNameFromFunctionConfig()`: returns `null` (stub, line 173-175)
- Direct code inspection — `UserDefinedFunctionExpanderImpl.parseAndValidate()` line 136: `.withUser(dremioUserDefinedFunction.getOwner())` already exists
- Direct code inspection — `CatalogImpl.getFunctions()` lines 1365-1389: `isRbacDeniedForFunction()` already called
- Direct code inspection — `CatalogImpl.getUserDefinedFunctionOwner()` lines 1445-1457: calls `getCatalogEntityOwner()`, falls back to `CatalogUser(userName)`
- Direct code inspection — `TestCatalogImpl.java` lines 1475-1501: existing unit tests for `validatePrivilege(EXECUTE)`

### Secondary (MEDIUM confidence)
- Pattern matching from Phase 8 research: VDS owner fix in `CatalogEntityOwnershipImpl` for DATASET type — identical pattern applies to FUNCTION type
- `DatasetVersionResource.save()` and `DatasetsUtil.toVirtualDatasetVersion()`: show how owner is stamped at VDS creation time — analogous pattern for UDF creation

---

## Metadata

**Confidence breakdown:**
- Root cause (missing proto field, empty FUNCTION branch): HIGH — directly confirmed in source
- Fix approach (add proto field, stamp in createOrUpdateFunction, fix FUNCTION case): HIGH — follows established patterns
- Definer semantics already working (just needs correct owner): HIGH — `withUser()` call confirmed in expander
- EXECUTE enforcement already working (needs tests only): HIGH — `isRbacDeniedForFunction()` confirmed in CatalogImpl

**Research date:** 2026-02-21
**Valid until:** Stable — no external dependencies; all findings are based on internal Dremio code
