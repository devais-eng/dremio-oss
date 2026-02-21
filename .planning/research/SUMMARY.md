# Project Research Summary

**Project:** Dremio OSS Fork — v1.2 CI/CD: Docker build on tag push, push to AWS ECR
**Domain:** GitHub Actions CI/CD pipeline for Maven-built Java application
**Researched:** 2026-02-20
**Confidence:** HIGH

## Executive Summary

This milestone adds a single GitHub Actions workflow that builds the Dremio OSS distribution from Maven source and pushes a Docker image to a private AWS ECR repository whenever a `v*` tag is pushed. The pattern is well-understood and all required actions are official, stable, and verified against live APIs. Exactly two files change: `.github/workflows/docker-ecr.yml` (new) and `distribution/docker/Dockerfile` (modified). No application source code changes are required.

The recommended approach is a single-job sequential workflow: checkout, Java 21 setup with built-in Maven cache, Maven package build (`-pl distribution/server -am -DskipTests -Drevision={version}`), Dockerfile adaptation from `wget`-based download to `COPY`-based local artifact, AWS credential configuration, ECR login, and Docker build + push using official actions. The git tag (e.g., `v1.2`) drives both the Maven revision (`-Drevision=1.2`) and the Docker image tag (`1.2`), keeping versions consistent throughout. The Docker image uses `eclipse-temurin:17-jre-jammy` as the runtime base, replacing the existing `eclipse-temurin:11-jdk` with a production-appropriate JRE on a supported Java LTS version.

The dominant risks are: Maven build time without caching (45-90 minutes cold), the existing Dockerfile's fundamental incompatibility with local CI artifacts (`wget` cannot reach a runner-local file), and ECR authentication misconfiguration. All three are well-documented and straightforward to prevent. The sharpest constraint is the Maven enforcer: it requires Java exactly `[21,22)`, so the workflow must pin `java-version: '21'` — any other value causes an immediate build failure before a single class is compiled.

---

## Key Findings

### Recommended Stack

The pipeline uses six established GitHub Actions in sequence, all verified via GitHub API as of 2026-02-20. Maven is provided by the repository's own `./mvnw` wrapper (3.9.9), so no separate Maven install step is needed. The Docker runtime base is `eclipse-temurin:17-jre-jammy` (Ubuntu 22.04 LTS, confirmed active on Docker Hub 2026-02-17), replacing the existing `eclipse-temurin:11-jdk` with a smaller, more secure JRE.

**Core technologies:**
- `actions/checkout@v6` (v6.0.2) — source checkout — latest stable
- `actions/setup-java@v5` (v5.2.0) — Java 21 temurin with built-in Maven cache — eliminates need for a separate `actions/cache` step
- `aws-actions/configure-aws-credentials@v4` — injects IAM key env vars into runner — official AWS action, scoped credential injection
- `aws-actions/amazon-ecr-login@v2` (v2.0.1) — authenticates Docker to ECR and outputs the registry URL — avoids hardcoding account IDs in workflow
- `docker/setup-buildx-action@v3` (v3.12.0) — required by `build-push-action`; enables BuildKit
- `docker/build-push-action@v6` (v6.19.2) — builds image and pushes to ECR — `push: true`, `tags:`, `build-args:` inputs
- `eclipse-temurin:17-jre-jammy` — Docker runtime base — LTS JRE (~300MB vs ~600MB JDK), Ubuntu 22.04 pinned for reproducibility

**Decision: skip `docker/metadata-action`.** Tag derivation is simple enough to handle with `${GITHUB_REF_NAME#v}` in a one-line shell step. The metadata action adds overhead not justified for a single-trigger pipeline.

**Decision: skip ECR layer cache for v1.2.** Docker build time is dominated by the 864MB tarball COPY layer, not by Dockerfile instructions. ECR registry cache adds storage cost and IAM permission complexity without meaningful build time improvement. Add in a follow-on milestone after baseline pipeline is proven.

### Expected Features

