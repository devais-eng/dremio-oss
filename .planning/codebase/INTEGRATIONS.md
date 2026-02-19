# External Integrations

**Analysis Date:** 2026-02-17

## APIs & External Services

**Customer Success / In-App Support:**
- Intercom - In-app chat and support widget embedded in UI
  - SDK: Browser script (`window.Intercom`)
  - Init: `/dac/ui/src/additionalSetup.js` calls `initIntercom` and `bootIntercom`
  - App ID: configured via `dremio.ui.intercom.appid` Maven property (default `z8apq4co`); runtime toggle via `window.dremioConfig.outsideCommunicationDisabled`
  - Helper files: `/dac/ui/src/intercom/initIntercom.js`, `/dac/ui/src/intercom/bootIntercom.js`, `/dac/ui/src/intercom/getIntercomAppId.js`

**Error Tracking:**
- Sentry - Frontend error reporting
  - SDK: `@sentry/browser 8.33.1`
  - Build plugin: `@sentry/webpack-plugin 2.22.6`
  - Config: `project.sentry.token` Maven property; disabled by default (`project.sentry.skip=true`)
  - Also referenced in UI startup via `SKIP_SENTRY_STEP=true` for development

**Analytics / Telemetry:**
- Segment - Frontend analytics (optional injection point)
  - Vendor injection: `/dac/ui/src/index.tsx` imports `@inject/vendor/segment`

**Nessie Catalog (Project Nessie):**
- Project Nessie 0.100.3 - Git-for-data catalog for Iceberg tables
  - Client: Nessie REST API client + gRPC (`services/nessie-grpc/`)
  - Services: `/services/nessie-grpc/`, `/services/nessie-proxy/`, `/services/nessie-metadata-cache/`, `/services/nessie-restjavax/`, `/services/nessie-validation/`
  - Plugin: `/plugins/dataplane/` (`DataplanePlugin`, `NessiePlugin`, `NessiePluginConfig`)
  - CEL expressions: `org.projectnessie.cel:cel-bom` 0.4.4 for access policies
  - UI client: auto-generated from OpenAPI spec via `gen:nessie` npm script targeting `http://localhost:19120/q/openapi`

## Data Storage

**Embedded Metadata Store:**
- RocksDB 7.10.2 - Primary embedded K/V store for all Dremio metadata
  - Client: `org.rocksdb:rocksdbjni`
  - Location: `services/datastore/` (`TestRocksDBStore.java`, `TestByteStoreManager.java`)
  - Connection: local filesystem path under `paths.local`

**Distributed Coordination:**
- Apache ZooKeeper 3.8.4 - Cluster coordination and service discovery
  - Client: Apache Curator 5.7.1 (`curator-framework`, `curator-recipes`, `curator-x-discovery`)
  - Implementation: `/services/coordinator/src/main/java/com/dremio/service/coordinator/zk/ZKClusterClient.java`
  - Embedded for single-node: `/dac/backend/src/main/java/com/dremio/dac/daemon/ZkServer.java`

**Pub/Sub Messaging:**
- NATS JetStream 2.20.6 - Event streaming and pub/sub for distributed operations
  - Client: `io.nats:jnats`
  - Implementation: `/services/pubsub-nats/src/main/java/com/dremio/services/pubsub/nats/`
  - Key classes: `NatsPubSubClient`, `NatsPublisher`, `NatsStreamManager`

**Test/Embedded Databases:**
- HSQLDB 2.3.1 - In-memory relational DB for test fixtures (foodmart, scott datasets)
- Derby 10.14.2.0 - Embedded relational DB used in some test scenarios
- Flapdoodle embedded MongoDB 4.18.1 - In-process MongoDB for plugin integration tests

**Caching:**
- Caffeine 3.2.0 - In-process caching (used throughout services)
- Redis (`redis.clients:jedis 5.0.2`) - Declared as dependency (available but use is service-specific)

## Object Storage (Source Plugins)

**Amazon S3:**
- Plugin: `/plugins/s3/`
- SDK: AWS SDK v1 (`com.amazonaws:aws-java-sdk-bom 1.12.750`) + AWS SDK v2 (`software.amazon.awssdk:* 2.30.27`)
- Key artifacts: `aws-java-sdk-s3`, `software.amazon.awssdk:s3`, `software.amazon.awssdk:sts`, `hadoop-aws`
- Auth: `InstanceProfileCredentialsProvider`, `STSAssumeRoleSessionCredentialsProvider`, profile credentials, `AssumeRoleCredentialsProvider` (`/common/aws/`)
- Port: 32010 (Arrow Flight) for data transfer

**Microsoft Azure Data Lake Storage:**
- Plugin: `/plugins/azure/`
- SDK: `azure.sdk.version 1.2.20`; also `azure-storage 8.3.0`, `hadoop-azure`
- Key classes: `AzureStoragePlugin`, `AzureStorageConf`, `AzureAuthTokenProvider`, `AzureSharedKeyCredentials`, `AzureSasSignatureCredentials`
- Auth methods: Shared Key, SAS token, OAuth (AAD)

