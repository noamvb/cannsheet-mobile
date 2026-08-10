# Latest handoff

Last updated: 2026-08-10

## Outcome

Working branch `claude/background-sync-history-insights-2owoab` adds a
best-effort Insights/History analytics cache prefetch to the existing
six-hour periodic `SyncWorker` run. It is implemented and self-reviewed
against the current source, but **not merged, not released, and not
validated by any test run, build, or device check in this session** — see
"What could not be validated" below before relying on it.

The prior release state (Cannsheet Mobile v1.2.19, background synchronization
via [PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30), and product
usage totals) remains the last released and device-validated state; see
`docs/PROJECT_STATE.md` for that evidence. This handoff only covers the new
branch.

## What this branch changes

- New `app/src/main/java/com/example/data/sync/AnalyticsPrefetcher.kt`:
  reads the current Room `analytics_cache` row for Insights and History,
  skips a resource whose cache is under two hours old, otherwise re-fetches
  using whichever range/filters that cache row was generated for (or the
  default range / unfiltered History when no cache exists), and for History
  merges the fresh first page with cached events strictly older than that
  page's oldest event so paged depth survives a background refresh.
- `SyncScheduler.periodicRequest()` now sets a `prefetch_analytics` input-data
  flag; `immediateRequest()` does not.
- `SyncWorker.doWork()` runs the prefetch, best-effort, after the existing
  queue-sync step, only when the flag is set and the queue-sync result was
  `NothingToSync` or `Applied` — never after `Retry` or
  `EnvironmentMismatch`. A prefetch failure cannot change the returned
  `Result` or the recorded background-sync status.
- Two small extractions with no behavior change: `InsightsResponseDto.cachedInsightsRange()`
  and `runCatchingCancellable` moved from `AnalyticsState.kt` into
  `AnalyticsData.kt` (`internal`) so the prefetcher and the UI coordinator
  share them.
- No Room migration, no schema/version change, no new Settings control, no
  change to `BackgroundSyncRunner`, `SyncEngine`, or `Repository.kt`.
- Full design rationale and accepted trade-offs are in
  [ADR-008](DECISIONS.md#adr-008-warm-the-analytics-cache-from-periodic-background-sync).

## New and changed tests

- `app/src/test/java/com/example/data/sync/AnalyticsPrefetcherTest.kt` (new):
  freshness gate, range/filter reuse, the History merge algorithm, and
  per-resource failure isolation.
- `app/src/test/java/com/example/data/sync/SyncWorkerPrefetchGateTest.kt`
  (new): the complete `shouldPrefetchAnalytics` gating truth table.
- `app/src/test/java/com/example/data/sync/SyncSchedulerTest.kt`: asserts the
  new input-data flag on `periodicRequest()`/`immediateRequest()`.
- `app/src/androidTest/java/com/example/data/sync/SyncWorkerTest.kt`: both
  fake runtimes implement the new `prefetchAnalytics()` method; new cases
  cover periodic-only triggering, the disabled switch, retry/mismatch
  skipping prefetch, and a prefetch failure not affecting the reported queue
  result.

None of these were executed in this session — see below.

## What could not be validated

This session's environment had **no JDK 17+, no Android SDK, and no Node.js
runtime**. `./gradlew --no-daemon testDebugUnitTest ...` was attempted and
failed immediately with "Gradle requires JVM 17 or later to run. Your build
is currently configured to use JVM 8" (only JDK 1.8 was present, and there
was no Homebrew or other package manager available to install a newer one).
`node` was not on `PATH` at all.

As a result, **none of the following were run**:

- `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
- `node tests/backend_analytics_test.js`
- Any manual device/emulator validation (no simulator or physical device was
  available either)

The change was implemented by reading the exact existing signatures it
depends on (`AnalyticsRepository`, `CannsheetGraph.analyticsRepository`,
`BackgroundSyncRunner`/`BackgroundSyncRunResult`, `SyncScheduler`,
`BackgroundSyncWorkerRuntime`) and manually re-reading every edited file
after writing it, but **no automated or manual check confirms it compiles,
passes tests, or behaves correctly on a device**. The pull request is opened
as a draft specifically because of this, and states plainly that CI (the
`Cannsheet Android PR validation` aggregate check, including
`compileDebugAndroidTestKotlin` and the emulator matrix) is the first real
validation this change will receive.

## Recommended next action

1. Let CI run on the draft PR and read its result before treating this
   change as working — do not assume the code above is correct until CI (or
   a local environment with JDK 17+ and the Android SDK) confirms it
   compiles and the new tests pass.
2. If CI passes, perform the manual validation steps described in the
   original task plan (trigger the periodic `WorkManager` job via
   `adb shell cmd jobscheduler run -f`, confirm one `resource=insights` and
   one `resource=history` GET in `adb logcat`, confirm airplane-mode cold
   open shows the warmed cache, confirm the 2-hour floor skips a second
   immediate run, confirm the "Background sync" switch off skips prefetch,
   and confirm a filtered History cache is refetched with the same filters)
   using the sandbox build type so no production write path is touched.
3. Convert the draft PR to ready for review once CI and manual validation
   both pass, and record the exact results in `docs/PROJECT_STATE.md`.

## Safety review

- No credential, signing key, keystore, private spreadsheet identifier,
  personal absolute path, runtime database, build output, or downloaded APK
  is committed.
- This branch does not touch `BuildConfig.GAS_URL`, `APP_ENVIRONMENT`,
  `applicationId`, `versionCode`, `versionName`, signing configuration, or
  `backend_additions.gs`, and creates no tag or release.
- Prefetch is read-only against the backend (two GETs at most per periodic
  wake) and writes only the existing `analytics_cache` table; it does not
  touch the offline action queue, `SyncEngine`, or any Room migration.
