# Cannsheet backend sync performance report

Status: implementation complete. All sandbox gates pass, the normal sandbox
baseline is restored, production version 8 is live on the unchanged endpoint,
and the first genuine production sync has been verified end to end.

## Phase 1 - authoritative state and backups

### Local/source state

- Authoritative repository: `noamvb/cannsheet-mobile`
- Branch at intake: `main`
- Fetched `HEAD` and `origin/main`: `488add9228cdd627957035e94417dd6d2ee8889c`
- Commit: `Release Cannsheet Mobile 1.2.4`
- Working branch: `backend-sync-performance`
- `backend_additions.gs` SHA-256 at intake: `6779BDF52E8801417A0496E87C7BE9A2C57A7FAADE459FFC6E0A9703478B1D2C`
- Backend API/schema: `2` / `2`
- Android application/version: `com.noamv.cannsheet.mobile`, version code `7`, version name `1.2.4`
- Initial syntax and contract checks passed.

### Live production state at intake

- Spreadsheet: `CannsheetG` (`1CCHCdliNsHx3LSPvHrXqGR2SWGhqwruHdAXaEuzmPKQ`)
- Apps Script project: `Cannsheet Backend` (`1C_I7_vWIuZoxQN3ZR3iAcNWq0-X3aJj4cS1EHbk2nW6yJT2dVfgy3vA2`)
- Active deployment: version `7`, ID `AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`
- Existing `/exec` URL is unchanged.
- Execution/access: deploying user / anyone.
- Phase 1 rollback target at intake: version `6` on the same deployment ID.
- Script Properties: production environment, production Sheet ID, production Form ID.
- Triggers: exactly `onInventoryEdit` (spreadsheet edit) and `onFormSubmit` (spreadsheet form submit).
- Live `Code.gs` exactly matched the intake repository hash.

### Fresh production reconciliation

| Metric | Value |
| --- | ---: |
| Purchases | 327 |
| Active / finished / unopened | 8 / 319 / 0 |
| Compatibility/Form rows | 3,543 |
| Canonical events | 3,542 |
| Unique Event UUIDs | 3,542 |
| Duplicate Event UUIDs | 0 |
| Purchases total Uses | 5,332.51 |
| SyncLedger rows | 19 |
| MigrationReport unresolved rows | 0 |

The intake audit found a pre-existing interrupted projection for `*J127`: compatibility row 3524 has one use that is absent from canonical history and Purchases effects. It also found a separate off-by-one lineage pointer between response row 3534 and canonical row 3531. These were not mutated during intake. The recovery design and production migration must reconcile them after sandbox approval.

### Backups

- Fresh spreadsheet copy: `CannsheetG Production Backup 2026-07-14 20-42-22 EDT - before backend sync performance` (`13jgXvSisHoZVIJB9j2_r5jwDP_6f7nJSE1vE5Tcs1_M`)
- Fresh local production source/manifest backup:
  `C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-14\backend-sync-performance\production`
- Fresh local sandbox source backup:
  `C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-14\backend-sync-performance\sandbox\before-baseline`

These intake backups were superseded by a second fresh backup taken immediately
before the Phase 9 production promotion.

## Historical production timing evidence

The 19 existing production `SyncLedger` rows were read without adding a new probe:

| Scenario | Samples | Minimum | Median | Maximum |
| --- | ---: | ---: | ---: | ---: |
| Empty accepted v2 | 2 | 4,017 ms | 4,392.5 ms | 4,768 ms |
| One-consumption accepted v2 | 15 | 4,152 ms | 6,542 ms | 15,422 ms |

These ledger values may exclude the final ledger write and HTTP response routing. Client wall-clock and follow-up GET timings are measured separately in the sandbox phases.

## Phase 2 - instrumented baseline

Phase 2 is complete in the sandbox. Production remained untouched throughout this phase.

### Instrumented sandbox deployment

- The timing-instrumented backend was deployed as sandbox version `2` on the existing sandbox deployment ID and `/exec` URL; no endpoint changed.
- The instrumented responses expose additive timing data only in `SANDBOX`, while the structured execution log records the internal phase timings.
- The production-sized deterministic fixture contained 400 purchases and 3,600 canonical events with 3,600 corresponding Form-response rows.
- Fixture status totals were 24 active, 336 finished, and 40 unopened purchases. Total Purchases uses were 2,700.
- The fixture helper has strict sandbox identity checks and a normal-baseline restore path.

### Automated baseline result

The complete benchmark passed all 87 of 87 correctness records. This covered read-only GETs, empty v2 syncs, one-consumption syncs, duplicate retries, purchases, mixed requests, partial rejection, and three pairs of concurrent identical requests. Every concurrent pair produced exactly one commit and one duplicate result.

After the benchmark, the expected sandbox mutations were present: 410 purchases, 3,639 canonical events, 3,639 Form-response rows, 3,639 unique event UUIDs, 2,739 total uses, and 49 ledger rows.

All timing cells below are exact `minimum / median / maximum / p95` values from `performance_evidence/baseline.json`, in milliseconds. A p95 is present only for metrics with at least 20 samples. The `cold` labels mean a 35-second idle period was scheduled before each request; they do not prove that Google started a new server instance.

