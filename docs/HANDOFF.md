# Latest handoff

Last updated: 2026-08-15

## Current outcome

Cannsheet Mobile v1.3.3 is implemented, validated, merged, tagged, published, and
independently verified. The release corrects three backend caching defects and one
client refresh race introduced by the v1.3.2 performance work, and it adds the
`ship-release` skill that documents the release pipeline itself.

The published APK is installable in place over v1.3.2. Nothing is outstanding for
this release.

- Fix PR [#87](https://github.com/noamvb/cannsheet-mobile/pull/87) squash-merged as
  commit `b3c869b9946473cd25eb128cad0e682628190875`.
- Version bump PR [#88](https://github.com/noamvb/cannsheet-mobile/pull/88) squash-merged
  as commit `df59c08b1815c77fe03273c89c7234ceee9b7296`.
- Skill PR [#86](https://github.com/noamvb/cannsheet-mobile/pull/86) squash-merged as
  commit `8abe6bec6bfdf76681278843eb41fa777ff9da69`, after the tag.
- Release metadata in `app/build.gradle.kts`: `versionName = "1.3.3"`, `versionCode = 34`.

## v1.3.3 changes

- **Durable mutation watermark (`b3c869b`, PR #87)**: `bumpMutationWatermark_()` wrote
  only to `CacheService`, so a lost or expired cache entry silently reverted the
  watermark and resurrected stale pre-mutation cached responses. It now persists to
  `PropertiesService` first, then `CacheService`, each independently guarded.
- **Batch/sequential read parity (`b3c869b`, PR #87)**: the batch read path returned
  date cells as display strings and dropped trailing blank cells, diverging from the
  sequential path's cell types and changing `sourceRevision.dataVersion` for identical
  data. It now requests `SERIAL_NUMBER`, converts known date columns back to `Date`
  objects, and pads every row to the header width before hashing.
- **Fake runtime fidelity (`b3c869b`, PR #87)**: `tests/fake_apps_script_runtime.js` now
  honors `valueRenderOption` and `dateTimeRenderOption`, so this class of divergence is
  actually catchable by tests rather than masked by a forgiving fake.
- **Client refresh race (`b3c869b`, PR #87)**: `loadInsightsCacheThenRefresh()` and
  `loadHistoryCacheThenRefresh()` claim `isRefreshing` synchronously before launching
  their coroutine, so a `markStale()` landing in that window cannot start a second
  refresh that the coroutine's own refresh then cancels and restarts.
- **Test-suite audit (`b3c869b`, PR #87)**: backend tests added in v1.3.2 that asserted
  against the fake runtime, or re-implemented the logic in the test body without
  reaching `backend_additions.gs`, were rewritten or deleted.
- **Portable E2E runner (`b3c869b`, PR #87)**: `tests/run_e2e_verification.sh` no longer
  hardcodes one machine's Node path; it prefers `node` on `PATH`.
- **Release skill (`8abe6be`, PR #86)**: `.agents/skills/ship-release/SKILL.md` documents
  the branch, validation, exact-SHA main proof, version bump, tag, publication,
  verification, and Obtainium hand-off steps, with `.claude/skills/ship-release/SKILL.md`
  as a thin vendor pointer.

## Validation and provenance

- The tagged commit `df59c08` has push-to-main validation
  [run 31864480932](https://github.com/noamvb/cannsheet-mobile/actions/runs/31864480932),
  completed with conclusion `success`. All six required jobs passed, which is what
  `release-apk.yml` verifies before publishing.
- The annotated tag `v1.3.3` points at exactly that validated commit.
- Publication [run 31864797568](https://github.com/noamvb/cannsheet-mobile/actions/runs/31864797568)
  completed with conclusion `success`, including its own post-publication re-download
  and re-verification step.
- The main run for `b3c869b` (31864272789) was `cancelled` by the immediately following
  merge. This did not block the release because the tag is on `df59c08`, whose own run
  is green and whose tree contains the `b3c869b` changes.
- Backend suites re-run at `8abe6be` and all passing: `backend_analytics_test.js`,
  `backend_contract_test.js`, `backend_corrections_test.js`, `backend_recovery_test.js`,
  `backend_spreadsheet_test.js`, `fake_sheets_batch_update_test.js`,
  `sandbox_performance_fixture_test.js`, `sandbox_provisioning_test.js`, and
  `python3 -m unittest tests/test_backend_sync_benchmark.py` (13 tests, OK).
- Not re-run in this verification pass: Gradle unit tests, `lintDebug`, and the emulator
  checks. They passed in CI on the tagged commit; no Android SDK was available in the
  verifying environment to repeat them locally.

## Published artifacts

Published to `noamvb/cannsheet-mobile-releases` under tag `v1.3.3`, two assets:

- `Cannsheet-Mobile-1.3.3.apk`
  SHA-256 `082598f6d717db2a92f476601669007b1bbdab55f34d304ddb3f16c0dbc76f1b`
- `Cannsheet-Mobile-1.3.3.apk.sha256`

Independently verified by downloading both assets and checking them outside the
publishing workflow:

- The published checksum file matches the published APK.
- The APK manifest declares package `com.noamv.cannsheet.mobile` and version `1.3.3`.
- The APK Signature Scheme v2 signing certificate is byte-identical to the one on
  `Cannsheet-Mobile-1.3.2.apk`, SHA-256 fingerprint
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`.
  Signing continuity holds, so v1.3.3 installs over v1.3.2 without an uninstall and the
  Room database and pending offline queue rows survive the update.

## Risks and open items

- **The release signing certificate carries the Android debug distinguished name**
  (`CN=Android Debug, O=Android, C=US`, serial `01`, valid 2026-07-10 to 2056-07-02).
  It is consistent across releases, so in-place updates work and nothing about this
  release is broken. It does matter for two futures: Google Play rejects
  debug-signed uploads, and if this keystore is the machine's `~/.android/debug.keystore`
  rather than a purpose-made release keystore, then losing or regenerating it would make
  it permanently impossible to ship an in-place update. Confirm the keystore behind
  `RELEASE_KEYSTORE_BASE64` is stored deliberately and backed up. Do not change the
  signing identity for an existing install base without planning the uninstall it forces.
- The cache-hit guard bypass found during the v1.3.3 review — a cache hit skips the
  environment, schema-version, timezone, and `PENDING_APPLY` guards that a cache miss
  enforces — is an accepted trade-off recorded in ADR-022, not a defect to fix silently.
- `backend_additions.gs` is source only. A backend change is not live merely because it
  merged; deploying the Apps Script is a separate owner-performed step.
- The skill PR `8abe6be` landed after the tag, so `main` is one commit ahead of `v1.3.3`.
  That commit is documentation only and is not in the published APK.

## Relevant files

- `backend_additions.gs`
- `tests/fake_apps_script_runtime.js`
- `tests/backend_analytics_test.js`
- `tests/run_e2e_verification.sh`
- `app/src/main/java/com/example/ui/AnalyticsState.kt`
- `app/src/test/java/com/example/ui/AnalyticsCoordinatorTest.kt`
- `app/build.gradle.kts`
- `.agents/skills/ship-release/SKILL.md`
- `.claude/skills/ship-release/SKILL.md`
- `docs/DECISIONS.md` (ADR-022)
- `docs/PROJECT_STATE.md`

## Recommended next action

Tell the phone owner to open Obtainium and install Cannsheet Mobile 1.3.3. Then confirm
whether the release keystore is a deliberate, backed-up release key rather than the
machine's debug keystore, and record the answer as an ADR.
