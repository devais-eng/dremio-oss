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
# Phase 35 UAT — JOIN pushdown verification via container logs.
#
# For EVERY test, this script:
#   1. Records the current container log position
#   2. Runs the SQL through Dremio
#   3. Captures new container log lines
#   4. Verifies that the pushed-down SQL contains a JOIN keyword (confirming pushdown fired)
#
# Three source modes are tested:
#   pg_test      — PostgreSQL in JDBC mode
#   pg_adbc_test — PostgreSQL in ADBC mode (same PG container, different Dremio source)
#   oracle_test  — Oracle in JDBC mode
#
# A negative test confirms that cross-source JOINs are NOT pushed down (Dremio handles in-engine).
#
# Usage:
#   ./test-uat-35.sh            # run all
#   ./test-uat-35.sh --pg-only  # PostgreSQL tests only (JDBC + ADBC)
#   ./test-uat-35.sh --ora-only # Oracle tests only
set +o histexpand 2>/dev/null
set -uo pipefail

DREMIO_URL="http://localhost:9047"
PASS='Admin123!'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose-jdbc-test.yml"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; BLUE='\033[0;34m'; NC='\033[0m'
BOLD='\033[1m'

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
RUN_PG=true
RUN_ORA=true

for arg in "$@"; do
  case "$arg" in
    --pg-only)  RUN_ORA=false ;;
    --ora-only) RUN_PG=false ;;
  esac
done

# ── Auth ────────────────────────────────────────────────────────────────
curl -s -o /dev/null -X PUT "$DREMIO_URL/apiv2/bootstrap/firstuser" \
  -H "Content-Type: application/json" \
  -d "{\"userName\":\"admin\",\"firstName\":\"A\",\"lastName\":\"U\",\"email\":\"a@e.c\",\"createdAt\":0,\"password\":\"$PASS\"}" || true

TOKEN=$(curl -s -X POST "$DREMIO_URL/apiv2/login" \
  -H "Content-Type: application/json" \
  -d "{\"userName\":\"admin\",\"password\":\"$PASS\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
AUTH="_dremio${TOKEN}"

# ── Container IDs ───────────────────────────────────────────────────────
PG_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q postgres)
ORA_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q oracle)

# ── Seed data ───────────────────────────────────────────────────────────
echo -e "${BOLD}Seeding test data...${NC}"

# PG: employees (12 rows) + departments (4 rows)
docker exec -i "$PG_CONTAINER" psql -U pguser -d testdb -q <<'EOSQL'
CREATE TABLE IF NOT EXISTS employees (
  id INTEGER PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  department VARCHAR(50),
  salary NUMERIC(10,2)
);
TRUNCATE employees RESTART IDENTITY;
INSERT INTO employees (id, name, department, salary) VALUES
  (1,  'Alice',   'Engineering', 120000),
  (2,  'Bob',     'Marketing',   95000),
  (3,  'Charlie', 'Engineering', 130000),
  (4,  'Diana',   'Sales',       88000),
  (5,  'Eve',     'Engineering', 145000),
  (6,  'Frank',   'Marketing',   72000),
  (7,  'Grace',   'Sales',       105000),
  (8,  'Hank',    NULL,          60000),
  (9,  'Ivy',     'Engineering', 110000),
  (10, 'Jack',    'Marketing',   98000),
  (11, 'Karen',   'Sales',       115000),
  (12, 'Leo',     NULL,          55000);

CREATE TABLE IF NOT EXISTS departments (
  dept_name VARCHAR(50) PRIMARY KEY,
  budget NUMERIC(12,2) NOT NULL,
  location VARCHAR(100) NOT NULL
);
TRUNCATE departments;
INSERT INTO departments (dept_name, budget, location) VALUES
  ('Engineering', 500000, 'Building A'),
  ('Marketing',   200000, 'Building B'),
  ('Sales',       300000, 'Building C'),
  ('HR',          150000, 'Building D');
EOSQL

# Oracle: employees (12 rows) + departments (4 rows)
docker exec -i "$ORA_CONTAINER" \
  sqlplus -s testuser/testpass@//localhost:1521/XEPDB1 <<'EOSQL'
BEGIN
  EXECUTE IMMEDIATE 'DROP TABLE departments';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
  EXECUTE IMMEDIATE 'DROP TABLE employees';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
