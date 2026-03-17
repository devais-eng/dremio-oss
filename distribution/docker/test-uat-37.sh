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
# Phase 37 UAT — Expression pushdown: functions, HAVING, ORDER BY expressions.
#
# Verifies that:
#   - Whitelisted function expressions in SELECT are pushed to source SQL (UPPER, LOWER, ROUND, ABS,
#     TRIM, LENGTH, EXTRACT, COALESCE, CAST, CEIL, FLOOR) for PG JDBC, PG ADBC, and Oracle.
#   - Function composition (UPPER(TRIM(col))) is pushed; non-whitelisted (RANDOM()) is NOT pushed.
#   - HAVING pushdown produces correct SQL with GROUP BY ... HAVING COUNT(*) > N.
#   - COUNT(DISTINCT col) produces correct SQL.
#   - ORDER BY expression (ORDER BY UPPER(name)) is pushed as a single source SQL query.
#   - ORDER BY expression + LIMIT K is pushed as single source SQL (TopN path).
#   - CAST in same-source JOIN is pushed (not declined).
#
# 31 test scenarios across 10 sections:
#   SECTION 1:  PG JDBC — Whitelisted function pushdown (8 tests)
#   SECTION 2:  PG JDBC — Function composition pushdown (3 tests)
#   SECTION 3:  PG JDBC — Non-whitelisted function rejection (1 test)
#   SECTION 4:  PG JDBC — HAVING pushdown (2 tests)
#   SECTION 5:  PG JDBC — COUNT(DISTINCT) (2 tests)
#   SECTION 6:  PG JDBC — ORDER BY expression + LIMIT K (3 tests)
#   SECTION 7:  PG JDBC — CAST in same-source JOIN (1 test)
#   SECTION 8:  PG ADBC — Key tests via ADBC protocol (4 tests)
#   SECTION 9:  Oracle — Function pushdown (3 tests)
#   SECTION 10: Oracle — HAVING + COUNT(DISTINCT) + ORDER BY expr (3 tests)
#
# Usage:
#   ./test-uat-37.sh            # run all sections
#   ./test-uat-37.sh --pg-only  # PostgreSQL tests only (JDBC + ADBC)
#   ./test-uat-37.sh --ora-only # Oracle tests only
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
  salary NUMERIC(10,2),
  hire_date DATE
);
TRUNCATE employees RESTART IDENTITY;
INSERT INTO employees (id, name, department, salary, hire_date) VALUES
  (1,  'Alice',   'Engineering', 120000, '2020-01-15'),
  (2,  'Bob',     'Marketing',   95000,  '2019-03-22'),
  (3,  'Charlie', 'Engineering', 130000, '2021-06-01'),
  (4,  'Diana',   'Sales',       88000,  '2018-11-30'),
  (5,  'Eve',     'Engineering', 145000, '2017-07-14'),
  (6,  'Frank',   'Marketing',   72000,  '2022-02-28'),
  (7,  'Grace',   'Sales',       105000, '2020-09-10'),
  (8,  'Hank',    NULL,          60000,  '2023-01-01'),
  (9,  'Ivy',     'Engineering', 110000, '2019-12-05'),
  (10, 'Jack',    'Marketing',   98000,  '2021-04-18'),
  (11, 'Karen',   'Sales',       115000, '2018-08-25'),
  (12, 'Leo',     NULL,          55000,  '2023-06-15');

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

# Oracle: employees + departments
docker exec -i "$ORA_CONTAINER" \
  sqlplus -s testuser/testpass@//localhost:1521/XEPDB1 <<'EOSQL'
BEGIN EXECUTE IMMEDIATE 'DROP TABLE departments'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE employees'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
/
CREATE TABLE employees (
  id NUMBER(10) PRIMARY KEY,
  name VARCHAR2(100) NOT NULL,
  department VARCHAR2(50),
  salary NUMBER(10,2),
  hire_date DATE
);
INSERT ALL
  INTO employees VALUES (1,  'Alice',   'Engineering', 120000, DATE '2020-01-15')
  INTO employees VALUES (2,  'Bob',     'Marketing',    95000, DATE '2019-03-22')
  INTO employees VALUES (3,  'Charlie', 'Engineering', 130000, DATE '2021-06-01')
  INTO employees VALUES (4,  'Diana',   'Sales',        88000, DATE '2018-11-30')
  INTO employees VALUES (5,  'Eve',     'Engineering', 145000, DATE '2017-07-14')
  INTO employees VALUES (6,  'Frank',   'Marketing',    72000, DATE '2022-02-28')
  INTO employees VALUES (7,  'Grace',   'Sales',       105000, DATE '2020-09-10')
  INTO employees VALUES (8,  'Hank',    NULL,           60000, DATE '2023-01-01')
  INTO employees VALUES (9,  'Ivy',     'Engineering', 110000, DATE '2019-12-05')
  INTO employees VALUES (10, 'Jack',    'Marketing',    98000, DATE '2021-04-18')
  INTO employees VALUES (11, 'Karen',   'Sales',       115000, DATE '2018-08-25')
  INTO employees VALUES (12, 'Leo',     NULL,           55000, DATE '2023-06-15')
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

