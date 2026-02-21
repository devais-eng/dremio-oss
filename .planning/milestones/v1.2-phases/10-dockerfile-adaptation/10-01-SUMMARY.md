---
phase: 10-dockerfile-adaptation
plan: 01
subsystem: infra
tags: [docker, dockerfile, multi-stage-build, eclipse-temurin, busybox, java17]

# Dependency graph
requires:
  - phase: 09-maven-build-in-ci
    provides: Maven build producing a local tarball artifact used as the COPY source

provides:
  - Multi-stage Dockerfile with COPY-based tarball ingestion (extractor stage)
  - Runtime stage based on eclipse-temurin:17-jre-jammy (JRE, not JDK)
  - ARG TARBALL_PATH=dremio.tar.gz for CI-controlled tarball filename
  - ARG JAVA_IMAGE for overridable runtime base image

affects: [11-ci-docker-build-push, github-actions-docker-distribution]

# Tech tracking
tech-stack:
  added: [eclipse-temurin:17-jre-jammy, busybox (extractor stage)]
  patterns: [multi-stage Docker build, COPY-from-context instead of wget, discarded extractor stage]

key-files:
  created: []
  modified:
    - distribution/docker/Dockerfile

key-decisions:
  - "Use busybox as extractor base — minimal image with tar support, zero footprint in final image"
  - "ARG TARBALL_PATH declared before first FROM for global scope so it can be used in COPY instruction"
  - "Upgrade runtime from eclipse-temurin:11-jdk to eclipse-temurin:17-jre-jammy — JRE not JDK, Java 17 for production"
  - "COPY --from=extractor --chown=dremio:dremio avoids separate chown layer in runtime image"

patterns-established:
  - "Multi-stage build pattern: extractor (busybox) extracts tarball, runtime (JRE) copies extracted files"
  - "Build args for externally-supplied artifacts: ARG before FROM for global scope"

requirements-completed: [DOCK-01, DOCK-02, DOCK-03, DOCK-04]

# Metrics
duration: 2min
completed: 2026-02-20
---

# Phase 10 Plan 01: Dockerfile Adaptation Summary

**Multi-stage Dockerfile rewrite replacing wget+DOWNLOAD_URL with COPY-based tarball ingestion, busybox extractor stage, and eclipse-temurin:17-jre-jammy runtime base**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-20T18:56:32Z
- **Completed:** 2026-02-20T18:58:40Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments

- Rewrote distribution/docker/Dockerfile as a multi-stage build eliminating wget dependency
- Added extractor stage (busybox) that COPYs tarball from build context, extracts it, and discards the archive
- Upgraded runtime base from eclipse-temurin:11-jdk to eclipse-temurin:17-jre-jammy (smaller, production-appropriate JRE)
- Validated all 4 DOCK requirements (DOCK-01 through DOCK-04) via structural analysis and Docker syntax check

## Task Commits

Each task was committed atomically:

1. **Task 1: Rewrite Dockerfile as multi-stage COPY-based build** - `99a5fa921` (feat)
2. **Task 2: Validate Dockerfile structure and requirement coverage** - no new commit (validation-only task)

**Plan metadata:** (docs commit below)

## Files Created/Modified

- `distribution/docker/Dockerfile` - Multi-stage build: extractor stage (busybox, COPY+tar) and runtime stage (eclipse-temurin:17-jre-jammy, COPY --from=extractor)

## Decisions Made

- Used busybox as the extractor base: minimal image that has tar support and is entirely discarded after build — no footprint in the final image
- Declared ARG TARBALL_PATH before the first FROM so it has global scope and can be referenced inside the extractor stage COPY instruction (Docker requires re-declaring the ARG inside each stage)
- Upgraded from eclipse-temurin:11-jdk to eclipse-temurin:17-jre-jammy: JRE (not JDK) is more appropriate for production runtime, and Java 17 matches the Maven build enforcer range used in Phase 9
- Used COPY --from=extractor --chown=dremio:dremio to transfer files with correct ownership in a single instruction, avoiding a separate chown layer

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- distribution/docker/Dockerfile is ready for use in Phase 11 CI workflow
- Phase 11 will use `docker build --build-arg TARBALL_PATH=<filename>` with a staging directory context containing the Maven-built tarball
- Blockers still pending: ECR repository, IAM credentials, and GitHub Secrets must be configured before Phase 11 validation

---
*Phase: 10-dockerfile-adaptation*
*Completed: 2026-02-20*

## Self-Check: PASSED

- distribution/docker/Dockerfile: FOUND
- .planning/phases/10-dockerfile-adaptation/10-01-SUMMARY.md: FOUND
- Commit 99a5fa921 (feat(10-01): rewrite Dockerfile as multi-stage COPY-based build): FOUND
