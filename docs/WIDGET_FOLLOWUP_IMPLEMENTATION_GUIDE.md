# Pen widget follow-up: implementation guide

Written: 2026-08-13
Base commit: `00f7860` (`main`, v1.2.26)
Findings source: `docs/WIDGET_FOLLOWUP_PLAN.md` (R-01…R-12)

## 0. How to use this document

This is an execution guide, not a review. The analysis behind every task lives in
`docs/WIDGET_FOLLOWUP_PLAN.md`; this document tells you what to change, in what
order, how to prove it, and where the traps are.

Each task gives you: the anchor (file and line at `00f7860`), the exact change,
the tests to add, acceptance criteria, and the verification command. Line numbers
are accurate at the base commit and will drift as you edit — re-anchor by symbol
name, not by line, once you have started.

Three things to read before writing code:

1. **§1 Ground rules.** This repository has hard constraints on releases,
   endpoints, and data safety. Violating one fails review regardless of code
   quality.
2. **§2 Preconditions.** The local gate needs JDK 17+; the instrumented tests
   need an emulator.
3. **§4 Task dependency order.** T1 must land before T2. T3 is blocked on device
   data you may not have.

**Do not batch all tasks into one commit.** The commit structure in §7 is part of
the deliverable.

## 1. Ground rules

From `AGENTS.md`, non-negotiable:

- **Never** change `versionCode`, `versionName`, create a tag or release, build a
  signed release, or touch signing configuration. None of this work is release
  work.
- **Never** change the production Apps Script endpoint, `applicationId`,
  namespace, or environment IDs.
- **Never** commit `sandbox.properties`, keystores, or credentials.
- Room migrations and the offline queues are **user data**. Nothing in this plan
  requires a schema change. If a task starts to need one, stop and escalate —
  that is a signal the approach is wrong.
- All queue synchronization goes through `SyncEngine` under
  `CannsheetGraph.syncMutex`. No task here touches sync.
- Report every check that was not run or did not pass. Do not describe an
  unexecuted check as successful.
- One coherent change per pull request.

Additional constraints specific to this work:

- **The seconds/uses boundary is inviolable.** Seconds are display and input
  units. Only `uses` may reach `ConsumptionLogger`, Room, the offline queue, or
  the wire. No task below changes this; if a diff makes `seconds` cross that
  line, it is wrong.
- **Do not weaken the claim protocol.** `claimCommit` → Room write →
  `completeCommit` is what makes a failed write recoverable. T1 tightens it;
  nothing may loosen it.
- **Do not remove the serialization boundary.** `PenWidgetRuntime`'s mutex is
  currently the only thing preventing an undo/claim race. T1 exists precisely so
  that stops being true — but until T1 lands, the mutex is load-bearing.

## 2. Preconditions

```bash
java -version          # must be 17 or newer; Gradle 9.3.1 rejects older
./gradlew --version    # confirms wrapper resolves
```

Android SDK Platform 36.1 and Build Tools 36.0.0 are required. The local gate
used by PR #55 and expected here:

```bash
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

Instrumented tests need a running emulator or device:

```bash
./gradlew --no-daemon connectedDebugAndroidTest
```

If you cannot run the instrumented suite locally, say so explicitly in the PR
body and rely on CI — but note that **PR CI only runs the API 24 emulator**
(`.github/workflows/android-pr-checks.yml` sets `api_levels=[24]` on every
`pull_request` classification path; `[24,36]` only on push to `main`). API 36
coverage therefore arrives *after* merge. Do not claim pre-merge API 36
validation.

Backend suites are unaffected by this work but are cheap:

```bash
node tests/backend_contract_test.js
node tests/backend_recovery_test.js
node tests/backend_analytics_test.js
```

## 3. What is already correct — do not "fix" these

These were verified end to end during review. Changing them will regress
behavior:

- `insertConsumption` uses `OnConflictStrategy.REPLACE` (`Database.kt:166`)
  against a unique `eventId` index (`Database.kt:53`). Combined with the
  backend's `eventId` dedup (`backend_additions.gs:2837-2839`), a retried claim
  cannot duplicate a row. Both halves are required. Do not change either.
- `resolveCommit` checks claim recoverability **before** the `force` branch
  (`PenWidgetTransitions.kt:38-40`), so `onDeleted`'s force-commit cannot steal
  an in-flight claim. Preserve that ordering.
- `onDeleted` clears widget state only when a re-read shows
  `pendingCommit == null` (`PenConsumptionWidgetProvider.kt:62-66`). A payload
  that failed to commit must survive.
- `PROCESS_CLAIM_OWNER_ID` is a per-process UUID, which is what lets a restarted
  process immediately recover an abandoned claim while the same process waits out
  `CLAIM_STALE_MILLIS`. Do not make it a constant.
- `compact = minHeightDp in 1 until subtitleMinHeightDp`
  (`PenWidgetUpdater.kt:43`) deliberately excludes `0` so a launcher reporting no
  options bundle gets the full layout. Keep the `1 until` bound.
- The v1→v2 payload migration derives `eventId` deterministically via
  `UUID.nameUUIDFromBytes` (`PenWidgetPayloadCodec.kt:62-64`). It must stay
  deterministic or repeated decodes will diverge.

## 4. Tasks and dependency order

| Task | Findings | Blocking? | Effort |
|------|----------|-----------|--------|
| **T1** Undo/claim arbitration | R-01 | none | small, high care |
| **T2** Provider test seam and tests | R-03 | after T1 | large |
| **T3** Widget sizing decision | R-02, R-04 | **blocked on device data** | medium |
| **T4** Robustness polish | R-05, R-06, R-07 | none | small |
| **T5** Text, accessibility, cleanup | R-08, R-09, R-10 | none | small |

T1 before T2 because T2's central test asserts T1's behavior. T3 can proceed in
parallel with T4/T5 once unblocked. T4 and T5 are independent of everything.

R-11 (per-tap cost under the mutex) and W-24 stay deferred until T3's device
session produces profiling data. R-12 (device validation) is not a code task —
it is the session T3 depends on.

---

## T1 — Move undo/claim arbitration into the resolver

**Finding:** R-01. **Severity:** Medium. **Risk:** behavior-neutral if done
correctly; this is the only task that touches arbitration.

### Why

`resolveUndo` matches on `commitId` alone. On its own terms it will happily
restore a payload that has already been claimed and whose Room write is in
flight — a **phantom log**: a row in Room and the offline queue that the user
believes they cancelled, with `enqueueSync` skipped because `completeCommit`
returns false.

This is **not currently reachable.** All six paths that can claim or undo hold
the same `WidgetWorkSerializer` mutex, so the claim→write→complete sequence and
any undo are strictly ordered:

| Path | Serialized at |
|---|---|
| Commit timer | `PenWidgetRuntime.kt:65-69` |
| WorkManager worker | `PenWidgetCommitWorker.kt:16` |
| Provider `onReceive` | `PenConsumptionWidgetProvider.kt:85` |
| Provider `onUpdate` | `PenConsumptionWidgetProvider.kt:21` |
| Provider `onDeleted` | `PenConsumptionWidgetProvider.kt:43` |
| Provider options-changed | `PenConsumptionWidgetProvider.kt:158` |
| Application startup | `CannsheetApplication.kt:24` |

The problem is that this is a whole-program invariant defended in one place,
documented nowhere near the code that depends on it, and invisible to the pure
tests covering `resolveUndo`. A second widget surface, a `Dispatchers.IO` hop
around the Room write, an `android:process` declaration, or a reasonable-looking
"don't hold the lock across I/O" refactor reintroduces a data-integrity bug with
no failing test.

ADR-013 already established the right principle for exactly this shape: *"WorkManager
cancellation is only an optimization; it cannot be the correctness boundary."*
Apply the same reasoning to the mutex.

### Change 1 — `app/src/main/java/com/example/widget/PenWidgetTransitions.kt:19-27`

Replace:

```kotlin
fun resolveUndo(
    payload: PenWidgetCommitPayload?,
    commitId: String,
): PenWidgetUndoResolution =
    if (payload?.commitId == commitId) {
        PenWidgetUndoResolution.Restored(payload.seconds)
    } else {
        PenWidgetUndoResolution.NoOp
    }
```

with:

```kotlin
/**
 * Undo loses to a live claim. A claimed payload is mid-Room-write, so restoring it would leave a
 * queued row the user believes was cancelled. Claim state — not the process-wide widget mutex —
 * is the correctness boundary here, for the same reason WorkManager cancellation is not one.
 */
