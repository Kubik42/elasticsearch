#!/usr/bin/env python3
"""Step 2 security boundary: parse a bot test-failure issue body into a validated, exec-ready command.

The issue body is UNTRUSTED structured input. This module never lets it reach a shell. It extracts the fenced
``Reproduction Line``, tokenises it with ``shlex`` (quote-aware, *no* execution), validates every token against a
strict allow-list, and emits an explicit ``argv`` array. The caller execs that array directly (no ``bash -c``), so
even a maliciously crafted body cannot inject commands. Anything that fails validation yields ``valid=false`` with a
reason, which the caller maps to the FAILURE outcome.

Output (stdout): a single JSON object. On success::

    {"valid": true, "argv": [...], "task_path": ":x:y:task", "test_class": "...", "test_method": "...",
     "gated_flags": {...}, "passthrough_flags": {...}, "applicable_branches": ["main"]}

On rejection: ``{"valid": false, "reason": "..."}``.
"""

from __future__ import annotations

import json
import re
import shlex
import sys

# -D flags whose presence forces an environment pre-flight (the runner must genuinely support them; never substitute).
# Mapping each to a human label keeps reproduce.sh's gate messages readable.
GATED_FLAGS = {
    "runtime.java": "JDK toolchain",
    "build.snapshot": "release-tests build config",
    "tests.jvm.argline": "release-tests jvm argline",
    "license.key": "x-pack license key file",
}

# -D flags passed through verbatim and never gated: the bot must not transition to FAILURE if its environment differs
# in locale/timezone/seed/iteration count. These are reproduction inputs, not environment capabilities. tests.class and
# tests.bwc belong here too: they select/scope the run (the bwc mixed-cluster form names the test via tests.class +
# tests.method and flips on backwards-compat mode) rather than asserting a local capability the runner must possess.
PASSTHROUGH_FLAGS = {
    "tests.seed",
    "tests.iters",
    "tests.locale",
    "tests.timezone",
    "tests.method",
    "tests.class",
    "tests.bwc",
}

# Shell-dangerous fragments rejected defensively in values. Exec is argv-direct so these are already inert, but a body
# carrying them signals tampering rather than a genuine CI-generated reproduction line, so we fail closed.
_DANGEROUS = ("`", "$(", ";", "|", "&", ">", "<", "\n", "\r", "\x00", "\\")

# Section header -> the text that follows it, captured up to the next blank-line-delimited bold header or fence.
_REPRO_BLOCK = re.compile(r"\*\*Reproduction Line:\*\*\s*\n```\s*\n(?P<body>.*?)\n```", re.DOTALL)
_BRANCHES = re.compile(r"\*\*Applicable branches:\*\*\s*\n(?P<body>.*?)(?:\n\s*\n|\n\*\*|\Z)", re.DOTALL)

# A fully-qualified test name: dotted package segments, an uppercase-initial class (optionally nested), then the method
# identifier, then an optional JUnit parameter descriptor in braces (``{p0=...}`` / ``{yaml=...}``).
_TEST_NAME = re.compile(
    r"^(?P<fqcn>(?:[A-Za-z_$][\w$]*\.)*[A-Z][\w$]*)\.(?P<method>[A-Za-z_$][\w$]*(?: \{.*\})?)$"
)
# A class-only fully-qualified name and a bare method name: the split form `--tests <FQCN> -Dtests.method=<method>` that
# ES|QL spec and other parameterized suites emit, where the method (with its `{...}` descriptor) rides in -Dtests.method.
_FQCN = re.compile(r"^(?:[A-Za-z_$][\w$]*\.)*[A-Z][\w$]*$")
_METHOD = re.compile(r"^[A-Za-z_$][\w$]*(?: \{.*\})?$")
# A gradle task path: leading colon, dotted/dashed segments. `#` separates the project path from a versioned task name
# in the bwc form (e.g. `:qa:mixed-cluster:v9.3.5#mixedClusterTest`), so it is permitted; inert under argv-direct exec.
_TASK_PATH = re.compile(r"^:[A-Za-z0-9:._#-]+$")

# Trailing ``/issues/<number>`` of a muted-tests.yml ``issue:`` URL, used to bind a muted entry to a discovered issue.
_MUTED_ISSUE = re.compile(r"/issues/(\d+)\s*$")


def _reject(reason: str) -> dict:
    return {"valid": False, "reason": reason}


def _has_dangerous(value: str) -> bool:
    return any(frag in value for frag in _DANGEROUS)


def _extract_branches(body: str) -> list[str]:
    m = _BRANCHES.search(body)
    if m is None:
        return []
    return [line.strip() for line in m.group("body").splitlines() if line.strip()]


