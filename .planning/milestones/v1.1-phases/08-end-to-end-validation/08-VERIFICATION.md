---
phase: 08-end-to-end-validation
verified: 2026-02-20T14:00:00Z
status: human_needed
score: 4/6 must-haves verified programmatically; 2/6 human-only; CONN-03 gap noted
re_verification: false
gaps:
  - truth: "Storage credentials vended by Lakekeeper in loadTable() responses propagate through DremioFileIO — Parquet reads succeed without storage permission errors"
    status: partial
    reason: "ROADMAP SC-6 requires credential vending propagation. Code confirms vended credentials are DISCARDED in getTableHandleInternal() — baseTable.io().close() is called and DremioFileIO uses only static Hadoop Configuration. The workaround (static fs.s3a.* in propertyList) works, but CONN-03 as written in REQUIREMENTS.md ('propagates correctly through DremioFileIO') is NOT met. PLAN 08-02 explicitly redefined CONN-03 to accept the workaround + documentation."
    artifacts:
      - path: "plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/AbstractRestCatalogAccessor.java"
        issue: "Lines 383-407: baseTable.io().close() discards vended credentials; DremioFileIO is built from static DatasetFileSystemCache, not from vended credentials in RESTTableOperations"
    missing:
      - "Code change in AbstractRestCatalogAccessor.getTableHandleInternal() to extract vended credentials from baseTable.operations() and pass them to createFS() / DatasetFileSystemCache"
      - "Until then: document as known v1.1 limitation (already done in SUMMARY) and use static creds workaround"
human_verification:
  - test: "CONN-01: Create RESTCATALOG source and verify GOOD state"
    expected: "PUT /api/v3/catalog with RESTCATALOG type returns 200; subsequent GET /api/v3/catalog/by-path/lakekeeper shows state.status = good"
    why_human: "Requires live Lakekeeper Docker stack + running Dremio instance; cannot verify connectivity programmatically"
  - test: "READ-01: Namespace testns browsable in Dremio source tree"
    expected: "GET /api/v3/catalog/by-path/lakekeeper children includes testns; Dremio UI shows testns folder under lakekeeper source"
    why_human: "Requires live Lakekeeper + Dremio; namespace listing is a runtime API call"
  - test: "READ-02: Table users listed under testns namespace"
    expected: "GET /api/v3/catalog/by-path/lakekeeper/testns children includes users table"
    why_human: "Requires live Lakekeeper + Dremio; table listing is a runtime API call"
  - test: "READ-03: SELECT * FROM lakekeeper.testns.users LIMIT 10 returns 10 rows"
    expected: "SQL job completes successfully; results contain 10 rows with columns id (BIGINT), name (VARCHAR), city (VARCHAR)"
    why_human: "Requires live Lakekeeper + MinIO + Dremio; Parquet read is a runtime operation"
  - test: "CONN-02: Source with rest.token in secretPropertyList reaches GOOD state and SELECT works"
    expected: "Source recreated with secretPropertyList[{name: rest.token, value: <token>}] reaches GOOD state; SELECT returns rows"
    why_human: "Requires live Dremio to test token auth path; token is silently accepted by Lakekeeper in no-auth mode"
  - test: "CONN-03: Static MinIO credentials workaround enables Parquet reads"
    expected: "SELECT query succeeds when fs.s3a.* properties are in propertyList; credential vending non-propagation documented as v1.1 limitation"
    why_human: "Runtime validation of S3 connectivity; credential vending code path traced in codebase confirms limitation"
---

# Phase 8: End-to-End Validation Verification Report

**Phase Goal:** Read-only operations against a live Lakekeeper Iceberg REST Catalog work correctly — namespaces browse, tables list, and SELECT queries return results
**Verified:** 2026-02-20T14:00:00Z
**Status:** human_needed (with one CONN-03 gap from ROADMAP contract vs PLAN redefinition)
**Re-verification:** No — initial verification

---

## Context: Nature of This Phase

Phase 8 is a pure validation phase — no code changes were made to the Dremio codebase (0 Java files modified). All 6 success criteria require a running Docker stack (Lakekeeper + MinIO) and a running Dremio instance. Static codebase verification can only confirm the prerequisite artifacts and wiring from Phase 7; runtime outcomes must be taken on trust from the human-verified SUMMARY documentation.

---

## Goal Achievement

