# Latest handoff

Last updated: 2026-08-11

## Current outcome

One-tap pen logging with duration chips is implemented, merged, released, and
installed as Cannsheet Mobile v1.2.24. The feature source was merged as
`76dc95a60a45b699471e6b9ca93c5132307b6081`; the versioned release source is
`7a112d315d5b6666bca8f582bc50f6db430345ab`, tagged `v1.2.24`. The public
signed APK is published in the separate releases repository and was installed
in place on the intended production device.

The feature keeps uses as the only Room, offline-queue, wire, Apps Script, and
spreadsheet quantity unit. It adds seconds-per-use settings and a loaded-pen
ID to the existing `consumption_preferences` DataStore, while preserving the
existing global quantity presets as the fallback. No Room schema, queue
payload, Apps Script contract, production endpoint, package ID, or signing
configuration was changed.

Bounded device validation used a separate debug-signed package
`com.noamv.cannsheet.mobile.devicecheck124` with the deliberately invalid
endpoint `https://devicecheck.invalid/exec`. The production package was not
used for test actions, and its existing data directory was preserved during
the in-place v1.2.24 update.

## Implementation and validation

- `ConsumptionPreferencesRepository` stores the version-1 seconds-per-use map
  and loaded-pen product ID atomically with the existing preferences snapshot.
  Missing duration data seeds `P` at `10.0`; an explicit payload without `P`
  preserves the user's choice to turn the rate off. Invalid duration records
  are ignored defensively.
- The Log screen resolves an explicit selectable pen first and otherwise uses
  the most recently logged selectable pen. Duration chips display seconds but
  convert back to uses before entering the existing countdown, Room queue,
  synchronization, and cancellation path. Successful local logs select the
  logged pen, and finishing it clears the loaded ID.
- The pending countdown callbacks now use one take-once `PendingSubmission`
  holder. Replacing an action flushes the displaced callback before starting
  the new countdown; cancellation takes it without invoking it. The holder is
  covered by JVM regression tests.
- New JVM and Compose coverage covers duration conversion, preference payload
  validation, loaded-pen selection, quick-log submission, countdown flush and
  cancellation, Settings save/clear behavior, persistence, and UI rendering.