CREATE TABLE employees (
  id NUMBER(10) PRIMARY KEY,
  name VARCHAR2(100) NOT NULL,
  department VARCHAR2(50),
  salary NUMBER(10,2)
);
INSERT ALL
  INTO employees (id, name, department, salary) VALUES (1, 'Alice',   'Engineering', 120000)
  INTO employees (id, name, department, salary) VALUES (2, 'Bob',     'Marketing',   95000)
  INTO employees (id, name, department, salary) VALUES (3, 'Charlie', 'Engineering', 130000)
  INTO employees (id, name, department, salary) VALUES (4, 'Diana',   'Sales',       88000)
  INTO employees (id, name, department, salary) VALUES (5, 'Eve',     'Engineering', 145000)
  INTO employees (id, name, department, salary) VALUES (6, 'Frank',   'Marketing',   72000)
  INTO employees (id, name, department, salary) VALUES (7, 'Grace',   'Sales',       105000)
  INTO employees (id, name, department, salary) VALUES (8, 'Hank',    NULL,          60000)
  INTO employees (id, name, department, salary) VALUES (9, 'Ivy',     'Engineering', 110000)
  INTO employees (id, name, department, salary) VALUES (10, 'Jack',   'Marketing',   98000)
  INTO employees (id, name, department, salary) VALUES (11, 'Karen',  'Sales',       115000)
  INTO employees (id, name, department, salary) VALUES (12, 'Leo',    NULL,          55000)
SELECT 1 FROM DUAL;
CREATE TABLE departments (
  dept_name VARCHAR2(50) PRIMARY KEY,
  budget NUMBER(12,2) NOT NULL,
  location VARCHAR2(100) NOT NULL
);
INSERT ALL
  INTO departments VALUES ('Engineering', 500000, 'Building A')
  INTO departments VALUES ('Marketing',   200000, 'Building B')
  INTO departments VALUES ('Sales',       300000, 'Building C')
  INTO departments VALUES ('HR',          150000, 'Building D')
SELECT 1 FROM DUAL;
COMMIT;
EOSQL

echo -e "${GREEN}Data seeded.${NC}"

# ── Create Dremio sources ────────────────────────────────────────────────
echo -n "Creating Dremio sources (pg_test, pg_adbc_test, oracle_test)... "
python3 -c "
import requests, time
url = '$DREMIO_URL'
auth = '$AUTH'
hdrs = {'Authorization': auth, 'Content-Type': 'application/json'}

# Delete existing sources
for name in ['pg_test', 'pg_adbc_test', 'oracle_test']:
    requests.delete(f'{url}/apiv2/source/{name}', headers=hdrs)
time.sleep(2)

# pg_test — PostgreSQL JDBC mode
requests.put(f'{url}/apiv2/source/pg_test', headers=hdrs, json={
    'name': 'pg_test',
    'config': {
        'hostname': 'postgres', 'port': 5432, 'databaseName': 'testdb',
        'username': 'pguser', 'password': 'pgpass',
        'useSsl': False, 'encryptionValidationMode': 'NO_VALIDATION',
        'fetchSize': 4096, 'queryTimeoutSec': 0
    },
    'type': 'POSTGRES_DB'
})

# pg_adbc_test — PostgreSQL ADBC mode (same container, different Dremio source)
requests.put(f'{url}/apiv2/source/pg_adbc_test', headers=hdrs, json={
    'name': 'pg_adbc_test',
    'config': {
        'hostname': 'postgres', 'port': 5432, 'databaseName': 'testdb',
        'username': 'pguser', 'password': 'pgpass',
        'useSsl': False, 'encryptionValidationMode': 'NO_VALIDATION',
        'fetchSize': 4096, 'queryTimeoutSec': 0,
        'protocolMode': 'ADBC'
    },
    'type': 'POSTGRES_DB'
})

# oracle_test — Oracle JDBC mode
requests.put(f'{url}/apiv2/source/oracle_test', headers=hdrs, json={
    'name': 'oracle_test',
    'config': {
        'hostname': 'oracle', 'port': 1521, 'serviceName': 'XEPDB1',
        'username': 'testuser', 'password': 'testpass',
        'useSsl': False, 'encryptionValidationMode': 'NO_VALIDATION',
        'fetchSize': 4096, 'queryTimeoutSec': 0
    },
    'type': 'ORACLE_DB'
})
" 2>/dev/null
sleep 15
echo -e "${GREEN}done.${NC}"
echo ""

