# Cannsheet Mobile v1.3.1 remediation plan

Status: Executed. Phases 1-11 complete; Phase 9 skipped per 5.2 (phone not attached); Phase 12 documentation in progress.

Base commit: `d36bda2` (`main`, released state v1.3.0, version code `31`).

Target release: version name `1.3.1`, version code `32`, published to
`noamvb/cannsheet-mobile-releases` and installed on the production phone through
Obtainium.

---

## 0. How to use this document

You are the **orchestrator**. Read sections 1 through 5 before touching a file.
Then execute sections 6 through 14 **in order**. Do not reorder phases, do not
merge two pull requests together, and do not skip a validation step because the
change "looks trivial".

Every path in this document is repository-relative. Every Kotlin snippet is a
literal target: type it as written unless the repository contradicts it, in
which case the repository wins — verify the behaviour, correct this document in
the same pull request, and say so in the pull-request description
(`AGENTS.md`, "Coding and documentation conventions").

Before substantial work, read the files `GEMINI.md` points at: `AGENTS.md`,
`docs/PROJECT_STATE.md`, `docs/HANDOFF.md`, then `docs/ARCHITECTURE.md` and
ADR-016 through ADR-019 in `docs/DECISIONS.md`.

**If a phase's validation fails, stop and report. Do not proceed to the next
phase with a red check.**

---

## 1. Executor and subagent policy

### 1.1 Tiers

This plan assumes the orchestrator is a fast high-reasoning model. Subagents are
graded into three tiers. Map them onto whatever model family you are running;
the *capability* requirement is what matters, not the brand name.

| Tier | Capability requirement | Reasoning effort | Use for |
|------|------------------------|------------------|---------|
| **A — Deep** | Most capable reasoning model available to you | Maximum / "high" thinking budget | Design-sensitive edits (Phase 8, Phase 9), the adversarial pre-release review (Phase 11), any failure diagnosis |
| **B — Standard** | The orchestrator's own model class | High | Mechanical but non-trivial edits (Phases 6, 7, 10), writing tests, writing pull-request descriptions |
| **C — Cheap** | Smallest/fastest model available | Low | Call-site enumeration, grep sweeps, reading CI logs, polling run status, checksum comparison |

If only one model is available to you, run every task yourself at high reasoning
and simply follow the *sequencing* below. A single careful pass beats a fan-out
that loses context.

### 1.2 Hard rules for subagents

1. **Subagents never run git write operations.** No `git commit`, `git push`,
   `git tag`, `gh pr merge`, `gh release`. The orchestrator owns every mutation
   of history and every publication. A subagent that "helpfully" pushes has
   broken the delivery contract in `AGENTS.md`.
2. **Subagents never run Gradle release tasks** (`assembleRelease`) and never
   touch `app/build.gradle.kts` signing blocks.
3. **Subagents never touch the phone.** All `adb` interaction is orchestrator-only
   and read-only until Phase 13.
4. **Every subagent prompt must carry**: the exact file list it may edit, the
   exact acceptance criteria, and the sentence *"Do not modify any file outside
   this list. Report, do not fix, anything you find outside it."*
5. **Subagents return work, not conclusions.** For an edit task, the deliverable
   is the edited files plus a summary of what changed and what was validated.
   For a research task, the deliverable is a list of `path:line` facts.
6. **Never trust a subagent's "all tests pass" claim.** The orchestrator re-runs
   the Gradle gate itself before every commit. This is not redundancy; it is the
   only evidence that counts.

### 1.3 Recommended fan-out per phase

| Phase | Tier | Parallel? | Notes |
|-------|------|-----------|-------|
| 6 (strings) | B | No | Single file plus one test |
| 7 (rail inset) | B | No | One-line change; the risk is in the reasoning, not the edit |
| 8 (suppression notice) | **A** | No | Sealed-interface change with 9 call sites; a cheap tier will miss one |
| 9 (two-pane threshold) | **A** | No | Only if Phase 5 measurement says so |
| 10 (dead state) | C | No | Pure deletion |
| 11 (adversarial review) | **A** ×3 | **Yes** | Three independent reviewers, distinct lenses — see 11.2 |
| 12 (docs) | B | No | Accuracy matters more than speed |
| 13–15 (release) | orchestrator only | No | Never delegate |

Phases 6, 7, and 10 are independent of each other and of 8/9. You may prepare
their branches in parallel, but **merge them one at a time in the order given**,
rebasing each subsequent branch on the new `main`, because each merge changes
the commit the next validation runs against.

---

## 2. Environment setup (verified 2026-08-13 on this machine)

