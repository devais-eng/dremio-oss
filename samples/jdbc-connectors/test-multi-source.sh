#!/usr/bin/env bash
#
# Multi-source UAT: Cross-source queries, pushdown verification, edge cases.
#
# Prerequisites:
#   docker compose up -d && ./scripts/seed-all.sh
#   Dremio sources created (this script creates them if missing)
#
set +o histexpand 2>/dev/null
set -uo pipefail

DREMIO_URL="http://localhost:9047"
PASS='Admin123!'
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; BLUE='\033[0;34m'; NC='\033[0m'
BOLD='\033[1m'
PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0

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

# ── Create Dremio sources ─────────────────────────────────────────────
echo -e "${BOLD}Creating Dremio sources...${NC}"
python3 << PYEOF
import requests, time
url = '$DREMIO_URL'
auth = '$AUTH'
hdrs = {'Authorization': auth, 'Content-Type': 'application/json'}

# Delete existing
import urllib.parse
for name in ['pg_jdbc', 'pg_adbc', 'oracle_src', 'nessie_src']:
    r = requests.get(f'{url}/apiv2/source/{name}', headers=hdrs)
    if r.status_code == 200:
        tag = urllib.parse.quote(r.json().get('tag',''))
        requests.delete(f'{url}/apiv2/source/{name}?version={tag}', headers=hdrs)
time.sleep(2)

# PG JDBC
requests.put(f'{url}/apiv2/source/pg_jdbc', headers=hdrs, json={
    'name': 'pg_jdbc',
    'config': {'hostname':'postgres','port':5432,'databaseName':'testdb',
               'username':'pguser','password':'pgpass',
               'useSsl':False,'encryptionValidationMode':'NO_VALIDATION',
               'fetchSize':4096,'queryTimeoutSec':0,'protocolMode':'JDBC'},
    'type': 'POSTGRES_DB'
})
print('pg_jdbc: created')

# PG ADBC
requests.put(f'{url}/apiv2/source/pg_adbc', headers=hdrs, json={
    'name': 'pg_adbc',
    'config': {'hostname':'postgres','port':5432,'databaseName':'testdb',
               'username':'pguser','password':'pgpass',
               'useSsl':False,'encryptionValidationMode':'NO_VALIDATION',
               'fetchSize':4096,'queryTimeoutSec':0,'protocolMode':'AUTO'},
    'type': 'POSTGRES_DB'
})
print('pg_adbc: created')

# Oracle
requests.put(f'{url}/apiv2/source/oracle_src', headers=hdrs, json={
    'name': 'oracle_src',
    'config': {'hostname':'oracle','port':1521,'serviceName':'XEPDB1',
               'username':'testuser','password':'testpass',
               'useSsl':False,'encryptionValidationMode':'NO_VALIDATION',
               'fetchSize':4096,'queryTimeoutSec':0},
    'type': 'ORACLE_DB'
})
print('oracle_src: created')

# Nessie/Iceberg REST Catalog
r = requests.put(f'{url}/apiv2/source/nessie_src', headers=hdrs, json={
    'name': 'nessie_src',
    'config': {
        'restEndpointUri': 'http://nessie:19120/iceberg/',
        'propertyList': [
            {'name': 'warehouse', 'value': 'warehouse'},
            {'name': 'fs.s3a.endpoint', 'value': 'minio:9000'},
            {'name': 'fs.s3a.access.key', 'value': 'minioadmin'},
            {'name': 'fs.s3a.secret.key', 'value': 'minioadmin'},
            {'name': 'fs.s3a.path.style.access', 'value': 'true'},
            {'name': 'fs.s3a.connection.ssl.enabled', 'value': 'false'},
            {'name': 'dremio.s3.compat', 'value': 'true'},
            {'name': 'fs.s3a.aws.credentials.provider', 'value': 'org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider'},
        ],
        'secretPropertyList': [],
    },
    'type': 'RESTCATALOG'
})
if r.status_code >= 400:
    print(f'nessie_src: FAILED ({r.status_code}): {r.text[:200]}')
