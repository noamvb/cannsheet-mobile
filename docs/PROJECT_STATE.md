# Project state

Last updated: 2026-08-11

## Repository state

- Canonical branch: `main`
- Released source commit and tag `v1.2.23`:
  `2e251a1d71aedbfe44e265c907a58d77ccd4d720`
- Current release metadata in `app/build.gradle.kts`: version name `1.2.23`,
  version code `26`
- Per-product-type quick-log quantity presets [PR #44](https://github.com/noamvb/cannsheet-mobile/pull/44)
  was squash-merged as `b9302edcc309e7ada5a30e528a091801df8fb568` after its
  PR checks passed in [run 31541378270](https://github.com/noamvb/cannsheet-mobile/actions/runs/31541378270)
  and the merged-main validation passed in
  [run 31541684164](https://github.com/noamvb/cannsheet-mobile/actions/runs/31541684164).
- Version-only release [PR #45](https://github.com/noamvb/cannsheet-mobile/pull/45)
  was squash-merged as `2e251a1d71aedbfe44e265c907a58d77ccd4d720` after its
  PR checks passed in [run 31542822264](https://github.com/noamvb/cannsheet-mobile/actions/runs/31542822264)
  and the exact versioned-main validation passed in
  [run 31543271407](https://github.com/noamvb/cannsheet-mobile/actions/runs/31543271407).
- The exact-main validation runs above completed successfully after targeted
  reruns of transient API 24 emulator failures; the reruns did not change the
  validated source commit.
- The v1.2.23 source tag/release is
  [Cannsheet Mobile 1.2.23](https://github.com/noamvb/cannsheet-mobile/releases/tag/v1.2.23).
- The signed publication workflow
  [run 31544318700](https://github.com/noamvb/cannsheet-mobile/actions/runs/31544318700)
  passed exact-main proof, version/secret/monotonicity checks, signed build,
  signature verification, public upload, and post-publication verification.
- The public signed release is
  [Cannsheet Mobile 1.2.23](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.23).
  Its exact APK asset is `Cannsheet-Mobile-1.2.23.apk` (13,525,786 bytes),
  accompanied by `Cannsheet-Mobile-1.2.23.apk.sha256`.
- Independently downloaded public APK SHA-256:
  `f9f319ba892f4e0f09c9bebc7a581fc1326bf162b1c21c06db653d051c8aab98`
- The downloaded public APK reports package `com.noamv.cannsheet.mobile`,
  version code `26`, version name `1.2.23`, and passes local v2 signature
  verification. It was installed in place on the intended wireless Android
  `the intended Android production device` with `adb install -r`; the app launched successfully afterward.
- The production readback before installation was version code `25`, version
  name `1.2.22`, signing identity `the release signing identity`, and data directory
  `the production app data directory`. The readback after installation
  was version code `26`, version name `1.2.23`, the same signing identity and
  the same data directory; install-time metadata remained unchanged. No
  uninstall, data clear, downgrade, or production data mutation was used.
- The previous public release had version code `25`; the new release is
  monotonic.
- History refresh feedback [PR #40](https://github.com/noamvb/cannsheet-mobile/pull/40)
  was squash-merged as `62d7cc6d960b0e13bdfd089152d14f8c20a308a1` after its
  feature validation passed.
- Version-only release [PR #41](https://github.com/noamvb/cannsheet-mobile/pull/41)
  was squash-merged as `633bd898ab59dc9d30acb2ba530a41e5f94c1e2a` after its
  PR checks and exact-main validation passed.
- Purchase autofill defaults feature [PR #36](https://github.com/noamvb/cannsheet-mobile/pull/36)
  was squash-merged as `5f6d1392a77067616bde43265278b77daf447f8e` after its
  PR and full API 24/API 36 validation passed.
- Version-only release [PR #37](https://github.com/noamvb/cannsheet-mobile/pull/37)
  was squash-merged as `b77bb4fdd9d4062d54d3bfb36837c7612b73eb6e` after its
  PR checks and exact-main validation passed.
- The feature PR checks passed [run 31458365282](https://github.com/noamvb/cannsheet-mobile/actions/runs/31458365282);
  the feature merge commit passed the full main matrix in
  [run 31458941204](https://github.com/noamvb/cannsheet-mobile/actions/runs/31458941204).
- The release PR checks passed [run 31459764234](https://github.com/noamvb/cannsheet-mobile/actions/runs/31459764234);
  the release merge commit passed the full main matrix in
  [run 31460010598](https://github.com/noamvb/cannsheet-mobile/actions/runs/31460010598).
- Signed publication workflow
  [run 31526429773](https://github.com/noamvb/cannsheet-mobile/actions/runs/31526429773)
  passed exact-main proof, version/secret/monotonicity checks, signed build,
  signature verification, public upload, and post-publication verification.
- The v1.2.22 feature PR checks passed
  [run 31524608644](https://github.com/noamvb/cannsheet-mobile/actions/runs/31524608644);
  the version-only PR checks passed
  [run 31525437265](https://github.com/noamvb/cannsheet-mobile/actions/runs/31525437265);
  and the exact release merge commit passed the full API 24/API 36 matrix in
  [run 31525848365](https://github.com/noamvb/cannsheet-mobile/actions/runs/31525848365).
- Background synchronization [PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30)
  and version-only release [PR #31](https://github.com/noamvb/cannsheet-mobile/pull/31)
  were squash-merged after their required validation passed.
- Analytics prefetch [PR #33](https://github.com/noamvb/cannsheet-mobile/pull/33) and version-only release [PR #34](https://github.com/noamvb/cannsheet-mobile/pull/34) were squash-merged after their required validation passed, and released as v1.2.20.
- The editable History milestone was delivered through backend
  [PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19), Android
  [PR #20](https://github.com/noamvb/cannsheet-mobile/pull/20), and production
  rollout hardening
  [PR #21](https://github.com/noamvb/cannsheet-mobile/pull/21),
  [PR #22](https://github.com/noamvb/cannsheet-mobile/pull/22), and
  [PR #23](https://github.com/noamvb/cannsheet-mobile/pull/23).

## Quick-log quantity presets by product type feature work

- The per-product-type quick-log quantity preset implementation was delivered
  through [PR #44](https://github.com/noamvb/cannsheet-mobile/pull/44), merged
  as `b9302edcc309e7ada5a30e528a091801df8fb568`, and released in v1.2.23.
  The version-only release [PR #45](https://github.com/noamvb/cannsheet-mobile/pull/45)
  merged as `2e251a1d71aedbfe44e265c907a58d77ccd4d720`.
- Global quick-log presets remain the fallback. Per-type overrides share the
  existing `consumption_preferences` DataStore in a version-1 JSON payload;
  there is no Room migration, queue contract change, Apps Script change, or
  production endpoint change.
- Product types use the canonical `P`, `E`, `J`, `F`, `S`, `K` codes and labels,
  unioned with normalized catalog types. The ViewModel resolves an effective
  preset for the selected type and the Settings UI supports editing, saving,
  resetting, and summarizing custom overrides.
- The exact local validation command passed with JDK 17.0.20, Gradle 9.3.1,
  Android platform `android-36.1`, and Build Tools `36.0.0`:
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
  It completed with `BUILD SUCCESSFUL`; 138 JVM tests completed in the final
  run, Android-test Kotlin compilation completed, lint completed, and the
  debug APK assembled.
- A temporary isolated `devicecheck` build was assembled only for bounded
  manual validation. The resulting APK was installed as package
  `the isolated devicecheck application`, version name
  `1.2.22-devicecheck` / version code `25`, alongside the production package.
  Its local artifact was
  `app/build/outputs/apk/devicecheck/app-devicecheck.apk` with SHA-256
  `6a748680fc91c2830d222beeb7b00050f9145a629ccc8f464e5a3c0d9ef6734a`.
- On the wireless Android `the intended Android production device`, Settings exposed all six product types;
  a Shatter override `0.1 / 0.25 / 0.5` saved and persisted across relaunch,
  appeared on the Shatter Log form, Pen showed the global `0.5 / 1 / 2`
  defaults, and reset returned Shatter to the global defaults. The existing
  global editor was also changed to `0.5 / 1.25 / 2`, saved, confirmed after
  relaunch, and restored to `0.5 / 1 / 2`; Edible with no override showed the
  default status and global fields. No log, purchase, finish, or sync action
  was submitted.
- The feature PR, merged-main validation, version PR, exact versioned-main
  validation, and signed publication workflow all passed in GitHub Actions;
  the exact run links and transient API 24 recovery boundary are recorded in
  the Repository state section above.
- The backend Node/Python suites were not run locally because the backend was
  unchanged; the required backend checks passed in CI. Connected instrumentation
  for the temporary `devicecheck` build was not run because that build type has
  no dedicated connected-test task; the new Android test source compiled in the
  local validation command. The bounded devicecheck walkthrough remains a
  manual UI check and is not evidence that the signed v1.2.23 production APK
  has been installed.
- The public v1.2.23 APK has been independently downloaded, checksum-checked,
  metadata-checked, and signature-checked locally. The successful production
  install and package readback are recorded in the Repository state section
  above and in `docs/HANDOFF.md`.

## Purchase autofill defaults feature work

- The approved implementation was delivered through
  [PR #36](https://github.com/noamvb/cannsheet-mobile/pull/36) and is included
  in released main commit
  `b77bb4fdd9d4062d54d3bfb36837c7612b73eb6e`.
- The Purchase screen now supports type-first, explicit-suggestion autofill;
  normalized product/type keys; saved-default precedence over catalog values;
  canonical THC fractions; and an opt-in save-default switch for cost, THC,
  and grams. Defaults are stored in a dedicated version-1 Preferences
  DataStore and are not part of Room or the Apps Script queue contract.
- The Undo countdown captures an immutable submission. After confirmation,
  Room purchase persistence completes before the optional DataStore write;
  default-write failure leaves the purchase queued and reports separate
  feedback from sync status.
- Local focused tests and the backend analytics benchmark passed. The full
  local release-branch Android command was attempted, but this machine's
  protected Android SDK metadata/license access caused the Gradle attempt to
  time out; GitHub CI is the authoritative Android evidence for the release.
- CI passed the feature PR, full post-merge API 24/API 36 matrix, release PR,
  full release-merge matrix, and signed publication workflow described above.
- Wireless ADB later reached the intended Android `the intended Android production device` at
  `the device wireless endpoint`. Production package readback reported
  `com.noamv.cannsheet.mobile`, version code `24`, version name `1.2.21`,
  `targetSdk 36`, and `the post-install timestamp`. Obtainium showed
  Cannsheet Mobile from `https://github.com/noamvb/cannsheet-mobile-releases`
  as `v1.2.21 Installed / Latest`, with the expected certificate and its
  update button disabled. The device was already current when connected, so
  an Obtainium in-place update transition was not observed.
- A read-only Purchase-screen check confirmed the type-first form, type
  choices, and visible initially-off defaults switch. After selecting type
  `P`, the local product selector remained unavailable, so suggestion/default
  application and a real purchase were intentionally not exercised.

## History refresh feedback feature work

- The focused implementation was delivered through
  [PR #40](https://github.com/noamvb/cannsheet-mobile/pull/40), squash-merged
  as `62d7cc6d960b0e13bdfd089152d14f8c20a308a1`, version-bumped through
  [PR #41](https://github.com/noamvb/cannsheet-mobile/pull/41), and released as
  Cannsheet Mobile v1.2.22 in main commit
  `633bd898ab59dc9d30acb2ba530a41e5f94c1e2a`.
- `historyNeedsRefreshForCorrections` is now the shared correction-gate and
  stale-refresh predicate. `AnalyticsCoordinator` exposes an idempotent
  `refreshHistoryIfNotCurrent()` entry point, and `CannsheetViewModel` delegates
  it to the History UI.
- History renders refresh progress in its header and inside the open detail
  sheet, reports refresh failures inside the sheet, starts one refresh when a
  stale/cached entry is opened, tracks the opened entry by UUID, and explains
  when a successful refresh removes it from the current page.
- JVM and Compose regression tests were added for the shared predicate,
  idempotent coordinator behavior, visible progress/error states, automatic
  refresh, and missing-entry sheet closure.
- GitHub Actions run [31523716900](https://github.com/noamvb/cannsheet-mobile/actions/runs/31523716900)
  passed the security/classification, backend, Android static, API 24
  instrumentation, and aggregate validation jobs for the code commit. The
  static job uploaded the debug APK artifact with ZIP digest
  `dcf7108717d33233bc571f00d396e7a3022a6878e9328a1272feb11ac617dc9b`.
- The version PR checks passed [run 31525437265](https://github.com/noamvb/cannsheet-mobile/actions/runs/31525437265),
  and the merged v1.2.22 commit passed the full API 24/API 36 main matrix in
  [run 31525848365](https://github.com/noamvb/cannsheet-mobile/actions/runs/31525848365).
- The signed publication workflow [run 31526429773](https://github.com/noamvb/cannsheet-mobile/actions/runs/31526429773)
  passed exact-main provenance, release-secret validation, signed APK
  construction, signature/metadata checks, publication, and post-publication
  verification. The public APK SHA-256 is
  `e02debc3efd922ee6005fcf2798d775b8e7d5e9ec7b0e0542d73171a3ea0ad32`.
- The exact local Android command was attempted with Gradle 9.3.1 and JDK
  17.0.20, but this Mac has no Android SDK and Gradle stopped with “SDK location
  not found”. CI is the authoritative build/test evidence for this branch.
- Wireless ADB reached the intended Android `the intended Android production device`. The published signed
  APK installed in place with `adb install -r`, changing the production package
  from version code `24` / version name `1.2.21` to version code `25` / version
  name `1.2.22`. The package signing identity remained `the release signing identity`, and the
  data directory remained `the production app data directory`; no
  uninstall, data clear, downgrade, or synthetic purchase/correction was
  performed.
- After the user unlocked the phone on 2026-08-11, bounded live validation
  reached Insights → History on the installed production package. With network
  available, History displayed saved rows, showed the automatic `Refreshing
  History…` progress state, settled back to rows, and opened an existing event
  with the online Correct/Void controls visible. A list-header refresh with rows
  already visible also showed the progress indicator and `Refreshing History…`
  text while the request was in flight.
- The network-interruption portion was exercised by starting a History refresh and
  disabling the phone's network; wireless ADB dropped as expected and a screen
  recording was pulled for local evidence. Once connectivity was restored,
  History returned to its saved rows. Because ADB was unavailable during the
  interruption, the transient offline error inside the sheet and the complete
  cold-open/manual-offline sequence were not independently read back; do not
  treat them as passed.
- No real correction, void, restore, purchase, or other production data mutation
  was performed. Correction-save success, rotation with an open sheet, and
  missing-entry dialog behavior remain unverified. The temporary recordings were
  removed from the phone after being pulled locally and are not committed because
  they include live History data.
- Phone use ended with airplane mode off, Wi-Fi/ADB restored, and no app data
  changed.

## Background synchronization feature work

Background synchronization was delivered in
[PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30) and released in
Cannsheet Mobile v1.2.19. It is Android-only: it does not change the Apps
Script backend, Room schema, or Room version.

The approved feature uses a process-wide `CannsheetGraph` with a shared mutex
and routes foreground and WorkManager queue attempts through one `SyncEngine`.
Connected immediate work is a serial unique `APPEND_OR_REPLACE` chain; periodic
retry is one six-hour unique `UPDATE` request. A DataStore toggle is a local
kill switch. The implementation does not change the Apps Script backend or the
Room schema/version.

Local validation passed 85 JVM tests, Android-test Kotlin compilation, lint
with no errors, debug APK assembly, and the unchanged backend analytics test.
The exact merged feature commit `4da427d` passed full main validation in
[run 31350500266](https://github.com/noamvb/cannsheet-mobile/actions/runs/31350500266),
including API 24, API 36, and the aggregate check. The version-only PR passed
[run 31351238565](https://github.com/noamvb/cannsheet-mobile/actions/runs/31351238565),
and exact release commit `009d38c` passed the same full main matrix in
[run 31351485515](https://github.com/noamvb/cannsheet-mobile/actions/runs/31351485515).

Physical-device validation used an isolated test application ID on a Android
the intended Android production device running Android 16 / API 36; the installed production and older
sandbox apps were untouched. All 39 connected sandbox instrumentation tests
passed. A process-dead airplane-mode test moved one queued action to the
sandbox Sheet within seconds of reconnect, left zero pending actions, and
showed a just-now successful Settings result. JobScheduler showed the periodic
and connected-only immediate jobs. Bounded event and ledger readbacks proved
one accepted request with no duplicate. The disabled switch kept a second
action local through reconnect; re-enabling drained it once. A final three-item
offline batch produced exactly three event rows sharing one request UUID and
one accepted ledger row with consumption count 3; delayed row counts remained
unchanged. The signed publication workflow
[31351814290](https://github.com/noamvb/cannsheet-mobile/actions/runs/31351814290)
published v1.2.19 after confirming that exact validated main commit.

Analytics prefetch (best-effort Insights/History cache warming from the periodic `SyncWorker` run) was delivered in [PR #33](https://github.com/noamvb/cannsheet-mobile/pull/33), version-bumped in [PR #34](https://github.com/noamvb/cannsheet-mobile/pull/34), and released as Cannsheet Mobile v1.2.20. It is gated by a new `prefetch_analytics` WorkManager input-data flag that only `SyncScheduler.periodicRequest()` sets; `AnalyticsPrefetcher` runs after the existing queue sync only when that run result is `NothingToSync` or `Applied` (never after `Retry` or `EnvironmentMismatch`), and only when the existing "Background sync" DataStore switch is on. No new Settings control was added. It re-reads whichever Insights range or History filters the current Room `analytics_cache` row was generated for (falling back to the default range and unfiltered History when no cache row exists), and skips a resource entirely when its cache is already less than two hours old. The History write merges the fresh first page with cached events strictly older than the fresh page's oldest event, rather than replacing deeper cached pages with a single page 1. `Database.kt`'s Room schema, version, and the `BackgroundSyncRunner` queue path are unchanged; `AnalyticsRepository`/`AnalyticsDataSource` gained no new methods. The three accepted trade-offs (a foreground/background write race resolved by Room `REPLACE` last-writer-wins, retained History events not re-validated against a corrected `sourceRevision.dataVersion` until the next live refresh, and a REPLACE correction that moves an event earlier than the fresh page's oldest event being unrecoverable without a full refetch) are recorded in [ADR-008](DECISIONS.md#adr-008-warm-the-analytics-cache-from-periodic-background-sync).

The implementing session's environment had no JDK 17+, no Android SDK, and no Node.js runtime, so none of `testDebugUnitTest`, `compileDebugAndroidTestKotlin`, `lintDebug`, `assembleDebug`, or `node tests/backend_analytics_test.js` could be executed locally; `./gradlew` itself refused to run under the only available JDK (1.8). The code was implemented and manually re-read against the existing `AnalyticsRepository`, `CannsheetGraph`, `SyncScheduler`, and `SyncWorker` source without local execution, and PR #33 was opened as a draft specifically because of that. CI then validated it: PR #33's checks passed
[run 31421082505](https://github.com/noamvb/cannsheet-mobile/actions/runs/31421082505),
the merge-to-main commit `f1ebdaa` passed the full matrix in
[run 31423351995](https://github.com/noamvb/cannsheet-mobile/actions/runs/31423351995),
PR #34's checks passed
[run 31424577797](https://github.com/noamvb/cannsheet-mobile/actions/runs/31424577797),
and the version-bump merge commit `d91444a` (the exact tagged/released commit) passed the full matrix in
[run 31424975576](https://github.com/noamvb/cannsheet-mobile/actions/runs/31424975576).
The signed publication workflow
[run 31426000025](https://github.com/noamvb/cannsheet-mobile/actions/runs/31426000025)
published v1.2.20 after confirming that exact validated main commit. No manual device validation was performed for this release; see "Current priorities" below.

## Product usage totals release

Release v1.2.18 adds correction-safe product usage totals to the Log screen
without changing the API version, endpoint, signing identity, or spreadsheet
schema:

- The ordinary catalog GET exposes `totalUses` from the existing
  `Purchases.Uses` projection. Blank cells become confirmed zero; invalid,
  negative, or non-finite values fail the refresh rather than replacing Room
  data.
- Room schema version 10 stores the nullable confirmed value. A 9-to-10
  migration leaves existing products and pending queues intact.
- A reactive grouped query exposes pending durable consumption quantities by
  product. Existing acknowledgement and borrowed-product ID remapping rules
  remain unchanged.
- The selected product and Recent Products cards show separate `Synced` and
  `Pending` lines; the picker is unchanged. Pending is never added into the
  confirmed value locally.

The backend response was validated in sandbox deployment version 14, then
promoted to production deployment version 13 after a verified full-spreadsheet
backup. Catalog totals matched both `Purchases.Uses` and Insights
`allTime.quantity` for every product, and bounded before/after readbacks showed
that validation GETs did not modify the projection. The Android feature is
merged, tagged, signed, published, and covered by API 24 and API 36 emulator
tests. Physical-phone installation, screenshots, and the manual offline/borrowed
acceptance scenarios remain unverified.

## Project summary

Cannsheet Mobile is a personal Android app for logging cannabis purchases and
consumption. It stores products, pending actions, interaction metadata, sync
state, and analytics cache data locally. It communicates with a Google Apps
Script web app whose checked-in source reads and writes Google Sheets.

## Verified implemented areas

Repository code and validation show:

- Tiered GitHub Actions validation with repository/security classification,
  backend tests, Android static checks, API 24/36 emulator coverage, and the
  required `Cannsheet Android PR validation` aggregate.
- A tag-triggered signed release workflow with exact-main validation,
  version-code monotonicity checks, APK signing verification, checksum
  generation, public asset verification, and overwrite protection.
- Compose screens for logging purchases and consumption, viewing Insights and
  History, editing eligible History entries, and changing settings.
- Room-backed offline queues for purchases, consumption, finish actions, and
  consumption corrections.
- Stable request/action IDs, acknowledgement-based queue deletion,
  duplicate-safe retry handling, persisted request identity, and
  production/sandbox environment checks.
- Versioned Insights and History responses, Room analytics caching, pagination,
  stale-cursor handling, and data-quality warnings.
- Correct, Void, Restore, optional correction reasons, safe product reopening,
  and explicit cancellation of pending corrections.
- A forward-only Room 8-to-9 migration and safe legacy defaults for the
  correction-aware version-2 network contract.
- A sandbox Android build type and fake Apps Script/Sheets runtimes for backend,
  spreadsheet, recovery, analytics, and provisioning regression coverage.

## Production correction state

- Production provisioning and full recoverable reconciliation completed from
  the merge-verified PR #23 source.
- The existing production Apps Script deployment is version 13. Version 12
  remains the rollback point from before the additive `totalUses` response.
- The public production endpoint reports API version 2, production environment,
  correction schema version 1, and correction writes enabled.
- Reconciliation was clean immediately before and after provisioning and before
  write enablement: no pending apply, incomplete journal, reported difference,
  or blocking difference.
- Production row counts are deliberately not recorded as durable expectations
  because ordinary app entries continued during rollout.
- The correction sheet remained exact-header and otherwise empty during
  rollout. Codex did not send a valid production correction request.

## Release and validation status

v1.2.23 evidence (current release):

- Quantity-preset feature PR #44's final checks passed [run 31541378270](https://github.com/noamvb/cannsheet-mobile/actions/runs/31541378270); its squash merge commit `b9302edcc309e7ada5a30e528a091801df8fb568` passed the exact merged-main matrix in [run 31541684164](https://github.com/noamvb/cannsheet-mobile/actions/runs/31541684164).
- Version PR #45's checks passed [run 31542822264](https://github.com/noamvb/cannsheet-mobile/actions/runs/31542822264); its version-bump merge commit `2e251a1d71aedbfe44e265c907a58d77ccd4d720` passed the exact versioned-main matrix in [run 31543271407](https://github.com/noamvb/cannsheet-mobile/actions/runs/31543271407), after targeted recovery of the transient API 24 emulator failure.
- Signed publication workflow [run 31544318700](https://github.com/noamvb/cannsheet-mobile/actions/runs/31544318700) passed exact-main validation, release-secret validation, signing, publication, and post-publication verification.
- The public release is [Cannsheet Mobile 1.2.23](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.23): `Cannsheet-Mobile-1.2.23.apk` (13,525,786 bytes) and its `.sha256`. Independently downloaded APK SHA-256: `f9f319ba892f4e0f09c9bebc7a581fc1326bf162b1c21c06db653d051c8aab98`.
- The publication workflow independently verified the expected signing certificate and confirmed package `com.noamv.cannsheet.mobile`, version code `26`, and version name `1.2.23`. Local `aapt2` and `apksigner` checks matched those values and passed APK Signature Scheme v2 verification.
- Wireless ADB device readback: Android `the intended Android production device`, Android 16 / API 36. The production package changed from version code `25` / version name `1.2.22` to version code `26` / version name `1.2.23`; the signing identity remained `the release signing identity`, the data directory remained `the production app data directory`, and install-time metadata remained unchanged.
- The in-place production update succeeded with `adb install -r`; no uninstall, data clear, downgrade, or production data action was performed. The app launched as `com.example.MainActivity` and was force-stopped for handoff.

Prior release (v1.2.22) evidence, retained for history:

- History refresh feedback PR #40's final checks passed [run 31524608644](https://github.com/noamvb/cannsheet-mobile/actions/runs/31524608644); its feature merge commit `62d7cc6d960b0e13bdfd089152d14f8c20a308a1` was included in the exact release merge.
- Release PR #41's checks passed [run 31525437265](https://github.com/noamvb/cannsheet-mobile/actions/runs/31525437265); its version-bump merge commit `633bd898ab59dc9d30acb2ba530a41e5f94c1e2a` passed the full API 24/API 36 matrix in [run 31525848365](https://github.com/noamvb/cannsheet-mobile/actions/runs/31525848365).
- Signed publication workflow [run 31526429773](https://github.com/noamvb/cannsheet-mobile/actions/runs/31526429773) passed exact-main validation, release-secret validation, signing, publication, and post-publication verification.
- The public release is [Cannsheet Mobile 1.2.22](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.22): `Cannsheet-Mobile-1.2.22.apk` (13,509,402 bytes) and its `.sha256`. Independently downloaded APK SHA-256: `e02debc3efd922ee6005fcf2798d775b8e7d5e9ec7b0e0542d73171a3ea0ad32`.
- The publication workflow independently verified the expected signing certificate and confirmed package `com.noamv.cannsheet.mobile`, version code `25`, and version name `1.2.22`.
- Wireless ADB device readback: Android `the intended Android production device`, Android 16 / API 36. `dumpsys package` before and after installation reported the same production signing identity `the release signing identity` and data directory; the package changed from version code `24` / version name `1.2.21` to version code `25` / version name `1.2.22`, with `the post-install timestamp` after installation.
- The in-place production update succeeded with `adb install -r`; no uninstall or data clear was performed. Bounded online History refresh validation was completed after the phone was unlocked; the remaining offline in-sheet error, correction-save, rotation, and missing-entry cases are recorded as unverified in the feature section above.
- The Purchase UI rendered with type-first selection and the new
  `Use these values as future defaults for this product and type` switch. The
  product selector was disabled after selecting type `P` in this session, so
  no production purchase or synthetic autofill test was performed.

Prior release (v1.2.20) evidence, retained for history:

- PR #33's checks passed [run 31421082505](https://github.com/noamvb/cannsheet-mobile/actions/runs/31421082505); the merge-to-main commit `f1ebdaa` passed the full API 24/API 36 matrix in [run 31423351995](https://github.com/noamvb/cannsheet-mobile/actions/runs/31423351995).
- PR #34's checks passed [run 31424577797](https://github.com/noamvb/cannsheet-mobile/actions/runs/31424577797); the version-bump merge commit `d91444a` (the exact tagged/released commit) passed the full matrix in [run 31424975576](https://github.com/noamvb/cannsheet-mobile/actions/runs/31424975576).
- Signed publication workflow [run 31426000025](https://github.com/noamvb/cannsheet-mobile/actions/runs/31426000025) passed the exact-main/tag/version gate, signed build, signature verification, checksum generation, public upload, and post-publication verification.
- The public release is [Cannsheet Mobile 1.2.20](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.20): `Cannsheet-Mobile-1.2.20.apk` (13,493,018 bytes) and its `.sha256`. APK SHA-256: `c4e96df1b1f158a4119def985ce6a6e0a1cd28463234435f509ceea84cc3532b` (independently re-fetched from the public release and confirmed to differ from the v1.2.19 checksum below, proving a distinct build; local `aapt`/`apksigner` re-verification was not performed in this session, but the release workflow's own post-publication step ran that exact check and passed).
- No manual device installation or Obtainium update was observed in this session.

Prior release (v1.2.19) evidence, retained for history:

Private-source evidence:

- Product usage totals PR #27 and its explicit full feature-branch run
  [31335057894](https://github.com/noamvb/cannsheet-mobile/actions/runs/31335057894)
  passed backend, Android static, API 24, API 36, and aggregate validation.
- Exact merged feature commit
  `039259117d231620b21a37136130de122239537c` passed push workflow run
  [31335500140](https://github.com/noamvb/cannsheet-mobile/actions/runs/31335500140).
- Version-only PR #28 passed required workflow run
  [31337182904](https://github.com/noamvb/cannsheet-mobile/actions/runs/31337182904)
  after retrying one external Android SDK archive HTTP 404; no code change was
  required for the retry.
- Exact release commit
  `e93883b5a3cb7e98160a59489677fd87e0bb217a` passed push workflow run
  [31337505912](https://github.com/noamvb/cannsheet-mobile/actions/runs/31337505912):
  classification/security, backend validation, Android static validation,
  API 24, API 36, and aggregate validation.

Signed-publication evidence:

- Annotated tag `v1.2.19` resolves to the exact release commit above.
- Signed release workflow run
  [31351814290](https://github.com/noamvb/cannsheet-mobile/actions/runs/31351814290)
  passed the exact-main/tag/version gate, tests, lint, signed build, signature
  verification, checksum generation, public upload, and post-publication
  verification.
- The public release contains exactly one APK (13,493,018 bytes) and its
  `.sha256` file, plus GitHub's automatic source archives.
- The preceding v1.2.18 release remains historical evidence only; its signed
  release workflow run
  [31343252239](https://github.com/noamvb/cannsheet-mobile/actions/runs/31343252239)
  passed tag/main/version checks, tests, lint, signed build, signature
  verification, checksum generation, publication, and post-publication
  verification.
- The public release contains one APK (13,241,161 bytes) and its `.sha256`
  release asset, plus GitHub's automatic source archives.

Independent public-artifact evidence:

- The independently downloaded v1.2.19 checksum file matched the APK:
  `86773a13c4633034fda8e67b033b2e2ec924333442ad5401ef2ae7d31bd2a747`.
- Android `aapt` reported application ID `com.noamv.cannsheet.mobile`,
  version code `22`, and version name `1.2.19`.
- Android `apksigner` verified APK Signature Scheme v2 with one signer.
- Signing certificate SHA-256:
  `the release signing certificate`

Local and device evidence:

- The exact local Android static command passed:
  `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
- The background-sync branch passed the isolated physical-device checks
  described above; a local screenshot captured its enabled, zero-pending,
  just-now-successful Settings state.
- No full manual product-total acceptance workflow was performed. The bounded
  History recording and online refresh readback are preserved locally, but the
  offline in-sheet error, correction save, rotation, and missing-entry portions
  are not independently verified.

## Known limitations

- `app/src/main/res/xml/backup_rules.xml` and
  `app/src/main/res/xml/data_extraction_rules.xml` remain sample/template rules;
  the latter contains a backup-selection TODO.
- The Kotlin namespace remains `com.example` while the application ID is
  `com.noamv.cannsheet.mobile`; `README.md` records this as an intentional
  source-layout compatibility choice.
- The public APK is independently verified by the signed publication workflow
  as update-compatible by package, higher version code, and signing
  certificate. The intended phone's v1.2.22 in-place update transition was
  observed live and the production package/data directory remained intact.
- The first real production correction lifecycle still requires a deliberate
  user/device check; no synthetic production correction was created for testing.

## Current priorities

1. Use the next genuine production purchase to validate default persistence and
   restart/autofill behavior without fabricating data.
2. If a live Obtainium transition must be witnessed explicitly, observe the
   next higher release update on the same device; v1.2.22 was installed via
   ADB in this session, while the phone was locked before an Obtainium/UI
   transition could be exercised.
3. Continue the existing v1.2.20 device checks for analytics prefetch and
   correction-safe usage totals.

## Unresolved questions

- Obtainium's post-install refresh was not observed in this session. The full
  History UI plan remains partially unverified: the offline error inside the
  detail sheet, correction save, rotation, and missing-entry dialog.
- Does a genuine product suggestion on the installed v1.2.22 app apply and
  preserve a saved Purchase default across restart? The product selector was
  unavailable during this read-only session, and no synthetic purchase was
  created.
- Do the confirmed-versus-pending totals remain clear during real offline,
  retry, borrowed-product, and correction workflows on the intended phone?
- Does the periodic worker's analytics prefetch actually warm the Insights/History cache on a real device (no emulator/CI substitute exists for this), and does the 2-hour freshness floor behave as expected across real wake intervals?

These require device evidence and should not be answered from repository or
workflow evidence alone.

## Relevant paths

- `app/src/main/java/com/example/ui`
- `app/src/main/java/com/example/data`
- `app/src/main/java/com/example/data/sync`
- `app/src/test`
- `app/src/androidTest`
- `tests/backend_corrections_test.js`
- `backend_additions.gs`
- `sandbox_provisioning.gs`
- `app/build.gradle.kts`
- `.github/workflows`
- `docs/HANDOFF.md`
