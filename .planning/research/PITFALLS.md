# Pitfalls Research

**Domain:** GitHub Actions CI/CD — Docker build + AWS ECR push for Dremio OSS fork (large Maven project)
**Researched:** 2026-02-20
**Confidence:** HIGH — findings derived from codebase analysis (enforcer constraints, Dockerfile, tarball size,
Maven module count) plus established GitHub Actions / Docker / ECR patterns with training-cutoff confidence.

---

## Critical Pitfalls

### Pitfall 1: Maven Enforcer Rejects Java 21.1+ — Build Fails Immediately

**What goes wrong:**
The Maven enforcer in `build-tools/pom.xml` requires Java version `[21,22)` — exactly Java 21.x, exclusive
of Java 22+. When `actions/setup-java` is configured with `java-version: '21'`, it installs the latest
available Java 21 patch release (e.g., 21.0.5 or later), which passes. However, if the version is set to
`'22'`, `'23'`, or `'latest'` (which tracks the latest LTS, currently 21 but will advance), the enforcer
fires immediately at the `validate` phase with a message like:

```
[ERROR] Rule 0: org.apache.maven.plugins.enforcer.RequireJavaVersion failed with message:
Detected JVM Version: 22.x.y - Supported JVM Version range is [21,22)
```

The build terminates before any compilation occurs.

**Why it happens:**
Dremio's root `pom.xml` uses Maven CI-friendly versioning (`${revision}`) and the enforcer check runs in the
`validate` phase — before any code is compiled. The enforcer range `[21,22)` uses Maven's standard version
range notation: inclusive lower bound 21, exclusive upper bound 22. This range was set deliberately to lock
the build to Java 21 APIs and prevent accidental use of preview features from Java 22+.

`actions/setup-java` accepts semver specifiers. `java-version: '21'` resolves to the latest Java 21.x
from the distribution (Eclipse Temurin by default). This is correct and stable.

**How to avoid:**
In the workflow YAML:
```yaml
- uses: actions/setup-java@v4
  with:
    java-version: '21'
    distribution: 'temurin'
    cache: 'maven'
```
Never use `java-version: 'latest'`, `'22'`, `'23'`, or an unversioned string. Pin to `'21'` exactly.
If the project upgrades the enforcer range in the future, the workflow must be updated in the same PR.

**Warning signs:**
- First build fails at `[INFO] BUILD FAILURE` within 60 seconds of starting the Maven step.
- Error message mentions `RequireJavaVersion` or `Detected JVM Version`.
- `java -version` output in the step log shows a version outside `21.x`.

**Phase to address:**
Phase 1 (Maven build setup) — first workflow commit must pin Java 21.

---

### Pitfall 2: GitHub Actions 6-Hour Job Timeout Exceeded for Full Maven Build Without Cache

**What goes wrong:**
A full cold build of Dremio OSS from source without a populated Maven cache takes 45–90 minutes on
a standard `ubuntu-latest` GitHub-hosted runner (4 cores, 16GB RAM as of 2024, varying by runner
version). With 158 Maven modules, dependency resolution alone can take 10–15 minutes if the
`~/.m2/repository` is empty. Without the `actions/cache` step, every job run is a cold build.

GitHub Actions has a 6-hour job timeout (`timeout-minutes` defaults to 360). The build will not hit
this limit, but it makes the CI pipeline slow (30–60 min per tag push). More immediately dangerous:
if the Maven cache `actions/cache` key is misconfigured (e.g., keyed only on `pom.xml` at the
root rather than the full `**/pom.xml` tree), a dependency change in a sub-module invalidates
nothing and the cache becomes stale, causing dependency resolution failures on the runner that do
not reproduce locally.

**Why it happens:**
`actions/cache` uses a hash key. If the key is `hashFiles('pom.xml')` (root only), changes to any of
the 157 sub-module `pom.xml` files are invisible to the cache key. The cached `~/.m2/repository` will
contain old artifact versions, and Maven will attempt to resolve the new versions from the remote
repository — which may fail if the Dremio-internal custom artifacts (e.g., `hadoop-3.3.6-dremio-*`,
`calcite-1.22.0-dremio-*`) are not available from the public Maven Central repo and the runner has
no access to Dremio's internal artifact server.

Additionally, Maven downloads artifacts into `~/.m2/repository` during the build run even with cache
configured. If the workflow does not restore the cache BEFORE the Maven step, the cache is useless.

**How to avoid:**
Cache key must hash ALL `pom.xml` files:
```yaml
- uses: actions/cache@v4
  with:
    path: ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      ${{ runner.os }}-maven-
```

Place the `actions/cache` step BEFORE the Maven build step. Also consider `actions/setup-java@v4`'s
built-in `cache: 'maven'` parameter, which handles the `pom.xml` hash automatically and is simpler
than a manual `actions/cache` step. Do not use both — choose one approach.

For Dremio specifically, the Maven build should use `-DskipTests` and consider
`-pl distribution/server -am` to build only the server distribution module and its transitive
dependencies (not UI, not JDBC driver). Verify that the custom `plugins/icebergcatalog` and RBAC
modules are in the transitive dependency graph of `distribution/server` before using `-pl`.

