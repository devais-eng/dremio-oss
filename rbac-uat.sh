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

# =============================================================================
# RBAC User Acceptance Test
# =============================================================================
# Tests the full RBAC lifecycle against a running Dremio instance.
#
# Prerequisites:
#   - Dremio running on localhost:9047 with services.rbac.enabled=true
#   - First user already bootstrapped (admin / admin123)
#
# Usage: ./rbac-uat.sh
# =============================================================================

set -euo pipefail

BASE_URL="http://localhost:9047"
ADMIN_USER="admin"
ADMIN_PASS="admin123"
TEST_USER="testuser"
TEST_PASS="testpass123"
TEST_ROLE="analysts"
SPACE_NAME="uat_space"

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

login() {
  local user="$1" pass="$2"
  local resp
  resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/apiv2/login" \
    -H "Content-Type: application/json" \
    -d "{\"userName\":\"$user\",\"password\":\"$pass\"}")
  local code
  code=$(echo "$resp" | tail -1)
  local body
  body=$(echo "$resp" | sed '$d')
  if [ "$code" != "200" ]; then
    echo ""
    return 1
  fi
  echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" 2>/dev/null
}

api_get() {
  local token="$1" path="$2"
  curl -s -w "\n%{http_code}" -X GET "$BASE_URL/api/v3$path" \
    -H "Authorization: _dremio$token" \
    -H "Content-Type: application/json"
}

api_post() {
  local token="$1" path="$2" data="${3:-}"
  curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v3$path" \
    -H "Authorization: _dremio$token" \
    -H "Content-Type: application/json" \
    -d "$data"
}

api_delete() {
  local token="$1" path="$2"
  curl -s -w "\n%{http_code}" -X DELETE "$BASE_URL/api/v3$path" \
    -H "Authorization: _dremio$token" \
    -H "Content-Type: application/json"
}

apiv2_put() {
  local token="$1" path="$2" data="${3:-}"
  curl -s -w "\n%{http_code}" -X PUT "$BASE_URL/apiv2$path" \
    -H "Authorization: _dremio$token" \
    -H "Content-Type: application/json" \
    -d "$data"
}

