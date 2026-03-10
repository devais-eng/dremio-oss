---
phase: 24-multi-branch-queries-and-hardening
plan: 02
subsystem: testing
tags: [testcontainers, nessie, docker, integration-testing, icebergcatalog]

# Dependency graph
requires:
  - phase: 21-configuration-and-nessie-detection
    provides: "RestIcebergCatalogPlugin with NessieEnabled config and detection logic"
  - phase: 22-branch-aware-catalog-infrastructure
    provides: "Branch cache and SupportsBranchAwareRestCatalog interface"
provides:
  - "NessieContainer testcontainers module (dremio-testcontainers-nessie)"
  - "Docker-based Nessie server wrapper for integration tests serving Iceberg REST on port 19120"
  - "NessieContainer available as test dependency in icebergcatalog plugin"
affects: [24-03, future-integration-tests]

# Tech tracking
tech-stack:
  added: [testcontainers, ghcr.io/projectnessie/nessie:0.100.3]
  patterns: [DremioContainer pattern for testcontainers wrapper]

key-files:
  created:
    - tools/testcontainers/nessie/pom.xml
    - tools/testcontainers/nessie/src/main/java/com/dremio/testcontainers/nessie/NessieContainer.java
  modified:
    - tools/testcontainers/pom.xml
    - plugins/icebergcatalog/pom.xml

key-decisions:
  - "NessieContainer exposes getIcebergRestUri() and getNessieApiUri() as separate helpers for different use cases"
  - "Image pinned to ghcr.io/projectnessie/nessie:0.100.3 matching project nessie.version property"
  - "No test dependencies in nessie module itself (NessieContainer has no tests in this plan)"

patterns-established:
  - "DremioContainer pattern: extend GenericContainer, implement DremioContainer, call DremioTestcontainersUsageValidator.validate() in start(), block setDockerImageName()"

requirements-completed: [BRQ-03]

# Metrics
duration: 4min
completed: 2026-03-10
---

# Phase 24 Plan 02: NessieContainer Testcontainers Module Summary

**NessieContainer testcontainers module wrapping ghcr.io/projectnessie/nessie:0.100.3, exposing Iceberg REST and Nessie API endpoints on port 19120, wired as test dependency to icebergcatalog plugin**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-10T17:05:13Z
- **Completed:** 2026-03-10T17:09:00Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Created `dremio-testcontainers-nessie` Maven module following NatsContainer pattern exactly
- NessieContainer implements DremioContainer, validates usage via DremioTestcontainersUsageValidator, and exposes three URI helpers (base, Iceberg REST, Nessie API)
- Registered nessie module in tools/testcontainers/pom.xml and wired as test-scoped dependency in icebergcatalog plugin

## Task Commits

Each task was committed atomically:

1. **Task 1: Create NessieContainer Maven module** - `1215d8607` (feat)
2. **Task 2: Add NessieContainer as test dependency to icebergcatalog** - `5d46b82e2` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `tools/testcontainers/nessie/pom.xml` - Maven module descriptor for dremio-testcontainers-nessie
- `tools/testcontainers/nessie/src/main/java/com/dremio/testcontainers/nessie/NessieContainer.java` - Docker container wrapper for Nessie server
- `tools/testcontainers/pom.xml` - Added nessie module entry after nats
- `plugins/icebergcatalog/pom.xml` - Added dremio-testcontainers-nessie as test dependency

## Decisions Made

- NessieContainer exposes `getIcebergRestUri()` (for RESTCATALOG config) and `getNessieApiUri()` (for Nessie API access) as separate helpers
- Docker image pinned to `ghcr.io/projectnessie/nessie:0.100.3` matching project's `nessie.version` property
- No test dependencies in the nessie module itself (NessieContainer is a utility, not a test runner)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Applied Spotless formatting to NessieContainer.java**
- **Found during:** Task 2 (verifying dependency resolution with mvn install)
- **Issue:** Spotless check failed during install phase due to Javadoc comment line wrapping
- **Fix:** Ran `mvn spotless:apply -pl tools/testcontainers/nessie` which reformatted Javadoc lines
- **Files modified:** tools/testcontainers/nessie/src/main/java/com/dremio/testcontainers/nessie/NessieContainer.java
- **Verification:** `mvn install` succeeded after formatting fix
- **Committed in:** 5d46b82e2 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Required for build to pass. Javadoc wrapping only — no logic changes.

## Issues Encountered

- Maven 3.8.7 / Java 11 enforcer blocked build; used ~/.local/share/maven/bin/mvn with JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 (matching project requirements per .local-env.sh)

## Next Phase Readiness

- NessieContainer is available in icebergcatalog test scope and ready for integration tests in Phase 24 Plan 03
- DremioTestcontainersUsageValidator ensures Docker availability is validated before container start

---
*Phase: 24-multi-branch-queries-and-hardening*
*Completed: 2026-03-10*

## Self-Check: PASSED

- tools/testcontainers/nessie/pom.xml: FOUND
- tools/testcontainers/nessie/src/main/java/com/dremio/testcontainers/nessie/NessieContainer.java: FOUND
- .planning/phases/24-multi-branch-queries-and-hardening/24-02-SUMMARY.md: FOUND
- Commit 1215d8607 (Task 1): FOUND
- Commit 5d46b82e2 (Task 2): FOUND
