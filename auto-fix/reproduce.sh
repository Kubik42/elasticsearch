#!/usr/bin/env bash
# Step 2: take issue.json, validate + reproduce the named failure against base main, classify the result strictly from
# the JUnit XML, bundle diagnostics, and hand off repro.json. Three terminal outcomes (all keep the assignment, since
# unassigning would re-float the issue into step 1's pool and cause infinite reprocessing):
#   REPRODUCED     -> repro.json written, step 3 proceeds.
#   NOT_REPRODUCED -> the named test passed; comment, stop.
#   FAILURE        -> could not even run the named test (bad parse, unmet env, still "no tests found"); comment, stop.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ISSUE_JSON="${1:-${AUTOFIX_WORK}/issue.json}"
# Resolve to an absolute path now: section 0 cd's into the worktree before the parser reads this, so a relative arg
# (the default is already absolute) would otherwise break once the working directory changes.
ISSUE_JSON="$(cd "$(dirname "${ISSUE_JSON}")" && pwd)/$(basename "${ISSUE_JSON}")"
REPRO_JSON="${AUTOFIX_WORK}/repro.json"
DIAG="${AUTOFIX_WORK}/diagnostics"
RUN_LOG="${AUTOFIX_WORK}/gradle.log"
PARSED="${AUTOFIX_WORK}/parsed.json"
MUTED="${AUTOFIX_TREE}/muted-tests.yml"
rm -rf "${DIAG}"; mkdir -p "${DIAG}"

NUMBER="$(jq -r '.number' "${ISSUE_JSON}")"
ISSUE_URL="$(jq -r '.url' "${ISSUE_JSON}")"

# Terminal-outcome helper: record the outcome, post the issue comment (unless dry-run), and exit cleanly (green run).
finish() {
  local outcome="$1" message="$2"
  emit outcome "${outcome}"
  local cf="${AUTOFIX_WORK}/comment.md"
  { printf '**Auto-fix step 2 — %s**\n\n%s\n' "${outcome}" "${message}"; } > "${cf}"
  if [[ "${outcome}" != "REPRODUCED" ]]; then
    issue_comment "${NUMBER}" "${cf}"
    # Pipeline stops here (step 3 will not run), so the shared worktree is no longer needed. On REPRODUCED it is kept
    # for fix.sh, which runs next. maybe_cleanup_run_tree is a no-op unless AUTOFIX_KEEP_TREE=0.
    maybe_cleanup_run_tree
  fi
  log "step 2 outcome: ${outcome}"
  exit 0
}

# ---- 0. Base preparation: run inside a dedicated worktree pinned to upstream main ------------------------------
# The whole pipeline operates in AUTOFIX_TREE (a separate git worktree), never the user's main checkout, so a run can
# never disturb in-progress work and step 3's `git diff` captures exactly the agent's delta. ensure_run_tree fetches
# upstream and creates-or-resets the worktree to a pristine <remote>/<branch>; every step below runs from inside it.
# Done before parsing so the muted-tests.yml the parser reconciles against is the same upstream copy the run acts on.
ensure_run_tree
cd "${AUTOFIX_TREE}"
BASE_SHA="$(git rev-parse HEAD)"
log "base SHA ${BASE_SHA}"

# ---- 1. Parse + validate (security boundary) + reconcile the test name against muted-tests.yml -----------------
# The reproduction command line can disagree with the rest of the issue; the muted-tests.yml entry keyed to the issue
# number is the canonical identity, so the parser prefers it on conflict and reports a warning we surface here.
python3 "${AUTOFIX_DIR}/parse_repro.py" "${ISSUE_JSON}" "${MUTED}" > "${PARSED}"
if [[ "$(jq -r '.valid' "${PARSED}")" != "true" ]]; then
  finish FAILURE "Could not parse a safe reproduction command: $(jq -r '.reason' "${PARSED}")"
fi
RECONCILE_WARNING="$(jq -r '.reconcile_warning // empty' "${PARSED}")"
if [[ -n "${RECONCILE_WARNING}" ]]; then
  log "reconcile: ${RECONCILE_WARNING}"
fi

