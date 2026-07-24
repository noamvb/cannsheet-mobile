# Project state

Last updated: 2026-07-23

## Repository state

- Canonical branch: `main`
- Released source commit and tag: `45fce56`
  (`v1.2.11`)
- Current release metadata in `app/build.gradle.kts`: version name `1.2.11`,
  version code `14`
- The public signed release is available from
  `noamvb/cannsheet-mobile-releases` under tag `v1.2.11`.

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

The shared-context system reached `main` through PR #6. GitHub Actions run
`30064613210` completed successfully after running the backend analytics test,
Android unit tests, and debug APK build.

Release metadata reached `main` through PR #7. GitHub Actions run `30065008648`
completed successfully with the same pull-request validation. Tag `v1.2.11`
then triggered release run `30065340691`, which completed successfully after
running unit tests and lint, validating the tag and required secrets, building
and verifying the signed APK, generating its checksum, and publishing the
APK-only release.

Independent public-artifact verification established:

- asset `Cannsheet-Mobile-1.2.11.apk`;
- SHA-256
  `8064dca240f358a2e8f0b7d318a6357630517b3fdd29296753780c3cecf9aaec`;
- package `com.noamv.cannsheet.mobile`;
- version code `14` and version name `1.2.11`; and
- signing-certificate SHA-256
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`.

The local backend analytics test passed. The local Gradle command did not start
because this worktree had no configured Android SDK location; the corresponding
GitHub Actions checks completed successfully in their configured SDK
environment. Android instrumentation and device installation tests were not
run.

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
