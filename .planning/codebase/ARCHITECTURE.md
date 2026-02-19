# Architecture

**Analysis Date:** 2026-02-17

## Pattern Overview

**Overall:** Distributed Query Engine with Coordinator/Executor Node Split

Dremio is a multi-module Java platform (Maven multi-module) structured as a distributed data lakehouse query engine. The architecture follows a **coordinator/executor separation** pattern where coordinator nodes handle query planning, catalog management, and client APIs, while executor nodes run the physical query fragments. Nodes can run in combined (LOCAL) or distributed (DISTRIBUTED) mode.

**Key Characteristics:**
- Coordinator nodes: query parsing, planning (Calcite-based), fragment distribution, result aggregation
- Executor nodes: fragment execution using a columnar, vectorized pipeline engine (Sabot)
- Plugin-based storage connectors: all data sources implement the StoragePlugin/FileSystemPlugin interface
- Catalog-centric metadata: all dataset metadata lives in the Namespace service backed by a KV store
- Protobuf + gRPC for all inter-node communication; Arrow Flight for client data streaming
- Dependency injection via HK2 (Jersey) + custom `SingletonRegistry`/`BindingCreator`

## Layers

**API / Presentation Layer:**
- Purpose: HTTP REST endpoints, Arrow Flight endpoints, JDBC driver
- Location: `dac/backend/src/main/java/com/dremio/dac/resource/`, `services/arrow-flight/`, `client/jdbc/`
- Contains: JAX-RS (Jersey) `@Path` resource classes, Flight producer, JDBC Driver
- Depends on: Service layer (DAC services, jobs service)
- Used by: UI, external BI tools, JDBC clients

**DAC Service Layer:**
- Purpose: Business logic for the Data Access Cloud (UI backend)
- Location: `dac/backend/src/main/java/com/dremio/dac/service/`
- Contains: Dataset versioning, catalog wrappers, reflection helpers, source management
- Depends on: Exec catalog, Jobs service, Namespace service
- Used by: REST resources in `dac/backend/src/main/java/com/dremio/dac/resource/`

**Query Execution Coordination Layer (Foreman):**
- Purpose: Receives user queries, coordinates full lifecycle from SQL to results
- Location: `sabot/kernel/src/main/java/com/dremio/exec/work/protector/`, `sabot/kernel/src/main/java/com/dremio/exec/work/foreman/`
- Contains: `Foreman.java`, `ForemenWorkManager.java`, `AttemptManager.java`, retry/re-attempt handlers
- Depends on: Planner, MaestroService, Catalog, QueryContext
- Used by: User RPC handlers and Arrow Flight producer

**Query Planning Layer:**
- Purpose: SQL parsing, logical planning, physical planning, optimization via Calcite
- Location: `sabot/kernel/src/main/java/com/dremio/exec/planner/`
- Contains: `sql/` (parsing, handlers), `logical/` (logical rel nodes), `physical/` (physical rel nodes), `fragment/` (plan splitting), `acceleration/` (reflection/materialization rules), `cost/`, `rules/`, `normalizer/`
- Depends on: Catalog, ExpressionRegistry, ReflectionService
- Used by: AttemptManager (via CommandCreator/CommandRunner)

**Catalog Layer:**
- Purpose: Unified view of all datasets (physical, virtual, versioned), resolving table paths to metadata
- Location: `sabot/kernel/src/main/java/com/dremio/exec/catalog/`
- Contains: `Catalog.java` (interface), `CatalogImpl.java`, `CatalogServiceImpl.java`, plugin registry
- Depends on: Namespace service, StoragePlugin implementations, Nessie for versioned tables
- Used by: Planner, Foreman, DAC service layer

**Namespace Service:**
- Purpose: Persistent hierarchical metadata store for all catalog entities (sources, spaces, datasets, splits)
- Location: `services/namespace/src/main/java/com/dremio/service/namespace/`
- Contains: `NamespaceService.java` (interface), dataset/source/folder/space sub-packages, partition split metadata
- Depends on: KV store (datastore)
- Used by: Catalog layer, accelerator, job service

**KV Store (Datastore):**
- Purpose: Pluggable key-value persistence abstraction (backed by RocksDB in production)
- Location: `services/datastore/src/main/java/com/dremio/datastore/`
- Contains: `KVStore.java`, `KVStoreProvider.java`, `IndexedStore.java` (Lucene-backed), `CoreKVStoreImpl.java`
- Depends on: Nothing above this layer
- Used by: Namespace service, Jobs service, Tokens service, Options service, and most other services

**Fragment Execution Layer (Sabot):**
- Purpose: Executes physical plan fragments on executor nodes using a vectorized pipeline model
- Location: `sabot/kernel/src/main/java/com/dremio/sabot/exec/`, `sabot/kernel/src/main/java/com/dremio/sabot/op/`
- Contains: `FragmentExecutor.java`, `FragmentWorkManager.java`, operator implementations (`op/scan/`, `op/join/`, `op/aggregate/`, `op/sort/`, etc.)
- Depends on: Physical plan objects, storage plugin scan implementations, Arrow vector buffers
- Used by: Invoked by MaestroService after Foreman distributes fragments