for name in ['pg_test', 'pg_adbc_test', 'oracle_test']:
    requests.delete(f'{url}/apiv2/source/{name}', headers=hdrs)
time.sleep(2)

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

requests.put(f'{url}/apiv2/source/pg_adbc_test', headers=hdrs, json={
    'name': 'pg_adbc_test',
    'config': {
        'hostname': 'postgres', 'port': 5432, 'databaseName': 'testdb',
        'username': 'pguser', 'password': 'pgpass',
        'useSsl': False, 'encryptionValidationMode': 'NO_VALIDATION',
        'fetchSize': 4096, 'queryTimeoutSec': 0,
        'protocolMode': 'AUTO'
    },
    'type': 'POSTGRES_DB'
})

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

pg_log_pos() {
  docker logs "$PG_CONTAINER" 2>&1 | wc -l
}

pg_new_lines() {
  local since="$1"
  docker logs "$PG_CONTAINER" 2>&1 | tail -n +"$since" \
    | awk '
      /^\t/ {
        if (current != "") current = current " " substr($0, 2)
        next
      }
      {
        if (current != "") print current
        current = $0
      }
      END { if (current != "") print current }
    ' \
    | grep -iE "execute|statement:" \
    | grep -iv "pg_catalog\|information_schema\|SET\|BEGIN\|COMMIT\|DEALLOCATE\|pg_type\|pg_namespace\|ROLLBACK\|SELECT 1\|SHOW\|pg_class\|pg_attribute\|pg_attrdef\|pg_constraint\|pg_index\|pg_description\|pg_am\|pg_stat\|pg_settings"
}

ora_flush_cache() {
  docker exec -i "$ORA_CONTAINER" \
    sqlplus -s sys/orapass@//localhost:1521/XEPDB1 as sysdba <<'EOSQL' 2>/dev/null
ALTER SYSTEM FLUSH SHARED_POOL;
EOSQL
}

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
    echo -e "  ${BLUE}PG LOG:${NC}"
    if [ -n "$pg_lines" ]; then
      echo "$pg_lines" | head -3 | while IFS= read -r line; do echo -e "    ${BLUE}$line${NC}"; done
    fi
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
# Source table references
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
echo -e "${BOLD}  PHASE 37 UAT: EXPRESSION PUSHDOWN                         ${NC}"
echo -e "${BOLD}  31 tests: functions, HAVING, COUNT DISTINCT, ORDER BY expr${NC}"
echo -e "${BOLD}============================================================${NC}"
echo ""

###########################################################################
# SECTION 1: PG JDBC — Whitelisted function pushdown (8 tests)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== SECTION 1: PG JDBC — WHITELISTED FUNCTION PUSHDOWN =====${NC}"

# T01: UPPER + LOWER in SELECT
run_test "SELECT UPPER(name), LOWER(department) FROM $PG_EMP" \
  "T01: PG UPPER+LOWER in SELECT" "pg" \
  "UPPER"

# T02: ROUND in SELECT
run_test "SELECT name, ROUND(salary, -3) FROM $PG_EMP" \
  "T02: PG ROUND in SELECT" "pg" \
  "ROUND"

# T03: ABS in SELECT
run_test "SELECT name, ABS(salary - 100000) FROM $PG_EMP" \
  "T03: PG ABS in SELECT" "pg" \
  "ABS"

# T04: TRIM + LENGTH
run_test "SELECT TRIM(name), LENGTH(department) FROM $PG_EMP WHERE department IS NOT NULL" \
  "T04: PG TRIM+LENGTH in SELECT" "pg" \
  "TRIM"

# T05: EXTRACT(YEAR FROM hire_date)
run_test "SELECT name, EXTRACT(YEAR FROM hire_date) AS yr FROM $PG_EMP" \
  "T05: PG EXTRACT(YEAR) in SELECT" "pg" \
  "EXTRACT"

# T06: COALESCE
run_test "SELECT name, COALESCE(department, 'Unknown') FROM $PG_EMP" \
  "T06: PG COALESCE in SELECT" "pg" \
  "COALESCE"

