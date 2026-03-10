---
phase: 24
plan: 03
subsystem: icebergcatalog-integration-tests
tags: [integration-test, nessie, minio, testcontainers, branch-aware]
dependency_graph:
  requires: [24-01, 24-02]
  provides: [ITRestIcebergCatalogBranchAware, end-to-end-branch-validation]
  affects: [RestIcebergCatalogPlugin, NessieContainer]
tech_stack:
  added: [ITRestIcebergCatalogBranchAware, Iceberg-Java-RESTCatalog-setup]
  patterns: [testcontainers-manual-lifecycle, iceberg-rest-catalog-setup, url-encoded-branch-names, application-global-s3-auth]
key_files:
  created:
    - plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/ITRestIcebergCatalogBranchAware.java
  modified:
    - plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java
    - plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/TestRestIcebergCatalogPlugin.java
    - tools/testcontainers/nessie/src/main/java/com/dremio/testcontainers/nessie/NessieContainer.java
decisions:
  - "Use Iceberg Java RESTCatalog client for test setup to create real tables in Nessie+MinIO (not fake v1 API commits with phantom metadata)"
  - "Use APPLICATION_GLOBAL auth with AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY env vars for Nessie-to-MinIO credentials instead of STATIC secrets system"
  - "URL-encode branch names in createBranchScopedAccessor to support slashes (e.g., feature/my-branch -> feature%2Fmy-branch)"
  - "Probe branchExists via datasetExists(probe_path) to get real 400/404 differentiation from Nessie"
  - "Mark space-branch test @Disabled since Nessie rejects branch names with spaces at HTTP level"
metrics:
  duration: "~2 hours (context continuation from previous session)"
  completed: "2026-03-10"
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 3
---

# Phase 24 Plan 03: Integration Tests Summary

Integration tests validating branch-aware REST catalog against a real Nessie server via NessieContainer + MinioContainer.

## What Was Built

`ITRestIcebergCatalogBranchAware` - JUnit 5 integration test class (IT prefix for DremioTestcontainersUsageValidator). Uses real Nessie Docker container backed by MinIO S3-compatible storage. 15 tests pass, 1 skipped (`@Disabled` space-branch test, Nessie rejects spaces in branch names).

**Test coverage (16 tests):**
- Cross-branch dataset handles (4): main/dev branch handle retrieval, same-table-different-branches isolation, table-on-one-branch-not-other
- Branch existence validation (3): branchExists true for valid branches, false for nonexistent, error message path validation
- Special character branch names (3): slash (`feature/my-branch`), dot (`release-1.0`), spaces (`@Disabled`)
- Nessie detection (2): isNessieDetected, getDefaultBranch returns "main"
- Regression + mixed-source (2): enableNessie=false isWrapperFor=false, mixed nessie/plain plugin differentiation via isWrapperFor
- resolveVersionContext (2): BRANCH type, NOT_SPECIFIED falls back to default branch

## Key Decisions

**Iceberg Java RESTCatalog for test data setup:** Instead of using the Nessie v1 commit API with fake metadata locations, we use `org.apache.iceberg.rest.RESTCatalog` Java client to create real tables. This writes actual Iceberg metadata to MinIO, making `tableExists()` work end-to-end (Iceberg's `tableExists` calls `loadTable` which reads S3 metadata).

**APPLICATION_GLOBAL auth with AWS env vars:** Nessie's STATIC auth mode uses a secrets system that requires complex Quarkus configuration. Using `APPLICATION_GLOBAL` with standard `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` container env vars is simpler and correctly uses the AWS SDK default credential provider chain.

**URL-encode branch names in accessor URI:** `createBranchScopedAccessor` now URL-encodes the branch name before appending to the REST endpoint URI. This allows slashes (`feature/my-branch` → `feature%2Fmy-branch`) to be treated as part of the branch name rather than URL path separators.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] NessieContainer.withS3Warehouse: wrong auth type and credential format**
- **Found during:** Task 1 (Nessie container startup for table creation)
- **Issue:** STATIC auth type requires Nessie's secrets system with complex Quarkus property format. The properties `nessie.catalog.service.s3.default-options.access-key.id` and `.secret` were not being picked up, causing "Missing access key and secret for STATIC authentication mode" when creating tables.
- **Fix:** Changed to `APPLICATION_GLOBAL` auth type with `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` env vars (standard AWS SDK credential provider chain)
- **Files modified:** `tools/testcontainers/nessie/src/main/java/com/dremio/testcontainers/nessie/NessieContainer.java`
- **Commit:** d81d0ec25

