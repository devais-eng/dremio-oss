#!/usr/bin/env bash
#
# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Smoke test: create PostgreSQL and Oracle sources in Dremio, seed data, run queries.
set -euo pipefail

DREMIO_URL="${DREMIO_URL:-http://localhost:9047}"
DREMIO_USER="${DREMIO_USER:-admin}"
DREMIO_PASS="${DREMIO_PASS:-Admin123!}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'
BLUE='\033[0;34m'
ok()   { echo -e "  ${GREEN}✔${NC} $*"; }
fail() { echo -e "  ${RED}✘${NC} $*"; FAILURES=$((FAILURES+1)); }
warn() { echo -e "  ${YELLOW}⚠${NC} $*"; }
info() { echo -e "  ${BLUE}ℹ${NC} $*"; }
FAILURES=0

# ── 1. Wait for Dremio ────────────────────────────────────────────────
echo "Waiting for Dremio to be ready..."
for i in $(seq 1 60); do
  if curl -sf "$DREMIO_URL" >/dev/null 2>&1; then
    ok "Dremio is up"
    break
  fi
  if [ "$i" -eq 60 ]; then
    fail "Dremio did not start within 120s"
    exit 1
  fi
  sleep 2
done

# ── 2. Bootstrap first user ──────────────────────────────────────────
echo "Bootstrapping first user..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X PUT "$DREMIO_URL/apiv2/bootstrap/firstuser" \
  -H "Content-Type: application/json" \
  -d "{
    \"userName\": \"$DREMIO_USER\",
    \"firstName\": \"Admin\",
    \"lastName\": \"User\",
    \"email\": \"admin@example.com\",
    \"createdAt\": 0,
    \"password\": \"$DREMIO_PASS\"
  }")
if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "409" ] || [ "$HTTP_CODE" = "400" ]; then
  ok "First user ready (HTTP $HTTP_CODE)"
else
  fail "Bootstrap user failed (HTTP $HTTP_CODE)"
  exit 1
fi

# ── 3. Login ─────────────────────────────────────────────────────────
echo "Logging in..."
LOGIN_RESP=$(curl -s -X POST "$DREMIO_URL/apiv2/login" \
  -H "Content-Type: application/json" \
  -d "{\"userName\": \"$DREMIO_USER\", \"password\": \"$DREMIO_PASS\"}")
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" 2>/dev/null) || {
  fail "Login failed: $LOGIN_RESP"
  exit 1
}
ok "Logged in"

AUTH="_dremio${TOKEN}"

# ── Helper: create source ────────────────────────────────────────────
create_source() {
  local name="$1" body="$2"
  local resp code rbody
  resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$DREMIO_URL/apiv2/source/$name" \
    -H "Content-Type: application/json" \
    -H "Authorization: $AUTH" \
    -d "$body")
  code=$(echo "$resp" | tail -1)
  rbody=$(echo "$resp" | sed '$d')
  if [ "$code" = "200" ]; then
    ok "Source '$name' created"
  elif [ "$code" = "409" ]; then
    warn "Source '$name' already exists"
  else
    fail "Source '$name' failed (HTTP $code): $rbody"
    return 1
  fi
}

# ── Helper: run SQL via Dremio ───────────────────────────────────────
run_dremio_sql() {
  local sql="$1" label="${2:-SQL}"
  local resp code body job_id status

  resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$DREMIO_URL/api/v3/sql" \
    -H "Content-Type: application/json" \
    -H "Authorization: $AUTH" \
    -d "{\"sql\": \"$sql\"}")
  code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')

  if [ "$code" != "200" ]; then
    fail "$label — HTTP $code: $body"
    return 1
  fi

  job_id=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null) || {
    fail "$label — no job id: $body"
    return 1
  }

  # Poll for completion
  for i in $(seq 1 30); do
    sleep 2
    status=$(curl -s \
      -H "Authorization: $AUTH" \
      "$DREMIO_URL/api/v3/job/$job_id" \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('jobState',''))" 2>/dev/null || true)
    if [ "$status" = "COMPLETED" ]; then
      ok "$label"
      return 0
    elif [ "$status" = "FAILED" ] || [ "$status" = "CANCELED" ]; then
      local detail
      detail=$(curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id" \
        | python3 -c "import sys,json; j=json.load(sys.stdin); print(j.get('errorMessage','')[:200])" 2>/dev/null || true)
      fail "$label — $status: $detail"
      return 1
    fi
  done
  fail "$label — timeout waiting for job $job_id"
  return 1
}