The Mac now has a working Android toolchain. It is **not** on the default PATH.
Export these in every shell that runs Gradle:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="/opt/homebrew/bin:$PATH"
```

Verified facts:

- `openjdk version "17.0.20"` at `/opt/homebrew/opt/openjdk@17`
- `$ANDROID_HOME/platforms/android-36.1` present
- `$ANDROID_HOME/build-tools/36.0.0` present
- `./gradlew --no-daemon --version` reports Gradle 9.3.1, Launcher JVM 17.0.20
- `adb` at `/opt/homebrew/bin/adb` (**not** on the default tool PATH)
- `gh` at `/opt/homebrew/bin/gh`, authenticated as `noamvb`
- `/usr/libexec/java_home -V` reports **only JDK 1.8** — this is a red herring.
  The Homebrew JDK 17 is not registered with `java_home`. Setting `JAVA_HOME`
  explicitly is mandatory; without it Gradle fails immediately.

**Never create or commit `local.properties`.** CI fails if it is tracked. Use
`ANDROID_HOME` only.

### 2.1 The one command that gates every commit

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools && ./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

This is the same gate the v1.3.0 release used. Expect roughly 6–7 minutes cold.
It must report `BUILD SUCCESSFUL`. Record the exact JVM test count from the
output; the v1.3.0 baseline was **333 JVM tests, zero failures/errors/skips**,
and this release adds more.

Backend suites are unchanged by this release and must not be modified. Run them
once anyway before the version pull request, because they are cheap:

```bash
node tests/backend_analytics_test.js && node tests/backend_contract_test.js && node tests/backend_corrections_test.js && node tests/backend_recovery_test.js && node tests/backend_spreadsheet_test.js && node tests/fake_sheets_batch_update_test.js && node tests/sandbox_performance_fixture_test.js && node tests/sandbox_provisioning_test.js && python3 -m unittest tests/test_backend_sync_benchmark.py
```

---

## 3. Ground rules (non-negotiable)

Restated from `AGENTS.md` because a plan that omits them gets them violated.

1. **One coherent change per pull request.** Unrelated cleanup goes in another
   pull request.
2. **Do not change `versionCode` or `versionName` in any pull request except
   Phase 14.** CI enforces monotonicity at release time.
3. **Do not touch** `backend_additions.gs`, `appsscript.json`,
   `sandbox_provisioning.gs`, `sandbox_performance_fixture.gs`, anything under
   `tests/`, `app/src/main/java/com/example/data/Database.kt`,
   `app/src/main/java/com/example/data/SyncEngine.kt`,
   `app/src/main/java/com/example/data/SyncQueueLogic.kt`,
   `app/src/main/java/com/example/data/Network.kt`,
   `app/src/main/java/com/example/data/Repository.kt`,
   `app/src/main/java/com/example/data/AnalyticsData.kt`,
   `.github/workflows/*`, or any `signingConfigs` / `applicationId` /
   `buildConfigField` line in `app/build.gradle.kts`. If one of these must
   change, **stop and re-plan** — it means a hidden coupling was missed.
4. **No Room schema change, no migration, no new dependency.** This release
   introduces none of the three.
5. **No change to the Apps Script contract, `ANALYTICS_VERSION`, the production
   endpoint, the application ID, or the package namespace.**
6. **Every pull-request description must contain all seven required sections**
   from `AGENTS.md`: summary; motivation/root cause; important implementation
   decisions; automated tests run and their exact results; manual validation
   performed; risks and data-safety considerations; screenshots or an
   explanation of their absence.
7. **Report what you did not run.** If a check was skipped, say so in the exact
   words. Never describe an unexecuted check as successful.
8. **Do not change existing Compose parameter order or remove any parameter** of
   `ConsumptionContent`, `HistoryContent`, `InsightsContent`, or
   `PenQuickLogCard`. Instrumentation tests call these directly; a signature
   break silently costs the project its device coverage. New parameters go last
   and carry a default.

### 3.1 Phone-safety rules

- **Never install a debug APK on the production phone.** The debug build uses
  `applicationId com.noamv.cannsheet.mobile` with no suffix and debug signing,
  so it cannot install over the release build without an uninstall — which
  deletes the Room database including pending offline queue rows.
- `adb` is at `/opt/homebrew/bin/adb`, not on the default PATH.
- The phone (`SM-F966W`) has **two displays**. A bare
  `adb exec-out screencap -p` returns a text warning, not a PNG. Use an explicit
  display id: cover `4630946872173396372` (1080×2520), inner
  `4630946449689556883` (1968×2184).
- Do not manufacture a production queue alert, a synthetic consumption entry, a
  correction, or any spreadsheet write during validation.

---

## 4. What this release fixes

Seven findings from the post-v1.3.0 review. Each names the evidence.

| ID | Finding | Severity | Phase |
|----|---------|----------|-------|
| **R1** | `docs/HANDOFF.md:44` states the backup policy backwards: "durable Room data and preferences remain eligible". The XML excludes the entire `cannsheet_db` and `sync_preferences`. Contradicts `docs/ARCHITECTURE.md` and ADR-017. | High (doc correctness) | 12 |
| **R2** | `queue_alert_stuck_body` claims per-entry age ("%1$d entries have been waiting … for over a day") but the trigger is episode-level: the queue has been *continuously non-empty* for 24h. Add three entries to a 25-hour-old episode and the notification asserts something false about three of them. ADR-017 admits the aggregate limitation; the string does not. | Medium (user-facing honesty) | 6 |
| **R3** | The navigation rail double-pads the bottom inset. `AppNavigation.kt:142–154` applies `Scaffold`'s `innerPadding` (which includes the navigation-bar inset when no bottom bar renders) to the `Row`, then `AppNavigationRail` adds `navigationBarsPadding()` again. Material3 `Scaffold` does not consume insets for descendants. | Low (visual, new surface) | 7 |
| **R4** | Runway suppression is silent. `RunwayPresentation.kt:62–75` suppresses on stale / cached / range-changing / any pending action; `InsightsScreen.kt:473` then returns before emitting anything, and the explanatory diagnostics live inside `Ready`. The headline feature vanishes with zero explanation exactly when the user is offline or has queued entries. | Medium (UX) | 8 |
| **R5** | The two-pane layout may never activate on the target device. Expanded starts at 840dp; the `SM-F966W` inner display is 1968×2184 px and its dp width may fall short. If so, PR #71 is dead code on the only device that matters. `docs/PROJECT_STATE.md:826` asks whether 40/60 is *usable*, never whether the breakpoint is *reachable*. | Medium (feature reach) | 5, 9 |
| **R6** | Undocumented backend cost: `ConsumptionScreen.kt:234` → `AnalyticsState.kt:224` → `loadInsightsCacheThenRefresh()` makes an Apps Script insights GET on every cold start of the default screen, plus one more per queue drain while Log is visible. The v1.3 plan said "Do not fetch anything new"; `SyncWorker` carries a comment rejecting exactly this trade. Recorded nowhere. | Medium | 5 (decision), 12 |
| **R7** | Dead state: `RunwayEstimateState.Ready.selectedRangeDayCount` is written and never read; `evidenceByType` is exposed on `Ready` but only consumed internally by `deriveRunwayPresentationState`. | Trivial | 10 |

Explicitly **not** in scope, recorded so they are not re-litigated:

- Adding `androidx.window` / `material3-adaptive` for hinge-aware placement.
  ADR-019 defers this to a deliberate Compose BOM upgrade. Unchanged.
- A rejection-review surface for `PARTIAL_REJECTIONS`. The alert says "Open
  Cannsheet to review them" and there is no such screen. This is inherited from
  the accepted v1.3 plan, is a feature, and deserves its own release.
- A minimum-elapsed-days floor on the month spend projection (day-1
  extrapolation is volatile). Real, but the copy already states "through day 1
  of 31". Log it; do not fix it here.
- Per-row queue enqueue timestamps. ADR-017 priced this and rejected it.

---

## 5. Open decisions — resolve before Phase 6

### D1 — R6: what to do about the Log-screen analytics fetch

Three options:

- **(A) Document only.** Record the behaviour in `docs/ARCHITECTURE.md` and
  `docs/PROJECT_STATE.md`. Zero code risk. Cost stays as-is.
- **(B) Document + debounce (recommended default).** Keep the once-per-process
  cold-start fetch, but gate *runway-only* refreshes (the `markStale()` path
  when the Insights tab is not the active surface) behind a 2-minute floor. This
  removes the "five logged hits in a session means five Apps Script reads" case
  while keeping estimates fresh in practice. Small, JVM-testable.
- **(C) Remove the Log-screen fetch entirely.** The runway would then only
  appear after the user visits Insights. Cheapest backend, worst feature.

**Default: (B).** Ask the owner once, in a single message, before starting
Phase 6. If there is no answer within your working session, implement (B) and
say plainly in the pull request that (B) was chosen by default under this plan.

If (B) is chosen, it becomes **Phase 8b**, a separate pull request after
Phase 8, specified in section 8b below. If (A) is chosen, skip 8b and cover R6
in the Phase 12 documentation pull request only.

### D2 — R5: only decidable after the Phase 5 measurement

Phase 9 is conditional. Do not pre-judge it.

---

## 6. Phase 5 — Evidence gathering (read-only, no code)

Do this first. Phase 9's existence depends on it.

### 5.1 Measure the phone's usable dp width

Connect the phone over wireless adb exactly as previous sessions did. Then:

```bash
/opt/homebrew/bin/adb shell wm size
```

```bash
/opt/homebrew/bin/adb shell wm density
```

```bash
/opt/homebrew/bin/adb shell dumpsys display | grep -i "mDisplayId\|deviceWidth\|deviceHeight\|density"
```

Compute, for the **inner** display in both orientations:

```
dp_width = pixel_width / (density_dpi / 160)
```

Record the raw command output verbatim into a scratch note. You will paste the
numbers into `docs/PROJECT_STATE.md` in Phase 12.

**Decision rule:**

- If **portrait dp width ≥ 840** → the Fold already reaches `EXPANDED`. Skip
  Phase 9 entirely. Record the measurement and close the unresolved question at
  `docs/PROJECT_STATE.md:826`.
- If **portrait dp width < 840 but ≥ 720** → execute Phase 9 with
  `TWO_PANE_MIN_WIDTH = 720.dp`.
- If **portrait dp width < 720** → execute Phase 9 but set
  `TWO_PANE_MIN_WIDTH` to the measured portrait width rounded **down** to the
  nearest 20dp, and only if that value is ≥ 600dp. Below 600dp a 40/60 split is
  not defensible; in that case skip Phase 9, and instead record in
  `docs/PROJECT_STATE.md` under "Known limitations" that the two-pane layout is
  unreachable on the production device and is effectively tablet/desktop-only.

**Do not install anything. Do not launch the app. Do not tap the widget.** This
phase is `wm size`, `wm density`, `dumpsys display`, and nothing else.

### 5.2 If the phone is unavailable

Do not guess. Skip Phase 9, and in Phase 12 record under "Unresolved questions"
the exact sentence:

> The `SM-F966W` inner-display dp width was not measured in this session, so
> whether the 840dp two-pane threshold is reachable on the production device
> remains unverified.

Then continue with the remaining phases.

---

## 7. Phase 6 — `fix/queue-alert-stuck-copy` (R2)

**Tier B. One file plus one test.**

### 7.1 Branch

```bash
git checkout main && git pull --ff-only && git checkout -b fix/queue-alert-stuck-copy
```

### 7.2 Edit `app/src/main/res/values/strings.xml`

Replace the existing `queue_alert_stuck_body` plurals block **exactly**:

```xml
    <plurals name="queue_alert_stuck_body">
        <item quantity="one">%1$d entry is waiting to sync. This phone has had unsent entries for over a day. Open Cannsheet.</item>
        <item quantity="other">%1$d entries are waiting to sync. This phone has had unsent entries for over a day. Open Cannsheet.</item>
    </plurals>
```

Why this wording: the count is the current aggregate pending count, which is
accurate. The duration claim is now attached to *the phone's queue episode*,
which is exactly what `evaluateQueueHealth` measures
(`app/src/main/java/com/example/data/sync/QueueHealth.kt:110`), rather than to
the individual entries, which the aggregate watermark cannot describe
(ADR-017, "Consequences").

Change nothing else in this file. Do not touch `queue_alert_stuck_title`, the
other three reason strings, or any settings string.

### 7.3 Update the instrumentation expectation

`app/src/androidTest/java/com/example/notifications/QueueAlertNotifierTest.kt`,
test `everyReasonMapsToResourceBackedNonSensitiveCopy`, around line 110. The
literal expected body for `STUCK_QUEUE` currently reads:

```kotlin
                    "2 entries have been waiting on this phone for over a day. " +
                    "Open Cannsheet to sync."
```

Replace with:

```kotlin
                    "2 entries are waiting to sync. This phone has had unsent " +
                    "entries for over a day. Open Cannsheet."
```

Leave every other assertion in that file untouched, including the
`assertFalse(... contains("Blue Dream"))` privacy assertion at line 68.

### 7.4 Validate

Run the section 2.1 gate. Then review the complete diff:

```bash
git diff main...HEAD
```

Confirm it is exactly two files.

### 7.5 Commit, push, pull request

```bash
git add -A && git commit -m "fix: describe the queue episode instead of per-entry age"
```

```bash
git push -u origin fix/queue-alert-stuck-copy
```

Open the pull request against `main` with the seven required sections. Title:

```
fix: describe the queue episode instead of per-entry age
```

Under "manual validation performed", state plainly: *"No device notification was
posted. The copy change is covered by the API 24 and API 36 instrumentation
run."* Under screenshots: *"No visible in-app UI change; the string is a
notification body covered by instrumentation."*

Wait for the **`Cannsheet Android PR validation`** aggregate check to pass, then
squash-merge. Then:

```bash
git checkout main && git pull --ff-only
```

---

## 8. Phase 7 — `fix/rail-inset-padding` (R3)

**Tier B. One line. The reasoning is the hard part; write it down.**

### 8.1 Branch from the new `main`

```bash
git checkout -b fix/rail-inset-padding
```

### 8.2 Edit `app/src/main/java/com/example/ui/AppNavigation.kt`

In `AppNavigationRail` (around line 188), the modifier chain currently reads:

```kotlin
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .displayCutoutPadding()
            .navigationBarsPadding()
            .testTag(AdaptiveNavigationTestTags.RAIL),
    ) {
```

Replace with:

```kotlin
    NavigationRail(
        // Scaffold already subtracts the navigation-bar inset through innerPadding
        // when no bottom bar renders, and AdaptiveNavigationLayout applies that
        // padding to the Row containing this rail. Applying navigationBarsPadding()
        // here again would double the bottom inset at medium and expanded width.
        // The display cutout is not part of Scaffold's systemBars insets, so it
        // still has to be handled here.
        modifier = Modifier
            .fillMaxHeight()
            .displayCutoutPadding()
            .testTag(AdaptiveNavigationTestTags.RAIL),
    ) {
```

Then remove the now-unused import if and only if `navigationBarsPadding` is used
nowhere else in this file:

```bash
grep -n "navigationBarsPadding" app/src/main/java/com/example/ui/AppNavigation.kt
```

If that returns only the import line, delete
`import androidx.compose.foundation.layout.navigationBarsPadding` from the
import block. If it returns other usages, **leave the import alone**.

**Do not** touch `SettingsScreen.kt`'s own `navigationBarsPadding()` (line 73).
That is pre-existing behaviour on a scrolling column, it is not part of this
finding, and changing it is unrelated cleanup.

### 8.3 Validate

Section 2.1 gate. `AdaptiveLayoutTest` must still pass — it asserts the rail's
presence, not its insets, so it should be unaffected.

There is **no automated test for this fix.** Compose inset behaviour is not
practically assertable here. Say so in the pull request in these words: *"No
automated coverage: window-inset padding is not assertable in the existing
Compose test harness. Visual verification is deferred to the post-release device
check recorded in the plan's Phase 16."*

### 8.4 Commit and merge

```bash
git add -A && git commit -m "fix: stop double-padding the navigation rail inset"
```

Push, open the pull request, wait for the aggregate check, squash-merge, pull
`main`.

---

## 9. Phase 8 — `fix/runway-suppression-notice` (R4)

**Tier A. This is the highest-churn change in the release: a sealed-interface
member changes shape and nine call sites move with it.** Give the subagent the
complete call-site list below; do not make it discover them.

### 9.1 Complete call-site inventory (verified against `d36bda2`)

| File | Line | Current |
|------|------|---------|
| `app/src/main/java/com/example/ui/RunwayPresentation.kt` | 18 | `val estimates: RunwayEstimateState = RunwayEstimateState.Suppressed,` |
| `app/src/main/java/com/example/ui/RunwayPresentation.kt` | 22 | `data object Suppressed : RunwayEstimateState` |
| `app/src/main/java/com/example/ui/RunwayPresentation.kt` | 73 | `estimates = RunwayEstimateState.Suppressed,` |
| `app/src/main/java/com/example/ui/InsightsScreen.kt` | 150 | `runwayState: RunwayEstimateState = RunwayEstimateState.Suppressed,` |
| `app/src/main/java/com/example/ui/InsightsScreen.kt` | 473 | `val ready = estimates as? RunwayEstimateState.Ready ?: return` |
| `app/src/test/java/com/example/ui/RunwayPresentationTest.kt` | 60, 76, 174, 175 | `assertSame(RunwayEstimateState.Suppressed, …)` |
| `app/src/androidTest/java/com/example/ui/InsightsRunwayTest.kt` | 327 | `estimates = RunwayEstimateState.Suppressed,` |

### 9.2 Edit `app/src/main/java/com/example/ui/RunwayPresentation.kt`

Add the reason enum immediately above `sealed interface RunwayEstimateState`:

```kotlin
/** Why estimates are withheld. Every value must produce honest user-facing copy. */
enum class RunwaySuppressionReason {
    /** No snapshot at all; the screen renders loading or error instead. */
    NO_SNAPSHOT,

