# Project state

Last updated: 2026-08-11

## Repository state

- Canonical branch: `main`
- Released source commit and tag `v1.2.21`:
  `b77bb4fdd9d4062d54d3bfb36837c7612b73eb6e`
- Current release metadata in `app/build.gradle.kts`: version name `1.2.21`,
  version code `24`
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
  [run 31460436036](https://github.com/noamvb/cannsheet-mobile/actions/runs/31460436036)
  passed exact-main proof, version/secret/monotonicity checks, signed build,
  signature verification, public upload, and post-publication verification.
- Background synchronization [PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30)
  and version-only release [PR #31](https://github.com/noamvb/cannsheet-mobile/pull/31)
  were squash-merged after their required validation passed.
- Analytics prefetch [PR #33](https://github.com/noamvb/cannsheet-mobile/pull/33) and version-only release [PR #34](https://github.com/noamvb/cannsheet-mobile/pull/34) were squash-merged after their required validation passed, and released as v1.2.20.
- The source tag/release is
  [Cannsheet Mobile 1.2.21](https://github.com/noamvb/cannsheet-mobile/releases/tag/v1.2.21).
- The public signed release is
  [Cannsheet Mobile 1.2.21](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.21).
  Its exact assets are `Cannsheet-Mobile-1.2.21.apk` (13,509,398 bytes) and
  `Cannsheet-Mobile-1.2.21.apk.sha256`.
- Independently downloaded public APK SHA-256:
  `e315a04300df297682ce19e3ff1e545a72824558daf720461cab59f3437545d0`
- Independent `aapt`/`apksigner` verification reported package
  `com.noamv.cannsheet.mobile`, version code `24`, version name `1.2.21`,
  and signing certificate SHA-256
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`.
- The previous public release had version code `23`; the new release is
  monotonic.
- The editable History milestone was delivered through backend
  [PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19), Android
  [PR #20](https://github.com/noamvb/cannsheet-mobile/pull/20), and production
  rollout hardening
  [PR #21](https://github.com/noamvb/cannsheet-mobile/pull/21),
  [PR #22](https://github.com/noamvb/cannsheet-mobile/pull/22), and
  [PR #23](https://github.com/noamvb/cannsheet-mobile/pull/23).

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
- Wireless ADB later reached the intended Samsung `SM-F966W` at
  `192.168.68.54:36595`. Production package readback reported
  `com.noamv.cannsheet.mobile`, version code `24`, version name `1.2.21`,
  `targetSdk 36`, and `lastUpdateTime 2026-08-11 01:10:00`. Obtainium showed
  Cannsheet Mobile from `https://github.com/noamvb/cannsheet-mobile-releases`
  as `v1.2.21 Installed / Latest`, with the expected certificate and its
  update button disabled. The device was already current when connected, so
  an Obtainium in-place update transition was not observed.
- A read-only Purchase-screen check confirmed the type-first form, type
  choices, and visible initially-off defaults switch. After selecting type
  `P`, the local product selector remained unavailable, so suggestion/default
  application and a real purchase were intentionally not exercised.

## History refresh feedback feature work

- The focused implementation is on branch `agent/history-refresh-feedback` and
  is not yet merged, released, or assigned a new version.
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
  instrumentation, and aggregate validation jobs for code commit `ae4812b`.
  The static job uploaded the debug APK artifact with ZIP digest
  `dcf7108717d33233bc571f00d396e7a3022a6878e9328a1272feb11ac617dc9b`.
- The exact local Android command was attempted with Gradle 9.3.1 and JDK
  17.0.20, but this Mac has no Android SDK and Gradle stopped with “SDK location
  not found”. CI is the authoritative build/test evidence for this branch.
- Wireless ADB reached the intended Samsung `SM-F966W`; its production package
  remained at version code `24` / version name `1.2.21` after the validated
  debug APK was rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because the
  installed Obtainium release signature differs. No uninstall, data clear, or
  production-package mutation was performed. Manual History validation and a
  recording therefore remain pending a release-signed build or explicit
  approval for a data-destructive reinstall.

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

Physical-device validation used an isolated test application ID on a Samsung
SM-F966W running Android 16 / API 36; the installed production and older
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

v1.2.21 evidence (current release):

- Feature PR #36's checks passed [run 31458365282](https://github.com/noamvb/cannsheet-mobile/actions/runs/31458365282); its merge commit `5f6d1392a77067616bde43265278b77daf447f8e` passed the full API 24/API 36 matrix in [run 31458941204](https://github.com/noamvb/cannsheet-mobile/actions/runs/31458941204).
- Release PR #37's checks passed [run 31459764234](https://github.com/noamvb/cannsheet-mobile/actions/runs/31459764234); its version-bump merge commit `b77bb4fdd9d4062d54d3bfb36837c7612b73eb6e` passed the full API 24/API 36 matrix in [run 31460010598](https://github.com/noamvb/cannsheet-mobile/actions/runs/31460010598).
- Signed publication workflow [run 31460436036](https://github.com/noamvb/cannsheet-mobile/actions/runs/31460436036) passed exact-main validation, signing, publication, and post-publication verification.
- The public release is [Cannsheet Mobile 1.2.21](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.21): `Cannsheet-Mobile-1.2.21.apk` (13,509,398 bytes) and its `.sha256`. Independently verified APK SHA-256: `e315a04300df297682ce19e3ff1e545a72824558daf720461cab59f3437545d0`.
- Independent public-artifact verification passed `apksigner`, confirmed the expected signing certificate, and confirmed package `com.noamv.cannsheet.mobile`, version code `24`, and version name `1.2.21`.
- Wireless ADB device readback: Samsung `SM-F966W`, Android 16 / API 36,
  `192.168.68.54:36595`. `dumpsys package` reported production version code
  `24`, version name `1.2.21`, and `lastUpdateTime 2026-08-11 01:10:00`.
- Obtainium detail readback reported `v1.2.21 Installed / Latest`, the public
  releases URL, the expected signing certificate, and a disabled update
  action. This verifies the current installed/public pairing, but not a live
  update transition because the device was already at v1.2.21 on connection.
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
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`

Local and device evidence:

- The exact local Android static command passed:
  `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
- The background-sync branch passed the isolated physical-device checks
  described above; a local screenshot captured its enabled, zero-pending,
  just-now-successful Settings state.
- No production-package feature installation, Obtainium update, or full manual
  product-total acceptance workflow was performed.

## Known limitations

- `app/src/main/res/xml/backup_rules.xml` and
  `app/src/main/res/xml/data_extraction_rules.xml` remain sample/template rules;
  the latter contains a backup-selection TODO.
- The Kotlin namespace remains `com.example` while the application ID is
  `com.noamv.cannsheet.mobile`; `README.md` records this as an intentional
  source-layout compatibility choice.
- The public APK is independently verified as update-compatible by package,
  higher version code, and signing certificate. The intended phone is now
  verified at the matching installed version and Obtainium current state, but
  the v1.2.21 in-place update transition itself was already complete before
  this session and was not observed live.
- The first real production correction lifecycle still requires a deliberate
  user/device check; no synthetic production correction was created for testing.

## Current priorities

1. Use the next genuine production purchase to validate default persistence and
   restart/autofill behavior without fabricating data.
2. If a live Obtainium transition must be witnessed explicitly, observe the
   next higher release update on the same device; v1.2.21 was already current
   when this session connected.
3. Continue the existing v1.2.20 device checks for analytics prefetch and
   correction-safe usage totals.

## Unresolved questions

- Obtainium detects v1.2.21 and reports it installed/latest on the intended
  phone; the in-place transition was not observed live in this session.
- Does a genuine product suggestion on the installed v1.2.21 app apply and
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
