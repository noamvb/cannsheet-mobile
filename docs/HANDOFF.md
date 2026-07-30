# Latest handoff

Last updated: 2026-07-29

Branch: `codex/typed-column-cosmetic-fallback`

Repository position:

- `origin/main` is
  `76638fb8d84ef38f0c24f39fd2e4c12cca0efe6e`, the squash merge of focused
  [PR #22](https://github.com/noamvb/cannsheet-mobile/pull/22);
- [PR #21](https://github.com/noamvb/cannsheet-mobile/pull/21) skips
  unsupported cosmetic formatting on the typed `Purchases` header and requires
  a full recoverable reconciliation before correction writes can be enabled;
- PR #22 adds exact Advanced Sheets table detection before the header
  formatting/frozen-row operations;
- the latest released source tag is `v1.2.16` at `7ee3f3a`; and
- version metadata remains `versionName` `1.2.16`, `versionCode` `19`.

The Android implementation, PR #21, and PR #22 are merged. Exact-source
production retries still exposed a typed-column restriction in the shared
cosmetic header path and were contained as described below. Production remains
in an additive, disabled-only partial state and its deployment is unchanged. No
version change, tag, signed APK, or publication has been made.

Approximate delivery position: 97% toward a downloadable updated APK. CI built
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

## Production rollout containment snapshot

On 2026-07-29, the production spreadsheet, linked Form, bound Apps Script
project, existing web deployment, access mode, and triggers were verified
before mutation. A fresh workbook backup and source/manifest rollback material
were captured.

The first `provisionConsumptionCorrections()` attempt made only additive,
disabled-state changes before stopping:

- `ConsumptionEventCorrections` exists with the exact expected header and zero
  correction rows;
- `CONSUMPTION_CORRECTION_SCHEMA_VERSION` is `1`;
- `CONSUMPTION_CORRECTION_WRITES_ENABLED` is `false`; and
- provisioning stopped when Google Sheets rejected cosmetic header formatting
  on typed `Purchases` columns.

A full post-failure recoverable reconciliation reported no pending apply, zero
incomplete journals, and no differences or blocking differences. The observed
raw row counts are only a point-in-time snapshot and must be read fresh because
ordinary production entries continue during this work.

The Apps Script editor source was restored byte-for-byte to the captured
pre-attempt source. The same public deployment remains on version 11, and no
valid production correction request was sent. Production correction writes
remain disabled.

After PR #21 merged, a second disabled-first attempt passed the table-header
formatting operation but stopped when Google Sheets rejected
`setFrozenRows(1)` on the typed `Purchases` table. Live metadata confirms that
`Purchases` already has one frozen row, so this was an unsupported attempt to
reapply an existing state, not a missing safety setting.

The editor source was immediately restored to the same exact prior hash again.
The correction sheet remained header-only, schema version remained `1`, writes
remained `false`, and a fresh full reconciliation remained clean while ordinary
production entries continued. No deployment or correction request followed
the failed attempt.

PR #22 then used the exact `Purchases` sheet ID and Advanced Sheets `tables`
metadata before correction provisioning. A first retry from that source still
stopped at the shared header-formatting line. The editor was immediately
restored to its captured prior source and a fresh full reconciliation was
clean.

Read-only live probes then established all of the following:

- the exact PR #22 field mask returned the `Purchases` table;
- the exact merged PR #22 helper returned `true`;
- the sheet name and ID matched the expected `Purchases` sheet; and
- of the sheets touched by the shared header-safety loop, only `Purchases` was
  reported as a Google Sheets table.

One final controlled retry used the exact saved PR #22 source after
`cloud_done`, a complete editor read-back, normalized source hash verification,
and a fresh clean reconciliation. It still stopped at the same shared cosmetic
formatting line. No further retry was made.

After that stopped attempt, the editor was restored byte-for-byte to rollback
hash `f46c958296c0fb9f81d99bcc6103dee59d3e359e5e03fae9a1c3218e50609521`.
The correction sheet remained header-only, schema version remained `1`, writes
remained `false`, and the post-failure full reconciliation again reported no
pending apply, incomplete journal, difference, or blocking difference. The
public deployment remains version 11 and no valid production correction request
has been sent.

The current focused follow-up removes table metadata from the cosmetic control
path. Only the exact Google typed-column restriction is tolerated around header
styling and frozen-row setup; all schema, validation, protection,
configuration, and reconciliation failures remain fatal.

## Merged Android implementation

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

- `backend_additions.gs`
- `tests/backend_corrections_test.js`
- `tests/fake_apps_script_runtime.js`
- `docs/HANDOFF.md`
- `docs/PROJECT_STATE.md`

There are no task-related untracked files. No Android source, version, endpoint,
application ID, namespace/package, signing, credential, secret, workflow, or
release file is changed on this focused branch.

## Validation performed

### Passing evidence

- Terra/high ran `node tests/backend_corrections_test.js` once after each of its
  two substantive test revisions; the final source reported
  `backend correction tests passed`.
- Terra/medium ran each complementary check once on the final source:
  - `node tests/backend_recovery_test.js`;
  - `node tests/fake_sheets_batch_update_test.js`;
  - `Get-Content -Raw backend_additions.gs | node --check`; and
  - `git -c core.fsmonitor=false diff --check`.
  All four passed, and the verifier found no P0, P1, or P2 issue.
- Sol/high approved the exact-error-only cosmetic fallback and required all
  validation, protection, schema, configuration, and reconciliation failures
  to remain fatal.
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
- Follow-up run `30504243649` again passed classification, backend validation,
  Android compilation/JVM/lint/debug assembly, and artifact upload. Its API 24
  suite again passed 20 of 22 tests: `performScrollTo()` completed, but the same
  modal-sheet nodes did not satisfy `assertIsDisplayed()`.
- The final approved adjustment keeps the visible timestamp assertion, checks
  lower details with `assertExists()`, and requires Correct/Void to exist with
  click actions. The focused diff passes `git diff --check`.
- Run `30505232031` stopped at `compileDebugAndroidTestKotlin` with only
  `Unresolved reference 'assertExists'` on the import line. Local inspection of
  the resolved Compose test library confirms `assertExists()` is a
  `SemanticsNodeInteraction` member, so the approved fix removes only that
  import.

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
- No manual emulator or physical-device visual check was performed.
- No screenshot or recording was captured for the visible UI change.
- No Android build was installed against the live sandbox deployment.
- Direct local ADB/emulator discovery was blocked by local access restrictions.
  The approved elevated route was then unavailable because the Codex execution
  credit limit was reached, so it was not retried or worked around.
- The full backend test matrix was not repeated during the Android milestone;
  backend PR CI and the completed live sandbox proof are the relevant evidence.
- Production validation stopped after the final contained exact-source
  provisioning failure described above. No successful production provisioning,
  deployment, enablement validation, or release validation has been completed.

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

Complete and independently review the focused cosmetic-fallback change, run its
single focused regression suite, open a focused PR, resolve every review thread,
and require green CI on the exact final head before merge. Only after that merge
may idempotent production provisioning be attempted once from the exact saved
source. If it succeeds, reconcile fresh, update the same deployment in place,
verify its disabled state, reconcile again, and only then enable and verify
correction writes.

The user has approved this contained production rollout. Version changes,
tagging, signing, and APK publication remain a separate approval gate.