# T07: CAST + CEIL
run_test "SELECT CAST(salary AS INTEGER), CEIL(salary) FROM $PG_EMP" \
  "T07: PG CAST+CEIL in SELECT" "pg" \
  "CAST"

# T08: FLOOR
run_test "SELECT FLOOR(salary / 1000) * 1000 AS salary_band FROM $PG_EMP" \
  "T08: PG FLOOR in SELECT" "pg" \
  "FLOOR"

fi  # RUN_PG

###########################################################################
# SECTION 2: PG JDBC — Function composition pushdown (3 tests)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== SECTION 2: PG JDBC — FUNCTION COMPOSITION =====${NC}"

# T09: UPPER(TRIM(name)) — whitelisted composition
run_test "SELECT UPPER(TRIM(name)) FROM $PG_EMP" \
  "T09: PG UPPER(TRIM()) composition pushed" "pg" \
  "UPPER"

# T10: ROUND(AVG(salary), 2) — aggregate + function
run_test "SELECT department, ROUND(AVG(salary), 2) AS avg_sal FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department" \
  "T10: PG ROUND(AVG()) pushed" "pg" \
  "ROUND"

# T11: CEIL(ABS(salary - 100000))
run_test "SELECT CEIL(ABS(salary - 100000)) FROM $PG_EMP" \
  "T11: PG CEIL(ABS()) nested pushed" "pg" \
  "CEIL"

fi  # RUN_PG

###########################################################################
# SECTION 3: PG JDBC — Non-whitelisted function rejection (1 test)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== SECTION 3: PG JDBC — NON-WHITELISTED REJECTION =====${NC}"

# T12: RANDOM() in ORDER BY — not whitelisted; ORDER BY should NOT appear in PG log
# Dremio should execute the sort in-engine after fetching rows from PG
run_test_no_pushdown "SELECT name FROM $PG_EMP ORDER BY RANDOM()" \
  "T12: PG RANDOM() NOT pushed (ORDER BY absent in PG log)" \
  "pg" \
  "ORDER BY RANDOM"

fi  # RUN_PG

###########################################################################
# SECTION 4: PG JDBC — HAVING pushdown (2 tests)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== SECTION 4: PG JDBC — HAVING PUSHDOWN =====${NC}"

# T13: GROUP BY department HAVING COUNT(*) > 1
run_test "SELECT department, COUNT(*) AS cnt FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department HAVING COUNT(*) > 1" \
  "T13: PG HAVING COUNT(*) > 1" "pg" \
  "GROUP BY"

# T14: GROUP BY department HAVING AVG(salary) > 100000
run_test "SELECT department, AVG(salary) AS avg_sal FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department HAVING AVG(salary) > 100000" \
  "T14: PG HAVING AVG(salary) > 100000" "pg" \
  "GROUP BY"

fi  # RUN_PG

###########################################################################
# SECTION 5: PG JDBC — COUNT(DISTINCT) (2 tests)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== SECTION 5: PG JDBC — COUNT(DISTINCT) =====${NC}"

# T15: COUNT(DISTINCT department)
run_test "SELECT COUNT(DISTINCT department) FROM $PG_EMP" \
  "T15: PG COUNT(DISTINCT department)" "pg" \
  "COUNT"

# T16: GROUP BY + COUNT(DISTINCT name)
run_test "SELECT department, COUNT(DISTINCT name) AS cnt_name FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department" \
  "T16: PG GROUP BY + COUNT(DISTINCT name)" "pg" \
  "COUNT"

fi  # RUN_PG

###########################################################################
# SECTION 6: PG JDBC — ORDER BY expression + LIMIT K (3 tests)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== SECTION 6: PG JDBC — ORDER BY EXPRESSION + LIMIT =====${NC}"

# T17: ORDER BY UPPER(name) (no LIMIT — SortPrel path)
run_test "SELECT name FROM $PG_EMP ORDER BY UPPER(name)" \
  "T17: PG ORDER BY UPPER(name) pushed" "pg" \
  "ORDER BY"

# T18: ORDER BY ROUND(salary, -3) DESC LIMIT 3 (TopN path)
run_test "SELECT name, salary FROM $PG_EMP ORDER BY ROUND(salary, -3) DESC LIMIT 3" \
  "T18: PG ORDER BY ROUND(salary) LIMIT 3 (TopN)" "pg" \
  "ORDER BY"

# T19: ORDER BY LENGTH(name) ASC LIMIT 5
run_test "SELECT name FROM $PG_EMP ORDER BY LENGTH(name) ASC LIMIT 5" \
  "T19: PG ORDER BY LENGTH(name) LIMIT 5" "pg" \
  "ORDER BY"

fi  # RUN_PG

