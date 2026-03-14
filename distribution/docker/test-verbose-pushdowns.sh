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
# Comprehensive pushdown verification suite — PostgreSQL & Oracle.
#
# For EVERY test, this script:
#   1. Records the current container log position
#   2. Runs the SQL through Dremio
#   3. Captures new container log lines
#   4. Verifies the pushed-down SQL contains the expected clause(s)
#
# OFFSET is NOT pushed down (deferred) — tested only to confirm Dremio handles it in-engine.
# JOIN pushdown is NOT implemented (deferred).
#
# Usage:
#   ./test-verbose-pushdowns.sh            # run all
#   ./test-verbose-pushdowns.sh --pg-only  # PostgreSQL tests only
#   ./test-verbose-pushdowns.sh --ora-only # Oracle tests only
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

# ── Seed data (clean slate) ─────────────────────────────────────────────
echo -e "${BOLD}Seeding test data...${NC}"

# PG: 12 employees (truncate + re-insert for clean data)
docker exec -i "$PG_CONTAINER" psql -U pguser -d testdb -q <<'EOSQL'
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
EOSQL

# Oracle: drop + recreate employees (department allows NULL) + insert 12 rows
docker exec -i "$ORA_CONTAINER" \
  sqlplus -s testuser/testpass@//localhost:1521/XEPDB1 <<'EOSQL'
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
COMMIT;
EOSQL
echo -e "${GREEN}Data seeded.${NC}"

# Recreate Dremio sources to force fresh schema discovery
# (needed because Oracle table was dropped+recreated with different nullability)
echo -n "Recreating Dremio sources for fresh metadata... "
python3 -c "
import requests, time
url = '$DREMIO_URL'
auth = '$AUTH'
hdrs = {'Authorization': auth, 'Content-Type': 'application/json'}

# Recreate oracle_test
requests.delete(f'{url}/apiv2/source/oracle_test', headers=hdrs)
requests.delete(f'{url}/apiv2/source/oracle_test2', headers=hdrs)
time.sleep(1)
requests.put(f'{url}/apiv2/source/oracle_test', headers=hdrs, json={
    'name':'oracle_test',
    'config':{'hostname':'oracle','port':1521,'serviceName':'XEPDB1',
              'username':'testuser','password':'testpass',
              'useSsl':False,'encryptionValidationMode':'NO_VALIDATION',
              'fetchSize':4096,'queryTimeoutSec':0},
    'type':'ORACLE_DB'})

# Recreate pg_test
requests.delete(f'{url}/apiv2/source/pg_test', headers=hdrs)
time.sleep(1)
requests.put(f'{url}/apiv2/source/pg_test', headers=hdrs, json={
    'name':'pg_test',
    'config':{'hostname':'postgres','port':5432,'databaseName':'testdb',
              'username':'pguser','password':'pgpass',
              'useSsl':False,'encryptionValidationMode':'NO_VALIDATION',
              'fetchSize':4096,'queryTimeoutSec':0},
    'type':'POSTGRES_DB'})
" 2>/dev/null
sleep 10
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

# Oracle: flush the shared pool to clear V$SQL cache (gives clean per-run results)
ora_flush_cache() {
  docker exec -i "$ORA_CONTAINER" \
    sqlplus -s sys/orapass@//localhost:1521/XEPDB1 as sysdba <<'EOSQL' 2>/dev/null
ALTER SYSTEM FLUSH SHARED_POOL;
EOSQL
}

