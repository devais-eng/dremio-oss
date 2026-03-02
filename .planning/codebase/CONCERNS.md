# Codebase Concerns

**Analysis Date:** 2026-02-17

---

## Tech Debt

**Guava Cache Usage (DX-51884 - Widespread):**
- Issue: 42+ production locations use deprecated Guava `CacheBuilder`/`LoadingCache` instead of the approved Caffeine cache abstraction. All are suppressed with `@SuppressWarnings("NoGuavaCacheUsage")` as a deliberate deferral.
- Files: `services/grpc/src/main/java/com/dremio/service/conduit/client/ConduitProviderImpl.java`, `services/tokens/src/main/java/com/dremio/service/tokens/TokenManagerImpl.java`, `services/transientstore/src/main/java/com/dremio/datastore/transientstore/InMemoryTransientStore.java`, `services/namespace/src/main/java/com/dremio/service/namespace/BatchLookupOptimiser.java`, `services/arrow-flight/src/main/java/com/dremio/service/flight/TokenCacheFlightSessionManager.java`, `services/datastore/src/main/java/com/dremio/datastore/indexed/LuceneSearchIndex.java`, `plugins/hive/src/main/java/com/dremio/exec/store/hive/HiveImpersonationUtil.java`, `plugins/pdfs/src/main/java/com/dremio/exec/store/dfs/PseudoDistributedFileSystem.java`, and ~34 more.
- Impact: Guava cache lacks advanced metrics and eviction control available in Caffeine; inconsistency with newer code using Caffeine.
- Fix approach: Replace all `CacheBuilder.newBuilder()` calls with the project's Caffeine-based abstractions, tracked under DX-51884.

**Legacy KV Store Layer (Not cleaned up):**
- Issue: The entire `services/datastore` module has a parallel "Legacy" API layer that is fully `@Deprecated` but still in active use across the codebase. Classes include `LegacyKVStoreProvider`, `LegacyKVStore`, `LegacyIndexedStore`, `LegacyStoreCreationFunction`, and adapter wrappers.
- Files: `services/datastore/src/main/java/com/dremio/datastore/api/LegacyKVStoreProvider.java`, `services/datastore/src/main/java/com/dremio/datastore/api/LegacyKVStore.java`, `services/datastore/src/main/java/com/dremio/datastore/adapter/LegacyKVStoreProviderAdapter.java`, `services/datastore/src/main/java/com/dremio/datastore/utility/LegacyStoreLoader.java` (13 files total).
- Impact: Maintenance burden; two code paths for the same functionality; new contributors may use the wrong API.
- Fix approach: Migrate all callers off the Legacy API; then delete the deprecated classes.

**Legacy Scheduler Infrastructure (DX-68199):**
- Issue: The old clustered singleton scheduler still exists alongside the new distributed singleton implementation. Multiple wrappers (`ModifiableWrappedSchedulerService`, `ModifiableLocalSchedulerService`) exist only to shim the old interface, with explicit TODO comments to remove them.
- Files: `services/scheduler/src/main/java/com/dremio/service/scheduler/ModifiableWrappedSchedulerService.java`, `services/scheduler/src/main/java/com/dremio/service/scheduler/ModifiableLocalSchedulerService.java`, `services/scheduler/src/main/java/com/dremio/service/scheduler/Schedule.java`.
- Impact: Code complexity; tests for the old path remain (`TestTaskLeaderSchedulerService` lines 536, 625, 639, 1086 explicitly note they test obsolete code).
- Fix approach: Complete migration to new distributed singleton (DX-68199); remove shim classes and obsolete test code.

**HybridJobsService Not Removed (DX-19547):**
- Issue: `HybridJobsService` is marked `@Deprecated // TODO DX-19547: Remove HJS` but still present in the codebase.
- Files: `services/jobs/src/main/java/com/dremio/service/jobs/HybridJobsService.java`
- Impact: Dead code adding maintenance surface.
- Fix approach: Verify no remaining callers and delete the class.

