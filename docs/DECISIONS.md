# Architectural decision log

This file records durable decisions that future work must understand. Add an ADR
only when a meaningful decision has been made; do not reconstruct unsupported
historical rationale.

## ADR-001: Keep cross-agent context in the Git repository

- Status: Accepted
- Date: 2026-07-23
- Context: Coding agents, accounts, and sessions cannot be assumed to share
  conversations, task history, memory, or hidden context. Project work needs a
  durable, reviewable handoff mechanism that travels with the source.
- Decision: The Git repository is the canonical source of shared context between
  coding agents. Required operational guidance belongs in `AGENTS.md`; current
  implementation state belongs in `docs/PROJECT_STATE.md`; durable technical
  decisions belong in `docs/DECISIONS.md`; and the latest cross-agent transfer
  state belongs in `docs/HANDOFF.md`. Vendor adapters such as `GEMINI.md` import
  or point to these canonical files without duplicating them. Important
  discoveries made during an agent session must be written back into the
  appropriate repository document rather than left only in a conversation.
- Rationale: Repository content is versioned, reviewable, available to every
  account with the checkout, and can be checked against code and configuration.
- Consequences: Agents must read the shared-context files before substantial
  work, keep them concise and evidence-based, and update them when their subject
  changes. `docs/HANDOFF.md` is replaceable latest state; Git history preserves
  earlier handoffs.
- Related files: `AGENTS.md`, `docs/PROJECT_STATE.md`,
  `docs/ARCHITECTURE.md`, `docs/HANDOFF.md`, `GEMINI.md`,
  `.agents/skills/project-handoff/SKILL.md`

## ADR-002: Android Adaptive and Themed Launcher Icon Conventions

- Status: Accepted
- Date: 2026-07-27
- Context: Android 8.0+ (API 26) uses adaptive icons (`mipmap-anydpi-v26/ic_launcher.xml`), and Android 13+ (API 33) supports Material You themed launcher icons (`<monochrome>`).
- Decision:
  1. `<monochrome>` drawables (`ic_launcher_monochrome.xml`) must contain solid vector shapes (`#000000`) only for positive space (emblem graphics and outlines). Surrounding background canvas and card interiors must be transparent (`#00000000`). Never draw solid background cards in monochrome vectors, as device launchers tint all non-transparent pixels, turning solid boxes into solid dark blobs.
  2. Remove legacy `.webp` bitmap launcher assets from density folders (`mipmap-hdpi`, `mipmap-xxhdpi`, etc.) so modern launchers consistently use `mipmap-anydpi-v26` adaptive XML drawables.
