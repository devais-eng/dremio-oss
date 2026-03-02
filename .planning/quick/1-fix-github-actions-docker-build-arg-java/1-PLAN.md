---
phase: quick
plan: 1
type: execute
wave: 1
depends_on: []
files_modified:
  - distribution/docker/Dockerfile
autonomous: true
requirements: [FIX-DOCKER-ARG-SCOPE]

must_haves:
  truths:
    - "Docker build succeeds without 'InvalidDefaultArgInFrom' or 'UndefinedArgInFrom' warnings"
    - "The runtime stage uses eclipse-temurin:17-jre-jammy as its base image"
    - "The extractor stage and tarball extraction still function correctly"
  artifacts:
    - path: "distribution/docker/Dockerfile"
      provides: "Multi-stage Docker build with correct ARG scoping"
      contains: "ARG JAVA_IMAGE"
  key_links:
    - from: "ARG JAVA_IMAGE (global scope)"
      to: "FROM ${JAVA_IMAGE} AS runtime"
      via: "Docker global ARG scoping rule"
      pattern: "ARG JAVA_IMAGE.*\\nARG TARBALL_PATH.*\\nFROM busybox|ARG TARBALL_PATH.*\\nARG JAVA_IMAGE.*\\nFROM busybox"
---

<objective>
Fix Docker build failure caused by ARG JAVA_IMAGE being declared inside a build stage instead of at global scope.

Purpose: The Dockerfile's `ARG JAVA_IMAGE` on line 24 is scoped to the `extractor` stage (after `FROM busybox`), making it unavailable to the second `FROM ${JAVA_IMAGE}` instruction. Docker requires ARGs used in FROM instructions to be declared at global scope (before the first FROM). This causes the GitHub Actions Docker build to fail with "base name (${JAVA_IMAGE}) should not be blank".

Output: A corrected Dockerfile where `ARG JAVA_IMAGE` is at global scope, and the Docker build succeeds.
</objective>

<execution_context>
@/home/emanuele/.claude/get-shit-done/workflows/execute-plan.md
@/home/emanuele/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@distribution/docker/Dockerfile
@.github/workflows/docker-ecr.yml
</context>

<tasks>

<task type="auto">
  <name>Task 1: Move ARG JAVA_IMAGE to global scope in Dockerfile</name>
  <files>distribution/docker/Dockerfile</files>
  <action>
    In `distribution/docker/Dockerfile`, move the `ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"` declaration from line 24 (inside the extractor stage) to global scope (before the first FROM).

    Specifically:
    1. Add `ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"` on line 17 (after the existing `ARG TARBALL_PATH=dremio.tar.gz` on line 16, before the `FROM busybox AS extractor` on what is currently line 17).
    2. Remove the now-redundant `ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"` from its current position on line 24 (between the extractor stage's `RUN` and the second `FROM`).
    3. Keep the blank line before `FROM ${JAVA_IMAGE} AS runtime` for readability.

    Why this works: In Docker, ARG instructions declared before the first FROM are "global" and available to all subsequent FROM instructions for variable substitution. ARG instructions declared after a FROM are scoped only to that build stage. Since `JAVA_IMAGE` is used in a FROM instruction, it must be global.

    The resulting top section of the Dockerfile should look like:
    ```
    ARG TARBALL_PATH=dremio.tar.gz
    ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"
    FROM busybox AS extractor
    ARG TARBALL_PATH
    COPY ${TARBALL_PATH} /tmp/dremio.tar.gz
    RUN mkdir -p /opt/dremio \
        && tar xzf /tmp/dremio.tar.gz -C /opt/dremio --strip-components=1 \
        && rm /tmp/dremio.tar.gz

    FROM ${JAVA_IMAGE} AS runtime
    ```

    Note: `ARG TARBALL_PATH` is re-declared inside the extractor stage (line 18) because it is used within that stage (in the COPY instruction). The global ARG on line 16 makes it available to FROM, and the re-declaration on line 18 makes it available within the stage. `JAVA_IMAGE` does NOT need re-declaration inside any stage since it is only used in a FROM instruction.
  </action>
  <verify>
    Run Docker build syntax check (dry-run) to confirm no ARG scoping warnings:
    ```
    cd distribution/docker && docker build --check -f Dockerfile . 2>&1 || true
    ```
    If `docker build --check` is not available, verify with:
    ```
    grep -n "ARG JAVA_IMAGE" distribution/docker/Dockerfile
    ```
    Expected: Only one match, on a line BEFORE the first `FROM busybox` line.

    Also verify the overall structure:
    ```
    grep -n "^ARG\|^FROM" distribution/docker/Dockerfile
    ```
    Expected output should show both global ARGs before the first FROM:
    ```
    16:ARG TARBALL_PATH=dremio.tar.gz
    17:ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"
    18:FROM busybox AS extractor
    ...
    XX:FROM ${JAVA_IMAGE} AS runtime
    ```
  </verify>
  <done>
    ARG JAVA_IMAGE is declared at global scope (before the first FROM instruction). The Dockerfile has exactly two global ARGs (TARBALL_PATH and JAVA_IMAGE) before `FROM busybox AS extractor`. The old ARG JAVA_IMAGE between the extractor stage and the runtime FROM is removed. No duplicate ARG JAVA_IMAGE declarations exist.
  </done>
</task>

</tasks>

<verification>
1. `grep -n "^ARG\|^FROM" distribution/docker/Dockerfile` shows both ARGs before first FROM
2. `grep -c "ARG JAVA_IMAGE" distribution/docker/Dockerfile` returns exactly 1
3. The Dockerfile structure is: global ARGs -> FROM extractor -> FROM runtime -> rest of build
</verification>

<success_criteria>
- ARG JAVA_IMAGE declared at global scope (before first FROM)
- No duplicate ARG JAVA_IMAGE declarations
- Docker build no longer fails with "base name (${JAVA_IMAGE}) should not be blank"
- Extractor stage still has its own `ARG TARBALL_PATH` re-declaration for in-stage use
</success_criteria>

<output>
After completion, create `.planning/quick/1-fix-github-actions-docker-build-arg-java/1-SUMMARY.md`
</output>
