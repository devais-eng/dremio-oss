---
phase: 09-udf-rights-verification-and-owner-resolution
plan: 01
subsystem: auth
tags: [udf, protobuf, definer-rights, ownership, rbac]

# Dependency graph
requires:
  - phase: 08-vds-definer-rights-safety-cluster
    provides: CatalogEntityOwnershipImpl DATASET branch pattern and CatalogUser identity model
provides:
  - optional string owner = 9 field on FunctionConfig proto message
  - Owner stamping at UDF creation time via schemaConfig.getUserName()
  - Owner preservation on UDF update via oldFunctionConfig.getOwner()
  - CatalogEntityOwnershipImpl FUNCTION branch returning CatalogUser with correct UDF creator identity
affects:
  - 09-udf-rights-verification-and-owner-resolution (rest of phase)
  - UserDefinedFunctionExpanderImpl.parseAndValidate() now receives real definer identity instead of fallback query user

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Proto owner field at field number N+1 for backward-compatible ownership tracking"
    - "Owner-stamp-at-create / owner-preserve-at-update pattern (same as VDS DatasetConfig.owner)"
    - "CatalogEntityOwnershipImpl switch branch: read typed config, extract owner string, null/empty guard, return CatalogUser"

key-files:
  created: []
  modified:
    - services/namespace/src/main/proto/function.proto
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/udf/UserDefinedFunctionCatalogImpl.java
    - sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java

key-decisions:
  - "FunctionConfig.owner (field 9) stamped directly on FunctionConfig by UserDefinedFunctionCatalogImpl — NOT piped through UserDefinedFunctionSerde.toProto()/fromProto() or UserDefinedFunction Java class (which has no owner field)"
  - "FUNCTION case in CatalogEntityOwnershipImpl uses fully-qualified FunctionConfig class name to avoid ambiguity with other FunctionConfig classes in scope"
  - "Legacy UDFs with null owner fall back to Optional.empty() (query user identity), preserving backward compatibility"
  - "No change to UserDefinedFunctionExpanderImpl or CatalogImpl.getUserDefinedFunctionOwner() — both already correctly wired"

patterns-established:
  - "Proto backward-compatibility: new optional field with next available field number; old records deserialize with field=null"
  - "Ownership resolution: FUNCTION case now mirrors DATASET case exactly in CatalogEntityOwnershipImpl"

requirements-completed: [UDF-01, UDF-02]

# Metrics
duration: 2min
completed: 2026-02-21
---

# Phase 9 Plan 01: UDF Owner Field and Definer Identity Resolution Summary

**FunctionConfig proto gains `owner = 9` field; UserDefinedFunctionCatalogImpl stamps creator on CREATE and preserves owner on UPDATE; CatalogEntityOwnershipImpl FUNCTION branch now returns CatalogUser with real UDF creator identity instead of Optional.empty()**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-21T14:34:38Z
- **Completed:** 2026-02-21T14:36:40Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Added `optional string owner = 9` to FunctionConfig proto message with definer-rights comment; backward-compatible (null on old records)
- UserDefinedFunctionCatalogImpl now stamps `schemaConfig.getUserName()` on UDF creation and preserves `oldFunctionConfig.getOwner()` on UPDATE
- CatalogEntityOwnershipImpl FUNCTION case now reads `nameSpaceContainer.getFunction().getOwner()`, guards on null/empty, and returns `CatalogUser(owner)` — activates definer semantics in UserDefinedFunctionExpanderImpl.parseAndValidate() which already calls `.withUser(owner)`

## Task Commits

Each task was committed atomically:

1. **Task 1: Add owner field to FunctionConfig proto and stamp owner in UserDefinedFunctionCatalogImpl** - `d1c15a43b` (feat)
2. **Task 2: Fix CatalogEntityOwnershipImpl FUNCTION branch to return owner** - `2bc8c4480` (feat)

## Files Created/Modified

- `services/namespace/src/main/proto/function.proto` - Added `optional string owner = 9` to FunctionConfig message for definer-rights identity storage
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/udf/UserDefinedFunctionCatalogImpl.java` - Stamp owner on create, preserve owner on update in createOrUpdateFunction() non-plugin branch
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java` - FUNCTION case replaced: reads function owner, guards null/empty, returns CatalogUser

## Decisions Made

- **Owner stamped on FunctionConfig directly, not via Serde:** `UserDefinedFunctionSerde.toProto()` is not changed. Owner is a persistence/identity concern, not a function definition concern. The Java `UserDefinedFunction` class has no owner field and is not modified.
- **Fully-qualified class name in FUNCTION case:** `com.dremio.service.namespace.function.proto.FunctionConfig` used inline to avoid any import ambiguity; no new import added to CatalogEntityOwnershipImpl.
- **Legacy null-owner falls back gracefully:** Both DATASET and FUNCTION cases return `Optional.empty()` when owner is null/empty — consistent pattern across both entity types.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- UDF-01 (definer-rights identity) and UDF-02 (FUNCTION owner resolution) requirements complete
- UserDefinedFunctionExpanderImpl.parseAndValidate() will now receive the UDF creator's identity via `.withUser(owner)` instead of the query user fallback
- Phase 9 Plan 02 can proceed to add privilege enforcement to UDF SQL execution paths

---
*Phase: 09-udf-rights-verification-and-owner-resolution*
*Completed: 2026-02-21*

## Self-Check: PASSED

All files found:
- FOUND: services/namespace/src/main/proto/function.proto
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/catalog/udf/UserDefinedFunctionCatalogImpl.java
- FOUND: sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogEntityOwnershipImpl.java
- FOUND: .planning/phases/09-udf-rights-verification-and-owner-resolution/09-01-SUMMARY.md

All commits verified:
- FOUND: d1c15a43b (feat(09-01): add owner field to FunctionConfig proto and stamp owner in UserDefinedFunctionCatalogImpl)
- FOUND: 2bc8c4480 (feat(09-01): fix CatalogEntityOwnershipImpl FUNCTION branch to return correct UDF owner)
