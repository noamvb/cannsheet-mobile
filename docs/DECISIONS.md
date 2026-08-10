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

- Status: Accepted; backend merged and sandbox verified, Android integration
  implemented in a working branch, production rollout pending
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

- Status: Accepted for feature-branch implementation; not merged, released, or
  deployed
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
  separate Room or Retrofit synchronization paths. The feature requires Android
  scheduling and device validation before any claim about background execution,
  spreadsheet writes, or user-visible behavior.
- Related files: `app/src/main/java/com/example/CannsheetApplication.kt`,
  `app/src/main/java/com/example/data/SyncPreferencesRepository.kt`,
  `app/src/main/java/com/example/data/sync`,
  `app/src/main/java/com/example/ui`, `app/build.gradle.kts`,
  `docs/ARCHITECTURE.md`



