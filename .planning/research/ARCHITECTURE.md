# Architecture Research

**Domain:** Dremio OSS — GitHub Actions CI/CD pipeline (Docker + ECR distribution)
**Researched:** 2026-02-20
**Confidence:** HIGH (Maven/Docker/GH Actions patterns are stable and well-documented; all Dremio
build artifact paths verified directly from codebase)

---

## Standard Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│              TRIGGER: git tag push  (refs/tags/v*)                  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│              GitHub Actions Runner (ubuntu-latest)                   │
│                                                                     │
│  JOB: build-and-push                                                │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  STAGE 1: Maven Build                                        │   │
│  │  actions/setup-java@v4 (Java 21, temurin)                   │   │
│  │  mvn package -pl distribution/server -am -DskipTests        │   │
│  │  OUTPUT: distribution/server/target/dremio-community-       │   │
│  │          ${version}.tar.gz                                   │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                        │
│  ┌──────────────────────────▼──────────────────────────────────┐   │
│  │  STAGE 2: AWS Authentication                                 │   │
│  │  aws-actions/configure-aws-credentials@v4                    │   │
│  │  Inputs: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY            │   │
│  │          AWS_DEFAULT_REGION (secret or var)                  │   │
│  │  aws-actions/amazon-ecr-login@v2                             │   │
│  │  OUTPUT: registry URL (e.g. 123456789.dkr.ecr.us-east-1.    │   │
│  │          amazonaws.com)                                       │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                        │
│  ┌──────────────────────────▼──────────────────────────────────┐   │
│  │  STAGE 3: Docker Build + Push                                │   │
│  │  docker/setup-buildx-action@v3                               │   │
│  │  docker/metadata-action@v5  (derives tags from git tag)      │   │
│  │  docker/build-push-action@v6                                 │   │
│  │    context: .                                                │   │
│  │    file: distribution/docker/Dockerfile                      │   │
│  │    push: true                                                │   │
│  │    tags: ECR_REGISTRY/REPO:v1.2.3, ECR_REGISTRY/REPO:latest │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│              AWS ECR (Private Registry)                              │
│              123456789.dkr.ecr.{region}.amazonaws.com/{repo}        │
│              Tags: v1.2.3, latest                                    │
└─────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Implementation |
|-----------|----------------|----------------|
| Workflow trigger | Fires only on semver tag pushes | `on: push: tags: ['v*']` |
| Maven build | Produces the server tarball | `mvn package -pl distribution/server -am -DskipTests` |
| AWS credential step | Configures AWS SDK env vars for the runner | `aws-actions/configure-aws-credentials@v4` with IAM keys from secrets |
| ECR login step | Authenticates Docker daemon against ECR | `aws-actions/amazon-ecr-login@v2`; outputs `registry` URL |
| Metadata action | Derives Docker tags from git tag | `docker/metadata-action@v5`; maps `refs/tags/v1.2.3` → `v1.2.3` + `latest` |
| Buildx + build-push | Builds and pushes the Docker image | `docker/build-push-action@v6`; passes tarball path as build arg |
| Adapted Dockerfile | Installs Dremio from local tarball via COPY | `COPY` replaces `wget "${DOWNLOAD_URL}"` |

---

## File Change Map — New vs Modified

| File | Status | Change |
|------|--------|--------|
| `.github/workflows/docker-ecr.yml` | **NEW** | The CI/CD workflow definition |
| `distribution/docker/Dockerfile` | **MODIFIED** | Replace `ARG DOWNLOAD_URL` + `wget` block with `ARG TARBALL_PATH` + `COPY` |

No other files are needed. The Maven build, pom.xml, `.mvn/maven.config`, and all existing source files are untouched.

---

## Recommended Project Structure

```
.github/
└── workflows/
    └── docker-ecr.yml          # Tag-triggered build, push to ECR

distribution/
└── docker/
    └── Dockerfile              # MODIFIED: wget -> COPY
```

### Structure Rationale

- `.github/workflows/docker-ecr.yml` — GitHub Actions requires workflow files here. One file per
  pipeline is the standard pattern. Splitting into reusable workflows (`.github/workflows/build.yml`
  + `.github/workflows/push.yml`) is premature for a single pipeline.