fun resolveUndo(
    payload: PenWidgetCommitPayload?,
    commitId: String,
    nowMillis: Long,
): PenWidgetUndoResolution = when {
    payload == null -> PenWidgetUndoResolution.NoOp
    payload.commitId != commitId -> PenWidgetUndoResolution.NoOp
    payload.claimId != null && !isClaimStale(payload, nowMillis) -> PenWidgetUndoResolution.NoOp
    else -> PenWidgetUndoResolution.Restored(payload.seconds)
}
```

`isClaimStale` is already `internal` in the same file (`:52`) — no visibility
change needed. A *stale* claim must remain undoable: it means the claiming
process died, and the payload is otherwise unrecoverable until `flushOverdue`.

### Change 2 — `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt:131-149`

Thread the clock through:

```kotlin
suspend fun undo(
    appWidgetId: Int,
    commitId: String,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
```

and pass `nowMillis` as the third argument to `resolveUndo` at `:135-138`.
Keep the default so the provider call site at
`PenConsumptionWidgetProvider.kt:106` compiles unchanged; tests pass an explicit
value.

### Change 3 — update the existing tests

`app/src/test/java/com/example/widget/PenWidgetTransitionsTest.kt` calls
`resolveUndo` with two arguments at **lines 14, 18, and 114**. All three stop
compiling. Add a `nowMillis` argument to each — any value works for those cases
because the fixture payload has `claimId == null`.

### Tests to add

In `PenWidgetTransitionsTest.kt`:

```kotlin
@Test
fun undoLosesToALiveClaimAndWinsAgainstAStaleOne() {
    val claimed = payload().copy(
        claimId = "owner:claim-1",
        claimedAtEpochMillis = 1_000L,
    )

    // Live claim: the Room write is in flight, undo must not restore.
    assertEquals(
        PenWidgetUndoResolution.NoOp,
        resolveUndo(claimed, claimed.commitId, nowMillis = 1_500L),
    )

    // Stale claim: the claiming process is gone, undo is the only way back.
    assertEquals(
        PenWidgetUndoResolution.Restored(claimed.seconds),
        resolveUndo(claimed, claimed.commitId, nowMillis = 1_000L + CLAIM_STALE_MILLIS),
    )

    // Unclaimed payload is unaffected.
    assertEquals(
        PenWidgetUndoResolution.Restored(payload().seconds),
        resolveUndo(payload(), payload().commitId, nowMillis = 1_500L),
    )
}
```

In `app/src/androidTest/java/com/example/widget/PenWidgetStateRepositoryTest.kt`:

```kotlin
@Test
fun undoCannotStealAClaimedPayload() = runBlocking {
    val payload = payload()
    repository.submitCommit(21, payload)
    val claim = repository.claimCommit(21, payload.commitId, nowMillis = 10_000L)
    assertNotNull(claim)

    assertFalse(repository.undo(21, payload.commitId, nowMillis = 10_100L))
    assertEquals(payload.commitId, repository.read(21).pendingCommit?.commitId)
    assertEquals(0, repository.read(21).draftSeconds)
}
```

### Acceptance criteria

- `resolveUndo` returns `NoOp` for a live-claimed payload and `Restored` for a
  stale-claimed one.
- `PenWidgetStateRepository.undo` cannot remove a live-claimed pending key.
- **No observable behavior change in normal operation.** Under the existing
  mutex an undo already runs strictly before or after a claim, so every existing
  test must still pass unmodified except for the three signature updates.
- `PenWidgetCommitCoordinatorTest.undoLeavesRoomUntouchedAndRestoresDraft` still
  passes — it undoes an *unclaimed* payload.

### Verification

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon connectedDebugAndroidTest --tests '*PenWidgetStateRepositoryTest*'
```

---

## T2 — Provider test seam and provider tests

**Finding:** R-03. **Severity:** Medium. **Risk:** medium — requires a
production-code seam. **Depends on:** T1.

### Why

`PenConsumptionWidgetProvider` has no test in any source set. It owns action
routing, the submit path, the undo path, `onDeleted`'s force-commit, and the
serialization contract T1 just made explicit. Every existing widget test covers a
collaborator; none covers the file that wires them together.

### Read this before you start: two obstacles

**Obstacle 1 — `goAsync()` returns null outside a real broadcast dispatch.**
`BroadcastReceiver.goAsync()` hands back the framework's pending result and
nulls it; called on a directly-constructed receiver it returns `null`, and
`PenWidgetRuntime.launchReceiver` then dereferences it. So
`provider.onReceive(context, intent)` **cannot** be called directly from a test.

**Obstacle 2 — every collaborator is a hard-wired singleton.**
`PenWidgetStateRepository(context)` binds to the real
`penWidgetStateDataStore` delegate; `PenWidgetDataSource.loadPenState` reaches
`CannsheetGraph.get()` and the real Room database; `PenWidgetCommitCoordinator`,
`PenWidgetRuntime`, and `PenWidgetScheduler` are objects. A provider test run
as-is would mutate real app state.

### Recommended approach — extract the routing logic

Do **not** introduce a mutable global test-seam object; it invites cross-test
leakage in a suite that already shares a process. Instead extract the pure
routing into a class the provider delegates to:

Create `app/src/main/java/com/example/widget/PenWidgetActionRouter.kt`:

```kotlin
package com.example.widget

import android.content.Context
import android.content.Intent
import com.example.domain.PenQuickLogState
import com.example.domain.SubmissionDateTime
import com.example.domain.currentSubmissionDateTime
import com.example.domain.secondsToUses
import java.util.UUID

/**
 * Action routing for [PenConsumptionWidgetProvider], separated so it can be exercised without a
 * live broadcast dispatch. Collaborators are constructor parameters purely so tests can substitute
 * them; production uses the defaults.
 */
internal class PenWidgetActionRouter(
    private val stateRepository: (Context) -> PenWidgetStateRepository = { PenWidgetStateRepository(it) },
    private val loadPenState: suspend (Context) -> PenQuickLogState = { PenWidgetDataSource.loadPenState(it) },
    private val scheduleTimer: (Context, Int, String) -> Unit = PenWidgetRuntime::scheduleCommitTimer,
    private val cancelTimer: (Int) -> Unit = PenWidgetRuntime::cancelCommitTimer,
    private val scheduleWork: (Context, Int, String) -> Unit = PenWidgetScheduler::scheduleCommit,
    private val cancelWork: (Context, Int) -> Unit = PenWidgetScheduler::cancelCommit,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val submissionDateTime: (Long) -> SubmissionDateTime = ::currentSubmissionDateTime,
) {
    suspend fun handle(context: Context, action: String, appWidgetId: Int, intent: Intent) { … }
}
```

Move the bodies of `handleAction` (`PenConsumptionWidgetProvider.kt:92-112`) and
`submit` (`:114-149`) into it verbatim, substituting the injected collaborators
for the direct calls. The provider keeps a single `private val router =
PenWidgetActionRouter()` and its `onReceive` calls `router.handle(...)`.

**This must be a pure move.** The production defaults reproduce today's behavior
exactly. If the diff changes any ordering — `flushOverdue` still runs before
`handle`, `PenWidgetUpdater.update` still runs after — it is wrong.

### Tests to add

`app/src/androidTest/java/com/example/widget/PenWidgetActionRouterTest.kt`,
following the fixture pattern already used by `PenWidgetCommitCoordinatorTest`
(in-memory Room via `Room.inMemoryDatabaseBuilder`, isolated DataStore via
`PreferenceDataStoreFactory.create { File(context.cacheDir, "…-${UUID.randomUUID()}") }`,
deleted in `@After`).

1. **`incrementsThenSubmitCaptureTheFinalDraft`** — route two `ACTION_INCREMENT`
   intents then one `ACTION_SUBMIT`; assert the stored payload's `seconds` is
   `2 * STEP_SECONDS`. This is the W-06 regression guard at the level where it
   can actually regress.
2. **`undoDuringAClaimDoesNotRestoreTheDraft`** — submit, claim the payload
   directly through the repository, then route `ACTION_UNDO`; assert the pending
   payload survives and the draft stays `0`. **This is T1's assertion at the
   routing level and is the single most valuable test in this task.**
3. **`undoBeforeAClaimRestoresTheDraft`** — submit, route `ACTION_UNDO`; assert
   the draft is restored to the submitted seconds and the pending key is gone.
4. **`submitIsRejectedWhenNoCartIsLoaded`** — `loadPenState` returns
   `NoCartLoaded`; assert no payload is stored and no timer or work is scheduled.
5. **`submitIsRejectedWhenTheRateIsOff`** — `loadPenState` returns `Loaded` with
   `secondsPerUse = null`; same assertions.
6. **`resetClearsTheDraftButNotAPendingPayload`** — assert `ACTION_RESET` zeroes
   a draft, and is a no-op while a payload is pending.

Plus a JVM test, `app/src/test/java/com/example/widget/PenWidgetActionsTest.kt`:

7. **`openActionsAreNotBroadcastActions`** — assert `ACTION_OPEN_LOG` and
   `ACTION_OPEN_SETTINGS` are absent from `HANDLED_ACTIONS`, and that the five
   mutation actions are present. This is a cheap standing guard on the W-12 fix;
   re-adding an open action to that set would silently restore the background
   activity-launch path.

### Acceptance criteria

- The provider's behavior is unchanged: `flushOverdue` → route → update ordering
  preserved, same collaborators called in the same order.
- All seven tests pass.
- Test 2 passes both before and after T1 — before, via the mutex; after, via the
  resolver. If it fails before T1, your claim setup is wrong.
- No mutable global state introduced.

### Verification

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon connectedDebugAndroidTest
```

---

## T3 — Widget sizing decision

**Findings:** R-02, R-04. **Severity:** Medium. **Status: BLOCKED.**

### The blocker

Two independently-introduced numbers landed equal:

- `widget_subtitle_min_height` = **160dp** (`app/src/main/res/values/dimens.xml`),
  the compact breakpoint at `PenWidgetUpdater.kt:38-43`
- `minResizeHeight` = **160dp** (both `pen_consumption_widget_info.xml` files)

So `widget_pen_consumption_compact.xml` renders only when the launcher reports
`OPTION_APPWIDGET_MIN_HEIGHT` *below the provider's own declared floor*. On a
launcher that honors `minResizeHeight`, the compact layout is dead — along with
`showCompactConfirmation` (`PenWidgetRenderer.kt:90`), the
`pen_widget_queued_symbol` string, and two renderer tests.

But it may not be dead. `OPTION_APPWIDGET_MIN_HEIGHT` is the lower bound *across
orientations*, and landscape home screens and the Fold's cover screen have
shorter cells. On this specific device the compact path could be what the user
actually sees on one screen.

**Source cannot settle this. Do not guess.**

### Required measurement

Build a **sandbox/debug package with a non-production endpoint** — never the
signed production package, and never install a debug build over it (ADR-013).
Add temporary logging in `PenWidgetUpdater.update`:

```kotlin
Log.d("PenWidget", "minH=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, -1)} " +
    "maxH=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, -1)} compact=$compact")
```

Record the value for each of: main screen portrait, main screen landscape, cover
screen portrait, cover screen landscape, each at default placement and at minimum
resize. **Remove the logging before committing.**

### Decision procedure

**If every measured value is ≥ 160dp** — the compact layout is unreachable.
Choose one:

- *(preferred)* Make it reachable: set `minResizeHeight` to **110dp** in both
  provider-info files — the compact layout's intrinsic height is ≈110dp
  (8 padding + 18 name + 42 + 42) — and set the breakpoint to **150dp**. This
  restores the resize range the widget originally advertised, now with a layout
  that actually renders correctly at that size.
- *(alternative)* Delete `widget_pen_consumption_compact.xml`, the
  `showCompactConfirmation` branch, `pen_widget_queued_symbol`, and the two
  compact renderer tests.

**If any measured value is < 160dp** — the compact path is live and
`minResizeHeight` is being violated by that launcher. Set the breakpoint from the
measurements (comfortably above the largest compact-appropriate value and below
the smallest full-layout value), and leave `minResizeHeight` alone.

### Change, either way — stop the two constants drifting

```xml
<!-- app/src/main/res/values/dimens.xml -->
<dimen name="widget_min_resize_height">110dp</dimen>
<dimen name="widget_subtitle_min_height">150dp</dimen>
```

and reference `@dimen/widget_min_resize_height` from `android:minResizeHeight`
and `android:minHeight` in **both** `xml/pen_consumption_widget_info.xml` and
`xml-v31/pen_consumption_widget_info.xml`, so a future edit cannot desynchronize
them.

### R-04 — the layout no longer stretches

W-05's fix correctly removed `layout_weight="1"` from the counter row
(`widget_pen_consumption.xml:31-36`, now `wrap_content` + `minHeight="56dp"`),
which stopped it being crushed at the floor. But nothing absorbs *extra* height:
at `maxResizeHeight="360dp"` the content occupies ≈157dp and ~200dp is blank.

**Do not re-add `layout_weight` to the counter row.** LinearLayout distributes
negative excess through weights too — that is exactly what caused W-05. Add a
weighted spacer after the step row instead:

```xml
    <View
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
```

Apply the same to `widget_pen_consumption_compact.xml` if T3 keeps it.

### Acceptance criteria

- The measured values are recorded in the PR body — this is the device evidence
  the feature has never had.
- The two sizing constants have a single source and a stated relationship.
- No dead layout, dead string, or dead render branch remains.
- Screenshots at default placement, minimum resize, and maximum resize, on both
  Fold screens, light and dark.
- The temporary logging is gone from the committed diff.

### Verification

```bash
./gradlew --no-daemon lintDebug assembleDebug
./gradlew --no-daemon connectedDebugAndroidTest --tests '*PenWidgetRendererTest*'
```

---

## T4 — Robustness polish

**Findings:** R-05, R-06, R-07. **Severity:** Low. **Risk:** low.

### R-05 — fence the non-atomic `submitCommit` overload

`PenWidgetStateRepository.submitCommit(appWidgetId, payload)`
(`PenWidgetStateRepository.kt:82-95`) is the pre-W-06 API that accepts a payload
built *outside* the edit. Production uses only the lambda overload
(`PenConsumptionWidgetProvider.kt:127`); the payload form's remaining callers are
eight sites across `PenWidgetStateRepositoryTest` and
`PenWidgetCommitCoordinatorTest`.

Leaving it unmarked invites a future caller to reintroduce W-06. Annotate it:

```kotlin
/**
 * Test-only. Production must use the [submitCommit] overload that builds the payload inside the
 * edit, so a concurrent increment cannot be applied and then discarded.
 */
@VisibleForTesting(otherwise = VisibleForTesting.NONE)
suspend fun submitCommit(appWidgetId: Int, payload: PenWidgetCommitPayload): Boolean {
```

Add `import androidx.annotation.VisibleForTesting`. Lint will now flag production
use. Migrating the tests to the lambda form and deleting the overload is also
acceptable if the diff stays small.

### R-06 — release the claim under `NonCancellable`

`PenWidgetCommitCoordinator.kt:46-57` catches `Throwable`, which includes
`CancellationException`. On cancellation it calls
`stateRepository.releaseClaim(...)` — a suspend call in an already-cancelled
coroutine, which throws immediately and is swallowed by the surrounding
`runCatching`. The claim then sticks until `CLAIM_STALE_MILLIS` (60s), during
which `flushOverdue` skips the payload and the widget shows "Saving…".

Reachability is low — `cancelCommitTimer` is only called from undo and
`onDeleted`, both under the mutex, so neither can interrupt a commit mid-flight,
and process death changes the claim owner and makes the claim immediately
recoverable. Fix it anyway; it is one line:

```kotlin
} catch (error: Throwable) {
    withContext(NonCancellable) {
        runCatching {
            stateRepository.releaseClaim(
                appWidgetId = appWidgetId,
                commitId = claim.payload.commitId,
                claimId = claim.claimId,
            )
        }
    }
    throw error
} finally {
    runCatching { updateWidget(context.applicationContext, appWidgetId) }
}
```

Add `import kotlinx.coroutines.NonCancellable` and
`import kotlinx.coroutines.withContext`. Leave the `finally` block as-is — a
render during cancellation is not worth protecting.

### R-07 — hoist `receiveAsFlow()` out of the composable argument

`MainActivity.kt:34` passes `routeRequests.receiveAsFlow()` directly as a
`CannsheetApp` argument. That returns a new instance per call, and
`AppNavigation` keys a `LaunchedEffect` on it — so if the `setContent` content
lambda ever recomposes, the effect restarts and a `Channel` element in flight
between `receive()` and `emit()` is dropped.

Today the lambda reads no snapshot state so it should not recompose. That is a
property of the current code, not a guarantee:

```kotlin
private val routeRequests = Channel<String>(capacity = Channel.BUFFERED)
private val routeRequestFlow = routeRequests.receiveAsFlow()
```

and pass `routeRequestFlow` at `:34`.

### Acceptance criteria

- Lint reports no new warnings; `@VisibleForTesting` does not trip on the
  existing test call sites.
- Existing tests pass unmodified.

### Verification

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug
```

---

## T5 — Text, accessibility, and cleanup

**Findings:** R-08, R-09, R-10. **Severity:** Low.

### R-08 — plurals with identical forms

`pen_widget_submit_seconds`, `pen_widget_undo_seconds`, and
`pen_widget_saving_seconds_description` declare `one` and `other` with identical
text ("Submit %1$d second pen log"). As English that is defensible — it reads as
the attributive compound "a 30-second pen log" — but two neighbouring plurals
(`pen_widget_reset_seconds_description`, `pen_widget_logging_seconds_description`)
*do* differentiate, so the file contradicts itself and a translator has no signal
about which reading was intended.

Pick one and apply it consistently. Recommended: hyphenate the compound and
demote the three to plain strings, since the plural distinction genuinely does
not apply to an attributive form.

```xml
<string name="pen_widget_submit_seconds">Submit %1$d-second pen log</string>
<string name="pen_widget_undo_seconds">Undo %1$d-second pen log</string>
<string name="pen_widget_saving_seconds_description">Saving %1$d-second pen log</string>
```

Update the three `getQuantityString` call sites in `PenWidgetRenderer.kt`
(`:115-119`, `:130-134`, `:139-144`, `:184-188`) to `getString`. Note
`pen_widget_saving_seconds_description` is used **twice** — on the submit button
and on the counter panel — so there are four call sites for three strings.

### R-09 — action hint on the name tap-through

`PenWidgetRenderer.kt:210-213` makes the product name an app-open target, closing
W-25's missing-tap-through gap. But its only accessible text is the cart name, so
a screen reader announces "Blue Dream cart" with no indication it is actionable.

Add:

```xml
<string name="pen_widget_open_app_description">%1$s. Double tap to open Cannsheet Mobile.</string>
```

```kotlin
setContentDescription(
    R.id.widget_pen_name,
    context.getString(R.string.pen_widget_open_app_description, productName),
)
```

### R-10 — delete the `ui` delegation shims

`ui/QuantityUnits.kt` and `ui/PenQuickLog.kt` are pure forwarders to
`com.example.domain`, kept so PR #55 did not have to touch import lists. The
W-19 cycle is genuinely broken — `com.example.widget` imports
`com.example.domain` and no longer imports `com.example.ui` — so this is
cleanup, not correctness.

Complete consumer list at `00f7860`:

| File | Symbol | Lines |
|---|---|---|
| `ui/ConsumptionScreen.kt` | `formatQuantityInInputUnit` | 186, 195, 844 |
| `ui/SettingsScreen.kt` | `formatQuantityInInputUnit` | 539 |
| `ui/CannsheetViewModel.kt` | `buildPenQuickLogState` | 167 |
| `test/…/ui/QuantityUnitsTest.kt` | `usesToSeconds`, `secondsToUses`, `formatQuantityInInputUnit` | 9, 10, 15, 20, 32, 33, 39 |
| `test/…/ui/PenQuickLogTest.kt` | `resolveLoadedPenProduct`, `buildPenQuickLogState` | 22, 39, 53, 61, 77, 91 |

Note `usesToSeconds` and `secondsToUses` have **no production consumers** through
the shim — only `QuantityUnitsTest`.

Add `import com.example.domain.<symbol>` to the three production files, then
delete both shim files. The two test files sit in package `com.example.ui` and
resolve the shims without imports, so they need explicit imports added — or move
them to `app/src/test/java/com/example/domain/`, which is the better home now
that the code under test lives there. Either is acceptable; moving is cleaner.

### Acceptance criteria

- `strings.xml` is internally consistent on plural usage.
- The name tap-through announces both identity and action.
- No delegation shims remain; no production file imports from `com.example.ui`
  for these symbols.
- Renderer tests updated for the string changes.

### Verification

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug
./gradlew --no-daemon connectedDebugAndroidTest --tests '*PenWidgetRendererTest*'
```

---

## 5. Full verification protocol

Run before every commit:

```bash
git diff --check
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

Run before opening the PR:

```bash
./gradlew --no-daemon connectedDebugAndroidTest
node tests/backend_contract_test.js
node tests/backend_recovery_test.js
node tests/backend_analytics_test.js
python3 -m unittest tests/test_backend_sync_benchmark.py
```

Record the **exact** command and the **exact** result for each. If a suite did
not run, say which and why. Do not describe an unexecuted check as successful —
this is an explicit `AGENTS.md` requirement and the most common way this
repository's PRs fail review.

Before committing, review the complete diff and confirm it contains no secrets,
no version change, no release or tag change, no signing change, and no endpoint,
application ID, package, or environment change.

## 6. Device validation

`docs/WIDGET_REVIEW_PLAN.md` §6 carries a 14-step checklist that has **never been
executed**, and `docs/WIDGET_FOLLOWUP_PLAN.md` §5 adds eight more. Two releases
have now shipped on source review plus emulator tests alone.

T3 cannot be completed without at least steps 15–17 and 21. If you run a device
session for T3, run the whole checklist while you are there — the marginal cost
is small and it closes the longest-standing gap in this feature.

Use a sandbox/debug package with a non-production endpoint. Do not use the signed
production package for synthetic widget submissions, and do not install a
debug-signed APK over it.

## 7. Commit and pull request structure

One PR per task group, in this order:

| PR | Tasks | Title |
|---|---|---|
| 1 | T1 + T2 | `fix: arbitrate widget undo against claim state` |
| 2 | T4 + T5 | `chore: widget robustness, text, and accessibility polish` |
| 3 | T3 | `fix: reconcile pen widget sizing with its compact layout` |

T1 and T2 ship together because T2's central test is T1's assertion; splitting
them leaves the new behavior unproven for one PR. T3 ships last because it is
gated on a device session and should not block the rest.

Every PR body must fill in `.github/pull_request_template.md`: summary,
motivation, scope and decisions, exact automated test commands and results,
manual validation, screenshots for visible UI changes, and risks and data-safety
considerations. PR 3 requires screenshots; PRs 1 and 2 do not (no visible
change), but must say so explicitly rather than leaving the section blank.

## 8. Documentation to update

Do this in the PR that makes the corresponding change, not as a follow-up:

- **`docs/DECISIONS.md`** — with PR 1, amend ADR-013's arbitration description:
  claim state, not the process mutex, is what makes undo-vs-commit safe. With
  PR 3, record the sizing decision and the measured values as a new ADR.
- **`docs/ARCHITECTURE.md`** — with PR 1, the deferred-commit section should name
  the serialization boundary and state what it does and does not guarantee, so
  the invariant is written where a future change will encounter it.
- **`docs/PROJECT_STATE.md`** — after each PR.
- **`docs/HANDOFF.md`** — before transferring work; use
  `.agents/skills/project-handoff/SKILL.md`.
- **`docs/WIDGET_FOLLOWUP_PLAN.md`** — add an implementation-resolution section
  mirroring the one in `docs/WIDGET_REVIEW_PLAN.md`, recording any place you
  deviated from this guide and why.

## 9. Explicit non-goals

Do not do these as part of this work:

- **W-24 / R-11** (per-tap DataStore scan and Room reads) stays deferred. It is a
  performance question, and the ground shifted when the mutex landed: the
  profiling should now measure *queueing under contention*, not per-tap cost. It
  needs T3's device session first.
- **No `onRestored` migration.** `pen_widget_state` is excluded from backup, so
  restore semantics do not apply to its pending payloads. This was decided in
  PR #55 and should stay decided.
- **Do not add Glance or any new UI dependency.** ADR-013 chose platform
  `RemoteViews` for API 24 compatibility.
- **Do not refactor `SyncEngine`, `SyncScheduler`, or the acknowledgement rules.**
  Nothing here touches sync.
- **Do not change the widget's five-state model** (`Unavailable`, `NoCart`,
  `RateOff`, `Composing`, `AwaitingCommit`) or the 0..600s / 10s-step draft
  bounds.

## 10. If something does not match this guide

This guide was written from source at `00f7860`. If a file does not look as
described:

1. Check whether `main` has moved past `00f7860` and re-anchor by symbol name.
2. If the code genuinely differs from the description, **the guide is wrong, not
   the code** — record the discrepancy in the PR body rather than forcing the
   change through.
3. If a task starts to require a Room migration, a wire-contract change, or an
   endpoint change, stop and escalate. None of these tasks should need one, and
   needing one means the approach has drifted.
