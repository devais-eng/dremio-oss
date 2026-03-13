#!/usr/bin/env bash
#
# keycloak-init.sh — Create client scopes and role mappers that cannot be
# declared in the realm JSON without wiping Keycloak's built-in OIDC scopes.
#
# Runs as a one-shot docker-compose service after Keycloak is healthy.
#
set -euo pipefail

KC_URL="${KC_URL:-http://keycloak:8080}"
KC_REALM="${KC_REALM:-iceberg}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-admin}"

# ── Wait for Keycloak ────────────────────────────────────────────────────────
KC_HEALTH_URL="${KC_HEALTH_URL:-http://keycloak:9000}"
echo "Waiting for Keycloak at ${KC_HEALTH_URL}/health/ready..."
for i in $(seq 1 60); do
  if curl -sf "${KC_HEALTH_URL}/health/ready" >/dev/null 2>&1; then
    echo "Keycloak is ready."
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "ERROR: Keycloak did not become ready in time." >&2
    exit 1
  fi
  sleep 2
done

# ── Obtain admin token ───────────────────────────────────────────────────────
get_token() {
  curl -sf -X POST "${KC_URL}/realms/master/protocol/openid-connect/token" \
    -d "grant_type=password" \
    -d "client_id=admin-cli" \
    -d "username=${KC_ADMIN_USER}" \
    -d "password=${KC_ADMIN_PASS}" \
    | jq -r '.access_token'
}

TOKEN=$(get_token)
if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  echo "ERROR: Failed to obtain admin token." >&2
  exit 1
fi
echo "Obtained admin token."

API="${KC_URL}/admin/realms/${KC_REALM}"

# Helper: idempotent POST (ignore 409 Conflict = already exists)
api_post() {
  local url="$1"
  shift
  local http_code
  http_code=$(curl -sf -o /dev/null -w "%{http_code}" \
    -X POST "$url" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    "$@") || true
  echo "$http_code"
}

# ── 1. Create "catalog" client scope ─────────────────────────────────────────
echo "Creating 'catalog' client scope..."
code=$(api_post "${API}/client-scopes" -d '{
  "name": "catalog",
  "protocol": "openid-connect",
  "attributes": {
    "include.in.token.scope": "true",
    "display.on.consent.screen": "false"
  }
}')
[ "$code" = "201" ] && echo "  Created." || echo "  Already exists or skipped (HTTP ${code})."

# ── 2. Create "sign" client scope ────────────────────────────────────────────
echo "Creating 'sign' client scope..."
code=$(api_post "${API}/client-scopes" -d '{
  "name": "sign",
  "protocol": "openid-connect",
  "attributes": {
    "include.in.token.scope": "true",
    "display.on.consent.screen": "false"
  }
}')
[ "$code" = "201" ] && echo "  Created." || echo "  Already exists or skipped (HTTP ${code})."

# ── 3. Assign "catalog" and "sign" as optional scopes to client1 ─────────────
# Refresh token (previous calls may have taken time)
TOKEN=$(get_token)

# Look up client1's internal ID
CLIENT1_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
  "${API}/clients?clientId=client1" | jq -r '.[0].id')

if [ -n "$CLIENT1_ID" ] && [ "$CLIENT1_ID" != "null" ]; then
  for scope_name in catalog sign; do
    SCOPE_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
      "${API}/client-scopes" | jq -r ".[] | select(.name==\"${scope_name}\") | .id")
    if [ -n "$SCOPE_ID" ] && [ "$SCOPE_ID" != "null" ]; then
      echo "Assigning '${scope_name}' as optional scope to client1..."
      curl -sf -o /dev/null -X PUT \
        "${API}/clients/${CLIENT1_ID}/optional-client-scopes/${SCOPE_ID}" \
        -H "Authorization: Bearer ${TOKEN}" 2>/dev/null || true
      echo "  Done."
    fi
  done
else
  echo "WARNING: client1 not found, skipping scope assignment."
fi

# ── 4. Create "roles" client scope with realm-role mapper ────────────────────
echo "Creating 'roles' client scope..."
code=$(api_post "${API}/client-scopes" -d '{
  "name": "roles",
  "protocol": "openid-connect",
  "attributes": {
    "include.in.token.scope": "false",
    "display.on.consent.screen": "false"
  }
}')
[ "$code" = "201" ] && echo "  Created." || echo "  Already exists or skipped (HTTP ${code})."

