#!/usr/bin/env bash
# Shared helpers for the auto-fix prototype. Sourced by every step script. Establishes the repo root, the work
# directory for run artifacts, and small logging / output helpers. Keeps each step script focused on its own logic.
set -euo pipefail

# Repo root = the directory that holds ./gradlew, discovered relative to this file (auto-fix/ lives at the repo root).
AUTOFIX_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${AUTOFIX_DIR}/.." && pwd)"

# Every tunable lives in config.env - the single configuration file (plain KEY=value data). Load it here so the values
# reach every step script that sources lib.sh. Each entry is applied as a DEFAULT, only when the variable is unset, so
# an environment override always wins; the value is literal to end of line (commas and > in a label list are safe) and
# exported so subprocesses (gh, the nested claude agents, diff_guard.py) inherit it.
while IFS= read -r _line || [[ -n "${_line}" ]]; do
  _line="${_line%%$'\r'}"                                  # tolerate CRLF line endings
  [[ "${_line}" =~ ^[[:space:]]*(#.*)?$ ]] && continue     # skip blank lines and comments
  _key="${_line%%=*}"; _key="${_key//[[:space:]]/}"        # key is left of the first =, whitespace trimmed
  [[ -z "${_key}" ]] && continue
  [[ -z "${!_key+x}" ]] && printf -v "${_key}" '%s' "${_line#*=}"   # default only when unset (env wins)
  export "${_key}"
done < "${AUTOFIX_DIR}/config.env"
unset _line _key

# Defaults derived from other settings (kept out of the declarative file, which has no interpolation), each applied
# only when unset so the environment - or an explicit entry in config.env - still wins. AUTOFIX_WORK holds run
# artifacts (issue.json, repro.json, diagnostics/, logs) under the prototype dir, so they are trivially deletable and
# excluded from the step-3 new-file guard. AUTOFIX_PR_HEAD_OWNER is the cross-repo fork owner; set "" for same-repo.
: "${AUTOFIX_WORK:=${AUTOFIX_DIR}/run}"
: "${AUTOFIX_PR_HEAD_OWNER=${ASSIGNEE}}"
# The pipeline operates inside a dedicated git worktree, never the user's main checkout. AUTOFIX_TREE defaults to a
# SIBLING of the repo (not a child) so the second full checkout and its multi-GB build/ stay outside the main tree -
# the IDE, Gradle, and `git status` of the main checkout never see it. ensure_run_tree (below) creates/resets it.
: "${AUTOFIX_TREE:=${REPO_ROOT}-autofix-worktree}"
export AUTOFIX_WORK AUTOFIX_PR_HEAD_OWNER AUTOFIX_TREE

mkdir -p "${AUTOFIX_WORK}"

log()  { printf '[auto-fix %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die()  { log "FATAL: $*"; exit 1; }

# Local alias for the dry-run flag (configured as AUTOFIX_DRY_RUN in config.env): 1 suppresses every GitHub mutation
# (assign, comment, branch push, PR) so the pipeline can be exercised end-to-end without touching the live repo.
DRY_RUN="${AUTOFIX_DRY_RUN}"

# Run a `gh` mutation unless in dry-run mode, in which case log the intended command and skip it.
gh_write() {
  if [[ "${DRY_RUN}" == "1" ]]; then
    log "DRY_RUN: would run: gh $*"
    return 0
  fi
  gh "$@"
}

# Post a comment on an issue (untrusted-data-safe: body is passed via --body-file, never interpolated into a command).
issue_comment() {
  local number="$1" body_file="$2"
  gh_write issue comment "${number}" --repo "${GH_REPO}" --body-file "${body_file}"
}

# Create-or-reset the dedicated run worktree (AUTOFIX_TREE) pinned to upstream main - the single source of a clean base.
# The pipeline operates here so a run never disturbs the user's main checkout and the two trees build independently. The
# worktree is reused across runs to keep the Gradle build cache warm (the dominant ES cost); `checkout -f -B` plus a
# `clean -fd` reset tracked AND stray untracked source to a pristine upstream tip while leaving the gitignored build/.
ensure_run_tree() {
  local ref="${AUTOFIX_UPSTREAM_REMOTE}/${AUTOFIX_UPSTREAM_BRANCH}"
  git -C "${REPO_ROOT}" fetch "${AUTOFIX_UPSTREAM_REMOTE}" "${AUTOFIX_UPSTREAM_BRANCH}"
  git -C "${REPO_ROOT}" worktree prune
  if git -C "${AUTOFIX_TREE}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "${AUTOFIX_TREE}" checkout -f -B "${AUTOFIX_RUN_BRANCH}" "${ref}"
    git -C "${AUTOFIX_TREE}" clean -fd >/dev/null 2>&1 || true
    log "reset run worktree ${AUTOFIX_TREE} to ${ref}"
  else
    git -C "${REPO_ROOT}" worktree add -B "${AUTOFIX_RUN_BRANCH}" "${AUTOFIX_TREE}" "${ref}"
    log "created run worktree ${AUTOFIX_TREE} at ${ref}"
  fi
}

# Remove the run worktree iff AUTOFIX_KEEP_TREE=0 (default 1 keeps it for a warm Gradle cache). Call ONLY at genuine
# pipeline-terminal points (never between steps that share the worktree); a safe no-op if it was never created.
maybe_cleanup_run_tree() {
  [[ "${AUTOFIX_KEEP_TREE}" == "1" ]] && return 0
  git -C "${REPO_ROOT}" worktree remove --force "${AUTOFIX_TREE}" >/dev/null 2>&1 || true
  log "removed run worktree ${AUTOFIX_TREE} (AUTOFIX_KEEP_TREE=0)"
}

# Emit a key=value pair to the GitHub Actions step output file when running under Actions; always echo for local runs.
emit() {
  local key="$1" value="$2"
  log "output ${key}=${value}"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "${key}" "${value}" >> "${GITHUB_OUTPUT}"
  fi
}
