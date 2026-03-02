# Codebase Structure

**Analysis Date:** 2026-02-17

## Directory Layout

```
dremio-oss/
├── build-tools/          # Maven build plugins, error-prone config, code generators
│   ├── configs/          # Shared build config (checkstyle, spotbugs, etc.)
│   ├── errorprone/       # Custom Error Prone checks
│   ├── fmpp-maven-plugin/   # FreeMarker preprocessor plugin (SQL grammar templates)
│   ├── openapi-generator/   # OpenAPI code gen plugin
│   └── protostuff-maven-plugin/  # Protostuff serialization plugin
├── client/               # Client-side modules
│   ├── base/             # Base client library (RPC client, connection management)
│   └── jdbc/             # JDBC driver (com.dremio.jdbc.Driver)
├── common/               # Shared utilities and base classes
│   ├── aws/              # AWS credential helpers
│   ├── core/             # Minimal core: exceptions, concurrent utilities
│   ├── legacy/           # Broad common utilities: config, memory, scanner, types
│   └── zookeeper/        # ZooKeeper client abstraction
├── connector/            # Storage connector SPI (interface only, no impl)
├── contrib/              # Vendored / shaded third-party libs
│   ├── hive2-exec-shade/ # Shaded Hive 2 exec jar
│   ├── hive3-exec-shade/ # Shaded Hive 3 exec jar
│   └── nessie-legacy-storage/  # Nessie legacy storage compatibility
├── dac/                  # Data Access Cloud - UI backend + web server
│   ├── backend/          # REST API, services, server setup
│   ├── common/           # Shared DAC utilities
│   ├── daemon/           # Main entry point (DremioDaemon.java)
│   ├── translations/     # i18n string bundles
│   ├── ui/               # React/TypeScript web frontend
│   ├── ui-common/        # Shared UI component library
│   ├── ui-lib/           # Design system components
│   └── ui-tools/         # UI build tools and utilities
├── distribution/         # Assembly and packaging
│   ├── docker/           # Dockerfile and container configs
│   ├── jdbc-driver/      # JDBC driver distribution assembly
│   ├── resources/        # Distribution resources (scripts, configs)
│   └── server/           # Server distribution assembly
├── plugins/              # Storage plugin implementations
│   ├── awsauth/          # AWS authentication helpers
│   ├── awsglue/          # AWS Glue catalog plugin
│   ├── azure/            # Azure Data Lake Storage plugin
│   ├── common/           # Shared plugin utilities
│   ├── dataplane/        # Nessie/Iceberg versioned catalog plugin
│   ├── dataplane-tests/  # Integration tests for dataplane plugin
│   ├── elasticsearch/    # Elasticsearch plugin
│   ├── gcs/              # Google Cloud Storage plugin
│   ├── hdfs/             # HDFS plugin
│   ├── hive/             # Hive plugin base
│   ├── hive2/            # Hive 2 specific plugin
│   ├── hive3/            # Hive 3 specific plugin
│   ├── hive-common/      # Shared Hive plugin code
│   ├── hive-function-registry/  # Hive UDF bridge
│   ├── icebergcatalog/   # Standalone Iceberg catalog plugin
│   ├── mongo/            # MongoDB plugin
│   ├── nas/              # Network-attached storage / local filesystem plugin
│   ├── pdfs/             # Parallel distributed filesystem plugin
│   ├── s3/               # Amazon S3 plugin
│   └── sysflight/        # System tables via Arrow Flight
├── protocol/             # Protobuf message definitions
│   └── src/main/protobuf/   # .proto files (CoordExecRPC, UserBitShared, Coordination, etc.)
├── provision/            # Cluster provisioning
│   ├── common/           # Provisioning API
│   └── yarn/             # YARN-based executor provisioning
├── sabot/                # Core query engine (SQL execution kernel)
│   ├── grammar/          # SQL grammar extensions (FreeMarker .ftl files for Calcite parser)
│   ├── kernel/           # Main execution engine module
│   ├── logical/          # Logical plan model
│   ├── serializer/       # Plan serialization utilities
│   └── vector-tools/     # Arrow vector utilities, type coercion rules
├── sample-data/          # Sample datasets (nations, regions in various formats)
├── services/             # All named microservices / service modules
│   ├── accelerator/      # Reflection (materialized view) service
│   ├── accelerator-api/  # Reflection service API
│   ├── arrow-flight/     # Arrow Flight server
│   ├── arrow-flight-common/  # Shared Flight utilities
│   ├── authenticator/    # Authentication SPI
│   ├── base-rpc/         # Netty-based binary RPC framework
│   ├── catalog/          # Catalog maintenance service
│   ├── catalog-api/      # Catalog service API
│   ├── command-pool/     # Command pool (async query coordination)
│   ├── configuration/    # System configuration service
│   ├── coordinator/      # Cluster coordinator (ZK-based)
│   ├── credentials/      # Secrets/credentials management
│   ├── datastore/        # KV store abstraction (RocksDB backend)
│   ├── distributed-plan-cache/  # Distributed query plan cache
│   ├── embedded-catalog/ # Embedded catalog for single-node mode
│   ├── exec-selector/    # Executor selection strategy
│   ├── executorservice/  # Executor gRPC service
│   ├── fabric-rpc/       # Fabric (internal cluster messaging) RPC
│   ├── functions/        # SQL function registry service
│   ├── grpc/             # gRPC utilities and conduit (in-process gRPC)
│   ├── hadoopcredentials/  # Hadoop credential integration
│   ├── jobcounts/        # Job count tracking
│   ├── jobresults/       # Job result storage and retrieval
│   ├── jobs/             # Job service (query history, status)
│   ├── jobtelemetry/     # Job metrics and telemetry
│   ├── maestro/          # Fragment distribution orchestrator
│   ├── namespace/        # Namespace (catalog metadata) service
│   ├── nessie-grpc/      # Nessie gRPC bindings
│   ├── nessie-metadata-cache/  # Nessie metadata caching
│   ├── nessie-proxy/     # Nessie API proxy
│   ├── nessie-restjavax/ # Nessie REST javax bindings
│   ├── nessie-storage-upgrade/  # Nessie storage migration
│   ├── nessie-validation/  # Nessie validation utilities
│   ├── node-metrics/     # Node-level metrics collection
│   ├── optimization-api/ # Query optimization API
│   ├── options/          # System options (runtime config)
│   ├── orphanage/        # Orphaned entity tracking
│   ├── orphanagecleaner/ # Orphan cleanup background service
│   ├── partition-stats/  # Partition statistics service
│   ├── pubsub-api/       # Pub/Sub messaging API
│   ├── pubsub-nats/      # NATS-based pub/sub implementation
│   ├── reindexer/        # KV store reindexing service
│   ├── resourcescheduler/  # Resource scheduling (workload management)
│   ├── scheduler/        # Background task scheduler
│   ├── scripts/          # Utility scripts
│   ├── spill/            # Spill-to-disk management
│   ├── sqlrunner/        # SQL runner service
│   ├── statistics/       # Column statistics service
│   ├── sysflight/        # System tables via Flight
│   ├── systemicebergtablesmaintainer/  # System Iceberg table maintenance
│   ├── telemetry-api/    # Telemetry API
│   ├── telemetry-impl/   # Telemetry implementation (Micrometer)
│   ├── telemetry-utils/  # Telemetry utilities
│   ├── tokens/           # Auth token management
│   ├── transientstore/   # In-memory transient KV store
│   ├── userpreferences/  # User preferences service
│   ├── users/            # User management service
│   └── usersessions/     # User session management
├── src/                  # Top-level source (minimal)
├── tools/                # Developer tools
└── ui/                   # Shared UI workspace (monorepo root for npm packages)
    ├── browserslist-config-dremio/
    ├── design-system/
    ├── dremio-js/        # Dremio JavaScript SDK
    ├── eslint-config-dremio/
    ├── icons/
    └── translations/
```

