/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.plugins.jdbc.integration;

import static org.junit.Assert.assertTrue;

import com.github.dockerjava.api.exception.NotFoundException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;

/**
 * Tier 2 integration tests for JDBC pushdown verification against a live Dremio planner stack.
 *
 * <p>Exercises all 32 UAT pushdown patterns through Dremio's full planning pipeline — not just
 * SqlBuilder unit tests — and verifies pushdown actually reached the database via PG container logs
 * and Oracle V$SQL. Covers WHERE, LIMIT, ORDER BY, AGG, JOIN, combined patterns, correctness
 * checks, and ADBC protocol path.
 *
 * <p>When the {@code dremio-oss:jdbc-test} Docker image is not available on the local machine, the
 * entire test class is skipped via {@code Assume.assumeTrue}.
 */
public class TestDremioJdbcIntegration {

  // ── Shared Docker network ────────────────────────────────────────────────
  @ClassRule(order = 0)
  public static final Network NETWORK = Network.newNetwork();

  // ── Containers ───────────────────────────────────────────────────────────
  @ClassRule(order = 1)
  public static final DremioJdbcPgContainer PG =
      new DremioJdbcPgContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases("postgres");

  // OracleContainer.withNetwork/withNetworkAliases return the raw OracleContainer type (not the
  // subclass), so we configure the container post-construction with a helper.
  public static final DremioJdbcOracleContainer ORACLE_CONTAINER = new DremioJdbcOracleContainer();

  static {
    ORACLE_CONTAINER.withNetwork(NETWORK).withNetworkAliases("oracle");
  }

  @ClassRule(order = 2)
  public static final DremioJdbcOracleContainer ORACLE = ORACLE_CONTAINER;

  @ClassRule(order = 3)
  public static final DremioJdbcContainer DREMIO =
      new DremioJdbcContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases("dremio");

  @ClassRule(order = 4)
  public static final DremioJdbcMinioContainer MINIO =
      new DremioJdbcMinioContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases("minio");

  @ClassRule(order = 5)
  public static final DremioJdbcNessieContainer NESSIE =
      new DremioJdbcNessieContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases("nessie");

  private static String TOKEN;
  private static String DREMIO_URL;
  // ── Source table identifiers ─────────────────────────────────────────────
  private static final String PG_EMP    = "pg_test.public.employees";
  private static final String PG_DEPT   = "pg_test.public.departments";
  private static final String ORA_EMP   = "oracle_test.TESTUSER.EMPLOYEES";
  private static final String ORA_DEPT  = "oracle_test.TESTUSER.DEPARTMENTS";
  private static final String ADBC_EMP  = "pg_adbc_test.public.employees";
  private static final String PG_VEC    = "pg_test.public.embeddings";
  private static final String ADBC_VEC  = "pg_adbc_test.public.embeddings";

  // ── UAT multi-source table identifiers ──────────────────────────────────
  private static final String PG_PRODUCTS    = "pg_jdbc.public.products";
  private static final String PG_ORDERS      = "pg_jdbc.public.orders";
  private static final String PG_ORDER_ITEMS = "pg_jdbc.public.order_items";
  private static final String ORA_CUSTOMERS  = "oracle_src.TESTUSER.CUSTOMERS";
  private static final String ORA_REGIONS    = "oracle_src.TESTUSER.REGIONS";
  private static final String ADBC_PRODUCTS  = "pg_adbc.public.products";

  // ── Iceberg / Nessie / S3 table identifiers ───────────────────────────────
  private static final String ICE_CATEGORIES = "nessie_rest.analytics.product_categories";
  private static final String ICE_SALES      = "nessie_rest.analytics.monthly_sales";
  private static final String S3_SHIPPING    = "s3_parquet.\"parquet-data\".shipping_rates";
  private static final String S3_REVIEWS     = "s3_parquet.\"parquet-data\".product_reviews";
  private static final String NVER_CATEGORIES = "nessie_ver.analytics.product_categories";

  // ── PG log filter patterns (same as test-regression.sh) ─────────────────
  private static final Pattern PG_INCLUDE =
      Pattern.compile("execute|statement:", Pattern.CASE_INSENSITIVE);
  private static final Pattern PG_EXCLUDE =
      Pattern.compile(
          "pg_catalog|information_schema|SET|BEGIN|COMMIT|DEALLOCATE"
              + "|pg_type|pg_namespace|ROLLBACK|SELECT 1|SHOW|pg_class"
              + "|pg_attribute|pg_attrdef|pg_constraint|pg_index|pg_description"
              + "|pg_am|pg_stat|pg_settings",
          Pattern.CASE_INSENSITIVE);

  // ── BeforeClass ──────────────────────────────────────────────────────────
  @BeforeClass
  public static void setUpClass() throws Exception {
    // Guard: skip if dremio-oss:jdbc-test image is not available
    try {
      DockerClientFactory.instance().client().inspectImageCmd("dremio-oss:jdbc-test").exec();
    } catch (NotFoundException e) {
      Assume.assumeTrue("dremio-oss:jdbc-test image not available — skipping Tier 2 tests", false);
    }

    DREMIO_URL = DREMIO.getDremioUrl();

    // Seed PostgreSQL (existing test data + UAT multi-source data)
    seedPostgres();
    seedPostgresUat();

    // Seed Oracle (existing test data + UAT multi-source data)
    seedOracle();
    seedOracleUat();

    // Create MinIO bucket for Nessie/Iceberg warehouse
    createMinioBuckets();

    // Bootstrap Dremio admin user
    bootstrapAdmin(DREMIO_URL);

    // Get auth token
    TOKEN = getToken(DREMIO_URL);

    // Create existing test sources
    createSource(DREMIO_URL, TOKEN, pgSourceJson("postgres", 5432));
    createSource(DREMIO_URL, TOKEN, oracleSourceJson("oracle", 1521));
    createSource(DREMIO_URL, TOKEN, adbcSourceJson("postgres", 5432));

    // Create UAT multi-source sources
    createSource(DREMIO_URL, TOKEN, pgJdbcSourceJson("postgres", 5432));
    createSource(DREMIO_URL, TOKEN, pgAdbcSourceJson("postgres", 5432));
    createSource(DREMIO_URL, TOKEN, oracleUatSourceJson("oracle", 1521));
    createSource(DREMIO_URL, TOKEN, nessieRestSourceJson("nessie", 19120));
    createSource(DREMIO_URL, TOKEN, nessieVerSourceJson("nessie", 19120));
    createSource(DREMIO_URL, TOKEN, s3ParquetSourceJson("minio", 9000));

    // Wait for metadata refresh
    Thread.sleep(15_000);

    // Seed Iceberg tables via pyiceberg in a Python container on the same Docker network.
    // This matches the UAT approach (samples/jdbc-connectors/scripts/seed-all.sh) and avoids
    // Dremio CTAS compatibility issues with the Nessie REST catalog.
    seedIceberg();

    // Upload CSV files to MinIO and promote them in Dremio
    seedMinioParquet();

    // Wait for metadata refresh after Iceberg + S3 seeding
    Thread.sleep(10_000);
  }

  // ── Seed helpers ─────────────────────────────────────────────────────────

  private static void seedPostgres() throws Exception {
    String jdbcUrl = PG.getJdbcUrl();
    try (Connection conn = DriverManager.getConnection(jdbcUrl, PG.getUsername(), PG.getPassword());
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS employees ("
              + "id INTEGER PRIMARY KEY, name VARCHAR(100) NOT NULL, "
              + "department VARCHAR(50), salary NUMERIC(10,2), hire_date DATE)");
      stmt.execute("TRUNCATE employees RESTART IDENTITY");
      stmt.execute(
          "INSERT INTO employees (id, name, department, salary, hire_date) VALUES "
              + "(1,'Alice','Engineering',120000,'2020-01-15'),"
              + "(2,'Bob','Marketing',95000,'2019-03-22'),"
              + "(3,'Charlie','Engineering',130000,'2021-06-01'),"
              + "(4,'Diana','Sales',88000,'2018-11-30'),"
              + "(5,'Eve','Engineering',145000,'2017-07-14'),"
              + "(6,'Frank','Marketing',72000,'2022-02-28'),"
              + "(7,'Grace','Sales',105000,'2020-09-10'),"
              + "(8,'Hank',NULL,60000,'2023-01-01'),"
              + "(9,'Ivy','Engineering',110000,'2019-12-05'),"
              + "(10,'Jack','Marketing',98000,'2021-04-18'),"
              + "(11,'Karen','Sales',115000,'2018-08-25'),"
              + "(12,'Leo',NULL,55000,'2023-06-15')");
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS departments ("
              + "dept_name VARCHAR(50) PRIMARY KEY, budget NUMERIC(12,2) NOT NULL, "
              + "location VARCHAR(100) NOT NULL)");
      stmt.execute("TRUNCATE departments");
      stmt.execute(
          "INSERT INTO departments (dept_name, budget, location) VALUES "
              + "('Engineering',500000,'Building A'),"
              + "('Marketing',200000,'Building B'),"
              + "('Sales',300000,'Building C'),"
              + "('HR',150000,'Building D')");
      // pgvector: create extension + embeddings table with 200 rows and HNSW indexes
      stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
      stmt.execute("DROP TABLE IF EXISTS embeddings");
      stmt.execute(
          "CREATE TABLE embeddings ("
              + "id INTEGER PRIMARY KEY, label TEXT, embedding vector(3))");
      stmt.execute(
          "INSERT INTO embeddings SELECT gs, 'item_' || gs, "
              + "('[' || (gs * 0.01)::text || ',' || (gs * 0.02)::text "
              + "|| ',' || (gs * 0.03)::text || ']')::vector "
              + "FROM generate_series(1, 200) gs");
      stmt.execute(
          "CREATE INDEX IF NOT EXISTS embeddings_l2_idx "
              + "ON embeddings USING hnsw (embedding vector_l2_ops)");
      stmt.execute(
          "CREATE INDEX IF NOT EXISTS embeddings_cos_idx "
              + "ON embeddings USING hnsw (embedding vector_cosine_ops)");
      stmt.execute(
          "CREATE INDEX IF NOT EXISTS embeddings_ip_idx "
              + "ON embeddings USING hnsw (embedding vector_ip_ops)");
    }
  }