**DatasetVersionResource Over-sized (1422 lines):**
- Issue: Nearly every transformation endpoint method in `DatasetVersionResource` has a `// TODO - Move transformations to their own Resource file` comment. The same comment appears 20+ times in the same file.
- Files: `dac/backend/src/main/java/com/dremio/dac/explore/DatasetVersionResource.java`
- Impact: God class; makes it difficult to add, test, or reason about individual transformations.
- Fix approach: Extract transformation endpoints into dedicated resource classes.

**DataplanePlugin Inheriting FileSystemConf (DX-92696):**
- Issue: `AbstractDataplanePluginConfig` inappropriately extends `FileSystemConf`, coupling the Nessie/Iceberg data plane to the filesystem plugin configuration model.
- Files: `plugins/dataplane/src/main/java/com/dremio/plugins/dataplane/store/AbstractDataplanePluginConfig.java`
- Impact: Unintended configuration inheritance; harder to evolve either API independently.
- Fix approach: Decouple per DX-92696; DataplanePlugin config should have its own base class.

**DatasetListingInvoker Opaque Object / Unnecessary Copies (DX-10857):**
- Issue: `DatasetListingInvoker` uses opaque byte objects instead of protobuf, causing unnecessary serialization/deserialization copies at several points. Server and client-side code are not separated (DX-10861).
- Files: `services/namespace/src/main/java/com/dremio/service/listing/DatasetListingInvoker.java`
- Impact: Performance overhead; blurs client/server boundary.
- Fix approach: Change to protobuf types (DX-10857); separate client/server code (DX-10861).

**NamespaceInternalKey SQL Dependency:**
- Issue: `NamespaceInternalKey` is explicitly noted as needing to be made independent of SQL, but the dependency remains.
- Files: `services/namespace/src/main/java/com/dremio/service/namespace/NamespaceInternalKey.java`
- Impact: Namespace key representation tied to SQL conventions; makes future key format changes risky.
- Fix approach: Decouple key generation from SQL formatting.

**ExpressionSplitCache and FileSystemPlugin Using Guava Cache (DX-51884):**
- Issue: Core execution-path classes `ExpressionSplitCache` and `FileSystemPlugin` use Guava cache with suppressed warnings.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/expr/ExpressionSplitCache.java`, `sabot/kernel/src/main/java/com/dremio/exec/store/dfs/FileSystemPlugin.java`, `sabot/kernel/src/main/java/com/dremio/exec/store/dfs/ImpersonationUtil.java`
- Impact: Same as general DX-51884 debt; hot path affected.
- Fix approach: Covered under DX-51884 migration.

**URI Scheme Fixup Hack in DataplanePlugin (DX-97346):**
- Issue: `DataplanePlugin` calls `removeUriScheme()` to strip URI schemes from Iceberg table locations as a workaround for an unresolved URI handling issue.
- Files: `plugins/dataplane/src/main/java/com/dremio/plugins/dataplane/store/DataplanePlugin.java` (lines 966-977, 3101)
- Impact: Fragile workaround; may break with Iceberg or cloud provider changes.
- Fix approach: Proper URI handling tracked under DX-97346.

---

## Known Bugs

**TypedFieldId Bug (Untracked):**
- Symptoms: Comment says "there is a bug here with some things" - vague acknowledgment of incorrect behavior in field ID resolution.
- Files: `sabot/vector-tools/src/main/java/com/dremio/exec/record/TypedFieldId.java` (line 259)
- Trigger: Unknown; specific to certain type/field configurations.
- Workaround: None documented.

**TIMESTAMPADD(SQL_TSI_FRAC_SECOND) Function Broken (DX-11268):**
- Symptoms: `TIMESTAMPADD` with fractional seconds does not work correctly. Multiple test methods are annotated with this bug reference and show expected failures.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/util/TSI.java` (lines 42, 48), `sabot/kernel/src/test/java/com/dremio/TestFunctionsQuery.java` (lines 1237, 1248, 1265, 1313)
- Trigger: Any query using `TIMESTAMPADD(SQL_TSI_FRAC_SECOND, n, timestamp)`.
- Workaround: None; function call silently returns wrong results.

