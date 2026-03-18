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
import java.util.List;
import java.util.regex.Pattern;
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

  private static String TOKEN;
  private static String DREMIO_URL;

  // ── Source table identifiers ─────────────────────────────────────────────
  private static final String PG_EMP    = "pg_test.public.employees";
  private static final String PG_DEPT   = "pg_test.public.departments";
  private static final String ORA_EMP   = "oracle_test.TESTUSER.EMPLOYEES";
  private static final String ORA_DEPT  = "oracle_test.TESTUSER.DEPARTMENTS";
  private static final String ADBC_EMP  = "pg_adbc_test.public.employees";

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

    // Seed PostgreSQL
    seedPostgres();

    // Seed Oracle
    seedOracle();

    // Bootstrap Dremio admin user
    bootstrapAdmin(DREMIO_URL);

    // Get auth token
    TOKEN = getToken(DREMIO_URL);

    // Create sources
    createSource(DREMIO_URL, TOKEN, pgSourceJson("postgres", 5432));
    createSource(DREMIO_URL, TOKEN, oracleSourceJson("oracle", 1521));
    createSource(DREMIO_URL, TOKEN, adbcSourceJson("postgres", 5432));

    // Wait for metadata refresh
    Thread.sleep(15_000);
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

}
