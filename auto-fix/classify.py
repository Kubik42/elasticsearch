#!/usr/bin/env python3
"""Step 2 classification: decide REPRODUCED / NOT_REPRODUCED / FAILURE strictly from the JUnit XML of the named test.

Gradle writes ``TEST-<fqcn>.xml`` per test class under ``build/test-results/<task>/``. Each test is a ``<testcase>``
whose ``name`` attribute is exactly the method descriptor we ran (e.g. ``test {p0=.../Downsample mixed...}``). A failed
run carries a child ``<failure>`` or ``<error>``. We key on the *exact* class+method we asked gradle to run, never on
the issue title (which can disagree with the reproduction line), and we trust the XML over the process exit code.

Outcomes:
  * REPRODUCED      - the named testcase exists and has a <failure>/<error> child.
  * NOT_REPRODUCED  - the named testcase exists and passed.
  * NOT_FOUND       - no XML for the class, or the class XML lacks the named method. The caller decides whether this is
                      a mute (remediate + retry) or a genuine FAILURE; this module does not see gradle's console.
The extracted failure message + stacktrace are emitted for the diagnostics root-cause hint.
"""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def _find_class_xml(results_root: Path, fqcn: str) -> Path | None:
    """Locate ``TEST-<fqcn>.xml`` anywhere under the results root (gradle nests it by task; we search defensively)."""
    target = f"TEST-{fqcn}.xml"
    for path in results_root.rglob(target):
        return path
    return None


def classify(results_root: Path, fqcn: str, method: str) -> dict:
    xml_path = _find_class_xml(results_root, fqcn)
    if xml_path is None:
        return {"outcome": "NOT_FOUND", "reason": f"no TEST-{fqcn}.xml under {results_root}"}

    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as exc:
        return {"outcome": "FAILURE", "reason": f"could not parse {xml_path}: {exc}"}

    # A testsuite contains testcases; the suite element itself may be the root or nested under <testsuites>.
    cases = root.iter("testcase")
    for case in cases:
        if case.get("name") != method:
            continue
        failure = case.find("failure")
        error = case.find("error")
        problem = failure if failure is not None else error
        if problem is None:
            return {"outcome": "NOT_REPRODUCED", "xml": str(xml_path)}
        message = (problem.get("message") or "").strip()
        detail = (problem.text or "").strip()
        return {
            "outcome": "REPRODUCED",
            "xml": str(xml_path),
            "failure_type": problem.get("type") or problem.tag,
            "failure_message": message,
            "failure_detail": detail[:4000],
        }

    return {"outcome": "NOT_FOUND", "reason": f"class XML present but no testcase named {method!r}", "xml": str(xml_path)}


def main() -> int:
    if len(sys.argv) != 4:
        print(json.dumps({"outcome": "FAILURE", "reason": "usage: classify.py <results_root> <fqcn> <method>"}))
        return 2
    result = classify(Path(sys.argv[1]), sys.argv[2], sys.argv[3])
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
