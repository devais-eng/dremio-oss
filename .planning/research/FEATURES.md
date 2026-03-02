# Feature Research: GitHub Actions CI/CD — Docker Build + ECR Push

**Domain:** GitHub Actions pipeline for Docker distribution of Dremio OSS fork to private AWS ECR
**Researched:** 2026-02-20
**Confidence:** HIGH (GitHub Actions, Docker Buildx, ECR auth — all stable, well-documented APIs within training cutoff)

---

## Context: What This Pipeline Does

Maven build produces a tarball at
`distribution/server/target/dremio-community-{version}.tar.gz`.
The existing `distribution/docker/Dockerfile` downloads Dremio from a URL
(`ARG DOWNLOAD_URL`) and installs it. The pipeline must:

1. Trigger on `git tag push` (e.g., `v1.2.0`)
2. Build the Maven tarball with Java 21 (compile-time JDK)
3. Adapt the Dockerfile to copy the tarball from the local build (not download from the internet)
4. Build a Docker image using Java 17 as the runtime base
5. Push to a private AWS ECR registry using IAM access keys
6. Tag the image with the git tag stripped of the `v` prefix (e.g., `v1.2.0` → `1.2.0`)

**Key codebase facts that affect the pipeline:**

- Maven build: `./mvnw package -DskipTests` or `mvn package -DskipTests` from root
- Build output tarball: `distribution/server/target/dremio-community-*.tar.gz`
- Existing Dockerfile uses `ARG DOWNLOAD_URL` + `wget` — must be overridden to `COPY` locally
- Runtime Java version: Java 17 (`eclipse-temurin:17-jre` replaces the existing `eclipse-temurin:11-jdk`)
- Build Java version: Java 21 (set up via `actions/setup-java`)
- Registry: private AWS ECR, IAM access keys stored in GitHub Secrets

---

## Category 1: Table Stakes

Features the pipeline must have to function at all. Missing any of these = the pipeline either does not run, does not push, or produces an unusable image.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Tag-triggered workflow | Prevents publishing from every commit; release intent is explicit | LOW | `on: push: tags: ['v*']` |
| Java 21 setup for Maven | Maven build fails without the correct JDK | LOW | `actions/setup-java@v4` with `java-version: '21'` |
| Maven build step | Produces the tarball artifact the Docker image packages | MEDIUM | Skip tests (`-DskipTests`), skip UI build if possible |
| Maven dependency cache | Build takes 20–60 min without cache; CI would be unusable | MEDIUM | `actions/cache@v4` keyed on `pom.xml` hash tree |
| Local tarball copy in Dockerfile | Existing Dockerfile uses `wget DOWNLOAD_URL` which cannot reach a local build artifact | LOW | Override via `ARG` replacement or Dockerfile edit |
| Docker Buildx setup | Required for layer caching and multi-platform builds | LOW | `docker/setup-buildx-action@v3` |
| ECR login | Push fails without authentication | LOW | `aws-actions/amazon-ecr-login@v2` |
| AWS credentials configuration | ECR login requires AWS access key + secret in environment | LOW | `aws-actions/configure-aws-credentials@v4` with `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` secrets |
| Image tag from git tag | `v1.2.0` → `1.2.0`; consumers expect SemVer tags without `v` prefix | LOW | `steps.tag.outputs.version=${GITHUB_REF#refs/tags/v}` |
| ECR repository URI in tag | Image must be tagged with full ECR URI for push to succeed | LOW | Compose as `{account}.dkr.ecr.{region}.amazonaws.com/{repo}:{version}` |
| Docker push to ECR | The actual distribution step | LOW | `docker/build-push-action@v6` with `push: true` |
| Job-level permissions | `contents: read` minimum; avoids over-permissioning | LOW | Explicit `permissions:` block in workflow |

### Complexity Detail: Maven Build Step

The Maven build for the full Dremio OSS tree is the highest-complexity table-stakes step:

- **Build time:** 20–60 minutes depending on cache hit rate. Without cache it can exceed the 60-minute GitHub-hosted runner default per-job limit (configurable to 360 minutes).
- **Memory:** Dremio's full Maven build can exceed 4GB heap. GitHub-hosted runners (`ubuntu-latest`) provide 7GB RAM. This is typically sufficient for `-DskipTests` builds but may need `-Xmx4g` Maven opts.
- **Profile:** Consider `-pl distribution/server -am` (build only the server distribution module and its dependencies) instead of building all modules, to reduce build time. This requires verification that this profile does not drop the custom RBAC / Iceberg REST Catalog modules.
- **Test skip:** `-DskipTests` is mandatory for a packaging-only pipeline.