mapfile -t ARGV < <(jq -r '.argv[]' "${PARSED}")
TASK_PATH="$(jq -r '.task_path' "${PARSED}")"
TEST_CLASS="$(jq -r '.test_class' "${PARSED}")"
TEST_METHOD="$(jq -r '.test_method' "${PARSED}")"
# How gradle selects the test: the --tests filter (synthetic-source / ES|QL forms) or the -Dtests.class/-Dtests.method
# system properties (the bwc / mixed-cluster form, whose StandaloneRestIntegTestTask ignores --tests). For the latter the
# class+method ride in flag_args via the passthrough flags, so build_argv adds no --tests and the --tests leak probe is skipped.
SELECT_VIA="$(jq -r '.select_via // "--tests"' "${PARSED}")"

# ---- 2. Branch gate (main only) --------------------------------------------------------------------------------
if jq -e '.applicable_branches | index("main")' "${PARSED}" >/dev/null; then
  log "branch gate ok (main is applicable)"
else
  finish FAILURE "Issue is not applicable to main (branches: $(jq -c '.applicable_branches' "${PARSED}")); only main is in scope."
fi

# ---- 3. Environment pre-flight (gate significant flags; never substitute) --------------------------------------
RUNTIME_JAVA="$(jq -r '.gated_flags."runtime.java" // empty' "${PARSED}")"
if [[ -n "${RUNTIME_JAVA}" && "${AUTOFIX_SKIP_JAVA_GATE}" != "1" ]]; then
  java_home_var="JAVA${RUNTIME_JAVA}_HOME"
  if [[ -z "${!java_home_var:-}" ]]; then
    finish FAILURE "Reproduction requires -Druntime.java=${RUNTIME_JAVA} but ${java_home_var} is not set; not substituting a different JDK."
  fi
  log "java gate ok (${java_home_var} present)"
fi
LICENSE_KEY="$(jq -r '.gated_flags."license.key" // empty' "${PARSED}")"
if [[ -n "${LICENSE_KEY}" && ! -f "${AUTOFIX_TREE}/${LICENSE_KEY}" ]]; then
  finish FAILURE "Reproduction requires -Dlicense.key=${LICENSE_KEY} but that file is absent."
fi

# ---- 4. Unmute FIRST ------------------------------------------------------------------------------------------------
# A muted test never executes, so every reproduction attempt against a still-muted test is a false negative. Remove the
# mute up front - before any gradle attempt. We unmute the whole (class, bare-method) FAMILY, not just this issue's
# entry: MutedTestsBuildService mutes a parameterized test by also excluding the bare method name (e.g. Class.test), so
# any single residual sibling mute keeps the target hidden. Section 8 reverts this on the base; step 3 removes the
# mutes permanently as part of the fix. UNMUTED is initialised here; UNMUTED_ISSUES records the numbers we cleared.
UNMUTED=0
mapfile -t UNMUTED_ISSUES < <(python3 "${AUTOFIX_DIR}/unmute.py" "${MUTED}" --family "${TEST_CLASS}" "${TEST_METHOD}")
if [[ "${#UNMUTED_ISSUES[@]}" -gt 0 ]]; then
  UNMUTED=1
  log "unmuted ${#UNMUTED_ISSUES[@]} muted-tests.yml entr(y/ies) [#${UNMUTED_ISSUES[*]}] for the ${TEST_CLASS}.test family before reproducing"
else
  log "no muted-tests.yml entry for the ${TEST_CLASS}.test family; nothing to unmute"
fi

# ---- 5. Reproduction attempts --------------------------------------------------------------------------------------
# The test was already unmuted in section 4b, so it can actually run. The failure can still be hidden three ways, so we
# try ordered attempts (each a fresh, cache-busted execution) and stop at the first that reproduces:
#   1. narrow --tests (CLASS.method{p0=...}) + the issue's own flags - the canonical, exact reproduction;
#   2. CLASS-LEVEL --tests (the class only, no method/param) with the same flags - exposes a test that LEAKS past an
#      insufficient build.gradle excludeTestsMatching filter (the runner checks the bare method name too); a narrow
#      method+param filter and a no-filter suite run both mask this. This is how the failure surfaces in CI;
#   3. snapshot-flip (-Dbuild.snapshot=true) when the issue used build.snapshot=false - feature-flag-gated tests are
#      disabled/excluded in release builds, so flipping enables the flag and lets the test actually run.
# The task path's last segment is the gradle task name, not a directory; strip it to get the project dir, where the
# JUnit XML lives under <project>/build/{test-results,testrun}/<task>/.
PROJECT_DIR="${AUTOFIX_TREE}/$(echo "${TASK_PATH%:*}" | sed 's#:#/#g; s#^/##')"
TASK_DIR="${PROJECT_DIR}/build"
RESULTS_ROOT="${TASK_DIR}/test-results"