**VectorContainer ZeroVector/NullVector Mismatch (DX-34589):**
- Symptoms: Empty array lists are represented as `NullVector` in Arrow, but code checks for `ZeroVector`. The current logic has a workaround to avoid false assertions.
- Files: `sabot/vector-tools/src/main/java/com/dremio/exec/record/VectorContainer.java` (line 318)
- Trigger: Queries involving empty list types.
- Workaround: Current `isNotNullVector()` check papers over the issue.

**IcebergNessieVersionedCommand Table Deletion Incomplete:**
- Symptoms: The physical file deletion is commented out in `deleteTable()`. Only the Nessie metadata entry is removed, leaving orphaned Iceberg files.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/nessie/IcebergNessieVersionedCommand.java` (line 52)
- Trigger: Any `DROP TABLE` on a versioned (Nessie-backed) Iceberg table.
- Workaround: Manual file cleanup or external garbage collection.

**NamespaceServiceImpl Potential NPE (DX-4490):**
- Symptoms: `idInExistingContainer` could be null, leading to potential NPE. Return value of namespace operations is also unchecked in several callers.
- Files: `services/namespace/src/main/java/com/dremio/service/namespace/NamespaceServiceImpl.java` (lines 769, 2298, 2384)
- Trigger: Edge cases in namespace container operations.
- Workaround: None documented.

**CatalogImpl Missing isPresent() Check (DX-??):**
- Symptoms: `handle.get()` called without a preceding `isPresent()` check.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java` (line 2270)
- Trigger: Catalog operation on a non-existent item.
- Workaround: None.

**Join Recommender is a No-op (DX-101113):**
- Symptoms: `JobsBasedRecommender.getJoinRecommendations()` always uses an empty `fieldOriginsList`, making the entire join recommendation engine return empty results.
- Files: `dac/backend/src/main/java/com/dremio/dac/explore/join/JobsBasedRecommender.java` (lines 76-88)
- Trigger: Any UI request for join recommendations.
- Workaround: None; feature is silently non-functional.

**RpcEncoder Finally Block Commented Out (FIXME):**
- Symptoms: The `finally` block in `RpcEncoder.encode()` that should release RPC message byte buffers is commented out. The FIXME comment acknowledges this without resolution.
- Files: `services/base-rpc/src/main/java/com/dremio/exec/rpc/RpcEncoder.java` (lines 195-199)
- Trigger: Every RPC message encoding operation.
- Workaround: Relies on GC for buffer reclaim; potential for off-heap memory pressure under high RPC load.

---

## Security Considerations

**SSL Certificate Validation Disabled (DX-12920):**
- Risk: `sslContextFactory.setValidateCerts(true)` and `setValidatePeerCerts()` are commented out, meaning SSL certificate validation is not enforced in the HTTPS connector. Server may start with invalid or expired certificates without error.
- Files: `dac/backend/src/main/java/com/dremio/dac/server/HttpsConnectorGenerator.java` (lines 127-131)
- Current mitigation: Certificates are loaded; only chain/peer validation is skipped.
- Recommendations: Re-enable certificate validation per DX-12920; add startup check to fail fast on invalid certificates.

**Token Cache Not Broadcast Across Coordinators:**
- Risk: Token invalidation (e.g., logout, revocation) is only applied to the local coordinator's cache. Other coordinators continue accepting the invalidated token until cache expiry.
- Files: `services/tokens/src/main/java/com/dremio/service/tokens/TokenManagerImpl.java` (lines 129-130)
- Current mitigation: Cache expiration eventually propagates; short expiry times reduce the window.
- Recommendations: Implement cross-coordinator token invalidation broadcast.

