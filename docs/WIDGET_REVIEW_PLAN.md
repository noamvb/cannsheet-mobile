# Pen widget implementation review and remediation plan

Reviewed: 2026-08-12
Subject: the home-screen pen consumption widget shipped in
[PR #52](https://github.com/noamvb/cannsheet-mobile/pull/52) (`0e9bb65`) and
released as v1.2.25.
Status: implemented in [PR #55](https://github.com/noamvb/cannsheet-mobile/pull/55),
with the review adjustments recorded below. Physical-launcher validation remains
outstanding and is deliberately not inferred from source, local compilation, or
emulator tests.

## 1. Scope and method

This is a source review of every file the widget feature touched, plus the
resources, manifest, tests, and the four shared-context documents updated in
`c3fa720`. Reviewed:

- `app/src/main/java/com/example/widget` (13 files, 1,012 lines)
- `app/src/main/java/com/example/data/ConsumptionLogger.kt`,
  `ProductTypeCodes.kt`, `Repository.kt`, `CannsheetGraph.kt`,
  `ConsumptionPreferencesRepository.kt`, `sync/SyncWorker.kt`,
  `sync/SyncScheduler.kt`
- `app/src/main/java/com/example/MainActivity.kt`, `CannsheetApplication.kt`,
  `ui/AppNavigation.kt`, `ui/CannsheetViewModel.kt`, `ui/QuantityUnits.kt`,
  `ui/PenQuickLog.kt`, `ui/ConsumptionDateTime.kt`
- `app/src/main/AndroidManifest.xml`, `app/src/main/res` widget layouts,
  drawables, colors, dimens, strings, and both `appwidget-provider` files
- `app/src/test/java/com/example/widget` (6 files) and
  `app/src/androidTest/java/com/example/widget` (4 files)

### What could not be verified here

No Gradle build, Android lint, unit test, or emulator run was executed for this
review; the available JVM in this session is not the JDK 17+ that Gradle 9.3.1
requires. Findings below are read from source and from the arithmetic shown
inline. Every finding marked **needs device confirmation** is a prediction about
runtime or launcher behavior that must be checked on a sandbox build before the
fix is considered proven. Per `docs/HANDOFF.md`, no physical widget walkthrough
has ever been performed on this feature, so there is no prior device evidence to
lean on either.

## 2. Verdict

The core arbitration design is sound and is the best part of this
implementation. `resolveUndo`/`resolveCommit` are pure and correct, every
read-modify-write goes through a single `DataStore.edit`, WorkManager
cancellation is correctly demoted to an optimization, and seconds genuinely
never cross into Room, the offline queue, or the wire — `logPayload` passes
`payload.uses` and nothing else. The four-way color qualifier coverage
(`values`, `values-night`, `values-v31`, `values-night-v31`) is complete, and
the per-widget draft isolation is right.

The problems are concentrated in four places: **what happens when the commit
path fails or runs late**, **the layout's own declared size**, **the
Activity-launch path**, and **strings that were written but never wired up**.
Three findings are data-safety issues that contradict guarantees `AGENTS.md` and
`docs/DECISIONS.md` already claim to hold.

| ID | Severity | Finding |
|----|----------|---------|
| W-01 | High | Payload is deleted before the Room write succeeds; a failed write loses the submission silently |
| W-02 | High | The documented "stable consumption ID" does not exist; retries can duplicate |
| W-03 | High | A late commit silently overwrites the user's current loaded pen cart |
| W-04 | High | WorkManager's 5-second delay is not a 5-second timer on a Doze/OEM-restricted device |
| W-05 | High | The layout does not fit its own declared minimum and target size |
| W-06 | Medium | Submit captures a stale seconds value (read-then-write race) |
| W-07 | Medium | Undo has no grace margin and loses at the boundary non-deterministically |
| W-08 | Medium | Wall-clock deadline can strand a payload permanently and block all future submits |
| W-09 | Medium | Removing the widget discards an in-flight submission and orphans its work |
| W-10 | Medium | Concurrent broadcast coroutines can leave a stale value rendered |
| W-11 | Medium | Uncaught exceptions in receiver coroutines crash the process |
| W-12 | Medium | `startActivity` from a background receiver is subject to background-launch restrictions |
| W-13 | Medium | `EXTRA_START_ROUTE` is never consumed, so rotation re-applies the widget's route |
| W-14 | Medium | `onNewIntent` is dead code; every widget open destroys in-app state |
| W-15 | Medium | Settings rate changes and catalog refreshes never refresh the widget |
| W-16 | Medium | The subtitle — the only success confirmation — is hidden at the default size |
| W-17 | Medium | Six widget strings are declared but never used; all widget copy is hardcoded English |
| W-18 | Medium | The counter's content description announces the action, never the value |
| W-19 | Medium | `ui → widget → ui` and `data → widget → data` package cycles |
| W-20 | Low | API 31+ widget-picker preview renders an empty card |
| W-21 | Low | Chronometer counts past zero into negative time |
| W-22 | Low | Payload version bump silently discards an open submission |
| W-23 | Low | Seconds round-trip is lossy for non-divisor rates (10s at 7s/use redisplays as 9.999997s) |
| W-24 | Low | Per-tap cost is a full DataStore scan plus three Room reads |
| W-25 | Low | Layout and resource polish (invalid `fontFamily`, dead attribute, inconsistent defaults, no tap-through) |

### Implementation resolution

The remediation was consolidated into PR #55 at the user's direction rather
than split across the six originally proposed PRs. W-01 through W-23 and the
applicable W-25 items are implemented with these review adjustments:

- Claims use both a unique claim token and a process-owner token. A different
  process may immediately recover an abandoned claim; the same process waits
  for the bounded stale-claim threshold. Completion must match the exact claim.
- Widget submissions never update the current loaded-cart preference. The cart
  is a submit-time input; a delayed commit must not mutate current selection.
- The compact layout retains decrement, counter/Undo, increment, and submit
  controls. Removing the step controls would make a newly placed compact widget
  unable to build a duration.
- `singleTop` is paired with an explicit one-shot navigation-event flow;
  `setContent` remains an `onCreate` operation and `onNewIntent` does not rebuild
  the Compose hierarchy.
- The repository convenience `addConsumption(action)` overload remains because
  Android regression tests use that API. W-24's caching/long-press optimization
  remains deferred: it is a performance enhancement, not a correctness fix,
  and would widen the state model without device profiling evidence.
- No `onRestored` migration was added for the short-lived widget DataStore,
  which is explicitly excluded from backup. Restore semantics therefore do not
  apply to its pending payloads.

The original findings and proposed fixes below are retained as the review
record. Where they differ from this section, this resolution is authoritative.

### Follow-up execution record

The separate follow-up execution guide supplied for the v1.2.26 base refers to
`docs/WIDGET_FOLLOWUP_PLAN.md`, but that path is not present on the base checkout
or the review branch. This document remains the canonical review and resolution
record. The first follow-up implementation keeps the existing process-local
serialization order while making the DataStore claim state authoritative for
undo-versus-commit arbitration, and extracts provider action routing into an
injected `PenWidgetActionRouter` so the provider's mutation paths can be tested
without a live broadcast dispatch. The follow-up remains unreleased at this
stage; sizing is still gated on physical Fold measurements. PR1 is now merged
as #59. The second follow-up group applies the cancellation-safe claim release,
stable route-flow ownership, resource/accessibility cleanup, and direct domain
imports described by T4/T5; it does not alter the widget's data contracts or
release metadata. PR2 is now merged as #60. The sandbox-only sizing evidence
for T3 is cover portrait `300dp`, main portrait `274dp`, main landscape
`259dp`, and cover landscape `300dp`; the launcher did not expose a usable
minimum-resize handle, so that value remains explicitly unmeasured. Since all
observed defaults are at least `160dp`, T3 applies the preferred `110dp`
minimum-resize height, a `150dp` compact breakpoint, and weighted bottom
spacers in the full and compact layouts.

## 3. Findings

### A. Data safety and correctness

#### W-01 (High) — The payload is deleted before the Room write succeeds

`PenWidgetStateRepository.takeCommit` removes the pending key inside its
`DataStore.edit` (`app/src/main/java/com/example/widget/PenWidgetStateRepository.kt:129`)
and only then does `PenWidgetCommitCoordinator.logPayload` call
`consumptionLogger.log` (`PenWidgetCommitCoordinator.kt:43-54`). If the Room
insert throws — full disk, IO error, a database that failed to open — the
payload is already gone.

The worker's retry cannot recover it. `PenWidgetCommitWorker.doWork` catches the
throwable and returns `Result.retry()` (`PenWidgetCommitWorker.kt:22`); the
retry re-enters `PenWidgetCommitCoordinator.commit`, finds no pending payload,
and returns `Result.success()`. The user saw "Queued ✓" and there is no row.

The same shape exists in `flushOverdue` (`PenWidgetCommitCoordinator.kt:31-41`):
a throw from `logPayload` loses the taken payload *and* aborts every remaining
pending widget in the loop, because the exception propagates out of `forEach`.

This directly contradicts `AGENTS.md`: *"A timeout must not cause duplicate
spreadsheet rows or silently discard a queued action"* and *"Delete a pending
purchase, consumption, or finish action only after the existing acknowledgement
rules prove that the server committed it."*

**Proposed fix — two-phase claim.** Replace the destructive take with a claim:

```kotlin
// PenWidgetCommitPayload gains: val claimedAtEpochMillis: Long?
suspend fun claimCommit(appWidgetId: Int, commitId: String?, nowMillis: Long): PenWidgetCommitPayload?
suspend fun releaseClaim(appWidgetId: Int, commitId: String)   // failure path
suspend fun completeCommit(appWidgetId: Int, commitId: String, nowMillis: Long)  // success path
```

`claimCommit` stamps `claimedAtEpochMillis` and returns the payload; the pending
key stays. `logPayload` runs; on success `completeCommit` removes the key and
sets `lastQueuedAtMillis`; on failure `releaseClaim` clears the stamp so the
next flush retries. A claim older than `CLAIM_STALE_MILLIS` (propose 60s) is
treated as abandoned and re-claimable, which covers process death between claim
and completion. This makes the coordinator's failure mode "retry later" instead
of "lose it", and requires W-02 so the retry cannot duplicate.

#### W-02 (High) — The documented "stable consumption ID" does not exist

`docs/HANDOFF.md:33` and ADR-013 item 2 in `docs/DECISIONS.md` both state that
submit captures a *"stable consumption ID"* in the payload. It does not.
`PenWidgetCommitPayload` (`PenWidgetDraft.kt:17-29`) has a `commitId`, which is
used only for undo/commit arbitration and never reaches Room.
`ConsumptionLogger.log` mints a fresh `eventId = UUID.randomUUID().toString()`
at write time (`ConsumptionLogger.kt:40`).

So the idempotency property the ADR claims is not implemented. Today it is
latent — nothing retries the write — but it becomes a live duplicate-row bug the
moment W-01's retry path exists, and it is already wrong as documentation.

**Proposed fix.** Add `eventId: String` to the payload (generated at submit
alongside `commitId`), bump `PEN_WIDGET_PAYLOAD_VERSION` to 2, and give
`ConsumptionLogger.log` an optional `eventId: String = UUID.randomUUID().toString()`
parameter. A retried write then reuses the same immutable ID, and the existing
queue/acknowledgement rules dedupe it exactly as they do for every other entry
point. Correct `docs/DECISIONS.md` and `docs/HANDOFF.md` in the same change —
right now they describe a guarantee the code does not provide.

#### W-03 (High) — A late commit silently overwrites the loaded pen cart

`ConsumptionLogger.log` writes `setLoadedPenProductId(productId)` for every
non-finished pen log (`ConsumptionLogger.kt:49-55`). For the in-app path that is
correct: the write happens at the moment the user acts. For the widget path the
write happens at *commit* time, which can be arbitrarily later than submit time
— five seconds normally, but minutes or hours if the worker is deferred (W-04)
and the payload is only picked up by a lazy `flushOverdue` at the next app
start.

Sequence: user submits from the widget at 09:00 with cart A loaded; the worker
is deferred; at 11:30 the user opens the app and deliberately switches the
loaded cart to B; `CannsheetApplication.onCreate` runs `flushOverdue`, the
payload commits, and `loadedPenProductId` is silently set back to A. The widget
and the Log screen now both show the wrong cart, and the next quick log goes to
the wrong product.

**Proposed fix.** Make the loaded-cart side effect conditional and explicit
rather than implicit in the logger:

- Add a `updateLoadedCart: Boolean = true` parameter to `ConsumptionLogger.log`,
  or better, capture `expectedLoadedProductId` in the payload at submit time and
  perform the preference write as a compare-and-set: only re-point the loaded
  cart if it still holds the value the user had when they submitted.
- Additionally suppress the write entirely when
  `nowMillis - payload.commitAtEpochMillis > LOADED_CART_FRESHNESS_MILLIS`
  (propose 5 minutes). A stale flush should still record consumption; it should
  not rewrite current preference state.

#### W-06 (Medium) — Submit captures a stale seconds value

`PenConsumptionWidgetProvider.submit` reads pen state, then reads the draft
(`PenConsumptionWidgetProvider.kt:115-119`), builds the payload from
`draft.draftSeconds` (`:131`), and only then calls `submitCommit`, which clears
the draft key unconditionally (`PenWidgetStateRepository.kt:83`).

Each broadcast runs its own coroutine (`PenConsumptionWidgetProvider.kt:76`), so
a `+10` tap that lands between the read and the edit is atomically applied to
the draft and then atomically thrown away by `submitCommit`. The logged quantity
is then lower than the number the user last saw on the widget. The window is
widened by `loadPenState`, which performs three Room `Flow.first()` collections
before the draft is even read (`PenWidgetDataSource.kt:12-16`).

The existing test `PenWidgetStateRepositoryTest.concurrentAdjustCallsDoNotLoseIncrements`
proves the *repository* is atomic; nothing tests the provider's compound
read-then-write.

**Proposed fix.** Move payload construction inside the edit. Give the repository
a `submitCommit(appWidgetId, buildPayload: (seconds: Int) -> PenWidgetCommitPayload?)`
overload that reads the draft inside `dataStore.edit`, calls the builder, and
writes the pending key in the same transaction, returning the payload it stored.
Resolve the product/rate before the edit (they are not part of the race);
resolve seconds and `secondsToUses` inside it.

#### W-07 (Medium) — Undo has no grace margin

`onReceive` runs `flushOverdue` *before* `handleAction`
(`PenConsumptionWidgetProvider.kt:78-79`), and `resolveCommit` treats a payload
as overdue as soon as `commitAtEpochMillis <= nowMillis`
(`PenWidgetTransitions.kt:36`). `commitAtEpochMillis` is exactly
`now + UNDO_WINDOW_MILLIS` (`PenConsumptionWidgetProvider.kt:126`), and the
WorkManager request uses the same 5,000 ms (`PenWidgetScheduler.kt:30`).

An Undo tap at T+4.9s whose broadcast reaches the receiver at T+5.02s — normal
launcher IPC and receiver dispatch latency — is flushed to a commit by its own
broadcast before `handleAction` ever sees it. The chronometer showed 0.1s
remaining; the tap did nothing. The outcome in the last few hundred milliseconds
is non-deterministic.

**Proposed fix.** Separate the *displayed* window from the *commit deadline*:

```kotlin
const val UNDO_WINDOW_MILLIS = 5_000L      // what the countdown shows
const val COMMIT_GRACE_MILLIS = 1_500L     // slack for IPC + dispatch
// commitAtEpochMillis = now + UNDO_WINDOW_MILLIS + COMMIT_GRACE_MILLIS
// WorkManager initial delay  = UNDO_WINDOW_MILLIS + COMMIT_GRACE_MILLIS
// AwaitingCommit.remainingMillis = commitAt - COMMIT_GRACE_MILLIS - now
```

The user sees a 5-second countdown; a tap issued inside it wins even if it
arrives 1.5s late. Add a `PenWidgetTransitionsTest` case at the boundary.

#### W-08 (Medium) — A wall-clock deadline can strand a payload permanently

`commitAtEpochMillis` is `System.currentTimeMillis()`-based
(`PenConsumptionWidgetProvider.kt:121-126`), and `resolveCommit`'s overdue rule
compares against wall clock (`PenWidgetTransitions.kt:36`). A backwards clock
change — manual adjustment, or an NTP correction after a battery-out reboot —
makes `commitAtEpochMillis` sit far in the future, so `flushOverdue` never
commits it.

The commit-ID worker is then the only rescue, and if that work is gone
(force-stop, WorkManager database cleared, "clear cache" on some OEM builds) the
payload is permanent. Because `submit` returns early whenever
`draft.pendingCommit != null` (`PenConsumptionWidgetProvider.kt:119`), the
widget is then wedged in `AwaitingCommit` forever: every button is inert, and
the only recovery is removing and re-adding the widget — which under W-09
destroys the payload rather than committing it.

**Proposed fix.**

1. Store `submittedAtEpochMillis` in the payload as well as the deadline.
2. Add an unconditional ceiling to `resolveCommit`: if
   `nowMillis - payload.submittedAtEpochMillis > MAX_PENDING_AGE_MILLIS`
   (propose 10 minutes) **or** that difference is negative by more than a small
   tolerance, treat the payload as overdue and commit it regardless of
   `commitAtEpochMillis`. A stranded submission should always resolve toward
   "recorded", never toward "stuck".
3. Optionally carry `SystemClock.elapsedRealtime()` alongside for the in-session
   case; the ceiling above is the cheaper fix and covers the wedge.

#### W-09 (Medium) — Removing the widget discards an in-flight submission

`onDeleted` (`PenConsumptionWidgetProvider.kt:48-61`) calls `flushOverdue`,
which by definition skips a payload that is *not yet* overdue, and then calls
`state.clear(appWidgetId)`, which removes the pending key
(`PenWidgetStateRepository.kt:158`). A submit made in the five seconds before
the widget is removed is deleted, not recorded. `PenWidgetScheduler.cancelCommit`
is also never called, so the unique work survives as an orphan that later
resolves to a no-op.

**Proposed fix.** In `onDeleted`, force-commit any pending payload before
clearing — pass a "deleting" flag through `takeCommit`/`claimCommit` that
ignores the deadline — then `PenWidgetScheduler.cancelCommit(context, id)` for
each removed ID, then `clear`. Removing a widget is a UI action; it must not be
a data-deletion action.

#### W-22 (Low) — A payload version bump silently discards an open submission

`PenWidgetPayloadCodec.decode` returns `null` for any payload whose `version`
is not the current constant (`PenWidgetPayloadCodec.kt:16-17`), and
`submitCommit` deliberately overwrites an undecodable raw value
(`PenWidgetStateRepository.kt:78-82`). That is the right behavior for corruption
but the wrong behavior for a version bump: an app update installed while a
payload is open drops a real submission with no trace.

W-02 requires a bump to version 2, so this needs an answer now.

**Proposed fix.** Decode known older versions into the current shape (v1 has no
`eventId` — mint one at decode time, which is safe because a v1 payload has
never been written to Room) and reserve `null` for genuinely unparseable
values.

### B. Delivery timing

#### W-04 (High) — WorkManager's initial delay is not a five-second timer

`PenWidgetScheduler.commitRequest` uses
`setInitialDelay(UNDO_WINDOW_MILLIS, MILLISECONDS)` on a `OneTimeWorkRequest`
(`PenWidgetScheduler.kt:28-37`). WorkManager schedules through JobScheduler; its
initial delay is a *minimum*, not a deadline. Under Doze, App Standby buckets,
or OEM background management the work can be deferred well past five seconds.
The production device is a Samsung SM-F966W, and Samsung's background management
is among the most aggressive in the ecosystem.

When that happens, all three of these are true at once:

- the consumption is not in Room or the offline queue, so it is not in the sync
  queue either;
- the widget stays in `AwaitingCommit`, because nothing re-renders it; and
- the chronometer keeps counting past zero into negative values (W-21).

Recovery is only `flushOverdue` on the next widget broadcast or app start
(`PenConsumptionWidgetProvider.kt:28,54,78`, `CannsheetApplication.kt:21-23`),
so a user who submits and then puts the phone down has an unrecorded log and a
visibly wrong widget until they next touch either surface.

**Needs device confirmation** — the exact deferral depends on bucket and OEM
policy. But the design should not depend on WorkManager latency regardless.

**Proposed fix — three tiers, in order of precedence:**

1. **Process-local timer (primary).** The process is alive at submit time — the
   broadcast just ran. Add an application-scoped
   `PenWidgetCommitTimer` holding one `Job` per widget ID that does
   `delay(window)` then `PenWidgetCommitCoordinator.commit(context, id, commitId)`.
   Cancel it on undo. This makes the common case exact.
2. **WorkManager (durable backstop).** Keep the unique work exactly as is, at
   `UNDO_WINDOW + COMMIT_GRACE` (W-07). It covers process death.
3. **Lazy flush (recovery).** Keep `flushOverdue`, with the W-08 ceiling.

Do not use `AlarmManager.setExactAndAllowWhileIdle`: it needs
`SCHEDULE_EXACT_ALARM` on API 31+, and this feature does not justify an exact
alarm permission. Do not hold the broadcast open with
`goAsync()` + `delay(5s)` either — manifest receivers dispatch serially per
process, so holding the submit broadcast would delay the *undo* broadcast queued
behind it, which is exactly backwards.

#### W-21 (Low) — The chronometer counts past zero

`PenWidgetRenderer` sets the chronometer base from
`SystemClock.elapsedRealtime() + remainingMillis` with `countDown = true` and
`started = true` (`PenWidgetRenderer.kt:93-96`). Once the base passes, a
counting-down `Chronometer` renders negative elapsed time. Under W-04 the widget
can sit showing `-00:41` and rising. It also keeps ticking in the launcher
process indefinitely.

**Proposed fix.** When `remainingMillis == 0L`, render a terminal state instead:
stop the chronometer (`started = false`), hide it, and show a "Saving…" label in
the counter panel with the Undo button disabled. Combined with W-04 tier 1 this
should be rare, but it must not be visually undefined when it happens.

### C. Concurrency and rendering

#### W-10 (Medium) — Concurrent broadcasts can leave a stale value on screen

Every entry point creates a fresh `CoroutineScope(SupervisorJob() + Dispatchers.Default)`
(`PenConsumptionWidgetProvider.kt:26,52,76,156`), and `PenWidgetUpdater` has its
own long-lived scope (`PenWidgetUpdater.kt:12`). The state mutation is atomic,
but mutation and render are two separate steps, and nothing orders the renders.

Two rapid `+` taps: broadcast 1 sets 10 and starts rendering; broadcast 2 sets
20 and starts rendering; if 2's `updateAppWidget` lands before 1's, the widget
displays `10s` while the stored draft is `20`. It stays wrong until the next
broadcast. The user then submits believing they have 10s selected — and W-06
means the captured value may be a third number.

**Proposed fix.** Serialize all widget work through a single application-scoped
context: one `CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))`,
or a `Mutex` keyed per widget ID held across mutate-then-render. Renders become
strictly ordered and last-writer-wins matches the last user action. This also
subsumes the "render optimistically from the returned value" idea, which alone
would not fix ordering.

#### W-11 (Medium) — Uncaught exceptions in receiver coroutines crash the process

All four launch sites use `try { … } finally { pendingResult.finish() }` with no
`catch` and no `CoroutineExceptionHandler`
(`PenConsumptionWidgetProvider.kt:26-33,52-60,76-84,156-163`). `SupervisorJob`
does not swallow the exception of the root coroutine; it reaches the thread's
uncaught handler and takes the process down. A DataStore IO error or a Room
failure during a background widget tap becomes a user-visible app crash with no
foreground UI to explain it.

`PenWidgetUpdater.updateAll` has the same hazard from the view-model side: at
`CannsheetViewModel.kt:339` it sits inside `runCatching`, but the work happens
in `updateScope.launch`, so anything it throws escapes the `runCatching`
entirely.

**Proposed fix.** Add a shared handler on the widget scope, and wrap each body
in `runCatching { … }.onFailure { /* log */ }` while keeping
`pendingResult.finish()` in `finally`. A widget refresh failing is never worth a
crash.

#### W-24 (Low) — Per-tap cost

Each broadcast performs `flushOverdue` — a full `dataStore.data.first()` plus a
Moshi decode of every pending key (`PenWidgetStateRepository.kt:136-152`) —
followed by an `adjustDraftSeconds` edit, then `update` which does another
DataStore read plus `loadPenState`'s three Room `Flow.first()` collections
(`PenWidgetDataSource.kt:12-16`), a `getAppWidgetOptions` binder call, and a
RemoteViews push. Reaching the 600-second ceiling takes 60 taps, so that is 60
of these round trips.

**Proposed fix.** Cheap wins, all optional: short-circuit `flushOverdue` when no
key starts with `pending_commit_` (one already-loaded snapshot, no decode);
cache `loadPenState` for a couple of seconds keyed on the DataStore/Room
generation; and consider a repeat-tap accelerator (long-press → 60s steps) so
large values need fewer round trips.

### D. Lifecycle and navigation

#### W-12 (Medium) — `startActivity` from a background broadcast receiver

`openMainActivity` calls `context.startActivity` (`PenConsumptionWidgetProvider.kt:140-147`)
from inside the `Dispatchers.Default` coroutine, after `flushOverdue` has
already suspended. Android 10+ restricts background activity starts; whether a
widget-tap broadcast confers a launch exemption depends on API level, launcher,
and OEM, and Android 14 tightened how PendingIntent senders pass launch
privileges. Routing an app-open through `getBroadcast` → coroutine →
`startActivity` puts this on the fragile side of that boundary for no benefit.

**Needs device confirmation** on the Samsung target — but the fix removes the
question rather than answering it.

**Proposed fix.** Use `PendingIntent.getActivity` for `ACTION_OPEN_LOG` and
`ACTION_OPEN_SETTINGS` so the launcher starts the activity directly, with the
route as an intent extra. Delete `ACTION_OPEN_LOG`/`ACTION_OPEN_SETTINGS` from
`HANDLED_ACTIONS` and `openMainActivity` from the provider. `pendingIntent()`
already gives each action a unique `data` URI (`PenWidgetActions.kt:42`), so
distinct activity PendingIntents come free.

#### W-13 (Medium) — `EXTRA_START_ROUTE` is never consumed

`MainActivity.onCreate` reads `intent.getStringExtra(EXTRA_START_ROUTE)`
(`MainActivity.kt:16`) and never removes it. The extra lives on the Activity's
intent for the whole instance, so **every** configuration-change recreation —
rotation, dark-mode toggle, font-size change, unfolding the device (this is a
Fold) — re-runs `render` with the widget's route and throws the user back to
Log or Settings from wherever they had navigated.

**Proposed fix.** Consume it once:

```kotlin
private fun consumeStartRoute(): String? =
    intent.getStringExtra(EXTRA_START_ROUTE)?.also { intent.removeExtra(EXTRA_START_ROUTE) }
```

#### W-14 (Medium) — `onNewIntent` is dead code and every widget open resets the app

`MainActivity` is declared without `android:launchMode`
(`AndroidManifest.xml:18-28`), i.e. `standard`, and the widget intent uses
`FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` without
`FLAG_ACTIVITY_SINGLE_TOP` (`PenConsumptionWidgetProvider.kt:144`). With a
standard-mode activity, `CLEAR_TOP` finishes and recreates the target rather
than delivering to it, so the `onNewIntent` override added in this PR
(`MainActivity.kt:19-23`) is not reached on the normal path — and the user's
unsaved Log or Purchase form input is destroyed each time they tap the widget.

**Proposed fix.** Add `android:launchMode="singleTop"` to `MainActivity` (or add
`FLAG_ACTIVITY_SINGLE_TOP` to the intent), which makes `onNewIntent` real. Pairs
with W-12: an activity PendingIntent should carry the same flags.

#### W-04a / W-15 (Medium) — Missing widget refresh triggers

`PenWidgetUpdater.updateAll` is called from four view-model paths and
`SyncWorker` (`CannsheetViewModel.kt:339,565,641,779`,
`SyncWorker.kt:54`). Two paths that change what the widget displays do not:

- `updateSecondsPerUseForType` and `clearSecondsPerUseForType`
  (`CannsheetViewModel.kt:274-287`). Turning the pen duration rate on in
  Settings leaves the widget showing "Pen duration rate is off" — and the hint
  says "Tap to set it in Settings", which the user just did. Turning it off
  leaves the widget accepting input it can no longer convert until some
  unrelated event refreshes it.
- `fetchProducts` (`CannsheetViewModel.kt:344-354`). A catalog refresh changes
  `totalUses` and product status, so the subtitle's "synced N uses" and status
  label go stale.

**Proposed fix.** Add `PenWidgetUpdater.updateAll(getApplication())` to both,
and — better — replace the scattered call sites with a single collector: have
`CannsheetGraph` expose a `WidgetRefresher` and drive it from one place that
observes the same inputs `penQuickLogState` already combines
(`CannsheetViewModel.kt:158-174`). That closes this class of bug instead of
patching two instances of it. See W-19.

#### W-25a (Low) — No `onRestored`, `onEnabled`, or `onDisabled`

After a backup restore, app widget IDs are remapped and `onRestored` is the hook
to migrate per-ID state. `pen_widget_state` is excluded from backup
(`backup_rules.xml`, `data_extraction_rules.xml`), so nothing is *wrong* — but
without `onRestored` the widget also cannot clean up. Low priority; note it and
decide explicitly.

### E. Layout, sizing, and preview

#### W-05 (High) — The layout does not fit its own declared minimum size

`widget_pen_consumption.xml` has a fixed vertical budget:

| Element | Height |
|---|---|
| root padding (`10dp` top + bottom) | 20dp |
| `widget_pen_name` (14sp bold, wrap) | ≈19dp |
| `widget_pen_subtitle` (10sp, wrap) | ≈14dp |
| middle row (`0dp` + `weight=1`), contains a **56dp** submit button | flexible |
| step row `layout_marginTop` | 8dp |
| step buttons | 40dp |

Everything except the middle row is fixed, totalling ≈101dp with the subtitle
visible and ≈87dp without it. The middle row — the counter panel and the submit
button, i.e. the entire point of the widget — absorbs whatever is left.

Both `appwidget-provider` files declare `minHeight="110dp"` and
`minResizeHeight="110dp"`, and the v31 file declares `targetCellHeight="2"`. At
110dp with the subtitle hidden, the middle row gets ≈23dp: the 56dp submit
button is clipped to well under half its declared size and the 28sp counter text
(≈37dp line box) does not fit at all. Even at a typical 2-cell height the middle
row stays under 56dp, so **the submit button is clipped at the widget's own
target size**, before the user resizes anything. API 31+ launchers also subtract
their own padding from the available space, making it worse.

**Needs device confirmation** — cell height in dp varies by launcher and by
screen (this is a Fold, with two very different home screens). The arithmetic
above is from the layout; the exact dp per cell is not.

**Proposed fix.**

1. Set `minHeight`/`minResizeHeight` to **160dp** and `targetCellHeight="3"`;
   keep `minWidth` at 110dp but raise `minResizeWidth` to 140dp so the counter
   and the 56dp button can sit side by side.
2. Give the middle row `layout_height="wrap_content"` with
   `android:minHeight="56dp"` so it stops being the shock absorber, and let the
   *subtitle* be the element that disappears under pressure.
3. Reduce the submit button to 48dp (still above the 48dp touch-target
   minimum) and the counter to 24sp to buy headroom.
4. Add a genuinely compact layout for small sizes — counter and submit only, no
   step row — and select it in `PenWidgetUpdater` from
   `OPTION_APPWIDGET_MIN_HEIGHT`, or on API 31+ use the
   `RemoteViews(Map<SizeF, RemoteViews>)` constructor so the launcher picks per
   size without a provider round trip.
5. Verify at 2x2, 3x2, 2x3, and 4x2 on both Fold screens.

#### W-16 (Medium) — The only success confirmation is hidden at the default size

`PenWidgetUpdater.update` hides the subtitle when
`OPTION_APPWIDGET_MIN_HEIGHT < 140` (`PenWidgetUpdater.kt:43`) — an undocumented
magic number in Kotlin, not a dimension resource. With `targetCellHeight="2"`,
the reported minimum height on most phone launchers is roughly 100–120dp, so the
subtitle is hidden at the widget's default placement.

The subtitle is where `"Queued ✓"` appears for eight seconds after a commit
(`PenWidgetUiModel.kt:117-121`), alongside product status, synced uses, and
pending uses. So at the default size, a user who submits sees the countdown, and
then… the counter returns to `0s`. There is no confirmation that anything was
recorded — on a widget whose entire job is recording things.

**Proposed fix.** Move the breakpoint to `@dimen/widget_subtitle_min_height` and
confirm the real value on device; and give the compact layout its own
confirmation that does not depend on the subtitle — show `✓` in the counter
panel for the `QUEUED_SUBTITLE_WINDOW_MILLIS` window, or tint the panel. The
confirmation must survive at the size the widget actually ships at.

#### W-20 (Low) — The API 31+ picker preview is an empty card

`xml-v31/pen_consumption_widget_info.xml` sets
`previewLayout="@layout/widget_pen_consumption"`, which takes precedence over
`previewImage` on API 31+. None of `widget_pen_name`, `widget_pen_subtitle`, or
`widget_pen_counter` declares `android:text`, so the widget picker renders a
card with a blank title, a blank subtitle, and a blank counter — with only the
`+`, `−`, and an empty submit button visible. `previewImage` is a 250×250,
1,074-byte placeholder used only below API 31. The production device is API 36,
so the blank card is what the user actually sees when adding the widget.

**Proposed fix.** Add `layout/widget_pen_consumption_preview.xml` — the same
layout with representative `android:text` ("Blue Dream cart", "Active · synced
12 uses", "30s") — and point `previewLayout` at it. `tools:text` does not render
in the picker. Replace the placeholder PNG with a render of that preview for
API < 31.

#### W-25 (Low) — Layout and resource polish

- `android:fontFamily="sans"` on `widget_pen_name`
  (`widget_pen_consumption.xml:15`) is not a valid family token; it silently
  falls back. Use `sans-serif` or drop it.
- `android:foregroundGravity="center"` on the counter `FrameLayout` (`:45`)
  applies to a foreground drawable, of which there is none. Dead attribute.
- Default backgrounds are inconsistent: `widget_pen_minus` starts disabled
  (`:95`) and `widget_pen_plus` starts enabled (`:112`). Only visible in the
  initial layout and preview, but it reads as an accident.
- `initialLayout` is the interactive layout with all text empty, so a
  newly-placed widget shows a blank card until the first `onUpdate` lands.
  Consider a neutral initial state.
- In `Composing`/`AwaitingCommit` there is no tap-through to the app at all —
  only the message layout is clickable (`PenWidgetRenderer.kt:52-62`). Users
  expect the widget body to open the app. Propose making
  `widget_pen_name` open the Log screen.
- Long subtitles truncate: `formatAmount` yields six decimal places, so
  "Active · synced 3.333333 uses · Pending: +1.666667 uses"
  (`PenWidgetUiModel.kt:123-135`) will ellipsize in a one-line 10sp field.
  Round display to 2–3 decimals.

### F. Accessibility and localization

#### W-17 (Medium) — Declared strings are never used; all copy is hardcoded

`strings.xml` gained thirteen `pen_widget_*` entries. Six of them are referenced
nowhere in the codebase — confirmed by grep across `app/src`:

- `pen_widget_no_carts`
- `pen_widget_no_cart_loaded`
- `pen_widget_no_cart_loaded_hint`
- `pen_widget_rate_off`
- `pen_widget_rate_off_hint`
- `pen_widget_queued`

Every one of them has a hardcoded English duplicate in `PenWidgetText`
(`PenWidgetUiModel.kt:8-18`), and the widget renders the Kotlin constants. So the
resources are dead weight (Android lint's `UnusedResources` will flag them), the
widget cannot be localized, and there are two sources of truth for the same
copy which have already drifted — `PenWidgetText.QUEUED` is `"Queued ✓"` while
`@string/pen_widget_queued` is `"Queued"`, and `UNAVAILABLE_HINT`
("Tap to open Cannsheet Mobile.") has no resource at all.

`"Undo within 5 seconds"` (`PenWidgetUiModel.kt:67`) is also hardcoded *and*
duplicates `UNDO_WINDOW_MILLIS`; changing the constant silently makes the copy a
lie. W-07 changes that window, so this needs fixing in the same pass.

**Proposed fix.** Have the UI model carry `@StringRes` IDs and format arguments
rather than resolved strings, and resolve them in `PenWidgetRenderer` where a
`Context` already exists. The model stays a pure data class, the unit tests keep
working by asserting IDs instead of literals, and the copy has one home. Add the
missing `pen_widget_no_carts_hint` and `pen_widget_undo_window` (a plural or
format string driven by the constant).

#### W-18 (Medium) — The counter announces the action, never the value

`PenWidgetRenderer` sets the counter panel's content description to
`@string/pen_widget_reset` — "Reset duration to zero"
(`PenWidgetRenderer.kt:112`). Setting a click PendingIntent on the `FrameLayout`
makes it an accessibility-focusable target, so a TalkBack user navigating to the
element that *displays the duration* hears "Reset duration to zero" and never
hears the duration. The inner `TextView` and `Chronometer` remain separately
focusable, so the value may be announced as a separate, unlabelled node — and
the ticking `Chronometer` will re-announce during the undo window.

**Proposed fix.** Set the panel's description to a formatted string —
"%1$d seconds. Double tap to reset." — updated on every render, and set
`importantForAccessibility="no"` on `widget_pen_counter` and
`widget_pen_countdown` so the panel is the single announced node. During
`AwaitingCommit`, describe the state rather than the ticking value: "Logging %1$d
seconds. Double tap to undo."

Also worth doing: include the pending amount in the submit button's description
("Submit 30 second pen log") rather than the static "Submit pen log"
(`PenWidgetRenderer.kt:105`), and give the `✓`/`UNDO` glyphs
(`PenWidgetUiModel.kt:16-17`) text equivalents.

### G. Architecture

#### W-19 (Medium) — Package dependency cycles

The feature introduced two cycles:

- `com.example.ui.CannsheetViewModel` → `com.example.widget.PenWidgetUpdater`
  (`CannsheetViewModel.kt:27`), while `com.example.widget` imports
  `PenQuickLogState`, `buildPenQuickLogState`, `secondsToUses`, and
  `currentSubmissionDateTime` from `com.example.ui`
  (`PenWidgetDataSource.kt:5-6`, `PenWidgetUiModel.kt:4`,
  `PenConsumptionWidgetProvider.kt:8-10`). So `ui → widget → ui`.
- `com.example.data.sync.SyncWorker` → `com.example.widget.PenWidgetUpdater`
  (`SyncWorker.kt:17`), while the widget depends on `com.example.data`
  throughout. So `data → widget → data`.

A data-layer worker importing a UI-surface object is the inversion that matters:
it means the sync path cannot be reasoned about, or tested, without the widget.
`AGENTS.md` asks for "narrow responsibilities" and a clean Compose/data-layer
split.

**Proposed fix.**

1. Define `interface WidgetRefresher { fun refreshAll() }` in `com.example.data`,
   implement it in `com.example.widget`, and expose one instance from
   `CannsheetGraph`. `SyncWorker` and `CannsheetViewModel` depend on the
   interface. This also makes the missing triggers in W-15 a one-line addition
   in one place, and makes refresh assertable in a JVM test.
2. Move the shared domain helpers out of `com.example.ui` into a neutral home
   (`com.example.domain` or `com.example.data`): `secondsToUses`/`usesToSeconds`
   (`QuantityUnits.kt`), `PenQuickLogState`/`buildPenQuickLogState`/
   `resolveLoadedPenProduct` (`PenQuickLog.kt`), and `SubmissionDateTime`/
   `currentSubmissionDateTime` (`ConsumptionDateTime.kt`). Leave type aliases
   behind if the churn is a concern.
3. Note for later: `CannsheetRepository` now carries both
   `addConsumption(action, millis)` (the interface override) and a convenience
   `addConsumption(action)` overload added purely to replace the removed default
   argument (`Repository.kt:57-73`). Audit whether the overload still has
   callers; if not, delete it.

#### W-23 (Low) — Lossy seconds round-trip for non-divisor rates

`secondsToUses` divides at scale 6 with `HALF_UP` (`QuantityUnits.kt:18-23`), and
`formatQuantityInInputUnit` multiplies back for display (`:26-37`). For any rate
that does not divide the seconds evenly the round trip does not close:

| Rate | Widget submits | Stored uses | Redisplayed in app |
|---|---|---|---|
| 7.0 s/use | 10s | 1.428571 | **9.999997s** |
| 3.0 s/use | 10s | 3.333333 | **9.999999s** |
| 3.0 s/use | 20s | 6.666667 | **20.000001s** |
| 7.5 s/use | 10s | 1.333333 | **9.999998s** |
| 10.0 s/use | 10s | 1.000000 | 10s |

This is pre-existing behavior in `QuantityUnits`, not introduced by the widget —
but the widget makes it far easier to hit, because it is a seconds-first entry
point with a 10-second step, and the default rate is arbitrary. A user who taps
`+10` once and later opens History sees `9.999997s`.

**Proposed fix.** Round the *display* round trip to a presentation scale (2–3
decimals) in `formatQuantityInInputUnit` while keeping storage at scale 6. Do
not change stored precision — that would touch the wire contract. Add a test
matrix over the rates above. Treat as its own PR, since it changes in-app
display outside the widget.

## 4. Original sequenced remediation plan

This was the proposed six-PR sequence before the user directed that the whole
review be implemented and merged through the existing branch. It is retained
to show the original risk ordering; PR-1 through PR-3 were the critical path.

### PR-1 — Widget commit durability *(W-01, W-02, W-09, W-22)*

The data-safety core. Two-phase claim replacing the destructive take; `eventId`
in the payload with payload version 2 and a v1→v2 decode migration; optional
`eventId` parameter on `ConsumptionLogger.log`; force-commit on `onDeleted` plus
`cancelCommit`. Correct the "stable consumption ID" claim in
`docs/DECISIONS.md` (ADR-013) and `docs/HANDOFF.md`.

Tests: coordinator test with a `ConsumptionLogRepository` that throws, asserting
the payload survives and a retry writes exactly one row with the same `eventId`;
process-death simulation via a stale claim; `onDeleted` with a fresh payload
asserting a row is written and no payload is dropped; codec v1→v2 migration.

### PR-2 — Commit timing and undo boundary *(W-04, W-07, W-08, W-21)*

Process-local timer as the primary commit path with WorkManager demoted to
backstop; `COMMIT_GRACE_MILLIS` separating displayed window from deadline;
`submittedAtEpochMillis` plus `MAX_PENDING_AGE_MILLIS` ceiling in `resolveCommit`;
terminal "Saving…" render at zero.

Tests: `resolveCommit` boundary and ceiling cases including a backwards clock
jump; timer cancel-on-undo; renderer assertion that `remainingMillis == 0`
produces a stopped chronometer.

### PR-3 — Provider concurrency and lifecycle *(W-06, W-10, W-11, W-12, W-13, W-14, W-15)*

Payload construction moved inside the submit edit; a single serialized widget
scope; `runCatching` + exception handler at every launch site; activity
PendingIntents replacing the broadcast-then-`startActivity` path;
`launchMode="singleTop"`; `EXTRA_START_ROUTE` consumed once; the two missing
refresh triggers.

Tests: a provider-level test (Robolectric or `androidTest`) driving
`increment → increment → submit` and asserting the payload matches the final
draft; ordering test for interleaved renders; a `MainActivity` test asserting
that a configuration change does not re-apply the widget route.

### PR-4 — Widget sizing and preview *(W-05, W-16, W-20, W-25)*

Provider-info sizes corrected; middle row given `wrap_content` + `minHeight`;
compact layout with its own confirmation affordance; subtitle breakpoint moved
to a dimension resource; dedicated preview layout and regenerated preview image;
polish items.

Tests: a renderer test that inflates into a fixed-size parent at 110dp, 160dp,
and 250dp and asserts the submit button is not clipped — the current
`PenWidgetRendererTest` inflates into an unconstrained `FrameLayout`
(`PenWidgetRendererTest.kt:41`), which is exactly why W-05 was invisible to CI.
Screenshots at 2x2 / 3x2 / 2x3 / 4x2 on both Fold screens, light and dark.

### PR-5 — Strings and accessibility *(W-17, W-18, plus the W-25 a11y items)*

UI model carries string resource IDs; renderer resolves them; dead resources
wired up or removed; content descriptions carry values, not just actions;
`importantForAccessibility` on the inner counter nodes.

Tests: assert the UI model returns resource IDs; a renderer test asserting the
counter panel's description contains the seconds value. Manual TalkBack pass.

### PR-6 — Layering *(W-19, W-23)*

`WidgetRefresher` interface in `CannsheetGraph`; shared helpers moved out of
`com.example.ui`; display-scale rounding for the seconds round trip. Largest
diff, lowest risk, best done last. Split W-23 out if the diff gets wide — it
changes in-app display outside the widget.

## 5. Test coverage gaps to close

Current widget coverage is 6 JVM tests and 4 instrumented tests, all aimed at
pure logic, the codec, the state repository, and RemoteViews inflation. What is
untested:

- **`PenConsumptionWidgetProvider` has no test of any kind.** Action routing,
  the flush-before-action ordering, the submit read-then-write race, and the
  undo-versus-flush boundary are entirely uncovered — and that is where W-06,
  W-07, and W-12 live.
- No test drives `ConsumptionLogger` to failure, so W-01 is invisible.
- No test asserts the loaded-cart side effect of a *late* commit (W-03).
- No test covers `PenWidgetUpdater` at all — neither the subtitle breakpoint nor
  render ordering.
- `PenWidgetRendererTest` inflates into an unconstrained parent, so no layout
  clipping can ever fail (W-05).
- Nothing asserts that widget copy comes from resources (W-17).
- `PenWidgetCommitWorkerTest` asserts only the missing-payload no-op; the
  success path, the retry path, and `runAttemptCount` exhaustion are uncovered.
- No test asserts that a Settings rate change refreshes the widget (W-15).

## 6. Device validation checklist

Per ADR-013 and `docs/HANDOFF.md:89-96`, this must run on a **sandbox/debug
package with a non-production endpoint** — never the signed production package,
and never installed over it.

1. Add the widget at 2x2; confirm the picker preview is not blank (W-20) and
   that the submit button is not clipped (W-05).
2. Resize to the declared minimum in both directions; confirm every control
   stays usable.
3. `+`/`−` to 600s and back to 0; confirm clamping, no lag, and no stale value
   after rapid taps (W-10).
4. Submit; confirm the countdown, then confirm the row appears in the queue
   within ~5s (W-04) and that a confirmation is visible at the default size
   (W-16).
5. Submit and tap Undo at ~1s, ~4.5s, and ~4.9s; confirm all three undo (W-07).
6. Submit, immediately lock the screen, wait 10 minutes, unlock; confirm the row
   was written and the widget is not stuck showing a negative countdown (W-04,
   W-21).
7. Submit, then change the loaded cart in-app within the window; confirm the
   commit does not re-point the loaded cart (W-03).
8. Submit and remove the widget within 5s; confirm the log was still recorded
   (W-09).
9. Turn the pen duration rate off and on in Settings; confirm the widget
   follows (W-15).
10. Tap the widget message with the app already open on the Purchase tab with
    unsaved input; confirm the input survives (W-14).
11. Open from the widget, then rotate / unfold; confirm the app stays where the
    user navigated (W-13).
12. Both Fold screens, light and dark, with and without Material You.
13. TalkBack pass over every state (W-18).
14. Airplane mode: submit, confirm the entry queues locally and syncs on
    reconnect.

## 7. Documentation to update

- `docs/DECISIONS.md` — ADR-013 claims a "stable consumption ID" that does not
  exist (W-02). Correct it, and add a follow-up ADR for the two-phase claim and
  the timer/WorkManager/flush tiering.
- `docs/HANDOFF.md:33` — same claim, same correction.
- `docs/ARCHITECTURE.md` — the deferred-commit mermaid diagram shows
  `Take → Logger → Room`; update it to the claim/complete sequence, and state
  that WorkManager is a backstop rather than the timer.
- `docs/PROJECT_STATE.md` — record the verified state after each PR.
- `AGENTS.md:54` — the pen-widget convention line should also say that the
  commit payload is removed only after the Room write is durable.

## 8. Risk and rollback

- **Payload version 2 (PR-1).** The one migration-shaped risk. Mitigated by the
  v1→v2 decode path in W-22; a v1 payload has never reached Room, so minting an
  `eventId` at decode time cannot duplicate. Test upgrade with a v1 payload
  present in the DataStore.
- **Two-phase claim (PR-1).** Changes the failure mode from "lose it" to "retry
  it", which trades a silent-loss risk for a duplicate risk. The `eventId` from
  W-02 is what makes the trade safe, which is why they ship together and not
  separately.
- **Timer tiering (PR-2).** Adds a process-local path alongside WorkManager;
  both go through the same atomic arbitration, so a double-fire is already a
  no-op by construction. No new duplicate surface.
- **Sizing changes (PR-4).** Raising `minResizeHeight` on a widget the user has
  already placed can cause the launcher to resize the existing instance. Cosmetic
  and self-correcting, but call it out in the release notes.
- **No Room migration, queue field, endpoint, application ID, or Apps Script
  change is required by any item in this plan.** If any proposed fix starts to
  need one, stop and re-scope.
- Rollback for all of it is per-PR revert; the widget is additive and reverting
  it removes the surface without touching queued data.

## 9. Checked and found correct

Recorded so a later reader does not re-investigate:

- `android:exported="false"` on the provider is correct. The system delivers
  `APPWIDGET_UPDATE` as an explicit component broadcast, and every custom action
  is sent by our own `PendingIntent`, so nothing external needs to reach it.
- `RemoteViews.setBoolean("setEnabled")` and `setInt("setBackgroundResource")`
  target remotable methods, and `PenWidgetRendererTest` exercises both on the
  API 24 and API 36 CI matrices.
- Moshi resolves the generated `@JsonClass(generateAdapter = true)` adapter
  without `KotlinJsonAdapterFactory`, and `isMinifyEnabled = false`
  (`app/build.gradle.kts:61`) means R8 cannot strip it. *If minification is ever
  enabled, this needs a keep rule* — worth a comment at the declaration.
- Seconds never cross into Room, the queue, or the wire.
  `PenWidgetCommitCoordinator.logPayload` passes `payload.uses` only, and
  `payload.seconds` is used solely for display and undo restoration. The central
  claim of ADR-013 holds.
- Color qualifiers cover all four combinations (`values`, `values-night`,
  `values-v31`, `values-night-v31`); every device lands on a defined palette.
- `resolveUndo`/`resolveCommit` are pure, total, and correct as written; the
  arbitration design is right and worth keeping through every fix above.
- Per-widget draft isolation is correct, and `adjustDraftSeconds` is genuinely
  atomic — `PenWidgetStateRepositoryTest.concurrentAdjustCallsDoNotLoseIncrements`
  proves it for 20 concurrent callers.
- `APPWIDGET_UPDATE_OPTIONS` correctly falls through `onReceive` to
  `onAppWidgetOptionsChanged`, because the provider reads its own
  `EXTRA_APP_WIDGET_ID` rather than the platform extra.
- The backup exclusions are correct and complete for `pen_widget_state`, in both
  `backup_rules.xml` and `data_extraction_rules.xml`, including
  `device-transfer`.