    /** The snapshot came from analytics_cache and has not been confirmed live. */
    CACHED_SNAPSHOT,

    /** A live snapshot exists but a local action invalidated it. */
    STALE_SNAPSHOT,

    /** A range change is in flight; the displayed data does not match the request. */
    RANGE_CHANGING,

    /** Room has queued actions the snapshot cannot include. */
    PENDING_ACTIONS,

    /** Room has not emitted a real queue count yet; a synthetic zero must not estimate. */
    QUEUE_COUNT_UNKNOWN,
}
```

Change the sealed member from a `data object` to a `data class`:

```kotlin
sealed interface RunwayEstimateState {
    data class Suppressed(val reason: RunwaySuppressionReason) : RunwayEstimateState

    data class Ready(
        val runwayByProductId: Map<String, ProductRunway>,
        val evidenceByType: Map<String, TypeCapacityEvidence>,
        val spendRunRate: SpendRunRate?,
        val diagnostics: List<RunwayDiagnostic>,
        val selectedRangeDayCount: Int = Int.MAX_VALUE,
    ) : RunwayEstimateState
}
```

Update the default at line 18:

```kotlin
data class RunwayPresentationState(
    val insights: InsightsUiState = InsightsUiState(),
    /** Null until Room has emitted the first real queue count. */
    val pendingActionCount: Int? = null,
    val estimates: RunwayEstimateState =
        RunwayEstimateState.Suppressed(RunwaySuppressionReason.NO_SNAPSHOT),
)
```

Replace the suppression branch of `deriveRunwayPresentationState` (lines 61–75)
so it classifies the reason **in this exact precedence order**. The order is
load-bearing: it must name the most actionable cause first.

```kotlin
    val data = insights.data
    val suppressionReason = when {
        data == null -> RunwaySuppressionReason.NO_SNAPSHOT
        pendingActionCount == null -> RunwaySuppressionReason.QUEUE_COUNT_UNKNOWN
        pendingActionCount > 0 -> RunwaySuppressionReason.PENDING_ACTIONS
        insights.pendingRange != null -> RunwaySuppressionReason.RANGE_CHANGING
        insights.isFromCache -> RunwaySuppressionReason.CACHED_SNAPSHOT
        insights.isStale -> RunwaySuppressionReason.STALE_SNAPSHOT
        else -> null
    }
    if (data == null || suppressionReason != null) {
        return RunwayPresentationState(
            insights = insights,
            pendingActionCount = pendingActionCount,
            estimates = RunwayEstimateState.Suppressed(
                suppressionReason ?: RunwaySuppressionReason.NO_SNAPSHOT,
            ),
        )
    }
