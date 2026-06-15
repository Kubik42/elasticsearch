#!/usr/bin/env bash
# Step 3: agent-driven fix + verification, gated by a mechanical floor, then a draft PR. The deterministic steps
# (discover, reproduce) decided WHAT and proved it reproduces; from here judgment is required, so two independent
# agents drive it, each instructed by its own .md (fix-instructions.md / verify-instructions.md):
#   1. FIX agent    - edits the tree to fix the reproduced failure at its root.
#   2. Mechanical floor (no judgment, never skipped): diff_guard.py (anti-cheat) + verify.sh (the recorded
#      reproduction command must now exit 0 on base+patch, plus a collateral project-task run).
#   3. VERIFY agent - a SEPARATE, fresh-context, read-only agent that did not see the fix's reasoning. It builds on the
#      floor and judges what the machine cannot: root-cause vs masking, completeness, scope, correct-when-enabled.
# Two terminal outcomes:
#   FIXED   -> floor passed AND the verify agent returned PASS; a draft PR is opened.
#   GAVE_UP -> budget exhausted still-red, the floor tripped, or the verify agent returned FAIL; comment, stay assigned.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REPRO_JSON="${1:-${AUTOFIX_WORK}/repro.json}"
[[ -f "${REPRO_JSON}" ]] || die "no repro.json at ${REPRO_JSON}"

NUMBER="$(jq -r '.issue_number' "${REPRO_JSON}")"
BASE_SHA="$(jq -r '.base_sha' "${REPRO_JSON}")"
TEST_CLASS="$(jq -r '.test_class' "${REPRO_JSON}")"
TEST_METHOD="$(jq -r '.test_method' "${REPRO_JSON}")"
UNMUTED="$(jq -r '.unmuted' "${REPRO_JSON}")"
MUTED="${AUTOFIX_TREE}/muted-tests.yml"
PATCH="${AUTOFIX_WORK}/fix.patch"
BASELINE_STATUS="${AUTOFIX_WORK}/baseline.status"
CURRENT_STATUS="${AUTOFIX_WORK}/current.status"

# Budgets (configured in config.env): wall-clock cap (timeout) plus a dollar cap, for each agent. Both keep step 3
# inside the 90-min job timeout. Local aliases below keep the rest of the script terse.
FIX_BUDGET_SECONDS="${AUTOFIX_FIX_BUDGET_SECONDS}"
FIX_BUDGET_USD="${AUTOFIX_FIX_BUDGET_USD}"
VERIFY_BUDGET_SECONDS="${AUTOFIX_VERIFY_BUDGET_SECONDS}"
VERIFY_BUDGET_USD="${AUTOFIX_VERIFY_BUDGET_USD}"

# Operate in the dedicated worktree reproduce.sh prepared (pinned to upstream main at BASE_SHA), never the user's main
# checkout. Both agents are launched from here, so their Edits land in the worktree and `Bash(./gradlew *)` is its gradlew.
cd "${AUTOFIX_TREE}"

# Default-deny tool policy for both nested agents (--permission-mode dontAsk): anything NOT explicitly allowed below is
# SILENTLY denied - no network, no Write (new files are out of scope), no git push / gh, no rm. Read-only shell builtins
# (ls, grep, cat, find, cd) still auto-run, but the Read/Grep/Glob TOOLS and every gradlew/git invocation must be listed.
# gradlew is the only build/exec the agents need; the git allow-list is read-only (diff/show/log/status) so they can
# inspect the patch and history but never mutate refs. This replaces --dangerously-skip-permissions, under which
# --allowedTools is a no-op (it approves everything but explicit denials); dontAsk is what makes the allow-list binding.
# Each agent is launched with cwd = the worktree but `--add-dir "${AUTOFIX_WORK}"`, so it can Read repro.json / the patch
# / diagnostics that live under the main checkout's run/ dir while still editing (and building) only inside the worktree.
COMMON_ALLOW=(
  Read Grep Glob TodoWrite
  "Bash(./gradlew *)"
  "Bash(git diff:*)" "Bash(git show:*)" "Bash(git log:*)" "Bash(git status:*)"
)
FIX_ALLOW=("${COMMON_ALLOW[@]}" Edit)   # the FIX agent may modify EXISTING files (Edit); Write/NotebookEdit stay denied
VERIFY_ALLOW=("${COMMON_ALLOW[@]}")     # the VERIFY agent is strictly read-only: inspect the tree + re-run gradle, nothing else

gave_up() {
  emit outcome GAVE_UP
  local cf="${AUTOFIX_WORK}/giveup.md"
  printf '**Auto-fix step 3 — GAVE_UP**\n\n%s\n' "$1" > "${cf}"
  # Non-fatal: a failed comment (e.g. the issue lives in a different repo than the PR target) must not mask the outcome.
  issue_comment "${NUMBER}" "${cf}" || log "WARNING: could not post GAVE_UP comment to ${GH_REPO}#${NUMBER} (continuing)"
  log "step 3 outcome: GAVE_UP — $1"
  maybe_cleanup_run_tree   # pipeline terminal; no-op unless AUTOFIX_KEEP_TREE=0
  exit 0
}

