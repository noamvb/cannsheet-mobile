# Latest handoff

Last updated: 2026-07-27

Branch: `release/v1.2.14`

Working tree status: Fixed purchase screen date picker timezone shift bug.

## Purpose of this session

Fix date picker behavior on purchase entry screen where selecting a manual date caused the day prior to be selected in negative-offset timezones (e.g., US timezones).

## Work completed

- Added `parsePickerDateToMillis(dateString: String): Long?` in `ConsumptionDateTime.kt` for timezone-safe parsing of ISO `"yyyy-MM-dd"` strings into UTC epoch millis.
- Updated `PurchaseScreen.kt` date picker state to format selected dates with `pickerDateToWire(selectedDateMillis)` (UTC timezone) and initialize state with `parsePickerDateToMillis(date) ?: currentLocalDateAsPickerMillis()`.
- Added unit test cases in `ConsumptionDateTimeTest.kt` verifying `parsePickerDateToMillis` and `pickerDateToWire` round-trip date parsing across multiple timezones (`America/New_York`, `Asia/Tokyo`, `UTC`, `America/Los_Angeles`).

## Current project state

`PurchaseScreen` and `ConsumptionScreen` both use consistent UTC date picker conversion helpers (`pickerDateToWire`, `currentLocalDateAsPickerMillis`, `parsePickerDateToMillis`) preventing timezone offset shifts when picking dates.

## Validation performed

The following completed successfully:

- `.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug` (passed, all 28 unit test tasks executed/up-to-date)
- `.\gradlew.bat --no-daemon lintDebug` (passed)
- Node backend contract & integration suites:
  - `node tests/backend_contract_test.js`
  - `node tests/backend_analytics_test.js`
  - `node tests/backend_recovery_test.js`
  - `node tests/backend_spreadsheet_test.js`
  - `node tests/fake_sheets_batch_update_test.js`
  - `node tests/sandbox_performance_fixture_test.js`
  - `node tests/sandbox_provisioning_test.js`

## Validation not performed

- Android instrumentation tests on a physical device or emulator were not performed.
- Live Apps Script deployment and production spreadsheet state were not modified or revalidated.

## Recommended next action

Merge or submit pull request with the purchase date picker fix.

## Risks, assumptions, and unresolved questions

- Both successful PR workflows and the successful release workflow emitted a
  non-fatal KSP annotation and a GitHub Actions Node.js runtime deprecation
  warning. These did not change the successful job conclusions, but future
  maintenance should investigate them if they become failures.
- Device installation compatibility was inferred from the unchanged
  application ID, increased version code, and matching signer, not proven by an
  installation during this session.
- Current live backend deployment, trigger, spreadsheet, and device-coverage
  state remain outside repository-only evidence.

## Relevant files

- `AGENTS.md`
- `GEMINI.md`
- `.agents/skills/project-handoff/SKILL.md`
- `docs/PROJECT_STATE.md`
- `docs/ARCHITECTURE.md`
- `docs/DECISIONS.md`
- `docs/HANDOFF.md`
- `app/build.gradle.kts`
- `.github/workflows/android-pr-checks.yml`
- `.github/workflows/release-apk.yml`