```

**Trap:** the `data == null ||` in the `if` is redundant with the `when` but it
is what convinces the Kotlin compiler that `data` is non-null for the rest of
the function. Keep it, and keep the smart-cast working by not reassigning
`data`.

**Trap:** the original guard suppressed on `insights.isStale || insights.isFromCache`
*before* checking `pendingActionCount`. The reordering above changes which
*reason* is reported but must not change *whether* the state is suppressed. Any
input that was suppressed before must still be suppressed. This is exactly what
the new test in 9.5 proves.

Add the copy function at the bottom of the file, next to
`runwayDiagnosticText`:

```kotlin
/** Null means the surface renders nothing, because the screen already explains itself. */
internal fun runwaySuppressionText(
    reason: RunwaySuppressionReason,
    pendingActionCount: Int,
): String? = when (reason) {
    RunwaySuppressionReason.NO_SNAPSHOT,
    RunwaySuppressionReason.QUEUE_COUNT_UNKNOWN,
    -> null

    RunwaySuppressionReason.PENDING_ACTIONS -> {
        // Build the whole clause, not a verb fragment: "1 entry is" and
        // "2 entries are" disagree in both number and verb.
        val clause = if (pendingActionCount == 1) {
            "1 entry is waiting"
        } else {
            "$pendingActionCount entries are waiting"
        }
        "Runway estimates pause while $clause to sync, because this snapshot " +
            "cannot include them."
    }

    RunwaySuppressionReason.RANGE_CHANGING ->
        "Runway estimates pause while the range changes."

    RunwaySuppressionReason.CACHED_SNAPSHOT ->
        "Runway estimates pause until Insights refreshes from the sheet; " +
            "this is a cached snapshot."

    RunwaySuppressionReason.STALE_SNAPSHOT ->
        "Runway estimates pause until Insights refreshes from the sheet."
}
```

### 9.3 Edit `app/src/main/java/com/example/ui/InsightsScreen.kt`

Add a test tag to `InsightsRunwayTestTags` (around line 440):

```kotlin
    const val SUPPRESSION_NOTICE = "insights-runway-suppression"
