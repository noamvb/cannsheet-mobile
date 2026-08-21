# Current handoff

Last updated: 2026-08-21

Repository: public `noamvb/cannsheet-mobile`

## Unreleased NFC quick-log work

The current working branch is `codex/nfc-quick-log-tags`; the durable-core parent
is `codex/nfc-deferred-core`. These are implementation workspaces, not merged PRs
or a release. They contain the approved uses-based NFC
contract, private registry, scan/result and tag-writer activities, Settings
integration, and the v3 direct-uses deferred outbox. The feature resolves the
current Pen cart at tap time, captures product/event/time before a fixed
five-second Undo window, and preserves the existing Room/sync claim boundary.
The local durable-core branch is based at `786ee86`; the current NFC feature
branch is local-only. Neither branch has been pushed or opened as a remote PR.

Validation completed so far: the serialized local gate
`./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
passed with 523 JVM tests and zero failures/errors/skips; Android-test Kotlin
compilation, lint, and debug assembly also passed. All eight checked-in Node
backend suites, the 13-test Python benchmark, and `git diff --check` passed.
Full emulator instrumentation, physical Samsung feasibility/sandbox evidence,
CI/PR integration, signed release, and Obtainium handoff remain outstanding.
No ADB/device or production NFC action was performed. Do not infer screen-off
dispatch, signed-package RF behavior, or release readiness from source, local
JVM tests, or the debug APK. Before splitting or merging, review the complete
diff and preserve the untracked `docs/images/multi-cart-widget-grid-after-commit.png`.

## Current release

Cannsheet Mobile `v1.5.2` (`versionCode 44`, `versionName 1.5.2`) is the latest
published Obtainium release in
[`noamvb/cannsheet-mobile-releases`](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.5.2).
Feature PR [#138](https://github.com/noamvb/cannsheet-mobile/pull/138) was
squash-merged as `9b1f0d7c120790565ea3764082bef7b305e5792d`; separate
version-only PR [#139](https://github.com/noamvb/cannsheet-mobile/pull/139) was
squash-merged as `ae9a4ebbc6509cd3b0ad3450dd4964574990f915`.

The annotated source tag `v1.5.2` peels to
`ae9a4ebbc6509cd3b0ad3450dd4964574990f915`, the exact version commit whose
`push` run passed the required six-job `main` gate. A later documentation-only
commit may advance `main`; the release tag intentionally remains on the exact
validated version commit.

The public release contains exactly:

- `Cannsheet-Mobile-1.5.2.apk` (14,024,579 bytes)
- `Cannsheet-Mobile-1.5.2.apk.sha256`

The independently calculated APK SHA-256 is
`46e7a9808813faa0c0dac672fc17c62192199eca4356bed8a07064753ed27707`.

## Runway behavior shipped in v1.5.2

- For an active product with valid grams, Runway first looks for at least three
  finished products with the same normalized type and canonical gram amount.
  It uses the median recorded finish total from that exact-size cohort, so
  differently sized products cannot influence the preferred baseline.
- If fewer than three exact-size observations exist, Runway deliberately falls
  back to the same-type grams-adjusted median and then the same-type
  per-product median when usable gram evidence is unavailable. Visible copy
  identifies the selected basis and reports its actual finished-product count.
- Remaining recorded uses are calculated separately from daily pace. An active
  product with zero recorded uses can therefore show its full typical recorded
  capacity immediately. Days remaining appears only after positive use in the
  selected range, a usable first-use date, and at least seven effective days in
  the response time zone.
- Capacity-only rows explain why a days estimate is not ready instead of
  collapsing to the generic "No reliable Pen runway estimate" message.
  Short-range guidance is not duplicated below rendered capacity rows.
- All pre-existing in-app safety gates remain: estimates require a live,
  non-cache, non-stale, non-transitioning Insights snapshot and a real zero
  pending-action count. Projection widgets retain their documented cached
  snapshot plus as-of-date rule.

This remains a presentation-only estimate of recorded finish behavior. It adds
no Room migration, cache or analytics field, queue mutation, wire payload,
Apps Script/backend behavior, spreadsheet write, endpoint, application ID,
signing configuration, or stored/transmitted-unit change. ADR-043 records the
owner-selected exact-size preference, labeled fallback, and immediate zero-use
capacity policy.

## Validation and release provenance

The complete local implementation gate ran from an isolated worktree with JDK
17, Gradle 9.3.1, Android Platform 36.1, and Build Tools 36.0.0:

- `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
  passed. JVM XML totals were 495 tests, zero failures, zero errors, and zero
  skips; lint reported zero errors and the debug APK assembled.
