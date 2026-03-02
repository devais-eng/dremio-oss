---
phase: quick-3
plan: 01
type: execute
wave: 1
depends_on: []
files_modified: [".github/workflows/docker-ecr.yml"]
autonomous: true
requirements: [QUICK-3]

must_haves:
  truths:
    - "Workflow pushes Docker image to ghcr.io instead of AWS ECR"
    - "No AWS secrets or actions remain in the workflow"
    - "Image is tagged with version and latest, namespaced under the repo owner"
  artifacts:
    - path: ".github/workflows/docker-ecr.yml"
      provides: "GHCR-based Docker build and push workflow"
      contains: "ghcr.io"
  key_links:
    - from: ".github/workflows/docker-ecr.yml"
      to: "ghcr.io"
      via: "docker/login-action with GITHUB_TOKEN"
      pattern: "registry:\\s*ghcr\\.io"
---

<objective>
Replace AWS ECR authentication and push with GitHub Container Registry (GHCR) in the docker job of the existing two-job workflow.

Purpose: Eliminate AWS dependency for Docker image distribution; GHCR is free for public repos, requires zero external account setup, and authenticates via the built-in GITHUB_TOKEN.
Output: Updated `.github/workflows/docker-ecr.yml` pushing to `ghcr.io/<owner>/dremio-oss`.
</objective>

<execution_context>
@/home/emanuele/.claude/get-shit-done/workflows/execute-plan.md
@/home/emanuele/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.github/workflows/docker-ecr.yml
</context>

<tasks>

<task type="auto">
  <name>Task 1: Replace ECR with GHCR in docker job</name>
  <files>.github/workflows/docker-ecr.yml</files>
  <action>
In `.github/workflows/docker-ecr.yml`, make the following changes:

1. **Rename the workflow:** Change `name:` from "Build and Push to ECR" to "Build and Push to GHCR".

2. **Add packages write permission:** Update the top-level `permissions` block to:
   ```yaml
   permissions:
     contents: read
     packages: write
   ```

3. **Replace the docker job steps** (lines 87-106 approximately). Remove ALL of these steps:
   - "Configure AWS credentials" (aws-actions/configure-aws-credentials@v4)
   - "Login to Amazon ECR" (aws-actions/amazon-ecr-login@v2)
   - The old "Build and push Docker image" step with ECR tags

   Also remove the entire comment block about Required GitHub Secrets (AWS_ACCESS_KEY_ID, etc.).

4. **Add GHCR login step** (after "Set up Docker Buildx"):
   ```yaml
   - name: Login to GHCR
     uses: docker/login-action@v3
     with:
       registry: ghcr.io
       username: ${{ github.repository_owner }}
       password: ${{ secrets.GITHUB_TOKEN }}
   ```

5. **Replace the build-push step** with GHCR tags:
   ```yaml
   - name: Build and push Docker image
     uses: docker/build-push-action@v6
     with:
       context: docker-context
       file: docker-context/Dockerfile
       push: true
       tags: |
         ghcr.io/${{ github.repository_owner }}/dremio-oss:${{ needs.build.outputs.version }}
         ghcr.io/${{ github.repository_owner }}/dremio-oss:latest
   ```

   Use lowercase `github.repository_owner` (GHCR requires lowercase registry paths; `github.repository_owner` is already lowercase).

6. **Leave the build job completely unchanged.** Do not touch anything in the `build:` job -- it is independent of the registry.

7. **Leave the `on:` trigger block unchanged.** Keep `push: tags: ['v*']` and `workflow_dispatch` with version input.

Do NOT rename the file itself (keep `docker-ecr.yml` -- renaming can be a follow-up).
  </action>
  <verify>
Run: `cat .github/workflows/docker-ecr.yml` and confirm:
- No references to `aws-actions/configure-aws-credentials` or `aws-actions/amazon-ecr-login`
- No references to `secrets.AWS_ACCESS_KEY_ID`, `secrets.AWS_SECRET_ACCESS_KEY`, `secrets.AWS_REGION`, `secrets.ECR_REPOSITORY`
- Contains `docker/login-action@v3` with `registry: ghcr.io`
- Tags contain `ghcr.io/${{ github.repository_owner }}/dremio-oss:`
- `permissions` includes `packages: write`
- Build job is unchanged (still has maven, upload-artifact, setup-java)
- YAML is valid: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/docker-ecr.yml'))"`
  </verify>
  <done>
Workflow authenticates to GHCR via GITHUB_TOKEN and pushes Docker images to ghcr.io/<owner>/dremio-oss with version and latest tags. All AWS/ECR references removed. Build job untouched.
  </done>
</task>

</tasks>

<verification>
- No AWS secrets or actions referenced anywhere in the workflow file
- GHCR login uses docker/login-action@v3 with GITHUB_TOKEN (no external secrets needed)
- Image tags follow GHCR convention: ghcr.io/<owner>/<image>:<tag>
- permissions block includes packages: write (required for GHCR push)
- YAML parses without errors
</verification>

<success_criteria>
The docker-ecr.yml workflow, when triggered, will authenticate to GHCR using the built-in GITHUB_TOKEN and push the Dremio Docker image to ghcr.io with version and latest tags. No AWS credentials or configuration are required.
</success_criteria>

<output>
After completion, create `.planning/quick/3-change-ecr-action-switch-to-ghcr-github-/3-SUMMARY.md`
</output>
