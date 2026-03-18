---
name: uat
description: Run RBAC UAT suites (non-SSO, SSO, or both) against Dremio with full environment lifecycle management
argument-hint: "<mode> (all | no-sso | sso | status | teardown)"
allowed-tools:
  - Read
  - Write
  - Edit
  - Glob
  - Grep
  - Bash
  - AskUserQuestion
---

<objective>
End-to-end UAT management for Dremio RBAC — handles Docker environment lifecycle (up/down),
image selection, first-user bootstrap, and test execution for both non-SSO and SSO modes.

**Modes:**
- `all` (default) — Run non-SSO UAT, tear down, then SSO UAT
- `no-sso` — Run only the non-SSO RBAC UAT
- `sso` — Run only the SSO (Keycloak) RBAC UAT
- `status` — Show current Docker environment state and test readiness
- `teardown` — Tear down any running UAT environment

**What this does:**
1. Optionally pulls/tags a Dremio image (defaults to `ghcr.io/devais-eng/dremio-oss:latest`)
2. Manages docker compose lifecycle (up/down with volume cleanup between modes)
3. Waits for all services to be healthy
4. Bootstraps admin user (non-SSO) or waits for dremio-init (SSO)
5. Executes the UAT script(s)
6. Reports pass/fail summary
</objective>

<context>
**Argument:** $ARGUMENTS — the mode to run (default: `all`)

If no argument given, default to `all`.

**Project layout:**
- Docker compose: `samples/iceberg-rest-catalog/docker-compose.yml` (base)
- SSO overlay: `samples/iceberg-rest-catalog/docker-compose.sso.yml`
- LDAP overlay: `samples/iceberg-rest-catalog/docker-compose.ldap.yml`
- Non-SSO UAT: `rbac-uat.sh` (project root)
- SSO UAT: `rbac-sso-uat.sh` (project root)
- Dremio image: built as `dremio-oss:local` (compose references this tag)

**Docker compose working directory:** `samples/iceberg-rest-catalog/`

**Non-SSO prerequisites:**
- Dremio healthy on localhost:9047
- First user bootstrapped via PUT /apiv2/bootstrap/firstuser (admin / admin123)

**SSO prerequisites:**
- Keycloak healthy on localhost:8080
- keycloak-init container completed (audience mapper, direct access grants)
- dremio-init container completed (admin bootstrap via SSO, seed data, RBAC grants)
- Docker network `iceberg-rest-catalog_default` available (for Keycloak token calls)

**Keycloak users (from realm JSON):**
- admin / admin123 (realm role: ADMIN, dremio_admin)
- testuser / testpass (realm role: analysts)
- alice / alice123 (realm role: analysts)
</context>

<process>

## Step 0: Parse Arguments

Parse `$ARGUMENTS` to determine mode. Valid values: `all`, `no-sso`, `sso`, `status`, `teardown`.
Default to `all` if empty or unrecognized.

## Step 1: Status Check (all modes)

Before any action, check current state:

```bash
# Check if compose services are running
cd samples/iceberg-rest-catalog
docker compose ps 2>/dev/null
```

For `status` mode, also show:
- Which services are up/healthy
- Whether Dremio is reachable (`curl -s -o /dev/null -w '%{http_code}' http://localhost:9047`)
- Whether Keycloak is reachable (`curl -s -o /dev/null -w '%{http_code}' http://localhost:8080`)
- The current `dremio-oss:local` image digest
Then stop — do not run tests.

For `teardown` mode:
```bash
cd samples/iceberg-rest-catalog
docker compose -f docker-compose.yml -f docker-compose.sso.yml --profile sso down -v
```
Then stop.

## Step 2: Image Preparation

Ask the user which image to use:

```
AskUserQuestion(
  header="Dremio Image",
  question="Which Dremio image should I use for UAT?",
  options: [
    "ghcr.io/devais-eng/dremio-oss:latest" — "Pull latest from GHCR (default)",
    "dremio-oss:local (existing)" — "Use the currently tagged local image (skip pull)",
    "Local build" — "Build from distribution/docker/Dockerfile using local dremio.tar.gz"
  ]
)
```