| Scenario | Label | Operation | Samples | Passed | Server | GET | POST | Follow-up GET | Combined |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| concurrent_identical | warm | POST+GET | 6 | 6 | 8122.0 / 11350.0 / 14796.0 / - | - | 9623.063 / 13102.709 / 16870.936 / - | 3209.977 / 3875.977 / 4557.636 / - | 14180.699 / 17155.457 / 20080.913 / - |
| duplicate_retry | warm | POST+GET | 10 | 10 | 3686.0 / 4923.5 / 8949.0 / - | - | 5547.553 / 6888.438 / 18356.016 / - | 2794.433 / 3703.772 / 6701.854 / - | 8731.312 / 11549.812 / 21749.693 / - |
| duplicate_seed | warm | POST+GET | 1 | 1 | 8403.0 / 8403.0 / 8403.0 / - | - | 10241.074 / 10241.074 / 10241.074 / - | 3555.053 / 3555.053 / 3555.053 / - | 13796.127 / 13796.127 / 13796.127 / - |
| empty_v2 | warm | POST+GET | 5 | 5 | 6044.0 / 6947.0 / 9203.0 / - | - | 7705.525 / 8832.421 / 11060.076 / - | 4221.372 / 5103.409 / 6186.818 / - | 11926.897 / 13839.545 / 17246.894 / - |
| get | cold | GET | 5 | 5 | 2702.0 / 2975.0 / 3238.0 / - | 4731.629 / 5390.987 / 6244.734 / - | - | - | - |
| get | warm | GET | 20 | 20 | 2590.0 / 3349.5 / 4743.0 / 4638.0 | 4121.714 / 5167.99 / 6804.973 / 6741.979 | - | - | - |
| mixed_purchase_consumption | warm | POST+GET | 5 | 5 | 7905.0 / 13446.0 / 14763.0 / - | - | 10916.517 / 16371.63 / 17162.379 / - | 3519.381 / 4371.018 / 6271.269 / - | 17187.786 / 20108.215 / 20742.648 / - |
| new_purchase | warm | POST+GET | 5 | 5 | 7481.0 / 8385.0 / 10172.0 / - | - | 9246.375 / 10376.419 / 12011.613 / - | 3143.532 / 4389.901 / 5193.606 / - | 13636.277 / 15155.145 / 15239.611 / - |
| one_consumption | cold | POST+GET | 5 | 5 | 9342.0 / 11079.0 / 12500.0 / - | - | 11305.092 / 13496.861 / 15578.469 / - | 4859.497 / 4998.766 / 7589.83 / - | 16303.858 / 18384.032 / 23168.299 / - |
| one_consumption | warm | POST+GET | 20 | 20 | 8414.0 / 10904.5 / 18411.0 / 14372.0 | - | 10286.036 / 13196.814 / 23691.282 / 16775.378 | 4273.739 / 4751.519 / 6915.324 / 6790.371 | 14896.392 / 18678.686 / 28343.078 / 23088.327 |
| partial_rejection | warm | POST+GET | 5 | 5 | 5973.0 / 8591.0 / 11057.0 / - | - | 7840.217 / 11298.26 / 13201.543 / - | 3657.329 / 4288.203 / 5224.013 / - | 13064.23 / 15016.633 / 17489.746 / - |

### Live Form and inventory-trigger verification

The initial Form checks exposed fixture-layout behavior, not backend failures:

1. After the benchmark, the synthetic/Android compatibility history occupied the top of `Form Responses 1`. Google Forms used its managed row 2 and overwrote the synthetic row there. The trigger still created one canonical `FORM` event and applied its use exactly once, but the response count did not increase and its lineage collided with the synthetic history.
2. After reseeding, the helper inserted one blank row before the synthetic history and adjusted canonical lineage. Google Forms' private next-row counter had already advanced, so it wrote at row 4 and collided again.
3. Widening the inserted reserve to 50 rows did not solve the issue: inserting above row 2 moved Google's managed pointer as well, and the response landed at row 55 inside the shifted synthetic history.

The safe fixture design therefore does not insert rows above row 2. It reseeds, clears and copies the 3,600 synthetic response rows down to physical rows 502-4101, shifts canonical source-row lineage by `+500`, and leaves rows 2-501 blank for Google Forms. This preserves deterministic lineage while reserving room for live Form submissions.

The final Form check then passed:

- The submitted `BASELINEFORM4` response appeared exactly once at physical row 56.
- Exactly one canonical event was created (`67a1d98a...`), and its source row was 56.
- There were 3,601 nonempty Form-response rows, and canonical event UUIDs and source-row lineage were both unique across all 3,601 events.
- Total uses changed from 2,700 to 2,700.5.
- Purchase `*P1B` changed from 7.5 to 8 uses and remained active.
- No sync-ledger row was created, as expected for a Form trigger rather than an HTTP sync.
- The visible Form submission took 728 ms in the browser.

The inventory-edit trigger also passed. Temporarily changing `C2` appended the `EDITTEST` marker to the Form help text. Restoring the original cell value restored the original Form help text. The Form was temporarily published and enabled only for this check, then returned to its prior unpublished/not-accepting-responses state.

### Phase 2 reflection

