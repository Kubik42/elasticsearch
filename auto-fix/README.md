# auto-fix prototype

Automated test-failure fixer for `elastic/elasticsearch`. Finds an unassigned `>test-failure` + `Team:StorageEngine`
issue, reproduces it against base `main`, and — only if reproduced — drives an agent to fix it and opens a **draft** PR.

Design is in the repo-root plans: `auto-fix-discovery-plan.md`, `auto-fix-reproduce-plan.md`, `auto-fix-fix-plan.md`.

## Pieces

| File | Role |
|---|---|
| `config.env` | Single configuration file: every tunable (target repo, identity, PR/push targets, budgets, caps) as plain `KEY=value` data. Loaded by `lib.sh`. |
| `auto-fix-test-failures.yml` | GitHub Actions workflow wiring the three steps (copy to `.github/workflows/` to run). |
| `discover.sh` | Step 1: query, pick the newest issue, cluster siblings sharing its fix locus, claim all, write `run/issue.json`. |
| `parse_repro.py` | Step 2 security boundary: parse the issue body into a validated, exec-ready `argv` (allow-list, no shell); reconcile the test name against `muted-tests.yml`. |
| `reproduce.sh` | Step 2 driver: validate → env pre-flight → unmute-first → ordered cache-busted attempts (narrow → class-level leak probe → snapshot-flip) → classify → diagnostics → `run/repro.json`. |
| `classify.py` | Step 2: REPRODUCED / NOT_REPRODUCED / NOT_FOUND strictly from the JUnit XML of the named test. |
| `unmute.py` | Remove a `muted-tests.yml` entry by issue number (step 2 unmutes to reproduce; step 3 removes it permanently). |
| `fix.sh` | Step 3 orchestrator: FIX agent → mechanical floor (diff guard + verify.sh) → independent VERIFY agent → PR. |
| `fix-instructions.md` | Instructions for the FIX agent: hard rules, ES domain knowledge, known failure patterns. Appended to its prompt. |
| `verify-instructions.md` | Instructions for the independent VERIFY agent: judge root-cause/completeness/scope above the mechanical floor; emit `AUTOFIX_VERDICT=PASS\|FAIL`. |
| `diff_guard.py` | Mechanical floor (anti-cheat + scope): fail-closed guard against degenerate "greens" (re-mute, `@AwaitsFix`, `assume*`, assertion deletion, new files) and against an over-large fix (>20 changed lines excluding `muted-tests.yml`). |
| `verify.sh` | Mechanical floor (objective): reapply only the captured patch to base, require the recorded reproduction command to now exit 0 (Goal 1), project task green (Goal 2). |
| `open_pr.sh` | Step 3: commit the fix in an isolated worktree at base SHA, push the branch to the fork, open a draft PR on upstream (cross-repo). |
| `lib.sh` | Shared helpers (repo root, work dir, dry-run guard, issue comments) + the run-worktree lifecycle (`ensure_run_tree` / `maybe_cleanup_run_tree`). |

## Run locally

```bash
# End-to-end, no GitHub mutations (no assign/comment/PR):
AUTOFIX_DRY_RUN=1 bash auto-fix/discover.sh
AUTOFIX_DRY_RUN=1 bash auto-fix/reproduce.sh
AUTOFIX_DRY_RUN=1 bash auto-fix/fix.sh
```