**Warning signs:**
- Build time does not improve after the first run (cache miss every time).
- Maven step log shows `Downloading from central:` for Dremio-internal artifact coordinates
  (e.g., `com.dremio:*`), which are not on Maven Central.
- Cache restore log shows "Cache not found" on subsequent runs.

**Phase to address:**
Phase 1 (Maven build setup) — cache must be correct before any other steps are reliable.

---

### Pitfall 3: Dockerfile `wget DOWNLOAD_URL` Cannot Reach a Local Build Artifact

**What goes wrong:**
The existing `distribution/docker/Dockerfile` downloads Dremio from a remote URL:
```dockerfile
ARG DOWNLOAD_URL
RUN wget -q "${DOWNLOAD_URL}" -O dremio.tar.gz && tar vxfz dremio.tar.gz ...
```
In the CI pipeline, the tarball is produced locally at
`distribution/server/target/dremio-community-*.tar.gz` (864MB). There is no URL to `wget` from — the
artifact is on the GitHub Actions runner's disk, not a web server. If the workflow passes
`--build-arg DOWNLOAD_URL=` with an empty value or a `file://` path, `wget` will fail, and the Docker
build will exit with a non-zero code. The image is never produced.

A naive workaround — uploading the tarball to a public URL or a pre-signed S3 URL — works but
introduces an unnecessary step, potential credential exposure in the `--build-arg`, and race conditions
if the URL expires before the Docker layer cache can reuse it.

**Why it happens:**
The Dockerfile was designed for the Dremio release workflow where tarballs are hosted at
`download.dremio.com`. For a fork building from source, the tarball never goes to a remote URL; it
is created by `mvn package` and sits on disk.

**How to avoid:**
Add a separate `Dockerfile.ci` (or modify the existing Dockerfile with a build-arg mode switch)
that uses `COPY` instead of `wget`:

```dockerfile
ARG JAVA_IMAGE="eclipse-temurin:17-jre"
FROM ${JAVA_IMAGE} AS base
LABEL maintainer="DevAIS Engineering"
COPY distribution/server/target/dremio-community-*.tar.gz /tmp/dremio.tar.gz
RUN mkdir -p /opt/dremio /var/lib/dremio /var/run/dremio /var/log/dremio /opt/dremio/data \
    && groupadd --system dremio --gid 999 \
    && useradd --base-dir /var/lib/dremio --system --uid 999 --gid dremio dremio \
    && tar vxfz /tmp/dremio.tar.gz -C /opt/dremio --strip-components=1 \
    && rm /tmp/dremio.tar.gz \
    && chown -R dremio:dremio /opt/dremio/data /var/run/dremio /var/log/dremio /var/lib/dremio
EXPOSE 9047/tcp 31010/tcp 32010/tcp 45678/tcp
USER dremio
WORKDIR /opt/dremio
ENV DREMIO_HOME=/opt/dremio DREMIO_PID_DIR=/var/run/dremio \
    DREMIO_GC_LOGS_ENABLED=yes DREMIO_GC_LOG_TO_CONSOLE=yes DREMIO_LOG_DIR=/var/log/dremio
ENTRYPOINT ["bin/dremio", "start-fg"]
```

The Docker build context MUST be the repository root (not `distribution/docker/`) so that the
`COPY distribution/server/target/...` path resolves. In the workflow:
```yaml
- uses: docker/build-push-action@v6
  with:
    context: .         # repository root, not ./distribution/docker/
    file: distribution/docker/Dockerfile.ci
```

**Warning signs:**
- Docker build fails with `wget: bad address` or `wget: invalid option` or HTTP 400/404.
- Build arg `DOWNLOAD_URL` is empty or a `file://` path in the `docker build` command.
- Docker build step log shows `wget` attempting a URL rather than a `COPY` instruction.

**Phase to address:**
Phase 2 (Dockerfile adaptation) — first step of Docker build phase before any image push attempt.

---

### Pitfall 4: Java Version Mismatch — Build JDK 21 vs Runtime JRE 11/17

**What goes wrong:**
The existing Dockerfile uses `eclipse-temurin:11-jdk` as the base image. The project context requires
Java 17 at runtime. The Maven build compiles with Java 21 (`maven.compiler.release=11` in root pom,
but the enforcer requires JVM 21 to RUN Maven). This creates a three-way version situation:

- Maven build JVM: Java 21 (enforcer mandated)
- `maven.compiler.release`: 11 (bytecode compiled to Java 11 class format)
- Dockerfile base image (existing): `eclipse-temurin:11-jdk`
- Intended runtime: Java 17

If the Dockerfile is left unchanged with `eclipse-temurin:11-jdk`, the image runs Java 11. Dremio
may start, but JVM tuning flags written for Java 17+ (e.g., `-XX:+UseZGC`, newer G1 options) in
`dremio-env` config will emit warnings or errors. More critically, if any Dremio startup code uses
APIs available in Java 17+ but not Java 11, the process will crash with `NoSuchMethodError` or
`UnsupportedClassVersionError`.

