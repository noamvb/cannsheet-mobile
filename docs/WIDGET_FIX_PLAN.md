# Widget remediation plan (v1.5.1)

Written: 2026-08-20
Reviewed: `main` at `b22150c`, versionCode 42, versionName 1.5.0.
Subject: the widget work shipped across v1.4.5 (PRs #109-#115) and v1.5.0
(PRs #116-#127), planned in `docs/WIDGET_EXPANSION_PLAN.md`.

The implementation is good. All sixteen pull requests landed, 468 JVM tests pass,
`lintDebug` reports zero errors, both releases are tagged and published, and the
executor correctly overrode two instructions in the previous plan that were
wrong (it exported the configuration activity, which the launcher requires, and
it gave the pen widget a 30-minute update period so the sync indicator cannot
sit stale — both documented in `docs/DECISIONS.md`).

This plan fixes what the review found: one shipped defect that makes a whole
v1.4.5 feature unreachable, one data-safety regression, and a cluster of
localization and presentation issues.

**One of these is my fault.** Finding H1 exists because the previous plan
dictated the wrong `android:configure` format. It is listed first and should be
fixed first.

---

> **Corrections discovered while executing this plan (2026-08-20).** Three of
> its sections asserted platform behaviour that turned out to be wrong. They are
> recorded here rather than edited in place, so the executed history stays
> readable.
>
> - **§7 PR C3** claimed `android.intent.action.DATE_CHANGED` is delivered to
>   manifest-declared receivers. **It is not** — the action is absent from
>   Android's implicit-broadcast exception list, so a manifest receiver never
>   receives it on API 26+. `TIME_SET` and `TIMEZONE_CHANGED` *are* exempt. Note
>   that `adb shell cmd package query-receivers` still lists the receiver:
>   resolution is not delivery. The shipped fix is a self-re-arming WorkManager
>   job (ADR-042).
> - **§6 PR C2** specified a migrated-flag gating `read()`, with migration run
>   only from `PenWidgetUpdater.update`. That leaves a window where an action
>   arriving first reads defaults, ignores a pinned cart, and logs consumption
>   against the wrong product. The shipped design adopts legacy config inside
>   `read()` atomically, so no caller can forget.
> - **§7 PR C3**'s file list omitted `TodayUpdater.kt`, which step 3 of the same
>   section requires changing.
>
> A later addition of `scheduleIfWidgetsExist` also had to be corrected during
> review: it used `ExistingWorkPolicy.REPLACE`, which cancelled the very
> midnight job it was meant to protect, because WorkManager starts a cold
> process whose `Application.onCreate` runs before the worker.

## 1. What the review found

Verified on this machine: `./gradlew --no-daemon testDebugUnitTest
compileDebugAndroidTestKotlin lintDebug assembleDebug` → `BUILD SUCCESSFUL`,
468 tests, 0 failures, 0 lint errors, 124 lint warnings. Finding H1 was
confirmed on a booted API 36 emulator by reading the framework's parsed
`AppWidgetProviderInfo.configure`.

| ID | Severity | Finding | PR |
| --- | --- | --- | --- |
| H1 | **High** | The pen widget's configuration activity is unreachable; `android:configure` parses to a nonexistent component | C1 |
| H2 | **High** | Pending widget commit payloads are now included in cloud backup and device transfer | C2 |
| M1 | Medium | The Today widget never rolls over at midnight | C3 |
| M2 | Medium | Three user-facing strings are hardcoded English in Kotlin | C4 |
| M3 | Medium | The as-of-date invariant from ADR-039 is not enforced where it renders, and is tested against dead code | C5 |
| M4 | Medium | The pen widget's picker preview no longer matches the widget | C6 |
| M5 | Medium | 20 `HardcodedText` lint warnings in the three new widget layouts, which double as picker previews | C6 |
| M6 | Medium | `PenWidgetUpdater.update` dispatches into two other widget families on every update | C7 |
| L1 | Low | `R.string.pen_widget_open_app_description` is orphaned | C6 |
| L2 | Low | 7 `PluralsCandidate` warnings; the repo already uses plurals for exactly these phrasings | C6 |
| L3 | Low | Two definitions of "the queue is stuck" disagree between two surfaces | C8 |
| L4 | Low | The Today streak silently caps at ten days | C8 |
| L5 | Low | `STATUS_ACTIVE = "ACTIVE"` is now defined in three files | C8 |

Checked and deliberately **not** in scope: the `RedundantLabel` lint warning at
`app/src/main/AndroidManifest.xml:36` is pre-existing — `git log -L` traces it to
the initial project commit `dfbaf00`, not to this work. Leave it alone; it
belongs in an unrelated cleanup PR.

Not fixable, recorded only: commits `d76d9aa` (`feat: add a today home-screen
widget`) and `1230cda` (`docs: allow labelled cached projections on widget
surfaces`) reached `main` without a pull-request number, meaning they were not
squash-merged from a PR. That violates the delivery rule but is now history.
Do not attempt to rewrite it. Follow §4 for every PR in this plan.

---

## 2. Executor contract

Identical to `docs/WIDGET_EXPANSION_PLAN.md` §1. The rules that matter most here:

1. **Never commit to `main`.** Branch, PR, squash merge.
2. **One pull request per numbered PR below.** Do not combine.
3. **A PR is done when it is merged and CI is green**, not when the code compiles.
4. **Never report a check as passing unless you ran it and saw it pass.**
5. **Do not change** `versionCode`, `versionName`, signing config, the Apps
   Script endpoint, the application ID, or the package namespace, except in C9.
6. **Never install a debug APK on the physical phone.** Use the emulator.
7. **Do not invent symbols.** Everything referenced below was verified present
   on `b22150c`.

Repo conventions, unchanged: JUnit 4, `org.junit.Assert.*`, camelCase test method
names without backticks, `runBlocking` not `runTest`, `@RunWith(AndroidJUnit4::class)`
for instrumented tests, conventional-commit subjects, `agent/<kebab-slug>` branches,
all user-facing copy in `app/src/main/res/values/strings.xml`.

---

## 3. Environment and subagents

### Environment

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="/Users/sophiaparis/Library/Application Support/com.raycast.macos/NodeJS/runtime/22.14.0/bin:/opt/homebrew/bin:$PATH"
```

`/usr/libexec/java_home -V` reports only JDK 1.8 and is misleading. There is no
system `node`. `adb` is at `/opt/homebrew/bin/adb`. Never create or commit
`local.properties`.

Emulator:

```bash
cd "$ANDROID_HOME/emulator" && ./emulator -avd cannsheet_widget_api36 -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
```

It fails if launched from another directory. Wait for
`adb shell getprop sys.boot_completed` to return `1`. Stop it with `adb emu kill`.

### Subagent rules

Three hard rules, unchanged:

1. **One writer per file, ever.** Two subagents must never edit the same file.
2. **No git, no gradle, no `gh`, no `adb` inside a subagent.**
3. **Re-read before you edit** anything a subagent touched.

These files are serial-only — you edit them, one at a time, never a subagent:

- `app/src/main/res/values/strings.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`
- `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`
- `app/build.gradle.kts`
- everything under `docs/`

**This plan is mostly small, serial PRs.** For C1, C2, C3, C5, C7, C8, and C9 do
not use subagents at all — each is a handful of edits where the ordering matters
and delegation costs more than it saves. Only C4 and C6 have genuinely
parallelizable work, and each says exactly what.

---

## 4. Delivery ritual

Run this for **every** PR. Only the branch, commit subject, and PR title change.

```bash
git switch main && git pull --ff-only && git switch -c <BRANCH>
```

Implement. Then:

```bash
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

A pass ends with `BUILD SUCCESSFUL`. Review the whole diff:

```bash
git status && git diff
```

Confirm no secrets, no `local.properties`, no personal machine paths, no
accidental version or signing changes.

```bash
git add -A
git commit -m "<COMMIT SUBJECT>" -m "<one paragraph: what changed and why>" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push -u origin <BRANCH>
```

```bash
gh pr create --base main --title "<PR TITLE>" --body "$(cat <<'BODY'
## Summary
…

## Motivation
…

## Implementation decisions
…

## Automated tests run
`./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug` — paste the exact result.
List every check you did NOT run and why.

## Manual validation
What you did on the emulator and at which API level. If none, say so plainly.

## Risks and data safety
…

## Screenshots
Before/after for any visible change, or state why absent.
BODY
)"
```

```bash
gh pr checks <number> --watch
gh pr merge <number> --squash --delete-branch
```

> `gh pr merge` is sometimes refused by the permission classifier on this
> machine. If it is, hand the exact command to the repository owner. Do not work
> around it.

**Not complete until the PR exists, CI is green, and it is merged.** Do not start
the next PR before this one merges; each branches from the updated `main`.

---

## 5. PR C1 — Fix the pen widget configuration component

**Severity: High. Do this first and merge it before anything else.**

### What is wrong

`app/src/main/res/xml/pen_consumption_widget_info.xml:4` and
`app/src/main/res/xml-v31/pen_consumption_widget_info.xml:4` both declare:

```xml
android:configure="com.noamv.cannsheet.mobile/com.example.widget.PenWidgetConfigureActivity"
```

The framework does not parse that attribute as a flattened ComponentName. It
takes the string as a **class name** and builds
`ComponentName(providerPackage, thatString)`. Confirmed by reading
`AppWidgetProviderInfo.configure` on a booted API 36 emulator running the
`b22150c` debug build:

```
PenConsumptionWidgetProvider  configure=ComponentInfo{com.noamv.cannsheet.mobile/com.noamv.cannsheet.mobile/com.example.widget.PenWidgetConfigureActivity}
ProjectionWidgetProvider      configure=ComponentInfo{com.noamv.cannsheet.mobile/com.example.widget.projection.ProjectionWidgetConfigureActivity}
```

The pen widget's class name contains a slash, so the component does not exist.

**Consequences.** On API 31+ the widget carries
`widgetFeatures="reconfigurable|configuration_optional"`, so adding it silently
skips configuration and the launcher's "reconfigure" entry targets a dead
component. Every A3 feature — pinned cart, discreet mode, per-instance step size
— is therefore unreachable in both shipped releases, which is why no one noticed.
On API 24-30 there is no `configuration_optional`, so the host is expected to
launch the configuration activity as part of adding the widget; a dead component
there can fail the add flow outright. `minSdk` is 24, so this must be checked.

The projection widget, added later in B8, uses the correct bare-class form. That
inconsistency is the tell.

### Files

- `app/src/main/res/xml/pen_consumption_widget_info.xml`
- `app/src/main/res/xml-v31/pen_consumption_widget_info.xml`
- `app/src/androidTest/java/com/example/widget/PenWidgetProviderInfoTest.kt` (new)

### Steps

**1.** In **both** provider-info files, replace the attribute with the bare
fully-qualified class name:

```xml
android:configure="com.example.widget.PenWidgetConfigureActivity"
```

Do not add the application id. Do not use a leading dot. Match the projection
widget's form exactly.

**2.** Add the regression test that would have caught this. New file
`app/src/androidTest/java/com/example/widget/PenWidgetProviderInfoTest.kt`:

```kotlin
package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the `android:configure` attribute format. The framework treats the
 * attribute as a bare class name and builds ComponentName(providerPackage, it),
 * so a flattened "package/class" value yields a class name containing a slash
 * and a component that cannot be started.
 */
@RunWith(AndroidJUnit4::class)
class PenWidgetProviderInfoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun everyConfigurableProviderResolvesItsConfigurationActivity() {
        val packageManager = context.packageManager
        val providers = AppWidgetManager.getInstance(context).installedProviders
            .filter { it.provider.packageName == context.packageName }

        assertTrue("No Cannsheet widget providers found", providers.isNotEmpty())

        providers.mapNotNull { it.configure }.forEach { configure ->
            assertEquals(
                "Configuration component must live in this package",
                context.packageName,
                configure.packageName,
            )
            assertTrue(
                "Class name must not contain '/': $configure",
                !configure.className.contains('/'),
            )
            assertNotNull(
                "Configuration activity is not resolvable: $configure",
                packageManager.resolveActivity(
                    android.content.Intent().setComponent(configure),
                    0,
                ),
            )
        }
    }

    @Test
    fun penWidgetDeclaresItsConfigurationActivity() {
        val info = AppWidgetManager.getInstance(context).installedProviders
            .single { it.provider.className == PenConsumptionWidgetProvider::class.java.name }

        assertEquals(
            "com.example.widget.PenWidgetConfigureActivity",
            info.configure?.className,
        )
    }
}
```

The first test covers every provider, so the projection widget and any future
one are guarded too.

### Traps

- **Do not "fix" the projection widget to match the pen widget.** The projection
  widget is the correct one.
- **Do not change `PenWidgetConfigureActivity`'s manifest entry.** It is
  `android:exported="true"` deliberately — the launcher runs in a different UID
  and cannot otherwise start the `APPWIDGET_CONFIGURE` flow. That was a
  correction the executor made to the previous plan and it is right. Reverting it
  would break configuration a second way.
- `resolveActivity` needs the component's package to match; a wrong package
  silently returns null, which is why the test asserts the package separately.

### Manual validation — mandatory

This is a behavioural fix, so compilation proves nothing.

1. Boot the API 36 emulator, install the debug APK, add the pen widget from the
   picker, and confirm the configuration screen appears.
2. Long-press the placed widget and confirm the launcher's reconfigure entry
   opens the same screen.
3. Set a pinned cart and discreet mode, save, and confirm the widget re-renders
   with them applied.
4. Record all four results in the PR body.

### Subagents

Do not use any. Two attribute edits and one test file.

### Delivery

- BRANCH: `agent/fix-widget-configure-component`
- COMMIT SUBJECT: `fix: point the pen widget at a resolvable configuration activity`
- PR TITLE: same.
- Screenshots: the configuration screen reached from the picker.

---

## 6. PR C2 — Stop backing up pending widget commits

**Severity: High.**

### What is wrong

To make `onRestored` useful, PR #114 removed the exclusions for
`datastore/pen_widget_state.preferences_pb` from both
`app/src/main/res/xml/backup_rules.xml` and
`app/src/main/res/xml/data_extraction_rules.xml`.

That file does not only hold configuration. It holds, keyed per widget id:

| Key prefix | Contents |
| --- | --- |
| `draft_seconds_` | an uncommitted draft |
| `pending_commit_` | **a captured commit payload awaiting the Room write** |
| `last_queued_at_` | a confirmation timestamp |
| `pinned_product_`, `discreet_`, `step_override_` | per-instance configuration |

`pending_commit_` is queue-participating state. The backup file's own comment
still says the opposite:

> Excluded: everything that participates in queue synchronization or represents
> a point-in-time server snapshot.

`cannsheet_db` is correctly still excluded from backup. So after a restore the
device has **no queue and no history**, but may have a resurrected pending
payload from whenever the backup was taken. `CannsheetApplication.onCreate`
calls `PenWidgetCommitCoordinator.flushOverdue(...)` on **every** app start, and
`MAX_PENDING_AGE_MILLIS` is 10 minutes, so a restored payload is overdue by
definition and commits immediately — writing a consumption the user may have
already synced, or already undone, on the old device.

**This is why the fix cannot be "strip pending keys in `onRestored`".** The
application's own start-up flush runs before any widget host restore callback.
The payload has to be absent from the backup in the first place.

### The fix: split the store

Move per-instance configuration into its own DataStore file, back **that** up,
and restore the exclusion on `pen_widget_state`.

### Files

- `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`
- `app/src/main/java/com/example/widget/PenWidgetConfigRepository.kt` (new)
- `app/src/main/java/com/example/widget/PenConsumptionWidgetProvider.kt`
- `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`
- `app/src/main/java/com/example/widget/PenWidgetConfigureActivity.kt`
- `app/src/main/java/com/example/widget/PenQuickTileService.kt`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/androidTest/java/com/example/widget/PenWidgetConfigStateTest.kt`
- `app/src/androidTest/java/com/example/widget/PenWidgetRestoreTest.kt`

### Steps

**1.** New file `PenWidgetConfigRepository.kt`. Copy the construction and
`requireValidWidgetId` pattern from `PenWidgetStateRepository` exactly:

```kotlin
package com.example.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.penWidgetConfigDataStore by preferencesDataStore(name = "pen_widget_config")

/**
 * Per-instance widget configuration, deliberately kept in a different DataStore
 * file from [PenWidgetStateRepository]. Configuration is safe to restore onto a
 * new device; drafts and pending commit payloads are not, because the Room
 * queue they belong to is excluded from backup and the application flushes
 * overdue payloads on every start.
 */
class PenWidgetConfigRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.penWidgetConfigDataStore)

    suspend fun read(appWidgetId: Int): PenWidgetInstanceConfig
    suspend fun write(appWidgetId: Int, config: PenWidgetInstanceConfig)
    suspend fun clear(appWidgetId: Int)
    suspend fun remapWidgetIds(oldWidgetIds: IntArray, newWidgetIds: IntArray)
}
```

Fill those four bodies by moving the existing implementations verbatim out of
`PenWidgetStateRepository`: `readConfig` becomes `read`, `writeConfig` becomes
`write`, and the three config key helpers plus the three prefix constants move
across unchanged. Keep the prefix strings byte-identical
(`"pinned_product_"`, `"discreet_"`, `"step_override_"`) — step 2 depends on it.

**2. One-time migration.** Existing v1.4.5/v1.5.0 installs have config keys in
`pen_widget_state`. Read must fall back once and copy forward:

```kotlin
suspend fun read(appWidgetId: Int): PenWidgetInstanceConfig {
    requireValidWidgetId(appWidgetId)
    val preferences = dataStore.data.first()
    if (preferences[migratedKey(appWidgetId)] == true) {
        return readFrom(preferences, appWidgetId)
    }
    return PenWidgetInstanceConfig.DEFAULT
}
```

and a separate explicit migration entry point called once per widget id from
`PenWidgetUpdater.update`, before the config is read:

```kotlin
/**
 * Copies any pre-v1.5.1 configuration out of the legacy state store. Idempotent:
 * the per-widget migrated flag makes a second call a no-op.
 */