**Must have (table stakes — P1, required for pipeline to function):**
- Tag-triggered workflow (`on: push: tags: ['v*']`) — the only valid trigger; prevents ECR pollution from branch builds
- Java 21 setup via `actions/setup-java@v5` — enforcer mandates `[21,22)`, fails immediately on any other version
- Maven dependency cache via `setup-java cache: 'maven'` — without this, every run is 45-90 minutes cold; CI is unusable
- Maven build: `./mvnw package -DskipTests -Pdremio.no-lint -pl distribution/server -am -Drevision={version}` — produces the tarball
- Dockerfile adapted to `COPY` instead of `wget DOWNLOAD_URL` — existing Dockerfile cannot reach a runner-local artifact
- Docker runtime base changed to `eclipse-temurin:17-jre-jammy` — replaces existing `eclipse-temurin:11-jdk`
- `docker/setup-buildx-action@v3` — required by `build-push-action`
- AWS credentials + ECR login (two-step `aws-actions` chain) — authentication prerequisite for push
- Tag version extraction stripping `v` prefix — image tag must be `1.2`, not `v1.2`
- Docker push to ECR with versioned tag + `latest` alias

**Should have (differentiators — P2, add after first successful push):**
- Docker layer cache via `cache-from: type=gha` — cuts Docker build time on repeat runs
- Workflow step summary output (`GITHUB_STEP_SUMMARY`) — logs pushed image URI for traceability
- ECR image scanning enabled on the ECR repository (AWS console setting, not a workflow change)
- Tag format validation step that exits non-zero if tag does not match `vX.Y.Z`

**Defer (v2+):**
- ARM64 multi-platform build — 5-10x slower via QEMU emulation; no stated deployment requirement
- Automated smoke test (pull image + `docker run` health check) after push
- SBOM / supply chain attestations
- Separate test workflow (`ci.yml`) triggered on PRs vs release workflow on tags

### Architecture Approach

The pipeline is a single-job, sequential-step workflow. The Maven build hands off to Docker via the runner filesystem: the tarball at `distribution/server/target/dremio-community-{version}.tar.gz` is staged into a `docker-context/` directory and consumed by a `COPY` instruction in the Dockerfile. The Docker build context is that staging directory (not the full repository root), keeping context size small and avoiding the need for a `.dockerignore` file.

**Major components:**
1. **Maven build stage** — `./mvnw package -pl distribution/server -am -DskipTests -Pdremio.no-lint -Drevision={version}` — produces `dremio-community-{version}.tar.gz`; Java 21 JDK on runner; `[21,22)` enforcer must pass
2. **Dockerfile adaptation** — replace `ARG DOWNLOAD_URL` + `RUN wget` with `ARG TARBALL_PATH` + `COPY`; change base image from `eclipse-temurin:11-jdk` to `eclipse-temurin:17-jre-jammy`; use multi-stage build to prevent tarball layer from persisting in final image
3. **AWS auth chain** — `configure-aws-credentials` (sets env vars) then `amazon-ecr-login` (writes Docker credentials, outputs registry URL); both must be in the same job as the push step
4. **Docker build + push** — `build-push-action@v6` with `context: docker-context`, `file: distribution/docker/Dockerfile`, `push: true`, versioned tag + `latest`

**Version alignment:** Git tag `v1.2` drives everything. Shell strips `v` to produce `1.2`. Maven receives `-Drevision=1.2`, producing `dremio-community-1.2.tar.gz`. Docker image is tagged `:1.2`. No ambiguity between Maven version and Docker tag.

**Build context strategy:** Stage the tarball into a `docker-context/` directory (`mkdir docker-context && cp distribution/server/target/dremio-community-*.tar.gz docker-context/dremio.tar.gz`) and pass `context: docker-context` to `build-push-action`. This avoids sending the full repository (including Maven cache) to the Docker daemon and eliminates `.dockerignore` maintenance. Pass `TARBALL_PATH=dremio.tar.gz` as a build arg.

### Critical Pitfalls

1. **Maven enforcer rejects Java 22+ immediately** — pin `java-version: '21'` in `setup-java`; never use `'latest'`, `'22'`, or any unversioned string. The enforcer runs at the `validate` phase before any code compiles. Recovery is a one-line YAML fix but wastes a 5-minute runner startup on every failed attempt. Phase 1.

2. **Dockerfile `wget DOWNLOAD_URL` is incompatible with CI local builds** — the existing Dockerfile is designed to download from `download.dremio.com`; there is no remote URL for a runner-local artifact. Replace `ARG DOWNLOAD_URL` + `RUN wget` with `ARG TARBALL_PATH` + `COPY`. This is the most important Dockerfile change and must precede any Docker step in the workflow. Phase 2.

