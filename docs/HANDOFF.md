# Latest handoff

Last updated: 2026-07-30

## Purpose of this session

Complete the editable consumption-History rollout, publish Cannsheet Mobile
v1.2.17, independently verify the public APK, and leave an evidence-based
handoff that separates repository, CI, production-backend, public-artifact, and
device state.

## Work completed

- Backend correction support was delivered through
  [PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19).
- Android Correct, Void, Restore, pending-correction, Room migration, queue,
  network-contract, and History UI support was delivered through
  [PR #20](https://github.com/noamvb/cannsheet-mobile/pull/20).
- Production rollout hardening was delivered through
  [PR #21](https://github.com/noamvb/cannsheet-mobile/pull/21),
  [PR #22](https://github.com/noamvb/cannsheet-mobile/pull/22), and
  [PR #23](https://github.com/noamvb/cannsheet-mobile/pull/23).
- Production provisioning and full recoverable reconciliation completed from
  merge-verified PR #23 source. The existing deployment was updated in place to
  version 12, and correction writes were enabled.
- Release [PR #24](https://github.com/noamvb/cannsheet-mobile/pull/24)
  changed the app to `versionName` `1.2.17` and `versionCode` `20`. Its PR head
  was `e69df9dcb6da90ad1431cc8350b03223ba374d17`; it was squash-merged at
  `b49f15fbc6319db7ae47d94cbdd71ccee6fcabb3`.
- Annotated tag `v1.2.17` targets that exact merged-main commit.
- Signed workflow run
  [30514059622](https://github.com/noamvb/cannsheet-mobile/actions/runs/30514059622)
  published [Cannsheet Mobile 1.2.17](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.17).
- Documentation follow-up
  [PR #25](https://github.com/noamvb/cannsheet-mobile/pull/25) merged after
  publication. It updated shared context without moving the release tag.

## Files and artifacts

Release PR #24 changed:

- `app/build.gradle.kts`
- `docs/PROJECT_STATE.md`
- `docs/HANDOFF.md`

This documentation follow-up branch, `codex/handoff-v1.2.17`, was created from
`origin/main` at `b49f15fbc6319db7ae47d94cbdd71ccee6fcabb3`. It modifies only:

- `docs/PROJECT_STATE.md`
- `docs/HANDOFF.md`

The public release contains exactly:

- `Cannsheet-Mobile-1.2.17.apk` — 13,241,161 bytes
- `Cannsheet-Mobile-1.2.17.apk.sha256`

The APK and checksum are publication artifacts, not files in the private source
checkout.

## Current state

### Private source

- Release tag `v1.2.17` resolves to release commit
  `b49f15fbc6319db7ae47d94cbdd71ccee6fcabb3`.
- `app/build.gradle.kts` contains version name `1.2.17` and version code `20`.
- PR #25 merged the evidence-focused documentation after the release commit;
  that later documentation commit does not change what `v1.2.17` identifies.
- No unrelated or untracked task file was present during the documentation
  updates.

### Production backend

- The public endpoint reports API version 2, `PRODUCTION`, correction schema
  version 1, and correction writes enabled.
- Reconciliation was clean immediately before and after provisioning and before
  write enablement: no pending apply, incomplete journal, reported difference,
  or blocking difference.
- Production row counts changed normally while rollout work was in progress and
  are not recorded here as future expectations.
- The correction sheet remained exact-header and otherwise empty during the
  rollout. Codex sent no valid production correction request.

### Public release

- Public release:
  [v1.2.17](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.2.17)
- Direct APK:
  [Cannsheet-Mobile-1.2.17.apk](https://github.com/noamvb/cannsheet-mobile-releases/releases/download/v1.2.17/Cannsheet-Mobile-1.2.17.apk)
- APK SHA-256:
  `8ffa67a223963459e8b5bd71a4f186e26cb7cacf84468121ad20d6dcecd5e938`

## Validation performed

### Passing repository and CI evidence

- PR #24 validation run
  [30513183284](https://github.com/noamvb/cannsheet-mobile/actions/runs/30513183284)
  passed the required classification/security, backend, Android static, API 24,
  and aggregate checks on exact head
  `e69df9dcb6da90ad1431cc8350b03223ba374d17`.
- Exact merged-main run
  [30513760246](https://github.com/noamvb/cannsheet-mobile/actions/runs/30513760246)
  passed classification/security, backend, Android static, API 24, API 36, and
  aggregate validation on
  `b49f15fbc6319db7ae47d94cbdd71ccee6fcabb3`.
- Signed release run
  [30514059622](https://github.com/noamvb/cannsheet-mobile/actions/runs/30514059622)
  passed exact tag/main/version checks, unit tests, lint, version monotonicity,
  keystore restoration, signed APK build, signature verification, checksum
  generation, publication, and post-publication verification.
- PR #25 used docs-only head `a05f0407273c79d23edf0c2d879ec44f9b0358a3`.
  Its PR run
  [30514663357](https://github.com/noamvb/cannsheet-mobile/actions/runs/30514663357)
  passed classification/security and aggregate validation while correctly
  skipping backend, Android, and emulator jobs.
- Its subsequent automatic main run
  [30514696023](https://github.com/noamvb/cannsheet-mobile/actions/runs/30514696023)
  passed classification/security, backend, Android static, API 24, API 36, and
  aggregate validation at docs commit
  `7cd31c6b97b874a6972c9eb855ace11e4cdd3b7e`.

### Passing independent public-artifact evidence

- The downloaded checksum file matched the downloaded APK:
  `8ffa67a223963459e8b5bd71a4f186e26cb7cacf84468121ad20d6dcecd5e938`.
- Android `aapt` reported:
  - application ID `com.noamv.cannsheet.mobile`;
  - version code `20`;
  - version name `1.2.17`.
- Android `apksigner verify --verbose --print-certs` succeeded:
  - APK Signature Scheme v2 verified;
  - one signer;
  - certificate SHA-256
    `A9:78:72:49:B1:06:D9:8A:42:1E:D8:39:78:93:61:A4:57:53:E3:67:E2:43:82:0D:10:D2:F3:A0:97:08:66:5E`.

### Passing production-backend evidence

- Production provisioning completed after the bounded typed-column cosmetic
  warning.
- Full recoverable reconciliation remained clean through provisioning and
  enablement.
- Deployment version 12 was verified at the existing production URL.
- Post-enable endpoint verification confirmed correction schema version 1 and
  correction writes enabled.

### Failed or blocked local validation

- The first local Gradle attempt did not start because Windows denied creation
  of the selected temporary Gradle directory.
- One permitted isolated-home retry downloaded Gradle, then failed before tests
  executed with
  `GeneratedClassCompilationException: Unable to compile generated classes`.
- No passing local Gradle build or local test result is claimed. Exact-source
  GitHub Actions is the authoritative passing Android evidence.

## Validation not performed

- No physical-device installation or in-place update was observed.
- Obtainium detection and installation of v1.2.17 was not tested.
- No screenshot or recording of the released app was captured.
- No end-to-end Correct, Void, or Restore action was performed with the
  released APK against production.
- No synthetic valid production correction request was sent solely for testing.

## Remaining work and risks

- The public APK is update-compatible by package ID, higher version code, and
  signing certificate, but a real phone update remains unverified.
- Production writes are enabled, but the first real correction lifecycle must
  be deliberate and observed. Do not infer device UX or live mutation behavior
  from CI, metadata, or provisioning evidence.
- Ordinary app entries can continue changing production row counts. Use fresh
  reconciliation state rather than this handoff's historical snapshots before
  any future production mutation.

## Recommended next action

Install or update to v1.2.17 on the intended phone, preferably through
Obtainium. Then perform one controlled Correct, Void, and Restore workflow on an
eligible History entry, verify the synchronized History result, and record the
device and reconciliation evidence in this handoff.

## Safety review

- The final documentation diff contains no credential, secret, private key,
  keystore, personal absolute path, runtime database, build output, or
  downloaded APK.
- No application ID, endpoint identity, signing configuration, signing key, or
  workflow secret was changed.
- Temporary Gradle and APK-verification downloads created by this session were
  removed after use.
- No unrelated user change was found or discarded.

## Relevant files

- `app/build.gradle.kts`
- `app/src/main/java/com/example/data`
- `app/src/main/java/com/example/ui`
- `app/src/test`
- `app/src/androidTest`
- `backend_additions.gs`
- `tests/backend_corrections_test.js`
- `.github/workflows/android-pr-checks.yml`
- `.github/workflows/release-apk.yml`
- `docs/PROJECT_STATE.md`