EXCLUSION_INSUFFICIENT=0
SNAPSHOT_FLIPPED=0
REPRO_NOTE=""
ATT=()
WIN_ARGV=()

# Print the issue's -D flags, optionally overriding build.snapshot (and its tests.jvm.argline twin) to $1 ("" = keep).
flag_args() {
  local snap="$1" key val
  while IFS= read -r kv; do
    key="${kv%%=*}"; val="${kv#*=}"
    [[ -n "${snap}" && "${key}" == "build.snapshot" ]] && val="${snap}"
    [[ -n "${snap}" && "${key}" == "tests.jvm.argline" ]] && val="-Dbuild.snapshot=${snap}"
    if [[ -n "${val}" ]]; then printf -- '-D%s=%s\n' "${key}" "${val}"; else printf -- '-D%s\n' "${key}"; fi
  done < <(jq -r '.gated_flags | to_entries[] | "\(.key)=\(.value)"' "${PARSED}")
  jq -r '.passthrough_flags | to_entries[] | "-D\(.key)=\(.value)"' "${PARSED}"
}

# Build the argv for an attempt into the global ATT array. $1 = include filter (narrow|class|none), $2 = snapshot
# override ("" keeps it). Cache-busting is mandatory: `gradle clean` wipes build/ but NOT the build/configuration
# caches, so after we mutate muted-tests.yml a re-run is served FROM-CACHE with the stale (muted) result, silently
# masking the failure. --rerun-tasks/--no-build-cache/--no-configuration-cache force a true fresh execution.
build_argv() {
  ATT=("./gradlew" "${TASK_PATH}" "--rerun-tasks" "--no-build-cache" "--no-configuration-cache")
  # Only add a --tests filter when that is gradle's selection mechanism. The bwc / mixed-cluster form selects via
  # -Dtests.class/-Dtests.method, which flag_args already emits from the passthrough flags, so it needs no --tests here.
  if [[ "${SELECT_VIA}" == "--tests" ]]; then
    case "$1" in
      narrow) ATT+=("--tests" "${TEST_CLASS}.${TEST_METHOD}") ;;
      class)  ATT+=("--tests" "${TEST_CLASS}") ;;
      none)   : ;;
    esac
  fi
  while IFS= read -r a; do ATT+=("${a}"); done < <(flag_args "$2")
}

# Run the current ATT array (labelled) from a clean build, capturing the log; sets GRADLE_EXIT.
run_attempt() {
  log "attempt [$1]: ${ATT[*]}"
  ./gradlew clean >/dev/null 2>&1 || true
  set +e; "${ATT[@]}" >"${RUN_LOG}" 2>&1; GRADLE_EXIT=$?; set -e
  log "attempt [$1] gradle exit ${GRADLE_EXIT}"
}

# Classify the named test from the latest run's JUnit XML; prints the outcome.
classify_named() {
  local root="${RESULTS_ROOT}"; [[ -d "${root}" ]] || root="${AUTOFIX_TREE}"
  python3 "${AUTOFIX_DIR}/classify.py" "${root}" "${TEST_CLASS}" "${TEST_METHOD}" > "${AUTOFIX_WORK}/classification.json"
  jq -r '.outcome' "${AUTOFIX_WORK}/classification.json"
}

# --- Attempt 1: narrow --tests (CLASS.method{p0=...}), the issue's own flags - the canonical, exact reproduction ---
build_argv narrow ""
WIN_ARGV=("${ATT[@]}")
run_attempt "narrow/original"
OUTCOME="$(classify_named)"