def parse(body: str) -> dict:
    """Parse an issue body into a validated command spec, or a rejection. Pure function for straightforward testing."""
    block = _REPRO_BLOCK.search(body)
    if block is None:
        return _reject("no fenced Reproduction Line block found")
    line = block.group("body").strip()
    # Accept both invocation forms: the canonical `./gradlew` and a bare `gradlew` (some CI repro lines omit the `./`).
    # argv is rebuilt with `./gradlew` below, so the wrapper is always run from the worktree root regardless of input.
    if line.startswith("./gradlew") is False and line.startswith("gradlew") is False:
        return _reject("reproduction line does not start with ./gradlew or gradlew")

    try:
        tokens = shlex.split(line)
    except ValueError as exc:
        return _reject(f"reproduction line is not well-formed shell: {exc}")
    if len(tokens) < 4 or tokens[0] not in ("./gradlew", "gradlew"):
        return _reject("reproduction line too short or malformed")

    task_path = None
    test_value = None
    gated_flags: dict[str, str] = {}
    passthrough_flags: dict[str, str] = {}

    i = 1
    while i < len(tokens):
        tok = tokens[i]
        if tok == "--tests":
            if test_value is not None:
                return _reject("more than one --tests argument")
            if i + 1 >= len(tokens):
                return _reject("--tests has no value")
            test_value = tokens[i + 1]
            i += 2
            continue
        if tok.startswith(":"):
            if task_path is not None:
                return _reject("more than one gradle task path")
            if _TASK_PATH.match(tok) is None:
                return _reject(f"task path has unexpected characters: {tok!r}")
            task_path = tok
            i += 1
            continue
        if tok.startswith("-D"):
            kv = tok[2:]
            key, _, value = kv.partition("=")
            if key in GATED_FLAGS:
                gated_flags[key] = value
            elif key in PASSTHROUGH_FLAGS:
                passthrough_flags[key] = value
            else:
                return _reject(f"-D flag not in allow-list: {key!r}")
            if _has_dangerous(value):
                return _reject(f"-D flag value contains shell metacharacters: {tok!r}")
            i += 1
            continue
        return _reject(f"unrecognised token: {tok!r}")

    if task_path is None:
        return _reject("no gradle task path found")

    method_flag = passthrough_flags.get("tests.method")
    class_flag = passthrough_flags.get("tests.class")
    # Three accepted ways to name the failing test, each resolved to (test_class, test_method) plus a select_via marker
    # that tells reproduce.sh which gradle selection mechanism to drive. classify.py keys the JUnit <testcase name> off
    # test_method, so for the split / bwc forms the -Dtests.method value (e.g. `test {p0=...}`) is the testcase name verbatim.
    #   1. --tests <FQCN>.<method>                            -> combined name in the gradle --tests filter
    #   2. --tests <FQCN> + -Dtests.method=<method>           -> split form ES|QL spec / parameterized suites emit
    #   3. -Dtests.class=<FQCN> + -Dtests.method=<method>     -> the bwc / mixed-cluster form (no --tests), whose
    #      StandaloneRestIntegTestTask selects by system property rather than the --tests filter.
    if test_value is not None and class_flag is not None:
        return _reject("both --tests and -Dtests.class name a test; conflicting test identity")
    if test_value is not None:
        if _has_dangerous(test_value):
            return _reject("--tests value contains shell metacharacters")
        name_match = _TEST_NAME.match(test_value)
        if name_match is not None:
            if method_flag is not None:
                return _reject("--tests already names a method but -Dtests.method is also set; conflicting test identity")
            test_class = name_match.group("fqcn")
            test_method = name_match.group("method")
        elif _FQCN.match(test_value) is not None and method_flag is not None:
            if _METHOD.match(method_flag) is None:
                return _reject(f"-Dtests.method is not a well-formed method name: {method_flag!r}")
            test_class = test_value
            test_method = method_flag
        else:
            return _reject(f"--tests value is not a well-formed test name: {test_value!r}")
        select_via = "--tests"
    elif class_flag is not None:
        if _FQCN.match(class_flag) is None:
            return _reject(f"-Dtests.class is not a well-formed class name: {class_flag!r}")
        if method_flag is None:
            return _reject("-Dtests.class is set without -Dtests.method; cannot identify a single test method to classify")
        if _METHOD.match(method_flag) is None:
            return _reject(f"-Dtests.method is not a well-formed method name: {method_flag!r}")
        test_class = class_flag
        test_method = method_flag
        select_via = "-Dtests.class"
    else:
        return _reject("no test selection found (need --tests or -Dtests.class)")

    # Rebuild argv deterministically from validated components rather than trusting the original token order. The
    # --tests filter (when used) leads; gated and passthrough flags - including the -Dtests.class/-Dtests.method that
    # carry selection in the bwc form - are re-emitted with their original key=value text so gradle receives them unchanged.
    argv = ["./gradlew", task_path]
    if test_value is not None:
        argv += ["--tests", test_value]
    for key, value in {**gated_flags, **passthrough_flags}.items():
        argv.append(f"-D{key}={value}" if value != "" else f"-D{key}")

    return {
        "valid": True,
        "argv": argv,
        "task_path": task_path,
        "test_class": test_class,
        "test_method": test_method,
        "select_via": select_via,
        "gated_flags": gated_flags,
        "passthrough_flags": passthrough_flags,
        "applicable_branches": _extract_branches(body),
    }


