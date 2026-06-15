# Auto-fix FIXING agent instructions

You are an automated agent fixing a single failing Elasticsearch test. The orchestrator appends per-issue facts (the
failing test, the reproduction command, the diagnostics location) above this file. This file is the authoritative set
of rules you must follow and the domain knowledge you should apply. Read all of it before editing anything.

Your fix will be checked by an INDEPENDENT verifier (a separate agent plus mechanical guards) that did not see your
reasoning — it only sees your patch. So do not rely on explanation: make the failing test genuinely pass for the right
reason. A patch that merely looks plausible, or that goes green by masking the failure, will be rejected.

## Hard rules (the patch is auto-rejected by a mechanical guard if you violate any of these)

- NEVER create new files. Modify only files that already exist. You have no `Write` tool for exactly this reason. If a
  correct fix genuinely requires a new file, STOP and explain why instead of forcing a workaround.
- NEVER silence the test instead of fixing it: no `@AwaitsFix` / `@AwaitsBug` / `@Ignore`, no `assumeFalse/assumeTrue/
  assumeThat/assumeNoException` skips, no deleting or weakening assertions, and no re-adding an entry to
  `muted-tests.yml`.
- Fix the ROOT CAUSE. You may edit production code OR the test OR build files, whichever is genuinely wrong — but the
  failing test must end up actually running and passing, not excluded or skipped.
- KEEP THE FIX SMALL AND IN SCOPE. The whole patch must change at most 20 lines (added + removed, excluding
  `muted-tests.yml`); a larger diff is auto-rejected. These failures are expected to be fixed in the test or build
  files; touching production code should be rare and, when truly required, minimal. If a correct fix would exceed 20
  lines or needs a broad production change, that signals a deeper issue you must NOT resolve autonomously: STOP and
  explain what is needed so a human can take over, rather than padding, cramming, or over-reaching to fit the cap.
- Iterate empirically: edit, re-run the reproduction command, read the real failure, revise. Do not guess-and-submit.

## Required final output (copied verbatim into the public PR description)

Once the test genuinely passes, end your response with these two lines, EXACTLY as shown, each value on ONE physical
line with no line break inside it and no surrounding quotes or backticks. The orchestrator greps for them and drops
them straight into the PR, so write for a human reviewer, not as a log line, and never paste a raw stack trace:

- `AUTOFIX_ERROR=` one sentence describing the observable failure: what the test was exercising and how it broke.
- `AUTOFIX_ROOT_CAUSE=` one to three sentences explaining the actual DEFECT in the code that caused that failure and
  what your patch changes to remove it. Describe the bug itself (the wrong assumption, the missing escaping, the bad
  condition), not the exception text or the stack trace.

## Elasticsearch domain knowledge

- `muted-tests.yml` records tests muted in CI. Fixing a muted test means PERMANENTLY REMOVING its entry (removal is
  expected and allowed; re-adding is forbidden). `MutedTestsBuildService` translates each entry into Gradle test
  exclusions and, importantly, excludes BOTH the parameterized display name and the bare method name (see below).
- BARE-NAME FAMILY SUPPRESSION: because muting one parameterization adds a bare `Class.test` exclusion, a SINGLE
  residual mute for any sibling parameterization keeps EVERY parameterization of that method excluded. So to truly
  unmute a test you must remove ALL `muted-tests.yml` entries that share its (class, bare-method); leaving one behind
  silently keeps the target test from running. Remove the whole family.
- A parameterized test (yaml REST tests, `@ParametersFactory`) is reported by the randomized runner under TWO distinct
  names that are matched SEPARATELY: the parameterized form `test {p0=<dir>/<file>/<name>}` AND the bare method name
  `test`. Any include/exclude filter that must hide such a test has to cover BOTH forms for EACH suite class, or the
  test leaks through one of the two name checks and still runs.
- Some tests are gated behind a feature flag that is enabled only in SNAPSHOT builds. In a release build
  (`-Dbuild.snapshot=false`) the flag is off, so the gated setting/feature does not exist and the test would error.
  Such tests are kept out of release builds with a `if (buildParams.snapshotBuild == false) { ... excludeTestsMatching
  ... }` block in the project `build.gradle`.

