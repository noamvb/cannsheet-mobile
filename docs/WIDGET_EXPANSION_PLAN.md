# Widget expansion plan (v1.4.5 and v1.5.0)

Written: 2026-08-20
Baseline: `main` at `ee1e6ab`, versionCode 40, versionName 1.4.4.

This plan takes the home-screen widget work from an empty working tree to two
published, signed APKs installable through Obtainium. It is 16 pull requests
across two releases.

**Read this whole section before touching a file.**

---

> **Correction (2026-08-20).** Section §7 PR A3 step 8 of this plan dictated
> `android:configure="com.noamv.cannsheet.mobile/com.example.widget.PenWidgetConfigureActivity"`.
> **That is wrong.** Android parses `android:configure` as a bare class name and
> builds `ComponentName(providerPackage, value)`, so the flattened form yields a
> class name containing a slash and a component that cannot be started. The pen
> widget's configuration activity was unreachable in the published v1.4.5 and
> v1.5.0 APKs as a result. Use the bare fully-qualified class name. Fixed in
> PR #128; see `docs/WIDGET_FIX_PLAN.md` §5 and ADR-042's sibling entry in
> `docs/PROJECT_STATE.md`.
>
> The plan is kept as written, rather than edited in place, so the executed
> history stays readable.

## 1. Executor contract

You are the executor. These rules are not advice.

1. **Never commit to `main`.** Every change goes on a branch and reaches `main`
   only through a squash-merged pull request.
2. **One pull request per numbered PR in this plan.** Do not combine two. Do not
   split one. `AGENTS.md` requires one coherent change per pull request.
3. **A PR is not done when the code is written.** It is done when the pull
   request exists on GitHub, CI is green, and it is merged. Section 4 is the
   literal command list. Run it every time.
4. **Never report a check as passing unless you ran it and saw it pass.** If a
   command was skipped or failed, say so in the pull request description in
   plain words.
5. **Do not invent symbols.** Every Kotlin symbol, resource name, and file path
   in this plan either exists in the repo today or is created by an earlier
   numbered step in this plan. If you cannot find something, stop and report it
   rather than writing a plausible substitute.
6. **Do not change** `versionCode`, `versionName`, signing config, the Apps
   Script endpoint, the application ID, or the package namespace, except in the
   two version-bump PRs (A7 and B9) that explicitly say to.
7. **Never install a debug APK on the physical phone.** Debug uses
   `applicationId com.noamv.cannsheet.mobile` with no suffix, so it cannot
   install over the release build without an uninstall, and an uninstall deletes
   the Room database including pending offline queue rows. Use the emulator
   (section 5) or the existing `sandbox` build type, which already carries
   `applicationIdSuffix = ".sandbox"`.
8. **Read `AGENTS.md` before your first edit** and obey every rule in it. The
   ones that will bite you here are quoted inline at the PRs they affect.

### Conventions this repo actually uses

Match these exactly. Do not substitute what is more common elsewhere.

| Thing | This repo does | Do NOT do |
| --- | --- | --- |
| Test framework | JUnit 4, `org.junit.Test`, `org.junit.Assert.assertEquals` | JUnit 5, kotlin.test |
| Test method names | camelCase sentences, no backticks: `fun heightBelowBreakpointSelectsCompactLayout()` | `` fun `height below breakpoint`() `` |
| Coroutine tests | `runBlocking { }` | `runTest { }` |
| Instrumented tests | `@RunWith(AndroidJUnit4::class)`, `ApplicationProvider.getApplicationContext<Context>()` | Robolectric (not used anywhere) |
| Commit subject | Conventional commits: `feat:`, `fix:`, `docs:`, `ci:` | Bare sentences, except the version-bump commits which use `Bump version to X.Y.Z (versionCode N)` |
| Branch names | `agent/<kebab-slug>` | `feature/…`, `main` |
| Widget strings | All copy in `app/src/main/res/values/strings.xml`, referenced as `R.string.…` | Hardcoded literals in Kotlin |
| Kotlin indent | 4 spaces in `app/src/main/java/**`, 2 spaces in `app/build.gradle.kts` | Mixing |

### Reference implementations to copy

When this plan says "copy the pattern", it means these files:

| Pattern | Copy from |
| --- | --- |
| An AppWidgetProvider | `app/src/main/java/com/example/widget/PenConsumptionWidgetProvider.kt` |
| A widget renderer | `app/src/main/java/com/example/widget/PenWidgetRenderer.kt` |
| Per-widget DataStore state | `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt` |
| A widget provider-info XML pair | `app/src/main/res/xml/pen_consumption_widget_info.xml` and `app/src/main/res/xml-v31/pen_consumption_widget_info.xml` |
| A Room migration | `AppDatabase.MIGRATION_8_9` in `app/src/main/java/com/example/data/Database.kt` |
| A Room migration test | `migrationFrom9To10PreservesProductsAndQueuedConsumptions` in `app/src/androidTest/java/com/example/data/DatabaseMigrationTest.kt` |
| A widget unit test | `app/src/test/java/com/example/widget/PenWidgetSizingTest.kt` |
| A widget instrumented test | `app/src/androidTest/java/com/example/widget/PenWidgetStateRepositoryTest.kt` |

### The existing API you will build on

Verified present on `main`. Signatures are exact.

```kotlin
// app/src/main/java/com/example/widget/PenWidgetStateRepository.kt
class PenWidgetStateRepository(context: Context)
suspend fun read(appWidgetId: Int): PenWidgetStoredState
suspend fun adjustDraftSeconds(appWidgetId: Int, delta: Int): Int
suspend fun resetDraftSeconds(appWidgetId: Int): Int
suspend fun submitCommit(appWidgetId: Int, buildPayload: (seconds: Int) -> PenWidgetCommitPayload?): PenWidgetCommitPayload?
suspend fun undo(appWidgetId: Int, commitId: String, nowMillis: Long = System.currentTimeMillis()): Boolean
suspend fun claimCommit(appWidgetId: Int, commitId: String?, nowMillis: Long, force: Boolean = false): PenWidgetCommitClaim?
suspend fun releaseClaim(appWidgetId: Int, commitId: String, claimId: String): Boolean
suspend fun completeCommit(appWidgetId: Int, commitId: String, claimId: String, nowMillis: Long): Boolean
suspend fun pendingCommits(): List<PendingPenWidgetCommit>
suspend fun clear(appWidgetId: Int)
data class PenWidgetStoredState(val draftSeconds: Int, val pendingCommit: PenWidgetCommitPayload?, val lastQueuedAtMillis: Long?)
// DataStore name "pen_widget_state"; key prefixes "draft_seconds_", "pending_commit_", "last_queued_at_"

// app/src/main/java/com/example/widget/PenWidgetActions.kt
fun pendingIntent(context: Context, appWidgetId: Int, action: String, commitId: String? = null): PendingIntent
// requestCode = 31 * appWidgetId + action.hashCode()

// app/src/main/java/com/example/widget/PenWidgetDraft.kt
const val STEP_SECONDS = 10
const val MAX_SECONDS = 600
const val UNDO_WINDOW_MILLIS = 5_000L
const val COMMIT_GRACE_MILLIS = 1_500L
const val QUEUED_SUBTITLE_WINDOW_MILLIS = 8_000L
const val PEN_WIDGET_PAYLOAD_VERSION = 2

// app/src/main/java/com/example/widget/PenWidgetUpdater.kt
object PenWidgetUpdater {
    fun updateAll(context: Context)
    suspend fun update(context: Context, appWidgetId: Int)
}

// app/src/main/java/com/example/widget/PenWidgetSizing.kt
fun resolve(widthDp: Int, heightDp: Int, compactBreakpointHeightDp: Int): PenWidgetLayoutSpec
data class PenWidgetLayoutSpec(val compact: Boolean, val textSizes: PenWidgetTextSizes)

// app/src/main/java/com/example/widget/PenWidgetCommitCoordinator.kt
companion object {
    suspend fun commit(context: Context, appWidgetId: Int, commitId: String?, nowMillis: Long = System.currentTimeMillis(), force: Boolean = false): Boolean
    suspend fun flushOverdue(context: Context, nowMillis: Long)
}

// app/src/main/java/com/example/data/ConsumptionLogger.kt
suspend fun log(date: String, time: String, productId: String, productUuid: String?, productType: String?,
                uses: Double, isFinished: Boolean, loggedAtEpochMillis: Long = System.currentTimeMillis(),
                eventId: String = UUID.randomUUID().toString(), updateLoadedCart: Boolean = true)

// app/src/main/java/com/example/domain/QuantityUnits.kt
fun usesToSeconds(uses: Double, secondsPerUse: Double): Double
fun secondsToUses(seconds: Double, secondsPerUse: Double): Double
fun formatQuantityInInputUnit(uses: Double, secondsPerUse: Double?): String

// app/src/main/java/com/example/domain/PenQuickLog.kt
data class Loaded(val product: Product, val presetUses: List<Double>, val secondsPerUse: Double?,
                  val syncedUses: Double?, val pendingUses: Double) : PenQuickLogState

// app/src/main/java/com/example/data/ConsumptionPreferencesRepository.kt
const val MAX_QUANTITY_PRESETS = 10
val DEFAULT_QUANTITY_PRESETS: List<Double> = listOf(0.5, 1.0, 2.0)

// app/src/main/java/com/example/data/sync/SyncScheduler.kt
fun enqueueImmediate(context: Context)

// app/src/main/java/com/example/data/CannsheetGraph.kt
fun get(context: Context): CannsheetGraph
// exposes: database, repository, consumptionPreferences, consumptionLogger,
//          analyticsRepository, syncPreferences, syncMutex, widgetRefresher
// repository.pendingActionCount: Flow<Int>

// app/src/main/java/com/example/domain/AppEntryPoints.kt
const val EXTRA_START_ROUTE = "com.noamv.cannsheet.mobile.widget.START_ROUTE"
// Routes: "consumption", "purchase", "insights", "settings"
```

---

## 2. Environment

Export all three every time you open a new shell. Without them the toolchain
looks absent and you will wrongly conclude the machine cannot build.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="/Users/sophiaparis/Library/Application Support/com.raycast.macos/NodeJS/runtime/22.14.0/bin:/opt/homebrew/bin:$PATH"
```

Notes that have cost previous sessions time:

- `/usr/libexec/java_home -V` reports only JDK 1.8. It is misleading. The
  Homebrew JDK 17 is real but unregistered. Do not use `java_home` to decide
  whether you can build.
- There is no system `node`. It comes from the Raycast path above.
- `adb` is at `/opt/homebrew/bin/adb`, not on the default PATH.
- **Never create or commit `local.properties`.** CI fails if it is tracked. Use
  `ANDROID_HOME`.

---

## 3. Subagent orchestration

You have a subagent tool. Used well it cuts wall-clock time on the large PRs.
Used badly it corrupts files and produces work you have to redo. These rules are
mandatory.

### The three hard rules

1. **One writer per file, ever.** Two subagents must never be told to edit the
   same file in the same step. There is no merge; the second write silently
   destroys the first.
2. **No git, no gradle, no `gh` inside a subagent.** Branching, committing,
   pushing, building, testing, and opening pull requests are yours alone. A
   subagent that runs `git commit` will commit a half-finished tree.
3. **Re-read before you edit.** If a subagent touched a file, read it again
   before you edit it yourself. Your memory of its contents is stale.

### What is safe to parallelize

| Safe in parallel | Why |
| --- | --- |
| Read-only research across different files | No writes at all. Have each report back; do the editing yourself. |
| Creating **new** files that no other task touches | Disjoint paths. A new provider, a new renderer, and a new test file can be written concurrently. |
| Writing **separate** test files | `PenWidgetPresetsTest.kt` and `PenWidgetConfigTest.kt` are different files. |

### What must be serial

These files are touched by nearly every PR in this plan. Only one agent — you —
edits them, and only one at a time:

- `app/src/main/res/values/strings.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`
- `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`
- `app/src/main/java/com/example/widget/PenWidgetUiModel.kt`
- `app/src/main/java/com/example/widget/PenWidgetActions.kt`
- `app/src/main/java/com/example/data/Database.kt`
- `app/build.gradle.kts`
- everything under `docs/`

Also serial by nature: anything where step N+1 needs a signature that step N
creates, and the whole of section 4.

### The pattern to use

For a large PR (A3, B3, B4, B5):

1. **Fan out to read (parallel, up to 3 subagents).** Ask each to report exact
   signatures and line numbers for one area. They must not edit.
2. **Write the shared files yourself, serially.** Strings, manifest, and any
   file in the serial list above.
3. **Fan out to create new files (parallel, only if genuinely disjoint).**
4. **Write the tests.** Separate test files may go in parallel.
5. **Validate, commit, PR — yourself, never delegated.**

### When not to bother

PRs A2, A4, A5, A7, B1, B7, and B9 are each a handful of edits to two or three
files. Spawning a subagent for them costs more than it saves. Do them yourself,
start to finish.

---

## 4. Delivery ritual

Run this for **every** PR in this plan. It is the same every time; only the
branch name, commit subject, and PR title change. Each PR section below ends by
naming those three values.

```bash
git switch main && git pull --ff-only && git switch -c <BRANCH>
```

Implement the change. Then validate:

```bash
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

A pass ends with `BUILD SUCCESSFUL`. Roughly 6-7 minutes cold. If any PR in this
plan touches `backend_additions.gs` or anything under `tests/` — none of them
should — also run the backend suites listed in `AGENTS.md`.

Review the whole diff before committing:

```bash
git status && git diff
```

Confirm: no secrets, no `local.properties`, no personal machine paths, no
accidental version or signing changes, no endpoint or application-ID changes.

```bash
git add -A
git commit -m "<COMMIT SUBJECT>" -m "<one paragraph explaining what and why>" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push -u origin <BRANCH>
```