The baseline is reproducible, correctness checks are green, and the measurements show the intended optimization target clearly: a warm one-consumption POST had a 13,196.814 ms median client time, followed by a 4,751.519 ms median GET, for an 18,678.686 ms combined median. The sandbox fixture now supports both large synthetic history and real Google Form insertions without corrupting lineage. Phase 3 can proceed against this recorded baseline, with production still unchanged.

## Phase 3 - low-risk POST fast path

Phase 3 is complete. The optimized POST path was deployed to the existing sandbox
deployment as version 3, then the concurrency correction was deployed in place as
version 4. The deployment ID and `/exec` URL did not change.

### Implemented changes

- `doPost()` now parses and validates the basic request shape before opening the
  spreadsheet, opens the configured spreadsheet once, validates the environment
  and Config marker once, and reuses one request context inside the lock.
- Normal HTTP execution uses read-only runtime schema assertions. Sheet creation,
  column insertion, formatting, frozen rows, and validation repair remain limited
  to explicit provisioning and maintenance paths.
- Empty v2 requests skip Purchases and ConsumptionEvents data reads while still
  writing their required SyncLedger record. Purchase-only requests skip event
  history, and consumption-only requests avoid purchase-duplicate structures.
- Event duplicate lookup is restricted to the Event UUID column. Batches of at
  most five use exact whole-cell TextFinder searches; larger batches and
  maintenance scans read that one column into a Set.
- Product validation and resolution reuse the same context, including physical
  row numbers when blank rows exist. Accepted events are aggregated by product,
  then only `Finished`, `Uses`, `Most recent use`, and `Finished At` are written
  for affected rows.
- A strictly newer timestamp is required to replace the most-recent value, so an
  out-of-order older event cannot move it backward. The already-deployed
  append-order `Finished At` behavior is preserved rather than silently changing
  the API contract during a performance patch.
- SyncLedger duplicate lookup searches only the Request UUID column. The final
  upsert uses the current grid, appends only when no exact match exists, and
  flushes before releasing the shared lock.

### Measured Event UUID lookup decision

The read-only Apps Script microbenchmark ran against 3,601 sandbox event rows.
Every strategy returned the expected result, and the UUID checksum remained
`dd087577` before and after the run.

| Batch | First Set | First TextFinder | Warm Set median | Warm TextFinder median |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 562 ms | 103 ms | 491 ms | 142 ms |
| 5 | 432 ms | 585 ms | 575 ms | 548 ms |
| 10 | 459 ms | 1,046 ms | 460 ms | 1,111 ms |
| 20 | 502 ms | 3,209 ms | 461 ms | 2,777 ms |

The exact raw samples and min/median/max values are preserved in
`performance_evidence/phase3_uuid_lookup.json` and
`performance_evidence/phase3_uuid_lookup.md`. The result supports the threshold of
five: TextFinder clearly wins the normal one-item case, the strategies are close
at five, and a one-column Set clearly wins at ten and twenty. A separate durable
UUID index is not justified.

### Automated safety proof

The following checks pass in the implementation worktree:

- syntax checks for `backend_additions.gs`, `sandbox_performance_fixture.gs`, and
  the fake Apps Script runtime;
- backend contract tests;
- 3,600-event fake-spreadsheet integration tests;
- sandbox fixture and lookup-benchmark tests; and
- all 13 Python benchmark-harness unit tests.

The fake-spreadsheet tests prove that a normal one-consumption request opens one
spreadsheet, uses one range-restricted TextFinder without reading event data,
reads only SyncLedger column A, performs no structural writes, and changes only
the intended product cells. They also cover the larger-batch one-column Set path,
empty and purchase-only requests, mixed temporary-product requests, duplicates
including conflicting content, validation failures, schema mismatch, and the
legacy v1 path.

### Full optimized sandbox benchmark

The exact same deterministic dataset, request namespace, timestamps, and scenario
counts used for the baseline were rerun against version 3. All 87 of 87
request-level correctness records passed.

| Warm one-consumption metric | Baseline median | Phase 3 median | Change |
| --- | ---: | ---: | ---: |
| Server POST | 10,904.5 ms | 5,417 ms | 50.3% faster |
| Client POST | 13,196.814 ms | 7,289.641 ms | 44.8% faster |
| Follow-up GET | 4,751.519 ms | 4,871.752 ms | 2.5% slower |
| Combined POST + GET | 18,678.686 ms | 12,252.782 ms | 34.4% faster |

The POST p95 improved by 47.38%, while combined p95 improved by 38.61%.
Standalone warm GET improved only 3.27%, as expected before the dedicated GET
change. Exact results are in `performance_evidence/phase3_optimized.json` and
`performance_evidence/phase3_optimized.md`.

### Concurrency defect found and corrected

A direct sheet-level audit after the otherwise-green version 3 run found 49
SyncLedger rows but only 48 unique Request UUIDs. Consumption history and Uses
were still exactly once, but a waiting duplicate request had appended a second
ledger row because the prior final write was not yet durably visible.

The correction uses an exact Request UUID TextFinder over the current ledger grid,
`appendRow()` only for no-match, and `SpreadsheetApp.flush()` before lock release.
It was deployed as sandbox version 4. After a fresh fixture seed, a focused test
ran ten concurrent identical pairs:

- 20 of 20 HTTP records passed;
- every pair returned exactly one `committed` and one `duplicate`;
- SyncLedger contained exactly 10 rows and 10 unique Request UUIDs;
- canonical history contained exactly 10 new events, one per pair;
- Form-compatible history contained exactly the same 10 rows; and
- Uses increased exactly 10 times.

Evidence is preserved in
`performance_evidence/phase3_ledger_concurrency_fix.json` and
`performance_evidence/phase3_ledger_concurrency_fix.md`.

### Phase 3 reflection

The POST goal was met without changing response or duplicate semantics. The
direct sheet audit also proved why response-only checks are insufficient and is
now part of later rollout verification. The remaining combined-latency gap is the
unchanged GET history scan, exactly the planned Phase 4 target.

## Implementation decisions

- Keep `ConsumptionEvents` authoritative; do not add a dedicated UUID index.
- Do not use CacheService as correctness state.
- Phase 3 intentionally stayed on the built-in spreadsheet service. Phase 4b
  enables the Advanced Sheets service because its single `batchUpdate` request
  provides the all-or-nothing multi-sheet commit that the built-in service
  cannot provide.
- Preserve existing public response, duplicate, ledger, and finished-state
  semantics while changing only how much spreadsheet data is touched.

## Sandbox verification

- Active sandbox deployment: version 10,
  `Phase 9 final promotion candidate smoke`.
- Version 9 is the performance-tested hot path. Version 10 adds only the
  read-only public product-reconciliation wrapper used by the production gate.
- Immediate tested rollback version: version 5,
  `Phase 4 compact GET projection`.
- Deployment ID:
  `AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA`.
- The existing sandbox `/exec` URL is unchanged.
- Phase 3 and Phase 4 correctness, concurrency, fault-recovery, Form, and direct
  sheet-level audits are green.

## Phase 4 - compact GET interaction projection

The Phase 4 projection rollout is complete in the sandbox. The remaining
atomic/recoverable write-path work and five-stage fault injection from the same
prompt phase are tracked below as Phase 4b. Production remained untouched
during this phase.

The selected projection adds one trailing Purchases field, `Last quantity`,
beside the existing durable `Most recent use` state. Before migration, columns R
through Z were inspected across every Purchases row in both sandbox and
production; they contained no values, formulas, typed cells, validations, or
user-owned data. Sandbox column R now contains the migrated projection.
Production R through Z remain unchanged.

### Implemented projection behavior

- an explicit, locked, idempotent two-step rollout: prepare the projection with
  the fallback still active, deploy the compatible code, then perform a final
  locked rebuild and enable from canonical ConsumptionEvents;
- strict-greater timestamp comparison, so the earlier canonical row retains its
  quantity when timestamps are equal;
- a read-only reconciliation function that reports every product mismatch;
- a versioned Config readiness marker with a legacy-history fallback and an
  explicit rollback switch;
- direct ready-state GET reads from Purchases only;
- paired timestamp/quantity validation, including valid zero quantities;
- Android and Google Form updates through the same projection calculation; and
- a sandbox fixture updated to carry and verify all 360 expected interaction
  summaries.

The reviewed source identities used for the rollout were:

- `backend_additions.gs` SHA-256
  `830A032551DF6410F34FF5B7B760A15BBB30F22A9E513164D20558596A3F395A`;
- `sandbox_performance_fixture.gs` SHA-256
  `4F1EBD502C7FEF1A6A113E2C325D6B0B300C8876821994047A4EB8F34A6EFADD`;
- corrected sandbox-only `sandbox_provisioning.gs` SHA-256
  `AC2DA1226AE373DA231AF9558CED781727921FE8E820AB0BD94E0C1A0F2D0396`.

### Migration and enablement

The preparation migration ran while version 4 was still active. It left the
Config marker at `0`, so GET continued to use the legacy fallback. The result
was:

- 400 purchases;
- 3,610 canonical events, all valid;
- 360 populated product summaries;
- zero legacy-comparison differences;
- zero reconciliation differences; and
- `readyToEnable=true`, `fastPathEnabled=false`.

The same existing deployment was then updated in place to version 5. A final
locked rebuild ran after deployment and changed
`INTERACTION_SUMMARY_VERSION` from `0` to `1`. It again found 3,610 valid
events, 360 populated summaries, and zero differences before reporting
`fastPathEnabled=true`.

### Structural read reduction

On the production-sized fixture, the legacy GET read 53,600 data cells:
6,800 Purchases cells (`400 x 17`) plus 46,800 canonical-event cells
(`3,600 x 13`). The ready-state GET reads 7,200 Purchases cells
(`400 x 18`) and zero event-history cells. That is 46,400 fewer cells, an
86.57% reduction in the normal projection read.

### Live Android and Form verification

The performance fixture was first returned to exactly 3,600 events and zero
ledger rows. Product `*P1B` started at 7.5 Uses, latest interaction
`2025-01-30 14:00`, and last quantity 1.

A real Google Form submission was temporarily enabled and submitted. It created
response row 57 and one deterministic canonical `FORM` event, changed Uses from
7.5 to 7.75, and changed the latest quantity to 0.25. As expected, the Form
path did not create a SyncLedger row.

Three Android-v2 events then exercised ordering:

- event `66000000-0000-4000-8000-000000000001` established the exact
  second-resolution latest timestamp with quantity 1.5;
