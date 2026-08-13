# Pen widget remediation review and follow-up plan

Reviewed: 2026-08-13
Subject: the widget hardening merged as
[PR #55](https://github.com/noamvb/cannsheet-mobile/pull/55) (`7f66e9b`) and
released as v1.2.26, assessed against `docs/WIDGET_REVIEW_PLAN.md`.
Status: proposal. Nothing in this document has been implemented.

## 1. Scope and method

A second source review, in two passes. First, verify each of the 25 findings
(W-01…W-25) in `docs/WIDGET_REVIEW_PLAN.md` against the code that actually
landed. Second, review the new code on its own terms for defects the
remediation introduced or left open. Reviewed at `00f7860`:

- `app/src/main/java/com/example/widget` (15 files, 1,509 lines — up from 13
  files, 1,012 lines)
- `app/src/main/java/com/example/domain` (3 new files)
- `app/src/main/java/com/example/data/ConsumptionLogger.kt`,
  `WidgetRefresher.kt`, `CannsheetGraph.kt`, `Database.kt`, `sync/SyncWorker.kt`
- `app/src/main/java/com/example/MainActivity.kt`, `CannsheetApplication.kt`,
  `ui/AppNavigation.kt`, `ui/CannsheetViewModel.kt`, and the `ui` delegation
  shims
- `AndroidManifest.xml`, all four widget layouts, `dimens.xml`, `strings.xml`,
  and both `appwidget-provider` files
- all 11 widget tests (7 JVM, 4 instrumented)
- `backend_additions.gs` consumption ingestion, to verify end-to-end
  idempotency

### What could not be verified here

No Gradle build, lint, unit test, or emulator run was executed for this review —
same JDK constraint as before. PR #55's CI is the execution evidence: the
pre-merge gate passed API 24, and the post-merge `main` run
[31652734762](https://github.com/noamvb/cannsheet-mobile/actions/runs/31652734762)
passed API 24 **and** API 36 instrumentation on this exact tree.

Still true, and now the single largest gap: **no physical device walkthrough has
ever been performed on this feature, across v1.2.25 or v1.2.26.** Every finding
below marked **needs device confirmation** is a prediction about launcher
behavior that source cannot settle.

## 2. Verdict

This is a strong remediation. Twenty-three of the twenty-five findings are
fully addressed, one is partially addressed, and one was deliberately deferred
with a stated rationale. More importantly, the fixes are *correct* — I traced
the three data-safety ones end to end rather than pattern-matching the diff:

- **W-01/W-02 hold under retry.** The claim survives a failed Room write
  (`PenWidgetCommitCoordinator.kt:38-57`), and a repeated write cannot
  duplicate: `insertConsumption` uses `OnConflictStrategy.REPLACE`
  (`Database.kt:166`) against a unique `eventId` index (`Database.kt:53`), and
  if the row has already synced and been acknowledged, the backend independently
  rejects the repeat as `duplicate` (`backend_additions.gs:2837-2839`). Both
  halves are needed, and both are present.
- **W-10 closes W-07's real race.** Every path that can claim, undo, or render
  now funnels through one process-wide mutex — the timer
  (`PenWidgetRuntime.kt:65-69`), the worker (`PenWidgetCommitWorker.kt:16`),
  all four provider entry points, and application startup
  (`CannsheetApplication.kt:24`). I checked each one specifically because the
  undo/claim arbitration depends on it.
- **W-05's arithmetic now works.** The full layout's intrinsic height is
  ≈157dp (20 padding + 19 name + 14 subtitle + 56 counter row + 48 step row)
  against a 160dp declared floor, with the weight removed from the row that was
  being crushed.

The remaining items are smaller: one latent invariant that is correct today but
undefended, one sizing threshold that collides with its own floor, and a handful
of polish and test-surface items. Nothing here is a live data-safety defect.

### W-01…W-25 verification

| ID | Status | Evidence |
|----|--------|----------|
| W-01 | Fixed | `claimCommit`/`releaseClaim`/`completeCommit`, `PenWidgetStateRepository.kt:156-218` |
| W-02 | Fixed | `eventId` in payload v2 (`PenWidgetDraft.kt:26`), `ConsumptionLogger.kt:38` |
| W-03 | Fixed | `updateLoadedCart = false` (`PenWidgetCommitCoordinator.kt:94`) |
| W-04 | Fixed | `PenWidgetCommitTimer` primary, WorkManager backstop, lazy flush |
| W-05 | Fixed | 160dp floor, `wrap_content`+`minHeight=56dp`, 48dp submit, `targetCellHeight=3` |
| W-06 | Fixed | `submitCommit(id) { seconds -> }` builds inside the edit (`:102-125`) |
| W-07 | Fixed | `COMMIT_GRACE_MILLIS = 1_500` split from the displayed window |
| W-08 | Fixed | `isCommitDue` ceiling + rollback tolerance (`PenWidgetTransitions.kt:44-50`) |
| W-09 | Fixed | `onDeleted` force-commits before clearing (`PenConsumptionWidgetProvider.kt:39-70`) |
| W-10 | Fixed | `WidgetWorkSerializer` + `limitedParallelism(1)` |
| W-11 | Fixed | `CoroutineExceptionHandler` + `runCatching` (`PenWidgetRuntime.kt:55,72-97`) |
| W-12 | Fixed | `PendingIntent.getActivity` (`PenWidgetActions.kt:37-55`); open actions off `HANDLED_ACTIONS` |
| W-13 | Fixed | `consumeStartRoute()` removes the extra (`MainActivity.kt:41-42`) |
| W-14 | Fixed | `launchMode="singleTop"` + `FLAG_ACTIVITY_SINGLE_TOP` + `Channel` nav events |
| W-15 | Fixed | single collector on `penQuickLogState` (`CannsheetViewModel.kt:250-255`) |
| **W-16** | **Partial** | breakpoint is a dimen resource, but see **R-02** |
| W-17 | Fixed | `PenWidgetText.Resource`/`Literal`, resolved in the renderer |
| W-18 | Fixed | value-bearing plurals + `importantForAccessibility="no"` on inner nodes |
| W-19 | Fixed | `com.example.domain`; `WidgetRefresher` in `data`; cycle broken |
| W-20 | Fixed | `widget_pen_consumption_preview.xml`; preview PNG 1KB → 61KB |
| W-21 | Fixed | `isSaving` terminal state, chronometer stopped and hidden |
| W-22 | Fixed | v1→v2 decode with deterministic `eventId` (`PenWidgetPayloadCodec.kt:58-74`) |
| W-23 | Fixed | `PRESENTATION_SCALE = 3` (`domain/QuantityUnits.kt:38`) |
| W-24 | Deferred | documented; see **R-11** for what changed underneath it |
| W-25 | Fixed | `sans-serif`, dead attribute removed, consistent defaults, initial layout, tap-through |

## 3. New findings

| ID | Severity | Finding |
|----|----------|---------|
| R-01 | Medium | The no-phantom-log invariant rests entirely on an undocumented process-local mutex; `resolveUndo` ignores claim state |
| R-02 | Medium | The compact breakpoint equals the resize floor, so the compact layout is either dead or is the Fold cover-screen path — and nobody knows which |
| R-03 | Medium | `PenConsumptionWidgetProvider` still has no test in any source set |
| R-04 | Low | The full layout no longer stretches; large sizes get dead space |
| R-05 | Low | The non-atomic `submitCommit(id, payload)` overload survives as a test-only API on a production class |
| R-06 | Low | `catch (Throwable)` swallows cancellation semantics; a cancelled commit can wedge a claim for 60s |
| R-07 | Low | `receiveAsFlow()` is evaluated in a composable argument position |
| R-08 | Low | Three plurals have identical `one`/`other` forms |
| R-09 | Low | The name tap-through has no screen-reader action hint |
| R-10 | Low | `ui` delegation shims remain after the domain extraction |
| R-11 | Low | The mutex converts per-tap cost into queueing latency under a broadcast deadline |
| R-12 | Carried | Physical device validation still not performed |

### A. Correctness

#### R-01 (Medium) — The undo/claim invariant is correct but undefended

`resolveUndo` matches on `commitId` alone (`PenWidgetTransitions.kt:19-27`). It
does not look at `claimId`. So on its own terms, an undo can restore a payload
that has already been claimed and whose Room write is in flight — which would
produce a **phantom log**: a row in Room and the offline queue that the user
believes they cancelled, with `enqueueSync` skipped because `completeCommit`
returns false.

**This is not currently reachable.** I checked every path that can claim or
undo, and all of them hold the same `WidgetWorkSerializer` mutex:

| Path | Serialized at |
|---|---|
| Commit timer | `PenWidgetRuntime.kt:65-69` |
| WorkManager worker | `PenWidgetCommitWorker.kt:16` |
| Provider `onReceive` (undo, submit, ±) | `PenConsumptionWidgetProvider.kt:85` |
| Provider `onUpdate` / `onDeleted` / options-changed | `:21`, `:43`, `:158` |
| Application startup flush | `CannsheetApplication.kt:24` |

Because claim → Room write → complete happens inside one critical section, an
undo runs strictly before or strictly after it, and both orderings are correct.

The problem is that this is a *whole-program* invariant defended in exactly one
place, documented nowhere near the code that depends on it, and invisible to the
pure-function tests that cover `resolveUndo`. Any future change that moves a
commit off the mutex — a second widget surface, a `Dispatchers.IO` hop for the
Room write, a multi-process `android:process` declaration, a well-meaning
"don't hold the lock across I/O" refactor — silently reintroduces a
data-integrity bug with no failing test.

**Proposed fix — make the invariant local and self-enforcing.**

```kotlin
fun resolveUndo(
    payload: PenWidgetCommitPayload?,
    commitId: String,
    nowMillis: Long,
): PenWidgetUndoResolution = when {
    payload?.commitId != commitId -> PenWidgetUndoResolution.NoOp
    // A claimed payload is being written to Room right now. Undo must lose.
    payload.claimId != null && !isClaimStale(payload, nowMillis) ->
        PenWidgetUndoResolution.NoOp
    else -> PenWidgetUndoResolution.Restored(payload.seconds)
}
```

This makes undo-vs-claim arbitration a property of the DataStore edit itself,
exactly as undo-vs-commit already is, and the mutex becomes an ordering
optimization rather than the correctness boundary — which is the same principle
ADR-013 already applied to WorkManager cancellation. Add a
`PenWidgetTransitionsTest` case asserting a claimed payload cannot be undone and
a stale-claimed one can, and a KDoc note on `WidgetWorkSerializer` stating what
it does *not* guarantee.

### B. Sizing and layout

#### R-02 (Medium) — The compact breakpoint collides with the resize floor

Two numbers were introduced independently and landed equal:

- `widget_subtitle_min_height` = **160dp** (`values/dimens.xml`), used as the
  compact breakpoint: `compact = minHeightDp in 1 until subtitleMinHeightDp`
  (`PenWidgetUpdater.kt:38-43`)
- `minResizeHeight` = **160dp** (both `appwidget-provider` files)

So the compact layout renders only when the launcher reports
`OPTION_APPWIDGET_MIN_HEIGHT` **below the height the provider declares as its
own floor**. On a launcher that honors `minResizeHeight`, that never happens and
`widget_pen_consumption_compact.xml` is dead — along with
`showCompactConfirmation` (`PenWidgetRenderer.kt:90`), the
`pen_widget_queued_symbol` string, and two of the six renderer tests.

But it may not be dead. `OPTION_APPWIDGET_MIN_HEIGHT` is documented as the lower
bound *across orientations*, and landscape home screens and the Fold's cover
screen have shorter cells than portrait on the main screen. On this specific
device — a Fold with two very different home screens — the compact path could
well be what the user actually sees on one of them.

**Either way this is a problem**, and the two possibilities need opposite fixes:

- If compact should be reachable, the resize floor is wrong. The compact layout's
  intrinsic height is ≈110dp (8 padding + 18 name + 42 + 42), so
  `minResizeHeight` should drop to **110dp** — restoring the resize range the
  original widget advertised, which now actually renders correctly — and the
  breakpoint should sit strictly between the two (propose **150dp**).
- If compact should not exist, delete the layout, the `showCompactConfirmation`
  branch, the string, and the two tests.

I recommend the first: the compact layout is well built and small-widget support
is worth having. But the choice must be made from device evidence, not from
source. **Needs device confirmation** — read
`OPTION_APPWIDGET_MIN_HEIGHT` on both Fold screens, in both orientations, at
2×3 and after a resize to minimum, and pick the breakpoint from the measured
values.

Whichever way it goes, the two constants must stop being independent. Put the
relationship in one place:

```xml
<!-- values/dimens.xml -->
<dimen name="widget_min_resize_height">110dp</dimen>   <!-- compact fits here -->
<dimen name="widget_subtitle_min_height">150dp</dimen> <!-- full layout above -->
```

and reference `@dimen/widget_min_resize_height` from both provider-info files so
a future edit cannot desynchronize them.

#### R-04 (Low) — The full layout no longer stretches

W-05's fix removed `layout_weight="1"` from the counter row and gave it
`wrap_content` + `android:minHeight="56dp"`
(`widget_pen_consumption.xml:31-36`). That correctly stops the row from being
crushed at the floor, but nothing now absorbs *extra* height: at
`maxResizeHeight="360dp"` the content occupies ≈157dp and the remaining ~200dp
is blank background with everything jammed to the top.

**Proposed fix.** Do not re-add `layout_weight` to the counter row — negative
excess is distributed through weights too, which is precisely what caused W-05.
Instead add a zero-height weighted spacer after the step row, so extra space is
absorbed but the floor is untouched:

```xml
<View
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_weight="1" />
```

Or set `android:gravity="center_vertical"` on the root to centre the block.
Verify at 2×3 and at maximum resize.

### C. Test surface

#### R-03 (Medium) — The provider is still untested

Test coverage improved substantially — `PenWidgetRuntimeTest` covers timer
ordering and serializer FIFO,
`PenWidgetCommitCoordinatorTest.failedRoomWriteKeepsPayloadAndRetryUsesTheSameEventId`
covers W-01 and W-02 together, `forceCommitRecordsFreshPayloadBeforeWidgetStateIsCleared`
covers W-09, and `compactAndFullLayoutsKeepSubmitVisibleAtSupportedHeights`
inflates into a *constrained* parent, which is what makes W-05 testable at all.

But `PenConsumptionWidgetProvider` still has no test in any source set. It is
the file that routes every action, owns the submit path, owns the undo path, and
holds the serialization contract that R-01 depends on. It is also the only
widget file whose bugs are invisible to every existing test.

**Proposed fix.** Add `PenConsumptionWidgetProviderTest` (Robolectric or
instrumented) covering:

1. `increment → increment → submit` captures the final draft, not an
   intermediate one (the W-06 regression guard at the level where it can
   actually regress).
2. An undo delivered while a claim is held does not restore the draft and does
   not produce a second Room row — the R-01 assertion, which should pass before
   the R-01 fix (via the mutex) and keep passing after it (via the resolver).
3. `ACTION_OPEN_LOG` / `ACTION_OPEN_SETTINGS` are absent from `HANDLED_ACTIONS`
   and produce activity PendingIntents, not broadcasts.
4. `onDeleted` with a fresh, not-yet-due payload writes the row and only then
   clears state.
5. An unhandled action falls through to `super.onReceive`.

### D. Robustness and polish

#### R-05 (Low) — Test-only non-atomic overload on a production class

`PenWidgetStateRepository.submitCommit(appWidgetId, payload)`
(`PenWidgetStateRepository.kt:82-95`) is the pre-W-06 API. Production uses only
the lambda form (`PenConsumptionWidgetProvider.kt:127`); the payload form's only
remaining callers are eight sites across two instrumented test files.

It is a loaded gun: it accepts a payload built outside the edit, which is the
exact shape W-06 was about. **Proposed fix:** annotate
`@VisibleForTesting(otherwise = VisibleForTesting.NONE)` with a KDoc line
pointing at the lambda overload, or migrate the tests and delete it. The
annotation is the cheaper option and lint will enforce it.

#### R-06 (Low) — Cancellation is caught as a generic failure

`PenWidgetCommitCoordinator.commit` catches `Throwable`
(`PenWidgetCommitCoordinator.kt:46`), which includes `CancellationException`. On
cancellation it attempts `stateRepository.releaseClaim(...)` — a suspend call in
an already-cancelled coroutine, which throws immediately and is swallowed by the
surrounding `runCatching`. The claim is then stuck until `CLAIM_STALE_MILLIS`
(60s) elapses, during which `flushOverdue` skips the payload and the widget
renders "Saving…".

Reachability is low: `cancelCommitTimer` is only called from undo and
`onDeleted`, both of which hold the mutex and therefore cannot interrupt a
commit mid-flight; process death changes the claim owner and makes the claim
immediately recoverable anyway. **Proposed fix** is one line — run the release
under `NonCancellable` so it completes regardless:

```kotlin
} catch (error: Throwable) {
    withContext(NonCancellable) {
        runCatching { stateRepository.releaseClaim(appWidgetId, claim.payload.commitId, claim.claimId) }
    }
    throw error
}
```

#### R-07 (Low) — `receiveAsFlow()` in a composable argument position

`MainActivity.render` passes `routeRequests.receiveAsFlow()` directly as a
`CannsheetApp` argument (`MainActivity.kt:34`). `receiveAsFlow()` returns a new
instance per call, and `AppNavigation` keys a `LaunchedEffect` on it — so if the
`setContent` content lambda ever recomposes, the effect restarts, and a
`Channel` element in flight between `receive()` and `emit()` is dropped.

Today the lambda reads no snapshot state, so it should not recompose and the
flow is created once. That is a property of the current code, not a guarantee.
**Proposed fix:** hoist it to a stable field.

```kotlin
private val routeRequests = Channel<String>(capacity = Channel.BUFFERED)
private val routeRequestFlow = routeRequests.receiveAsFlow()
```

#### R-08 (Low) — Plurals with identical forms

`pen_widget_submit_seconds`, `pen_widget_undo_seconds`, and
`pen_widget_saving_seconds_description` declare `one` and `other` with the same
text ("Submit %1$d second pen log"). As English that is defensible — it reads as
the attributive compound "a 30-second pen log" — but two neighbouring plurals
(`pen_widget_reset_seconds_description`, `pen_widget_logging_seconds_description`)
*do* differentiate, so the file is internally inconsistent, and a translator
given identical `one`/`other` forms has no signal about which reading was
intended.

**Proposed fix:** hyphenate the compound (`Submit %1$d-second pen log`) and
convert these three to plain `<string>` resources, or differentiate the forms.
Either is fine; the inconsistency is the finding.

#### R-09 (Low) — Tap-through has no action hint

`setOnClickPendingIntent(R.id.widget_pen_name, …)` (`PenWidgetRenderer.kt:210`)
makes the product name an app-open target, which correctly closes W-25's
missing-tap-through gap. But the node's only accessible text is the cart name,
so TalkBack announces "Blue Dream cart" with no indication it is actionable or
what it does. **Proposed fix:** set a content description such as
"%1$s. Double tap to open Cannsheet Mobile."

#### R-10 (Low) — Delegation shims after the domain extraction

`ui/QuantityUnits.kt` and `ui/PenQuickLog.kt` are now pure forwarders to
`com.example.domain`. They exist so `ConsumptionScreen`, `SettingsScreen`, and
`CannsheetViewModel` did not need import changes. The W-19 cycle is genuinely
broken — `com.example.widget` imports `com.example.domain` and no longer imports
`com.example.ui` — so this is cleanup, not a correctness issue. **Proposed fix:**
update the three call sites to import `com.example.domain` directly and delete
both shims.

#### R-11 (Low) — The mutex reshapes W-24's deferred cost

W-24 (per-tap `flushOverdue` scan plus three Room reads) was deferred pending
device profiling, which remains reasonable. Worth recording that the ground
shifted: every widget action now also acquires a process-wide mutex held across
the *entire* receiver block — `flushOverdue`, the mutation, and
`PenWidgetUpdater.update`'s DataStore read, three Room `first()` collections and
`getAppWidgetOptions` binder call — and `pendingResult.finish()` runs only after
that block completes (`PenWidgetRuntime.kt:86-96`).

Per-tap work is milliseconds, so 60 taps to reach the 600s ceiling is not a real
risk. But the profiling W-24 was deferred for should now measure *queueing under
contention* (a tap arriving while a commit holds the lock), not just per-tap
cost, and should confirm the receiver never approaches the background-broadcast
deadline. Cheap mitigation if it does: skip `flushOverdue` when the already-loaded
preferences snapshot contains no `pending_commit_` key.

#### R-12 (Carried) — Device validation

Unchanged and still the largest gap. PR #55 states it plainly: instrumentation
compiled locally but was not executed locally, no production widget was added or
tapped, and no launcher, TalkBack, or Doze walkthrough was performed. Two
releases have now shipped on source review plus emulator tests alone, and R-02
in particular *cannot* be resolved without a device.

## 4. Sequenced remediation plan

Three PRs. R-01 and R-02 are the ones that matter; the rest is a cleanup sweep.

### PR-1 — Undo/claim invariant and provider tests *(R-01, R-03, R-05, R-06)*

Move claim-awareness into `resolveUndo` so undo-vs-claim is arbitrated in the
DataStore edit; thread `nowMillis` through `PenWidgetStateRepository.undo`; run
`releaseClaim` under `NonCancellable`; annotate the legacy `submitCommit`
overload; add `PenConsumptionWidgetProviderTest` with the five cases above plus
`PenWidgetTransitionsTest` cases for claimed and stale-claimed undo.

This is the only PR that changes arbitration behavior. It should land alone so a
regression is unambiguous.

### PR-2 — Widget sizing decision *(R-02, R-04)*

**Blocked on device measurement.** Read `OPTION_APPWIDGET_MIN_HEIGHT` on both
Fold screens in both orientations, then either lower `minResizeHeight` to 110dp
and set the breakpoint to 150dp (keeping compact), or delete the compact path
entirely. Move both numbers into `dimens.xml` and reference the resize floor
from both provider-info files. Add the weighted spacer for R-04. Include
screenshots at 2×3, minimum resize, and maximum resize on both screens.

### PR-3 — Polish sweep *(R-07, R-08, R-09, R-10)*

Hoist `routeRequestFlow`; resolve the plurals inconsistency; add the tap-through
action hint; delete the `ui` shims and update the three call sites. Low risk,
no behavior change beyond accessibility text.

R-11 stays deferred with W-24 until PR-2's device session produces profiling
data. R-12 is not a PR — it is the device session that PR-2 depends on.

## 5. Device validation checklist

The 14-step checklist in `docs/WIDGET_REVIEW_PLAN.md` §6 still stands unexecuted
and should be run against a sandbox/debug package with a non-production
endpoint, per ADR-013. Add these, specific to what v1.2.26 changed:

15. Read `OPTION_APPWIDGET_MIN_HEIGHT` on both Fold screens, both orientations,
    at 2×3 and at minimum resize — this decides R-02.
16. Confirm the widget picker preview shows populated text on API 36 (W-20).
17. Confirm the initial layout ("Loading…") appears on placement and is replaced
    promptly (W-25).
18. Submit, then confirm the counter shows "Saving…" at 5s and the row lands by
    ~6.5s (W-04, W-07, W-21).
19. Submit and tap Undo at ~4.9s repeatedly — confirm the grace window makes this
    deterministic rather than a coin flip (W-07).
20. Force-stop the app immediately after submit, reopen, and confirm the payload
    commits exactly once with one spreadsheet row (W-01, W-02, claim recovery).
21. Resize to maximum and confirm the layout does not leave dead space (R-04).
22. TalkBack over Composing, AwaitingCommit, and Saving states, including the
    name tap-through (W-18, R-09).

## 6. Documentation to update

- `docs/DECISIONS.md` — if PR-1 lands, ADR-013's arbitration description should
  say that claim state, not the process mutex, is what makes undo-vs-commit
  safe. Record the R-02 sizing decision as a new ADR once device data settles it.
- `docs/ARCHITECTURE.md` — the deferred-commit section should name the
  serialization boundary and state explicitly what it does and does not
  guarantee, so R-01's invariant is written down somewhere a future change will
  encounter it.
- `docs/WIDGET_REVIEW_PLAN.md` — already carries its implementation-resolution
  section; add a pointer to this document as the follow-up record.
- `docs/PROJECT_STATE.md` and `docs/HANDOFF.md` — update after each PR.

## 7. Risk and rollback

- **PR-1 is the only behavioral risk.** Making `resolveUndo` claim-aware changes
  which side wins a race that currently cannot occur, so the observable change
  should be nil; the provider tests are what prove that. Reverting restores the
  mutex-only invariant, which is correct today.
- **PR-2 touches a shipped widget's declared size.** Lowering `minResizeHeight`
  cannot shrink an existing placement on its own, but raising or lowering the
  floor can cause a launcher to re-fit one. Cosmetic, self-correcting, worth a
  release-note line.
- **No Room migration, queue field, wire contract, endpoint, application ID,
  signing, or version change is required by anything in this plan.** If a
  proposed fix starts to need one, stop and re-scope.
- Rollback is per-PR revert throughout.

## 8. Checked and found correct

Recorded so a later reader does not re-investigate:

- **End-to-end write idempotency holds.** Local: unique `eventId` index
  (`Database.kt:53`) with `OnConflictStrategy.REPLACE` (`Database.kt:166`), so a
  retried claim replaces rather than duplicates. Remote: if the row already
  synced and was acknowledged and deleted locally, a re-inserted row re-syncs and
  the backend rejects it as `duplicate` on `eventId`
  (`backend_additions.gs:2837-2839`). Both layers are required for PR #55's
  retry design to be safe, and both are present.
- **Every claim/undo path is serialized.** Verified individually against all six
  entry points, including application startup — this is what makes R-01 latent
  rather than live.
- **`force` does not bypass claim safety.** `resolveCommit` checks claim
  recoverability *before* the `force` branch (`PenWidgetTransitions.kt:38-40`),
  so `onDeleted`'s force-commit cannot steal an in-flight claim.
- **`onDeleted` cannot lose a payload.** State is cleared only when a re-read
  shows `pendingCommit == null` (`PenConsumptionWidgetProvider.kt:62-66`); a
  payload that failed to commit survives and is recovered by a later
  `flushOverdue`, which iterates all pending keys regardless of whether the
  widget still exists.
- **W-23's rounding closes.** `PRESENTATION_SCALE = 3` maps every case in the
  original table back to a whole number: 10s at 7s/use round-trips 9.999997 →
  "10s".
- **The v1→v2 payload migration cannot duplicate.** `eventId` is derived
  deterministically via `UUID.nameUUIDFromBytes` over the v1 `commitId`
  (`PenWidgetPayloadCodec.kt:62-64`), so repeated decodes of the same stored v1
  payload yield the same ID.
- **`compact` defaults safe.** `minHeightDp in 1 until subtitleMinHeightDp`
  excludes 0, so a launcher that reports no options bundle gets the full layout
  rather than the compact one.
- **The `subtitle` calls against the compact layout are safe.** RemoteViews
  actions targeting a view ID absent from the inflated layout are skipped rather
  than throwing — and the compact layout carries a zero-sized
  `widget_pen_subtitle` anyway.
- **No dangling string references.** `pen_widget_reset` was removed and nothing
  references it; the six previously-unused strings from W-17 are now all wired
  through `PenWidgetText.Resource`.
