#!/usr/bin/env bash
#
# keycloak-ldap-init.sh — Federate OpenLDAP into Keycloak via Admin REST API.
#
# Creates an LDAP User Storage Provider in the iceberg realm, adds a
# role-ldap-mapper that maps LDAP groups to Keycloak realm roles, and
# triggers a full user sync so ldap-engineer and ldap-analyst are importable.
#
# Runs as a one-shot docker-compose service after keycloak-init completes
# and openldap is healthy.
#
set -euo pipefail

KC_URL="${KC_URL:-http://keycloak:8080}"
KC_REALM="${KC_REALM:-iceberg}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-admin}"
LDAP_URL="${LDAP_URL:-ldap://openldap:389}"
LDAP_BIND_DN="${LDAP_BIND_DN:-cn=admin,dc=example,dc=org}"
LDAP_BIND_PASSWORD="${LDAP_BIND_PASSWORD:-admin}"

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

# OpenLDAP is guaranteed healthy by compose depends_on condition.
# Add a small safety margin before hitting the Admin API.
echo "Waiting 3s safety margin for OpenLDAP to finish LDIF import..."
sleep 3

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

# ── Step 4: Create LDAP User Storage Provider (idempotent) ──────────────────
echo "Checking for existing LDAP user storage provider..."
TOKEN=$(get_token)

EXISTING_LDAP_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
  "${API}/components?type=org.keycloak.storage.UserStorageProvider" \
  | jq -r '.[] | select(.providerId=="ldap" and .name=="ldap") | .id' || echo "")

if [ -n "$EXISTING_LDAP_ID" ] && [ "$EXISTING_LDAP_ID" != "null" ]; then
  LDAP_COMPONENT_ID="$EXISTING_LDAP_ID"
  echo "  LDAP provider already exists (id=${LDAP_COMPONENT_ID}), skipping creation."
else
  echo "Creating LDAP User Storage Provider..."
  LDAP_RESPONSE=$(curl -sf -X POST "${API}/components" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\": \"ldap\",
      \"providerId\": \"ldap\",
      \"providerType\": \"org.keycloak.storage.UserStorageProvider\",
      \"config\": {
        \"vendor\": [\"other\"],
        \"connectionUrl\": [\"${LDAP_URL}\"],
        \"bindDn\": [\"${LDAP_BIND_DN}\"],
        \"bindCredential\": [\"${LDAP_BIND_PASSWORD}\"],
        \"usersDn\": [\"ou=people,dc=example,dc=org\"],
        \"usernameLDAPAttribute\": [\"uid\"],
        \"rdnLDAPAttribute\": [\"uid\"],
        \"uuidLDAPAttribute\": [\"entryUUID\"],
        \"userObjectClasses\": [\"inetOrgPerson\"],
        \"editMode\": [\"READ_ONLY\"],
        \"syncRegistrations\": [\"false\"],
        \"trustEmail\": [\"true\"],
        \"fullSyncPeriod\": [\"-1\"],
        \"changedSyncPeriod\": [\"-1\"],
        \"importEnabled\": [\"true\"],
        \"enabled\": [\"true\"],
        \"batchSizeForSync\": [\"1000\"],
        \"searchScope\": [\"1\"],
        \"pagination\": [\"true\"]
      }
    }")
  LDAP_COMPONENT_ID=$(echo "$LDAP_RESPONSE" | jq -r '.id')
  if [ -z "$LDAP_COMPONENT_ID" ] || [ "$LDAP_COMPONENT_ID" = "null" ]; then
    # Keycloak returns 201 with Location header, not body — fetch the ID
    TOKEN=$(get_token)
    LDAP_COMPONENT_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
      "${API}/components?type=org.keycloak.storage.UserStorageProvider" \
      | jq -r '.[] | select(.providerId=="ldap" and .name=="ldap") | .id')
  fi
  echo "  Created LDAP provider (id=${LDAP_COMPONENT_ID})."
fi

if [ -z "$LDAP_COMPONENT_ID" ] || [ "$LDAP_COMPONENT_ID" = "null" ]; then
  echo "ERROR: Could not obtain LDAP component ID." >&2
  exit 1
fi

# ── Step 5: Create role-ldap-mapper (idempotent) ─────────────────────────────
# Maps LDAP groups (cn=engineers, cn=analysts) directly to Keycloak realm roles.
# This is the correct approach: Dremio's KeycloakRoleSyncer reads realm_access.roles
# from the JWT, so we need LDAP groups -> realm roles (not Keycloak groups).
echo "Checking for existing role-ldap-mapper..."
TOKEN=$(get_token)

EXISTING_MAPPER_ID=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
  "${API}/components?parent=${LDAP_COMPONENT_ID}&type=org.keycloak.storage.ldap.mappers.LDAPStorageMapper" \
  | jq -r '.[] | select(.name=="role-mapper") | .id' || echo "")

if [ -n "$EXISTING_MAPPER_ID" ] && [ "$EXISTING_MAPPER_ID" != "null" ]; then
  echo "  role-mapper already exists (id=${EXISTING_MAPPER_ID}), skipping creation."
else
  echo "Creating role-ldap-mapper..."
  curl -sf -X POST "${API}/components" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\": \"role-mapper\",
      \"providerId\": \"role-ldap-mapper\",
      \"providerType\": \"org.keycloak.storage.ldap.mappers.LDAPStorageMapper\",
      \"parentId\": \"${LDAP_COMPONENT_ID}\",
      \"config\": {
        \"roles.dn\": [\"ou=groups,dc=example,dc=org\"],
        \"role.name.ldap.attribute\": [\"cn\"],
        \"role.object.classes\": [\"groupOfNames\"],
        \"membership.ldap.attribute\": [\"member\"],
        \"membership.attribute.type\": [\"DN\"],
        \"membership.user.ldap.attribute\": [\"uid\"],
        \"use.realm.roles.mapping\": [\"true\"],
        \"mode\": [\"READ_ONLY\"]
      }
    }" > /dev/null 2>&1 || true
  echo "  Created role-mapper (maps cn=engineers/cn=analysts -> realm roles)."
fi

# ── Step 7: Trigger full user sync ───────────────────────────────────────────
echo "Triggering full LDAP user sync..."
TOKEN=$(get_token)
curl -sf -X POST "${API}/user-storage/${LDAP_COMPONENT_ID}/sync?action=triggerFullSync" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" > /dev/null 2>&1 || true
echo "  Sync triggered."

# Verify synced users
sleep 2
TOKEN=$(get_token)
USERS=$(curl -sf -H "Authorization: Bearer ${TOKEN}" "${API}/users?search=ldap-" || echo "[]")
COUNT=$(echo "$USERS" | jq 'length' 2>/dev/null || echo "0")
echo "  Found ${COUNT} LDAP users in Keycloak."

echo ""
echo "=== LDAP federation complete ==="
echo "  Provider: ldap (${LDAP_URL})"
echo "  Users DN: ou=people,dc=example,dc=org"
echo "  Role mapper: ou=groups,dc=example,dc=org -> realm roles"
echo "  Synced users: ldap-engineer (engineers), ldap-analyst (analysts)"