- older event `66000000-0000-4000-8000-000000000002` added 2 Uses without
  moving the latest timestamp or quantity backward; and
- equal-timestamp event `66000000-0000-4000-8000-000000000003` added 0.75
  Uses while preserving the earlier canonical row's quantity 1.5.

All three requests were accepted and each produced one ledger row. Product
`*P1B` finished at 12 Uses with the expected timestamp and last quantity.
Reconciliation over all 3,604 canonical events reported zero invalid events and
an empty differences list.

The Form was then disabled, unpublished, and emptied again. The corrected
normal sandbox reset was run and verified at six purchases, five compatibility
rows, five unique canonical events, 5.25 total Uses, statuses 3 active / 2
finished / 1 unopened, zero ledger rows, marker `1`, and zero interaction
summary differences. The 400-purchase / 3,600-event performance fixture was
then re-seeded for the remaining phases, again with all 360 summaries populated
and zero mismatches.

The sandbox-only reset helper was also corrected to pad legacy 17-value fixture
rows to the 18-column Purchases schema, rebuild and re-enable the compact
projection, and fail if reconciliation is not clean.

### Separate GET benchmark

The GET-only benchmark used the same baseline namespace, 35-second cold
interval, five cold samples, and 20 warm samples. All 25 of 25 correctness
records passed.

| GET metric | Baseline | Phase 4 | Change |
| --- | ---: | ---: | ---: |
| Warm client median | 5,167.990 ms | 3,270.518 ms | 36.72% faster |
| Warm server median | 3,349.5 ms | 1,557 ms | 53.52% faster |
| Warm client p95 | 6,741.979 ms | 4,356.193 ms | 35.39% faster |
| Cold client median | 5,390.987 ms | 3,578.342 ms | 33.62% faster |
| Cold server median | 2,975 ms | 1,867 ms | 37.24% faster |

The remaining warm median gap between measured server work and client wall
time is about 1,713.518 ms and is attributable to the Apps Script/platform and
network path rather than the removed history scan. Exact samples are preserved
in `performance_evidence/phase4_compact_get.json` and
`performance_evidence/phase4_compact_get.md`.

### Phase 4 projection reflection

The read-model change met its goal without changing the endpoint or public
response. Migration, rollback marker, Android writes, Form writes,
equal/older timestamps, normal reset, and direct sheet reconciliation all
passed. The plan remains valid.

### Phase 4b - recoverable multi-sheet writes

Phase 4b is complete in the sandbox. It replaces a sequence of independently
visible spreadsheet writes with a recoverable two-batch protocol:

1. one atomic core batch updates Purchases, compatibility history, canonical
   history, the compact interaction summary, and a durable journal row;
2. an idempotent Form refresh runs only when required; and
3. one atomic final batch writes canonical lineage, the SyncLedger result, marks
   the journal `COMPLETE`, and clears the pending pointer.

The `SyncApplyJournal` sheet and the versioned Config keys
`RECOVERABLE_SYNC_APPLY_VERSION` and `PENDING_APPLY_KEY` are additive. Version
`0` leaves the new path disabled; version `1` enables it only after migration
and reconciliation pass. The explicit repair command can finish an interrupted
request from its journal without replaying product effects. It also finds an
orphan Google Form response, assigns a deterministic Event UUID, and projects
it exactly once.

The core and final spreadsheet batches use the Advanced Sheets
`spreadsheets.batchUpdate` API. Canonical and journal append rows are allocated
under the script lock. Android compatibility history resolves its newly
appended identity row with a fresh, narrow Advanced Sheets values read. This
last detail was required because the built-in Apps Script spreadsheet cache can
remain stale immediately after an Advanced Sheets write. Sandbox versions 6
and 7 exposed that behavior; version 8 contains the corrected fresh-tail
resolution.

The source identities at the version 8 Phase 4b checkpoint were:

- `backend_additions.gs` SHA-256
  `065E503674C9F8DCEAD42B09AF919022E8843041D137403D8EA2043E7E0922C5`;
- `sandbox_performance_fixture.gs` SHA-256
  `8C2B077D151415F3D5A434831092655AE7506D1B4A217ACD41AB5099C1DE76DC`;
- `sandbox_provisioning.gs` SHA-256
  `F1EA041DD072C827FDE44C082C2FF413A02140897819CFBD328A8C844A148F26`;
  and
- `appsscript.json` SHA-256
  `10A3C76D80B40039CB4DAD2F43C08DA170C883068871DB35A2CA7D303D7ECFCA`.

### Migration and normal smoke test

Preparation created the journal, appended compatibility identity headers at
columns H and I, backfilled all 3,600 fixture identities, and reported zero
blocking differences while the Config marker remained `0`. The existing
sandbox deployment was then updated in place and the marker enabled at `1`.

A normal Android-v2 smoke request created one compatibility row, one canonical
event, one ledger row, one `COMPLETE` journal, correct lineage, and one product
effect. The pending pointer was blank afterward. An idempotent repair pass then
reported no pending work and no orphan Form rows.

### Seven-stage fault-injection result

All seven deliberate failure points passed:

- after compatibility preparation;
- after canonical preparation;
- after product-effect preparation;
- after interaction-summary preparation;
- after the atomic core commit;
- before the final ledger batch; and
- after the durable `COMPLETE` state.

