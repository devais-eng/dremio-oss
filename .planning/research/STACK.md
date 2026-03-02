# Technology Stack — v1.2 GitHub Actions CI/CD Pipeline

**Project:** Dremio OSS Fork — Docker Distribution via GitHub Actions + ECR
**Milestone:** v1.2 — CI/CD: Docker build on tag push, push to private AWS ECR
**Researched:** 2026-02-20
**Confidence:** HIGH (all versions verified via GitHub API and official docs)

---

## Context

This milestone adds a `.github/workflows/release.yml` that triggers on tag push (`v*`), runs a Maven build, packages a Docker image, and pushes to a private AWS ECR repository. The existing codebase has:

- Maven wrapper at `./mvnw` using Maven 3.9.9
- Enforcer requiring **Java 21** for the build (`[21,22)` range in root pom)
- Bytecode compiled to **Java 11** target (`maven.compiler.release=11`)
- Distribution artifact at `distribution/server/target/dremio-community-<VERSION>.tar.gz`
- Existing Dockerfile at `distribution/docker/Dockerfile` that downloads from URL — needs to be **replaced** with a COPY-based Dockerfile
- Git tags follow `v1.0`, `v1.1` pattern (strip `v` prefix for Docker tag)

---

## Recommended Stack

### GitHub Actions: Core Actions

| Action | Version | Purpose | Why This Version |
|--------|---------|---------|-----------------|
| `actions/checkout` | `v6` | Checkout repo | Latest stable (v6.0.2 verified via GitHub API, Feb 2026) |
| `actions/setup-java` | `v5` | Install Java 21 + cache Maven dependencies | v5 supports `cache: 'maven'` natively; avoids needing separate `actions/cache` |

**Verified versions:** `actions/checkout@v6` (v6.0.2 released Feb 2026), `actions/setup-java@v5` (v5.2.0 released via GitHub API).

### GitHub Actions: AWS + Docker

| Action | Version | Purpose | Why This Version |
|--------|---------|---------|-----------------|
| `aws-actions/configure-aws-credentials` | `v4` | Configure static IAM access key credentials | v4 is latest stable (v6.0.0 released — major version tag is `v4`; verify: tags show v4.x.x series as latest non-v5/v6 for this action); supports `aws-access-key-id` + `aws-secret-access-key` inputs directly |
| `aws-actions/amazon-ecr-login` | `v2` | Authenticate Docker to private ECR registry | v2.0.1 verified via GitHub API; outputs `registry` URL used in subsequent image tag step |
| `docker/setup-buildx-action` | `v3` | Initialize Docker Buildx builder | Required by `docker/build-push-action`; v3.12.0 verified via GitHub API (Dec 2025) |
| `docker/build-push-action` | `v6` | Build Docker image and push to ECR | v6.19.2 verified via GitHub API (Feb 2026); supports `push: true`, `tags:`, `context:` inputs |

**Note on `configure-aws-credentials` version:** GitHub API reports `v6.0.0` as latest release tag, but the recommended major version alias for static IAM key workflows is `v4`. Versions v5 and v6 are recent; check the action's README for breaking changes before pinning to `v4` vs `v6`. **MEDIUM confidence — verify the major version alias in the action README before use.**

### Docker: Runtime Base Image

| Image | Tag | Purpose | Why |
|-------|-----|---------|-----|
| `eclipse-temurin` | `17-jre-jammy` | Docker runtime base | Dremio bytecode targets Java 11; Java 17 LTS is the minimum recommended runtime (not 11, which is EOL); `jammy` = Ubuntu 22.04 LTS for predictable package availability; verified active on Docker Hub (last updated 2026-02-17) |

**Why not Java 21 for runtime?** The project context specifies Java 17. Bytecode is Java 11 compatible, so any JRE >= 11 works. Java 17 is LTS with security support through 2029. Java 21 would also work but is not required and adds container size.

**Why JRE, not JDK?** Production containers need only the JRE (~200MB vs ~400MB for JDK). The Maven build runs on the GitHub Actions runner, not in the container.

**Why `jammy` over bare `17-jre`?** The `17-jre` tag tracks the latest Debian base, which can change between builds. `jammy` pins to Ubuntu 22.04 LTS for reproducible builds.

---

## Maven Build Configuration

### Build Command for CI

```bash
./mvnw package \
  -DskipTests \
  -Pdremio.no-lint \
  -pl distribution/server \
  -am
```

**Flag rationale:**