suspend fun migrateFromLegacyStore(appWidgetId: Int, legacy: PenWidgetStateRepository)
```

Implement it as: if the migrated flag is already set, return. Otherwise call
`legacy.readLegacyConfig(appWidgetId)`, write it here, set the flag, and then
call `legacy.clearLegacyConfig(appWidgetId)`.

**3.** In `PenWidgetStateRepository`, rename the existing `readConfig` to
`readLegacyConfig` and `writeConfig` to nothing — delete it. Add:

```kotlin
suspend fun clearLegacyConfig(appWidgetId: Int)
```

that removes only the three config keys. Keep the three config key removals in
`clear(appWidgetId)` too, so deleting a widget still cleans legacy leftovers.
Remove the three config fields from `remapWidgetIds` and its private
`RemappedState` — after this PR that store holds nothing restorable, and the
config store does its own remap.

**4.** Update the three callers to read config from the new repository:
`PenWidgetUpdater.update`, `PenWidgetConfigureActivity.save`, and
`PenQuickTileService` (`refreshTileFromState` and `submitDefaultPreset`).

**5.** `PenConsumptionWidgetProvider.onRestored` must remap **both** stores:

```kotlin
PenWidgetConfigRepository(appContext).remapWidgetIds(oldWidgetIds, newWidgetIds)
PenWidgetStateRepository(appContext).remapWidgetIds(oldWidgetIds, newWidgetIds)
```

Keep the state remap: within a single device an id remap can still happen and
the draft should follow. It is only *cross-device restore* that must not carry
payloads, and step 6 is what prevents that.

`onDeleted` must call `clear` on both stores.

**6.** Restore the exclusions. In `app/src/main/res/xml/backup_rules.xml`, add
back:

```xml
<exclude domain="file" path="datastore/pen_widget_state.preferences_pb" />
```

and update the comment block to read:

```xml
    Included: user settings and pen_widget_config. Per-instance widget
    configuration carries no synchronization identity and is safe to restore
    onto any install; AppWidgetProvider.onRestored remaps its IDs.

    Excluded: everything that participates in queue synchronization or
    represents a point-in-time server snapshot. pen_widget_state holds captured
    commit payloads that the application flushes on start, so restoring it would
    re-queue consumptions the source device already recorded.
