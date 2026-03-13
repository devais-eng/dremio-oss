#!/usr/bin/env bash
# =============================================================================
# RBAC + SSO (Keycloak) User Acceptance Test
# =============================================================================
# Tests the full RBAC lifecycle with Keycloak SSO authentication against a
# running Dremio instance in keycloak auth mode.
#
# Prerequisites:
#   - Docker compose SSO mode running (Dremio + Keycloak + Nessie + MinIO)
#   - Keycloak dremio-web client has audience mapper and direct access grants
#   - Keycloak realm users: admin (ADMIN), testuser, alice (analysts)
#
# Usage: ./rbac-sso-uat.sh
# =============================================================================

set -euo pipefail

# -- config ------------------------------------------------------------------
DREMIO_URL="http://localhost:9047"
KC_URL="http://keycloak:8080"
KC_REALM="iceberg"
KC_CLIENT="dremio-web"
KC_SECRET="dremio-secret"
DOCKER_NETWORK="iceberg-rest-catalog_default"

# Keycloak users (from realm JSON)
ADMIN_USER="admin"
ADMIN_PASS="admin123"
TEST_USER="testuser"
TEST_PASS="testpass"
ALICE_USER="alice"
ALICE_PASS="alice123"

TEST_ROLE="uat_testers"
SPACE_NAME="uat_sso_space"

PASS=0
FAIL=0
TOTAL=0

# -- helpers -----------------------------------------------------------------

green()  { printf '\033[32m%s\033[0m\n' "$*"; }
red()    { printf '\033[31m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
bold()   { printf '\033[1m%s\033[0m\n' "$*"; }

assert_eq() {
  local label="$1" expected="$2" actual="$3"
  TOTAL=$((TOTAL + 1))
  if [ "$expected" = "$actual" ]; then
    green "  PASS [$TOTAL] $label"
    PASS=$((PASS + 1))
  else
    red "  FAIL [$TOTAL] $label  (expected=$expected, actual=$actual)"
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  local label="$1" needle="$2" haystack="$3"
  TOTAL=$((TOTAL + 1))
  if echo "$haystack" | grep -q "$needle"; then
    green "  PASS [$TOTAL] $label"
    PASS=$((PASS + 1))
  else
    red "  FAIL [$TOTAL] $label  (expected to contain: $needle)"
    FAIL=$((FAIL + 1))
  fi
}

assert_not_contains() {
  local label="$1" needle="$2" haystack="$3"
  TOTAL=$((TOTAL + 1))
  if echo "$haystack" | grep -q "$needle"; then
    red "  FAIL [$TOTAL] $label  (should NOT contain: $needle)"
    FAIL=$((FAIL + 1))
  else
    green "  PASS [$TOTAL] $label"
    PASS=$((PASS + 1))
  fi
}

assert_http() {
  local label="$1" expected_code="$2" actual_code="$3"
  assert_eq "$label" "$expected_code" "$actual_code"
}

# Get Keycloak JWT token (must call keycloak:8080 from inside Docker for correct issuer)
get_kc_token() {
  local user="$1" pass="$2"
  docker run --rm --network "$DOCKER_NETWORK" --entrypoint sh curlimages/curl:latest -c "
    curl -s -X POST ${KC_URL}/realms/${KC_REALM}/protocol/openid-connect/token \
      -d 'grant_type=password' \
      -d 'client_id=${KC_CLIENT}' \
      -d 'client_secret=${KC_SECRET}' \
      -d 'username=${user}' \
      -d 'password=${pass}'
  " | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('access_token',''))" 2>/dev/null
}

# Dremio API helpers (use Bearer token for JWT)
api_get() {
  local token="$1" path="$2"
  curl -s -w "\n%{http_code}" -X GET "$DREMIO_URL/api/v3$path" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json"
}

api_post() {
  local token="$1" path="$2" data="${3:-}"
  curl -s -w "\n%{http_code}" -X POST "$DREMIO_URL/api/v3$path" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d "$data"
}

api_put() {
  local token="$1" path="$2" data="${3:-}"
  curl -s -w "\n%{http_code}" -X PUT "$DREMIO_URL/api/v3$path" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d "$data"
}

api_delete() {
  local token="$1" path="$2"
  curl -s -w "\n%{http_code}" -X DELETE "$DREMIO_URL/api/v3$path" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json"
}

run_sql() {
  local token="$1" sql="$2"
  local resp code body job_id state
  resp=$(api_post "$token" "/sql" "{\"sql\":\"$sql\"}")
  code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')
  if [ "$code" != "200" ]; then
    echo "SQL_ERROR:$code:$body"
    return 0
  fi
  job_id=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
  for _ in $(seq 1 30); do
    resp=$(api_get "$token" "/job/$job_id")
    code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')
    state=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('jobState','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
    case "$state" in
      COMPLETED) echo "OK:$job_id"; return 0 ;;
      FAILED|CANCELED|CANCELLED)
        local errmsg
        errmsg=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('errorMessage',''))" 2>/dev/null || echo "")
        echo "FAILED:$job_id:$errmsg"
        return 0
        ;;
    esac
    sleep 1
  done
  echo "TIMEOUT:$job_id"
}

