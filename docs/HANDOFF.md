# Latest handoff

Last updated: 2026-08-11

## Current outcome

The per-product-type quick-log quantity preset feature is implemented, merged,
released, and installed as Cannsheet Mobile v1.2.23. The source release is
commit `2e251a1d71aedbfe44e265c907a58d77ccd4d720`; the public signed APK is
published in the separate releases repository and was installed in place on
the intended Android production device.

The implementation preserves the existing global quick-log presets as the
fallback and adds version-1 per-type overrides in the same
`consumption_preferences` DataStore. No Room schema, offline queue, Apps Script
contract, production endpoint, package ID, or signing configuration was
changed.

For device validation, a temporary debug-signed `devicecheck` variant was
assembled and installed alongside production as
`the isolated devicecheck application`. The production package was not
overwritten, uninstalled, downgraded, or cleared.

## Implementation and validation

- `ConsumptionPreferencesRepository` now decodes and writes a defensive,
  versioned override payload atomically with the existing preferences snapshot.
  Product-type keys are normalized with `Locale.ROOT`; invalid records are
  ignored and invalid preset lists fall back to the global list.
- `ProductTypes` defines the canonical code/label set and merges it with
  normalized catalog types. `CannsheetViewModel` exposes the effective
  type-aware presets and uses them for consumption defaults.
- Settings keeps the existing global editor and adds a reusable editor for
  per-type custom quantities, reset behavior, status, and a custom-type
  summary. Purchase type choices now use the same canonical codes.
- New JVM coverage covers payload validation, normalization, duplicate
  handling, ordering, atomic writes, failure behavior, persistence, and
  effective-preset resolution. New Compose coverage covers selecting a type,
  reseeding on type changes, saving, and reset behavior.