- `distribution/docker/Dockerfile` — keep it in its existing location. The `build-push-action`
  `file:` input accepts any path; moving the Dockerfile only adds confusion.

---

## Architectural Patterns

### Pattern 1: Single-Job Tag-Triggered Workflow

**What:** One workflow file, one job, sequential steps within that job. The trigger is a tag push
matching a glob pattern.

**When to use:** When the build, Docker build, and ECR push all belong together and there is no
parallel work to do. Adding a separate `build` job and `push` job (with `needs:`) only makes sense
when you need to run tests in parallel or conditionally skip the push.

**Trade-offs:** Simple to understand and debug. Cannot reuse the build artifact across matrix builds.
Adequate for this milestone.

**Example:**
```yaml
name: Build and Push to ECR

on:
  push:
    tags:
      - 'v*'

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    steps:
      # ... steps in sequence
```

### Pattern 2: Artifact Handoff via Filesystem (Maven → Docker COPY)

**What:** The Maven build produces a tarball at a known path. The Docker build accesses it via
`COPY` using a build argument for the path, or by placing the tarball in the Docker build context.

**When to use:** When you own both the Maven build and the Docker build and they run in the same
CI job. This avoids the network round-trip of uploading to S3 and downloading in the Dockerfile.

**Trade-offs:** Couples the Maven step and Docker step to run in the same job/runner. For large
repositories where the Maven artifact is cached and reused across jobs, a multi-job approach with
`actions/upload-artifact` is better. For a single pipeline, filesystem handoff is simpler and
faster.

**Example — tarball path derived from Maven version:**
```yaml
- name: Derive tarball path
  id: tarball
  run: |
    VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout \
      -pl distribution/server)
    echo "path=distribution/server/target/dremio-community-${VERSION}.tar.gz" \
      >> "$GITHUB_OUTPUT"

- name: Build and push Docker image
  uses: docker/build-push-action@v6
  with:
    context: .
    file: distribution/docker/Dockerfile
    build-args: |
      TARBALL_PATH=${{ steps.tarball.outputs.path }}
    tags: ${{ steps.meta.outputs.tags }}
    push: true
```

### Pattern 3: ECR Authentication via aws-actions

**What:** Two sequential actions handle AWS auth: `configure-aws-credentials` (sets env vars) then
`amazon-ecr-login` (authenticates Docker). The `amazon-ecr-login` action outputs the registry URL
so you do not hardcode it.

**When to use:** Always, when authenticating to ECR with IAM access keys. OIDC federation is the
alternative (no long-lived keys) but requires IAM role configuration that is out of scope here.

**Trade-offs:** IAM access keys are long-lived credentials. They must be rotated manually. They
are stored as GitHub encrypted secrets. If the repository is public, secrets are not exposed to
fork PRs. For a private repository this risk is lower.

**Example:**
```yaml
- name: Configure AWS credentials
  uses: aws-actions/configure-aws-credentials@v4
  with:
    aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
    aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
    aws-region: ${{ secrets.AWS_DEFAULT_REGION }}

- name: Log in to Amazon ECR
  id: login-ecr
  uses: aws-actions/amazon-ecr-login@v2

- name: Build and push
  uses: docker/build-push-action@v6
  with:
    tags: ${{ steps.login-ecr.outputs.registry }}/${{ env.ECR_REPOSITORY }}:${{ github.ref_name }}
```

### Pattern 4: Docker Metadata Action for Tag Derivation

**What:** `docker/metadata-action@v5` reads the git ref and generates `tags:` and `labels:` outputs
following Docker Hub / OCI conventions. When the trigger is `refs/tags/v1.2.3`, it outputs both
`v1.2.3` and `latest` tags by default.

**When to use:** Always. Deriving image tags from git refs manually is error-prone and non-idiomatic.

**Trade-offs:** Adds one extra step but eliminates a class of manual string-manipulation bugs.

**Example:**
```yaml
- name: Extract Docker metadata
  id: meta
  uses: docker/metadata-action@v5
  with:
    images: ${{ steps.login-ecr.outputs.registry }}/${{ env.ECR_REPOSITORY }}
    tags: |
      type=semver,pattern={{version}}
      type=semver,pattern={{major}}.{{minor}}
      type=raw,value=latest,enable={{is_default_branch}}
```

