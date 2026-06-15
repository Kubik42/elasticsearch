#!/usr/bin/env python3
"""Remove muted-tests.yml entries. Used twice: step 2 unmutes to reproduce, step 3's fix removes the mute permanently.

muted-tests.yml is a list of entries under ``tests:``; each entry is a block beginning ``- class:`` and running until
the next ``- `` or EOF, carrying ``class``/optional ``method``/``issue`` lines. Line-based block handling avoids a
PyYAML dependency and preserves the file's exact formatting.

Two modes:
  unmute.py <muted-tests.yml> <issue_number_or_url>      -> remove every block whose ``issue:`` references the number
                                                            (robust to http/https and trailing slash); prints the count.
  unmute.py <muted-tests.yml> --family <class> <method>  -> remove every block for the SAME (class, bare-method) family
                                                            as the target test; prints the removed issue numbers, one
                                                            per line. This is required because MutedTestsBuildService
                                                            mutes a parameterized test by ALSO excluding the bare method
                                                            name (e.g. ``Class.test``), so a single residual sibling
                                                            mute suppresses EVERY parameterization of that method - the
                                                            target test stays hidden until the whole family is unmuted.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


def _issue_number(token: str) -> str:
    m = re.search(r"(\d+)", token)
    if m is None:
        raise SystemExit(f"could not extract an issue number from {token!r}")
    return m.group(1)


def remove_entries(text: str, issue_number: str) -> tuple[str, int]:
    """Return (new_text, removed_count). A block runs from a ``- `` list item to the line before the next ``- ``."""
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    removed = 0
    i = 0
    n = len(lines)
    issue_pat = re.compile(rf"issues/{re.escape(issue_number)}(?:\b|/|$)")
    while i < n:
        line = lines[i]
        # A list entry begins with optional indent then "- ". Collect the whole block to inspect its issue: line.
        if re.match(r"^\s*-\s", line):
            block = [line]
            j = i + 1
            while j < n and re.match(r"^\s*-\s", lines[j]) is None and lines[j].strip() != "":
                block.append(lines[j])
                j += 1
            block_text = "".join(block)
            if "issue:" in block_text and issue_pat.search(block_text):
                removed += 1
            else:
                out.extend(block)
            i = j
        else:
            out.append(line)
            i += 1
    return "".join(out), removed


def _bare_method(method: str) -> str:
    """Strip the randomized-runner parameter suffix: ``test {p0=...}`` -> ``test``. The bare name is what triggers the
    family-wide bare-name exclusion in MutedTestsBuildService, so it is the key we cluster a mute family on."""
    idx = method.find(" {")
    return (method[:idx] if idx >= 0 else method).strip()


def remove_family(text: str, class_name: str, method: str) -> tuple[str, list[str]]:
    """Return (new_text, removed_issue_numbers). Remove every block whose ``class`` equals ``class_name`` and whose
    ``method``'s bare name equals the target method's bare name (so all parameterizations of that method are unmuted)."""
    bare = _bare_method(method)
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    removed: list[str] = []
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]
        if re.match(r"^\s*-\s", line):
            block = [line]
            j = i + 1
            while j < n and re.match(r"^\s*-\s", lines[j]) is None and lines[j].strip() != "":
                block.append(lines[j])
                j += 1
            block_text = "".join(block)
            cls = re.search(r"^\s*(?:-\s*)?class:\s*(\S+)", block_text, re.MULTILINE)
            mth = re.search(r"^\s*method:\s*(.+?)\s*$", block_text, re.MULTILINE)
            if cls and mth and cls.group(1) == class_name and _bare_method(mth.group(1)) == bare:
                issue = re.search(r"issues/(\d+)", block_text)
                if issue:
                    removed.append(issue.group(1))
            else:
                out.extend(block)
            i = j
        else:
            out.append(line)
            i += 1
    return "".join(out), removed


def main() -> int:
    if len(sys.argv) == 5 and sys.argv[2] == "--family":
        path = Path(sys.argv[1])
        new_text, removed = remove_family(path.read_text(encoding="utf-8"), sys.argv[3], sys.argv[4])
        if removed:
            path.write_text(new_text, encoding="utf-8")
        for number in removed:
            print(number)
        return 0
    if len(sys.argv) != 3:
        print("usage: unmute.py <muted-tests.yml> <issue_number_or_url> | --family <class> <method>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    number = _issue_number(sys.argv[2])
    new_text, removed = remove_entries(path.read_text(encoding="utf-8"), number)
    if removed:
        path.write_text(new_text, encoding="utf-8")
    print(removed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
