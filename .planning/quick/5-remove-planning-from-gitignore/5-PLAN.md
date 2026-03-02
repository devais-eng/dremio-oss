---
plan: 5
title: remove ./planning from .gitignore
quick: true
---

## Goal

Untrack `.planning/` from the `.gitignore` rule `.*/` so the planning directory can be committed normally.

## Context

`.gitignore` line 2 has `.*/` which ignores all hidden directories. `.planning` is caught by this rule. The fix is to add `!.planning/` exception alongside existing `!.mvn/` and `!.github/` exceptions.

## Tasks

### Task 1 — Add !.planning/ exception to .gitignore

**File:** `.gitignore`
**Action:** Insert `!.planning/` after `!.github/` on line 4.
**Verify:** `git check-ignore .planning` returns exit code 1 (not ignored).
**Done:** `.planning/` files can be staged without `-f`.

### Task 2 — Stage and commit existing .planning/ files

**Action:** `git add -f .planning/` to bring all planning files into the index, then commit.
**Verify:** `git ls-files .planning/ | wc -l` > 0.
**Done:** `.planning/` tracked in git history.
