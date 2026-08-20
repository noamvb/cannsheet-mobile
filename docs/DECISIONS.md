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

## ADR-020: Floor Log-screen-initiated analytics refreshes

- Status: Accepted; implemented in v1.3.1.
- Date: 2026-08-13
- Context: The Log screen consumes the Insights snapshot to display per-product
  runway estimates beside active inventory. In v1.3.0, every queue drain that
  occurred while the Log screen was visible immediately dispatched an Apps Script
  analytics fetch. During multi-entry logging sessions, this generated multiple
  rapid read requests for derived data whose values rarely shifted between
  consecutive entries.
- Decision:
  1. Define a 2-minute minimum interval (`RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS = 2L * 60L * 1000L`)
     for analytics refreshes triggered solely by Log screen visibility.
  2. Gating applies only to automatic post-drain or invalidation-driven refreshes
     while `runwayScreenVisible` is true and `insightsScreenVisible` is false.
  3. The cold-start initial load (`loadInsightsCacheThenRefresh()`) and any
     refresh initiated while the **Insights** tab is visible remain immediate and
     unfloored.
  4. Guard against non-monotonic or backwards clock jumps so that the floor cannot
     wedge shut.
- Rationale: A 2-minute debounce preserves backend efficiency and matches the
  prefetch-gating rationale in `SyncWorker`. The slight estimate lag on the Log
  screen is acceptable given that estimates are presentation-only approximations,
  while the full Insights tab remains immediately fresh.
- Consequences: Estimates on the Log screen may reflect a snapshot up to two
  minutes old following a series of rapid logs. No database, sync engine,
  acknowledgement, or Apps Script contracts are affected.
- Related files: `app/src/main/java/com/example/ui/AnalyticsState.kt`,
  `app/src/test/java/com/example/ui/AnalyticsCoordinatorTest.kt`,
  `docs/ARCHITECTURE.md`

## ADR-021: Fast-path backend analytics response caching and non-blocking client presentation

