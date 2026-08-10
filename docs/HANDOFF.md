# Latest handoff

Last updated: 2026-08-10

## Outcome

Product usage totals and background synchronization are merged, signed, and
publicly available in Cannsheet Mobile v1.2.19. The Log Consumption screen
shows correction-aware
synced totals from the existing `Purchases.Uses` projection and separately
shows durable locally pending consumption. Totals appear on the selected and
Recent Products cards, not in the product picker.

Background synchronization was delivered through
[PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30). The implementation uses a
process-wide graph and shared mutex so `SyncEngine` serializes foreground and
WorkManager queue attempts. It schedules connected immediate work with
`APPEND_OR_REPLACE`, periodic six-hour work with `UPDATE`, leaves default
WorkManager initialization in place, does not use expedited work, and provides
a DataStore kill switch. It does not change the backend or Room schema/version.

Public release:
[Cannsheet Mobile 1.2.19](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.19)

Direct APK:
[Cannsheet-Mobile-1.2.19.apk](https://github.com/noamvb/cannsheet-mobile-releases/releases/download/v1.2.19/Cannsheet-Mobile-1.2.19.apk)

## Source and release state

- Background synchronization was delivered through
  [PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30).
- Version-only release
  [PR #31](https://github.com/noamvb/cannsheet-mobile/pull/31) changed
  `versionName` to `1.2.19` and `versionCode` to `22`.
- Exact release commit:
  `009d38cc00642c323015958160b768661c13036d`.
- Annotated tag `v1.2.19` targets that exact commit.
- No endpoint, API version, signing identity, spreadsheet column, or release
  workflow was changed for this feature.

## Backend rollout

- Sandbox deployment version 14 was updated and validated first.
- Before production promotion, a complete spreadsheet backup named
  `CannsheetG Production Backup 2026-08-09 17-15-10 EDT - before product usage totals rollout`
  was created. Its sheet layout and bounded `Purchases` sample matched the
  source spreadsheet.
- Production Apps Script deployment version 13 was updated in place. Version 12
  remains available as the backend rollback point.
- All 352 production catalog products returned finite, nonnegative
  `totalUses` values.
- Every returned total matched `Purchases.Uses` and correction-resolved
  Insights `allTime.quantity`.
- Bounded `Purchases` readbacks were unchanged after catalog and Insights GETs;
  no spreadsheet write was used to validate the response.

## Validation evidence

- Exact feature-branch full matrix
  [31335057894](https://github.com/noamvb/cannsheet-mobile/actions/runs/31335057894)
  passed backend, Android static, API 24, API 36, and aggregate validation.
- Exact merged-feature main run
  [31335500140](https://github.com/noamvb/cannsheet-mobile/actions/runs/31335500140)
  passed the same complete matrix.
- Release PR run
  [31337182904](https://github.com/noamvb/cannsheet-mobile/actions/runs/31337182904)
  passed after retrying an external Android SDK archive HTTP 404. No source
  change was needed for the retry.
- Exact release-commit main run
  [31337505912](https://github.com/noamvb/cannsheet-mobile/actions/runs/31337505912)
  passed backend, Android static, API 24, API 36, and aggregate validation.
- Signed publication run
  [31343252239](https://github.com/noamvb/cannsheet-mobile/actions/runs/31343252239)
  passed exact-main/tag/version gates, tests, lint, signed build, signature
  verification, checksum generation, public upload, and post-publication
  verification.
- The local command
  `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
  completed successfully on the release source.
- On `agent/background-sync`, the same local command completed successfully:
  85 JVM tests passed with zero failures, Android-test Kotlin compiled, lint
  reported no errors, and a debug APK was assembled. Focused tests cover every
  `SyncEngine` outcome, status-message parity, request-ID reuse, shared-mutex
  serialization, scheduler constraints, bounded worker passes, and Settings
  last-run wording.
- The worker instrumentation source compiles and uses an in-memory Room queue
  with a fake API to check empty, transport, acknowledgement, and environment
  mismatch paths.
- Exact feature-branch PR run
  [31347066238](https://github.com/noamvb/cannsheet-mobile/actions/runs/31347066238)
  passed repository safety, backend validation, Android static validation,
  the API 24 emulator instrumentation suite, and aggregate validation.
- Exact pre-device-evidence branch run
  [31347378448](https://github.com/noamvb/cannsheet-mobile/actions/runs/31347378448)
  passed the same repository safety, backend, Android static, API 24, and
  aggregate checks at commit `d523c28`.
- A Samsung SM-F966W running Android 16 / API 36 completed the isolated
  physical-device validation. The temporary test application ID was
  `com.noamv.cannsheet.mobile.backgroundsynctest`; the installed v1.2.18
  production app and older sandbox app were not overwritten or cleared.
- The physical phone ran 39 sandbox instrumentation tests with zero failures,
  including the process-wide graph and injected in-memory Room worker tests.
- With airplane mode enabled, one 1.5-use action remained durable as
  `Pending Actions: 1`. After the UI process was killed without force-stop and
  connectivity returned, Android started the process within five seconds,
  `SyncWorker` succeeded, the queue became zero, and Settings showed
  `Last run: just now — Sync successful`.
- JobScheduler showed both the six-hour periodic job and an immediate
  connected-only job with exponential 30-second backoff. The immediate job
  was held specifically by the missing connectivity constraint while the
  phone was offline.
- Bounded sandbox Sheet readback showed exactly one new `ANDROID_V2`
  consumption and one `ACCEPTED` ledger entry for the same request UUID. A
  delayed second read showed no duplicate.
- With background sync disabled, a separate 0.5-use action stayed pending
  through process death and reconnect, and both bounded Sheet row counts stayed
  unchanged. Re-enabling the switch drained it once and restored the enabled,
  successful state.
- A three-action offline batch reached `Pending Actions: 3`, then synchronized
  after process death as exactly three consumption rows sharing one request
  UUID. The single corresponding ledger row recorded consumption count 3 and
  `ACCEPTED`; a delayed read left both row counts unchanged.
- The final physical-device screen showed background sync enabled,
  `Pending Actions: 0`, and a just-now successful result. A screenshot was
  reviewed locally but was not committed.
- `node tests/backend_analytics_test.js` passed without changing backend source.
- The merged debug manifest retains default WorkManager initialization and the
  `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, and `FOREGROUND_SERVICE` permissions.
- Exact merged feature main run
  [31350500266](https://github.com/noamvb/cannsheet-mobile/actions/runs/31350500266)
  passed safety, backend, Android static, API 24, API 36, and aggregate checks.
- Version-only PR run
  [31351238565](https://github.com/noamvb/cannsheet-mobile/actions/runs/31351238565)
  passed its required checks. Exact version-bump main run
  [31351485515](https://github.com/noamvb/cannsheet-mobile/actions/runs/31351485515)
  passed the full API 24/API 36 matrix and aggregate check.
- Signed publication run
  [31351814290](https://github.com/noamvb/cannsheet-mobile/actions/runs/31351814290)
  passed exact-main/tag/version validation, signed build, signature and checksum
  verification, public upload, and post-publication verification.

## Independent public APK verification

- APK size: 13,493,018 bytes.
- APK SHA-256:
  `86773a13c4633034fda8e67b033b2e2ec924333442ad5401ef2ae7d31bd2a747`.
- The downloaded checksum file exactly matched the downloaded APK.
- Android `aapt` reported:
  - application ID `com.noamv.cannsheet.mobile`;
  - version code `22`;
  - version name `1.2.19`.
- Android `apksigner` reported a valid APK Signature Scheme v2 signature with
  one signer.
- Signing certificate SHA-256:
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`.

## Not yet verified

- The isolated background-sync package was installed directly with ADB. No
  in-place Obtainium update to v1.2.19 or installation of the production
  package was observed.
- The complete manual offline, countdown-cancellation, failed-sync persistence,
  borrowed-product remapping, large-font, and live correction scenarios were
  not performed on the intended phone.
- CI, APK metadata, checksum, and signature evidence prove release integrity;
  they do not prove physical-device UX.

## Recommended next action

Install or update the production package to v1.2.19 through Obtainium or the
direct APK link, then complete the remaining product-total and correction
scenarios when desired.

## Safety review

- No credential, signing key, keystore, private spreadsheet identifier,
  personal absolute path, runtime database, build output, or downloaded APK is
  committed.
- The documentation follow-up occurs after the immutable release tag and does
  not change what v1.2.19 identifies.