## Directory Purposes

**`sabot/kernel/`:**
- Purpose: The core query execution engine
- Contains: SQL planner (`exec/planner/`), physical operators (`sabot/op/`), catalog (`exec/catalog/`), storage abstractions (`exec/store/`), foreman/coordinator logic (`exec/work/`), expression evaluation (`exec/expr/`), code generation (`exec/compile/`)
- Key files: `sabot/kernel/src/main/java/com/dremio/exec/server/SabotContext.java`, `sabot/kernel/src/main/java/com/dremio/exec/work/protector/Foreman.java`, `sabot/kernel/src/main/java/com/dremio/exec/work/foreman/AttemptManager.java`

**`dac/backend/`:**
- Purpose: REST API server, web server configuration, DAC-layer services
- Contains: JAX-RS resources (`resource/`), Jersey server setup (`server/`), DAC services (`service/`), daemon startup (`daemon/`), explore/BI tools (`explore/`)
- Key files: `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemon.java`, `dac/backend/src/main/java/com/dremio/dac/server/WebServer.java`, `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java`

**`dac/daemon/`:**
- Purpose: Standalone daemon main class and YARN daemon variant
- Key files: `dac/daemon/src/main/java/com/dremio/dac/daemon/DremioDaemon.java` (main entry point)

**`services/namespace/`:**
- Purpose: All catalog metadata persistence (sources, spaces, datasets, splits)
- Contains: `NamespaceService.java` (interface), source/dataset/folder/space sub-packages, partition chunk metadata
- Key files: `services/namespace/src/main/java/com/dremio/service/namespace/NamespaceService.java`