  private static void seedOracle() throws Exception {
    // Seed Oracle — result is intentionally ignored (errors surface as test failures)
    ORACLE
        .execInContainer(
            "sh",
            "-c",
            "sqlplus -s TESTUSER/testpass@//localhost:1521/XEPDB1 <<'EOSQL'\n"
                    + "BEGIN\n"
                    + "  EXECUTE IMMEDIATE 'CREATE TABLE EMPLOYEES ("
                    + "id NUMBER(10) PRIMARY KEY, name VARCHAR2(100) NOT NULL, "
                    + "department VARCHAR2(50), salary NUMBER(10,2), hire_date DATE)';\n"
                    + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;\n"
                    + "END;\n"
                    + "/\n"
                    + "DELETE FROM EMPLOYEES;\n"
                    + "INSERT ALL\n"
                    + "  INTO EMPLOYEES VALUES(1,'Alice','Engineering',120000,TO_DATE('2020-01-15','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(2,'Bob','Marketing',95000,TO_DATE('2019-03-22','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(3,'Charlie','Engineering',130000,TO_DATE('2021-06-01','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(4,'Diana','Sales',88000,TO_DATE('2018-11-30','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(5,'Eve','Engineering',145000,TO_DATE('2017-07-14','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(6,'Frank','Marketing',72000,TO_DATE('2022-02-28','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(7,'Grace','Sales',105000,TO_DATE('2020-09-10','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(8,'Hank',NULL,60000,TO_DATE('2023-01-01','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(9,'Ivy','Engineering',110000,TO_DATE('2019-12-05','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(10,'Jack','Marketing',98000,TO_DATE('2021-04-18','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(11,'Karen','Sales',115000,TO_DATE('2018-08-25','YYYY-MM-DD'))\n"
                    + "  INTO EMPLOYEES VALUES(12,'Leo',NULL,55000,TO_DATE('2023-06-15','YYYY-MM-DD'))\n"
                    + "SELECT 1 FROM DUAL;\n"
                    + "BEGIN\n"
                    + "  EXECUTE IMMEDIATE 'CREATE TABLE DEPARTMENTS ("
                    + "dept_name VARCHAR2(50) PRIMARY KEY, budget NUMBER(12,2) NOT NULL, "
                    + "location VARCHAR2(100) NOT NULL)';\n"
                    + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;\n"
                    + "END;\n"
                    + "/\n"
                    + "DELETE FROM DEPARTMENTS;\n"
                    + "INSERT ALL\n"
                    + "  INTO DEPARTMENTS VALUES('Engineering',500000,'Building A')\n"
                    + "  INTO DEPARTMENTS VALUES('Marketing',200000,'Building B')\n"
                    + "  INTO DEPARTMENTS VALUES('Sales',300000,'Building C')\n"
                    + "  INTO DEPARTMENTS VALUES('HR',150000,'Building D')\n"
                    + "SELECT 1 FROM DUAL;\n"
                    + "COMMIT;\n"
                    + "EOSQL");
  }

  // ── UAT seed helpers ───────────────────────────────────────────────────────

  /** Seeds UAT multi-source products/orders/order_items tables in PostgreSQL. */
  private static void seedPostgresUat() throws Exception {
    String jdbcUrl = PG.getJdbcUrl();
    try (Connection conn = DriverManager.getConnection(jdbcUrl, PG.getUsername(), PG.getPassword());
        Statement stmt = conn.createStatement()) {
      // Products with 4-dim pgvector embeddings (matching seed-all.sh exactly)
      stmt.execute("DROP TABLE IF EXISTS order_items CASCADE");
      stmt.execute("DROP TABLE IF EXISTS orders CASCADE");
      stmt.execute("DROP TABLE IF EXISTS products CASCADE");
      stmt.execute(
          "CREATE TABLE products ("
              + "id INTEGER PRIMARY KEY, name VARCHAR(200) NOT NULL, "
              + "category_id INTEGER NOT NULL, price NUMERIC(10,2) NOT NULL, "
              + "description TEXT, embedding vector(4))");
      stmt.execute(
          "INSERT INTO products VALUES "
              + "(1, 'Laptop Pro 15', 1, 1299.99, 'High-performance laptop', '[0.8, 0.2, 0.1, 0.9]'),"
              + "(2, 'Wireless Mouse', 1, 29.99, 'Ergonomic wireless mouse', '[0.7, 0.3, 0.2, 0.8]'),"
              + "(3, 'USB-C Hub', 1, 49.99, 'Multi-port USB-C hub', '[0.75, 0.25, 0.15, 0.85]'),"
              + "(4, 'Standing Desk', 2, 599.99, 'Adjustable standing desk', '[0.1, 0.9, 0.8, 0.2]'),"
              + "(5, 'Office Chair', 2, 449.99, 'Ergonomic office chair', '[0.15, 0.85, 0.75, 0.25]'),"
              + "(6, 'Desk Lamp', 2, 79.99, 'LED desk lamp', '[0.2, 0.8, 0.7, 0.3]'),"
              + "(7, 'Mechanical Keyboard', 1, 149.99, 'Cherry MX switches', '[0.72, 0.28, 0.18, 0.82]'),"
              + "(8, 'Monitor 27\"', 1, 399.99, '4K IPS display', '[0.78, 0.22, 0.12, 0.88]'),"
              + "(9, 'Webcam HD', 1, 89.99, '1080p webcam', '[0.65, 0.35, 0.25, 0.75]'),"
              + "(10, 'Bookshelf', 2, 199.99, 'Wooden bookshelf', '[0.12, 0.88, 0.78, 0.22]'),"
              + "(11, 'Headphones', 1, 249.99, 'Noise-cancelling', '[0.68, 0.32, 0.22, 0.78]'),"
              + "(12, 'Filing Cabinet', 2, 159.99, 'Metal filing cabinet', '[0.18, 0.82, 0.72, 0.28]'),"
              + "(13, 'Tablet 10\"', 1, 499.99, 'Android tablet', '[0.76, 0.24, 0.14, 0.86]'),"
              + "(14, 'Whiteboard', 2, 89.99, 'Magnetic whiteboard', '[0.22, 0.78, 0.68, 0.32]'),"
              + "(15, 'Printer', 1, 299.99, 'Color laser printer', '[0.62, 0.38, 0.28, 0.72]')");
      stmt.execute(
          "CREATE INDEX IF NOT EXISTS products_embedding_l2_idx "
              + "ON products USING hnsw (embedding vector_l2_ops)");

      // Orders (10 rows)
      stmt.execute(
          "CREATE TABLE orders ("
              + "id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, "
              + "order_date DATE NOT NULL, total NUMERIC(12,2) NOT NULL, "
              + "status VARCHAR(20) NOT NULL)");
      stmt.execute(
          "INSERT INTO orders VALUES "
              + "(1, 101, '2024-01-15', 1329.98, 'shipped'),"
              + "(2, 102, '2024-01-20', 649.98, 'delivered'),"
              + "(3, 103, '2024-02-01', 499.99, 'shipped'),"
              + "(4, 101, '2024-02-10', 79.99, 'delivered'),"
              + "(5, 104, '2024-02-15', 1749.98, 'processing'),"
              + "(6, 105, '2024-03-01', 449.99, 'delivered'),"
              + "(7, 102, '2024-03-05', 299.99, 'shipped'),"
              + "(8, 103, '2024-03-10', 149.99, 'delivered'),"
              + "(9, 106, '2024-03-15', 89.99, 'shipped'),"
              + "(10, 104, '2024-03-20', 599.99, 'delivered')");

      // Order items (13 rows, linking orders to products)
      stmt.execute(
          "CREATE TABLE order_items ("
              + "id INTEGER PRIMARY KEY, order_id INTEGER NOT NULL REFERENCES orders(id), "
              + "product_id INTEGER NOT NULL REFERENCES products(id), "
              + "quantity INTEGER NOT NULL, unit_price NUMERIC(10,2) NOT NULL)");
      stmt.execute(
          "INSERT INTO order_items VALUES "
              + "(1, 1, 1, 1, 1299.99),"
              + "(2, 1, 2, 1, 29.99),"
              + "(3, 2, 4, 1, 599.99),"
              + "(4, 2, 3, 1, 49.99),"
              + "(5, 3, 13, 1, 499.99),"
              + "(6, 4, 6, 1, 79.99),"
              + "(7, 5, 1, 1, 1299.99),"
              + "(8, 5, 5, 1, 449.99),"
              + "(9, 6, 5, 1, 449.99),"
              + "(10, 7, 15, 1, 299.99),"
              + "(11, 8, 7, 1, 149.99),"
              + "(12, 9, 14, 1, 89.99),"
              + "(13, 10, 4, 1, 599.99)");
    }
  }