The first four failures invalidated the atomic core request, so no partial row
or product mutation appeared. Retrying then committed exactly once. Failures
after the core commit left one durable core result and a recoverable journal;
explicit repair completed lineage and ledger without incrementing Uses twice.
The failure after `COMPLETE` simulated a lost HTTP response: the first call had
already committed, and the identical retry returned `duplicate`.

After the suite there were 3,608 canonical events, 11 completed journals, zero
incomplete journals, a blank pending pointer, and no reconciliation or blocking
differences.

### Real Google Form recovery result

The Form was temporarily published and a real submission was made with the
compatibility-stage fault armed. Google Forms wrote native response row 59, but
the injected failure left its identity blank and created no canonical event,
journal, ledger, or product effect. The repair command then:

- assigned deterministic Event UUID
  `d9f86bd8-5132-595e-8a1f-5476d62d3215`;
- created exactly one `FORM_RECOVERY` canonical event pointing to response row
  59;
- increased the product's Uses once, from 1.5 to 2.25;
- wrote one `COMPLETE` recovery journal; and
- left the ledger unchanged, as required for a Form rather than HTTP request.

Reconciliation found no differences or blockers. The Form was then unpublished,
its native responses were cleared by reseeding, and the production-sized
performance fixture was restored exactly.

### Phase 4b reflection

The lock still serializes competing syncs, while the journal and atomic batches
now make every accepted request either invisible, durably recoverable, or
complete. Faults cannot leave only half of canonical history and product state,
and retries cannot apply Uses twice. Production remained untouched.

## Phase 5 - optional caching decision

No CacheService layer was added. The structural read reductions already remove
the expensive whole-history work, while correct cache invalidation would have
to cover Android syncs, purchases, Form submissions, inventory edits,
migration, provisioning, resets, and concurrent mutation. A cache would add
failure modes without being allowed to become correctness state. This phase is
therefore complete with caching deliberately rejected.

## Final structural service and range comparison

The following exact footprints use the same 400-purchase/3,600-event sandbox
fixture as the timed benchmark. They count scale-dependent body ranges rather
than fixed header and Config checks. A TextFinder range is counted by its
one-column search span; this does not claim that Google downloads every searched
cell to the script.

| Scenario | Before | Final | Change |
| --- | ---: | ---: | ---: |
| Normal one-consumption body read/search footprint | 60,400 cells | 10,801 cells | 82.12% smaller |
| Normal one-consumption explicit write footprint | 1,628 cells | 51 cells | 96.87% smaller |
| Exact duplicate body read/search footprint | 53,621 cells | 10,802 cells | 79.85% smaller |
| Exact duplicate explicit write footprint | 8 cells | 8 cells | ledger-only no-op path |
| Accepted empty-v2 body read footprint | 53,600 cells | 0 scalable body cells | full-history reads removed |
| Accepted empty-v2 explicit write footprint | 8 cells | 8 cells | ledger-only no-op path |
| GET body read footprint | 53,600 cells | 7,200 cells | 86.57% smaller |

Before optimization, a normal one-consumption request opened the spreadsheet
twice, read the full Purchases table twice and full canonical history once,
issued seven independent value writes, and repeated four structural frozen-row
operations. The final path opens once, reads Purchases once, searches only the
Event UUID column, performs one fresh one-cell compatibility identity read, and
uses two atomic Sheets batches with no HTTP-path structural mutation.

Duplicate, empty, and rejected-only requests have no newly accepted purchase or
event, so their only durable change is the locked, idempotent eight-cell ledger
upsert. They skip the journal and both atomic batches. Pending work from an
earlier real mutation is still repaired before this shortcut is considered.

## Phase 6 - correctness and client-contract regression

The final local backend implementation passes:

- JavaScript syntax validation;
- backend contract tests;
- 3,600-event fake-spreadsheet integration tests;
- the Advanced Sheets atomic-batch simulator;
- every recovery and fault-injection test;
- sandbox fixture and provisioning tests; and
- all 13 Python benchmark-harness unit tests.

A final live simultaneous-request test sent two byte-identical Android-v2
requests at the same time. Both HTTP records passed: one returned `committed`,
the other `duplicate`. Direct sheet inspection found one compatibility row, one
canonical event, one product increment, one ledger row, two completed journal
records, correct lineage, and a blank pending pointer. Reconciliation reported
3,601 canonical rows, zero incomplete journals, and no differences. Exact
evidence is in `performance_evidence/phase6_concurrent_identical.json` and
`.md`. The 400-purchase/3,600-event fixture was then restored.

Android and Gradle source are byte-for-byte unchanged from the intake commit.
The relevant existing tests are `SyncQueueLogicTest`,
`EnvironmentContractTest`, and `SyncFailureStatusTest`. Two capped local Gradle
attempts stalled while calculating the task graph and produced no test-result
files or remaining Java process. The repository's current release workflow
builds the APK but does not run unit tests, so no Android unit-test pass is
claimed. This is recorded as a local tooling limitation rather than hidden;
there is no Android implementation or release in this backend-only scope.

## Phase 7 - final performance acceptance

The final source was deployed to the existing sandbox endpoint as version 9,
`Phase 7 no-op ledger fast path`. Its SHA-256 is
`4E74C83D69FE7EBA89B39F343750BAD5610022F10B46DB43C4688F0C93361C01`.