Separately: using `eclipse-temurin:17-jdk` (full JDK) instead of `eclipse-temurin:17-jre` (runtime
only) adds approximately 200–300MB to the image unnecessarily. Production images should use JRE.

**Why it happens:**
The original Dockerfile was written for Dremio 5.x/6.x which supported Java 11. The project context
specifies Java 17 runtime, but the Dockerfile was not updated. The Maven compiler release of `11`
means the `.class` files target Java 11 compatibility — they will run on any JVM 11+, including 17 or 21.
The issue is runtime API availability, not bytecode compatibility.

**How to avoid:**
Set the base image to `eclipse-temurin:17-jre` in `Dockerfile.ci`. Do not use `17-jdk`.
Do not use `21-jre` unless the project explicitly requires Java 21 runtime behavior.
The `ARG JAVA_IMAGE` mechanism already exists in the Dockerfile — use it:
```dockerfile
ARG JAVA_IMAGE="eclipse-temurin:17-jre"
FROM ${JAVA_IMAGE} AS base
```
Or pass it as a build arg:
```yaml
build-args: |
  JAVA_IMAGE=eclipse-temurin:17-jre
```

**Warning signs:**
- `docker inspect <image>` shows `eclipse-temurin:11` base layer.
- Container startup logs show Java 11 in JVM info: `java version "11.x.x"`.
- `WARN: Unrecognized VM option` for JVM flags in `dremio-env` that require Java 17+.

**Phase to address:**
Phase 2 (Dockerfile adaptation) — correct the base image at the same time as the `COPY` adaptation.

---

### Pitfall 5: ECR Authentication Token Expires Mid-Build (12-Hour Token Lifetime)

**What goes wrong:**
`aws-actions/amazon-ecr-login` obtains a temporary Docker login token from ECR. This token is valid
for 12 hours. In a standard pipeline where Maven build takes 30–60 minutes and Docker build + push
takes 5–15 minutes, the total job time is well under 12 hours, and token expiry is not a practical
risk.

However, the risk materializes in two scenarios: (1) if the job is queued in GitHub Actions for a long
time before it starts (queuing counts against the total 6-hour limit, but not the ECR token lifetime
which starts when `ecr-login` runs); (2) if Docker layer cache is cold and the 864MB tarball layer
takes unusually long to push. Neither scenario is common for private repos, but the failure mode
(silent auth error mid-push, non-zero exit, workflow fails but no image is pushed) is confusing because
the `docker push` command may print authentication errors that look like network issues.

The more immediate authentication pitfall: using the wrong action or action version. The v1 action
`amazon-ecr-login@v1` outputs `registry` as a step output; the v2 action outputs it differently. If
the workflow uses `@v1` syntax for credential injection but `@v2` output syntax for registry URI
extraction, the `ECR_REGISTRY` variable will be empty and the `docker push` will fail with
`denied: requested access to the resource is denied`.

**Why it happens:**
AWS ECR tokens are short-lived by design. The action must run in the same job step sequence as the
Docker push, not in a separate job. If `ecr-login` is in Job A and `docker push` is in Job B, the
token is not automatically shared between jobs (only artifacts and caches are shared).

**How to avoid:**
- Use `aws-actions/amazon-ecr-login@v2` (current major version as of 2025).
- Place `configure-aws-credentials` and `ecr-login` in the same job as `build-push-action`.
- Extract the ECR registry URI from the `ecr-login` step output: `${{ steps.login-ecr.outputs.registry }}`.
- Full sequence:
  ```yaml
  - name: Configure AWS credentials
    uses: aws-actions/configure-aws-credentials@v4
    with:
      aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
      aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
      aws-region: ${{ secrets.AWS_REGION }}

  - name: Login to Amazon ECR
    id: login-ecr
    uses: aws-actions/amazon-ecr-login@v2

  - name: Build and push
    uses: docker/build-push-action@v6
    with:
      tags: ${{ steps.login-ecr.outputs.registry }}/dremio-oss:${{ env.IMAGE_VERSION }}
  ```

**Warning signs:**
- `docker push` fails with `denied: requested access to the resource is denied` or `no basic auth credentials`.
- ECR registry URI is an empty string in the `tags:` field (produces `:tagname` without a registry prefix).
- `ecr-login` step succeeds but Docker push fails: indicates version mismatch in output variable naming.

**Phase to address:**
Phase 3 (ECR authentication and push) — verify authentication chain before attempting a push.

---

### Pitfall 6: IAM Key Secrets Missing or Misconfigured — Silent Permission Errors

