# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-20)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** v1.2 GitHub Actions Docker Distribution

## Current Position

Phase: 9 — Maven Build in CI
Plan: 01 complete
Status: Phase 9 in progress (1/1 plans complete)
Last activity: 2026-02-20 — 09-01 complete: docker-ecr.yml workflow created

## Performance Metrics

**Velocity (v1.0):**
- Total plans completed: 15
- Average duration: ~20 min
- Total execution time: ~5 hours

**Velocity (v1.1):**
- Total plans completed: 3
- Average duration: ~15 min
- Total execution time: ~45 min

**Velocity (v1.2):**
- Total plans completed: 1
- Average duration: ~2 min
- Total execution time: ~2 min

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
- [Phase 09-maven-build-in-ci]: Added !.github/ exception to .gitignore to allow CI workflow files to be tracked in git
- [Phase 09-maven-build-in-ci]: Use actions/setup-java@v5 with cache: maven for automatic ~/.m2/repository caching keyed on pom.xml hashes

### Pending Todos

None.

### Blockers/Concerns

- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available locally. CI uses setup-java to install Java 21.
- [AWS]: ECR repository and IAM user with access keys must be created in AWS before Phase 11 can be validated.
- [Secrets]: GitHub Secrets (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION, ECR_REPOSITORY) must be configured before Phase 11.

## Session Continuity

Last session: 2026-02-20
Stopped at: Completed 09-01-PLAN.md — docker-ecr.yml workflow created
Resume file: None