### Observable Truths (from ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|---------|
| 1 (CONN-01) | User can create a RESTCATALOG source pointing to Lakekeeper and it reaches GOOD state | ? HUMAN | SUMMARY 08-02 claims verified; confirmed by commit d8649dc8d message; requires live stack to verify |
| 2 (READ-01) | User can browse namespaces in the Dremio UI tree (Lakekeeper GET /v1/namespaces reflected) | ? HUMAN | SUMMARY 08-02 claims "Namespace testns browsable via Dremio API and UI"; requires live stack |
| 3 (READ-02) | User can list tables within a namespace in the Dremio UI tree | ? HUMAN | SUMMARY 08-02 claims "Table users listed under testns"; requires live stack |
| 4 (READ-03) | SELECT executes and returns rows from Iceberg table backed by Parquet | ? HUMAN | SUMMARY 08-02 claims "10 rows with id (BIGINT), name (VARCHAR), city (VARCHAR)"; requires live stack |
| 5 (CONN-02) | Source authenticated via OAuth2 bearer token (rest.token) connects and all read operations work | ? HUMAN | SUMMARY 08-02 claims "bearer token silently accepted by Lakekeeper"; requires live stack |
| 6 (CONN-03) | Storage credentials vended by Lakekeeper in loadTable() responses propagate through DremioFileIO | PARTIAL | Codebase CONFIRMS credentials are NOT propagated (see Gap below). Workaround validated. ROADMAP criterion is not met by code; PLAN 08-02 redefined CONN-03 to accept documented workaround. |

**Score:** 5/6 truths per PLAN definition; 4/6 per ROADMAP SC definition (CONN-03 is partial; runtime truths are human-only)

---

## Prerequisite Artifact Verification (Phase 7 artifacts enabling Phase 8)

These are the only items verifiable statically. Phase 8 has no code artifacts of its own.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `plugins/icebergcatalog/src/main/resources/restcatalog-layout.json` | UI layout for RESTCATALOG source form | VERIFIED | File exists, 86 lines, defines all tabs (General/Connection, Catalog Properties, Secret Credentials, Advanced Options); `config.secretPropertyList` section present |
| `plugins/icebergcatalog/src/main/resources/RESTCATALOG.svg` | Source type icon | VERIFIED | File exists at expected classpath location |
| `plugins/icebergcatalog/src/main/java/.../RestIcebergCatalogPluginConfig.java` | `@SourceType` annotation with `value="RESTCATALOG"`, `label="Iceberg REST Catalog"`, `uiConfig="restcatalog-layout.json"` | VERIFIED | Line 27: `@SourceType(value = "RESTCATALOG", label = "Iceberg REST Catalog", uiConfig = "restcatalog-layout.json")` — exact annotation confirmed |
| `plugins/icebergcatalog/target/dremio-icebergcatalog-plugin-26.0.5-202509091642240013-f5051a07.jar` | Built plugin JAR containing Phase 7 artifacts | VERIFIED | JAR exists (97025 bytes, 2026-02-20 10:05); contains `RESTCATALOG.svg` and `restcatalog-layout.json` at root — confirmed via Python zipfile |
| Distribution JAR at `.../jars/dremio-icebergcatalog-plugin-26.0.5-*.jar` | Deployed plugin JAR = same as target JAR | VERIFIED | Distribution JAR exists (97025 bytes, 2026-02-20 10:11); same size as source JAR (97025 bytes); contains `RESTCATALOG.svg` and `restcatalog-layout.json` — confirmed via Python zipfile |
| `.planning/phases/08-end-to-end-validation/deploy-plugin-jar.sh` | Reproducible JAR deployment script | VERIFIED | File exists, 39 lines, uses `cp` + `jar tf` verification, committed in 95f4e7e94 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `restEndpointUri` (RestIcebergCatalogPluginConfig.restEndpointUri) | `CatalogProperties.URI` in RESTCatalog | `getRestEndpoint()` → `buildCatalogProperties()` line 301 | WIRED | `RestIcebergCatalogPlugin.java:301`: `properties.put(CatalogProperties.URI, getRestEndpoint())` |
| `propertyList` + `secretPropertyList` | Hadoop Configuration + RESTCatalog properties | `getConfigPropertyList()` merges both; `buildCatalogProperties()` calls `config.set(p.name, p.value)` for each | WIRED | `RestIcebergCatalogPlugin.java:134-141, 306-308`: both lists merged and set on both Hadoop conf and properties map |
| `rest.token` in `secretPropertyList` | `Authorization: Bearer` header in Lakekeeper requests | `secretPropertyList` → `configPropertyList` → `buildCatalogProperties()` → `RESTCatalog` SDK reads `rest.token` property | WIRED | Code path confirmed: `IcebergCatalogPluginConfig.java:47-48` (`@Secret` annotation), `RestIcebergCatalogPlugin.java:139-140` (merged into configPropertyList), `RestIcebergCatalogPlugin.java:307` (set on config) |
| `fs.s3a.*` properties in `propertyList` | `DatasetFileSystemCache` Hadoop Configuration | `buildCatalogProperties()` sets all properties via `config.set(p.name, p.value)`; DatasetFileSystemCache uses this configuration | WIRED | `RestIcebergCatalogPlugin.java:307`: `config.set(p.name, p.value)` for every property; the Hadoop Configuration object is the same one that flows to DatasetFileSystemCache |
| Lakekeeper `loadTable()` vended credentials | `DremioFileIO` / `DatasetFileSystemCache` | NOT WIRED — intentional gap | NOT WIRED | `AbstractRestCatalogAccessor.java:383-407`: `baseTable.io().close()` discards vended credentials; `DremioFileIO` is built from `DatasetFileSystemCache` with static conf. This is a documented v1.1 limitation. |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|---------|
| READ-01 | 08-02-PLAN | User can browse namespaces in an Iceberg REST Catalog source | ? HUMAN | SUMMARY claims verified; code path (`getFolderStream()` → `listNamespaces()`) is substantive and wired |
| READ-02 | 08-02-PLAN | User can list tables within a namespace | ? HUMAN | SUMMARY claims verified; code path (`listDatasetIdentifiers()` → `catalog.listTables()`) is substantive and wired |
| READ-03 | 08-02-PLAN | User can SELECT from an Iceberg table and get query results | ? HUMAN | SUMMARY claims "10 rows with id, name, city"; code path (ParquetScanTableFunction → DremioFileIO → DatasetFileSystemCache) is wired but requires runtime validation |
| CONN-01 | 08-01-PLAN, 08-02-PLAN | User can create RESTCATALOG source pointing to Lakekeeper endpoint | ? HUMAN | SUMMARY claims "source reaches GOOD state"; `checkStateInternal()` code path verified; requires live Dremio |
| CONN-02 | 08-02-PLAN | OAuth2/bearer token authentication via catalog properties | ? HUMAN | SUMMARY claims "bearer token silently accepted"; code path verified (secretPropertyList → configPropertyList → RESTCatalog SDK → Authorization header); requires live Dremio |
| CONN-03 | 08-01-PLAN, 08-02-PLAN | Storage credential vending from Lakekeeper propagates correctly through DremioFileIO | PARTIAL | REQUIREMENTS.md says "propagates correctly"; ROADMAP SC-6 says "propagate through DremioFileIO"; code CONFIRMS they do NOT propagate. PLAN 08-02 success_criteria redefined CONN-03 to accept documented workaround. This is a semantic gap between ROADMAP and PLAN. |

