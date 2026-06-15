#!/usr/bin/env bash
# Step 3 independent verification: prove that the CAPTURED PATCH (not the agent's possibly-richer workspace) resolves
# the failure from a clean base, and introduces no collateral failure in the same project. The agent reached green
# itself, so its self-report is not trusted; this re-derives green from exactly what the PR will contain.
#
# Design: verification is MODE-AGNOSTIC on purpose. It does not know or care WHY the test failed (plain bug, leak past
# an excludeTestsMatching filter, feature-flag/snapshot gating, ...). It simply re-runs the EXACT command that
# reproduced the failure and requires it to now succeed. The mode-appropriate command already lives in repro.json's
# reproduction_command (reproduce.sh chose narrow / class-level / snapshot and recorded it), and the "was green reached
# honestly" question belongs to diff_guard.py (re-mute, @AwaitsFix, assume*, assertion/test deletion, new files). That
# separation keeps this script general: new failure modes need changes in reproduce.sh, never here.
#   Goal 1 (hard gate): the reproduction command no longer reproduces the failure (exits 0). Red -> exit 1 (GAVE_UP).
#   Goal 2 (collateral): the run-project's full test task is green. New failure -> exit 1. Full-repo regression is
#                        delegated to the draft PR's CI and never run here.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REPRO_JSON="$1"
PATCH="$2"
VERIFY_LOG="${AUTOFIX_WORK}/verify.log"
: > "${VERIFY_LOG}"

TASK_PATH="$(jq -r '.task_path' "${REPRO_JSON}")"
# Verify with the command that ACTUALLY reproduced (narrow / class-level / snapshot-flipped, already cache-busted), not
# the issue's narrow line - the recorded reproduction command is the one demonstrated to expose this exact failure.
mapfile -t REPRO_ARGV < <(jq -r 'if (.reproduction_command // [] | length) > 0 then .reproduction_command[] else .argv[] end' "${REPRO_JSON}")

# Operate in the dedicated worktree (pinned to upstream main at BASE_SHA), never the user's main checkout.
cd "${AUTOFIX_TREE}"

# ---- Reset tracked changes to base, then reapply ONLY the captured patch ----------------------------------------
mapfile -t CHANGED < <(git diff --name-only)
if [[ "${#CHANGED[@]}" -gt 0 ]]; then
  git checkout -- "${CHANGED[@]}"
fi
if ! git apply --check "${PATCH}" 2>>"${VERIFY_LOG}"; then
  log "patch does not apply cleanly to base"; exit 1
fi
git apply "${PATCH}"
log "reapplied patch onto clean base"

./gradlew clean >/dev/null 2>&1 || true

# ---- Goal 1: the reproduction command no longer reproduces the failure -----------------------------------------
# Mode-agnostic: re-run the exact command that reproduced the failure and require it to now SUCCEED. We do not inspect
# why it failed or special-case the failure mode - "the thing that reproduced it no longer does" is the general signal.
# Gradle fails a --tests filter that matches nothing, so a deleted/renamed test cannot masquerade as success here, and
# diff_guard.py independently blocks the degenerate ways to force a green (re-mute, @AwaitsFix, assume*, deletions).
log "Goal 1: the reproduction command must no longer reproduce the failure"
set +e
"${REPRO_ARGV[@]}" >>"${VERIFY_LOG}" 2>&1
g1_rc=$?
set -e
if [[ "${g1_rc}" -ne 0 ]]; then
  log "Goal 1 failed: the reproduction command still fails (exit ${g1_rc}); the failure is not resolved"; exit 1
fi
log "Goal 1 passed: the reproduction command is green"

# ---- Goal 2: the project's full test task is green (bounded collateral check) -----------------------------------
if [[ "${AUTOFIX_SKIP_GOAL2}" == "1" ]]; then
  log "Goal 2 skipped (AUTOFIX_SKIP_GOAL2=1)"; exit 0
fi
# Same gated flags + locale/timezone, but without the seed/iters/--tests filter, to exercise the whole suite once.
# Cache-busting is mandatory here too: a stale cached task result (build cache is not cleared by `gradle clean`) would
# make this collateral check pass without actually running the suite against the patched tree.
GOAL2_ARGV=("./gradlew" "${TASK_PATH}" "--rerun-tasks" "--no-build-cache" "--no-configuration-cache")
while IFS= read -r kv; do GOAL2_ARGV+=("-D${kv}"); done < <(jq -r '.gated_flags | to_entries[] | "\(.key)=\(.value)"' "${REPRO_JSON}")
while IFS= read -r kv; do GOAL2_ARGV+=("-D${kv}"); done < <(jq -r '.passthrough_flags | to_entries[] | select(.key=="tests.locale" or .key=="tests.timezone") | "\(.key)=\(.value)"' "${REPRO_JSON}")
log "Goal 2: running project task: ${GOAL2_ARGV[*]}"
set +e
"${GOAL2_ARGV[@]}" >>"${VERIFY_LOG}" 2>&1
g2_rc=$?
set -e
if [[ "${g2_rc}" -ne 0 ]]; then
  log "Goal 2 failed: project task exited ${g2_rc} (fix introduced a collateral failure)"; exit 1
fi
log "Goal 2 passed: project task is green"
exit 0