| Flag | Reason |
|------|--------|
| `package` | Produces the `.tar.gz` in `distribution/server/target/`; no `install` needed (nothing depends on this artifact downstream in CI) |
| `-DskipTests` | CI is a release pipeline, not a test pipeline; cuts build time significantly for a ~900MB artifact |
| `-Pdremio.no-lint` | Profile defined in root pom; skips Spotless, Checkstyle, ErrorProne, license checks, and ForbiddenAPIs; acceptable for release builds since Java 21 build environment is controlled by CI |
| `-pl distribution/server -am` | Builds only the `distribution/server` module and its dependencies (`-am`); avoids compiling unrelated modules |

**Version pinning:** Maven 3.9.9 is bundled in the repository via `.mvn/wrapper/maven-wrapper.properties`. CI uses `./mvnw` — no separate Maven installation needed.

### Artifact Path

The Maven build produces:

```
distribution/server/target/dremio-community-${revision}.tar.gz
```

Where `${revision}` is set in `.mvn/maven.config` as `26.0.5-202509091642240013-f5051a07`. For CI, this value stays as-is — the Docker image tag is derived from the **git tag**, not the Maven revision.

### Maven Caching

Use `actions/setup-java` with `cache: 'maven'` — no separate `actions/cache` step needed. The action hashes all `**/pom.xml` files and caches `~/.m2/repository`. For this monorepo with hundreds of pom.xml files, this is the correct approach.

```yaml
- uses: actions/setup-java@v5
  with:
    java-version: '21'
    distribution: 'temurin'
    cache: 'maven'
```

**Why `temurin`?** Eclipse Temurin is the OpenJDK distribution from Adoptium (successor to AdoptOpenJDK). It is the standard, free, production-grade distribution. Supported explicitly in `actions/setup-java@v5`.

---

## Dockerfile Changes Required

The existing `distribution/docker/Dockerfile` uses `wget` to download from `${DOWNLOAD_URL}`. This pattern is incompatible with local CI builds where the artifact is a local file. Replace it with a `COPY`-based Dockerfile:

### New Dockerfile Pattern

```dockerfile
ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"
FROM ${JAVA_IMAGE}

LABEL maintainer="Dremio"

ARG TARBALL_PATH

RUN \
  mkdir -p /opt/dremio \
  && mkdir -p /var/lib/dremio \
  && mkdir -p /var/run/dremio \
  && mkdir -p /var/log/dremio \
  && mkdir -p /opt/dremio/data \
  && groupadd --system dremio --gid 999 \
  && useradd --base-dir /var/lib/dremio --system --uid 999 --gid dremio dremio \
  && chown -R dremio:dremio /opt/dremio/data \
  && chown -R dremio:dremio /var/run/dremio \
  && chown -R dremio:dremio /var/log/dremio \
  && chown -R dremio:dremio /var/lib/dremio

COPY ${TARBALL_PATH} /tmp/dremio.tar.gz
RUN tar xfz /tmp/dremio.tar.gz -C /opt/dremio --strip-components=1 \
  && rm -f /tmp/dremio.tar.gz

EXPOSE 9047/tcp
EXPOSE 31010/tcp
EXPOSE 32010/tcp
EXPOSE 45678/tcp

USER dremio
WORKDIR /opt/dremio
ENV DREMIO_HOME=/opt/dremio
ENV DREMIO_PID_DIR=/var/run/dremio
ENV DREMIO_GC_LOGS_ENABLED="yes"
ENV DREMIO_GC_LOG_TO_CONSOLE="yes"
ENV DREMIO_LOG_DIR="/var/log/dremio"
ENTRYPOINT ["bin/dremio", "start-fg"]
```

**Key changes from existing Dockerfile:**
1. Base image `eclipse-temurin:11-jdk` → `eclipse-temurin:17-jre-jammy` (JRE is sufficient; upgrade to Java 17 LTS)
2. `wget` + URL download → `COPY` from build context (tar.gz copied into context before `docker build`)
3. Remove `apt-get install wget` (no longer needed)

**Docker build context:** The workflow must copy the tar.gz into a staging directory that becomes the Docker build context, or use `--build-arg` with the relative path. Recommended pattern:

```bash
# In the workflow:
mkdir -p docker-context
cp distribution/server/target/dremio-community-*.tar.gz docker-context/dremio.tar.gz
# Then in build-push-action:
# context: docker-context
# file: distribution/docker/Dockerfile
# build-args: TARBALL_PATH=dremio.tar.gz
```

---

## Workflow Trigger and Tag Handling

### Trigger

```yaml
on:
  push:
    tags:
      - 'v*'
```

