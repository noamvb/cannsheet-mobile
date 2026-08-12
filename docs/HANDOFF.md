# Latest handoff

Last updated: 2026-08-12

## Current outcome

Cannsheet Mobile v1.2.25 is implemented, released, published to the separate
GitHub releases repository, and installed in place on the production phone.
The home-screen pen widget feature was delivered through [PR #52](https://github.com/noamvb/cannsheet-mobile/pull/52),
squash-merged as `0e9bb650de7c9a3d7d629f20bedda5857528770b`. The separate
version-only [PR #53](https://github.com/noamvb/cannsheet-mobile/pull/53)
merged as `7c652fb48b4de5ba20b003abc828df9111124d73`; tag `v1.2.25` points to
that exact `main` tip.

The public signed release is [Cannsheet Mobile 1.2.25](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.25),
with `Cannsheet-Mobile-1.2.25.apk` and
`Cannsheet-Mobile-1.2.25.apk.sha256`. The independently downloaded APK
matched the published SHA-256:

`e6283536c4e00f60db473660370390bcf433e43d0d18dc7d49a3e8acbb7aa45a`

The implementation does not alter the Room schema, existing queue payloads,
Apps Script contract, production endpoint, application ID, or signing
configuration.

## Implementation

- The widget uses the platform `AppWidgetProvider` and `RemoteViews` APIs, with
  no Glance dependency, and remains compatible with API 24.
- It reuses the loaded-pen resolution, rate, date/time, and
  `ConsumptionLogger` boundaries. Seconds are display/input units only;
  `secondsToUses` converts before Room, the offline queue, or the wire.
- Submit captures the product, stable consumption ID, date, time, rate,
  displayed seconds, and converted uses in the `pen_widget_state` DataStore.
  Room persistence is deferred for five seconds through unique WorkManager
  work. The worker atomically takes the payload before using the shared logger
  and immediate sync scheduler.
- Undo and worker commit are arbitrated by the captured `commitId`. Cancelling
  WorkManager is only an optimization; a late worker becomes a no-op, and no
  already queued Room row is deleted. Overdue payloads are lazily flushed on
  widget broadcasts and application startup.
- The state model is `Unavailable`, `NoCart`, `RateOff`, `Composing`, or
  `AwaitingCommit`. The draft starts at zero, moves in ten-second steps, clamps
  to 0..600 seconds, and enables submit only for a positive value.
- Provider actions, application startup, loaded-cart/log/finish changes, and
  acknowledged sync work request widget refreshes. The v1.2.25 source also
  contains the API 24-safe `RemoteViews` rendering fix required by CI.

## Validation and provenance

- Feature PR gate [run 31621145764](https://github.com/noamvb/cannsheet-mobile/actions/runs/31621145764)
  passed all six jobs after the API 24 renderer fix.
- Version PR gate [run 31621733498](https://github.com/noamvb/cannsheet-mobile/actions/runs/31621733498)
  passed all required jobs.
- Exact post-merge `main` validation [run 31622170237](https://github.com/noamvb/cannsheet-mobile/actions/runs/31622170237)
  passed all six jobs, including API 24 and API 36 emulator validation,
  backend validation, Android static validation, classification/security, and
  aggregate checks.
- Signed publication [run 31622788837](https://github.com/noamvb/cannsheet-mobile/actions/runs/31622788837)
  passed exact-main proof, signed build, signature verification, checksum,
  public publication, and post-publication verification.
- Local `git diff --check` passed. The bundled Node runtime passed
  `tests/backend_contract_test.js`, `tests/backend_recovery_test.js`, and
  `tests/backend_analytics_test.js`; `python3 -m unittest tests/test_backend_sync_benchmark.py`
  passed all 13 tests. Local Gradle Android validation was not run for this
  release session because the available JVM is Java 8 while Gradle 9.3.1
  requires Java 17 or newer; the GitHub Actions Android matrix is the
  authoritative Android build/test evidence.
- Source previews and CI widget renderer/state tests are available. No
  production-device widget screenshot or action walkthrough was performed.

## Phone state and safety boundary

- The intended phone is a Samsung SM-F966W running Android API 36. Before the
  update, the production package readback reported version code `27`, version
  name `1.2.24`; after the update it reported version code `28`, version name
  `1.2.25`, with the `PenConsumptionWidgetProvider` receiver present.
- The signed APK was installed in place with `adb install -r`, preserving the
  existing package/data boundary. No uninstall, data clear, downgrade, app
  launch, widget add, widget tap, production Log/Purchase/Finish/Sync action,
  or synthetic production mutation was performed.
- Wireless ADB was disconnected immediately after the final package readback.
  The user was notified that the phone was safe to resume, and no further
  phone commands are pending.
- These are package/readback facts only. CI emulator validation, source
  previews, and production-package installation must not be described as a
  physical widget UI walkthrough.

## Recommended next action

The release is complete. If physical widget visual/action coverage is desired,
build or obtain a separate sandbox/debug package with a non-production endpoint
and test only that package: light/dark rendering, launcher sizing, +/- controls,
reset, countdown/Undo, and all local message states. Do not use the signed
production package for synthetic widget submissions or install a debug-signed
APK over it.

## Data-safety notes

- Seconds never enter the Room schema, offline queue, Apps Script request, or
  spreadsheet contract; only converted uses do.
- The five-second widget payload is short-lived DataStore UI state and is
  excluded from backup. Commit-ID arbitration makes Undo and late worker
  callbacks safe without deleting existing queued actions.
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