**`services/datastore/`:**
- Purpose: KV store abstraction layer, used by nearly every service
- Contains: `KVStore.java`, `IndexedStore.java` (Lucene-indexed), `KVStoreProvider.java`, `CoreKVStoreImpl.java`
- Key files: `services/datastore/src/main/java/com/dremio/datastore/api/KVStore.java`, `services/datastore/src/main/java/com/dremio/datastore/api/KVStoreProvider.java`

**`services/coordinator/`:**
- Purpose: Cluster membership and leader election via ZooKeeper
- Contains: `ClusterCoordinator.java` (abstract base with Role enum: MASTER, COORDINATOR, EXECUTOR)
- Key files: `services/coordinator/src/main/java/com/dremio/service/coordinator/ClusterCoordinator.java`

**`plugins/`:**
- Purpose: All data source connector implementations; each subdirectory is an independently deployed plugin
- Contains: Plugin-specific `StoragePlugin` implementations, scan operators, format readers
- Key files: `plugins/dataplane/src/main/java/com/dremio/plugins/dataplane/store/NessiePlugin.java`, `plugins/s3/`, `plugins/hive3/`

**`protocol/`:**
- Purpose: All Protobuf message definitions for inter-node communication
- Contains: `.proto` files for all RPC messages
- Key files: `protocol/src/main/protobuf/CoordExecRPC.proto`, `protocol/src/main/protobuf/UserBitShared.proto`, `protocol/src/main/protobuf/Coordination.proto`

**`sabot/grammar/`:**
- Purpose: SQL grammar extensions to Apache Calcite's parser
- Contains: FreeMarker template files (`.ftl`) that extend Calcite's `parserImpls.ftl`
- Key files: `sabot/grammar/src/main/codegen/includes/parserImpls.ftl`, `versionSupport.ftl`, `alter.ftl`

**`connector/`:**
- Purpose: Storage connector SPI only (interfaces) - no implementations
- Contains: `ConnectorException.java`, metadata and impersonation interfaces
- Key files: `connector/src/main/java/com/dremio/connector/`