get_http_code() { echo "$1" | tail -1; }
get_body()      { echo "$1" | sed '$d'; }

# =============================================================================
bold "============================================================"
bold " RBAC + SSO (Keycloak) User Acceptance Test"
bold "============================================================"
echo ""

# -- Step 0: SSO JIT Login (admin) ------------------------------------------
bold "--- Step 0: SSO JIT Login (admin) ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")
if [ -z "$ADMIN_TOKEN" ]; then
  red "FATAL: Cannot get Keycloak token for $ADMIN_USER."
  exit 1
fi
green "  Admin JWT obtained (${ADMIN_TOKEN:0:12}...)"

# First API call triggers JIT provisioning + ADMIN role sync
resp=$(api_get "$ADMIN_TOKEN" "/catalog")
code=$(get_http_code "$resp")
assert_http "Admin JWT accepted by Dremio (JIT provisioned)" "200" "$code"

resp=$(api_get "$ADMIN_TOKEN" "/rbac/roles")
code=$(get_http_code "$resp")
assert_http "Admin can access /rbac/roles (admin privilege)" "200" "$code"
body=$(get_body "$resp")
assert_contains "ADMIN role exists (synced from Keycloak)" "ADMIN" "$body"

echo ""

# -- Step 1: Create analysts role before JIT-provisioning non-admin users ----
bold "--- Step 1: Pre-create 'analysts' role (for Keycloak role sync) ---"

# The role syncer only assigns memberships to existing Dremio roles.
# Create analysts before testuser/alice login so their realm roles get synced.
ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

resp=$(api_post "$ADMIN_TOKEN" "/rbac/roles" "{\"roleName\":\"analysts\"}")
code=$(get_http_code "$resp")
if [ "$code" = "200" ]; then
  assert_http "Create 'analysts' role" "200" "$code"
elif [ "$code" = "409" ]; then
  yellow "  SKIP [--] analysts role already exists"
else
  assert_http "Create 'analysts' role" "200" "$code"
fi

echo ""

# -- Step 2: SSO JIT Login (testuser) → triggers role sync -----------------
bold "--- Step 2: SSO JIT Login (testuser) → analysts role sync ---"

TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")
if [ -z "$TEST_TOKEN" ]; then
  red "FATAL: Cannot get Keycloak token for $TEST_USER."
  exit 1
fi
green "  testuser JWT obtained"

# This JIT-provisions testuser AND syncs analysts role membership
resp=$(api_get "$TEST_TOKEN" "/catalog")
code=$(get_http_code "$resp")
assert_http "testuser JWT accepted by Dremio (JIT provisioned)" "200" "$code"

# Verify non-admin denied access to RBAC management
resp=$(api_get "$TEST_TOKEN" "/rbac/roles")
code=$(get_http_code "$resp")
assert_http "testuser denied /rbac/roles (non-admin)" "400" "$code"

echo ""

# -- Step 3: SSO JIT Login (alice) → triggers role sync --------------------
bold "--- Step 3: SSO JIT Login (alice) → analysts role sync ---"

ALICE_TOKEN=$(get_kc_token "$ALICE_USER" "$ALICE_PASS")
if [ -z "$ALICE_TOKEN" ]; then
  red "FATAL: Cannot get Keycloak token for $ALICE_USER."
  exit 1
fi
green "  alice JWT obtained"

resp=$(api_get "$ALICE_TOKEN" "/catalog")
code=$(get_http_code "$resp")
assert_http "alice JWT accepted by Dremio (JIT provisioned)" "200" "$code"

echo ""

# -- Step 4: Keycloak Role Sync Verification ---------------------------------
bold "--- Step 4: Keycloak role sync verification ---"

# Refresh admin token (previous one may be close to expiry)
ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

