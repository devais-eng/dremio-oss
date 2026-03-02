---
phase: 01-design-and-proto-schema
plan: 01
subsystem: auth
tags: [protobuf, proto3, rbac, kvstore, dremio-config, hocon]

# Dependency graph
requires: []
provides:
  - "rbac.proto: Proto3 messages for Role, Grant, Membership with permanent field numbers"
  - "DremioConfig.RBAC_ENABLED constant = \"services.rbac.enabled\""
  - "services.rbac.enabled: false default in dremio-reference.conf"
  - "KV store name constants documented in rbac.proto comments (oss_rbac_roles, oss_rbac_grants, oss_rbac_memberships)"
affects:
  - 02-kvstore-foundation
  - 03-enforcement-core
  - 04-catalog-wiring
  - 05-ddl-and-system-tables
  - 06-rest-api-and-audit

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Proto3 message definitions with java_package=com.dremio.exec.rbac.proto and java_outer_classname=RbacProto"
    - "DremioConfig constants follow HOCON key path convention (string value == config path)"
    - "Feature flag defaults OFF via dremio-reference.conf; requires coordinator restart to change"

key-files:
  created:
    - sabot/kernel/src/main/protobuf/rbac.proto
  modified:
    - common/legacy/src/main/java/com/dremio/config/DremioConfig.java
    - common/legacy/src/main/resources/dremio-reference.conf

key-decisions:
  - "role_id = slugified role name (not UUID), immutable identity key in oss_rbac_roles store"
  - "privilege and object_type stored as strings (not proto enums) for forward compatibility"
  - "KV store key separator is pipe | (not in slugified IDs, dot-paths, or privilege names)"
  - "ADMIN and PUBLIC are synthetic constants -- never written to any KV store"
  - "Feature flag services.rbac.enabled defaults to false -- system is unchanged until explicitly enabled"
  - "uint64 for timestamps (epoch millis) -- consistent with codebase convention in script.proto, FunctionRPC.proto"

patterns-established:
  - "Proto3 file header pattern: Apache license block, syntax=proto3, java_package, java_outer_classname, optimize_for=SPEED, package declaration"
  - "KV store keys: roles use simple string (role_id), grants and memberships use pipe-delimited composite keys"
  - "Use Format.ofString() for KV store key format (NOT Format.ofCompoundFormat()) to preserve human-readable inspection"

requirements-completed:
  - ENFC-09

# Metrics
duration: 8min
completed: 2026-02-17
---

# Phase 1 Plan 01: Proto3 Schema and Feature Flag Summary

**Proto3 RBAC schema (Role, Grant, Membership messages) and OFF-by-default feature flag wiring via DremioConfig constant + dremio-reference.conf entry**

## Performance

- **Duration:** 8 min
- **Started:** 2026-02-17T14:41:13Z
- **Completed:** 2026-02-17T14:49:53Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Created `rbac.proto` with Role (4 fields), Grant (6 fields), Membership (4 fields) messages in proto3 syntax with permanent field numbers
- Verified proto compilation via protoc: produces `RbacProto.java` at `com/dremio/exec/rbac/proto/` as expected by protobuf-maven-plugin
- Added `DremioConfig.RBAC_ENABLED = "services.rbac.enabled"` constant following existing service-toggle naming convention
- Added `services.rbac.enabled: false` default in dremio-reference.conf inside the services block, ensuring system behavior is unchanged on fresh installs

## Task Commits

Each task was committed atomically:

1. **Task 1: Create rbac.proto with Role, Grant, and Membership messages** - `569874205` (feat)
2. **Task 2: Add RBAC feature flag to DremioConfig and dremio-reference.conf** - `07a6c0364` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `sabot/kernel/src/main/protobuf/rbac.proto` - Proto3 messages for RBAC KV store persistence. Role: role_id(1), role_name(2), created_by(3), created_at(4). Grant: role_id(1), object_type(2), object_path(3), privilege(4), granted_by(5), granted_at(6). Membership: user_name(1), role_id(2), granted_by(3), granted_at(4). Includes KV store key format specification in file-level comments.
- `common/legacy/src/main/java/com/dremio/config/DremioConfig.java` - Added `RBAC_ENABLED = "services.rbac.enabled"` constant after NESSIE_SERVICE_LAKEHOUSE_CATALOG_PORT, with Javadoc comment explaining semantics
- `common/legacy/src/main/resources/dremio-reference.conf` - Added `rbac: { enabled: false }` inside services block (after jobs entry), HOCON key path `services.rbac.enabled` matches Java constant value exactly