apiv2_get() {
  local token="$1" path="$2"
  curl -s -w "\n%{http_code}" -X GET "$BASE_URL/apiv2$path" \
    -H "Authorization: _dremio$token" \
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
  # Poll for job completion (max 30s)
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
bold " RBAC User Acceptance Test"
bold "============================================================"
echo ""

# -- Step 0: Login as admin --------------------------------------------------
bold "--- Step 0: Admin login ---"
ADMIN_TOKEN=$(login "$ADMIN_USER" "$ADMIN_PASS")
if [ -z "$ADMIN_TOKEN" ]; then
  red "FATAL: Cannot login as admin ($ADMIN_USER). Is Dremio running?"
  exit 1
fi
green "  Admin login OK (token=${ADMIN_TOKEN:0:12}...)"
echo ""

# -- Step 1: RBAC REST API - Roles ------------------------------------------
bold "--- Step 1: RBAC REST API - Roles ---"

# List roles (should have ADMIN and PUBLIC)
resp=$(api_get "$ADMIN_TOKEN" "/rbac/roles")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_http "GET /rbac/roles returns 200" "200" "$code"
assert_contains "Roles include ADMIN" "ADMIN" "$body"
assert_contains "Roles include PUBLIC" "PUBLIC" "$body"

# Delete test role if it exists from a previous run (ignore errors)
api_delete "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members/$TEST_USER" >/dev/null 2>&1 || true
api_delete "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE" >/dev/null 2>&1 || true

# Create a role
resp=$(api_post "$ADMIN_TOKEN" "/rbac/roles" "{\"roleName\":\"$TEST_ROLE\"}")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_http "POST /rbac/roles (create '$TEST_ROLE') returns 200" "200" "$code"
assert_contains "Response contains role name" "$TEST_ROLE" "$body"

# Create duplicate role -> 409
resp=$(api_post "$ADMIN_TOKEN" "/rbac/roles" "{\"roleName\":\"$TEST_ROLE\"}")
code=$(get_http_code "$resp")
assert_http "Duplicate role creation returns 409" "409" "$code"

echo ""

# -- Step 2: Create test user ------------------------------------------------
bold "--- Step 2: Create test user ---"

resp=$(apiv2_put "$ADMIN_TOKEN" "/user/$TEST_USER" \
  "{\"userName\":\"$TEST_USER\",\"firstName\":\"Test\",\"lastName\":\"User\",\"email\":\"$TEST_USER@test.com\",\"password\":\"$TEST_PASS\"}")
code=$(get_http_code "$resp")
if [ "$code" = "200" ]; then
  assert_http "Create test user '$TEST_USER'" "200" "$code"
else
  # User already exists from a previous run
  yellow "  SKIP [--] User '$TEST_USER' already exists (reusing)"
fi

TEST_TOKEN=$(login "$TEST_USER" "$TEST_PASS")
if [ -z "$TEST_TOKEN" ]; then
  red "FATAL: Cannot login as test user ($TEST_USER)."
  exit 1
fi
green "  Test user login OK (token=${TEST_TOKEN:0:12}...)"
echo ""

# -- Step 3: Membership management -------------------------------------------
bold "--- Step 3: Membership management ---"

# Add test user to role
resp=$(api_post "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members" "{\"userName\":\"$TEST_USER\"}")
code=$(get_http_code "$resp")
assert_http "Add $TEST_USER to role '$TEST_ROLE'" "200" "$code"

# List members
resp=$(api_get "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_http "List members of '$TEST_ROLE'" "200" "$code"
assert_contains "Members include $TEST_USER" "$TEST_USER" "$body"

# Duplicate membership -> 409
resp=$(api_post "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members" "{\"userName\":\"$TEST_USER\"}")
code=$(get_http_code "$resp")
assert_http "Duplicate membership returns 409" "409" "$code"

echo ""

# -- Step 4: Setup test data (space + VDS) ------------------------------------
bold "--- Step 4: Setup test data ---"

# Create a space (or reuse if already exists)
resp=$(api_post "$ADMIN_TOKEN" "/catalog" \
  "{\"entityType\":\"space\",\"name\":\"$SPACE_NAME\"}")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
if [ "$code" = "200" ]; then
  SPACE_ID=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
  assert_http "Create space '$SPACE_NAME'" "200" "$code"
elif [ "$code" = "409" ]; then
  # Space already exists from a previous run — look it up
  resp=$(api_get "$ADMIN_TOKEN" "/catalog/by-path/$SPACE_NAME")
  body=$(get_body "$resp")
  SPACE_ID=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
  yellow "  SKIP [--] Space '$SPACE_NAME' already exists (reusing)"
else
  assert_http "Create space '$SPACE_NAME'" "200" "$code"
fi

# Create VDS (use CREATE OR REPLACE for idempotency)
result=$(run_sql "$ADMIN_TOKEN" "CREATE OR REPLACE VDS $SPACE_NAME.test_view AS SELECT 1 AS id, 'hello' AS msg")
assert_contains "Create VDS $SPACE_NAME.test_view" "OK" "$result"

# Create another VDS that test user should NOT have access to
result=$(run_sql "$ADMIN_TOKEN" "CREATE OR REPLACE VDS $SPACE_NAME.secret_view AS SELECT 42 AS secret_val")
assert_contains "Create VDS $SPACE_NAME.secret_view" "OK" "$result"

echo ""

# -- Step 5: Deny-by-default enforcement ------------------------------------
bold "--- Step 5: Deny-by-default (no grants yet) ---"

# Test user should NOT be able to query any VDS
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.test_view")
assert_contains "testuser denied access to test_view (no grant)" "FAILED" "$result"

result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "testuser denied access to secret_view (no grant)" "FAILED" "$result"

echo ""

# -- Step 6: Grant SELECT on test_view to analysts role ----------------------
bold "--- Step 6: Grant privileges ---"

# Grant via REST API
resp=$(api_post "$ADMIN_TOKEN" "/rbac/grants" \
  "{\"roleId\":\"$TEST_ROLE\",\"objectType\":\"VDS\",\"objectPath\":\"$SPACE_NAME.test_view\",\"privilege\":\"SELECT\"}")
code=$(get_http_code "$resp")
assert_http "Grant SELECT on test_view to '$TEST_ROLE'" "200" "$code"

# List grants on test_view
resp=$(api_get "$ADMIN_TOKEN" "/rbac/grants?objectType=VDS&objectPath=$SPACE_NAME.test_view")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_http "List grants on test_view" "200" "$code"
assert_contains "Grant includes $TEST_ROLE" "$TEST_ROLE" "$body"
assert_contains "Grant includes SELECT" "SELECT" "$body"

echo ""

# -- Step 7: Enforcement after grant -----------------------------------------
bold "--- Step 7: Enforcement after grant ---"

# Test user CAN query test_view now
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.test_view")
assert_contains "testuser CAN access test_view (granted)" "OK" "$result"

# Test user still CANNOT query secret_view
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "testuser DENIED access to secret_view (no grant)" "FAILED" "$result"

# Admin can still query both
result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM $SPACE_NAME.test_view")
assert_contains "admin CAN access test_view (admin bypass)" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "admin CAN access secret_view (admin bypass)" "OK" "$result"

echo ""

# -- Step 8: SQL DDL for RBAC -----------------------------------------------
bold "--- Step 8: SQL DDL (GRANT/REVOKE via SQL) ---"

# Grant via SQL DDL
result=$(run_sql "$ADMIN_TOKEN" "GRANT SELECT ON VDS $SPACE_NAME.secret_view TO ROLE $TEST_ROLE")
assert_contains "SQL GRANT SELECT on secret_view to $TEST_ROLE" "OK" "$result"

# Now test user can access secret_view
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "testuser CAN access secret_view after SQL GRANT" "OK" "$result"

# Revoke via SQL DDL
result=$(run_sql "$ADMIN_TOKEN" "REVOKE SELECT ON VDS $SPACE_NAME.secret_view FROM ROLE $TEST_ROLE")
assert_contains "SQL REVOKE SELECT on secret_view from $TEST_ROLE" "OK" "$result"

# Test user denied again
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.secret_view")
assert_contains "testuser DENIED secret_view after SQL REVOKE" "FAILED" "$result"

echo ""

# -- Step 9: Role DDL via SQL ------------------------------------------------
bold "--- Step 9: SQL DDL (CREATE/DROP ROLE, GRANT/REVOKE ROLE) ---"

result=$(run_sql "$ADMIN_TOKEN" "CREATE ROLE devs")
assert_contains "SQL CREATE ROLE devs" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "GRANT ROLE devs TO USER $TEST_USER")
assert_contains "SQL GRANT ROLE devs TO USER $TEST_USER" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "REVOKE ROLE devs FROM USER $TEST_USER")
assert_contains "SQL REVOKE ROLE devs FROM USER $TEST_USER" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "DROP ROLE devs")
assert_contains "SQL DROP ROLE devs" "OK" "$result"