**Google Cloud Storage:**
- Plugin: `/plugins/gcs/`
- SDK: `gcs-connector-version 2.2.2` (Dremio fork); `google-cloud-nio 0.126.3`
- Key classes: `GoogleStoragePlugin`, `GoogleBucketFileSystem`, `GCSConf`
- KMS integration: `google-cloud-kms 2.15.0` (available in dependency management)

**HDFS / On-premises:**
- Plugin: `/plugins/hdfs/`
- SDK: Apache Hadoop Common 3.3.6 (Dremio fork), `hadoop-common`
- Supports native Hadoop authentication including Kerberos

**NAS / Local Filesystem:**
- Plugin: `/plugins/nas/`
- Local or NFS filesystem access

## Database Source Plugins

**Hive Metastore (v2 and v3):**
- Plugins: `/plugins/hive2/`, `/plugins/hive3/`, `/plugins/hive-common/`, `/plugins/hive-function-registry/`
- Hive 2: `plugin.hive2.hive.version 2.3.9` (Dremio fork)
- Hive 3: `plugin.hive3.hive.version 3.1.1` (Dremio fork)
- Metastore backends: Hive Metastore service or embedded; HBase 1.1.13 (Hive2), HBase 2.6.0 (Hive3)
- DataNucleus ORM used internally by Hive Metastore

**AWS Glue Catalog:**
- Plugin: `/plugins/awsglue/`
- Hive2 SDK: `plugin.hive2.aws.glue.version 1.10.0`
- Hive3 SDK: `plugin.hive3.aws.glue.version 3.4.0`

**Elasticsearch / OpenSearch:**
- Plugin: `/plugins/elasticsearch/`
- Elasticsearch SDK: `elasticsearch.version 8.14.2` (client); tested against ES `7.17.24`
- OpenSearch client: `opensearch-java 2.19.0`
- HTTP client: `okhttp3 4.12.0`

**MongoDB:**
- Plugin: `/plugins/mongo/`
- Key class: `MongoStoragePluginConfig`
- Test: Flapdoodle embedded MongoDB 4.18.1

**Iceberg Catalog:**
- Plugin: `/plugins/icebergcatalog/`
- Apache Iceberg 1.7.0 (Dremio fork); ORC 1.5.1 (Dremio fork for Hive3)

**JDBC Sources (generic):**
- Plugin: `/plugins/` (jdbc-plugin)
- Snowflake: `net.snowflake:snowflake-jdbc 3.20.0`
- Vertica: `com.vertica.jdbc:vertica-jdbc 24.2.0`
- MariaDB/MySQL: `org.mariadb.jdbc:mariadb-java-client 3.0.8`
- PostgreSQL: `org.postgresql:postgresql 42.7.3`
- MySQL distribution: `mysql.dist.version 8.0.33.1`

## Authentication & Identity

**Auth Provider:**
- Custom pluggable authentication via `services/authenticator/`
  - Interface: `Authenticator`, `AuthProvider`, `AuthRequest`, `AuthResult` in `/services/authenticator/src/main/java/com/dremio/authenticator/`
  - JAX-RS filter: `DACAuthFilter` in `/dac/backend/src/main/java/com/dremio/dac/server/DACAuthFilter.java`

**Tokens & Sessions:**
- JWT-based tokens managed by `/services/tokens/`
- JWKS (JSON Web Key Set) management: `JWKSetManager`, `RemoteJWKSetManager`, `SystemJWKSetManager` in `/services/tokens/src/main/java/com/dremio/service/tokens/jwks/`
- Token manager: `TokenManagerImplV2` with JWT support

**SCIM 2.0:**
- SDK: `scim2-sdk 2.4.0` - User provisioning protocol implementation

**Hadoop Kerberos:**
- `/services/hadoopcredentials/` - `DremioCredentialProvider` integrating with Hadoop credential store
- Production key classes: `DremioCredentialProviderFactory`, `DremioCredentialProvider`

**Credentials Service:**
- `/services/credentials/` - Internal secret management and encryption
- Local cipher: `LocalCipher`, `SystemCipher` for at-rest secret encryption
- Remote secrets: `RemoteSecretsCreatorImpl` for distributed secret creation
- Providers: `SystemSecretCredentialsProvider`, `FileCredentialsException` for file-based credentials

**Crypto:**
- BouncyCastle 1.78.1 (`bcprov-jdk18on`, `bcpkix-jdk18on`) - Cryptographic operations

## Client Protocols

**Arrow Flight (Port 32010):**
- Protocol: Apache Arrow Flight RPC over gRPC
- Server: `DremioFlightServer` in `/services/arrow-flight/src/main/java/org/apache/arrow/flight/`
- Session management: `services/usersessions/`
- Used by: Python/pandas, Power BI, Tableau, and other Flight-compatible clients