**Arrow Flight Client Address Not Captured (DX-25278):**
- Risk: When creating tokens via Arrow Flight auth, the `clientAddress` parameter is documented as a no-op (`// TODO noop`). Client address is not recorded for audit/security purposes.
- Files: `services/arrow-flight/src/main/java/com/dremio/service/flight/utils/DremioFlightAuthUtils.java` (line 103), `services/tokens/src/main/java/com/dremio/service/tokens/TokenManager.java` (line 81)
- Current mitigation: None.
- Recommendations: Capture and log client address for security audit trail.

**SSL KeyStore Password Not Set in Jetty Context (DX-12920):**
- Risk: `sslContextFactory.setKeyStorePassword()` is commented out, meaning the Jetty SSL context may not be configured with the keystore password.
- Files: `dac/backend/src/main/java/com/dremio/dac/server/HttpsConnectorGenerator.java` (line 96)
- Current mitigation: `setKeyManagerPassword` is set; KeyStore is loaded directly in code.
- Recommendations: Resolve per DX-12920.

**JWKS Keystore Admin Command Missing:**
- Risk: There is no admin command to re-create the JWKS keystore and rotate key pairs if the keystore is compromised. Only scheduled rotation exists.
- Files: `services/tokens/src/main/java/com/dremio/service/tokens/jwks/SystemJWKSetManager.java` (line 588)
- Current mitigation: Scheduled key rotation reduces impact window.
- Recommendations: Implement emergency keystore re-creation command noted in phase 2 TODO.

---

## Performance Bottlenecks

**FileSelection O(N^2) Algorithm:**
- Problem: `FileSelection` uses an O(N^2) algorithm when `fileAttributesList` is large, comparing every entry against every other.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/dfs/FileSelection.java` (line 315)
- Cause: Nested iteration without indexing.
- Improvement path: Replace with a HashSet-based lookup for O(N) deduplication.

**IcebergTableWrapper Reads All Manifest Files:**
- Problem: Getting row counts or bounds for an Iceberg table iterates all manifest files instead of using incremental/delta-based computation.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/IcebergTableWrapper.java` (lines 161, 170, 394)
- Cause: Iceberg does not track per-row-group counts; delta-based optimization not implemented.
- Improvement path: Cache computed statistics; implement delta-based update path as noted in TODOs.

**DremioFileSystemCache Configuration Cloning Heap Risk:**
- Problem: `DremioFileSystemCache` clones the entire Hadoop `Configuration` object for each filesystem instance, which can consume significant heap memory with many large configurations.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/dfs/DremioFileSystemCache.java` (line 58)
- Cause: Configuration cloning is used to set disable-cache flags without mutating shared state.
- Improvement path: Use a lightweight wrapper or lazy configuration modification.

**Reflection Materialization Cache: Correctness vs. Performance Tradeoff:**
- Problem: `MaterializationCache` uses a simple metadata hash rather than a dataset-content hash to determine cache validity. This may cause stale cache hits.
- Files: `services/accelerator/src/main/java/com/dremio/service/reflection/descriptor/MaterializationCache.java` (line 509)
- Cause: Dataset hash not available at the point of cache comparison.
- Improvement path: Incorporate dataset hash into cache key comparison.

**IcebergCommitOpHelper Avoidable Copy:**
- Problem: In the Iceberg commit path, a `toByteArray()` call introduces an avoidable memory copy of metadata bytes.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/manifestwriter/IcebergCommitOpHelper.java` (line 643)
- Cause: API mismatch between internal representation and protobuf serialization.
- Improvement path: Use zero-copy streaming serialization.