Open the pull request. The description **must** contain all seven sections
`AGENTS.md` requires:

```bash
gh pr create --base main --title "<PR TITLE>" --body "$(cat <<'BODY'
## Summary
…

## Motivation
…

## Implementation decisions
…

## Automated tests run
`./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug` — paste the exact result here.
List every check you did NOT run and say why.

## Manual validation
What you did on the emulator, and on which API level. If none, say so plainly.

## Risks and data safety
…

## Screenshots
Attach before/after for any visible widget change, or state why they are absent.
BODY
)"
```

Wait for checks, then merge:

```bash
gh pr checks <number> --watch
gh pr merge <number> --squash --delete-branch
```

> `gh pr merge` is sometimes refused by the permission classifier on this
> machine. If it is, hand the exact command to the repository owner and wait for
> them to merge. Do not work around it.

**This PR is not complete until the pull request exists, CI is green, and it is
merged.** Do not start the next PR before this one is merged, because every
following PR branches from the updated `main`.

---

## 5. Emulator validation

Widget work cannot be proven by compilation. Every PR that changes something
visible must be checked on the emulator.

```bash
cd "$ANDROID_HOME/emulator" && ./emulator -avd cannsheet_widget_api36 -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
```

It fails if launched from another directory. `avdmanager` needs `JAVA_HOME`
exported or it reports JDK 1.8 and refuses.

Run one instrumented class:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.widget.PenWidgetStateRepositoryTest
```

Dark mode: `adb shell cmd uimode night yes`.

To keep files a test wrote, add
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` — Gradle
uninstalls both APKs by default, which deletes `getExternalFilesDir(...)`.

**Widget checklist** — walk this for every widget PR:

1. Add the widget from the picker. It renders, no blank card.
2. Resize to the smallest allowed size, then the largest. Text scales, nothing
   clips.
3. Light and dark mode both legible.
4. Every button responds and the state survives a launcher restart
   (`adb shell am force-stop com.android.launcher3` or reboot the emulator).
5. Remove the widget, re-add it. No stale state from the old instance.
6. Any API-31-only path: repeat the check on an API 24 emulator, or state
   explicitly that the path is guarded and unreachable there.

---

## 6. PR map

Sixteen pull requests. Merge in this order. Later PRs assume earlier ones landed.

### Release A — v1.4.5 (versionCode 41): pen widget improvements

| PR | Title | Depends on | Size |
| --- | --- | --- | --- |
| A1 | `feat: surface quantity presets on the pen widget` | — | large |
| A2 | `feat: scale the pen widget step size with rendered size` | A1 | small |
| A3 | `feat: add a pen widget configuration activity` | A1, A2 | largest in release A |
| A4 | `feat: show sync trouble in the pen widget subtitle` | A3 | medium |
| A5 | `feat: open the cart picker directly from the pen widget` | — | small |
| A6 | `fix: preserve pen widget state across restore and cut resize reload churn` | A3 | medium |
| A7 | `Bump version to 1.4.5 (versionCode 41)` | A1–A6 all merged | small |

### Release B — v1.5.0 (versionCode 42): new surfaces

| PR | Title | Depends on | Size |
| --- | --- | --- | --- |
| B1 | `feat: add launcher shortcuts for log, purchase, and insights` | — | small |
| B2 | `feat: add a quick settings tile for one-tap pen logging` | A3 | large |
| B3 | `feat: add a sync status home-screen widget` | — | large |
| B4 | `feat: add a multi-cart quick log widget` | A1, A3 | largest in the plan |
| B5 | `feat: record consumption history locally` | — | large, highest data risk |
| B6 | `feat: add a today home-screen widget` | B5 | medium |
| B7 | `docs: allow labelled cached projections on widget surfaces` | — | docs only |
| B8 | `feat: add runway and spend projection widgets` | B7 merged first | large |
| B9 | `Bump version to 1.5.0 (versionCode 42)` | B1–B8 all merged | small |

**Ordering traps:**

- A2 changes how the step reaches the router; A3 adds a per-instance override on
  top of it. Doing A3 first means reworking it.
- B7 is documentation only and **must merge before B8**, because B8's widgets
  would otherwise violate the rule B7 amends.
- B5 must merge before B6. B6 has no data source without it.
- B2 and B4 both reuse the config state added in A3.

---

## 7. Release A — pull requests

### PR A1 — Surface quantity presets on the pen widget

**Goal.** `PenQuickLogState.Loaded.presetUses` is computed by
`buildPenQuickLogState` and passed into the widget's data source, but
`buildPenWidgetUiModel` never reads it. Today logging a typical 30s hit is four
taps (`+`, `+`, `+`, `✓`). Add a preset row so it is one tap plus the existing
undo window.

**Presets are stored as USES, not seconds.** Convert for display with
`usesToSeconds(preset, secondsPerUse)`. Tapping a preset **sets** the draft to
that many seconds; it does not add to it.

#### Files

Modified:
- `app/src/main/java/com/example/widget/PenWidgetUiModel.kt`
- `app/src/main/java/com/example/widget/PenWidgetActions.kt`
- `app/src/main/java/com/example/widget/PenWidgetActionRouter.kt`
- `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`
- `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`
- `app/src/main/java/com/example/widget/PenWidgetSizing.kt`
- `app/src/main/res/layout/widget_pen_consumption.xml`
- `app/src/main/res/layout/widget_pen_consumption_compact.xml`
- `app/src/main/res/values/strings.xml`

New:
- `app/src/test/java/com/example/widget/PenWidgetPresetsTest.kt`

#### Steps

**1. Add the actions.** In `PenWidgetActions.kt`, next to the existing
constants, add exactly three preset actions. Three, not ten — see the trap
below.

```kotlin
const val ACTION_PRESET_1 = "com.noamv.cannsheet.mobile.widget.PRESET_1"
const val ACTION_PRESET_2 = "com.noamv.cannsheet.mobile.widget.PRESET_2"
const val ACTION_PRESET_3 = "com.noamv.cannsheet.mobile.widget.PRESET_3"

val PRESET_ACTIONS: List<String> = listOf(ACTION_PRESET_1, ACTION_PRESET_2, ACTION_PRESET_3)
```

Add all three to `HANDLED_ACTIONS`. Miss this and the taps are silently dropped
by `PenConsumptionWidgetProvider.onReceive`.

**2. Add the setter.** In `PenWidgetStateRepository.kt`, add this next to
`resetDraftSeconds`, copying its structure exactly — including the guard that
makes it a no-op while a commit is pending:

```kotlin
suspend fun setDraftSeconds(appWidgetId: Int, seconds: Int): Int {
    requireValidWidgetId(appWidgetId)
    var result = 0
    dataStore.edit { preferences ->
        if (PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]) != null) {
            result = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0
        } else {
            result = seconds.coerceIn(0, MAX_SECONDS)
            preferences[draftKey(appWidgetId)] = result
        }
    }
    return result
}
```

**3. Grow the UI model.** In `PenWidgetUiModel.kt`, add a field to
`PenWidgetUiModel.Composing`:

```kotlin
val presetSeconds: List<Int>,
```

Populate it inside `buildPenWidgetUiModel`, in the `is PenQuickLogState.Loaded`
branch where `secondsPerUse != null`:

```kotlin
presetSeconds = penState.presetUses
    .asSequence()
    .map { usesToSeconds(it, penState.secondsPerUse).toInt() }
    .filter { it in 1..MAX_SECONDS }
    .distinct()
    .sorted()
    .take(3)
    .toList(),
```

Import `com.example.domain.usesToSeconds`.

**4. Add the layout row.** In `widget_pen_consumption.xml`, add a horizontal
`LinearLayout` **below** the existing `widget_pen_step_row`, with
`android:layout_weight="2"` and `android:layout_marginTop="8dp"`, containing
three `Button`s with ids `widget_pen_preset_1`, `widget_pen_preset_2`,
`widget_pen_preset_3`. Copy every attribute from the existing
`widget_pen_minus` button — `minHeight="0dp"`, `minWidth="0dp"`,
`padding="0dp"`, `stateListAnimator="@null"`,
`background="@drawable/widget_step_button"`,
`textColor="@color/widget_on_step"` — and give each `layout_width="0dp"`,
`layout_height="match_parent"`, `layout_weight="1"`.

Add the **same three ids** to `widget_pen_consumption_compact.xml`, but with
`android:visibility="gone"` and zero size. The renderer sets text and visibility
on ids that must exist in both layouts, and `setViewVisibility` on an id that is
absent throws at render time.

**5. Render them.** In `PenWidgetRenderer.buildInteractiveViews`, add a
`presetSeconds: List<Int>` parameter (pass `emptyList()` from the
`AwaitingCommit` branch — presets are disabled during the undo window). Then,
for each index `0..2`:

```kotlin
val presetIds = listOf(R.id.widget_pen_preset_1, R.id.widget_pen_preset_2, R.id.widget_pen_preset_3)
presetIds.forEachIndexed { index, viewId ->
    val value = presetSeconds.getOrNull(index)
    if (value == null || spec.compact) {
        setViewVisibility(viewId, View.GONE)
    } else {
        setViewVisibility(viewId, View.VISIBLE)
        setTextViewText(viewId, context.getString(R.string.pen_widget_seconds_short, value))
        setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, spec.textSizes.presetSp)
        setContentDescription(viewId, context.getString(R.string.pen_widget_preset_description, value))
        setBoolean(viewId, "setEnabled", awaitingCommit == null)
        setOnClickPendingIntent(viewId, pendingIntent(context, appWidgetId, PRESET_ACTIONS[index]))
    }
}
```

**6. Add `presetSp`** to `PenWidgetTextSizes` in `PenWidgetSizing.kt` and give it
a value in all three size sets: `compactSizes` 0f (hidden anyway), `baseSizes`
14f, `largestSizes` 24f. Add it to `interpolate` — every field must be listed
there or it will not compile.

**7. Route the taps.** In `PenWidgetActionRouter.handle`, add:

```kotlin
in PRESET_ACTIONS -> {
    val index = PRESET_ACTIONS.indexOf(action)
    val penState = loadPenState(context) as? PenQuickLogState.Loaded ?: return
    val secondsPerUse = penState.secondsPerUse ?: return
    val seconds = penState.presetUses
        .asSequence()
        .map { usesToSeconds(it, secondsPerUse).toInt() }
        .filter { it in 1..MAX_SECONDS }
        .distinct()
        .sorted()
        .take(3)
        .toList()
        .getOrNull(index) ?: return
    state.setDraftSeconds(appWidgetId, seconds)
}
```

`when` on a `String` with `in PRESET_ACTIONS` requires the subject to be the
`action` variable, which it already is.

**8. Add the strings** to `app/src/main/res/values/strings.xml`:

```xml
<string name="pen_widget_preset_description">Set duration to %1$d seconds</string>
```

`pen_widget_seconds_short` already exists — reuse it, do not add a duplicate.

#### Traps

- **Do NOT add one action constant per preset up to ten.** The requestCode
  formula is `31 * appWidgetId + action.hashCode()`. Ten near-identical action
  strings raise collision risk across widget instances, and a collision means
  tapping one widget's button drives another's. Three fixed slots, index into
  the sorted list.
- **Do NOT convert presets with `toInt()` before filtering.** `usesToSeconds`
  can return a fractional value; `0.4` uses at 10s/use is 4s, but `0.04` uses is
  `0` seconds, which would submit nothing. The `filter { it in 1..MAX_SECONDS }`
  is what protects you.
- **Do NOT let a preset tap bypass the pending-commit guard.** `setDraftSeconds`
  must no-op while a payload is pending, exactly like `adjustDraftSeconds`.
  Skipping that lets a preset overwrite a draft the user is about to undo.
- The duplicated preset-seconds computation in steps 3 and 7 is deliberate: the
  router cannot see the UI model. If you extract it to a shared function, put it
  in `PenWidgetUiModel.kt` as `internal fun penWidgetPresetSeconds(state: PenQuickLogState.Loaded): List<Int>`
  and call it from both. Do not put it in `PenWidgetDraft.kt`, which must not
  depend on `com.example.domain`.

#### Tests

New file `app/src/test/java/com/example/widget/PenWidgetPresetsTest.kt`, class
`PenWidgetPresetsTest`, JUnit 4, no Android dependencies. Build a
`PenQuickLogState.Loaded` by hand as `PenWidgetUiModelTest.kt` already does —
read that file first and copy its fixture helpers.

Methods:
- `presetUsesAreConvertedToSecondsWithTheProductRate()`
- `presetsBelowOneSecondAreDropped()`
- `presetsAboveTheMaximumAreDropped()`
- `atMostThreePresetsAreOffered()` — feed ten presets, assert size 3.
- `presetsAreSortedAscendingAndDeduplicated()`
- `awaitingCommitOffersNoPresets()`

#### Subagent orchestration

Mostly serial: steps 1-3, 5, 6, 7 all touch files on the serial list. Worth
parallelizing only this:

- **Parallel (2 subagents), after step 3 is written:** one writes the two layout
  XML files (step 4); one writes the test file (tests). Disjoint paths.
- Everything else: you, in the numbered order. Steps 5 and 6 must follow 3
  because they consume `presetSeconds` and `presetSp`.

#### Delivery

Run §4 with:
- BRANCH: `agent/widget-preset-row`
- COMMIT SUBJECT: `feat: surface quantity presets on the pen widget`
- PR TITLE: `feat: surface quantity presets on the pen widget`
- Screenshots required: before/after at the default size and at the largest size.

---

### PR A2 — Scale the step size with rendered size

**Goal.** `STEP_SECONDS` is a fixed 10 against `MAX_SECONDS` of 600 — sixty taps
to the ceiling. A widget that has been resized large has room for a bigger step.

#### Files