Based on selection:
- **GHCR pull:** `docker pull ghcr.io/devais-eng/dremio-oss:latest && docker tag ghcr.io/devais-eng/dremio-oss:latest dremio-oss:local`
- **Existing local:** Verify `docker image inspect dremio-oss:local` exists
- **Local build:** `cd samples/iceberg-rest-catalog && docker compose build dremio`

## Step 3: Run Non-SSO UAT (if mode is `all` or `no-sso`)

### 3a. Tear down any existing environment
```bash
cd samples/iceberg-rest-catalog
docker compose -f docker-compose.yml -f docker-compose.sso.yml --profile sso down -v
```

### 3b. Start non-SSO environment
```bash
cd samples/iceberg-rest-catalog
docker compose up -d
```

### 3c. Wait for Dremio health
Poll `docker inspect --format='{{.State.Health.Status}}' iceberg-rest-catalog-dremio-1` every 10s, max 120s.

### 3d. Bootstrap first user
```bash
curl -s -X PUT "http://localhost:9047/apiv2/bootstrap/firstuser" \
  -H "Content-Type: application/json" \
  -d '{"userName":"admin","firstName":"Admin","lastName":"User","email":"admin@test.com","createdAt":0,"password":"admin123"}'
```

### 3e. Execute non-SSO UAT
```bash
cd <project-root>
bash rbac-uat.sh
```

Capture exit code. Report results.

### 3f. Tear down (if `all` mode — need clean state for SSO)
```bash
cd samples/iceberg-rest-catalog
docker compose down -v
```

## Step 4: Run SSO UAT (if mode is `all` or `sso`)

### 4a. Start SSO environment
```bash
cd samples/iceberg-rest-catalog
docker compose -f docker-compose.yml -f docker-compose.sso.yml --profile sso up -d
```

### 4b. Wait for dremio-init to complete
Poll `docker inspect --format='{{.State.Status}}' iceberg-rest-catalog-dremio-init-1` every 5s, max 300s.
Verify exit code is 0.

### 4c. Execute SSO UAT
```bash
cd <project-root>
bash rbac-sso-uat.sh
```

Capture exit code. Report results.

### 4d. Leave SSO environment running (user may want to explore)

## Step 5: Summary Report

Present combined results:

```
## UAT Results

| Suite        | Tests | Passed | Failed | Status |
|------------- |-------|--------|--------|--------|
| Non-SSO RBAC | 45    | 45     | 0      | PASS   |
| SSO RBAC     | 48    | 48     | 0      | PASS   |

Image: ghcr.io/devais-eng/dremio-oss:latest (sha256:abc...)
Environment: SSO stack still running (use `/uat teardown` to clean up)
```

If any suite failed, highlight the failures and suggest checking Dremio logs:
```bash
docker logs iceberg-rest-catalog-dremio-1 2>&1 | tail -50
```

</process>

<error_handling>

**Docker not running:** Tell user to start Docker daemon.
**Image pull fails:** Check network, auth (`docker login ghcr.io`), suggest using local build.
**Dremio won't start:** Check `docker logs iceberg-rest-catalog-dremio-1`, common issues: port 9047 in use, OOM.
**First user bootstrap fails:** Dremio may not be ready yet — retry after 10s. If 409, user already exists (OK).
**Keycloak not healthy:** Check `docker logs iceberg-rest-catalog-keycloak-1`, common: slow JVM startup.
**dremio-init fails:** Check `docker logs iceberg-rest-catalog-dremio-init-1` — usually Keycloak or Dremio connectivity.
**UAT script fails:** The scripts are self-contained with pass/fail output. Show full output to user.
**Port conflicts:** Check `ss -tlnp | grep -E '9047|8080|9000|19120'` and suggest stopping conflicting services.

</error_handling>
