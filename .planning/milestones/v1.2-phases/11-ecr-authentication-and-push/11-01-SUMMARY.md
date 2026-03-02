---
phase: 11-ecr-authentication-and-push
plan: 01
subsystem: infra
tags: [github-actions, docker, ecr, aws, ci-cd, buildx]

# Dependency graph
requires:
  - phase: 09-maven-build-in-ci
    provides: GitHub Actions workflow with Maven build producing tarball artifact
  - phase: 10-dockerfile-adaptation
    provides: Multi-stage Dockerfile with COPY-based tarball ingestion and ARG TARBALL_PATH=dremio.tar.gz default

provides:
  - Complete end-to-end CI/CD pipeline: Maven build -> staging -> Docker buildx -> AWS auth -> ECR login -> push
  - Docker image tagged with version string and latest on every v* tag push
  - Dynamic ECR registry URL via login step output (no hardcoded AWS values)
  - Staging directory pattern isolating Docker build context from repo root

affects: [github-actions-docker-distribution, v1.2-milestone-completion]

# Tech tracking
tech-stack:
  added: [docker/setup-buildx-action@v3, aws-actions/configure-aws-credentials@v4, aws-actions/amazon-ecr-login@v2, docker/build-push-action@v6]
  patterns:
    - Staging directory pattern for Docker context isolation
    - Dynamic ECR registry URL from login step output (steps.login-ecr.outputs.registry)
    - Dual tagging (versioned + latest) in build-push-action
    - AWS credentials exclusively from GitHub Secrets (never hardcoded)

key-files:
  created: []
  modified:
    - .github/workflows/docker-ecr.yml

key-decisions:
  - "Use docker-context staging directory to isolate Docker build context from repo root, sending only tarball and Dockerfile to daemon"
  - "Dynamic ECR registry URL from steps.login-ecr.outputs.registry instead of hardcoded account ID or registry URI"
  - "Dual tagging: versioned tag from steps.version.outputs.VERSION plus :latest for convenience"
  - "No build-args needed: staging renames tarball to dremio.tar.gz matching Dockerfile ARG default"

patterns-established:
  - "Staging directory pattern: mkdir docker-context, copy artifacts with normalized names, use as build context"
  - "ECR registry URL wiring: login step id -> steps.{id}.outputs.registry in downstream tags"
  - "GitHub Secrets for all AWS values: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION, ECR_REPOSITORY"

requirements-completed: [ECR-01, ECR-02, ECR-03, ECR-04]

# Metrics
duration: 2min
completed: 2026-02-20
---

# Phase 11 Plan 01: ECR Authentication and Push Summary

**ECR authentication and Docker build-push steps completing the CI/CD pipeline with aws-actions/configure-aws-credentials@v4, ECR login with dynamic registry URL, and dual-tagged image push (versioned + latest)**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-20T19:17:50Z
- **Completed:** 2026-02-20T19:19:47Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments

- Extended .github/workflows/docker-ecr.yml from 5 steps to 10 steps, completing the full CI/CD pipeline
- All 4 ECR requirements verified: ECR-01 (AWS credentials from secrets), ECR-02 (ECR login with dynamic registry output), ECR-03 (versioned tag push), ECR-04 (latest tag push)
- 18/18 structural validation checks pass including step ordering, context isolation, and zero hardcoded AWS values
- Comment block documents all 4 required GitHub Secrets for team onboarding

## Task Commits

Each task was committed atomically:

1. **Task 1: Append ECR authentication and Docker build-push steps to workflow** - `eddf591ec` (feat)
2. **Task 2: Validate workflow requirement coverage** - no new commit (validation-only task)

**Plan metadata:** (docs commit below)

## Files Created/Modified

- `.github/workflows/docker-ecr.yml` - Added 5 new steps: Stage Docker context, Set up Docker Buildx, Configure AWS credentials, Login to Amazon ECR, Build and push Docker image

## Decisions Made

- **Staging directory for context isolation:** Created `docker-context/` directory with only tarball (renamed to dremio.tar.gz) and Dockerfile, preventing the entire repo from being sent to Docker daemon
- **Dynamic ECR registry URL:** Used `steps.login-ecr.outputs.registry` in both tags rather than hardcoding any AWS account ID or registry URI -- the URL is resolved at runtime from the ECR login step
- **Dual tagging strategy:** Both versioned tag (from git tag via steps.version.outputs.VERSION) and :latest applied to each push, enabling both pinned and floating references
- **No TARBALL_PATH build-arg:** The staging step renames the glob-matched tarball to `dremio.tar.gz`, matching the Dockerfile's `ARG TARBALL_PATH=dremio.tar.gz` default, so no build-arg override is needed

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

The following AWS and GitHub configuration must be completed before the workflow can run successfully:

1. **Create a private ECR repository** in AWS Console (ECR -> Create repository)
2. **Create an IAM user** with ECR push permissions (ecr:GetAuthorizationToken, ecr:BatchCheckLayerAvailability, ecr:InitiateLayerUpload, ecr:UploadLayerPart, ecr:CompleteLayerUpload, ecr:PutImage, ecr:BatchGetImage)
3. **Add GitHub Secrets** in the repository settings (Settings -> Secrets and variables -> Actions):
   - `AWS_ACCESS_KEY_ID` - IAM user access key
   - `AWS_SECRET_ACCESS_KEY` - IAM user secret key
   - `AWS_REGION` - AWS region of the ECR repository (e.g., eu-west-1)
   - `ECR_REPOSITORY` - ECR repository name (not the full URI)

## Next Phase Readiness

- The complete CI/CD pipeline is structurally ready: v* tag push triggers Maven build, tarball staging, Docker build, ECR authentication, and image push
- Blockers remain: AWS ECR repository, IAM user, and GitHub Secrets must be configured before the first real workflow run
- The v1.2 "GitHub Actions Docker Distribution" milestone is structurally complete pending infrastructure setup

---
*Phase: 11-ecr-authentication-and-push*
*Completed: 2026-02-20*

## Self-Check: PASSED

- .github/workflows/docker-ecr.yml: FOUND
- .planning/phases/11-ecr-authentication-and-push/11-01-SUMMARY.md: FOUND
- Commit eddf591ec (feat(11-01): add ECR authentication and Docker build-push steps to workflow): FOUND