Modified: `PenWidgetSizing.kt`, `PenWidgetActions.kt`, `PenWidgetActionRouter.kt`,
`PenWidgetRenderer.kt`, `PenWidgetUpdater.kt`, `strings.xml`.
New: none — extend `app/src/test/java/com/example/widget/PenWidgetSizingTest.kt`.

#### Steps

**1.** Add `val stepSeconds: Int` to `PenWidgetLayoutSpec` (not to
`PenWidgetTextSizes` — it is not a text size). Values: `compact` → 10,
base → 10, and in `resolve`, when `growthFraction(...) >= 0.5`, → 30.

```kotlin
data class PenWidgetLayoutSpec(
    val compact: Boolean,
    val textSizes: PenWidgetTextSizes,
    val stepSeconds: Int = STEP_SECONDS,
)
```

The default keeps `PenWidgetSizing.base` and every existing test compiling.

**2.** The router hardcodes `STEP_SECONDS`. It has no access to the spec, so the
step must travel in the intent. In `PenWidgetActions.kt` add:

```kotlin
const val EXTRA_STEP_SECONDS = "com.noamv.cannsheet.mobile.widget.STEP_SECONDS"
```

and add an optional parameter to `pendingIntent`:

```kotlin
fun pendingIntent(
    context: Context,
    appWidgetId: Int,
    action: String,
    commitId: String? = null,
    stepSeconds: Int? = null,
): PendingIntent
```

Put it in the extras next to `commitId`. **Do not add it to the `data` URI** —
the URI is what makes PendingIntents distinct, and varying it by step would
create a new PendingIntent every resize, leaking them.

**3.** Because `PendingIntent` equality ignores extras, an existing `+` intent
will keep its old step unless you pass `FLAG_UPDATE_CURRENT` — which the
function already does. No change needed, but do not "fix" it to `FLAG_ONE_SHOT`.

**4.** In the renderer, pass `spec.stepSeconds` when building the `+` and `−`
intents. In the router, read it:

```kotlin
val step = intent.getIntExtra(EXTRA_STEP_SECONDS, STEP_SECONDS).coerceIn(1, MAX_SECONDS)
```