3. **Maven cold builds are 45-90 minutes without cache** — configure `actions/setup-java@v5` with `cache: 'maven'`; it keys automatically on `**/pom.xml`. Also pass `-Drevision` explicitly so Maven-installed `com.dremio:*` artifacts in `~/.m2` don't accumulate different version strings and inflate the cache. Phase 1.

4. **ECR auth chain must stay in one job** — `configure-aws-credentials` + `ecr-login` + `build-push-action` must all be in the same job; the ECR token written to `~/.docker/config.json` does not cross job boundaries. Use `@v2` of `amazon-ecr-login` and reference `${{ steps.login-ecr.outputs.registry }}` (never a hardcoded ECR URI). Phase 3.

5. **Docker image bloat from two-layer COPY + tar pattern** — naive `COPY tarball` then `RUN tar` produces two large layers; the tarball layer (~864MB) persists in image history after extraction, inflating the final image to 2.5-3GB. Use multi-stage build (extractor stage + runtime stage with `COPY --from=extractor`) to produce a final image under 1.5GB. Design multi-stage from the start — retrofitting is painful. Phase 2.

6. **Missing GitHub Secrets cause silent empty-string failures** — `${{ secrets.MISSING }}` evaluates to `""` without error. Create all secrets (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `ECR_REPOSITORY`) before pushing the workflow. Test IAM permissions locally with `aws ecr describe-repositories` before adding keys to GitHub. Phase 3.

---

## Implications for Roadmap

The natural implementation sequence follows dependency order: validate Maven build in CI first, then validate the Dockerfile change locally, then wire up ECR authentication and the full push. This matches the Architecture research's explicit recommended build order.

### Phase 1: Maven Build in CI

**Rationale:** The Maven build is the longest step (45-90 minutes cold) and most likely to fail for project-specific reasons unrelated to Docker or AWS. Validating it first in isolation means Phases 2 and 3 start from a known-good foundation. The Java 21 enforcer and Maven revision handling must be correct before anything else is added.

**Delivers:** A working GitHub Actions workflow that triggers on `v*` tags, installs Java 21 with Maven cache, and produces `distribution/server/target/dremio-community-{version}.tar.gz`. The workflow exits after Maven (no Docker or ECR steps). A post-Maven step lists the target directory to confirm the tarball exists at the expected path.

**Addresses (P1 features):** Tag-triggered workflow, Java 21 setup, Maven dependency cache, Maven build producing tarball.

**Avoids:**
- Pitfall 1 (Java enforcer) — pin `java-version: '21'`
- Pitfall 2 (Maven cold build) — `cache: 'maven'` in `setup-java`
- Pitfall 10 (Maven `revision` cache pollution) — pass explicit `-Drevision=${{ steps.tag.outputs.VERSION }}`

**Key implementation decisions:**
- Use `./mvnw` not `mvn` to pick up the project's pinned Maven 3.9.9
- Derive version: `echo "VERSION=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT`
- Pass `-Drevision=${{ steps.tag.outputs.VERSION }}` to Maven
- Verify that `-pl distribution/server -am` includes the RBAC and Iceberg REST Catalog modules added in v1.0 and v1.1 (run `./mvnw dependency:tree -pl distribution/server` before committing)

### Phase 2: Dockerfile Adaptation

**Rationale:** The Dockerfile change is a prerequisite for any Docker build step in the workflow. It is faster to validate locally (`docker build` on a developer machine) than to iterate in CI, where each attempt requires a full 30-45 minute Maven build before the Docker step is reached. Keeping this as a separate phase from Phase 3 means ECR credentials are not needed to test image correctness.

**Delivers:** A modified `distribution/docker/Dockerfile` using multi-stage build: `COPY` + extract in a build stage, final image from a clean `eclipse-temurin:17-jre-jammy` base. A locally buildable Docker image that starts Dremio correctly (`docker run -p 9047:9047 {image}` shows `Server is up` in logs). Image size under 1.5GB.

**Addresses (P1 features):** Dockerfile COPY adaptation, Docker runtime base update (Java 11 JDK -> Java 17 JRE), image size optimization.

