---
phase: quick-1
plan: 1
subsystem: infra
tags: [docker, dockerfile, multi-stage-build, github-actions, ecr]

# Dependency graph
requires: []
provides:
  - "Corrected Dockerfile with ARG JAVA_IMAGE at global scope"
  - "Docker build no longer fails with 'base name (${JAVA_IMAGE}) should not be blank'"
affects: [docker-ecr, github-actions]

# Tech tracking
tech-stack:
  added: []
  patterns: ["Docker global ARG scoping: ARGs used in FROM must be declared before first FROM"]

key-files:
  created: []
  modified:
    - distribution/docker/Dockerfile

key-decisions:
  - "ARG JAVA_IMAGE moved to global scope (before first FROM) so Docker can resolve it in FROM ${JAVA_IMAGE} AS runtime"
  - "No re-declaration of ARG JAVA_IMAGE inside any stage needed since it is only used in a FROM instruction"

patterns-established:
  - "Docker ARG scoping: global ARGs (before first FROM) are available to FROM instructions; stage-scoped ARGs are available only within that stage"

requirements-completed: [FIX-DOCKER-ARG-SCOPE]

# Metrics
duration: 3min
completed: 2026-02-25
---

# Quick Task 1: Fix Docker ARG JAVA_IMAGE Scope Summary

**Moved ARG JAVA_IMAGE to global scope in multi-stage Dockerfile so FROM ${JAVA_IMAGE} AS runtime resolves the variable correctly during Docker build**

## Performance

- **Duration:** ~3 min
- **Started:** 2026-02-25T00:00:00Z
- **Completed:** 2026-02-25T00:03:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments

- Identified that `ARG JAVA_IMAGE` was declared inside the `extractor` stage (between the first FROM and the second FROM), making it invisible to the `FROM ${JAVA_IMAGE} AS runtime` instruction
- Moved `ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"` to global scope (line 17, before `FROM busybox AS extractor`)
- Removed the now-redundant stage-scoped `ARG JAVA_IMAGE` declaration (previously line 24)
- Docker build will now correctly resolve `${JAVA_IMAGE}` to `eclipse-temurin:17-jre-jammy`

## Task Commits

Each task was committed atomically:

1. **Task 1: Move ARG JAVA_IMAGE to global scope in Dockerfile** - `188c96edd` (fix)

**Plan metadata:** (see below — created in final commit)

## Files Created/Modified

- `/home/emanuele/IdeaProjects/dremio-oss/distribution/docker/Dockerfile` - Moved ARG JAVA_IMAGE from stage scope to global scope

## Decisions Made

- ARG JAVA_IMAGE does not need re-declaration inside the `runtime` stage since it is only referenced in the FROM instruction, not within the stage body. Only `ARG TARBALL_PATH` needs re-declaration inside `extractor` because it is used in `COPY ${TARBALL_PATH}` within that stage.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- GitHub Actions Docker build should now succeed; the `FROM ${JAVA_IMAGE} AS runtime` instruction will resolve correctly at build time
- If `--build-arg JAVA_IMAGE=...` is passed at build time, it will still override the default `eclipse-temurin:17-jre-jammy`

---
*Phase: quick-1*
*Completed: 2026-02-25*

## Self-Check: PASSED

- FOUND: `distribution/docker/Dockerfile`
- FOUND: `.planning/quick/1-fix-github-actions-docker-build-arg-java/1-SUMMARY.md`
- FOUND: commit `188c96edd`
