---
phase: quick-2
plan: 01
subsystem: infra
tags: [github-actions, docker, ecr, maven, artifacts]

requires: []
provides:
  - "Two-job docker-ecr workflow: build (Maven) + docker (ECR push) connected via artifact"
  - "Re-run failed jobs skips Maven build when only ECR push fails"
affects: [docker-ecr-workflow, ci-cd]

tech-stack:
  added: [actions/upload-artifact@v4, actions/download-artifact@v4]
  patterns: ["Job output propagation via jobs.<id>.outputs", "Sparse checkout for Dockerfile-only job"]

key-files:
  created: []
  modified: [".github/workflows/docker-ecr.yml"]

key-decisions:
  - "Sparse-checkout in docker job: only Dockerfile needed, avoids full 5GB repo clone"
  - "retention-days: 1 for artifact: tarball is transient, no long-term storage needed"
  - "if-no-files-found: error on upload: fail fast if Maven didn't produce a tarball"

patterns-established:
  - "Build artifact pattern: upload in build job, download in docker job via named artifact"
  - "Job output version propagation: steps.version.outputs.VERSION -> jobs.build.outputs.version -> needs.build.outputs.version"

requirements-completed: [QUICK-2]

duration: 3min
completed: 2026-02-25
---

# Quick Task 2: Split Docker-ECR Workflow Summary

**docker-ecr.yml split into build (Maven compile + tarball upload) and docker (artifact download + ECR push) jobs, enabling re-run of failed Docker push without rerunning the 10+ min Maven build**

## Performance

- **Duration:** 3 min
- **Started:** 2026-02-25T14:19:53Z
- **Completed:** 2026-02-25T14:22:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Split single-job workflow into two jobs connected by a GitHub Actions artifact
- Build job uploads `dremio-community-*.tar.gz` via `actions/upload-artifact@v4` with 1-day retention
- Docker job downloads artifact, stages Docker context, and pushes to ECR
- Version string propagated from `build` job outputs to `docker` job via `needs.build.outputs.version`
- Docker job uses sparse checkout (Dockerfile only) to avoid cloning the full repository

## Task Commits

Each task was committed atomically:

1. **Task 1: Split docker-ecr.yml into build and docker jobs** - `92f7bfccf` (feat)

**Plan metadata:** (see final commit below)

## Files Created/Modified
- `.github/workflows/docker-ecr.yml` - Rewritten from single-job to two-job workflow with artifact passing

## Decisions Made
- Sparse checkout in the docker job: the job only needs `distribution/docker/Dockerfile`. Checking out the full repo would clone unnecessary gigabytes.
- `retention-days: 1`: the tarball is a transient CI artifact, no reason to retain it beyond the same pipeline run.
- `if-no-files-found: error`: if Maven didn't produce a tarball, the upload should fail immediately rather than silently creating an empty artifact.
- Comments block (required GitHub Secrets) moved to the docker job where the credentials are actually consumed.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Workflow is ready to use. No additional configuration needed.
- ECR secrets (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION, ECR_REPOSITORY) must be set in the GitHub repository before the workflow runs (pre-existing requirement, not changed by this task).

---
*Phase: quick-2*
*Completed: 2026-02-25*
