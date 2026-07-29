# Project state

Last updated: 2026-07-28

## Repository state

- Canonical branch: `main`
- Current working branch: `ci/tiered-validation-release-gate`
- Released source commit and tag: `v1.2.14`
- Current release metadata in `app/build.gradle.kts`: version name `1.2.14`,
  version code `17`
- The public signed release is available from
  `noamvb/cannsheet-mobile-releases` under tag `v1.2.14`.

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
- A sandbox Android build type with a separate application ID suffix and a
  Gradle task that validates its local endpoint before sandbox builds.
- Fake Apps Script/Sheets runtimes and regression suites for backend contracts,
  spreadsheet writes, recovery, analytics, and sandbox helpers.
- Android JVM and instrumentation tests for data, migration, queue, coordinator,
  helper, and Compose UI behavior.

These statements describe checked-in implementation, not a fresh live-service
or device verification.

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

## Current validation status

The tiered CI validation and release provenance gate overhaul was implemented on branch `ci/tiered-validation-release-gate`.

Verification completed:
- `git diff --check` passed cleanly.
- Path classification logic tested across 10 representative file sets (docs-only, backend-only, full Android, push to main, fallback).
- Exact-SHA confirmation logic tested across 14 scenarios (success, non-push, wrong SHA, missing jobs, skipped jobs, cancellation, invalid output).
- Local backend test suites passed:
  - `node tests/backend_contract_test.js`
  - `node tests/backend_analytics_test.js`
  - `node tests/backend_recovery_test.js`
  - `node tests/backend_spreadsheet_test.js`
  - `node tests/fake_sheets_batch_update_test.js`
  - `node tests/sandbox_performance_fixture_test.js`
  - `node tests/sandbox_provisioning_test.js`
  - `PYTHONPATH=. python -m unittest discover -s tests -p "test_backend_sync_benchmark.py"`
- Local Gradle static validation passed:
  - `.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug` (passed)
  - Gradle configuration cache reuse verified (`Reusing configuration cache`).

## Current priorities

No product roadmap priority can be verified from the repository. The
shared-context bootstrap and release `1.2.11` are complete; no next product task
is established by repository evidence.

## Unresolved questions

- What is the current live Apps Script deployment version and trigger state?
- Do the connected production sheets currently match the contracts and
  reconciliation expectations in the checked-in backend reports?
- Which supported Android versions/devices have been manually exercised for
  release `1.2.11`?

These require external or device evidence and should not be answered from this
document alone.

## Relevant paths

- `app/src/main/java/com/example/ui`
- `app/src/main/java/com/example/data`
- `app/src/test`
- `app/src/androidTest`
- `tests`
- `backend_additions.gs`
- `app/build.gradle.kts`
- `.github/workflows`
- `BACKEND_ANALYTICS_REPORT.md`
- `BACKEND_SYNC_PERFORMANCE_REPORT.md`
- `AGENTS.md`
- `GEMINI.md`
- `.agents/skills/project-handoff/SKILL.md`
