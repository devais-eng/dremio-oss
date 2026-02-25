---
phase: quick-2
plan: 01
type: execute
wave: 1
depends_on: []
files_modified: [".github/workflows/docker-ecr.yml"]
autonomous: true
requirements: [QUICK-2]

must_haves:
  truths:
    - "Workflow has two separate jobs: build and docker"
    - "build job compiles Maven and uploads tarball as artifact"
    - "docker job downloads artifact, builds Docker image, and pushes to ECR"
    - "docker job depends on build job via needs: build"
    - "Re-run failed jobs only re-runs docker if build succeeded"
    - "Version output is propagated from build job to docker job"
  artifacts:
    - path: ".github/workflows/docker-ecr.yml"
      provides: "Two-job workflow with artifact passing"
      contains: "needs: build"
  key_links:
    - from: "build job"
      to: "docker job"
      via: "actions/upload-artifact and actions/download-artifact"
      pattern: "upload-artifact|download-artifact"
    - from: "build job outputs"
      to: "docker job version reference"
      via: "jobs.build.outputs.version"
      pattern: "needs\\.build\\.outputs\\.version"
---

<objective>
Split the single-job docker-ecr.yml workflow into two jobs (build + docker) connected by a GitHub Actions artifact, so failed Docker pushes can be re-run without rebuilding Maven.

Purpose: A Maven build takes 10+ minutes. If only the ECR push fails (credentials, network, etc.), the user should be able to "Re-run failed jobs" and skip the Maven build entirely.
Output: Updated `.github/workflows/docker-ecr.yml` with two jobs.
</objective>

<execution_context>
@/home/emanuele/.claude/get-shit-done/workflows/execute-plan.md
@/home/emanuele/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.github/workflows/docker-ecr.yml
@distribution/docker/Dockerfile
</context>

<tasks>

<task type="auto">
  <name>Task 1: Split docker-ecr.yml into build and docker jobs with artifact passing</name>
  <files>.github/workflows/docker-ecr.yml</files>
  <action>
Rewrite `.github/workflows/docker-ecr.yml` to have two jobs instead of one. Keep the workflow name and triggers identical.

**Job 1: `build`**
- runs-on: ubuntu-latest
- Steps:
  1. `actions/checkout@v4`
  2. Extract version from tag: `echo "VERSION=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"` (id: version)
  3. `actions/setup-java@v5` with java-version 21, distribution temurin, cache maven
  4. Maven build: `./mvnw package -DskipTests -Pdremio.no-lint -pl distribution/server -am`
  5. Verify tarball: `ls -lh distribution/server/target/dremio-community-*.tar.gz`
  6. `actions/upload-artifact@v4` with:
     - name: `dremio-tarball`
     - path: `distribution/server/target/dremio-community-*.tar.gz`
     - retention-days: 1
     - if-no-files-found: error
- outputs:
  ```yaml
  outputs:
    version: ${{ steps.version.outputs.VERSION }}
  ```

**Job 2: `docker`**
- runs-on: ubuntu-latest
- needs: build
- Steps:
  1. `actions/checkout@v4` with `sparse-checkout: distribution/docker/Dockerfile` and `sparse-checkout-cone-mode: false` (only need the Dockerfile, not the full repo)
  2. `actions/download-artifact@v4` with name: `dremio-tarball` and path: `docker-context`
  3. Stage Docker context:
     ```
     mv docker-context/dremio-community-*.tar.gz docker-context/dremio.tar.gz
     cp distribution/docker/Dockerfile docker-context/Dockerfile
     ```
  4. `docker/setup-buildx-action@v3`
  5. `aws-actions/configure-aws-credentials@v4` (same secrets as current)
  6. `aws-actions/amazon-ecr-login@v2` (id: login-ecr)
  7. `docker/build-push-action@v6` with context: docker-context, file: docker-context/Dockerfile, push: true, tags using `${{ needs.build.outputs.version }}` instead of `${{ steps.version.outputs.VERSION }}`

Keep the existing comments block about required GitHub Secrets above the AWS credentials step.

Preserve the permissions block (`contents: read`) at the workflow level.
  </action>
  <verify>
Run `cat .github/workflows/docker-ecr.yml` and confirm:
- Two jobs exist: `build` and `docker`
- `build` job has `outputs:` with version
- `docker` job has `needs: build`
- `docker` job uses `actions/download-artifact@v4`
- `build` job uses `actions/upload-artifact@v4`
- Docker tags reference `${{ needs.build.outputs.version }}`
- YAML is valid: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/docker-ecr.yml'))"`
  </verify>
  <done>
docker-ecr.yml contains two jobs. The build job compiles Maven and uploads the tarball artifact. The docker job downloads the artifact, builds the Docker image, and pushes to ECR. Version is passed via job outputs. YAML validates without errors.
  </done>
</task>

</tasks>

<verification>
- `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/docker-ecr.yml'))"` succeeds (valid YAML)
- Workflow file contains exactly two job keys: `build` and `docker`
- `needs: build` is present in the docker job
- `upload-artifact` appears in build job, `download-artifact` appears in docker job
- Version tag reference uses `needs.build.outputs.version`
- No leftover Docker/ECR steps in the build job
- No Maven/Java steps in the docker job
</verification>

<success_criteria>
The docker-ecr.yml workflow is split into two jobs connected by an artifact. If the docker job fails, GitHub's "Re-run failed jobs" will only re-run the docker job, skipping the Maven build entirely.
</success_criteria>

<output>
After completion, create `.planning/quick/2-split-docker-ecr-workflow-into-build-and/2-SUMMARY.md`
</output>
