#!/usr/bin/env bash
# Step 3 PR creation: open a DRAFT PR containing exactly the captured patch. A dedicated git worktree at the base SHA
# isolates this from the user's real working tree and branch, so the commit holds only the fix delta (the worktree is
# clean at base, so `git add -A` after applying the patch stages precisely the changed files - never user untracked
# files). The PR is draft-only on purpose: a human + CI are the real quality/regression backstop.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Absolutize the inputs up front: this script cd's into a throwaway worktree before applying the patch, so a relative
# path passed by a caller (or on the command line) would no longer resolve from there.
abspath() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$(pwd)" "$1" ;; esac; }
REPRO_JSON="$(abspath "$1")"
PATCH="$(abspath "$2")"

NUMBER="$(jq -r '.issue_number' "${REPRO_JSON}")"
BASE_SHA="$(jq -r '.base_sha' "${REPRO_JSON}")"
TEST_CLASS="$(jq -r '.test_class' "${REPRO_JSON}")"
SHORT_CLASS="${TEST_CLASS##*.}"
TEST_METHOD="$(jq -r '.test_method' "${REPRO_JSON}")"
UNMUTED="$(jq -r '.unmuted' "${REPRO_JSON}")"

# The PR's "What" (the observable error) and "Root cause" (the underlying bug) come from the fix agent's structured
# summary. If that summary is absent (e.g. open_pr.sh run standalone, or an older run), fall back to the parsed
# exception for the error and a pointer to the diff for the cause, so the body is always coherent.
SUMMARY_JSON="${AUTOFIX_WORK}/fix-summary.json"
ERROR_TXT=""; ROOT_CAUSE_TXT=""
if [[ -f "${SUMMARY_JSON}" ]]; then
  ERROR_TXT="$(jq -r '.error // empty' "${SUMMARY_JSON}")"
  ROOT_CAUSE_TXT="$(jq -r '.root_cause // empty' "${SUMMARY_JSON}")"
fi
EXC_MSG="$(jq -r '.root_cause.message // empty' "${REPRO_JSON}")"
[[ -n "${ERROR_TXT}" ]] || ERROR_TXT="The test \`${TEST_CLASS}.${TEST_METHOD}\` failed${EXC_MSG:+ with: ${EXC_MSG}}."
[[ -n "${ROOT_CAUSE_TXT}" ]] || ROOT_CAUSE_TXT="See the diff and the linked issue; no structured root-cause summary was captured for this run."

# The full set of issues this one PR closes: the primary plus any siblings the discovery step clustered by fix locus.
mapfile -t CLOSES < <(jq -r '[.issue_number] + ([.issue.siblings[]?.number] // []) | unique[]' "${REPRO_JSON}")

cd "${REPO_ROOT}"

# Targets (configured in config.env) default to the contributor cross-repo flow: push the fix branch to the user's FORK
# remote (AUTOFIX_PUSH_REMOTE) and open the PR on the upstream GH_REPO. For a cross-repo PR the head must be
# "<owner>:<branch>"; AUTOFIX_PR_HEAD_OWNER is the fork owner (default ASSIGNEE), or "" for a same-repo flow.
PUSH_REMOTE="${AUTOFIX_PUSH_REMOTE}"
PR_HEAD_OWNER="${AUTOFIX_PR_HEAD_OWNER}"
BASE_BRANCH="${AUTOFIX_PR_BASE}"
# A PR diff is computed against the base branch tip, not the commit the work branched from. That is fine upstream, where
# `main` IS on the base SHA's lineage, but on a fork whose `main` has drifted from BASE_SHA the diff balloons to every
# divergent file. AUTOFIX_PUSH_BASE=1 fixes that: push BASE_SHA to a dedicated base branch and target it, so the PR
# shows EXACTLY the fix regardless of the fork's state. ONLY for a SAME-REPO PR: the base branch is pushed to
# PUSH_REMOTE, so it must live in the PR's target repo - do NOT combine with the default cross-repo PR onto upstream.
PUSH_BASE="${AUTOFIX_PUSH_BASE}"
[[ "${PUSH_BASE}" == "1" ]] && BASE_BRANCH="auto-fix/base-issue-${NUMBER}"
# Labels must already exist in the target repo, so they are opt-in: the comma-separated AUTOFIX_PR_LABELS (config.env),
# which can be set to "" to add none. Each is passed only if non-empty so an empty list adds no --label flags.
LABEL_ARGS=()
IFS=',' read -ra _labels <<< "${AUTOFIX_PR_LABELS}"
for l in "${_labels[@]}"; do [[ -n "${l}" ]] && LABEL_ARGS+=(--label "${l}"); done