**What goes wrong:**
The GitHub Actions workflow requires four secrets: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_REGION`, and `ECR_REPOSITORY`. If any of these secrets are missing from the repository's
GitHub Secrets settings, the workflow receives an empty string for the secret value. GitHub Actions
does not fail immediately on a missing secret reference — `${{ secrets.MISSING_SECRET }}` evaluates
to an empty string, not an error.

The consequences depend on which secret is missing:
- `AWS_ACCESS_KEY_ID` or `AWS_SECRET_ACCESS_KEY` missing: `configure-aws-credentials` fails with
  `Error: Must provide at least one authorized credential set`.
- `AWS_REGION` missing: the action defaults to no region, causing ECR login to fail with
  `Error: Could not resolve endpoint`.
- `ECR_REPOSITORY` missing: the image tag becomes `{registry}/:version` (empty repository name),
  and `docker push` fails with `invalid reference format`.

None of these errors reveal that a secret is missing — they look like AWS API errors or Docker
configuration errors, leading to incorrect debugging paths.

Additionally: IAM policies attached to the access key must include `ecr:GetAuthorizationToken`
(for `ecr-login`) plus `ecr:BatchCheckLayerAvailability`, `ecr:InitiateLayerUpload`,
`ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:PutImage` (for `docker push`). The managed
policy `AmazonEC2ContainerRegistryPowerUser` covers all of these. A custom IAM policy that only
includes `ecr:PutImage` will fail at `InitiateLayerUpload` with a cryptic permissions error.

**Why it happens:**
GitHub Actions does not validate that referenced secrets exist when the workflow is parsed. The
`${{ secrets.NAME }}` interpolation is silent on missing values. The workflow author adds the `uses:`
lines correctly but forgets to create the corresponding secrets in Settings > Secrets and Variables.

**How to avoid:**
- Create all four secrets in the repository before pushing the workflow file.
- Name secrets consistently: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`,
  `ECR_REPOSITORY`. Document this in a comment in the workflow file.
- Test IAM permissions with `aws ecr describe-repositories` locally using the same access key before
  adding it to GitHub Secrets.
- Use `AmazonEC2ContainerRegistryPowerUser` managed policy for the CI IAM user — do not write a
  custom policy unless required by security policy.

**Warning signs:**
- `configure-aws-credentials` step fails with credential or region errors.
- Docker push fails with `repository does not exist` or `invalid reference format`.
- `aws sts get-caller-identity` in a debugging step returns error or empty output.
- Step log shows the secrets are present but the values look truncated (GitHub masks secrets, but
  a missing secret shows as an empty string, not a masked value).

**Phase to address:**
Phase 3 (ECR authentication and push) — set up secrets before the first workflow run.

---

### Pitfall 7: Docker Image Size Bloat — 864MB Tarball Produces Multi-GB Image

**What goes wrong:**
The Dremio distribution tarball is 864MB (measured from `distribution/server/target/`). When added
to a Docker image as a `COPY` + `RUN tar` pair in two separate `RUN` instructions, the image layer
history records both the compressed tarball copy AND the extracted contents as separate layers. Total
image size can reach 2–3GB if the Dockerfile is naively structured.

A second source of bloat: using `eclipse-temurin:17-jdk` (includes full JDK, ~600MB) instead of
`eclipse-temurin:17-jre` (~300MB) adds ~300MB unnecessarily.

A third source: running `apt-get update` without `rm -rf /var/lib/apt/lists/*` in the same `RUN`
instruction leaves the package lists in the image layer (100–200MB).

GitHub-hosted runners have disk space limits (~14GB available on `ubuntu-latest`). A 2.5GB image
pushed to ECR costs non-trivial storage and pull time in downstream deployments.

**Why it happens:**
Docker layer caching works at the `RUN` instruction level. Each `RUN` instruction creates a new
immutable layer. The tarball `COPY` step creates a layer with the compressed file; the subsequent
`RUN tar xzf` step creates another layer with the extracted files. Both layers exist in the image's
layer history, but the tarball layer is effectively wasted space after extraction.

**How to avoid:**
Combine `COPY` and extraction into a single `RUN` using a heredoc or pipe to avoid intermediate
layers, or use a multi-stage build:

```dockerfile
# Stage 1: extract tarball
FROM eclipse-temurin:17-jre AS extractor
COPY distribution/server/target/dremio-community-*.tar.gz /tmp/dremio.tar.gz
RUN mkdir -p /opt/dremio && tar xzf /tmp/dremio.tar.gz -C /opt/dremio --strip-components=1

# Stage 2: runtime image (no tarball layer)
FROM eclipse-temurin:17-jre AS runtime
RUN groupadd --system dremio --gid 999 \
    && useradd --base-dir /var/lib/dremio --system --uid 999 --gid dremio dremio \
    && mkdir -p /opt/dremio/data /var/run/dremio /var/log/dremio /var/lib/dremio \
    && chown -R dremio:dremio /opt/dremio/data /var/run/dremio /var/log/dremio /var/lib/dremio
COPY --from=extractor --chown=dremio:dremio /opt/dremio /opt/dremio
```

Multi-stage build ensures the final image contains only the JRE and extracted Dremio files — no
tarball, no extraction tooling, no apt package lists.

**Warning signs:**
- `docker image ls` shows image > 2.5GB.
- ECR push time exceeds 10 minutes on a fast connection (indicates multiple large layers).
- `docker history <image>` shows two large layers (one for tarball copy, one for extraction).

**Phase to address:**
Phase 2 (Dockerfile adaptation) — design multi-stage from the start; retrofitting is painful.

---

### Pitfall 8: Tag Parsing Edge Cases — `v` Prefix Strip Breaks on Non-Semver Tags

