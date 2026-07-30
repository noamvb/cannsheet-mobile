# Project state

Last updated: 2026-07-30

## Repository state

- Canonical branch: `main`
- Current documentation follow-up branch: `codex/handoff-v1.2.17`
- Current `origin/main` and released source tag `v1.2.17`:
  `b49f15fbc6319db7ae47d94cbdd71ccee6fcabb3`
- Current release metadata in `app/build.gradle.kts`: version name `1.2.17`,
  version code `20`
- Release [PR #24](https://github.com/noamvb/cannsheet-mobile/pull/24)
  was squash-merged after its required validation passed.
- The public signed release is
  [Cannsheet Mobile 1.2.17](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.17).
  It contains exactly `Cannsheet-Mobile-1.2.17.apk` and
  `Cannsheet-Mobile-1.2.17.apk.sha256`.
- Public APK SHA-256:
  `8ffa67a223963459e8b5bd71a4f186e26cb7cacf84468121ad20d6dcecd5e938`
- The editable History milestone was delivered through backend
  [PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19), Android
  [PR #20](https://github.com/noamvb/cannsheet-mobile/pull/20), and production
  rollout hardening
  [PR #21](https://github.com/noamvb/cannsheet-mobile/pull/21),
  [PR #22](https://github.com/noamvb/cannsheet-mobile/pull/22), and
  [PR #23](https://github.com/noamvb/cannsheet-mobile/pull/23).

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
- The existing production Apps Script deployment was updated in place to
  version 12.
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

Private-source evidence:

- PR #24 head `e69df9dcb6da90ad1431cc8350b03223ba374d17`
  passed required PR workflow run
  [30513183284](https://github.com/noamvb/cannsheet-mobile/actions/runs/30513183284):
  classification/security, backend validation, Android static validation,
  API 24 emulator, and aggregate validation.
- Exact merged-main commit
  `b49f15fbc6319db7ae47d94cbdd71ccee6fcabb3` passed push workflow run
  [30513760246](https://github.com/noamvb/cannsheet-mobile/actions/runs/30513760246):
  classification/security, backend validation, Android static validation,
  API 24, API 36, and aggregate validation.

Signed-publication evidence:

- Tag `v1.2.17` resolves to the exact merged-main commit above.
- Signed release workflow run
  [30514059622](https://github.com/noamvb/cannsheet-mobile/actions/runs/30514059622)
  passed tag/main/version checks, tests, lint, signed build, signature
  verification, checksum generation, publication, and post-publication
  verification.
- The public release contains exactly one APK (13,241,161 bytes) and its
  `.sha256` file.

Independent public-artifact evidence:

- The downloaded checksum file matched the APK:
  `8ffa67a223963459e8b5bd71a4f186e26cb7cacf84468121ad20d6dcecd5e938`.
- Android `aapt` reported application ID `com.noamv.cannsheet.mobile`,
  version code `20`, and version name `1.2.17`.
- Android `apksigner` verified APK Signature Scheme v2 with one signer.
- Signing certificate SHA-256:
  `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`

Local and device evidence:

- One isolated local Gradle run failed before tests executed with
  `GeneratedClassCompilationException: Unable to compile generated classes`;
  no passing local build or test result is claimed.
- No physical-device installation, Obtainium update, screenshot, recording, or
  end-to-end correction action using the released APK was performed.

## Known limitations

- `app/src/main/res/xml/backup_rules.xml` and
  `app/src/main/res/xml/data_extraction_rules.xml` remain sample/template rules;
  the latter contains a backup-selection TODO.
- The Kotlin namespace remains `com.example` while the application ID is
  `com.noamv.cannsheet.mobile`; `README.md` records this as an intentional
  source-layout compatibility choice.
- The public APK is independently verified as update-compatible by package,
  higher version code, and signing certificate, but an actual in-place phone
  installation has not been observed in this release session.
- The first real production correction lifecycle still requires a deliberate
  user/device check; no synthetic production correction was created for testing.

## Current priorities

1. Install or update to v1.2.17 on the intended phone, preferably through
   Obtainium.
2. Perform one controlled Correct, Void, and Restore workflow on an eligible
   History entry and confirm the resulting History state after synchronization.
3. Record device results in `docs/HANDOFF.md` if they become available.

## Unresolved questions

- Does Obtainium detect and install v1.2.17 in place on the intended phone?
- Does the first real device correction lifecycle remain clear and reliable
  against the live production backend?

These require device evidence and should not be answered from repository or
workflow evidence alone.

## Relevant paths

- `app/src/main/java/com/example/ui`
- `app/src/main/java/com/example/data`
- `app/src/test`
- `app/src/androidTest`
- `tests/backend_corrections_test.js`
- `backend_additions.gs`
- `sandbox_provisioning.gs`
- `app/build.gradle.kts`
- `.github/workflows`
- `docs/HANDOFF.md`
