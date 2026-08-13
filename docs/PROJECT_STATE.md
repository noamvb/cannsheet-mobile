# Project state

Last updated: 2026-08-13

## Repository state

- Canonical branch: `main`
- Latest application release source commit and tag `v1.3.0`:
  `a733a9d2741c4c6eaaa074461a354ffa6fb9751e`
- Current release metadata in `app/build.gradle.kts`: version name `1.3.0`,
  version code `31`
- Version-only release [PR #73](https://github.com/noamvb/cannsheet-mobile/pull/73)
  was squash-merged as `a733a9d2741c4c6eaaa074461a354ffa6fb9751e`. Its PR gate
  passed in [run 31733035509](https://github.com/noamvb/cannsheet-mobile/actions/runs/31733035509),
  and the exact post-merge `main` six-job validation passed in
  [run 31733624463](https://github.com/noamvb/cannsheet-mobile/actions/runs/31733624463),
  including API 24, API 36, backend, static, classification, and aggregate jobs.
- The `v1.3.0` tag points to that exact validated `main` commit. Signed
  publication [run 31734329091](https://github.com/noamvb/cannsheet-mobile/actions/runs/31734329091)
  passed exact-main proof, protected signing, version monotonicity, signature
  and metadata checks, artifact upload, public release creation, and
  post-publication verification.
- The public signed release is
  [Cannsheet Mobile 1.3.0](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.3.0).
  Its two authored assets are `Cannsheet-Mobile-1.3.0.apk` and
  `Cannsheet-Mobile-1.3.0.apk.sha256`.
- Independently downloaded public APK SHA-256:
  `d3d2076341afb489ddd59e479ff3cb60d8b0e7af484b9e7b829ae054ed5bf2c7`
- The independent download reports package `com.noamv.cannsheet.mobile`,
  version code `31`, version name `1.3.0`, minimum SDK 24, target SDK 36, and
  launcher `com.example.MainActivity`. Local `aapt` and `apksigner verify`
  confirm the metadata and APK Signature Scheme v2. Its certificate and public
  key digests match both the public v1.2.27 APK and the APK pulled from the
  production phone before update, preserving in-place update compatibility.

## v1.3 feature work (released in v1.3.0)

The accepted v1.3 feature sequence is implemented, documented, released, and
installed through its bounded package-update step. The source changes were
delivered as eight independently reviewable squash merges from the released
v1.2.27 base:

| Change | Pull request | Exact merged `main` commit | Exact-main validation |
|---|---|---|---|
| Deliberate backup and device-transfer policy | [#64](https://github.com/noamvb/cannsheet-mobile/pull/64) | `039196f37579ec42cc7d31bdee0a30aae14e38c4` | [run 31669121074](https://github.com/noamvb/cannsheet-mobile/actions/runs/31669121074) |
| Queue episode and alert-claim state, without presentation | [#65](https://github.com/noamvb/cannsheet-mobile/pull/65) | `8b450ab7da807860649c20cc3f4692024941807e` | [run 31672286395](https://github.com/noamvb/cannsheet-mobile/actions/runs/31672286395) |
| Shared, byte-identical out-of-app start-route extra | [#66](https://github.com/noamvb/cannsheet-mobile/pull/66) | `aba8081fdd2d2b845276f222871160c142bf814d` | [run 31672786453](https://github.com/noamvb/cannsheet-mobile/actions/runs/31672786453) |
| Opt-in queue-integrity notification delivery | [#67](https://github.com/noamvb/cannsheet-mobile/pull/67) | `e5a759f5a1b7961c7d9cef512d26e8d58689d5c9` | [run 31711445230](https://github.com/noamvb/cannsheet-mobile/actions/runs/31711445230) |
| Inventory-runway and current-month spend model | [#68](https://github.com/noamvb/cannsheet-mobile/pull/68) | `e10b251edc70e6b367d0af183b6dca3de1dd88a6` | [run 31715871378](https://github.com/noamvb/cannsheet-mobile/actions/runs/31715871378) |
| Runway and spend presentation on Insights and Log | [#69](https://github.com/noamvb/cannsheet-mobile/pull/69) | `c1c8ddc53bf6601c11408914206e09959c19c9b8` | [run 31723327998](https://github.com/noamvb/cannsheet-mobile/actions/runs/31723327998) |
| Local width breakpoints and adaptive navigation chrome | [#70](https://github.com/noamvb/cannsheet-mobile/pull/70) | `19d61abc3f132c4e8a72d3fea04d7b2c9172cd16` | [run 31727193756](https://github.com/noamvb/cannsheet-mobile/actions/runs/31727193756) |
| Expanded-width Insights and History detail panes | [#71](https://github.com/noamvb/cannsheet-mobile/pull/71) | `d8b9efbdb3c8b6c9603a6aa5d6d267677c3d8511` | [run 31729893399](https://github.com/noamvb/cannsheet-mobile/actions/runs/31729893399) |

Each exact-main run above passed classification, backend validation, Android
static validation, API 24 and API 36 emulator jobs, and the required aggregate
check. The final adaptive-layout run completed in 6m44s. These are CI and
emulator evidence, not a physical-device walkthrough.

Documentation [PR #72](https://github.com/noamvb/cannsheet-mobile/pull/72)
merged as `ea76f1b23263952c3ef861795f6fa6efaee6191d`; exact-main
[run 31731518220](https://github.com/noamvb/cannsheet-mobile/actions/runs/31731518220)
passed all six jobs. The deliberately separate version-only PR #73, exact-main
run, signed publication, independent artifact verification, and bounded phone
installation are recorded in Repository state and Release and validation
status below.

The queue alert is local, off by default, subordinate to the background-sync
kill switch, and limited to aggregate count/reason copy. It can surface an
environment mismatch, partial rejection, pending backend capability, or a
continuously non-empty queue at least 24 hours old. Ordinary retry exhaustion
does not alert by itself. Alert evaluation and presentation never mutate a
queue row.

Runway and spend pace are presentation-only estimates from the existing
versioned Insights payload. They require a fresh live snapshot and no pending
local actions. Capacity evidence comes from medians over the user's own
finished products; burn windows and month eligibility use the response time
zone and range. No new backend request, Apps Script field, Room table, queue
payload, or spreadsheet write was introduced.

Navigation uses locally derived Material width boundaries: compact below
600dp, medium from 600dp through 839dp, and expanded from 840dp. Compact keeps
the bottom bar; medium and expanded use a rail; expanded Insights and History
use a 40/60 list-detail layout backed by the same detail bodies as the modal
sheets. This is width-responsive behavior, not hinge-aware placement.

A bounded production Samsung Fold package session installed v1.3.0 in place
and performed package/signature/readback checks only. No app screen was
launched, so no production notification, 24-hour queue episode, runway
estimate, navigation rail, or expanded two-pane layout was exercised. No
screenshot or recording is claimed. The production Apps Script and spreadsheet
were not changed or probed.

## Pen widget follow-up implementation (released in v1.2.27)

- The follow-up guide is based on `00f7860`, the current v1.2.26 `main` tip.
  The guide names `docs/WIDGET_FOLLOWUP_PLAN.md`, but that file is not present
  on the base checkout or its review branch; `docs/WIDGET_REVIEW_PLAN.md` is
  the canonical in-repository review and resolution record.
- The first follow-up change moves undo-versus-claim arbitration into
  `resolveUndo`: a live claim cannot be restored, while a stale claim remains
  undoable for process-death recovery. The repository threads an injectable
  clock through `undo`, and the action-routing seam tests this rule without a
  live broadcast dispatch.
- The provider now preserves the existing `flushOverdue` → route → render
  ordering while delegating mutation routing to `PenWidgetActionRouter`. The
  seam covers final-draft capture, live-claim undo, pre-claim undo, invalid
  loaded-pen/rate states, and reset behavior. Open-app actions remain outside
  `HANDLED_ACTIONS`.
- This follow-up has not changed the Room schema, queue payloads, Apps Script
  contract, endpoint, or package ID. PR #61 was squash-merged as
  `72f1b0920f70001e02916000ba8e8dc40d3a7ee8` after its replacement full
  Android PR gate [run 31664591647](https://github.com/noamvb/cannsheet-mobile/actions/runs/31664591647)
  passed. The requested version-only release PR #62 then bumped only the
  application version metadata and was released as v1.2.27 through the gated
  publication workflow recorded above.
- PR1 was squash-merged as [PR #59](https://github.com/noamvb/cannsheet-mobile/pull/59)
  at `c8329336a7cb766f5c44df030b8aa9707652e701` after its full API 24 PR gate
  [run 31661910965](https://github.com/noamvb/cannsheet-mobile/actions/runs/31661910965)
  passed classification, backend, Android static, emulator, and aggregate
  validation. PR2 was squash-merged as [PR #60](https://github.com/noamvb/cannsheet-mobile/pull/60)
  at `435ac39bd6dd3d91b8d051ed7e9c085ace6d7b07` after its full API 24 PR gate
  [run 31662427658](https://github.com/noamvb/cannsheet-mobile/actions/runs/31662427658)
  passed the same classification, backend, Android static, emulator, and
  aggregate jobs. The remaining T3 sizing change is now isolated in its own
  branch.
- The sandbox-only Fold sizing session measured the launcher callback options
  for the pinned sandbox widget in all four requested default placements:
  cover portrait `300dp`, main portrait `274dp`, main landscape `259dp`, and
  cover landscape `300dp` (`OPTION_APPWIDGET_MIN_HEIGHT` and `MAX_HEIGHT` were
  equal in each callback). The Samsung free-grid selection surface did not
  expose a usable minimum-resize handle for this temporary widget, so no
  minimum-resize value is claimed. Because every observed default is above
  `160dp`, the follow-up uses the guide's evidence-backed compact decision:
  `minResizeHeight=110dp`, a `150dp` compact breakpoint, and an explicit
  weighted spacer in both full and compact layouts. The probe used the
  suffixed sandbox package with an invalid non-production endpoint, was
  removed afterward, and did not submit a production widget action.
- Home-screen pen widget feature [PR #52](https://github.com/noamvb/cannsheet-mobile/pull/52)
  was squash-merged as `0e9bb650de7c9a3d7d629f20bedda5857528770b`; its final
  six-job gate passed in [run 31621145764](https://github.com/noamvb/cannsheet-mobile/actions/runs/31621145764).
- Widget remediation [PR #55](https://github.com/noamvb/cannsheet-mobile/pull/55)
  implements the source-review findings without a version, Room schema, queue,
  backend, endpoint, package, or signing change. Its local Android gate passed
  with JDK 17.0.20, Android platform 36.1, and Build Tools 36.0.0:
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
  The run completed with `BUILD SUCCESSFUL`; 186 JVM tests passed, Android-test
  Kotlin compilation and lint completed, and the debug APK assembled. All eight
  checked-in Node.js backend suites passed with the bundled runtime, and
  `python3 -m unittest tests/test_backend_sync_benchmark.py` passed all 13 tests.
- Historical v1.2.25 version-only release [PR #53](https://github.com/noamvb/cannsheet-mobile/pull/53)
  was squash-merged as `7c652fb48b4de5ba20b003abc828df9111124d73`; its five-job
  PR gate passed in [run 31621733498](https://github.com/noamvb/cannsheet-mobile/actions/runs/31621733498).
- The exact post-merge `main` six-job validation passed in
  [run 31622170237](https://github.com/noamvb/cannsheet-mobile/actions/runs/31622170237),
  including API 24, API 36, backend, static, classification, and aggregate jobs.
- The historical v1.2.25 signed publication workflow
  [run 31622788837](https://github.com/noamvb/cannsheet-mobile/actions/runs/31622788837)
  passed exact-main proof, signed build, signature verification, artifact
  upload, public publication, and post-publication checks.
- The historical v1.2.25 public signed release was
  [Cannsheet Mobile 1.2.25](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.25).
  Its exact APK asset is `Cannsheet-Mobile-1.2.25.apk`, accompanied by
  `Cannsheet-Mobile-1.2.25.apk.sha256`.
- Historical v1.2.25 independently downloaded public APK SHA-256:
  `e6283536c4e00f60db473660370390bcf433e43d0d18dc7d49a3e8acbb7aa45a`
- The historical v1.2.25 release workflow verified the signed APK's package and
  version metadata; its independent local download matched the public checksum.
- The historical v1.2.25 production readback before installation was version
  code `27`, version name `1.2.24`; the readback after installation was version
  code `28`, version name `1.2.25`, with the `PenConsumptionWidgetProvider`
  receiver present. The in-place update used `adb install -r`; no uninstall,
  data clear, downgrade, app launch, widget interaction, or production data
  action was used.
- ADB was disconnected after the historical v1.2.25 package readback. The
  v1.2.26 publication did not access the phone.
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
- On the wireless the intended production device, Settings exposed all six product types;
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

## One-tap pen logging feature work

- The submission countdown now uses one `PendingSubmission` holder. A new
  purchase, consumption, borrowed-consumption, or finish action flushes the
  displaced callback immediately before starting its own countdown, so a
  second quick submission is never silently discarded. The holder's
  take-once behavior is covered by JVM regression tests. This prerequisite was
  delivered through PR #48 and merged as
  `19f00174268bc5b93065c61a8407aeeaebf388b9`; its six-job PR gate passed in
  [run 31550011273](https://github.com/noamvb/cannsheet-mobile/actions/runs/31550011273).
- The pen quick-log implementation adds a version-1 seconds-per-use map and a
  loaded-pen product ID to the existing `consumption_preferences` DataStore.
  A missing duration payload seeds `P` at 10 seconds per use; an explicit
  payload without `P` preserves the user's decision to turn that rate off,
  including after a clear and relaunch. Invalid duration records are skipped
  defensively.
- The Log screen resolves an explicit selectable `P` cart first and otherwise
  uses the most recently logged selectable pen. Its duration chips display
  seconds but pass uses through the existing countdown, Room queue, sync, and
  cancellation controls. Successful local pen logs auto-select the logged cart;
  finishing the loaded cart clears it. The Settings type editor can enable,
  save, preview, and clear a seconds-per-use rate. No Room schema, queue
  payload, Apps Script contract, endpoint, package ID, or release metadata is
  changed.
- The feature was delivered through [PR #49](https://github.com/noamvb/cannsheet-mobile/pull/49),
  squash-merged as `76dc95a60a45b699471e6b9ca93c5132307b6081`, and validated by
  its five-job PR gate in [run 31551639733](https://github.com/noamvb/cannsheet-mobile/actions/runs/31551639733)
  plus the exact merged-main six-job matrix in
  [run 31552090083](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552090083).
- Local validation for the implementation branch passed with JDK 17.0.20,
  Gradle 9.3.1, Android Platform 36.1, and Build Tools 36.0.0:
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
  The run completed with `BUILD SUCCESSFUL`; 161 JVM tests completed,
  Android-test Kotlin compilation completed, lint completed, and the debug APK
  assembled. The bundled Node runtime also passed
  `tests/backend_analytics_test.js`. The required backend suites and emulator
  instrumentation also passed in the GitHub Actions gates above.
- Version-only release [PR #50](https://github.com/noamvb/cannsheet-mobile/pull/50)
  changed only the application version metadata, merged as
  `7a112d315d5b6666bca8f582bc50f6db430345ab`, and passed its PR gate in
  [run 31552545541](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552545541)
  plus the exact versioned-main matrix in
  [run 31552869317](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552869317).
- Signed publication [run 31553283041](https://github.com/noamvb/cannsheet-mobile/actions/runs/31553283041)
  produced and published v1.2.24. The independently downloaded APK has
  SHA-256 `b899a98d4c48cc20663a05270e56535af90f3584e91bcf0e53cc3e6ea244d6d0`,
  reports package `com.noamv.cannsheet.mobile`, version code `27`, version
  name `1.2.24`, and passed local v2 signature verification.
- The bounded manual walkthrough used a separate debug-signed package
  `com.noamv.cannsheet.mobile.devicecheck124` with endpoint
  `https://devicecheck.invalid/exec`, so its actions could not reach the
  production Apps Script backend. It verified the loaded `BH Raspberry Riptide`
  pen card, 5s/15s/20s chips, 15s = 1.5 uses, the existing countdown and
  cancellation controls, pending accumulation from two rapid chips, and the
  Settings duration save/clear behavior across relaunch. Screenshots are
  attached to the PR #49 conversation.
- The signed v1.2.24 production APK was separately installed in place as
  `com.noamv.cannsheet.mobile`; package readback showed version code `27`,
  version name `1.2.24`, the existing production data directory, and a
  successful launch. No production Log, Purchase, Finish, or Sync action was
  submitted during the walkthrough or launch check. ADB was disconnected after
  readback, and the temporary `devicecheck` build type was removed from the
  source checkout.

## Home-screen pen widget feature work

- The widget implementation was delivered through
  [PR #52](https://github.com/noamvb/cannsheet-mobile/pull/52), squash-merged
  as `0e9bb650de7c9a3d7d629f20bedda5857528770b`, and released in v1.2.25.
  It uses the classic `AppWidgetProvider`/`RemoteViews` API and does not add
  Glance or a second data/network path.
- The widget reuses the loaded-pen resolution, date/time, rate, and
  `ConsumptionLogger` boundaries. Seconds are display/input units only;
  `secondsToUses` runs before Room, the offline queue, or Apps Script payloads.
- A submit captures the stable consumption ID and immutable product/date/time/
  rate/seconds/uses payload in the `pen_widget_state` DataStore. PR #55 adds a
  five-second process-local Undo timer with 1.5 seconds of delivery grace,
  keeps unique WorkManager work as a process-death backstop, and lazily flushes
  overdue payloads on widget broadcasts and application startup.
- PR #55 replaces destructive payload removal with claim/write/complete:
  the payload remains until its stable event is durably inserted into Room,
  failed writes release the claim for idempotent retry, process-owned claim
  tokens recover process death, and widget deletion force-commits instead of
  discarding a fresh submission. A widget commit never re-points the user's
  currently loaded cart.
- Widget states are `Unavailable`, `NoCart`, `RateOff`, `Composing`, and
  `AwaitingCommit`. The editable draft starts at zero, moves in ten-second
  steps, clamps to 0..600 seconds, and enables submit only for a positive
  value. Widget refreshes are requested by provider actions, application
  startup, reactive pen-state changes, and acknowledged sync work through a
  data-facing refresher interface.
- PR #55 also adds serialized provider/worker execution, exception-safe
  receiver completion, one-shot `singleTop` app navigation, resource-backed
  copy and dynamic accessibility descriptions, neutral and representative
  previews, full/compact layouts with corrected provider dimensions, and
  presentation-only rounding for seconds converted from stored uses.
- The change does not alter the Room schema, existing queue payloads, Apps
  Script contract, endpoint, package ID, or signing configuration. API-safe
  `RemoteViews` rendering is covered by the final CI API 24/API 36 paths.
- Source previews and CI RemoteViews/state tests were produced, but no physical
  widget was tapped in the production package during the v1.2.25 install.
  The device evidence is limited to signed package installation and readback;
  a sandbox package is required for any future visual/action walkthrough.
- The PR #55 preview asset is a generated representative widget-picker image,
  not physical-device evidence. No PR #55 launcher screenshot, physical widget
  interaction, emulator instrumentation execution, or production-data action
  has been performed; Android instrumentation source was compiled only.

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
- Wireless ADB later reached the intended production device at
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
- Wireless ADB reached the intended production device. The published signed
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

Physical-device validation used an isolated test application ID on an Android device running Android 16 / API 36; the installed production and older
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

v1.3.0 evidence (current release):

- Source PRs #64 through #71 and documentation PR #72 are listed in the v1.3
  feature-work section above with exact merge commits and exact-main runs.
- On the version-only branch, the exact command
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
  passed with JDK 17, Android platform 36.1, and Build Tools 36.0.0. Gradle
  reported `BUILD SUCCESSFUL` in 6m30s; 333 JVM tests passed with zero
  failures, errors, or skips, Android-test Kotlin compiled, lint completed,
  and the debug APK assembled. `git diff --check` passed, and the complete
  branch diff was one file with exactly two additions and two deletions.
- Version-only [PR #73](https://github.com/noamvb/cannsheet-mobile/pull/73)
  merged as `a733a9d2741c4c6eaaa074461a354ffa6fb9751e`. Its PR gate
  [run 31733035509](https://github.com/noamvb/cannsheet-mobile/actions/runs/31733035509)
  passed, and exact-main [run 31733624463](https://github.com/noamvb/cannsheet-mobile/actions/runs/31733624463)
  passed classification/security, backend validation, Android static
  validation, API 24, API 36, and the required aggregate in 6m59s.
- Annotated tag `v1.3.0` resolves to that exact versioned-main commit. Signed
  publication [run 31734329091](https://github.com/noamvb/cannsheet-mobile/actions/runs/31734329091)
  passed exact-main proof, version and monotonicity checks, tests/lint, release
  assembly, APK signature and metadata checks, checksum creation, public
  publication, and post-publication verification in 5m53s.
- The public release is [Cannsheet Mobile 1.3.0](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.3.0):
  `Cannsheet-Mobile-1.3.0.apk` and `Cannsheet-Mobile-1.3.0.apk.sha256`.
  An independent download matched SHA-256
  `d3d2076341afb489ddd59e479ff3cb60d8b0e7af484b9e7b829ae054ed5bf2c7`.
  `aapt` confirmed package `com.noamv.cannsheet.mobile`, version code `31`,
  version name `1.3.0`, minimum SDK 24, target SDK 36, and launcher
  `com.example.MainActivity`; `apksigner verify` confirmed one v2 signer.
- The signer subject remains the same unexpected Android Debug subject as the
  prior production release, but certificate and public-key digests matched the
  public v1.2.27 APK and the pre-update APK pulled from the phone. The installed
  v1.2.27 APK was also byte-identical to the public v1.2.27 APK.
- Bounded wireless ADB readback on the production Samsung Fold showed the
  transition from version code `30` / version name `1.2.27` to `31` / `1.3.0`
  after `adb install -r`. Signing version/keyset, data directory, data inode,
  and first-install time were unchanged. The post-install APK pulled from the
  phone was byte-identical to the independently verified public v1.3.0 APK.
  The launcher resolved without being launched; the app was force-stopped,
  ADB was disconnected, and the final device list was empty. No app screen,
  notification, widget, queue action, sync, or production data mutation was
  exercised.

v1.2.27 evidence (prior release):

- Follow-up PR #59 (`c8329336a7cb766f5c44df030b8aa9707652e701`), PR #60
  (`435ac39bd6dd3d91b8d051ed7e9c085ace6d7b07`), and PR #61
  (`72f1b0920f70001e02916000ba8e8dc40d3a7ee8`) delivered the T1/T2, T4/T5,
  and T3 changes from the follow-up guide. Their required validation runs
  passed, including the API 24 RemoteViews-safe renderer correction in the
  final PR #61 run.
- Version-only PR #62 merged as `39abdf3814b1ff1f75ca06ca2d78e72a10d281b5`.
  Its PR gate passed in [run 31665017557](https://github.com/noamvb/cannsheet-mobile/actions/runs/31665017557),
  and the exact versioned-main commit passed all six required jobs in
  [run 31665252525](https://github.com/noamvb/cannsheet-mobile/actions/runs/31665252525).
- Annotated tag `v1.2.27` points to that exact main commit. Signed publication
  [run 31665589830](https://github.com/noamvb/cannsheet-mobile/actions/runs/31665589830)
  passed exact-main proof, unit tests/lint, monotonic version-code validation,
  signed APK build, independent signature/metadata checks, checksum creation,
  public publication, and post-publication verification.
- The public release is [Cannsheet Mobile 1.2.27](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.27):
  `Cannsheet-Mobile-1.2.27.apk` and `Cannsheet-Mobile-1.2.27.apk.sha256`.
  The independently downloaded APK matched the published SHA-256
  `05ec7a07e3f686e7ae95a613d56e311cd498cbec83d09cda743b424f404af92d`.
- Local `aapt` reported package `com.noamv.cannsheet.mobile`, version code
  `30`, and version name `1.2.27`. Local `apksigner verify` reported a valid
  v2 signature and the same certificate digest as v1.2.26.
- Bounded wireless ADB readback showed the production package transition from
  version code `29` / version name `1.2.26` to `30` / `1.2.27` after
  `adb install -r`. The signing identity, package data directory, and first
  install time were unchanged. ADB was disconnected afterward and no app
  launch, widget interaction, production submission, or synthetic data action
  was performed. This is package/readback evidence only; no physical
  production widget screenshot or action walkthrough was performed.

Prior release (v1.2.25) evidence, retained for history:

- Widget feature PR #52 merged as `0e9bb650de7c9a3d7d629f20bedda5857528770b` after final six-job run [31621145764](https://github.com/noamvb/cannsheet-mobile/actions/runs/31621145764). Version PR #53's checks passed [run 31621733498](https://github.com/noamvb/cannsheet-mobile/actions/runs/31621733498); its version-bump merge commit `7c652fb48b4de5ba20b003abc828df9111124d73` passed the exact versioned-main six-job matrix in [run 31622170237](https://github.com/noamvb/cannsheet-mobile/actions/runs/31622170237).
- Signed publication workflow [run 31622788837](https://github.com/noamvb/cannsheet-mobile/actions/runs/31622788837) passed exact-main validation, signed build, signature verification, public publication, and post-publication verification.
- The public release is [Cannsheet Mobile 1.2.25](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.25): `Cannsheet-Mobile-1.2.25.apk` and its `.sha256`. Independently downloaded APK SHA-256: `e6283536c4e00f60db473660370390bcf433e43d0d18dc7d49a3e8acbb7aa45a`.
- The publication workflow verified package `com.noamv.cannsheet.mobile`, version code `28`, and version name `1.2.25`; the independent local download matched the public checksum. A separate local `apksigner` verification was not performed for this release session.
- Wireless ADB readback on the Samsung SM-F966W, Android API 36, showed the production package transition from version code `27` / version name `1.2.24` to version code `28` / version name `1.2.25`, with the `PenConsumptionWidgetProvider` receiver present. The in-place update used `adb install -r`; no uninstall, data clear, downgrade, app launch, widget interaction, or production data action was performed.
- ADB was disconnected after the final package readback and the user was notified that the phone was safe to resume. This is package/readback evidence only; no physical production widget screenshot or action walkthrough was performed.

Prior release (v1.2.24) evidence, retained for history:

- Submission flush PR #48 merged as `19f00174268bc5b93065c61a8407aeeaebf388b9` after six-job run [31550011273](https://github.com/noamvb/cannsheet-mobile/actions/runs/31550011273). One-tap pen logging PR #49 merged as `76dc95a60a45b699471e6b9ca93c5132307b6081` after its five-job gate [31551639733](https://github.com/noamvb/cannsheet-mobile/actions/runs/31551639733) and exact merged-main matrix [31552090083](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552090083).
- Version PR #50's checks passed [run 31552545541](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552545541); its version-bump merge commit `7a112d315d5b6666bca8f582bc50f6db430345ab` passed the exact versioned-main matrix in [run 31552869317](https://github.com/noamvb/cannsheet-mobile/actions/runs/31552869317).
- Signed publication workflow [run 31553283041](https://github.com/noamvb/cannsheet-mobile/actions/runs/31553283041) passed exact-main validation, signed build, signature verification, public publication, and post-publication verification.
- The public release is [Cannsheet Mobile 1.2.24](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.24): `Cannsheet-Mobile-1.2.24.apk` and its `.sha256`. Independently downloaded APK SHA-256: `b899a98d4c48cc20663a05270e56535af90f3584e91bcf0e53cc3e6ea244d6d0`.
- The v1.2.24 production package was installed in place and launched successfully; ADB was disconnected afterward. The bounded debug-package walkthrough and screenshots remain described in the one-tap pen logging section above.

Prior release (v1.2.22) evidence, retained for history:

- History refresh feedback PR #40's final checks passed [run 31524608644](https://github.com/noamvb/cannsheet-mobile/actions/runs/31524608644); its feature merge commit `62d7cc6d960b0e13bdfd089152d14f8c20a308a1` was included in the exact release merge.
- Release PR #41's checks passed [run 31525437265](https://github.com/noamvb/cannsheet-mobile/actions/runs/31525437265); its version-bump merge commit `633bd898ab59dc9d30acb2ba530a41e5f94c1e2a` passed the full API 24/API 36 matrix in [run 31525848365](https://github.com/noamvb/cannsheet-mobile/actions/runs/31525848365).
- Signed publication workflow [run 31526429773](https://github.com/noamvb/cannsheet-mobile/actions/runs/31526429773) passed exact-main validation, release-secret validation, signing, publication, and post-publication verification.
- The public release is [Cannsheet Mobile 1.2.22](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.22): `Cannsheet-Mobile-1.2.22.apk` (13,509,402 bytes) and its `.sha256`. Independently downloaded APK SHA-256: `e02debc3efd922ee6005fcf2798d775b8e7d5e9ec7b0e0542d73171a3ea0ad32`.
- The publication workflow independently verified the expected signing certificate and confirmed package `com.noamv.cannsheet.mobile`, version code `25`, and version name `1.2.22`.
- Wireless ADB device readback: the intended production device, Android 16 / API 36. `dumpsys package` before and after installation reported the same production signing identity `the release signing identity` and data directory; the package changed from version code `24` / version name `1.2.21` to version code `25` / version name `1.2.22`, with `the post-install timestamp` after installation.
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

- The Kotlin namespace remains `com.example` while the application ID is
  `com.noamv.cannsheet.mobile`; `README.md` records this as an intentional
  source-layout compatibility choice.
- The public v1.3.0 APK is independently checksum-checked against its
  published `.sha256`; the signed publication workflow verified its package,
  higher version code, and signing certificate. The intended phone's v1.3.0
  in-place update transition was observed live and the production package/data
  boundary remained intact.
- The first real production correction lifecycle still requires a deliberate
  user/device check; no synthetic production correction was created for testing.
- v1.3.0 phone validation was package/readback only. No app screen,
  notification, production widget tap, consumption submission, or physical
  screenshot was exercised; CI emulator results and source previews remain
  separate evidence boundaries.
- Queue notifications, runway/spend estimates, navigation rail behavior, and
  the expanded Insights/History panes have CI coverage but no physical-device
  walkthrough. A real 24-hour offline queue was not created for testing.
- Adaptive layout decisions are width-only. They do not inspect a fold hinge or
  guarantee that a detail divider avoids the Samsung Fold's physical crease.

## Current priorities

1. If physical v1.3 UI coverage is needed, use an isolated sandbox/debug
   package and verify notification permission/channel states, queue-alert
   routing, fresh/stale/pending runway suppression, compact/medium/expanded
   navigation, and both Insights/History detail surfaces. Do not manufacture a
   production queue alert or spreadsheet write.
2. If physical widget visual/action coverage is needed, build a separate
   sandbox/debug package with a non-production endpoint and verify light/dark
   rendering, 2x2 sizing, +/- controls, reset, countdown/undo, and message
   states without creating production data.
3. Use the next genuine production purchase to validate default persistence and
   restart/autofill behavior without fabricating data.
4. Continue analytics prefetch and correction-safe usage-total checks; CI does
   not substitute for device evidence for those wake/offline workflows.

## Unresolved questions

- Will Android 13+ permission denial/revocation, a disabled notification
  channel, and the 24-hour no-network alert schedule behave as expected on the
  intended phone? CI validates API branches but not a real day-long queue.
- Do runway and month-spend estimates remain understandable against the user's
  real finished-product evidence, and do they disappear promptly for every
  stale or pending-action transition on the intended phone?
- Does the main Fold display provide enough useful width for the 40/60
  Insights and History panes in portrait and landscape, and is the
  width-only divider acceptable near the physical crease?
- Obtainium's post-install refresh was not observed in this session. The full
  History UI plan remains partially unverified: the offline error inside the
  detail sheet, correction save, rotation, and missing-entry dialog.
- Does the widget render and behave correctly on the intended physical device
  across light/dark themes, launcher sizing, and all local state transitions?
  CI RemoteViews tests and source previews do not answer that question.
- Does a genuine product suggestion on the installed production app apply and
  preserve a saved Purchase default across restart? The product selector was
  unavailable during this read-only session, and no synthetic purchase was
  created.
- Do the confirmed-versus-pending totals remain clear during real offline,
  retry, borrowed-product, and correction workflows on the intended phone?
- Does the periodic worker's analytics prefetch actually warm the Insights/History cache on a real device (no emulator/CI substitute exists for this), and does the 2-hour freshness floor behave as expected across real wake intervals?

These require device evidence and should not be answered from repository or
workflow evidence alone.

## Relevant paths

- `app/src/main/java/com/example/widget`
- `app/src/main/java/com/example/notifications`
- `app/src/main/java/com/example/ui`
- `app/src/main/java/com/example/data`
- `app/src/main/java/com/example/data/sync`
- `app/src/main/java/com/example/domain`
- `app/src/test`
- `app/src/androidTest`
- `tests/backend_corrections_test.js`
- `backend_additions.gs`
- `sandbox_provisioning.gs`
- `app/build.gradle.kts`
- `.github/workflows`
- `docs/HANDOFF.md`