---

## Data Flow

### End-to-End Build to Registry Flow

```
Developer pushes tag: git tag v1.2.3 && git push origin v1.2.3
    |
    v
GitHub receives refs/tags/v1.2.3 push
    |
    v
Workflow trigger fires: on.push.tags matches 'v*'
    |
    v
Runner starts: ubuntu-latest
    |
    v
actions/checkout@v4
  - checks out full repository at the tag ref
    |
    v
actions/setup-java@v4 (Java 21, distribution: temurin)
  - installs Eclipse Temurin JDK 21 (required by maven-enforcer [21,22) rule)
    |
    v
actions/cache@v4 (optional but recommended)
  - caches ~/.m2/repository keyed by pom.xml hash
  - reduces subsequent build time from ~45min to ~10min
    |
    v
mvn package -pl distribution/server -am -DskipTests
  - builds all Maven modules required by distribution/server
  - produces: distribution/server/target/dremio-community-${revision}.tar.gz
  - revision is set in .mvn/maven.config as -Drevision=<value>
  - on CI, pass -Drevision=${GITHUB_REF_NAME#v} to align Maven version with git tag
    |
    v
aws-actions/configure-aws-credentials@v4
  - reads AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION from secrets
  - sets AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION env vars
    |
    v
aws-actions/amazon-ecr-login@v2
  - runs: aws ecr get-login-password | docker login ...
  - outputs: registry = 123456789.dkr.ecr.us-east-1.amazonaws.com
    |
    v
docker/setup-buildx-action@v3
  - creates a BuildKit builder instance (faster, multi-platform capable)
    |
    v
docker/metadata-action@v5
  - input: refs/tags/v1.2.3
  - outputs tags:
      123456789.dkr.ecr.us-east-1.amazonaws.com/dremio-oss:1.2.3
      123456789.dkr.ecr.us-east-1.amazonaws.com/dremio-oss:1.2
      123456789.dkr.ecr.us-east-1.amazonaws.com/dremio-oss:latest
    |
    v
docker/build-push-action@v6
  - context: . (entire repository root)
  - file: distribution/docker/Dockerfile
  - build-args: TARBALL_PATH=distribution/server/target/dremio-community-1.2.3.tar.gz
  - push: true
  - tags: <from metadata-action>
    |
    v
Docker daemon executes modified Dockerfile:
  - FROM eclipse-temurin:11-jdk
  - creates dremio user/group, directories
  - COPY ${TARBALL_PATH} /tmp/dremio.tar.gz
  - tar xfz /tmp/dremio.tar.gz -C /opt/dremio --strip-components=1
  - rm /tmp/dremio.tar.gz
    |
    v
Built image pushed to AWS ECR:
  123456789.dkr.ecr.us-east-1.amazonaws.com/dremio-oss:1.2.3
  123456789.dkr.ecr.us-east-1.amazonaws.com/dremio-oss:latest
```

### Version Alignment Between Maven and Docker

```
.mvn/maven.config: -Drevision=26.0.5-202509091642240013-f5051a07  (local dev default)
        |
        | overridden on CI:
        v
mvn ... -Drevision=${GITHUB_REF_NAME#v}
  where GITHUB_REF_NAME = "v1.2.3"
  strip "v" prefix -> revision = "1.2.3"
        |
        v
Tarball: distribution/server/target/dremio-community-1.2.3.tar.gz
Docker image tag: :1.2.3
```

---

## Dockerfile Adaptation — wget to COPY

### Existing Dockerfile (distribution/docker/Dockerfile)

```dockerfile
ARG JAVA_IMAGE="eclipse-temurin:11-jdk"
FROM ${JAVA_IMAGE} as base

LABEL maintainer=Dremio

ARG DOWNLOAD_URL

RUN \
  apt-get update \
  && apt-get install wget -y \
  && rm -rf /var/lib/apt/lists/* \
  \
  && mkdir -p /opt/dremio \
  && mkdir -p /var/lib/dremio \
  && mkdir -p /var/run/dremio \
  && mkdir -p /var/log/dremio \
  && mkdir -p /opt/dremio/data \
  \
  && groupadd --system dremio --gid 999 \
  && useradd --base-dir /var/lib/dremio --system --uid 999 --gid dremio dremio \
  && chown -R dremio:dremio /opt/dremio/data \
  && chown -R dremio:dremio /var/run/dremio \
  && chown -R dremio:dremio /var/log/dremio \
  && chown -R dremio:dremio /var/lib/dremio \
  && wget -q "${DOWNLOAD_URL}" -O dremio.tar.gz \
  && tar vxfz dremio.tar.gz -C /opt/dremio --strip-components=1 \
  && rm -rf dremio.tar.gz
```

