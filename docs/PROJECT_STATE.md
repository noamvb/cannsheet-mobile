# Project state

Last updated: 2026-07-23

## Repository state

- Branch: `docs/shared-codex-context`
- Base/tracking reference at inspection: `origin/release/v1.2.10`
- Working tree at initial inspection: clean
- Current working tree: uncommitted shared-context documentation changes are
  expected; no application or backend behavior changes are part of this task.
- Current release metadata in `app/build.gradle.kts`: version name `1.2.10`,
  version code `13`

## Project summary

Cannsheet Mobile is a personal Android app for logging cannabis purchases and
consumption. It stores products, pending actions, interaction metadata, sync
state, and analytics cache data locally. It communicates with a Google Apps
Script web app whose checked-in source reads and writes Google Sheets.

## Verified implemented areas

Repository code and tests show:

- Compose screens for logging consumption and purchases, viewing Insights and
  History, and changing settings.
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

At the start of the shared-context bootstrap, the branch was clean at commit
`f6f98ae` (`Release Cannsheet Mobile 1.2.10`). Validation results for the
documentation changes are recorded in `docs/HANDOFF.md`, which is updated last.

The pull-request workflow runs:

- `node tests/backend_analytics_test.js`
- `./gradlew --no-daemon testDebugUnitTest assembleDebug`

The tag-triggered release workflow also runs `lintDebug`, validates release
secrets, builds a signed APK, verifies it, and publishes it. Release operations
must not be run without explicit authorization.

## Current priorities

No product roadmap priority can be verified from the repository. The immediate
branch purpose is the focused shared-context bootstrap described in
`docs/HANDOFF.md`.

## Unresolved questions

- What is the current live Apps Script deployment version and trigger state?
- Do the connected production sheets currently match the contracts and
  reconciliation expectations in the checked-in backend reports?
- Which supported Android versions/devices have been manually exercised for
  release `1.2.10`?

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
