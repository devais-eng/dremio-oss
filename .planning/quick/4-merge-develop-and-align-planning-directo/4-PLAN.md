---
phase: quick
plan: 4
type: execute
wave: 1
depends_on: []
files_modified:
  - .planning/PROJECT.md
  - .planning/ROADMAP.md
  - .planning/STATE.md
  - .planning/MILESTONES.md
autonomous: true
requirements: []
must_haves:
  truths:
    - "rbac branch contains all commits from origin/develop (no divergence on non-planning code)"
    - "Merge commit exists referencing develop integration"
    - ".planning/ files reflect the combined project history (RBAC + Docker/CI milestones)"
    - "STATE.md shows accurate current position after merge"
  artifacts:
    - path: ".planning/PROJECT.md"
      provides: "Combined project description covering RBAC, Iceberg, and Docker milestones"
    - path: ".planning/ROADMAP.md"
      provides: "Roadmap with all milestones from both branches"
    - path: ".planning/STATE.md"
      provides: "Current state reflecting post-merge position"
  key_links:
    - from: "rbac branch"
      to: "origin/develop"
      via: "git merge"
      pattern: "merge commit on rbac branch"
---

<objective>
Merge origin/develop into the rbac branch, resolve .planning/ conflicts by keeping rbac's versions (RBAC is the primary product story), then reconcile the .planning/ top-level files to reflect the combined project history across all milestones (RBAC v1.0, Iceberg v1.1, Docker/CI v1.2 from develop, and RBAC enforcement v1.2 from rbac).

Purpose: Bring non-planning code from develop (Dockerfile, GitHub Actions workflow, security fixes) into rbac without clobbering the RBAC planning artifacts.
Output: Merged branch with consistent code and unified .planning/ narrative.
</objective>

<execution_context>
@/home/emanuele/.claude/get-shit-done/workflows/execute-plan.md
@/home/emanuele/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
</context>

<tasks>

