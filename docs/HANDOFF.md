# Latest handoff

Last updated: 2026-08-13

## Current outcome

Cannsheet Mobile v1.2.27 is implemented, released, and published to the
separate GitHub releases repository. The widget follow-up was delivered through
[PR #59](https://github.com/noamvb/cannsheet-mobile/pull/59),
[PR #60](https://github.com/noamvb/cannsheet-mobile/pull/60), and
[PR #61](https://github.com/noamvb/cannsheet-mobile/pull/61), then the version
metadata was bumped through [PR #62](https://github.com/noamvb/cannsheet-mobile/pull/62).
The tag `v1.2.27` points to exact validated `main` commit
`39abdf3814b1ff1f75ca06ca2d78e72a10d281b5`.

The public signed release is [Cannsheet Mobile 1.2.27](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.27),
with `Cannsheet-Mobile-1.2.27.apk` and
`Cannsheet-Mobile-1.2.27.apk.sha256`. The independently downloaded APK
matched the published SHA-256:

`05ec7a07e3f686e7ae95a613d56e311cd498cbec83d09cda743b424f404af92d`

The downloaded APK reports package `com.noamv.cannsheet.mobile`, version code
`30`, and version name `1.2.27`; local `aapt` and `apksigner verify` confirm
the metadata and v2 signature. Its certificate digest matches v1.2.26, so the
release is compatible with the existing production package. Obtainium can
discover the public release asset from the releases repository.

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

- PR #59's full gate [run 31661910965](https://github.com/noamvb/cannsheet-mobile/actions/runs/31661910965),
  PR #60's full gate [run 31662427658](https://github.com/noamvb/cannsheet-mobile/actions/runs/31662427658),
  and PR #61's replacement full gate [run 31664591647](https://github.com/noamvb/cannsheet-mobile/actions/runs/31664591647)
  passed. PR #61's first API 24 attempt exposed an invalid plain `View` in
  `RemoteViews`; the fix replaced those spacers with supported `TextView`
  instances and the replacement run passed.
- Version PR #62 gate [run 31665017557](https://github.com/noamvb/cannsheet-mobile/actions/runs/31665017557)
  passed the required checks.
- Exact post-merge `main` validation [run 31665252525](https://github.com/noamvb/cannsheet-mobile/actions/runs/31665252525)
  passed all six jobs, including API 24 and API 36 emulator validation,
  backend validation, Android static validation, classification/security, and
  aggregate checks.
- Signed publication [run 31665589830](https://github.com/noamvb/cannsheet-mobile/actions/runs/31665589830)
  passed exact-main proof, protected signing, version-code monotonicity,
  unit tests/lint, signed build, signature and metadata checks, checksum,
  public publication, and post-publication verification.
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
- Local v1.2.27 release-branch validation passed with JDK 17.0.20, Android
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

- The v1.2.27 update was installed in place on the intended Samsung Fold
  running Android API 36. Before installation, the production package
  readback reported version code `29`, version name `1.2.26`; after it reported
  version code `30`, version name `1.2.27`.
- The update used `adb install -r`, preserving the existing package/data
  boundary, signing identity, package data directory, and first-install time.
  No uninstall, data clear, downgrade, app launch, widget add, widget tap,
  production Log/Purchase/Finish/Sync action, or synthetic production mutation
  was performed.
- Wireless ADB was disconnected after the final package readback, and the
  device list was empty. The phone was explicitly returned to normal use.
- These are package/readback facts only. CI emulator validation, source
  previews, and production-package installation must not be described as a
  physical widget UI walkthrough.

## Recommended next action

The v1.2.27 release is complete and its signed APK is available from the public
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