**`common/legacy/`:**
- Purpose: Broad shared utilities: config loading, memory management, classpath scanning, logging, Arrow helpers
- Contains: `SabotConfig`, `DremioConfig`, `UserException`, `ClassPathScanner`, memory allocator utilities
- Key files: `common/legacy/src/main/java/com/dremio/common/config/SabotConfig.java`, `common/legacy/src/main/java/com/dremio/config/DremioConfig.java`

**`services/accelerator/`:**
- Purpose: Reflection (materialized view) service — creates and manages accelerated datasets
- Contains: `ReflectionAdministrationService.java`, dependency graph, materialization management
- Key files: `services/accelerator/src/main/java/com/dremio/service/reflection/`

**`dac/ui/`:**
- Purpose: React/TypeScript web frontend
- Contains: `src/components/`, `src/containers/`, `src/actions/`, `src/endpoints/`, React entry point at `src/index.tsx`
- Key files: `dac/ui/src/index.tsx`, `dac/ui/src/dremio.ts`

## Key File Locations

**Entry Points:**
- `dac/daemon/src/main/java/com/dremio/dac/daemon/DremioDaemon.java`: `main()` - process entry point
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemon.java`: daemon lifecycle and service orchestration
- `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java`: service binding registration
- `dac/ui/src/index.tsx`: UI application entry point

**Configuration:**
- `common/legacy/src/main/java/com/dremio/config/DremioConfig.java`: runtime configuration wrapper
- `common/legacy/src/main/java/com/dremio/common/config/SabotConfig.java`: HOCON-based config loader
- `dac/backend/src/main/java/com/dremio/dac/server/DACConfig.java`: DAC-specific config
- `sabot/kernel/src/main/resources/sabot-default.conf`: default HOCON configuration

**Core Logic:**
- `sabot/kernel/src/main/java/com/dremio/exec/server/SabotContext.java`: central dependency container for the engine
- `sabot/kernel/src/main/java/com/dremio/exec/work/protector/Foreman.java`: query execution entry point
- `sabot/kernel/src/main/java/com/dremio/exec/work/foreman/AttemptManager.java`: per-attempt query lifecycle
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/Catalog.java`: catalog interface
- `sabot/kernel/src/main/java/com/dremio/exec/catalog/CatalogImpl.java`: catalog implementation
- `sabot/kernel/src/main/java/com/dremio/exec/maestro/MaestroServiceImpl.java`: fragment distribution

**Storage Plugin SPI:**
- `connector/src/main/java/com/dremio/connector/`: connector SPI interfaces
- `sabot/kernel/src/main/java/com/dremio/exec/store/dfs/FileSystemPlugin.java`: base filesystem plugin

**Operator SPI:**
- `sabot/kernel/src/main/java/com/dremio/sabot/op/spi/SingleInputOperator.java`: unary operator interface
- `sabot/kernel/src/main/java/com/dremio/sabot/op/spi/DualInputOperator.java`: binary operator interface (join, etc.)
- `sabot/kernel/src/main/java/com/dremio/sabot/op/spi/ProducerOperator.java`: source operator interface

**Testing:**
- Test classes co-located with source under `src/test/java/` in each module
- Integration test base classes: `sabot/kernel/src/test/java/com/dremio/exec/` (SabotNode-based tests)
- DAC integration tests: `dac/backend/src/test/java/com/dremio/dac/`
- Plugin integration tests: `plugins/dataplane-tests/`

## Naming Conventions

**Files:**
- Java classes: `PascalCase.java` matching the class name
- Protobuf: `PascalCase.proto`, service-oriented naming (e.g., `CoordExecRPC.proto`, `UserBitShared.proto`)
- FreeMarker templates: `camelCase.ftl` for grammar extensions
- Maven modules: `kebab-case` directory names matching `artifactId`