```

Add the same `<exclude>` line to **both** the `<cloud-backup>` and
`<device-transfer>` blocks of
`app/src/main/res/xml/data_extraction_rules.xml`. Do not exclude
`pen_widget_config` anywhere — it is the file that should travel.

### Traps

- **Do not add `pen_widget_config` to the exclusion lists.** The whole point is
  that it is backed up. Read your diff and confirm you excluded the right file.
- **`preferencesDataStore` is a property delegate and must be top-level**, one
  per file per name. Declaring a second delegate with the name
  `"pen_widget_state"` anywhere throws at first access.
- **Do not migrate inside `read`.** A read happens on every render, including
  from the tile and the commit coordinator; a write-on-read races the config
  activity. Migrate explicitly from `PenWidgetUpdater.update` only.
- The migrated flag must be **per widget id**, not global — widgets restored at
  different times must each get their one-time copy.

### Tests

Extend `PenWidgetConfigStateTest.kt` (androidTest, `runBlocking`), pointing it at
the new repository:
- `writeThenReadRoundTrips()`
- `readOnAnUnconfiguredWidgetReturnsDefaults()`
- `clearRemovesEveryConfigKey()`
- `configForOneWidgetDoesNotLeakToAnother()`
- `migrationCopiesLegacyConfigOnceAndClearsTheLegacyKeys()`
- `migrationIsIdempotent()`
- `migrationLeavesDraftsAndPendingPayloadsInTheLegacyStore()`

Extend `PenWidgetRestoreTest.kt`:
- `remapMovesConfigInTheConfigStore()`
- `remapMovesDraftAndPendingInTheStateStore()`
- `remapHandlesOverlappingOldAndNewIds()` (keep the existing coverage)

### Manual validation

On the emulator, confirm that installing this build over a v1.5.0 build keeps an
existing widget's pinned cart and discreet mode — that is the migration path.
Record the result. A full backup/restore cycle is hard to reproduce on an
emulator; if you cannot do it, say so plainly rather than implying you did.

### Subagents

Do not use any. Every file is either on the serial list or depends on a signature
introduced two steps earlier.

### Delivery

- BRANCH: `agent/widget-config-store-split`
- COMMIT SUBJECT: `fix: keep pending widget commits out of backup and device transfer`
- The PR body's "Risks and data safety" section must explain the resurrection
  scenario, the start-up flush that rules out fixing it in `onRestored`, and the
  one-time migration path for existing installs.

---

## 7. PR C3 — Roll the Today widget over at midnight

**Severity: Medium.**

### What is wrong

`app/src/main/res/xml/today_widget_info.xml` and its `xml-v31` twin both declare
`android:updatePeriodMillis="0"`, and `TodayWidgetProvider` overrides only
`onUpdate`. Nothing in the app listens for a date change. The widget therefore
refreshes only when a widget host update fires or when
`CannsheetWidgetRefresher.refreshAll()` runs — that is, after a sync or a pen
state change.

At local midnight the widget keeps rendering the previous day's total, its
comparison, and its streak until the user happens to log something. For a widget
whose entire subject is "today", that is a functional defect.

### Files

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/widget/today/TodayWidgetProvider.kt`
- `app/src/test/java/com/example/widget/today/TodayUiModelTest.kt`

