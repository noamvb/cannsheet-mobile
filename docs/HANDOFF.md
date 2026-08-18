# Latest handoff

Last updated: 2026-08-18

Repository: public `noamvb/cannsheet-mobile`

## Current release: v1.4.0

`v1.4.0` (`versionCode 36`, `versionName 1.4.0`) adds an optional written summary above the
Insights statistics, produced on device by the model hosted in `noamvb/local-llm` from
analytics this app already holds. Nothing leaves the phone.

### Shipped changes

1. **Locally generated Insights summary** (PR #94, squashed `3a32595`)
   - `domain/CannsheetLlmFacts.kt` maps an `InsightsResponseDto` to pre-computed `Fact`
     values. The model receives facts, never rows.
   - `shouldSummarise` is deliberately stricter than the screen: it suppresses on a cached,
     stale, range-changing, refreshing, errored or absent snapshot, and whenever local
     actions are queued. A `null` pending-action count suppresses too — the screen masks
     that with `?: 0`, which is right for a banner and wrong for prose.
   - **No runway or spend projection is ever transmitted**, per the `AGENTS.md` rule. A test
     fails if any fact label contains runway, project, forecast, estimate, per day, will
     last, or remaining.
   - Dates come only from `response.range`; the mapper never reads a device clock.
   - `kotlinx-serialization` added so the contract file is copied verbatim from
     `noamvb/local-llm` rather than reimplemented against Moshi.
2. **Version bump and ADR-025** (PR #95, squashed `d2638a1`)

### Release provenance

- Merged pull requests: **#94** (`3a32595`), **#95** (`d2638a1`).
- Tagged commit **`d2638a12c65b5f6f80ae5a71615a8c0e767c1ceb`**, the tip of `main`.
- Proven by push-to-main run **`32173460500`**, event `push`, conclusion `success`, with all
  six required jobs individually `success`: Classify changes and scan repository, Backend
  validation, Android static validation, Emulator API 24, Emulator API 36, Cannsheet
  Android PR validation. Verified job-by-job rather than from the run summary, because two
  main runs were in flight from back-to-back merges and a cancelled run is not a success.
- Published assets: `Cannsheet-Mobile-1.4.0.apk` (13,898,694 bytes) and its `.sha256`.
- Published APK SHA-256: `255dcea1cef8490f683e086513c26025b920904a9ee64e5308208de0b6037db6`, re-downloaded and verified after publication.
- Signing certificate SHA-256:
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, **identical to
  v1.3.4**, so Obtainium updates in place and the Room database and pending offline queue
  are not at risk.
- Package `com.noamv.cannsheet.mobile`, `versionCode 36`, minSdk 24.

## Not verified

- **The summary card has never been observed rendering on a device.** Doing so needs a live
  Apps Script analytics response together with a release-signed build, because
  `shouldSummarise` deliberately refuses the cached snapshot a debug build most easily
  produces. The feature renders nothing when its preconditions are unmet, so the failure
  mode is absence rather than error — but "shipped" here means "shipped, unwitnessed".
- The equivalent feature in `noamvb/poop-schedule` **was** verified end to end on a Galaxy
  Z Fold 7 against real records, and uses the same IPC and the same contract. What is
  unproven is specifically this app's mapper and suppression gate against live data.
- No Apps Script deployment or live spreadsheet change was made in this cycle.

## Companion app

Requires `noamvb/local-llm` v0.1.1 or later. **LocalLLM must be installed first**, because
Android grants a signature-level permission only if the app defining it is already present.
This app's certificate digest is listed in that app's `known_signers.xml`; the two apps do
not share a signing key, which is why the permission is `signature|knownSigner`.
