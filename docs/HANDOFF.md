# Current handoff

Last updated: 2026-08-26

Repository: public `noamvb/cannsheet-mobile`

## Cannsheet Mobile v1.8.0 (code 52) - barcode purchase autofill

Scanning the GS1 DataMatrix on a product label now fills the Purchase form from the app's
own record of that product. The feature is recognition, not extraction: a GTIN cannot say
what a product is, so a first sighting is typed in as before and the mapping is learned on
submit; every later scan of that product resolves exactly. See ADR-049 and the
"Barcode purchase autofill" section of `docs/PROJECT_STATE.md`.

### Release provenance

- Pull request merged: `noamvb/cannsheet-mobile#165`, squash merged
- Squashed commit on `main`: `f3a72111d0bf97aa0d5fe655dd8652ab2a0e27ef`
- Proving `main` run: run `32952596531`, `event=push`, `conclusion=success`
- All six required jobs passed on that exact SHA, on a `push` event:
  `Classify changes and scan repository`, `Backend validation`,
  `Android static validation`, `Emulator API 24`, `Emulator API 36`, and
  `Cannsheet Android PR validation`. Emulator API 36 only ever runs on `main`, which is
  why a green pull-request check can never satisfy the publish workflow.
- Annotated tag `v1.8.0` points at exactly that validated commit: `git rev-list -n 1 v1.8.0` returns `f3a72111d0bf97aa0d5fe655dd8652ab2a0e27ef`
- Published assets: `Cannsheet-Mobile-1.8.0.apk` (37,981,857 bytes) and
  `Cannsheet-Mobile-1.8.0.apk.sha256`, published 2026-08-26T09:38:36Z by release workflow
  run `32953500368`
- APK SHA-256: `8d74fe34605ba999d5b250819b69a44c703606055a4d684d0e2043551ee1d1c5`,
  re-downloaded and checked independently with `shasum -a 256 -c` after publication: OK.
  `aapt dump badging` reports package `com.noamv.cannsheet.mobile`, versionCode 52,
  versionName 1.8.0, sdkVersion 24, targetSdkVersion 36, the `CAMERA` permission, and
  `uses-feature-not-required: android.hardware.camera`.
- Signing certificate: `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`
  (`CN=Android Debug`), byte-identical to the certificate on the published v1.7.3 APK,
  which was downloaded and compared rather than assumed. `apksigner verify` reports
  `Verifies` with APK Signature Scheme v2. The phone can therefore update in place; no
  uninstall is required and no Room data or queued offline row is at risk.

### What shipped

- `Gs1Barcode` parses parenthesised, GS-separated and bare-concatenated GS1 element
  strings, plus bare UPC-A and EAN-13, into a normalised 14-digit GTIN with a validated
  mod-10 check digit, batch/lot (AI 10) and packaging date (AI 13, pinned to `Locale.US`).
- `scanned_product_links`, Room migration 11 -> 12, maps a GTIN to a product identity.
  Local only: not referenced by `SyncEngine`, no wire model, no spreadsheet column, and
  `backend_additions.gs` is unchanged.
- A recognised GTIN drives the existing autofill through `PurchaseFormState.withAutofillFor`,
  the same function a tapped suggestion uses.
- A changed lot flags THC, and only THC. An absent batch on either side is not a change.
- The pending scan lives on the form state, so abandoning a scan and later entering a
  different product by hand cannot link the first barcode to the second product.
- Purchase form state hoisted into `CannsheetViewModel`; the form now survives rotation
  and process death.
- New `CAMERA` permission, `uses-feature required="false"`. Frames are analysed in memory
  and never stored. No GTIN is transmitted; there is no external lookup.

### Verification performed

- Local: `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug` green,
  586 unit tests across 77 suites, zero failures.
- Local backend: 8 node suites and 13 python tests green.
- Local instrumentation: the full suite was run against a local API 36 emulator,
  driven by explicit adb serial so that AGP device orchestration could not reach the
  attached phone: `OK (172 tests)`, zero failures.
- CI: the six required jobs on the tagged `main` SHA, including Emulator API 24 and
  Emulator API 36.
- Publication re-verified independently after the workflow's own self-check.

### Known limitations

- End-to-end scanning against physical product labels was not exercised before release:
  it needs real camera hardware pointed at a real DataMatrix, and nothing is installed on
  the phone directly. The owner should confirm after installing through Obtainium.
- OCR and receipt scanning are deliberately not implemented. `cost` is still typed by
  hand. Whether OCR is worth building should be decided from how often an unrecognised
  product is actually scanned.
- The first scan of any product is no faster than before; only repeat purchases benefit.

### Next step for the phone owner

v1.8.0 is published. Open Obtainium, pull to refresh or tap **Check for updates**, and
install Cannsheet Mobile 1.8.0.