# ── 4. Seed PostgreSQL ────────────────────────────────────────────────
echo ""
echo "Seeding PostgreSQL..."
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose-jdbc-test.yml"
PG_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q postgres)
docker exec -i "$PG_CONTAINER" psql -U pguser -d testdb -q <<'EOSQL'
CREATE TABLE IF NOT EXISTS employees (
  id SERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  department VARCHAR(50),
  salary NUMERIC(10,2)
);
INSERT INTO employees (name, department, salary) VALUES
  ('Alice', 'Engineering', 120000),
  ('Bob', 'Marketing', 95000),
  ('Charlie', 'Engineering', 130000)
ON CONFLICT DO NOTHING;
EOSQL
ok "PostgreSQL seeded"

# ── 4b. Seed PostgreSQL all-types table ───────────────────────────────
echo "Seeding PostgreSQL all-types table..."
docker exec -i "$PG_CONTAINER" psql -U pguser -d testdb -q <<'EOSQL'
CREATE TABLE IF NOT EXISTS pg_all_types (
  id SERIAL PRIMARY KEY,
  col_text TEXT,
  col_varchar VARCHAR(100),
  col_char CHAR(10),
  col_boolean BOOLEAN,
  col_smallint SMALLINT,
  col_integer INTEGER,
  col_bigint BIGINT,
  col_real REAL,
  col_double DOUBLE PRECISION,
  col_numeric NUMERIC(10,2),
  col_date DATE,
  col_time TIME,
  col_timestamp TIMESTAMP,
  col_timestamptz TIMESTAMPTZ,
  col_bytea BYTEA,
  col_uuid UUID,
  col_jsonb JSONB,
  col_json JSON,
  col_interval INTERVAL,
  col_money MONEY,
  col_int_array INTEGER[],
  col_text_array TEXT[],
  col_cidr CIDR,
  col_inet INET,
  col_macaddr MACADDR
);
INSERT INTO pg_all_types (
  col_text, col_varchar, col_char, col_boolean, col_smallint, col_integer,
  col_bigint, col_real, col_double, col_numeric, col_date, col_time,
  col_timestamp, col_timestamptz, col_bytea, col_uuid, col_jsonb, col_json,
  col_interval, col_money, col_int_array, col_text_array, col_cidr, col_inet,
  col_macaddr
) VALUES (
  'hello', 'world', 'pad       ', true, 32767, 2147483647,
  9223372036854775807, 3.14, 2.718281828, 123.45, '2024-01-15', '10:30:00',
  '2024-01-15 10:30:00', '2024-01-15 10:30:00+00', E'\\x48656C6C6F',
  '12151fd2-7586-11e9-8f9e-2a86e4085a59', '{"key":"value"}', '{"num":42}',
  '1 year 2 months', '$1234.56', ARRAY[1,2,3], ARRAY['a','b','c'],
  '192.168.1.0/24', '192.168.1.1', '08:00:2b:01:02:03'
) ON CONFLICT DO NOTHING;
EOSQL
ok "PostgreSQL all-types table seeded"

# ── 5. Seed Oracle ────────────────────────────────────────────────────
echo "Seeding Oracle..."
# Use SQLPlus via docker exec (easier than installing Oracle client locally)
ORA_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q oracle)
docker exec -i "$ORA_CONTAINER" \
  sqlplus -s testuser/testpass@//localhost:1521/XEPDB1 <<'EOSQL'
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE products (
    id NUMBER(10) PRIMARY KEY,
    name VARCHAR2(100) NOT NULL,
    price NUMBER(10,2),
    created_at DATE DEFAULT SYSDATE
  )';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