Artifacts land in `auto-fix/run/` (gitignored by the prototype's own dir exclusion in `diff_guard.py`). The first run
creates the dedicated worktree at `AUTOFIX_TREE` (a sibling of the repo, default `<repo>-autofix-worktree`) and reuses it
on later runs; your main checkout is never modified, so these commands are safe to run with uncommitted work in progress.

## Environment knobs

All defaults live in one place — `config.env` (plain `KEY=value`). Edit it to change a default permanently, or override
any knob for a single run via the environment (`VAR=... bash auto-fix/<step>.sh`); an environment value always wins.

| Var | Default | Effect |
|---|---|---|
| `AUTOFIX_DRY_RUN` | `0` | `1` suppresses every GitHub mutation (assign, comment, branch push, PR). |
| `AUTOFIX_UPSTREAM_REMOTE` / `AUTOFIX_UPSTREAM_BRANCH` | `elasticsearch_main` / `main` | The remote/branch that tracks `elastic/elasticsearch`; the run worktree is fetched from and reset to `<remote>/<branch>` each run. |
| `AUTOFIX_TREE` | _(sibling of repo)_ | The dedicated git worktree the whole pipeline operates in. Empty → `<repo>-autofix-worktree`. Your main checkout is never touched. |
| `AUTOFIX_KEEP_TREE` | `1` | Keep the worktree between runs for a warm Gradle cache. `0` removes it (`git worktree remove`) at each pipeline-terminal outcome. |
| `AUTOFIX_RUN_BRANCH` | `auto-fix/working-base` | The branch the worktree is created/reset to upstream on (`checkout -f -B`), so reruns reset rather than accumulate. |
| `AUTOFIX_SKIP_JAVA_GATE` | `0` | `1` skips the `-Druntime.java=N` → `JAVA${N}_HOME` pre-flight. |
| `AUTOFIX_SKIP_GOAL2` | `0` | `1` skips the step-3 full-project collateral run (Goal 1 still gates). |
| `AUTOFIX_FIX_BUDGET_SECONDS` / `AUTOFIX_FIX_BUDGET_USD` | `3600` / `20` | Wall-clock and dollar caps on the FIX agent. |
| `AUTOFIX_VERIFY_BUDGET_SECONDS` / `AUTOFIX_VERIFY_BUDGET_USD` | `1800` / `10` | Wall-clock and dollar caps on the VERIFY agent. |
| `AUTOFIX_FIX_MODEL` | _(unset)_ | Override the `claude` model for both agents (no flag → CLI default). |
| `GH_REPO` / `ASSIGNEE` | `elastic/elasticsearch` / `Kubik42` | Repo the PR is opened on + issue comments target, and the claim/assignee identity. |
| `AUTOFIX_PUSH_REMOTE` | `fork` | Git remote the fix branch is pushed to. Defaults to the contributor's fork for the cross-repo PR onto `GH_REPO`. |
| `AUTOFIX_PR_HEAD_OWNER` | `$ASSIGNEE` | Fork owner for the cross-repo head `<owner>:<branch>`. Set `""` for a same-repo PR (branch lives in `GH_REPO`). |
| `AUTOFIX_PR_BASE` | `main` | PR base branch (overridden when `AUTOFIX_PUSH_BASE=1`). |
| `AUTOFIX_PUSH_BASE` | `0` | `1` pushes `BASE_SHA` to a dedicated base branch and targets it, pinning the PR diff to exactly the fix. SAME-REPO only (the base branch is pushed to `AUTOFIX_PUSH_REMOTE`); incompatible with the default cross-repo PR onto upstream. |
| `AUTOFIX_PR_LABELS` | `Team:StorageEngine,>bug` | Comma-separated PR labels (must exist in the target repo); set empty on a fork. |
| `AUTOFIX_MAX_FIX_LINES` | `20` | Max changed lines (added + removed, excluding `muted-tests.yml`) a fix may contain; a larger diff trips the floor → GAVE_UP. |

## Prototype caveats (see plans for full deferral list)

- The whole pipeline runs inside a dedicated `git worktree` (`AUTOFIX_TREE`, a sibling of the repo) pinned to upstream
  main — your main checkout is never touched, so you can keep working (and building) while a run is in flight, and the
  captured fix patch is exactly the agent's delta. The worktree is reused across runs to keep the Gradle cache warm
  (`AUTOFIX_KEEP_TREE=0` to remove it). Cost: a second full checkout plus its own multi-GB `build/`. The nested fix/verify
  agents run from inside the worktree under `--permission-mode dontAsk` with an explicit allow-list (gradlew + read-only
  git + Read/Grep/Glob, plus `--add-dir` to read run artifacts; the fix agent also gets Edit), so anything off-list —
  network, Write, git push, rm — is denied rather than approved.
- A test excluded by a `build.gradle` `excludeTestsMatching` filter reports "No tests found"; this is often a
  feature-flag/snapshot-only gate. `reproduce.sh` handles reproducing it (class-level filter / snapshot flip) and
  `fix-instructions.md` documents how the fix agent resolves the common leaky-exclusion pattern.
- Fixes that genuinely require a **new file** are out of scope (modify-only) → GAVE_UP.
- The fix is intentionally bounded: at most 20 changed lines (excluding `muted-tests.yml`), expected in test/build files
  and only rarely in production code. A larger or production-heavy fix is treated as a deeper issue needing human input
  → GAVE_UP (raise `AUTOFIX_MAX_FIX_LINES` only with care).
- Step 2 runs the reproduction once with the seed; the `-Dtests.iters=100` flakiness phase is deferred.