def _muted_entries_for_issue(muted_text: str, number: str) -> list[dict]:
    """Return the muted-tests.yml entries (class/method) bound to the given issue number. Block-aware and line-based so
    PyYAML stays out of the dependency set (mirrors unmute.py). Entries without a method (whole-class mutes) are kept;
    the caller decides how to treat them."""
    entries: list[dict] = []
    cur: dict = {}

    def flush() -> None:
        nonlocal cur
        if cur.get("issue_number") == number and "class" in cur:
            entries.append(cur)
        cur = {}

    for raw in muted_text.splitlines():
        line = raw
        if line.startswith("- "):
            flush()
            line = line[2:]
        stripped = line.strip()
        if stripped.startswith("class:"):
            cur["class"] = stripped[len("class:") :].strip()
        elif stripped.startswith("method:"):
            cur["method"] = stripped[len("method:") :].strip()
        elif stripped.startswith("issue:"):
            m = _MUTED_ISSUE.search(stripped)
            if m is not None:
                cur["issue_number"] = m.group(1)
    flush()
    return entries


def reconcile(result: dict, muted_text: str, number: str) -> dict:
    """Cross-check the reproduction line's test identity against the muted-tests.yml entry bound to the issue number.

    The reproduction command line is a *generated* artifact and can disagree with the rest of the issue (observed on
    batch-filed bot issues whose repro lines were crossed). The muted-tests.yml entry keyed to the issue number is the
    canonical identity - it is exactly what the team muted *for this issue* - so on a single-method conflict we prefer
    it, rebuild ``--tests`` accordingly, and record a warning. Ambiguous (multiple methods) or whole-class mutes leave
    the reproduction line untouched. Reconciliation only narrows to the right test; the command flags are unchanged."""
    if result.get("valid") is False:
        return result
    method_entries = [e for e in _muted_entries_for_issue(muted_text, number) if "method" in e]
    if len(method_entries) != 1:
        result["reconciled"] = False
        return result
    entry = method_entries[0]
    if entry["class"] == result["test_class"] and entry["method"] == result["test_method"]:
        result["reconciled"] = False
        return result
    candidate = f"{entry['class']}.{entry['method']}"
    if _has_dangerous(candidate) or _TEST_NAME.match(candidate) is None:
        result["reconciled"] = False
        result["reconcile_warning"] = f"muted-tests.yml entry for #{number} is not a well-formed test name; kept the reproduction line"
        return result
    result["reconcile_warning"] = (
        f"reproduction line named {result['test_class']}.{result['test_method']} but muted-tests.yml for #{number} "
        f"names {candidate}; preferring the muted entry (the canonical identity for this issue)"
    )
    result["test_class"] = entry["class"]
    result["test_method"] = entry["method"]
    # Keep every place the test identity appears in sync with the reconciled values. The combined --tests filter carries
    # the full CLASS.method; the split / bwc forms carry the class and method in -Dtests.class / -Dtests.method, which
    # reproduce.sh reads from passthrough_flags to build its attempts, so update both the flags and their argv tokens.
    pf = result.get("passthrough_flags", {})
    if "tests.class" in pf:
        pf["tests.class"] = entry["class"]
    if "tests.method" in pf:
        pf["tests.method"] = entry["method"]
    argv = result.get("argv", [])
    for idx, tok in enumerate(argv):
        if tok == "--tests" and idx + 1 < len(argv):
            argv[idx + 1] = candidate
        elif tok.startswith("-Dtests.class="):
            argv[idx] = f"-Dtests.class={entry['class']}"
        elif tok.startswith("-Dtests.method="):
            argv[idx] = f"-Dtests.method={entry['method']}"
    result["reconciled"] = True
    return result


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(json.dumps(_reject("usage: parse_repro.py <issue.json> [muted-tests.yml]")))
        return 2
    with open(sys.argv[1], encoding="utf-8") as fh:
        issue = json.load(fh)
    body = issue.get("body") or ""
    result = parse(body)
    if len(sys.argv) == 3 and result.get("valid"):
        number = str(issue.get("number") or "")
        with open(sys.argv[2], encoding="utf-8") as fh:
            muted_text = fh.read()
        result = reconcile(result, muted_text, number)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