**BatchLookupOptimiser Uses Guava Cache (On Read Path):**
- Problem: `BatchLookupOptimiser` in the namespace service uses a Guava cache on the metadata lookup hot path, pending DX-51884 migration.
- Files: `services/namespace/src/main/java/com/dremio/service/namespace/BatchLookupOptimiser.java`
- Cause: DX-51884 not yet addressed.
- Improvement path: Migrate to Caffeine cache for better metrics and async loading.

---

## Fragile Areas

**CatalogImpl (3552 lines, many open TODOs):**
- Files: `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java`
- Why fragile: Largest production class in the core; handles versioned and non-versioned catalog operations; multiple open DX tickets (DX-65443, DX-44984, DX-91837, DX-100362, DX-99025, DX-98540, DX-98542, DX-96277, DX-103164); no null check on `handle.get()` (line 2270).
- Safe modification: Add new methods in isolation; run full catalog integration tests after any change; avoid modifying existing method signatures without auditing all callers.
- Test coverage: Partially covered by `dac/backend/src/test/java/com/dremio/dac/api/TestCatalogResource.java` and dataplane integration tests; core catalog logic lacks fine-grained unit tests.

**LocalJobsService (3831 lines):**
- Files: `services/jobs/src/main/java/com/dremio/service/jobs/LocalJobsService.java`
- Why fragile: Largest file in the codebase; handles all local job lifecycle management; open DX-17909 (authorization username missing), DX-10977, DX-2139 (result batches not persisted).
- Safe modification: Changes to job state transitions require end-to-end job lifecycle tests. Authorization parameter is a no-op in places (look for `// TODO (DX-17909)`).
- Test coverage: `dac/backend/src/test/java/com/dremio/service/jobs/TestJobService.java` (2850 lines) provides broad coverage but is itself large and complex.

**VectorizedHashAggOperator (3503 lines):**
- Files: `sabot/kernel/src/main/java/com/dremio/sabot/op/aggregate/vectorized/VectorizedHashAggOperator.java`
- Why fragile: Core of vectorized aggregation; complex state machine for spill-to-disk; open TODO on whether a verification check is essential (line 1234); sibling-fragment spill disabled (line 1256).
- Safe modification: Any change to spill behavior requires extensive testing with memory-constrained workloads; avoid touching state transition logic without full operator-level tests.
- Test coverage: `sabot/kernel/src/test/java/com/dremio/sabot/aggregate/` directory; spill path is under-tested.

**IcebergCommitOpHelper - Read Signature Refactor Pending (DX-58354):**
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/manifestwriter/IcebergCommitOpHelper.java`
- Why fragile: Read signature management logic needs refactoring; wait time tracking not implemented; code tightly coupled to filesystem operations.
- Safe modification: Changes to commit logic must be tested against all DML operations (INSERT, DELETE, UPDATE, MERGE) on Iceberg tables.

**Iceberg Equality Delete Filter - Nested Field Handling Unknown:**
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/deletes/EqualityDeleteFilter.java` (line 144), `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/deletes/ParquetRowLevelDeleteFileReaderFactory.java` (line 147)
- Why fragile: Correctness with nested fields is explicitly uncertain in both delete filter classes.
- Safe modification: Do not use equality delete filters on nested struct columns until the TODO is resolved; add explicit tests for nested field delete scenarios.

**NamespaceServiceImpl Side-Effect in addOrUpdateDataset:**
- Files: `services/namespace/src/main/java/com/dremio/service/namespace/NamespaceServiceImpl.java` (lines 703-722)
- Why fragile: `addOrUpdateDataset` has a documented side effect where it may silently delete `existingContainer` as an unintended consequence; return value of `false` signals this happened but many callers do not check it (DX-4490).
- Safe modification: Always check the boolean return value; add logging around unexpected deletions.