**What goes wrong:**
The standard bash parameter expansion `${GITHUB_REF#refs/tags/v}` strips the `refs/tags/v` prefix
from a git ref like `refs/tags/v1.2.0`, producing `1.2.0`. This works correctly for tags matching the
`v*` workflow trigger pattern.

However, edge cases break this:
1. **Tag without `v` prefix:** If someone pushes `1.2.0` (no `v`), the workflow trigger `tags: ['v*']`
   does not fire, so this case is safely excluded from the pipeline. BUT if the trigger is changed to
   `tags: ['*']`, then `${GITHUB_REF#refs/tags/v}` produces `1.2.0` (correct for `v1.2.0`) but
   `${GITHUB_REF#refs/tags/v}` on `refs/tags/1.2.0` produces `1.2.0` only if there is no leading `v`
   — wait, `refs/tags/1.2.0` with `#refs/tags/v` strip produces `1.2.0` unchanged because the prefix
   `refs/tags/v` does not match `refs/tags/1.2.0`. Result: the tag `1.2.0` maps to image tag `1.2.0`,
   which is correct, but only by accident.
2. **Tag with double `v`:** `vv1.2.0` → strip `refs/tags/v` → `v1.2.0` → image tag is `v1.2.0` (has a
   `v`). Consumer scripts expecting SemVer without `v` will fail.
3. **Tag containing `/`:** `release/v1.2.0` → `${GITHUB_REF#refs/tags/}` → `release/v1.2.0` → Docker
   tag `release/v1.2.0` → invalid Docker tag (slashes are illegal except in registry/repo context).
4. **`GITHUB_REF` is empty:** If the workflow is triggered via `workflow_dispatch` (manual trigger),
   `GITHUB_REF` may not contain a tag. The strip operation produces an empty string, and the image
   gets tagged `:` — failing with `invalid reference format`.

**Why it happens:**
Bash parameter expansion `${var#prefix}` silently returns the original string if the prefix does not
match, rather than erroring. Docker tag validation errors are vague. The workflow trigger filter
`tags: ['v*']` provides partial protection but does not cover manual triggers or future trigger changes.

**How to avoid:**
Use a dedicated step that validates the tag format and exits if invalid:
```yaml
- name: Extract and validate version
  id: version
  run: |
    TAG="${GITHUB_REF#refs/tags/}"
    if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "ERROR: Tag '$TAG' does not match expected format vX.Y.Z"
      exit 1
    fi
    VERSION="${TAG#v}"
    echo "version=$VERSION" >> "$GITHUB_OUTPUT"
    echo "tag=$TAG" >> "$GITHUB_OUTPUT"
```
Reference the validated version in subsequent steps: `${{ steps.version.outputs.version }}`.

**Warning signs:**
- Docker build step fails with `invalid reference format` — usually indicates empty tag or illegal
  characters in the tag string.
- Image is pushed with tag `v1.2.0` (with `v`) instead of `1.2.0` (without `v`).
- Multiple images with different tag formats exist in ECR from different invocations.

**Phase to address:**
Phase 3 (ECR authentication and push) — validate tag parsing before the first push attempt.

---

### Pitfall 9: Secrets Leaked via Build Args or Workflow Logs

**What goes wrong:**
GitHub Actions masks secret values in workflow logs when secrets are accessed via
`${{ secrets.NAME }}`. However, masking is bypassed if:
1. A secret value is passed as a Docker `--build-arg` (e.g., `--build-arg AWS_KEY=${{ secrets.AWS_ACCESS_KEY_ID }}`). Docker build output may echo build args in layer metadata or build logs.
2. A secret is assigned to a variable using `echo "KEY=${{ secrets.KEY }}" >> $GITHUB_ENV` and that
   variable is later printed by a script.
3. `set -x` is enabled in a shell script that processes secret values — bash's debug output (`+ echo secret_value`) bypasses GitHub's masking.
4. A secret value appears in a Docker image label or environment variable embedded at build time via
   `--build-arg` and inspectable with `docker inspect`.

For this pipeline, the specific risk is the `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` being
inadvertently printed. The `configure-aws-credentials` action is designed to avoid this, but custom
scripts that re-export these values are not protected.

**Why it happens:**
GitHub Actions log masking works by comparing log output against known secret values. It does not mask
values that are set indirectly (via env vars set in previous steps that are not explicitly declared as
secrets). The Docker build process is a subprocess; its stdout is captured and logged by the runner,
but Docker layer commands are not post-processed by the masking filter in all contexts.

**How to avoid:**
- Never pass `AWS_ACCESS_KEY_ID` or `AWS_SECRET_ACCESS_KEY` as Docker `--build-arg` or `ENV`.
- Let `configure-aws-credentials` inject the credentials into the runner environment and let
  `ecr-login` use them transparently — do not re-export them in shell scripts.
- Do not use `set -x` in any script step that processes `${{ secrets.* }}` values.
- Do not `echo` or `cat` secret values for debugging, even temporarily.
- Do not embed credentials as Docker image labels or `ENV` instructions in the Dockerfile.
- Add `add-mask` as an extra guard for derived values:
  ```yaml
  - run: echo "::add-mask::${{ steps.login-ecr.outputs.registry }}"
  ```
  This masks the ECR registry URI in subsequent log output, preventing account ID exposure.

