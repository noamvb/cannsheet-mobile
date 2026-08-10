# Project state

Last updated: 2026-08-09

## Repository state

- Canonical branch: `main`
- Released source commit and annotated tag `v1.2.18`:
  `e93883b5a3cb7e98160a59489677fd87e0bb217a`
- Current release metadata in `app/build.gradle.kts`: version name `1.2.18`,
  version code `21`
- Product usage totals [PR #27](https://github.com/noamvb/cannsheet-mobile/pull/27)
  and version-only release [PR #28](https://github.com/noamvb/cannsheet-mobile/pull/28)
  were squash-merged after their required validation passed.
- The public signed release is
  [Cannsheet Mobile 1.2.18](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.18).
  Its release assets are `Cannsheet-Mobile-1.2.18.apk` and
  `Cannsheet-Mobile-1.2.18.apk.sha256` (in addition to GitHub's automatic source archives).
- Public APK SHA-256:
  `70cdc24f3a5dea63701b5fbbc2b2adadaaa18d46a665a76af13cb3f8350d5792`
- The editable History milestone was delivered through backend
  [PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19), Android
  [PR #20](https://github.com/noamvb/cannsheet-mobile/pull/20), and production
  rollout hardening
  [PR #21](https://github.com/noamvb/cannsheet-mobile/pull/21),
  [PR #22](https://github.com/noamvb/cannsheet-mobile/pull/22), and
  [PR #23](https://github.com/noamvb/cannsheet-mobile/pull/23).

## Background synchronization feature work

Background synchronization is in progress on feature branch
`agent/background-sync` in
[PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30), based on main
commit `9b71cab`. It is not merged, released, deployed, or included in
Cannsheet Mobile v1.2.18.

The approved feature uses a process-wide `CannsheetGraph` with a shared mutex
and routes foreground and WorkManager queue attempts through one `SyncEngine`.
Connected immediate work is a serial unique `APPEND_OR_REPLACE` chain; periodic
retry is one six-hour unique `UPDATE` request. A DataStore toggle is a local
kill switch. The implementation does not change the Apps Script backend or the
Room schema/version.

Local validation passed 85 JVM tests, Android-test Kotlin compilation, lint
with no errors, debug APK assembly, and the unchanged backend analytics test.
The compiled worker instrumentation covers a real in-memory Room queue and fake
API, but it was not executed because no device or emulator was connected.
Physical-device behavior, job-scheduler inspection, screenshots, and live Sheet
exactly-once readback remain unverified. Existing v1.2.18 release evidence below
remains the current released-state evidence.

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

- Annotated tag `v1.2.18` resolves to the exact release commit above.
- Signed release workflow run
  [31343252239](https://github.com/noamvb/cannsheet-mobile/actions/runs/31343252239)
  passed tag/main/version checks, tests, lint, signed build, signature
  verification, checksum generation, publication, and post-publication
  verification.
- The public release contains one APK (13,241,161 bytes) and its `.sha256`
  release asset, plus GitHub's automatic source archives.

Independent public-artifact evidence:

- The downloaded checksum file matched the APK:
  `70cdc24f3a5dea63701b5fbbc2b2adadaaa18d46a665a76af13cb3f8350d5792`.
- Android `aapt` reported application ID `com.noamv.cannsheet.mobile`,
  version code `21`, and version name `1.2.18`.
- Android `apksigner` verified APK Signature Scheme v2 with one signer.
- Signing certificate SHA-256:
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`

Local and device evidence:

- The exact local Android static command passed:
  `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
- No physical-device installation, Obtainium update, screenshot, recording, or
  full manual product-total acceptance workflow was performed.

## Known limitations

- `app/src/main/res/xml/backup_rules.xml` and
  `app/src/main/res/xml/data_extraction_rules.xml` remain sample/template rules;
  the latter contains a backup-selection TODO.
- The Kotlin namespace remains `com.example` while the application ID is
  `com.noamv.cannsheet.mobile`; `README.md` records this as an intentional
  source-layout compatibility choice.
- The public APK is independently verified as update-compatible by package,
  higher version code, and signing certificate, but an actual in-place phone
  installation has not been observed in this release session.
- The first real production correction lifecycle still requires a deliberate
  user/device check; no synthetic production correction was created for testing.

## Current priorities

1. Complete focused validation and review for the unmerged background-sync
   feature branch; do not treat it as deployed or released.
2. Verify background synchronization on a physical device, including the
   DataStore kill switch and a safe Sheet readback, before making any live
   execution claim.
3. Install or update to v1.2.18 on the intended phone, preferably through
   Obtainium.
4. Verify selected and recent product cards for zero, integer, fractional,
   offline pending, successful sync, failed sync, and borrowed-product cases.
5. Perform one controlled correction and confirm that the synced total changes
   only after backend acknowledgement, without creating pending consumption.
6. Record device screenshots and results in `docs/HANDOFF.md` if available.

## Unresolved questions

- Does Obtainium detect and install v1.2.18 in place on the intended phone?
- Do the confirmed-versus-pending totals remain clear during real offline,
  retry, borrowed-product, and correction workflows on the intended phone?

These require device evidence and should not be answered from repository or
workflow evidence alone.

## Relevant paths

- `app/src/main/java/com/example/ui`
- `app/src/main/java/com/example/data`
- `app/src/test`
- `app/src/androidTest`
- `tests/backend_corrections_test.js`
- `backend_additions.gs`
- `sandbox_provisioning.gs`
- `app/build.gradle.kts`
- `.github/workflows`
- `docs/HANDOFF.md`