### Steps

**1.** `TodayWidgetProvider` is already a manifest-registered receiver. Add the
date and time broadcasts to its existing `<receiver>` block's intent filter:

```xml
<intent-filter>
    <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    <action android:name="android.intent.action.DATE_CHANGED" />
    <action android:name="android.intent.action.TIME_SET" />
    <action android:name="android.intent.action.TIMEZONE_CHANGED" />
</intent-filter>
```

All three extra actions are protected broadcasts that the system still delivers
to manifest-declared receivers; they are not subject to the implicit-broadcast
restrictions that block most manifest receivers since API 26. `DATE_CHANGED`
covers midnight, `TIME_SET` covers a manual clock change, and
`TIMEZONE_CHANGED` covers travel.

**2.** Handle them in `TodayWidgetProvider`:

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
        Intent.ACTION_DATE_CHANGED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        -> {
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            PenWidgetRuntime.launchReceiver(pendingResult) {
                TodayUpdater.updateAllSuspending(appContext)
            }
        }

        else -> super.onReceive(context, intent)
    }
}
```

`Intent.ACTION_TIME_CHANGED` is the constant for `android.intent.action.TIME_SET`
— the names differ, which is easy to get wrong.

**3.** `TodayUpdater.updateAll` is `fun`, not `suspend`, and launches its own
coroutine; calling it from inside `launchReceiver` would return before the work
finished and `goAsync()` would be finished early. Add a suspending sibling:

```kotlin
suspend fun updateAllSuspending(context: Context) {
    val appContext = context.applicationContext
    val manager = AppWidgetManager.getInstance(appContext)
    val component = ComponentName(appContext, TodayWidgetProvider::class.java)
    manager.getAppWidgetIds(component).forEach { update(appContext, it) }
}
```

Keep `updateAll` as-is; `CannsheetWidgetRefresher` calls it from a non-suspending
context.

### Traps

- **Do not add `android:exported="true"`** to the receiver. System broadcasts
  reach non-exported manifest receivers; exporting it would let any app trigger
  refreshes.
- **Do not solve this with `updatePeriodMillis`.** The minimum is 30 minutes and
  it wakes the device; a date-change broadcast is free and exact.
- **Do not call `TodayUpdater.updateAll` from inside `launchReceiver`** — see
  step 3.

### Tests

The broadcast wiring is not unit-testable here, so cover the model boundary that
the rollover exposes. Add to `TodayUiModelTest.kt`:
- `todayTotalIsZeroWhenTheDateAdvancesPastEveryEntry()`
- `streakEndsYesterdayWhenTodayHasNoEntries()`

Both already pass a `todayDate` parameter, so simply advance it.

### Manual validation

On the emulator:

```bash
adb shell date 010200002027.00 && adb shell am broadcast -a android.intent.action.DATE_CHANGED
```

Confirm the widget resets to the empty state. Restore the clock afterward with
`adb shell settings put global auto_time 1`. Record the result.

### Subagents

Do not use any.

### Delivery

- BRANCH: `agent/today-widget-date-rollover`
- COMMIT SUBJECT: `fix: refresh the today widget when the date changes`
- Screenshot: the widget before and after the simulated rollover.
---

## 8. PR C4 — Move the last three hardcoded strings into resources

**Severity: Medium.**

### What is wrong

Most of the new widget code resources its copy correctly. Three places do not,
and they are the ones a translator or a copy change would silently miss. The
original widget review closed this exact class of finding as W-17; it has
partially regressed.

**1. `app/src/main/java/com/example/widget/sync/SyncStatusUiModel.kt`.**
`formatLastSyncLabel` builds five English strings in Kotlin and wraps the result
in `PenWidgetText.Literal`:

```kotlin
"Never synced", "Synced just now", "Synced ${ageMinutes}m ago",
"Synced ${ageMinutes / MINUTES_PER_HOUR}h ago", "Synced yesterday",
"Synced ${ageMinutes / MINUTES_PER_DAY}d ago"
```

Every other string in that widget already goes through `strings.xml` — including
a correct `sync_status_widget_pending` plural — so this one line is the outlier.

**2. `app/src/main/java/com/example/widget/today/TodayUiModel.kt`.**
`private const val EMPTY_TODAY_DISPLAY = "No logs yet today"`, rendered straight
into the view by `TodayRenderer` line 22 via `model.todayDisplay`. Every other
string in `TodayRenderer` uses `context.getString`.

**3. `app/src/main/java/com/example/widget/PenTileState.kt`.** `penTileState`
returns hardcoded `"Undo"` and `"No cart loaded"` labels — which
`PenQuickTileService.refreshTileFromState` then **discards**, substituting
`R.string.pen_tile_undo` and `R.string.pen_tile_no_cart` for those two states.
So the literals are dead in production but live in `PenTileStateTest`, which
therefore asserts on text the user never sees.

### Files

- `app/src/main/java/com/example/widget/sync/SyncStatusUiModel.kt`
- `app/src/main/java/com/example/widget/sync/SyncStatusRenderer.kt`
- `app/src/main/java/com/example/widget/today/TodayUiModel.kt`
- `app/src/main/java/com/example/widget/today/TodayRenderer.kt`
- `app/src/main/java/com/example/widget/PenTileState.kt`
- `app/src/main/java/com/example/widget/PenQuickTileService.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/example/widget/sync/SyncStatusUiModelTest.kt`
- `app/src/test/java/com/example/widget/today/TodayUiModelTest.kt`
- `app/src/test/java/com/example/widget/PenTileStateTest.kt`

### Steps

**1.** Add to `app/src/main/res/values/strings.xml`, next to the existing
`sync_status_*` block:

```xml
<string name="sync_status_last_sync_never">Never synced</string>
<string name="sync_status_last_sync_just_now">Synced just now</string>
<string name="sync_status_last_sync_minutes">Synced %1$dm ago</string>
<string name="sync_status_last_sync_hours">Synced %1$dh ago</string>
<string name="sync_status_last_sync_yesterday">Synced yesterday</string>
<string name="sync_status_last_sync_days">Synced %1$dd ago</string>
```

next to the `today_widget_*` block:

```xml
<string name="today_widget_no_logs">No logs yet today</string>
```

and next to the `pen_tile_*` block:

```xml
<string name="pen_tile_loaded">%1$s · %2$ds</string>
```

**2.** `formatLastSyncLabel` returns `PenWidgetText` instead of `String`:

```kotlin
private fun formatLastSyncLabel(
    lastMeaningfulSyncAtEpochMillis: Long?,
    nowMillis: Long,
): PenWidgetText {
    val lastSync = lastMeaningfulSyncAtEpochMillis
        ?: return PenWidgetText.Resource(R.string.sync_status_last_sync_never)
    val ageMinutes = (nowMillis - lastSync).coerceAtLeast(0L) / MINUTE_MILLIS
    return when {
        ageMinutes < 1L ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_just_now)
        ageMinutes < MINUTES_PER_HOUR ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_minutes, listOf(ageMinutes.toInt()))
        ageMinutes < MINUTES_PER_DAY ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_hours, listOf((ageMinutes / MINUTES_PER_HOUR).toInt()))
        ageMinutes < MINUTES_PER_DAY * 2L ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_yesterday)
        else ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_days, listOf((ageMinutes / MINUTES_PER_DAY).toInt()))
    }
}
```

`PenWidgetText.Resource` takes `arguments: List<Any>`, and `%1$d` requires an
`Int`, not a `Long` — `String.format` throws `IllegalFormatConversionException`
on a `Long` for `%d`… it does not, but `getQuantityString`-style mistakes here
are common, so convert explicitly with `.toInt()` as shown and keep it obvious.

`SyncStatusUiModel.lastSyncLabel` is already typed `PenWidgetText`, so the data
class needs no change. `SyncStatusRenderer` must resolve it — check whether it
already calls a `resolve` helper; `PenWidgetText.resolve` is a private extension
in `PenWidgetRenderer.kt`. If the sync renderer has its own copy, leave it; if it
was calling `.value` on a `Literal`, replace that with the resolve call.

**3.** `TodayUiModel.todayDisplay` becomes `PenWidgetText`:

```kotlin
todayDisplay = if (hasTodayEntries) {
    PenWidgetText.Literal(formatQuantityInInputUnit(todayUses, secondsPerUse))
} else {
    PenWidgetText.Resource(R.string.today_widget_no_logs)
},
```

`Literal` is correct for the formatted quantity — that is data, not copy. Delete
`EMPTY_TODAY_DISPLAY`. Update `emptyTodayUiModel()` the same way, and resolve it
in `TodayRenderer` line 22.

**4.** `PenTileModel.label` becomes `PenWidgetText`:

```kotlin
internal data class PenTileModel(
    val label: PenWidgetText,
    val state: Int,
)
```

with `PenWidgetText.Resource(R.string.pen_tile_undo)`,
`PenWidgetText.Resource(R.string.pen_tile_no_cart)`, and
`PenWidgetText.Resource(R.string.pen_tile_loaded, listOf(loaded.product.name, seconds))`.

Then **delete the label-substitution `when` block** in
`PenQuickTileService.refreshTileFromState` — it exists only to paper over the
hardcoded labels — and resolve `model.label` directly.

### Traps

- **Do not move the formatted quantity into a resource.**
  `formatQuantityInInputUnit` output is a number, and wrapping it in a
  `<string>` would double-format it.
- **`PenWidgetText` lives in `com.example.widget`.** `SyncStatusUiModel` already
  imports it, so that file needs no new import. `TodayUiModel` and
  `PenTileState` do not yet use it — `TodayUiModel` currently types
  `todayDisplay` as `String` — so add
  `import com.example.widget.PenWidgetText` there. Both subpackages already
  depend on the parent package, so no new dependency direction is created. Do
  not create a second text abstraction in either subpackage.
- **Do not delete `pen_tile_undo` or `pen_tile_no_cart`.** They stop being used
  by the service and start being used by the model. Deleting them and
  re-adding them under new names churns the diff for nothing.

### Tests

Rewrite the three test files' assertions to compare against `PenWidgetText`
values rather than English:

`SyncStatusUiModelTest.kt` — replace every `assertEquals("Synced 5m ago", …)`
with a comparison to
`PenWidgetText.Resource(R.string.sync_status_last_sync_minutes, listOf(5))`.
`PenWidgetText.Resource` is a data class, so equality works. Keep every existing
case: never synced, just now, minutes, hours, yesterday, days, and the
clock-rollback case.

`TodayUiModelTest.kt` — `emptyHistoryProducesTheEmptyState()` asserts
`PenWidgetText.Resource(R.string.today_widget_no_logs)`.

`PenTileStateTest.kt` — all five cases assert on resource ids and arguments.

Unit tests referencing `R.string.*` compile fine here: this is an Android
library-style unit test source set with `R` available at compile time. If a test
fails at runtime because resource ids are stubbed, move only that assertion to
comparing `PenWidgetText.Resource::resourceId` rather than resolving the string.

### Subagents

**Parallel (3 subagents), after step 1 is written by you:** one takes the sync
model + its test, one takes the today model + renderer + its test, one takes the
tile model + service + its test. The three groups touch disjoint files.

**Serial, you:** `strings.xml` (step 1), first and alone.

Give each subagent the exact resource names from step 1 so all three code
against the same strings.

### Delivery

- BRANCH: `agent/widget-string-resources`
- COMMIT SUBJECT: `fix: move remaining widget copy into string resources`

---

## 9. PR C5 — Enforce the as-of invariant where it renders

**Severity: Medium.**

### What is wrong

ADR-039 permits a widget to show a cached projection **only** when it displays
the snapshot's as-of date. The previous plan enforced that by coupling the two in
one value:

```kotlin
// ProjectionUiModel.kt:48
val renderedText: String
    get() = "$valueText — as of $asOfDate"