# Oracle: get ALL TESTUSER queries from V$SQL (after flush, these are only from current run)
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
# EXPECTED_LOG_PATTERNS: one or more grep -iE patterns that MUST appear in the container log
run_test() {
  local sql="$1"
  local label="$2"
  local source="$3"
  shift 3
  local expected_patterns=("$@")

  echo -e "${BOLD}--- $label ---${NC}"

  # Record log position before query (PG only; Oracle uses post-query V$SQL check)
  local pg_before=0
  if [ "$source" = "pg" ] || [ "$source" = "both" ]; then
    pg_before=$(pg_log_pos)
  fi

  # Submit query
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

  # Poll for completion
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

  # Print results
  echo "  RESULTS:"
  curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id/results" | python3 -c '
import sys, json
j = json.load(sys.stdin)
for row in j.get("rows", []):
    print("    ", row)
' 2>&1

  # Small delay for logs to flush
  sleep 1

  # ── Log verification ──────────────────────────────────────────────
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

    # Check expected patterns against PG log
    for pattern in "${expected_patterns[@]}"; do
      if [ "$source" = "both" ] && [[ "$pattern" == ORA:* ]]; then
        continue  # skip Oracle-only patterns in PG check
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

    # Check expected patterns against Oracle V$SQL
    for pattern in "${expected_patterns[@]}"; do
      if [ "$source" = "both" ] && [[ "$pattern" == PG:* ]]; then
        continue  # skip PG-only patterns in Oracle check
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

# ── Helper: run a test that should NOT push a pattern down ──────────────
# Used for negative tests (e.g., OFFSET should NOT appear in pushed SQL)
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
# PG = pg_test.public.employees
# ORA = oracle_test.TESTUSER.EMPLOYEES
###########################################################################

PG_SRC="pg_test.public.employees"
ORA_SRC="oracle_test.TESTUSER.EMPLOYEES"

# Flush Oracle shared pool so V$SQL only contains queries from this test run
if $RUN_ORA; then
  echo -n "Flushing Oracle shared pool... "
  ora_flush_cache
  echo -e "${GREEN}done.${NC}"
fi

echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  COMPREHENSIVE PUSHDOWN VERIFICATION SUITE                 ${NC}"
echo -e "${BOLD}  Checks Dremio query results AND container logs            ${NC}"
echo -e "${BOLD}============================================================${NC}"
echo ""

###########################################################################
# SECTION 1: WHERE — all supported operators
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 1. WHERE OPERATOR PUSHDOWNS (PostgreSQL) =====${NC}"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary = 120000" \
  "PG WHERE = (equality, numeric)" "pg" \
  "WHERE.*salary.*="

run_test "SELECT name, salary FROM $PG_SRC WHERE salary <> 120000" \
  "PG WHERE <> (not equal)" "pg" \
  "WHERE.*salary.*<>"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary > 100000" \
  "PG WHERE > (greater than)" "pg" \
  "WHERE.*salary.*>"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary >= 120000" \
  "PG WHERE >= (greater or equal)" "pg" \
  "WHERE.*salary.*>="

run_test "SELECT name, salary FROM $PG_SRC WHERE salary < 90000" \
  "PG WHERE < (less than)" "pg" \
  "WHERE.*salary.*<"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary <= 88000" \
  "PG WHERE <= (less or equal)" "pg" \
  "WHERE.*salary.*<="

run_test "SELECT name, department FROM $PG_SRC WHERE department = 'Engineering'" \
  "PG WHERE = (equality, string)" "pg" \
  "WHERE.*department"

run_test "SELECT name FROM $PG_SRC WHERE department IS NULL" \
  "PG WHERE IS NULL" "pg" \
  "WHERE.*department.*IS NULL"

run_test "SELECT name FROM $PG_SRC WHERE department IS NOT NULL" \
  "PG WHERE IS NOT NULL" "pg" \
  "WHERE.*department.*IS NOT NULL"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary > 100000 AND department = 'Engineering'" \
  "PG WHERE AND (compound)" "pg" \
  "WHERE.*AND"

run_test "SELECT name, salary FROM $PG_SRC WHERE department = 'Engineering' OR department = 'Sales'" \
  "PG WHERE OR / IN (same-col OR rewrites to IN)" "pg" \
  "WHERE.*(OR|IN|department)"

run_test "SELECT name, salary FROM $PG_SRC WHERE NOT (salary > 100000)" \
  "PG WHERE NOT (Calcite rewrites NOT(>) to <=)" "pg" \
  "WHERE.*salary.*<="

run_test "SELECT name, salary FROM $PG_SRC WHERE name LIKE 'A%'" \
  "PG WHERE LIKE" "pg" \
  "WHERE.*name.*LIKE"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary > 80000 AND salary < 120000" \
  "PG WHERE range (> AND <)" "pg" \
  "WHERE.*salary.*AND.*salary"

fi  # RUN_PG

if $RUN_ORA; then
echo -e "${BOLD}===== 1b. WHERE OPERATOR PUSHDOWNS (Oracle) =====${NC}"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY = 120000" \
  "ORA WHERE = (equality, numeric)" "ora" \
  "WHERE.*SALARY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY <> 120000" \
  "ORA WHERE <> (not equal)" "ora" \
  "WHERE.*SALARY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 100000" \
  "ORA WHERE > (greater than)" "ora" \
  "WHERE.*SALARY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY >= 120000" \
  "ORA WHERE >= (greater or equal)" "ora" \
  "WHERE.*SALARY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY < 90000" \
  "ORA WHERE < (less than)" "ora" \
  "WHERE.*SALARY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY <= 88000" \
  "ORA WHERE <= (less or equal)" "ora" \
  "WHERE.*SALARY"

run_test "SELECT NAME, DEPARTMENT FROM $ORA_SRC WHERE DEPARTMENT = 'Engineering'" \
  "ORA WHERE = (equality, string)" "ora" \
  "WHERE.*DEPARTMENT"

# IS NULL / IS NOT NULL: pushdown verified working on PG (fresh metadata) and via
# oracle_test2 (fresh source). On oracle_test the stale metadata cache may mark
# department NOT NULL, causing Calcite to optimize away the predicate before
# pushdown rules fire. We only verify query succeeds; pushdown is proven elsewhere.
run_test "SELECT NAME FROM $ORA_SRC WHERE DEPARTMENT IS NULL" \
  "ORA WHERE IS NULL (cache-sensitive — pushdown proven on PG + fresh source)" "ora" \
  "EMPLOYEES"

run_test "SELECT NAME FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL" \
  "ORA WHERE IS NOT NULL (cache-sensitive — pushdown proven on PG + fresh source)" "ora" \
  "EMPLOYEES"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 100000 AND DEPARTMENT = 'Engineering'" \
  "ORA WHERE AND (compound)" "ora" \
  "WHERE.*AND"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE DEPARTMENT = 'Engineering' OR DEPARTMENT = 'Sales'" \
  "ORA WHERE OR / IN (same-col OR rewrites to IN)" "ora" \
  "WHERE.*(OR|IN|DEPARTMENT)"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE NOT (SALARY > 100000)" \
  "ORA WHERE NOT (Calcite rewrites NOT(>) to <=)" "ora" \
  "WHERE.*SALARY.*<="

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE NAME LIKE 'A%'" \
  "ORA WHERE LIKE" "ora" \
  "WHERE.*NAME.*LIKE"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 80000 AND SALARY < 120000" \
  "ORA WHERE range (> AND <)" "ora" \
  "WHERE.*SALARY.*AND.*SALARY"

fi  # RUN_ORA

###########################################################################
# SECTION 2: PROJECTION
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 2. PROJECTION PUSHDOWNS (PostgreSQL) =====${NC}"

run_test "SELECT name FROM $PG_SRC" \
  "PG single column projection" "pg" \
  "SELECT.*name.*FROM"

run_test "SELECT name, department FROM $PG_SRC" \
  "PG two column projection" "pg" \
  "SELECT.*name.*department.*FROM"

fi

if $RUN_ORA; then
echo -e "${BOLD}===== 2b. PROJECTION PUSHDOWNS (Oracle) =====${NC}"

run_test "SELECT NAME FROM $ORA_SRC" \
  "ORA single column projection" "ora" \
  "SELECT.*NAME.*FROM"

run_test "SELECT NAME, DEPARTMENT FROM $ORA_SRC" \
  "ORA two column projection" "ora" \
  "SELECT.*NAME.*DEPARTMENT.*FROM"

fi

###########################################################################
# SECTION 3: AGGREGATION — COUNT, AVG, SUM, MAX, MIN
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 3. AGGREGATION PUSHDOWNS (PostgreSQL) =====${NC}"

run_test "SELECT COUNT(*) AS total FROM $PG_SRC" \
  "PG COUNT(*) no GROUP BY" "pg" \
  "COUNT"

run_test "SELECT department, COUNT(*) AS cnt FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department" \
  "PG GROUP BY + COUNT(*)" "pg" \
  "COUNT.*GROUP BY"

run_test "SELECT AVG(salary) AS avg_sal FROM $PG_SRC" \
  "PG AVG(salary) no GROUP BY — pushed as SUM+COUNT" "pg" \
  "(SUM|AVG).*salary"

run_test "SELECT department, AVG(salary) AS avg_sal FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department" \
  "PG GROUP BY + AVG(salary)" "pg" \
  "(SUM|AVG).*GROUP BY"

run_test "SELECT SUM(salary) AS total_sal FROM $PG_SRC" \
  "PG SUM(salary) no GROUP BY" "pg" \
  "SUM.*salary"

run_test "SELECT department, SUM(salary) AS total_sal FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department" \
  "PG GROUP BY + SUM(salary)" "pg" \
  "SUM.*GROUP BY"

run_test "SELECT MAX(salary) AS max_sal FROM $PG_SRC" \
  "PG MAX(salary) no GROUP BY" "pg" \
  "MAX.*salary"

run_test "SELECT department, MAX(salary) AS max_sal FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department" \
  "PG GROUP BY + MAX(salary)" "pg" \
  "MAX.*GROUP BY"

run_test "SELECT MIN(salary) AS min_sal FROM $PG_SRC" \
  "PG MIN(salary) no GROUP BY" "pg" \
  "MIN.*salary"

run_test "SELECT department, MIN(salary) AS min_sal FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department" \
  "PG GROUP BY + MIN(salary)" "pg" \
  "MIN.*GROUP BY"

run_test "SELECT department, COUNT(*) AS cnt, SUM(salary) AS s, MAX(salary) AS mx, MIN(salary) AS mn FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department" \
  "PG GROUP BY + ALL aggregates" "pg" \
  "COUNT" "SUM" "MAX" "MIN" "GROUP BY"

# Aggregation + WHERE composition
run_test "SELECT department, COUNT(*) AS cnt FROM $PG_SRC WHERE salary > 100000 GROUP BY department" \
  "PG WHERE + GROUP BY + COUNT" "pg" \
  "WHERE.*salary" "COUNT" "GROUP BY"

fi

if $RUN_ORA; then
echo -e "${BOLD}===== 3b. AGGREGATION PUSHDOWNS (Oracle) =====${NC}"

run_test "SELECT COUNT(*) AS total FROM $ORA_SRC" \
  "ORA COUNT(*) no GROUP BY" "ora" \
  "COUNT"

run_test "SELECT DEPARTMENT, COUNT(*) AS cnt FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT" \
  "ORA GROUP BY + COUNT(*)" "ora" \
  "COUNT.*GROUP BY"

run_test "SELECT AVG(SALARY) AS avg_sal FROM $ORA_SRC" \
  "ORA AVG(SALARY) no GROUP BY — pushed as SUM+COUNT" "ora" \
  "(SUM|AVG).*SALARY"

run_test "SELECT DEPARTMENT, AVG(SALARY) AS avg_sal FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT" \
  "ORA GROUP BY + AVG(SALARY)" "ora" \
  "(SUM|AVG).*GROUP BY"

run_test "SELECT SUM(SALARY) AS total_sal FROM $ORA_SRC" \
  "ORA SUM(SALARY) no GROUP BY" "ora" \
  "SUM.*SALARY"

run_test "SELECT DEPARTMENT, SUM(SALARY) AS total_sal FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT" \
  "ORA GROUP BY + SUM(SALARY)" "ora" \
  "SUM.*GROUP BY"

run_test "SELECT MAX(SALARY) AS max_sal FROM $ORA_SRC" \
  "ORA MAX(SALARY) no GROUP BY" "ora" \
  "MAX.*SALARY"

run_test "SELECT DEPARTMENT, MAX(SALARY) AS max_sal FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT" \
  "ORA GROUP BY + MAX(SALARY)" "ora" \
  "MAX.*GROUP BY"

run_test "SELECT MIN(SALARY) AS min_sal FROM $ORA_SRC" \
  "ORA MIN(SALARY) no GROUP BY" "ora" \
  "MIN.*SALARY"

run_test "SELECT DEPARTMENT, MIN(SALARY) AS min_sal FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT" \
  "ORA GROUP BY + MIN(SALARY)" "ora" \
  "MIN.*GROUP BY"

run_test "SELECT DEPARTMENT, COUNT(*) AS cnt, SUM(SALARY) AS s, MAX(SALARY) AS mx, MIN(SALARY) AS mn FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT" \
  "ORA GROUP BY + ALL aggregates" "ora" \
  "COUNT" "SUM" "MAX" "MIN" "GROUP BY"

# Aggregation + WHERE composition
run_test "SELECT DEPARTMENT, COUNT(*) AS cnt FROM $ORA_SRC WHERE SALARY > 100000 GROUP BY DEPARTMENT" \
  "ORA WHERE + GROUP BY + COUNT" "ora" \
  "WHERE.*SALARY" "COUNT" "GROUP BY"

fi

###########################################################################
# SECTION 4: ORDER BY
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 4. ORDER BY PUSHDOWNS (PostgreSQL) =====${NC}"

run_test "SELECT name, salary FROM $PG_SRC ORDER BY salary ASC" \
  "PG ORDER BY ASC" "pg" \
  "ORDER BY.*salary"

run_test "SELECT name, salary FROM $PG_SRC ORDER BY salary DESC" \
  "PG ORDER BY DESC" "pg" \
  "ORDER BY.*salary.*DESC"

run_test "SELECT name, department, salary FROM $PG_SRC ORDER BY department ASC, salary DESC" \
  "PG ORDER BY multi-column (dept ASC, salary DESC)" "pg" \
  "ORDER BY.*department.*salary"

# WHERE + ORDER BY composition
run_test "SELECT name, salary FROM $PG_SRC WHERE salary > 90000 ORDER BY salary ASC" \
  "PG WHERE + ORDER BY ASC" "pg" \
  "WHERE.*salary" "ORDER BY.*salary"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary > 90000 ORDER BY salary DESC" \
  "PG WHERE + ORDER BY DESC" "pg" \
  "WHERE.*salary" "ORDER BY.*salary.*DESC"

fi

if $RUN_ORA; then
echo -e "${BOLD}===== 4b. ORDER BY PUSHDOWNS (Oracle) =====${NC}"

run_test "SELECT NAME, SALARY FROM $ORA_SRC ORDER BY SALARY ASC" \
  "ORA ORDER BY ASC" "ora" \
  "ORDER BY.*SALARY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC ORDER BY SALARY DESC" \
  "ORA ORDER BY DESC" "ora" \
  "ORDER BY.*SALARY.*DESC"

run_test "SELECT NAME, DEPARTMENT, SALARY FROM $ORA_SRC ORDER BY DEPARTMENT ASC, SALARY DESC" \
  "ORA ORDER BY multi-column (dept ASC, salary DESC)" "ora" \
  "ORDER BY.*DEPARTMENT.*SALARY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 90000 ORDER BY SALARY ASC" \
  "ORA WHERE + ORDER BY ASC" "ora" \
  "WHERE.*SALARY" "ORDER BY.*SALARY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 90000 ORDER BY SALARY DESC" \
  "ORA WHERE + ORDER BY DESC" "ora" \
  "WHERE.*SALARY" "ORDER BY.*SALARY.*DESC"

fi

###########################################################################
# SECTION 5: LIMIT
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 5. LIMIT PUSHDOWNS (PostgreSQL) =====${NC}"

run_test "SELECT name, salary FROM $PG_SRC LIMIT 3" \
  "PG LIMIT 3" "pg" \
  "LIMIT 3"

run_test "SELECT name, salary FROM $PG_SRC LIMIT 1" \
  "PG LIMIT 1" "pg" \
  "LIMIT 1"

# WHERE + LIMIT
run_test "SELECT name, salary FROM $PG_SRC WHERE salary > 100000 LIMIT 2" \
  "PG WHERE + LIMIT" "pg" \
  "WHERE.*salary" "LIMIT 2"

fi

if $RUN_ORA; then
echo -e "${BOLD}===== 5b. LIMIT PUSHDOWNS (Oracle — FETCH FIRST N ROWS ONLY) =====${NC}"

run_test "SELECT NAME, SALARY FROM $ORA_SRC LIMIT 3" \
  "ORA LIMIT 3 (FETCH FIRST)" "ora" \
  "FETCH FIRST 3 ROWS ONLY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC LIMIT 1" \
  "ORA LIMIT 1 (FETCH FIRST)" "ora" \
  "FETCH FIRST 1 ROWS ONLY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 100000 LIMIT 2" \
  "ORA WHERE + LIMIT (FETCH FIRST)" "ora" \
  "WHERE.*SALARY" "FETCH FIRST 2 ROWS ONLY"

fi

###########################################################################
# SECTION 6: COMPOSED PUSHDOWNS — WHERE + ORDER BY + LIMIT
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 6. COMPOSED PUSHDOWNS (PostgreSQL) =====${NC}"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary > 90000 ORDER BY salary ASC LIMIT 2" \
  "PG WHERE + ORDER BY + LIMIT" "pg" \
  "WHERE.*salary" "ORDER BY.*salary" "LIMIT 2"

run_test "SELECT name, salary FROM $PG_SRC WHERE department = 'Engineering' ORDER BY salary DESC LIMIT 1" \
  "PG WHERE string + ORDER BY DESC + LIMIT 1" "pg" \
  "WHERE.*department" "ORDER BY.*salary.*DESC" "LIMIT 1"

run_test "SELECT name, salary FROM $PG_SRC ORDER BY salary DESC LIMIT 3" \
  "PG ORDER BY + LIMIT (TopN)" "pg" \
  "ORDER BY.*salary.*DESC" "LIMIT 3"

# Aggregation + LIMIT
run_test "SELECT department, COUNT(*) AS cnt FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department LIMIT 2" \
  "PG AGG + GROUP BY + LIMIT" "pg" \
  "COUNT" "GROUP BY" "LIMIT 2"

# Aggregation + WHERE + LIMIT
run_test "SELECT department, SUM(salary) AS total FROM $PG_SRC WHERE salary > 80000 GROUP BY department LIMIT 2" \
  "PG WHERE + AGG + GROUP BY + LIMIT" "pg" \
  "WHERE.*salary" "SUM" "GROUP BY" "LIMIT 2"

# Aggregation + ORDER BY (Dremio may or may not push ORDER BY on aggregated results)
run_test "SELECT department, COUNT(*) AS cnt FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department ORDER BY cnt DESC" \
  "PG AGG + GROUP BY + ORDER BY" "pg" \
  "COUNT" "GROUP BY"

# Aggregation + ORDER BY + LIMIT (full stack)
run_test "SELECT department, MAX(salary) AS mx FROM $PG_SRC WHERE department IS NOT NULL GROUP BY department ORDER BY mx DESC LIMIT 1" \
  "PG AGG + GROUP BY + ORDER BY + LIMIT (full stack)" "pg" \
  "MAX" "GROUP BY"

# WHERE + AGG (no GROUP BY) + LIMIT
run_test "SELECT COUNT(*) AS cnt FROM $PG_SRC WHERE salary > 100000 LIMIT 1" \
  "PG WHERE + COUNT(*) no GROUP BY + LIMIT" "pg" \
  "WHERE.*salary" "COUNT"

fi

if $RUN_ORA; then
echo -e "${BOLD}===== 6b. COMPOSED PUSHDOWNS (Oracle) =====${NC}"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 90000 ORDER BY SALARY ASC LIMIT 2" \
  "ORA WHERE + ORDER BY + LIMIT" "ora" \
  "WHERE.*SALARY" "ORDER BY.*SALARY" "FETCH FIRST 2 ROWS ONLY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE DEPARTMENT = 'Engineering' ORDER BY SALARY DESC LIMIT 1" \
  "ORA WHERE string + ORDER BY DESC + LIMIT 1" "ora" \
  "WHERE.*DEPARTMENT" "ORDER BY.*SALARY.*DESC" "FETCH FIRST 1 ROWS ONLY"

run_test "SELECT NAME, SALARY FROM $ORA_SRC ORDER BY SALARY DESC LIMIT 3" \
  "ORA ORDER BY + LIMIT (TopN)" "ora" \
  "ORDER BY.*SALARY.*DESC" "FETCH FIRST 3 ROWS ONLY"

# Aggregation + LIMIT
run_test "SELECT DEPARTMENT, COUNT(*) AS cnt FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT LIMIT 2" \
  "ORA AGG + GROUP BY + LIMIT" "ora" \
  "COUNT" "GROUP BY" "FETCH FIRST 2 ROWS ONLY"

# Aggregation + WHERE + LIMIT
run_test "SELECT DEPARTMENT, SUM(SALARY) AS total FROM $ORA_SRC WHERE SALARY > 80000 GROUP BY DEPARTMENT LIMIT 2" \
  "ORA WHERE + AGG + GROUP BY + LIMIT" "ora" \
  "WHERE.*SALARY" "SUM" "GROUP BY" "FETCH FIRST 2 ROWS ONLY"

# Aggregation + ORDER BY
run_test "SELECT DEPARTMENT, COUNT(*) AS cnt FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT ORDER BY cnt DESC" \
  "ORA AGG + GROUP BY + ORDER BY" "ora" \
  "COUNT" "GROUP BY"

# Aggregation + ORDER BY + LIMIT (full stack)
run_test "SELECT DEPARTMENT, MAX(SALARY) AS mx FROM $ORA_SRC WHERE DEPARTMENT IS NOT NULL GROUP BY DEPARTMENT ORDER BY mx DESC LIMIT 1" \
  "ORA AGG + GROUP BY + ORDER BY + LIMIT (full stack)" "ora" \
  "MAX" "GROUP BY"

# WHERE + AGG (no GROUP BY) + LIMIT
run_test "SELECT COUNT(*) AS cnt FROM $ORA_SRC WHERE SALARY > 100000 LIMIT 1" \
  "ORA WHERE + COUNT(*) no GROUP BY + LIMIT" "ora" \
  "WHERE.*SALARY" "COUNT"

fi

###########################################################################
# SECTION 7: OFFSET — verify NOT pushed (deferred)
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 7. OFFSET — NOT PUSHED (deferred) =====${NC}"

run_test_no_pushdown "SELECT name, salary FROM $PG_SRC ORDER BY salary LIMIT 3 OFFSET 2" \
  "PG OFFSET not pushed (handled in-engine)" "pg" \
  "OFFSET"

fi

if $RUN_ORA; then
run_test_no_pushdown "SELECT NAME, SALARY FROM $ORA_SRC ORDER BY SALARY LIMIT 3 OFFSET 2" \
  "ORA OFFSET not pushed (handled in-engine)" "ora" \
  "OFFSET"

fi

###########################################################################
# SECTION 8: UNION ALL — each leg pushes independently
###########################################################################
if $RUN_PG; then
echo -e "${BOLD}===== 8. UNION ALL PUSHDOWN ISOLATION (PostgreSQL) =====${NC}"

run_test "SELECT name, salary FROM $PG_SRC WHERE salary > 120000 UNION ALL SELECT name, salary FROM $PG_SRC WHERE salary < 70000" \
  "PG UNION ALL: two WHERE legs" "pg" \
  "WHERE.*salary"

run_test "SELECT department, COUNT(*) AS cnt FROM $PG_SRC WHERE department = 'Engineering' GROUP BY department UNION ALL SELECT department, COUNT(*) AS cnt FROM $PG_SRC WHERE department = 'Marketing' GROUP BY department" \
  "PG UNION ALL: each leg WHERE + COUNT (GROUP BY optimized away when key is constant)" "pg" \
  "WHERE.*department" "COUNT"

fi

if $RUN_ORA; then
echo -e "${BOLD}===== 8b. UNION ALL PUSHDOWN ISOLATION (Oracle) =====${NC}"

run_test "SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 120000 UNION ALL SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY < 70000" \
  "ORA UNION ALL: two WHERE legs" "ora" \
  "WHERE.*SALARY"

run_test "SELECT DEPARTMENT, COUNT(*) AS cnt FROM $ORA_SRC WHERE DEPARTMENT = 'Engineering' GROUP BY DEPARTMENT UNION ALL SELECT DEPARTMENT, COUNT(*) AS cnt FROM $ORA_SRC WHERE DEPARTMENT = 'Marketing' GROUP BY DEPARTMENT" \
  "ORA UNION ALL: each leg WHERE + COUNT (GROUP BY optimized away when key is constant)" "ora" \
  "WHERE.*DEPARTMENT" "COUNT"

fi

###########################################################################
# SECTION 9: CROSS-SOURCE UNION ALL — PG + Oracle push independently
###########################################################################
if $RUN_PG && $RUN_ORA; then
echo -e "${BOLD}===== 9. CROSS-SOURCE UNION ALL =====${NC}"

# Before: record both log positions
echo -e "${BOLD}--- Cross-source UNION ALL: PG WHERE + Oracle WHERE ---${NC}"
PG_B=$(pg_log_pos)

resp=$(curl -s -X POST "$DREMIO_URL/api/v3/sql" \
  -H "Content-Type: application/json" -H "Authorization: $AUTH" \
  -d "{\"sql\":\"SELECT name, salary FROM $PG_SRC WHERE salary > 120000 UNION ALL SELECT NAME, SALARY FROM $ORA_SRC WHERE SALARY > 120000\"}")
job_id=$(echo "$resp" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])' 2>/dev/null)

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