# admin should have ADMIN role
result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.membership WHERE member_name = '$ADMIN_USER'")
if echo "$result" | grep -q "OK"; then
  job_id=$(echo "$result" | cut -d: -f2)
  resp=$(api_get "$ADMIN_TOKEN" "/job/$job_id/results?offset=0&limit=100")
  body=$(get_body "$resp")
  assert_contains "admin is member of ADMIN role (Keycloak sync)" "ADMIN" "$body"
else
  TOTAL=$((TOTAL + 1)); FAIL=$((FAIL + 1))
  red "  FAIL [$TOTAL] admin membership query failed: $result"
fi

# testuser should have analysts role (synced from Keycloak)
result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.membership WHERE member_name = '$TEST_USER'")
if echo "$result" | grep -q "OK"; then
  job_id=$(echo "$result" | cut -d: -f2)
  resp=$(api_get "$ADMIN_TOKEN" "/job/$job_id/results?offset=0&limit=100")
  body=$(get_body "$resp")
  assert_contains "testuser is member of analysts role (Keycloak sync)" "analysts" "$body"
else
  TOTAL=$((TOTAL + 1)); FAIL=$((FAIL + 1))
  red "  FAIL [$TOTAL] testuser membership query failed: $result"
fi

# alice should have analysts role too
result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.membership WHERE member_name = '$ALICE_USER'")
if echo "$result" | grep -q "OK"; then
  job_id=$(echo "$result" | cut -d: -f2)
  resp=$(api_get "$ADMIN_TOKEN" "/job/$job_id/results?offset=0&limit=100")
  body=$(get_body "$resp")
  assert_contains "alice is member of analysts role (Keycloak sync)" "analysts" "$body"
else
  TOTAL=$((TOTAL + 1)); FAIL=$((FAIL + 1))
  red "  FAIL [$TOTAL] alice membership query failed: $result"
fi

echo ""

# -- Step 5: RBAC REST API - Create test role + assign ----------------------
bold "--- Step 5: RBAC REST API - Role management (admin via SSO) ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

# Cleanup from previous runs
api_delete "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members/$TEST_USER" >/dev/null 2>&1 || true
api_delete "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE" >/dev/null 2>&1 || true

resp=$(api_post "$ADMIN_TOKEN" "/rbac/roles" "{\"roleName\":\"$TEST_ROLE\"}")
code=$(get_http_code "$resp")
assert_http "Admin (SSO) creates role '$TEST_ROLE'" "200" "$code"

resp=$(api_post "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members" "{\"userName\":\"$TEST_USER\"}")
code=$(get_http_code "$resp")
assert_http "Admin (SSO) adds testuser to $TEST_ROLE" "200" "$code"

resp=$(api_post "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members" "{\"userName\":\"$TEST_USER\"}")
code=$(get_http_code "$resp")
assert_http "Duplicate membership returns 409" "409" "$code"

echo ""

# -- Step 6: Setup test data ------------------------------------------------
bold "--- Step 6: Setup test data (space + VDS) ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

resp=$(api_post "$ADMIN_TOKEN" "/catalog" \
  "{\"entityType\":\"space\",\"name\":\"$SPACE_NAME\"}")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
if [ "$code" = "200" ]; then
  SPACE_ID=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
  assert_http "Create space '$SPACE_NAME'" "200" "$code"
elif [ "$code" = "409" ]; then
  resp=$(api_get "$ADMIN_TOKEN" "/catalog/by-path/$SPACE_NAME")
  body=$(get_body "$resp")
  SPACE_ID=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
  yellow "  SKIP [--] Space already exists (reusing)"
else
  assert_http "Create space '$SPACE_NAME'" "200" "$code"
fi

result=$(run_sql "$ADMIN_TOKEN" "CREATE OR REPLACE VDS $SPACE_NAME.public_view AS SELECT 1 AS id, 'hello' AS msg")
assert_contains "Create VDS public_view" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "CREATE OR REPLACE VDS $SPACE_NAME.secret_view AS SELECT 42 AS secret_val")
assert_contains "Create VDS secret_view" "OK" "$result"

echo ""

# -- Step 7: Deny-by-default enforcement (SSO user) -------------------------
bold "--- Step 7: Deny-by-default (no grants for testuser) ---"

TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")

result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.public_view")
assert_contains "testuser DENIED public_view (no grant)" "FAILED" "$result"

result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "testuser DENIED secret_view (no grant)" "FAILED" "$result"

echo ""

