#!/usr/bin/env bash
# launchd control wrapper for the auto-fix prototype. `install` writes a LaunchAgent plist that fires run-once.sh every
# hour (it captures the CURRENT PATH and JAVA_HOME so the scheduled job has the same toolchain a scheduled process does
# not inherit your interactive shell) and loads it; `stop` unloads it; `start` forces one run now; `status`/`logs` for
# observability. The hourly tick is safe to overlap with a long run because run-once.sh skips when a prior run holds the
# lock. Stop the schedule any time with: scheduler.sh stop
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_ONCE="${SELF_DIR}/run-once.sh"
LOG_DIR="${SELF_DIR}/logs"
LABEL="com.kubik42.autofix"
PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"
DOMAIN="gui/$(id -u)"
INTERVAL="${AUTOFIX_INTERVAL_SECONDS:-3600}"     # one run per hour by default; override for testing

usage() { echo "usage: scheduler.sh {install|stop|start|status|logs}"; exit 2; }

case "${1:-}" in
  install)
    mkdir -p "${HOME}/Library/LaunchAgents" "${LOG_DIR}"
    cat > "${PLIST}" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>${RUN_ONCE}</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>${PATH}</string>
    <key>JAVA_HOME</key><string>${JAVA_HOME:-}</string>
  </dict>
  <key>StartInterval</key><integer>${INTERVAL}</integer>
  <key>RunAtLoad</key><false/>
  <key>StandardOutPath</key><string>${LOG_DIR}/launchd.out</string>
  <key>StandardErrorPath</key><string>${LOG_DIR}/launchd.err</string>
  <key>ProcessType</key><string>Background</string>
</dict>
</plist>
PLIST
    launchctl bootout "${DOMAIN}/${LABEL}" 2>/dev/null || true
    launchctl bootstrap "${DOMAIN}" "${PLIST}"
    echo "installed + loaded ${LABEL}: run-once.sh every ${INTERVAL}s. plist: ${PLIST}"
    echo "next tick within ${INTERVAL}s; force one now with: $0 start"
    ;;
  stop)
    launchctl bootout "${DOMAIN}/${LABEL}" 2>/dev/null || true
    echo "unloaded ${LABEL}: no further hourly ticks. (A run already in flight is sent SIGTERM; rerun 'install' to resume.)"
    echo "plist left at ${PLIST}; delete it to remove entirely."
    ;;
  start)
    launchctl kickstart -k "${DOMAIN}/${LABEL}"
    echo "kicked one run now; follow it with: $0 logs"
    ;;
  status)
    launchctl print "${DOMAIN}/${LABEL}" 2>/dev/null | grep -E "^[[:space:]]*(state|pid|last exit code) " \
      || echo "${LABEL} not loaded (run: $0 install)"
    [[ -d "${SELF_DIR}/.lock" ]] && echo "lock held by pid $(cat "${SELF_DIR}/.lock/pid" 2>/dev/null) (a run is in flight)"
    ;;
  logs)
    latest="$(ls -t "${LOG_DIR}"/run-*.log 2>/dev/null | head -1 || true)"
    [[ -n "${latest}" ]] && { echo "tailing ${latest}"; tail -f "${latest}"; } || echo "no run logs yet in ${LOG_DIR}"
    ;;
  *) usage ;;
esac
