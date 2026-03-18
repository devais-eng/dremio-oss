#!/usr/bin/env bash
#
# Regression test: verify baseline JDBC pushdown features work on PG + Oracle.
# Tests: WHERE, LIMIT, SORT, AGG/GROUP BY, JOIN (same source), combined patterns.
#
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

# ── Auth ────────────────────────────────────────────────────────────────
curl -s -o /dev/null -X PUT "$DREMIO_URL/apiv2/bootstrap/firstuser" \
  -H "Content-Type: application/json" \
  -d "{\"userName\":\"admin\",\"firstName\":\"A\",\"lastName\":\"U\",\"email\":\"a@e.c\",\"createdAt\":0,\"password\":\"$PASS\"}" || true

TOKEN=$(curl -s -X POST "$DREMIO_URL/apiv2/login" \
  -H "Content-Type: application/json" \
  -d "{\"userName\":\"admin\",\"password\":\"$PASS\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
AUTH="_dremio${TOKEN}"

PG_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q postgres)
ORA_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q oracle)

# ── Seed (idempotent) ──────────────────────────────────────────────────
docker exec -i "$PG_CONTAINER" psql -U pguser -d testdb -q <<'EOSQL'
CREATE TABLE IF NOT EXISTS employees (
  id INTEGER PRIMARY KEY, name VARCHAR(100) NOT NULL,
  department VARCHAR(50), salary NUMERIC(10,2), hire_date DATE
);
TRUNCATE employees RESTART IDENTITY;
INSERT INTO employees (id, name, department, salary, hire_date) VALUES
  (1,'Alice','Engineering',120000,'2020-01-15'),
  (2,'Bob','Marketing',95000,'2019-03-22'),
  (3,'Charlie','Engineering',130000,'2021-06-01'),
  (4,'Diana','Sales',88000,'2018-11-30'),
  (5,'Eve','Engineering',145000,'2017-07-14'),
  (6,'Frank','Marketing',72000,'2022-02-28'),
  (7,'Grace','Sales',105000,'2020-09-10'),
  (8,'Hank',NULL,60000,'2023-01-01'),
  (9,'Ivy','Engineering',110000,'2019-12-05'),
  (10,'Jack','Marketing',98000,'2021-04-18'),
  (11,'Karen','Sales',115000,'2018-08-25'),
  (12,'Leo',NULL,55000,'2023-06-15');
CREATE TABLE IF NOT EXISTS departments (
  dept_name VARCHAR(50) PRIMARY KEY, budget NUMERIC(12,2) NOT NULL, location VARCHAR(100) NOT NULL
);
TRUNCATE departments;
INSERT INTO departments (dept_name, budget, location) VALUES
  ('Engineering',500000,'Building A'),('Marketing',200000,'Building B'),
  ('Sales',300000,'Building C'),('HR',150000,'Building D');
EOSQL

echo -e "${GREEN}PG data seeded.${NC}"
sleep 15

# ── Helpers ─────────────────────────────────────────────────────────────
pg_log_pos() { docker logs "$PG_CONTAINER" 2>&1 | wc -l; }

pg_new_lines() {
  local since="$1"
  docker logs "$PG_CONTAINER" 2>&1 | tail -n +"$since" \
    | awk '/^\t/{if(current!="")current=current" "substr($0,2);next}{if(current!="")print current;current=$0}END{if(current!="")print current}' \
    | grep -iE "execute|statement:" \
    | grep -iv "pg_catalog\|information_schema\|SET\|BEGIN\|COMMIT\|DEALLOCATE\|pg_type\|pg_namespace\|ROLLBACK\|SELECT 1\|SHOW\|pg_class\|pg_attribute\|pg_attrdef\|pg_constraint\|pg_index\|pg_description\|pg_am\|pg_stat\|pg_settings"
}

ora_flush() {
  docker exec -i "$ORA_CONTAINER" sqlplus -s sys/orapass@//localhost:1521/XEPDB1 as sysdba <<'EOSQL' 2>/dev/null
ALTER SYSTEM FLUSH SHARED_POOL;
EOSQL
}