**JDBC (Port 31010):**
- Driver: `client/jdbc/` - Custom Dremio JDBC driver
- Protocol: Apache Avatica 1.23.0 over Protobuf or JSON
- Distribution: `distribution/` packages shaded JDBC driver jar

**gRPC Internal RPC (Port 45678):**
- Fabric RPC: Custom binary RPC over Netty for intra-cluster data exchange
  - Implementation: `services/fabric-rpc/`, `services/base-rpc/`
  - Protocol definitions: `/protocol/src/main/protobuf/Fabric.proto`, `ExecRPC.proto`, `UserCoordRPC.proto`

**REST API (Port 9047):**
- Base path: `/api/v3/`
- Framework: Jersey 2.46 on Jetty 9.4
- Swagger/OpenAPI: `swagger-core 2.2.25`, `swagger-annotations 1.6.4`, `openapitools.plugin 6.3.0`
- API documentation generated via `build-tools/openapi-generator/`

## Monitoring & Observability

**Distributed Tracing:**
- OpenTelemetry 1.41.0 + instrumentation 2.12.0 - Primary tracing framework
  - Configurator: `/services/telemetry-impl/src/main/java/com/dremio/telemetry/impl/config/tracing/OpenTelemetryConfigurator.java`
- Jaeger - Tracing backend integration
  - Configurator: `/services/telemetry-impl/src/main/java/com/dremio/telemetry/impl/config/tracing/JaegerConfigurator.java`
- OpenTracing 0.33.0 + `opentracing-grpc 0.2.0` - Legacy tracing API bridge
- OpenCensus 0.31.1 - Additional observability bridge

**Metrics:**
- Dropwizard Metrics 4.1.19 - Core metrics framework used throughout services
- Micrometer 1.12.2 - Metrics façade for multiple backends
- Prometheus - Export format via `io.prometheus:simpleclient_bom 0.16.0`
- JMX - Metrics export configurator (`JmxConfigurator.java`)

**Logging:**
- SLF4J 1.7.36 API - Logging interface used everywhere
- Logback 1.2.13 (`logback-classic`, `logback-core`, `logback-access`) - Implementation
- Logstash Logback Encoder 7.2 - Structured JSON log output
- SLF4J configurator: `Slf4jConfigurator` for metrics-to-logs export

**Frontend Observability:**
- OpenTelemetry JS `@opentelemetry/api 1.7.0` + `exporter-trace-otlp-http 0.47.0` - Frontend tracing exported via OTLP/HTTP
- Sentry `@sentry/browser 8.33.1` - Frontend error reporting (production only)

## CI/CD & Deployment

**Hosting:**
- Docker: `eclipse-temurin:11-jdk` base image; Dockerfile at `/distribution/docker/Dockerfile`
- YARN: Provisioning on Apache Hadoop YARN via Apache Twill 0.14.0 (Dremio fork)
  - Implementation: `/provision/yarn/yarntwill/`
  - Key classes: `YarnController`, `DacDaemonYarnApplication`, `AppBundleGenerator`

**Build Tooling:**
- Maven 3.9.9 multi-module build (15 top-level modules)
- Frontend: `frontend-maven-plugin` with embedded pnpm 8.7.0
- OpenAPI code generation: `openapitools-maven-plugin 6.3.0` for server stubs and client TypeScript
- Protobuf: Maven plugins generate Java gRPC stubs from `.proto` files in `/protocol/`

## Webhooks & Callbacks

**Incoming:**
- REST API at port 9047 is the primary ingress
- Arrow Flight at port 32010 for bulk data transfer
- gRPC Fabric at port 45678 for internal cluster communication

**Outgoing:**
- Nessie REST API calls (internal embedded or external Nessie server)
- Cloud storage SDK calls to S3, ADLS, GCS endpoints
- Intercom SDK makes outbound calls to `api.intercom.io` (suppressible via `outsideCommunicationDisabled`)
- OpenTelemetry OTLP exporter: configurable endpoint for traces

## Environment Configuration

**Required env vars:**
- `DREMIO_HOME` - Root installation directory (used in `dremio.conf` template)
- `DREMIO_LOG_DIR` - Log output directory
- `DREMIO_PID_DIR` - PID file directory
- `DREMIO_GC_LOGS_ENABLED` - GC log enable flag
- `DREMIO_GC_LOG_TO_CONSOLE` - GC log destination

**Key config paths:**
- Main config: `${DREMIO_HOME}/conf/dremio.conf` (HOCON format)
- `paths.local` - Local data directory
- `paths.dist` - Distributed storage URI (e.g., `s3://bucket/prefix`)

**Secrets location:**
- Encrypted in RocksDB datastore via `CredentialsServiceImpl`; plaintext credentials never persisted
- Hadoop credential store integration via `DremioCredentialProvider` for Hadoop-compatible secrets

---

*Integration audit: 2026-02-17*