**Iceberg View Metadata Stats Unimplemented:**
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/IcebergViewMetadataImplV0.java` (line 124), `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/IcebergViewMetadataImplV1.java` (lines 195, 206)
- Why fragile: View statistics methods return stubs (`TODO(DX-99176)`, `TODO(DX-99177)`); planner may make suboptimal decisions for queries over Iceberg views.
- Safe modification: Any code relying on view statistics should treat them as unreliable.

**Iceberg Partition Spec Update Not Supported:**
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/manifestwriter/ManifestWritesHelper.java` (lines 448-449), `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/manifestwriter/SchemaDiscoveryManifestWritesHelper.java` (lines 198-199)
- Why fragile: Partition spec ID is hardcoded to 0 for all writes; tables that have evolved partition specs will use the wrong spec ID in new manifest entries.
- Safe modification: Do not rely on partition spec evolution for Iceberg tables managed through Dremio's manifest writer path until this is resolved.

---

## Scaling Limits

**Token Invalidation in Multi-Coordinator Deployments:**
- Current capacity: Tokens are cached per coordinator; revocation is local only.
- Limit: In a multi-coordinator cluster, a revoked token remains valid on other coordinators until cache TTL expires.
- Scaling path: Implement a broadcast mechanism for token invalidation events across coordinators.

**JDBC TINYINT and SMALLINT Not Implemented:**
- Current capacity: TINYINT and SMALLINT SQL types are not supported in the JDBC driver (multiple `@Ignore("TODO(DRILL-2470)")` tests).
- Limit: Applications expecting these numeric types via JDBC will encounter unsupported type errors.
- Scaling path: Implement TINYINT/SMALLINT support in `ResultSetGetMethodConversionsTest` area; tracked under DRILL-2470.

---

## Dependencies at Risk

**Guava Cache (Deprecated Usage Pattern):**
- Risk: Guava cache is still used extensively despite an active project-level ban (`NoGuavaCacheUsage` lint rule); 42+ `@SuppressWarnings` suppressions indicate intentional but unresolved debt.
- Impact: If Guava removes or breaks the cache API, 42+ production locations break simultaneously.
- Migration plan: Caffeine migration tracked under DX-51884; a `CaffeineTransientStore` replacement already exists at `services/transientstore/src/main/java/com/dremio/datastore/transientstore/CaffeineTransientStore.java`.

**Hive2/Hive3 Dual Plugin Code:**
- Risk: Two separate Hive plugin implementations (`plugins/hive2` and `plugins/hive3`) with largely duplicated logic (e.g., `HiveTestDataGenerator` appears in both at 3056/3055 lines each).
- Impact: Bug fixes must be applied to both plugins; divergence risk over time.
- Migration plan: Consolidate or move Hive2-specific differences behind an abstraction layer.

**Bouncycastle for JWKS Certificate Generation:**
- Risk: Bouncycastle is used for X.509 certificate generation in `SystemJWKSetManager`. If the Bouncycastle dependency is removed or upgraded with breaking API changes, JWKS initialization fails.
- Impact: Token-based authentication fails at startup.
- Migration plan: No migration plan documented; test coverage for JWKS initialization should be maintained.

---

## Missing Critical Features

**Iceberg Time Travel Reflections:**
- Problem: `ScanCrel.isSubstitutable` is explicitly set to `false` with comment `// TODO: Support reflections on Iceberg time travel`.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/calcite/logical/ScanCrel.java` (line 91)
- Blocks: Accelerations/reflections cannot be used to speed up Iceberg time travel queries; every time travel query hits raw data.

**Iceberg Sorted Table Reflections (DX-55508):**
- Problem: Sorted Iceberg tables are excluded from reflection eligibility with a hardcoded check in `ReflectionManager`.
- Files: `services/accelerator/src/main/java/com/dremio/service/reflection/ReflectionManager.java` (line 1582)
- Blocks: Users cannot create reflections on sorted Iceberg tables until DX-55508 is complete.

**Filesystem Cleanup on Table Drop (Dataplane):**
- Problem: Dropping a versioned (Nessie/Dataplane) table does not clean up the underlying filesystem objects. Tests explicitly acknowledge this with `// TODO For now, we aren't doing filesystem cleanup`.
- Files: `plugins/dataplane-tests/src/test/java/com/dremio/exec/catalog/dataplane/ITDataplanePluginDrop.java` (lines 80, 100, 123), `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/nessie/IcebergNessieVersionedCommand.java`
- Blocks: Storage costs grow unboundedly after table drops; no garbage collection mechanism.

