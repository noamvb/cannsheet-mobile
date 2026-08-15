# Latest handoff

Last updated: 2026-08-14

## Current outcome

Cannsheet Mobile v1.3.2 is implemented, validated, and merged into `main`. The release
optimizes Insights and History refresh performance across the Google Apps Script backend
and Android Compose client, reducing refresh times from minutes down to sub-second cache
returns and fast single-RPC cold recalculations.

- Feature PR [#83](https://github.com/noamvb/cannsheet-mobile/pull/83) was squash-merged
  as commit `0462e3895e54d588523c932dcbbfaebca014ef04`.
- Version bump PR [#84](https://github.com/noamvb/cannsheet-mobile/pull/84) was squash-merged
  as commit `9118da294c65e8d89a4214f4946399ba0928929b`.
- Release metadata in `app/build.gradle.kts`: `versionName = "1.3.2"`, `versionCode = 33`.

## v1.3.2 performance changes

- **Fast-Path `CacheService` (`0462e38`, PR #83)**: Serialized analytics responses in Google
  Apps Script are cached in 100KB chunks in `CacheService` keyed by resource, environment, query
  parameters, and `MUTATION_WATERMARK`. Unchanged requests return in <200ms without touching the
  Google Sheets API.
- **Atomic Cache Invalidation (`0462e38`, PR #83)**: Any mutating write endpoint (`doPost` commit,
  `onFormSubmit`, `onInventoryEdit`, migrations) bumps `MUTATION_WATERMARK` in `CacheService`,
  immediately invalidating all cached responses without persistent property write overhead.
- **Single-RPC Batch Sheets Range Retrieval (`0462e38`, PR #83)**: Replaced sequential per-sheet
  `getRange().getValues()` reads with a consolidated `Sheets.Spreadsheets.Values.batchGet` call,
  eliminating serial roundtrip latency for cold recalculations.
- **Scoped Lock Concurrency (`0462e38`, PR #83)**: `ScriptLock` is held only during the atomic
  batch data fetch and released before in-memory aggregations and response serialization, eliminating
  `BACKEND_BUSY` read lock contention.
- **Instant Local Cache Presentation (`0462e38`, PR #83)**: In `AnalyticsCoordinator`, cached
  Room SQLite data is emitted immediately with `isInitialLoading = false`, rendering data at 0ms
  upon screen entry while network refreshes execute non-blocking in the background.
- **Version metadata (`9118da2`, PR #84)**: Bumped `versionCode = 33`, `versionName = "1.3.2"`
  in `app/build.gradle.kts`.

## Validation and provenance

- PR #83 CI gate [run 31859344672](https://github.com/noamvb/cannsheet-mobile/actions/runs/31859344672)
  passed all jobs (classification, backend validation, Android static validation, API 24 emulator,
  aggregate check).
- Version PR #84 CI gate [run 31859570262](https://github.com/noamvb/cannsheet-mobile/actions/runs/31859570262)
  passed all jobs.
- Local E2E verification suite (`tests/run_e2e_verification.sh`) passed 100%:
  - All 8 Node.js backend suites (`backend_analytics_test.js`, `backend_contract_test.js`,
    `fake_sheets_batch_update_test.js`, `backend_corrections_test.js`, `backend_recovery_test.js`,
    `backend_spreadsheet_test.js`, `sandbox_performance_fixture_test.js`, `sandbox_provisioning_test.js`).
  - Python benchmark suite (`tests/test_backend_sync_benchmark.py`: 13/13 tests OK).
  - Android JVM unit tests (`./gradlew testDebugUnitTest`: BUILD SUCCESSFUL).

## Data-safety notes

- Analytics endpoints remain strictly read-only and never mutate Google Sheets data rows.
- Cache invalidation is atomic via `MUTATION_WATERMARK`.
- Full backward compatibility with `analyticsVersion` 1 and 2 and offline Room queues is preserved.
- The production endpoint, signing configuration, package name, and credentials are unchanged.

## Relevant files

- `backend_additions.gs`
- `tests/fake_apps_script_runtime.js`
- `tests/backend_analytics_test.js`
- `app/src/main/java/com/example/ui/AnalyticsState.kt`
- `app/src/test/java/com/example/ui/AnalyticsCoordinatorTest.kt`
- `app/src/test/java/com/example/data/AnalyticsDataTest.kt`
- `app/build.gradle.kts`
- `docs/DECISIONS.md` (ADR-021)
- `docs/PROJECT_STATE.md`
- `docs/HANDOFF.md`
