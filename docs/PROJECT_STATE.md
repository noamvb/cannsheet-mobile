# Project state

Last updated: 2026-07-29

## Repository state

- Canonical branch: `main`
- Current working branch: `codex/history-corrections-backend`
- Branch base, `origin/main`, and released source tag `v1.2.16`:
  `7ee3f3a6995475c25addc712259cc44f8530b7a0`
- Current release metadata in `app/build.gradle.kts`: version name `1.2.16`,
  version code `19`
- The public signed release is published to
  `noamvb/cannsheet-mobile-releases` under tag
  [v1.2.16](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.16).
- The current working tree contains a locally verified, uncommitted backend
  correction milestone. It has not been pushed, deployed, or released.

## Project summary

Cannsheet Mobile is a personal Android app for logging cannabis purchases and
consumption. It stores products, pending actions, interaction metadata, sync
state, and analytics cache data locally. It communicates with a Google Apps
Script web app whose checked-in source reads and writes Google Sheets.

## Verified implemented areas

Repository code and tests show:

- Tiered GitHub Actions validation workflow (`.github/workflows/android-pr-checks.yml`) with fail-safe path classification (`classify`), dedicated backend testing (`backend`), Android static checks (`android-static`), API 24/36 emulator matrix (`emulator`), reusable AVD snapshot caching (`cannsheet-avd-v1`), security scanning, and required aggregation job (`Cannsheet Android PR validation`).
- Restructured release workflow (`.github/workflows/release-apk.yml`) with exact-SHA main validation proof (`confirm-main-validation`), signed build verification (`verify`) using `--no-configuration-cache`, version code monotonicity checks, publication asset verification, and post-publication validation (`publish`) without overwrite (`--clobber`).
- Custom adaptive app icon with dark emerald background grid, botanical/sheet emblem, and Android 13+ Material You monochrome themed icon support (`ic_launcher_monochrome.xml`).
- Compose screens for logging consumption and purchases, viewing Insights and
  History, and changing settings.
- Purchase screen date picker UTC date formatting (`pickerDateToWire`, `parsePickerDateToMillis`) preventing one-day date shifts in negative-offset timezones.
- Personal and borrowed-product consumption logging.
- Standalone product-finish actions.
- A user-visible cancellation countdown before queued actions are submitted.
- Room-backed offline queues for purchases, consumption events, and finish
  actions.
- Acknowledgement-based queue deletion, duplicate-safe response handling,
  persisted sync request identity, and production/sandbox environment checks.
- Server-backed product refresh that restores pending purchases, reapplies
  queued finish state, and merges newer product interaction data.
- Versioned Insights and History responses, Room analytics caching, pagination,
  stale-cursor handling, and data-quality warnings.
- DataStore-backed quick-log quantity presets and the unopened-product setting.
- Quick-log preset boundary rules covered by fast JVM tests, with one focused
  Compose add-and-save smoke test using stable test tags instead of text-field
  ordering or visible labels.
- A sandbox Android build type with a separate application ID suffix and a
  Gradle task that validates its local endpoint before sandbox builds.
- Fake Apps Script/Sheets runtimes and regression suites for backend contracts,
  spreadsheet writes, recovery, analytics, and sandbox helpers.
- Android JVM and instrumentation tests for data, migration, queue, coordinator,
  helper, and Compose UI behavior.

These statements describe checked-in implementation, not a fresh live-service
or device verification.

## Current branch implementation

The uncommitted backend milestone adds:

- an append-only `ConsumptionEventCorrections` schema and the `REPLACE`, `VOID`,
  and `RESTORE` correction protocol;
- stable correction IDs, expected-head conflict checks, exact duplicate
  acknowledgement, content-conflict rejection, and safe product-reopen checks;
- correction-aware effective events for both legacy and current
  Insights/History reads, including audit metadata and correction-aware stale
  cursors;
- durable journal, recovery, and reconciliation coverage for correction writes;
- disabled-first production provisioning and sandbox provisioning that validates
  environment, spreadsheet, form, and Config identity before any mutation; and
