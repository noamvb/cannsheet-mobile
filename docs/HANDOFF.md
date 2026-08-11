# Latest handoff

Last updated: 2026-08-11

## Current feature outcome

The Purchase autofill defaults feature is implemented, merged, signed, and
published in Cannsheet Mobile v1.2.21. It adds type-first explicit
suggestions, local normalized product/type defaults, saved-over-catalog
precedence, canonical THC handling, an opt-in default-save switch, and
purchase-before-default persistence after the existing Undo countdown. The
feature does not change the Room schema or Apps Script contract.

Feature PR #36 merged as
`5f6d1392a77067616bde43265278b77daf447f8e`; release PR #37 merged as
`b77bb4fdd9d4062d54d3bfb36837c7612b73eb6e`. The feature PR, release PR, both
full exact-main API 24/API 36 matrices, and signed publication workflow all
passed. The public release is
[Cannsheet Mobile v1.2.21](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.21)
with APK SHA-256
`e315a04300df297682ce19e3ff1e545a72824558daf720461cab59f3437545d0`.
Independent verification confirmed package `com.noamv.cannsheet.mobile`,
version code 24, version name 1.2.21, and the expected signing certificate.

Wireless ADB later reached the intended Samsung SM-F966W (`192.168.68.54:36595`)
without uninstalling or clearing Cannsheet data. Android read back production
package `com.noamv.cannsheet.mobile` at version code 24 / version name 1.2.21
(`targetSdk 36`). Obtainium showed Cannsheet Mobile from the public releases
repository as `v1.2.21 Installed / Latest`, with the expected signing
certificate and a disabled update button. The device was already current when
this session connected, so an Obtainium in-place update transition was not
observed and must not be claimed.

A read-only Purchase smoke check confirmed the type-first form, the available
type choices, and the new defaults switch visible and initially off. The local
product selector remained unavailable after choosing type `P`, so no
suggestion/default application could be exercised; no purchase was submitted.
The next genuine purchase remains the appropriate way to validate persistence
and restart/autofill behavior without fabricating production data. See
`docs/PROJECT_STATE.md` for the exact release and device evidence.

## Previous release outcome

Analytics prefetch (best-effort Insights/History cache warming from the periodic `SyncWorker` run) is **merged, released, and CI-validated**: [PR #33](https://github.com/noamvb/cannsheet-mobile/pull/33) and [PR #34](https://github.com/noamvb/cannsheet-mobile/pull/34) are both merged into `main`, and Cannsheet Mobile **v1.2.20** is published and signed. The code was implemented and self-reviewed without local test execution (this session's environment had no JDK 17+, Android SDK, or Node.js runtime â€” see "What could not be validated" below), so PR #33 was opened as a draft specifically for that reason. CI then validated everything before any tag was created: PR-level checks, two full push-to-main API 24/API 36 matrix runs (one per merge), and the signed release workflow all passed. See `docs/PROJECT_STATE.md` for the exact run IDs and commit SHAs. No manual device validation was performed.

The prior release before this work, Cannsheet Mobile v1.2.19 (background synchronization via [PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30), and product usage totals), is documented in `docs/PROJECT_STATE.md` for historical evidence. **v1.2.20 (this work) is now the current release.**

## Previous release: v1.2.20 changes

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
  `NothingToSync` or `Applied` â€” never after `Retry` or
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

## Previous release: v1.2.20 tests

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

None of these were executed in this session â€” see below. (Update: CI subsequently ran the equivalent checks â€” unit tests, lint, `compileDebugAndroidTestKotlin`, and two full API 24/API 36 matrix runs â€” and all passed before the release was tagged; see `docs/PROJECT_STATE.md` for exact run IDs. This section is kept as-is for an accurate record of what this session itself could verify.)

## Historical v1.2.20 validation note

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

## Current next actions

The v1.2.21 implementation, signed public release, current-device readback,
and Obtainium current-state check are complete. The remaining manual checks are:

1. Use the next genuine purchase to validate saved-default persistence and
   restart/autofill behavior; do not create a synthetic production purchase.
2. If an in-place Obtainium update must be witnessed explicitly, observe the
   next higher release update on the same device. The v1.2.21 transition was
   already complete before this ADB session began.
3. Separately complete the older v1.2.20 analytics-prefetch device checks
   described in `docs/PROJECT_STATE.md`.

## Historical v1.2.20 recommended actions

All of this has now happened: CI passed, PR #33 and PR #34 were merged, and v1.2.20 was tagged and published. The remaining recommended actions are the manual device validation steps that were never run in this session and have no CI substitute:

1. Trigger the periodic `WorkManager` job on a device (`adb shell cmd jobscheduler run -f`), confirm one `resource=insights` and one `resource=history` GET fire via `adb logcat`, and confirm a cold airplane-mode open shows the warmed cache with correction affordances still disabled.
2. Re-run the worker immediately after and confirm both resources are skipped (inside the 2-hour freshness floor).
3. Confirm the "Background sync" switch off skips prefetch, and confirm a filtered History cache is refetched with the same filters â€” using the sandbox build type so no production write path is touched.
4. Update this file and `docs/PROJECT_STATE.md`'s "Current priorities"/"Unresolved questions" once that device evidence exists.

## Safety review

- No credential, signing key, keystore, private spreadsheet identifier,
  personal absolute path, runtime database, build output, or downloaded APK
  is committed.
- The feature changed no `BuildConfig.GAS_URL`, `APP_ENVIRONMENT`,
  `applicationId`, signing configuration, or `backend_additions.gs`. The
  separate release PR changed only `versionCode` and `versionName`; the
  approved tag `v1.2.21` and public release were then created by the signed
  release workflow.
- Prefetch is read-only against the backend (two GETs at most per periodic
  wake) and writes only the existing `analytics_cache` table; it does not
  touch the offline action queue, `SyncEngine`, or any Room migration.