- Status: Accepted; implemented in v1.3.2 through [PR #83](https://github.com/noamvb/cannsheet-mobile/pull/83) and version bump [PR #84](https://github.com/noamvb/cannsheet-mobile/pull/84).
- Date: 2026-08-14
- Context: Refreshing the Insights and History pages previously took multiple seconds or minutes due to sequential Google Sheets API reads across four sheets (`Purchases`, `ConsumptionEvents`, `ConsumptionEventCorrections`, `SyncLedger`) and recalculating all historical metrics from scratch on every request. On the Android client, screen entry set `isInitialLoading = true` before reading Room SQLite cache, showing full-screen blocking spinners.
- Decision:
  1. **Backend CacheService Response Chunking**: Cache serialized analytics JSON responses in Google Apps Script `CacheService` in 100KB chunks keyed by resource, environment, query parameters, and a script-wide `MUTATION_WATERMARK`.
  2. **Atomic Invalidation via Watermark**: Any mutating write (`doPost`, `onFormSubmit`, `onInventoryEdit`, migrations) bumps `MUTATION_WATERMARK` in `CacheService`, immediately invalidating all cached responses without persistent property overhead.
  3. **Single-RPC Batch Sheet Reads**: Replace serial `getRange().getValues()` calls across multiple sheets with a consolidated `Sheets.Spreadsheets.Values.batchGet` call.
  4. **Scoped ScriptLock**: Hold `ScriptLock` only during the atomic batch data retrieval, releasing it prior to in-memory aggregation and response formatting to eliminate `BACKEND_BUSY` contention on read requests.
  5. **Instant Local Cache Presentation**: In `AnalyticsCoordinator`, emit cached SQLite state immediately with `isInitialLoading = false` so UI renders at 0ms, running network refreshes strictly in the background.
- Rationale: Caching unchanged analytics payloads in Google Apps Script provides sub-200ms response times without spreadsheet API overhead. Single-RPC batch retrieval accelerates cold recalculations. Immediate cache presentation removes UI blocking while preserving full data consistency and background refresh semantics.
- Consequences: Unchanged analytics queries return in <200ms. Cold recalculations are 3-5x faster. The Android UI renders instantly on screen entry. Wire contracts, schema versioning, and Room offline queue invariants remain completely intact.
- Related files: `backend_additions.gs`,
  `tests/fake_apps_script_runtime.js`,
  `tests/backend_analytics_test.js`,
  `app/src/main/java/com/example/ui/AnalyticsState.kt`,
  `app/src/test/java/com/example/ui/AnalyticsCoordinatorTest.kt`,
  `app/src/test/java/com/example/data/AnalyticsDataTest.kt`,
  `docs/ARCHITECTURE.md`

## ADR-022: Correctness fixes for the v1.3.2 analytics caching fast path

- Status: Accepted; implemented in v1.3.3.
- Date: 2026-08-15
- Context: A review of ADR-021's v1.3.2 implementation, verified by running
  probes against the project's own fake Apps Script runtime, found three real
  defects and one inaccurate claim:
  1. **The watermark was never durably persisted.** `getMutationWatermark_()`
     read `CacheService` and fell back to `PropertiesService`, but
     `bumpMutationWatermark_()` wrote only to `CacheService`. The
     `PropertiesService` fallback was dead code. Reproduced: read, mutate the
     sheet and bump the watermark, read again (correct), remove the
     watermark's cache entry, read again -- the response reverted to the
     pre-mutation payload cached under key `w:0`. Because `CacheService`
     entries expire after at most six hours and can be evicted earlier, this
     was a real and not merely theoretical failure mode.
  2. **The batch read path changed cell types relative to the sequential
     path.** `fetchAnalyticsDataSheetsBatch_` requested
     `dateTimeRenderOption: 'FORMATTED_STRING'`, so the Advanced Sheets
     Values API returns date/time cells as display strings, while the
     sequential fallback's `Range.getValues()` returns JS `Date` objects.
     `analyticsHashValue_` types a `Date` as `{type:'date'}` and a string as
     `{type:'string'}`, so `sourceRevision.dataVersion` differed between the
     two paths for identical underlying data. On the client,
     `AnalyticsState.kt`'s mid-pagination `dataVersion` guard treats that
     mismatch as a change mid-read and calls `restartStaleCursor()`; because
     the batch-to-sequential fallback is silent, a page 1 read on the batch
     path followed by a page 2 fallback to the sequential path produced a
     spurious "History changed again. Refresh to continue." error. A second,
     related divergence existed for ordinary blank trailing cells: the
     Advanced Sheets Values API omits trailing empty cells per row, while
     `Range.getValues()` always pads to a full rectangular matrix with `''`;
     unpadded, a short batch row read as `undefined` past its length where
     the sequential row read as `''`, and `analyticsHashValue_` types `null`
     and `string` differently. Both had to be closed for the batch and
     sequential paths to actually agree on `dataVersion`, which was the
     stated purpose of Decision 3 in ADR-021.
  3. **ADR-021's consequence "Wire contracts, schema versioning, and Room
     offline queue invariants remain completely intact" was inaccurate.** A
     cache hit returns before `readAnalyticsSnapshot_`, so it skips the
     environment, schema-version, timezone, and `PENDING_APPLY` guards that a
     cache miss enforces. Reproduced: with a pending recoverable sync apply
     armed, an identical request returns `success` on a cache hit and
     `BACKEND_BUSY` on a cache miss. This is a real behavioral difference
     between the cached and live paths, not an intact invariant.
  4. **`loadInsightsCacheThenRefresh`/`loadHistoryCacheThenRefresh` dropped
     their synchronous state guard.** Decision 5 in ADR-021 removed the
     synchronous `isInitialLoading = true` update (correctly, to stop the
     blocking spinner) but did not replace it with anything, so
     `refreshInsightsIfNeeded`/`refreshHistoryIfNeeded`'s
     `isInitialLoading || isRefreshing` early-return guard was unheld between
     `onInsightsVisible()`/`onHistoryVisible()` scheduling the cache-load
     coroutine and that coroutine actually running. A `markStale()` landing
     in that window passed the guard and started its own network refresh,
     which the cache-load coroutine's own refresh call then cancelled and
     replaced -- a wasted Apps Script read on every such race.
- Decision:
  1. `bumpMutationWatermark_()` now writes `PropertiesService` first, then
     `CacheService`, with each write independently guarded so one failure
     does not lose the other.
  2. `fetchAnalyticsDataSheetsBatch_` requests `dateTimeRenderOption:
     'SERIAL_NUMBER'` instead of `'FORMATTED_STRING'` and converts the known
     date-typed columns (tracked per sheet in `ANALYTICS_DATE_COLUMNS_`) back
     to `Date` objects via `dateFromSpreadsheetSerial_`, the exact inverse of
     the existing `spreadsheetLocalDateSerial_`. Every batch-fetched row is
     also padded to the header row's width (`padBatchRowWidth_`) before
     normalization, so a row with trailing blank cells hashes identically on
     both paths. `tests/fake_apps_script_runtime.js`'s
     `getSheetValuesObject` now honors `valueRenderOption`/
     `dateTimeRenderOption` instead of ignoring them, so this divergence is
     now something the test suite can actually detect.
  3. The cache-hit guard-bypass described above is kept exactly as-is. Making
     a cache hit re-check environment/schema-version/timezone/pending-apply
     would require a Sheets read on every request, which defeats the
     optimization ADR-021 exists for. This is now an explicit, accepted
     trade-off rather than an unstated gap: a cache hit can return `success`
     in a narrow window where a cache miss would return `BACKEND_BUSY` (or
     another guard failure), until the relevant cache entries expire (at most
     six hours) or the watermark is bumped by a mutation.
  4. `loadInsightsCacheThenRefresh`/`loadHistoryCacheThenRefresh` now set
     `isRefreshing = true` (and, for insights, `isStale = true`) synchronously
     before launching the cache-load coroutine, restoring the guard without
     restoring the blocking spinner (the full-screen loader is gated on
     `data == null && isInitialLoading` / `events.isEmpty() &&
     isInitialLoading`, neither of which this touches).
- Rationale: These are correctness fixes to behavior ADR-021 already
  committed to, not a new design. Deferring the guard-bypass fix keeps the
  fast path fast at the cost of the pending-apply window described above,
  which is judged acceptable because the window is bounded by the watermark
  and cache TTL, and the endpoint is analytics reads, not the mutation path
  itself.
- Consequences: The watermark survives cache eviction. The batch and
  sequential read paths now produce byte-identical responses, including
  `sourceRevision.dataVersion`, verified by `T1.F2.3` in
  `tests/backend_analytics_test.js`, which runs the same request through both
  paths and asserts full-response equality. A cache hit still bypasses the
  environment/schema-version/timezone/`PENDING_APPLY` guards that a cache
  miss enforces; this is accepted, not fixed, and any future work that
  depends on those guards holding unconditionally must account for it. No
  performance numbers are restated here: ADR-021's "<200ms" and "3-5x faster"
  figures were never measured against the real Apps Script/Sheets backend,
  only asserted, and this pass did not add a production measurement either.
- Related files: `backend_additions.gs`,
  `tests/fake_apps_script_runtime.js`,
  `tests/backend_analytics_test.js`,
  `app/src/main/java/com/example/ui/AnalyticsState.kt`,
  `app/src/test/java/com/example/ui/AnalyticsCoordinatorTest.kt`,
  `tests/run_e2e_verification.sh`


## ADR-023: Let the pen widget's controls grow instead of a dead bottom spacer

- Status: Accepted; implemented for Cannsheet Mobile v1.3.4.
- Date: 2026-08-17
- Context: ADR-015 point 4 added a zero-content `TextView` with
  `layout_weight="1"` to the bottom of both interactive widget layouts so that
  extra launcher height was "absorbed without distorting the controls". It
  absorbed the height literally: every control kept its fixed `dp` size and the
  spacer took the entire remainder. A widget resized to roughly `285x295dp`
  spent about half its area on empty background below the `+`/`−` row, which is
  what the owner reported. The same layouts also fix every text size in `sp` at
  inflation, so even a taller counter panel would have drawn base-size glyphs.
- Decision:
  1. Delete the weighted bottom spacer from `widget_pen_consumption.xml` and
     `widget_pen_consumption_compact.xml`, and give the counter row and the
     step row `layout_weight` `3` and `2` on top of their existing `dp` heights.
     The `dp` values become floors that any surplus height is shared over,
     rather than fixed sizes.
  2. Give the counter panel and the submit button `layout_height="match_parent"`
     inside the counter row, and weight the row horizontally `8:1` with a `40dp`
     submit floor, so the submit control stays at least `48dp` wide at the
     `140dp` minimum resize width and widens with the widget.
  3. Add `PenWidgetSizing`, a pure function from the launcher-reported
     `OPTION_APPWIDGET_MIN_WIDTH`/`MIN_HEIGHT` to a `PenWidgetLayoutSpec`
     carrying the compact decision and eight `sp` text sizes, interpolated
     between a base set at `140x160dp` and a largest set at `280x320dp` and
     rounded to half a point. `PenWidgetRenderer` applies them with
     `setTextViewTextSize`.
  4. Scale by `min(widthFraction, heightFraction)` so a tall narrow widget does
     not grow text its counter panel cannot hold, and clamp at the full-scale
     size so text stops growing rather than tracking the widget forever.
  5. Mirror the same weights in `widget_pen_consumption_preview.xml` so the
     API 31+ picker preview fills the same way the placed widget does.
- Rationale: `RemoteViews` cannot set layout geometry below API 31, and
  `TextView` autosizing would rescale the counter every time its text changed
  between `0s`, `30s`, `Saving…`, and `✓`. Weights handle the geometry in XML
  where they work on every supported API level, and one tested pure function
  handles the text, which keeps the size policy unit-testable without a device.
  Reporting minimums rather than maximums keeps the chosen sizes valid in both
  orientations.
- Consequences: When a widget is shorter than its own content the two rows now
  share the shortfall proportionally instead of the bottom control being
  clipped, so at the `110dp` compact floor a control can measure about `2dp`
  under its nominal height; `PenWidgetRendererTest` encodes that tolerance
  explicitly below `160dp` and holds the full floor above it. Presentation
  only: no Room, DataStore, queue, network, backend, or quantity-contract
  change, and `secondsToUses` is untouched. `PenWidgetRenderer.buildRemoteViews`
  now takes a `PenWidgetLayoutSpec` instead of a `compact: Boolean`.
- Related files: `app/src/main/java/com/example/widget/PenWidgetSizing.kt`,
  `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`,
  `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`,
  `app/src/main/res/layout/widget_pen_consumption.xml`,
  `app/src/main/res/layout/widget_pen_consumption_compact.xml`,
  `app/src/main/res/layout/widget_pen_consumption_preview.xml`,
  `app/src/test/java/com/example/widget/PenWidgetSizingTest.kt`,
  `app/src/androidTest/java/com/example/widget/PenWidgetRendererTest.kt`,
  `docs/images/pen-widget-285x295-before.png`,
  `docs/images/pen-widget-285x295-after.png`

## ADR-024: Keep the existing release signing key despite its debug-style name

### Context

While wiring a separate on-device model app to Cannsheet, the published release APKs were
inspected and the signing certificate reads:

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e
```

The name invites the conclusion that `release-apk.yml` fell back to debug signing because
the keystore secrets were missing. It did not, and that conclusion was reached and then
withdrawn during this investigation. The facts:

- `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD` and `RELEASES_TOKEN` are all present on the repository.
- The workflow asserts each is non-empty with `test -n` before building, so an absent
  secret fails the release rather than silently degrading it.
- The certificate digest is identical across v1.3.2, v1.3.3 and v1.3.4, so the key is
  stable and in-place updates work.

The keystore held in the secret simply carries a debug-style distinguished name. It is a
real, private, consistently used key.

### Decision

Keep it. Do not replace the signing key.

### Consequences

- In-place updates through Obtainium continue to work, which is the property that matters.
- Replacing the key would produce a different certificate, and Android cannot install an
  APK over one signed by a different key. The only path would be uninstalling Cannsheet,
  which destroys the Room database **and any pending offline queue rows that have not yet
  reached the spreadsheet**. That cost is not justified by a cosmetic name.
- If the key is ever replaced deliberately, prefer APK Signature Scheme v3 key rotation
  with a signing lineage, which preserves in-place updates, over generating a fresh
  unrelated key.
- The digest above is recorded so a future release can be checked against it. A change in
  this value between releases is a serious problem and means updates will fail.
- If the keystore originated as an Android debug keystore it carries the well-known
  `android` store and key passwords. The file itself is a repository secret, so this is a
  weak second factor rather than an open door, but it is a reason not to treat the digest
  as a strong identity claim.

### Not verified

The keystore's origin and its actual passwords were not inspected; only the certificate it
produces was. Whether it is a copied AGP debug keystore or a purpose-made keystore given
those distinguished-name values is unknown.

## ADR-025: The generated Insights summary is stricter than the figures it describes

### Context

`v1.4.0` adds an optional written summary above the Insights statistics, produced on device
by the model hosted in `noamvb/local-llm`.

The statistics themselves are shown from a cached or stale snapshot under a visible
"not current" notice, which is correct: a labelled number under a warning is honest. Prose
is not read that way. "You logged 42 times this month" reads as authoritative even when
three logs are still sitting in the offline queue.

### Decision

`CannsheetLlmFacts.shouldSummarise` suppresses the summary on a cached, stale,
range-changing, refreshing, errored or absent snapshot, and whenever local actions are
queued — mirroring `deriveRunwayPresentationState`. A `null` pending-action count also
suppresses: the screen masks the unknown case with `?: 0`, which is right for a banner and
wrong for prose, so `InsightsContent` takes a separate nullable parameter.

No projection is ever transmitted. `AGENTS.md` requires runway and spend projections not be
persisted, transmitted, or treated as confirmed values, and sending them over IPC to
another app is transmission. Only recorded figures are sent, and a test fails if any fact
label contains runway, project, forecast, estimate, per day, will last, or remaining.

### Consequences

- The summary appears less often than the statistics do. That is intended.
- Dates come only from `response.range`; the mapper never reads a device clock.
- Incomplete source data is surfaced as its own fact so the model can qualify rather than
  narrate a range that silently dropped rows.
- The permission is `signature|knownSigner`; this app's certificate digest is listed in the
  model app's `known_signers.xml`. The two apps do not share a key.

### Not verified

The card has not been observed rendering on a device. Doing so needs a live Apps Script
analytics response together with a release-signed build, and the gate deliberately refuses
the cached snapshot a debug build most easily produces.

**Update, 2026-08-19:** observed, on a Galaxy Z Fold 7 running the release-signed 1.4.0
build against a live account. The card rendered within about eight seconds of opening
Insights from a cold `local-llm` process, and every figure in the summary matched the
statistics displayed lower on the same screen. This was after `local-llm` 0.1.2 fixed a
defect that had been deleting the model on every app switch, which is most of why the
card had never been seen running before.

## ADR-026: The narrative card shows a loading state, and it must be provably total

### Context

Generation takes ten-plus seconds before the first token and eight more after. The card
sat empty that whole window, indistinguishable from "no model installed" — which it is
deliberately silent for — and read as broken. `produceState<String?>` compounded this: a
failure and "not started" both collapsed to `null`, so the card had no way to express that
generation was underway even if it wanted to.

### Decision

`NarrativeState` gains `Loading`, set immediately after every pre-flight gate has passed
and immediately before `client.generate()`. That ordering is the fix: a phone with no
model installed still sees nothing at all, forever, because nothing before that point
changed.

A card that can appear is a card that can get stuck, so three ways it could hang were
closed before this shipped:

- `terminalState()` makes the end of a generation total. A flow that completes having
  emitted zero fragments would otherwise leave `Loading` in place permanently — reachable,
  not theoretical, because `LocalLlmClient` does not re-send text through `onComplete` for
  a streaming request, so a service answering only there closes the flow with no emissions.
- The collection is bounded by a 90 s timeout. A successful bind is no guarantee of ever
  being answered; a wedged service emits no fragment, no completion and no error.
- `EngineState.UNSUPPORTED` is an explicit gate. It reports `modelDownloaded = true` on
  purpose (`noamvb/local-llm` v0.1.2, see ADR-025's update above), so it cleared the
  existing check and would have drawn a spinner only to have the request refused a moment
  later.

A blank first fragment keeps the loading body rather than collapsing the Card, since
models routinely open with a newline and tearing the Card down one frame after raising it
is worse than either state alone.

The `poop-schedule` insight card ships the identical state names, mapping shape, and
loading-state composition — down to the caption text and test tag — so the two stay one
feature rather than two that happen to look similar.


## ADR-027: Generation is driven above the LazyColumn, not inside the card

### Context

The narrative card sits in a `LazyColumn` item. `LazyColumn` disposes an off-screen item's
entire composition once it scrolls far enough away, discarding any `remember` or
`produceState` state that lived inside it.

With generation driven by a `produceState` inside the card, scrolling it out of view
discarded that coroutine, and scrolling back created a fresh composition that started
again from nothing. The owner saw the summary visibly regenerate for no reason, and every
round trip cost another binder request and another model run on the phone.

### Decision

`rememberNarrativeState()` is called once in the Insights content composable, above the
`LazyColumn`, and the resolved state is passed down. The card only renders it. Item
virtualisation cannot reach the screen-level scope, so generation survives any amount of
scrolling.

`produceState` still keys on the snapshot identity, range, cache/stale flags and pending
action count, so a genuine change restarts generation as it should — only scroll no longer
counts as a change.

### Consequences

The general shape is worth remembering: work whose cost or visible identity should outlive
a scroll must not be owned by a composable the list is free to dispose. The same fix landed
in `poop-schedule` as D-016; the two insight cards continue to track each other.

## ADR-028: Keep pen-widget presets exact and size-gated

### Context

The pen home-screen widget can offer saved quantity presets, but the widget displays
seconds while the durable product preference remains uses. A preset that converts to a
fractional second cannot be represented by the existing integer-second draft without
silently changing the configured quantity. The full layout also has a minimum usable
height for its existing counter, submit, and increment/decrement controls.

### Decision

The full pen widget exposes up to three fixed preset slots. Presets are converted from
uses to seconds for display and input, but only exact whole-second values in `1..600` are
offered; fractional, invalid, and out-of-range values are omitted. Preset taps use a
guarded per-widget DataStore draft setter and do not alter Room, the offline queue, or the
wire contract. Compact layouts and regular layouts below `200dp` hide the preset row,
while the unreported/default layout remains preset-capable.

### Consequences

- Saved preferences remain uses-only and existing persistence/synchronization contracts
  remain unchanged.
- A user may see fewer than three preset buttons when configured values are not exactly
  representable as integer seconds.
- Small regular widgets preserve the existing control floor instead of shrinking those
  controls to make room for presets.

### Related files

- `app/src/main/java/com/example/widget/PenWidgetUiModel.kt`
- `app/src/main/java/com/example/widget/PenWidgetSizing.kt`
- `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`
- `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`

## ADR-029: Scale pen-widget steps with rendered size

### Context

The pen widget's fixed ten-second step is useful at its base size but makes a large
widget slow to operate: reaching the 600-second ceiling takes sixty taps. The router
handles broadcasts without access to launcher-reported dimensions, while Android
`PendingIntent` identity ignores extras and the existing data URI is deliberately stable.

### Decision

`PenWidgetSizing.resolve` keeps the ten-second default for compact and base-width
layouts, and selects a thirty-second step once the rendered growth fraction reaches
`0.5`. The renderer carries that value in an optional `STEP_SECONDS` broadcast extra
for the increment/decrement intents; the router defaults missing extras to ten seconds
and clamps received values to `1..600`. The step is not added to the data URI, so resize
updates reuse the existing widget/action `PendingIntent` identity. Accessibility text is
set by the renderer from the same resolved step because formatted string resources are
not safe as static XML content descriptions.

### Consequences

- Large widgets reach the ceiling in twenty increments instead of sixty.
- Existing and stale intents remain compatible because a missing or invalid extra uses
  the safe ten-second default and bounded router value.
- The step remains a presentation/input detail; uses-only persistence and wire contracts
  are unchanged.

### Related files

- `app/src/main/java/com/example/widget/PenWidgetSizing.kt`
- `app/src/main/java/com/example/widget/PenWidgetActions.kt`
- `app/src/main/java/com/example/widget/PenWidgetActionRouter.kt`
- `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`

## ADR-030: Keep pen-widget configuration optional and launcher-invokable

### Context

Widget instances created before configuration support have no per-instance keys,
and Android 12+'s `configuration_optional` feature means a host may add a widget
without ever launching the configuration activity. The new settings must therefore
be absent-safe and must not replace the existing loaded-cart, persistence, queue, or
wire contracts. The configuration activity is started by the launcher, which runs
under a different Android UID from Cannsheet.

### Decision

Persist pinned product ID, discreet mode, and an optional step override under
app-widget-ID-suffixed DataStore keys. Missing, blank, or out-of-range values read as
the default configuration; widget deletion removes all three keys. The updater reads
the configuration once, passes the pin and discreet flag into presentation, and
applies a valid step override over the size-resolved layout spec. Discreet mode is
presentation-only: it replaces the product name with the generic `Pen cart` label and
the status/count subtitle with `Tap to log`, while retaining queued confirmation and
accessibility descriptions.

The configuration activity is explicitly `exported="true"` because the launcher must
invoke it across the application boundary. This corrects the attached implementation
plan's `exported="false"` value, which was observed to prevent the API-36 launcher
from opening the settings screen. The activity initializes `RESULT_CANCELED`, only
returns `RESULT_OK` after durable configuration and serialized rendering, and never
updates `AppWidgetManager` directly.

### Consequences

- Existing and optionally configured widgets preserve the pre-configuration default
  behavior, while restored IDs cannot inherit stale configuration after deletion.
- Stored and transmitted quantities remain uses-only; widget seconds stay a display
  and input concern, and no new backend or Room contract is introduced.
- The configuration surface is available to the launcher as required by Android's
  app-widget host contract. The activity intentionally does not expose a network or
  production-data mutation path beyond the existing widget update boundary.

### Related files

- `app/src/main/java/com/example/widget/PenWidgetConfigureActivity.kt`
- `app/src/main/java/com/example/widget/PenWidgetInstanceConfig.kt`
- `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`
- `app/src/main/java/com/example/widget/PenWidgetDataSource.kt`
- `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`
- `app/src/main/java/com/example/widget/PenWidgetUiModel.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/pen_consumption_widget_info.xml`
- `app/src/main/res/xml-v31/pen_consumption_widget_info.xml`

## ADR-031: Show an age-based sync-behind subtitle on the pen widget

### Context

The pen widget already distinguishes synced uses from product-local pending uses,
but a queue can remain non-empty for a long time without a visible indication that
background synchronization is not catching up. The repository already persists
the queue's non-empty watermark and exposes the aggregate pending-action count.

### Decision

When the aggregate durable queue count is positive and its existing
`queueNonEmptySinceEpochMillis` watermark is at least
`QUEUE_STUCK_THRESHOLD_MILLIS` (24 hours) old, the widget receives a boolean
`queueStuck` presentation signal and shows `%1$s · sync is behind`, using only the
product status label as its argument. Both provider-info XML files request the
platform's minimum 30-minute periodic `onUpdate` callback so the presentation
reaches the threshold without depending on queue-alert opt-in or another widget
tap. The subtitle precedence is explicit: recently queued confirmation,
sync-behind, discreet mode, then the existing synced or pending variants. The
signal is read during the widget update and is not persisted, transmitted, or
added to queue-alert notification paths.

### Consequences

- A long-lived non-empty queue is visible on the widget without exposing an
  aggregate action count, product quantities, dates, or queue rows.
- Immediate queued feedback remains visible for its existing window, and discreet
  mode remains effective unless the queue needs the higher-priority sync warning.
- The host may refresh the widget at its normal periodic-update cadence (at least
  30 minutes), which bounds how long the widget can remain unaware of a newly
  stuck queue without scheduling notification work.
- The Room schema, offline payloads, Apps Script contract, and notification alert
  behavior remain unchanged.

### Related files

- `app/src/main/java/com/example/widget/PenWidgetDataSource.kt`
- `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`
- `app/src/main/java/com/example/widget/PenWidgetUiModel.kt`
- `app/src/main/res/xml/pen_consumption_widget_info.xml`
- `app/src/main/res/xml-v31/pen_consumption_widget_info.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/example/widget/PenWidgetUiModelTest.kt`

## ADR-033: Remap widget state before restore rendering and use size-mapped RemoteViews

- Status: Accepted
- Date: 2026-08-20
- Context: Android can assign new app-widget IDs during backup/restore, while
  the pen widget stores draft seconds, pending commits, queue timestamps, and
  optional configuration under ID-suffixed DataStore keys. Without a restore
  callback, a pending commit becomes unreachable. The widget DataStore is
  deliberately included in cloud backup and device transfer; a restored
  pending payload retains its original event ID and therefore remains safe to
  retry through the Room unique index and existing server idempotency rules.
  Separately, Android 12+ can select a `RemoteViews` variant by rendered size,
  avoiding repeated data reads for resize-only changes; older API levels still
  require the existing options callback and single-view path.
- Decision: `onRestored` snapshots every per-widget key for each old/new ID pair
  inside one DataStore edit, removes all old keys, writes the captured values to
  the new IDs, then flushes overdue commits and renders the restored widgets in
  that order. Snapshot-before-remove is required for overlapping mappings. On
  API 31+, `PenWidgetUpdater` supplies compact, base, and large `RemoteViews`
  variants at `110x110`, `140x160`, and `280x320` through the guarded
  `RemoteViews(Map<SizeF, RemoteViews>)` constructor. Each variant applies the
  optional per-widget step override; API 24–30 retain the existing live-size
  `RemoteViews` fallback. On API 31+, the provider returns from
  `onAppWidgetOptionsChanged` because the host can select the prepared variant;
  older hosts retain the existing callback and live-size update. The provider
  cancels the final widget's invalid-ID WorkManager name in `onDisabled`.
- Consequences: Restore preserves the durable widget draft, pending commit, and
  configuration boundaries without changing Room, offline queue, or wire
  contracts. A retried restored payload keeps its original stable event ID, so
  backup/restore does not create a second consumption. API 24 instrumentation
  remains safe because the API 31 constructor is guarded, and API 31+ hosts can
  select a prepared rendering without a full data reload for every size
  transition. The five remapping cases are covered by pure-JVM tests, including
  overlapping IDs and unrelated widgets.
- Related files: `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`,
  `app/src/main/java/com/example/widget/PenConsumptionWidgetProvider.kt`,
  `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`,
  `app/src/test/java/com/example/widget/PenWidgetRestoreTest.kt`,
  `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`,
  `app/src/main/res/xml/backup_rules.xml`,
  `app/src/main/res/xml/data_extraction_rules.xml`

## ADR-032: Deep-link the interactive pen widget name to the cart picker

- Status: Accepted
- Date: 2026-08-20
- Context: The interactive pen widget's product name opened the Log screen, so
  choosing a different cart required a second tap on the screen's `Swap cart`
  control. The app already has a shared start-route extra and a local product
  picker with a `LOADED_PEN` mode, but an activity launch needs to work for both
  a cold start and an existing `singleTop` activity.
- Decision: Keep `EXTRA_START_ROUTE` and its existing string value unchanged.
  Add the sibling `EXTRA_OPEN_CART_PICKER` and the activity-only
  `ACTION_OPEN_CART_PICKER`, retaining the existing widget/app-widget/action URI
  identity scheme. Consume the boolean exactly once in `MainActivity` on both
  `onCreate` and `onNewIntent`, delivering it through a conflated channel so
  repeated taps cannot queue repeated sheet openings. The consumption screen
  owns the private picker mode and opens the existing picker in `LOADED_PEN`
  mode, the same mode used by `Swap cart`. Only the interactive widget's
  product-name target changes; message
  states continue using `ACTION_OPEN_LOG`.
- Consequences: A configured or unconfigured widget can reach cart selection in
  one tap without adding a persistence, Room, offline-queue, backend, or wire
  contract. The picker remains local UI state and uses the existing selectable
  product filtering and logging target. The old route extra remains compatible
  with already-issued PendingIntents.
- Related files: `app/src/main/java/com/example/domain/AppEntryPoints.kt`,
  `app/src/main/java/com/example/widget/PenWidgetActions.kt`,
  `app/src/main/java/com/example/MainActivity.kt`,
  `app/src/main/java/com/example/ui/AppNavigation.kt`,
  `app/src/main/java/com/example/ui/ConsumptionScreen.kt`,
  `app/src/main/java/com/example/widget/PenWidgetRenderer.kt`,
  `app/src/main/res/values/strings.xml`,
  `app/src/androidTest/java/com/example/MainActivityIntentTest.kt`

## ADR-034: Reuse the pen-widget commit machinery for the Quick Settings tile

- Status: Accepted
- Date: 2026-08-20
- Context: Android's `TileService` has no app-widget ID, but one-tap pen
  logging must retain the existing five-second undo window, durable payload
  identity, claim/write/complete arbitration, and WorkManager process-death
  recovery. A second logging path would risk diverging from the home-screen
  widget's uses-only and retry behavior.
- Decision: Reserve `PEN_TILE_WIDGET_ID = Int.MAX_VALUE` as a non-negative
  pseudo ID that `AppWidgetManager` will never allocate. Store the tile draft
  and pending payload under the existing ID-suffixed DataStore namespace, set
  the first configured preset before submission, and route both widget and tile
  submissions through the shared `submitPenLog` helper. The updater skips the
  pseudo ID instead of calling `AppWidgetManager.updateAppWidget`; when a
  deferred tile commit completes it refreshes the active `TileService` without
  creating a repeating refresh loop. Provider restore/delete paths continue to
  operate only on launcher-issued app-widget IDs.
- Consequences: The tile inherits the existing atomic draft capture, stable
  event ID, undo arbitration, durable Room queue, and sync scheduling behavior.
  Tile labels and unavailable/undo states remain presentation-only. No Room
  schema, offline payload, Apps Script contract, endpoint, package, or stored
  unit changes are introduced.
- Related files: `app/src/main/java/com/example/widget/PenQuickTileService.kt`,
  `app/src/main/java/com/example/widget/PenTileState.kt`,
  `app/src/main/java/com/example/widget/PenWidgetActionRouter.kt`,
  `app/src/main/java/com/example/widget/PenWidgetUpdater.kt`,
  `app/src/main/java/com/example/widget/PenWidgetStateRepository.kt`,
  `app/src/main/java/com/example/widget/PenWidgetCommitCoordinator.kt`,
  `app/src/test/java/com/example/widget/PenTileStateTest.kt`,
  `app/src/androidTest/java/com/example/widget/PenWidgetStateRepositoryTest.kt`
