# Cannsheet Mobile v1.3 feature and implementation plan

Status: accepted implementation record. Source steps 5.1–5.7 are implemented
and merged; step 5.8 records the resulting state. The separate version, signed
publication, artifact-verification, and installation steps 5.9–5.10 remain
pending. Exact commits and CI evidence are maintained in
`docs/PROJECT_STATE.md`; no physical v1.3 walkthrough is claimed.

Base commit: `82e3c75` (`main`, released state v1.2.27, version code `30`).

Implementation note: repository evidence corrected several proposal details
while the sequence was executed. In particular, queue-alert delivery now uses
an exact persisted claim token and a no-network delayed check; runway evidence
is basis-specific and uses product-specific civil-date windows; spend pace is
restricted to a true current-month snapshot; width is classified once before
the rail; and History correction state remains parent-owned and saveable. The
code and current architecture documents are authoritative for those details.

## 0. How to use this document

Read sections 1–3 to decide *what* v1.3 should be. Read sections 4–13 to
implement it. Section 5 is the literal delivery sequence; do not reorder it,
do not merge two of its pull requests together, and do not skip its
validation steps.

Every file path in this document is repository-relative. Kotlin signatures
were literal proposal targets unless an implemented-correction note now marks
them as design history; current source and ADRs are authoritative after merge.
Where a rule exists to prevent a specific failure, the failure is named. Where
a decision has a cost, the cost is stated instead of hidden.

If you are implementing this and something in the repository contradicts this
document, the repository wins. Verify the behaviour and correct this document
in the same pull request, per `AGENTS.md`.

## 1. Method and evidence boundary

What this plan is based on:

- Full read of `docs/PROJECT_STATE.md`, `docs/ARCHITECTURE.md`,
  `docs/HANDOFF.md`, `docs/DECISIONS.md` (ADR-001 through ADR-015 headings),
  and `AGENTS.md`.
- Full or targeted read of the Android source under
  `app/src/main/java/com/example`, including every file in `ui`, `data`,
  `data/sync`, `domain`, and `widget`.
- `app/build.gradle.kts`, `gradle/libs.versions.toml`,
  `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`,
  `app/src/main/res/xml/backup_rules.xml`,
  `app/src/main/res/xml/data_extraction_rules.xml`.
- `.github/workflows/android-pr-checks.yml` job graph and classification
  logic.
- Targeted read of `backend_additions.gs` for the analytics product
  projection (status labels, type normalization, grams validation).

What this plan is **not** based on, and must not be described as:

- No build, unit test, lint run, instrumentation run, or APK assembly was
  performed for this proposal. The implementing session's environment had
  **JDK 1.8 only, no `JAVA_HOME`, no Android SDK, no `local.properties`, and
  no `node` on `PATH`**. `./gradlew` cannot run here, and neither can the
  Node backend suites. See section 9 for how to handle this.
- No device, emulator, or physical Samsung Fold was used.
- No live Apps Script deployment or spreadsheet state was inspected.

## 2. Where v1.2.27 actually leaves the app

Understanding the gaps requires naming what already exists, because the
strongest v1.3 candidates are the ones the current architecture almost
supports.

Already delivered and solid:

- Local-first purchase / consumption / finish / correction queues with
  immutable UUIDs, acknowledgement-only deletion, and a single `SyncEngine`
  under `CannsheetGraph.syncMutex`.
- Background sync (`SyncScheduler`, `SyncWorker`, `BackgroundSyncRunner`)
  with a DataStore kill switch and analytics prefetch.
- A rich versioned analytics contract: `InsightsResponseDto` already carries
  daily activity, weekday and hour histograms, inventory counts, per-type
  breakdowns, per-product all-time and in-range activity, monthly spending,
  sync health, data-quality warnings, and a source revision hash.
- Append-only History corrections with a full audit chain.
- A hardened `RemoteViews` pen widget with claim/write/complete delivery.

The four real gaps, each verifiable in source:

**G1 — The app can never tell the user that its local data is stuck.**
`app/src/main/AndroidManifest.xml` declares only `INTERNET` and
`ACCESS_NETWORK_STATE`. There is no notification channel, no
`POST_NOTIFICATIONS`, and no user-visible signal outside the app. When
`SyncWorker` exhausts its five retries it calls
`recordMeaningfulResult(BackgroundSyncResult.RETRY_EXHAUSTED)` and returns
`Result.success()` (`app/src/main/java/com/example/data/sync/SyncWorker.kt`
lines 65–72). The queued rows stay on the phone indefinitely and the only
surface that says so is a badge on the Settings tab and a line of text inside
Settings. For an app whose entire value proposition is "your data reaches the
sheet", this is the largest functional gap in the product.

**G2 — The app knows everything needed to forecast, and forecasts nothing.**
`AnalyticsProductDto` already exposes `allTime.quantity`, `range.quantity`,
`grams`, `status`, `costPerLogToDateCents`, and `daysSinceLastLog` for every
product, including finished ones. Nothing in `InsightsScreen.kt` projects
forward. The user can see that a cart has 51 uses on it but not that their
last seven finished carts averaged 84, nor that at their current rate this one
has about five days left. This is computable entirely client-side from the
existing payload — **no backend, wire, Room, or queue change at all.**

**G3 — The app is phone-shaped on a device that is not a phone.**
`docs/PROJECT_STATE.md` records the target device as a Samsung Fold running
API 36, and `docs/DECISIONS.md` ADR-015 records measured launcher heights of
`300dp` cover portrait, `274dp` main portrait, `259dp` main landscape. The app
itself is fixed single-pane with a four-item `NavigationBar`
(`app/src/main/java/com/example/ui/AppNavigation.kt` lines 70–108) at every
width. On the unfolded inner display the Insights list renders in one narrow
column with the rest of the screen empty. There is also a concrete state bug:
`app/src/main/java/com/example/ui/InsightsScreen.kt:142` holds the open
product sheet in a plain `remember`, so unfolding, folding, or rotating closes
it — while the History sheet three hundred lines below deliberately uses
`rememberSaveable` and documents why ("Hold the identity, not the snapshot",
`InsightsScreen.kt:389–391`).

**G4 — There is no way to get data out of the phone.**
Every read path terminates in the UI. There is no export, no share, no
diagnostic bundle. If Apps Script or the spreadsheet becomes unavailable, the
locally cached History and the pending queue are unreachable by any means the
user controls.

There is also one recorded hygiene item worth closing in this release:
`docs/PROJECT_STATE.md` "Known limitations" states that
`app/src/main/res/xml/backup_rules.xml` and
`app/src/main/res/xml/data_extraction_rules.xml` "remain sample/template
rules; the latter contains a backup-selection TODO". Today `allowBackup` is
`true` and the Room queue is therefore inside cloud backup by default,
without anyone having decided that it should be.

## 3. Scope decision for v1.3

### 3.1 Recommended scope

| ID | Feature | Why it earns a minor version | Size | Data risk |
|----|---------|------------------------------|------|-----------|
| F0 | Deliberate backup and data-extraction rules | Closes a recorded known limitation; makes an accidental default an explicit decision | XS | Medium — see 4.1 |
| F1 | Queue integrity alerts | Closes G1, the only gap that can cost the user real data | M | Low |
| F2 | Inventory runway and spend run rate | Closes G2; the headline user-visible feature; zero contract change | M | None |
| F3 | Adaptive layout for large screens and foldables | Closes G3 on the actual target device; fixes a real state bug | M–L | None |

F0 through F3 are the recommended v1.3. They are independent: any one can be
dropped without blocking the others.

### 3.2 Specified but deferred

| ID | Feature | Recommendation |
|----|---------|----------------|
| F4 | Local export via the Storage Access Framework | Fully specified in 4.5. **Drop this first** if v1.3 needs to be smaller. Ship as v1.3.1. |

### 3.3 Considered and deliberately not proposed

These are recorded so the same ideas are not re-litigated next release.

- **A second widget, or widgets for other product types.** Incremental over
  v1.2.27's widget rather than new capability, and the widget surface still
  has zero physical-device action evidence
  (`docs/PROJECT_STATE.md`, "Unresolved questions"). Adding surfaces before
  validating the existing one compounds unverified risk.
- **Quick Settings tile and launcher shortcuts for pen logging.** Genuinely
  attractive, but every new commit entry point must be arbitrated against the
  widget's DataStore claim state (ADR-014) or it can produce a second
  in-flight submission for the same intent. That arbitration deserves its own
  release, not a corner of this one.
- **Consumption goals, limits, or tolerance-break tracking.** Technically
  straightforward. Not proposed because it changes the app from a neutral
  ledger into something that scores the user's behaviour, and that is a
  product decision for the owner to make explicitly rather than something to
  slip into a feature plan. If wanted, it should be a purely factual counter
  ("14 of your set 20 uses this week") with no judgement language, and it
  should be its own release.
- **Room `exportSchema = true` plus generated-schema migration tests.** Real
  technical debt (`app/src/main/java/com/example/data/Database.kt:385`), but
  it is maintenance, not a feature, and v1.3 introduces no schema change that
  makes it urgent. Log it for a maintenance release.
- **A user-facing theme picker.** `MyApplicationTheme` hardcodes
  `dynamicColor = true` (`app/src/main/java/com/example/ui/theme/Theme.kt:37`).
  Low value for a single-user app.
- **Any Room schema change.** v1.3 deliberately introduces none. Every
  feature below is implementable without a migration, and section 4.2.2
  explains where a schema change was considered and rejected in favour of a
  DataStore watermark.

### 3.4 What v1.3 explicitly does not touch

Stated once, and repeated in each feature's data-safety notes:

- No change to `versionCode` / `versionName` in any feature pull request.
  Only the version-only pull request in step 5.9 changes them.
- No change to the Room schema, Room version, or migrations.
- No change to the offline queue payloads, acknowledgement rules, request-ID
  handling, or `SyncEngine`.
- No change to `backend_additions.gs`, `appsscript.json`, the Apps Script
  contract, `ANALYTICS_VERSION`, the production endpoint, the application ID,
  the package namespace, or signing configuration.
- No new third-party dependency. F3 is specifically designed to avoid pulling
  in `material3-window-size-class` or `material3-adaptive`; see 4.4.1.

---

## 4. Feature specifications

### 4.1 F0 — Deliberate backup and data-extraction rules

#### 4.1.1 Problem

`android:allowBackup="true"` with template rules means Android's cloud backup
currently includes the Room database `cannsheet_db`, which holds the pending
purchase / consumption / finish / correction queues and `sync_request_state`.
Nobody decided that. The only deliberate line in either file excludes
`datastore/pen_widget_state.preferences_pb`.