**2. [Rule 1 - Bug] RestIcebergCatalogPlugin.branchExists: probe always returned true**
- **Found during:** Task 1 (branchExists unit test failures during development)
- **Issue 1:** Original probe `accessor.namespaceExists(List.of())` caused `IndexOutOfBoundsException` in `namespaceFromPath` (requires at least 1 element).
- **Issue 2:** After fix to `namespaceExists(List.of(name))`, still always returned true because `RESTCatalog.namespaceExists(Namespace.of())` short-circuits to true without making an HTTP call.
- **Fix:** Changed to `accessor.datasetExists(Arrays.asList(name, "__branch_probe__", "__exists_check__"))`. For nonexistent branches: Nessie returns 400 `NoSuchReferenceException` → `BadRequestException` (not caught by `datasetExists`) → caught by `branchExists` → returns false. For valid branches: Nessie returns 404 `NoSuchTableException` → `datasetExists` returns false → `branchExists` returns true.
- **Files modified:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java`
- **Commit:** d81d0ec25

**3. [Rule 1 - Bug] RestIcebergCatalogPlugin.createBranchScopedAccessor: slash in branch name broke URL routing**
- **Found during:** Task 2 (`testBranchWithSlashInName` failed - branchExists returned false for `feature/my-branch`)
- **Issue:** The branch name was appended raw to the REST endpoint URI (`restEndpoint + "/" + branchName`), so `feature/my-branch` became `http://nessie:19120/iceberg/feature/my-branch`, which Nessie interprets as namespace `feature` + branch `my-branch` rather than branch `feature/my-branch`.
- **Fix:** URL-encode the branch name using `URLEncoder.encode(branchName, StandardCharsets.UTF_8).replace("+", "%20")` before appending, so `feature/my-branch` becomes `feature%2Fmy-branch`.
- **Files modified:** `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/RestIcebergCatalogPlugin.java`
- **Commit:** d81d0ec25

**4. [Rule 2 - Missing] TestRestIcebergCatalogPlugin: updated unit tests for new branchExists probe**
- **Found during:** Bug fix #2 above
- **Issue:** Unit tests mocked `namespaceExists()` but the implementation changed to `datasetExists()`.
- **Fix:** Updated `testBranchExistsReturnsTrueForValidBranch` and `testBranchExistsReturnsFalseForInvalidBranch` to mock `datasetExists()` with the probe path `[name, "__branch_probe__", "__exists_check__"]`.
- **Files modified:** `plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/TestRestIcebergCatalogPlugin.java`
- **Commit:** d81d0ec25

**5. [Rule 3 - Blocking] testMixedSourceDispatchDifferentiation: Wrapper.unwrap() throws instead of returning null**
- **Found during:** Task 2 test run
- **Issue:** Test expected `plainPlugin.unwrap(SupportsBranchAwareRestCatalog.class)` to return `null`, but `Wrapper.unwrap()` throws `IllegalArgumentException` when `isWrapperFor` returns false (documented behavior).
- **Fix:** Changed test to use `assertFalse(plainPlugin.isWrapperFor(...))` instead of `unwrap` + null assertion.
- **Files modified:** `ITRestIcebergCatalogBranchAware.java`
- **Commit:** 6194dcee7

**6. [Rule 3 - Blocking] Test setup: Java version required Java 21 (ErrorProne compiled for Java 17)**
- **Found during:** Initial test run attempt
- **Issue:** `java.lang.UnsupportedClassVersionError: com/google/errorprone/ErrorProneJavacPlugin` — system default Java was 11, but ErrorProne needed Java 17+.
- **Fix:** All Maven commands use `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` to use the installed Java 21.
- **Impact:** No code change; build environment documentation only.

**7. [Rule 3 - Blocking] DremioTestcontainersUsageValidator: System.setProperty too late**
- **Found during:** First test run with Java 21
- **Issue:** `System.setProperty("dremio.testcontainers.enabled", "true")` in `@BeforeAll` runs after `@Container`-managed MinioContainer starts, so the validator fires before the property is set.
- **Fix:** Removed `System.setProperty` from test code; pass `-Ddremio.testcontainers.enabled=true` as JVM system property in Maven command.
- **Files modified:** `ITRestIcebergCatalogBranchAware.java`
- **Commit:** 6194dcee7

**8. [Rule 1 - Bug] Test setup: fake Nessie v1 commit API with phantom metadata caused S3 read failures**
- **Found during:** Task 2 (getDatasetHandle tests)
- **Issue:** The original setup used Nessie's v1 commit API to register tables with fake `metadataLocation: "s3://warehouse/ns/table_a/meta.json"`. When `tableExists()` is called, Iceberg's REST catalog calls `loadTable()` which tries to read this nonexistent S3 file, causing `Failed to read table metadata from s3://warehouse/...`.
- **Fix:** Replaced Nessie v1 commit API with Iceberg Java `RESTCatalog` client to create actual tables. The `createTable()` call creates real Iceberg metadata in MinIO, making `tableExists()` work.
- **Files modified:** `ITRestIcebergCatalogBranchAware.java` (removed `commitNessieEntries` helper, added `openIcebergCatalog` helper)
- **Commit:** 6194dcee7

## Test Results

```
Tests run: 16, Failures: 0, Errors: 0, Skipped: 1
- 15 passing
- 1 skipped: testBranchWithSpacesInName (@Disabled - Nessie rejects spaces in branch names)
```

Unit tests also pass after deviations:
```
Tests run: 39, Failures: 0, Errors: 0, Skipped: 0 (TestRestIcebergCatalogPlugin)
```

## Self-Check: PASSED

Files created:
- `/home/filippo/PycharmProjects/dremio-oss/plugins/icebergcatalog/src/test/java/com/dremio/plugins/icebergcatalog/store/ITRestIcebergCatalogBranchAware.java` - FOUND

Commits:
- d81d0ec25 (fix: deviation fixes) - FOUND
- 6194dcee7 (feat: IT test class) - FOUND
