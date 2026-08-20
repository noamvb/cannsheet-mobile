# Latest handoff

Last updated: 2026-08-20

Repository: public `noamvb/cannsheet-mobile`

## Current widget expansion: A4 in progress

PR #109 (`feat: surface quantity presets on the pen widget`) is squash-merged into
`main` as `847b184`; no version metadata or published APK changed. Its full-widget
presets remain uses-only, use the guarded DataStore draft setter, omit fractional
second conversions rather than flooring them, and hide below the `200dp` regular
layout floor.

A2 (`feat: scale the pen widget step size with rendered size`) is squash-merged as
PR #110 / `e00b3bb`. Large rendered widgets carry a 30-second increment/decrement
step through the existing broadcast intent extras; compact and base-width widgets
carry the default 10-second step. The router clamps the extra, and the renderer
formats the matching accessibility descriptions. The Room schema, offline queue,
wire payloads, backend contract, endpoint, package ID, and version metadata are
unchanged.

PR #111 (`feat: add a pen widget configuration activity`) is squash-merged as
`9749f44`. It adds optional per-instance DataStore configuration for a pinned
selectable pen, discreet presentation, and a 5/10/30 second step override.
Unconfigured or restored widgets read defaults and continue to follow
`preferences.loadedPenProductId`; deleted widget IDs clear all three
configuration keys. The Compose activity routes saving through
`PenWidgetRuntime.withSerialized { PenWidgetUpdater.update(...) }`.

The configuration activity is explicitly exported because the launcher is a
separate Android UID and otherwise cannot invoke the `APPWIDGET_CONFIGURE` flow;
the attached plan's `exported="false"` value was observed to make the launcher
settings control a no-op on the API-36 emulator. This is the only intentional
manifest deviation from the plan and is required for the requested feature to
function.

Validation is emulator-only and green so far: A1/A2's exact local Gradle gate and
focused API-36 renderer/router tests passed, and A3's exact local Gradle gate plus
focused `PenWidgetConfigStateTest` instrumentation passed 4/4. Manual API-36
evidence observed the old v1.4.4 widget surviving `adb install -r`, a readable light
configuration screen, discreet-mode output (`Pen cart` / `Tap to log`), and
30-second accessibility actions. The remove/re-add gesture remains unverified from
A1 because the AVD launcher opened the app drawer; no physical phone or production
data was used.

A3's post-review focused router instrumentation passed 8/8 after adding the
per-widget pinned-product assertion. Its refreshed remote PR gate was run
`32350773583`, with all required jobs green. A4 is now on
`agent/widget-sync-indicator`; its JVM unit-test task passed, while the exact
Android gate, screenshots, review, and merge are still pending.

A4's sync indicator is presentation-only. It reads the existing aggregate pending
action count and `queueNonEmptySinceEpochMillis`, shows `%1$s · sync is behind`
after the existing 24-hour threshold, and preserves the precedence
queued → sync-behind → discreet → existing status/counts. It adds no queue fields,
notification behavior, Room schema, or wire payload changes. Both provider-info
XML files request the platform's minimum 30-minute periodic update, independent of
queue-alert opt-in, so an otherwise idle widget will recompute after crossing the
threshold.

## Current release: v1.4.4

`v1.4.4` (`versionCode 40`, `versionName 1.4.4`) is a patch release with two follow-up
refinements to the LocalLLM prewarm work.

### Shipped changes