**Storage Plugin Layer:**
- Purpose: Abstraction for connecting to data sources; each source type is a plugin
- Location: `sabot/kernel/src/main/java/com/dremio/exec/store/dfs/` (base DFS), `plugins/` (concrete implementations)
- Contains: `FileSystemPlugin`, `StoragePlugin` base classes; concrete: `plugins/s3/`, `plugins/hdfs/`, `plugins/hive3/`, `plugins/elasticsearch/`, `plugins/dataplane/` (Nessie/Iceberg)
- Depends on: Connector SPI (`connector/src/main/java/com/dremio/connector/`)
- Used by: Catalog layer for metadata and by fragment execution for reading data

**Cluster Coordination Layer:**
- Purpose: Node discovery, cluster membership, distributed elections via ZooKeeper
- Location: `services/coordinator/src/main/java/com/dremio/service/coordinator/`
- Contains: `ClusterCoordinator.java` (abstract), ZK-backed implementation, node Role enum (COORDINATOR, EXECUTOR, MASTER)
- Depends on: ZooKeeper client (`common/zookeeper/`)
- Used by: DACDaemon startup, NodeRegistration, Maestro executor selection

**Maestro Service:**
- Purpose: Fragment distribution - sends physical plan fragments to executor nodes over Fabric RPC
- Location: `sabot/kernel/src/main/java/com/dremio/exec/maestro/`, `services/maestro/`
- Contains: `MaestroService.java`, `MaestroServiceImpl.java`, `FragmentStarter.java`, `FragmentTracker.java`
- Depends on: Fabric RPC, executor node registry, physical plan
- Used by: AttemptManager (after planning is complete)

**Inter-Node RPC Layer:**
- Purpose: Netty-based binary RPC for coordinator-executor and executor-executor communication
- Location: `services/base-rpc/src/main/java/com/dremio/exec/rpc/`, `services/fabric-rpc/src/main/java/com/dremio/services/fabric/`
- Contains: `BasicServer.java`, `BasicClient.java`, `FabricServiceImpl.java`, protobuf message encoding
- Depends on: Netty, Protobuf definitions in `protocol/src/main/protobuf/`
- Used by: Maestro, Sabot executors (sender/receiver operators)

## Data Flow

**Query Execution Flow (SELECT):**

1. Client sends SQL via JDBC (`client/jdbc/`) or Arrow Flight (`services/arrow-flight/`) or REST (`dac/backend/`)
2. `Foreman.java` or `AttemptManager.java` receives the query with user context
3. `CommandCreator` dispatches to the appropriate handler (e.g., `NormalHandler` for DML/DQL)
4. SQL parsed by Calcite grammar (extended in `sabot/grammar/`) into `SqlNode`
5. `DremioSqlToRelConverter` converts `SqlNode` → logical `RelNode` tree
6. Logical plan optimized (Calcite RBO/CBO rules in `exec/planner/logical/` and `exec/planner/physical/`)
7. Reflection/materialization substitution checked via `AccelerationRewritePlanner`
8. Physical plan produced as `PhysicalPlan` (list of `PhysicalOperator` nodes)
9. `FragmentParallelizer` splits plan into `Fragment`s and assigns parallelism
10. `MaestroServiceImpl` distributes fragments to executor nodes via Fabric RPC
11. Each executor instantiates a `FragmentExecutor` that builds a `Pipeline` of `Operator`s
12. Data flows through pipeline as columnar Arrow batches (`VectorAccessible`)
13. Results stream back to coordinator via sender/receiver exchange operators
14. Coordinator's screen operator delivers results to client

**Metadata Refresh Flow:**
1. Catalog detects source or metadata staleness
2. `StoragePlugin.listDatasetNames()` enumerates tables
3. Dataset metadata (schema, splits) fetched and stored in `NamespaceService`
4. Partition splits stored in `KVStore` for executor use during scan planning

**State Management:**
- Query state tracked in `AttemptManager` via `QueryState` enum (STARTING, RUNNING, COMPLETED, FAILED, CANCELED)
- Cluster state in ZooKeeper via `ClusterCoordinator`
- Persistent metadata in RocksDB-backed KV store via `KVStoreProvider`
- Job history persisted in `JobsService` backed by KV store

## Key Abstractions

**StoragePlugin:**
- Purpose: Represents a connected data source (S3, Hive, Nessie, HDFS, etc.)
- Examples: `plugins/s3/src/main/java/com/dremio/plugins/s3/store/S3StoragePlugin.java`, `plugins/dataplane/src/main/java/com/dremio/plugins/dataplane/store/NessiePlugin.java`, `plugins/hive3/`
- Pattern: Implements `StoragePlugin` interface; registered via classpath scanning and SabotConfig

**Operator (SPI):**
- Purpose: Single unit of execution in a physical pipeline; consumes/produces Arrow record batches
- Examples: `sabot/kernel/src/main/java/com/dremio/sabot/op/spi/SingleInputOperator.java`, `DualInputOperator.java`, `ProducerOperator.java`
- Pattern: State machine (NEEDS_SETUP → CAN_CONSUME/CAN_PRODUCE → DONE); operators wired into `Pipeline` by `PipelineCreator`