MERGE INTO products p USING (
  SELECT 1 AS id, 'Widget' AS name, 19.99 AS price FROM DUAL UNION ALL
  SELECT 2, 'Gadget', 49.99 FROM DUAL UNION ALL
  SELECT 3, 'Doohickey', 9.99 FROM DUAL
) s ON (p.id = s.id)
WHEN NOT MATCHED THEN INSERT (id, name, price) VALUES (s.id, s.name, s.price);
COMMIT;
EOSQL
ok "Oracle seeded"

# ── 5b. Seed Oracle all-types table ──────────────────────────────────
echo "Seeding Oracle all-types table..."
docker exec -i "$ORA_CONTAINER" \
  sqlplus -s testuser/testpass@//localhost:1521/XEPDB1 <<'EOSQL'
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE ora_all_types (
    id NUMBER(10) PRIMARY KEY,
    col_number_ps NUMBER(10,2),
    col_number_bare NUMBER,
    col_float FLOAT,
    col_binary_float BINARY_FLOAT,
    col_binary_double BINARY_DOUBLE,
    col_varchar2 VARCHAR2(100),
    col_nvarchar2 NVARCHAR2(100),
    col_char CHAR(10),
    col_clob CLOB,
    col_nclob NCLOB,
    col_date DATE,
    col_timestamp TIMESTAMP(6),
    col_timestamp_tz TIMESTAMP(6) WITH TIME ZONE
  )';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
MERGE INTO ora_all_types t USING (
  SELECT 1 AS id,
    12345.67 AS col_number_ps,
    3.141592653589793 AS col_number_bare,
    2.71828 AS col_float,
    CAST(3.14 AS BINARY_FLOAT) AS col_binary_float,
    CAST(2.718281828459045 AS BINARY_DOUBLE) AS col_binary_double,
    'hello' AS col_varchar2,
    N'world' AS col_nvarchar2,
    'pad       ' AS col_char,
    TO_CLOB('clob text value') AS col_clob,
    TO_NCLOB(N'nclob text value') AS col_nclob,
    TO_DATE('2024-01-15 10:30:00', 'YYYY-MM-DD HH24:MI:SS') AS col_date,
    TIMESTAMP '2024-01-15 10:30:00.123456' AS col_timestamp,
    TIMESTAMP '2024-01-15 10:30:00.123 +05:30' AS col_timestamp_tz
  FROM DUAL
) s ON (t.id = s.id)
WHEN NOT MATCHED THEN INSERT (
  id, col_number_ps, col_number_bare, col_float, col_binary_float, col_binary_double,
  col_varchar2, col_nvarchar2, col_char, col_clob, col_nclob,
  col_date, col_timestamp, col_timestamp_tz
) VALUES (
  s.id, s.col_number_ps, s.col_number_bare, s.col_float, s.col_binary_float, s.col_binary_double,
  s.col_varchar2, s.col_nvarchar2, s.col_char, s.col_clob, s.col_nclob,
  s.col_date, s.col_timestamp, s.col_timestamp_tz
);
COMMIT;
EOSQL
ok "Oracle all-types table seeded"

# ── 6. Create PostgreSQL source ───────────────────────────────────────
echo ""
echo "Creating PostgreSQL source in Dremio..."
create_source "pg_test" '{
  "name": "pg_test",
  "config": {
    "hostname": "postgres",
    "port": 5432,
    "databaseName": "testdb",
    "username": "pguser",
    "password": "pgpass",
    "useSsl": false,
    "encryptionValidationMode": "NO_VALIDATION",
    "fetchSize": 4096,
    "queryTimeoutSec": 0
  },
  "type": "POSTGRES_DB"
}'

