# Current handoff

Last updated: 2026-08-30

Repository: public `noamvb/cannsheet-mobile`

## Cannsheet Mobile v1.9.0 (code 54) - pen widget step repaired, in flight

**Status: published and independently verified on 2026-08-30.**

The pen widget's `+`/`-` buttons now move the counter by the number they state, and that
number is configuration rather than a function of widget size. See ADR-050 and the
"Pen widget step size" section of `docs/PROJECT_STATE.md`.

### What was wrong, and why the tests did not catch it

The report was "the 2x2 widget steps by 30 seconds instead of 10". Driving the real widget
over ADB on a Fold 7 (API 36) showed something narrower and worse: the `+` button announced
**"Increase duration by 10 seconds"** and moved the counter to **30**. The label and the
behaviour disagreed.

`PenWidgetUpdater` builds three `RemoteViews` per instance on API 31+ - buckets 110x110,
140x160 and 280x320 - and `PenWidgetSizing.resolve` gave only the largest
`stepSeconds = 30`. Each bucket asked `pendingIntent()` for a step-carrying intent, but
that function derived uniqueness from `31 * appWidgetId + action.hashCode()` and the data
URI `cannsheet://pen-widget/<id>/<action>`, neither of which encoded the step.
`PendingIntent` equality ignores extras, so all three buckets resolved to one
`PendingIntent`, `FLAG_UPDATE_CURRENT` made the last write win, and the large bucket was
written last. Text and content descriptions live inside each bucket's own `RemoteViews`,
so they stayed truthful while the effect did not.

The size rule added in #110 therefore never gated anything. It only poisoned the shared
intent, so **every** API 31+ pen widget without an explicit override stepped by 30 at every
size, including compact.

The suite passed throughout, because `PenWidgetRendererTest` asserted the content
description - precisely the half that was correct - and nothing asserted what a tap
actually delivered. A test that checks only the announcement of a behaviour cannot see the
behaviour diverge from it.

### What v1.9.0 changed

- `stepSeconds` is removed from `PenWidgetLayoutSpec` entirely, so "the buckets disagree
  about the step" is no longer expressible rather than merely false. `PenWidgetUpdater`
  resolves the step once per update and passes that one value to every bucket.
- `EXTRA_STEP_SECONDS` is deleted. `PenWidgetActionRouter` reads the step from the config
  repository it already held, so a stale `PendingIntent` issued by a pre-upgrade build is
  inert rather than authoritative - the fix applies on the next tap, before any repaint.
- `pendingIntent()`'s data URI now encodes `commitId` too, under a comment recording the
  rule that the URI must cover every extra that changes the intent's effect.
- An app-wide default step lives under an unsuffixed key in the existing
  `pen_widget_config` DataStore. `PenWidgetConfigRepository.effectiveStepSeconds` is the
  only resolver of `override ?: default`. The key is excluded from `clear`,
  `remapWidgetIds` and legacy adoption, and an invalid stored value falls back in memory
  without being rewritten.
- `stepSecondsOverride == null` inherits that default. The configure screen previously read
  null as 10 and wrote it back as an explicit 10, so merely opening and saving it detached
  that widget from the default forever; it now carries null through and offers Default.
- Full layouts render `+10s` / `-10s`. The compact layout keeps bare symbols, where a
  four-glyph label clips at roughly 45dp. The step's invisibility is why this survived.
- The provider is `reconfigurable`, and Settings gains a Widgets section with the app
  default plus a per-widget list. Before this the picker existed but could only be reached
  by deleting the widget and adding it again.
- Both Settings write paths await their repaint. A durable configuration write paired with
  a dropped render would reproduce the same label/behaviour split, so
  `PenWidgetUpdater.updateAllNow` suspends until the render lands rather than enqueueing it.

### Behaviour change on upgrade

Every placed pen widget without an explicit override steps by 10 after this release,
**including a large one that stepped by 30 before**. No migration runs. An individual
widget can be raised to 30 in Settings, or by long-pressing it and reconfiguring.

### Verification performed

- Local, `--rerun-tasks` so nothing came off a cache:
  `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`,
  `BUILD SUCCESSFUL in 8m 12s`, **588 unit tests, 0 failures, 0 errors, 0 skipped**
  (the `main` baseline was 587: three deleted size-to-step assertions, four new
  default-step tests).
- 8 Node backend suites pass; `python3 -m unittest tests/test_backend_sync_benchmark.py`
  reports 13 tests, OK.
- Pull request `noamvb/cannsheet-mobile#169`: all five checks green, including
  Emulator API 24 at 4m53s, which is the first actual execution of the new instrumentation
  coverage.

New coverage aimed squarely at the gap that let this ship:

- `PenWidgetActionRouterTest.incrementUsesEffectiveStepAndIgnoresAStaleIntentExtra` puts
  the literal legacy extra name with value `30` on the intent and asserts the draft still
  moves by the effective step. It fails against the pre-fix code.
