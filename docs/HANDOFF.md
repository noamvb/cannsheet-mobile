# Latest handoff

Last updated: 2026-08-19

Repository: public `noamvb/cannsheet-mobile`

## Current release: v1.4.1

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
  this release's loading-state addition and does not cover it.
- No Apps Script deployment or live spreadsheet change was made in this cycle.

## Companion app

Requires `noamvb/local-llm` v0.1.1 or later. **LocalLLM must be installed first**, because
Android grants a signature-level permission only if the app defining it is already present.
This app's certificate digest is listed in that app's `known_signers.xml`; the two apps do
not share a signing key, which is why the permission is `signature|knownSigner`.