**Orphaned requirements check:** No orphaned requirements. REQUIREMENTS.md maps READ-01, READ-02, READ-03, CONN-01, CONN-02, CONN-03 to Phase 8 — all appear in 08-01-PLAN and/or 08-02-PLAN frontmatter.

---

## Anti-Patterns Found

No code changes were made in Phase 8. Anti-pattern scanning skipped (no modified Java files).

The `deploy-plugin-jar.sh` script is substantive (39 lines, proper error checking, `set -euo pipefail`). No placeholders found.

---

## CONN-03 Gap Analysis

**The critical distinction:** There are two different success definitions in play:

| Source | CONN-03 Definition | Met? |
|--------|--------------------|------|
| REQUIREMENTS.md | "Storage credential vending from Lakekeeper **propagates correctly** through DremioFileIO for Parquet reads" | NO — code confirms vended creds are discarded |
| ROADMAP.md SC-6 | "Storage credentials vended by Lakekeeper in loadTable() responses **propagate through DremioFileIO** — Parquet reads succeed without storage permission errors" | NO — same reason |
| 08-02-PLAN success_criteria | "Parquet reads succeed via **static MinIO credentials**; credential vending non-propagation **documented as v1.1 known limitation**" | YES — workaround validated per SUMMARY |

**Code evidence (from `AbstractRestCatalogAccessor.java`, lines 376-407):**

```java
DremioFileIO fileIO = (DremioFileIO) plugin.createIcebergFileIO(
    plugin.createFS(
        SupportsFsCreation.builder()
            .filePath(baseTable.location())  // uses table location only
            .withSystemUserName()
            .withSystemUserId()
            .dataset(dataset)),
    null, dataset, null, null);
return new DremioBaseTable(
    new DremioRESTTableOperations(fileIO, ((HasTableOperations) baseTable).operations()),
    baseTable.name());
// baseTable.io().close() -- vended credentials are DISCARDED here
```

`plugin.createFS()` → `DatasetFileSystemCache` → built from static Hadoop Configuration set at source creation time. Vended credentials from `baseTable.operations()` are never extracted and never passed to `DatasetFileSystemCache`.

**Conclusion:** The workaround works for long-lived MinIO credentials but fails for short-lived IAM/STS tokens. The PLAN acknowledged this and documented it as a v1.1 known limitation. The ROADMAP criterion itself is NOT fully satisfied by the code as implemented.