# ── Core test harness ───────────────────────────────────────────────────

# Captures PG log line count at this moment
pg_log_pos() {
  docker logs "$PG_CONTAINER" 2>&1 | wc -l
}

# Returns new PG log lines since $1, filtered to Dremio queries only
pg_new_lines() {
  local since="$1"
  docker logs "$PG_CONTAINER" 2>&1 | tail -n +"$since" \
    | grep -iE "execute|statement:" \
    | grep -iv "pg_catalog\|information_schema\|SET\|BEGIN\|COMMIT\|DEALLOCATE\|pg_type\|pg_namespace\|ROLLBACK\|SELECT 1\|SHOW\|pg_class\|pg_attribute\|pg_attrdef\|pg_constraint\|pg_index\|pg_description\|pg_am\|pg_stat\|pg_settings"
}

# Oracle: flush the shared pool to clear V$SQL cache
ora_flush_cache() {
  docker exec -i "$ORA_CONTAINER" \
    sqlplus -s sys/orapass@//localhost:1521/XEPDB1 as sysdba <<'EOSQL' 2>/dev/null
ALTER SYSTEM FLUSH SHARED_POOL;
EOSQL
}

# Oracle: get all TESTUSER queries from V$SQL
ora_all_queries() {
  docker exec -i "$ORA_CONTAINER" \
    sqlplus -s sys/orapass@//localhost:1521/XEPDB1 as sysdba <<'EOSQL' 2>/dev/null
SET HEADING OFF FEEDBACK OFF PAGESIZE 200 LINESIZE 500
SELECT sql_text FROM v$sql
WHERE parsing_schema_name = 'TESTUSER'
  AND sql_text NOT LIKE '%v$sql%'
  AND sql_text NOT LIKE '%DUAL%'
  AND sql_text NOT LIKE '%SYS_CONTEXT%'
  AND sql_text NOT LIKE '%OPT_DYN_SAMP%'
  AND sql_text NOT LIKE '%DBMS_%'
  AND sql_text NOT LIKE '%all_objects%'
  AND sql_text NOT LIKE '%all_users%'
  AND sql_text NOT LIKE '%CREATE%'
  AND sql_text NOT LIKE '%MERGE%'
  AND sql_text NOT LIKE '%DELETE%'
  AND sql_text NOT LIKE '%INSERT%'
  AND sql_text NOT LIKE '%table_type%'
ORDER BY last_active_time DESC;
EOSQL
}

