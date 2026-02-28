---
phase: quick-3
plan: 01
subsystem: infra
tags: [docker, ghcr, github-actions, github-packages]

# Dependency graph
requires:
  - phase: quick-2
    provides: "Two-job docker-ecr.yml workflow with separate build and docker jobs"
provides:
  - "GHCR-based Docker build and push workflow using GITHUB_TOKEN, no AWS dependency"
affects: [docker-ecr.yml, docker distribution]

# Tech tracking
tech-stack:
  added: [docker/login-action@v3 targeting ghcr.io]
  patterns: [GHCR push via GITHUB_TOKEN with packages: write permission]

key-files:
  created: []
  modified: [.github/workflows/docker-ecr.yml]

key-decisions:
  - "Switched Docker registry from AWS ECR to GHCR to eliminate external AWS dependency"
  - "Used github.repository_owner (already lowercase) as GHCR namespace — no separate secret needed"
  - "Kept filename docker-ecr.yml unchanged to avoid rename churn; content fully reflects GHCR"

patterns-established:
  - "GHCR auth pattern: docker/login-action@v3 with registry ghcr.io, username github.repository_owner, password GITHUB_TOKEN"
  - "packages: write permission required at workflow level for GHCR push"

requirements-completed: [QUICK-3]

# Metrics
duration: 3min
completed: 2026-02-28
---

# Quick Task 3: Change ECR Action — Switch to GHCR Summary

**Replaced AWS ECR push with GitHub Container Registry using docker/login-action@v3 and GITHUB_TOKEN, eliminating all AWS secrets and infrastructure dependency.**

## Performance

- **Duration:** ~3 min
- **Started:** 2026-02-28T12:17:00Z
- **Completed:** 2026-02-28T12:20:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Removed aws-actions/configure-aws-credentials and aws-actions/amazon-ecr-login steps
- Removed all four AWS secret references (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION, ECR_REPOSITORY)
- Added docker/login-action@v3 targeting ghcr.io with built-in GITHUB_TOKEN
- Tags now push to ghcr.io/<owner>/dremio-oss with version and latest tags
- Added packages: write to permissions block
- Build job left completely unchanged

## Task Commits

Each task was committed atomically:

1. **Task 1: Replace ECR with GHCR in docker job** - `70a2c7808` (feat)

**Plan metadata:** (docs commit below)

## Files Created/Modified
- `.github/workflows/docker-ecr.yml` - Renamed workflow, replaced ECR auth/push steps with GHCR login-action + GHCR-tagged build-push step, added packages: write permission

## Decisions Made
- Kept the filename `docker-ecr.yml` as-is — renaming the file is a separate concern and would cause noise in git history for a purely cosmetic change.
- Used `github.repository_owner` as the GHCR namespace since it is guaranteed lowercase and requires no separate configuration.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - GITHUB_TOKEN is automatically provided by GitHub Actions. No secrets or external accounts need to be configured.

## Next Phase Readiness
- Workflow is ready to push images to GHCR on the next `v*` tag push or manual workflow_dispatch trigger.
- The [AWS] blocker in STATE.md is now resolved — no ECR repository, IAM user, or AWS secrets are required.

---
*Phase: quick-3*
*Completed: 2026-02-28*