The first final run showed that real commits were fast enough but empty and
duplicate-only requests still paid for an unnecessary journal and two atomic
batches. The final change keeps validation, locking, pending-work recovery, and
the idempotent ledger acknowledgement, while bypassing those mutation batches
only when the request contains no newly accepted purchase or event. Real
mutations continue through the recoverable two-batch protocol.

After a clean reseed, the same deterministic suite passed all 97 of 97 records:

| Acceptance metric | Baseline | Final | Change / result |
| --- | ---: | ---: | ---: |
| Warm one-consumption client POST median | 13,196.814 ms | 7,150.037 ms | 45.82% faster |
| Warm one-consumption combined median | 18,678.686 ms | 10,788.764 ms | 42.24% faster |
| Warm one-consumption client POST p95 | 16,775.378 ms | 10,581.904 ms | 36.92% faster |
| Warm one-consumption combined p95 | 23,088.327 ms | 14,089.213 ms | 38.98% faster |
| Warm GET client median | 5,167.990 ms | 3,162.749 ms | 38.80% faster |
| Warm GET client p95 | 6,741.979 ms | 3,831.513 ms | 43.17% faster |
| Duplicate-only client POST median | - | 5,568.843 ms | faster than a new commit |
| Empty-v2 server median | 4,392.5 ms historical production | 2,470 ms | measurably faster |

The final warm one-consumption server median was 5,276 ms. Maximum observed
warm POST and combined times were 10,982.361 ms and 14,902.722 ms, comfortably
below the Android 60-second read timeout. Cold GET and cold one-consumption
medians were recorded separately at 3,739.309 ms and 8,458.765 ms client time;
the cold combined median was 11,700.221 ms.

All three simultaneous identical pairs returned exactly one `committed` and one
`duplicate` acknowledgement. The post-run direct audit found 3,639 matching
canonical and compatibility rows, 49 ledger rows, 44 completed journal rows,
no pending pointer, no incomplete journal, and no reconciliation difference.
Exact results are in `performance_evidence/phase7_final_v9.json` and `.md`; the
focused shortcut proof is in `phase7_noop_fast_path_smoke.json` and `.md`.

### Phase 7 reflection

Every mandatory structural, correctness, latency, duplicate, empty-request, p95,
and timeout gate now passes. The plan can safely advance to rollback proof and
normal sandbox restoration.

## Phase 8 - sandbox rollback proof and restoration

The rollback path was exercised before production promotion:

1. The recoverable-write marker was disabled with no pending apply.
2. The existing deployment was edited in place from optimized version 9 to
   rollback version 5. Its deployment ID and `/exec` URL did not change.
3. A read-only GET returned HTTP 200, API version 2, `SANDBOX`, and all 410
   fixture products in 1,578 ms server / 3,289.302 ms client time.
4. Exact before/after reads proved zero mutation: 410 purchases, 3,639
   compatibility rows, 3,639 canonical events, 49 ledger rows, and 44 journal
   rows were unchanged.
5. The same deployment was restored to version 9, the marker was re-enabled, and
   reconciliation found zero incomplete journals, differences, or blockers.

The normal deterministic reset then restored exactly six purchases, five
compatibility rows, five unique canonical events, 5.25 total Uses, status totals
3 active / 2 finished / 1 unopened, zero ledger rows, zero journal rows, zero
migration rows, both optimization markers at version 1, and a blank pending
pointer. A final reconciliation reported five canonical rows and no difference
or blocker. The linked Form is unpublished and shows zero responses.

Exact proof is in `performance_evidence/phase8_rollback_proof.json` and `.md`;
the rollback-version GET is in `phase8_rollback_get.json` and `.md`.

The final read-only reconciliation wrapper was then smoke-tested as sandbox
version 10. It changed no hot-path behavior and returned zero product,
interaction-summary, or recoverable-apply differences.

### Phase 8 reflection

The sandbox is back at its small, known-good baseline, the optimized version is
active again, and the tested rollback target works on the same endpoint. At
this checkpoint all sandbox gates were closed, allowing production promotion
only through the guarded migration and stop conditions documented below.

## Phase 9 - production promotion and genuine-sync verification

Production promotion is complete. The existing deployment was edited in place
from version 7 to version 8, `Backend sync performance and recoverable atomic
apply`. The deployment ID, `/exec` URL, execution identity, and public access
setting did not change. The final local source SHA-256 is
`909F9E4385100037DB3ACCFFBD8B545DC154FC0EBC712C8947B557A85D9E3EAF`;
the final manifest SHA-256 is
`F3A1DF8363103618B9FA77E6039818F2B6219D2037686FE7C61F152A83E6F975`.
The live editor source and manifest match those files after newline
normalization.

### Fresh rollback point

- Drive spreadsheet copy:
  `CannsheetG Production Backup 2026-07-17 21-56-01 EDT - before backend sync performance promotion`
  (`15OT5fu0qzVuA11srEJ7o-Siqq2xLwTrTXLTtmZMf45M`)
- Local source, manifest, and spreadsheet:
  `C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-17\backend-sync-performance\production-before-promotion-21-56-01-EDT`