# --- Attempt 2: CLASS-LEVEL --tests (just the class, no method/param), same flags = the excludeTestsMatching leak
# probe. A class-level include filter makes the randomized runner enumerate the class's methods and check the
# build.gradle exclude pattern against the BARE method name ("test"), which never contains the parameterised
# substring, so the excluded parameterisations LEAK and run (and fail under the release flags). A narrow method+param
# filter and a no-filter suite run BOTH mask this - only the class-level filter exposes it (matches CI). ---
if [[ "${OUTCOME}" != "REPRODUCED" && "${SELECT_VIA}" == "--tests" ]]; then
  build_argv class ""
  run_attempt "class-level (excludeTestsMatching leak probe)"
  if [[ "$(classify_named)" == "REPRODUCED" ]]; then
    OUTCOME="REPRODUCED"; EXCLUSION_INSUFFICIENT=1; WIN_ARGV=("${ATT[@]}")
    REPRO_NOTE="Reproduces only with a CLASS-LEVEL --tests filter (the class, no method/param): the parameterised test LEAKS past the build.gradle excludeTestsMatching filter, which matches only the parameterised display name while the randomized runner also checks the bare method name. The exclusion is therefore insufficient (a narrow method+param filter and a no-filter run both mask it). Likely fix: enumerate both the parameterised and the bare-method name forms per suite class (see fix-instructions.md, pattern 1)."
    log "exclusion-insufficiency leak detected (class-level filter)"
  fi
fi

# --- Attempt 3: snapshot-flip, only when the issue used build.snapshot=false ---
if [[ "${OUTCOME}" != "REPRODUCED" && "$(jq -r '.gated_flags."build.snapshot" // empty' "${PARSED}")" == "false" ]]; then
  build_argv narrow "true"
  run_attempt "narrow/snapshot=true (feature-flag probe)"
  if [[ "$(classify_named)" == "REPRODUCED" ]]; then
    OUTCOME="REPRODUCED"; SNAPSHOT_FLIPPED=1; WIN_ARGV=("${ATT[@]}")
    REPRO_NOTE="Reproduces only with -Dbuild.snapshot=true: the test is feature-flag-gated and the issue's -Dbuild.snapshot=false both disables the flag and triggers the build.gradle exclusion, so the failure cannot surface in a release build."
    log "feature-flag (snapshot=true) reproduction"
  fi
fi
log "final classification: ${OUTCOME}"

# ---- 7. Diagnostics bundle (cluster logs + JUnit XML + thin root-cause) ----------------------------------------
# Cluster node logs (where the *real* error lives when the ES cluster fails to start) and the JUnit XML, best-effort.
# Two layouts hold node logs: in-JVM integ tests write under build/testrun/<task>/, while Gradle test-cluster tasks
# (the bwc / mixed-cluster suites) write each node's log under build/testclusters/<node>/logs/ - that is where a generic
# ConnectTransportException's true cause (e.g. a node that died on an InvalidIndexTemplateException) is recorded, so both
# roots must be scanned or the fix step gets only the uninformative transport error. A missing root just yields no hits.
# Preserve the relative path of each cluster log so multi-node layouts stay legible. Portable (no GNU `cp --parents`).
while IFS= read -r f; do
  rel="${f#${AUTOFIX_TREE}/}"
  mkdir -p "${DIAG}/$(dirname "${rel}")"
  cp "${f}" "${DIAG}/${rel}" 2>/dev/null || true
done < <(find "${TASK_DIR}/testrun" "${TASK_DIR}/testclusters" -path '*/logs/*' -type f ! -name 'gc.log*' 2>/dev/null)
while IFS= read -r f; do
  cp "${f}" "${DIAG}/" 2>/dev/null || true
done < <(find "${TASK_DIR}/test-results" -name 'TEST-*.xml' -type f 2>/dev/null)
# Hand the agent a TRIMMED gradle log: drop successful per-task lifecycle lines and compile notes (~2/3 of the output,
# pure noise) while keeping FAILED tasks and the failure/exception block. The full log stays at ${RUN_LOG} for humans.
awk '
  /^> Task / && !/FAILED/ { next }
  /^Note: / { next }
  /uses or overrides a deprecated|Recompile with -Xlint/ { next }
  /^Download(ing|ed) / { next }
  { print }
