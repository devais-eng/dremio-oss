---
phase: 09-maven-build-in-ci
plan: 01
subsystem: infra
tags: [github-actions, maven, java, ci, docker-ecr]

# Dependency graph
requires: []
provides:
  - GitHub Actions workflow triggered on v* tag pushes
  - Maven build step producing distribution/server tarball with Java 21
  - Version extraction from git tag (v prefix stripped)
  - Post-build tarball verification step
affects:
  - 10-docker-build
  - 11-ecr-push

# Tech tracking
tech-stack:
  added: [actions/checkout@v4, actions/setup-java@v5, Maven 3.x, Java 21 temurin]
  patterns:
    - Tag-triggered GitHub Actions workflow (v* filter, no branch/PR/dispatch triggers)
    - Version extraction via GITHUB_REF_NAME#v shell parameter expansion
    - Maven build with -pl distribution/server -am for module-targeted builds
    - Step outputs pattern for passing version between workflow steps

key-files:
  created:
    - .github/workflows/docker-ecr.yml
  modified:
    - .gitignore

key-decisions:
  - "Add !.github/ exception to .gitignore so CI workflow files can be tracked in git"
  - "Use actions/setup-java@v5 cache: maven for automatic ~/.m2/repository caching keyed on pom.xml hashes"
  - "Post-build ls step fails the workflow if tarball is absent, providing explicit signal rather than silent absence"
  - "PyYAML parses 'on:' as boolean True (YAML 1.1); validation script must handle wf.get(True) alongside wf.get('on')"

patterns-established:
  - "Tag trigger only: on.push.tags: ['v*'] with no branch/PR/dispatch triggers — keeps CI focused on releases"
  - "Version wiring: GITHUB_REF_NAME#v strip in step output, then ${{ steps.version.outputs.VERSION }} in downstream steps"

requirements-completed: [TRIG-01, TRIG-02, TRIG-03, BILD-01, BILD-02, BILD-03, BILD-04]

# Metrics
duration: 2min
completed: 2026-02-20
---

# Phase 9 Plan 01: Maven Build in CI Summary

**GitHub Actions workflow on v* tag push that installs Java 21 (temurin), runs ./mvnw package targeting distribution/server, and verifies the dremio-community-{version}.tar.gz tarball exists**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-20T18:21:55Z
- **Completed:** 2026-02-20T18:23:48Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Created `.github/workflows/docker-ecr.yml` — complete GitHub Actions workflow for tag-triggered Maven builds
- All 7 requirement checks pass: TRIG-01, TRIG-02, TRIG-03 (Maven half), BILD-01 through BILD-04
- Workflow is Phase 10/11 ready — Docker and ECR steps will extend this file in subsequent phases

## Task Commits

Each task was committed atomically:

1. **Task 1: Author the docker-ecr GitHub Actions workflow** - `35e40373b` (feat)
2. **Task 2: Validate workflow structure and requirement coverage** - no file changes (validation only)

**Plan metadata:** (docs commit — see below)

## Files Created/Modified

- `.github/workflows/docker-ecr.yml` - GitHub Actions workflow: tag trigger, version extraction, Java 21 setup, Maven build, tarball verification
- `.gitignore` - Added `!.github/` exception so CI workflow files are tracked in git

## Decisions Made

- **!.github/ in .gitignore:** The project's `.gitignore` has `.*/` (ignore all dot-directories) with only `.mvn/` excepted. Added `!.github/` exception so the workflow file can be committed. No other dot-directories need tracking.
- **Validation script YAML quirk:** PyYAML 5.x (YAML 1.1 spec) parses bare `on:` as boolean `True`. The workflow file is correct for GitHub Actions; the Python validation script uses `wf.get('on', wf.get(True, {}))` to handle both cases. This is a tooling quirk, not a file defect.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added !.github/ exception to .gitignore**
- **Found during:** Task 1 (commit attempt)
- **Issue:** `.gitignore` contains `.*/` which ignores all dot-directories including `.github/`. Git refused to stage the workflow file.
- **Fix:** Added `!.github/` negation rule to `.gitignore`, parallel to the existing `!.mvn/` exception.
- **Files modified:** `.gitignore`
- **Verification:** `git check-ignore -v .github/workflows/docker-ecr.yml` returns "not ignored anymore"
- **Committed in:** `35e40373b` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Auto-fix necessary to commit the workflow file. No scope creep.

## Issues Encountered

- PyYAML interprets `on:` as boolean `True` under YAML 1.1. The workflow file is valid for GitHub Actions. The Task 2 validation script was updated to handle this parser behavior. No file changes were required.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `.github/workflows/docker-ecr.yml` is complete and valid — Phase 10 will extend it with Docker build steps
- Phase 11 will add ECR authentication and push steps
- Blockers remain: Java 21 enforcer requirement means the build can only run in CI (not locally without Java 21); AWS ECR setup and GitHub Secrets configuration needed before Phase 11 validation

---
*Phase: 09-maven-build-in-ci*
*Completed: 2026-02-20*
