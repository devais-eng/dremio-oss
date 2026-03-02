# Technology Stack

**Analysis Date:** 2026-02-17

## Languages

**Primary:**
- Java 11 (LTS) - All backend services, query engine, plugins, RPC layer; enforced via `maven.compiler.release=11` in `/pom.xml`
- TypeScript 5.7.2 - Frontend UI (`/dac/ui/`, `/ui/design-system/`, `/ui/dremio-js/`)
- JavaScript (ES modules) - Legacy UI code in `/dac/ui/src/` alongside TypeScript

**Secondary:**
- SQL (extended dialect) - Query language surface; grammar defined in `/sabot/grammar/`
- Protobuf 3 - Inter-node and client-server RPC protocol definitions in `/protocol/src/main/protobuf/`
- FlatBuffers 1.12.0 - High-performance serialization used in Arrow columnar format exchange
- LESS - Frontend CSS preprocessor (`/dac/ui/` uses `less-loader 12.2.0`)

## Runtime

**Environment:**
- JVM: Java 11 (eclipse-temurin:11-jdk as Docker base image; see `/distribution/docker/Dockerfile`)
- JVM heap: 512M–3G for tests; 5120M direct memory; production sized separately
- Node.js: No pinned version in `.nvmrc`; implied by `pnpm 8.7.0` and Node 22 types (`@types/node 22.5.1`)

**Package Manager:**
- Java: Apache Maven 3.9.9 (via Maven Wrapper at `./mvnw`)
- Frontend: pnpm 8.7.0 (configured in `/pom.xml` property `pnpm.version`)
- Lockfile: pnpm lockfile present (frozen lockfile install enforced in CI)

## Frameworks

**Backend Core:**
- Jersey 2.46 (JAX-RS 2.1) - REST API server; embedded via `jersey-container-jetty-servlet`; used in `/dac/backend/`
- Jetty 9.4.58.v20250814 - Embedded HTTP server + WebSocket; exposes port 9047
- Google Guice 6.0.0 - Dependency injection throughout all services and plugins
- PF4J 3.10.0 - Plugin framework for storage plugins in `/plugins/`
- Immutables 2.10.1 - Code generation for value objects (annotated with `@Value.Immutable`)

**Query Engine:**
- Apache Calcite 1.22.0 (Dremio fork) - SQL parsing, logical and physical planning
- Apache Arrow 18.1.1 (Dremio fork) - In-memory columnar data format; Gandiva used for native expression evaluation
- Apache Parquet 1.15.2 - Columnar file format read/write
- Apache Iceberg 1.7.0 (Dremio fork) - Table format for data lakehouse
- Apache Avro 1.11.4 - Schema serialization for Hive metadata

**Networking & Messaging:**
- gRPC 1.70.0 (Netty transport) - Internal node-to-node RPC for all cluster services
- Netty 4.1.126.Final - Async network layer under gRPC and Fabric RPC
- Apache ZooKeeper 3.8.4 + Apache Curator 5.7.1 - Distributed coordination for cluster membership
- NATS (`jnats 2.20.6`) - Pub/sub messaging; implemented in `/services/pubsub-nats/`

**Serialization:**
- Jackson 2.15.3 (JSON) - REST API serialization; all services
- Protocol Buffers 3.25.5 - gRPC service contracts and Fabric RPC messages
- Protostuff 1.4.4 - Alternative protobuf serialization for datastore
- FlatBuffers 1.12.0 - Arrow IPC messages

**Frontend:**
- React 18.3.1 - UI component framework (`/dac/ui/src/`)
- Redux 4.x + redux-saga 0.15 + redux-form 5.x - Legacy state management
- TanStack Query 5.66.3 - Modern data fetching alongside Redux
- React Router 3.2.6 (primary) + 6.x (partial migration as `react-router6`) - Routing
- MUI 5.9.2 + Mantine 5.x + Emotion 11.x - Component and styling libraries
- Webpack 5.96.1 - Frontend bundler
- Monaco Editor 0.49.0 - SQL/code editor in the UI
- ECharts 5.3.x + C3 + D3 - Data visualization
- OpenTelemetry JS 1.20.x - Frontend tracing

