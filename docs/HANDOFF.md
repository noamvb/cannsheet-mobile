# Latest handoff

Last updated: 2026-08-13

## Current outcome

Cannsheet Mobile v1.2.26 is implemented, released, and published to the
separate GitHub releases repository. The home-screen pen widget feature was
delivered through [PR #52](https://github.com/noamvb/cannsheet-mobile/pull/52),
squash-merged as `0e9bb650de7c9a3d7d629f20bedda5857528770b`, and its
remediation through [PR #55](https://github.com/noamvb/cannsheet-mobile/pull/55)
is included in the release. The version-only
[PR #56](https://github.com/noamvb/cannsheet-mobile/pull/56) merged as
`ec75c5297691ac870679f8462a0102c89e6f682e`; tag `v1.2.26` points to that
exact validated `main` tip.

The public signed release is [Cannsheet Mobile 1.2.26](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.26),
with `Cannsheet-Mobile-1.2.26.apk` and
`Cannsheet-Mobile-1.2.26.apk.sha256`. The independently downloaded APK
matched the published SHA-256:

`c5ddfb6117e4159ffcb764e776838e3d1c5c6db8d7462f2dd53b1f1f5de9e5cf`

The downloaded APK reports package `com.noamv.cannsheet.mobile`, version code
`29`, and version name `1.2.26`; local `apksigner verify` confirms its v2
signature. Obtainium can discover the public release asset from the releases
repository. No production phone or ADB action was used for this release.

The implementation does not alter the Room schema, existing queue payloads,
Apps Script contract, production endpoint, application ID, or signing
configuration. The only release-only source change was the requested
`versionCode`/`versionName` bump in `app/build.gradle.kts`.

The original widget proposal and accepted remediation adjustments are recorded
in `docs/WIDGET_REVIEW_PLAN.md`.

## Implementation

- The widget uses the platform `AppWidgetProvider` and `RemoteViews` APIs, with
  no Glance dependency, and remains compatible with API 24.
- It reuses the loaded-pen resolution, rate, date/time, and
  `ConsumptionLogger` boundaries. Seconds are display/input units only;
  `secondsToUses` converts before Room, the offline queue, or the wire.
- Submit atomically captures the product, stable consumption event ID, submit
  timing, date, time, rate, displayed seconds, and converted uses in payload
  version 2 of `pen_widget_state`; valid version-1 payloads migrate on decode.
- A process-local five-second timer is the primary Undo/delivery path, followed
  by 1.5 seconds of grace. Unique WorkManager work is the process-death
  backstop, while broadcasts and startup lazily flush overdue work. Maximum-age
  and backwards-clock rules prevent a pending payload from wedging the widget.
- Delivery claims without removing the payload, writes the stable event through
  the shared logger, and completes/removes only the exact claim after Room is
  durable. Failure releases it for retry; widget deletion force-commits first.
  No widget retry deletes a queued Room row or changes the current loaded cart.
- The state model is `Unavailable`, `NoCart`, `RateOff`, `Composing`, or
  `AwaitingCommit`. The draft starts at zero, moves in ten-second steps, clamps
  to 0..600 seconds, and enables submit only for a positive value.
- Provider actions and workers share serialized mutation/render execution.
  Application startup, reactive pen-state changes, and acknowledged sync work
  refresh through a data-facing interface, removing package dependency cycles.
- Widget app opens use activity `PendingIntent`s and one-shot `singleTop`
  navigation events without rebuilding Compose. Copy is resource-backed;
  accessibility descriptions include the value and action; initial, picker,
  full, and compact layouts have explicit usable sizing and confirmation.

## Validation and provenance

- Version PR #56 gate [run 31656052541](https://github.com/noamvb/cannsheet-mobile/actions/runs/31656052541)
  passed the required checks.
- Exact post-merge `main` validation [run 31656311214](https://github.com/noamvb/cannsheet-mobile/actions/runs/31656311214)
  passed all six jobs, including API 24 and API 36 emulator validation,
  backend validation, Android static validation, classification/security, and
  aggregate checks.
- Signed publication [run 31656746063](https://github.com/noamvb/cannsheet-mobile/actions/runs/31656746063)
  passed exact-main proof, protected signing, signature and metadata checks,
  checksum, public publication, and post-publication verification.
- Feature PR gate [run 31621145764](https://github.com/noamvb/cannsheet-mobile/actions/runs/31621145764)
  passed all six jobs after the API 24 renderer fix.
- Historical v1.2.25 version PR gate [run 31621733498](https://github.com/noamvb/cannsheet-mobile/actions/runs/31621733498)
  passed all required jobs.
- Historical v1.2.25 exact post-merge `main` validation [run 31622170237](https://github.com/noamvb/cannsheet-mobile/actions/runs/31622170237)
  passed all six jobs, including API 24 and API 36 emulator validation,
  backend validation, Android static validation, classification/security, and
  aggregate checks.
- Historical v1.2.25 signed publication [run 31622788837](https://github.com/noamvb/cannsheet-mobile/actions/runs/31622788837)
  passed exact-main proof, signed build, signature verification, checksum,
  public publication, and post-publication verification.
- Local v1.2.26 release-branch validation passed with JDK 17.0.20, Android
  platform 36.1, and Build Tools 36.0.0. The exact command was
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`;
  it completed with `BUILD SUCCESSFUL`, 186 JVM tests, zero failures/errors/
  skips, Android-test Kotlin compilation, lint, and debug APK assembly. Local
  `git diff --check` also passed. The GitHub Actions matrix above is the
  authoritative CI and emulator evidence.
- PR #55 local validation passed with JDK 17.0.20, Android platform 36.1, and
  Build Tools 36.0.0. The exact command was
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`;
  it completed with `BUILD SUCCESSFUL`, 186 passing JVM tests, Android-test
  Kotlin compilation, lint, and debug APK assembly. All eight checked-in Node.js
  backend suites passed using the bundled Node runtime, and the Python backend
  benchmark passed all 13 tests. The first JavaScript attempt found no `node` on
  the shell PATH and executed no suites; the explicit bundled-runtime rerun is
  the passing evidence.
- Source previews and CI widget renderer/state tests are available. No
  production-device widget screenshot or action walkthrough was performed.
- PR #55 adds a generated representative picker preview, but no physical
  launcher sizing/action walkthrough, emulator instrumentation execution, or
  production widget submission was run. Android instrumentation source compiled
  in the local gate and is not being described as executed device coverage.

## Phone state and safety boundary

- The historical v1.2.25 update was installed in place on the intended Samsung
  SM-F966W running Android API 36. Before that update, the production package
  readback reported version code `27`, version name `1.2.24`; after it reported
  version code `28`, version name `1.2.25`, with the
  `PenConsumptionWidgetProvider` receiver present.
- The historical update used `adb install -r`, preserving the existing
  package/data boundary. No uninstall, data clear, downgrade, app launch,
  widget add, widget tap, production Log/Purchase/Finish/Sync action, or
  synthetic production mutation was performed.
- Wireless ADB was disconnected after that historical package readback. The
  v1.2.26 release did not access the phone, and no phone commands are pending.
- These are package/readback facts only. CI emulator validation, source
  previews, and production-package installation must not be described as a
  physical widget UI walkthrough.

## Recommended next action

The v1.2.26 release is complete and its signed APK is available from the public
release assets for Obtainium. If physical widget visual/action coverage is desired,
build or obtain a separate sandbox/debug package with a non-production endpoint
and test only that package: light/dark rendering, launcher sizing, +/- controls,
reset, countdown/Undo, and all local message states. Do not use the signed
production package for synthetic widget submissions or install a debug-signed
APK over it.

## Data-safety notes

- Seconds never enter the Room schema, offline queue, Apps Script request, or
  spreadsheet contract; only converted uses do.
- The short-lived widget payload is excluded from backup. Claim/write/complete
  keeps it recoverable until Room is durable, and stable-event arbitration makes
  retries idempotent without deleting existing queued actions.
- Existing Room migrations, immutable IDs, acknowledgement rules, retries,
  and synchronization locking remain unchanged.
- Public documentation intentionally omits wireless ADB serials, private local
  paths, credentials, and signing material.

## Relevant files

- `app/src/main/java/com/example/widget`
- `app/src/main/java/com/example/data/ConsumptionLogger.kt`
- `app/src/main/java/com/example/data/sync/SyncScheduler.kt`
- `app/src/main/java/com/example/data/sync/SyncWorker.kt`
- `app/src/main/java/com/example/CannsheetApplication.kt`
- `app/src/main/java/com/example/ui/CannsheetViewModel.kt`
- `app/src/test/java/com/example/widget`
- `app/src/androidTest/java/com/example/widget`
- `docs/ARCHITECTURE.md`
- `docs/DECISIONS.md`
- `docs/PROJECT_STATE.md`