echo ""

# -- Step 10: System tables --------------------------------------------------
bold "--- Step 10: System tables (admin only) ---"

result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.roles")
assert_contains "admin can query sys.roles" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.privileges")
assert_contains "admin can query sys.privileges" "OK" "$result"

result=$(run_sql "$ADMIN_TOKEN" "SELECT * FROM sys.membership")
assert_contains "admin can query sys.membership" "OK" "$result"

echo ""

# -- Step 11: Non-admin denied RBAC REST API ---------------------------------
bold "--- Step 11: Non-admin denied RBAC management ---"

resp=$(api_get "$TEST_TOKEN" "/rbac/roles")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_http "Non-admin GET /rbac/roles denied (400)" "400" "$code"
assert_contains "Non-admin GET /rbac/roles error message" "ADMIN role members" "$body"

resp=$(api_post "$TEST_TOKEN" "/rbac/roles" "{\"roleName\":\"hackers\"}")
code=$(get_http_code "$resp")
body=$(get_body "$resp")
assert_http "Non-admin POST /rbac/roles denied (400)" "400" "$code"
assert_contains "Non-admin POST /rbac/roles error message" "ADMIN role members" "$body"

echo ""

# -- Step 12: Revoke grant via REST API --------------------------------------
bold "--- Step 12: Revoke grant via REST API ---"

resp=$(api_delete "$ADMIN_TOKEN" "/rbac/grants?roleId=$TEST_ROLE&objectType=VDS&objectPath=$SPACE_NAME.test_view&privilege=SELECT")
code=$(get_http_code "$resp")
assert_http "Revoke SELECT on test_view from $TEST_ROLE (REST)" "204" "$code"

# Verify enforcement
result=$(run_sql "$TEST_TOKEN" "SELECT * FROM $SPACE_NAME.test_view")
assert_contains "testuser DENIED test_view after REST revoke" "FAILED" "$result"

echo ""

# -- Step 13: VDS lifecycle privileges (ALTER/DROP) --------------------------
bold "--- Step 13: VDS lifecycle privileges ---"

# testuser cannot DROP without privilege
result=$(run_sql "$TEST_TOKEN" "DROP VDS $SPACE_NAME.test_view")
assert_contains "testuser DENIED DROP VDS (no privilege)" "FAILED" "$result"

# testuser cannot ALTER without privilege
result=$(run_sql "$TEST_TOKEN" "CREATE OR REPLACE VDS $SPACE_NAME.test_view AS SELECT 2 AS id")
assert_contains "testuser DENIED ALTER VDS (no privilege)" "FAILED" "$result"

echo ""

# -- Cleanup -----------------------------------------------------------------
bold "--- Cleanup ---"

# Remove membership
resp=$(api_delete "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE/members/$TEST_USER")
code=$(get_http_code "$resp")
assert_http "Remove $TEST_USER from $TEST_ROLE" "204" "$code"

# Delete role
resp=$(api_delete "$ADMIN_TOKEN" "/rbac/roles/$TEST_ROLE")
code=$(get_http_code "$resp")
assert_http "Delete role '$TEST_ROLE'" "204" "$code"

# Drop VDS and space
run_sql "$ADMIN_TOKEN" "DROP VDS $SPACE_NAME.test_view" >/dev/null 2>&1
run_sql "$ADMIN_TOKEN" "DROP VDS $SPACE_NAME.secret_view" >/dev/null 2>&1

resp=$(api_delete "$ADMIN_TOKEN" "/api/v3/catalog/by-path/$SPACE_NAME" 2>/dev/null)

echo ""

# -- Summary -----------------------------------------------------------------
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