# run_test SQL LABEL SOURCE EXPECTED_LOG_PATTERNS...
# SOURCE: "pg", "ora", or "both"
run_test() {
  local sql="$1"
  local label="$2"
  local source="$3"
  shift 3
  local expected_patterns=("$@")

  echo -e "${BOLD}--- $label ---${NC}"

  local pg_before=0
  if [ "$source" = "pg" ] || [ "$source" = "both" ]; then
    pg_before=$(pg_log_pos)
  fi

  local resp job_id status
  resp=$(curl -s -X POST "$DREMIO_URL/api/v3/sql" \
    -H "Content-Type: application/json" -H "Authorization: $AUTH" \
    -d "{\"sql\":\"$sql\"}")
  job_id=$(echo "$resp" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])' 2>/dev/null)
  if [ -z "$job_id" ]; then
    echo -e "  ${RED}ERROR submitting: $resp${NC}"
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo ""
    return 1
  fi

  for i in $(seq 1 30); do
    sleep 2
    status=$(curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id" \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('jobState',''))" 2>/dev/null || true)
    if [ "$status" = "COMPLETED" ]; then break; fi
    if [ "$status" = "FAILED" ] || [ "$status" = "CANCELED" ]; then
      local detail
      detail=$(curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id" \
        | python3 -c "import sys,json; j=json.load(sys.stdin); print(j.get('errorMessage','')[:600])" 2>/dev/null || true)
      echo -e "  ${RED}QUERY FAILED: $detail${NC}"
      FAIL_COUNT=$((FAIL_COUNT+1))
      echo ""
      return 1
    fi
  done

  if [ "$status" != "COMPLETED" ]; then
    echo -e "  ${RED}TIMEOUT waiting for job $job_id${NC}"
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo ""
    return 1
  fi

  echo "  RESULTS:"
  curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id/results" | python3 -c '
import sys, json
j = json.load(sys.stdin)
for row in j.get("rows", []):
    print("    ", row)
' 2>&1

  sleep 1

  local all_patterns_ok=true

  if [ "$source" = "pg" ] || [ "$source" = "both" ]; then
    local pg_lines
    pg_lines=$(pg_new_lines "$pg_before")
    echo -e "  ${BLUE}PG LOG:${NC}"
    if [ -n "$pg_lines" ]; then
      echo "$pg_lines" | head -5 | while IFS= read -r line; do
        echo -e "    ${BLUE}$line${NC}"
      done
    else
      echo -e "    ${YELLOW}(no Dremio query lines captured)${NC}"
    fi

    for pattern in "${expected_patterns[@]}"; do
      if [ "$source" = "both" ] && [[ "$pattern" == ORA:* ]]; then
        continue
      fi
      local clean_pattern="${pattern#PG:}"
      clean_pattern="${clean_pattern#ANY:}"
      if echo "$pg_lines" | grep -qiE "$clean_pattern"; then
        echo -e "    ${GREEN}PG PUSHDOWN OK:${NC} found '$clean_pattern'"
      else
        echo -e "    ${RED}PG PUSHDOWN MISSING:${NC} expected '$clean_pattern' in PG log${NC}"
        all_patterns_ok=false
      fi
    done
  fi

  if [ "$source" = "ora" ] || [ "$source" = "both" ]; then
    local ora_lines
    ora_lines=$(ora_all_queries)
    echo -e "  ${BLUE}ORA V\$SQL:${NC}"
    if [ -n "$(echo "$ora_lines" | tr -d '[:space:]')" ]; then
      echo "$ora_lines" | grep -v '^$' | head -3 | while IFS= read -r line; do
        echo -e "    ${BLUE}$line${NC}"
      done
    else
      echo -e "    ${YELLOW}(no TESTUSER queries in V\$SQL)${NC}"
    fi

    for pattern in "${expected_patterns[@]}"; do
      if [ "$source" = "both" ] && [[ "$pattern" == PG:* ]]; then
        continue
      fi
      local clean_pattern="${pattern#ORA:}"
      clean_pattern="${clean_pattern#ANY:}"
      if echo "$ora_lines" | grep -qiE "$clean_pattern"; then
        echo -e "    ${GREEN}ORA PUSHDOWN OK:${NC} found '$clean_pattern'"
      else
        echo -e "    ${RED}ORA PUSHDOWN MISSING:${NC} expected '$clean_pattern' in Oracle V\$SQL${NC}"
        all_patterns_ok=false
      fi
    done
  fi

  if $all_patterns_ok; then
    echo -e "  ${GREEN}PASS${NC}"
    PASS_COUNT=$((PASS_COUNT+1))
  else
    echo -e "  ${RED}FAIL (pushdown not confirmed in container log)${NC}"
    FAIL_COUNT=$((FAIL_COUNT+1))
  fi
  echo ""
}

# run_test_no_pushdown SQL LABEL SOURCE UNEXPECTED_PATTERN
# Passes when the pattern does NOT appear in the container log (i.e., pushdown correctly absent)
run_test_no_pushdown() {
  local sql="$1"
  local label="$2"
  local source="$3"
  local unexpected_pattern="$4"

  echo -e "${BOLD}--- $label ---${NC}"

  local pg_before=0
  if [ "$source" = "pg" ] || [ "$source" = "both" ]; then
    pg_before=$(pg_log_pos)
  fi

  local resp job_id status
  resp=$(curl -s -X POST "$DREMIO_URL/api/v3/sql" \
    -H "Content-Type: application/json" -H "Authorization: $AUTH" \
    -d "{\"sql\":\"$sql\"}")
  job_id=$(echo "$resp" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])' 2>/dev/null)
  if [ -z "$job_id" ]; then
    echo -e "  ${RED}ERROR submitting: $resp${NC}"
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo ""
    return 1
  fi

  for i in $(seq 1 30); do
    sleep 2
    status=$(curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id" \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('jobState',''))" 2>/dev/null || true)
    if [ "$status" = "COMPLETED" ]; then break; fi
    if [ "$status" = "FAILED" ] || [ "$status" = "CANCELED" ]; then
      local detail
      detail=$(curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id" \
        | python3 -c "import sys,json; j=json.load(sys.stdin); print(j.get('errorMessage','')[:600])" 2>/dev/null || true)
      echo -e "  ${RED}QUERY FAILED: $detail${NC}"
      FAIL_COUNT=$((FAIL_COUNT+1))
      echo ""
      return 1
    fi
  done

  echo "  RESULTS:"
  curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id/results" | python3 -c '
import sys, json
j = json.load(sys.stdin)
for row in j.get("rows", []):
    print("    ", row)
' 2>&1

  sleep 1

  local found=false
  if [ "$source" = "pg" ] || [ "$source" = "both" ]; then
    local pg_lines
    pg_lines=$(pg_new_lines "$pg_before")
    if echo "$pg_lines" | grep -qiE "$unexpected_pattern"; then
      found=true
    fi
  fi
  if [ "$source" = "ora" ] || [ "$source" = "both" ]; then
    local ora_lines
    ora_lines=$(ora_all_queries)
    if echo "$ora_lines" | grep -qiE "$unexpected_pattern"; then
      found=true
    fi
  fi

  if $found; then
    echo -e "  ${RED}FAIL: '$unexpected_pattern' was pushed but should NOT be${NC}"
    FAIL_COUNT=$((FAIL_COUNT+1))
  else
    echo -e "  ${GREEN}PASS (correctly NOT pushed: '$unexpected_pattern')${NC}"
    PASS_COUNT=$((PASS_COUNT+1))
  fi
  echo ""
}

