#!/usr/bin/env python3
"""Step 3 fail-closed guard: reject a generated patch that games "make the named test pass" instead of fixing the bug.

"Make the named test green" has trivially-reachable degenerate solutions a code-writing agent will find: re-mute the
test, slap on ``@AwaitsFix``/``@AwaitsBug``, ``assume*``-skip the failing path, or delete/weaken the assertion that
was failing. Independent verification (verify.sh) *cannot* catch these - a gutted test passes by construction. So this
runs after generation and before verification and fails closed on any forbidden construct. It is pattern-based, hence
necessary-not-sufficient; the draft PR plus human review is the real backstop.

It also enforces a hard SCOPE cap: a fix for one bot-filed failure should be small and mostly touch test/build files. A
diff over ``_MAX_FIX_LINES`` changed lines (excluding ``muted-tests.yml``) is rejected as too deep for autonomous work.

Inputs (argv):
  1. patch file - unified ``git diff`` of tracked changes (the exact patch the PR will contain).
  2. baseline status file - ``git status --porcelain --untracked-files=all`` captured *before* the agent ran.
  3. current status file - the same command captured *after* the agent ran.

The work directory (the prototype's own ``auto-fix/`` tree) and gradle ``build/`` output are never part of a fix, so
new untracked entries under those prefixes are ignored when detecting agent-created files.

Exit 0 = clean (proceed to verify). Exit 1 = guard tripped (caller maps to GAVE_UP). A JSON verdict goes to stdout.
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

# Untracked paths under these prefixes are never "the fix" and must not count as agent-created new files.
_IGNORED_UNTRACKED_PREFIXES = ("auto-fix/", "build/")

# A correct fix for one bot-filed test failure is small: ideally test/build files, rarely production, and tightly
# scoped. A diff larger than this many changed lines (added + removed, EXCLUDING muted-tests.yml churn) signals a deeper
# problem the agent must not resolve autonomously - it is handed off to a human. The canonical default lives in
# config.env (exported as AUTOFIX_MAX_FIX_LINES); the literal here is only the fallback for a standalone invocation.
_MAX_FIX_LINES = int(os.environ.get("AUTOFIX_MAX_FIX_LINES", "20"))

# Constructs that silence rather than fix. Matched against ADDED ("+") lines of the patch.
_DENY_ADDED = [
    (re.compile(r"@AwaitsFix\b"), "re-adds @AwaitsFix (silences the test)"),
    (re.compile(r"@AwaitsBug\b"), "re-adds @AwaitsBug (silences the test)"),
    (re.compile(r"\bassumeFalse\s*\("), "adds assumeFalse(...) (skips the failing path)"),
    (re.compile(r"\bassumeTrue\s*\("), "adds assumeTrue(...) (skips the failing path)"),
    (re.compile(r"\bassumeThat\s*\("), "adds assumeThat(...) (skips the failing path)"),
    (re.compile(r"\bassumeNoException\s*\("), "adds assumeNoException(...) (skips the failing path)"),
    (re.compile(r"@Ignore\b"), "adds @Ignore (disables the test)"),
]

# Assertion-bearing call patterns, counted on added vs removed lines to detect net assertion deletion.
_ASSERTION = re.compile(r"\b(assert[A-Z]\w*|assertThat|assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|fail)\s*\(")


def _parse_diff(patch_text: str):
    """Yield (file_path, sign, content) for each +/- body line, tracking the current target file from +++ headers."""
    current = None
    for line in patch_text.splitlines():
        if line.startswith("+++ "):
            target = line[4:].strip()
            current = target[2:] if target.startswith("b/") else target
            continue
        if line.startswith("--- ") or line.startswith("+++"):
            continue
        if line.startswith("+") and line.startswith("+++") is False:
            yield current, "+", line[1:]
        elif line.startswith("-") and line.startswith("---") is False:
            yield current, "-", line[1:]


def _untracked(status_text: str) -> set[str]:
    """Extract untracked paths ("?? path") from porcelain status, ignoring the prototype's own and build output."""
    out = set()
    for line in status_text.splitlines():
        if line.startswith("?? "):
            path = line[3:].strip().strip('"')
            if path.startswith(_IGNORED_UNTRACKED_PREFIXES) is False:
                out.add(path)
    return out


def check(patch_text: str, baseline_status: str, current_status: str) -> dict:
    violations: list[str] = []

    if patch_text.strip() == "":
        violations.append("patch is empty (no tracked changes - nothing was fixed)")

    new_untracked = _untracked(current_status) - _untracked(baseline_status)
    if new_untracked:
        violations.append(
            "fix created new untracked file(s), which the PR would omit: " + ", ".join(sorted(new_untracked))
        )

    added_assertions = 0
    removed_assertions = 0
    changed_fix_lines = 0  # added + removed body lines outside muted-tests.yml; this is the real size of the fix
    for path, sign, content in _parse_diff(patch_text):
        if not (path and path.endswith("muted-tests.yml")):
            changed_fix_lines += 1
        if sign == "+":
            for pattern, message in _DENY_ADDED:
                if pattern.search(content):
                    violations.append(f"{path}: {message}")
            # Re-muting: any ADDED line in muted-tests.yml re-disables a test. Removals (unmuting) are allowed.
            if path and path.endswith("muted-tests.yml") and content.strip():
                violations.append("muted-tests.yml: adds a mute entry (re-mutes the test)")
            if _ASSERTION.search(content):
                added_assertions += 1
        elif sign == "-":
            if _ASSERTION.search(content):
                removed_assertions += 1

    if removed_assertions > added_assertions:
        violations.append(
            f"net deletion of assertions ({removed_assertions} removed, {added_assertions} added) - likely weakening the test"
        )

    if changed_fix_lines > _MAX_FIX_LINES:
        violations.append(
            f"fix changes {changed_fix_lines} lines (excluding muted-tests.yml), over the {_MAX_FIX_LINES}-line cap - a "
            "change this large suggests a deeper issue that needs human input, not autonomous handling"
        )

    return {"clean": len(violations) == 0, "violations": violations}


def main() -> int:
    if len(sys.argv) != 4:
        print(json.dumps({"clean": False, "violations": ["usage: diff_guard.py <patch> <baseline_status> <current_status>"]}))
        return 1
    patch_text = Path(sys.argv[1]).read_text(encoding="utf-8")
    baseline = Path(sys.argv[2]).read_text(encoding="utf-8")
    current = Path(sys.argv[3]).read_text(encoding="utf-8")
    verdict = check(patch_text, baseline, current)
    print(json.dumps(verdict, indent=2))
    return 0 if verdict["clean"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
