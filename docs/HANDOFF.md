# Current handoff

Last updated: 2026-09-02

Repository: public `noamvb/cannsheet-mobile`

## Cannsheet Mobile v1.10.0 (code 56) - purchase tax basis, autofill basis, THC formatting

**Status: v1.10.0 published and independently verified on 2026-09-02.** On-device
confirmation by the owner has not happened yet and cannot until they install it
through Obtainium.

### What shipped

Three changes to the purchase form:

- The tax basis is an explicit Pre-tax / Post-tax segmented choice headed "Price
  entered as", sited above the cost field, whose label follows the choice. The
  converted amount shows under the field - `$56.50 with 13% tax`, or `$44.25 before
  13% tax`. ADR-051.
- That basis now travels with every autofilled cost. An unrecorded basis is `null`,
  deliberately distinct from `false`, and autofill warns rather than assuming
  pre-tax. ADR-052.
- The autofilled THC percent is rounded to two decimals, ending a float artifact
  (`27.140000000000004`) that predated both features.

### Release provenance

**Pull request merged**

- `#173` "Purchase tax basis, autofill basis, and THC formatting (release 1.10.0)",
  squash-merged as `9144353326ef3e0a033b53524902c27cae4d4fb6`.

**Main validation**

- Run `33690914527`, `event: push`, `headSha`
  `9144353326ef3e0a033b53524902c27cae4d4fb6`, final conclusion `success`.
- All six required jobs individually `success`: Classify changes and scan
  repository, Backend validation, Android static validation, Emulator API 24,
  Emulator API 36, Cannsheet Android PR validation.
- `Emulator API 24` failed on the first attempt with
  `Failed to install split APK(s)` / `ShellCommandUnresponsiveException` and an
  emulator console that never started - the install flake the workflow itself
  documents, not a test failure. `Emulator API 36` passed on that same attempt with
  the same commit. The failed jobs were re-run on the **same run id**, so the green
  result still carries `event: push` at the required SHA.

**Tag**

- Annotated tag `v1.10.0` points at exactly `9144353326ef3e0a033b53524902c27cae4d4fb6`,
  which was the tip of `origin/main` when the tag was pushed.

**Published assets** (`noamvb/cannsheet-mobile-releases`, 2026-09-02T22:52:21Z)

- `Cannsheet-Mobile-1.10.0.apk` (38,003,765 bytes)
- `Cannsheet-Mobile-1.10.0.apk.sha256`
- APK SHA-256 `dac9d2b6dc5283f850d6a03e94ae613039e4f00227d4cf13538383a69d002497`,
  recomputed from the downloaded asset and matching the published checksum file.
- `aapt2 dump badging` reports package `com.noamv.cannsheet.mobile`, versionCode 56,
  versionName 1.10.0.
- Signing certificate SHA-256
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, **identical to
  v1.9.1**, so the phone updates in place.

### Backend

The client half needs a backend that publishes two fields, and that backend is live.

- Production Apps Script **version 15** on the unchanged deployment
  `AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`;
  the `/exec` URL never changed.
- Verified against the live endpoint: `taxRate: 0.13`, 373 products, every one
  carrying `postTax` where the sheet records it.
- Version 14 was published first and was **wrong**: it used `truthy_`, which never
  returns null, so a blank `Post-tax` cell became `false` and the client's
  unknown-basis warning could never fire. Version 15 uses `strictPostTax_` - the
  same helper `analyticsCost_` uses to refuse a final cost rather than guess - and
  omits the field unless the sheet records it.
- **The live sheet has no blank `Post-tax` cells**, so versions 14 and 15 return
  identical data for it (106 true, 267 false, 0 absent). The live feed therefore
  cannot distinguish them. What proves version 15's behaviour is the backend test
  seeding an explicit blank row, which is mutation-verified: reverting to `truthy_`
  reds it. The unknown-basis warning is correspondingly dormant for catalog products
  on this data, and active for saved defaults stored before this release.

### Still outstanding

- **The signing certificate is `CN=Android Debug`** and has been for every release,
  including 1.9.1. Updates work and nothing is broken, but this is a weaker signing
  story than a dedicated release key. Changing it would break in-place updates and
  force a reinstall, so it is a deliberate decision to make rather than drift into.
- The four July backend documents were **stale rather than wrong**, and an earlier
  revision of this file said "wrong", which overstated it.
  `BACKEND_SYNC_PERFORMANCE_REPORT.md` and `BACKEND_ANALYTICS_ROLLBACK.md` were
  committed on 2026-07-17 and 2026-07-18, when versions 8 and 9 were in fact live;
  their present-tense status lines simply stopped being true as production moved
  on. All four now carry a dated historical-record banner pointing at this file and
  `docs/PROJECT_STATE.md`, and the two rollback documents warn that following their
  version targets today would discard every backend change since July - the sync
  one names version 7, which would undo the tax-basis fields v1.10.0 depends on.