**Warning signs:**
- Workflow log shows a sequence of digits matching `AWS_ACCESS_KEY_ID` format (20 uppercase alphanumerics).
- `docker inspect <image>` shows credential-looking values in `Config.Env` or `Config.Labels`.
- A team member reports seeing AWS key values in a downloaded workflow log artifact.

**Phase to address:**
Phase 3 (ECR authentication and push) — review all steps that touch secrets before committing the workflow.

---

### Pitfall 10: Maven Cache Invalidation on `revision` Property — Build Always Downloads

**What goes wrong:**
Dremio uses Maven CI-friendly versioning: the root `pom.xml` declares `<version>${revision}</version>`.
The `revision` property is passed at build time via `-Drevision=X.Y.Z` or read from `.mvn/maven.config`.
When `actions/setup-java cache: 'maven'` or `actions/cache` keys on `hashFiles('**/pom.xml')`, the cache
key is stable as long as `pom.xml` files do not change.

However, installed artifacts in `~/.m2/repository` for the Dremio project itself (e.g.,
`com/dremio/dremio-parent/{revision}/`) will accumulate versions from every build run if the
`revision` changes between runs (e.g., because the build injects a timestamp-based version). A 3.3GB
Maven repo (measured locally) growing by 50–100MB per unique `revision` build will eventually exhaust
the GitHub Actions workspace disk (14GB limit).

More immediately: if the workflow does not pass `-Drevision` explicitly and the `pom.xml` does not
have a default value for `${revision}`, Maven will fail with `revision is undefined`. Checking the
local repo shows the `revision` is resolved via the `flatten-maven-plugin`'s `revision` property —
if this plugin does not run before dependent modules are resolved, cross-module version references fail.

**Why it happens:**
Maven's `${revision}` CI-friendly versioning requires the `flatten-maven-plugin` to write resolved
`pom.xml` files before reactor dependencies can resolve correctly. In a standard `mvn package` invocation
this happens automatically. In a parallel build with `-T 4C`, the flatten goal may not have completed
for a parent module before a child module attempts to resolve the parent's version, causing a reactor
build order failure.

**How to avoid:**
- Use `./mvnw package -DskipTests -Drevision=X.Y.Z` where `X.Y.Z` is derived from the git tag (e.g.,
  `${{ steps.version.outputs.version }}`). This makes the version explicit and matches the image tag.
- Do not use parallel Maven builds (`-T`) without testing on the full project tree first. The Dremio
  build is not guaranteed to be parallel-safe across all modules.
- Add `~/.m2/repository/com/dremio/` to a `.gitignore`-equivalent cache exclusion list or accept
  that the Dremio artifacts in the cache are rebuilt on every run (they are produced by the build itself
  and are not downloadable from Maven Central).

**Warning signs:**
- `mvn package` fails with `Could not find artifact com.dremio:dremio-parent:pom:${revision}`.
- Maven cache restore succeeds but subsequent build attempts to download `com.dremio:*` artifacts
  (these are always built from source and should never be downloaded).
- Workflow disk usage exceeds 10GB before the Docker build step (accumulated `revision` artifacts).

**Phase to address:**
Phase 1 (Maven build setup) — pass explicit `-Drevision` from the first workflow draft.

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| `java-version: 'latest'` in `setup-java` | No version to update | Build breaks when GH Actions updates `latest` to Java 22+, failing the enforcer check | Never — pin to `'21'` |
| Skipping `actions/cache` on first commit | Fewer lines of YAML | Every run is a 45–90 min cold build; CI becomes unusable within days | Never for this project |
| Using existing Dockerfile unchanged with a `file://` `DOWNLOAD_URL` trick | No Dockerfile changes | Fragile, undocumented, breaks on path changes | Never |
| `ubuntu-latest` instead of `ubuntu-22.04` | Always current | Docker image SHA changes under you; runner environment shifts break reproducibility | Acceptable only if you want automatic runner OS updates |
| Hardcoded ECR registry URI string in workflow | Simple copy-paste | URI must be updated in two places (secrets + workflow) on account or region change | Never — always use the `ecr-login` step output |
| `--build-arg` to pass secrets into Docker | Works once | Secrets end up in image layer history (retrievable via `docker history`) and in CI logs | Never |
| `jdk` base image instead of `jre` | Slightly easier debugging in container | +300MB image size permanently | Acceptable in development environments; never in production images |

---

## Integration Gotchas

