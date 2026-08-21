# Current handoff

Last updated: 2026-08-20

Repository: public `noamvb/cannsheet-mobile`

## Current release

Cannsheet Mobile `v1.5.1` (`versionCode 43`, `versionName 1.5.1`) is
published for Obtainium in
[`noamvb/cannsheet-mobile-releases`](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.5.1).
The release contains exactly:

- `Cannsheet-Mobile-1.5.1.apk`
- `Cannsheet-Mobile-1.5.1.apk.sha256`

The annotated source tag `v1.5.1` peels to
`96807f048297dd553beb653a06c5736928e2927f`, the version-only PR #136 squash
commit and the exact validated `main` tip at publication time. The later
docs-only provenance commit is deliberately not the tag target.

The phone owner should open Obtainium, pull to refresh or tap **Check for
updates**, and install Cannsheet Mobile 1.5.1. No phone installation, app
launch, widget action, production-data read, or production backend write was
performed during this release.

## What v1.5.1 ships

The runtime changes after v1.5.0 are:

- PR #128 / `7372c6f`: make the pen-widget configuration component resolvable
  from launcher configuration flows.
- PR #129 / `5ccbd26`: exclude pending widget commits from backup and device
  transfer so a restored device cannot replay another device's deferred entry.
- PR #130 / `55ad366`: render every projection value together with its source
  snapshot as-of date.
- PR #131 / `b48a5a8`: refresh the Today widget at local-day rollover through
  a self-rearming WorkManager job.
- PR #132 / `78a6913`: move remaining widget copy into Android string
  resources.
- PR #133 / `d0b895b`: route commit-driven refreshes through the shared widget
  surface router.
- PR #135 / `61fec5e`: stop treating response-wide
  `dataQuality.complete=false` as a universal projection-widget veto.

PR #134 / `423a62e` checks in the widget expansion and remediation plans and
does not change runtime behavior. PR #136 / `96807f0` changes only
`versionCode 42 -> 43` and `versionName 1.5.0 -> 1.5.1`. The source tree
also contains the v1.5.0 publication record from PR #127 / `b22150c`.

## Projection-widget correction

The reported `Projection unavailable` / `The cached snapshot is incomplete.`
state came from an aggregate gate in `buildProjectionUiModel`. The backend
marks `dataQuality.complete` false for any warning, including warnings
unrelated to the selected Runway or Spend mode, so the gate could suppress
both widgets before their own builders evaluated usable inputs.

PR #135 removes only that aggregate veto and its obsolete suppression copy.
A snapshot must still be successful and structurally usable. Each ready figure
must still include the snapshot's own as-of date, and the existing mode-specific
builders still reject unsafe inputs; for example, unknown personal cost still
suppresses an unsafe Spend figure. The change adds no projection persistence,
refresh loop, queue mutation, network payload, backend write, or synthetic
fallback.

## Validation and publication provenance

Local feature validation after rebasing onto then-current `main` passed with
JDK 17.0.20, Gradle 9.3.1, Android Platform 36.1, and Build Tools 36.0.0:

- `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
  reported `BUILD SUCCESSFUL` in 8m40s.
- The JVM XML reports contained 484 tests, zero failures, zero errors, and zero
  skips; lint contained zero errors.
- All eight checked-in Node backend suites passed with Node 22.14.0.
- `python3 -m unittest tests/test_backend_sync_benchmark.py` passed all 13
  tests.
- `git diff --check` passed.

The same complete local gate on the isolated version branch reported
`BUILD SUCCESSFUL` in 19m25s with the same 484/0/0/0 JVM result and zero lint
errors. Independent `aapt` readback of that generated debug APK reported
package `com.noamv.cannsheet.mobile`, version code `43`, version name
`1.5.1`, min SDK `24`, and target SDK `36`. All eight Node suites and all
13 Python tests also passed on the version branch.

GitHub provenance:

- Feature PR #135 head `f5811d4`: PR run `32441202819` passed its required
  jobs; squash commit `61fec5e`.
- Exact feature `main` run `32441499321` passed all six named jobs,
  including Emulator API 24 and Emulator API 36.
- Version PR #136 head `6f90526`: PR run `32443424342` passed its required
  jobs; squash commit `96807f0`.
- Exact tagged-`main` run `32443710327` was a completed successful
  `push` run on `main` for `96807f0`; Classify, Backend, Android static,
  Emulator API 24, Emulator API 36, and the aggregate validation job all
  succeeded.
- Annotated tag `v1.5.1` points to that exact validated commit.
- Publication run `32444205628` passed exact-main confirmation, metadata and
  monotonicity checks, unit tests and lint, signed build, signature verification,
  public release creation, and post-publication verification.

The successful Android jobs emitted the known non-fatal KSP/IntelliJ
`ApplicationManager.getApplication()` null-service annotation and GitHub
action-runtime deprecation notices. They did not fail any Gradle, lint,
instrumentation, signing, or publication job.

## Independent public-artifact verification

The published APK is 14,024,579 bytes. Its independently calculated SHA-256 is:

`74d362ffd5b40eda8a89f09257db7f83251a8f4c9c0f7d994bec4b76948ea56f`

That digest matches both the 93-byte published checksum file and GitHub's asset
digest; `shasum -a 256 -c` reported `OK`. Independent Build Tools 36.0.0
readback confirmed:

- package `com.noamv.cannsheet.mobile`
- version code `43`; version name `1.5.1`
- min SDK `24`; target SDK `36`
- successful zip-alignment verification
- one signer and APK Signature Scheme v2 true
- certificate SHA-256
  `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`
- public-key SHA-256
  `2de7a08db8c185ec727b77a3f1f7afd3b159c03f8efc6eb2d20c51d3a7043e7c`

Both digests exactly match an independently downloaded public v1.5.0 APK, which
proves signer continuity for an in-place update. The human-readable certificate
subject is `C=US, O=Android, CN=Android Debug`; it was not used as continuity
proof.

## Data and device boundaries

v1.5.1 introduces no Room migration, destructive fallback, queue deletion or
acknowledgement change, event/request ID change, wire-contract change, Apps
Script change, endpoint change, package/application-ID change, or signing
configuration change. Projections remain presentation-only and are neither
persisted nor transmitted.

No physical-widget walkthrough is claimed. Earlier read-only ADB discovery
found no attached or advertised phone and the ADB server was stopped. The
current release procedure expressly leaves installation to Obtainium; do not
sideload the local debug APK or uninstall the production app, because uninstall
would delete the Room database and any pending offline queue rows.

After the owner installs 1.5.1, the remaining useful manual check is to refresh
Insights, then inspect both Runway and Spend widget instances. An unavailable
state can still be correct when there is no cached snapshot, the response is
structurally unusable, or the selected mode's own required inputs do not yield a
safe figure. The removed response-wide incomplete-snapshot message should no
longer suppress otherwise-usable figures.

## Canonical references

- `docs/PROJECT_STATE.md`: verified implementation and release state.
- `docs/ARCHITECTURE.md`: system boundaries and data flows.
- `docs/DECISIONS.md`: durable design and safety decisions.
- `docs/WIDGET_EXPANSION_PLAN.md`: accepted widget expansion plan.
- `docs/WIDGET_FIX_PLAN.md`: checked-in remediation plan.