' "${RUN_LOG}" > "${DIAG}/gradle.log" 2>/dev/null || cp "${RUN_LOG}" "${DIAG}/gradle.log" 2>/dev/null || true
jq -r '.failure_message // empty, .failure_detail // empty' "${AUTOFIX_WORK}/classification.json" > "${DIAG}/root-cause.txt" 2>/dev/null || true

# ---- 8. Capture reproduction steps + revert any unmute before reporting ----------------------------------------
REPRO_STEPS="${REPRO_NOTE}"
if [[ "${UNMUTED}" == "1" ]]; then
  REPRO_STEPS="${REPRO_STEPS}${REPRO_STEPS:+ }Before running the command, remove the muted-tests.yml entries for the ${TEST_CLASS}.test family (issues #${UNMUTED_ISSUES[*]}); a single residual sibling mute re-adds the bare-method exclusion and hides the test."
  git checkout -- "${MUTED}"
  log "reverted muted-tests.yml unmute (step 3 will remove the mutes permanently)"
fi

# The argv that ACTUALLY reproduced (may differ from the issue's narrow line: class-level or snapshot-flipped).
WIN_ARGV_JSON="$(printf '%s\n' "${WIN_ARGV[@]}" | jq -R . | jq -s .)"
# The full (class, bare-method) mute family we cleared - step 3 removes these permanently; the PR documents them.
UNMUTED_ISSUES_JSON="$(printf '%s\n' "${UNMUTED_ISSUES[@]+"${UNMUTED_ISSUES[@]}"}" | jq -R 'select(length>0) | tonumber' | jq -s .)"

# ---- 9. Branch on outcome --------------------------------------------------------------------------------------
case "${OUTCOME}" in
  REPRODUCED)
    jq -n \
      --argjson parsed "$(cat "${PARSED}")" \
      --argjson issue "$(cat "${ISSUE_JSON}")" \
      --argjson classification "$(cat "${AUTOFIX_WORK}/classification.json")" \
      --argjson repro_command "${WIN_ARGV_JSON}" \
      --arg base_sha "${BASE_SHA}" \
      --arg diagnostics "${DIAG}" \
      --arg repro_steps "${REPRO_STEPS}" \
      --argjson unmuted "${UNMUTED}" \
      --argjson unmuted_issues "${UNMUTED_ISSUES_JSON}" \
      --argjson exclusion_insufficient "${EXCLUSION_INSUFFICIENT}" \
      --argjson snapshot_flipped "${SNAPSHOT_FLIPPED}" \
      '{
        issue_number: $issue.number, issue_url: $issue.url, issue: $issue,
        argv: $parsed.argv, reproduction_command: $repro_command, task_path: $parsed.task_path,
        test_class: $parsed.test_class, test_method: $parsed.test_method,
        gated_flags: $parsed.gated_flags, passthrough_flags: $parsed.passthrough_flags,
        base_sha: $base_sha, diagnostics_path: $diagnostics,
        unmuted: ($unmuted == 1), unmuted_issues: $unmuted_issues, reproduction_steps: $repro_steps,
        exclusion_insufficient: ($exclusion_insufficient == 1), snapshot_flipped: ($snapshot_flipped == 1),
        root_cause: { type: $classification.failure_type, message: $classification.failure_message }
      }' > "${REPRO_JSON}"
    emit repro_json "${REPRO_JSON}"
    finish REPRODUCED "Reproduced ${TEST_CLASS}.${TEST_METHOD} on base ${BASE_SHA}. Handing off to step 3."
    ;;
  NOT_REPRODUCED)
    finish NOT_REPRODUCED "The named test passed on base ${BASE_SHA} with the issue's seed. Not reproducible as-is (single-phase; -Dtests.iters phase is deferred). Staying assigned for manual review."
    ;;
  NOT_FOUND|FAILURE)
    finish FAILURE "Could not run the named test ($(jq -r '.reason // "unknown"' "${AUTOFIX_WORK}/classification.json")). See diagnostics."
    ;;
  *)
    finish FAILURE "Unexpected classification outcome: ${OUTCOME}"
    ;;
esac