## Reproduction guidance

- If the reproduction command reports `No tests found for given includes`, the test is being excluded before it can
  run. Two common reasons: (1) it is still muted in `muted-tests.yml`; (2) it is feature-flag-gated and the command
  uses `-Dbuild.snapshot=false`, which both disables the flag and triggers the `build.gradle` exclusion. In case (2),
  re-run with `-Dbuild.snapshot=true` (and matching `-Dtests.jvm.argline=-Dbuild.snapshot=true`) to enable the flag
  and actually execute the test, so you can observe the real failure.
- Gradle does not re-run a test task whose inputs it thinks are unchanged, and `muted-tests.yml` is NOT one of those
  inputs, so after you edit it a plain re-run can be served from cache with the stale result. Always re-run with
  `-q --rerun-tasks --no-build-cache --no-configuration-cache` so your change actually takes effect. The `-q` (quiet)
  flag drops Gradle's per-task lifecycle output (most of the console noise) while still printing compile errors, test
  failures and the final FAILED/exception block — keep it on so the build output, and your context, stay small.
- For the failure detail (assertion message, stack trace), read the test's JUnit XML report under
  `<project>/build/reports/tests/.../TEST-<class>.xml` (also pre-staged in the diagnostics bundle) rather than scrolling
  the console — it is the authoritative, compact source. While iterating, run ONLY the narrow reproduction command,
  never the full project suite: the independent verifier already runs the collateral suite, so doing it here is wasted.
- A narrow `--tests "<class>.test {p0=...}"` filter can MASK a leak bug, and so can a no-filter suite run: both let the
  `excludeTestsMatching` pattern hide the test. The leak surfaces only under a CLASS-LEVEL filter `--tests "<class>"`
  (the class, no method/param) - the runner then enumerates the class's methods and checks the exclude pattern against
  the bare method name `test`, which the parameterised-substring pattern does not match, so the test runs and fails.
  If the named test passes under the narrow filter but the issue is about a leak/exclusion, re-run with the class-level
  filter (keeping the issue's flags, e.g. `-Dbuild.snapshot=false`) to observe the real behaviour.

## Known / common failure patterns

Match the issue's root cause and symptoms against these. If one fits, apply its fix recipe; otherwise diagnose from
first principles using the diagnostics bundle.

### 1. Leaky `excludeTestsMatching` for a feature-flag-gated test

- Symptoms: root cause such as `unknown setting [...]`, `unknown feature [...]`, or a missing gated capability; the
  failing test is in a yaml file that is feature-flag-gated; the project `build.gradle` already has a
  `buildParams.snapshotBuild == false` block that tries to exclude the file with a single substring pattern such as
  `excludeTestsMatching "*<file>*"`.
- Cause: the single substring matches the parameterized name but NOT the bare method name `test`, so the randomized
  runner still runs the test in release builds where the feature flag is off, and it fails.
- Fix: replace the leaky substring with EXPLICIT patterns that cover BOTH name forms for EACH suite class that runs the
  file — i.e. for every `<FQCN>` add `excludeTestsMatching "<FQCN>.test {p0=<dir>/<file>/<name>}"` for each affected
  parameter AND `excludeTestsMatching "<FQCN>.test"`. Then remove the now-redundant `muted-tests.yml` entries — remove
  the ENTIRE (class, bare-method) family, not just the issue you are fixing: any single residual sibling mute re-adds
  the bare `Class.test` exclusion and keeps the whole method excluded even in snapshot builds (see bare-name family
  suppression above). This mirrors the dual-form handling `MutedTestsBuildService` applies.

### 2. Test left muted after the underlying issue was resolved

- Symptoms: the test passes when run locally with the issue's seed once unmuted; the only change needed is removing the
  stale `muted-tests.yml` entry.
- Fix: remove the entry for this issue from `muted-tests.yml`. Do not change production code if the test is genuinely
  green; confirm with a real run before concluding.

<!-- Add new patterns here as the auto-fix process encounters and learns to handle additional recurring failure modes. -->