**GCS TTL Fetching Not Implemented:**
- Problem: `GoogleBucketFileSystem.fetchTTL()` is a stub returning nothing.
- Files: `plugins/gcs/src/main/java/com/dremio/plugins/gcs/GoogleBucketFileSystem.java` (line 155)
- Blocks: GCS metadata refresh TTL cannot be determined from the bucket; metadata refresh may use suboptimal intervals.

---

## Test Coverage Gaps

**TINYINT and SMALLINT JDBC Types:**
- What's not tested: JDBC `TINYINT` and `SMALLINT` type handling via `ResultSet.get*` methods are all ignored.
- Files: `client/jdbc/src/test/java/com/dremio/jdbc/ResultSetGetMethodConversionsTest.java` (~20 `@Ignore` annotations for these types)
- Risk: Any implementation of these types could have silent bugs that go undetected.
- Priority: Medium

**Iceberg Nested Field Equality Deletes:**
- What's not tested: `EqualityDeleteFilter` and `ParquetRowLevelDeleteFileReaderFactory` behavior with nested struct columns is explicitly unknown.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/deletes/EqualityDeleteFilter.java`, `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/deletes/ParquetRowLevelDeleteFileReaderFactory.java`
- Risk: Equality deletes on nested fields may silently produce wrong results or miss rows.
- Priority: High

**Gandiva Large CASE Expressions (DX-34386 / DX-29559):**
- What's not tested: A kludge in `CodeGenerationContextAnnotator` hard-limits case expression size before routing to Gandiva. The limit is described as temporary but has no test for the boundary condition or post-fix correctness.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/expr/CodeGenerationContextAnnotator.java`
- Risk: Queries near the case expression size threshold may be incorrectly routed between Gandiva and Java code generators.
- Priority: Medium

**Iceberg View Statistics:**
- What's not tested: `getStatistics()` and related stats methods in `IcebergViewMetadataImplV0` and `IcebergViewMetadataImplV1` return stubs; there are no tests verifying planner behavior with missing view stats.
- Files: `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/IcebergViewMetadataImplV0.java`, `sabot/kernel/src/main/java/com/dremio/exec/store/iceberg/IcebergViewMetadataImplV1.java`
- Risk: Planner may generate suboptimal plans for queries over Iceberg views.
- Priority: Medium

**DX-15645 Sleep Workarounds in Parquet/JSON/Text Tests:**
- What's not tested: `TestParquetScan`, `TestJsonRecordReader`, and `TestNewTextReader` all use `Thread.sleep()` calls to work around an unresolved timing issue (DX-15645). The actual race condition is untested.
- Files: `sabot/kernel/src/test/java/com/dremio/exec/store/parquet/TestParquetScan.java`, `sabot/kernel/src/test/java/com/dremio/exec/store/json/TestJsonRecordReader.java`, `sabot/kernel/src/test/java/com/dremio/exec/store/text/TestNewTextReader.java`
- Risk: Tests pass intermittently; underlying timing issue could manifest in production under load.
- Priority: High

**HadoopFileSystemWrapperFSError Switch-Case Hack (DX-7629):**
- What's not tested: The switch-case in `TestHadoopFileSystemWrapperFSError` is acknowledged as a temporary hack; the correct underlying behavior is not tested.
- Files: `sabot/kernel/src/test/java/com/dremio/exec/hadoop/TestHadoopFileSystemWrapperFSError.java`
- Risk: Filesystem error handling may not behave correctly in all cases.
- Priority: Low

---

*Concerns audit: 2026-02-17*