---

## Category 2: Differentiators

Features that are not required for basic operation but significantly improve reliability, security, or developer experience. Justified for an initial build of this pipeline.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Docker layer cache (registry mode) | Dramatically speeds up Docker builds by reusing unchanged layers across runs | MEDIUM | `cache-from: type=registry` + `cache-to: type=registry,mode=max` pointing at ECR |
| `latest` tag alias | Makes image discovery trivial; `docker pull {repo}:latest` always gets newest release | LOW | Add `latest` alongside the version tag in `tags:` list |
| Build status badge | README shows current pipeline state; standard OSS signal | LOW | GitHub auto-generates at `{repo}/actions/workflows/{file}/badge.svg` |
| Workflow summary output | Logs the pushed image URI in the job summary for traceability | LOW | `echo "Pushed: $IMAGE_URI" >> $GITHUB_STEP_SUMMARY` |
| ECR image scan on push | AWS ECR can auto-scan pushed images for CVEs | LOW | Enable in ECR repository settings (not the workflow itself); just document as expected configuration |
| Explicit runner pinning | Pin to `ubuntu-22.04` (not `ubuntu-latest`) for reproducible builds | LOW | `runs-on: ubuntu-22.04` |
| Secrets named consistently | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `ECR_REGISTRY`, `ECR_REPOSITORY` as GitHub Secrets | LOW | Document naming convention in PITFALLS; easy to get wrong |

### Docker Layer Cache (Registry Mode) — Detail

ECR supports OCI-compliant layer caching when used as the cache backend. The workflow passes:

```yaml
cache-from: type=registry,ref=$ECR_REGISTRY/$ECR_REPOSITORY:buildcache
cache-to: type=registry,ref=$ECR_REGISTRY/$ECR_REPOSITORY:buildcache,mode=max
```

This stores the build cache as a separate image tag in the same ECR repository. It requires the ECR repository to exist before the first run and the IAM principal to have `ecr:BatchGetImage`, `ecr:PutImage`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload` permissions — all covered by `AmazonEC2ContainerRegistryPowerUser`.

**Alternative — GitHub Actions Cache backend:** `type=gha` uses GitHub's built-in cache (5GB limit per repo). Simpler setup but slower for large layers because GitHub cache is evicted after 7 days of non-use. Prefer ECR registry cache for a project with large Docker images like Dremio.

---

## Category 3: Anti-Features

Features that are commonly added to CI/CD pipelines but create problems in this specific context.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Running tests in CI | Comprehensive quality gate | Dremio's full test suite takes hours; a Docker packaging pipeline is not the right place for it; GitHub-hosted runners will timeout | Separate test workflow triggered on PR, not on tag push |
| Building the UI in CI | Complete build artifact | Dremio's frontend build (pnpm + webpack) adds 10–20 minutes and requires Node.js setup; the tarball for the Docker image only needs the server JAR | Use `-DskipTests` and profile-limit Maven to `distribution/server -am`; if UI is not in the server tarball, skip UI explicitly |
| Multi-platform Docker build (ARM64) | Broader deployment compatibility | QEMU emulation for ARM64 on x86 GitHub runners is 5–10x slower than native; adds 30+ minutes for a Java app Docker build with no immediate need | Build only `linux/amd64` for the initial pipeline; add ARM64 when there is a concrete deployment need |
| Pushing on every branch push | Continuous delivery | Pollutes ECR with development images; makes it impossible to know what image corresponds to what release | Keep `on: push: tags: ['v*']` as the only trigger |
| Separate Docker registry (DockerHub/GHCR) alongside ECR | Redundancy | Doubles push time and complexity; two authentication setups; secrets sprawl | Push only to ECR per the project specification |
| Inline AWS credentials in workflow YAML | Convenience | Credential leak risk; credentials in YAML (even via secrets) should be isolated to the configure-aws-credentials action only | Use `aws-actions/configure-aws-credentials@v4` which handles env var injection scoped to the step |
| Full Maven clean build without cache | Simplicity | 20–60 minute build on every tag push; first run must populate cache, subsequent runs must hit it | Always configure `actions/cache@v4` for `~/.m2/repository` |

---

## Feature Dependencies

```
[Tag Push Trigger]
    └──fires──> [Job]
                    ├──requires──> [Checkout]
                    │                  └──provides──> git ref for tag extraction
                    │
                    ├──requires──> [Java 21 Setup]
                    │                  └──enables──> [Maven Build]
                    │                                    └──produces──> [Tarball artifact]
                    │
                    ├──requires──> [Maven Dependency Cache]
                    │                  └──accelerates──> [Maven Build]
                    │
                    ├──requires──> [AWS Credentials Configuration]
                    │                  └──enables──> [ECR Login]
                    │                                    └──enables──> [Docker Push]
                    │
                    └──requires──> [Docker Buildx Setup]
                                       └──enables──> [Docker Build + Push]
                                                          ├──requires──> [Tarball artifact] (from Maven Build)
                                                          ├──requires──> [ECR Login] (auth)
                                                          └──uses──> [Docker Layer Cache] (optional, from ECR)