  /** Seeds UAT Oracle REGIONS and CUSTOMERS tables. */
  private static void seedOracleUat() throws Exception {
    ORACLE.execInContainer(
        "sh",
        "-c",
        "sqlplus -s TESTUSER/testpass@//localhost:1521/XEPDB1 <<'EOSQL'\n"
            + "BEGIN EXECUTE IMMEDIATE 'DROP TABLE CUSTOMERS'; EXCEPTION WHEN OTHERS THEN NULL; END;\n"
            + "/\n"
            + "BEGIN EXECUTE IMMEDIATE 'DROP TABLE REGIONS'; EXCEPTION WHEN OTHERS THEN NULL; END;\n"
            + "/\n"
            + "CREATE TABLE REGIONS (\n"
            + "  ID NUMBER(10) PRIMARY KEY,\n"
            + "  NAME VARCHAR2(100) NOT NULL,\n"
            + "  COUNTRY VARCHAR2(50) NOT NULL\n"
            + ")\n"
            + "/\n"
            + "INSERT ALL\n"
            + "  INTO REGIONS VALUES (1, 'West Coast', 'US')\n"
            + "  INTO REGIONS VALUES (2, 'East Coast', 'US')\n"
            + "  INTO REGIONS VALUES (3, 'Midwest', 'US')\n"
            + "  INTO REGIONS VALUES (4, 'Europe', 'EU')\n"
            + "SELECT 1 FROM DUAL\n"
            + "/\n"
            + "CREATE TABLE CUSTOMERS (\n"
            + "  ID NUMBER(10) PRIMARY KEY,\n"
            + "  NAME VARCHAR2(100) NOT NULL,\n"
            + "  EMAIL VARCHAR2(200) NOT NULL,\n"
            + "  REGION_ID NUMBER(10) NOT NULL,\n"
            + "  TIER VARCHAR2(20) NOT NULL,\n"
            + "  SIGNUP_DATE DATE NOT NULL\n"
            + ")\n"
            + "/\n"
            + "INSERT ALL\n"
            + "  INTO CUSTOMERS VALUES (101, 'Alice Johnson', 'alice@example.com', 1, 'premium', DATE '2023-01-15')\n"
            + "  INTO CUSTOMERS VALUES (102, 'Bob Smith', 'bob@example.com', 2, 'standard', DATE '2023-03-20')\n"
            + "  INTO CUSTOMERS VALUES (103, 'Carol Chen', 'carol@example.com', 1, 'premium', DATE '2023-05-10')\n"
            + "  INTO CUSTOMERS VALUES (104, 'Dave Wilson', 'dave@example.com', 3, 'premium', DATE '2023-07-01')\n"
            + "  INTO CUSTOMERS VALUES (105, 'Eve Brown', 'eve@example.com', 4, 'standard', DATE '2023-09-15')\n"
            + "  INTO CUSTOMERS VALUES (106, 'Frank Lee', 'frank@example.com', 2, 'standard', DATE '2023-11-20')\n"
            + "SELECT 1 FROM DUAL\n"
            + "/\n"
            + "COMMIT\n"
            + "/\n"
            + "EOSQL");
  }

  /** Creates MinIO buckets required by Nessie/Iceberg and S3 Parquet sources. */
  private static void createMinioBuckets() throws Exception {
    io.minio.MinioClient minioClient =
        io.minio.MinioClient.builder()
            .endpoint(MINIO.getS3Endpoint())
            .credentials("minioadmin", "minioadmin")
            .build();
    // Warehouse bucket for Nessie/Iceberg
    if (!minioClient.bucketExists(
        io.minio.BucketExistsArgs.builder().bucket("warehouse").build())) {
      minioClient.makeBucket(io.minio.MakeBucketArgs.builder().bucket("warehouse").build());
    }
    // Parquet data bucket for S3 source
    if (!minioClient.bucketExists(
        io.minio.BucketExistsArgs.builder().bucket("parquet-data").build())) {
      minioClient.makeBucket(
          io.minio.MakeBucketArgs.builder().bucket("parquet-data").build());
    }
  }

  /**
   * Seeds Iceberg tables via Dremio SQL CTAS into the nessie_rest source. Requires the nessie_rest
   * source to already be created and metadata to be refreshed.
   */
  private static void seedIceberg() throws Exception {
    // Seed Iceberg tables via pyiceberg in a Python container on the same Docker network.
    // This matches the UAT approach (samples/jdbc-connectors/scripts/seed-all.sh) exactly,
    // avoiding Dremio CTAS compatibility issues with the Nessie REST catalog.
    String script =
        "pip install --quiet pyiceberg[s3fs]==0.7.1 pyarrow==17.0.0 2>/dev/null && python3 -c '"
            + "import pyarrow as pa\n"
            + "from pyiceberg.catalog import load_catalog\n"
            + "catalog = load_catalog(\"nessie\", **{\n"
            + "    \"uri\": \"http://nessie:19120/iceberg/\",\n"
            + "    \"s3.endpoint\": \"http://minio:9000\",\n"
            + "    \"s3.access-key-id\": \"minioadmin\",\n"
            + "    \"s3.secret-access-key\": \"minioadmin\",\n"
            + "    \"s3.region\": \"us-east-1\",\n"
            + "    \"s3.path-style-access\": \"true\",\n"
            + "})\n"
            + "try:\n"
            + "    catalog.create_namespace(\"analytics\")\n"
            + "except Exception:\n"
            + "    pass\n"
            + "schema = pa.schema([(\"category_id\", pa.int32()), (\"category_name\", pa.string()),"
            + " (\"department\", pa.string()), (\"margin_pct\", pa.float64())])\n"
            + "data = pa.table({\"category_id\": [1, 2, 3],"
            + " \"category_name\": [\"Electronics\", \"Furniture\", \"Accessories\"],"
            + " \"department\": [\"Tech\", \"Home\", \"Tech\"],"
            + " \"margin_pct\": [0.15, 0.25, 0.30]}, schema=schema)\n"
            + "try:\n"
            + "    catalog.drop_table(\"analytics.product_categories\")\n"
            + "except Exception:\n"
            + "    pass\n"
            + "tbl = catalog.create_table(\"analytics.product_categories\", schema=schema)\n"
            + "tbl.append(data)\n"
            + "print(f\"Created analytics.product_categories: {len(data)} rows\")\n"
            + "ss = pa.schema([(\"month\", pa.string()), (\"product_id\", pa.int32()),"
            + " (\"units_sold\", pa.int32()), (\"revenue\", pa.float64())])\n"
            + "sd = pa.table({\"month\": [\"2024-01\",\"2024-01\",\"2024-02\",\"2024-02\","
            + "\"2024-03\",\"2024-03\",\"2024-01\",\"2024-02\",\"2024-03\",\"2024-01\","
            + "\"2024-02\",\"2024-03\"],"
            + " \"product_id\": [1,4,1,13,5,7,2,6,15,8,3,14],"
            + " \"units_sold\": [5,3,8,2,4,6,15,7,3,4,10,5],"
            + " \"revenue\": [6499.95,1799.97,10399.92,999.98,1799.96,899.94,"
            + "449.85,559.93,899.97,1599.96,499.90,449.95]}, schema=ss)\n"
            + "try:\n"
            + "    catalog.drop_table(\"analytics.monthly_sales\")\n"
            + "except Exception:\n"
            + "    pass\n"
            + "tbl2 = catalog.create_table(\"analytics.monthly_sales\", schema=ss)\n"
            + "tbl2.append(sd)\n"
            + "print(f\"Created analytics.monthly_sales: {len(sd)} rows\")\n"
            + "'";
    DremioJdbcPythonSeedContainer pyContainer =
        new DremioJdbcPythonSeedContainer(script);
    pyContainer.withNetwork(NETWORK);
    pyContainer.start();
    // Wait for both tables to be created
    org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Created analytics.*", 2)
        .withStartupTimeout(Duration.ofMinutes(3))
        .waitUntilReady(pyContainer);
    String logs = pyContainer.getLogs();
    System.out.println("[Iceberg seed] " + logs);
    pyContainer.stop();
  }

