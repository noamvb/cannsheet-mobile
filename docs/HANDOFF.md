# Latest handoff

Last updated: 2026-08-18

## Current outcome

Cannsheet Mobile v1.3.4 is implemented, validated, merged, tagged, and published. The
release fixes the home-screen pen widget not scaling when it is resized larger: the widget
now spends surplus launcher space on its own controls and grows its text to match, instead
of showing a block of empty background below the `+`/`−` row.

- Feature PR [#90](https://github.com/noamvb/cannsheet-mobile/pull/90) was squash-merged as
  commit `3ef1f0ffa11111b3e3ce2765514484671a42fb54`.
- Version bump PR [#91](https://github.com/noamvb/cannsheet-mobile/pull/91) was squash-merged
  as commit `f33a8ef5f441261c887355972cc0736e72532b05`.
- Release metadata in `app/build.gradle.kts`: `versionName = "1.3.4"`, `versionCode = 35`.

## v1.3.4 widget changes

- **Dead bottom spacer removed (`3ef1f0f`, PR #90)**: ADR-015 point 4 ended
  `widget_pen_consumption.xml` and `widget_pen_consumption_compact.xml` with a zero-content
  `TextView` carrying `layout_weight="1"`. Because every control had a fixed `dp` height, all
  surplus launcher height went to that spacer — a widget at roughly `285x295dp` spent about
  half its area on empty background.
- **Rows now absorb surplus height (`3ef1f0f`, PR #90)**: the counter row and step row carry
  `layout_weight` `3` and `2` over their existing `dp` heights, turning those heights into
  floors rather than fixed sizes. The counter panel and submit button fill the counter row,
  which is weighted `8:1` horizontally with a `40dp` submit floor so the submit control still
  measures at least `48dp` wide at the `140dp` minimum resize width.
- **Text scales with the widget (`3ef1f0f`, PR #90)**: `PenWidgetSizing` maps the
  launcher-reported `OPTION_APPWIDGET_MIN_WIDTH`/`MIN_HEIGHT` onto a `PenWidgetLayoutSpec`
  holding the compact decision and eight `sp` sizes, interpolated between a base set at
  `140x160dp` and a largest set at `280x320dp`, clamped at both ends and rounded to half a
  point. Growth uses `min(widthFraction, heightFraction)`. `PenWidgetRenderer` applies them
  through `setTextViewTextSize` and now takes that spec instead of a `compact: Boolean`.
- **Picker preview matched (`3ef1f0f`, PR #90)**: `widget_pen_consumption_preview.xml` mirrors
  the same weights so the API 31+ preview fills the way a placed widget does.
- **Version metadata (`f33a8ef`, PR #91)**: bumped `versionCode = 35`, `versionName = "1.3.4"`.

Recorded as ADR-023, which explicitly supersedes ADR-015 point 4.

## Validation and provenance

- PR #90 CI gate [run 32094909789](https://github.com/noamvb/cannsheet-mobile/actions/runs/32094909789)
  passed all jobs. PR #91 CI gate
  [run 32095902249](https://github.com/noamvb/cannsheet-mobile/actions/runs/32095902249) passed all jobs.
- **The tagged SHA's main run is [run 32096495724](https://github.com/noamvb/cannsheet-mobile/actions/runs/32096495724)**,
  `event = push`, `headSha = f33a8ef5f441261c887355972cc0736e72532b05`, conclusion `success`,
  with all six required jobs individually green: Classify changes and scan repository, Backend
  validation, Android static validation, Emulator API 24, Emulator API 36, and Cannsheet Android
  PR validation.
- The annotated tag `v1.3.4` (`d597d2657587a56f733d22247a732e234dbb39ed`) points at exactly
  that validated commit `f33a8ef5f441261c887355972cc0736e72532b05`, which was the tip of
  `origin/main` when the tag was pushed.
- **Two API 36 flakes preceded that green run and are recorded here so they are not mistaken
  for a regression.** Run 32095780753 (`3ef1f0f`) failed
  `com.example.ui.PurchaseContentTest.changingTypeClearsFieldsButReselectingTypePreservesThem`
  with `Failed to inject touch input.`, and run 32096495724's first attempt failed
  `com.example.ui.PurchaseContentTest.requiresTypeBeforeShowingNormalizedDeduplicatedSuggestions`
  with `Failed: assertExists.`. Both job logs show the runner emulator degraded before the tests
  began (eight consecutive `adb ... failed with exit code 1` retries and
  `[EmulatorConsole]: Failed to start Emulator console for 5554`). Different test methods and
  different assertions failed each time, and no widget test failed in either run. The failed
  jobs of run 32096495724 were re-run and passed.
- **Local execution on this machine confirmed the flake diagnosis rather than assuming it.**
  At the tagged commit, `PurchaseContentTest` passed 10/10 on a local
  `google_apis/arm64-v8a` API 36 emulator, and the complete instrumented suite passed
  **133/133 with 0 failures** on the same emulator.
- Local gate at the feature commit:
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
  BUILD SUCCESSFUL; **385 JVM unit tests, 0 failures, 0 errors, 0 skipped**; Android lint
  **0 errors, 61 warnings**.
- All 8 Node backend suites passed, and `python3 -m unittest tests/test_backend_sync_benchmark.py`
  ran 13 tests OK.
- New coverage: `PenWidgetSizingTest` (12 JVM cases over the size mapping) and three new
  `PenWidgetRendererTest` instrumented cases asserting dead space stays within the root padding,
  that panels and text grow with the widget, and that the submit control stays paired to the
  counter panel.

## Published release

- Release `v1.3.4` published to `noamvb/cannsheet-mobile-releases` at 2026-08-18T04:11:41Z by
  [run 32097861177](https://github.com/noamvb/cannsheet-mobile/actions/runs/32097861177), whose
  three jobs — Confirm tested main commit, Verify and build signed APK, and Publish verified
  Cannsheet APK — all succeeded.
- Assets: `Cannsheet-Mobile-1.3.4.apk` (13,800,314 bytes) and `Cannsheet-Mobile-1.3.4.apk.sha256`.
- APK SHA-256: `e7f6801811d31e93fd1f768734c500bf4ae66d8f4189bd2c0b8e0e1d4d5e4549`
  (independently re-downloaded and verified with `shasum -a 256 -c`: OK).
- Badging: `package com.noamv.cannsheet.mobile`, `versionCode 35`, `versionName 1.3.4`,
  `sdkVersion 24`, `targetSdkVersion 36`. Verified with APK Signature Scheme v2 only
  (v1/v3/v3.1/v4 false), 1 signer.
- **Signing certificate is unchanged from v1.3.3.** Both releases report SHA-256
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, DN
  `C=US, O=Android, CN=Android Debug`. The phone can therefore update in place; no uninstall is
  required, so the Room database and any pending offline-queue rows are preserved.
- `versionCode` monotonicity confirmed against the previous published APK, not the previous tag:
  v1.3.3's APK reports `versionCode 34`, and this release is `35`.

## Data-safety notes

- Presentation only. No Room schema, migration, DataStore payload, offline-queue,
  `SyncEngine`, idempotency, acknowledgement, or retry change.
- `secondsToUses` is untouched and seconds still never reach Room, the offline queue, or the
  wire. The widget's commit, undo, and claim/write/complete arbitration paths are unchanged.
- The production Apps Script endpoint, application ID, package/namespace, environment IDs,
  credentials, and signing configuration are unchanged.
- Behaviour change at the small end: when a widget is shorter than its own content the two rows
  now share the shortfall proportionally instead of the bottom control being clipped. At the
  `110dp` compact floor a control can measure about `2dp` under nominal; `PenWidgetRendererTest`
  encodes that tolerance explicitly below `160dp` and holds the full `40dp` floor above it.
- Six new `NestedWeights` lint warnings, one per weighted row, are accepted: a horizontal
  `LinearLayout` cannot fill remaining width without a weight. Lint remains at 0 errors.

## Device evidence and its limits

- **No physical device was used for this release.** The Samsung SM-F966W was not connected over
  adb at any point. A debug build cannot be installed over the production package without an
  uninstall that would destroy the Room database and any pending offline-queue rows, and no
  sandbox package was built for this change.
- Visual evidence is emulator-rendered, not phone-rendered: before/after renders were captured
  from the real `RemoteViews` path on a local API 36 emulator in dark mode at `140x110`,
  `140x160`, `285x295`, and `360x360` dp using a temporary capture harness that was removed
  before the commit. The `285x295dp` pair is checked in at
  `docs/images/pen-widget-285x295-before.png` and `docs/images/pen-widget-285x295-after.png`.
- No production widget was placed, resized, or tapped, and no production data action was
  performed. Confirming the resized widget on the Fold's own launcher remains outstanding.
- This is nonetheless the first widget change in this repository verified by executed Android
  instrumentation rather than compilation alone.

## Next steps

- The phone owner updates through Obtainium, which tracks `noamvb/cannsheet-mobile-releases`.
- If a physical widget walkthrough is wanted, it needs a temporary build type with an
  `applicationIdSuffix` (or the existing `sandbox` build type plus a `sandbox.properties`
  endpoint), never a debug build over the production package.

## Relevant files

- `app/src/main/java/com/example/widget/PenWidgetSizing.kt`
- `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`
- `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`
- `app/src/main/res/layout/widget_pen_consumption.xml`
- `app/src/main/res/layout/widget_pen_consumption_compact.xml`
- `app/src/main/res/layout/widget_pen_consumption_preview.xml`
- `app/src/test/java/com/example/widget/PenWidgetSizingTest.kt`
- `app/src/androidTest/java/com/example/widget/PenWidgetRendererTest.kt`
- `app/build.gradle.kts`
- `docs/DECISIONS.md` (ADR-023)
- `docs/PROJECT_STATE.md`
- `docs/HANDOFF.md`
- `docs/images/pen-widget-285x295-before.png`, `docs/images/pen-widget-285x295-after.png`