# ── 7. Create Oracle source ──────────────────────────────────────────
echo "Creating Oracle source in Dremio..."
create_source "oracle_test" '{
  "name": "oracle_test",
  "config": {
    "hostname": "oracle",
    "port": 1521,
    "serviceName": "XEPDB1",
    "username": "testuser",
    "password": "testpass",
    "useSsl": false,
    "encryptionValidationMode": "NO_VALIDATION",
    "fetchSize": 4096,
    "queryTimeoutSec": 0
  },
  "type": "ORACLE_DB"
}'

# ── 8. Wait for metadata refresh ────────────────────────────────────
echo ""
echo "Waiting for metadata refresh (15s)..."
sleep 15

# ── 9. Query PostgreSQL via Dremio ───────────────────────────────────
echo ""
echo "Querying PostgreSQL via Dremio..."
run_dremio_sql "SELECT * FROM pg_test.public.employees ORDER BY id" \
  "SELECT * FROM pg_test.public.employees"

run_dremio_sql "SELECT department, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM pg_test.public.employees GROUP BY department" \
  "GROUP BY department (PostgreSQL)"

# ── 10. Query Oracle via Dremio ──────────────────────────────────────
echo ""
echo "Querying Oracle via Dremio..."
run_dremio_sql "SELECT * FROM oracle_test.TESTUSER.PRODUCTS ORDER BY ID" \
  "SELECT * FROM oracle_test.TESTUSER.PRODUCTS"

run_dremio_sql "SELECT NAME, PRICE FROM oracle_test.TESTUSER.PRODUCTS WHERE PRICE > 10 ORDER BY PRICE DESC" \
  "Filter pushdown (Oracle WHERE PRICE > 10)"

# ── 11. Cross-source join ────────────────────────────────────────────
echo ""
echo "Cross-source join (PostgreSQL × Oracle)..."
run_dremio_sql "SELECT e.name AS employee, p.name AS product, p.price FROM pg_test.public.employees e CROSS JOIN oracle_test.TESTUSER.PRODUCTS p WHERE e.department = 'Engineering' AND p.price > 10 ORDER BY e.name, p.price" \
  "Cross-source join (PG employees × Oracle products)"

# ── 12. All-types validation (PostgreSQL) ────────────────────────────
echo ""
echo "All-types validation (PostgreSQL)..."
run_dremio_sql "SELECT * FROM pg_test.public.pg_all_types WHERE id = 1" \
  "PG all-types: SELECT * (27 columns)"

run_dremio_sql "SELECT col_text, col_boolean, col_integer, col_bigint, col_real, col_double, col_numeric, col_date, col_timestamp FROM pg_test.public.pg_all_types WHERE id = 1" \
  "PG all-types: core types projection"

run_dremio_sql "SELECT col_uuid, col_jsonb, col_json, col_interval, col_money FROM pg_test.public.pg_all_types WHERE id = 1" \
  "PG all-types: exotic types (UUID, JSONB, JSON, INTERVAL, MONEY)"

run_dremio_sql "SELECT col_int_array, col_text_array, col_cidr, col_inet, col_macaddr FROM pg_test.public.pg_all_types WHERE id = 1" \
  "PG all-types: arrays + network types"

run_dremio_sql "SELECT col_bytea, col_time, col_timestamptz FROM pg_test.public.pg_all_types WHERE id = 1" \
  "PG all-types: bytea, time, timestamptz"

# ── 13. All-types validation (Oracle) ────────────────────────────────
echo ""
echo "All-types validation (Oracle)..."
run_dremio_sql "SELECT * FROM oracle_test.TESTUSER.ORA_ALL_TYPES WHERE ID = 1" \
  "Oracle all-types: SELECT * (14 columns)"

run_dremio_sql "SELECT COL_NUMBER_PS, COL_NUMBER_BARE, COL_FLOAT, COL_BINARY_FLOAT, COL_BINARY_DOUBLE FROM oracle_test.TESTUSER.ORA_ALL_TYPES WHERE ID = 1" \
  "Oracle all-types: numeric types (NUMBER, FLOAT, BINARY_FLOAT/DOUBLE)"