<task type="auto">
  <name>Task 1: Merge origin/develop into rbac, keeping rbac .planning/ on conflicts</name>
  <files>
    (merge commit — all conflict-resolved files)
  </files>
  <action>
    Run the merge, then resolve all .planning/ conflicts by taking rbac's version (ours), and take develop's version for non-.planning/ files if conflicts arise.

    Step 1 — Start the merge:
    ```
    git merge origin/develop --no-ff -m "chore: merge origin/develop into rbac — bring in Docker/CI and security fixes"
    ```

    Step 2 — If merge conflicts arise, resolve them:
    - For .planning/ files: `git checkout --ours -- .planning/` (keep rbac's RBAC-focused planning)
    - For non-.planning/ source files with conflicts: inspect manually and prefer develop's version unless it conflicts with RBAC logic. The key source files from develop are: `.github/workflows/docker-ghcr.yml`, `distribution/docker/Dockerfile`, and security/vulnerability fixes.
    - Stage resolved files: `git add .`

    Step 3 — Complete the merge:
    ```
    git commit --no-edit
    ```

    IMPORTANT: The following files from develop must be present in the final merge:
    - `.github/workflows/docker-ghcr.yml` (GHCR push workflow)
    - `distribution/docker/Dockerfile` (multi-stage build)
    - `.gitignore` changes
    - `common/legacy/src/main/resources/dremio-reference.conf` changes
    - Security fix: `fix: pin actions/download-artifact to v4.1.8`

    Do NOT include develop's .planning/ files — keep rbac's versions.
  </action>
  <verify>
    1. `git log --oneline -3` — shows merge commit at HEAD
    2. `git log --oneline origin/develop..HEAD` — shows rbac commits on top
    3. `git diff origin/develop -- .planning/STATE.md` — shows rbac's STATE.md (RBAC milestone content)
    4. `ls .github/workflows/` — docker-ghcr.yml present
  </verify>
  <done>
    Merge commit exists. rbac branch contains all develop commits. .planning/ reflects rbac's versions. Non-planning code from develop (Dockerfile, workflow, security fixes) is present.
  </done>
</task>

<task type="auto">
  <name>Task 2: Align .planning/ top-level files to reflect combined project history</name>
  <files>
    .planning/PROJECT.md
    .planning/ROADMAP.md
    .planning/STATE.md
    .planning/MILESTONES.md
  </files>
  <action>
    After the merge, update the top-level .planning/ files to reflect the complete combined history. The rbac branch is the authoritative version but its files only know about the RBAC milestone (v1.2 Privilege Context & Enforcement). Develop had a different v1.2 (GitHub Actions Docker Distribution). Rename to avoid collision and update narratives.

    **Milestone naming resolution:**
    - develop's v1.2 = "GitHub Actions Docker Distribution" (ECR → GHCR workflow)
    - rbac's v1.2 = "Privilege Context & Enforcement" (RBAC enforcement)
    - Resolution: rename rbac's v1.2 to v1.3 "Privilege Context & Enforcement", keep develop's v1.2 as-is.

    **Update .planning/PROJECT.md:**
    - Title: "Dremio OSS Enhancements" (broader scope than just RBAC)
    - What This Is: describe all three areas — RBAC, Iceberg REST Catalog, Docker CI/CD
    - Core Value: "Make Dremio OSS a production-capable data lakehouse query engine by closing critical gaps in access control, catalog connectivity, and deployment automation."
    - Requirements: merge both branches' requirements lists, keeping all ✓ items from both
    - Add Docker/CI requirements from develop (GitHub Actions workflow, Dockerfile, GHCR push)

    **Update .planning/ROADMAP.md:**
    - Add develop's milestones (v1.2 GitHub Actions Docker Distribution, phases 9-11) before rbac's enforcement milestone
    - Rename rbac's v1.2 to v1.3 "Privilege Context & Enforcement" — phases 12-20 (or whatever numbers were used)
    - Mark all shipped
    - Add Quick Tasks section listing quick-1 through quick-3 (from develop) and note they exist in .planning/quick/

    **Update .planning/STATE.md:**
    - Shipped Milestones: list v1.0, v1.1, v1.2 (Docker), v1.3 (RBAC Enforcement)
    - Current Position: "All milestones complete. Next milestone TBD."
    - Last activity: 2026-03-01 — merged develop into rbac, aligned planning directory
    - Known Limitations: keep rbac's list (UI search, guard scope, bulkGetTables performance)

    **Update .planning/MILESTONES.md:**
    - Add v1.2 (GitHub Actions Docker Distribution) entry from develop's milestone data
    - Rename existing v1.2 entry to v1.3

    Keep all existing .planning/milestones/ subdirectories and .planning/phases/ as-is.
    Keep .planning/quick/ directory as-is (quick-1 through quick-3 from develop are already present via merge).

    Commit all changes: `git add .planning/PROJECT.md .planning/ROADMAP.md .planning/STATE.md .planning/MILESTONES.md && git commit -m "docs: align .planning/ to reflect combined project history post-merge"`
  </action>
  <verify>
    1. `grep -i "docker\|ghcr\|ecr" .planning/PROJECT.md` — Docker/CI content present
    2. `grep "v1.3\|Privilege Context" .planning/ROADMAP.md` — RBAC enforcement milestone present with correct version
    3. `grep "GitHub Actions\|Docker Distribution" .planning/ROADMAP.md` — v1.2 Docker milestone present
    4. `grep "v1.3" .planning/STATE.md` — STATE.md lists v1.3
    5. `git log --oneline -5` — alignment commit present
  </verify>
  <done>
    .planning/ top-level files describe all four milestones coherently. No version number collision. RBAC milestone is v1.3. Docker CI milestone is v1.2. STATE.md reflects post-merge state.
  </done>
</task>

</tasks>

<verification>
- `git log --oneline -5` — merge commit + alignment commit visible
- `git diff origin/develop -- .github/workflows/docker-ghcr.yml` — no diff (workflow from develop is present)
- `git diff origin/master -- .planning/STATE.md` — not empty (planning content reflects rbac work)
- `.planning/ROADMAP.md` contains both "GitHub Actions" and "Privilege Context" milestone entries
- `ls .planning/quick/` — quick-1, quick-2, quick-3, quick-4 directories present
</verification>

<success_criteria>
- rbac branch merged with origin/develop, no uncommitted changes
- .planning/ directory tells the complete project story (RBAC + Iceberg + Docker/CI)
- No milestone version number conflicts
- git log shows clean merge commit and follow-up alignment commit
</success_criteria>

<output>
After completion, create `.planning/quick/4-merge-develop-and-align-planning-directo/4-SUMMARY.md`
</output>