### Adapted Dockerfile — COPY instead of wget

Three changes only:
1. Remove `ARG DOWNLOAD_URL`
2. Add `ARG TARBALL_PATH` (receives path relative to build context)
3. Replace `apt-get install wget` + `wget` line with `COPY ${TARBALL_PATH} /tmp/dremio.tar.gz`

```dockerfile
ARG JAVA_IMAGE="eclipse-temurin:11-jdk"
FROM ${JAVA_IMAGE} as base

LABEL maintainer=Dremio

# TARBALL_PATH is relative to the Docker build context (repository root).
# CI passes: distribution/server/target/dremio-community-${version}.tar.gz
ARG TARBALL_PATH

RUN \
  apt-get update \
  && rm -rf /var/lib/apt/lists/* \
  \
  && mkdir -p /opt/dremio \
  && mkdir -p /var/lib/dremio \
  && mkdir -p /var/run/dremio \
  && mkdir -p /var/log/dremio \
  && mkdir -p /opt/dremio/data \
  \
  && groupadd --system dremio --gid 999 \
  && useradd --base-dir /var/lib/dremio --system --uid 999 --gid dremio dremio \
  && chown -R dremio:dremio /opt/dremio/data \
  && chown -R dremio:dremio /var/run/dremio \
  && chown -R dremio:dremio /var/log/dremio \
  && chown -R dremio:dremio /var/lib/dremio

COPY ${TARBALL_PATH} /tmp/dremio.tar.gz

RUN tar xfz /tmp/dremio.tar.gz -C /opt/dremio --strip-components=1 \
  && rm /tmp/dremio.tar.gz \
  && chown -R dremio:dremio /opt/dremio

EXPOSE 9047/tcp
EXPOSE 31010/tcp
EXPOSE 32010/tcp
EXPOSE 45678/tcp

USER dremio
WORKDIR /opt/dremio
ENV DREMIO_HOME /opt/dremio
ENV DREMIO_PID_DIR /var/run/dremio
ENV DREMIO_GC_LOGS_ENABLED="yes"
ENV DREMIO_GC_LOG_TO_CONSOLE="yes"
ENV DREMIO_LOG_DIR="/var/log/dremio"
ENTRYPOINT ["bin/dremio", "start-fg"]
```

**Why split `RUN` into two:** `COPY` cannot be inside a `RUN` command. The standard pattern is:
- `RUN` for OS setup (directories, users — this layer is stable and cached)
- `COPY` to bring in the artifact (invalidates cache only when tarball changes)
- `RUN` to extract + clean up

**Why `COPY` can use a build arg for the path:** Docker supports `ARG` before `COPY`. The build
arg `TARBALL_PATH` is a path relative to the build context. When `docker build --build-arg
TARBALL_PATH=distribution/server/target/dremio-community-1.2.3.tar.gz -f
distribution/docker/Dockerfile .` is run from the repo root, Docker can access the tarball.

**Build context:** The `build-push-action` `context: .` means the entire repository root is the
build context. The tarball at `distribution/server/target/` is inside this context and reachable
by `COPY`.

**Important:** The build context must not contain the entire Maven repository cache. Add a
`.dockerignore` at the repository root to exclude heavy directories:

```
# .dockerignore (new file at repository root)
.git
dac/ui-lib/node_modules
dac/ui-tools/node_modules
dac/ui-common/node_modules
**/.m2
**/target/archive-tmp
**/target/site
**/target/surefire-reports
# Keep: distribution/server/target/*.tar.gz
```

---

## Complete Workflow File

**Location:** `.github/workflows/docker-ecr.yml` (new file)

