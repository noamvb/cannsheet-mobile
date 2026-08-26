# Current handoff

Last updated: 2026-08-26

Repository: public `noamvb/cannsheet-mobile`

## Cannsheet Mobile v1.8.1 (code 53) - barcode learning repaired

v1.8.0 shipped barcode purchase autofill in which the learning path never worked. v1.8.1
repairs it. Scanning a product label now fills the Purchase form from the app's own record
of that product: a first sighting is typed in as before and the mapping is learned on
submit, and every later scan of that product resolves exactly. See ADR-049 and the
"Barcode purchase autofill" section of `docs/PROJECT_STATE.md`.

### Release provenance

- Pull request merged: `noamvb/cannsheet-mobile#167`, squash merged
- Squashed commit on `main`: `b20088a7a0089c9ea7e18f9d4439812538582ae6`
- Proving `main` run: `33010663275`, `event=push`, `conclusion=success`
- All six required jobs passed on that exact SHA: `Classify changes and scan repository`,
  `Backend validation`, `Android static validation`, `Emulator API 24`, `Emulator API 36`,
  and `Cannsheet Android PR validation`. Emulator API 36 only ever runs on `main`, which is
  why a green pull-request check can never satisfy the publish workflow.
- Annotated tag `v1.8.1` points at exactly that validated commit: `git rev-list -n 1 v1.8.1`
  returns `b20088a7a0089c9ea7e18f9d4439812538582ae6`
- Published by release workflow run `33011290682` at 2026-08-26T20:44:45Z:
  `Cannsheet-Mobile-1.8.1.apk` (37,981,857 bytes) and `Cannsheet-Mobile-1.8.1.apk.sha256`
- APK SHA-256: `2ad6ba29a33c918a89a6c8c89b2087975bfb7936a5f325093c348f43d1a0bcb7`,
  re-downloaded and checked independently with `shasum -a 256 -c` after publication: OK.
  `aapt dump badging` reports package `com.noamv.cannsheet.mobile`, versionCode 53,
  versionName 1.8.1, sdkVersion 24, targetSdkVersion 36, the `CAMERA` permission, and
  `uses-feature-not-required: android.hardware.camera`.
- Signing certificate: `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`
  (`CN=Android Debug`), byte-identical to the certificate on the published v1.8.0 APK,
  which was downloaded and compared rather than assumed. `apksigner verify` reports
  `Verifies` with APK Signature Scheme v2. The phone updates in place; no uninstall is
  required and no Room data or queued offline row is at risk.

### What was wrong in v1.8.0, and why the tests did not catch it

`PurchaseFormState.clearedForNewSelection()` cleared the pending barcode, and selecting a
Type calls it. A newly scanned product has nothing prefilled, so the user must select a
Type - which erased the barcode before submission could learn it. The learning path was
broken every time, not intermittently. Confirmed on device: a second scan of the same
product still reported "New product".

The camera, parser, navigation and permission flow were all correct. The camera bound
cleanly on the device and the barcode was detected. The failure was form state alone.

The unit test covering that transition **asserted the broken behaviour**, so it passed
while the feature was dead. A test that encodes the wrong requirement is worse than no
test, because it reads as proof. Its replacement was verified by reintroducing the defect
locally and confirming exactly one test failed, at `PurchaseFormStateTest.kt:119`.

The defect came from over-correcting a rarer case: scanning, abandoning the entry, and
later entering a different product by hand would have mislinked the barcode. Guarding that
cost the primary path.

### What v1.8.1 changed

- The barcode survives every form edit and is dropped only by a successful submission or a
  reset. Editing the form is how the user tells the app what the barcode belongs to.
- A persistent line states that a barcode is attached, and carries a **Remove** action. It
  renders whenever a barcode is attached rather than only once the transient message
  clears, so the control exists at every moment attachment does. Attachment is never silent
  and never unrevocable.
- The rarer mislinking case is handled by that explicit control rather than by a heuristic.
  Detaching on a type change would silently drop the barcode when the user merely corrects
  a mis-tapped type, and would still miss an abandoned entry whose type is already set.
  Guessing at intent is what produced the original defect.

### Verification performed

- Local: `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug` green,
  587 unit tests, zero failures.
- CI: 175 instrumentation tests green on Emulator API 24 and Emulator API 36, plus the six
  required jobs on the tagged `main` SHA.
- Publication re-verified independently after the workflow's own self-check.

### Known limitations

- End-to-end scanning against physical product labels was not re-tested before this
  release. The app learns a barcode only on submit, and there is no purchase-deletion path
  in the app or the backend, so a synthetic test run would leave a duplicate purchase row
  removable only by hand-editing the spreadsheet. The owner's next genuine purchase
  exercises the flow at no cost: the first scan teaches it, the scan after that should
  prefill.
- The local emulator could not run the instrumentation suite reliably on this machine; it
  aborted with `INSTRUMENTATION_ABORTED: System has crashed` under memory pressure. CI
  covers it on both API levels.
- OCR and receipt scanning remain deliberately unimplemented. `cost` is still typed by hand.

### Operational notes worth keeping

- The CI emulator jobs are intermittently unreliable in a way that looks like a hang: test
  progress stops at a fixed count and the step is killed at its 20-minute cap. One such
  failure and its immediate re-run on the same commit are decisive - the failed run went
  silent at 105/174 for sixteen minutes, the re-run completed the remaining 66 tests in
  nine seconds. Silence at a fixed count means a dead emulator, not a hanging test, and
  warrants a re-run rather than a fix.
- `main` is governed by a repository ruleset, not classic branch protection, and it sets
  `required_review_thread_resolution`. An unresolved review thread blocks the merge even
  with every check green, and `gh pr merge` reports only "the base branch policy prohibits
  the merge".

### Next step for the phone owner

v1.8.1 is published. Open Obtainium, pull to refresh or tap **Check for updates**, and
install Cannsheet Mobile 1.8.1.