```

Change `RunwaySection`'s signature and early return. Current head (line 468):

```kotlin
@Composable
internal fun RunwaySection(
    estimates: RunwayEstimateState,
    products: List<AnalyticsProductDto>,
) {
    val ready = estimates as? RunwayEstimateState.Ready ?: return
```

Replace with:

```kotlin
@Composable
internal fun RunwaySection(
    estimates: RunwayEstimateState,
    products: List<AnalyticsProductDto>,
    pendingActionCount: Int = 0,
) {
    val ready = when (estimates) {
        is RunwayEstimateState.Ready -> estimates
        is RunwayEstimateState.Suppressed -> {
            val notice = runwaySuppressionText(estimates.reason, pendingActionCount)
            if (notice != null) {
                Column(Modifier.testTag(InsightsRunwayTestTags.SECTION)) {
                    SectionCard("Runway") {
                        Text(
                            notice,
                            modifier = Modifier
                                .testTag(InsightsRunwayTestTags.SUPPRESSION_NOTICE),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            return
        }
    }
```

Update the single call site (line 318):

```kotlin
        item {
            RunwaySection(
                estimates = runwayState,
                products = data.products,
                pendingActionCount = pendingCount,
            )
        }
```

`pendingCount` is already a parameter of `InsightsContent` (line 146), so no
signature change is needed there.

**Do not** add a suppression notice to the Log screen
(`ConsumptionScreen.kt`). The runway line there is one line inside a product
card; a suppression banner would be noise. Record that choice in the pull
request.

### 9.4 Update existing tests

- `app/src/test/java/com/example/ui/RunwayPresentationTest.kt` lines 60, 76,
  174, 175: `assertSame` no longer works on a data class instance. Replace each
  with an `assertEquals` against the expected reason, for example:

```kotlin
        assertEquals(
            RunwayEstimateState.Suppressed(RunwaySuppressionReason.PENDING_ACTIONS),
            whileQueued.estimates,
        )
```

  Read each test body to pick the correct reason for its fixture. Do not
  guess — if a fixture is both cached and pending, the precedence table in 9.2
  says `PENDING_ACTIONS` wins.

- `app/src/androidTest/java/com/example/ui/InsightsRunwayTest.kt` line 327,
  inside `suppressedEstimatesRenderNeitherSurface`: change to
  `RunwayEstimateState.Suppressed(RunwaySuppressionReason.STALE_SNAPSHOT)`. That
  test asserts neither the runway rows nor the spend projection render; both
  assertions stay true. **Add** an assertion that the suppression notice *does*
  now render, and rename the test to
  `suppressedEstimatesExplainThemselvesWithoutRenderingRows`.

### 9.5 New tests (required)

In `app/src/test/java/com/example/ui/RunwayPresentationTest.kt`:

1. `suppressionPrecedencePrefersPendingActionsOverCacheAndStaleness` — a fixture
   that is simultaneously cached, stale, and pending returns
   `PENDING_ACTIONS`.
2. `everyInputSuppressedBeforeIsStillSuppressed` — for each of the six
   suppressing conditions in isolation, assert `estimates is Suppressed`. This
   is the regression test for the reordering trap in 9.2.
3. `readyStateSurvivesAFreshCompleteSnapshot` — the happy path still returns
   `Ready`.
4. `suppressionCopyNamesTheQueueCountAndNeverUsesSingularForPlural` — assert
   `runwaySuppressionText(PENDING_ACTIONS, 1)` contains `"1 entry is waiting"`
   and `runwaySuppressionText(PENDING_ACTIONS, 2)` contains
   `"2 entries are waiting"`.
5. `noSnapshotAndUnknownQueueCountRenderNoCopy` — both return `null`.

### 9.6 Validate, commit, merge

Section 2.1 gate. Title:

```
fix: explain why runway estimates are paused
```

Screenshots: this **is** a visible UI change. Producing a screenshot requires a
device or emulator you may not have. If you cannot, use these exact words in the
pull request: *"No screenshot was produced; no emulator session was run outside
CI. The new copy is asserted by name in `InsightsRunwayTest`."*

---

## 9b. Phase 8b — `fix/runway-refresh-debounce` (R6, only if D1 = B)

**Tier A. Skip this entire phase if the owner chose option (A) or (C).**

### 9b.1 Edit `app/src/main/java/com/example/ui/AnalyticsState.kt`

Add near the other file-level constants:

```kotlin
/**
 * Minimum gap between analytics refreshes initiated by the Log screen alone.
 *
 * The Insights tab still refreshes immediately on every invalidation. This floor
 * exists so a logging session that drains the queue several times in a row costs
 * one Apps Script analytics read instead of one per drained episode; the same
 * cost reasoning is recorded in SyncWorker's prefetch-gating comment.
 */
internal const val RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS: Long = 2L * 60L * 1000L
```

Add an injectable clock to the coordinator constructor **as a defaulted trailing
parameter**, so every existing construction site keeps compiling. The declared
repository type is `AnalyticsDataSource`, **not** `AnalyticsRepository` — do not
"correct" it:

```kotlin
class AnalyticsCoordinator(
    private val repository: AnalyticsDataSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
```

There are 10+ existing `AnalyticsCoordinator(repository, coordinatorScope)` call
sites in `app/src/test/java/com/example/ui/AnalyticsCoordinatorTest.kt`. The
default keeps every one of them compiling; only the new tests in 9b.2 pass a
third argument.

Add the tracking field beside the other private vars:

```kotlin
    private var lastInsightsRefreshStartedAtEpochMillis: Long? = null
```

Set it at the top of `refreshInsights`, immediately after
`insightsJob?.cancel()`:

```kotlin
        lastInsightsRefreshStartedAtEpochMillis = clock()
```

Replace `shouldRefreshInsightsWhileVisible()`:

```kotlin
    private fun shouldRefreshInsightsWhileVisible(): Boolean = when {
        // The Insights tab is the surface the user is actually looking at.
        insightsScreenVisible && !historyVisible -> true
        // The Log screen only carries one derived line per product. Refreshing it
        // on every queue drain would buy freshness the user cannot see at the
        // price of an Apps Script read per logged entry.
        runwayScreenVisible -> runwayOnlyRefreshIsDue()
        else -> false
    }

    private fun runwayOnlyRefreshIsDue(): Boolean {
        val last = lastInsightsRefreshStartedAtEpochMillis ?: return true
        val now = clock()
        if (now < last) return true // Backwards clock: do not wedge the floor shut.
        return now - last >= RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS
    }
```

**Do not** change `ensureInsightsLoadedOrRefreshStale()` or
`loadInsightsCacheThenRefresh()`. The once-per-process cold-start fetch stays;
without it the Log screen has no live snapshot and the runway is permanently
suppressed.

### 9b.2 Tests

Extend `app/src/test/java/com/example/ui/AnalyticsCoordinatorTest.kt` with a
controllable clock:

1. `markStaleWithOnlyTheLogScreenVisibleSkipsARefreshInsideTheFloor`
2. `markStaleWithOnlyTheLogScreenVisibleRefreshesOnceTheFloorElapses`
3. `markStaleWithTheInsightsTabVisibleRefreshesImmediatelyRegardlessOfTheFloor`
4. `aBackwardsClockDoesNotWedgeTheRunwayOnlyFloorShut`
5. `theColdStartLoadStillFetchesOnce`

Follow the existing fake-repository pattern in that file; do not introduce a
mocking library.

### 9b.3 Merge

Title:

```
perf: bound Log-screen analytics refreshes
```

Under "risks", state: *"Estimates on the Log screen can lag a queue drain by up
to two minutes. The Insights tab is unaffected. No queue, sync, or
acknowledgement behaviour changes."*

---

## 10. Phase 9 — `fix/two-pane-threshold` (R5, conditional on Phase 5)

**Tier A. Execute only if the Phase 5 decision rule says so.**

### 10.1 Edit `app/src/main/java/com/example/ui/WindowWidth.kt`

Keep `windowWidthFor` canonical — ADR-019 and `WindowWidthTest` depend on the
Material boundaries. Add a separate, explicitly named threshold:

```kotlin
/**
 * Minimum width at which a 40/60 list-detail split leaves both panes usable.
 *
 * Deliberately independent of [WindowWidth]: the Material expanded boundary
 * classifies windows, while this value answers a narrower product question about
 * one specific layout. The production Samsung Fold inner display measures below
 * the expanded boundary, so tying panes to EXPANDED made the layout unreachable
 * on the only device the feature targets.
 */
val TWO_PANE_MIN_WIDTH: Dp = <VALUE_FROM_PHASE_5>.dp

fun supportsTwoPaneDetail(maxWidth: Dp): Boolean = maxWidth >= TWO_PANE_MIN_WIDTH
```

### 10.2 Thread the boolean through, do not overload `WindowWidth`

`app/src/main/java/com/example/ui/AppNavigation.kt`:

- In `AdaptiveNavigationLayout` (line 130), after `val windowWidth = windowWidthFor(maxWidth)` add:

```kotlin
        val twoPane = supportsTwoPaneDetail(maxWidth)
```

- Change the content lambda type from `@Composable (WindowWidth) -> Unit` to
  `@Composable (WindowWidth, Boolean) -> Unit`, and the invocation at line 161 to
  `content(windowWidth, twoPane)`.
- In `CannsheetApp` (line 92), change `{ windowWidth ->` to
  `{ windowWidth, twoPane ->` and line 98 to:

```kotlin
                composable(Screen.Insights.route) {
                    InsightsScreen(viewModel, windowWidth = windowWidth, twoPane = twoPane)
                }
```

`app/src/main/java/com/example/ui/InsightsScreen.kt` — add a trailing defaulted
parameter to each of the three composables and replace the six width
comparisons:

| Line | Current | Becomes |
|------|---------|---------|
| 91 | `windowWidth: WindowWidth = WindowWidth.COMPACT,` (on `InsightsScreen`) | add `twoPane: Boolean = false,` after it |
| 122 / 137 | `windowWidth = windowWidth,` | add `twoPane = twoPane,` |
| 151 | `windowWidth: WindowWidth = WindowWidth.COMPACT,` (on `InsightsContent`) | add `twoPane: Boolean = false,` after it |
| 171 | `if (windowWidth != WindowWidth.EXPANDED)` | `if (!twoPane)` |
| 192 | `if (windowWidth == WindowWidth.EXPANDED)` | `if (twoPane)` |
| 417 | `if (windowWidth == WindowWidth.EXPANDED)` | `if (twoPane)` |
| 551 | `windowWidth: WindowWidth = WindowWidth.COMPACT,` (on `HistoryContent`) | add `twoPane: Boolean = false,` after it |
| 627 | `if (windowWidth != WindowWidth.EXPANDED)` | `if (!twoPane)` |
| 694 | `if (windowWidth == WindowWidth.EXPANDED)` | `if (twoPane)` |
| 787 | `if (windowWidth == WindowWidth.EXPANDED)` | `if (twoPane)` |

**Trap:** `windowWidth` may become unused inside `InsightsContent` and
`HistoryContent` after this. **Keep the parameter anyway** — rule 3.8 forbids
removing parameters from these composables, and the existing instrumentation
tests pass it. Add `@Suppress("UNUSED_PARAMETER")` only if the build warns as an
error; otherwise leave it.

### 10.3 Tests

- `app/src/test/java/com/example/ui/WindowWidthTest.kt`: **do not change the
  existing three tests.** Add `supportsTwoPaneDetailBoundaries` covering
  `TWO_PANE_MIN_WIDTH - 1.dp` (false), `TWO_PANE_MIN_WIDTH` (true), `0.dp`
  (false), and a very large width (true).
- `app/src/androidTest/java/com/example/ui/InsightsRunwayTest.kt:234` and
  `app/src/androidTest/java/com/example/ui/HistoryContentTest.kt:85`: these pass
  `windowWidth = WindowWidth.EXPANDED` to get panes. Add `twoPane = true`
  alongside. Keep the `windowWidth` argument so the tests still exercise the
  real parameter set.
- `app/src/androidTest/java/com/example/ui/AdaptiveLayoutTest.kt`: add a test
  that a container at `TWO_PANE_MIN_WIDTH` renders the rail **and** that a
  container 1dp narrower does not offer panes. Reuse the existing sized-container
  helper rather than writing a new harness.

### 10.4 Merge

Title:

```
fix: make the two-pane threshold reachable on the production display
```

The pull-request description **must** quote the Phase 5 measurement verbatim
(`wm size`, `wm density`, computed dp) as the motivation. This is the evidence
that justifies departing from the Material boundary; without it the change looks
arbitrary to the next reader.

---

## 11. Phase 10 — `chore/runway-dead-state` (R7)

**Tier C. Pure deletion.**

### 11.1 `selectedRangeDayCount`

Remove the field from `RunwayEstimateState.Ready`
(`app/src/main/java/com/example/ui/RunwayPresentation.kt:29`) and its assignment
at line 148. Then:

```bash
grep -rn "selectedRangeDayCount" app/src
```

must return nothing. If any test constructs `Ready(...)` with named arguments
including it, remove that argument too.

### 11.2 `evidenceByType`

**Keep it.** `RunwayPresentationTest.kt:105` asserts against
`ready.evidenceByType`, and it is genuine evidence a future diagnostic surface
will want. Removing it would delete test coverage of
`buildTypeCapacityEvidence`. Record that decision in the pull-request
description in one sentence so the next reviewer does not re-raise it.

### 11.3 Merge

Title:

```
chore: drop unread runway presentation state
```

---

## 12. Phase 11 — Adversarial pre-release review

**Tier A ×3, in parallel. This runs before the documentation pull request, on
the merged `main`, not on any branch.**

```bash
git checkout main && git pull --ff-only && git log --oneline -12
```

Spawn three independent reviewers with **distinct lenses**. Give each the same
diff scope (`git diff d36bda2..HEAD`) and a different question. Each returns a
list of `path:line` findings with a concrete failure scenario — not opinions.

1. **Correctness lens.** "Find any input for which the changed code produces a
   wrong value, crashes, or changes queue/sync/acknowledgement behaviour.
   Default to reporting nothing unless you can name the exact input."
2. **Regression lens.** "Find any behaviour that worked in v1.3.0 and no longer
   works. Pay specific attention to: the six suppression inputs, the compact
   modal-sheet path, correction dialogs at both widths, and the notification
   copy on API 24 versus API 33+."
3. **Contract lens.** "Find any violation of `AGENTS.md`: a touched file from the
   do-not-touch list, a changed Compose parameter order, a version metadata
   change outside the release pull request, a new dependency, a schema change, or
   a documentation claim not supported by the code."

Kill any finding that two of the three reviewers cannot reproduce. Fix what
survives in a **new, separate pull request** — do not amend merged history.

---

## 13. Phase 12 — `docs/v1-3-1-state` (R1, R5, R6, plus this plan)

**Tier B. Accuracy is the deliverable.**

### 13.1 Commit this plan

Add `docs/V1_3_1_FIX_PLAN.md` (this file) to the repository, updating its Status
line to reflect what was actually executed, including any phase that was skipped
and why.

### 13.2 Fix `docs/HANDOFF.md` (R1 — the highest-value line in this release)

Line 44 currently reads:

> - Backup and device-transfer behavior is deliberate: durable Room data and
>   preferences remain eligible, while transient cache and in-flight widget
>   arbitration state are excluded as recorded in the backup XML and ADRs.

Replace with:

```markdown
- Backup and device-transfer behavior is deliberate: only user settings
  (`consumption_preferences`, `purchase_defaults`) remain eligible. The Room
  database `cannsheet_db`, `sync_preferences`, and `pen_widget_state` are
  excluded from both cloud backup and device transfer, because restoring them
  would reintroduce queue/request identity, a point-in-time analytics snapshot,
  queue-alert episode state, or an in-flight deferred widget payload. A phone
  lost while holding unsynced queue rows loses them; that trade is recorded in
  ADR-017 and `app/src/main/res/xml/backup_rules.xml`.
```

Then rewrite the rest of `docs/HANDOFF.md` for the v1.3.1 release per
`AGENTS.md`'s handoff protocol and `.agents/skills/project-handoff/SKILL.md`.

### 13.3 Update `docs/ARCHITECTURE.md` (R6)

In the "Inventory runway" subsection, after the paragraph beginning "Inventory
runway and current-month spend pace are derived on Android", add:

```markdown
The Log screen is a second consumer of the Insights snapshot. Entering it marks
the runway surface visible, which loads the analytics cache and performs one
live Insights fetch per process, and a queue drain while it is the only visible
analytics surface can trigger a further refresh. This is the cost of showing a
per-product estimate outside the Insights tab; it adds no request type, field, or
contract, only additional reads of the existing endpoint.
```

If D1 = B, append: `Runway-only refreshes are additionally floored at two
minutes; the Insights tab is never floored.`

If Phase 9 ran, update the adaptive-layout paragraph to describe
`TWO_PANE_MIN_WIDTH` as separate from the Material classification.

### 13.4 Update `docs/PROJECT_STATE.md`

- Add a "v1.3.1 fix work" section mirroring the v1.3 table: each pull request,
  its merged `main` commit, and its exact-main run URL.
- Record the Phase 5 measurement verbatim under a new subsection.
- Under "Known limitations", add the two-pane reachability outcome and, if
  Phase 7's visual check has not happened yet, the fact that rail inset padding
  has no automated coverage.
- Under "Unresolved questions", **remove** the two-pane width question at line
  826 if Phase 5 answered it; otherwise restate it with the measured numbers.

### 13.5 Update `docs/DECISIONS.md`

Add ADRs only for durable decisions actually made:

- **ADR-020** (only if Phase 9 ran): "Separate the two-pane threshold from the
  Material width class." Context: the measurement. Decision, rationale,
  consequences, related files — match the house format exactly.
- **ADR-021** (only if D1 = B): "Floor Log-screen-initiated analytics
  refreshes." State the cost being bought and the freshness being given up.

Do not write an ADR for the string fix, the inset fix, or the dead-state
deletion. They are corrections, not decisions.

### 13.6 Update `AGENTS.md` if and only if a rule changed

If D1 = B, add one bullet under "Coding and documentation conventions":

```markdown
- The Log screen may consume the Insights snapshot but must not drive
  unbounded analytics refreshes; runway-only refreshes are floored.
```

Otherwise leave `AGENTS.md` alone.

### 13.7 Merge

Title:

```
docs: record the v1.3.1 fixes and decisions
```

A documentation-only pull request is classified `run_backend=false`,
`run_android=false` — only the classify and aggregate jobs run on the **pull
request**. That is expected. The **push to main** after merge always runs all
six jobs (`.github/workflows/android-pr-checks.yml:127–131`), which is what the
release gate reads.

---

## 14. Phase 13 — `release/v1.3.1` (version only)

**Orchestrator only. Never delegate. This pull request changes exactly one file
and exactly two lines.**

```bash
git checkout main && git pull --ff-only && git checkout -b release/v1.3.1
```

In `app/build.gradle.kts`, lines 38–39:

```kotlin
    versionCode = 32
    versionName = "1.3.1"
```

Nothing else. No source, no docs, no resources.

Verify before committing:

```bash
git diff main...HEAD --stat
```

Must read exactly `app/build.gradle.kts | 4 +-` (two additions, two deletions).

```bash
git diff --check
```

Run the full section 2.1 gate plus the backend suites from section 2. Record the
exact JVM test count and the Gradle wall time; you will quote both in
`docs/PROJECT_STATE.md` later.

Title:

```
chore: prepare v1.3.1 release
```

Merge, then:

```bash
git checkout main && git pull --ff-only && git rev-parse HEAD
```

**Record that SHA.** It is the only commit the tag may point at.

---

## 15. Phase 14 — Tag, publish, verify

### 15.1 Wait for the exact-main run

The release workflow refuses to publish unless the tagged SHA has a completed,
successful **push-to-main** run of `android-pr-checks.yml` with all six of these
jobs at `conclusion == success`
(`.github/workflows/release-apk.yml:73–102`):

```
Classify changes and scan repository
Backend validation
Android static validation
Emulator API 24
Emulator API 36
Cannsheet Android PR validation
```

Poll it (Tier C is fine for this):

```bash
gh run list --workflow=android-pr-checks.yml --branch=main --event=push --limit 3
```

```bash
gh run watch <RUN_ID> --exit-status
```

Do not tag until this is green. A tag on an unvalidated commit fails the release
and leaves a tag you then have to delete from a public repository.

### 15.2 Tag

```bash
git tag -a v1.3.1 -m "Cannsheet Mobile 1.3.1"
```

```bash
git push origin v1.3.1
```

The tag push triggers `Publish signed APK`. It will, in order: re-prove the
exact-SHA validation; confirm `versionName` matches the tag and the tagged
commit is the tip of `origin/main`; confirm all five release secrets exist;
download the previously published APK from `noamvb/cannsheet-mobile-releases`
and require `32 > 31`; run `testDebugUnitTest lintDebug`; build and sign
`assembleRelease`; verify the signature and badging; refuse to overwrite an
existing public `v1.3.1`; publish `Cannsheet-Mobile-1.3.1.apk` and
`Cannsheet-Mobile-1.3.1.apk.sha256` to the releases repository; then re-download
and re-verify what it just published.

Watch it:

```bash
gh run list --workflow=release-apk.yml --limit 3
```

```bash
gh run watch <RUN_ID> --exit-status
```

### 15.3 Independent verification (do not skip — this is separate from the
workflow's own post-publication check)

```bash
mkdir -p /tmp/cannsheet-1.3.1 && gh release download v1.3.1 --repo noamvb/cannsheet-mobile-releases --dir /tmp/cannsheet-1.3.1
```

```bash
cd /tmp/cannsheet-1.3.1 && shasum -a 256 -c Cannsheet-Mobile-1.3.1.apk.sha256
```

```bash
/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0/aapt dump badging /tmp/cannsheet-1.3.1/Cannsheet-Mobile-1.3.1.apk | head -5
```

```bash
/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0/apksigner verify --verbose --print-certs /tmp/cannsheet-1.3.1/Cannsheet-Mobile-1.3.1.apk
```

Assert: package `com.noamv.cannsheet.mobile`, versionCode `32`, versionName
`1.3.1`, minSdk 24, targetSdk 36, one APK Signature Scheme v2 signer.

**Signer continuity is mandatory.** The certificate subject reads as an Android
debug subject — that is expected and documented; it is the same key as v1.2.27
and v1.3.0. Compare the **certificate SHA-256 digest** printed above against the
digest of the currently installed APK. If they differ, **stop** — an in-place
update will fail and the only recovery is an uninstall, which destroys the queue.

To get the installed APK's digest without touching the app:

```bash
/opt/homebrew/bin/adb shell pm path com.noamv.cannsheet.mobile
```

```bash
/opt/homebrew/bin/adb pull <PATH_FROM_ABOVE> /tmp/cannsheet-installed.apk
```

```bash
/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0/apksigner verify --print-certs /tmp/cannsheet-installed.apk | grep -i "SHA-256 digest"
```

---

## 16. Phase 15 — Obtainium update on the phone

This is the owner's step. Hand them these instructions verbatim; do not attempt
to drive the phone's UI.

Obtainium already tracks
`https://github.com/noamvb/cannsheet-mobile-releases`. Once the release
workflow reports success:

1. Open **Obtainium** on the phone.
2. Pull to refresh, or open the **Cannsheet Mobile** app entry and tap the
   refresh icon. It should now show **1.3.1** as available.
3. Tap **Update**. Android's package installer appears.
4. Confirm the install. Because the signing key is unchanged, this is an
   in-place update: the Room database, the pending offline queue, DataStore
   preferences, and any placed widget all survive. **There must be no uninstall
   prompt.** If Android asks to uninstall first, **cancel** — that means a
   signing mismatch, and installing would delete queued entries.
5. Open Cannsheet → **Settings**. Confirm the header reads
   `Version: 1.3.1 (32)`.

If Obtainium does not see the new version, the cause is almost always release
propagation lag or a stale cache. Confirm the release exists first:

```bash
gh release view v1.3.1 --repo noamvb/cannsheet-mobile-releases
```

### 16.1 Post-install verification (owner, on-device, non-destructive)

None of these create data:

- **R3** — unfold to the inner display. The navigation rail's bottom item should
  sit a normal distance above the gesture/navigation bar, not floating well
  above it.
- **R5** — on the inner display, open **Insights** and tap a product. If Phase 9
  ran, the detail appears in a right-hand pane rather than a bottom sheet. In
  cover-display portrait it must still open as a bottom sheet.
- **R4** — open **Insights** while something is queued (or immediately after
  logging, during the countdown). The Runway card should now say why estimates
  are paused instead of disappearing.
- **R2** — cannot be verified without a genuinely stuck 24-hour queue. Do not
  manufacture one. This stays an open item.

Report what was checked and what was not into `docs/PROJECT_STATE.md`.

---

## 17. Phase 16 — Post-release handoff

Final pull request, after the phone check:

- `docs/PROJECT_STATE.md`: release evidence for v1.3.1 in the same shape as the
  v1.3.0 block — tag SHA, publication run URL, independent checksum, `aapt`
  metadata, signer continuity, and the bounded phone session.
- `docs/HANDOFF.md`: current outcome, what was validated, and what was not.
- Move any surviving unverified item into "Unresolved questions".

Title:

```
docs: record v1.3.1 release handoff
```

---

## 18. Failure playbooks

| Symptom | Cause | Action |
|---------|-------|--------|
| `./gradlew` fails instantly with a JVM error | `JAVA_HOME` unset; `/usr/libexec/java_home` only knows JDK 8 | Export the section 2 variables in that shell |
| `SDK location not found` | `ANDROID_HOME` unset | Export it. **Do not** create `local.properties` |
| Release workflow fails at "Confirm tested main commit" | Tag pushed before the push-to-main run finished, or a job was skipped | Delete the tag locally and remotely, wait for green, re-tag |
| Release workflow fails at "Require monotonic versionCode" | `versionCode` not greater than 31 | Fix in a new version-only pull request; never force-push a merged commit |
| Release workflow fails at "Reject existing public versions" | `v1.3.1` already published | Bump to 1.3.2 / code 33. **Never delete a published public release** |
| Obtainium offers the update but Android demands an uninstall | Signing key mismatch | **Stop.** Do not uninstall. Re-verify signer continuity per 15.3 |
| A Compose instrumentation test fails only on API 24 | An API-gated call reached the pre-26 branch | Guard on `Build.VERSION.SDK_INT`; the API 24 emulator has caught this class of bug before (PR #61) |
| `adb exec-out screencap -p` returns text, not a PNG | Two displays; no id given | Pass the explicit display id from section 3.1 |

---

## 19. Definition of done

- [ ] Phase 5 measurement recorded verbatim, or its absence recorded verbatim
- [ ] D1 answered by the owner or defaulted to (B) with that stated in the PR
- [ ] Phases 6, 7, 8, (8b), (9), 10 each merged as their own squash commit, each
      with a green `Cannsheet Android PR validation`
- [ ] Phase 11 adversarial review run; surviving findings fixed in their own PR
- [ ] `docs/HANDOFF.md:44` backup statement corrected — **the single highest-value
      line in this release**
- [ ] `grep -rn "selectedRangeDayCount" app/src` returns nothing
- [ ] Documentation PR merged; six-job push-to-main run green
- [ ] Version PR is exactly two changed lines in `app/build.gradle.kts`
- [ ] `v1.3.1` tag points at the exact validated `main` tip
- [ ] `Publish signed APK` run green end to end
- [ ] Independent download: checksum matches, `aapt` reports code 32 / name
      1.3.1, `apksigner` reports one v2 signer, certificate digest matches the
      installed APK
- [ ] Obtainium shows 1.3.1; in-place update completes with **no** uninstall
      prompt; Settings reads `Version: 1.3.1 (32)`
- [ ] Post-release handoff PR merged
- [ ] Every check that was not run is named in writing, in the pull request that
      should have run it