```yaml
name: Build and Push Docker Image to ECR

on:
  push:
    tags:
      - 'v*'

env:
  # Set ECR_REPOSITORY as an env var (not a secret) — it is not sensitive.
  # Example: "dremio-oss" or "my-org/dremio"
  ECR_REPOSITORY: dremio-oss

jobs:
  build-and-push:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Cache Maven local repository
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: |
            ${{ runner.os }}-maven-

      - name: Derive version from git tag
        id: version
        run: |
          # Strip the leading "v" from the git tag (e.g. v1.2.3 -> 1.2.3)
          echo "value=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"

      - name: Build Maven distribution tarball
        run: |
          mvn package \
            --batch-mode \
            --no-transfer-progress \
            -pl distribution/server \
            -am \
            -DskipTests \
            -Drevision=${{ steps.version.outputs.value }}

      - name: Locate tarball
        id: tarball
        run: |
          TARBALL="distribution/server/target/dremio-community-${{ steps.version.outputs.value }}.tar.gz"
          if [ ! -f "$TARBALL" ]; then
            echo "ERROR: tarball not found at $TARBALL"
            ls distribution/server/target/ || true
            exit 1
          fi
          echo "path=$TARBALL" >> "$GITHUB_OUTPUT"

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ secrets.AWS_DEFAULT_REGION }}

      - name: Log in to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Extract Docker metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ steps.login-ecr.outputs.registry }}/${{ env.ECR_REPOSITORY }}
          tags: |
            type=semver,pattern={{version}}
            type=semver,pattern={{major}}.{{minor}}
            type=raw,value=latest

      - name: Build and push Docker image
        uses: docker/build-push-action@v6
        with:
          context: .
          file: distribution/docker/Dockerfile
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          build-args: |
            TARBALL_PATH=${{ steps.tarball.outputs.path }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

---

## GitHub Secrets Configuration

Secrets are set at: GitHub repository > Settings > Secrets and variables > Actions > Secrets

| Secret Name | Value | Notes |
|-------------|-------|-------|
| `AWS_ACCESS_KEY_ID` | IAM access key ID | From IAM user with `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:CompleteLayerUpload`, `ecr:InitiateLayerUpload`, `ecr:PutImage`, `ecr:UploadLayerPart` permissions |
| `AWS_SECRET_ACCESS_KEY` | IAM secret access key | Paired with `AWS_ACCESS_KEY_ID` |
| `AWS_DEFAULT_REGION` | e.g. `us-east-1` | The region where the ECR registry lives |

The ECR repository URL (`ECR_REGISTRY`) is output by `amazon-ecr-login@v2` — it is not a secret
and does not need to be stored separately. The `ECR_REPOSITORY` name (e.g. `dremio-oss`) is set
as a plain `env:` variable in the workflow file.

### Minimum IAM Policy for the ECR Push User

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:CompleteLayerUpload",
        "ecr:InitiateLayerUpload",
        "ecr:PutImage",
        "ecr:UploadLayerPart"
      ],
      "Resource": "arn:aws:ecr:{region}:{account-id}:repository/{repo-name}"
    }
  ]
}
```

`ecr:GetAuthorizationToken` is IAM-wide (resource `*`) because it is a control-plane operation.
All image-layer operations are scoped to the specific ECR repository ARN.

---

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| AWS ECR | `aws-actions/configure-aws-credentials@v4` + `amazon-ecr-login@v2` | Registry URL is output by the login action — do not hardcode |
| GitHub Actions cache | `actions/cache@v4` for `.m2/repository`; `cache-from/cache-to: type=gha` for Docker layer cache | Both significantly reduce re-build time |
| GitHub Secrets store | `secrets.AWS_ACCESS_KEY_ID`, `secrets.AWS_SECRET_ACCESS_KEY`, `secrets.AWS_DEFAULT_REGION` | Encrypted at rest, masked in logs, not available to fork PR runs |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| Maven build → Docker build | Filesystem: `distribution/server/target/*.tar.gz` | Same runner job; tarball path passed as Docker build-arg |
| Docker build → ECR | Docker push to authenticated registry | Registry URL from `amazon-ecr-login` step output `${{ steps.login-ecr.outputs.registry }}` |
| Git tag → Maven version | Shell string strip: `${GITHUB_REF_NAME#v}` | Ensures Maven `dremio-community-${revision}.tar.gz` name aligns with git tag |
| Git tag → Docker tag | `docker/metadata-action@v5` semver patterns | Generates `:1.2.3`, `:1.2`, `:latest` automatically |