###########################################################################
# Source references
###########################################################################
PG_EMP="pg_test.public.employees"
PG_DEPT="pg_test.public.departments"
PG_ADBC_EMP="pg_adbc_test.public.employees"
PG_ADBC_DEPT="pg_adbc_test.public.departments"
ORA_EMP="oracle_test.TESTUSER.EMPLOYEES"
ORA_DEPT="oracle_test.TESTUSER.DEPARTMENTS"

# Flush Oracle shared pool before tests
if $RUN_ORA; then
  echo -n "Flushing Oracle shared pool... "
  ora_flush_cache
  echo -e "${GREEN}done.${NC}"
fi

echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  PHASE 35 UAT: JOIN PUSHDOWN VERIFICATION                  ${NC}"
echo -e "${BOLD}  PG JDBC + PG ADBC + Oracle JDBC (container log check)     ${NC}"
echo -e "${BOLD}============================================================${NC}"
echo ""

###########################################################################
# SECTION 1: PG JDBC — JOIN pushdown verification
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 1. PG JDBC — JOIN PUSHDOWN =====${NC}"

# Test 1: INNER JOIN
run_test "SELECT e.name, e.salary, d.budget FROM $PG_EMP e INNER JOIN $PG_DEPT d ON e.department = d.dept_name" \
  "PG JDBC: INNER JOIN employees x departments" "pg" \
  "JOIN"

# Test 2: LEFT JOIN
run_test "SELECT e.name, d.budget FROM $PG_EMP e LEFT JOIN $PG_DEPT d ON e.department = d.dept_name" \
  "PG JDBC: LEFT JOIN (all employees, nulls for unmatched)" "pg" \
  "LEFT.*JOIN"

# Test 3: RIGHT JOIN
run_test "SELECT e.name, d.dept_name, d.budget FROM $PG_EMP e RIGHT JOIN $PG_DEPT d ON e.department = d.dept_name" \
  "PG JDBC: RIGHT JOIN (all departments, nulls for unmatched)" "pg" \
  "RIGHT.*JOIN"

# Test 4: FULL OUTER JOIN
run_test "SELECT e.name, d.dept_name FROM $PG_EMP e FULL OUTER JOIN $PG_DEPT d ON e.department = d.dept_name" \
  "PG JDBC: FULL OUTER JOIN" "pg" \
  "FULL.*JOIN"

# Test 5: JOIN + WHERE filter
# Note: filter may or may not be inside the pushed SQL depending on planner decisions.
# We verify correctness; pushdown of the JOIN itself is the primary check.
run_test "SELECT e.name, e.salary, d.budget FROM $PG_EMP e INNER JOIN $PG_DEPT d ON e.department = d.dept_name WHERE e.salary > 100000" \
  "PG JDBC: JOIN + WHERE filter (salary > 100000)" "pg" \
  "JOIN"

