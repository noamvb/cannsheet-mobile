# Latest handoff

Last updated: 2026-08-13

## Current outcome

Cannsheet Mobile v1.3.1 is implemented, documented, released, published, and
independently verified. The remediation sequence addressed post-release
findings R1 through R7 across source PRs [#75](https://github.com/noamvb/cannsheet-mobile/pull/75)
through [#79](https://github.com/noamvb/cannsheet-mobile/pull/79), documentation
PR [#80](https://github.com/noamvb/cannsheet-mobile/pull/80), and the version-only
release PR [#81](https://github.com/noamvb/cannsheet-mobile/pull/81).

Annotated tag `v1.3.1` points to exact validated `main` commit
`b3575ea51c14cb58797f1f9e9cf2ecfcb41be408`.

The public signed release is
[Cannsheet Mobile 1.3.1](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.3.1),
with authored assets `Cannsheet-Mobile-1.3.1.apk` and
`Cannsheet-Mobile-1.3.1.apk.sha256`. An independent download matched SHA-256:

`fb729f569dde1c1cd604582dc38e4bd6e8155489d7d6515f79e73879c076cdd3`

Local `aapt` inspection reported package `com.noamv.cannsheet.mobile`, version
code `32`, version name `1.3.1`, minimum SDK 24, target SDK 36, and launcher
`com.example.MainActivity`. Local `apksigner verify` confirmed APK Signature
Scheme v2. Its certificate and public key digests match the public v1.3.0 and
v1.2.27 APKs and the APK installed on the production phone, preserving in-place
update compatibility.

The implementation does not change the production Apps Script, spreadsheet,
endpoint, application ID, namespace, Room schema, existing queue payloads, or
wire contracts.

## v1.3.1 remediation changes

- **R2 (`5534416`, PR #75)**: Corrected `queue_alert_stuck_body` plurals in
  `strings.xml` to accurately describe the phone's non-empty queue episode duration
  rather than claiming per-entry age.
- **R3 (`c988aa5`, PR #76)**: Removed double-padding `navigationBarsPadding()` on
  `AppNavigationRail` so the navigation rail avoids redundant bottom offset on
  expanded/medium viewports.
- **R4 (`8f2eff4`, PR #77)**: Added structured `RunwaySuppressionReason` enum and
  `RunwayEstimateState.Suppressed(reason)` data class, rendering an explanatory
  card with clear grammar and pluralization in `RunwaySection` when estimates are paused.
- **R5 / D1 (`f522202`, PR #78)**: Enforced a 2-minute debounce floor
  (`RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS`) on Log-screen-initiated analytics
  refreshes, preventing redundant Apps Script reads during multi-entry logging sessions
  while keeping the active Insights tab immediate.
- **R7 (`90a6e25`, PR #79)**: Removed unread `selectedRangeDayCount` presentation
  state from `RunwayEstimateState.Ready`.
- **R1, R6, ADR-020 (`0288619`, PR #80)**: Corrected backup policy documentation in
  `HANDOFF.md`, added ADR-020, updated `ARCHITECTURE.md`, `PROJECT_STATE.md`, and
  committed `docs/V1_3_1_FIX_PLAN.md`.
- **Version metadata (`b3575ea`, PR #81)**: Bumped `versionCode = 32`, `versionName = "1.3.1"`
  in `app/build.gradle.kts`.

## Validation and provenance

- Each PR passed CI gates and the exact-main push runs on `main` passed all six jobs
  (classification, backend validation, Android static validation, API 24 emulator,
  API 36 emulator, and required aggregate status).
- The version-only local gate (`./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`)
  completed with `BUILD SUCCESSFUL`; 343 JVM unit tests passed with 0 failures, 0 errors,
  and 0 skips.
- The python benchmark test (`tests/test_backend_sync_benchmark.py`) passed (13 tests OK).
- Version PR #81 gate [run 31748102654](https://github.com/noamvb/cannsheet-mobile/actions/runs/31748102654)
  and exact versioned-main [run 31748421571](https://github.com/noamvb/cannsheet-mobile/actions/runs/31748421571)
  passed all six jobs.
- Signed publication [run 31748906313](https://github.com/noamvb/cannsheet-mobile/actions/runs/31748906313)
  passed exact-SHA proof, protected signing, version monotonicity, metadata verification,
  checksum generation, and public release publication.
- Independent public verification checked authored asset names, SHA-256 checksum,
  metadata (package, versionCode 32, versionName 1.3.1, minSdk 24, targetSdk 36),
  v2 signature, and certificate/signer continuity against v1.3.0 and v1.2.27.

## Device state and Obtainium installation

- Wireless ADB was not connected during Phase 5 verification; per Section 5.2 of the
  remediation plan, physical display measurement was skipped and Phase 9 (two-pane
  threshold lowering) was not executed without device evidence.
- The phone owner updates to v1.3.1 directly through Obtainium by checking for updates
  from the release repository `noamvb/cannsheet-mobile-releases`.

## Data-safety notes

- Queue alerts read aggregate state only and never receive queue rows or entry
  details. Their copy contains no product names, quantities, or dates.
- Queue persistence, immutable IDs, acknowledgement-only deletion, retries,
  environment checks, and `CannsheetGraph.syncMutex` synchronization remain
  unchanged.
- Runway and spend estimates are neither persisted nor transmitted and
  pause gracefully when their evidence is not fresh and complete.
- The production backend and spreadsheet were not changed or probed.

## Relevant files

- `app/src/main/java/com/example/ui/RunwayPresentation.kt`
- `app/src/main/java/com/example/ui/AnalyticsState.kt`
- `app/src/main/java/com/example/ui/InsightsScreen.kt`
- `app/src/main/java/com/example/ui/AppNavigation.kt`
- `app/src/main/res/values/strings.xml`
- `app/build.gradle.kts`
- `docs/V1_3_1_FIX_PLAN.md`
- `docs/PROJECT_STATE.md`
- `docs/DECISIONS.md`
- `docs/ARCHITECTURE.md`
