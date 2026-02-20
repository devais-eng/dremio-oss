---
phase: 01-design-and-proto-schema
verified: 2026-02-17T15:30:00Z
status: passed
score: 4/4 success criteria verified
re_verification: false
---

# Phase 1: Design and Proto Schema Verification Report

**Phase Goal:** All foundational design decisions are locked and the proto schema, package structure, and feature flag exist as compilable artifacts -- unblocking all downstream phases
**Verified:** 2026-02-17T15:30:00Z
**Status:** PASSED
**Re-verification:** No -- initial verification

---

## Goal Achievement

### Observable Truths (from ROADMAP.md Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Proto3 file defines Role, Grant, and Membership messages that compile without errors and use no required fields | VERIFIED | `rbac.proto` exists at `sabot/kernel/src/main/protobuf/rbac.proto` with `syntax = "proto3"`, all three messages present, no `required` keyword in any message body, no proto enums used |
| 2 | KV store key format specifications are documented in code comments with composite-key patterns for grants (role+object+privilege) | VERIFIED | File-level comment block in `rbac.proto` lines 24-39 documents all three stores: `oss_rbac_roles` (simple key), `oss_rbac_grants` (pipe-delimited composite), `oss_rbac_memberships` (pipe-delimited composite). `RbacConfig.java` provides `grantKey()` and `membershipKey()` helpers with full Javadoc. |
| 3 | All new RBAC code lives under a package namespace that does not collide with Dremio Enterprise Edition packages | VERIFIED | Package `com.dremio.exec.rbac` used throughout; all KV store names carry `oss_rbac_` prefix (ROLES_STORE=`oss_rbac_roles`, GRANTS_STORE=`oss_rbac_grants`, MEMBERSHIPS_STORE=`oss_rbac_memberships`). Four Java files exist under `sabot/kernel/src/main/java/com/dremio/exec/rbac/`. |
| 4 | A config-file feature flag (services.rbac.enabled, via DremioConfig) for RBAC enforcement exists, defaults to OFF, and requires coordinator restart to change | VERIFIED | `DremioConfig.RBAC_ENABLED = "services.rbac.enabled"` at line 152 of DremioConfig.java (with Javadoc: "requires coordinator restart to change"). `dremio-reference.conf` contains `rbac: { enabled: false }` inside the `services` block (lines 351-353). String value in Java exactly matches HOCON key path. |

**Score:** 4/4 truths verified

---

## Required Artifacts

### Plan 01-01 Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `sabot/kernel/src/main/protobuf/rbac.proto` | Proto3 message definitions for Role, Grant, Membership | VERIFIED | File exists (63 lines). Contains `syntax = "proto3"`, `java_package = "com.dremio.exec.rbac.proto"`, `java_outer_classname = "RbacProto"`, `optimize_for = SPEED`. Three messages with correct field numbers and types. KV key format comment block present. No enums, no required fields. |
| `common/legacy/src/main/java/com/dremio/config/DremioConfig.java` | RBAC_ENABLED config constant | VERIFIED | Line 152: `public static final String RBAC_ENABLED = "services.rbac.enabled";` with Javadoc comment. |
| `common/legacy/src/main/resources/dremio-reference.conf` | Default OFF value for RBAC feature flag | VERIFIED | Lines 351-353: `rbac: { enabled: false }` inside the `services` block (which closes at line 354). |

### Plan 01-02 Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RbacConfig.java` | Store name constants and composite key format helpers | VERIFIED | File exists (74 lines). Declares ROLES_STORE, GRANTS_STORE, MEMBERSHIPS_STORE with `oss_rbac_` prefix; KEY_SEP = "|"; grantKey() and membershipKey() static methods with full Javadoc; private constructor prevents instantiation. |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/RoleStore.java` | KVStoreCreationFunction for oss_rbac_roles store | VERIFIED | File exists (65 lines). Contains `KVStoreCreationFunction<String, Role>` inner class `StoreCreator`. Uses `Format.ofString()` for key, `Format.ofProtobuf(Role.class)` for value. References `RbacConfig.ROLES_STORE`. @Inject constructor with `Provider<KVStoreProvider>`. |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/GrantStore.java` | KVStoreCreationFunction for oss_rbac_grants store with composite key | VERIFIED | File exists (69 lines). Contains `KVStoreCreationFunction<String, Grant>` inner class `StoreCreator`. Uses `Format.ofString()` for key, `Format.ofProtobuf(Grant.class)` for value. References `RbacConfig.GRANTS_STORE`. Javadoc documents composite key via `{@link RbacConfig#grantKey}`. |
| `sabot/kernel/src/main/java/com/dremio/exec/rbac/MembershipStore.java` | KVStoreCreationFunction for oss_rbac_memberships store with composite key | VERIFIED | File exists (69 lines). Contains `KVStoreCreationFunction<String, Membership>` inner class `StoreCreator`. Uses `Format.ofString()` for key, `Format.ofProtobuf(Membership.class)` for value. References `RbacConfig.MEMBERSHIPS_STORE`. Javadoc documents composite key via `{@link RbacConfig#membershipKey}`. |

---

## Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `DremioConfig.java` | `dremio-reference.conf` | String constant matches HOCON key path | WIRED | `DremioConfig.RBAC_ENABLED = "services.rbac.enabled"` (line 152); `dremio-reference.conf` has `services.rbac.enabled` at path `services { rbac { enabled: false } }`. Exact string match confirmed. |
| `rbac.proto` | proto-maven-plugin output | `java_outer_classname = "RbacProto"` (plugin compiles all .proto in src/main/protobuf/) | VERIFIED (structure) | `java_outer_classname = "RbacProto"` present in proto file. Plugin configured in sabot/kernel/pom.xml to compile all .proto files in src/main/protobuf/. Maven compile cannot be run (Java 21 unavailable); proto3 syntax verified by inspection -- no syntax violations found. |
| `RoleStore.java` | `rbac.proto` | `Format.ofProtobuf(Role.class)` -- Role class generated by protobuf-maven-plugin | WIRED | `Format.ofProtobuf(Role.class)` at RoleStore.java line 61. Import: `com.dremio.exec.rbac.proto.RbacProto.Role`. |
| `GrantStore.java` | `rbac.proto` | `Format.ofProtobuf(Grant.class)` | WIRED | `Format.ofProtobuf(Grant.class)` at GrantStore.java line 65. Import: `com.dremio.exec.rbac.proto.RbacProto.Grant`. |
| `MembershipStore.java` | `rbac.proto` | `Format.ofProtobuf(Membership.class)` | WIRED | `Format.ofProtobuf(Membership.class)` at MembershipStore.java line 65. Import: `com.dremio.exec.rbac.proto.RbacProto.Membership`. |
| `RbacConfig.java` | `GrantStore.java` | `RbacConfig.grantKey()` used in GrantStore | PARTIAL (Phase 1 by design) | `{@link RbacConfig#grantKey}` reference in GrantStore Javadoc (line 34). No runtime call yet -- Phase 2 will add CRUD methods. This is intentional stub behavior. |
| `RbacConfig.java` | `MembershipStore.java` | `RbacConfig.membershipKey()` used in MembershipStore | PARTIAL (Phase 1 by design) | `{@link RbacConfig#membershipKey}` reference in MembershipStore Javadoc (line 34). No runtime call yet -- Phase 2 will add CRUD methods. This is intentional stub behavior. |

**Note on PARTIAL key links:** Both GrantStore and MembershipStore reference their respective key helpers only in Javadoc, not in runtime code. This is by design: Phase 1 creates stubs; Phase 2 adds the CRUD methods that will actually call `RbacConfig.grantKey()` and `RbacConfig.membershipKey()`. The plan explicitly documents this split ("Phase 2 will add: get, put, delete..." comments appear in the store source files). This does not constitute a gap.

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| ENFC-09 | 01-01-PLAN.md, 01-02-PLAN.md | Enforcement gated behind a feature flag (defaults to OFF); can be enabled via system option | SATISFIED | `DremioConfig.RBAC_ENABLED = "services.rbac.enabled"` provides the constant. `dremio-reference.conf` defaults it to `false`. Package scaffold provides the structure for Phase 4 to gate enforcement behind `dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)`. |

**Orphaned requirements check:** REQUIREMENTS.md traceability table maps ENFC-09 to Phase 1 only. No other requirements are mapped to Phase 1. Both plans (`01-01-PLAN.md` and `01-02-PLAN.md`) declare `requirements: [ENFC-09]`. Coverage is complete.

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `RoleStore.java` | 48 | `// Phase 2 will add: get, put, delete, list methods here.` | INFO | Intentional stub marker. Phase 1 goal is scaffolding; Phase 2 fills CRUD. Not a blocker. |
| `GrantStore.java` | 52 | `// Phase 2 will add: get, put, delete, listByRole methods here.` | INFO | Same as above. Intentional. |
| `MembershipStore.java` | 52 | `// Phase 2 will add: get, put, delete, listByUser, listByRole methods here.` | INFO | Same as above. Intentional. |

No blockers found. All anti-patterns are intentional Phase 1 stubs with clear Phase 2 handoff markers.

---

## Human Verification Required

### 1. Maven Compile Verification

**Test:** Run `mvn compile -pl sabot/kernel -am -q` from repo root with Java 21.
**Expected:** Build succeeds with zero errors; generated Java class `RbacProto.java` appears under `sabot/kernel/target/generated-sources/protobuf/java/com/dremio/exec/rbac/proto/`.
**Why human:** Java 21 is not available on this machine. Proto3 syntax has been verified by inspection (correct header, no enums, no required fields, valid field types), and the SUMMARY notes `protoc 3.6.0` compiled rbac.proto cleanly. Maven build verification requires a Java 21 JDK.

---

## Commit History Verification

All four commits claimed in SUMMARYs are verified present in git history:

| Commit | Task | Status |
|--------|------|--------|
| `569874205` | feat(01-01): add rbac.proto | VERIFIED in `git log` |
| `07a6c0364` | feat(01-01): add RBAC_ENABLED config constant | VERIFIED in `git log` |
| `88e030dee` | feat(01-02): add RbacConfig | VERIFIED in `git log` |
| `6ca3e27ec` | feat(01-02): add RoleStore, GrantStore, MembershipStore | VERIFIED in `git log` |

---

## Gaps Summary

No gaps found. All four success criteria are satisfied by code that exists and contains the expected content. All seven declared artifacts are present and substantive. Key links are wired (with two Javadoc-only links that are intentional Phase 1 stubs). The sole requirement ENFC-09 is satisfied. No blocker anti-patterns found.

---

_Verified: 2026-02-17T15:30:00Z_
_Verifier: Claude (gsd-verifier)_
