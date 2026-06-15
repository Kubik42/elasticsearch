#!/usr/bin/env bash
# Local single-tick orchestrator for the auto-fix prototype. Replicates the GitHub Actions job (discover -> reproduce ->
# fix) for ONE run on this machine: it serializes via an atomic lock so an hourly tick fired while a previous run is
# still in flight is skipped (the steps share AUTOFIX_TREE and run/, so overlap would corrupt state), chains the three
# steps on the same key=value outputs they already emit, and writes a timestamped log. Invoked per tick by the launchd
# agent (see scheduler.sh); safe to run by hand too. NOT `set -e`: step failures are handled so the lock always releases.
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUTOFIX_DIR="$(cd "${SELF_DIR}/.." && pwd)"     # the prototype dir holding discover.sh / reproduce.sh / fix.sh
LOG_DIR="${SELF_DIR}/logs"
LOCK_DIR="${SELF_DIR}/.lock"                     # mkdir is atomic and portable; flock is absent on stock macOS
mkdir -p "${LOG_DIR}"

ts="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="${LOG_DIR}/run-${ts}.log"
log() { printf '[orchestrator %s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "${LOG}" >&2; }

# Serialize: claim the lock or skip this tick. A lock whose recorded PID is dead is reclaimed (a prior run was killed,
# e.g. by `scheduler.sh stop` mid-fix), so a crash never wedges the schedule permanently.
if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
  prev="$(cat "${LOCK_DIR}/pid" 2>/dev/null || true)"
  if [[ -n "${prev}" ]] && kill -0 "${prev}" 2>/dev/null; then
    log "previous run still in flight (pid ${prev}); skipping this tick"
    exit 0
  fi
  log "stale lock (pid ${prev:-unknown} not running); reclaiming"
  rm -rf "${LOCK_DIR}"; mkdir "${LOCK_DIR}"
fi
echo "$$" > "${LOCK_DIR}/pid"
trap 'rm -rf "${LOCK_DIR}"; log "lock released"' EXIT
log "lock acquired (pid $$); full log: ${LOG}"

# Chain the steps through the very mechanism the scripts use under Actions: emit() appends key=value to $GITHUB_OUTPUT
# when set. We point it at a temp file and read it after each step, resetting between steps so each step's keys are clean.
OUT="$(mktemp)"; export GITHUB_OUTPUT="${OUT}"
read_out()  { grep -E "^$1=" "${OUT}" 2>/dev/null | tail -n1 | cut -d= -f2-; }
reset_out() { : > "${OUT}"; }

# The steps write their human-readable terminal-outcome text to a file under the run dir (reproduce -> comment.md,
# fix -> giveup.md) and post it as an issue comment; the orchestrator only sees the outcome key, so that explanation
# stays buried in the per-step log. Surface it: mirror the same default lib.sh uses for the run dir (env override wins)
# and echo the message file, when present, to the terminal and the run log so a non-happy outcome is visible at a glance.
WORK="${AUTOFIX_WORK:-${AUTOFIX_DIR}/run}"
show_message() { local f="$1"; [[ -s "${f}" ]] && { echo; cat "${f}"; echo; } | tee -a "${LOG}" >&2; return 0; }

run_step() {  # name script -> returns the step's exit code; all step stdout/stderr is appended to the run log
  local name="$1" script="$2" rc
  log "=== step: ${name} (${script}) ==="
  bash "${AUTOFIX_DIR}/${script}" >>"${LOG}" 2>&1; rc=$?
  if [[ ${rc} -eq 0 ]]; then log "step ${name} exited 0"; else log "step ${name} FAILED (exit ${rc})"; fi
  return ${rc}
}

# Step 1: discover & claim. Emits issue= (empty when no candidate -> nothing to do this tick).
reset_out
run_step discover discover.sh || exit 1
issue="$(read_out issue)"
if [[ -z "${issue}" ]]; then log "no candidate issue found; done"; exit 0; fi
log "claimed issue #${issue}"

# Step 2: reproduce. Emits outcome=REPRODUCED|NOT_REPRODUCED|FAILURE; only REPRODUCED advances to the fix step.
reset_out
run_step reproduce reproduce.sh || exit 1
outcome="$(read_out outcome)"
log "reproduce outcome: ${outcome:-<none>}"
if [[ "${outcome}" != "REPRODUCED" ]]; then
  show_message "${WORK}/comment.md"
  log "not reproduced; staying assigned for manual review; done"
  exit 0
fi

# Step 3: fix, verify, open draft PR. FIXED opens a draft PR; GAVE_UP writes giveup.md - surface either to the terminal.
reset_out
run_step fix fix.sh || exit 1
fix_outcome="$(read_out outcome)"
log "fix outcome: ${fix_outcome:-<none>}"
if [[ "${fix_outcome}" == "FIXED" ]]; then
  log "fix step complete for #${issue}; inspect the run/ artifacts and any draft PR"
else
  show_message "${WORK}/giveup.md"
  log "fix did not produce a PR (outcome: ${fix_outcome:-<none>}); see run/ artifacts"
fi