# Branch name, collision-checked against the push remote so reruns don't clobber a prior attempt.
BRANCH="auto-fix/issue-${NUMBER}"
if git ls-remote --exit-code --heads "${PUSH_REMOTE}" "${BRANCH}" >/dev/null 2>&1; then
  BRANCH="${BRANCH}-$(git rev-parse --short "${BASE_SHA}")-$(date -u +%H%M%S)"
fi
HEAD_REF="${PR_HEAD_OWNER:+${PR_HEAD_OWNER}:}${BRANCH}"
log "PR branch ${BRANCH} -> ${PUSH_REMOTE} (PR on ${GH_REPO}, base ${BASE_BRANCH}, head ${HEAD_REF})"

# Isolated worktree at base; apply the patch; stage exactly the fix.
WT="$(mktemp -d)"
cleanup() { cd "${REPO_ROOT}"; git worktree remove --force "${WT}" >/dev/null 2>&1 || true; }
trap cleanup EXIT
git worktree add --detach "${WT}" "${BASE_SHA}" >/dev/null
cd "${WT}"
git checkout -b "${BRANCH}" >/dev/null
git apply "${PATCH}"
git add -A

# "Fixes #N" lines (one per clustered issue) so merging this single PR closes the whole batch, and a "#a, #b" list.
CLOSES_LINES=""; CLOSES_LIST=""
for n in "${CLOSES[@]}"; do
  CLOSES_LINES="${CLOSES_LINES}Fixes #${n}"$'\n'
  CLOSES_LIST="${CLOSES_LIST}${CLOSES_LIST:+, }#${n}"
done

# The PR title is the full, human-readable summary. The COMMIT SUBJECT obeys the 50/72 rule, but is truncated on a word
# boundary so it never cuts mid-word (e.g. ".. test failu"); the 50-col cap applies to the commit only, not the title.
PR_TITLE="Fix ${SHORT_CLASS} test failure"
COMMIT_SUBJECT="${PR_TITLE}"
if [[ "${#COMMIT_SUBJECT}" -gt 50 ]]; then
  COMMIT_SUBJECT="${COMMIT_SUBJECT:0:50}"; COMMIT_SUBJECT="${COMMIT_SUBJECT% *}"   # drop the partial trailing word
fi
# Commit: 50/72 rule, no AI-attribution trailer (the agent disclosure lives in the PR description only).
git commit --quiet -m "${COMMIT_SUBJECT}" -m "Addresses the failures tracked in ${CLOSES_LIST}."

# PR body via file (never interpolate untrusted text into a command line).
BODY="${AUTOFIX_WORK}/pr-body.md"
cat > "${BODY}" <<EOF
${CLOSES_LINES}
## What

${ERROR_TXT}
$( [[ "${#CLOSES[@]}" -gt 1 ]] && echo "This single change resolves a clustered batch of issues that share one fix locus: ${CLOSES_LIST}." )

## Root cause

${ROOT_CAUSE_TXT}

Verified against base \`${BASE_SHA}\`: the named test passes (Goal 1) and the project's test task is green (Goal 2).
$( [[ "${UNMUTED}" == "true" ]] && echo "This PR also removes the test's \`muted-tests.yml\` entry, so CI will run it again." )

---
> **Note:** this fix was generated by an automated agent, not authored by @${ASSIGNEE} directly. It is a **draft**
> pending human review; do not merge without verifying the change is correct and not merely green.
EOF

if [[ "${DRY_RUN}" == "1" ]]; then
  log "DRY_RUN: would push ${BRANCH} and open draft PR for #${NUMBER}"
  log "DRY_RUN: PR body at ${BODY}"
  cleanup; trap - EXIT
  exit 0
fi

[[ "${PUSH_BASE}" == "1" ]] && git push "${PUSH_REMOTE}" "${BASE_SHA}:refs/heads/${BASE_BRANCH}"
git push -u "${PUSH_REMOTE}" "${BRANCH}"
gh pr create \
  --repo "${GH_REPO}" \
  --draft \
  --base "${BASE_BRANCH}" \
  --head "${HEAD_REF}" \
  --title "${PR_TITLE}" \
  --body-file "${BODY}" \
  --assignee "${ASSIGNEE}" \
  "${LABEL_ARGS[@]}"

cleanup; trap - EXIT
log "opened draft PR for #${NUMBER} on branch ${BRANCH}"