run_dremio_sql "SELECT COL_VARCHAR2, COL_NVARCHAR2, COL_CHAR, COL_CLOB, COL_NCLOB FROM oracle_test.TESTUSER.ORA_ALL_TYPES WHERE ID = 1" \
  "Oracle all-types: string types (VARCHAR2, NVARCHAR2, CHAR, CLOB, NCLOB)"

run_dremio_sql "SELECT COL_DATE, COL_TIMESTAMP, COL_TIMESTAMP_TZ FROM oracle_test.TESTUSER.ORA_ALL_TYPES WHERE ID = 1" \
  "Oracle all-types: temporal types (DATE, TIMESTAMP, TIMESTAMP WITH TZ)"

# ── 14. Pushdown verification via query logs ─────────────────────────
echo ""
echo "Checking pushed-down queries in database logs..."

# Record timestamp marker so we only check queries after this point
PG_LOG_BEFORE=$(docker logs "$PG_CONTAINER" 2>&1 | wc -l)

# Run a filter query that should push down WHERE
run_dremio_sql "SELECT col_integer, col_text FROM pg_test.public.pg_all_types WHERE col_integer > 1000000" \
  "PG pushdown test: WHERE col_integer > 1000000"

# Run a limit query
run_dremio_sql "SELECT col_varchar FROM pg_test.public.pg_all_types LIMIT 1" \
  "PG pushdown test: LIMIT 1"

# Run a limit query against Oracle (uses FETCH FIRST N ROWS ONLY)
run_dremio_sql "SELECT COL_VARCHAR2 FROM oracle_test.TESTUSER.ORA_ALL_TYPES LIMIT 1" \
  "Oracle pushdown test: LIMIT 1 (FETCH FIRST)"

sleep 2
echo ""
echo "PostgreSQL query log (queries from Dremio):"
docker logs "$PG_CONTAINER" 2>&1 | tail -n +"$PG_LOG_BEFORE" \
  | grep -i "statement:\|execute" \
  | grep -iv "pg_catalog\|information_schema\|SET\|BEGIN\|COMMIT\|DEALLOCATE\|pg_type\|pg_namespace" \
  | head -20 \
  | while IFS= read -r line; do info "$line"; done || true

# For Oracle, query V$SQL for recently executed statements from our user.
# Use SYS because testuser may lack V$SQL privileges.
echo ""
echo "Oracle query log (recent queries from TESTUSER):"
ORA_LOG=$(docker exec -i "$ORA_CONTAINER" \
  sqlplus -s sys/orapass@//localhost:1521/XEPDB1 as sysdba <<'EOSQL' 2>&1 || true
SET LINESIZE 200
SET PAGESIZE 100
SET FEEDBACK OFF
SELECT sql_text FROM v$sql
WHERE parsing_schema_name = 'TESTUSER'
  AND sql_text NOT LIKE '%v$sql%'
  AND sql_text NOT LIKE '%DUAL%'
  AND sql_text NOT LIKE '%SYS_CONTEXT%'
  AND sql_text NOT LIKE '%OPT_DYN_SAMP%'
  AND sql_text NOT LIKE '%DBMS_APPLICATION_INFO%'
  AND sql_text NOT LIKE '%all_objects%'
  AND sql_text NOT LIKE '%all_users%'
  AND sql_text NOT LIKE '%table_type from dual%'
  AND sql_text NOT LIKE '%CREATE%'
  AND sql_text NOT LIKE '%MERGE%'
  AND ROWNUM <= 15
ORDER BY last_active_time DESC;
EOSQL
)
echo "$ORA_LOG" | while IFS= read -r line; do [ -n "$line" ] && info "$line"; done || true

# ── Summary ──────────────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [ "$FAILURES" -eq 0 ]; then
  echo -e "${GREEN}ALL TESTS PASSED${NC}"
else
  echo -e "${RED}$FAILURES TEST(S) FAILED${NC}"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
exit "$FAILURES"