- Feature PR [#44](https://github.com/noamvb/cannsheet-mobile/pull/44) merged as
  `b9302edcc309e7ada5a30e528a091801df8fb568`; its PR checks were run in
  [31541378270](https://github.com/noamvb/cannsheet-mobile/actions/runs/31541378270)
  and its exact merged-main validation passed in
  [31541684164](https://github.com/noamvb/cannsheet-mobile/actions/runs/31541684164).
- Version PR [#45](https://github.com/noamvb/cannsheet-mobile/pull/45) merged as
  `2e251a1d71aedbfe44e265c907a58d77ccd4d720`; its PR checks passed in
  [31542822264](https://github.com/noamvb/cannsheet-mobile/actions/runs/31542822264)
  and exact versioned-main validation passed in
  [31543271407](https://github.com/noamvb/cannsheet-mobile/actions/runs/31543271407).
- Signed publication [run 31544318700](https://github.com/noamvb/cannsheet-mobile/actions/runs/31544318700)
  passed its exact-main, signed-build, checksum, public-publication, and
  post-publication checks. The downloaded APK is 13,525,786 bytes and has
  SHA-256 `f9f319ba892f4e0f09c9bebc7a581fc1326bf162b1c21c06db653d051c8aab98`.
- The public APK reports package `com.noamv.cannsheet.mobile`, version code
  `26`, and version name `1.2.23`; local `apksigner` verification passed using
  APK Signature Scheme v2.
- The production package readback before installation was version code `25`,
  version name `1.2.22`, signing identity `the release signing identity`, and data directory
  `the production app data directory`. `adb install -r` completed with
  `Success`; the after-install readback was version code `26`, version name
  `1.2.23`, the same signing identity and data directory, and an unchanged
  install-time metadata. Launching `com.example.MainActivity` succeeded before
  the app was force-stopped for handoff.
- The final local command passed with JDK 17.0.20 and Gradle 9.3.1:
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
  Result: `BUILD SUCCESSFUL`; 138 JVM tests completed, Android-test Kotlin
  compilation completed, lint completed, and the debug APK assembled.
- The temporary device-check APK was built with
  `./gradlew --no-daemon assembleDevicecheck`, installed successfully, and
  reported version name `1.2.22-devicecheck` / version code `25`. The artifact
  was `app/build/outputs/apk/devicecheck/app-devicecheck.apk` with SHA-256
  `6a748680fc91c2830d222beeb7b00050f9145a629ccc8f464e5a3c0d9ef6734a`.
- `git diff --check` passed. Existing CRLF files caused only the repository's
  line-ending conversion warnings; no whitespace errors were reported.
- Backend Node/Python suites were not run because backend sources were not
  changed. Connected instrumentation was not run because the temporary
  `devicecheck` build type has no dedicated connected-test task; the new
  Android test source compiled successfully. No CI run was started.

## Device state

- Device: wireless ADB target `the wireless ADB target`,
  Android `the intended Android production device`, Android API 36.
- The device returned to wireless ADB at `the device wireless endpoint`. The production
  v1.2.23 APK was installed with `adb install -r` and the package metadata,
  signing identity, data directory, and successful launch were read back.
- Installed validation package: `the isolated devicecheck application`,
  version code `25`, version name `1.2.22-devicecheck`, installed with
  `adb install -r` from the isolated APK.
- The production package `com.noamv.cannsheet.mobile` remained installed and
  was not uninstalled, cleared, downgraded, or modified by this validation.
- The isolated app's temporary Shatter override was reset before finishing;
  its Settings state showed `Using the default quantities` and
  `No product types have custom quantities.` Pending actions remained zero.

## Manual validation boundary

The phone was occupied from the explicit start notification through the final
ADB check. The completed bounded walkthrough was:

- Settings displayed the existing global presets `0.5 / 1 / 2` and the new
  product-type editor.
- The existing global editor was changed to `0.5 / 1.25 / 2`, saved, confirmed
  after force-stop/relaunch, and restored to `0.5 / 1 / 2`.
- The picker exposed exactly `E — Edible`, `F — Flower`, `J — Joint`,
  `K — Keef`, `P — Pen`, and `S — Shatter`.
- Shatter was edited to `0.1 / 0.25 / 0.5`, saved, displayed a custom status
  and summary, and retained that override after force-stop/relaunch.
- The Shatter Log form displayed `0.1 / 0.25 / 0.5`; after reset, the same
  Shatter form displayed the global `0.5 / 1 / 2` values. The Pen form also
  displayed the global values.
- Edible with no override displayed `Using the default quantities` and seeded
  its fields from the global list.
- Selecting products was local UI state only. No Log Consumption, Mark
  product as finished, Purchase, or Sync Now action was pressed. No real
  production data was changed.

This is a manual UI readback, not a physical-device instrumentation result.
The new Compose instrumentation test was compiled but not connected-run.
The isolated debug-signed package was used for the UI walkthrough because a
debug APK cannot safely overlay the production-signed package without an
uninstall; the production package was deliberately left untouched during that
walkthrough.

The signed v1.2.23 APK was then physically installed on the production package
after a separate notification that phone use was starting. The before/after
readbacks prove an in-place update while preserving the production signing
identity and data directory. No real log, purchase, finish, or sync action was
submitted during the launch check.

## Data-safety notes

- The override data is stored in the isolated app's Preferences DataStore for
  this device check. Existing Room data, pending actions, and production app
  data were not cleared or migrated.
- The implementation does not alter synchronization acknowledgement rules,
  immutable IDs, retries, backend writes, or release metadata.
- The production install used only `adb install -r`; no uninstall, data clear,
  downgrade, or destructive migration was performed. Existing Room data and
  pending actions therefore remained in place.
- The temporary `devicecheck` build configuration is removed from the final
  source diff after validation. The installed APK remains on the device as the
  isolated validation app from the earlier bounded walkthrough; it is not the
  v1.2.23 production release.

## Relevant files

- `app/src/main/java/com/example/data/ConsumptionPreferencesRepository.kt`
- `app/src/main/java/com/example/ui/ProductTypes.kt`
- `app/src/main/java/com/example/ui/CannsheetViewModel.kt`
- `app/src/main/java/com/example/ui/ConsumptionScreen.kt`
- `app/src/main/java/com/example/ui/PurchaseScreen.kt`
- `app/src/main/java/com/example/ui/SettingsScreen.kt`
- `app/src/test/java/com/example/data/QuantityPresetOverridesTest.kt`
- `app/src/test/java/com/example/ui/ProductTypesTest.kt`
- `app/src/androidTest/java/com/example/ui/ProductTypeQuantityEditorTest.kt`
- `docs/ARCHITECTURE.md`
- `docs/DECISIONS.md`
- `docs/PROJECT_STATE.md`