- Rationale: Ensures crisp, correct theme tinting across all launchers (Pixel, Samsung One UI) and prevents fallback to stale bitmap assets.
- Related files: `app/src/main/res/drawable/ic_launcher_monochrome.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `AGENTS.md`

## ADR-003: UTC-Based Date Picker Formatting for Compose UI

- Status: Accepted
- Date: 2026-07-27
- Context: Jetpack Compose Material 3 `DatePickerState.selectedDateMillis` returns epoch milliseconds corresponding to 00:00:00 UTC of the selected day. Using device-local `SimpleDateFormat` without setting `TimeZone.getTimeZone("UTC")` formats 00:00:00 UTC into the evening of the previous day in timezones behind UTC (such as US Eastern Time UTC-4).
- Decision: Use `pickerDateToWire`, `currentLocalDateAsPickerMillis`, and `parsePickerDateToMillis` in `ConsumptionDateTime.kt` across all Compose UI date pickers to enforce timezone-invariant UTC ISO (`yyyy-MM-dd`) date string conversions.
- Rationale: Guarantees that manually selected dates in date pickers stay on the chosen calendar date regardless of the device's local timezone.
- Related files: `app/src/main/java/com/example/ui/ConsumptionDateTime.kt`, `app/src/main/java/com/example/ui/PurchaseScreen.kt`, `app/src/main/java/com/example/ui/ConsumptionScreen.kt`, `app/src/test/java/com/example/ui/ConsumptionDateTimeTest.kt`

## ADR-004: Tiered Validation Workflows and Exact-SHA Release Provenance Gate

- Status: Accepted
- Date: 2026-07-28
- Context: CI validation and release workflows needed optimization to keep documentation-only and backend-only pull requests fast, execute full Android matrix validation across boundary versions (API 24 and API 36) on `main`, prove exact commit SHA validation prior to release publishing, and securely manage Gradle configuration caching and signing secrets.
- Decision:
  1. Implement fail-safe path classification job (`classify`) in `.github/workflows/android-pr-checks.yml`:
     - Documentation-only PRs skip backend and Android static/emulator jobs.
     - Backend-only PRs run backend test suites and skip Android static/emulator jobs.
     - Android and uncertain PRs run backend, Android unit/lint/debug assembly, and API 24 emulator tests.
     - Pushes to `main` and manual dispatches run full matrix validation across both API 24 and API 36 boundary versions.
     - Maintain the exact required status check job display name (`Cannsheet Android PR validation`) for branch protection compatibility.
  2. Implement reusable AVD snapshot caching (`cannsheet-avd-v1`) seeded from `main` branch pushes to accelerate emulator boot times.
  3. Enforce exact-SHA validation proof in `.github/workflows/release-apk.yml` (`confirm-main-validation` job):
     - Release tag creation requires proof via GitHub Actions API that the exact commit SHA passed all validation jobs (`Classify changes and scan repository`, `Backend validation`, `Android static validation`, `Emulator API 24`, `Emulator API 36`, `Cannsheet Android PR validation`) on `main` push.
  4. Exclude signing credentials from Gradle configuration caching:
     - The release build explicitly passes `--no-configuration-cache` for `assembleRelease` while normal PR and `main` builds preserve full configuration caching.
  5. Remove release overwrite (`--clobber`):
     - Existing public releases on `noamvb/cannsheet-mobile-releases` cannot be overwritten; publications require version code monotonicity and post-publication asset download and verification.
- Rationale: Ensures fast developer feedback on narrow PRs, guarantees comprehensive emulator test coverage on `main` before release, prevents unverified code from being published, and protects release signing credentials.
- Related files: `.github/workflows/android-pr-checks.yml`, `.github/workflows/release-apk.yml`, `gradle.properties`, `docs/PROJECT_STATE.md`, `docs/HANDOFF.md`

## ADR-005: Represent consumption edits as append-only corrections

- Status: Accepted; backend and Android integration merged, production rollout
  completed from the merge-verified PR #23 source, and released in v1.2.18
- Date: 2026-07-29
- Context: A mistaken consumption entry must be correctable from the app without
  erasing its history, producing duplicate rows on retry, changing totals
  differently for old and new clients, or making a partially completed
  spreadsheet write unrecoverable.
- Decision:
  1. Keep `ConsumptionEvents` immutable. Record `REPLACE`, `VOID`, and `RESTORE`
     operations in an additive `ConsumptionEventCorrections` sheet.
  2. Give every correction a stable UUID and require the client to name the
     expected correction-chain head. A retry with identical content is a safe
     duplicate; reused identity or a stale head is a conflict.
  3. Resolve the complete linear correction chain into one effective event view
     for Insights, History, reconciliation, and product projections. Voided
     events remain auditable but do not contribute to effective totals.
  4. Include correction state in History cursor consistency checks and the
     durable apply/recovery journal.
  5. Gate writes separately from schema provisioning. Provision production with
     writes disabled, reconcile, and enable only through an explicit rollout
     action. Sandbox helpers must prove target identity before mutation.
  6. Keep ordinary Android History pages bounded: show lifecycle and revision
     metadata without requesting every audit revision for every page. A future
     user-visible revision trail must use a single-event, non-caching detail
     request rather than enlarging the paginated History cache.
- Rationale: An append-only revision log preserves evidence and idempotency while
  allowing the user-facing History screen to behave like editable history.
- Consequences: The backend and client must carry revision metadata and handle
  stale-edit conflicts. The Android client must keep a correction queued until
  an exact accepted acknowledgement arrives. Rollout requires separate sandbox,
  production-disabled, reconciliation, enablement, and release evidence.
- Related files: `backend_additions.gs`, `sandbox_provisioning.gs`,
  `app/src/main/java/com/example/data/AnalyticsData.kt`,
  `app/src/main/java/com/example/data/Database.kt`,
  `app/src/main/java/com/example/data/Repository.kt`,
  `app/src/main/java/com/example/ui/InsightsScreen.kt`,
  `tests/backend_corrections_test.js`,
  `tests/sandbox_provisioning_test.js`, `docs/ARCHITECTURE.md`

## ADR-006: Keep confirmed and pending product usage totals separate

- Status: Accepted
- Date: 2026-08-09
- Context: The Log screen needs to show how much of a product has been used,
  including logs that are still offline, without bypassing correction semantics
  or treating the local queue as complete history.
- Decision:
  1. Extend the existing ordinary product GET response with nullable
     `totalUses`, sourced only from the correction-maintained `Purchases.Uses`
     projection. Validate finite, nonnegative values and round the response to
     six decimal places; do not add a new endpoint, spreadsheet column, API
     version, or History/Insights scan.
  2. Store the confirmed value in nullable `Product.totalUses` through Room
     migration 9-to-10. Invalid values reject the refresh before the cached
     catalog is replaced; missing values remain unavailable rather than zero.
  3. Derive `pendingUsesByProduct` from grouped durable `consumption_actions`
     rows. Existing acknowledgement/remapping rules remain the only way queue
     rows disappear or borrowed temporary IDs become final IDs.
  4. Render confirmed and pending values as separate lines on the selected and
     Recent Products cards. Never add pending values optimistically into the
     confirmed total, and leave the picker unchanged.
- Rationale: The projection is already updated for ordinary logs and
  Correct/Void/Restore operations, while the queue aggregate gives immediate
  offline feedback without claiming unconfirmed backend state. Keeping the
  values separate makes retry, failure, and correction behavior auditable.
- Consequences: A new app against an older backend shows an unavailable
  confirmed value but still shows local pending consumption. After an accepted
  sync, Pending can disappear before the follow-up catalog refresh supplies the
  new confirmed value. Room schema changes are forward-only.
- Related files: `backend_additions.gs`, `app/src/main/java/com/example/data/Network.kt`,
  `app/src/main/java/com/example/data/Database.kt`,
  `app/src/main/java/com/example/data/ProductMapping.kt`,
  `app/src/main/java/com/example/data/Repository.kt`,
  `app/src/main/java/com/example/ui/CannsheetViewModel.kt`,
  `app/src/main/java/com/example/ui/ConsumptionScreen.kt`,
  `tests/backend_analytics_test.js`, `tests/backend_corrections_test.js`

## ADR-007: Serialize background queue synchronization in one app graph

- Status: Accepted; merged through [PR #30](https://github.com/noamvb/cannsheet-mobile/pull/30),
  version-bumped through [PR #31](https://github.com/noamvb/cannsheet-mobile/pull/31),
  and released as Cannsheet Mobile v1.2.19
- Date: 2026-08-09
- Context: Pending actions already have a safe, acknowledgement-based sync
  protocol, but they need a retry path when the app is not in the foreground.
  A foreground attempt and a WorkManager attempt must not create competing
  queue snapshots, Room instances, or request lifecycles.
- Decision:
  1. Create one process-wide `CannsheetGraph` from the application. It owns the
     single Room instance and one shared `syncMutex`; all queue synchronization
     goes through `SyncEngine` while holding that mutex.
  2. Reuse the existing queue snapshot, persisted request ID, environment,
     response-identity, acknowledgement, and duplicate-safe retry rules.
     Background work is a trigger for the same engine, not a backend protocol
     change.
  3. Schedule connected immediate work as one serial unique chain using
     `APPEND_OR_REPLACE`, and schedule one periodic retry every six hours using
     `UPDATE`.
  4. Keep standard WorkManager initialization and do not request expedited
     work. This feature must not add a custom initializer solely to alter
     WorkManager startup behavior.
  5. Store a DataStore background-sync toggle as a kill switch: disabled work
     exits before it sends queued actions, while keeping those rows available
     for a later foreground or re-enabled retry.
  6. Do not change the Apps Script backend, Room schema, or Room version.
- Rationale: A single application graph and mutex make foreground and
  background attempts cooperate while retaining the proven idempotent queue
  protocol. Unique work policies prevent an unbounded set of retries, and the
  preference provides a local stop control without data loss.
- Consequences: New sync callers must use `SyncEngine`; they must not construct
  separate Room or Retrofit synchronization paths. Android scheduling and
  isolated device validation were completed for the released implementation;
  the bounded physical-device evidence is recorded in the v1.2.19 project-state
  section.
- Related files: `app/src/main/java/com/example/CannsheetApplication.kt`,
  `app/src/main/java/com/example/data/SyncPreferencesRepository.kt`,
  `app/src/main/java/com/example/data/sync`,
  `app/src/main/java/com/example/ui`, `app/build.gradle.kts`,
  `docs/ARCHITECTURE.md`

## ADR-012: Make pen quick logging duration-based without changing the wire unit

- Status: Accepted; merged through [PR #49](https://github.com/noamvb/cannsheet-mobile/pull/49)
  and released as Cannsheet Mobile v1.2.24 through version-only [PR #50](https://github.com/noamvb/cannsheet-mobile/pull/50)
- Date: 2026-08-11
- Context: Pen entries are the common consumption path, but the battery reports
  pull duration in seconds while the app stores and transmits uses. A single
  pen cart can be in the battery at a time, so repeatedly choosing a product
  and doing the seconds-to-uses arithmetic adds avoidable work. The existing
  submission countdown also discarded a pending action when another action was
  queued during the cancellation window.
- Decision:
  1. Keep uses as the only stored and transmitted quantity. Store an optional
     seconds-per-use rate per product type and use decimal-safe conversion only
     for duration chip labels; custom quantity entry remains in uses.
  2. Seed the missing duration payload with `P = 10.0` seconds per use. Once a
     versioned payload exists, an absent `P` record is an explicit off state and
     must not be reseeded. Invalid payloads load defensively as an empty map,
     and clearing a rate writes an explicit empty/reduced payload.
  3. Track one loaded pen product ID locally. A valid explicit selectable `P`
     product wins; otherwise the most recently logged selectable `P` product is
     the fallback. A successful local pen log moves the loaded ID to that
     product, while finishing the loaded product clears it.
  4. Render one-tap duration chips only for a resolved loaded pen. Route each
     chip through the existing `queueConsumption` countdown so CANCEL, SUBMIT
     NOW, offline Room persistence, acknowledgement rules, and the PR 1 flush
     behavior remain the single submission path.
  5. Replace the four pending countdown callbacks with one take-once holder.
     Replacing an action returns the displaced callback so it is invoked before
     the new countdown starts; cancel takes it without invoking it.
- Rationale: The UI becomes one tap for the pen-specific common case while the
  durable quantity unit, queue identity, timestamp capture, sync protocol, and
  backend contract remain unchanged. Explicit preference semantics prevent a
  user-cleared rate from silently returning on a later read.
- Consequences: The app gains two local DataStore values and a pen-specific Log
  card plus Settings controls. No Room migration, Apps Script change, endpoint
  change, or spreadsheet write change is required. The feature was accepted
  with a bounded isolated debug-signed walkthrough using a deliberately invalid
  endpoint; the signed v1.2.24 production APK was then installed in place
  without submitting a production data action.
- Related files: `app/src/main/java/com/example/data/ConsumptionPreferencesRepository.kt`,
  `app/src/main/java/com/example/ui/QuantityUnits.kt`,
  `app/src/main/java/com/example/ui/PenQuickLog.kt`,
  `app/src/main/java/com/example/ui/CannsheetViewModel.kt`,
  `app/src/main/java/com/example/ui/ConsumptionScreen.kt`,
  `app/src/main/java/com/example/ui/SettingsScreen.kt`,
  `app/src/test/java/com/example/data/SecondsPerUseOverridesTest.kt`,
  `app/src/test/java/com/example/ui/QuantityUnitsTest.kt`,
  `app/src/test/java/com/example/ui/PenQuickLogTest.kt`,
  `app/src/androidTest/java/com/example/ui/PenQuickLogCardTest.kt`,
  `app/src/androidTest/java/com/example/ui/ProductTypeQuantityEditorTest.kt`,
  `docs/ARCHITECTURE.md`

## ADR-008: Warm the analytics cache from periodic background sync

- Status: Accepted; merged and released as Cannsheet Mobile v1.2.20
- Date: 2026-08-10
- Context: `AnalyticsCoordinator.loadInsightsCacheThenRefresh()` and
  `loadHistoryCacheThenRefresh()` show whatever Room `analytics_cache` row
  already exists, then always issue a live Apps Script refresh. On a cold
  open, that cached row can be many hours old, and it is the only thing an
  offline user ever sees. The existing periodic `SyncWorker` run already wakes
  up every six hours but does nothing when the offline action queue is empty.
- Decision:
  1. Add `AnalyticsPrefetcher`, driven from `SyncWorker.doWork()` as a step
     beside the existing queue sync, not inside `BackgroundSyncRunner`. The
     runner's queue semantics are data-sensitive and its existing test fake
     drives `hasPendingActions()` from a queue consumed by `removeFirst()`, so
     an extra call from inside the runner would break it.
  2. Reuse whichever request shape the current cache row was generated for:
     the cached Insights payload's `range.scope`, or the cached History
     response's `filters`. The `analytics_cache` row is keyed by
     `(environment, resource)` only, so a hardcoded default-range prefetch
     would silently reset a user whose last view was "All" or a filtered
     History. With no cache at all, fetch `InsightsRange.Default` /
     `HistoryFilters()`.
  3. Gate prefetch to periodic runs only, via a `prefetch_analytics`
     WorkManager input-data flag that only `SyncScheduler.periodicRequest()`
     sets. The one-shot chain fires after user actions; prefetching there
     would pay for two extra Apps Script reads on every logged entry for no
     benefit. Prefetch also only runs when the queue-sync result was
     `NothingToSync` or `Applied`, never after `Retry` or
     `EnvironmentMismatch`, and only when the existing "Background sync"
     DataStore switch is on. No new Settings control was added.
  4. Skip a resource whose cache is already less than two hours old, so a
     recently-refreshed cache is not re-fetched on every six-hour wake.
  5. Merge History writes to preserve paged depth: keep cached events
     strictly older than the fresh first page's oldest event, and drop
     everything else, rather than replacing a deeper cached page set with a
     single fresh page 1.
  6. Treat every prefetch step as best effort. A failure must never redeliver
     the queue, overwrite the background-sync status shown in Settings, or
     stop the other resource's prefetch; Insights and History are fetched and
     failed independently.
  7. Accept three trade-offs rather than engineering around them: a
     foreground/background write race where Room `OnConflictStrategy.REPLACE`
     makes the last write win (the two-hour floor keeps the window narrow and
     the next foreground refresh self-corrects); retained History events are
     not re-validated against a corrected `sourceRevision.dataVersion` until
     the next live refresh (no worse than today's staleness, and the UI
     already flags `isFromCache`/`isStale` and refuses correction affordances
     from stale state); and a `REPLACE` correction that moves a retained event
     to an earlier timestamp can leave a stale copy off the fresh first page
     until a full refetch.
- Rationale: Reusing the existing periodic worker and the existing cache
  read/write paths warms the same data the UI already trusts, without a new
  endpoint, Room migration, or Settings surface, and without changing what the
  coordinator does on `onVisible`.
- Consequences: `BackgroundSyncWorkerRuntime` gained a
  `prefetchAnalytics()` method that both the production
  `GraphBackgroundSyncWorkerRuntime` and every test fake must implement.
  `AnalyticsRepository`/`AnalyticsDataSource` gained no new methods; the
  prefetcher depends only on the existing `fetchInsights`, `fetchHistory`,
  `saveHistory`, `readCachedInsights`, and `readCachedHistory` operations
  through a narrow `AnalyticsPrefetchOperations` boundary. The Room schema and
  version are unchanged. The coordinator's own live refresh on every
  `onVisible` is unchanged and must not be "optimised away" by this cache
  being warmer.
- Related files: `app/src/main/java/com/example/data/sync/AnalyticsPrefetcher.kt`,
  `app/src/main/java/com/example/data/sync/SyncWorker.kt`,
  `app/src/main/java/com/example/data/sync/SyncScheduler.kt`,
  `app/src/main/java/com/example/data/AnalyticsData.kt`,
  `app/src/main/java/com/example/ui/AnalyticsState.kt`,
  `app/src/test/java/com/example/data/sync/AnalyticsPrefetcherTest.kt`,
  `app/src/test/java/com/example/data/sync/SyncWorkerPrefetchGateTest.kt`,
  `docs/ARCHITECTURE.md`

## ADR-009: Keep Purchase autofill defaults local and keyed by product name/type

- Status: Accepted; merged and released as Cannsheet Mobile v1.2.21 through
  [PR #36](https://github.com/noamvb/cannsheet-mobile/pull/36) and version-only
  [PR #37](https://github.com/noamvb/cannsheet-mobile/pull/37)
- Date: 2026-08-11
- Context: Purchase catalog rows can be incomplete, can share a name across
  product types, and can change independently of a user's preferred cost,
  THC, and grams. The feature needs to remember explicit user choices without
  changing Room's pending queue schema or the Apps Script contract.
- Decision:
  1. Store defaults in one version-1 JSON value in a dedicated Preferences
     DataStore named `purchase_defaults`, with one atomic full-map edit per
     saved product/type key. Invalid records are skipped, malformed or
     unsupported payloads load as an empty map, and deterministic type/name
     ordering makes the stored representation stable.
  2. Normalize keys with trimmed, locale-independent product names and types;
     preserve the product type as part of identity so the same name in two
     types cannot share defaults. Store THC as a fraction from `0.0` through
     `1.0`, retaining explicit zero.
  3. Use the saved map only after an explicit type-filtered suggestion
     selection. A complete saved entry takes precedence over catalog values;
     otherwise valid catalog fields are used as a fallback. Manually entered
     products can become saved keys after a successful purchase.
  4. Capture an immutable submission before the existing Undo countdown. After
     the countdown, write the purchase to Room first, then attempt the optional
     DataStore write, and then follow the existing synchronization path. A
     canceled countdown writes neither item; a DataStore failure never removes
     or retries the already-queued purchase.
  5. Keep default-save validation stricter than ordinary unchecked purchases:
     cost is finite and non-negative, THC is explicitly entered and within
     `0..100` percent, grams is finite and positive, and name/type are
     nonblank. Feedback about purchase/default persistence is separate from
     the existing sync status.
- Rationale: A local preference is available offline, avoids a Room migration,
  preserves the existing queue and backend idempotency boundaries, and lets a
  user correct or replace future defaults without mutating catalog data.
- Consequences: `CannsheetGraph` owns one `PurchaseDefaultsRepository`; the
  feature adds no backend fields, endpoint behavior, UUID behavior, Room
  tables, version metadata, or signing configuration. Device, CI, and
  Obtainium evidence remain separate from the local checks, and the release
  publication is recorded in the v1.2.21 project-state entry.
- Related files: `app/src/main/java/com/example/data/PurchaseDefaultsRepository.kt`,
  `app/src/main/java/com/example/data/CannsheetGraph.kt`,
  `app/src/main/java/com/example/ui/CannsheetViewModel.kt`,
  `app/src/main/java/com/example/ui/PurchaseScreen.kt`,
  `app/src/main/java/com/example/ui/PurchaseAutofill.kt`,
  `app/src/test/java/com/example/data/PurchaseDefaultsRepositoryTest.kt`,
  `app/src/test/java/com/example/ui/PurchasePersistenceTest.kt`,
  `app/src/androidTest/java/com/example/ui/PurchaseContentTest.kt`

## ADR-010: Make History refresh feedback visible and rebind open entries

- Status: Accepted; merged and released as Cannsheet Mobile v1.2.22 through
  [PR #40](https://github.com/noamvb/cannsheet-mobile/pull/40) and version-only
  [PR #41](https://github.com/noamvb/cannsheet-mobile/pull/41)
- Date: 2026-08-11
- Context: A cached or stale History page correctly blocks corrections, but the
  existing refresh action provided no visible progress or failure inside the
  detail sheet and handed correction dialogs the DTO that was opened before a
  successful refresh. This made the correction gate appear broken and could
  reject an edit immediately after the user followed its refresh instruction.
- Decision:
  1. Keep the fresh-snapshot correction gate and share its predicate with the
     History detail sheet's automatic refresh entry point.
  2. Render History refresh progress in the list header and render progress or
     failure inline in the detail sheet, where the reader can see it while the
     modal sheet is open.
  3. Start one idempotent refresh when a stale or cached entry is opened,
     rather than adding a process-lifecycle observer, time-based staleness TTL,
     or a new dependency.
  4. Track the opened entry by UUID and re-read it from current History state so
     correction drafts carry the current `correctionHeadId` after refresh.
  5. Close the sheet and explain the outcome when the opened entry is no longer
     present on the refreshed page.
- Rationale: The existing optimistic-concurrency rule remains the data-safety
  boundary, while visible state and UUID-based rebinding make the prescribed
  refresh action observable and make a successful refresh genuinely unblock a
  correction.
- Consequences: The History UI gains one automatic refresh trigger, inline
  failure feedback, rotation-safe entry identity, and an explicit missing-entry
  dialog. The accepted trade-off is that automatic refresh resets History to
  page 1 and clears paged depth, exactly as the manual Refresh button already
  does. It can only fire when `historyNeedsRefreshForCorrections` is true, which
  already implies `hasFreshCursor == false` and no usable cursor.
- Related files: `app/src/main/java/com/example/ui/AnalyticsState.kt`,
  `app/src/main/java/com/example/ui/CannsheetViewModel.kt`,
  `app/src/main/java/com/example/ui/InsightsScreen.kt`,
  `app/src/test/java/com/example/ui/AnalyticsCoordinatorTest.kt`,
  `app/src/test/java/com/example/ui/HistoryCorrectionUiTest.kt`,
  `app/src/androidTest/java/com/example/ui/HistoryContentTest.kt`,
  `docs/ARCHITECTURE.md`

## ADR-011: Keep quick-log quantity presets local and overridable per product type

- Status: Accepted; merged through [PR #44](https://github.com/noamvb/cannsheet-mobile/pull/44)
  and released as Cannsheet Mobile v1.2.23 through version-only
  [PR #45](https://github.com/noamvb/cannsheet-mobile/pull/45)
- Date: 2026-08-11
- Context: The Log screen currently renders one global quick-log quantity list
  for every product. Pens, shatter, flower, and other product types can use
  different units or practical quantities, so a shared list is repeatedly
  wrong for at least one type. The feature needs to add type-specific choices
  without changing the existing global preference, Room schema, offline queue,
  or Apps Script contract.
- Decision:
  1. Keep the existing global quick-log quantity editor and use its list as the
     fallback for every type without a valid override. Store overrides in the
     existing `consumption_preferences` DataStore as one version-1 JSON value;
     do not touch the existing scalar global preset keys.
  2. Normalize `ProductTypeKey` values with trimmed,
     locale-independent uppercase text. Decode the JSON defensively: malformed,
     unsupported, or invalid records are ignored, duplicate normalized types
     resolve to the later record, and the in-memory/stored representation is
     deterministic by type.
  3. Read global presets and overrides from the same `dataStore.data` snapshot,
     expose effective presets from `CannsheetViewModel`, and use the selected
     catalog product's type for both Log chips and the initial default quantity.
     Remembered per-product quantities still take precedence over the type
     default; clearing the selection continues to use the global list.
  4. Reuse one validated preset-row editor for the global Settings section and
     the type-specific section. The type editor exposes the canonical type
     labels plus normalized catalog extensions and provides an explicit reset
     to the global fallback.
  5. Keep borrowed products on global presets because their free-text type is
     collected after the chips render. Keep overrides type-scoped rather than
     introducing per-product persistence.
- Rationale: A local full-map preference preserves offline behavior and avoids
  a migration or backend contract change. The override resolver makes the new
  behavior explicit while retaining the existing global settings and test tags
  as a regression boundary.
- Consequences: The feature adds a version-1 JSON preference, a shared product
  type catalog, and ViewModel/UI flows but no Room tables, queue payloads,
  network fields, Apps Script writes, or signing-configuration changes. A
  malformed override payload safely behaves as if no type overrides exist.
  The signed v1.2.23 release passed the exact-main and publication gates. The
  earlier feature walkthrough uses an isolated temporary application ID; the
  public v1.2.23 APK was subsequently installed in place on the production
  Samsung with `adb install -r`, preserving package identity, signing identity,
  data directory, and existing install time. No production data action was
  submitted during the launch check.
- Related files: `app/src/main/java/com/example/data/ConsumptionPreferencesRepository.kt`,
  `app/src/main/java/com/example/ui/ProductTypes.kt`,
  `app/src/main/java/com/example/ui/CannsheetViewModel.kt`,
  `app/src/main/java/com/example/ui/ConsumptionScreen.kt`,
  `app/src/main/java/com/example/ui/SettingsScreen.kt`,
  `app/src/main/java/com/example/ui/PurchaseScreen.kt`,
  `app/src/test/java/com/example/data/QuantityPresetOverridesTest.kt`,
  `app/src/test/java/com/example/ui/ProductTypesTest.kt`,
  `app/src/androidTest/java/com/example/ui/ProductTypeQuantityEditorTest.kt`,
  `docs/ARCHITECTURE.md`

## ADR-013: Use a RemoteViews widget with a DataStore-arbitrated deferred commit

- Status: Accepted; merged through [PR #52](https://github.com/noamvb/cannsheet-mobile/pull/52)
  and released as Cannsheet Mobile v1.2.25 through version-only
  [PR #53](https://github.com/noamvb/cannsheet-mobile/pull/53)
- Date: 2026-08-12
- Context: Pen consumption is frequent enough to benefit from a home-screen
  entry point, but the existing data boundary is deliberately expressed in
  uses, not seconds. The widget also needs a short Undo affordance without
  creating a second persistence or synchronization path, and an undo/worker
  race must not delete or duplicate an existing Room queue row.
- Decision:
  1. Use the platform `AppWidgetProvider` and `RemoteViews` APIs, with no
     Glance dependency, and keep the implementation compatible with API 24.
     Cover rendering and action compatibility in the API 24/API 36 validation
     paths.
  2. Keep seconds as input/display units only. On submit, capture the product,
     stable consumption event ID, date, time, seconds, rate, and `uses` in a versioned
     `pen_widget_state` DataStore payload after applying `secondsToUses`. Only
     the converted uses enter `ConsumptionLogger`, Room, the offline queue, and
     the Apps Script wire contract.
  3. Present a five-second Undo window. Use a process-local timer for the common
     commit path, one unique WorkManager request as the durable backstop, and
     lazy overdue flushing on broadcasts and application startup as recovery.
     Every delivery tier must use the same DataStore arbitration boundary;
     cancelling work is an optimization. Undo loses to a live DataStore claim
     and may restore only an unclaimed or stale-claimed payload. The process-local
     `WidgetWorkSerializer` orders the known provider and worker entry points but
     is not the correctness boundary.
  4. Reuse the shared loaded-cart and logging boundaries, refresh the widget
     after relevant view-model, provider, startup, and acknowledged-sync events,
     and add no Room migration, queue field, network endpoint, or Apps Script
     path.
- Rationale: Classic `RemoteViews` keeps the widget small and compatible with
  the existing minimum API while avoiding a new UI/runtime dependency. A
  DataStore-captured immutable payload lets the widget show Undo without
  partially writing or later reconstructing a submission; commit IDs make late
  worker callbacks harmless. Reusing `ConsumptionLogger` preserves the
  existing stable-ID, queue, acknowledgement, and retry guarantees.
- Consequences: The app gains `Unavailable`, `NoCart`, `RateOff`, `Composing`,
  and `AwaitingCommit` widget states, a bounded 0..600-second draft, and a
  short-lived local pending payload. The payload is excluded from backup. A
  future physical visual/action walkthrough must use a
  separate sandbox/debug package; the v1.2.25 production install was verified
  by package/readback only and no production widget action was submitted. The
  follow-up arbitration rule is claim-based: the mutex preserves ordering for
  current-process callers, but a live claim itself prevents undo from restoring
  a payload while its Room write is in flight.
- Related files: `app/src/main/java/com/example/widget/PenConsumptionWidgetProvider.kt`,
  `app/src/main/java/com/example/widget/PenWidgetCommitCoordinator.kt`,
  `app/src/main/java/com/example/widget/PenWidgetCommitWorker.kt`,
  `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`,
  `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`,
  `app/src/main/java/com/example/data/ConsumptionLogger.kt`,
  `app/src/main/java/com/example/data/sync/SyncScheduler.kt`,
  `app/src/main/java/com/example/data/sync/SyncWorker.kt`,
  `app/src/main/java/com/example/CannsheetApplication.kt`,
  `app/src/main/java/com/example/ui/CannsheetViewModel.kt`,
  `app/src/test/java/com/example/widget`,
  `app/src/androidTest/java/com/example/widget`,
  `docs/ARCHITECTURE.md`

## ADR-014: Make widget delivery retry-safe before removing captured state

- Status: Accepted; implemented through
  [PR #55](https://github.com/noamvb/cannsheet-mobile/pull/55)
- Date: 2026-08-12
- Context: The initial widget implementation removed its DataStore payload
  before the Room write, generated the consumption event ID only at write time,
  depended on WorkManager latency for a five-second interaction, and allowed a
  late widget commit to re-point the loaded cart. A Room failure could therefore
  lose the submission, while a naive retry could duplicate it.
- Decision:
  1. Use payload version 2 with a submit-time `eventId`,
     `submittedAtEpochMillis`, and claim metadata. Decode known version-1
     payloads into the new shape with a deterministic event ID; reject only
     malformed or unknown payloads.
  2. Claim a due payload in DataStore without deleting it, write the same
     immutable event ID through `ConsumptionLogger`, and remove the payload only
     after Room persistence succeeds. Release a failed claim for retry. A unique
     claim token prevents an older attempt from completing a newer claim, and a
     new process can immediately recover a claim left by process death.
  3. Show Undo for five seconds, reserve a 1.5-second delivery grace interval,
     use a serialized process-local timer as the primary path, retain unique
     WorkManager work as the durable backstop, and force resolution after a
     bounded maximum age or a material backwards wall-clock jump. All paths use
     the same claim/complete arbitration.
  4. A widget commit records consumption without changing the loaded-cart
     preference. Widget deletion force-commits a fresh payload before clearing
     per-widget state and cancels its timer/work only after no payload remains.
  5. Serialize provider mutation-plus-render operations and join WorkManager to
     that boundary. Use direct activity PendingIntents and one-shot navigation
     route events so opening the widget does not rely on a background activity
     launch or recreate an existing Compose tree.
  6. Keep widget copy in Android resources, announce values and actions through
     accessibility descriptions, provide full and compact layouts plus a real
     picker preview, and round display-only quantities without changing stored
     six-decimal uses.
- Rationale: The durable payload becomes a tiny local outbox: failure retains
  retryable state, the stable event ID makes a repeated Room insert idempotent,
  and completion is the only destructive transition. Timer, worker, startup,
  receiver, and deletion paths can race without losing or duplicating the
  logical event.
- Consequences: The change adds no Room migration, queue field, Apps Script
  contract, production endpoint, application ID, signing, or release-metadata
  change. A v1 pending payload is upgraded at decode/claim time. Physical
  launcher sizing, Doze timing, and TalkBack behavior still require a separate
  sandbox-package walkthrough; local tests and CI are not device evidence.
- Related files: `app/src/main/java/com/example/widget`,
  `app/src/main/java/com/example/domain`,
  `app/src/main/java/com/example/data/ConsumptionLogger.kt`,
  `app/src/main/java/com/example/data/WidgetRefresher.kt`,
  `app/src/main/java/com/example/MainActivity.kt`,
  `app/src/main/java/com/example/ui/AppNavigation.kt`, `AGENTS.md`,
  `docs/ARCHITECTURE.md`

## ADR-015: Make the pen widget compact breakpoint reachable on the Fold

- Status: Accepted; implemented in the sizing follow-up after physical
  sandbox-package measurement and released as Cannsheet Mobile v1.2.27.
- Date: 2026-08-13
- Context: The full `RemoteViews` layout contains the cart name, subtitle,
  counter/submit row, and +/- row. Its declared `160dp` minimum-resize height
  therefore kept the existing compact layout unreachable. The follow-up guide
  required launcher evidence before changing that threshold, including both
  Fold screens and both portrait/landscape orientations.
- Decision:
  1. Measure `OPTION_APPWIDGET_MIN_HEIGHT` and `MAX_HEIGHT` through a temporary
     suffixed sandbox package with a deliberately invalid non-production
     endpoint. Do not use the production package for widget placement or
     actions.
  2. Record the observed default callback heights as cover portrait `300dp`,
     main portrait `274dp`, main landscape `259dp`, and cover landscape
     `300dp`. The Samsung free-grid selection UI did not expose a usable
     minimum-resize handle for the pinned sandbox widget, so the minimum-resize
     callback is not represented as measured evidence.
  3. Since all observed defaults are at least `160dp`, lower only
     `minResizeHeight` to the guide's preferred `110dp`, move the compact
     breakpoint to `150dp`, and keep the provider's `minHeight` at `160dp`.
     Reference the new minimum from one shared dimension resource in both
     provider-info variants.
  4. Add a weighted bottom spacer to the full and compact vertical layouts so
     extra launcher height is absorbed without distorting the controls or
     relying on incidental root measurement behavior.
- Rationale: The measured defaults leave ample room for the full layout, while
  a `110dp` resize floor makes the already-existing compact layout reachable on
  launchers that honor the provider's minimum. A `150dp` breakpoint leaves a
  small buffer above the compact layout's fixed control stack. Keeping the
  higher `minHeight` preserves the provider's initial/default presentation;
  only user-resize eligibility changes.
- Consequences: Existing widget instances may be offered a smaller resize
  range after the release, but no Room, DataStore payload, queue, network,
  backend, or stored quantity contract changes. The sandbox package and its
  temporary log/pin hook were removed after measurement. Physical minimum-size
  evidence remains a documented launcher limitation rather than an inferred
  value. The v1.2.27 production install was verified by package/readback only;
  no physical production widget action was performed.
- Related files: `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`,
  `app/src/main/res/values/dimens.xml`,
  `app/src/main/res/xml/pen_consumption_widget_info.xml`,
  `app/src/main/res/xml-v31/pen_consumption_widget_info.xml`,
  `app/src/main/res/layout/widget_pen_consumption.xml`,
  `app/src/main/res/layout/widget_pen_consumption_compact.xml`,
  `docs/WIDGET_REVIEW_PLAN.md`

## ADR-016: Alert on queue integrity, not on every sync failure

- Status: Accepted; implemented and released in v1.3.0.
- Date: 2026-08-13
- Context: WorkManager already retries transient background failures, but the
  app previously had no user-visible signal when local entries remained unable
  to reach the sheet. Alerting on every failed attempt would turn ordinary
  offline periods into noise and could encourage unsafe queue manipulation.
- Decision:
  1. Queue alerts are separately opt-in and default off. The background-sync
     kill switch takes precedence over the alert preference.
  2. An aggregate queue that remains continuously non-empty for 24 hours may
     alert. Current-episode environment mismatch, partial rejection, and
     backend-capability-pending results may alert immediately.
  3. `RETRY_EXHAUSTED` is not independently actionable; an ordinary transient
     outage becomes actionable only through the same 24-hour queue-age rule.
  4. Suppress the same active reason for 24 hours after an exact delivery claim.
     Use a stable notification card and an unconstrained delayed check so the
     clock can mature while connected sync work is blocked.
  5. Notification copy may contain only an aggregate pending count and reason.
     It must not contain product names, quantities, consumption dates, or other
     entry detail.
- Rationale: Alerting on durable queue integrity focuses interruption on states
  that may require the owner, while WorkManager retains responsibility for
  routine recovery. Aggregate copy supplies enough context without exposing
  consumption detail on a lock screen.
- Consequences: A continuously non-empty queue can alert while offline, but a
  brief failure and five exhausted retries alone remain silent. Presentation is
  advisory and cannot acknowledge, mutate, or delete queue rows.
- Related files: `app/src/main/java/com/example/data/sync/QueueHealth.kt`,
  `app/src/main/java/com/example/data/sync/QueueAlertScheduler.kt`,
  `app/src/main/java/com/example/data/sync/QueueAlertDeliveryCoordinator.kt`,
  `app/src/main/java/com/example/notifications/QueueAlertNotifier.kt`,
  `app/src/main/java/com/example/ui/SettingsScreen.kt`

## ADR-017: Track queue age in DataStore rather than a Room column

- Status: Accepted; implemented and released in v1.3.0.
- Date: 2026-08-13
- Context: The stuck-queue rule needs the beginning of the current aggregate
  non-empty episode. Adding an enqueue timestamp to each Room queue would
  require a forward schema migration across four action types and would still
  need episode-level logic. Persisting one aggregate watermark avoids changing
  user-data rows but cannot identify the age of an individual entry.
- Decision: Store `queue_non_empty_since_epoch_millis` in
  `sync_preferences`. Serialize Room depth observation, set the watermark only
  on empty-to-non-empty transition, preserve it while depth stays positive, and
  clear it on drain. Persist the last alert reason/time and an exact claim token
  beside it; scope terminal sync results to the current watermark before they
  can become actionable.
- Rationale: The current feature needs episode age, not a queue inspector. One
  DataStore value avoids a Room migration and leaves immutable queue payloads
  and acknowledgement rules untouched.
- Consequences: v1.3 cannot answer which individual row is oldest. A future
  queue-inspector feature that promises per-entry age must pay for explicit
  enqueue timestamps, migrations for every supported schema, reconciliation of
  legacy rows, and UI/privacy decisions; it must not reinterpret this aggregate
  watermark as row evidence.
- Related files: `app/src/main/java/com/example/data/SyncPreferencesRepository.kt`,
  `app/src/main/java/com/example/CannsheetApplication.kt`,
  `app/src/main/java/com/example/data/sync/QueueHealth.kt`,
  `app/src/main/res/xml/backup_rules.xml`,
  `app/src/main/res/xml/data_extraction_rules.xml`

## ADR-018: Estimate runway from the user's own finished products

- Status: Accepted; implemented and released in v1.3.0.
- Date: 2026-08-13
- Context: The Insights contract already contains per-product status, grams,
  first-use time, recorded quantities, and month spending. A remaining-use
  estimate can therefore be derived without inventing a universal product
  capacity or changing the backend, but sparse or stale evidence can make the
  arithmetic misleading.
- Decision:
  1. Model the typical recorded amount at finish with the median, not the mean,
     over at least three eligible finished products of the normalized type.
     Describe it as recorded finish evidence, never literal cartridge or
     package capacity.
  2. Prefer a per-gram median only when at least three finished products have
     valid gram evidence. Confidence and displayed sample size use the evidence
     selected by that basis.
  3. Require at least seven inclusive calendar days in the product-specific
     burn window, beginning at the later of the selected range start or the
     product's first recorded use in the response time zone.
  4. Derive runway and current-month spend only from `InsightsResponseDto` in
     presentation state. Suppress them for a missing, cached, stale, changing,
     or locally incomplete snapshot, including any pending action.
  5. Admit month projection only for a true current-month-through-today range
     in the response time zone and suppress ambiguous ownership, cost, date,
     invalid-row, or bucket-reconstruction cases.
- Rationale: Medians reduce sensitivity to atypical finished products, basis-
  specific evidence keeps confidence honest, and suppressing stale/incomplete
  snapshots is safer than displaying a precise-looking value from data known
  to omit local actions.
- Consequences: Many products intentionally show no estimate until evidence is
  sufficient. The estimates are not persisted or transmitted, do not alter
  consumption behavior, and require no Apps Script, wire, Room, or spreadsheet
  change.
- Related files: `app/src/main/java/com/example/domain/InventoryRunway.kt`,
  `app/src/main/java/com/example/ui/RunwayPresentation.kt`,
  `app/src/main/java/com/example/ui/RunwayFormatting.kt`,
  `app/src/main/java/com/example/ui/AnalyticsState.kt`,
  `app/src/main/java/com/example/ui/CannsheetViewModel.kt`

## ADR-019: Derive width breakpoints locally instead of adding an adaptive dependency

- Status: Accepted; implemented and released in v1.3.0.
- Date: 2026-08-13
- Context: v1.3 needed only Material width classification, a navigation rail,
  and expanded analytics detail panes. The project pins Compose BOM
  `2024.09.00`; adding `material3-window-size-class` or
  `material3-adaptive` during release work would enlarge the dependency and BOM
  compatibility surface for behavior that `BoxWithConstraints` can supply.
- Decision: Derive compact below 600dp, medium below 840dp, and expanded at
  840dp or wider in one local helper. Compute the class once at the app root,
  before rail width is removed, and pass it through the existing single
  `NavHost`. Use a bottom bar at compact width, a rail at medium/expanded width,
  and shared-detail 40/60 Insights and History panes only at expanded width.
- Rationale: A tiny pure helper is JVM-testable, avoids release-cycle dependency
  alignment risk, and prevents a 900dp root from being misclassified after the
  rail narrows its child content.
- Consequences: The feature is responsive to width but not hinge-aware; it does
  not inspect folding features or guarantee crease avoidance. Revisit the
  official adaptive libraries when the Compose BOM is deliberately upgraded
  and the app needs posture-aware placement, not as incidental release work.
- Related files: `app/src/main/java/com/example/ui/WindowWidth.kt`,
  `app/src/main/java/com/example/ui/AppNavigation.kt`,
  `app/src/main/java/com/example/ui/InsightsScreen.kt`