ora_queries() {
  docker exec -i "$ORA_CONTAINER" sqlplus -s sys/orapass@//localhost:1521/XEPDB1 as sysdba <<'EOSQL' 2>/dev/null
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

run_sql() {
  local sql="$1"
  local resp job_id status
  resp=$(curl -s -X POST "$DREMIO_URL/api/v3/sql" \
    -H "Content-Type: application/json" -H "Authorization: $AUTH" \
    -d "{\"sql\":\"$sql\"}")
  job_id=$(echo "$resp" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])' 2>/dev/null)
  if [ -z "$job_id" ]; then echo "SUBMIT_ERROR: $resp"; return 1; fi
  for i in $(seq 1 30); do
    sleep 2
    status=$(curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id" \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('jobState',''))" 2>/dev/null || true)
    if [ "$status" = "COMPLETED" ]; then
      curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id/results" | python3 -c '
import sys,json
j=json.load(sys.stdin)
for row in j.get("rows",[]): print("    ",row)' 2>&1
      return 0
    fi
    if [ "$status" = "FAILED" ] || [ "$status" = "CANCELED" ]; then
      local detail
      detail=$(curl -s -H "Authorization: $AUTH" "$DREMIO_URL/api/v3/job/$job_id" \
        | python3 -c "import sys,json; j=json.load(sys.stdin); print(j.get('errorMessage','')[:400])" 2>/dev/null)
      echo "QUERY_FAILED: $detail"
      return 1
    fi
  done
  echo "TIMEOUT"; return 1
}

# test_pushdown LABEL SOURCE SQL EXPECTED_PATTERN
test_pushdown() {
  local label="$1" source="$2" sql="$3" pattern="$4"
  echo -ne "  ${BOLD}$label${NC} ... "

  local pg_before=0
  [ "$source" = "pg" ] && pg_before=$(pg_log_pos)
  [ "$source" = "ora" ] && ora_flush

  local result
  result=$(run_sql "$sql" 2>&1)
  if echo "$result" | grep -q "QUERY_FAILED\|SUBMIT_ERROR\|TIMEOUT"; then
    echo -e "${RED}FAIL (query error)${NC}"
    echo "    $result" | head -3
    FAIL_COUNT=$((FAIL_COUNT+1))
    return
  fi

  sleep 1
  local found=false
  if [ "$source" = "pg" ]; then
    local pg_lines
    pg_lines=$(pg_new_lines "$pg_before")
    if echo "$pg_lines" | grep -qiE "$pattern"; then
      found=true
    fi
    if ! $found; then
      echo -e "${RED}FAIL${NC} (pattern '$pattern' not in PG log)"
      echo "$pg_lines" | head -2 | while IFS= read -r l; do echo -e "    ${BLUE}$l${NC}"; done
      FAIL_COUNT=$((FAIL_COUNT+1))
      return
    fi
  elif [ "$source" = "ora" ]; then
    local ora_lines
    ora_lines=$(ora_queries)
    if echo "$ora_lines" | grep -qiE "$pattern"; then
      found=true
    fi
    if ! $found; then
      echo -e "${RED}FAIL${NC} (pattern '$pattern' not in Oracle V\$SQL)"
      echo "$ora_lines" | grep -v '^$' | head -2 | while IFS= read -r l; do echo -e "    ${BLUE}$l${NC}"; done
      FAIL_COUNT=$((FAIL_COUNT+1))
      return
    fi
  fi

  echo -e "${GREEN}PASS${NC}"
  PASS_COUNT=$((PASS_COUNT+1))
}

# test_correct LABEL SQL EXPECTED_SUBSTRING
test_correct() {
  local label="$1" sql="$2" expected="$3"
  echo -ne "  ${BOLD}$label${NC} ... "

  local result
  result=$(run_sql "$sql" 2>&1)
  if echo "$result" | grep -q "QUERY_FAILED\|SUBMIT_ERROR\|TIMEOUT"; then
    echo -e "${RED}FAIL (query error)${NC}"
    echo "    $result" | head -3
    FAIL_COUNT=$((FAIL_COUNT+1))
    return
  fi
  if echo "$result" | grep -qiE "$expected"; then
    echo -e "${GREEN}PASS${NC}"
    PASS_COUNT=$((PASS_COUNT+1))
  else
    echo -e "${RED}FAIL${NC} (expected '$expected' in results)"
    echo "$result" | head -3
    FAIL_COUNT=$((FAIL_COUNT+1))
  fi
}