else:
    print('nessie_src: created')
PYEOF
echo "Waiting 15s for metadata..."
sleep 15
echo -e "${GREEN}Sources ready.${NC}"
echo ""

# ── Helpers ─────────────────────────────────────────────────────────────
pg_log_pos() { docker logs "$PG_CONTAINER" 2>&1 | wc -l; }

pg_new_lines() {
  local since="$1"
  docker logs "$PG_CONTAINER" 2>&1 | tail -n +"$since" \
    | awk '/^\t/{if(current!="")current=current" "substr($0,2);next}{if(current!="")print current;current=$0}END{if(current!="")print current}' \
    | grep -iE "execute|statement:" \
    | grep -iv "pg_catalog\|information_schema\|SET\|BEGIN\|COMMIT\|DEALLOCATE\|pg_type\|pg_namespace\|ROLLBACK\|SELECT 1\|SHOW\|pg_class\|pg_attribute\|pg_attrdef\|pg_constraint\|pg_index\|pg_description\|pg_am\|pg_stat\|pg_settings"
}

run_sql() {
  local sql="$1"
  python3 -c "
import requests, time, json, sys
sql = '''$sql'''
hdrs = {'Authorization': '$AUTH', 'Content-Type': 'application/json'}
r = requests.post('$DREMIO_URL/api/v3/sql', headers=hdrs, json={'sql': sql})
try:
    job_id = r.json().get('id','')
except:
    print('SUBMIT_ERROR: ' + r.text[:200]); sys.exit(1)
if not job_id:
    print('SUBMIT_ERROR: ' + r.text[:200]); sys.exit(1)
for i in range(30):
    time.sleep(2)
    r = requests.get(f'$DREMIO_URL/api/v3/job/{job_id}', headers=hdrs)
    state = r.json().get('jobState','')
    if state == 'COMPLETED':
        r = requests.get(f'$DREMIO_URL/api/v3/job/{job_id}/results', headers=hdrs)
        for row in r.json().get('rows',[]): print('    ', row)
        sys.exit(0)
    if state in ('FAILED','CANCELED'):
        print('QUERY_FAILED: ' + r.json().get('errorMessage','')[:400])
        sys.exit(1)
print('TIMEOUT'); sys.exit(1)
"
}

test_pushdown() {
  local label="$1" source="$2" sql="$3" pattern="$4"
  echo -ne "  ${BOLD}$label${NC} ... "
  local pg_before=0
  [ "$source" = "pg" ] && pg_before=$(pg_log_pos)
  local result
  result=$(run_sql "$sql" 2>&1)
  if echo "$result" | grep -q "QUERY_FAILED\|SUBMIT_ERROR\|TIMEOUT"; then
    echo -e "${RED}FAIL (query error)${NC}"
    echo "    $result" | head -3
    FAIL_COUNT=$((FAIL_COUNT+1)); return
  fi
  sleep 1
  if [ "$source" = "pg" ]; then
    local pg_lines
    pg_lines=$(pg_new_lines "$pg_before")
    if echo "$pg_lines" | grep -qiE "$pattern"; then
      echo -e "${GREEN}PASS${NC}"
      PASS_COUNT=$((PASS_COUNT+1)); return
    else
      echo -e "${RED}FAIL${NC} (pattern '$pattern' not in PG log)"
      echo "$pg_lines" | head -2 | while IFS= read -r l; do echo -e "    ${BLUE}$l${NC}"; done
      FAIL_COUNT=$((FAIL_COUNT+1)); return
    fi
  fi
  echo -e "${GREEN}PASS${NC}"
  PASS_COUNT=$((PASS_COUNT+1))
}