Common mistakes when connecting to external services.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| AWS ECR | Using `@v1` of `amazon-ecr-login` — outputs `registry` differently | Always use `@v2`; reference registry as `${{ steps.login-ecr.outputs.registry }}` |
| AWS ECR | Missing `ecr:BatchCheckLayerAvailability` in IAM policy | Use `AmazonEC2ContainerRegistryPowerUser` managed policy; do not write a minimal custom policy |
| AWS ECR | ECR repository does not exist before first push | Create the repository in AWS console or Terraform before running the workflow |
| AWS ECR | Pushing to the wrong region — ECR URI contains region; `AWS_REGION` secret must match the registry region | Verify registry URI format: `{account_id}.dkr.ecr.{region}.amazonaws.com/{repo}` |
| Docker Buildx | Not calling `docker/setup-buildx-action` before `build-push-action` | `build-push-action` requires Buildx; without it, `cache-from`/`cache-to` options silently fail |
| GitHub Secrets | Secret added to wrong scope (Organization vs Repository) | For a private fork, add secrets to the specific repository, not the organization (unless org-level inheritance is configured) |
| Maven + GitHub Actions | Using `mvn` directly instead of `./mvnw` | `mvnw` uses the Maven version pinned by the project wrapper; `mvn` uses whatever Maven the runner provides, which may not match |

---

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| No Docker layer cache | Every push rebuilds all layers (5–10 min for 864MB tarball layer alone) | Use ECR registry cache (`type=registry`) from the first run | After first push once team starts pushing multiple releases per sprint |
| Maven cache keyed on root `pom.xml` only | Cache is never invalidated on sub-module changes; can serve stale JARs or fail to cache new deps | Key on `**/pom.xml` glob (all pom files) | First time a sub-module pom changes |
| Single-job workflow (Maven + Docker in one job) | Runner disk pressure: Maven repo (3.3GB) + Docker build (2GB+ intermediate) + tarball (864MB) can approach 14GB disk limit | Split into two jobs: Maven build (upload tarball artifact) + Docker build (download artifact) if disk pressure is observed | When `~/.m2` cache is restored AND a large Docker build runs in the same job |
| Full Maven build for packaging only | 30–60 min per pipeline run | Use `./mvnw package -pl distribution/server -am -DskipTests` after verifying all custom modules are transitive dependencies | Immediately — optimize from the first workflow version |
| `ubuntu-latest` runner churn | Reproducibility issues as the runner OS and pre-installed tools change | Pin to `ubuntu-22.04` for stability | When GitHub updates `ubuntu-latest` to Ubuntu 24+ |

---

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| IAM access key with `AdministratorAccess` policy for CI | Compromised key = full AWS account access | Create a dedicated CI IAM user with only `AmazonEC2ContainerRegistryPowerUser` (or a minimal custom policy scoped to the specific ECR repository) |
| Long-lived access keys (no rotation) | Keys leaked via log or source become permanently usable | Rotate access keys every 90 days; set up a GitHub Dependabot or cron job to alert on key age |
| `AWS_ACCESS_KEY_ID` visible in `docker build` output | Key appears in build logs in plain text | Never pass secrets as `--build-arg`; let `configure-aws-credentials` manage env vars |
| Image pushed with `latest` tag as the only tag | No version traceability; `latest` in ECR does not mean the image is safe or from a specific release | Always push BOTH the version tag AND `latest` — never push only `latest` |
| ECR repository with public access enabled | Anyone can pull Dremio images including any sensitive configuration baked in | Keep ECR repository private (default); never enable public access for a fork with custom configuration |
| No `contents: read` permission constraint | Default `GITHUB_TOKEN` permissions are broader than needed for a packaging workflow | Add explicit `permissions: contents: read` at the job or workflow level |

