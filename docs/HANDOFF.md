# Latest handoff

Last updated: 2026-07-29

Branch: `codex/quick-log-test-hardening`

Repository position: `HEAD`, `origin/main`, and peeled tag `v1.2.15` all point
to source commit `e19dfbe4a908ce4ec152380e6ecb053a2e6e3f8a`.

Working tree status: six modified tracked files for the focused quick-log
test-hardening change, plus 11 pre-existing untracked `job_*.txt` GitHub Actions
log downloads. Nothing was staged when this handoff was refreshed.

## Purpose of this session

Finish and independently verify the Cannsheet Mobile `1.2.15` release after a
long sequence of GitHub Actions failures, then preserve the exact failure
lessons and a safer validation/tagging sequence so future releases do not use
`main` and a release tag as a trial-and-error test loop. Implement the focused
test and CI hardening that prevents the same failure pattern.

## Work completed

- GitHub Actions main validation run
  [30467643070](https://github.com/noamvb/cannsheet-mobile/actions/runs/30467643070)
  completed successfully for exact source SHA `e19dfbe`:
  - repository classification and security scan passed;
  - all checked-in backend tests passed;
  - Android unit tests, lint, and debug APK assembly passed;
  - instrumentation tests passed on API 24 and API 36;
  - required job `Cannsheet Android PR validation` passed.
- Signed release run
  [30467644284](https://github.com/noamvb/cannsheet-mobile/actions/runs/30467644284)
  completed successfully:
  - exact-SHA main validation proof passed;
  - unit tests, lint, and signed release assembly passed;
  - signing, package, version, and monotonic version-code checks passed;
  - the public APK and checksum were published and post-publication checks
    passed.
- Public release
  [v1.2.15](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.15)
  is the latest Cannsheet Mobile release.
- The repetitive failure sequence was traced to
  `app/src/androidTest/java/com/example/ui/QuickLogQuantityEditorTest.kt`, not
  to the production release build:
  - instrumentation-test compilation was not part of the faster static Android
    job, so missing Compose test imports (`onAllNodes`, then
    `verticalScroll`) were found only after an emulator had started;
  - selectors sometimes targeted the existing third text field instead of the
    newly added fourth field, producing `42.0` instead of separate `2.0` and
    `4.0` values;
  - the disabled remove-button assertion initially used the wrong merged versus
    unmerged Compose semantics view;
  - dynamically added UI nodes were queried before Compose had settled;
  - lower controls were off-screen on the smaller API 24 emulator viewport.
- The final passing test uses a scrollable test container, waits for Compose to
  become idle after adding a preset, and now targets stable test tags rather
  than the final editable node or visible button text.
- The follow-up hardening is implemented on
  `codex/quick-log-test-hardening`:
  - `QuickLogQuantityEditor` exposes stable tags for preset inputs, remove
    buttons, Add, and Save;
  - the Compose suite keeps one user-level add-and-save smoke test;
  - duplicate minimum/maximum UI assertions were removed because the same rules
    are covered more quickly by JVM tests;
  - the JVM preset-validation test was split into clear valid-count,
    invalid-count, and invalid-value cases;
  - `android-static` now runs `compileDebugAndroidTestKotlin` before emulator
    jobs, catching missing instrumentation-test imports early.

## Required prevention checklist

Use this order for future Android UI changes and releases:

1. Develop and review the change on a focused pull-request branch. Do not fix
   new test failures by repeatedly pushing experimental commits directly to
   `main`.
2. Before merging, run the fast local checks:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
   .\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
   .\gradlew.bat --no-daemon compileDebugAndroidTestKotlin
   ```

   The second command is important: it catches missing instrumentation-test
   imports without waiting for an emulator.
3. When an emulator is available, run the affected instrumentation test class
   before the full suite:

   ```powershell
   .\gradlew.bat --no-daemon connectedDebugAndroidTest `
     -Pandroid.testInstrumentationRunnerArguments.class=com.example.ui.QuickLogQuantityEditorTest
   ```

4. For changes to Compose instrumentation tests, manually dispatch
   `Cannsheet PR checks` on the feature branch before merge. A manual dispatch
   runs both API 24 and API 36, while an ordinary Android pull request currently
   runs API 24.
5. After merge, push the source commit to `main` and wait for the exact
   push-to-main run to finish. Confirm that API 24, API 36, Android static,
   backend, and `Cannsheet Android PR validation` are all successful.
6. Only after that exact `main` SHA is green should a release tag be created
   and pushed. Push the tag once. Do not use release tags or no-op “kick”
   commits to probe whether tests pass, and never move a tag that already
   produced a public artifact.

For Compose tests specifically:

- Prefer stable `Modifier.testTag(...)` contracts for editable fields and
  buttons instead of relying on text-field order, `onLast()`, or translated
  visible text.
- Call `waitForIdle()` after clicks that add, remove, or otherwise replace
  semantics nodes.
- Inspect the actual merged and unmerged semantics trees before choosing a
  selector; do not guess which tree exposes a node.
- Put long test content in a scrollable or appropriately sized container so
  controls remain reachable on the API 24 boundary device.
- Treat a compile error differently from a runtime assertion failure. Fix and
  locally compile the entire instrumentation source set before triggering
  another emulator matrix.

## Implemented CI and test hardening

1. `.github/workflows/android-pr-checks.yml` now runs
   `compileDebugAndroidTestKotlin` in `android-static`.
2. `QuickLogQuantityEditorTest` uses stable test tags and contains one focused
   add-and-save smoke test.
3. Fast JVM tests retain the 1-to-10, positive, finite, and distinct-value
   coverage with clearer failure names.
4. API 24 pull-request coverage and API 24/API 36 main/manual-dispatch coverage
   remain unchanged.

## Files changed by this task

- `.github/workflows/android-pr-checks.yml`
- `app/src/main/java/com/example/ui/SettingsScreen.kt`
- `app/src/androidTest/java/com/example/ui/QuickLogQuantityEditorTest.kt`
- `app/src/test/java/com/example/data/ConsumptionPreferencesRepositoryTest.kt`
- `docs/PROJECT_STATE.md`
- `docs/HANDOFF.md`

No version, signing, endpoint, package, application ID, environment, Room,
backend, Apps Script, or Google Sheets contract was changed.

## Current project and release state

- Source version: `versionName` `1.2.15`, `versionCode` `18`
- Previous public version code: `17` in `1.2.14`
- Application ID: `com.noamv.cannsheet.mobile`
- Public APK:
  `Cannsheet-Mobile-1.2.15.apk`
- Public checksum:
  `c5eadf90c52a5a1637283c569bb32d34f6f84cbf33b86321815d57baaf8c89e3`
- APK signing verification: APK Signature Scheme v2 passed with one signer;
  signer SHA-256 is
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`.
- The public release has exactly one custom `.apk` asset and its `.sha256`
  file; GitHub also displays its automatic source archives.

## Validation performed

Passing live GitHub evidence:

- Main validation run `30467643070`: success for SHA `e19dfbe`, including API
  24 and API 36 instrumentation tests.
- Release run `30467644284`: success for SHA `e19dfbe`, including signed build,
  publication, and post-publication verification.

Passing independent public-artifact checks performed after publication:

- downloaded APK SHA-256 matched the published `.sha256` file;
- `aapt dump badging` reported package
  `com.noamv.cannsheet.mobile`, `versionCode='18'`, and
  `versionName='1.2.15'`;
- the previous public `1.2.14` APK reported `versionCode='17'`;
- `apksigner verify --verbose --print-certs` passed using v2 signing and the
  expected signer fingerprint.

Documentation-task safety checks:

- pre-edit `git diff --check` passed;
- the staged-file list was empty;
- the 11 untracked CI logs were inspected separately because ordinary
  `git diff` excludes them;
- a focused scan found no private-key headers, common token/key formats, or
  personal Windows home paths in those untracked logs.

Current hardening checks:

- `.\gradlew.bat --no-daemon --console=plain compileDebugAndroidTestKotlin`
  passed (`BUILD SUCCESSFUL`).
- `.\gradlew.bat --no-daemon --console=plain testDebugUnitTest assembleDebug`
  passed (`BUILD SUCCESSFUL`).
- `.\gradlew.bat --no-daemon --console=plain lintDebug` passed
  (`BUILD SUCCESSFUL`).
- Focused
  `ConsumptionPreferencesRepositoryTest` passed after the boundary-test split.
- `git diff --check` passed.

## Validation not performed

- The remaining Compose add-and-save smoke test was not run locally because
  `adb devices -l` reported no connected Android device or emulator. It must be
  verified by the pull-request API 24 emulator job.
- No physical-device installation, Obtainium upgrade, or manual UI acceptance
  test was performed.
- No live Apps Script deployment, trigger, spreadsheet schema, or production
  data behavior was tested.

## Remaining work

- Commit and push the focused hardening branch, open its pull request, and wait
  for `Cannsheet Android PR validation`.
- The 11 untracked `job_*.txt` files remain in the worktree as generated
  diagnostic artifacts. They were not changed or deleted.

## Recommended next action

Open the focused pull request and verify the remaining Compose smoke test on its
API 24 runner. Before any future release tag, manually dispatch the full API
24/API 36 matrix on this branch or wait for both boundary jobs on the exact
post-merge `main` SHA.

## Risks, assumptions, and unresolved questions

- The stable input tags use the user-visible one-based preset position. That is
  appropriate because saved order is part of the feature contract, but a future
  drag-to-reorder feature would need a stable row identity instead.
- GitHub displays a non-fatal KSP annotation in the successful workflows. The
  jobs and Gradle builds completed successfully, but the annotation may confuse
  future reviewers unless the underlying tool issue is separately investigated.
- The hardening branch begins at released source SHA `e19dfbe`; it is not
  deployed or released until its focused pull request is merged and a later
  explicitly approved release is published.

## Relevant files

- `app/src/androidTest/java/com/example/ui/QuickLogQuantityEditorTest.kt`
- `app/src/main/java/com/example/ui/SettingsScreen.kt`
- `.github/workflows/android-pr-checks.yml`
- `.github/workflows/release-apk.yml`
- `app/build.gradle.kts`
- `docs/PROJECT_STATE.md`
- `docs/DECISIONS.md`
- `docs/HANDOFF.md`
- `AGENTS.md`
- `CONTRIBUTING.md`