**Impact on phase status:** This does not block the phase from being considered complete for v1.1 goals — the PLAN explicitly scoped CONN-03 to the static-creds workaround. However, the REQUIREMENTS.md and ROADMAP.md definition of CONN-03 remains unresolved until a code change is made.

---

## Human Verification Required

Phase 8 is entirely human-validated (runtime infrastructure). The following items need confirmation:

### 1. CONN-01: RESTCATALOG Source Reaches GOOD State

**Test:** `curl -s http://localhost:9047/api/v3/catalog/by-path/lakekeeper -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('state', {}).get('status'))"`
**Expected:** Prints `good`
**Why human:** Requires live Lakekeeper Docker stack + live Dremio instance

### 2. READ-01: Namespace testns Browsable

**Test:** `curl -s http://localhost:9047/api/v3/catalog/by-path/lakekeeper -H "Authorization: Bearer $TOKEN"` — look for `testns` in children
**Expected:** `testns` appears as a folder in the source tree
**Why human:** Requires live Lakekeeper + Dremio

### 3. READ-02: Table users Listed Under testns

**Test:** `curl -s http://localhost:9047/api/v3/catalog/by-path/lakekeeper/testns -H "Authorization: Bearer $TOKEN"` — look for `users` in children
**Expected:** `users` table appears under `testns`
**Why human:** Requires live Lakekeeper + Dremio

### 4. READ-03: SELECT Returns 10 Rows

**Test:** Submit `SELECT * FROM lakekeeper.testns.users LIMIT 10` via Dremio SQL API or UI
**Expected:** 10 rows returned with columns id (BIGINT), name (VARCHAR), city (VARCHAR)
**Why human:** Requires live Lakekeeper + MinIO + Dremio; Parquet read is entirely runtime

### 5. CONN-02: OAuth2 Bearer Token Source Works

**Test:** Recreate source with `secretPropertyList: [{name: "rest.token", value: "<token>"}]`; verify source reaches GOOD state and SELECT works
**Expected:** Source health = good; SELECT returns rows even with token set
**Why human:** Requires live Dremio; token forwarding is runtime behavior

### 6. CONN-03: Static Credential Workaround Validated (Partial)

**Test:** Confirm SELECT query succeeded when `fs.s3a.*` properties were in `propertyList`
**Expected:** SELECT returns rows; credential vending limitation documented
**Why human:** Runtime S3 connectivity; code-confirmed that vending does NOT propagate (limitation documented in SUMMARY)

---

## Commit Evidence (SUMMARY Claims)

All claimed outcomes are supported by commits in git history:

| Commit | Date | Content |
|--------|------|---------|
| `95f4e7e94` | 2026-02-20 10:07 | Deploy Phase 7 JAR to distribution; add deploy-plugin-jar.sh |
| `9873c3e40` | 2026-02-20 12:59 | 08-01-SUMMARY — Lakekeeper stack running, test data seeded |
| `d8649dc8d` | 2026-02-20 13:29 | 08-02-SUMMARY — all 6 success criteria documented as passed |

The commit message for `d8649dc8d` explicitly lists: CONN-01 good state, READ-01 testns browsable, READ-02 users listed, READ-03 10 rows, CONN-02 rest.token works, CONN-03 static creds workaround validated.

---

## Summary of Findings

**Phase 7 prerequisite artifacts:** All verified in codebase. Plugin JAR deployed to distribution with correct Phase 7 artifacts. Distribution JAR (97025 bytes, 2026-02-20 10:11) matches source JAR exactly.

**Key wiring (static analysis):** All mechanically verifiable links are WIRED:
- `restEndpointUri` → `CatalogProperties.URI` in RESTCatalog
- `propertyList` + `secretPropertyList` → Hadoop Configuration (via `buildCatalogProperties()`)
- `rest.token` → `Authorization: Bearer` header (via Iceberg SDK)
- `fs.s3a.*` in `propertyList` → `DatasetFileSystemCache` Hadoop conf

**CONN-03 gap:** Vended credentials from Lakekeeper `loadTable()` do NOT propagate to `DremioFileIO`. This is a code-confirmed fact (not a SUMMARY claim). The workaround (static `fs.s3a.*` credentials) was validated and documented as a v1.1 known limitation. PLAN 08-02 explicitly accepted this as CONN-03 complete; REQUIREMENTS.md and ROADMAP.md do not.

**Runtime validation:** 5 of 6 criteria (all but CONN-03 code propagation) are human-validated only — the SUMMARY documentation and commit messages provide the only evidence.

---

*Verified: 2026-02-20T14:00:00Z*
*Verifier: Claude (gsd-verifier)*
