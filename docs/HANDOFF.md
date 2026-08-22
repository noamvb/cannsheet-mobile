# Current handoff

Last updated: 2026-08-22

Repository: public `noamvb/cannsheet-mobile`

## Current release

Cannsheet Mobile `v1.6.0` (`versionCode 45`, `versionName 1.6.0`) is the latest
published Obtainium release in
[`noamvb/cannsheet-mobile-releases`](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.6.0).
It ships registered NFC quick-log tags.

Three pull requests landed for this release, each squash-merged into `main`:

- [#142](https://github.com/noamvb/cannsheet-mobile/pull/142) direct-uses
  deferred pen commits, squash-merged as
  `c6092730b56b8485ccf9c4bce446eb5d7d91c7a9`;
- [#143](https://github.com/noamvb/cannsheet-mobile/pull/143) registered NFC
  quick-log tags, squash-merged as
  `32baa52066cd773c3a8bb39573b28075588ca77d`; and
- [#144](https://github.com/noamvb/cannsheet-mobile/pull/144) the version-only
  bump, squash-merged as `6887647cea0c65723ff55ac0b78e03b3a822aa5c`.

The annotated source tag `v1.6.0` peels to
`6887647cea0c65723ff55ac0b78e03b3a822aa5c`, the exact version commit that was
the tip of `origin/main` when the tag was pushed. A later documentation-only
commit may advance `main`; the release tag intentionally remains on the exact
validated version commit.

That commit's `push`-event run of "Cannsheet PR checks" on `main` is
[`32546306052`](https://github.com/noamvb/cannsheet-mobile/actions/runs/32546306052),
completed with conclusion `success`. All six required jobs individually
succeeded: `Classify changes and scan repository`, `Backend validation`,
`Android static validation`, `Emulator API 24`, `Emulator API 36`, and
`Cannsheet Android PR validation`. No `main` run in this release sequence was
cancelled by a back-to-back merge; the runs for `c6092730` and `32baa520` also
completed successfully.

Publication ran as
[`32546649850`](https://github.com/noamvb/cannsheet-mobile/actions/runs/32546649850),
with `Confirm tested main commit`, `Verify and build signed APK`, and
`Publish verified Cannsheet APK` all green.

The public release contains exactly:

- `Cannsheet-Mobile-1.6.0.apk` (14,123,411 bytes)
- `Cannsheet-Mobile-1.6.0.apk.sha256`

The independently calculated APK SHA-256 is
`e571fc631f45ac8cded2a1946ac9a704923e70752319aa60cd0b4327e2c0e901`, which
matches the published checksum file (`shasum -a 256 -c` reported `OK`).
Independent `aapt dump badging` confirms package `com.noamv.cannsheet.mobile`,
`versionCode 45`, `versionName 1.6.0`, `sdkVersion 24`, and
`targetSdkVersion 36`. `apksigner verify` reports APK Signature Scheme v2.

The signer certificate SHA-256 is
`a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`. It was
compared directly against the previously published `Cannsheet-Mobile-1.5.2.apk`
and is **identical**, so the phone can update in place. A changed certificate
would have forced an uninstall and destroyed the Room database including pending
offline queue rows.

## NFC quick-log behavior shipped in v1.6.0

- A rewritable tag carries only protocol version `0x01`, a random RFC-4122
  tag UUID, and a whole `uses` value in `1..10` — 18 payload bytes, plus an
  Android Application Record. No product, endpoint, date, label, event ID, or
  seconds is ever written to a tag.
- The tag is an allowlisted convenience capability, not authentication. A clone
  with the same UUID and quantity is accepted by design; the local registry is
  the trust boundary. An unregistered UUID, a quantity that disagrees with its
  registration, or a corrupt registry all fail closed.
- The tapped quantity is applied to whichever Pen cart is current at tap time,
  resolved through the shared explicit-then-most-recent rule. Product, event ID,
  quantity, and timestamp are captured before the five-second Undo window, and
  the delayed commit uses the captured product with `updateLoadedCart = false`.
- Only `uses` cross `ConsumptionLogger` into Room, the offline queue, and the
  wire. The NFC path never consults a seconds-per-use rate.
- NFC reuses the deferred outbox's claim, Room-write, and completion boundary
  on the reserved surface ID `Int.MAX_VALUE - 1`. Pending-payload removal stays
  gated on durable Room persistence, and Undo never deletes a Room row.
- The result surface is lock-safe: while the keyguard is active it shows the
  quantity only, and cart-picker or Settings navigation requires unlock.
- Writing a tag requires exact readback before the registry is updated. Foreign
  content requires explicit confirmation plus a second presentation of the same
  physical tag, and a blank `NdefFormatable` tag requires a verification retap.
  No tag is erased and none is ever made read-only.

## Validation and release provenance

The serialized local gate on the release branch content:

```
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

passed with **523 JVM tests, zero failures, errors, or skips**; Android-test
Kotlin compilation, lint (126 warnings, zero errors), and debug assembly all
succeeded. All eight checked-in Node backend suites and the 13-test Python
benchmark passed, and `git diff --check` was clean. The deferred-core commit was
additionally validated standalone in a detached worktree before PR #142 was
opened: **499 JVM tests, zero failures**.

`PenWidgetStateRepositoryTest` was run on the local `cannsheet_widget_api36`
emulator: **11 of 11 passing**, including the two new tests covering undecodable
pending state.

Both feature pull requests received automated review comments that were fixed
rather than dismissed. #142 corrected a regression it had introduced, where an
undecodable pending payload blocked every submit while `read` still reported an
empty slot, leaving a widget that looked editable but silently refused to log;
the raw value is still preserved, and the surface is now consistently blocked
and reported through `pendingCommitUnreadable`. #143 fixed recreation losing
every non-`AwaitingUndo` state, an ignored `NfcAdapter.ignore` return value that
suppressed the removal fallback, and a `RegistrySaveFailed` state that offered no
recovery action.

## Validation boundary and remaining owner action

No physical NFC evidence exists. **No production or sandbox NFC tag has been
written or tapped**, no RF behavior has been observed, and the Samsung SM-F966W
screen-off/locked feasibility probe described in
`docs/NFC_QUICK_LOG_IMPLEMENTATION_PLAN.md` has not been run. Emulator results
prove theming, layout, manifest resolution, and NDEF serialization only; they
are not evidence of radio behavior. Do not infer screen-off dispatch or
signed-package RF behavior from source, JVM tests, emulator runs, or the debug
APK.

Screenshots in `docs/images/nfc-*.png` are API 36 emulator renders of the
no-NFC-hardware states, captured to document the surface legibility fix. The
"before" images are built from the pre-fix commit.

No APK was installed on the owner's phone and no phone state was changed. The
remaining release actions are for the owner:

1. Update through Obtainium to 1.6.0.
2. Write a first tag from Settings → NFC quick-log tags, choosing a quantity.
3. Confirm a tap logs that quantity against the currently loaded Pen cart, and
   that Undo within five seconds prevents the log.

Do not uninstall the production app or sideload a locally built debug APK;
either could break the signed-update path, and uninstalling would delete the
Room database and pending offline queue rows.

## Canonical references

- `docs/PROJECT_STATE.md`: verified implementation and release state.
- `docs/ARCHITECTURE.md`: system boundaries, the v3 deferred outbox, and the
  NFC data flow.
- `docs/DECISIONS.md`: durable decisions, including ADR-044 for NFC quick-log.
- `docs/NFC_QUICK_LOG_IMPLEMENTATION_PLAN.md`: the approved build specification,
  including the unperformed physical feasibility probe.
- `app/src/main/java/com/example/nfc/NfcQuickLogContract.kt`: the exact wire
  contract and fail-closed parser.
- `app/src/main/java/com/example/nfc/NfcQuickLogRegistryRepository.kt`: the
  local allowlist and its corruption behavior.
- `.agents/skills/ship-release/SKILL.md`: branch, PR, exact-main, tag,
  publication, artifact-verification, and Obtainium workflow.
