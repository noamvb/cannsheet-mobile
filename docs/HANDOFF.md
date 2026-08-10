# Latest handoff

Last updated: 2026-08-09

## Outcome

Product usage totals are merged, deployed, signed, and publicly available in
Cannsheet Mobile v1.2.18. The Log Consumption screen shows correction-aware
synced totals from the existing `Purchases.Uses` projection and separately
shows durable locally pending consumption. Totals appear on the selected and
Recent Products cards, not in the product picker.

Public release:
[Cannsheet Mobile 1.2.18](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.18)

Direct APK:
[Cannsheet-Mobile-1.2.18.apk](https://github.com/noamvb/cannsheet-mobile-releases/releases/download/v1.2.18/Cannsheet-Mobile-1.2.18.apk)

## Source and release state

- Product usage totals were delivered through
  [PR #27](https://github.com/noamvb/cannsheet-mobile/pull/27).
- Version-only release
  [PR #28](https://github.com/noamvb/cannsheet-mobile/pull/28) changed
  `versionName` to `1.2.18` and `versionCode` to `21`.
- Exact release commit:
  `e93883b5a3cb7e98160a59489677fd87e0bb217a`.
- Annotated tag `v1.2.18` targets that exact commit.
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

## Independent public APK verification

- APK size: 13,241,161 bytes.
- APK SHA-256:
  `70cdc24f3a5dea63701b5fbbc2b2adadaaa18d46a665a76af13cb3f8350d5792`.
- The downloaded checksum file exactly matched the downloaded APK.
- Android `aapt` reported:
  - application ID `com.noamv.cannsheet.mobile`;
  - version code `21`;
  - version name `1.2.18`.
- Android `apksigner` reported a valid APK Signature Scheme v2 signature with
  one signer.
- Signing certificate SHA-256:
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`.

## Not yet verified

- No physical-phone installation or in-place Obtainium update was observed.
- No screenshot or recording was captured on a physical device.
- The complete manual offline, countdown-cancellation, failed-sync persistence,
  borrowed-product remapping, large-font, and live correction scenarios were
  not performed on the intended phone.
- CI, APK metadata, checksum, and signature evidence prove release integrity;
  they do not prove physical-device UX.

## Recommended next action

Install or update to v1.2.18 through Obtainium or the direct APK link. Then
verify a selected product and Recent Products card with synced and pending
totals, including one offline log followed by successful synchronization. If
possible, capture the phone screenshots and add the results to this handoff.

## Safety review

- No credential, signing key, keystore, private spreadsheet identifier,
  personal absolute path, runtime database, build output, or downloaded APK is
  committed.
- The documentation follow-up occurs after the immutable release tag and does
  not change what v1.2.18 identifies.