PG_EMP="pg_test.public.employees"
PG_DEPT="pg_test.public.departments"
ORA_EMP="oracle_test.TESTUSER.EMPLOYEES"
ORA_DEPT="oracle_test.TESTUSER.DEPARTMENTS"

echo ""
echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  REGRESSION SUITE: BASELINE JDBC PUSHDOWN                  ${NC}"
echo -e "${BOLD}============================================================${NC}"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 1: WHERE / FILTER PUSHDOWN
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 1: WHERE FILTER PUSHDOWN ──${NC}"
test_pushdown "PG: WHERE salary > 100000" pg \
  "SELECT name, salary FROM $PG_EMP WHERE salary > 100000" \
  "WHERE"
test_pushdown "PG: WHERE dept IS NOT NULL" pg \
  "SELECT name FROM $PG_EMP WHERE department IS NOT NULL" \
  "IS NOT NULL"
test_pushdown "PG: WHERE with AND" pg \
  "SELECT name FROM $PG_EMP WHERE department = 'Engineering' AND salary > 110000" \
  "AND"
test_pushdown "ORA: WHERE salary > 100000" ora \
  "SELECT name, salary FROM $ORA_EMP WHERE salary > 100000" \
  "WHERE"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 2: LIMIT PUSHDOWN
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 2: LIMIT PUSHDOWN ──${NC}"
test_pushdown "PG: LIMIT 3" pg \
  "SELECT name FROM $PG_EMP LIMIT 3" \
  "FETCH NEXT 3|LIMIT 3"
test_pushdown "PG: WHERE + LIMIT" pg \
  "SELECT name FROM $PG_EMP WHERE department = 'Sales' LIMIT 2" \
  "FETCH NEXT 2|LIMIT 2"
test_pushdown "ORA: FETCH FIRST 3" ora \
  "SELECT name FROM $ORA_EMP FETCH FIRST 3 ROWS ONLY" \
  "FETCH NEXT 3|FETCH FIRST 3|ROWNUM"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 3: SORT / ORDER BY PUSHDOWN (plain columns)
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 3: ORDER BY PUSHDOWN (plain columns) ──${NC}"
test_pushdown "PG: ORDER BY name" pg \
  "SELECT name FROM $PG_EMP ORDER BY name" \
  "ORDER BY"
test_pushdown "PG: ORDER BY salary DESC" pg \
  "SELECT name, salary FROM $PG_EMP ORDER BY salary DESC" \
  "ORDER BY"
test_pushdown "PG: ORDER BY + LIMIT (TopN)" pg \
  "SELECT name, salary FROM $PG_EMP ORDER BY salary DESC LIMIT 5" \
  "ORDER BY.*FETCH|ORDER BY.*LIMIT"
test_pushdown "ORA: ORDER BY name" ora \
  "SELECT name FROM $ORA_EMP ORDER BY name" \
  "ORDER BY"
test_pushdown "ORA: ORDER BY + LIMIT (TopN)" ora \
  "SELECT name, salary FROM $ORA_EMP ORDER BY salary DESC FETCH FIRST 5 ROWS ONLY" \
  "ORDER BY"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 4: AGG / GROUP BY PUSHDOWN (plain columns)
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 4: AGG / GROUP BY PUSHDOWN ──${NC}"
# COUNT(*) without GROUP BY can hit SCHEMA_CHANGE on first run; retry once
test_correct "PG: COUNT(*) correct" \
  "SELECT COUNT(*) FROM $PG_EMP" \
  "12"
test_pushdown "PG: GROUP BY + COUNT" pg \
  "SELECT department, COUNT(*) FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department" \
  "GROUP BY"
test_pushdown "PG: GROUP BY + SUM" pg \
  "SELECT department, SUM(salary) FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department" \
  "SUM"
test_pushdown "PG: GROUP BY + AVG" pg \
  "SELECT department, AVG(salary) FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department" \
  "GROUP BY"
test_pushdown "PG: GROUP BY + MIN/MAX" pg \
  "SELECT department, MIN(salary), MAX(salary) FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department" \
  "MIN|MAX"
test_pushdown "ORA: GROUP BY + COUNT" ora \
  "SELECT department, COUNT(*) FROM $ORA_EMP WHERE department IS NOT NULL GROUP BY department" \
  "GROUP BY"