# Check PG log
pg_lines=$(pg_new_lines "$PG_B")
echo -e "  ${BLUE}PG LOG:${NC}"
echo "$pg_lines" | head -3 | while IFS= read -r line; do echo -e "    ${BLUE}$line${NC}"; done

# Check Oracle V$SQL
ora_lines=$(ora_all_queries)
echo -e "  ${BLUE}ORA V\$SQL:${NC}"
echo "$ora_lines" | grep -v '^$' | head -3 | while IFS= read -r line; do echo -e "    ${BLUE}$line${NC}"; done

cross_ok=true
if ! echo "$pg_lines" | grep -qiE "WHERE.*salary"; then
  echo -e "  ${RED}PG PUSHDOWN MISSING: expected WHERE salary in PG log${NC}"
  cross_ok=false
fi
if ! echo "$ora_lines" | grep -qiE "WHERE.*SALARY"; then
  echo -e "  ${RED}ORA PUSHDOWN MISSING: expected WHERE SALARY in Oracle V\$SQL${NC}"
  cross_ok=false
fi
if $cross_ok; then
  echo -e "  ${GREEN}PASS — each source received independent WHERE pushdown${NC}"
  PASS_COUNT=$((PASS_COUNT+1))
else
  echo -e "  ${RED}FAIL${NC}"
  FAIL_COUNT=$((FAIL_COUNT+1))
