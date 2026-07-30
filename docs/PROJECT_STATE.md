# Project state

Last updated: 2026-07-29

## Repository state

- Canonical branch: `main`
- Current working branch: `codex/history-corrections-android`
- Branch base and `origin/main`:
  `621f9801f907dde9d5315cb5f261bbfc3407f868`
- Released source tag `v1.2.16`:
  `7ee3f3a6995475c25addc712259cc44f8530b7a0`
- Current release metadata in `app/build.gradle.kts`: version name `1.2.16`,
  version code `19`
- The public signed release is published to
  `noamvb/cannsheet-mobile-releases` under tag
  [v1.2.16](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.16).
- The current working tree contains an uncommitted Android History-correction
  milestone. It has not been pushed, opened as a pull request, or released.

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

## Current correction milestone

Merged backend
[PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19) provides:

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
  wall-clock values, independent of the execution host's timezone;
- strict rejection of nonexistent correction replacement wall times; and
- lock-held refresh of the independently controlled correction write gate.

The current Android working tree adds:

- a forward-only Room 8-to-9 migration and durable pending-correction queue;
- stable correction/action IDs, unchanged-payload request-ID reuse, and exact
  action/target acknowledgement deletion;
- version-2 correction sync and History contracts with safe legacy defaults;
- fresh-server and capability gates that prevent editing cached, stale, or
  already-pending entries;
- user-facing Correct, Void, Restore, optional reason, safe product-reopen, and
  explicit pending-correction cancellation flows; and
- lifecycle and revision details in History while leaving the full append-only
  audit on the backend instead of adding it to every paginated cache response.

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
- The currently published v1.2.16 APK cannot create or queue consumption
  corrections. The controls exist only in this unmerged working branch.
- The correction schema is provisioned and correction writes are enabled in the
  isolated live sandbox. Production has not been provisioned, reconciled,
  deployed, or enabled for correction writes.

## Current validation status

The backend foundation is merged at `621f980`. Final PR run `30499192644`
passed all five required checks. Live sandbox verification then proved:

- Apps Script deployment version 13 with environment `SANDBOX`, analytics API
  version 2, correction schema version 1, and correction writes enabled;
- one `VOID` committed once and returned `duplicate` on identical retry;
- History exposed the resulting lifecycle, head, and audit revision;
- `resetSandboxData()` restored the five seeded original events; and
- a production-labelled request was rejected with `ENVIRONMENT_MISMATCH`.

Android working-tree evidence:

- generated JVM XML reports cover 49 tests with zero failures, errors, or
  skips, including the new mapping, queue, retry, and History helper tests;
- the Gradle wrapper timed out before returning its final exit code, so the XML
  reports are evidence but the command itself is not recorded as successful;
- independent Terra/medium review found no P0 or P1 correctness issue;
- a second focused architecture review found and removed unconditional audit
  overfetch from paginated History; and
- `git -c core.fsmonitor=false diff --check` currently exits successfully.

Not yet performed:

- production provisioning, reconciliation, write enablement, or deployment;
- pull-request CI, Android lint/build, emulator/device validation, or a visual
  screenshot/recording; and
- signed APK preparation or publication for this feature.

## Current priorities

Review the complete Android diff, open the focused pull request, and use its
single CI run for the authoritative build, lint, JVM, instrumentation, and API
24 emulator evidence. Merge, production rollout, and release remain separate
approval gates.

## Unresolved questions

- What is the current live Apps Script deployment version and trigger state?
- Do the connected production sheets currently match the contracts and
  reconciliation expectations in the checked-in backend reports?
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