- `PenWidgetRendererTest.everyBucketKeepsVisibleLabelsAndDescriptionsAlignedWithTheEffectiveStep`
  asserts visible text and content description **together** across all three buckets, with
  a step of 5 so it cannot pass by coincidence.
- `PenWidgetDefaultStepTest` covers inheritance, clamping, `clear`/`remapWidgetIds`
  preservation, and that an invalid stored value falls back without being repaired.
- `PenWidgetConfigureActivityTest` proves a null override selects Default and Save emits
  null.

### Release provenance

- Pull request merged: `noamvb/cannsheet-mobile#169`, squash merged
- Squashed commit on `main`: `7e9eb58f4dcb9687d8594deb324be7ae4926b97f`
- Proving `main` run: `33315508424`, `event=push`, `conclusion=success`
- All six required jobs passed on that exact SHA: `Classify changes and scan repository`,
  `Backend validation`, `Android static validation`, `Emulator API 24`, `Emulator API 36`,
  and `Cannsheet Android PR validation`. Emulator API 36 only ever runs on `main`, which is
  why a green pull-request check can never satisfy the publish workflow.
- Annotated tag `v1.9.0` points at exactly that validated commit: `git rev-list -n 1 v1.9.0`
  returns `7e9eb58f4dcb9687d8594deb324be7ae4926b97f`
- Published by release workflow run `33315829391` at 2026-08-30T14:10:40Z:
  `Cannsheet-Mobile-1.9.0.apk` (38,003,753 bytes) and `Cannsheet-Mobile-1.9.0.apk.sha256`
- APK SHA-256: `cdb44ae9fd126e0b987b62f0f8bba6a1a5d87cac9e56ff6147321ec0f598eb6d`,
  re-downloaded and checked independently with `shasum -a 256 -c` after publication: OK.
  `aapt dump badging` reports package `com.noamv.cannsheet.mobile`, versionCode 54,
  versionName 1.9.0, sdkVersion 24, targetSdkVersion 36.
- Signing certificate: `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`
  (`CN=Android Debug`), byte-identical to the certificate on the published v1.8.1 APK.
  `apksigner verify` reports `Verifies` with APK Signature Scheme v2 and no other scheme.
  The phone updates in place; no uninstall is required and no Room data or queued offline
  row is at risk.

### Known limitations

- Instrumentation tests were compiled locally but not executed there.
  `connectedDebugAndroidTest` targets every attached device, and the owner's phone carries
  the release build; a debug build shares its application id but is signed differently, so
  running them would force an uninstall and destroy user data. CI covers them on both API
  levels.
- No screenshots accompany the pull request, for the same reason: the only attached device
  is the owner's phone, and no local emulator is provisioned. The visible change is
  asserted mechanically instead, by the renderer test checking exact button text at each
  bucket.
- On-device confirmation that the shipped build steps by 10 has **not** happened and cannot
  happen until the owner installs 1.9.0 through Obtainium. The pre-fix behaviour was
  confirmed on hardware; the post-fix behaviour is currently proven only by CI.
- The `+10s` / `-10s` labels need a widget repaint to appear, so they may briefly lag after
  install. The stepping itself is correct from the first tap, because the router reads
  configuration rather than an intent extra.

### Operational notes worth keeping

- The CI emulator jobs are intermittently unreliable in a way that looks like a hang: test
  progress stops at a fixed count and the step is killed at its 20-minute cap. One such
  failure and its immediate re-run on the same commit are decisive - the failed run went
  silent at 105/174 for sixteen minutes, the re-run completed the remaining 66 tests in
  nine seconds. Silence at a fixed count means a dead emulator, not a hanging test, and
  warrants a re-run rather than a fix.
- `main` is governed by a repository ruleset, not classic branch protection, and it sets
  `required_review_thread_resolution`. An unresolved review thread blocks the merge even
  with every check green, and `gh pr merge` reports only "the base branch policy prohibits
  the merge". The `chatgpt-codex-connector` bot posts review threads automatically, so a
  pull request can arrive at "all checks green" and still be unmergeable until those
  threads are read and resolved. Resolving requires the GraphQL `resolveReviewThread`
  mutation; `gh pr review` does not do it.
- A delegated run can be killed by the orchestrator's own harness restarting, leaving every
  file written, no verification build ever run, and no report. A complete-looking working
  tree says nothing about whether the code compiles. Run acceptance yourself.

### Next step for the phone owner

v1.9.0 is published. Open Obtainium, pull to refresh or tap **Check for updates**, and
install Cannsheet Mobile 1.9.0.

After installing, the fix is worth one check: tap `+` once on the small pen widget and
confirm the counter moves to 10, not 30. On-device confirmation of the shipped build has
not been performed - the pre-fix behaviour was reproduced on hardware, but the post-fix
behaviour is so far proven only by CI, because installing a debug build over the release
build would have forced an uninstall and destroyed the Room database.
