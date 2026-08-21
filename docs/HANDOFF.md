# Current handoff

Last updated: 2026-08-21

Repository: public `noamvb/cannsheet-mobile`

## Current release

Cannsheet Mobile `v1.5.1` (`versionCode 43`, `versionName 1.5.1`) remains the
latest published Obtainium release in
[`noamvb/cannsheet-mobile-releases`](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.5.1).
Its annotated source tag peels to
`96807f048297dd553beb653a06c5736928e2927f`, the exact version-only `main`
commit validated before publication. The published APK SHA-256 is
`74d362ffd5b40eda8a89f09257db7f83251a8f4c9c0f7d994bec4b76948ea56f`.

Independent v1.5.1 verification reported package
`com.noamv.cannsheet.mobile`, version code `43`, version name `1.5.1`, min SDK
`24`, target SDK `36`, zip alignment, one signer, and APK Signature Scheme v2.
Its certificate SHA-256 is
`a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e` and
its public-key SHA-256 is
`2de7a08db8c185ec727b77a3f1f7afd3b159c03f8efc6eb2d20c51d3a7043e7c`;
both matched the independently downloaded v1.5.0 APK.

## Runway correction in release preparation

Feature PR #138 fixes the reported generic "No reliable Pen runway estimate"
behavior and carries the implementation, regression coverage, and durable
documentation. Its behavior is:

- For an active product with valid grams, prefer the median recorded finish
  total from at least three finished products with the same normalized type and
  canonical gram amount. Other sizes cannot affect that exact cohort.
- If the exact-size cohort has fewer than three observations, retain the
  broader same-type grams-adjusted fallback, then the legacy same-type
  per-product fallback. Visible copy identifies the selected basis and its
  actual finished-product count.
- Compute remaining recorded uses separately from the burn-rate pace. A new
  active product with zero recorded uses can show its full estimated comparison
  immediately. Days remaining still requires positive selected-range use, a
  usable first-log date, and at least seven effective response-time-zone days.
- Preserve the existing in-app non-cache, non-stale, non-transitioning snapshot
  and real zero-pending-action gates. Projection-widget cache/as-of rules are
  unchanged.

The change is presentation-only. It adds no Room migration, analytics or cache
field, queue mutation, wire payload, Apps Script/backend behavior, spreadsheet
write, endpoint, application ID, signing, or stored/transmitted-unit change.
ADR-043 records the owner-selected exact-size preference, labeled fallback,
and immediate zero-use capacity policy.

## Validation completed for PR #138

The complete local gate ran from the isolated feature worktree with JDK 17,
Gradle 9.3.1, Android Platform 36.1, and Build Tools 36.0.0:

- `./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
  passed. JVM XML totals were 495 tests, zero failures, zero errors, and zero
  skips; lint reported zero errors and the debug APK assembled.
- All eight checked-in Node backend suites passed with Node 22.14.0.
- `python3 -m unittest tests/test_backend_sync_benchmark.py` passed all 13
  tests.
- `git diff --check` passed, and independent diff review found no remaining
  correctness or data-safety issue.

The first PR run, `32447738171`, correctly failed one newly added Insights
Compose assertion. The UI row tag is on a parent container, while the rendered
copy lives on descendant text nodes; the failure was a test-selector defect,
not a product-code failure. Commit `74bef32` changed that assertion to inspect
descendants and recompiled the instrumentation suite locally. PR run
`32448073354` then passed repository scan, backend validation, Android static
validation, all 162 API 24 instrumentation tests, and the aggregate validation
job. The handoff-only follow-up commit must still receive its own final PR gate
before merge.

No screenshot or production-data walkthrough is claimed. The visible state
requires a fresh Insights snapshot with the owner's matching product history,
and this checkout has no safe seeded end-to-end sandbox fixture for that
screen. The new Compose tests render and assert the exact-size capacity-only
state in both Insights and Pen Quick Log, including the absence of fabricated
days/rate copy.

## Release work still required

Do not describe the correction as shipped yet. The remaining release sequence
is:

1. Let the updated feature PR #138 pass and squash-merge it.
2. Wait for that feature merge's `main` run to complete before merging anything
   else.
3. Reconfirm the public latest release, then use a separate version-only PR for
   the next monotonic version (expected `versionCode 44`, `versionName 1.5.2`).
4. Wait for the exact version commit's successful `push` run on `main` and
   verify all six named jobs individually, including API 24 and API 36.
5. Annotate and push the matching tag, wait for signed publication, then
   independently download and verify the APK/checksum, package metadata,
   alignment, v2 signature, and signer continuity against v1.5.1.
6. Replace this handoff with the final PR/SHA/run/tag/asset provenance and tell
   the owner to update through Obtainium.

The tag must point to the exact current `origin/main` commit whose push run
passed all six jobs. Do not merge another PR between that validation and tag
publication.

## Data and device boundaries

A read-only `adb devices -l` check on 2026-08-21 found the owner's physical
Samsung phone connected. The ADB server was immediately stopped. No APK was
installed, no app was launched, and no phone data or state was changed; the
phone was explicitly declared safe to resume using. Local instrumentation was
not run because Gradle could have targeted the physical phone. Isolated GitHub
emulators are the device-level evidence for this release.

Never install a local debug APK over the production app or uninstall the
production app. Debug signing cannot update the release build, and uninstalling
would delete the Room database and any pending offline queue rows. Final phone
installation remains owner-performed through Obtainium.

## Canonical references

- `docs/PROJECT_STATE.md`: verified implementation and release state.
- `docs/ARCHITECTURE.md`: system boundaries and data flows.
- `docs/DECISIONS.md`: durable design and safety decisions, including ADR-043.
- `.agents/skills/ship-release/SKILL.md`: exact branch, PR, main-proof, tag,
  publication, artifact-verification, and Obtainium workflow.