# ---- 1. Ensure base, re-apply the unmute so the agent iterates against an UNMUTED test -------------------------
[[ "$(git rev-parse HEAD)" == "${BASE_SHA}" ]] || log "WARNING: HEAD is not the recorded base ${BASE_SHA}"
if [[ "${UNMUTED}" == "true" ]]; then
  # Family unmute (class, bare-method): a single residual sibling mute re-adds the bare-method exclusion and would
  # hide the target test from the agent, so clear the whole family - exactly what step 2 reproduced against.
  mapfile -t REUNMUTED < <(python3 "${AUTOFIX_DIR}/unmute.py" "${MUTED}" --family "${TEST_CLASS}" "${TEST_METHOD}")
  log "re-applied family unmute (${#REUNMUTED[@]} entr(y/ies) [#${REUNMUTED[*]}]); the fix removes the mutes permanently"
fi

# ---- 2. Baseline snapshot (powers the new-untracked-file guard) ------------------------------------------------
git status --porcelain --untracked-files=all > "${BASELINE_STATUS}"

# ---- 3. Build the agent prompt: per-issue facts here, the authoritative rules + known patterns appended from the
# shared instructions file (single source of truth, kept out of this heredoc to avoid drift). The untrusted issue body
# never enters the prompt — only file paths and the validated reproduction command do.
# Prefer the command that ACTUALLY reproduced (suite-level or snapshot-flipped), not the issue's narrow line.
mapfile -t ARGV < <(jq -r 'if (.reproduction_command // [] | length) > 0 then .reproduction_command[] else .argv[] end' "${REPRO_JSON}")
CMD_STR="$(printf '%q ' "${ARGV[@]}")"
ROOT_CAUSE="$(jq -r '.root_cause.message // empty' "${REPRO_JSON}")"
REPRO_NOTE="$(jq -r '.reproduction_steps // empty' "${REPRO_JSON}")"
INSTRUCTIONS="${AUTOFIX_DIR}/fix-instructions.md"
PROMPT="${AUTOFIX_WORK}/agent-prompt.txt"
cat > "${PROMPT}" <<EOF
You are fixing a failing Elasticsearch test. Reproduction details: ${REPRO_JSON}. Diagnostics (cluster logs, JUnit
XML, root-cause hint) are under $(jq -r '.diagnostics_path' "${REPRO_JSON}").

Failing test: ${TEST_CLASS}.${TEST_METHOD}
$( [[ -n "${ROOT_CAUSE}" ]] && echo "Observed root cause: ${ROOT_CAUSE}" )
Reproduce it (deterministic, with the original seed) by running, from the repo root:
  ${CMD_STR}
$( [[ -n "${REPRO_NOTE}" ]] && echo "How it reproduces (read carefully — it may differ from the issue's own command): ${REPRO_NOTE}" )

Your goal: make that exact test pass by fixing the ROOT CAUSE, then briefly summarise the root cause and the fix.
$( [[ "${UNMUTED}" == "true" ]] && echo "This test's muted-tests.yml entry was removed so it runs; leave it removed (the fix keeps the test live)." )

Follow ALL rules below, and apply any matching known-failure pattern. These instructions are authoritative.

==================== AGENT INSTRUCTIONS ====================
EOF
cat "${INSTRUCTIONS}" >> "${PROMPT}"

# ---- 4. Run the agent loop (wall-clock + dollar budget; modify-only enforced by withholding the Write tool) -----
log "running agent (budget ${FIX_BUDGET_SECONDS}s / \$${FIX_BUDGET_USD})"
AGENT_LOG="${AUTOFIX_WORK}/agent.log"
model_args=(); [[ -n "${AUTOFIX_FIX_MODEL}" ]] && model_args=(--model "${AUTOFIX_FIX_MODEL}")
set +e
timeout "${FIX_BUDGET_SECONDS}" claude -p "$(cat "${PROMPT}")" \
  --permission-mode dontAsk \
  --add-dir "${AUTOFIX_WORK}" \
  --allowedTools "${FIX_ALLOW[@]}" \
  --disallowedTools "Write" "NotebookEdit" \
  --max-budget-usd "${FIX_BUDGET_USD}" \
  --output-format text "${model_args[@]}" > "${AGENT_LOG}" 2>&1
agent_rc=$?
set -e
log "agent exited rc=${agent_rc}"

# Capture the agent's PR-facing summary (single-line markers, same convention as the verify verdict). open_pr.sh turns
# these into the "What" (the observable error) and "Root cause" (the underlying bug) sections of the PR description.
FIX_ERROR="$(grep -oE 'AUTOFIX_ERROR=.+' "${AGENT_LOG}" | tail -1 | cut -d= -f2-)"
FIX_ROOT_CAUSE="$(grep -oE 'AUTOFIX_ROOT_CAUSE=.+' "${AGENT_LOG}" | tail -1 | cut -d= -f2-)"
jq -n --arg error "${FIX_ERROR}" --arg root_cause "${FIX_ROOT_CAUSE}" \
  '{error: $error, root_cause: $root_cause}' > "${AUTOFIX_WORK}/fix-summary.json"