**5.** Content descriptions are currently the fixed strings
`pen_widget_increase` / `pen_widget_decrease` ("Increase duration by 10
seconds"). Replace both with formatted strings:

```xml
<string name="pen_widget_increase">Increase duration by %1$d seconds</string>
<string name="pen_widget_decrease">Decrease duration by %1$d seconds</string>
```

Then update the two `android:contentDescription` attributes in **both** layouts,
which currently reference these ids directly — a formatted string used as a
static XML attribute renders the literal `%1$d`. Remove the attribute from the
XML entirely and set it only from the renderer.

#### Traps

- **`PenWidgetSizingTest` asserts equality on whole `PenWidgetLayoutSpec`
  objects** (`assertEquals(PenWidgetSizing.base, ...)`). Adding a field with a
  default keeps those passing. Adding one without a default breaks eight tests.
- A3 will later add a per-instance override. Keep the step resolution in
  `PenWidgetSizing.resolve` and read it in one place in `PenWidgetUpdater`, so
  A3 only has to override the resolved value.

#### Tests

Add to the existing `PenWidgetSizingTest`:
- `baseSizeUsesTheDefaultStep()`
- `largeWidgetUsesTheLargerStep()`
- `compactAlwaysUsesTheDefaultStep()`

#### Subagent orchestration

Do not use subagents. This is five small edits across files that all sit on the
serial list, and the ordering is strict.

#### Delivery

- BRANCH: `agent/widget-step-scaling`
- COMMIT SUBJECT: `feat: scale the pen widget step size with rendered size`
- PR TITLE: same.
---

### PR A3 — Pen widget configuration activity

**Goal.** Every widget instance renders whatever cart is globally loaded
(`preferences.loadedPenProductId`), so two widgets can never track two carts.
Add a configuration activity that gives each instance: a pinned product
(default: follow the loaded cart), a discreet mode, and a step-size override.

This is the largest PR in release A and the foundation for B2 and B4.

#### Files

New:
- `app/src/main/java/com/example/widget/PenWidgetConfigureActivity.kt`
- `app/src/main/java/com/example/widget/PenWidgetInstanceConfig.kt`
- `app/src/test/java/com/example/widget/PenWidgetInstanceConfigTest.kt`
- `app/src/androidTest/java/com/example/widget/PenWidgetConfigStateTest.kt`

Modified:
- `PenWidgetStateRepository.kt`, `PenWidgetDataSource.kt`, `PenWidgetUpdater.kt`,
  `PenWidgetUiModel.kt`, `PenWidgetRenderer.kt`,
  `PenConsumptionWidgetProvider.kt`, `AndroidManifest.xml`,
  `res/xml/pen_consumption_widget_info.xml`,
  `res/xml-v31/pen_consumption_widget_info.xml`, `strings.xml`

#### Steps

**1. The config value object.** New file `PenWidgetInstanceConfig.kt`:

```kotlin
package com.example.widget

/**
 * Per-instance widget configuration. Every field has a default that reproduces
 * the pre-configuration behaviour, because `configuration_optional` means the
 * activity may never have run for a given instance.
 */
data class PenWidgetInstanceConfig(
    val pinnedProductId: String? = null,
    val discreet: Boolean = false,
    val stepSecondsOverride: Int? = null,
) {
    companion object {
        val DEFAULT = PenWidgetInstanceConfig()
    }
}
```

**2. Persist it.** In `PenWidgetStateRepository.kt` add three key prefixes to the
private companion, following the existing naming exactly:

```kotlin
const val PINNED_PRODUCT_PREFIX = "pinned_product_"
const val DISCREET_PREFIX = "discreet_"
const val STEP_OVERRIDE_PREFIX = "step_override_"
```

and the matching private key helpers next to `draftKey`:

```kotlin
private fun pinnedProductKey(appWidgetId: Int) = stringPreferencesKey("$PINNED_PRODUCT_PREFIX$appWidgetId")
private fun discreetKey(appWidgetId: Int) = booleanPreferencesKey("$DISCREET_PREFIX$appWidgetId")
private fun stepOverrideKey(appWidgetId: Int) = intPreferencesKey("$STEP_OVERRIDE_PREFIX$appWidgetId")
```

Import `androidx.datastore.preferences.core.booleanPreferencesKey`.

Add two functions:

```kotlin
suspend fun readConfig(appWidgetId: Int): PenWidgetInstanceConfig {
    requireValidWidgetId(appWidgetId)
    val preferences = dataStore.data.first()
    return PenWidgetInstanceConfig(
        pinnedProductId = preferences[pinnedProductKey(appWidgetId)]?.takeIf { it.isNotBlank() },
        discreet = preferences[discreetKey(appWidgetId)] ?: false,
        stepSecondsOverride = preferences[stepOverrideKey(appWidgetId)]?.takeIf { it in 1..MAX_SECONDS },
    )
}

suspend fun writeConfig(appWidgetId: Int, config: PenWidgetInstanceConfig) {
    requireValidWidgetId(appWidgetId)
    dataStore.edit { preferences ->
        val pinned = config.pinnedProductId?.trim()
        if (pinned.isNullOrBlank()) preferences.remove(pinnedProductKey(appWidgetId))
        else preferences[pinnedProductKey(appWidgetId)] = pinned
        preferences[discreetKey(appWidgetId)] = config.discreet
        val step = config.stepSecondsOverride?.takeIf { it in 1..MAX_SECONDS }
        if (step == null) preferences.remove(stepOverrideKey(appWidgetId))
        else preferences[stepOverrideKey(appWidgetId)] = step
    }
}
```

**Extend `clear(appWidgetId)` to remove all three new keys.** Forget this and a
deleted widget leaves its config behind, which a recycled widget id then
inherits.

**3. Honour the pin.** `PenWidgetDataSource.loadPenState` currently passes
`preferences.loadedPenProductId` as `explicitProductId`. Change the signature to:

```kotlin
suspend fun loadPenState(context: Context, pinnedProductId: String? = null): PenQuickLogState
```

and inside, pass `explicitProductId = pinnedProductId ?: preferences.loadedPenProductId`.

`resolveLoadedPenProduct` already falls back to the most-recently-logged
selectable pen when the explicit id does not match a selectable product, so a
pinned cart that is later marked Finished degrades gracefully. Do not add your
own fallback.

**4. Thread it through the updater.** In `PenWidgetUpdater.update`, read the
config once and use it three ways:

```kotlin
val config = PenWidgetStateRepository(appContext).readConfig(appWidgetId)
val penState = PenWidgetDataSource.loadPenState(appContext, config.pinnedProductId)
```

then pass `config.discreet` into `buildPenWidgetUiModel`, and apply
`config.stepSecondsOverride` on top of the resolved spec:

```kotlin
val spec = PenWidgetSizing.resolve(...).let { resolved ->
    config.stepSecondsOverride?.let { resolved.copy(stepSeconds = it) } ?: resolved
}
```

**5. Discreet mode.** Add `discreet: Boolean` as the last parameter of
`buildPenWidgetUiModel`, defaulted to `false` so existing tests compile. When
`discreet` is true:

- `productName` becomes `PenWidgetText.Resource(R.string.pen_widget_generic_cart)` — the string already exists ("Pen cart").
- `subtitle` becomes `PenWidgetText.Resource(R.string.pen_widget_discreet_subtitle)` — new string, `"Tap to log"`. No status, no synced count, no pending count.
- The `recentlyQueued` subtitle (`pen_widget_queued`, "Queued ✓") **still shows**. It leaks nothing.

In the renderer, discreet mode must not strip content descriptions — a blind
user is not the threat model here. Keep `pen_widget_submit_seconds` and the
counter-panel plurals exactly as they are, and use the generic name in
`pen_widget_open_app_description`.

**6. The activity.** New file `PenWidgetConfigureActivity.kt`. Use Compose, as
`MainActivity` does. Skeleton with every signature filled in:

```kotlin
package com.example.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.data.CannsheetGraph
import com.example.data.ProductTypeCodes
import com.example.data.productStatus
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PenWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must be first: the launcher deletes the widget if the activity is
        // dismissed without RESULT_OK, and defaulting to CANCELED is what makes
        // a back-press behave correctly.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            MyApplicationTheme {
                // Compose UI: a product list (radio, plus a "Follow the loaded
                // cart" option at the top), a discreet-mode Switch, and a step
                // size SegmentedButton over 5/10/30. A "Save" Button calls save().
            }
        }
    }

    private fun save(config: PenWidgetInstanceConfig) {
        lifecycleScope.launch {
            PenWidgetStateRepository(applicationContext).writeConfig(appWidgetId, config)
            PenWidgetRuntime.withSerialized {
                PenWidgetUpdater.update(applicationContext, appWidgetId)
            }
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}
```

Load the selectable pens for the picker with the same filter
`resolveLoadedPenProduct` uses:

```kotlin
val graph = CannsheetGraph.get(applicationContext)
val pens = graph.repository.allProducts.first().filter { product ->
    product.productStatus.isSelectable &&
        ProductTypeCodes.normalize(product.type) == ProductTypeCodes.PEN
}
```

`ProductTypeCodes` is `internal` — it is in the same module, so this compiles.

**7. Manifest.** Add inside `<application>`:

```xml
<activity
    android:name=".widget.PenWidgetConfigureActivity"
    android:exported="false"
    android:label="@string/pen_widget_configure_label"
    android:theme="@style/Theme.MyApplication">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
    </intent-filter>
</activity>
```

Check the exact theme name in `app/src/main/res/values/themes.xml` before
writing it; use whatever `MainActivity` uses.

**8. Provider info.** Add to **both** files:

```xml
android:configure="com.noamv.cannsheet.mobile/com.example.widget.PenWidgetConfigureActivity"
```

The `android:configure` value is `applicationId/fully-qualified-class`. The
application id is `com.noamv.cannsheet.mobile` and the namespace is
`com.example` — they differ, and getting this wrong makes the launcher refuse to
add the widget.

Add to **`xml-v31/` only**:

```xml
android:widgetFeatures="reconfigurable|configuration_optional"
```

`widgetFeatures` was added in API 31. Putting it in `res/xml/` fails `lintDebug`
with `NewApi` and breaks the CI static-validation job.

**9. Strings.** Add: `pen_widget_configure_label` ("Pen widget settings"),
`pen_widget_discreet_subtitle` ("Tap to log"), `pen_widget_follow_loaded_cart`
("Follow the loaded cart"), `pen_widget_discreet_mode` ("Discreet mode"),
`pen_widget_discreet_mode_hint` ("Hide the product name and use counts"),
`pen_widget_step_size` ("Step size"), `pen_widget_save` ("Save").

#### Traps

- **`configuration_optional` means the activity may never run.** Every read path
  must tolerate absent config. That is why every field of
  `PenWidgetInstanceConfig` is nullable or defaulted. Never write code that
  assumes a widget has been configured.
- **Existing widgets on the user's phone were created before this PR.** They
  have no config keys. `readConfig` returns `DEFAULT`, which reproduces exactly
  today's behaviour. Verify this on the emulator by adding a widget on the old
  build, then installing the new one over it.
- **`setResult(RESULT_CANCELED)` must be the first thing in `onCreate`,** before
  the early `finish()` returns. Otherwise a back-press leaves a widget the
  launcher thinks failed.
- **Do not call `AppWidgetManager.updateAppWidget` directly from the activity.**
  Go through `PenWidgetUpdater.update` inside `PenWidgetRuntime.withSerialized`,
  or you race the provider's own renders.
- **Do not make `loadPenState`'s new parameter required.** `PenWidgetDataSource`
  is called from `PenWidgetActionRouter` too; a default of `null` keeps that
  call site correct.

#### Tests

`PenWidgetInstanceConfigTest.kt` (JVM, JUnit 4):
- `defaultConfigFollowsTheLoadedCart()`
- `blankPinnedProductIdIsTreatedAsAbsent()`
- `stepOverrideOutsideTheAllowedRangeIsIgnored()`
- `discreetModelHidesTheProductName()` — asserts `buildPenWidgetUiModel(..., discreet = true)` yields the generic name resource.
- `discreetModelHidesUseCounts()`
- `discreetModelStillShowsTheQueuedConfirmation()`

`PenWidgetConfigStateTest.kt` (androidTest, `@RunWith(AndroidJUnit4::class)`,
`runBlocking`) — copy the DataStore fixture from `PenWidgetStateRepositoryTest.kt`:
- `writeConfigThenReadConfigRoundTrips()`
- `readConfigOnAnUnconfiguredWidgetReturnsDefaults()`
- `clearRemovesEveryConfigKey()`
- `configForOneWidgetDoesNotLeakToAnother()`

#### Subagent orchestration

The largest parallel opportunity in release A, but only in one place:

- **Parallel (3 subagents), after step 2 is merged into your working tree:**
  (a) write `PenWidgetConfigureActivity.kt`; (b) write
  `PenWidgetInstanceConfigTest.kt`; (c) write `PenWidgetConfigStateTest.kt`.
  Three new files, disjoint paths, no shared state. Give each the full text of
  `PenWidgetInstanceConfig` and the repository functions so they code against
  real signatures.
- **Serial, you only:** steps 1-5 and 7-9. Steps 7, 8, 9 touch the manifest, the
  two provider XMLs, and `strings.xml` — all on the serial list.
- **Never** let the subagent writing the activity also edit the manifest. It
  will, if you let it. State the prohibition in the subagent's prompt.

#### Delivery

- BRANCH: `agent/widget-config-activity`
- COMMIT SUBJECT: `feat: add a pen widget configuration activity`
- Screenshots required: the config screen, and a discreet-mode widget.
- Emulator checklist mandatory, including the "install over an old build"
  check described in the traps.

---

### PR A4 — Show sync trouble in the pen widget subtitle

**Goal.** The subtitle shows status, synced uses, and pending uses. Nothing
distinguishes a healthy queue from one that has been failing for two days.

#### Files

Modified: `PenWidgetUiModel.kt`, `PenWidgetDataSource.kt`, `PenWidgetUpdater.kt`,
`strings.xml`. New test: extend `PenWidgetUiModelTest.kt`.

#### Steps

**1.** Read the queue signal in `PenWidgetDataSource`. The aggregate count is
already exposed:

```kotlin
val pendingActionCount = graph.repository.pendingActionCount.first()
```

For "how long has it been stuck", `QueueHealthSnapshot` in
`app/src/main/java/com/example/data/sync/QueueHealth.kt` carries
`queueNonEmptySinceEpochMillis` and `QUEUE_STUCK_THRESHOLD_MILLIS` (24 hours).
Read the snapshot through `graph.syncPreferences` — open
`SyncPreferencesRepository.kt` and use the existing read accessor rather than
adding one.

**2.** Add a nullable field to whatever `loadPenState` returns, or return a
small pair. Preferred, to avoid disturbing `PenQuickLogState`:

```kotlin
data class PenWidgetData(
    val penState: PenQuickLogState,
    val queueStuck: Boolean,
)
```

`loadPenState` keeps its current signature and a new
`suspend fun loadWidgetData(context: Context, pinnedProductId: String? = null): PenWidgetData`
wraps it.

**3.** Add `queueStuck: Boolean = false` to `buildPenWidgetUiModel` and a new
subtitle branch. **Precedence, highest first** — implement exactly this order:

1. `recentlyQueued` → `pen_widget_queued`
2. `queueStuck` → new string `pen_widget_sync_stuck` = `"%1$s · sync is behind"`, argument = product status label
3. discreet → `pen_widget_discreet_subtitle`
4. existing synced/pending variants, unchanged

Queued wins over stuck because it is the immediate feedback for the tap the user
just made, and it clears itself after `QUEUED_SUBTITLE_WINDOW_MILLIS`.

#### Traps

- **`AGENTS.md` forbids product names, quantities, and dates in queue *alerts*.**
  Read the rule: it governs notifications and the alert presenter, not this
  widget, which already displays the product name by design. You are adding a
  count-free, date-free phrase to a surface that already shows more than this.
  Say so explicitly in the PR description under "Risks and data safety" so a
  reviewer does not have to re-derive it.
- **Do not read the queue on every render if it costs a query per tap.**
  `pendingActionCount` is a Room `Flow`; `.first()` on it is one query. That is
  acceptable and matches what `PenWidgetDataSource` already does for
  `pendingProductUses`. Do not add a second, redundant count query.
- Do not surface a raw pending *number* here. `pendingUses` for this product is
  already shown; an app-wide action count next to it reads as contradictory.

#### Tests

Add to `PenWidgetUiModelTest.kt`:
- `stuckQueueShowsTheSyncBehindSubtitle()`
- `recentlyQueuedOutranksStuckQueue()`
- `discreetModeOutranksTheSyncedCountsButNotQueued()`

#### Subagent orchestration

Do not use subagents. Four files, all serial, and step 3's precedence logic must
be written in one head.

#### Delivery

- BRANCH: `agent/widget-sync-indicator`
- COMMIT SUBJECT: `feat: show sync trouble in the pen widget subtitle`

---

### PR A5 — Open the cart picker directly from the pen widget

**Goal.** Tapping the product name opens the Log screen; choosing a different
cart is a further tap on "Swap cart". Land on the picker directly.

#### Files

Modified: `PenWidgetActions.kt`, `PenWidgetRenderer.kt`,
`domain/AppEntryPoints.kt`, `MainActivity.kt`, `ui/AppNavigation.kt`,
`ui/ConsumptionScreen.kt`, `strings.xml`.
New test: `app/src/androidTest/java/com/example/MainActivityIntentTest.kt` already
exists — extend it.

#### Steps

**1.** `AGENTS.md` freezes `EXTRA_START_ROUTE`:

> `EXTRA_START_ROUTE` lives in `com.example.domain` and its string value is part
> of already-issued widget `PendingIntent`s; do not change it.

So **do not touch it**. Add a sibling in the same file:

```kotlin
/** Set alongside [EXTRA_START_ROUTE] to request the cart picker on arrival. */
const val EXTRA_OPEN_CART_PICKER = "com.noamv.cannsheet.mobile.widget.OPEN_CART_PICKER"
```

**2.** New action `ACTION_OPEN_CART_PICKER` in `PenWidgetActions.kt`. In
`pendingIntent`, extend the existing activity branch so this action is treated
like `ACTION_OPEN_LOG` — route `"consumption"` — and additionally
`putExtra(EXTRA_OPEN_CART_PICKER, true)`. Keep the distinct `data` URI, which
the existing code already derives from the action.

**3.** `MainActivity` consumes the route through
`internal fun Intent.consumeStartRoute(): String?` and a
`Channel<String>`. Add a parallel one-shot:

```kotlin
internal fun Intent.consumeOpenCartPicker(): Boolean =
    getBooleanExtra(EXTRA_OPEN_CART_PICKER, false).also {
        if (it) removeExtra(EXTRA_OPEN_CART_PICKER)
    }
```

Add a second channel `private val pickerRequests = Channel<Unit>(capacity = Channel.CONFLATED)`
and feed it from both `onCreate` and `onNewIntent`, exactly as the route channel
is fed. Pass its flow into `CannsheetApp` as a new parameter
`openCartPickerRequests: Flow<Unit> = emptyFlow()`.

**4.** `ConsumptionScreen` owns the picker state locally —
`var showProductPicker by rememberSaveable { mutableStateOf(false) }` and
`var pickerMode by rememberSaveable { mutableStateOf(ProductPickerMode.LOG_TARGET) }`
at `app/src/main/java/com/example/ui/ConsumptionScreen.kt:287-288`. Thread the
flow down to `ConsumptionScreen` and collect it:

```kotlin
LaunchedEffect(openCartPickerRequests) {
    openCartPickerRequests.collect {
        pickerMode = ProductPickerMode.LOG_TARGET
        showProductPicker = true
    }
}
```

`ProductPickerMode` is a **private** enum at `ConsumptionScreen.kt:95` with
values `LOG_TARGET` and `LOADED_PEN`. Because it is private to that file, this
`LaunchedEffect` must live in `ConsumptionScreen.kt` too — do not try to
reference the enum from `AppNavigation.kt` or `MainActivity.kt`. Check which
value the "Swap cart" button uses at `ConsumptionScreen.kt:560` and use the same
one.

**5.** In the renderer, point `R.id.widget_pen_name` at
`ACTION_OPEN_CART_PICKER` instead of `ACTION_OPEN_LOG`, and update its content
description to a new string `pen_widget_open_picker_description` =
`"%1$s. Double tap to choose a different cart."`.

Keep `ACTION_OPEN_LOG` — the message-state layout
(`widget_pen_message.xml`) still uses it and `PenWidgetOpenTarget.Log` maps to it.

#### Traps

- **Do not change the value of `EXTRA_START_ROUTE`.** Already-issued
  `PendingIntent`s on the user's home screen carry the old string; changing it
  silently breaks every existing widget until the user re-adds it.
- **`Channel.BUFFERED` vs `CONFLATED`:** the route channel is `BUFFERED`. Use
  `CONFLATED` for the picker so three rapid taps do not queue three sheet
  openings.
- Cold start and warm start are different paths. `onCreate` handles cold;
  `onNewIntent` handles warm. Wire both or the feature works exactly once.

#### Tests

Extend `MainActivityIntentTest.kt`:
- `cartPickerExtraIsConsumedOnce()`
- `cartPickerExtraIsRemovedFromTheIntent()`

#### Subagent orchestration

Do not use subagents. The change is a single thread of control across six files
and every step depends on the previous one's signature.

#### Delivery

- BRANCH: `agent/widget-cart-picker-deeplink`
- COMMIT SUBJECT: `feat: open the cart picker directly from the pen widget`
- Screenshots: not required (no visual change beyond the tap target's
  destination); state that in the PR body.

---

### PR A6 — Preserve widget state across restore, cut resize reload churn

**Goal.** Two unrelated-looking defects with one owner, the provider:

1. `PenConsumptionWidgetProvider` has no `onRestored`. On backup/restore Android
   remaps app widget ids, so every per-id DataStore key
   (`draft_seconds_<id>`, `pending_commit_<id>`, `last_queued_at_<id>`, plus the
   three config keys from A3) is orphaned — a restored widget loses its pinned
   cart and, worse, a pending commit becomes unreachable and never flushes.
2. Every resize fires `onAppWidgetOptionsChanged`, which triggers a full
   `PenWidgetUpdater.update` — a DataStore read plus four Room `Flow` reads —
   for what is usually just a text-size change.

#### Files

Modified: `PenConsumptionWidgetProvider.kt`, `PenWidgetStateRepository.kt`,
`PenWidgetUpdater.kt`, `PenWidgetRenderer.kt`.
New tests: extend `PenWidgetStateRepositoryTest.kt`; new
`app/src/test/java/com/example/widget/PenWidgetRestoreTest.kt`.

#### Steps

**1. Key remapping.** Add to `PenWidgetStateRepository`:

```kotlin
/**
 * Moves every per-widget key from [oldWidgetIds][i] to [newWidgetIds][i] in one
 * edit. Android remaps app widget ids on restore; without this the restored
 * widget reads a foreign id's state and any pending commit is stranded.
 */
suspend fun remapWidgetIds(oldWidgetIds: IntArray, newWidgetIds: IntArray) {
    require(oldWidgetIds.size == newWidgetIds.size) {
        "Restored widget id arrays must be the same length."
    }
    oldWidgetIds.forEach(::requireValidWidgetId)
    newWidgetIds.forEach(::requireValidWidgetId)
    dataStore.edit { preferences ->
        // Snapshot every value first: a restore can map 42 -> 17 while 17 also
        // maps elsewhere, so removing as you go would corrupt the chain.
        val snapshot = oldWidgetIds.mapIndexed { index, oldId ->
            RemappedState(
                newWidgetId = newWidgetIds[index],
                draftSeconds = preferences[draftKey(oldId)],
                pendingCommit = preferences[pendingKey(oldId)],
                lastQueuedAtMillis = preferences[lastQueuedKey(oldId)],
                pinnedProductId = preferences[pinnedProductKey(oldId)],
                discreet = preferences[discreetKey(oldId)],
                stepSecondsOverride = preferences[stepOverrideKey(oldId)],
            )
        }

        oldWidgetIds.forEach { oldId ->
            preferences.remove(draftKey(oldId))
            preferences.remove(pendingKey(oldId))
            preferences.remove(lastQueuedKey(oldId))
            preferences.remove(pinnedProductKey(oldId))
            preferences.remove(discreetKey(oldId))
            preferences.remove(stepOverrideKey(oldId))
        }

        snapshot.forEach { state ->
            val newId = state.newWidgetId
            state.draftSeconds?.let { preferences[draftKey(newId)] = it }
            state.pendingCommit?.let { preferences[pendingKey(newId)] = it }
            state.lastQueuedAtMillis?.let { preferences[lastQueuedKey(newId)] = it }
            state.pinnedProductId?.let { preferences[pinnedProductKey(newId)] = it }
            state.discreet?.let { preferences[discreetKey(newId)] = it }
            state.stepSecondsOverride?.let { preferences[stepOverrideKey(newId)] = it }
        }
    }
}

private data class RemappedState(
    val newWidgetId: Int,
    val draftSeconds: Int?,
    val pendingCommit: String?,
    val lastQueuedAtMillis: Long?,
    val pinnedProductId: String?,
    val discreet: Boolean?,
    val stepSecondsOverride: Int?,
)
```

Put `RemappedState` as a private top-level class in the same file. Do not try to
destructure a `List<Any?>` into six variables — Kotlin's destructuring
declarations stop at `component5`.

**2. Provider overrides.** Add to `PenConsumptionWidgetProvider`:

```kotlin
override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
    super.onRestored(context, oldWidgetIds, newWidgetIds)
    val pendingResult = goAsync()
    val appContext = context.applicationContext
    PenWidgetRuntime.launchReceiver(pendingResult) {
        PenWidgetStateRepository(appContext).remapWidgetIds(oldWidgetIds, newWidgetIds)
        PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
        newWidgetIds.forEach { PenWidgetUpdater.update(appContext, it) }
    }
}

override fun onDisabled(context: Context) {
    super.onDisabled(context)
    // Last instance removed. onDeleted already cleared per-id keys; nothing to
    // do here beyond cancelling any timer that outlived them.
    val pendingResult = goAsync()
    PenWidgetRuntime.launchReceiver(pendingResult) {
        PenWidgetScheduler.cancelCommit(context.applicationContext, AppWidgetManager.INVALID_APPWIDGET_ID)
    }
}
```

Do **not** add `onEnabled` unless you have something to do in it. An empty
override is noise.

**3. Size-mapped RemoteViews (API 31+).** In `PenWidgetUpdater.update`, when
`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`, build a map instead of a
single `RemoteViews`:

```kotlin
val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    RemoteViews(
        mapOf(
            SizeF(110f, 110f) to PenWidgetRenderer.buildRemoteViews(appContext, appWidgetId, model, compactSpec),
            SizeF(140f, 160f) to PenWidgetRenderer.buildRemoteViews(appContext, appWidgetId, model, baseSpec),
            SizeF(280f, 320f) to PenWidgetRenderer.buildRemoteViews(appContext, appWidgetId, model, largeSpec),
        ),
    )
} else {
    PenWidgetRenderer.buildRemoteViews(appContext, appWidgetId, model, spec)
}
```

The three specs come from `PenWidgetSizing.resolve` called at those three sizes.
Import `android.util.SizeF` and `android.os.Build`.

Keep `onAppWidgetOptionsChanged` — API 24 through 30 still need it. On API 31+
it becomes redundant but harmless.

#### Traps

- **CI runs instrumentation on API 24 and API 36.** Any unguarded API 31 call
  fails the API 24 emulator job, and `lintDebug` fails first with `NewApi`.
  Guard with `Build.VERSION.SDK_INT`, not with a try/catch.
- **The `RemoteViews(Map<SizeF, RemoteViews>)` constructor requires at least one
  entry and at most 16.** An empty map throws.
- **Remapping must snapshot before removing.** A restore can map id 42 to id 17
  while 17 also maps to something else; removing as you go corrupts the chain.
  That is what the two-pass `moved` list is for.
- `onRestored` runs before `onUpdate` on restore. Remap first, then flush, then
  render — in that order, or the flush operates on keys that are still orphaned.

#### Tests

`PenWidgetRestoreTest.kt` is a pure-JVM test of the id arithmetic only if you
extract it; otherwise put these in `PenWidgetStateRepositoryTest.kt`
(androidTest, `runBlocking`):
- `remapMovesDraftPendingAndConfigToTheNewId()`
- `remapClearsTheOldId()`
- `remapHandlesOverlappingOldAndNewIds()`
- `remapRejectsMismatchedArrayLengths()` — expect `IllegalArgumentException`.
- `remapLeavesUnrelatedWidgetsUntouched()`

#### Subagent orchestration

- **Parallel (2 subagents):** one writes the test methods; one drafts the
  size-mapped `RemoteViews` block in isolation and reports it back for you to
  paste. `PenWidgetUpdater.kt` is on the serial list — the subagent must
  **report code, not write the file**.
- Serial: steps 1 and 2, both in files on the serial list.

#### Delivery

- BRANCH: `agent/widget-restore-and-resize`
- COMMIT SUBJECT: `fix: preserve pen widget state across restore and cut resize reload churn`
- Emulator: run the API 24 emulator too, and say so in the PR body.

---

### PR A7 — Version bump and documentation for v1.4.5

Do this **after A1-A6 are all merged**, as a separate pull request.

#### Steps

**1.** In `app/build.gradle.kts`, lines 38-39, change exactly two values:

```kotlin
versionCode = 41
versionName = "1.4.5"
```

**2.** `docs/PROJECT_STATE.md` — add a section describing the shipped pen widget
improvements, matching the style of the existing
`## Pen widget follow-up implementation (released in v1.2.27)` section.

**3.** `docs/DECISIONS.md` — only if you made a durable decision worth recording.
The next free number is **ADR-028**; check the file before assuming, and note
that PR B7 also wants ADR-028, so whichever merges first takes it.

**4.** `docs/HANDOFF.md` — replace the current-release section. Do not append a
diary. It must record: which PRs merged with their squashed SHAs, the `main` run
id that proved the tagged SHA with all six jobs green, the tag, the published
asset names, the APK SHA-256, and the signing-certificate comparison. You will
only have the last four **after** section 8 — so write this PR's doc changes
now with everything you do know, and amend the provenance in the follow-up docs
PR after publication, exactly as `docs: record the v1.4.3 release handoff` did.

#### Delivery

- BRANCH: `agent/release-v1-4-5`
- COMMIT SUBJECT: `Bump version to 1.4.5 (versionCode 41)`
- Note the different commit style: version bumps do **not** use a conventional
  commit prefix. Match `Bump version to 1.4.4 (versionCode 40)`.
---

## 8. Release runbook

Run this twice: once after A7 merges (v1.4.5), once after B9 merges (v1.5.0).
Substitute `<VERSION>` = `1.4.5` then `1.5.0`.

### The thing that breaks releases

`release-apk.yml` refuses to publish unless the **exact tagged commit SHA** has a
**completed, successful, `push`-event run on `main`** of the workflow
**"Cannsheet PR checks"**, in which all six of these jobs individually succeeded:

```
Classify changes and scan repository
Backend validation
Android static validation
Emulator API 24
Emulator API 36
Cannsheet Android PR validation
```

Two consequences that have already cost a release cycle:

1. **A green pull-request check is not proof.** Pull requests classify as
   `api_levels=[24]`; only pushes to `main` run API 36. The publish workflow
   requires the API 36 job by name, so only the push-to-main run can satisfy it.
2. **Back-to-back merges cancel each other.** Merging a second pull request
   while the first merge's main run is still going cancels the first, and a
   `cancelled` run is not a `success`.

So: land every PR, **then** wait for the final `main` SHA to go fully green,
**then** tag that SHA. Never tag a commit whose main run you have not personally
confirmed green.

### Step 1 — Confirm main

```bash
git switch main && git pull --ff-only && git log --oneline -3
git rev-parse HEAD
```

### Step 2 — Prove the exact SHA is green

```bash
gh run list --branch main --limit 5 --json databaseId,headSha,event,conclusion
```

Find the run whose `headSha` equals the `HEAD` from step 1 **and** whose `event`
is `push`. If it is `cancelled`, `failure`, or absent:

```bash
gh run rerun <run-id>
gh run watch <run-id> --exit-status
```

Then confirm all six jobs individually:

```bash
gh run view <run-id> --json jobs --jq '.jobs[] | "\(.conclusion)\t\(.name)"'
```

Every line must read `success`. Only then continue.

### Step 3 — Ask before publishing

Publication is outward-facing and irreversible: it creates a public GitHub
release that cannot be overwritten. **Ask the repository owner to confirm before
pushing the tag.** They have already asked for these two releases, so a short
"v<VERSION> is ready to tag at `<sha>`, run <run-id> is green on all six jobs —
push the tag?" is enough.

### Step 4 — Tag and publish

```bash
git tag -a v<VERSION> -m "Cannsheet Mobile <VERSION>"
git push origin v<VERSION>
gh run list --workflow=release-apk.yml --limit 1
gh run watch <run-id> --exit-status
```

If it fails **before** the publish job: fix the cause, land the fix, wait for a
new green main run, delete the tag locally and remotely, retag. If it fails
**after** publishing: do not retag — bump to the next version.

### Step 5 — Verify the publication independently

Do not rely on the workflow's own self-check.

```bash
gh release view v<VERSION> --repo noamvb/cannsheet-mobile-releases --json tagName,publishedAt,assets
gh release download v<VERSION> --repo noamvb/cannsheet-mobile-releases --dir /tmp/verify-<VERSION>
cd /tmp/verify-<VERSION> && shasum -a 256 -c Cannsheet-Mobile-<VERSION>.apk.sha256
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging Cannsheet-Mobile-<VERSION>.apk | head -3
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs Cannsheet-Mobile-<VERSION>.apk
```

Expect exactly two assets, package `com.noamv.cannsheet.mobile`, the intended
versionCode and versionName, minSdk 24, targetSdk 36, and APK Signature Scheme
v2. **Compare the certificate digest against the previous release.** A changed
signing certificate makes in-place update impossible and would force an
uninstall, destroying the Room database and every pending offline queue row. If
it differs, stop and tell the owner; do not tell them to install.

### Step 6 — Record provenance

Open one more PR (`docs: record the v<VERSION> release handoff and publication
verification`) updating `docs/HANDOFF.md` with the merged PRs and SHAs, the main
run id, the tag, the asset names, the APK SHA-256, and the certificate
comparison.

### Step 7 — Hand off to the phone

Never install anything on the phone yourself. Tell the owner:

> v<VERSION> is published. Open Obtainium, pull to refresh or tap **Check for
> updates**, and install Cannsheet Mobile <VERSION>.

---

## 9. Release B — pull requests

### PR B1 — Launcher shortcuts

**Goal.** Long-press the app icon for "Log pen", "New purchase", "Insights".

#### Files

New: `app/src/main/res/xml/shortcuts.xml`.
Modified: `AndroidManifest.xml`, `strings.xml`.
Possibly new: three drawables under `app/src/main/res/drawable/`.

#### Steps

**1.** `app/src/main/res/xml/shortcuts.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <shortcut
        android:shortcutId="log_pen"
        android:enabled="true"
        android:icon="@drawable/ic_shortcut_log"
        android:shortcutShortLabel="@string/shortcut_log_short"
        android:shortcutLongLabel="@string/shortcut_log_long">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.noamv.cannsheet.mobile"
            android:targetClass="com.example.MainActivity">
            <extra
                android:name="com.noamv.cannsheet.mobile.widget.START_ROUTE"
                android:value="consumption" />
        </intent>
    </shortcut>
    <!-- purchase -> route "purchase"; insights -> route "insights" -->
</shortcuts>
```

The `<extra>` name must be the **literal value** of `EXTRA_START_ROUTE`
(`com.noamv.cannsheet.mobile.widget.START_ROUTE`). XML cannot reference the
Kotlin constant. Add a comment in `AppEntryPoints.kt` pointing at this file so a
future change keeps them in sync.

`android:targetPackage` is the **applicationId** and `android:targetClass` is the
**namespace-qualified class**. They differ in this project; both are shown above.

**2.** Manifest — inside the `MainActivity` `<activity>` block, which is the
LAUNCHER activity:

```xml
<meta-data
    android:name="android.app.shortcuts"
    android:resource="@xml/shortcuts" />
```

It must be on the activity with `<category android:name="android.intent.category.LAUNCHER" />`
or the shortcuts never appear.

**3.** Strings: `shortcut_log_short` ("Log"), `shortcut_log_long` ("Log pen
consumption"), `shortcut_purchase_short` ("Purchase"), `shortcut_purchase_long`
("Record a purchase"), `shortcut_insights_short` ("Insights"),
`shortcut_insights_long` ("Open insights").

`shortcutShortLabel` is truncated hard by launchers — keep it to one word.

**4.** Icons. Check `app/src/main/res/drawable/` for existing vector assets
first. If none fit, create three simple `<vector>` drawables at 24dp with
`android:tint="?attr/colorPrimary"`. Do not reuse the launcher icon.

#### Traps

- **Static shortcuts require API 25; `minSdk` is 24.** This is safe — on API 24
  the `<meta-data>` is simply ignored and no shortcuts appear. No guard is
  needed and no `tools:targetApi` is required. State this in the PR body so a
  reviewer does not ask.
- Do not add `android:enabled="false"` anywhere; a disabled static shortcut needs
  a `shortcutDisabledMessage` and adds nothing here.

#### Tests

No new automated test. `lintDebug` validates the XML. Manual validation on the
emulator: long-press the icon, confirm three shortcuts, tap each, confirm it
lands on the right tab. Record that in the PR body.

#### Subagent orchestration

Do not use subagents. Three files, ten minutes.

#### Delivery

- BRANCH: `agent/launcher-shortcuts`
- COMMIT SUBJECT: `feat: add launcher shortcuts for log, purchase, and insights`
- Screenshot required: the long-press menu.

---

### PR B2 — Quick Settings tile

**Goal.** Swipe down, one tap, log the default preset for the loaded cart with
the same 5-second undo window. Faster than a widget and works over any app.

Depends on A3 (it reuses `PenWidgetInstanceConfig` semantics for "which cart").

#### Files

New:
- `app/src/main/java/com/example/widget/PenQuickTileService.kt`
- `app/src/main/java/com/example/widget/PenTileState.kt`
- `app/src/test/java/com/example/widget/PenTileStateTest.kt`

Modified: `AndroidManifest.xml`, `strings.xml`,
`PenWidgetStateRepository.kt`, `PenWidgetCommitCoordinator.kt`.

#### The core design problem, and its answer

Every existing commit path is keyed by `appWidgetId`. The tile has no widget id.
**Do not invent a fake id and do not make `appWidgetId` nullable** — both break
`requireValidWidgetId` and the `pendingCommits()` key scan, which parses the id
out of the key name with `removePrefix(PENDING_PREFIX).toIntOrNull()`.

Instead, reserve a dedicated non-negative id constant that the AppWidgetManager
will never issue, and use the existing machinery unchanged:

```kotlin
// PenTileState.kt
/**
 * Reserved pseudo widget id for the Quick Settings tile. AppWidgetManager
 * allocates ids from 1 upward and never issues Int.MAX_VALUE, so the tile can
 * reuse the whole per-widget commit, claim, and undo machinery without a
 * parallel implementation.
 */
const val PEN_TILE_WIDGET_ID: Int = Int.MAX_VALUE
```

`requireValidWidgetId` only demands `>= 0`, so this passes. `pendingCommits()`
will surface it and `flushOverdue` will commit it — which is exactly right; a
tile submission must survive process death like any other.

**One consequence you must handle:** `PenWidgetUpdater.update` is called for
every pending id during `flushOverdue`. Add an early return at the top:

```kotlin
suspend fun update(context: Context, appWidgetId: Int) {
    if (appWidgetId < 0 || appWidgetId == PEN_TILE_WIDGET_ID) return
    …
}
```

The existing `if (appWidgetId < 0) return` is already there — extend it.
Without this, `AppWidgetManager.updateAppWidget(Int.MAX_VALUE, …)` throws.

#### Steps

**1. The service.**

```kotlin
package com.example.widget

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class PenQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        PenWidgetRuntime.launchSerialized {
            submitDefaultPreset(applicationContext)
            refreshTileFromState()
        }
    }
}
```

`onClick` must not block. `PenWidgetRuntime.launchSerialized` already provides a
scope with `Dispatchers.Default.limitedParallelism(1)` and an exception handler —
use it rather than creating a new scope.

**2. Submitting.** Reuse `PenWidgetActionRouter`'s submit logic. Factor its
private `submit` into an internal function both callers use:

```kotlin
internal suspend fun submitPenLog(
    context: Context,
    appWidgetId: Int,
    seconds: Int,
    penState: PenQuickLogState.Loaded,
    now: () -> Long = System::currentTimeMillis,
    newId: () -> String = { UUID.randomUUID().toString() },
): PenWidgetCommitPayload?
```

For the tile, `seconds` is the **first** preset from
`penWidgetPresetSeconds(penState)` (the helper added in A1), or `STEP_SECONDS`
if the list is empty.

Because `submitCommit` reads the draft from DataStore and refuses when it is
zero, the tile must set the draft first:

```kotlin
state.setDraftSeconds(PEN_TILE_WIDGET_ID, seconds)   // added in A1
```

then call the shared submit. Do not add a "submit without a draft" path; the
atomicity guarantee in `submitCommit`'s doc comment depends on the draft being
the single source of the captured value.

**3. Tile label and state.** `Tile.STATE_ACTIVE` during the undo window,
`Tile.STATE_INACTIVE` otherwise, `Tile.STATE_UNAVAILABLE` when there is no
loaded cart or `secondsPerUse` is null.

```kotlin
private fun applyTile(label: String, state: Int) {
    qsTile?.apply {
        this.label = label
        this.state = state
        updateTile()
    }
}
```

Labels: cart name plus the preset (`"Daytona Peach · 30s"`) when idle; during
the undo window, `"Undo"` — and a second tap within the window calls
`stateRepository.undo(PEN_TILE_WIDGET_ID, commitId, now)`.

The tile only redraws while the shade is open (`onStartListening` /
`onStopListening`). Do not schedule a repeating refresh; when the shade is
closed nobody is looking, and the commit timer in `PenWidgetRuntime` completes
the write regardless.

**4. Manifest.**

```xml
<service
    android:name=".widget.PenQuickTileService"
    android:exported="true"
    android:icon="@drawable/ic_tile_pen"
    android:label="@string/pen_tile_label"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

`android:exported="true"` is **required** — the system binds it. The
`BIND_QUICK_SETTINGS_TILE` permission is what makes that safe; only the system
holds it.

**5. Strings and icon.** `pen_tile_label` ("Log pen"), `pen_tile_no_cart` ("No
cart loaded"), `pen_tile_undo` ("Undo"). Create `ic_tile_pen.xml` as a 24dp
single-colour vector — QS tile icons must be monochrome or they render as a
grey blob.

#### Traps

- **Do not call `startActivityAndCollapse(Intent)`.** It throws
  `UnsupportedOperationException` on API 34+. If you ever open the app from the
  tile, use the `PendingIntent` overload guarded by
  `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`. The design
  above never opens the app, so the simplest correct answer is: don't.
- **`TileService` is API 24+** — fine at `minSdk 24`. `Tile.STATE_*` constants
  are also 24+. No guard needed.
- **`qsTile` is null when not listening.** Every write goes through `qsTile?.`
  and `updateTile()`. Calling `updateTile()` outside a listening window silently
  does nothing; that is fine.
- **The tile shares one DataStore key namespace with the widgets.** Because
  `PEN_TILE_WIDGET_ID` is a real key suffix, `clear()` and `remapWidgetIds` must
  never be called with it from provider code — they never are, because providers
  only ever pass ids the launcher gave them.

#### Tests

`PenTileStateTest.kt` (JVM, JUnit 4) over a pure function you extract for the
label/state decision:

```kotlin
internal fun penTileState(penState: PenQuickLogState, pending: PenWidgetCommitPayload?): PenTileModel
```

- `noLoadedCartIsUnavailable()`
- `missingRateIsUnavailable()`
- `loadedCartShowsNameAndFirstPreset()`
- `pendingCommitShowsUndoAndActiveState()`
- `emptyPresetsFallsBackToTheDefaultStep()`

Instrumented: extend `PenWidgetStateRepositoryTest.kt` with
`tileIdRoundTripsThroughTheSameStateMachinery()`.

#### Subagent orchestration

- **Parallel (2 subagents):** one writes `PenTileState.kt` + `PenTileStateTest.kt`
  (new files, disjoint); one drafts the vector drawable `ic_tile_pen.xml`.
- **Serial, you:** the manifest, `strings.xml`, the `PenWidgetUpdater` early
  return, and the `submitPenLog` extraction — that last one edits
  `PenWidgetActionRouter.kt`, which A1 also touched, so re-read it first.
- Do the `submitPenLog` extraction **before** spawning anyone, so the subagents
  code against its final signature.

#### Delivery

- BRANCH: `agent/quick-settings-tile`
- COMMIT SUBJECT: `feat: add a quick settings tile for one-tap pen logging`
- Emulator: add the tile via the shade's edit mode, tap it, confirm the undo
  window, confirm the row reaches Room. Screenshot the tile in both states.

---

### PR B3 — Sync status widget

**Goal.** A small widget showing pending count and last sync time, with a tap to
sync now. The one thing the app knows that you currently must open it to learn.

#### Files

New:
- `app/src/main/java/com/example/widget/sync/SyncStatusWidgetProvider.kt`
- `app/src/main/java/com/example/widget/sync/SyncStatusUiModel.kt`
- `app/src/main/java/com/example/widget/sync/SyncStatusRenderer.kt`
- `app/src/main/java/com/example/widget/sync/SyncStatusUpdater.kt`
- `app/src/main/res/layout/widget_sync_status.xml`
- `app/src/main/res/xml/sync_status_widget_info.xml`
- `app/src/main/res/xml-v31/sync_status_widget_info.xml`
- `app/src/test/java/com/example/widget/sync/SyncStatusUiModelTest.kt`

Modified: `AndroidManifest.xml`, `strings.xml`,
`app/src/main/java/com/example/widget/PenWidgetRefresher.kt`.

Copy the structure of the pen widget's equivalents. This is a new package under
`com.example.widget.sync` so the two widgets do not collide in one flat package.

#### Steps

**1. The model.** Pure function, no Android types, so it is unit-testable:

```kotlin
data class SyncStatusUiModel(
    val pendingCount: Int,
    val lastSyncLabel: PenWidgetText,
    val stuck: Boolean,
)

fun buildSyncStatusUiModel(
    pendingCount: Int,
    lastMeaningfulSyncAtEpochMillis: Long?,
    queueNonEmptySinceEpochMillis: Long?,
    nowMillis: Long,
): SyncStatusUiModel
```

Reuse `PenWidgetText` from the pen widget rather than defining a second text
abstraction.

`stuck` is `queueNonEmptySinceEpochMillis != null && nowMillis - it >= QUEUE_STUCK_THRESHOLD_MILLIS`,
using the existing constant in `data/sync/QueueHealth.kt`.

**2. Data.** Read `graph.repository.pendingActionCount.first()` and the sync
timestamps from `graph.syncPreferences`. Open `SyncPreferencesRepository.kt` and
use the accessors that already exist — do not add new ones unless nothing fits,
and if you must, add a read-only `Flow`, never a mutator.

**3. Tap to sync.** One action:

```kotlin
const val ACTION_SYNC_NOW = "com.noamv.cannsheet.mobile.widget.SYNC_NOW"
```

Handle it in `SyncStatusWidgetProvider.onReceive` by calling
`SyncScheduler.enqueueImmediate(appContext)` and then re-rendering. **Do not
touch `SyncEngine` directly.** `AGENTS.md`:

> All queue synchronization must go through `SyncEngine` under
> `CannsheetGraph.syncMutex`.

`SyncScheduler.enqueueImmediate` enqueues `SyncWorker`, which is the sanctioned
path and already holds the mutex. Going around it risks concurrent syncs and
duplicate spreadsheet rows.

**4. Refresh.** `SyncWorker` already calls `graph.widgetRefresher.refreshAll()`.
Make that refresh both widgets. Change `PenWidgetRefresher.refreshAll` to:

```kotlin
override fun refreshAll() {
    runCatching { PenWidgetUpdater.updateAll(appContext) }
    runCatching { SyncStatusUpdater.updateAll(appContext) }
}
```

Rename the class to `CannsheetWidgetRefresher` in the same PR, updating its one
call site in `CannsheetApplication.kt:28`. Leaving it named `PenWidgetRefresher`
while it refreshes two widgets is the kind of drift that misleads the next
reader.

**5. Provider info.** `minWidth="110dp"`, `minHeight="40dp"`,
`targetCellWidth="2"`, `targetCellHeight="1"` (v31 only),
`updatePeriodMillis="0"`, `resizeMode="horizontal"`. Same
`previewLayout` pattern as the pen widget on v31.

#### Traps

- **`AGENTS.md`, quoted, applies to this surface:**

  > Queue-integrity alerts are advisory only. Evaluation may read the existing
  > aggregate pending-action count, but no notification or presenter may receive
  > queue rows or entry details […] Notification content must never include
  > product names, quantities, or dates.

  This widget may show **the aggregate count and a relative time** ("3 pending ·
  synced 14m ago"). It must **never** show product names, per-product
  quantities, or absolute dates. Use a relative label ("14m ago", "yesterday"),
  not a timestamp. Quote this rule in the PR description.
- **Do not add a second `WidgetRefresher` to the graph.** There is one slot
  (`installedWidgetRefresher`); fan out inside the single implementation.
- Each `runCatching` in step 4 must be separate, so a failure in one widget does
  not stop the other from refreshing.

#### Tests

`SyncStatusUiModelTest.kt`:
- `emptyQueueShowsSyncedState()`
- `pendingCountIsReported()`
- `queueOlderThanTheStuckThresholdIsStuck()`
- `queueYoungerThanTheThresholdIsNotStuck()`
- `neverSyncedShowsTheNeverLabel()`
- `clockRollbackDoesNotReportNegativeAge()`

#### Subagent orchestration

The best parallel opportunity in the plan — eight new files in a new package.

- **Parallel (3 subagents):** (a) `SyncStatusUiModel.kt` + its test;
  (b) `widget_sync_status.xml` + the two provider-info XMLs; (c)
  `SyncStatusRenderer.kt` + `SyncStatusUpdater.kt`. All disjoint new paths.
  Give each subagent the model signature from step 1 verbatim, so (b) and (c)
  code against the same field names.
- **Serial, you:** `SyncStatusWidgetProvider.kt` (it depends on all three),
  `AndroidManifest.xml`, `strings.xml`, and the `PenWidgetRefresher` rename.
- Do the rename **last**, after everything compiles, so a broken intermediate
  state does not confuse you about which failure is which.

#### Delivery

- BRANCH: `agent/sync-status-widget`
- COMMIT SUBJECT: `feat: add a sync status home-screen widget`
- Screenshots: empty queue, pending queue, stuck queue.
---

### PR B4 — Multi-cart quick log widget

**Goal.** One wide widget with a button per active cart, each logging that
cart's default preset with the full undo window.

Depends on A1 (`penWidgetPresetSeconds`, `setDraftSeconds`) and A3
(`loadPenState(context, pinnedProductId)`).

#### The two design decisions, made

**Fixed buttons, not a collection widget.** A `RemoteViewsService` /
`RemoteViewsFactory` collection would scale to any number of carts, but it needs
a bound service, `setPendingIntentTemplate` + `setOnClickFillInIntent` for
per-item taps, and it re-fetches on every `notifyAppWidgetViewDataChanged`. Four
fixed buttons plus an overflow row cover the real case — a person has a handful
of open carts — with none of that machinery, and reuse the renderer pattern the
repo already has. If the user ever needs more, revisit; do not build it now.

**Submit directly at the default preset; no per-cart draft.** The widget writes
the chosen cart's first preset into the existing draft slot for its own
`appWidgetId`, then calls the same submit path. One pending commit per widget at
a time, one undo window, no new state shape. **Do not** invent a
per-`(widgetId, productId)` draft key — it multiplies the DataStore key space
and the `pendingCommits()` scan cannot parse a compound suffix.

#### Files

New:
- `app/src/main/java/com/example/widget/multi/MultiCartWidgetProvider.kt`
- `app/src/main/java/com/example/widget/multi/MultiCartUiModel.kt`
- `app/src/main/java/com/example/widget/multi/MultiCartRenderer.kt`
- `app/src/main/java/com/example/widget/multi/MultiCartUpdater.kt`
- `app/src/main/res/layout/widget_multi_cart.xml`
- `app/src/main/res/xml/multi_cart_widget_info.xml`
- `app/src/main/res/xml-v31/multi_cart_widget_info.xml`
- `app/src/test/java/com/example/widget/multi/MultiCartUiModelTest.kt`

Modified: `AndroidManifest.xml`, `strings.xml`, `PenWidgetRefresher.kt`
(renamed to `CannsheetWidgetRefresher` in B3 — add a third `runCatching`).

#### Steps

**1. The model.**

```kotlin
data class MultiCartEntry(
    val productId: String,
    val productUuid: String?,
    val name: String,
    val seconds: Int,
    val secondsPerUse: Double,
)

data class MultiCartUiModel(
    val entries: List<MultiCartEntry>,
    val overflowCount: Int,
    val pending: PenWidgetCommitPayload?,
)

fun buildMultiCartUiModel(
    products: List<Product>,
    interactions: List<ProductInteraction>,
    globalPresets: List<Double>,
    presetOverrides: Map<ProductTypeKey, List<Double>>,
    secondsPerUseOverrides: Map<ProductTypeKey, Double>,
    pending: PenWidgetCommitPayload?,
    maxEntries: Int = 4,
): MultiCartUiModel
```

Select products with the same filter `resolveLoadedPenProduct` uses
(`product.productStatus.isSelectable && ProductTypeCodes.normalize(product.type) == ProductTypeCodes.PEN`),
order by `interactions` `lastLoggedAtEpochMillis` descending so the carts you
actually use float to the top, take `maxEntries`, and put the remainder in
`overflowCount`.

**2. Actions.** Four fixed slots, exactly as A1 did for presets, for exactly the
same requestCode-collision reason:

```kotlin
const val ACTION_CART_1 = "com.noamv.cannsheet.mobile.widget.CART_1"
// … CART_2, CART_3, CART_4
val CART_ACTIONS: List<String> = listOf(ACTION_CART_1, ACTION_CART_2, ACTION_CART_3, ACTION_CART_4)
```

**3. Submit.** In the provider's receive path, for slot index `i`:

```kotlin
val model = buildMultiCartUiModel(...)
val entry = model.entries.getOrNull(index) ?: return
val state = PenWidgetStateRepository(appContext)
state.setDraftSeconds(appWidgetId, entry.seconds)
val payload = state.submitCommit(appWidgetId) { seconds ->
    PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = commitId,
        eventId = eventId,
        submittedAtEpochMillis = submittedAt,
        commitAtEpochMillis = submittedAt + COMMIT_DELAY_MILLIS,
        productId = entry.productId,
        productUuid = entry.productUuid,
        seconds = seconds,
        secondsPerUse = entry.secondsPerUse,
        uses = secondsToUses(seconds.toDouble(), entry.secondsPerUse),
        date = at.date,
        time = at.time,
    )
}
if (payload != null) {
    PenWidgetRuntime.scheduleCommitTimer(appContext, appWidgetId, payload.commitId)
    runCatching { PenWidgetScheduler.scheduleCommit(appContext, appWidgetId, payload.commitId) }
}
```

This mirrors `PenWidgetActionRouter.submit` exactly — read that function and
keep the structure identical, including the `runCatching` around the WorkManager
backstop and the comment explaining why its failure must not suppress the timer.

**4. Undo.** While `pending != null`, replace the whole button grid with a
single full-width UNDO button wired to `ACTION_UNDO` with the payload's
`commitId`, plus the `Chronometer` countdown. Reuse
`R.string.pen_widget_undo_symbol` and the existing countdown mechanics from
`PenWidgetRenderer.buildInteractiveViews`.

**5. Overflow.** When `overflowCount > 0`, the last row shows
`R.string.multi_cart_more` (`"+%1$d more · open app"`) wired to
`ACTION_OPEN_LOG`.

**6. Provider info.** `minWidth="250dp"`, `minHeight="110dp"`,
`targetCellWidth="4"`, `targetCellHeight="1"` (v31 only),
`resizeMode="horizontal|vertical"`, `updatePeriodMillis="0"`.

#### Traps

- **`secondsToUses` before Room, always.** `AGENTS.md`:

  > quantities are displayed in seconds but must be converted with
  > `secondsToUses` before reaching Room, the offline queue, or the wire

  The payload's `uses` field is what `PenWidgetCommitCoordinator.logPayload`
  passes to `ConsumptionLogger`. Put a raw seconds value there and you log 30
  uses instead of 3.
- **`submitCommit` requires `payload.seconds == seconds`** — it `require`s it and
  throws otherwise. Build the payload from the `seconds` the lambda receives,
  never from `entry.seconds` captured outside.
- **`updateLoadedCart = false`.** `PenWidgetCommitCoordinator.logPayload` already
  passes this. Do not "fix" it to `true` — logging from a multi-cart widget must
  not silently change which cart the pen widget and the Log screen consider
  loaded.
- **One pending commit per widget instance.** While a commit is pending,
  `setDraftSeconds` and `submitCommit` both no-op by design. The renderer must
  therefore show the undo state rather than a live grid, or taps appear dead.

#### Tests

`MultiCartUiModelTest.kt`:
- `onlySelectablePensAreOffered()`
- `finishedCartsAreExcluded()`
- `mostRecentlyLoggedCartsComeFirst()`
- `atMostFourCartsAreShown()`
- `remainingCartsBecomeTheOverflowCount()`
- `eachEntryUsesItsOwnTypeRateAndFirstPreset()`
- `pendingCommitSuppressesTheGrid()`

#### Subagent orchestration

- **Parallel (3 subagents):** (a) `MultiCartUiModel.kt` + test;
  (b) `widget_multi_cart.xml` + both provider-info XMLs; (c)
  `MultiCartRenderer.kt` + `MultiCartUpdater.kt`.
- **Serial, you:** `MultiCartWidgetProvider.kt` (the submit path — this is the
  data-safety-critical code and you should write it yourself against the real
  `PenWidgetActionRouter.submit`), the manifest, `strings.xml`, and the
  refresher fan-out.
- **Do not delegate the submit path.** It is the one place in this PR where a
  plausible-looking mistake writes wrong data to the user's spreadsheet.

#### Delivery

- BRANCH: `agent/multi-cart-widget`
- COMMIT SUBJECT: `feat: add a multi-cart quick log widget`
- Emulator: log from two different carts, confirm two distinct rows with correct
  `uses`, and confirm the loaded cart did not change.

---

### PR B5 — Record consumption history locally

**Goal.** `ConsumptionAction` rows are deleted once the server acknowledges
them, so the app has no local record of what was logged. Add an append-only
local history table. This is the foundation for B6.

**This is the highest data-risk PR in the plan.** Read the Room and data-safety
rules in `AGENTS.md` before starting.

#### Files

Modified:
- `app/src/main/java/com/example/data/Database.kt`
- `app/src/main/java/com/example/data/CannsheetGraph.kt`
- `app/src/main/java/com/example/data/ConsumptionLogger.kt`
- `app/src/androidTest/java/com/example/data/DatabaseMigrationTest.kt`

New:
- `app/src/androidTest/java/com/example/data/ConsumptionHistoryDaoTest.kt`

#### Steps

**1. The entity.** In `Database.kt`, next to the other `@Entity` classes:

```kotlin
@Entity(
    tableName = "consumption_history",
    indices = [Index(value = ["eventId"], unique = true), Index(value = ["loggedAtEpochMillis"])],
)
data class ConsumptionHistoryEntry(
    @PrimaryKey val eventId: String,
    val date: String,
    val time: String,
    val productId: String,
    val productUuid: String?,
    val uses: Double,
    val isFinished: Boolean,
    val loggedAtEpochMillis: Long,
)
```

`eventId` as the primary key makes the append idempotent: the same logical event
can never produce two history rows, which matters because
`PenWidgetCommitCoordinator` can retry a claim after process death.

**2. Bump the database.** Add `ConsumptionHistoryEntry::class` to the `entities`
list and change `version = 10` to `version = 11`.

**3. The migration.** Add to the `companion object`, after `MIGRATION_9_10`:

```kotlin
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `consumption_history` (
                `eventId` TEXT NOT NULL,
                `date` TEXT NOT NULL,
                `time` TEXT NOT NULL,
                `productId` TEXT NOT NULL,
                `productUuid` TEXT,
                `uses` REAL NOT NULL,
                `isFinished` INTEGER NOT NULL,
                `loggedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`eventId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_consumption_history_eventId` " +
                "ON `consumption_history` (`eventId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_consumption_history_loggedAtEpochMillis` " +
                "ON `consumption_history` (`loggedAtEpochMillis`)",
        )
    }
}
```

**4. Register it.** `CannsheetGraph.kt` lists every migration in
`.addMigrations(...)`. Add `AppDatabase.MIGRATION_10_11` to the end. **Miss this
and the app wipes or crashes on upgrade** — the list is the only thing standing
between an existing install and a failed open.

**5. DAO.** Add to `CannsheetDao`:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertConsumptionHistory(entry: ConsumptionHistoryEntry)

@Query("SELECT * FROM consumption_history WHERE loggedAtEpochMillis >= :fromEpochMillis ORDER BY loggedAtEpochMillis DESC")
fun consumptionHistorySince(fromEpochMillis: Long): Flow<List<ConsumptionHistoryEntry>>

@Query("DELETE FROM consumption_history WHERE loggedAtEpochMillis < :beforeEpochMillis")
suspend fun pruneConsumptionHistoryBefore(beforeEpochMillis: Long): Int
```

`OnConflictStrategy.IGNORE` is what makes the append idempotent. Do not use
`REPLACE`; a replay must not overwrite the original timestamp.

**6. Append on log.** `ConsumptionLogger.log` is the one place every entry point
funnels through — the widget, the tile, the multi-cart widget, and the Log
screen all reach Room here. Add the history write immediately after the queue
write, inside the same function:

```kotlin
repository.addConsumption(action, loggedAtEpochMillis)
historyRecorder.record(
    ConsumptionHistoryEntry(
        eventId = eventId,
        date = date,
        time = time,
        productId = productId,
        productUuid = productUuid,
        uses = uses,
        isFinished = isFinished,
        loggedAtEpochMillis = loggedAtEpochMillis,
    ),
)
```

Add a third constructor parameter with a narrow interface, matching the file's
existing style (`ConsumptionLogRepository`, `LoadedPenProductStore`):

```kotlin
/** Narrow local-history boundary used by the shared consumption logger. */
interface ConsumptionHistoryRecorder {
    suspend fun record(entry: ConsumptionHistoryEntry)
}
```

`CannsheetRepository` implements it; wire it in `CannsheetGraph`'s
`consumptionLogger = ConsumptionLogger(repository, consumptionPreferences, repository)`.

**Do not put the two writes in one transaction.** The queue write is the one
that must succeed; history is derived convenience data. If you wrap them
together, a history failure would roll back a queued consumption the user
believes was recorded — strictly worse. Wrap the history call in
`runCatching { }` and let it fail silently, with a comment saying exactly this.

**7. Pruning.** Do not add automatic pruning in this PR. The table grows by one
small row per log; at a few logs a day that is negligible for years, and an
automatic delete is a destructive path that would need its own rollback story.
The `pruneConsumptionHistoryBefore` DAO method exists for a future explicit
action; leave it uncalled and say so in the PR description.

#### Traps

- **`exportSchema = false`.** There are no exported schema JSON files, so you
  cannot diff your `CREATE TABLE` against Room's expectation, and the existing
  migration tests use a raw `SupportSQLiteOpenHelper` that never invokes Room's
  validator. A column-type or index mismatch will therefore pass every existing
  test and then throw `IllegalStateException: Migration didn't properly handle`
  on a real device. **You must add the validation test in step 8.**
- `Boolean` maps to `INTEGER NOT NULL`, `Double` to `REAL NOT NULL`, `String?` to
  `TEXT` (nullable, no `NOT NULL`), `String` to `TEXT NOT NULL`. Room's
  generated index name format is `index_<table>_<column>` exactly.
- **Never use `fallbackToDestructiveMigration`.** `AGENTS.md` forbids it and it
  would delete the user's pending offline queue.
- `Index` and `PrimaryKey` need imports: `androidx.room.Index`,
  `androidx.room.PrimaryKey`, `androidx.room.Insert`,
  `androidx.room.OnConflictStrategy`.

#### Tests

**8. The validation test — the important one.** In `DatabaseMigrationTest.kt`,
add a test that migrates with the raw helper and then **opens the real Room
database**, which forces Room's schema validation to run:

```kotlin
@Test
fun migrationFrom10To11ProducesASchemaRoomAccepts() {
    // build a version-10 database with the raw helper, as the existing tests do
    // …
    val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(AppDatabase.MIGRATION_10_11)
        .build()
    // Any query forces the open and therefore the validation.
    runBlocking { database.cannsheetDao().insertConsumptionHistory(sampleEntry) }
    database.close()
}
```

This is the only thing that catches a `CREATE TABLE` that disagrees with the
entity. Do not skip it.

Also add, following the existing per-migration pattern:
- `migrationFrom10To11PreservesProductsAndQueuedActions()` — copy
  `migrationFrom9To10PreservesProductsAndQueuedConsumptions` and extend it.

`ConsumptionHistoryDaoTest.kt` (androidTest, in-memory Room):
- `insertingTheSameEventIdTwiceKeepsOneRow()`
- `historySinceReturnsOnlyEntriesAtOrAfterTheCutoff()`
- `historyIsOrderedNewestFirst()`
- `pruneRemovesOnlyOlderEntries()`

#### Subagent orchestration

- **Parallel (2 subagents):** one writes `ConsumptionHistoryDaoTest.kt`; one
  writes the two new methods for `DatabaseMigrationTest.kt` **and reports them
  back as text** — that file is shared and you paste them in yourself.
- **Serial, you:** every edit to `Database.kt`, `CannsheetGraph.kt`, and
  `ConsumptionLogger.kt`. These are the data-safety-critical files. Write them
  yourself and read the whole diff twice.

#### Delivery

- BRANCH: `agent/local-consumption-history`
- COMMIT SUBJECT: `feat: record consumption history locally`
- The PR description's "Risks and data safety" section must cover: the schema
  bump, why history failure cannot roll back a queue write, the idempotency
  guarantee, and the absence of pruning.
- Emulator: **install the previous release build first, log a few entries, then
  install this build over it** and confirm the upgrade opens cleanly and the old
  rows survive. State the result. This is the check that matters most.

---

### PR B6 — Today widget

**Goal.** Today's total, a comparison against a recent average, and a streak.

Depends on B5.

#### Files

New: `app/src/main/java/com/example/widget/today/` (provider, model, renderer,
updater), `widget_today.xml`, both provider-info XMLs, and
`app/src/test/java/com/example/widget/today/TodayUiModelTest.kt`.
Modified: `AndroidManifest.xml`, `strings.xml`, the widget refresher.

#### Steps

**1. Definitions, fixed.** Do not improvise these:

- **Today** = entries whose `date` field equals today's local `yyyy-MM-dd`.
- **Today's total** = sum of `uses` over those entries.
- **Baseline** = mean daily total over the previous 7 complete days that have at
  least one entry; null when fewer than 3 such days exist.
- **Streak** = consecutive days ending yesterday with at least one entry, plus
  today if today has one.

**2. The timezone question, resolved.** `AGENTS.md` says:

> Month and day arithmetic on analytics data uses the response's own `timeZone`
> and `range` fields, never a device-local `Calendar` or `LocalDate.now()`.

That rule governs **analytics data**, which arrives with its own timezone. This
widget reads the **local** `consumption_history` table, whose `date` column was
written by `currentSubmissionDateTime` from the device clock at log time.
Comparing a locally-written `date` string against a locally-derived today is
consistent, and reaching for an analytics timezone here would introduce a
mismatch, not remove one.

So: use the device's local date, and **write that reasoning into the PR
description and as a comment above the query**. Do not silently pick one.

**3. Model.** Pure function over a list of entries and a "today" string:

```kotlin
fun buildTodayUiModel(
    entries: List<ConsumptionHistoryEntry>,
    todayDate: String,
    secondsPerUse: Double?,
): TodayUiModel
```

Pass `todayDate` in rather than calling `LocalDate.now()` inside, so the tests
are deterministic.

**4. Display.** Show uses converted to the input unit with the existing
`formatQuantityInInputUnit(uses, secondsPerUse)` — it already returns `"45s"` or
`"1.5"`. Do not write a second formatter.

**5. Pending entries count.** They are in the history table the moment they are
logged, before sync. That is correct: the widget answers "what did I do today",
not "what has the server confirmed". Say so in the PR body.

#### Traps

- The history table only contains entries logged **after B5 shipped**. On first
  install the widget shows a legitimately empty today and no streak. Make the
  empty state read as "no logs yet today", not as an error, and do not
  backfill from analytics — that would mix two sources with different
  timezone semantics.
- `entries` must be read with the `consumptionHistorySince` Flow bounded to
  roughly 10 days, not the whole table.

#### Tests

`TodayUiModelTest.kt`:
- `todayTotalSumsOnlyTodaysEntries()`
- `baselineIgnoresDaysWithNoEntries()`
- `baselineIsNullBelowThreeObservedDays()`
- `streakCountsConsecutiveDaysEndingToday()`
- `streakSurvivesADayWithMultipleEntries()`
- `streakBreaksOnAMissingDay()`
- `emptyHistoryProducesTheEmptyState()`

#### Subagent orchestration

Same shape as B3: parallel on (model + test), (layouts), (renderer + updater);
serial on provider, manifest, strings, refresher.

#### Delivery

- BRANCH: `agent/today-widget`
- COMMIT SUBJECT: `feat: add a today home-screen widget`

---

### PR B7 — Allow labelled cached projections on widget surfaces

**Documentation only. Must merge before B8.**

#### The problem

`AGENTS.md` currently says:

> Runway and spend projections are presentation-only estimates derived from
> `InsightsResponseDto`. They must not be persisted, transmitted, or treated as
> confirmed values, and must degrade to showing nothing when the Insights
> snapshot is cached, stale, changing range, or incomplete because a local
> action is pending.

A widget's only data source **is** the cached snapshot
(`AnalyticsRepository.readCachedInsights()`), so under this rule a projection
widget would render blank essentially always. The owner has approved amending
the rule for widget surfaces, on the condition that a cached projection always
carries an explicit as-of date.

#### Steps

**1.** In `AGENTS.md`, replace that bullet with:

> Runway and spend projections are presentation-only estimates derived from
> `InsightsResponseDto`. They must not be persisted, transmitted, or treated as
> confirmed values. In-app surfaces must degrade to showing nothing when the
> Insights snapshot is cached, stale, changing range, or incomplete because a
> local action is pending. A home-screen widget may instead render a cached
> projection, but only when it displays the snapshot's own as-of date alongside
> the figure, and it must still show nothing when no snapshot has ever been
> cached.

**2.** Add ADR-028 to `docs/DECISIONS.md`, matching the existing format exactly
(`## ADR-0NN: <title>` with `### Context`, `### Decision`, `### Consequences`,
and `### Not verified` where applicable — read ADR-027 first and copy its shape).
Confirm 028 is still free; PR A7 may have taken it.

The ADR must record: why the in-app rule exists (a projection presented as
current, next to a queue the user just added to, misleads), why a widget is
different (it is glanceable, never authoritative, and cannot refresh on its
own), and the exact condition (an as-of date is mandatory, absence of any
snapshot still renders nothing).

**3.** Update `docs/PROJECT_STATE.md` if it restates the old rule.

#### Traps

- **Do not change any code in this PR.** A docs-only PR that also touches Kotlin
  is not one coherent change, and the classify job's behaviour differs.
- Do not weaken the "must not be persisted or transmitted" half. The widget
  reads; it never writes a projection anywhere.

#### Delivery

- BRANCH: `agent/adr-widget-projections`
- COMMIT SUBJECT: `docs: allow labelled cached projections on widget surfaces`
- No tests to run beyond the standard gate. Say in the PR body that the change
  is documentation only.

---

### PR B8 — Runway and spend projection widgets

**Goal.** A glanceable "~4 days left on this cart" and "month-to-date spend,
projected month end". **B7 must be merged first.**

#### The design decision, made

**One provider with two modes, chosen in the configuration activity** — not two
providers. The two widgets share their entire data path (read the cached
snapshot, derive an as-of date, format), differ only in which figure they
render, and a user is unlikely to want both. One provider, one config screen,
a `mode` key in its per-instance state.

#### Files

New: `app/src/main/java/com/example/widget/projection/` (provider, model,
renderer, updater, config activity), `widget_projection.xml`, both provider-info
XMLs, and `app/src/test/java/com/example/widget/projection/ProjectionUiModelTest.kt`.
Modified: `AndroidManifest.xml`, `strings.xml`, the widget refresher.

#### Steps

**1. Read the snapshot** without the ViewModel:

```kotlin
val snapshot = CannsheetGraph.get(appContext).analyticsRepository.readCachedInsights()
```

Returns `InsightsResponseDto?`. `null` means nothing has ever been cached —
render the empty state and stop.

**2. Derive the as-of date from the snapshot's own fields**, never from the
device clock. `InsightsResponseDto` carries `range: AnalyticsRangeDto` (with
`from`, `to`, `dayCount`) and a `timeZone`. Use `range.to` as the as-of date.
This is the `AGENTS.md` rule about analytics arithmetic, and here it applies
directly — unlike in B6.

**3. Compute** with the existing builders, unchanged:

```kotlin
// app/src/main/java/com/example/domain/InventoryRunway.kt
buildTypeCapacityModels(products: List<AnalyticsProductDto>): Map<String, TypeCapacityModel>
buildProductRunway(...)   // read the file for the exact parameter list
```

For spend, use the existing `SpendRunRate` builder in the same file over
`MonthlySpendDto`. Open `InventoryRunway.kt` and copy the call shape from
`deriveRunwayPresentationState` in
`app/src/main/java/com/example/ui/RunwayPresentation.kt`, which already does
exactly this assembly.

**4. Reuse the formatters.** `app/src/main/java/com/example/ui/RunwayFormatting.kt`
and `RunwayPresentation.kt` already turn a `ProductRunway` into display text
(`runwaySummaryText`). Call them. If they are `internal` to `com.example.ui`
they are still reachable from `com.example.widget.projection` — same module. **Do
not reimplement the formatting**; two formatters will drift and the widget will
contradict the app.

**5. The mandatory label.** Every rendered figure is accompanied by
`R.string.projection_as_of` = `"as of %1$s"`, argument `range.to`. This is the
condition B7's ADR sets. A render path that can produce a figure without the
label is a bug, so build them into the same `PenWidgetText` value rather than
two independent view slots.

**6. Suppression that still applies.** `deriveRunwayPresentationState` suppresses
on `RunwaySuppressionReason.PENDING_ACTIONS`, `QUEUE_COUNT_UNKNOWN`, and
`RANGE_CHANGING`. For the widget, B7 lifts the *staleness* suppression only.
Keep suppressing when the snapshot is structurally unusable — no products, or
`dataQuality.complete == false`. Read `RunwayPresentation.kt` and mirror its
checks other than freshness.

#### Traps

- **The widget only reads.** Never call `fetchInsights`, never write the cache,
  never trigger an analytics refresh from a widget render. `AGENTS.md` floors
  runway-only refreshes for exactly this reason, and a widget rendering on every
  resize would hammer it.
- **No figure without an as-of date.** See step 5.
- `readCachedInsights()` is `suspend` and does IO. Call it inside the existing
  serialized widget scope, not on a broadcast receiver's main thread.

#### Tests

`ProjectionUiModelTest.kt`, pure JVM over a hand-built `InsightsResponseDto`:
- `nullSnapshotProducesTheEmptyState()`
- `runwayModeShowsDaysRemainingWithAnAsOfDate()`
- `spendModeShowsMonthToDateAndProjectionWithAnAsOfDate()`
- `everyRenderedFigureCarriesAnAsOfDate()`
- `incompleteDataQualityIsSuppressed()`
- `asOfDateComesFromTheSnapshotRangeNotTheDeviceClock()`

#### Subagent orchestration

- **Parallel (3):** (a) model + test; (b) layouts + provider XMLs; (c) the
  config activity, which is a near-copy of A3's and touches nothing else.
- **Serial, you:** provider, updater, manifest, strings, refresher.
- Give subagent (a) the verbatim signatures of `buildProductRunway` and the
  spend builder — have it read `InventoryRunway.kt` itself rather than trusting
  a paraphrase.

#### Delivery

- BRANCH: `agent/projection-widgets`
- COMMIT SUBJECT: `feat: add runway and spend projection widgets`
- The PR body must state that ADR-028 (PR B7) authorises the cached-with-label
  behaviour, and link it.

---

### PR B9 — Version bump and documentation for v1.5.0

Do this **after B1-B8 are all merged**.

Same shape as A7:

**1.** `app/build.gradle.kts` lines 38-39:

```kotlin
versionCode = 42
versionName = "1.5.0"
```

**2.** `docs/PROJECT_STATE.md` — a section for the new surfaces: shortcuts, the
tile, and the four new widgets.

**3.** `docs/HANDOFF.md` — as in A7, with provenance amended after publication.

**4.** `docs/ARCHITECTURE.md` — this release adds a new Room table, a new
service, and four new widget providers. If the document describes the widget
layer or the schema, update it. Check before assuming it needs no change.

#### Delivery

- BRANCH: `agent/release-v1-5-0`
- COMMIT SUBJECT: `Bump version to 1.5.0 (versionCode 42)`

Then run §8 with `<VERSION>` = `1.5.0`.

---

## 10. Hard prohibitions

- Do not commit to `main`.
- Do not change `versionCode`, `versionName`, signing config, tags, or releases
  outside A7, B9, and §8.
- Do not change the production Apps Script endpoint, the application ID, the
  package namespace, environment IDs, credentials, or secrets.
- Do not change the value of `EXTRA_START_ROUTE`.
- Do not commit keystores, credentials, tokens, `sandbox.properties`,
  `local.properties`, or any personal machine path.
- Do not use `fallbackToDestructiveMigration`.
- Do not install a debug APK on the physical phone.
- Do not overwrite or delete a published release.
- Do not report a release as delivered until §8 step 5 has actually run and
  passed.
- Do not describe a check you did not run as passing.

## 11. Definition of done

This plan is complete when:

1. All sixteen pull requests are merged into `main`.
2. Both releases are published to `noamvb/cannsheet-mobile-releases` with two
   assets each, verified per §8 step 5, with a signing certificate matching the
   previous release.
3. `docs/PROJECT_STATE.md`, `docs/DECISIONS.md`, and `docs/HANDOFF.md` reflect
   the shipped state, including release provenance for both versions.
4. The repository owner has been told, for each release, to update through
   Obtainium.

If any part of this plan turns out to be wrong about the codebase — a signature
that does not match, a file that does not exist — **stop and report it** rather
than writing something plausible. A wrong guess here reaches a real
spreadsheet of real data.
