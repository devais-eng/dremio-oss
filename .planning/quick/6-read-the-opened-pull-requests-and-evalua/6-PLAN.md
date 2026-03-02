---
phase: quick-6
plan: 6
type: execute
wave: 1
depends_on: []
files_modified: []
autonomous: false
requirements: []

must_haves:
  truths:
    - "All open Copilot PRs are listed with their review comments"
    - "Each Copilot comment is evaluated as actionable, informational, or ignorable"
    - "A written assessment exists documenting decisions for each comment"
  artifacts: []
  key_links: []
---

<objective>
Read all open pull requests in the devais-eng/dremio-oss repository that originate from
Copilot branches, fetch their review comments, and evaluate each comment to determine
whether it is actionable (requires a code change), informational (no action needed), or
ignorable (incorrect/irrelevant). Produce a written evaluation as output.

Purpose: Ensure no Copilot feedback is missed and decisions about each comment are
explicit and documented before closing or merging any PR.

Output: A written evaluation of all Copilot PR comments, printed to the console.
</objective>

<execution_context>
@/home/emanuele/.claude/get-shit-done/workflows/execute-plan.md
</execution_context>

<context>
@/home/emanuele/IdeaProjects/dremio-oss/.planning/STATE.md
@/home/emanuele/IdeaProjects/dremio-oss/.planning/PROJECT.md
</context>

<tasks>

<task type="checkpoint:human-action">
  <name>Task 1: Authenticate GitHub CLI</name>
  <what-built>gh CLI is installed at /snap/bin/gh but has no credentials (~/.config/gh/ is absent).</what-built>
  <how-to-verify>
    Run the following in a terminal:

      gh auth login

    Choose "GitHub.com", then "SSH" or "HTTPS" as the protocol, then authenticate
    via browser or paste a Personal Access Token. Scopes needed: `repo`, `read:org`.

    Confirm success:

      gh auth status

    Expected output: "Logged in to github.com as <your-username>"
  </how-to-verify>
  <resume-signal>Type "authenticated" once `gh auth status` shows you are logged in.</resume-signal>
</task>

<task type="auto">
  <name>Task 2: Fetch open PRs and evaluate Copilot comments</name>
  <files>No files modified — read-only evaluation.</files>
  <action>
    There are three known Copilot branches in the remote:
      - origin/copilot/create-pull-request-from-docker-build
      - origin/copilot/describe-activity-activities
      - origin/copilot/update-documentation-for-iceberg-rest-catalog

    Run the following to list open PRs and confirm their numbers:

      gh pr list --repo devais-eng/dremio-oss --state open --json number,title,headRefName,url

    For each open PR found, fetch its review comments and inline code comments:

      gh pr view <number> --repo devais-eng/dremio-oss --comments
      gh api repos/devais-eng/dremio-oss/pulls/<number>/comments

    For each comment from Copilot (author login contains "copilot" or "github-actions"):
      1. Read the comment body in full.
      2. Identify which file and line it refers to (if inline).
      3. Evaluate:
         - ACTIONABLE: Comment identifies a real bug, security issue, or meaningful
           improvement aligned with the project's Java/Dremio patterns. Note the
           required change.
         - INFORMATIONAL: Comment explains behavior correctly but no change is needed
           (e.g., confirms code is correct, explains a tradeoff already decided).
         - IGNORABLE: Comment is incorrect, based on misunderstanding the codebase,
           or refers to patterns not applicable here (e.g., suggests test frameworks
           not used in this project, flags non-issues).

    For each PR, also note:
      - Is the PR already merged? If so, skip — changes are already in master.
      - Is the PR still open and targeting a branch that has since diverged from master?
        Note if the branch content is already included in the rbac branch.

    Print a structured evaluation to the console in this format:

      ## PR #<N> — <title>
      Branch: <headRefName>
      Status: open / merged / already-in-rbac

      ### Comment by <author> on <file>:<line>
      Summary: <one sentence of what the comment says>
      Verdict: ACTIONABLE | INFORMATIONAL | IGNORABLE
      Reason: <why>
      Action required: <what to do, or "none">

    After all PRs, print a summary section:

      ## Summary
      - Actionable: <N> comments requiring follow-up
      - Informational: <N> comments, no action
      - Ignorable: <N> comments, dismissed

      ### Action Items
      - [ ] <description of each actionable change, with PR and file reference>
  </action>
  <verify>
    All three Copilot branch PRs are covered in the output. Every Copilot comment has a
    verdict (ACTIONABLE / INFORMATIONAL / IGNORABLE) with a stated reason.
  </verify>
  <done>
    A complete evaluation is printed to the console. The "Action Items" section lists
    every actionable comment as a checkbox. The user can decide which items to address
    in a follow-up quick task or milestone.
  </done>
</task>

</tasks>

<verification>
After Task 2 completes, the user reviews the printed evaluation and confirms:
- All open PRs were found and evaluated.
- The action items list is complete and accurate.
</verification>

<success_criteria>
Every Copilot PR comment has a verdict. Actionable items are listed explicitly so
nothing falls through the cracks.
</success_criteria>

<output>
No SUMMARY.md required for this read-only evaluation task. The output is printed to the
console and reviewed interactively.

If actionable items are found, create a follow-up quick task via `/gsd:quick` describing
the specific changes to make.
</output>