---

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Java version:** Verify `java -version` in the Maven step log shows `21.x.x` — not 11, 17, or 22.
- [ ] **Maven cache hit:** On second run, verify the Maven step log shows "Cache restored" and build time drops from 45+ minutes to under 20 minutes.
- [ ] **Tarball produced:** Confirm the Maven step produces a `*.tar.gz` file at `distribution/server/target/dremio-community-*.tar.gz` by listing the directory in a step after Maven.
- [ ] **Dockerfile uses COPY not wget:** Inspect the Docker build log — the first substantive instruction should be `COPY`, not `RUN wget`.
- [ ] **Runtime is JRE not JDK:** `docker run {image} java -version` should show a JRE build, not a JDK build. `docker inspect {image}` should show `eclipse-temurin:17-jre` as the base.
- [ ] **Image tag correct:** ECR console should show the image with tag `1.2.0` (no `v` prefix), not `v1.2.0`, `latest` only, or empty.
- [ ] **ECR push succeeded:** `aws ecr describe-images --repository-name dremio-oss` returns the expected image digest and the correct tag.
- [ ] **No secrets in logs:** Manually inspect the full workflow run log and confirm `AWS_ACCESS_KEY_ID` value (20 alphanumeric characters) does not appear in any step output.
- [ ] **Container starts:** After push, `docker run -p 9047:9047 {ecr-image}:1.2.0` should bring up Dremio without JVM errors. Check `docker logs` for `Server is up`.

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Wrong Java version (enforcer failure) | LOW | Update `java-version` to `'21'` in `actions/setup-java`, push fix commit |
| Cold build every run (no cache) | LOW | Add `actions/cache` step before Maven; wait for next run to populate cache |
| Dockerfile `wget` failure | LOW | Create `Dockerfile.ci` with `COPY` pattern; update workflow `file:` reference |
| Wrong base image (JDK instead of JRE, or Java 11) | LOW | Update `JAVA_IMAGE` arg in `Dockerfile.ci`; rebuild and push |
| ECR auth token scope wrong | MEDIUM | Update IAM policy for CI user; may require AWS console access; test with `aws ecr get-login-password` locally first |
| Secrets missing from GitHub repo | LOW | Add secrets in Settings > Secrets and Variables > Actions; re-run failed workflow |
| Image tag has `v` prefix or is empty | LOW | Fix the version extraction step; push a new tag to re-trigger the pipeline |
| Secrets leaked in logs | HIGH | Immediately rotate `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in AWS IAM; update GitHub Secrets with new values; audit ECR for unauthorized pushes |
| Docker image too large (>2.5GB) | MEDIUM | Refactor Dockerfile to multi-stage build; rebuild; push new version; old oversized images remain in ECR until manually deleted |
| Maven cache stale (wrong pom hash key) | LOW | Update cache key to `hashFiles('**/pom.xml')`; delete old cache in GitHub Actions > Caches; next run rebuilds |

---

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Java 21 enforcer (P1) | Phase 1: Maven build setup | `java -version` shows `21.x` in step log; Maven build reaches `[INFO] BUILD SUCCESS` |
| Maven timeout / cache (P2) | Phase 1: Maven build setup | Second run completes in < 25 min; "Cache restored" in step log |
| Dockerfile `wget` incompatibility (P3) | Phase 2: Dockerfile adaptation | Docker build log shows `COPY` not `wget`; no `DOWNLOAD_URL` build arg in step |
| Java version mismatch build vs runtime (P4) | Phase 2: Dockerfile adaptation | `docker inspect` shows JRE 17 base; container startup shows `java version "17"` |
| ECR token expiry / action version mismatch (P5) | Phase 3: ECR authentication | `ecr-login` output `registry` variable is non-empty; push succeeds end-to-end |
| Missing / misconfigured IAM secrets (P6) | Phase 3: ECR authentication | `aws sts get-caller-identity` in a test step returns account identity before pipeline runs |
| Docker image size bloat (P7) | Phase 2: Dockerfile adaptation | `docker image ls` shows final image < 1.5GB; `docker history` shows multi-stage build |
| Tag parsing edge cases (P8) | Phase 3: ECR authentication | Test with tag `v1.2.3`: ECR image tag is `1.2.3`; validate step exits non-zero for malformed tags |
| Secrets in logs (P9) | Phase 3: ECR authentication | Full workflow log audit before marking pipeline complete |
| Maven `revision` cache pollution (P10) | Phase 1: Maven build setup | Build passes explicit `-Drevision`; no `com.dremio:*` download attempts in Maven log |

---

## Sources

- **Codebase analysis (HIGH confidence — directly measured):**
  - `distribution/docker/Dockerfile` — `ARG DOWNLOAD_URL` + `wget` pattern; `eclipse-temurin:11-jdk` base image confirmed
  - `distribution/server/target/dremio-community-26.0.5-*.tar.gz` — 864MB measured with `du -sh`
  - `~/.m2/repository` — 3.3GB measured with `du -sh`; confirms Maven repo size for cache planning
  - `build-tools/pom.xml` — `requireJavaVersion [21,22)` enforcer range confirmed by direct read
  - `pom.xml` root — 158 Maven modules confirmed by `find . -name pom.xml | wc -l`; `maven.compiler.release=11`
  - `distribution/server/pom.xml` — `dremio.distribution.tar.maxSize=980000000` (980MB max)

- **GitHub Actions official documentation (HIGH confidence — stable platform behavior):**
  - `actions/setup-java@v4` — `java-version` semver resolution behavior
  - `actions/cache@v4` — `hashFiles()` glob pattern behavior; restore-keys fallback
  - `docker/build-push-action@v6` — `context:`, `file:`, `tags:`, `push:` parameters
  - `docker/setup-buildx-action@v3` — required for `build-push-action` cache-from/cache-to
  - `aws-actions/configure-aws-credentials@v4` — env var injection scoping
  - `aws-actions/amazon-ecr-login@v2` — `registry` step output format
  - `GITHUB_REF` format for tag refs — `refs/tags/v1.2.0` format

- **AWS ECR documentation (HIGH confidence — established API):**
  - ECR authorization token lifetime: 12 hours (AWS official doc)
  - IAM permissions for `ecr-login` and `docker push`: `GetAuthorizationToken`, `BatchCheckLayerAvailability`,
    `InitiateLayerUpload`, `UploadLayerPart`, `CompleteLayerUpload`, `PutImage`
  - `AmazonEC2ContainerRegistryPowerUser` managed policy — covers all required ECR push permissions

- **Docker documentation (HIGH confidence — stable behavior):**
  - Layer caching: each `RUN` instruction creates an immutable layer; `COPY` + separate `RUN tar` = two layers
  - Multi-stage builds: final image contains only files explicitly `COPY --from=`'d from builder stages
  - `eclipse-temurin:17-jre` vs `eclipse-temurin:17-jdk` size difference (~300MB)

---

*Pitfall research for: GitHub Actions CI/CD pipeline — Maven build + Docker + ECR push for Dremio OSS fork*
*Researched: 2026-02-20*