**Directories:**
- Java packages under `src/main/java/`: `com/dremio/[module]/[subpackage]`
- Services follow pattern: `services/[service-name]/src/main/java/com/dremio/service/[servicename]/`
- Plugins follow pattern: `plugins/[plugin-name]/src/main/java/com/dremio/plugins/[name]/`
- Sabot kernel packages: `com.dremio.exec.*` (planner, catalog, store) and `com.dremio.sabot.*` (executor, operators)

**Interfaces vs Implementations:**
- Core abstractions are always interfaces (e.g., `Catalog`, `KVStore`, `StoragePlugin`, `ClusterCoordinator`)
- Implementations named `[Interface]Impl` (e.g., `CatalogImpl`, `CatalogServiceImpl`, `MaestroServiceImpl`)
- API-only modules named `[service]-api` (e.g., `services/catalog-api`, `services/accelerator-api`)
- Test helpers named `[Name]Test`, `[Name]TestBase`, `Test[Name]`

## Where to Add New Code

**New Storage Plugin:**
- Implementation: `plugins/[plugin-name]/src/main/java/com/dremio/plugins/[name]/`
- Follow: `plugins/s3/` or `plugins/elasticsearch/` as examples
- Register: Plugin config class annotated and picked up by classpath scanning; plugin listed in SabotConfig

**New REST Endpoint:**
- Implementation: `dac/backend/src/main/java/com/dremio/dac/resource/[Name]Resource.java`
- Service layer: `dac/backend/src/main/java/com/dremio/dac/service/[domain]/[Name]Service.java`
- Register resource: Add to `RestServerV2.java` or via Jersey package scanning

**New Physical Operator:**
- Physical plan node: `sabot/kernel/src/main/java/com/dremio/exec/physical/config/[Name]POP.java` (Plain Old Physical)
- Execution impl: `sabot/kernel/src/main/java/com/dremio/sabot/op/[category]/[Name]Operator.java`
- Register: Add to `OperatorCreatorRegistry` via annotation

**New Service:**
- API interface: `services/[name]-api/src/main/java/com/dremio/service/[name]/[Name]Service.java`
- Implementation: `services/[name]/src/main/java/com/dremio/service/[name]/[Name]ServiceImpl.java`
- Register: Add binding in `DACDaemonModule.java` (or `ConfigurationModuleImpl.java`)

**New KV Store:**
- Store creator: Implement `KVStoreCreationFunction<K,V>` in the owning service module
- Registration: Annotate with `@Store`, picked up by classpath scan at startup

**Utilities:**
- Shared helpers: `common/legacy/src/main/java/com/dremio/common/util/` (general utilities)
- Exec-only utilities: `sabot/kernel/src/main/java/com/dremio/exec/util/`
- Arrow/vector utilities: `sabot/vector-tools/src/main/java/com/dremio/`

**New gRPC Service:**
- Protobuf: `protocol/src/main/protobuf/[ServiceName].proto` or relevant service `src/main/protobuf/`
- Service impl: `services/[name]/src/main/java/com/dremio/service/[name]/`
- Wire up: Register via gRPC conduit (`services/grpc/`)

## Special Directories

**`protocol/src/main/protobuf/`:**
- Purpose: All Protobuf `.proto` definitions for inter-node communication
- Generated: Java classes generated into `target/` during build
- Committed: `.proto` source files only, not generated Java

**`sabot/grammar/src/main/codegen/`:**
- Purpose: FreeMarker templates used by FMPP Maven plugin to generate Calcite parser extensions
- Generated: Java parser files generated at build time
- Committed: `.ftl` template files only

**`dac/ui/src/`:**
- Purpose: React/TypeScript frontend source
- Generated: `target/` or `dist/` output not committed
- Committed: All TypeScript/TSX source, SCSS, assets

**`sample-data/`:**
- Purpose: Small data files for development/testing
- Generated: No
- Committed: Yes (binary Parquet files included)

**`contrib/`:**
- Purpose: Vendored and shaded third-party dependencies that required patching or isolation
- Generated: No
- Committed: Yes (as Maven modules with their own `pom.xml`)

---

*Structure analysis: 2026-02-17*