```

`ProjectionWidgetRenderer` does not use it. It sets the value into
`R.id.widget_projection_primary` (line 69) and the date into
`R.id.widget_projection_as_of` (lines 79-80) as two independent operations, and
line 58 has a path that sets `widget_projection_as_of` to `View.GONE`.

So the coupling is decorative. `renderedText` is referenced **only** by
`ProjectionUiModelTest` (five assertions), which means the test named
`everyRenderedFigureCarriesAnAsOfDate` verifies a string the user never sees. The
invariant that a durable decision rests on is unenforced in production and
falsely reported as tested.

Also, `renderedText` hardcodes `" — as of "` in Kotlin while
`R.string.projection_as_of` ("as of %1$s") already exists and is correctly used
by the renderer.

### Files

- `app/src/main/java/com/example/widget/projection/ProjectionUiModel.kt`
- `app/src/main/java/com/example/widget/projection/ProjectionWidgetRenderer.kt`
- `app/src/test/java/com/example/widget/projection/ProjectionUiModelTest.kt`
- `app/src/androidTest/java/com/example/widget/projection/ProjectionWidgetRendererTest.kt` (new)

### Steps

**1.** Delete the `renderedText` computed property from `ProjectionFigure`. Keep
the class comment but rewrite it to describe what actually holds the invariant
after this PR.

**2.** In `ProjectionWidgetRenderer`, make the two writes inseparable. Extract a
single private function that is the **only** place either view id is written:

```kotlin
/**
 * The one place a figure reaches the view. Value and as-of date are written
 * together so no future edit can render a projection without its date, which
 * ADR-039 requires for any cached figure.
 */