---

## Java Version Constraint — Critical Build Requirement

The Dremio Maven build enforces Java 21 at build time (maven-enforcer rule `[21,22)` in root
`pom.xml`, line ~3396). The workflow `setup-java` step MUST use `java-version: '21'`.

The Maven compiler release target is `11` (`maven.compiler.release=11` in root `pom.xml`). This
means:
- **CI runner needs Java 21** to compile (maven-enforcer blocks builds on older JDKs)
- **Docker runtime base image uses Java 11** (`eclipse-temurin:11-jdk` in existing Dockerfile)
- The tarball contains Java 11 bytecode — it runs on Java 11+ at runtime

Do not "fix" the Dockerfile to use `eclipse-temurin:21-jdk`. The existing base image is correct
for the runtime. The CI runner is separate from the Docker runtime.

---

## Build Order for Implementation Phases

### Phase 1 — Workflow Skeleton (fastest feedback, no ECR needed yet)

1. Create `.github/workflows/docker-ecr.yml` with the trigger, checkout, Java setup, and Maven
   build steps only (no Docker, no ECR steps). Push a test tag.
2. Verify: Maven build completes on the runner, tarball appears in the expected path.
3. Confirms: Java 21 enforcement passes, Maven build works in CI, tarball naming is correct.

**Why first:** Validates the Maven build in CI before adding Docker complexity. The Maven step
is the longest (30-45 minutes on a cold cache) and most likely to fail for reasons unrelated to
Docker or ECR.

### Phase 2 — Dockerfile Adaptation

1. Modify `distribution/docker/Dockerfile`: replace `ARG DOWNLOAD_URL` + `wget` with
   `ARG TARBALL_PATH` + `COPY`.
2. Add `.dockerignore` at repository root.
3. Test locally: `mvn package -pl distribution/server -am -DskipTests && docker build
   --build-arg TARBALL_PATH=distribution/server/target/dremio-community-*.tar.gz
   -f distribution/docker/Dockerfile .`
4. Confirm the image starts: `docker run --rm -p 9047:9047 <image_id>` and Dremio
   UI appears at `http://localhost:9047`.

**Why second:** Validates the Dockerfile change in isolation before adding the CI push step.
A broken Dockerfile is easier to debug locally than in CI.

### Phase 3 — ECR Authentication + Push in Workflow