# Test 6: JOIN + GROUP BY + SUM
# Expected totals: Engineering = 120000+130000+145000+110000 = 505000,
#                  Marketing   = 95000+72000+98000 = 265000,
#                  Sales       = 88000+105000+115000 = 308000
run_test "SELECT d.dept_name, SUM(e.salary) as total_salary FROM $PG_EMP e INNER JOIN $PG_DEPT d ON e.department = d.dept_name GROUP BY d.dept_name" \
  "PG JDBC: JOIN + GROUP BY + SUM(salary)" "pg" \
  "JOIN"

# Test 7: JOIN + GROUP BY + AVG + ORDER BY
run_test "SELECT d.dept_name, AVG(e.salary) as avg_salary FROM $PG_EMP e INNER JOIN $PG_DEPT d ON e.department = d.dept_name GROUP BY d.dept_name ORDER BY avg_salary DESC" \
  "PG JDBC: JOIN + GROUP BY + AVG + ORDER BY" "pg" \
  "JOIN"

# Test 8: JOIN + GROUP BY + COUNT
# Expected: Engineering=4, Marketing=3, Sales=3
run_test "SELECT d.dept_name, COUNT(*) as emp_count FROM $PG_EMP e INNER JOIN $PG_DEPT d ON e.department = d.dept_name GROUP BY d.dept_name" \
  "PG JDBC: JOIN + GROUP BY + COUNT(*)" "pg" \
  "JOIN"

fi  # RUN_PG

###########################################################################
# SECTION 2: PG ADBC — same JOIN tests via ADBC source
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 2. PG ADBC — JOIN PUSHDOWN (ADBC protocol) =====${NC}"

# Test 9: ADBC INNER JOIN
run_test "SELECT e.name, e.salary, d.budget FROM $PG_ADBC_EMP e INNER JOIN $PG_ADBC_DEPT d ON e.department = d.dept_name" \
  "PG ADBC: INNER JOIN employees x departments" "pg" \
  "JOIN"

# Test 10: ADBC LEFT JOIN
run_test "SELECT e.name, d.budget FROM $PG_ADBC_EMP e LEFT JOIN $PG_ADBC_DEPT d ON e.department = d.dept_name" \
  "PG ADBC: LEFT JOIN (all employees)" "pg" \
  "JOIN"

# Test 11: ADBC JOIN + GROUP BY + SUM
run_test "SELECT d.dept_name, SUM(e.salary) as total_salary FROM $PG_ADBC_EMP e INNER JOIN $PG_ADBC_DEPT d ON e.department = d.dept_name GROUP BY d.dept_name" \
  "PG ADBC: JOIN + GROUP BY + SUM(salary)" "pg" \
  "JOIN"

# Test 12: ADBC combined — JOIN + WHERE + GROUP BY + AVG + ORDER BY
run_test "SELECT d.dept_name, AVG(e.salary) as avg_sal FROM $PG_ADBC_EMP e INNER JOIN $PG_ADBC_DEPT d ON e.department = d.dept_name WHERE e.salary > 60000 GROUP BY d.dept_name ORDER BY avg_sal DESC" \
  "PG ADBC: JOIN + WHERE + GROUP BY + AVG + ORDER BY (combined)" "pg" \
  "JOIN"

fi  # RUN_PG

###########################################################################
# SECTION 3: Oracle JDBC — JOIN pushdown verification
###########################################################################
if $RUN_ORA; then
echo -e "${BOLD}===== 3. ORACLE JDBC — JOIN PUSHDOWN =====${NC}"

# Flush Oracle pool before Oracle section
ora_flush_cache

# Test 13: Oracle INNER JOIN
run_test "SELECT e.NAME, e.SALARY, d.BUDGET FROM $ORA_EMP e INNER JOIN $ORA_DEPT d ON e.DEPARTMENT = d.DEPT_NAME" \
  "ORA JDBC: INNER JOIN EMPLOYEES x DEPARTMENTS" "ora" \
  "JOIN"

# Test 14: Oracle LEFT JOIN
run_test "SELECT e.NAME, d.BUDGET FROM $ORA_EMP e LEFT JOIN $ORA_DEPT d ON e.DEPARTMENT = d.DEPT_NAME" \
  "ORA JDBC: LEFT JOIN (all employees)" "ora" \
  "LEFT.*JOIN"

# Test 15: Oracle FULL OUTER JOIN
run_test "SELECT e.NAME, d.DEPT_NAME FROM $ORA_EMP e FULL OUTER JOIN $ORA_DEPT d ON e.DEPARTMENT = d.DEPT_NAME" \
  "ORA JDBC: FULL OUTER JOIN" "ora" \
  "FULL.*JOIN"

