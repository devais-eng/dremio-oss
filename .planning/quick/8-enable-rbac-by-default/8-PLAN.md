---
phase: quick-8
plan: 8
type: execute
wave: 1
depends_on: []
files_modified:
  - common/legacy/src/main/resources/dremio-reference.conf
autonomous: true
requirements:
  - RBAC-DEFAULT-ON
must_haves:
  truths:
    - "RBAC enforcement is active by default when Dremio starts without custom config overrides"
    - "PDS SELECT enforcement is active by default alongside RBAC"
    - "A fresh deployment enforces role-based access without any operator opt-in step"
  artifacts:
    - path: "common/legacy/src/main/resources/dremio-reference.conf"
      provides: "Default RBAC enabled flags"
      contains: "enabled: true"
  key_links:
    - from: "common/legacy/src/main/resources/dremio-reference.conf"
      to: "DremioConfig.RBAC_ENABLED (services.rbac.enabled)"
      via: "Typesafe Config resolution: dremio-reference.conf is the lowest-priority default"
      pattern: "rbac.*enabled.*true"
---

<objective>
Enable RBAC (and PDS SELECT enforcement) by default in the reference configuration.

Purpose: The RBAC system is complete and production-ready. Keeping it opt-in means a fresh OSS
deployment ships with no access control. Flipping both flags to `true` makes RBAC on by default;
operators who want the old permissive behaviour can still override in their dremio.conf.

Output: `dremio-reference.conf` with `services.rbac.enabled = true` and
`services.rbac.pds.enabled = true`.
</objective>

<execution_context>
@/home/emanuele/.claude/get-shit-done/workflows/execute-plan.md
@/home/emanuele/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
</context>

<tasks>

<task type="auto">
  <name>Task 1: Flip RBAC defaults to true in dremio-reference.conf</name>
  <files>common/legacy/src/main/resources/dremio-reference.conf</files>
  <action>
    In `common/legacy/src/main/resources/dremio-reference.conf`, locate the `services.rbac` block
    (around line 351). It currently reads:

      rbac: {
        enabled: false  # RBAC enforcement; requires coordinator restart to change
        pds: {
          enabled: false  # PDS SELECT enforcement (opt-in). Only active when rbac.enabled is also true.
        }
      }

    Change both `false` values to `true`. Update the comment on `pds.enabled` to reflect it is now
    on by default (remove the "(opt-in)" qualifier). Leave all other lines unchanged.

    Final expected block:

      rbac: {
        enabled: true  # RBAC enforcement; requires coordinator restart to change
        pds: {
          enabled: true  # PDS SELECT enforcement. Only active when rbac.enabled is also true.
        }
      }

    Do NOT touch sabot-module.conf — it does not contain RBAC enabled flags, only the package scan
    entry for `com.dremio.exec.rbac` which must stay as-is.
  </action>
  <verify>
    grep -n "rbac" common/legacy/src/main/resources/dremio-reference.conf

    Expected output shows both flags as `true`:
      351:  rbac: {
      352:    enabled: true  # RBAC enforcement; requires coordinator restart to change
      353:    pds: {
      354:      enabled: true  # PDS SELECT enforcement. Only active when rbac.enabled is also true.
      355:    }
      356:  }
  </verify>
  <done>
    Both `services.rbac.enabled` and `services.rbac.pds.enabled` are `true` in
    `dremio-reference.conf`. No other lines in the file are modified.
  </done>
</task>

</tasks>

<verification>
grep -A 5 "rbac:" common/legacy/src/main/resources/dremio-reference.conf

Both flags must show `true`. Confirm no unintended whitespace or formatting changes were introduced
by running: git diff common/legacy/src/main/resources/dremio-reference.conf
</verification>

<success_criteria>
- `services.rbac.enabled` defaults to `true`
- `services.rbac.pds.enabled` defaults to `true`
- `git diff` shows exactly 2 lines changed (the two `false` -> `true` replacements, and the comment tweak)
- All existing tests that explicitly set `rbac.enabled = true` in test config continue to pass (no change to their behaviour)
- Tests that relied on `rbac.enabled` defaulting to `false` will need their test config updated if they exist (check with: grep -r "rbac.enabled" dac/backend/src/test --include="*.java" -l)
</success_criteria>

<output>
After completion, create `.planning/quick/8-enable-rbac-by-default/8-SUMMARY.md` following the
standard summary template.
</output>