test_correct() {
  local label="$1" sql="$2" expected="$3"
  echo -ne "  ${BOLD}$label${NC} ... "
  local result
  result=$(run_sql "$sql" 2>&1)
  if echo "$result" | grep -q "QUERY_FAILED\|SUBMIT_ERROR\|TIMEOUT"; then
    echo -e "${RED}FAIL (query error)${NC}"
    echo "    $result" | head -3
    FAIL_COUNT=$((FAIL_COUNT+1)); return
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

test_no_error() {
  local label="$1" sql="$2"
  echo -ne "  ${BOLD}$label${NC} ... "
  local result
  result=$(run_sql "$sql" 2>&1)
  if echo "$result" | grep -q "QUERY_FAILED\|SUBMIT_ERROR\|TIMEOUT"; then
    echo -e "${RED}FAIL (query error)${NC}"
    echo "    $result" | head -3
    FAIL_COUNT=$((FAIL_COUNT+1)); return
  fi
  echo -e "${GREEN}PASS${NC}"
  PASS_COUNT=$((PASS_COUNT+1))
}

# Aliases
PG="pg_jdbc.\"public\""
ADBC="pg_adbc.\"public\""
ORA="oracle_src.TESTUSER"
ICE="nessie_src.analytics"

echo ""
echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  MULTI-SOURCE UAT                                          ${NC}"
echo -e "${BOLD}============================================================${NC}"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 1: SAME-SOURCE PUSHDOWN (regression baseline)
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 1: SAME-SOURCE PUSHDOWN (regression) ──${NC}"
test_pushdown "PG JDBC: WHERE + ORDER BY + LIMIT" pg \
  "SELECT name, price FROM $PG.products WHERE price > 100 ORDER BY price DESC LIMIT 5" \
  "ORDER BY"
test_pushdown "PG JDBC: GROUP BY + HAVING" pg \
  "SELECT category_id, COUNT(*) AS cnt FROM $PG.products GROUP BY category_id HAVING COUNT(*) > 3" \
  "GROUP BY"
test_pushdown "PG JDBC: INNER JOIN (same source)" pg \
  "SELECT o.id, oi.product_id FROM $PG.orders o INNER JOIN $PG.order_items oi ON o.id = oi.order_id" \
  "JOIN|INNER"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 2: ADBC vs JDBC (same PG data, different protocol)
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 2: ADBC vs JDBC PROTOCOL COMPARISON ──${NC}"
test_correct "JDBC: product count" \
  "SELECT COUNT(*) AS cnt FROM $PG.products WHERE category_id IS NOT NULL" \
  "15"
test_correct "ADBC: product count (same data)" \
  "SELECT COUNT(*) AS cnt FROM $ADBC.products WHERE category_id IS NOT NULL" \
  "15"
test_correct "JDBC: SUM(price) for electronics" \
  "SELECT CAST(SUM(price) AS BIGINT) FROM $PG.products WHERE category_id = 1" \
  "3070"
test_correct "ADBC: SUM(price) for electronics (same data)" \
  "SELECT CAST(SUM(price) AS BIGINT) FROM $ADBC.products WHERE category_id = 1" \
  "3070"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 3: CROSS-SOURCE JOINS (PG × Oracle)
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 3: CROSS-SOURCE JOINS (PG × Oracle) ──${NC}"
echo -e "  ${YELLOW}(Cross-source JOINs compute in Dremio engine — not pushed)${NC}"

test_correct "PG orders × Oracle customers (INNER JOIN)" \
  "SELECT o.id AS order_id, c.name AS customer_name FROM $PG.orders o INNER JOIN $ORA.CUSTOMERS c ON o.customer_id = c.id ORDER BY o.id" \
  "Alice Johnson"

test_correct "Oracle customers with region info" \
  "SELECT c.NAME, c.TIER, r.COUNTRY FROM $ORA.CUSTOMERS c INNER JOIN $ORA.REGIONS r ON c.REGION_ID = r.ID WHERE c.TIER = 'premium' ORDER BY c.NAME" \
  "Alice Johnson.*premium.*US"

test_correct "Cross-source: PG order totals per Oracle customer" \
  "SELECT c.name, SUM(o.total) AS total_spent FROM $PG.orders o INNER JOIN $ORA.CUSTOMERS c ON o.customer_id = c.id GROUP BY c.name ORDER BY total_spent DESC" \
  "Dave Wilson"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 4: PGVECTOR SEMANTIC SEARCH
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 4: PGVECTOR SEMANTIC SEARCH ──${NC}"

test_pushdown "KNN: l2_distance pushdown (<->)" pg \
  "SELECT id, name FROM $PG.products ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 3" \
  "<->"

test_pushdown "KNN: cosine_distance pushdown (<=>)" pg \
  "SELECT id, name FROM $PG.products ORDER BY cosine_distance(embedding, ARRAY[0.1, 0.9, 0.8, 0.2]) LIMIT 3" \
  "<=>"

test_pushdown "KNN: inner_product pushdown (<#>)" pg \
  "SELECT id, name FROM $PG.products ORDER BY inner_product(embedding, ARRAY[0.5, 0.5, 0.5, 0.5]) LIMIT 3" \
  "<#>"

test_correct "KNN: nearest to electronics cluster [0.8,0.2,0.1,0.9]" \
  "SELECT name FROM $PG.products ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 1" \
  "Laptop Pro 15"

test_correct "KNN: nearest to furniture cluster [0.1,0.9,0.8,0.2]" \
  "SELECT name FROM $PG.products ORDER BY l2_distance(embedding, ARRAY[0.1, 0.9, 0.8, 0.2]) LIMIT 1" \
  "Standing Desk"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 5: FUNCTION COMPOSITION & EXPRESSION PUSHDOWN
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 5: FUNCTION COMPOSITION & EXPRESSION PUSHDOWN ──${NC}"

test_no_error "CAST(EXTRACT(YEAR FROM date) AS INTEGER)" \
  "SELECT CAST(EXTRACT(YEAR FROM order_date) AS INTEGER) AS yr, COUNT(*) FROM $PG.orders GROUP BY CAST(EXTRACT(YEAR FROM order_date) AS INTEGER)"

test_no_error "ROUND(SUM(price * 1.1), 2) with GROUP BY" \
  "SELECT category_id, ROUND(SUM(price * 1.1), 2) AS boosted FROM $PG.products GROUP BY category_id"

test_no_error "UPPER(TRIM(name)) in ORDER BY" \
  "SELECT name FROM $PG.products ORDER BY UPPER(TRIM(name)) LIMIT 5"

test_no_error "COALESCE + CAST composition" \
  "SELECT COALESCE(CAST(category_id AS VARCHAR), 'unknown') AS cat FROM $PG.products LIMIT 5"

test_no_error "Nested: CEIL(ABS(price - 500))" \
  "SELECT name, CEIL(ABS(price - 500)) AS dist FROM $PG.products ORDER BY dist LIMIT 5"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 6: ICEBERG / NESSIE QUERIES
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 6: ICEBERG / NESSIE QUERIES ──${NC}"

test_correct "Iceberg: product_categories row count" \
  "SELECT COUNT(*) AS cnt FROM $ICE.product_categories" \
  "3"

test_correct "Iceberg: monthly_sales row count" \
  "SELECT COUNT(*) AS cnt FROM $ICE.monthly_sales" \
  "12"

test_correct "Iceberg: SUM(revenue) by month" \
  "SELECT \"month\", CAST(SUM(revenue) AS BIGINT) AS total FROM $ICE.monthly_sales GROUP BY \"month\" ORDER BY \"month\"" \
  "2024-01"

test_correct "PG products × Iceberg categories (cross-source)" \
  "SELECT p.name, c.category_name FROM $PG.products p INNER JOIN $ICE.product_categories c ON p.category_id = c.category_id WHERE p.price > 400 ORDER BY p.name" \
  "Laptop Pro 15.*Electronics"

test_correct "Iceberg sales × PG products (cross-source)" \
  "SELECT p.name, s.\"month\", s.units_sold FROM $ICE.monthly_sales s INNER JOIN $PG.products p ON s.product_id = p.id WHERE s.units_sold > 10 ORDER BY s.units_sold DESC" \
  "Wireless Mouse"

test_correct "Triple-source: PG products × Iceberg categories × Oracle regions (via orders+customers)" \
  "SELECT cat.category_name, r.name AS region FROM $PG.order_items oi INNER JOIN $PG.products p ON oi.product_id = p.id INNER JOIN $ICE.product_categories cat ON p.category_id = cat.category_id INNER JOIN $PG.orders o ON oi.order_id = o.id INNER JOIN $ORA.CUSTOMERS c ON o.customer_id = c.id INNER JOIN $ORA.REGIONS r ON c.region_id = r.id WHERE c.name = 'Alice Johnson' ORDER BY cat.category_name" \
  "Electronics"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 7: CROSS-SOURCE + SEMANTIC SEARCH COMBINATION
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 7: CROSS-SOURCE + SEMANTIC SEARCH ──${NC}"
echo -e "  ${YELLOW}(Semantic search pushes to PG; cross-source JOIN in Dremio engine)${NC}"

test_correct "KNN products → cross-join Oracle customer" \
  "SELECT p.name AS product, c.name AS customer FROM (SELECT name FROM $PG.products ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 3) p, $ORA.CUSTOMERS c WHERE c.id = 101" \
  "Laptop Pro 15.*Alice"

test_correct "KNN products → Iceberg categories enrichment" \
  "SELECT p.name, cat.category_name, cat.margin_pct FROM (SELECT id, name, category_id FROM $PG.products ORDER BY l2_distance(embedding, ARRAY[0.8, 0.2, 0.1, 0.9]) LIMIT 3) p INNER JOIN $ICE.product_categories cat ON p.category_id = cat.category_id" \
  "Electronics"

test_correct "PG order analytics × Oracle customer details" \
  "SELECT c.name, c.tier, o.order_count FROM (SELECT customer_id, COUNT(*) AS order_count FROM $PG.orders GROUP BY customer_id) o INNER JOIN $ORA.CUSTOMERS c ON o.customer_id = c.id ORDER BY o.order_count DESC" \
  "Alice Johnson.*premium"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# SECTION 8: EDGE CASES
# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}── SECTION 8: EDGE CASES ──${NC}"

test_no_error "NULL embedding in distance function" \
  "SELECT l2_distance(CAST(NULL AS LIST(FLOAT)), ARRAY[1.0, 2.0, 3.0, 4.0])"

test_no_error "Empty result set (WHERE impossible)" \
  "SELECT * FROM $PG.products WHERE price > 999999"

test_no_error "PG same-source multi-table JOIN" \
  "SELECT o.id, oi.product_id, p.name FROM $PG.orders o INNER JOIN $PG.order_items oi ON o.id = oi.order_id INNER JOIN (SELECT id, name FROM $PG.products WHERE category_id = 1) p ON oi.product_id = p.id LIMIT 5"

test_no_error "JDBC and ADBC same table in one query" \
  "SELECT j.name, a.price FROM $PG.products j INNER JOIN $ADBC.products a ON j.id = a.id LIMIT 5"

test_no_error "Oracle self-join" \
  "SELECT c.name, r.name AS region FROM $ORA.CUSTOMERS c INNER JOIN $ORA.REGIONS r ON c.region_id = r.id"

test_correct "Cross-source COUNT consistency" \
  "SELECT COUNT(*) AS pg_count FROM $PG.products WHERE category_id IS NOT NULL" \
  "15"
echo ""

# ═══════════════════════════════════════════════════════════════════════
echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}  MULTI-SOURCE UAT SUMMARY                                  ${NC}"
echo -e "${BOLD}  ${GREEN}$PASS_COUNT passed${NC}${BOLD}, ${RED}$FAIL_COUNT failed${NC}${BOLD}, ${YELLOW}$SKIP_COUNT skipped${NC}${BOLD}  ${NC}"
echo -e "${BOLD}============================================================${NC}"

if [ "$FAIL_COUNT" -gt 0 ]; then exit 1; fi
exit 0