private fun RemoteViews.setFigure(context: Context, figure: ProjectionFigure) {
    setViewVisibility(R.id.widget_projection_primary, View.VISIBLE)
    setTextViewText(R.id.widget_projection_primary, figure.valueText)
    setViewVisibility(R.id.widget_projection_as_of, View.VISIBLE)
    setTextViewText(
        R.id.widget_projection_as_of,
        context.getString(R.string.projection_as_of, figure.asOfDate),
    )
}
```

Replace every existing write to those two ids in the `Ready` path with one
`setFigure(context, figure)` call. In the `Suppressed` path both ids are hidden
together — that is correct and stays.

**3.** Review line 58's `setViewVisibility(R.id.widget_projection_as_of, View.GONE)`.
After step 2 it must appear only in the suppressed branch, where the primary
value is also hidden or replaced by the suppression message. If it can be reached
while a figure is showing, that is the bug; remove that path.

### Traps

- **Do not keep `renderedText` "for the tests".** A test asserting on a string
  the renderer does not produce is worse than no test, because it reads as
  coverage. That is the whole finding.
- **Do not add a second `as of` string.** `R.string.projection_as_of` exists.

### Tests

**1.** In `ProjectionUiModelTest.kt`, rewrite the five `renderedText` assertions
to assert on `valueText` and `asOfDate` as separate fields. Keep
`everyRenderedFigureCarriesAnAsOfDate` but change it to assert that every figure
has a non-blank, ISO-formatted `asOfDate`.

**2.** New instrumented test
`app/src/androidTest/java/com/example/widget/projection/ProjectionWidgetRendererTest.kt`
that actually inflates the RemoteViews and reads the two `TextView`s. Copy the
inflation approach from `app/src/androidTest/java/com/example/widget/PenWidgetRendererTest.kt`
— read that file first, it already solves applying a `RemoteViews` to a real view
tree.

- `readyFigureRendersBothTheValueAndTheAsOfDate()`
- `asOfDateIsVisibleWheneverTheValueIsVisible()`
- `suppressedModelHidesBothTheValueAndTheAsOfDate()`

The second test is the one that enforces ADR-039. Name it so a future reader
knows not to delete it.

### Subagents

Do not use any. Three files, and step 2 must be written against what step 1
leaves behind.

### Delivery

- BRANCH: `agent/projection-as-of-invariant`
- COMMIT SUBJECT: `fix: render projection values and their as-of date together`
- The PR body must cite ADR-039 and state that the previous coupling was not
  reached by the renderer.

---

## 10. PR C6 — Widget preview and resource hygiene

**Severity: Medium.**

### What is wrong

Four related resource problems, all visible in `lintDebug` output or in the
launcher's widget picker.

**1. `app/src/main/res/layout/widget_pen_consumption_preview.xml` was never
updated.** It is the `previewLayout` in
`app/src/main/res/xml-v31/pen_consumption_widget_info.xml`, so it is what the
API 31+ picker shows. PR #109 added a preset row to the real layouts; the preview
still shows the old three-row widget. `grep -c widget_pen_preset` returns 4 for
`widget_pen_consumption.xml`, 3 for the compact layout, and **0** for the preview.

**2. 20 `HardcodedText` warnings** across `widget_projection.xml`,
`widget_sync_status.xml`, and `widget_today.xml` — for example `"Projection
unavailable"`, `"Never synced"`, `"0 pending"`, `"Streak: 0 days"`, `"as of —"`.
These layouts are their own `previewLayout`, so unlike the pen widget's
placeholders these strings **are** shown to the user, untranslated, in the picker.

**3. `R.string.pen_widget_open_app_description` is orphaned.** PR #113 repointed
the widget name tap from `ACTION_OPEN_LOG` to `ACTION_OPEN_CART_PICKER` and
switched to `pen_widget_open_picker_description`. Lint confirms:
`strings.xml:51 — The resource R.string.pen_widget_open_app_description appears
to be unused`.

**4. Seven `PluralsCandidate` warnings**, at `strings.xml` lines 53, 62, 63, 64,
100, 122, and 128. The repo already models exactly these phrasings as plurals
(`pen_widget_reset_seconds_description`, `pen_widget_logging_seconds_description`,
`sync_status_widget_pending`), so the new strings diverged from an established
local convention.

### Files

- `app/src/main/res/layout/widget_pen_consumption_preview.xml`
- `app/src/main/res/layout/widget_projection.xml`
- `app/src/main/res/layout/widget_sync_status.xml`
- `app/src/main/res/layout/widget_today.xml`
- `app/src/main/res/values/strings.xml`
- the renderers that resolve the newly-pluralized strings
- the tests that assert on them

### Steps

**1.** Add a preset row to `widget_pen_consumption_preview.xml` mirroring
`widget_pen_consumption.xml` lines 132-176: the same `widget_pen_preset_row`
container and three buttons with the same background, weights, and text sizes.
Give them static preview values via three new `translatable="false"` strings:

```xml
<string name="pen_widget_preview_preset_1" translatable="false">15s</string>
<string name="pen_widget_preview_preset_2" translatable="false">30s</string>
<string name="pen_widget_preview_preset_3" translatable="false">60s</string>
```

Follow the existing preview convention: `pen_widget_preview_product`,
`pen_widget_preview_subtitle`, and `pen_widget_preview_seconds` already work this
way.

**2.** Replace every hardcoded `android:text` in the three new widget layouts
with a `@string/...` reference. Most of the needed strings already exist —
`projection_widget_unavailable`, `sync_status_widget_pending_none`,
`today_widget_no_streak`, and after PR C4 also `sync_status_last_sync_never` and
`today_widget_no_logs`. Add resources only where none exists, and give them
`_preview` names when they are placeholder-only.

Run `./gradlew --no-daemon lintDebug` and confirm `HardcodedText` drops from 20
to 6. Six remain and are correct: the `−` and `+` glyphs in the three pen widget
layouts are symbols, not copy.

**3.** Delete `pen_widget_open_app_description` from `strings.xml`. Confirm with
`grep -rn "pen_widget_open_app_description" app/src` that nothing references it —
if anything does, stop, because that means a code path regressed instead.

**4.** Convert the seven flagged strings to plurals. For each, add a
`<plurals>` with `one` and `other` items, delete the `<string>`, and change the
call site from `context.getString(R.string.x, n)` to
`context.resources.getQuantityString(R.plurals.x, n, n)` — note `n` appears
**twice**: once to select the quantity and once as the format argument. Getting
that wrong is the classic plurals bug and produces a `MissingFormatArgument`
crash at render time.

The seven, with their call sites:

| String | Call site |
| --- | --- |
| `pen_widget_undo_window` | `PenWidgetUiModel.buildPenWidgetUiModel` |
| `pen_widget_preset_description` | `PenWidgetRenderer` preset loop |
| `pen_widget_increase` | `PenWidgetRenderer` |
| `pen_widget_decrease` | `PenWidgetRenderer` |
| `today_widget_streak` | `TodayRenderer` |
| `projection_widget_more` | `ProjectionWidgetRenderer` |
| `multi_cart_more` | `MultiCartRenderer` |

`pen_widget_undo_window` is passed `UNDO_WINDOW_MILLIS / 1_000L`, a `Long`.
`getQuantityString` takes an `Int` quantity — convert with `.toInt()`.

### Traps

- **Do not delete the `−`/`+` `android:text` attributes.** They are the button
  glyphs and are correctly `HardcodedText`-flagged but correct.
- **Do not add `tools:text` instead of `@string`.** `tools:` attributes are
  stripped at build time, so a `previewLayout` using them renders blank in the
  picker — which is exactly the W-20 defect the original review fixed.
- **Plurals need both arguments.** See step 4.
- **`android:text` on a view the renderer overwrites is still shown in the
  picker.** That is why step 2 matters even for views the renderer always sets.

### Tests

Update any test asserting on the seven converted strings. Add to
`PenWidgetPresetsTest` or the relevant renderer test:
- `presetDescriptionUsesTheSingularFormForOneSecond()`

Nothing else needs new coverage; `lintDebug` is the check for steps 2 and 3.

### Subagents

**Parallel (2 subagents):** one takes `widget_pen_consumption_preview.xml` (step
1); one takes the three new widget layouts (step 2). Disjoint files.

**Serial, you:** `strings.xml` for all four steps, then every renderer call-site
change in step 4, then the tests. Do the `strings.xml` additions **before**
spawning, so the subagents reference names that exist.

### Delivery

- BRANCH: `agent/widget-resource-hygiene`
- COMMIT SUBJECT: `fix: correct widget previews and localize remaining widget layout text`
- The PR body must record the before/after `HardcodedText` and
  `PluralsCandidate` warning counts.
- Screenshot: the API 31+ widget picker showing the pen widget preview with its
  preset row.

---

## 11. PR C7 — Route widget updates by provider

**Severity: Medium.**

### What is wrong