# Add the realm-role protocol mapper to the "roles" scope
ROLES_SCOPE_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
  "${API}/client-scopes" | jq -r '.[] | select(.name=="roles") | .id')

if [ -n "$ROLES_SCOPE_ID" ] && [ "$ROLES_SCOPE_ID" != "null" ]; then
  echo "Adding realm-role mapper to 'roles' scope..."
  code=$(api_post "${API}/client-scopes/${ROLES_SCOPE_ID}/protocol-mappers/models" -d '{
    "name": "realm roles",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-usermodel-realm-role-mapper",
    "config": {
      "claim.name": "realm_access.roles",
      "jsonType.label": "String",
      "multivalued": "true",
      "id.token.claim": "true",
      "access.token.claim": "true",
      "userinfo.token.claim": "true"
    }
  }')
  [ "$code" = "201" ] && echo "  Mapper created." || echo "  Mapper already exists or skipped (HTTP ${code})."
fi

# ── 5. Assign "roles" as default scope to dremio-web ─────────────────────────
TOKEN=$(get_token)

DREMIO_CLIENT_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
  "${API}/clients?clientId=dremio-web" | jq -r '.[0].id')

if [ -n "$DREMIO_CLIENT_ID" ] && [ "$DREMIO_CLIENT_ID" != "null" ] && \
   [ -n "$ROLES_SCOPE_ID" ] && [ "$ROLES_SCOPE_ID" != "null" ]; then
  echo "Assigning 'roles' as default scope to dremio-web..."
  curl -sf -o /dev/null -X PUT \
    "${API}/clients/${DREMIO_CLIENT_ID}/default-client-scopes/${ROLES_SCOPE_ID}" \
    -H "Authorization: Bearer ${TOKEN}" 2>/dev/null || true
  echo "  Done."
else
  echo "WARNING: dremio-web or roles scope not found, skipping."
fi

# ── 6. Enable direct access grants on dremio-web ────────────────────────────
TOKEN=$(get_token)

DREMIO_CLIENT_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
  "${API}/clients?clientId=dremio-web" | jq -r '.[0].id')

if [ -n "$DREMIO_CLIENT_ID" ] && [ "$DREMIO_CLIENT_ID" != "null" ]; then
  echo "Enabling direct access grants on dremio-web..."
  curl -sf -o /dev/null -X PUT \
    "${API}/clients/${DREMIO_CLIENT_ID}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"id\":\"${DREMIO_CLIENT_ID}\",\"clientId\":\"dremio-web\",\"directAccessGrantsEnabled\":true}" 2>/dev/null || true
  echo "  Done."
else
  echo "WARNING: dremio-web not found, skipping direct access grants."
fi

# ── 7. Add audience mapper to dremio-web ─────────────────────────────────────
# By default, Keycloak does NOT include the requesting client's clientId in the
# JWT "aud" claim. Dremio's OidcTokenValidator requires aud to contain "dremio-web".
TOKEN=$(get_token)

DREMIO_CLIENT_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
  "${API}/clients?clientId=dremio-web" | jq -r '.[0].id')

if [ -n "$DREMIO_CLIENT_ID" ] && [ "$DREMIO_CLIENT_ID" != "null" ]; then
  echo "Adding audience mapper to dremio-web..."
  code=$(api_post "${API}/clients/${DREMIO_CLIENT_ID}/protocol-mappers/models" -d '{
    "name": "dremio-web-audience",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-audience-mapper",
    "config": {
      "included.client.audience": "dremio-web",
      "id.token.claim": "true",
      "access.token.claim": "true"
    }
  }')
  [ "$code" = "201" ] && echo "  Audience mapper created." || echo "  Audience mapper already exists or skipped (HTTP ${code})."
else
  echo "WARNING: dremio-web not found, skipping audience mapper."
fi

echo ""
echo "=== Keycloak initialization complete ==="
echo "  Scopes: catalog, sign (→ client1), roles (→ dremio-web)"
echo "  Audience mapper: dremio-web (→ dremio-web aud claim)"
echo "  Users defined in realm JSON: admin (ADMIN), testuser, alice"