- All eight checked-in Node backend suites passed.
- `python3 -m unittest tests/test_backend_sync_benchmark.py` passed all 13
  tests.
- `git diff --check` passed, and independent review found no remaining
  correctness or data-safety defect. The review's duplicate short-range-copy
  finding and missing direct Compose coverage were corrected before merge.

Remote provenance:

- Feature PR final run
  [32448486181](https://github.com/noamvb/cannsheet-mobile/actions/runs/32448486181)
  passed repository scan, backend validation, Android static validation, all
  API 24 instrumentation tests, and the aggregate gate. Exact feature-`main`
  run [32448792817](https://github.com/noamvb/cannsheet-mobile/actions/runs/32448792817)
  passed all six jobs, including API 24 and API 36.
- Version PR run
  [32449569622](https://github.com/noamvb/cannsheet-mobile/actions/runs/32449569622)
  passed its required jobs. Exact tagged-`main` run
  [32449886506](https://github.com/noamvb/cannsheet-mobile/actions/runs/32449886506)
  was a successful `push` run on
  `ae9a4ebbc6509cd3b0ad3450dd4964574990f915`; each of its six named jobs
  succeeded.
- Signed publication run
  [32450412524](https://github.com/noamvb/cannsheet-mobile/actions/runs/32450412524)
  passed exact-main confirmation, version and monotonicity checks, unit
  tests/lint, signed build, workflow signature verification, publication, and
  post-publication verification.

Independent fresh-download verification of the public APK and checksum passed.
The checksum file and GitHub asset digest both match the SHA-256 above. Build
Tools 36.0.0 readback confirmed package `com.noamv.cannsheet.mobile`, version
code `44`, version name `1.5.2`, min SDK `24`, target SDK `36`, valid zip
alignment, one signer, and APK Signature Scheme v2. Certificate SHA-256
`a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`
and public-key SHA-256
`2de7a08db8c185ec727b77a3f1f7afd3b159c03f8efc6eb2d20c51d3a7043e7c`
exactly match an independently downloaded public v1.5.1 APK.

## Validation boundary and remaining owner action

A read-only `adb devices -l` check found the owner's physical Samsung phone;
the ADB server was immediately stopped. No APK was installed, no app was
launched, and no phone data or state was changed. Local instrumentation was not
run because Gradle could have targeted that physical phone; GitHub's isolated
API 24 and API 36 emulators supply the device-level automated evidence.

No physical screenshot or production-history walkthrough is claimed. The
visible state depends on a fresh Insights snapshot and the owner's actual
product history, and there was no safe seeded end-to-end sandbox fixture for
that screen. Direct Compose tests render the same-size capacity-only state in
both Insights and Pen Quick Log and assert that no fabricated days or rate copy
appears.

The release workflow is complete. The only remaining release action is for the
owner to update through Obtainium, refresh Insights, and compare the Pen runway
with real same-size finished products. Do not uninstall the production app or
sideload a local debug APK; either could break the signed-update path, and
uninstalling would delete the Room database and pending offline queue rows.

## Canonical references

- `docs/PROJECT_STATE.md`: verified implementation and release state.
- `docs/ARCHITECTURE.md`: system boundaries and Runway data flow.
- `docs/DECISIONS.md`: durable decisions, including ADR-043.
- `app/src/main/java/com/example/domain/InventoryRunway.kt`: capacity cohorts
  and pace calculation.
- `app/src/main/java/com/example/ui/RunwayFormatting.kt`: evidence and
  capacity-only copy.
- `.agents/skills/ship-release/SKILL.md`: branch, PR, exact-main, tag,
  publication, artifact-verification, and Obtainium workflow.