- Still genuinely open: the analytics caching and batch-fetch work of `0462e38`
  (14 Aug, #83) has **never served production**. It is a separate thing from the
  July "backend sync performance" of version 8, which is what the similarly named
  document describes. A deployment titled "Backend sync performance and recoverable
  atomic apply" sits in the project's Archived list. Whether `0462e38` should ever
  be deployed is undecided.
- On-device confirmation of 1.10.0 by the owner.

## Cannsheet Mobile v1.9.1 (code 55) - pen widget step repaired, label case corrected

**Status: v1.9.1 published and independently verified on 2026-08-31.** It corrects a
label-case defect the owner found on their phone immediately after installing v1.9.0.

### The v1.9.0 follow-up defect

v1.9.0 drew the new step labels as `+10S` / `-10S`, which reads as `+105`. The strings are
lowercase (`+%1$ds`), but `Button` styles default to `textAllCaps`, applied as a
`TransformationMethod` at draw time, so the lowercase `s` never reached the screen. Fixed by
setting `android:textAllCaps="false"` on the five labelled buttons in
`widget_pen_consumption.xml`. The three preset buttons had rendered `10S` / `20S` / `30S`
since #109 and are corrected in the same change rather than left inconsistent.

The renderer test did not catch it, and the reason is worth keeping: it asserted
`plus.text.toString()`, which returns the **stored** string, while `textAllCaps` transforms
the text only at draw time. The assertion was green while the screen showed something else -
the same "check the announcement, not the behaviour" mistake that let the original step
defect ship, one level down. `PenWidgetRendererTest` now asserts through
`transformationMethod.getTransformation(...)`, so it fails against the un-fixed layout.

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

**v1.9.1 (code 55) - current release**

- Pull request merged: `noamvb/cannsheet-mobile#171`, squash merged
- Squashed commit on `main`: `d59925899880333eea133efc53993fde4f029d2b`
- Proving `main` run: `33342371169`, `event=push`, `conclusion=success`, all six required
  jobs green on that exact SHA. The first attempt failed in `Emulator API 36` on
  `PurchaseContentTest.changingTypeClearsFieldsButReselectingTypePreservesThem` with
  `Failed to inject touch input`, alongside `Failed to start Emulator console for 5554` and
  repeated `adb` exit-code-1 lines - the documented emulator flakiness, unrelated to the
  change, which touched only a widget layout and a widget renderer test. Re-running the
  failed jobs on the same commit passed.
- Annotated tag `v1.9.1` points at exactly that commit
- Published at 2026-08-31T00:44:16Z: `Cannsheet-Mobile-1.9.1.apk` (38,003,769 bytes) and
  `Cannsheet-Mobile-1.9.1.apk.sha256`
- APK SHA-256: `71795b64d0fb3caa9e0fa920c1997c76a5b1e3d3792984678324318c88a50ed8`,
  re-downloaded and checked with `shasum -a 256 -c` after publication: OK. `aapt dump
  badging` reports package `com.noamv.cannsheet.mobile`, versionCode 55, versionName 1.9.1,
  compileSdk 36.
- Signing certificate: `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`
  (`CN=Android Debug`), unchanged from v1.9.0 and v1.8.1. `apksigner verify` reports
  `Verifies` with APK Signature Scheme v2. The phone updates in place; no Room data or
  queued offline row is at risk.

**v1.9.0 (code 54) - superseded after a few hours**

- Squashed commit `7e9eb58f4dcb9687d8594deb324be7ae4926b97f` via `#169`, proving run
  `33315508424`, published 2026-08-30T14:10:40Z, APK SHA-256
  `cdb44ae9fd126e0b987b62f0f8bba6a1a5d87cac9e56ff6147321ec0f598eb6d`, same signing
  certificate. Provenance recorded in `#170`. Superseded by v1.9.1 because its new step
  labels drew as `+10S`.

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
- On-device confirmation of the shipped v1.9.1 build has **not** happened, for either the
  stepping fix or the label case, and cannot until the owner installs 1.9.1 through
  Obtainium. The pre-fix behaviour of both was confirmed on hardware - the step defect over
  ADB, the `+10S` label by the owner reading their own home screen - but neither fix is
  proven anywhere except CI.
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

v1.9.1 is published. Open Obtainium, pull to refresh or tap **Check for updates**, and
install Cannsheet Mobile 1.9.1.

Two things worth a look after installing: tap `+` once on the small pen widget and confirm
the counter moves to 10 rather than 30, and confirm the buttons now read `+10s` / `-10s`
rather than `+10S`. On-device confirmation of either has not been performed here - the
pre-fix behaviour was reproduced on hardware, but both fixes are so far proven only by CI,
because installing a debug build over the release build would force an uninstall and
destroy the Room database.