`PenWidgetUpdater.update` — the pen widget's own updater — now begins:

```kotlin
if (MultiCartUpdater.ownsAppWidgetId(appContext, appWidgetId)) {
    MultiCartUpdater.update(appContext, appWidgetId)
    return
}
if (appWidgetId == PEN_TILE_WIDGET_ID) {
    PenQuickTileService.requestRefresh(context)
    return
}
```

This exists because `PenWidgetCommitCoordinator` is constructed with
`updateWidget = PenWidgetUpdater::update` and now serves three surfaces — the pen
widget, the multi-cart widget, and the tile — all sharing one DataStore keyed by
widget id.

Two problems. It inverts the layering: the pen widget's updater must know about
every other widget family, so adding a fourth means editing it again. And
`ownsAppWidgetId` calls `AppWidgetManager.getAppWidgetIds` on **every** update,
including the three renders the API 31+ size-mapped path already performs.

### Files

- `app/src/main/java/com/example/widget/PenWidgetSurfaceRouter.kt` (new)
- `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`
- `app/src/main/java/com/example/widget/PenWidgetCommitCoordinator.kt`
- `app/src/main/java/com/example/widget/multi/MultiCartUpdater.kt`
- `app/src/test/java/com/example/widget/PenWidgetSurfaceRouterTest.kt` (new)

### Steps

**1.** New file `PenWidgetSurfaceRouter.kt`:

```kotlin
package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.example.widget.multi.MultiCartUpdater
import com.example.widget.multi.MultiCartWidgetProvider

/**
 * Sends a commit-driven refresh to whichever surface owns [appWidgetId]. The
 * pen widget, the multi-cart widget, and the Quick Settings tile share one
 * per-id DataStore, so the commit coordinator needs one place that knows which
 * of them to redraw. Adding a surface means adding a branch here and nowhere
 * else.
 */
object PenWidgetSurfaceRouter {
    suspend fun refresh(context: Context, appWidgetId: Int) {
        if (appWidgetId < 0) return
        val appContext = context.applicationContext
        if (appWidgetId == PEN_TILE_WIDGET_ID) {
            PenQuickTileService.requestRefresh(appContext)
            return
        }
        if (ownedBy(appContext, MultiCartWidgetProvider::class.java, appWidgetId)) {
            MultiCartUpdater.update(appContext, appWidgetId)
            return
        }
        PenWidgetUpdater.update(appContext, appWidgetId)
    }

    private fun ownedBy(context: Context, provider: Class<*>, appWidgetId: Int): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, provider))
            .contains(appWidgetId)
}
```

**2.** Delete both early-return blocks from `PenWidgetUpdater.update`, restoring
its original first line `if (appWidgetId < 0) return`. Delete
`MultiCartUpdater.ownsAppWidgetId`, which becomes unused.

**3.** In `PenWidgetCommitCoordinator.forContext`, change

```kotlin
updateWidget = PenWidgetUpdater::update,
```

to

```kotlin
updateWidget = PenWidgetSurfaceRouter::refresh,
```

`PenWidgetCommitCoordinator`'s constructor parameter is already typed
`suspend (Context, Int) -> Unit`, so the signature matches with no other change.

**4.** Check the other construction site: `PenWidgetRuntime`'s `commitTimer`
calls `PenWidgetUpdater.update` in its `renderSaving` lambda. Change that to
`PenWidgetSurfaceRouter.refresh` too, or the "Saving…" frame renders on the wrong
surface for a tile or multi-cart commit.

### Traps

- **Check for other `PenWidgetUpdater::update` references before you finish.**
  `grep -rn "PenWidgetUpdater.update\|PenWidgetUpdater::update" app/src/main`
  and route every commit-driven one. Leave `PenWidgetUpdater.updateAll` alone —
  it is the pen widget's own bulk refresh and is correct.
- **Do not put the router in `com.example.widget.multi`.** It must sit in the
  parent package or it recreates the inverted dependency in the other direction.
- The tile branch must come **first**. `PEN_TILE_WIDGET_ID` is `Int.MAX_VALUE`;
  passing it to `getAppWidgetIds(...).contains(...)` is harmless but pointless,
  and ordering it first avoids the AppWidgetManager call entirely for tile
  commits.

### Tests

`PenWidgetSurfaceRouterTest.kt` cannot easily fake `AppWidgetManager`. Test the
part that is pure: extract the decision into an internal function taking the
owned-id set, and test that.

```kotlin
internal enum class PenWidgetSurface { TILE, MULTI_CART, PEN }

internal fun resolveSurface(appWidgetId: Int, multiCartIds: IntArray): PenWidgetSurface
```

- `tileIdResolvesToTheTile()`
- `multiCartIdResolvesToMultiCart()`
- `unknownIdResolvesToThePenWidget()`
- `tileIdWinsEvenIfItAppearsInTheMultiCartIds()`

### Subagents

Do not use any.

### Delivery

- BRANCH: `agent/widget-surface-router`
- COMMIT SUBJECT: `refactor: route commit refreshes through a single widget surface router`
- This is a refactor with no user-visible change. Say so, and state that the
  emulator check was a regression pass: log from the pen widget, the multi-cart
  widget, and the tile, and confirm each redraws.

---

## 12. PR C8 — Consistency cleanups

**Severity: Low. One coherent change: make three duplicated definitions agree.**

### L3 — Two definitions of "stuck"

`PenWidgetDataSource.loadWidgetData` requires a non-empty queue:

```kotlin
val queueStuck = pendingActionCount > 0 && queueNonEmptySince?.let { … } == true
```

`buildSyncStatusUiModel` does not:

```kotlin
stuck = queueNonEmptySinceEpochMillis?.let { since ->
    nowMillis >= since && nowMillis - since >= QUEUE_STUCK_THRESHOLD_MILLIS
} == true
```

So if `queueNonEmptySinceEpochMillis` is ever left set while the queue is empty,
the sync widget says "Sync is behind" while the pen widget says nothing.

**Fix:** add one shared predicate next to the constant it uses, in
`app/src/main/java/com/example/data/sync/QueueHealth.kt`:

```kotlin
/** The single definition of a stuck queue, shared by every surface that shows one. */
fun isQueueStuck(
    pendingActionCount: Int,
    queueNonEmptySinceEpochMillis: Long?,
    nowMillis: Long,
): Boolean {
    if (pendingActionCount <= 0) return false
    val since = queueNonEmptySinceEpochMillis ?: return false
    if (nowMillis < since) return false
    return nowMillis - since >= QUEUE_STUCK_THRESHOLD_MILLIS
}
```

Call it from both places. `buildSyncStatusUiModel` already receives
`pendingCount`, so it needs no new parameter.

### L4 — The Today streak caps at ten days

`TodayUpdater.HISTORY_LOOKBACK_MILLIS` is 10 days, and `buildTodayUiModel` walks
back through `loggedDates` built from that window. A 30-day streak renders as
`Streak: 10 days` with no indication it was truncated.

**Fix:** raise the lookback to 90 days
(`90L * 24L * 60L * 60L * 1000L`) and add a `@VisibleForTesting` constant so the
test can assert the bound. The table holds one small row per log; ninety days is
still trivial. Add a comment saying the streak is bounded by this window and why
that bound was chosen.

Do **not** remove the bound entirely — an unbounded query on a table that only
grows is the kind of thing that is fine for two years and then is not.

### L5 — `STATUS_ACTIVE` defined three times

`private const val STATUS_ACTIVE = "ACTIVE"` now appears in
`app/src/main/java/com/example/domain/InventoryRunway.kt:462`,
`app/src/main/java/com/example/ui/RunwayPresentation.kt:233`, and
`app/src/main/java/com/example/widget/projection/ProjectionUiModel.kt:162`.

