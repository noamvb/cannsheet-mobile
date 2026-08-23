# Current handoff

Last updated: 2026-08-23

Repository: public `noamvb/cannsheet-mobile`

## LocalLLM version-one lifecycle coordinator (implemented, not released)

The focused `codex/cannsheet-localllm-coordinator` branch starts from exact-green main
`9d3fa6c` and changes only the optional version-one Insights narrative lifecycle,
its regression tests, and shared-context documentation. It replaces the Compose-owned
generation coroutine with a screen-lifetime coordinator keyed by the full eligibility
state and a deterministic request/facts fingerprint. Refresh, pending-range, pending-action,
initial-loading, refreshing, cache/stale, error, snapshot, or facts transitions synchronously
clear visible prose and cancel old work; an exact-key render gate also prevents the first
post-transition composition from showing prior prose. Stream fragments remain buffered behind
the loading state. Only a normally completed terminal output that passes deterministic length,
Unicode/control/bidirectional-character, finite Cannsheet-owned English-vocabulary,
prompt/refusal, health/causal/advice, projection, numeric-expression/unit, and grounded-number
validation is shown or retained in the four-entry screen-memory LRU for that exact key. Dynamic
fact text never expands the language allowlist. Collection stops before the buffer can exceed
2,000 characters. Partial, oversized, timed-out, cancelled, failed, or rejected output is hidden
and cannot be promoted or cached. LocalLLM absence, bind/status failure, missing model, and
unsupported state remain silent.

`InsightNarrativeCardTest` has direct JVM coverage for cancellation/hiding, render-time gating,
timeout after a partial fragment, oversized-stream cancellation, bounded access-order cache
reuse/eviction, cancellation propagation, Unicode and numeric-expression bypasses, and terminal
acceptance/rejection. The final focused suite passed 17 tests with zero failures/errors/skips. The
final full gate used the task-specific Gradle home, JDK/SDK paths,
`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`, and
`-Pkotlin.compiler.execution.strategy=in-process`; it executed all 64 tasks and passed in 5m 8s:
547 JVM tests with zero failures/errors/skips, Android-test Kotlin compilation, debug assembly,
and lint with zero errors and 126 existing warnings. All eight checked-in Node suites passed with
the installed Node 22 runtime's absolute path, and the Python backend benchmark passed 13 tests.
The first backend command did not start because `node` was absent from the shell PATH. No emulator,
physical device, ADB, backend deployment, production data, version, signing, release, or phone
installation was used.

Review-time failures were corrected rather than counted as evidence: one focused attempt found a
Kotlin constructor-call compile error; two focused runs exposed inadequate synchronization in the
new timeout test; and an oversized-output assertion once observed the intentional transient loading
state. The tests now use explicit synchronization and terminal-state waits. One concurrent
shared-output attempt failed with an inconclusive `NoSuchFileException`. A non-isolated full run
finished green but emitted a background KSP AWT shutdown exception and was rejected as final
evidence; a cold isolated run without in-process Kotlin compilation was intentionally stopped. The
first final-gate attempt found one one-second test wait that was too short under full-suite load;
after increasing only that test helper's scheduling allowance, the focused and full gates above
passed. An accidentally started focused command without the required environment was stopped before
task output, and the exact isolated command was rerun. An initial direct wrapper invocation also
failed because this worktree's `gradlew` is not executable; every reported Gradle pass used
`bash ./gradlew`.

## LocalLLM version-one fact corrections (implemented, not released)

The focused `codex/cannsheet-localllm-facts` branch corrects the bounded facts
Cannsheet supplies to LocalLLM without changing the service contract, lifecycle,
database, queue, backend, version, signing, or release configuration. Recorded
spend now states the number of purchases with known costs and is omitted when all
costs are unknown. Time-of-day counts are aggregated into valid bands; tied type,
weekday, and time-band leaders remain ties; product-type codes and legacy labels
are combined under their production display label; and count-based metrics say
"most frequently logged". The regression suite scans every serialized fact field
for forbidden projection language.

The final branch content passed
`./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` with 538 JVM
tests and zero failures, errors, or skips; lint and debug assembly also passed.
The focused `CannsheetLlmFactsTest` suite passed all 22 tests. No emulator,
physical device, ADB, production data, backend deployment, release, or phone
installation was used. The canonical client copy, version-two provider,
Assistant UI, grounded cards, and daily worker remain later coherent changes
in the accepted roadmap.

## Current release