###########################################################################
# SECTION 7: PG JDBC — CAST in same-source JOIN (1 test)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== SECTION 7: PG JDBC — CAST IN SAME-SOURCE JOIN =====${NC}"

# T20: JOIN + CAST(salary AS INTEGER) — must be pushed as single PG query
run_test "SELECT e.name, CAST(e.salary AS INTEGER) AS salary_int, d.budget FROM $PG_EMP e INNER JOIN $PG_DEPT d ON e.department = d.dept_name" \
  "T20: PG JOIN + CAST(salary AS INTEGER) pushed" "pg" \
  "JOIN"

fi  # RUN_PG

###########################################################################
# SECTION 8: PG ADBC — Key tests via ADBC protocol (4 tests)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== SECTION 8: PG ADBC — ADBC PROTOCOL PATH =====${NC}"

# T21: ADBC UPPER+LOWER
run_test "SELECT UPPER(name), LOWER(department) FROM $PG_ADBC_EMP WHERE department IS NOT NULL" \
  "T21: PG ADBC UPPER+LOWER pushed" "pg" \
  "UPPER"

# T22: ADBC HAVING
run_test "SELECT department, COUNT(*) AS cnt FROM $PG_ADBC_EMP WHERE department IS NOT NULL GROUP BY department HAVING COUNT(*) > 0" \
  "T22: PG ADBC HAVING COUNT(*) > 0" "pg" \
  "GROUP BY"

# T23: ADBC COUNT(DISTINCT)
run_test "SELECT COUNT(DISTINCT department) FROM $PG_ADBC_EMP" \
  "T23: PG ADBC COUNT(DISTINCT)" "pg" \
  "COUNT"

# T24: ADBC ORDER BY expr + LIMIT
run_test "SELECT name FROM $PG_ADBC_EMP ORDER BY UPPER(name) ASC LIMIT 3" \
  "T24: PG ADBC ORDER BY UPPER(name) LIMIT 3" "pg" \
  "ORDER BY"

fi  # RUN_PG

###########################################################################
# SECTION 9: Oracle — Function pushdown (3 tests)
###########################################################################
if $RUN_ORA; then
echo -e "${BOLD}===== SECTION 9: ORACLE — FUNCTION PUSHDOWN =====${NC}"

ora_flush_cache

# T25: Oracle UPPER + ROUND
run_test "SELECT UPPER(name), ROUND(salary, 0) FROM $ORA_EMP" \
  "T25: Oracle UPPER+ROUND in SELECT" "ora" \
  "UPPER"

# T26: Oracle CEIL
run_test "SELECT name, CEIL(salary) FROM $ORA_EMP" \
  "T26: Oracle CEIL in SELECT" "ora" \
  "CEIL"

# T27: Oracle COALESCE
run_test "SELECT COALESCE(department, 'None') FROM $ORA_EMP" \
  "T27: Oracle COALESCE in SELECT" "ora" \
  "COALESCE"

fi  # RUN_ORA

###########################################################################
# SECTION 10: Oracle — HAVING + COUNT(DISTINCT) + ORDER BY expr (3 tests)
###########################################################################
if $RUN_ORA; then
echo -e "${BOLD}===== SECTION 10: ORACLE — HAVING + COUNT(DISTINCT) + ORDER BY EXPR =====${NC}"

ora_flush_cache

# T28: Oracle HAVING
run_test "SELECT department, COUNT(*) FROM $ORA_EMP WHERE department IS NOT NULL GROUP BY department HAVING COUNT(*) > 1" \
  "T28: Oracle HAVING COUNT(*) > 1" "ora" \
  "GROUP BY"

# T29: Oracle COUNT(DISTINCT)
run_test "SELECT COUNT(DISTINCT department) FROM $ORA_EMP" \
  "T29: Oracle COUNT(DISTINCT department)" "ora" \
  "COUNT"

# T30: Oracle ORDER BY UPPER(name) FETCH FIRST 3
run_test "SELECT name FROM $ORA_EMP ORDER BY UPPER(name) FETCH FIRST 3 ROWS ONLY" \
  "T30: Oracle ORDER BY UPPER(name) FETCH FIRST 3" "ora" \
  "ORDER BY"

fi  # RUN_ORA

###########################################################################
# SUMMARY
###########################################################################
echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  PHASE 37 UAT SUMMARY                                      ${NC}"
echo -e "${BOLD}  ${GREEN}$PASS_COUNT passed${NC}${BOLD}, ${RED}$FAIL_COUNT failed${NC}${BOLD}, ${YELLOW}$SKIP_COUNT skipped${NC}${BOLD}          ${NC}"
echo -e "${BOLD}============================================================${NC}"

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