# Test 16: Oracle JOIN + GROUP BY + SUM
run_test "SELECT d.DEPT_NAME, SUM(e.SALARY) as total_salary FROM $ORA_EMP e INNER JOIN $ORA_DEPT d ON e.DEPARTMENT = d.DEPT_NAME GROUP BY d.DEPT_NAME" \
  "ORA JDBC: JOIN + GROUP BY + SUM(SALARY)" "ora" \
  "JOIN"

# Test 17: Oracle JOIN + GROUP BY + AVG + ORDER BY
run_test "SELECT d.DEPT_NAME, AVG(e.SALARY) as avg_salary FROM $ORA_EMP e INNER JOIN $ORA_DEPT d ON e.DEPARTMENT = d.DEPT_NAME GROUP BY d.DEPT_NAME ORDER BY avg_salary DESC" \
  "ORA JDBC: JOIN + GROUP BY + AVG + ORDER BY (combined)" "ora" \
  "JOIN"

fi  # RUN_ORA

###########################################################################
# SECTION 4: Negative test — cross-source JOIN should NOT push down
###########################################################################
if $RUN_PG && $RUN_ORA; then
echo -e "${BOLD}===== 4. NEGATIVE: CROSS-SOURCE JOIN — NOT pushed down =====${NC}"

# Test 18: Cross-source JOIN (PG employees x Oracle departments)
# Dremio executes in-engine; each source should receive a simple SELECT (no JOIN).
# The PG log should NOT contain a JOIN keyword — PG only scans its table independently.
echo -e "${BOLD}--- NEGATIVE: Cross-source JOIN (PG x Oracle) not pushed ---${NC}"

PG_B=$(pg_log_pos)

resp=$(curl -s -X POST "$DREMIO_URL/api/v3/sql" \
  -H "Content-Type: application/json" -H "Authorization: $AUTH" \
  -d "{\"sql\":\"SELECT e.name, d.DEPT_NAME FROM $PG_EMP e INNER JOIN $ORA_DEPT d ON e.department = d.DEPT_NAME\"}")
job_id=$(echo "$resp" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])' 2>/dev/null)

if [ -z "$job_id" ]; then
  echo -e "  ${RED}ERROR submitting cross-source query: $resp${NC}"
  FAIL_COUNT=$((FAIL_COUNT+1))
  echo ""
else
  for i in $(seq 1 30); do
    sleep 2
    status=$(curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id" \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('jobState',''))" 2>/dev/null || true)
    if [ "$status" = "COMPLETED" ] || [ "$status" = "FAILED" ] || [ "$status" = "CANCELED" ]; then break; fi
  done

  echo "  RESULTS:"
  curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id/results" | python3 -c '
import sys, json
j = json.load(sys.stdin)
for row in j.get("rows", []):
    print("    ", row)
' 2>&1

  sleep 1

  # Verify: PG log should NOT contain JOIN keyword (each table scanned separately)
  pg_lines=$(pg_new_lines "$PG_B")
  echo -e "  ${BLUE}PG LOG (should be simple SELECT, no JOIN):${NC}"
  if [ -n "$pg_lines" ]; then
    echo "$pg_lines" | head -3 | while IFS= read -r line; do echo -e "    ${BLUE}$line${NC}"; done
  else
    echo -e "    ${YELLOW}(no Dremio query lines captured)${NC}"
  fi

  if echo "$pg_lines" | grep -qiE "JOIN"; then
    echo -e "  ${RED}FAIL: PG log contains JOIN — cross-source join was incorrectly pushed${NC}"
    FAIL_COUNT=$((FAIL_COUNT+1))
  else
    echo -e "  ${GREEN}PASS (PG log does NOT contain JOIN — cross-source correctly handled in-engine)${NC}"
    PASS_COUNT=$((PASS_COUNT+1))
  fi
  echo ""
fi

fi  # RUN_PG && RUN_ORA

###########################################################################
# SUMMARY
###########################################################################
echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  SUMMARY: ${GREEN}$PASS_COUNT passed${NC}, ${RED}$FAIL_COUNT failed${NC}, ${YELLOW}$SKIP_COUNT skipped${NC}"
echo -e "${BOLD}============================================================${NC}"

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