1. Create the IAM user and ECR repository in AWS.
2. Add secrets to GitHub (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`).
3. Add the `configure-aws-credentials`, `amazon-ecr-login`, `setup-buildx`, `metadata-action`,
   and `build-push-action` steps to the workflow.
4. Push a test tag and verify the image appears in ECR.

**Why last:** Requires AWS infrastructure and secrets to exist. Decoupling Phase 3 from Phase 1
means the Maven/Docker changes can be developed and tested without AWS access.

---

## Scaling Considerations

This is a CI/CD pipeline, not a runtime system. Scaling concerns are about pipeline performance,
not user load.

| Scale | Architecture Adjustment |
|-------|-------------------------|
| Single developer, rare releases | Current design is sufficient. Cold Maven build is ~40min. |
| Weekly releases | Add Maven `.m2` cache (`actions/cache@v4`). Reduces rebuild time to ~10min after cache warms. |
| Multiple tags per week | Add Docker layer cache (`cache-from: type=gha`). Reduces Docker build time on layer cache hits. |
| Multi-arch images (AMD64 + ARM64) | Add `platforms: linux/amd64,linux/arm64` to `build-push-action`. Build time doubles; consider self-hosted ARM runner or QEMU emulation tradeoffs. |
| Separate test and release pipelines | Split into two workflows: `ci.yml` (on every push, runs tests) + `release.yml` (on tag push, skips tests, builds Docker). Out of scope for this milestone. |

---

## Anti-Patterns

### Anti-Pattern 1: Hardcoding the ECR Registry URL

**What people do:** Set `ECR_REGISTRY: 123456789.dkr.ecr.us-east-1.amazonaws.com` as a secret
or env var and use it directly in the Docker tag.

**Why it's wrong:** The `amazon-ecr-login@v2` action outputs the registry URL. Using the output
avoids duplication and ensures the URL is always consistent with the authenticated registry.

**Do this instead:** Use `${{ steps.login-ecr.outputs.registry }}` in the `tags:` field of
`build-push-action`.

### Anti-Pattern 2: Running Maven Without `-DskipTests` in the Docker Pipeline

**What people do:** Run the full Maven build (with tests) before the Docker push, making the
pipeline 60-90 minutes long.

**Why it's wrong:** Tests should run in a separate CI job triggered on every push/PR. The
release pipeline triggered by a tag should trust that the tag was applied to a commit that
already passed CI. Re-running all tests on every tag push doubles the CI cost and pipeline time.

**Do this instead:** `-DskipTests` in the Docker pipeline. Have a separate workflow for PRs/pushes
that runs `mvn verify` without the Docker steps.

### Anti-Pattern 3: Using `docker build --build-arg DOWNLOAD_URL=...` in CI to Download from S3

**What people do:** Upload the Maven tarball to S3 after the Maven build, then pass the S3 URL
as `DOWNLOAD_URL` to the original Dockerfile's `wget` step.

**Why it's wrong:** This requires S3 upload permissions, a network round-trip inside the Docker
build, and keeps the problematic wget-in-Dockerfile pattern. It also makes local Docker builds
require an external URL.

**Do this instead:** Adapt the Dockerfile to use `COPY` (as described above). The tarball is on
the runner filesystem. No S3 involvement needed.

### Anti-Pattern 4: Setting the Build Context to `distribution/docker/`

**What people do:** Set `context: distribution/docker` in `build-push-action` to minimize build
context size.

**Why it's wrong:** The tarball is at `distribution/server/target/`, which is outside the
`distribution/docker/` context. Docker cannot `COPY` files from outside the build context.

**Do this instead:** Set `context: .` (repository root) and use `.dockerignore` to exclude
node_modules, `.git`, etc. The tarball at `distribution/server/target/` is then accessible.

### Anti-Pattern 5: Deriving Maven Version with `mvn help:evaluate` Instead of `GITHUB_REF_NAME`

**What people do:** Run `mvn help:evaluate -Dexpression=project.version -q -DforceStdout` to get
the version from the POM, then use that as the Docker tag.

**Why it's wrong:** For this project, the version IS the revision passed as `-Drevision` to Maven,
which is read from `.mvn/maven.config` during local builds. In CI you should override `-Drevision`
to match the git tag. If you evaluate `project.version` before overriding `-Drevision`, you get
the version from `.mvn/maven.config` (the developer's local version), not the release version.

**Do this instead:** Derive the version from `GITHUB_REF_NAME` (strip the `v` prefix), use it
as both `-Drevision` for Maven AND as the Docker tag. They will always agree.

---

## Sources

All pipeline patterns are HIGH confidence based on:
- GitHub Actions official documentation patterns for Docker publishing (well-established, stable
  since 2021)
- `aws-actions/configure-aws-credentials` and `aws-actions/amazon-ecr-login` are official AWS
  actions with stable v4/v2 API
- `docker/build-push-action`, `docker/metadata-action`, `docker/setup-buildx-action` are official
  Docker actions with stable v5/v6 API
- Dremio build structure verified directly from:
  - `/home/emanuele/IdeaProjects/dremio-oss/distribution/docker/Dockerfile` (existing Dockerfile)
  - `/home/emanuele/IdeaProjects/dremio-oss/distribution/server/pom.xml` (finalName, distribution name)
  - `/home/emanuele/IdeaProjects/dremio-oss/.mvn/maven.config` (revision property)
  - `/home/emanuele/IdeaProjects/dremio-oss/pom.xml` (maven.compiler.release=11, enforcer [21,22))
  - `distribution/server/target/dremio-community-26.0.5-202509091642240013-f5051a07.tar.gz` (actual artifact, naming confirmed)

---

*Architecture research for: Dremio OSS GitHub Actions CI/CD pipeline (Docker + ECR)*
*Researched: 2026-02-20*