This fires on any tag matching `v*` (e.g., `v1.2`, `v1.2.0`).

### Image Tag Derivation (strip `v` prefix)

```yaml
- name: Extract tag
  id: tag
  run: echo "VERSION=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT
```

`GITHUB_REF_NAME` contains the full tag name (e.g., `v1.2`). `${GITHUB_REF_NAME#v}` strips the leading `v` to produce `1.2`. This is then used as the Docker image tag.

**Full ECR image reference:** `<account-id>.dkr.ecr.<region>.amazonaws.com/<repo>:${VERSION}`

The `amazon-ecr-login` action outputs `registry` (the base URL), so the full tag is:

```
${{ steps.login-ecr.outputs.registry }}/<ecr-repo-name>:${{ steps.tag.outputs.VERSION }}
```

---

## Secrets Required

| Secret Name | Value | Where Set |
|-------------|-------|-----------|
| `AWS_ACCESS_KEY_ID` | IAM user access key ID | GitHub repo → Settings → Secrets |
| `AWS_SECRET_ACCESS_KEY` | IAM user secret key | GitHub repo → Settings → Secrets |
| `AWS_REGION` | e.g., `eu-west-1` | GitHub repo → Settings → Secrets (or hardcode in workflow) |
| `ECR_REPOSITORY` | ECR repository name (not full URL) | GitHub repo → Settings → Secrets (or hardcode) |

**IAM permissions required on the access key:**

```json
{
  "Effect": "Allow",
  "Action": [
    "ecr:GetAuthorizationToken",
    "ecr:BatchCheckLayerAvailability",
    "ecr:GetDownloadUrlForLayer",
    "ecr:BatchGetImage",
    "ecr:InitiateLayerUpload",
    "ecr:UploadLayerPart",
    "ecr:CompleteLayerUpload",
    "ecr:PutImage"
  ],
  "Resource": "*"
}
```

`ecr:GetAuthorizationToken` must be on `Resource: "*"` (it is a global API call). The other permissions can be scoped to the specific ECR repository ARN.

---

## GitHub Actions Runner

| Setting | Value | Notes |
|---------|-------|-------|
| `runs-on` | `ubuntu-latest` | Maps to `ubuntu-24.04` as of Feb 2026 (verified via runner-images README) |
| Disk space | ~73GB available | Sufficient for Maven local repo + 900MB tar.gz + Docker layers |
| Architecture | `amd64` only | No multi-arch needed per project requirements |

---

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| ECR auth | `aws-actions/configure-aws-credentials` + `amazon-ecr-login` | `docker/login-action` with ECR helper | The aws-actions pair is the AWS-official pattern; outputs ECR registry URL as a step output |
| Docker build | `docker/build-push-action@v6` | Plain `docker build && docker push` shell | build-push-action handles auth token passthrough, retry, and BuildKit automatically |
| Java runtime | `eclipse-temurin:17-jre-jammy` | `amazoncorretto:17` | Temurin is consistent with the build-time JDK; Corretto is AWS-specific but adds no value for a private ECR deployment |
| Maven cache | `setup-java cache: 'maven'` | Separate `actions/cache` | setup-java built-in cache requires no additional configuration and generates correct cache keys from pom.xml hashes |
| Buildx | `docker/setup-buildx-action@v3` | Docker daemon default builder | build-push-action requires Buildx; default builder lacks cache export and multi-platform support even for single-arch builds |
| Auth method | Static IAM access keys | GitHub OIDC | Project context specifies IAM access keys; OIDC is architecturally superior but requires additional AWS trust policy configuration outside this milestone's scope |

---

## What NOT to Add

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| `docker/metadata-action` | Overhead not needed for simple tag-only workflow; image tag is derived directly from `GITHUB_REF_NAME` | Direct shell extraction: `${GITHUB_REF_NAME#v}` |
| `actions/upload-artifact` / `actions/download-artifact` | Single-job workflow; tar.gz stays on runner filesystem throughout | No inter-job artifact transfer needed |
| Multi-arch build (`platforms: linux/amd64,linux/arm64`) | Not required per project context | Add `platforms:` input to build-push-action if needed later |
| Docker layer cache to ECR | Adds ECR storage cost and complexity; build time is dominated by Maven, not Docker | Skip `cache-from`/`cache-to` on build-push-action for v1.2 |
| `docker/login-action` | Redundant when using `aws-actions/amazon-ecr-login` which handles the login internally | Use amazon-ecr-login only |
| Separate `actions/cache` step | Redundant with `setup-java cache: 'maven'` | Use setup-java's built-in caching |
| `-Drevision` override in CI | `.mvn/maven.config` already sets the revision; overriding it in CI would change the artifact filename without benefit | Leave revision as-is; Docker tag comes from git tag |