**Fix:** make the one in `InventoryRunway.kt` internal and non-private:

```kotlin
/** Analytics-side status code for an active product. Distinct from ProductStatus.ACTIVE, which is a local Int code. */
internal const val ANALYTICS_STATUS_ACTIVE = "ACTIVE"
```

and delete the other two, importing it instead. `com.example.domain` is already
depended on by both `com.example.ui` and `com.example.widget.projection`, so no
new dependency direction is created — verify that claim before you rely on it.

### Traps

- **Do not unify `ANALYTICS_STATUS_ACTIVE` with `ProductStatus.ACTIVE`.** They
  are different things: the analytics DTO carries a `String` status, the Room
  entity an `Int` code whose label happens to be `"Active"`. Conflating them
  would silently break both.
- Changing the lookback window changes what `TodayUiModelTest` sees only if the
  test builds entries older than 10 days — check before assuming it is unaffected.

### Tests

- `QueueHealthTest` (or wherever `QUEUE_STUCK_THRESHOLD_MILLIS` is covered):
  `emptyQueueIsNeverStuck()`, `nonEmptyQueueBelowTheThresholdIsNotStuck()`,
  `nonEmptyQueueAtTheThresholdIsStuck()`, `clockRollbackIsNotStuck()`.
- `SyncStatusUiModelTest`: `zeroPendingIsNeverStuck()` — the regression this fixes.
- `TodayUiModelTest`: `streakCountsBeyondTenDays()`.

### Subagents

Do not use any. Three small changes across shared files.

### Delivery

- BRANCH: `agent/widget-consistency-cleanups`
- COMMIT SUBJECT: `fix: unify the stuck-queue definition and widen the today streak window`

---

## 13. PR C9 — Version bump and documentation for v1.5.1

Do this **after C1-C8 are all merged**.

### Steps

**1.** `app/build.gradle.kts`, lines 39-40 — exactly two values:

```kotlin
versionCode = 43
versionName = "1.5.1"
```

**2.** `docs/PROJECT_STATE.md` — add a v1.5.1 section recording what was fixed,
in the style of the existing per-release sections. State plainly that the pen
widget's configuration activity was unreachable in v1.4.5 and v1.5.0, since
anyone reading the v1.4.5 notes will otherwise believe the feature worked.

**3.** `docs/DECISIONS.md` — the next free number is **ADR-042**; check the file
rather than trusting this. Two decisions here are durable and worth recording:

- The `android:configure` attribute is a bare class name, not a flattened
  ComponentName, and there is now a test that enforces it.
- Per-instance widget configuration lives in its own DataStore file so it can be
  backed up while commit payloads cannot.

**4.** `docs/HANDOFF.md` — replace the current-release section. Record the merged
PRs with their squashed SHAs, the `main` run id that proved the tagged SHA with
all six jobs green, the tag, the published asset names, the APK SHA-256, and the
signing-certificate comparison. The last four only exist after §14 step 5, so
write what you know now and amend in the follow-up docs PR, exactly as
`docs: record v1.5.0 publication verification (#127)` did.

### Delivery

- BRANCH: `agent/release-v1-5-1`
- COMMIT SUBJECT: `Bump version to 1.5.1 (versionCode 43)`
- Note the style: version bumps do **not** take a conventional-commit prefix.

---

## 14. Release runbook — v1.5.1

Identical to `docs/WIDGET_EXPANSION_PLAN.md` §8. Restated because it is the part
most often skipped.

### The gate

`release-apk.yml` refuses to publish unless the **exact tagged commit SHA** has a
completed, successful, `push`-event run on `main` of **"Cannsheet PR checks"** in
which all six jobs individually succeeded:

```
Classify changes and scan repository
Backend validation
Android static validation
Emulator API 24
Emulator API 36
Cannsheet Android PR validation
```

A green pull-request check is **not** proof — PRs run only API 24. And
back-to-back merges cancel each other's main runs, and a cancelled run is not a
success. Land everything, then wait for the final `main` SHA to go green, then
tag that SHA.

### Steps

```bash
git switch main && git pull --ff-only && git log --oneline -3
git rev-parse HEAD
gh run list --branch main --limit 5 --json databaseId,headSha,event,conclusion
```

Find the run whose `headSha` equals `HEAD` and whose `event` is `push`. If it is
`cancelled`, `failure`, or absent:

```bash
gh run rerun <run-id>
gh run watch <run-id> --exit-status
```

Confirm all six jobs:

```bash
gh run view <run-id> --json jobs --jq '.jobs[] | "\(.conclusion)\t\(.name)"'
```

Every line must read `success`.

**Ask the repository owner to confirm before pushing the tag.** Publication is
public and irreversible.

```bash
git tag -a v1.5.1 -m "Cannsheet Mobile 1.5.1"
git push origin v1.5.1
gh run list --workflow=release-apk.yml --limit 1
gh run watch <run-id> --exit-status
```

Verify independently:

```bash
gh release view v1.5.1 --repo noamvb/cannsheet-mobile-releases --json tagName,publishedAt,assets
gh release download v1.5.1 --repo noamvb/cannsheet-mobile-releases --dir /tmp/verify-1.5.1
cd /tmp/verify-1.5.1 && shasum -a 256 -c Cannsheet-Mobile-1.5.1.apk.sha256
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging Cannsheet-Mobile-1.5.1.apk | head -3
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs Cannsheet-Mobile-1.5.1.apk
```

Expect two assets, package `com.noamv.cannsheet.mobile`, versionCode 43,
versionName 1.5.1, minSdk 24, targetSdk 36, APK Signature Scheme v2. **Compare
the certificate digest against v1.5.0.** A changed certificate makes in-place
update impossible and would force an uninstall, destroying the Room database and
every pending offline queue row. If it differs, stop and tell the owner.

Then open the provenance PR (`docs: record the v1.5.1 release handoff and
publication verification`) and tell the owner:

> v1.5.1 is published. Open Obtainium, pull to refresh or tap **Check for
> updates**, and install Cannsheet Mobile 1.5.1.

Never install anything on the phone yourself.

---

## 15. Hard prohibitions

- Do not commit to `main`.
- Do not change `versionCode`, `versionName`, signing config, tags, or releases
  outside C9 and §14.
- Do not change the production Apps Script endpoint, the application ID, the
  package namespace, environment IDs, credentials, or secrets.
- Do not change the value of `EXTRA_START_ROUTE`.
- Do not revert `PenWidgetConfigureActivity`'s `android:exported="true"`.
- Do not exclude `pen_widget_config` from backup, or include `pen_widget_state`.
- Do not use `fallbackToDestructiveMigration`.
- Do not install a debug APK on the physical phone.
- Do not overwrite or delete a published release.
- Do not report a check you did not run as passing.

## 16. Definition of done

1. C1-C9 are all merged into `main`.
2. v1.5.1 is published to `noamvb/cannsheet-mobile-releases` with two assets,
   verified per §14, with a signing certificate matching v1.5.0.
3. `lintDebug` reports `HardcodedText` down from 20 to 6 and `PluralsCandidate`
   at 0, with the counts recorded in the C6 pull request.
4. The pen widget's configuration screen has been opened from the launcher picker
   on a real emulator and that result is recorded in the C1 pull request.
5. `docs/PROJECT_STATE.md`, `docs/DECISIONS.md`, and `docs/HANDOFF.md` reflect
   the shipped state, including the note that the configuration activity was
   unreachable in v1.4.5 and v1.5.0.
6. The repository owner has been told to update through Obtainium.

If any statement in this plan turns out not to match the codebase, **stop and
report it** rather than writing something plausible. One finding in this plan
exists precisely because the previous plan asserted a format that was never
verified against a running device.