**Avoids:**
- Pitfall 3 (wget incompatibility) — `COPY` replaces `wget`
- Pitfall 4 (Java version mismatch) — base image `eclipse-temurin:17-jre-jammy`; runtime JRE not JDK
- Pitfall 7 (Docker image size bloat) — multi-stage build eliminates tarball layer from final image

**Key implementation decisions:**
- Multi-stage Dockerfile: Stage 1 extracts tarball; Stage 2 is the final runtime image with `COPY --from=stage1`
- Use `ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"` for future flexibility
- Docker build command for local testing: `mkdir docker-context && cp distribution/server/target/dremio-community-*.tar.gz docker-context/dremio.tar.gz && docker build --build-arg TARBALL_PATH=dremio.tar.gz -f distribution/docker/Dockerfile docker-context`

### Phase 3: ECR Authentication and Push

**Rationale:** Requires AWS infrastructure (IAM user, ECR repository) and GitHub Secrets to exist. Depends on Phases 1 and 2 being proven working. This phase is the lowest-risk once prior phases are validated — the `aws-actions` auth chain is a well-documented, official pattern.

**Delivers:** The complete end-to-end workflow. Tag push triggers Maven build, Docker image build, and ECR push. Image appears in ECR with tags `{version}` and `latest`. A full log audit confirms no secrets appear in workflow output.

**Addresses (P1 features):** AWS credentials configuration, ECR login, image tag extraction, Docker push to ECR, versioned + latest tags.

**Avoids:**
- Pitfall 5 (ECR auth token version mismatch) — use `amazon-ecr-login@v2`; reference registry as `${{ steps.login-ecr.outputs.registry }}`
- Pitfall 6 (missing/misconfigured IAM secrets) — create all secrets and test IAM locally before first workflow run
- Pitfall 8 (tag parsing edge cases) — add validation step: regex check `^v[0-9]+\.[0-9]+\.[0-9]+$` before version is used downstream
- Pitfall 9 (secrets leaked in logs) — never pass `AWS_*` keys as Docker `--build-arg`; add `::add-mask::` for ECR registry URI

**Key implementation decisions:**
- IAM policy: use `AmazonEC2ContainerRegistryPowerUser` managed policy for the CI user; scope `ecr:*` image actions to the specific repository ARN; `ecr:GetAuthorizationToken` must be `Resource: "*"`
- Create the ECR repository in AWS before the first pipeline run (it does not auto-create on push)
- Tag the image with both `{version}` and `latest`; decide before Phase 3 whether pre-release tags (e.g., `v1.2-rc1`) should move `latest`
- Add `echo "Pushed: $IMAGE_URI" >> $GITHUB_STEP_SUMMARY` for traceability

### Phase Ordering Rationale

- Phase 1 before Phase 2: Maven must succeed before Docker can proceed; Maven failure modes (enforcer, module graph, caching) are independent of Dockerfile changes and faster to debug without Docker complexity in the loop.
- Phase 2 before Phase 3: Dockerfile changes are faster to iterate locally than in CI; a broken Docker build in CI requires a full 30-45 minute Maven build before the failure is discovered.
- All three phases are sequentially dependent: each phase's output is a hard prerequisite for the next.

### Research Flags

Phases with standard, well-documented patterns (no deeper research needed):
- **Phase 1 (Maven build):** All patterns verified. Java 21 enforcer constraint, Maven wrapper usage, `setup-java` cache configuration — no unknowns.
- **Phase 2 (Dockerfile):** Multi-stage Dockerfile pattern is standard Docker. The wget-to-COPY substitution is mechanical. No unknowns once the build context strategy is decided.
- **Phase 3 (ECR push):** The `aws-actions` auth chain is official and stable. No unknowns beyond AWS account setup (out-of-code scope).

**One open decision to resolve before implementation begins:**
- `configure-aws-credentials` version pin: STACK.md notes `v6.0.0` is the latest release tag but recommends `@v4`. Verify against the action's README before Phase 3 to confirm whether `@v4` or `@v6` is the correct pin for the `aws-access-key-id` + `aws-secret-access-key` input format.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All action versions verified via GitHub API; Docker Hub tag confirmed; project `pom.xml`, `.mvn/wrapper/`, and `distribution/docker/Dockerfile` inspected directly |
| Features | HIGH | P1/P2/P3 feature set is unambiguous for this scope; all features are standard GitHub Actions / ECR patterns with no novel integration points |
| Architecture | HIGH | Two-file change scope confirmed; all component boundaries and data flows based on direct codebase inspection and verified artifact paths |
| Pitfalls | HIGH | Most pitfalls derived from direct codebase measurement (tarball 864MB, Maven repo 3.3GB, enforcer range `[21,22)`, existing Dockerfile `wget` pattern) |