#### 4.1.2 The actual hazard

Restoring a stale backup onto a fresh install restores:

1. **Queue rows whose server acknowledgement already happened.** The protocol
   is idempotent by immutable UUID, so the backend answers `duplicate` and
   `SyncQueueLogic` deletes them. This case is safe.
2. **`sync_request_state`**, which carries a persisted `requestId` and a
   `payloadFingerprint`. A restored request ID that the server has already
   audited, paired with a queue that no longer matches the fingerprint, is
   exactly the input `SyncEngine` guards with `RequestIdMismatch`
   (`app/src/main/java/com/example/data/SyncEngine.kt:168–173`). That is a
   retry loop, not data loss, but it is a wedge state a user cannot diagnose.
3. **`analytics_cache`**, which would present another point in time as the
   "cached" snapshot with a `sourceDataVersion` that no longer matches
   anything. `AnalyticsCoordinator` would show it as cache-backed data.

Against that: excluding the queue means a phone lost while holding unsynced
actions loses them. That window is genuinely small — `SyncScheduler`
enqueues connected immediate work on every action, and cloud backup only runs
roughly daily on unmetered power while idle, so the backup would usually be
stale anyway.

#### 4.1.3 Decision

Exclude the Room database, `sync_preferences`, and `pen_widget_state` from
both cloud backup and device transfer. Keep `consumption_preferences` and
`purchase_defaults`, which are pure user settings with no synchronization
identity and real restore value.

Both files must be changed together and kept identical in intent:
`fullBackupContent` governs API 24–30, `dataExtractionRules` governs API 31+,
and this project's `minSdk` is 24 so both are live.

#### 4.1.4 Exact content

`app/src/main/res/xml/backup_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    Cannsheet Mobile backup policy (API 24-30).

    Included: user settings only. consumption_preferences and
    purchase_defaults carry no synchronization identity and are safe to
    restore onto any install.

    Excluded: everything that participates in queue synchronization or
    represents a point-in-time server snapshot. Restoring cannsheet_db would
    reintroduce a sync_request_state requestId/payloadFingerprint pair the
    server may already have audited, and an analytics_cache row whose
    sourceDataVersion no longer describes any real server state.

    Keep this file and res/xml/data_extraction_rules.xml in agreement.
-->
<full-backup-content>
    <exclude domain="database" path="cannsheet_db" />
    <exclude domain="database" path="cannsheet_db-wal" />
    <exclude domain="database" path="cannsheet_db-shm" />
    <exclude domain="file" path="datastore/sync_preferences.preferences_pb" />
    <exclude domain="file" path="datastore/pen_widget_state.preferences_pb" />
</full-backup-content>
```

`app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    Cannsheet Mobile backup policy (API 31+). See res/xml/backup_rules.xml
    for the rationale; the two files must stay in agreement.
-->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" path="cannsheet_db" />
        <exclude domain="database" path="cannsheet_db-wal" />
        <exclude domain="database" path="cannsheet_db-shm" />
        <exclude domain="file" path="datastore/sync_preferences.preferences_pb" />
        <exclude domain="file" path="datastore/pen_widget_state.preferences_pb" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="database" path="cannsheet_db" />
        <exclude domain="database" path="cannsheet_db-wal" />
        <exclude domain="database" path="cannsheet_db-shm" />
        <exclude domain="file" path="datastore/sync_preferences.preferences_pb" />
        <exclude domain="file" path="datastore/pen_widget_state.preferences_pb" />
    </device-transfer>
</data-extraction-rules>
```

The Room database name is `cannsheet_db`, confirmed at
`app/src/main/java/com/example/data/CannsheetGraph.kt:31`. The DataStore file
names are `sync_preferences`
(`app/src/main/java/com/example/data/SyncPreferencesRepository.kt:14`),
`consumption_preferences`
(`app/src/main/java/com/example/data/ConsumptionPreferencesRepository.kt:20`),
and `purchase_defaults`
(`app/src/main/java/com/example/data/PurchaseDefaultsRepository.kt`).
Preferences DataStore files live at `files/datastore/<name>.preferences_pb`,
hence `domain="file"`.

#### 4.1.5 Validation

Automated coverage is not meaningful for backup XML; state that plainly
rather than inventing a test. The check is manual and device-bound:

```bash
adb shell bmgr backupnow com.noamv.cannsheet.mobile
```

then inspect `adb logcat -d | grep -i backup`. Do **not** perform a restore
onto the production package during validation.

If no device session is performed, the pull request must say so in the exact
words "no on-device backup verification was performed".

---

### 4.2 F1 — Queue integrity alerts

> Implemented correction: delivery was hardened beyond the proposal APIs in
> this section. The merged design atomically claims an alert with a persisted
> UUID before presentation, conditionally completes or releases that exact
> claim, serializes all stable-notification side effects through one graph-owned
> coordinator, scopes terminal results to the current queue episode, and uses a
> separate unconstrained delayed worker for both the 24-hour threshold and
> repeat deadline. The presenter reports known post failure and never receives
> queue rows or entry details. `QueueHealth.kt`,
> `QueueAlertDeliveryCoordinator.kt`, `QueueAlertScheduler.kt`, and ADR-016 are
> authoritative; the earlier signatures below remain proposal history.

#### 4.2.1 Goal

Tell the user, outside the app, exactly once per condition, when their local
data has stopped reaching the spreadsheet — and never at any other time.

The design constraint that matters most: **a notification that fires for
ordinary offline moments will be turned off within a week, and then it will
not fire for the real event either.** Every rule below exists to protect the
signal.

#### 4.2.2 Why no Room column

Detecting "the queue has not drained in a day" needs an enqueue time. None of
`PurchaseAction`, `ConsumptionAction`, `FinishAction`, or
`PendingConsumptionCorrection` stores one
(`app/src/main/java/com/example/data/Database.kt:33–93`).

Two options were weighed:

- **Add `queuedAtEpochMillis` via Room migration 10→11.** Gives exact per-row
  age and would enable a future queue inspector. Costs a schema change, a
  forward migration, migration tests from every supported prior version, and
  a `NOT NULL DEFAULT` decision for pre-existing rows whose real age is
  unknown.
- **Track a watermark in the existing `sync_preferences` DataStore.** Records
  when the queue last transitioned from empty to non-empty. Sufficient for
  "the queue has been non-empty for N hours". No schema change, no migration,
  no user-data risk.

**Decision: the DataStore watermark.** v1.3 introduces no schema change. The
per-row alternative is recorded in the ADR so a future queue inspector knows
what it costs.

#### 4.2.3 New pure-domain module

New file `app/src/main/java/com/example/data/sync/QueueHealth.kt`:

```kotlin
package com.example.data.sync

import com.example.data.BackgroundSyncResult

/** How long a non-empty queue may sit before it is worth interrupting the user. */
const val QUEUE_STUCK_THRESHOLD_MILLIS: Long = 24L * 60L * 60L * 1000L

/** How long the same reason stays suppressed after it has been shown once. */
const val QUEUE_ALERT_REPEAT_MILLIS: Long = 24L * 60L * 60L * 1000L

/** The complete set of conditions Cannsheet will interrupt the user about. */
enum class QueueAlertReason {
    ENVIRONMENT_MISMATCH,
    PARTIAL_REJECTIONS,
    BACKEND_CAPABILITY_PENDING,
    STUCK_QUEUE,
}

data class QueueHealthSnapshot(
    val pendingActionCount: Int,
    val queueNonEmptySinceEpochMillis: Long?,
    val lastResult: BackgroundSyncResult?,
    val backgroundSyncEnabled: Boolean,
    val alertsEnabled: Boolean,
    val lastAlertReason: QueueAlertReason?,
    val lastAlertAtEpochMillis: Long?,
)

data class QueueAlert(
    val reason: QueueAlertReason,
    val pendingActionCount: Int,
    val queueAgeMillis: Long,
)

/**
 * The one decision function. Pure so the whole truth table is JVM-testable
 * without WorkManager, DataStore, or a device.
 */
fun evaluateQueueHealth(
    snapshot: QueueHealthSnapshot,
    nowEpochMillis: Long,
): QueueAlert?
```

Implementation rules, in evaluation order. Each is a separate JVM test case.

