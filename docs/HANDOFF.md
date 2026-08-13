# Latest handoff

Last updated: 2026-08-13

## Current outcome

Cannsheet Mobile v1.3.0 is implemented, documented, released, published, and
installed on the intended production Samsung Fold. The accepted sequence was
delivered through source PRs
[#64](https://github.com/noamvb/cannsheet-mobile/pull/64) through
[#71](https://github.com/noamvb/cannsheet-mobile/pull/71), documentation
[#72](https://github.com/noamvb/cannsheet-mobile/pull/72), and the deliberately
separate version-only
[#73](https://github.com/noamvb/cannsheet-mobile/pull/73).

Annotated tag `v1.3.0` points to exact validated `main` commit
`a733a9d2741c4c6eaaa074461a354ffa6fb9751e`. The tag remains on that release
commit; this later handoff update must not move it.

The public signed release is
[Cannsheet Mobile 1.3.0](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.3.0),
with authored assets `Cannsheet-Mobile-1.3.0.apk` and
`Cannsheet-Mobile-1.3.0.apk.sha256`. An independent download matched SHA-256:

`d3d2076341afb489ddd59e479ff3cb60d8b0e7af484b9e7b829ae054ed5bf2c7`

Local `aapt` inspection reported package `com.noamv.cannsheet.mobile`, version
code `31`, version name `1.3.0`, minimum SDK 24, target SDK 36, and launcher
`com.example.MainActivity`. Local `apksigner verify` confirmed one APK
Signature Scheme v2 signer. The subject text is unexpectedly debug-like, so
continuity was established from the actual certificate and public-key digests:
both matched the public v1.2.27 APK and the pre-update APK pulled from the
production phone. The installed v1.2.27 APK was byte-identical to the public
v1.2.27 asset, and the installed v1.3.0 APK was byte-identical to the new public
asset.

The implementation does not change the production Apps Script, spreadsheet,
endpoint, application ID, namespace, Room schema, existing queue payloads, or
wire contracts. The release-only source change was the requested version
metadata bump in `app/build.gradle.kts`.

## v1.3 implementation

- Backup and device-transfer behavior is deliberate: only user settings
  (`consumption_preferences`, `purchase_defaults`) remain eligible. The Room
  database `cannsheet_db`, `sync_preferences`, and `pen_widget_state` are
  excluded from both cloud backup and device transfer, because restoring them
  would reintroduce queue/request identity, a point-in-time analytics snapshot,
  queue-alert episode state, or an in-flight deferred widget payload. A phone
  lost while holding unsynced queue rows loses them; that trade is recorded in
  ADR-017 and `app/src/main/res/xml/backup_rules.xml`.
- Queue-integrity alerts are off by default, subordinate to background sync,
  aggregate-only, and advisory. They cover current-episode terminal integrity
  states and a continuously non-empty queue at least 24 hours old. Alert paths
  cannot acknowledge, write, or delete queue rows, and an unconstrained delayed
  check allows the 24-hour clock to mature while network-constrained sync work
  is blocked.
- Inventory runway and current-month spend pace are presentation-only estimates
  derived from the existing versioned Insights response. They use the response
  time zone, basis-specific finished-product evidence, product-specific burn
  windows, strict month eligibility, exact Product IDs, and suppression for
  cached, stale, changing, ambiguous, or locally incomplete snapshots.
- Navigation classifies root width once: compact below 600dp, medium from 600dp
  through 839dp, and expanded from 840dp. Compact uses the bottom bar;
  medium/expanded use a rail; expanded Insights and History use shared-detail
  40/60 panes. This is responsive large-screen behavior, not hinge-aware
  placement.
- History refresh and correction safety remain parent-owned across sheet and
  pane rendering. Correction drafts/dialog state are saveable, and stale
  responses cannot clear a newer analytics or History invalidation.

## Validation and provenance

- Each source merge listed in `docs/PROJECT_STATE.md` passed its exact-main
  classification/security, backend, Android static, API 24, API 36, and
  aggregate workflow. Documentation PR #72 exact-main
  [run 31731518220](https://github.com/noamvb/cannsheet-mobile/actions/runs/31731518220)
  passed the same six jobs.
- The version-only local gate used JDK 17, Android platform 36.1, and Build
  Tools 36.0.0:
  `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
  It completed with `BUILD SUCCESSFUL` in 6m30s; 333 JVM tests passed with zero
  failures, errors, or skips, Android-test Kotlin compiled, lint completed, and
  the debug APK assembled. `git diff --check` passed. The complete branch diff
  was one file with exactly two additions and two deletions.
- Version PR #73 gate
  [run 31733035509](https://github.com/noamvb/cannsheet-mobile/actions/runs/31733035509)
  passed. Exact versioned-main
  [run 31733624463](https://github.com/noamvb/cannsheet-mobile/actions/runs/31733624463)
  passed all six jobs in 6m59s, including API 24 and API 36.
- Signed publication
  [run 31734329091](https://github.com/noamvb/cannsheet-mobile/actions/runs/31734329091)
  passed exact-SHA proof, version and monotonicity checks, unit tests/lint,
  protected signing, release assembly, signature and metadata verification,
  checksum creation, public publication, and post-publication verification in
  5m53s.
- Independent public verification checked the authored asset names, checksum,
  package/version/SDK/launcher metadata, v2 signature, signer continuity, and
  previous-release continuity. These checks are separate from the release
  workflow's own post-publication verification.

## Phone state and safety boundary

- Before installation, production package readback reported version code `30`,
  version name `1.2.27`, APK signing version 2, and the existing launcher.
  The installed base APK matched the public v1.2.27 APK byte for byte.
- The independently verified public v1.3.0 APK was installed in place with
  `adb install -r`; the command returned `Success`. No uninstall, data clear,
  downgrade, or debug-signed replacement was used.
- After installation, readback reported version code `31`, version name
  `1.3.0`, APK signing version 2, the same signing keyset, the same data
  directory and data inode, and the same first-install time. Only the expected
  package update time and APK path changed.
- The launcher still resolved to `com.example.MainActivity` without launching
  it. The APK pulled back after installation matched the public v1.3.0 APK byte
  for byte and matched its published checksum.
- The app was force-stopped after verification. Wireless ADB was disconnected,
  the final device list was empty, and the phone was explicitly returned to
  normal use.
- No app screen was launched; no notification permission or channel was
  touched; no widget was added or tapped; and no Log, Purchase, Finish,
  correction, queue, sync, or spreadsheet action was performed. This is
  production-package installation/readback evidence, not a physical UI or
  feature walkthrough. No screenshot or recording is claimed.

## Recommended next actions

1. If physical v1.3 UI evidence is desired, use an isolated sandbox/debug
   package with a non-production endpoint. Check notification grant/denial and
   channel states, Settings routing, stale/pending runway suppression, compact
   and rail navigation, and expanded Insights/History panes without creating a
   production queue episode or spreadsheet write.
2. Validate runway wording and estimates only against genuine finished-product
   knowledge; do not manufacture production consumption history. A real
   24-hour offline queue alert remains deliberately untested.
3. Keep production widget visual/action checks separate. If needed, use the
   suffixed sandbox package for light/dark rendering, launcher sizing, controls,
   countdown/Undo, and local message states.
4. The first genuine production correction lifecycle, real Purchase-default
   restart behavior, and real-device analytics prefetch/wake behavior remain
   evidence gaps. Do not fill them with synthetic production writes.

## Data-safety notes

- Queue alerts read aggregate state only and never receive queue rows or entry
  details. Their copy contains no product names, quantities, or dates.
- Queue persistence, immutable IDs, acknowledgement-only deletion, retries,
  environment checks, and `CannsheetGraph.syncMutex` synchronization remain
  unchanged.
- Runway and spend estimates are neither persisted nor transmitted and
  disappear when their evidence is not fresh and complete.
- The production backend and spreadsheet were not changed or probed during
  v1.3 implementation, release, artifact verification, or installation.
- Public documentation intentionally omits wireless ADB serials, private local
  paths, credentials, signing material, and exact certificate digests.

## Relevant files

- `app/src/main/java/com/example/data/sync`
- `app/src/main/java/com/example/notifications`
- `app/src/main/java/com/example/domain/InventoryRunway.kt`
- `app/src/main/java/com/example/ui`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/test`
- `app/src/androidTest`
- `app/build.gradle.kts`
- `.github/workflows/android-pr-checks.yml`
- `.github/workflows/release-apk.yml`
- `docs/V1_3_FEATURE_PLAN.md`
- `docs/PROJECT_STATE.md`
- `docs/DECISIONS.md`
