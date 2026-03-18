---
phase: quick-9
plan: 1
subsystem: samples/iceberg-rest-catalog
tags: [ldap, keycloak, docker-compose, sso, rbac, federation]
dependency_graph:
  requires: [samples/iceberg-rest-catalog SSO profile, Keycloak iceberg realm, Dremio RBAC roles]
  provides: [LDAP overlay compose profile, OpenLDAP seed users/groups, Keycloak LDAP federation script]
  affects: [samples/iceberg-rest-catalog/README.md]
tech_stack:
  added: [osixia/openldap:1.5.0, Keycloak LDAP User Storage Provider, role-ldap-mapper]
  patterns: [additive compose overlay, idempotent Admin REST API init scripts, depends_on service_healthy]
key_files:
  created:
    - samples/iceberg-rest-catalog/keycloak/ldap-seed.ldif
    - samples/iceberg-rest-catalog/docker-compose.ldap.yml
    - samples/iceberg-rest-catalog/keycloak/keycloak-ldap-init.sh
  modified:
    - samples/iceberg-rest-catalog/README.md
decisions:
  - "Use role-ldap-mapper (not group-ldap-mapper): maps LDAP groups directly to Keycloak realm roles so JWT realm_access.roles claim is populated — required for Dremio KeycloakRoleSyncer"
  - "Skip explicit OpenLDAP TCP wait in init script: compose depends_on openldap service_healthy already guarantees LDAP readiness; add 3s safety margin only"
  - "No host port mapping for OpenLDAP: only Keycloak needs to reach it on the Docker network; reduces attack surface"
metrics:
  duration: 2 minutes
  completed_date: "2026-03-16"
  tasks_completed: 3
  files_created: 3
  files_modified: 1
---

# Quick Task 9: Extend Samples Environment with LDAP Summary

**One-liner:** LDAP overlay for iceberg-rest-catalog demo using OpenLDAP 1.5.0 + Keycloak role-ldap-mapper, mapping cn=engineers/cn=analysts LDAP groups to existing Keycloak realm roles for RBAC-transparent SSO login.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Create LDIF seed and docker-compose.ldap.yml overlay | b707aaed1 | keycloak/ldap-seed.ldif, docker-compose.ldap.yml |
| 2 | Create keycloak-ldap-init.sh federation script | 69a19c522 | keycloak/keycloak-ldap-init.sh |
| 3 | Update README.md with LDAP documentation | b12cb458e | README.md |

## What Was Built

### LDAP Seed Directory (`keycloak/ldap-seed.ldif`)
Pre-populates OpenLDAP with:
- `ou=people,dc=example,dc=org` — user container
- `ou=groups,dc=example,dc=org` — group container
- `uid=ldap-engineer` — password `engineer123`, inetOrgPerson + posixAccount
- `uid=ldap-analyst` — password `analyst123`, inetOrgPerson + posixAccount
- `cn=engineers` — groupOfNames, member: ldap-engineer
- `cn=analysts` — groupOfNames, member: ldap-analyst

### Compose Overlay (`docker-compose.ldap.yml`)
Additive overlay under `--profile ldap`:
- **openldap** service: osixia/openldap:1.5.0 with `--copy-service` for LDIF bootstrap, healthcheck via `ldapsearch -s base`
- **keycloak-ldap-init** service: alpine/curl + jq, depends on `keycloak-init` (completed) + `openldap` (healthy)

Usage:
```bash
docker compose -f docker-compose.yml -f docker-compose.sso.yml \
  -f docker-compose.ldap.yml --profile sso --profile ldap up -d
```

### Federation Script (`keycloak/keycloak-ldap-init.sh`)
Idempotent Keycloak Admin REST API script:
1. Waits for Keycloak health endpoint (same loop as keycloak-init.sh)
2. Obtains admin token via master realm password grant
3. Creates LDAP User Storage Provider (idempotent — skips if exists)
4. Creates `role-ldap-mapper` pointing to `ou=groups,dc=example,dc=org` (idempotent)
5. Triggers `triggerFullSync` to import ldap-engineer and ldap-analyst
6. Verifies sync via `GET /users?search=ldap-`

### Updated README
- Architecture diagram updated to show Keycloak--LDAP-->OpenLDAP connection
- New "SSO with Keycloak + LDAP" Quick Start subsection
- LDAP Users table with credentials and Dremio access rights
- OpenLDAP row in Services table (internal-only)
- Three LDAP troubleshooting entries
- Updated Cleanup section with LDAP teardown command

## Decisions Made

### role-ldap-mapper vs group-ldap-mapper
Selected `role-ldap-mapper` over `group-ldap-mapper`. The group mapper creates Keycloak groups (not realm roles), which would not populate `realm_access.roles` in the JWT. Dremio's `KeycloakRoleSyncer` reads `realm_access.roles`, so role mapping is required for LDAP users to inherit RBAC grants.

### OpenLDAP wait strategy
The init script skips an explicit TCP check for OpenLDAP and uses a 3-second safety margin instead, because `depends_on: openldap: condition: service_healthy` already guarantees the LDAP service is ready before `keycloak-ldap-init` starts. The healthcheck uses `ldapsearch -s base` which verifies the LDIF was successfully imported.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Compose validation command in plan omitted profile flags**
- **Found during:** Task 1 verification
- **Issue:** The plan's verification command `docker compose -f ... config --quiet` (without `--profile sso --profile ldap`) fails because `dremio` in docker-compose.sso.yml depends on `keycloak` which is profile-gated. Without profiles, Docker Compose reports "depends on undefined service".
- **Fix:** Added `--profile sso --profile ldap` to all compose validation invocations. The files themselves are correct — the plan's test command was incomplete.
- **Files modified:** None (verification command adjustment only)

## Self-Check: PASSED

Files exist:
- FOUND: samples/iceberg-rest-catalog/keycloak/ldap-seed.ldif
- FOUND: samples/iceberg-rest-catalog/docker-compose.ldap.yml
- FOUND: samples/iceberg-rest-catalog/keycloak/keycloak-ldap-init.sh

Commits exist:
- FOUND: b707aaed1
- FOUND: 69a19c522
- FOUND: b12cb458e