- focused fake-runtime, regression, scale, recovery, and provisioning tests,
  registered in backend-only CI; and
- canonical-time parsing for version-2 consumption, finish, and correction
  wall-clock values, independent of the execution host's timezone.

Android persistence, queueing, History editing UI, and network integration are
not part of this milestone and remain pending behind the backend PR and sandbox
verification gates.

## Partial areas and known limitations

- `app/src/main/res/xml/backup_rules.xml` and
  `app/src/main/res/xml/data_extraction_rules.xml` remain sample/template rules;
  the latter contains a backup-selection TODO.
- The Kotlin namespace remains `com.example` while the Android application ID is
  `com.noamv.cannsheet.mobile`; `README.md` documents this as an intentional
  source-layout compatibility choice.
- Device/emulator behavior and Android instrumentation tests require an
  available Android device or emulator and are not covered by ordinary JVM
  tests.
- Live Apps Script deployment, trigger, spreadsheet-schema, and production-data
  state cannot be established from the checkout alone.
- Backend behavior is concentrated in the large `backend_additions.gs` file and
  covered by fake-runtime tests. Live Apps Script and spreadsheet behavior still
  requires separate validation.
- The checked-in Android client cannot yet create or queue consumption
  corrections. No user-facing correction control exists in the current APK.
- The correction schema has not been provisioned in a live sandbox or
  production workbook, and correction writes have not been enabled in
  production.

## Current validation status

The current backend milestone passed:

- all eight checked-in Node backend suites, including the new
  `backend_corrections_test.js`;
- correction scale coverage with 3,600 events and 600 corrections;
- both Apps Script syntax checks through `node --check`;
- 13 Python backend benchmark tests; and
- `git diff --check origin/main`.

An independent read-only verifier reviewed the complete diff. Its first pass
found that sandbox provisioning could mutate a wrongly targeted
production-marked spreadsheet before rejecting it. The implementation was
changed to perform the complete target guard first, and a snapshot/no-write
regression was added. The verifier's second pass found the repaired backend
milestone ready.

Pull request
[PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19) is open. Its first
GitHub Actions run passed classification, Android static validation, and the API
24 emulator, but the backend job exposed a host-timezone-dependent retry bug.
The same failure was reproduced locally under `TZ=UTC`. The backend now parses
wall-clock input explicitly in `America/New_York`, and the fake runtime and
affected assertions cover both UTC and New York hosts. A follow-up CI run is
pending.

Not yet performed:

- GitHub Actions for this branch or a pull request;
- a real Apps Script deployment or live sandbox workbook verification;
- production provisioning, reconciliation, write enablement, or deployment;
- Android implementation, Gradle validation, emulator/device validation; or
- signed APK preparation or publication for this feature.

## Current priorities

After user approval, commit and open the focused backend pull request, then pass
backend CI and review. Merge and live sandbox rollout remain separate approval
gates. Android implementation begins only after the backend contract is merged
and verified in the sandbox.

## Unresolved questions

- What is the current live Apps Script deployment version and trigger state?
- Do the connected production sheets currently match the contracts and
  reconciliation expectations in the checked-in backend reports?
- Can the sandbox web app and workbook be reached with the current deployment
  credentials for end-to-end correction verification?
- Which supported physical Android device will be used for the final correction
  workflow check?

These require external or device evidence and should not be answered from this
document alone.

## Relevant paths

- `app/src/main/java/com/example/ui`
- `app/src/main/java/com/example/data`
- `app/src/test`
- `app/src/androidTest`
- `app/src/androidTest/java/com/example/ui/QuickLogQuantityEditorTest.kt`
- `tests`
- `tests/backend_corrections_test.js`
- `backend_additions.gs`
- `sandbox_provisioning.gs`
- `app/build.gradle.kts`
- `.github/workflows`
- `BACKEND_ANALYTICS_REPORT.md`
- `BACKEND_SYNC_PERFORMANCE_REPORT.md`
- `AGENTS.md`
- `GEMINI.md`
- `.agents/skills/project-handoff/SKILL.md`