```

### Dependency Notes

- **Maven Build must complete before Docker Build:** The Docker build COPYs the tarball from `distribution/server/target/`. If Maven fails, the Docker step must not run. Use job-level `needs:` or sequential steps.
- **ECR Login must precede Docker Push:** `aws-actions/amazon-ecr-login@v2` writes Docker credentials to `~/.docker/config.json`. The `docker/build-push-action` reads this file implicitly.
- **AWS Credentials must precede ECR Login:** `amazon-ecr-login` calls the AWS API; it requires the environment variables set by `configure-aws-credentials`.
- **Docker Buildx must be set up before `build-push-action`:** `docker/setup-buildx-action@v3` creates the builder instance that `build-push-action` uses. Without it, cache-from/cache-to registry mode does not work.
- **Dockerfile must be modified before Docker Build:** The existing `Dockerfile` uses `ARG DOWNLOAD_URL` + `wget`. The pipeline must either pass the tarball path via build arg and replace the download with a `COPY`, or a modified Dockerfile must exist in the repository.

---

## MVP Definition

### Launch With (v1.2 pipeline milestone)

Minimum: pipeline pushes a correctly tagged, runnable Dremio image to ECR on every `v*` tag.

- [ ] Tag-triggered workflow (`on: push: tags: ['v*']`) — defines when pipeline runs
- [ ] `actions/setup-java@v4` with Java 21 — enables Maven build
- [ ] `actions/cache@v4` for `~/.m2/repository` — makes build time acceptable
- [ ] Maven build step producing tarball — creates the artifact to package
- [ ] Dockerfile adapted to `COPY` local tarball (not `wget` from URL) with Java 17 runtime base — builds correct image
- [ ] `docker/setup-buildx-action@v3` — enables `build-push-action` with cache support
- [ ] `aws-actions/configure-aws-credentials@v4` with IAM key secrets — authenticates to AWS
- [ ] `aws-actions/amazon-ecr-login@v2` — obtains Docker credentials for ECR
- [ ] Image tag extraction: strip `v` prefix from `GITHUB_REF` — correct tag format
- [ ] `docker/build-push-action@v6` with `push: true`, version tag, `latest` tag — pushes to ECR

### Add After Validation (v1.x)

- [ ] Docker layer cache via ECR registry (`cache-from`/`cache-to`) — add after first push confirms pipeline works; requires cache ECR repository tag permissions
- [ ] Build summary output (`GITHUB_STEP_SUMMARY`) — trivial quality-of-life addition
- [ ] ECR image scan (repository-level setting, not workflow) — document as recommended ECR configuration

### Future Consideration (v2+)

- [ ] ARM64 platform build — when deployment targets include ARM instances
- [ ] Automated smoke test (pull image + `docker run` health check) — after image push, confirm container starts
- [ ] SBOM generation — when supply chain compliance is required

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Tag-triggered workflow | HIGH | LOW | P1 |
| Java 21 + Maven build | HIGH | MEDIUM | P1 |
| Maven dependency cache | HIGH | LOW | P1 |
| Dockerfile local COPY adaptation | HIGH | LOW | P1 |
| Docker Buildx setup | HIGH | LOW | P1 |
| AWS credentials + ECR login | HIGH | LOW | P1 |
| Image tagging (version + latest) | HIGH | LOW | P1 |
| Docker push to ECR | HIGH | LOW | P1 |
| Docker layer cache (ECR registry) | MEDIUM | MEDIUM | P2 |
| Build summary output | LOW | LOW | P2 |
| ECR image scan (repository config) | MEDIUM | LOW | P2 |
| Explicit runner pinning | LOW | LOW | P2 |
| ARM64 platform build | LOW | HIGH | P3 |
| Automated smoke test post-push | MEDIUM | MEDIUM | P3 |

**Priority key:**
- P1: Must have for pipeline to function and publish images
- P2: Should have; add once P1 is working and confirmed
- P3: Nice to have; future consideration

---

## Dockerfile Adaptation — Key Design Decision

The existing `distribution/docker/Dockerfile` downloads Dremio from a URL:

```dockerfile
ARG DOWNLOAD_URL
RUN wget -q "${DOWNLOAD_URL}" -O dremio.tar.gz && tar vxfz dremio.tar.gz ...
```

For a local CI build, the tarball is at `distribution/server/target/dremio-community-*.tar.gz`.
The pipeline cannot `wget` a local file path. Two valid approaches:

**Option A — Modify Dockerfile in-tree (recommended):**
Change the Dockerfile to `COPY` the tarball from a build-context-relative path and adjust the runtime base:

```dockerfile
ARG JAVA_IMAGE="eclipse-temurin:17-jre"
FROM ${JAVA_IMAGE} AS base
COPY distribution/server/target/dremio-community-*.tar.gz /tmp/dremio.tar.gz
RUN tar vxfz /tmp/dremio.tar.gz -C /opt/dremio --strip-components=1 && rm /tmp/dremio.tar.gz
```

Build context must be the repository root (not `distribution/docker/`) so the `COPY` path resolves.

**Option B — Separate CI Dockerfile:**
Create `distribution/docker/Dockerfile.ci` used only by the GitHub Actions workflow. Keeps the existing Dockerfile unchanged for compatibility with upstream tooling.

Both approaches are valid. Option A is simpler (one Dockerfile, one source of truth). Option B preserves the original Dockerfile for teams using the `DOWNLOAD_URL` pattern. The roadmap should specify which approach to use; note this as a decision point.

**Java version change:** The existing Dockerfile uses `eclipse-temurin:11-jdk` as base. The project context specifies Java 17 runtime. The `JAVA_IMAGE` build arg must be set to `eclipse-temurin:17-jre` (not `17-jdk`) — `jre` is smaller and production-appropriate. The Maven build uses Java 21 (compile-time only) and is not related to the Docker runtime base.

---

## Build Status Badge

GitHub Actions auto-generates a badge URL:

```
https://github.com/{owner}/{repo}/actions/workflows/{workflow-filename}/badge.svg
```

Add to `README.md` after the workflow file is committed and the first run completes. Badge reflects the last run on the default branch (not tag-triggered runs, unless the default branch filter is added). For tag-triggered pipelines, the badge may show "no status" until a tag is pushed — this is expected behavior.

---

## Sources

- **GitHub Actions official documentation (HIGH confidence):**
  - `on.push.tags` trigger syntax — stable since GitHub Actions v1
  - `actions/setup-java@v4` — current major version as of 2025
  - `actions/cache@v4` — current major version with improved cache key handling
  - `docker/setup-buildx-action@v3`, `docker/build-push-action@v6` — Docker's official actions
  - `aws-actions/configure-aws-credentials@v4` — AWS official action
  - `aws-actions/amazon-ecr-login@v2` — AWS official action
  - `GITHUB_REF` environment variable format for tag refs

- **Codebase analysis (HIGH confidence):**
  - `distribution/docker/Dockerfile` — existing Dockerfile uses `ARG DOWNLOAD_URL` + `wget`; `eclipse-temurin:11-jdk` base; examined directly
  - `distribution/server/target/dremio-community-26.0.5-202509091642240013-f5051a07.tar.gz` — confirms tarball output path and naming pattern
  - `pom.xml` root — confirms Maven multi-module structure; 15 modules requiring full build or `--projects` pruning

- **Standard CI/CD patterns (HIGH confidence — stable industry practices):**
  - Maven dependency caching via `~/.m2/repository` is the canonical approach
  - ECR registry-mode Docker layer caching is the standard for private registries
  - IAM access key secrets as `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` is the established pattern
  - `v` prefix stripping: `${GITHUB_REF#refs/tags/v}` is standard bash parameter expansion

---

*Feature research for: GitHub Actions Docker + ECR pipeline for Dremio OSS fork*
*Milestone: CI/CD pipeline (subsequent to RBAC v1.0 and Iceberg REST Catalog v1.1)*
*Researched: 2026-02-20*