Cannsheet Mobile `v1.6.2` (`versionCode 47`, `versionName 1.6.2`) is the latest
published Obtainium release in
[`noamvb/cannsheet-mobile-releases`](https://github.com/noamvb/cannsheet-mobile-releases/releases/tag/v1.6.2).
It is the first NFC release verified on real hardware before publication.

Two pull requests landed for this release, each squash-merged into `main`:

- [#149](https://github.com/noamvb/cannsheet-mobile/pull/149) let the tag writer
  own the NFC field while it is open, squash-merged as `857713f7`; and
- [#150](https://github.com/noamvb/cannsheet-mobile/pull/150) the version-only
  bump, squash-merged as `7335cda8990833dbfceb7422effbbb60425d09e7`.

The annotated source tag `v1.6.2` peels to
`7335cda8990833dbfceb7422effbbb60425d09e7`, the exact version commit that was the
tip of `origin/main` when the tag was pushed.

That commit's `push`-event run of "Cannsheet PR checks" on `main` is
[`32552800420`](https://github.com/noamvb/cannsheet-mobile/actions/runs/32552800420),
conclusion `success`, with all six required jobs individually green. Publication
ran as
[`32553132034`](https://github.com/noamvb/cannsheet-mobile/actions/runs/32553132034),
all three jobs green.

The public release contains exactly:

- `Cannsheet-Mobile-1.6.2.apk` (14,123,411 bytes)
- `Cannsheet-Mobile-1.6.2.apk.sha256`

The independently calculated APK SHA-256 is
`055ba03d3b451deccae2eeeb164779c9ee1d6f1ba2fcb89a53edd67e02488121`, matching the
published checksum file. `aapt dump badging` confirms package
`com.noamv.cannsheet.mobile`, `versionCode 47`, `versionName 1.6.2`,
`sdkVersion 24`, `targetSdkVersion 36`; `apksigner verify` reports APK Signature
Scheme v2. The signer certificate SHA-256 is
`a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e`, **identical**
to `v1.6.1`, `v1.6.0`, and `v1.5.2`, so the phone updates in place.

### Hardware verification performed before this release

The fix was exercised on the owner's Samsung SM-F966W over USB ADB before the
tag was pushed, using the checked-in `sandbox` build type. That variant installs
alongside production as `com.noamv.cannsheet.mobile.sandbox` with its own Room
database and a sandbox endpoint, so the test could not read or write production
data or reach the real spreadsheet. Production stayed at `v1.6.1` with its
original install date throughout.

A 26,886-line logcat capture of the session shows:

- `NfcService: setReaderMode … packageName: com.noamv.cannsheet.mobile.sandbox,
  flags: 15` when the writer opened, with `flags: 0` only at activity pauses and
  never during tag I/O;
- **`NfcQuickLogActivity` appearing zero times in the entire capture**, so the
  platform never dispatched a tag the writer was working on;
- a real registry entry persisted after exact readback, one tag with `uses: 1`;
- the registration timestamp resolving to the moment immediately after the final
  tag presentation, which is the adopt-an-unregistered-tag path that failed in
  `v1.6.1`; and
- no `AndroidRuntime` or `FATAL EXCEPTION` entries.

Paths still unverified on hardware: foreign-content overwrite, rewrite of a
registered tag, revoke, registry-corruption recovery, and an actual quick-log tap
logging a consumption against a loaded Pen cart.

## Incident: v1.6.0 shipped an NFC writer that could not read any tag

The owner reported that every tag returned "Could not read this tag" on first
physical use. It reproduced on every tag, and would have on every device.

`NfcTagWriterActivity.handleTag` called `disableReader()` before performing any
tag I/O. `NfcAdapter.disableReaderMode` restores the adapter to normal polling,
which resets the controller's discovery loop and deactivates the tag just handed
to the reader callback. The `Tag` handle was dead from that point, so the
`Ndef.connect()` inside `NfcTagWriter.inspect` always threw `IOException` and
every inspection resolved to `Failed`. The teardown was intended to prevent a
second callback re-entering an in-flight operation, which `tagIoMutex` and the
immediate state flip already guaranteed.

The same error disabled the reader in every state that asks the owner to retap,
so the two-presentation overwrite and verification flows would have been the next
wall. `WaitingToWrite` renders no action button, making that path a hard dead
end.

v1.6.1 fixed the read failure but still released the reader once an outcome was
settled, which caused a second incident; see below. The rule that actually holds
is that the writer keeps reader mode for its entire resumed lifetime, and only
`onPause` releases it.

**Why nothing caught it.** No automated check in this repository can execute a
single line of the tag I/O path: the JVM suites have no Android framework and the
API 24/36 emulators have no NFC radio. `v1.6.0` was released with that boundary
stated explicitly in this document, and the defect landed squarely inside it. The
`acceptsTagPresentation` predicate is now a pure function with direct coverage,
but the reader-lifetime half of the rule remains a review invariant that CI
cannot verify.

## Incident: v1.6.1 handed the tag back to the platform mid-registration

Presenting a tag in the writer popped up "This NFC tag is not registered" and
killed the writer while the owner was trying to register that very tag.

Tags already carrying Cannsheet content from the earlier failed attempts resolved
to `CompatibleUnregistered`, which offered Adopt and then called
`disableReader()`. With reader mode off and the tag still in the field, the
platform owned dispatch, matched the Application Record this app had written to
the tag itself, and launched `NfcQuickLogActivity` over the writer. The tag is
not in the registry until the writer finishes, so the scan surface truthfully
reported it unregistered — and bringing it to the front paused the writer, whose
`onPause` disabled its reader in turn.

That teardown came from a review comment on the v1.6.1 pull request asking the
reader to be released after terminal outcomes, to avoid polling behind a result
screen. The concern was cosmetic; the cost was this incident. Holding reader mode
is what suppresses platform dispatch, so for this Activity it is a correctness
requirement. Fixed in v1.6.2 (#149), which also preserves result states across a
resume and adds `NfcWriterSession` for the pause/resume gap.

**This is the first part of the feature ever confirmed on hardware.** Verified on
the owner's SM-F966W with the `sandbox` build type, which installs alongside
production under `com.noamv.cannsheet.mobile.sandbox` with its own database and a
sandbox endpoint. A 26,886-line logcat capture shows reader mode held across all
tag I/O (`flags: 15`, with `flags: 0` only at activity pauses),
**`NfcQuickLogActivity` appearing zero times**, a real registry entry persisted
after exact readback, and the registration timestamp landing immediately after
the final tap — the adopt path that originally failed. No crashes.

**The standing lesson: do not treat green CI as evidence about the NFC radio
path.** Any future change to `NfcTagWriter`, `NfcTagWriterActivity`, or
`NfcQuickLogActivity`'s intent handling needs a physical tap before release. The
safe way to get one is a temporary build type with an `applicationIdSuffix`, so
the test build installs alongside the release app; a plain debug build shares the
release `applicationId` with debug signing and could only install after an
uninstall, which would delete the Room database and pending offline queue rows.

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