## Permanent Field Numbers (immutable -- downstream phases depend on these)

| Message    | Field      | Number | Type   | Meaning                              |
|------------|------------|--------|--------|--------------------------------------|
| Role       | role_id    | 1      | string | Slugified role name, identity key    |
| Role       | role_name  | 2      | string | Human-readable name                  |
| Role       | created_by | 3      | string | Username of creator                  |
| Role       | created_at | 4      | uint64 | Epoch millis                         |
| Grant      | role_id    | 1      | string | Which role holds this grant          |
| Grant      | object_type| 2      | string | "VDS" or "FUNCTION" (plain string)   |
| Grant      | object_path| 3      | string | Dot-delimited path, e.g. "s.my_view" |
| Grant      | privilege  | 4      | string | "SELECT", "EXECUTE", "CREATE_VIEW"   |
| Grant      | granted_by | 5      | string | Username of granter                  |
| Grant      | granted_at | 6      | uint64 | Epoch millis                         |
| Membership | user_name  | 1      | string | Username of member                   |
| Membership | role_id    | 2      | string | Which role this membership belongs to|
| Membership | granted_by | 3      | string | Username of granter                  |
| Membership | granted_at | 4      | uint64 | Epoch millis                         |

## KV Store Constants

Declared in rbac.proto comments; full Java constants come in Plan 01-02:

| Store Name           | Key Format                                    | Example                              |
|----------------------|-----------------------------------------------|--------------------------------------|
| oss_rbac_roles       | `{role_id}`                                   | `analyst`                            |
| oss_rbac_grants      | `{role_id}|{object_type}|{object_path}|{privilege}` | `analyst|VDS|schemas.my_view|SELECT` |
| oss_rbac_memberships | `{user_name}|{role_id}`                       | `alice|analyst`                      |

## Decisions Made

- **String vs enum for privilege/object_type:** Stored as plain strings to allow adding new privilege types or object types without proto schema evolution. Proto enums require explicit proto changes for new values.
- **Pipe separator for composite keys:** Safe character -- not present in slugified role IDs (lowercase alphanumeric + hyphen/underscore), dot-delimited object paths, or privilege name strings.
- **Format.ofString() for KV store keys:** NOT Format.ofCompoundFormat() -- preserves human-readable key inspection during debugging. Documented in proto comment block.
- **uint64 for timestamps:** Consistent with `script.proto` and `FunctionRPC.proto` in the codebase (epoch millis convention).
- **RBAC_ENABLED naming:** Shorter form following newer naming trend (vs older `_BOOLEAN`/`_BOOL` suffix pattern). Consistent with `JOBS_ENABLED_BOOL` as most recent precedent.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Maven build requires Java 21 (enforcer plugin, range [21,22)) but only Java 11 and 17 are available on this machine. Used `protoc` directly to verify proto compilation -- protoc 3.6.0 compiled rbac.proto cleanly, producing `RbacProto.java` at `com/dremio/exec/rbac/proto/`. The protobuf-maven-plugin in `sabot/kernel/pom.xml` uses the same source root and will compile identically when a Java 21 JDK is available.

## Next Phase Readiness

- rbac.proto field numbers are frozen and ready for Phase 2 LegacyKVStore wiring
- `DremioConfig.RBAC_ENABLED` is ready for use in Phase 2 store initialization and Phase 3 enforcement
- KV store names (`oss_rbac_roles`, `oss_rbac_grants`, `oss_rbac_memberships`) are documented and ready for Java constant declarations in Plan 01-02
- Feature flag defaults OFF: all existing tests and functionality are unaffected

---
*Phase: 01-design-and-proto-schema*
*Completed: 2026-02-17*

## Self-Check: PASSED

- FOUND: `sabot/kernel/src/main/protobuf/rbac.proto`
- FOUND: `common/legacy/src/main/java/com/dremio/config/DremioConfig.java` (with RBAC_ENABLED)
- FOUND: `common/legacy/src/main/resources/dremio-reference.conf` (with services.rbac.enabled)
- FOUND: `.planning/phases/01-design-and-proto-schema/01-01-SUMMARY.md`
- FOUND: commit `569874205` (feat(01-01): add rbac.proto)
- FOUND: commit `07a6c0364` (feat(01-01): add RBAC_ENABLED config constant)
- Proto compilation verified via protoc 3.6.0: produces `RbacProto.java` at `com/dremio/exec/rbac/proto/`