- PR [#48](https://github.com/noamvb/cannsheet-mobile/pull/48) merged as
  `19f00174268bc5b93065c61a8407aeeaebf388b9` after six-job run
  [31550011273](https://github.com/noamvb/cannsheet-mobile/actions/runs/31550011273).
  Feature PR [#49](https://github.com/noamvb/cannsheet-mobile/pull/49) merged as
  `76dc95a60a45b699471e6b9ca93c5132307b6081`; its five-job gate
  [31551639733](https://github.com/noamvb/cannsheet-mobile/actions/runs/31551639733)
  and exact merged-main matrix
  [31552090083](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552090083)
  passed. Version PR [#50](https://github.com/noamvb/cannsheet-mobile/pull/50)
  merged as `7a112d315d5b6666bca8f582bc50f6db430345ab` after its PR gate
  [31552545541](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552545541)
  and exact versioned-main matrix
  [31552869317](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552869317)
  passed.
- Signed publication [run 31553283041](https://github.com/noamvb/cannsheet-mobile/actions/runs/31553283041)
  passed exact-main proof, signed build, checksum, public publication, and
  post-publication checks. The public APK reports package
  `com.noamv.cannsheet.mobile`, version code `27`, and version name `1.2.24`;
  local v2 signature verification passed. Published SHA-256:
  `b899a98d4c48cc20663a05270e56535af90f3584e91bcf0e53cc3e6ea244d6d0`.
- The production package readback before installation was version code `26`,
  version name `1.2.23`, and the existing production data directory. After the
  in-place update it reported version code `27`, version name `1.2.24`, the
  same package/data directory, unchanged install-time metadata, and a
  successful launch of `com.example.MainActivity`.
- The final local command passed with JDK 17.0.20 and Gradle 9.3.1:
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
  Result: `BUILD SUCCESSFUL`; 161 JVM tests completed, Android-test Kotlin
  compilation completed, lint completed, and the debug APK assembled. The
  bundled Node runtime also passed `tests/backend_analytics_test.js`.
- `git diff --check` passed. Existing CRLF files caused only the repository's
  line-ending conversion warnings; no whitespace errors were reported.
- The required backend suites and emulator instrumentation passed in the
  GitHub Actions gates. The bounded manual device walkthrough was performed
  with a separate package and is not a connected instrumentation run of the
  signed production APK.

## Device state

- Device: Samsung SM-F966W, Android API 36, reached over wireless ADB for the
  bounded validation window.
- The public v1.2.24 APK was installed in place with `adb install -r`; package
  metadata, data-directory preservation, and successful launch were read back
  locally. The production app was not used for test taps or submissions.
- The isolated package `com.noamv.cannsheet.mobile.devicecheck124` was used for
  the UI walkthrough and remains separate from the production package. It was
  configured with the deliberately invalid `devicecheck.invalid` endpoint.
- After the walkthrough, temporary remote screenshots/XML were removed and
  the wireless ADB connection was disconnected. The phone is available for
  normal personal use.

## Manual validation boundary

The phone was occupied from the explicit start notification through the final
ADB check. The completed bounded walkthrough used only the isolated package:

- The Log screen loaded `BH Raspberry Riptide` and showed the one-tap pen card
  with `5s`, `15s`, and `20s` chips; the `15s` chip represented `1.5` uses.
- Tapping a chip showed the existing cancellable countdown with `CANCEL` and
  `SUBMIT NOW`. Two rapid chips preserved `Pending: +2 uses`; cancelling a
  third chip did not add another pending action.
- `Swap cart` opened the selectable pen picker and showed the available
  `BH Raspberry Riptide` pen. No swap or production submission was made.
- Settings saved the seeded `10` seconds-per-use rate and showed the preview
  `5s · 15s · 20s`. Clearing the setting showed `Using the default quantities`
  and that off state persisted after force-stop/relaunch. Returning to Log
  showed the plain `0.5 / 1.5 / 2` use chips.
- No production Log Consumption, Mark product as finished, Purchase, or Sync
  Now action was pressed. The isolated app used an invalid endpoint, so its
  pending test actions could not reach the production Apps Script backend.

This is a manual UI readback, not a physical-device instrumentation result.
The new Compose instrumentation tests compiled and the GitHub emulator jobs
passed, but the signed production APK was not action-tested on the phone.
The screenshots are attached to the PR #49 conversation.

The signed v1.2.24 APK was separately installed on the production package.
Before/after package readbacks prove an in-place update from version `1.2.23`
to `1.2.24` while preserving the existing data directory. No production data
action was submitted during the install or launch check.

## Data-safety notes

- The isolated package's Settings changes and pending `+2 uses` test state are
  contained in its separate application data. Existing Room data, pending
  actions, and production app data were not cleared or migrated.
- The implementation does not alter synchronization acknowledgement rules,
  immutable IDs, retries, backend writes, or release metadata.
- The production install used only `adb install -r`; no uninstall, data clear,
  downgrade, or destructive migration was performed. Existing Room data and
  pending actions therefore remained in place.
- The temporary `devicecheck` build configuration is removed from the final
  source diff after validation. The installed isolated APK remains on the
  device as a separate validation app; it is not the v1.2.24 production
  release.

## Relevant files

- `app/src/main/java/com/example/data/ConsumptionPreferencesRepository.kt`
- `app/src/main/java/com/example/ui/QuantityUnits.kt`
- `app/src/main/java/com/example/ui/PenQuickLog.kt`
- `app/src/main/java/com/example/ui/CannsheetViewModel.kt`
- `app/src/main/java/com/example/ui/ConsumptionScreen.kt`
- `app/src/main/java/com/example/ui/SettingsScreen.kt`
- `app/src/test/java/com/example/data/SecondsPerUseOverridesTest.kt`
- `app/src/test/java/com/example/ui/QuantityUnitsTest.kt`
- `app/src/test/java/com/example/ui/PenQuickLogTest.kt`
- `app/src/androidTest/java/com/example/ui/PenQuickLogCardTest.kt`
- `app/src/androidTest/java/com/example/ui/ProductTypeQuantityEditorTest.kt`
- `docs/ARCHITECTURE.md`
- `docs/DECISIONS.md`
- `docs/PROJECT_STATE.md`