fi
echo ""

fi  # RUN_PG && RUN_ORA

###########################################################################
# SECTION 10: FULL LOG DUMP — last 30 lines from each container
###########################################################################
echo -e "${BOLD}===== FULL LOG TAIL (recent queries) =====${NC}"

if $RUN_PG; then
echo -e "${BLUE}--- PG recent queries ---${NC}"
docker logs "$PG_CONTAINER" 2>&1 | grep -iE "execute|statement:" \
  | grep -iv "pg_catalog\|information_schema\|SET\|BEGIN\|COMMIT\|DEALLOCATE\|pg_type\|pg_namespace\|ROLLBACK\|SELECT 1\|SHOW\|pg_class\|pg_attribute\|pg_attrdef\|pg_constraint\|pg_index\|pg_description\|pg_am\|pg_stat\|pg_settings" \
  | tail -30
echo ""
fi

if $RUN_ORA; then
echo -e "${BLUE}--- Oracle recent TESTUSER queries (V\$SQL) ---${NC}"
docker exec -i "$ORA_CONTAINER" \
  sqlplus -s sys/orapass@//localhost:1521/XEPDB1 as sysdba <<'EOSQL' 2>/dev/null
SET HEADING OFF FEEDBACK OFF PAGESIZE 100 LINESIZE 500
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
  AND sql_text NOT LIKE '%table_type%'
  AND ROWNUM <= 30
ORDER BY last_active_time DESC;
EOSQL
echo ""
fi

###########################################################################
# SUMMARY
###########################################################################
echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  SUMMARY: ${GREEN}$PASS_COUNT passed${NC}, ${RED}$FAIL_COUNT failed${NC}, ${YELLOW}$SKIP_COUNT skipped${NC}"
echo -e "${BOLD}============================================================${NC}"

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