- Pre-promotion deployment rollback version: 7 on the same deployment ID
- Post-promotion snapshot:
  `C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-17\backend-sync-performance\production-after-promotion-version-8\CannsheetG-after-promotion-before-real-sync.xlsx`

### Guarded migration and known-data repair

The interaction-summary migration read 3,572 valid canonical events and found
no legacy or projection difference. Recoverable-write preparation safely
backfilled 3,570 row identities, then stopped on the two previously known
lineage errors. The explicit repair:

- relinked the two mismatched compatibility rows;
- canonicalized the one interrupted `*J127` use exactly once;
- increased `*J127` Uses from 3 to 4;
- created deterministic repair event
  `dfabd249-0d95-52a1-8b2b-70a5b60d93e4`; and
- left no unresolved row, duplicate UUID, incomplete journal, or pending apply.

The repair helper's same-execution check initially reported one stale product
value immediately after its Advanced Sheets batch. The rollout stopped at that
gate. A new execution then scanned all 329 products and returned zero
differences; fresh interaction-summary and recovery checks were also clean.
This was a Google Apps Script same-execution cache limitation, not durable data
drift, so rollback was not warranted.

Both Config markers were enabled only after those fresh checks passed. The Form
was refreshed from seven stale product choices to the exact nine active
products, adding the already-active `*S69` and `*P94`.

### Zero-mutation production checks

A GET returned HTTP 200, API version 2, `PRODUCTION`, and 329 products.
Malformed JSON, an unsupported API version, and a wrong-environment request were
all rejected with their expected error codes. Exact before/after hashes proved
that none of the ten sheets changed. No accepted empty production request was
sent.

### First genuine production sync

The next real app request was observed rather than replaced by synthetic data:

- Request UUID: `8ca578a3-e553-4a6e-9161-93cf07946fff`
- Event UUID: `c9fd36d1-93b2-4594-bea4-a4f6673284f3`
- Apply UUID: `eac357b0-4d66-4ce5-8404-eb34d4121210`
- Product and quantity: `*P94`, 2 uses
- Acknowledgement: success, all accepted, one `committed` consumption
- SyncLedger duration: 6,355 ms

An exact spreadsheet comparison found changes only in the five expected sheets:
Purchases, Form Responses 1, ConsumptionEvents, SyncLedger, and
SyncApplyJournal. The request produced one compatibility row, one canonical
event with correct lineage, one unique ledger row, one complete journal row,
and a single Uses change from 53 to 55. Config and every unrelated sheet were
unchanged.

The corresponding Apps Script executions completed on production version 8:
`doPost` in 8.676 seconds and the app's follow-up `doGet` in 2.351 seconds. The
6,355 ms ledger duration is slightly below the 6,542 ms historical
one-consumption median, but one live request is not enough for a statistical
performance claim. The controlled sandbox results remain the acceptance proof.

After that real sync, fresh reconciliations reported:

| Metric | Final production value |
| --- | ---: |
| Purchases | 329 |
| Active / finished / unopened | 9 / 320 / 0 |
| Compatibility rows | 3,574 |
| Canonical events / unique UUIDs | 3,574 / 3,574 |
| Purchases total Uses | 5,401.51 |
| SyncLedger rows / unique request UUIDs | 39 / 39 |
| SyncApplyJournal complete / incomplete | 2 / 0 |
| Pending apply | blank |
| Product projection differences | 0 |
| Interaction-summary differences | 0 |
| Recoverable-apply differences / blockers | 0 / 0 |

The post-sync workbook is preserved at
`C:\Users\noamv\Downloads\cannsheet-mobile-main\backups\2026-07-17\backend-sync-performance\production-after-promotion-version-8\CannsheetG-after-first-real-sync.xlsx`
with SHA-256
`82189CCB0607E1E04498A758F9FD99766A96AC3BEB2E4B9A71B517A438B5215E`.

The final live trigger check still shows exactly two HEAD triggers:
`onInventoryEdit` for spreadsheet edits and `onFormSubmit` for spreadsheet form
submissions. Script Properties are unchanged and still point to the production
Sheet, Form, and environment.

### Remaining overhead and final scope status

The structural whole-history scans and broad write paths were removed, but
Google Apps Script still contributes startup, routing, locking, and spreadsheet
service overhead. The current Android client also performs a follow-up GET after
a successful POST; changing that client behavior would be a separate,
explicitly authorized Android task. The migration helper's fresh-execution
recheck requirement is retained because of the observed same-execution
SpreadsheetApp cache behavior.

| Area | Final status |
| --- | --- |
| Local backend source | Implemented and fully tested on branch `backend-sync-performance`; final raw source SHA-256 `909F9E4385100037DB3ACCFFBD8B545DC154FC0EBC712C8947B557A85D9E3EAF` |
| Sandbox | Version 10 active on the unchanged sandbox endpoint; normal six-product baseline restored and clean |
| Production | Version 8 active on the unchanged production endpoint; migration, rejection probes, genuine sync, and final reconciliation all clean |
| Android | Source, package, signing, and release unchanged; no APK was built or published |

### Phase 9 reflection

All required production gates are closed. Version 8 is live on the original
endpoint, the known pre-existing drift was repaired deterministically, rejection
checks were mutation-free, and a genuine app sync completed exactly once with a
clean final reconciliation. No Android source or release was changed.