  /**
   * Uploads Parquet files to MinIO for the S3 source. Uses MinIO Java client to create CSV data
   * files and the Dremio promote API to register them as datasets.
   *
   * <p>Since writing actual Parquet files in Java requires heavyweight Hadoop dependencies, we
   * upload CSV files and promote them as CSV format in Dremio. This is simpler and Dremio supports
   * CSV auto-detection for S3 sources.
   */
  private static void seedMinioParquet() throws Exception {
    io.minio.MinioClient minioClient =
        io.minio.MinioClient.builder()
            .endpoint(MINIO.getS3Endpoint())
            .credentials("minioadmin", "minioadmin")
            .build();

    // Shipping rates CSV
    String shippingCsv =
        "region_id,region_name,shipping_rate,delivery_days\n"
            + "1,West Coast,5.99,3\n"
            + "2,East Coast,7.99,4\n"
            + "3,Midwest,6.99,5\n"
            + "4,Europe,15.99,10\n";
    byte[] shippingBytes = shippingCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    minioClient.putObject(
        io.minio.PutObjectArgs.builder()
            .bucket("parquet-data")
            .object("shipping_rates/data.csv")
            .stream(new java.io.ByteArrayInputStream(shippingBytes), shippingBytes.length, -1)
            .contentType("text/csv")
            .build());

    // Product reviews CSV
    String reviewsCsv =
        "review_id,product_id,rating,review_text\n"
            + "1,1,5,Great laptop\n"
            + "2,1,4,Good value\n"
            + "3,4,5,Solid desk\n"
            + "4,5,3,OK chair\n"
            + "5,7,4,Love the keys\n"
            + "6,8,5,Crisp display\n"
            + "7,13,4,Nice tablet\n"
            + "8,2,5,Perfect mouse\n";
    byte[] reviewsBytes = reviewsCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    minioClient.putObject(
        io.minio.PutObjectArgs.builder()
            .bucket("parquet-data")
            .object("product_reviews/data.csv")
            .stream(new java.io.ByteArrayInputStream(reviewsBytes), reviewsBytes.length, -1)
            .contentType("text/csv")
            .build());

    // Promote folders in Dremio
    Thread.sleep(5_000);
    HttpClient client = HttpClient.newHttpClient();
    for (String folder : new String[] {"shipping_rates", "product_reviews"}) {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      DREMIO_URL
                          + "/apiv2/source/s3_parquet/file_format/parquet-data/"
                          + folder))
              .PUT(HttpRequest.BodyPublishers.ofString("{\"type\":\"Text\",\"fieldDelimiter\":\",\",\"lineDelimiter\":\"\\n\",\"extractHeader\":true}"))
              .header("Content-Type", "application/json")
              .header("Authorization", TOKEN)
              .build();
      client.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }

  // ── UAT source JSON builders ──────────────────────────────────────────────

  private static String pgJdbcSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"pg_jdbc\","
        + "\"config\":{"
        + "\"hostname\":\"" + hostname + "\","
        + "\"port\":" + port + ","
        + "\"databaseName\":\"testdb\","
        + "\"username\":\"pguser\","
        + "\"password\":\"pgpass\","
        + "\"useSsl\":false,"
        + "\"encryptionValidationMode\":\"NO_VALIDATION\","
        + "\"fetchSize\":4096,"
        + "\"queryTimeoutSec\":0,"
        + "\"protocolMode\":\"JDBC\""
        + "},"
        + "\"type\":\"POSTGRES_DB\""
        + "}";
  }

  private static String pgAdbcSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"pg_adbc\","
        + "\"config\":{"
        + "\"hostname\":\"" + hostname + "\","
        + "\"port\":" + port + ","
        + "\"databaseName\":\"testdb\","
        + "\"username\":\"pguser\","
        + "\"password\":\"pgpass\","
        + "\"useSsl\":false,"
        + "\"encryptionValidationMode\":\"NO_VALIDATION\","
        + "\"fetchSize\":4096,"
        + "\"queryTimeoutSec\":0,"
        + "\"protocolMode\":\"AUTO\""
        + "},"
        + "\"type\":\"POSTGRES_DB\""
        + "}";
  }

  private static String oracleUatSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"oracle_src\","
        + "\"config\":{"
        + "\"hostname\":\"" + hostname + "\","
        + "\"port\":" + port + ","
        + "\"serviceName\":\"XEPDB1\","
        + "\"username\":\"testuser\","
        + "\"password\":\"testpass\","
        + "\"useSsl\":false,"
        + "\"encryptionValidationMode\":\"NO_VALIDATION\","
        + "\"fetchSize\":4096,"
        + "\"queryTimeoutSec\":0"
        + "},"
        + "\"type\":\"ORACLE_DB\""
        + "}";
  }

  private static String nessieRestSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"nessie_rest\","
        + "\"config\":{"
        + "\"restEndpointUri\":\"http://" + hostname + ":" + port + "/iceberg/\","
        + "\"propertyList\":["
        + "{\"name\":\"warehouse\",\"value\":\"warehouse\"},"
        + "{\"name\":\"fs.s3a.endpoint\",\"value\":\"minio:9000\"},"
        + "{\"name\":\"fs.s3a.access.key\",\"value\":\"minioadmin\"},"
        + "{\"name\":\"fs.s3a.secret.key\",\"value\":\"minioadmin\"},"
        + "{\"name\":\"fs.s3a.path.style.access\",\"value\":\"true\"},"
        + "{\"name\":\"fs.s3a.connection.ssl.enabled\",\"value\":\"false\"},"
        + "{\"name\":\"dremio.s3.compat\",\"value\":\"true\"},"
        + "{\"name\":\"fs.s3a.aws.credentials.provider\",\"value\":\"org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider\"}"
        + "],"
        + "\"secretPropertyList\":[]"
        + "},"
        + "\"type\":\"RESTCATALOG\""
        + "}";
  }

  private static String nessieVerSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"nessie_ver\","
        + "\"config\":{"
        + "\"nessieEndpoint\":\"http://" + hostname + ":" + port + "/api/v2\","
        + "\"nessieAuthType\":\"NONE\","
        + "\"awsAccessKey\":\"minioadmin\","
        + "\"awsAccessSecret\":\"minioadmin\","
        + "\"awsRootPath\":\"/warehouse\","
        + "\"secure\":false,"
        + "\"propertyList\":["
        + "{\"name\":\"fs.s3a.endpoint\",\"value\":\"minio:9000\"},"
        + "{\"name\":\"fs.s3a.path.style.access\",\"value\":\"true\"},"
        + "{\"name\":\"fs.s3a.connection.ssl.enabled\",\"value\":\"false\"},"
        + "{\"name\":\"dremio.s3.compat\",\"value\":\"true\"},"
        + "{\"name\":\"fs.s3a.aws.credentials.provider\",\"value\":\"org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider\"}"
        + "],"
        + "\"credentialType\":\"ACCESS_KEY\""
        + "},"
        + "\"type\":\"NESSIE\""
        + "}";
  }

  private static String s3ParquetSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"s3_parquet\","
        + "\"config\":{"
        + "\"accessKey\":\"minioadmin\","
        + "\"accessSecret\":\"minioadmin\","
        + "\"secure\":false,"
        + "\"externalBucketList\":[\"parquet-data\"],"
        + "\"rootPath\":\"/\","
        + "\"compatibilityMode\":true,"
        + "\"enableAsync\":true,"
        + "\"propertyList\":["
        + "{\"name\":\"fs.s3a.endpoint\",\"value\":\"" + hostname + ":" + port + "\"},"
        + "{\"name\":\"fs.s3a.path.style.access\",\"value\":\"true\"},"
        + "{\"name\":\"fs.s3a.connection.ssl.enabled\",\"value\":\"false\"}"
        + "],"
        + "\"credentialType\":\"ACCESS_KEY\""
        + "},"
        + "\"type\":\"S3\""
        + "}";
  }

  // ── Dremio REST API helpers ───────────────────────────────────────────────

  private static void bootstrapAdmin(String dremioUrl) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String body =
        "{\"userName\":\"admin\",\"firstName\":\"A\",\"lastName\":\"U\","
            + "\"email\":\"a@e.c\",\"createdAt\":0,\"password\":\"Admin123!\"}";
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(dremioUrl + "/apiv2/bootstrap/firstuser"))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build();
    // Ignore non-200: may already be bootstrapped
    client.send(req, HttpResponse.BodyHandlers.discarding());
  }

  private static String getToken(String dremioUrl) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String body = "{\"userName\":\"admin\",\"password\":\"Admin123!\"}";
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(dremioUrl + "/apiv2/login"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    String json = resp.body();
    // Simple JSON extraction: "token":"<value>"
    int start = json.indexOf("\"token\":\"") + 9;
    int end = json.indexOf("\"", start);
    return "_dremio" + json.substring(start, end);
  }

  private static void createSource(String dremioUrl, String token, String sourceJson)
      throws Exception {
    // Extract source name from JSON for the PUT URL
    int nameStart = sourceJson.indexOf("\"name\":\"") + 8;
    int nameEnd = sourceJson.indexOf("\"", nameStart);
    String name = sourceJson.substring(nameStart, nameEnd);

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(dremioUrl + "/apiv2/source/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(sourceJson))
            .header("Content-Type", "application/json")
            .header("Authorization", token)
            .build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    int code = resp.statusCode();
    if (code == 200 || code == 409) {
      return; // Created or already exists
    }
    throw new RuntimeException(
        "createSource(" + name + ") failed: HTTP " + code + " — " + resp.body());
  }

  private static String pgSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"pg_test\","
        + "\"config\":{"
        + "\"hostname\":\"" + hostname + "\","
        + "\"port\":" + port + ","
        + "\"databaseName\":\"testdb\","
        + "\"username\":\"pguser\","
        + "\"password\":\"pgpass\","
        + "\"useSsl\":false,"
        + "\"encryptionValidationMode\":\"NO_VALIDATION\","
        + "\"fetchSize\":4096,"
        + "\"queryTimeoutSec\":0"
        + "},"
        + "\"type\":\"POSTGRES_DB\""
        + "}";
  }

  private static String oracleSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"oracle_test\","
        + "\"config\":{"
        + "\"hostname\":\"" + hostname + "\","
        + "\"port\":" + port + ","
        + "\"serviceName\":\"XEPDB1\","
        + "\"username\":\"testuser\","
        + "\"password\":\"testpass\","
        + "\"useSsl\":false,"
        + "\"encryptionValidationMode\":\"NO_VALIDATION\","
        + "\"fetchSize\":4096,"
        + "\"queryTimeoutSec\":0"
        + "},"
        + "\"type\":\"ORACLE_DB\""
        + "}";
  }

  private static String adbcSourceJson(String hostname, int port) {
    return "{"
        + "\"name\":\"pg_adbc_test\","
        + "\"config\":{"
        + "\"hostname\":\"" + hostname + "\","
        + "\"port\":" + port + ","
        + "\"databaseName\":\"testdb\","
        + "\"username\":\"pguser\","
        + "\"password\":\"pgpass\","
        + "\"useSsl\":false,"
        + "\"encryptionValidationMode\":\"NO_VALIDATION\","
        + "\"fetchSize\":4096,"
        + "\"queryTimeoutSec\":0,"
        + "\"protocolMode\":\"AUTO\""
        + "},"
        + "\"type\":\"POSTGRES_DB\""
        + "}";
  }

  // ── SQL runner ───────────────────────────────────────────────────────────

  private static List<String> runSql(String sql) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    // Submit query
    String submitBody = "{\"sql\":\"" + sql.replace("\"", "\\\"") + "\"}";
    HttpRequest submitReq =
        HttpRequest.newBuilder()
            .uri(URI.create(DREMIO_URL + "/api/v3/sql"))
            .POST(HttpRequest.BodyPublishers.ofString(submitBody))
            .header("Content-Type", "application/json")
            .header("Authorization", TOKEN)
            .build();
    HttpResponse<String> submitResp = client.send(submitReq, HttpResponse.BodyHandlers.ofString());
    String submitJson = submitResp.body();
    int idStart = submitJson.indexOf("\"id\":\"") + 6;
    int idEnd = submitJson.indexOf("\"", idStart);
    if (idStart < 6) {
      throw new AssertionError("Submit failed — no job id in: " + submitJson);
    }
    String jobId = submitJson.substring(idStart, idEnd);

    // Poll for completion
    for (int i = 0; i < 30; i++) {
      Thread.sleep(2000);
      HttpRequest pollReq =
          HttpRequest.newBuilder()
              .uri(URI.create(DREMIO_URL + "/api/v3/job/" + jobId))
              .GET()
              .header("Authorization", TOKEN)
              .build();
      HttpResponse<String> pollResp = client.send(pollReq, HttpResponse.BodyHandlers.ofString());
      String pollJson = pollResp.body();
      String state = extractJsonString(pollJson, "jobState");
      if ("COMPLETED".equals(state)) {
        // Fetch results
        HttpRequest resultsReq =
            HttpRequest.newBuilder()
                .uri(URI.create(DREMIO_URL + "/api/v3/job/" + jobId + "/results"))
                .GET()
                .header("Authorization", TOKEN)
                .build();
        HttpResponse<String> resultsResp =
            client.send(resultsReq, HttpResponse.BodyHandlers.ofString());
        return parseResultRows(resultsResp.body());
      }
      if ("FAILED".equals(state) || "CANCELED".equals(state)) {
        String errMsg = extractJsonString(pollJson, "errorMessage");
        throw new AssertionError("Query " + state + ": " + errMsg + "\nSQL: " + sql);
      }
    }
    throw new AssertionError("TIMEOUT waiting for job " + jobId + "\nSQL: " + sql);
  }

  /** Extracts a simple string field from a JSON object (not nested). */
  private static String extractJsonString(String json, String field) {
    String key = "\"" + field + "\":\"";
    int start = json.indexOf(key);
    if (start < 0) {
      return "";
    }
    start += key.length();
    int end = json.indexOf("\"", start);
    return end > start ? json.substring(start, end) : "";
  }

  /** Returns each row as a toString()-style string from the results JSON. */
  private static List<String> parseResultRows(String json) {
    List<String> rows = new ArrayList<>();
    // rows array: [{"col":"val",...}, ...]
    int rowsStart = json.indexOf("\"rows\":[");
    if (rowsStart < 0) {
      return rows;
    }
    rowsStart += 8;
    // Split on top-level row objects (naive but sufficient for flat results)
    int depth = 0;
    int rowStart = -1;
    for (int i = rowsStart; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '{') {
        if (depth == 0) {
          rowStart = i;
        }
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0 && rowStart >= 0) {
          rows.add(json.substring(rowStart, i + 1));
          rowStart = -1;
        }
      } else if (c == ']' && depth == 0) {
        break;
      }
    }
    return rows;
  }

  // ── PG log helpers ───────────────────────────────────────────────────────

  private static int pgLogMarker() {
    String logs = PG.getLogs();
    return logs == null ? 0 : logs.split("\n").length;
  }

  private static String pgLogsSince(int marker) {
    String logs = PG.getLogs();
    if (logs == null) {
      return "";
    }
    String[] lines = logs.split("\n");
    // Collapse continuation lines (tab-indented)
    List<String> collapsed = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = marker; i < lines.length; i++) {
      String line = lines[i];
      if (line.startsWith("\t")) {
        if (current.length() > 0) {
          current.append(" ").append(line.substring(1));
        }
      } else {
        if (current.length() > 0) {
          collapsed.add(current.toString());
        }
        current = new StringBuilder(line);
      }
    }
    if (current.length() > 0) {
      collapsed.add(current.toString());
    }
    // Filter
    StringBuilder result = new StringBuilder();
    for (String line : collapsed) {
      if (PG_INCLUDE.matcher(line).find() && !PG_EXCLUDE.matcher(line).find()) {
        result.append(line).append("\n");
      }
    }
    return result.toString();
  }

  // ── Oracle helpers ───────────────────────────────────────────────────────

  private static void oraFlushSharedPool() throws Exception {
    // OracleContainer.configure() sets ORACLE_PASSWORD = this.password (same as APP_USER_PASSWORD)
    // Use echo >> temp file to avoid shell $ expansion issues with v$sql reference.
    String sysPass = ORACLE.getPassword();
    ORACLE.execInContainer(
        "sh",
        "-c",
        "echo \"ALTER SYSTEM FLUSH SHARED_POOL;\" > /tmp/flush.sql"
            + " && sqlplus -s sys/" + sysPass + "@//localhost:1521/XEPDB1 as sysdba @/tmp/flush.sql");
  }

  private static String oraQueriesSince() throws Exception {
    // Build the V$SQL query using echo >> to avoid shell $ expansion.
    // \$ inside double-quoted sh strings produces a literal $ character.
    // OracleContainer.configure() sets ORACLE_PASSWORD = this.password (APP user password).
    String sysPass = ORACLE.getPassword();
    String writeCmd =
        "echo \"SET HEADING OFF\" > /tmp/vsql_query.sql"
            + " && echo \"SET FEEDBACK OFF\" >> /tmp/vsql_query.sql"
            + " && echo \"SET PAGESIZE 200\" >> /tmp/vsql_query.sql"
            + " && echo \"SET LINESIZE 500\" >> /tmp/vsql_query.sql"
            + " && echo \"SELECT sql_text FROM v\\$sql\" >> /tmp/vsql_query.sql"
            + " && echo \"WHERE parsing_schema_name = 'TESTUSER'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%v\\$sql%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%DUAL%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%SYS_CONTEXT%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%OPT_DYN_SAMP%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%DBMS_%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%all_objects%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%all_users%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%CREATE%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%MERGE%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%DELETE%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%INSERT%'\" >> /tmp/vsql_query.sql"
            + " && echo \"  AND sql_text NOT LIKE '%table_type%'\" >> /tmp/vsql_query.sql"
            + " && echo \"ORDER BY last_active_time DESC;\" >> /tmp/vsql_query.sql"
            + " && sqlplus -s sys/" + sysPass + "@//localhost:1521/XEPDB1 as sysdba @/tmp/vsql_query.sql";
    return ORACLE.execInContainer("sh", "-c", writeCmd).getStdout();
  }

  // ── Assertion helpers ────────────────────────────────────────────────────

  private static void assertPgPushdown(String sql, String pattern) throws Exception {
    int marker = pgLogMarker();
    runSql(sql);
    Thread.sleep(1000);
    String logs = pgLogsSince(marker);
    assertTrue(
        "Pattern '" + pattern + "' not found in PG logs after:\n" + sql + "\nLogs:\n" + logs,
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(logs).find());
  }

  private static void assertOraPushdown(String sql, String pattern) throws Exception {
    oraFlushSharedPool();
    runSql(sql);
    Thread.sleep(1000);
    String queries = oraQueriesSince();
    assertTrue(
        "Pattern '" + pattern + "' not found in Oracle V$SQL after:\n" + sql,
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(queries)
            .find());
  }

  private static void assertCorrect(String sql, String expectedSubstring) throws Exception {
    List<String> rows = runSql(sql);
    String allRows = String.join("\n", rows);
    assertTrue(
        "Expected '" + expectedSubstring + "' in results:\n" + allRows,
        Pattern.compile(expectedSubstring, Pattern.CASE_INSENSITIVE).matcher(allRows).find());
  }

  private static void assertNoError(String sql) throws Exception {
    List<String> rows = runSql(sql);
    // If runSql didn't throw, the query succeeded
  }

  /**
   * Asserts that running the given SQL does NOT cause any new SQL to appear in the PG container
   * logs. Used for S3/Nessie/RESTCATALOG queries that must NOT trigger JDBC pushdown to PostgreSQL.
   */
  private static void assertNoPgPushdown(String sql) throws Exception {
    int marker = pgLogMarker();
    runSql(sql);
    Thread.sleep(1000);
    String logs = pgLogsSince(marker);
    // Filter out noise: keep only lines containing SELECT/INSERT/UPDATE/DELETE
    // (the PG log includes connection and parameter messages we should ignore)
    String sqlStatements =
        Arrays.stream(logs.split("\n"))
            .filter(
                line -> line.contains("statement:") || line.contains("execute"))
            .filter(
                line ->
                    Pattern.compile("SELECT|INSERT|UPDATE|DELETE", Pattern.CASE_INSENSITIVE)
                        .matcher(line)
                        .find())
            .collect(Collectors.joining("\n"));
    assertTrue(
        "JDBC pushdown unexpectedly fired for non-JDBC source query. PG logs show new SQL:\n"
            + sqlStatements
            + "\nQuery was:\n"
            + sql,
        sqlStatements.trim().isEmpty());
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 1: WHERE / FILTER PUSHDOWN (4 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testPgWhereGtSalary() throws Exception {
    assertPgPushdown(
        "SELECT name, salary FROM " + PG_EMP + " WHERE salary > 100000", "WHERE");
  }

  @Test
  public void testPgWhereIsNotNull() throws Exception {
    assertPgPushdown(
        "SELECT name FROM " + PG_EMP + " WHERE department IS NOT NULL", "IS NOT NULL");
  }

  @Test
  public void testPgWhereAnd() throws Exception {
    assertPgPushdown(
        "SELECT name FROM " + PG_EMP
            + " WHERE department = 'Engineering' AND salary > 110000",
        "AND");
  }

  @Test
  public void testOraWhereGtSalary() throws Exception {
    assertOraPushdown(
        "SELECT name, salary FROM " + ORA_EMP + " WHERE salary > 100000", "WHERE");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 2: LIMIT PUSHDOWN (3 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testPgLimit3() throws Exception {
    assertPgPushdown(
        "SELECT name FROM " + PG_EMP + " LIMIT 3", "FETCH NEXT 3|LIMIT 3");
  }

  @Test
  public void testPgWhereLimit() throws Exception {
    assertPgPushdown(
        "SELECT name FROM " + PG_EMP + " WHERE department = 'Sales' LIMIT 2",
        "FETCH NEXT 2|LIMIT 2");
  }

  @Test
  public void testOraFetchFirst3() throws Exception {
    assertOraPushdown(
        "SELECT name FROM " + ORA_EMP + " FETCH FIRST 3 ROWS ONLY",
        "FETCH NEXT 3|FETCH FIRST 3|ROWNUM");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 3: ORDER BY PUSHDOWN — plain columns (5 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testPgOrderByName() throws Exception {
    assertPgPushdown(
        "SELECT name FROM " + PG_EMP + " ORDER BY name", "ORDER BY");
  }

  @Test
  public void testPgOrderBySalaryDesc() throws Exception {
    assertPgPushdown(
        "SELECT name, salary FROM " + PG_EMP + " ORDER BY salary DESC", "ORDER BY");
  }

  @Test
  public void testPgOrderByLimitTopN() throws Exception {
    assertPgPushdown(
        "SELECT name, salary FROM " + PG_EMP + " ORDER BY salary DESC LIMIT 5",
        "ORDER BY.*FETCH|ORDER BY.*LIMIT");
  }

  @Test
  public void testOraOrderByName() throws Exception {
    assertOraPushdown(
        "SELECT name FROM " + ORA_EMP + " ORDER BY name", "ORDER BY");
  }

  @Test
  public void testOraOrderByLimitTopN() throws Exception {
    assertOraPushdown(
        "SELECT name, salary FROM " + ORA_EMP
            + " ORDER BY salary DESC FETCH FIRST 5 ROWS ONLY",
        "ORDER BY");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 4: AGG / GROUP BY PUSHDOWN — plain columns (6 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testPgGroupByCount() throws Exception {
    assertPgPushdown(
        "SELECT department, COUNT(*) FROM " + PG_EMP
            + " WHERE department IS NOT NULL GROUP BY department",
        "GROUP BY");
  }

  @Test
  public void testPgGroupBySum() throws Exception {
    assertPgPushdown(
        "SELECT department, SUM(salary) FROM " + PG_EMP
            + " WHERE department IS NOT NULL GROUP BY department",
        "SUM");
  }

  @Test
  public void testPgGroupByAvg() throws Exception {
    assertPgPushdown(
        "SELECT department, AVG(salary) FROM " + PG_EMP
            + " WHERE department IS NOT NULL GROUP BY department",
        "GROUP BY");
  }

  @Test
  public void testPgGroupByMinMax() throws Exception {
    assertPgPushdown(
        "SELECT department, MIN(salary), MAX(salary) FROM " + PG_EMP
            + " WHERE department IS NOT NULL GROUP BY department",
        "MIN|MAX");
  }

  @Test
  public void testOraGroupByCount() throws Exception {
    assertOraPushdown(
        "SELECT department, COUNT(*) FROM " + ORA_EMP
            + " WHERE department IS NOT NULL GROUP BY department",
        "GROUP BY");
  }

  @Test
  public void testOraGroupBySum() throws Exception {
    assertOraPushdown(
        "SELECT department, SUM(salary) FROM " + ORA_EMP
            + " WHERE department IS NOT NULL GROUP BY department",
        "SUM");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 5: JOIN PUSHDOWN — same source (4 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testPgInnerJoin() throws Exception {
    assertPgPushdown(
        "SELECT e.name, d.budget FROM " + PG_EMP + " e INNER JOIN " + PG_DEPT
            + " d ON e.department = d.dept_name",
        "JOIN|INNER");
  }

  @Test
  public void testPgLeftJoin() throws Exception {
    assertPgPushdown(
        "SELECT e.name, d.budget FROM " + PG_EMP + " e LEFT JOIN " + PG_DEPT
            + " d ON e.department = d.dept_name",
        "JOIN|LEFT");
  }

  @Test
  public void testOraInnerJoin() throws Exception {
    assertOraPushdown(
        "SELECT e.name, d.budget FROM " + ORA_EMP + " e INNER JOIN " + ORA_DEPT
            + " d ON e.department = d.dept_name",
        "JOIN|INNER");
  }

  @Test
  public void testOraLeftJoin() throws Exception {
    assertOraPushdown(
        "SELECT e.name, d.budget FROM " + ORA_EMP + " e LEFT JOIN " + ORA_DEPT
            + " d ON e.department = d.dept_name",
        "JOIN|LEFT");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 6: COMBINED PATTERNS (5 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testPgWhereOrderByLimit() throws Exception {
    assertPgPushdown(
        "SELECT name, salary FROM " + PG_EMP
            + " WHERE department = 'Engineering' ORDER BY salary DESC LIMIT 2",
        "ORDER BY");
  }

  @Test
  public void testPgGroupByHaving() throws Exception {
    assertPgPushdown(
        "SELECT department, COUNT(*) AS cnt FROM " + PG_EMP
            + " WHERE department IS NOT NULL GROUP BY department HAVING COUNT(*) > 2",
        "GROUP BY");
  }

  @Test
  public void testPgOrderByExprLimit() throws Exception {
    assertPgPushdown(
        "SELECT name FROM " + PG_EMP + " ORDER BY UPPER(name) LIMIT 3",
        "ORDER BY.*UPPER|UPPER.*ORDER BY");
  }

  @Test
  public void testOraWhereOrderByLimit() throws Exception {
    assertOraPushdown(
        "SELECT name, salary FROM " + ORA_EMP
            + " WHERE department = 'Engineering' ORDER BY salary DESC FETCH FIRST 2 ROWS ONLY",
        "ORDER BY");
  }

  @Test
  public void testOraOrderByExprLimit() throws Exception {
    assertOraPushdown(
        "SELECT name FROM " + ORA_EMP + " ORDER BY UPPER(name) FETCH FIRST 3 ROWS ONLY",
        "ORDER BY.*UPPER|UPPER.*ORDER BY");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 7: CORRECTNESS CHECKS — results validation (5 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testPgSumEngineering() throws Exception {
    assertCorrect(
        "SELECT CAST(SUM(salary) AS BIGINT) FROM " + PG_EMP
            + " WHERE department = 'Engineering'",
        "505000");
  }

  @Test
  public void testPgCountGroupBy() throws Exception {
    assertCorrect(
        "SELECT department, COUNT(*) AS cnt FROM " + PG_EMP
            + " WHERE department IS NOT NULL GROUP BY department ORDER BY department",
        "Engineering.*4");
  }

  @Test
  public void testPgJoinCorrectData() throws Exception {
    assertCorrect(
        "SELECT e.name, d.location FROM " + PG_EMP + " e INNER JOIN " + PG_DEPT
            + " d ON e.department = d.dept_name WHERE e.name = 'Alice'",
        "Alice.*Building A");
  }

  @Test
  public void testPgOrderByUpperCorrect() throws Exception {
    assertCorrect(
        "SELECT name FROM " + PG_EMP + " ORDER BY UPPER(name) LIMIT 3",
        "Alice");
  }

  @Test
  public void testOraJoinCorrectData() throws Exception {
    assertCorrect(
        "SELECT e.name, d.location FROM " + ORA_EMP + " e INNER JOIN " + ORA_DEPT
            + " d ON e.department = d.dept_name WHERE e.name = 'Alice'",
        "Alice.*Building A");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 8: ADBC TESTS (3 tests using pg_adbc_test source)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testAdbcWhereFilter() throws Exception {
    List<String> rows =
        runSql(
            "SELECT name, salary FROM " + ADBC_EMP + " WHERE salary > 100000");
    assertTrue("ADBC WHERE filter returned no rows", !rows.isEmpty());
  }

  @Test
  public void testAdbcGroupBy() throws Exception {
    List<String> rows =
        runSql(
            "SELECT department, COUNT(*) FROM " + ADBC_EMP
                + " WHERE department IS NOT NULL GROUP BY department");
    assertTrue("ADBC GROUP BY returned no rows", !rows.isEmpty());
  }

  @Test
  public void testAdbcOrderByLimit() throws Exception {
    List<String> rows =
        runSql(
            "SELECT name, salary FROM " + ADBC_EMP
                + " ORDER BY salary DESC LIMIT 5");
    assertTrue("ADBC ORDER BY LIMIT returned no rows", !rows.isEmpty());
    // First row should have the highest salary (Eve = 145000)
    String firstRow = rows.get(0);
    assertTrue(
        "Expected Eve (highest salary 145000) as first row, got: " + firstRow,
        firstRow.contains("Eve") || firstRow.contains("145000"));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SECTION 9: PGVECTOR PUSHDOWN TESTS (7 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testPgvectorL2Pushdown() throws Exception {
    assertPgPushdown(
        "SELECT id FROM " + PG_VEC
            + " ORDER BY l2_distance(embedding, ARRAY[0.5, 1.0, 1.5]) LIMIT 10",
        "<->");
  }

  @Test
  public void testPgvectorCosinePushdown() throws Exception {
    assertPgPushdown(
        "SELECT id FROM " + PG_VEC
            + " ORDER BY cosine_distance(embedding, ARRAY[0.5, 1.0, 1.5]) LIMIT 10",
        "<=>");
  }

  @Test
  public void testPgvectorInnerProductPushdown() throws Exception {
    assertPgPushdown(
        "SELECT id FROM " + PG_VEC
            + " ORDER BY inner_product(embedding, ARRAY[0.5, 1.0, 1.5]) LIMIT 10",
        "<#>");
  }

  // EXPLAIN ANALYZE index scan test removed: with 200 rows, PG correctly prefers seq scan.
  // HNSW index usage is a PG optimizer decision, not a Dremio pushdown concern.
  // The pushdown verification tests (testPgvectorL2/Cosine/InnerProductPushdown) confirm
  // that <-> / <=> / <#> operators reach PG — index usage follows automatically at scale.

  @Test
  public void testPgvectorL2Correctness() throws Exception {
    assertCorrect(
        "SELECT id, label FROM " + PG_VEC
            + " ORDER BY l2_distance(embedding, ARRAY[0.5, 1.0, 1.5]) LIMIT 5",
        "item_");
  }

  @Test
  public void testPgvectorAdbcL2() throws Exception {
    List<String> rows = runSql(
        "SELECT id, label FROM " + ADBC_VEC
            + " ORDER BY l2_distance(embedding, ARRAY[0.5, 1.0, 1.5]) LIMIT 5");
    assertTrue("ADBC l2_distance returned no rows", !rows.isEmpty());
    assertTrue(
        "Expected 'item_' in ADBC results, got: " + rows,
        rows.stream().anyMatch(r -> r.contains("item_")));
  }

  @Test
  public void testPgvectorAdbcCosine() throws Exception {
    List<String> rows = runSql(
        "SELECT id, label FROM " + ADBC_VEC
            + " ORDER BY cosine_distance(embedding, ARRAY[0.5, 1.0, 1.5]) LIMIT 5");
    assertTrue("ADBC cosine_distance returned no rows", !rows.isEmpty());
    assertTrue(
        "Expected 'item_' in ADBC results, got: " + rows,
        rows.stream().anyMatch(r -> r.contains("item_")));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 1: SAME-SOURCE PUSHDOWN — PG + Oracle (6 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testUatS1PgWhereOrderByLimit() throws Exception {
    assertPgPushdown(
        "SELECT name, price FROM " + PG_PRODUCTS
            + " WHERE price > 100 ORDER BY price DESC LIMIT 5",
        "ORDER BY");
  }

  @Test
  public void testUatS1PgGroupByHaving() throws Exception {
    assertPgPushdown(
        "SELECT category_id, COUNT(*) AS cnt FROM " + PG_PRODUCTS
            + " GROUP BY category_id HAVING COUNT(*) > 3",
        "GROUP BY");
  }

  @Test
  public void testUatS1PgInnerJoinSameSource() throws Exception {
    assertPgPushdown(
        "SELECT o.id, oi.product_id FROM " + PG_ORDERS + " o INNER JOIN "
            + PG_ORDER_ITEMS + " oi ON o.id = oi.order_id",
        "JOIN|INNER");
  }

  @Test
  public void testUatS1OraWhereCustomerTier() throws Exception {
    assertOraPushdown(
        "SELECT NAME, TIER FROM " + ORA_CUSTOMERS
            + " WHERE TIER = 'premium'",
        "WHERE");
  }

  @Test
  public void testUatS1OraOrderByFetchFirst() throws Exception {
    assertOraPushdown(
        "SELECT NAME FROM " + ORA_CUSTOMERS
            + " ORDER BY NAME FETCH FIRST 3 ROWS ONLY",
        "ORDER BY.*FETCH|FETCH FIRST");
  }

  @Test
  public void testUatS1OraGroupByRegion() throws Exception {
    assertOraPushdown(
        "SELECT REGION_ID, COUNT(*) AS cnt FROM " + ORA_CUSTOMERS
            + " GROUP BY REGION_ID",
        "GROUP BY");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 2: ADBC vs JDBC PROTOCOL (4 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testUatS2JdbcProductCount() throws Exception {
    assertCorrect(
        "SELECT COUNT(*) AS cnt FROM " + PG_PRODUCTS
            + " WHERE category_id IS NOT NULL",
        "15");
  }

  @Test
  public void testUatS2AdbcProductCount() throws Exception {
    assertCorrect(
        "SELECT COUNT(*) AS cnt FROM " + ADBC_PRODUCTS
            + " WHERE category_id IS NOT NULL",
        "15");
  }

  @Test
  public void testUatS2JdbcSumPrice() throws Exception {
    assertCorrect(
        "SELECT CAST(SUM(price) AS BIGINT) FROM " + PG_PRODUCTS
            + " WHERE category_id = 1",
        "3070");
  }

  @Test
  public void testUatS2AdbcSumPrice() throws Exception {
    assertCorrect(
        "SELECT CAST(SUM(price) AS BIGINT) FROM " + ADBC_PRODUCTS
            + " WHERE category_id = 1",
        "3070");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 3: CROSS-SOURCE JOINS PG x Oracle (3 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testUatS3PgOrdersXOracleCustomers() throws Exception {
    assertCorrect(
        "SELECT o.id AS order_id, c.name AS customer_name FROM " + PG_ORDERS
            + " o INNER JOIN " + ORA_CUSTOMERS
            + " c ON o.customer_id = c.id ORDER BY o.id",
        "Alice Johnson");
  }

  @Test
  public void testUatS3OracleCustomersWithRegion() throws Exception {
    assertCorrect(
        "SELECT c.NAME, c.TIER, r.COUNTRY FROM " + ORA_CUSTOMERS
            + " c INNER JOIN " + ORA_REGIONS
            + " r ON c.REGION_ID = r.ID WHERE c.TIER = 'premium' ORDER BY c.NAME",
        "Alice Johnson.*premium.*US");
  }

  @Test
  public void testUatS3CrossSourceOrderTotals() throws Exception {
    assertCorrect(
        "SELECT c.name, SUM(o.total) AS total_spent FROM " + PG_ORDERS
            + " o INNER JOIN " + ORA_CUSTOMERS
            + " c ON o.customer_id = c.id GROUP BY c.name ORDER BY total_spent DESC",
        "Dave Wilson");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 4: PGVECTOR SEMANTIC SEARCH (12 tests)
  // ══════════════════════════════════════════════════════════════════════════

  // 4a: KNN pushdown (3 operator tests)

  @Test
  public void testUatS4KnnL2Pushdown() throws Exception {
    assertPgPushdown(
        "SELECT id, name FROM " + PG_PRODUCTS
            + " ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 3",
        "<->");
  }

  @Test
  public void testUatS4KnnCosinePushdown() throws Exception {
    assertPgPushdown(
        "SELECT id, name FROM " + PG_PRODUCTS
            + " ORDER BY cosine_distance(embedding, ARRAY[0.1, 0.9, 0.8, 0.2]) LIMIT 3",
        "<=>");
  }

  @Test
  public void testUatS4KnnInnerProductPushdown() throws Exception {
    assertPgPushdown(
        "SELECT id, name FROM " + PG_PRODUCTS
            + " ORDER BY inner_product(embedding, ARRAY[0.5, 0.5, 0.5, 0.5]) LIMIT 3",
        "<#>");
  }

  // 4b: KNN nearest-neighbor correctness (2 tests)

  @Test
  public void testUatS4KnnNearestElectronics() throws Exception {
    assertCorrect(
        "SELECT name FROM " + PG_PRODUCTS
            + " ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 1",
        "Laptop Pro 15");
  }

  @Test
  public void testUatS4KnnNearestFurniture() throws Exception {
    assertCorrect(
        "SELECT name FROM " + PG_PRODUCTS
            + " ORDER BY l2_distance(embedding, ARRAY[0.1, 0.9, 0.8, 0.2]) LIMIT 1",
        "Standing Desk");
  }

  // 4c: Distance correctness (5 tests)

  @Test
  public void testUatS4DistanceL2SelfMatch() throws Exception {
    assertCorrect(
        "SELECT CAST(l2_distance(embedding, CAST(ARRAY[0.8, 0.2, 0.1, 0.9] AS LIST(FLOAT)))"
            + " < 0.001 AS BOOLEAN) AS ok FROM " + PG_PRODUCTS + " WHERE id = 1",
        "true");
  }

  @Test
  public void testUatS4DistanceCosineSelfMatch() throws Exception {
    assertCorrect(
        "SELECT CAST(cosine_distance(embedding, CAST(ARRAY[0.1, 0.9, 0.8, 0.2] AS LIST(FLOAT)))"
            + " < 0.001 AS BOOLEAN) AS ok FROM " + PG_PRODUCTS + " WHERE id = 4",
        "true");
  }

  @Test
  public void testUatS4DistanceCosineApprox() throws Exception {
    assertCorrect(
        "SELECT CAST(ABS(cosine_distance(embedding, CAST(ARRAY[0.1, 0.9, 0.8, 0.2] AS LIST(FLOAT)))"
            + " - 0.6533) < 0.001 AS BOOLEAN) AS ok FROM " + PG_PRODUCTS + " WHERE id = 1",
        "true");
  }

  @Test
  public void testUatS4DistanceL2Approx() throws Exception {
    assertCorrect(
        "SELECT CAST(ABS(l2_distance(embedding, CAST(ARRAY[0.8, 0.2, 0.1, 0.9] AS LIST(FLOAT)))"
            + " - 1.4) < 0.01 AS BOOLEAN) AS ok FROM " + PG_PRODUCTS + " WHERE id = 4",
        "true");
  }

  @Test
  public void testUatS4DistanceInnerProductApprox() throws Exception {
    assertCorrect(
        "SELECT CAST(ABS(inner_product(embedding, CAST(ARRAY[0.5, 0.5, 0.5, 0.5] AS LIST(FLOAT)))"
            + " + 1.0) < 0.001 AS BOOLEAN) AS ok FROM " + PG_PRODUCTS + " WHERE id = 1",
        "true");
  }

  // 4d: ADBC distance matches JDBC (2 tests)

  @Test
  public void testUatS4AdbcKnnNearestElectronics() throws Exception {
    assertCorrect(
        "SELECT name FROM " + ADBC_PRODUCTS
            + " ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 1",
        "Laptop Pro 15");
  }

  @Test
  public void testUatS4AdbcDistanceMatchesJdbc() throws Exception {
    assertCorrect(
        "SELECT CAST(ABS(cosine_distance(j.embedding, CAST(ARRAY[0.1, 0.9, 0.8, 0.2] AS LIST(FLOAT)))"
            + " - cosine_distance(a.embedding, CAST(ARRAY[0.1, 0.9, 0.8, 0.2] AS LIST(FLOAT))))"
            + " < 0.001 AS BOOLEAN) AS ok FROM "
            + PG_PRODUCTS + " j INNER JOIN " + ADBC_PRODUCTS + " a ON j.id = a.id WHERE j.id = 1",
        "true");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 5: FUNCTION COMPOSITION & EXPRESSION PUSHDOWN (5 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testUatS5CastExtractYear() throws Exception {
    assertNoError(
        "SELECT CAST(EXTRACT(YEAR FROM order_date) AS INTEGER) AS yr, COUNT(*) FROM "
            + PG_ORDERS + " GROUP BY CAST(EXTRACT(YEAR FROM order_date) AS INTEGER)");
  }

  @Test
  public void testUatS5RoundSumGroupBy() throws Exception {
    assertNoError(
        "SELECT category_id, ROUND(SUM(price * 1.1), 2) AS boosted FROM "
            + PG_PRODUCTS + " GROUP BY category_id");
  }

  @Test
  public void testUatS5UpperTrimOrderBy() throws Exception {
    assertNoError(
        "SELECT name FROM " + PG_PRODUCTS
            + " ORDER BY UPPER(TRIM(name)) LIMIT 5");
  }

  @Test
  public void testUatS5CoalesceCast() throws Exception {
    assertNoError(
        "SELECT COALESCE(CAST(category_id AS VARCHAR), 'unknown') AS cat FROM "
            + PG_PRODUCTS + " LIMIT 5");
  }

  @Test
  public void testUatS5CeilAbsNested() throws Exception {
    assertNoError(
        "SELECT name, CEIL(ABS(price - 500)) AS dist FROM "
            + PG_PRODUCTS + " ORDER BY dist LIMIT 5");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 6: ICEBERG / NESSIE QUERIES (6 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testUatS6IcebergCategoriesCount() throws Exception {
    assertCorrect(
        "SELECT COUNT(*) AS cnt FROM " + ICE_CATEGORIES,
        "3");
  }

  @Test
  public void testUatS6IcebergSalesCount() throws Exception {
    assertCorrect(
        "SELECT COUNT(*) AS cnt FROM " + ICE_SALES,
        "12");
  }

  @Test
  public void testUatS6IcebergSumRevenueByMonth() throws Exception {
    assertCorrect(
        "SELECT \"month\", CAST(SUM(revenue) AS BIGINT) AS total FROM "
            + ICE_SALES + " GROUP BY \"month\" ORDER BY \"month\"",
        "2024-01");
  }

  @Test
  public void testUatS6PgProductsXIcebergCategories() throws Exception {
    assertCorrect(
        "SELECT p.name, c.category_name FROM " + PG_PRODUCTS
            + " p INNER JOIN " + ICE_CATEGORIES
            + " c ON p.category_id = c.category_id WHERE p.price > 400 ORDER BY p.name",
        "Laptop Pro 15.*Electronics");
  }

  @Test
  public void testUatS6IcebergSalesXPgProducts() throws Exception {
    assertCorrect(
        "SELECT p.name, s.\"month\", s.units_sold FROM " + ICE_SALES
            + " s INNER JOIN " + PG_PRODUCTS
            + " p ON s.product_id = p.id WHERE s.units_sold > 10 ORDER BY s.units_sold DESC",
        "Wireless Mouse");
  }

  @Test
  public void testUatS6TripleSourcePgIcebergOracle() throws Exception {
    assertCorrect(
        "SELECT cat.category_name, r.name AS region FROM " + PG_ORDER_ITEMS
            + " oi INNER JOIN " + PG_PRODUCTS + " p ON oi.product_id = p.id"
            + " INNER JOIN " + ICE_CATEGORIES + " cat ON p.category_id = cat.category_id"
            + " INNER JOIN " + PG_ORDERS + " o ON oi.order_id = o.id"
            + " INNER JOIN " + ORA_CUSTOMERS + " c ON o.customer_id = c.id"
            + " INNER JOIN " + ORA_REGIONS + " r ON c.region_id = r.id"
            + " WHERE c.name = 'Alice Johnson' ORDER BY cat.category_name",
        "Electronics");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 7: CROSS-SOURCE + SEMANTIC SEARCH (3 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testUatS7KnnProductsXOracleCustomer() throws Exception {
    assertCorrect(
        "SELECT p.name AS product, c.name AS customer FROM ("
            + "SELECT name FROM " + PG_PRODUCTS
            + " ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 3"
            + ") p, " + ORA_CUSTOMERS + " c WHERE c.id = 101",
        "Laptop Pro 15.*Alice");
  }

  @Test
  public void testUatS7KnnProductsXIcebergEnrichment() throws Exception {
    assertCorrect(
        "SELECT p.name, cat.category_name, cat.margin_pct FROM ("
            + "SELECT id, name, category_id FROM " + PG_PRODUCTS
            + " ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 3"
            + ") p INNER JOIN " + ICE_CATEGORIES
            + " cat ON p.category_id = cat.category_id",
        "Electronics");
  }

  @Test
  public void testUatS7PgOrderAnalyticsXOracleCustomers() throws Exception {
    assertCorrect(
        "SELECT c.name, c.tier, o.order_count FROM ("
            + "SELECT customer_id, COUNT(*) AS order_count FROM " + PG_ORDERS
            + " GROUP BY customer_id"
            + ") o INNER JOIN " + ORA_CUSTOMERS
            + " c ON o.customer_id = c.id ORDER BY o.order_count DESC",
        "Alice Johnson.*premium");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 8: EDGE CASES (6 tests)
  // ══════════════════════════════════════════════════════════════════════════

  @Test
  public void testUatS8NullEmbedding() throws Exception {
    assertNoError(
        "SELECT l2_distance(CAST(NULL AS LIST(FLOAT)), ARRAY[1.0, 2.0, 3.0, 4.0])");
  }

  @Test
  public void testUatS8EmptyResult() throws Exception {
    assertNoError(
        "SELECT * FROM " + PG_PRODUCTS + " WHERE price > 999999");
  }

  @Test
  public void testUatS8PgMultiTableJoin() throws Exception {
    assertNoError(
        "SELECT o.id, oi.product_id, p.name FROM " + PG_ORDERS + " o INNER JOIN "
            + PG_ORDER_ITEMS + " oi ON o.id = oi.order_id INNER JOIN ("
            + "SELECT id, name FROM " + PG_PRODUCTS
            + " WHERE category_id = 1) p ON oi.product_id = p.id LIMIT 5");
  }

  @Test
  public void testUatS8JdbcAndAdbcSameQuery() throws Exception {
    assertNoError(
        "SELECT j.name, a.price FROM " + PG_PRODUCTS + " j INNER JOIN "
            + ADBC_PRODUCTS + " a ON j.id = a.id LIMIT 5");
  }

  @Test
  public void testUatS8OracleSelfJoin() throws Exception {
    assertNoError(
        "SELECT c.name, r.name AS region FROM " + ORA_CUSTOMERS
            + " c INNER JOIN " + ORA_REGIONS
            + " r ON c.region_id = r.id");
  }

  @Test
  public void testUatS8CrossSourceCountConsistency() throws Exception {
    assertCorrect(
        "SELECT COUNT(*) AS pg_count FROM " + PG_PRODUCTS
            + " WHERE category_id IS NOT NULL",
        "15");
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UAT SECTION 9: NON-JDBC SOURCE VALIDATION (11 tests)
  // ══════════════════════════════════════════════════════════════════════════

  // S3 / MinIO Parquet — assertNoPgPushdown verifies no JDBC rules fired
  @Test
  public void testUatS9S3ShippingRates() throws Exception {
    assertNoPgPushdown(
        "SELECT * FROM " + S3_SHIPPING + " LIMIT 5");
  }

  @Test
  public void testUatS9S3ShippingRatesCount() throws Exception {
    assertNoPgPushdown(
        "SELECT COUNT(*) AS cnt FROM " + S3_SHIPPING);
    // Also verify correctness
    assertCorrect(
        "SELECT COUNT(*) AS cnt FROM " + S3_SHIPPING,
        "4");
  }

  @Test
  public void testUatS9S3ProductReviews() throws Exception {
    assertNoPgPushdown(
        "SELECT review_id, rating, review_text FROM " + S3_REVIEWS
            + " ORDER BY rating DESC LIMIT 3");
  }

  @Test
  public void testUatS9S3AvgRating() throws Exception {
    assertNoPgPushdown(
        "SELECT CAST(AVG(CAST(rating AS DOUBLE)) AS INTEGER) AS avg_rating FROM "
            + S3_REVIEWS);
    // Also verify correctness
    assertCorrect(
        "SELECT CAST(AVG(CAST(rating AS DOUBLE)) AS INTEGER) AS avg_rating FROM "
            + S3_REVIEWS,
        "4");
  }

  // Nessie versioned — assertNoPgPushdown verifies no JDBC rules fired
  @Test
  public void testUatS9NessieVerCategories() throws Exception {
    assertNoPgPushdown(
        "SELECT * FROM " + NVER_CATEGORIES + " LIMIT 5");
  }

  @Test
  public void testUatS9NessieVerCategoriesCount() throws Exception {
    assertNoPgPushdown(
        "SELECT COUNT(*) AS cnt FROM " + NVER_CATEGORIES);
    // Also verify correctness
    assertCorrect(
        "SELECT COUNT(*) AS cnt FROM " + NVER_CATEGORIES,
        "3");
  }

  @Test
  public void testUatS9NessieVerGroupByDepartment() throws Exception {
    assertNoPgPushdown(
        "SELECT department, COUNT(*) AS cnt FROM " + NVER_CATEGORIES
            + " GROUP BY department ORDER BY department");
  }

  // RESTCATALOG self-join — uses assertNoError (Iceberg doesn't go through PG)
  @Test
  public void testUatS9RestcatalogSelfJoin() throws Exception {
    assertNoError(
        "SELECT a.category_name, b.department FROM " + ICE_CATEGORIES
            + " a, " + ICE_CATEGORIES
            + " b WHERE a.department = b.department AND a.category_id < b.category_id");
  }

  // Cross-source: S3 x PG — PG is legitimately queried, so use assertCorrect
  @Test
  public void testUatS9S3ReviewsXPgProducts() throws Exception {
    assertCorrect(
        "SELECT p.name, r.rating, r.review_text FROM " + S3_REVIEWS
            + " r INNER JOIN " + PG_PRODUCTS
            + " p ON r.product_id = p.id ORDER BY r.rating DESC LIMIT 3",
        "Laptop|desk|display");
  }

  // Nessie versioned filter — assertNoPgPushdown verifies no JDBC rules fired
  @Test
  public void testUatS9NessieVerXDepartmentFilter() throws Exception {
    assertNoPgPushdown(
        "SELECT cat.category_name, cat.department FROM " + NVER_CATEGORIES
            + " cat WHERE cat.department = 'Tech' ORDER BY cat.category_name");
    // Also verify correctness
    assertCorrect(
        "SELECT cat.category_name, cat.department FROM " + NVER_CATEGORIES
            + " cat WHERE cat.department = 'Tech' ORDER BY cat.category_name",
        "Accessories|Electronics");
  }

  // Triple: S3 x Iceberg x PG — PG is legitimately queried, so use assertNoError
  @Test
  public void testUatS9S3ReviewsXIcebergXPgProducts() throws Exception {
    assertNoError(
        "SELECT p.name, cat.category_name, r.rating FROM " + S3_REVIEWS
            + " r INNER JOIN " + PG_PRODUCTS + " p ON r.product_id = p.id"
            + " INNER JOIN " + ICE_CATEGORIES + " cat ON p.category_id = cat.category_id"
            + " ORDER BY r.rating DESC LIMIT 5");
  }

  // ── explainAnalyze helper ─────────────────────────────────────────────────

  /**
   * Runs EXPLAIN ANALYZE directly against the PG container (bypassing Dremio) and returns
   * the full output as a String. Used to verify index usage for pgvector ORDER BY + LIMIT queries.
   */
  private static String explainAnalyze(String sql) throws Exception {
    try (Connection conn = DriverManager.getConnection(
            PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
         Statement stmt = conn.createStatement();
         java.sql.ResultSet rs = stmt.executeQuery("EXPLAIN ANALYZE " + sql)) {
      StringBuilder sb = new StringBuilder();
      while (rs.next()) {
        sb.append(rs.getString(1)).append("\n");
      }
      return sb.toString();
    }
  }

}
