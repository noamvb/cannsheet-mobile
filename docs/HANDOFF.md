# Latest handoff

Last updated: 2026-07-29

Branch: `codex/history-corrections-android`

Repository position:

- `origin/main` is
  `621f9801f907dde9d5315cb5f261bbfc3407f868`, the squash merge of backend
  [PR #19](https://github.com/noamvb/cannsheet-mobile/pull/19);
- Android commit `ffbec5a` opened draft
  [PR #20](https://github.com/noamvb/cannsheet-mobile/pull/20), and this handoff
  accompanies its approved test-only CI follow-up;
- the latest released source tag is `v1.2.16` at `7ee3f3a`; and
- version metadata remains `versionName` `1.2.16`, `versionCode` `19`.

The Android implementation is committed and pushed. Draft PR #20 is open.
No merge, production change, version change, tag, signed APK, or publication
has been made.

Approximate delivery position: 87% toward a downloadable updated APK. CI built
and uploaded a temporary debug APK, but no signed installable update has been
prepared.

## Purpose of this session

Continue the approved editable-consumption-History feature after its backend
foundation: merge and prove the backend in the live sandbox, then implement the
Android Room queue, sync contract, ViewModel behavior, and Correct/Void/Restore
History UI without touching production or release metadata.

## Completed backend and sandbox milestones

- Backend PR #19 was merged to `main` at `621f980`.
- Its final GitHub Actions run, `30499192644`, passed all five required checks.
- The checked-in merged Apps Script source was copied to the verified
  `Cannsheet Sandbox Backend` bound project and normalized source equality was
  confirmed.
- `provisionSandbox()` completed successfully and created the additive
  `ConsumptionEventCorrections` sheet.
- The existing sandbox web-app deployment was updated in place from version 12
  to version 13. Its deployment identity, execution owner, and public access
  setting were preserved.
- A live sandbox capability read returned environment `SANDBOX`, API v2,
  correction schema version 1, and correction writes enabled.
- A focused live `VOID` proof committed revision 1; an identical retry returned
  `duplicate`; History showed lifecycle `VOIDED`, the matching correction head,
  and one audit revision.
- `resetSandboxData()` then completed. Final sandbox History contained the five
  seeded events in `ORIGINAL` state, revision 0, with no correction heads or
  audit revisions.
- A `PRODUCTION`-labelled request to the sandbox was rejected with
  `ENVIRONMENT_MISMATCH`.

Production was not opened, provisioned, reconciled, deployed, or enabled.

## Current uncommitted Android implementation

### Persistence and retry safety

- Room is advanced from schema 8 to 9 with an additive
  `pending_consumption_corrections` table and a tested `MIGRATION_8_9`.
- The existing `sync_request_state` gains a non-null payload fingerprint with an
  empty migration default; existing request rows are preserved.
- A pending correction stores a stable action UUID, target event UUID, expected
  correction head, operation, optional reason, optional product-reopen request,
  and the complete immutable replacement snapshot when applicable.
- The target event is the queue primary key and the action ID is unique.
  Enqueue uses `ABORT`, so a second correction cannot silently replace the first.
- The repository exposes pending-correction flows and explicit enqueue, lookup,
  list, and user-confirmed cancel operations.
- An unchanged queue snapshot reuses its request UUID after an unknown network
  outcome. A changed snapshot receives a new request UUID.
- A correction row is removed automatically only when a successful response
  contains `committed` or `duplicate` for the exact sent
  `(actionId, targetEventId)` pair. Rejections remain queued.

### Network and analytics contract

- Sync API v2 carries `consumptionCorrections` and parses capability,
  acknowledgement, rejection, stale-head, and current-head fields.
- The Android reason limit matches the merged backend limit of 200 characters;
  no silent truncation is used.
- History now requests analytics contract v2 and parses lifecycle, head,
  revision, reopen eligibility, capability, source-revision, and optional audit
  DTOs with safe defaults. Ordinary page requests omit full audit revisions so
  paginated payloads and the 200-entry cache remain bounded.
- Version-1 acknowledgement compatibility remains available for ordinary
  queues, while correction requests require a matching version-2 request ID.
- A successful audited partial response clears its request identity after local
  acknowledgement processing, so a smaller remaining payload cannot reuse the
  prior audited request ID.

### User-facing History behavior

- Editing is available only from a fresh, non-cached History snapshot whose
  backend advertises correction schema version 1 and enabled writes.
- An entry with a local pending correction cannot receive another correction.
- Tapping a History entry exposes:
  - `Correct` for an effective entry;
  - `Void` for an effective entry; and
  - `Restore` for a voided entry.
- Correct pre-fills date, time, product, quantity, and finished state. It
  validates canonical UUIDs, a real calendar date, a valid time shape, positive
  quantity, and the 200-character reason boundary.
- Void and Restore require confirmation and preserve the original event.
- Product reopening is offered only when the backend says it is eligible and
  the proposed correction removes the current finish marker.
- Plain-language messages cover offline pending state, exact server acceptance,
  stale-head conflict, unsafe reopen, disabled capability, and unavailable
  backend.
- A pending correction can be cancelled only through an explicit confirmation.
  Cancellation is serialized with sync so it cannot race an active request.
- History is marked stale after accepted changes; the existing coordinator
  performs one refresh when History is visible.

## Files changed

Modified tracked files:

- `app/src/androidTest/java/com/example/data/DatabaseMigrationTest.kt`
- `app/src/androidTest/java/com/example/ui/HistoryContentTest.kt`
- `app/src/main/java/com/example/data/AnalyticsData.kt`
- `app/src/main/java/com/example/data/Database.kt`
- `app/src/main/java/com/example/data/Network.kt`
- `app/src/main/java/com/example/data/Repository.kt`
- `app/src/main/java/com/example/data/SyncQueueLogic.kt`
- `app/src/main/java/com/example/ui/AnalyticsState.kt`
- `app/src/main/java/com/example/ui/CannsheetViewModel.kt`
- `app/src/main/java/com/example/ui/InsightsScreen.kt`
- `app/src/test/java/com/example/data/SyncQueueLogicTest.kt`
- `docs/HANDOFF.md`

Untracked task tests:

- `app/src/test/java/com/example/data/ConsumptionCorrectionMappingTest.kt`
- `app/src/test/java/com/example/ui/HistoryCorrectionUiTest.kt`

No version, endpoint, application ID, namespace/package, signing, credential,
secret, backend, workflow, or release file is changed in this Android worktree.

## Validation performed

### Passing evidence

- `git -c core.fsmonitor=false diff --check` exited 0 before this handoff update.
- Android JVM compilation reached the test runner and generated reports for 12
  test classes: 49 tests, zero failures, zero errors, and zero skipped.
- The new passing JVM coverage includes:
  - correction DTO mapping and immutable replacement validation;
  - exact action/target acknowledgement matching;
  - retained structured conflicts and unsafe-reopen rejections;
  - stable retry fingerprint and changed-payload fingerprint behavior; and
  - History capability, lifecycle action, reason, product identity, and reopen
    UI rules.
- A read-only Terra/medium verification pass found no P0 or P1 defect in the
  combined Android diff.
- A Sol/high architecture pass confirmed there is no safe existing single-event
  audit query. It recommended keeping full audit revisions out of ordinary
  History pages until a bounded, non-caching detail endpoint exists.
- Draft PR #20 run `30503688027` passed:
  - repository/security classification;
  - all backend suites;
  - Android JVM tests and instrumentation compilation;
  - Android lint and debug APK assembly; and
  - debug APK artifact upload.
- The API 24 emulator ran 22 instrumentation tests. Twenty passed. The two
  failures were `HistoryContentTest` assertions for an event UUID and the
  Correct button while those nodes were below the visible part of the new
  scrollable sheet.
- The approved follow-up uses Compose `performScrollTo()` before checking each
  off-screen node. That method is already used by the existing instrumentation
  suite, and the focused diff passes `git diff --check`.

The Gradle invocation was:

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest
```

The command wrapper timed out after 124 seconds without returning Gradle's final
exit code. The Gradle process continued and subsequently produced complete XML
reports showing the 49/0/0/0 result above. Treat the test reports as passing
evidence, but do not claim that the wrapper itself returned success.

### Earlier blocked attempts

The implementation agents' focused Gradle attempts were blocked first by
missing local SDK configuration and then by restricted SDK access/timeouts.
They did not run a second broad test matrix. A local ignored `local.properties`
was added for the final parent validation.

## Validation not performed

- `assembleSandbox` was not run.
- The approved `HistoryContentTest` scroll fix has not yet passed its follow-up
  API 24 CI run.
- No manual emulator or physical-device visual check was performed.
- No screenshot or recording was captured for the visible UI change.
- No Android build was installed against the live sandbox deployment.
- Direct local ADB/emulator discovery was blocked by local access restrictions.
  The approved elevated route was then unavailable because the Codex execution
  credit limit was reached, so it was not retried or worked around.
- The full backend test matrix was not repeated during the Android milestone;
  backend PR CI and the completed live sandbox proof are the relevant evidence.
- No production or release validation was attempted.

## Independent review findings and residual risks

1. Resolved design finding: unconditional `includeAudit=true` would make every
   page and cached event grow with an unbounded correction chain. The Android
   page shows lifecycle and revision number, while the full audit remains in the
   backend/Sheet. A future visible trail requires a single-event detail API.
2. P2: the migration test creates a reduced hand-written version-8 database and
   invokes `MIGRATION_8_9`; it does not perform full Room schema-parity
   validation against an exported version-8 schema. Emulator execution is still
   required.
3. The backend is the final strict validator for New York wall-clock time,
   including nonexistent daylight-saving gap times. Such a rejection remains
   queued and visible rather than being discarded.
4. `docs/PROJECT_STATE.md`, `docs/DECISIONS.md`, and `docs/ARCHITECTURE.md` now
   describe the Android milestone and bounded audit-loading decision.
5. Ordinary production entries continued during this work. Any future
   production provisioning, reconciliation, deployment, and write enablement
   must acquire the script lock and read fresh state; do not rely on stored row
   counts.

## Safety review

- The 13 Android source/test paths were scanned for secret-like patterns and
  personal absolute paths; none were found.
- No staged changes exist.
- No unrelated source change was found.
- Ignored `app/build/` output and ignored `local.properties` exist locally and
  are not part of the Git diff.
- No generated APK, database, log, credential, token, keystore, or local
  endpoint property is included in the working tree changes.

## Recommended next action

Commit and push the focused `HistoryContentTest` scroll fix, then monitor the
one resulting PR run. Do not manually rerun already-passing jobs. If API 24 and
the aggregate pass, update the PR validation summary and request the separate
merge, production-rollout, and release approvals as needed. The debug artifact
is validation evidence, not the signed production update.

Do not provision or enable production and do not bump the version, tag, sign,
or publish an APK without the user's separate approval.