log "captured fix summary (error=$([[ -n "${FIX_ERROR}" ]] && echo set || echo MISSING), root_cause=$([[ -n "${FIX_ROOT_CAUSE}" ]] && echo set || echo MISSING))"

# ---- 5. Capture the tracked diff = the patch -------------------------------------------------------------------
git diff > "${PATCH}"
git status --porcelain --untracked-files=all > "${CURRENT_STATUS}"

# ---- 6. Mechanical floor, part 1: diff guard (no judgment, never skipped) --------------------------------------
if ! python3 "${AUTOFIX_DIR}/diff_guard.py" "${PATCH}" "${BASELINE_STATUS}" "${CURRENT_STATUS}" > "${AUTOFIX_WORK}/guard.json"; then
  gave_up "Diff guard tripped: $(jq -c '.violations' "${AUTOFIX_WORK}/guard.json")"
fi
log "mechanical floor: diff guard clean"

# ---- 7. Mechanical floor, part 2: the reproduction command no longer reproduces (+ collateral) -----------------
# verify.sh reapplies ONLY the patch to a clean base and re-runs the recorded reproduction command (mode-agnostic:
# it must now exit 0) plus the project task. This is the objective gate; the verify AGENT below adds judgment on top.
if ! bash "${AUTOFIX_DIR}/verify.sh" "${REPRO_JSON}" "${PATCH}"; then
  gave_up "Mechanical floor failed: the reproduction command still fails or a collateral test broke (see ${AUTOFIX_WORK}/verify.log)."
fi
log "mechanical floor: reproduction command resolved"

# ---- 8. Independent VERIFY agent (judgment gate) ---------------------------------------------------------------
# A separate, fresh-context, read-only agent that never saw the fix's reasoning - only the patch on the base+patch
# tree. It judges what the floor cannot (root-cause vs masking, completeness across siblings, scope, gated-correct).
# It ends its output with AUTOFIX_VERDICT=PASS|FAIL; anything other than a clean PASS is treated as a rejection.
VERIFY_INSTRUCTIONS="${AUTOFIX_DIR}/verify-instructions.md"
VERIFY_PROMPT="${AUTOFIX_WORK}/verify-prompt.txt"
cat > "${VERIFY_PROMPT}" <<EOF
You are independently verifying an automated fix for a failing Elasticsearch test. The base is checked out with ONLY
the patch under review applied, so the working tree is exactly what the PR would contain.

Patch under review: ${PATCH}
Reproduction details (identity, root cause, the exact reproduction_command, clustered siblings): ${REPRO_JSON}
Diagnostics (cluster logs, JUnit XML, root-cause hint): $(jq -r '.diagnostics_path' "${REPRO_JSON}")
Mechanical-floor log (diff guard + reproduction command re-run, both already PASSED): ${AUTOFIX_WORK}/verify.log

Failing test under review: ${TEST_CLASS}.${TEST_METHOD}
$( [[ -n "${ROOT_CAUSE}" ]] && echo "Observed root cause: ${ROOT_CAUSE}" )

Judge whether this patch fixes the failure at its root, completely, and without scope creep, then emit your verdict as
instructed below. These instructions are authoritative.

==================== VERIFICATION INSTRUCTIONS ====================
EOF
cat "${VERIFY_INSTRUCTIONS}" >> "${VERIFY_PROMPT}"

log "running verify agent (budget ${VERIFY_BUDGET_SECONDS}s / \$${VERIFY_BUDGET_USD})"
VERIFY_AGENT_LOG="${AUTOFIX_WORK}/verify-agent.log"
set +e
timeout "${VERIFY_BUDGET_SECONDS}" claude -p "$(cat "${VERIFY_PROMPT}")" \
  --permission-mode dontAsk \
  --add-dir "${AUTOFIX_WORK}" \
  --allowedTools "${VERIFY_ALLOW[@]}" \
  --disallowedTools "Edit" "Write" "NotebookEdit" \
  --max-budget-usd "${VERIFY_BUDGET_USD}" \
  --output-format text "${model_args[@]}" > "${VERIFY_AGENT_LOG}" 2>&1
verify_rc=$?
set -e
log "verify agent exited rc=${verify_rc}"
VERDICT="$(grep -oE 'AUTOFIX_VERDICT=(PASS|FAIL)' "${VERIFY_AGENT_LOG}" | tail -1 | cut -d= -f2)"
if [[ "${VERDICT}" != "PASS" ]]; then
  gave_up "Independent verify agent did not pass (verdict='${VERDICT:-none}', rc=${verify_rc}; see ${VERIFY_AGENT_LOG})."
fi
log "verify agent verdict: PASS"

# ---- 9. Open the draft PR (FIXED) ------------------------------------------------------------------------------
bash "${AUTOFIX_DIR}/open_pr.sh" "${REPRO_JSON}" "${PATCH}"
emit outcome FIXED
log "step 3 outcome: FIXED"
maybe_cleanup_run_tree   # PR already built from its own worktree at BASE_SHA; no-op unless AUTOFIX_KEEP_TREE=0