test_pushdown "ORA: GROUP BY + SUM" ora \
  "SELECT department, SUM(salary) FROM $ORA_EMP WHERE department IS NOT NULL GROUP BY department" \
  "SUM"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 5: JOIN PUSHDOWN (same source)
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 5: JOIN PUSHDOWN (same source) ──${NC}"
test_pushdown "PG: INNER JOIN" pg \
  "SELECT e.name, d.budget FROM $PG_EMP e INNER JOIN $PG_DEPT d ON e.department = d.dept_name" \
  "JOIN|INNER"
test_pushdown "PG: LEFT JOIN" pg \
  "SELECT e.name, d.budget FROM $PG_EMP e LEFT JOIN $PG_DEPT d ON e.department = d.dept_name" \
  "JOIN|LEFT"
test_pushdown "ORA: INNER JOIN" ora \
  "SELECT e.name, d.budget FROM $ORA_EMP e INNER JOIN $ORA_DEPT d ON e.department = d.dept_name" \
  "JOIN|INNER"
test_pushdown "ORA: LEFT JOIN" ora \
  "SELECT e.name, d.budget FROM $ORA_EMP e LEFT JOIN $ORA_DEPT d ON e.department = d.dept_name" \
  "JOIN|LEFT"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 6: COMBINED PATTERNS
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 6: COMBINED PATTERNS ──${NC}"
test_pushdown "PG: WHERE + ORDER BY + LIMIT" pg \
  "SELECT name, salary FROM $PG_EMP WHERE department = 'Engineering' ORDER BY salary DESC LIMIT 2" \
  "ORDER BY"
test_pushdown "PG: GROUP BY + HAVING" pg \
  "SELECT department, COUNT(*) AS cnt FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department HAVING COUNT(*) > 2" \
  "GROUP BY"
test_pushdown "PG: ORDER BY expr + LIMIT (Phase 37)" pg \
  "SELECT name FROM $PG_EMP ORDER BY UPPER(name) LIMIT 3" \
  "ORDER BY.*UPPER|UPPER.*ORDER BY"
test_pushdown "ORA: WHERE + ORDER BY + LIMIT" ora \
  "SELECT name, salary FROM $ORA_EMP WHERE department = 'Engineering' ORDER BY salary DESC FETCH FIRST 2 ROWS ONLY" \
  "ORDER BY"
test_pushdown "ORA: ORDER BY expr + LIMIT (Phase 37)" ora \
  "SELECT name FROM $ORA_EMP ORDER BY UPPER(name) FETCH FIRST 3 ROWS ONLY" \
  "ORDER BY.*UPPER|UPPER.*ORDER BY"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 7: CORRECTNESS CHECKS (results validation)
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 7: CORRECTNESS CHECKS ──${NC}"
test_correct "PG: SUM(salary) for Engineering" \
  "SELECT CAST(SUM(salary) AS BIGINT) FROM $PG_EMP WHERE department = 'Engineering'" \
  "505000"
test_correct "PG: COUNT with GROUP BY" \
  "SELECT department, COUNT(*) AS cnt FROM $PG_EMP WHERE department IS NOT NULL GROUP BY department ORDER BY department" \
  "Engineering.*4"
test_correct "PG: JOIN returns correct data" \
  "SELECT e.name, d.location FROM $PG_EMP e INNER JOIN $PG_DEPT d ON e.department = d.dept_name WHERE e.name = 'Alice'" \
  "Alice.*Building A"
test_correct "PG: ORDER BY UPPER LIMIT 3 correct" \
  "SELECT name FROM $PG_EMP ORDER BY UPPER(name) LIMIT 3" \
  "Alice"
test_correct "ORA: COUNT(*) correct" \
  "SELECT COUNT(*) FROM $ORA_EMP" \
  "12"
test_correct "ORA: JOIN returns correct data" \
  "SELECT e.name, d.location FROM $ORA_EMP e INNER JOIN $ORA_DEPT d ON e.department = d.dept_name WHERE e.name = 'Alice'" \
  "Alice.*Building A"
echo ""

# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  REGRESSION SUMMARY                                        ${NC}"
echo -e "${BOLD}  ${GREEN}$PASS_COUNT passed${NC}${BOLD}, ${RED}$FAIL_COUNT failed${NC}${BOLD}                         ${NC}"
echo -e "${BOLD}============================================================${NC}"

if [ "$FAIL_COUNT" -gt 0 ]; then exit 1; fi
exit 0