**Catalog (interface):**
- Purpose: Per-query, per-user view of all datasets; the entry point for all metadata resolution
- Examples: `sabot/kernel/src/main/java/com/dremio/exec/catalog/Catalog.java`, `CatalogImpl.java`, `CachingCatalog.java`
- Pattern: Composite interface (EntityExplorer + DatasetCatalog + SourceCatalog + InformationSchemaCatalog + VersionContextResolver + FolderCatalog); new instance per query via `CatalogFactory`

**PhysicalOperator (plan nodes):**
- Purpose: Serializable representation of an operator in the physical plan (before execution)
- Examples: `sabot/kernel/src/main/java/com/dremio/exec/physical/base/GroupScan.java`, `AbstractSingle.java`, `Exchange.java`
- Pattern: Visitor pattern via `AbstractPhysicalVisitor`; serialized as Protobuf for distribution

**KVStore:**
- Purpose: Typed key-value persistence; `IndexedStore` adds Lucene-based secondary indexing
- Examples: `services/datastore/src/main/java/com/dremio/datastore/api/KVStore.java`, `IndexedStore.java`
- Pattern: Created via `KVStoreCreationFunction` implementations scanned at startup; accessed through `KVStoreProvider`

**Fragment:**
- Purpose: A parallelizable slice of the physical plan sent to one executor node
- Examples: `sabot/kernel/src/main/java/com/dremio/exec/planner/fragment/Fragment.java`
- Pattern: Built by `MakeFragmentsVisitor` walking the physical plan tree; each fragment has a root operator and exchange boundaries

## Entry Points

**DremioDaemon (main entry point):**
- Location: `dac/daemon/src/main/java/com/dremio/dac/daemon/DremioDaemon.java`
- Triggers: `java -jar dremio-daemon.jar` / startup scripts
- Responsibilities: Parses configuration, runs auto-upgrade, instantiates `DACDaemon`

**DACDaemon:**
- Location: `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemon.java`
- Triggers: Called by `DremioDaemon.main()`
- Responsibilities: Bootstraps the `SingletonRegistry`, starts all services (ZK, SabotNode, WebServer, Arrow Flight), assigns node roles (MASTER, COORDINATOR, EXECUTOR)

**DACDaemonModule / DACModule:**
- Location: `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java`, `DACModule.java`
- Triggers: Called during `DACDaemon` startup
- Responsibilities: Registers all service bindings in the `SingletonRegistry`

**WebServer / RestServerV2:**
- Location: `dac/backend/src/main/java/com/dremio/dac/server/WebServer.java`, `RestServerV2.java`
- Triggers: Started by DACDaemon
- Responsibilities: Hosts Jersey JAX-RS REST API (`/api/v2/` and `/apiv3/`); registers all `@Path` resources

**DremioFlightService:**
- Location: `services/arrow-flight/src/main/java/com/dremio/service/flight/DremioFlightService.java`
- Triggers: Started by DACDaemon
- Responsibilities: Exposes Arrow Flight endpoint for high-performance data retrieval

**Foreman:**
- Location: `sabot/kernel/src/main/java/com/dremio/exec/work/protector/Foreman.java`
- Triggers: Invoked by user RPC handler or Flight producer when a query arrives
- Responsibilities: Entry point for query execution; creates `AttemptManager`, handles retries, delivers results to response sender

## Error Handling

**Strategy:** Layered; `UserException` is the top-level user-facing exception type; execution failures produce `DremioPBError` protobuf messages that are propagated back to the coordinator.

**Patterns:**
- `UserException` (`common/core/src/main/java/com/dremio/common/exceptions/`) wraps all user-visible failures with error context
- `AttemptManager` catches failures and triggers re-attempt logic (via `ReAttemptHandler`) when safe to do so
- Fragment failures reported to coordinator via `FragmentStatusReporter` → Maestro → AttemptManager
- Execution-level OOM triggers heap clawback (`HeapMonitorManager`, `HeapClawBackStrategy`) before failing the query

## Cross-Cutting Concerns

**Logging:** SLF4J with Logback; JUL bridged via `JULBridge` at startup (`DremioDaemon`). Structured logging context added via `RequestContext`.

**Validation:** Bean Validation (Hibernate Validator) for REST request bodies; `Preconditions` (Guava) for internal contracts.

**Authentication:** Token-based (`services/tokens/`) for REST and Flight; JDBC uses password auth via `UserService`. `DACAuthFilter` (Jersey filter) validates tokens on every HTTP request. `DACSecurityContext` propagates identity into JAX-RS context.

**Options System:** Runtime-tunable system options backed by KV store (`services/options/`); accessed via `OptionManager` injected into `SabotContext` and `QueryContext`. Options defined as `TypeValidators` with defaults in `ExecConstants`.

**Code Generation:** `exec/compile/` supports runtime Java code generation and compilation (via `javac` API) for expression evaluation hot paths; LLVM via Gandiva (`sabot/op/llvm/`) for vectorized expression acceleration.

---

*Architecture analysis: 2026-02-17*
