# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-21)

**Core value:** Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation.
**Current focus:** Planning next milestone

## Current Position

Milestone: v1.2 GitHub Actions Docker Distribution — SHIPPED 2026-02-21
Status: All milestones complete (v1.0, v1.1, v1.2). No active milestone.
Last activity: 2026-02-25 - Completed quick task 2: Split docker-ecr workflow into build and docker jobs

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
- Total plans completed: 3
- Average duration: ~2 min
- Total execution time: ~6 min

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
v1.2 decisions archived to milestones/v1.2-ROADMAP.md.

### Pending Todos

None.

### Blockers/Concerns

- [Build]: Maven build requires Java 21 (enforcer [21,22) range); only Java 11/17 available locally. CI uses setup-java to install Java 21.
- [AWS]: ECR repository, IAM user, and GitHub Secrets must be configured before first workflow run.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 1 | Fix Github Actions docker build ARG JAVA_IMAGE scope | 2026-02-25 | 7fd637779 | [1-fix-github-actions-docker-build-arg-java](./quick/1-fix-github-actions-docker-build-arg-java/) |
| 2 | Split docker-ecr workflow into build and docker jobs | 2026-02-25 | 92f7bfccf | [2-split-docker-ecr-workflow-into-build-and](./quick/2-split-docker-ecr-workflow-into-build-and/) |

## Session Continuity

Last session: 2026-02-25
Stopped at: Completed quick task 2: Split docker-ecr workflow into build and docker jobs
Resume file: None