**Testing (Java):**
- JUnit 4.13.2 + JUnit 5.10.3 (both in use)
- Mockito 5.15.2
- AssertJ 3.27.3
- TestContainers 1.20.4 - Integration tests with real Docker containers
- Flapdoodle embedded MongoDB - In-process MongoDB for plugin tests
- Maven Surefire (unit) + Maven Failsafe (integration tests)

**Testing (Frontend):**
- Mocha 9.x - Test runner
- Chai 4.x - Assertions
- Enzyme 3.x + Testing Library 13.x - React component testing
- Playwright 1.47.0 - End-to-end tests
- MSW (Mock Service Worker) 1.3.2 - API mocking

## Key Dependencies

**Critical:**
- `org.apache.arrow:*` 18.1.1 (Dremio fork) - Entire columnar in-memory processing pipeline
- `org.apache.calcite:calcite-core` 1.22.0 (Dremio fork) - SQL query planner; modifications are extensive
- `org.apache.iceberg:*` 1.7.0 (Dremio fork) - Lakehouse table format
- `org.apache.hadoop:hadoop-common` 3.3.6 (Dremio fork) - HDFS, filesystem abstraction
- `org.projectnessie.nessie:*` 0.100.3 - Git-like catalog for Iceberg; embedded via `services/nessie-*`
- `io.grpc:grpc-bom` 1.70.0 - All inter-service communication
- `org.rocksdb:rocksdbjni` 7.10.2 - Primary embedded key-value store for metadata (`services/datastore/`)

**Infrastructure:**
- `org.apache.zookeeper:*` 3.8.4 - Cluster coordination
- `org.apache.hadoop:hadoop-aws` 3.3.6 - S3/AWS filesystem integration
- `com.amazonaws:aws-java-sdk-bom` 1.12.750 (v1) + `software.amazon.awssdk:*` 2.30.27 (v2) - AWS SDK (both versions)
- `com.fasterxml.jackson:jackson-bom` 2.15.3 - JSON everywhere
- `io.opentelemetry:opentelemetry-bom` 1.41.0 - Distributed tracing and metrics export
- `org.glassfish.jersey.containers:jersey-container-jetty-servlet` 2.46 - REST server
- `net.snowflake:snowflake-jdbc` 3.20.0 - Snowflake connector
- `net.logstash.logback:logstash-logback-encoder` 7.2 - Structured JSON logging

**Code Quality:**
- Google Error Prone 2.36.0 - Compile-time bug detection (configured as compiler plugin in `/pom.xml`)
- Spotless 2.43.0 + google-java-format 1.25.2 - Java code formatting
- Janino 3.1.12 - Runtime Java bytecode compilation for expression evaluation

## Configuration

**Environment:**
- Primary config: HOCON format via `com.typesafe:config` 1.4.3 at `/distribution/resources/src/main/resources/conf/dremio.conf`
- Key paths configured via `DREMIO_HOME` environment variable
- `paths.local` - local data storage path
- `paths.dist` - distributed storage path (must be set for production)
- `services.coordinator.enabled`, `services.executor.enabled` - role configuration

**Build:**
- `/pom.xml` - Parent POM; all dependency versions declared here via `<dependencyManagement>`
- `/.mvn/wrapper/maven-wrapper.properties` - Maven 3.9.9 pinned
- `/mvnw`, `/mvnw.cmd` - Maven wrapper scripts
- `/dac/ui/package.json` - Frontend dependency manifest
- Sentry token: `project.sentry.token` property (skipped by default; `project.sentry.skip=true`)
- Intercom App ID: `dremio.ui.intercom.appid` (default `z8apq4co`)

## Platform Requirements

**Development:**
- Java 11 JDK (eclipse-temurin recommended)
- Maven 3.9.9 (via `./mvnw`)
- pnpm 8.7.0
- Significant RAM: tests require up to 3G heap + 5120M direct memory per fork; 4 parallel test forks

**Production:**
- Docker image based on `eclipse-temurin:11-jdk`
- Ports exposed: 9047 (HTTP/UI), 31010 (JDBC/ODBC client), 32010 (Arrow Flight), 45678 (internal Fabric RPC)
- Requires distributed filesystem for `paths.dist` (HDFS, S3, ADLS, GCS, or local)

---

*Stack analysis: 2026-02-17*
