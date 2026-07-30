# Project state

Last updated: 2026-07-29

## Repository state

- Canonical branch: `main`
- Current working branch: `codex/typed-table-freeze-fix`
- Branch base and `origin/main`:
  `fb3ad6b763a7fab0b9f49f7e4855715eadde6aa6`
- Released source tag `v1.2.16`:
  `7ee3f3a6995475c25addc712259cc44f8530b7a0`
- Current release metadata in `app/build.gradle.kts`: version name `1.2.16`,
  version code `19`
- The public signed release is published to
  `noamvb/cannsheet-mobile-releases` under tag
  [v1.2.16](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.16).
- The Android History-correction milestone was merged through
  [PR #20](https://github.com/noamvb/cannsheet-mobile/pull/20), and its first
  production-rollout hardening was merged through
  [PR #21](https://github.com/noamvb/cannsheet-mobile/pull/21). Neither milestone
  has been included in a signed release.

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

The current Android branch adds:

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
  corrections. The Android controls are merged on `main`, but the published
  production backend deployment still predates the correction API.
- The correction schema is provisioned and correction writes are enabled in the
  isolated live sandbox.
- Production is in a safe, additive partial-provisioning state. On 2026-07-29,
  after identity checks and a fresh workbook backup, the first provisioning
  attempt created an empty, exact-header `ConsumptionEventCorrections` sheet and
  set schema version `1` with correction writes `false`. It then stopped when
  Google Sheets rejected cosmetic formatting on the typed `Purchases` header.
- The post-failure recoverable reconciliation snapshot was clean: no pending
  apply, no incomplete journal, and no reported differences or blocking
  differences. Its observed row counts are deliberately not recorded as future
  expectations because ordinary production entries continue during rollout.
  The editor source was restored exactly to its pre-attempt content, and the
  existing production deployment remained unchanged at version 11.
- PR #21 merged the focused typed-header formatting fix and added the full
  recoverable reconciliation as an enablement gate. The first post-merge retry
  passed that operation, then stopped when Google Sheets also rejected
  reapplying `setFrozenRows(1)` to the typed `Purchases` table. Live metadata
  confirms that table already has one frozen row.
- After the second contained failure, the editor source was again restored
  exactly, the correction sheet remained header-only, schema version remained
  `1`, writes remained `false`, and a fresh full reconciliation remained clean.
  Production provisioning has not completed and no new backend version has
  been deployed.
- The current follow-up reads the exact `Purchases` sheet's Advanced Sheets
  table metadata before correction provisioning. Table-backed headers are left
  to Google Sheets regardless of frozen-row count; ordinary sheets retain
  styling and frozen-row setup. Missing or ambiguous metadata fails closed
  before additive correction configuration is changed.

## Current validation status

The backend foundation is merged at `621f980`. Final PR run `30499192644`
passed all five required checks. Live sandbox verification then proved:

- Apps Script deployment version 13 with environment `SANDBOX`, analytics API
  version 2, correction schema version 1, and correction writes enabled;
- one `VOID` committed once and returned `duplicate` on identical retry;
- History exposed the resulting lifecycle, head, and audit revision;
- `resetSandboxData()` restored the five seeded original events; and
- a production-labelled request was rejected with `ENVIRONMENT_MISMATCH`.

Android branch evidence:

- generated JVM XML reports cover 49 tests with zero failures, errors, or
  skips, including the new mapping, queue, retry, and History helper tests;
- the Gradle wrapper timed out before returning its final exit code, so the XML
  reports are evidence but the command itself is not recorded as successful;
- independent Terra/medium review found no P0 or P1 correctness issue;
- a second focused architecture review found and removed unconditional audit
  overfetch from paginated History; and
- `git -c core.fsmonitor=false diff --check` currently exits successfully.

Draft PR #20 run `30503688027` passed repository/security classification,
backend validation, Android JVM tests, instrumentation compilation, lint, debug
APK assembly, and artifact upload. Its API 24 emulator ran 22 instrumentation
tests: 20 passed, while two `HistoryContentTest` assertions failed because they
checked details and buttons below the visible area of the new scrollable sheet.
Follow-up run `30504243649` proved that Compose could scroll to the nodes, but
Material's modal-sheet clipping still did not satisfy the stronger
`assertIsDisplayed()` geometry check. The final approved test-only adjustment
keeps the visible timestamp assertion, verifies lower details exist, and
verifies Correct/Void exist with click actions. Run `30505232031` then stopped
at instrumentation-test compilation because `assertExists` was imported as an
extension even though this Compose version exposes it as a
`SemanticsNodeInteraction` member. The approved one-line import removal still
required one more CI run; that follow-up passed and PR #20 was subsequently
merged at `434d004`.

Not yet performed:

- successful completion of production correction provisioning;
- a new production backend deployment, disabled-state endpoint verification,
  correction write enablement, or post-enable verification;
- a physical-device visual check, screenshot/recording, or live-sandbox APK
  installation; and
- signed APK preparation or publication for this feature.

## Current priorities

The focused typed-table frozen-row fix and its production-shaped regression
coverage are implemented on the current branch. Resolve every PR review thread
and require green CI on the exact final head before merge. Retry idempotent
production provisioning only from that merge-verified source, read fresh
reconciliation state, update the existing deployment in place, and verify the
public endpoint while writes are still disabled. Enable writes only after the
second clean reconciliation. Release remains a separate approval gate.

## Unresolved questions

- Will the follow-up typed-table fix complete idempotent provisioning against
  the current production table layout?
- Will fresh production reconciliation remain clean immediately before
  correction writes are enabled?
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