# -- Step 8: Grant privileges via REST API -----------------------------------
bold "--- Step 8: Grant SELECT via REST API ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

resp=$(api_post "$ADMIN_TOKEN" "/rbac/grants" \
  "{\"roleId\":\"$TEST_ROLE\",\"objectType\":\"VDS\",\"objectPath\":\"$SPACE_NAME.public_view\",\"privilege\":\"SELECT\"}")
code=$(get_http_code "$resp")
assert_http "Grant SELECT on public_view to $TEST_ROLE" "200" "$code"

echo ""

# -- Step 9: Enforcement after grant ----------------------------------------
bold "--- Step 9: Enforcement after grant (SSO user) ---"

TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")

result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.public_view")
assert_contains "testuser CAN access public_view (granted via REST)" "OK" "$result"

result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "testuser DENIED secret_view (no grant)" "FAILED" "$result"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")
result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "admin CAN access secret_view (admin bypass)" "OK" "$result"

echo ""

# -- Step 10: SQL DDL GRANT/REVOKE -------------------------------------------
bold "--- Step 10: SQL DDL (GRANT/REVOKE via SQL) ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

result=$(run_sql "$ADMIN_TOKEN" "GRANT SELECT ON VDS $SPACE_NAME.secret_view TO ROLE $TEST_ROLE")
assert_contains "SQL GRANT SELECT on secret_view to $TEST_ROLE" "OK" "$result"

TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "testuser CAN access secret_view after SQL GRANT" "OK" "$result"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")
result=$(run_sql "$ADMIN_TOKEN" "REVOKE SELECT ON VDS $SPACE_NAME.secret_view FROM ROLE $TEST_ROLE")
assert_contains "SQL REVOKE SELECT on secret_view" "OK" "$result"

TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "testuser DENIED secret_view after SQL REVOKE" "FAILED" "$result"

echo ""

# -- Step 11: SQL DDL CREATE/DROP ROLE ----------------------------------------
bold "--- Step 11: SQL DDL (CREATE/DROP ROLE, GRANT/REVOKE ROLE) ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

result=$(run_sql "$ADMIN_TOKEN" "CREATE ROLE sso_devs")
assert_contains "SQL CREATE ROLE sso_devs" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "GRANT ROLE sso_devs TO USER $TEST_USER")
assert_contains "SQL GRANT ROLE sso_devs TO USER $TEST_USER" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "REVOKE ROLE sso_devs FROM USER $TEST_USER")
assert_contains "SQL REVOKE ROLE sso_devs FROM USER $TEST_USER" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "DROP ROLE sso_devs")
assert_contains "SQL DROP ROLE sso_devs" "OK" "$result"

echo ""

# -- Step 12: System tables (admin via SSO) -----------------------------------
bold "--- Step 12: System tables (admin via SSO) ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.roles")
assert_contains "admin (SSO) can query sys.roles" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.privileges")
assert_contains "admin (SSO) can query sys.privileges" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.membership")
assert_contains "admin (SSO) can query sys.membership" "OK" "$result"

echo ""

# -- Step 13: Non-admin denied RBAC management (SSO) --------------------------
bold "--- Step 13: Non-admin denied RBAC management (SSO) ---"

TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")

resp=$(api_get "$TEST_TOKEN" "/rbac/roles")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_http "testuser (SSO) GET /rbac/roles denied" "400" "$code"
assert_contains "Error references ADMIN role" "ADMIN role members" "$body"

resp=$(api_post "$TEST_TOKEN" "/rbac/roles" "{\"roleName\":\"hackers\"}")
code=$(get_http_code "$resp")
assert_http "testuser (SSO) POST /rbac/roles denied" "400" "$code"

resp=$(api_post "$TEST_TOKEN" "/catalog" "{\"entityType\":\"space\",\"name\":\"hacker_space\"}")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_contains "testuser (SSO) cannot create space" "administrator" "$body"

resp=$(api_post "$TEST_TOKEN" "/catalog" "{\"entityType\":\"source\",\"name\":\"hacker_source\",\"type\":\"NAS\",\"config\":{\"path\":\"/tmp\"}}")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_contains "testuser (SSO) cannot create source" "administrator" "$body"

echo ""

# -- Step 14: VDS lifecycle privileges (SSO user) ----------------------------
bold "--- Step 14: VDS lifecycle privileges (SSO user) ---"

TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")

result=$(run_sql "$TEST_TOKEN" "DROP VDS $SPACE_NAME.public_view")
assert_contains "testuser DENIED DROP VDS (no privilege)" "FAILED" "$result"