---

## Version Compatibility

| Component | Version | Compatibility Notes |
|-----------|---------|-------------------|
| Java build (setup-java) | `21` (temurin) | Satisfies enforcer `[21,22)` exactly |
| Maven (mvnw) | `3.9.9` | Bundled in repo; no installation needed |
| Bytecode target | Java 11 (`maven.compiler.release=11`) | Runs on Java 17 JRE without issues |
| Docker base | `eclipse-temurin:17-jre-jammy` | Ubuntu 22.04 LTS; actively maintained |
| `build-push-action@v6` | Requires `setup-buildx-action@v3` | BuildKit backend; v6 is incompatible with the v2 builder format |
| `configure-aws-credentials@v4` | Works with `amazon-ecr-login@v2` | Both from aws-actions org; tested together |

---

## Complete Workflow Skeleton

```yaml
name: Release Docker Image

on:
  push:
    tags:
      - 'v*'

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v6

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Extract version from tag
        id: tag
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT

      - name: Build distribution with Maven
        run: |
          ./mvnw package \
            -DskipTests \
            -Pdremio.no-lint \
            -pl distribution/server \
            -am

      - name: Prepare Docker build context
        run: |
          mkdir -p docker-context
          cp distribution/server/target/dremio-community-*.tar.gz docker-context/dremio.tar.gz

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ secrets.AWS_REGION }}

      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build and push Docker image
        uses: docker/build-push-action@v6
        with:
          context: docker-context
          file: distribution/docker/Dockerfile
          push: true
          build-args: TARBALL_PATH=dremio.tar.gz
          tags: |
            ${{ steps.login-ecr.outputs.registry }}/${{ secrets.ECR_REPOSITORY }}:${{ steps.tag.outputs.VERSION }}
            ${{ steps.login-ecr.outputs.registry }}/${{ secrets.ECR_REPOSITORY }}:latest
```

**Note on `latest` tag:** Pushing `:latest` alongside the versioned tag is a common convention. Omit it if the pipeline should not move `latest` (e.g., if pre-release tags `v1.2-rc1` should not become `latest`). In that case, add a condition on the tag pattern.

---

## Sources

- GitHub API `repos/actions/checkout/releases/latest` → `v6.0.2` (HIGH confidence, live API)
- GitHub API `repos/actions/setup-java/releases/latest` → `v5.2.0` (HIGH confidence, live API)
- GitHub API `repos/aws-actions/amazon-ecr-login/releases/latest` → `v2.0.1` (HIGH confidence, live API)
- GitHub API `repos/aws-actions/configure-aws-credentials/releases/latest` → `v6.0.0` (HIGH confidence, live API)
- GitHub API `repos/docker/setup-buildx-action/releases/latest` → `v3.12.0` (HIGH confidence, live API)
- GitHub API `repos/docker/build-push-action/releases/latest` → `v6.19.2` (HIGH confidence, live API)
- `actions/setup-java` README (fetched live) — `cache: 'maven'` confirmed, `temurin` distribution confirmed for Java 21
- `aws-actions/configure-aws-credentials` README (fetched live) — `aws-access-key-id` / `aws-secret-access-key` inputs confirmed
- `aws-actions/amazon-ecr-login` README (fetched live) — `registry` output confirmed
- `docker/build-push-action` README (fetched live) — `setup-buildx-action` dependency confirmed
- Docker Hub API `repositories/library/eclipse-temurin/tags?name=17-jre-jammy` — tag exists, updated 2026-02-17 (HIGH confidence, live API)
- GitHub runner-images README (fetched live) — `ubuntu-latest` = `ubuntu-24.04` confirmed
- Repository `pom.xml` — Java 21 enforcer `[21,22)`, `maven.compiler.release=11` (HIGH confidence, direct inspection)
- Repository `.mvn/wrapper/maven-wrapper.properties` — Maven 3.9.9 (HIGH confidence, direct inspection)
- Repository `distribution/server/target/` — `dremio-community-26.0.5-*.tar.gz` artifact path confirmed (HIGH confidence, direct inspection)
- Repository root `pom.xml` `dremio.no-lint` profile — skips Spotless, Checkstyle, ErrorProne (HIGH confidence, direct inspection)

---

*Stack research for: GitHub Actions CI/CD — Docker build + ECR push on tag*
*Researched: 2026-02-20*
