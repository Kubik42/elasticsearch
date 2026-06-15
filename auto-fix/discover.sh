#!/usr/bin/env bash
# Step 1: select one open, unassigned, bot-filed StorageEngine test-failure issue as the PRIMARY, cluster any SIBLING
# issues that share the same fix locus, claim them all by assignment, and hand off via issue.json (siblings embedded).
# Bot test-failure issues are often batch-filed: many issues, one root cause, one fix (e.g. several parameterised tests
# in the same yaml file, across two suite classes). Clustering by locus lets one draft PR close the whole batch, the
# way a human fix does. The issue body is untrusted data: written to a file, never interpolated into a shell command.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ISSUE_JSON="${AUTOFIX_WORK}/issue.json"
RAW="${AUTOFIX_WORK}/_raw.json"
MUTED="${REPO_ROOT}/muted-tests.yml"

# The >test-failure label's literal ">" breaks `gh search issues`; `gh issue list --search` is required. -linked:pr
# skips issues a human (or a prior auto-fix run) already has a PR for, which is also the dedup signal for FIXED issues.
SEARCH='label:>test-failure label:Team:StorageEngine no:assignee author:elasticsearchmachine -linked:pr sort:created-desc'

log "querying candidate pool in ${GH_REPO}"
gh issue list \
  --repo "${GH_REPO}" \
  --search "${SEARCH}" \
  --state open \
  --limit "${AUTOFIX_DISCOVER_LIMIT}" \
  --json number,title,body,createdAt,url > "${RAW}"

count="$(jq 'length' "${RAW}")"
if [[ "${count}" -eq 0 ]]; then
  log "no matching issues; clean no-op"
  emit issue ""
  exit 0
fi

# Fix-locus key for a candidate = (gradle task path, test file) derived from its validated reproduction line. The test
# file is the {p0=<dir>/<file>/<name>} path with the trailing test-name segment stripped, so every parameterised case
# in the same yaml file and task clusters together. Empty when the candidate has no parseable reproduction line.
locus_of() {
  local issue_json="$1" parsed task tm p0 file
  parsed="$(python3 "${AUTOFIX_DIR}/parse_repro.py" "${issue_json}" "${MUTED}")"
  [[ "$(jq -r '.valid' <<<"${parsed}")" == "true" ]] || { printf ''; return; }
  task="$(jq -r '.task_path' <<<"${parsed}")"
  tm="$(jq -r '.test_method' <<<"${parsed}")"
  if [[ "${tm}" == *'{p0='* ]]; then
    p0="${tm#*\{p0=}"; p0="${p0%\}}"   # <dir>/<file>/<name>
    file="${p0%/*}"                    # strip trailing /<name>; no slash -> whole p0
  else
    file="${tm}"                       # non-parameterised: the bare method identity is the locus
  fi
  printf '%s::%s' "${task}" "${file}"
}

# Primary = newest candidate (the query sorts created-desc). Write it out and compute its locus.
jq '.[0]' "${RAW}" > "${ISSUE_JSON}"
number="$(jq -r '.number' "${ISSUE_JSON}")"
title="$(jq -r '.title' "${ISSUE_JSON}")"
primary_locus="$(locus_of "${ISSUE_JSON}")"
log "primary #${number}: ${title}"
log "primary locus: ${primary_locus:-<none>}"

# Cluster: any other candidate sharing the primary's (non-empty) locus is a sibling fixed by the same change. A draft
# PR + human review is the final safety net against an over-eager match; we group only on an exact locus equality.
siblings_json="[]"
sibling_numbers=()
if [[ -n "${primary_locus}" ]]; then
  tmp="$(mktemp)"
  for i in $(seq 1 $((count - 1))); do
    jq ".[${i}]" "${RAW}" > "${tmp}"
    if [[ "$(locus_of "${tmp}")" == "${primary_locus}" ]]; then
      snum="$(jq -r '.number' "${tmp}")"
      sibling_numbers+=("${snum}")
      siblings_json="$(jq --argjson s "$(jq '{number, url, title}' "${tmp}")" '. + [$s]' <<<"${siblings_json}")"
    fi
  done
  rm -f "${tmp}"
fi
log "siblings: ${sibling_numbers[*]:-<none>}"

# Embed the sibling list in issue.json so it rides through step 2's repro.json to step 3's PR ("Fixes #" per member).
jq --argjson siblings "${siblings_json}" '. + {siblings: $siblings}' "${ISSUE_JSON}" > "${ISSUE_JSON}.tmp" && mv "${ISSUE_JSON}.tmp" "${ISSUE_JSON}"

# Claim the whole cluster. The query already filters no:assignee, so assigned issues drop out of the next run's pool.
gh_write issue edit "${number}" --repo "${GH_REPO}" --add-assignee "${ASSIGNEE}"
log "claimed primary #${number} for ${ASSIGNEE}"
for snum in "${sibling_numbers[@]}"; do
  gh_write issue edit "${snum}" --repo "${GH_REPO}" --add-assignee "${ASSIGNEE}"
  log "claimed sibling #${snum} for ${ASSIGNEE}"
done

emit issue "${number}"
emit issue_json "${ISSUE_JSON}"
emit siblings "${sibling_numbers[*]:-}"