1. **Unbind cleanup on failed binds** (PR #106, squashed `a8125a5`)
   - `LocalLlmClient.warmup()` now always calls `unbindService` on close, including when
     `bindService` returned `false`. Previously a failed bind left `close()` a no-op,
     leaking the registered `ServiceConnection` on every Insights open where LocalLLM is
     absent or refuses the bind.
2. **Insights warmup gated on data availability** (PR #106, squashed `a8125a5`)
   - `rememberNarrativeState()` now keys its warmup `DisposableEffect` on
     `CannsheetLlmFacts.shouldSummarise(state, pendingActionCount)`, so the binding only
     opens when a summary generation is actually plausible. Previously it fired
     unconditionally, so a screen with too little data to summarise still paid the cost
     of loading the 2 GB model.
3. **Version bump to 1.4.4 (versionCode 40)**
   - `versionCode` 39 → 40, `versionName` 1.4.3 → 1.4.4.

### Release provenance

- Merged pull requests: **#106** (`a8125a5`), **#107** (`ee1e6ab`).
- Tagged commit **`ee1e6ab075ce056fff84cf226df6a51baaa21689`**, the tip of `main`.
- Proven by push-to-main run **`32303352218`**, event `push`, conclusion `success`, with all
  six required jobs individually `success`: `Classify changes and scan repository`,
  `Backend validation`, `Android static validation`, `Emulator API 24`, `Emulator API 36`,
  `Cannsheet Android PR validation`.
- `v1.4.4` is an **annotated** tag pointing at exactly that commit.
- Release workflow **`32327408818`**: all three jobs (`Confirm tested main commit`,
  `Verify and build signed APK`, `Publish verified Cannsheet APK`) succeeded.
- Published assets on `noamvb/cannsheet-mobile-releases`: `Cannsheet-Mobile-1.4.4.apk`
  (13,898,698 bytes) and `Cannsheet-Mobile-1.4.4.apk.sha256`.
- Published APK SHA-256:
  `c8b8365ebbe7d38c0e587e0447d3e01e65731b0905c28806197317ec50a8e14a`, independently
  downloaded and checked with `shasum -a 256 -c` against the published asset.
- Signing certificate SHA-256:
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, extracted directly
  from the APK's v2 signing block (parsed by hand — no `apksigner` available in this
  session's environment) and **identical to v1.4.1 through v1.4.3**, so Obtainium updates
  in place.
- Package, `versionCode`, and `versionName` were asserted by the release workflow's own
  `Verify signed APK` step, not independently re-extracted here (no `aapt`/`apksigner` in
  this session's environment).

## v1.4.3

`v1.4.3` (`versionCode 39`, `versionName 1.4.3`) holds a LocalLLM warmup binding open while
the Insights overview screen is active. This keeps LocalLLM resident and prevents the inference
service from being terminated with a freshly loaded model in memory during the gap between the
status check and generation.

### Shipped changes

1. **Hold LocalLLM warmup binding across Insights screen lifecycle** (PR #103, squashed `27d3a56`)
   - Adopted the `LocalLlmClient.warmup(): AutoCloseable` binding handle from LocalLLM client.
   - `rememberNarrativeState()` holds the warmup binding via `DisposableEffect` for the duration
     the Insights Overview screen is mounted, and safely releases the binding on disposal.

2. **Version bump to 1.4.3 (versionCode 39)** (PR #104, squashed `1dc4cca`)
   - `versionCode` 38 → 39, `versionName` 1.4.2 → 1.4.3.

### Release provenance

- Merged pull requests: **#103** (`27d3a56`), **#104** (`1dc4cca`).
- Tagged commit **`1dc4cca1bbe43e9dd747e5b9175db731d70fe5c9`**, the tip of `main`.
- Proven by push-to-main run **`32292855160`**, event `push`, conclusion `success`, with all
  six required jobs individually `success`: `Classify changes and scan repository`,
  `Backend validation`, `Android static validation`, `Emulator API 24`, `Emulator API 36`,
  `Cannsheet Android PR validation`.
- `v1.4.3` is an **annotated** tag pointing at exactly that commit.
- Release workflow **`32293494930`**: all three jobs (`Confirm tested main commit`,
  `Verify and build signed APK`, `Publish verified Cannsheet APK`) succeeded.
- Published assets on `noamvb/cannsheet-mobile-releases`: `Cannsheet-Mobile-1.4.3.apk`
  (13,898,698 bytes) and `Cannsheet-Mobile-1.4.3.apk.sha256`.
- Published APK SHA-256:
  `f8cef5d38a38396fd6e527426526b7d75d66d729bc6ed5eaacaca4dd5b416f90`, independently
  downloaded and checked with `shasum -a 256 -c` against the published asset.
- Signing certificate SHA-256:
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, extracted from the
  APK's v2 signing block and **identical to v1.4.2 and v1.4.1**, so Obtainium updates in place.
- Package `com.noamv.cannsheet.mobile`, `versionCode 39`, `versionName 1.4.3`, minSdk 24.

## v1.4.2

`v1.4.2` (`versionCode 38`, `versionName 1.4.2`) stops the Insights summary regenerating when
it is scrolled out of view and back. A patch bump: no behaviour change beyond the card no
longer redoing work it had already done.

### Shipped changes

1. **The summary no longer regenerates on scroll** (PR #100, squashed `e557894`)
   - The card sits in a `LazyColumn` item, and `LazyColumn` disposes an off-screen item's
     entire composition once it scrolls far enough away — discarding the `produceState`
     that was driving generation. Scrolling back built a fresh composition and started
     again from nothing: a visible regeneration, plus another binder request and another
     on-device model run every round trip.
   - `rememberNarrativeState()` is now called once above the `LazyColumn` and the resolved
     state passed down; the card only renders. See `docs/DECISIONS.md` ADR-027.

2. **Version bump** (PR #101, squashed `152d141`)
   - `versionCode` 37 → 38, `versionName` 1.4.1 → 1.4.2.

### Release provenance

- Merged pull requests: **#100** (`e557894`), **#101** (`152d141`).
- Tagged commit **`152d141b9ff27b523f4473ce8edb49b301014f3e`**, the tip of `main`.
- Proven by push-to-main run **`32278056196`**, event `push`, conclusion `success`, with all
  six required jobs individually `success`: `Classify changes and scan repository`,
  `Backend validation`, `Android static validation`, `Emulator API 24`, `Emulator API 36`,
  `Cannsheet Android PR validation`.
- `v1.4.2` is an **annotated** tag pointing at exactly that commit, restoring the convention
  the ship-release skill specifies and that v1.4.1 deviated from (v1.4.1's tag is
  lightweight; it is published and must not be re-tagged).
- Release workflow **`32278927941`**: all three jobs succeeded.
- Published assets on `noamvb/cannsheet-mobile-releases`: `Cannsheet-Mobile-1.4.2.apk`
  (13,898,698 bytes) and `Cannsheet-Mobile-1.4.2.apk.sha256`.
- Published APK SHA-256:
  `b515500fb225c4249df44ec72f45c3671408ff08dfc72622bd37b1539b4d2236`, independently
  downloaded and checked with `shasum -a 256 -c` against the published asset.
- Signing certificate SHA-256:
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, extracted from the
  APK's v2 signing block and **identical to v1.4.1**, so Obtainium updates in place. The
  debug-style DN is deliberate and documented in ADR-024.
- Package `com.noamv.cannsheet.mobile`, `versionCode 38`, `versionName 1.4.2`, minSdk 24.

## v1.4.1

`v1.4.1` (`versionCode 37`, `versionName 1.4.1`) gives the Insights narrative card a loading
state: an indeterminate progress bar and "Writing a summary on this phone…" from the moment
generation is committed to until the first token arrives. A patch bump — it makes the v1.4.0
feature visible while it works, without changing what the feature does.

### Shipped changes

1. **Loading indicator while the Insights summary generates** (PR #97, squashed `21a9fd8`)
   - `NarrativeState` gains a `Loading` case, set only after every pre-flight gate has passed
     and immediately before the call to `client.generate()`, so a phone with no model
     installed still sees nothing rather than a spinner that would never resolve.
   - Three failure modes were closed off at the same time, because a card that can appear is a
     card that can get stuck: `terminalState()` makes the end of a generation total (a flow
     completing having emitted nothing can no longer leave the progress bar on screen
     indefinitely); the collection is bounded by a timeout (a wedged service emits no
     fragment, no completion, and no error at all); and `UNSUPPORTED` is now an explicit gate,
     since it reports its model as downloaded on purpose and would otherwise draw a spinner
     only to have the request refused a moment later.
   - A blank first fragment keeps the loading body rather than collapsing the card, since
     models routinely open with a newline.
   - `docs/DECISIONS.md` gains ADR-026, recording the same three failure modes and how each
     was closed before this shipped. It also corrects ADR-025's "not verified" paragraph: the
     card **has** now been observed rendering on a device (Galaxy Z Fold 7, against a live
     account, 2026-08-19), with every figure matching the statistics below it. That correction
     ships with this release, not with the loading-indicator code itself.
2. **Version bump** (PR #98, squashed `1fc3959`)
   - `docs/PROJECT_STATE.md` refreshed; it was two releases stale.

### Release provenance

- Merged pull requests: **#97** (`21a9fd8`), **#98** (`1fc3959`).
- Tagged commit **`1fc3959345eae3140b77ee5c705fac3253fc9ec7`**, the tip of `main`.
- Proven by push-to-main run **`32214781173`**, event `push`, conclusion `success`, with all
  six required jobs individually `success` on the first attempt (no re-run needed): Classify
  changes and scan repository, Backend validation, Android static validation, Emulator API 24,
  Emulator API 36, Cannsheet Android PR validation.
- Release workflow **`32244654049`** on `.github/workflows/release-apk.yml`: all three jobs
  (`Confirm tested main commit`, `Verify and build signed APK`,
  `Publish verified Cannsheet APK`) succeeded.
- Published assets: `Cannsheet-Mobile-1.4.1.apk` (13,898,698 bytes) and its `.sha256`.
- Published APK SHA-256: `9974b604d7834cc236345ad0235385aa2c896a59f907a81e66b1c2ecc2a0e8aa`,
  independently downloaded and verified against both the published `.sha256` asset and
  GitHub's own recorded asset digest — not copied from workflow logs.
- Signing certificate SHA-256:
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, extracted directly from
  the APK's v2 signing block and confirmed **byte-identical to v1.4.0**, so Obtainium updates
  in place and the Room database and pending offline queue are not at risk.
- Package `com.noamv.cannsheet.mobile`, `versionCode 37`, `versionName 1.4.1`, minSdk 24.

## Not verified

- The loading indicator itself (the actual progress bar and "Writing a summary on this
  phone…" text, and the transition into and out of it) has not been observed rendering on a
  device against a live analytics response with this release build. ADR-025's correction
  confirms the underlying **summary card** renders correctly against real data; it predates
  the loading-state addition and does not cover it.
- The v1.4.2 scroll fix has not been observed on a device either. The defect it corrects
  *was* reported from the phone by the owner, so the bug is confirmed on hardware even
  though the fix is verified only by unit tests and CI.
- **v1.4.3's warmup-binding adoption and v1.4.4's follow-up fixes are unverified on device.**
  Holding the `warmup()` binding across the Insights screen lifecycle (v1.4.3), always
  unbinding on close (v1.4.4), and gating the warmup on `shouldSummarise` (v1.4.4) are all
  timing/lifecycle changes with no visible UI difference — verified only by unit tests, CI,
  and (for v1.4.4) an independent re-download and signature check of the published
  artefact. None has been observed running against LocalLLM on a physical phone.
- No Apps Script deployment or live spreadsheet change was made in this cycle.

## Companion app

Requires `noamvb/local-llm` v0.1.1 or later. **LocalLLM must be installed first**, because
Android grants a signature-level permission only if the app defining it is already present.
This app's certificate digest is listed in that app's `known_signers.xml`; the two apps do
not share a signing key, which is why the permission is `signature|knownSigner`.