1. `!snapshot.alertsEnabled` → `null`. The user's switch wins over everything.
2. `!snapshot.backgroundSyncEnabled` → `null`. Background sync is already a
   documented kill switch (`docs/ARCHITECTURE.md`, "Background queue
   synchronization"). If the user turned delivery off, a queue that does not
   drain is the expected outcome, not an anomaly.
3. `snapshot.pendingActionCount <= 0` → `null`. Nothing local is at risk.
4. `lastResult == BackgroundSyncResult.ENVIRONMENT_MISMATCH` →
   `QueueAlertReason.ENVIRONMENT_MISMATCH`. Highest priority: the app and
   backend disagree about environment and no retry can fix it.
5. `lastResult == BackgroundSyncResult.PARTIAL_REJECTIONS` →
   `QueueAlertReason.PARTIAL_REJECTIONS`. The server refused specific items;
   they will never drain without the user looking.
6. `lastResult == BackgroundSyncResult.BACKEND_CAPABILITY_PENDING` →
   `QueueAlertReason.BACKEND_CAPABILITY_PENDING`. The deployment predates a
   capability the queued action needs.
7. Otherwise, compute the queue age:
   - `queueNonEmptySinceEpochMillis == null` → `null`.
   - `nowEpochMillis < queueNonEmptySinceEpochMillis` → `null`. **Backwards
     clock.** Mirrors the widget's backwards-clock rule (ADR-014). The caller
     is responsible for resetting the watermark; see rule 10.
   - `nowEpochMillis - since >= QUEUE_STUCK_THRESHOLD_MILLIS` →
     `QueueAlertReason.STUCK_QUEUE`, else `null`.
8. `BackgroundSyncResult.RETRY_EXHAUSTED` deliberately does **not** trigger on
   its own. `SyncWorker` exhausts five exponential retries from a 30-second
   base, which a three-minute tunnel can produce. It is covered by
   `STUCK_QUEUE` once the queue is genuinely a day old. Write this reasoning
   as a code comment; a future reader will otherwise "fix" it.
9. `BackgroundSyncResult.SUCCESS` and `COMPLETED_WITHOUT_ACK` never trigger on
   their own; they fall through to the age rule.
10. Repeat suppression, applied last, before returning a non-null alert: if
    `lastAlertReason == candidate.reason` and `lastAlertAtEpochMillis != null`
    and `nowEpochMillis - lastAlertAtEpochMillis < QUEUE_ALERT_REPEAT_MILLIS`
    and `nowEpochMillis >= lastAlertAtEpochMillis`, return `null`. A
    *different* reason is never suppressed — an escalation must get through
    immediately. A backwards clock (`now < lastAlertAt`) does not suppress.

Every branch must return a value; do not throw. `evaluateQueueHealth` must
never touch `System.currentTimeMillis()` — the clock is a parameter, exactly
as `PenWidgetStateRepository.undo` threads an injectable clock (ADR-014
follow-up, `docs/PROJECT_STATE.md`, "Pen widget follow-up implementation").

#### 4.2.4 Watermark persistence

Extend `app/src/main/java/com/example/data/SyncPreferencesRepository.kt`.
Add to the internal key block at the top of the file:

```kotlin
internal const val QUEUE_ALERTS_ENABLED_KEY = "queue_alerts_enabled"
internal const val QUEUE_NON_EMPTY_SINCE_EPOCH_MILLIS_KEY =
    "queue_non_empty_since_epoch_millis"
internal const val LAST_QUEUE_ALERT_REASON_KEY = "last_queue_alert_reason"
internal const val LAST_QUEUE_ALERT_AT_EPOCH_MILLIS_KEY =
    "last_queue_alert_at_epoch_millis"
```

Extend `SyncPreferences` with four fields, all defaulted so existing stored
data decodes unchanged:

```kotlin
data class SyncPreferences(
    val enabled: Boolean = true,
    val lastMeaningfulSyncAtEpochMillis: Long? = null,
    val lastResult: BackgroundSyncResult? = null,
    val queueAlertsEnabled: Boolean = false,
    val queueNonEmptySinceEpochMillis: Long? = null,
    val lastQueueAlertReason: QueueAlertReason? = null,
    val lastQueueAlertAtEpochMillis: Long? = null,
)
```

`queueAlertsEnabled` defaults to **`false`**. Notifications are opt-in: on
API 33+ they require a runtime permission the app has never asked for, and
defaulting to on would either silently do nothing or produce a surprise
permission prompt.

Decode `lastQueueAlertReason` with the same defensive pattern the file
already uses for `lastResult`:
`QueueAlertReason.entries.firstOrNull { it.name == storedReason }`. An
unrecognized stored value must yield `null`, never throw.

New methods:

```kotlin
suspend fun setQueueAlertsEnabled(enabled: Boolean)

/**
 * Records the empty <-> non-empty transition. Idempotent while the queue
 * stays non-empty: overwriting an existing watermark on every observation
 * would restart the clock and STUCK_QUEUE would never fire.
 */
suspend fun observeQueueDepth(
    pendingActionCount: Int,
    nowEpochMillis: Long = System.currentTimeMillis(),
)

suspend fun recordQueueAlert(
    reason: QueueAlertReason,
    atEpochMillis: Long = System.currentTimeMillis(),
)

/** Clears alert bookkeeping without touching the enabled switch. */
suspend fun clearQueueAlertState()
```

`observeQueueDepth` behaviour, inside a single `dataStore.edit`:

- `pendingActionCount <= 0` → remove `QUEUE_NON_EMPTY_SINCE`,
  `LAST_QUEUE_ALERT_REASON`, and `LAST_QUEUE_ALERT_AT`. A drained queue is
  the resolution event; the next stuck period must start clean.
- `pendingActionCount > 0` and no stored watermark → store `nowEpochMillis`.
- `pendingActionCount > 0` and a stored watermark exists → leave it, **unless**
  `nowEpochMillis < stored` (backwards clock), in which case overwrite with
  `nowEpochMillis`. Without this the watermark can sit in the future forever
  and `STUCK_QUEUE` becomes unreachable.

#### 4.2.5 Where the watermark is updated

One central collector covers every path — foreground sync, background sync,
widget commits, and manual queueing — without each one remembering to call in.

In `app/src/main/java/com/example/CannsheetApplication.kt`, inside the
existing `applicationScope.launch { ... }` block, add a second collector:

```kotlin
applicationScope.launch {
    graph.repository.pendingActionCount
        .distinctUntilChanged()
        .collect { count -> graph.syncPreferences.observeQueueDepth(count) }
}
```

`CannsheetRepository.pendingActionCount` is already a `Flow<Int>` combining
all four queue counts (`app/src/main/java/com/example/data/Repository.kt:24`).

Because the process is not always alive, also call
`graph.syncPreferences.observeQueueDepth(...)` at the start of
`SyncWorker.doWork()` via the runtime interface. Two writers of the same
idempotent value is fine; a missed transition is not.

#### 4.2.6 Delivery boundary

`data` must not import notification/UI code. Mirror the existing
`WidgetRefresher` pattern exactly
(`app/src/main/java/com/example/data/WidgetRefresher.kt`, and
`CannsheetGraph.installWidgetRefresher`).

New file `app/src/main/java/com/example/data/sync/QueueAlertPresenter.kt`:

```kotlin
package com.example.data.sync

/** Data-facing boundary for the optional out-of-app alert surface. */
interface QueueAlertPresenter {
    fun present(alert: QueueAlert)

    fun clear()
}

internal object NoOpQueueAlertPresenter : QueueAlertPresenter {
    override fun present(alert: QueueAlert) = Unit

    override fun clear() = Unit
}
```

In `app/src/main/java/com/example/data/CannsheetGraph.kt`, add alongside the
existing widget refresher plumbing:

```kotlin
@Volatile
private var installedQueueAlertPresenter: QueueAlertPresenter =
    NoOpQueueAlertPresenter

val queueAlertPresenter: QueueAlertPresenter
    get() = installedQueueAlertPresenter

fun installQueueAlertPresenter(presenter: QueueAlertPresenter) {
    installedQueueAlertPresenter = presenter
}
```

Install it from `CannsheetApplication.onCreate()` next to
`graph.installWidgetRefresher(PenWidgetRefresher(this))`.

#### 4.2.7 Notification implementation

New file `app/src/main/java/com/example/notifications/QueueAlertNotifier.kt`.

```kotlin
package com.example.notifications

const val QUEUE_ALERT_CHANNEL_ID = "cannsheet-queue-alerts"
const val QUEUE_ALERT_NOTIFICATION_ID = 1001

class QueueAlertNotifier(context: Context) : QueueAlertPresenter {
    override fun present(alert: QueueAlert)

    override fun clear()
}
```

Non-negotiable implementation details:

- **API 24 compatibility.** `minSdk` is 24 and CI runs an API 24 emulator.
  Notification channels are API 26+. Guard channel creation with
  `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)`. Build the
  notification with `NotificationCompat.Builder(context, QUEUE_ALERT_CHANNEL_ID)` —
  the channel ID is harmlessly ignored below API 26. The API 24 emulator has
  already caught one `RemoteViews` incompatibility in this project
  (`docs/HANDOFF.md`, PR #61); assume it will catch this too.
- **`androidx.core:core-ktx` already provides `NotificationCompat` and
  `NotificationManagerCompat`.** Do not add a dependency.
- **One stable notification ID.** Reusing `QUEUE_ALERT_NOTIFICATION_ID`
  replaces rather than stacks. The user must never accumulate seven identical
  cards.
- **Channel:** name from `strings.xml`, `IMPORTANCE_DEFAULT`, description
  set, `setShowBadge(true)`. Create it lazily inside `present`, not at
  application start, so a user who never enables alerts never gets a channel
  in system settings.
- **Content intent:** `PendingIntent.getActivity` to `MainActivity` carrying
  `EXTRA_START_ROUTE = "settings"`, with flags
  `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE`, and a
  distinct `data` `Uri` (`cannsheet://queue-alert`) because `PendingIntent`
  equality ignores extras. This mirrors
  `app/src/main/java/com/example/widget/PenWidgetActions.kt:30–55` — read that
  function before writing this one.
- **`setAutoCancel(true)`**, `setOnlyAlertOnce(true)`,
  `setCategory(NotificationCompat.CATEGORY_STATUS)`,
  `setSmallIcon(R.drawable.ic_launcher_foreground)` unless a dedicated
  monochrome status icon is added.
- **`present` must be total.** Wrap the `notify` call so a
  `SecurityException` (permission revoked between check and post) cannot
  escape into `SyncWorker` and turn an already-acknowledged queue delivery
  into a failed run. This is the same hazard `SyncWorker` already guards for
  widget refresh (`SyncWorker.kt:56`, `runCatching { runtime.refreshWidgets() }`).
- **Check `NotificationManagerCompat.from(context).areNotificationsEnabled()`
  before posting.** If notifications are off at the OS level, do nothing and
  do not record the alert as shown — otherwise repeat suppression burns a
  24-hour window on a notification that was never delivered.

#### 4.2.8 Move `EXTRA_START_ROUTE` to a neutral home

The notification needs the same start-route extra the widget uses. It is
currently `app/src/main/java/com/example/widget/PenWidgetActions.kt:18`, and
`notifications` importing `widget` is the wrong direction.

Move the single constant to a new file
`app/src/main/java/com/example/domain/AppEntryPoints.kt`:

```kotlin
package com.example.domain

/**
 * The start-route extra shared by every out-of-app entry point (home-screen
 * widget, notifications). Lives in domain for the same reason the pen-state
 * helpers do: ui -> widget -> ui and notifications -> widget are both cycles
 * waiting to happen.
 */
const val EXTRA_START_ROUTE = "com.noamv.cannsheet.mobile.widget.START_ROUTE"
```

**Keep the string value byte-identical.** It appears in already-issued
`PendingIntent`s held by the launcher for installed widgets; changing it
would silently break widget taps after update.

Update the imports at these exact sites, changing nothing else:

- `app/src/main/java/com/example/MainActivity.kt:9`
- `app/src/main/java/com/example/widget/PenWidgetActions.kt` (delete the
  declaration, add the import)
- `app/src/androidTest/java/com/example/MainActivityIntentTest.kt:5`

#### 4.2.9 Worker integration

Extend `BackgroundSyncWorkerRuntime` in
`app/src/main/java/com/example/data/sync/SyncWorker.kt`:

```kotlin
suspend fun observeQueueDepth()

suspend fun evaluateAndRecordQueueAlert(): QueueAlert?

fun presentQueueAlert(alert: QueueAlert)

fun clearQueueAlert()
```

In `doWork()`:

- Call `runtime.observeQueueDepth()` **before** the `isEnabled()` early return,
  so the watermark stays accurate even while background sync is off.
- After `workerResult` is computed and after the analytics prefetch decision,
  evaluate the alert. `evaluateAndRecordQueueAlert()` reads a fresh
  `SyncPreferences` plus the live pending count, calls `evaluateQueueHealth`,
  and on a non-null result calls `recordQueueAlert` before returning it.
- If it returns non-null, `runCatching { runtime.presentQueueAlert(alert) }`.
  If the pending count is zero, `runCatching { runtime.clearQueueAlert() }`.
- **The alert step must never change `workerResult`.** Compute it, then
  return the already-decided result. Add a comment saying so.

Ordering trap: `recordQueueAlert` must happen *before* `present`, not after.
If the process dies between them the worst case is a suppressed notification;
the reverse ordering risks a notification every six hours forever.

#### 4.2.10 Settings UI

In `app/src/main/java/com/example/ui/SettingsScreen.kt`, add an "Alerts"
section immediately after the existing "Background sync" row and before the
"Sync Now" button.

Contents:

1. A `Switch` bound to `backgroundSyncPreferences.queueAlertsEnabled`,
   `testTag = QueueAlertSettingsTestTags.SWITCH`, content description
   `"Queue alerts"`.
2. A status line, `testTag = QueueAlertSettingsTestTags.STATUS`, from a new
   pure function (see below).
3. A permission-outcome line, `testTag = QueueAlertSettingsTestTags.PERMISSION`,
   shown only when the user turned the switch on and the OS refused.
4. Explanatory body text: `"Tells you if logged entries stop reaching the
   sheet. Nothing is sent anywhere; the alert is local to this phone."`

New object next to the existing `BackgroundSyncSettingsTestTags`:

```kotlin
internal object QueueAlertSettingsTestTags {
    const val SWITCH = "settings-queue-alerts-switch"
    const val STATUS = "settings-queue-alerts-status"
    const val PERMISSION = "settings-queue-alerts-permission"
}
```

New pure function in the same file, tested on the JVM exactly like the
existing `backgroundSyncLastRunText`:

```kotlin
internal fun queueAlertStatusText(
    pendingActionCount: Int,
    queueNonEmptySinceEpochMillis: Long?,
    nowEpochMillis: Long,
): String
```

Returns:

- `pendingActionCount <= 0` → `"Everything is synced."`
- watermark `null` → `"$pendingActionCount waiting to sync."`
- `nowEpochMillis < since` → `"$pendingActionCount waiting to sync."` (do not
  render a negative duration)
- otherwise → `"$pendingActionCount waiting to sync for ${elapsed}."` using
  the same minute/hour/day wording helper the file already has. Extract that
  wording into a shared private helper rather than duplicating it.

Runtime permission handling, in the composable:

```kotlin
val context = LocalContext.current
val activity = context as? Activity
val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted -> /* see below */ }
```

Flow when the switch is turned **on**:

- `Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU` → call
  `viewModel.setQueueAlertsEnabled(true)` directly. No runtime permission
  exists below API 33.
- Permission already granted
  (`ContextCompat.checkSelfPermission(...) == PackageManager.PERMISSION_GRANTED`)
  → enable directly.
- Otherwise launch `Manifest.permission.POST_NOTIFICATIONS`.
  - Granted → `setQueueAlertsEnabled(true)`.
  - Denied → **leave the preference `false`** and show the permission line:
    `"Android is blocking Cannsheet notifications. Turn them on in Android
    Settings, then try again."` A switch that reads "on" while the OS drops
    every notification is a lie; do not do it.
  - Permanently denied is not separately detectable after the fact; the same
    message covers it. Optionally use
    `activity?.shouldShowRequestPermissionRationale(...)` before launching to
    tailor the copy.

Turning the switch **off** always calls `setQueueAlertsEnabled(false)` and
`clear()` on the presenter (via the view model), with no permission
interaction.

Add to `app/src/main/java/com/example/ui/CannsheetViewModel.kt`:

```kotlin
fun setQueueAlertsEnabled(enabled: Boolean)
```

launching into `viewModelScope`, writing through
`graph.syncPreferences.setQueueAlertsEnabled(enabled)` and, when disabling,
calling `graph.queueAlertPresenter.clear()`.

#### 4.2.11 Manifest and strings

`app/src/main/AndroidManifest.xml`, alongside the two existing permissions:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

No `<service>`, no `<receiver>`, no exported component is added.

`app/src/main/res/values/strings.xml` additions. All user-visible alert copy
must be resource-backed; hardcoded widget copy was finding W-17 in
`docs/WIDGET_REVIEW_PLAN.md` and must not be repeated:

```xml
<string name="queue_alert_channel_name">Sync alerts</string>
<string name="queue_alert_channel_description">Tells you when logged entries stop reaching the sheet.</string>
<string name="queue_alert_stuck_title">Entries are waiting to sync</string>
<plurals name="queue_alert_stuck_body">
    <item quantity="one">%1$d entry has been waiting on this phone for over a day. Open Cannsheet to sync.</item>
    <item quantity="other">%1$d entries have been waiting on this phone for over a day. Open Cannsheet to sync.</item>
</plurals>
<string name="queue_alert_rejected_title">Some entries were not accepted</string>
<string name="queue_alert_rejected_body">The sheet refused part of your last sync. Open Cannsheet to review them.</string>
<string name="queue_alert_capability_title">The sheet needs an update</string>
<string name="queue_alert_capability_body">Your entries are safe on this phone, but the backend cannot accept them yet.</string>
<string name="queue_alert_environment_title">Sync setup needs attention</string>
<string name="queue_alert_environment_body">Cannsheet and the sheet disagree about which environment they are in. Nothing will sync until this is fixed.</string>
```

#### 4.2.12 Data safety

- No alert path reads, writes, or deletes a queue row. The presenter receives
  a value object and nothing else.
- No alert path touches `SyncEngine`, `syncMutex`, request IDs, or
  acknowledgement rules.
- Notification content never includes product names, quantities, dates, or
  any consumption detail — only counts and durations. A lock-screen preview
  must not disclose what the user logged.
- Turning alerts off, revoking the permission, or clearing the notification
  never mutates local data.

---

### 4.3 F2 — Inventory runway and spend run rate

#### 4.3.1 Goal

Answer two questions the data already contains and the UI has never asked:

- "How much is left in this cart, and how long will it last?"
- "At this rate, what will this month cost?"

Both are computed on the client from `InsightsResponseDto`. No backend
change, no new request, no contract version bump.

#### 4.3.2 The capacity model

The app does not know how many uses a cart holds. It can learn it: every
finished product of the same type is a completed observation of exactly that.

For each normalized product type, take every product in
`InsightsResponseDto.products` where:

- `status == "FINISHED"`, and
- `allTime.quantity` is finite and `> 0`, and
- `ProductTypeCodes.normalize(type)` equals the target type.

`AnalyticsProductDto.status` is one of `"ACTIVE"`, `"FINISHED"`,
`"UNOPENED"`, `"UNKNOWN"` and `type` is uppercased or `"UNKNOWN"` — both
confirmed in `backend_additions.gs` (status labels around line 1510, type
uppercasing at line 816).

Two bases, preferring the more informative one:

- **Per gram.** If at least `MIN_CAPACITY_SAMPLE` of the finished sample have
  `grams != null && grams > 0`, compute the median of
  `allTime.quantity / grams` across those, and estimate a target product's
  capacity as `medianUsesPerGram * product.grams` (only when the target's own
  `grams` is non-null and positive). The backend already nulls out
  non-positive grams (`backend_additions.gs:807–809`), so a non-null value is
  trustworthy.
- **Per product.** Otherwise, the median of `allTime.quantity` across the
  finished sample.

Use the **median**, never the mean. One 400-use outlier would otherwise make
every current cart look nearly empty.

#### 4.3.3 New pure-domain module

> Implemented correction: the proposal signatures and pseudocode in this
> subsection were refined before merge. Production accepts the whole
> `InsightsResponseDto` plus an injected clock for spend projection; validates
> strict civil dates and the response time zone; derives each burn window from
> the later of `range.from` or the product's first recorded use; uses
> basis-specific evidence counts; rejects unknown product types; reconstructs
> current personal spend from products; and uses `BigDecimal` with half-up
> rounding. See `InventoryRunway.kt` and ADR-018 for the authoritative
> implementation. The original proposal text below remains as design history,
> not a callable contract.

New file `app/src/main/java/com/example/domain/InventoryRunway.kt`:

```kotlin
package com.example.domain

import com.example.data.AnalyticsProductDto
import com.example.data.AnalyticsRangeDto
import com.example.data.MonthlySpendDto

/** Fewer finished products than this cannot support an honest estimate. */
const val MIN_CAPACITY_SAMPLE: Int = 3

/** A burn rate measured over fewer days than this is noise. */
const val MIN_BURN_RATE_DAYS: Int = 7

enum class RunwayBasis { PER_GRAM, PER_PRODUCT }

enum class RunwayConfidence { LOW, MEDIUM, HIGH }

data class TypeCapacityModel(
    val type: String,
    val sampleSize: Int,
    val medianUsesPerProduct: Double,
    val medianUsesPerGram: Double?,
    val perGramSampleSize: Int,
)

data class ProductRunway(
    val productId: String,
    val type: String,
    val usesSoFar: Double,
    val estimatedCapacityUses: Double,
    val estimatedRemainingUses: Double,
    val usesPerDay: Double,
    val estimatedDaysRemaining: Double,
    val basis: RunwayBasis,
    val confidence: RunwayConfidence,
    val sampleSize: Int,
)

data class SpendRunRate(
    val month: String,
    val monthToDateCents: Long,
    val elapsedDays: Int,
    val daysInMonth: Int,
    val projectedMonthEndCents: Long,
)

fun buildTypeCapacityModels(
    products: List<AnalyticsProductDto>,
): Map<String, TypeCapacityModel>

fun buildProductRunway(
    product: AnalyticsProductDto,
    models: Map<String, TypeCapacityModel>,
    range: AnalyticsRangeDto,
): ProductRunway?

fun projectCurrentMonthSpend(
    byMonth: List<MonthlySpendDto>,
    rangeEndIsoDate: String,
): SpendRunRate?

internal fun median(values: List<Double>): Double
```

`median` implementation, written out because an off-by-one here is silent:

```kotlin
internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty()) { "median requires at least one value" }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
```

`buildProductRunway` rules, in order. Any failed guard returns `null` — the UI
shows nothing rather than a wrong number:

1. `product.status != "ACTIVE"` → `null`. Unopened products have not started;
   finished ones are done. Both are rendered by the UI as plain states, not
   as runways.
2. `models[ProductTypeCodes.normalize(product.type)]` missing → `null`.
3. `product.allTime.quantity` not finite or `< 0` → `null`.
4. Capacity: if the model has `medianUsesPerGram != null` **and**
   `product.grams != null && product.grams > 0`, use
   `medianUsesPerGram * product.grams` with `RunwayBasis.PER_GRAM`; else
   `medianUsesPerProduct` with `RunwayBasis.PER_PRODUCT`.
5. Capacity not finite or `<= 0` → `null`.
6. `estimatedRemainingUses = (capacity - allTime.quantity).coerceAtLeast(0.0)`.
   A cart past the median capacity reports `0.0`, not a negative number. The
   UI renders that as "past the usual amount for this type", which is
   informative rather than broken.
7. `range.dayCount < MIN_BURN_RATE_DAYS` → `null`.
8. `usesPerDay = product.range.quantity / range.dayCount`. Use
   `range.quantity` (uses), not `range.logCount` (events) — two 5-second hits
   and one 60-second hit are not the same consumption.
9. `usesPerDay` not finite or `<= 0` → `null`. A product untouched in the
   range has no rate.
10. `estimatedDaysRemaining = estimatedRemainingUses / usesPerDay`; guard for
    finiteness one final time before constructing the result. Never emit
    `Infinity` or `NaN`.
11. Confidence:
    - `HIGH` when `basis == PER_GRAM && sampleSize >= 8`
    - `MEDIUM` when `sampleSize >= 5`
    - `LOW` otherwise

`projectCurrentMonthSpend` rules:

1. Find the entry in `byMonth` whose `month` equals the first seven characters
   of `rangeEndIsoDate` (`"YYYY-MM"`). Missing → `null`.
2. `elapsedDays` = the day-of-month component of `rangeEndIsoDate`, parsed as
   an integer from characters 8..9. Outside `1..31` → `null`.
3. `daysInMonth` computed from the year and month **arithmetically**, not with
   a `Calendar` in the device's default zone: `31` for months 1,3,5,7,8,10,12;
   `30` for 4,6,9,11; February is `29` when
   `(year % 4 == 0 && year % 100 != 0) || year % 400 == 0`, else `28`.
4. `projectedMonthEndCents =
   Math.round(monthToDateCents.toDouble() / elapsedDays * daysInMonth)`.
5. `elapsedDays >= daysInMonth` → return with
   `projectedMonthEndCents == monthToDateCents`. The month is over; do not
   extrapolate past it.

**Timezone trap — read this twice.** `rangeEndIsoDate` must be
`InsightsResponseDto.range.to`, which the backend produces in `CANN.TIME_ZONE`
(currently `America/New_York`), and `byMonth[].month` is in the same zone. Never substitute a
device-local `Calendar`, `Date`, or `LocalDate.now()`. This is the same class
of bug ADR-003 already records for `DatePicker`, and the same reason
`docs/ARCHITECTURE.md` documents version-2 date/time strings as wall-clock
values in `CANN.TIME_ZONE`. A device in a different zone must produce
identical numbers.

`buildTypeCapacityModels` must be O(n) over products plus a sort per type, and
must tolerate an empty list, an all-`UNKNOWN`-type list, and products whose
`grams` is `null`.

#### 4.3.4 View-model exposure

In `app/src/main/java/com/example/ui/CannsheetViewModel.kt`, derive runway
state from the existing Insights flow. Do not fetch anything new.

```kotlin
val runwayByProductId: StateFlow<Map<String, ProductRunway>> = insightsState
    .map { state ->
        val data = state.data
        if (data == null || state.isStale) return@map emptyMap()
        val models = buildTypeCapacityModels(data.products)
        data.products
            .mapNotNull { buildProductRunway(it, models, data.range) }
            .associateBy(ProductRunway::productId)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
```

The `state.isStale` guard is the honesty rule: the Log screen must not show a
confident "5 days left" derived from a snapshot the app already knows is out
of date. Cache-backed but non-stale data is fine and is labelled as such on
Insights.

Keyed by `AnalyticsProductDto.productId`, which is the same identifier space
as Room's `Product.id`. Both are the Purchases sheet's `Product ID` column:
the catalog GET builds `id: legacyId` from it (`backend_additions.gs:150,187`)
and the analytics product builder reads the same column
(`backend_additions.gs:750`).

**Expected misses are correct, do not "fix" them.** A pending purchase or a
borrowed product exists in Room under its temporary `tempId` until
`applyAcknowledgements` remaps it (`app/src/main/java/com/example/data/Repository.kt:159–172`).
Those IDs are not in any analytics response, so the lookup returns `null` and
the UI shows no runway for them. That is the honest answer: the backend has
never seen the product, so there is nothing to estimate from. Do not fall
back to matching on name or `productUuid`.

#### 4.3.5 UI surfaces

**Insights, new section.** In `InsightsContent`
(`app/src/main/java/com/example/ui/InsightsScreen.kt`), add a
`SectionCard("Runway")` item between the existing `"Inventory"` and
`"Uses by type"` items. Contents:

- Active products with a runway, sorted ascending by
  `estimatedDaysRemaining`, capped at five rows plus a "See all" toggle
  matching the existing Products-section pattern (`InsightsScreen.kt:331–339`).
- Each row: product name; `"~${formatDecimal(days)} days left"`;
  `"${formatUsageAmount(remaining)} of ~${formatUsageAmount(capacity)} uses"`.
- A footer stating the basis in plain words, e.g. `"Estimated from 7 finished
  Pen products you already logged. Your carts vary; treat this as a rough
  guide."`
- When a type has fewer than `MIN_CAPACITY_SAMPLE` finished products:
  `"Not enough finished Pen products yet — 3 needed, you have 1."` Naming the
  threshold turns an empty card into a comprehensible one.
- When `range.dayCount < MIN_BURN_RATE_DAYS`: `"Pick a range of at least 7
  days to estimate a rate."`

**Insights, spending section.** Extend the existing `SectionCard("Spending
(CAD)")` with one line when `projectCurrentMonthSpend` returns non-null:
`"On track for ${cad(projected)} this month (${cad(monthToDate)} so far,
day $elapsedDays of $daysInMonth)"`.

**Product analytics sheet.** Add the same three runway values to
`ProductAnalyticsSheet`, plus the confidence wording: `LOW` →
`"rough estimate"`, `MEDIUM` → `"estimate"`, `HIGH` → `"estimate from a good
sample"`.

**Log screen.** In `ConsumptionContent`, add one line to the selected-product
card and to `PenQuickLogCard`'s `Loaded` state, below the existing
synced/pending lines: `"~${formatDecimal(days)} days left (estimate)"`.
Requires a new optional parameter on both composables, defaulted so existing
call sites and the existing Compose tests keep compiling:

```kotlin
runwayByProductId: Map<String, ProductRunway> = emptyMap(),
```

**Do not change the existing parameter order or remove any parameter of
`ConsumptionContent`, `HistoryContent`, `InsightsContent`, or
`PenQuickLogCard`.** `app/src/androidTest/java/com/example/ui/` calls these
composables directly; a signature break silently costs the project its
instrumentation coverage.

#### 4.3.6 Copy discipline

Every runway string must:

- contain the word "estimate" or "~", and
- name its evidence ("from 7 finished Pen products"), and
- avoid any instruction, encouragement, or judgement about consumption.

The feature reports arithmetic on the user's own records. It does not advise.

#### 4.3.7 Data safety

Presentation only. `InventoryRunway.kt` has no repository, DataStore, network,
or Room reference and cannot acquire one — it takes DTOs and returns value
objects. Nothing in F2 writes anywhere.

---

### 4.4 F3 — Adaptive layout for large screens and foldables

> Implemented correction: F3 was split into the plan's allowed A/B sequence.
> Width is classified once at the app root before the rail narrows content and
> then passed into Insights. Expanded width uses shared-detail 40/60 panes;
> compact and medium keep modal detail. History refresh/rebind/missing-entry
> effects remain once at parent scope, while operation names and correction
> draft primitives are saveable. The implementation is width-responsive but
> does not inspect a fold hinge or claim crease avoidance. ADR-019 and current
> source are authoritative.

#### 4.4.1 No new dependencies

The obvious approach is `androidx.compose.material3:material3-window-size-class`
or `androidx.compose.material3.adaptive`. Both are rejected for v1.3:

- The project pins Compose BOM `2024.09.00`
  (`gradle/libs.versions.toml:9`). Adding adaptive artifacts risks a BOM
  alignment problem in a release the owner wants to ship, and this session
  cannot build to check.
- Everything needed is a width breakpoint, and `BoxWithConstraints` already
  provides one exactly.

Add a small internal helper instead. New file
`app/src/main/java/com/example/ui/WindowWidth.kt`:

```kotlin
package com.example.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3's width breakpoints, derived locally so the project does not
 * take on material3-window-size-class inside a release.
 */
enum class WindowWidth {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

fun windowWidthFor(maxWidth: Dp): WindowWidth = when {
    maxWidth < 600.dp -> WindowWidth.COMPACT
    maxWidth < 840.dp -> WindowWidth.MEDIUM
    else -> WindowWidth.EXPANDED
}
```

Pure, and therefore JVM-testable without a device.

#### 4.4.2 Navigation

Restructure `CannsheetApp` in
`app/src/main/java/com/example/ui/AppNavigation.kt`:

- Wrap the `Scaffold` in `BoxWithConstraints` and compute
  `windowWidthFor(maxWidth)`.
- `COMPACT` → keep today's `NavigationBar` bottom bar, unchanged.
- `MEDIUM` and `EXPANDED` → render a `NavigationRail` (plain Material 3, no
  new artifact) on the start edge and no bottom bar. Same four destinations,
  same routes, same badge behaviour for the Settings pending count.
- Keep one `NavHost` and one `navController` across all widths. Do **not**
  build separate navigation graphs per width — a config change would reset
  the back stack.
- The submission countdown `Card` currently uses
  `align(Alignment.BottomCenter).fillMaxWidth()` (`AppNavigation.kt:110–117`).
  Add `.widthIn(max = 480.dp)` so it does not stretch across the unfolded
  display. Keep it bottom-centred at every width.
- Preserve `enableEdgeToEdge()` behaviour; the rail must respect
  `navigationBarsPadding()` / `displayCutoutPadding()`.

#### 4.4.3 Two-pane Insights at EXPANDED

At `EXPANDED` only:

- Insights renders the list in a start pane at `weight(0.4f)` and the selected
  product's detail in an end pane at `weight(0.6f)`, instead of a
  `ModalBottomSheet`.
- History renders the event list in the start pane and the selected event's
  detail — including the correction controls — in the end pane.
- With nothing selected, the end pane shows a neutral placeholder
  (`"Select an entry to see details"`).
- At `COMPACT` and `MEDIUM`, both keep today's `ModalBottomSheet` exactly.

**Correction-safety invariant.** The History detail pane must reuse the same
composable body as the sheet, so `historyCorrectionAvailability`, the
`onRefreshIfNotCurrent` trigger, the UUID re-binding, and the missing-entry
dialog behave identically. Extract the current sheet body into
`internal fun HistoryEventDetail(...)` and call it from both. Do not fork the
logic — ADR-010 and the "Editing unavailable" flow depend on that exact
sequence.

#### 4.4.4 State-preservation fixes (a real bug, not polish)

`app/src/main/java/com/example/ui/InsightsScreen.kt:142`:

```kotlin
var selectedProduct by remember { mutableStateOf<AnalyticsProductDto?>(null) }
```

`remember` does not survive a configuration change. Folding, unfolding, or
rotating closes the open product sheet. Three hundred lines below, the History
sheet does this correctly and says why (`InsightsScreen.kt:389–391`: "Hold the
identity, not the snapshot"). Apply the same pattern:

```kotlin
var selectedProductId by rememberSaveable { mutableStateOf<String?>(null) }
val selectedProduct = selectedProductId?.let { id ->
    data.products.firstOrNull { it.productId == id }
}
```

`AnalyticsProductDto` is not `Parcelable`, so storing the DTO in
`rememberSaveable` is not an option anyway — store the ID.

Audit every `remember { mutableStateOf(...) }` in `ui/` that holds
user-visible selection or input, and convert to `rememberSaveable` where the
type is savable. Known candidates to check while there, without changing
behaviour: the dialog and input flags in `ConsumptionContent`
(most already use `rememberSaveable`), and the editor state in
`ProductTypeQuantityEditor` (`durationEnabled`, `secondsPerUseInput`,
`durationSaved` — currently plain `remember`, and these are re-seeded by a
`LaunchedEffect` so converting them needs care; if converting is not clearly
safe, leave them and say so in the pull request).

#### 4.4.5 Tests

- JVM: `WindowWidthTest` — boundary values `599.dp`, `600.dp`, `839.dp`,
  `840.dp`, `0.dp`, and a very large width.
- androidTest: render `CannsheetApp` inside sized containers at 400dp, 700dp,
  and 900dp; assert the bottom bar exists only at 400dp and the rail only at
  700dp/900dp.
- androidTest: at 900dp, assert the Insights detail pane is present without a
  modal sheet; at 400dp, assert the sheet path still opens.
- androidTest: a configuration-change test that opens the product detail,
  triggers a recreate, and asserts the detail is still open. This is the
  regression test for the `remember` bug and is the single most valuable test
  in F3.

#### 4.4.6 Data safety

Pure presentation. No data, sync, queue, widget, or preference behaviour
changes. The one risk is regressing existing Compose instrumentation, which
is why 4.3.5's signature-stability rule applies here too.

---

### 4.5 F4 — Local export (specified, deferred)

Include only if v1.3 has room. Otherwise ship as v1.3.1.

#### 4.5.1 Shape

A "Export data" section in Settings with three actions, each opening the
system file picker via
`ActivityResultContracts.CreateDocument("text/csv")` or
`CreateDocument("application/json")`. No storage permission is required, no
file is written without the user choosing a destination, and nothing is ever
transmitted.

1. **History CSV** — from `AnalyticsRepository.readCachedHistory()`. Columns:
   `eventUuid,localDate,localTime,productId,productName,productType,quantity,
   finished,lifecycleState,revision`. RFC 4180 quoting: wrap any field
   containing `,`, `"`, `\r`, or `\n` in double quotes and double any internal
   quote. Product names can contain commas; a naive `joinToString(",")` will
   corrupt the file.
2. **Insights JSON** — the raw cached payload from the `analytics_cache` row,
   written verbatim. Verbatim matters: a re-serialized copy is a different
   artifact from what the server sent.
3. **Pending queue JSON** — purchases, consumptions, finish actions, and
   corrections as they exist in Room, with their immutable IDs. This is the
   diagnostic bundle for a stuck queue.

#### 4.5.2 Rules

- Writes go through `contentResolver.openOutputStream(uri)` on
  `Dispatchers.IO`, wrapped so a `SecurityException` or `IOException` becomes
  visible UI feedback rather than a crash.
- **Read-only with respect to app state.** Export must never mark anything
  as exported, clear a queue, or touch `sync_request_state`.
- Default filenames carry the date from the Insights response
  (`range.to`), not device-local time — same timezone rule as 4.3.3.
- Settings must state plainly: `"This writes your consumption records to a
  file you choose. Anyone with that file can read them."` The app's whole
  subject matter is sensitive; the warning is not optional.

---

## 5. Delivery sequence

Nine steps. Execute them in order. Each numbered step is exactly one pull
request against `main`, per `AGENTS.md` ("Keep each pull request to one
coherent change").

For every step: branch from current `main`, implement, run the validation in
section 9, review the complete diff (`git diff main...HEAD`) before
committing, commit, push, open the pull request with the seven required
description sections from `AGENTS.md`, wait for the required
`Cannsheet Android PR validation` aggregate check, then squash-merge.

**Do not change `versionCode` or `versionName` in steps 5.1 through 5.8.**
CI enforces version-code monotonicity at release time and the project rule is
explicit (`AGENTS.md`, "Change and release rules").

### 5.1 `chore/backup-rules` — F0

Files: `app/src/main/res/xml/backup_rules.xml`,
`app/src/main/res/xml/data_extraction_rules.xml`, and the "Known limitations"
entry in `docs/PROJECT_STATE.md`.

Title: `chore: make backup and data-extraction rules deliberate`

### 5.2 `feat/queue-health-model` — F1, data only

Files: new `app/src/main/java/com/example/data/sync/QueueHealth.kt`; edits to
`app/src/main/java/com/example/data/SyncPreferencesRepository.kt`,
`app/src/main/java/com/example/CannsheetApplication.kt`,
`app/src/main/java/com/example/data/sync/SyncWorker.kt` (watermark call and
runtime method only); new
`app/src/test/java/com/example/data/sync/QueueHealthTest.kt`; additions to
`app/src/test/java/com/example/data/SyncPreferencesRepositoryTest.kt`.

No manifest change, no notification, no UI. This step is fully JVM-testable
and should be merged before anything can post a notification.

Title: `feat: track queue-integrity state without notifying yet`

### 5.3 `refactor/shared-start-route` — 4.2.8

Files: new `app/src/main/java/com/example/domain/AppEntryPoints.kt`; edits to
`app/src/main/java/com/example/MainActivity.kt`,
`app/src/main/java/com/example/widget/PenWidgetActions.kt`,
`app/src/androidTest/java/com/example/MainActivityIntentTest.kt`.

Mechanical move, identical string value. Kept separate so that if a widget
tap regresses after update, the bisect lands on a three-file diff.

Title: `refactor: share the start-route extra across entry points`

### 5.4 `feat/queue-alerts` — F1, delivery and UI

Files: new `app/src/main/java/com/example/notifications/QueueAlertNotifier.kt`;
new `app/src/main/java/com/example/data/sync/QueueAlertPresenter.kt`; edits to
`app/src/main/AndroidManifest.xml`,
`app/src/main/java/com/example/data/CannsheetGraph.kt`,
`app/src/main/java/com/example/CannsheetApplication.kt`,
`app/src/main/java/com/example/data/sync/SyncWorker.kt`,
`app/src/main/java/com/example/ui/SettingsScreen.kt`,
`app/src/main/java/com/example/ui/CannsheetViewModel.kt`,
`app/src/main/res/values/strings.xml`; new
`app/src/test/java/com/example/ui/QueueAlertStatusTextTest.kt`; new
`app/src/androidTest/java/com/example/notifications/QueueAlertNotifierTest.kt`;
new `app/src/androidTest/java/com/example/ui/QueueAlertSettingsTest.kt`.

Title: `feat: alert when queued entries stop reaching the sheet`

### 5.5 `feat/runway-model` — F2, domain only

Files: new `app/src/main/java/com/example/domain/InventoryRunway.kt`; new
`app/src/test/java/com/example/domain/InventoryRunwayTest.kt`.

No UI. Pure functions and their tests. If the numbers are wrong, this is where
it is cheap to find out.

Title: `feat: model inventory runway and spend run rate`

### 5.6 `feat/runway-ui` — F2, surfaces

Files: edits to `app/src/main/java/com/example/ui/InsightsScreen.kt`,
`app/src/main/java/com/example/ui/ConsumptionScreen.kt`,
`app/src/main/java/com/example/ui/CannsheetViewModel.kt`; additions to
`app/src/androidTest/java/com/example/ui/ConsumptionContentTest.kt` and
`app/src/androidTest/java/com/example/ui/PenQuickLogCardTest.kt`; new
`app/src/androidTest/java/com/example/ui/InsightsRunwayTest.kt`.

Title: `feat: show inventory runway and monthly spend pace`

### 5.7 `feat/adaptive-layout` — F3

Files: new `app/src/main/java/com/example/ui/WindowWidth.kt`; edits to
`app/src/main/java/com/example/ui/AppNavigation.kt`,
`app/src/main/java/com/example/ui/InsightsScreen.kt`; new
`app/src/test/java/com/example/ui/WindowWidthTest.kt`; new
`app/src/androidTest/java/com/example/ui/AdaptiveLayoutTest.kt`; additions to
`app/src/androidTest/java/com/example/ui/HistoryContentTest.kt`.

Largest step. If it needs splitting, split as (a) breakpoints, rail, and the
`rememberSaveable` fixes, then (b) two-pane Insights and History.

Title: `feat: adapt the layout to large screens and foldables`

### 5.8 `docs/v1-3-state` — documentation

Files: `docs/PROJECT_STATE.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`,
`AGENTS.md`, `docs/V1_3_FEATURE_PLAN.md`. See section 11 for exact content.

Title: `docs: record the v1.3 feature set and decisions`

### 5.9 `release/v1.3.0` — version only

The only file change:

```kotlin
versionCode = 31
versionName = "1.3.0"
```

in `app/build.gradle.kts` (currently lines 38–39). Nothing else. No source,
no docs, no resources.

Title: `chore: prepare v1.3.0 release`

### 5.10 Tag and publish

Only after 5.9 is merged **and** the exact post-merge `main` commit has passed
the full six-job matrix:

```bash
git fetch origin main
git checkout main
git pull --ff-only
git tag -a v1.3.0 -m "Cannsheet Mobile 1.3.0"
git push origin v1.3.0
```

The tag must point at the exact validated `main` commit; the release workflow
enforces this in its `confirm-main-validation` job
(`.github/workflows/release-apk.yml:13`). Then verify the signed publication
run, download the published APK independently, and check its SHA-256 against
the published `.sha256`, matching the evidence pattern already used for
v1.2.27 in `docs/PROJECT_STATE.md`.

---

## 6. Complete file inventory

### New files

| Path | Step |
|------|------|
| `app/src/main/java/com/example/data/sync/QueueHealth.kt` | 5.2 |
| `app/src/test/java/com/example/data/sync/QueueHealthTest.kt` | 5.2 |
| `app/src/main/java/com/example/domain/AppEntryPoints.kt` | 5.3 |
| `app/src/main/java/com/example/data/sync/QueueAlertPresenter.kt` | 5.4 |
| `app/src/main/java/com/example/notifications/QueueAlertNotifier.kt` | 5.4 |
| `app/src/test/java/com/example/ui/QueueAlertStatusTextTest.kt` | 5.4 |
| `app/src/androidTest/java/com/example/notifications/QueueAlertNotifierTest.kt` | 5.4 |
| `app/src/androidTest/java/com/example/ui/QueueAlertSettingsTest.kt` | 5.4 |
| `app/src/main/java/com/example/domain/InventoryRunway.kt` | 5.5 |
| `app/src/test/java/com/example/domain/InventoryRunwayTest.kt` | 5.5 |
| `app/src/androidTest/java/com/example/ui/InsightsRunwayTest.kt` | 5.6 |
| `app/src/main/java/com/example/ui/WindowWidth.kt` | 5.7 |
| `app/src/test/java/com/example/ui/WindowWidthTest.kt` | 5.7 |
| `app/src/androidTest/java/com/example/ui/AdaptiveLayoutTest.kt` | 5.7 |

### Modified files

| Path | Steps | Nature |
|------|-------|--------|
| `app/src/main/res/xml/backup_rules.xml` | 5.1 | Replace template with policy |
| `app/src/main/res/xml/data_extraction_rules.xml` | 5.1 | Replace template with policy |
| `app/src/main/java/com/example/data/SyncPreferencesRepository.kt` | 5.2 | Four new preference fields and four methods |
| `app/src/main/java/com/example/CannsheetApplication.kt` | 5.2, 5.4 | Queue-depth collector; presenter install |
| `app/src/main/java/com/example/data/sync/SyncWorker.kt` | 5.2, 5.4 | Runtime methods; alert evaluation |
| `app/src/main/java/com/example/MainActivity.kt` | 5.3 | Import only |
| `app/src/main/java/com/example/widget/PenWidgetActions.kt` | 5.3 | Move one constant out |
| `app/src/androidTest/java/com/example/MainActivityIntentTest.kt` | 5.3 | Import only |
| `app/src/main/AndroidManifest.xml` | 5.4 | One permission |
| `app/src/main/java/com/example/data/CannsheetGraph.kt` | 5.4 | Presenter install point |
| `app/src/main/java/com/example/ui/SettingsScreen.kt` | 5.4 | Alerts section |
| `app/src/main/java/com/example/ui/CannsheetViewModel.kt` | 5.4, 5.6 | Alert toggle; runway flow |
| `app/src/main/res/values/strings.xml` | 5.4 | Alert copy |
| `app/src/main/java/com/example/ui/InsightsScreen.kt` | 5.6, 5.7 | Runway section; two-pane; saveable fix |
| `app/src/main/java/com/example/ui/ConsumptionScreen.kt` | 5.6 | Runway lines |
| `app/src/main/java/com/example/ui/AppNavigation.kt` | 5.7 | Rail, breakpoints, countdown width |
| `app/src/test/java/com/example/data/SyncPreferencesRepositoryTest.kt` | 5.2 | Watermark cases |
| `app/src/androidTest/java/com/example/ui/ConsumptionContentTest.kt` | 5.6 | Runway line |
| `app/src/androidTest/java/com/example/ui/PenQuickLogCardTest.kt` | 5.6 | Runway line |
| `app/src/androidTest/java/com/example/ui/HistoryContentTest.kt` | 5.7 | Detail pane parity |
| `app/build.gradle.kts` | 5.9 | Version metadata only |
| `docs/*.md`, `AGENTS.md` | 5.8 | See section 11 |

### Files that must not be touched

`backend_additions.gs`, `appsscript.json`, `sandbox_provisioning.gs`,
`sandbox_performance_fixture.gs`, everything under `tests/`,
`app/src/main/java/com/example/data/Database.kt`,
`app/src/main/java/com/example/data/SyncEngine.kt`,
`app/src/main/java/com/example/data/SyncQueueLogic.kt`,
`app/src/main/java/com/example/data/Network.kt`,
`app/src/main/java/com/example/data/Repository.kt`,
`.github/workflows/*`, and every existing `signingConfigs` /
`applicationId` / `buildConfigField` line in `app/build.gradle.kts`.

If any of these needs to change, stop and re-plan. It means a hidden coupling
was missed, and the release-safety rules in `AGENTS.md` apply.

---

## 7. Test plan

### 7.1 JVM tests

`QueueHealthTest` — one test method per rule in 4.2.3, plus:

- alerts disabled with a stuck queue → `null`
- background sync disabled with a stuck queue → `null`
- empty queue with `PARTIAL_REJECTIONS` → `null`
- `ENVIRONMENT_MISMATCH` outranks a stuck queue
- `PARTIAL_REJECTIONS` outranks a stuck queue
- exactly at `QUEUE_STUCK_THRESHOLD_MILLIS` → fires (boundary is inclusive)
- one millisecond under → does not fire
- `now` before the watermark → `null`
- same reason inside the repeat window → suppressed
- same reason exactly at the repeat window → fires
- different reason inside the repeat window → fires
- `RETRY_EXHAUSTED` alone under the threshold → `null`
- `RETRY_EXHAUSTED` alone over the threshold → `STUCK_QUEUE`

`SyncPreferencesRepositoryTest` additions — watermark set on the 0→N
transition, unchanged on N→N, cleared on N→0, overwritten on a backwards
clock, alert reason round-trips, unknown stored reason decodes to `null`,
defaults are correct for a store that has never seen the new keys.

`QueueAlertStatusTextTest` — all branches of `queueAlertStatusText`, mirroring
the structure of the existing `BackgroundSyncLastRunTextTest`.

`InventoryRunwayTest` — the heart of F2:

- `median` with odd, even, single-element, and unsorted input
- empty product list → empty models
- two finished products of a type → no model (below `MIN_CAPACITY_SAMPLE`)
- three finished → per-product model
- per-gram basis preferred when three finished products have positive grams
- per-gram basis **not** used when the target product's own `grams` is null
- an outlier does not move the median the way it would move a mean
- `range.dayCount == 6` → `null`; `== 7` → a rate
- zero in-range quantity → `null`
- uses beyond estimated capacity → `estimatedRemainingUses == 0.0`, never
  negative
- `UNOPENED` and `FINISHED` products → `null`
- every returned `Double` is finite for every fixture
- `projectCurrentMonthSpend`: mid-month, first day, last day, past the last
  day, a leap-year February, a missing month, `2100-02` (not a leap year)
- **a timezone test**: run the projection twice with
  `TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))` and
  `TimeZone.getTimeZone("Pacific/Midway")` and assert identical output. This
  is the regression test for the ADR-003 class of bug. Restore the default
  in `@After`.

`WindowWidthTest` — the breakpoint boundaries listed in 4.4.5.

### 7.2 Instrumentation tests

`QueueAlertNotifierTest` — channel exists after `present` on API 26+;
`NotificationManager.getActiveNotifications()` contains exactly one entry with
`QUEUE_ALERT_NOTIFICATION_ID`; a second `present` with a different reason
replaces rather than adds; `clear()` removes it; `present` does not throw when
notifications are disabled. Must pass on both API 24 and API 36 — the API 24
path exercises the pre-channel branch.

`QueueAlertSettingsTest` — the switch renders; toggling on below API 33
enables directly; the status line shows the pending count and the age; the
permission line appears only after a denial. Gate API-33-specific assertions
on `Build.VERSION.SDK_INT` so the API 24 job does not fail on them.

`InsightsRunwayTest` — the Runway section renders rows from a fixture; the
insufficient-sample message names the threshold; nothing renders when the
state is stale.

`AdaptiveLayoutTest` — as listed in 4.4.5, including the configuration-change
test.

`ConsumptionContentTest`, `PenQuickLogCardTest`, `HistoryContentTest` — extend
existing files rather than replacing them, and confirm the existing
assertions still pass unchanged.

### 7.3 Backend tests

Unchanged and not re-run locally unless a backend file changes — which it must
not. CI's `backend` job runs them regardless on a push to `main`.

---

## 8. Device validation checklist

CI emulators and source previews are not device evidence. This project
already records that boundary explicitly and must keep doing so.

**Use a sandbox or `devicecheck` package with a non-production endpoint for
every interactive check.** `docs/HANDOFF.md`'s recommended next action already
says this, and the prior devicecheck precedents are recorded in
`docs/PROJECT_STATE.md`. Do not perform synthetic submissions on the signed
production package.

F1:

- [ ] With alerts on and API 33+, the permission prompt appears once and the
      switch only turns on after Grant
- [ ] Denying leaves the switch off and shows the permission line
- [ ] With the queue artificially non-empty and the watermark backdated more
      than 24 hours, the notification appears exactly once
- [ ] A second worker run inside 24 hours does not post a duplicate
- [ ] Draining the queue clears the notification
- [ ] Turning background sync off suppresses the alert
- [ ] The notification body shows counts only, no product names, and the
      lock-screen preview confirms it
- [ ] Tapping the notification opens Settings, not the Log screen
- [ ] Verified on both a light and a dark system theme

F2:

- [ ] The Runway section renders with real data and its numbers are plausible
      against the user's own knowledge of their carts
- [ ] A type with fewer than three finished products shows the
      insufficient-sample message with the correct count
- [ ] Switching the Insights range to something under seven days hides rates
      rather than showing a wrong one
- [ ] The Log screen runway line matches the Insights value for the same
      product
- [ ] The monthly projection matches a hand calculation

F3, on the Samsung Fold specifically:

- [ ] Cover display: bottom navigation bar, single pane
- [ ] Main display portrait: navigation rail
- [ ] Main display landscape: navigation rail, two-pane Insights
- [ ] Opening a product detail, folding, and unfolding keeps it open — the
      regression this feature exists to fix
- [ ] The same for a History entry with an open correction form
- [ ] The submission countdown card stays readable and does not stretch
- [ ] Nothing is clipped by the hinge, the cutout, or the system bars

Whatever is not performed must be listed in the pull request in the words
"not performed", per `AGENTS.md`.

---

## 9. Validation commands and the toolchain reality

### 9.1 Required per-step command

```bash
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

This is the exact command every prior release session in
`docs/PROJECT_STATE.md` used. Use it verbatim so results are comparable.
Requires JDK 17+, Android SDK Platform 36.1, Build Tools 36.0.0, Gradle 9.3.1
via the wrapper.

Backend suites, only if a backend file changes (it must not):

```bash
node tests/backend_contract_test.js
node tests/backend_analytics_test.js
node tests/backend_corrections_test.js
node tests/backend_recovery_test.js
node tests/backend_spreadsheet_test.js
node tests/fake_sheets_batch_update_test.js
node tests/sandbox_performance_fixture_test.js
node tests/sandbox_provisioning_test.js
PYTHONPATH=. python3 -m unittest tests/test_backend_sync_benchmark.py
```

Also run before every commit:

```bash
git diff --check
```

### 9.2 If the local toolchain is unavailable

At the time this plan was written, the machine had **JDK 1.8, no
`JAVA_HOME`, no Android SDK, no `local.properties`, and no `node`**. Under
those conditions `./gradlew` will not run at all.

The project already has a precedent for this exact situation: PR #33 was
opened as a **draft** specifically because its session could not build
locally, and CI became the authoritative evidence
(`docs/PROJECT_STATE.md`, "Background synchronization feature work"). Follow
that precedent:

1. Attempt the command and capture the exact failure.
2. Open the pull request as a **draft**, stating in the description that no
   local Android validation was possible and quoting the failure.
3. Let the GitHub Actions jobs be the evidence.
4. Mark ready for review only after `Cannsheet Android PR validation` passes.

Never describe an unexecuted check as successful. This rule is in `AGENTS.md`
and it is the one this project cares about most.

### 9.3 Which CI jobs each step triggers

`.github/workflows/android-pr-checks.yml` classifies by changed path. Every
step from 5.1 through 5.7 and 5.9 touches non-doc, non-backend files, so each
gets the **Full Android PR** classification: `classify`, `backend`,
`android-static`, `emulator` (API 24 on pull requests), and the required
`Cannsheet Android PR validation` aggregate.

Step 5.8 is documentation-only and will be classified as such — `run_backend`
and `run_android` both `false`. That is expected; do not mix a source change
into it, or the classification changes and the doc PR starts booting
emulators.

Pushes to `main` run the API 24 **and** API 36 matrix. Steps 5.9 and 5.10
depend on that exact-commit six-job run passing before the tag is pushed.

---

## 10. Risks, mitigations, and rollback

| Risk | Likelihood | Impact | Mitigation | Rollback |
|------|-----------|--------|------------|----------|
| Notification fires for ordinary offline periods, user disables alerts permanently | Medium | High — the feature becomes dead code | 24-hour threshold, `RETRY_EXHAUSTED` excluded, repeat suppression, kill switch honoured; full truth table in `QueueHealthTest` | Raise `QUEUE_STUCK_THRESHOLD_MILLIS`; the constant is one line |
| Notification path throws inside `SyncWorker` and turns an acknowledged delivery into a failed run | Low | High — sync regression | `runCatching` around every presenter call, mirroring `SyncWorker.kt:56`; alert step cannot change `workerResult` | Revert step 5.4 |
| API 24 emulator rejects a notification API | Medium | Medium — CI red | Explicit `Build.VERSION.SDK_INT >= O` channel guard; `NotificationCompat` throughout; instrumentation runs on API 24 | Fix forward; the API 24 renderer fix in PR #61 is the precedent |
| Runway numbers are misleading for a user with few finished products | Medium | Medium — false confidence | `MIN_CAPACITY_SAMPLE`, `MIN_BURN_RATE_DAYS`, median over mean, confidence tier, evidence named in the copy | Raise the minimums, or hide the section |
| Runway shown from a stale snapshot | Low | Medium | `state.isStale` guard in the view model flow | One-line guard change |
| Month projection wrong when the device and response zones differ | Low | Medium | Strict civil-date arithmetic in the response time zone with explicit regression tests | Revert step 5.5 |
| Adaptive layout regresses existing Compose instrumentation | Medium | Medium — CI red | `*Content` composable signatures frozen; new parameters defaulted and appended last | Revert step 5.7 |
| Two-pane History diverges from the sheet and weakens correction safety | Low | High — correctness | One shared `HistoryEventDetail` composable; no forked logic | Revert step 5.7 |
| Backup exclusion loses unsynced actions on a device restore | Low | Medium | Sync window is short; hazard analysis in 4.1.2; recorded in the ADR | Restore the previous XML |
| Moving `EXTRA_START_ROUTE` breaks installed widget taps | Very low | High | String value byte-identical; isolated in step 5.3 for cheap bisect | Revert step 5.3 |

Release-level rollback: every step is a separate squash-merge, so
`git revert <sha>` on `main` undoes exactly one feature. Publishing a
corrected build follows the existing tag-and-release flow with an incremented
version code; the release workflow's monotonicity check will reject anything
else. Users on Obtainium get the next published release; no server-side or
spreadsheet state is involved in any rollback, because v1.3 changes nothing
on the backend.

---

## 11. Documentation updates (step 5.8)

`docs/PROJECT_STATE.md`:

- New section "v1.3 feature work" describing what merged, with exact commit
  SHAs and CI run links filled in as they happen. Do not pre-write run links.
- Update "Known limitations": remove the backup/data-extraction template
  entry, closed by step 5.1.
- Update "Current priorities" and "Unresolved questions" with whatever device
  validation from section 8 was not performed.

`docs/ARCHITECTURE.md`:

- Under "Android components", add `app/src/main/java/com/example/notifications`
  and state that it sits behind the data-facing `QueueAlertPresenter`
  interface, exactly as the widget sits behind `WidgetRefresher`.
- Under "Background queue synchronization", extend the mermaid diagram with
  the queue-health evaluation and presenter edge.
- New subsection "Inventory runway", stating that it is derived client-side
  from the existing Insights payload and adds no request or contract.
- Under "State and error handling", note the width breakpoints and that one
  `NavHost` serves all widths.
- Under "Persistence and models", record the five new `sync_preferences` keys,
  including the exact alert-claim token added during delivery hardening, and
  the backup policy.

`docs/DECISIONS.md` — four new ADRs, following the existing format:

- **ADR-016: Alert on queue integrity, not on every sync failure.** Records
  the 24-hour threshold, the `RETRY_EXHAUSTED` exclusion, opt-in default, the
  kill-switch precedence, and that counts are shown without product detail.
- **ADR-017: Track queue age in DataStore rather than a Room column.**
  Records both options from 4.2.2, the decision, and what a future queue
  inspector would need to pay to change it.
- **ADR-018: Estimate runway from the user's own finished products.** Records
  the median-over-mean choice, the sample minimums, the per-gram preference,
  the `isStale` honesty guard, and that no backend change was required.
- **ADR-019: Derive width breakpoints locally instead of adding an adaptive
  dependency.** Records the BOM-alignment risk and the intent to revisit when
  the Compose BOM is next updated.

`AGENTS.md` — add to "Coding and documentation conventions":

- Queue-integrity alerts are advisory only. Evaluation may read the aggregate
  pending-action count, but no notification or presenter receives queue rows or
  entry details, and no alert path may write, acknowledge, or delete a queue
  row. Notification content must never include product names, quantities, or
  dates.
- Runway and spend projections are presentation-only estimates derived from
  `InsightsResponseDto`. They must not be persisted, transmitted, or treated
  as confirmed values, and must degrade to showing nothing when the Insights
  snapshot is stale.
- Month and day arithmetic on analytics data uses the response's own
  `timeZone` and `range` fields, never a device-local `Calendar` or
  `LocalDate.now()`.
- `EXTRA_START_ROUTE` lives in `com.example.domain` and its string value is
  part of already-issued widget `PendingIntent`s; do not change it.

`docs/HANDOFF.md` — rewrite at the end of the release with the v1.3 outcome,
exact validation runs, published checksum, phone state, and the safety
boundary, following the v1.2.27 structure. It is intentionally not rewritten
in step 5.8 while versioning, publication, artifact verification, and any
authorized installation evidence are still unknown.

---

## 12. Owner decisions resolved during implementation

The owner accepted the proposal and its stated defaults. The implementation
therefore used the following resolutions:

1. Use a 24-hour stuck-queue threshold and 24-hour same-reason repeat window.
2. Defer F4/export beyond v1.3.
3. Include two-pane History at expanded width, with one shared detail body and
   parent-owned refresh/rebind state.
4. Include compact runway copy on the selected Log product and loaded pen.
5. Prepare `1.3.0`, version code `31`, in the separate version-only step.
6. Do not add a consumption goal or limit feature.

---

## 13. What this plan does not claim

- The original proposal itself had no build, test, lint, emulator, or device
  evidence. Implementation evidence is recorded separately and exactly in
  `docs/PROJECT_STATE.md`; it must not be back-projected onto the proposal's
  initial assumptions.
- No physical v1.3 device session, Fold screenshot, hinge-aware check, live
  notification permission walkthrough, 24-hour queue episode, or real-cart
  runway comparison supports the implementation record yet.
- Line numbers cited from the repository were read at commit `82e3c75` and
  will drift as the files change.
- The runway model is an estimate built on the user's own history. It has not
  been validated against real cart capacities, and section 8 exists precisely
  because that validation requires the owner's own knowledge of their carts.
- Whether the Fold's inner display benefits from a two-pane Insights layout
  is a design judgement made from the recorded launcher measurements in
  ADR-015, not from having seen the app on that screen.