result=$(run_sql "$TEST_TOKEN" "CREATE OR REPLACE VDS $SPACE_NAME.public_view AS SELECT 2 AS id")
assert_contains "testuser DENIED ALTER VDS (no privilege)" "FAILED" "$result"

echo ""

# -- Step 15: Cross-SSO-user isolation (via Keycloak-synced analysts role) ----
bold "--- Step 15: Cross-SSO-user isolation ---"

ALICE_TOKEN=$(get_kc_token "$ALICE_USER" "$ALICE_PASS")

# alice is not in $TEST_ROLE → should be denied
result=$(run_sql "$ALICE_TOKEN" "SELECT * FROM $SPACE_NAME.public_view")
assert_contains "alice DENIED public_view (not in $TEST_ROLE)" "FAILED" "$result"

# Grant to analysts (Keycloak-synced role — both testuser and alice are members)
ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")
result=$(run_sql "$ADMIN_TOKEN" "GRANT SELECT ON VDS $SPACE_NAME.public_view TO ROLE analysts")
assert_contains "SQL GRANT to analysts role (Keycloak-synced)" "OK" "$result"

# Both should now have access via analysts
TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.public_view")
assert_contains "testuser CAN access via analysts role" "OK" "$result"

ALICE_TOKEN=$(get_kc_token "$ALICE_USER" "$ALICE_PASS")
result=$(run_sql "$ALICE_TOKEN" "SELECT * FROM $SPACE_NAME.public_view")
assert_contains "alice CAN access via analysts role" "OK" "$result"

echo ""

# -- Step 16: Revoke REST API grant -------------------------------------------
bold "--- Step 16: Revoke grant via REST API ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

resp=$(api_delete "$ADMIN_TOKEN" "/rbac/grants?roleId=$TEST_ROLE&objectType=VDS&objectPath=$SPACE_NAME.public_view&privilege=SELECT")
code=$(get_http_code "$resp")
assert_http "Revoke SELECT on public_view from $TEST_ROLE (REST)" "204" "$code"

# testuser can still access via analysts role
TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.public_view")
assert_contains "testuser still has access via analysts role" "OK" "$result"

# Revoke analysts grant too
ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")
result=$(run_sql "$ADMIN_TOKEN" "REVOKE SELECT ON VDS $SPACE_NAME.public_view FROM ROLE analysts")
assert_contains "SQL REVOKE from analysts" "OK" "$result"

TEST_TOKEN=$(get_kc_token "$TEST_USER" "$TEST_PASS")
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.public_view")
assert_contains "testuser DENIED after all revokes" "FAILED" "$result"

echo ""

# -- Cleanup ------------------------------------------------------------------
bold "--- Cleanup ---"

ADMIN_TOKEN=$(get_kc_token "$ADMIN_USER" "$ADMIN_PASS")

api_delete "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members/$TEST_USER" >/dev/null 2>&1 || true
resp=$(api_delete "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE")
code=$(get_http_code "$resp")
assert_http "Delete role '$TEST_ROLE'" "204" "$code"

run_sql "$ADMIN_TOKEN" "DROP VDS $SPACE_NAME.public_view" >/dev/null 2>&1
run_sql "$ADMIN_TOKEN" "DROP VDS $SPACE_NAME.secret_view" >/dev/null 2>&1

# Delete space
if [ -n "${SPACE_ID:-}" ] && [ "$SPACE_ID" != "" ]; then
  resp=$(api_delete "$ADMIN_TOKEN" "/catalog/$SPACE_ID?tag=$(api_get "$ADMIN_TOKEN" "/catalog/$SPACE_ID" | sed '$d' | python3 -c "import sys,json; print(json.load(sys.stdin).get('tag',''))" 2>/dev/null)")
  code=$(get_http_code "$resp")
  [ "$code" = "204" ] && green "  Space deleted" || yellow "  Space delete returned $code"
fi

# Note: analysts role is NOT deleted — it persists for ongoing SSO use
yellow "  NOTE: analysts role preserved (used by Keycloak sync)"

echo ""

# -- Summary ------------------------------------------------------------------
bold "============================================================"
bold " Results: $PASS passed / $FAIL failed / $TOTAL total"
bold "============================================================"

if [ "$FAIL" -gt 0 ]; then
  red "SOME TESTS FAILED"
  exit 1
else
  green "ALL TESTS PASSED"
  exit 0
fi