**Overall confidence:** HIGH

### Gaps to Address

- **`-pl distribution/server -am` module graph verification:** Confirm the RBAC and Iceberg REST Catalog modules added in v1.0 and v1.1 are in the transitive dependency graph of `distribution/server` before committing to the partial build flag. Run `./mvnw dependency:tree -pl distribution/server` locally. If they are missing, the tarball will silently lack custom features. A 5-minute verification that prevents a correctness bug.

- **`configure-aws-credentials` major version pin:** STACK.md notes MEDIUM confidence on whether to pin `@v4` or `@v6`. Check the action README before Phase 3 implementation. If the `aws-access-key-id` / `aws-secret-access-key` input format is unchanged in v6, `@v6` is preferred. If there are breaking changes, use `@v4`.

- **`latest` tag behavior on pre-release tags:** The workflow trigger `tags: ['v*']` matches `v1.2-rc1`. Decide before Phase 3 whether all `v*` tags move `latest`, or whether a semver condition should gate `latest` to production releases only (e.g., only tags matching `vX.Y.Z` without pre-release suffix). This is a policy decision, not a technical one.

---

## Sources

### Primary (HIGH confidence — live API verification + direct codebase inspection)
- GitHub API: `actions/checkout@v6` (v6.0.2), `actions/setup-java@v5` (v5.2.0), `aws-actions/amazon-ecr-login@v2` (v2.0.1), `aws-actions/configure-aws-credentials` (v6.0.0 latest release), `docker/setup-buildx-action@v3` (v3.12.0), `docker/build-push-action@v6` (v6.19.2) — all verified Feb 2026
- Docker Hub API: `eclipse-temurin:17-jre-jammy` — confirmed active, updated 2026-02-17
- Repository `pom.xml` — Java 21 enforcer `[21,22)`, `maven.compiler.release=11`, 158 Maven modules
- Repository `.mvn/wrapper/maven-wrapper.properties` — Maven 3.9.9
- Repository `.mvn/maven.config` — `revision` property baseline value
- Repository `distribution/server/target/dremio-community-26.0.5-*.tar.gz` — 864MB measured; naming pattern confirmed
- Repository `distribution/docker/Dockerfile` — `ARG DOWNLOAD_URL` + `wget` pattern confirmed; `eclipse-temurin:11-jdk` base confirmed
- Local `~/.m2/repository` — 3.3GB measured; Maven cache size baseline
- GitHub runner-images README — `ubuntu-latest` = `ubuntu-24.04` as of Feb 2026; ~73GB disk available

### Secondary (HIGH confidence — official documentation, stable patterns)
- `actions/setup-java` README — `cache: 'maven'`, `distribution: 'temurin'` inputs confirmed
- `aws-actions/configure-aws-credentials` README — `aws-access-key-id` / `aws-secret-access-key` inputs confirmed
- `aws-actions/amazon-ecr-login` README — `registry` step output confirmed
- `docker/build-push-action` README — `setup-buildx-action` dependency confirmed
- AWS ECR documentation — 12-hour authorization token lifetime; `ecr:GetAuthorizationToken` requires `Resource: "*"` (control-plane operation)
- GitHub Actions documentation — `on.push.tags` syntax, `GITHUB_REF_NAME` env var, `GITHUB_OUTPUT` mechanism, secrets interpolation (`${{ secrets.MISSING }}` = `""` silently)
- Docker documentation — multi-stage build layer isolation; `COPY` + separate `RUN tar` creates two immutable layers

### Gaps (MEDIUM confidence — needs verification before Phase 3)
- `aws-actions/configure-aws-credentials` major version pin (`@v4` vs `@v6`) — verify against action README before Phase 3 implementation

---

*Research completed: 2026-02-20*
*Ready for roadmap: yes*
